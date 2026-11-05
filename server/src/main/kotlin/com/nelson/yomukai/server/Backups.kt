package com.nelson.yomukai.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Nightly copies of the SQLite database: `yomukai-AAAAMMDD-HHMMSS.db.gz` in [dir], kept [keepDays] days.
 * When [copyDir] is set (a folder of the NAS or of the home worker, mounted over Tailscale) every copy is also
 * placed there whenever that folder is reachable; copies missed while it was not are caught up the next time.
 */
class Backups(
    private val dbFile: File,
    val dir: File,
    private val keepDays: Int = 14,
    private val copyDir: File? = null,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val log = LoggerFactory.getLogger("Backups")

    /** Makes one backup now; returns the file. */
    suspend fun backup(db: Database, now: Instant = Instant.now()): File {
        dir.mkdirs()
        val name = nameFor(now, zone)
        val raw = File(dir, "$name.tmp")
        raw.delete()
        db.backupTo(raw)
        val target = File(dir, name)
        gzip(raw, target)
        raw.delete()
        prune(dir, now)
        copyToRemote(now)
        return target
    }

    /** Copies to [copyDir] every backup it is missing, if the folder is there. Never fails the backup. */
    fun copyToRemote(now: Instant = Instant.now()) {
        val remote = copyDir ?: return
        if (!remote.isDirectory || !remote.canWrite()) {
            log.debug("Carpeta de copia remota {} no disponible ahora; se copiará cuando vuelva", remote)
            return
        }
        runCatching {
            list(dir).filter { !File(remote, it.name).exists() }.forEach { local ->
                val partial = File(remote, "${local.name}.partial")
                local.copyTo(partial, overwrite = true)
                Files.move(partial.toPath(), File(remote, local.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            prune(remote, now)
        }.onFailure { log.warn("No se pudo copiar la copia de seguridad a {}", remote, it) }
    }

    private fun prune(folder: File, now: Instant) {
        expired(list(folder).map { it.name }, now, keepDays, zone).forEach { File(folder, it).delete() }
    }

    /** Runs [backup] every day at [hour] (server time zone); in between, retries the remote copy once an hour. */
    fun schedule(scope: CoroutineScope, db: Database, hour: Int) = scope.launch {
        var next = nextRun(LocalDateTime.now(zone), hour)
        while (true) {
            val untilNext = Duration.between(LocalDateTime.now(zone), next).toMillis()
            delay(untilNext.coerceIn(1_000, 60 * 60_000L))
            if (LocalDateTime.now(zone).isBefore(next)) {
                if (copyDir != null) copyToRemote()
                continue
            }
            next = nextRun(LocalDateTime.now(zone), hour)
            runCatching { backup(db) }
                .onSuccess { log.info("Copia de seguridad hecha: {}", it.name) }
                .onFailure { log.error("La copia de seguridad nocturna falló", it) }
        }
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val NAME = Regex("""yomukai-(\d{8}-\d{6})\.db\.gz""")

        fun nameFor(at: Instant, zone: ZoneId): String = "yomukai-${FORMAT.format(at.atZone(zone))}.db.gz"

        fun timeOf(name: String, zone: ZoneId): Instant? = NAME.matchEntire(name)?.let {
            runCatching { LocalDateTime.parse(it.groupValues[1], FORMAT).atZone(zone).toInstant() }.getOrNull()
        }

        /** Backups in [folder], newest first. Other files there are left alone. */
        fun list(folder: File): List<File> =
            folder.listFiles().orEmpty().filter { NAME.matches(it.name) && it.isFile }.sortedByDescending { it.name }

        /** Names older than [keepDays], except the newest one, which is always kept so there is never no backup at all. */
        fun expired(names: List<String>, now: Instant, keepDays: Int, zone: ZoneId): List<String> {
            val dated = names.mapNotNull { n -> timeOf(n, zone)?.let { n to it } }.sortedByDescending { it.second }
            val limit = now.minus(Duration.ofDays(keepDays.toLong()))
            return dated.drop(1).filter { it.second.isBefore(limit) }.map { it.first }
        }

        fun nextRun(now: LocalDateTime, hour: Int): LocalDateTime {
            val today = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
            return if (today.isAfter(now)) today else today.plusDays(1)
        }

        private fun gzip(source: File, target: File) {
            val partial = File(target.parentFile, "${target.name}.partial")
            GZIPOutputStream(partial.outputStream().buffered()).use { out -> source.inputStream().use { it.copyTo(out) } }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        /**
         * Puts [backup] (.db.gz or a plain .db) in place of [dbFile]. The server must be stopped: [ServerLock] checks it.
         * The backup is checked first (opens, passes integrity_check, schema not newer than this program), and the
         * current database is kept beside it as `yomukai.db.antes-de-restaurar-…`, so a restore can be undone.
         */
        fun restore(backup: File, dbFile: File): File? {
            require(backup.isFile) { "No existe la copia $backup" }
            val dir = dbFile.absoluteFile.parentFile.apply { mkdirs() }
            val candidate = File(dir, "${dbFile.name}.restaurando")
            candidate.delete()
            if (backup.name.endsWith(".gz")) {
                GZIPInputStream(backup.inputStream().buffered()).use { input -> candidate.outputStream().use { input.copyTo(it) } }
            } else {
                backup.copyTo(candidate, overwrite = true)
            }
            try {
                verify(candidate)
            } catch (e: Exception) {
                candidate.delete()
                throw e
            }
            var previous: File? = null
            if (dbFile.exists()) {
                previous = File(dir, "${dbFile.name}.antes-de-restaurar-${FORMAT.format(LocalDateTime.now())}")
                Files.move(dbFile.toPath(), previous.toPath())
            }
            // A WAL left by the old database would be replayed onto the restored one: move it aside with it.
            listOf("-wal", "-shm").forEach { suffix ->
                val side = File(dir, dbFile.name + suffix)
                if (side.exists()) {
                    if (previous != null) Files.move(side.toPath(), File(dir, previous.name + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    else side.delete()
                }
            }
            Files.move(candidate.toPath(), dbFile.toPath())
            return previous
        }

        private fun verify(file: File) {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                val check = c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) } }
                check(check == "ok") { "La copia está dañada: $check" }
                val tables = c.columnsOf("users")
                check(tables.isNotEmpty()) { "La copia no es una base de datos de Yomukai" }
                val version = if (c.columnsOf("schema_version").isEmpty()) 0 else Migrations.currentVersion(c)
                check(version <= Migrations.LATEST) {
                    "La copia es de una versión más nueva de Yomukai (esquema $version); actualiza antes de restaurarla"
                }
            }
        }
    }
}

/**
 * Held by the running server on `data/.lock`, so `restore` refuses to swap the database under it.
 * File locks are seen across processes and across containers sharing the same volume.
 */
class ServerLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    companion object {
        fun tryAcquire(dataDir: File): ServerLock? {
            dataDir.mkdirs()
            val raf = RandomAccessFile(File(dataDir, ".lock"), "rw")
            val lock = runCatching { raf.channel.tryLock() }.getOrNull()
            if (lock == null) {
                raf.close()
                return null
            }
            return ServerLock(raf, lock)
        }
    }
}

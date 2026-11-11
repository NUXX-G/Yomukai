package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Kinds of heavy work done by the home workers, never by the VPS. The names travel to the worker: never rename. */
object JobTypes {
    const val TTS = "tts"
    const val OCR = "ocr"
    const val TRANSLATE = "translate"
    const val WHISPER = "whisper"
    const val AUDIO_FINGERPRINT = "audio_fingerprint"
    const val JELLYFIN_DOWNLOAD = "jellyfin_download"
    const val CBZ_EXPORT = "cbz_export"
    const val DICTIONARY = "dictionary"

    /** Covers and banners at every width, made at home (F2.2). */
    const val COVERS = "covers"

    /** Test job: the worker sends the payload back. Used by the admin's "probar worker" button. */
    const val ECHO = "echo"

    /** A broken source: the worker proposes a fix as a GitHub pull request (scripts/auto-repair.sh). */
    const val SOURCE_REPAIR = "source_repair"

    val ALL = setOf(TTS, OCR, TRANSLATE, WHISPER, AUDIO_FINGERPRINT, JELLYFIN_DOWNLOAD, CBZ_EXPORT, ECHO, SOURCE_REPAIR, DICTIONARY, COVERS)

    /** Better on a GPU: given to a GPU worker first when one is online. */
    val GPU_PREFERRED = setOf(TTS, WHISPER)
}

/** How urgent a job is: someone waiting on screen beats the next chapter, which beats overnight pre-generation. */
object JobPriority {
    const val INTERACTIVE = 100
    const val NEXT = 50
    const val BACKGROUND = 10
}

enum class JobStatus(val db: String) {
    QUEUED("queued"), RUNNING("running"), DONE("done"), FAILED("failed"), EXPIRED("expired");
    companion object { fun of(db: String) = entries.first { it.db == db } }
}

data class Job(
    val id: Long, val type: String, val priority: Int, val payload: JsonObject, val dedupKey: String?, val status: JobStatus,
    val attempts: Int, val maxAttempts: Int, val workerId: Long?, val requestedBy: Long?, val prefersGpu: Boolean,
    val createdAt: Long, val updatedAt: Long, val expiresAt: Long, val error: String?, val resultKey: String?
)

/**
 * Finished results (audio, OCR boxes, subtitles, fingerprints...) on the VPS disk, at most [maxBytes] in total.
 * The least recently used go first. Keys are the jobs' dedup keys, so two users asking for the same thing share it.
 */
class ResultCache(private val db: Database, private val dir: File, private val maxBytes: Long) {
    init { dir.mkdirs() }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".bin"

    /** Stores at most [limit] bytes from [input]; throws if it is longer (the partial file is removed). */
    suspend fun put(key: String, input: InputStream, contentType: String, limit: Long): Long {
        val name = fileName(key)
        val partial = File(dir, "$name.partial")
        var size = 0L
        try {
            partial.outputStream().buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= limit) { "Resultado demasiado grande (máximo ${limit / 1_048_576} MB)" }
                    out.write(buffer, 0, n)
                }
            }
            Files.move(partial.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
        val now = System.currentTimeMillis()
        db.tx {
            update(
                """INSERT INTO results(key, file, size, content_type, created_at, last_access) VALUES(?,?,?,?,?,?)
                   ON CONFLICT(key) DO UPDATE SET file = excluded.file, size = excluded.size, content_type = excluded.content_type,
                   created_at = excluded.created_at, last_access = excluded.last_access""",
                key, name, size, safeType(contentType), now, now
            )
        }
        trim()
        return size
    }

    /** The stored file and its type, marking it as used. */
    suspend fun get(key: String): Pair<File, String>? {
        val row = db.tx {
            queryOne("SELECT file, content_type FROM results WHERE key = ?", key) { getString(1) to getString(2) }
                ?.also { update("UPDATE results SET last_access = ? WHERE key = ?", System.currentTimeMillis(), key) }
        } ?: return null
        val file = File(dir, row.first)
        if (!file.isFile) {
            db.tx { update("DELETE FROM results WHERE key = ?", key) }
            return null
        }
        return file to row.second
    }

    suspend fun has(key: String): Boolean = db.tx { queryOne("SELECT 1 FROM results WHERE key = ?", key) { 1 } } != null

    /** Deletes a result once it has been used up (its contents now live elsewhere). */
    suspend fun remove(key: String) {
        val file = db.tx { queryOne("SELECT file FROM results WHERE key = ?", key) { getString(1) } } ?: return
        db.tx { update("DELETE FROM results WHERE key = ?", key) }
        File(dir, file).delete()
    }

    suspend fun totalBytes(): Long = db.tx { queryOne("SELECT COALESCE(SUM(size), 0) FROM results") { getLong(1) } } ?: 0

    /** Removes the least recently used results until the total fits in [maxBytes]. */
    suspend fun trim() {
        var total = totalBytes()
        if (total <= maxBytes) return
        val victims = db.tx { query("SELECT key, file, size FROM results ORDER BY last_access ASC LIMIT 500") { Triple(getString(1), getString(2), getLong(3)) } }
        for ((key, file, size) in victims) {
            if (total <= maxBytes) break
            File(dir, file).delete()
            db.tx { update("DELETE FROM results WHERE key = ?", key) }
            total -= size
        }
    }

    companion object {
        private val ALLOWED = setOf(
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/opus", "audio/webm", "application/json", "text/vtt",
            "text/plain", "application/zip", "application/octet-stream"
        )

        /** Only plain data types are ever served back; anything else becomes a download. */
        fun safeType(type: String): String = type.substringBefore(';').trim().lowercase().takeIf { it in ALLOWED } ?: "application/octet-stream"
    }
}

/**
 * The queue of heavy jobs, in SQLite so it survives restarts. Workers claim the most urgent job they can do; a job whose
 * worker stops reporting goes back to the queue; failed jobs are retried with a pause, up to their attempt limit.
 */
class JobQueue(private val db: Database, val results: ResultCache, private val clock: () -> Long = System::currentTimeMillis) {

    data class Request(
        val type: String,
        val payload: JsonObject,
        val priority: Int = JobPriority.INTERACTIVE,
        /** Same key = same work: joins a live job, or is answered from the cache. */
        val dedupKey: String? = null,
        val requestedBy: Long? = null,
        val ttlMs: Long = 6 * HOUR,
        val maxAttempts: Int = 3
    )

    /** The job for [request]: an existing live one with the same key (raised to the higher priority) or a new one. */
    suspend fun enqueue(request: Request): Job {
        require(request.type in JobTypes.ALL) { "Tipo de trabajo desconocido: ${request.type}" }
        val payload = request.payload.toString()
        require(payload.length <= MAX_PAYLOAD) { "Datos del trabajo demasiado grandes" }
        val now = clock()
        val id = db.tx {
            val existing = request.dedupKey?.let { key ->
                queryOne("SELECT id, priority FROM jobs WHERE dedup_key = ? AND status IN ('queued','running')", key) { getLong(1) to getInt(2) }
            }
            if (existing != null) {
                if (request.priority > existing.second) update("UPDATE jobs SET priority = ?, updated_at = ? WHERE id = ?", request.priority, now, existing.first)
                existing.first
            } else {
                update(
                    """INSERT INTO jobs(type, priority, payload, dedup_key, status, max_attempts, requested_by, prefers_gpu, created_at, updated_at, expires_at)
                       VALUES(?,?,?,?,'queued',?,?,?,?,?,?)""",
                    request.type, request.priority, payload, request.dedupKey, request.maxAttempts.coerceIn(1, 10), request.requestedBy,
                    request.type in JobTypes.GPU_PREFERRED, now, now, now + request.ttlMs
                )
                lastId()
            }
        }
        return get(id)!!
    }

    suspend fun get(id: Long): Job? = db.tx { queryOne("SELECT * FROM jobs WHERE id = ?", id) { job() } }

    /**
     * Gives [worker] the most urgent job it can run, or null. A job that prefers a GPU waits up to [GPU_GRACE_MS] for a GPU
     * worker when one is online, so the laptop with the RTX gets the voices while it is on and the home server otherwise.
     */
    suspend fun claim(worker: WorkerInfo, gpuWorkerOnline: Boolean, leaseMs: Long = LEASE_MS): Job? {
        val engines = worker.engines.filter { it in JobTypes.ALL }
        if (engines.isEmpty()) return null
        val now = clock()
        val id = db.tx {
            val marks = engines.joinToString(",") { "?" }
            val candidates = query(
                """SELECT id, prefers_gpu, created_at FROM jobs WHERE status = 'queued' AND not_before <= ? AND expires_at > ? AND type IN ($marks)
                   ORDER BY priority DESC, created_at ASC LIMIT 20""",
                now, now, *engines.toTypedArray()
            ) { Triple(getLong(1), getInt(2) == 1, getLong(3)) }
            val pick = candidates.firstOrNull { (_, prefersGpu, created) ->
                worker.hasGpu || !prefersGpu || !gpuWorkerOnline || now - created >= GPU_GRACE_MS
            } ?: return@tx null
            val changed = update(
                "UPDATE jobs SET status = 'running', worker_id = ?, attempts = attempts + 1, lease_until = ?, updated_at = ? WHERE id = ? AND status = 'queued'",
                worker.id, now + leaseMs, now, pick.first
            )
            pick.first.takeIf { changed == 1 }
        } ?: return null
        return get(id)
    }

    /** Keeps a running job leased to its worker; false if the job is no longer that worker's. */
    suspend fun heartbeat(jobId: Long, workerId: Long, leaseMs: Long = LEASE_MS): Boolean = db.tx {
        update("UPDATE jobs SET lease_until = ?, updated_at = ? WHERE id = ? AND worker_id = ? AND status = 'running'", clock() + leaseMs, clock(), jobId, workerId) == 1
    }

    /** Stores the result of a running job of [workerId] and marks it done. */
    suspend fun complete(jobId: Long, workerId: Long, input: InputStream, contentType: String, limit: Long = MAX_RESULT_BYTES): Job {
        val job = get(jobId)?.takeIf { it.workerId == workerId && it.status == JobStatus.RUNNING }
            ?: throw NoSuchElementException("Ese trabajo no está asignado a este worker")
        val key = job.dedupKey ?: "job-${job.id}"
        results.put(key, input, contentType, limit)
        db.tx {
            update("UPDATE jobs SET status = 'done', result_key = ?, error = NULL, lease_until = NULL, updated_at = ? WHERE id = ?", key, clock(), jobId)
        }
        return get(jobId)!!
    }

    /** A worker could not do the job: retried later (with growing pauses) unless [retry] is false or attempts ran out. */
    suspend fun fail(jobId: Long, workerId: Long, error: String, retry: Boolean = true): Job {
        val now = clock()
        db.tx {
            val row = queryOne("SELECT attempts, max_attempts FROM jobs WHERE id = ? AND worker_id = ? AND status = 'running'", jobId, workerId) { getInt(1) to getInt(2) }
                ?: throw NoSuchElementException("Ese trabajo no está asignado a este worker")
            val again = retry && row.first < row.second
            if (again) {
                update(
                    "UPDATE jobs SET status = 'queued', worker_id = NULL, lease_until = NULL, not_before = ?, error = ?, updated_at = ? WHERE id = ?",
                    now + backoff(row.first), error.take(1_000), now, jobId
                )
            } else {
                update("UPDATE jobs SET status = 'failed', lease_until = NULL, error = ?, updated_at = ? WHERE id = ?", error.take(1_000), now, jobId)
            }
        }
        return get(jobId)!!
    }

    /** Housekeeping, run every minute: lost leases back to the queue, stale jobs expired, old finished ones deleted. */
    suspend fun sweep(): Int = db.tx {
        val now = clock()
        var changed = update(
            "UPDATE jobs SET status = 'failed', error = 'El worker dejó de responder', updated_at = ? WHERE status = 'running' AND lease_until < ? AND attempts >= max_attempts",
            now, now
        )
        changed += update(
            "UPDATE jobs SET status = 'queued', worker_id = NULL, lease_until = NULL, updated_at = ? WHERE status = 'running' AND lease_until < ?",
            now, now
        )
        changed += update("UPDATE jobs SET status = 'expired', updated_at = ? WHERE status = 'queued' AND expires_at <= ?", now, now)
        changed += update("DELETE FROM jobs WHERE status IN ('done','failed','expired') AND updated_at < ?", now - KEEP_FINISHED_MS)
        changed
    }

    data class Stats(val queued: Map<String, Int>, val running: Int, val lastDone: Job?)

    suspend fun stats(): Stats = db.tx {
        Stats(
            queued = query("SELECT type, COUNT(*) FROM jobs WHERE status = 'queued' GROUP BY type") { getString(1) to getInt(2) }.toMap(),
            running = queryOne("SELECT COUNT(*) FROM jobs WHERE status = 'running'") { getInt(1) } ?: 0,
            lastDone = queryOne("SELECT * FROM jobs WHERE status = 'done' ORDER BY updated_at DESC LIMIT 1") { job() }
        )
    }

    suspend fun recent(limit: Int = 50): List<Job> = db.tx { query("SELECT * FROM jobs ORDER BY updated_at DESC LIMIT ?", limit) { job() } }

    private fun java.sql.ResultSet.job() = Job(
        id = getLong("id"), type = getString("type"), priority = getInt("priority"),
        payload = runCatching { JsonParser.parseString(getString("payload")).asJsonObject }.getOrElse { JsonObject() },
        dedupKey = getString("dedup_key"), status = JobStatus.of(getString("status")), attempts = getInt("attempts"),
        maxAttempts = getInt("max_attempts"), workerId = longOrNull("worker_id"), requestedBy = longOrNull("requested_by"),
        prefersGpu = getInt("prefers_gpu") == 1, createdAt = getLong("created_at"), updatedAt = getLong("updated_at"),
        expiresAt = getLong("expires_at"), error = getString("error"), resultKey = getString("result_key")
    )

    companion object {
        const val HOUR = 3_600_000L
        const val LEASE_MS = 10 * 60_000L
        const val GPU_GRACE_MS = 60_000L
        const val KEEP_FINISHED_MS = 7 * 24 * HOUR
        const val MAX_PAYLOAD = 64 * 1024
        const val MAX_RESULT_BYTES = 512L * 1024 * 1024

        /** 1 min, 4 min, 9 min... capped at one hour. */
        fun backoff(attempts: Int): Long = (attempts.toLong() * attempts * 60_000L).coerceAtMost(HOUR)
    }
}

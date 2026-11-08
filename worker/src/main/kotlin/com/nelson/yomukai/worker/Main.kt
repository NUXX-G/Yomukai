package com.nelson.yomukai.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val config = WorkerConfig.fromEnv()
    runBlocking { Worker(config).run() }
}

/**
 * The worker's life: say hello every minute (with its capabilities and whether it is busy), and in each of its
 * [WorkerConfig.concurrency] slots keep asking for jobs, run them, and upload the result. When the VPS is unreachable it
 * just waits and tries again; nothing is lost, the jobs stay queued there.
 */
class Worker(
    private val config: WorkerConfig,
    private val handlers: List<JobHandler> = allHandlers(config),
    private val vps: VpsClient = VpsClient(config.serverUrl, config.token),
    private val load: LoadMonitor = LoadMonitor(config.maxLoadPerCore, config.pauseFile)
) {
    private val log = LoggerFactory.getLogger("Worker")
    private val byType = handlers.associateBy { it.type }

    @Volatile private var pausedByVps = false

    suspend fun run() = coroutineScope {
        log.info("Worker para {} con {} hueco(s); motores: {}", config.serverUrl, config.concurrency, handlers.joinToString { it.type })
        launch { helloLoop() }
        launch { housekeeping() }
        config.backupDir?.let { dir -> launch { backupLoop(dir) } }
        repeat(config.concurrency) { slot -> launch { slotLoop(slot) } }
    }

    private suspend fun helloLoop() {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { vps.hello(Capabilities.describe(handlers, load.busy(), config.concurrency)) } }
                .onSuccess { pausedByVps = it.get("paused")?.asBoolean == true }
                .onFailure { log.warn("No se pudo contactar con el VPS: {}", it.message) }
            delay(HELLO_MS)
        }
    }

    private suspend fun housekeeping() {
        while (true) {
            delay(60_000)
            handlers.forEach { runCatching { it.unloadIfIdle() } }
            // Leftovers of jobs killed halfway.
            config.workDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 6 * 3_600_000L }?.forEach { it.deleteRecursively() }
        }
    }

    private suspend fun backupLoop(dir: File) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { vps.pullBackup(dir) } }
                .onSuccess { it?.let { f -> log.info("Copia de la base de datos guardada en {}", f) } }
                .onFailure { log.warn("No se pudo traer la copia de seguridad: {}", it.message) }
            runCatching { pruneBackups(dir, keep = 14) }
            delay(6 * 3_600_000L)
        }
    }

    private suspend fun slotLoop(slot: Int) {
        var idle = 0
        while (true) {
            if (pausedByVps || load.busy()) {
                delay(BUSY_WAIT_MS)
                continue
            }
            val job = try {
                withContext(Dispatchers.IO) { vps.claim() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Hueco {}: el VPS no responde ({})", slot, e.message)
                delay(OFFLINE_WAIT_MS)
                continue
            }
            if (job == null) {
                idle = (idle + 1).coerceAtMost(6)
                delay(POLL_MS * idle)
                continue
            }
            idle = 0
            process(job)
        }
    }

    /** Runs one job with a heartbeat, then uploads the result or reports the failure. */
    suspend fun process(job: ClaimedJob) = coroutineScope {
        val handler = byType[job.type]
        if (handler == null) {
            runCatching { vps.fail(job.id, "Este worker no sabe hacer '${job.type}'", retry = true) }
            return@coroutineScope
        }
        log.info("Trabajo {} ({}) intento {}", job.id, job.type, job.attempt)
        val work = launch(Dispatchers.Default) {
            var output: JobOutput? = null
            try {
                // Voice samples stay in the work folder (made only when needed); its 6-hour clean-up just means
                // they are fetched again.
                val samples = java.io.File(config.workDir, "voice-samples")
                val voiceSample = { id: Long ->
                    samples.mkdirs()
                    java.io.File(samples, "$id.wav").takeIf { it.isFile || vps.voiceSample(id, it) }
                }
                output = handler.run(job, JobContext(config.workDir, vps.http, vps::freshVideo, voiceSample))
                withContext(Dispatchers.IO) { vps.upload(job.id, output) }
                log.info("Trabajo {} terminado", job.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentJobFailure) {
                runCatching { vps.fail(job.id, e.message ?: "Error", retry = false) }
            } catch (e: Exception) {
                log.warn("Trabajo {} falló: {}", job.id, e.message)
                runCatching { withContext(Dispatchers.IO) { vps.fail(job.id, e.message ?: e.javaClass.simpleName, retry = true) } }
            } finally {
                output?.file?.delete()
            }
        }
        val beat = launch(Dispatchers.IO) {
            while (isActive && work.isActive) {
                delay(HEARTBEAT_MS)
                if (work.isActive && runCatching { vps.heartbeat(job.id) }.getOrDefault(true) == false) {
                    log.warn("El VPS ya no asigna el trabajo {} a este worker: se cancela", job.id)
                    work.cancel()
                }
            }
        }
        work.join()
        beat.cancel()
    }

    companion object {
        const val HELLO_MS = 60_000L
        const val POLL_MS = 5_000L
        const val BUSY_WAIT_MS = 30_000L
        const val OFFLINE_WAIT_MS = 30_000L
        const val HEARTBEAT_MS = 120_000L

        fun pruneBackups(dir: File, keep: Int) {
            dir.listFiles { f -> f.name.matches(Regex("""yomukai-\d{8}-\d{6}\.db\.gz""")) }
                ?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.delete() }
        }
    }
}

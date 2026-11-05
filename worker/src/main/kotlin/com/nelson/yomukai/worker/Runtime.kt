package com.nelson.yomukai.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs external tools (ffmpeg, whisper, piper...) gently: lowest CPU and disk priority on Linux, with a time limit. */
object Processes {
    private val log = LoggerFactory.getLogger("Processes")
    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val hasIonice = linux && Capabilities.installed("ionice", "--version")

    /** The command with `nice -n 19` (and `ionice -c3`, idle disk class) in front where available. */
    fun lowPriority(command: List<String>): List<String> = when {
        !linux -> command
        hasIonice -> listOf("nice", "-n", "19", "ionice", "-c3") + command
        else -> listOf("nice", "-n", "19") + command
    }

    class Result(val exitCode: Int, val output: String)

    /**
     * Runs [command] (arguments are passed as a list, never through a shell) and returns its exit code and the last
     * part of its output. Killed after [timeoutMs] or when the coroutine is cancelled.
     */
    suspend fun run(command: List<String>, timeoutMs: Long, dir: File? = null, stdin: ByteArray? = null, env: Map<String, String> = emptyMap()): Result =
        runInterruptible(Dispatchers.IO) {
            val pb = ProcessBuilder(lowPriority(command)).redirectErrorStream(true)
            dir?.let(pb::directory)
            pb.environment().putAll(env)
            val process = pb.start()
            try {
                stdin?.let { bytes -> process.outputStream.use { it.write(bytes) } } ?: process.outputStream.close()
                val tail = StringBuilder()
                val reader = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> synchronized(tail) { tail.append(line).append('\n'); if (tail.length > 8_000) tail.delete(0, tail.length - 8_000) } }
                    }
                }.apply { isDaemon = true; start() }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    error("${command.first()} tardó más de ${timeoutMs / 1000} s")
                }
                reader.join(2_000)
                Result(process.exitValue(), synchronized(tail) { tail.toString() })
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                log.info("Proceso {} cancelado", command.first())
                throw e
            }
        }
}

/** Decides whether the machine is too busy (someone gaming, compiling, watching Jellyfin transcodes...) to take jobs. */
class LoadMonitor(private val maxLoadPerCore: Double, private val pauseFile: File?, private val cpus: Int = Capabilities.cpus()) {
    fun loadAverage(): Double? =
        runCatching { File("/proc/loadavg").readText().trim().split(' ').first().toDouble() }.getOrNull()
            ?: java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage.takeIf { it >= 0 }

    fun busy(): Boolean = isBusy(loadAverage(), cpus, maxLoadPerCore, pauseFile?.exists() == true)

    companion object {
        fun isBusy(load: Double?, cpus: Int, maxPerCore: Double, paused: Boolean): Boolean =
            paused || (load != null && load / cpus.coerceAtLeast(1) > maxPerCore)
    }
}

/**
 * Something expensive to keep in memory (a loaded model, a TTS server process): created on first use and closed
 * after [idleMs] without use, so the home server gets its RAM back when nobody is listening.
 */
class IdleResource<T : AutoCloseable>(private val name: String, private val idleMs: Long, private val clock: () -> Long = System::currentTimeMillis, private val create: () -> T) {
    private val log = LoggerFactory.getLogger("IdleResource")
    private var value: T? = null
    private var lastUsed = 0L

    @Synchronized
    fun <R> use(block: (T) -> R): R {
        val v = value ?: create().also { value = it; log.info("{} cargado", name) }
        lastUsed = clock()
        return block(v)
    }

    @Synchronized
    fun isLoaded() = value != null

    /** Closes the resource if it has been idle long enough. Called every minute. */
    @Synchronized
    fun unloadIfIdle() {
        val v = value ?: return
        if (clock() - lastUsed >= idleMs) {
            runCatching { v.close() }
            value = null
            log.info("{} descargado de memoria tras {} min sin uso", name, idleMs / 60_000)
        }
    }

    @Synchronized
    fun close() {
        value?.let { runCatching { it.close() } }
        value = null
    }
}

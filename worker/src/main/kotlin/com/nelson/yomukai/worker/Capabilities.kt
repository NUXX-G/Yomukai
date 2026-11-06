package com.nelson.yomukai.worker

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** What this machine is: cores, memory, GPU, and the engines it can run. */
object Capabilities {
    fun cpus(): Int = Runtime.getRuntime().availableProcessors()

    /** Total RAM in MB: /proc/meminfo on Linux, the JVM's view elsewhere. */
    fun ramMb(): Long = parseMemInfo(runCatching { File("/proc/meminfo").readText() }.getOrNull())
        ?: runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / 1_048_576
        }.getOrDefault(0)

    fun parseMemInfo(text: String?): Long? =
        text?.lineSequence()?.firstOrNull { it.startsWith("MemTotal:") }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)

    /** NVIDIA GPU name via nvidia-smi, or null. */
    fun gpu(): String? = runCatching {
        val p = ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader").redirectErrorStream(true).start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        parseNvidiaSmi(p.inputStream.bufferedReader().readText())
    }.getOrNull()

    fun parseNvidiaSmi(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.contains("not found", true) && !it.contains("failed", true) }
            ?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    /** One job per 2 cores and 2 GB, at least one, at most 4. */
    fun defaultConcurrency(cpus: Int = cpus(), ramMb: Long = ramMb()): Int =
        minOf(cpus / 2, (ramMb / 2048).toInt(), 4).coerceAtLeast(1)

    fun describe(handlers: List<JobHandler>, busy: Boolean, concurrency: Int): JsonObject = JsonObject().apply {
        addProperty("cpus", cpus())
        addProperty("ramMb", ramMb())
        gpu()?.let { addProperty("gpu", it) }
        addProperty("os", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        addProperty("busy", busy)
        addProperty("maxConcurrent", concurrency)
        add("engines", JsonArray().also { a -> handlers.map { it.type }.distinct().forEach(a::add) })
        handlers.filterIsInstance<TtsHandler>().firstOrNull()?.let { add("voices", it.info()) }
    }

    /** True when [command] can be started (it is on the PATH or is an existing file). */
    fun installed(command: String, vararg probeArgs: String = arrayOf("--version")): Boolean = runCatching {
        val p = ProcessBuilder(command, *probeArgs).redirectErrorStream(true).start()
        p.inputStream.close()
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        true
    }.getOrDefault(false)
}

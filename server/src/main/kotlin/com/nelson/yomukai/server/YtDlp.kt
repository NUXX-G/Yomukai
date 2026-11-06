package com.nelson.yomukai.server

import com.google.gson.JsonParser
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * yt-dlp as the first way to find the stream behind a player page. Only extraction (`-j`, no download), one run at a
 * time, with a time limit. Hosts it cannot handle are learnt: after two failures in a row it is skipped there for a
 * day and the built-in extractors do the work. The page address comes from scraped HTML, so it is checked to be an
 * http(s) URL and passed after "--", where it can never be read as an option.
 */
class YtDlp(
    private val binary: File,
    private val timeoutMs: Long = 25_000,
    concurrency: Int = 1,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runner: suspend (List<String>, Long) -> String? = ::run,
    /** True when every address of the host is public: a scraped page must never make the VPS reach its own network. */
    private val hostIsPublic: suspend (String) -> Boolean = ::resolvesOnlyToPublic
) {
    private val log = LoggerFactory.getLogger("YtDlp")
    private val permits = Semaphore(concurrency)
    private class HostRecord(var failures: Int = 0, var skipUntil: Long = 0)
    private val hosts = ConcurrentHashMap<String, HostRecord>()

    val available: Boolean get() = binary.isFile && binary.canExecute()

    suspend fun resolve(source: VideoSource): VideoSource? {
        val url = source.videoUrl.trim()
        if (source.isDirect || !SAFE_URL.matches(url)) return null
        val host = hostOf(url) ?: return null
        val record = hosts.getOrPut(host) { HostRecord() }
        if (record.skipUntil > clock()) return null
        if (!hostIsPublic(host)) return null
        val output = permits.withPermit { runner(command(url), timeoutMs) }
        val found = output?.let(::parse)
        synchronized(record) {
            if (found == null) {
                record.failures++
                if (record.failures >= 2) {
                    record.skipUntil = clock() + SKIP_MS
                    record.failures = 0
                    log.info("yt-dlp no sirve para {}: se usan los extractores propios durante un día", host)
                }
            } else {
                record.failures = 0
            }
        }
        return found?.let { (stream, headers) ->
            source.copy(videoUrl = stream, isDirect = true, headers = headers, quality = source.quality + if ("m3u8" in stream) " • HLS" else " • MP4")
        }
    }

    fun command(url: String): List<String> = listOf(
        binary.absolutePath, "--ignore-config", "--no-cache-dir", "--no-playlist", "--no-warnings", "--no-progress",
        // No "generic" extractor: only sites yt-dlp knows, so an arbitrary address is never fetched.
        "--use-extractors", "default,-generic",
        "--socket-timeout", "10", "-f", "best[protocol^=http]/best", "-j", "--", url
    )

    companion object {
        private const val SKIP_MS = 24 * 3_600_000L
        private val SAFE_URL = Regex("^https?://[^\\s\"'<>]{4,2000}$")
        private val KEEP_HEADERS = setOf("user-agent", "referer", "origin")

        suspend fun resolvesOnlyToPublic(host: String): Boolean = runInterruptible(Dispatchers.IO) {
            runCatching { java.net.InetAddress.getAllByName(host).let { all -> all.isNotEmpty() && all.all(PublicAddresses::isPublic) } }.getOrDefault(false)
        }

        fun hostOf(url: String): String? = Regex("^https?://([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")

        /** The stream URL and the headers it needs, from `yt-dlp -j` output; null when there is none. */
        fun parse(output: String): Pair<String, Map<String, String>>? = runCatching {
            val line = output.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
            val o = JsonParser.parseString(line).asJsonObject
            val url = o.get("url")?.asString?.takeIf { SAFE_URL.matches(it) } ?: return null
            val headers = o.getAsJsonObject("http_headers")?.entrySet().orEmpty()
                .filter { it.key.lowercase() in KEEP_HEADERS && it.value.isJsonPrimitive }
                .associate { it.key to it.value.asString.take(500) }
            url to headers
        }.getOrNull()

        private suspend fun run(command: List<String>, timeoutMs: Long): String? = runInterruptible(Dispatchers.IO) {
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                process.outputStream.close()
                val out = StringBuilder()
                val reader = Thread { process.inputStream.bufferedReader().use { r -> out.append(r.readText().take(2_000_000)) } }.apply { isDaemon = true; start() }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    return@runInterruptible null
                }
                reader.join(2_000)
                if (process.exitValue() == 0) out.toString() else null
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                throw e
            }
        }
    }
}

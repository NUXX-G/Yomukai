package com.nelson.yomukai.worker

import com.google.gson.JsonObject
import com.nelson.yomukai.data.work.AudioFingerprint

/**
 * Opening and ending of an episode: the first and last [WINDOW_S] seconds of its audio and of another episode of the
 * same series are fingerprinted with Chromaprint (`fpcalc -raw`) and compared ([AudioFingerprint]). Only audio is read,
 * at low priority, and the temporary files are deleted. Needs ffmpeg, ffprobe and fpcalc (all in the worker image).
 */
class FingerprintHandler : JobHandler {
    override val type = "audio_fingerprint"
    override fun available(): Boolean = listOf("ffmpeg", "ffprobe").all { Capabilities.installed(it, "-version") } && Capabilities.installed("fpcalc", "-version")

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val episodes = job.payload.getAsJsonArray("episodes")?.mapNotNull { it as? JsonObject }.orEmpty()
        if (episodes.size < 2) throw PermanentJobFailure("Hacen falta dos episodios")
        val (a, b) = episodes.take(2).map { e ->
            val url = e.get("url")?.asString.orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw PermanentJobFailure("Dirección de vídeo no válida")
            val headers = e.getAsJsonObject("headers")?.entrySet()?.filter { it.key in setOf("Referer", "User-Agent", "Origin") }
                ?.joinToString("") { "${it.key}: ${it.value.asString.replace("\r", "").replace("\n", "")}\r\n" }.orEmpty()
            url to headers
        }
        val duration = duration(a.first, a.second)
        val prints = listOf(a, b).flatMap { (url, headers) ->
            listOf(fingerprint(context, url, headers, fromEnd = false), fingerprint(context, url, headers, fromEnd = true))
        }
        val m = AudioFingerprint.markers(prints[0], prints[2], prints[1], prints[3], duration, WINDOW_S.toDouble())
        val out = context.newFile(".json")
        out.writeText(JsonObject().apply {
            m.opening?.let { addProperty("openingStart", it.startSec); addProperty("openingEnd", it.endSec) }
            m.ending?.let { addProperty("endingStart", it.startSec); addProperty("endingEnd", it.endSec) }
            addProperty("duration", duration)
        }.toString())
        return JobOutput(out, "application/json")
    }

    /** Only network protocols, never local files: the address comes from a source. */
    private val inputGuard = listOf("-protocol_whitelist", "https,http,tls,tcp,crypto,hls,httpproxy")

    private suspend fun duration(url: String, headers: String): Double {
        val r = Processes.run(
            listOf("ffprobe", "-v", "error") + inputGuard + listOf("-headers", headers, "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", url),
            timeoutMs = 60_000
        )
        return r.output.trim().lineSequence().firstNotNullOfOrNull { it.trim().toDoubleOrNull() } ?: error("No se pudo leer la duración")
    }

    private suspend fun fingerprint(context: JobContext, url: String, headers: String, fromEnd: Boolean): IntArray {
        val wav = context.newFile(".wav")
        try {
            val seek = if (fromEnd) listOf("-sseof", "-$WINDOW_S") else emptyList()
            val ff = Processes.run(
                listOf("ffmpeg", "-y", "-v", "error") + inputGuard + listOf("-headers", headers) + seek +
                    listOf("-i", url, "-t", WINDOW_S.toString(), "-vn", "-ac", "1", "-ar", "11025", "-f", "wav", wav.absolutePath),
                timeoutMs = 5 * 60_000
            )
            check(ff.exitCode == 0 && wav.length() >= 1_000) { "ffmpeg no pudo leer el audio: ${ff.output.takeLast(200)}" }
            val fp = Processes.run(listOf("fpcalc", "-raw", "-length", WINDOW_S.toString(), wav.absolutePath), timeoutMs = 120_000)
            return AudioFingerprint.parseRaw(fp.output).also { check(it.isNotEmpty()) { "fpcalc no dio huella" } }
        } finally {
            wav.delete()
        }
    }

    companion object {
        const val WINDOW_S = 360
    }
}

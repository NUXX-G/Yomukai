package com.nelson.yomukai.worker

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.ocr.TesseractTsv
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.net.URI

/** Addresses from sources are only fetched when they lead to the public internet, never to this home network. */
internal object PublicOnly {
    fun check(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw PermanentJobFailure("Dirección no válida")
        if (uri.scheme != "https" && uri.scheme != "http") throw PermanentJobFailure("Solo http(s)")
        val host = uri.host ?: throw PermanentJobFailure("Dirección sin servidor")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        if (addresses.isEmpty() || addresses.any(::isLocal)) {
            throw PermanentJobFailure("Esa dirección no es pública")
        }
    }

    /**
     * A resolver that only ever returns public addresses: the address checked is the one connected to, so a name that
     * changes its answer after the check (DNS rebinding) still cannot reach this home network.
     */
    val dns = okhttp3.Dns { host ->
        okhttp3.Dns.SYSTEM.lookup(host).filterNot(::isLocal).ifEmpty { throw java.net.UnknownHostException("$host no es una dirección pública") }
    }

    /** [base] without following redirects by itself (each hop is checked by hand) and with the public-only [dns]. */
    fun client(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient = base.newBuilder().followRedirects(false).followSslRedirects(false).dns(dns).build()

    private fun isLocal(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || isPrivateV6(a)

    private fun isPrivateV6(a: InetAddress): Boolean = a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
}

/** Only these headers go with a fetch, with no line breaks (they come from a source's settings). */
internal fun safeHeaders(payload: JsonObject): Map<String, String> =
    payload.getAsJsonObject("headers")?.entrySet()?.filter { it.key in setOf("Referer", "User-Agent", "Origin") }
        ?.associate { it.key to it.value.asString.replace("\r", "").replace("\n", "").take(500) }.orEmpty()

/**
 * Text of a comic page with Tesseract (`tesseract … tsv`): paragraphs as boxes in fractions of the page, each one a
 * bubble or a sound effect. The page is downloaded from the source (public addresses only) and deleted afterwards.
 */
class OcrHandler : JobHandler {
    override val type = "ocr"
    override fun available(): Boolean = Capabilities.installed("tesseract", "--version")

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val url = job.payload.get("url")?.asString.orEmpty()
        PublicOnly.check(url)
        val image = context.newFile(".img")
        val request = Request.Builder().url(url).apply { safeHeaders(job.payload).forEach { (k, v) -> header(k, v) } }.build()
        context.http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("La página respondió ${r.code}")
            val body = r.body ?: error("Página vacía")
            if (body.contentLength() > MAX_IMAGE) throw PermanentJobFailure("Imagen demasiado grande")
            image.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (image.length() > MAX_IMAGE) throw PermanentJobFailure("Imagen demasiado grande")
        val lang = LANGS[job.payload.get("lang")?.asString.orEmpty().lowercase().take(2)] ?: "eng"
        val result = Processes.run(listOf("tesseract", image.absolutePath, "stdout", "-l", lang, "tsv"), timeoutMs = 120_000)
        if (result.exitCode != 0) error("Tesseract falló: ${result.output.takeLast(200)}")
        val boxes = TesseractTsv.parse(result.output)
        val out = context.newFile(".json")
        out.writeText(JsonObject().apply {
            add("boxes", JsonArray().also { arr ->
                boxes.forEach { b ->
                    arr.add(JsonObject().apply {
                        addProperty("x", b.x); addProperty("y", b.y); addProperty("w", b.w); addProperty("h", b.h)
                        addProperty("text", b.text.take(2_000)); addProperty("kind", b.kind.name)
                    })
                }
            })
            addProperty("engine", "tesseract")
            addProperty("language", lang)
        }.toString())
        return JobOutput(out, "application/json")
    }

    companion object {
        private const val MAX_IMAGE = 25L * 1024 * 1024
        private val LANGS = mapOf("en" to "eng", "es" to "spa", "ja" to "jpn", "ko" to "kor", "zh" to "chi_sim", "fr" to "fra", "de" to "deu", "it" to "ita", "pt" to "por")
    }
}

/**
 * Subtitles for an episode with whisper.cpp: the audio is taken with ffmpeg (network protocols only) and transcribed
 * to WebVTT. WORKER_WHISPER_MODEL is the model file (a small one on CPU, a better one on the GPU worker);
 * WORKER_WHISPER_BIN the program (whisper-cli by default).
 */
class WhisperHandler(
    private val model: File? = System.getenv("WORKER_WHISPER_MODEL")?.let(::File)?.takeIf { it.isFile },
    private val binary: String = System.getenv("WORKER_WHISPER_BIN") ?: "whisper-cli"
) : JobHandler {
    override val type = "whisper"
    override fun available(): Boolean = model != null && Capabilities.installed("ffmpeg", "-version") && Capabilities.installed(binary, "--help")

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val url = job.payload.get("url")?.asString.orEmpty()
        PublicOnly.check(url)
        val headers = safeHeaders(job.payload).entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val wav = context.newFile(".wav")
        val ff = Processes.run(
            listOf("ffmpeg", "-y", "-v", "error", "-protocol_whitelist", "https,http,tls,tcp,crypto,hls,httpproxy", "-headers", headers, "-i", url,
                "-vn", "-ac", "1", "-ar", "16000", "-f", "wav", wav.absolutePath),
            timeoutMs = 30 * 60_000L
        )
        if (ff.exitCode != 0 || wav.length() < 10_000) error("ffmpeg no pudo sacar el audio: ${ff.output.takeLast(200)}")
        val lang = job.payload.get("lang")?.asString?.lowercase()?.take(2)?.takeIf { it.all(Char::isLetter) } ?: "auto"
        val base = File(context.workDir, "subs-${job.id}")
        val w = Processes.run(listOf(binary, "-m", model!!.absolutePath, "-f", wav.absolutePath, "-l", lang, "-ovtt", "-of", base.absolutePath), timeoutMs = 2 * 3_600_000L)
        val vtt = File(base.absolutePath + ".vtt")
        if (w.exitCode != 0 || !vtt.isFile) error("whisper falló: ${w.output.takeLast(200)}")
        return JobOutput(vtt, "text/vtt")
    }
}

/**
 * Translation with a LibreTranslate server running at home (WORKER_LIBRETRANSLATE_URL, e.g. http://localhost:5000,
 * which itself uses Argos Translate). Used for bubbles of abandoned works and for the language-learning tools.
 */
class TranslateHandler(private val base: String? = System.getenv("WORKER_LIBRETRANSLATE_URL")?.trimEnd('/')) : JobHandler {
    override val type = "translate"
    override fun available(): Boolean = base != null

    override suspend fun run(job: ClaimedJob, context: JobContext): JobOutput {
        val texts = job.payload.getAsJsonArray("texts")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.take(2_000) }?.take(200).orEmpty()
        if (texts.isEmpty()) throw PermanentJobFailure("Nada que traducir")
        val from = job.payload.get("from")?.asString?.take(5) ?: "auto"
        val to = job.payload.get("to")?.asString?.take(5) ?: "es"
        val body = JsonObject().apply {
            add("q", JsonArray().also { a -> texts.forEach(a::add) }); addProperty("source", from); addProperty("target", to); addProperty("format", "text")
        }
        val translated = context.http.newCall(Request.Builder().url("$base/translate").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) error("LibreTranslate respondió ${r.code}")
            val json = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            val t = json.get("translatedText")
            if (t.isJsonArray) t.asJsonArray.map { it.asString } else listOf(t.asString)
        }
        val out = context.newFile(".json")
        out.writeText(JsonObject().apply { add("translations", JsonArray().also { a -> translated.forEach(a::add) }) }.toString())
        return JobOutput(out, "application/json")
    }
}

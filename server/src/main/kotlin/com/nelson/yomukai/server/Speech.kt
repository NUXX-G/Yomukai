package com.nelson.yomukai.server

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A voice the server can read with: Piper model, and the speaker inside it for models with several. */
data class VoiceInfo(
    val id: String,
    val label: String,
    /** "es" or "en". */
    val lang: String,
    /** "f", "m" or "" when not known for sure. */
    val gender: String,
    val region: String,
    val model: String,
    val speaker: String? = null
)

/**
 * Reading aloud with natural (neural) voices: Piper, open source, running on this server. Each
 * model is downloaded from the Piper voices repository the first time it is used and kept in the
 * data folder. Audio is kept too, so a chapter read again, or by a friend, costs nothing.
 */
class Speech(private val http: ServerHttp, dataDir: File, private val piper: File) {
    private val log = LoggerFactory.getLogger("YomukaiSpeech")
    private val voicesDir = File(dataDir, "voices").apply { mkdirs() }
    private val cache = ImageCache(File(dataDir, "cache/tts"), maxBytes = 1024L * 1024 * 1024)
    private val downloading = ConcurrentHashMap<String, Mutex>()
    /** Piper uses every core it gets: a couple at a time keeps the server answering everyone else. */
    private val synthLimit = Semaphore(maxOf(1, minOf(2, Runtime.getRuntime().availableProcessors() - 1)))

    val available: Boolean get() = piper.isFile && piper.canExecute()

    fun voices(): List<VoiceInfo> = VOICES

    /** WAV audio of [text] read by [voiceId] at [rate] (1 = normal). */
    suspend fun synthesize(text: String, voiceId: String, rate: Float): ByteArray {
        val voice = VOICES.firstOrNull { it.id == voiceId } ?: throw IllegalArgumentException("Voz desconocida")
        val clean = text.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
        require(clean.isNotEmpty()) { "Texto vacío" }
        val speed = (if (rate.isFinite()) rate else 1f).coerceIn(0.5f, 2.5f)
        val key = sha("${voice.id}|$speed|$clean")
        cache.get(key)?.let { return it.first }
        if (!available) throw IOException("Este servidor no tiene voces naturales instaladas")
        val (model, config) = ensureModel(voice.model)
        val speakerId = voice.speaker?.let { config.speakers[it] }
        val pcm = synthLimit.withPermit { run(model, clean, speed, speakerId) }
        val wav = wav(pcm, config.sampleRate)
        cache.put(key, wav, "audio/wav")
        return wav
    }

    private class ModelConfig(val sampleRate: Int, val speakers: Map<String, Int>)

    /** The model and its settings on disk, downloaded once (one download at a time per model). */
    private suspend fun ensureModel(name: String): Pair<File, ModelConfig> {
        val onnx = File(voicesDir, "$name.onnx")
        val json = File(voicesDir, "$name.onnx.json")
        if (!onnx.exists() || !json.exists()) {
            downloading.getOrPut(name) { Mutex() }.withLock {
                if (!onnx.exists() || !json.exists()) {
                    val base = "https://huggingface.co/rhasspy/piper-voices/resolve/main/" + modelPath(name)
                    log.info("Descargando la voz {}", name)
                    download("$base.onnx.json", json)
                    download("$base.onnx", onnx)
                }
            }
        }
        val root = JsonParser.parseString(json.readText()).asJsonObject
        val rate = root.getAsJsonObject("audio")?.get("sample_rate")?.asInt ?: 22050
        val speakers = root.getAsJsonObject("speaker_id_map")?.entrySet()?.associate { it.key to it.value.asInt }.orEmpty()
        return onnx to ModelConfig(rate, speakers)
    }

    private suspend fun download(url: String, to: File) = withContext(Dispatchers.IO) {
        val tmp = File(to.parentFile, to.name + ".part")
        http.streamClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("No se pudo descargar la voz (HTTP ${r.code})")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        }
        if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
    }

    /** Runs Piper on [text]: raw 16-bit mono PCM out. */
    private suspend fun run(model: File, text: String, speed: Float, speaker: Int?): ByteArray = withContext(Dispatchers.IO) {
        val command = mutableListOf(
            piper.absolutePath, "--model", model.absolutePath, "--output-raw",
            "--length_scale", String.format(java.util.Locale.ROOT, "%.3f", 1f / speed),
            "--sentence_silence", "0.25", "--quiet"
        )
        speaker?.let { command += listOf("--speaker", it.toString()) }
        val process = ProcessBuilder(command).directory(piper.parentFile).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        // Blocking reads ignore coroutine timeouts: a watchdog kills Piper past the deadline, which ends them.
        val watchdog = Thread {
            try { if (!process.waitFor(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.use { it.write((text + "\n").toByteArray(Charsets.UTF_8)) }
            val out = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_PCM_BYTES) throw IOException("Audio demasiado largo")
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS) || !process.isAlive && process.exitValue() != 0 && out.size() == 0) {
                throw IOException("La voz tardó demasiado")
            }
            if (out.size() == 0) throw IOException("La voz no devolvió audio")
            out.toByteArray()
        } finally {
            process.destroyForcibly()
            watchdog.interrupt()
        }
    }

    companion object {
        private const val MAX_CHARS = 2_000
        private const val SYNTH_TIMEOUT_MS = 90_000L
        /** 2,000 characters are about two minutes of speech: 22 050 Hz, 16 bit. */
        private const val MAX_PCM_BYTES = 16 * 1024 * 1024

        /** "es_ES-davefx-medium" -> "es/es_ES/davefx/medium/es_ES-davefx-medium". */
        fun modelPath(name: String): String {
            val (locale, speaker, quality) = name.split('-', limit = 3)
            return "${locale.substringBefore('_')}/$locale/$speaker/$quality/$name"
        }

        /** A WAV file around 16-bit mono [pcm]. */
        fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
                putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }

        private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** The voices offered: natural-sounding ones, with the speaker's gender where it is known for sure. */
        val VOICES = listOf(
            VoiceInfo("es-lucia", "Lucía", "es", "f", "España", "es_ES-sharvard-medium", "F"),
            VoiceInfo("es-sergio", "Sergio", "es", "m", "España", "es_ES-sharvard-medium", "M"),
            VoiceInfo("es-david", "David", "es", "m", "España", "es_ES-davefx-medium"),
            VoiceInfo("es-daniela", "Daniela", "es", "f", "Argentina", "es_AR-daniela-high"),
            VoiceInfo("es-claude", "Claude", "es", "", "México", "es_MX-claude-high"),
            VoiceInfo("es-ald", "Ald", "es", "", "México", "es_MX-ald-medium"),
            VoiceInfo("en-lessac", "Lessac", "en", "f", "EE. UU.", "en_US-lessac-high"),
            VoiceInfo("en-amy", "Amy", "en", "f", "EE. UU.", "en_US-amy-medium"),
            VoiceInfo("en-ryan", "Ryan", "en", "m", "EE. UU.", "en_US-ryan-high"),
            VoiceInfo("en-joe", "Joe", "en", "m", "EE. UU.", "en_US-joe-medium"),
            VoiceInfo("en-cori", "Cori", "en", "f", "Reino Unido", "en_GB-cori-high"),
            VoiceInfo("en-alan", "Alan", "en", "m", "Reino Unido", "en_GB-alan-medium")
        )
    }
}

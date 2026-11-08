package com.nelson.yomukai.data.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Natural voices that live on the phone (sherpa-onnx with Piper voices, Apache-2.0/MIT): no server and no connection
 * once downloaded. Each voice is data only (an ONNX model, its tokens and espeak-ng's pronunciation data), downloaded
 * from sherpa-onnx's official releases when the user asks for it.
 */
class DeviceVoices(context: Context, private val http: () -> OkHttpClient) {
    /** A voice that can be downloaded. [gender] is "female" or "male", for narrators and dialogue. */
    data class Voice(val id: String, val lang: String, val label: String, val gender: String, val pack: String)

    private val dir = File(context.filesDir, "voices")
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()
    private val _downloading = MutableStateFlow<String?>(null)
    val downloading: StateFlow<String?> = _downloading.asStateFlow()

    private val lock = Mutex()
    private var loadedId: String? = null
    private var engine: OfflineTts? = null

    private fun scan(): Set<String> = CATALOG.filter { File(dir, it.id).resolve(DONE).isFile }.map { it.id }.toSet()

    /** Whether this build can run them (the native library is there for this phone's processor). */
    val supported: Boolean by lazy { runCatching { System.loadLibrary("sherpa-onnx-jni"); true }.getOrDefault(false) }

    fun voicesFor(lang: String): List<Voice> = CATALOG.filter { it.id in _installed.value && it.lang == lang.take(2).lowercase() }

    suspend fun download(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val voice = CATALOG.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalArgumentException("Voz desconocida"))
        _downloading.value = id
        val target = File(dir, id)
        val result = runCatching {
            target.deleteRecursively()
            target.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${voice.pack}.tar.bz2"
            http().newCall(Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "La descarga respondió ${r.code}" }
                val body = r.body ?: error("Descarga vacía")
                extract(TarArchiveInputStream(BZip2CompressorInputStream(body.byteStream().buffered())), target)
            }
            File(target, DONE).writeText(voice.pack)
        }
        if (result.isFailure) target.deleteRecursively()
        _installed.value = scan()
        _downloading.value = null
        result
    }

    /** Unpacks only regular files, flattened under [target]'s own folders, never outside it, within [MAX_BYTES]. */
    private fun extract(tar: TarArchiveInputStream, target: File) {
        var total = 0L
        val root = target.canonicalPath + File.separator
        while (true) {
            val entry = tar.nextEntry ?: break
            if (!entry.isFile) continue
            // Drop the pack's top folder ("vits-piper-…/model.onnx" → "model.onnx").
            val name = entry.name.substringAfter('/', entry.name)
            if (name.isBlank() || name.startsWith("/") || name.split('/').any { it == ".." }) continue
            val out = File(target, name)
            check(out.canonicalPath.startsWith(root)) { "Ruta no válida en el paquete" }
            out.parentFile?.mkdirs()
            out.outputStream().use { o ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = tar.read(buf)
                    if (n < 0) break
                    total += n
                    check(total <= MAX_BYTES) { "Paquete de voz demasiado grande" }
                    o.write(buf, 0, n)
                }
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { if (loadedId == id) { engine?.release(); engine = null; loadedId = null } }
        File(dir, id).deleteRecursively()
        _installed.value = scan()
    }

    /** [text] spoken by voice [id] at [rate] (1 = normal), as a WAV file's bytes. */
    suspend fun speak(id: String, text: String, rate: Float): ByteArray = withContext(Dispatchers.Default) {
        check(supported) { "Este móvil no puede usar las voces del dispositivo" }
        lock.withLock {
            val tts = engine?.takeIf { loadedId == id } ?: run {
                engine?.release()
                load(id).also { engine = it; loadedId = id }
            }
            val audio = tts.generate(text, 0, rate.coerceIn(MIN_RATE, MAX_RATE))
            wav(audio.samples, audio.sampleRate)
        }
    }

    private fun load(id: String): OfflineTts {
        val folder = File(dir, id)
        check(File(folder, DONE).isFile) { "Esa voz no está descargada" }
        val model = folder.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: error("Falta el modelo de la voz")
        val vits = OfflineTtsVitsModelConfig(
            model.path, "", File(folder, "tokens.txt").path, File(folder, "espeak-ng-data").path, "", NOISE, NOISE_W, 1f
        )
        val config = OfflineTtsConfig(
            OfflineTtsModelConfig(vits, numThreads = THREADS, debug = false, provider = "cpu"), "", "", 1, SILENCE
        )
        return OfflineTts(null, config)
    }

    /** 16-bit mono PCM WAV of [samples] (-1..1). */
    private fun wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(WAV_HEADER - 8 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples.size * 2)
        }
        return ByteArrayOutputStream(WAV_HEADER + samples.size * 2).apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    companion object {
        /** One woman and one man per language when there are both (Piper has no Japanese or Korean voices). */
        val CATALOG = listOf(
            Voice("es-davefx", "es", "Dave (España)", "male", "vits-piper-es_ES-davefx-medium-int8"),
            Voice("es-sharvard", "es", "Sharvard (España)", "female", "vits-piper-es_ES-sharvard-medium-int8"),
            Voice("es-ald", "es", "Ald (México)", "male", "vits-piper-es_MX-ald-medium-int8"),
            Voice("en-amy", "en", "Amy (EE. UU.)", "female", "vits-piper-en_US-amy-medium-int8"),
            Voice("en-ryan", "en", "Ryan (EE. UU.)", "male", "vits-piper-en_US-ryan-medium-int8"),
            Voice("en-jenny", "en", "Jenny (Reino Unido)", "female", "vits-piper-en_GB-jenny_dioco-medium-int8"),
            Voice("zh-xiaoya", "zh", "Xiao Ya", "female", "vits-piper-zh_CN-xiao_ya-medium-int8"),
            Voice("zh-chaowen", "zh", "Chaowen", "male", "vits-piper-zh_CN-chaowen-medium-int8"),
            Voice("fr-siwis", "fr", "Siwis", "female", "vits-piper-fr_FR-siwis-medium-int8"),
            Voice("fr-tom", "fr", "Tom", "male", "vits-piper-fr_FR-tom-medium-int8"),
            Voice("de-thorsten", "de", "Thorsten", "male", "vits-piper-de_DE-thorsten-medium-int8"),
            Voice("it-paola", "it", "Paola", "female", "vits-piper-it_IT-paola-medium-int8"),
            Voice("pt-faber", "pt", "Faber (Brasil)", "male", "vits-piper-pt_BR-faber-medium-int8"),
            Voice("ru-irina", "ru", "Irina", "female", "vits-piper-ru_RU-irina-medium-int8")
        )

        /** Prefix of a device voice's id where server voices are listed too. */
        const val PREFIX = "d:"

        private const val DONE = ".ok"
        private const val MAX_BYTES = 200L * 1024 * 1024
        private const val BUFFER = 1 shl 16
        private const val THREADS = 2
        private const val NOISE = 0.667f
        private const val NOISE_W = 0.8f
        private const val SILENCE = 0.2f
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2f
        private const val WAV_HEADER = 44
    }
}

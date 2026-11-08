package com.nelson.yomukai.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nelson.yomukai.data.prefs.NovelReaderSettings
import com.nelson.yomukai.data.speech.Dialogue
import com.nelson.yomukai.data.speech.Gender
import com.nelson.yomukai.data.sync.ServerSync
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the novel aloud paragraph by paragraph, on into the chapters appended after. The caller
 * supplies the text through [textAt] (flat paragraph index -> text, or null at the end of what is
 * loaded).
 *
 * Two engines:
 *  - Natural voices: the neural (Piper) voices of the Yomukai server the app syncs with. The next
 *    paragraphs are prepared while one is read, so there is no silence between them.
 *  - The phone's own text to speech, with the voice chosen among the installed ones, best first.
 * With voices by character, dialogue is read by the voice of whoever speaks when the text says it
 * ("—dijo ella", "he said"): a woman's or a man's, the narrator's otherwise.
 */
class NovelSpeech(
    context: Context,
    private val server: ServerSync,
    private val device: com.nelson.yomukai.data.speech.DeviceVoices,
    private val onNeedMore: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _current = MutableStateFlow<Int?>(null)

    /** Flat item index being spoken, or null when idle. */
    val current: StateFlow<Int?> = _current.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The phone's voices for the current language, the most natural first. */
    private val _systemVoices = MutableStateFlow<List<Voice>>(emptyList())
    val systemVoices: StateFlow<List<Voice>> = _systemVoices.asStateFlow()

    /** The server's natural voices (all languages); empty without a server that has them. */
    private val _naturalVoices = MutableStateFlow<List<ServerSync.ServerVoice>>(emptyList())
    val naturalVoices: StateFlow<List<ServerSync.ServerVoice>> = _naturalVoices.asStateFlow()

    /** A message for the user when the natural voices failed and the phone's took over. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var tts: TextToSpeech? = null
    private var textAt: (Int) -> String? = { null }
    private var settings = NovelReaderSettings()
    private var lang = "es"
    private var locale: Locale = Locale.getDefault()
    private var naturalFailed = false

    // System engine queue state.
    private var nextToQueue = 0
    private var waitingForMore = false

    // Natural engine state.
    private var naturalJob: Job? = null
    private var moreContent: CompletableDeferred<Unit>? = null
    private var player: MediaPlayer? = null
    private val prepared = LinkedHashMap<String, Deferred<File>>()

    init {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                engine.setLanguage(locale)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.substringBefore('.')?.toIntOrNull()?.let { _current.value = it }
                    }

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId?.substringBefore('.')?.toIntOrNull() ?: return
                        if (utteranceId.endsWith(LAST_CHUNK)) scope.launch { queueAhead(id) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        scope.launch { stop() }
                    }
                })
                refreshSystemVoices()
                _ready.value = true
            }
        }
        scope.launch {
            // The phone's own natural voices (downloaded in Ajustes) count too, and need no server.
            val onPhone = if (device.supported) {
                com.nelson.yomukai.data.speech.DeviceVoices.CATALOG.filter { it.id in device.installed.value }.map {
                    ServerSync.ServerVoice(com.nelson.yomukai.data.speech.DeviceVoices.PREFIX + it.id, it.label, it.lang, it.gender, tr("en el móvil"))
                }
            } else emptyList()
            _naturalVoices.value = server.voices() + onPhone
            if (_naturalVoices.value.isNotEmpty()) _ready.value = true
        }
    }

    private fun refreshSystemVoices() {
        val voices = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
        _systemVoices.value = voices
            .filter { it.locale.language == locale.language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    fun setLanguage(value: Locale) {
        locale = value
        lang = if (value.language == "en") "en" else "es"
        if (_ready.value) { tts?.setLanguage(value); refreshSystemVoices() }
    }

    fun update(value: NovelReaderSettings) {
        settings = value
        tts?.setSpeechRate(value.ttsRate)
    }

    private val useNatural get() = settings.ttsEngine != "system" && !naturalFailed && _naturalVoices.value.any { it.lang == lang }

    /** Starts reading at flat paragraph [index]. */
    fun start(index: Int, text: (Int) -> String?) {
        stop()
        textAt = text
        _notice.value = null
        if (useNatural) startNatural(index) else startSystem(index)
    }

    fun stop() {
        naturalJob?.cancel()
        naturalJob = null
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
        waitingForMore = false
        _current.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        prepared.values.forEach { it.cancel() }
        prepared.clear()
        scope.cancel()
        appContext.cacheDir.resolve(AUDIO_DIR).deleteRecursively()
    }

    /** Call when more paragraphs became available (the next chapter was appended). */
    fun onMoreContent() {
        moreContent?.complete(Unit)
        if (!waitingForMore) return
        waitingForMore = false
        queueAhead(nextToQueue - 1)
    }

    /** A short sample of a voice; [natural] picks the engine. */
    fun sample(voice: String, natural: Boolean) {
        stop()
        val text = if (lang == "en") "This is how I will read your novels. Do you like my voice?" else "Así leeré tus novelas. ¿Te gusta mi voz?"
        if (natural) {
            naturalJob = scope.launch {
                runCatching { play(fetchAudio(text, voice)) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) _notice.value = tr("No se pudo probar la voz: {0}", it.message) }
            }
        } else {
            val engine = tts ?: return
            engine.voices?.firstOrNull { it.name == voice }?.let { engine.voice = it } ?: engine.setLanguage(locale)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sample")
        }
    }

    // --- Pieces and voices -------------------------------------------------------------------

    private class Piece(val text: String, val voice: String?)

    private fun voiceKey(engine: String, role: String) = "$engine.$lang.$role"

    private fun chosen(engine: String, role: String): String? = settings.ttsVoices[voiceKey(engine, role)]?.takeIf { it.isNotBlank() }

    /** Default natural voices: a man narrates, women and men speak with their own. */
    private fun naturalDefault(role: String): String {
        // Without the server's voices for this language, the phone's: a man narrates when there is one.
        val serverHas = _naturalVoices.value.any { it.lang == lang && !it.id.startsWith(com.nelson.yomukai.data.speech.DeviceVoices.PREFIX) }
        if (!serverHas) {
            val local = _naturalVoices.value.filter { it.lang == lang }
            val wanted = if (role == "female") "female" else "male"
            (local.firstOrNull { it.gender == wanted } ?: local.firstOrNull())?.let { return it.id }
        }
        return serverDefault(role)
    }

    private fun serverDefault(role: String): String = when (lang) {
        "en" -> mapOf("narrator" to "en-lessac", "female" to "en-amy", "male" to "en-ryan")
        else -> mapOf("narrator" to "es-david", "female" to "es-lucia", "male" to "es-sergio")
    }.getValue(role)

    private fun pieces(paragraph: String, natural: Boolean): List<Piece> {
        val text = Dialogue.speakable(paragraph)
        if (!Dialogue.hasWords(text)) return emptyList()
        val parts = if (settings.ttsCharacters) Dialogue.split(text) else listOf(com.nelson.yomukai.data.speech.SpeechPart(text, false))
        val engine = if (natural) "natural" else "system"
        fun pick(role: String) = chosen(engine, role) ?: if (natural) naturalDefault(role) else null
        return parts.flatMap { part ->
            val role = when {
                !part.dialogue -> "narrator"
                part.gender == Gender.FEMALE -> "female"
                part.gender == Gender.MALE -> "male"
                // Unknown speaker: the narrator's voice, better than a wrong one.
                else -> "narrator"
            }
            val voice = pick(role) ?: pick("narrator")
            Dialogue.chunks(part.text, if (natural) 400 else 3500).map { Piece(it, voice) }
        }
    }

    // --- Phone's voices ------------------------------------------------------------------------

    private fun startSystem(index: Int) {
        if (tts == null || !_ready.value) return
        nextToQueue = index
        waitingForMore = false
        _current.value = index
        tts?.setSpeechRate(settings.ttsRate)
        queueAhead(index - 1)
    }

    /** Queues paragraphs until about [AHEAD] are waiting after [spokenIndex]. */
    private fun queueAhead(spokenIndex: Int) {
        val engine = tts ?: return
        if (_current.value == null && spokenIndex >= 0) return
        while (nextToQueue <= spokenIndex + AHEAD) {
            val text = textAt(nextToQueue)
            if (text == null) {
                // Past the end of what is loaded: ask for the next chapter and wait for [onMoreContent].
                waitingForMore = true
                onNeedMore()
                return
            }
            val list = pieces(text, natural = false)
            if (list.isEmpty()) {
                nextToQueue++
                continue
            }
            list.forEachIndexed { i, piece ->
                val voice = piece.voice?.let { name -> engine.voices?.firstOrNull { it.name == name } }
                if (voice != null) engine.voice = voice else engine.setLanguage(locale)
                val id = "$nextToQueue." + if (i == list.lastIndex) LAST_CHUNK else "part$i"
                engine.speak(piece.text, TextToSpeech.QUEUE_ADD, null, id)
            }
            nextToQueue++
        }
    }

    // --- Natural voices ------------------------------------------------------------------------

    private fun audioKey(piece: Piece) = "${piece.voice}|${settings.ttsRate}|${piece.text}"

    private suspend fun fetchAudio(text: String, voice: String): File = withContext(Dispatchers.IO) {
        val prefix = com.nelson.yomukai.data.speech.DeviceVoices.PREFIX
        val bytes = if (voice.startsWith(prefix)) device.speak(voice.removePrefix(prefix), text, settings.ttsRate)
        else server.speak(text, voice, settings.ttsRate)
        val dir = appContext.cacheDir.resolve(AUDIO_DIR).apply { mkdirs() }
        File(dir, "${(voice + text).hashCode().toUInt()}-${System.nanoTime()}.wav").apply { writeBytes(bytes) }
    }

    /** The audio of [piece], fetched once, also ahead of time. */
    private fun audioFor(piece: Piece): Deferred<File> {
        val key = audioKey(piece)
        prepared[key]?.let { return it }
        val job = scope.async(Dispatchers.IO) { fetchAudio(piece.text, piece.voice ?: naturalDefault("narrator")) }
        prepared[key] = job
        while (prepared.size > KEEP_AUDIO) {
            val oldest = prepared.keys.first()
            prepared.remove(oldest)?.let { old -> scope.launch { runCatching { old.await().delete() } } }
        }
        return job
    }

    private fun startNatural(index: Int) {
        _current.value = index
        naturalJob = scope.launch {
            var i = index
            while (isActive) {
                val text = textAt(i)
                if (text == null) {
                    val signal = CompletableDeferred<Unit>()
                    moreContent = signal
                    onNeedMore()
                    signal.await()
                    continue
                }
                _current.value = i
                // The next two paragraphs start being prepared now.
                for (ahead in 1..2) textAt(i + ahead)?.let { pieces(it, natural = true).forEach { p -> audioFor(p) } }
                for (piece in pieces(text, natural = true)) {
                    val file = try {
                        audioFor(piece).await()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // The server could not read it: the phone's voices go on from here.
                        naturalFailed = true
                        _notice.value = tr("Las voces naturales del servidor no responden ({0}); sigo con las del móvil.", e.message)
                        startSystem(i)
                        return@launch
                    }
                    play(file)
                }
                i++
            }
        }
    }

    /** Plays [file] to its end; cancelling stops it. */
    private suspend fun play(file: File) = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setOnCompletionListener { it.release(); if (player === it) player = null; if (cont.isActive) cont.resume(Unit) }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (player === p) player = null
            if (cont.isActive) cont.resumeWithException(IllegalStateException(tr("No se pudo reproducir la voz")))
            true
        }
        cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private companion object {
        const val AHEAD = 4
        const val LAST_CHUNK = "last"
        const val KEEP_AUDIO = 24
        const val AUDIO_DIR = "tts"
    }
}

@Composable
fun rememberNovelSpeech(onNeedMore: () -> Unit): NovelSpeech {
    val context = LocalContext.current
    val server = LocalAppContainer.current.serverSync
    val device = LocalAppContainer.current.deviceVoices
    val speech = remember { NovelSpeech(context, server, device, onNeedMore) }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    return speech
}

/** "Lucía · España · mujer" for a server voice. */
fun ServerSync.ServerVoice.display(): String =
    listOf(label, region, when (gender) { "f" -> "mujer"; "m" -> "hombre"; else -> "" }).filter { it.isNotBlank() }.joinToString(" · ")

/** A phone voice in words: its name, quality and whether it needs the internet. */
fun Voice.display(): String {
    val quality = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "calidad muy alta"
        quality >= Voice.QUALITY_HIGH -> "calidad alta"
        quality >= Voice.QUALITY_NORMAL -> "calidad normal"
        else -> "calidad baja"
    }
    val region = locale.displayCountry.takeIf { it.isNotBlank() }
    return listOfNotNull(name, region, quality, if (isNetworkConnectionRequired) tr("con internet") else null).joinToString(" · ")
}

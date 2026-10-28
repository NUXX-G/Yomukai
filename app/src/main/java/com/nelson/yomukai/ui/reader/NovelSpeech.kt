package com.nelson.yomukai.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reads the novel aloud paragraph by paragraph using the system [TextToSpeech] engine. The caller
 * supplies the text through [textAt] (flat paragraph index -> text, or null at the end of what is
 * loaded) so reading continues across chapters as they are appended.
 */
class NovelSpeech(context: Context, private val onNeedMore: () -> Unit) {
    private val _current = MutableStateFlow<Int?>(null)

    /** Flat item index being spoken, or null when idle. */
    val current: StateFlow<Int?> = _current.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var tts: TextToSpeech? = null
    private var textAt: (Int) -> String? = { null }
    private var nextToQueue = 0
    private var rate = 1f
    private var waitingForMore = false
    private var pendingLanguage: Locale = Locale.getDefault()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                engine.setLanguage(pendingLanguage)
                engine.setSpeechRate(rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.substringBefore('.')?.toIntOrNull()?.let { _current.value = it }
                    }

                    override fun onDone(utteranceId: String?) {
                        // Keep a short queue ahead of the voice.
                        val id = utteranceId?.substringBefore('.')?.toIntOrNull() ?: return
                        if (utteranceId.endsWith(LAST_CHUNK)) queueAhead(id)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        stop()
                    }
                })
                _ready.value = true
            }
        }
    }

    fun setLanguage(locale: Locale) {
        pendingLanguage = locale
        if (_ready.value) tts?.setLanguage(locale)
    }

    fun setRate(value: Float) {
        rate = value
        tts?.setSpeechRate(value)
    }

    /** Starts reading at flat paragraph [index]. */
    fun start(index: Int, text: (Int) -> String?) {
        val engine = tts ?: return
        if (!_ready.value) return
        textAt = text
        engine.stop()
        nextToQueue = index
        waitingForMore = false
        _current.value = index
        queueAhead(index - 1)
    }

    fun stop() {
        tts?.stop()
        _current.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _current.value = null
    }

    /** Queues paragraphs until about [AHEAD] are waiting after [spokenIndex]. */
    private fun queueAhead(spokenIndex: Int) {
        val engine = tts ?: return
        while (nextToQueue <= spokenIndex + AHEAD) {
            val text = textAt(nextToQueue)
            if (text == null) {
                // Past the end of what is loaded: ask for the next chapter and wait for [onMoreContent].
                waitingForMore = true
                onNeedMore()
                return
            }
            if (text.isBlank()) {
                nextToQueue++
                continue
            }
            val chunks = chunkForSpeech(text)
            chunks.forEachIndexed { i, chunk ->
                val id = "$nextToQueue." + if (i == chunks.lastIndex) LAST_CHUNK else "part$i"
                engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
            }
            nextToQueue++
        }
    }

    /** Call when more paragraphs became available (the next chapter was appended). */
    fun onMoreContent() {
        if (!waitingForMore) return
        waitingForMore = false
        queueAhead(nextToQueue - 1)
    }

    private companion object {
        const val AHEAD = 4
        const val LAST_CHUNK = "last"
    }
}

@Composable
fun rememberNovelSpeech(onNeedMore: () -> Unit): NovelSpeech {
    val context = LocalContext.current
    val speech = remember { NovelSpeech(context, onNeedMore) }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    return speech
}

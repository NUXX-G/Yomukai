package com.nelson.yomukai.data.ocr

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads aloud the bubbles of the page being read, in reading order and without sound effects, with the device's
 * voice. Pages hand over their text when they have been read ([offer]); the reader says which page is on screen
 * ([onPage]); each page is spoken once, when it becomes the current one.
 */
class BubbleSpeaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pages = ConcurrentHashMap<Int, List<String>>()
    private var current = -1
    private var spoken = -1
    private var language = ""

    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) { tts?.stop(); spoken = -1 } else maybeSpeak()
        }

    fun offer(page: Int, texts: List<String>, lang: String) {
        pages[page] = texts
        if (lang != language) { language = lang; tts?.language = Locale.forLanguageTag(lang) }
        maybeSpeak()
    }

    fun onPage(page: Int) {
        if (page == current) return
        current = page
        maybeSpeak()
    }

    /** A new chapter: page numbers start again. */
    fun reset() {
        pages.clear()
        spoken = -1
        current = -1
        tts?.stop()
    }

    @Synchronized
    private fun maybeSpeak() {
        if (!enabled || current < 0 || current == spoken) return
        val texts = pages[current] ?: return
        val engine = tts ?: TextToSpeech(context.applicationContext) { ok ->
            ready = ok == TextToSpeech.SUCCESS
            if (ready) { tts?.language = Locale.forLanguageTag(language); maybeSpeak() }
        }.also { tts = it }
        if (!ready) return
        spoken = current
        texts.forEachIndexed { i, t -> engine.speak(t, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "b$current-$i") }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}

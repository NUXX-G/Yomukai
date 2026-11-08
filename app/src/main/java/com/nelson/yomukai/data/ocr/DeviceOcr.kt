package com.nelson.yomukai.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Bubble text on the device with ML Kit: recognition (Latin, Chinese, Japanese, Korean) and translation. Nothing leaves
 * the phone; translation models (~30 MB) download once per language pair the first time they are needed.
 */
class DeviceOcr {
    private val recognizers = ConcurrentHashMap<String, TextRecognizer>()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val pages = object : LinkedHashMap<String, List<TextBox>>(MAX_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TextBox>>) = size > MAX_PAGES
    }
    private val lock = Mutex()

    /** Script family of a source language ("ZH", "ja"…) for the recognizer. */
    private fun script(lang: String) = when (lang.take(2).lowercase()) {
        "zh" -> "zh"
        "ja" -> "ja"
        "ko" -> "ko"
        else -> "latin"
    }

    private fun recognizer(lang: String): TextRecognizer = recognizers.getOrPut(script(lang)) {
        when (script(lang)) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * The text blocks of a page as fractions of it, bubbles and sound effects told apart. Tall strips (manhwa) are read
     * in slices, as the recognizer shrinks huge images until the text is unreadable. Results are kept per [key].
     */

    /** What was read of page [key] already, without reading it. */
    suspend fun peek(key: String): List<TextBox>? = lock.withLock { pages[key] }

    suspend fun read(key: String, bitmap: Bitmap, lang: String): List<TextBox> {
        lock.withLock { pages[key] }?.let { return it }
        val boxes = withContext(Dispatchers.Default) {
            val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            val w = source.width.toDouble()
            val h = source.height.toDouble()
            buildList {
                var top = 0
                while (top < source.height) {
                    val sliceH = minOf(SLICE, source.height - top)
                    val slice = if (top == 0 && sliceH == source.height) source else Bitmap.createBitmap(source, 0, top, source.width, sliceH)
                    val text = recognizer(lang).process(InputImage.fromBitmap(slice, 0)).await()
                    for (block in text.textBlocks) {
                        val r = block.boundingBox ?: continue
                        val content = block.text.replace('\n', ' ').trim()
                        if (content.isEmpty()) continue
                        val box = TextBox(r.left / w, (top + r.top) / h, r.width() / w, r.height() / h, content)
                        add(box.copy(kind = BubbleClassifier.classify(content, box.h, block.lines.size)))
                    }
                    if (slice !== source) slice.recycle()
                    top += sliceH
                }
                if (source !== bitmap) source.recycle()
            }
        }
        lock.withLock { pages[key] = boxes }
        return boxes
    }

    /** [texts] translated from [from] to [to] ("en", "zh"…); null when the pair is not available on the device. */
    suspend fun translate(texts: List<String>, from: String, to: String): List<String>? {
        val source = TranslateLanguage.fromLanguageTag(from.take(2).lowercase()) ?: return null
        val target = TranslateLanguage.fromLanguageTag(to.take(2).lowercase()) ?: return null
        if (source == target) return texts
        val translator = translators.getOrPut("$source>$target") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
        return runCatching {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            texts.map { translator.translate(it).await() }
        }.getOrNull()
    }

    private companion object {
        const val SLICE = 3_000
        const val MAX_PAGES = 60
    }
}

/** Bubbles to translate in a reader: from the source's language to the interface's. */
data class BubbleTranslation(
    val from: String,
    val to: String,
    val translate: Boolean = true,
    val learning: Boolean = false,
    val speak: Boolean = false
)

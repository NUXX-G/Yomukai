package com.nelson.yomukai.data.ocr

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart autoscroll for long strips: for each page, a speed factor per horizontal band ([ScrollPlanner.factor]) —
 * slower where the OCR found words, quicker over plain or empty bands. Works without OCR from the picture alone, on a
 * 48-pixel-wide copy of the page so a long manhwa strip costs almost no memory.
 */
class ScrollProfiles(private val context: Context, private val ocr: DeviceOcr) {
    private val profiles = ConcurrentHashMap<String, List<ScrollPlanner.Strip>>()

    /**
     * Factor at [fraction] (0..1) of the page shown by list item [itemKey], for a reader at [wpm] words per minute;
     * 1 when it has not been worked out.
     */
    fun factorAt(itemKey: Any?, fraction: Float, wpm: Double = ScrollPlanner.DEFAULT_WPM): Double {
        val p = profiles[itemKey as? String ?: return 1.0] ?: return 1.0
        return ScrollPlanner.factor(p[(fraction * p.size).toInt().coerceIn(0, p.size - 1)], wpm)
    }

    suspend fun compute(itemKey: String, model: Any?, headers: Map<String, String>, ocrKey: String) {
        if (profiles.containsKey(itemKey)) return
        val small = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(model).size(Dimension(SAMPLE_WIDTH), Dimension.Undefined).allowHardware(false)
                .apply { if (headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build()) }
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
        } ?: return
        profiles[itemKey] = withContext(Dispatchers.Default) { profile(small, ocr.peek(ocrKey)) }
        if (profiles.size > MAX_PAGES) profiles.keys.take(profiles.size - MAX_PAGES).forEach(profiles::remove)
    }

    private fun profile(bitmap: Bitmap, boxes: List<TextBox>?): List<ScrollPlanner.Strip> {
        val w = bitmap.width
        val h = bitmap.height
        // Bands about as tall as they are wide on screen; at least one.
        val count = (h / w).coerceIn(1, MAX_BANDS)
        val words = boxes?.let { ScrollPlanner.wordsPerStrip(it, count) }
        return List(count) { i ->
            val top = h * i / count
            val bottom = h * (i + 1) / count
            val pixels = IntArray(w * (bottom - top))
            bitmap.getPixels(pixels, 0, w, 0, top, w, bottom - top)
            val grey = IntArray(pixels.size) { p -> val c = pixels[p]; ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10 }
            val (empty, detail) = ScrollPlanner.analyze(grey, w)
            ScrollPlanner.Strip(bottom - top, words?.get(i) ?: 0, detail, empty && (words?.get(i) ?: 0) == 0)
        }
    }

    private companion object {
        const val SAMPLE_WIDTH = 48
        const val MAX_BANDS = 80
        const val MAX_PAGES = 300
    }
}

package com.nelson.yomukai.ui.reader

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/** Finds the content area of a page so blank white margins can be cut away. */
object BorderCrop {
    /** A pixel counts as paper when all three channels are at least this bright. */
    private const val WHITE = 240

    /** Rows/columns with at most this share of non-white pixels still count as blank (scan noise). */
    private const val NOISE = 0.005f

    /**
     * @return `[left, top, right, bottom]` (right/bottom exclusive) of the content, or null when
     * nothing worthwhile (under [minTrimFraction] of either dimension) would be trimmed.
     */
    fun contentBounds(pixels: IntArray, width: Int, height: Int, minTrimFraction: Float = 0.02f): IntArray? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null

        fun nonWhite(p: Int) = ((p shr 16) and 0xFF) < WHITE || ((p shr 8) and 0xFF) < WHITE || (p and 0xFF) < WHITE

        val rowLimit = (width * NOISE).toInt()
        val colLimit = (height * NOISE).toInt()
        fun rowBlank(y: Int): Boolean {
            var count = 0
            for (x in 0 until width) if (nonWhite(pixels[y * width + x]) && ++count > rowLimit) return false
            return true
        }
        fun colBlank(x: Int): Boolean {
            var count = 0
            for (y in 0 until height) if (nonWhite(pixels[y * width + x]) && ++count > colLimit) return false
            return true
        }

        var top = 0
        while (top < height && rowBlank(top)) top++
        if (top >= height) return null // an entirely blank page: leave it alone
        var bottom = height
        while (bottom > top + 1 && rowBlank(bottom - 1)) bottom--
        var left = 0
        while (left < width && colBlank(left)) left++
        var right = width
        while (right > left + 1 && colBlank(right - 1)) right--

        val trimmedEnough = (left + (width - right)) >= width * minTrimFraction ||
            (top + (height - bottom)) >= height * minTrimFraction
        return if (trimmedEnough) intArrayOf(left, top, right, bottom) else null
    }
}

/** Coil transformation applying [BorderCrop]; tall strips (webtoon slices) are skipped to save memory. */
class TrimBordersTransformation : Transformation() {
    override val cacheKey: String = "trim_borders_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.height > input.width * 3) return input
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val b = BorderCrop.contentBounds(pixels, input.width, input.height) ?: return input
        return Bitmap.createBitmap(input, b[0], b[1], b[2] - b[0], b[3] - b[1])
    }
}

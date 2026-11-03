package com.nelson.yomukai.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Picks the accent color of a cover and derives readable tones from it. Works on plain ARGB ints,
 * so it is pure and unit tested; [CoverTheme] turns the result into a Material color scheme.
 */
object CoverPalette {
    /**
     * The dominant colorful hue of [pixels]: pixels are grouped by hue, each group weighs its
     * saturation (gray, near-black and near-white pixels barely count), and the heaviest group's
     * average is returned. Null when the image has no real color (manga covers are often grayscale).
     */
    fun dominant(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(HUE_BUCKETS)
        val sumR = DoubleArray(HUE_BUCKETS)
        val sumG = DoubleArray(HUE_BUCKETS)
        val sumB = DoubleArray(HUE_BUCKETS)
        var colorful = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            val hsl = toHsl(pixel)
            val (h, s, l) = hsl
            if (s < MIN_SATURATION || l < MIN_LIGHTNESS || l > MAX_LIGHTNESS) continue
            colorful++
            val bucket = ((h / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            // Mid lightness and strong saturation make the color that reads as "the cover's color".
            val w = s * (1f - abs(l - 0.5f) * 1.2f)
            weight[bucket] += w
            sumR[bucket] += ((pixel shr 16) and 0xFF) * w
            sumG[bucket] += ((pixel shr 8) and 0xFF) * w
            sumB[bucket] += (pixel and 0xFF) * w
        }
        if (colorful < pixels.size * MIN_COLORFUL_SHARE) return null
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] <= 0.0) return null
        val w = weight[best]
        return argb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt())
    }

    /** [color] with its lightness set to [lightness] and saturation capped, for text and accents. */
    fun tone(color: Int, lightness: Float, maxSaturation: Float = 0.75f): Int {
        val (h, s, _) = toHsl(color)
        return fromHsl(h, min(s, maxSaturation), lightness)
    }

    /** WCAG contrast ratio between two opaque colors. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) + 0.7152 * channel((color shr 8) and 0xFF) + 0.0722 * channel(color and 0xFF)
    }

    fun toHsl(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        if (maxC == minC) return Triple(0f, 0f, l)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(((r + m) * 255f).toInt(), ((g + m) * 255f).toInt(), ((b + m) * 255f).toInt())
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private const val HUE_BUCKETS = 12
    private const val MIN_SATURATION = 0.22f
    private const val MIN_LIGHTNESS = 0.12f
    private const val MAX_LIGHTNESS = 0.9f
    private const val MIN_COLORFUL_SHARE = 0.04
}

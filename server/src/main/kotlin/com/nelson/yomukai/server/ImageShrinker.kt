package com.nelson.yomukai.server

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/**
 * Pages far bigger than the screen they are read on (4000 px scans, 5 MB PNGs) are the slowest to
 * arrive, and the reader shows them at the screen's width anyway. Such pages are re-drawn at that
 * width as good-quality JPEG, often a tenth of the size. Small or already light images go untouched.
 */
object ImageShrinker {
    /** Widths the browser may ask for: few, so the same size is shared (and cached) by many screens. */
    private val WIDTHS = listOf(720, 1080, 1440, 1920, 2560)

    /** The width bucket for a requested width; null when none or nonsense was asked for. */
    fun bucket(requested: String?): Int? {
        val w = requested?.toIntOrNull()?.takeIf { it in 100..10_000 } ?: return null
        return WIDTHS.firstOrNull { it >= w } ?: WIDTHS.last()
    }

    /**
     * A JPEG of [bytes] no wider than [maxWidth], or null when the original is as good a choice:
     * already small enough, an animation, unreadable, or not really lighter once shrunk.
     */
    fun shrink(bytes: ByteArray, type: String, maxWidth: Int): ByteArray? {
        if (bytes.size < MIN_BYTES || "gif" in type) return null
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
                val tooWide = width > maxWidth * 1.5
                val pixels = width.toLong() * height
                val heavy = bytes.size > HEAVY_BYTES && ("png" in type || "bmp" in type) && pixels <= DECODE_BUDGET
                if (!tooWide && !heavy) return null
                // Reading every n-th pixel of a huge page saves most of the decoding; the final
                // smooth scaling below still works from twice the target width at least.
                val param = reader.defaultReadParam
                // The decoded picture never takes more than DECODE_BUDGET pixels of memory, however big the file says it is.
                val budgetStep = kotlin.math.ceil(kotlin.math.sqrt(pixels.toDouble() / DECODE_BUDGET)).toInt()
                val step = max(max(1, width / (maxWidth * 2)), budgetStep)
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val source = reader.read(0, param)
                val targetW = if (tooWide) maxWidth else source.width
                val targetH = max(1, (source.height.toLong() * targetW / source.width).toInt())
                val out = scale(source, targetW, targetH)
                val jpeg = encode(out)
                return jpeg.takeIf { it.size < bytes.size * 7 / 10 }
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * An uploaded picture (avatar, banner) always re-drawn as a plain JPEG: whatever the file carried (metadata,
     * odd formats, tricks for other decoders) is gone. [aspect] crops the centre to width/height (1.0 = square).
     * Null when the file is not a readable image or is absurdly large.
     */
    fun reencode(bytes: ByteArray, maxWidth: Int, aspect: Double): ByteArray? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        return input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
                val pixels = width.toLong() * height
                val param = reader.defaultReadParam
                val step = kotlin.math.ceil(kotlin.math.sqrt(pixels.toDouble() / DECODE_BUDGET)).toInt().coerceAtLeast(1)
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val source = reader.read(0, param)
                // Centre crop to the wanted shape.
                val (cw, ch) = if (source.width.toDouble() / source.height > aspect) {
                    (source.height * aspect).toInt().coerceAtLeast(1) to source.height
                } else {
                    source.width to (source.width / aspect).toInt().coerceAtLeast(1)
                }
                val cropped = source.getSubimage((source.width - cw) / 2, (source.height - ch) / 2, cw, ch)
                val targetW = minOf(maxWidth, cw)
                val targetH = max(1, (ch.toLong() * targetW / cw).toInt())
                encode(scale(cropped, targetW, targetH))
            } catch (e: Exception) {
                null
            } finally {
                reader.dispose()
            }
        }
    }

    /** Halves while far above the target (sharp result), then one smooth step; on white for transparent PNGs. */
    private fun scale(image: BufferedImage, w: Int, h: Int): BufferedImage {
        var current = image
        var cw = image.width
        var ch = image.height
        while (cw / 2 >= w) {
            cw /= 2; ch = max(1, ch / 2)
            current = draw(current, cw, ch)
        }
        return draw(current, w, h)
    }

    private fun draw(image: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(image, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encode(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = QUALITY
                if (canWriteProgressive()) progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
            writer.write(null, IIOImage(image, null, null), param)
            writer.dispose()
        }
        return out.toByteArray()
    }

    private const val MIN_BYTES = 300 * 1024
    private const val HEAVY_BYTES = 1_500_000
    private const val MAX_PIXELS = 60_000_000L
    private const val DECODE_BUDGET = 24_000_000L
    private const val QUALITY = 0.86f
}

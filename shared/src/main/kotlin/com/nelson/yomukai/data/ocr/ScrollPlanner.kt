package com.nelson.yomukai.data.ocr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Smart autoscroll: each page is cut into horizontal strips, and each strip gets a time on screen. Slow where there is
 * text (by its words and the reader's own speed), quicker over panels without text (a bit slower when the drawing is
 * detailed), almost instant over empty gutters. The reader's speed is learnt from how they correct the scroll.
 */
object ScrollPlanner {
    /** One strip of a page: its height in pixels, words in it (from OCR), how detailed the drawing is (0..1), empty or not. */
    data class Strip(val height: Int, val words: Int, val detail: Double, val empty: Boolean)

    const val DEFAULT_WPM = 220.0
    const val MIN_WPM = 80.0
    const val MAX_WPM = 700.0

    /** Milliseconds a strip should take at [wpm] words per minute. */
    fun durationMs(strip: Strip, wpm: Double = DEFAULT_WPM): Long = when {
        strip.empty -> EMPTY_MS
        strip.words > 0 -> (BASE_TEXT_MS + strip.words / wpm.coerceIn(MIN_WPM, MAX_WPM) * 60_000).toLong()
        else -> (PLAIN_PANEL_MS + strip.detail.coerceIn(0.0, 1.0) * (DETAILED_PANEL_MS - PLAIN_PANEL_MS)).toLong()
    }

    /** Pixels per second for each strip (what the reader animates, easing between neighbours). */
    fun speeds(strips: List<Strip>, wpm: Double = DEFAULT_WPM): List<Double> =
        strips.map { s -> s.height * 1000.0 / durationMs(s, wpm).coerceAtLeast(1) }

    /**
     * New words-per-minute after the reader slowed down, sped up or went back over a text strip: [expectedMs] is what the
     * plan gave it, [actualMs] what it really took. A moving average, so one distraction does not change much.
     */
    fun learn(wpm: Double, expectedMs: Long, actualMs: Long, weight: Double = 0.2): Double {
        if (expectedMs <= 0 || actualMs <= 0) return wpm
        val observed = wpm * expectedMs / actualMs
        return (wpm * (1 - weight) + observed * weight).coerceIn(MIN_WPM, MAX_WPM)
    }

    /**
     * Plan B without OCR, from the picture alone: grey values (0..255) of a strip, sampled. Nearly flat means an empty
     * gutter; how much neighbouring pixels differ says how detailed the drawing is.
     */
    fun analyze(grey: IntArray, width: Int): Pair<Boolean, Double> {
        if (grey.isEmpty() || width <= 1) return true to 0.0
        val mean = grey.average()
        val sd = sqrt(grey.sumOf { (it - mean) * (it - mean) } / grey.size)
        if (sd < EMPTY_SD) return true to 0.0
        var edges = 0
        var pairs = 0
        for (i in grey.indices) {
            if ((i + 1) % width == 0) continue
            if (i + 1 < grey.size) { pairs++; if (abs(grey[i] - grey[i + 1]) > EDGE_STEP) edges++ }
        }
        val detail = if (pairs == 0) 0.0 else (edges.toDouble() / pairs / EDGE_FULL).coerceIn(0.0, 1.0)
        return false to detail
    }

    private const val EMPTY_MS = 150L
    private const val BASE_TEXT_MS = 400.0
    private const val PLAIN_PANEL_MS = 900.0
    private const val DETAILED_PANEL_MS = 2_000.0
    private const val EMPTY_SD = 6.0
    private const val EDGE_STEP = 24
    private const val EDGE_FULL = 0.35
}

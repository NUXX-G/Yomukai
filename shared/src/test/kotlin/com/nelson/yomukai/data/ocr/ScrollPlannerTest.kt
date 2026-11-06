package com.nelson.yomukai.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPlannerTest {
    @Test
    fun textIsSlowPanelsQuickerGuttersAlmostInstant() {
        val text = ScrollPlanner.Strip(400, words = 22, detail = 0.3, empty = false)
        val busy = ScrollPlanner.Strip(400, words = 0, detail = 1.0, empty = false)
        val plain = ScrollPlanner.Strip(400, words = 0, detail = 0.0, empty = false)
        val gutter = ScrollPlanner.Strip(400, words = 0, detail = 0.0, empty = true)
        val t = listOf(text, busy, plain, gutter).map { ScrollPlanner.durationMs(it, 220.0) }
        assertTrue(t[0] > t[1] && t[1] > t[2] && t[2] > t[3])
        assertEquals(400 + 6_000, t[0].toInt())
        // A faster reader gets less time for the same words.
        assertTrue(ScrollPlanner.durationMs(text, 440.0) < t[0])
        val speeds = ScrollPlanner.speeds(listOf(text, gutter))
        assertTrue(speeds[1] > speeds[0] * 10)
    }

    @Test
    fun theReadersSpeedIsLearntSlowly() {
        // Took twice the planned time: the speed goes down, but only part of the way.
        val slower = ScrollPlanner.learn(220.0, expectedMs = 5_000, actualMs = 10_000)
        assertTrue(slower < 220.0 && slower > 110.0)
        assertEquals(220.0, ScrollPlanner.learn(220.0, 0, 100), 1e-9)
        assertEquals(ScrollPlanner.MAX_WPM, ScrollPlanner.learn(690.0, 10_000, 100), 1e-9)
    }

    @Test
    fun withoutOcrTheImageItselfTellsGuttersFromDrawings() {
        val flat = IntArray(400) { 250 }
        assertEquals(true to 0.0, ScrollPlanner.analyze(flat, 20))
        val stripes = IntArray(400) { if (it % 2 == 0) 0 else 255 }
        val (empty, detail) = ScrollPlanner.analyze(stripes, 20)
        assertEquals(false, empty)
        assertEquals(1.0, detail, 1e-9)
        val soft = IntArray(400) { 100 + (it % 20) }
        assertTrue(ScrollPlanner.analyze(soft, 20).second < 0.1)
    }
}

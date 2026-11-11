package com.nelson.yomukai.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** F6.2: the same speed at every frame rate and every speed, and no jumps. */
class AutoScrollPaceTest {
    private fun run(speed: Float, fps: Int, seconds: Int): Int {
        var carry = 0f
        var moved = 0
        val frame = 1_000_000_000L / fps
        var t = 0L
        repeat(fps * seconds) {
            val dt = AutoScrollPace.frameSeconds(t, t + frame)
            t += frame
            val (px, rest) = AutoScrollPace.pixels(carry, speed, dt)
            carry = rest
            moved += px
        }
        return moved
    }

    @Test
    fun everySpeedAtEveryFrameRate() {
        for (fps in listOf(30, 60, 90, 120)) {
            for (speed in listOf(3f, 12f, 40f, 120f, 1000f)) {
                val measured = run(speed, fps, 10) / 10f
                assertTrue("$speed px/s at $fps fps gave $measured", abs(measured - speed) <= maxOf(1f, speed * 0.02f))
            }
        }
    }

    @Test
    fun aLongFrameIsNotAJump() {
        assertEquals(AutoScrollPace.MAX_FRAME_S, AutoScrollPace.frameSeconds(0, 2_000_000_000L))
        assertEquals(0f, AutoScrollPace.frameSeconds(10, 5))
    }

    @Test
    fun smartStaysAroundThePaceAndOnlyFollowsTheReadingSpeed() {
        assertEquals(3.0, AutoScrollPace.clampFactor(40.0), 0.0)
        assertEquals(0.35, AutoScrollPace.clampFactor(0.01), 0.0)
        assertEquals(1.0, AutoScrollPace.clampFactor(Double.NaN), 0.0)
        assertEquals(80f, AutoScrollPace.smartBase(40f, 440f))
        assertEquals(40f, AutoScrollPace.smartBase(40f, 0f))
        assertTrue(AutoScrollPace.ease(1.0, 3.0, 1 / 60f) < 1.2)
    }
}

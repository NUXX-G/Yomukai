package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import com.nelson.yomukai.data.reader.AutoScrollPace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The auto-scroll loop of both readers (F6.2). It used to die silently the first time the reader touched the list
 * (a drag cancels any running scroll, and that cancellation ended the loop while the button still said "playing").
 * Now a touch pauses it for [AutoScrollPace.RESUME_AFTER_MS] and it goes on; a long frame never jumps; at the end it
 * waits while [waitMore] says the next chapter is coming, and calls [onEnd] when nothing more will come.
 * [speed] is read every frame (pixels per second), so changing it does not restart anything.
 */
suspend fun runAutoScroll(
    state: ScrollableState,
    speed: () -> Float,
    dragged: () -> Boolean,
    waitMore: () -> Boolean,
    onEnd: () -> Unit
) {
    val loop = AutoScrollLoop(state, speed, dragged, waitMore)
    var last = withFrameNanos { it }
    while (currentCoroutineContext().isActive) {
        val now = withFrameNanos { it }
        val dt = AutoScrollPace.frameSeconds(last, now)
        last = now
        if (!loop.step(now, dt)) { onEnd(); return }
    }
}

/** One frame of [runAutoScroll]; false when it has reached the end and nothing more is coming. */
private class AutoScrollLoop(
    private val state: ScrollableState,
    private val speed: () -> Float,
    private val dragged: () -> Boolean,
    private val waitMore: () -> Boolean
) {
    private var carry = 0f
    private var pausedUntil = 0L
    private var current = 0.0
    private var waitingSince = 0L

    suspend fun step(now: Long, dt: Float): Boolean {
        when {
            dragged() -> pause(now)
            now < pausedUntil -> Unit
            !state.canScrollForward -> {
                if (waitingSince == 0L) waitingSince = now
                // The next chapter may take a while (a slow source), but not forever (there may be none).
                return waitMore() && now - waitingSince < MAX_WAIT_NANOS
            }
            else -> move(now, dt)
        }
        return true
    }

    private fun pause(now: Long) {
        pausedUntil = now + AutoScrollPace.RESUME_AFTER_MS * 1_000_000
        current = 0.0
        carry = 0f
    }

    private suspend fun move(now: Long, dt: Float) {
        waitingSince = 0L
        val target = speed().toDouble().coerceAtLeast(0.0)
        current = if (current == 0.0) target else AutoScrollPace.ease(current, target, dt)
        val (px, rest) = AutoScrollPace.pixels(carry, current.toFloat(), dt)
        carry = rest
        if (px == 0) return
        try {
            state.scrollBy(px.toFloat())
        } catch (e: CancellationException) {
            // The reader grabbed the list: only the screen going away ends the loop.
            if (!currentCoroutineContext().isActive) throw e
            pause(now)
        }
    }
}

private const val MAX_WAIT_NANOS = 30_000_000_000L

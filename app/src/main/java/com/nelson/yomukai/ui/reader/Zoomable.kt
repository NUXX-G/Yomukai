package com.nelson.yomukai.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class ZoomState(private val maxScale: Float = 4f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Zooms around [centroid] (layout coordinates) and applies [pan], keeping content inside bounds. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val newScale = (scale * zoom).coerceIn(1f, maxScale)
        val realZoom = newScale / scale
        val center = Offset(size.width / 2f, size.height / 2f)
        val fromCenter = centroid - center
        val newOffset = offset * realZoom + fromCenter * (1 - realZoom) + pan
        scale = newScale
        offset = clamp(newOffset, size)
    }

    fun pan(delta: Offset, size: IntSize) {
        offset = clamp(offset + delta, size)
    }

    fun snapTo(newScale: Float, newOffset: Offset, size: IntSize) {
        scale = newScale
        offset = clamp(newOffset, size)
    }

    private fun clamp(value: Offset, size: IntSize): Offset {
        val maxX = size.width * (scale - 1) / 2f
        val maxY = size.height * (scale - 1) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Pinch-to-zoom that cooperates with scrolling parents/children:
 *  - two-finger gestures are always captured (Initial pass, so lists/pagers don't scroll),
 *  - one-finger drags are only captured while zoomed; with [lockVerticalPan] the vertical
 *    component is left to the scrolling list (webtoon mode).
 */
fun Modifier.zoomable(
    state: ZoomState,
    scope: CoroutineScope,
    lockVerticalPan: Boolean = false,
    onTap: ((Offset, IntSize) -> Unit)? = null
): Modifier = this
    .pointerInput(state, lockVerticalPan) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    state.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = true), size)
                    event.changes.forEach { it.consume() }
                } else if (pressed == 1 && state.isZoomed) {
                    val pan = event.calculatePan()
                    if (lockVerticalPan) {
                        state.pan(Offset(pan.x, 0f), size)
                    } else {
                        state.pan(pan, size)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state, onTap) {
        detectTapGestures(
            onDoubleTap = { tap ->
                val anim = Animatable(state.scale)
                val target = if (state.isZoomed) 1f else 2.5f
                val center = Offset(size.width / 2f, size.height / 2f)
                val targetOffset = if (target > 1f) (center - tap) * (target - 1f) else Offset.Zero
                val startOffset = state.offset
                val startScale = state.scale
                scope.launch {
                    anim.animateTo(target) {
                        val fraction = if (target == startScale) 1f else (value - startScale) / (target - startScale)
                        state.snapTo(value, startOffset + (targetOffset - startOffset) * fraction, size)
                    }
                }
            },
            onTap = { onTap?.invoke(it, size) }
        )
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

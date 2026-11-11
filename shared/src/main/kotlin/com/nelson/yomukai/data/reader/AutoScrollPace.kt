package com.nelson.yomukai.data.reader

/**
 * The arithmetic of auto-scroll (F6.2), the same rules as the web's lib/autoscroll.js:
 * - a long frame (a busy phone, the app coming back) never becomes a jump: at most [MAX_FRAME_S] per frame;
 * - speed changes are eased, never sudden;
 * - the smart mode stays around the reader's own pace and never uses the normal mode's speed.
 */
object AutoScrollPace {
    const val MAX_FRAME_S = 0.05f

    /** A finger on the page pauses auto-scroll this long after it lets go; then it goes on by itself. */
    const val RESUME_AFTER_MS = 1_500L

    /** Reading pace at ×1 in the smart mode. */
    const val BASE_WPM = 220f

    fun frameSeconds(previousNanos: Long, nowNanos: Long): Float = ((nowNanos - previousNanos) / 1e9f).coerceIn(0f, MAX_FRAME_S)

    fun ease(current: Double, target: Double, dt: Float, rate: Double = 2.5): Double = current + (target - current) * minOf(1.0, dt * rate)

    /** A smart factor kept between [low]× and [high]× (a gap is not 15× faster, a busy page is not stuck). */
    fun clampFactor(factor: Double, low: Double = 0.35, high: Double = 3.0): Double = if (factor.isNaN() || factor <= 0) 1.0 else factor.coerceIn(low, high)

    /** The smart mode's speed at ×1 for a reading pace: [basePx] per second at [BASE_WPM] words a minute. */
    fun smartBase(basePx: Float, wpm: Float): Float = basePx * (if (wpm > 0) wpm else BASE_WPM) / BASE_WPM

    /**
     * Pixels to scroll this frame, carrying the fraction to the next one (some list widgets drop fractions):
     * returns the whole pixels and the new carry.
     */
    fun pixels(carry: Float, speed: Float, dt: Float): Pair<Int, Float> {
        val total = carry + speed * dt
        val whole = total.toInt()
        return whole to (total - whole)
    }
}

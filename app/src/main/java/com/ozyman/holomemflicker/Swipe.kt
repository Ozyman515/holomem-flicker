package com.ozyman.holomemflicker

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A button mapped as a SWIPE: one press = finger down at the button's spot, a straight slide
 * to the end of its arrow, then lift (even if the button is still held). Made for fixed-direction
 * flick notes in Live, so the default is short and fast.
 */
object Swipe {
    /** Default swipe when a point is switched to swipe: straight up, about a flick's length. */
    const val DEFAULT_DY = -0.08f // share of screen height
    const val DEFAULT_MS = 60

    /** Swipe durations the editor's Speed button cycles through (ms). */
    val SPEEDS = listOf(30, 45, 60, 80, 120, 200, 400)

    /** One move every this many ms while sliding (about 120 Hz). */
    const val STEP_MS = 8

    fun nextSpeed(ms: Int): Int {
        val i = SPEEDS.indexOfFirst { it > ms }
        return if (i < 0) SPEEDS[0] else SPEEDS[i]
    }

    /** Is this tap point a swipe? (an arrow at least a few pixels long) */
    fun isSwipe(t: Prefs.TapPoint): Boolean = t.sdx != 0f || t.sdy != 0f

    /**
     * The slide in time steps: (delay after touch-down in ms, x, y) for each move.
     * The last step lands exactly on the end point, after which the finger lifts.
     */
    fun path(x0: Float, y0: Float, x1: Float, y1: Float, ms: Int): List<Triple<Long, Float, Float>> {
        val steps = max(2, (ms.toFloat() / STEP_MS).roundToInt())
        return (1..steps).map { k ->
            val f = k.toFloat() / steps
            Triple((ms.toLong() * k) / steps, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
        }
    }

    /** Readable direction for the editor banner. */
    fun describe(t: Prefs.TapPoint, w: Int, h: Int): String {
        val dx = t.sdx * w
        val dy = t.sdy * h
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).roundToInt()
        val deg = Math.toDegrees(Math.atan2(-dy.toDouble(), dx.toDouble()))
        val dir = when {
            deg >= 67.5 && deg < 112.5 -> "up"
            deg >= 22.5 && deg < 67.5 -> "up-right"
            deg >= -22.5 && deg < 22.5 -> "right"
            deg >= -67.5 && deg < -22.5 -> "down-right"
            deg >= -112.5 && deg < -67.5 -> "down"
            deg >= -157.5 && deg < -112.5 -> "down-left"
            deg >= 112.5 && deg < 157.5 -> "up-left"
            else -> "left"
        }
        return "swipe $dir, $len px in ${t.sms} ms"
    }
}

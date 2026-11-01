package com.ozyman.holomemflicker

import kotlin.math.hypot

/**
 * What a finger can grab in the layout editors (both the full-screen one and the in-game one):
 * tap index (0+), [LEFT] / [RIGHT] stick, or the aiming handle at the end of a selected swipe's arrow.
 */
object EditorTargets {
    const val NONE = HoldToMove.NONE
    const val LEFT = -1
    const val RIGHT = -2
    private const val HANDLE_BASE = -1000

    fun handleOf(tap: Int): Int = HANDLE_BASE - tap
    fun isHandle(target: Int): Boolean = target <= HANDLE_BASE && target != NONE
    fun tapOfHandle(target: Int): Int = HANDLE_BASE - target

    /** The tap / stick a target belongs to (a handle belongs to its tap). */
    fun owner(target: Int): Int = if (isHandle(target)) tapOfHandle(target) else target

    /** Closest marker to (x, y) on a [w]×[h] board; the selected swipe's handle wins when touched. */
    fun hit(d: Prefs.Layout, selected: Int, x: Float, y: Float, w: Int, h: Int): Int {
        d.taps.getOrNull(owner(selected))?.let { t ->
            if (Swipe.isSwipe(t)) {
                val hx = (t.fx + t.sdx) * w
                val hy = (t.fy + t.sdy) * h
                if (hypot(hx - x, hy - y) < 60f) return handleOf(owner(selected))
            }
        }
        var best = NONE
        var bestD = 90f
        d.taps.forEachIndexed { i, t ->
            val dd = hypot(t.fx * w - x, t.fy * h - y) - maxOf(0f, t.fr * w - 45f)
            if (dd < bestD) { bestD = dd; best = i }
        }
        val dl = hypot(d.lfx * w - x, d.lfy * h - y)
        if (dl < bestD) { bestD = dl; best = LEFT }
        val dr = hypot(d.rfx * w - x, d.rfy * h - y)
        if (dr < bestD) best = RIGHT
        return best
    }

    /** Puts [target] at (fx, fy) (screen fractions). A handle re-aims its swipe; the point itself stays. */
    fun moveTo(d: Prefs.Layout, target: Int, fx: Float, fy: Float) {
        val x = fx.coerceIn(0f, 1f)
        val y = fy.coerceIn(0f, 1f)
        when {
            target == LEFT -> { d.lfx = x; d.lfy = y }
            target == RIGHT -> { d.rfx = x; d.rfy = y }
            isHandle(target) -> d.taps.getOrNull(tapOfHandle(target))?.let { t ->
                t.sdx = x - t.fx
                t.sdy = y - t.fy
                if (t.sdx == 0f && t.sdy == 0f) t.sdy = -0.001f // never collapse back into a plain tap
            }
            target >= 0 -> d.taps.getOrNull(target)?.let { it.fx = x; it.fy = y }
        }
    }

    /** Swipe button in the editors: plain tap ↔ swipe (straight up by default). Returns the new banner text. */
    fun toggleSwipe(t: Prefs.TapPoint, w: Int, h: Int): String {
        return if (Swipe.isSwipe(t)) {
            t.sdx = 0f
            t.sdy = 0f
            "${Prefs.buttonName(t.code)} is a plain tap again."
        } else {
            t.sdx = 0f
            t.sdy = Swipe.DEFAULT_DY
            if (t.sms <= 0) t.sms = Swipe.DEFAULT_MS
            "${Prefs.buttonName(t.code)}: ${Swipe.describe(t, w, h)}. Hold the arrow's round end, then drag it to aim."
        }
    }

    /** Speed button: cycles the selected swipe's duration. Returns the banner text, or null if it isn't a swipe. */
    fun cycleSpeed(t: Prefs.TapPoint, w: Int, h: Int): String? {
        if (!Swipe.isSwipe(t)) return null
        t.sms = Swipe.nextSpeed(t.sms)
        return "${Prefs.buttonName(t.code)}: ${Swipe.describe(t, w, h)}."
    }
}

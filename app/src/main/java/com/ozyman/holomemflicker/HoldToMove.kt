package com.ozyman.holomemflicker

import kotlin.math.hypot

/**
 * Touch rules for the layout editors, so fine-tuning with the arrows never moves a marker by accident:
 *  - touching a marker that isn't selected only SELECTS it (nothing moves, however the finger slides)
 *  - touching the selected marker and HOLDING it for [holdMs] picks it up; only then does it follow the finger
 *  - sliding off before the hold fires cancels the pick-up (the marker stays put)
 *
 * Targets are plain ints chosen by the editor (tap index, -1 / -2 for the sticks, swipe handles, …);
 * [NONE] means empty space. Pure logic, so it can be unit-tested without a screen.
 */
class HoldToMove(val holdMs: Long = HOLD_MS, val slopPx: Float = SLOP_PX) {

    /** The marker under the finger that will move once the hold fires ([NONE] = nothing will move). */
    var armed = NONE
        private set

    /** True once the hold has fired: the [armed] marker follows the finger. */
    var moving = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /**
     * Finger down on [hit] ([NONE] = empty space). [wasSelected]: that marker was already selected
     * before this touch - only then can a hold pick it up; otherwise this touch just selects it.
     */
    fun down(hit: Int, wasSelected: Boolean, x: Float, y: Float, now: Long) {
        moving = false
        armed = if (hit != NONE && wasSelected) hit else NONE
        downX = x
        downY = y
        downAt = now
    }

    /**
     * Called when the hold timer fires (and on every move). Returns true the moment the pick-up happens.
     */
    fun holdCheck(now: Long): Boolean {
        if (armed == NONE || moving) return false
        if (now - downAt < holdMs) return false
        moving = true
        return true
    }

    /**
     * Finger moved to (x, y). Returns the marker to move there, or [NONE] if nothing should move.
     */
    fun move(x: Float, y: Float, now: Long): Int {
        if (armed == NONE) return NONE
        if (!moving) {
            if (hypot(x - downX, y - downY) > slopPx) { // slid off before the hold: not a pick-up
                armed = NONE
                return NONE
            }
            holdCheck(now)
            if (!moving) return NONE
        }
        return armed
    }

    fun up() {
        armed = NONE
        moving = false
    }

    companion object {
        const val NONE = Int.MIN_VALUE
        /** How long to hold the selected marker before it can be dragged. */
        const val HOLD_MS = 350L
        /** How far the finger may wander during the hold. */
        const val SLOP_PX = 28f
    }
}

package com.ozyman.holomemflicker

import kotlin.math.hypot

/**
 * The Park camera stick: a finger that keeps swiping while the stick is held.
 *
 * A swipe can't go on forever (the screen ends), so at [maxDrag] the finger has to lift and touch
 * down again. Up to v18 that happened in the same instant: the game saw the same finger jump back
 * to the anchor inside one frame and swung the camera back with it - the "flicker" in Park.
 *
 * Now the finger lifts, stays up for [gapMs] (a few game frames, so the game clearly sees the
 * swipe end), and comes back down on the far side of the anchor, so the next swipe has room for
 * longer and re-anchors happen about half as often.
 */
class CameraSwipe(
    private val bx: Float, private val by: Float,
    private val screenW: Float, private val screenH: Float,
    private val speed: Float, private val dead: Float,
    /** How long the finger stays up on a re-anchor (see [GAP_MS], [GAP_MINIGAME_MS]). */
    private val gapMs: Long = GAP_MS
) {
    sealed class Op {
        data class Down(val x: Float, val y: Float) : Op()
        data class Move(val x: Float, val y: Float) : Op()
        object Up : Op()
    }

    companion object {
        const val GAP_MS = 50L
        /**
         * Minigames run their camera at a lower frame rate than Park, so a 50 ms lift could fall
         * between two game frames: the game never saw the finger leave and swung the camera back
         * on the re-anchor (the same flicker Park used to have). Stay up longer there.
         */
        const val GAP_MINIGAME_MS = 120L
        /** Longest single swipe, as a share of screen width. */
        const val MAX_DRAG = 0.18f
        /** Re-anchor this far behind the anchor (share of [maxDrag]), opposite to the push. */
        const val BACK = 0.5f
    }

    private val maxDrag = screenW * MAX_DRAG
    var down = false; private set
    var px = bx; private set
    var py = by; private set
    /** Waiting to touch down again after a re-anchor lift (until this time). */
    var rearmAt = 0L; private set

    /** True while there's still something to do (finger down, or a re-anchor pending). */
    fun busy(now: Long) = down || rearmAt > now

    /** One 60 Hz tick with stick position (x, y) in -1..1. */
    fun step(x: Float, y: Float, now: Long): Op? {
        val mag = hypot(x, y)
        if (!down) {
            if (mag < dead) { rearmAt = 0; return null }
            if (now < rearmAt) return null
            val back = if (rearmAt > 0) maxDrag * BACK else 0f
            rearmAt = 0
            px = (bx - x / mag * back).coerceIn(1f, screenW - 1f)
            py = (by - y / mag * back).coerceIn(1f, screenH - 1f)
            down = true
            return Op.Down(px, py)
        }
        if (mag < dead * 0.6f) { down = false; return Op.Up }
        px = (px + x * speed).coerceIn(1f, screenW - 1f)
        py = (py + y * speed).coerceIn(1f, screenH - 1f)
        if (hypot(px - bx, py - by) > maxDrag) {
            // lift; touch down again only after the gap (see class doc)
            down = false
            rearmAt = now + gapMs
            return Op.Up
        }
        return Op.Move(px, py)
    }

    /** Forget everything (mode change, stop). */
    fun reset() { down = false; rearmAt = 0; px = bx; py = by }
}

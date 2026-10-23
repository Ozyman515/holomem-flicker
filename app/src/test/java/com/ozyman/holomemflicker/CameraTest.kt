package com.ozyman.holomemflicker

import com.ozyman.holomemflicker.CameraSwipe.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v19: the Park camera no longer flickers when its swipe re-anchors. */
class CameraTest {

    private fun cam() = CameraSwipe(2000f, 800f, 2560f, 1600f, speed = 30f, dead = 0.35f)

    /** Hold the stick right for [ms] at 60 Hz, starting at [t0]; returns every op with its time. */
    private fun hold(c: CameraSwipe, x: Float, y: Float, t0: Long, ms: Long) =
        (t0 until t0 + ms step 16).mapNotNull { t -> c.step(x, y, t)?.let { t to it } }

    @Test
    fun neverTouchesDownInTheSameInstantItLifted() {
        val c = cam()
        val ops = hold(c, -1f, 0f, 0, 3000)
        val ups = ops.filter { it.second == Op.Up }
        assertTrue("should re-anchor a few times in 3 s", ups.size >= 2)
        for ((tUp, _) in ups) {
            val next = ops.first { it.first > tUp }
            assertTrue("next op after a lift must be a touch-down", next.second is Op.Down)
            assertTrue("finger came back after ${next.first - tUp} ms (game needs a gap)", next.first - tUp >= CameraSwipe.GAP_MS)
        }
    }

    @Test
    fun reAnchorsBehindTheAnchorSoSwipesLastLonger() {
        val c = cam()
        val ops = hold(c, -1f, 0f, 0, 3000)
        val downs = ops.map { it.second }.filterIsInstance<Op.Down>()
        assertEquals("first touch is at the anchor", 2000f, downs[0].x, 0.1f)
        // pushing left: later touches start to the right of the anchor
        assertTrue(downs.drop(1).all { it.x > 2000f })
        // so each swipe after the first is longer than the first one
        val ups = ops.filter { it.second == Op.Up }.map { it.first }
        assertTrue("2nd swipe ${ups[1] - ups[0]} ms vs 1st ${ups[0]} ms", ups[1] - ups[0] > ups[0])
    }

    @Test
    fun releasingTheStickLiftsAndStopsCleanly() {
        val c = cam()
        hold(c, 0f, 1f, 0, 200)
        assertTrue(c.down)
        assertEquals(Op.Up, c.step(0f, 0f, 216))
        assertFalse(c.busy(216))
        assertEquals(null, c.step(0f, 0f, 232))
    }

    @Test
    fun releasingDuringTheGapDoesNotTouchAgain() {
        val c = cam()
        val ops = hold(c, -1f, 0f, 0, 3000)
        val tUp = ops.first { it.second == Op.Up }.first
        val c2 = cam()
        hold(c2, -1f, 0f, 0, tUp + 1)
        assertTrue("waiting to re-anchor", c2.busy(tUp + 1))
        assertEquals(null, c2.step(0f, 0f, tUp + 16))
        assertFalse(c2.busy(tUp + 16))
        assertEquals(null, c2.step(0f, 0f, tUp + 100))
    }
}

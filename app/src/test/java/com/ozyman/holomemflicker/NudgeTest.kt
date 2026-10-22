package com.ozyman.holomemflicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Precision d-pad in the layout editors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NudgeTest {
    private val w = 2560
    private val h = 1600

    @Test
    fun rightMovesATapPointTwoPixelsRight() {
        val d = Prefs.defaults(0)
        val t = d.taps[0]
        val x0 = t.fx * w
        val y0 = t.fy * h
        val msg = NudgePad.apply(d, 0, NudgePad.STEP_PX, 0, w, h)
        assertEquals(x0 + 2f, t.fx * w, 0.01f)
        assertEquals(y0, t.fy * h, 0.01f)
        assertTrue(msg!!.contains("px"))
    }

    @Test
    fun allFourDirectionsAndBackAgain() {
        val d = Prefs.defaults(0)
        val t = d.taps[0]
        val x0 = t.fx; val y0 = t.fy
        NudgePad.apply(d, 0, 0, -2, w, h) // up
        assertEquals(y0 * h - 2f, t.fy * h, 0.01f)
        NudgePad.apply(d, 0, -2, 0, w, h) // left
        assertEquals(x0 * w - 2f, t.fx * w, 0.01f)
        NudgePad.apply(d, 0, 0, 2, w, h) // down
        NudgePad.apply(d, 0, 2, 0, w, h) // right
        assertEquals(x0, t.fx, 1e-5f)
        assertEquals(y0, t.fy, 1e-5f)
    }

    @Test
    fun movesSticksToo() {
        val d = Prefs.defaults(0)
        val lx = d.lfx; val ry = d.rfy
        NudgePad.apply(d, -1, 2, 0, w, h)
        NudgePad.apply(d, -2, 0, 2, w, h)
        assertEquals(lx * w + 2f, d.lfx * w, 0.01f)
        assertEquals(ry * h + 2f, d.rfy * h, 0.01f)
    }

    @Test
    fun staysOnScreenAndNeedsASelection() {
        val d = Prefs.defaults(0)
        d.taps[0].fx = 0f
        NudgePad.apply(d, 0, -2, 0, w, h)
        assertEquals(0f, d.taps[0].fx, 0f)
        assertNull(NudgePad.apply(d, Int.MIN_VALUE, 2, 0, w, h))
    }
}

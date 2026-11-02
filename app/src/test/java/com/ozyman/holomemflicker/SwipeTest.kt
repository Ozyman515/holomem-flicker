package com.ozyman.holomemflicker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Swipe buttons, and the select-then-hold rule in the layout editors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeTest {
    private val w = 2560
    private val h = 1600

    // ---- swipe path ----

    @Test
    fun pathEndsExactlyOnTheEndPointAtTheSwipeTime() {
        val p = Swipe.path(100f, 500f, 100f, 372f, 60)
        val last = p.last()
        assertEquals(60L, last.first)
        assertEquals(100f, last.second, 0.001f)
        assertEquals(372f, last.third, 0.001f)
        // steps are in order and evenly spread, about one every 8 ms
        assertTrue(p.size >= 7)
        for (i in 1 until p.size) assertTrue(p[i].first > p[i - 1].first)
        for (i in 1 until p.size) assertTrue(p[i].third < p[i - 1].third) // moving up the whole way
    }

    @Test
    fun veryFastSwipeStillHasAMiddleStep() {
        val p = Swipe.path(0f, 0f, 10f, 0f, 5)
        assertEquals(2, p.size)
        assertEquals(5f, p[0].second, 0.001f)
    }

    @Test
    fun speedCyclesAndWraps() {
        assertEquals(80, Swipe.nextSpeed(60))
        assertEquals(30, Swipe.nextSpeed(400))
        assertEquals(30, Swipe.nextSpeed(999))
    }

    // ---- saved layouts ----

    @Test
    fun swipeIsSavedLoadedAndSentToTheEngine() {
        val ctx = RuntimeEnvironment.getApplication()
        val l = Prefs.defaults(0)
        val t = l.taps[0]
        EditorTargets.toggleSwipe(t, w, h)
        t.sdx = 0.02f
        t.sms = 45
        Prefs.save(ctx, 0, l)

        val back = Prefs.load(ctx, 0).taps[0]
        assertTrue(Swipe.isSwipe(back))
        assertEquals(0.02f, back.sdx, 1e-6f)
        assertEquals(Swipe.DEFAULT_DY, back.sdy, 1e-6f)
        assertEquals(45, back.sms)
        assertFalse(Swipe.isSwipe(Prefs.load(ctx, 0).taps[1])) // the others stay taps

        val (sw, sh) = Prefs.screenSize(ctx)
        val tap = JSONObject(Prefs.buildConfig(ctx)).getJSONArray("modes").getJSONObject(0).getJSONArray("taps").getJSONObject(0)
        assertEquals(0.02 * sw, tap.getDouble("sx"), 0.01)
        assertEquals(Swipe.DEFAULT_DY.toDouble() * sh, tap.getDouble("sy"), 0.01)
        assertEquals(45, tap.getInt("ms"))
        val plain = JSONObject(Prefs.buildConfig(ctx)).getJSONArray("modes").getJSONObject(0).getJSONArray("taps").getJSONObject(1)
        assertFalse(plain.has("sx"))
    }

    @Test
    fun holdAtTheEndIsSavedAndSentToTheEngine() {
        val ctx = RuntimeEnvironment.getApplication()
        val l = Prefs.defaults(0)
        val t = l.taps[0]
        EditorTargets.toggleSwipe(t, w, h)
        assertFalse(t.shold) // new swipes lift at the end (one swipe per press)
        assertTrue(EditorTargets.toggleHold(t, w, h)!!.contains("holds at the end"))
        assertTrue(t.shold)
        Prefs.save(ctx, 0, l)
        assertTrue(Prefs.load(ctx, 0).taps[0].shold)
        assertFalse(Prefs.load(ctx, 0).taps[1].shold)
        val tap = JSONObject(Prefs.buildConfig(ctx)).getJSONArray("modes").getJSONObject(0).getJSONArray("taps").getJSONObject(0)
        assertTrue(tap.getBoolean("hold"))
        assertTrue(l.copy().taps[0].shold)
        assertTrue(EditorTargets.toggleHold(t, w, h)!!.contains("then lifts"))
        assertFalse(t.shold)
    }

    @Test
    fun endButtonNeedsASwipePoint() {
        val t = Prefs.defaults(0).taps[0]
        assertEquals(null, EditorTargets.toggleHold(t, w, h))
        assertFalse(t.shold)
    }

    @Test
    fun copyKeepsTheSwipe() {
        val l = Prefs.defaults(0)
        EditorTargets.toggleSwipe(l.taps[0], w, h)
        assertTrue(Swipe.isSwipe(l.copy().taps[0]))
    }

    @Test
    fun swipeButtonTogglesBackToATap() {
        val t = Prefs.defaults(0).taps[0]
        EditorTargets.toggleSwipe(t, w, h)
        assertTrue(Swipe.isSwipe(t))
        EditorTargets.toggleSwipe(t, w, h)
        assertFalse(Swipe.isSwipe(t))
    }

    @Test
    fun describeReadsTheDirection() {
        val t = Prefs.TapPoint(0x136, 0.5f, 0.5f, sdx = 0f, sdy = -0.08f, sms = 60)
        assertTrue(Swipe.describe(t, w, h).startsWith("swipe up, 128 px in 60 ms"))
        t.sdx = 0.05f; t.sdy = 0f
        assertTrue(Swipe.describe(t, w, h).startsWith("swipe right"))
    }

    // ---- aiming handle ----

    @Test
    fun handleOfTheSelectedSwipeIsGrabbedAndReAims() {
        val l = Prefs.defaults(0)
        val t = l.taps[0]
        EditorTargets.toggleSwipe(t, w, h)
        val hx = (t.fx + t.sdx) * w
        val hy = (t.fy + t.sdy) * h
        val hit = EditorTargets.hit(l, 0, hx, hy, w, h)
        assertTrue(EditorTargets.isHandle(hit))
        assertEquals(0, EditorTargets.owner(hit))
        val fx0 = t.fx
        EditorTargets.moveTo(l, hit, t.fx + 0.03f, t.fy)
        assertEquals(fx0, t.fx, 0f) // the point stays put
        assertEquals(0.03f, t.sdx, 1e-5f)
        assertEquals(0f, t.sdy, 1e-5f)
        // not selected: the handle isn't offered, the point itself is
        assertEquals(0, EditorTargets.hit(l, Int.MIN_VALUE, t.fx * w, t.fy * h, w, h))
    }

    @Test
    fun handleNeverTurnsTheSwipeBackIntoATap() {
        val l = Prefs.defaults(0)
        val t = l.taps[0]
        EditorTargets.toggleSwipe(t, w, h)
        EditorTargets.moveTo(l, EditorTargets.handleOf(0), t.fx, t.fy)
        assertTrue(Swipe.isSwipe(t))
    }

    // ---- select, then hold to move ----

    @Test
    fun firstTouchOnlySelects() {
        val g = HoldToMove()
        g.down(3, false, 100f, 100f, 0)
        assertEquals(HoldToMove.NONE, g.move(400f, 400f, 1000)) // slid far, long after: still nothing moves
        assertFalse(g.moving)
    }

    @Test
    fun selectedMarkerMovesOnlyAfterTheHold() {
        val g = HoldToMove()
        g.down(3, true, 100f, 100f, 0)
        assertEquals(HoldToMove.NONE, g.move(105f, 102f, 100)) // too soon
        assertTrue(g.holdCheck(HoldToMove.HOLD_MS))            // timer fires
        assertEquals(3, g.move(300f, 250f, HoldToMove.HOLD_MS + 20))
        g.up()
        assertEquals(HoldToMove.NONE, g.move(310f, 250f, HoldToMove.HOLD_MS + 40))
    }

    @Test
    fun slidingBeforeTheHoldCancelsThePickUp() {
        val g = HoldToMove()
        g.down(-1, true, 100f, 100f, 0)
        assertEquals(HoldToMove.NONE, g.move(100f + HoldToMove.SLOP_PX + 5f, 100f, 50))
        assertFalse(g.holdCheck(HoldToMove.HOLD_MS + 1)) // the timer firing later doesn't pick it up
        assertEquals(HoldToMove.NONE, g.move(300f, 300f, HoldToMove.HOLD_MS + 50))
    }

    @Test
    fun emptySpaceNeverMovesAnything() {
        val g = HoldToMove()
        g.down(HoldToMove.NONE, true, 0f, 0f, 0)
        assertFalse(g.holdCheck(10_000))
        assertEquals(HoldToMove.NONE, g.move(5f, 5f, 10_000))
    }
}

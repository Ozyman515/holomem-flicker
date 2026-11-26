package com.ozyman.holomemflicker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The in-game coach's controller loop, plus Select+Start and the d-pad in the engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoachTest {
    private fun coach() = Coach(runAsync = { it() }, post = { it() }, changed = {}, thinkMs = 200)
    private val A = Coach.KEY_A
    private val B = Coach.KEY_B

    @Test
    fun loopPlaysBestThenTakesTheirPickedReply() {
        val c = coach()
        assertEquals(Coach.Phase.START, c.phase)
        c.onKey(A) // I go first
        assertEquals(Coach.Phase.MY_TURN, c.phase)
        val best = c.best!!.move
        assertEquals(Coach.Role.BEST, c.highlight!!.second)
        assertEquals("Play best move?", c.prompt)
        c.onKey(A) // yes
        assertEquals(Coach.Phase.THEIR_TURN, c.phase)
        assertEquals(best, c.last)
        assertTrue(c.replies.isNotEmpty() && c.replies.size <= 4)
        assertEquals(c.replies[0], c.highlight!!.first)
        c.onKey(Coach.KEY_DOWN)
        assertEquals(c.replies[1], c.highlight!!.first) // the highlight follows the list
        assertEquals(Coach.Role.THEIRS, c.highlight!!.second)
        val pick = c.replies[1]
        c.onKey(A)
        assertEquals(pick, c.last)
        assertEquals(Coach.Phase.MY_TURN, c.phase)
    }

    @Test
    fun theyGoFirstAndBOpensOther() {
        val c = coach()
        c.onKey(Coach.KEY_DOWN); c.onKey(A)
        assertEquals(Coach.Phase.THEIR_TURN, c.phase)
        assertEquals(1, c.state.turn)
        c.onKey(B)
        assertEquals(Coach.Phase.OTHER, c.phase)
        assertEquals("Enter their move by hand", c.items[0].label)
        c.onKey(B)
        assertEquals(Coach.Phase.THEIR_TURN, c.phase)
    }

    @Test
    fun handEntryWithTheDpad() {
        val c = coach()
        c.onKey(A) // I go first
        c.onKey(B) // Other…
        c.onKey(A) // I played a different move
        assertEquals(Coach.Phase.MANUAL, c.phase)
        // cursor starts on your Leader (column 1 middle); move to the Turtle (column 2 middle)
        assertEquals(0, c.cx); assertEquals(1, c.cy)
        c.onKey(Coach.KEY_RIGHT)
        c.onKey(A)
        assertNotNull(c.picked)
        assertEquals(listOf(Puka.index(2, 1)), c.targets) // Turtle only goes forward, onto their Turtle
        c.onKey(Coach.KEY_RIGHT); c.onKey(A)
        assertEquals(Coach.Phase.THEIR_TURN, c.phase)
        assertEquals(1, c.state.hand(0, Puka.TURTLE)) // their Turtle went to your bench
    }

    @Test
    fun capturesGoToTheBenchBothWays() {
        val c = coach()
        c.newMatch(0)
        val take = Puka.moves(c.state).first { it.from == Puka.index(1, 1) } // your Turtle takes theirs
        c.play(take)
        assertEquals(1, c.state.hand(0, Puka.TURTLE))
        val back = Puka.moves(c.state).first { it.to == Puka.index(2, 1) && it.from >= 0 } // they take it back
        c.play(back)
        assertEquals(1, c.state.hand(1, Puka.TURTLE))
    }

    @Test
    fun winCanBeTakenBack() {
        val c = coach()
        val s = Puka.empty(0)
        s.b[Puka.index(0, 1)] = Puka.LEADER
        s.b[Puka.index(3, 1)] = Puka.LEADER or 8
        s.b[Puka.index(2, 0)] = Puka.WHALE
        c.newMatch(0)
        c.setPosition(s)
        val before = c.state.copy()
        c.onKey(A) // play best: takes their Leader
        assertEquals(Coach.Phase.OVER, c.phase)
        assertEquals("You win!", c.prompt)
        assertEquals("Undo the last move", c.items[0].label)
        c.onKey(A)
        assertEquals(before, c.state)
        assertEquals(Coach.Phase.MY_TURN, c.phase)
        // and the fixed board itself can be undone back to the start position
        c.onKey(B); c.onKey(Coach.KEY_DOWN); c.onKey(A) // Other… → Undo
        assertEquals(Puka.start(), c.state)
    }

    @Test
    fun drawIsReversibleToo() {
        val c = coach()
        c.newMatch(0)
        c.onKey(B) // Other…
        val i = c.items.indexOfFirst { it.label == "Match ended in a draw" }
        repeat(i) { c.onKey(Coach.KEY_DOWN) }
        c.onKey(A)
        assertEquals(Coach.Phase.OVER, c.phase)
        assertEquals("Draw", c.prompt)
        c.onKey(A) // Take back the draw (no moves yet)
        assertEquals(Coach.Phase.MY_TURN, c.phase)
        assertFalse(c.drawn)
    }

    @Test
    fun settingsSizeAndSeeThrough() {
        var saved = 0
        val c = coach().also { it.onSettings = { saved++ } }
        repeat(3) { c.onKey(Coach.KEY_DOWN) } // START: Panel settings
        c.onKey(A)
        assertEquals(Coach.Phase.SETTINGS, c.phase)
        c.onKey(Coach.KEY_RIGHT) // size +10
        assertEquals(110, c.settings.scale)
        c.onKey(Coach.KEY_DOWN); c.onKey(Coach.KEY_RIGHT); c.onKey(Coach.KEY_RIGHT) // more see-through
        assertEquals(80, c.settings.opacity)
        repeat(10) { c.onKey(Coach.KEY_RIGHT) }
        assertEquals(Coach.MIN_OPACITY, c.settings.opacity)
        c.onKey(Coach.KEY_DOWN); c.onKey(A) // Move the panel
        assertEquals(Coach.Phase.MOVE_PANEL, c.phase)
        val x = c.settings.x
        c.onKey(Coach.KEY_RIGHT)
        assertTrue(c.settings.x > x)
        c.onKey(A)
        assertEquals(Coach.Phase.SETTINGS, c.phase)
        assertTrue(saved >= 5)
        val again = Coach.Settings.from(c.settings.toJson())
        assertEquals(c.settings.scale, again.scale)
        assertEquals(c.settings.opacity, again.opacity)
    }

    @Test
    fun savesAndResumesTheMatch() {
        val c = coach()
        c.onKey(A); c.onKey(A) // I go first, play best
        val j = c.toJson()
        val d = coach()
        d.load(JSONObject(j.toString()))
        assertEquals(c.state, d.state)
        assertEquals(Coach.Phase.THEIR_TURN, d.phase)
        assertEquals(c.last, d.last)
        d.undo()
        assertEquals(Puka.start(), d.state)
    }

    // ---- engine side: Select+Start and the d-pad ----

    private fun engine(): Engine {
        val modes = JSONArray()
        for (n in listOf("Live", "Park", "Minigame")) modes.put(JSONObject().put("name", n))
        return Engine(JSONObject().put("modes", modes).put("idleMs", 60_000))
    }

    private val sel = 0x13a
    private val start = 0x13b
    private fun Engine.press(code: Int) { feedKeyForTest(code, 1); feedKeyForTest(code, 0) }
    private fun Engine.combo(code: Int) { feedKeyForTest(sel, 1); press(code); feedKeyForTest(sel, 0) }
    private fun Engine.st() = JSONObject(stateJson())

    @Test
    fun selectStartOpensTheCoachAndButtonsGoToIt() {
        val e = engine()
        e.combo(start)
        assertTrue(e.st().getBoolean("coach"))
        assertFalse(e.st().getBoolean("editing"))
        e.press(A)
        assertEquals(A, e.st().getString("coachKey").substringAfter(':').toInt())
        val k1 = e.st().getString("coachKey")
        e.feedHatForTest(Engine.HAT_Y, 1); e.feedHatForTest(Engine.HAT_Y, 0)
        assertEquals(Coach.KEY_DOWN, e.st().getString("coachKey").substringAfter(':').toInt())
        assertTrue(k1 != e.st().getString("coachKey"))
        e.feedHatForTest(Engine.HAT_X, -1)
        assertEquals(Coach.KEY_LEFT, e.st().getString("coachKey").substringAfter(':').toInt())
        e.feedHatForTest(Engine.HAT_X, 0)
        // the same button twice still counts twice
        e.press(A); val a1 = e.st().getString("coachKey"); e.press(A)
        assertTrue(a1 != e.st().getString("coachKey"))
        e.combo(start)
        assertFalse(e.st().getBoolean("coach"))
        e.shutdown()
    }

    @Test
    fun selectStartStillClosesTheEditor() {
        val e = engine()
        e.setEditing(true)
        e.combo(start)
        assertFalse(e.st().getBoolean("editing"))
        assertFalse(e.st().getBoolean("coach"))
        e.shutdown()
    }

    @Test
    fun quickMenuCanOpenTheCoach() {
        val e = engine()
        e.combo(0x134) // Select+Y
        repeat(6) { e.press(QuickMenu.KEY_DPAD_DOWN) } // Live Park Minigame Overlay Look Edit → Puka Coach
        e.press(A)
        assertTrue(e.st().getBoolean("coach"))
        assertFalse(e.st().getBoolean("menu"))
        e.shutdown()
    }

    // ---- 4-tall boards (some Challenge stages) ----

    private fun four(turn: Int): Puka.State {
        val s = Puka.empty(turn, 4)
        s.b[s.idx(0, 1)] = Puka.LEADER
        s.b[s.idx(3, 2)] = Puka.LEADER or 8
        s.b[s.idx(1, 3)] = Puka.TURTLE
        s.b[s.idx(2, 0)] = Puka.WHALE or 8
        return s
    }

    @Test
    fun fourTallBoardRulesAndCoach() {
        val s = four(0)
        assertEquals(16, s.n); assertEquals(4, s.rows)
        // Leader in the middle of column 1 (screen row 1 of 4): 5 steps on the board edge column
        val lp = s.idx(0, 1)
        assertEquals(5, Puka.moves(s).count { it.from == lp })
        // the Turtle on the bottom screen row (row 4) steps forward along that row
        val tp = s.idx(1, 3)
        assertEquals(listOf(s.idx(2, 3)), Puka.moves(s).filter { it.from == tp }.map { it.to })
        // a Turtle reaching their back column turns gold
        val t2 = Puka.empty(0, 4).also { it.b[it.idx(0, 0)] = Puka.LEADER; it.b[it.idx(3, 3)] = Puka.LEADER or 8; it.b[it.idx(2, 1)] = Puka.TURTLE }
        val g = Puka.apply(t2, Puka.Move(t2.idx(2, 1), t2.idx(3, 1), 0))
        assertEquals(Puka.GOLD, g.b[g.idx(3, 1)])
        // a Try on the 4-tall board
        val tr = Puka.empty(0, 4).also { it.b[it.idx(2, 0)] = Puka.LEADER; it.b[it.idx(1, 3)] = Puka.LEADER or 8 }
        val after = Puka.apply(tr, Puka.Move(tr.idx(2, 0), tr.idx(3, 0), 0))
        assertEquals(0, Puka.terminal(after)) // their turn: they can't reach it
        assertNotNull(Puka.search(s, 300))
        // the coach plays it and the hand-entry cursor wraps over 4 rows
        val c = coach()
        c.startFrom(s)
        assertEquals(Coach.Phase.MY_TURN, c.phase)
        assertNotNull(c.best)
        c.onKey(B); c.onKey(A)
        assertEquals(Coach.Phase.MANUAL, c.phase)
        repeat(3) { c.onKey(Coach.KEY_DOWN) }
        assertEquals(0, c.cy) // 1 → 2 → 3 → 0
        val d = coach(); d.load(JSONObject(c.toJson().toString()))
        assertEquals(4, d.state.w)
        assertEquals(c.state, d.state)
    }
}

package com.ozyman.holomemflicker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Select+Y quick menu: open/close, d-pad (hat and buttons), choosing each kind of row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuTest {
    private val sel = 0x13a
    private val y = 0x134
    private val a = 0x130
    private val b = 0x131

    private fun engine(): Engine {
        val modes = JSONArray()
        for (n in listOf("Live", "Park", "Minigame")) modes.put(JSONObject().put("name", n))
        return Engine(JSONObject().put("modes", modes).put("idleMs", 60_000))
    }

    private fun Engine.press(code: Int) { feedKeyForTest(code, 1); feedKeyForTest(code, 0) }
    private fun Engine.combo(code: Int) { feedKeyForTest(sel, 1); press(code); feedKeyForTest(sel, 0) }
    private fun Engine.st() = JSONObject(stateJson())

    @Test
    fun selectYOpensOnTheCurrentModeAndClosesAgain() {
        val e = engine()
        e.setMode(1)
        e.combo(y)
        assertTrue(e.st().getBoolean("menu"))
        assertEquals(1, e.st().getInt("menuIdx"))
        assertEquals(3, e.st().getJSONArray("modeNames").length())
        e.combo(y)
        assertFalse(e.st().getBoolean("menu"))
        e.shutdown()
    }

    @Test
    fun dpadMovesAndWrapsHatAndButtons() {
        val e = engine()
        e.combo(y)
        e.feedHatForTest(Engine.HAT_Y, -1); e.feedHatForTest(Engine.HAT_Y, 0) // up from Live wraps to Close
        assertEquals(QuickMenu.count(3) - 1, e.st().getInt("menuIdx"))
        e.press(QuickMenu.KEY_DPAD_DOWN)
        assertEquals(0, e.st().getInt("menuIdx"))
        e.feedHatForTest(Engine.HAT_Y, 1)
        assertEquals(1, e.st().getInt("menuIdx"))
        e.shutdown()
    }

    @Test
    fun choosingAModeSwitchesAndCloses() {
        val e = engine()
        e.combo(y)
        e.feedHatForTest(Engine.HAT_Y, 1); e.feedHatForTest(Engine.HAT_Y, 1) // Minigame
        e.press(a)
        assertEquals(2, e.st().getInt("mode"))
        assertFalse(e.st().getBoolean("menu"))
        e.shutdown()
    }

    @Test
    fun overlayAndLookStayOpen() {
        val e = engine()
        e.combo(y)
        repeat(3) { e.press(QuickMenu.KEY_DPAD_DOWN) } // Overlay
        e.press(a)
        assertTrue(e.st().getBoolean("overlay"))
        assertTrue(e.st().getBoolean("menu"))
        e.press(QuickMenu.KEY_DPAD_DOWN) // Look
        e.press(a); e.press(a)
        assertEquals(2, e.st().getInt("lookSeq"))
        assertTrue(e.st().getBoolean("menu"))
        e.shutdown()
    }

    @Test
    fun editOpensTheEditorAndBCloses() {
        val e = engine()
        e.combo(y)
        repeat(5) { e.press(QuickMenu.KEY_DPAD_DOWN) } // Edit layout
        e.press(a)
        assertTrue(e.st().getBoolean("editing"))
        assertFalse(e.st().getBoolean("menu"))
        e.setEditing(false)
        e.combo(y)
        e.press(b)
        assertFalse(e.st().getBoolean("menu"))
        e.shutdown()
    }

    @Test
    fun rowsListModesThenExtras() {
        val rows = QuickMenu.rows(listOf("Live", "Park"), 1, false, "Neon")
        assertEquals(listOf("Live", "Park", "Overlay", "Look", "Edit layout", "Close"), rows.map { it.label })
        assertTrue(rows[1].current)
        assertEquals("off", rows[2].hint)
        assertTrue(rows[2].gapBefore)
        assertEquals("Neon", rows[3].hint)
    }
}

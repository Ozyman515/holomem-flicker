package com.ozyman.holomemflicker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v14 battery work: long-poll wakeups, idle sleep, wake on input, delta touch log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryTest {

    private fun engine(idleMs: Long = 60_000) = Engine(JSONObject().put("idleMs", idleMs))

    @Test
    fun waitReturnsOnTimeoutWhenNothingHappens() {
        val e = engine()
        val start = System.nanoTime()
        val seq = e.waitChange(JSONObject(e.stateJson()).getLong("seq"), 300)
        val took = (System.nanoTime() - start) / 1_000_000
        assertEquals(0L, seq)
        assertTrue("waited $took ms", took in 250..2000)
        e.shutdown()
    }

    @Test
    fun waitWakesImmediatelyOnAButton() {
        val e = engine()
        Thread { Thread.sleep(150); e.simulateKey(0x130, true) }.start()
        val start = System.nanoTime()
        val seq = e.waitChange(0, 10_000)
        val took = (System.nanoTime() - start) / 1_000_000
        assertTrue(seq > 0)
        assertTrue("woke after $took ms", took < 2000)
        assertTrue(JSONObject(e.stateJson()).getJSONArray("keys").toString().contains("304"))
        e.shutdown()
    }

    @Test
    fun overlayToggleAndModeChangeWakeTheOverlay() {
        val e = engine()
        val s0 = JSONObject(e.stateJson()).getLong("seq")
        e.setOverlay(true)
        val s1 = e.waitChange(s0, 1000)
        assertTrue(s1 > s0)
        assertTrue(JSONObject(e.stateJson()).getBoolean("overlay"))
        e.shutdown()
    }

    @Test
    fun sleepsAfterIdleAndWakesOnAnyButton() {
        val e = engine(idleMs = 300)
        e.startIdleTimer()
        assertFalse(JSONObject(e.stateJson()).getBoolean("sleeping"))
        Thread.sleep(700)
        val st = JSONObject(e.stateJson())
        assertTrue("should be asleep after 300 ms idle", st.getBoolean("sleeping"))

        // a press wakes it straight away
        val seq = st.getLong("seq")
        Thread { Thread.sleep(100); e.simulateKey(0x131, true) }.start()
        e.waitChange(seq, 5000)
        assertFalse(JSONObject(e.stateJson()).getBoolean("sleeping"))

        // and the countdown restarts: still awake shortly after, asleep again later
        e.simulateKey(0x131, false)
        Thread.sleep(100)
        assertFalse(JSONObject(e.stateJson()).getBoolean("sleeping"))
        Thread.sleep(800)
        assertTrue(JSONObject(e.stateJson()).getBoolean("sleeping"))
        e.shutdown()
    }

    @Test
    fun activityKeepsItAwake() {
        val e = engine(idleMs = 400)
        e.startIdleTimer()
        repeat(6) {
            Thread.sleep(150)
            e.simulateKey(0x130, true); e.simulateKey(0x130, false)
        }
        assertFalse("pressed every 150 ms - must stay awake", JSONObject(e.stateJson()).getBoolean("sleeping"))
        e.shutdown()
    }

    @Test
    fun trailsAskOnlyForNewTouches() {
        val t = TouchTrails()
        assertEquals(-1, t.since()) // first read: whole log
        val log = org.json.JSONArray()
            .put(org.json.JSONArray().put(1).put(1).put(10.0).put(10.0).put(0))
            .put(org.json.JSONArray().put(2).put(1).put(20.0).put(10.0).put(1))
        t.read(log, 1000)
        assertEquals(2, t.since())
        t.read(org.json.JSONArray(), 1016) // nothing new: keeps its place
        assertEquals(2, t.since())
        t.read(null, 1032) // overlay off: next read starts over
        assertEquals(-1, t.since())
    }
}

package com.ozyman.holomemflicker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Follows the four recorded matches from their screen readings (2 per second, read by the
 * prototype) and checks the moves found are the ones the prototype tracker found, including
 * the Undo in the Luna match. Ties are answered the way the user would (the real move).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PukaTrackerTest {
    private fun sq(s: Puka.State, i: Int) = Puka.short(s, i)
    private fun text(s: Puka.State, m: Puka.Move) =
        if (m.drop != 0) "drop " + mapOf(Puka.WHALE to "W", Puka.DOLPHIN to "D", Puka.TURTLE to "T")[m.drop] + " " + sq(s, m.to) else sq(s, m.from) + ">" + sq(s, m.to)

    @Test
    fun followsTheRecordedMatches() {
        val all = JSONObject(javaClass.classLoader!!.getResourceAsStream("puka-tracks.json").bufferedReader().readText())
        for (v in all.keys()) {
            val o = all.getJSONObject(v)
            val w = o.getInt("w")
            var state = Puka.empty(o.getInt("first"), w)
            val rows = if (w == 4) "TULB" else "TMB"
            val cells = o.getJSONArray("cells")
            for (k in 0 until cells.length()) {
                val c = cells.getJSONArray(k)
                val i = state.idx(c.getString(0).toInt() - 1, rows.indexOf(c.getString(1)))
                val t = when (c.getString(3)) { "L" -> Puka.LEADER; "W" -> Puka.WHALE; "D" -> Puka.DOLPHIN; "T" -> Puka.TURTLE; else -> Puka.GOLD }
                state.b[i] = t or (if (c.getString(2) == "O") 8 else 0)
            }
            val bench = o.getJSONArray("bench")
            for (side in 0..1) { val b = bench.getJSONArray(side); for (k in 0 until b.length()) { val t = when (b.getString(k)) { "W" -> Puka.WHALE; "D" -> Puka.DOLPHIN; else -> Puka.TURTLE }; state.h[side * 5 + t]++ } }
            val expected = o.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it != "?" }
            val got = ArrayList<String>()
            val history = ArrayList<Puka.State>()
            val tr = PukaTracker()
            val frames = o.getJSONArray("frames")
            for (k in 0 until frames.length()) {
                val f = frames.getJSONObject(k)
                val g = f.getJSONArray("g")
                val grid = (0 until g.length()).map { y -> val row = g.getJSONArray(y); (0 until row.length()).map { row.getString(it) } }
                val r = PukaReader.Reading(f.getInt("n"), f.getDouble("sc"), grid, listOf(emptyList(), emptyList()), if (f.isNull("u")) null else f.getInt("u"), null)
                var res = tr.update(r, state, history)
                var guard = 0
                while (res !is PukaTracker.Result.Nothing && guard++ < 4) {
                    when (res) {
                        is PukaTracker.Result.Moves -> for (m in res.moves) { got.add(text(state, m)); history.add(state); state = Puka.apply(state, m) }
                        is PukaTracker.Result.Undo -> { got.add("UNDO"); repeat(res.steps) { state = history.removeAt(history.size - 1) } }
                        is PukaTracker.Result.Ambiguous -> {
                            val want = expected.getOrNull(got.size)
                            val m = res.options.firstOrNull { text(state, it) == want } ?: res.options[0]
                            got.add(text(state, m)); history.add(state); state = Puka.apply(state, m)
                        }
                        else -> got.add("LOST@" + f.getString("f"))
                    }
                    if (res is PukaTracker.Result.Lost) break
                    res = tr.update(r, state, history) // a two-move gap: the second move is found on the same board
                }
            }
            println("$v: $got")
            assertEquals("match $v", expected, got)
        }
    }
}

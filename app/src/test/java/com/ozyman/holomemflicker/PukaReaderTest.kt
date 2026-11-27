package com.ozyman.holomemflicker

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import android.graphics.BitmapFactory
import org.robolectric.annotation.GraphicsMode

/**
 * The screen reader against frames from the user's match videos (3-tall, 4-tall, rotated view,
 * after an Undo), compared with the prototype's readings (expected.json). Also prints timings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PukaReaderTest {
    private fun pixels(name: String): Pair<IntArray, Int> {
        val b = BitmapFactory.decodeStream(res(name))
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px to b.width
    }

    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!

    @Test
    fun matchesThePrototype() {
        val digits = PukaReader.loadDigits(File("src/main/assets/puka-undo-digits.json").readText())
        val reader = PukaReader(digits)
        val exp = JSONArray(res("puka-frames/expected.json").bufferedReader().readText())
        val log = StringBuilder()
        for (n in 0 until exp.length()) {
            val e = exp.getJSONObject(n)
            val (px, iw) = pixels("puka-frames/" + e.getString("file"))
            val t0 = System.nanoTime()
            val r = reader.read(px, iw)
            val ms = (System.nanoTime() - t0) / 1e6
            val name = e.getString("file")
            log.append("$name ${r.tall}-tall score %.2f undo ${r.undos} myTurn ${r.myTurn} %.0f ms\n".format(r.score, ms))
            assertEquals("tall $name", e.getInt("n"), r.tall)
            assertEquals("score $name", e.getDouble("score"), r.score, 0.04)
            val g = e.getJSONArray("grid")
            for (y in 0 until g.length()) {
                val row = g.getJSONArray(y)
                for (x in 0 until row.length()) {
                    val want = row.getString(x).let { if (it.startsWith(".")) "" else it }
                    assertEquals("tile $name row $y col $x", want, r.grid[y][x])
                }
            }
            val b = e.getJSONArray("bench")
            for (s in 0..1) assertEquals("bench $name side $s", b.getJSONArray(s).let { a -> (0 until a.length()).map { a.getString(it)[0] } }, r.benches[s])
            if (!e.isNull("undo")) assertEquals("undos $name", e.getInt("undo"), r.undos)
        }
        println(log)
        File(System.getProperty("screens.dir") ?: "screens").apply { mkdirs() }.resolve("reader-timings.txt").writeText(log.toString())
    }

    @Test
    fun rotatedViewIsRejected() {
        val (px, iw) = pixels("puka-frames/1453-0060.jpg")
        val r = PukaReader().read(px, iw)
        assertTrue(!r.normalView)
    }
}

package com.ozyman.holomemflicker

import java.io.ByteArrayOutputStream

/**
 * Screen capture for the Puka reader, run in the shell helper: `screencap` raw output
 * (header: width, height, format[, colour space], then RGBA bytes), scaled to 1280 wide with
 * 2x2 averaging. A portrait buffer is turned to landscape.
 */
object ScreenGrab {
    class Shot(val px: IntArray, val w: Int, val srcW: Int, val srcH: Int, val capMs: Long, val convMs: Long)

    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** Raw screencap bytes and how long the grab took. */
    fun capture(): Pair<ByteArray, Long> {
        val t0 = System.nanoTime()
        val p = ProcessBuilder("screencap").redirectErrorStream(false).start()
        val buf = ByteArrayOutputStream(20 shl 20)
        p.inputStream.use { it.copyTo(buf, 1 shl 20) }
        p.waitFor()
        return buf.toByteArray() to (System.nanoTime() - t0) / 1_000_000
    }

    fun grab(): Shot { val (raw, ms) = capture(); return convert(raw, ms, false) }

    /** [otherWay] = turn a portrait buffer the other way round. */
    fun convert(raw: ByteArray, capMs: Long, otherWay: Boolean): Shot {
        val t1 = System.nanoTime()
        val sw = le(raw, 0); val sh = le(raw, 4)
        require(sw in 1..10000 && sh in 1..10000) { "bad screencap header" }
        val head = raw.size - sw * sh * 4
        require(head in 12..16) { "unexpected screencap size ${raw.size} for ${sw}x$sh" }
        val land = sw >= sh
        val lw = if (land) sw else sh; val lh = if (land) sh else sw
        val ow = PukaReader.W
        val oh = Math.round(lh * ow / lw.toFloat())
        val sc = lw / ow.toFloat()
        val px = IntArray(ow * oh)
        fun at(x: Int, y: Int): Int { // landscape coordinates -> buffer offset
            val bx: Int; val by: Int
            if (land) { bx = x; by = y } else if (!otherWay) { bx = sw - 1 - y; by = x } else { bx = y; by = sh - 1 - x } // rotate 90° (portrait buffer)
            return head + (by * sw + bx) * 4
        }
        for (y in 0 until oh) {
            val sy = minOf((y * sc).toInt(), lh - 2)
            for (x in 0 until ow) {
                val sx = minOf((x * sc).toInt(), lw - 2)
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val o = at(sx + dx, sy + dy)
                    r += raw[o].toInt() and 255; g += raw[o + 1].toInt() and 255; b += raw[o + 2].toInt() and 255
                }
                px[y * ow + x] = ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
        }
        val t2 = System.nanoTime()
        return Shot(px, ow, sw, sh, capMs, (t2 - t1) / 1_000_000)
    }
}

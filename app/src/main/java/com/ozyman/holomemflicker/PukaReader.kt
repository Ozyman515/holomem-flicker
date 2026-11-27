package com.ozyman.holomemflicker

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Puka Puka Fight screen (normal camera view) into a board: whose piece is on each tile
 * (blue / orange corner markings) and which piece it is (hat colour), both benches, the Undo
 * counter and whose turn it is (the Undo button is white on your turn, grey on theirs).
 *
 * Works on a frame scaled to 1280 pixels wide (the tablet's 2560x1600 halved). A port of the
 * prototype tested on the match videos; PukaReaderTest checks it gives the same answers.
 * No Android classes, so it runs in the shell helper and in plain JVM tests.
 */
class PukaReader(private val digits: Map<Int, FloatArray> = emptyMap()) {
    companion object {
        const val W = 1280
        private class Row(val y0: Int, val y1: Int, val cols: Array<IntArray>)
        private val LAYOUT = mapOf(
            3 to arrayOf(
                Row(240, 330, arrayOf(intArrayOf(255, 405), intArrayOf(460, 620), intArrayOf(670, 830), intArrayOf(880, 1040))),
                Row(340, 440, arrayOf(intArrayOf(200, 380), intArrayOf(430, 610), intArrayOf(675, 855), intArrayOf(890, 1080))),
                Row(465, 595, arrayOf(intArrayOf(160, 360), intArrayOf(410, 605), intArrayOf(675, 870), intArrayOf(930, 1130))),
            ),
            4 to arrayOf(
                Row(245, 300, arrayOf(intArrayOf(275, 425), intArrayOf(470, 610), intArrayOf(670, 810), intArrayOf(860, 1000))),
                Row(305, 385, arrayOf(intArrayOf(225, 400), intArrayOf(450, 610), intArrayOf(670, 830), intArrayOf(880, 1050))),
                Row(395, 490, arrayOf(intArrayOf(170, 375), intArrayOf(420, 605), intArrayOf(670, 850), intArrayOf(900, 1100))),
                Row(500, 610, arrayOf(intArrayOf(100, 335), intArrayOf(395, 600), intArrayOf(675, 885), intArrayOf(930, 1170))),
            ),
        )
        private val LABELS = arrayOf(intArrayOf(0, 575, 220, 620), intArrayOf(1060, 575, 1280, 620))
        private val UNDO_BOX = intArrayOf(1108, 44, 1134, 72)
        /** Below this the camera isn't in the normal view (rotated, zoomed, a cut-in on screen). */
        const val MIN_SCORE = 0.15

        fun loadDigits(json: String): Map<Int, FloatArray> {
            val o = JSONObject(json)
            return o.keys().asSequence().associate { k -> val a = o.getJSONArray(k); k.toInt() to FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        }
    }

    /** One reading. grid[row][col]: "" empty, else owner B/O + type L/W/D/T/G or ? (screen layout: row 0 = top, col 0 = your side). */
    class Reading(val tall: Int, val score: Double, val grid: List<List<String>>, val benches: List<List<Char>>, val undos: Int?, val myTurn: Boolean?) {
        val normalView get() = score >= MIN_SCORE
        fun toJson(): JSONObject = JSONObject().put("tall", tall).put("score", score).put("normal", normalView)
            .put("grid", JSONArray(grid.map { JSONArray(it) }))
            .put("bench", JSONArray(benches.map { b -> JSONArray(b.map { it.toString() }) }))
            .put("undos", undos ?: -1).put("myTurn", myTurn?.let { if (it) 1 else 0 } ?: -1)

        companion object {
            fun fromJson(o: JSONObject): Reading {
                val g = o.getJSONArray("grid"); val b = o.getJSONArray("bench")
                val mt = o.optInt("myTurn", -1); val u = o.optInt("undos", -1)
                return Reading(o.getInt("tall"), o.getDouble("score"),
                    (0 until g.length()).map { y -> val row = g.getJSONArray(y); (0 until row.length()).map { row.getString(it) } },
                    (0..1).map { s -> val a = b.getJSONArray(s); (0 until a.length()).map { a.getString(it)[0] } },
                    if (u < 0) null else u, if (mt < 0) null else mt == 1)
            }
        }

        /** The board as a coach position (benches read too; whose move from the Undo button, else yours). */
        fun toState(): Puka.State? {
            if (!normalView) return null
            val s = Puka.empty(if (myTurn == false) 1 else 0, tall)
            for ((y, row) in grid.withIndex()) for ((x, c) in row.withIndex()) {
                if (c.isEmpty()) continue
                val t = when (c[1]) { 'L' -> Puka.LEADER; 'W' -> Puka.WHALE; 'D' -> Puka.DOLPHIN; 'T' -> Puka.TURTLE; 'G' -> Puka.GOLD; else -> return null }
                s.b[s.idx(x, y)] = t or (if (c[0] == 'O') 8 else 0)
            }
            for (side in 0..1) for (k in benches[side]) {
                val t = when (k) { 'W' -> Puka.WHALE; 'D' -> Puka.DOLPHIN; else -> Puka.TURTLE }
                s.h[side * 5 + t]++
            }
            return s
        }
    }

    private class Masks(n: Int) {
        val org = BooleanArray(n); val blu = BooleanArray(n); val side = BooleanArray(n)
        val red = BooleanArray(n); val gold = BooleanArray(n); val whale = BooleanArray(n)
        val pink = BooleanArray(n); val green = BooleanArray(n); val face = BooleanArray(n)
        val white = BooleanArray(n)
    }

    private var w = W
    private var h = 0

    private fun masks(px: IntArray): Masks {
        val m = Masks(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 255) / 255f
            val g = ((c shr 8) and 255) / 255f
            val b = (c and 255) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val d = mx - mn + 1e-6f
            var hh = when (mx) {
                r -> ((g - b) / d).let { v -> ((v % 6f) + 6f) % 6f }
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (hh < 0) hh += 360f
            val s = d / (mx + 1e-6f)
            val v = mx
            m.org[i] = hh > 12 && hh < 27 && s > 0.78f && v > 0.8f
            m.blu[i] = hh > 198 && hh < 216 && s > 0.88f && v > 0.8f
            m.side[i] = hh > 218 && hh < 228 && s > 0.72f && v > 0.88f
            m.red[i] = (hh < 10 || hh > 350) && s > 0.6f && v > 0.55f
            m.gold[i] = hh > 28 && hh < 56 && s > 0.4f && v > 0.65f
            m.whale[i] = hh > 218 && hh < 230 && s > 0.55f && s < 0.74f && v > 0.65f && v < 0.92f
            m.pink[i] = hh > 305 && hh < 340 && s > 0.45f && v > 0.7f
            m.green[i] = hh > 85 && hh < 150 && s > 0.45f && v > 0.4f
            m.face[i] = hh > 5 && hh < 45 && s > 0.06f && s < 0.4f && v > 0.85f
            m.white[i] = s < 0.1f && v > 0.93f
        }
        for (l in LABELS) for (y in l[1] until minOf(l[3], h)) for (x in l[0] until minOf(l[2], w)) { m.org[y * w + x] = false; m.blu[y * w + x] = false }
        return m
    }

    private fun mean(mask: BooleanArray, y0: Int, y1: Int, x0: Int, x1: Int): Double {
        var n = 0; var t = 0
        for (y in maxOf(y0, 0) until minOf(y1, h)) for (x in maxOf(x0, 0) until minOf(x1, w)) { n++; if (mask[y * w + x]) t++ }
        return if (n == 0) 0.0 else t.toDouble() / n
    }

    private fun boardSize(m: Masks): Pair<Int, Double> {
        var best = 3 to -1.0
        for ((n, rows) in LAYOUT) {
            var sc = 0.0; var k = 0
            for (r in rows) {
                val hgt = r.y1 - r.y0
                for (c in r.cols) {
                    val yb = (r.y1 + 0.02 * hgt).toInt()
                    val wd = c[1] - c[0]
                    sc += mean(m.side, yb, yb + 4, (c[0] + 0.3 * wd).toInt(), (c[1] - 0.3 * wd).toInt()); k++
                }
            }
            sc /= k
            if (sc > best.second) best = n to sc
        }
        return best
    }

    private fun ownerOf(m: Masks, y0: Int, y1: Int, x0: Int, x1: Int): Char {
        val hh = y1 - y0; val ww = x1 - x0
        val oy0 = (y0 + 0.06 * hh).toInt(); val oy1 = (y1 - 0.06 * hh).toInt(); val ox0 = (x0 + 0.05 * ww).toInt(); val ox1 = (x1 - 0.05 * ww).toInt()
        val iy0 = (y0 + 0.25 * hh).toInt(); val iy1 = (y1 - 0.25 * hh).toInt(); val ix0 = (x0 + 0.2 * ww).toInt(); val ix1 = (x1 - 0.2 * ww).toInt()
        var n = 0; var o = 0; var b = 0
        for (y in oy0 until oy1) for (x in ox0 until ox1) {
            if (y in iy0 until iy1 && x in ix0 until ix1) continue
            if (y !in 0 until h || x !in 0 until w) continue
            n++; val i = y * w + x
            if (m.org[i]) o++; if (m.blu[i]) b++
        }
        if (n == 0) return '.'
        val om = o.toDouble() / n; val bm = b.toDouble() / n
        return if (bm > 0.05 && bm > 3 * om) 'B' else if (om > 0.015 && om > 3 * bm) 'O' else '.'
    }

    // ---- binary morphology on a sub-window: square (2r+1) windows, zero border (same as r rounds of 3x3, like scipy) ----
    private fun boxCount(a: BooleanArray, ww: Int, hh: Int): IntArray {
        val p = IntArray((ww + 1) * (hh + 1))
        for (y in 0 until hh) { var row = 0; for (x in 0 until ww) { if (a[y * ww + x]) row++; p[(y + 1) * (ww + 1) + x + 1] = p[y * (ww + 1) + x + 1] + row } }
        return p
    }
    private fun rect(p: IntArray, ww: Int, x0: Int, y0: Int, x1: Int, y1: Int) =
        p[y1 * (ww + 1) + x1] - p[y0 * (ww + 1) + x1] - p[y1 * (ww + 1) + x0] + p[y0 * (ww + 1) + x0]

    private fun dilate(a: BooleanArray, ww: Int, hh: Int, r: Int): BooleanArray {
        val p = boxCount(a, ww, hh); val n = BooleanArray(a.size)
        for (y in 0 until hh) for (x in 0 until ww)
            n[y * ww + x] = rect(p, ww, maxOf(x - r, 0), maxOf(y - r, 0), minOf(x + r + 1, ww), minOf(y + r + 1, hh)) > 0
        return n
    }

    private fun erode(a: BooleanArray, ww: Int, hh: Int, r: Int): BooleanArray {
        val p = boxCount(a, ww, hh); val n = BooleanArray(a.size); val full = (2 * r + 1) * (2 * r + 1)
        for (y in r until hh - r) for (x in r until ww - r)
            n[y * ww + x] = rect(p, ww, x - r, y - r, x + r + 1, y + r + 1) == full
        return n
    }

    private class Comp(var area: Int, var y: Double, var x: Double, val pix: IntArray)

    /** Connected parts (4-connected) of a closed mask; positions in frame coordinates. */
    private fun comps(mask: BooleanArray, ww: Int, hh: Int, ox: Int, oy: Int): List<Comp> {
        val closed = erode(dilate(mask, ww, hh, 2), ww, hh, 2)
        val lab = IntArray(closed.size)
        val out = ArrayList<Comp>()
        val stack = IntArray(closed.size)
        var next = 0
        for (s in closed.indices) {
            if (!closed[s] || lab[s] != 0) continue
            next++
            var sp = 0; stack[sp++] = s; lab[s] = next
            val pix = ArrayList<Int>()
            var sy = 0.0; var sx = 0.0
            while (sp > 0) {
                val p = stack[--sp]; pix.add(p)
                val py = p / ww; val px = p % ww
                sy += py; sx += px
                if (py > 0) { val q = p - ww; if (closed[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q } }
                if (py < hh - 1) { val q = p + ww; if (closed[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q } }
                if (px > 0) { val q = p - 1; if (closed[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q } }
                if (px < ww - 1) { val q = p + 1; if (closed[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q } }
            }
            if (pix.size < 40) continue
            out.add(Comp(pix.size, sy / pix.size + oy, sx / pix.size + ox, pix.toIntArray()))
        }
        return out
    }

    private class Blob(val k: Char, var y: Double, var x: Double, var area: Int)

    private fun sub(mask: BooleanArray, x0: Int, y0: Int, ww: Int, hh: Int): BooleanArray {
        val a = BooleanArray(ww * hh)
        for (y in 0 until hh) for (x in 0 until ww) a[y * ww + x] = mask[(y0 + y) * w + x0 + x]
        return a
    }

    private fun blobs(m: Masks, x0: Int, x1: Int, y0: Int, y1: Int, minA: Int): MutableList<Blob> {
        val ww = x1 - x0; val hh = y1 - y0
        val gold = sub(m.gold, x0, y0, ww, hh); val red = sub(m.red, x0, y0, ww, hh)
        val goldNear = dilate(gold, ww, hh, 8); val redNear = dilate(red, ww, hh, 8)
        val raw = ArrayList<Blob>()
        for (c in comps(red, ww, hh, x0, y0)) if (c.pix.count { goldNear[it] } >= 8) raw.add(Blob('L', c.y, c.x, c.area))
        for (c in comps(gold, ww, hh, x0, y0)) if (c.pix.count { redNear[it] } < 8) raw.add(Blob('G', c.y, c.x, c.area))
        for ((mk, k) in listOf(m.whale to 'W', m.pink to 'D', m.green to 'T'))
            for (c in comps(sub(mk, x0, y0, ww, hh), ww, hh, x0, y0)) raw.add(Blob(k, c.y, c.x, c.area))
        val merged = ArrayList<Blob>()
        for (b in raw.sortedByDescending { it.area }) {
            val c = merged.firstOrNull { it.k == b.k && Math.abs(it.y - b.y) < 45 && Math.abs(it.x - b.x) < 60 }
            if (c != null) {
                c.y = (c.y * c.area + b.y * b.area) / (c.area + b.area); c.x = (c.x * c.area + b.x * b.area) / (c.area + b.area); c.area += b.area
            } else merged.add(Blob(b.k, b.y, b.x, b.area))
        }
        return merged.filter { it.area >= (if (it.k == 'L') minA * 0.6 else minA.toDouble()) }.toMutableList()
    }

    /** A hat in a small window (benches): same colour rules, by pixel counts. */
    private fun hatIn(m: Masks, y0: Int, y1: Int, x0: Int, x1: Int, minPx: Int): Char {
        var red = 0; var gold = 0; var whale = 0; var pink = 0; var green = 0
        for (y in maxOf(y0, 0) until minOf(y1, h)) for (x in maxOf(x0, 0) until minOf(x1, w)) {
            val i = y * w + x
            if (m.red[i]) red++; if (m.gold[i]) gold++; if (m.whale[i]) whale++; if (m.pink[i]) pink++; if (m.green[i]) green++
        }
        val sc = linkedMapOf('L' to minOf(red, gold * 3), 'W' to whale, 'D' to pink, 'T' to green, 'G' to (if (red < gold * 0.3) gold else 0))
        var k = 'L'; var best = -1
        for ((kk, v) in sc) if (v > best) { best = v; k = kk }
        return if (best > minPx) k else '?'
    }

    private fun count(m: BooleanArray, y0: Int, y1: Int, x0: Int, x1: Int): Int {
        var t = 0
        for (y in maxOf(y0, 0) until minOf(y1, h)) for (x in maxOf(x0, 0) until minOf(x1, w)) if (m[y * w + x]) t++
        return t
    }

    private fun benches(m: Masks): List<List<Char>> = (0..1).map { side ->
        val got = ArrayList<Char>()
        for (k in 0 until 6) {
            val x = if (side == 0) 165 + 100 * k else 1153 - 100 * k
            val kind = hatIn(m, 705, 790, x - 45, x + 45, 400)
            val face = count(m.face, 740, 795, x - 35, x + 35)
            if (kind != '?' && face > 25) got.add(kind) else break
        }
        got
    }

    private fun undoCount(px: IntArray): Int? {
        if (digits.isEmpty()) return null
        val (x0, y0, x1, y1) = UNDO_BOX.toList()
        val ww = x1 - x0; val hh = y1 - y0
        if (y1 > h) return null
        val g = FloatArray(ww * hh)
        for (y in 0 until hh) for (x in 0 until ww) {
            val c = px[(y0 + y) * w + x0 + x]
            g[y * ww + x] = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255))
        }
        val mu = g.average().toFloat()
        var sd = 0.0; for (v in g) sd += (v - mu) * (v - mu); sd = Math.sqrt(sd / g.size)
        for (i in g.indices) g[i] = ((g[i] - mu) / (sd + 1e-6)).toFloat()
        var best: Int? = null; var bs = 0.5
        for ((k, t) in digits) {
            if (t.size != g.size) continue
            var s = 0.0; for (i in g.indices) s += g[i] * t[i]; s /= g.size
            if (s > bs) { bs = s; best = k }
        }
        return best
    }

    /** Undo button white (your turn) or grey (theirs); null when it can't tell. */
    /** From the header at the top: "Your turn" starts further right than the wider "Opponent's turn". */
    private fun myTurn(m: Masks): Boolean? {
        var left = -1
        for (x in 440 until 840) {
            var n = 0
            for (y in 22 until minOf(48, h)) if (m.white[y * w + x]) n++
            if (n >= 2) { left = x; break }
        }
        return when (left) { in 585..612 -> true; in 545..578 -> false; else -> null }
    }

    /** [px] = RGB pixels of a frame [width] wide (scaled to 1280 first). */
    fun read(px: IntArray, width: Int = W, size: Int? = null): Reading {
        w = width; h = px.size / width
        val m = masks(px)
        val (n, score) = if (size == null) boardSize(m) else size to 1.0
        val rows = LAYOUT.getValue(n)
        val own = Array(rows.size) { CharArray(4) }
        val cen = Array(rows.size) { arrayOfNulls<DoubleArray>(4) }
        for ((ry, r) in rows.withIndex()) for ((cx, c) in r.cols.withIndex()) {
            own[ry][cx] = ownerOf(m, r.y0, r.y1, c[0], c[1])
            var x = (c[0] + c[1]) / 2.0; x += 0.08 * (x - 640)
            cen[ry][cx] = doubleArrayOf((r.y0 + r.y1) / 2.0, x)
        }
        val kind = Array(rows.size) { CharArray(4) { ' ' } }
        val minA = if (n == 4) 400 else 700
        for (c in 0 until 4) {
            val xl = rows.indices.minOf { cen[it][c]!![1] } - 90; val xr = rows.indices.maxOf { cen[it][c]!![1] } + 90
            val x0 = maxOf(xl, 0.0).toInt(); val x1 = minOf(xr, w.toDouble()).toInt()
            var bl = blobs(m, x0, x1, 0, minOf(rows.last().y1, h), minA)
            val used = HashSet<Blob>()
            for (r in rows.indices.reversed()) {
                if (own[r][c] == '.') continue
                val (cy, cx) = cen[r][c]!!.let { it[0] to it[1] }
                val ey = cy - 160
                val pick = bl.filter { it !in used && it.y - ey > -100 && it.y - ey < 70 && Math.abs(it.x - cx) < 85 }.minByOrNull { Math.abs(it.y - ey) }
                if (pick != null) {
                    used.add(pick); kind[r][c] = pick.k
                    val lim = pick.y
                    for (b in bl) if (b !in used && b.y >= lim - 10) b.y = 1e9
                } else kind[r][c] = '?'
            }
        }
        val grid = rows.indices.map { r -> (0 until 4).map { c -> if (own[r][c] == '.') "" else "${own[r][c]}${kind[r][c]}" } }
        return Reading(n, score, grid, benches(m), undoCount(px), myTurn(m))
    }
}

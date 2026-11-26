package com.ozyman.holomemflicker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws the in-game coach panel: the mini board (laid out like the game: your side on the left,
 * attacking right), both benches, the prompt and the D-pad list.
 *
 * Colours follow the game's pieces: Whale blue, Dolphin pink, Turtle green, Leader yellow
 * (Gold Turtle: green with a gold ring). Your pieces are rounded squares, theirs are circles.
 * The best move is red, outlined in the colour of the piece it moves; a reply of theirs picked
 * in the list is violet the same way.
 */
class CoachPainter(private val density: Float) {
    companion object {
        val LEADER = Color.rgb(0xFF, 0xD0, 0x2E)
        val DOLPHIN = Color.rgb(0xFF, 0x6F, 0xAE)
        val WHALE = Color.rgb(0x4C, 0x9B, 0xFF)
        val TURTLE = Color.rgb(0x46, 0xC6, 0x66)
        val GOLD_RING = Color.rgb(0xFF, 0xB3, 0x00)
        val BEST = Color.rgb(0xE5, 0x30, 0x2E)
        val THEIRS = Color.rgb(0x9C, 0x6B, 0xFF)
        val PANEL = Color.rgb(0x16, 0x1B, 0x2A)
        val INK = Color.rgb(0xF2, 0xF4, 0xF8)
        val MUTED = Color.rgb(0x9A, 0xA3, 0xB5)

        fun colorOf(t: Int): Int = when (t) {
            Puka.LEADER -> LEADER
            Puka.DOLPHIN -> DOLPHIN
            Puka.WHALE -> WHALE
            else -> TURTLE
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Where the panel was drawn last (screen pixels), so the Fix board button can sit under it. */
    val panel = RectF()

    private var u = density // one "dp" at the current size

    fun draw(c: Canvas, w: Float, h: Float, coach: Coach, now: Long) {
        val st = coach.settings
        u = density * st.scale / 100f
        val cell = 50 * u
        val bench = 40 * u
        val pad = 14 * u
        val boardW = bench * 2 + cell * 4 + 12 * u
        val pw = boardW + pad * 2
        val items = coach.items
        val listH = items.size * 34 * u
        val ph = pad + 22 * u + cell * 3 + 14 * u + 26 * u + 20 * u + listH + pad
        val x0 = ((w - pw) * st.x).coerceAtLeast(0f)
        val y0 = ((h - ph) * st.y).coerceAtLeast(0f)
        panel.set(x0, y0, x0 + pw, y0 + ph)

        val layer = c.saveLayerAlpha(panel.left - 4 * u, panel.top - 4 * u, panel.right + 4 * u, panel.bottom + 4 * u, 255 * st.opacity / 100)

        // panel
        fill.color = PANEL
        fill.alpha = 245
        c.drawRoundRect(panel, 18 * u, 18 * u, fill)
        stroke.color = Raden.alpha(INK, 0x30); stroke.strokeWidth = 1.5f * u
        c.drawRoundRect(panel, 18 * u, 18 * u, stroke)

        var y = y0 + pad
        text.textSize = 11 * u; text.isFakeBoldText = true; text.letterSpacing = 0.18f; text.color = MUTED
        text.textAlign = Paint.Align.LEFT
        c.drawText("PUKA PUKA COACH", x0 + pad, y + 11 * u, text)
        text.textAlign = Paint.Align.RIGHT; text.isFakeBoldText = false; text.letterSpacing = 0f
        c.drawText("Select+Start closes", x0 + pw - pad, y + 11 * u, text)
        y += 22 * u

        // board + benches
        val bx = x0 + pad + bench + 6 * u
        val by = y
        drawBoard(c, coach, bx, by, cell)
        drawBench(c, coach, 0, x0 + pad, by, bench, cell)
        drawBench(c, coach, 1, bx + cell * 4 + 6 * u, by, bench, cell)
        y += cell * 3 + 14 * u

        // prompt + detail
        text.textAlign = Paint.Align.LEFT
        text.textSize = 19 * u; text.isFakeBoldText = true
        text.color = when {
            coach.phase == Coach.Phase.OVER && Puka.winner(coach.state) == 0 && !coach.drawn -> LEADER
            coach.phase == Coach.Phase.MY_TURN && !coach.thinking -> Raden.mix(BEST, Color.WHITE, 0.35f)
            else -> INK
        }
        var prompt = coach.prompt
        if (coach.thinking) prompt = "Thinking" + ".".repeat(((now / 300) % 4).toInt())
        c.drawText(prompt, x0 + pad, y + 19 * u, text)
        y += 26 * u
        text.textSize = 12 * u; text.isFakeBoldText = false; text.color = MUTED
        c.drawText(ellipsize(coach.detail, pw - pad * 2), x0 + pad, y + 12 * u, text)
        y += 20 * u

        // list
        for ((i, it) in items.withIndex()) {
            val r = RectF(x0 + pad - 4 * u, y + 2 * u, x0 + pw - pad + 4 * u, y + 32 * u)
            val on = i == coach.sel
            if (on) {
                val hl = coach.highlight
                fill.color = if (hl != null && hl.second == Coach.Role.THEIRS) THEIRS else if (coach.phase == Coach.Phase.MY_TURN && i == 0) BEST else Raden.alpha(INK, 0x2A)
                if (fill.color == THEIRS || fill.color == BEST) fill.alpha = 0xC0
                c.drawRoundRect(r, 10 * u, 10 * u, fill)
            }
            text.textSize = 14 * u; text.isFakeBoldText = on; text.color = INK
            text.textAlign = Paint.Align.LEFT
            c.drawText(ellipsize((if (on) "▸ " else "   ") + it.label, r.width() * 0.72f), r.left + 10 * u, r.centerY() + 5 * u, text)
            if (it.hint.isNotEmpty()) {
                text.textAlign = Paint.Align.RIGHT; text.isFakeBoldText = false; text.color = MUTED; text.textSize = 12 * u
                c.drawText(it.hint, r.right - 10 * u, r.centerY() + 4 * u, text)
            }
            y += 34 * u
        }
        c.restoreToCount(layer)
    }

    private fun ellipsize(s: String, max: Float): String {
        if (text.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && text.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private fun cellRect(bx: Float, by: Float, cell: Float, i: Int): RectF {
        val x = bx + Puka.screenX(i) * cell
        val y = by + Puka.screenY(i) * cell
        return RectF(x, y, x + cell, y + cell)
    }

    private fun drawBoard(c: Canvas, coach: Coach, bx: Float, by: Float, cell: Float) {
        val s = coach.state
        // squares: your back column tinted warm, theirs cool (like the game's two halves)
        for (i in 0 until 12) {
            val r = cellRect(bx, by, cell, i)
            r.inset(1.5f * u, 1.5f * u)
            fill.color = when (Puka.screenX(i)) { 0 -> Color.rgb(0x3A, 0x2F, 0x2A); 3 -> Color.rgb(0x26, 0x30, 0x44); else -> Color.rgb(0x26, 0x2C, 0x3B) }
            c.drawRoundRect(r, 8 * u, 8 * u, fill)
        }
        coach.last?.let { m ->
            val r = cellRect(bx, by, cell, m.to); r.inset(2 * u, 2 * u)
            stroke.color = Raden.alpha(INK, 0x55); stroke.strokeWidth = 2 * u
            c.drawRoundRect(r, 8 * u, 8 * u, stroke)
        }
        // highlighted move (under the pieces)
        val hl = coach.highlight
        if (hl != null) {
            val (m, role) = hl
            val col = if (role == Coach.Role.BEST) BEST else THEIRS
            val t = if (m.drop != 0) m.drop else Puka.typ(s.b[m.from])
            if (m.from >= 0) mark(c, cellRect(bx, by, cell, m.from), col, t, false)
            mark(c, cellRect(bx, by, cell, m.to), col, t, true)
        }
        // hand entry: legal targets, the picked piece and the cursor
        if (coach.phase == Coach.Phase.MANUAL) {
            coach.picked?.let { p -> if (p.from >= 0) mark(c, cellRect(bx, by, cell, p.from), BEST, Puka.typ(s.b[p.from]), false) }
            for (t in coach.targets) {
                val r = cellRect(bx, by, cell, t)
                fill.color = Raden.alpha(INK, 0x70)
                c.drawCircle(r.centerX(), r.centerY(), 7 * u, fill)
            }
        }
        for (i in 0 until 12) {
            val p = s.b[i]
            if (p == 0) continue
            val r = cellRect(bx, by, cell, i)
            piece(c, r.centerX(), r.centerY(), cell * 0.36f, Puka.typ(p), Puka.own(p))
        }
        if (hl != null && hl.first.from >= 0) arrow(c, cellRect(bx, by, cell, hl.first.from), cellRect(bx, by, cell, hl.first.to), if (hl.second == Coach.Role.BEST) BEST else THEIRS)
        if (coach.phase == Coach.Phase.MANUAL && coach.cx in 0..3) {
            val r = cellRect(bx, by, cell, Puka.index(coach.cx, coach.cy)); r.inset(1 * u, 1 * u)
            stroke.color = Color.WHITE; stroke.strokeWidth = 3.5f * u
            c.drawRoundRect(r, 9 * u, 9 * u, stroke)
        }
    }

    /** Red (or violet) square with a thick outline in the moving piece's colour. */
    private fun mark(c: Canvas, cellR: RectF, col: Int, t: Int, target: Boolean) {
        val r = RectF(cellR); r.inset(2.5f * u, 2.5f * u)
        fill.color = col; fill.alpha = if (target) 0xD0 else 0x80
        c.drawRoundRect(r, 8 * u, 8 * u, fill)
        stroke.color = if (t == Puka.GOLD) GOLD_RING else colorOf(t); stroke.strokeWidth = 4 * u
        c.drawRoundRect(r, 8 * u, 8 * u, stroke)
    }

    private fun arrow(c: Canvas, a: RectF, b: RectF, col: Int) {
        val dx = b.centerX() - a.centerX(); val dy = b.centerY() - a.centerY()
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1) return
        val ux = dx / len; val uy = dy / len
        val sx = a.centerX() + ux * a.width() * 0.42f; val sy = a.centerY() + uy * a.height() * 0.42f
        val ex = b.centerX() - ux * b.width() * 0.42f; val ey = b.centerY() - uy * b.height() * 0.42f
        stroke.color = Color.WHITE; stroke.strokeWidth = 6 * u; stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(sx, sy, ex, ey, stroke)
        stroke.color = col; stroke.strokeWidth = 3.5f * u
        c.drawLine(sx, sy, ex, ey, stroke)
        path.reset()
        path.moveTo(ex + ux * 7 * u, ey + uy * 7 * u)
        path.lineTo(ex - ux * 5 * u - uy * 6 * u, ey - uy * 5 * u + ux * 6 * u)
        path.lineTo(ex - ux * 5 * u + uy * 6 * u, ey - uy * 5 * u - ux * 6 * u)
        path.close()
        fill.color = col
        c.drawPath(path, fill)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawBench(c: Canvas, coach: Coach, side: Int, x: Float, by: Float, w: Float, cell: Float) {
        val s = coach.state
        val r = RectF(x, by, x + w, by + cell * 3)
        fill.color = Raden.alpha(INK, 0x10)
        c.drawRoundRect(r, 10 * u, 10 * u, fill)
        val hl = coach.highlight
        for (k in 0..2) {
            val t = Puka.DOLPHIN + k
            val cy = by + cell * k + cell / 2
            val slot = RectF(x, by + cell * k, x + w, by + cell * (k + 1))
            if (hl != null && hl.first.drop == t && s.turn == side) mark(c, slot, if (hl.second == Coach.Role.BEST) BEST else THEIRS, t, false)
            if (coach.phase == Coach.Phase.MANUAL && coach.picked?.drop == t && s.turn == side) mark(c, slot, BEST, t, false)
            val n = s.hand(side, t)
            if (n > 0) {
                piece(c, x + w / 2, cy, w * 0.34f, t, side)
                if (n > 1) {
                    text.textSize = 11 * u; text.isFakeBoldText = true; text.textAlign = Paint.Align.CENTER
                    fill.color = INK
                    c.drawCircle(x + w - 9 * u, cy + 12 * u, 8 * u, fill)
                    text.color = PANEL
                    c.drawText("×$n", x + w - 9 * u, cy + 16 * u, text)
                }
            } else {
                fill.color = Raden.alpha(INK, 0x22)
                c.drawCircle(x + w / 2, cy, 3 * u, fill)
            }
            if (coach.phase == Coach.Phase.MANUAL && coach.cx == (if (side == 0) -1 else 4) && coach.cy == k) {
                stroke.color = Color.WHITE; stroke.strokeWidth = 3.5f * u
                val cr = RectF(slot); cr.inset(1 * u, 1 * u)
                c.drawRoundRect(cr, 9 * u, 9 * u, stroke)
            }
        }
        text.textSize = 9 * u; text.isFakeBoldText = true; text.textAlign = Paint.Align.CENTER; text.color = MUTED
        c.drawText(if (side == 0) "YOU" else "THEM", x + w / 2, by + cell * 3 + 11 * u, text)
    }

    /** One piece: type colour, square for you / circle for them, dots for where it can step. */
    fun piece(c: Canvas, cx: Float, cy: Float, rad: Float, t: Int, side: Int) {
        fill.color = colorOf(t)
        if (side == 0) c.drawRoundRect(cx - rad, cy - rad, cx + rad, cy + rad, rad * 0.35f, rad * 0.35f, fill)
        else c.drawCircle(cx, cy, rad * 1.08f, fill)
        stroke.strokeWidth = 2.2f * u
        stroke.color = if (t == Puka.GOLD) GOLD_RING else if (side == 0) Color.WHITE else Color.rgb(0x10, 0x12, 0x1A)
        if (t == Puka.GOLD) stroke.strokeWidth = 3.5f * u
        if (side == 0) c.drawRoundRect(cx - rad, cy - rad, cx + rad, cy + rad, rad * 0.35f, rad * 0.35f, stroke)
        else c.drawCircle(cx, cy, rad * 1.08f, stroke)
        // letter
        text.textAlign = Paint.Align.CENTER; text.isFakeBoldText = true; text.textSize = rad * 0.95f
        text.color = Color.rgb(0x10, 0x12, 0x1A)
        val letter = when (t) { Puka.LEADER -> "L"; Puka.DOLPHIN -> "D"; Puka.WHALE -> "W"; Puka.TURTLE -> "T"; else -> "G" }
        c.drawText(letter, cx, cy + rad * 0.34f, text)
        // step dots: your pieces face right (toward their side), theirs face left
        fill.color = Color.rgb(0x10, 0x12, 0x1A)
        val sgn = if (side == 0) 1 else -1
        for (d in Puka.DIRS.getValue(t)) {
            val dx = -d[0] * sgn // board row step -1 (forward) = screen right for you
            val dy = d[1]
            c.drawCircle(cx + dx * rad * 0.78f, cy + dy * rad * 0.78f, rad * 0.11f, fill)
        }
    }
}

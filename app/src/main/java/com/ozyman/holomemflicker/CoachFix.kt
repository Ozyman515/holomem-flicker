package com.ozyman.holomemflicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The one touch control of the in-game coach: "Fix board", which must be held for a second
 * (a ring fills up) so brushing the screen does nothing. Lives in its own small window.
 */
class CoachFixButton(ctx: Context, private val onFire: () -> Unit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; isFakeBoldText = true }
    private var downAt = 0L
    var scale = 1f

    companion object { const val HOLD_MS = 1000L }

    fun sizeW() = (150 * d * scale).toInt()
    fun sizeH() = (44 * d * scale).toInt()

    override fun onDraw(c: Canvas) {
        val u = d * scale
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        r.inset(2 * u, 2 * u)
        fill.color = CoachPainter.PANEL
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        val cx = r.left + r.height() / 2
        val cy = r.centerY()
        val rad = r.height() / 2 - 7 * u
        ring.strokeWidth = 4 * u
        ring.color = Raden.alpha(CoachPainter.INK, 0x40)
        c.drawCircle(cx, cy, rad, ring)
        val p = progress()
        if (p > 0f) {
            ring.color = CoachPainter.BEST
            c.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, -90f, 360f * p, false, ring)
            postInvalidateOnAnimation()
        }
        text.textSize = 13 * u
        text.color = CoachPainter.INK
        c.drawText("Fix board", cx + rad + 10 * u, cy - 1 * u, text)
        text.textSize = 10 * u; text.isFakeBoldText = false
        text.color = CoachPainter.MUTED
        c.drawText(if (p > 0f) "keep holding…" else "hold to open", cx + rad + 10 * u, cy + 12 * u, text)
        text.isFakeBoldText = true
    }

    private fun progress(): Float = if (downAt == 0L) 0f else ((SystemClock.uptimeMillis() - downAt) / HOLD_MS.toFloat()).coerceIn(0f, 1f)

    private val fire = Runnable { if (downAt != 0L) { downAt = 0L; invalidate(); onFire() } }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); postDelayed(fire, HOLD_MS); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; removeCallbacks(fire); invalidate() }
        }
        return true
    }
}

/**
 * Touch editor for the coach's board ("Fix board"): pick a piece from the palette (or the
 * eraser) and tap squares, + / − for the benches, choose whose move it is, then Done.
 * Laid out like the game: your side on the left.
 */
class CoachFixView(ctx: Context, start: Puka.State, private val title: String = "Fix board", private val onDone: (Puka.State?) -> Unit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val painter = CoachPainter(d)
    var s = start.copy()
    private var pick = Puka.LEADER // type; 0 = eraser
    private var pickSide = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    private val cell get() = 64 * d
    fun sizeW() = (cell * 4 + 2 * 120 * d + 48 * d).toInt()
    fun sizeH() = (40 * d + cell * 4 + 24 * d + 56 * d + 16 * d + 52 * d + 24 * d).toInt() // room for the 4-tall board

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat()
        fill.color = CoachPainter.PANEL
        c.drawRoundRect(0f, 0f, w, height.toFloat(), 20 * d, 20 * d, fill)
        text.textSize = 16 * d; text.color = CoachPainter.INK
        c.drawText("$title: pick a piece, tap squares", w / 2, 28 * d, text)

        val bench = 120 * d
        val bx = 24 * d + bench
        val by = 40 * d
        // board
        for (i in 0 until s.n) {
            val x = bx + s.sx(i) * cell
            val y = by + s.sy(i) * cell
            val r = RectF(x, y, x + cell, y + cell); r.inset(2 * d, 2 * d)
            fill.color = if (s.sx(i) == 0) Color.rgb(0x3A, 0x2F, 0x2A) else Color.rgb(0x26, 0x2C, 0x3B)
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            val p = s.b[i]
            if (p != 0) painter.piece(c, r.centerX(), r.centerY(), cell * 0.34f, Puka.typ(p), Puka.own(p))
            hits.add(RectF(r) to { s.b[i] = if (pick == 0) 0 else pick or (if (pickSide == 1) 8 else 0); invalidate() })
        }
        // benches with + / −
        for (side in 0..1) {
            val x = if (side == 0) 12 * d else bx + cell * 4 + 12 * d
            for (k in 0..2) {
                val t = Puka.DOLPHIN + k
                val cy = by + cell * k + cell / 2
                painter.piece(c, x + 22 * d, cy, 15 * d, t, side)
                text.textSize = 15 * d; text.color = CoachPainter.INK
                c.drawText("×${s.hand(side, t)}", x + 56 * d, cy + 5 * d, text)
                val minus = RectF(x + 72 * d, cy - 14 * d, x + 92 * d, cy + 14 * d)
                val plus = RectF(x + 94 * d, cy - 14 * d, x + 114 * d, cy + 14 * d)
                button(c, minus, "−") { s.h[side * 5 + t] = (s.h[side * 5 + t] - 1).coerceAtLeast(0) }
                button(c, plus, "+") { s.h[side * 5 + t] = (s.h[side * 5 + t] + 1).coerceAtMost(2) }
            }
        }
        // palette: your 5 types, their 5 types, eraser
        var y = by + cell * s.w + 24 * d
        val pw = ((w - 48 * d - 8 * d * 10) / 11).coerceAtMost(48 * d)
        val total = pw * 11 + 8 * d * 10
        var x = (w - total) / 2
        for (side in 0..1) for (t in 1..5) {
            val r = RectF(x, y, x + pw, y + pw)
            if (pick == t && pickSide == side) { stroke.color = Color.WHITE; stroke.strokeWidth = 3 * d; c.drawRoundRect(r, 10 * d, 10 * d, stroke) }
            painter.piece(c, r.centerX(), r.centerY(), pw * 0.3f, t, side)
            hits.add(r to { pick = t; pickSide = side; invalidate() })
            x += pw + 8 * d
        }
        val er = RectF(x, y, x + pw, y + pw)
        if (pick == 0) { stroke.color = Color.WHITE; stroke.strokeWidth = 3 * d; c.drawRoundRect(er, 10 * d, 10 * d, stroke) }
        text.textSize = 22 * d; text.color = CoachPainter.INK
        c.drawText("✕", er.centerX(), er.centerY() + 8 * d, text)
        hits.add(er to { pick = 0; invalidate() })
        y += pw + 16 * d
        // board size, whose move, Cancel / Done
        val bw = (w - 48 * d - 36 * d) / 4
        button(c, RectF(24 * d, y, 24 * d + bw, y + 44 * d), "${s.w} tall ⇄") {
            // switching size starts an empty board of the other size (benches kept)
            s = Puka.State(IntArray((7 - s.w) * 4), s.h.copyOf(), s.turn, 7 - s.w)
        }
        button(c, RectF(36 * d + bw, y, 36 * d + bw * 2, y + 44 * d), if (s.turn == 0) "Your move" else "Their move") { s.turn = 1 - s.turn }
        button(c, RectF(48 * d + bw * 2, y, 48 * d + bw * 3, y + 44 * d), "Cancel") { onDone(null) }
        val ok = Puka.leaderPos(s, 0) >= 0 && Puka.leaderPos(s, 1) >= 0
        button(c, RectF(60 * d + bw * 3, y, 60 * d + bw * 4, y + 44 * d), if (ok) "Done" else "Add Leaders", primary = ok) { if (ok) onDone(s.copy()) }
    }

    private fun button(c: Canvas, r: RectF, label: String, primary: Boolean = false, run: () -> Unit) {
        fill.color = if (primary) CoachPainter.BEST else Raden.alpha(CoachPainter.INK, 0x26)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        text.textSize = (r.height() * 0.42f).coerceAtMost(16 * d); text.color = CoachPainter.INK
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
        hits.add(RectF(r) to { run(); invalidate() })
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            for ((r, f) in hits.asReversed()) if (r.contains(e.x, e.y)) { f(); break }
        }
        return true
    }
}

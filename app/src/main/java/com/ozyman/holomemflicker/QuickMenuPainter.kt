package com.ozyman.holomemflicker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Draws the quick menu ([QuickMenu]) over the game: a dark glass panel in the middle of the
 * screen. The highlighted row is a lit pill in the theme's tap glow (the same family as the mode
 * banner); the current mode gets a small dot. [t] runs 0→1 over the first ~140 ms after it opens
 * (fade + small rise), then stays at 1.
 */
class QuickMenuPainter(private val d: Float) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, w: Float, h: Float, rows: List<QuickMenu.Row>, selected: Int, glow: Int, t: Float = 1f) {
        if (rows.isEmpty()) return
        val ease = 1f - (1f - t) * (1f - t)
        val a = (255 * ease).toInt()
        val rowH = 44f * d
        val gap = 10f * d
        val padX = 18f * d
        val panelW = minOf(w * 0.8f, 340f * d)
        val titleH = 40f * d
        val footH = 34f * d
        val panelH = titleH + rows.size * rowH + rows.count { it.gapBefore } * gap + footH + 12f * d
        val left = (w - panelW) / 2
        val top = (h - panelH) / 2 + (1f - ease) * 16f * d
        val corner = 22f * d

        // panel
        val r = RectF(left, top, left + panelW, top + panelH)
        bg.color = Raden.alpha(Raden.mix(Color.BLACK, glow, 0.10f), (0xE6 * ease).toInt())
        bg.setShadowLayer(24f * d, 0f, 6f * d, Raden.alpha(Color.BLACK, (0x90 * ease).toInt()))
        c.drawRoundRect(r, corner, corner, bg)
        edge.strokeWidth = 1.5f * d
        edge.color = Raden.alpha(glow, (0x70 * ease).toInt())
        c.drawRoundRect(r, corner, corner, edge)

        // title
        label.textAlign = Paint.Align.LEFT
        label.textSize = 13f * d
        label.letterSpacing = 0.22f
        label.color = Raden.alpha(glow, a)
        c.drawText("QUICK MENU", left + padX, top + titleH * 0.68f, label)
        label.letterSpacing = 0f

        // rows
        var y = top + titleH
        rows.forEachIndexed { i, row ->
            if (row.gapBefore) {
                dot.color = Raden.alpha(Color.WHITE, (0x22 * ease).toInt())
                c.drawRect(left + padX, y + gap / 2 - d / 2, left + panelW - padX, y + gap / 2 + d / 2, dot)
                y += gap
            }
            val sel = i == selected
            if (sel) {
                val rr = RectF(left + 8f * d, y + 3f * d, left + panelW - 8f * d, y + rowH - 3f * d)
                pill.shader = LinearGradient(0f, rr.top, 0f, rr.bottom,
                    Raden.mix(glow, Color.WHITE, 0.25f), glow, Shader.TileMode.CLAMP)
                pill.alpha = a
                pill.setShadowLayer(12f * d, 0f, 0f, Raden.alpha(glow, (0xAA * ease).toInt()))
                c.drawRoundRect(rr, rr.height() / 2, rr.height() / 2, pill)
            }
            val ink = if (sel) Raden.mix(glow, Color.BLACK, 0.85f) else Color.WHITE
            if (row.current) {
                dot.color = Raden.alpha(if (sel) ink else glow, a)
                c.drawCircle(left + padX + 3f * d, y + rowH / 2, 3.5f * d, dot)
            }
            label.textAlign = Paint.Align.LEFT
            label.textSize = 17f * d
            label.color = Raden.alpha(ink, a)
            c.drawText(row.label, left + padX + 14f * d, y + rowH / 2 + 6f * d, label)
            if (row.hint.isNotEmpty()) {
                hint.textSize = 13f * d
                hint.color = Raden.alpha(ink, if (sel) a else (0x99 * ease).toInt())
                c.drawText(row.hint, left + panelW - padX - 6f * d, y + rowH / 2 + 5f * d, hint)
            }
            y += rowH
        }

        // controls
        foot.textSize = 12f * d
        foot.color = Raden.alpha(Color.WHITE, (0x88 * ease).toInt())
        c.drawText("D-pad  move   ·   A  choose   ·   B  close", left + panelW / 2, top + panelH - footH * 0.42f, foot)
    }
}

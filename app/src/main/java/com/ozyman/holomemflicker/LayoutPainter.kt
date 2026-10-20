package com.ozyman.holomemflicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Draws a mode's layout (tap points + stick zones). Shared by the editor and the in-game overlay. */
class LayoutPainter(private val ctx: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    /** [selected]: tap index, -1 = left stick, -2 = right stick, anything else = none. */
    fun draw(c: Canvas, w: Float, h: Float, l: Prefs.Layout, selected: Int = Int.MIN_VALUE) {
        stick(c, w, h, "L", l.ltype, l.lfx, l.lfy, selected == -1)
        stick(c, w, h, "R", l.rtype, l.rfx, l.rfy, selected == -2)
        l.taps.forEachIndexed { i, t ->
            val x = t.fx * w
            val y = t.fy * h
            fill.color = 0xAA2E7DFF.toInt()
            c.drawCircle(x, y, 42f, fill)
            ring.color = if (i == selected) Color.YELLOW else Color.WHITE
            c.drawCircle(x, y, 42f, ring)
            text.textSize = if (Prefs.buttonName(t.code).length > 3) 24f else 34f
            c.drawText(Prefs.buttonName(t.code), x, y + 11f, text)
        }
        text.textSize = 34f
    }

    private fun stick(c: Canvas, w: Float, h: Float, side: String, type: String, fx: Float, fy: Float, sel: Boolean) {
        val x = fx * w
        val y = fy * h
        val label = "$side stick · $type"
        val color = when (type) {
            "flick" -> 0xFFFF66CC.toInt()
            "joystick" -> 0xFF4ADE80.toInt()
            "camera" -> 0xFFFBBF24.toInt()
            else -> 0xFF8890B8.toInt()
        }
        val r = when (type) {
            "flick" -> Prefs.getInt(ctx, Prefs.RADIUS, 50) / 1000f * w
            "joystick" -> Prefs.getInt(ctx, Prefs.JOY, 60) / 1000f * w
            "camera" -> w * 0.05f
            else -> 30f
        }.coerceAtLeast(30f)
        if (type == "flick") {
            val reach = Prefs.getInt(ctx, Prefs.REACH, 18) / 100f * w
            ring.color = (color and 0x00FFFFFF) or 0x88000000.toInt()
            c.drawLine(x - reach, y, x + reach, y, ring)
        }
        fill.color = (color and 0x00FFFFFF) or 0x55000000
        c.drawCircle(x, y, r, fill)
        ring.color = if (sel) Color.YELLOW else color
        c.drawCircle(x, y, r, ring)
        if (type == "camera") {
            // little arrows to show it's a swipe area
            c.drawLine(x - r * 0.6f, y, x + r * 0.6f, y, ring)
            c.drawLine(x, y - r * 0.6f, x, y + r * 0.6f, ring)
        }
        c.drawText(label, x, y - r - 12f, text)
    }
}

package com.ozyman.holomemflicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Draws a mode's layout (tap points + stick zones). Shared by the editor and the in-game overlay.
 *
 * "Penlight" look: taps are lit round buttons (shine on top, glowing outline), sticks are dark
 * rings with a glowing edge and a faint inner ring, a flick stick shows its reach as a dotted
 * light line with a diamond at each end, and each stick gets a lit label chip underneath.
 * Glow colours follow the member theme: taps use a bright version of the theme's main colour,
 * flicks use the theme's accent.
 */
class LayoutPainter(private val ctx: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val rect = RectF()

    /** [selected]: tap index, -1 = left stick, -2 = right stick, anything else = none. */
    fun draw(c: Canvas, w: Float, h: Float, l: Prefs.Layout, selected: Int = Int.MIN_VALUE) {
        val k = w / 1000f // the design was drawn on a 1000-wide screen
        stick(c, w, h, k, "L", l.ltype, l.lfx, l.lfy, selected == -1)
        stick(c, w, h, k, "R", l.rtype, l.rfx, l.rfy, selected == -2)
        val glow = tapGlow()
        l.taps.forEachIndexed { i, t ->
            val x = t.fx * w
            val y = t.fy * h
            val r = (t.fr * w).coerceAtLeast(26f)
            val sel = i == selected
            // body: shine on top, dark below
            fill.shader = LinearGradient(0f, y - r, 0f, y + r,
                intArrayOf(Raden.alpha(Color.WHITE, 0x4D), Raden.alpha(Raden.NIGHT, 0x8C), Raden.alpha(Raden.NIGHT, 0xBF)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, fill)
            fill.shader = null
            // glowing outline
            ring.strokeWidth = 3f * k
            ring.color = if (sel) Color.WHITE else glow
            ring.setShadowLayer(16f * k, 0f, 0f, if (sel) Raden.CRIMSON else glow)
            c.drawCircle(x, y, r, ring)
            ring.clearShadowLayer()
            // label
            val label = if (t.code < 0) "?" else Prefs.buttonName(t.code)
            text.color = Color.WHITE
            text.letterSpacing = 0f
            text.textSize = minOf(16f * k, r * if (label.length > 3) 0.45f else 0.62f)
            text.setShadowLayer(6f * k, 0f, 0f, glow)
            c.drawText(label, x, y + text.textSize * 0.36f, text)
            text.clearShadowLayer()
        }
    }

    private fun stick(c: Canvas, w: Float, h: Float, k: Float, side: String, type: String, fx: Float, fy: Float, sel: Boolean) {
        val x = fx * w
        val y = fy * h
        val color = when (type) {
            "flick" -> Raden.ORANGE
            "joystick" -> tapGlow()
            "camera" -> Raden.PLATINUM
            else -> Raden.TEAL_SOFT
        }
        val r = when (type) {
            "flick" -> Prefs.getInt(ctx, Prefs.RADIUS, 50) / 1000f * w
            "joystick" -> Prefs.getInt(ctx, Prefs.JOY, 60) / 1000f * w
            "camera" -> w * 0.05f
            else -> 30f
        }.coerceAtLeast(30f)

        if (type == "flick") {
            // reach: dotted light line on both sides, a diamond at each end
            val reach = Prefs.getInt(ctx, Prefs.REACH, 18) / 100f * w
            dots.strokeWidth = 2f * k
            dots.color = color
            dots.pathEffect = DashPathEffect(floatArrayOf(0.1f, 10f * k), 0f)
            dots.setShadowLayer(4f * k, 0f, 0f, color)
            val gap = r + 4f * k
            val tip = 10f * k
            if (reach - tip > gap) {
                c.drawLine(x - reach + tip, y, x - gap, y, dots)
                c.drawLine(x + gap, y, x + reach - tip, y, dots)
            }
            dots.pathEffect = null
            dots.clearShadowLayer()
            fill.color = color
            fill.setShadowLayer(5f * k, 0f, 0f, color)
            diamond(c, x - reach + 2f * k, y, 8f * k)
            diamond(c, x + reach - 2f * k, y, 8f * k)
            fill.clearShadowLayer()
        }

        // dark core fading to a tinted edge
        fill.shader = RadialGradient(x, y, r,
            intArrayOf(Raden.alpha(Raden.NIGHT, 0x40), Raden.alpha(Raden.NIGHT, 0x99), Raden.alpha(color, 0x40)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        // glowing edge
        ring.strokeWidth = 2f * k
        ring.color = if (sel) Color.WHITE else color
        ring.setShadowLayer(14f * k, 0f, 0f, if (sel) Raden.CRIMSON else color)
        c.drawCircle(x, y, r, ring)
        ring.clearShadowLayer()
        // faint inner ring
        ring.strokeWidth = 1f * k
        ring.color = Raden.alpha(Raden.CREAM, 0x59)
        c.drawCircle(x, y, r * 0.64f, ring)
        if (type == "camera") {
            // little cross to show it's a swipe area
            ring.strokeWidth = 2f * k
            ring.color = color
            c.drawLine(x - r * 0.6f, y, x + r * 0.6f, y, ring)
            c.drawLine(x, y - r * 0.6f, x, y + r * 0.6f, ring)
        }

        // lit label chip underneath
        val label = "$side · ${type.uppercase()}"
        text.textSize = 11f * k
        text.letterSpacing = 0.18f
        val tw = text.measureText(label)
        val top = y + r + 7f * k
        rect.set(x - tw / 2 - 12f * k, top, x + tw / 2 + 12f * k, top + 22f * k)
        fill.color = color
        fill.setShadowLayer(12f * k, 0f, 0f, color)
        c.drawRoundRect(rect, 11f * k, 11f * k, fill)
        fill.clearShadowLayer()
        text.color = Raden.mix(color, Color.BLACK, 0.88f)
        c.drawText(label, x, rect.centerY() + text.textSize * 0.36f, text)
        text.letterSpacing = 0f
    }

    private fun diamond(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x - s, y); path.lineTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.close()
        c.drawPath(path, fill)
    }

    companion object {
        /** A bright, glowing version of the theme's main colour (Raden's teal comes out as sea glass). */
        fun tapGlow(): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(Raden.TEAL, hsv)
            hsv[1] = 0.55f
            hsv[2] = 0.85f
            return Color.HSVToColor(hsv)
        }
    }
}

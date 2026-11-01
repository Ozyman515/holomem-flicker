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
 * Other styles (main screen → Style, see [Looks]): Minimal, Neon, Bubble, Gamepad, Pixel.
 * Glow colours follow the member theme: taps use a bright version of the theme's main colour,
 * flicks use the theme's accent. Swipe points get a lit arrow from the circle to where the swipe
 * ends; when selected in an editor the arrow's end has a round handle to aim it.
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
    fun draw(c: Canvas, w: Float, h: Float, l: Prefs.Layout, selected: Int = Int.MIN_VALUE, look: String = Looks.current(ctx)) {
        val k = w / 1000f // the design was drawn on a 1000-wide screen
        stickAny(c, w, h, k, look, "L", l.ltype, l.lfx, l.lfy, selected == -1)
        stickAny(c, w, h, k, look, "R", l.rtype, l.rfx, l.rfy, selected == -2)
        l.taps.forEachIndexed { i, t -> if (Swipe.isSwipe(t)) arrow(c, w, h, k, t, i == selected, look) }
        l.taps.forEachIndexed { i, t ->
            val x = t.fx * w
            val y = t.fy * h
            val r = (t.fr * w).coerceAtLeast(26f)
            val label = if (t.code < 0) "?" else Prefs.buttonName(t.code)
            when (look) {
                Looks.MINIMAL -> tapMinimal(c, k, x, y, r, label, i == selected)
                Looks.NEON -> tapNeon(c, k, x, y, r, label, i == selected)
                Looks.BUBBLE -> tapBubble(c, k, x, y, r, label, i == selected)
                Looks.GAMEPAD -> tapGamepad(c, k, x, y, r, label, t.code, i == selected)
                Looks.PIXEL -> tapPixel(c, k, x, y, r, label, i == selected)
                else -> tapPenlight(c, k, x, y, r, label, i == selected)
            }
        }
    }

    private fun stickAny(c: Canvas, w: Float, h: Float, k: Float, look: String, side: String, type: String, fx: Float, fy: Float, sel: Boolean) {
        if (look == Looks.PENLIGHT || look !in Looks.ALL) return stick(c, w, h, k, side, type, fx, fy, sel)
        val x = fx * w
        val y = fy * h
        val r = stickRadius(type, w)
        val color = stickColor(type)
        if (type == "flick") reach(c, w, k, look, x, y, r, color)
        when (look) {
            Looks.MINIMAL -> {
                ring.strokeWidth = 1.5f * k
                ring.color = if (sel) Color.WHITE else Raden.alpha(color, 0xCC)
                c.drawCircle(x, y, r, ring)
                fill.color = Raden.alpha(color, 0xCC)
                c.drawCircle(x, y, 3f * k, fill)
                if (type == "camera") cross(c, k, x, y, r * 0.35f, color, 1.5f)
            }
            Looks.NEON -> {
                fill.color = Raden.alpha(Raden.NIGHT, 0x55)
                c.drawCircle(x, y, r, fill)
                ring.strokeWidth = 3f * k
                ring.color = if (sel) Color.WHITE else color
                ring.pathEffect = DashPathEffect(floatArrayOf(14f * k, 7f * k), 0f)
                ring.setShadowLayer(18f * k, 0f, 0f, if (sel) Raden.CRIMSON else color)
                c.drawCircle(x, y, r, ring)
                ring.pathEffect = null
                ring.strokeWidth = 2f * k
                ring.color = neonPartner(color)
                ring.setShadowLayer(12f * k, 0f, 0f, neonPartner(color))
                c.drawCircle(x, y, r * 0.55f, ring)
                ring.clearShadowLayer()
                if (type == "camera") cross(c, k, x, y, r * 0.4f, color, 2f)
            }
            Looks.BUBBLE -> {
                glass(c, k, x, y, r, color, sel)
                if (type == "camera") cross(c, k, x, y, r * 0.4f, Raden.alpha(Color.WHITE, 0xCC), 2f)
            }
            Looks.GAMEPAD -> {
                // thumbstick: dark well, grippy cap with a ridge ring
                fill.color = Raden.alpha(0xFF1A1A1E.toInt(), 0xB3)
                c.drawCircle(x, y, r, fill)
                ring.strokeWidth = 2f * k
                ring.color = if (sel) Color.WHITE else Raden.alpha(color, 0xE6)
                if (sel) ring.setShadowLayer(12f * k, 0f, 0f, Raden.CRIMSON)
                c.drawCircle(x, y, r, ring)
                ring.clearShadowLayer()
                val cap = r * 0.5f
                fill.shader = RadialGradient(x - cap * 0.3f, y - cap * 0.35f, cap * 1.3f,
                    intArrayOf(0xFF6A6A72.toInt(), 0xFF2E2E34.toInt()), null, Shader.TileMode.CLAMP)
                fill.setShadowLayer(6f * k, 0f, 3f * k, Raden.alpha(Color.BLACK, 0x99))
                c.drawCircle(x, y, cap, fill)
                fill.clearShadowLayer()
                fill.shader = null
                ring.strokeWidth = 1.5f * k
                ring.color = Raden.alpha(Color.WHITE, 0x40)
                c.drawCircle(x, y, cap * 0.72f, ring)
                if (type == "camera") cross(c, k, x, y, cap * 0.45f, color, 2f)
            }
            Looks.PIXEL -> {
                val px = pixelSize(k)
                pixelRing(c, x, y, r, px, if (sel) Color.WHITE else color)
                pixelRing(c, x, y, r * 0.55f, px, Raden.alpha(color, 0x80))
                if (type == "camera") {
                    fill.color = color
                    c.drawRect(x - r * 0.35f, y - px / 2, x + r * 0.35f, y + px / 2, fill)
                    c.drawRect(x - px / 2, y - r * 0.35f, x + px / 2, y + r * 0.35f, fill)
                }
            }
        }
        chip(c, k, look, "$side · ${type.uppercase()}", x, y + r + 7f * k, color)
    }

    private fun stickRadius(type: String, w: Float): Float = when (type) {
        "flick" -> Prefs.getInt(ctx, Prefs.RADIUS, 50) / 1000f * w
        "joystick" -> Prefs.getInt(ctx, Prefs.JOY, 60) / 1000f * w
        "camera" -> w * 0.05f
        else -> 30f
    }.coerceAtLeast(30f)

    private fun stickColor(type: String): Int = when (type) {
        "flick" -> Raden.ORANGE
        "joystick" -> tapGlow()
        "camera" -> Raden.PLATINUM
        else -> Raden.TEAL_SOFT
    }

    /** Flick reach (how far sideways a flick can start), in each look's own line style. */
    private fun reach(c: Canvas, w: Float, k: Float, look: String, x: Float, y: Float, r: Float, color: Int) {
        val reach = Prefs.getInt(ctx, Prefs.REACH, 18) / 100f * w
        val gap = r + 4f * k
        if (reach <= gap) return
        when (look) {
            Looks.MINIMAL -> {
                dots.strokeWidth = 1.5f * k
                dots.color = Raden.alpha(color, 0x99)
                c.drawLine(x - reach, y, x - gap, y, dots)
                c.drawLine(x + gap, y, x + reach, y, dots)
                c.drawLine(x - reach, y - 6f * k, x - reach, y + 6f * k, dots)
                c.drawLine(x + reach, y - 6f * k, x + reach, y + 6f * k, dots)
            }
            Looks.NEON -> {
                dots.strokeWidth = 2.5f * k
                dots.color = color
                dots.setShadowLayer(10f * k, 0f, 0f, color)
                c.drawLine(x - reach, y, x - gap, y, dots)
                c.drawLine(x + gap, y, x + reach, y, dots)
                dots.clearShadowLayer()
                fill.color = neonPartner(color)
                fill.setShadowLayer(8f * k, 0f, 0f, neonPartner(color))
                c.drawCircle(x - reach, y, 4f * k, fill)
                c.drawCircle(x + reach, y, 4f * k, fill)
                fill.clearShadowLayer()
            }
            Looks.BUBBLE -> {
                // a string of little bubbles
                var d = gap + 8f * k
                while (d < reach) {
                    val rr = 3.5f * k
                    fill.color = Raden.alpha(color, 0x99)
                    c.drawCircle(x - d, y, rr, fill)
                    c.drawCircle(x + d, y, rr, fill)
                    d += 14f * k
                }
                glass(c, k, x - reach, y, 7f * k, color, false)
                glass(c, k, x + reach, y, 7f * k, color, false)
            }
            Looks.GAMEPAD -> {
                // chevrons pointing outward
                dots.strokeWidth = 3f * k
                dots.color = Raden.alpha(color, 0xCC)
                var d = gap + 10f * k
                while (d < reach) {
                    chevron(c, x + d, y, 6f * k, 1f)
                    chevron(c, x - d, y, 6f * k, -1f)
                    d += 18f * k
                }
            }
            Looks.PIXEL -> {
                val px = pixelSize(k)
                fill.color = color
                var d = gap + px
                while (d < reach) {
                    c.drawRect(x + d, y - px / 2, x + d + px, y + px / 2, fill)
                    c.drawRect(x - d - px, y - px / 2, x - d, y + px / 2, fill)
                    d += px * 2.5f
                }
                c.drawRect(x + reach - px * 1.5f, y - px * 1.5f, x + reach + px * 1.5f, y + px * 1.5f, fill)
                c.drawRect(x - reach - px * 1.5f, y - px * 1.5f, x - reach + px * 1.5f, y + px * 1.5f, fill)
            }
        }
    }

    private fun chevron(c: Canvas, x: Float, y: Float, s: Float, dir: Float) {
        c.drawLine(x - s * dir, y - s, x, y, dots)
        c.drawLine(x, y, x - s * dir, y + s, dots)
    }

    /** Stick name tag under the circle, per look. */
    private fun chip(c: Canvas, k: Float, look: String, label: String, x: Float, top: Float, color: Int) {
        when (look) {
            Looks.MINIMAL -> {
                text.textSize = 10f * k
                text.letterSpacing = 0.12f
                text.color = Raden.alpha(color, 0xCC)
                c.drawText(label, x, top + 12f * k, text)
            }
            Looks.NEON -> {
                text.textSize = 11f * k
                text.letterSpacing = 0.2f
                text.color = Color.WHITE
                text.setShadowLayer(8f * k, 0f, 0f, color)
                c.drawText(label, x, top + 14f * k, text)
                text.clearShadowLayer()
            }
            Looks.BUBBLE -> {
                text.textSize = 11f * k
                text.letterSpacing = 0.1f
                val tw = text.measureText(label)
                rect.set(x - tw / 2 - 12f * k, top, x + tw / 2 + 12f * k, top + 22f * k)
                fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                    Raden.alpha(Color.WHITE, 0x99), Raden.alpha(color, 0x99), Shader.TileMode.CLAMP)
                c.drawRoundRect(rect, 11f * k, 11f * k, fill)
                fill.shader = null
                text.color = Raden.NIGHT
                c.drawText(label, x, rect.centerY() + text.textSize * 0.36f, text)
            }
            Looks.GAMEPAD -> {
                text.textSize = 10f * k
                text.letterSpacing = 0.15f
                val tw = text.measureText(label)
                rect.set(x - tw / 2 - 10f * k, top, x + tw / 2 + 10f * k, top + 20f * k)
                fill.color = Raden.alpha(0xFF1A1A1E.toInt(), 0xD9)
                c.drawRoundRect(rect, 5f * k, 5f * k, fill)
                ring.strokeWidth = 1.5f * k
                ring.color = Raden.alpha(color, 0xCC)
                c.drawRoundRect(rect, 5f * k, 5f * k, ring)
                text.color = Color.WHITE
                c.drawText(label, x, rect.centerY() + text.textSize * 0.36f, text)
            }
            Looks.PIXEL -> {
                text.typeface = android.graphics.Typeface.MONOSPACE
                text.textSize = 11f * k
                text.letterSpacing = 0f
                val tw = text.measureText(label)
                rect.set(x - tw / 2 - 8f * k, top, x + tw / 2 + 8f * k, top + 20f * k)
                fill.color = color
                c.drawRect(rect, fill)
                fill.color = Raden.NIGHT
                c.drawRect(rect.left + 2f * k, rect.top + 2f * k, rect.right - 2f * k, rect.bottom - 2f * k, fill)
                text.color = color
                c.drawText(label, x, rect.centerY() + text.textSize * 0.36f, text)
                text.typeface = android.graphics.Typeface.DEFAULT
            }
        }
        text.letterSpacing = 0f
    }

    // ---------- tap points, one function per look ----------

    private fun drawLabel(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, color: Int, glow: Int, scale: Float = 1f) {
        text.color = color
        text.letterSpacing = 0f
        text.textSize = minOf(16f * k * scale, r * if (label.length > 3) 0.45f else 0.62f)
        if (glow != 0) text.setShadowLayer(6f * k, 0f, 0f, glow)
        c.drawText(label, x, y + text.textSize * 0.36f, text)
        text.clearShadowLayer()
    }

    /** Penlight (the original): lit round button, shine on top, glowing outline. */
    private fun tapPenlight(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, sel: Boolean) {
        val glow = tapGlow()
        fill.shader = LinearGradient(0f, y - r, 0f, y + r,
            intArrayOf(Raden.alpha(Color.WHITE, 0x4D), Raden.alpha(Raden.NIGHT, 0x8C), Raden.alpha(Raden.NIGHT, 0xBF)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        ring.strokeWidth = 3f * k
        ring.color = if (sel) Color.WHITE else glow
        ring.setShadowLayer(16f * k, 0f, 0f, if (sel) Raden.CRIMSON else glow)
        c.drawCircle(x, y, r, ring)
        ring.clearShadowLayer()
        drawLabel(c, k, x, y, r, label, Color.WHITE, glow)
    }

    /** Minimal: a thin outline and the button name - hides as little of the game as possible. */
    private fun tapMinimal(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, sel: Boolean) {
        val col = tapGlow()
        ring.strokeWidth = (if (sel) 2.5f else 1.5f) * k
        ring.color = if (sel) Color.WHITE else Raden.alpha(col, 0xCC)
        c.drawCircle(x, y, r, ring)
        drawLabel(c, k, x, y, r, label, if (sel) Color.WHITE else Raden.alpha(col, 0xE6), 0, 0.85f)
    }

    /** Neon: hollow double ring in the theme's two colours, strong glow, glowing name. */
    private fun tapNeon(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, sel: Boolean) {
        val a = tapGlow()
        val b = neonPartner(a)
        fill.color = Raden.alpha(Raden.NIGHT, 0x4D)
        c.drawCircle(x, y, r, fill)
        ring.strokeWidth = 3f * k
        ring.color = if (sel) Color.WHITE else a
        ring.setShadowLayer(20f * k, 0f, 0f, if (sel) Raden.CRIMSON else a)
        c.drawCircle(x, y, r, ring)
        ring.strokeWidth = 1.5f * k
        ring.color = b
        ring.setShadowLayer(10f * k, 0f, 0f, b)
        c.drawCircle(x, y, r * 0.78f, ring)
        ring.clearShadowLayer()
        drawLabel(c, k, x, y, r, label, Color.WHITE, a)
    }

    /** Bubble: glossy see-through bubble with a white shine, like the theme picker. */
    private fun tapBubble(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, sel: Boolean) {
        glass(c, k, x, y, r, tapGlow(), sel)
        drawLabel(c, k, x, y, r, label, Raden.mix(Raden.NIGHT, Color.BLACK, 0.3f), Raden.alpha(Color.WHITE, 0xCC))
    }

    /** Gamepad: solid face buttons in controller colours (A green, B red, X blue, Y yellow), raised with a shadow. */
    private fun tapGamepad(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, code: Int, sel: Boolean) {
        val base = when (code) {
            0x130 -> 0xFF3BB54A.toInt() // A
            0x131 -> 0xFFE53935.toInt() // B
            0x133 -> 0xFF2F6FE0.toInt() // X
            0x134 -> 0xFFF2B41C.toInt() // Y
            else -> Raden.TEAL_SOFT
        }
        // shadow / side of the button
        fill.color = Raden.alpha(Raden.mix(base, Color.BLACK, 0.55f), 0xE6)
        c.drawCircle(x, y + 3f * k, r, fill)
        // face
        fill.shader = RadialGradient(x - r * 0.3f, y - r * 0.4f, r * 1.4f,
            intArrayOf(Raden.mix(base, Color.WHITE, 0.25f), base, Raden.mix(base, Color.BLACK, 0.25f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        ring.strokeWidth = (if (sel) 3f else 1.5f) * k
        ring.color = if (sel) Color.WHITE else Raden.alpha(Color.BLACK, 0x59)
        if (sel) ring.setShadowLayer(14f * k, 0f, 0f, Raden.CRIMSON)
        c.drawCircle(x, y, r, ring)
        ring.clearShadowLayer()
        drawLabel(c, k, x, y, r, label, Color.WHITE, Raden.alpha(Color.BLACK, 0x99))
    }

    /** Pixel: chunky 8-bit button - a blocky ring, flat fill, monospace name. */
    private fun tapPixel(c: Canvas, k: Float, x: Float, y: Float, r: Float, label: String, sel: Boolean) {
        val col = tapGlow()
        val px = pixelSize(k)
        fill.color = Raden.alpha(Raden.NIGHT, 0xA6)
        pixelDisc(c, x, y, r, px)
        pixelRing(c, x, y, r, px, if (sel) Color.WHITE else col)
        text.typeface = android.graphics.Typeface.MONOSPACE
        drawLabel(c, k, x, y, r, label, if (sel) Color.WHITE else col, 0)
        text.typeface = android.graphics.Typeface.DEFAULT
    }

    // ---------- shared bits for the looks ----------

    /** Glossy glass bubble (Bubble look). */
    private fun glass(c: Canvas, k: Float, x: Float, y: Float, r: Float, color: Int, sel: Boolean) {
        fill.shader = RadialGradient(x, y + r * 0.25f, r * 1.1f,
            intArrayOf(Raden.alpha(color, 0x40), Raden.alpha(color, 0x8C), Raden.alpha(Raden.mix(color, Color.WHITE, 0.3f), 0xCC)),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        // shine: soft white oval near the top-left
        rect.set(x - r * 0.62f, y - r * 0.82f, x + r * 0.18f, y - r * 0.28f)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Raden.alpha(Color.WHITE, 0xCC), Raden.alpha(Color.WHITE, 0x10), Shader.TileMode.CLAMP)
        c.drawOval(rect, fill)
        fill.shader = null
        ring.strokeWidth = (if (sel) 3f else 1.5f) * k
        ring.color = if (sel) Color.WHITE else Raden.alpha(Color.WHITE, 0x99)
        if (sel) ring.setShadowLayer(14f * k, 0f, 0f, Raden.CRIMSON)
        c.drawCircle(x, y, r, ring)
        ring.clearShadowLayer()
    }

    private fun cross(c: Canvas, k: Float, x: Float, y: Float, s: Float, color: Int, wPx: Float) {
        ring.strokeWidth = wPx * k
        ring.color = color
        c.drawLine(x - s, y, x + s, y, ring)
        c.drawLine(x, y - s, x, y + s, ring)
    }

    /** The second neon colour: the theme accent, or the main glow when the accent is too close to it. */
    private fun neonPartner(a: Int): Int = if (a == Raden.ORANGE) tapGlow() else Raden.mix(Raden.ORANGE, Color.WHITE, 0.15f)

    private fun pixelSize(k: Float) = (4f * k).coerceAtLeast(3f)

    /** A circle outline drawn as square blocks on a [px] grid. */
    private fun pixelRing(c: Canvas, x: Float, y: Float, r: Float, px: Float, color: Int) {
        fill.color = color
        val n = Math.ceil((r / px).toDouble()).toInt()
        for (i in -n..n) for (j in -n..n) {
            val d = Math.hypot(i.toDouble(), j.toDouble()) * px
            if (d <= r && d > r - px * 1.2) c.drawRect(x + i * px - px / 2, y + j * px - px / 2, x + i * px + px / 2, y + j * px + px / 2, fill)
        }
    }

    /** A filled circle drawn as square blocks (uses the current fill colour). */
    private fun pixelDisc(c: Canvas, x: Float, y: Float, r: Float, px: Float) {
        val n = Math.ceil((r / px).toDouble()).toInt()
        for (j in -n..n) {
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            for (i in -n..n) if (Math.hypot(i.toDouble(), j.toDouble()) * px <= r.toDouble()) { lo = minOf(lo, i); hi = maxOf(hi, i) }
            if (lo <= hi) c.drawRect(x + lo * px - px / 2, y + j * px - px / 2, x + hi * px + px / 2, y + j * px + px / 2, fill)
        }
    }

    /** Swipe arrow: dotted lit line from the circle's edge to the end, an arrowhead, and a handle when selected. */
    private fun arrow(c: Canvas, w: Float, h: Float, k: Float, t: Prefs.TapPoint, sel: Boolean, look: String) {
        val x0 = t.fx * w
        val y0 = t.fy * h
        val x1 = x0 + t.sdx * w
        val y1 = y0 + t.sdy * h
        val len = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (len < 1f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val color = when (look) {
            Looks.MINIMAL, Looks.PIXEL -> tapGlow()
            Looks.GAMEPAD -> Color.WHITE
            else -> Raden.ORANGE
        }
        val r = (t.fr * w).coerceAtLeast(26f)
        val head = 10f * k          // arrowhead length
        val wing = head * 0.55f     // arrowhead half-width
        // shaft: solid lit line from the circle's edge (or its centre, for a short swipe) to the arrowhead
        val from = if (len > r + head * 1.5f) r else 0f
        dots.strokeWidth = (if (look == Looks.MINIMAL) 2f else 3f) * k
        dots.color = color
        if (look != Looks.MINIMAL && look != Looks.PIXEL) dots.setShadowLayer(5f * k, 0f, 0f, color)
        c.drawLine(x0 + ux * from, y0 + uy * from, x1 - ux * head, y1 - uy * head, dots)
        dots.clearShadowLayer()
        // slim arrowhead
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x1 - ux * head - uy * wing, y1 - uy * head + ux * wing)
        path.lineTo(x1 - ux * head + uy * wing, y1 - uy * head - ux * wing)
        path.close()
        fill.color = color
        fill.setShadowLayer(6f * k, 0f, 0f, color)
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        if (sel) {
            // handle to aim the swipe (hold it, then drag)
            ring.strokeWidth = 3f * k
            ring.color = Color.WHITE
            ring.setShadowLayer(12f * k, 0f, 0f, Raden.CRIMSON)
            c.drawCircle(x1, y1, HANDLE_R * k, ring)
            ring.clearShadowLayer()
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
        /** Radius of a selected swipe's aiming handle, on the 1000-wide design scale. */
        const val HANDLE_R = 12f

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

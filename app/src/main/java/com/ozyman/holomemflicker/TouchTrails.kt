package com.ozyman.holomemflicker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray

/**
 * The flicker's own touches, as the overlay shows them: a dot that follows each finger,
 * its swipe trail, and a ring that grows out wherever a tap lands. Everything fades once
 * the finger lifts. Fed from the engine's "trace" log: [seq, stroke, x, y, kind 0 down / 1 move / 2 up].
 */
class TouchTrails {
    private class Stroke(val pts: ArrayList<FloatArray> = ArrayList(), var downAt: Long = 0, var upAt: Long = 0)

    private val strokes = LinkedHashMap<Int, Stroke>()
    private var seq = 0
    private var sync = true // first read after the overlay turns on: skip touches that already ended

    /** Which log entries to ask the engine for next: only newer ones, or the whole log (-1) after a reset. */
    fun since(): Int = if (sync) -1 else seq

    fun read(tr: JSONArray?, now: Long) {
        if (tr == null) { strokes.clear(); sync = true; return }
        if (tr.length() > 0 && tr.getJSONArray(tr.length() - 1).getInt(0) < seq) sync = true // engine restarted
        if (sync) { seq = 0; strokes.clear() }
        for (i in 0 until tr.length()) {
            val e = tr.getJSONArray(i)
            val s = e.getInt(0)
            if (s <= seq) continue
            seq = s
            val id = e.getInt(1)
            val x = e.getDouble(2).toFloat()
            val y = e.getDouble(3).toFloat()
            when (e.getInt(4)) {
                0 -> strokes[id] = Stroke(downAt = now).also { it.pts.add(floatArrayOf(x, y)) }
                1 -> strokes[id]?.pts?.add(floatArrayOf(x, y))
                2 -> strokes[id]?.let { it.pts.add(floatArrayOf(x, y)); it.upAt = now }
            }
        }
        if (sync) { strokes.entries.removeAll { it.value.upAt > 0 }; sync = false }
        strokes.entries.removeAll { it.value.upAt > 0 && now - it.value.upAt > FADE_MS }
        while (strokes.size > 40) strokes.remove(strokes.keys.first())
    }

    /** For tests: set a stroke's timing directly. */
    fun age(id: Int, downAgo: Long, upAgo: Long, now: Long) {
        strokes[id]?.let { it.downAt = now - downAgo; it.upAt = if (upAgo < 0) 0 else now - upAgo }
    }

    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Draws every live / fading touch, shifted by (ox, oy). Returns true while something is still animating. */
    fun draw(c: Canvas, ox: Float, oy: Float, d: Float, now: Long): Boolean {
        var animating = false
        for (s in strokes.values) {
            val up = s.upAt > 0
            val f = if (up) (1f - (now - s.upAt) / FADE_MS.toFloat()).coerceIn(0f, 1f) else 1f
            if (f <= 0f) continue
            val a = (255 * f).toInt()
            if (up || now - s.downAt < RING_MS) animating = true
            if (s.pts.size > 1) {
                path.reset()
                path.moveTo(s.pts[0][0] + ox, s.pts[0][1] + oy)
                for (i in 1 until s.pts.size) path.lineTo(s.pts[i][0] + ox, s.pts[i][1] + oy)
                trail.strokeWidth = 7f * d
                trail.color = Raden.alpha(Raden.ORANGE, a)
                c.drawPath(path, trail)
            }
            val p0 = s.pts[0]
            val pl = s.pts.last()
            val age = (now - s.downAt).coerceIn(0L, RING_MS) / RING_MS.toFloat()
            trail.strokeWidth = 3f * d
            trail.color = Raden.alpha(Raden.CREAM, (a * (1f - age * 0.85f)).toInt())
            c.drawCircle(p0[0] + ox, p0[1] + oy, (14f + 26f * age) * d, trail)
            dot.color = Raden.alpha(if (up) Raden.ORANGE else Raden.CRIMSON, a)
            c.drawCircle(pl[0] + ox, pl[1] + oy, 13f * d, dot)
            dot.color = Raden.alpha(Raden.CREAM, a)
            c.drawCircle(pl[0] + ox, pl[1] + oy, 5f * d, dot)
        }
        return animating
    }

    companion object {
        const val FADE_MS = 1200L
        const val RING_MS = 450L
    }
}

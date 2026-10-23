package com.ozyman.holomemflicker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout

/**
 * On-screen d-pad for the layout editors: the precision option.
 * Each tap moves the selected point / stick [STEP_PX] pixels; holding an arrow repeats.
 * The middle button flips the pad to the other bottom corner, so it never hides a marker for long.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class NudgePad(ctx: Context, private val onNudge: (dxPx: Int, dyPx: Int) -> Unit) : GridLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null
    private var onLeft = false

    init {
        rowCount = 3
        columnCount = 3
        val size = (56 * resources.displayMetrics.density).toInt()
        fun cell(row: Int, col: Int, label: String, setup: (Button) -> Unit) {
            val b = Button(ctx).apply {
                text = label
                textSize = 20f
                setPadding(0, 0, 0, 0)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                Raden.pill(this, if (label == "⇄") Raden.ORANGE else Raden.TEAL, radius = 200f)
                alpha = 0.92f
            }
            setup(b)
            addView(b, LayoutParams(spec(row), spec(col)).apply {
                width = size; height = size
                setMargins(4, 4, 4, 4)
            })
        }
        fun arrow(row: Int, col: Int, label: String, dx: Int, dy: Int) = cell(row, col, label) { b ->
            b.contentDescription = "Nudge " + when { dx < 0 -> "left"; dx > 0 -> "right"; dy < 0 -> "up"; else -> "down" }
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        onNudge(dx * STEP_PX, dy * STEP_PX)
                        val r = object : Runnable {
                            override fun run() {
                                onNudge(dx * STEP_PX, dy * STEP_PX)
                                handler.postDelayed(this, REPEAT_MS)
                            }
                        }
                        repeat = r
                        handler.postDelayed(r, HOLD_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stop()
                    }
                }
                true
            }
        }
        arrow(0, 1, "▲", 0, -1)
        arrow(1, 0, "◀", -1, 0)
        cell(1, 1, "⇄") { b ->
            b.contentDescription = "Move the d-pad to the other corner"
            b.setOnClickListener { flip() }
        }
        arrow(1, 2, "▶", 1, 0)
        arrow(2, 1, "▼", 0, 1)
    }

    private fun stop() {
        repeat?.let { handler.removeCallbacks(it) }
        repeat = null
    }

    /** Layout params that pin the pad to a bottom corner of a FrameLayout. */
    fun cornerParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or if (onLeft) Gravity.START else Gravity.END).apply {
            setMargins(24, 24, 24, 24)
        }

    private fun flip() {
        onLeft = !onLeft
        layoutParams = cornerParams()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Pixels per tap. */
        const val STEP_PX = 2
        const val HOLD_MS = 400L
        const val REPEAT_MS = 50L

        /**
         * Size −/+ step for a tap circle. Up to v19 one tap was ×1.2; now it's half that step
         * (√1.2 ≈ ×1.095), so two taps land exactly where one tap used to (1 → 3 → 5 instead of 1 → 5).
         */
        val SIZE_STEP = Math.sqrt(1.2).toFloat()
        const val MIN_FR = 0.008f
        const val MAX_FR = 0.15f

        /** New circle radius (share of screen width) after one Size tap in direction [dir]. */
        fun resized(fr: Float, dir: Int): Float =
            (fr * if (dir > 0) SIZE_STEP else 1 / SIZE_STEP).coerceIn(MIN_FR, MAX_FR)

        /**
         * Moves the selected marker of [d] by (dxPx, dyPx) on a [w]×[h] board.
         * [sel]: tap index, -1 left stick, -2 right stick. Returns a short position read-out, or null if nothing is selected.
         */
        fun apply(d: Prefs.Layout, sel: Int, dxPx: Int, dyPx: Int, w: Int, h: Int): String? {
            if (w <= 0 || h <= 0) return null
            val ddx = dxPx.toFloat() / w
            val ddy = dyPx.toFloat() / h
            fun c(v: Float) = v.coerceIn(0f, 1f)
            val (name, x, y) = when (sel) {
                -1 -> { d.lfx = c(d.lfx + ddx); d.lfy = c(d.lfy + ddy); Triple("Left stick", d.lfx, d.lfy) }
                -2 -> { d.rfx = c(d.rfx + ddx); d.rfy = c(d.rfy + ddy); Triple("Right stick", d.rfx, d.rfy) }
                else -> {
                    val t = d.taps.getOrNull(sel) ?: return null
                    t.fx = c(t.fx + ddx); t.fy = c(t.fy + ddy)
                    Triple(Prefs.buttonName(t.code), t.fx, t.fy)
                }
            }
            return "$name at x %d, y %d px".format(Math.round(x * w), Math.round(y * h))
        }
    }
}

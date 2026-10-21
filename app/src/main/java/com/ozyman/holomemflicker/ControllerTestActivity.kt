package com.ozyman.holomemflicker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Controller test: a Kishi-style drawing that lights up as buttons/sticks move.
 *  - Cyan  = what the FLICKER engine sees (the thing that matters in-game)
 *  - Orange = what Android itself sees (works even with the flicker stopped)
 * Touches the flicker injects land on this screen and are drawn as dots + trails,
 * so you can watch taps and flicks happen without opening the game.
 */
class ControllerTestActivity : Activity() {

    private var service: IFlickService? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pad: Pad

    // engine state
    private var eKeys = HashSet<Int>()
    private var eAxes = HashMap<Int, Float>()
    private var eRx = 2
    private var eRy = 5
    private var eDevice = ""
    private var engineOk = false

    // android state
    private val aKeys = HashSet<Int>()
    private val aAxes = HashMap<String, Float>()

    // touches that land on this screen
    private class Trail(val pts: ArrayList<FloatArray> = ArrayList(), var down: Boolean = true, var upAt: Long = 0)
    private val trails = HashMap<Int, Trail>()
    private val oldTrails = ArrayList<Trail>()
    private var touchCount = 0

    // Kishi V3 Pro extra buttons: tap one on the drawing, then press it, to learn its code
    private var learningKey: String? = null

    private fun bindExtra(code: Int) {
        val k = learningKey ?: return
        Prefs.setExtraCode(this, k, code)
        learningKey = null
        pad.invalidate()
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, FlickService::class.java.name))
            .daemon(true)
            .processNameSuffix("engine")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IFlickService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = service
            var ok = false
            if (s != null) {
                try {
                    val o = JSONObject(s.inputState())
                    val keys = o.optJSONArray("keys")
                    if (keys != null) {
                        ok = true
                        val k = HashSet<Int>()
                        for (i in 0 until keys.length()) k.add(keys.getInt(i))
                        (k - eKeys).firstOrNull()?.let { if (learningKey != null) bindExtra(it) }
                        eKeys = k
                        val ax = HashMap<Int, Float>()
                        val axes = o.optJSONObject("axes")
                        if (axes != null) for (key in axes.keys()) ax[key.toInt()] = axes.getDouble(key).toFloat()
                        eAxes = ax
                        eRx = o.optInt("rx", 2)
                        eRy = o.optInt("ry", 5)
                        eDevice = o.optString("device")
                    }
                } catch (_: Throwable) {
                }
            }
            engineOk = ok
            pad.invalidate()
            handler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this)
        pad = Pad(this)
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        root.addView(Button(this).apply {
            text = "✕  Close"
            Raden.pill(this, Raden.CRIMSON)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })
        setContentView(root)
        try {
            if (Shizuku.pingBinder()) Shizuku.bindUserService(args, conn)
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't kill the engine: it's a daemon shared with the main screen.
        try { Shizuku.unbindUserService(args, conn, false) } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- Android-side input (orange) ----
    private fun isPad(e: KeyEvent) =
        (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(e.keyCode)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (learningKey != null && event.scanCode > 0 && event.repeatCount == 0) {
            bindExtra(event.scanCode)
            return true
        }
        if (isPad(event)) {
            aKeys.add(event.scanCode)
            pad.invalidate()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isPad(event)) {
            aKeys.remove(event.scanCode)
            pad.invalidate()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            aAxes["lx"] = e.getAxisValue(MotionEvent.AXIS_X)
            aAxes["ly"] = e.getAxisValue(MotionEvent.AXIS_Y)
            aAxes["rx"] = e.getAxisValue(MotionEvent.AXIS_Z)
            aAxes["ry"] = e.getAxisValue(MotionEvent.AXIS_RZ)
            aAxes["hx"] = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            aAxes["hy"] = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            aAxes["lt"] = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
            aAxes["rt"] = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            pad.invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ---- engine helpers ----
    private fun ek(code: Int) = engineOk && eKeys.contains(code)
    private fun ea(code: Int) = if (engineOk) eAxes[code] ?: 0f else 0f
    private fun ak(code: Int) = aKeys.contains(code)
    private fun aa(name: String) = aAxes[name] ?: 0f

    /** Trigger 0..1 from the engine: GAS/BRAKE if present, else Z/RZ when sticks use RX/RY. */
    private fun eTrig(right: Boolean): Float {
        if (!engineOk) return 0f
        val code = when {
            eAxes.containsKey(10) && eAxes.containsKey(9) -> if (right) 9 else 10
            eRx == 3 -> if (right) 5 else 2
            else -> return 0f
        }
        return ((eAxes[code] ?: -1f) + 1f) / 2f
    }

    private inner class Pad(ctx: Context) : View(ctx) {
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Raden.NIGHT_PANEL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Raden.TEAL_SOFT
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Raden.PLATINUM
            textAlign = Paint.Align.LEFT
            typeface = android.graphics.Typeface.MONOSPACE
        }
        private val cyan = Raden.SEA_GLASS
        private val orange = Raden.ORANGE

        private fun stateColor(e: Boolean, a: Boolean): Int? = when {
            e -> cyan
            a -> orange
            else -> null
        }

        private fun button(c: Canvas, label: String, x: Float, y: Float, r: Float, code: Int) {
            val col = stateColor(ek(code), ak(code))
            fill.color = col ?: Raden.TEAL
            c.drawCircle(x, y, r, fill)
            c.drawCircle(x, y, r, line)
            txt.textSize = r * 0.8f
            txt.color = if (col != null) Raden.INK else Raden.CREAM
            c.drawText(label, x, y + r * 0.28f, txt)
        }

        private fun pill(c: Canvas, label: String, rect: RectF, code: Int, analog: Float = 0f) {
            val col = stateColor(ek(code), ak(code))
            fill.color = Raden.TEAL
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
            if (analog > 0.02f) {
                fill.color = cyan
                val r2 = RectF(rect.left, rect.top, rect.left + rect.width() * analog.coerceIn(0f, 1f), rect.bottom)
                c.drawRoundRect(r2, rect.height() / 2, rect.height() / 2, fill)
            }
            if (col != null) {
                fill.color = col
                c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
            }
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, line)
            txt.textSize = rect.height() * 0.55f
            txt.color = if (col != null) Raden.INK else Raden.CREAM
            c.drawText(label, rect.centerX(), rect.centerY() + rect.height() * 0.2f, txt)
        }

        val extraRects = LinkedHashMap<String, RectF>()
        private val dash = DashPathEffect(floatArrayOf(14f, 10f), 0f)

        /** An extra Kishi button (L4/R4/M1/M2/Share/Nexus): lights up with whatever code it was taught. */
        private fun extra(c: Canvas, label: String, rect: RectF, rear: Boolean = false) {
            extraRects[label] = rect
            val code = Prefs.extraCode(context, label)
            val col = if (code >= 0) stateColor(ek(code), ak(code)) else null
            val rad = minOf(rect.width(), rect.height()) / 2
            fill.color = col ?: Raden.TEAL_DEEP
            c.drawRoundRect(rect, rad, rad, fill)
            val learning = learningKey == label
            line.color = when {
                learning -> Raden.CRIMSON
                code >= 0 -> Raden.ORANGE
                else -> Raden.PLATINUM
            }
            line.strokeWidth = if (learning) 9f else 4f
            if (rear || code < 0) line.pathEffect = dash
            c.drawRoundRect(rect, rad, rad, line)
            line.pathEffect = null
            line.strokeWidth = 4f
            line.color = Raden.TEAL_SOFT
            txt.textSize = minOf(rect.height(), rect.width()) * 0.42f
            txt.color = if (col != null) Raden.INK else Raden.CREAM
            c.drawText(label, rect.centerX(), rect.centerY() + txt.textSize * 0.35f, txt)
            small.textAlign = Paint.Align.CENTER
            small.textSize = height * 0.019f
            small.color = if (learning) Raden.ORANGE else Raden.PLATINUM
            val sub = when {
                learning -> "press it…"
                code >= 0 -> "= " + if (code in 0x130..0x13e) Prefs.buttonName(code) else "0x%03x".format(code)
                else -> "tap to test"
            }
            c.drawText(sub, rect.centerX(), rect.bottom + small.textSize * 1.25f, small)
            small.textAlign = Paint.Align.LEFT
        }

        private fun stick(c: Canvas, label: String, x: Float, y: Float, r: Float,
                          ex: Float, ey: Float, ax: Float, ay: Float, clickCode: Int) {
            val click = stateColor(ek(clickCode), ak(clickCode))
            fill.color = Raden.NIGHT
            c.drawCircle(x, y, r, fill)
            line.color = click ?: Raden.TEAL_SOFT
            line.strokeWidth = if (click != null) 10f else 4f
            c.drawCircle(x, y, r, line)
            line.strokeWidth = 4f
            line.color = Raden.TEAL_SOFT
            // threshold ring
            val dead = Prefs.getInt(context, Prefs.DEAD, 35) / 100f
            fill.color = Raden.alpha(Raden.PLATINUM, 0x2A)
            c.drawCircle(x, y, r * dead, fill)
            // android dot (orange), engine dot (cyan)
            if (ax != 0f || ay != 0f) {
                fill.color = orange
                c.drawCircle(x + ax * r * 0.8f, y + ay * r * 0.8f, r * 0.22f, fill)
            }
            fill.color = if (engineOk) cyan else Raden.INK_SOFT
            c.drawCircle(x + ex * r * 0.8f, y + ey * r * 0.8f, r * 0.28f, fill)
            txt.textSize = r * 0.28f
            txt.color = Raden.CREAM
            c.drawText(label, x, y + r + r * 0.4f, txt)
            small.textSize = r * 0.2f
            small.textAlign = Paint.Align.CENTER
            c.drawText("x %+.2f  y %+.2f".format(ex, ey), x, y + r + r * 0.7f, small)
            small.textAlign = Paint.Align.LEFT
        }

        private fun dpad(c: Canvas, x: Float, y: Float, s: Float) {
            val hx = if (engineOk) ea(0x10) else aa("hx")
            val hy = if (engineOk) ea(0x11) else aa("hy")
            val dirs = listOf(
                Triple(0f, -1f, hy < -0.5f || ek(0x220) || ak(0x220)),
                Triple(0f, 1f, hy > 0.5f || ek(0x221) || ak(0x221)),
                Triple(-1f, 0f, hx < -0.5f || ek(0x222) || ak(0x222)),
                Triple(1f, 0f, hx > 0.5f || ek(0x223) || ak(0x223))
            )
            for ((dx, dy, on) in dirs) {
                val cx = x + dx * s
                val cy = y + dy * s
                fill.color = if (on) (if (engineOk) cyan else orange) else Raden.TEAL
                c.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, fill)
                c.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, line)
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(Raden.NIGHT)

            // grips + tablet bridge
            val lg = RectF(w * 0.02f, h * 0.22f, w * 0.30f, h * 0.96f)
            val rg = RectF(w * 0.70f, h * 0.22f, w * 0.98f, h * 0.96f)
            c.drawRoundRect(lg, h * 0.12f, h * 0.12f, body)
            c.drawRoundRect(rg, h * 0.12f, h * 0.12f, body)
            c.drawRoundRect(lg, h * 0.12f, h * 0.12f, line)
            c.drawRoundRect(rg, h * 0.12f, h * 0.12f, line)
            val tab = RectF(w * 0.30f, h * 0.26f, w * 0.70f, h * 0.92f)
            fill.color = 0xFF081815.toInt()
            c.drawRect(tab, fill)
            c.drawRect(tab, line)

            val u = h * 0.05f
            // shoulders
            pill(c, "L2", RectF(w * 0.05f, h * 0.03f, w * 0.17f, h * 0.10f), 0x138, eTrig(false).coerceAtLeast(aa("lt")))
            pill(c, "L1", RectF(w * 0.05f, h * 0.12f, w * 0.17f, h * 0.19f), 0x136)
            extra(c, "L4", RectF(w * 0.19f, h * 0.125f, w * 0.27f, h * 0.185f))
            pill(c, "R2", RectF(w * 0.83f, h * 0.03f, w * 0.95f, h * 0.10f), 0x139, eTrig(true).coerceAtLeast(aa("rt")))
            pill(c, "R1", RectF(w * 0.83f, h * 0.12f, w * 0.95f, h * 0.19f), 0x137)
            extra(c, "R4", RectF(w * 0.73f, h * 0.125f, w * 0.81f, h * 0.185f))
            // rear paddles (on the back of the grips, drawn dashed)
            extra(c, "M1", RectF(w * 0.004f, h * 0.50f, w * 0.036f, h * 0.80f), rear = true)
            extra(c, "M2", RectF(w * 0.964f, h * 0.50f, w * 0.996f, h * 0.80f), rear = true)

            // left side: stick on top, d-pad below
            stick(c, "Left stick", w * 0.13f, h * 0.42f, u * 2.4f, ea(0), ea(1), aa("lx"), aa("ly"), 0x13d)
            dpad(c, w * 0.20f, h * 0.78f, u * 1.1f)
            button(c, "⧉", w * 0.27f, h * 0.30f, u * 0.6f, 0x13a) // view / select
            val sr = u * 0.6f
            extra(c, "Share", RectF(w * 0.27f - sr * 1.6f, h * 0.60f - sr, w * 0.27f + sr * 1.6f, h * 0.60f + sr))

            // right side: ABXY on top, stick below
            val bx = w * 0.86f
            val by = h * 0.40f
            button(c, "Y", bx, by - u * 1.6f, u * 0.85f, 0x134)
            button(c, "A", bx, by + u * 1.6f, u * 0.85f, 0x130)
            button(c, "X", bx - u * 1.6f, by, u * 0.85f, 0x133)
            button(c, "B", bx + u * 1.6f, by, u * 0.85f, 0x131)
            button(c, "☰", w * 0.73f, h * 0.30f, u * 0.6f, 0x13b) // start
            button(c, "⌂", w * 0.73f, h * 0.86f, u * 0.6f, 0x13c) // home
            extra(c, "Nexus", RectF(w * 0.73f - sr * 1.6f, h * 0.60f - sr, w * 0.73f + sr * 1.6f, h * 0.60f + sr))
            stick(c, "Right stick", w * 0.80f, h * 0.72f, u * 2.4f, ea(eRx), ea(eRy), aa("rx"), aa("ry"), 0x13e)

            // middle "screen": status + injected touches
            small.textSize = h * 0.021f
            var ty = tab.top + h * 0.05f
            fun say(s: String, col: Int = Raden.PLATINUM) {
                small.color = col
                c.drawText(s, tab.left + 20f, ty, small)
                ty += h * 0.035f
            }
            say("KISHI V3 PRO TEST", Raden.CREAM)
            say(if (engineOk) "■ teal = engine sees it ($eDevice)" else "■ engine not running (Start it on the main screen)", cyan)
            say("■ orange = Android sees it (no flicker)", orange)
            say("Engine buttons: " + eKeys.sorted().joinToString(" ") { Prefs.buttonName(it) })
            say("Android buttons: " + aKeys.sorted().joinToString(" ") { Prefs.buttonName(it) })
            say("Touches received on this screen: $touchCount")
            val lk = learningKey
            if (lk != null) say("Press $lk now… (tap it again to forget it)", Raden.ORANGE)
            else say("Dashed = extra button. Tap it, then press it.", Raden.PLATINUM)
            say("Extras: " + Prefs.EXTRA_KEYS.joinToString("  ") { k ->
                val cd = Prefs.extraCode(context, k)
                "$k " + if (cd < 0) "?" else "0x%03x".format(cd)
            })
            say("Dots below = where the flicker is touching", Raden.TEAL_SOFT)

            // trails (drawn over everything, in real screen coords)
            val now = SystemClock.uptimeMillis()
            oldTrails.removeAll { now - it.upAt > 1200 }
            for (t in oldTrails + trails.values) {
                val alpha = if (t.down) 255 else (255 * (1f - (now - t.upAt) / 1200f)).toInt().coerceIn(0, 255)
                fill.color = Color.argb(alpha, 224, 137, 91)
                for (i in 1 until t.pts.size) {
                    val a = t.pts[i - 1]
                    val b = t.pts[i]
                    fill.strokeWidth = 10f
                    c.drawLine(a[0], a[1], b[0], b[1], fill)
                }
                t.pts.lastOrNull()?.let { p ->
                    c.drawCircle(p[0], p[1], 36f, fill)
                }
            }
            if (oldTrails.isNotEmpty()) postInvalidateDelayed(33)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val hitKey = extraRects.entries.firstOrNull { (_, r) ->
                        RectF(r).apply { inset(-30f, -30f) }.contains(e.getX(i), e.getY(i))
                    }?.key
                    if (hitKey != null) {
                        if (learningKey == hitKey) {
                            Prefs.setExtraCode(context, hitKey, -1)
                            learningKey = null
                        } else {
                            learningKey = hitKey
                        }
                        invalidate()
                        return true
                    }
                    val t = Trail()
                    t.pts.add(floatArrayOf(e.getX(i), e.getY(i)))
                    trails[e.getPointerId(i)] = t
                    touchCount++
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                    trails[e.getPointerId(i)]?.pts?.add(floatArrayOf(e.getX(i), e.getY(i)))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    trails.remove(e.getPointerId(e.actionIndex))?.let {
                        it.down = false
                        it.upAt = now
                        oldTrails.add(it)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    for (t in trails.values) { t.down = false; t.upAt = now; oldTrails.add(t) }
                    trails.clear()
                }
            }
            invalidate()
            return true
        }
    }
}

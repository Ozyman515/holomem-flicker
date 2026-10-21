package com.ozyman.holomemflicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Lives in the app process while the flicker runs.
 * Watches the engine and draws, over the game:
 *  - the current mode's layout when the overlay is toggled on (Select+L1)
 *  - a short banner with the mode name whenever the mode changes (Select+R1)
 * The window never takes touches, and stays under Android's 0.8 opacity limit so
 * touches (real or injected) still reach the game underneath.
 */
class OverlayService : Service() {

    private var service: IFlickService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: OverlayView? = null

    private var overlayOn = false
    private var modeIdx = 0
    private var modeName = ""
    private var lastSeq = -1
    private var bannerUntil = 0L
    private val layouts = HashMap<Int, Prefs.Layout>()

    // in-game editor
    private var editor: Editor? = null
    private var lastKeySeen = ""
    private var pressedNow = HashSet<Int>()

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
            if (s != null) try {
                val o = JSONObject(s.inputState())
                if (o.has("mode")) {
                    val newMode = o.optInt("mode")
                    val seq = o.optInt("modeSeq")
                    overlayOn = o.optBoolean("overlay")
                    modeName = o.optString("modeName")
                    if (lastSeq >= 0 && seq != lastSeq) bannerUntil = SystemClock.uptimeMillis() + 1500
                    if (newMode != modeIdx) Prefs.putInt(this@OverlayService, Prefs.MODE, newMode)
                    lastSeq = seq
                    modeIdx = newMode
                    val keys = o.optJSONArray("keys")
                    val pr = HashSet<Int>()
                    if (keys != null) for (i in 0 until keys.length()) pr.add(keys.getInt(i))
                    pressedNow = pr
                    val editing = o.optBoolean("editing")
                    val lk = o.optString("lastKey")
                    val ed = editor
                    if (editing && ed == null) {
                        openEditor()
                    } else if (!editing && ed != null && !ed.closing) {
                        ed.saveAndClose(false) // closed with Select+Start: keep the changes
                    }
                    if (lk != lastKeySeen) {
                        val code = lk.substringAfter(':').toIntOrNull()
                        if (lastKeySeen.isNotEmpty() && code != null) editor?.onKey(code, pressedNow)
                        lastKeySeen = lk
                    }
                    editor?.onPoll(pressedNow)
                }
            } catch (_: Throwable) {
            }
            refresh()
            handler.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNote()
        wm = getSystemService(WindowManager::class.java)
        try {
            if (Shizuku.pingBinder()) Shizuku.bindUserService(args, conn)
        } catch (_: Throwable) {
        }
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "reload") layouts.clear()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        removeView()
        editor?.remove()
        editor = null
        try { Shizuku.unbindUserService(args, conn, false) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startForegroundNote() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("flicker", "Holomem Flicker", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "flicker")
            .setContentTitle("Holomem Flicker is running")
            .setContentText("Select+L1: show layout · Select+R1: switch mode")
            .setSmallIcon(android.R.drawable.ic_media_ff)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun layoutFor(i: Int): Prefs.Layout = layouts.getOrPut(i) { Prefs.load(this, i) }

    private fun refresh() {
        val showBanner = SystemClock.uptimeMillis() < bannerUntil
        val want = (overlayOn || showBanner) && editor == null
        if (want && view == null) addView()
        if (!want && view != null) removeView()
        view?.invalidate()
    }

    private fun addView() {
        if (!Settings.canDrawOverlays(this)) return
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0.75f // below Android's 0.8 "untrusted touch" limit
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            wm?.addView(v, lp)
            view = v
        } catch (_: Throwable) {
        }
    }

    private fun removeView() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Throwable) {}
        view = null
    }

    private fun openEditor() {
        if (!Settings.canDrawOverlays(this)) return
        removeView()
        val e = Editor(modeIdx)
        if (e.add()) editor = e
    }

    /**
     * Lenovo-style editor drawn over the game. The window takes every touch, so the
     * game can't be touched while it's open, and the engine sends no input either.
     */
    private inner class Editor(val mode: Int) {
        val data: Prefs.Layout = Prefs.load(this@OverlayService, mode).copy()
        var selected = Int.MIN_VALUE
        var learning = false
        var pendingSelect = false
        var closing = false
        private val ctx = android.view.ContextThemeWrapper(this@OverlayService, android.R.style.Theme_Material)
        private val root = android.widget.FrameLayout(ctx)
        private val board = Board()
        private val banner = android.widget.TextView(ctx)

        fun add(): Boolean {
            root.addView(board, android.widget.FrameLayout.LayoutParams(-1, -1))
            val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val bar = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            fun btn(label: String, f: () -> Unit) = bar.addView(android.widget.Button(ctx).apply {
                text = label
                Raden.pill(this, if (label == "Save") Raden.ORANGE else if (label == "Exit") Raden.CRIMSON else Raden.TEAL)
                setOnClickListener { f() }
            }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 10, 6, 6) })
            btn("Size −") { resize(-1) }
            btn("Size +") { resize(+1) }
            btn("Delete") { delete() }
            btn("Stick type") { stickType() }
            btn("Reset") {
                val d = Prefs.defaults(mode)
                data.taps.clear(); data.taps.addAll(d.taps)
                data.lfx = d.lfx; data.lfy = d.lfy; data.rfx = d.rfx; data.rfy = d.rfy
                data.ltype = d.ltype; data.rtype = d.rtype
                selected = Int.MIN_VALUE
                say("Back to the default ${Prefs.MODE_NAMES[mode]} layout (not saved yet).")
            }
            btn("Save") { saveAndClose(true) }
            btn("Exit") { close(true) }
            col.addView(bar)
            banner.setTextColor(Raden.CREAM)
            banner.background = Raden.rounded(Raden.alpha(Raden.TEAL_DEEP, 0xE6), 22f, Raden.ORANGE, 3)
            banner.setPadding(28, 12, 28, 12)
            col.addView(banner)
            root.addView(col, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            say("Editing ${Prefs.MODE_NAMES.getOrElse(mode) { "?" }}. Tap empty space to add a point, then press a button. Tap a point to select / drag it.")
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            return try {
                wm?.addView(root, lp)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun remove() {
            try { wm?.removeView(root) } catch (_: Throwable) {}
        }

        fun say(t: String) {
            banner.text = t
            board.invalidate()
        }

        /** A controller button was pressed while the editor is open. */
        fun onKey(code: Int, pressed: Set<Int>) {
            if (code == 0x13a) { // Select: maybe a combo, maybe an assignment - decide on release
                pendingSelect = true
                return
            }
            if (pressed.contains(0x13a)) { // part of a Select combo
                pendingSelect = false
                return
            }
            pendingSelect = false
            assign(code)
        }

        fun onPoll(pressed: Set<Int>) {
            if (pendingSelect && !pressed.contains(0x13a)) {
                pendingSelect = false
                assign(0x13a)
            }
        }

        private fun assign(code: Int) {
            val t = data.taps.getOrNull(selected) ?: return
            if (!learning) return
            t.code = code
            learning = false
            say("Point set to ${Prefs.buttonName(code)}. Drag to move, Size −/+ to resize.")
        }

        private fun resize(dir: Int) {
            val t = data.taps.getOrNull(selected) ?: return say("Tap a point first.")
            t.fr = (t.fr * if (dir > 0) 1.2f else 1 / 1.2f).coerceIn(0.008f, 0.15f)
            say("Circle: %.1f%% of screen width. Presses land randomly inside it.".format(t.fr * 100))
        }

        private fun delete() {
            if (selected < 0) return say("Tap a point first. (Sticks can't be deleted - set their type to off.)")
            data.taps.removeAt(selected)
            selected = Int.MIN_VALUE
            learning = false
            say("Deleted.")
        }

        private fun stickType() {
            fun next(t: String) = Prefs.STICK_TYPES[(Prefs.STICK_TYPES.indexOf(t) + 1) % Prefs.STICK_TYPES.size]
            when (selected) {
                -1 -> { data.ltype = next(data.ltype); say("Left stick: ${data.ltype}") }
                -2 -> { data.rtype = next(data.rtype); say("Right stick: ${data.rtype}") }
                else -> say("Tap a stick circle first.")
            }
        }

        fun saveAndClose(tellEngine: Boolean) {
            data.taps.removeAll { it.code < 0 }
            Prefs.save(this@OverlayService, mode, data)
            layouts.clear()
            closing = true
            remove()
            editor = null
            val s = service
            Thread {
                try {
                    if (tellEngine) s?.setEditing(false)
                    s?.start(Prefs.buildConfig(this@OverlayService)) // reload with the new layout
                } catch (_: Throwable) {
                }
            }.start()
            bannerUntil = SystemClock.uptimeMillis() + 1200
            modeName = "Saved · " + Prefs.MODE_NAMES.getOrElse(mode) { "" }
        }

        fun close(tellEngine: Boolean) {
            closing = true
            remove()
            editor = null
            if (tellEngine) try { service?.setEditing(false) } catch (_: Throwable) {}
        }

        private inner class Board : View(ctx) {
            private val painter = LayoutPainter(ctx)
            private var dragging = Int.MIN_VALUE

            override fun onDraw(c: Canvas) {
                c.drawColor(Raden.alpha(Raden.NIGHT, 0x66))
                painter.draw(c, width.toFloat(), height.toFloat(), data, selected)
            }

            private fun hit(x: Float, y: Float): Int {
                var best = Int.MIN_VALUE
                var bestD = 90f
                data.taps.forEachIndexed { i, t ->
                    val d = Math.hypot((t.fx * width - x).toDouble(), (t.fy * height - y).toDouble()).toFloat() -
                        maxOf(0f, t.fr * width - 45f)
                    if (d < bestD) { bestD = d; best = i }
                }
                val dl = Math.hypot((data.lfx * width - x).toDouble(), (data.lfy * height - y).toDouble()).toFloat()
                if (dl < bestD) { bestD = dl; best = -1 }
                val dr = Math.hypot((data.rfx * width - x).toDouble(), (data.rfy * height - y).toDouble()).toFloat()
                if (dr < bestD) best = -2
                return best
            }

            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                val fx = (e.x / width).coerceIn(0f, 1f)
                val fy = (e.y / height).coerceIn(0f, 1f)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val h = hit(e.x, e.y)
                        if (h == Int.MIN_VALUE) {
                            data.taps.add(Prefs.TapPoint(-1, fx, fy))
                            selected = data.taps.size - 1
                            learning = true
                            say("New point - press the controller button for it.")
                        } else {
                            selected = h
                            learning = h >= 0
                            if (h >= 0) say("${Prefs.buttonName(data.taps[h].code)} selected. Drag, resize, delete, or press a button to reassign.")
                            else say("${if (h == -1) "Left" else "Right"} stick selected. Drag it, or tap Stick type.")
                        }
                        dragging = selected
                    }
                    android.view.MotionEvent.ACTION_MOVE -> when {
                        dragging == -1 -> { data.lfx = fx; data.lfy = fy }
                        dragging == -2 -> { data.rfx = fx; data.rfy = fy }
                        dragging >= 0 -> data.taps.getOrNull(dragging)?.let { it.fx = fx; it.fy = fy }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> dragging = Int.MIN_VALUE
                }
                invalidate()
                return true
            }
        }
    }

    private inner class OverlayView(ctx: Context) : View(ctx) {
        private val painter = LayoutPainter(ctx)
        private val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Raden.alpha(Raden.TEAL_DEEP, 0xDD) }
        private val bannerTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Raden.CREAM
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = 64f
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (overlayOn) painter.draw(c, w, h, layoutFor(modeIdx))
            val showBanner = SystemClock.uptimeMillis() < bannerUntil
            if (showBanner || overlayOn) {
                val label = if (showBanner) "$modeName mode" else modeName
                bannerTxt.textSize = if (showBanner) 64f else 36f
                val tw = bannerTxt.measureText(label)
                val top = if (showBanner) h * 0.40f else 70f
                val r = RectF(w / 2 - tw / 2 - 40f, top, w / 2 + tw / 2 + 40f, top + bannerTxt.textSize * 1.6f)
                c.drawRoundRect(r, 30f, 30f, bannerBg)
                c.drawText(label, w / 2, r.centerY() + bannerTxt.textSize * 0.35f, bannerTxt)
            }
            if (showBanner) postInvalidateDelayed(100)
        }
    }
}

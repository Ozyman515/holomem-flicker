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
        val want = overlayOn || showBanner
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

    private inner class OverlayView(ctx: Context) : View(ctx) {
        private val painter = LayoutPainter(ctx)
        private val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD15182A.toInt() }
        private val bannerTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
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

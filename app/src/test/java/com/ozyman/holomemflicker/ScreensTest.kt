package com.ozyman.holomemflicker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the app's screens to PNGs (screens/ in the repo root) without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScreensTest {
    private val out = File(System.getProperty("screens.dir") ?: "screens").apply { mkdirs() }

    private fun save(v: View, name: String, w: Int, h: Int? = null) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            if (h != null) View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(a: Activity): View =
        (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    @Test
    fun main() {
        Prefs.putInt(RuntimeEnvironment.getApplication(), DisclaimerActivity.PREF, DisclaimerActivity.VERSION)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = content(a) as ViewGroup
        val scroll = root.getChildAt(0) as ScrollView
        val w = 2560
        // what you see when the app opens (with the Disclaimer tab)
        save(root, "1-main-top", w, 1600)
        // the whole scrolling page in one tall image
        val inner = scroll.getChildAt(0)
        scroll.removeView(inner)
        val holder = android.widget.FrameLayout(a).apply {
            setBackgroundColor(Raden.MINT)
            addView(inner)
        }
        save(holder, "2-main-full", w)
    }

    /** First launch: the disclaimer that must be accepted. */
    @Test
    fun disclaimer() {
        val i = Intent(RuntimeEnvironment.getApplication(), DisclaimerActivity::class.java)
            .putExtra(DisclaimerActivity.EXTRA_MUST_ACCEPT, true)
        val a = Robolectric.buildActivity(DisclaimerActivity::class.java, i).setup().get()
        val scroll = content(a) as ScrollView
        val inner = scroll.getChildAt(0)
        scroll.removeView(inner)
        save(android.widget.FrameLayout(a).apply { setBackgroundColor(Raden.MINT); addView(inner) }, "11-disclaimer", 2560)
    }

    /** Not accepted yet: opening the app goes straight to the disclaimer; accepting lets you in. */
    @Test
    fun disclaimerGate() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.putInt(ctx, DisclaimerActivity.PREF, 0)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val next = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
        org.junit.Assert.assertEquals(DisclaimerActivity::class.java.name, next?.component?.className)
        org.junit.Assert.assertTrue(next!!.getBooleanExtra(DisclaimerActivity.EXTRA_MUST_ACCEPT, false))
        val d = Robolectric.buildActivity(DisclaimerActivity::class.java, next).setup().get()
        val accept = (content(d) as ScrollView).findViewsWithText("I understand and accept")
        accept.performClick()
        org.junit.Assert.assertTrue(DisclaimerActivity.accepted(ctx))
    }

    private fun ScrollView.findViewsWithText(t: String): View {
        val found = ArrayList<View>()
        findViewsWithText(found, t, View.FIND_VIEWS_WITH_TEXT)
        return found.first()
    }

    private fun editor(mode: Int, name: String) {
        val i = Intent(RuntimeEnvironment.getApplication(), CalibrateActivity::class.java).putExtra("mode", mode)
        val a = Robolectric.buildActivity(CalibrateActivity::class.java, i).setup().get()
        save(content(a), name, 2560, 1600)
    }

    /** The in-game overlay with the flicker's touches: taps (ring + dot) and swipes (trails), live and fading. */
    @Test
    fun overlayTouches() {
        val ctx = RuntimeEnvironment.getApplication()
        Raden.apply(ctx)
        val w = 2560; val h = 1600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2A2340.toInt()) // stand-in for the game
        val l = Prefs.load(ctx, 0)
        LayoutPainter(ctx).draw(c, w.toFloat(), h.toFloat(), l)
        val tr = org.json.JSONArray()
        var seq = 0
        fun ev(id: Int, x: Float, y: Float, k: Int) = tr.put(org.json.JSONArray().put(++seq).put(id).put(x.toDouble()).put(y.toDouble()).put(k))
        val t = l.taps
        // 1: tap being held right now
        t.getOrNull(0)?.let { ev(1, it.fx * w, it.fy * h, 0) }
        // 2: tap that just lifted (fading)
        t.getOrNull(1)?.let { ev(2, it.fx * w, it.fy * h, 0); ev(2, it.fx * w, it.fy * h, 2) }
        // 3: right-stick flick mid-swipe
        val rx = l.rfx * w; val ry = l.rfy * h
        ev(3, rx, ry, 0); for (i in 1..8) ev(3, rx + i * 28f, ry - i * 22f, 1)
        // 4: left-stick flick finished a moment ago (fading)
        val lx = l.lfx * w; val ly = l.lfy * h
        ev(4, lx, ly, 0); for (i in 1..8) ev(4, lx - i * 30f, ly + i * 6f, 1); ev(4, lx - 240f, ly + 48f, 2)
        val trails = TouchTrails()
        val now = 100_000L
        trails.read(org.json.JSONArray(), now) // overlay just opened
        trails.read(tr, now)
        trails.age(1, 120, -1, now)
        trails.age(2, 300, 250, now)
        trails.age(3, 200, -1, now)
        trails.age(4, 600, 500, now)
        trails.draw(c, 0f, 0f, 2f, now)
        File(out, "8-overlay-touches.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun editorLive() = editor(0, "3-editor-live")
    @Test fun editorPark() = editor(1, "4-editor-park")

    @Test
    fun controllerTest() {
        // demo state: L4/R4 taught as your current L3/R3 remap, the rest untaught
        Prefs.setExtraCode(RuntimeEnvironment.getApplication(), "L4", 0x13d)
        Prefs.setExtraCode(RuntimeEnvironment.getApplication(), "R4", 0x13e)
        val a = Robolectric.buildActivity(ControllerTestActivity::class.java).setup().get()
        // demo: L2 squeezed 60 %, R2 25 %
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply {
            setAxisValue(android.view.MotionEvent.AXIS_LTRIGGER, 0.6f)
            setAxisValue(android.view.MotionEvent.AXIS_RTRIGGER, 0.25f)
        })
        val ev = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0)
        a.onGenericMotionEvent(ev)
        save(content(a), "5-controller-test", 2560, 1600)
    }

    @Test
    fun themePicker() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.putStr(app, Raden.PREF_KEY, "raden")
        val a = Robolectric.buildActivity(ThemePickerActivity::class.java).setup().get()
        val scroll = (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ScrollView
        save(scroll, "6-theme-picker", 2560, 1600)
        val inner = scroll.getChildAt(0)
        val bg = scroll.background
        scroll.removeView(inner)
        val holder = android.widget.FrameLayout(a).apply { background = bg; addView(inner) }
        save(holder, "7-theme-picker-full", 2560)
    }

    @Test
    fun mainKaela() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.putStr(app, Raden.PREF_KEY, "kaela")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        save(content(a), "8-main-kaela", 2560, 1600)
        Prefs.putStr(app, Raden.PREF_KEY, "suisei")
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        save(content(b), "9-main-suisei", 2560, 1600)
        Prefs.putStr(app, Raden.PREF_KEY, "raden")
    }

    @Test
    fun icons() {
        val app = RuntimeEnvironment.getApplication()
        val keys = listOf("raden", "kaela", "towa", "bijou", "suisei", "miko", "korone", "pekora", "mococo", "fuwawa", "ollie", "lamy")
        val s = 288
        val bmp = Bitmap.createBitmap(s * 6, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF202020.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        keys.forEachIndexed { i, k ->
            val x = (i % 6) * s
            val y = (i / 6) * s
            val t = ThemeData.byKey(k)
            val path = android.graphics.Path().apply {
                addCircle(x + s / 2f, y + s / 2f, s * 0.42f, android.graphics.Path.Direction.CW)
            }
            c.save()
            c.clipPath(path)
            paint.color = t.deep
            c.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), paint)
            val id = app.resources.getIdentifier("ic_fan_$k", "drawable", app.packageName)
            val d = app.resources.getDrawable(id, null)
            // adaptive icons show the middle 72/108 of the layer
            val pad = (s * 0.42f * 2 * (108f / 72f) - s) / 2
            d.setBounds((x - pad).toInt(), (y - pad).toInt(), (x + s + pad).toInt(), (y + s + pad).toInt())
            d.draw(c)
            c.restore()
        }
        File(out, "10-icons.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

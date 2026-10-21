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
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = content(a) as ScrollView
        val w = 2560
        // what you see when the app opens
        save(scroll, "1-main-top", w, 1600)
        // the whole scrolling page in one tall image
        val inner = scroll.getChildAt(0)
        scroll.removeView(inner)
        val holder = android.widget.FrameLayout(a).apply {
            setBackgroundColor(Raden.MINT)
            addView(inner)
        }
        save(holder, "2-main-full", w)
    }

    private fun editor(mode: Int, name: String) {
        val i = Intent(RuntimeEnvironment.getApplication(), CalibrateActivity::class.java).putExtra("mode", mode)
        val a = Robolectric.buildActivity(CalibrateActivity::class.java, i).setup().get()
        save(content(a), name, 2560, 1600)
    }

    @Test fun editorLive() = editor(0, "3-editor-live")
    @Test fun editorPark() = editor(1, "4-editor-park")

    @Test
    fun controllerTest() {
        // demo state: L4/R4 taught as your current L3/R3 remap, the rest untaught
        Prefs.setExtraCode(RuntimeEnvironment.getApplication(), "L4", 0x13d)
        Prefs.setExtraCode(RuntimeEnvironment.getApplication(), "R4", 0x13e)
        val a = Robolectric.buildActivity(ControllerTestActivity::class.java).setup().get()
        save(content(a), "5-controller-test", 2560, 1600)
    }
}

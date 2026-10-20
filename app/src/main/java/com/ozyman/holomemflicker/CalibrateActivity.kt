package com.ozyman.holomemflicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Full-screen layout editor. Optional background = one of your in-song screenshots,
 * so the markers line up with the real judgment line.
 */
class CalibrateActivity : Activity() {

    private lateinit var data: Prefs.Layout
    private var modeIdx = 0
    private lateinit var board: Board
    private lateinit var banner: TextView
    private var bg: Bitmap? = null

    /** Called with the evdev scan code of the next controller button press. */
    private var learning: ((Int) -> Unit)? = null
    private val swallowUp = HashSet<Int>()

    companion object {
        const val SEL_NONE = Int.MIN_VALUE
        const val SEL_LEFT = -1
        const val SEL_RIGHT = -2
        private const val PICK_BG = 7
    }

    private var selected = SEL_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modeIdx = intent.getIntExtra("mode", 0)
        data = Prefs.load(this, modeIdx)

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        board = Board(this)
        root.addView(board, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                alpha = 0.85f
                setOnClickListener { onClick() }
            })
        }
        btn("Background") { pickBackground() }
        btn("Add point") {
            learn("Press the controller button for the NEW point…") { code ->
                data.taps.add(Prefs.TapPoint(code, 0.5f, 0.85f))
                selected = data.taps.size - 1
                say("Added a ${Prefs.buttonName(code)} point in the middle. Drag it into place.")
            }
        }
        btn("Button") {
            val i = selected
            if (i < 0) {
                say("Tap a tap point first to select it.")
            } else {
                learn("Press the controller button for this point…") { code ->
                    data.taps[i].code = code
                    say("Point is now ${Prefs.buttonName(code)}.")
                }
            }
        }
        btn("Delete") {
            val i = selected
            if (i < 0) {
                say("Tap a tap point first to select it. (Sticks can't be deleted - set their type to off.)")
            } else {
                data.taps.removeAt(i)
                selected = SEL_NONE
                say("Deleted.")
            }
        }
        btn("Stick type") {
            when (selected) {
                SEL_LEFT -> {
                    data.ltype = nextType(data.ltype)
                    say("Left stick is now: ${data.ltype}")
                }
                SEL_RIGHT -> {
                    data.rtype = nextType(data.rtype)
                    say("Right stick is now: ${data.rtype}")
                }
                else -> say("Tap a stick circle first, then tap Stick type to switch flick / joystick / camera / off.")
            }
        }
        btn("Reset") {
            data = Prefs.defaults(modeIdx)
            selected = SEL_NONE
            say("Back to the default layout (not saved yet).")
        }
        btn("Save") {
            Prefs.save(this, modeIdx, data)
            finish()
        }
        btn("Cancel") { finish() }
        col.addView(bar)
        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 8, 24, 8)
            text = "Editing ${Prefs.MODE_NAMES.getOrElse(modeIdx) { "?" }} mode. Drag markers; tap one to select it. Several points can share one button."
        }
        col.addView(banner)
        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)

        Prefs.getStr(this, Prefs.BG, "").takeIf { it.isNotEmpty() }?.let { loadBg(Uri.parse(it)) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun say(s: String) {
        banner.text = s
        board.invalidate()
    }

    private fun learn(prompt: String, cb: (Int) -> Unit) {
        say(prompt)
        learning = cb
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val cb = learning
        if (cb != null && event.repeatCount == 0 && event.scanCode > 0) {
            learning = null
            swallowUp.add(event.scanCode)
            cb(event.scanCode)
            return true
        }
        if (swallowUp.contains(event.scanCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (swallowUp.remove(event.scanCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun pickBackground() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, PICK_BG)
    }

    @Deprecated("plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, intent)
        val uri = intent?.data
        if (requestCode == PICK_BG && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            Prefs.putStr(this, Prefs.BG, uri.toString())
            loadBg(uri)
        }
    }

    private fun loadBg(uri: Uri) {
        bg = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                if (info.size.width > 1600) dec.setTargetSampleSize(2)
            }
        } catch (_: Throwable) {
            null
        }
        board.invalidate()
    }

    private fun nextType(t: String): String {
        val i = Prefs.STICK_TYPES.indexOf(t)
        return Prefs.STICK_TYPES[(i + 1) % Prefs.STICK_TYPES.size]
    }

    private inner class Board(ctx: Context) : View(ctx) {
        private val painter = LayoutPainter(ctx)
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
        }
        private var dragging = SEL_NONE

        private fun px(f: Float) = f * width
        private fun py(f: Float) = f * height

        override fun onDraw(c: Canvas) {
            val b = bg
            if (b != null) {
                c.drawBitmap(b, null, Rect(0, 0, width, height), null)
                c.drawColor(0x44000000)
            } else {
                c.drawColor(0xFF15182A.toInt())
                fill.color = 0x55FFFFFF
                c.drawRect(0f, height * 0.77f, width.toFloat(), height * 0.78f, fill)
            }
            painter.draw(c, width.toFloat(), height.toFloat(), data, selected)
        }

        private fun hit(x: Float, y: Float): Int {
            var best = SEL_NONE
            var bestD = 90f
            data.taps.forEachIndexed { i, t ->
                val d = hypot(px(t.fx) - x, py(t.fy) - y)
                if (d < bestD) { bestD = d; best = i }
            }
            val dl = hypot(px(data.lfx) - x, py(data.lfy) - y)
            if (dl < bestD) { bestD = dl; best = SEL_LEFT }
            val dr = hypot(px(data.rfx) - x, py(data.rfy) - y)
            if (dr < bestD) { best = SEL_RIGHT }
            return best
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = hit(e.x, e.y)
                    selected = dragging
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (dragging != SEL_NONE) {
                    val fx = (e.x / width).coerceIn(0f, 1f)
                    val fy = (e.y / height).coerceIn(0f, 1f)
                    when (dragging) {
                        SEL_LEFT -> { data.lfx = fx; data.lfy = fy }
                        SEL_RIGHT -> { data.rfx = fx; data.rfy = fy }
                        else -> data.taps.getOrNull(dragging)?.let { it.fx = fx; it.fy = fy }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = SEL_NONE
            }
            return true
        }
    }
}

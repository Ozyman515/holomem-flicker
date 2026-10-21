package com.ozyman.holomemflicker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Theme changer: every holomem is a glossy round bubble (PS Vita home-screen style),
 * grouped by generation. Tapping one previews it live; "Use" saves it and swaps the launcher icon.
 */
class ThemePickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var useBtn: Button
    private var picked: ThemeSpec = Raden.current
    private val bubbles = ArrayList<Bubble>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Raden.apply(this)
        picked = Raden.current

        val dm = resources.displayMetrics
        val cols = max(4, min(9, (dm.widthPixels / dm.density / 118f).toInt()))

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 48)
        }
        // Top bar: title + use / back
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            Raden.pill(this, 0x33FFFFFF, Color.WHITE)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 16 })
        useBtn = Button(this).apply { setOnClickListener { use() } }
        bar.addView(useBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "Tap a bubble to preview. \"Use\" saves it and recolors the app icon (your launcher may take a moment to refresh)."
            textSize = 14f
            setTextColor(0xDDFFFFFF.toInt())
            setPadding(4, 10, 4, 18)
        })

        // Bubbles, grouped by generation
        for ((group, list) in ThemeData.ALL.groupBy { it.group }) {
            root.addView(TextView(this).apply {
                text = group
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(26, 6, 26, 6)
                background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 40f }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 22; bottomMargin = 8 })
            list.chunked(cols).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { t ->
                    val b = Bubble(this, t)
                    bubbles.add(b)
                    row.addView(b, LinearLayout.LayoutParams(0, (118 * dm.density).toInt(), 1f))
                }
                repeat(cols - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                root.addView(row)
            }
        }
        setContentView(ScrollView(this).apply { addView(root); isVerticalScrollBarEnabled = false })
        refresh()
    }

    private fun refresh() {
        Raden.use(picked)
        val p = picked
        // Vita-like wallpaper: the theme's deep colour fading into night, with a soft diagonal sheen
        (root.parent as? View)?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(p.deep, Raden.mix(p.deep, p.night, 0.6f), p.night)
        )
        title.text = "Theme · ${p.name}"
        title.setTextColor(Color.WHITE)
        val saved = Prefs.getStr(this, Raden.PREF_KEY, "raden") == p.key
        useBtn.text = if (saved) "In use ✓" else "Use ${p.short}"
        Raden.pill(useBtn, if (saved) 0x33FFFFFF else p.accent, Color.WHITE)
        window.statusBarColor = p.deep
        window.navigationBarColor = p.night
        bubbles.forEach { it.invalidate() }
    }

    private fun use() {
        Prefs.putStr(this, Raden.PREF_KEY, picked.key)
        setLauncherIcon(this, picked.key)
        refresh()
        setResult(RESULT_OK)
        finish()
    }

    override fun onBackPressed() {
        Raden.apply(this) // drop an unsaved preview
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun finish() {
        Raden.apply(this)
        super.finish()
    }

    /** One glossy round "skittle" bubble with the member's short name under it. */
    private inner class Bubble(ctx: Context, val t: ThemeSpec) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 1.5f, 0xAA000000.toInt())
        }
        private var pressedScale = 1f

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = min(w, h * 0.78f) * 0.36f * pressedScale
            val cx = w / 2
            val cy = h * 0.40f
            val sel = t.key == picked.key
            // drop shadow
            p.shader = null
            p.color = 0x55000000
            c.drawCircle(cx, cy + r * 0.10f, r * 1.02f, p)
            // selection halo
            if (sel) {
                p.color = Raden.alpha(t.accent, 0xCC)
                c.drawCircle(cx, cy, r * 1.17f, p)
                p.color = Color.WHITE
                c.drawCircle(cx, cy, r * 1.08f, p)
            }
            // body: radial gradient soft -> primary -> deep
            p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
                intArrayOf(t.soft, t.primary, t.deep), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            // accent "skittle" swirl across the lower half
            p.shader = null
            p.color = Raden.alpha(t.accent, 0xE6)
            c.drawArc(RectF(cx - r * 0.72f, cy - r * 0.15f, cx + r * 0.72f, cy + r * 0.85f), 20f, 140f, false,
                p.apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f; strokeCap = Paint.Cap.ROUND })
            p.style = Paint.Style.FILL
            // rim
            p.color = Raden.alpha(t.card, 0xAA)
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.06f
            c.drawCircle(cx, cy, r * 0.97f, p)
            p.style = Paint.Style.FILL
            // glossy highlight on the top half
            p.shader = LinearGradient(0f, cy - r, 0f, cy, 0xCCFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawOval(RectF(cx - r * 0.72f, cy - r * 0.93f, cx + r * 0.72f, cy - r * 0.05f), p)
            p.shader = null
            // name
            txt.textSize = min(r * 0.42f, 16f * resources.displayMetrics.density)
            c.drawText(t.short, cx, cy + r + txt.textSize * 1.35f, txt)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedScale = 0.9f; invalidate() }
                MotionEvent.ACTION_UP -> {
                    pressedScale = 1f
                    picked = t
                    refresh()
                }
                MotionEvent.ACTION_CANCEL -> { pressedScale = 1f; invalidate() }
            }
            return true
        }
    }

    companion object {
        /** Enable the launcher alias with this theme's icon and disable the others. */
        fun setLauncherIcon(ctx: Context, key: String) {
            val pm = ctx.packageManager
            val pkg = ctx.packageName
            try {
                pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.Launcher_$key"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                for (t in ThemeData.ALL) if (t.key != key) {
                    pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.Launcher_${t.key}"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            } catch (_: Throwable) {
            }
        }
    }
}

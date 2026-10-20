package com.ozyman.holomemflicker

import android.content.Context
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Saved layout. Positions are fractions of the (landscape) screen so they survive resolution changes. */
object Prefs {
    class TapPoint(var code: Int, var fx: Float, var fy: Float)

    class Layout(
        val taps: MutableList<TapPoint>,
        var lfx: Float, var lfy: Float,
        var rfx: Float, var rfy: Float
    )

    private const val FILE = "flicker"

    private val names = mapOf(
        0x130 to "A", 0x131 to "B", 0x133 to "X", 0x134 to "Y",
        0x136 to "L1", 0x137 to "R1", 0x138 to "L2", 0x139 to "R2",
        0x13a to "Select", 0x13b to "Start", 0x13c to "Home",
        0x13d to "ThumbL", 0x13e to "ThumbR"
    )

    fun buttonName(code: Int): String = names[code] ?: "0x%03x".format(code)

    fun defaults(): Layout = Layout(
        mutableListOf(
            TapPoint(0x136, 0.318f, 0.848f), // L1
            TapPoint(0x137, 0.678f, 0.848f), // R1
            TapPoint(0x13d, 0.464f, 0.866f), // ThumbL
            TapPoint(0x13e, 0.532f, 0.866f)  // ThumbR
        ),
        0.30f, 0.80f, 0.70f, 0.80f
    )

    fun load(ctx: Context): Layout {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = sp.getString("layout", null) ?: return defaults()
        return try {
            val o = JSONObject(s)
            val arr = o.getJSONArray("taps")
            val taps = ArrayList<TapPoint>()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                taps.add(TapPoint(t.getInt("code"), t.getDouble("fx").toFloat(), t.getDouble("fy").toFloat()))
            }
            Layout(
                taps,
                o.getDouble("lfx").toFloat(), o.getDouble("lfy").toFloat(),
                o.getDouble("rfx").toFloat(), o.getDouble("rfy").toFloat()
            )
        } catch (_: Throwable) {
            defaults()
        }
    }

    fun save(ctx: Context, l: Layout) {
        val arr = JSONArray()
        for (t in l.taps) arr.put(JSONObject().put("code", t.code).put("fx", t.fx.toDouble()).put("fy", t.fy.toDouble()))
        val o = JSONObject().put("taps", arr)
            .put("lfx", l.lfx.toDouble()).put("lfy", l.lfy.toDouble())
            .put("rfx", l.rfx.toDouble()).put("rfy", l.rfy.toDouble())
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("layout", o.toString()).apply()
    }

    // Tunables (percent values)
    fun getInt(ctx: Context, key: String, def: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(key, def)

    fun putInt(ctx: Context, key: String, v: Int) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(key, v).apply()

    fun getStr(ctx: Context, key: String, def: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun putStr(ctx: Context, key: String, v: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, v).apply()

    const val RADIUS = "radiusPct"   // flick length, % of screen width x10
    const val REACH = "reachPct"     // sideways reach, % of screen width
    const val DEAD = "deadPct"       // stick threshold, %
    const val AXES = "rightAxes"     // auto | z | rx
    const val BG = "bgUri"

    fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return max(b.width(), b.height()) to min(b.width(), b.height())
    }

    /** Build the engine config in real screen pixels (landscape). */
    fun buildConfig(ctx: Context): String {
        val (w, h) = screenSize(ctx)
        val l = load(ctx)
        val taps = JSONArray()
        for (t in l.taps) {
            taps.put(
                JSONObject().put("name", buttonName(t.code)).put("code", t.code)
                    .put("x", (t.fx * w).toDouble()).put("y", (t.fy * h).toDouble())
            )
        }
        val sticks = JSONArray()
            .put(JSONObject().put("name", "Left stick").put("side", "left")
                .put("x", (l.lfx * w).toDouble()).put("y", (l.lfy * h).toDouble()))
            .put(JSONObject().put("name", "Right stick").put("side", "right")
                .put("x", (l.rfx * w).toDouble()).put("y", (l.rfy * h).toDouble()))
        return JSONObject()
            .put("taps", taps)
            .put("sticks", sticks)
            .put("radius", getInt(ctx, RADIUS, 50) / 1000.0 * w)
            .put("reach", getInt(ctx, REACH, 18) / 100.0 * w)
            .put("dead", getInt(ctx, DEAD, 35) / 100.0)
            .put("rightAxes", getStr(ctx, AXES, "auto"))
            .put("screenW", w.toDouble())
            .put("screenH", h.toDouble())
            .toString()
    }
}

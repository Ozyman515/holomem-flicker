package com.ozyman.holomemflicker

import android.content.Context
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Saved layouts, one per MODE (Live / Park / Minigame).
 * Positions are fractions of the (landscape) screen so they survive resolution changes.
 */
object Prefs {
    /** fr = radius of the tap circle as a fraction of screen width; presses land randomly inside it. */
    class TapPoint(var code: Int, var fx: Float, var fy: Float, var fr: Float = Prefs.DEFAULT_TAP_R)

    const val DEFAULT_TAP_R = 0.021f

    class Layout(
        val taps: MutableList<TapPoint>,
        var lfx: Float, var lfy: Float,
        var rfx: Float, var rfy: Float,
        var ltype: String = "flick",
        var rtype: String = "flick"
    ) {
        fun copy(): Layout = Layout(
            taps.map { TapPoint(it.code, it.fx, it.fy, it.fr) }.toMutableList(),
            lfx, lfy, rfx, rfy, ltype, rtype
        )
    }

    val MODE_NAMES = listOf("Live", "Park", "Minigame")
    val STICK_TYPES = listOf("flick", "joystick", "camera", "off")

    private const val FILE = "flicker"

    private val names = mapOf(
        0x130 to "A", 0x131 to "B", 0x133 to "X", 0x134 to "Y",
        0x136 to "L1", 0x137 to "R1", 0x138 to "L2", 0x139 to "R2",
        0x13a to "Select", 0x13b to "Start", 0x13c to "Home",
        0x13d to "ThumbL", 0x13e to "ThumbR"
    )

    /** Kishi V3 Pro extra buttons (L4, R4, M1, M2, Share, Nexus) the user taught in the controller test. */
    val EXTRA_KEYS = listOf("L4", "R4", "M1", "M2", "Share", "Nexus")
    @Volatile private var extraLabels: Map<Int, String> = emptyMap()

    fun extraCode(ctx: Context, key: String): Int = getInt(ctx, "extra_$key", -1)

    fun setExtraCode(ctx: Context, key: String, code: Int) {
        putInt(ctx, "extra_$key", code)
        refreshExtras(ctx)
    }

    fun refreshExtras(ctx: Context) {
        val m = HashMap<Int, String>()
        for (k in EXTRA_KEYS) extraCode(ctx, k).takeIf { it >= 0 }?.let { m.putIfAbsent(it, k) }
        extraLabels = m
    }

    fun buttonName(code: Int): String = names[code] ?: extraLabels[code] ?: "0x%03x".format(code)

    /** Live: the flick layout. */
    private fun liveDefaults(): Layout = Layout(
        mutableListOf(
            TapPoint(0x136, 0.318f, 0.848f), // L1
            TapPoint(0x137, 0.678f, 0.848f), // R1
            TapPoint(0x13d, 0.464f, 0.866f), // ThumbL
            TapPoint(0x13e, 0.532f, 0.866f)  // ThumbR
        ),
        0.30f, 0.80f, 0.70f, 0.80f, "flick", "flick"
    )

    /** Park: the user's chosen default (screenshot positions / 2000x1253). */
    private fun parkDefaults(): Layout = Layout(
        mutableListOf(
            TapPoint(0x13a, 0.110f, 0.214f), // Select (map)
            TapPoint(0x13b, 0.958f, 0.070f), // Start (menu)
            TapPoint(0x134, 0.922f, 0.438f), // Y (top prompt)
            TapPoint(0x131, 0.924f, 0.525f), // B (middle prompt)
            TapPoint(0x130, 0.927f, 0.612f), // A (bottom prompt)
            TapPoint(0x133, 0.877f, 0.869f)  // X (jump)
        ),
        0.194f, 0.796f, // left stick = walking joystick
        0.810f, 0.683f, // right stick = camera
        "joystick", "camera"
    )

    /** Minigame: the user's chosen default. */
    private fun minigameDefaults(): Layout = Layout(
        mutableListOf(
            TapPoint(0x13a, 0.105f, 0.210f), // Select
            TapPoint(0x13b, 0.958f, 0.070f), // Start
            TapPoint(0x139, 0.859f, 0.778f), // R2
            TapPoint(0x138, 0.715f, 0.886f)  // L2
        ),
        0.183f, 0.611f,
        0.797f, 0.642f,
        "joystick", "camera"
    )

    fun defaults(mode: Int = 0): Layout = when (mode) {
        1 -> parkDefaults()
        2 -> minigameDefaults()
        else -> liveDefaults()
    }

    private fun toJson(l: Layout): JSONObject {
        val arr = JSONArray()
        for (t in l.taps) arr.put(JSONObject().put("code", t.code).put("fx", t.fx.toDouble()).put("fy", t.fy.toDouble()).put("fr", t.fr.toDouble()))
        return JSONObject().put("taps", arr)
            .put("lfx", l.lfx.toDouble()).put("lfy", l.lfy.toDouble())
            .put("rfx", l.rfx.toDouble()).put("rfy", l.rfy.toDouble())
            .put("ltype", l.ltype).put("rtype", l.rtype)
    }

    private fun fromJson(o: JSONObject, fallback: Layout): Layout = try {
        val arr = o.getJSONArray("taps")
        val taps = ArrayList<TapPoint>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            taps.add(TapPoint(t.getInt("code"), t.getDouble("fx").toFloat(), t.getDouble("fy").toFloat(), t.optDouble("fr", DEFAULT_TAP_R.toDouble()).toFloat()))
        }
        Layout(
            taps,
            o.getDouble("lfx").toFloat(), o.getDouble("lfy").toFloat(),
            o.getDouble("rfx").toFloat(), o.getDouble("rfy").toFloat(),
            o.optString("ltype", fallback.ltype), o.optString("rtype", fallback.rtype)
        )
    } catch (_: Throwable) {
        fallback
    }

    fun load(ctx: Context, mode: Int = 0): Layout {
        refreshExtras(ctx)
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = sp.getString("layout_$mode", null)
        if (s != null) return fromJson(JSONObject(s), defaults(mode))
        // first run for this mode
        if (mode == 0) {
            sp.getString("layout", null)?.let { return fromJson(JSONObject(it), liveDefaults()) } // pre-modes save
        }
        return defaults(mode)
    }

    fun save(ctx: Context, mode: Int, l: Layout) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("layout_$mode", toJson(l).toString()).apply()
    }

    // Tunables (percent / per-mille values)
    fun getInt(ctx: Context, key: String, def: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(key, def)

    fun putInt(ctx: Context, key: String, v: Int) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(key, v).apply()

    fun getStr(ctx: Context, key: String, def: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, def) ?: def

    fun putStr(ctx: Context, key: String, v: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, v).apply()

    const val RADIUS = "radiusPct"   // flick length, ‰ of screen width
    const val REACH = "reachPct"     // sideways reach, % of screen width
    const val DEAD = "deadPct"       // flick stick threshold, %
    const val JOY = "joyPermille"    // joystick drag radius, ‰ of screen width
    const val CAM = "camPermille"    // camera speed, ‰ of screen width per frame at full tilt
    const val AXES = "rightAxes"     // auto | z | rx
    const val BG = "bgUri"
    const val MODE = "mode"          // starting mode index
    const val KILL_PKG = "killPkg"   // mapper app to force-stop on Start ("" = off)

    /** Everything the app has saved (layouts, sliders, choices) as one JSON backup. */
    fun exportAll(ctx: Context): String {
        val o = JSONObject().put("app", "holomem-flicker").put("backupVersion", 1)
        val data = JSONObject()
        for ((k, v) in ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).all) {
            if (k == DisclaimerActivity.PREF) continue // each install accepts the disclaimer itself
            when (v) {
                is Int -> data.put(k, JSONObject().put("t", "i").put("v", v))
                is String -> data.put(k, JSONObject().put("t", "s").put("v", v))
                is Boolean -> data.put(k, JSONObject().put("t", "b").put("v", v))
            }
        }
        return o.put("prefs", data).toString(2)
    }

    /** Restore a backup made by [exportAll]. Returns how many settings were restored. */
    fun importAll(ctx: Context, json: String): Int {
        val o = JSONObject(json)
        require(o.optString("app") == "holomem-flicker") { "not a Holomem Flicker backup" }
        val data = o.getJSONObject("prefs")
        val ed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        var n = 0
        for (k in data.keys()) {
            if (k == DisclaimerActivity.PREF) continue
            val e = data.getJSONObject(k)
            when (e.optString("t")) {
                "i" -> ed.putInt(k, e.getInt("v"))
                "s" -> ed.putString(k, e.getString("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                else -> continue
            }
            n++
        }
        ed.apply()
        return n
    }

    fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return max(b.width(), b.height()) to min(b.width(), b.height())
    }

    /** Stick parameters in pixels for a given type. */
    fun stickJson(ctx: Context, side: String, type: String, fx: Float, fy: Float, w: Int, h: Int): JSONObject {
        val o = JSONObject().put("name", if (side == "left") "Left stick" else "Right stick")
            .put("side", side).put("type", type)
            .put("x", (fx * w).toDouble()).put("y", (fy * h).toDouble())
        when (type) {
            "flick" -> o.put("radius", getInt(ctx, RADIUS, 50) / 1000.0 * w)
                .put("reach", getInt(ctx, REACH, 18) / 100.0 * w)
                .put("dead", getInt(ctx, DEAD, 35) / 100.0)
            "joystick" -> o.put("radius", getInt(ctx, JOY, 60) / 1000.0 * w).put("reach", 0.0).put("dead", 0.15)
            "camera" -> o.put("radius", getInt(ctx, CAM, 10) / 1000.0 * w).put("reach", 0.0).put("dead", 0.2)
        }
        return o
    }

    /** Build the engine config in real screen pixels (landscape), with every mode. */
    fun buildConfig(ctx: Context): String {
        val (w, h) = screenSize(ctx)
        val modes = JSONArray()
        for ((i, name) in MODE_NAMES.withIndex()) {
            val l = load(ctx, i)
            val taps = JSONArray()
            for (t in l.taps) {
                taps.put(
                    JSONObject().put("name", buttonName(t.code)).put("code", t.code)
                        .put("x", (t.fx * w).toDouble()).put("y", (t.fy * h).toDouble())
                        .put("r", (t.fr * w).toDouble())
                )
            }
            val sticks = JSONArray()
            if (l.ltype != "off") sticks.put(stickJson(ctx, "left", l.ltype, l.lfx, l.lfy, w, h))
            if (l.rtype != "off") sticks.put(stickJson(ctx, "right", l.rtype, l.rfx, l.rfy, w, h))
            modes.put(JSONObject().put("name", name).put("taps", taps).put("sticks", sticks))
        }
        return JSONObject()
            .put("modes", modes)
            .put("mode", getInt(ctx, MODE, 0))
            .put("rightAxes", getStr(ctx, AXES, "auto"))
            .put("screenW", w.toDouble())
            .put("screenH", h.toDouble())
            .toString()
    }
}

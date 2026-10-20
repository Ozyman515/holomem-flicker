package com.ozyman.holomemflicker

import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.min

/**
 * Reads the controller straight from /dev/input (works as the shell user)
 * and turns it into touches:
 *  - tap slots: a button held = a finger held at that spot
 *  - sticks: "steering wheel" flick. Finger goes down at the base point
 *    (shifted sideways by the push direction for edge reach), then follows
 *    the stick outward. The fast jump is what the game reads as a flick.
 */
class Engine(cfg: JSONObject) : Thread("flicker-engine") {

    private class Tap(val name: String, val code: Int, val x: Float, val y: Float) {
        var pid = -1
    }

    private class Stick(
        val name: String, val ax: Int, val ay: Int,
        val bx: Float, val by: Float
    ) {
        var pid = -1
        var sx = 0f
        var sy = 0f
        var lastX = 0f
        var lastY = 0f
    }

    @Volatile private var running = true
    @Volatile var status = "starting"
        private set
    @Volatile var lastKey = ""
        private set
    private var keySeq = 0

    private val forcedPath = cfg.optString("devicePath", "")
    private val rightAxes = cfg.optString("rightAxes", "auto")
    private val radius = cfg.optDouble("radius", 120.0).toFloat()
    private val reach = cfg.optDouble("reach", 400.0).toFloat()
    private val dead = cfg.optDouble("dead", 0.35).toFloat()
    private val screenW = cfg.optDouble("screenW", 2560.0).toFloat()
    private val screenH = cfg.optDouble("screenH", 1600.0).toFloat()

    private val taps = ArrayList<Tap>()
    private val sticks = ArrayList<Stick>()
    private val stickCfg = ArrayList<JSONObject>()

    private val raw = HashMap<Int, Int>()
    private val range = HashMap<Int, IntArray>()
    private val recent = ArrayDeque<String>()

    private var inj: TouchInjector? = null
    private var stream: FileInputStream? = null

    init {
        val ta = cfg.optJSONArray("taps")
        if (ta != null) for (i in 0 until ta.length()) {
            val o = ta.getJSONObject(i)
            taps.add(Tap(o.optString("name"), o.optInt("code"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat()))
        }
        val sa = cfg.optJSONArray("sticks")
        if (sa != null) for (i in 0 until sa.length()) stickCfg.add(sa.getJSONObject(i))
    }

    private fun note(s: String) {
        synchronized(recent) {
            recent.addLast(s)
            while (recent.size > 10) recent.removeFirst()
        }
    }

    fun shutdown() {
        running = false
        try { stream?.close() } catch (_: Throwable) {}
        try { inj?.releaseAll() } catch (_: Throwable) {}
    }

    private fun norm(code: Int): Float {
        val r = range[code] ?: return 0f
        val v = raw[code] ?: return 0f
        val span = (r[1] - r[0]).toFloat()
        if (span <= 0f) return 0f
        return ((v - r[0]) / span) * 2f - 1f
    }

    fun monitorText(): String {
        val sb = StringBuilder(status).append('\n')
        for (s in sticks) {
            sb.append("%s  x=%+.2f y=%+.2f %s\n".format(s.name, norm(s.ax), norm(s.ay), if (s.pid >= 0) "FLICK" else ""))
        }
        sb.append("fingers down: ").append(inj?.count() ?: 0).append('\n')
        synchronized(recent) { for (r in recent) sb.append(r).append('\n') }
        return sb.toString()
    }

    override fun run() {
        try {
            loop()
        } catch (t: Throwable) {
            if (running) status = "ERROR: $t"
        } finally {
            try { inj?.releaseAll() } catch (_: Throwable) {}
            if (status.startsWith("Running")) status = "stopped"
        }
    }

    private fun loop() {
        val injector = TouchInjector()
        inj = injector
        val devs = DeviceScanner.scan()
        val dev = DeviceScanner.pickController(devs, forcedPath)
        if (dev == null) {
            status = "ERROR: no controller found. Devices:\n" + devs.joinToString("\n")
            return
        }
        for ((code, arr) in dev.abs) {
            range[code] = intArrayOf(arr[1], arr[2])
            raw[code] = arr[0]
        }
        val right = when (rightAxes) {
            "z" -> 2 to 5
            "rx" -> 3 to 4
            else -> if (dev.abs.containsKey(9) || dev.abs.containsKey(10)) 2 to 5
            else if (dev.abs.containsKey(3) && dev.abs.containsKey(4)) 3 to 4
            else 2 to 5
        }
        for (o in stickCfg) {
            val isRight = o.optString("side") == "right"
            val (ax, ay) = if (isRight) right else (0 to 1)
            sticks.add(Stick(o.optString("name"), ax, ay, o.optDouble("x").toFloat(), o.optDouble("y").toFloat()))
        }
        status = "Running on ${dev.name} (${dev.path}), right stick axes %02x/%02x".format(right.first, right.second)

        val buf = ByteArray(24 * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(dev.path).use { s ->
            stream = s
            while (running) {
                val n = s.read(buf)
                if (n <= 0) break
                var off = 0
                while (off + 24 <= n) {
                    val type = bb.getShort(off + 16).toInt() and 0xffff
                    val code = bb.getShort(off + 18).toInt() and 0xffff
                    val value = bb.getInt(off + 20)
                    handle(injector, type, code, value)
                    off += 24
                }
            }
        }
    }

    private fun handle(injector: TouchInjector, type: Int, code: Int, value: Int) {
        when (type) {
            1 -> { // EV_KEY
                if (value == 2) return
                if (value == 1) {
                    keySeq++
                    lastKey = "$keySeq:$code"
                }
                note("button 0x%03x %s".format(code, if (value == 1) "down" else "up"))
                for (t in taps) {
                    if (t.code != code) continue
                    if (value == 1 && t.pid < 0) {
                        t.pid = injector.down(t.x, t.y)
                    } else if (value == 0 && t.pid >= 0) {
                        injector.up(t.pid)
                        t.pid = -1
                    }
                }
            }
            3 -> raw[code] = value // EV_ABS
            0 -> if (code == 0) for (s in sticks) stick(injector, s) // SYN_REPORT
        }
    }

    private fun stick(injector: TouchInjector, s: Stick) {
        val x = norm(s.ax)
        val y = norm(s.ay)
        if (x == s.lastX && y == s.lastY) return
        s.lastX = x
        s.lastY = y
        val mag = hypot(x, y)
        if (s.pid < 0) {
            if (mag >= dead) {
                val ux = x / mag
                s.sx = (s.bx + ux * reach).coerceIn(1f, screenW - 1f)
                s.sy = s.by.coerceIn(1f, screenH - 1f)
                s.pid = injector.down(s.sx, s.sy)
                note("${s.name} flick at ${s.sx.toInt()},${s.sy.toInt()}")
            }
        } else if (mag < dead * 0.6f) {
            injector.up(s.pid)
            s.pid = -1
        } else {
            val m = min(mag, 1f) * radius / mag
            val tx = (s.sx + x * m).coerceIn(1f, screenW - 1f)
            val ty = (s.sy + y * m).coerceIn(1f, screenH - 1f)
            injector.move(s.pid, tx, ty)
        }
    }
}

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
    @Volatile private var nEvents = 0
    @Volatile private var nKeys = 0

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

    private val recent = ArrayDeque<String>()

    /** One input node being read. Several are read at once, because system mappers
     *  (Lenovo's included) may hide the real controller behind a virtual one. */
    private inner class Reader(val dev: DeviceInfo) : Thread("reader-" + dev.path) {
        val raw = HashMap<Int, Int>()
        val range = HashMap<Int, IntArray>()
        @Volatile var events = 0
        var stream: FileInputStream? = null

        init {
            for ((code, arr) in dev.abs) {
                range[code] = intArrayOf(arr[1], arr[2])
                raw[code] = arr[0]
            }
        }

        fun norm(code: Int): Float {
            val r = range[code] ?: return 0f
            val v = raw[code] ?: return 0f
            val span = (r[1] - r[0]).toFloat()
            if (span <= 0f) return 0f
            return ((v - r[0]) / span) * 2f - 1f
        }

        override fun run() {
            val buf = ByteArray(24 * 64)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            try {
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
                            events++
                            handle(this, type, code, value)
                            off += 24
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) note("${dev.path} stopped: $t")
            }
        }
    }

    private val readers = ArrayList<Reader>()
    @Volatile private var active: Reader? = null

    private var inj: TouchInjector? = null

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
        for (r in readers) try { r.stream?.close() } catch (_: Throwable) {}
        try { inj?.releaseAll() } catch (_: Throwable) {}
    }

    fun monitorText(): String {
        val sb = StringBuilder(status).append('\n')
        val a = active
        for (s in sticks) {
            val x = a?.norm(s.ax) ?: 0f
            val y = a?.norm(s.ay) ?: 0f
            sb.append("%s  x=%+.2f y=%+.2f %s\n".format(s.name, x, y, if (s.pid >= 0) "FLICK" else ""))
        }
        val i = inj
        sb.append("controller events read: $nEvents (buttons: $nKeys)\n")
        for (r in readers) sb.append("  ${r.dev.path} \"${r.dev.name}\": ${r.events} events\n")
        sb.append("fingers down: ").append(i?.count() ?: 0)
            .append("   touches sent: ").append(i?.sent ?: 0)
            .append("   failed: ").append(i?.failed ?: 0).append('\n')
        if (i != null && i.lastError.isNotEmpty()) sb.append("last touch error: ").append(i.lastError).append('\n')
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
        val cands = DeviceScanner.candidates(devs, forcedPath)
        if (cands.isEmpty()) {
            status = "ERROR: no controller found. Devices:\n" + devs.joinToString("\n")
            return
        }
        val main = DeviceScanner.pickController(cands, forcedPath) ?: cands[0]
        val right = when (rightAxes) {
            "z" -> 2 to 5
            "rx" -> 3 to 4
            else -> if (main.abs.containsKey(9) || main.abs.containsKey(10)) 2 to 5
            else if (main.abs.containsKey(3) && main.abs.containsKey(4)) 3 to 4
            else 2 to 5
        }
        for (o in stickCfg) {
            val isRight = o.optString("side") == "right"
            val (ax, ay) = if (isRight) right else (0 to 1)
            sticks.add(Stick(o.optString("name"), ax, ay, o.optDouble("x").toFloat(), o.optDouble("y").toFloat()))
        }
        for (d in cands) readers.add(Reader(d))
        active = readers.firstOrNull { it.dev === main }
        status = "Running: listening to ${readers.size} input device(s), main = ${main.name}, right stick axes %02x/%02x"
            .format(right.first, right.second)
        for (r in readers) r.start()
        for (r in readers) r.join()
    }

    @Synchronized
    private fun handle(r: Reader, type: Int, code: Int, value: Int) {
        val injector = inj ?: return
        nEvents++
        if (type == 1) nKeys++
        when (type) {
            1 -> { // EV_KEY
                if (value == 2) return
                if (value == 1) {
                    keySeq++
                    lastKey = "$keySeq:$code"
                }
                note("${r.dev.path} button 0x%03x %s".format(code, if (value == 1) "down" else "up"))
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
            3 -> { // EV_ABS
                r.raw[code] = value
                active = r
            }
            0 -> if (code == 0 && active === r) for (s in sticks) stick(injector, r, s) // SYN_REPORT
        }
    }

    private fun stick(injector: TouchInjector, r: Reader, s: Stick) {
        val x = r.norm(s.ax)
        val y = r.norm(s.ay)
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

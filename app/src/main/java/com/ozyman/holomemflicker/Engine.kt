package com.ozyman.holomemflicker

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.min

/**
 * Reads the controller straight from /dev/input (works as the shell user)
 * and turns it into touches, using one of several MODES (Live / Park / Minigame):
 *  - tap points: a button held = a finger held at that spot (several per button allowed)
 *  - sticks, per mode:
 *      flick    : finger down at the base (shifted sideways by push direction), follows the stick
 *      joystick : same, without the sideways shift - a virtual joystick for walking
 *      camera   : continuous swipe from an anchor while the stick is held (re-anchors at the edge, see CameraSwipe)
 *      off
 *  - combos: hold the MODIFIER (Select). Select+L1 = overlay on/off, Select+R1 = next mode.
 *    Buttons pressed while Select is held never tap. Select alone taps (on release) if mapped.
 */
class Engine(cfg: JSONObject) : Thread("flicker-engine") {

    /** A tap point; each press lands at a random spot inside radius [r]. */
    private class Tap(val code: Int, val x: Float, val y: Float, val r: Float) {
        var pid = -1
    }

    private val rng = java.util.Random()

    /** Uniform random point in the tap circle. */
    private fun spot(t: Tap): FloatArray {
        if (t.r <= 1f) return floatArrayOf(t.x, t.y)
        val a = rng.nextDouble() * Math.PI * 2
        val d = t.r * Math.sqrt(rng.nextDouble())
        return floatArrayOf(
            (t.x + d * Math.cos(a)).toFloat().coerceIn(1f, screenW - 1f),
            (t.y + d * Math.sin(a)).toFloat().coerceIn(1f, screenH - 1f)
        )
    }

    private class Stick(
        val name: String, val type: String, val ax: Int, val ay: Int,
        val bx: Float, val by: Float, val radius: Float, val reach: Float, val dead: Float
    ) {
        var pid = -1
        var sx = 0f
        var sy = 0f
        var px = 0f
        var py = 0f
        var lastX = 0f
        var lastY = 0f
        // bounce guard: direction of the last flick and when it ended
        var dirX = 0f
        var dirY = 0f
        var upAt = 0L
        /** camera sticks: the swipe state (see CameraSwipe) */
        var cam: CameraSwipe? = null
    }

    private class Mode(val name: String, val taps: List<Tap>, val stickCfg: List<JSONObject>) {
        val sticks = ArrayList<Stick>()
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
    private val screenW = cfg.optDouble("screenW", 2560.0).toFloat()
    private val screenH = cfg.optDouble("screenH", 1600.0).toFloat()
    private val modKey = cfg.optInt("modKey", 0x13a)
    private val overlayKey = cfg.optInt("overlayKey", 0x136)
    private val modeKey = cfg.optInt("modeKey", 0x137)
    private val editKey = cfg.optInt("editKey", 0x13b)

    private val modes = ArrayList<Mode>()
    @Volatile var modeIdx = 0
        private set
    @Volatile var overlayOn = false
        private set
    @Volatile var modeSeq = 0
        private set
    @Volatile var editing = false
        private set

    private var modHeld = false
    private var comboUsed = false
    private val suppressed = HashSet<Int>()

    private val recent = ArrayDeque<String>()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    // ---- battery: change counter (overlay long-polls it), on-demand camera ticker, idle sleep ----
    private val seqLock = Object()
    private var changeSeq = 0L
    private var ticker: ScheduledFuture<*>? = null
    @Volatile var sleeping = false
        private set
    @Volatile private var lastActive = nowMs()
    private var idleCheck: ScheduledFuture<*>? = null
    private val idleMs = cfg.optLong("idleMs", IDLE_MS)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** Starts the sleep countdown (the engine thread does this once it's listening; tests call it directly). */
    fun startIdleTimer() = scheduleIdleCheck(idleMs)

    /** For tests: a controller button pressed / released, as if read from the device. */
    fun simulateKey(code: Int, down: Boolean) {
        synchronized(this) {
            if (down) markActive()
            if (down) pressed.add(code) else pressed.remove(code)
        }
        bump()
    }

    /** Something the overlay shows changed: wake anyone waiting in [waitChange]. */
    private fun bump() {
        synchronized(seqLock) {
            changeSeq++
            seqLock.notifyAll()
        }
    }

    /** Blocks until the state changes past [since] or [timeoutMs] passes. Returns the current change number. */
    fun waitChange(since: Long, timeoutMs: Long): Long {
        synchronized(seqLock) {
            val end = nowMs() + timeoutMs
            while (changeSeq == since && running) {
                val left = end - nowMs()
                if (left <= 0) break
                seqLock.wait(left)
            }
            return changeSeq
        }
    }

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
    private val pressed = HashSet<Int>()
    @Volatile private var rAx = 2
    @Volatile private var rAy = 5
    @Volatile private var active: Reader? = null
    private var inj: TouchInjector? = null

    init {
        modeIdx = cfg.optInt("mode", 0)
        val ma = cfg.optJSONArray("modes")
        if (ma != null && ma.length() > 0) {
            for (i in 0 until ma.length()) modes.add(parseMode(ma.getJSONObject(i)))
        } else {
            // old single-layout config
            modes.add(parseMode(cfg.put("name", "Live")))
        }
        if (modeIdx !in modes.indices) modeIdx = 0
    }

    private fun parseMode(o: JSONObject): Mode {
        val taps = ArrayList<Tap>()
        val ta = o.optJSONArray("taps")
        if (ta != null) for (i in 0 until ta.length()) {
            val t = ta.getJSONObject(i)
            taps.add(Tap(t.optInt("code"), t.optDouble("x").toFloat(), t.optDouble("y").toFloat(), t.optDouble("r", 0.0).toFloat()))
        }
        val sc = ArrayList<JSONObject>()
        val sa = o.optJSONArray("sticks")
        if (sa != null) for (i in 0 until sa.length()) sc.add(sa.getJSONObject(i))
        return Mode(o.optString("name", "Mode"), taps, sc)
    }

    private fun note(s: String) {
        synchronized(recent) {
            recent.addLast(s)
            while (recent.size > 10) recent.removeFirst()
        }
    }

    private val mode: Mode get() = modes[modeIdx]

    /** Snapshot for the controller test screen and the overlay. */
    @Synchronized
    fun stateJson(): String = stateJson(-1)

    /** [traceSince]: only touch-log entries newer than this (-1 = the whole log). */
    @Synchronized
    fun stateJson(traceSince: Int): String {
        val o = JSONObject()
        o.put("seq", synchronized(seqLock) { changeSeq })
        o.put("sleeping", sleeping)
        o.put("keys", JSONArray(pressed.toList()))
        val axes = JSONObject()
        val a = active
        if (a != null) for (c in a.range.keys) axes.put(c.toString(), a.norm(c).toDouble())
        o.put("axes", axes)
        o.put("rx", rAx)
        o.put("ry", rAy)
        o.put("device", a?.dev?.name ?: "")
        o.put("fingers", inj?.count() ?: 0)
        o.put("mode", modeIdx)
        o.put("modeName", if (modes.isEmpty()) "" else mode.name)
        o.put("overlay", overlayOn)
        o.put("modeSeq", modeSeq)
        o.put("editing", editing)
        o.put("lastKey", lastKey)
        o.put("running", status.startsWith("Running"))
        if (overlayOn && !sleeping) inj?.let { i ->
            val tr = JSONArray()
            val log = i.trace()
            // a log that ends below what the overlay has seen means the engine restarted: send it all
            val since = if (log.isNotEmpty() && log.last()[0].toInt() < traceSince) -1 else traceSince
            for (e in log) if (e[0].toInt() > since) tr.put(JSONArray().put(e[0].toInt()).put(e[1].toInt()).put(e[2].toDouble()).put(e[3].toDouble()).put(e[4].toInt()))
            o.put("trace", tr)
        }
        return o.toString()
    }

    fun shutdown() {
        running = false
        bump()
        for (r in readers) try { r.stream?.close() } catch (_: Throwable) {}
        try { inj?.releaseAll() } catch (_: Throwable) {}
        timer.shutdownNow()
    }

    @Synchronized
    fun setMode(i: Int) {
        if (i !in modes.indices) return
        releaseMode()
        modeIdx = i
        modeSeq++
        note("mode → ${mode.name}")
        bump()
    }

    @Synchronized
    fun setOverlay(on: Boolean) {
        overlayOn = on
        bump()
    }

    /** In-game editor open: nothing reaches the game. */
    @Synchronized
    fun setEditing(on: Boolean) {
        if (on) releaseMode()
        editing = on
        bump()
    }

    private fun releaseMode() {
        val injector = inj ?: return
        for (t in mode.taps) if (t.pid >= 0) { injector.up(t.pid); t.pid = -1 }
        for (s in mode.sticks) {
            if (s.pid >= 0) { injector.up(s.pid); s.pid = -1 }
            s.cam?.reset()
        }
    }

    fun monitorText(): String {
        val sb = StringBuilder(status).append('\n')
        sb.append("Mode: ").append(if (modes.isEmpty()) "-" else mode.name)
            .append("   overlay: ").append(if (overlayOn) "on" else "off").append('\n')
        val a = active
        if (modes.isNotEmpty()) for (s in mode.sticks) {
            val x = a?.norm(s.ax) ?: 0f
            val y = a?.norm(s.ay) ?: 0f
            sb.append("%s [%s]  x=%+.2f y=%+.2f %s\n".format(s.name, s.type, x, y, if (s.pid >= 0) "ACTIVE" else ""))
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
            timer.shutdownNow()
        }
    }

    private fun loop() {
        val injector = TouchInjector()
        injector.onRecord = { bump() }
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
        rAx = right.first
        rAy = right.second
        for (m in modes) for (o in m.stickCfg) {
            val isRight = o.optString("side") == "right"
            val (ax, ay) = if (isRight) right else (0 to 1)
            m.sticks.add(
                Stick(
                    o.optString("name", if (isRight) "Right stick" else "Left stick"),
                    o.optString("type", "flick"), ax, ay,
                    o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    o.optDouble("radius", 120.0).toFloat(),
                    o.optDouble("reach", 0.0).toFloat(),
                    o.optDouble("dead", 0.35).toFloat()
                )
            )
        }
        for (d in cands) readers.add(Reader(d))
        active = readers.firstOrNull { it.dev === main }
        status = "Running: listening to ${readers.size} input device(s), main = ${main.name}, right stick axes %02x/%02x"
            .format(right.first, right.second)
        // the 60 Hz ticker only runs while a camera stick is pushed (see startTicker)
        startIdleTimer()
        bump()
        for (r in readers) r.start()
        for (r in readers) r.join()
    }

    @Synchronized
    private fun handle(r: Reader, type: Int, code: Int, value: Int) {
        if (!running) return
        val injector = inj ?: return
        nEvents++
        if (type == 1) nKeys++
        if (isActivity(r, type, code, value)) markActive()
        when (type) {
            1 -> onKey(injector, r.dev.path, code, value)
            3 -> { // EV_ABS
                r.raw[code] = value
                active = r
            }
            0 -> if (code == 0 && active === r && !editing) { // SYN_REPORT
                for (s in mode.sticks) stick(injector, r, s)
                if (ticker == null && mode.sticks.any { it.type == "camera" && hypot(r.norm(it.ax), r.norm(it.ay)) >= it.dead }) startTicker()
            }
        }
    }

    /** A real press or a real stick push - not the resting jitter some sticks send nonstop. */
    private fun isActivity(r: Reader, type: Int, code: Int, value: Int): Boolean = when (type) {
        1 -> value != 2
        3 -> when (code) {
            0, 1, rAx, rAy -> { r.raw[code] = value; Math.abs(r.norm(code)) > 0.3f }
            0x10, 0x11 -> value != 0 // d-pad
            else -> false
        }
        else -> false
    }

    /** Controller used: wake up if asleep, and push the sleep timer back. */
    private fun markActive() {
        lastActive = nowMs()
        if (sleeping) {
            sleeping = false
            note("awake")
            scheduleIdleCheck(idleMs)
            bump()
        }
    }

    private fun scheduleIdleCheck(delay: Long) {
        idleCheck?.cancel(false)
        idleCheck = try {
            timer.schedule(Runnable { idleCheck() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
    }

    /** Fires once per idle period (no polling): sleeps if the controller has been left alone. */
    private fun idleCheck() {
        synchronized(this) {
            if (!running || sleeping) return
            val quiet = nowMs() - lastActive
            val fingers = inj?.count() ?: 0
            if (quiet < idleMs || fingers > 0 || editing) {
                scheduleIdleCheck(if (quiet < idleMs) idleMs - quiet else idleMs)
                return
            }
            sleeping = true
            stopTicker()
            note("sleeping (no controller input for ${idleMs / 1000}s)")
        }
        bump()
    }

    private fun startTicker() {
        ticker = try {
            timer.scheduleAtFixedRate(Runnable { tick() }, 0, 16, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
    }

    private fun stopTicker() {
        ticker?.cancel(false)
        ticker = null
    }

    /**
     * Every real press / release goes through here and wakes the overlay right away
     * (Select+L1 overlay, editor assignments, pressed-key display) - not on the next timeout.
     */
    private fun onKey(injector: TouchInjector?, src: String, code: Int, value: Int) {
        if (value == 2) return // auto-repeat
        key(injector, src, code, value)
        bump()
    }

    /** For tests: a controller button event exactly as the device reader delivers it (no touch injector). */
    fun feedKeyForTest(code: Int, value: Int) {
        synchronized(this) {
            if (value == 1) markActive()
            onKey(inj, "test", code, value)
        }
    }

    private fun key(injector: TouchInjector?, src: String, code: Int, value: Int) {
        if (value == 2) return
        if (value == 1) pressed.add(code) else pressed.remove(code)
        if (value == 1) {
            keySeq++
            lastKey = "$keySeq:$code"
        }
        note("$src button 0x%03x %s".format(code, if (value == 1) "down" else "up"))

        // ---- modifier / combos ----
        if (code == modKey) {
            if (value == 1) {
                modHeld = true
                comboUsed = false
            } else {
                modHeld = false
                if (!comboUsed && !editing && injector != null) quickTap(injector, code) // Select alone = its own tap
            }
            return
        }
        if (value == 1 && modHeld) {
            comboUsed = true
            suppressed.add(code)
            when (code) {
                overlayKey -> {
                    overlayOn = !overlayOn
                    note("overlay ${if (overlayOn) "on" else "off"}")
                }
                modeKey -> setMode((modeIdx + 1) % modes.size)
                editKey -> {
                    setEditing(!editing)
                    note("editor ${if (editing) "open" else "closed"}")
                }
            }
            return
        }
        if (value == 0 && suppressed.remove(code)) return
        if (editing || injector == null) return

        // ---- normal taps ----
        for (t in mode.taps) {
            if (t.code != code) continue
            if (value == 1 && t.pid < 0) {
                val p = spot(t)
                t.pid = injector.down(p[0], p[1])
            } else if (value == 0 && t.pid >= 0) {
                injector.up(t.pid)
                t.pid = -1
            }
        }
    }

    /** Short tap at every point mapped to [code] in the current mode. */
    private fun quickTap(injector: TouchInjector, code: Int) {
        val ids = mode.taps.filter { it.code == code }.map { val p = spot(it); injector.down(p[0], p[1]) }
        if (ids.isEmpty()) return
        timer.schedule(Runnable {
            synchronized(this@Engine) { for (id in ids) injector.up(id) }
        }, 70, TimeUnit.MILLISECONDS)
    }

    private fun stick(injector: TouchInjector, r: Reader, s: Stick) {
        val x = r.norm(s.ax)
        val y = r.norm(s.ay)
        if (x == s.lastX && y == s.lastY) return
        s.lastX = x
        s.lastY = y
        when (s.type) {
            "flick", "joystick" -> wheel(injector, s, x, y)
            // camera handled by the ticker
        }
    }

    /** Steering-wheel behaviour (flick and joystick). */
    private fun wheel(injector: TouchInjector, s: Stick, x: Float, y: Float) {
        val mag = hypot(x, y)
        if (s.pid < 0) {
            if (mag >= s.dead) {
                val ux = x / mag
                if (s.type == "flick" && System.currentTimeMillis() - s.upAt < 180) {
                    // stick springing back past centre after a hard flick: ignore the opposite direction
                    val dot = ux * s.dirX + (y / mag) * s.dirY
                    if (dot < -0.3f) return
                }
                s.sx = (s.bx + ux * s.reach).coerceIn(1f, screenW - 1f)
                s.sy = s.by.coerceIn(1f, screenH - 1f)
                s.pid = injector.down(s.sx, s.sy)
                if (s.type == "flick") note("${s.name} flick at ${s.sx.toInt()},${s.sy.toInt()}")
            }
        } else if (mag < s.dead * 0.6f) {
            injector.up(s.pid)
            s.pid = -1
            s.upAt = System.currentTimeMillis()
        } else {
            s.dirX = x / mag
            s.dirY = y / mag
            val m = min(mag, 1f) * s.radius / mag
            val tx = (s.sx + x * m).coerceIn(1f, screenW - 1f)
            val ty = (s.sy + y * m).coerceIn(1f, screenH - 1f)
            injector.move(s.pid, tx, ty)
        }
    }

    /** 60 Hz ticker: camera sticks keep swiping while held. */
    private fun tick() {
        try {
            synchronized(this) {
                val injector = inj ?: return stopTicker()
                val a = active ?: return stopTicker()
                if (modes.isEmpty() || editing || !running) { stopTicker(); return }
                for (s in mode.sticks) {
                    if (s.type != "camera") continue
                    val x = a.norm(s.ax)
                    val y = a.norm(s.ay)
                    val gap = if (mode.name == "Minigame") CameraSwipe.GAP_MINIGAME_MS else CameraSwipe.GAP_MS
                    val cam = s.cam ?: CameraSwipe(s.bx, s.by, screenW, screenH, s.radius, s.dead, gap).also { s.cam = it }
                    if (!cam.down && s.pid >= 0) { injector.up(s.pid); s.pid = -1 } // out of sync: start clean
                    when (val op = cam.step(x, y, System.currentTimeMillis())) {
                        is CameraSwipe.Op.Down -> s.pid = injector.down(op.x, op.y)
                        is CameraSwipe.Op.Move -> if (s.pid >= 0) injector.move(s.pid, op.x, op.y)
                        CameraSwipe.Op.Up -> if (s.pid >= 0) { injector.up(s.pid); s.pid = -1 }
                        null -> {}
                    }
                }
                // no camera finger down any more: let the CPU rest until the next push
                // (a camera stick waiting to touch down again after a re-anchor still counts)
                val now = System.currentTimeMillis()
                if (mode.sticks.none { it.type == "camera" && (it.pid >= 0 || it.cam?.busy(now) == true) }) stopTicker()
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** No controller input for this long = sleep (overlay hidden, nothing ticking). */
        const val IDLE_MS = 60_000L
    }
}

package com.ozyman.holomemflicker

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.TreeMap

/**
 * Runs inside the Shizuku (shell-uid) process. Keeps ONE multi-touch stream so
 * taps, holds and flicks can overlap without cancelling each other.
 */
class TouchInjector {
    private val target: Any
    private val inject: Method
    private val active = TreeMap<Int, FloatArray>()
    private var downTime = 0L
    private val deviceId: Int

    init {
        var t: Any? = null
        var m: Method? = null
        try {
            val c = Class.forName("android.hardware.input.InputManagerGlobal")
            t = c.getMethod("getInstance").invoke(null)
            m = c.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        } catch (_: Throwable) {
        }
        if (t == null || m == null) {
            val c = Class.forName("android.hardware.input.InputManager")
            t = c.getMethod("getInstance").invoke(null)
            m = c.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }
        target = t!!
        inject = m!!
        deviceId = try {
            InputDevice.getDeviceIds().firstOrNull { id ->
                InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
            } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    @Volatile var sent = 0
    @Volatile var failed = 0
    @Volatile var lastError = ""

    @Synchronized
    fun count(): Int = active.size

    // Recent touch points for the overlay's tap / swipe trails: [seq, stroke, x, y, kind(0 down,1 move,2 up)]
    private val log = ArrayDeque<FloatArray>()
    private val strokeOf = HashMap<Int, Int>()
    private var seq = 0
    private var strokes = 0

    /** Called after every logged touch point (the engine uses it to wake the overlay). */
    @Volatile var onRecord: (() -> Unit)? = null

    private fun record(id: Int, x: Float, y: Float, kind: Int) {
        if (kind == 0) strokeOf[id] = ++strokes
        val st = strokeOf[id] ?: return
        log.addLast(floatArrayOf((++seq).toFloat(), st.toFloat(), x, y, kind.toFloat()))
        while (log.size > 160) log.removeFirst()
        onRecord?.invoke()
    }

    /** Recently logged touch points, oldest first. */
    @Synchronized
    fun trace(): List<FloatArray> = log.toList()

    @Synchronized
    fun down(x: Float, y: Float): Int {
        var id = 0
        while (active.containsKey(id)) id++
        val now = SystemClock.uptimeMillis()
        if (active.isEmpty()) downTime = now
        active[id] = floatArrayOf(x, y)
        record(id, x, y, 0)
        val action = if (active.size == 1) {
            MotionEvent.ACTION_DOWN
        } else {
            val idx = active.keys.indexOf(id)
            MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(action, now)
        return id
    }

    @Synchronized
    fun move(id: Int, x: Float, y: Float) {
        val p = active[id] ?: return
        if (p[0] == x && p[1] == y) return
        p[0] = x
        p[1] = y
        record(id, x, y, 1)
        send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis())
    }

    @Synchronized
    fun up(id: Int) {
        if (!active.containsKey(id)) return
        val action = if (active.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            val idx = active.keys.indexOf(id)
            MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(action, SystemClock.uptimeMillis())
        active[id]?.let { record(id, it[0], it[1], 2) }
        active.remove(id)
    }

    @Synchronized
    fun releaseAll() {
        for (id in active.keys.toList()) up(id)
    }

    private fun send(action: Int, now: Long) {
        val entries = active.entries.toList()
        val n = entries.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().also {
                it.id = entries[i].key
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also {
                it.x = entries[i].value[0]
                it.y = entries[i].value[1]
                it.pressure = 1f
                it.size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            downTime, now, action, n, props, coords,
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            val ok = inject.invoke(target, ev, 0)
            sent++
            if (ok == false) {
                failed++
                lastError = "system refused the touch"
            }
        } catch (t: Throwable) {
            failed++
            lastError = (t.cause ?: t).toString()
        } finally {
            ev.recycle()
        }
    }
}

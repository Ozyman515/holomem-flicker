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

    @Synchronized
    fun down(x: Float, y: Float): Int {
        var id = 0
        while (active.containsKey(id)) id++
        val now = SystemClock.uptimeMillis()
        if (active.isEmpty()) downTime = now
        active[id] = floatArrayOf(x, y)
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

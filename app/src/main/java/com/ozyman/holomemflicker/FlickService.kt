package com.ozyman.holomemflicker

import android.os.Build
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/** Shizuku user service: runs as the shell user, so it can read /dev/input and inject touches. */
class FlickService : IFlickService.Stub() {
    private var engine: Engine? = null

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (_: Throwable) {
        }
    }

    override fun destroy() {
        engine?.shutdown()
        exitProcess(0)
    }

    override fun start(configJson: String?): String {
        engine?.shutdown()
        val e = Engine(JSONObject(configJson ?: "{}"))
        engine = e
        e.start()
        var waited = 0
        while (e.status == "starting" && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
        return e.status
    }

    override fun stop() {
        engine?.shutdown()
        engine = null
    }

    override fun status(): String = engine?.status ?: "stopped"

    override fun monitor(): String = engine?.monitorText() ?: "stopped"

    override fun lastKey(): String = engine?.lastKey ?: ""

    override fun testTouch(x: Float, y: Float): String = try {
        val t = TouchInjector()
        val id = t.down(x, y)
        Thread.sleep(60)
        t.up(id)
        if (t.failed > 0) "touch failed: ${t.lastError}" else "touch sent"
    } catch (e: Throwable) {
        "touch error: ${e.cause ?: e}"
    }

    /** Download the APK as the shell user and install it with pm (no installer prompt). */
    override fun installUpdate(url: String?): String = try {
        val dest = java.io.File("/data/local/tmp/holomem-flicker-update.apk")
        var conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        var hops = 0
        while (conn.responseCode in 300..399 && hops < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = java.net.URL(java.net.URL(url), next).openConnection() as java.net.HttpURLConnection
            hops++
        }
        if (conn.responseCode != 200) throw RuntimeException("download HTTP ${conn.responseCode}")
        conn.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
        conn.disconnect()
        val p = ProcessBuilder("pm", "install", "-r", dest.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        dest.delete()
        out
    } catch (t: Throwable) {
        "update failed: $t"
    }

    override fun listDevices(): String = try {
        DeviceScanner.scan().joinToString("\n")
    } catch (t: Throwable) {
        "error: $t"
    }
}

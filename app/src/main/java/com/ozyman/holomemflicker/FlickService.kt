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

    /** Unsigned installs are switched off: only [installVerified] installs anything. */
    override fun installUpdate(url: String?): String =
        "refused: this version only installs signed updates"

    /**
     * Download the APK as the shell user, check it against the SHA-256 from the SIGNED
     * manifest (the app verified the signature first), and only then install it with pm.
     */
    override fun installVerified(url: String?, sha256: String?): String = try {
        val u = url ?: ""
        val want = (sha256 ?: "").lowercase()
        if (!u.startsWith("https://github.com/Ozyman515/holomem-flicker/releases/download/")) throw RuntimeException("unexpected download address")
        if (!Regex("^[0-9a-f]{64}$").matches(want)) throw RuntimeException("bad fingerprint")
        val dest = java.io.File("/data/local/tmp/holomem-flicker-update.apk")
        dest.delete()
        var conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        var hops = 0
        while (conn.responseCode in 300..399 && hops < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            val nu = java.net.URL(java.net.URL(u), next)
            if (nu.protocol != "https") throw RuntimeException("insecure redirect")
            conn = nu.openConnection() as java.net.HttpURLConnection
            hops++
        }
        if (conn.responseCode != 200) throw RuntimeException("download HTTP ${conn.responseCode}")
        conn.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
        conn.disconnect()
        val got = UpdateVerifier.sha256Hex(dest)
        if (got != want) {
            dest.delete()
            throw RuntimeException("the download doesn't match the signed fingerprint - not installing")
        }
        val p = ProcessBuilder("pm", "install", "-r", dest.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        dest.delete()
        out
    } catch (t: Throwable) {
        "update failed: ${t.message ?: t}"
    }

    override fun inputState(): String = engine?.stateJson() ?: "{}"

    override fun setMode(mode: Int) {
        engine?.setMode(mode)
    }

    override fun setOverlay(on: Boolean) {
        engine?.setOverlay(on)
    }

    override fun setEditing(on: Boolean) {
        engine?.setEditing(on)
    }

    /** Shell can grant "Display over other apps" without a settings trip. */
    override fun grantOverlay(pkg: String?): String = try {
        val p = ProcessBuilder("appops", "set", pkg ?: "", "SYSTEM_ALERT_WINDOW", "allow")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.isEmpty()) "ok" else out
    } catch (t: Throwable) {
        "error: $t"
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    /** Installed packages that could be Lenovo's key mapper / game assistant. */
    override fun findPackages(): String = try {
        run("pm", "list", "packages").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { Regex("lenovo|zui|legion|game|keymap|gamepad|assist", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .filter { !it.startsWith("com.ozyman") }
            .sorted()
            .joinToString("\n")
    } catch (t: Throwable) {
        "error: $t"
    }

    override fun forceStop(pkg: String?): String = try {
        if (pkg.isNullOrBlank() || !Regex("^[A-Za-z0-9_.]+$").matches(pkg)) "bad package"
        else run("am", "force-stop", pkg).ifEmpty { "stopped $pkg" }
    } catch (t: Throwable) {
        "error: $t"
    }

    override fun listDevices(): String = try {
        DeviceScanner.scan().joinToString("\n")
    } catch (t: Throwable) {
        "error: $t"
    }
}

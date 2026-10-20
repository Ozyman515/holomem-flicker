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

    override fun listDevices(): String = try {
        DeviceScanner.scan().joinToString("\n")
    } catch (t: Throwable) {
        "error: $t"
    }
}

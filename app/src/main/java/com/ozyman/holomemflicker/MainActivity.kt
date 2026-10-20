package com.ozyman.holomemflicker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.view.View
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private var service: IFlickService? = null
    private var pendingStart = false
    private var pendingInstall = false
    private var updateUrl: String? = null
    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var shizukuText: TextView
    private lateinit var statusText: TextView
    private lateinit var layoutText: TextView
    private lateinit var monitorText: TextView
    private lateinit var axesBtn: Button
    private lateinit var modeText: TextView
    private lateinit var killText: TextView

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, FlickService::class.java.name))
            .daemon(true)
            .processNameSuffix("engine")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = IFlickService.Stub.asInterface(binder)
            if (pendingInstall) {
                pendingInstall = false
                doInstall()
            } else if (pendingStart) {
                pendingStart = false
                doStart()
            } else {
                statusText.text = safe { service?.status() } ?: "connected"
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            statusText.text = "Engine disconnected"
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshShizuku() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refreshShizuku() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshShizuku() } }

    private val poll = object : Runnable {
        override fun run() {
            val s = service
            if (s != null) monitorText.text = safe { s.monitor() } ?: "-"
            handler.postDelayed(this, 200)
        }
    }

    private fun <T> safe(f: () -> T): T? = try { f() } catch (t: Throwable) { null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        fun label(s: String, size: Float = 16f): TextView = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { col.addView(it) }
        fun slider(name: String, key: String, def: Int, lo: Int, hi: Int, fmt: (Int) -> String) {
            val t = label("")
            val cur = Prefs.getInt(this, key, def)
            t.text = "$name: ${fmt(cur)}"
            col.addView(SeekBar(this).apply {
                max = hi - lo
                progress = cur - lo
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        Prefs.putInt(this@MainActivity, key, p + lo)
                        t.text = "$name: ${fmt(p + lo)}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        label("Holomem Flicker", 26f).setTypeface(null, Typeface.BOLD)
        label("Turn the Lenovo key mapper OFF (or use an empty profile) while this is running, or every press counts twice.", 14f)

        updateText = label("Version v${BuildConfig.VERSION_CODE}")
        button("Check for updates") { checkUpdate() }
        updateBtn = button("Install update") { installUpdate() }.apply { visibility = View.GONE }

        shizukuText = label("Shizuku: checking…")
        button("Grant Shizuku access") { requestShizuku() }

        button("▶ Start flicker") { start() }
        button("■ Stop flicker") { stop() }
        statusText = label("Engine: stopped")

        layoutText = label("")
        button("🎮 Controller test (buttons, sticks, and where touches land)") {
            startActivity(Intent(this, ControllerTestActivity::class.java))
        }

        label("Lenovo mapper", 18f).setTypeface(null, Typeface.BOLD)
        killText = label("")
        updateKillText()
        button("Pick the mapper app to stop on Start") { pickMapper() }
        button("Don't stop any app") {
            Prefs.putStr(this, Prefs.KILL_PKG, "")
            updateKillText()
        }

        label("Modes", 18f).setTypeface(null, Typeface.BOLD)
        label("In game: hold Select and press L1 to show/hide the layout, Select + R1 to switch mode.", 14f)
        modeText = label("")
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in Prefs.MODE_NAMES.withIndex()) {
            modeRow.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { pickMode(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(modeRow)
        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in Prefs.MODE_NAMES.withIndex()) {
            editRow.addView(Button(this).apply {
                text = "Edit $name"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CalibrateActivity::class.java).putExtra("mode", i))
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(editRow)
        button("Show / hide layout overlay now") {
            val sv = service
            if (sv == null) statusText.text = "Start the flicker first."
            else safe { sv.setOverlay(!(JSONObject(sv.inputState()).optBoolean("overlay"))) }
        }

        label("Tuning (press Start again to apply)", 18f).setTypeface(null, Typeface.BOLD)
        slider("Flick length", Prefs.RADIUS, 50, 15, 150) { "%.1f%% of screen width".format(it / 10f) }
        slider("Edge reach (stick pushed sideways)", Prefs.REACH, 18, 0, 40) { "$it% of screen width" }
        slider("Stick threshold (flick)", Prefs.DEAD, 35, 15, 80) { "$it%" }
        slider("Joystick size (Park)", Prefs.JOY, 60, 20, 150) { "%.1f%% of screen width".format(it / 10f) }
        slider("Camera speed (Park)", Prefs.CAM, 10, 2, 40) { "$it" }
        axesBtn = button("") { cycleAxes() }
        updateAxesBtn()

        label("Live input", 18f).setTypeface(null, Typeface.BOLD)
        monitorText = label("-", 13f).apply { typeface = Typeface.MONOSPACE }
        lateinit var target: TextView
        button("Test touch (taps the box below by itself)") {
            val s = service
            if (s == null) {
                target.text = "Start the flicker first."
            } else {
                target.text = "Sending a touch…"
                val loc = IntArray(2)
                target.getLocationOnScreen(loc)
                val x = loc[0] + target.width / 2f
                val y = loc[1] + target.height / 2f
                Thread {
                    Thread.sleep(300)
                    val r = safe { s.testTouch(x, y) } ?: "engine call failed"
                    Thread.sleep(400)
                    runOnUiThread { if (!target.text.startsWith("✓")) target.text = "No touch arrived ($r)" }
                }.start()
            }
        }
        target = label("[ touch test box ]", 18f).apply {
            setBackgroundColor(0xFF2E3A66.toInt())
            setPadding(30, 40, 30, 40)
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) text = "✓ Touch received! Injection works."
                true
            }
        }
        button("List input devices") {
            val s = service
            monitorText.text = if (s == null) "Start the flicker first." else (safe { s.listDevices() } ?: "error")
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF15182A.toInt())
            addView(col)
        })

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        val sb = StringBuilder()
        for ((i, name) in Prefs.MODE_NAMES.withIndex()) {
            val l = Prefs.load(this, i)
            val groups = l.taps.groupBy { it.code }.entries.joinToString(", ") { "${Prefs.buttonName(it.key)}×${it.value.size}" }
            sb.append("$name: $groups · L stick ${l.ltype}, R stick ${l.rtype}\n")
        }
        layoutText.text = sb.toString().trim()
        updateModeText()
        if (service != null) startService(Intent(this, OverlayService::class.java).setAction("reload"))
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun refreshShizuku() {
        shizukuText.text = when {
            safe { Shizuku.pingBinder() } != true -> "Shizuku: NOT running. Open the Shizuku app and start it (wireless debugging)."
            !shizukuReady() -> "Shizuku: running, but this app needs access. Tap Grant."
            else -> "Shizuku: ready ✓"
        }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                refreshShizuku()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
        } catch (t: Throwable) {
            shizukuText.text = "Shizuku error: $t"
        }
        refreshShizuku()
    }

    private fun start() {
        if (!shizukuReady()) {
            refreshShizuku()
            statusText.text = "Shizuku isn't ready yet."
            return
        }
        if (service == null) {
            pendingStart = true
            statusText.text = "Starting engine…"
            try {
                Shizuku.bindUserService(args, conn)
            } catch (t: Throwable) {
                statusText.text = "Bind failed: $t"
            }
        } else {
            doStart()
        }
    }

    private fun doStart() {
        val s = service ?: return
        val kill = Prefs.getStr(this, Prefs.KILL_PKG, "")
        if (kill.isNotEmpty()) safe { s.forceStop(kill) }
        statusText.text = safe { s.start(Prefs.buildConfig(this)) } ?: "Engine call failed"
        if (!android.provider.Settings.canDrawOverlays(this)) safe { s.grantOverlay(packageName) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        safe { startForegroundService(Intent(this, OverlayService::class.java)) }
    }

    private fun stop() {
        safe { stopService(Intent(this, OverlayService::class.java)) }
        safe { service?.stop() }
        safe { Shizuku.unbindUserService(args, conn, true) }
        service = null
        statusText.text = "Engine: stopped"
        monitorText.text = "-"
    }

    private fun updateKillText() {
        val k = Prefs.getStr(this, Prefs.KILL_PKG, "")
        killText.text = if (k.isEmpty()) "Not stopping any app on Start." else "On Start, force-stop: $k"
    }

    private fun pickMapper() {
        val s = service
        if (s == null) {
            killText.text = "Start the flicker once first (it needs Shizuku), then pick."
            return
        }
        val list = (safe { s.findPackages() } ?: "").lines().filter { it.isNotBlank() && !it.startsWith("error") }
        if (list.isEmpty()) {
            killText.text = "No Lenovo / game-assistant apps found."
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Which app is the key mapper?")
            .setItems(list.toTypedArray()) { _, i ->
                Prefs.putStr(this, Prefs.KILL_PKG, list[i])
                updateKillText()
                safe { s.forceStop(list[i]) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMode(i: Int) {
        Prefs.putInt(this, Prefs.MODE, i)
        safe { service?.setMode(i) }
        updateModeText()
    }

    private fun updateModeText() {
        val i = Prefs.getInt(this, Prefs.MODE, 0)
        modeText.text = "Current mode: ${Prefs.MODE_NAMES.getOrElse(i) { "?" }}"
    }

    private fun checkUpdate() {
        updateText.text = "Version v${BuildConfig.VERSION_CODE} — checking for updates…"
        Thread {
            val result: Pair<Int, String?>? = try {
                val c = URL("https://api.github.com/repos/Ozyman515/holomem-flicker/releases/latest")
                    .openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                c.disconnect()
                val tag = o.optString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = o.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                tag to url
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                val mine = BuildConfig.VERSION_CODE
                when {
                    result == null -> updateText.text = "Version v$mine — couldn't reach GitHub."
                    result.first > mine && result.second != null -> {
                        updateUrl = result.second
                        updateText.text = "Version v$mine — update v${result.first} is available!"
                        updateBtn.text = "Install update v${result.first}"
                        updateBtn.visibility = View.VISIBLE
                    }
                    else -> {
                        updateText.text = "Version v$mine — up to date ✓"
                        updateBtn.visibility = View.GONE
                    }
                }
            }
        }.start()
    }

    private fun installUpdate() {
        if (!shizukuReady()) {
            updateText.text = "Updating needs Shizuku running. Start Shizuku first."
            return
        }
        if (service == null) {
            pendingInstall = true
            updateText.text = "Connecting…"
            try {
                Shizuku.bindUserService(args, conn)
            } catch (t: Throwable) {
                updateText.text = "Bind failed: $t"
            }
        } else {
            doInstall()
        }
    }

    private fun doInstall() {
        val s = service ?: return
        val url = updateUrl ?: return
        updateText.text = "Downloading and installing… the app will close when it's done. Reopen it after."
        updateBtn.isEnabled = false
        Thread {
            val r = safe { s.installUpdate(url) } ?: "install call failed"
            runOnUiThread {
                updateBtn.isEnabled = true
                updateText.text = "Update result: $r"
            }
        }.start()
    }

    private fun cycleAxes() {
        val next = when (Prefs.getStr(this, Prefs.AXES, "auto")) {
            "auto" -> "z"
            "z" -> "rx"
            else -> "auto"
        }
        Prefs.putStr(this, Prefs.AXES, next)
        updateAxesBtn()
    }

    private fun updateAxesBtn() {
        val v = when (Prefs.getStr(this, Prefs.AXES, "auto")) {
            "z" -> "Z / RZ"
            "rx" -> "RX / RY"
            else -> "Auto"
        }
        axesBtn.text = "Right stick axes: $v (tap to change if the right stick doesn't move in Live input)"
    }
}

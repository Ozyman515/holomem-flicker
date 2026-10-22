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
    private lateinit var backupText: TextView

    companion object {
        private const val REQ_EXPORT = 41
        private const val REQ_IMPORT = 42
    }

    @Deprecated("plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        backupText.text = try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Prefs.exportAll(this).toByteArray()) }
                    "Backup saved ✓"
                }
                REQ_IMPORT -> {
                    val txt = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val n = Prefs.importAll(this, txt)
                    recreate()
                    "Restored $n settings ✓ (press Start again to apply)"
                }
                else -> return
            }
        } catch (t: Throwable) {
            "Backup error: ${t.message ?: t}"
        }
    }

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

    private val modeBtns = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Raden.apply(this)
        themeKey = Raden.current.key
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 64)
        }
        // Header banner (Raden's teal menu panel)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 40)
            background = Raden.rounded(Raden.TEAL, 44f)
        }
        header.addView(TextView(this).apply {
            text = "Holomem Flicker"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Raden.CREAM)
        })
        header.addView(TextView(this).apply {
            text = "An unofficial fan-made project"
            textSize = 15f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(Raden.CREAM)
        })
        header.addView(TextView(this).apply {
            text = "${Raden.current.name} theme  ·  v${BuildConfig.VERSION_CODE}"
            textSize = 15f
            setTextColor(Raden.PLATINUM)
        })
        header.addView(View(this).apply { background = Raden.rounded(Raden.ORANGE, 6f) },
            LinearLayout.LayoutParams(220, 10).apply { topMargin = 18 })
        header.addView(Button(this).apply {
            text = "🎨  Theme · ${Raden.current.short}"
            Raden.pill(this, Raden.ORANGE)
            setOnClickListener { startActivity(Intent(this@MainActivity, ThemePickerActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 22 })
        col.addView(header)

        // Everything else goes into cream "cards"
        var cur: LinearLayout = col
        fun section(title: String?) {
            cur = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 30, 44, 34)
                Raden.card(this)
            }
            col.addView(cur, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 30 })
            if (title != null) cur.addView(TextView(this).apply {
                text = title
                Raden.heading(this)
                setPadding(0, 0, 0, 8)
            })
        }
        fun label(s: String, size: Float = 16f): TextView = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Raden.INK)
            setPadding(0, 10, 0, 10)
        }.also { cur.addView(it) }
        fun styled(b: Button, fill: Int) = b.also { Raden.pill(it, fill) }
        fun button(s: String, fill: Int = Raden.TEAL, onClick: () -> Unit): Button = Button(this).apply {
            text = s
            Raden.pill(this, fill)
            setOnClickListener { onClick() }
        }.also { cur.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 6 }) }
        fun row(vararg bs: Button) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bs.forEachIndexed { i, b ->
                r.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i > 0) leftMargin = 16
                })
            }
            cur.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 6 })
        }
        fun slider(name: String, key: String, def: Int, lo: Int, hi: Int, fmt: (Int) -> String) {
            val t = label("")
            val cur0 = Prefs.getInt(this, key, def)
            t.text = "$name: ${fmt(cur0)}"
            cur.addView(SeekBar(this).apply {
                Raden.seek(this)
                max = hi - lo
                progress = cur0 - lo
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

        section(null)
        label("Turn the Lenovo key mapper OFF (or use an empty profile) while this is running, or every press counts twice.", 14f)
            .setTextColor(Raden.INK_SOFT)
        updateText = label("Version v${BuildConfig.VERSION_CODE}")
        button("Check for updates") { checkUpdate() }
        updateBtn = button("Install update", Raden.ORANGE) { installUpdate() }.apply { visibility = View.GONE }

        section("Engine")
        shizukuText = label("Shizuku: checking…")
        button("Grant Shizuku access") { requestShizuku() }
        row(
            styled(Button(this).apply { text = "▶  Start flicker"; setOnClickListener { start() } }, Raden.ORANGE),
            styled(Button(this).apply { text = "■  Stop flicker"; setOnClickListener { stop() } }, Raden.CRIMSON)
        )
        statusText = label("Engine: stopped")
        layoutText = label("", 14f).apply { setTextColor(Raden.INK_SOFT) }
        button("Controller test (buttons, sticks, and where touches land)") {
            startActivity(Intent(this, ControllerTestActivity::class.java))
        }

        section("Modes")
        label("In game: hold Select and press L1 to show/hide the layout, Select + R1 to switch mode, Select + Start to edit.", 14f)
            .setTextColor(Raden.INK_SOFT)
        modeText = label("")
        modeBtns.clear()
        row(*Prefs.MODE_NAMES.mapIndexed { i, name ->
            Button(this).apply {
                text = name
                Raden.pill(this, Raden.TEAL)
                setOnClickListener { pickMode(i) }
            }.also { modeBtns.add(it) }
        }.toTypedArray())
        row(*Prefs.MODE_NAMES.mapIndexed { i, name ->
            styled(Button(this).apply {
                text = "Edit $name"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CalibrateActivity::class.java).putExtra("mode", i))
                }
            }, Raden.TEAL_SOFT)
        }.toTypedArray())
        button("Show / hide layout overlay now") {
            val sv = service
            if (sv == null) statusText.text = "Start the flicker first."
            else safe { sv.setOverlay(!(JSONObject(sv.inputState()).optBoolean("overlay"))) }
        }

        section("Tuning")
        label("Press Start again to apply.", 14f).setTextColor(Raden.INK_SOFT)
        slider("Flick length", Prefs.RADIUS, 50, 15, 150) { "%.1f%% of screen width".format(it / 10f) }
        slider("Edge reach (stick pushed sideways)", Prefs.REACH, 18, 0, 40) { "$it% of screen width" }
        slider("Stick threshold (flick)", Prefs.DEAD, 35, 15, 80) { "$it%" }
        slider("Joystick size (Park)", Prefs.JOY, 60, 20, 150) { "%.1f%% of screen width".format(it / 10f) }
        slider("Camera speed (Park)", Prefs.CAM, 10, 2, 40) { "$it" }
        axesBtn = button("") { cycleAxes() }
        updateAxesBtn()

        section("Lenovo mapper")
        killText = label("")
        updateKillText()
        row(
            styled(Button(this).apply { text = "Pick the mapper app to stop on Start"; setOnClickListener { pickMapper() } }, Raden.TEAL),
            styled(Button(this).apply {
                text = "Don't stop any app"
                setOnClickListener {
                    Prefs.putStr(this@MainActivity, Prefs.KILL_PKG, "")
                    updateKillText()
                }
            }, Raden.TEAL_SOFT)
        )

        section("Backup")
        backupText = label("Save all your layouts and settings to a file, or load them back.", 14f)
        row(
            styled(Button(this).apply {
                text = "Back up to a file"
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "holomem-flicker-backup.json"),
                        REQ_EXPORT
                    )
                }
            }, Raden.TEAL),
            styled(Button(this).apply {
                text = "Restore from a file"
                setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_IMPORT
                    )
                }
            }, Raden.TEAL)
        )

        section("Live input")
        monitorText = label("-", 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Raden.PLATINUM)
            background = Raden.rounded(Raden.TEAL_DEEP, 24f)
            setPadding(28, 22, 28, 22)
        }
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
            setTextColor(Raden.CREAM)
            background = Raden.rounded(Raden.TEAL_SOFT, 24f, Raden.ORANGE, 4)
            gravity = android.view.Gravity.CENTER
            setPadding(30, 44, 30, 44)
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) text = "✓ Touch received! Injection works."
                true
            }
        }
        button("List input devices") {
            val s = service
            monitorText.text = if (s == null) "Start the flicker first." else (safe { s.listDevices() } ?: "error")
        }

        window.statusBarColor = Raden.TEAL_DEEP
        window.navigationBarColor = Raden.MINT
        // Page scrolls underneath; the Disclaimer tab stays pinned to the top-right corner.
        val root = android.widget.FrameLayout(this)
        root.addView(ScrollView(this).apply {
            setBackgroundColor(Raden.MINT)
            addView(col)
        })
        root.addView(Button(this).apply {
            text = "⚠ Disclaimer"
            isAllCaps = false
            textSize = 14f
            Raden.pill(this, Raden.ORANGE)
            setOnClickListener { openDisclaimer(false) }
        }, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            setMargins(0, 56, 76, 0)
        })
        setContentView(root)

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        checkUpdate()
    }

    private var themeKey = ""

    override fun onResume() {
        super.onResume()
        if (!DisclaimerActivity.accepted(this)) {
            // first launch, or the disclaimer changed: it has to be accepted before anything else
            openDisclaimer(true)
            return
        }
        if (Prefs.getStr(this, Raden.PREF_KEY, "raden") != themeKey) {
            recreate()
            return
        }
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
        modeBtns.forEachIndexed { j, b -> Raden.pill(b, if (j == i) Raden.CRIMSON else Raden.TEAL) }
    }

    private var updateSha: String? = null

    /** Signed-update check: manifest + signature from the repo, verified against the built-in public key. */
    private fun checkUpdate() {
        updateText.text = "Version v${BuildConfig.VERSION_CODE} — checking for signed updates…"
        Thread {
            fun get(u: String): ByteArray? = try {
                val c = URL(u + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val ok = c.responseCode == 200
                val b = if (ok) c.inputStream.readBytes() else null
                c.disconnect()
                b
            } catch (_: Throwable) {
                null
            }
            val manifest = get(UpdateVerifier.MANIFEST_URL)
            val sig = get(UpdateVerifier.SIG_URL)
            val msg: String
            var rel: UpdateVerifier.Release? = null
            val mine = BuildConfig.VERSION_CODE
            if (manifest == null) {
                msg = "Version v$mine — no signed release published yet."
            } else if (sig == null || !UpdateVerifier.signatureOk(manifest, String(sig))) {
                msg = "Version v$mine — the latest release is NOT signed by your key. Not offering it."
            } else {
                rel = UpdateVerifier.parse(manifest)
                msg = when {
                    rel == null -> "Version v$mine — signed release has an unexpected format. Not offering it."
                    rel.version > mine -> "Version v$mine — signed update v${rel.version} is available ✓"
                    else -> "Version v$mine — up to date ✓ (signed)"
                }
            }
            runOnUiThread {
                updateText.text = msg
                val r = rel
                if (r != null && r.version > mine) {
                    updateUrl = r.apkUrl
                    updateSha = r.sha256
                    updateBtn.text = "Install signed update v${r.version}"
                    updateBtn.visibility = View.VISIBLE
                } else {
                    updateBtn.visibility = View.GONE
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
        val sha = updateSha ?: return
        updateText.text = "Downloading, checking the signature fingerprint, installing… the app will close when it's done. Reopen it after."
        updateBtn.isEnabled = false
        Thread {
            val r = safe { s.installVerified(url, sha) } ?: "install call failed"
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

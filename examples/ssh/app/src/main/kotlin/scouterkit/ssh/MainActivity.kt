package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.io.File

private const val SCREEN_REQUEST = 7 // the screen-sharing consent

// The home screen: what the glasses show and why, the three modes (the terminal, the phone's screen, the display
// off), quick controls, and the terminal as the glasses get it. Settings holds the rest. The session runs
// until Stop, with no keyer needed (keyer and keyboard mode still work as before).
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var terminal: TerminalView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var pills: LinearLayout
    private lateinit var termTile: Tile
    private lateinit var screenTile: Tile
    private lateinit var offTile: Tile
    private lateinit var stopSharing: View
    private lateinit var batterySwitch: Switch
    private lateinit var forceSwitch: Switch
    private val listener: () -> Unit = { show() }
    private var asked: String? = null
    private val associating = mutableSetOf<String>() // devices asked for while this screen lives
    private var companion: String? = null // the last association's outcome

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val page = page()
        page.addView(header("Scouter", "G1 glasses · runs on this phone", "Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        val hero = card()
        headline = text("", 23f, Ui.TEXT, Ui.BLACK).apply { letterSpacing = -0.01f }
        detail = text("", 13.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) }
        pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hero.addView(headline)
        hero.addView(detail)
        hero.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        })
        page.addView(hero)

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        termTile = Tile(this, "Terminal", "Claude on the glasses") { startTerminal() }
        screenTile = Tile(this, "Phone screen", "Share the entire screen") { phoneScreen() }
        offTile = Tile(this, "Display off", "Dark, link kept") { toggleDisplay() }
        for ((i, t) in listOf(termTile, screenTile, offTile).withIndex())
            tiles.addView(t, lp(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(10) })
        page.addView(tiles, lp().apply { bottomMargin = dp(14) })

        val quick = card("Quick controls")
        quick.addView(slider("Brightness", 0, 42, settings.brightness, { "${it * 100 / 42}%" }) { v ->
            settings.brightness = v
            Thread { Native.brightness(v) }.start()
        })
        batterySwitch = toggle("Battery icons", "Each eye's battery level beside the terminal", settings.battery) {
            settings.battery = it
            Thread { Native.setBattery(it) }.start()
        }
        quick.addToggle(batterySwitch)
        forceSwitch = toggle("Always visible", "Off: the terminal shows only when you look up", settings.phoneForce) {
            settings.phoneForce = it
            if (ScouterService.running) ScouterService.send(this)
        }
        quick.addToggle(forceSwitch)
        quick.addToggle(toggle("Dark while the phone is locked", "With the phone's screen on the glasses", settings.offWhenLocked) {
            settings.offWhenLocked = it
        })
        stopSharing = button("Stop sharing the phone's screen") { ScouterService.send(this, ScouterService.ACTION_STOP_SHARING) }
        quick.addView(stopSharing)
        quick.addView(button("Stop everything", DANGER) {
            note = null
            ScouterService.send(this, ScouterService.ACTION_STOP)
        })
        page.addView(quick)

        val preview = card("Terminal", "What the glasses get in terminal view")
        terminal = TerminalView(this)
        preview.addView(clipFrame(terminal, 320))
        page.addView(preview)
        if (BuildConfig.FLAVOR == "emulated") page.addView(PanelView(this))
        status = text("", 11.5f, Ui.FAINT, Ui.MONO).apply { setPadding(dp(4), dp(4), dp(4), 0) }
        page.addView(status)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) importTestKey()
        Session.listeners += listener
        Session.foreground = true
        val keyboard = Settings(this).keyboard
        // Keyboard mode: the keys come only while this screen is in front, so it stays on (dimmed by Android).
        if (keyboard) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Settings may have changed; and while Android has not said whether keyer is near (the app new or
        // restarted), the service tries keyer's link. In keyboard mode this screen in front starts the session.
        if (keyboard || ScouterService.running || Session.near == null || BuildConfig.FLAVOR == "emulated") ScouterService.send(this)
        associate()
        show()
    }

    override fun onPause() {
        Session.listeners -= listener
        Session.foreground = false
        if (ScouterService.running) ScouterService.send(this) // keyboard mode: the session lets go after its grace
        super.onPause()
    }

    // Keyboard mode: a paired keyboard's keys go to the terminal; the phone's own buttons stay Android's.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Settings(this).keyboard && Keyboard.physical(event) && Keyboard.send(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // adb, debug builds (in a release build any app could send these): am start -n PACKAGE/scouterkit.ssh.MainActivity
    // --es open start|stop|check|spike [extras for the spike]
    private fun handle(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.getStringExtra("open")) {
            "start" -> ScouterService.send(this)
            "stop" -> ScouterService.send(this, ScouterService.ACTION_STOP)
            "check" -> Thread { Native.check().lines().filter { it.isNotEmpty() }.forEach { android.util.Log.i("scouter", "check: $it") } }.start()
            "spike" -> startActivity(Intent(this, HidSpikeActivity::class.java).putExtras(intent))
        }
        intent.getStringExtra("keys")?.let { type(it) }
        if (intent.getStringExtra("open") == "encrypt") intent.getStringExtra("address")?.let { encrypt(it) }
        // The emulated flavour: --ei pitch 30 tilts both emulated arms' heads.
        if (intent.hasExtra("pitch")) for (arm in 0..1) Native.emulatedProperty(arm, Native.PROP_PITCH, intent.getIntExtra("pitch", 0))
    }

    // Debug builds: --es open encrypt --es address ADDR connects once to a bonded device for 6 s, so the log shows
    // whether the link encrypts with the stored key (bt_btif auth_cmpl); closed at once if the bond changes.
    @android.annotation.SuppressLint("MissingPermission")
    private fun encrypt(address: String) {
        val device = getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(address)
        if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
            android.util.Log.i("scouter", "encrypt: not bonded")
            return
        }
        val main = android.os.Handler(mainLooper)
        var gatt: android.bluetooth.BluetoothGatt? = null
        val bonds = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                android.util.Log.i("scouter", "encrypt: bond changed, closing")
                gatt?.close()
                gatt = null
            }
        }
        registerReceiver(bonds, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        gatt = device.connectGatt(this, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, state: Int) {
                android.util.Log.i("scouter", "encrypt: state $state status $status bond ${g.device.bondState}")
            }
        }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        main.postDelayed({
            android.util.Log.i("scouter", "encrypt: done, bond ${device.bondState}")
            gatt?.close()
            gatt = null
            unregisterReceiver(bonds)
        }, 6000)
    }

    // Debug builds: --es keys 'ls{enter}' types as keyer would ({enter} {tab} {esc} {bs} {up} {down}
    // {left} {right} {c-x} for Ctrl+x).
    private fun type(text: String) {
        val usages = mapOf("enter" to 0x28, "esc" to 0x29, "bs" to 0x2A, "tab" to 0x2B, "right" to 0x4F,
            "left" to 0x50, "down" to 0x51, "up" to 0x52)
        Regex("\\{([a-z-]+)\\}|.", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("c-") -> Native.keyEvent(1, 0x01, name[2].code)
                name.isNotEmpty() -> usages[name]?.let { Native.keyEvent(2, 0, it) }
                else -> Native.keyEvent(1, 0, m.value.codePointAt(0))
            }
        }
    }

    // The app as keyer's companion (CompanionApp): the system's dialog, once per device while this screen lives,
    // not behind the lock screen (Android cancels it there). The next one follows when it has closed (onResume).
    private fun associate() {
        if (BuildConfig.FLAVOR == "emulated" || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val device = CompanionApp.missing(this).firstOrNull { it !in associating } ?: return
        associating += device
        CompanionApp.associate(this, device) { error -> runOnUiThread { companion = error; show() } }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == CompanionApp.REQUEST) companion = CompanionApp.result(this, if (result == RESULT_OK) data else null)
        if (request == SCREEN_REQUEST) {
            if (result == RESULT_OK && data != null) {
                startingAt = android.os.SystemClock.elapsedRealtime()
                ScouterService.sendScreen(this, result, data)
            } else note = "Screen sharing was not allowed."
            show()
        }
    }

    // What the phone's controls need first, or null.
    private fun ready(): String? {
        val settings = Settings(this)
        return when {
            ScouterService.missing(this).isNotEmpty() -> "allow Bluetooth and notifications in Settings first"
            !ScouterService.bluetoothOn(this) -> "switch Bluetooth on first"
            BuildConfig.FLAVOR != "emulated" && (settings.left.isEmpty() || settings.right.isEmpty()) ->
                "pair the glasses first: Settings, Glasses"
            else -> null
        }
    }

    // The terminal on the glasses, started from the phone: it runs until Stop.
    private fun startTerminal() {
        ready()?.let { note = it; return show() }
        val settings = Settings(this)
        note = if (settings.host.isEmpty()) "No server yet: run scouter.sh in Termux, then Settings, Terminal server, \"Termux on this phone\"." else null
        settings.glassesAllowed = true
        startingAt = android.os.SystemClock.elapsedRealtime()
        ScouterService.send(this, ScouterService.ACTION_TERMINAL)
        show()
    }

    // Android asks to share the entire screen (Android 14+: no single-app choice); the service gets the answer.
    @Suppress("DEPRECATION")
    private fun shareScreen() {
        ready()?.let { note = it; return show() }
        Settings(this).glassesAllowed = true
        note = null
        val projection = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val ask = if (android.os.Build.VERSION.SDK_INT >= 34)
            projection.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else projection.createScreenCaptureIntent()
        startActivityForResult(ask, SCREEN_REQUEST)
    }

    // The phone's screen: at once while it is shared, else Android asks first.
    private fun phoneScreen() {
        if (Session.capturing && ScouterService.running) {
            note = null
            ScouterService.send(this, ScouterService.ACTION_VIEW_SCREEN)
        } else shareScreen()
    }

    // The display off / on, while a session runs.
    private fun toggleDisplay() {
        if (!ScouterService.running || Session.showing.isEmpty()) {
            note = "start Terminal or Phone screen first"
            return show()
        }
        note = null
        ScouterService.send(this, ScouterService.ACTION_DISPLAY)
    }

    private var note: String? = null // the last control's problem
    private var startingAt = 0L // a mode was tapped: "Starting…" until the session shows (or 45 s)

    // R3: in Doze the network waits for maintenance windows unless the app is exempt (keyer's association
    // exempts it).
    private fun exempt() = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun show() {
        val settings = Settings(this)
        val running = ScouterService.running && Session.showing.isNotEmpty()
        if (running) startingAt = 0L
        val starting = !running && startingAt > 0 && android.os.SystemClock.elapsedRealtime() - startingAt < 45000
        headline.text = when {
            starting -> "Starting…"
            !running -> "Ready when you are"
            Session.blank -> "Display off"
            Session.showing == Settings.SCREEN -> "Phone screen on the glasses"
            else -> "Terminal on the glasses"
        }
        val permitted = ScouterService.missing(this).isEmpty()
        val lines = mutableListOf<String>()
        if (!permitted) lines += "Allow Bluetooth and notifications in Settings."
        else if (!ScouterService.bluetoothOn(this)) lines += "Bluetooth is off: the app waits for it."
        if (settings.left.isEmpty() || settings.right.isEmpty()) lines += "Pair the glasses in Settings, Glasses."
        else if (running) lines += when (Session.glasses) {
            "ready" -> "Glasses connected" + (if (Session.left && Session.right) "" else " (one arm down)")
            "connecting" -> "Connecting to the glasses…"
            else -> "Glasses: ${Session.glasses}"
        }
        if (running && Session.showing == Settings.TERMINAL) lines += "Terminal: " + Session.mosh.replaceFirstChar { it.uppercase() }
        if (Session.capturing || Session.screen.startsWith("stopped") || Session.screen.startsWith("error"))
            lines += "Phone screen: ${Session.screen}"
        if (Session.blank) lines += "Turn it back on here, in the notification, or with your TouchBar gesture."
        if (Session.gesture.isNotEmpty()) lines += "Last gesture: ${Session.gesture}"
        if (starting) lines += "Connecting to the glasses and the server…"
        note?.let { lines += it }
        detail.text = lines.joinToString("\n").ifEmpty { "Pick Terminal or Phone screen to start." }

        pills.removeAllViews()
        fun pill(label: String, dot: Int) = pills.addView(statusPill(label, dot), lp(android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8) })
        val b = Session.battery
        for (arm in 0..1) {
            val name = if (arm == 0) "Left" else "Right"
            val up = if (arm == 0) Session.left else Session.right
            val pct = b.getOrElse(arm) { -1 }
            val label = if (pct >= 0) "$name $pct%" + (if (b.getOrElse(arm + 2) { 0 } == 1) " ⚡" else "") else name
            pill(label, when {
                !running || !up -> Ui.FAINT
                pct in 0..15 -> Ui.BAD
                pct in 16..35 -> Ui.WARN
                else -> Ui.GOOD
            })
        }
        pill("SSH", when {
            Session.mosh == "connected" -> Ui.GOOD
            Session.mosh.startsWith("error") || Session.mosh.startsWith("no contact") -> Ui.BAD
            Session.mosh == "off" -> Ui.FAINT
            else -> Ui.WARN
        })
        pill(if (Session.capturing) "Sharing" else "Screen", if (Session.capturing) Ui.ACCENT else Ui.FAINT)

        termTile.select(running && !Session.blank && Session.showing == Settings.TERMINAL)
        screenTile.select(running && !Session.blank && Session.showing == Settings.SCREEN,
            if (Session.capturing) "Shared · tap to show" else "Share the entire screen")
        offTile.select(Session.blank, if (Session.blank) "Tap to turn on" else "Dark, link kept")
        stopSharing.visibility = if (Session.capturing) View.VISIBLE else View.GONE
        if (batterySwitch.isChecked != settings.battery) batterySwitch.isChecked = settings.battery
        if (forceSwitch.isChecked != settings.phoneForce) forceSwitch.isChecked = settings.phoneForce

        status.text = (if (exempt()) "" else "battery optimised · ") +
            "glasses ${Session.glasses} (L ${Session.left} R ${Session.right}" + (if (Session.shown) ", shown" else "") +
            ") · screen ${Session.screen} (${Session.screenFrames} frames) · mosh ${Session.mosh} · keyer ${Session.keyer}" +
            (companion?.let { " · companion: $it" } ?: "") + (if (Session.termux.isNotEmpty()) " · Termux ${Session.termux}" else "")
        Native.snapshot()?.let { terminal.screen = TerminalView.parse(it) }
        title = terminal.screen?.title?.ifEmpty { null } ?: getString(R.string.app_name)
        val fingerprint = Session.unknownHost
        if (fingerprint != null && fingerprint != asked) {
            asked = fingerprint
            dialog()
                .setTitle("Trust this server?")
                .setCancelable(false) // Trust or Cancel, not a stray touch
                .setMessage("${settings.host}\n$fingerprint")
                .setPositiveButton("Trust") { _, _ -> ScouterService.send(this, ScouterService.ACTION_TRUST, "fingerprint" to fingerprint) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // Debug builds: a key placed with `adb shell run-as PACKAGE` in files/import_key (and import_passphrase)
    // goes into the vault, for tests without the file picker.
    private fun importTestKey() {
        val key = File(filesDir, "import_key").takeIf { it.exists() } ?: return
        val vault = Vault(this)
        vault.put(Vault.SSH_KEY, key.readBytes())
        val passphrase = File(filesDir, "import_passphrase")
        if (passphrase.exists()) vault.put(Vault.SSH_PASSPHRASE, passphrase.readBytes()) else vault.delete(Vault.SSH_PASSPHRASE)
        key.delete()
        passphrase.delete()
    }
}

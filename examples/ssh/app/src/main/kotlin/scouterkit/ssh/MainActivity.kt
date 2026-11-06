package scouterkit.ssh

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

private const val SCREEN_REQUEST = 7 // the screen-sharing consent

// The mirror while the app is in the foreground (no keys from the phone: they come from keyer), the session's
// state, Settings, and the phone's own controls: the terminal (Termux on this phone, or any server) or this
// phone's screen on the glasses until Stop, with no keyer needed.
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var terminal: TerminalView
    private val listener: () -> Unit = { show() }
    private var asked: String? = null
    private val associating = mutableSetOf<String>() // devices asked for while this screen lives
    private var companion: String? = null // the last association's outcome

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(16, 8, 16, 8) }
        terminal = TerminalView(this)
        list.addView(status)
        list.addView(terminal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (BuildConfig.FLAVOR == "emulated") list.addView(PanelView(this))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun control(label: String, action: () -> Unit) = row.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        control("Terminal on glasses") { startTerminal() }
        control("Phone screen on glasses") { shareScreen() }
        control("Stop") { ScouterService.send(this, ScouterService.ACTION_STOP) }
        list.addView(row)
        list.addView(Button(this).apply {
            text = "Settings"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        setContentView(list)
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
            if (result == RESULT_OK && data != null) ScouterService.sendScreen(this, result, data)
            else note = "screen sharing not allowed"
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
        note = if (settings.host.isEmpty()) "no server yet: Settings, Termux on this phone, Set up" else null
        settings.glassesAllowed = true
        ScouterService.send(this, ScouterService.ACTION_TERMINAL)
        show()
    }

    // Android asks which screen to share; the service gets the answer.
    @Suppress("DEPRECATION")
    private fun shareScreen() {
        ready()?.let { note = it; return show() }
        Settings(this).glassesAllowed = true
        note = null
        startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager::class.java)
            .createScreenCaptureIntent(), SCREEN_REQUEST)
    }

    private var note: String? = null // the last control's problem

    // R3: in Doze the network waits for maintenance windows unless the app is exempt (keyer's association
    // exempts it).
    private fun exempt() = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun show() {
        val permitted = ScouterService.missing(this).isEmpty()
        status.text = (if (permitted) "" else "allow Bluetooth and notifications in Settings · ") +
            (if (!permitted || ScouterService.bluetoothOn(this)) "" else "Bluetooth is off: the app waits for it · ") +
            (if (exempt()) "" else "battery optimised: in Doze, keys wait for its windows · ") +
            "glasses ${Session.glasses} (L ${Session.left} R ${Session.right}" + (if (Session.shown) ", shown" else "") +
            ") · " + (if (Session.screen != "off") "screen ${Session.screen} (${Session.screenFrames} frames) · " else "") +
            "mosh ${Session.mosh} · keyer ${Session.keyer}" + (companion?.let { " · companion: $it" } ?: "") +
            (if (Session.termux.isNotEmpty()) " · Termux ${Session.termux}" else "") +
            (if (Settings(this).phone) " · started from the phone (Stop ends it)" else "") +
            (note?.let { "\n$it" } ?: "")
        Native.snapshot()?.let { terminal.screen = TerminalView.parse(it) }
        title = terminal.screen?.title?.ifEmpty { null } ?: getString(R.string.app_name)
        val fingerprint = Session.unknownHost
        if (fingerprint != null && fingerprint != asked) {
            asked = fingerprint
            AlertDialog.Builder(this)
                .setTitle("Trust this server?")
                .setCancelable(false) // Trust or Cancel, not a stray touch
                .setMessage("${Settings(this).host}\n$fingerprint")
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

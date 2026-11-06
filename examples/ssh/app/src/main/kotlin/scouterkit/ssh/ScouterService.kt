package scouterkit.ssh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

// What the UI shows: the service's state, observed in-process.
object Session {
    @Volatile var glasses = "off"
    @Volatile var left = false // arm links up
    @Volatile var right = false
    @Volatile var mosh = "off"
    @Volatile var keyer = "off"
    @Volatile var shown = false // the terminal shows on an eye
    @Volatile var foreground = false // MainActivity is in front: it gets a paired keyboard's keys (Keyboard)
    @Volatile var title = ""
    @Volatile var unknownHost: String? = null // a fingerprint waiting for the user's confirmation
    @Volatile var termux = "" // Termux's setup, as its last run reported it
    @Volatile var screen = "off" // the phone's screen on the glasses: off, starting, on, hidden, or why it stopped
    @Volatile var screenFrames = 0
    @Volatile var screenFps = 0f // frames shown on the glasses per second, over the last 2 s
    @Volatile var capturing = false // Android's screen sharing runs (the phone's screen can show without asking)
    @Volatile var screenSource = "" // the screen the glasses get: Settings.PHONE or "screen:ID" (a server's)
    @Volatile var remote = "" // a server's screen streams (RemoteScreen): its server id
    @Volatile var source = "" // the source shown, by name (Settings.sourceName)
    @Volatile var showing = "" // what the glasses show now: Settings.TERMINAL or SCREEN ("" no session)
    @Volatile var blank = false // the display is off (nothing shows; the TouchBar still works)
    @Volatile var battery: IntArray = intArrayOf(-1, -1, -1, -1) // left %, right %, left charging, right charging
    @Volatile var gesture = "" // the last TouchBar gesture and what it did
    // The devices Android reports near (ScouterCompanionService: keyer); null: unknown (none reported since the
    // process started, Bluetooth was off since, or keyer's link did not come after the last report).
    @Volatile var near: Set<String>? = null
    val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun changed() = main.post { for (l in listeners.toList()) l() }
}

// Owns the links (R1): the glasses (ScouterKit over GlassesLink, or the emulated arms), the mosh session and
// keyer. It runs only while keyer is on and Bluetooth is on (the user's battery rule): keyer's presence
// (ScouterCompanionService) starts it and keyer's link is tried; while that link is up refresh() connects the
// glasses, takes keyer's keys and starts or resumes mosh. With keyer's link gone, or Bluetooth switched off, it
// lets go (end), so the phone can sleep. Blocking work runs on its worker thread.
class ScouterService : Service() {
    private val worker = HandlerThread("session").apply { start() }
    private val work = Handler(worker.looper)
    private lateinit var settings: Settings
    private lateinit var vault: Vault
    @Volatile private var link: GlassesLink? = null
    private var keyer: KeyerLink? = null
    private var keyerAddress = ""
    private var wake: PowerManager.WakeLock? = null
    private var glassesWith: String? = null // the settings the running glasses session started with
    // Why the glasses link stopped (a bond gone, encryption refused): no reconnect and no pairing until an arm
    // is bonded again (bonds), the glasses' Settings change (another pair, the switch) or keyer's next session.
    private var stopped: String? = null
    private var stoppedWith = ""
    // keyer's link is tried until then: after Android reported keyer near, or the app opened before any report.
    private var probeUntil = 0L
    @Volatile private var ended = false
    // The phone's screen on the glasses (Settings.show SCREEN), while Android's screen sharing runs.
    @Volatile private var screen: PhoneScreen? = null
    // A server's screen (the laptop's) over SSH, while it is the source.
    @Volatile private var remote: RemoteScreen? = null
    private var forced = false // the terminal forced on for a session started from the phone (phoneForce)
    private val main = Handler(Looper.getMainLooper())
    private val screenTicker = object : Runnable {
        override fun run() {
            val remoteShown = Session.screenSource.startsWith("screen:")
            val s = (if (remoteShown) remote?.state else screen?.state) ?: run {
                if (screen == null && remote == null) return
                Native.SCREEN_SAME
            }
            val status = Native.status()
            val state = when {
                settings.show != Settings.SCREEN -> "shared (the glasses show the terminal)"
                Session.blank -> "shared (display off)"
                status.getOrElse(6) { 0 } != 1 -> if (Session.glasses == "ready") "not on the glasses yet" else "waiting for the glasses"
                s == Native.SCREEN_HIDDEN -> "off: ${Native.screenWhy().ifEmpty { "hidden" }}"
                else -> "on"
            }
            val frames = status.getOrElse(7) { 0 }
            Session.screenFps = if (frames >= Session.screenFrames) (frames - Session.screenFrames) / 2f else 0f
            Session.screenFrames = frames
            if (state != Session.screen) update(screen = state) else Session.changed()
            work.postDelayed(this, 2000)
        }
    }
    private var blankByLock = false // the display went off because the phone locked
    // Each arm's battery, every 30 s while the glasses are up.
    private val batteryTicker = object : Runnable {
        override fun run() {
            if (Session.glasses == "ready") {
                Session.battery = Native.battery()
                Session.changed()
            }
            work.postDelayed(this, 30000)
        }
    }
    // The phone locked (its screen off): with the phone's screen on the glasses there is nothing new to show.
    private val lock = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> work.post {
                    if (settings.offWhenLocked && settings.show == Settings.SCREEN && !Session.blank) {
                        blankByLock = true
                        display(false, "phone locked")
                    }
                }
                Intent.ACTION_SCREEN_ON -> work.post {
                    if (blankByLock) display(true, "phone on")
                }
            }
        }
    }
    private val retryGlasses = Runnable { refresh() }
    private val letGo = Runnable {
        if (active() || SystemClock.elapsedRealtime() < probeUntil) return@Runnable refresh()
        // Android's report did not hold (it can be stale: a device lost while Android's scan was stopped stays
        // "near"); opening the app tries keyer again.
        if (keyerNear()) Session.near = null
        end()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            poll()
            retryMosh()
            refresh()
            work.postDelayed(this, 60000)
        }
    }
    // A network came (back): a session that could not start is tried again; a running one roams by itself.
    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) work.post { retryMosh() }
        }
    }
    // An arm bonded again (paired in the case): a stop of the glasses link ends.
    @Suppress("DEPRECATION")
    private val bonds = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) == BluetoothDevice.BOND_BONDED) work.post { bonded(device.address) }
        }
    }
    // Bluetooth switched off (to BLE_ON as well): Android drops every link without a GATT callback, so without
    // this the session went on as if keyer and the arms were up (wake lock, mosh, writes retried every 5 ms).
    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (BuildConfig.FLAVOR == "emulated") return
            // The broadcast's state, not the adapter's now: off and on again quickly still dropped the links.
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) != BluetoothAdapter.STATE_ON) {
                link?.bluetoothOff()
                work.post { bluetoothOff() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        settings = Settings(this)
        vault = Vault(this)
        Native.onChanged = { work.post { poll() } }
        Native.onGesture = { a, g -> work.post { gesture(a, g) } }
        work.postDelayed(watchdog, 60000)
        work.postDelayed(batteryTicker, 5000)
        registerReceiver(lock, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        registerReceiver(bonds, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun retryMosh() {
        if (active() && Session.mosh.startsWith("error") && Session.unknownHost == null && online()) startMosh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ refuses a connectedDevice service without its runtime prerequisite (Bluetooth), and 12+ one
        // started from the background without an exemption (keyer's association): then no service.
        try {
            startForeground(NOTIFICATION, notification(), types()) // keeps mediaProjection while the screen is shared
        } catch (e: SecurityException) {
            return refused(e)
        } catch (e: IllegalStateException) {
            return refused(e)
        }
        if (!bluetoothOn(this)) { // e.g. a sticky restart after the user switched Bluetooth off
            work.post { bluetoothOff() }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> work.post {
                settings.phone = false
                end()
            }
            // From the phone: the glasses show this phone's screen (Android's consent in the intent)...
            ACTION_SCREEN -> startScreen(intent)
            // ... or the terminal (screen sharing goes on, so the phone's screen can come back without asking)...
            ACTION_TERMINAL -> work.post {
                settings.phone = true
                view(Settings.TERMINAL)
            }
            // A saved server's terminal (extra "id"); empty: the next saved server.
            ACTION_SERVER -> work.post {
                val id = intent.getStringExtra("id").orEmpty()
                val all = settings.servers()
                val pick = all.firstOrNull { it.id == id }
                    ?: all.getOrNull((all.indexOfFirst { it.id == settings.active()?.id } + 1) % maxOf(1, all.size))
                pick?.let { source("term:${it.id}") }
            }
            // ... or the phone's screen again, while it is shared.
            ACTION_VIEW_SCREEN -> work.post { source(Settings.PHONE) }
            // A source (Settings.sources), or next / prev: the app's buttons, shortcuts, the tile.
            ACTION_SOURCE -> work.post { source(intent.getStringExtra(Shortcuts.EXTRA) ?: "") }
            ACTION_STOP_SHARING -> work.post { screenEnded("off") }
            // The display off / on (the notification, the Quick Settings tile, the app): extra "on" true/false,
            // absent: the other way.
            ACTION_DISPLAY -> work.post {
                val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, true) else Session.blank
                blankByLock = false
                display(on, "app")
            }
            ACTION_TRUST -> work.post { trust(intent.getStringExtra("fingerprint")) }
            // Android reported keyer near (or gone): a report of near gets keyer's link 30 s to come.
            ACTION_NEAR -> work.post {
                if (keyerNear()) {
                    probeUntil = SystemClock.elapsedRealtime() + PROBE_MS
                    keyer?.nudge()
                }
                refresh()
            }
            // The app opened or Settings changed: the changes apply, and keyer is tried while Android has not
            // said whether it is near.
            else -> work.post {
                if (Session.near == null) probeUntil = SystemClock.elapsedRealtime() + PROBE_MS
                retryMosh()
                refresh()
            }
        }
        return START_STICKY
    }

    private fun refused(e: Exception): Int {
        Log.w(TAG, "no foreground service: ${e.message}")
        ended = true
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        ended = true
        screen?.release()
        screen = null
        remote?.release()
        remote = null
        Session.remote = ""
        Session.screenSource = ""
        Session.capturing = false
        Session.showing = ""
        Native.onGesture = null
        work.removeCallbacks(batteryTicker)
        runCatching { unregisterReceiver(lock) }
        runCatching { unregisterReceiver(bonds) }
        runCatching { unregisterReceiver(adapterState) }
        work.removeCallbacks(watchdog)
        getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network)
        work.post { // ended by the system: as when keyer goes
            stopKeyer()
            pauseMosh()
            stopGlasses(true)
        }
        worker.quitSafely()
        Native.onChanged = null
        // keyer came back while this service was letting go: a new one.
        if (keyerNear()) runCatching { send(this, ACTION_NEAR) }
        super.onDestroy()
    }

    private fun keyerNear() = settings.keyer.isNotEmpty() && Session.near?.contains(settings.keyer.uppercase()) == true

    // keyer on: its link is up. Android's presence report only starts the service and keyer's link: it can be
    // stale (a device lost while Android's scan was stopped stays "near"), and the glasses are not held on it.
    // The emulated flavour has no keyer.
    private fun active(): Boolean {
        if (BuildConfig.FLAVOR == "emulated") return true
        if (settings.phone) return true // started from the phone: until Stop
        if (settings.keyboard) return Session.foreground
        if (settings.keyer.isEmpty()) return false
        return keyer?.up == true
    }

    // Bluetooth off (BLE_ON counts as off): no links and no service; Android's reports of keyer are void.
    private fun bluetoothOff() {
        if (ended) return
        Log.i(TAG, "Bluetooth off: the session ends")
        Session.near = null
        link?.bluetoothOff()
        end()
    }

    // While keyer is on: the glasses (anew after their Settings changed; dx and dy set the grid, R27), keyer's
    // keys and mosh (resumed, or a new session for another server). Otherwise only keyer's link, while it is
    // tried; 3 s after keyer's link has gone (or the probe's end) the service lets go.
    private fun refresh() {
        if (ended) return
        if (!bluetoothOn(this)) return bluetoothOff()
        startKeyer()
        if (!active()) {
            val grace = if (settings.keyboard) KEYBOARD_GRACE_MS else 3000L
            if (!work.hasCallbacks(letGo)) work.postDelayed(letGo, maxOf(grace, probeUntil - SystemClock.elapsedRealtime()))
            return
        }
        work.removeCallbacks(letGo)
        // The screen needs Android's screen sharing, which ends with the process: then the terminal.
        if (settings.show == Settings.SCREEN && screen == null) settings.show = Settings.TERMINAL
        // Keyboard mode: the terminal shows on the glasses whatever the head's angle, as while keyer's keys come.
        if (settings.keyboard && Session.keyer != KEYBOARD) {
            Native.force(true)
            update(keyer = KEYBOARD)
        }
        // Started from the phone: the same, unless the user wants the looking-up rule.
        val force = settings.phone && settings.phoneForce
        if (force != forced) {
            forced = force
            Native.force(force || Session.keyer == "keys" || Session.keyer == KEYBOARD)
        }
        if (glassesWith != null && glassesWith != glassesSettings()) stopGlasses(false)
        if (stopped != null && stoppedWith != glassesSettings()) stopped = null
        if (!work.hasCallbacks(retryGlasses)) startGlasses()
        if (moshWith != null && moshWith != moshSettings()) stopMosh()
        // The phone's screen on the glasses: mosh waits (paused, the session kept) until the terminal is back.
        if (settings.show == Settings.SCREEN) {
            if (moshWith != null && Session.mosh != "paused") pauseMosh()
            return
        }
        startMosh()
        if (moshWith != null && moshSize != (settings.cols to settings.rows)) {
            moshSize = settings.cols to settings.rows
            Native.resize(moshSize.first, moshSize.second)
        }
    }

    // keyer gone or Bluetooth off (the user's battery rule): keyer's keys go back to it, mosh goes quiet but keeps
    // its session for keyer's next session in this process, and the glasses keep the terminal's program and last
    // screen and show them by themselves when the head looks up. A stop of the glasses link stays in the
    // notification.
    private fun end() {
        ended = true
        work.removeCallbacks(watchdog)
        work.removeCallbacks(letGo)
        stopKeyer()
        if (Session.keyer == KEYBOARD) {
            Native.force(false)
            update(keyer = "off")
        }
        if (forced) Native.force(false)
        forced = false
        work.removeCallbacks(screenTicker)
        stopRemote()
        Session.screenSource = ""
        screen?.let {
            screen = null
            it.release()
            Native.screenStop()
            Session.screen = "off"
        }
        Session.capturing = false
        Session.showing = ""
        if (Session.blank) Native.blank(false)
        Session.blank = false
        blankByLock = false
        pauseMosh()
        stopGlasses(true, stopped?.let { "stopped: $it (pair again in the case)" } ?: "off")
        if (stopped == null) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(ongoing = false))
        }
        stopSelf()
    }

    private fun glassesSettings() = listOf(settings.glassesAllowed, settings.left, settings.right, settings.dx,
        settings.dy, settings.angle, settings.ignoreWorn).joinToString(" ") // brightness, battery: live

    private fun moshSettings() = listOf(settings.host, settings.port, settings.user, settings.command, settings.moshPorts).joinToString(" ")

    private fun startGlasses() {
        if (glassesWith != null || stopped != null) return
        val emulated = BuildConfig.FLAVOR == "emulated"
        if (!emulated && !settings.glassesAllowed) return update(glasses = "off in Settings")
        if (!emulated && (settings.left.isEmpty() || settings.right.isEmpty())) return update(glasses = "not paired")
        if (!emulated) {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            val unbonded = listOf(settings.left, settings.right).indexOfFirst {
                adapter.getRemoteDevice(it).bondState != BluetoothDevice.BOND_BONDED
            }
            if (unbonded >= 0) return lostGlasses("the ${GlassesLink.NAMES[unbonded]} arm is not bonded")
        }
        update(glasses = "connecting")
        val l = if (emulated) null else GlassesLink(this, arrayOf(settings.left, settings.right), { armChanged() },
            { why -> work.post { lostGlasses(why) } })
        val font = assets.open("departure_mono_nerd.skf").use { it.readBytes() }
        Native.terminalMargins(settings.termTop, settings.termBottom)
        val error = Native.glassesStart(l, settings.left, settings.right, settings.dx, settings.dy, settings.brightness,
            settings.angle, font, BuildConfig.DEBUG && settings.ignoreWorn, settings.battery)
        if (error != null || l?.lost != null) {
            Native.glassesStop(false)
            l?.quit()
            l?.lost?.let { return lostGlasses(it) }
            update(glasses = error)
            work.postDelayed(retryGlasses, 10000)
            return
        }
        link = l
        glassesWith = glassesSettings()
        if (emulated) Session.left = true.also { Session.right = true }
        update(glasses = "ready")
        work.removeCallbacks(batteryTicker)
        work.postDelayed(batteryTicker, 3000)
    }

    // An arm's bond is gone, or its encryption failed: no reconnect and no pairing (Android would pair again by
    // itself, docs/fps-link.md §13.1); see `stopped`.
    private fun lostGlasses(why: String) {
        if (stopped != null) return
        Log.w(TAG, "glasses stopped: $why")
        stopped = why
        stoppedWith = glassesSettings()
        stopGlasses(false, "stopped: $why (pair again in the case)")
    }

    private fun bonded(address: String) {
        if (stopped == null || !(address.equals(settings.left, true) || address.equals(settings.right, true))) return
        stopped = null
        update(glasses = "off")
        refresh()
    }

    // keep: the arms go on showing the last screen by themselves (keyer gone); else their programs stop.
    private fun stopGlasses(keep: Boolean, state: String = "off") {
        work.removeCallbacks(retryGlasses)
        if (glassesWith != null || link != null) {
            Native.glassesStop(keep)
            link?.quit()
            link = null
            glassesWith = null
            Session.left = false
            Session.right = false
        }
        update(glasses = state)
    }

    private fun startMosh() {
        if (moshWith != null && Session.mosh == "paused") { // keyer back: the same session
            Native.moshPause(false)
            return update(mosh = "connecting")
        }
        if (Session.mosh != "off" && !Session.mosh.startsWith("error")) return
        if (settings.host.isEmpty() || settings.user.isEmpty()) return update(mosh = "error: no server in Settings")
        // Termux on this phone: its sshd started first (it does not run by itself).
        if (settings.termux) Termux.ensureSshd(this)?.let { return update(mosh = "error: $it") }
        update(mosh = "starting")
        // The last mosh-server this server ran for the app ends first (the record names its server).
        val record = vault.text(Vault.MOSH)?.split(" ")
        val previous = if (record?.getOrNull(4) == "${settings.host}:${settings.port}") record.getOrNull(3)?.toIntOrNull() ?: 0 else 0
        try {
            val m = Bootstrap(vault).start(settings.host, settings.port, settings.user, settings.command, settings.moshPorts, previous)
            vault.putText(Vault.MOSH, "${m.ip} ${m.port} ${m.key} ${m.pid} ${settings.host}:${settings.port}")
            val error = Native.moshStart(m.ip, m.port, m.key, settings.cols, settings.rows)
            if (error == null) {
                moshWith = moshSettings()
                moshSize = settings.cols to settings.rows
            }
            update(mosh = if (error == null) "connecting" else "error: $error")
        } catch (e: Bootstrap.UnknownHost) {
            // Termux's own host key, as Termux reported it at setup: trusted without asking.
            if (settings.termux && e.fingerprint in settings.termuxKeys.split(" ")) {
                Bootstrap.remember(vault, "${settings.host}:${settings.port}", e.fingerprint)
                update(mosh = "off")
                return startMosh()
            }
            Session.unknownHost = e.fingerprint
            update(mosh = "error: confirm the host key")
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap: ${e.message}")
            update(mosh = "error: ${e.message}")
        }
    }

    // keyer gone: no traffic; mosh-server keeps the session and the client waits in this process (if Android
    // ends the process, keyer's next session starts a new one: tmux on the server keeps the work).
    private fun pauseMosh() {
        if (moshWith == null) return stopMosh()
        Native.moshPause(true)
        update(mosh = "paused")
    }

    private fun stopMosh() {
        Native.moshStop()
        moshWith = null
        update(mosh = "off")
    }

    private fun trust(fingerprint: String?) {
        if (fingerprint == null || fingerprint != Session.unknownHost) return
        Bootstrap.remember(vault, "${settings.host}:${settings.port}", fingerprint)
        Session.unknownHost = null
        update(mosh = "off")
        startMosh()
    }

    private fun startKeyer() {
        if (settings.keyboard) return stopKeyer()
        if (keyer != null && keyerAddress != settings.keyer) stopKeyer()
        if (keyer != null || settings.keyer.isEmpty()) return
        keyerAddress = settings.keyer
        lateinit var k: KeyerLink
        k = KeyerLink(this, settings.keyer) { state -> work.post { if (keyer === k) keyerState(state) } }
        keyer = k
        k.start()
    }

    private fun stopKeyer() {
        val k = keyer ?: return
        k.quit()
        keyer = null
        Native.force(forced)
        update(keyer = "off")
    }

    // While the app holds keyer's keys the glasses show the terminal whatever the head's angle (R25, the
    // user's rule); otherwise the arms' looking-up rule decides. keyer coming or going decides what runs.
    private fun keyerState(state: String) {
        Native.force(state == "keys" || forced)
        update(keyer = state)
        refresh()
    }

    // After a native change: the mosh status and, while the terminal is live, a wake lock (R4).
    private fun poll() {
        val s = Native.status()
        val mosh = when {
            Session.mosh in setOf("off", "paused") || Session.mosh.startsWith("error") -> Session.mosh
            s[1] == 1 -> "ended"
            s[0] == 0 -> "connecting"
            s[2] > 10000 -> "no contact for ${s[2] / 1000} s"
            else -> "connected"
        }
        val shown = s[5] == 1
        val title = Native.texts()[1]
        if (mosh != Session.mosh || shown != Session.shown || title != Session.title) {
            Session.shown = shown
            Session.title = title
            update(mosh = mosh)
        } else Session.changed()
        if (mosh == "ended") work.post { stopMosh() } // the next refresh starts a new session
        // R20: a new session over SSH when the network is up and the server has not answered for 5 min.
        if (mosh != "paused" && s[0] == 1 && s[2] > 300000 && online()) work.post {
            stopMosh()
            startMosh()
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun update(glasses: String? = null, mosh: String? = null, keyer: String? = null, screen: String? = null) {
        glasses?.let { Session.glasses = it }
        mosh?.let { Session.mosh = it }
        keyer?.let { Session.keyer = it }
        screen?.let { Session.screen = it }
        Session.capturing = this.screen != null
        Session.showing = if (ended || !active()) "" else settings.show
        GlassesTile.refresh(this)
        SourceTile.refresh(this)
        // R4: awake only while the terminal is live, shown on an eye or typed into with keyer, or started from
        // the phone (Claude's output reaches the glasses with the phone's screen off).
        val live = Session.mosh == "connected" && Session.glasses == "ready" &&
            (Session.shown || Session.keyer == "keys" || Session.keyer == KEYBOARD ||
                (settings.phone && settings.show == Settings.TERMINAL))
        if (live && wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scouter:terminal").apply { acquire() }
        if (!live) wake?.release().also { wake = null }
        if (!ended) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        Session.changed()
    }

    private fun stateLine(): String {
        fun arm(up: Boolean) = if (up) "up" else "down"
        val glasses = if (Session.glasses == "ready") "L ${arm(Session.left)} R ${arm(Session.right)}" else "glasses ${Session.glasses}"
        val what = if (screen != null) "phone screen ${Session.screen}" else "mosh ${Session.mosh}"
        return "$glasses · $what · keyer ${Session.keyer}"
    }

    // The foreground service's types: the glasses link, and screen sharing while it runs (Android 14+ checks).
    private fun types() = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        (if (screen != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)

    private fun foreground() = main.post {
        if (!ended) runCatching { startForeground(NOTIFICATION, notification(), types()) }
            .onFailure { Log.w(TAG, "foreground: ${it.message}") }
    }

    // Android's consent came (MainActivity): in the foreground as mediaProjection first (Android 14+ requires it
    // before the projection), then the capture, then the glasses switch from the terminal to it. Main thread.
    private fun startScreen(intent: Intent) {
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            work.post { update(screen = "Android refused screen sharing: ${e.message}") }
            return
        }
        val projection = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }
            .getOrNull()
        if (projection == null) {
            foreground()
            work.post { update(screen = "screen sharing was not allowed") }
            return
        }
        screen?.release()
        val capture = PhoneScreen(this, projection) { work.post { screenEnded("stopped (screen sharing ended)") } }
        try {
            capture.start()
        } catch (e: Exception) {
            capture.release()
            screen = null
            foreground()
            work.post { update(screen = "error: ${e.message}") }
            return
        }
        screen = capture
        work.post {
            if (screen !== capture) return@post
            stopRemote()
            Session.screenSource = Settings.PHONE
            settings.source = Settings.PHONE
            Session.source = settings.sourceName(Settings.PHONE)
            settings.phone = true
            settings.show = Settings.SCREEN
            val error = Native.screenStart()
            if (error != null) {
                Log.w(TAG, "phone screen: $error")
                return@post screenEnded("error: $error")
            }
            update(screen = "starting")
            work.removeCallbacks(screenTicker)
            work.postDelayed(screenTicker, 2000)
            refresh()
        }
    }

    // A source on the glasses (Settings.sources): "term:ID" (that saved server's terminal), PHONE (the phone's
    // screen, while it is shared: Android asks in the app), "screen:ID" (that server's screen over SSH), or
    // next / prev through them.
    private fun source(want: String) {
        val all = settings.sources().map { it.first }.filter { it != Settings.PHONE || screen != null }
        val key = if (want == Settings.ACTION_NEXT || want == Settings.ACTION_PREV) {
            if (all.isEmpty()) return
            val now = if (settings.show == Settings.TERMINAL) "term:${settings.active()?.id}" else Session.screenSource
            val i = all.indexOf(now)
            all[Math.floorMod(if (i < 0) 0 else i + if (want == Settings.ACTION_NEXT) 1 else -1, all.size)]
        } else want
        settings.phone = true
        when {
            key.startsWith("term:") -> {
                val id = key.removePrefix("term:")
                if (settings.servers().none { it.id == id }) return
                if (settings.activeServer != id || settings.host.isEmpty()) settings.useServer(id)
                view(Settings.TERMINAL)
            }
            key == Settings.PHONE -> {
                if (screen == null) return update(screen = "not shared: tap Phone screen in the app")
                stopRemote()
                Session.screenSource = key
                view(Settings.SCREEN)
            }
            key.startsWith("screen:") -> {
                val s = settings.servers().firstOrNull { it.id == key.removePrefix("screen:") } ?: return
                startRemote(s)
                Session.screenSource = key
                view(Settings.SCREEN)
                update(screen = "starting")
                work.removeCallbacks(screenTicker)
                work.postDelayed(screenTicker, 2000)
            }
        }
        SourceTile.refresh(this)
    }

    private fun startRemote(s: Settings.Server) {
        if (remote?.server?.id == s.id) return
        stopRemote()
        lateinit var r: RemoteScreen
        r = RemoteScreen(this, s) { why -> work.post { remoteEnded(r, why) } }
        remote = r
        Session.remote = s.id
        r.start()
    }

    private fun stopRemote() {
        remote?.release()
        remote = null
        Session.remote = ""
        if (Session.screenSource.startsWith("screen:")) Session.screenSource = ""
    }

    // A server's screen stopped by itself (not reachable, no vox-screen there, ended): the terminal comes back.
    private fun remoteEnded(r: RemoteScreen, why: String) {
        if (remote !== r) return
        remote = null
        Session.remote = ""
        if (Session.screenSource == r.key) {
            Session.screenSource = ""
            if (screen == null) Native.screenStop()
            view(Settings.TERMINAL)
        }
        update(screen = why)
    }

    // What the glasses show: the terminal, or the phone's screen (only while it is shared).
    private fun view(show: String) {
        if (show == Settings.SCREEN && screen == null && remote == null) return update(screen = "not shared: tap Phone screen in the app")
        if (show == Settings.SCREEN && Session.screenSource.isEmpty()) Session.screenSource = if (remote != null) remote!!.key else Settings.PHONE
        if (show == Settings.TERMINAL) stopRemote()
        settings.show = show
        settings.source = if (show == Settings.TERMINAL) "term:${settings.active()?.id ?: ""}" else Session.screenSource
        Session.source = settings.sourceName(settings.source)
        if (show == Settings.SCREEN) {
            val error = Native.screenStart()
            if (error != null) {
                // The glasses keep the terminal: say so, and keep the sharing for another try.
                Log.w(TAG, "phone screen: $error")
                settings.show = Settings.TERMINAL
                update(screen = "error: $error")
                return refresh()
            }
        } else Native.screenStop()
        if (blankByLock) blankByLock = false
        update()
        refresh()
    }

    // The display on or off; why: for the status line.
    private fun display(on: Boolean, why: String) {
        if (!on == Session.blank) return
        Session.blank = !on
        if (on) blankByLock = false
        Native.blank(!on)
        Log.i(TAG, "display ${if (on) "on" else "off"} ($why)")
        update()
    }

    // A TouchBar gesture: what Settings says it does.
    private fun gesture(arm: Int, gesture: Int) {
        val action = settings.gestureAction(arm, gesture)
        val name = (if (arm == 0) "left " else "right ") + (Settings.GESTURES.firstOrNull { it.first == gesture }?.second ?: "gesture $gesture")
        Session.gesture = "$name: ${Settings.ACTIONS[action] ?: action}"
        when (action) {
            Settings.ACTION_DISPLAY -> {
                blankByLock = false
                display(Session.blank, name)
            }
            Settings.ACTION_VIEW -> if (screen != null || remote != null)
                view(if (settings.show == Settings.SCREEN) Settings.TERMINAL else Settings.SCREEN)
            Settings.ACTION_NEXT, Settings.ACTION_PREV -> source(action)
            Settings.ACTION_STOP -> {
                settings.phone = false
                end()
            }
        }
        Session.changed()
    }

    // Screen sharing over (Stop, the user in Android's status bar, or an error): back to the terminal.
    private fun screenEnded(why: String) {
        val capture = screen ?: return
        screen = null
        capture.release()
        // A server's screen on the glasses goes on.
        if (remote != null && Session.screenSource.startsWith("screen:")) {
            foreground()
            return update(screen = why)
        }
        work.removeCallbacks(screenTicker)
        Native.screenStop()
        Session.screenSource = ""
        settings.show = Settings.TERMINAL
        foreground()
        update(screen = why)
        refresh()
    }

    // GlassesLink: an arm's link went up or down.
    fun armChanged() = work.post { update() }

    // The app's one notification: the state, no actions (Settings is the only control).
    private fun notification(ongoing: Boolean = true): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val line = stateLine()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(Session.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .apply {
                if (!ongoing) return@apply
                fun action(code: Int, label: String, intent: Intent) = addAction(Notification.Action.Builder(null, label,
                    PendingIntent.getForegroundService(this@ScouterService, code, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
                fun to(action: String) = Intent(this@ScouterService, ScouterService::class.java).setAction(action)
                action(2, if (Session.blank) "Display on" else "Display off", to(ACTION_DISPLAY))
                if (screen != null) action(3, if (settings.show == Settings.SCREEN) "Terminal" else "Phone screen",
                    to(if (settings.show == Settings.SCREEN) ACTION_TERMINAL else ACTION_VIEW_SCREEN))
                action(1, "Stop", to(ACTION_STOP))
            }
            .build()
    }

    companion object {
        const val TAG = "scouter"
        const val CHANNEL = "session"
        const val NOTIFICATION = 1
        const val ACTION_STOP = "scouterkit.ssh.STOP"
        const val ACTION_TRUST = "scouterkit.ssh.TRUST"
        const val ACTION_NEAR = "scouterkit.ssh.NEAR" // keyer came or went (Session.near)
        const val ACTION_SCREEN = "scouterkit.ssh.SCREEN" // the phone's screen on the glasses (EXTRA_CODE, EXTRA_DATA)
        const val ACTION_TERMINAL = "scouterkit.ssh.TERMINAL" // the terminal on the glasses, started from the phone
        const val ACTION_VIEW_SCREEN = "scouterkit.ssh.VIEW_SCREEN" // the phone's screen again, while it is shared
        const val ACTION_STOP_SHARING = "scouterkit.ssh.STOP_SHARING" // screen sharing ends
        const val ACTION_DISPLAY = "scouterkit.ssh.DISPLAY" // the display on / off (EXTRA_ON, else the other way)
        const val ACTION_SOURCE = "scouterkit.ssh.SOURCE" // a source on the glasses (Shortcuts.EXTRA: a key, next, prev)
        const val ACTION_SERVER = "scouterkit.ssh.SERVER" // a saved server's terminal (extra "id"; none: the next)
        const val EXTRA_ON = "on"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val PROBE_MS = 30000L // how long keyer's link is tried without it coming up
        const val KEYBOARD = "phone keyboard" // Session.keyer in keyboard mode, while the app is in front
        const val KEYBOARD_GRACE_MS = 60000L // keyboard mode: the session lets go this long after the app left the front
        @Volatile var running = false // a service instance exists
        // Requested in Settings: Bluetooth (the links, and on Android 14+ the connectedDevice service's
        // prerequisite) and notifications (13+: the state).
        val PERMISSIONS = listOfNotNull(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)

        fun missing(context: Context) = PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        fun bluetooth(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        // Bluetooth as the user switched it: on only in STATE_ON. BLE_ON (Android keeps Bluetooth Low Energy on
        // for other apps' scans after the user switched Bluetooth off) is off for the app. The emulated flavour
        // (emulated arms in the app, no keyer) needs no Bluetooth.
        fun bluetoothOn(context: Context) = BuildConfig.FLAVOR == "emulated" ||
            bluetooth(context) && context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        // The native mosh session outlives a service: paused between keyer's sessions, in this process.
        @Volatile private var moshWith: String? = null // the settings it started with
        @Volatile private var moshSize = 0 to 0

        // Never without Bluetooth permission (Settings asks for it), nor with Bluetooth off (a running service
        // ends by itself then).
        fun send(context: Context, action: String? = null, extra: Pair<String, String>? = null) {
            if (!bluetooth(context)) {
                Log.w(TAG, "no Bluetooth permission: the service waits for Settings")
                return
            }
            if (!bluetoothOn(context)) {
                Log.i(TAG, "Bluetooth is off: no service")
                return
            }
            val intent = Intent(context, ScouterService::class.java).setAction(action)
            extra?.let { intent.putExtra(it.first, it.second) }
            context.startForegroundService(intent)
        }

        // Android's screen-sharing consent (MainActivity) to the service.
        fun sendScreen(context: Context, code: Int, data: Intent) {
            if (!bluetoothOn(context)) return
            context.startForegroundService(Intent(context, ScouterService::class.java).setAction(ACTION_SCREEN)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }
    }
}

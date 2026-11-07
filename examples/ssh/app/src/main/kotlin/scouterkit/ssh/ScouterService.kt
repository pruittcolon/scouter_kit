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
    @Volatile var voice = "" // voice: what the band says (or last said)
    @Volatile var listening = false
    @Volatile var dictating = false // what is said is typed into the terminal
    @Volatile var pointer = false // the pointer is on (its keys move and click it)
    @Volatile var tabs = "" // the last tab action's problem, if any
    // The glasses' cursor on the phone's screen, in 1/10000 of its width and height (-1: none): drawn into the
    // phone's picture (Settings.screenOptions), its clicks tapped there by VoxKeys.
    @Volatile var cursorX = -1
    @Volatile var cursorY = -1
    @Volatile var typing = false // keys typed into the phone's app: Enter and Space go to it (the arrows end it)
    // A quick reply to a notification (its own Reply), written on the dashboard: to whom, the words so far, and
    // "Sent" or a problem for a moment after. replyKey null: none.
    @Volatile var replyKey: String? = null
    @Volatile var replyTo: String? = null
    @Volatile var replyDraft = ""
    @Volatile var replyStatus = ""
    @Volatile var tabList = listOf<Tabs.Tab>() // the terminal's tabs (tmux windows), for the dashboard
    @Volatile var terms: Map<String, String> = emptyMap() // each terminal's state by server id (Term.status)
    @Volatile var heard = listOf<String>() // the last phrases, newest first
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
            // A server's screen chosen: on the glasses once its first image has come (its card until then).
            remote?.let { r ->
                if (r.images > 0 && chosen() == r.key) {
                    remoteTries = 0
                    remoteWhy = ""
                    if (settings.show != Settings.SCREEN) {
                        transition(if (fxKey == r.key && fxStyle != Fx.NONE) fxStyle else Fx.JUMP, r.key)
                        render(Settings.SCREEN)
                    }
                }
            }
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
                header()
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
        VoxKeys.onMouse = { x, y -> mouse(x, y) }
        VoxKeys.onTrackpad = { kind, dx, dy -> work.post { trackpad(kind, dx, dy) } }
        Looks.app = VoxKeys.app
        VoxKeys.onApp = { pkg ->
            work.post {
                Looks.app = pkg
                lookSwitch()
            }
        }
        settings.listen(lookPrefs)
        work.postDelayed(windowPoll, 3000)
        LookUp.onChange = { work.post { header() } }
        work.post(headerTick)
        work.postDelayed(tabPoll, 5000)
        work.postDelayed(watchdog, 60000)
        work.postDelayed(batteryTicker, 5000)
        registerReceiver(lock, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        registerReceiver(bonds, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    // The chosen terminal's session again after an error: soon at first, then less often (each terminal counts its
    // own failures); at once when the network comes back or the watchdog looks. This phone's own server needs no
    // network.
    private val moshRetry = Runnable { refresh() }

    private fun retryMosh() {
        val s = settings.active() ?: return
        val t = terms[s.id] ?: return
        if (chosen().startsWith("term:") && t.status.startsWith("error") && t.unknownHost == null && (s.local || online())) {
            t.retryAt = 0L
            refresh()
        }
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
                settings.active()?.let { choose("term:${it.id}") } ?: run {
                    settings.phone = true
                    settings.source = ""
                    render(Settings.TERMINAL) // the card: no terminal yet
                }
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
            // extra "fx": how it was asked for (an arrow: "left", "right", "up", "down"), for its transition.
            ACTION_SOURCE -> work.post { source(intent.getStringExtra(Shortcuts.EXTRA) ?: "", intent.getStringExtra("fx")) }
            ACTION_STOP_SHARING -> work.post { screenEnded("off") }
            // The display off / on (the notification, the Quick Settings tile, the app): extra "on" true/false,
            // absent: the other way.
            ACTION_DISPLAY -> work.post {
                val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, true) else Session.blank
                blankByLock = false
                display(on, "app")
            }
            ACTION_TRUST -> work.post { trust(intent.getStringExtra("fingerprint")) }
            ACTION_CLOSE -> work.post { closeSource() }
            ACTION_REPLY -> work.post { reply(intent.getStringExtra("key").orEmpty()) }
            // The pointer (extra "cmd": toggle, off, move:DX:DY, click, right, up, down) and tabs (extra "tab").
            ACTION_POINTER -> work.post { pointer(intent.getStringExtra("cmd").orEmpty()) }
            ACTION_TABS -> work.post { tabs(intent.getStringExtra("tab").orEmpty()) }
            // The look-up view's settings changed: the header and the fade again.
            ACTION_LOOK_UP -> work.post {
                Native.fxEnabled(settings.animations)
                Native.terminalFade(settings.fadeMs)
                Native.terminalDash(settings.dashRows)
                header()
                refresh()
            }
            // Voice: "listen" (one command; again: stop), "stop", or "always" (Settings.voiceAlways changed).
            ACTION_VOICE -> work.post {
                when (intent.getStringExtra("mode")) {
                    "stop" -> stopListening()
                    "always" -> {
                        if (!settings.voiceAlways && listener?.once == false) stopListening()
                        voiceAlways()
                    }
                    else -> listen(true)
                }
            }
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
        LookUp.onChange = null
        work.removeCallbacks(headerTick)
        work.removeCallbacks(tabPoll)
        Thread { Bootstrap.dropShared() }.start()
        screen?.release()
        screen = null
        remote?.release()
        remote = null
        Session.remote = ""
        Session.screenSource = ""
        Session.capturing = false
        Session.showing = ""
        Native.onGesture = null
        VoxKeys.onMouse = null
        VoxKeys.onTrackpad = null
        VoxKeys.capture(false)
        VoxKeys.onApp = null
        settings.unlisten(lookPrefs)
        work.removeCallbacks(windowPoll)
        work.removeCallbacks(batteryTicker)
        runCatching { unregisterReceiver(lock) }
        runCatching { unregisterReceiver(bonds) }
        runCatching { unregisterReceiver(adapterState) }
        work.removeCallbacks(watchdog)
        getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network)
        work.post { // ended by the system: as when keyer goes
            stopKeyer()
            pauseTerms(true)
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
        if (settings.show == Settings.SCREEN && screen == null && remote == null) settings.show = Settings.TERMINAL
        voiceAlways()
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
        // Saved servers removed or edited: their sessions end (an edited one starts anew when it is chosen).
        val servers = settings.servers()
        for (t in terms.values.toList()) {
            val s = servers.firstOrNull { it.id == t.id }
            if (s == null || (t.key != null && t.key != serverKey(s))) dropTerm(t)
        }
        if (moshSize != (settings.cols to settings.rows)) {
            moshSize = settings.cols to settings.rows
            Native.resize(moshSize.first, moshSize.second)
        }
        val key = chosen()
        // A server's screen chosen and not running: started (again after a failure once its wait is over); the
        // glasses keep its card until its first image.
        if (key.startsWith("screen:") && remote == null && key !in closed && SystemClock.elapsedRealtime() >= remoteRetryAt)
            servers.firstOrNull { "screen:${it.id}" == key }?.let { s ->
                startRemote(s)
                Session.screenSource = key
                work.removeCallbacks(screenTicker)
                work.postDelayed(screenTicker, 1000)
            }
        // A screen on the glasses: the terminals wait (paused, their sessions kept) until the terminal is back.
        if (settings.show == Settings.SCREEN) {
            pauseTerms(true)
            return
        }
        // The terminal's path: the chosen terminal (started on its own, never waiting for another one), or the
        // card of a source that cannot show yet.
        val server = if (key.startsWith("term:")) settings.active() else null
        pauseTerms(server == null)
        if (server != null) startTerm(server)
        showTerm(server)
    }

    // keyer gone or Bluetooth off (the user's battery rule): keyer's keys go back to it, mosh goes quiet but keeps
    // its session for keyer's next session in this process, and the glasses keep the terminal's program and last
    // screen and show them by themselves when the head looks up. A stop of the glasses link stays in the
    // notification.
    private fun end() {
        ended = true
        stopListening()
        voiceEngine?.let { e -> Thread { runCatching { e.release() } }.start() }
        voiceEngine = null
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
        pauseTerms(true)
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

    private fun serverKey(s: Settings.Server) = listOf(s.host, s.port, s.user, s.command, s.moshPorts).joinToString(" ")

    // A start in progress (Native.glassesStart waits up to 30 s for both arms: on its own thread, so taps, voice
    // and shortcuts never wait for it), and the starts that failed in a row (each retry waits longer).
    private var glassesStarting = false
    private var glassesTries = 0

    private fun startGlasses() {
        if (glassesWith != null || stopped != null || glassesStarting) return
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
        Native.fxEnabled(settings.animations)
        Native.terminalFade(settings.fadeMs)
        Native.terminalDash(settings.dashRows)
        Native.lookUpHeader(LookUp.lines(this, settings))
        val with = glassesSettings()
        val (left, right, dx, dy) = listOf(settings.left, settings.right, settings.dx.toString(), settings.dy.toString())
        val (brightness, angle) = settings.brightness to settings.angle
        val (ignoreWorn, battery) = (BuildConfig.DEBUG && settings.ignoreWorn) to settings.battery
        glassesStarting = true
        Thread({
            val error = runCatching {
                Native.glassesStart(l, left, right, dx.toInt(), dy.toInt(), brightness, angle, font, ignoreWorn, battery)
            }.getOrElse { it.message ?: "the glasses did not start" }
            work.post { glassesStarted(l, with, error) }
        }, "glasses-start").start()
    }

    // A start ended: the session goes on with the glasses, or (an error, or Stop / new settings meanwhile) they stop
    // and another start follows: soon at first, then less often (RETRY_MS).
    private fun glassesStarted(l: GlassesLink?, with: String, error: String?) {
        glassesStarting = false
        val stale = ended || !active() || with != glassesSettings()
        if (error != null || l?.lost != null || stale) {
            Native.glassesStop(false)
            l?.quit()
            l?.lost?.let { return lostGlasses(it) }
            if (error == null) return refresh() // stale: a start with what holds now, if anything
            val wait = RETRY_MS[minOf(glassesTries, RETRY_MS.size - 1)]
            glassesTries++
            Log.i(TAG, "glasses: $error; again in ${wait / 1000} s")
            // Both arms answered: the error is not about finding them, so it shows as it is.
            val found = Session.left && Session.right
            update(glasses = if (found) "$error (again in ${wait / 1000} s)"
                else "looking for the glasses (out of the case, nearby?), again in ${wait / 1000} s")
            work.postDelayed(retryGlasses, wait)
            return
        }
        glassesTries = 0
        val emulated = BuildConfig.FLAVOR == "emulated"
        link = l
        glassesWith = with
        if (emulated) Session.left = true.also { Session.right = true }
        update(glasses = "ready")
        work.removeCallbacks(batteryTicker)
        work.postDelayed(batteryTicker, 3000)
        header() // the dashboard on the new terminal
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

    // ------------------------------------------------ terminals

    // The source the user chose: "term:ID" (the saved server in use, Settings.active), PHONE or "screen:ID".
    // Only the user's choice sets it; what cannot show yet gets a card on the glasses, nothing switches by itself.
    private fun chosen(): String {
        val src = settings.source
        if (src == Settings.PHONE || src.startsWith("screen:")) return src
        return settings.active()?.let { "term:${it.id}" } ?: ""
    }

    // A terminal's session: its SSH start runs on its own thread (an unreachable server holds up nothing else),
    // then mosh keeps it, side by side with the other terminals', while the process lives.
    private fun startTerm(s: Settings.Server) {
        if (s.id in closed) return // closed by the user: until it is chosen again
        val t = terms.getOrPut(s.id) { Term(s.id) }
        if (t.started || t.thread?.isAlive == true || t.unknownHost != null) return
        if (t.status.startsWith("error") && SystemClock.elapsedRealtime() < t.retryAt) return
        t.key = serverKey(s)
        if (s.host.isEmpty() || s.user.isEmpty()) return failed(t, "no address (Terminal tab)")
        t.status = "starting"
        val record = (vault.text(vaultKey(t.id)) ?: vault.text(Vault.MOSH))?.split(" ")
        val previous = if (record?.getOrNull(4) == "${s.host}:${s.port}") record.getOrNull(3)?.toIntOrNull() ?: 0 else 0
        val cols = settings.cols
        val rows = settings.rows
        val termux = s.local && s.port == Termux.PORT
        val boot = Bootstrap(vault)
        Log.i(TAG, "terminal ${s.name}: starting")
        t.thread = Thread({
            val r = runCatching {
                // Termux on this phone: its sshd started first (it does not run by itself).
                if (termux) Termux.ensureSshd(this)?.let { throw IllegalStateException(it) }
                boot.start(s.host, s.port, s.user, s.command, s.moshPorts, previous)
            }
            work.post { started(t, s, r, cols, rows) }
        }, "vox-start-${s.id}").apply { isDaemon = true }
        t.thread?.start()
    }

    private fun started(t: Term, s: Settings.Server, r: Result<Bootstrap.Mosh>, cols: Int, rows: Int) {
        t.thread = null
        if (terms[t.id] !== t || ended) return // dropped meanwhile (its server edited or removed), or the service ended
        val m = r.getOrNull()
        val e = r.exceptionOrNull()
        when {
            m != null -> {
                vault.putText(vaultKey(t.id), "${m.ip} ${m.port} ${m.key} ${m.pid} ${s.host}:${s.port}")
                val error = Native.moshStart(t.id, m.ip, m.port, m.key, cols, rows)
                if (error == null) {
                    t.started = true
                    t.tries = 0
                    t.status = "connecting"
                    if (moshPaused) Native.moshPause(true)
                    if ((cols to rows) != moshSize) Native.resize(moshSize.first, moshSize.second)
                    Log.i(TAG, "terminal ${s.name}: started")
                } else failed(t, error)
            }
            e is Bootstrap.UnknownHost -> {
                // Termux's own host key, as Termux reported it at setup: trusted without asking.
                if (s.local && e.fingerprint in settings.termuxKeys.split(" ")) {
                    Bootstrap.remember(vault, "${s.host}:${s.port}", e.fingerprint)
                    t.status = "off"
                } else {
                    t.unknownHost = e.fingerprint
                    t.status = "error: confirm the host key"
                }
            }
            else -> {
                Log.w(TAG, "terminal ${s.name}: ${e?.message}")
                val refused = (e?.message ?: "").contains("refused", ignoreCase = true)
                failed(t, if (s.local && refused) "this phone's SSH server is off (type ssh in Termux)" else e?.message ?: "failed")
            }
        }
        refresh() // shown (or its card) if it is the one chosen
        update()
    }

    private fun failed(t: Term, why: String) {
        t.status = "error: $why"
        val wait = RETRY_MS[minOf(t.tries, RETRY_MS.size - 1)]
        t.tries++
        t.retryAt = SystemClock.elapsedRealtime() + wait
        work.removeCallbacks(moshRetry)
        work.postDelayed(moshRetry, wait + 50)
    }

    // A terminal's session ends (its server edited or removed, its command ended, no answer for long).
    private fun dropTerm(t: Term) {
        terms.remove(t.id)
        if (t.started) Native.moshStop(t.id)
        if (moshShown == t.id) moshShown = ""
        tabCache.remove(t.id)
    }

    // Every terminal's session quiet (keyer gone, a screen or a card shown) or going again.
    private fun pauseTerms(on: Boolean) {
        if (moshPaused == on) return
        moshPaused = on
        Native.moshPause(on)
    }

    // The chosen terminal on the glasses: its session once it has one, else (and for a screen that cannot show)
    // a card: the source's name, what is going on, and how to switch. Its tabs on the dashboard.
    private var tabsFor: String? = null
    private fun showTerm(server: Settings.Server?) {
        val t = server?.let { terms[it.id] }
        val shown = if (t != null && t.started) t.id else ""
        if (shown != moshShown) {
            moshShown = shown
            Native.moshShow(shown)
        }
        if (server?.id != tabsFor) {
            tabsFor = server?.id
            Session.tabList = server?.let { tabCache[it.id] }.orEmpty()
            dash()
            if (t?.status == "connected") fetchTabs()
        }
        card(cardLines(server, t))
    }

    private fun card(lines: List<String>) {
        val size = settings.cols to settings.rows
        if (lines == cardShown && size == cardSize) return
        cardShown = lines
        cardSize = size
        Native.card(lines.toTypedArray(), size.first, size.second)
    }

    private fun cardLines(server: Settings.Server?, t: Term?): List<String> {
        val key = chosen()
        val hint = switchHint()
        val lines = when {
            key == Settings.PHONE -> listOf("PHONE SCREEN", "", "Not shared right now", "Share it in Vox: Phone screen", "", hint)
            key.startsWith("screen:") -> {
                val name = settings.servers().firstOrNull { "screen:${it.id}" == key }?.name ?: "Screen"
                if (key in closed) listOf("${name.uppercase()} SCREEN", "", "Closed", "Choose it again to start it", "", hint)
                else if (remoteWhy.isEmpty()) listOf("${name.uppercase()} SCREEN", "", "Starting…", "", hint)
                else listOf("${name.uppercase()} SCREEN", "", remoteWhy, "Trying again by itself", "", hint)
            }
            server == null -> listOf("NO TERMINAL", "", "Add a server in Vox: Terminal tab", "", hint)
            server.id in closed -> listOf(server.name.uppercase(), "", "Closed", "Choose it again to connect", "", hint)
            t == null || t.status == "off" || t.status == "starting" || t.status == "connecting" ->
                listOf(server.name.uppercase(), "", "Connecting…", "", hint)
            t.unknownHost != null -> listOf(server.name.uppercase(), "", "New host key: confirm it in Vox", "", hint)
            t.status.startsWith("error") ->
                listOf(server.name.uppercase(), "", "Can't connect: " + t.status.removePrefix("error: "), "Trying again by itself", "", hint)
            else -> emptyList()
        }
        val room = maxOf(8, settings.cols - 2)
        return lines.map { if (it.length <= room) it else it.take(room - 1) + "…" }
    }

    // How to switch: the user's own TouchBar gesture for the next source (Settings), and the keyboard's.
    private fun switchHint(): String {
        val touch = (0..1).flatMap { arm ->
            Settings.GESTURES.filter { settings.gestureAction(arm, it.first) == Settings.ACTION_NEXT }
                .map { (if (arm == 0) "left " else "right ") + it.second }
        }.firstOrNull()
        return listOfNotNull(touch?.let { "Next source: $it" }, "Alt+arrows").joinToString(" · ")
    }

    // Alt+Q: the source shown closes (a terminal's connection ends, a screen stops) and the glasses go to the next
    // one still open; with none, the card says it is closed until it is chosen again.
    private val closed = HashSet<String>() // terminals' ids and "screen:ID" keys the user closed
    private fun closeSource() {
        val key = chosen()
        when {
            key.startsWith("term:") -> {
                val id = key.removePrefix("term:")
                closed += id
                terms[id]?.let { dropTerm(it) }
            }
            key == Settings.PHONE -> screen?.let { screenEnded("closed") }
            key.startsWith("screen:") -> {
                closed += key
                stopRemote()
                remoteWhy = "closed"
            }
            else -> return
        }
        Log.i(TAG, "closed $key")
        val open = settings.sources().map { it.first }.firstOrNull { k ->
            k != key && when {
                k.startsWith("term:") -> terms[k.removePrefix("term:")]?.started == true
                k == Settings.PHONE -> screen != null
                else -> false
            }
        }
        if (open != null) choose(open) else render(Settings.TERMINAL)
        update()
    }

    private fun vaultKey(id: String) = Vault.MOSH + "." + id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun trust(fingerprint: String?) {
        val s = settings.active() ?: return
        val t = terms[s.id] ?: return
        if (fingerprint == null || fingerprint != t.unknownHost) return
        Bootstrap.remember(vault, "${s.host}:${s.port}", fingerprint)
        t.unknownHost = null
        t.status = "off"
        t.retryAt = 0L
        update()
        refresh()
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
        var changed = false
        for (t in terms.values.toList()) {
            if (!t.started) continue
            val st = Native.moshStatus(t.id) ?: continue
            val now = when {
                st[1] == 1 -> "ended"
                st[0] == 0 -> "connecting"
                st[2] > 10000 -> "no contact for ${st[2] / 1000} s"
                else -> "connected"
            }
            if (now != t.status) {
                if (now == "connected" && t.status == "connecting" && t.id == settings.active()?.id) work.post { fetchTabs() }
                t.status = now
                changed = true
            }
            // Its command ended, or (R20) its server has not answered for 5 min with the network up: a new session
            // (the chosen terminal's at once, another's when it is chosen again). This phone's own server needs no
            // network and does not roam: silent 20 s, its mosh-server is gone (its SSH server stopped with it).
            val local = settings.servers().firstOrNull { it.id == t.id }?.local == true
            val dead = !moshPaused && st[0] == 1 && (if (local) st[2] > 20000 else st[2] > 300000 && online())
            if (now == "ended" || dead) work.post {
                if (terms[t.id] === t) {
                    dropTerm(t)
                    refresh()
                }
            }
        }
        val shown = s[5] == 1
        val title = Native.texts()[1]
        if (changed || shown != Session.shown || title != Session.title) {
            Session.shown = shown
            Session.title = title
            if (changed && !ended && settings.show == Settings.TERMINAL)
                showTerm(if (chosen().startsWith("term:")) settings.active() else null)
            update()
        } else Session.changed()
    }

    private fun online(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun update(glasses: String? = null, keyer: String? = null, screen: String? = null) {
        glasses?.let { Session.glasses = it }
        // The chosen terminal's state, for the app and the notification.
        val t = settings.active()?.let { terms[it.id] }
        Session.mosh = if (t != null && t.started && moshPaused) "paused" else t?.status ?: "off"
        Session.unknownHost = t?.unknownHost
        keyer?.let { Session.keyer = it }
        screen?.let { Session.screen = it }
        Session.capturing = this.screen != null
        Session.terms = terms.mapValues { it.value.status }
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
        trackpadMode()
        Session.changed()
    }

    // ------------------------------------------------ the trackpad

    // A trackpad or mouse paired with the phone: taken for the glasses' pointer while they show the terminal (or a
    // card) and the user sees it; otherwise it is the phone's own pointer (on the phone's screen the glasses draw
    // where it is).
    private fun trackpadMode() {
        VoxKeys.capture(!ended && settings.trackpad && glassesWith != null && !Session.blank &&
            settings.show == Settings.TERMINAL && Session.shown)
    }

    private var glideX = 0f
    private var glideY = 0f
    private var glideBar = false // the pointer was on the dashboard at the last glide
    private var swipe = 0f

    // The trackpad's motion: the pointer glides a cell at a time in the terminal (fractions kept, faster strokes go
    // further) and a place at a time on the dashboard; a tap clicks, a right click (two fingers) right-clicks, the
    // wheel and two-finger swipes scroll. A click while the pointer is off only shows it.
    private fun trackpad(kind: Int, dx: Float, dy: Float) {
        when (kind) {
            VoxKeys.Trackpad.MOVE -> glide(dx, dy)
            VoxKeys.Trackpad.CLICK, VoxKeys.Trackpad.RIGHT -> {
                if (!Session.pointer) return pointer("toggle")
                pointer(if (kind == VoxKeys.Trackpad.RIGHT) "right" else "click")
            }
            VoxKeys.Trackpad.WHEEL -> repeat(minOf(5, kotlin.math.abs(dy).toInt().coerceAtLeast(1))) {
                pointer(if (dy > 0) "up" else "down")
            }
            VoxKeys.Trackpad.SCROLL -> {
                swipe += dy
                val step = 28f
                while (kotlin.math.abs(swipe) >= step) {
                    pointer(if (swipe > 0) "up" else "down")
                    swipe -= if (swipe > 0) step else -step
                }
            }
        }
    }

    private fun glide(dx: Float, dy: Float) {
        val onBar = pointerBar >= 0
        if (onBar != glideBar) {
            glideBar = onBar
            glideX = 0f
            glideY = 0f
        }
        // Speed: the setting, and a little acceleration for quick strokes.
        val v = kotlin.math.hypot(dx, dy)
        val k = settings.trackpadSpeed.coerceIn(1, 10) / 5f * (1f + minOf(1.5f, v / 40f))
        glideX += dx * k
        glideY += dy * k
        // A cell is about 6 x 12 trackpad pixels; a place on the dashboard 30 across, 20 down.
        val (sx, sy) = if (onBar) 30f to 20f else 6f to 12f
        val cx = (glideX / sx).toInt()
        val cy = (glideY / sy).toInt()
        if (cx == 0 && cy == 0) return
        glideX -= cx * sx
        glideY -= cy * sy
        if (onBar) {
            // One place at a time, rows first (a diagonal stroke goes where it mostly points).
            if (cy != 0 && kotlin.math.abs(cy) * sy >= kotlin.math.abs(cx) * sx) pointer("move:0:${if (cy > 0) 1 else -1}")
            else if (cx != 0) repeat(minOf(3, kotlin.math.abs(cx))) { pointer("move:${if (cx > 0) 1 else -1}:0") }
        } else pointer("move:$cx:$cy")
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
            pendingNote?.let { key ->
                pendingNote = null
                work.postDelayed({ openNote(key) }, 500)
            }
        }
    }

    // A source on the glasses (Settings.sources): "term:ID" (that saved server's terminal), PHONE (the phone's
    // screen), "screen:ID" (that server's screen over SSH), or next / prev through them (skipping the phone's
    // screen while it is not shared).
    // how: the way it was asked for (an arrow, next / prev; else a jump), for the transition on the glasses.
    private fun source(want: String, how: String? = null) {
        val key = if (want == Settings.ACTION_NEXT || want == Settings.ACTION_PREV) {
            val all = settings.sources().map { it.first }.filter { it != Settings.PHONE || screen != null }
            if (all.isEmpty()) return
            val i = all.indexOf(chosen())
            all[Math.floorMod(if (i < 0) 0 else i + if (want == Settings.ACTION_NEXT) 1 else -1, all.size)]
        } else want
        choose(key, Fx.style(how ?: want))
    }

    // ------------------------------------------------ transitions

    // The glasses go to `key` with the style's transition (Fx.kt): what shows goes now, a label says where to,
    // and the next picture comes in. Nothing while the display is off or the glasses are not up.
    private var fxStyle = Fx.NONE // the last switch's, for a server's screen that shows once its first image comes
    private var fxKey = ""
    private fun transition(style: Int, key: String, now: Boolean = false, label: Pair<String, String>? = null) {
        if (!settings.animations || style == Fx.NONE || Session.blank || glassesWith == null) return
        val toTerminal = when {
            key.startsWith("term:") -> true
            key == Settings.PHONE -> screen == null // not shared: its card
            key.startsWith("screen:") -> remote?.key != key || (remote?.images ?: 0) == 0 // its card until an image
            else -> true
        }
        val (name, kind) = label ?: fxLabel(key)
        val l = Fx.label(name, kind)
        Native.transition(style, l.packed, l.w, l.h, toTerminal, now)
        fxStyle = style
        fxKey = key
    }

    private fun fxLabel(key: String): Pair<String, String> {
        val id = key.substringAfter(':')
        val server = settings.servers().firstOrNull { it.id == id }
        return when {
            key == Settings.PHONE -> "Phone" to "screen"
            key.startsWith("screen:") -> (server?.name ?: "Screen") to "screen"
            else -> (server?.name ?: "Terminal") to "terminal"
        }
    }

    // The user's choice (Settings.source): kept until they choose another. One that cannot show yet (a server
    // that does not answer, the phone's screen not shared) shows its card; nothing switches by itself.
    private fun choose(key: String, style: Int = Fx.JUMP) {
        settings.phone = true
        val from = chosen()
        val fx = if (key != from || (key == Settings.PHONE && screen != null && settings.show != Settings.SCREEN)) style else Fx.NONE
        when {
            key.startsWith("term:") -> {
                val id = key.removePrefix("term:")
                if (settings.servers().none { it.id == id }) return
                transition(fx, key)
                if (settings.activeServer != id || settings.host.isEmpty()) settings.useServer(id)
                // Chosen again: a failed one is tried at once, a silent one starts anew.
                terms[id]?.let { if (it.status.startsWith("error")) it.retryAt = 0L else if (it.status.startsWith("no contact")) dropTerm(it) }
                closed.remove(key)
                closed.remove(id)
                settings.source = key
                stopRemote()
                render(Settings.TERMINAL)
            }
            key == Settings.PHONE -> {
                stopRemote() // first: no more of the server's pictures (one of them would come in with it)
                transition(fx, key)
                settings.source = key
                lastScreen = key
                if (screen == null) {
                    update(screen = "not shared: tap Phone screen in the app")
                    render(Settings.TERMINAL) // its card
                } else {
                    Session.screenSource = key
                    render(Settings.SCREEN)
                }
            }
            key.startsWith("screen:") -> {
                val s = settings.servers().firstOrNull { it.id == key.removePrefix("screen:") } ?: return
                transition(fx, key)
                closed.remove(key)
                settings.source = key
                lastScreen = key
                remoteTries = 0
                remoteRetryAt = 0L
                remoteWhy = ""
                Session.screenSource = key
                if (remote?.key != key) {
                    startRemote(s)
                    render(Settings.TERMINAL) // its card ("Starting…") until its first image
                } else render(if (remote!!.images > 0) Settings.SCREEN else Settings.TERMINAL)
                update(screen = "starting")
                work.removeCallbacks(screenTicker)
                work.postDelayed(screenTicker, 1000)
            }
            else -> return
        }
        Session.source = settings.sourceName(key)
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

    // A server's screen stopped by itself (not reachable, no vox-screen there, ended): it stays the choice, its
    // card says why, and it is tried again, soon at first, then less often.
    private var remoteWhy = ""
    private var remoteTries = 0
    private var remoteRetryAt = 0L
    private var lastScreen = "" // the screen last chosen (the TouchBar's terminal / screen switch goes back to it)
    private fun remoteEnded(r: RemoteScreen, why: String) {
        if (remote !== r) return
        remote = null
        Session.remote = ""
        Log.w(TAG, "remote screen: $why")
        remoteWhy = why
        val wait = RETRY_MS[minOf(remoteTries, RETRY_MS.size - 1)]
        remoteTries++
        remoteRetryAt = SystemClock.elapsedRealtime() + wait
        work.postDelayed({ if (chosen() == r.key) refresh() }, wait + 50)
        if (chosen() == r.key) {
            if (screen == null) Native.screenStop()
            render(Settings.TERMINAL) // its card
        }
        update(screen = why)
    }

    // ------------------------------------------------ the pointer and tabs

    private var pointerRow = 0
    private var pointerCol = 0
    // Above the terminal's top row the pointer is on the dashboard's tab bar: the place it is on (an index in
    // barHits, the bar's tabs and + as last drawn), or -1 while it is in the terminal.
    private var pointerBar = -1
    private var barHits: List<Dashboard.Hit> = emptyList()
    private var barWidth = 0
    private val pointerIdle = Runnable { // hidden after a while; its keys still work
        Native.pointer(-1, -1)
        hideCursor()
        if (pointerBar >= 0) {
            pointerBar = -1
            pointerRow = 0
            dash()
        }
    }

    private fun pointer(cmd: String) {
        val parts = cmd.split(":")
        if (onPhone()) {
            phonePointer(parts)
            Session.changed()
            return
        }
        when (parts[0]) {
            "toggle" -> if (Session.pointer) pointerOff() else pointerOn()
            "off" -> pointerOff()
            "move" -> {
                if (!Session.pointer) pointerOn()
                val dx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val dy = parts.getOrNull(2)?.toIntOrNull() ?: 0
                if (pointerBar >= 0) barMove(dx, dy)
                else if (pointerRow + dy < 0 && barHits.isNotEmpty() && settings.dashRows > 0) barEnter()
                else {
                    pointerRow = (pointerRow + dy).coerceIn(0, settings.rows - 1)
                    pointerCol = (pointerCol + dx).coerceIn(0, settings.cols - 1)
                }
                showPointer()
            }
            "click", "right", "up", "down" -> {
                if (!Session.pointer) pointerOn()
                if (pointerBar >= 0) barClick(parts[0])
                else {
                    val button = when (parts[0]) { "right" -> 2; "up" -> 64; "down" -> 65; else -> 0 }
                    if (!Native.mouse(button, pointerRow, pointerCol))
                        Session.tabs = "the terminal's program takes no mouse (in tmux: Mouse in tmux, Terminal tab)"
                    else Session.tabs = ""
                }
                showPointer()
            }
        }
        Session.changed()
    }

    // The column under a place on the bar, and the place nearest a column (the dashboard is the terminal's width).
    private fun barCol(hit: Dashboard.Hit): Int =
        if (barWidth <= 0) pointerCol else (hit.center * settings.cols / barWidth).toInt().coerceIn(0, settings.cols - 1)

    private fun barNearest(col: Int): Int {
        val x = if (settings.cols <= 0) 0f else (col + 0.5f) * barWidth / settings.cols
        return barHits.indices.minByOrNull { i ->
            val h = barHits[i]
            if (x < h.left) h.left - x else if (x > h.right) x - h.right else 0f
        } ?: -1
    }

    // The dashboard's places by row (top to bottom: the tab bar, then the notifications), each left to right.
    private fun barRows(): List<List<Int>> = barHits.indices.groupBy { barHits[it].top }.toSortedMap().values
        .map { row -> row.sortedBy { barHits[it].left } }

    private fun nearestIn(row: List<Int>, x: Float): Int? = row.minByOrNull { i ->
        val h = barHits[i]
        if (x < h.left) h.left - x else if (x > h.right) x - h.right else 0f
    }

    // Up from the terminal's top row: onto the dashboard's lowest row (the notifications, else the tab bar), at the
    // place above the pointer (else the nearest).
    private fun barEnter() {
        val rows = barRows()
        val x = if (settings.cols <= 0) 0f else (pointerCol + 0.5f) * barWidth / settings.cols
        pointerBar = rows.lastOrNull()?.let { nearestIn(it, x) } ?: -1
        if (pointerBar < 0) return
        Native.pointer(-1, -1) // the cell in the terminal goes; the dashboard shows where it is
        dash()
    }

    // On the dashboard: left and right go a place at a time in a row, up and down a row (to the place nearest
    // above or below); down from the lowest row goes back into the terminal under it.
    private fun barMove(dx: Int, dy: Int) {
        val rows = barRows()
        val r = rows.indexOfFirst { pointerBar in it }
        val here = barHits.getOrNull(pointerBar)
        if (r < 0 || here == null) return barLeave()
        when {
            dy > 0 && r == rows.size - 1 -> {
                pointerCol = barCol(here)
                return barLeave()
            }
            dy > 0 -> pointerBar = nearestIn(rows[r + 1], here.center) ?: pointerBar
            dy < 0 && r > 0 -> pointerBar = nearestIn(rows[r - 1], here.center) ?: pointerBar
            dx != 0 -> {
                val row = rows[r]
                pointerBar = row[(row.indexOf(pointerBar) + if (dx > 0) 1 else -1).coerceIn(0, row.size - 1)]
            }
            else -> return
        }
        dash()
    }

    private fun barLeave() {
        pointerBar = -1
        pointerRow = 0
        dash()
    }

    // A click on the bar: a tab is shown (a right click closes it), the + makes another, PHONE brings up the
    // phone's screen; the wheel goes to the tab before or after.
    private fun barClick(what: String) {
        val hit = barHits.getOrNull(pointerBar) ?: return
        Session.tabs = ""
        when {
            hit.action == "phone" -> if (what == "click") {
                barLeave()
                phoneScreen()
            }
            hit.action.startsWith("note:") -> {
                val key = hit.action.removePrefix("note:")
                if (what == "click") openNote(key) else if (what == "right") replyStart(key)
            }
            what == "click" -> tabs(hit.action)
            what == "right" && hit.action.startsWith("select:") -> tabs("close:" + hit.action.removePrefix("select:"))
            what == "up" -> tabs("prev")
            what == "down" -> tabs("next")
        }
    }

    // The phone's screen on the glasses: at once while it is shared, else Android asks in the app (it comes up on
    // the phone) and the glasses say so meanwhile.
    private fun phoneScreen() {
        if (screen == null) runCatching {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHARE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "share: ${it.message}") }
        choose(Settings.PHONE, Fx.DOWN)
    }

    // ------------------------------------------------ notifications

    // A notification clicked on the dashboard: its app opens on the phone (as its notification would) and the
    // glasses show the phone's screen with the cursor ready, to click on through and type with the keyboard.
    // The screen not shared yet: Android asks first, then the notification opens.
    private var pendingNote: String? = null
    private fun openNote(key: String) {
        barLeave()
        if (screen == null) {
            pendingNote = key
            return phoneScreen()
        }
        runCatching {
            startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { Log.w(TAG, "note: ${it.message}") }
        choose(Settings.PHONE)
        Session.typing = false
        if (onPhone()) {
            Session.pointer = true
            cursorX = 5000
            cursorY = 5000
            showCursor()
        }
    }

    // A quick reply (a right click on a notification that has its own Reply): typed (VoxKeys takes every key
    // meanwhile) or said (Alt+Space), shown on the dashboard, sent with Enter through the notification.
    private val replyClear = Runnable {
        Session.replyKey = null
        Session.replyTo = null
        Session.replyStatus = ""
        dash()
        Session.changed()
    }

    private fun replyStart(key: String) {
        val n = LookUp.notes.firstOrNull { it.key == key } ?: return
        if (!n.reply) return openNote(key)
        work.removeCallbacks(replyClear)
        Session.replyKey = key
        Session.replyTo = n.title.ifBlank { n.app }
        Session.replyDraft = ""
        Session.replyStatus = ""
        dash()
        Session.changed()
    }

    // "c:TEXT" adds, "del" takes the last character, "send", "cancel".
    private fun reply(what: String) {
        val key = Session.replyKey ?: return
        if (Session.replyStatus.isNotEmpty()) return
        when {
            what.startsWith("c:") -> Session.replyDraft += what.removePrefix("c:")
            what == "del" -> Session.replyDraft = Session.replyDraft.dropLast(1)
            what == "cancel" -> return replyClear.run()
            what == "send" -> {
                val text = Session.replyDraft.trim()
                if (text.isEmpty()) return replyClear.run()
                val ok = VoxNotifications.reply(this, key, text)
                Log.i(TAG, "reply: " + if (ok) "sent" else "failed")
                Session.replyStatus = if (ok) "Sent \u2713" else "Could not send: open it instead (click)"
                work.postDelayed(replyClear, if (ok) 1500 else 3000)
            }
        }
        dash()
        Session.changed()
    }

    // ------------------------------------------------ the cursor on the phone's screen

    // The phone's own screen on the glasses: the pointer is a cursor on it, its clicks are taps (VoxKeys).
    private fun onPhone() = screen != null && settings.show == Settings.SCREEN &&
        Session.screenSource.ifEmpty { Settings.PHONE } == Settings.PHONE

    private var cursorX = 5000
    private var cursorY = 5000

    private fun phonePointer(parts: List<String>) {
        when (parts[0]) {
            "toggle" -> if (Session.pointer) pointerOff() else pointerOn()
            "off" -> pointerOff()
            "move" -> {
                if (!Session.pointer) pointerOn()
                // A step: 1/60 of the screen across, 1/45 down (the keyboard's arrows, a cell of the touchpad).
                cursorX = (cursorX + (parts.getOrNull(1)?.toIntOrNull() ?: 0) * 167).coerceIn(0, 10000)
                cursorY = (cursorY + (parts.getOrNull(2)?.toIntOrNull() ?: 0) * 222).coerceIn(0, 10000)
                showCursor()
            }
            "click", "right", "up", "down" -> {
                if (!Session.pointer) pointerOn()
                val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                val x = cursorX / 10000f * (b.width() - 1)
                val y = cursorY / 10000f * (b.height() - 1)
                val ok = when (parts[0]) {
                    "click" -> VoxKeys.tap(x, y)
                    "right" -> VoxKeys.tap(x, y, long = true)
                    "up" -> VoxKeys.scroll(x, y, -b.height() / 5f, b.height().toFloat())
                    else -> VoxKeys.scroll(x, y, b.height() / 5f, b.height().toFloat())
                }
                Session.tabs = if (ok) "" else "clicks on the phone need Vox's keyboard shortcuts (Android Settings: Accessibility, Vox)"
                showCursor()
            }
        }
    }

    // A mouse or trackpad paired with the phone moves Android's own pointer (and clicks for itself); VoxKeys
    // watches where it goes, and the glasses draw the cursor there while they show the phone's screen.
    private var mouseAt = 0L
    private fun mouse(x: Float, y: Float) {
        if (!onPhone()) return
        val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        if (b.width() <= 1 || b.height() <= 1) return
        cursorX = (x / (b.width() - 1) * 10000).toInt().coerceIn(0, 10000)
        cursorY = (y / (b.height() - 1) * 10000).toInt().coerceIn(0, 10000)
        Session.cursorX = cursorX
        Session.cursorY = cursorY
        val now = SystemClock.uptimeMillis()
        if (now - mouseAt > 500) { // the hiding after a while, pushed back at most twice a second
            mouseAt = now
            work.post {
                work.removeCallbacks(pointerIdle)
                work.postDelayed(pointerIdle, 10000)
            }
        }
    }

    // The cursor into the phone's picture (PhoneScreen sends it again when the options change); hidden after a while.
    private fun showCursor() {
        Session.cursorX = cursorX
        Session.cursorY = cursorY
        work.removeCallbacks(pointerIdle)
        work.postDelayed(pointerIdle, 10000)
    }

    private fun hideCursor() {
        Session.cursorX = -1
        Session.cursorY = -1
    }

    private fun pointerOn() {
        Session.pointer = true
        if (onPhone()) {
            cursorX = 5000
            cursorY = 5000
            return showCursor()
        }
        if (settings.tmuxMouse) tabs("mouse")
        pointerRow = settings.rows / 2
        pointerCol = settings.cols / 2
        showPointer()
    }

    private fun pointerOff() {
        Session.pointer = false
        work.removeCallbacks(pointerIdle)
        Native.pointer(-1, -1)
        hideCursor()
        if (pointerBar >= 0) barLeave()
    }

    private fun showPointer() {
        Native.pointer(if (pointerBar >= 0) -1 else pointerRow, if (pointerBar >= 0) -1 else pointerCol)
        work.removeCallbacks(pointerIdle)
        work.postDelayed(pointerIdle, 10000)
    }

    // The tabs for the dashboard: asked of tmux every 5 s while the terminal shows on the glasses (over the kept
    // SSH connection), and right after a tab action.
    private var tabsAsking = false
    private val tabPoll = object : Runnable {
        override fun run() {
            if (Session.shown && settings.show == Settings.TERMINAL && settings.dashRows > 0) fetchTabs()
            work.postDelayed(this, 5000)
        }
    }

    // The chosen terminal's tabs, asked of its tmux only while its session is connected; each terminal keeps its
    // own list (tabCache), so a switch shows the right tabs at once.
    private fun fetchTabs() {
        val s = settings.active() ?: return
        if (!chosen().startsWith("term:")) return
        val session = Tabs.session(s)
        if (session == null) {
            if (Session.tabList.isNotEmpty()) {
                Session.tabList = emptyList()
                dash()
            }
            return
        }
        if (terms[s.id]?.status != "connected" || tabsAsking) return
        val cmd = Tabs.command("list", session) ?: return
        tabsAsking = true
        Thread {
            val list = runCatching { Tabs.parse(Bootstrap(vault).runShared(s.host, s.port, s.user, cmd)) }.getOrNull()
            work.post {
                tabsAsking = false
                if (list == null) return@post
                tabCache[s.id] = list
                if (settings.active()?.id == s.id && (list.size != Session.tabList.size ||
                        list.zip(Session.tabList).any { (a, b) -> a.index != b.index || a.name != b.name || a.active != b.active })) {
                    Session.tabList = list
                    dash()
                }
            }
        }.start()
    }

    // A tab action (Tabs.command) in the server in use, over SSH beside mosh, off the work thread.
    private fun tabs(action: String) {
        val s = settings.active() ?: return
        val session = Tabs.session(s)
        if (session == null) {
            Session.tabs = "tabs need tmux on ${s.name}: its command like tmux new -A -s main"
            Session.changed()
            return
        }
        if (terms[s.id]?.status != "connected") {
            if (action != "mouse") Session.tabs = "${s.name} is not connected yet"
            Session.changed()
            return
        }
        val cmd = Tabs.command(action, session) ?: return
        tabTransition(action, s)
        Thread {
            val r = runCatching { Bootstrap(vault).runShared(s.host, s.port, s.user, cmd) }
            work.post {
                Session.tabs = r.exceptionOrNull()?.let { "tabs: ${it.message}" } ?: ""
                Session.changed()
                fetchTabs() // the dashboard's tab bar at once
            }
        }.start()
    }

    // A tab change on the glasses: a quick stream toward the tab it goes to (its name on the label); the terminal's
    // next screen (tmux's redraw) comes in with it.
    private fun tabTransition(action: String, s: Settings.Server) {
        if (settings.show != Settings.TERMINAL || chosen() != "term:${s.id}") return
        val list = Session.tabList
        val at = list.indexOfFirst { it.active }
        val target = when {
            action == "next" -> if (list.isEmpty()) null else list[Math.floorMod(at + 1, list.size)]
            action == "prev" -> if (list.isEmpty()) null else list[Math.floorMod(at - 1, list.size)]
            action.startsWith("select:") -> list.firstOrNull { it.index.toString() == action.removePrefix("select:") }
            else -> null
        }
        val active = list.getOrNull(at)
        val style = when {
            action == "new" || action == "claude" || action == "cursor" -> Fx.TAB_RIGHT
            action == "next" -> Fx.TAB_RIGHT
            action == "prev" -> Fx.TAB_LEFT
            // Closing the tab shown: tmux shows another.
            action == "close" || (action.startsWith("close:") && active != null && action == "close:${active.index}") -> Fx.TAB_LEFT
            target != null && active != null && target.index != active.index ->
                if (list.indexOf(target) > at) Fx.TAB_RIGHT else Fx.TAB_LEFT
            else -> Fx.NONE
        }
        val label = when {
            action == "new" -> "New tab" to s.name
            action == "claude" || action == "cursor" -> action.replaceFirstChar { it.uppercase() } to s.name
            target != null -> "${target.index} ${target.name}" to s.name
            else -> s.name to "tab"
        }
        transition(style, "term:${s.id}", now = true, label = label)
    }

    // ------------------------------------------------ the look-up header

    // The header's lines again: at each minute, when notifications or the batteries change, and on request.
    private val headerTick = object : Runnable {
        override fun run() {
            header()
            val now = System.currentTimeMillis()
            work.postDelayed(this, 60000 - now % 60000 + 50)
        }
    }

    private fun header() {
        if (ended) return
        Native.lookUpHeader(LookUp.lines(this, settings))
        dash()
    }

    // The graphical dashboard: drawn again (the arms get the rows that changed) while the terminal runs.
    private var dashVoice: String? = null // what voice says on it, or null
    private fun dash() {
        if (ended || settings.dashRows == 0) return
        val size = Native.dashSize()
        if (size[0] <= 0 || size[1] <= 0) return
        val hovered = barHits.getOrNull(pointerBar)?.action
        val hover = if (Session.pointer) hovered else null
        val f = Dashboard.render(this, settings, size[0], size[1], dashVoice, Session.listening, hover)
        barHits = f.hits
        barWidth = size[0]
        // The same place after a new picture (notifications come and go); one closed: the place now there.
        if (pointerBar >= 0) {
            val again = barHits.indexOfFirst { it.action == hovered }
            pointerBar = if (again >= 0) again else minOf(pointerBar, barHits.size - 1)
            if (again < 0 && pointerBar >= 0 && hover != null) return dash()
        }
        Native.dash(f.packed)
    }

    // ------------------------------------------------ voice

    // Parakeet, loaded on first use; the listener while the microphone is on.
    @Volatile private var voiceEngine: VoiceEngine? = null
    private var listener: VoiceListener? = null
    private var voiceLoading = false
    private var voiceFailedAt = 0L // always listening waits a while after a failure
    private val bandOff = Runnable {
        Native.voiceBand("", 1)
        if (!Session.listening) Session.voice = ""
        dashVoice = null
        dash()
        Session.changed()
    }

    // The band on the glasses (and the Voice tab): hold keeps it, else it goes after Settings.voiceBandSecs.
    private fun voiceSay(text: String, hold: Boolean = false) {
        Session.voice = text
        if (settings.dashRows > 0) { // on the dashboard's card instead of over the terminal
            dashVoice = if (settings.voiceBand) text else null
            dash()
        } else Native.voiceBand(if (settings.voiceBand) text else "", 2)
        work.removeCallbacks(bandOff)
        if (!hold) work.postDelayed(bandOff, settings.voiceBandSecs.coerceIn(1, 30) * 1000L)
        Session.changed()
    }

    // Listening on the glasses' microphone: once (push to talk: a TouchBar gesture, Alt+Space, the app; again
    // stops it) or always (Settings.voiceAlways: phrases that start with "Vox").
    private fun listen(once: Boolean) {
        if (listener != null || voiceLoading) {
            if (once) stopListening()
            return
        }
        if (!VoiceModel.available) return voiceSay("$MIC voice is not in this build")
        if (!VoiceModel.ready(this)) return voiceSay("$MIC download the speech model first: Vox, Voice tab")
        if (Session.glasses != "ready") return voiceSay("$MIC the glasses are not connected")
        voiceLoading = true
        voiceSay(if (voiceEngine == null) "$MIC loading the speech model…" else listeningText(once), hold = true)
        Thread {
            val engine = voiceEngine ?: runCatching { VoiceEngine(this) }.getOrElse { e ->
                work.post {
                    voiceLoading = false
                    voiceFailedAt = SystemClock.elapsedRealtime()
                    voiceSay("$MIC the speech model did not load: ${e.message}")
                }
                return@Thread
            }
            voiceEngine = engine
            work.post {
                voiceLoading = false
                if (ended || listener != null) return@post
                lateinit var l: VoiceListener
                l = VoiceListener(engine, settings.voiceRealtime, once,
                    onPartial = { t -> work.post { if (listener === l) voiceSay("$MIC $t…", hold = true) } },
                    onPhrase = { t -> work.post { if (listener === l) heard(t, once) } },
                    onEnd = { why -> work.post { listenerEnded(l, why) } })
                listener = l
                Session.listening = true
                voiceSay(listeningText(once), hold = true)
                l.start()
            }
        }.start()
    }

    private fun listeningText(once: Boolean) = if (once) "$MIC listening… say a command" else "$MIC say \"Vox\" and a command"

    private fun stopListening() {
        listener?.stop()
    }

    private fun listenerEnded(l: VoiceListener, why: String) {
        if (listener !== l) return
        listener = null
        Session.listening = false
        if (why.isNotEmpty()) {
            if (!l.once) voiceFailedAt = SystemClock.elapsedRealtime()
            voiceSay("$MIC $why")
        } else if (Session.voice.endsWith("…")) voiceSay(Session.voice.removeSuffix("…"))
        else work.postDelayed(bandOff, settings.voiceBandSecs.coerceIn(1, 30) * 1000L)
        Session.changed()
        if (!l.once) work.postDelayed({ voiceAlways() }, 1000)
    }

    // Always listening: on while the glasses are up and the setting is on (not again within 30 s of a failure).
    private fun voiceAlways() {
        if (!settings.voiceAlways || listener != null || voiceLoading || ended || Session.glasses != "ready") return
        if (!VoiceModel.available || !VoiceModel.ready(this)) return
        if (SystemClock.elapsedRealtime() - voiceFailedAt < 30000) return
        listen(false)
    }

    // A phrase: a command (always listening: only after "Vox"), or, while dictating, text for the terminal.
    private fun heard(text: String, once: Boolean) {
        Session.heard = (listOf(text) + Session.heard).take(8)
        if (Session.replyKey != null && Session.replyStatus.isEmpty()) { // said into a quick reply
            val sep = if (Session.replyDraft.isEmpty() || Session.replyDraft.endsWith(" ")) "" else " "
            return reply("c:$sep${text.trim()}")
        }
        var said = text
        if (!once && !Session.dictating) said = VoiceCommands.afterWake(text) ?: run {
            Session.changed()
            return
        }
        val m = VoiceCommands.match(settings, said)
        if (Session.dictating && (m == null || m.action == VoiceCommands.TYPE)) {
            type((m?.arg ?: said) + " ")
            return voiceSay("$MIC typing: $said", hold = true)
        }
        if (m == null) return voiceSay("$MIC \u201c$said\u201d: no such command")
        voiceSay("$MIC \u201c$said\u201d \u2192 ${m.label}")
        voiceDo(m)
    }

    private fun voiceDo(m: VoiceCommands.Match) {
        when (m.action) {
            VoiceCommands.SOURCE -> if (m.arg == Settings.PHONE && screen == null)
                voiceSay("$MIC share the phone's screen in the app first") else source(m.arg)
            VoiceCommands.NEXT -> source(Settings.ACTION_NEXT)
            VoiceCommands.PREV -> source(Settings.ACTION_PREV)
            VoiceCommands.UP, VoiceCommands.DOWN -> {
                val now = if (settings.show == Settings.TERMINAL) "term:${settings.active()?.id}" else Session.screenSource.ifEmpty { Settings.PHONE }
                settings.move(now, m.action)?.let { key ->
                    if (key == Settings.PHONE && screen == null) voiceSay("$MIC share the phone's screen in the app first")
                    else source(key)
                }
            }
            VoiceCommands.DISPLAY_ON -> display(true, "voice")
            VoiceCommands.DISPLAY_OFF -> display(false, "voice")
            VoiceCommands.BRIGHTER, VoiceCommands.DIMMER -> {
                settings.brightness = (settings.brightness + if (m.action == VoiceCommands.BRIGHTER) 6 else -6).coerceIn(0, 42)
                Native.brightness(settings.brightness)
            }
            VoiceCommands.VISIBLE_ON, VoiceCommands.VISIBLE_OFF -> {
                settings.phoneForce = m.action == VoiceCommands.VISIBLE_ON
                refresh()
            }
            VoiceCommands.TYPE -> type(m.arg)
            VoiceCommands.RUN -> {
                type(m.arg)
                key("enter")
            }
            VoiceCommands.KEY -> key(m.arg)
            VoiceCommands.DICTATE_ON -> {
                Session.dictating = true
                voiceSay("$MIC dictating: say \"send\" for Enter, \"stop dictating\" to end", hold = true)
                if (listener == null) listen(false)
            }
            VoiceCommands.DICTATE_OFF -> Session.dictating = false
            VoiceCommands.STOP -> {
                Session.dictating = false
                stopListening()
            }
            VoiceCommands.TAB -> tabs(m.arg)
            VoiceCommands.POINTER -> pointer(m.arg)
        }
        Session.changed()
    }

    // Text into the terminal, as keyer would type it.
    private fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            Native.keyEvent(1, 0, cp)
            i += Character.charCount(cp)
        }
    }

    private fun key(name: String) = when (name) {
        "enter" -> Native.keyEvent(2, 0, 0x28)
        "escape" -> Native.keyEvent(2, 0, 0x29)
        "backspace" -> Native.keyEvent(2, 0, 0x2A)
        "tab" -> Native.keyEvent(2, 0, 0x2B)
        "right" -> Native.keyEvent(2, 0, 0x4F)
        "left" -> Native.keyEvent(2, 0, 0x50)
        "down" -> Native.keyEvent(2, 0, 0x51)
        "up" -> Native.keyEvent(2, 0, 0x52)
        "ctrl-c" -> Native.keyEvent(1, 0x01, 'c'.code)
        else -> {}
    }

    // What the glasses show: the terminal, or the phone's screen (only while it is shared).
    // What the glasses draw: a screen (the phone's, or a server's once its images come) or the terminal's path
    // (the chosen terminal, or the card of a source that cannot show yet). The choice itself is Settings.source.
    private fun render(want: String) {
        val show = if (want == Settings.SCREEN && screen == null && remote == null) Settings.TERMINAL else want
        if (show == Settings.SCREEN && Session.screenSource.isEmpty()) Session.screenSource = remote?.key ?: Settings.PHONE
        settings.show = show
        Session.source = settings.sourceName(chosen())
        if (!onPhone()) hideCursor()
        if (show == Settings.SCREEN) {
            val error = Native.screenStart()
            if (error != null) {
                // The glasses keep the terminal's path: say so, and keep the sharing for another try.
                Log.w(TAG, "phone screen: $error")
                settings.show = Settings.TERMINAL
                update(screen = "error: $error")
                return refresh()
            }
        } else Native.screenStop()
        if (blankByLock) blankByLock = false
        lookSwitch()
        update()
        refresh()
        if (show == Settings.TERMINAL) header()
    }

    // ------------------------------------------------ the look per app

    // The look of what the screen shows now: the phone's app in front, a server's window, else the source; null while
    // the terminal shows.
    private fun lookKey(): String? {
        if (settings.show != Settings.SCREEN) return null
        val src = Session.screenSource.ifEmpty { Settings.PHONE }
        if (src == Settings.PHONE) return Looks.app.takeIf { it.isNotEmpty() }?.let { "app:$it" } ?: "src:phone"
        val id = src.removePrefix("screen:")
        return Looks.windows[id]?.takeIf { it.isNotEmpty() }?.let { "win:$id:$it" } ?: "src:$src"
    }

    // What shows changed (an app, a window, the source): its look comes back.
    private fun lookSwitch() {
        if (!settings.looksPerApp) return
        val key = lookKey() ?: return
        if (key == Looks.key) return
        if (Looks.use(settings, key)) Log.i(TAG, "look: $key")
        Session.changed()
    }

    // A look setting changed (the Screen tab): the look in use keeps it.
    private val lookPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k in Looks.PREFS) work.post { if (settings.looksPerApp) Looks.remember(settings) }
    }

    // A server's screen on the glasses: its window in front (X11's active window's class), asked every few seconds
    // over the server's kept SSH connection; a server where that fails is asked again after a minute.
    private var windowAsking = false
    private val windowRetry = HashMap<String, Long>()
    private val windowPoll = object : Runnable {
        override fun run() {
            work.postDelayed(this, 2500)
            val src = Session.screenSource
            if (!settings.looksPerApp || settings.show != Settings.SCREEN || !src.startsWith("screen:") || windowAsking) return
            val s = settings.servers().firstOrNull { "screen:${it.id}" == src } ?: return
            if (SystemClock.elapsedRealtime() < (windowRetry[s.id] ?: 0L)) return
            windowAsking = true
            Thread {
                val out = runCatching { Bootstrap(vault).runShared(s.host, s.port, s.user, WINDOW_CMD) }.getOrNull()
                val cls = out?.lines()?.lastOrNull { it.contains("WM_CLASS") }?.let { Regex("\"([^\"]*)\"\\s*$").find(it)?.groupValues?.get(1) }
                work.post {
                    windowAsking = false
                    if (cls.isNullOrEmpty()) {
                        windowRetry[s.id] = SystemClock.elapsedRealtime() + 60000
                        return@post
                    }
                    if (Looks.windows[s.id] != cls) {
                        Looks.windows = Looks.windows + (s.id to cls)
                        lookSwitch()
                    }
                }
            }.start()
        }
    }

    // The display on or off; why: for the status line.
    private fun display(on: Boolean, why: String) {
        if (!on == Session.blank) return
        // A CRT switching off (the picture closes to a line, then a dot) or on (it opens from the middle).
        if (settings.animations && glassesWith != null)
            Native.transition(if (on) Fx.ON else Fx.OFF, null, 0, 0, settings.show != Settings.SCREEN, false)
        Session.blank = !on
        if (on) blankByLock = false
        Native.blank(!on)
        Log.i(TAG, "display ${if (on) "on" else "off"} ($why)")
        update()
    }

    // A TouchBar gesture: what Settings says it does.
    private fun gesture(arm: Int, gesture: Int) {
        // The left TouchBar: a hold's own action when it is let go; held 3 s, the glasses go look-up only
        // (Always visible off), which only the app turns back (no accidental switches).
        if (arm == 0 && gesture == Native.GESTURE_HOLD) {
            work.removeCallbacks(longHold)
            work.postDelayed(longHold, LONG_HOLD_MS - 458)
            return
        }
        if (arm == 0 && gesture == Native.GESTURE_HOLD_END) {
            if (!work.hasCallbacks(longHold)) return // the long hold happened
            work.removeCallbacks(longHold)
            return gestureAction(arm, Native.GESTURE_HOLD)
        }
        if (gesture == Native.GESTURE_HOLD_END) return
        gestureAction(arm, gesture)
    }

    private val longHold = Runnable {
        Session.gesture = "left held 3 s: look-up only"
        Log.i(TAG, "left held 3 s: Always visible off")
        if (settings.phoneForce) {
            settings.phoneForce = false
            refresh()
        }
        voiceSay("Look-up only. Turn Always visible back on in Vox.")
        Session.changed()
    }

    private fun gestureAction(arm: Int, gesture: Int) {
        val action = settings.gestureAction(arm, gesture)
        val name = (if (arm == 0) "left " else "right ") + (Settings.GESTURES.firstOrNull { it.first == gesture }?.second ?: "gesture $gesture")
        Session.gesture = "$name: ${Settings.ACTIONS[action] ?: action}"
        when (action) {
            Settings.ACTION_DISPLAY -> {
                blankByLock = false
                display(Session.blank, name)
            }
            // The terminal and the screen last chosen, one then the other.
            Settings.ACTION_VIEW -> if (chosen().startsWith("term:")) {
                lastScreen.ifEmpty { if (screen != null) Settings.PHONE else "" }.takeIf { it.isNotEmpty() }?.let { choose(it, Fx.DOWN) }
            } else settings.active()?.let { choose("term:${it.id}", Fx.UP) }
            Settings.ACTION_NEXT, Settings.ACTION_PREV -> source(action)
            Settings.ACTION_LISTEN -> listen(true)
            Settings.ACTION_TAB_NEXT -> tabs("next")
            Settings.ACTION_TAB_PREV -> tabs("prev")
            Settings.ACTION_TAB_NEW -> tabs("new")
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
        foreground()
        update(screen = why)
        render(Settings.TERMINAL) // the phone's screen still chosen: its card says it is not shared
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
        // The X11 window in front on a server (its WM_CLASS line), for the look per window.
        const val WINDOW_CMD = "export DISPLAY=\"\${DISPLAY:-:0}\"; U=\$(id -u); if [ -z \"\$XAUTHORITY\" ] && " +
            "[ -f \"/run/user/\$U/gdm/Xauthority\" ]; then export XAUTHORITY=\"/run/user/\$U/gdm/Xauthority\"; fi; " +
            "xprop -id \"\$(xprop -root _NET_ACTIVE_WINDOW | sed 's/.*# //')\" WM_CLASS"
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
        const val ACTION_CLOSE = "scouterkit.ssh.CLOSE" // the source shown closes (Alt+Q)
        const val ACTION_REPLY = "scouterkit.ssh.REPLY" // a quick reply's key (extra "key": c:TEXT, del, send, cancel)
        const val ACTION_VOICE = "scouterkit.ssh.VOICE" // voice (extra "mode": listen, stop, always)
        const val ACTION_LOOK_UP = "scouterkit.ssh.LOOK_UP" // the look-up view's settings changed
        const val ACTION_POINTER = "scouterkit.ssh.POINTER" // the pointer (extra "cmd")
        // Retries after a failure (the glasses' start, the terminal's session): soon at first, then less often.
        val RETRY_MS = longArrayOf(3000, 5000, 8000, 12000, 20000, 30000)
        const val LONG_HOLD_MS = 3000L // the left TouchBar held this long: look-up only
        const val ACTION_TABS = "scouterkit.ssh.TABS" // a tab action (extra "tab", Tabs.command)
        const val MIC = "\uF130" // the terminal font's microphone (Nerd Fonts)
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
        // The native mosh sessions outlive a service (paused between keyer's sessions, in this process): one per
        // terminal (a saved server), side by side, by its id. Used on the work thread.
        class Term(val id: String) {
            @Volatile var status = "off" // off, starting, connecting, connected, no contact for N s, ended, error: …
            var key: String? = null      // its server's fields when it was started (serverKey)
            var started = false          // a native session exists
            var tries = 0                // failed starts in a row
            var retryAt = 0L             // after an error: no new start before (elapsedRealtime)
            var thread: Thread? = null   // its SSH start, under way
            var unknownHost: String? = null // a host key waiting for the user
        }
        private val terms = HashMap<String, Term>()
        private val tabCache = HashMap<String, List<Tabs.Tab>>() // each terminal's tabs, as last asked
        private var moshShown = ""     // the session shown on the glasses (Native.moshShow)
        private var moshPaused = false // every session quiet (Native.moshPause)
        private var cardShown: List<String> = emptyList() // the card on the glasses (Native.card)
        private var cardSize = 0 to 0
        @Volatile private var moshSize = 0 to 0

        // Never without Bluetooth permission (Settings asks for it), nor with Bluetooth off (a running service
        // ends by itself then).
        fun send(context: Context, action: String? = null, vararg extras: Pair<String, String>) {
            if (!bluetooth(context)) {
                Log.w(TAG, "no Bluetooth permission: the service waits for Settings")
                return
            }
            if (!bluetoothOn(context)) {
                Log.i(TAG, "Bluetooth is off: no service")
                return
            }
            val intent = Intent(context, ScouterService::class.java).setAction(action)
            for ((k, v) in extras) intent.putExtra(k, v)
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

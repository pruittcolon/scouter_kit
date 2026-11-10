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
    @Volatile var voiceMode = false // voice mode (Alt+V): talking to the terminal, a strip at the bottom says so
    @Volatile var tvTyping = false // in TV mode (Alt+Y), T: the keyboard types into the TV's text field
    @Volatile var hub = false // the hub is open (Alt+G)
    @Volatile var hubTyping = false // the hub takes typing (a timer's length, an alarm, a reminder, a note)
    @Volatile var captions = "" // live captions on: "captions" (Alt+E) or "translate" (Alt+I); "" off
    @Volatile var home = false // the smart home panel is open (Alt+O)
    @Volatile var people = false // the calls and texts panel is open (Alt+J)
    @Volatile var peopleTyping = false // it takes typing (a text)
    @Volatile var editor = false // the text editor is open (Alt+N): it takes the keys
    @Volatile var listenPanel = false // Listen mode's panel is open (hub L, or Alt+E while Listen is on)
    @Volatile var listenTyping = false // it takes typing (a name for a voice)
    @Volatile var car = "" // Car mode: "" off, else its state (idle, listening, thinking, speaking)
    @Volatile var carName = "" // the car connected (its Bluetooth name), or ""
    @Volatile var pointer = false // the pointer is on (its keys move and click it)
    @Volatile var demo = false // the preview mode is running (Alt+D, the Preview button)
    @Volatile var tvKeys = false // the keyboard is the Vizio TV's remote (Alt+Y)
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
    @Volatile var keysToPhone = false // Alt+K: the keyboard types into the phone's apps even while the terminal shows
    @Volatile var keysOff = false // Alt+X: the keyboard's keys do nothing anywhere (Alt+X aside) until Alt+X again
    @Volatile var pointerKeys = false // Alt+M put the pointer on: the arrows, Enter and Space are its
    @Volatile var tunes = false // the synthwave scene is on the glasses
    @Volatile var overlay = "" // a panel over what shows (Panels.HELP, SETTINGS, NOTE), or ""
    @Volatile var overlayGen = 0 // each change of a screen's panel (the frames carry it)
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
            keepServer()
            work.postDelayed(this, 60000)
        }
    }

    // The phone's own SSH server (the terminal's, Car mode's Claude): started again when it stopped (its proot
    // session ended), at most every three minutes. A port check on the phone, nothing on the glasses.
    private var serverStartAt = 0L
    private fun keepServer() {
        if (settings.servers().none { it.local } || !Termux.permitted(this)) return
        Thread {
            if (Termux.listening()) return@Thread
            val now = SystemClock.elapsedRealtime()
            if (now - serverStartAt < 180_000) return@Thread
            serverStartAt = now
            Log.i(TAG, "server: the phone's SSH server is down: starting it again")
            Termux.run(this, Termux.START)?.let { Log.w(TAG, "server: $it") }
        }.start()
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

    // Heads-up on a screen (the phone's or a server's): the head read five times a second between frames (a still
    // picture sends none), on its own thread (two Bluetooth reads); the panels on again: a frame at once.
    private val looker = HandlerThread("heads-up").apply { start() }
    private val look = Handler(looker.looper)
    private val lookTick = object : Runnable {
        override fun run() {
            if (ended) return
            val on = !settings.phoneForce && settings.show == Settings.SCREEN && Session.glasses == "ready" && !Session.blank
            if (on && runCatching { Native.screenLook(settings.screenOptions()) }.getOrDefault(0) == 1) {
                screen?.poke()
                remote?.redraw()
            }
            look.postDelayed(this, if (on) 200L else 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        look.postDelayed(lookTick, 1000)
        settings = Settings(this)
        vault = Vault(this)
        // A crash: its log kept, and sent to the phone's server the next time (LogBook).
        if (!crashWatch) {
            crashWatch = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { Log.e(TAG, "crash in ${t.name}", e) }
                LogBook.crash(applicationContext, e)
                previous?.uncaughtException(t, e)
            }
        }
        work.postDelayed({ LogBook.sendPending(this, vault) }, 20000)
        work.postDelayed({ ListenService.ensure(this) }, 3000) // Listen mode, if it is on (Android may want Vox opened)
        Meetings.onDone = { said -> work.post { note(said, 4000) } }
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
        Media.onChange = { work.post { dash(); car?.show() } }
        Media.onStarted = { work.post { car?.reclaim() } }
        Media.start(this)
        registerReceiver(carLink, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        carScan()
        work.postDelayed(mediaTick, 1000)
        work.postDelayed(tunesTick, 500)
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
            // Keeps mediaProjection while the screen is shared (and the microphone once allowed: if Android won't
            // now, in the back, without it).
            try {
                startForeground(NOTIFICATION, notification(), types())
            } catch (e: SecurityException) {
                if (!micService) throw e
                micService = false
                startForeground(NOTIFICATION, notification(), types())
            }
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
            // Tunes on or off (extra "on": true / false, absent: the other way).
            ACTION_TUNES -> work.post { tunesSet(intent.getStringExtra("on")?.toBoolean() ?: !Session.tunes) }
            // Alt+A, S, L, = / -, P, , / . and the keys while a panel is open (extra "do").
            ACTION_PANEL -> work.post { hotkey(intent.getStringExtra("do").orEmpty()) }
            // Alt+K: the keyboard's keys to the phone's apps, or back to the glasses' terminal.
            ACTION_KEYS -> work.post {
                Session.keysToPhone = !Session.keysToPhone
                voiceSay(if (Session.keysToPhone) "\u2328 Keyboard: phone (Alt+K for the glasses)" else "\u2328 Keyboard: glasses")
            }
            // The look-up view's settings changed: the header and the fade again.
            ACTION_LOOK_UP -> work.post { lookUpChanged() }
            // Voice: "listen" (one command; again: stop), "stop", or "always" (Settings.voiceAlways changed).
            ACTION_VOICE -> work.post {
                when (intent.getStringExtra("mode")) {
                    "car_check" -> carCheck()
                    "mic_fg" -> micForeground()
                    "car_test" -> carTest()
                    "car_new" -> {
                        settings.carSession = ""
                        car?.newConversation()
                    }
                    "car_talk" -> car?.talk()
                    "car_voice" -> Thread { // Kokoro downloaded (the Voice tab): loaded ahead while Car mode is on
                        KokoroModel.unload()
                        if (car != null) KokoroModel.load(this)
                    }.start()
                    "stop" -> stopListening()
                    "voicemode" -> voiceModeSet(!Session.voiceMode)
                    "voicemode:on" -> voiceModeSet(true)
                    "voicemode:off" -> voiceModeSet(false)
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
        look.removeCallbacks(lookTick)
        looker.quitSafely()
        LookUp.onChange = null
        work.removeCallbacks(headerTick)
        work.removeCallbacks(tabPoll)
        work.removeCallbacks(editorTick)
        runCatching { editor?.save() }
        Listen.unwatch(listenWatch)
        Meetings.onDone = null
        Thread { Bootstrap.dropShared() }.start()
        runCatching { dim?.release() } // pocket mode's screen lock
        dim = null
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
        runCatching { unregisterReceiver(carLink) }
        Media.onStarted = null
        car?.release()
        car = null
        carListener?.stop()
        Session.car = ""
        Media.onChange = null
        Media.stop()
        work.removeCallbacks(mediaTick)
        work.removeCallbacks(tunesTick)
        if (Session.tunes || tunesBar) Tunes.stop()
        Session.tunes = false
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
            update(keyer = KEYBOARD)
            forceApply()
        }
        // Started from the phone: the same, unless the user wants the looking-up rule.
        val force = settings.phone && settings.phoneForce
        if (force != forced) {
            forced = force
            forceApply()
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
        if (voiceEngine != null) Thread { runCatching { Parakeet.done("glasses") } }.start()
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
        if (car != null) display(false, "car mode") // driving: nothing on the glasses
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
                failed(t, if (s.local && refused) "this phone's SSH server is off (type ssh in Termux)" else Bootstrap.plain(e, s.host, s.port))
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
        update(keyer = "off")
        forceApply()
    }

    // While the app holds keyer's keys the glasses show the terminal whatever the head's angle (R25, the
    // user's rule); otherwise the arms' looking-up rule decides. keyer coming or going decides what runs.
    private fun keyerState(state: String) {
        update(keyer = state)
        forceApply()
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

    // A trackpad or mouse paired with the phone: taken while the glasses show the terminal or the phone's screen
    // (their own arrow, drawn by the arms: Android leaves its pointer out of the shared screen); otherwise the phone's.
    private fun trackpadMode() {
        VoxKeys.capture(!ended && settings.trackpad && glassesWith != null && Session.glasses == "ready" && !Session.blank &&
            (settings.show == Settings.TERMINAL || onPhone() || onLaptop()) && !Session.tunes)
        pocket()
    }

    // Pocket mode: while the phone's screen is on the glasses the phone stays awake (dimmed), so it does not lock in
    // a pocket and the keyboard and trackpad keep working it; it wakes for them if it went dark meanwhile.
    private var dim: PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    private fun pocket() {
        val want = !ended && settings.pocket && onPhone() && Session.glasses == "ready" && !Session.blank
        if (want && dim == null) dim = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "scouter:pocket").apply {
                setReferenceCounted(false)
                acquire()
            }
        if (!want && dim != null) {
            runCatching { dim?.release() }
            dim = null
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeForInput() {
        if (!settings.pocket || !onPhone()) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        runCatching {
            pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "scouter:wake").acquire(3000)
        }
    }

    private var swipe = 0f

    // The trackpad: the arrow glides like a mouse pointer (the setting's speed, quicker strokes go further); a tap
    // clicks, a right click (two fingers) right-clicks, the wheel and two-finger swipes scroll. A click while the
    // pointer is off only shows it.
    private fun trackpad(kind: Int, dx: Float, dy: Float) {
        if (Session.overlay == Panels.HELP || Session.overlay == Panels.SETTINGS) return panelTrackpad(kind, dy)
        if (Session.overlay == Panels.EDITOR) return editorTrackpad(kind, dy)
        if (Session.overlay == Panels.LISTEN) return listenTrackpad(kind, dy)
        if (onLaptop()) return laptopTrackpad(kind, dx, dy)
        if (onPhone()) return phoneTrackpad(kind, dx, dy)
        if (pressed || sliding) { // the phone's screen went from the glasses with a finger still on it
            pressed = false
            slideLift()
            Finger.release()
        }
        if (settings.show != Settings.TERMINAL) return
        when (kind) {
            VoxKeys.Trackpad.MOVE, VoxKeys.Trackpad.DRAG -> {
                val k = glide(dx, dy)
                if (!Session.pointer) pointerOn()
                moveBy(dx * k, dy * k)
                Session.changed()
            }
            VoxKeys.Trackpad.CLICK, VoxKeys.Trackpad.RIGHT -> {
                if (!Session.pointer) return pointer("toggle")
                pointer(if (kind == VoxKeys.Trackpad.RIGHT) "right" else "click")
            }
            VoxKeys.Trackpad.WHEEL -> if (dy != 0f) scroll(if (dy > 0) -1 else 1, kotlin.math.abs(dy).toInt().coerceIn(1, 5))
            VoxKeys.Trackpad.SCROLL -> {
                swipe += dy
                val step = 28f
                var steps = 0
                while (kotlin.math.abs(swipe) >= step) {
                    steps += if (swipe > 0) 1 else -1
                    swipe -= if (swipe > 0) step else -step
                }
                // Natural: the fingers going down bring the text down (back in its history).
                if (steps != 0) scroll(if (steps > 0) -1 else 1, kotlin.math.abs(steps))
            }
        }
    }

    // The trackpad's gain: glasses pixels per trackpad pixel (the speed setting; quicker strokes go further).
    private fun glide(dx: Float, dy: Float): Float {
        val v = kotlin.math.hypot(dx, dy)
        val accel = settings.trackpadAccel.coerceIn(0, 10) / 5f
        return settings.trackpadSpeed.coerceIn(1, 10) / 5f * 0.9f * (1f + accel * minOf(1.5f, v / 30f))
    }

    // The trackpad on the phone's screen, as a finger there (Finger): the arms' arrow glides over the picture as over
    // the terminal (the same speed, in glasses pixels); the button puts a finger down where it points and lifts it
    // when let go (a click taps, held still it is a long press, moved it drags: text, sliders, a list pulled
    // down); a right click is a long press; two-finger swipes (the trackpad's wheels, both ways) slide the page under
    // the arrow as a finger would, a quick one flinging it on. The first click only shows the arrow.
    private var pressed = false // the button down: a finger held on the phone (Finger)
    private var sliding = false // a two-finger swipe under way: a finger held for it
    private val slideMoves = ArrayDeque<FloatArray>() // its last moves: time (ms), dx, dy
    private val slideEnd = Runnable { slideLift() }

    private fun phoneTrackpad(kind: Int, dx: Float, dy: Float) {
        wakeForInput()
        val natural = if (settings.scrollNatural) 1f else -1f
        val speed = settings.scrollSpeed.coerceIn(1, 10) / 3f
        when (kind) {
            VoxKeys.Trackpad.MOVE, VoxKeys.Trackpad.DRAG -> {
                if (kind == VoxKeys.Trackpad.MOVE && pressed) { // its release went missing
                    pressed = false
                    Finger.release()
                }
                if (!Session.pointer) {
                    Session.pointer = true
                    Session.pointerKeys = false
                }
                val k = glide(dx, dy)
                val x0 = cursorX
                val y0 = cursorY
                cursorBy(dx * k, dy * k)
                if (pressed) {
                    val b = screenBounds()
                    Finger.move((cursorX - x0) / 10000f * (b.width() - 1), (cursorY - y0) / 10000f * (b.height() - 1))
                }
            }
            VoxKeys.Trackpad.CLICK -> {
                if (!Session.pointer) return pointer("toggle")
                if (sliding) slideLift()
                val b = screenBounds()
                pressed = Finger.press(cursorX / 10000f * (b.width() - 1), cursorY / 10000f * (b.height() - 1),
                    b.width(), b.height(), slide = false)
                Session.tabs = if (pressed) "" else "clicks on the phone need Vox's keyboard shortcuts (Android Settings: Accessibility, Vox)"
                showCursor()
            }
            VoxKeys.Trackpad.RIGHT -> {
                if (!Session.pointer) return pointer("toggle")
                pointer("right")
                return
            }
            VoxKeys.Trackpad.RELEASE -> if (pressed && dx == 0f) {
                pressed = false
                Finger.release()
            }
            // A wheel's notch: as far as the app's own wheel scrolling goes. Turned up (natural), the page comes
            // down: a finger moving down; turned right, a finger moving left.
            VoxKeys.Trackpad.WHEEL -> {
                val c = android.view.ViewConfiguration.get(this)
                slide(-dx * c.scaledHorizontalScrollFactor * speed * natural, dy * c.scaledVerticalScrollFactor * speed * natural)
            }
            // A touchpad's own two-finger swipe (pixels; positive: the fingers went down): the finger goes with them.
            VoxKeys.Trackpad.SCROLL -> slide(dx * 2f * speed * natural, dy * 2f * speed * natural)
        }
        Session.changed()
    }

    // A two-finger swipe's move: a finger put down at the arrow (the first), moved; lifted when no more come.
    private fun slide(fx: Float, fy: Float) {
        if (pressed) return
        val now = SystemClock.uptimeMillis().toFloat()
        if (!sliding) {
            val b = screenBounds()
            if (!Finger.press(cursorX / 10000f * (b.width() - 1), cursorY / 10000f * (b.height() - 1), b.width(), b.height(), slide = true)) {
                Session.tabs = "scrolling the phone needs Vox's keyboard shortcuts (Android Settings: Accessibility, Vox)"
                return
            }
            sliding = true
            slideMoves.clear()
        }
        Finger.move(fx, fy)
        slideMoves.addLast(floatArrayOf(now, fx, fy))
        while (slideMoves.size > 1 && now - slideMoves.first()[0] > SLIDE_SPEED_MS) slideMoves.removeFirst()
        work.removeCallbacks(slideEnd)
        work.postDelayed(slideEnd, SLIDE_IDLE_MS)
    }

    // The swipe ended (nothing for SLIDE_IDLE_MS): the finger lifts at the speed of its last moves, so a quick
    // swipe flings the page on and a slow one stops it where it is.
    private fun slideLift() {
        work.removeCallbacks(slideEnd)
        if (!sliding) return
        sliding = false
        val last = slideMoves.lastOrNull()?.get(0) ?: 0f
        var sx = 0f
        var sy = 0f
        var first = last
        for (m in slideMoves) if (last - m[0] <= SLIDE_SPEED_MS) {
            sx += m[1]
            sy += m[2]
            first = minOf(first, m[0])
        }
        slideMoves.clear()
        val span = (last - first) + 12f // the first move's own time before it came
        val vx = sx / span
        val vy = sy / span
        if (kotlin.math.hypot(vx, vy) < 0.4f) Finger.release() else Finger.release(vx, vy)
    }

    // ------------------------------------------------ the laptop's screen: its keyboard and mouse

    // A server's screen on the glasses (the laptop's): the keyboard and the trackpad work its desktop (RemoteInput).
    private fun onLaptop() = settings.show == Settings.SCREEN && Session.screenSource.startsWith("screen:") &&
        remote?.key == Session.screenSource

    @Volatile private var laptopIn: RemoteInput? = null
    private var laptopButton = 0 // the laptop's button held (1 left, 3 right), 0 none
    private var wheelV = 0f // wheel notches not sent yet (fractions of the trackpad's fine steps)
    private var wheelH = 0f

    // The input link to the server whose screen shows, started with it; null with none.
    private fun laptopInput(): RemoteInput? {
        val r = remote ?: return null
        laptopIn?.let { if (it.server.id == r.server.id && !it.ended) return it }
        laptopIn?.close()
        val i = RemoteInput(this, r.server) { x, y ->
            work.post { if (onLaptop() && laptopButton == 0) { cursorX = x.toFloat(); cursorY = y.toFloat() } }
        }
        laptopIn = i
        RemoteInput.current = i
        i.start()
        return i
    }

    private fun laptopInputStop() {
        laptopIn?.close()
        laptopIn = null
        RemoteInput.current = null
        laptopButton = 0
    }

    // Glasses pixels to the picture as the phone's cursor (cursorBy), sent as the laptop's pointer; the arms draw
    // the arrow at once and leave it after a moment (the laptop's own pointer is in its picture by then).
    private val laptopArrowOff = Runnable { if (onLaptop()) hideCursor() }

    private fun laptopTrackpad(kind: Int, dx: Float, dy: Float) {
        val input = laptopInput() ?: return
        if (input.ended) return
        val natural = if (settings.scrollNatural) 1f else -1f
        val speed = settings.scrollSpeed.coerceIn(1, 10) / 3f
        when (kind) {
            VoxKeys.Trackpad.MOVE, VoxKeys.Trackpad.DRAG -> {
                val k = glide(dx, dy)
                cursorBy(dx * k, dy * k)
                input.send("p ${cursorX.toInt()} ${cursorY.toInt()}")
                work.removeCallbacks(pointerIdle)
                work.removeCallbacks(laptopArrowOff)
                work.postDelayed(laptopArrowOff, 1500)
            }
            VoxKeys.Trackpad.CLICK, VoxKeys.Trackpad.RIGHT -> {
                val b = if (kind == VoxKeys.Trackpad.RIGHT) 3 else 1
                if (laptopButton != 0 && laptopButton != b) input.send("b $laptopButton 0")
                laptopButton = b
                input.send("p ${cursorX.toInt()} ${cursorY.toInt()}")
                input.send("b $b 1")
            }
            VoxKeys.Trackpad.RELEASE -> {
                val b = if (dx != 0f) 3 else 1
                input.send("b $b 0")
                if (laptopButton == b) laptopButton = 0
            }
            // Turned up (natural), the page comes down: X's button 4; right: 7.
            VoxKeys.Trackpad.WHEEL, VoxKeys.Trackpad.SCROLL -> {
                if (kind == VoxKeys.Trackpad.WHEEL) {
                    wheelV += dy * natural * speed
                    wheelH += dx * natural * speed
                } else { // a touchpad's swipe in pixels: the fingers going down bring the page down
                    wheelV += dy / 40f * natural * speed
                    wheelH -= dx / 40f * natural * speed
                }
                val v = wheelV.toInt()
                val h = wheelH.toInt()
                if (v != 0) input.send("w ${if (v > 0) 4 else 5} ${kotlin.math.abs(v)}")
                if (h != 0) input.send("w ${if (h > 0) 7 else 6} ${kotlin.math.abs(h)}")
                wheelV -= v
                wheelH -= h
            }
        }
    }

    // The cursor by (gx, gy) glasses pixels: through the picture's scale and turn to the phone's screen, kept on
    // the part that shows.
    private fun cursorBy(gx: Float, gy: Float) {
        val s = Native.screenShows()
        if (s == null) {
            cursorX = (cursorX + gx * 10000f / 540f).coerceIn(0f, 10000f)
            cursorY = (cursorY + gy * 10000f / 480f).coerceIn(0f, 10000f)
        } else {
            val w = (s[2] - s[0]).toFloat()
            val h = (s[3] - s[1]).toFloat()
            val ow = s[4].coerceAtLeast(1)
            val oh = s[5].coerceAtLeast(1)
            val (ddx, ddy) = when (((settings.screenRotate % 360) + 360) % 360) {
                90 -> gy * w / oh to -gx * h / ow
                180 -> -gx * w / ow to -gy * h / oh
                270 -> -gy * w / oh to gx * h / ow
                else -> gx * w / ow to gy * h / oh
            }
            cursorX = (cursorX + ddx).coerceIn(s[0].toFloat(), s[2].toFloat())
            cursorY = (cursorY + ddy).coerceIn(s[1].toFloat(), s[3].toFloat())
        }
        showCursor()
    }

    // Scrolling the phone's app: `px` screen pixels onward (positive: the page goes on down), as swipes there;
    // what comes while one runs adds up for the next (a new gesture would cut the running one short).
    private var swipePending = 0f
    private var swiping = false
    private var swipePendingX = 0f
    private fun phoneScroll(px: Float, pxX: Float = 0f) {
        swipePending += px
        swipePendingX += pxX
        if (!swiping) swipeNext()
    }

    private fun swipeNext() {
        val b = screenBounds()
        val d = swipePending.coerceIn(-b.height() * 0.4f, b.height() * 0.4f)
        val h = swipePendingX.coerceIn(-b.width() * 0.4f, b.width() * 0.4f)
        if (kotlin.math.abs(d) < 6f && kotlin.math.abs(h) < 6f) {
            swipePending = 0f
            swipePendingX = 0f
            swiping = false
            return
        }
        swipePending -= d
        swipePendingX -= h
        swiping = true
        val x = cursorX / 10000f * (b.width() - 1)
        val y = cursorY / 10000f * (b.height() - 1)
        val ok = VoxKeys.scroll(x, y, d, b.height().toFloat(), { work.post { swiping = false; swipeNext() } }, h, b.width().toFloat())
        if (!ok) {
            swiping = false
            swipePending = 0f
            swipePendingX = 0f
            Session.tabs = "scrolling the phone needs Vox's keyboard shortcuts (Android Settings: Accessibility, Vox)"
        }
    }

    private fun screenBounds() = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds

    private fun stateLine(): String {
        fun arm(up: Boolean) = if (up) "up" else "down"
        val glasses = if (Session.glasses == "ready") "L ${arm(Session.left)} R ${arm(Session.right)}" else "glasses ${Session.glasses}"
        val what = if (screen != null) "phone screen ${Session.screen}" else "mosh ${Session.mosh}"
        return "$glasses · $what · keyer ${Session.keyer}"
    }

    // The foreground service's types: the glasses link, and screen sharing while it runs (Android 14+ checks).
    // And the microphone once Vox was in front with it allowed (Android lets a service in the back hear only when it
    // became a microphone service while the app was open): Car mode's phone microphone.
    private fun types() = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        (if (screen != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or
        (if (micService) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    @Volatile private var micService = false

    private fun foreground() = main.post {
        if (ended) return@post
        runCatching { startForeground(NOTIFICATION, notification(), types()) }.onFailure {
            Log.w(TAG, "foreground: ${it.message}")
            if (micService) { // not allowed now: without the microphone
                micService = false
                runCatching { startForeground(NOTIFICATION, notification(), types()) }
            }
        }
    }

    // Vox in front (MainActivity): the service becomes a microphone service too, if the microphone is allowed.
    private fun micForeground() {
        if (micService || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micService = true
        foreground()
        Log.i(TAG, "car: the phone's microphone may be used in the back")
    }

    // Android's consent came (MainActivity): in the foreground as mediaProjection first (Android 14+ requires it
    // before the projection), then the capture, then the glasses switch from the terminal to it. Main thread.
    private fun startScreen(intent: Intent) {
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        try {
            val with = types() or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION // the microphone kept if it was
            runCatching { startForeground(NOTIFICATION, notification(), with) }.onFailure {
                if (!micService) throw it
                micService = false
                startForeground(NOTIFICATION, notification(), types() or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
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
            parkRemote()
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

    // ------------------------------------------------ panels and hotkeys

    // A hotkey (Hotkeys, ACTION_PANEL): "help" (Alt+A), "settings" (Alt+S), "key:K" (up, down, left, right, enter,
    // esc, space while a panel is open), "lookup" (Alt+0 / Alt+Z: heads-up mode), "bright:1" / "bright:-1" (Alt+= / -),
    // "media:toggle", "media:prev", "media:next" (Alt+P, Alt+, / .), "althold:on" / "althold:off" (Alt held by
    // itself: the help while it is held, unless a panel is open already), "keys:off" / "keys:on" (Alt+X), "share"
    // (Alt+C: the phone's screen, shared first if it is not).
    private fun hotkey(what: String) {
        when {
            what == "althold:on" -> if (Session.overlay.isEmpty() || Session.overlay == Panels.NOTE) {
                altHelp = true
                panelOpen(Panels.HELP)
            }
            what == "althold:off" -> if (altHelp) {
                altHelp = false
                if (Session.overlay == Panels.HELP) panelClose()
            }
            what == Panels.HELP || what == Panels.SETTINGS -> panelOpen(what)
            what == "keys:off" -> note("Keyboard off: Alt+X turns it on")
            what == "keys:on" -> note("Keyboard on")
            what == "share" -> phoneScreen()
            what.startsWith("key:") -> panelKey(what.removePrefix("key:"))
            what == "lookup" -> lookUpOnly()
            what == "lowbat" -> lowBattery()
            what == "demo" -> if (Session.demo) demoStop("Preview stopped") else demoStart()
            what == "tv" -> tvRemote()
            what == "tvtab" -> tvTab()
            what == "tvstop" -> tvTab(stop = true)
            what.startsWith("tv:") -> tvKey(what.removePrefix("tv:"))
            what == "hub" -> hubOpen()
            what == "editor" -> editorOpen()
            what == "listen" -> listenOpen()
            what.startsWith("lkey:") -> listenKey(what.removePrefix("lkey:"))
            what.startsWith("lchar:") -> listenChar(what.removePrefix("lchar:").toIntOrNull() ?: 0)
            what.startsWith("edkey:") -> editorKey(what.removePrefix("edkey:"))
            what == "captions" && Listen.on -> listenOpen()
            what == "captions" || what == "translate" -> captions(what)
            what == "capesc" -> if (Session.captions.isNotEmpty()) captionsStop("")
            what == "home" -> homeOpen()
            what.startsWith("hkey:") -> homeKey(what.removePrefix("hkey:"))
            what == "people" -> peopleOpen()
            what.startsWith("pkey:") -> peopleKey(what.removePrefix("pkey:"))
            what.startsWith("pchar:") -> peopleChar(what.removePrefix("pchar:").toIntOrNull() ?: 0)
            what == "pdel" -> peopleDel()
            what == "penter" -> peopleEnter()
            what == "pesc" -> peopleEsc()
            what.startsWith("hubkey:") -> hubKey(what.removePrefix("hubkey:"))
            what.startsWith("hubchar:") -> hubChar(what.removePrefix("hubchar:").toIntOrNull() ?: 0)
            what == "hubdel" -> hubDel()
            what == "hubenter" -> hubEnter()
            what == "hubesc" -> hubEsc()
            what == "tvtype:on" || what == "tvtype:off" -> tvTyping(what == "tvtype:on")
            what.startsWith("tvchar:") -> tvChar(what.removePrefix("tvchar:").toIntOrNull() ?: 0)
            what.startsWith("tvapp:") -> Vizio.APPS.getOrNull(what.removePrefix("tvapp:").toIntOrNull() ?: -1)?.let { tvApp(it) }
            what.startsWith("bright:") -> bright(what.removePrefix("bright:").toIntOrNull() ?: 0)
            what.startsWith("media:") -> {
                val ok = Media.control(what.removePrefix("media:"))
                note(when {
                    !ok -> "Nothing is playing"
                    what == "media:next" -> ">> Next track"
                    what == "media:prev" -> "<< Previous track"
                    else -> "Play / pause"
                })
            }
        }
        Session.changed()
    }

    private var panelAt = 0 // the quick setting chosen
    private var altHelp = false // the help is up while Alt is held (it goes with Alt)
    private var tweaks: List<Panels.Tweak> = emptyList()
    private var noteText = ""
    private val noteEnd = Runnable { if (Session.overlay == Panels.NOTE) panelClose() }

    // The help or the quick settings over what shows (the same hotkey again closes it). The display comes on and
    // Tunes goes for it; the terminal shows whatever the head's angle meanwhile.
    private fun panelOpen(kind: String) {
        if (glassesWith == null) return
        if (Session.overlay == kind) return panelClose()
        if (Session.tunes) tunesSet(false)
        if (Session.blank) display(true, "panel")
        work.removeCallbacks(noteEnd)
        Session.overlay = kind
        if (kind == Panels.SETTINGS) tweaks = tweaks().also { panelAt = panelAt.coerceIn(0, maxOf(0, it.size - 1)) }
        if (Session.pointer) pointerOff() // its arrow would sit on the panel
        forceApply()
        panelDraw()
        Log.i(TAG, "panel: $kind")
    }

    // What a hotkey did, for a moment at the bottom (an open panel shows the change itself).
    private fun note(text: String, ms: Long = 1600) {
        // A panel that takes the keys says it on its own line instead.
        when (Session.overlay) {
            Panels.HUB -> { hubSaid = text; return hubDraw() }
            Panels.HOME -> { homeSaid = text; return homeDraw() }
            Panels.PEOPLE -> { peopleSaid = text; return peopleDraw() }
            Panels.EDITOR -> { editor?.said = text; return editorDraw() }
            Panels.LISTEN -> { listenSaid = text; return listenDraw() }
            Panels.CAPTIONS -> { capState = text; return captionsDraw() }
        }
        if (Session.overlay == Panels.HELP || Session.overlay == Panels.SETTINGS) return panelDraw()
        if (glassesWith == null || Session.blank || Session.tunes) return
        noteText = text
        Session.overlay = Panels.NOTE
        forceApply()
        panelDraw()
        work.removeCallbacks(noteEnd)
        work.postDelayed(noteEnd, ms)
    }

    private fun panelClose() {
        altHelp = false
        if (Session.overlay.isEmpty()) return
        // What stays while its mode is on comes back: the TV remote (Alt+Y), voice mode's strip.
        val back = when {
            Session.hub -> Panels.HUB
            Session.home -> Panels.HOME
            Session.people -> Panels.PEOPLE
            Session.editor -> Panels.EDITOR
            Session.listenPanel -> Panels.LISTEN
            Session.captions.isNotEmpty() -> Panels.CAPTIONS
            Session.tvKeys -> Panels.TV
            Session.voiceMode -> Panels.VOICE
            else -> ""
        }
        if (back.isNotEmpty() && Session.overlay != back) {
            work.removeCallbacks(noteEnd)
            Session.overlay = back
            Native.overlay(null, null, false)
            Native.screenOverlay(null, 0, 0, 0, 0)
            forceApply()
            panelDraw()
            Session.changed()
            return
        }
        Session.overlay = ""
        work.removeCallbacks(noteEnd)
        Native.overlay(null, null, false)
        Native.screenOverlay(null, 0, 0, 0, 0)
        Session.overlayGen++
        screen?.poke()
        remote?.poke()
        forceApply()
        Session.changed()
    }

    // A key while a panel is open: the quick settings move with the arrows (up and down: the setting, left and
    // right: its value); Esc or Enter closes either.
    private fun panelKey(k: String) {
        if (Session.overlay == Panels.SETTINGS && tweaks.isNotEmpty()) {
            when (k) {
                "up" -> panelAt = (panelAt - 1 + tweaks.size) % tweaks.size
                "down" -> panelAt = (panelAt + 1) % tweaks.size
                "left", "right" -> tweaks.getOrNull(panelAt)?.change?.invoke(if (k == "left") -1 else 1)
                "esc", "enter" -> return panelClose()
                else -> return
            }
            return panelDraw()
        }
        if (k == "esc" || k == "enter" || k == "space") panelClose()
    }

    // The trackpad while a panel is open: a click closes it, scrolling walks the quick settings.
    private var panelSwipe = 0f
    private fun panelTrackpad(kind: Int, dy: Float) {
        when (kind) {
            VoxKeys.Trackpad.CLICK, VoxKeys.Trackpad.RIGHT -> panelClose()
            VoxKeys.Trackpad.WHEEL -> if (dy != 0f) panelKey(if (dy > 0) "up" else "down")
            VoxKeys.Trackpad.SCROLL -> {
                panelSwipe += dy
                while (kotlin.math.abs(panelSwipe) >= 40f) {
                    panelKey(if (panelSwipe > 0) "up" else "down")
                    panelSwipe -= if (panelSwipe > 0) 40f else -40f
                }
            }
        }
    }

    // The panel on what shows now: lines of the terminal, or a picture in a screen's frames (the other cleared).
    private fun panelDraw() {
        val kind = Session.overlay
        if (kind.isEmpty() || glassesWith == null) return
        val onScreen = settings.show == Settings.SCREEN
        val content = when (kind) {
            Panels.SETTINGS -> Panels.settings(if (onScreen) Session.source.ifEmpty { "Screen" } else "Terminal", tweaks, panelAt)
            Panels.NOTE -> Panels.note(noteText)
            Panels.VOICE -> Panels.voice(voiceStripText)
            Panels.INTRO -> return // drawn by introFrame
            Panels.TV -> return tvDraw()
            Panels.HUB -> return hubDraw()
            Panels.CAPTIONS -> return captionsDraw()
            Panels.HOME -> return homeDraw()
            Panels.PEOPLE -> return peopleDraw()
            Panels.EDITOR -> return editorDraw()
            Panels.LISTEN -> return listenDraw()
            else -> null // the help: laid out for each
        }
        if (onScreen) {
            val st = Native.screenStats()
            val w = st[5]
            val h = st[6]
            if (w <= 0 || h <= 0) return
            val art = if (content == null) Panels.helpArt(w, h, altHelp) else Panels.stripArt(w, h, content)
            Native.overlay(null, null, false)
            Native.screenOverlay(art.pixels, art.w, art.h, art.x, art.y)
            Session.overlayGen++
            screen?.poke()
            remote?.poke()
        } else {
            val g = region()
            val cols = if (g[2] > 0) g[0] / g[2] else settings.cols
            val rows = if (g[3] > 0) g[1] / g[3] else settings.rows
            val c = content ?: Panels.helpLines(cols, rows, altHelp)
            Native.screenOverlay(null, 0, 0, 0, 0)
            Native.overlay(c.lines.toTypedArray(), c.marks.toIntArray(), c.full)
        }
    }

    // The terminal shows whatever the head's angle: a session started from the phone (Always visible), keyer's
    // keys, the keyboard mode, or a panel open.
    // Heads-up (look-up only, Alt+0) wins: then only a panel that is open for a moment (the help, the quick
    // settings, a note, a title card) holds the terminal; voice mode's strip, the TV remote and the keyboard modes
    // show while looking up, as the terminal does.
    private fun forceApply() {
        val brief = Session.overlay == Panels.HELP || Session.overlay == Panels.SETTINGS || Session.overlay == Panels.NOTE ||
            Session.overlay == Panels.INTRO || Session.overlay == Panels.HUB || Session.overlay == Panels.CAPTIONS ||
            Session.overlay == Panels.HOME || Session.overlay == Panels.PEOPLE || Session.overlay == Panels.EDITOR ||
            Session.overlay == Panels.LISTEN
        val held = Session.overlay.isNotEmpty() || Session.keyer == "keys" || Session.keyer == KEYBOARD
        Native.force(forced || brief || (settings.phoneForce && held))
    }

    // The look-up view's settings changed (the app, the quick settings).
    private fun lookUpChanged() {
        Native.fxEnabled(settings.animations)
        Native.terminalFade(settings.fadeMs)
        Native.terminalDash(settings.dashRows)
        header()
        refresh()
    }

    // Alt+0 / Alt+Z, heads-up mode: the terminal only while looking up (it fades away with the head level), or always. A note says which
    // (the terminal shows for it, then goes by the new rule).
    private fun lookUpOnly() {
        settings.phone = true
        settings.phoneForce = !settings.phoneForce
        Log.i(TAG, "look-up only: ${!settings.phoneForce}")
        refresh()
        note(if (settings.phoneForce) "Always on" else "Heads-up: look up to see it")
    }

    // ------------------------------------------------ preview mode

    // A short tour, live on the glasses (Alt+D or the Preview button; again to stop): the phone's screen, the phone's
    // terminal, the laptop's screen, the laptop's terminal (one that is not set up is left out), then an end card.
    // Each gets a title card first (Intro: big letters decode, stand, collapse to a line), drawn over a terminal
    // that is really chosen underneath, so nothing else pulls the glasses back to the screen meanwhile. The
    // terminal under a card is the one the next step shows, or warms up (the laptop's), so each screen is ready
    // when its card goes. What the tour changed (always on, the dashboard's look) is put back at the end.
    private var demoRun = 0
    private var demoStyle = ""
    private var demoForce = false

    private fun demoStop(message: String) {
        if (!Session.demo) return
        demoRun++
        Session.demo = false
        settings.dashStyle = demoStyle
        settings.phoneForce = demoForce
        panelClose()
        refresh()
        dash()
        note(message)
        Session.changed()
    }

    // One frame of a title card over the whole terminal (text frames are sharp and small to send).
    private fun introFrame(title: String, sub: String, index: Int, total: Int, step: Int) {
        if (glassesWith == null || Session.blank) return
        val g = region()
        val cols = if (g[2] > 0) g[0] / g[2] else settings.cols
        val rows = if (g[3] > 0) g[1] / g[3] else settings.rows
        val (lines, marks) = Intro.frame(cols.coerceIn(30, 120), rows.coerceIn(12, 60), title, sub, index, total, step)
        if (Session.overlay != Panels.INTRO) {
            work.removeCallbacks(noteEnd)
            if (Session.pointer) pointerOff()
            Session.overlay = Panels.INTRO
            forceApply()
        }
        Native.screenOverlay(null, 0, 0, 0, 0)
        Native.overlay(lines.toTypedArray(), marks.toIntArray(), true)
    }

    private fun demoStart() {
        if (car != null) return note("Preview is off while driving")
        if (glassesWith == null || Session.glasses != "ready") {
            Session.tabs = "Preview needs the glasses connected"
            Session.changed()
            return
        }
        val servers = settings.servers()
        val phoneTerm = servers.firstOrNull { it.local }?.let { "term:${it.id}" }
        val laptop = servers.firstOrNull { !it.local }
        val laptopTerm = laptop?.let { "term:${it.id}" }
        val home = phoneTerm ?: laptopTerm ?: return note("Preview needs a terminal: add one in Vox")
        val gen = ++demoRun
        Session.demo = true
        demoStyle = settings.dashStyle
        demoForce = settings.phoneForce
        settings.phone = true
        settings.phoneForce = true
        settings.dashStyle = "classic"
        if (Session.blank) display(true, "demo")
        panelClose()
        refresh()
        var t = 0L
        val at: (Long, () -> Unit) -> Unit = { after, f ->
            t += after
            work.postDelayed({ if (demoRun == gen) f() }, t)
        }
        val total = 2 + (if (phoneTerm != null) 1 else 0) + (if (laptop != null) 2 else 0)
        var n = 0
        // under: the terminal chosen beneath the card (a quick cut, hidden by it). The card (Intro.PLAN, about 1.9 s),
        // then `show` brings its screen, which stays `hold` ms: the time a screen needs to come, plus about two
        // seconds to look at it.
        fun step(name: String, sub: String, under: String, hold: Long, show: (() -> Unit)? = null) {
            val i = ++n
            at(0) { if (chosen() != under || settings.show != Settings.TERMINAL) choose(under, Fx.CUT) }
            at(300) {}
            for ((s, wait) in Intro.PLAN) at(wait) { introFrame(name, sub, i, total, s) }
            at(Intro.END_WAIT) { panelClose(); show?.invoke() }
            at(hold) {}
        }
        // Not shared yet: Android's question comes first (VoxKeys answers it), which takes a moment more.
        val sharing = if (screen == null) 1500L else 0L
        step("Phone screen", "your phone, with a cursor", home, 3200 + sharing) { phoneScreen() }
        if (phoneTerm != null) step("Terminal", "SSH and mosh, on the phone", phoneTerm, 2200)
        if (laptop != null && laptopTerm != null) {
            step("Laptop screen", "your computer, on your glasses", laptopTerm, 3400) { choose("screen:${laptop.id}", Fx.RIGHT) }
            step("Laptop terminal", "its terminal, over SSH", laptopTerm, 2200)
        }
        step("Built on ScouterKit", "firmware by mafik", home, 600)
        at(0) { demoStop("Preview over") }
        Log.i(TAG, "preview: ${t / 1000} s")
    }

    // Alt+B: low battery. The glasses at a tenth of the brightness (the least they draw); again, back to the level
    // they had. Alt+= / - still change it meanwhile (that level then stays when it is left).
    private fun lowBattery() {
        if (!settings.lowBattery) {
            settings.brightnessFull = settings.brightness
            settings.brightness = LOW_BRIGHTNESS
            settings.lowBattery = true
        } else {
            settings.brightness = if (settings.brightness == LOW_BRIGHTNESS) settings.brightnessFull else settings.brightness
            settings.lowBattery = false
        }
        val level = settings.brightness
        Thread { Native.brightness(level) }.start()
        note(if (settings.lowBattery) "Low battery: dimmed to 10% (Alt+B back)" else "Brightness back to ${level * 100 / 42}%")
    }

    // Alt+Y: the keyboard is the Vizio TV's remote (arrows, Enter, volume...), or the keyboard again.
    private fun tvRemote() {
        if (!Vizio.paired(settings)) {
            Session.tvKeys = false
            return note("TV not paired: Settings, TV remote")
        }
        Session.tvKeys = !Session.tvKeys
        work.removeCallbacks(tvIdle)
        if (Session.tvKeys) {
            work.postDelayed(tvIdle, TV_IDLE_MS)
            tvShow()
        } else tvHide("TV remote off")
    }

    // The remote on the glasses while the keyboard is the TV's (Panels.TV, over whatever shows; the terminal under
    // it in view): how the TV is, the key pressed last lit for a moment.
    private var tvStatus = ""
    private var tvLit = ""
    private val tvUnlit = Runnable { tvLit = ""; if (Session.overlay == Panels.TV) tvDraw() }

    private fun tvShow() {
        if (Session.blank) display(true, "tv remote")
        if (Session.tunes) tunesSet(false)
        if (Session.pointer) pointerOff()
        tvStatus = "${settings.tvHost}: asking the TV…"
        Session.overlay = Panels.TV
        work.removeCallbacks(noteEnd)
        forceApply()
        tvDraw()
        Vizio.status(settings) { s -> work.post {
            tvStatus = if (s == null) "${settings.tvHost}: not answering (off at the wall, or another Wi-Fi?)" else "the TV is $s"
            if (Session.overlay == Panels.TV) tvDraw()
        } }
        Session.changed()
    }

    private fun tvHide(why: String) {
        Session.tvTyping = false
        work.removeCallbacks(tvUnlit)
        tvLit = ""
        if (Session.overlay == Panels.TV) panelClose()
        note(why)
    }

    // T in TV mode: the keyboard types into the TV's text field (a search box open on it), Esc or Tab ends it.
    private var tvTyped = "" // what was typed this time (shown on the glasses' remote)
    private fun tvTyping(on: Boolean) {
        Session.tvTyping = on && Session.tvKeys
        tvTyped = ""
        tvStatus = if (Session.tvTyping) "TYPING: open a search box on the TV, then type (Esc: done)" else "typing done"
        if (Session.overlay == Panels.TV) tvDraw()
        Session.changed()
    }

    private fun tvChar(code: Int) {
        if (code !in 8..126) return
        work.removeCallbacks(tvIdle)
        work.postDelayed(tvIdle, TV_IDLE_MS)
        tvTyped = if (code == 8) tvTyped.dropLast(1) else tvTyped + code.toChar()
        tvStatus = "TYPING: " + tvTyped.takeLast(40) + "█"
        if (Session.overlay == Panels.TV) tvDraw()
        Vizio.type(settings, code.toChar().toString()) { why ->
            if (why.isNotEmpty()) work.post { tvStatus = why; if (Session.overlay == Panels.TV) tvDraw() }
        }
    }

    private fun tvApp(app: Vizio.App) {
        work.removeCallbacks(tvIdle)
        work.postDelayed(tvIdle, TV_IDLE_MS)
        tvStatus = "opening ${app.name}…"
        if (Session.overlay == Panels.TV) tvDraw()
        Vizio.launch(settings, app) { why ->
            work.post {
                tvStatus = if (why.isEmpty()) "${app.name} on the TV" else "${app.name}: $why"
                if (Session.overlay == Panels.TV) tvDraw() else note(tvStatus)
            }
        }
    }

    private fun tvDraw() {
        if (glassesWith == null) return
        if (settings.show == Settings.SCREEN) {
            val st = Native.screenStats()
            if (st[5] <= 0 || st[6] <= 0) return
            val art = Panels.tvArt(st[5], st[6], tvStatus, tvLit)
            Native.overlay(null, null, false)
            Native.screenOverlay(art.pixels, art.w, art.h, art.x, art.y)
            Session.overlayGen++
            screen?.poke()
            remote?.poke()
        } else {
            val g = region()
            val cols = if (g[2] > 0) g[0] / g[2] else settings.cols
            val rows = if (g[3] > 0) g[1] / g[3] else settings.rows
            val c = Panels.tvLines(cols, rows, tvStatus, tvLit)
            Native.screenOverlay(null, 0, 0, 0, 0)
            Native.overlay(c.lines.toTypedArray(), c.marks.toIntArray(), true)
        }
    }

    // Five minutes without a TV key: the keyboard is the keyboard again (a forgotten remote would leave it dead).
    private val tvIdle = Runnable {
        if (Session.tvKeys) {
            Session.tvKeys = false
            tvHide("TV remote off (5 minutes idle)")
            Session.changed()
        }
    }

    private fun tvKey(name: String) {
        work.removeCallbacks(tvIdle)
        if (Session.tvKeys) work.postDelayed(tvIdle, TV_IDLE_MS)
        tvLit = name
        if (name == "POWER") tvStatus = "power… (waking takes up to 25 s)"
        work.removeCallbacks(tvUnlit)
        work.postDelayed(tvUnlit, 450)
        if (Session.overlay == Panels.TV) tvDraw()
        val done: (String) -> Unit = { why ->
            work.post {
                tvStatus = if (why.isEmpty()) "sent: ${name.lowercase().replace('_', ' ')}" else why
                if (Session.overlay == Panels.TV) tvDraw() else if (why.isNotEmpty()) note(why)
            }
        }
        if (name == "PLAY_PAUSE") Vizio.playPause(settings, done) else Vizio.key(settings, name, done)
    }

    // ------------------------------------------------ full panels: one way to draw them

    // A full panel (Panels.frameLines / frameArt) for `kind` while it is the one shown: rows(width in characters,
    // rows that fit) builds what it holds.
    private fun framePanel(kind: String, title: String, close: String, foot: String, rows: (Int, Int) -> List<Pair<String, Int>>) {
        if (glassesWith == null || Session.overlay != kind) return
        if (settings.show == Settings.SCREEN) {
            val st = Native.screenStats()
            if (st[5] <= 0 || st[6] <= 0) return
            val art = Panels.frameArt(st[5], st[6], title, close, rows(((st[5] - 24) / 7.4f).toInt(), ((st[6] - 90) / 18)), foot)
            Native.overlay(null, null, false)
            Native.screenOverlay(art.pixels, art.w, art.h, art.x, art.y)
            Session.overlayGen++
            screen?.poke()
            remote?.poke()
        } else {
            val g = region()
            val cols = if (g[2] > 0) g[0] / g[2] else settings.cols
            val n = if (g[3] > 0) g[1] / g[3] else settings.rows
            val c = Panels.frameLines(cols, n, title, close, rows(maxOf(20, cols - 4), maxOf(3, n - 2)), foot)
            Native.screenOverlay(null, 0, 0, 0, 0)
            Native.overlay(c.lines.toTypedArray(), c.marks.toIntArray(), true)
        }
    }

    // Opening one of the panels that take the keys (the hub, smart home, calls) closes the others.
    private fun panelsAway(keep: String) {
        if (keep != Panels.HUB && Session.hub) hubClose()
        if (keep != Panels.HOME && Session.home) homeClose()
        if (keep != Panels.PEOPLE && Session.people) peopleClose()
        if (keep != Panels.EDITOR && Session.editor) editorClose()
        if (keep != Panels.LISTEN && Session.listenPanel) listenClose()
    }

    private fun panelUp(kind: String) {
        if (Session.blank) display(true, kind)
        if (Session.tunes) tunesSet(false)
        if (Session.pointer) pointerOff()
        work.removeCallbacks(noteEnd)
        Session.overlay = kind
        forceApply()
    }

    // ------------------------------------------------ live captions and translation (Alt+E, Alt+I)

    // What is said around you, on the glasses as it is said: captions (Parakeet, English, the words so far while
    // they are said) or a translation into English (Whisper small, any of its languages, a phrase at a time). The
    // phone's microphone (it hears the room; the glasses' if Android keeps the phone's from Vox), on a panel over
    // whatever shows (Panels.CAPTIONS, held in view). The same key or Esc ends it; each session's words are kept in
    // files/captions.
    @Volatile private var translateEngine: TranslateEngine? = null
    private var capListener: VoiceListener? = null
    private val capLines = ArrayList<String>()
    private var capPartial = ""
    private var capState = ""
    private var capFile: java.io.File? = null
    private var capRun = 0

    private fun captions(mode: String) {
        if (Session.captions == mode) return captionsStop("")
        val translate = mode == "translate"
        val why = when {
            !VoiceModel.available -> "voice is not in this build"
            !translate && !VoiceModel.ready(this) -> "captions: download the speech model first (Vox, Voice tab)"
            translate && !TranslateModel.ready(this) -> "translation: download its model first (Vox, Voice tab)"
            carListener != null -> "Car mode has the microphone"
            glassesWith == null -> "the glasses are not connected"
            else -> null
        }
        if (why != null) return note(why, 3000)
        if (Session.captions.isNotEmpty()) captionsStop("", quiet = true)
        if (Session.voiceMode) voiceModeSet(false)
        listener?.stop() // always listening gives the microphone up (voiceAlways waits while captions run)
        panelsAway("")
        Session.captions = mode
        val run = ++capRun
        capLines.clear()
        capPartial = ""
        capState = "loading the model…"
        capFile = runCatching {
            java.io.File(filesDir, "captions").apply { mkdirs() }.resolve(
                java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()) + "-$mode.txt")
        }.getOrNull()
        Log.i(TAG, "captions: $mode")
        panelUp(Panels.CAPTIONS)
        captionsDraw()
        Thread {
            val engine: Transcriber? = runCatching {
                if (translate) translateEngine ?: TranslateEngine(this).also { translateEngine = it }
                else voiceEngine ?: Parakeet.use(this, "glasses").also { voiceEngine = it }
            }.getOrNull()
            work.post {
                if (run != capRun || Session.captions != mode) return@post
                if (engine == null) return@post captionsStop("the model did not load")
                captionsListen(engine, phone = true)
            }
        }.start()
        Session.changed()
    }

    private fun captionsListen(engine: Transcriber, phone: Boolean) {
        val run = capRun
        val translate = Session.captions == "translate"
        lateinit var l: VoiceListener
        l = VoiceListener(engine, realtime = !translate && settings.voiceRealtime, once = false,
            onPartial = { t -> work.post { if (capListener === l) { capPartial = t; captionsDraw() } } },
            onPhrase = { t -> work.post { if (capListener === l) captionsHeard(t, engine) } },
            onEnd = { why ->
                work.post {
                    if (capListener !== l) return@post
                    capListener = null
                    if (run != capRun || Session.captions.isEmpty()) return@post
                    if (why.isNotEmpty() && phone) { // the phone's microphone could not be had: the glasses'
                        Log.i(TAG, "captions: the phone's microphone: $why; the glasses' instead")
                        captionsListen(engine, phone = false)
                    } else captionsStop(why)
                }
            },
            onSpeaking = { s ->
                work.post {
                    if (capListener !== l) return@post
                    capState = if (s) "hearing…" else if (translate) "translating…" else "listening"
                    captionsDraw()
                }
            },
            mic = if (phone) HubMic(this, "captions") else GlassesMic)
        capListener = l
        capState = "listening (the ${if (phone) "phone's" else "glasses'"} microphone)"
        l.start()
        captionsDraw()
    }

    private fun captionsHeard(text: String, engine: Transcriber) {
        val translate = Session.captions == "translate"
        // Whisper says these to silence and noise.
        val bare = text.lowercase().filter { it.isLetter() || it == ' ' }.trim()
        if (translate && (bare.isEmpty() || bare in WHISPER_NOISE)) {
            capState = "listening"
            capPartial = ""
            return captionsDraw()
        }
        val lang = (engine as? TranslateEngine)?.lang.orEmpty()
        val line = if (translate && lang.isNotEmpty() && lang != "en") "[$lang] $text" else text
        capLines += line
        while (capLines.size > 200) capLines.removeAt(0)
        capPartial = ""
        capState = "listening"
        runCatching {
            capFile?.appendText(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  $line\n")
        }
        captionsDraw()
    }

    private fun captionsStop(why: String, quiet: Boolean = false) {
        capRun++
        capListener?.stop()
        capListener = null
        val was = Session.captions
        Session.captions = ""
        Log.i(TAG, "captions: off" + if (why.isNotEmpty()) " ($why)" else "")
        if (Session.overlay == Panels.CAPTIONS) panelClose()
        if (!quiet && was.isNotEmpty()) note(if (why.isEmpty()) "${if (was == "translate") "Translation" else "Captions"} off: kept in Vox's files"
            else "Captions stopped: $why", 2500)
        work.postDelayed({ voiceAlways() }, 1500)
        Session.changed()
    }

    private fun captionsDraw() {
        val translate = Session.captions == "translate"
        framePanel(Panels.CAPTIONS, if (translate) "VOX//TRANSLATE" else "VOX//CAPTIONS",
            if (translate) "Alt+I or Esc ends" else "Alt+E or Esc ends",
            if (translate) "any language into English · Whisper on the phone" else "what is said around you · Parakeet on the phone") { width, max ->
            val rows = mutableListOf<Pair<String, Int>>()
            rows += ("▌▌ " + (if (translate) "TRANSLATING TO ENGLISH" else "LIVE CAPTIONS") + " ▐░▒▓ " + capState) to 1
            rows += "" to 0
            val partial = if (capPartial.isEmpty()) emptyList() else Panels.wrap("$capPartial…", width)
            val room = maxOf(1, max - rows.size - partial.size)
            val said = capLines.takeLast(40).flatMap { Panels.wrap(it, width) }.takeLast(room)
            if (said.isEmpty() && partial.isEmpty()) rows += "  (nothing heard yet)" to 2
            for (l in said) rows += l to 0
            for (l in partial) rows += l to 2
            rows
        }
    }

    // ------------------------------------------------ smart home (Alt+O; Home.kt)

    // The TVs and Home Assistant's entities, one a row with its state: the arrows (or 1 ... 9) pick one; Enter or
    // Space turns it on or off, + / - its volume (a TV, a player), temperature (a thermostat) or brightness (a
    // light), M mutes, H a TV's home, R asks every state again, Esc closes.
    private var homeDevices: List<Home.Device> = emptyList()
    private var homeAt = 0
    private var homeSaid = ""
    private val homeStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val homeNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun homeOpen() {
        if (glassesWith == null) return
        if (Session.overlay == Panels.HOME) return homeClose()
        panelsAway(Panels.HOME)
        Session.home = true
        homeDevices = Home.devices(this)
        homeAt = homeAt.coerceIn(0, maxOf(0, homeDevices.size - 1))
        homeSaid = if (homeDevices.isEmpty()) "" else "asking…"
        panelUp(Panels.HOME)
        homeDraw()
        homeRefresh(null)
        // No Roku known yet: looked for once (SSDP, 3 s), then the panel again with what answered.
        if (Home.rokus(this).isEmpty()) Thread {
            val found = Home.findRokus(this)
            if (found.isNotEmpty()) work.post {
                if (!Session.home) return@post
                homeDevices = Home.devices(this)
                homeRefresh(null)
                homeDraw()
            }
        }.start()
        Session.changed()
    }

    private fun homeClose() {
        Session.home = false
        if (Session.overlay == Panels.HOME) panelClose()
        Session.changed()
    }

    // Each device's state asked again (one, or every one), the panel drawn as answers come.
    private fun homeRefresh(only: Home.Device?) {
        for (d in homeDevices) {
            if (only != null && d !== only) continue
            when (d.kind) {
                "vizio" -> Vizio.status(settings) { s -> homeStates[d.id] = s ?: "no answer"; work.post { homeSaid = ""; homeDraw() } }
                "roku" -> Thread {
                    val mode = Home.rokuInfo(d.id)["power-mode"]
                    homeStates[d.id] = when (mode) { null -> "no answer"; "PowerOn" -> "on"; else -> "standby" }
                    work.post { homeSaid = ""; homeDraw() }
                }.start()
                "ha" -> Thread {
                    val (name, state) = Home.haState(settings, d.id)
                    homeNames[d.id] = name
                    homeStates[d.id] = state
                    work.post { homeSaid = ""; homeDraw() }
                }.start()
            }
        }
    }

    private fun homeKey(k: String) {
        if (!Session.home) return
        when (k) {
            "up" -> homeAt = (homeAt - 1).coerceAtLeast(0)
            "down" -> homeAt = (homeAt + 1).coerceAtMost(maxOf(0, homeDevices.size - 1))
            "esc" -> return homeClose()
            "refresh" -> { homeSaid = "asking…"; homeRefresh(null) }
            "toggle", "plus", "minus", "mute", "home" -> homeAct(k)
            else -> k.toIntOrNull()?.let { n -> if (n in 1..homeDevices.size) homeAt = n - 1 }
        }
        homeDraw()
    }

    private fun homeAct(what: String) {
        val d = homeDevices.getOrNull(homeAt) ?: return
        val name = homeNames[d.id] ?: d.name
        val said = when (what) { "toggle" -> "on / off"; "plus" -> "up"; "minus" -> "down"; "mute" -> "mute"; else -> "home" }
        val done: (String) -> Unit = { why ->
            work.post {
                homeSaid = if (why.isEmpty()) "$name: $said" else "$name: $why"
                homeDraw()
                work.postDelayed({ if (Session.home) homeRefresh(d) }, 1500)
            }
        }
        homeSaid = "$name: $said…"
        when (d.kind) {
            "vizio" -> Vizio.key(settings, when (what) {
                "toggle" -> "POWER"; "plus" -> "VOL_UP"; "minus" -> "VOL_DOWN"; "mute" -> "MUTE"; else -> "HOME"
            }, done)
            "roku" -> Thread {
                done(Home.rokuKey(d.id, when (what) {
                    "toggle" -> "Power"; "plus" -> "VolumeUp"; "minus" -> "VolumeDown"; "mute" -> "VolumeMute"; else -> "Home"
                }))
            }.start()
            "ha" -> Thread {
                done(when (what) {
                    "toggle" -> Home.haToggle(settings, d.id)
                    "plus" -> Home.haStep(settings, d.id, true)
                    "minus" -> Home.haStep(settings, d.id, false)
                    else -> "not for this one"
                })
            }.start()
        }
    }

    private fun homeDraw() {
        framePanel(Panels.HOME, "VOX//HOME", "Esc closes", "↑↓ pick · Enter on/off · + - up/down · M mute · H home · R again") { width, max ->
            val rows = mutableListOf<Pair<String, Int>>()
            rows += ("▌▌ SMART HOME ▐░▒▓ " + homeSaid) to 1
            rows += "" to 0
            if (homeDevices.isEmpty()) {
                for (l in Panels.wrap("Nothing to work yet. Pair the Vizio (the TV tab), find a Roku or add Home Assistant " +
                    "(Vox, Settings, Smart home).", width)) rows += l to 0
            }
            for ((i, d) in homeDevices.withIndex()) {
                val kind = when (d.kind) { "vizio" -> "Vizio TV"; "roku" -> "Roku TV"; else -> d.id.substringBefore('.') }
                val name = (homeNames[d.id] ?: d.name).take(24)
                val line = " ${i + 1}  ${name.padEnd(24)} ${kind.padEnd(10)} ${homeStates[d.id] ?: "…"}"
                rows += (if (i == homeAt) line.replaceFirst(" ${i + 1} ", "■${i + 1} ") else line) to (if (i == homeAt) 3 else 0)
            }
            rows.take(max)
        }
    }

    // ------------------------------------------------ calls and texts (Alt+J; People.kt)

    // The starred contacts: 1 ... 9 (or the arrows) pick one; C (or Enter) asks to call, Enter calls; T types a
    // text, Enter sends it; V says it (Parakeet), Enter sends it; Esc goes back, then closes.
    private var peopleList: List<People.Person> = emptyList()
    private var peopleAt = 0
    private var peopleMode = "" // "", "call" (asked), "text" (typing), "say" (listening)
    private var peopleText = ""
    private var peopleSaid = ""

    private fun peopleOpen() {
        if (glassesWith == null) return
        if (Session.overlay == Panels.PEOPLE) return peopleClose()
        panelsAway(Panels.PEOPLE)
        Session.people = true
        peopleList = People.favorites(this)
        peopleAt = peopleAt.coerceIn(0, maxOf(0, peopleList.size - 1))
        peopleMode = ""
        peopleText = ""
        peopleSaid = ""
        Session.peopleTyping = false
        panelUp(Panels.PEOPLE)
        peopleDraw()
        Session.changed()
    }

    private fun peopleClose() {
        Session.people = false
        Session.peopleTyping = false
        peopleMode = ""
        if (Session.overlay == Panels.PEOPLE) panelClose()
        Session.changed()
    }

    private fun peopleKey(k: String) {
        if (!Session.people) return
        val p = peopleList.getOrNull(peopleAt)
        when (k) {
            "up" -> peopleAt = (peopleAt - 1).coerceAtLeast(0)
            "down" -> peopleAt = (peopleAt + 1).coerceAtMost(maxOf(0, peopleList.size - 1))
            "c" -> if (p != null) peopleMode = "call"
            "t" -> if (p != null) {
                peopleMode = "text"
                peopleText = ""
                Session.peopleTyping = true
            }
            "v" -> if (p != null) {
                if (capListener != null) peopleSaid = "captions have the microphone"
                else {
                    peopleMode = "say"
                    peopleText = ""
                    listen(true)
                }
            }
            else -> k.toIntOrNull()?.let { n -> if (n in 1..peopleList.size) { peopleAt = n - 1; peopleMode = "" } }
        }
        peopleDraw()
    }

    private fun peopleChar(code: Int) {
        if (peopleMode != "text" || code < 32) return
        peopleText = (peopleText + String(Character.toChars(code))).take(480)
        peopleDraw()
    }

    private fun peopleDel() {
        if (peopleMode != "text") return
        peopleText = peopleText.dropLast(1)
        peopleDraw()
    }

    private fun peopleEnter() {
        val p = peopleList.getOrNull(peopleAt) ?: return
        when (peopleMode) {
            "", "call" -> if (peopleMode.isEmpty()) peopleMode = "call" else {
                peopleSaid = People.call(this, p.number).ifEmpty { "calling ${p.name}" }
                peopleMode = ""
                Log.i(TAG, "people: a call")
            }
            "text" -> if (peopleText.isBlank()) peopleSaid = "type the text first" else {
                peopleSaid = People.text(this, p.number, peopleText.trim()).ifEmpty { "text sent to ${p.name}" }
                Log.i(TAG, "people: a text")
                peopleMode = ""
                peopleText = ""
                Session.peopleTyping = false
            }
        }
        peopleDraw()
    }

    private fun peopleEsc() {
        if (peopleMode.isEmpty()) return peopleClose()
        if (peopleMode == "say") stopListening()
        peopleMode = ""
        peopleText = ""
        Session.peopleTyping = false
        peopleDraw()
    }

    // Said for a text (V): it becomes the text, to send with Enter (or fix by typing).
    private fun peopleHeard(text: String) {
        peopleText = text.trim()
        peopleMode = "text"
        Session.peopleTyping = true
        peopleDraw()
    }

    private fun peopleDraw() {
        framePanel(Panels.PEOPLE, "VOX//CALLS", "Esc closes", "1-9 pick · C call · T type a text · V say a text · Enter yes") { width, max ->
            val rows = mutableListOf<Pair<String, Int>>()
            rows += ("▌▌ CALLS AND TEXTS ▐░▒▓ " + peopleSaid) to 1
            rows += "" to 0
            if (!People.allowed(this)) for (l in Panels.wrap("Allow Contacts, Phone and SMS for Vox: Vox, Settings, Calls and texts.", width))
                rows += l to 1
            else if (peopleList.isEmpty()) for (l in Panels.wrap("No starred contacts: star the people you call in the phone's " +
                "Contacts app, then Alt+J again.", width)) rows += l to 0
            for ((i, p) in peopleList.withIndex()) {
                val line = " ${i + 1}  ${p.name.take(26).padEnd(26)} ${p.number}"
                rows += (if (i == peopleAt) line.replaceFirst(" ${i + 1} ", "■${i + 1} ") else line) to (if (i == peopleAt) 3 else 0)
            }
            val p = peopleList.getOrNull(peopleAt)
            if (p != null && peopleMode.isNotEmpty()) {
                rows += "" to 0
                when (peopleMode) {
                    "call" -> rows += " ■ Call ${p.name}? Enter calls, Esc no" to 3
                    "say" -> rows += " ■ Say the text to ${p.name}… (Esc: no)" to 3
                    else -> {
                        rows += " ■ Text to ${p.name}: Enter sends, Esc no" to 3
                        for (l in Panels.wrap("› $peopleText█", width - 2)) rows += "  $l" to 1
                    }
                }
            }
            rows.take(max)
        }
    }

    // ------------------------------------------------ the text editor (Alt+N, or E on the hub; Editor.kt)

    // Plain text files on the glasses: the files in Vox's docs folder, then one to read and edit from the keyboard
    // (saved by itself 2 s after the last edit, and when it closes). The file stays open for the next Alt+N.
    private var editor: Editor? = null
    private var editorSwipe = 0f

    private fun editorOpen() {
        if (glassesWith == null) return
        if (Session.overlay == Panels.EDITOR) return editorClose()
        panelsAway(Panels.EDITOR)
        val ed = editor ?: Editor(java.io.File(getExternalFilesDir(null) ?: filesDir, "docs")).also { editor = it }
        ed.refresh()
        ed.reloadIfChanged()
        Session.editor = true
        panelUp(Panels.EDITOR)
        editorDraw()
        work.removeCallbacks(editorTick)
        work.postDelayed(editorTick, 1000)
        Session.changed()
    }

    private fun editorClose() {
        editor?.save()
        Session.editor = false
        work.removeCallbacks(editorTick)
        if (Session.overlay == Panels.EDITOR) panelClose()
        Session.changed()
    }

    // Edits saved 2 s after the last one.
    private val editorTick = object : Runnable {
        override fun run() {
            val ed = editor ?: return
            if (!Session.editor) return
            if (ed.due()) {
                ed.save()
                editorDraw()
            }
            work.postDelayed(this, 1000)
        }
    }

    // A key (Hotkeys: "code:meta:char").
    private fun editorKey(spec: String) {
        val ed = editor ?: return
        if (!Session.editor) return
        val p = spec.split(':')
        val code = p.getOrNull(0)?.toIntOrNull() ?: return
        if (!ed.key(code, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0)) return editorClose()
        editorDraw()
    }

    // The trackpad's wheel and two-finger slides scroll the file (or walk the list).
    private fun editorTrackpad(kind: Int, dy: Float) {
        val ed = editor ?: return
        when (kind) {
            VoxKeys.Trackpad.WHEEL -> if (dy != 0f) ed.scroll(if (dy > 0) -3 else 3) else return
            VoxKeys.Trackpad.SCROLL -> {
                editorSwipe += dy
                var n = 0
                while (kotlin.math.abs(editorSwipe) >= 30f) {
                    n += if (editorSwipe > 0) -1 else 1
                    editorSwipe -= if (editorSwipe > 0) 30f else -30f
                }
                if (n == 0) return
                ed.scroll(n)
            }
            else -> return
        }
        editorDraw()
    }

    private fun editorDraw() {
        val ed = editor ?: return
        val foot = if (ed.file == null) "↑↓ pick · Enter opens · N new file · R reload · Esc closes"
            else "type to edit · PgUp/PgDn page · Ctrl+S save · Ctrl+Z undo · Esc files"
        framePanel(Panels.EDITOR, "VOX//EDITOR", "Alt+N closes", foot) { width, max -> ed.rows(width, max) }
    }

    // ------------------------------------------------ Listen mode on the glasses (hub L, Alt+E while it is on; Listen.kt)

    // What was heard, newest at the bottom, the phrase being said under it. O turns Listen mode on or off, P pauses
    // it 15 min, the arrows go back through the lines, Esc closes. While it is open Listen reads phrases at once.
    private var listenBack = 0 // lines scrolled back
    private var listenSaid = ""
    private var listenDrawn = 0L
    private val listenWatch: () -> Unit = { work.post { if (Session.overlay == Panels.LISTEN) listenSoon() } }
    private val listenLater = Runnable { listenDraw() }
    private val listenTick = object : Runnable {
        override fun run() {
            if (!Session.listenPanel) return
            Listen.looking(60_000)
            work.postDelayed(this, 15_000)
        }
    }

    private fun listenOpen() {
        if (glassesWith == null) return
        if (Session.overlay == Panels.LISTEN) return listenClose()
        panelsAway(Panels.LISTEN)
        Session.listenPanel = true
        listenBack = 0
        listenSaid = ""
        panelUp(Panels.LISTEN)
        Listen.watch(listenWatch)
        Listen.looking(60_000)
        listenDraw()
        work.removeCallbacks(listenTick)
        work.postDelayed(listenTick, 15_000)
        Session.changed()
    }

    private fun listenClose() {
        Session.listenPanel = false
        Listen.unwatch(listenWatch)
        work.removeCallbacks(listenTick)
        work.removeCallbacks(listenLater)
        if (Session.overlay == Panels.LISTEN) panelClose()
        Session.changed()
    }

    // Drawn again at most every 400 ms (the words come fast while someone talks).
    private fun listenSoon() {
        val wait = 400 - (SystemClock.elapsedRealtime() - listenDrawn)
        work.removeCallbacks(listenLater)
        if (wait <= 0) listenDraw() else work.postDelayed(listenLater, wait)
    }

    // The newest line of a voice not named yet (among those shown): its voice group and label.
    private fun listenVoice(): Listen.Heard? = Listen.recent(40).dropLast(listenBack).lastOrNull { it.voice.isNotEmpty() }

    private var listenName: StringBuilder? = null // what is being typed: a name (N) or a question (A, D)
    private var listenNaming: Listen.Heard? = null
    private var listenFor = "" // "name", "ask", "deep"
    private var listenQuestion = ""
    private var listenAnswer = "" // the answer to the last question, as it comes ("" none)

    // A question about the transcript (Search.ask, on the phone's LFM2.5; in a meeting, about the meeting): the
    // answer grows on the panel. "catch up": the last 5 minutes.
    private fun listenAsk(q: String, deep: Boolean) {
        listenQuestion = if (q == "catch up") "Catch me up (the last 5 minutes)" else q
        listenAnswer = "thinking…"
        listenDraw()
        Thread {
            val grow: (String) -> Unit = { t -> work.post { listenAnswer = t.ifEmpty { "thinking…" }; listenSoon() } }
            val got = runCatching {
                when {
                    q == "catch up" -> Meetings.catchUp(this, grow)
                    Meetings.current != null && !deep -> Meetings.ask(this, q, grow)
                    else -> Search.ask(this, q, deep, grow)
                }
            }.getOrElse { "it stopped: ${it.message}" }
            work.post {
                listenAnswer = got ?: "no language model yet: Vox, Voice tab, Listen mode, AI models"
                listenDraw()
            }
        }.start()
    }

    private fun listenChar(code: Int) {
        val n = listenName ?: return
        if (code >= 32 && n.length < 40) n.append(String(Character.toChars(code)))
        listenDraw()
    }

    // The voice named (or said to be you): its lines get the name, it teaches the person's voiceprint.
    private fun listenAssign(h: Listen.Heard, name: String, me: Boolean) {
        Thread {
            val db = ListenDb.get(this)
            val person = if (me) Speakers.ME.also { if (db.personList().none { p -> p.first == it }) db.addPerson("You", false, it) }
                else db.personNamed(name) ?: db.addPerson(name, false)
            db.nameVoice(h.voice, person)
            Listen.peopleChanged()
            work.post {
                listenSaid = "${h.who.ifEmpty { "that voice" }} is ${if (me) "you" else name} now"
                listenDraw()
            }
        }.start()
    }

    private fun listenKey(k: String) {
        if (!Session.listenPanel) return
        listenName?.let { name ->
            when (k) {
                "enter" -> {
                    val n = name.toString().trim()
                    if (listenFor == "name") {
                        val h = listenNaming
                        if (h != null && n.isNotEmpty()) listenAssign(h, n, n.equals("me", true) || n.equals("you", true))
                    } else if (listenFor == "action" || listenFor == "note") {
                        if (n.isNotEmpty()) {
                            Meetings.mark(this, listenFor, n)
                            listenSaid = if (listenFor == "action") "action item kept" else "note kept"
                        }
                    } else if (listenFor == "meeting") {
                        Meetings.start(this, n)
                        listenSaid = "meeting started"
                    } else if (n.isNotEmpty()) listenAsk(n, listenFor == "deep")
                    listenName = null
                    Session.listenTyping = false
                }
                "del" -> if (name.isNotEmpty()) name.setLength(name.length - 1)
                "esc" -> {
                    listenName = null
                    Session.listenTyping = false
                }
            }
            return listenDraw()
        }
        Session.listenTyping = false
        when (k) {
            "n" -> {
                val h = listenVoice() ?: run { listenSaid = "no unnamed voice in view"; null }
                if (h != null) {
                    listenNaming = h
                    listenFor = "name"
                    listenName = StringBuilder()
                    Session.listenTyping = true
                } else Session.listenTyping = false
            }
            "s" -> if (Meetings.current != null) {
                Meetings.stop(this)
                listenSaid = "meeting ended: its notes are being written"
            } else {
                listenFor = "meeting"
                listenName = StringBuilder()
                Session.listenTyping = true
            }
            "b" -> if (Meetings.current != null) {
                Thread { Meetings.bookmark(this) }.start()
                listenSaid = "bookmarked the last 30 s"
            } else listenSaid = "bookmarks are for meetings: S starts one"
            "i", "t" -> if (Meetings.current != null) {
                listenFor = if (k == "i") "action" else "note"
                listenName = StringBuilder()
                Session.listenTyping = true
            } else {
                listenSaid = "S starts a meeting first"
                Session.listenTyping = false
            }
            "c" -> listenAsk("catch up", false)
            "a", "d" -> {
                listenFor = if (k == "d") "deep" else "ask"
                listenName = StringBuilder()
                Session.listenTyping = true
            }
            "m" -> listenVoice()?.let { listenAssign(it, "You", true) } ?: run { listenSaid = "no unnamed voice in view" }
            "o" -> if (Listen.on || ListenService.running) {
                ListenService.stop(this)
                listenSaid = "listen mode off"
            } else {
                ListenService.start(this)
                listenSaid = "starting (if it does not, open Vox once: Android's rule)"
            }
            "p" -> if (Listen.paused) {
                Listen.resume()
                listenSaid = "listening again"
            } else {
                Listen.pause(15 * 60_000L)
                listenSaid = "paused for 15 min"
            }
            "up" -> listenBack += 1
            "down" -> listenBack = maxOf(0, listenBack - 1)
            "pgup" -> listenBack += 8
            "pgdn" -> listenBack = maxOf(0, listenBack - 8)
            "esc" -> if (listenAnswer.isNotEmpty()) listenAnswer = "" else return listenClose()
        }
        listenDraw()
    }

    private fun listenTrackpad(kind: Int, dy: Float) {
        if (kind != VoxKeys.Trackpad.WHEEL || dy == 0f) return
        listenBack = maxOf(0, listenBack + if (dy > 0) 2 else -2)
        listenDraw()
    }

    private fun listenDraw() {
        listenDrawn = SystemClock.elapsedRealtime()
        val on = Listen.on
        val head = when {
            !on -> "off: O turns it on"
            Listen.paused -> "paused (P listens again)"
            else -> Listen.state
        }
        val foot = if (Meetings.current != null) "S end · B bookmark · I action · T note · C catch up · A ask"
            else "O on/off · P pause · N name · M me · A ask · D deep · S meeting · C catch up"
        framePanel(Panels.LISTEN, "VOX//LISTEN", "Esc closes", foot) { width, max ->
            val rows = mutableListOf<Pair<String, Int>>()
            rows += ("▌▌ LISTEN ▐░▒▓ ${Listen.today} lines today · $head" + if (listenSaid.isNotEmpty()) " · $listenSaid" else "") to 1
            Meetings.current?.let { m ->
                val min = (System.currentTimeMillis() - m.started) / 60000
                val st = runCatching { Meetings.stats(this) }.getOrNull()
                rows += ("▌▌ MEETING ▐░▒▓ ${m.title} · $min min" + (st?.let { " · " + Words.line(it) } ?: "")) to 1
            }
            if (Meetings.writing.isNotEmpty()) rows += " writing the notes of \"${Meetings.writing}\"…" to 2
            listenName?.let { n ->
                rows += when (listenFor) {
                    "name" -> " ■ Who is ${listenNaming?.who?.ifEmpty { "this voice" }}? Type the name (or me), Enter, Esc no"
                    "meeting" -> " ■ A meeting: its title (or just Enter), Esc no"
                    "action" -> " ■ Action item: who, what, by when; Enter keeps it"
                    "note" -> " ■ A note for this moment; Enter keeps it"
                    else -> " ■ Ask ${if (listenFor == "deep") "(deep) " else ""}about what was said: type, Enter, Esc no"
                } to 3
                rows += "   › $n█" to 1
            }
            if (listenAnswer.isNotEmpty() && listenName == null) {
                rows += " ■ $listenQuestion" to 3
                for (l in Panels.wrap(listenAnswer, maxOf(10, width - 2)).takeLast(maxOf(1, max - rows.size - 3))) rows += " $l" to 0
                rows += "" to 0
            }
            val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
            val body = mutableListOf<Pair<String, Int>>()
            for (h in Listen.recent(40)) {
                val first = fmt.format(java.util.Date(h.start)) + " " + (if (h.who.isNotEmpty()) "${h.who} › " else "› ")
                val wrapped = Panels.wrap(h.text, maxOf(10, width - first.length))
                for ((i, l) in wrapped.withIndex()) body += ((if (i == 0) first else " ".repeat(first.length)) + l) to (if (h.quiet) 2 else 0)
            }
            if (on && (Listen.speaking || Listen.partial.isNotEmpty())) {
                for ((i, l) in Panels.wrap(Listen.partial.ifEmpty { "…" }, maxOf(10, width - 8)).withIndex())
                    body += ((if (i == 0) "  now › " else "        ") + l) to 1
            }
            if (body.isEmpty()) body += (if (on) "Nothing heard yet: talk near the phone."
                else "Listen mode writes down what is said around the phone, all day.") to 2
            val room = maxOf(1, max - rows.size)
            listenBack = listenBack.coerceIn(0, maxOf(0, body.size - room))
            val end = body.size - listenBack
            rows += body.subList(maxOf(0, end - room), end)
            rows
        }
    }

    // ------------------------------------------------ the hub (Alt+G; Hub.kt)

    // The everyday tools on the glasses (Panels.HUB, over whatever shows): the time, the timer (Timer), the
    // stopwatch, the next alarm (the phone's clock), reminders, the newest notes, the next events. One letter each;
    // the ones that ask (timer, alarm, reminder, note) take what is typed until Enter (Esc: back).
    private var hubInput = "" // being typed for: "timer", "alarm", "reminder", "note"; "" none
    private var hubText = ""
    private var hubSaid = "" // what the last key did, at the bottom

    private fun hubOpen() {
        if (glassesWith == null) return
        if (Session.overlay == Panels.HUB) return hubClose()
        panelsAway(Panels.HUB)
        if (Session.blank) display(true, "hub")
        if (Session.tunes) tunesSet(false)
        if (Session.pointer) pointerOff()
        Session.hub = true
        hubInput = ""
        hubText = ""
        hubSaid = ""
        Session.hubTyping = false
        work.removeCallbacks(noteEnd)
        Session.overlay = Panels.HUB
        forceApply()
        hubDraw()
        work.removeCallbacks(hubTick)
        work.postDelayed(hubTick, 1000)
        Session.changed()
    }

    private fun hubClose() {
        Session.hub = false
        Session.hubTyping = false
        hubInput = ""
        work.removeCallbacks(hubTick)
        if (Session.overlay == Panels.HUB) panelClose()
        Session.changed()
    }

    // The clock, the timer and the stopwatch move while it is open.
    private val hubTick = object : Runnable {
        override fun run() {
            if (Session.overlay != Panels.HUB) return
            hubDraw()
            work.postDelayed(this, if (Hub.stopwatchRunning) 500L else 1000L)
        }
    }

    private fun hubRows(): List<Pair<String, Int>> {
        val r = mutableListOf<Pair<String, Int>>()
        val now = System.currentTimeMillis()
        val wx = Weather.now?.let { "   ${it.temp}°${it.unit.uppercase()}  ${it.place}" } ?: ""
        r += ("▌▌ " + java.text.SimpleDateFormat("h:mm a  ·  EEE, MMM d", java.util.Locale.US).format(java.util.Date(now)) +
            " ▐░▒▓$wx") to 1
        r += "" to 0
        fun item(key: String, name: String, state: String, asks: String?) {
            if (asks != null && hubInput == asks) { // the one being typed for: its question, then the line typed
                r += (" ■ $key  ${name.padEnd(10)} ${hubPrompt(asks)}") to 3
                r += ("          › " + hubText.takeLast(52) + "█") to 1
            } else r += (" › $key  ${name.padEnd(10)} $state") to 0
        }
        item("T", "Timer", when {
            Timer.running -> "${Timer.text()} left   (P pause, Shift+T stop)"
            Timer.remaining() > 0 -> "${Timer.text()} paused   (P go on)"
            else -> "off"
        }, "timer")
        item("S", "Stopwatch", when {
            Hub.stopwatchRunning -> "${Hub.stopwatchText()} running   (S stop)"
            Hub.stopwatch() > 0 -> "${Hub.stopwatchText()} stopped   (S go on, Shift+S zero)"
            else -> "off"
        }, null)
        val next = runCatching { getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()
        item("A", "Alarm", if (next != null) "next: " + Hub.whenText(next, now) else "none set", "alarm")
        val rem = Hub.reminders(this)
        item("R", "Reminder", if (rem.isEmpty()) "none" else "${rem.size}: next ${Hub.whenText(rem[0].at, now)} ${rem[0].text}", "reminder")
        val notes = Hub.notes(this)
        item("N", "Note", if (notes.isEmpty()) "none yet" else "${notes.size} kept   (Shift+N drops the newest)", "note")
        item("E", "Editor", "text files on the glasses (Alt+N)", null)
        item("L", "Listen", if (Listen.on) "${Listen.today} lines today · ${Listen.state}" else "off (L opens it, O turns it on)", null)
        if (notes.isNotEmpty()) {
            r += "" to 0
            r += "▌▌ NOTES ▐░▒▓" to 1
            for (n in notes.take(4)) r += "  · $n" to 0
        }
        val events = if (Agenda.allowed(this)) runCatching { Agenda.next(this).take(2) }.getOrDefault(emptyList()) else emptyList()
        if (events.isNotEmpty()) {
            r += "" to 0
            r += "▌▌ NEXT ▐░▒▓" to 1
            for (e in events) r += ("  · " + (if (e.allDay) "today" else Hub.whenText(e.begin, now)) + "  " + e.title) to 0
        }
        if (hubSaid.isNotEmpty()) {
            r += "" to 0
            r += " › $hubSaid" to 1
        }
        return r
    }

    private fun hubPrompt(what: String) = when (what) {
        "timer" -> "how long? (5, 90s, 1:30, 1h30)"
        "alarm" -> "what time? (7:30, 6:45p, 19:00)"
        "reminder" -> "when, what? (20 call mom, 7:30p dinner)"
        else -> "type it, Enter keeps it"
    }

    private fun hubDraw() {
        if (glassesWith == null || Session.overlay != Panels.HUB) return
        val items = hubRows()
        if (settings.show == Settings.SCREEN) {
            val st = Native.screenStats()
            if (st[5] <= 0 || st[6] <= 0) return
            val art = Panels.hubArt(st[5], st[6], items)
            Native.overlay(null, null, false)
            Native.screenOverlay(art.pixels, art.w, art.h, art.x, art.y)
            Session.overlayGen++
            screen?.poke()
            remote?.poke()
        } else {
            val g = region()
            val cols = if (g[2] > 0) g[0] / g[2] else settings.cols
            val rows = if (g[3] > 0) g[1] / g[3] else settings.rows
            val c = Panels.hubLines(cols, rows, items)
            Native.screenOverlay(null, 0, 0, 0, 0)
            Native.overlay(c.lines.toTypedArray(), c.marks.toIntArray(), true)
        }
    }

    // A letter on the hub: lower case does it, upper case (Shift) takes it back.
    private fun hubKey(k: String) {
        if (!Session.hub) return
        when (k) {
            "t" -> hubAsk("timer")
            "T" -> { Timer.reset(); hubSaid = "timer stopped" }
            "p" -> hubSaid = if (Timer.running || Timer.remaining() > 0) {
                Timer.toggle()
                if (Timer.running) "timer going on" else "timer paused"
            } else "no timer: T sets one"
            "s" -> { Hub.stopwatchToggle(); hubSaid = if (Hub.stopwatchRunning) "stopwatch going" else "stopwatch stopped" }
            "S" -> { Hub.stopwatchReset(); hubSaid = "stopwatch zeroed" }
            "a" -> hubAsk("alarm")
            "r" -> hubAsk("reminder")
            "R" -> hubSaid = Hub.dropReminder(this)?.let { "reminder dropped: ${it.text}" } ?: "no reminder to drop"
            "n" -> hubAsk("note")
            "N" -> hubSaid = Hub.dropNote(this)?.let { "note dropped: $it" } ?: "no note to drop"
            "e" -> return editorOpen()
            "l" -> return listenOpen()
        }
        dash()
        hubDraw()
        work.removeCallbacks(hubTick)
        work.postDelayed(hubTick, 500)
    }

    private fun hubAsk(what: String) {
        hubInput = what
        hubText = ""
        Session.hubTyping = true
    }

    private fun hubChar(code: Int) {
        if (hubInput.isEmpty() || code < 32) return
        hubText = (hubText + String(Character.toChars(code))).take(120)
        hubDraw()
    }

    private fun hubDel() {
        if (hubInput.isEmpty()) return
        hubText = hubText.dropLast(1)
        hubDraw()
    }

    private fun hubEsc() {
        if (hubInput.isEmpty()) return hubClose()
        hubInput = ""
        hubText = ""
        Session.hubTyping = false
        hubDraw()
    }

    // Enter: what was typed done; a time it cannot read keeps the line to fix.
    private fun hubEnter() {
        val t = hubText.trim()
        hubSaid = when (hubInput) {
            "timer" -> when (val d = Hub.duration(t)) {
                null -> return hubWrong("timer: like 5, 90s, 1:30 or 1h30")
                0L -> { Timer.reset(); "timer off" }
                else -> { Timer.set(d); "timer set: ${Timer.text(d)}" }
            }
            "alarm" -> Hub.alarm(t)?.let { (h, m) -> setAlarm(h, m) } ?: return hubWrong("alarm: like 7:30, 6:45p or 19:00")
            "reminder" -> Hub.reminder(t)?.let { (at, words) ->
                Hub.addReminder(this, at, words)
                "reminder at ${Hub.whenText(at)}: $words"
            } ?: return hubWrong("reminder: like 20 call mom or 7:30p dinner")
            "note" -> if (t.isEmpty()) return hubWrong("note: type something first") else { Hub.addNote(this, t); "note kept" }
            else -> return
        }
        Log.i(TAG, "hub: $hubInput done")
        hubInput = ""
        hubText = ""
        Session.hubTyping = false
        dash()
        hubDraw()
    }

    private fun hubWrong(why: String) {
        hubSaid = why
        hubDraw()
    }

    // An alarm in the phone's clock app (it rings there, as one set by hand).
    private fun setAlarm(hour: Int, minute: Int): String = runCatching {
        startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Vox")
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "hub: alarm asked for ${Hub.clockText(hour, minute)}")
        "alarm set for ${Hub.clockText(hour, minute)} (the phone's clock)"
    }.getOrElse { "alarm: the clock app did not take it (${it.message})" }

    // A timer's end or a reminder: on the glasses for 8 s (the display on for it), the phone buzzes, and a
    // notification stays on the phone.
    private var alertId = 0
    private fun alert(title: String, text: String) {
        Log.i(TAG, "alert: $title")
        if (Session.blank) display(true, "alert")
        note(if (text.isEmpty()) title else "$title: $text", 8000)
        runCatching {
            val v = getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 350), -1))
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(ALERTS, "Timers and reminders", NotificationManager.IMPORTANCE_HIGH))
            manager.notify(ALERT_ID + (alertId++ % 20), android.app.Notification.Builder(this, ALERTS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build())
        }
    }

    // Alt+= / -: brighter or dimmer, a seventh of the range a press.
    private fun bright(dir: Int) {
        settings.lowBattery = false
        settings.brightness = (settings.brightness + 6 * dir).coerceIn(0, 42)
        val level = settings.brightness
        Thread { Native.brightness(level) }.start()
        note("Brightness ${level * 100 / 42}%")
    }

    // The quick settings of what shows: a screen's picture, or the terminal and its look-up view.
    private fun tweaks(): List<Panels.Tweak> {
        fun num(name: String, get: () -> Int, set: (Int) -> Unit, lo: Int, hi: Int, step: Int,
                show: (Int) -> String = { "$it" }, after: () -> Unit = {}) =
            Panels.Tweak(name, { show(get()) }) { d -> set((get() + d * step).coerceIn(lo, hi)); after() }
        fun pick(name: String, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit,
                 after: () -> Unit = {}) =
            Panels.Tweak(name, { options.firstOrNull { it.first == get() }?.second ?: get() }) { d ->
                val i = options.indexOfFirst { it.first == get() }.coerceAtLeast(0)
                set(options[(i + d + options.size) % options.size].first)
                after()
            }
        fun flag(name: String, get: () -> Boolean, set: (Boolean) -> Unit, after: () -> Unit = {}) =
            Panels.Tweak(name, { if (get()) "on" else "off" }) { _ -> set(!get()); after() }
        val bright = num("Brightness", { settings.brightness }, { settings.brightness = it }, 0, 42, 3, { "${it * 100 / 42}%" }) {
            val level = settings.brightness
            Thread { Native.brightness(level) }.start()
        }
        val speed = num("Cursor speed", { settings.trackpadSpeed }, { settings.trackpadSpeed = it }, 1, 10, 1)
        val accel = num("Cursor acceleration", { settings.trackpadAccel }, { settings.trackpadAccel = it }, 0, 10, 1)
        val scrollSpeed = num("Scroll speed", { settings.scrollSpeed }, { settings.scrollSpeed = it }, 1, 10, 1)
        val natural = flag("Natural scrolling", { settings.scrollNatural }, { settings.scrollNatural = it })
        val lowBat = Panels.Tweak("Low battery (10%)", { if (settings.lowBattery) "on" else "off" }) { _ -> lowBattery() }
        val voice = Panels.Tweak("Voice mode (Alt+V)", { if (Session.voiceMode) "on: talking to the terminal" else "off" }) { _ ->
            voiceModeSet(!Session.voiceMode)
        }
        val words = flag("Realtime words", { settings.voiceRealtime }, { settings.voiceRealtime = it })
        val t = mutableListOf<Panels.Tweak>()
        if (settings.show == Settings.SCREEN) {
            t += pick("Picture", listOf("2" to "black and white (fast)", "4" to "4 greys", "8" to "8 greys", "16" to "16 greys"),
                { settings.screenLevels.toString() }, { settings.screenLevels = it.toInt() })
            t += num("Invert", { settings.screenInvertPct }, { settings.screenInvertPct = it }, 0, 100, 10, { "$it%" })
            t += num("Threshold", { settings.screenThreshold }, { settings.screenThreshold = it }, 16, 240, 8)
            t += num("Contrast", { settings.screenContrast }, { settings.screenContrast = it }, 0, 100, 10)
            t += num("Zoom", { settings.screenZoom }, { settings.screenZoom = it }, 100, 400, 25, { "$it%" })
            t += num("Zoomed part", { settings.screenPanY }, { settings.screenPanY = it }, 0, 100, 10, { "$it% down" })
            t += pick("Fit", listOf(Settings.FIT to "whole screen", Settings.FILL to "fill", Settings.STRETCH to "stretch"),
                { settings.screenFit }, { settings.screenFit = it })
            t += pick("Turn", listOf("0" to "none", "90" to "90 right", "180" to "upside down", "270" to "90 left"),
                { settings.screenRotate.toString() }, { settings.screenRotate = it.toInt() })
            t += num("Cut top", { settings.screenCropTop }, { settings.screenCropTop = it }, 0, 300, 10, { "${it / 10}%" })
            t += num("Cut bottom", { settings.screenCropBottom }, { settings.screenCropBottom = it }, 0, 300, 10, { "${it / 10}%" })
            t += flag("Clean specks", { settings.screenClean }, { settings.screenClean = it })
            t += num("Frames a second", { settings.screenFps }, { settings.screenFps = it }, 1, 30, 1)
            t += bright
            t += lowBat
            if (onPhone()) t += flag("Pocket mode", { settings.pocket }, { settings.pocket = it }) { trackpadMode() }
            t += speed
            t += accel
            t += scrollSpeed
            t += natural
            t += voice
        } else {
            t += bright
            t += lowBat
            // Heads-up mode (Alt+0): the terminal only while looking up (it fades in and out with the head).
            t += Panels.Tweak("Heads-up mode (Alt+0)", { if (settings.phoneForce) "off: always on" else "on: look up to see" }) { _ ->
                settings.phone = true
                settings.phoneForce = !settings.phoneForce
                refresh()
            }
            t += num("Fade in / out", { settings.fadeMs }, { settings.fadeMs = it }, 0, 1500, 100,
                { if (it == 0) "at once" else "${it} ms" }) { Native.terminalFade(settings.fadeMs) }
            t += flag("Look-up dashboard", { settings.dashboard }, { settings.dashboard = it }) { lookUpChanged() }
            t += pick("Dashboard", Dashboard.STYLES, { settings.dashStyle }, { settings.dashStyle = it }) { lookUpChanged() }
            t += num("Dashboard height", { settings.dashHeight }, { settings.dashHeight = it }, 56, 140, 14,
                { "$it px" }) { lookUpChanged() }
            t += voice
            t += words
            t += speed
            t += accel
            t += scrollSpeed
            t += natural
            t += flag("Type on the glasses", { settings.keysToGlasses }, { settings.keysToGlasses = it })
            t += flag("Animations", { settings.animations }, { settings.animations = it }) { Native.fxEnabled(settings.animations) }
            t += pick("Tunes follows", listOf(Tunes.BEAT to "the beat", Tunes.VOLUME to "the volume", Tunes.STEADY to "a steady tempo"),
                { settings.tunesMode }, { settings.tunesMode = it })
        }
        if (Vizio.paired(settings)) t += Panels.Tweak("TV remote (Alt+Y)", { if (Session.tvKeys) "on" else "off" }) { _ -> tvRemote() }
        return t
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
    private var lastSwitchAt = -Fx.QUICK_MS

    private fun choose(key: String, style: Int = Fx.JUMP) {
        val t0 = SystemClock.uptimeMillis()
        settings.phone = true
        if (Session.tunes) tunesSet(false)
        val from = chosen()
        val moves = key != from || (key == Settings.PHONE && screen != null && settings.show != Settings.SCREEN)
        // Quick toggling: a switch soon after the last one cuts (one frame) instead of animating.
        val quick = t0 - lastSwitchAt < Fx.QUICK_MS
        if (moves) lastSwitchAt = t0
        val fx = if (!moves) Fx.NONE else if (quick) Fx.CUT else style
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
                parkRemote()
                render(Settings.TERMINAL)
            }
            key == Settings.PHONE -> {
                parkRemote() // first: no more of the server's pictures (one of them would come in with it)
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
                work.removeCallbacks(remotePark)
                if (remote?.key != key) {
                    startRemote(s)
                    render(Settings.TERMINAL) // its card ("Starting…") until its first image
                } else render(if (remote!!.images > 0) Settings.SCREEN else Settings.TERMINAL)
                remote?.poke() // kept connected: its picture now
                laptopInput() // its keyboard and mouse
                update(screen = "starting")
                work.removeCallbacks(screenTicker)
                work.postDelayed(screenTicker, 1000)
            }
            else -> return
        }
        Session.source = settings.sourceName(key)
        SourceTile.refresh(this)
        Log.i(TAG, "switch: $from -> $key (fx $fx): ${SystemClock.uptimeMillis() - t0} ms on the phone")
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

    // Another source in front: a server's screen stays connected, its newest picture waiting unseen, so going back
    // to it shows at once; unseen for PARK_MS it stops.
    private val remotePark = Runnable { remote?.let { if (chosen() != it.key) stopRemote() } }

    private fun parkRemote() {
        if (Session.screenSource.startsWith("screen:")) Session.screenSource = ""
        laptopIn?.send("r") // no button left held there
        laptopButton = 0
        if (remote == null) return
        work.removeCallbacks(remotePark)
        work.postDelayed(remotePark, PARK_MS)
    }

    private fun stopRemote() {
        work.removeCallbacks(remotePark)
        laptopInputStop()
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
        laptopInputStop()
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

    // The pointer: an arrow the arms draw over the terminal at pixel (ptrX, ptrY) of its region (y < 0: over the
    // dashboard above it). The trackpad glides it like a mouse, the keys (Alt+M) and the touchpad step it a cell;
    // over the dashboard the place under its tip is ringed and its clicks are that place's.
    private var ptrX = 0f
    private var ptrY = 0f
    // The dashboard's place under the pointer (an index in barHits, as last drawn), or -1.
    private var pointerBar = -1
    private var barHits: List<Dashboard.Hit> = emptyList()
    private var barWidth = 0
    private val pointerIdle = Runnable { // hidden after a while; it comes back where it was
        Native.pointerAt(ptrX.toInt(), ptrY.toInt(), false)
        hideCursor()
        if (pointerBar >= 0) {
            pointerBar = -1
            dash()
        }
    }

    // The terminal's region on the glasses: width, height, a cell's width and height, the dashboard's height.
    private fun region(): IntArray = Native.termGeometry()

    private fun pointer(cmd: String) {
        val parts = cmd.split(":")
        if (onPhone()) {
            phonePointer(parts)
            Session.changed()
            return
        }
        when (parts[0]) {
            "toggle" -> if (Session.pointer) pointerOff() else pointerOn()
            // Alt+M: the pointer with the keys (arrows, Enter, Space), or off.
            "keys" -> if (Session.pointer && Session.pointerKeys) pointerOff() else {
                if (!Session.pointer) pointerOn()
                Session.pointerKeys = true
            }
            "off" -> pointerOff()
            "move" -> { // cells (the keys, the touchpad)
                if (!Session.pointer) pointerOn()
                val g = region()
                moveBy((parts.getOrNull(1)?.toIntOrNull() ?: 0) * g[2].toFloat(), (parts.getOrNull(2)?.toIntOrNull() ?: 0) * g[3].toFloat())
            }
            "glide" -> { // pixels (the trackpad, the touchpad)
                if (!Session.pointer) pointerOn()
                moveBy(parts.getOrNull(1)?.toFloatOrNull() ?: 0f, parts.getOrNull(2)?.toFloatOrNull() ?: 0f)
            }
            "click", "right", "up", "down" -> {
                if (!Session.pointer) pointerOn()
                if (ptrY < 0) {
                    if (pointerBar >= 0) barClick(parts[0])
                } else {
                    val g = region()
                    val button = when (parts[0]) { "right" -> 2; "up" -> 64; "down" -> 65; else -> 0 }
                    val row = if (g[3] > 0) (ptrY / g[3]).toInt() else 0
                    val col = if (g[2] > 0) (ptrX / g[2]).toInt() else 0
                    if (!Native.mouse(button, row, col))
                        Session.tabs = "the terminal's program takes no mouse (in tmux: Mouse in tmux, Terminal tab)"
                    else Session.tabs = ""
                }
                showPointer()
            }
        }
        Session.changed()
    }

    // Scrolling the terminal: dir -1 back in its history (up), 1 forward; `steps` steps of Settings.scrollSpeed lines.
    // To the program as the mouse's wheel when it takes the mouse (tmux with its mouse on), else tmux's own history
    // over the server's kept SSH connection (steps that come meanwhile add up).
    private var scrollPending = 0
    private var scrolling = false
    private fun scroll(dir0: Int, steps: Int) {
        val dir = if (settings.scrollNatural) dir0 else -dir0
        val lines = settings.scrollSpeed.coerceIn(1, 10) * steps
        val g = region()
        // Where the wheel turns: under the pointer, or (it off) the terminal's middle.
        val y = if (Session.pointer) ptrY else g[1] / 2f
        val x = if (Session.pointer) ptrX else g[0] / 2f
        val row = if (g[3] > 0 && y >= 0) (y / g[3]).toInt() else 0
        val col = if (g[2] > 0) (x / g[2]).toInt() else 0
        if (Session.pointer && ptrY < 0 && pointerBar >= 0) return barClick(if (dir < 0) "up" else "down")
        // The wheel: each event about 3 lines in tmux.
        val wheel = maxOf(1, (lines + 2) / 3)
        var taken = true
        repeat(wheel) { if (taken) taken = Native.mouse(if (dir < 0) 64 else 65, row, col) }
        if (taken) return
        // tmux's mouse is off (only the pointer turned it on): on now, so the next scroll goes as the wheel at once;
        // this one through its copy mode.
        val t = SystemClock.uptimeMillis()
        if (settings.tmuxMouse && t - mouseAskedAt > 20000) {
            mouseAskedAt = t
            tabs("mouse")
        }
        val s = settings.active() ?: return
        if (Tabs.session(s) == null || terms[s.id]?.status != "connected") {
            Session.tabs = "scrolling needs tmux (or a program that takes the mouse)"
            Session.changed()
            return
        }
        scrollPending += dir * lines
        if (!scrolling) scrollFlush(s)
    }

    private var mouseAskedAt = -20000L

    private fun scrollFlush(s: Settings.Server) {
        val n = scrollPending
        if (n == 0) return
        scrollPending = 0
        val session = Tabs.session(s) ?: return
        val cmd = Tabs.command(if (n < 0) "scroll:up:${-n}" else "scroll:down:$n", session) ?: return
        scrolling = true
        Thread {
            runCatching { Bootstrap(vault).runShared(s.host, s.port, s.user, cmd) }
            work.post {
                scrolling = false
                scrollFlush(s)
            }
        }.start()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val g = region()
        if (g[0] <= 0) return
        ptrX = (ptrX + dx).coerceIn(0f, g[0] - 1f)
        ptrY = (ptrY + dy).coerceIn(-g[4].toFloat(), g[1] - 1f)
        hover()
        showPointer()
    }

    // Over the dashboard: the place under the tip, ringed (the dashboard drawn again when it changes).
    private fun hover() {
        val g = region()
        val y = ptrY + g[4]
        val at = if (ptrY < 0) barHits.indexOfFirst { ptrX >= it.left - 2 && ptrX <= it.right + 2 && y >= it.top - 2 && y <= it.bottom + 2 } else -1
        if (at != pointerBar) {
            pointerBar = at
            dash()
        }
    }

    // Away from the dashboard (a notification opened, the phone's screen chosen): no place ringed.
    private fun barLeave() {
        if (pointerBar < 0) return
        pointerBar = -1
        dash()
    }

    // A click on the bar: a tab is shown (a right click closes it), the + makes another, PHONE brings up the
    // phone's screen; the wheel goes to the tab before or after.
    private fun barClick(what: String) {
        val hit = barHits.getOrNull(pointerBar) ?: return
        Session.tabs = ""
        when {
            hit.action.startsWith("media:") -> if (what == "click") {
                if (!Media.control(hit.action.removePrefix("media:"))) Session.tabs = "nothing plays"
            }
            hit.action.startsWith("timer:") -> {
                if (what == "right") Timer.reset()
                else if (what == "click") when (hit.action.removePrefix("timer:")) {
                    "minus" -> Timer.add(-60_000L)
                    "plus" -> Timer.add(60_000L)
                    else -> Timer.toggle()
                }
                dash()
            }
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
            cursorX = 5000f
            cursorY = 5000f
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

    private var cursorX = 5000f // 1/10000 of the phone's screen
    private var cursorY = 5000f

    private fun phonePointer(parts: List<String>) {
        when (parts[0]) {
            "toggle" -> if (Session.pointer) pointerOff() else pointerOn()
            "keys" -> if (Session.pointer && Session.pointerKeys) pointerOff() else {
                if (!Session.pointer) pointerOn()
                Session.pointerKeys = true
            }
            "off" -> pointerOff()
            "move" -> {
                if (!Session.pointer) pointerOn()
                // A step: 1/60 of the screen across, 1/45 down (the keyboard's arrows, a cell of the touchpad).
                cursorX = (cursorX + (parts.getOrNull(1)?.toIntOrNull() ?: 0) * 167).coerceIn(0f, 10000f)
                cursorY = (cursorY + (parts.getOrNull(2)?.toIntOrNull() ?: 0) * 222).coerceIn(0f, 10000f)
                showCursor()
            }
            "glide" -> { // glasses pixels (the touchpad)
                if (!Session.pointer) pointerOn()
                cursorBy(parts.getOrNull(1)?.toFloatOrNull() ?: 0f, parts.getOrNull(2)?.toFloatOrNull() ?: 0f)
            }
            "click", "right", "up", "down" -> {
                if (!Session.pointer) pointerOn()
                wakeForInput()
                val b = screenBounds()
                val x = cursorX / 10000f * (b.width() - 1)
                val y = cursorY / 10000f * (b.height() - 1)
                val ok = when (parts[0]) {
                    "click" -> VoxKeys.tap(x, y)
                    "right" -> VoxKeys.tap(x, y, long = true)
                    "up" -> true.also { phoneScroll(-b.height() / 4f) }
                    else -> true.also { phoneScroll(b.height() / 4f) }
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
        cursorX = (x / (b.width() - 1) * 10000).coerceIn(0f, 10000f)
        cursorY = (y / (b.height() - 1) * 10000).coerceIn(0f, 10000f)
        work.post { sendCursor(true) }
        val now = SystemClock.uptimeMillis()
        if (now - mouseAt > 500) { // the hiding after a while, pushed back at most twice a second
            mouseAt = now
            work.post {
                work.removeCallbacks(pointerIdle)
                work.postDelayed(pointerIdle, 10000)
            }
        }
    }

    // The cursor on the phone's picture (the arms draw it: it moves at once, between frames); hidden after a while.
    private fun showCursor() {
        sendCursor(true)
        work.removeCallbacks(pointerIdle)
        work.postDelayed(pointerIdle, 10000)
    }

    private fun hideCursor() = sendCursor(false)

    // To the glasses at most every 15 ms (a quick stroke sends its latest place), again while it waits for a busy
    // link or a transition (for up to 5 s). Greys: the capture sends a frame with it.
    private var cursorOn = false
    private var cursorAt = 0L
    private var cursorTries = 0
    private val cursorFlush = Runnable { sendCursor(cursorOn, retry = true) }
    private fun sendCursor(on: Boolean, retry: Boolean = false) {
        cursorOn = on
        work.removeCallbacks(cursorFlush)
        val now = SystemClock.uptimeMillis()
        if (now - cursorAt < 15) {
            work.postDelayed(cursorFlush, 15 - (now - cursorAt))
            return
        }
        cursorAt = now
        if (!retry) cursorTries = 0
        if (Native.screenPointer(cursorX.toInt(), cursorY.toInt(), on) && cursorTries++ < 200)
            work.postDelayed(cursorFlush, 25)
        if (settings.screenLevels != 2) screen?.poke()
    }

    private fun pointerOn() {
        Session.pointer = true
        Session.pointerKeys = false
        if (onPhone()) {
            cursorX = 5000f
            cursorY = 5000f
            return showCursor()
        }
        if (settings.tmuxMouse) tabs("mouse")
        val g = region()
        ptrX = g[0] / 2f
        ptrY = g[1] / 2f
        showPointer()
    }

    private fun pointerOff() {
        Session.pointer = false
        Session.pointerKeys = false
        work.removeCallbacks(pointerIdle)
        Native.pointerAt(ptrX.toInt(), ptrY.toInt(), false)
        hideCursor()
        barLeave()
    }

    private fun showPointer() {
        Native.pointerAt(ptrX.toInt(), ptrY.toInt(), true)
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

    // Alt+F (and the TV page's button): Firefox by itself on the TV, as the phone's `phone` command does it (the
    // mirror to the Vizio, in this phone's own Linux), in a tab "tv" of the phone's terminal; closing that tab, or
    // stop, ends it.
    private fun tvTab(stop: Boolean = false) {
        val s = settings.servers().firstOrNull { it.host == "127.0.0.1" || it.host == "localhost" }
        if (s == null) {
            note("TV: needs the phone's own terminal (127.0.0.1)")
            return
        }
        val cmd = if (stop) Tabs.tvStop() else Tabs.tv(Tabs.session(s) ?: "claude")
        note(if (stop) "TV: stopping" else "TV: Firefox on the TV")
        Thread {
            val r = runCatching { Bootstrap(vault).runShared(s.host, s.port, s.user, cmd) }
            work.post {
                r.exceptionOrNull()?.let { note("TV: " + Bootstrap.plain(it, s.host, s.port)) }
                Log.i(TAG, "tv tab: " + (r.exceptionOrNull()?.message ?: r.getOrNull()?.trim()?.take(120)))
                if (!stop) fetchTabs()
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
        if (Media.now == null) Media.start(this) // notification access may have come since
        Weather.refresh(settings) { work.post { dash() } } // every 30 minutes
        // The game score while a dashboard shows it (every 50 s around a game, else every 15 minutes).
        if (settings.dashRows > 0 && Dashboard.layout(settings).widgets.any { it.type == Dashboard.SPORTS })
            Sports.refresh(this, settings) { work.post { dash() } }
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
        if (carListener != null) return // Car mode has the microphone
        if (capListener != null) return voiceSay("$MIC live captions have the microphone")
        if (once && Session.voiceMode) return voiceModeSet(false) // the listen gesture (or key) ends voice mode
        if (listener != null || voiceLoading) {
            if (once) stopListening()
            return
        }
        if (!VoiceModel.available) return voiceSay("$MIC voice is not in this build")
        if (!VoiceModel.ready(this)) return voiceSay("$MIC download the speech model first: Vox, Voice tab")
        if (Session.glasses != "ready") return voiceSay("$MIC the glasses are not connected")
        voiceLoading = true
        if (Session.voiceMode) voiceStrip(if (voiceEngine == null) "loading the speech model…" else "listening")
        else voiceSay(if (voiceEngine == null) "$MIC loading the speech model…" else listeningText(once), hold = true)
        Thread {
            val engine = voiceEngine ?: runCatching { Parakeet.use(this, "glasses") }.getOrElse { e ->
                work.post {
                    voiceLoading = false
                    voiceFailedAt = SystemClock.elapsedRealtime()
                    voiceSay("$MIC the speech model did not load: ${e.message}")
                    if (Session.voiceMode) voiceModeSet(false, "the speech model did not load")
                }
                return@Thread
            }
            voiceEngine = engine
            work.post {
                voiceLoading = false
                if (ended || listener != null) return@post
                lateinit var l: VoiceListener
                l = VoiceListener(engine, settings.voiceRealtime, once,
                    onPartial = { t ->
                        work.post {
                            if (listener !== l) return@post
                            if (Session.voiceMode) voiceStrip("$t…") else voiceSay("$MIC $t…", hold = true)
                        }
                    },
                    onPhrase = { t -> work.post { if (listener === l) heard(t, once) } },
                    onEnd = { why -> work.post { listenerEnded(l, why) } })
                listener = l
                Session.listening = true
                if (Session.voiceMode) voiceStrip("listening: talk, then say \"send\"")
                else voiceSay(listeningText(once), hold = true)
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
        if (Session.voiceMode) { // voice mode keeps the microphone on (its strip, not the band); a failure ends it
            if (why.isNotEmpty()) voiceModeSet(false, why)
            else work.postDelayed({ if (Session.voiceMode && listener == null && !voiceLoading) listen(false) }, 300)
            Session.changed()
            return
        }
        if (why.isNotEmpty()) {
            if (!l.once) voiceFailedAt = SystemClock.elapsedRealtime()
            voiceSay("$MIC $why")
        } else if (Session.voice.endsWith("…")) voiceSay(Session.voice.removeSuffix("…"))
        else work.postDelayed(bandOff, settings.voiceBandSecs.coerceIn(1, 30) * 1000L)
        Session.changed()
        if (!l.once) work.postDelayed({ voiceAlways() }, 1000)
    }

    // ------------------------------------------------ voice mode: talking to the terminal

    // Alt+V (the Voice tab, a TouchBar gesture; Alt+V again, "voice off" or the listen gesture end it): the
    // microphone stays on and what is said is typed into the terminal, phrase by phrase (Claude's prompt, a
    // shell), with no "Vox" first. A strip at the bottom says it is on and shows the phrase being said, and the
    // terminal stays in view meanwhile. Said by itself: "send" presses Enter, "scratch that" takes the last phrase
    // back, "clear" empties the line (Ctrl+U), "escape" stops Claude, "option one / two / three" answers its
    // questions, "voice off" ends it. A phrase that ends in "send it" is typed, then sent.
    private var voiceStripText = ""
    private var lastPhrase = "" // the last phrase typed (scratch that)

    private fun voiceModeSet(on: Boolean, why: String = "") {
        if (on == Session.voiceMode) return
        if (on) {
            val no = when {
                carListener != null -> "Car mode has the microphone"
                !VoiceModel.available -> "voice is not in this build"
                !VoiceModel.ready(this) -> "download the speech model first: Vox, Voice tab"
                Session.glasses != "ready" -> "the glasses are not connected"
                else -> null
            }
            if (no != null) {
                voiceSay("$MIC $no")
                return
            }
            Session.voiceMode = true
            lastPhrase = ""
            Log.i(TAG, "voice mode: on")
            // The terminal in view (a screen gives way to the terminal in use), the display on.
            if (settings.show != Settings.TERMINAL || !chosen().startsWith("term:")) settings.active()?.let { choose("term:${it.id}", Fx.UP) }
            if (Session.blank) display(true, "voice mode")
            voiceStrip(if (voiceEngine == null) "loading the speech model…" else "listening: talk, then say \"send\"")
            if (listener == null) listen(false)
            else if (listener?.once == true) listener?.stop() // its end starts one that stays on
        } else {
            Session.voiceMode = false
            Log.i(TAG, "voice mode: off" + if (why.isNotEmpty()) " ($why)" else "")
            if (!settings.voiceAlways) listener?.stop()
            if (Session.overlay == Panels.VOICE) panelClose()
            note(if (why.isEmpty()) "Voice mode off" else "Voice mode off: $why")
        }
        Session.changed()
    }

    // The strip's words (and the app's Voice tab). A panel open over it (the help, a note) keeps the glasses: the
    // strip comes back when it closes (panelClose).
    private fun voiceStrip(text: String) {
        voiceStripText = text
        Session.voice = "Voice mode: $text"
        if (Session.voiceMode && !Session.tvKeys && (Session.overlay.isEmpty() || Session.overlay == Panels.VOICE)) {
            if (Session.overlay != Panels.VOICE) {
                Session.overlay = Panels.VOICE
                forceApply()
            }
            panelDraw()
        }
        Session.changed()
    }

    private fun voiceModePhrase(text: String) {
        val words = VoiceCommands.normalize(text)
        when (words) {
            "voice off", "voice mode off", "stop voice", "stop voice mode", "end voice mode", "stop listening" ->
                return voiceModeSet(false)
            "send", "send it", "enter", "submit", "press enter" -> {
                key("enter")
                lastPhrase = ""
                return voiceStrip("sent")
            }
            "scratch that", "delete that", "undo that" -> {
                repeat(lastPhrase.codePointCount(0, lastPhrase.length)) { key("backspace") }
                lastPhrase = ""
                return voiceStrip("took the last phrase back")
            }
            "clear", "clear line", "clear that", "clear it" -> {
                Native.keyEvent(1, 0x01, 'u'.code)
                lastPhrase = ""
                return voiceStrip("line cleared")
            }
            "escape", "press escape", "stop claude", "interrupt" -> {
                key("escape")
                return voiceStrip("escape")
            }
            "option one", "option 1" -> return type("1").also { voiceStrip("option 1") }
            "option two", "option 2" -> return type("2").also { voiceStrip("option 2") }
            "option three", "option 3" -> return type("3").also { voiceStrip("option 3") }
        }
        val end = SEND_AT_END.find(text)
        val body = (if (end != null) text.substring(0, end.range.first) else text).trim()
        if (body.isNotEmpty()) {
            lastPhrase = "$body "
            type(lastPhrase)
        }
        if (end != null) {
            key("enter")
            lastPhrase = ""
        }
        voiceStrip((if (end != null) "sent: " else "typed: ") + body)
    }

    // Always listening: on while the glasses are up and the setting is on (not again within 30 s of a failure).
    private fun voiceAlways() {
        if (!settings.voiceAlways || listener != null || carListener != null || voiceLoading || ended || Session.glasses != "ready") return
        if (Session.captions.isNotEmpty()) return // live captions have the microphone
        if (!VoiceModel.available || !VoiceModel.ready(this)) return
        if (SystemClock.elapsedRealtime() - voiceFailedAt < 30000) return
        listen(false)
    }

    // ------------------------------------------------ car mode (Car.kt)

    private var car: CarMode? = null
    private var carListener: VoiceListener? = null
    private var carBlanked = false // Car mode turned the glasses' display off
    private val carDevices = HashMap<String, String>() // a car's Bluetooth address: its name
    private val carGone = HashMap<String, Runnable>() // a car whose link dropped: its end of Car mode, if it stays gone

    private val carHost = object : CarHost {
        override val handler get() = work
        override fun carListen(onLive: () -> Unit, onPartial: (String) -> Unit, onPhrase: (String) -> Unit,
                               onSpeaking: (Boolean) -> Unit, onEnd: (String) -> Unit) =
            carMic(onLive, onPartial, onPhrase, onSpeaking, onEnd, 0)
        override fun carPreload() {
            if (voiceEngine != null || voiceLoading || !VoiceModel.available || !VoiceModel.ready(this@ScouterService)) return
            voiceLoading = true
            Thread {
                val e = runCatching { Parakeet.use(this@ScouterService, "glasses") }.getOrNull()
                work.post {
                    if (voiceEngine == null) voiceEngine = e
                    voiceLoading = false
                }
            }.start()
        }
        override fun carTmux(server: Settings.Server) = Tabs.session(server) ?: "claude"
        override fun carStopListening() {
            carListener?.stop()
        }
        // Not on the glasses while driving: the app's Voice tab shows it.
        override fun carBand(text: String, hold: Boolean) {
            Session.voice = text
            Session.changed()
        }
        override fun carServer(): Settings.Server? {
            val list = settings.servers()
            return list.firstOrNull { it.id == settings.carServer } ?: list.firstOrNull { it.local } ?: settings.active()
        }
        override fun carVault() = vault
        override fun carChanged() {
            Session.car = car?.state?.name?.lowercase() ?: ""
            Session.changed()
        }
    }

    // The glasses' microphone for Car mode: always listening gives it up first (its microphone let go before this
    // one starts: tries again every 100 ms for up to 2 s).
    private fun carMic(onLive: () -> Unit, onPartial: (String) -> Unit, onPhrase: (String) -> Unit,
                       onSpeaking: (Boolean) -> Unit, onEnd: (String) -> Unit, tries: Int) {
        if (!VoiceModel.available) return onEnd("This Vox has no speech engine")
        if (!VoiceModel.ready(this)) return onEnd("Download the speech model in Vox, on the Voice tab, first")
        val phone = settings.carMic != "glasses"
        if (!phone && Session.glasses != "ready") return onEnd("Connect the glasses to talk to Claude")
        if (carListener != null) return onEnd("")
        if (listener != null || voiceLoading) {
            listener?.stop()
            if (tries >= 100) return onEnd("The microphone is busy") // the model loading ahead: up to 10 s
            work.postDelayed({ carMic(onLive, onPartial, onPhrase, onSpeaking, onEnd, tries + 1) }, 100)
            return
        }
        voiceLoading = true
        Thread {
            val engine = voiceEngine ?: runCatching { Parakeet.use(this, "glasses") }.getOrElse { e ->
                work.post {
                    voiceLoading = false
                    onEnd("The speech model did not load: ${e.message}")
                }
                return@Thread
            }
            voiceEngine = engine
            work.post {
                voiceLoading = false
                if (ended) return@post onEnd("")
                lateinit var l: VoiceListener
                l = VoiceListener(engine, settings.voiceRealtime, false, onPartial = onPartial, onPhrase = onPhrase,
                    onEnd = { why ->
                        work.post {
                            if (carListener === l) carListener = null
                            Session.listening = listener != null
                            Session.changed()
                            onEnd(why)
                            work.postDelayed({ voiceAlways() }, 1500)
                        }
                    },
                    onSpeaking = onSpeaking, flushOnStop = true, onLive = onLive,
                    mic = if (phone) HubMic(this@ScouterService, "car") else GlassesMic)
                carListener = l
                Session.listening = true
                Session.changed()
                l.start()
            }
        }.start()
    }

    // A car's audio connected or gone (Bluetooth class car audio or hands-free).
    private val carLink = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            val name = runCatching { d.name }.getOrNull() ?: "the car"
            Log.i(TAG, "bt: $name ${i.action?.substringAfterLast('.')}, class " +
                Integer.toHexString(runCatching { d.bluetoothClass?.deviceClass ?: 0 }.getOrDefault(0)))
            if (!CarMode.isCar(d)) return
            val address = d.address
            work.post {
                if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    carGone.remove(address)?.let { work.removeCallbacks(it) } // back within the grace: nothing changed
                    val known = address in carDevices
                    carDevices[address] = name
                    Log.i(TAG, "car: connected ($name)${if (known) ", again" else ""}")
                    if (!known) carCheck()
                } else if (address in carDevices && address !in carGone) {
                    // A car's link drops for a moment now and then: Car mode (and the glasses kept dark) ends only
                    // when it stays gone, so a blip doesn't light the glasses and darken them again seconds later.
                    val gone = Runnable {
                        carGone.remove(address)
                        carDevices.remove(address)
                        Log.i(TAG, "car: gone ($name)")
                        carCheck()
                    }
                    carGone[address] = gone
                    work.postDelayed(gone, CAR_GONE_MS)
                    Log.i(TAG, "car: link dropped ($name), Car mode stays ${CAR_GONE_MS / 1000} s")
                }
            }
        }
    }

    // A car connected already when Vox starts: its audio profiles' devices.
    private fun carScan() {
        val bt = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)) runCatching {
            bt.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                    val found = runCatching {
                        proxy.connectedDevices.filter { CarMode.isCar(it) }.map { it.address to (runCatching { it.name }.getOrNull() ?: "the car") }
                    }.getOrDefault(emptyList())
                    runCatching { bt.closeProfileProxy(p, proxy) }
                    work.post {
                        found.forEach { (a, n) -> carDevices[a] = n }
                        carCheck()
                    }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
        work.post { carCheck() }
    }

    // On while a car is connected (Settings.carMode "auto"), or always ("on"), or never.
    private fun carCheck() {
        if (ended) return
        val want = when (settings.carMode) {
            "on" -> true
            "off" -> false
            else -> carDevices.isNotEmpty()
        }
        Session.carName = carDevices.values.firstOrNull() ?: ""
        if (want && car == null) {
            Log.i(TAG, "car mode on")
            val greet = settings.carGreets < 3
            if (greet) settings.carGreets = settings.carGreets + 1
            car = CarMode(this, carHost).also { it.start(greet) }
            // Driving: the glasses go dark (back on when Car mode ends, if it was Car mode that turned them off).
            carBlanked = !Session.blank
            display(false, "car mode")
        } else if (!want && car != null) {
            Log.i(TAG, "car mode off")
            car?.release()
            car = null
            carListener?.stop()
            if (carBlanked) display(true, "car mode off")
            carBlanked = false
        }
        carHost.carChanged()
    }

    // The Voice tab's test: the chimes and a line in the car's voice (without Car mode on too).
    private fun carTest() {
        car?.let { return it.test() }
        CarSounds.play(CarSounds.Sound.LISTEN)
        work.postDelayed({ CarSounds.play(CarSounds.Sound.SEND) }, 700)
        val speech = CarSpeech(this) {}
        work.postDelayed({ speech.speak("Hi, this is how Claude sounds in the car.", "test") }, 1500)
        work.postDelayed({ speech.release() }, 15000)
    }

    // A phrase: a command (always listening: only after "Vox"), or, while dictating, text for the terminal.
    private fun heard(text: String, once: Boolean) {
        Session.heard = (listOf(text) + Session.heard).take(8)
        if (Session.replyKey != null && Session.replyStatus.isEmpty()) { // said into a quick reply
            val sep = if (Session.replyDraft.isEmpty() || Session.replyDraft.endsWith(" ")) "" else " "
            return reply("c:$sep${text.trim()}")
        }
        if (Session.people && peopleMode == "say") return peopleHeard(text)
        if (Session.voiceMode) return voiceModePhrase(text)
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
            VoiceCommands.MEDIA -> if (!Media.control(m.arg)) voiceSay("Nothing is playing (Vox needs notification access)")
            VoiceCommands.LISTEN -> {
                when (m.arg) {
                    "off" -> ListenService.stop(this)
                    "pause" -> Listen.pause(15 * 60_000L)
                    else -> ListenService.start(this)
                }
                voiceSay("$MIC listen mode ${m.arg}")
            }
            VoiceCommands.TUNES -> tunesSet(if (m.arg == "off") false else if (m.arg == "on") true else !Session.tunes)
            VoiceCommands.TIMER -> {
                when (m.arg) {
                    "toggle" -> Timer.toggle()
                    "reset" -> Timer.reset()
                    else -> Timer.set((m.arg.toLongOrNull() ?: 300L) * 1000)
                }
                dash()
            }
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
        lookSwitch() // first: the first picture goes with its look
        if (show == Settings.SCREEN) {
            val error = Native.screenStart()
            if (error != null) {
                // The glasses keep the terminal's path: say so, and keep the sharing for another try.
                Log.w(TAG, "phone screen: $error")
                settings.show = Settings.TERMINAL
                update(screen = "error: $error")
                return refresh()
            }
            screen?.poke() // its picture now, not at the capture's next tick
        } else Native.screenStop()
        if (blankByLock) blankByLock = false
        update()
        refresh()
        if (show == Settings.TERMINAL) header()
        // A panel open stays: drawn on what shows now (the quick settings of this screen).
        if (Session.overlay == Panels.SETTINGS) tweaks = tweaks().also { panelAt = panelAt.coerceIn(0, maxOf(0, it.size - 1)) }
        if (Session.overlay.isNotEmpty()) panelDraw()
    }

    // Each second: a timer that ends says so; the dashboard again while what it shows moves (what plays, the timer).
    private val mediaTick = object : Runnable {
        override fun run() {
            work.postDelayed(this, 1000)
            if (Timer.ended()) {
                alert("Time's up", "the timer ended")
                dash()
            }
            for (r in Hub.due(this@ScouterService)) alert("Reminder", r.text)
            if (settings.dashRows == 0 || !Session.shown || settings.show != Settings.TERMINAL) return
            val types = Dashboard.layout(settings).widgets.map { it.type }
            if ((Media.now?.playing == true && Dashboard.MEDIA in types) || (Timer.running && Dashboard.TIMER in types)) dash()
        }
    }

    // ------------------------------------------------ tunes

    // The synthwave scene on the glasses (the arms play it; the music's pulse goes to them each tick), or what was
    // there again.
    private fun tunesSet(on: Boolean) {
        if (on == Session.tunes) return
        if (on) {
            if (glassesWith == null) return
            Native.tunes(true)?.let {
                Log.w(TAG, "tunes: $it")
                Session.gesture = "tunes: $it"
                Session.changed()
                return
            }
            if (Session.pointer) pointerOff()
            panelClose()
            Session.tunes = true
            Tunes.start(this, settings.tunesMode)
            Log.i(TAG, "tunes: on (${settings.tunesMode})")
        } else {
            Session.tunes = false
            Tunes.stop()
            Native.tunes(false)
            Thread { Native.brightness(settings.brightness) }.start() // the scene breathed with it
            header()
            Log.i(TAG, "tunes: off")
        }
        update()
    }

    // Each 60 ms: the music's pulse to the scene; the Tunes bar on the dashboard followed (and drawn again about five
    // times a second) while it shows.
    private var tunesBar = false
    private var tunesFrames = 0
    private val tunesTick = object : Runnable {
        override fun run() {
            work.postDelayed(this, 60)
            val bar = !Session.tunes && settings.dashRows > 0 && Session.shown && settings.show == Settings.TERMINAL &&
                Dashboard.layout(settings).widgets.any { it.type == Dashboard.TUNES }
            if (bar != tunesBar) {
                tunesBar = bar
                if (bar) Tunes.start(this@ScouterService, settings.tunesMode) else Tunes.stop()
            }
            if (!Session.tunes && !bar) return
            val (pulse, speed) = Tunes.tick(settings.tunesMode, settings.tunesBpm)
            if (Session.tunes) Native.tunesBeat(pulse, speed)
            if (bar && ++tunesFrames % 3 == 0) dash()
        }
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
        // Car mode (driving): the glasses stay dark; only the user turning them on (the app, a hotkey, voice, a
        // TouchBar action) lights them, never a panel or the phone waking.
        if (on && car != null && (why == "panel" || why == "phone on")) return
        if (!on) panelClose()
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
            Settings.ACTION_VOICE_MODE -> voiceModeSet(!Session.voiceMode)
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
        @Volatile private var crashWatch = false
        const val TAG = "scouter"
        const val PARK_MS = 5 * 60_000L // a server's screen kept connected while another source shows
        const val CHANNEL = "session"
        const val NOTIFICATION = 1
        // The X11 window in front on a server (its WM_CLASS line), for the look per window.
        const val WINDOW_CMD = "export DISPLAY=\"\${DISPLAY:-:0}\"; U=\$(id -u); if [ -z \"\$XAUTHORITY\" ] && " +
            "[ -f \"/run/user/\$U/gdm/Xauthority\" ]; then export XAUTHORITY=\"/run/user/\$U/gdm/Xauthority\"; fi; " +
            "xprop -id \"\$(xprop -root _NET_ACTIVE_WINDOW | sed 's/.*# //')\" WM_CLASS"
        const val ACTION_STOP = "scouterkit.ssh.STOP"
        const val ACTION_KEYS = "scouterkit.ssh.KEYS" // the keyboard to the phone's apps or the glasses' terminal (Alt+K)
        const val ACTION_TUNES = "scouterkit.ssh.TUNES" // the synthwave scene on the glasses, or off (extra "on")
        const val ACTION_PANEL = "scouterkit.ssh.PANEL" // a hotkey's panel or action (extra "do": hotkey())
        const val ACTION_TRUST = "scouterkit.ssh.TRUST"
        const val ACTION_NEAR = "scouterkit.ssh.NEAR" // keyer came or went (Session.near)
        const val ACTION_SCREEN = "scouterkit.ssh.SCREEN" // the phone's screen on the glasses (EXTRA_CODE, EXTRA_DATA)
        const val ACTION_TERMINAL = "scouterkit.ssh.TERMINAL" // the terminal on the glasses, started from the phone
        const val ACTION_VIEW_SCREEN = "scouterkit.ssh.VIEW_SCREEN" // the phone's screen again, while it is shared
        const val ACTION_STOP_SHARING = "scouterkit.ssh.STOP_SHARING" // screen sharing ends
        const val LOW_BRIGHTNESS = 4 // a tenth of 42
        const val TV_IDLE_MS = 5 * 60_000L
        const val ALERTS = "alerts" // the notification channel of timers and reminders
        // What Whisper says to silence and noise (dropped from translations).
        private val WHISPER_NOISE = setOf("you", "thank you", "thanks for watching", "thank you for watching", "bye",
            "thank you very much", "please subscribe", "subtitles by the amaraorg community", "music")
        const val ALERT_ID = 4000
        const val SLIDE_IDLE_MS = 90L // a two-finger swipe ends when its wheels are still this long
        const val SLIDE_SPEED_MS = 80f // its speed at the end: over its last moves this long
        const val CAR_GONE_MS = 20_000L
        // Voice mode: a phrase that ends in "send it" (or "send that") is sent after it is typed.
        private val SEND_AT_END = Regex("[\\s,.;:!?]+send (it|that)[\\s.!?]*\$", RegexOption.IGNORE_CASE)
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

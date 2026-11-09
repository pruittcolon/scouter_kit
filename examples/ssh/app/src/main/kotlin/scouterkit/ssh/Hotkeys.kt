package scouterkit.ssh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

// A keyboard's shortcuts (a Bluetooth keyboard paired with the phone): Alt with an arrow moves around the
// sources as a grid (left and right: this phone, then each other server; up and down: terminal, screen),
// Alt+1 ... Alt+9 picks Home's source of that number, Alt+0 turns the display off or on, Alt+Space (or Alt+V)
// listens for a voice command, Alt+M the pointer (then arrows, Enter, Escape), Alt+T / [ / ] / W the tabs,
// Alt+A the help on the glasses (every hotkey: Panels.GROUPS; also while Alt is held by itself), Alt+S the quick
// settings, Alt+Z always on / look-up only (Alt+L too), Alt+= / - the brightness, Alt+P / , / . the music, Alt+X
// the keyboard off / on, Alt+C the phone's screen (shared first, Android's question answered by VoxKeys). MainActivity takes them while Vox is in front; VoxKeys (an
// accessibility service the user turns on) everywhere.
object Hotkeys {
    // The event was Vox's: a shortcut, or a key typed into the glasses' terminal (both its press and its release are
    // ours then).
    fun handle(context: Context, e: KeyEvent): Boolean {
        if (keyboardSwitch(context, e)) return true
        if (shortcut(context, e)) return true
        return toGlasses(context, e) && Keyboard.send(e)
    }

    // The glasses' source is the terminal: a keyboard's keys are its, whatever app is in front on the phone and
    // wherever the head points (looking down at the keys hides it for a moment). Alt+K sends them to the phone again
    // until it is pressed again; the phone's screen on the glasses takes them as always.
    fun toGlasses(context: Context, e: KeyEvent): Boolean {
        if (!Keyboard.physical(e) || Session.keysToPhone || !ScouterService.running) return false
        if (Session.glasses != "ready" || Session.blank || Session.showing != Settings.TERMINAL || Session.tunes) return false
        return Settings(context).keysToGlasses
    }

    // Alt held by itself: the hotkeys pop up on the glasses (after ALT_HOLD_MS, so a quick Alt+key doesn't flash
    // them) and go when it is let go; a key pressed with it takes them away and does its own thing.
    private const val ALT_HOLD_MS = 300L
    private val main = Handler(Looper.getMainLooper())
    private var altShown = false
    private var altApp: Context? = null
    private val altShow = Runnable {
        val c = altApp ?: return@Runnable
        altShown = true
        ScouterService.send(c, ScouterService.ACTION_PANEL, "do" to "althold:on")
    }

    private fun altGone(context: Context) {
        main.removeCallbacks(altShow)
        if (altShown) {
            altShown = false
            ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to "althold:off")
        }
    }

    // Alt+X: the keyboard off (its keys do nothing anywhere: in a bag, on the table) or on again. Only Alt+X gets
    // through meanwhile.
    private fun keyboardSwitch(context: Context, e: KeyEvent): Boolean {
        if (!Keyboard.physical(e) || !Settings(context).hotkeys) return false
        if (e.keyCode == KeyEvent.KEYCODE_X && e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                altGone(context)
                Session.keysOff = !Session.keysOff
                android.util.Log.i(ScouterService.TAG, "keyboard " + if (Session.keysOff) "off" else "on")
                if (ScouterService.running)
                    ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to if (Session.keysOff) "keys:off" else "keys:on")
                Session.changed()
            }
            return true
        }
        if (!Session.keysOff) return false
        altGone(context)
        return true
    }

    private fun altHold(context: Context, e: KeyEvent) {
        val alt = e.keyCode == KeyEvent.KEYCODE_ALT_LEFT || e.keyCode == KeyEvent.KEYCODE_ALT_RIGHT
        if (alt && e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0 && !altShown) {
                altApp = context.applicationContext
                main.removeCallbacks(altShow)
                if (!e.isCtrlPressed && !e.isMetaPressed && !e.isShiftPressed) main.postDelayed(altShow, ALT_HOLD_MS)
            }
            return
        }
        if (alt || e.action == KeyEvent.ACTION_DOWN) altGone(context) // Alt let go, or another key with it
    }

    private fun shortcut(context: Context, e: KeyEvent): Boolean {
        val settings = Settings(context)
        if (!settings.hotkeys) return false
        altHold(context, e)
        // A quick reply being written on the dashboard: every key is its (Alt+Space / Alt+V still listen).
        if (Session.replyKey != null && !(e.isAltPressed && (e.keyCode == KeyEvent.KEYCODE_SPACE || e.keyCode == KeyEvent.KEYCODE_V))) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            val what = when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "send"
                KeyEvent.KEYCODE_ESCAPE -> "cancel"
                KeyEvent.KEYCODE_DEL -> "del"
                else -> e.getUnicodeChar(e.metaState).takeIf { it > 0 && !e.isCtrlPressed }?.let { "c:" + String(Character.toChars(it)) }
            } ?: return true
            ScouterService.send(context, ScouterService.ACTION_REPLY, "key" to what)
            return true
        }
        // The TV remote on (Alt+Y): the keyboard is the TV's remote (Vizio.LAYOUT); Alt shortcuts go on as ever.
        if (Session.tvKeys && !e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
            val k = Vizio.forKey(e.keyCode, e.isShiftPressed) ?: return true
            val again = k.startsWith("VOL") || k.startsWith("CH") || k in listOf("UP", "DOWN", "LEFT", "RIGHT")
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || again))
                ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to "tv:$k")
            return true
        }
        // A panel on the glasses (the help, the quick settings): its keys are the arrows, Enter, Space and Escape
        // (the rest do nothing meanwhile); Alt shortcuts go on as ever (Alt+A / Alt+S again close it).
        if ((Session.overlay == Panels.HELP || Session.overlay == Panels.SETTINGS) && !e.isAltPressed && !e.isCtrlPressed &&
            !e.isMetaPressed) {
            val k = when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
                KeyEvent.KEYCODE_SPACE -> "space"
                KeyEvent.KEYCODE_ESCAPE -> "esc"
                else -> null
            }
            if (k != null && e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || k in listOf("up", "down", "left", "right")))
                ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to "key:$k")
            return true
        }
        // The phone's screen on the glasses, the pointer on: typing goes to the phone's app as it is (Space, and
        // Enter once something was typed); the arrows move the cursor again.
        val onPhone = Session.showing == Settings.SCREEN && Session.screenSource.ifEmpty { Settings.PHONE } == Settings.PHONE
        if (Session.pointer && onPhone && !e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    Session.typing = false
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (Session.typing) return false
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_ESCAPE -> {}
                else -> {
                    if (e.action == KeyEvent.ACTION_DOWN && (e.getUnicodeChar(e.metaState) > 0 || e.keyCode == KeyEvent.KEYCODE_DEL))
                        Session.typing = true
                    return false
                }
            }
        }
        // The pointer's keys while Alt+M put it on (no Alt): arrows move it (Shift: by 5), Enter or Space clicks
        // (Shift+Enter: right click), Page Up / Down scroll, Escape ends it. On by the trackpad, the keys type.
        if (Session.pointer && Session.pointerKeys && !e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
            val step = if (e.isShiftPressed) 5 else 1
            val cmd = when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "move:${-step}:0"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "move:$step:0"
                KeyEvent.KEYCODE_DPAD_UP -> "move:0:${-step}"
                KeyEvent.KEYCODE_DPAD_DOWN -> "move:0:$step"
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (e.isShiftPressed) "right" else "click"
                KeyEvent.KEYCODE_SPACE -> "click"
                KeyEvent.KEYCODE_PAGE_UP -> "up"
                KeyEvent.KEYCODE_PAGE_DOWN -> "down"
                KeyEvent.KEYCODE_ESCAPE -> "off"
                else -> return false
            }
            val repeatable = cmd.startsWith("move") || cmd == "up" || cmd == "down"
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || repeatable))
                ScouterService.send(context, ScouterService.ACTION_POINTER, "cmd" to cmd)
            return true
        }
        // Ctrl+Alt+Left / Right: the tab before / after.
        if (e.isAltPressed && e.isCtrlPressed && !e.isMetaPressed &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
                ScouterService.send(context, ScouterService.ACTION_TABS, "tab" to if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) "prev" else "next")
            return true
        }
        // Ctrl+Left, with a screen on the glasses (the phone's or a server's): back to the SSH terminal. In the
        // terminal itself it is the word before, as ever.
        if (e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed && !e.isShiftPressed &&
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && Session.showing == Settings.SCREEN) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && settings.active() != null)
                ScouterService.send(context, ScouterService.ACTION_SOURCE,
                    Shortcuts.EXTRA to "term:${settings.active()?.id}", "fx" to "left")
            return true
        }
        if (!e.isAltPressed || e.isCtrlPressed || e.isMetaPressed) return false
        // The phone's screen on the glasses: Alt+Backspace is Back, Alt+H Home, Alt+R Recents, Alt+N the
        // notifications (VoxKeys).
        val nav = when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_H -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_R -> AccessibilityService.GLOBAL_ACTION_RECENTS
            KeyEvent.KEYCODE_N -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> null
        }
        if (nav != null && onPhone) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) VoxKeys.global(nav)
            return true
        }
        // Alt+A the help, Alt+S the quick settings, Alt+Z (or L) always on / look-up only, Alt+= / - brighter /
        // dimmer, Alt+P play or pause, Alt+, / . the track before / after.
        val panel = when (e.keyCode) {
            KeyEvent.KEYCODE_A -> Panels.HELP
            KeyEvent.KEYCODE_S -> Panels.SETTINGS
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_L -> "lookup"
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "bright:1"
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "bright:-1"
            KeyEvent.KEYCODE_P -> "media:toggle"
            KeyEvent.KEYCODE_B -> "lowbat"
            KeyEvent.KEYCODE_Y -> "tv"
            KeyEvent.KEYCODE_D -> "demo"
            KeyEvent.KEYCODE_COMMA -> "media:prev"
            KeyEvent.KEYCODE_PERIOD -> "media:next"
            else -> null
        }
        if (panel != null) {
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || panel.startsWith("bright")))
                ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to panel)
            return true
        }
        // Alt+C: the phone's screen on the glasses, shared first if it is not (VoxKeys taps Android's Start).
        if (e.keyCode == KeyEvent.KEYCODE_C) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (ScouterService.running) ScouterService.send(context, ScouterService.ACTION_PANEL, "do" to "share")
                else context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHARE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }
        // Alt+U: Tunes, the synthwave scene, on or off.
        if (e.keyCode == KeyEvent.KEYCODE_U) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) ScouterService.send(context, ScouterService.ACTION_TUNES)
            return true
        }
        // Alt+K: the keyboard to the phone's apps, or back to the glasses' terminal.
        if (e.keyCode == KeyEvent.KEYCODE_K) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) ScouterService.send(context, ScouterService.ACTION_KEYS)
            return true
        }
        // Alt+Q closes the source shown (a terminal's connection, a screen's sharing).
        if (e.keyCode == KeyEvent.KEYCODE_Q) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) ScouterService.send(context, ScouterService.ACTION_CLOSE)
            return true
        }
        // Alt+M the pointer; Alt+T a new tab, Alt+[ / ] the tab before / after, Alt+W closes the tab shown.
        val tab = when (e.keyCode) {
            KeyEvent.KEYCODE_T -> "new"
            KeyEvent.KEYCODE_LEFT_BRACKET -> "prev"
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "next"
            KeyEvent.KEYCODE_W -> "close"
            else -> null
        }
        if (tab != null || e.keyCode == KeyEvent.KEYCODE_M) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (tab != null) ScouterService.send(context, ScouterService.ACTION_TABS, "tab" to tab)
                else ScouterService.send(context, ScouterService.ACTION_POINTER, "cmd" to "keys")
            }
            return true
        }
        val target = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_0 -> Shortcuts.DISPLAY
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_V -> LISTEN
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> (e.keyCode - KeyEvent.KEYCODE_0).toString()
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) run(context, settings, target)
        return true
    }

    private fun run(context: Context, settings: Settings, target: String) {
        if (target == Shortcuts.DISPLAY) return ScouterService.send(context, ScouterService.ACTION_DISPLAY)
        if (target == LISTEN) return ScouterService.send(context, ScouterService.ACTION_VOICE, "mode" to "listen")
        val key = when (target) {
            "left", "right", "up", "down" -> settings.move(current(settings), target)
            else -> settings.sources().getOrNull(target.toInt() - 1)?.first
        } ?: return
        if (key == Settings.PHONE && !(Session.capturing && ScouterService.running)) {
            // Android asks for the phone's screen in the app.
            context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHARE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (settings.left.isNotEmpty() && settings.right.isNotEmpty()) settings.glassesAllowed = true
        // An arrow: its own transition on the glasses (Fx), else a jump.
        ScouterService.send(context, ScouterService.ACTION_SOURCE, Shortcuts.EXTRA to key, "fx" to target)
    }

    // The source on the glasses now (or last).
    private fun current(settings: Settings): String = when (Session.showing) {
        Settings.SCREEN -> Session.screenSource.ifEmpty { Settings.PHONE }
        Settings.TERMINAL -> "term:${settings.active()?.id}"
        else -> settings.source.ifEmpty { "term:${settings.active()?.id}" }
    }

    private const val LISTEN = "listen" // Alt+Space or Alt+V: voice

    // Whether VoxKeys is turned on in Android's accessibility settings.
    fun everywhere(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, VoxKeys::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

// The keyboard's shortcuts in every app: an accessibility service that only filters key events (it reads no
// screen content); every other key goes on as it came.
// It also taps the phone where the glasses' cursor is (Android's accessibility gestures), and Back and Home.
// A mouse or a keyboard's trackpad: watched (Android's pointer as always, the glasses draw where it is), or taken
// for the glasses' own pointer while they show a terminal (capture).
class VoxKeys : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        captured = false
        watchMouse(wantCapture)
    }

    // Android 14+: the mouse's events taken (they reach no app meanwhile: the glasses' pointer or cursor uses them,
    // its clicks become taps), or left to the phone.
    private fun watchMouse(take: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val info = serviceInfo ?: return
        try {
            info.motionEventSources = if (take) InputDevice.SOURCE_MOUSE else 0
            serviceInfo = info
            captured = take
            seen = false
            android.util.Log.i(ScouterService.TAG, "trackpad: " + if (take) "the glasses'" else "the phone's")
        } catch (e: Throwable) {
            android.util.Log.w(ScouterService.TAG, "mouse: ${e.message}")
            captured = false
            runCatching {
                info.motionEventSources = 0
                serviceInfo = info
            }
        }
    }

    private var seen = false // the first taken event, logged

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var sawButton = false // the device sends BUTTON_PRESS (else DOWN / UP are its clicks)

    override fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        if (!captured) {
            onMouse?.invoke(event.x, event.y)
            return
        }
        val pad = onTrackpad ?: return
        if (!seen) {
            seen = true
            android.util.Log.i(ScouterService.TAG, "trackpad: events coming (${event.device?.name})")
        }
        // How far it moved: the device's own relative motion (unclamped), else from Android's pointer.
        var dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        var dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx == 0f && dy == 0f && !lastX.isNaN()) {
            dx = event.x - lastX
            dy = event.y - lastY
        }
        lastX = event.x
        lastY = event.y
        val twoFinger = android.os.Build.VERSION.SDK_INT >= 34 &&
            event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
        when {
            twoFinger -> pad(Trackpad.SCROLL, event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE))
            event.actionMasked == MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) pad(Trackpad.WHEEL, 0f, v)
            }
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS -> {
                sawButton = true
                pad(when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> Trackpad.RIGHT
                    else -> Trackpad.CLICK
                }, 0f, 0f)
            }
            event.actionMasked == MotionEvent.ACTION_DOWN && !sawButton ->
                pad(if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) Trackpad.RIGHT else Trackpad.CLICK, 0f, 0f)
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE ->
                if (dx != 0f || dy != 0f) pad(Trackpad.MOVE, dx, dy)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = Hotkeys.handle(this, event)

    // Android's question to share the screen, asked by Vox itself (MainActivity.shareScreen): its Start button
    // tapped for the user. The dialog can take a moment to come: looked for at each window change and every 200 ms
    // for up to 15 s. Only the system UI's dialog that names this app, and only its start / share button.
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var confirmUntil = 0L
    private val confirmTick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (android.os.SystemClock.uptimeMillis() > confirmUntil) return
            if (tapShare()) {
                confirmUntil = 0L
                return
            }
            ui.postDelayed(this, 200)
        }
    }

    private fun startConfirm() {
        confirmUntil = android.os.SystemClock.uptimeMillis() + 15000
        ui.removeCallbacks(confirmTick)
        ui.postDelayed(confirmTick, 100)
    }

    private fun tapShare(): Boolean {
        val label = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault("Vox")
        val roots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow?.let { roots += it } }
        runCatching { windows.mapNotNullTo(roots) { it.root } }
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (!pkg.contains("systemui", true) && pkg != "android") continue
            if (root.findAccessibilityNodeInfosByText(label).isEmpty()) continue // not Vox's question
            for (word in SHARE_BUTTONS) for (n in root.findAccessibilityNodeInfosByText(word)) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
                if (t != word) continue
                var c: android.view.accessibility.AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.isEnabled && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    android.util.Log.i(ScouterService.TAG, "share: tapped \"$t\" ($pkg)")
                    return true
                }
            }
        }
        return false
    }

    // Which app is in front (its package name only, from window changes), for the look the glasses keep per app.
    // Vox, the system's own windows and keyboards are left out. A window change also looks for the share question's
    // button while Vox waits for it (tapShare); no other content is read.
    private var imes: Set<String>? = null
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (confirmUntil > 0L) ui.post(confirmTick)
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        val keyboards = imes ?: runCatching {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet()).also { imes = it }
        if (pkg in keyboards || pkg == app) return
        app = pkg
        onApp?.invoke(pkg)
    }
    override fun onInterrupt() {}

    // What the trackpad did while taken (onTrackpad's kind).
    object Trackpad {
        const val MOVE = 0  // dx, dy: device pixels
        const val CLICK = 1
        const val RIGHT = 2
        const val WHEEL = 3 // dy: notches, positive up
        const val SCROLL = 4 // dy: a two-finger swipe's pixels, positive: the fingers went down (scrolls up)
    }

    companion object {
        @Volatile private var instance: VoxKeys? = null
        @Volatile var onMouse: ((Float, Float) -> Unit)? = null // where Android's pointer is, in screen pixels
        // The trackpad's events while taken (Trackpad's kind, dx, dy), on the accessibility thread.
        @Volatile var onTrackpad: ((Int, Float, Float) -> Unit)? = null
        // The app in front changed (its package), and the last one.
        @Volatile var onApp: ((String) -> Unit)? = null
        @Volatile var app = ""
        @Volatile private var captured = false
        @Volatile private var wantCapture = false

        // The trackpad to the glasses' pointer (taken from the phone's apps), or back to the phone's own pointer.
        fun capture(take: Boolean) {
            if (wantCapture == take) return
            wantCapture = take
            val s = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (captured != wantCapture) s.watchMouse(wantCapture) }
        }

        // A tap (long: held 650 ms, a right click) at x, y of the phone's screen, in pixels. False: VoxKeys is off.
        fun tap(x: Float, y: Float, long: Boolean = false): Boolean {
            val s = instance ?: return false
            val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 650 else 40)).build()
            return s.dispatchGesture(g, null, null)
        }

        // A scroll from x, y: a swipe of dy pixels (positive: the page goes on downward, as a wheel turned down),
        // started where the whole swipe fits on the screen; done (from the main thread) when it has run or was cut.
        fun scroll(x: Float, y: Float, dy: Float, height: Float, done: (() -> Unit)? = null, dx: Float = 0f, width: Float = 0f): Boolean {
            val s = instance ?: return false
            val d = dy.coerceIn(-(height - 4f), height - 4f)
            val from = if (d > 0) y.coerceIn(d + 2f, height - 2f) else y.coerceIn(2f, height - 2f + d)
            val to = (from - d).coerceIn(1f, height - 2f)
            // Sideways too (two fingers going left or right): the same swipe, along x.
            val w = if (width > 0f) width else x + 1f
            val h = if (width > 0f) dx.coerceIn(-(w - 4f), w - 4f) else 0f
            val fromX = if (h > 0) x.coerceIn(h + 2f, w - 2f) else x.coerceIn(2f, (w - 2f + h).coerceAtLeast(2f))
            val toX = (fromX - h).coerceIn(1f, w - 2f)
            val path = Path().apply {
                moveTo(fromX.coerceAtLeast(0f), from)
                lineTo(toX.coerceAtLeast(0f), to)
            }
            // About 2 px a millisecond: the page follows quickly, without a long fling.
            val ms = (maxOf(kotlin.math.abs(d), kotlin.math.abs(h)) / 2f).toLong().coerceIn(80L, 400L)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
            val callback = done?.let {
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) = it()
                    override fun onCancelled(d: GestureDescription?) = it()
                }
            }
            return s.dispatchGesture(g, callback, null)
        }

        // Back, Home, Recents (AccessibilityService.GLOBAL_ACTION_*).
        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        // The share question's buttons that start sharing (AOSP 14: Start; 15+: Share screen; One UI: Start now).
        private val SHARE_BUTTONS = listOf("start now", "share screen", "start", "share", "next")

        // Vox is about to ask Android to share the screen: tap its Start when it comes. False: VoxKeys is off.
        fun confirmShare(): Boolean {
            val s = instance ?: return false
            android.os.Handler(android.os.Looper.getMainLooper()).post { s.startConfirm() }
            return true
        }
    }
}

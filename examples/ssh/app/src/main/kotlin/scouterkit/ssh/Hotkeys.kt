package scouterkit.ssh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

// A keyboard's shortcuts (a Bluetooth keyboard paired with the phone): Alt with an arrow moves around the
// sources as a grid (left and right: this phone, then each other server; up and down: terminal, screen),
// Alt+1 ... Alt+9 picks Home's source of that number, Alt+0 turns the display off or on, Alt+Space (or Alt+V)
// listens for a voice command, Alt+M the pointer (then arrows, Enter, Escape), Alt+T / [ / ] / W the tabs.
// MainActivity takes them
// while Vox is in front; VoxKeys (an accessibility service the user turns on) everywhere.
object Hotkeys {
    // The event was Vox's: a shortcut, or a key typed into the glasses' terminal (both its press and its release are
    // ours then).
    fun handle(context: Context, e: KeyEvent): Boolean {
        if (shortcut(context, e)) return true
        return toGlasses(context, e) && Keyboard.send(e)
    }

    // The glasses show the terminal (on an eye now): a keyboard's keys are its, whatever app is in front on the
    // phone (Alt+K sends them to the phone again until it is pressed again; the phone's screen on the glasses takes
    // them as always).
    fun toGlasses(context: Context, e: KeyEvent): Boolean {
        if (!Keyboard.physical(e) || Session.keysToPhone || !ScouterService.running) return false
        if (Session.glasses != "ready" || Session.blank || Session.showing != Settings.TERMINAL || !Session.shown) return false
        return Settings(context).keysToGlasses
    }

    private fun shortcut(context: Context, e: KeyEvent): Boolean {
        val settings = Settings(context)
        if (!settings.hotkeys) return false
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
        if (!e.isAltPressed || e.isCtrlPressed || e.isMetaPressed) return false
        // The phone's screen on the glasses: Alt+Backspace is Back, Alt+H Home (VoxKeys).
        if (Session.showing == Settings.SCREEN && Session.screenSource.ifEmpty { Settings.PHONE } == Settings.PHONE &&
            (e.keyCode == KeyEvent.KEYCODE_DEL || e.keyCode == KeyEvent.KEYCODE_H)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
                VoxKeys.global(if (e.keyCode == KeyEvent.KEYCODE_DEL) AccessibilityService.GLOBAL_ACTION_BACK
                else AccessibilityService.GLOBAL_ACTION_HOME)
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

    // Android 15+: the mouse's events watched (observed sources: they go on to the phone's apps too), or taken.
    private fun watchMouse(take: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val info = serviceInfo ?: return
        try {
            info.motionEventSources = InputDevice.SOURCE_MOUSE
            info.javaClass.getMethod("setObservedMotionEventSources", Int::class.javaPrimitiveType)
                .invoke(info, if (take) 0 else InputDevice.SOURCE_MOUSE)
            serviceInfo = info
            captured = take
            android.util.Log.i(ScouterService.TAG, "trackpad: " + if (take) "the glasses' pointer" else "the phone's pointer")
        } catch (e: Throwable) {
            // Never left taking the mouse: nothing watched.
            android.util.Log.w(ScouterService.TAG, "mouse: ${e.message}")
            captured = false
            runCatching {
                info.motionEventSources = 0
                serviceInfo = info
            }
        }
    }

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
            twoFinger -> pad(Trackpad.SCROLL, 0f, event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE))
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

    // Which app is in front (its package name only, from window changes; the screen's content is never read), for
    // the look the glasses keep per app. Vox, the system's own windows and keyboards are left out.
    private var imes: Set<String>? = null
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
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

        // A scroll from x, y: a swipe of dy pixels (positive: the page goes on downward, as a wheel turned down).
        fun scroll(x: Float, y: Float, dy: Float, height: Float): Boolean {
            val s = instance ?: return false
            val from = y.coerceIn(1f, height - 2f)
            val to = (from - dy).coerceIn(1f, height - 2f)
            val path = Path().apply {
                moveTo(x.coerceAtLeast(0f), from)
                lineTo(x.coerceAtLeast(0f), to)
            }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build()
            return s.dispatchGesture(g, null, null)
        }

        // Back, Home, Recents (AccessibilityService.GLOBAL_ACTION_*).
        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}

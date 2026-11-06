package scouterkit.ssh

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// A keyboard's shortcuts (a Bluetooth keyboard paired with the phone): Alt with an arrow moves around the
// sources as a grid (left and right: this phone, then each other server; up and down: terminal, screen),
// Alt+1 ... Alt+9 picks Home's source of that number, Alt+0 turns the display off or on, Alt+Space (or Alt+V)
// listens for a voice command, Alt+M the pointer (then arrows, Enter, Escape), Alt+T / [ / ] / W the tabs.
// MainActivity takes them
// while Vox is in front; VoxKeys (an accessibility service the user turns on) everywhere.
object Hotkeys {
    // The event was a shortcut (both its press and its release are ours then).
    fun handle(context: Context, e: KeyEvent): Boolean {
        val settings = Settings(context)
        if (!settings.hotkeys) return false
        // The pointer's keys while it is on (no Alt): arrows move it (Shift: by 5), Enter or Space clicks (Shift+Enter:
        // right click), Page Up / Down scroll, Escape ends it.
        if (Session.pointer && !e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
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
                else ScouterService.send(context, ScouterService.ACTION_POINTER, "cmd" to "toggle")
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
        ScouterService.send(context, ScouterService.ACTION_SOURCE, Shortcuts.EXTRA to key)
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
class VoxKeys : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean = Hotkeys.handle(this, event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

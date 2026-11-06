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
// listens for a voice command. MainActivity takes them
// while Vox is in front; VoxKeys (an accessibility service the user turns on) everywhere.
object Hotkeys {
    // The event was a shortcut (both its press and its release are ours then).
    fun handle(context: Context, e: KeyEvent): Boolean {
        val settings = Settings(context)
        if (!settings.hotkeys || !e.isAltPressed || e.isCtrlPressed || e.isMetaPressed) return false
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

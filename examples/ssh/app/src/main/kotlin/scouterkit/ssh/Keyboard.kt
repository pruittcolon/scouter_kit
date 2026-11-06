package scouterkit.ssh

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

// A Bluetooth (or USB) keyboard paired with the phone, as keyer's key stream (Native.keyEvent): Android hands its
// keys to the app's screen only while it is in front (MainActivity). Keys that type a character go as Unicode (the
// keyboard's layout applies, Shift included), the rest as HID usages; Ctrl, Alt and Meta as keyer's modifier bits.
object Keyboard {
    private const val UNICODE = 1
    private const val HID = 2
    private const val CTRL = 0x01
    private const val SHIFT = 0x02
    private const val ALT = 0x04
    private const val SUPER = 0x08

    // Android key codes of keys that type no character: their HID usage (keyboard page).
    private val usages = mapOf(
        KeyEvent.KEYCODE_ENTER to 0x28, KeyEvent.KEYCODE_NUMPAD_ENTER to 0x28, KeyEvent.KEYCODE_ESCAPE to 0x29,
        KeyEvent.KEYCODE_DEL to 0x2A, KeyEvent.KEYCODE_TAB to 0x2B, KeyEvent.KEYCODE_SPACE to 0x2C,
        KeyEvent.KEYCODE_F1 to 0x3A, KeyEvent.KEYCODE_F2 to 0x3B, KeyEvent.KEYCODE_F3 to 0x3C,
        KeyEvent.KEYCODE_F4 to 0x3D, KeyEvent.KEYCODE_F5 to 0x3E, KeyEvent.KEYCODE_F6 to 0x3F,
        KeyEvent.KEYCODE_F7 to 0x40, KeyEvent.KEYCODE_F8 to 0x41, KeyEvent.KEYCODE_F9 to 0x42,
        KeyEvent.KEYCODE_F10 to 0x43, KeyEvent.KEYCODE_F11 to 0x44, KeyEvent.KEYCODE_F12 to 0x45,
        KeyEvent.KEYCODE_INSERT to 0x49, KeyEvent.KEYCODE_MOVE_HOME to 0x4A, KeyEvent.KEYCODE_PAGE_UP to 0x4B,
        KeyEvent.KEYCODE_FORWARD_DEL to 0x4C, KeyEvent.KEYCODE_MOVE_END to 0x4D, KeyEvent.KEYCODE_PAGE_DOWN to 0x4E,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0x4F, KeyEvent.KEYCODE_DPAD_LEFT to 0x50, KeyEvent.KEYCODE_DPAD_DOWN to 0x51,
        KeyEvent.KEYCODE_DPAD_UP to 0x52,
    )

    // A key from a physical keyboard (not the phone's own buttons or the on-screen keyboard).
    fun physical(e: KeyEvent): Boolean {
        val device = e.device ?: return false
        return !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            (e.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    }

    // Sends a key press (repeats included) to the terminal. Returns whether the key was the terminal's: modifier
    // keys alone and keys it has no meaning for (volume, media) stay Android's.
    fun send(e: KeyEvent): Boolean {
        if (KeyEvent.isModifierKey(e.keyCode)) return true
        if (e.action != KeyEvent.ACTION_DOWN) return usages.containsKey(e.keyCode) || e.unicodeChar != 0
        val mods = (if (e.isCtrlPressed) CTRL else 0) or (if (e.isAltPressed) ALT else 0) or
            (if (e.isMetaPressed) SUPER else 0)
        usages[e.keyCode]?.let {
            Native.keyEvent(HID, mods or (if (e.isShiftPressed) SHIFT else 0), it)
            return true
        }
        // The character without Ctrl, Alt and Meta (they go as modifier bits); Shift and the layout stay applied.
        val plain = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val c = e.getUnicodeChar(plain)
        if (c == 0 || c and KeyCharacterMap.COMBINING_ACCENT != 0) return false
        Native.keyEvent(UNICODE, mods, c)
        return true
    }
}

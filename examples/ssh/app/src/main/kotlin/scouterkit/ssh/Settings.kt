package scouterkit.ssh

import android.content.Context

// Plain settings (R6): secrets live in the Vault, not here.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var host: String by Text("host", "")
    var port: Int by Number("port", 22)
    var user: String by Text("user", "")
    var command: String by Text("command", "")
    var moshPorts: String by Text("mosh_ports", "") // mosh-server's UDP ports, e.g. 60000:60009; empty: its default
    var dx: Int by Number("dx", 100)
    var dy: Int by Number("dy", 9)
    var angle: Int by Number("angle", 20)
    var brightness: Int by Number("brightness", 21)
    var left: String by Text("left", "")
    var right: String by Text("right", "")
    var keyer: String by Text("keyer", "") // bonded address; empty: no keyer link
    // Keys from a keyboard paired with the phone instead of keyer: the session runs while the app is in front.
    var keyboard: Boolean
        get() = prefs.getBoolean("keyboard", false)
        set(v) = prefs.edit().putBoolean("keyboard", v).apply()
    // Debug builds: the terminal shows with the glasses folded (WORN 0), for tests on the table.
    var ignoreWorn: Boolean
        get() = prefs.getBoolean("ignore_worn", false)
        set(v) = prefs.edit().putBoolean("ignore_worn", v).apply()
    // What the glasses show: TERMINAL (the mosh session) or SCREEN (this phone's screen).
    var show: String by Text("show", TERMINAL)
    // A session started from the phone (no keyer needed): it runs until Stop, also with the app in the back.
    var phone: Boolean
        get() = prefs.getBoolean("phone", false)
        set(v) = prefs.edit().putBoolean("phone", v).apply()
    // Started from the phone, the terminal shows whatever the head's angle (not only when looking up).
    var phoneForce: Boolean
        get() = prefs.getBoolean("phone_force", true)
        set(v) = prefs.edit().putBoolean("phone_force", v).apply()
    // The phone's screen: a full-width band (else the whole screen, its shape kept), the band's place (0 top ..
    // 100 bottom), light backgrounds dark (black is see-through), 16 or 2 levels, frames a second at most.
    var screenBand: Boolean
        get() = prefs.getBoolean("screen_band", false)
        set(v) = prefs.edit().putBoolean("screen_band", v).apply()
    var screenBandPos: Int by Number("screen_band_pos", 0)
    var screenInvert: Boolean
        get() = prefs.getBoolean("screen_invert", true)
        set(v) = prefs.edit().putBoolean("screen_invert", v).apply()
    var screenLevels: Int by Number("screen_levels", 2) // 2: the fast path (VIDEO records); 4, 8 or 16 greys
    // Less to send: pixels in blocks of detail x detail (1 ... 4), or 0, auto (black and white): every pixel
    // when a frame fits `screenBudget` KB, else coarser; a screen that stopped moving sharpens.
    var screenDetail: Int by Number("screen_detail", 0)
    var screenBudget: Int by Number("screen_budget", 6)
    // The home screen's preview of what the glasses get, in the panels' green or in white.
    var screenPreview: Boolean
        get() = prefs.getBoolean("screen_preview", true)
        set(v) = prefs.edit().putBoolean("screen_preview", v).apply()
    var previewWhite: Boolean
        get() = prefs.getBoolean("preview_white", false)
        set(v) = prefs.edit().putBoolean("preview_white", v).apply()
    // The look: percent toward the inverse (0 as on the phone, 100 light backgrounds dark), midtones (gamma
    // x100), the black-and-white threshold, and steady (16 greys: one-level flicker ignored).
    var screenInvertPct: Int
        get() = prefs.getInt("screen_invert_pct", if (screenInvert) 100 else 0)
        set(v) = prefs.edit().putInt("screen_invert_pct", v).apply()
    var screenGamma: Int by Number("screen_gamma", 100)
    var screenThreshold: Int by Number("screen_threshold", 128)
    var screenDither: Boolean
        get() = prefs.getBoolean("screen_dither", false)
        set(v) = prefs.edit().putBoolean("screen_dither", v).apply()
    var screenSteady: Boolean
        get() = prefs.getBoolean("screen_steady", true)
        set(v) = prefs.edit().putBoolean("screen_steady", v).apply()
    // How the phone's screen fills its place: FIT (its shape kept), FILL (cropped to fill), STRETCH (distorted).
    var screenFit: String
        get() = prefs.getString("screen_fit", null) ?: if (screenBand) FILL else FIT
        set(v) = prefs.edit().putString("screen_fit", v).apply()
    // Where a crop sits on the phone's screen (0 left/top ... 100 right/bottom).
    var screenPanX: Int by Number("screen_pan_x", 50)
    var screenPanY: Int
        get() = prefs.getInt("screen_pan_y", screenBandPos)
        set(v) = prefs.edit().putInt("screen_pan_y", v).apply()
    // The picture's place on the glasses, in percent of their area: left, top, width, height.
    var screenOutX: Int by Number("screen_out_x", 0)
    var screenOutY: Int by Number("screen_out_y", 0)
    var screenOutW: Int by Number("screen_out_w", 100)
    var screenOutH: Int by Number("screen_out_h", 100)
    // The picture turned clockwise (0, 90, 180, 270), magnified (100 ... 400 %), its contrast stretched
    // (0 ... 100) and sharpened.
    var screenRotate: Int by Number("screen_rotate", 0)
    var screenZoom: Int by Number("screen_zoom", 100)
    var screenContrast: Int by Number("screen_contrast", 60)
    var screenSharpen: Boolean
        get() = prefs.getBoolean("screen_sharpen", true)
        set(v) = prefs.edit().putBoolean("screen_sharpen", v).apply()
    var screenFps: Int by Number("screen_fps", 5)
    // The phone's screen keeps the panels off while an arm is in the case (charging), or while both arms say
    // "not worn" (a guess from the arms' radio, so off by default).
    var screenHideCase: Boolean
        get() = prefs.getBoolean("screen_hide_case", true)
        set(v) = prefs.edit().putBoolean("screen_hide_case", v).apply()
    var screenHideFolded: Boolean
        get() = prefs.getBoolean("screen_hide_folded", false)
        set(v) = prefs.edit().putBoolean("screen_hide_folded", v).apply()
    // The display goes off while the phone is locked (the phone's screen view: nothing new to show).
    var offWhenLocked: Boolean
        get() = prefs.getBoolean("off_when_locked", true)
        set(v) = prefs.edit().putBoolean("off_when_locked", v).apply()
    // For Native.screenFrame.
    fun screenOptions() = intArrayOf(listOf(FIT, FILL, STRETCH).indexOf(screenFit).coerceAtLeast(0), screenPanX, screenPanY,
        screenZoom, screenRotate, screenOutX, screenOutY, screenOutW, screenOutH, screenInvertPct, screenLevels,
        screenContrast, if (screenSharpen) 1 else 0, if (screenHideCase) 1 else 0, if (screenHideFolded) 1 else 0,
        screenGamma, screenThreshold, if (screenSteady) 1 else 0, if (screenDither) 1 else 0, screenDetail, screenBudget)

    // Once: the fast path (black and white) for everyone, as 0.4 made it the default.
    fun migrate() {
        if (prefs.getBoolean("migrated_04", false)) return
        screenLevels = 2
        prefs.edit().putBoolean("migrated_04", true).apply()
    }

    // Each eye's battery band beside the terminal.
    var battery: Boolean
        get() = prefs.getBoolean("battery", true)
        set(v) = prefs.edit().putBoolean("battery", v).apply()

    // What a TouchBar gesture does (ACTIONS): arm 0 left, 1 right; Native.GESTURE_*. Holding the right arm turns
    // the display off and on by default.
    fun gestureAction(arm: Int, gesture: Int): String =
        prefs.getString("gesture_${arm}_$gesture", if (arm == 1 && gesture == Native.GESTURE_HOLD) ACTION_DISPLAY else ACTION_NONE)
            ?: ACTION_NONE

    fun setGestureAction(arm: Int, gesture: Int, action: String) = prefs.edit().putString("gesture_${arm}_$gesture", action).apply()
    // Termux's sshd host keys (SHA256:... fingerprints, space separated), as Termux itself reported them: trusted
    // without asking.
    var termuxKeys: String by Text("termux_keys", "")
    // The server is Termux on this phone: its sshd is started before each session.
    val termux get() = (host == Termux.HOST || host == "localhost") && port == Termux.PORT

    // The app connects to G1 arms only when this is on and both addresses are set (off by default).
    var glassesAllowed: Boolean
        get() = prefs.getBoolean("glasses_allowed", false)
        set(v) = prefs.edit().putBoolean("glasses_allowed", v).apply()

    // An address as the arm (or keyer) it is, for logs and the UI.
    fun side(address: String) = when {
        address.equals(left, ignoreCase = true) -> "left arm"
        address.equals(right, ignoreCase = true) -> "right arm"
        address.equals(keyer, ignoreCase = true) -> "keyer"
        else -> "another device"
    }

    val cols get() = grid(dx, dy).first
    val rows get() = grid(dx, dy).second

    companion object {
        const val TERMINAL = "terminal"
        const val SCREEN = "screen"
        const val FIT = "fit"
        const val FILL = "fill"
        const val STRETCH = "stretch"
        // TouchBar actions.
        const val ACTION_NONE = "none"
        const val ACTION_DISPLAY = "display" // the display off / on
        const val ACTION_VIEW = "view" // the terminal / the phone's screen (while it is shared)
        const val ACTION_STOP = "stop" // the session ends
        val ACTIONS = linkedMapOf(ACTION_NONE to "Nothing", ACTION_DISPLAY to "Display off / on",
            ACTION_VIEW to "Switch terminal / phone screen", ACTION_STOP to "Stop everything")
        val GESTURES = listOf(Native.GESTURE_HOLD to "hold", Native.GESTURE_DOUBLE to "double tap",
            Native.GESTURE_TRIPLE to "triple tap", Native.GESTURE_TAP to "tap")

        // The terminal's size for dx and dy: the binocular area in 7 x 14 cells (R19).
        fun grid(dx: Int, dy: Int) = (640 - dx) / 7 to (480 - dy) / 14
    }

    private inner class Text(val key: String, val default: String) {
        operator fun getValue(owner: Any, p: Any): String = prefs.getString(key, default) ?: default
        operator fun setValue(owner: Any, p: Any, v: String) = prefs.edit().putString(key, v).apply()
    }

    private inner class Number(val key: String, val default: Int) {
        operator fun getValue(owner: Any, p: Any): Int = prefs.getInt(key, default)
        operator fun setValue(owner: Any, p: Any, v: Int) = prefs.edit().putInt(key, v).apply()
    }
}

package scouterkit.ssh

// libscouter_ssh.so: ScouterKit, mosh and the glasses renderer (native/jni.cpp).
object Native {
    // ScouterKit's InitError values for linkProblem.
    const val ERROR_BLUETOOTH = 1
    const val ERROR_BONDING = 3
    const val ERROR_FIRMWARE = 4

    @Volatile var onChanged: (() -> Unit)? = null
    // A TouchBar gesture: arm 0 left, 1 right; GESTURE_*.
    @Volatile var onGesture: ((Int, Int) -> Unit)? = null

    init {
        System.loadLibrary("scouter_ssh")
    }

    @JvmStatic external fun version(): String

    // Blocks until both arms are ready (at most 30 s), then starts the terminal on them; null link: the
    // emulated arms. `font`: departure_mono_nerd.skf. Returns an error or null.
    // ignoreWorn (debug): the terminal also shows with the glasses folded. battery: each eye's battery band.
    @JvmStatic external fun glassesStart(link: GlassesLink?, left: String, right: String, dx: Int, dy: Int,
                                         brightness: Int, angle: Int, font: ByteArray, ignoreWorn: Boolean,
                                         battery: Boolean): String?
    // keep: the terminal's program and its last screen stay on the arms, which show them when the head looks
    // up (keyer off); otherwise the programs stop.
    @JvmStatic external fun glassesStop(keep: Boolean)
    // Read-back of what the arms show against what was sent (T3); blocks for seconds.
    @JvmStatic external fun check(): String
    @JvmStatic external fun emulatedPanel(arm: Int): ByteArray?
    @JvmStatic external fun emulatedProperty(arm: Int, id: Int, value: Int)
    const val PROP_PITCH = 0x1B

    @JvmStatic external fun linkUp(arm: Int, mtu: Int)
    @JvmStatic external fun linkDown(arm: Int)
    @JvmStatic external fun linkData(arm: Int, data: ByteArray)
    @JvmStatic external fun linkFound(arm: Int, address: String)
    @JvmStatic external fun linkProblem(code: Int, text: String)

    @JvmStatic external fun moshStart(ip: String, port: Int, key: String, cols: Int, rows: Int): String?
    // No traffic while paused (keyer off); the session resumes where it was.
    @JvmStatic external fun moshPause(on: Boolean)
    @JvmStatic external fun moshStop()
    @JvmStatic external fun resize(cols: Int, rows: Int)
    @JvmStatic external fun keyEvent(kind: Int, mods: Int, value: Int)
    // keyer held: the terminal shows on the glasses whatever the head's angle (R25).
    @JvmStatic external fun force(on: Boolean)
    // heard, ended, silent ms, left up, right up, terminal shown on an eye, phone's screen mirrored (1),
    // its frames shown.
    @JvmStatic external fun status(): IntArray
    // The mosh session's last error and the terminal's title.
    @JvmStatic external fun texts(): Array<String>
    @JvmStatic external fun snapshot(): ByteArray?

    // The phone's screen instead of the terminal: now, or as soon as the glasses are started. An error or null
    // (the terminal stays then).
    @JvmStatic external fun screenStart(): String?
    // Back to the terminal.
    @JvmStatic external fun screenStop()
    // One captured frame: a direct buffer of RGBA_8888 rows `stride` bytes apart. Blocks while the previous
    // frame is not on the display yet. One of SCREEN_*.
    // options: Settings.screenOptions() (fit, pan x, pan y, zoom, rotate, place x, y, w, h, invert, levels,
    // contrast, sharpen, dark in the case, dark when folded, gamma, threshold, steady, dither, detail, budget).
    @JvmStatic external fun screenFrame(buffer: java.nio.ByteBuffer, width: Int, height: Int, stride: Int,
                                        options: IntArray): Int
    // What the glasses show (the last picture sent), else what they would show for this frame, into out (640 x 480
    // bytes at least, 0 ... 255); info: width, height, sent (1). False for a bad frame.
    @JvmStatic external fun screenPreview(buffer: java.nio.ByteBuffer, width: Int, height: Int, stride: Int,
                                          options: IntArray, dx: Int, dy: Int, out: ByteArray, info: IntArray): Boolean
    // frames, the last frame's bytes, bytes a second, detail (1 every pixel ... 4), frames skipped while busy,
    // the picture's w, h, x, y, the path (2 black and white, 16 greys, 0 none), panels off (1).
    @JvmStatic external fun screenStats(): IntArray
    // Each eye's panel read back against what was sent, a line each; blocks for a second or more.
    @JvmStatic external fun screenCheck(): String
    // Why the mirror keeps the panels off ("in the case", "folded"), or "".
    @JvmStatic external fun screenWhy(): String
    // Pixels left empty above and below the terminal on the glasses (fewer rows), at once.
    @JvmStatic external fun terminalMargins(top: Int, bottom: Int)
    // Voice: the band over the terminal's top rows (wrapped into at most `lines` lines; empty: gone), and the
    // glasses' microphone (micRead: 16 kHz mono since the last call, no waiting; -1 when it is off).
    @JvmStatic external fun voiceBand(text: String, lines: Int)
    @JvmStatic external fun micStart(): Boolean
    @JvmStatic external fun micStop()
    @JvmStatic external fun micRead(out: FloatArray): Int
    // Each eye's battery band beside the terminal, at once.
    @JvmStatic external fun setBattery(on: Boolean)
    // Both arms' brightness (0 ... 42), at once.
    @JvmStatic external fun brightness(level: Int)
    // The display off (nothing shows; the TouchBar still reports) or on again.
    @JvmStatic external fun blank(on: Boolean)
    // Left %, right %, left charging, right charging (-1 unknown); blocks while the arms answer.
    @JvmStatic external fun battery(): IntArray
    const val SCREEN_SHOWN = 0
    const val SCREEN_SAME = 1
    const val SCREEN_HIDDEN = 2 // in the case or folded: the panels are off
    const val SCREEN_STOPPED = 3
    const val SCREEN_BUSY = 4 // the link still carries earlier frames: this one waits
    const val SCREEN_LATER = 5 // sent coarser (auto detail): the same frame again sharpens it
    // TOUCH_GESTURE (VM.md §6.7).
    const val GESTURE_TAP = 1
    const val GESTURE_DOUBLE = 2
    const val GESTURE_TRIPLE = 3
    const val GESTURE_HOLD = 4 // a press held 458 ms

    // From the native threads: a new screen or status.
    @JvmStatic fun changed() {
        onChanged?.invoke()
    }

    // From the native threads: a TouchBar gesture.
    @JvmStatic fun gesture(arm: Int, gesture: Int) {
        onGesture?.invoke(arm, gesture)
    }
}

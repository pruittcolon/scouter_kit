package scouterkit.ssh

import android.content.Context

// Plain settings (R6): secrets live in the Vault, not here.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val app: Context = context.applicationContext

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

    // The look-up view (LookUp): the fade when the terminal comes and goes (ms, 0: none), and the header above the
    // terminal with the time, date and batteries, and the newest phone notification.
    var fadeMs: Int by Number("fade_ms", 400)
    // The graphical dashboard above the terminal (Dashboard) instead of the header's text lines, and its height.
    var dashboard: Boolean
        get() = prefs.getBoolean("dashboard", true)
        set(v) = prefs.edit().putBoolean("dashboard", v).apply()
    var dashHeight: Int by Number("dash_height", 88)
    // The dashboard's height on the glasses, 0 without it (even, as the native side takes it).
    val dashRows get() = if (dashboard && (lookUpClock || lookUpNotifications)) dashHeight.coerceIn(56, 140) and 1.inv() else 0
    var lookUpClock: Boolean
        get() = prefs.getBoolean("look_up_clock", true)
        set(v) = prefs.edit().putBoolean("look_up_clock", v).apply()
    var lookUpNotifications: Boolean
        get() = prefs.getBoolean("look_up_notifications", true)
        set(v) = prefs.edit().putBoolean("look_up_notifications", v).apply()

    // Pixels of the glasses' display left empty above and below the terminal (its top can be hard to see).
    var termTop: Int by Number("term_top", 0)
    var termBottom: Int by Number("term_bottom", 0)

    // Each eye's battery band beside the terminal.
    var battery: Boolean
        get() = prefs.getBoolean("battery", true)
        set(v) = prefs.edit().putBoolean("battery", v).apply()

    // What a TouchBar gesture does (ACTIONS): arm 0 left, 1 right; Native.GESTURE_*. By default: right hold turns
    // the display off and on, left hold goes to the next source, left double tap back to the one before.
    fun gestureAction(arm: Int, gesture: Int): String = prefs.getString("gesture_${arm}_$gesture", when {
        arm == 1 && gesture == Native.GESTURE_HOLD -> ACTION_DISPLAY
        arm == 0 && gesture == Native.GESTURE_HOLD -> ACTION_NEXT
        arm == 0 && gesture == Native.GESTURE_DOUBLE -> ACTION_PREV
        arm == 1 && gesture == Native.GESTURE_DOUBLE -> ACTION_LISTEN
        else -> ACTION_NONE
    }) ?: ACTION_NONE

    fun setGestureAction(arm: Int, gesture: Int, action: String) = prefs.edit().putString("gesture_${arm}_$gesture", action).apply()
    // Termux's sshd host keys (SHA256:... fingerprints, space separated), as Termux itself reported them: trusted
    // without asking.
    var termuxKeys: String by Text("termux_keys", "")
    // The server is Termux on this phone: its sshd is started before each session.
    val termux get() = (host == Termux.HOST || host == "localhost") && port == Termux.PORT

    // Saved servers. The active one's fields are host, port, user, command and moshPorts (what the service uses):
    // switching copies a saved server into them, and editing them (Settings, Terminal server) updates it.
    data class Server(val id: String, val name: String, val host: String, val port: Int, val user: String,
                      val command: String = "", val moshPorts: String = "") {
        val address get() = "$user@$host" + if (port != 22) ":$port" else ""
        // This phone's own server: its screen is the phone's (no screen over SSH).
        val local get() = host == Termux.HOST || host == "localhost"
    }

    var activeServer: String by Text("server_active", "")

    fun servers(): List<Server> {
        val list = runCatching {
            val a = org.json.JSONArray(prefs.getString("servers", null) ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Server(o.getString("id"), o.optString("name"), o.optString("host"), o.optInt("port", 22),
                    o.optString("user"), o.optString("command"), o.optString("mosh_ports"))
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty() || host.isEmpty()) return list
        // From before saved servers: the one in the fields.
        val first = Server(newServerId(), nameFor(host, command), host, port, user, command, moshPorts)
        activeServer = first.id
        saveServers(listOf(first))
        return listOf(first)
    }

    private fun saveServers(list: List<Server>) {
        val a = org.json.JSONArray()
        for (s in list) a.put(org.json.JSONObject().put("id", s.id).put("name", s.name).put("host", s.host)
            .put("port", s.port).put("user", s.user).put("command", s.command).put("mosh_ports", s.moshPorts))
        prefs.edit().putString("servers", a.toString()).apply()
        Shortcuts.update(app)
    }

    fun active(): Server? = servers().let { l -> l.firstOrNull { it.id == activeServer } ?: l.firstOrNull() }

    // The terminal from this saved server from now on (a running session connects again).
    fun useServer(id: String): Server? {
        val s = servers().firstOrNull { it.id == id } ?: return null
        host = s.host
        port = s.port
        user = s.user
        command = s.command
        moshPorts = s.moshPorts
        activeServer = s.id
        return s
    }

    // Adds or replaces (the same id); the active one's fields follow.
    fun putServer(s: Server) {
        val l = servers().toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l += s
        saveServers(l)
        if (s.id == active()?.id) useServer(s.id)
    }

    fun removeServer(id: String) {
        val l = servers().filter { it.id != id }
        saveServers(l)
        if (activeServer == id) l.firstOrNull()?.let { useServer(it.id) } ?: run { activeServer = "" }
    }

    // The fields were edited (Settings, Terminal server): the active saved server takes them.
    fun syncActive() {
        if (host.isEmpty()) return
        val a = active() ?: return
        if (a.host != host || a.port != port || a.user != user || a.command != command || a.moshPorts != moshPorts)
            putServer(a.copy(host = host, port = port, user = user, command = command, moshPorts = moshPorts))
    }

    // What the glasses can show, in order: each saved server's terminal, the phone's screen, each other server's
    // screen (SSH, vox-screen). Keys: "term:ID", PHONE, "screen:ID"; with a name for buttons and shortcuts.
    fun sources(): List<Pair<String, String>> {
        val l = servers()
        return l.map { "term:${it.id}" to "${it.name} · terminal" } + (PHONE to "Phone screen") +
            l.filter { !it.local }.map { "screen:${it.id}" to "${it.name} · screen" }
    }

    // A step from one source to another on a grid: the machines across (this phone, then each other saved
    // server), terminal above screen. Left and right keep the kind where the other machine has it.
    fun move(from: String, dir: String): String? {
        val l = servers()
        val local = l.firstOrNull { it.local }
        val machines = listOf(local?.let { "term:${it.id}" } to PHONE) +
            l.filter { !it.local }.map { "term:${it.id}" to "screen:${it.id}" }
        var m = machines.indexOfFirst { it.first == from || it.second == from }
        val screen = from == PHONE || from.startsWith("screen:")
        if (m < 0) m = 0
        return when (dir) {
            "left", "right" -> {
                val to = machines[Math.floorMod(m + if (dir == "right") 1 else -1, machines.size)]
                (if (screen) to.second else to.first) ?: to.second
            }
            "up" -> machines[m].first ?: from
            "down" -> machines[m].second
            else -> null
        }
    }

    // Voice (Voice.kt): realtime words, always listening (after the wake word "Vox"), the band on the glasses and
    // how long it stays after a command, and the user's own commands.
    var voiceRealtime: Boolean
        get() = prefs.getBoolean("voice_realtime", true)
        set(v) = prefs.edit().putBoolean("voice_realtime", v).apply()
    var voiceAlways: Boolean
        get() = prefs.getBoolean("voice_always", false)
        set(v) = prefs.edit().putBoolean("voice_always", v).apply()
    var voiceBand: Boolean
        get() = prefs.getBoolean("voice_band", true)
        set(v) = prefs.edit().putBoolean("voice_band", v).apply()
    var voiceBandSecs: Int by Number("voice_band_secs", 4)

    fun voiceCommands(): List<VoiceCommands.Command> = runCatching {
        val a = org.json.JSONArray(prefs.getString("voice_commands", null) ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            VoiceCommands.Command(o.getString("phrase"), o.getString("action"), o.optString("arg"))
        }
    }.getOrDefault(emptyList())

    fun saveVoiceCommands(list: List<VoiceCommands.Command>) {
        val a = org.json.JSONArray()
        for (c in list) a.put(org.json.JSONObject().put("phrase", c.phrase).put("action", c.action).put("arg", c.arg))
        prefs.edit().putString("voice_commands", a.toString()).apply()
    }

    // A keyboard's Alt shortcuts (Hotkeys).
    var hotkeys: Boolean
        get() = prefs.getBoolean("hotkeys", true)
        set(v) = prefs.edit().putBoolean("hotkeys", v).apply()

    // The source the glasses show (or last showed).
    var source: String by Text("source", "")

    fun sourceName(key: String) = sources().firstOrNull { it.first == key }?.second ?: key

    // The laptop's (or another server's) screen: its width asked for, in pixels (the height follows its shape).
    var remoteWidth: Int by Number("remote_width", 960)

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

    val cols get() = grid(dx, dy, termTop, termBottom).first
    // The terminal's rows for the SSH session: the glasses' rows less the look-up header's.
    val rows get() = maxOf(2, grid(dx, dy, termTop + dashRows, termBottom).second - LookUp.rows(this))

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
        const val ACTION_NEXT = "next" // the next source (sources())
        const val ACTION_PREV = "prev" // the source before
        const val ACTION_LISTEN = "listen" // voice: listen for one command (again: stop)
        val ACTIONS = linkedMapOf(ACTION_NONE to "Nothing", ACTION_DISPLAY to "Display off / on",
            ACTION_NEXT to "Next source", ACTION_PREV to "Previous source", ACTION_LISTEN to "Listen (voice command)",
            ACTION_VIEW to "Switch terminal / screen", ACTION_STOP to "Stop everything")
        const val PHONE = "phone" // the phone's own screen (a source)

        fun newServerId() = java.util.UUID.randomUUID().toString().take(8)
        // A name for a server from before saved servers: this phone's own is "Phone".
        @Suppress("UNUSED_PARAMETER")
        fun nameFor(host: String, command: String) = if (host == Termux.HOST || host == "localhost") "Phone" else host
        val GESTURES = listOf(Native.GESTURE_HOLD to "hold", Native.GESTURE_DOUBLE to "double tap",
            Native.GESTURE_TRIPLE to "triple tap", Native.GESTURE_TAP to "tap")

        // The terminal's size for dx and dy: the binocular area in 7 x 14 cells (R19), less the margins above and
        // below (at least two rows; as the native terminal works it out).
        fun grid(dx: Int, dy: Int, top: Int = 0, bottom: Int = 0): Pair<Int, Int> {
            val vh = 480 - kotlin.math.abs(dy)
            val t = top.coerceIn(0, maxOf(0, vh - 28))
            val b = bottom.coerceIn(0, maxOf(0, vh - 28 - t))
            return (640 - kotlin.math.abs(dx)) / 7 to (vh - t - b) / 14
        }
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

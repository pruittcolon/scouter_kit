package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

private const val SCREEN_REQUEST = 7 // the screen-sharing consent

// Vox's main screen, in tabs: Home (what the glasses show: one button per source, the display, brightness,
// shortcuts), Screen (the preview of what the glasses get, size and speed, place, look) and Terminal (its
// preview, its place on the glasses, the saved servers); Settings opens the rest. The session runs until Stop,
// with no keyer needed (keyer and keyboard mode still work as before).
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var terminal: TerminalView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var pills: LinearLayout
    private lateinit var leftGauge: BatteryGauge
    private lateinit var rightGauge: BatteryGauge
    private lateinit var sourcesBox: LinearLayout
    private lateinit var pinsBox: LinearLayout
    private lateinit var serversBox: LinearLayout
    private val sourceTiles = mutableListOf<Pair<String, Tile>>()
    private var offTile: Tile? = null
    private lateinit var stopSharing: View
    private lateinit var batterySwitch: Switch
    private lateinit var forceSwitch: Switch
    private lateinit var previewView: PreviewView
    private lateinit var previewText: TextView
    private lateinit var content: FrameLayout
    private val pages = mutableListOf<View>()
    private val tabLabels = mutableListOf<TextView>()
    private var tab = 0
    private var resumed = false
    private var checked: String? = null // the last "Check the glasses"
    private val listener: () -> Unit = { show() }
    private val previewListener: () -> Unit = {
        runOnUiThread {
            previewView.refresh()
            previewLine()
        }
    }
    private var asked: String? = null
    private val associating = mutableSetOf<String>() // devices asked for while this screen lives
    private var companion: String? = null // the last association's outcome

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        settings.migrate()
        settings.servers() // the server from before saved servers becomes the first one
        Shortcuts.update(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.SURFACE
        window.decorView.setBackgroundColor(Ui.BG)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        content = FrameLayout(this)
        root.addView(content, lp(h = 0, weight = 1f))
        root.addView(tabBar(), lp())
        setContentView(root)
        pages += homePage(settings)
        pages += screenPage(settings)
        pages += terminalPage(settings)
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selectTab(getPreferences(MODE_PRIVATE).getInt("tab", 0))
        handle(intent)
    }

    // A page: a dark scrolling column.
    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(column)
        }
        return scroll to column
    }

    // The tabs at the bottom; Settings opens its own screen.
    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        for ((i, name) in listOf("Home", "Screen", "Terminal", "Settings").withIndex()) {
            val t = text(name, 13.5f, Ui.MUTED, Ui.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { if (i == 3) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) else selectTab(i) }
            }
            tabLabels += t
            bar.addView(t, lp(0, weight = 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { setBackgroundColor(Ui.STROKE) }, lp(h = 1))
            addView(bar, lp())
        }
    }

    private fun selectTab(i: Int) {
        tab = i.coerceIn(0, pages.size - 1)
        for ((k, p) in pages.withIndex()) p.visibility = if (k == tab) View.VISIBLE else View.GONE
        for ((k, t) in tabLabels.withIndex()) {
            val on = k == tab
            t.setTextColor(if (on) Ui.ON_ACCENT else Ui.MUTED)
            t.background = if (on) accentGradient(dp(14).toFloat()) else ripple(rounded(0, dp(14).toFloat()), dp(14).toFloat())
        }
        getPreferences(MODE_PRIVATE).edit().putInt("tab", tab).apply()
        // The preview is made only while its tab shows.
        Preview.wanted = resumed && tab == 1 && Settings(this).screenPreview
        if (tab == 1) previewView.refresh()
    }

    private fun homePage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Vox", "Your glasses: phone and laptop, terminal and screen"))
        val hero = card()
        headline = text("", 23f, Ui.TEXT, Ui.BLACK).apply { letterSpacing = -0.01f }
        detail = text("", 13.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) }
        pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hero.addView(headline)
        hero.addView(detail)
        val gauges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftGauge = BatteryGauge(this, "Left arm")
        rightGauge = BatteryGauge(this, "Right arm")
        gauges.addView(leftGauge, lp(0, weight = 1f).apply { rightMargin = dp(10) })
        gauges.addView(rightGauge, lp(0, weight = 1f).apply { leftMargin = dp(10) })
        hero.addView(gauges, lp().apply { bottomMargin = dp(10) })
        hero.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        })
        page.addView(hero)

        val on = card("On the glasses", "One tap switches. Shortcuts and the TouchBar do the same from anywhere.")
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        on.addView(sourcesBox)
        page.addView(on)

        val quick = card()
        quick.addView(slider("Brightness", 0, 42, settings.brightness, { "${it * 100 / 42}%" }) { v ->
            settings.brightness = v
            Thread { Native.brightness(v) }.start()
        })
        quick.addView(button("Stop everything", DANGER) {
            note = null
            ScouterService.send(this, ScouterService.ACTION_STOP)
        })
        page.addView(quick)

        val keys = card("Shortcuts", "Keyboard: Alt+← / → phone or laptop, Alt+↑ / ↓ terminal or screen, Alt+1…9 a " +
            "source, Alt+0 display off / on (everywhere once turned on in Settings, Keyboard shortcuts). Glasses: hold " +
            "the left TouchBar for the next source, double-tap it for the one before, hold the right one for display " +
            "off. Long-press the Vox icon, or add these to your home screen; Quick Settings: the \"Next source\" tile.")
        pinsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keys.addView(pinsBox)
        page.addView(keys)
        status = text("", 11.5f, Ui.FAINT, Ui.MONO).apply { setPadding(dp(4), dp(4), dp(4), 0) }
        page.addView(status)
        return scroll
    }

    private fun screenPage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Screen", "What the glasses get, and how"))
        val shown = card("Preview", "What the glasses get (it stays out of the screen sharing itself).")
        previewView = PreviewView(this).apply {
            white = settings.previewWhite
            visibility = if (settings.screenPreview) View.VISIBLE else View.GONE
        }
        shown.addView(previewView, lp().apply { topMargin = dp(4) })
        previewText = text("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }
        shown.addView(previewText)
        lateinit var colour: TextView
        colour = chip(if (settings.previewWhite) "Panel green" else "White", false) {
            settings.previewWhite = !settings.previewWhite
            previewView.white = settings.previewWhite
            colour.text = if (settings.previewWhite) "Panel green" else "White"
        }
        lateinit var hide: TextView
        hide = chip(if (settings.screenPreview) "Hide preview" else "Show preview", false) {
            settings.screenPreview = !settings.screenPreview
            Preview.wanted = resumed && tab == 1 && settings.screenPreview
            previewView.visibility = if (settings.screenPreview) View.VISIBLE else View.GONE
            hide.text = if (settings.screenPreview) "Hide preview" else "Show preview"
        }
        shown.addView(chipRow(chip("Check the glasses", false) { checkGlasses() }, colour, hide))
        stopSharing = button("Stop sharing the phone's screen") { ScouterService.send(this, ScouterService.ACTION_STOP_SHARING) }
        shown.addView(stopSharing)
        page.addView(shown)

        val size = card("Size and speed", "Less to send is faster on the glasses and lighter on both batteries.")
        size.addView(text("Greys", 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, dp(4), 0, 0) })
        size.addView(segmented(listOf("2" to "B&W · fast", "4" to "4", "8" to "8", "16" to "16"),
            settings.screenLevels.toString()) { settings.screenLevels = it.toInt() })
        size.addView(text("Detail", 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        size.addView(segmented(listOf("0" to "Auto", "1" to "Full", "2" to "½", "3" to "⅓", "4" to "¼"),
            settings.screenDetail.toString()) { settings.screenDetail = it.toInt() })
        size.addView(slider("Auto: a moving frame at most", 1, 32, settings.screenBudget, { "$it KB" },
            { settings.screenBudget = it }) { settings.screenBudget = it })
        size.addView(slider("Frames a second at most", 1, 30, settings.screenFps, { "$it" }) { settings.screenFps = it })
        size.addView(text("Laptop screen: pixels asked for", 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        size.addView(segmented(listOf("640" to "640", "960" to "960", "1280" to "1280"), settings.remoteWidth.toString()) {
            settings.remoteWidth = it.toInt()
        })
        page.addView(size)

        val where = card("Place", "Where it shows: drag to move, drag the corner to resize, double-tap to fill.")
        where.addView(segmented(listOf(Settings.FIT to "Fit", Settings.FILL to "Fill", Settings.STRETCH to "Stretch"), settings.screenFit) {
            settings.screenFit = it
        })
        val (cols, rows) = 640 - kotlin.math.abs(settings.dx) to 480 - kotlin.math.abs(settings.dy)
        val editor = LayoutEditor(this, cols.toFloat() / rows, settings.screenOutX, settings.screenOutY, settings.screenOutW,
            settings.screenOutH, { x, y, w, h -> place(x, y, w, h) }) { x, y, w, h -> place(x, y, w, h) }
        where.addView(editor, lp().apply { topMargin = dp(6) })
        where.addView(chipRow(
            chip("Full", false) { editor.set(0, 0, 100, 100) },
            chip("Left half", false) { editor.set(0, 0, 50, 100) },
            chip("Right half", false) { editor.set(50, 0, 50, 100) },
            chip("Top half", false) { editor.set(0, 0, 100, 50) },
            chip("Bottom half", false) { editor.set(0, 50, 100, 50) },
            chip("Small, centred", false) { editor.set(25, 25, 50, 50) },
        ))
        where.addView(slider("Zoom", 100, 400, settings.screenZoom, { String.format(java.util.Locale.US, "%.1f×", it / 100f) },
            { settings.screenZoom = it }) { settings.screenZoom = it })
        where.addView(slider("Pan across", 0, 100, settings.screenPanX, { "$it%" }, { settings.screenPanX = it }) { settings.screenPanX = it })
        where.addView(slider("Pan down", 0, 100, settings.screenPanY, { "$it%" }, { settings.screenPanY = it }) { settings.screenPanY = it })
        where.addView(segmented(listOf("0" to "Upright", "90" to "↻ 90°", "180" to "180°", "270" to "↺ 90°"),
            settings.screenRotate.toString()) { settings.screenRotate = it.toInt() })
        page.addView(where)

        val look = card("Look", "Black is see-through on the glasses.")
        lateinit var invertBar: android.widget.SeekBar
        lateinit var gammaBar: android.widget.SeekBar
        lateinit var contrastBar: android.widget.SeekBar
        lateinit var thresholdBar: android.widget.SeekBar
        fun bar(v: View): android.widget.SeekBar = (v as LinearLayout).getChildAt(1) as android.widget.SeekBar
        fun lookPreset(invert: Int, gamma: Int, contrast: Int, threshold: Int) {
            settings.screenInvertPct = invert
            settings.screenGamma = gamma
            settings.screenContrast = contrast
            settings.screenThreshold = threshold
            invertBar.progress = invert
            gammaBar.progress = gamma
            contrastBar.progress = contrast
            thresholdBar.progress = threshold
        }
        look.addView(chipRow(
            chip("Dark mode", false) { lookPreset(100, 100, 60, 128) },
            chip("Soft", false) { lookPreset(70, 110, 40, 128) },
            chip("As on the phone", false) { lookPreset(0, 100, 0, 128) },
            chip("High contrast", false) { lookPreset(100, 140, 100, 150) },
            chip("Bright text", false) { lookPreset(100, 70, 80, 100) },
        ))
        val invertRow = slider("Invert", 0, 100, settings.screenInvertPct, { "$it%" }, { settings.screenInvertPct = it }) {
            settings.screenInvertPct = it
        }
        invertBar = bar(invertRow)
        look.addView(invertRow)
        val contrastRow = slider("Contrast boost", 0, 100, settings.screenContrast, { "$it%" }, { settings.screenContrast = it }) {
            settings.screenContrast = it
        }
        contrastBar = bar(contrastRow)
        look.addView(contrastRow)
        val more = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val gammaRow = slider("Midtones", 40, 250, settings.screenGamma, {
            when {
                it < 95 -> "brighter " + String.format(java.util.Locale.US, "%.2f", it / 100f)
                it > 105 -> "darker " + String.format(java.util.Locale.US, "%.2f", it / 100f)
                else -> "as is"
            }
        }, { settings.screenGamma = it }) { settings.screenGamma = it }
        gammaBar = bar(gammaRow)
        more.addView(gammaRow)
        val thresholdRow = slider("Black & white cut-off", 10, 245, settings.screenThreshold, { "$it" },
            { settings.screenThreshold = it }) { settings.screenThreshold = it }
        thresholdBar = bar(thresholdRow)
        more.addView(thresholdRow)
        more.addToggle(toggle("Sharpen", "Crisper text", settings.screenSharpen) { settings.screenSharpen = it })
        more.addToggle(toggle("Dither", "Black & white: greys as fine patterns (photos, video; more to send)", settings.screenDither) {
            settings.screenDither = it
        })
        more.addToggle(toggle("Steady", "16 greys: ignore one-step flicker (smoother, less to send)", settings.screenSteady) {
            settings.screenSteady = it
        })
        lateinit var moreChip: TextView
        moreChip = chip("More look settings", false) {
            more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            moreChip.text = if (more.visibility == View.VISIBLE) "Fewer look settings" else "More look settings"
        }
        look.addView(chipRow(moreChip))
        look.addView(more)
        look.addToggle(toggle("Dark while the phone is locked", "With the phone's screen on the glasses", settings.offWhenLocked) {
            settings.offWhenLocked = it
        })
        page.addView(look)
        return scroll
    }

    private fun terminalPage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Terminal", "Where it comes from, and where it sits"))
        val got = card("What the glasses get")
        terminal = TerminalView(this)
        got.addView(clipFrame(terminal, 260))
        page.addView(got)

        val servers = card("Servers", "Saved once and kept with the app's updates. The terminal comes from the one in " +
            "use; Home switches between them.")
        serversBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servers.addView(serversBox)
        servers.addView(button("Add a server") { editServer(null) })
        page.addView(servers)

        val sits = card("On the glasses")
        fun margins() {
            val (top, bottom) = settings.termTop to settings.termBottom
            Thread { Native.terminalMargins(top, bottom) }.start()
            if (ScouterService.running) ScouterService.send(this) // the SSH session gets the new number of rows
        }
        fun rowsLeft(top: Int, bottom: Int) = Settings.grid(settings.dx, settings.dy, top, bottom).second
        sits.addView(slider("Space at the top", 0, 300, settings.termTop,
            { "$it px · ${rowsLeft(it, settings.termBottom)} rows" }) { v ->
            settings.termTop = v
            margins()
        })
        sits.addView(slider("Space at the bottom", 0, 200, settings.termBottom,
            { "$it px · ${rowsLeft(settings.termTop, it)} rows" }) { v ->
            settings.termBottom = v
            margins()
        })
        sits.addView(slider("Depth", 0, 320, settings.dx, { "$it · picture ${640 - it} px wide" }) { v ->
            // The eyes' horizontal offset: the picture seems nearer or farther. The glasses reconnect to apply it.
            settings.dx = v
            if (ScouterService.running) ScouterService.send(this)
        })
        sits.addView(text("The spaces move the terminal where the display is easier to see (fewer rows); depth moves " +
            "it nearer or farther (the glasses reconnect for a moment).", 12f, Ui.FAINT))
        forceSwitch = toggle("Always visible", "Off: the terminal shows only when you look up", settings.phoneForce) {
            settings.phoneForce = it
            if (ScouterService.running) ScouterService.send(this)
        }
        sits.addToggle(forceSwitch)
        batterySwitch = toggle("Battery icons", "Each eye's battery level beside the terminal", settings.battery) {
            settings.battery = it
            Thread { Native.setBattery(it) }.start()
        }
        sits.addToggle(batterySwitch)
        page.addView(sits)
        if (BuildConfig.FLAVOR == "emulated") page.addView(PanelView(this))
        return scroll
    }

    // Home's buttons, one per source (Settings.sources) and the display, two a row; and the shortcuts to add.
    private fun buildSources() {
        val settings = Settings(this)
        sourcesBox.removeAllViews()
        sourceTiles.clear()
        val keys = settings.sources().map { it.first } + OFF
        for (row in keys.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, key) in row.withIndex()) {
                val (title, sub) = label(settings, key)
                val tile = Tile(this, title, sub) { choose(key) }
                line.addView(tile, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(10) })
                if (key == OFF) offTile = tile else sourceTiles += key to tile
            }
            if (row.size == 1) line.addView(View(this), lp(0, weight = 1f).apply { leftMargin = dp(10) })
            sourcesBox.addView(line, lp().apply { bottomMargin = dp(10) })
        }
        if (settings.servers().isEmpty()) sourcesBox.addView(chipRow(chip("Add a server for the terminal", false) { selectTab(2) }))
        pinsBox.removeAllViews()
        val pins = settings.sources().map { (key, name) -> chip("+ $name", false) { pin(key, name) } } +
            chip("+ Next source", false) { pin(Shortcuts.NEXT, "Next source") } +
            chip("+ Display off / on", false) { pin(Shortcuts.DISPLAY, "Display off / on") }
        for (row in pins.chunked(2)) pinsBox.addView(chipRow(*row.toTypedArray()))
    }

    // A source button's title and the line under it.
    private fun label(settings: Settings, key: String): Pair<String, String> {
        val server = settings.servers().firstOrNull { it.id == key.substringAfter(":") }
        return when {
            key == OFF -> "Display" to if (Session.blank) "Off · tap for on" else "Off, link kept"
            key == Settings.PHONE -> "Phone" to if (Session.capturing) "Screen · shared" else "Screen"
            key.startsWith("term:") -> (server?.name ?: "Server") to "Terminal"
            else -> (server?.name ?: "Server") to "Screen"
        }
    }

    private fun pin(key: String, name: String) {
        if (!Shortcuts.pin(this, key, name)) {
            note = "This home screen can't add shortcuts: long-press the Vox icon instead."
            show()
        }
    }

    // A source on the glasses; the phone's screen asks Android first while it is not shared.
    private fun choose(key: String) {
        if (key == OFF) return toggleDisplay()
        ready()?.let { note = it; return show() }
        Settings(this).glassesAllowed = true
        note = null
        if (key == Settings.PHONE && !(Session.capturing && ScouterService.running)) return shareScreen()
        startingAt = android.os.SystemClock.elapsedRealtime()
        ScouterService.send(this, ScouterService.ACTION_SOURCE, Shortcuts.EXTRA to key)
        show()
    }

    // The Terminal tab's list: each saved server, the one in use marked.
    private fun buildServers() {
        val settings = Settings(this)
        serversBox.removeAllViews()
        val active = settings.active()?.id
        for (s in settings.servers()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(s.name + if (s.id == active) "  · in use" else "", 15.5f, Ui.TEXT, Ui.BOLD))
            texts.addView(text(s.address + if (s.command.isNotEmpty()) " · ${s.command}" else "", 12.5f, Ui.MUTED))
            row.addView(texts, lp(0, weight = 1f).apply { rightMargin = dp(8) })
            row.addView(chip("Edit", false) { editServer(s) })
            serversBox.addView(row)
        }
        if (settings.servers().isEmpty()) serversBox.addView(text("No server yet: add one, or run scouter.sh in Termux " +
            "and use Settings, Terminal server.", 12.5f, Ui.FAINT))
    }

    // Add (null) or change a saved server; Delete from here too.
    private fun editServer(s: Settings.Server?) {
        val settings = Settings(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val name = field("Name", s?.name ?: "", "Laptop")
        val host = field("Host", s?.host ?: "", "an address, a Tailscale IP or name")
        val port = field("Port", (s?.port ?: 22).toString(), "22", numeric = true)
        val user = field("User", s?.user ?: "", "")
        val command = field("Command", s?.command ?: "", "empty: the login shell; tmux new -A -s main")
        for (f in listOf(name, host, port, user, command)) box.addField(f)
        val d = dialog()
            .setTitle(if (s == null) "Add a server" else "Edit ${s.name}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                val u = user.text.toString().trim()
                if (h.isEmpty() || u.isEmpty()) {
                    note = "A server needs a host and a user."
                    return@setPositiveButton show()
                }
                val saved = Settings.Server(s?.id ?: Settings.newServerId(), name.text.toString().trim().ifEmpty { h }, h,
                    port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22, u, command.text.toString().trim(),
                    s?.moshPorts ?: "")
                settings.putServer(saved)
                buildServers()
                buildSources()
                if (saved.id == settings.active()?.id && ScouterService.running) ScouterService.send(this)
            }
            .setNegativeButton("Cancel", null)
        if (s != null) d.setNeutralButton("Delete") { _, _ ->
            dialog()
                .setTitle("Delete ${s.name}?")
                .setMessage(s.address)
                .setPositiveButton("Delete") { _, _ ->
                    settings.removeServer(s.id)
                    buildServers()
                    buildSources()
                    if (ScouterService.running) ScouterService.send(this)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        d.show()
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) importTestKey()
        resumed = true
        Session.listeners += listener
        Session.foreground = true
        Preview.listener = previewListener
        buildSources()
        buildServers()
        selectTab(tab)
        val keyboard = Settings(this).keyboard
        // Keyboard mode: the keys come only while this screen is in front, so it stays on (dimmed by Android).
        if (keyboard) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Settings may have changed; and while Android has not said whether keyer is near (the app new or
        // restarted), the service tries keyer's link. In keyboard mode this screen in front starts the session.
        if (keyboard || ScouterService.running || Session.near == null || BuildConfig.FLAVOR == "emulated") ScouterService.send(this)
        associate()
        show()
    }

    override fun onPause() {
        resumed = false
        Session.listeners -= listener
        Session.foreground = false
        Preview.wanted = false
        Preview.listener = null
        if (ScouterService.running) ScouterService.send(this) // keyboard mode: the session lets go after its grace
        super.onPause()
    }

    // Keyboard mode: a paired keyboard's keys go to the terminal; the phone's own buttons stay Android's.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Hotkeys.handle(this, event)) return true // Alt shortcuts first (VoxKeys takes them before us when on)
        if (Settings(this).keyboard && Keyboard.physical(event) && Keyboard.send(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // vox://server?name=…&host=…&port=…&user=…&command=… adds (or updates) a saved server once the user agrees; a
    // shortcut's "share" asks for the phone's screen. Debug builds also take adb's extras: am start -n
    // PACKAGE/scouterkit.ssh.MainActivity --es open start|stop|check|spike [extras for the spike].
    private fun handle(intent: Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "vox" && uri.host == "server") {
            setIntent(Intent(this, MainActivity::class.java))
            return addFromLink(uri)
        }
        if (intent.getBooleanExtra(EXTRA_SHARE, false)) {
            intent.removeExtra(EXTRA_SHARE)
            return shareScreen()
        }
        if (!BuildConfig.DEBUG) return
        when (intent.getStringExtra("open")) {
            "start" -> ScouterService.send(this)
            "stop" -> ScouterService.send(this, ScouterService.ACTION_STOP)
            "check" -> Thread { Native.check().lines().filter { it.isNotEmpty() }.forEach { android.util.Log.i("scouter", "check: $it") } }.start()
            "spike" -> startActivity(Intent(this, HidSpikeActivity::class.java).putExtras(intent))
        }
        intent.getStringExtra("keys")?.let { type(it) }
        if (intent.getStringExtra("open") == "encrypt") intent.getStringExtra("address")?.let { encrypt(it) }
        // The emulated flavour: --ei pitch 30 tilts both emulated arms' heads.
        if (intent.hasExtra("pitch")) for (arm in 0..1) Native.emulatedProperty(arm, Native.PROP_PITCH, intent.getIntExtra("pitch", 0))
    }

    private fun addFromLink(uri: android.net.Uri) {
        val settings = Settings(this)
        val host = uri.getQueryParameter("host").orEmpty().trim()
        val user = uri.getQueryParameter("user").orEmpty().trim()
        if (host.isEmpty() || user.isEmpty() || host.contains(' ') || user.contains(' ')) return
        val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        val same = settings.servers().firstOrNull { it.host == host && it.port == port && it.user == user }
        val s = Settings.Server(same?.id ?: Settings.newServerId(),
            uri.getQueryParameter("name")?.trim()?.ifEmpty { null } ?: same?.name ?: host, host, port, user,
            uri.getQueryParameter("command")?.trim() ?: same?.command ?: "", same?.moshPorts ?: "")
        dialog()
            .setTitle(if (same == null) "Add this server?" else "Update ${same.name}?")
            .setMessage("${s.name}\n${s.address}" + if (s.command.isNotEmpty()) "\n${s.command}" else "")
            .setPositiveButton(if (same == null) "Add" else "Update") { _, _ ->
                settings.putServer(s)
                buildServers()
                buildSources()
                selectTab(2)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Debug builds: --es open encrypt --es address ADDR connects once to a bonded device for 6 s, so the log shows
    // whether the link encrypts with the stored key (bt_btif auth_cmpl); closed at once if the bond changes.
    @android.annotation.SuppressLint("MissingPermission")
    private fun encrypt(address: String) {
        val device = getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(address)
        if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
            android.util.Log.i("scouter", "encrypt: not bonded")
            return
        }
        val main = android.os.Handler(mainLooper)
        var gatt: android.bluetooth.BluetoothGatt? = null
        val bonds = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                android.util.Log.i("scouter", "encrypt: bond changed, closing")
                gatt?.close()
                gatt = null
            }
        }
        registerReceiver(bonds, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        gatt = device.connectGatt(this, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, state: Int) {
                android.util.Log.i("scouter", "encrypt: state $state status $status bond ${g.device.bondState}")
            }
        }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        main.postDelayed({
            android.util.Log.i("scouter", "encrypt: done, bond ${device.bondState}")
            gatt?.close()
            gatt = null
            unregisterReceiver(bonds)
        }, 6000)
    }

    // Debug builds: --es keys 'ls{enter}' types as keyer would ({enter} {tab} {esc} {bs} {up} {down}
    // {left} {right} {c-x} for Ctrl+x).
    private fun type(text: String) {
        val usages = mapOf("enter" to 0x28, "esc" to 0x29, "bs" to 0x2A, "tab" to 0x2B, "right" to 0x4F,
            "left" to 0x50, "down" to 0x51, "up" to 0x52)
        Regex("\\{([a-z-]+)\\}|.", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("c-") -> Native.keyEvent(1, 0x01, name[2].code)
                name.isNotEmpty() -> usages[name]?.let { Native.keyEvent(2, 0, it) }
                else -> Native.keyEvent(1, 0, m.value.codePointAt(0))
            }
        }
    }

    // The app as keyer's companion (CompanionApp): the system's dialog, once per device while this screen lives,
    // not behind the lock screen (Android cancels it there). The next one follows when it has closed (onResume).
    private fun associate() {
        if (BuildConfig.FLAVOR == "emulated" || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val device = CompanionApp.missing(this).firstOrNull { it !in associating } ?: return
        associating += device
        CompanionApp.associate(this, device) { error -> runOnUiThread { companion = error; show() } }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == CompanionApp.REQUEST) companion = CompanionApp.result(this, if (result == RESULT_OK) data else null)
        if (request == SCREEN_REQUEST) {
            if (result == RESULT_OK && data != null) {
                startingAt = android.os.SystemClock.elapsedRealtime()
                ScouterService.sendScreen(this, result, data)
            } else note = "Screen sharing was not allowed."
            show()
        }
    }

    // What the phone's controls need first, or null.
    private fun ready(): String? {
        val settings = Settings(this)
        return when {
            ScouterService.missing(this).isNotEmpty() -> "allow Bluetooth and notifications in Settings first"
            !ScouterService.bluetoothOn(this) -> "switch Bluetooth on first"
            BuildConfig.FLAVOR != "emulated" && (settings.left.isEmpty() || settings.right.isEmpty()) ->
                "pair the glasses first: Settings, Glasses"
            else -> null
        }
    }

    // Android asks to share the entire screen (Android 14+: no single-app choice); the service gets the answer.
    @Suppress("DEPRECATION")
    private fun shareScreen() {
        ready()?.let { note = it; return show() }
        Settings(this).glassesAllowed = true
        note = null
        val projection = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val ask = if (android.os.Build.VERSION.SDK_INT >= 34)
            projection.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else projection.createScreenCaptureIntent()
        startActivityForResult(ask, SCREEN_REQUEST)
    }

    // The display off / on, while a session runs.
    private fun toggleDisplay() {
        if (!ScouterService.running || Session.showing.isEmpty()) {
            note = "start Terminal or Phone screen first"
            return show()
        }
        note = null
        ScouterService.send(this, ScouterService.ACTION_DISPLAY)
    }

    // The picture's place on the glasses (the layout editor, its presets).
    private fun place(x: Int, y: Int, w: Int, h: Int) {
        val settings = Settings(this)
        settings.screenOutX = x
        settings.screenOutY = y
        settings.screenOutW = w
        settings.screenOutH = h
    }

    // Under the preview: whether it is what the glasses show, how much each frame takes, the last check.
    private fun previewLine() {
        val (has, sent, st) = Preview.read { _, w, _, sent, stats -> Triple(w > 0, sent, stats.copyOf()) }
        fun size(b: Int) = if (b < 1024) "$b B" else String.format(java.util.Locale.US, "%.1f KB", b / 1024f)
        val approx = if (st.getOrElse(9) { 0 } == 16) "≈" else ""
        val detail = when (st.getOrElse(3) { 1 }) {
            1 -> "every pixel"
            2 -> "½ detail"
            3 -> "⅓ detail"
            else -> "¼ detail"
        }
        val line = when {
            !Session.capturing && Session.remote.isEmpty() -> "Nothing shared yet: tap Phone (screen) or a laptop's screen on Home."
            !has -> "Waiting for the first picture…"
            sent -> "On the glasses now · $approx${size(st[1])} a frame · $approx${size(st[2])}/s · $detail"
            else -> "Not on the glasses (${Session.screen}): what they would show"
        }
        previewText.text = line + (checked?.let { "\n$it" } ?: "")
    }

    // Each eye's panel read back against what was sent.
    private fun checkGlasses() {
        checked = "Checking the glasses…"
        previewLine()
        Thread {
            val r = Native.screenCheck().lines().filter { it.isNotBlank() }.joinToString(" · ")
            runOnUiThread {
                checked = r.replaceFirstChar { it.uppercase() }
                previewLine()
            }
        }.start()
    }

    private var note: String? = null // the last control's problem
    private var startingAt = 0L // a mode was tapped: "Starting…" until the session shows (or 45 s)

    // R3: in Doze the network waits for maintenance windows unless the app is exempt (keyer's association
    // exempts it).
    private fun exempt() = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun show() {
        val settings = Settings(this)
        val running = ScouterService.running && Session.showing.isNotEmpty()
        if (running) startingAt = 0L
        val starting = !running && startingAt > 0 && android.os.SystemClock.elapsedRealtime() - startingAt < 45000
        val active = settings.active()
        val remoteShown = Session.screenSource.startsWith("screen:")
        headline.text = when {
            starting -> "Starting…"
            !running -> "Ready when you are"
            Session.blank -> "Display off"
            Session.showing == Settings.SCREEN -> if (remoteShown) "${Session.source.substringBefore(" ·")} screen on the glasses"
                else "Phone screen on the glasses"
            else -> "${active?.name ?: "The"} terminal on the glasses"
        }
        val permitted = ScouterService.missing(this).isEmpty()
        val lines = mutableListOf<String>()
        if (!permitted) lines += "Allow Bluetooth and notifications in Settings."
        else if (!ScouterService.bluetoothOn(this)) lines += "Bluetooth is off: the app waits for it."
        if (settings.left.isEmpty() || settings.right.isEmpty()) lines += "Pair the glasses in Settings, Glasses."
        else if (running) lines += when (Session.glasses) {
            "ready" -> "Glasses connected" + (if (Session.left && Session.right) "" else " (one arm down)")
            "connecting" -> "Connecting to the glasses…"
            else -> "Glasses: ${Session.glasses}"
        }
        if (running && Session.showing == Settings.TERMINAL)
            lines += "Terminal (${active?.address ?: "no server"}): " + Session.mosh.replaceFirstChar { it.uppercase() }
        if (Session.capturing || remoteShown || Session.screen.startsWith("stopped") || Session.screen.startsWith("error") ||
            Session.screen.contains("screen"))
            lines += "Screen: ${Session.screen}" + if (Session.screen == "on")
                String.format(java.util.Locale.US, " · %.1f fps", Session.screenFps) else ""
        if (Session.blank) lines += "Turn it back on here, in the notification, or with your TouchBar gesture."
        if (Session.gesture.isNotEmpty()) lines += "Last gesture: ${Session.gesture}"
        if (starting) lines += "Connecting to the glasses and the server…"
        note?.let { lines += it }
        detail.text = lines.joinToString("\n").ifEmpty { "Pick what the glasses show." }

        pills.removeAllViews()
        fun pill(label: String, dot: Int) = pills.addView(statusPill(label, dot), lp(ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8) })
        val b = Session.battery
        leftGauge.set(b.getOrElse(0) { -1 }, b.getOrElse(2) { 0 } == 1, running && Session.left)
        rightGauge.set(b.getOrElse(1) { -1 }, b.getOrElse(3) { 0 } == 1, running && Session.right)
        pill("Glasses", when {
            running && Session.left && Session.right -> Ui.GOOD
            running && Session.glasses == "connecting" -> Ui.WARN
            running -> Ui.BAD
            else -> Ui.FAINT
        })
        pill("SSH", when {
            Session.mosh == "connected" -> Ui.GOOD
            Session.mosh.startsWith("error") || Session.mosh.startsWith("no contact") -> Ui.BAD
            Session.mosh == "off" -> Ui.FAINT
            else -> Ui.WARN
        })
        pill(if (Session.capturing) "Sharing" else "Screen", if (Session.capturing) Ui.ACCENT else Ui.FAINT)

        // The source on the glasses, lit.
        val current = when {
            !running || Session.blank -> ""
            Session.showing == Settings.TERMINAL -> "term:${active?.id}"
            else -> Session.screenSource.ifEmpty { Settings.PHONE }
        }
        for ((key, tile) in sourceTiles) tile.select(key == current, label(settings, key).second)
        offTile?.select(Session.blank, label(settings, OFF).second)
        stopSharing.visibility = if (Session.capturing) View.VISIBLE else View.GONE
        if (batterySwitch.isChecked != settings.battery) batterySwitch.isChecked = settings.battery
        if (forceSwitch.isChecked != settings.phoneForce) forceSwitch.isChecked = settings.phoneForce

        status.text = (if (exempt()) "" else "battery optimised · ") +
            "glasses ${Session.glasses} (L ${Session.left} R ${Session.right}" + (if (Session.shown) ", shown" else "") +
            ") · screen ${Session.screen} (${Session.screenFrames} frames) · mosh ${Session.mosh} · keyer ${Session.keyer}" +
            (companion?.let { " · companion: $it" } ?: "") + (if (Session.termux.isNotEmpty()) " · Termux ${Session.termux}" else "")
        previewLine()
        Native.snapshot()?.let { terminal.screen = TerminalView.parse(it) }
        title = terminal.screen?.title?.ifEmpty { null } ?: getString(R.string.app_name)
        val fingerprint = Session.unknownHost
        if (fingerprint != null && fingerprint != asked) {
            asked = fingerprint
            dialog()
                .setTitle("Trust this server?")
                .setCancelable(false) // Trust or Cancel, not a stray touch
                .setMessage("${active?.name ?: settings.host} (${settings.host}:${settings.port})\n$fingerprint\n\nOn the " +
                    "server, ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub shows its fingerprint.")
                .setPositiveButton("Trust") { _, _ -> ScouterService.send(this, ScouterService.ACTION_TRUST, "fingerprint" to fingerprint) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // Debug builds: a key placed with `adb shell run-as PACKAGE` in files/import_key (and import_passphrase)
    // goes into the vault, for tests without the file picker.
    private fun importTestKey() {
        val key = File(filesDir, "import_key").takeIf { it.exists() } ?: return
        val vault = Vault(this)
        vault.put(Vault.SSH_KEY, key.readBytes())
        val passphrase = File(filesDir, "import_passphrase")
        if (passphrase.exists()) vault.put(Vault.SSH_PASSPHRASE, passphrase.readBytes()) else vault.delete(Vault.SSH_PASSPHRASE)
        key.delete()
        passphrase.delete()
    }

    companion object {
        const val EXTRA_SHARE = "share" // a shortcut: ask for the phone's screen
        private const val OFF = "off"   // Home's display button
    }
}

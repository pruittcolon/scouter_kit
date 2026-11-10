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
private const val MIC_REQUEST = 8 // Car mode's phone microphone

// Vox's main screen, in tabs with icons: Glasses (what they show, live; the sources as a grid that Alt+arrows
// walk; the display, always visible, voice and the pointer; brightness), Screen (the picture the glasses get and
// how it looks), Terminal (the terminal, its tabs, the touchpad, the servers), Voice; Settings opens the rest.
// Rarely used settings fold away in sections. The session runs until the power button stops it.
class MainActivity : Activity() {
    private lateinit var terminal: TerminalView
    private lateinit var homeTerminal: TerminalView
    private lateinit var homePicture: PreviewView
    private lateinit var glasses: GlassesView
    private lateinit var linkPill: TextView
    private lateinit var powerDisc: FrameLayout
    private lateinit var powerIcon: IconView
    private var powerShown: Boolean? = null
    private var shareAway = false // the share was asked from outside the app: back to what was in front after
    private var sharingShown: Boolean? = null
    private lateinit var statusLine: TextView
    private lateinit var bannerBox: LinearLayout
    private var bannerKey = ""
    private lateinit var gridHolder: FrameLayout
    private var gridKey = ""
    private val gridTiles = mutableListOf<Triple<String?, GridTile, String>>()
    private lateinit var displayAction: RoundAction
    private lateinit var alwaysAction: RoundAction
    private lateinit var voiceAction: RoundAction
    private lateinit var pointerAction: RoundAction
    private lateinit var tunesAction: RoundAction
    private lateinit var previewButton: TextView
    private lateinit var serversBox: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private var tabStripKey: String? = null
    private lateinit var tabsState: TextView
    private lateinit var batterySwitch: Switch
    private lateinit var notesState: TextView
    private lateinit var dashPreview: android.widget.ImageView
    private lateinit var styleRow: LinearLayout
    private lateinit var weatherState: TextView
    private lateinit var sportsState: TextView
    private lateinit var calendarState: TextView
    private lateinit var calendarButton: View
    private lateinit var layoutBox: LinearLayout
    private lateinit var shareChip: TextView
    private lateinit var lookTitle: TextView
    private lateinit var lookSub: TextView
    private lateinit var forgetChip: TextView
    private lateinit var defaultChip: TextView
    private var screenBuiltFor: String? = null // the look the Screen tab's controls were made with
    private lateinit var voiceState: TextView
    private var voiceModeButton: TextView? = null // voice mode on / off (talking to the terminal)
    private lateinit var carState: TextView
    private lateinit var carVoiceText: TextView
    private lateinit var carVoiceButton: TextView
    // While Car mode's voice downloads: its progress.
    private val carVoiceTicker = object : Runnable {
        override fun run() {
            showVoice()
            if (KokoroModel.progress >= 0) content.postDelayed(this, 500)
        }
    }
    private lateinit var voiceModel: TextView
    private lateinit var modelButton: TextView
    private lateinit var micDisc: FrameLayout
    private lateinit var micIcon: IconView
    private lateinit var micHalo: View
    private var haloAnim: android.animation.Animator? = null
    private lateinit var commandsBox: LinearLayout
    private lateinit var heardText: TextView
    private var translateModel: TextView? = null // the translation model's state (Voice tab)
    private var translateButton: TextView? = null
    // While the speech model downloads: its progress on the Voice tab.
    private val modelTicker = object : Runnable {
        override fun run() {
            showVoice()
            if (VoiceModel.progress >= 0 || TranslateModel.progress >= 0) content.postDelayed(this, 500)
        }
    }
    private lateinit var previewView: PreviewView
    private lateinit var previewText: TextView
    private lateinit var content: FrameLayout
    private val pages = mutableListOf<View>()
    private val tabItems = mutableListOf<Triple<FrameLayout, IconView, TextView>>()
    private var tab = 0
    private var resumed = false
    private var checked: String? = null // the last "Check the glasses"
    private val listener: () -> Unit = { show() }
    private val previewListener: () -> Unit = {
        runOnUiThread {
            previewView.refresh()
            homePicture.refresh()
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
        screenBuiltFor = Looks.key
        pages += terminalPage(settings)
        pages += voicePage(settings)
        pages += tvPage()
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selectTab(getPreferences(MODE_PRIVATE).getInt("tab", 0), animate = false)
        handle(intent)
    }

    // A page: a dark scrolling column.
    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(column)
        }
        return scroll to column
    }

    // The tabs at the bottom: an icon in a pill (lit when chosen) and its name; Settings opens its own screen.
    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(6), dp(8), dp(6), dp(10))
        }
        val items = listOf("glasses" to "Glasses", "screen" to "Screen", "terminal" to "Terminal", "mic" to "Voice",
            "tv" to "TV", "gear" to "Settings")
        for ((i, item) in items.withIndex()) {
            val (icon, name) = item
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { if (i == items.size - 1) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) else selectTab(i) }
            }
            val pill = FrameLayout(this)
            val glyph = IconView(this, icon, 22, Ui.MUTED)
            pill.addView(glyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            cell.addView(pill, LinearLayout.LayoutParams(dp(58), dp(32)))
            val label = text(name, 11.5f, Ui.MUTED, Ui.BOLD).apply { gravity = Gravity.CENTER }
            cell.addView(label, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            tabItems += Triple(pill, glyph, label)
            bar.addView(cell, lp(0, weight = 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { setBackgroundColor(Ui.STROKE) }, lp(h = 1))
            addView(bar, lp())
        }
    }

    private fun selectTab(i: Int, animate: Boolean = true) {
        val was = tab
        tab = i.coerceIn(0, pages.size - 1)
        for ((k, p) in pages.withIndex()) p.visibility = if (k == tab) View.VISIBLE else View.GONE
        if (animate && was != tab) pages[tab].apply {
            alpha = 0f
            translationY = dp(10).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        for ((k, item) in tabItems.withIndex()) {
            val (pill, glyph, label) = item
            val on = k == tab
            pill.background = if (on) accentGradient(dp(16).toFloat()) else null
            glyph.color = if (on) Ui.ON_ACCENT else Ui.MUTED
            label.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
        }
        getPreferences(MODE_PRIVATE).edit().putInt("tab", tab).apply()
        wantPreview()
        if (tab == 1) previewView.refresh()
        if (tab == TV_TAB) tv?.refresh()
    }

    // The screen's picture is made only while a tab shows it (Screen, or Glasses while a screen is on them).
    private fun wantPreview() {
        Preview.wanted = resumed && (tab == 1 || (tab == 0 && Session.showing == Settings.SCREEN))
    }

    // ------------------------------------------------ TV (TvPage, as Settings' TV remote)

    private var tv: TvPage? = null
    private val TV_TAB = 4

    private fun tvPage(): View {
        val (scroll, page) = scrollPage()
        tv = TvPage(this, page)
        return scroll
    }

    // ------------------------------------------------ Glasses (home)

    private fun homePage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        // The name, the glasses' link, and the power: start, or stop everything.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        top.addView(text("Vox", 30f, Ui.TEXT, Ui.BLACK).apply { letterSpacing = -0.02f }, lp(0, weight = 1f))
        linkPill = statusPill("Glasses", Ui.FAINT)
        top.addView(linkPill, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        powerDisc = FrameLayout(this).apply { setOnClickListener { power() } }
        powerIcon = IconView(this, "power", 22, Ui.TEXT)
        powerDisc.addView(powerIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        top.addView(powerDisc, LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(top)

        // What the glasses show, live.
        homeTerminal = TerminalView(this).apply {
            tint = GLASS
            interactive = false
            fit = true
        }
        homePicture = PreviewView(this)
        glasses = GlassesView(this, homeTerminal, homePicture).apply {
            setOnClickListener { selectTab(if (Session.showing == Settings.SCREEN) 1 else 2) }
        }
        page.addView(glasses, lp().apply { topMargin = dp(16) })
        statusLine = text("", 13f, Ui.MUTED).apply { setPadding(dp(4), dp(10), dp(4), 0) }
        page.addView(statusLine)
        bannerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(bannerBox)

        // The sources as a grid: the machines across, terminal above screen (as Alt+arrows walk it).
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(22), dp(4), dp(10))
        }
        head.addView(text("SWITCH", 11.5f, Ui.MUTED, Ui.BOLD).apply { letterSpacing = 0.14f }, lp(0, weight = 1f))
        head.addView(text("Alt + ← → ↑ ↓", 12f, Ui.FAINT, Ui.BOLD))
        page.addView(head)
        gridHolder = FrameLayout(this)
        page.addView(gridHolder)

        // The everyday switches.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(22), 0, dp(4))
        }
        displayAction = RoundAction(this, "power", "Display") { choose(OFF) }
        alwaysAction = RoundAction(this, "eye", "Always on") {
            val s = Settings(this)
            s.phoneForce = !s.phoneForce
            if (ScouterService.running) ScouterService.send(this)
            show()
        }
        voiceAction = RoundAction(this, "mic", "Voice") { ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "listen") }
        pointerAction = RoundAction(this, "pointer", "Pointer") {
            ScouterService.send(this, ScouterService.ACTION_POINTER, "cmd" to "toggle")
        }
        tunesAction = RoundAction(this, "spark", "Tunes") { ScouterService.send(this, ScouterService.ACTION_TUNES) }
        for (a in listOf(displayAction, alwaysAction, voiceAction, pointerAction, tunesAction)) actions.addView(a, lp(0, weight = 1f))
        page.addView(actions)
        previewButton = button("▶  Preview on the glasses", 0) {
            ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to "demo")
        }
        page.addView(previewButton, lp().apply { topMargin = dp(16) })

        val light = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(IconView(this, "sun", 22, Ui.ACCENT), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        row.addView(slider("Brightness", 0, 42, settings.brightness, { "${it * 100 / 42}%" }) { v ->
            settings.brightness = v
            Thread { Native.brightness(v) }.start()
        }, lp(0, weight = 1f))
        light.addView(row)
        page.addView(light, lp().apply { topMargin = dp(14) })
        return scroll
    }

    // ------------------------------------------------ Screen

    private fun screenPage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Screen", "The picture the glasses get, and its look"))
        val shown = card()
        previewView = PreviewView(this).apply { white = settings.previewWhite }
        shown.addView(previewView)
        previewText = text("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) }
        shown.addView(previewText)
        shareChip = chip("Share the phone's screen", true) {
            if (Session.capturing) ScouterService.send(this, ScouterService.ACTION_STOP_SHARING) else shareScreen()
        }
        lateinit var colour: TextView
        colour = chip(if (settings.previewWhite) "Panel green" else "White", false) {
            settings.previewWhite = !settings.previewWhite
            previewView.white = settings.previewWhite
            colour.text = if (settings.previewWhite) "Panel green" else "White"
        }
        shown.addView(chipRow(shareChip, colour, chip("Check the glasses", false) { checkGlasses() }))
        page.addView(shown)

        // The look per app: whose look the settings below are, kept as they change.
        val per = card("Look for")
        lookTitle = text("", 18f, Ui.TEXT, Ui.BOLD)
        lookSub = text("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(4)) }
        per.addView(lookTitle)
        per.addView(lookSub)
        forgetChip = chip("Forget its look", false) {
            Looks.forget(settings, Looks.key)
            rebuildScreen()
            show()
        }
        defaultChip = chip("Use for every app", false) {
            Looks.makeDefault(settings, Looks.key)
            note = "This look is now the default for every app without its own."
            show()
        }
        per.addView(chipRow(forgetChip, defaultChip))
        per.addToggle(toggle("Remember the look per app", "Each phone app and laptop window keeps the look you set while " +
            "it shows, and gets it back when it shows again", settings.looksPerApp) {
            settings.looksPerApp = it
            if (!it) Looks.key = ""
            if (ScouterService.running) ScouterService.send(this)
            show()
        })
        page.addView(per)

        // The look: presets, then the two sliders that matter most; the rest folded.
        val look = page.section("Look", "screen_look", "contrast", open = true, hint = "Black is see-through on the glasses.")
        lateinit var invertBar: android.widget.SeekBar
        lateinit var gammaBar: android.widget.SeekBar
        lateinit var contrastBar: android.widget.SeekBar
        lateinit var thresholdBar: android.widget.SeekBar
        fun bar(v: View): android.widget.SeekBar = (v as LinearLayout).getChildAt(1) as android.widget.SeekBar
        val presets = listOf(
            "Dark mode" to intArrayOf(100, 100, 60, 128), "Soft" to intArrayOf(70, 110, 40, 128),
            "As on the phone" to intArrayOf(0, 100, 0, 128), "High contrast" to intArrayOf(100, 140, 100, 150),
            "Bright text" to intArrayOf(100, 70, 80, 100))
        val presetChips = mutableListOf<TextView>()
        fun markPreset() = presets.forEachIndexed { i, (_, v) ->
            val on = settings.screenInvertPct == v[0] && settings.screenGamma == v[1] && settings.screenContrast == v[2] &&
                settings.screenThreshold == v[3]
            val r = dp(18).toFloat()
            presetChips[i].background = ripple(if (on) accentGradient(r) else rounded(Ui.RAISED, r, dp(1), Ui.STROKE), r)
            presetChips[i].setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT)
        }
        fun lookPreset(v: IntArray) {
            settings.screenInvertPct = v[0]
            settings.screenGamma = v[1]
            settings.screenContrast = v[2]
            settings.screenThreshold = v[3]
            invertBar.progress = v[0]
            gammaBar.progress = v[1]
            contrastBar.progress = v[2]
            thresholdBar.progress = v[3]
            markPreset()
        }
        for ((name, v) in presets) presetChips += chip(name, false) { lookPreset(v) }
        look.addView(chipRow(*presetChips.toTypedArray()))
        val invertRow = slider("Invert", 0, 100, settings.screenInvertPct, { "$it%" }, { settings.screenInvertPct = it }) {
            settings.screenInvertPct = it
            markPreset()
        }
        invertBar = bar(invertRow)
        look.addView(invertRow)
        val contrastRow = slider("Contrast boost", 0, 100, settings.screenContrast, { "$it%" }, { settings.screenContrast = it }) {
            settings.screenContrast = it
            markPreset()
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
        }, { settings.screenGamma = it }) {
            settings.screenGamma = it
            markPreset()
        }
        gammaBar = bar(gammaRow)
        more.addView(gammaRow)
        val thresholdRow = slider("Black & white cut-off", 10, 245, settings.screenThreshold, { "$it" },
            { settings.screenThreshold = it }) {
            settings.screenThreshold = it
            markPreset()
        }
        thresholdBar = bar(thresholdRow)
        more.addView(thresholdRow)
        more.addToggle(toggle("Sharpen", "Crisper text", settings.screenSharpen) { settings.screenSharpen = it })
        more.addToggle(toggle("Dither", "Black & white: greys as fine patterns (photos, video; more to send)", settings.screenDither) {
            settings.screenDither = it
        })
        more.addToggle(toggle("Steady", "Pixels change only when clearly brighter or darker: no shimmer, less to send", settings.screenSteady) {
            settings.screenSteady = it
        })
        more.addToggle(toggle("Clean edges", "Black and white: lone specks go, pinholes fill", settings.screenClean) {
            settings.screenClean = it
        })
        lateinit var moreChip: TextView
        moreChip = chip("More", false) {
            android.transition.TransitionManager.beginDelayedTransition(page)
            more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            moreChip.text = if (more.visibility == View.VISIBLE) "Less" else "More"
        }
        look.addView(chipRow(moreChip))
        look.addView(more)
        markPreset()
        // Android's own text modes, to try: plain large text (Google's Reading mode), or bolder outlined text
        // everywhere (Accessibility). Crisp text reads better on the glasses and is less to send.
        look.addView(subhead("Text modes on the phone"))
        look.addView(chipRow(
            chip("Reading mode", false) {
                val pkg = "com.google.android.accessibility.reader"
                val open = packageManager.getLaunchIntentForPackage(pkg)
                runCatching { startActivity(open ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))) }
                    .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) } }
            },
            chip("Text contrast", false) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
        ))

        val where = page.section("Place", "screen_place", "place",
            hint = "Where it shows: drag to move, drag the corner to resize, double-tap to fill.")
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
            chip("Centre", false) { editor.set(25, 25, 50, 50) },
        ))
        where.addView(slider("Zoom", 100, 400, settings.screenZoom, { String.format(java.util.Locale.US, "%.1f×", it / 100f) },
            { settings.screenZoom = it }) { settings.screenZoom = it })
        where.addView(slider("Pan across", 0, 100, settings.screenPanX, { "$it%" }, { settings.screenPanX = it }) { settings.screenPanX = it })
        where.addView(slider("Pan down", 0, 100, settings.screenPanY, { "$it%" }, { settings.screenPanY = it }) { settings.screenPanY = it })
        where.addView(segmented(listOf("0" to "Upright", "90" to "↻ 90°", "180" to "180°", "270" to "↺ 90°"),
            settings.screenRotate.toString()) { settings.screenRotate = it.toInt() })
        // The screen's bars cut away first (the clock and icons change often and are rarely wanted).
        fun pct(v: Int) = if (v == 0) "none" else String.format(java.util.Locale.US, "%.1f%%", v / 10f)
        val cutTop = slider("Cut the top", 0, 150, settings.screenCropTop, { pct(it) }, { settings.screenCropTop = it }) {
            settings.screenCropTop = it
        }
        val cutBottom = slider("Cut the bottom", 0, 150, settings.screenCropBottom, { pct(it) }, { settings.screenCropBottom = it }) {
            settings.screenCropBottom = it
        }
        fun cut(top: Int, bottom: Int) {
            settings.screenCropTop = top
            settings.screenCropBottom = bottom
            ((cutTop as LinearLayout).getChildAt(1) as android.widget.SeekBar).progress = top
            ((cutBottom as LinearLayout).getChildAt(1) as android.widget.SeekBar).progress = bottom
        }
        where.addView(subhead("Bars"))
        where.addView(chipRow(chip("The phone's bars", false) { cut(30, 22) }, chip("None", false) { cut(0, 0) }))
        where.addView(cutTop)
        where.addView(cutBottom)

        val size = page.section("Speed and detail", "screen_speed", "speed",
            hint = "Less to send is faster on the glasses and lighter on both batteries.")
        size.addView(subhead("Greys"))
        size.addView(segmented(listOf("2" to "B&W · fast", "4" to "4", "8" to "8", "16" to "16"),
            settings.screenLevels.toString()) { settings.screenLevels = it.toInt() })
        size.addView(subhead("Detail"))
        size.addView(segmented(listOf("0" to "Auto", "1" to "Full", "2" to "½", "3" to "⅓", "4" to "¼"),
            settings.screenDetail.toString()) { settings.screenDetail = it.toInt() })
        size.addView(slider("Auto: a moving frame at most", 1, 32, settings.screenBudget, { "$it KB" },
            { settings.screenBudget = it }) { settings.screenBudget = it })
        size.addView(slider("Frames a second at most", 1, 30, settings.screenFps, { "$it" }) { settings.screenFps = it })
        size.addView(subhead("Laptop screen: pixels asked for"))
        size.addView(segmented(listOf("640" to "640", "960" to "960", "1280" to "1280"), settings.remoteWidth.toString()) {
            settings.remoteWidth = it.toInt()
        })

        val dark = page.section("Going dark", "screen_dark", "dark")
        dark.addToggle(toggle("While the phone is locked", "With the phone's screen on the glasses", settings.offWhenLocked) {
            settings.offWhenLocked = it
        })
        dark.addToggle(toggle("In the charging case", "A lit panel slows charging", settings.screenHideCase) {
            settings.screenHideCase = it
        })
        dark.addToggle(toggle("When folded", "Uses the arms' wear guess, which can be wrong", settings.screenHideFolded) {
            settings.screenHideFolded = it
        })
        return scroll
    }

    // ------------------------------------------------ Terminal

    private fun terminalPage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Terminal", "Your servers, its tabs and the pointer"))
        val got = card()
        terminal = TerminalView(this)
        got.addView(clipFrame(terminal, 240))
        // Its tabs (tmux windows): tap one to show it, hold to close it; + a new one; Claude's and Cursor's.
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        got.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip)
        }, lp().apply { topMargin = dp(10) })
        tabsState = text("", 12f, Ui.FAINT).apply { setPadding(dp(2), dp(6), 0, 0) }
        got.addView(tabsState)
        page.addView(got)

        val point = card("Touchpad")
        point.addView(Touchpad(this), lp().apply { topMargin = dp(2) })
        page.addView(point)

        val servers = page.section("Servers", "term_servers", "server", open = true,
            hint = "Switch on the Glasses tab. Tap one to edit it.")
        serversBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servers.addView(serversBox)
        servers.addView(button("Add a server") { editServer(null) })

        val sits = page.section("On the glasses", "term_glasses", "glasses")
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
        batterySwitch = toggle("Battery icons", "Each eye's battery level beside the terminal", settings.battery) {
            settings.battery = it
            Thread { Native.setBattery(it) }.start()
        }
        sits.addToggle(batterySwitch)
        sits.addToggle(toggle("Mouse in tmux", "The pointer's clicks and scrolling reach tmux (tabs, panes, history)", settings.tmuxMouse) {
            settings.tmuxMouse = it
            tab(if (it) "mouse" else "mouse_off")
        })
        sits.addToggle(toggle("Transitions", "A half-second animation on the glasses when you switch", settings.animations) {
            settings.animations = it
            lookUpChanged()
        })

        val view = page.section("Look-up screen", "term_lookup", "eye", open = true,
            hint = "The strip above the terminal when you look up. Pick a style, or make your own.")
        dashPreview = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        view.addView(dashPreview, lp().apply { topMargin = dp(4); bottomMargin = dp(6) })
        view.addView(subhead("Style"))
        styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        view.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(styleRow)
        }, lp().apply { topMargin = dp(4) })
        layoutBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        view.addView(layoutBox)

        // The widgets' own settings: the weather's city and units, the calendar's permission.
        view.addView(subhead("Weather"))
        val city = field("City", settings.weatherCity, "e.g. Chicago")
        fun saveCity() {
            val v = city.text.toString().trim()
            if (v == settings.weatherCity) return
            settings.weatherCity = v
            weatherState.text = "Looking up the weather…"
            Weather.refresh(settings, force = true) { runOnUiThread { show(); lookUpChanged() } }
        }
        city.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        city.setOnEditorActionListener { _, _, _ -> saveCity(); false }
        city.setOnFocusChangeListener { _, has -> if (!has) saveCity() }
        view.addField(city)
        view.addView(segmented(listOf("" to "Auto", "f" to "°F", "c" to "°C"), settings.weatherUnit) {
            settings.weatherUnit = it
            Weather.refresh(settings, force = true) { runOnUiThread { show(); lookUpChanged() } }
        })
        weatherState = text("", 12.5f, Ui.FAINT)
        view.addView(weatherState)
        view.addView(subhead("Game score"))
        val team = field("Team", Sports.team(settings), "NFL letters, e.g. NE, GB, KC")
        fun saveTeam() {
            val v = team.text.toString().trim().uppercase(java.util.Locale.US)
            if (v == settings.sportsTeam) return
            settings.sportsTeam = v
            sportsState.text = "Looking for the game…"
            Sports.refresh(this, settings, force = true) { runOnUiThread { show(); lookUpChanged() } }
        }
        team.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        team.setOnEditorActionListener { _, _, _ -> saveTeam(); false }
        team.setOnFocusChangeListener { _, has -> if (!has) saveTeam() }
        view.addField(team)
        sportsState = text("", 12.5f, Ui.FAINT)
        view.addView(sportsState)
        Sports.refresh(this, settings) { runOnUiThread { show() } }
        view.addView(subhead("Calendar"))
        calendarState = text("", 12.5f, Ui.FAINT)
        view.addView(calendarState)
        calendarButton = button("Allow the calendar") {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), CALENDAR_REQUEST)
        }
        view.addView(calendarButton)
        view.addView(subhead("Size and fade"))
        view.addToggle(toggle("Graphical dashboard", "Off: the header as two lines of text", settings.dashboard) {
            settings.dashboard = it
            showDash()
            lookUpChanged()
        })
        view.addView(slider("Dashboard height", 56, 140, settings.dashHeight, { "$it px" }, {
            settings.dashHeight = it
            showDash()
        }) { v ->
            settings.dashHeight = v
            buildStyles()
            lookUpChanged()
        })
        view.addView(slider("Fade in", 0, 800, settings.fadeMs, { if (it == 0) "off" else "$it ms" }) { v ->
            settings.fadeMs = v
            lookUpChanged()
        })
        view.addView(subhead("Text header (graphical off)"))
        view.addToggle(toggle("Time, date and batteries", null, settings.lookUpClock) {
            settings.lookUpClock = it
            showDash()
            lookUpChanged()
        })
        view.addToggle(toggle("Phone notifications", "The newest three; the pointer opens them", settings.lookUpNotifications) {
            settings.lookUpNotifications = it
            showDash()
            lookUpChanged()
        })
        notesState = text("", 12.5f, Ui.FAINT)
        view.addView(notesState)

        val keys = page.section("Keys", "term_keys", "keyboard")
        keys.addView(text("Alt+M the pointer, then arrows (Shift: faster), Enter or Space to click, Shift+Enter a right " +
            "click, Page Up / Down to scroll, Escape to end it. Alt+T a new tab, Alt+[ / ] the tab before / after, Alt+W " +
            "closes the tab shown. Voice: \"pointer\", \"click\", \"new tab\", \"next tab\", \"tab 2\", \"Claude tab\".",
            13f, Ui.MUTED))
        if (BuildConfig.FLAVOR == "emulated") page.addView(PanelView(this))
        return scroll
    }

    private fun tab(action: String) = ScouterService.send(this, ScouterService.ACTION_TABS, "tab" to action)

    // The look-up screen's styles as pictures in the glasses' green (tap one), then the user's own layout's editor
    // while that is the style.
    private fun buildStyles() {
        val settings = Settings(this)
        val h = settings.dashHeight.coerceIn(56, 140) and 1.inv()
        val w = Dashboard.width(settings)
        styleRow.removeAllViews()
        for ((key, name) in Dashboard.STYLES) {
            val on = settings.dashStyle == key
            val r = dp(14).toFloat()
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(8))
                background = ripple(rounded(if (on) Ui.RAISED else 0, r, dp(if (on) 2 else 1), if (on) Ui.ACCENT else Ui.STROKE), r)
                setOnClickListener {
                    settings.dashStyle = key
                    buildStyles()
                    showDash()
                    lookUpChanged()
                }
            }
            val picture = Dashboard.render(this, settings, w, h, null, false, null, key).preview
            cell.addView(android.widget.ImageView(this).apply {
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
                setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, picture))
            }, LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.addView(text(name, 12.5f, if (on) Ui.TEXT else Ui.MUTED, Ui.BOLD).apply { setPadding(dp(2), dp(6), 0, 0) })
            styleRow.addView(cell, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
        buildLayoutEditor()
    }

    // Your own layout: the tab bar on or off, then every widget: on or off, its place (up, down) and, for the
    // ones that share the room, how wide.
    private fun buildLayoutEditor() {
        val settings = Settings(this)
        layoutBox.removeAllViews()
        if (settings.dashStyle != "custom") return
        val lay = Dashboard.custom(settings)
        fun save(tabs: Boolean, list: List<Dashboard.Widget>) = layoutBox.post {
            Dashboard.saveCustom(settings, Dashboard.Layout(tabs, list))
            buildStyles()
            showDash()
            lookUpChanged()
        }
        layoutBox.addView(subhead("Your layout"))
        layoutBox.addToggle(toggle("Tab bar", "The terminal's tabs, + and PHONE along the top", lay.tabs) { save(it, lay.widgets) })
        val on = lay.widgets
        val off = Dashboard.WIDGETS.map { it.first }.filter { t -> on.none { it.type == t } }
        val names = Dashboard.WIDGETS.toMap()
        fun row(type: String, index: Int) {
            val active = index >= 0
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            line.addView(Switch(this).apply {
                isChecked = active
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.ACCENT, 0xFFC9CED8.toInt()))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(0x665EEAD4, Ui.STROKE))
                setOnCheckedChangeListener { _, checked ->
                    save(lay.tabs, if (checked) on + Dashboard.Widget(type) else on.filter { it.type != type })
                }
            })
            line.addView(text(names[type] ?: type, 14.5f, if (active) Ui.TEXT else Ui.MUTED, Ui.BOLD).apply { setPadding(dp(8), 0, 0, 0) },
                lp(0, weight = 1f))
            if (active) {
                val wd = on[index]
                if (type == Dashboard.NOTES || type == Dashboard.MEDIA) line.addView(chip("×${wd.weight}", false) {
                    save(lay.tabs, on.map { if (it.type == type) Dashboard.Widget(type, wd.weight % 3 + 1) else it })
                }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
                fun move(by: Int) {
                    val j = index + by
                    if (j < 0 || j >= on.size) return
                    val l = on.toMutableList()
                    l[index] = l[j].also { l[j] = l[index] }
                    save(lay.tabs, l)
                }
                line.addView(chip("↑", false) { move(-1) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
                line.addView(chip("↓", false) { move(1) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            layoutBox.addView(line)
        }
        for ((i, wd) in on.withIndex()) row(wd.type, i)
        for (t in off) row(t, -1)
        layoutBox.addView(text("Now playing needs notification access (Settings, Access): Spotify, YouTube and other " +
            "players show there, with buttons the pointer clicks.", 12f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun lookUpChanged() {
        if (ScouterService.running) ScouterService.send(this, ScouterService.ACTION_LOOK_UP)
    }

    // The tab strip again when the tabs changed.
    private fun buildTabStrip() {
        val list = Session.tabList
        val key = list.joinToString("|") { "${it.index}:${it.name}:${it.active}" }
        if (key == tabStripKey) return
        tabStripKey = key
        tabStrip.removeAllViews()
        fun add(v: View) = tabStrip.addView(v, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        for (t in list) add(chip("${t.index}  ${t.name}", t.active) { tab("select:${t.index}") }.apply {
            setOnLongClickListener {
                tab("close:${t.index}")
                true
            }
        })
        add(chip("+", false) { tab("new") })
        add(chip("Claude", false) { tab("claude") })
        add(chip("Cursor", false) { tab("cursor") })
    }

    // ------------------------------------------------ Voice

    private fun voicePage(settings: Settings): View {
        val (scroll, page) = scrollPage()
        page.addView(header("Voice", "Commands through the glasses' microphone"))
        val now = card()
        val stage = FrameLayout(this)
        micHalo = View(this).apply {
            background = accentGradient(dp(60).toFloat())
            alpha = 0f
        }
        stage.addView(micHalo, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        micDisc = FrameLayout(this).apply {
            setOnClickListener { ScouterService.send(this@MainActivity, ScouterService.ACTION_VOICE, "mode" to "listen") }
        }
        micIcon = IconView(this, "mic", 40, Ui.TEXT)
        micDisc.addView(micIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stage.addView(micDisc, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        now.addView(stage, lp(h = dp(132)))
        voiceState = text("", 17f, Ui.TEXT, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        now.addView(voiceState)
        now.addView(text("Tap, double-tap the right TouchBar, or Alt+Space.", 12.5f, Ui.FAINT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        })
        page.addView(now)

        // Voice mode: everything said is typed into the terminal on the glasses (Claude's prompt) until it is off.
        val talk = card("Voice mode", "Talk to the terminal: what you say is typed into it (Claude's prompt), a " +
            "strip at the bottom of the glasses shows it is on. Say \"send\" for Enter, \"scratch that\" to take " +
            "the last phrase back, \"escape\" to stop Claude, \"option one\" to answer it, \"voice off\" to end. " +
            "Alt+V on the keyboard.")
        voiceModeButton = button(if (Session.voiceMode) "Voice mode off" else "Voice mode on", 0) {
            ScouterService.send(this@MainActivity, ScouterService.ACTION_VOICE, "mode" to "voicemode")
        }
        talk.addView(voiceModeButton, lp().apply { topMargin = dp(8) })
        page.addView(talk)

        val model = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(IconView(this, "download", 22, Ui.ACCENT), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("Speech model", 15f, Ui.TEXT, Ui.BOLD))
        voiceModel = text("", 12.5f, Ui.MUTED)
        texts.addView(voiceModel)
        row.addView(texts, lp(0, weight = 1f))
        modelButton = chip("Download", true) {
            if (VoiceModel.ready(this)) dialog().setTitle("Delete the speech model?")
                .setMessage("Voice commands need it; you can download it again.")
                .setPositiveButton("Delete") { _, _ ->
                    VoiceModel.delete(this)
                    showVoice()
                }
                .setNegativeButton("Cancel", null).show()
            else download()
        }
        row.addView(modelButton)
        model.addView(row)
        page.addView(model)

        // Live captions (Parakeet) and translation into English (Whisper small): on the glasses, Alt+E / Alt+I.
        val listen = card("Captions and translation", "What is said around you, on the glasses as it is said: Alt+E " +
            "captions (the speech model above), Alt+I a translation into English from any language (its own model, " +
            "below). The phone's microphone hears the room: allow it once. Each session's words are kept in Vox's files.")
        val tRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tTexts.addView(text("Translation model", 15f, Ui.TEXT, Ui.BOLD))
        translateModel = text("", 12.5f, Ui.MUTED).also { tTexts.addView(it) }
        tRow.addView(tTexts, lp(0, weight = 1f))
        translateButton = chip("Download", true) {
            if (TranslateModel.ready(this)) dialog().setTitle("Delete the translation model?")
                .setMessage("Translation needs it; you can download it again.")
                .setPositiveButton("Delete") { _, _ ->
                    TranslateModel.delete(this)
                    showVoice()
                }
                .setNegativeButton("Cancel", null).show()
            else downloadTranslate()
        }.also { tRow.addView(it) }
        listen.addView(tRow, lp().apply { topMargin = dp(8) })
        fun go(what: String) {
            if (!ScouterService.running)
                return android.widget.Toast.makeText(this, "Start Vox first (Glasses tab)", android.widget.Toast.LENGTH_SHORT).show()
            ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to what)
        }
        listen.addView(chipRow(chip("Captions (Alt+E)", true) { go("captions") }, chip("Translate (Alt+I)", false) { go("translate") }))
        listen.addView(chipRow(chip("Allow the phone's microphone", false) { askMic() }))
        page.addView(listen)

        // Car mode (Car.kt): Claude by voice through the car.
        val carBox = page.section("Car mode", "voice_car", "mic", open = true)
        carState = text("", 15f, Ui.TEXT, Ui.BOLD)
        carBox.addView(carState)
        carBox.addView(text("In the car, press Next on the steering wheel, wait for the chime, talk, then press Next " +
            "again to send. Claude's answer is read aloud through the car; the music pauses meanwhile and comes back " +
            "after. Next while Claude talks: ask again. Play / pause stops the answer, Previous hears it again. " +
            "Previous otherwise starts a new conversation (a new Claude tab). It listens on the phone's microphone (or " +
            "the glasses'); while Car mode is on the glasses' display stays off, since you are driving.", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
        carBox.addView(segmented(listOf("auto" to "When the car connects", "on" to "Always", "off" to "Off"), settings.carMode) {
            settings.carMode = it
            ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "car_check")
            showVoice()
        })
        carBox.addView(text("Listen with", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        carBox.addView(segmented(listOf("phone" to "The phone's microphone", "glasses" to "The glasses'"), settings.carMic) {
            settings.carMic = it
            if (it == "phone") askMic()
        })
        carBox.addView(text("Talk to", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        carBox.addView(segmented(listOf("terminal" to "This Claude (the terminal)", "chat" to "A separate chat"),
            settings.carTarget) { settings.carTarget = it })
        val servers = settings.servers()
        if (servers.size > 1) {
            carBox.addView(text("Claude runs on", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
            val chosen = servers.firstOrNull { it.id == settings.carServer } ?: servers.firstOrNull { it.local } ?: settings.active()
            carBox.addView(segmented(servers.map { it.id to it.name }, chosen?.id ?: "") { settings.carServer = it })
        }
        // The voice: Kokoro (downloaded once), its speaker, or the phone's own.
        carBox.addView(text("Voice", 13f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        carVoiceText = text("", 12.5f, Ui.MUTED)
        voiceRow.addView(carVoiceText, lp(0, weight = 1f))
        carVoiceButton = chip("Download", true) {
            if (KokoroModel.ready(this)) dialog().setTitle("Delete the car voice?")
                .setMessage("Car mode uses the phone's own voice then; you can download it again.")
                .setPositiveButton("Delete") { _, _ ->
                    KokoroModel.delete(this)
                    ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "car_voice")
                    showVoice()
                }
                .setNegativeButton("Cancel", null).show()
            else downloadCarVoice()
        }
        voiceRow.addView(carVoiceButton)
        carBox.addView(voiceRow)
        carBox.addView(segmented(KokoroModel.VOICES.take(5).map { (id, name) -> id.toString() to name },
            settings.carSpeaker.toString()) { settings.carSpeaker = it.toInt(); settings.carVoice = "kokoro" })
        carBox.addView(segmented(KokoroModel.VOICES.drop(5).map { (id, name) -> id.toString() to name } +
            listOf("android" to "Phone's"), if (settings.carVoice == "android") "android" else settings.carSpeaker.toString()) {
            if (it == "android") settings.carVoice = "android" else {
                settings.carSpeaker = it.toInt()
                settings.carVoice = "kokoro"
            }
        })
        carBox.addView(slider("Speaking speed", 60, 160, settings.carRate, { "$it%" }) { settings.carRate = it })
        carBox.addView(chipRow(
            chip("Test the sound", true) { ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "car_test") },
            chip("New conversation", false) { ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "car_new") },
        ))

        val cmds = page.section("Your commands", "voice_cmds", "list", open = true)
        commandsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cmds.addView(commandsBox)
        cmds.addView(button("Add a command") { addCommand() })

        val how = page.section("How it listens", "voice_how", "mic")
        how.addToggle(toggle("Realtime words", "The words show while you speak (more of the phone's CPU)", settings.voiceRealtime) {
            settings.voiceRealtime = it
        })
        how.addToggle(toggle("Always listening", "Say \"Vox\" first: \"Vox, laptop screen\". The glasses' microphone stays " +
            "on (their battery; the right arm shows a dot).", settings.voiceAlways) {
            settings.voiceAlways = it
            ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "always")
        })
        how.addToggle(toggle("Show on the glasses", "A band at the top while you use voice", settings.voiceBand) {
            settings.voiceBand = it
        })
        how.addView(slider("The band stays", 1, 15, settings.voiceBandSecs, { "$it s" }) { settings.voiceBandSecs = it })

        val say = page.section("What you can say", "voice_help", "info")
        say.addView(text(VoiceCommands.HELP, 13f, Ui.MUTED))

        val got = page.section("Heard", "voice_heard", "list")
        heardText = text("", 13f, Ui.MUTED)
        got.addView(heardText)
        return scroll
    }

    // The dashboard as the glasses get it (their green), at the width the terminal has now.
    private fun showDash() {
        if (!::dashPreview.isInitialized) return
        val settings = Settings(this)
        val h = settings.dashHeight.coerceIn(56, 140) and 1.inv()
        dashPreview.visibility = if (settings.dashboard) View.VISIBLE else View.GONE
        if (!settings.dashboard) return
        val voice = if (Session.listening || Session.voice.isNotEmpty()) Session.voice.ifEmpty { null } else null
        val f = Dashboard.render(this, settings, Dashboard.width(settings), h, voice, Session.listening)
        dashPreview.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, f.preview).apply { setFilterBitmap(false) })
    }

    private fun showVoice() {
        if (!::voiceState.isInitialized) return
        fun mb(b: Long) = "${b / 1_000_000} MB"
        val ready = VoiceModel.ready(this)
        voiceModel.text = when {
            !VoiceModel.available -> "This build has no speech engine."
            VoiceModel.progress >= 0 -> "Downloading… ${mb(VoiceModel.progress)} of ${mb(VoiceModel.bytes)}"
            ready -> "Parakeet, on this phone, offline · ${mb(VoiceModel.bytes)}"
            VoiceModel.problem != null -> "Not downloaded: ${VoiceModel.problem}"
            else -> "Not downloaded yet · ${mb(VoiceModel.bytes)}, Wi-Fi is best"
        }
        modelButton.visibility = if (VoiceModel.available && VoiceModel.progress < 0) View.VISIBLE else View.GONE
        modelButton.text = if (ready) "Delete" else "Download"
        val r = dp(18).toFloat()
        modelButton.background = ripple(if (ready) rounded(Ui.RAISED, r, dp(1), Ui.STROKE) else accentGradient(r), r)
        modelButton.setTextColor(if (ready) Ui.TEXT else Ui.ON_ACCENT)
        val tReady = TranslateModel.ready(this)
        translateModel?.text = when {
            !VoiceModel.available -> "This build has no speech engine."
            TranslateModel.progress >= 0 -> "Downloading… ${mb(TranslateModel.progress)} of ${mb(TranslateModel.bytes)}"
            tReady -> "Whisper small, on this phone, offline · ${mb(TranslateModel.bytes)}"
            TranslateModel.problem != null -> "Not downloaded: ${TranslateModel.problem}"
            else -> "Not downloaded yet · ${mb(TranslateModel.bytes)}, Wi-Fi is best"
        }
        translateButton?.let { b ->
            b.visibility = if (VoiceModel.available && TranslateModel.progress < 0) View.VISIBLE else View.GONE
            b.text = if (tReady) "Delete" else "Download"
            b.background = ripple(if (tReady) rounded(Ui.RAISED, r, dp(1), Ui.STROKE) else accentGradient(r), r)
            b.setTextColor(if (tReady) Ui.TEXT else Ui.ON_ACCENT)
        }
        val car = when (Session.car) {
            "" -> if (Settings(this).carMode == "off") "Off" else "Waiting for the car (its Bluetooth audio)"
            "listening" -> "Listening…"
            "thinking" -> "Claude is thinking…"
            "speaking" -> "Speaking"
            else -> "On" + (if (Session.carName.isNotEmpty()) " · ${Session.carName}" else "") + ": press Next to talk"
        }
        if (::carState.isInitialized && carState.text.toString() != car) carState.text = car
        if (::carVoiceText.isInitialized) {
            val kReady = KokoroModel.ready(this)
            carVoiceText.text = when {
                !VoiceModel.available -> "This build has no speech engine: the phone's voice"
                KokoroModel.progress >= 0 -> "Downloading Kokoro… ${KokoroModel.progress / 1_000_000} of ${KokoroModel.bytes / 1_000_000} MB"
                kReady -> "Kokoro, natural and offline · ${KokoroModel.bytes / 1_000_000} MB"
                KokoroModel.problem != null -> "Not downloaded: ${KokoroModel.problem}"
                else -> "Kokoro sounds far more natural: ${KokoroModel.bytes / 1_000_000} MB, once (until then, the phone's voice)"
            }
            carVoiceButton.visibility = if (VoiceModel.available && KokoroModel.progress < 0) View.VISIBLE else View.GONE
            carVoiceButton.text = if (kReady) "Delete" else "Download"
        }
        val said = Session.voice.replace(ScouterService.MIC, "").trim()
        voiceState.text = (if (Session.listening) said.ifEmpty { "Listening…" } else said.ifEmpty { "Tap to speak" }) +
            if (Session.dictating) " · dictating" else ""
        val listening = Session.listening
        if (micDisc.background == null || listening != (haloAnim != null)) {
            val d = dp(48).toFloat()
            micDisc.background = ripple(if (listening) accentGradient(d) else rounded(Ui.RAISED, d, dp(1), Ui.STROKE), d)
            micIcon.color = if (listening) Ui.ON_ACCENT else Ui.TEXT
        }
        if (listening && haloAnim == null) {
            val sx = android.animation.ObjectAnimator.ofFloat(micHalo, View.SCALE_X, 0.8f, 1.15f)
            val sy = android.animation.ObjectAnimator.ofFloat(micHalo, View.SCALE_Y, 0.8f, 1.15f)
            val a = android.animation.ObjectAnimator.ofFloat(micHalo, View.ALPHA, 0.45f, 0f)
            for (o in listOf(sx, sy, a)) {
                o.repeatCount = android.animation.ValueAnimator.INFINITE
                o.duration = 1100
            }
            haloAnim = android.animation.AnimatorSet().apply {
                playTogether(sx, sy, a)
                start()
            }
        } else if (!listening && haloAnim != null) {
            haloAnim?.cancel()
            haloAnim = null
            micHalo.alpha = 0f
        }
        heardText.text = Session.heard.joinToString("\n").ifEmpty { "Nothing yet." }
        voiceModeButton?.text = if (Session.voiceMode) "Voice mode off" else "Voice mode on"
    }

    // ------------------------------------------------ the sources grid

    // A machine on the grid: its terminal and its screen (null: it has none).
    private class Machine(val name: String, val icon: String, val term: String?, val screen: String?)

    // The machines as Alt+arrows walk them (Settings.move): this phone (its own server's terminal, its screen),
    // then each other server; another server on this phone gets a column with its terminal only.
    private fun machines(settings: Settings): List<Machine> {
        val l = settings.servers()
        val local = l.filter { it.local }
        val out = mutableListOf(Machine("Phone", "phone", local.firstOrNull()?.let { "term:${it.id}" }, Settings.PHONE))
        for (s in local.drop(1)) out += Machine(s.name, "phone", "term:${s.id}", null)
        for (s in l.filter { !it.local }) out += Machine(s.name, "laptop", "term:${s.id}", "screen:${s.id}")
        return out
    }

    // Home's grid again (when the servers changed): a column per machine, terminal above screen; up to two fill
    // the width, more scroll across.
    private fun buildSources() {
        val settings = Settings(this)
        val list = machines(settings)
        val key = list.joinToString("|") { "${it.name}/${it.term}/${it.screen}" }
        if (key == gridKey) return
        gridKey = key
        gridTiles.clear()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fill = list.size <= 2
        for ((i, m) in list.withIndex()) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, 0, dp(8))
            }
            head.addView(IconView(this, m.icon, 15, Ui.MUTED), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
            head.addView(text(m.name.uppercase(), 11.5f, Ui.MUTED, Ui.BOLD).apply { letterSpacing = 0.1f; maxLines = 1 })
            col.addView(head)
            val term = GridTile(this, "terminal", "Terminal") { if (m.term != null) choose(m.term) else editServer(null, local = true) }
            val screen = GridTile(this, "screen", "Screen") { m.screen?.let { choose(it) } }
            col.addView(term, lp().apply { bottomMargin = dp(10) })
            col.addView(screen, lp())
            gridTiles += Triple(m.term, term, "Add this phone's")
            gridTiles += Triple(m.screen, screen, "None")
            row.addView(col, if (fill) lp(0, weight = 1f).apply { if (i > 0) leftMargin = dp(12) }
                else lp(dp(150)).apply { if (i > 0) leftMargin = dp(12) })
        }
        if (list.size == 1) row.addView(addMachine(), lp(0, weight = 1f).apply { leftMargin = dp(12) })
        gridHolder.removeAllViews()
        if (fill) gridHolder.addView(row)
        else gridHolder.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    // Where a laptop's column would be: adds a server.
    private fun addMachine(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val r = dp(20).toFloat()
        background = rounded(0, r).apply { setStroke(dp(1), Ui.STROKE, dp(5).toFloat(), dp(4).toFloat()) }
        setPadding(dp(12), dp(20), dp(12), dp(20))
        addView(IconView(context, "plus", 26, Ui.MUTED))
        addView(text("Add a laptop", 13.5f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        setOnClickListener { editServer(null) }
    }

    // A tile's line and dot: how its source is doing.
    private fun tileState(key: String?): Pair<String, Int> {
        if (key == null) return "—" to 0
        val id = key.substringAfter(':')
        return when {
            key == Settings.PHONE -> if (Session.capturing) "Shared" to Ui.GOOD else "Tap to share" to 0
            key.startsWith("screen:") -> if (Session.remote == id) "Streaming" to Ui.GOOD else "Over SSH" to 0
            else -> when (val st = Session.terms[id] ?: "off") {
                "connected" -> "Connected" to Ui.GOOD
                "starting", "connecting" -> "Connecting…" to Ui.WARN
                "off" -> "Ready" to 0
                "ended" -> "Ended" to Ui.FAINT
                else -> if (st.startsWith("no contact")) "No contact" to Ui.WARN else "Can't connect" to Ui.BAD
            }
        }
    }

    // ------------------------------------------------ servers

    // The Terminal tab's list: each saved server with its state, the one in use marked; a tap edits it.
    private fun buildServers() {
        val settings = Settings(this)
        serversBox.removeAllViews()
        val active = settings.active()?.id
        for (s in settings.servers()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = ripple(rounded(0, dp(14).toFloat()), dp(14).toFloat())
                setOnClickListener { editServer(s) }
            }
            row.addView(IconView(this, if (s.local) "phone" else "laptop", 22, Ui.MUTED), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(s.name + if (s.id == active) "  · in use" else "", 15.5f, Ui.TEXT, Ui.BOLD))
            texts.addView(text(s.address + if (s.command.isNotEmpty()) " · ${s.command}" else "", 12.5f, Ui.MUTED).apply { maxLines = 1 })
            row.addView(texts, lp(0, weight = 1f).apply { rightMargin = dp(8) })
            val (state, dot) = tileState("term:${s.id}")
            row.addView(text(state, 12f, if (dot != 0) dot else Ui.FAINT, Ui.BOLD))
            serversBox.addView(row)
        }
        if (settings.servers().isEmpty()) serversBox.addView(text("No server yet: add one. For this phone's own terminal, " +
            "run scouter.sh in Termux, then add it with \"This phone\".", 12.5f, Ui.FAINT))
    }

    // Add (null) or change a saved server: presets for this phone, the fields, mosh's ports; Delete and Forget its
    // host key from here too.
    private fun editServer(s: Settings.Server?, local: Boolean = false) {
        val settings = Settings(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val name = field("Name", s?.name ?: if (local) "Phone" else "", "Laptop")
        val host = field("Host", s?.host ?: if (local) Termux.HOST else "", "an address, a Tailscale IP or name")
        val port = field("Port", (s?.port ?: if (local) Termux.PORT else 22).toString(), "22", numeric = true)
        val user = field("User", s?.user ?: if (local) "root" else "", "")
        val command = field("Command", s?.command ?: if (local) "bash -ic claude" else "", "empty: the login shell; tmux new -A -s main")
        val ports = field("mosh UDP ports", s?.moshPorts ?: "", "e.g. 60000:60009 (empty: mosh's default)")
        fun preset(n: String, h: String, p: Int, u: String, c: String) {
            if (name.text.isBlank()) name.setText(n)
            host.setText(h)
            port.setText(p.toString())
            user.setText(u)
            command.setText(c)
        }
        box.addView(subhead("This phone"))
        box.addView(chipRow(
            chip("Claude in Ubuntu", false) { preset("Phone", Termux.HOST, Termux.PORT, "root", "bash -ic claude") },
            chip("Ubuntu shell", false) { preset("Phone", Termux.HOST, Termux.PORT, "root", "") },
            chip("Termux", false) { preset("Phone", Termux.HOST, Termux.PORT, Termux.USER, "tmux new -A -s main") },
        ))
        for (f in listOf(name, host, port, user, command, ports)) box.addField(f)
        if (s != null) box.addView(chipRow(chip("Forget its host key", false) {
            Bootstrap.forget(Vault(this), "${s.host}:${s.port}")
            note = "${s.name}: its host key is asked for again at the next connection."
            show()
        }).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) })
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
                val mp = ports.text.toString().trim().takeIf { it.isEmpty() || Regex("\\d{1,5}(:\\d{1,5})?").matches(it) }
                    ?: s?.moshPorts ?: ""
                val saved = Settings.Server(s?.id ?: Settings.newServerId(), name.text.toString().trim().ifEmpty { h }, h,
                    port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22, u, command.text.toString().trim(), mp)
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
        if (tab == TV_TAB) tv?.refresh()
        Session.listeners += listener
        Session.foreground = true
        // While Vox is in front: its service may become a microphone service (Car mode's phone microphone).
        if (ScouterService.running && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "mic_fg")
        Preview.listener = previewListener
        gridKey = ""
        buildSources()
        buildServers()
        buildStyles()
        buildCommands()
        if (VoiceModel.progress >= 0 || TranslateModel.progress >= 0) content.post(modelTicker)
        selectTab(tab, animate = false)
        val keyboard = Settings(this).keyboard
        // Keyboard mode: the keys come only while this screen is in front, so it stays on (dimmed by Android).
        if (keyboard) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Settings may have changed; and while Android has not said whether keyer is near (the app new or
        // restarted), the service tries keyer's link. In keyboard mode this screen in front starts the session.
        // Start by itself: the app open is enough.
        val auto = Settings(this).startsByItself()
        if (auto || keyboard || ScouterService.running || Session.near == null || BuildConfig.FLAVOR == "emulated") ScouterService.send(this)
        associate()
        show()
    }

    // The power button: stops everything (after asking) while it runs, else starts with the source last chosen.
    private fun power() {
        if (ScouterService.running && Session.showing.isNotEmpty()) {
            dialog().setTitle("Stop everything?")
                .setMessage("The glasses let go and the terminals go quiet until you start again.")
                .setPositiveButton("Stop") { _, _ ->
                    note = null
                    ScouterService.send(this, ScouterService.ACTION_STOP)
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val settings = Settings(this)
        val key = settings.source.ifEmpty { settings.active()?.let { "term:${it.id}" } ?: "" }
        if (key.isEmpty()) return editServer(null)
        choose(key)
    }

    // ------------------------------------------------ the state

    private fun show() {
        val settings = Settings(this)
        val running = ScouterService.running && Session.showing.isNotEmpty()
        if (running) startingAt = 0L
        val starting = !running && startingAt > 0 && android.os.SystemClock.elapsedRealtime() - startingAt < 45000
        val active = settings.active()
        val remoteShown = Session.screenSource.startsWith("screen:")
        val both = Session.left && Session.right

        // The top bar: the glasses' link and the power.
        val linkDot = when {
            running && both -> Ui.GOOD
            running && Session.glasses == "connecting" || starting -> Ui.WARN
            running -> Ui.BAD
            else -> Ui.FAINT
        }
        setPill(linkPill, when {
            running && both -> "Connected"
            running && Session.glasses == "ready" -> "One arm"
            running || starting -> "Connecting"
            else -> "Off"
        }, linkDot)
        if (running != powerShown) {
            powerShown = running
            val r = dp(22).toFloat()
            powerDisc.background = ripple(if (running) accentGradient(r) else rounded(Ui.RAISED, r, dp(1), Ui.STROKE), r)
            powerIcon.color = if (running) Ui.ON_ACCENT else Ui.TEXT
        }

        // The glasses, live.
        val mode = when {
            !running -> GlassesView.SHOW_TERMINAL
            Session.showing == Settings.SCREEN -> GlassesView.SHOW_SCREEN
            else -> GlassesView.SHOW_TERMINAL
        }
        val title = when {
            !running -> "Vox"
            Session.tunes -> "Tunes"
            Session.showing == Settings.SCREEN -> if (remoteShown) Session.source else "Phone · screen"
            else -> Session.source.ifEmpty { "${active?.name ?: "Terminal"} · terminal" }
        }
        val message = when {
            starting -> "Starting…"
            !running -> "Tap a source to start"
            Session.blank -> "Display off"
            Session.tunes -> "\u266B  Synthwave on the glasses"
            Session.showing == Settings.TERMINAL && !Session.shown && !settings.phoneForce -> "Look up to see it"
            else -> ""
        }
        val b = Session.battery
        glasses.set(mode, title, message, if (running && !Session.blank) linkDot else Ui.FAINT, b.getOrElse(0) { -1 },
            b.getOrElse(1) { -1 }, b.getOrElse(2) { 0 } == 1, b.getOrElse(3) { 0 } == 1, running && both)
        wantPreview()

        // One line of what goes on.
        val parts = mutableListOf<String>()
        if (running) {
            parts += if (Session.showing == Settings.SCREEN) {
                "Screen " + if (Session.screen == "on") String.format(java.util.Locale.US, "%.1f fps", Session.screenFps) else Session.screen
            } else "${active?.name ?: "Terminal"}: ${Session.mosh}"
            if (Session.pointer) parts += "pointer on"
            if (Session.listening) parts += "listening"
        }
        statusLine.text = parts.joinToString(" · ")
        statusLine.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        // What needs the user, with the button that fixes it.
        val banners = mutableListOf<Triple<String, String, Pair<String, () -> Unit>?>>()
        val permitted = ScouterService.missing(this).isEmpty()
        val openSettings = "Settings" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (!permitted) banners += Triple("lock", "Vox needs Bluetooth and notifications.", openSettings)
        else if (!ScouterService.bluetoothOn(this)) banners += Triple("info", "Bluetooth is off: Vox waits for it.", null)
        if (settings.left.isEmpty() || settings.right.isEmpty())
            banners += Triple("glasses", "Pair your glasses (in the open case).", "Pair" to { startActivity(Intent(this, PairingActivity::class.java)) })
        if (running && Session.glasses == "ready" && !both) banners += Triple("glasses", "One arm is not connected.", null)
        if (settings.servers().isEmpty()) banners += Triple("server", "Add a server for the terminal.", "Add" to { editServer(null) })
        if (!Hotkeys.everywhere(this)) banners += Triple("keyboard", "Turn on Vox in Accessibility so your keyboard and " +
            "trackpad reach the glasses from any app.", "Turn on" to { openAccessibility() })
        note?.let { n -> banners += Triple("info", n, "OK" to { note = null; show() }) }
        val key = banners.joinToString("|") { it.first + it.second + (it.third?.first ?: "") }
        if (key != bannerKey) {
            bannerKey = key
            android.transition.TransitionManager.beginDelayedTransition(bannerBox)
            bannerBox.removeAllViews()
            for ((icon, text, action) in banners)
                bannerBox.addView(banner(icon, text, action?.first, if (icon == "info") Ui.ACCENT else Ui.WARN) { action?.second?.invoke() })
        }

        // The grid: the source on the glasses lit, each with its state.
        val current = when {
            !running || Session.blank -> ""
            Session.showing == Settings.TERMINAL -> "term:${active?.id}"
            else -> Session.screenSource.ifEmpty { Settings.PHONE }
        }
        for ((k, tile, empty) in gridTiles) {
            if (k == null) {
                tile.set(GridTile.STATE_NONE, empty, 0)
                continue
            }
            val (sub, dot) = tileState(k)
            tile.set(if (k == current) GridTile.STATE_ON else GridTile.STATE_IDLE, sub, dot)
        }

        // The everyday switches.
        displayAction.set(running && !Session.blank, if (Session.blank) "Display off" else "Display")
        alwaysAction.set(settings.phoneForce, if (settings.phoneForce) "Always on" else "Look up")
        voiceAction.set(Session.listening, if (Session.listening) "Listening" else "Voice")
        pointerAction.set(Session.pointer)
        tunesAction.set(Session.tunes)
        previewButton.text = if (Session.demo) "■  Stop the preview" else "▶  Preview on the glasses"

        // The look per app.
        val lk = Looks.key
        if (lk != screenBuiltFor && settings.looksPerApp) rebuildScreen()
        when {
            !settings.looksPerApp -> {
                lookTitle.text = "Every app"
                lookSub.text = "One look for everything (per app is off)."
            }
            lk.isEmpty() -> {
                lookTitle.text = "Nothing on the glasses yet"
                lookSub.text = "Once a screen shows, its app (or laptop window) is named here."
            }
            else -> {
                lookTitle.text = Looks.name(this, settings, lk)
                val own = Looks.own(settings, lk)
                lookSub.text = if (Looks.fallback(lk) == null) "The default look for this screen's apps."
                    else if (own) "Its own look: kept as you change it."
                    else "The default look: change anything to give it its own."
            }
        }
        val app = lk.isNotEmpty() && Looks.fallback(lk) != null && settings.looksPerApp
        forgetChip.visibility = if (app && Looks.own(settings, lk)) View.VISIBLE else View.GONE
        defaultChip.visibility = if (app) View.VISIBLE else View.GONE

        // The other tabs.
        val sharing = Session.capturing
        if (sharing != sharingShown) {
            sharingShown = sharing
            shareChip.text = if (sharing) "Stop sharing" else "Share the phone's screen"
            val cr = dp(18).toFloat()
            shareChip.background = ripple(if (sharing) rounded(0x1FF87171, cr, dp(1), 0x66F87171) else accentGradient(cr), cr)
            shareChip.setTextColor(if (sharing) Ui.BAD else Ui.ON_ACCENT)
        }
        if (batterySwitch.isChecked != settings.battery) batterySwitch.isChecked = settings.battery
        showDash()
        buildTabStrip()
        tabsState.text = when {
            Session.tabs.isNotEmpty() -> Session.tabs
            Session.tabList.isEmpty() -> "Tabs are the tmux windows of the server in use."
            else -> "Tap a tab to show it, hold it to close it."
        }
        if (::sportsState.isInitialized) sportsState.text = Sports.game?.let { g ->
            val vs = "${g.away.name} at ${g.home.name}"
            when (g.state) {
                "in" -> "Live: ${g.away.abbr} ${g.away.score}, ${g.home.abbr} ${g.home.score} · ${g.clock}"
                "post" -> "$vs · ${g.away.abbr} ${g.away.score}, ${g.home.abbr} ${g.home.score} (${g.clock.lowercase()})"
                else -> "$vs · ${g.clock.lowercase()}" + (if (g.tv.isNotEmpty()) " on ${g.tv}" else "")
            } + " · from ESPN. Add the Game score widget, or pick the Game day style."
        } ?: Sports.problem.ifEmpty { "Looking for the game…" }
        weatherState.text = Weather.now?.let { "${it.temp}° ${Weather.word(it.code).lowercase()} in ${it.place} · from Open-Meteo, every 30 minutes" }
            ?: Weather.problem.ifEmpty { "Type a city for the weather widget." }
        val calendar = Agenda.allowed(this)
        calendarState.text = if (calendar) "Allowed: the next events of the phone's calendars." else "The calendar widget needs access to read your calendars."
        calendarButton.visibility = if (calendar) View.GONE else View.VISIBLE
        notesState.text = if (LookUp.allowed(this)) "Notification access: allowed (${LookUp.notes.size} now)." else
            "Notification access: not allowed yet (Settings, Access)."
        showVoice()
        previewLine()
        Native.snapshot()?.let {
            val s = TerminalView.parse(it)
            terminal.screen = s
            homeTerminal.screen = s
        }
        title(terminal.screen?.title?.ifEmpty { null } ?: getString(R.string.app_name))
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

    // The Screen tab made again (its controls show the look now in use), where it was scrolled to.
    private fun rebuildScreen() {
        screenBuiltFor = Looks.key
        if (pages.size < 2) return
        val old = pages[1]
        val y = old.scrollY
        val page = screenPage(Settings(this))
        pages[1] = page
        val i = content.indexOfChild(old)
        content.removeView(old)
        content.addView(page, i, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.visibility = if (tab == 1) View.VISIBLE else View.GONE
        page.post { page.scrollTo(0, y) }
        sharingShown = null
        previewView.refresh()
    }

    // Vox's switch in Android's accessibility settings (its own page where Android has one).
    private fun openAccessibility() {
        val me = android.content.ComponentName(this, VoxKeys::class.java).flattenToString()
        runCatching {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me))
        }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    }

    private fun title(t: String) {
        title = t
    }

    // A status pill's dot and word.
    private fun setPill(pill: TextView, label: String, dot: Int) {
        val span = android.text.SpannableString("●  $label")
        span.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
        pill.text = span
    }

    private fun download() {
        if (VoiceModel.progress >= 0) return
        dialog().setTitle("Download the speech model?")
            .setMessage("${VoiceModel.bytes / 1_000_000} MB from Hugging Face and GitHub, each file checked. Wi-Fi is best. " +
                "It goes on where it stopped if interrupted.")
            .setPositiveButton("Download") { _, _ ->
                VoiceModel.progress = 0
                Thread {
                    VoiceModel.download(applicationContext)
                    runOnUiThread { showVoice() }
                }.start()
                content.post(modelTicker)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun downloadTranslate() {
        if (TranslateModel.progress >= 0) return
        dialog().setTitle("Download the translation model?")
            .setMessage("${TranslateModel.bytes / 1_000_000} MB (OpenAI's Whisper small) from Hugging Face and GitHub, each " +
                "file checked. Wi-Fi is best. It goes on where it stopped if interrupted.")
            .setPositiveButton("Download") { _, _ ->
                TranslateModel.progress = 0
                Thread {
                    TranslateModel.download(applicationContext)
                    runOnUiThread { showVoice() }
                }.start()
                content.post(modelTicker)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // Car mode on the phone's microphone: Android asks once; Vox's service then may hear in the back.
    private fun askMic() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "mic_fg")
        else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }

    // Car mode's voice (Kokoro), as the speech model is downloaded.
    private fun downloadCarVoice() {
        if (KokoroModel.progress >= 0) return
        dialog().setTitle("Download the car voice?")
            .setMessage("Kokoro: ${KokoroModel.bytes / 1_000_000} MB from Hugging Face, each file checked. Wi-Fi is best. " +
                "It goes on where it stopped if interrupted.")
            .setPositiveButton("Download") { _, _ ->
                KokoroModel.progress = 0
                Thread {
                    KokoroModel.download(applicationContext)
                    runOnUiThread {
                        showVoice()
                        ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "car_voice")
                    }
                }.start()
                content.post(carVoiceTicker)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // The Voice tab's own commands: each phrase and what it does.
    private fun buildCommands() {
        val settings = Settings(this)
        commandsBox.removeAllViews()
        val list = settings.voiceCommands()
        for ((i, c) in list.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text("\u201c${c.phrase}\u201d", 15f, Ui.TEXT, Ui.BOLD))
            val (action, arg) = if (c.action.startsWith("key:")) VoiceCommands.KEY to c.action.removePrefix("key:") else c.action to c.arg
            texts.addView(text(VoiceCommands.label(settings, action, arg), 12.5f, Ui.MUTED))
            row.addView(texts, lp(0, weight = 1f).apply { rightMargin = dp(8) })
            row.addView(chip("Delete", false) {
                settings.saveVoiceCommands(list.filterIndexed { k, _ -> k != i })
                buildCommands()
            })
            commandsBox.addView(row)
        }
        if (list.isEmpty()) commandsBox.addView(text("None yet: for example \"work\" to show the laptop's terminal.", 12.5f, Ui.FAINT))
    }

    private fun addCommand() {
        val settings = Settings(this)
        val options = settings.sources().map { (key, name) -> "source|$key" to "Show $name" } +
            VoiceCommands.CHOICES.map { (k, v) -> k to v }
        var pick = options.first().first
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val phrase = field("When I say", "", "e.g. work")
        box.addField(phrase)
        box.addView(choiceRow("It does", options.first().second) { shown ->
            dialog().setTitle("It does")
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == pick }) { d, w ->
                    pick = options[w].first
                    shown.text = options[w].second
                    d.dismiss()
                }
                .show()
        })
        val arg = field("Text (for Type text, Type text and Enter)", "", "e.g. git status")
        box.addField(arg)
        dialog().setTitle("Add a voice command")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Add") { _, _ ->
                val p = phrase.text.toString().trim()
                val (action, a) = if (pick.startsWith("source|")) VoiceCommands.SOURCE to pick.removePrefix("source|")
                    else pick to arg.text.toString().trim()
                if (p.isEmpty() || ((action == VoiceCommands.TYPE || action == VoiceCommands.RUN) && a.isEmpty())) {
                    note = "A voice command needs words to say (and text for typing)."
                    return@setPositiveButton show()
                }
                settings.saveVoiceCommands(settings.voiceCommands() + VoiceCommands.Command(p, action, a))
                buildCommands()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == CALENDAR_REQUEST) {
            buildStyles()
            lookUpChanged()
            show()
        }
        if (request == MIC_REQUEST && results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ScouterService.send(this, ScouterService.ACTION_VOICE, "mode" to "mic_fg")
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
            shareAway = true
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
                // Asked from elsewhere (a hotkey, the glasses): the app that was in front comes back.
                if (shareAway) moveTaskToBack(true)
            } else note = "Screen sharing was not allowed."
            shareAway = false
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
        VoxKeys.confirmShare() // its Start tapped for the user (VoxKeys on)
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
            !Session.capturing && Session.remote.isEmpty() -> "Nothing shared yet: share the phone's screen, or pick a laptop's screen on the Glasses tab."
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
    private var startingAt = 0L // a source was tapped: "Starting…" until the session shows (or 45 s)

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
        private const val OFF = "off"   // the display's switch
        private const val GLASS = 0xFF3CFF78.toInt() // the panel's green, for the glasses' view
        private const val CALENDAR_REQUEST = 9
    }
}

package scouterkit.ssh

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import scouterkit.ssh.ListenLook.caps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Listen mode on the phone, in four places (the bar at the bottom): Live (what is said, as a conversation that
// grows at the bottom while it is said, the sound and the words being said pinned under it), People (the voices to
// name first, you, the people), Ask (questions and searches about anything said), More (meetings, automations,
// hearing and the quiet-voice check, the AI models, how voices are told apart). The top bar: the orb (tap on / off,
// hold to pause) and what Listen is doing.
class ListenActivity : Activity(), HearingCheck.Ui {
    companion object {
        const val EXTRA_RESUME = "resume"
        // The Rainbow Passage (Fairbanks, 1960), read to give a voice: about 30 s.
        const val PASSAGE = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The " +
            "rainbow is a division of white light into many beautiful colors. These take the shape of a long round arch, " +
            "with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a " +
            "boiling pot of gold at one end. People look, but no one ever finds it."
        private val TABS = listOf(Triple("live", "Live", "mic"), Triple("people", "People", "eye"),
            Triple("ask", "Ask", "spark"), Triple("more", "More", "list"))
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var content: FrameLayout
    private val tabViews = HashMap<String, View>()
    private val navItems = HashMap<String, Pair<IconView, TextView>>()
    private var tab = "live"

    // Top bar.
    private lateinit var orb: ListenOrb
    private lateinit var state: TextView
    private lateinit var speedPill: TextView

    // Live.
    private lateinit var trace: ListenTrace
    private lateinit var liveScroll: ScrollView
    private lateinit var liveList: LinearLayout
    private lateinit var liveInfo: TextView
    private lateinit var newPill: TextView
    private lateinit var nowBar: LinearLayout
    private lateinit var nowPulse: Pulse
    private lateinit var nowText: TextView
    private val shown = ArrayList<Long>() // the line ids drawn, in order
    private var shownNames = 0 // a hash of the names when drawn
    private var lastWho = ""
    private var lastAt = 0L
    private var loading = false
    private var again = false

    // People.
    private lateinit var peopleBody: LinearLayout
    private var recorder: VoiceRecorder? = null
    private var recordText = ""

    // Ask.
    private lateinit var askOut: LinearLayout
    private var deep = false
    private val history = ArrayList<Pair<String, String>>()

    // More and its pages.
    private lateinit var moreBody: LinearLayout
    private var subpage = ""

    // The check (Hearing page).
    private var sentence: TextView? = null
    private var checkState: TextView? = null
    private var checkList: LinearLayout? = null
    private var checkUse: TextView? = null
    private var saveVoice: TextView? = null
    private var lastClips: List<FloatArray> = emptyList()
    private var check: HearingCheck? = null
    private var far = false
    private var suggestion: HearingCheck.Suggestion? = null

    private val fast = object : Runnable {
        override fun run() {
            val on = Listen.on && !Listen.paused
            val heard = Listen.level + Listen.gainDb
            orb.set((heard + 70f) / 60f, when {
                !Listen.on -> 0
                Listen.paused -> 3
                Listen.speaking -> 2
                else -> 1
            })
            if (tab == "live") trace.push(if (on) heard else -100f, on && Listen.speaking, Listen.floor + Listen.gainDb)
            main.postDelayed(this, 120)
        }
    }
    private val slow = object : Runnable {
        override fun run() {
            showTop()
            if (tab == "live") showNow()
            Listen.looking(30_000)
            main.postDelayed(this, 1000)
        }
    }
    private val periodic = object : Runnable {
        override fun run() {
            if (tab == "live") loadLive()
            main.postDelayed(this, 8000)
        }
    }
    private val watcher: () -> Unit = {
        main.post {
            if (tab == "live") {
                showNow()
                loadLive()
            }
        }
    }

    // ------------------------------------------------ the frame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.SURFACE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(topBar())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)
        tabViews["live"] = buildLive()
        tabViews["people"] = scrolled { peopleBody = it }
        tabViews["ask"] = buildAsk()
        tabViews["more"] = scrolled { moreBody = it }
        for (v in tabViews.values) content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab = getSharedPreferences("ui", Context.MODE_PRIVATE).getString("listen_tab2", "live") ?: "live"
        if (TABS.none { it.first == tab }) tab = "live"
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) settings.listenOn = true
        showTab(tab)
    }

    override fun onResume() {
        super.onResume()
        if (settings.listenOn) {
            if (micAllowed()) ListenService.ensure(this) else askMic()
        }
        Listen.watch(watcher)
        Listen.looking(30_000)
        main.post(fast)
        main.post(slow)
        main.post(periodic)
        showTab(tab)
    }

    override fun onPause() {
        super.onPause()
        Listen.unwatch(watcher)
        main.removeCallbacks(fast)
        main.removeCallbacks(slow)
        main.removeCallbacks(periodic)
    }

    override fun onDestroy() {
        check?.cancel()
        recorder?.stop()
        super.onDestroy()
    }

    @Deprecated("Back closes a More page first")
    override fun onBackPressed() {
        if (tab == "more" && subpage.isNotEmpty()) return showMore("")
        if (tab != "live") return showTab("live")
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun scrolled(take: (LinearLayout) -> Unit): ScrollView {
        val col = column().apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        take(col)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        }
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(14), dp(8))
        }
        orb = ListenOrb(this).apply {
            isClickable = true
            setOnClickListener { toggle() }
            setOnLongClickListener {
                if (Listen.paused) Listen.resume() else if (Listen.on) Listen.pause(15 * 60_000L)
                showTop()
                true
            }
        }
        bar.addView(orb, LinearLayout.LayoutParams(dp(58), dp(58)))
        val t = column()
        t.addView(text("Listen", 21f, Ui.TEXT, Ui.BLACK))
        state = text("", 12.5f, Ui.MUTED)
        t.addView(state)
        bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        speedPill = text("", 11f, Ui.TEXT, Ui.BOLD).apply {
            letterSpacing = 0.12f
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        bar.addView(speedPill)
        bar.addView(IconView(this, "close", 22, Ui.MUTED).apply {
            setPadding(dp(14), dp(6), dp(2), dp(6))
            setOnClickListener { finish() }
        })
        return bar
    }

    private fun bottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(0, dp(6), 0, dp(8))
        }
        for ((key, label, icon) in TABS) {
            val item = column().apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showTab(key) }
            }
            val ic = IconView(this, icon, 22, Ui.MUTED)
            item.addView(ic)
            val tx = text(label, 11.5f, Ui.MUTED, Ui.BOLD).apply { gravity = Gravity.CENTER }
            item.addView(tx)
            navItems[key] = ic to tx
            bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun showTab(k: String) {
        tab = k
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("listen_tab2", k).apply()
        for ((key, v) in tabViews) v.visibility = if (key == k) View.VISIBLE else View.GONE
        for ((key, p) in navItems) {
            val on = key == k
            p.second.setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
            p.first.color = if (on) Ui.ACCENT else Ui.MUTED
        }
        showTop()
        when (k) {
            "live" -> { loadLive(); showNow() }
            "people" -> showPeople()
            "more" -> showMore(subpage)
        }
    }

    private fun showTop() {
        val on = Listen.on
        state.text = when {
            !VoiceModel.ready(this) -> "Download the speech model (Voice tab)"
            !micAllowed() -> "Tap the orb and allow the microphone"
            on && Listen.paused -> "Paused until " + SimpleDateFormat("h:mm a", Locale.US).format(Date(Listen.pausedUntil)) + " · hold the orb"
            MicHub.silenced && on -> "Another app has the microphone"
            on -> "${Listen.today} lines today · " + Listen.state + (Meetings.current?.let { " · meeting" } ?: "")
            settings.listenOn -> "Starting…"
            else -> "Off · tap the orb to start"
        }
        state.setTextColor(when {
            on && Listen.paused -> Ui.WARN
            on && Listen.speaking -> Ui.ACCENT
            on -> Ui.MUTED
            else -> Ui.FAINT
        })
        val live = settings.listenSpeed == "live" || (settings.listenSpeed == "auto" && Listen.speed == "live")
        speedPill.text = if (!on) "OFF" else if (live) "● LIVE" else "ECO"
        speedPill.setTextColor(if (on && live) Ui.ON_ACCENT else Ui.TEXT)
        speedPill.background = if (on && live) accentGradient(dp(12).toFloat()) else rounded(Ui.RAISED, dp(12).toFloat(), dp(1), Ui.STROKE)
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 41)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (micAllowed() && settings.listenOn) ListenService.start(this)
        showTop()
    }

    private fun toggle() {
        if (Listen.on || ListenService.running) ListenService.stop(this)
        else {
            settings.listenOn = true
            if (!micAllowed()) askMic() else ListenService.start(this)
        }
        main.postDelayed({ showTop(); showNow() }, 300)
    }

    // ------------------------------------------------ Live: the conversation as it happens

    private fun buildLive(): View {
        val box = column()
        val head = column().apply { setPadding(dp(16), dp(2), dp(16), dp(6)) }
        trace = ListenTrace(this)
        head.addView(trace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        liveInfo = caps("", Ui.FAINT, 9.5f).apply { setPadding(dp(2), dp(6), 0, 0) }
        head.addView(liveInfo)
        box.addView(head)
        val frame = FrameLayout(this)
        liveList = column().apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        liveScroll = ScrollView(this).apply {
            addView(liveList)
            setOnScrollChangeListener { _, _, _, _, _ -> if (atBottom()) newPill.visibility = View.GONE }
        }
        frame.addView(liveScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        newPill = chip("↓ New lines", true) { toBottom() }.apply { visibility = View.GONE }
        frame.addView(newPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nowBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        nowPulse = Pulse(this)
        nowBar.addView(nowPulse)
        nowText = text("", 15f, Ui.TEXT, Ui.BOLD)
        nowBar.addView(nowText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        box.addView(nowBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8)
        })
        return box
    }

    private fun atBottom() = liveScroll.scrollY + liveScroll.height >= liveList.height - dp(60)

    private fun toBottom() {
        newPill.visibility = View.GONE
        liveScroll.post { liveScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // The words being said now, pinned under the conversation.
    private fun showNow() {
        val on = Listen.on && !Listen.paused
        val talking = on && (Listen.speaking || Listen.partial.isNotEmpty())
        nowPulse.visibility = if (talking) View.VISIBLE else View.GONE
        nowText.text = when {
            !Listen.on && !settings.listenOn -> "Off: tap the orb to start listening"
            !Listen.on -> "Starting…"
            Listen.paused -> "Paused: hold the orb to listen again"
            talking -> Listen.partial.ifEmpty { "hearing…" }
            Listen.waiting >= 2 -> "Writing down ${Listen.waiting.toInt()} s of speech…"
            else -> "Listening"
        }
        nowText.setTextColor(if (talking) Ui.ACCENT else Ui.MUTED)
        nowBar.background = rounded(Ui.SURFACE, dp(18).toFloat(), dp(1), if (talking) Ui.ACCENT else Ui.STROKE)
    }

    // Today's lines: new ones are added at the bottom (the view follows them while it is at the bottom); names that
    // changed or lines cut by speaker draw it again.
    private fun loadLive() {
        if (loading) return run { again = true }
        loading = true
        Thread {
            val db = ListenDb.get(this)
            val names = db.names()
            val list = db.between(ListenDb.dayStart(System.currentTimeMillis()), Long.MAX_VALUE).takeLast(200)
            val fired = Listen.recent(60).associate { it.id to it.fired }
            val unnamed = list.map { it.voice }.filter { it.isNotEmpty() }.distinct().size
            main.post {
                loading = false
                drawLive(list, names, fired, unnamed)
                if (again) {
                    again = false
                    loadLive()
                }
            }
        }.start()
    }

    private fun drawLive(list: List<ListenDb.Line>, names: Map<String, String>, fired: Map<Long, String>, unnamed: Int) {
        liveInfo.text = "TODAY · ${list.size} LINES" + (if (unnamed > 0) " · $unnamed VOICES TO NAME (PEOPLE)" else "") +
            " · NEWEST AT THE BOTTOM"
        val ids = list.map { it.id }
        val sig = names.hashCode()
        val follow = shown.isEmpty() || atBottom()
        val fresh = sig != shownNames || ids.size < shown.size || ids.subList(0, minOf(shown.size, ids.size)) != shown
        if (fresh) {
            liveList.removeAllViews()
            shown.clear()
            lastWho = "\u0000"
            lastAt = 0L
            shownNames = sig
            if (list.isEmpty()) liveList.addView(text(if (Listen.on) "Nothing said yet today. Talk near the phone: lines appear " +
                "here as they are said." else "Listen mode is off. Tap the orb at the top.", 14f, Ui.FAINT).apply {
                setPadding(0, dp(24), 0, 0)
                gravity = Gravity.CENTER
            })
        }
        val added = list.drop(shown.size)
        if (added.isNotEmpty() && shown.isEmpty()) liveList.removeAllViews()
        for (l in added) {
            addLine(l, names[l.person.ifEmpty { l.voice }] ?: "", fired[l.id] ?: "")
            shown += l.id
        }
        if (added.isNotEmpty()) {
            if (follow || fresh) toBottom() else newPill.visibility = View.VISIBLE
        }
    }

    private fun addLine(l: ListenDb.Line, who: String, fired: String) {
        val col = ListenLook.color(who)
        // A header when the speaker changes (or after two minutes): avatar, name, time.
        if (who != lastWho || l.start - lastAt > 120_000) {
            val h = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(4))
            }
            h.addView(Avatar(this, who.ifEmpty { "?" }, 26))
            h.addView(text(who.ifEmpty { "Someone" }, 13.5f, col, Ui.BOLD).apply { setPadding(dp(8), 0, dp(8), 0) })
            h.addView(text(SimpleDateFormat("h:mm a", Locale.US).format(Date(l.start)), 11.5f, Ui.FAINT))
            liveList.addView(h)
            lastWho = who
        }
        lastAt = l.start
        val bubble = column().apply {
            background = rounded(Ui.SURFACE, dp(16).toFloat(), dp(1), Ui.STROKE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bubble.addView(text(l.text, 15.5f, if (l.quiet) Ui.MUTED else Ui.TEXT))
        val tags = listOfNotNull(l.emotion.ifEmpty { null }, l.sound.ifEmpty { null }, if (l.quiet) "quiet" else null,
            fired.ifEmpty { null }?.let { "■ $it" })
        if (tags.isNotEmpty()) bubble.addView(caps(tags.joinToString(" · "), if (fired.isNotEmpty()) Ui.ACCENT else col, 9.5f)
            .apply { setPadding(0, dp(4), 0, 0) })
        liveList.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(34); topMargin = dp(4) })
    }

    // ------------------------------------------------ People

    private fun personRow(name: String, sub: String, subColor: Int, size: Int = 42): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        r.addView(Avatar(this, name, size))
        val t = column()
        t.addView(text(name, 15.5f, Ui.TEXT, Ui.BOLD))
        t.addView(text(sub, 12.5f, subColor))
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        return r
    }

    private fun showPeople() {
        Thread {
            val db = ListenDb.get(this)
            val all = db.personList()
            val me = all.firstOrNull { it.first == Speakers.ME }
            val meN = if (me != null) db.sampleCount(Speakers.ME) else 0
            val people = all.filter { it.first != Speakers.ME }.map { it to db.sampleCount(it.first) }
            val voices = db.voiceList()
            main.post {
                val b = peopleBody
                b.removeAllViews()
                // Voices to name: what needs you first.
                val vc = card()
                vc.addView(caps(if (voices.isEmpty()) "voices to name" else "voices to name · ${voices.size}", if (voices.isEmpty()) Ui.MUTED else Ui.WARN))
                if (voices.isEmpty()) vc.addView(text("None waiting: every voice heard has a name.", 13.5f, Ui.GOOD).apply { setPadding(0, dp(8), 0, 0) })
                else vc.addView(text("Voices Vox heard but was not told about, with what they said. One person heard as two " +
                    "voices: give both the same name.", 12.5f, Ui.FAINT).apply { setPadding(0, dp(4), 0, 0) })
                for ((v, n, quotes) in voices) {
                    vc.addView(personRow(v.label, "$n lines · last ${SimpleDateFormat("h:mm a", Locale.US).format(Date(v.updated))}", Ui.MUTED))
                    for (q in quotes) vc.addView(text("“$q”", 13.5f, Ui.TEXT).apply { setPadding(dp(54), dp(3), 0, 0) })
                    vc.addView(chipRow(
                        chip("Name…", true) {
                            ask("Who is ${v.label}?", "") { name ->
                                val pid = if (name.equals("me", true) || name.equals("you", true)) Speakers.ME.also {
                                    if (db.personList().none { p -> p.first == it }) db.addPerson("You", false, it)
                                } else db.personNamed(name) ?: db.addPerson(name, false)
                                db.nameVoice(v.id, pid)
                                Listen.peopleChanged()
                                main.post { showPeople() }
                            }
                        },
                        chip("That's me", false) {
                            Thread {
                                if (db.personList().none { it.first == Speakers.ME }) db.addPerson("You", false, Speakers.ME)
                                db.nameVoice(v.id, Speakers.ME)
                                Listen.peopleChanged()
                                main.post { showPeople() }
                            }.start()
                        },
                    ).apply { setPadding(dp(54), dp(4), 0, dp(6)) })
                }
                b.addView(vc)
                // You.
                val yc = card()
                yc.addView(personRow("You", if (me != null) "Known by $meN voiceprints: your lines say You" else
                    "Not recorded: 30 s makes your lines say You", if (me != null) Ui.GOOD else Ui.WARN, 48))
                if (recordText.isNotEmpty() || me == null) yc.addView(text(recordText.ifEmpty {
                    "Read this aloud at your usual volume (about 30 s):\n\n“$PASSAGE”" }, 13f, Ui.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
                yc.addView(chipRow(chip(if (me != null) "Record again (adds to it)" else "Record my voice (30 s)", true) { recordMe() }))
                b.addView(yc)
                // People.
                val pc = card()
                pc.addView(caps("people · ${people.size}"))
                if (people.isEmpty()) pc.addView(text("Nobody yet: name a voice above, or remember one after the quiet-voice " +
                    "check (More, Hearing).", 13f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
                for ((p, n) in people) {
                    val (id, name, soft) = p
                    pc.addView(personRow(name, "$n voiceprints" + if (soft) " · soft voice" else "", if (soft) Ui.ACCENT else Ui.MUTED))
                    pc.addView(chipRow(
                        chip(if (soft) "Soft voice: on" else "Soft voice: off", soft) {
                            Thread { db.setSoft(id, !soft); Listen.peopleChanged(); main.post { showPeople() } }.start()
                        },
                        chip("Rename", false) { ask("Rename $name", name) { db.rename(id, it); Listen.peopleChanged(); main.post { showPeople() } } },
                        chip("Forget", false) {
                            dialog().setTitle("Forget $name?").setMessage("Their voiceprints go; their lines keep the words.")
                                .setPositiveButton("Forget") { _, _ ->
                                    Thread { db.deletePerson(id); Listen.peopleChanged(); main.post { showPeople() } }.start()
                                }.setNegativeButton("Cancel", null).show()
                        },
                    ).apply { setPadding(dp(54), dp(4), 0, dp(6)) })
                }
                b.addView(pc)
                if (!VoiceprintModel.ready(this)) b.addView(banner("info", "Names need the voiceprint model (30 MB).", "Get it") {
                    showTab("more")
                    showMore("voices")
                })
            }
        }.start()
    }

    private fun ask(title: String, value: String, done: (String) -> Unit) {
        val f = field("Name", value, "a name (or me)")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(f)
        }
        dialog().setTitle(title).setView(box).setPositiveButton("OK") { _, _ ->
            val n = f.text.toString().trim()
            if (n.isNotEmpty()) Thread { done(n) }.start()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun recordMe() {
        if (recorder != null) return
        if (!micAllowed()) return askMic()
        if (!VoiceprintModel.ready(this)) {
            recordText = "Get the voiceprint model first: More, Voice recognition."
            return showPeople()
        }
        recorder = VoiceRecorder(this, 30, tick = { left, lv ->
            main.post {
                recordText = "● Recording… $left s left (${lv.toInt()} dB)\n\n“$PASSAGE”"
                if (tab == "people") showPeople()
            }
        }, done = { clips, why ->
            main.post { recordText = if (why != null) "Not recorded: $why" else "Remembering your voice…"; showPeople() }
            if (why == null) {
                val err = Speakers.enroll(this, "You", false, clips, Speakers.ME)
                main.post {
                    recordText = err?.let { "Not saved: $it" } ?: ""
                    showPeople()
                }
            }
            recorder = null
        }).also { it.start() }
    }

    // ------------------------------------------------ Ask (and find)

    private fun buildAsk(): View = scrolled { b ->
        val c = card()
        c.addView(caps("ask or find"))
        c.addView(text("Ask: an answer from what was said (LFM2.5 on this phone). Find: the lines themselves, by words or " +
            "meaning. \"What did Maria say about the trip?\" · \"What did we decide yesterday?\"", 12.5f, Ui.FAINT)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val q = field("Question or words", "", "about any day").apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
        c.addView(q)
        val deepChip = chip("Deep: off", false)
        deepChip.setOnClickListener {
            deep = !deep
            deepChip.text = if (deep) "Deep: on" else "Deep: off"
            deepChip.setTextColor(if (deep) Ui.ACCENT else Ui.TEXT)
        }
        c.addView(chipRow(chip("Ask", true) { askNow(q.text.toString().trim()) }, chip("Find", false) { findNow(q.text.toString().trim()) },
            deepChip), lp().apply { topMargin = dp(8) })
        q.setOnEditorActionListener { _, _, _ -> askNow(q.text.toString().trim()); true }
        b.addView(c)
        askOut = column()
        b.addView(askOut)
        showHistory()
    }

    private fun askNow(q: String) {
        if (q.isEmpty()) return
        askOut.removeAllViews()
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Avatar(this, "Vox", 28))
        head.addView(caps("vox · ${if (deep) "deep" else "quick"} · on this phone", Ui.ACCENT).apply { setPadding(dp(8), 0, 0, 0) })
        c.addView(head)
        c.addView(text(q, 14f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        val a = text("Thinking…", 15.5f, Ui.TEXT).apply { setPadding(0, dp(6), 0, 0); setTextIsSelectable(true) }
        c.addView(a)
        askOut.addView(c)
        if (!LlmModels.usable()) return run { a.text = "This phone or build cannot run the models." }
        if (LlmModels.ALL.none { it.role != "embed" && LlmModels.ready(this, it) }) return run {
            a.text = "Download LFM2.5 1.2B first: More, AI models."
        }
        Thread {
            val got = runCatching { Search.ask(this, q, deep) { t -> main.post { a.text = t.ifEmpty { "Thinking…" } } } }
                .getOrElse { "It stopped: ${it.message}" } ?: "Download LFM2.5 1.2B first: More, AI models."
            main.post {
                a.text = got
                history.add(0, q to got)
                showHistory(keep = 1)
            }
        }.start()
    }

    private fun findNow(q: String) {
        if (q.isEmpty()) return
        askOut.removeAllViews()
        val c = card()
        c.addView(caps("finding…"))
        askOut.addView(c)
        Thread {
            val hits = runCatching { Search.find(this, q, 60) }.getOrDefault(emptyList())
            val names = ListenDb.get(this).names()
            main.post {
                c.removeAllViews()
                c.addView(caps("\"$q\" · ${hits.size} found"))
                if (hits.isEmpty()) c.addView(text("Nothing found.", 13.5f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
                val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                for (h in hits) {
                    val who = names[h.line.person.ifEmpty { h.line.voice }] ?: "Someone"
                    c.addView(caps("$who · ${fmt.format(Date(h.line.start))}" + if (h.meaning && !h.words) " · by meaning" else "",
                        ListenLook.color(who), 9.5f).apply { setPadding(0, dp(12), 0, dp(2)) })
                    c.addView(text(h.line.text, 14.5f, Ui.TEXT))
                }
            }
        }.start()
    }

    private fun showHistory(keep: Int = 0) {
        while (askOut.childCount > keep) askOut.removeViewAt(keep)
        if (history.size <= keep) return
        val h = card()
        h.addView(caps("asked before"))
        for ((q, a) in history.drop(keep).take(10)) {
            h.addView(text(q, 14f, Ui.ACCENT, Ui.BOLD).apply { setPadding(0, dp(10), 0, dp(2)) })
            h.addView(text(a, 13.5f, Ui.MUTED).apply { setTextIsSelectable(true) })
        }
        askOut.addView(h)
    }

    // ------------------------------------------------ More: a menu, each row a page

    private fun showMore(page: String) {
        subpage = page
        val b = moreBody
        b.removeAllViews()
        if (page.isNotEmpty()) {
            b.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(10))
                isClickable = true
                setOnClickListener { showMore("") }
                addView(text("‹  More", 15f, Ui.ACCENT, Ui.BOLD))
            })
        }
        when (page) {
            "" -> moreMenu(b)
            "meet" -> meetPage(b)
            "rules" -> rulesPage(b)
            "hearing" -> hearingPage(b)
            "models" -> modelsPage(b)
            "voices" -> voicesPage(b)
        }
    }

    private fun moreMenu(b: LinearLayout) {
        val rules = Automations.rules(this).count { it.on }
        val ready = LlmModels.ALL.count { LlmModels.ready(this, it) } + (if (ToneModel.ready(this)) 1 else 0)
        val items = listOf(
            listOf("meet", "Meetings", "Start one, its notes, who talked how much", "tabs"),
            listOf("rules", "Automations", "$rules on · Hey Vox, reminders, your own rules", "bolt"),
            listOf("hearing", "Hearing", "Soft voices, the quiet-voice check, speed", "mic"),
            listOf("models", "AI models", "$ready of ${LlmModels.ALL.size + 1} downloaded · LFM, meaning, tone", "spark"),
            listOf("voices", "Voice recognition", VoiceprintModel.chosen(this).name + if (VoiceprintModel.ready(this)) " ready" else " not downloaded", "eye"),
        )
        val c = card()
        for ((i, item) in items.withIndex()) {
            if (i > 0) c.rule()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { showMore(item[0]) }
            }
            row.addView(IconView(this, item[3], 22, Ui.ACCENT))
            val t = column()
            t.addView(text(item[1], 16f, Ui.TEXT, Ui.BOLD))
            t.addView(text(item[2], 12.5f, Ui.MUTED))
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
            row.addView(IconView(this, "chevron", 18, Ui.FAINT))
            c.addView(row)
        }
        b.addView(c)
        b.addView(text("Every day is also a file, \"Transcript <date>.md\", and every meeting its notes, in Vox's docs folder " +
            "(Alt+N on the glasses). On the glasses, the live view: hub L or Alt+E.", 12.5f, Ui.FAINT).apply { setPadding(dp(4), dp(4), dp(4), 0) })
    }

    // ---- Meetings

    private fun shareRows(st: Words.Stats): List<Triple<String, Float, String>> = st.people.take(6).map {
        val m = (it.seconds / 60).toInt()
        val s = (it.seconds % 60).toInt()
        Triple(it.who, (it.seconds / maxOf(1.0, st.seconds)).toFloat(), "${Words.pct(it.seconds, st.seconds)}% · $m:${"%02d".format(s)}")
    }

    private fun meetPage(b: LinearLayout) {
        val c = card()
        c.addView(caps("meeting"))
        val cur = Meetings.current
        val line = text(when {
            cur != null -> "● ${cur.title} · ${(System.currentTimeMillis() - cur.started) / 60000} min"
            Meetings.writing.isNotEmpty() -> "Writing the notes of ${Meetings.writing}…"
            else -> "No meeting now"
        }, 18f, if (cur != null) Ui.ACCENT else Ui.TEXT, Ui.BLACK).apply { setPadding(0, dp(6), 0, dp(4)) }
        c.addView(line)
        val share = ShareBars(this).apply { visibility = View.GONE }
        c.addView(share)
        val title = field("Title", "", "what it is about (optional)")
        if (cur == null) c.addView(title, lp().apply { topMargin = dp(6) })
        val out = text("", 13.5f, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
        c.addView(chipRow(
            chip(if (cur != null) "End the meeting" else "Start a meeting", true) {
                if (Meetings.current != null) Meetings.stop(this) else Meetings.start(this, title.text.toString().trim())
                main.postDelayed({ showMore("meet") }, 300)
            },
            chip("Bookmark", false) { Thread { Meetings.bookmark(this) }.start(); out.text = "Bookmarked the last 30 s." },
            chip("Catch me up", false) {
                out.text = "Thinking…"
                Thread {
                    val t = runCatching { Meetings.catchUp(this) { s -> main.post { out.text = s } } }.getOrNull()
                    main.post { out.text = t ?: "Download LFM2.5 1.2B first (AI models)." }
                }.start()
            },
        ), lp().apply { topMargin = dp(6) })
        c.addView(out)
        c.addToggle(toggle("Start from the calendar", "An event with two or more people starts one while Listen is on",
            settings.listenMeetingsAuto) { settings.listenMeetingsAuto = it })
        b.addView(c)
        val t = card()
        t.addView(caps("today's talk"))
        val todayShare = ShareBars(this)
        t.addView(todayShare, lp().apply { topMargin = dp(8) })
        val report = text("", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }
        t.addView(report)
        b.addView(t)
        val p = card()
        p.addView(caps("past meetings"))
        val past = column()
        p.addView(past)
        b.addView(p)
        Thread {
            val st = Meetings.stats(this)
            val db = ListenDb.get(this)
            val (base, n) = Words.baseline(this)
            val today = Words.stats(db.between(ListenDb.dayStart(System.currentTimeMillis()), Long.MAX_VALUE), db.names(), base, n)
            val list = db.meetings(10)
            main.post {
                if (st != null && st.lines > 0) { share.visibility = View.VISIBLE; share.set(shareRows(st)) }
                todayShare.set(shareRows(today))
                report.text = if (today.lines == 0) "Nothing heard today yet." else Words.report(today)
                if (list.isEmpty()) past.addView(text("None yet.", 13f, Ui.FAINT))
                val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                for (m in list) {
                    past.addView(text(m.title, 15f, Ui.TEXT, Ui.BOLD).apply { setPadding(0, dp(12), 0, 0) })
                    past.addView(caps(fmt.format(Date(m.started)) + (if (m.ended > 0) " · ${(m.ended - m.started) / 60000} min" else " · going on"), Ui.FAINT, 9.5f))
                    if (m.summary.isNotEmpty()) past.addView(text(m.summary.take(700), 13f, Ui.MUTED).apply { setTextIsSelectable(true) })
                    if (m.ended > 0) past.addView(chipRow(chip("Write the notes again", false) {
                        line.text = "Writing the notes of ${m.title}…"
                        Thread {
                            val f = runCatching { Meetings.write(this, m.id) }.getOrNull()
                            main.post { line.text = if (f != null) "Saved ${f.name}" else "The notes could not be written" }
                        }.start()
                    }))
                }
            }
        }.start()
    }

    // ---- Automations

    private fun rulesPage(b: LinearLayout) {
        b.addView(text("Each rule runs on every line the moment it is written. Words: phrases, comma-separated (* any line). " +
            "Who: anyone, me, not me, or a name.", 12.5f, Ui.FAINT).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        for (r in Automations.rules(this)) {
            val row = card()
            row.background = rounded(Ui.SURFACE, dp(20).toFloat(), dp(1), if (r.on) Ui.ACCENT else Ui.STROKE)
            row.addToggle(toggle(r.name, r.summary(), r.on) { on ->
                r.on = on
                Automations.save(this, Automations.rules(this))
                main.post { showMore("rules") }
            })
            if (r.lastAt > 0) row.addView(caps("fired " + SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(r.lastAt)), Ui.ACCENT, 9.5f))
            row.addView(chipRow(chip("Edit", false) { editRule(r) }, chip("Delete", false) {
                Automations.save(this, Automations.rules(this).filter { it.id != r.id })
                showMore("rules")
            }))
            b.addView(row)
        }
        b.addView(chipRow(chip("Add a rule", true) { editRule(null) }))
        val t = card()
        t.addView(caps("try a line"))
        val tryLine = field("Line", "", "hey vox, what did we decide yesterday")
        t.addView(tryLine, lp().apply { topMargin = dp(6) })
        val tryWho = field("Said by", "You", "a name, or You")
        t.addView(tryWho, lp().apply { topMargin = dp(6) })
        val tryOut = text("", 13.5f, Ui.TEXT).apply { setPadding(0, dp(6), 0, 0) }
        t.addView(chipRow(chip("Which rules fire?", true) {
            val who = tryWho.text.toString().trim()
            val person = if (who.equals("you", true) || who.equals("me", true)) Speakers.ME else ""
            val hits = Automations.rules(this).filter { it.on }.mapNotNull { r ->
                Automations.match(r, tryLine.text.toString(), who, person, "")?.let { r.name + (if (it.isNotEmpty()) " (\"$it\")" else "") }
            }
            tryOut.text = if (hits.isEmpty()) "None would fire." else "■ " + hits.joinToString("\n■ ")
            tryOut.setTextColor(if (hits.isEmpty()) Ui.MUTED else Ui.ACCENT)
        }))
        t.addView(tryOut)
        b.addView(t)
    }

    private fun editRule(r: Automations.Rule?) {
        val rule = r ?: Automations.Rule(java.util.UUID.randomUUID().toString().take(8), "", true, "", "", "", mutableSetOf("glasses"), "", "", 30)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = field("Name", rule.name, "what it is for").also { box.addField(it) }
        val words = field("Words", rule.words, "phrases, comma-separated; * any line").also { box.addField(it) }
        val who = field("Who", rule.who, "anyone, me, not me, or a name").also { box.addField(it) }
        val mood = field("Sounding", rule.mood, "blank, or happy / sad / angry / fearful / surprised").also { box.addField(it) }
        val checks = Automations.ACTIONS.map { (k, label) ->
            android.widget.CheckBox(this).apply {
                text = label
                setTextColor(Ui.TEXT)
                isChecked = k in rule.actions
                box.addView(this)
            } to k
        }
        val cmd = field("Vox command", rule.command, "lights off, timer 5 minutes…").also { box.addField(it) }
        val url = field("Webhook URL", rule.url, "https://…").also { box.addField(it) }
        val rest = field("Rest after firing (s)", rule.restS.toString(), "30", numeric = true).also { box.addField(it) }
        dialog().setTitle(if (r == null) "New rule" else "Edit ${r.name}").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                rule.name = name.text.toString().trim().ifEmpty { "Rule" }
                rule.words = words.text.toString().trim()
                rule.who = who.text.toString().trim()
                rule.mood = mood.text.toString().trim().lowercase(Locale.US)
                rule.actions = checks.filter { it.first.isChecked }.map { it.second }.toMutableSet()
                rule.command = cmd.text.toString().trim()
                rule.url = url.text.toString().trim()
                rule.restS = rest.text.toString().toIntOrNull()?.coerceIn(0, 86400) ?: 30
                Automations.save(this, Automations.rules(this).filter { it.id != rule.id } + rule)
                showMore("rules")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- Hearing (and the quiet-voice check)

    private fun hearingPage(b: LinearLayout) {
        val ck = card()
        ck.addView(caps("quiet-voice check"))
        ck.addView(text("The softest voice reads 10 short sentences at their usual volume. Passes at 10/10 near (1 m), 9/10 " +
            "far (3 m), 90% of the words right; if not, it finds settings that hear them. Then remember the voice.", 12.5f, Ui.FAINT))
        ck.addView(segmented(listOf("near" to "Near (1 m)", "far" to "Far (3 m)"), if (far) "far" else "near") { far = it == "far" })
        sentence = text("", 21f, Ui.TEXT, Ui.BLACK).apply { setPadding(0, dp(8), 0, dp(4)) }.also { ck.addView(it) }
        checkState = text("", 13f, Ui.MUTED).also { ck.addView(it) }
        checkList = column().also { ck.addView(it) }
        ck.addView(chipRow(chip("Start the check", true) { startCheck() }, chip("Next sentence", false) { check?.skip() },
            chip("Stop", false) { check?.cancel(); check = null }))
        checkUse = chip("Use these settings", true) {
            suggestion?.let {
                settings.listenSensitivity = it.sensitivity
                settings.listenBoost = it.boost
                checkState?.text = "Set: sensitivity ${it.sensitivity}, boost +${it.boost} dB. Run the check again to confirm."
            }
        }.apply { visibility = View.GONE }.also { ck.addView(it) }
        saveVoice = chip("Remember this voice…", true) { saveCheckedVoice() }.apply { visibility = View.GONE }.also { ck.addView(it) }
        b.addView(ck)

        val how = card()
        how.addView(caps("hearing"))
        how.addView(slider("Sensitivity", 1, 10, settings.listenSensitivity,
            { "$it · threshold ${"%.2f".format(Locale.US, Listen.threshold(it))}" }) { settings.listenSensitivity = it })
        how.addView(slider("Boost for the detector", 0, 24, settings.listenBoost, { "+$it dB" }) { settings.listenBoost = it })
        how.addToggle(toggle("Automatic level", "Lifts a quiet room so soft speech still counts", settings.listenAutoLevel) { settings.listenAutoLevel = it })
        how.addToggle(toggle("Second chance for quiet speech", "Keeps quiet phrases with real words (marked quiet)",
            settings.listenSecondChance) { settings.listenSecondChance = it })
        how.addView(slider("End of a phrase after", 300, 1500, settings.listenPauseMs, { "$it ms of quiet" }) { settings.listenPauseMs = it })
        how.addView(caps("microphone", Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        val sources = mutableListOf("voice" to "Voice", "camcorder" to "Camcorder", "mic" to "Standard")
        if (MicHub.hasRaw(this)) sources.add(1, "raw" to "Raw")
        how.addView(segmented(sources, settings.listenSource) {
            settings.listenSource = it
            MicHub.reopen()
        })
        how.addView(caps("speed", Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        how.addView(segmented(listOf("auto" to "Auto", "live" to "Live", "eco" to "Eco"), settings.listenSpeed) { settings.listenSpeed = it })
        how.addView(text("Auto: at once while you look, charge or have a rule on; else in batches every 2 min.", 12f, Ui.FAINT))
        how.addToggle(toggle("Day files", "Each day's transcript as a file in the docs folder", settings.listenFiles) { settings.listenFiles = it })
        b.addView(how)
    }

    // ---- AI models

    private fun modelRow(name: String, about: String, size: Long, ready: Boolean, progress: Long, problem: String?,
                         onDownload: () -> Unit, onDelete: () -> Unit): View {
        fun gb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(Locale.US, bytes / 1e9) else "${bytes / 1_000_000} MB"
        val r = card()
        r.background = rounded(Ui.SURFACE, dp(20).toFloat(), dp(1), if (ready) Ui.ACCENT else Ui.STROKE)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = column()
        t.addView(text(name, 15.5f, Ui.TEXT, Ui.BOLD))
        t.addView(text("$about · ${gb(size)}", 12.5f, Ui.MUTED))
        t.addView(caps(when {
            progress >= 0 -> "downloading ${gb(progress)}"
            ready -> "ready"
            problem != null -> "stopped: $problem"
            else -> "not downloaded"
        }, if (ready) Ui.GOOD else if (progress >= 0) Ui.ACCENT else Ui.FAINT, 9.5f))
        top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (progress < 0) top.addView(chip(if (ready) "Delete" else "Download", !ready) {
            if (ready) dialog().setTitle("Delete $name?").setPositiveButton("Delete") { _, _ -> onDelete() }
                .setNegativeButton("Cancel", null).show()
            else onDownload()
        })
        r.addView(top)
        if (progress >= 0) r.addView(Progress(this).apply { fraction = progress.toFloat() / size }, lp().apply { topMargin = dp(8) })
        return r
    }

    private fun ticker(page: String, going: () -> Boolean) {
        val r = object : Runnable {
            override fun run() {
                if (tab == "more" && subpage == page) showMore(page)
                if (going()) main.postDelayed(this, 700)
            }
        }
        main.postDelayed(r, 300)
    }

    private fun modelsPage(b: LinearLayout) {
        b.addView(text("On this phone, offline. Get LFM2.5 1.2B first (Ask, Hey Vox, catch-up); a deep model for meeting " +
            "notes; EmbeddingGemma to find by meaning; SenseVoice for tone. Wi-Fi is best.", 12.5f, Ui.FAINT)
            .apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        if (!LlmModels.usable()) b.addView(text("This build or phone cannot run the language models (vox_llm: ${LlmNative.available}, " +
            "CPU: ${LlmModels.cpuOk}).", 13f, Ui.WARN))
        for (m in LlmModels.ALL) {
            val busy = LlmModels.downloading == m.id
            b.addView(modelRow(m.name, m.about, m.part.size, LlmModels.ready(this, m), if (busy) maxOf(0L, LlmModels.progress) else -1,
                if (busy) LlmModels.problem else null, onDownload = {
                    if (LlmModels.downloading.isEmpty()) {
                        Thread { LlmModels.download(this, m); main.post { if (subpage == "models") showMore("models") } }.start()
                        ticker("models") { LlmModels.downloading.isNotEmpty() }
                    }
                }, onDelete = { LlmModels.delete(this, m); showMore("models") }))
        }
        b.addView(modelRow("SenseVoice Small", "tone of voice: emotions and sounds", ToneModel.bytes, ToneModel.ready(this),
            ToneModel.progress, ToneModel.problem, onDownload = {
                Thread { ToneModel.download(this); main.post { if (subpage == "models") showMore("models") } }.start()
                ticker("models") { ToneModel.progress >= 0 }
            }, onDelete = { ToneModel.delete(this); showMore("models") }))
        val c = card()
        c.addToggle(toggle("Tone of voice", "Each line's emotion and sounds", settings.listenTone) { settings.listenTone = it })
        c.addToggle(toggle("Find by meaning", "Meaning vectors for lines of four words or more, made while quiet", settings.listenMeaning) {
            settings.listenMeaning = it
        })
        b.addView(c)
    }

    // ---- Voice recognition

    private fun voicesPage(b: LinearLayout) {
        val ch = VoiceprintModel.chosen(this)
        val c = card()
        c.addView(caps("voiceprint model"))
        c.addView(segmented(VoiceprintModel.CHOICES.map { it.id to it.name }, settings.listenVoiceModel) {
            settings.listenVoiceModel = it
            Listen.peopleChanged()
            showMore("voices")
        })
        c.addView(text("Each model knows people by its own voiceprints: after switching, record the voices again.", 12f, Ui.FAINT))
        b.addView(c)
        b.addView(modelRow(ch.name, ch.about, ch.part.size, VoiceprintModel.ready(this, ch), VoiceprintModel.progress, VoiceprintModel.problem,
            onDownload = {
                Thread { VoiceprintModel.download(this, ch); main.post { Listen.peopleChanged(); if (subpage == "voices") showMore("voices") } }.start()
                ticker("voices") { VoiceprintModel.progress >= 0 }
            }, onDelete = { VoiceprintModel.delete(this, ch); Listen.peopleChanged(); showMore("voices") }))
        val t = card()
        t.addToggle(toggle("Know who speaks", "A voiceprint for each line, matched to the people", settings.listenSpeakers) {
            settings.listenSpeakers = it
            Listen.peopleChanged()
        })
        t.addToggle(toggle("Split where the speaker changes", "A phrase with two people becomes a line each (6 MB more)",
            settings.listenSplit) { settings.listenSplit = it })
        b.addView(t)
    }

    // ------------------------------------------------ the check

    private fun startCheck() {
        if (check != null) return
        if (!micAllowed()) return askMic()
        if (!VoiceModel.ready(this)) {
            checkState?.text = "Download the speech model first (Voice tab)."
            return
        }
        checkList?.removeAllViews()
        checkUse?.visibility = View.GONE
        saveVoice?.visibility = View.GONE
        sentence?.setTextColor(Ui.TEXT)
        suggestion = null
        check = HearingCheck(this, this).also { it.start(far) }
    }

    override fun prompt(i: Int, total: Int, sentence: String) = main.post {
        this.sentence?.text = "${i + 1}/$total   “$sentence”"
    }.let { }

    override fun heard(i: Int, ok: Boolean, got: String, accuracy: Int, quiet: Boolean) = main.post {
        val mark = if (ok) "✓" else "✗"
        val line = "$mark ${i + 1}. " + (if (ok) "$accuracy% · $got" else "not heard") + if (quiet) " (second chance)" else ""
        checkList?.addView(text(line, 13f, if (ok && accuracy >= 90) Ui.GOOD else if (ok) Ui.WARN else Ui.BAD))
    }.let { }

    override fun status(s: String) = main.post { checkState?.text = s }.let { }

    override fun done(r: HearingCheck.Result) = main.post {
        check = null
        sentence?.text = if (r.passed) "Passed" else "Not yet"
        sentence?.setTextColor(if (r.passed) Ui.GOOD else Ui.WARN)
        checkState?.text = "${if (r.far) "Far" else "Near"}: heard ${r.heard}/${r.total}, words ${r.accuracy}% right. ${r.note}"
        suggestion = r.suggestion?.takeIf { it.heard > r.heard }
        checkUse?.visibility = if (suggestion != null) View.VISIBLE else View.GONE
        lastClips = r.clips
        saveVoice?.visibility = if (r.clips.size >= 3) View.VISIBLE else View.GONE
    }.let { }

    private fun saveCheckedVoice() {
        val clips = lastClips
        val name = field("Name", "", "who read the sentences")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
        }
        dialog().setTitle("Remember this voice").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                checkState?.text = "Remembering $n's voice…"
                Thread {
                    val err = Speakers.enroll(this, n, true, clips)
                    main.post {
                        checkState?.text = err?.let { "Not saved: $it" } ?: "Saved $n (soft voice): their lines carry the name."
                        saveVoice?.visibility = View.GONE
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }
}

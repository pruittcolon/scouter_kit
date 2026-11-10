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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import scouterkit.ssh.ListenLook.caps
import scouterkit.ssh.ListenLook.tile
import scouterkit.ssh.ListenLook.tileLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Listen mode's console on the phone. Always on top: the orb (tap: on / off, long press: pause), the trace of the
// last seconds of sound, four tiles (lines today, voices, waiting, speed). Below, six tabs: Live (the transcript as
// it is written, the words being said, search), People (you, the voices to name, the people, how voices are told
// apart), Ask (the phone's LFM about anything said), Meetings (now, today's talk, past notes), Rules (the
// automations), Setup (hearing, the quiet-voice check, the models).
class ListenActivity : Activity(), HearingCheck.Ui {
    companion object {
        const val EXTRA_RESUME = "resume"
        // The Rainbow Passage (Fairbanks, 1960), read to give a voice: about 30 s.
        const val PASSAGE = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The " +
            "rainbow is a division of white light into many beautiful colors. These take the shape of a long round arch, " +
            "with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a " +
            "boiling pot of gold at one end. People look, but no one ever finds it."
        val TABS = listOf("live" to "Live", "people" to "People", "ask" to "Ask", "meet" to "Meet", "rules" to "Rules", "setup" to "Setup")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private val tabs = LinkedHashMap<String, LinearLayout>()
    private var tab = "live"

    // The hero.
    private lateinit var orb: ListenOrb
    private lateinit var trace: ListenTrace
    private lateinit var state: TextView
    private lateinit var sub: TextView
    private lateinit var tLines: TextView
    private lateinit var tVoices: TextView
    private lateinit var tWait: TextView
    private lateinit var tSpeed: TextView

    // Live.
    private lateinit var lines: LinearLayout
    private lateinit var linesTitle: TextView
    private lateinit var search: EditText
    private lateinit var nowRow: LinearLayout
    private lateinit var nowText: TextView
    private var searching = ""
    private var lastLines = 0L

    // People.
    private lateinit var meBox: LinearLayout
    private lateinit var toName: LinearLayout
    private lateinit var peopleBox: LinearLayout
    private lateinit var recordLine: TextView
    private lateinit var modelLine: TextView
    private lateinit var modelChip: TextView
    private lateinit var modelBar: Progress
    private var recorder: VoiceRecorder? = null

    // Ask.
    private lateinit var answer: TextView
    private lateinit var asked: LinearLayout
    private val history = ArrayList<Pair<String, String>>()

    // Meetings.
    private lateinit var meetingLine: TextView
    private lateinit var meetingShare: ShareBars
    private lateinit var meetingsBox: LinearLayout
    private lateinit var todayShare: ShareBars
    private lateinit var insights: TextView
    private lateinit var catchUp: TextView

    // Rules.
    private lateinit var autoBox: LinearLayout

    // Setup.
    private lateinit var sentence: TextView
    private lateinit var checkState: TextView
    private lateinit var checkList: LinearLayout
    private lateinit var checkUse: TextView
    private lateinit var saveVoice: TextView
    private lateinit var modelsBox: LinearLayout
    private var lastClips: List<FloatArray> = emptyList()
    private var check: HearingCheck? = null
    private var far = false
    private var suggestion: HearingCheck.Suggestion? = null

    private val fast = object : Runnable {
        override fun run() {
            val on = Listen.on && !Listen.paused
            val heard = Listen.level + Listen.gainDb
            orb.set(((heard + 70f) / 60f), when {
                !Listen.on -> 0
                Listen.paused -> 3
                Listen.speaking -> 2
                else -> 1
            })
            trace.push(if (on) heard else -100f, on && Listen.speaking, Listen.floor + Listen.gainDb)
            main.postDelayed(this, 120)
        }
    }
    private var counted = 0L
    private val slow = object : Runnable {
        override fun run() {
            showHero()
            showNow()
            Listen.looking(30_000)
            val now = System.currentTimeMillis()
            if (now - counted > 5000) {
                counted = now
                countVoices()
            }
            main.postDelayed(this, 1000)
        }
    }
    private val watcher: () -> Unit = {
        main.post {
            showNow()
            val now = System.currentTimeMillis()
            if (searching.isEmpty() && tab == "live" && now - lastLines > 1200) {
                lastLines = now
                showLines()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val page = page()
        page.addView(header("Listen", "Who said what, as it is said · on this phone", "Done") { finish() })
        page.addView(hero())
        tab = getSharedPreferences("ui", Context.MODE_PRIVATE).getString("listen_tab", "live") ?: "live"
        if (TABS.none { it.first == tab }) tab = "live"
        page.addView(segmented(TABS, tab) { showTab(it) })
        tabs["live"] = buildLive()
        tabs["people"] = buildPeople()
        tabs["ask"] = buildAsk()
        tabs["meet"] = buildMeetings()
        tabs["rules"] = buildRules()
        tabs["setup"] = buildSetup()
        for ((k, v) in tabs) {
            v.visibility = if (k == tab) View.VISIBLE else View.GONE
            page.addView(v)
        }
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) settings.listenOn = true
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
        refresh(tab)
    }

    override fun onPause() {
        super.onPause()
        Listen.unwatch(watcher)
        main.removeCallbacks(fast)
        main.removeCallbacks(slow)
    }

    override fun onDestroy() {
        check?.cancel()
        recorder?.stop()
        super.onDestroy()
    }

    private fun showTab(k: String) {
        tab = k
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("listen_tab", k).apply()
        for ((key, v) in tabs) v.visibility = if (key == k) View.VISIBLE else View.GONE
        refresh(k)
    }

    private fun refresh(k: String) {
        showHero()
        countVoices()
        when (k) {
            "live" -> { showLines(); showNow() }
            "people" -> { showMe(); showPeople(); showModel() }
            "meet" -> { showMeetings(); showInsights() }
            "rules" -> showRules()
            "setup" -> showModels()
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // ------------------------------------------------ the hero

    private fun hero(): View {
        val c = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        orb = ListenOrb(this).apply {
            isClickable = true
            setOnClickListener { toggle() }
            setOnLongClickListener {
                if (Listen.paused) Listen.resume() else if (Listen.on) Listen.pause(15 * 60_000L)
                showHero()
                true
            }
        }
        top.addView(orb, LinearLayout.LayoutParams(dp(112), dp(112)))
        val right = column()
        state = text("", 19f, Ui.TEXT, Ui.BLACK)
        right.addView(state)
        sub = text("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        right.addView(sub)
        right.addView(caps("tap the orb: on / off · hold: pause 15 min", Ui.FAINT, 9.5f).apply { setPadding(0, dp(8), 0, 0) })
        top.addView(right, lp(0, weight = 1f).apply { leftMargin = dp(12) })
        c.addView(top)
        trace = ListenTrace(this)
        c.addView(trace, lp(h = dp(54)).apply { topMargin = dp(12) })
        val (b1, v1) = tile(); tileLabel(b1, "lines today"); tLines = v1
        val (b2, v2) = tile(); tileLabel(b2, "voices"); tVoices = v2
        val (b3, v3) = tile(); tileLabel(b3, "waiting"); tWait = v3
        val (b4, v4) = tile(); tileLabel(b4, "speed"); tSpeed = v4
        c.addView(rowOf(b1, b2, b3, b4), lp().apply { topMargin = dp(12) })
        return c
    }

    private fun showHero() {
        val on = Listen.on
        state.text = when {
            !VoiceModel.ready(this) -> "Speech model needed"
            on && Listen.paused -> "Paused"
            on && Listen.speaking -> "Hearing speech"
            on -> Listen.state.replaceFirstChar { it.uppercase() }
            settings.listenOn -> "Starting…"
            else -> "Off"
        }
        state.setTextColor(when {
            on && Listen.paused -> Ui.WARN
            on && Listen.speaking -> Ui.ACCENT
            on -> Ui.ACCENT2
            else -> Ui.TEXT
        })
        val ready = listOfNotNull(
            if (settings.listenSpeakers && VoiceprintModel.ready(this)) "voices" else null,
            if (settings.listenTone && ToneModel.ready(this)) "tone" else null,
            if (LlmModels.usable() && LlmModels.ALL.any { it.role != "embed" && LlmModels.ready(this, it) }) "LFM" else null,
            if (settings.listenMeaning && LlmModels.ready(this, LlmModels.EMBED)) "meaning" else null,
            Automations.rules(this).count { it.on }.takeIf { it > 0 }?.let { "$it rules" },
        )
        sub.text = when {
            !VoiceModel.ready(this) -> "Download it on the Voice tab first."
            !micAllowed() -> "The microphone is not allowed yet: tap the orb."
            MicHub.silenced -> "Another app has the microphone; back when it lets go."
            on && Listen.paused -> "Back at " + SimpleDateFormat("h:mm a", Locale.US).format(Date(Listen.pausedUntil)) + ". Hold the orb to listen now."
            Meetings.current != null -> "In a meeting: ${Meetings.current?.title}"
            else -> (if (ready.isEmpty()) "Models: Setup tab" else ready.joinToString(" · ")) + (MicHub.problem?.let { " · $it" } ?: "")
        }
        tLines.text = Listen.today.toString()
        tWait.text = if (Listen.waiting >= 1) "${Listen.waiting.toInt()}s" else "0"
        tSpeed.text = when (settings.listenSpeed) {
            "live" -> "Live"
            "eco" -> "Eco"
            else -> if (Listen.speed == "live") "Live" else if (Listen.on) "Eco" else "Auto"
        }
        tSpeed.setTextColor(if (tSpeed.text == "Live") Ui.ACCENT else Ui.TEXT)
    }

    private fun countVoices() {
        Thread {
            val db = ListenDb.get(this)
            val people = db.personList().size
            val unnamed = db.voiceList().size
            main.post {
                tVoices.text = if (unnamed > 0) "$people+$unnamed" else "$people"
                tVoices.setTextColor(if (unnamed > 0) Ui.WARN else Ui.TEXT)
            }
        }.start()
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 41)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (micAllowed() && settings.listenOn) ListenService.start(this)
        showHero()
    }

    private fun toggle() {
        if (Listen.on || ListenService.running) ListenService.stop(this)
        else {
            settings.listenOn = true
            if (!micAllowed()) askMic() else ListenService.start(this)
        }
        main.postDelayed({ showHero() }, 300)
    }

    // ------------------------------------------------ Live

    private fun buildLive(): LinearLayout {
        val box = column()
        val c = card()
        linesTitle = caps("live transcript")
        c.addView(linesTitle)
        search = field("Search", "", "search every day, by words or meaning").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> runSearch(); true }
        }
        c.addView(search, lp().apply { topMargin = dp(8) })
        c.addView(chipRow(chip("Search", true) { runSearch() }, chip("Live", false) {
            searching = ""
            search.setText("")
            showLines()
        }))
        nowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Ui.RAISED, dp(14).toFloat(), dp(1), Ui.ACCENT)
            visibility = View.GONE
        }
        nowRow.addView(Pulse(this))
        nowText = text("", 15f, Ui.ACCENT, Ui.BOLD)
        nowRow.addView(nowText, lp(0, weight = 1f).apply { leftMargin = dp(10) })
        c.addView(nowRow, lp().apply { topMargin = dp(10) })
        lines = column()
        c.addView(lines)
        c.addView(text("Newest on top. Every day is also a file, \"Transcript <date>.md\", in Vox's docs folder (Alt+N " +
            "on the glasses). The glasses' live view: hub L or Alt+E.", 12f, Ui.FAINT).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(c)
        return box
    }

    private fun showNow() {
        if (!::nowRow.isInitialized) return
        val talking = Listen.on && !Listen.paused && (Listen.speaking || Listen.partial.isNotEmpty())
        nowRow.visibility = if (talking && searching.isEmpty()) View.VISIBLE else View.GONE
        nowText.text = Listen.partial.ifEmpty { "hearing…" }
    }

    // The transcript, newest first: a header (who, when, mood, the rules it fired) where the speaker changes.
    private fun showLines() {
        val q = searching
        Thread {
            val db = ListenDb.get(this)
            val names = db.names()
            val hits = if (q.isEmpty()) emptyList() else runCatching { Search.find(this, q, 80) }.getOrDefault(emptyList())
            val how = hits.associate { it.line.id to (if (it.meaning && !it.words) "by meaning" else "") }
            val list = if (q.isEmpty()) db.between(ListenDb.dayStart(System.currentTimeMillis()), Long.MAX_VALUE).takeLast(80).reversed()
                else hits.map { it.line }
            val fired = Listen.recent(60).associate { it.id to it.fired }
            main.post {
                linesTitle.text = if (q.isEmpty()) "LIVE TRANSCRIPT · TODAY · ${list.size} NEWEST" else "\"${q.uppercase()}\" · ${list.size} FOUND"
                lines.removeAllViews()
                if (list.isEmpty()) lines.addView(text(if (q.isEmpty()) "Nothing heard yet today." else "Nothing found.", 13f, Ui.FAINT)
                    .apply { setPadding(0, dp(10), 0, 0) })
                val fmt = SimpleDateFormat(if (q.isEmpty()) "h:mm:ss a" else "MMM d, h:mm a", Locale.US)
                var last = "\u0000"
                for (l in list) {
                    val who = names[l.person.ifEmpty { l.voice }] ?: ""
                    val col = ListenLook.color(who)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(View(this).apply { background = rounded(col, dp(2).toFloat()) }, LinearLayout.LayoutParams(dp(3),
                        LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(10) })
                    val col2 = column()
                    if (who != last || q.isNotEmpty()) {
                        val tags = listOfNotNull(fmt.format(Date(l.start)), l.emotion.ifEmpty { null }, l.sound.ifEmpty { null },
                            if (l.quiet) "quiet" else null, how[l.id]?.ifEmpty { null }, fired[l.id]?.ifEmpty { null }?.let { "■ $it" })
                        col2.addView(caps((who.ifEmpty { "someone" }) + " · " + tags.joinToString(" · "), col, 10f)
                            .apply { setPadding(0, dp(10), 0, dp(2)) })
                    } else if (fired[l.id]?.isNotEmpty() == true || l.emotion.isNotEmpty()) {
                        col2.addView(caps(listOfNotNull(l.emotion.ifEmpty { null }, fired[l.id]?.ifEmpty { null }?.let { "■ $it" })
                            .joinToString(" · "), col, 9.5f).apply { setPadding(0, dp(4), 0, 0) })
                    }
                    col2.addView(text(l.text, 15f, if (l.quiet) Ui.MUTED else Ui.TEXT).apply { setPadding(0, dp(2), 0, dp(4)) })
                    row.addView(col2, lp(0, weight = 1f))
                    lines.addView(row, lp())
                    last = who
                }
            }
        }.start()
    }

    private fun runSearch() {
        searching = search.text.toString().trim()
        showLines()
        showNow()
    }

    // ------------------------------------------------ People

    private fun buildPeople(): LinearLayout {
        val box = column()
        meBox = card()
        box.addView(meBox)
        val tn = card()
        tn.addView(caps("voices to name"))
        tn.addView(text("Each is a voice Vox heard but was not told about, with its newest lines. Name it, or say it is you; " +
            "two voices of one person: give both the same name.", 12.5f, Ui.FAINT).apply { setPadding(0, dp(4), 0, dp(4)) })
        toName = column()
        tn.addView(toName)
        box.addView(tn)
        val pc = card()
        pc.addView(caps("people"))
        peopleBox = column()
        pc.addView(peopleBox)
        box.addView(pc)
        val rc = card()
        rc.addView(caps("telling voices apart"))
        rc.addToggle(toggle("Know who speaks", "A voiceprint for each line, matched to the people", settings.listenSpeakers) {
            settings.listenSpeakers = it
            Listen.peopleChanged()
        })
        rc.addToggle(toggle("Split where the speaker changes", "A phrase with two people becomes a line each (pyannote, 6 MB)",
            settings.listenSplit) { settings.listenSplit = it })
        rc.addView(caps("voiceprint model", Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        rc.addView(segmented(VoiceprintModel.CHOICES.map { it.id to it.name }, settings.listenVoiceModel) {
            settings.listenVoiceModel = it
            showModel()
            Listen.peopleChanged()
        })
        modelLine = text("", 12.5f, Ui.MUTED)
        modelChip = chip("Download", true) { modelAction() }
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mrow.addView(modelLine, lp(0, weight = 1f))
        mrow.addView(modelChip)
        rc.addView(mrow)
        modelBar = Progress(this).apply { visibility = View.GONE }
        rc.addView(modelBar, lp().apply { topMargin = dp(6) })
        rc.addView(text("Each model knows people by its own voiceprints: after switching, record the voices again.", 12f, Ui.FAINT)
            .apply { setPadding(0, dp(6), 0, 0) })
        box.addView(rc)
        return box
    }

    private fun showMe() {
        Thread {
            val db = ListenDb.get(this)
            val me = db.personList().firstOrNull { it.first == Speakers.ME }
            val n = if (me != null) db.sampleCount(Speakers.ME) else 0
            main.post {
                meBox.removeAllViews()
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(Avatar(this, "You", 52))
                val t = column()
                t.addView(text("You", 17f, Ui.TEXT, Ui.BLACK))
                t.addView(text(if (me != null) "Known by $n voiceprints · your lines say You" else "Not recorded yet: 30 s makes " +
                    "your lines say You", 12.5f, if (me != null) Ui.GOOD else Ui.WARN))
                top.addView(t, lp(0, weight = 1f).apply { leftMargin = dp(12) })
                meBox.addView(top)
                recordLine = text("Read this aloud at your usual volume (about 30 s):\n\n“$PASSAGE”", 13f, Ui.MUTED)
                    .apply { setPadding(0, dp(10), 0, dp(4)) }
                meBox.addView(recordLine)
                meBox.addView(chipRow(chip(if (me != null) "Record again (adds to it)" else "Record my voice (30 s)", true) { recordMe() }))
            }
        }.start()
    }

    private fun personRow(name: String, lineText: String, lineColor: Int): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2))
        }
        r.addView(Avatar(this, name, 42))
        val t = column()
        t.addView(text(name, 15.5f, Ui.TEXT, Ui.BOLD))
        t.addView(text(lineText, 12.5f, lineColor))
        r.addView(t, lp(0, weight = 1f).apply { leftMargin = dp(12) })
        return r
    }

    private fun showPeople() {
        Thread {
            val db = ListenDb.get(this)
            val people = db.personList().filter { it.first != Speakers.ME }.map { (id, name, soft) -> Triple(id, name, soft) to db.sampleCount(id) }
            val voices = db.voiceList()
            main.post {
                toName.removeAllViews()
                if (voices.isEmpty()) toName.addView(text("None waiting: every voice heard has a name.", 13f, Ui.GOOD).apply { setPadding(0, dp(8), 0, 0) })
                for ((v, n, quotes) in voices) {
                    toName.addView(personRow(v.label, "$n lines · heard ${SimpleDateFormat("MMM d h:mm a", Locale.US).format(Date(v.updated))}", Ui.MUTED))
                    for (q in quotes) toName.addView(text("“$q”", 13f, Ui.MUTED).apply { setPadding(dp(54), dp(2), 0, 0) })
                    toName.addView(chipRow(
                        chip("Name…", true) {
                            ask("Who is ${v.label}?", "") { name ->
                                val pid = if (name.equals("me", true) || name.equals("you", true)) Speakers.ME.also {
                                    if (db.personList().none { p -> p.first == it }) db.addPerson("You", false, it)
                                } else db.personNamed(name) ?: db.addPerson(name, false)
                                db.nameVoice(v.id, pid)
                                Listen.peopleChanged()
                                main.post { refresh("people") }
                            }
                        },
                        chip("That's me", false) {
                            Thread {
                                if (db.personList().none { it.first == Speakers.ME }) db.addPerson("You", false, Speakers.ME)
                                db.nameVoice(v.id, Speakers.ME)
                                Listen.peopleChanged()
                                main.post { refresh("people") }
                            }.start()
                        },
                    ).apply { setPadding(dp(54), 0, 0, 0) })
                }
                peopleBox.removeAllViews()
                if (people.isEmpty()) peopleBox.addView(text("Nobody yet: name a voice above, or remember one from the " +
                    "quiet-voice check (Setup).", 13f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
                for ((p, n) in people) {
                    val (id, name, soft) = p
                    peopleBox.addView(personRow(name, "$n voiceprints" + if (soft) " · soft voice" else "", if (soft) Ui.ACCENT else Ui.MUTED))
                    peopleBox.addView(chipRow(
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
                    ).apply { setPadding(dp(54), 0, 0, 0) })
                }
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
            recordLine.text = "Download the voiceprint model first (below)."
            return
        }
        recorder = VoiceRecorder(this, 30, tick = { left, lv ->
            main.post { recordLine.text = "● Recording… $left s left (${lv.toInt()} dB)\n\n“$PASSAGE”" }
        }, done = { clips, why ->
            main.post { recordLine.text = if (why != null) "Not recorded: $why" else "Remembering your voice…" }
            if (why == null) {
                val err = Speakers.enroll(this, "You", false, clips, Speakers.ME)
                main.post {
                    recordLine.text = err?.let { "Not saved: $it" } ?: "Saved: your lines say You."
                    showMe()
                }
            }
            recorder = null
        }).also { it.start() }
    }

    private fun showModel() {
        val ch = VoiceprintModel.chosen(this)
        val ready = VoiceprintModel.ready(this, ch)
        fun mb(b: Long) = "${b / 1_000_000} MB"
        val busy = VoiceprintModel.progress >= 0
        modelLine.text = when {
            busy -> "Downloading ${ch.name}… ${mb(VoiceprintModel.progress)} of ${mb(ch.part.size)}"
            ready -> "${ch.name} ready · ${ch.about} · ${mb(ch.part.size)}"
            VoiceprintModel.problem != null -> "Not downloaded: ${VoiceprintModel.problem}"
            else -> "${ch.name}: ${ch.about} · ${mb(ch.part.size)}"
        }
        modelLine.setTextColor(if (ready) Ui.GOOD else Ui.MUTED)
        modelChip.visibility = if (busy) View.GONE else View.VISIBLE
        modelChip.text = if (ready) "Delete" else "Download"
        modelBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) modelBar.fraction = VoiceprintModel.progress.toFloat() / ch.part.size
    }

    private fun modelAction() {
        val ch = VoiceprintModel.chosen(this)
        if (VoiceprintModel.ready(this, ch)) {
            dialog().setTitle("Delete the ${ch.name} model?").setMessage("Names stop until it is downloaded again.")
                .setPositiveButton("Delete") { _, _ ->
                    VoiceprintModel.delete(this, ch)
                    Listen.peopleChanged()
                    showModel()
                }.setNegativeButton("Cancel", null).show()
            return
        }
        Thread {
            VoiceprintModel.download(this, ch)
            main.post { showModel(); Listen.peopleChanged() }
        }.start()
        ticker { showModel(); VoiceprintModel.progress >= 0 }
    }

    // Runs show() every half second while it says to go on.
    private fun ticker(show: () -> Boolean) {
        val r = object : Runnable {
            override fun run() {
                if (show()) main.postDelayed(this, 500)
            }
        }
        main.postDelayed(r, 300)
    }

    // ------------------------------------------------ Ask

    private fun buildAsk(): LinearLayout {
        val box = column()
        val c = card()
        c.addView(caps("ask about anything said"))
        c.addView(text("\"What did Maria say about the trip?\" · \"What did we decide yesterday?\" · \"Did anyone mention the " +
            "dentist this week?\" Answers come from the transcript, on this phone. Quick: LFM2.5 1.2B, seconds. Deep: the " +
            "biggest downloaded (8B-A1B thinks first). On the glasses: A, or say \"hey vox, …\".", 12.5f, Ui.FAINT)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val question = field("Question", "", "ask about any day")
        c.addView(question)
        val ans = column().apply {
            background = rounded(Ui.RAISED, dp(16).toFloat(), dp(1), Ui.STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        val ansHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ansHead.addView(Avatar(this, "Vox", 28))
        ansHead.addView(caps("vox · on this phone", Ui.ACCENT).apply { setPadding(dp(8), 0, 0, 0) })
        ans.addView(ansHead)
        answer = text("", 15f, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
        ans.addView(answer)
        fun go(deep: Boolean) {
            val q = question.text.toString().trim()
            if (q.isEmpty()) return
            ans.visibility = View.VISIBLE
            if (!LlmModels.usable()) return run { answer.text = "This phone or build cannot run the models." }
            if (LlmModels.ALL.none { it.role != "embed" && LlmModels.ready(this, it) }) return run { answer.text = "Download LFM2.5 1.2B first (Setup tab, AI models)." }
            answer.text = "Thinking…"
            Thread {
                val got = runCatching { Search.ask(this, q, deep) { t -> main.post { answer.text = t.ifEmpty { "Thinking…" } } } }
                    .getOrElse { "It stopped: ${it.message}" } ?: "Download LFM2.5 1.2B first (Setup tab, AI models)."
                main.post {
                    answer.text = got
                    history.add(0, q to got)
                    showHistory()
                }
            }.start()
        }
        c.addView(chipRow(chip("Ask", true) { go(false) }, chip("Ask deep", false) { go(true) }))
        c.addView(ans, lp().apply { topMargin = dp(10) })
        box.addView(c)
        val h = card()
        h.addView(caps("asked before"))
        asked = column()
        h.addView(asked)
        box.addView(h)
        showHistory()
        return box
    }

    private fun showHistory() {
        asked.removeAllViews()
        if (history.isEmpty()) asked.addView(text("Your questions and their answers show here.", 13f, Ui.FAINT))
        for ((q, a) in history.take(10)) {
            asked.addView(text(q, 14f, Ui.ACCENT, Ui.BOLD).apply { setPadding(0, dp(10), 0, dp(2)) })
            asked.addView(text(a, 13.5f, Ui.MUTED).apply { setTextIsSelectable(true) })
        }
    }

    // ------------------------------------------------ Meetings

    private fun buildMeetings(): LinearLayout {
        val box = column()
        val c = card()
        c.addView(caps("meeting"))
        meetingLine = text("", 18f, Ui.TEXT, Ui.BLACK).apply { setPadding(0, dp(6), 0, dp(4)) }
        c.addView(meetingLine)
        meetingShare = ShareBars(this)
        c.addView(meetingShare, lp().apply { topMargin = dp(6) })
        val title = field("Title", "", "what it is about (optional)")
        c.addView(title, lp().apply { topMargin = dp(8) })
        catchUp = text("", 13.5f, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
        c.addView(chipRow(
            chip("Start / end", true) {
                if (Meetings.current != null) Meetings.stop(this) else Meetings.start(this, title.text.toString().trim())
                main.postDelayed({ showMeetings() }, 300)
            },
            chip("Bookmark", false) { Thread { Meetings.bookmark(this) }.start(); catchUp.text = "Bookmarked the last 30 s." },
            chip("Catch me up", false) {
                catchUp.text = "Thinking…"
                Thread {
                    val t = runCatching { Meetings.catchUp(this) { s -> main.post { catchUp.text = s } } }.getOrNull()
                    main.post { catchUp.text = t ?: "Download LFM2.5 1.2B first (Setup tab)." }
                }.start()
            },
        ))
        c.addView(catchUp)
        c.addToggle(toggle("Start from the calendar", "An event with two or more people starts a meeting while Listen is on; " +
            "it ends 5 min after the event", settings.listenMeetingsAuto) { settings.listenMeetingsAuto = it })
        c.addView(text("When a meeting ends, the phone's LFM writes its notes: summary, decisions, action items, open " +
            "questions, your bookmarks and notes, who talked how much, the transcript, as a file (Alt+N on the glasses). " +
            "Glasses' live view: S start / end, B bookmark, I action item, T note, C catch me up.", 12f, Ui.FAINT)
            .apply { setPadding(0, dp(6), 0, 0) })
        box.addView(c)
        val t = card()
        t.addView(caps("today's talk"))
        todayShare = ShareBars(this)
        t.addView(todayShare, lp().apply { topMargin = dp(8) })
        insights = text("", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }
        t.addView(insights)
        box.addView(t)
        val p = card()
        p.addView(caps("past meetings"))
        meetingsBox = column()
        p.addView(meetingsBox)
        box.addView(p)
        return box
    }

    private fun shareRows(st: Words.Stats): List<Triple<String, Float, String>> = st.people.take(6).map {
        val m = (it.seconds / 60).toInt()
        val s = (it.seconds % 60).toInt()
        Triple(it.who, (it.seconds / maxOf(1.0, st.seconds)).toFloat(), "${Words.pct(it.seconds, st.seconds)}% · $m:${"%02d".format(s)}")
    }

    private fun showMeetings() {
        val cur = Meetings.current
        meetingLine.text = when {
            cur != null -> "● ${cur.title} · ${(System.currentTimeMillis() - cur.started) / 60000} min"
            Meetings.writing.isNotEmpty() -> "Writing the notes of ${Meetings.writing}…"
            else -> "No meeting now"
        }
        meetingLine.setTextColor(if (cur != null) Ui.ACCENT else Ui.TEXT)
        Thread {
            val st = Meetings.stats(this)
            val list = ListenDb.get(this).meetings(10)
            main.post {
                meetingShare.visibility = if (st != null && st.lines > 0) View.VISIBLE else View.GONE
                if (st != null) meetingShare.set(shareRows(st))
                meetingsBox.removeAllViews()
                if (list.isEmpty()) meetingsBox.addView(text("None yet.", 13f, Ui.FAINT))
                val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                for (m in list) {
                    meetingsBox.addView(text(m.title, 15f, Ui.TEXT, Ui.BOLD).apply { setPadding(0, dp(12), 0, 0) })
                    meetingsBox.addView(caps(fmt.format(Date(m.started)) + (if (m.ended > 0) " · ${(m.ended - m.started) / 60000} min" else " · going on"),
                        Ui.FAINT, 9.5f))
                    if (m.summary.isNotEmpty()) meetingsBox.addView(text(m.summary.take(700), 13f, Ui.MUTED).apply {
                        setPadding(0, dp(4), 0, 0)
                        setTextIsSelectable(true)
                    })
                    if (m.ended > 0) meetingsBox.addView(chipRow(chip("Write the notes again", false) {
                        meetingLine.text = "Writing the notes of ${m.title}…"
                        Thread {
                            val f = runCatching { Meetings.write(this, m.id) }.getOrNull()
                            main.post {
                                meetingLine.text = if (f != null) "Saved ${f.name}" else "The notes could not be written"
                                showMeetings()
                            }
                        }.start()
                    }))
                }
            }
        }.start()
    }

    private fun showInsights() {
        Thread {
            val db = ListenDb.get(this)
            val day = ListenDb.dayStart(System.currentTimeMillis())
            val (base, n) = Words.baseline(this)
            val st = Words.stats(db.between(day, Long.MAX_VALUE), db.names(), base, n)
            main.post {
                todayShare.set(shareRows(st))
                insights.text = if (st.lines == 0) "Nothing heard today yet." else Words.report(st)
            }
        }.start()
    }

    // ------------------------------------------------ Rules

    private fun buildRules(): LinearLayout {
        val box = column()
        val c = card()
        c.addView(caps("automations"))
        c.addView(text("Each rule runs on every line the moment it is written. Words: phrases, comma-separated (* any line). " +
            "Who: anyone, me, not me, or a name. Listen reads at once while any rule is on.", 12.5f, Ui.FAINT)
            .apply { setPadding(0, dp(4), 0, dp(4)) })
        autoBox = column()
        c.addView(autoBox)
        c.addView(chipRow(chip("Add a rule", true) { editRule(null) }))
        box.addView(c)
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
        box.addView(t)
        return box
    }

    private fun showRules() {
        autoBox.removeAllViews()
        for (r in Automations.rules(this)) {
            val row = column().apply {
                background = rounded(Ui.RAISED, dp(16).toFloat(), dp(1), if (r.on) Ui.ACCENT else Ui.STROKE)
                setPadding(dp(14), dp(4), dp(14), dp(8))
            }
            row.addToggle(toggle(r.name, r.summary(), r.on) { on ->
                r.on = on
                Automations.save(this, Automations.rules(this))
                main.post { showRules() }
            })
            if (r.lastAt > 0) row.addView(caps("fired " + SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(r.lastAt)), Ui.ACCENT, 9.5f))
            row.addView(chipRow(chip("Edit", false) { editRule(r) }, chip("Delete", false) {
                Automations.save(this, Automations.rules(this).filter { it.id != r.id })
                showRules()
            }))
            autoBox.addView(row, lp().apply { topMargin = dp(10) })
        }
    }

    // A rule's fields in a dialog (new when r is null).
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
        dialog().setTitle(if (r == null) "New rule" else "Edit ${r.name}").setView(android.widget.ScrollView(this).apply { addView(box) })
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
                showRules()
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------ Setup

    private fun buildSetup(): LinearLayout {
        val box = column()
        val sp = card()
        sp.addView(caps("speed"))
        sp.addView(segmented(listOf("auto" to "Auto", "live" to "Live", "eco" to "Eco"), settings.listenSpeed) { settings.listenSpeed = it })
        sp.addView(text("Auto: at once while you look (the glasses' live view, this page), while charging or while a rule " +
            "is on; otherwise in batches every 2 minutes so the phone rests. Nothing is lost either way.", 12f, Ui.FAINT))
        box.addView(sp)

        val how = card()
        how.addView(caps("hearing"))
        how.addView(text("For soft voices: raise the sensitivity or the boost, keep the second chance on, then run the check " +
            "below. The trace on top shows it: speech should rise well over the dashed line.", 12.5f, Ui.FAINT))
        how.addView(slider("Sensitivity", 1, 10, settings.listenSensitivity,
            { "$it · threshold ${"%.2f".format(Locale.US, Listen.threshold(it))}" }) { settings.listenSensitivity = it })
        how.addView(slider("Boost for the detector", 0, 24, settings.listenBoost, { "+$it dB" }) { settings.listenBoost = it })
        how.addToggle(toggle("Automatic level", "Lifts a quiet room so soft speech still counts (up to +18 dB more)",
            settings.listenAutoLevel) { settings.listenAutoLevel = it })
        how.addToggle(toggle("Second chance for quiet speech", "A keener detector keeps quiet phrases the first one missed " +
            "when they hold real words (marked quiet)", settings.listenSecondChance) { settings.listenSecondChance = it })
        how.addView(slider("End of a phrase after", 300, 1500, settings.listenPauseMs, { "$it ms of quiet" }) { settings.listenPauseMs = it })
        how.addView(caps("microphone", Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        val sources = mutableListOf("voice" to "Voice", "camcorder" to "Camcorder", "mic" to "Standard")
        if (MicHub.hasRaw(this)) sources.add(1, "raw" to "Raw")
        how.addView(segmented(sources, settings.listenSource) {
            settings.listenSource = it
            MicHub.reopen()
        })
        how.addToggle(toggle("Day files", "Each day's transcript as a file in Vox's docs folder (Alt+N on the glasses)",
            settings.listenFiles) { settings.listenFiles = it })
        box.addView(how)

        val ck = card()
        ck.addView(caps("quiet-voice check"))
        ck.addView(text("The softest voice reads 10 short sentences at their usual volume. Near: about 1 m. Far: across the " +
            "room. Passes at 10/10 near, 9/10 far, 90% of the words right; if not, it tries other settings on the same " +
            "recording. Then remember the voice: it gets the name and a lower bar (soft voice).", 12.5f, Ui.FAINT))
        ck.addView(segmented(listOf("near" to "Near (1 m)", "far" to "Far (3 m)"), "near") { far = it == "far" })
        sentence = text("", 21f, Ui.TEXT, Ui.BLACK).apply { setPadding(0, dp(10), 0, dp(6)) }
        ck.addView(sentence)
        checkState = text("", 13f, Ui.MUTED)
        ck.addView(checkState)
        checkList = column()
        ck.addView(checkList)
        ck.addView(chipRow(chip("Start the check", true) { startCheck() }, chip("Next sentence", false) { check?.skip() },
            chip("Stop", false) { check?.cancel(); check = null }))
        checkUse = chip("Use these settings", true) {
            suggestion?.let {
                settings.listenSensitivity = it.sensitivity
                settings.listenBoost = it.boost
                checkState.text = "Set: sensitivity ${it.sensitivity}, boost +${it.boost} dB. Run the check again to confirm."
                recreate()
            }
        }.apply { visibility = View.GONE }
        ck.addView(checkUse)
        saveVoice = chip("Remember this voice…", true) { saveCheckedVoice() }.apply { visibility = View.GONE }
        ck.addView(saveVoice)
        box.addView(ck)

        val ai = card()
        ai.addView(caps("ai models · on this phone, offline"))
        ai.addView(text("Liquid's LFM2.5 answers and writes meeting notes; EmbeddingGemma finds by meaning; SenseVoice hears " +
            "the tone. Wi-Fi is best.", 12.5f, Ui.FAINT))
        modelsBox = column()
        ai.addView(modelsBox)
        ai.addToggle(toggle("Tone of voice", "Each line's emotion and sounds (laughter, music…)", settings.listenTone) { settings.listenTone = it })
        ai.addToggle(toggle("Search by meaning", "A meaning vector for lines of four words or more, made while the phone is " +
            "quiet", settings.listenMeaning) { settings.listenMeaning = it })
        box.addView(ai)
        return box
    }

    // One model as a row: name, what for, size and state, a bar while downloading, Download / Delete.
    private fun modelRow(name: String, about: String, size: Long, ready: Boolean, progress: Long, problem: String?,
                         onDownload: () -> Unit, onDelete: () -> Unit): View {
        fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(Locale.US, b / 1e9) else "${b / 1_000_000} MB"
        val r = column().apply {
            background = rounded(Ui.RAISED, dp(16).toFloat(), dp(1), if (ready) Ui.ACCENT else Ui.STROKE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = column()
        t.addView(text(name, 15f, Ui.TEXT, Ui.BOLD))
        t.addView(text("$about · ${gb(size)}", 12f, Ui.MUTED))
        t.addView(caps(when {
            progress >= 0 -> "downloading ${gb(progress)}"
            ready -> "ready"
            problem != null -> "stopped: $problem"
            else -> "not downloaded"
        }, if (ready) Ui.GOOD else if (progress >= 0) Ui.ACCENT else Ui.FAINT, 9.5f))
        top.addView(t, lp(0, weight = 1f))
        if (progress < 0) top.addView(chip(if (ready) "Delete" else "Download", !ready) {
            if (ready) dialog().setTitle("Delete $name?").setPositiveButton("Delete") { _, _ -> onDelete() }
                .setNegativeButton("Cancel", null).show()
            else onDownload()
        })
        r.addView(top)
        if (progress >= 0) r.addView(Progress(this).apply { fraction = progress.toFloat() / size }, lp().apply { topMargin = dp(8) })
        return r
    }

    private fun showModels() {
        modelsBox.removeAllViews()
        if (!LlmModels.usable()) modelsBox.addView(text("This build or phone cannot run the language models (vox_llm: " +
            "${LlmNative.available}, CPU: ${LlmModels.cpuOk}).", 13f, Ui.WARN))
        for (m in LlmModels.ALL) {
            val busy = LlmModels.downloading == m.id
            modelsBox.addView(modelRow(m.name, m.about, m.part.size, LlmModels.ready(this, m), if (busy) maxOf(0L, LlmModels.progress) else -1,
                if (busy) LlmModels.problem else null, onDownload = {
                    if (LlmModels.downloading.isEmpty()) {
                        Thread { LlmModels.download(this, m); main.post { showModels() } }.start()
                        ticker { showModels(); LlmModels.downloading.isNotEmpty() }
                    }
                }, onDelete = { LlmModels.delete(this, m); showModels() }), lp().apply { topMargin = dp(10) })
        }
        modelsBox.addView(modelRow("SenseVoice Small", "tone of voice: emotions and sounds", ToneModel.bytes, ToneModel.ready(this),
            ToneModel.progress, ToneModel.problem, onDownload = {
                Thread { ToneModel.download(this); main.post { showModels() } }.start()
                ticker { showModels(); ToneModel.progress >= 0 }
            }, onDelete = { ToneModel.delete(this); showModels() }), lp().apply { topMargin = dp(10) })
    }

    // ------------------------------------------------ the check

    private fun startCheck() {
        if (check != null) return
        if (!micAllowed()) return askMic()
        if (!VoiceModel.ready(this)) {
            checkState.text = "Download the speech model first (Voice tab)."
            return
        }
        checkList.removeAllViews()
        checkUse.visibility = View.GONE
        saveVoice.visibility = View.GONE
        sentence.setTextColor(Ui.TEXT)
        suggestion = null
        check = HearingCheck(this, this).also { it.start(far) }
    }

    override fun prompt(i: Int, total: Int, sentence: String) = main.post {
        this.sentence.text = "${i + 1}/$total   “$sentence”"
    }.let { }

    override fun heard(i: Int, ok: Boolean, got: String, accuracy: Int, quiet: Boolean) = main.post {
        val mark = if (ok) "✓" else "✗"
        val line = "$mark ${i + 1}. " + (if (ok) "$accuracy% · $got" else "not heard") + if (quiet) " (second chance)" else ""
        checkList.addView(text(line, 13f, if (ok && accuracy >= 90) Ui.GOOD else if (ok) Ui.WARN else Ui.BAD))
    }.let { }

    override fun status(s: String) = main.post { checkState.text = s }.let { }

    override fun done(r: HearingCheck.Result) = main.post {
        check = null
        sentence.text = if (r.passed) "Passed" else "Not yet"
        sentence.setTextColor(if (r.passed) Ui.GOOD else Ui.WARN)
        checkState.text = "${if (r.far) "Far" else "Near"}: heard ${r.heard}/${r.total}, words ${r.accuracy}% right. ${r.note}"
        suggestion = r.suggestion?.takeIf { it.heard > r.heard }
        checkUse.visibility = if (suggestion != null) View.VISIBLE else View.GONE
        lastClips = r.clips
        saveVoice.visibility = if (r.clips.size >= 3) View.VISIBLE else View.GONE
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
                checkState.text = "Remembering $n's voice…"
                Thread {
                    val err = Speakers.enroll(this, n, true, clips)
                    main.post {
                        checkState.text = err?.let { "Not saved: $it" } ?: "Saved $n (soft voice): their lines carry the name."
                        saveVoice.visibility = View.GONE
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }
}

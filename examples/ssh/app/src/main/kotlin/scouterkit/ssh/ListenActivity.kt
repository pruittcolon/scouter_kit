package scouterkit.ssh

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Listen mode on the phone: on / off and how it is doing, how it hears (a live level meter, the hearing settings,
// the quiet-voice check), and what it heard (today's lines, and a search through every day).
class ListenActivity : Activity(), HearingCheck.Ui {
    companion object {
        const val EXTRA_RESUME = "resume"
        // The Rainbow Passage (Fairbanks, 1960), read to give a voice: about 30 s.
        const val PASSAGE = "When the sunlight strikes raindrops in the air, they act as a prism and form a rainbow. The " +
            "rainbow is a division of white light into many beautiful colors. These take the shape of a long round arch, " +
            "with its path high above, and its two ends apparently beyond the horizon. There is, according to legend, a " +
            "boiling pot of gold at one end. People look, but no one ever finds it."
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var state: TextView
    private lateinit var stats: TextView
    private lateinit var onOff: TextView
    private lateinit var pause: TextView
    private lateinit var meter: LevelMeter
    private lateinit var meterText: TextView
    private lateinit var sentence: TextView
    private lateinit var checkState: TextView
    private lateinit var checkList: LinearLayout
    private lateinit var checkUse: TextView
    private lateinit var checkStart: TextView
    private lateinit var lines: LinearLayout
    private lateinit var linesTitle: TextView
    private lateinit var search: EditText
    private lateinit var peopleBox: LinearLayout
    private lateinit var modelsBox: LinearLayout
    private lateinit var answer: TextView
    private lateinit var modelLine: TextView
    private lateinit var modelChip: TextView
    private lateinit var recordLine: TextView
    private lateinit var saveVoice: TextView
    private var lastClips: List<FloatArray> = emptyList()
    private var recorder: VoiceRecorder? = null
    private var check: HearingCheck? = null
    private var far = false
    private var suggestion: HearingCheck.Suggestion? = null
    private var searching = ""
    private var lastLines = 0L

    private val tick = object : Runnable {
        override fun run() {
            showLevel()
            main.postDelayed(this, 150)
        }
    }
    private val slow = object : Runnable {
        override fun run() {
            showState()
            Listen.looking(30_000)
            main.postDelayed(this, 1000)
        }
    }
    private val watcher: () -> Unit = {
        main.post {
            val now = System.currentTimeMillis()
            if (searching.isEmpty() && now - lastLines > 1500) {
                lastLines = now
                showLines()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val page = page()
        page.addView(header("Listen", "Who said what, all day, on this phone", "Done") { finish() })

        // On / off and how it is doing.
        val now = card()
        state = text("", 18f, Ui.TEXT, Ui.BOLD)
        now.addView(state)
        stats = text("", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(8)) }
        now.addView(stats)
        onOff = button("", 0) { toggle() }
        now.addView(onOff, lp())
        pause = chip("Pause 15 min", false) {
            if (Listen.paused) Listen.resume() else Listen.pause(15 * 60_000L)
            showState()
        }
        now.addView(chipRow(pause))
        now.addView(text("Speed", 13f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        now.addView(segmented(listOf("auto" to "Auto", "live" to "Live", "eco" to "Eco"), settings.listenSpeed) {
            settings.listenSpeed = it
        })
        now.addView(text("Auto: at once while you look (the glasses' Listen panel, this page) or while charging; " +
            "otherwise in batches every 2 minutes so the phone rests (Eco). Nothing is lost either way.", 12.5f, Ui.FAINT))
        page.addView(now)

        // What it hears now.
        val lv = card("What Vox hears", "The bar is what the speech detector hears (with the boost); the mark is " +
            "the room's quiet level. Have the quiet voice talk: the bar should jump well past the mark.")
        meter = LevelMeter(this)
        lv.addView(meter, lp(h = dp(28)))
        meterText = text("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        lv.addView(meterText)
        page.addView(lv)

        // How it hears.
        val how = page.section("Hearing", "listen_hearing", "mic", open = true,
            hint = "For soft voices: raise the sensitivity or the boost, keep the second chance on, then run the check below.")
        how.addView(slider("Sensitivity", 1, 10, settings.listenSensitivity,
            { "$it · threshold ${"%.2f".format(Locale.US, Listen.threshold(it))}" }) { settings.listenSensitivity = it })
        how.addView(slider("Boost for the detector", 0, 24, settings.listenBoost, { "+$it dB" }) { settings.listenBoost = it })
        how.addToggle(toggle("Automatic level", "Lifts a quiet room so soft speech still counts (up to +18 dB more)",
            settings.listenAutoLevel) { settings.listenAutoLevel = it })
        how.addToggle(toggle("Second chance for quiet speech", "A keener detector keeps quiet phrases the first one " +
            "missed, when they hold real words (marked quiet)", settings.listenSecondChance) { settings.listenSecondChance = it })
        how.addView(slider("End of a phrase after", 300, 1500, settings.listenPauseMs, { "$it ms of quiet" }) { settings.listenPauseMs = it })
        how.addView(text("Microphone", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        val sources = mutableListOf("voice" to "Voice", "camcorder" to "Camcorder", "mic" to "Standard")
        if (MicHub.hasRaw(this)) sources.add(1, "raw" to "Raw")
        how.addView(segmented(sources, settings.listenSource) {
            settings.listenSource = it
            MicHub.reopen()
        })
        how.addView(text("Voice: tuned for speech, no automatic gain. Raw: nothing done to it. Camcorder: often " +
            "several microphones. Standard: the phone's own processing. The check tells which hears best.", 12.5f, Ui.FAINT))
        how.addToggle(toggle("Day files", "Each day's transcript as a file in Vox's docs folder (Alt+N on the glasses)",
            settings.listenFiles) { settings.listenFiles = it })

        // The quiet-voice check.
        val ck = card("Quiet-voice check", "The softest voice reads 10 short sentences at their usual volume. " +
            "Near: about 1 m from the phone. Far: across the room. It passes at 10/10 near, 9/10 far, 90% of " +
            "the words right; if not, it tries other settings on the same recording.")
        ck.addView(segmented(listOf("near" to "Near (1 m)", "far" to "Far (3 m)"), "near") { far = it == "far" })
        sentence = text("", 22f, Ui.TEXT, Ui.BOLD).apply { setPadding(0, dp(10), 0, dp(6)) }
        ck.addView(sentence)
        checkState = text("", 13f, Ui.MUTED)
        ck.addView(checkState)
        checkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ck.addView(checkList)
        checkStart = chip("Start the check", true) { startCheck() }
        val skip = chip("Next sentence", false) { check?.skip() }
        val stop = chip("Stop", false) {
            check?.cancel()
            check = null
        }
        ck.addView(chipRow(checkStart, skip, stop))
        checkUse = chip("Use these settings", true) {
            suggestion?.let {
                settings.listenSensitivity = it.sensitivity
                settings.listenBoost = it.boost
                checkState.text = "Set: sensitivity ${it.sensitivity}, boost +${it.boost} dB. Run the check again to confirm."
                recreate()
            }
        }.apply { visibility = View.GONE }
        ck.addView(checkUse)
        saveVoice = chip("Remember this voice…", false) { saveCheckedVoice() }.apply { visibility = View.GONE }
        ck.addView(saveVoice)
        page.addView(ck)

        // Who speaks.
        val who = page.section("Who speaks", "listen_people", "eye", open = true,
            hint = "Lines get names from voiceprints. Record your own voice once; name the others from their lines below.")
        who.addToggle(toggle("Know who speaks", "A voiceprint for each line, matched to the people below", settings.listenSpeakers) {
            settings.listenSpeakers = it
            Listen.peopleChanged()
        })
        who.addView(text("Voiceprint model", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        who.addView(segmented(VoiceprintModel.CHOICES.map { it.id to it.name }, settings.listenVoiceModel) {
            settings.listenVoiceModel = it
            showModel()
            Listen.peopleChanged()
        })
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        modelLine = text("", 12.5f, Ui.MUTED)
        mrow.addView(modelLine, lp(0, weight = 1f))
        modelChip = chip("Download", true) { modelAction() }
        mrow.addView(modelChip)
        who.addView(mrow)
        who.addView(text("Each model knows people only by its own voiceprints: after switching, record the voices again.",
            12f, Ui.FAINT).apply { setPadding(0, dp(4), 0, dp(6)) })
        recordLine = text("Your voice: read this aloud at your usual volume, about 30 s:\n\n“$PASSAGE”", 13f, Ui.MUTED)
        who.addView(recordLine)
        who.addView(chipRow(chip("Record my voice (30 s)", true) { recordMe() }))
        peopleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        who.addView(peopleBox)

        // Ask: an answer from the transcript, on the phone (Liquid's LFM2.5).
        val askCard = card("Ask", "A question about what was said: \"what did Maria say about the trip?\", \"what did " +
            "we decide yesterday?\". Quick: the 1.2B model, a few seconds. Deep: the biggest downloaded (8B-A1B thinks first).")
        val question = field("Question", "", "ask about any day")
        askCard.addView(question, lp().apply { topMargin = dp(4); bottomMargin = dp(6) })
        answer = text("", 14.5f, Ui.TEXT).apply { setPadding(0, dp(6), 0, dp(4)); setTextIsSelectable(true) }
        fun go(deep: Boolean) {
            val q = question.text.toString().trim()
            if (q.isEmpty()) return
            if (!LlmModels.usable()) return run { answer.text = "This phone or build cannot run the models (needs vox_llm and an ARMv8.2 CPU with int8 matrix instructions)." }
            if (LlmModels.ALL.none { it.role != "embed" && LlmModels.ready(this, it) }) return run { answer.text = "Download a model first (AI models, below)." }
            answer.text = "Thinking…"
            Thread {
                val got = runCatching { Search.ask(this, q, deep) { t -> main.post { answer.text = t.ifEmpty { "Thinking…" } } } }
                    .getOrElse { "It stopped: ${it.message}" }
                main.post { answer.text = got ?: "Download a model first (AI models, below)." }
            }.start()
        }
        askCard.addView(chipRow(chip("Ask", true) { go(false) }, chip("Ask deep", false) { go(true) }))
        askCard.addView(answer)
        page.addView(askCard)

        // The models.
        val ai = page.section("AI models", "listen_models", "spark", hint = "On this phone, offline: Liquid's LFM2.5 " +
            "answers and summarizes, EmbeddingGemma finds by meaning. Wi-Fi is best for the downloads.")
        modelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ai.addView(modelsBox)
        ai.addToggle(toggle("Search by meaning", "A meaning vector for every line of four words or more, made while the " +
            "phone is quiet (more often while charging)", settings.listenMeaning) { settings.listenMeaning = it })

        // What it heard.
        val heard = card()
        linesTitle = text("TODAY", 11.5f, Ui.MUTED, Ui.BOLD).apply { letterSpacing = 0.14f }
        heard.addView(linesTitle)
        search = field("Search", "", "words, from any day").apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> runSearch(); true }
        }
        heard.addView(search, lp().apply { topMargin = dp(8); bottomMargin = dp(8) })
        heard.addView(chipRow(chip("Search", true) { runSearch() }, chip("Today", false) {
            searching = ""
            search.setText("")
            showLines()
        }))
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heard.addView(lines)
        heard.addView(text("Every day is also a file, \"Transcript <date>.md\", in Vox's docs folder: Alt+N on the " +
            "glasses opens it.", 12.5f, Ui.FAINT).apply { setPadding(0, dp(10), 0, 0) })
        page.addView(heard)
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) settings.listenOn = true
    }

    override fun onResume() {
        super.onResume()
        if (settings.listenOn) {
            if (micAllowed()) ListenService.ensure(this) else askMic()
        }
        Listen.watch(watcher)
        Listen.looking(30_000)
        main.post(tick)
        main.post(slow)
        showLines()
        showModel()
        showPeople()
        showModels()
    }

    // Each model: what it is for, its size, downloaded or not (Download / Delete).
    private fun showModels() {
        modelsBox.removeAllViews()
        if (!LlmModels.usable()) modelsBox.addView(text("This build or phone cannot run them (vox_llm: ${LlmNative.available}, " +
            "CPU: ${LlmModels.cpuOk}).", 13f, Ui.WARN))
        fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(Locale.US, b / 1e9) else "${b / 1_000_000} MB"
        for (m in LlmModels.ALL) {
            val ready = LlmModels.ready(this, m)
            val busy = LlmModels.downloading == m.id
            modelsBox.addView(text(m.name, 15f, Ui.TEXT, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
            modelsBox.addView(text("${m.about} · ${gb(m.part.size)} · " + when {
                busy && LlmModels.progress >= 0 -> "downloading ${gb(LlmModels.progress)}"
                ready -> "ready"
                LlmModels.problem != null && busy -> "stopped: ${LlmModels.problem}"
                else -> "not downloaded"
            }, 12.5f, Ui.MUTED))
            if (!busy) modelsBox.addView(chipRow(chip(if (ready) "Delete" else "Download", !ready) {
                if (ready) dialog().setTitle("Delete ${m.name}?").setPositiveButton("Delete") { _, _ ->
                    LlmModels.delete(this, m)
                    showModels()
                }.setNegativeButton("Cancel", null).show()
                else {
                    if (LlmModels.downloading.isNotEmpty()) return@chip
                    Thread {
                        LlmModels.download(this, m)
                        main.post { showModels() }
                    }.start()
                    val ticker = object : Runnable {
                        override fun run() {
                            showModels()
                            if (LlmModels.downloading.isNotEmpty()) main.postDelayed(this, 1000)
                        }
                    }
                    main.postDelayed(ticker, 300)
                }
            }))
        }
    }

    override fun onPause() {
        super.onPause()
        Listen.unwatch(watcher)
        main.removeCallbacks(tick)
        main.removeCallbacks(slow)
    }

    override fun onDestroy() {
        check?.cancel()
        recorder?.stop()
        super.onDestroy()
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 41)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (micAllowed() && settings.listenOn) ListenService.start(this)
        showState()
    }

    private fun toggle() {
        if (Listen.on || ListenService.running) ListenService.stop(this)
        else {
            settings.listenOn = true
            if (!micAllowed()) askMic() else ListenService.start(this)
        }
        main.postDelayed({ showState() }, 300)
    }

    private fun showState() {
        val on = Listen.on
        state.text = when {
            !VoiceModel.ready(this) -> "Download the speech model first (Voice tab)"
            on && Listen.paused -> "Paused until " + SimpleDateFormat("h:mm a", Locale.US).format(Date(Listen.pausedUntil))
            on -> Listen.state.replaceFirstChar { it.uppercase() }
            settings.listenOn -> "Starting…"
            else -> "Off"
        }
        state.setTextColor(if (on && !Listen.paused) Ui.ACCENT else Ui.TEXT)
        val wait = Listen.waiting
        stats.text = "${Listen.today} lines today" +
            (if (wait >= 1) " · ${wait.toInt()} s of speech waiting" else "") +
            (if (MicHub.silenced) " · another app has the microphone" else "") +
            (MicHub.problem?.let { " · $it" } ?: "")
        onOff.text = if (on || settings.listenOn) "Stop listening" else "Start listening"
        pause.text = if (Listen.paused) "Listen again" else "Pause 15 min"
    }

    private fun showLevel() {
        val heardDb = Listen.level + Listen.gainDb
        meter.set(heardDb, Listen.floor + Listen.gainDb, Listen.speaking, Listen.on)
        meterText.text = if (!Listen.on) "Listen mode is off: the meter moves while it listens."
            else "Room ${Listen.floor.toInt()} dB · now ${Listen.level.toInt()} dB · boost +${Listen.gainDb.toInt()} dB" +
                if (Listen.speaking) " · speech" else ""
    }

    // Today's lines (newest at the bottom), or the search's.
    private fun showLines() {
        val q = searching
        Thread {
            val db = ListenDb.get(this)
            val names = db.names()
            val hits = if (q.isEmpty()) emptyList() else runCatching { Search.find(this, q, 80) }.getOrDefault(emptyList())
            val how = hits.associate { it.line.id to (if (it.meaning && !it.words) " · by meaning" else "") }
            val list = if (q.isEmpty()) db.between(ListenDb.dayStart(System.currentTimeMillis()), Long.MAX_VALUE).takeLast(60)
                else hits.map { it.line }
            main.post {
                linesTitle.text = if (q.isEmpty()) "TODAY · ${list.size} LATEST" else "\"$q\" · ${list.size} FOUND"
                lines.removeAllViews()
                if (list.isEmpty()) lines.addView(text(if (q.isEmpty()) "Nothing heard yet today." else "Nothing found.", 13f, Ui.FAINT))
                val fmt = SimpleDateFormat(if (q.isEmpty()) "h:mm a" else "MMM d, h:mm a", Locale.US)
                for (l in list) {
                    val who = names[l.person.ifEmpty { l.voice }]?.let { "$it: " } ?: ""
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                    row.addView(text(fmt.format(Date(l.start)) + (if (l.quiet) " · quiet" else "") + (how[l.id] ?: ""), 11.5f, Ui.FAINT))
                    row.addView(text(who + l.text, 14.5f, if (l.quiet) Ui.MUTED else Ui.TEXT))
                    lines.addView(row, lp())
                }
            }
        }.start()
    }

    private fun runSearch() {
        searching = search.text.toString().trim()
        showLines()
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

    // ------------------------------------------------ who speaks

    private fun showModel() {
        val ch = VoiceprintModel.chosen(this)
        val ready = VoiceprintModel.ready(this, ch)
        fun mb(b: Long) = "${b / 1_000_000} MB"
        modelLine.text = when {
            VoiceprintModel.progress >= 0 -> "Downloading… ${mb(VoiceprintModel.progress)} of ${mb(ch.part.size)}"
            ready -> "${ch.name}: ${ch.about} · ${mb(ch.part.size)}"
            VoiceprintModel.problem != null -> "Not downloaded: ${VoiceprintModel.problem}"
            else -> "${ch.name} (${ch.about}): not downloaded · ${mb(ch.part.size)}"
        }
        modelChip.visibility = if (VoiceprintModel.progress >= 0) View.GONE else View.VISIBLE
        modelChip.text = if (ready) "Delete" else "Download"
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
            main.post {
                showModel()
                Listen.peopleChanged()
            }
        }.start()
        val ticker = object : Runnable {
            override fun run() {
                showModel()
                if (VoiceprintModel.progress >= 0) main.postDelayed(this, 500)
            }
        }
        main.postDelayed(ticker, 300)
    }

    private fun recordMe() {
        if (recorder != null) return
        if (!micAllowed()) return askMic()
        if (!VoiceprintModel.ready(this)) {
            recordLine.text = "Download the voiceprint model first (above)."
            return
        }
        recorder = VoiceRecorder(this, 30, tick = { left, lv ->
            main.post { recordLine.text = "Recording… $left s left (${lv.toInt()} dB)\n\n“$PASSAGE”" }
        }, done = { clips, why ->
            main.post { recordLine.text = if (why != null) "Not recorded: $why" else "Remembering your voice…" }
            if (why == null) {
                val err = Speakers.enroll(this, "You", false, clips, Speakers.ME)
                main.post {
                    recordLine.text = err?.let { "Not saved: $it" } ?: "Saved: your lines will say \"You\"."
                    showPeople()
                }
            }
            recorder = null
        }).also { it.start() }
    }

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
                        checkState.text = err?.let { "Not saved: $it" } ?: "Saved $n (soft voice): their lines will carry the name."
                        saveVoice.visibility = View.GONE
                        showPeople()
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    // The people (soft voice, rename, forget) and the voices not named yet (name them, or say it was you).
    private fun showPeople() {
        Thread {
            val db = ListenDb.get(this)
            val people = db.personList().map { (id, name, soft) -> listOf(id, name, soft.toString(), db.sampleCount(id).toString()) }
            val voices = db.voiceList()
            main.post {
                peopleBox.removeAllViews()
                peopleBox.addView(subhead("People"))
                if (people.isEmpty()) peopleBox.addView(text("Nobody yet.", 13f, Ui.FAINT))
                for ((id, name, soft, n) in people) {
                    peopleBox.addView(text("$name · $n samples" + if (soft == "true") " · soft voice" else "", 14.5f, Ui.TEXT, Ui.BOLD)
                        .apply { setPadding(0, dp(8), 0, 0) })
                    peopleBox.addView(chipRow(
                        chip(if (soft == "true") "Soft voice: on" else "Soft voice: off", soft == "true") {
                            db.setSoft(id, soft != "true")
                            Listen.peopleChanged()
                            showPeople()
                        },
                        chip("Rename", false) { ask("Rename $name", name) { db.rename(id, it); Listen.peopleChanged(); showPeople() } },
                        chip("Forget", false) {
                            dialog().setTitle("Forget $name?").setMessage("Their voiceprints go; their lines keep the words.")
                                .setPositiveButton("Forget") { _, _ ->
                                    db.deletePerson(id)
                                    Listen.peopleChanged()
                                    showPeople()
                                }.setNegativeButton("Cancel", null).show()
                        },
                    ))
                }
                peopleBox.addView(subhead("Voices not named yet"))
                if (voices.isEmpty()) peopleBox.addView(text("None yet: they appear as Vox hears new voices.", 13f, Ui.FAINT))
                for ((v, n, lines) in voices) {
                    peopleBox.addView(text("${v.label} · $n lines", 14.5f, Ui.TEXT, Ui.BOLD).apply { setPadding(0, dp(8), 0, 0) })
                    for (l in lines) peopleBox.addView(text("“$l”", 12.5f, Ui.MUTED))
                    peopleBox.addView(chipRow(
                        chip("Name…", true) {
                            ask("Who is ${v.label}?", "") { name ->
                                val pid = db.personNamed(name) ?: db.addPerson(name, false)
                                db.nameVoice(v.id, pid)
                                Listen.peopleChanged()
                                showPeople()
                            }
                        },
                        chip("That's me", false) {
                            if (db.personList().none { it.first == Speakers.ME }) db.addPerson("You", false, Speakers.ME)
                            db.nameVoice(v.id, Speakers.ME)
                            Listen.peopleChanged()
                            showPeople()
                        },
                    ))
                }
            }
        }.start()
    }

    private fun ask(title: String, value: String, done: (String) -> Unit) {
        val f = field("Name", value, "a name")
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
}

// The level meter: what the detector hears (dB, -80 ... -10), the room's quiet level as a mark, lit while speech is heard.
class LevelMeter(context: Context) : View(context) {
    private var level = -100f
    private var floor = -100f
    private var speech = false
    private var on = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(level: Float, floor: Float, speech: Boolean, on: Boolean) {
        this.level = level
        this.floor = floor
        this.speech = speech
        this.on = on
        invalidate()
    }

    private fun x(db: Float) = ((db + 80f) / 70f).coerceIn(0f, 1f) * width

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        p.color = Ui.RAISED
        c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
        if (!on) return
        p.color = if (speech) Ui.ACCENT else Ui.ACCENT2
        c.drawRoundRect(0f, 0f, maxOf(h, x(level)), h, r, r, p)
        p.color = Ui.WARN
        val fx = x(floor)
        c.drawRect(fx - 2f, 0f, fx + 2f, h, p)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) setMeasuredDimension(measuredWidth, context.dp(28))
    }
}

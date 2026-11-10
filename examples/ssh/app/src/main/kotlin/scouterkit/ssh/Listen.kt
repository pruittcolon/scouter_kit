package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

// Listen mode: the phone's microphone all day (ListenService keeps it), every phrase written down in listen.db and
// the day's file. Two threads: hearing (the shared microphone -> the speech detector -> phrases waiting on disk)
// and working (Parakeet reads them: at once while someone looks - Live - else in batches - Eco - so the phone rests
// between). Quiet speech: the detector hears a boosted copy (the room's level found by itself, plus the chosen
// boost) while Parakeet gets the audio as recorded; a second, more sensitive detector keeps quiet phrases the
// first missed, when they hold real words.
object Listen {
    class Heard(val id: Long, val start: Long, val end: Long, val text: String, val quiet: Boolean, var who: String = "",
                var voice: String = "", var mood: String = "", var fired: String = "")

    @Volatile var on = false
        private set
    @Volatile var state = "off" // what it does now, for the phone and the glasses
        private set
    @Volatile var pausedUntil = 0L // wall clock
        private set
    @Volatile var checking = false // the hearing check has the phrases (they are not written down)
    @Volatile var level = -100f // the last 100 ms, dBFS
        private set
    @Volatile var floor = -70f // the room's quiet level, dBFS
        private set
    @Volatile var gainDb = 0f // the boost the detector hears now
        private set
    @Volatile var speaking = false
        private set
    @Volatile var partial = "" // the phrase being said, while someone looks
        private set
    @Volatile var today = 0 // lines today
        private set
    @Volatile var waiting = 0.0 // seconds of speech waiting to be read
        private set
    private val lookUntil = java.util.concurrent.atomic.AtomicLong(0) // elapsedRealtime: someone looks (Live)

    private val recentLines = ArrayDeque<Heard>()
    private val watchers = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Object()
    @Volatile private var gen = 0
    private var app: Context? = null

    // ------------------------------------------------ on, off, pause

    @Synchronized
    fun start(c: Context) {
        if (on) return
        val ctx = c.applicationContext
        app = ctx
        on = true
        val run = ++gen
        ListenQueue.recover(ctx)
        runCatching { loadRecent(ctx) }
        setState("starting")
        Thread({ hear(ctx, run) }, "listen-hear").start()
        Thread({ work(ctx, run) }, "listen-work").start()
        Log.i(ScouterService.TAG, "listen: on")
    }

    @Synchronized
    fun stop() {
        if (!on) return
        on = false
        gen++
        synchronized(lock) { lock.notifyAll() }
        setState("off")
        Log.i(ScouterService.TAG, "listen: off")
    }

    fun pause(ms: Long) {
        pausedUntil = System.currentTimeMillis() + ms
        Log.i(ScouterService.TAG, "listen: paused for ${ms / 60000} min")
        changed()
    }

    fun resume() {
        pausedUntil = 0L
        changed()
    }

    val paused get() = System.currentTimeMillis() < pausedUntil

    // Someone looks (the glasses' panel, the phone's page): phrases are read at once for a while.
    fun looking(ms: Long = 20_000) {
        lookUntil.set(SystemClock.elapsedRealtime() + ms)
        synchronized(lock) { lock.notifyAll() }
    }

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }
    private fun changed() { for (w in watchers) runCatching { w() } }

    private fun setState(s: String) {
        if (s == state) return
        state = s
        changed()
    }

    fun recent(n: Int): List<Heard> = synchronized(recentLines) { recentLines.toList().takeLast(n) }

    // People or voice groups changed (named, merged, enrolled): the matcher and the names shown are read again.
    @Volatile private var reloadPeople = true

    fun peopleChanged() {
        reloadPeople = true
        app?.let { c -> Thread { runCatching { loadRecent(c) }; changed() }.start() }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loadRecent(c: Context) {
        val db = ListenDb.get(c)
        val names = db.names()
        val lines = db.recent(40).map { Heard(it.id, it.start, it.end, it.text, it.quiet, names[it.person.ifEmpty { it.voice }] ?: "", it.voice,
            it.emotion.ifEmpty { it.sound }) }
        synchronized(recentLines) {
            recentLines.clear()
            recentLines.addAll(lines)
        }
        today = db.count(ListenDb.dayStart(System.currentTimeMillis()))
    }

    // ------------------------------------------------ hearing settings

    // The detector's threshold for sensitivity 1 ... 10 (0.70 ... 0.25).
    fun threshold(sensitivity: Int) = (0.75f - 0.05f * sensitivity.coerceIn(1, 10)).coerceIn(0.2f, 0.7f)

    fun vadConfig(c: Context, threshold: Float, pauseMs: Int, minSpeech: Float = 0.25f) = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = VoiceModel.file(c, "silero_vad.onnx").path, threshold = threshold,
            minSilenceDuration = pauseMs.coerceIn(200, 2000) / 1000f, minSpeechDuration = minSpeech, windowSize = 512,
            maxSpeechDuration = 20f),
        sampleRate = 16000)

    // The automatic part of the boost: lifts a quiet room (its floor) to about -58 dBFS, at most +18 dB.
    fun autoGain(floorDb: Float) = (-58f - floorDb).coerceIn(0f, 18f)

    fun db(rms: Double) = if (rms <= 1e-9) -100f else (20 * log10(rms)).toFloat().coerceAtLeast(-100f)

    fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return if (to > from) sqrt(s / (to - from)) else 0.0
    }

    // What the detector hears: louder by gain (linear), soft-limited near full scale so it never clips hard.
    fun boosted(v: Float, gain: Float): Float {
        val x = v * gain
        val a = abs(x)
        return if (a <= 0.8f) x else Math.copySign(0.8f + 0.2f * tanh((a - 0.8f) / 0.2f), x)
    }

    // The room's quiet level, following the quiet moments quickly and the loud ones slowly (about 1 dB in 5 s).
    fun follow(floorDb: Float, levelDb: Float) = if (levelDb < floorDb) floorDb + (levelDb - floorDb) * 0.3f
        else floorDb + min(0.02f, (levelDb - floorDb) * 0.01f)

    // ------------------------------------------------ hearing (the microphone to phrases on disk)

    // The last 40 s of audio as recorded, by its position since the start (for a phrase's own samples).
    private class Ring(val size: Int) {
        private val a = FloatArray(size)
        var total = 0L
            private set

        @Synchronized
        fun add(src: FloatArray, n: Int) {
            for (i in 0 until n) a[((total + i) % size).toInt()] = src[i]
            total += n
        }

        @Synchronized
        fun slice(from: Long, to: Long): FloatArray {
            val f = max(max(from, 0L), total - size + 1)
            val t = min(to, total)
            if (t <= f) return FloatArray(0)
            return FloatArray((t - f).toInt()) { a[((f + it) % size).toInt()] }
        }
    }

    @Volatile private var ring: Ring? = null
    @Volatile private var speechFrom = -1L

    private fun hear(c: Context, run: Int) {
        val settings = Settings(c)
        var failed = false
        var sub: MicHub.Sub? = null
        var main: Vad? = null
        var second: Vad? = null
        try {
            while (on && run == gen && !VoiceModel.ready(c)) {
                setState("download the speech model first (Voice tab)")
                Thread.sleep(5000)
            }
            val r = Ring(16000 * 40)
            ring = r
            val block = FloatArray(1600)
            val window = FloatArray(512)
            var fill = 0
            var cfg = ""
            var vadBase = 0L
            var anchorWall = System.currentTimeMillis()
            var anchorAt = 0L
            val mains = ArrayDeque<LongArray>() // the first detector's phrases (start, end), the last minute
            val quiets = ArrayList<LongArray>() // the second's, waiting to be compared
            var lastLevel = 0L
            fun wall(at: Long) = anchorWall + (at - anchorAt) * 1000 / 16000
            fun emit(a: Long, b: Long, quiet: Boolean) {
                if (checking) return
                val samples = r.slice(a - 4000, b + 1600) // a quarter second before, a tenth after
                if (samples.size < 4800) return
                ListenQueue.push(c, samples, wall(max(max(a - 4000, 0L), r.total - 16000L * 40 + 1)), quiet)
                waiting = ListenQueue.seconds(c)
                synchronized(lock) { lock.notifyAll() }
            }
            while (on && run == gen) {
                if (sub == null) {
                    val (s, why) = MicHub.open(c, "listen", strict = false)
                    if (s == null) {
                        setState("paused: $why")
                        Thread.sleep(10_000)
                        continue
                    }
                    sub = s
                    cfg = ""
                }
                val n = sub.read(block)
                if (n < 0) { // the recording ended: join again
                    sub = null
                    continue
                }
                if (n == 0) continue
                val now = System.currentTimeMillis()
                // The clock: samples counted against the wall clock; a gap (a lost microphone) starts it again.
                if (abs(now - (wall(r.total) + n * 1000L / 16000)) > 2000) {
                    anchorWall = now - n * 1000L / 16000
                    anchorAt = r.total
                }
                r.add(block, n)
                val lv = db(rms(block, 0, n))
                level = lv
                floor = follow(floor, lv).coerceIn(-100f, -20f)
                val stop = paused || checking || MicHub.silenced
                if (stop) {
                    setState(when {
                        MicHub.silenced -> "paused: another app has the microphone"
                        checking -> "paused for the hearing check"
                        else -> "paused"
                    })
                    if (speaking) { speaking = false; speechFrom = -1 }
                    cfg = "" // the detectors start fresh after
                    continue
                }
                // The detectors, made again when the hearing settings change.
                val key = "${settings.listenSensitivity}:${settings.listenPauseMs}:${settings.listenSecondChance}"
                if (key != cfg) {
                    main?.release()
                    second?.release()
                    val t = threshold(settings.listenSensitivity)
                    main = Vad(null, vadConfig(c, t, settings.listenPauseMs))
                    second = if (settings.listenSecondChance) Vad(null, vadConfig(c, max(0.15f, t - 0.2f), settings.listenPauseMs, 0.3f)) else null
                    vadBase = r.total - n
                    fill = 0
                    cfg = key
                    mains.clear()
                    quiets.clear()
                }
                val m = main ?: continue
                val auto = if (settings.listenAutoLevel) autoGain(floor) else 0f
                gainDb = settings.listenBoost + auto
                val g = 10f.pow(gainDb / 20f)
                for (i in 0 until n) {
                    window[fill++] = boosted(block[i], g)
                    if (fill == window.size) {
                        val w = window.copyOf()
                        m.acceptWaveform(w)
                        second?.acceptWaveform(w)
                        fill = 0
                    }
                }
                val sp = m.isSpeechDetected()
                if (sp != speaking) {
                    speaking = sp
                    speechFrom = if (sp) r.total - 8000 else -1
                    if (!sp) partial = ""
                    changed()
                }
                while (!m.empty()) {
                    val seg = m.front()
                    m.pop()
                    val a = vadBase + seg.start
                    val b = a + seg.samples.size
                    mains.addLast(longArrayOf(a, b))
                    emit(a, b, false)
                }
                second?.let { q ->
                    while (!q.empty()) {
                        val seg = q.front()
                        q.pop()
                        val a = vadBase + seg.start
                        quiets += longArrayOf(a, a + seg.samples.size)
                    }
                }
                // A quiet phrase 1.5 s after it ended: kept when the first detector did not hear most of it.
                val it = quiets.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    if (r.total - p[1] < 24000) continue
                    it.remove()
                    val covered = mains.sumOf { q -> max(0L, min(q[1], p[1]) - max(q[0], p[0])) }
                    if (covered < (p[1] - p[0]) * 0.3) emit(p[0], p[1], true)
                }
                while (mains.isNotEmpty() && r.total - mains.first()[1] > 16000L * 60) mains.removeFirst()
                if (now - lastLevel > 1000) {
                    lastLevel = now
                    setState(if (speaking) "hearing speech" else "listening")
                    changed()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(ScouterService.TAG, "listen: hearing stopped: $e")
            setState("hearing again after a fault")
            failed = true
        } finally {
            sub?.let { MicHub.close(it) }
            main?.release()
            second?.release()
            ring = null
            speaking = false
        }
        // A fault never leaves Listen deaf: the hearing starts again (and again) while it is on.
        if (failed && on && run == gen) Thread({
            Thread.sleep(2000)
            if (on && run == gen) hear(c, run)
        }, "listen-hear").start()
    }

    // ------------------------------------------------ working (phrases to lines)

    private var charging = false
    private var chargingAt = 0L

    private fun charging(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - chargingAt > 30_000) {
            chargingAt = now
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
        return charging
    }

    // Live: someone looks, the phone charges, or Live was chosen; Eco: a batch every 2 min or 60 s of speech.
    private fun live(c: Context, settings: Settings) = when (settings.listenSpeed) {
        "live" -> true
        "eco" -> false
        else -> SystemClock.elapsedRealtime() < lookUntil.get() || charging(c) || Automations.anyOn(c)
    }

    private fun due(c: Context, settings: Settings): Boolean {
        val n = ListenQueue.count(c)
        if (n == 0) return false
        if (live(c, settings)) return true
        return ListenQueue.seconds(c) >= 60 || ListenQueue.oldestAge(c) >= 120_000
    }

    private fun hot(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    // A conversation's voice groups whose lines sound alike (their averages at the group bar or closer) become the
    // one heard first. True when any did. Voiceprints only: no audio is read again.
    private fun regroup(db: ListenDb, conv: Long, ident: Speakers.Identifier, model: String): Boolean {
        val groups = db.conversationVoices(conv).mapNotNull { (id, list) -> Speakers.mean(list)?.let { id to it } }.toMutableList()
        var merged = false
        var i = 0
        while (i < groups.size) {
            var j = i + 1
            while (j < groups.size) {
                if (Speakers.cos(groups[i].second, groups[j].second) >= ident.bars.group) {
                    val (keep, drop) = groups[i].first to groups[j].first
                    db.mergeVoice(drop, keep)
                    ident.voices.removeAll { it.id == drop }
                    ident.voices.firstOrNull { it.id == keep }?.let { db.saveVoice(it, model) }
                    groups.removeAt(j)
                    merged = true
                    Log.i(ScouterService.TAG, "listen: two voices of a conversation were one")
                } else j++
            }
            i++
        }
        return merged
    }

    private val FILLER = setOf("um", "uh", "hmm", "mm", "mhm", "ah", "oh", "er", "erm", "huh")

    // Text worth a line: two characters at least; a quiet phrase needs two real words.
    fun usable(text: String, quiet: Boolean): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        val words = t.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (quiet) return words.size >= 2 && words.any { it !in FILLER }
        return true
    }

    private fun work(c: Context, run: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var failed = false
        val settings = Settings(c)
        val db = ListenDb.get(c)
        var engine: VoiceEngine? = null
        var prints: Voiceprints? = null
        var tone: Tone? = null
        var turns: Turns? = null
        val group = ArrayList<Turns.Piece>() // the recent lines with their audio, for speaker changes
        var chunkEnd = 0L
        var ident: Speakers.Identifier? = null
        var names = emptyMap<String, String>()
        var regrouped = c.getSharedPreferences("listen", Context.MODE_PRIVATE).getLong("regrouped", 0L)
        val bars = Speakers.Bars()
        var lastWork = SystemClock.elapsedRealtime()
        var lastPartial = 0L
        var lastFiles = 0L
        var lastFill = 0L
        val days = HashSet<Long>()
        try {
            while (on && run == gen) {
                synchronized(lock) { lock.wait(1000) }
                if (!(on && run == gen)) break
                val nowE = SystemClock.elapsedRealtime()
                waiting = ListenQueue.seconds(c)
                runCatching { Meetings.tick(c) }
                // Someone looks: the model ready for the words as they are said.
                if (engine == null && nowE < lookUntil.get() && !hot(c)) {
                    engine = runCatching { Parakeet.use(c, "listen") }.getOrNull()
                    lastWork = nowE
                }
                // The phrase being said, read every second while someone looks and nothing waits.
                val r = ring
                val from = speechFrom
                if (engine != null && r != null && from >= 0 && speaking && nowE < lookUntil.get() && nowE - lastPartial > 1000 &&
                    ListenQueue.count(c) == 0) {
                    lastPartial = nowE
                    val samples = r.slice(from, r.total)
                    if (samples.size > 8000) {
                        val t = runCatching { engine.timed(samples).first }.getOrDefault("")
                        if (t.isNotEmpty() && speaking) {
                            partial = t
                            changed()
                        }
                    }
                }
                if (days.isNotEmpty() && (nowE - lastFiles > if (live(c, settings)) 15_000 else 120_000)) {
                    lastFiles = nowE
                    if (settings.listenFiles) for (d in days) runCatching { Transcripts.write(c, d) }
                    days.clear()
                }
                if (!due(c, settings)) {
                    // Quiet moments: meaning vectors for lines without one (every 10 s while charging, else every minute).
                    if (nowE - lastFill > (if (charging(c)) 10_000L else 60_000L) && !hot(c) && !speaking) {
                        lastFill = nowE
                        runCatching { Search.fill(c, if (charging(c)) 60 else 20) }
                            .onFailure { Log.w(ScouterService.TAG, "listen: vectors: ${it.message}") }
                    }
                    // Nothing for 10 min: the model leaves memory (it loads again in a few seconds).
                    if (engine != null && nowE - lastWork > 10 * 60_000L && nowE >= lookUntil.get() && ListenQueue.count(c) == 0) {
                        Parakeet.done("listen")
                        engine = null
                        prints?.release()
                        prints = null
                        tone?.release()
                        tone = null
                        turns?.release()
                        turns = null
                        Log.i(ScouterService.TAG, "listen: the speech model unloaded (quiet for 10 min)")
                    }
                    continue
                }
                if (hot(c)) {
                    setState("cooling down: phrases wait")
                    continue
                }
                if (engine == null) {
                    engine = runCatching { Parakeet.use(c, "listen") }.getOrElse { e ->
                        setState("the speech model did not load: ${e.message}")
                        Thread.sleep(30_000)
                        null
                    } ?: continue
                }
                // Who speaks: the voiceprint model (when on and downloaded) and the people, read again after changes.
                val speakers = settings.listenSpeakers && VoiceprintModel.ready(c)
                if (speakers && (prints == null || prints.model != VoiceprintModel.chosen(c).id)) {
                    prints?.release()
                    prints = runCatching { Voiceprints(c) }.getOrElse {
                        Log.w(ScouterService.TAG, "listen: the voiceprint model did not load: ${it.message}")
                        null
                    }
                    reloadPeople = true
                }
                if (speakers && prints != null && (reloadPeople || ident == null)) {
                    reloadPeople = false
                    val model = prints.model
                    val people = db.people(model)
                    bars.person = 0.50f
                    bars.group = 0.55f
                    Speakers.calibrate(people.map { it.id to db.samples(it.id, model) }, bars)
                    val meta = c.getSharedPreferences("listen", Context.MODE_PRIVATE)
                    ident = Speakers.Identifier(people, db.voices(model), bars) {
                        val n = meta.getInt("voice_n", 0) + 1
                        meta.edit().putInt("voice_n", n).apply()
                        "Voice $n"
                    }
                    names = db.names()
                }
                // A chunk of lines ends (30 s of speech, or 3 s of quiet): where the speaker changed inside a line, it
                // is cut into one line each.
                fun closeChunk() {
                    if (group.isEmpty()) return
                    val pieces = ArrayList(group)
                    group.clear()
                    val p = prints
                    val id = ident
                    if (!speakers || !settings.listenSplit || p == null || id == null) return
                    if (!SegmentationModel.ready(c)) {
                        SegmentationModel.download(c)
                        if (!SegmentationModel.ready(c)) return
                    }
                    runCatching {
                        val t = turns?.takeIf { it.model == p.model } ?: run {
                            turns?.release()
                            Turns(c).also { turns = it }
                        }
                        val cuts = t.split(pieces) { s -> s.size >= 16000 && p.print(s) != null }
                        for ((lineId, parts) in cuts) {
                            val news = parts.map { part ->
                                val v = p.print(part.samples)
                                val m = v?.let { id.identify(it, part.samples.size / 16000f) }
                                m?.voice?.let { g -> db.saveVoice(g, p.model) }
                                ListenDb.NewPart(part.start, part.end, part.text, Turns.json(part.words), m?.person ?: "",
                                    m?.voice?.id ?: "", v, p.model)
                            }
                            val ids = db.replaceLine(lineId, news)
                            if (ids.size != news.size) continue
                            names = db.names()
                            val heard = news.mapIndexed { k, n ->
                                Heard(ids[k], n.start, n.end, n.text, false, names[n.person.ifEmpty { n.voice }] ?: "", n.voice)
                            }
                            synchronized(recentLines) {
                                val at = recentLines.indexOfFirst { it.id == lineId }
                                if (at >= 0) {
                                    recentLines.removeAt(at)
                                    recentLines.addAll(at, heard)
                                }
                            }
                            today += ids.size - 1
                            days += ListenDb.dayStart(news.first().start)
                            Turns.log("a line cut in ${news.size} where the speaker changed")
                        }
                        if (cuts.isNotEmpty()) changed()
                    }.onFailure { Log.w(ScouterService.TAG, "turns: ${it.message}") }
                }
                var done = 0
                val t0 = SystemClock.elapsedRealtime()
                while (on && run == gen) {
                    val chunk = ListenQueue.take(c) ?: break
                    try {
                        val (text, words) = engine.timed(chunk.samples)
                        if (usable(text, chunk.quiet)) {
                            val end = chunk.start + chunk.samples.size * 1000L / 16000
                            val w = JSONArray()
                            for ((word, at) in words) w.put(JSONArray().put(word).put((at * 1000).toInt()))
                            if (chunkEnd > 0 && (chunk.start - chunkEnd >= 3000 || chunk.start < chunkEnd - 60_000)) closeChunk()
                            val line = db.add(chunk.start, end, text, w.toString(), chunk.quiet)
                            val h = Heard(line.id, line.start, line.end, line.text, line.quiet)
                            val p = prints
                            val id = ident
                            var personId = ""
                            var emotion = ""
                            if (speakers && p != null && id != null) runCatching {
                                // The first 10 s are plenty for a voiceprint.
                                val v = p.print(if (chunk.samples.size > 160_000) chunk.samples.copyOf(160_000) else chunk.samples)
                                if (v != null) {
                                    val m = id.identify(v, chunk.samples.size / 16000f)
                                    db.setSpeaker(line.id, m.person ?: "", m.voice?.id ?: "", v, p.model)
                                    m.voice?.let { g ->
                                        db.saveVoice(g, p.model)
                                        if (m.created) names = names + (g.id to g.label)
                                    }
                                    h.who = names[m.person ?: m.voice?.id ?: ""] ?: ""
                                    h.voice = m.voice?.id ?: ""
                                    personId = m.person ?: ""
                                }
                            }.onFailure { Log.w(ScouterService.TAG, "listen: voiceprint: ${it.message}") }
                            // How it was said: loudness, speed, and (SenseVoice) emotion and sounds.
                            runCatching {
                                val secs = chunk.samples.size / 16000f
                                val loud = db(rms(chunk.samples))
                                val rate = if (secs > 0.5f) words.size / secs else 0f
                                var emo = ""
                                var snd = ""
                                if (settings.listenTone && ToneModel.ready(c)) {
                                    val t = tone ?: Tone(c).also { tone = it }
                                    val (e, s) = t.hear(chunk.samples)
                                    emo = e
                                    snd = s
                                }
                                db.setTone(line.id, emo, snd, loud, rate)
                                emotion = emo
                                h.mood = emo.ifEmpty { snd }
                            }.onFailure { Log.w(ScouterService.TAG, "listen: tone: ${it.message}") }
                            // The automations, on the line as it was just written.
                            if (!chunk.quiet || text.length > 6) runCatching {
                                h.fired = Automations.run(c, text, h.who, personId, emotion, line.start).joinToString(", ")
                            }
                                .onFailure { Log.w(ScouterService.TAG, "listen: automations: ${it.message}") }
                            synchronized(recentLines) {
                                recentLines.addLast(h)
                                while (recentLines.size > 60) recentLines.removeFirst()
                            }
                            group += Turns.Piece(line.id, line.start, chunk.samples, text,
                                words.map { (word, at) -> word to (at * 1000).toInt() }, chunk.quiet)
                            chunkEnd = end
                            if (group.sumOf { it.samples.size } >= 16000 * 30) closeChunk()
                            if (ListenDb.dayStart(line.start) == ListenDb.dayStart(System.currentTimeMillis())) today++
                            else today = db.count(ListenDb.dayStart(System.currentTimeMillis()))
                            days += ListenDb.dayStart(line.start)
                            partial = ""
                            done++
                            changed()
                        }
                        ListenQueue.done(chunk)
                    } catch (e: Exception) {
                        Log.w(ScouterService.TAG, "listen: a phrase failed: ${e.message}")
                        ListenQueue.aside(chunk)
                    }
                    if (hot(c)) break
                }
                waiting = ListenQueue.seconds(c)
                // Nothing more waiting and 3 s quiet since: the chunk is complete.
                if (ListenQueue.count(c) == 0 && System.currentTimeMillis() - chunkEnd >= 3000) closeChunk()
                // Conversations that ended (5 min of quiet): their voice groups that sound alike become one.
                if (speakers && ident != null) runCatching {
                    val ended = db.conversations(0, System.currentTimeMillis() - ListenDb.GAP_MS)
                        .filter { it.id > regrouped && it.ended < System.currentTimeMillis() - ListenDb.GAP_MS }
                    for (conv in ended) {
                        if (regroup(db, conv.id, ident, prints?.model ?: "")) reloadPeople = true
                        regrouped = conv.id
                    }
                    if (ended.isNotEmpty()) c.getSharedPreferences("listen", Context.MODE_PRIVATE).edit().putLong("regrouped", regrouped).apply()
                }
                lastWork = SystemClock.elapsedRealtime()
                if (done > 0) Log.i(ScouterService.TAG, "listen: $done lines in ${lastWork - t0} ms" +
                    if (live(c, settings)) " (live)" else " (eco)")
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(ScouterService.TAG, "listen: work stopped: $e")
            failed = true
        } finally {
            if (engine != null) Parakeet.done("listen")
            prints?.release()
            tone?.release()
            turns?.release()
            if (settings.listenFiles) for (d in days) runCatching { Transcripts.write(c, d) }
        }
        if (failed && on && run == gen) Thread({
            Thread.sleep(2000)
            if (on && run == gen) work(c, run)
        }, "listen-work").start()
    }
}

// Phrases waiting to be read, on disk (16-bit, 16 kHz), so nothing is lost while the phone rests, the model loads or
// the app restarts: "<start ms>_<n|q>.pcm" (q: kept by the quiet-voice second chance). One being read is ".working";
// one the app died on is set aside (".failed") at the next start instead of being tried again and again. At most
// 300 MB (the oldest go).
object ListenQueue {
    class Chunk(val file: File, val start: Long, val quiet: Boolean, val samples: FloatArray)

    private const val CAP = 300L * 1024 * 1024

    fun dir(c: Context) = File(c.filesDir, "listen/queue").apply { mkdirs() }

    private fun pending(c: Context) = (dir(c).listFiles { f -> f.name.endsWith(".pcm") } ?: emptyArray()).sortedBy { it.name }

    @Synchronized
    fun push(c: Context, samples: FloatArray, start: Long, quiet: Boolean) {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val name = String.format(Locale.US, "%013d_%s", start, if (quiet) "q" else "n")
        val part = File(dir(c), "$name.part")
        part.writeBytes(bb.array())
        part.renameTo(File(dir(c), "$name.pcm"))
        var total = pending(c).sumOf { it.length() }
        for (f in pending(c)) {
            if (total <= CAP) break
            total -= f.length()
            f.delete()
        }
    }

    @Synchronized
    fun take(c: Context): Chunk? {
        val f = pending(c).firstOrNull() ?: return null
        val w = File(f.parentFile, f.name.removeSuffix(".pcm") + ".working")
        if (!f.renameTo(w)) return null
        val bytes = runCatching { RandomAccessFile(w, "r").use { r -> ByteArray(r.length().toInt()).also { r.readFully(it) } } }
            .getOrElse { w.delete(); return null }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(bytes.size / 2) { bb.short / 32768f }
        val parts = w.name.removeSuffix(".working").split("_")
        return Chunk(w, parts[0].toLongOrNull() ?: w.lastModified(), parts.getOrNull(1) == "q", samples)
    }

    fun done(chunk: Chunk) {
        chunk.file.delete()
    }

    fun aside(chunk: Chunk) {
        chunk.file.renameTo(File(chunk.file.parentFile, chunk.file.name.removeSuffix(".working") + ".failed"))
    }

    // At the start: one being read when the app died is set aside; set-aside ones older than 3 days go.
    fun recover(c: Context) {
        for (f in dir(c).listFiles() ?: emptyArray()) {
            when {
                f.name.endsWith(".working") -> f.renameTo(File(f.parentFile, f.name.removeSuffix(".working") + ".failed"))
                f.name.endsWith(".part") -> f.delete()
                f.name.endsWith(".failed") && System.currentTimeMillis() - f.lastModified() > 3 * 86_400_000L -> f.delete()
            }
        }
    }

    fun count(c: Context) = pending(c).size

    fun seconds(c: Context) = pending(c).sumOf { it.length() } / 32000.0

    fun oldestAge(c: Context): Long {
        val f = pending(c).firstOrNull() ?: return 0
        val start = f.name.substringBefore('_').toLongOrNull() ?: f.lastModified()
        return System.currentTimeMillis() - start
    }
}

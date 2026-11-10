package scouterkit.ssh

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.Vad
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The quiet-voice check: someone (a soft-spoken voice) reads ten short sentences from where they usually are; Vox
// says how many it heard, how many words it got right, and, from the same recording played again through the
// detector at other settings, the gentlest settings that hear them all. Listen mode writes nothing down meanwhile.
// The bar: near (about 1 m) 10/10 heard, far (about 3 m) 9/10, 90% of the words right.
class HearingCheck(private val c: Context, private val ui: Ui) {
    interface Ui {
        fun prompt(i: Int, total: Int, sentence: String)
        fun heard(i: Int, ok: Boolean, got: String, accuracy: Int, quiet: Boolean)
        fun status(s: String)
        fun done(r: Result)
    }

    class Suggestion(val sensitivity: Int, val boost: Int, val heard: Int)
    class Result(val far: Boolean, val heard: Int, val total: Int, val accuracy: Int, val passed: Boolean,
                 val suggestion: Suggestion?, val note: String)

    @Volatile private var stopped = false
    @Volatile private var skip = false

    fun start(far: Boolean) = Thread({ run(far) }, "hearing-check").start()
    fun skip() { skip = true }
    fun cancel() { stopped = true }

    private class Window(val from: Long, var to: Long)

    private fun run(far: Boolean) {
        val settings = Settings(c)
        val sentences = if (far) SENTENCES.drop(10) else SENTENCES.take(10)
        var sub: MicHub.Sub? = null
        var main: Vad? = null
        var second: Vad? = null
        val audio = FloatList()
        val windows = ArrayList<Window>()
        var heard = 0
        var accSum = 0
        try {
            Listen.checking = true
            ui.status("Loading the speech model…")
            val engine = runCatching { Parakeet.use(c, "check") }.getOrElse { e ->
                ui.status("The speech model did not load: ${e.message}")
                return
            }
            val (s, why) = MicHub.open(c, "check")
            if (s == null) {
                ui.status("No microphone: $why")
                return
            }
            sub = s
            val t = Listen.threshold(settings.listenSensitivity)
            main = Vad(null, Listen.vadConfig(c, t, settings.listenPauseMs))
            second = if (settings.listenSecondChance) Vad(null, Listen.vadConfig(c, max(0.15f, t - 0.2f), settings.listenPauseMs, 0.3f)) else null
            // The room's level first: a moment of quiet.
            ui.status("Quiet for 2 seconds…")
            var floor = Listen.floor.takeIf { Listen.on } ?: -70f
            val block = FloatArray(1600)
            val window = FloatArray(512)
            var fill = 0
            val t0 = SystemClock.elapsedRealtime()
            while (!stopped && SystemClock.elapsedRealtime() - t0 < 2000) {
                val n = s.read(block)
                if (n <= 0) continue
                audio.add(block, n)
                floor = Listen.follow(floor, Listen.db(Listen.rms(block, 0, n)))
            }
            val g = 10f.pow((settings.listenBoost + if (settings.listenAutoLevel) Listen.autoGain(floor) else 0f) / 20f)
            val fed = audio.size.toLong() // the detectors start here
            fun feed(n: Int) {
                for (i in 0 until n) {
                    window[fill++] = Listen.boosted(block[i], g)
                    if (fill == window.size) {
                        val w = window.copyOf()
                        main.acceptWaveform(w)
                        second?.acceptWaveform(w)
                        fill = 0
                    }
                }
            }
            for ((i, sentence) in sentences.withIndex()) {
                if (stopped) break
                skip = false
                ui.prompt(i, sentences.size, sentence)
                ui.status("Read it at your usual voice")
                val w = Window(audio.size.toLong(), audio.size.toLong())
                windows += w
                val began = SystemClock.elapsedRealtime()
                val mains = ArrayList<LongArray>()
                val quiets = ArrayList<LongArray>()
                var lastEnd = 0L
                while (!stopped && !skip) {
                    val n = s.read(block)
                    if (n < 0) break
                    if (n > 0) {
                        audio.add(block, n)
                        feed(n)
                        while (!main.empty()) {
                            val seg = main.front()
                            main.pop()
                            val a = fed + seg.start
                            mains += longArrayOf(a, a + seg.samples.size)
                            lastEnd = SystemClock.elapsedRealtime()
                        }
                        second?.let { q ->
                            while (!q.empty()) {
                                val seg = q.front()
                                q.pop()
                                val a = fed + seg.start
                                quiets += longArrayOf(a, a + seg.samples.size)
                                lastEnd = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    // Done: 1.2 s after the last phrase ended (more may follow), or 15 s without any.
                    if (lastEnd > 0 && now - lastEnd > 1200 && !main.isSpeechDetected()) break
                    if (now - began > 15_000 && lastEnd == 0L) break
                    if (now - began > 25_000) break
                }
                w.to = audio.size.toLong()
                // The words: the first detector's phrases, else the second's.
                val parts = mains.filter { it[0] >= w.from - 4000 }.ifEmpty { quiets.filter { it[0] >= w.from - 4000 } }
                val quiet = mains.none { it[0] >= w.from - 4000 } && parts.isNotEmpty()
                var text = ""
                for (p in parts) {
                    val pcm = audio.slice(max(0, p[0] - 4000), min(audio.size.toLong(), p[1] + 1600))
                    if (pcm.size < 4800) continue
                    val got = runCatching { engine.timed(pcm).first }.getOrDefault("")
                    if (got.isNotEmpty()) text = if (text.isEmpty()) got else "$text $got"
                }
                val ok = text.isNotEmpty() && (!quiet || Listen.usable(text, true))
                val acc = if (ok) accuracy(sentence, text) else 0
                if (ok) heard++
                accSum += acc
                ui.heard(i, ok, text, acc, quiet)
                SystemClock.sleep(600)
            }
            if (stopped) return ui.status("Stopped")
            val total = sentences.size
            val accuracy = if (total > 0) accSum / total else 0
            val need = if (far) total - 1 else total
            val passed = heard >= need && accuracy >= 90
            ui.status("Trying other settings on the same recording…")
            val sugg = if (heard < total) suggest(audio, windows, settings) else null
            val note = when {
                passed -> "Passed: Vox hears this voice from here."
                sugg != null && sugg.heard > heard -> "With sensitivity ${sugg.sensitivity} and boost +${sugg.boost} dB Vox heard " +
                    "${sugg.heard}/$total of the same recording."
                heard < need -> "Missed ${total - heard}. Move the phone closer (on the table, face up), or raise the sensitivity."
                else -> "Heard, but the words were ${100 - accuracy}% wrong: the phone closer, or the Raw / Camcorder microphone."
            }
            ui.done(Result(far, heard, total, accuracy, passed, sugg, note))
        } catch (e: Exception) {
            ui.status("The check stopped: ${e.message}")
        } finally {
            sub?.let { MicHub.close(it) }
            main?.release()
            second?.release()
            Parakeet.done("check")
            Listen.checking = false
        }
    }

    // The same recording through the detector at gentler-to-keener settings: the first that hears a phrase in
    // every sentence's time (or the one hearing the most).
    private fun suggest(audio: FloatList, windows: List<Window>, settings: Settings): Suggestion? {
        val samples = audio.toArray()
        var best: Suggestion? = null
        val floor = run {
            var f = -70f
            var i = 0
            while (i + 1600 <= min(samples.size, 32000)) { f = Listen.follow(f, Listen.db(Listen.rms(samples, i, i + 1600))); i += 1600 }
            f
        }
        for (sens in settings.listenSensitivity..10) for (boost in listOf(settings.listenBoost, 12, 18, 24).filter { it >= settings.listenBoost }.distinct()) {
            if (stopped) return best
            val g = 10f.pow((boost + Listen.autoGain(floor)) / 20f)
            val vad = Vad(null, Listen.vadConfig(c, Listen.threshold(sens), settings.listenPauseMs))
            val found = ArrayList<Long>()
            try {
                var i = 0
                val w = FloatArray(512)
                while (i + 512 <= samples.size) {
                    for (k in 0 until 512) w[k] = Listen.boosted(samples[i + k], g)
                    vad.acceptWaveform(w.copyOf())
                    while (!vad.empty()) { found += vad.front().start.toLong(); vad.pop() }
                    i += 512
                }
                vad.flush()
                while (!vad.empty()) { found += vad.front().start.toLong(); vad.pop() }
            } finally {
                vad.release()
            }
            val n = windows.count { w -> found.any { it >= w.from - 4000 && it < w.to } }
            if (best == null || n > best.heard) best = Suggestion(sens, boost, n)
            if (n == windows.size) return best
        }
        return best
    }

    // Growing float audio.
    private class FloatList {
        private var a = FloatArray(16000 * 60)
        var size = 0
            private set
        fun add(src: FloatArray, n: Int) {
            if (size + n > a.size) a = a.copyOf(max(a.size * 2, size + n))
            System.arraycopy(src, 0, a, size, n)
            size += n
        }
        fun slice(from: Long, to: Long) = a.copyOfRange(from.toInt(), to.toInt())
        fun toArray() = a.copyOf(size)
    }

    companion object {
        // Words right, 0 ... 100: one minus the word error rate against what was read.
        fun accuracy(expected: String, got: String): Int {
            fun words(s: String) = s.lowercase(Locale.US).replace("'", "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val a = words(expected)
            val b = words(got)
            if (a.isEmpty()) return 0
            val d = IntArray(b.size + 1) { it }
            for (i in 1..a.size) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.size) {
                    val cur = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = cur
                }
            }
            return max(0, 100 - d[b.size] * 100 / a.size)
        }

        val SENTENCES = listOf(
            "The kitchen light is still on downstairs.",
            "Can you pick up some milk on the way home?",
            "I think the meeting moved to Thursday afternoon.",
            "Please put the blue folder back on my desk.",
            "We should call your mother this weekend.",
            "The dog needs a walk before it gets dark.",
            "Remind me to pay the water bill tomorrow.",
            "That movie was much better than I expected.",
            "Let's leave a little earlier on Saturday morning.",
            "Did anyone see where I left my glasses?",
            "The package should arrive sometime next week.",
            "I would like pasta for dinner tonight.",
            "Turn the music down a little, please.",
            "We are out of coffee and paper towels.",
            "My phone battery is almost empty again.",
            "Ask him if he wants to come over later.",
            "The weather looks nice for a walk tomorrow.",
            "I already sent the photos to your sister.",
            "Let's watch something funny before bed.",
            "Could you close the window in the bedroom?",
        )
    }
}

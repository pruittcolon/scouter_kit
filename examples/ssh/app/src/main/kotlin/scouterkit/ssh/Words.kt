package scouterkit.ssh

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

// How lines were said and what the words show. Tone: SenseVoice Small (FunAudioLLM; int8, as converted for
// sherpa-onnx, the same as Vox Amelior's) hears each line's emotion (happy, sad, angry, fearful, surprised,
// disgusted; neutral is left blank) and sounds in it (laughter, music, applause, crying, a cough, a sneeze); the audio
// itself gives the loudness and the speaking speed. Words: per person talk time and share, words per minute,
// questions, filler words, talking over someone, the longest stretch, and the words that stood out against the
// last 30 days.
object ToneModel {
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/" +
        "2365baeacb507f821a0c8120fcee3d484dba7a07"
    val parts = listOf(
        VoiceModel.Part("model.int8.onnx", "$HF/model.int8.onnx", 239233841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
        VoiceModel.Part("tokens.txt", "$HF/tokens.txt", 315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
    )
    val bytes = parts.sumOf { it.size }

    @Volatile var progress = -1L
    @Volatile var problem: String? = null

    fun dir(c: Context) = File(c.filesDir, "voice/sensevoice")
    fun ready(c: Context) = parts.all { File(dir(c), it.name).length() == it.size }

    fun download(c: Context): String? {
        problem = null
        return try {
            VoiceModel.fetch(dir(c), parts) { progress = it }
            null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            problem
        } finally {
            progress = -1
        }
    }

    fun delete(c: Context) {
        dir(c).deleteRecursively()
    }
}

class Tone(c: Context) {
    private val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = File(ToneModel.dir(c), "model.int8.onnx").path, language = "auto",
                useInverseTextNormalization = false),
            tokens = File(ToneModel.dir(c), "tokens.txt").path, numThreads = 2)))

    // The line's emotion and sound ("" for none), from at most its first 15 s.
    @Synchronized
    fun hear(samples: FloatArray): Pair<String, String> {
        val s = recognizer.createStream()
        try {
            s.acceptWaveform(if (samples.size > 240_000) samples.copyOf(240_000) else samples, 16000)
            recognizer.decode(s)
            val r = recognizer.getResult(s)
            return emotion(r.emotion) to sound(r.event)
        } finally {
            s.release()
        }
    }

    @Synchronized
    fun release() = recognizer.release()

    companion object {
        private fun tag(t: String) = t.replace("<|", "").replace("|>", "").trim().lowercase(Locale.US)

        fun emotion(t: String) = when (tag(t)) {
            "happy" -> "happy"
            "sad" -> "sad"
            "angry" -> "angry"
            "fearful" -> "fearful"
            "surprised" -> "surprised"
            "disgusted" -> "disgusted"
            else -> ""
        }

        fun sound(t: String) = when (tag(t)) {
            "laughter" -> "laughter"
            "bgm" -> "music"
            "applause" -> "applause"
            "cry" -> "crying"
            "cough" -> "cough"
            "sneeze" -> "sneeze"
            else -> ""
        }
    }
}

object Words {
    class Person(val who: String) {
        var seconds = 0.0
        var words = 0
        var lines = 0
        var questions = 0
        var fillers = 0
        var over = 0 // started while someone else was still talking
        var longest = 0.0 // seconds in a row
        val emotions = HashMap<String, Int>()
        var laughs = 0
        val wpm get() = if (seconds > 5) words / (seconds / 60.0) else 0.0
    }

    class Stats(val people: List<Person>, val top: List<String>, val seconds: Double, val lines: Int)

    val STOP = setOf("the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was",
        "were", "be", "been", "it", "its", "it's", "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
        "me", "him", "her", "us", "them", "my", "your", "his", "our", "their", "so", "if", "then", "there", "here", "what",
        "when", "where", "who", "how", "why", "do", "does", "did", "not", "no", "yes", "yeah", "okay", "ok", "oh", "just",
        "like", "um", "uh", "have", "has", "had", "can", "could", "would", "will", "should", "about", "know", "think", "get",
        "got", "go", "going", "gonna", "really", "right", "well", "all", "one", "i'm", "don't", "that's", "there's", "from",
        "up", "out", "as", "by", "some", "more", "very", "also", "too", "want", "mean", "see", "say", "said", "now")

    private val FILLERS = listOf("um", "uh", "erm", "uhm", "you know", "i mean", "basically", "literally", "kind of", "sort of")

    fun words(t: String) = t.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    fun fillers(t: String): Int {
        val s = " " + words(t).joinToString(" ") + " "
        return FILLERS.sumOf { f -> Regex(" ${Regex.escape(f)} ").findAll(s).count() }
    }

    // Stats of lines (oldest first); names: id -> name; baseline: word -> lines it was in, over many lines (top
    // words are the ones more common here than there).
    fun stats(lines: List<ListenDb.Line>, names: Map<String, String>, baseline: Map<String, Int> = emptyMap(),
              baseLines: Int = 0): Stats {
        val people = LinkedHashMap<String, Person>()
        var prev: ListenDb.Line? = null
        var runWho = ""
        var runFrom = 0L
        val counts = HashMap<String, Int>()
        for (l in lines) {
            val who = names[l.person.ifEmpty { l.voice }] ?: "Someone"
            val p = people.getOrPut(who) { Person(who) }
            val secs = max(0L, l.end - l.start) / 1000.0
            p.seconds += secs
            val w = words(l.text)
            p.words += w.size
            p.lines++
            if (l.text.trimEnd().endsWith("?")) p.questions++
            p.fillers += fillers(l.text)
            if (l.emotion.isNotEmpty()) p.emotions[l.emotion] = (p.emotions[l.emotion] ?: 0) + 1
            if (l.sound == "laughter") p.laughs++
            prev?.let { q ->
                val qWho = names[q.person.ifEmpty { q.voice }] ?: "Someone"
                if (qWho != who && l.start < q.end - 300) p.over++
            }
            if (who != runWho) {
                runWho = who
                runFrom = l.start
            }
            p.longest = max(p.longest, (l.end - runFrom) / 1000.0)
            for (x in w.toSet()) if (x !in STOP && x.length > 2) counts[x] = (counts[x] ?: 0) + 1
            prev = l
        }
        // Words that stood out: counted here, weighted by how rare they were before.
        val top = counts.entries.filter { it.value >= 2 }
            .sortedByDescending { e -> e.value * ln((baseLines + 1.0) / (1.0 + (baseline[e.key] ?: 0))) }
            .take(12).map { it.key }
        val total = people.values.sumOf { it.seconds }
        return Stats(people.values.sortedByDescending { it.seconds }, top, total, lines.size)
    }

    // A line for the glasses: "You 54% · Maria 31% · 12 questions · 9 fillers · 2 talked over".
    fun line(s: Stats): String {
        if (s.lines == 0) return "nothing said yet"
        val share = s.people.take(3).joinToString(" · ") { "${it.who} ${pct(it.seconds, s.seconds)}%" }
        val q = s.people.sumOf { it.questions }
        val f = s.people.sumOf { it.fillers }
        val o = s.people.sumOf { it.over }
        return "$share · $q questions · $f fillers · $o talked over"
    }

    fun pct(a: Double, b: Double) = if (b > 0) (a * 100 / b).toInt() else 0

    // The words of the last days' lines: word -> lines it was in, and the line count (the baseline).
    fun baseline(c: Context, days: Int = 30): Pair<Map<String, Int>, Int> {
        val db = ListenDb.get(c)
        val now = System.currentTimeMillis()
        val lines = db.between(now - days * 86_400_000L, now).takeLast(20_000)
        val m = HashMap<String, Int>()
        for (l in lines) for (w in words(l.text).toSet()) m[w] = (m[w] ?: 0) + 1
        return m to lines.size
    }

    // A readable report (the Listen page, meeting notes).
    fun report(s: Stats): String {
        val sb = StringBuilder()
        for (p in s.people) {
            sb.append("${p.who}: ${pct(p.seconds, s.seconds)}% of the talk, ${p.words} words")
            if (p.wpm > 0) sb.append(", ${p.wpm.toInt()} words a minute")
            if (p.questions > 0) sb.append(", ${p.questions} questions")
            if (p.fillers > 0) sb.append(", ${p.fillers} fillers")
            if (p.over > 0) sb.append(", talked over someone ${p.over}x")
            if (p.longest >= 30) sb.append(", longest stretch ${(p.longest / 60).toInt()}:${String.format(Locale.US, "%02d", (p.longest % 60).toInt())}")
            val mood = p.emotions.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key} ${it.value}" }
            if (mood.isNotEmpty()) sb.append("; sounded $mood")
            if (p.laughs > 0) sb.append("; laughed ${p.laughs}x")
            sb.append("\n")
        }
        if (s.top.isNotEmpty()) sb.append("Words that stood out: ${s.top.joinToString(", ")}\n")
        return sb.toString()
    }
}

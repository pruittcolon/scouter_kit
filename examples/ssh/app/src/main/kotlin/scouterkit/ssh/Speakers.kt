package scouterkit.ssh

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

// Who is speaking (Listen mode): voiceprints from a WeSpeaker model (sherpa-onnx), matched to the people Vox was
// told about, or grouped into "Voice N" until someone names them. The matching is Vox Amelior's, ported: a person
// needs a high enough similarity and a lead over the next best, a "not this person" example closer than the person
// vetoes the name, each person has up to five voice patterns (close up, across the room...), and unknown voices
// form groups that stop drifting after 50 lines. A wrong name is worse than "Voice 3": below the bar, no name.

// The voiceprint models (sherpa-onnx's release "speaker-recongition-models", WeSpeaker, Apache-2.0; trained on
// VoxCeleb, large-margin fine-tuned). CAM++: light and accurate (the default for all day). ResNet293: the most
// accurate, several times the work per line.
object VoiceprintModel {
    class Choice(val id: String, val name: String, val about: String, val part: VoiceModel.Part)

    private const val R = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"
    val CHOICES = listOf(
        Choice("campp", "CAM++", "light, accurate: best for all day", VoiceModel.Part("wespeaker_campp_lm.onnx",
            "$R/wespeaker_en_voxceleb_CAM%2B%2B_LM.onnx", 29292687, "e197af7e9d473030cf486b3124149a19bf37014d0e4485e4c70c483b0ec10cb2")),
        Choice("resnet293", "ResNet293", "the most accurate, more battery", VoiceModel.Part("wespeaker_resnet293_lm.onnx",
            "$R/wespeaker_en_voxceleb_resnet293_LM.onnx", 114336527, "f65dbc820e534eef64ae12d1e289e20244d60e60f7f00d7b092092b1c458be2e")),
    )

    @Volatile var progress = -1L
    @Volatile var problem: String? = null

    fun chosen(c: Context): Choice = CHOICES.firstOrNull { it.id == Settings(c).listenVoiceModel } ?: CHOICES[0]
    fun dir(c: Context) = File(c.filesDir, "voice/speakers")
    fun file(c: Context, ch: Choice = chosen(c)) = File(dir(c), ch.part.name)
    fun ready(c: Context, ch: Choice = chosen(c)) = file(c, ch).length() == ch.part.size

    fun download(c: Context, ch: Choice = chosen(c)): String? {
        problem = null
        return try {
            VoiceModel.fetch(dir(c), listOf(ch.part)) { progress = it }
            null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            problem
        } finally {
            progress = -1
        }
    }

    fun delete(c: Context, ch: Choice) {
        file(c, ch).delete()
    }
}

// A voiceprint model loaded: audio (16 kHz) to a unit-length voiceprint.
class Voiceprints(c: Context) {
    val model: String = VoiceprintModel.chosen(c).id
    private val ex = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(
        model = VoiceprintModel.file(c).path, numThreads = 2))
    val dim = ex.dim()

    // Null when there is too little audio for one.
    private var gone = false

    @Synchronized
    fun print(samples: FloatArray): FloatArray? {
        if (gone) return null // let go meanwhile (another thread still held it)
        val s = ex.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            s.inputFinished()
            if (!ex.isReady(s)) return null
            return Speakers.unit(ex.compute(s))
        } finally {
            s.release()
        }
    }

    @Synchronized
    fun release() {
        if (gone) return
        gone = true
        ex.release()
    }
}

object Speakers {
    const val ME = "me" // the person who is you

    // A voice remembered from clips of it (the quiet-voice check, "Record my voice"): each clip cut into pieces of
    // 2-6 s, a print each, the odd ones out dropped. Into the person of that name (made if new; id given: that
    // person). Null when done, else why not. Blocks (seconds): call it off the main thread.
    fun enroll(c: Context, name: String, soft: Boolean, clips: List<FloatArray>, id: String? = null): String? {
        if (!VoiceprintModel.ready(c)) return "download the voiceprint model first"
        val vp = runCatching { Voiceprints(c) }.getOrElse { return "the voiceprint model did not load: ${it.message}" }
        try {
            val prints = ArrayList<FloatArray>()
            for (clip in clips) {
                var at = 0
                while (at < clip.size) {
                    val end = if (clip.size - at < 16000 * 8) clip.size else at + 16000 * 5
                    if (end - at >= 16000 * 2) vp.print(clip.copyOfRange(at, end))?.let { prints += it }
                    at = end
                }
            }
            val clean = keepClean(prints)
            if (clean.size < 3) return "too little clear speech (${clean.size} pieces): record a bit longer"
            val db = ListenDb.get(c)
            val person = id?.also { db.addPerson(name, soft, it) } ?: db.personNamed(name) ?: db.addPerson(name, soft)
            if (soft) db.setSoft(person, true)
            db.addSamples(person, clean, "enrolled", vp.model)
            Listen.peopleChanged()
            return null
        } finally {
            vp.release()
        }
    }

    class Person(val id: String, var name: String, var soft: Boolean, var centroid: FloatArray?,
                 var patterns: List<FloatArray>, var negatives: List<FloatArray>)

    class Voice(val id: String, var label: String, var centroid: FloatArray, var count: Int, var updated: Long)

    // Who: a person's id, or a voice group (voice, created now or not), or nobody (too short / no model).
    class Match(val person: String?, val voice: Voice?, val score: Float, val created: Boolean = false)

    // The bars (cosine similarity), per model; calibrate() moves the person bar to the people's own voices.
    class Bars(var person: Float = 0.50f, val margin: Float = 0.04f, var group: Float = 0.55f, val minSeconds: Float = 1.0f,
               val maxGroups: Int = 30, val groupWeight: Int = 50, val vetoFloor: Float = 0.55f)

    fun unit(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
    }

    fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun mean(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val m = FloatArray(list[0].size)
        for (v in list) for (i in m.indices) m[i] += v[i]
        return unit(m)
    }

    fun blob(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    fun floats(b: ByteArray?): FloatArray? {
        if (b == null || b.isEmpty()) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }

    // Patterns: several prints packed together (all the same length).
    fun blobs(list: List<FloatArray>): ByteArray = if (list.isEmpty()) ByteArray(0) else {
        val bb = ByteBuffer.allocate(4 + list.size * list[0].size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(list[0].size)
        for (v in list) for (x in v) bb.putFloat(x)
        bb.array()
    }

    fun unblobs(b: ByteArray?): List<FloatArray> {
        if (b == null || b.size < 8) return emptyList()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val dim = bb.int
        if (dim <= 0) return emptyList()
        val n = (b.size - 4) / (dim * 4)
        return List(n) { FloatArray(dim) { bb.float } }
    }

    // A person's voice patterns: their samples split into 2 ... 5 groups of similar sound (at least 3 each), each
    // group's average; none with too few samples (then only the overall average counts). Deterministic.
    fun patterns(samples: List<FloatArray>, minGroup: Int = 3, kMax: Int = 5): List<FloatArray> {
        if (samples.size < minGroup * 2) return emptyList()
        for (k in minOf(kMax, samples.size / minGroup) downTo 2) {
            // Farthest-point start, then k-means.
            val centers = mutableListOf(samples[0])
            while (centers.size < k) centers += samples.maxByOrNull { s -> -centers.maxOf { cos(it, s) } }!!
            var assign = IntArray(samples.size)
            repeat(20) {
                assign = IntArray(samples.size) { i -> centers.indices.maxByOrNull { cos(centers[it], samples[i]) }!! }
                for (j in centers.indices) mean(samples.filterIndexed { i, _ -> assign[i] == j })?.let { centers[j] = it }
            }
            if ((0 until k).all { j -> assign.count { it == j } >= minGroup }) return centers
        }
        return emptyList()
    }

    // Clean samples only: ones far from the rest (a TV, another person) are dropped.
    fun keepClean(prints: List<FloatArray>): List<FloatArray> {
        if (prints.size < 3) return prints
        val m = mean(prints) ?: return prints
        val sims = prints.map { cos(it, m) }
        val avg = sims.average()
        val sd = sqrt(sims.map { (it - avg) * (it - avg) }.average())
        return prints.filterIndexed { i, _ -> sims[i] >= max(0.3, avg - 2 * sd) }
    }

    // The person bar from the people's own samples: halfway between how alike one person's samples are and how
    // alike two people's are, kept between 0.35 and 0.70 (unchanged with fewer than two people).
    fun calibrate(people: List<Pair<String, List<FloatArray>>>, bars: Bars) {
        val withSamples = people.filter { it.second.size >= 2 }
        if (withSamples.size < 2) return
        val same = ArrayList<Float>()
        val diff = ArrayList<Float>()
        for ((i, a) in withSamples.withIndex()) {
            val sa = a.second.takeLast(20)
            for (x in sa.indices) for (y in x + 1 until sa.size) same += cos(sa[x], sa[y])
            for (b in withSamples.drop(i + 1)) for (x in sa) for (y in b.second.takeLast(20)) diff += cos(x, y)
        }
        if (same.isEmpty() || diff.isEmpty()) return
        same.sort()
        diff.sort()
        // The 10th percentile of same-person and the 90th of different people.
        val lo = same[(same.size * 0.1).toInt().coerceIn(0, same.size - 1)]
        val hi = diff[(diff.size * 0.9).toInt().coerceIn(0, diff.size - 1)]
        bars.person = ((lo + hi) / 2).coerceIn(0.35f, 0.70f)
        bars.group = (bars.person + 0.05f).coerceIn(0.40f, 0.75f)
    }

    // The matcher: people and voice groups in memory; the caller saves what changes.
    class Identifier(var people: List<Person>, val voices: MutableList<Voice>, val bars: Bars, private val newLabel: () -> String) {
        fun score(p: Person, v: FloatArray): Float {
            var s = p.centroid?.let { cos(v, it) } ?: -1f
            for (pt in p.patterns) s = max(s, cos(v, pt))
            return s
        }

        fun identify(v: FloatArray, seconds: Float): Match {
            val ranked = people.map { it to score(it, v) }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            if (best != null) {
                val soft = best.first.soft
                val bar = bars.person - if (soft) 0.05f else 0f
                val enough = seconds >= bars.minSeconds - if (soft) 0.3f else 0f
                val second = ranked.getOrNull(1)?.second ?: -1f
                if (enough && best.second >= bar && best.second - second >= bars.margin && !vetoed(best.first, v, best.second))
                    return Match(best.first.id, null, best.second)
            }
            if (seconds < bars.minSeconds) return Match(null, null, 0f)
            return group(v)
        }

        private fun vetoed(p: Person, v: FloatArray, score: Float) = p.negatives.any { n ->
            val s = cos(v, n)
            s >= bars.vetoFloor && s > score
        }

        private fun group(v: FloatArray): Match {
            val best = voices.maxByOrNull { cos(v, it.centroid) }
            val s = best?.let { cos(v, it.centroid) } ?: -1f
            if (best != null && s >= bars.group) {
                absorb(best, v)
                return Match(null, best, s)
            }
            if (voices.size >= bars.maxGroups) {
                // Too many: the one unheard longest makes room (it keeps its lines and label).
                voices.minByOrNull { it.updated }?.let { voices.remove(it) }
            }
            val fresh = Voice(java.util.UUID.randomUUID().toString().take(8), newLabel(), v, 1, System.currentTimeMillis())
            voices += fresh
            return Match(null, fresh, 1f, created = true)
        }

        fun absorb(g: Voice, v: FloatArray) {
            val w = g.count.coerceIn(1, bars.groupWeight)
            g.centroid = unit(FloatArray(v.size) { g.centroid[it] * w + v[it] })
            g.count++
            g.updated = System.currentTimeMillis()
        }
    }
}

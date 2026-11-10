package scouterkit.ssh

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.min

// Speaker changes inside lines (Listen mode): a phrase where two people talked ("yeah" "no, Thursday") is cut into
// one line each. Per chunk of recent lines (up to 30 s of speech, or until a 3 s pause), sherpa-onnx's diarization
// (pyannote segmentation 3.0, MIT, plus the chosen voiceprint model, clustered) says who talked when; Vox Amelior's
// rules decide: slivers at a line's edges belong to its neighbours, a part whose voice cannot be named joins its
// longer neighbour, and a line is cut only when every part lasts 1.5 s and has two words. Each part is named from
// its own audio. Only chunks with a line of 3 s or more are looked at (shorter ones cannot be cut).
object SegmentationModel {
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/" +
        "9403a6902bb58e3d5ae8c7e77c3422de279db2e0"
    val part = VoiceModel.Part("pyannote_segmentation_3.onnx", "$HF/model.onnx", 5992913,
        "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079")

    fun file(c: Context) = File(VoiceprintModel.dir(c), part.name)
    fun ready(c: Context) = file(c).length() == part.size

    // Small: fetched with the voiceprint model when needed.
    fun download(c: Context): String? = runCatching {
        VoiceModel.fetch(VoiceprintModel.dir(c), listOf(part)) {}
        null
    }.getOrElse { it.message ?: "the download stopped" }
}

class Turns(c: Context) {
    // A line of the chunk with its audio and word times (ms from its start).
    class Piece(val id: Long, val start: Long, val samples: FloatArray, val text: String, val words: List<Pair<String, Int>>,
                val quiet: Boolean)

    // What a line becomes: parts, each with its times, text, words, and audio (to name it).
    class Part(val start: Long, val end: Long, val text: String, val words: List<Pair<String, Int>>, val samples: FloatArray)

    private class Turn(var start: Float, var end: Float, val speaker: Int) {
        val dur get() = end - start
    }

    private val diar = OfflineSpeakerDiarization(null, OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SegmentationModel.file(c).path), numThreads = 2),
        embedding = SpeakerEmbeddingExtractorConfig(model = VoiceprintModel.file(c).path, numThreads = 2),
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
        minDurationOn = 0.24f, minDurationOff = 0.24f))
    val model: String = VoiceprintModel.chosen(c).id

    @Synchronized
    fun release() = diar.release()

    // The lines to cut (id -> its parts) in a chunk; the others stay. named(samples): whether a voice can be
    // named (a voiceprint and a match), so an unnamed one never starts a line of its own.
    @Synchronized
    fun split(chunk: List<Piece>, named: (FloatArray) -> Boolean): Map<Long, List<Part>> {
        if (chunk.none { it.samples.size >= 48000 }) return emptyMap()
        val total = chunk.sumOf { it.samples.size }
        val audio = FloatArray(total)
        val offsets = ArrayList<Int>()
        var at = 0
        for (p in chunk) {
            offsets += at
            System.arraycopy(p.samples, 0, audio, at, p.samples.size)
            at += p.samples.size
        }
        val segs = diar.process(audio).map { Turn(it.start, it.end, it.speaker) }.sortedBy { it.start }
        if (segs.map { it.speaker }.distinct().size < 2) return emptyMap()
        val out = HashMap<Long, List<Part>>()
        for ((i, p) in chunk.withIndex()) {
            if (p.samples.size < 48000) continue
            val from = offsets[i] / 16000f
            val to = from + p.samples.size / 16000f
            var parts = within(segs, from, to)
            if (parts.size < 2) continue
            // Parts whose voice cannot be named join their longer neighbour; then neighbours of one voice join.
            parts = absorb(parts) { t -> named(slice(p.samples, t)) }
            parts = joinSame(parts)
            if (parts.size < 2 || parts.any { it.dur < 1.5f }) continue
            val cut = parts.map { t ->
                val a = (t.start * 1000).toInt()
                val b = (t.end * 1000).toInt()
                val w = p.words.filter { it.second in a until b }
                Part(p.start + a, p.start + b, w.joinToString(" ") { it.first }, w.map { it.first to it.second - a }, slice(p.samples, t))
            }
            if (cut.any { part -> part.words.size < 2 }) continue
            out[p.id] = cut
        }
        return out
    }

    private fun slice(s: FloatArray, t: Turn): FloatArray {
        val a = (t.start * 16000).toInt().coerceIn(0, s.size)
        val b = (t.end * 16000).toInt().coerceIn(a, s.size)
        return s.copyOfRange(a, b)
    }

    // The turns inside [from, to) seconds, relative to from; silence goes to the turn before it, slivers (under 1.5 s)
    // at the edges to their neighbour.
    private fun within(all: List<Turn>, from: Float, to: Float): List<Turn> {
        val out = ArrayList<Turn>()
        for (t in all) {
            val a = max(t.start, from)
            val b = min(t.end, to)
            if (b > a) out += Turn(a - from, b - from, t.speaker)
        }
        if (out.isEmpty()) return out
        out[0].start = 0f
        for (k in 1 until out.size) out[k - 1].end = out[k].start // gaps belong to the turn before
        out[out.size - 1].end = to - from
        while (out.size > 1 && out.first().dur < 1.5f) { out[1].start = 0f; out.removeAt(0) }
        while (out.size > 1 && out.last().dur < 1.5f) { out[out.size - 2].end = out.last().end; out.removeAt(out.size - 1) }
        return out
    }

    private fun absorb(parts: List<Turn>, named: (Turn) -> Boolean): List<Turn> {
        val list = ArrayList(parts)
        while (list.size > 1) {
            val i = list.indexOfFirst { it.dur < 1.5f || !named(it) }
            if (i < 0) break
            val left = list.getOrNull(i - 1)
            val right = list.getOrNull(i + 1)
            if (right == null || (left != null && left.dur >= right.dur)) left!!.end = list[i].end else right.start = list[i].start
            list.removeAt(i)
        }
        return list
    }

    private fun joinSame(parts: List<Turn>): List<Turn> {
        val out = ArrayList<Turn>()
        for (t in parts) if (out.isNotEmpty() && out.last().speaker == t.speaker) out.last().end = t.end else out += t
        return out
    }

    companion object {
        fun words(json: String): List<Pair<String, Int>> = runCatching {
            val a = JSONArray(json)
            List(a.length()) { a.getJSONArray(it).let { w -> w.getString(0) to w.getInt(1) } }
        }.getOrDefault(emptyList())

        fun json(words: List<Pair<String, Int>>): String {
            val a = JSONArray()
            for ((w, t) in words) a.put(JSONArray().put(w).put(t))
            return a.toString()
        }

        fun log(msg: String) = Log.i(ScouterService.TAG, "turns: $msg")
    }
}

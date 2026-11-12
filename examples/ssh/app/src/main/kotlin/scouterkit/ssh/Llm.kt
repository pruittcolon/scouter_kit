package scouterkit.ssh

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// The on-phone language models (llama.cpp, native/llm_jni.cpp): Liquid's LFM2.5 to answer and summarize, and
// EmbeddingGemma to search by meaning. Each is a GGUF file downloaded once on the Listen page (pinned revision,
// SHA-256 checked). Loaded when needed and let go when idle: the quick model after 10 min, the deep one after 3
// (it is several GB), the embedding model after 10.
object LlmNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("vox_llm"); true }.getOrElse {
            Log.w(ScouterService.TAG, "llm: no vox_llm in this build: ${it.message}")
            false
        }
    }

    // Each piece of an answer as it comes (UTF-8 bytes); false stops it.
    interface Sink {
        fun piece(b: ByteArray): Boolean
    }

    external fun load(path: String, nCtx: Int, threads: Int, batchThreads: Int, embed: Boolean, gpuLayers: Int): Long
    external fun threads(h: Long, threads: Int, batchThreads: Int)
    external fun timings(h: Long): LongArray // the last generate: prompt tokens, prompt µs, generated tokens, generation µs
    external fun devices(): String // the GPUs llama.cpp can use, "" none
    external fun env(key: String, value: String)
    external fun free(h: Long)
    external fun stop(h: Long)
    external fun count(h: Long, text: ByteArray): Int
    external fun generate(h: Long, prompt: ByteArray, maxTokens: Int, temp: Float, minP: Float, sink: Sink?): ByteArray
    external fun embed(h: Long, text: ByteArray): FloatArray?
}

object LlmModels {
    // role: "quick" (fast answers, titles, action items), "deep" (summaries, questions across days), "embed".
    class Model(val id: String, val name: String, val about: String, val role: String, val ctx: Int, val thinks: Boolean,
                val part: VoiceModel.Part)

    private fun hf(repo: String, rev: String, file: String) = "https://huggingface.co/$repo/resolve/$rev/$file"

    private const val Q = "LiquidAI/LFM2.5-1.2B-Instruct-GGUF"
    private const val QREV = "8ed288026e23958ad9dfa92d53ed773a8eee7125"
    private const val M = "LiquidAI/LFM2.5-2.6B-GGUF"
    private const val MREV = "e7caca5d835a3901a8e0d63e94009429bafafdfc"
    private const val B = "LiquidAI/LFM2.5-8B-A1B-GGUF"
    private const val BREV = "49c14831707011e64d70b2ebd8462ba08d608434"
    private const val E = "ggml-org/embeddinggemma-300M-GGUF"
    private const val EREV = "0f741b5a6585bd53aeb15cd1372c56f2a0f65e12"

    val QUICK = Model("lfm-1.2b", "LFM2.5 1.2B", "quick answers, titles, action items", "quick", 8192, false,
        VoiceModel.Part("LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf", hf(Q, QREV, "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf"), 695755488,
            "bb741ebb106d543e9de114b843a3d3d73d51c74b5801e69da2abde821a0cb3e1"))
    val MID = Model("lfm-2.6b", "LFM2.5 2.6B", "better summaries, still light", "deep", 16384, false,
        VoiceModel.Part("LFM2.5-2.6B-QAD-Q4_0.gguf", hf(M, MREV, "LFM2.5-2.6B-QAD-Q4_0.gguf"), 1593894944,
            "a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03"))
    val BIG = Model("lfm-8b-a1b", "LFM2.5 8B-A1B", "the best: thinks first (8B, 1.5B active)", "deep", 16384, true,
        VoiceModel.Part("LFM2.5-8B-A1B-Q4_0.gguf", hf(B, BREV, "LFM2.5-8B-A1B-Q4_0.gguf"), 4844678368,
            "48ed1465d761311b2fd57b7fb46cf969a20b3a8281945b04e10f52bd1609e715"))
    val EMBED = Model("embeddinggemma", "EmbeddingGemma 300M", "search by meaning", "embed", 1024, false,
        VoiceModel.Part("embeddinggemma-300M-Q8_0.gguf", hf(E, EREV, "embeddinggemma-300M-Q8_0.gguf"), 333590944,
            "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63"))
    val ALL = listOf(QUICK, MID, BIG, EMBED)

    @Volatile var downloading = "" // the model being downloaded
    @Volatile var progress = -1L
    @Volatile var problem: String? = null

    fun dir(c: Context) = File(c.filesDir, "models")
    fun file(c: Context, m: Model) = File(dir(c), m.part.name)
    fun ready(c: Context, m: Model) = file(c, m).length() == m.part.size

    fun download(c: Context, m: Model): String? {
        problem = null
        downloading = m.id
        return try {
            VoiceModel.fetch(dir(c), listOf(m.part)) { progress = it }
            null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            problem
        } finally {
            progress = -1
            downloading = ""
        }
    }

    fun delete(c: Context, m: Model) {
        Llm.unload(m.id)
        file(c, m).delete()
    }

    // The deep model: the biggest downloaded (8B-A1B, else 2.6B), else the quick one.
    fun deep(c: Context): Model? = listOf(BIG, MID, QUICK).firstOrNull { ready(c, it) }

    // The CPU can run them (dot products and int8 matrix multiply, what vox_llm is built for).
    val cpuOk: Boolean by lazy {
        val f = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        f.contains("asimddp") && f.contains("i8mm")
    }

    fun usable() = LlmNative.available && cpuOk
}

// Loaded models by id, each used by one caller at a time; let go when idle.
object Llm {
    private class Loaded(val model: LlmModels.Model, val handle: Long, var used: Long)

    private val loaded = HashMap<String, Loaded>()
    private val locks = HashMap<String, Any>()
    private var sweeper: Thread? = null

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Any() } }

    private fun idleMs(m: LlmModels.Model) = if (m.id == LlmModels.BIG.id || m.id == LlmModels.MID.id) 3 * 60_000L else 10 * 60_000L

    // ------------------------------------------------ Vox thinking: Listen waits, the model gets the phone

    // Answers being worked on. While any is, Listen writes nothing down (its audio waits on disk) and the model runs
    // on the phone's cores (or its GPU); Listen catches up afterwards.
    val busy = java.util.concurrent.atomic.AtomicInteger(0)

    fun <T> hold(block: () -> T): T {
        if (busy.incrementAndGet() == 1) Log.i(ScouterService.TAG, "llm: thinking: Listen waits")
        Listen.nudge()
        try {
            return block()
        } finally {
            if (busy.decrementAndGet() == 0) Log.i(ScouterService.TAG, "llm: done thinking: Listen catches up")
            Listen.nudge()
        }
    }

    // The GPU llama.cpp found ("" none; this build may have no GPU backend). Its compiled kernels are kept in the
    // app's cache, so only the first load compiles them.
    @Volatile private var gpuName: String? = null

    fun gpu(c: Context): String = gpuName ?: synchronized(this) {
        gpuName ?: (if (LlmNative.available) runCatching {
            val dir = java.io.File(c.cacheDir, "opencl").apply { mkdirs() }
            LlmNative.env("GGML_OPENCL_KERNEL_CACHE_DIR", dir.path)
            LlmNative.devices()
        }.getOrDefault("") else "").also {
            gpuName = it
            Log.i(ScouterService.TAG, "llm: GPU: " + it.ifEmpty { "none (the CPU)" })
        }
    }

    // Where a model runs: the GPU when there is one and it is allowed, else the CPU.
    fun onGpu(c: Context, m: LlmModels.Model) = m.role != "embed" && Settings(c).llmGpu && gpu(c).isNotEmpty()

    // ------------------------------------------------ speed, for time estimates

    // Tokens a second, measured on this phone: prompt reading and writing, per model (and per CPU / GPU).
    private fun speedKey(c: Context, m: LlmModels.Model) = m.id + if (onGpu(c, m)) "_gpu" else "_cpu"

    fun speed(c: Context, m: LlmModels.Model): Pair<Float, Float> {
        val p = c.getSharedPreferences("llm_speed", Context.MODE_PRIVATE)
        val k = speedKey(c, m)
        // First guesses for the S25's cores, until measured.
        val (pp, tg) = when (m.id) {
            LlmModels.QUICK.id -> 220f to 35f
            LlmModels.MID.id -> 100f to 18f
            LlmModels.BIG.id -> 70f to 22f
            else -> 300f to 30f
        }
        return p.getFloat(k + "_pp", pp) to p.getFloat(k + "_tg", tg)
    }

    private fun learn(c: Context, m: LlmModels.Model, t: LongArray) {
        if (t.size < 4) return
        val p = c.getSharedPreferences("llm_speed", Context.MODE_PRIVATE)
        val k = speedKey(c, m)
        val (pp, tg) = speed(c, m)
        val e = p.edit()
        if (t[0] >= 64 && t[1] > 0) e.putFloat(k + "_pp", pp * 0.6f + 0.4f * (t[0] * 1e6f / t[1]))
        if (t[2] >= 16 && t[3] > 0) e.putFloat(k + "_tg", tg * 0.6f + 0.4f * (t[2] * 1e6f / t[3]))
        e.apply()
    }

    // About how long a call takes: its prompt and answer tokens at the measured speeds, plus loading it if needed.
    fun estimateMs(c: Context, m: LlmModels.Model, promptTokens: Int, answerTokens: Int): Long {
        val (pp, tg) = speed(c, m)
        val load = if (synchronized(loaded) { loaded.containsKey(m.id) }) 0L else 1500L + m.part.size / 2_000_000L
        return load + (promptTokens / pp * 1000).toLong() + (answerTokens / tg * 1000).toLong()
    }

    private fun get(c: Context, m: LlmModels.Model): Loaded? {
        if (!LlmModels.usable() || !LlmModels.ready(c, m)) return null
        synchronized(loaded) { loaded[m.id]?.let { it.used = System.currentTimeMillis(); return it } }
        // Listen waits while a model works: generation on 6 of the 8 cores, prompt reading on 7 (one for the
        // microphone and the screen). Embeddings run beside Listen: 2.
        val embed = m.role == "embed"
        val threads = if (embed) 2 else 6
        val batch = if (embed) 2 else 7
        val gpuLayers = if (onGpu(c, m)) 99 else 0
        val t0 = System.currentTimeMillis()
        val h = LlmNative.load(LlmModels.file(c, m).path, m.ctx, threads, batch, embed, gpuLayers)
        if (h == 0L) {
            Log.w(ScouterService.TAG, "llm: ${m.name} did not load")
            return null
        }
        Log.i(ScouterService.TAG, "llm: ${m.name} loaded in ${System.currentTimeMillis() - t0} ms" +
            if (gpuLayers > 0) " on the GPU (${gpu(c)})" else " on the CPU")
        val l = Loaded(m, h, System.currentTimeMillis())
        synchronized(loaded) { loaded[m.id] = l }
        sweep()
        return l
    }

    fun unload(id: String) {
        synchronized(lock(id)) {
            val l = synchronized(loaded) { loaded.remove(id) } ?: return
            LlmNative.free(l.handle)
            Log.i(ScouterService.TAG, "llm: ${l.model.name} let go")
        }
    }

    // Models unused for a while leave memory.
    private fun sweep() {
        if (sweeper != null) return
        sweeper = Thread({
            while (true) {
                Thread.sleep(30_000)
                val now = System.currentTimeMillis()
                val old = synchronized(loaded) { loaded.values.filter { now - it.used > idleMs(it.model) }.map { it.model.id } }
                for (id in old) unload(id)
                if (synchronized(loaded) { loaded.isEmpty() }) break
            }
            sweeper = null
        }, "llm-sweep").apply { isDaemon = true; start() }
    }

    fun stop(m: LlmModels.Model) {
        synchronized(loaded) { loaded[m.id] }?.let { LlmNative.stop(it.handle) }
    }

    // A chat turn (LFM2.5's ChatML): the answer, its thinking left out; onText gets the answer so far as it grows.
    // Null when the model is not there or the prompt was too long.
    fun chat(c: Context, m: LlmModels.Model, system: String, user: String, maxTokens: Int = 700, temp: Float = 0.3f,
             onText: ((String) -> Unit)? = null): String? = hold { synchronized(lock(m.id)) {
        val l = get(c, m) ?: return@hold null
        val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        val acc = java.io.ByteArrayOutputStream()
        val sink = object : LlmNative.Sink {
            override fun piece(b: ByteArray): Boolean {
                acc.write(b)
                onText?.invoke(visible(acc.toString(Charsets.UTF_8.name())))
                return true
            }
        }
        val out = LlmNative.generate(l.handle, prompt.toByteArray(), maxTokens, temp, 0.15f, sink)
        l.used = System.currentTimeMillis()
        runCatching { learn(c, m, LlmNative.timings(l.handle)) }
        val text = String(out, Charsets.UTF_8)
        if (text.isEmpty() && LlmNative.count(l.handle, prompt.toByteArray()) >= m.ctx - 16) return@hold null
        visible(text).trim()
    } }

    // What to show of an answer: the part after the model's thinking (<think> ... </think>), "" while it thinks.
    fun visible(s: String): String {
        val open = s.indexOf("<think>")
        if (open < 0) return s
        val close = s.indexOf("</think>", open)
        return if (close < 0) s.substring(0, open) else s.substring(0, open) + s.substring(close + 8).trimStart()
    }

    // Tokens in a text for this model (to fit transcript lines into its context); a rough count if not loaded.
    fun tokens(c: Context, m: LlmModels.Model, text: String): Int {
        val l = synchronized(loaded) { loaded[m.id] } ?: return text.length / 3 + 1
        return LlmNative.count(l.handle, text.toByteArray())
    }

    // ------------------------------------------------ embeddings (EmbeddingGemma)

    // A text's vector for search: EmbeddingGemma with its task prompt, cut to 256 dimensions (Matryoshka) and
    // normalized. query: a question (else a line to find). Null without the model.
    fun vector(c: Context, text: String, query: Boolean): FloatArray? = synchronized(lock(LlmModels.EMBED.id)) {
        val l = get(c, LlmModels.EMBED) ?: return null
        val prompt = if (query) "task: search result | query: $text" else "title: none | text: $text"
        val v = LlmNative.embed(l.handle, prompt.toByteArray()) ?: return null
        l.used = System.currentTimeMillis()
        val cut = v.copyOf(minOf(256, v.size))
        var s = 0.0
        for (x in cut) s += x * x
        val n = sqrt(s).toFloat()
        if (n == 0f) null else FloatArray(cut.size) { cut[it] / n }
    }

    // 256 floats as a scale and 256 signed bytes (what listen.db keeps per line).
    fun pack(v: FloatArray): ByteArray {
        var m = 0f
        for (x in v) m = max(m, abs(x))
        val scale = if (m > 0f) m / 127f else 1f
        val out = ByteArray(4 + v.size)
        val bits = java.lang.Float.floatToIntBits(scale)
        out[0] = (bits shr 24).toByte(); out[1] = (bits shr 16).toByte(); out[2] = (bits shr 8).toByte(); out[3] = bits.toByte()
        for (i in v.indices) out[4 + i] = Math.round(v[i] / scale).coerceIn(-127, 127).toByte()
        return out
    }

    // Similarity of a query vector with a packed one.
    fun dot(q: FloatArray, packed: ByteArray, at: Int = 0): Float {
        val bits = ((packed[at].toInt() and 0xFF) shl 24) or ((packed[at + 1].toInt() and 0xFF) shl 16) or
            ((packed[at + 2].toInt() and 0xFF) shl 8) or (packed[at + 3].toInt() and 0xFF)
        val scale = java.lang.Float.intBitsToFloat(bits)
        var s = 0f
        val n = minOf(q.size, 256)
        for (i in 0 until n) s += q[i] * packed[at + 4 + i]
        return s * scale
    }
}

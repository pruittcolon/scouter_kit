package scouterkit.ssh

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Searching what was heard: by words (full text, BM25) and by meaning (EmbeddingGemma vectors, kept per line of four
// words or more), fused by reciprocal rank (k 60) as Vox Amelior does. Meaning is used only when it helps: two or
// more real words in the question, and only lines close enough to the best match (within 0.12, at least 0.30:
// Vox Amelior's calibration). Ask: the lines found (or a time's lines: "yesterday", "last week") given to Liquid's
// LFM2.5 on the phone, which answers with the times and names it read.
object Search {
    class Hit(val line: ListenDb.Line, val words: Boolean, val meaning: Boolean)

    // The vectors in memory: line ids and packed vectors, filled from listen.db and kept up to date.
    private var ids = LongArray(0)
    private var vecs = ByteArray(0)
    private var count = 0
    private var last = 0L
    private val lock = Any()

    private fun index(c: Context) = synchronized(lock) {
        ListenDb.get(c).vectors(last) { id, v ->
            if (v.size != 260) return@vectors
            if (count == ids.size) {
                ids = ids.copyOf(maxOf(1024, ids.size * 2))
                vecs = vecs.copyOf(maxOf(1024, ids.size) * 260)
            }
            ids[count] = id
            System.arraycopy(v, 0, vecs, count * 260, 260)
            count++
            last = id
        }
    }

    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "is", "was", "what", "who",
        "when", "where", "did", "do", "does", "about", "say", "said", "i", "me", "my", "we", "us", "it", "that", "this")

    fun find(c: Context, query: String, limit: Int = 60): List<Hit> {
        val db = ListenDb.get(c)
        val byWords = db.searchRanked(query, 200)
        val content = query.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }
        val byMeaning = if (content.size >= 2 && Settings(c).listenMeaning) meaning(c, query) else emptyList()
        val score = HashMap<Long, Double>()
        for ((r, id) in byWords.withIndex()) score[id] = (score[id] ?: 0.0) + 1.0 / (60 + r)
        for ((r, id) in byMeaning.withIndex()) score[id] = (score[id] ?: 0.0) + 1.0 / (60 + r)
        val top = score.entries.sortedByDescending { it.value }.take(limit).map { it.key }
        val lines = db.lines(top).associateBy { it.id }
        val w = byWords.toHashSet()
        val m = byMeaning.toHashSet()
        return top.mapNotNull { id -> lines[id]?.let { Hit(it, id in w, id in m) } }
    }

    // Line ids by meaning, best first.
    private fun meaning(c: Context, query: String): List<Long> {
        val q = Llm.vector(c, query, true) ?: return emptyList()
        index(c)
        val scored = ArrayList<Pair<Long, Float>>()
        synchronized(lock) {
            for (i in 0 until count) scored += ids[i] to Llm.dot(q, vecs, i * 260)
        }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        val floor = maxOf(0.30f, best - 0.12f)
        return scored.filter { it.second >= floor }.sortedByDescending { it.second }.take(200).map { it.first }
    }

    // Vectors for lines that have none yet (Listen's worker, in its quiet moments): at most n. Lines found.
    fun fill(c: Context, n: Int): Int {
        if (!Settings(c).listenMeaning || !LlmModels.usable() || !LlmModels.ready(c, LlmModels.EMBED) || Llm.busy.get() > 0) return 0
        val db = ListenDb.get(c)
        var done = 0
        for ((id, text) in db.needVectors(n)) {
            val v = Llm.vector(c, text, false) ?: break
            db.setVector(id, Llm.pack(v))
            done++
        }
        return done
    }

    // ------------------------------------------------ time words

    // A time named in a question: today, yesterday, this morning / afternoon / evening, tonight, this week, last
    // week, this month, last month, or a weekday ("on Monday": the last one). From, to (wall clock), or null.
    fun span(question: String, now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val q = question.lowercase(Locale.US)
        val day = ListenDb.dayStart(now)
        fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        fun hours(d: Long, a: Int, b: Int) = d + a * 3600_000L to d + b * 3600_000L
        val weekStart = cal(day).apply {
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
        }.timeInMillis
        val monthStart = cal(day).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val yesterday = cal(day).apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        return when {
            "this morning" in q -> hours(day, 0, 12)
            "this afternoon" in q -> hours(day, 12, 17)
            "this evening" in q || "tonight" in q -> hours(day, 17, 24)
            "yesterday morning" in q -> hours(yesterday, 0, 12)
            "yesterday afternoon" in q -> hours(yesterday, 12, 17)
            "yesterday evening" in q || "last night" in q -> hours(yesterday, 17, 24)
            "yesterday" in q -> yesterday to day
            "today" in q -> day to ListenDb.nextDay(day)
            "last week" in q -> cal(weekStart).apply { add(Calendar.DAY_OF_MONTH, -7) }.timeInMillis to weekStart
            "this week" in q -> weekStart to now
            "last month" in q -> cal(monthStart).apply { add(Calendar.MONTH, -1) }.timeInMillis to monthStart
            "this month" in q -> monthStart to now
            else -> {
                val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                val w = days.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(q) }
                if (w < 0) null else {
                    val d = cal(day).apply {
                        add(Calendar.DAY_OF_MONTH, -1)
                        while (get(Calendar.DAY_OF_WEEK) != w + 1) add(Calendar.DAY_OF_MONTH, -1)
                    }.timeInMillis
                    d to ListenDb.nextDay(d)
                }
            }
        }
    }

    // ------------------------------------------------ asking

    private const val SYSTEM = "You answer questions about the user's own conversations, using only the transcript " +
        "lines given (time, who, words; \"You\" is the user). Answer briefly and plainly, naming who said it and " +
        "when. If the lines do not say, answer that they do not. Never invent lines."

    // An answer from the transcript: deep uses the deep model (when downloaded). onText: the answer as it grows.
    // Null: no model (download one on the Listen page).
    fun ask(c: Context, question: String, deep: Boolean, onText: ((String) -> Unit)? = null): String? {
        val model = (if (deep) LlmModels.deep(c) else null) ?: LlmModels.QUICK.takeIf { LlmModels.ready(c, it) }
            ?: LlmModels.deep(c) ?: return null
        val db = ListenDb.get(c)
        val names = db.names()
        val span = span(question)
        // The lines: a time's lines when one is named (found ones within it first), else the best found with the
        // lines around them.
        val found = find(c, question, 40).map { it.line }
        val pool = LinkedHashMap<Long, ListenDb.Line>()
        if (span != null) {
            for (l in found) if (l.start in span.first until span.second) pool[l.id] = l
            for (l in db.between(span.first, span.second)) pool[l.id] = l
        } else for (l in found.take(15)) for (a in db.around(l.id, 2, 2)) pool[a.id] = a
        val fmt = SimpleDateFormat("EEE MMM d h:mm a", Locale.US)
        fun row(l: ListenDb.Line) = "[${fmt.format(Date(l.start))}] " + (names[l.person.ifEmpty { l.voice }] ?: "Someone") + ": " + l.text
        // As many as fit in about two thirds of the model's context.
        val budget = model.ctx * 2 / 3 * 3 // characters (about 3 per token)
        val picked = ArrayList<ListenDb.Line>()
        var size = 0
        val order = if (span != null) pool.values.toList() else pool.values.sortedBy { it.start }
        for (l in order) {
            val r = row(l)
            if (size + r.length + 1 > budget) break
            picked += l
            size += r.length + 1
        }
        if (picked.isEmpty()) return "Nothing in the transcript matches that yet."
        val text = picked.sortedBy { it.start }.joinToString("\n") { row(it) }
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.US).format(Date())
        Log.i(ScouterService.TAG, "ask: ${picked.size} lines to ${model.name}")
        return Llm.chat(c, model, SYSTEM, "Now: $now\n\nTranscript lines:\n$text\n\nQuestion: $question",
            maxTokens = if (model.thinks) 1500 else 500, onText = onText)
    }
}

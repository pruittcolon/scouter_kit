package scouterkit.ssh

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Answering from more transcript than a model can read at once. The lines a question needs (a time's lines, or the
// ones search finds with the lines around them) go to the model whole when they fit; otherwise in parts: the quick
// model takes notes from each part ("map"), the notes are combined (again in parts when they are still too long),
// and the answer is written from the notes. A check pass can then read the answer against the evidence and say what
// it changed. Each step reports how far along it is and about how long is left, from the speeds measured on this
// phone (Llm.speed). Vox holds the phone meanwhile (Llm.hold): Listen's audio waits and is written down after.
object Think {
    class Scope(val key: String, val label: String)
    class Step(val label: String, val fraction: Float, val etaMs: Long, val part: Int, val parts: Int)
    class Result(val answer: String, val feedback: String, val notes: List<String>, val lines: Int, val parts: Int,
                 val model: String, val ms: Long, val evidence: String, val stopped: Boolean)

    val SCOPES = listOf(Scope("auto", "Auto"), Scope("today", "Today"), Scope("yesterday", "Yesterday"), Scope("week", "This week"),
        Scope("lastweek", "Last week"), Scope("month", "This month"), Scope("all", "All time"))

    @Volatile private var cancelled = false
    @Volatile private var working: LlmModels.Model? = null

    fun cancel() {
        cancelled = true
        working?.let { Llm.stop(it) }
    }

    // A scope's span (from, to), or null: auto takes the question's own time words.
    fun span(key: String, question: String, now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val day = ListenDb.dayStart(now)
        fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        val weekStart = cal(day).apply { while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        return when (key) {
            "today" -> day to now + 1
            "yesterday" -> cal(day).apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis to day
            "week" -> weekStart to now + 1
            "lastweek" -> cal(weekStart).apply { add(Calendar.DAY_OF_MONTH, -7) }.timeInMillis to weekStart
            "month" -> cal(day).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis to now + 1
            "all" -> 0L to now + 1
            else -> Search.span(question, now)
        }
    }

    // A question about a whole stretch (a summary, every decision...) rather than one thing.
    private val BROAD = Regex("\\b(summar\\w*|recap|overview|everything|all the|what happened|what did (we|i) (do|talk|discuss)\\w*|" +
        "action items?|to.?dos?|tasks?|decisions?|decided|promis\\w*|commit\\w*|follow.?ups?|highlights?|topics?|themes?|" +
        "how was|mood|list)\\b", RegexOption.IGNORE_CASE)

    fun broad(q: String) = BROAD.containsMatchIn(q)

    private const val MAP = "You take notes from one part of a conversation transcript, for a question. Lines are " +
        "[time] who: words; \"You\" is the user. Write up to 8 short bullet points (\"- \") that help answer the " +
        "question, each with its time and who said it. Only what the lines say. If nothing in this part helps, write " +
        "only NONE."
    private const val COMBINE = "You combine notes taken from a transcript into fewer bullet points (\"- \"), keeping " +
        "every fact, time and name that helps answer the question. Drop repeats. Only what the notes say."
    private const val ANSWER = "You answer questions about the user's own conversations (\"You\" is the user), from " +
        "the transcript lines or the notes taken from them that you are given. Answer plainly and directly, then the " +
        "details as short bullet points with who said it and when. If they do not say, answer that they do not. " +
        "Never invent anything."
    private const val CHECK = "You check an answer against its evidence. Write two parts. FEEDBACK: one or two " +
        "sentences on anything in the draft that the evidence does not support, gets wrong or leaves out (or: " +
        "Everything is supported.). ANSWER: the corrected answer (the draft unchanged if nothing was wrong). Use only " +
        "the evidence."

    // Characters for about n tokens (transcript text runs about 3.3 characters a token; a little margin).
    private fun chars(tokens: Int) = tokens * 3
    private fun tokens(chars: Int) = chars / 3 + 1

    fun eta(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(1)
        return when {
            s < 60 -> "about $s s left"
            s < 3600 -> "about ${s / 60} min ${s % 60} s left"
            else -> "about ${s / 3600} h ${(s % 3600) / 60} min left"
        }
    }

    // The answer to a question in a chat (history: the earlier questions and answers, oldest first). deep: the
    // deepest model writes the answer (and checks it); check: a check pass with the quick model too. Null when no
    // model is downloaded.
    fun run(c: Context, question: String, history: List<Pair<String, String>>, scope: String, deep: Boolean, check: Boolean,
            onStep: (Step) -> Unit, onText: (String) -> Unit): Result? {
        val quick = LlmModels.QUICK.takeIf { LlmModels.ready(c, it) } ?: LlmModels.deep(c) ?: return null
        val writer = if (deep) LlmModels.deep(c) ?: quick else quick
        cancelled = false
        val t0 = System.currentTimeMillis()
        return Llm.hold {
            onStep(Step("Finding what to read…", 0f, 0, 0, 0))
            val db = ListenDb.get(c)
            val names = db.names()
            val span = span(scope, question)
            val isBroad = broad(question)
            val sameDay = span != null && ListenDb.dayStart(span.first) == ListenDb.dayStart(span.second - 1)
            val fmt = SimpleDateFormat(if (sameDay) "h:mm a" else "EEE MMM d h:mm a", Locale.US)
            fun row(l: ListenDb.Line) = "[${fmt.format(Date(l.start))}] " + (names[l.person.ifEmpty { l.voice }] ?: "Someone") + ": " + l.text
            // The model's room: its context less the instructions, the chat so far and the answer.
            val past = history.takeLast(3).joinToString("\n") { (q, a) -> "Q: $q\nA: ${a.take(600)}" }
            val answerTokens = if (writer.thinks) 1500 else 600
            val finalBudget = chars(writer.ctx - answerTokens - tokens(past.length) - 500).coerceAtLeast(3000)
            val partBudget = chars(quick.ctx - 300 - 400).coerceAtLeast(3000)
            // The lines.
            val pool: List<ListenDb.Line> = run {
                val inSpan = span?.let { db.between(it.first, it.second).takeLast(60_000) }
                when {
                    inSpan != null && (isBroad || inSpan.sumOf { it.text.length + 30 } <= finalBudget) -> inSpan
                    inSpan != null -> {
                        val found = Search.find(c, question, 80).map { it.line }.filter { it.start in span.first until span.second }
                        if (found.isEmpty()) inSpan else context(db, found)
                    }
                    isBroad -> db.between(ListenDb.dayStart(System.currentTimeMillis()), Long.MAX_VALUE)
                    else -> context(db, Search.find(c, question, 60).map { it.line })
                }
            }.sortedBy { it.start }
            if (pool.isEmpty()) return@hold Result("Nothing in the transcript matches that" + (if (span != null) " in that time" else "") +
                ".", "", emptyList(), 0, 0, writer.name, System.currentTimeMillis() - t0, "", false)
            val rows = pool.map { row(it) }
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.US).format(Date())
            val head = (if (past.isNotEmpty()) "Earlier in this chat:\n$past\n\n" else "") + "Now: $now\n\n"
            var evidence: String
            val notes = ArrayList<String>()
            var parts = 1
            if (rows.sumOf { it.length + 1 } <= finalBudget) {
                evidence = rows.joinToString("\n")
            } else {
                // In parts: the plan and its estimate first, so the bar and the time left mean something.
                val chunks = ArrayList<String>()
                var cur = StringBuilder()
                for (r in rows) {
                    if (cur.length + r.length + 1 > partBudget && cur.isNotEmpty()) {
                        chunks += cur.toString()
                        cur = StringBuilder()
                    }
                    cur.append(r).append('\n')
                }
                if (cur.isNotEmpty()) chunks += cur.toString()
                parts = chunks.size
                val est = chunks.map { Llm.estimateMs(c, quick, tokens(it.length) + 200, 220) }.toMutableList()
                val finalEst = Llm.estimateMs(c, writer, tokens(minOf(finalBudget, parts * 900)), answerTokens / 2)
                var planned = est.sum() + finalEst + (if (check || deep) finalEst else 0)
                var doneEst = 0L
                var doneReal = 0L
                fun left(): Long {
                    val ratio = if (doneEst > 0) doneReal.toDouble() / doneEst else 1.0
                    return ((planned - doneEst) * ratio).toLong()
                }
                for ((i, ch) in chunks.withIndex()) {
                    if (cancelled) break
                    onStep(Step("Reading part ${i + 1} of $parts", doneEst.toFloat() / planned, left(), i + 1, parts))
                    working = quick
                    val s0 = System.currentTimeMillis()
                    val got = Llm.chat(c, quick, MAP, "Question: $question\n\nPart ${i + 1} of $parts:\n$ch",
                        maxTokens = if (quick.thinks) 900 else 260, temp = 0.2f) ?: ""
                    doneReal += System.currentTimeMillis() - s0
                    doneEst += est[i]
                    val t = got.trim()
                    if (t.isNotEmpty() && !t.startsWith("NONE", ignoreCase = true)) notes += t
                }
                // Combined until they fit the answer's room.
                var level = 0
                var joined = notes.joinToString("\n")
                while (!cancelled && joined.length > finalBudget && level < 4) {
                    level++
                    val groups = ArrayList<String>()
                    var g = StringBuilder()
                    for (n in notes) {
                        if (g.length + n.length > partBudget && g.isNotEmpty()) {
                            groups += g.toString()
                            g = StringBuilder()
                        }
                        g.append(n).append('\n')
                    }
                    if (g.isNotEmpty()) groups += g.toString()
                    val more = groups.map { Llm.estimateMs(c, quick, tokens(it.length) + 150, 300) }
                    planned += more.sum()
                    val next = ArrayList<String>()
                    for ((k, grp) in groups.withIndex()) {
                        if (cancelled) break
                        onStep(Step("Combining notes ${k + 1} of ${groups.size}", doneEst.toFloat() / planned, left(), parts, parts))
                        val s0 = System.currentTimeMillis()
                        next += (Llm.chat(c, quick, COMBINE, "Question: $question\n\nNotes:\n$grp", maxTokens = if (quick.thinks) 900 else 320,
                            temp = 0.2f) ?: "").trim()
                        doneReal += System.currentTimeMillis() - s0
                        doneEst += more[k]
                    }
                    notes.clear()
                    notes += next.filter { it.isNotEmpty() }
                    joined = notes.joinToString("\n")
                }
                evidence = joined.take(finalBudget)
                if (!cancelled) onStep(Step("Writing the answer", doneEst.toFloat() / planned, left(), parts, parts))
            }
            if (cancelled) return@hold Result("Stopped.", "", notes, pool.size, parts, writer.name, System.currentTimeMillis() - t0, evidence, true)
            if (parts == 1) onStep(Step("Writing the answer", 0.1f, Llm.estimateMs(c, writer, tokens(evidence.length) + 400, answerTokens / 2), 1, 1))
            working = writer
            val label = if (parts == 1) "Transcript lines (${pool.size}):" else "Notes taken from ${pool.size} transcript lines in $parts parts:"
            var answer = Llm.chat(c, writer, ANSWER, "$head$label\n$evidence\n\nQuestion: $question", maxTokens = answerTokens,
                onText = onText) ?: "The model could not answer (the request was too long)."
            if (answer.isBlank()) answer = "The model returned nothing. Ask again (it moves to the CPU after a GPU failure), or try Deep."
            var feedback = ""
            if ((check || deep) && !cancelled && answer.isNotBlank()) {
                onStep(Step("Double-checking the answer", 0.95f, Llm.estimateMs(c, writer, tokens(evidence.length + answer.length) + 300,
                    answerTokens / 2), parts, parts))
                val r = Llm.chat(c, writer, CHECK, "Question: $question\n\nEvidence:\n${evidence.take(finalBudget - answer.length)}\n\n" +
                    "Draft answer:\n$answer", maxTokens = answerTokens) ?: ""
                val fi = r.indexOf("FEEDBACK:", ignoreCase = true)
                val ai = r.indexOf("ANSWER:", ignoreCase = true)
                if (fi >= 0 && ai > fi) {
                    feedback = r.substring(fi + 9, ai).trim()
                    val fixed = r.substring(ai + 7).trim()
                    if (fixed.length > 20) {
                        answer = fixed
                        onText(answer)
                    }
                }
            }
            working = null
            Log.i(ScouterService.TAG, "think: ${pool.size} lines, $parts parts, ${System.currentTimeMillis() - t0} ms")
            Result(answer, feedback, notes, pool.size, parts, writer.name, System.currentTimeMillis() - t0, evidence, cancelled)
        }
    }

    // A check of an earlier answer, against its evidence: (feedback, answer).
    fun check(c: Context, question: String, answer: String, evidence: String, deep: Boolean, onText: (String) -> Unit): Pair<String, String>? {
        val m = (if (deep) LlmModels.deep(c) else null) ?: LlmModels.QUICK.takeIf { LlmModels.ready(c, it) } ?: LlmModels.deep(c) ?: return null
        return Llm.hold {
            val room = chars(m.ctx - (if (m.thinks) 1500 else 600) - 400) - answer.length
            val r = Llm.chat(c, m, CHECK, "Question: $question\n\nEvidence:\n${evidence.take(room.coerceAtLeast(2000))}\n\nDraft answer:\n$answer",
                maxTokens = if (m.thinks) 1500 else 600) ?: return@hold null
            val fi = r.indexOf("FEEDBACK:", ignoreCase = true)
            val ai = r.indexOf("ANSWER:", ignoreCase = true)
            if (fi < 0 || ai <= fi) return@hold r.trim() to answer
            val fixed = r.substring(ai + 7).trim().ifEmpty { answer }
            onText(fixed)
            r.substring(fi + 9, ai).trim() to fixed
        }
    }

    // Found lines with the two before and after each (the same conversation), in time order, without repeats.
    private fun context(db: ListenDb, found: List<ListenDb.Line>): List<ListenDb.Line> {
        val out = LinkedHashMap<Long, ListenDb.Line>()
        for (l in found.take(30)) for (a in db.around(l.id, 2, 2)) out[a.id] = a
        return out.values.toList()
    }

    // What a span holds, in one line: lines, conversations, talk time, who.
    fun info(c: Context, from: Long, to: Long): String {
        val db = ListenDb.get(c)
        val lines = db.between(from, to)
        if (lines.isEmpty()) return "Nothing heard."
        val st = Words.stats(lines, db.names())
        val convs = db.conversations(from, to).size
        val mins = (st.seconds / 60).toInt()
        val talk = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        val who = st.people.take(4).joinToString(", ") { it.who }
        return "${lines.size} lines · $convs conversations · $talk of talk · $who"
    }
}

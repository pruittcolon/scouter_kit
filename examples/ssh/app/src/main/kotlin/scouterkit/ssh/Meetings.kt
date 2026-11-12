package scouterkit.ssh

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Meetings (Listen mode): a stretch of the day with a title, started by hand (the glasses' Listen panel S, the
// Listen page) or by itself when a calendar event with other people begins. While it runs: bookmarks, action items
// and typed notes at their moment, "catch me up" (the last 5 minutes in three lines), the talk share. When it ends,
// the phone's LFM2.5 writes the notes (summary, decisions, action items, open questions), and Vox saves them with
// the bookmarks, the notes, who talked how much and the whole transcript as "Meeting <date> <time> <title>.md" in
// the docs folder (Alt+N on the glasses).
object Meetings {
    class Current(val id: Long, val title: String, val started: Long, val endsAt: Long, val people: List<String>,
                  val calendar: Boolean)

    @Volatile var current: Current? = null
        private set
    @Volatile var writing = "" // the meeting whose notes are being written ("" none)
    var onDone: ((String) -> Unit)? = null // the glasses: "notes ready"

    private val fmtTime = SimpleDateFormat("h:mm a", Locale.US)

    fun start(c: Context, title: String, endsAt: Long = 0L, people: List<String> = emptyList(), calendar: Boolean = false): Current {
        current?.let { return it }
        val now = System.currentTimeMillis()
        val t = title.ifBlank { "Meeting at " + fmtTime.format(Date(now)) }
        val id = ListenDb.get(c).meetingStart(t, now, people.joinToString(", "))
        val m = Current(id, t, now, endsAt, people, calendar)
        current = m
        Log.i(ScouterService.TAG, "meeting: started" + if (calendar) " (calendar)" else "")
        Listen.looking(120_000)
        return m
    }

    // The meeting ends; its notes are written in the background (onDone says when).
    fun stop(c: Context) {
        val m = current ?: return
        current = null
        val end = System.currentTimeMillis()
        ListenDb.get(c).meetingEnd(m.id, end)
        Log.i(ScouterService.TAG, "meeting: ended after ${(end - m.started) / 60000} min")
        Thread({
            writing = m.title
            val f = runCatching { write(c, m.id) }.getOrElse {
                Log.w(ScouterService.TAG, "meeting: notes failed: ${it.message}")
                null
            }
            writing = ""
            onDone?.invoke(if (f != null) "Meeting notes ready: ${f.name} (Alt+N)" else "Meeting notes could not be written")
        }, "meeting-notes").start()
    }

    fun mark(c: Context, kind: String, text: String) {
        val m = current ?: return
        ListenDb.get(c).addNote(m.id, System.currentTimeMillis(), kind, text)
    }

    // A bookmark: the last 30 s's words kept with it.
    fun bookmark(c: Context) {
        val now = System.currentTimeMillis()
        val said = ListenDb.get(c).between(now - 30_000, now + 1).joinToString(" ") { it.text }
        mark(c, "bookmark", said.ifEmpty { "(nothing said in the last 30 s)" })
    }

    // The talk share and the like so far.
    fun stats(c: Context): Words.Stats? {
        val m = current ?: return null
        val db = ListenDb.get(c)
        return Words.stats(db.between(m.started, System.currentTimeMillis() + 1), db.names())
    }

    private fun rows(c: Context, from: Long, to: Long): List<String> {
        val db = ListenDb.get(c)
        val names = db.names()
        return db.between(from, to).map { "[${fmtTime.format(Date(it.start))}] ${names[it.person.ifEmpty { it.voice }] ?: "Someone"}: ${it.text}" }
    }

    // The last 5 minutes in three short lines (the quick model).
    fun catchUp(c: Context, onText: (String) -> Unit): String? {
        val now = System.currentTimeMillis()
        val lines = rows(c, now - 5 * 60_000, now + 1)
        if (lines.isEmpty()) return "Nothing was said in the last 5 minutes."
        val m = LlmModels.QUICK.takeIf { LlmModels.ready(c, it) } ?: LlmModels.deep(c) ?: return null
        return Llm.chat(c, m, "You catch someone up on a conversation they half missed. At most three short lines: " +
            "what is being discussed, what was just decided or asked, anything asked of \"You\". Names, no filler.",
            lines.takeLast(120).joinToString("\n"), maxTokens = if (m.thinks) 800 else 160, onText = onText)
    }

    // A question about this meeting only (its lines).
    fun ask(c: Context, q: String, onText: (String) -> Unit): String? {
        val m = current ?: return Search.ask(c, q, false, onText)
        val model = LlmModels.QUICK.takeIf { LlmModels.ready(c, it) } ?: LlmModels.deep(c) ?: return null
        val lines = rows(c, m.started, System.currentTimeMillis() + 1)
        val budget = model.ctx * 2 / 3 * 3
        val picked = ArrayList<String>()
        var size = 0
        for (l in lines.reversed()) {
            if (size + l.length > budget) break
            picked += l
            size += l.length + 1
        }
        return Llm.chat(c, model, "You answer questions about the meeting in progress from its transcript lines. Brief, " +
            "plain, with names and times; if the lines do not say, say so.", "Meeting: ${m.title}\n\n" +
            picked.reversed().joinToString("\n") + "\n\nQuestion: $q", maxTokens = if (model.thinks) 1200 else 400, onText = onText)
    }

    // ------------------------------------------------ the notes

    private const val NOTES = "You write meeting notes from a transcript (lines: [time] who: words; \"You\" is the " +
        "user). Write exactly these sections, plain text, no other text:\nSUMMARY: 3-6 sentences.\nDECISIONS: one per " +
        "line starting with \"- \", or \"- none\".\nACTION ITEMS: one per line, \"- who: what (by when, if said)\", or " +
        "\"- none\".\nOPEN QUESTIONS: one per line starting with \"- \", or \"- none\".\nOnly what the lines say; never invent."

    private const val PART = "Summarize this part of a meeting transcript for later notes: the points discussed, " +
        "decisions, action items (who, what, when) and open questions, as short lines. Only what the lines say."

    // The notes of a meeting: the model's sections (in parts first when the meeting is longer than its context),
    // then the file. The file, or null.
    fun write(c: Context, id: Long): File? = Llm.hold { writeHeld(c, id) }

    private fun writeHeld(c: Context, id: Long): File? {
        val db = ListenDb.get(c)
        val m = db.meeting(id) ?: return null
        val end = if (m.ended > 0) m.ended else System.currentTimeMillis()
        val lines = db.between(m.started, end + 1)
        val names = db.names()
        val rows = lines.map { "[${fmtTime.format(Date(it.start))}] ${names[it.person.ifEmpty { it.voice }] ?: "Someone"}: ${it.text}" }
        val model = LlmModels.deep(c)
        var sections = ""
        if (model != null && rows.isNotEmpty() && LlmModels.usable()) {
            val budget = model.ctx / 2 * 3 // characters per call
            val text = rows.joinToString("\n")
            sections = if (text.length <= budget) Llm.chat(c, model, NOTES, "Meeting: ${m.title}\n\n$text",
                maxTokens = if (model.thinks) 2500 else 900) ?: ""
            else {
                // Long: each part summarized, then the notes from the parts.
                val parts = ArrayList<String>()
                var cur = StringBuilder()
                for (r in rows) {
                    if (cur.length + r.length > budget) {
                        parts += cur.toString()
                        cur = StringBuilder()
                    }
                    cur.append(r).append('\n')
                }
                if (cur.isNotEmpty()) parts += cur.toString()
                val sums = parts.mapIndexed { i, p ->
                    Llm.chat(c, model, PART, "Part ${i + 1} of ${parts.size}:\n$p", maxTokens = if (model.thinks) 1500 else 500) ?: ""
                }
                Llm.chat(c, model, NOTES, "Meeting: ${m.title}\n\nNotes of its parts, in order:\n" + sums.joinToString("\n\n"),
                    maxTokens = if (model.thinks) 2500 else 900) ?: ""
            }
        }
        val (base, baseN) = Words.baseline(c)
        val stats = Words.stats(lines, names, base, baseN)
        val day = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        val sb = StringBuilder()
        sb.append("# ${m.title}\n\n")
        sb.append(day.format(Date(m.started))).append(", ").append(fmtTime.format(Date(m.started))).append(" - ")
            .append(fmtTime.format(Date(end))).append(" (").append((end - m.started) / 60000).append(" min)")
        val who = stats.people.joinToString(", ") { it.who }
        if (who.isNotEmpty()) sb.append(" · ").append(who)
        sb.append("\n\n")
        if (sections.isNotBlank()) sb.append(sections.trim()).append("\n\n")
        else sb.append(if (model == null) "(No language model downloaded: Listen page, AI models. The transcript is below.)\n\n"
            else "(The model wrote nothing. The transcript is below.)\n\n")
        val notes = db.notes(m.id)
        if (notes.isNotEmpty()) {
            sb.append("BOOKMARKS AND NOTES:\n")
            for ((at, kind, text) in notes) sb.append("- ${fmtTime.format(Date(at))} ").append(when (kind) {
                "bookmark" -> "bookmark: \"$text\""
                "action" -> "action item: $text"
                else -> "note: $text"
            }).append("\n")
            sb.append("\n")
        }
        sb.append("WHO TALKED:\n").append(Words.report(stats)).append("\n")
        sb.append("TRANSCRIPT:\n")
        for (l in lines) {
            sb.append(SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(l.start))).append("  ")
            names[l.person.ifEmpty { l.voice }]?.let { sb.append(it).append(": ") }
            sb.append(l.text)
            if (l.emotion.isNotEmpty()) sb.append(" [").append(l.emotion).append("]")
            if (l.sound.isNotEmpty()) sb.append(" [").append(l.sound).append("]")
            sb.append("\n")
        }
        val safe = m.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(40).ifEmpty { "Meeting" }
        val f = File(Transcripts.dir(c), "Meeting " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(m.started)) + " $safe.md")
        f.parentFile?.mkdirs()
        f.writeText(sb.toString())
        db.meetingSummary(m.id, sections, f.name)
        return f
    }

    // ------------------------------------------------ the calendar

    // An event happening now (not all day) with other people invited: its title, end and the people's names.
    private class Event(val title: String, val end: Long, val people: List<String>)

    private fun now(c: Context): Event? {
        if (!Agenda.allowed(c)) return null
        val t = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, t - 60_000)
        ContentUris.appendId(b, t + 60_000)
        runCatching {
            c.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.VISIBLE} = 1", null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(4) == 1) continue
                    val begin = cur.getLong(2)
                    val end = cur.getLong(3)
                    if (t < begin || t >= end) continue
                    val people = ArrayList<String>()
                    CalendarContract.Attendees.query(c.contentResolver, cur.getLong(0), arrayOf(CalendarContract.Attendees.ATTENDEE_NAME,
                        CalendarContract.Attendees.ATTENDEE_EMAIL))?.use { a ->
                        while (a.moveToNext()) people += (a.getString(0)?.takeIf { it.isNotBlank() } ?: a.getString(1)?.substringBefore('@') ?: "")
                    }
                    if (people.filter { it.isNotBlank() }.size >= 2) return Event(cur.getString(1) ?: "", end, people.filter { it.isNotBlank() })
                }
            }
        }
        return null
    }

    @Volatile private var lastTick = 0L

    // Each minute (Listen's worker): a calendar meeting starts or ends by itself.
    fun tick(c: Context) {
        val t = System.currentTimeMillis()
        if (t - lastTick < 60_000) return
        lastTick = t
        if (!Settings(c).listenMeetingsAuto || !Listen.on) return
        val cur = current
        if (cur == null) now(c)?.let { e -> start(c, e.title, e.end, e.people, calendar = true) }
        else if (cur.calendar && cur.endsAt > 0 && t > cur.endsAt + 5 * 60_000) stop(c)
    }
}

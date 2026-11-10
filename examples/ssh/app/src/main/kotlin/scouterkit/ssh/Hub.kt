package scouterkit.ssh

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

// The hub (Alt+G): the everyday tools on the glasses, from the keyboard: a timer (Timer), a stopwatch, an alarm in the
// phone's clock app, reminders, notes. Notes and reminders stay in Vox's private settings ("hub").
object Hub {
    class Reminder(val at: Long, val text: String)

    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    // ------------------------------------------------ notes (newest first, at most 50)

    fun notes(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("notes", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun addNote(c: Context, text: String) = saveNotes(c, (listOf(text.trim()) + notes(c)).take(50))

    // The newest note taken back; it, or null with none.
    fun dropNote(c: Context): String? = notes(c).firstOrNull()?.also { saveNotes(c, notes(c).drop(1)) }

    private fun saveNotes(c: Context, list: List<String>) =
        prefs(c).edit().putString("notes", JSONArray(list).toString()).apply()

    // ------------------------------------------------ reminders (soonest first)

    fun reminders(c: Context): List<Reminder> = runCatching {
        val a = JSONArray(prefs(c).getString("reminders", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Reminder(o.getLong("at"), o.getString("text")) } }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    fun addReminder(c: Context, at: Long, text: String) = saveReminders(c, reminders(c) + Reminder(at, text.trim()))

    // The soonest one taken back; it, or null with none.
    fun dropReminder(c: Context): Reminder? = reminders(c).firstOrNull()?.also { saveReminders(c, reminders(c).drop(1)) }

    // The ones due by `now` (wall clock), taken off the list.
    fun due(c: Context, now: Long = System.currentTimeMillis()): List<Reminder> {
        val all = reminders(c)
        val due = all.filter { it.at <= now }
        if (due.isNotEmpty()) saveReminders(c, all.filter { it.at > now })
        return due
    }

    private fun saveReminders(c: Context, list: List<Reminder>) {
        val a = JSONArray()
        for (r in list.sortedBy { it.at }) a.put(JSONObject().put("at", r.at).put("text", r.text))
        prefs(c).edit().putString("reminders", a.toString()).apply()
    }

    // ------------------------------------------------ the stopwatch

    @Volatile private var since = 0L // elapsedRealtime when it started running, 0: stopped
    @Volatile private var kept = 0L  // ms counted before
    val stopwatchRunning get() = since > 0
    fun stopwatch(): Long = kept + if (since > 0) SystemClock.elapsedRealtime() - since else 0L
    fun stopwatchToggle() {
        if (since > 0) {
            kept += SystemClock.elapsedRealtime() - since
            since = 0L
        } else since = SystemClock.elapsedRealtime()
    }
    fun stopwatchReset() {
        since = 0L
        kept = 0L
    }

    // "0:12.3", "4:05.0", "1:02:03".
    fun stopwatchText(ms: Long = stopwatch()): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, ms / 100 % 10)
    }

    // ------------------------------------------------ what is typed

    // A timer's length: "5" (minutes), "90s", "1:30" (minutes and seconds), "1h", "1h30", "45m". Null: not one.
    fun duration(text: String): Long? {
        val t = text.trim().lowercase(Locale.US).replace(" ", "")
        if (t.isEmpty()) return null
        Regex("^(\\d+):(\\d{1,2})$").find(t)?.let { m -> return (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 }
        if (!Regex("^(\\d+[hms]?)+$").matches(t)) return null
        var total = 0L
        var last = ' '
        for (m in Regex("(\\d+)([hms]?)").findAll(t)) {
            // A bare number is minutes, or the next unit down after one with a unit ("1h30", "2m30").
            val unit = m.groupValues[2].firstOrNull() ?: when (last) { 'h' -> 'm'; 'm' -> 's'; else -> 'm' }
            total += m.groupValues[1].toLong() * when (unit) { 'h' -> 3600L; 'm' -> 60L; else -> 1L }
            last = unit
        }
        return total * 1000
    }

    // A clock time: "7", "730", "7:30", "7:30pm", "7p", "19:30". Hour 0..23 and minute; pm: null when not said.
    private class Clock(val hour: Int, val minute: Int, val pm: Boolean?)

    private fun clock(text: String): Clock? {
        val m = Regex("^(\\d{1,2})(?::?(\\d{2}))?\\s*(a|am|p|pm)?$").find(text.trim().lowercase(Locale.US)) ?: return null
        var h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
        val suffix = m.groupValues[3]
        if (h > 23 || mi > 59) return null
        val pm = when {
            suffix.startsWith("p") -> true
            suffix.startsWith("a") -> false
            else -> null
        }
        if (pm != null) {
            if (h !in 1..12) return null
            h = h % 12 + if (pm) 12 else 0
        }
        return Clock(h, mi, pm)
    }

    // An alarm's time: hour 0..23 and minute ("7:30" is in the morning, "7:30p" or "19:30" in the evening).
    fun alarm(text: String): Pair<Int, Int>? = clock(text)?.let { it.hour to it.minute }

    // A reminder: "20 call mom" (in 20 minutes), "1h30 laundry", "7:30 call mom" or "7:30pm …" (the next such time;
    // without am / pm, whichever comes first). The time (wall clock) and the words, or null.
    fun reminder(text: String, now: Long = System.currentTimeMillis()): Pair<Long, String>? {
        val parts = text.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return null
        val first = parts[0]
        val words = parts[1].trim()
        if (first.contains(':') || Regex("\\d(a|am|p|pm)$", RegexOption.IGNORE_CASE).containsMatchIn(first)) {
            val c = clock(first) ?: return null
            fun next(hour: Int): Long = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, c.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            val at = if (c.pm == null && c.hour in 1..11) minOf(next(c.hour), next(c.hour + 12)) else next(c.hour)
            return at to words
        }
        val d = duration(first) ?: return null
        if (d <= 0) return null
        return now + d to words
    }

    // "4:05 PM", "Tue 7:30 AM" (another day).
    fun whenText(at: Long, now: Long = System.currentTimeMillis()): String {
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val time = String.format(Locale.US, "%d:%02d %s", (a.get(Calendar.HOUR) + 11) % 12 + 1, a.get(Calendar.MINUTE),
            if (a.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
        val sameDay = a.get(Calendar.YEAR) == n.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) time else a.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) + " " + time
    }

    fun clockText(hour: Int, minute: Int) =
        String.format(Locale.US, "%d:%02d %s", (hour + 11) % 12 + 1, minute, if (hour < 12) "AM" else "PM")
}

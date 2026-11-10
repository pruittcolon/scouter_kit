package scouterkit.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Automations (Listen mode): rules that run on each line the moment it is written. A rule matches words (any of
// its phrases, as whole words; "*" any line), who said it (anyone, me, not me, or a name) and how (an emotion),
// then does any of: show it on the glasses, a phone notification, a buzz, a hub note, a reminder ("remind me to
// call mom in 20 minutes"), ask the phone's LFM what follows the phrase and show the answer ("hey vox, what did
// Maria say about the trip?"), a Vox command ("lights off", "timer 5 minutes"), a webhook (JSON POST). Each rule
// rests a while after it fires. With a rule on, Listen reads phrases at once (Live) so the rules are timely.
object Automations {
    class Rule(var id: String, var name: String, var on: Boolean, var words: String, var who: String, var mood: String,
               var actions: MutableSet<String>, var url: String, var command: String, var restS: Int) {
        @Volatile var lastAt = 0L

        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("on", on).put("words", words).put("who", who)
            .put("mood", mood).put("actions", JSONArray(actions.toList())).put("url", url).put("command", command).put("rest", restS)

        fun summary(): String {
            val w = if (words.trim() == "*") "any line" else "\"$words\""
            val by = when (who.lowercase(Locale.US)) { "", "anyone" -> ""; else -> " by $who" }
            val how = if (mood.isNotEmpty()) " sounding $mood" else ""
            return "$w$by$how → " + actions.joinToString(", ") { ACTIONS[it] ?: it }
        }

        companion object {
            fun of(o: JSONObject) = Rule(o.optString("id"), o.optString("name"), o.optBoolean("on"), o.optString("words"),
                o.optString("who"), o.optString("mood"), o.optJSONArray("actions")?.let { a -> MutableList(a.length()) { a.getString(it) }.toMutableSet() }
                    ?: mutableSetOf(), o.optString("url"), o.optString("command"), o.optInt("rest", 30))
        }
    }

    val ACTIONS = linkedMapOf("glasses" to "show on the glasses", "notify" to "phone notification", "buzz" to "buzz the phone",
        "note" to "hub note", "remind" to "reminder", "ask" to "ask the LFM", "command" to "Vox command", "webhook" to "webhook")

    private fun prefs(c: Context) = c.getSharedPreferences("automations", Context.MODE_PRIVATE)

    @Volatile private var cache: List<Rule>? = null

    // The rules; the first time, a few ready ones (two on: "hey vox" questions and "remind me").
    fun rules(c: Context): List<Rule> {
        cache?.let { return it }
        val raw = prefs(c).getString("rules", null)
        val list = if (raw == null) defaults() else runCatching {
            val a = JSONArray(raw)
            List(a.length()) { Rule.of(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (raw == null) save(c, list)
        cache = list
        return list
    }

    fun save(c: Context, list: List<Rule>) {
        prefs(c).edit().putString("rules", JSONArray(list.map { it.json() }).toString()).apply()
        cache = list
    }

    fun anyOn(c: Context) = rules(c).any { it.on }

    private fun defaults() = listOf(
        Rule("heyvox", "Hey Vox", true, "hey vox, okay vox, ok vox", "me", "", mutableSetOf("ask", "glasses"), "", "", 5),
        Rule("remind", "Remind me", true, "remind me", "me", "", mutableSetOf("remind", "glasses"), "", "", 5),
        Rule("asked", "Things asked of me", false, "can you, could you, would you, please send, don't forget", "not me", "",
            mutableSetOf("glasses", "note"), "", "", 20),
        Rule("tense", "Raised voices", false, "*", "", "angry", mutableSetOf("glasses", "buzz"), "", "", 120),
        Rule("name", "My name", false, "", "not me", "", mutableSetOf("glasses", "buzz"), "", "", 30),
    )

    fun norm(s: String) = " " + s.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim() + " "

    // The phrase of the rule found in the line (" hey vox "), or null.
    fun match(r: Rule, text: String, who: String, person: String, emotion: String): String? {
        val w = r.who.trim().lowercase(Locale.US)
        val me = person == Speakers.ME
        when (w) {
            "", "anyone" -> {}
            "me" -> if (!me) return null
            "not me" -> if (me) return null
            else -> if (!who.equals(r.who.trim(), true)) return null
        }
        if (r.mood.isNotEmpty() && r.mood != emotion) return null
        if (r.words.trim() == "*") return ""
        val t = norm(text)
        for (p in r.words.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val n = norm(p)
            if (n.isNotBlank() && t.contains(n)) return n.trim()
        }
        return null
    }

    // A line just written: every rule that matches fires (unless resting). The rules fired.
    fun run(c: Context, text: String, who: String, person: String, emotion: String, at: Long): List<String> {
        val fired = ArrayList<String>()
        for (r in rules(c)) {
            if (!r.on) continue
            val phrase = match(r, text, who, person, emotion) ?: continue
            val now = System.currentTimeMillis()
            if (now - r.lastAt < r.restS * 1000L) continue
            r.lastAt = now
            fired += r.name
            Log.i(ScouterService.TAG, "automation: ${r.name}")
            Thread({ runCatching { fire(c, r, text, who, phrase, at) }.onFailure {
                Log.w(ScouterService.TAG, "automation ${r.name}: ${it.message}")
            } }, "automation").start()
        }
        return fired
    }

    // What follows the rule's phrase in the line ("hey vox, what time ..." -> "what time ...").
    private fun after(text: String, phrase: String): String {
        if (phrase.isEmpty()) return text
        val i = norm(text).indexOf(" $phrase ")
        if (i < 0) return text
        // Map back roughly: the words after the phrase's words.
        val words = text.trim().split(Regex("\\s+"))
        val skip = norm(text).substring(0, i + phrase.length + 1).trim().split(" ").size
        return words.drop(skip).joinToString(" ").trim(' ', ',', '.', '!', '?').ifEmpty { text }
    }

    private fun fire(c: Context, r: Rule, text: String, who: String, phrase: String, at: Long) {
        val line = (if (who.isNotEmpty()) "$who: " else "") + text
        for (a in r.actions) when (a) {
            "glasses" -> if (!r.actions.contains("ask")) glasses(c, "${r.name} › $line")
            "notify" -> notify(c, r.name, line)
            "buzz" -> c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            "note" -> Hub.addNote(c, line.take(200))
            "remind" -> reminder(after(text, phrase))?.let { (atMs, what) ->
                Hub.addReminder(c, atMs, what)
                glasses(c, "Reminder set: $what at ${Hub.whenText(atMs)}")
            } ?: glasses(c, "Remind me: say \"remind me to … in 20 minutes\" or \"… at 7:30\"")
            "ask" -> {
                val q = after(text, phrase)
                glasses(c, "Vox › $q …")
                val answer = runCatching { Search.ask(c, q, false) }.getOrNull()
                    ?: "no language model yet (Listen page, AI models)"
                glasses(c, "Vox › $answer", 12_000)
                if (r.actions.contains("notify")) notify(c, "Vox: $q", answer)
            }
            "command" -> if (r.command.isNotBlank() && ScouterService.running)
                ScouterService.send(c, ScouterService.ACTION_VOICE, "mode" to "do:${r.command.trim()}")
            "webhook" -> if (r.url.startsWith("http")) post(r.url, JSONObject().put("rule", r.name).put("text", text)
                .put("who", who).put("at", at).put("phrase", phrase))
        }
    }

    // "remind me to call mom in 20 minutes", "remind me in an hour to stretch", "remind me at 7:30 to leave".
    fun reminder(text: String, now: Long = System.currentTimeMillis()): Pair<Long, String>? {
        val t = text.lowercase(Locale.US).trim().trim('.', '!', '?')
        val nums = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10,
            "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "sixty" to 60, "half an" to 30)
        fun n(s: String) = s.toIntOrNull() ?: nums[s]
        val inRe = Regex("in (\\d+|a|an|one|two|three|four|five|ten|fifteen|twenty|thirty|forty five|forty|sixty|half an) (minutes|minute|mins|min|hours|hour)\\b")
        inRe.find(t)?.let { m ->
            val half = m.groupValues[1] == "half an"
            val k = if (half) 30 else n(m.groupValues[1]) ?: return null
            val unit = if (!half && m.groupValues[2].startsWith("h")) 3_600_000L else 60_000L
            val what = t.replace(m.value, "").replace(Regex("^(to |that )"), "").replace(Regex("\\s+"), " ").trim(' ', ',')
            return now + k * unit to what.removePrefix("to ").ifEmpty { "reminder" }
        }
        Regex("at (\\d{1,2})(?::(\\d{2}))? ?(am|pm|a m|p m)?").find(t)?.let { m ->
            val clock = m.groupValues[1] + ":" + m.groupValues[2].ifEmpty { "00" } +
                m.groupValues[3].replace(" ", "")
            val what = t.replace(m.value, "").replace(Regex("^(to |that )"), "").replace(Regex("\\s+"), " ").trim(' ', ',')
            return Hub.reminder("$clock ${what.removePrefix("to ").ifEmpty { "reminder" }}", now)
        }
        return null
    }

    private fun glasses(c: Context, text: String, ms: Int = 6000) {
        if (ScouterService.running) ScouterService.send(c, ScouterService.ACTION_PANEL, "do" to "lnote:$ms:$text")
    }

    private fun notify(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("automations") == null)
            nm.createNotificationChannel(NotificationChannel("automations", "Listen automations", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(c, "automations").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        runCatching { nm.notify(7400 + (title.hashCode() and 0xFF), n) }
    }

    private fun post(url: String, body: JSONObject) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            Log.i(ScouterService.TAG, "automation: webhook ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }
}

package scouterkit.ssh

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.CalendarContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// The look-up dashboard's weather: Open-Meteo (no account, no key) for the city in Settings.weatherCity, looked up
// once (its geocoding) and asked every 30 minutes, off the caller's thread.
object Weather {
    class Now(val temp: Int, val code: Int, val high: Int, val low: Int, val place: String, val unit: String)

    @Volatile var now: Now? = null
    @Volatile var problem = "" // why there is none (no city, not found, no network)
    @Volatile private var fetchedAt = 0L
    @Volatile private var fetching = false

    // Asked again when 30 minutes have gone (or force); done: on the asking thread, after a change.
    fun refresh(settings: Settings, force: Boolean = false, done: () -> Unit = {}) {
        val city = settings.weatherCity.trim()
        if (city.isEmpty()) {
            now = null
            problem = "Set a city (Terminal tab, Look-up screen)"
            return
        }
        if (fetching || (!force && SystemClock.elapsedRealtime() - fetchedAt < 30 * 60_000L && now != null)) return
        fetching = true
        Thread {
            runCatching {
                var place = settings.weatherPlace
                if (!place.startsWith("$city|")) { // the city's place, once
                    val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"))
                    val r = g.optJSONArray("results")?.optJSONObject(0) ?: throw IllegalStateException("\"$city\" was not found")
                    place = "$city|${r.getDouble("latitude")}|${r.getDouble("longitude")}|${r.optString("name", city)}"
                    settings.weatherPlace = place
                }
                val (_, lat, lon, name) = place.split("|", limit = 4)
                val unit = settings.weatherUnit.ifEmpty { if (Locale.getDefault().country in listOf("US", "LR", "MM")) "f" else "c" }
                val f = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
                    if (unit == "f") "&temperature_unit=fahrenheit" else "")
                val cur = f.getJSONObject("current")
                val daily = f.getJSONObject("daily")
                now = Now(Math.round(cur.getDouble("temperature_2m")).toInt(), cur.optInt("weather_code"),
                    Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(), name, unit)
                problem = ""
                fetchedAt = SystemClock.elapsedRealtime()
            }.onFailure { problem = it.message ?: "no weather" }
            fetching = false
            done()
        }.start()
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        try {
            if (c.responseCode != 200) throw IllegalStateException("the weather service said ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    // WMO weather codes: a word and an icon (Dashboard draws it).
    fun word(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Storm"
        else -> "—"
    }

    fun icon(code: Int) = when (code) {
        0 -> "sun"
        1, 2 -> "partly"
        3 -> "cloud"
        45, 48 -> "fog"
        in 51..67, 80, 81, 82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "cloud"
    }
}

// The look-up dashboard's next events: the phone's calendars (READ_CALENDAR), the next 36 hours, asked at most once
// a minute.
object Agenda {
    class Event(val begin: Long, val title: String, val allDay: Boolean)

    @Volatile private var cached: List<Event> = emptyList()
    @Volatile private var askedAt = 0L

    fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun next(c: Context): List<Event> {
        if (!allowed(c)) return emptyList()
        val t = SystemClock.elapsedRealtime()
        if (t - askedAt < 60_000L && askedAt > 0) return cached.filter { it.allDay || it.begin > System.currentTimeMillis() - 30 * 60_000L }
        askedAt = t
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 30 * 60_000L) // what began in the last half hour still shows
        ContentUris.appendId(b, now + 36 * 3_600_000L)
        val out = mutableListOf<Event>()
        runCatching {
            c.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY), "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext() && out.size < 6)
                    out += Event(cur.getLong(0), cur.getString(1) ?: "", cur.getInt(2) == 1)
            }
        }
        cached = out
        return out
    }
}

// The look-up dashboard's timer: a countdown that the pointer (−1 min, start / pause, +1 min; a right click resets
// it) and voice ("timer 5 minutes") set; when it ends the glasses say so.
object Timer {
    @Volatile private var endsAt = 0L // while it runs: elapsedRealtime at the end
    @Volatile private var left = 0L   // while it is paused (or not started): what is left, ms
    @Volatile var total = 0L          // what it was set to, ms
    @Volatile private var rang = true

    val running get() = endsAt > 0
    fun remaining(): Long = if (endsAt > 0) maxOf(0L, endsAt - SystemClock.elapsedRealtime()) else left

    fun set(ms: Long, start: Boolean = true) {
        total = ms.coerceIn(0L, 24 * 3_600_000L)
        left = total
        endsAt = 0L
        rang = false
        if (start && total > 0) toggle()
    }

    fun toggle() {
        if (endsAt > 0) {
            left = remaining()
            endsAt = 0L
        } else {
            if (left <= 0L) left = if (total > 0) total else 5 * 60_000L
            if (total <= 0L) total = left
            endsAt = SystemClock.elapsedRealtime() + left
            rang = false
        }
    }

    fun add(ms: Long) {
        if (endsAt > 0) endsAt = maxOf(SystemClock.elapsedRealtime(), endsAt + ms)
        else left = maxOf(0L, left + ms)
        total = maxOf(total + ms, remaining())
        rang = false
    }

    fun reset() {
        endsAt = 0L
        left = 0L
        total = 0L
        rang = true
    }

    // True once, when a running countdown has just ended.
    fun ended(): Boolean {
        if (endsAt == 0L || rang || remaining() > 0L) return false
        rang = true
        endsAt = 0L
        left = 0L
        return true
    }

    // "4:59", "1:02:03".
    fun text(ms: Long = remaining()): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

// The dashboard's game score (Dashboard.SPORTS): ESPN's public scoreboard (no account, no key) for the NFL team in
// Settings.sportsTeam (its letters: NE). This week's game of that team from the league's scoreboard (live: the score,
// quarter and clock, down and distance, who has the ball, the red zone), else its next game from the team's page.
// Asked every 50 s while the game is on or within half an hour of its kickoff, else every 15 minutes, off the
// caller's thread. Each team's logo is fetched once and made to show on the glasses: lit where the logo is, by its
// brightness, so a navy logo isn't lost on their black.
object Sports {
    class Side(val abbr: String, val name: String, val score: String, val record: String, val home: Boolean, val logo: Bitmap?)
    class Game(val state: String, val clock: String, val us: Side, val them: Side, val down: String, val ball: String,
               val redZone: Boolean, val tv: String, val start: Long, val lastPlay: String) {
        val away get() = if (us.home) them else us
        val home get() = if (us.home) us else them
    }

    @Volatile var game: Game? = null
    @Volatile var problem = ""
    @Volatile private var fetchedAt = 0L
    @Volatile private var fetchedFor = ""
    @Volatile private var fetching = false
    private val logos = HashMap<String, Bitmap?>()
    private const val API = "https://site.api.espn.com/apis/site/v2/sports/football/nfl"

    fun team(settings: Settings) = settings.sportsTeam.trim().uppercase(Locale.US).ifEmpty { "NE" }

    fun refresh(context: Context, settings: Settings, force: Boolean = false, done: () -> Unit = {}) {
        val team = team(settings)
        val g = game
        val soon = g != null && (g.state == "in" || (g.state == "pre" && g.start - System.currentTimeMillis() < 30 * 60_000L))
        val every = when {
            soon -> 50_000L
            g == null -> 2 * 60_000L
            else -> 15 * 60_000L
        }
        if (fetching || (!force && fetchedFor == team && SystemClock.elapsedRealtime() - fetchedAt < every)) return
        fetching = true
        Thread {
            runCatching {
                var ev = find(get("$API/scoreboard").optJSONArray("events"), team)
                if (ev == null) ev = get("$API/teams/${team.lowercase(Locale.US)}").optJSONObject("team")
                    ?.optJSONArray("nextEvent")?.optJSONObject(0)
                game = parse(context, ev ?: throw IllegalStateException("no game found for $team"), team)
                problem = ""
            }.onFailure {
                problem = it.message ?: "no score"
                if (fetchedFor != team) game = null
            }
            fetchedAt = SystemClock.elapsedRealtime()
            fetchedFor = team
            fetching = false
            done()
        }.start()
    }

    private fun find(events: org.json.JSONArray?, team: String): JSONObject? {
        if (events == null) return null
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val cs = e.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: continue
            for (j in 0 until cs.length())
                if (cs.optJSONObject(j)?.optJSONObject("team")?.optString("abbreviation").equals(team, true)) return e
        }
        return null
    }

    private fun parse(context: Context, ev: JSONObject, team: String): Game {
        val comp = ev.getJSONArray("competitions").getJSONObject(0)
        val status = comp.optJSONObject("status") ?: ev.optJSONObject("status") ?: JSONObject()
        val type = status.optJSONObject("type") ?: JSONObject()
        val state = type.optString("state", "pre")
        val start = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mmX", Locale.US).parse(ev.optString("date"))?.time
        }.getOrNull() ?: 0L
        val cs = comp.getJSONArray("competitors")
        val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
        val ids = HashMap<String, String>()
        fun side(c: JSONObject): Side {
            val t = c.optJSONObject("team") ?: JSONObject()
            val abbr = t.optString("abbreviation")
            ids[t.optString("id")] = abbr
            val score = c.opt("score").let { if (it is JSONObject) it.optString("displayValue") else it?.toString() ?: "" }
            val record = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary")
                ?: c.optJSONArray("record")?.optJSONObject(0)?.optString("displayValue") ?: ""
            val url = t.optString("logo").ifEmpty { t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: "" }
            return Side(abbr, t.optString("shortDisplayName").ifEmpty { t.optString("displayName") }, score, record,
                c.optString("homeAway") == "home", logo(context, abbr, url))
        }
        val all = sides.map { side(it) }
        val us = all.firstOrNull { it.abbr.equals(team, true) } ?: all.first()
        val them = all.firstOrNull { it !== us } ?: us
        val sit = comp.optJSONObject("situation")
        val clock = when (state) {
            "in" -> {
                val period = status.optInt("period")
                when {
                    type.optString("name").contains("HALFTIME") -> "HALFTIME"
                    type.optString("name").contains("END_PERIOD") -> "END Q$period"
                    period > 4 -> "OT ${status.optString("displayClock")}"
                    period > 0 -> "Q$period ${status.optString("displayClock")}"
                    else -> type.optString("shortDetail")
                }
            }
            "post" -> type.optString("shortDetail").ifEmpty { "Final" }.uppercase(Locale.US)
            else -> kickoff(start)
        }
        val tv = comp.optJSONArray("broadcasts")?.let { b ->
            (0 until b.length()).mapNotNull { i ->
                b.optJSONObject(i)?.let { o -> o.optJSONArray("names")?.optString(0) ?: o.optJSONObject("media")?.optString("shortName") }
            }.firstOrNull { it.isNotEmpty() }
        } ?: ""
        return Game(state, clock, us, them, sit?.optString("downDistanceText")?.takeIf { it != "null" } ?: "",
            ids[sit?.optString("possession") ?: ""] ?: "", sit?.optBoolean("isRedZone") ?: false, tv, start,
            sit?.optJSONObject("lastPlay")?.optString("text") ?: "")
    }

    // "TODAY 4:25 PM", "SUN 1:00 PM" (this week), "NOV 15 1:00 PM".
    fun kickoff(at: Long): String {
        if (at <= 0) return "SCHEDULED"
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        val days = ((at - now.timeInMillis) / 86_400_000L)
        val day = when {
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
            days in 0..5 -> java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(at))
            else -> java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(at))
        }
        return "$day $time".uppercase(Locale.getDefault())
    }

    // A team's logo, small and lit for the glasses; kept (memory and the app's cache) once fetched.
    private fun logo(context: Context, abbr: String, url: String): Bitmap? {
        synchronized(logos) { if (logos.containsKey(abbr)) return logos[abbr] }
        val file = java.io.File(context.cacheDir, "logos/$abbr.png")
        val raw = runCatching {
            if (file.length() > 0) BitmapFactory.decodeFile(file.path) else {
                if (url.isEmpty()) return@runCatching null
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val bytes = try { c.inputStream.readBytes() } finally { c.disconnect() }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
        val lit = raw?.let { lit(it) }
        synchronized(logos) { logos[abbr] = lit }
        return lit
    }

    // Grey by where the logo is (alpha) and how bright its colour is, never darker than about a third.
    private fun lit(src: Bitmap): Bitmap {
        val n = 64
        val small = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c) / 255f
            val v = maxOf(Color.red(c), Color.green(c), Color.blue(c)) / 255f
            val g = (a * (0.38f + 0.62f * v) * 255).toInt().coerceIn(0, 255)
            px[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        try {
            if (c.responseCode != 200) throw IllegalStateException("the scores service said ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}

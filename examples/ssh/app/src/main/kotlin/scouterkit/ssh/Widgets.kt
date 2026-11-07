package scouterkit.ssh

import android.content.ContentUris
import android.content.Context
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

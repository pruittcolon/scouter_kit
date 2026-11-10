package scouterkit.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

// Smart home (Alt+O): what the glasses can work on the home network, on one panel. The paired Vizio (Vizio.kt);
// Roku TVs over ECP (HTTP port 8060 on the TV, no pairing: Settings, System, Advanced, Control by mobile apps on),
// found by SSDP; and Home Assistant's entities (its address, a long-lived access token and the entities in
// Vox's Settings): lights, switches, fans, media players, thermostats. Each call blocks: off the main thread.
object Home {
    // kind: "vizio", "roku" or "ha"; id: the Roku's address or the entity's id.
    class Device(val kind: String, val id: String, val name: String)

    fun devices(c: Context): List<Device> {
        val s = Settings(c)
        val out = mutableListOf<Device>()
        if (Vizio.paired(s)) out += Device("vizio", s.tvHost, "Vizio TV")
        for ((host, name) in rokus(c)) out += Device("roku", host, name)
        for (id in s.haEntities.split(",", " ", "\n").map { it.trim() }.filter { "." in it }) out += Device("ha", id, id)
        return out
    }

    // ------------------------------------------------ Roku (ECP)

    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    // The Roku TVs found before: address and name.
    fun rokus(c: Context): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs(c).getString("rokus", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> o.getString("host") to o.getString("name") } }
    }.getOrDefault(emptyList())

    // SSDP for Rokus (ST roku:ecp, 3 s), their names asked; kept for next time. The ones found.
    fun findRokus(c: Context): List<Pair<String, String>> {
        val hosts = linkedSetOf<String>()
        runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val ask = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: roku:ecp\r\n\r\n"
                    .toByteArray()
                s.send(DatagramPacket(ask, ask.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    if (text.contains("roku:ecp", ignoreCase = true)) p.address.hostAddress?.let { hosts += it }
                }
            }
        }
        val found = hosts.map { h -> h to (rokuInfo(h)["user-device-name"] ?: rokuInfo(h)["friendly-device-name"] ?: "Roku TV") }
        if (found.isNotEmpty()) {
            val a = JSONArray()
            for ((h, n) in found) a.put(JSONObject().put("host", h).put("name", n))
            prefs(c).edit().putString("rokus", a.toString()).apply()
        }
        return found
    }

    // The Roku's device-info fields (power-mode: PowerOn, Ready, DisplayOff...); empty when it does not answer.
    fun rokuInfo(host: String): Map<String, String> = runCatching {
        val text = http("http://$host:8060/query/device-info", "GET", null, null, 3000)
        Regex("<([a-z-]+)>([^<]*)</\\1>").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }.getOrDefault(emptyMap())

    // A key on the Roku (ECP names: Power, PowerOn, PowerOff, VolumeUp, VolumeDown, VolumeMute, Home...).
    fun rokuKey(host: String, key: String): String = runCatching {
        http("http://$host:8060/keypress/$key", "POST", "", null, 3000)
        ""
    }.getOrElse { "the Roku did not answer" }

    // ------------------------------------------------ Home Assistant (REST API)

    // An entity's state and the attributes worth showing: "on", "off", "heat 70° (68°)", "playing 35%".
    fun haState(s: Settings, id: String): Pair<String, String> = runCatching {
        val o = JSONObject(http(s.haUrl.trimEnd('/') + "/api/states/$id", "GET", null, s.haToken, 4000))
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val name = a.optString("friendly_name").ifEmpty { id }
        val state = o.optString("state")
        val shown = when (id.substringBefore('.')) {
            "climate" -> state + (a.optDouble("temperature").takeIf { !it.isNaN() }?.let { " ${it.toInt()}°" } ?: "") +
                (a.optDouble("current_temperature").takeIf { !it.isNaN() }?.let { " (now ${it.toInt()}°)" } ?: "")
            "light" -> state + (a.optInt("brightness", -1).takeIf { it >= 0 && state == "on" }?.let { " ${it * 100 / 255}%" } ?: "")
            "media_player" -> state + (a.optDouble("volume_level").takeIf { !it.isNaN() }?.let { " vol ${(it * 100).toInt()}%" } ?: "")
            else -> state
        }
        name to shown
    }.getOrElse { id to "no answer" }

    // On / off (any entity Home Assistant can toggle).
    fun haToggle(s: Settings, id: String) = haService(s, "homeassistant", "toggle", JSONObject().put("entity_id", id))

    // Up or down: a thermostat's target a degree, a light's brightness 10%, a player's volume a step.
    fun haStep(s: Settings, id: String, up: Boolean): String = when (id.substringBefore('.')) {
        "climate" -> runCatching {
            val a = JSONObject(http(s.haUrl.trimEnd('/') + "/api/states/$id", "GET", null, s.haToken, 4000)).optJSONObject("attributes")
            val t = a?.optDouble("temperature") ?: Double.NaN
            if (t.isNaN()) "it has no target temperature"
            else haService(s, "climate", "set_temperature", JSONObject().put("entity_id", id).put("temperature", t + if (up) 1 else -1))
        }.getOrElse { "Home Assistant did not answer" }
        "light" -> haService(s, "light", "turn_on", JSONObject().put("entity_id", id).put("brightness_step_pct", if (up) 10 else -10))
        "media_player" -> haService(s, "media_player", if (up) "volume_up" else "volume_down", JSONObject().put("entity_id", id))
        "fan" -> haService(s, "fan", if (up) "increase_speed" else "decrease_speed", JSONObject().put("entity_id", id))
        else -> "only lights, thermostats, players and fans go up and down"
    }

    private fun haService(s: Settings, domain: String, service: String, body: JSONObject): String = runCatching {
        http(s.haUrl.trimEnd('/') + "/api/services/$domain/$service", "POST", body.toString(), s.haToken, 5000)
        ""
    }.getOrElse { "Home Assistant: ${it.message ?: "no answer"}" }

    // ------------------------------------------------ HTTP

    private fun http(url: String, method: String, body: String?, token: String?, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.requestMethod = method
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException(if (code == 401) "the token was refused" else "HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}

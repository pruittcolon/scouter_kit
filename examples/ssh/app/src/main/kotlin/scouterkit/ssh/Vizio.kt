package scouterkit.ssh

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// A Vizio SmartCast TV's remote (its own HTTPS API on port 7345). The TV is found on the Wi-Fi (or its address
// typed), paired once with the PIN it shows (Settings, TV remote), and the token it gives stays on this phone,
// in Vox's private settings. Nothing about the TV is in the app itself. The TV's certificate is self-signed, so
// it is accepted for this one connection only, never for anything else.
object Vizio {
    private const val PORT = 7345

    // Vizio's remote keys: (code set, code).
    private val KEYS = mapOf(
        "UP" to (3 to 8), "DOWN" to (3 to 0), "LEFT" to (3 to 1), "RIGHT" to (3 to 7), "OK" to (3 to 2),
        "BACK" to (4 to 0), "HOME" to (4 to 15), "MENU" to (4 to 8), "INFO" to (4 to 6), "EXIT" to (9 to 0),
        "VOL_UP" to (5 to 1), "VOL_DOWN" to (5 to 0), "MUTE" to (5 to 4),
        "PLAY" to (2 to 3), "PAUSE" to (2 to 2), "FWD" to (2 to 0), "REW" to (2 to 1),
        "INPUT" to (7 to 1), "CH_UP" to (8 to 1), "CH_DOWN" to (8 to 0),
        "POWER" to (11 to 2), "POWER_OFF" to (11 to 0), "POWER_ON" to (11 to 1),
    )

    // What a key on the keyboard does while the TV remote is on (Alt+Y): key -> (the TV's key, the words for the glasses).
    val LAYOUT: List<Triple<String, String, String>> = listOf(
        Triple("Arrows", "UP/DOWN/LEFT/RIGHT", "move"),
        Triple("Enter", "OK", "select"),
        Triple("Backspace / Esc", "BACK", "back"),
        Triple("H", "HOME", "home"),
        Triple("+ / -", "VOL", "volume"),
        Triple("M", "MUTE", "mute"),
        Triple("Space", "PLAY/PAUSE", "play / pause"),
        Triple("I", "INPUT", "input"),
        Triple("Page Up / Down", "CH", "channel"),
        Triple("P", "POWER", "power"),
    )

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "vizio").apply { isDaemon = true } }
    private val ctx: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), SecureRandom())
    }

    @Volatile private var playing = true
    private var pairToken = ""
    private var pairId = ""

    fun paired(s: Settings) = s.tvHost.isNotEmpty() && s.tvToken.isNotEmpty()

    // One request to the TV: the JSON answer, or an exception with the reason in words.
    private fun call(host: String, method: String, path: String, body: JSONObject?, token: String, timeout: Int = 4000): JSONObject {
        val c = java.net.URL("https://$host:$PORT$path").openConnection() as HttpsURLConnection
        try {
            c.sslSocketFactory = ctx.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.requestMethod = method
            if (token.isNotEmpty()) c.setRequestProperty("AUTH", token)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            return JSONObject(stream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun ok(d: JSONObject) = d.optJSONObject("STATUS")?.optString("RESULT") == "SUCCESS"

    // A key to the TV (off the main thread). done: the words for what happened ("" when it went through).
    fun key(settings: Settings, name: String, done: ((String) -> Unit)? = null) {
        val k = KEYS[name]
        if (k == null || !paired(settings)) {
            done?.invoke(if (!paired(settings)) "Pair the TV first (Settings, TV remote)" else "No such key")
            return
        }
        val host = settings.tvHost
        val token = settings.tvToken
        val body = JSONObject().put("KEYLIST", JSONArray().put(JSONObject()
            .put("CODESET", k.first).put("CODE", k.second).put("ACTION", "KEYPRESS")))
        pool.execute {
            val why = runCatching {
                val d = call(host, "PUT", "/key_command/", body, token)
                if (ok(d)) "" else d.optJSONObject("STATUS")?.optString("DETAIL").orEmpty().ifEmpty { "The TV said no" }
            }.getOrElse { e ->
                val moved = if (e is java.io.IOException) relocate(settings, token) else null
                if (moved == null) "The TV did not answer (is it on, on this Wi-Fi?)"
                else runCatching { if (ok(call(moved, "PUT", "/key_command/", body, token))) "" else "The TV said no" }
                    .getOrDefault("The TV did not answer")
            }
            done?.invoke(why)
        }
    }

    // Play / pause, one key (the TV has two): the other from the one sent last.
    fun playPause(settings: Settings, done: ((String) -> Unit)? = null) {
        playing = !playing
        key(settings, if (playing) "PLAY" else "PAUSE", done)
    }

    // The TV's name and whether it is on (null: no answer).
    fun status(settings: Settings, done: (String?) -> Unit) {
        val host = settings.tvHost
        val token = settings.tvToken
        pool.execute {
            done(runCatching {
                val d = call(host, "GET", "/state/device/power_mode", null, token)
                val items = d.optJSONArray("ITEMS")
                val on = items?.optJSONObject(0)?.optInt("VALUE", 0) == 1
                if (on) "on" else "off (standby)"
            }.getOrNull())
        }
    }

    // Pairing, step 1: the TV shows a PIN on its screen. done: "" or the reason.
    fun pairStart(host: String, done: (String) -> Unit) {
        pool.execute {
            done(runCatching {
                pairId = "vox-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8)
                val d = call(host, "PUT", "/pairing/start",
                    JSONObject().put("DEVICE_ID", pairId).put("DEVICE_NAME", "Vox"), "", 8000)
                pairToken = d.getJSONObject("ITEM").getString("PAIRING_REQ_TOKEN")
                ""
            }.getOrElse { "That address did not answer as a Vizio TV (the TV on, same Wi-Fi?)" })
        }
    }

    // Step 2: the PIN. On success the TV's address and token are kept here.
    fun pairFinish(settings: Settings, host: String, pin: String, done: (String) -> Unit) {
        pool.execute {
            done(runCatching {
                val d = call(host, "PUT", "/pairing/pair", JSONObject().put("DEVICE_ID", pairId)
                    .put("CHALLENGE_TYPE", 1).put("RESPONSE_VALUE", pin.trim())
                    .put("PAIRING_REQ_TOKEN", pairToken), "", 8000)
                val token = d.getJSONObject("ITEM").getString("AUTH_TOKEN")
                settings.tvHost = host
                settings.tvToken = token
                ""
            }.getOrElse { "That PIN did not work: start again" })
        }
    }

    // Every address of this phone's Wi-Fi network (at most a /22, 1022 hosts) that answers on 7345: a quick
    // connect each, many at once (a few seconds in all).
    private fun scan(): List<String> {
        val (ip, prefix) = wifi()
        if (ip.isEmpty()) return emptyList()
        val bits = prefix.coerceIn(22, 30)
        val me = ip.split('.').fold(0L) { a, p -> (a shl 8) or p.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        val net = me and mask
        val hosts = (1 until (1L shl (32 - bits)) - 1).map { net + it }.filter { it != me }
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = Executors.newFixedThreadPool(64)
        for (h in hosts) {
            val addr = "${h shr 24 and 255}.${h shr 16 and 255}.${h shr 8 and 255}.${h and 255}"
            workers.execute {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(addr, PORT), 400) }
                    found += addr
                }
            }
        }
        workers.shutdown()
        workers.awaitTermination(30, TimeUnit.SECONDS)
        return found.sorted()
    }

    // Looking for a TV on the Wi-Fi (Settings, TV remote). done: the first address found, or "".
    fun find(done: (String) -> Unit) {
        Thread { done(scan().firstOrNull().orEmpty()) }.start()
    }

    // The paired TV moved to another address (the router gave it a new one): found again by asking each TV on the
    // Wi-Fi with our token, and kept. At most once a minute (a TV that is unplugged does not start a scan per key).
    @Volatile private var relocatedAt = 0L
    private fun relocate(settings: Settings, token: String): String? {
        val now = System.currentTimeMillis()
        if (now - relocatedAt < 60_000) return null
        relocatedAt = now
        for (host in scan()) {
            if (host == settings.tvHost) continue
            val good = runCatching { ok(call(host, "GET", "/state/device/power_mode", null, token)) }.getOrDefault(false)
            if (good) {
                settings.tvHost = host
                return host
            }
        }
        return null
    }

    // This phone's Wi-Fi address and its network's prefix length; ("", 0) off Wi-Fi. The Wi-Fi interface first
    // (wlan*): mobile data can have a private address too, and that network has no TV.
    fun wifi(): Pair<String, Int> = runCatching {
        val all = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
        val ordered = all.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (i in ordered) for (a in i.interfaceAddresses) {
            val ad = a.address
            if (ad is java.net.Inet4Address && ad.isSiteLocalAddress) return@runCatching ad.hostAddress.orEmpty() to a.networkPrefixLength.toInt()
        }
        "" to 0
    }.getOrDefault("" to 0)

    // The remote key a keyboard key stands for while the remote is on (null: none; the key goes on as it is).
    fun forKey(code: Int): String? = when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> "UP"
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> "OK"
        android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_ESCAPE -> "BACK"
        android.view.KeyEvent.KEYCODE_H -> "HOME"
        android.view.KeyEvent.KEYCODE_EQUALS, android.view.KeyEvent.KEYCODE_PLUS, android.view.KeyEvent.KEYCODE_NUMPAD_ADD -> "VOL_UP"
        android.view.KeyEvent.KEYCODE_MINUS, android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "VOL_DOWN"
        android.view.KeyEvent.KEYCODE_M -> "MUTE"
        android.view.KeyEvent.KEYCODE_SPACE -> "PLAY_PAUSE"
        android.view.KeyEvent.KEYCODE_I -> "INPUT"
        android.view.KeyEvent.KEYCODE_PAGE_UP -> "CH_UP"
        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "CH_DOWN"
        android.view.KeyEvent.KEYCODE_P -> "POWER"
        else -> null
    }
}

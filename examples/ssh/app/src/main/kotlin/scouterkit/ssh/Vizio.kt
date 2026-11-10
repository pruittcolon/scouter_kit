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
        Triple("1 ... 9, 0", "APP", "an app (below)"),
        Triple("T", "TYPE", "type text (Esc: done)"),
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
    // The pairing under way (pairStart): kept as the TV sent them, numbers as numbers.
    @Volatile private var pairToken = 0L
    @Volatile private var pairChallenge = 1
    @Volatile private var pairId = ""
    @Volatile private var pairHost = ""

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
        val k = KEYS[name] ?: return done?.invoke("No such key") ?: Unit
        if (name == "POWER" || name == "POWER_ON") return power(settings, k, done)
        keys(settings, listOf(k), done)
    }

    // Power. A TV in standby answers and takes the key. A TV that is off in Eco mode has its network asleep
    // (nothing answers on 7345) but wakes on Wake-on-LAN, as Vizio's own app wakes it: the magic packet to the MAC
    // addresses learned while it was on, then POWER_ON (not the toggle: that would turn it off again) until the TV
    // answers, at most WAKE_MS. Its own thread: the key pool stays free meanwhile.
    private const val WAKE_MS = 25_000L
    private val MAC = Regex("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}")

    private fun power(settings: Settings, k: Pair<Int, Int>, done: ((String) -> Unit)?) {
        if (!paired(settings)) return done?.invoke("Pair the TV first (the TV tab)") ?: Unit
        Thread({ val why = wake(settings, k); done?.invoke(why) }, "vizio-power").start()
    }

    private fun press(host: String, token: String, k: Pair<Int, Int>, timeout: Int) = runCatching {
        val key = JSONObject().put("CODESET", k.first).put("CODE", k.second).put("ACTION", "KEYPRESS")
        ok(call(host, "PUT", "/key_command/", JSONObject().put("KEYLIST", JSONArray().put(key)), token, timeout))
    }.getOrDefault(false)

    private fun wake(settings: Settings, k: Pair<Int, Int>): String {
        val host = settings.tvHost
        val token = settings.tvToken
        if (press(host, token, k, 2500)) { learn(settings, host, token); return "" }
        val macs = settings.tvMac.split(',').filter { MAC.matches(it.trim()) }
        if (macs.isEmpty()) return "The TV is asleep and Vox can't wake it yet: turn it on once, then open the TV tab"
        val on = KEYS.getValue("POWER_ON")
        val end = System.currentTimeMillis() + WAKE_MS
        while (System.currentTimeMillis() < end) {
            wol(macs, host)
            if (press(host, token, on, 1500)) return ""
            Thread.sleep(1000)
        }
        val moved = relocate(settings, token)
        if (moved != null && press(moved, token, on, 2500)) return ""
        return "The TV did not wake (Wake-on-LAN for 25 s): is it on this Wi-Fi?"
    }

    // Wake-on-LAN: the magic packet (6 bytes 0xFF, then the MAC 16 times) to the Wi-Fi's broadcast address, the
    // all-ones broadcast and the TV's own address, ports 9 and 7.
    private fun wol(macs: List<String>, host: String) {
        val targets = mutableListOf("255.255.255.255", host)
        val (ip, prefix) = wifi()
        if (ip.isNotEmpty() && prefix in 8..30) {
            val me = ip.split('.').fold(0L) { a, p -> (a shl 8) or p.toLong() }
            val b = me or ((1L shl (32 - prefix)) - 1)
            targets += "${b shr 24 and 255}.${b shr 16 and 255}.${b shr 8 and 255}.${b and 255}"
        }
        runCatching {
            java.net.DatagramSocket().use { s ->
                s.broadcast = true
                for (mac in macs) {
                    val m = mac.trim().split(':', '-').map { it.toInt(16).toByte() }
                    val p = ByteArray(102) { if (it < 6) 0xFF.toByte() else m[(it - 6) % 6] }
                    for (t in targets) for (port in intArrayOf(9, 7)) runCatching {
                        s.send(java.net.DatagramPacket(p, p.size, java.net.InetAddress.getByName(t), port))
                    }
                }
            }
        }
    }

    // The TV's MAC addresses (Wi-Fi and Ethernet), asked once while it answers and kept for waking it.
    private fun learn(settings: Settings, host: String, token: String) {
        if (settings.tvMac.isNotEmpty()) return
        runCatching {
            val items = call(host, "GET", "/menu_native/dynamic/tv_settings/network/network_information", null, token)
                .optJSONArray("ITEMS") ?: return
            val macs = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("CNAME") == "wlan_mac" || it.optString("CNAME") == "rj45_mac" }
                .map { it.optString("VALUE").trim() }
                .filter { MAC.matches(it) && it.any { c -> c != '0' && c != ':' && c != '-' } }
            if (macs.isNotEmpty()) settings.tvMac = macs.joinToString(",")
        }
    }

    // Text into the field the TV shows (a search box), as Vizio's own app types it: each character a key of codeset
    // 0 with its ASCII code (7-bit ASCII only: others are left out); 8 is Backspace there.
    fun type(settings: Settings, text: String, done: ((String) -> Unit)? = null) {
        val codes = text.filter { it.code in 8..126 }.map { 0 to it.code }
        if (codes.isEmpty()) return done?.invoke("") ?: Unit
        val parts = codes.chunked(50)
        for ((i, part) in parts.withIndex()) keys(settings, part, if (i == parts.size - 1) done else null)
    }

    // Keys (codeset, code) in one KEYLIST, in order; a TV that moved is looked for once.
    private fun keys(settings: Settings, codes: List<Pair<Int, Int>>, done: ((String) -> Unit)?) {
        if (!paired(settings)) return done?.invoke("Pair the TV first (the TV tab)") ?: Unit
        val list = JSONArray()
        for ((set, code) in codes) list.put(JSONObject().put("CODESET", set).put("CODE", code).put("ACTION", "KEYPRESS"))
        send(settings, "/key_command/", JSONObject().put("KEYLIST", list), done)
    }

    // The popular apps (Vizio's published app list: its launch payloads), in the order of the keys 1 ... 9, 0 in TV
    // mode. alt: the payload some chipsets take instead (tried when the first is refused).
    class App(val name: String, val ns: Int, val id: String, val alt: Pair<Int, String>? = null)
    val APPS = listOf(
        App("Netflix", 3, "1"), App("YouTube", 5, "1"), App("Prime Video", 2, "4", 3 to "3"), App("Disney+", 2, "75"),
        App("Hulu", 2, "3"), App("HBO Max", 2, "128"), App("Paramount+", 2, "37"), App("Peacock", 2, "88"),
        App("Tubi", 2, "61"), App("Plex", 2, "9"),
    )

    fun launch(settings: Settings, app: App, done: ((String) -> Unit)? = null) {
        fun body(ns: Int, id: String) = JSONObject().put("VALUE", JSONObject().put("NAME_SPACE", ns).put("APP_ID", id)
            .put("MESSAGE", JSONObject.NULL))
        val alt = app.alt
        val first: ((String) -> Unit)? = if (alt == null) done else { why: String ->
            if (why.isEmpty() || why.startsWith("The TV did not answer")) done?.invoke(why)
            else send(settings, "/app/launch", body(alt.first, alt.second), done)
            Unit
        }
        send(settings, "/app/launch", body(app.ns, app.id), first)
    }

    // A PUT to the paired TV; done: "" when it went through, else why. A TV that moved is looked for once.
    private fun send(settings: Settings, path: String, body: JSONObject, done: ((String) -> Unit)?) {
        if (!paired(settings)) return done?.invoke("Pair the TV first (the TV tab)") ?: Unit
        val host = settings.tvHost
        val token = settings.tvToken
        pool.execute {
            val why = runCatching {
                val d = call(host, "PUT", path, body, token, 8000)
                if (ok(d)) "" else d.optJSONObject("STATUS")?.optString("DETAIL").orEmpty().ifEmpty { "The TV said no" }
            }.getOrElse { e ->
                val moved = if (e is java.io.IOException) relocate(settings, token) else null
                if (moved == null) "The TV did not answer (is it on, on this Wi-Fi?)"
                else runCatching { if (ok(call(moved, "PUT", path, body, token, 8000))) "" else "The TV said no" }
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
                learn(settings, host, token)
                if (on) "on" else "off (standby)"
            }.getOrNull())
        }
    }

    // A pairing reply that was not SUCCESS, in words. CHALLENGE_INCORRECT is the wrong PIN (the PIN stays valid:
    // type it again); INVALID_PARAMETER is a request the TV can't match to a PIN (none waiting, or a field of the
    // wrong type: the token sent back as a string gets this whatever the PIN).
    private fun refused(d: JSONObject): String {
        val s = d.optJSONObject("STATUS")
        return when (s?.optString("RESULT").orEmpty()) {
            "CHALLENGE_INCORRECT" -> "The TV says that PIN is wrong: check it and tap Finish again"
            "INVALID_PARAMETER" -> "The TV has no PIN waiting (closed or timed out): tap Pair for a new one"
            "BLOCKED" -> "The TV is pairing something else: close its pop-up, then tap Pair"
            "" -> "The TV gave no answer it could use: tap Pair again"
            else -> "The TV said: " + s!!.optString("DETAIL").ifEmpty { s.optString("RESULT") }
        }
    }

    // Pairing, step 1: the TV shows a PIN on its screen. done: "" or the reason.
    fun pairStart(host: String, done: (String) -> Unit) {
        pool.execute {
            done(runCatching {
                val id = "vox-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8)
                val d = call(host, "PUT", "/pairing/start",
                    JSONObject().put("DEVICE_ID", id).put("DEVICE_NAME", "Vox"), "", 8000)
                val item = d.optJSONObject("ITEM")
                if (!ok(d) || item == null || !item.has("PAIRING_REQ_TOKEN")) refused(d)
                else {
                    pairToken = item.getLong("PAIRING_REQ_TOKEN") // a number: the TV refuses it back as a string
                    pairChallenge = item.optInt("CHALLENGE_TYPE", 1)
                    pairHost = host
                    pairId = id
                    ""
                }
            }.getOrElse { "That address did not answer as a Vizio TV (the TV on, same Wi-Fi?)" })
        }
    }

    // Step 2: the PIN, to the TV that showed it. On success the TV's address and token are kept here.
    fun pairFinish(settings: Settings, pin: String, done: (String) -> Unit) {
        val id = pairId
        val host = pairHost
        if (id.isEmpty()) return done("Tap Pair first: the TV then shows a PIN")
        val body = JSONObject().put("DEVICE_ID", id).put("CHALLENGE_TYPE", pairChallenge)
            .put("RESPONSE_VALUE", pin.trim()).put("PAIRING_REQ_TOKEN", pairToken)
        pool.execute {
            done(runCatching {
                val d = call(host, "PUT", "/pairing/pair", body, "", 8000)
                val token = d.optJSONObject("ITEM")?.optString("AUTH_TOKEN").orEmpty()
                if (!ok(d) || token.isEmpty()) refused(d)
                else {
                    settings.tvHost = host
                    settings.tvToken = token
                    settings.tvMac = ""
                    learn(settings, host, token)
                    pairId = ""
                    ""
                }
            }.getOrElse { "The TV did not answer: is it still on, on this Wi-Fi?" })
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

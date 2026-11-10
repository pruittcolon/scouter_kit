package scouterkit.ssh

import org.json.JSONObject

// The screen's look remembered per app: the phone's app in front (VoxKeys tells which), a server's window in front
// (its X11 class, asked over SSH), else per source. A change of the look while one of them shows is kept for it,
// and its look comes back when it shows again; one without a look of its own takes its source's. Keys:
// "app:PACKAGE", "win:SERVER:CLASS", "src:phone", "src:screen:SERVER"; the looks are JSON in Settings.looks.
object Looks {
    @Volatile var app = "" // the phone's app in front (Vox's own and the system's windows left out)
    @Volatile var windows: Map<String, String> = emptyMap() // each server's window in front (its class), by id
    @Volatile var key = "" // the look in use now

    // The settings a look holds (Settings' names), to notice their changes.
    val PREFS = setOf("screen_fit", "screen_pan_x", "screen_pan_y", "screen_zoom", "screen_rotate", "screen_out_x",
        "screen_out_y", "screen_out_w", "screen_out_h", "screen_invert_pct", "screen_gamma", "screen_threshold",
        "screen_contrast", "screen_levels", "screen_detail", "screen_budget", "screen_sharpen", "screen_dither",
        "screen_steady", "screen_clean", "screen_crop_top", "screen_crop_bottom")

    fun capture(s: Settings): JSONObject = JSONObject().apply {
        put("fit", s.screenFit)
        put("panX", s.screenPanX)
        put("panY", s.screenPanY)
        put("zoom", s.screenZoom)
        put("rotate", s.screenRotate)
        put("outX", s.screenOutX)
        put("outY", s.screenOutY)
        put("outW", s.screenOutW)
        put("outH", s.screenOutH)
        put("invert", s.screenInvertPct)
        put("gamma", s.screenGamma)
        put("threshold", s.screenThreshold)
        put("contrast", s.screenContrast)
        put("levels", s.screenLevels)
        put("detail", s.screenDetail)
        put("budget", s.screenBudget)
        put("sharpen", s.screenSharpen)
        put("dither", s.screenDither)
        put("steady", s.screenSteady)
        put("clean", s.screenClean)
        put("cropTop", s.screenCropTop)
        put("cropBottom", s.screenCropBottom)
    }

    fun apply(s: Settings, o: JSONObject) {
        fun int(k: String, set: (Int) -> Unit) {
            if (o.has(k)) set(o.optInt(k))
        }
        fun bool(k: String, set: (Boolean) -> Unit) {
            if (o.has(k)) set(o.optBoolean(k))
        }
        o.optString("fit").takeIf { it.isNotEmpty() }?.let { s.screenFit = it }
        int("panX") { s.screenPanX = it }
        int("panY") { s.screenPanY = it }
        int("zoom") { s.screenZoom = it }
        int("rotate") { s.screenRotate = it }
        int("outX") { s.screenOutX = it }
        int("outY") { s.screenOutY = it }
        int("outW") { s.screenOutW = it }
        int("outH") { s.screenOutH = it }
        int("invert") { s.screenInvertPct = it }
        int("gamma") { s.screenGamma = it }
        int("threshold") { s.screenThreshold = it }
        int("contrast") { s.screenContrast = it }
        int("levels") { s.screenLevels = it }
        int("detail") { s.screenDetail = it }
        int("budget") { s.screenBudget = it }
        bool("sharpen") { s.screenSharpen = it }
        bool("dither") { s.screenDither = it }
        bool("steady") { s.screenSteady = it }
        bool("clean") { s.screenClean = it }
        int("cropTop") { s.screenCropTop = it }
        int("cropBottom") { s.screenCropBottom = it }
    }

    private fun all(s: Settings): JSONObject = runCatching { JSONObject(s.looks) }.getOrDefault(JSONObject())

    // The look an app or window falls back on: its source's.
    fun fallback(key: String): String? = when {
        key.startsWith("app:") -> "src:phone"
        key.startsWith("win:") -> "src:screen:" + key.removePrefix("win:").substringBefore(':')
        else -> null
    }

    // Whether this key has a look of its own.
    fun own(s: Settings, key: String): Boolean = all(s).has(key)

    // The key's look (its own, else its source's), or null.
    fun lookOf(s: Settings, key: String): JSONObject? {
        val a = all(s)
        return a.optJSONObject(key) ?: fallback(key)?.let { a.optJSONObject(it) }
    }

    private fun same(a: JSONObject, b: JSONObject): Boolean = a.keys().asSequence().all { k -> b.has(k) && a.get(k).toString() == b.get(k).toString() }

    // The look in use becomes `key`'s (the source's default is made from the look in use the first time); true when
    // the settings changed.
    fun use(s: Settings, key: String): Boolean {
        this.key = key
        val a = all(s)
        val src = fallback(key) ?: key
        if (!a.has(src)) {
            a.put(src, capture(s))
            s.looks = a.toString()
        }
        val look = a.optJSONObject(key) ?: a.optJSONObject(src) ?: return false
        if (same(look, capture(s))) return false
        apply(s, look)
        return true
    }

    // The settings changed: the look in use keeps them (unless they are the look it already has).
    fun remember(s: Settings) {
        val k = key.ifEmpty { return }
        val now = capture(s)
        val had = lookOf(s, k)
        if (had != null && same(now, had)) return
        val a = all(s)
        a.put(k, now)
        s.looks = a.toString()
    }

    // The key's own look goes: its source's applies again.
    fun forget(s: Settings, key: String) {
        val a = all(s)
        if (fallback(key) == null) return // a source's look stays: it is the default
        a.remove(key)
        s.looks = a.toString()
        a.optJSONObject(fallback(key)!!)?.let { apply(s, it) }
    }

    // The look in use becomes its source's default too.
    fun makeDefault(s: Settings, key: String) {
        val src = fallback(key) ?: key
        val a = all(s)
        a.put(src, capture(s))
        s.looks = a.toString()
    }

    // A name for a key: the app's, "Laptop · firefox", "Phone screen".
    fun name(c: android.content.Context, s: Settings, key: String): String = when {
        key.startsWith("app:") -> {
            val pkg = key.removePrefix("app:")
            runCatching {
                val pm = c.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        key.startsWith("win:") -> {
            val rest = key.removePrefix("win:")
            val server = s.servers().firstOrNull { it.id == rest.substringBefore(':') }?.name ?: "Server"
            "$server · ${rest.substringAfter(':')}"
        }
        key == "src:phone" -> "Phone screen"
        key.startsWith("src:screen:") -> (s.servers().firstOrNull { it.id == key.removePrefix("src:screen:") }?.name ?: "Server") + " screen"
        else -> key
    }
}

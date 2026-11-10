package scouterkit.ssh

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// The Vizio TV's remote on the phone, as a page (the app's TV tab, and Settings' TV remote): whether the TV answers,
// the buttons, Firefox on the TV, then finding and pairing the TV once (the TV shows a PIN). The same remote is on the
// keyboard with Alt+Y (Vizio.LAYOUT), drawn on the glasses meanwhile. The TV's address and token stay in Vox's private
// settings. done: a Done button in the header (its own screen) that calls it.
class TvPage(private val a: Activity, page: LinearLayout, done: (() -> Unit)? = null) {
    private val settings = Settings(a)
    private val status: TextView
    private val host: EditText
    private val pin: EditText
    private val remote: LinearLayout
    private lateinit var words: EditText

    init {
        with(a) {
            page.addView(if (done != null) header("TV remote", "A Vizio SmartCast TV", "Done") { done() }
                else header("TV", "A Vizio SmartCast TV's remote"))
            status = text("", 14f, Ui.TEXT, Ui.BOLD).apply { setPadding(dp(4), dp(2), dp(4), dp(10)) }
            page.addView(status)

            remote = card("Remote", "Alt+Y on the keyboard turns it into these keys (and shows them on the glasses): " +
                "arrows, Enter, Backspace, H, +/-, M, Space, I, Page Up/Down, P.")
            fun key(label: String, name: String, kind: Int = 1) = button(label, kind) { send(name) }
            remote.addView(row(key("Power", "POWER", 2), key("Input", "INPUT"), key("Home", "HOME")))
            remote.addView(row(View(this), key("▲", "UP"), View(this)))
            remote.addView(row(key("◀", "LEFT"), key("OK", "OK", 0), key("▶", "RIGHT")))
            remote.addView(row(View(this), key("▼", "DOWN"), View(this)))
            remote.addView(row(key("Back", "BACK"), key("Menu", "MENU"), key("Info", "INFO")))
            remote.addView(row(key("Vol −", "VOL_DOWN"), key("Mute", "MUTE"), key("Vol +", "VOL_UP")))
            remote.addView(row(key("Ch −", "CH_DOWN"), button("Play / pause") { Vizio.playPause(settings) { say(it) } }, key("Ch +", "CH_UP")))
            remote.addView(row(button("Remote on the glasses (Alt+Y)", 0) { glasses() }))
            page.addView(remote)

            // The popular apps, opened on the TV (in TV mode the keys 1 ... 9, 0 do it too).
            val apps = card("Apps", "Opens the app on the TV. In TV mode (Alt+Y) the keys 1 to 9 and 0, in this order.")
            for (three in Vizio.APPS.chunked(3)) apps.addView(row(*three.map { app ->
                button(app.name) { Vizio.launch(settings, app) { why -> say(if (why.isEmpty()) "${app.name} on the TV" else "${app.name}: $why") } }
            }.toTypedArray(), *Array(3 - three.size) { View(this) }))
            page.addView(apps)

            // Text into the TV's search box (open one first): sent as typed by Vizio's own app.
            val type = card("Type on the TV", "Open a search box on the TV first. In TV mode (Alt+Y), T types from the " +
                "keyboard instead (Esc: done). Letters, digits and plain punctuation only.")
            words = field("Text", "", "what to type on the TV")
            type.addView(words)
            type.addView(row(
                button("Type it", 0) { typeOnTv(enter = false) },
                button("Type + Enter") { typeOnTv(enter = true) },
                button("⌫") { Vizio.type(settings, "\b") { say(it) } },
            ))
            page.addView(type)

            // The phone's own mirror to the TV (its Linux's `phone` command), as a tab of the phone's terminal.
            val firefox = card("Firefox on the TV", "Firefox by itself on the TV, worked from the phone (the phone " +
                "command in this phone's Linux). It runs in a tab named tv of the phone's terminal; closing the tab " +
                "stops it. Alt+F on the keyboard too.")
            firefox.addView(row(button("Firefox on the TV", 0) { mirror("tvtab") }, button("Stop it") { mirror("tvstop") }))
            page.addView(firefox)

            val pair = card("Pairing", "Turn the TV on, on the same Wi-Fi as this phone. Find it, then Pair: the TV " +
                "shows a PIN, type it here, then Finish. Once.")
            host = field("TV address", settings.tvHost, "found by Find the TV, or type the address from the TV's network settings")
            pair.addView(host)
            pair.addView(chipRow(
                chip("Find the TV", true) { find() },
                chip("Pair", false) { pairStart() },
            ))
            pin = field("PIN shown on the TV", "", "4 digits", numeric = true)
            pair.addView(pin)
            pair.addView(chipRow(chip("Finish pairing", false) { pairFinish() }))
            page.addView(pair)
        }
        show()
    }

    private fun row(vararg keys: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, k) in keys.withIndex()) addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { if (i > 0) leftMargin = a.dp(8); topMargin = a.dp(8) })
    }

    // Whether the TV answers (on or in standby): when the page shows.
    fun refresh() {
        if (!Vizio.paired(settings)) return show()
        Vizio.status(settings) { s -> a.runOnUiThread {
            status.text = if (s == null && settings.tvMac.isNotEmpty()) "${settings.tvHost}: the TV is asleep (Power wakes it)"
            else if (s == null) "Paired with ${settings.tvHost}: not answering (off at the wall, or another Wi-Fi?)"
            else "${settings.tvHost}: the TV is $s"
        } }
    }

    private fun say(why: String) = a.runOnUiThread { if (why.isNotEmpty()) status.text = why }

    private fun show() {
        val paired = Vizio.paired(settings)
        remote.alpha = if (paired) 1f else 0.4f
        status.text = if (paired) "Paired with ${settings.tvHost}" else "Not paired yet: pair it below"
    }

    private fun send(name: String) {
        if (name == "POWER") status.text = "Power… (an asleep TV takes up to 25 s to wake)"
        Vizio.key(settings, name) { why -> if (name == "POWER" && why.isEmpty()) refresh() else say(why) }
    }

    private fun typeOnTv(enter: Boolean) {
        val t = words.text.toString()
        if (t.isEmpty()) return run { status.text = "Type something first" }
        status.text = "Typing on the TV…"
        Vizio.type(settings, t) { why ->
            if (why.isNotEmpty()) return@type say(why)
            if (enter) Vizio.key(settings, "OK") { say(if (it.isEmpty()) "Typed and entered" else it) }
            else say("Typed")
            a.runOnUiThread { words.setText("") }
        }
    }

    private fun glasses() {
        if (!ScouterService.running) return run { status.text = "Start Vox first (Glasses tab)" }
        ScouterService.send(a, ScouterService.ACTION_PANEL, "do" to "tv")
    }

    private fun mirror(what: String) {
        if (!ScouterService.running) return run { status.text = "Start Vox first (Glasses tab)" }
        ScouterService.send(a, ScouterService.ACTION_PANEL, "do" to what)
        status.text = if (what == "tvstop") "Stopping the TV mirror" else "Putting Firefox on the TV…"
    }

    private fun find() {
        status.text = "Looking for the TV on the Wi-Fi…"
        if (Vizio.wifi().first.isEmpty()) return run { status.text = "Not on a Wi-Fi network" }
        Vizio.find { found -> a.runOnUiThread {
            if (found.isEmpty()) status.text = "No TV found: is it on? Or type its address (TV settings, Network)."
            else {
                host.setText(found)
                status.text = "Found a TV at $found: tap Pair"
            }
        } }
    }

    private fun pairStart() {
        val h = host.text.toString().trim()
        if (h.isEmpty()) return run { status.text = "Find the TV first (or type its address)" }
        status.text = "Asking the TV for a PIN…"
        Vizio.pairStart(h) { why -> a.runOnUiThread {
            status.text = if (why.isEmpty()) "Type the PIN the TV shows, then Finish pairing" else why
        } }
    }

    private fun pairFinish() {
        val p = pin.text.toString().trim()
        if (p.isEmpty()) return run { status.text = "Type the PIN the TV shows" }
        status.text = "Sending the PIN…"
        Vizio.pairFinish(settings, p) { why -> a.runOnUiThread {
            if (why.isEmpty()) { pin.setText(""); show(); refresh() } else status.text = why
        } }
    }
}

package scouterkit.ssh

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// The Vizio TV's remote on the phone: finding and pairing the TV once (the TV shows a PIN), then the buttons. The
// same remote is on the keyboard with Alt+Y (Vizio.LAYOUT). The TV's address and token stay in Vox's private settings.
class TvActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var pin: EditText
    private lateinit var remote: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val page = page()
        page.addView(header("TV remote", "A Vizio SmartCast TV", "Done") { finish() })

        val pair = card("The TV", "Turn the TV on, on the same Wi-Fi as this phone. Find it, then Pair: the TV shows a " +
            "PIN, type it here. Once.")
        status = text("", 13.5f, Ui.TEXT)
        pair.addView(status)
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

        remote = card("Remote", "Alt+Y on the keyboard turns these into keys: arrows, Enter, Backspace, H, +/-, M, Space, I, " +
            "Page Up/Down, P.")
        fun key(label: String, name: String, kind: Int = 1) = button(label, kind) { send(name) }
        fun row(vararg keys: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, k) in keys.withIndex()) addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) leftMargin = dp(8); topMargin = dp(8) })
        }
        remote.addView(row(key("Power", "POWER", 2), key("Input", "INPUT"), key("Home", "HOME")))
        remote.addView(row(View(this), key("▲", "UP"), View(this)))
        remote.addView(row(key("◀", "LEFT"), key("OK", "OK", 0), key("▶", "RIGHT")))
        remote.addView(row(View(this), key("▼", "DOWN"), View(this)))
        remote.addView(row(key("Back", "BACK"), key("Menu", "MENU"), key("Info", "INFO")))
        remote.addView(row(key("Vol −", "VOL_DOWN"), key("Mute", "MUTE"), key("Vol +", "VOL_UP")))
        remote.addView(row(key("Ch −", "CH_DOWN"), button("Play / pause") { Vizio.playPause(settings) { say(it) } }, key("Ch +", "CH_UP")))
        page.addView(remote)

        // The phone's own mirror to the TV (its Linux's `phone` command), as a tab of the phone's terminal.
        val firefox = card("Firefox on the TV", "Firefox by itself on the TV, worked from the phone (the phone " +
            "command in this phone's Linux). It runs in a tab named tv of the phone's terminal; closing the tab stops " +
            "it. Alt+F on the keyboard too.")
        fun mirror(what: String) {
            if (!ScouterService.running) return run { status.text = "Start Vox first (Glasses tab)" }
            ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to what)
            status.text = if (what == "tvstop") "Stopping the TV mirror" else "Putting Firefox on the TV…"
        }
        firefox.addView(row(button("Firefox on the TV", 0) { mirror("tvtab") }, button("Stop it") { mirror("tvstop") }))
        page.addView(firefox)
        show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun say(why: String) = runOnUiThread { if (why.isNotEmpty()) status.text = why }

    private fun show() {
        val paired = Vizio.paired(settings)
        remote.alpha = if (paired) 1f else 0.4f
        status.text = if (paired) "Paired with ${settings.tvHost}" else "Not paired yet"
    }

    // Whether the TV answers (on or in standby).
    private fun refresh() {
        if (!Vizio.paired(settings)) return show()
        Vizio.status(settings) { s -> runOnUiThread {
            status.text = if (s == null) "Paired with ${settings.tvHost}: not answering (off at the wall, or another Wi-Fi?)"
            else "${settings.tvHost}: the TV is $s"
        } }
    }

    private fun send(name: String) = Vizio.key(settings, name) { say(it) }

    private fun find() {
        status.text = "Looking for the TV on the Wi-Fi…"
        if (Vizio.wifi().first.isEmpty()) return run { status.text = "Not on a Wi-Fi network" }
        Vizio.find { found -> runOnUiThread {
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
        Vizio.pairStart(h) { why -> runOnUiThread {
            status.text = if (why.isEmpty()) "Type the PIN the TV shows, then Finish pairing" else why
        } }
    }

    private fun pairFinish() {
        val p = pin.text.toString().trim()
        if (p.isEmpty()) return run { status.text = "Type the PIN the TV shows" }
        status.text = "Sending the PIN…"
        Vizio.pairFinish(settings, p) { why -> runOnUiThread {
            if (why.isEmpty()) { pin.setText(""); show(); refresh() } else status.text = why
        } }
    }
}

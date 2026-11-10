package scouterkit.ssh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// The glasses' panels over whatever shows: the help (Alt+A: every hotkey and what it does), the quick settings
// (Alt+S: this screen's settings, one at a time) and a note (what a hotkey just did). On the terminal they are
// lines of its own text (Native.overlay); on a screen, a picture in its frames (Native.screenOverlay). Both keep
// running underneath, so a panel comes and goes at once.
object Panels {
    const val HELP = "help"
    const val SETTINGS = "settings"
    const val NOTE = "note"
    const val INTRO = "intro" // the preview mode's title card (Intro)
    const val VOICE = "voice" // voice mode's strip at the bottom (ScouterService.voiceModeSet)
    const val TV = "tv" // the TV remote while the keyboard is the TV's (Alt+Y)
    const val HUB = "hub" // the hub: timer, stopwatch, alarm, reminders, notes (Alt+G)
    const val CAPTIONS = "captions" // live captions (Alt+E) or translation (Alt+I)
    const val HOME = "home" // smart home: the TVs, Home Assistant's lights, switches, thermostats (Alt+O)
    const val PEOPLE = "people" // calls and texts to the starred contacts (Alt+J)
    const val EDITOR = "editor" // the text editor: .txt files in Vox's docs folder (Alt+N)
    const val LISTEN = "listen" // Listen mode: what was heard today (hub L, Alt+E while it is on)

    // The hotkeys by group: the keys, what they do. The help on the glasses and the app's Hotkeys page.
    val GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Screens" to listOf(
            "Alt+arrows" to "move between screens",
            "Alt+1..9" to "jump to a screen",
            "Alt+Q" to "close the screen shown",
        ),
        "Display" to listOf(
            "Alt+0" to "heads-up: look up to see",
            "Alt+L" to "display off / on",
            "Alt+= / -" to "brighter / dimmer",
            "Alt+S" to "quick settings",
            "Alt+A" to "this help (or hold Alt)",
            "Alt+B" to "low battery (10%)",
            "Alt+U" to "Tunes",
            "Alt+D" to "preview mode (demo)",
        ),
        "Media and voice" to listOf(
            "Alt+P" to "play / pause",
            "Alt+, / ." to "previous / next",
            "Alt+Space" to "voice command",
            "Alt+V" to "voice mode: talk to it",
            "Alt+Y" to "TV remote on / off",
        ),
        "Terminal" to listOf(
            "Alt+T" to "new tab",
            "Alt+[ / ]" to "tab before / after",
            "Alt+W" to "close the tab",
            "Alt+K" to "keys: phone / glasses",
            "Alt+X" to "keyboard off / on",
            "Alt+M" to "pointer (arrows, Enter)",
            "Alt+F" to "Firefox on the TV (tab)",
        ),
        "Phone screen" to listOf(
            "Alt+C" to "share it (taps Start)",
            "trackpad" to "tap, drag, 2-finger slide",
            "Alt+Bksp" to "Back",
            "Alt+H" to "Home",
            "Alt+R" to "Recents",
            "Alt+N" to "notifications",
        ),
        "Hub and AI" to listOf(
            "Alt+G" to "hub: timer, alarm, notes",
            "Alt+E" to "captions (Listen: its lines)",
            "Alt+I" to "translate to English",
            "Alt+O" to "smart home",
            "Alt+J" to "calls and texts",
            "Alt+N" to "text editor (.txt files)",
        ),
    )
    private const val LEFT_GROUPS = 3 // Screens, Display, Media in the left column

    // A setting the quick settings change: its name, its value as words, a step (dir -1 or 1).
    class Tweak(val name: String, val value: () -> String, val change: (Int) -> Unit)

    // What a panel shows: lines with marks (0 plain, 1 bold, 2 faint, 3 highlighted), whole (the help) or a strip.
    class Content(val lines: List<String>, val marks: List<Int>, val full: Boolean)

    fun help(): Content = Content(emptyList(), emptyList(), true) // drawn by helpLines / helpArt

    // The quick settings: a heading, the setting before, the chosen one (highlighted), the one after, the keys.
    fun settings(where: String, tweaks: List<Tweak>, at: Int): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        fun item(i: Int) = tweaks.getOrNull(i)?.let { "${it.name}: ${it.value()}" } ?: ""
        lines += "QUICK SETTINGS · $where   ${at + 1}/${tweaks.size}"; marks += 1
        lines += "   " + item(at - 1); marks += 2
        lines += " < " + item(at) + " >"; marks += 3
        lines += "   " + item(at + 1); marks += 2
        lines += "Up/Down: setting   Left/Right: change   Esc: done"; marks += 2
        return Content(lines, marks, false)
    }

    fun note(text: String) = Content(listOf(text), listOf(1), false)

    // Voice mode's strip: that it is on, the way out, what it hears or did (the line is cut at the width).
    fun voice(text: String) = Content(listOf("■ VOICE · Alt+V off › $text"), listOf(1), false)

    // The help as the terminal's lines (cols x rows): a title, then two columns of groups, then the keys to close.
    private fun closes(held: Boolean) = if (held) "let go of Alt to close" else "Alt+A or Esc closes"

    fun helpLines(cols: Int, rows: Int, held: Boolean = false): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        val close = closes(held)
        val w = cols.coerceAtLeast(30)
        // The banner: a chamfered bar with the name, glitch blocks, and what closes it.
        val left = "┏━[ VOX//HOTKEYS ]░▒▓█"
        val right = "█▓▒░[ $close ]━┓"
        lines += (left + "━".repeat(maxOf(0, w - left.length - right.length)) + right).take(w); marks += 1
        val half = if (w >= 60) (w - 3) / 2 else w - 2
        fun column(groups: List<Pair<String, List<Pair<String, String>>>>): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            for ((i, g) in groups.withIndex()) {
                if (i > 0) out += "" to 0
                // The group's name on a tag, a rule running off it (thin: a full bar would light half the panel).
                val name = "▌▌ ${g.first.uppercase()} ▐░▒▓ "
                out += (name + "─".repeat(maxOf(0, half - name.length - 1))).take(half) to 1
                for ((k, what) in g.second) out += (" › " + k.padEnd(10) + what).take(half) to 0
            }
            return out
        }
        val l = column(GROUPS.take(LEFT_GROUPS))
        val r = column(GROUPS.drop(LEFT_GROUPS))
        val body = if (half == w - 2) listOf(l + listOf("" to 0) + r) else listOf(l, r)
        if (body.size == 1) {
            for ((t, m) in body[0]) { lines += "┃" + t.padEnd(w - 2) + "┃"; marks += m }
        } else {
            for (i in 0 until maxOf(l.size, r.size)) {
                val a2 = l.getOrNull(i)
                val b2 = r.getOrNull(i)
                lines += "┃" + (a2?.first ?: "").padEnd(half) + "┃" + (b2?.first ?: "").padEnd(w - half - 3) + "┃"
                marks += if ((a2?.second ?: 0) == 1 || (b2?.second ?: 0) == 1) 1 else 0
            }
        }
        val foot = "┗━ ▓▒░ SYS.READY // Pocket mode: the trackpad points, the keyboard types "
        val end = "░▒▓ ━┛"
        lines += (foot + "━".repeat(maxOf(0, w - foot.length - end.length)) + end).take(w); marks += 2
        return Content(lines.take(maxOf(3, rows)), marks.take(maxOf(3, rows)), true)
    }

    // A picture for a screen's frames: w x h, 0 or 255 a pixel (black and white on the glasses, so no smoothing),
    // at (x, y) of the picture.
    class Art(val pixels: ByteArray, val w: Int, val h: Int, val x: Int, val y: Int)

    private fun paint(size: Float, bold: Boolean = false) = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun pixels(b: Bitmap): ByteArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = ByteArray(px.size)
        for (i in px.indices) if ((px[i] and 0xFF) >= 128) out[i] = 0xFF.toByte()
        b.recycle()
        return out
    }

    // The help over the whole picture: a title bar, two columns of groups (the keys bold), the way out. Kept for
    // the next time (holding Alt brings it often): it shows without being drawn again.
    private var helpKept: Pair<String, Art>? = null

    fun helpArt(w: Int, h: Int, held: Boolean = false): Art {
        val id = "$w:$h:$held"
        helpKept?.let { if (it.first == id) return it.second }
        return drawHelp(w, h, held).also { helpKept = id to it }
    }

    private fun drawHelp(w: Int, h: Int, held: Boolean): Art {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val bar = paint(0f).apply { style = Paint.Style.FILL }
        val line = paint(0f).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        // The banner: a lit bar with a cut corner, the name in black on it, glitch blocks, the way out.
        val ban = android.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(w - 36f, 0f); lineTo(w.toFloat(), 30f); lineTo(0f, 30f); close()
        }
        c.drawPath(ban, bar)
        val title = paint(19f, true).apply { color = Color.BLACK }
        c.drawText("VOX//HOTKEYS", 14f, 22f, title)
        val close = paint(13f).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT }
        c.drawText(closes(held), w - 40f, 21f, close)
        // Glitch blocks between the name and the way out, thinning out (never over either).
        val ink = paint(0f).apply { style = Paint.Style.FILL; color = Color.BLACK }
        val stop = w - 40f - close.measureText(closes(held)) - 14f
        var gx = 14f + title.measureText("VOX//HOTKEYS") + 16f
        var i = 0
        while (gx + 10f < stop) {
            c.drawRect(gx, 6f, gx + 4f + (i % 3) * 3f, 24f, ink)
            gx += 12f + (i * 5) % 11 + i * 2
            i++
        }
        // Scanlines under the banner, thicker towards the middle.
        for (i in 0 until 4) c.drawRect(0f, 34f + i * 3f, w * (1f - i * 0.22f), 35f + i * 3f, bar)
        val head = paint(15f, true).apply { color = Color.BLACK }
        val key = paint(14f, true)
        val text = paint(14f)
        val most = maxOf(GROUPS.take(LEFT_GROUPS).sumOf { it.second.size }, GROUPS.drop(LEFT_GROUPS).sumOf { it.second.size })
        val groups = maxOf(LEFT_GROUPS, GROUPS.size - LEFT_GROUPS)
        val rowH = minOf(19f, (h - 66f - 34f - groups * 29f) / maxOf(1, most))
        val colW = w / 2f
        val keyW = minOf(96f, colW * 0.38f)
        fun column(groups: List<Pair<String, List<Pair<String, String>>>>, x0: Float) {
            var y = 66f
            for ((i, g) in groups.withIndex()) {
                if (i > 0) y += 6f
                // The group's name on a lit tag, a hatch running off it.
                val tagW = head.measureText(g.first.uppercase()) + 26f
                c.drawRect(x0, y - 14f, x0 + tagW, y + 4f, bar)
                c.drawPath(android.graphics.Path().apply {
                    moveTo(x0 + tagW, y - 14f); lineTo(x0 + tagW + 10f, y + 4f); lineTo(x0 + tagW, y + 4f); close()
                }, bar)
                c.drawText(g.first.uppercase(), x0 + 8f, y, head)
                var hx = x0 + tagW + 16f
                while (hx < x0 + colW - 24f) { c.drawLine(hx, y + 4f, hx + 6f, y - 14f, line.apply { strokeWidth = 1f }); hx += 7f }
                y += rowH + 4f
                for ((k, what) in g.second) {
                    c.drawRect(x0, y - 11f, x0 + 3f, y + 2f, bar) // a lit tick before each key
                    c.drawText(k, x0 + 10f, y, key)
                    c.drawText(what, x0 + 10f + keyW, y, text)
                    y += rowH
                }
            }
        }
        column(GROUPS.take(LEFT_GROUPS), 14f)
        column(GROUPS.drop(LEFT_GROUPS), colW + 8f)
        // A divider down the middle with notches, corner brackets, the status line.
        line.strokeWidth = 2f
        c.drawLine(colW, 44f, colW, h - 30f, line)
        for (i in 0 until 6) c.drawRect(colW - 4f, 60f + i * 62f, colW + 4f, 64f + i * 62f, bar)
        c.drawLine(2f, h - 26f, 22f, h - 26f, line); c.drawLine(2f, h - 26f, 2f, h - 4f, line)
        c.drawLine(w - 2f, h - 26f, w - 22f, h - 26f, line); c.drawLine(w - 2f, h - 26f, w - 2f, h - 4f, line)
        c.drawText("SYS.READY //  pocket mode: the trackpad points and taps, the keyboard types", 30f, h - 10f, paint(12f))
        return Art(pixels(b), w, h, 0, 0)
    }

    // ------------------------------------------------ the TV remote on the glasses (Alt+Y)

    // A Vizio.LAYOUT row's keys (its second field, "UP/DOWN/LEFT/RIGHT", "VOL"...) took the key sent last (lit: its
    // Vizio name, VOL_UP and the like).
    private fun lit(field: String, lit: String) = lit.isNotEmpty() && field.split("/").any { lit == it || lit.startsWith(it + "_") }

    // The remote as the terminal's lines (cols x rows), while the keyboard is the TV's: the arrows and OK as a pad
    // (the key pressed last filled for a moment), the other keys as a list (its row marked), how the TV is.
    fun tvLines(cols: Int, rows: Int, status: String, lit: String): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        val w = cols.coerceAtLeast(40)
        val left = "┏━[ VOX//TV REMOTE ]░▒▓█"
        val right = "█▓▒░[ Alt+Y: keyboard back ]━┓"
        lines += (left + "━".repeat(maxOf(0, w - left.length - right.length)) + right).take(w); marks += 1
        fun c(label: String, key: String): String { // a pad cell, 7 wide
            if (lit == key) return " █████ "
            val l = (7 - label.length) / 2
            return " ".repeat(l) + label + " ".repeat(7 - l - label.length)
        }
        val pad = listOf(
            "        ┌───────┐",
            "        │" + c("↑", "UP") + "│",
            "┌───────┼───────┼───────┐",
            "│" + c("←", "LEFT") + "│" + c("OK", "OK") + "│" + c("→", "RIGHT") + "│",
            "└───────┼───────┼───────┘",
            "        │" + c("↓", "DOWN") + "│",
            "        └───────┘",
        )
        val keys = mutableListOf<Pair<String, Int>>()
        val tag = "▌▌ KEYS ▐░▒▓ "
        keys += tag to 1
        for ((k, field, what) in Vizio.LAYOUT) {
            val on = lit(field, lit)
            keys += ((if (on) " ■ " else " › ") + k.padEnd(17) + what) to (if (on) 1 else 0)
        }
        keys += (" › " + "Alt+F".padEnd(17) + "Firefox on the TV") to 0
        val padW = 27
        val body = mutableListOf<Pair<String, Int>>()
        if (w >= padW + 40) {
            for (i in 0 until maxOf(pad.size + 1, keys.size)) {
                val a = if (i in 1..pad.size) "  " + pad[i - 1] else ""
                val b = keys.getOrNull(i)
                body += (a.padEnd(padW) + (b?.first ?: "")) to (b?.second ?: 0)
            }
        } else {
            for (p in pad) body += ("  $p") to 0
            body += "" to 0
            body += keys
        }
        // The apps of the keys 1 ... 9, 0, as many to a line as fit.
        body += "" to 0
        body += "▌▌ APPS ▐░▒▓ " to 1
        var appLine = ""
        for ((i, app) in Vizio.APPS.withIndex()) {
            val item = "${(i + 1) % 10} ${app.name}"
            if (appLine.isNotEmpty() && appLine.length + 3 + item.length > w - 6) {
                body += " $appLine" to 0
                appLine = ""
            }
            appLine += (if (appLine.isEmpty()) "" else "   ") + item
        }
        if (appLine.isNotEmpty()) body += " $appLine" to 0
        val stat = "▌▌ TV ▐░▒▓ " + status
        lines += ("┃ " + stat.take(w - 4).padEnd(w - 4) + " ┃"); marks += 1
        lines += "┃" + " ".repeat(w - 2) + "┃"; marks += 0
        for ((t, m) in body) {
            lines += "┃ " + t.take(w - 4).padEnd(w - 4) + " ┃"
            marks += m
        }
        val foot = "┗━ ▓▒░ every key goes to the TV // Vox's Alt keys still work "
        val end = "░▒▓ ━┛"
        lines += (foot + "━".repeat(maxOf(0, w - foot.length - end.length)) + end).take(w); marks += 2
        return Content(lines.take(maxOf(3, rows)), marks.take(maxOf(3, rows)), true)
    }

    // The same remote drawn for a screen's frames (the phone's or the laptop's picture), w x h.
    fun tvArt(w: Int, h: Int, status: String, lit: String): Art {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val bar = paint(0f).apply { style = Paint.Style.FILL }
        val line = paint(0f).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        // The banner, as the help's: a lit bar with a cut corner, the name in black, the way out.
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(w - 36f, 0f); lineTo(w.toFloat(), 30f); lineTo(0f, 30f); close()
        }, bar)
        c.drawText("VOX//TV REMOTE", 14f, 22f, paint(19f, true).apply { color = Color.BLACK })
        c.drawText("Alt+Y: keyboard back", w - 40f, 21f, paint(13f).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT })
        for (i in 0 until 4) c.drawRect(0f, 34f + i * 3f, w * (1f - i * 0.22f), 35f + i * 3f, bar)
        c.drawText(status.take(60), 14f, 66f, paint(15f, true))
        // The pad: five keys, the one pressed last lit.
        val s = minOf(54f, (h - 120f) / 3.4f)
        val cx = 20f + s * 1.6f
        val cy = 96f + s * 1.6f
        fun key(x: Float, y: Float, label: String, name: String) {
            val r = android.graphics.RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
            val on = lit == name
            if (on) c.drawRoundRect(r, 8f, 8f, bar) else c.drawRoundRect(r, 8f, 8f, line)
            val p = paint(s * 0.42f, true).apply { textAlign = Paint.Align.CENTER; if (on) color = Color.BLACK }
            c.drawText(label, x, y + s * 0.15f, p)
        }
        key(cx, cy - s * 1.1f, "↑", "UP")
        key(cx - s * 1.1f, cy, "←", "LEFT")
        key(cx, cy, "OK", "OK")
        key(cx + s * 1.1f, cy, "→", "RIGHT")
        key(cx, cy + s * 1.1f, "↓", "DOWN")
        // The other keys as a list, the one pressed last on a lit bar.
        val x0 = cx + s * 1.9f
        val rowH = minOf(22f, (h - 150f) / (Vizio.LAYOUT.size + 1))
        var y = 92f
        val k = paint(14f, true)
        val t = paint(14f)
        for ((keys, field, what) in Vizio.LAYOUT + listOf(Triple("Alt+F", "", "Firefox on the TV"))) {
            if (lit(field, lit)) {
                c.drawRect(x0 - 4f, y - 15f, w - 10f, y + 5f, bar)
                c.drawText(keys, x0, y, Paint(k).apply { color = Color.BLACK })
                c.drawText(what, x0 + 130f, y, Paint(t).apply { color = Color.BLACK })
            } else {
                c.drawRect(x0 - 4f, y - 11f, x0 - 1f, y + 2f, bar)
                c.drawText(keys, x0 + 4f, y, k)
                c.drawText(what, x0 + 130f, y, t)
            }
            y += rowH
        }
        // The apps of the keys 1 ... 9, 0, two lines over the footer.
        val apps = Vizio.APPS.mapIndexed { i, app -> "${(i + 1) % 10} ${app.name}" }
        val ap = paint(13f, true)
        c.drawText(apps.take(5).joinToString("   "), 14f, h - 52f, ap)
        c.drawText(apps.drop(5).joinToString("   "), 14f, h - 34f, ap)
        c.drawLine(2f, h - 26f, 22f, h - 26f, line); c.drawLine(2f, h - 26f, 2f, h - 4f, line)
        c.drawLine(w - 2f, h - 26f, w - 22f, h - 26f, line); c.drawLine(w - 2f, h - 26f, w - 2f, h - 4f, line)
        c.drawText("every key goes to the TV  //  Vox's Alt keys still work", 30f, h - 10f, paint(12f))
        return Art(pixels(b), w, h, 0, 0)
    }

    // ------------------------------------------------ the hub on the glasses (Alt+G)

    // The hub's rows (ScouterService.hubRows): text and a mark (0 plain, 1 bold, 2 faint, 3 lit: the one being typed
    // into). As the terminal's lines inside the frame, or (hubArt) drawn for a screen's frames.
    fun hubLines(cols: Int, rows: Int, items: List<Pair<String, Int>>): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        val w = cols.coerceAtLeast(40)
        val left = "┏━[ VOX//HUB ]░▒▓█"
        val right = "█▓▒░[ Esc closes ]━┓"
        lines += (left + "━".repeat(maxOf(0, w - left.length - right.length)) + right).take(w); marks += 1
        for ((t, m) in items) {
            lines += "┃ " + t.take(w - 4).padEnd(w - 4) + " ┃"
            marks += m
        }
        val foot = "┗━ ▓▒░ T timer · P pause · S watch · A alarm · R remind · N note · E editor · L listen "
        val end = "░▒▓ ━┛"
        lines += (foot + "━".repeat(maxOf(0, w - foot.length - end.length)) + end).take(w); marks += 2
        return Content(lines.take(maxOf(3, rows)), marks.take(maxOf(3, rows)), true)
    }

    fun hubArt(w: Int, h: Int, items: List<Pair<String, Int>>): Art {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val bar = paint(0f).apply { style = Paint.Style.FILL }
        val line = paint(0f).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(w - 36f, 0f); lineTo(w.toFloat(), 30f); lineTo(0f, 30f); close()
        }, bar)
        c.drawText("VOX//HUB", 14f, 22f, paint(19f, true).apply { color = Color.BLACK })
        c.drawText("Esc closes", w - 40f, 21f, paint(13f).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT })
        for (i in 0 until 4) c.drawRect(0f, 34f + i * 3f, w * (1f - i * 0.22f), 35f + i * 3f, bar)
        val rowH = minOf(20f, (h - 90f) / maxOf(1, items.size))
        var y = 64f
        for ((t, m) in items) {
            val text = t.replace(Regex("[▌▐░▒▓]"), "").trim()
            when (m) {
                3 -> {
                    c.drawRect(8f, y - 14f, w - 8f, y + 5f, bar)
                    c.drawText(text, 14f, y, paint(14f, true).apply { color = Color.BLACK })
                }
                1 -> c.drawText(text, 10f, y, paint(14f, true))
                else -> c.drawText(text, 10f, y, paint(13.5f))
            }
            y += rowH
        }
        c.drawLine(2f, h - 26f, 22f, h - 26f, line); c.drawLine(2f, h - 26f, 2f, h - 4f, line)
        c.drawLine(w - 2f, h - 26f, w - 22f, h - 26f, line); c.drawLine(w - 2f, h - 26f, w - 2f, h - 4f, line)
        c.drawText("T timer · P pause · S watch · A alarm · R remind · N note · E editor · L listen · Shift: undo", 30f, h - 10f, paint(12f))
        return Art(pixels(b), w, h, 0, 0)
    }

    // ------------------------------------------------ a full panel's frame (the hub, captions, smart home, calls)

    // Rows (text, mark: 0 plain, 1 bold, 2 faint, 3 lit) inside the frame with a title bar and a footer, as the
    // terminal's lines.
    fun frameLines(cols: Int, rows: Int, title: String, close: String, items: List<Pair<String, Int>>, foot: String): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        val w = cols.coerceAtLeast(40)
        val left = "┏━[ $title ]░▒▓█"
        val right = "█▓▒░[ $close ]━┓"
        lines += (left + "━".repeat(maxOf(0, w - left.length - right.length)) + right).take(w); marks += 1
        for ((t, m) in items.take(maxOf(1, rows - 2))) {
            lines += "┃ " + t.take(w - 4).padEnd(w - 4) + " ┃"
            marks += m
        }
        val f = "┗━ ▓▒░ $foot "
        val end = "░▒▓ ━┛"
        lines += (f + "━".repeat(maxOf(0, w - f.length - end.length)) + end).take(w); marks += 2
        return Content(lines.take(maxOf(3, rows)), marks.take(maxOf(3, rows)), true)
    }

    // The same rows drawn for a screen's frames (w x h): the title on a lit bar, the rows, the footer.
    fun frameArt(w: Int, h: Int, title: String, close: String, items: List<Pair<String, Int>>, foot: String): Art {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val bar = paint(0f).apply { style = Paint.Style.FILL }
        val line = paint(0f).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(w - 36f, 0f); lineTo(w.toFloat(), 30f); lineTo(0f, 30f); close()
        }, bar)
        c.drawText(title, 14f, 22f, paint(19f, true).apply { color = Color.BLACK })
        c.drawText(close, w - 40f, 21f, paint(13f).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT })
        for (i in 0 until 4) c.drawRect(0f, 34f + i * 3f, w * (1f - i * 0.22f), 35f + i * 3f, bar)
        val rowH = minOf(20f, (h - 90f) / maxOf(1, items.size))
        var y = 64f
        for ((t, m) in items) {
            val text = t.replace(Regex("[▌▐░▒▓]"), "").trim()
            when (m) {
                3 -> {
                    c.drawRect(8f, y - 14f, w - 8f, y + 5f, bar)
                    c.drawText(text, 14f, y, paint(14f, true).apply { color = Color.BLACK })
                }
                1 -> c.drawText(text, 10f, y, paint(14f, true))
                else -> c.drawText(text, 10f, y, paint(13.5f))
            }
            y += rowH
        }
        c.drawLine(2f, h - 26f, 22f, h - 26f, line); c.drawLine(2f, h - 26f, 2f, h - 4f, line)
        c.drawLine(w - 2f, h - 26f, w - 22f, h - 26f, line); c.drawLine(w - 2f, h - 26f, w - 2f, h - 4f, line)
        c.drawText(foot, 30f, h - 10f, paint(12f))
        return Art(pixels(b), w, h, 0, 0)
    }

    // Words cut into lines of at most `width` characters (at spaces where it can).
    fun wrap(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            var wd = word
            while (wd.length > width) {
                if (line.isNotEmpty()) { out += line; line = "" }
                out += wd.take(width)
                wd = wd.drop(width)
            }
            line = if (line.isEmpty()) wd else if (line.length + 1 + wd.length <= width) "$line $wd" else { out += line; wd }
        }
        if (line.isNotEmpty() || out.isEmpty()) out += line
        return out
    }

    // A strip at the bottom of the picture: a rule, then the lines (highlighted ones on a lit bar).
    fun stripArt(w: Int, h: Int, content: Content): Art {
        val line = 20
        val sh = minOf(h, 8 + line * content.lines.size + 4)
        val b = Bitmap.createBitmap(w, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val lit = paint(0f).apply { style = Paint.Style.FILL }
        c.drawRect(0f, 1f, w.toFloat(), 3f, lit)
        var y = 8 + line - 5
        for ((i, t) in content.lines.withIndex()) {
            val mark = content.marks.getOrElse(i) { 0 }
            val p = when (mark) {
                1, 3 -> paint(16f, true)
                2 -> paint(14f)
                else -> paint(15f)
            }
            if (mark == 3) {
                c.drawRect(4f, (y - line + 6).toFloat(), w - 4f, (y + 5).toFloat(), lit)
                p.color = Color.BLACK
            }
            c.drawText(t, 10f, y.toFloat(), p)
            y += line
        }
        return Art(pixels(b), w, sh, 0, h - sh)
    }
}

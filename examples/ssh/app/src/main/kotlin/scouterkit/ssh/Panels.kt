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
        val rowH = 19f
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

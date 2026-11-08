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

    // The hotkeys by group: the keys, what they do. The help on the glasses and the app's Hotkeys page.
    val GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Screens" to listOf(
            "Alt+arrows" to "move between screens",
            "Alt+1..9" to "jump to a screen",
            "Alt+Q" to "close the screen shown",
        ),
        "Display" to listOf(
            "Alt+0" to "display off / on",
            "Alt+Z" to "always on / look-up only",
            "Alt+= / -" to "brighter / dimmer",
            "Alt+S" to "quick settings",
            "Alt+A" to "this help (or hold Alt)",
            "Alt+U" to "Tunes",
        ),
        "Media and voice" to listOf(
            "Alt+P" to "play / pause",
            "Alt+, / ." to "previous / next",
            "Alt+Space" to "voice command",
        ),
        "Terminal" to listOf(
            "Alt+T" to "new tab",
            "Alt+[ / ]" to "tab before / after",
            "Alt+W" to "close the tab",
            "Alt+K" to "keys: phone / glasses",
            "Alt+M" to "pointer (arrows, Enter)",
        ),
        "Phone screen" to listOf(
            "trackpad" to "point, tap, scroll",
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

    // The help as the terminal's lines (cols x rows): a title, then two columns of groups, then the keys to close.
    private fun closes(held: Boolean) = if (held) "let go of Alt to close" else "Alt+A or Esc closes"

    fun helpLines(cols: Int, rows: Int, held: Boolean = false): Content {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<Int>()
        val close = closes(held)
        lines += "VOX HOTKEYS".padEnd(maxOf(0, cols - close.length - 3)) + close; marks += 1
        lines += "─".repeat(cols); marks += 2
        val half = if (cols >= 60) (cols - 1) / 2 else cols
        fun column(groups: List<Pair<String, List<Pair<String, String>>>>): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            for ((i, g) in groups.withIndex()) {
                if (i > 0) out += "" to 0
                out += g.first.uppercase() to 1
                for ((k, what) in g.second) out += (" " + k.padEnd(11) + what).take(half) to 0
            }
            return out
        }
        val left = column(GROUPS.take(LEFT_GROUPS))
        val right = column(GROUPS.drop(LEFT_GROUPS))
        if (half == cols) { // narrow: one column, as far as it goes
            for ((t, m) in left + listOf("" to 0) + right) {
                lines += t
                marks += m
            }
        } else {
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i)
                val r = right.getOrNull(i)
                lines += (l?.first ?: "").padEnd(half + 1) + (r?.first ?: "")
                // A heading on either side makes the line bold (the terminal marks whole lines).
                marks += if ((l?.second ?: 0) == 1 || (r?.second ?: 0) == 1) 1 else 0
            }
        }
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
        c.drawRect(0f, 0f, w.toFloat(), 30f, bar)
        val title = paint(19f, true).apply { color = Color.BLACK }
        c.drawText("VOX · HOTKEYS", 12f, 22f, title)
        val close = paint(13f).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT }
        c.drawText(closes(held), w - 10f, 21f, close)
        val head = paint(15f, true)
        val key = paint(14f, true)
        val text = paint(14f)
        val line = 19f
        val colW = w / 2f
        val keyW = minOf(96f, colW * 0.38f)
        fun column(groups: List<Pair<String, List<Pair<String, String>>>>, x0: Float) {
            var y = 54f
            for ((i, g) in groups.withIndex()) {
                if (i > 0) y += 8f
                c.drawText(g.first.uppercase(), x0, y, head)
                c.drawRect(x0, y + 4f, x0 + colW - 24f, y + 5f, bar)
                y += line + 4f
                for ((k, what) in g.second) {
                    c.drawText(k, x0, y, key)
                    c.drawText(what, x0 + keyW, y, text)
                    y += line
                }
            }
        }
        column(GROUPS.take(LEFT_GROUPS), 12f)
        column(GROUPS.drop(LEFT_GROUPS), colW + 6f)
        c.drawText("Pocket mode: the trackpad points and taps, the keyboard types into the phone.", 12f, h - 10f, paint(12f))
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

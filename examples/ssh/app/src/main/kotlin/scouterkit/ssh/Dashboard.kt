package scouterkit.ssh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The look-up dashboard above the terminal (Native.dash): drawn here at the glasses' own pixels in their 16
// levels (black is see-through), sent as 4 bpp. A large thin clock with a soft glow and the date; a card with
// the newest notification (or, while voice is in use, what is being heard); a ring gauge for each arm's
// battery; corner brackets and a ticked rule under it all.
object Dashboard {
    // hits: where the tab bar's tabs and its + are (left to right), for the pointer.
    class Frame(val packed: ByteArray, val preview: Bitmap, val hits: List<Hit> = emptyList())

    // A place on the tab bar the pointer can click: its pixels across, and its action ("select:N", "new").
    class Hit(val left: Float, val right: Float, val action: String) {
        val center get() = (left + right) / 2
    }

    private fun grey(level: Int) = Color.rgb(level * 17, level * 17, level * 17)

    private fun paint(size: Float, level: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        color = grey(level)
        typeface = face
        letterSpacing = spacing
    }

    private val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    // voice: what the voice band says (null: none); listening: the microphone is on; hover: the action of the
    // tab bar's place the pointer is on (outlined and marked), or null.
    fun render(context: Context, settings: Settings, w: Int, h: Int, voice: String?, listening: Boolean,
               hover: String? = null): Frame {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val pad = 5f
        val line = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f }
        val fill = Paint().apply { isAntiAlias = true }

        // The frame: corner brackets.
        line.color = grey(9)
        val k = 8f
        for ((x, dx) in listOf(0.5f to 1f, w - 0.5f to -1f)) {
            c.drawLine(x, 0.5f, x + dx * k, 0.5f, line)
            c.drawLine(x, 0.5f, x, k, line)
            c.drawLine(x, h - 0.5f, x + dx * k, h - 0.5f, line)
            c.drawLine(x, h - 0.5f, x, h - k, line)
        }

        // The tab bar: the terminal's name ("PHONE TMUX"), its tabs (the one shown filled), and a + for another.
        val barH = maxOf(14f, h * 0.18f)
        val barY = pad
        val server = settings.active()
        val tabs = Session.tabList
        val tmux = server != null && Tabs.session(server) != null
        val label = paint(barH * 0.62f, 9, CONDENSED, 0.18f)
        val name = ((server?.name ?: "Terminal") + if (tmux) " tmux" else "").uppercase(Locale.getDefault())
        var x = pad + 6f
        val textY = barY + barH * 0.72f
        c.drawText(name, x, textY, label)
        x += label.measureText(name) + 10f
        val tabText = paint(barH * 0.66f, 13, MEDIUM)
        val dark = paint(barH * 0.66f, 0, MEDIUM)
        val room = w - pad - 6f - (barH + 6f) // the + at the end
        var shown = 0
        val hits = mutableListOf<Hit>()
        val mark = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f; color = grey(15) }
        // The pointer on a place: a bright ring just outside it and a notch under it.
        fun hovered(box: RectF) {
            c.drawRoundRect(RectF(box.left - 2f, box.top - 1.5f, box.right + 2f, box.bottom + 1.5f), 5f, 5f, mark)
            fill.color = grey(15)
            val notch = Path().apply {
                moveTo(box.centerX() - 3f, box.bottom + 4f)
                lineTo(box.centerX() + 3f, box.bottom + 4f)
                lineTo(box.centerX(), box.bottom + 1.5f)
                close()
            }
            c.drawPath(notch, fill)
        }
        for (t in tabs) {
            val text = "${t.index} " + TextUtils.ellipsize(t.name, tabText, barH * 6f, TextUtils.TruncateAt.END)
            val tw = tabText.measureText(text) + 12f
            if (x + tw > room) {
                c.drawText("…", x, textY, tabText)
                x += tabText.measureText("…") + 6f
                break
            }
            val box = RectF(x, barY, x + tw, barY + barH)
            if (t.active) {
                fill.color = grey(14)
                c.drawRoundRect(box, 4f, 4f, fill)
                c.drawText(text, x + 6f, textY, dark)
            } else {
                line.color = grey(5)
                c.drawRoundRect(box, 4f, 4f, line)
                c.drawText(text, x + 6f, textY, tabText)
            }
            val action = "select:${t.index}"
            hits += Hit(box.left, box.right, action)
            if (hover == action) hovered(box)
            x += tw + 5f
            shown++
        }
        if (tmux) { // +: another tab (Alt+T, "new tab", the TouchBar)
            val box = RectF(x, barY, x + barH, barY + barH)
            line.color = grey(8)
            c.drawRoundRect(box, 4f, 4f, line)
            val on = hover == "new"
            if (on) {
                fill.color = grey(14)
                c.drawRoundRect(box, 4f, 4f, fill)
            }
            val plus = paint(barH * 0.8f, if (on) 0 else 13, LIGHT).apply { textAlign = Paint.Align.CENTER }
            c.drawText("+", box.centerX(), textY + barH * 0.06f, plus)
            hits += Hit(box.left, box.right, "new")
            if (on) hovered(box)
        }
        line.color = grey(3)
        val ruleY = barY + barH + 3.5f
        c.drawLine(pad + 6f, ruleY, w - pad - 6f, ruleY, line)

        // The row under it.
        val top = ruleY + 3f
        val rowH = h - top - pad
        // Left: the clock with a dim halo, the date under it.
        var left = pad + 6f
        if (settings.lookUpClock) {
            val now = Date()
            val h24 = android.text.format.DateFormat.is24HourFormat(context)
            val clock = SimpleDateFormat(if (h24) "H:mm" else "h:mm", Locale.getDefault()).format(now)
            val ampm = if (h24) "" else SimpleDateFormat("a", Locale.US).format(now).uppercase(Locale.US)
            val date = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
            val big = paint(rowH * 0.58f, 15, THIN).apply { setShadowLayer(4f, 0f, 0f, grey(5)) }
            val base = top + rowH * 0.6f
            c.drawText(clock, left, base, big)
            val cw = big.measureText(clock)
            val ap = paint(rowH * 0.17f, 9, MEDIUM, 0.08f)
            if (ampm.isNotEmpty()) c.drawText(ampm, left + cw + 3f, top + rowH * 0.27f, ap)
            val small = paint(rowH * 0.17f, 10, CONDENSED, 0.2f)
            c.drawText(date, left + 1f, top + rowH * 0.92f, small)
            left += maxOf(cw + (if (ampm.isNotEmpty()) ap.measureText(ampm) + 5f else 0f), small.measureText(date)) + 12f
            line.color = grey(3)
            c.drawLine(left - 6f, top + 2f, left - 6f, top + rowH - 2f, line)
        }

        // Right: each arm's battery, small: a letter, a bar, the level.
        val bw = 30f
        val bh = maxOf(6f, rowH * 0.13f)
        val pct = paint(rowH * 0.2f, 13, MEDIUM).apply { textAlign = Paint.Align.RIGHT }
        val letter = paint(rowH * 0.17f, 8, CONDENSED)
        val right = w - pad - 6f
        val battLeft = right - pct.measureText("100%") - 4f - bw - 4f - letter.measureText("L")
        val b = Session.battery
        for (i in 0..1) {
            val cy = top + rowH * (if (i == 0) 0.32f else 0.74f)
            val level = b.getOrElse(i) { -1 }
            c.drawText(if (i == 0) "L" else "R", battLeft, cy + letter.textSize * 0.35f, letter)
            val bx = battLeft + letter.measureText("L") + 4f
            val box = RectF(bx, cy - bh / 2, bx + bw, cy + bh / 2)
            line.color = grey(6)
            c.drawRoundRect(box, 2f, 2f, line)
            fill.color = grey(6)
            c.drawRect(box.right + 0.5f, cy - bh / 4, box.right + 2f, cy + bh / 4, fill) // the cap
            if (level >= 0) {
                fill.color = grey(if (level <= 15) 15 else 12)
                c.drawRect(box.left + 1.5f, box.top + 1.5f, box.left + 1.5f + (bw - 3f) * level.coerceIn(0, 100) / 100f, box.bottom - 1.5f, fill)
            }
            val charging = b.getOrElse(i + 2) { 0 } == 1
            c.drawText(if (level >= 0) "$level%" + if (charging) "+" else "" else "–", right, cy + pct.textSize * 0.36f, pct)
        }

        // Middle: voice while it is in use, else the newest three notifications.
        val mx = left
        val mRight = battLeft - 12f
        val mw = mRight - mx
        if (mw > 80f) {
            line.color = grey(3)
            c.drawLine(mRight + 6f, top + 2f, mRight + 6f, top + rowH - 2f, line)
            val app = paint(rowH * 0.16f, 9, CONDENSED, 0.15f)
            val title = paint(rowH * 0.19f, 15, MEDIUM)
            val body = paint(rowH * 0.18f, 10, LIGHT)
            val lineH = rowH / 3f
            if (voice != null) {
                c.drawText(if (listening) "\u25CF LISTENING" else "VOICE", mx, top + lineH * 0.62f, app)
                val words = voice.replace(ScouterService.MIC, "").trim()
                val lines = wrap(words, title, mw).takeLast(2)
                for ((i, l) in lines.withIndex()) c.drawText(l, mx, top + lineH * (1.62f + i), title)
            } else if (settings.lookUpNotifications) {
                val notes = LookUp.notes.take(3)
                if (notes.isEmpty()) {
                    c.drawText("NOTIFICATIONS", mx, top + lineH * 0.62f, app)
                    c.drawText("All clear", mx, top + lineH * 1.62f, paint(rowH * 0.19f, 8, LIGHT))
                }
                val appW = minOf(mw * 0.3f, app.measureText("MESSAGES") + 6f)
                for ((i, n) in notes.withIndex()) {
                    val y = top + lineH * (i + 0.66f)
                    c.drawText(TextUtils.ellipsize(n.app.uppercase(Locale.getDefault()), app, appW - 4f, TextUtils.TruncateAt.END).toString(), mx, y, app)
                    val head = n.title.ifBlank { n.app }
                    val tx = mx + appW
                    val avail = mw - appW
                    val headText = TextUtils.ellipsize(head, title, avail * 0.55f, TextUtils.TruncateAt.END).toString()
                    c.drawText(headText, tx, y, title)
                    if (n.text.isNotBlank()) {
                        val rest = avail - title.measureText(headText) - 6f
                        if (rest > 20f) c.drawText(TextUtils.ellipsize(n.text.replace('\n', ' '), body, rest, TextUtils.TruncateAt.END).toString(),
                            tx + title.measureText(headText) + 6f, y, body)
                    }
                }
                if (LookUp.notes.size > 3) {
                    val more = paint(rowH * 0.15f, 7, CONDENSED).apply { textAlign = Paint.Align.RIGHT }
                    c.drawText("+${LookUp.notes.size - 3}", mRight, top + lineH * 2.66f, more)
                }
            }
        }

        // To the glasses' 4 bpp (two pixels a byte, the left one high), and a preview in the panels' green.
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val stride = w / 2
        val packed = ByteArray(stride * h)
        val green = IntArray(16) { Color.rgb(it * 50 / 15, it * 17, it * 110 / 15) }
        for (y in 0 until h) for (x in 0 until stride * 2) {
            val v = px[y * w + x]
            val luma = (Color.red(v) * 77 + Color.green(v) * 150 + Color.blue(v) * 29) shr 8
            val level = (luma * 15 + 127) / 255
            val i = y * stride + x / 2
            packed[i] = (packed[i].toInt() or (if (x % 2 == 0) (level shl 4) else level)).toByte()
            px[y * w + x] = green[level]
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return Frame(packed, bmp, hits)
    }

    // Words in lines that fit `room` pixels.
    private fun wrap(text: String, p: TextPaint, room: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) <= room || cur.isEmpty()) cur = next else {
                out += cur
                cur = word
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out.ifEmpty { listOf("…") }
    }

    // The dashboard's width for a preview without the glasses (the terminal's, as the native side works it out).
    fun width(settings: Settings) = (settings.cols * 7 + 1) and 1.inv()
}

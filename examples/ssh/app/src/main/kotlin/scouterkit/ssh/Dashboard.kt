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
    class Frame(val packed: ByteArray, val preview: Bitmap)

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

    // voice: what the voice band says (null: none); listening: the microphone is on.
    fun render(context: Context, settings: Settings, w: Int, h: Int, voice: String?, listening: Boolean): Frame {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val pad = 6f
        val line = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f }

        // The frame: corner brackets and a ticked rule along the bottom.
        line.color = grey(11)
        val k = 10f
        for ((x, dx) in listOf(0.5f to 1f, w - 0.5f to -1f)) {
            c.drawLine(x, 0.5f, x + dx * k, 0.5f, line)
            c.drawLine(x, 0.5f, x, k, line)
            c.drawLine(x, h - 1.5f, x + dx * k, h - 1.5f, line)
            c.drawLine(x, h - 1.5f, x, h - k, line)
        }
        line.color = grey(3)
        c.drawLine(k + 4, h - 1.5f, w - k - 4, h - 1.5f, line)
        line.color = grey(6)
        var tx = k + 4 + 24f
        while (tx < w - k - 8) {
            c.drawLine(tx, h - 4.5f, tx, h - 1.5f, line)
            tx += 24f
        }

        // The clock: large and thin, a dim halo around it.
        val now = Date()
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val clock = SimpleDateFormat(if (h24) "H:mm" else "h:mm", Locale.getDefault()).format(now)
        val ampm = if (h24) "" else SimpleDateFormat("a", Locale.US).format(now).uppercase(Locale.US)
        val date = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
        val x0 = pad + 8f
        var right = x0
        if (settings.lookUpClock) {
            val big = paint(h * 0.56f, 15, THIN).apply { setShadowLayer(5f, 0f, 0f, grey(5)) }
            val base = pad + h * 0.53f
            c.drawText(clock, x0, base, big)
            val cw = big.measureText(clock)
            if (ampm.isNotEmpty()) c.drawText(ampm, x0 + cw + 4f, pad + h * 0.2f, paint(h * 0.15f, 9, MEDIUM, 0.08f))
            val small = paint(h * 0.145f, 10, CONDENSED, 0.22f)
            val dateBase = h - pad - 9f
            c.drawText(date, x0 + 1f, dateBase, small)
            // An accent under the date, fading out to the right.
            val dw = small.measureText(date)
            for (i in 0 until 6) {
                line.color = grey(12 - i * 2)
                c.drawLine(x0 + 1f + i * dw / 6, dateBase + 4f, x0 + 1f + (i + 1) * dw / 6, dateBase + 4f, line)
            }
            right = maxOf(x0 + cw + (if (ampm.isNotEmpty()) paint(h * 0.15f, 9, MEDIUM).measureText(ampm) + 6f else 0f), x0 + dw)
        }

        // The batteries: a ring each, the level inside, L and R under them.
        val r = h * 0.27f
        val cy = h * 0.42f
        val cxR = w - pad - r - 6f
        val cxL = cxR - 2 * r - 16f
        val b = Session.battery
        val ring = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 3.5f; strokeCap = Paint.Cap.ROUND }
        for ((i, cx) in listOf(cxL, cxR).withIndex()) {
            val pct = b.getOrElse(i) { -1 }
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            ring.color = grey(3)
            c.drawArc(box, 0f, 360f, false, ring)
            if (pct >= 0) {
                ring.color = grey(if (pct <= 15) 15 else 13)
                c.drawArc(box, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, ring)
            }
            val num = paint(r * 0.72f, 15, MEDIUM).apply { textAlign = Paint.Align.CENTER }
            c.drawText(if (pct >= 0) "$pct" else "–", cx, cy + num.textSize * 0.36f, num)
            val tag = paint(h * 0.12f, 8, CONDENSED, 0.2f).apply { textAlign = Paint.Align.CENTER }
            val charging = b.getOrElse(i + 2) { 0 } == 1
            c.drawText(if (i == 0) "L" else "R", cx, cy + r + h * 0.17f, tag)
            if (charging) { // a small bolt at the ring's top right
                val p = Path().apply {
                    val bx = cx + r * 0.62f
                    val by = cy - r * 0.95f
                    moveTo(bx + 2f, by); lineTo(bx - 2f, by + 6f); lineTo(bx + 1f, by + 6f)
                    lineTo(bx - 1f, by + 11f); lineTo(bx + 4f, by + 4f); lineTo(bx + 1f, by + 4f); close()
                }
                c.drawPath(p, Paint().apply { isAntiAlias = true; color = grey(15) })
            }
        }

        // The card between them: voice while it is in use, else the newest notification.
        val left = right + 16f
        val cardRight = cxL - r - 14f
        if (cardRight - left >= 110f) {
            val card = RectF(left, pad + 2f, cardRight, h - pad - 6f)
            line.color = grey(4)
            c.drawRoundRect(card, 6f, 6f, line)
            val accent = Paint().apply { color = grey(13) }
            c.drawRect(card.left, card.top + 6f, card.left + 3f, card.bottom - 6f, accent)
            val tx0 = card.left + 11f
            val room = card.right - tx0 - 8f
            val label = paint(h * 0.12f, 9, CONDENSED, 0.18f)
            val title = paint(h * 0.17f, 15, MEDIUM)
            val body = paint(h * 0.145f, 10, LIGHT)
            val y1 = card.top + h * 0.19f
            val y2 = card.top + h * 0.42f
            val y3 = card.top + h * 0.64f
            if (voice != null) {
                // A microphone in a ring, the state, and what is heard (two lines, the newest words).
                val mx = tx0 + 7f
                val my = y1 - h * 0.04f
                val mic = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f; color = grey(15) }
                c.drawRoundRect(RectF(mx - 2.5f, my - 5f, mx + 2.5f, my + 2f), 2.5f, 2.5f, mic)
                c.drawArc(RectF(mx - 4.5f, my - 3f, mx + 4.5f, my + 4.5f), 0f, 180f, false, mic)
                c.drawLine(mx, my + 4.5f, mx, my + 6.5f, mic)
                c.drawText(if (listening) "LISTENING" else "VOICE", tx0 + 18f, y1, label)
                val words = voice.replace(ScouterService.MIC, "").trim()
                val lines = wrap(words, title, room)
                val shown = lines.takeLast(2)
                c.drawText(shown.getOrElse(0) { "" }, tx0, y2 + h * 0.03f, title)
                if (shown.size > 1) c.drawText(shown[1], tx0, y3 + h * 0.06f, title)
            } else if (settings.lookUpNotifications) {
                val n = LookUp.notes.firstOrNull()
                if (n == null) {
                    c.drawText("ALL CLEAR", tx0, y1, label)
                    c.drawText("No new notifications", tx0, y2 + h * 0.03f, paint(h * 0.16f, 8, LIGHT))
                } else {
                    c.drawText(TextUtils.ellipsize(n.app.uppercase(Locale.getDefault()), label, room - 30f, TextUtils.TruncateAt.END).toString(),
                        tx0, y1, label)
                    val count = LookUp.notes.size
                    if (count > 1) { // a pill with how many
                        val t = "$count"
                        val pill = paint(h * 0.12f, 15, MEDIUM).apply { textAlign = Paint.Align.CENTER }
                        val pw = pill.measureText(t) + 10f
                        val pr = RectF(card.right - 8f - pw, y1 - h * 0.13f, card.right - 8f, y1 + h * 0.03f)
                        line.color = grey(9)
                        c.drawRoundRect(pr, 7f, 7f, line)
                        c.drawText(t, pr.centerX(), y1 - h * 0.005f, pill)
                    }
                    val head = n.title.ifBlank { n.app }
                    c.drawText(TextUtils.ellipsize(head, title, room, TextUtils.TruncateAt.END).toString(), tx0, y2 + h * 0.03f, title)
                    if (n.text.isNotBlank())
                        c.drawText(TextUtils.ellipsize(n.text.replace('\n', ' '), body, room, TextUtils.TruncateAt.END).toString(),
                            tx0, y3 + h * 0.07f, body)
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
        return Frame(packed, bmp)
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

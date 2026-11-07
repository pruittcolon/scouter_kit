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
// levels (black is see-through), sent as 4 bpp. A style (STYLES) or the user's own layout: the tab bar on top or
// not, then a row of widgets: the clock and date, the newest notifications, what plays (cover, title, artist,
// progress and its buttons), each arm's battery, the phone's battery. Voice, or a quick reply being written,
// takes the widest widget's place while it is in use. Corner brackets frame it.
object Dashboard {
    // hits: where the tab bar's tabs and its + are (left to right), for the pointer.
    class Frame(val packed: ByteArray, val preview: Bitmap, val hits: List<Hit> = emptyList())

    // A place on the dashboard the pointer can click: its pixels across and down, and its action ("select:N",
    // "new", "phone" on the tab bar; "note:KEY" a notification). Places with the same top are a row.
    class Hit(val left: Float, val right: Float, val action: String, val top: Float = 0f, val bottom: Float = 0f) {
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

    // The styles, and the widgets a layout is made of (NOTES and MEDIA share the room the others leave, by weight).
    val STYLES = listOf("classic" to "Classic", "media" to "Media", "everything" to "Everything", "focus" to "Focus",
        "minimal" to "Minimal", "custom" to "Your own")
    const val CLOCK = "clock"
    const val NOTES = "notes"
    const val MEDIA = "media"
    const val BATTERY = "battery"
    const val PHONE = "phone"
    val WIDGETS = listOf(CLOCK to "Clock and date", NOTES to "Notifications", MEDIA to "Now playing",
        BATTERY to "Glasses' batteries", PHONE to "Phone's battery")
    private val FLEX = setOf(NOTES, MEDIA)

    class Widget(val type: String, val weight: Int = 1)
    class Layout(val tabs: Boolean, val widgets: List<Widget>)

    fun layout(settings: Settings, style: String = settings.dashStyle): Layout = when (style) {
        "media" -> Layout(true, listOf(Widget(CLOCK), Widget(MEDIA), Widget(BATTERY)))
        "everything" -> Layout(true, listOf(Widget(CLOCK), Widget(NOTES), Widget(MEDIA), Widget(BATTERY)))
        "focus" -> Layout(false, listOf(Widget(CLOCK), Widget(NOTES, 2)))
        "minimal" -> Layout(false, listOf(Widget(CLOCK), Widget(PHONE), Widget(BATTERY)))
        "custom" -> custom(settings)
        else -> Layout(true, listOf(Widget(CLOCK), Widget(NOTES), Widget(BATTERY)))
    }

    // The user's own layout (Settings.dashLayout): {"tabs": true, "widgets": [{"type": "clock", "weight": 1}, ...]}.
    fun custom(settings: Settings): Layout = runCatching {
        val o = org.json.JSONObject(settings.dashLayout)
        val a = o.getJSONArray("widgets")
        Layout(o.optBoolean("tabs", true), (0 until a.length()).map { i ->
            val w = a.getJSONObject(i)
            Widget(w.getString("type"), w.optInt("weight", 1).coerceIn(1, 3))
        }.filter { it.type in WIDGETS.map { p -> p.first } })
    }.getOrDefault(Layout(true, listOf(Widget(CLOCK), Widget(NOTES), Widget(MEDIA), Widget(BATTERY))))

    fun saveCustom(settings: Settings, l: Layout) {
        val a = org.json.JSONArray()
        for (w in l.widgets) a.put(org.json.JSONObject().put("type", w.type).put("weight", w.weight))
        settings.dashLayout = org.json.JSONObject().put("tabs", l.tabs).put("widgets", a).toString()
    }

    // voice: what the voice band says (null: none); listening: the microphone is on; hover: the action of the
    // place the pointer is on (outlined and marked), or null; look: another style than the settings' (previews).
    fun render(context: Context, settings: Settings, w: Int, h: Int, voice: String?, listening: Boolean,
               hover: String? = null, look: String? = null): Frame {
        val lay = layout(settings, look ?: settings.dashStyle)
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

        var top = pad
        if (lay.tabs) {
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
            // The phone's screen at the bar's right end: a tab that brings it up.
            val phoneLabel = paint(barH * 0.6f, 13, CONDENSED, 0.12f)
            val phoneW = phoneLabel.measureText("PHONE") + barH * 0.9f + 12f
            val phoneBox = RectF(w - pad - 6f - phoneW, barY, w - pad - 6f, barY + barH)
            val room = phoneBox.left - 8f - (barH + 6f) // the + after the tabs
            var shown = 0
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
                hits += Hit(box.left, box.right, action, barY, barY + barH)
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
                hits += Hit(box.left, box.right, "new", barY, barY + barH)
                if (on) hovered(box)
            }
            run {
                val on = hover == "phone"
                if (on) {
                    fill.color = grey(14)
                    c.drawRoundRect(phoneBox, 4f, 4f, fill)
                } else {
                    line.color = grey(6)
                    c.drawRoundRect(phoneBox, 4f, 4f, line)
                }
                // A small phone: a rounded outline with a dot for its button.
                val ph = barH * 0.62f
                val pw = ph * 0.55f
                val px = phoneBox.left + 6f
                val py = phoneBox.centerY() - ph / 2
                val icon = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.2f; color = grey(if (on) 0 else 13) }
                c.drawRoundRect(RectF(px, py, px + pw, py + ph), 2f, 2f, icon)
                icon.style = Paint.Style.FILL
                c.drawCircle(px + pw / 2, py + ph - 2.2f, 0.9f, icon)
                phoneLabel.color = grey(if (on) 0 else 13)
                c.drawText("PHONE", px + pw + 5f, textY, phoneLabel)
                hits += Hit(phoneBox.left, phoneBox.right, "phone", barY, barY + barH)
                if (on) hovered(phoneBox)
            }
            line.color = grey(3)
            val ruleY = barY + barH + 3.5f
            c.drawLine(pad + 6f, ruleY, w - pad - 6f, ruleY, line)

            top = ruleY + 3f
        }

        // The row: fixed widgets at their own width, the others sharing what is left by weight.
        val rowH = h - top - pad
        val right = w - pad - 6f
        val gap = 12f
        val widths = lay.widgets.map { wd -> if (wd.type in FLEX) 0f else natural(context, wd.type, rowH) }
        val room = (right - (pad + 6f)) - widths.sum() - gap * (lay.widgets.size - 1).coerceAtLeast(0)
        val shares = lay.widgets.filter { it.type in FLEX }.sumOf { it.weight }.coerceAtLeast(1)
        var x = pad + 6f
        var overlay: RectF? = null
        for ((i, wd) in lay.widgets.withIndex()) {
            val ww = if (wd.type in FLEX) maxOf(0f, room * wd.weight / shares) else widths[i]
            val r = RectF(x, top, x + ww, top + rowH)
            if (i > 0) {
                line.color = grey(3)
                c.drawLine(x - gap / 2, top + 2f, x - gap / 2, top + rowH - 2f, line)
            }
            val busy = wd.type in FLEX && overlay == null && (voice != null || Session.replyTo != null)
            if (busy) overlay = r
            else when (wd.type) {
                CLOCK -> clock(context, c, r)
                BATTERY -> batteries(c, r, line, fill)
                PHONE -> phoneBattery(context, c, r, line, fill)
                NOTES -> notes(c, r, hits, hover, mark, fill, settings)
                MEDIA -> media(c, r, hits, hover, mark, fill, line)
            }
            x += ww + gap
        }
        // Voice, or a quick reply: in the widest widget's place (the whole row when there is none).
        if (voice != null || Session.replyTo != null) {
            val r = overlay ?: RectF(pad + 6f, top, right, top + rowH).also {
                fill.color = Color.BLACK
                c.drawRect(it, fill)
            }
            spoken(c, r, voice, listening)
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

    // A fixed widget's width at this row height.
    private fun natural(context: Context, type: String, rowH: Float): Float = when (type) {
        CLOCK -> {
            val (clock, ampm, date) = clockTexts(context)
            val big = paint(rowH * 0.58f, 15, THIN)
            val ap = paint(rowH * 0.17f, 9, MEDIUM, 0.08f)
            val small = paint(rowH * 0.17f, 10, CONDENSED, 0.2f)
            maxOf(big.measureText(clock) + (if (ampm.isNotEmpty()) ap.measureText(ampm) + 5f else 0f), small.measureText(date))
        }
        BATTERY -> {
            val pct = paint(rowH * 0.2f, 13, MEDIUM)
            val letter = paint(rowH * 0.17f, 8, CONDENSED)
            pct.measureText("100%+") + 4f + 30f + 4f + letter.measureText("L")
        }
        PHONE -> paint(rowH * 0.2f, 13, MEDIUM).measureText("100%") + 30f + 8f
        else -> 0f
    }

    private fun clockTexts(context: Context): Triple<String, String, String> {
        val now = Date()
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        return Triple(SimpleDateFormat(if (h24) "H:mm" else "h:mm", Locale.getDefault()).format(now),
            if (h24) "" else SimpleDateFormat("a", Locale.US).format(now).uppercase(Locale.US),
            SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now).uppercase(Locale.getDefault()))
    }

    // The clock with a dim halo, the date under it.
    private fun clock(context: Context, c: Canvas, r: RectF) {
        val (clock, ampm, date) = clockTexts(context)
        val rowH = r.height()
        val big = paint(rowH * 0.58f, 15, THIN).apply { setShadowLayer(4f, 0f, 0f, grey(5)) }
        c.drawText(clock, r.left, r.top + rowH * 0.6f, big)
        val cw = big.measureText(clock)
        val ap = paint(rowH * 0.17f, 9, MEDIUM, 0.08f)
        if (ampm.isNotEmpty()) c.drawText(ampm, r.left + cw + 3f, r.top + rowH * 0.27f, ap)
        c.drawText(date, r.left + 1f, r.top + rowH * 0.92f, paint(rowH * 0.17f, 10, CONDENSED, 0.2f))
    }

    // Each arm's battery, small: a letter, a bar, the level.
    private fun batteries(c: Canvas, r: RectF, line: Paint, fill: Paint) {
        val rowH = r.height()
        val bw = 30f
        val bh = maxOf(6f, rowH * 0.13f)
        val pct = paint(rowH * 0.2f, 13, MEDIUM).apply { textAlign = Paint.Align.RIGHT }
        val letter = paint(rowH * 0.17f, 8, CONDENSED)
        val b = Session.battery
        for (i in 0..1) {
            val cy = r.top + rowH * (if (i == 0) 0.32f else 0.74f)
            val level = b.getOrElse(i) { -1 }
            c.drawText(if (i == 0) "L" else "R", r.left, cy + letter.textSize * 0.35f, letter)
            val bx = r.left + letter.measureText("L") + 4f
            bar(c, RectF(bx, cy - bh / 2, bx + bw, cy + bh / 2), level, line, fill)
            val charging = b.getOrElse(i + 2) { 0 } == 1
            c.drawText(if (level >= 0) "$level%" + if (charging) "+" else "" else "–", r.right, cy + pct.textSize * 0.36f, pct)
        }
    }

    // The phone's own battery: a bar and its level, PHONE over it.
    private fun phoneBattery(context: Context, c: Canvas, r: RectF, line: Paint, fill: Paint) {
        val rowH = r.height()
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1
        val charging = bm?.isCharging == true
        c.drawText("PHONE", r.left, r.top + rowH * 0.3f, paint(rowH * 0.15f, 8, CONDENSED, 0.15f))
        val bh = maxOf(6f, rowH * 0.13f)
        val cy = r.top + rowH * 0.62f
        bar(c, RectF(r.left, cy - bh / 2, r.left + 30f, cy + bh / 2), level, line, fill)
        val pct = paint(rowH * 0.2f, 13, MEDIUM).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(if (level >= 0) "$level%" + if (charging) "+" else "" else "–", r.right, cy + pct.textSize * 0.36f, pct)
    }

    private fun bar(c: Canvas, box: RectF, level: Int, line: Paint, fill: Paint) {
        line.color = grey(6)
        c.drawRoundRect(box, 2f, 2f, line)
        fill.color = grey(6)
        c.drawRect(box.right + 0.5f, box.centerY() - box.height() / 4, box.right + 2f, box.centerY() + box.height() / 4, fill)
        if (level >= 0) {
            fill.color = grey(if (level <= 15) 15 else 12)
            c.drawRect(box.left + 1.5f, box.top + 1.5f, box.left + 1.5f + (box.width() - 3f) * level.coerceIn(0, 100) / 100f, box.bottom - 1.5f, fill)
        }
    }

    // The newest three notifications, each a row the pointer can click (open it, or reply).
    private fun notes(c: Canvas, r: RectF, hits: MutableList<Hit>, hover: String?, mark: Paint, fill: Paint, settings: Settings) {
        val rowH = r.height()
        val mx = r.left
        val mRight = r.right
        val mw = r.width()
        if (mw < 60f) return
        val app = paint(rowH * 0.16f, 9, CONDENSED, 0.15f)
        val title = paint(rowH * 0.19f, 15, MEDIUM)
        val body = paint(rowH * 0.18f, 10, LIGHT)
        val lineH = rowH / 3f
        val top = r.top
        val notes = LookUp.notes.take(3)
        if (notes.isEmpty()) {
            c.drawText("NOTIFICATIONS", mx, top + lineH * 0.62f, app)
            c.drawText("All clear", mx, top + lineH * 1.62f, paint(rowH * 0.19f, 8, LIGHT))
        }
        val appW = minOf(mw * 0.3f, app.measureText("MESSAGES") + 6f)
        for ((i, n) in notes.withIndex()) {
            val y = top + lineH * (i + 0.66f)
            val row = RectF(mx - 3f, top + lineH * i + 1f, mRight + 1f, top + lineH * (i + 1) - 1f)
            val action = "note:${n.key}"
            if (n.key.isNotEmpty()) hits += Hit(row.left, row.right, action, row.top, row.bottom)
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
            if (hover == action) {
                // The ring, and what a click does at its right end (over the text, on black).
                c.drawRoundRect(row, 3f, 3f, mark)
                val hint = paint(rowH * 0.13f, 15, CONDENSED, 0.1f).apply { textAlign = Paint.Align.RIGHT }
                val says = if (n.reply) "OPEN \u00B7 \u21E7\u23CE REPLY" else "OPEN"
                val hw = hint.measureText(says) + 8f
                fill.color = Color.BLACK
                c.drawRect(row.right - hw - 2f, row.top + 1.5f, row.right - 1.5f, row.bottom - 1.5f, fill)
                c.drawText(says, row.right - 5f, row.centerY() + hint.textSize * 0.36f, hint)
            }
        }
        if (LookUp.notes.size > 3) {
            val more = paint(rowH * 0.15f, 7, CONDENSED).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("+${LookUp.notes.size - 3}", mRight, top + lineH * 2.66f, more)
        }
    }

    // What plays: its cover, the app and whether it plays, the title, the artist, a progress line, and the buttons
    // the pointer clicks (before, play / pause, next).
    private fun media(c: Canvas, r: RectF, hits: MutableList<Hit>, hover: String?, mark: Paint, fill: Paint, line: Paint) {
        val rowH = r.height()
        if (r.width() < 90f) return
        val now = Media.now
        val small = paint(rowH * 0.15f, 9, CONDENSED, 0.15f)
        val title = paint(rowH * 0.21f, 15, MEDIUM)
        val artist = paint(rowH * 0.17f, 10, LIGHT)
        // The cover (in the glasses' greys), else a note.
        val side = minOf(rowH - 4f, r.width() * 0.3f)
        val cover = RectF(r.left, r.top + (rowH - side) / 2, r.left + side, r.top + (rowH + side) / 2)
        val art = now?.art
        if (art != null) {
            c.save()
            c.clipRect(cover)
            c.drawBitmap(art, null, cover, Paint().apply { isFilterBitmap = true })
            c.restore()
            line.color = grey(5)
            c.drawRoundRect(cover, 3f, 3f, line)
        } else {
            line.color = grey(5)
            c.drawRoundRect(cover, 3f, 3f, line)
            val note = paint(side * 0.55f, 9, LIGHT).apply { textAlign = Paint.Align.CENTER }
            c.drawText("\u266A", cover.centerX(), cover.centerY() + note.textSize * 0.35f, note)
        }
        // The buttons at the right end, one row for the pointer.
        val bs = minOf(rowH * 0.42f, 22f)
        val by = r.top + rowH * 0.62f - bs / 2
        val names = listOf("prev", "toggle", "next")
        val btnRight = r.right
        val btnLeft = btnRight - bs * 3 - 8f
        for ((i, name) in names.withIndex()) {
            val b = RectF(btnLeft + i * (bs + 4f), by, btnLeft + i * (bs + 4f) + bs, by + bs)
            val action = "media:$name"
            val on = hover == action
            if (on) {
                fill.color = grey(14)
                c.drawRoundRect(b, 4f, 4f, fill)
            } else {
                line.color = grey(6)
                c.drawRoundRect(b, 4f, 4f, line)
            }
            glyph(c, b, if (name == "toggle") (if (now?.playing == true) "pause" else "play") else name, grey(if (on) 0 else 14))
            if (now != null) hits += Hit(b.left, b.right, action, by, by + bs)
            if (on) hovered(c, b, mark, fill)
        }
        // The words between the cover and the buttons.
        val tx = cover.right + 8f
        val room = btnLeft - 8f - tx
        if (room > 30f) {
            val head = if (now == null) "NOTHING PLAYING" else (now.app.uppercase(Locale.getDefault()) + if (now.playing) " \u00B7 PLAYING" else " \u00B7 PAUSED")
            c.drawText(TextUtils.ellipsize(head, small, room, TextUtils.TruncateAt.END).toString(), tx, r.top + rowH * 0.22f, small)
            if (now != null) {
                c.drawText(TextUtils.ellipsize(now.title.ifBlank { now.app }, title, room, TextUtils.TruncateAt.END).toString(), tx, r.top + rowH * 0.52f, title)
                if (now.artist.isNotBlank()) c.drawText(TextUtils.ellipsize(now.artist, artist, room, TextUtils.TruncateAt.END).toString(), tx, r.top + rowH * 0.76f, artist)
                if (now.duration > 0) { // the progress line
                    val py = r.top + rowH * 0.92f
                    line.color = grey(3)
                    c.drawLine(tx, py, tx + room, py, line)
                    fill.color = grey(13)
                    c.drawRect(tx, py - 1f, tx + room * (now.now().toFloat() / now.duration).coerceIn(0f, 1f), py + 1f, fill)
                }
            } else c.drawText("Spotify, YouTube…", tx, r.top + rowH * 0.52f, artist)
        }
    }

    // A button's sign: play, pause, before, next.
    private fun glyph(c: Canvas, b: RectF, what: String, colour: Int) {
        val p = Paint().apply { isAntiAlias = true; color = colour }
        val s = b.width() * 0.22f
        val cx = b.centerX()
        val cy = b.centerY()
        val path = Path()
        when (what) {
            "play" -> {
                path.moveTo(cx - s * 0.8f, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx - s * 0.8f, cy + s)
                path.close()
                c.drawPath(path, p)
            }
            "pause" -> {
                c.drawRect(cx - s, cy - s, cx - s * 0.3f, cy + s, p)
                c.drawRect(cx + s * 0.3f, cy - s, cx + s, cy + s, p)
            }
            "next", "prev" -> {
                val d = if (what == "next") 1f else -1f
                path.moveTo(cx - d * s, cy - s)
                path.lineTo(cx + d * s * 0.5f, cy)
                path.lineTo(cx - d * s, cy + s)
                path.close()
                c.drawPath(path, p)
                c.drawRect(minOf(cx + d * s * 0.5f, cx + d * s), cy - s, maxOf(cx + d * s * 0.5f, cx + d * s), cy + s, p)
            }
        }
    }

    private fun hovered(c: Canvas, box: RectF, mark: Paint, fill: Paint) {
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

    // Voice while it is in use, or a quick reply being written, in r.
    private fun spoken(c: Canvas, r: RectF, voice: String?, listening: Boolean) {
        val rowH = r.height()
        val mx = r.left
        val mw = r.width()
        val top = r.top
        val app = paint(rowH * 0.16f, 9, CONDENSED, 0.15f)
        val title = paint(rowH * 0.19f, 15, MEDIUM)
        val lineH = rowH / 3f
        val replyTo = Session.replyTo
        if (replyTo != null) {
            // A quick reply being written: to whom, the words so far with a caret, and the keys.
            val head = (if (listening) "\u25CF " else "") + "REPLY \u00B7 " + replyTo.uppercase(Locale.getDefault())
            c.drawText(TextUtils.ellipsize(head, app, mw, TextUtils.TruncateAt.END).toString(), mx, top + lineH * 0.62f, app)
            val status = Session.replyStatus
            if (status.isNotEmpty()) c.drawText(status, mx, top + lineH * 1.62f, title)
            else {
                val words = wrap(Session.replyDraft + "\u258F", title, mw).takeLast(1)
                for ((i, l) in words.withIndex()) c.drawText(l, mx, top + lineH * (1.62f + i), title)
            }
            c.drawText("ENTER SEND \u00B7 ESC CANCEL \u00B7 ALT+SPACE SAY IT", mx, top + lineH * 2.62f,
                paint(rowH * 0.14f, 7, CONDENSED, 0.1f))
        } else if (voice != null) {
            c.drawText(if (listening) "\u25CF LISTENING" else "VOICE", mx, top + lineH * 0.62f, app)
            val words = voice.replace(ScouterService.MIC, "").trim()
            val lines = wrap(words, title, mw).takeLast(2)
            for ((i, l) in lines.withIndex()) c.drawText(l, mx, top + lineH * (1.62f + i), title)
        }
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

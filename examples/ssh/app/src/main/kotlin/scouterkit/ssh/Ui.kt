package scouterkit.ssh

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

// The app's look: dark surfaces, rounded cards, one accent gradient. Plain Android views built in code (the app
// has no UI library), so every screen uses these helpers.
object Ui {
    const val BG = 0xFF07090D.toInt()
    const val SURFACE = 0xFF10141B.toInt()
    const val RAISED = 0xFF171C25.toInt()
    const val STROKE = 0xFF242B38.toInt()
    const val TEXT = 0xFFF3F5F9.toInt()
    const val MUTED = 0xFF8D96A8.toInt()
    const val FAINT = 0xFF5B6475.toInt()
    const val ACCENT = 0xFF5EEAD4.toInt()
    const val ACCENT2 = 0xFF818CF8.toInt()
    const val ON_ACCENT = 0xFF05110F.toInt()
    const val GOOD = 0xFF34D399.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val BAD = 0xFFF87171.toInt()

    val BOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BLACK: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val MONO: Typeface = Typeface.MONOSPACE
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun accentGradient(radius: Float): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.ACCENT, Ui.ACCENT2)).apply { cornerRadius = radius }

// A touch ripple over a background.
fun ripple(content: Drawable, radius: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, radius))

fun Context.text(value: String, size: Float = 15f, color: Int = Ui.TEXT, face: Typeface = Typeface.DEFAULT): TextView =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = face
        setLineSpacing(0f, 1.15f)
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.gap(space: Int) = addView(View(context), lp(h = context.dp(space)))

// The screen: a dark scrolling column with room at the sides; the window's bars in the same colour.
fun Activity.page(): LinearLayout {
    window.statusBarColor = Ui.BG
    window.navigationBarColor = Ui.BG
    window.decorView.setBackgroundColor(Ui.BG)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(20), dp(18), dp(36))
    }
    setContentView(ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(column)
    })
    return column
}

// A title with a line under it, and an optional pill button at the right.
fun Context.header(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(text(title, 30f, Ui.TEXT, Ui.BLACK).apply { letterSpacing = -0.02f })
    texts.addView(text(subtitle, 13f, Ui.MUTED))
    row.addView(texts, lp(0, weight = 1f))
    if (action != null) row.addView(chip(action, false, onAction))
    row.setPadding(dp(4), dp(6), dp(4), dp(18))
    return row
}

// A rounded surface holding a column; `title` above its content in small capitals.
fun Context.card(title: String? = null, hint: String? = null): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, dp(24).toFloat(), dp(1), Ui.STROKE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp().apply { bottomMargin = dp(14) }
    }
    if (title != null) card.addView(text(title.uppercase(), 11.5f, Ui.MUTED, Ui.BOLD).apply {
        letterSpacing = 0.14f
        setPadding(0, 0, 0, dp(if (hint == null) 8 else 2))
    })
    if (hint != null) card.addView(text(hint, 13f, Ui.FAINT).apply { setPadding(0, 0, 0, dp(8)) })
    return card
}

// A small pill: an outlined button, or filled with the accent when `on`.
fun Context.chip(label: String, on: Boolean, onClick: (() -> Unit)? = null): TextView = text(label, 13.5f, if (on) Ui.ON_ACCENT else Ui.TEXT, Ui.BOLD).apply {
    val r = dp(18).toFloat()
    val base = if (on) accentGradient(r) else rounded(Ui.RAISED, r, dp(1), Ui.STROKE)
    background = if (onClick != null) ripple(base, r) else base
    setPadding(dp(14), dp(8), dp(14), dp(8))
    gravity = Gravity.CENTER
    if (onClick != null) setOnClickListener { onClick() }
}

// A status pill: a coloured dot and a label.
fun Context.statusPill(label: String, dot: Int): TextView = text("●  $label", 12.5f, Ui.TEXT, Ui.BOLD).apply {
    val span = android.text.SpannableString(text)
    span.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
    text = span
    background = rounded(Ui.RAISED, dp(14).toFloat(), dp(1), Ui.STROKE)
    setPadding(dp(11), dp(6), dp(12), dp(6))
}

// A wide button: primary (the accent), plain, or danger.
fun Context.button(label: String, kind: Int = 1, onClick: () -> Unit): TextView = text(label, 15f, Ui.TEXT, Ui.BOLD).apply {
    val r = dp(16).toFloat()
    val base = when (kind) {
        0 -> accentGradient(r).also { setTextColor(Ui.ON_ACCENT) }
        2 -> rounded(0x1FF87171, r, dp(1), 0x66F87171).also { setTextColor(Ui.BAD) }
        else -> rounded(Ui.RAISED, r, dp(1), Ui.STROKE)
    }
    background = ripple(base, r)
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = lp().apply { topMargin = dp(8) }
    setOnClickListener { onClick() }
}

const val PRIMARY = 0
const val PLAIN = 1
const val DANGER = 2

// A row with a title, a line under it and a switch at the right.
fun Context.toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): Switch {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
    }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(text(title, 15.5f, Ui.TEXT, Ui.BOLD))
    if (sub != null) texts.addView(text(sub, 12.5f, Ui.MUTED))
    row.addView(texts, lp(0, weight = 1f).apply { rightMargin = dp(12) })
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    val s = Switch(this).apply {
        isChecked = value
        thumbTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, 0xFFC9CED8.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x665EEAD4, Ui.STROKE))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    row.addView(s)
    row.setOnClickListener { s.toggle() }
    toggleRows[s] = row
    return s
}

// The rows that toggle() made, so the caller can place them (toggle() returns the switch).
val toggleRows = java.util.WeakHashMap<Switch, View>()

fun LinearLayout.addToggle(s: Switch) = addView(toggleRows[s] ?: s)

// A slider: title, its value at the right, the bar under them. live: while dragging; commit: on release.
fun Context.slider(title: String, min: Int, max: Int, value: Int, format: (Int) -> String = { "$it" },
                   live: (Int) -> Unit = {}, commit: (Int) -> Unit): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(6)) }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val shown = text(format(value), 14f, Ui.ACCENT, Ui.BOLD)
    top.addView(text(title, 15.5f, Ui.TEXT, Ui.BOLD), lp(0, weight = 1f))
    top.addView(shown)
    box.addView(top)
    box.addView(SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value.coerceIn(min, max)
        progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(Ui.STROKE)
        setPadding(dp(4), dp(10), dp(4), dp(4))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, v: Int, user: Boolean) {
                shown.text = format(v)
                if (user) live(v)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) = commit(bar.progress)
        })
    }, lp())
    return box
}

// A segmented choice: options as (key, label); the selected one filled with the accent.
fun Context.segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(Ui.RAISED, dp(16).toFloat(), dp(1), Ui.STROKE)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = lp().apply { topMargin = dp(6); bottomMargin = dp(6) }
    }
    var current = selected
    val labels = mutableListOf<TextView>()
    fun paint() = options.forEachIndexed { i, (key, _) ->
        val on = key == current
        labels[i].background = if (on) accentGradient(dp(12).toFloat()) else null
        labels[i].setTextColor(if (on) Ui.ON_ACCENT else Ui.MUTED)
    }
    for ((key, label) in options) {
        val t = text(label, 13.5f, Ui.MUTED, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener {
                current = key
                paint()
                onSelect(key)
            }
        }
        labels += t
        row.addView(t, lp(0, weight = 1f))
    }
    paint()
    return row
}

// A row that opens a choice: title at the left, the value and a chevron at the right.
fun Context.choiceRow(title: String, value: String, onClick: (TextView) -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
    }
    val v = text(value, 14f, Ui.ACCENT, Ui.BOLD)
    row.addView(text(title, 15.5f, Ui.TEXT, Ui.BOLD), lp(0, weight = 1f))
    row.addView(v)
    row.addView(text("  ›", 18f, Ui.FAINT))
    row.setOnClickListener { onClick(v) }
    return row
}

// A labelled text field on a raised surface.
fun Context.field(label: String, value: String, hint: String = "", numeric: Boolean = false, secret: Boolean = false,
                  lines: Int = 1): EditText {
    val e = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.FAINT)
        textSize = 15f
        background = rounded(Ui.RAISED, dp(14).toFloat(), dp(1), Ui.STROKE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (lines > 1) minLines = lines
        if (lines > 1) typeface = Ui.MONO
    }
    fieldLabels[e] = label
    return e
}

val fieldLabels = java.util.WeakHashMap<EditText, String>()

// Adds the field with its label above it.
fun LinearLayout.addField(e: EditText) {
    fieldLabels[e]?.let { addView(context.text(it, 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(context.dp(2), context.dp(10), 0, context.dp(6)) }) }
    addView(e, lp())
}

// A thin rule between groups in a card.
fun LinearLayout.rule() = addView(View(context).apply { setBackgroundColor(Ui.STROKE) },
    lp(h = 1).apply { topMargin = context.dp(6); bottomMargin = context.dp(6) })

// A wrapping row of chips (simple: one line, scrolls sideways when full).
fun Context.chipRow(vararg chips: View): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((i, c) in chips.withIndex()) row.addView(c, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8) })
    return android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
        layoutParams = lp().apply { topMargin = dp(4); bottomMargin = dp(4) }
    }
}

// A frame with rounded corners that clips its child (the terminal preview).
fun Context.clipFrame(child: View, heightDp: Int): FrameLayout = FrameLayout(this).apply {
    background = rounded(0xFF000000.toInt(), dp(18).toFloat(), dp(1), Ui.STROKE)
    clipToOutline = true
    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    layoutParams = lp(h = dp(heightDp)).apply { topMargin = dp(4) }
}

// Dialogs in the app's colours.
fun Context.dialog(): android.app.AlertDialog.Builder =
    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

// Where the phone's picture sits on the glasses: their view area with the picture's rectangle on it. Drag inside
// the rectangle to move it, its corner to resize it, double-tap to fill the area. Values in percent of the area;
// change: while dragging; commit: on release.
class LayoutEditor(context: Context, private val aspect: Float, x: Int, y: Int, w: Int, h: Int,
                   private val change: (Int, Int, Int, Int) -> Unit, private val commit: (Int, Int, Int, Int) -> Unit) : View(context) {
    var px = x
    var py = y
    var pw = w
    var ph = h
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val area = android.graphics.RectF()
    private val pic = android.graphics.RectF()
    private var mode = 0 // 0 none, 1 move, 2 resize
    private var downX = 0f
    private var downY = 0f
    private var start = intArrayOf(0, 0, 0, 0)
    private val taps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
            set(0, 0, 100, 100)
            commit(px, py, pw, ph)
            return true
        }
    })

    fun set(x: Int, y: Int, w: Int, h: Int) {
        pw = w.coerceIn(10, 100)
        ph = h.coerceIn(10, 100)
        px = x.coerceIn(0, 100 - pw)
        py = y.coerceIn(0, 100 - ph)
        change(px, py, pw, ph)
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val wd = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(wd, (wd / aspect).toInt() + context.dp(8))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        area.set(2 * d, 2 * d, width - 2 * d, height - 6 * d)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = 0xFF000000.toInt()
        canvas.drawRoundRect(area, 14 * d, 14 * d, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1 * d
        paint.color = Ui.STROKE
        canvas.drawRoundRect(area, 14 * d, 14 * d, paint)
        for (i in 1..2) { // thirds, to place by
            val gx = area.left + area.width() * i / 3
            val gy = area.top + area.height() * i / 3
            canvas.drawLine(gx, area.top, gx, area.bottom, paint)
            canvas.drawLine(area.left, gy, area.right, gy, paint)
        }
        pic.set(area.left + area.width() * px / 100, area.top + area.height() * py / 100,
            area.left + area.width() * (px + pw) / 100, area.top + area.height() * (py + ph) / 100)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = 0x335EEAD4
        canvas.drawRoundRect(pic, 8 * d, 8 * d, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = Ui.ACCENT
        canvas.drawRoundRect(pic, 8 * d, 8 * d, paint)
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawCircle(pic.right, pic.bottom, 9 * d, paint) // the resize handle
        paint.color = Ui.TEXT
        paint.textSize = 12 * d
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("$pw × $ph%", pic.centerX(), pic.centerY() + 4 * d, paint)
        paint.textAlign = android.graphics.Paint.Align.LEFT
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (taps.onTouchEvent(e)) return true
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val d = resources.displayMetrics.density
                mode = when {
                    Math.hypot((e.x - pic.right).toDouble(), (e.y - pic.bottom).toDouble()) < 30 * d -> 2
                    pic.contains(e.x, e.y) -> 1
                    else -> 0
                }
                downX = e.x
                downY = e.y
                start = intArrayOf(px, py, pw, ph)
                parent?.requestDisallowInterceptTouchEvent(mode != 0)
                return mode != 0
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val ddx = ((e.x - downX) / area.width() * 100).toInt()
                val ddy = ((e.y - downY) / area.height() * 100).toInt()
                if (mode == 1) set(start[0] + ddx, start[1] + ddy, start[2], start[3])
                if (mode == 2) {
                    pw = (start[2] + ddx).coerceIn(10, 100 - start[0])
                    ph = (start[3] + ddy).coerceIn(10, 100 - start[1])
                    change(px, py, pw, ph)
                    invalidate()
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (mode != 0) commit(px, py, pw, ph)
                mode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }
}

// ------------------------------------------------ icons

// The app's line icons, drawn in code on a 24-unit grid with round strokes (one look everywhere, no resources).
object Icons {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    private val box = android.graphics.RectF()

    fun draw(c: android.graphics.Canvas, kind: String, size: Float, color: Int, x: Float = 0f, y: Float = 0f) {
        val s = size / 24f
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        paint.color = color
        paint.strokeWidth = 1.8f
        paint.style = android.graphics.Paint.Style.STROKE
        fun rect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
            box.set(l, t, r, b)
            c.drawRoundRect(box, rad, rad, paint)
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0, y0, x1, y1, paint)
        fun poly(close: Boolean, vararg p: Float) {
            path.reset()
            path.moveTo(p[0], p[1])
            var i = 2
            while (i + 1 < p.size) {
                path.lineTo(p[i], p[i + 1])
                i += 2
            }
            if (close) path.close()
            c.drawPath(path, paint)
        }
        fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
            box.set(l, t, r, b)
            c.drawArc(box, start, sweep, false, paint)
        }
        fun dot(cx: Float, cy: Float) = line(cx, cy, cx + 0.01f, cy)
        when (kind) {
            "terminal" -> {
                rect(3f, 5f, 21f, 19f, 2.5f)
                poly(false, 7.5f, 9.5f, 10.5f, 12f, 7.5f, 14.5f)
                line(12.5f, 15f, 16.5f, 15f)
            }
            "screen" -> {
                rect(3f, 4.5f, 21f, 16f, 2f)
                line(12f, 16f, 12f, 19.5f)
                line(8.5f, 19.5f, 15.5f, 19.5f)
            }
            "tv" -> { // a set with its antennas
                rect(3f, 7f, 21f, 19.5f, 2f)
                line(8.5f, 2.5f, 12f, 6.5f)
                line(15.5f, 2.5f, 12f, 6.5f)
            }
            "phone" -> {
                rect(7f, 2.5f, 17f, 21.5f, 2.5f)
                line(11f, 18.3f, 13f, 18.3f)
            }
            "laptop" -> {
                rect(5f, 5f, 19f, 15f, 1.5f)
                line(2.5f, 18.5f, 21.5f, 18.5f)
            }
            "power" -> {
                arc(5f, 5f, 19f, 19f, -60f, 300f)
                line(12f, 3f, 12f, 11f)
            }
            "mic" -> {
                rect(9f, 3f, 15f, 14f, 3f)
                arc(5.5f, 4.5f, 18.5f, 17.5f, 0f, 180f)
                line(12f, 17.5f, 12f, 21f)
                line(9f, 21f, 15f, 21f)
            }
            "eye" -> {
                path.reset()
                path.moveTo(2.5f, 12f)
                path.quadTo(12f, 3.5f, 21.5f, 12f)
                path.quadTo(12f, 20.5f, 2.5f, 12f)
                path.close()
                c.drawPath(path, paint)
                c.drawCircle(12f, 12f, 3f, paint)
            }
            "sun" -> {
                c.drawCircle(12f, 12f, 4f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(),
                        12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat())
                }
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3f, paint)
                c.drawCircle(12f, 12f, 7f, paint)
                paint.strokeWidth = 2.6f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0 + 22.5)
                    line(12f + 7.5f * Math.cos(a).toFloat(), 12f + 7.5f * Math.sin(a).toFloat(),
                        12f + 9.6f * Math.cos(a).toFloat(), 12f + 9.6f * Math.sin(a).toFloat())
                }
            }
            "keyboard" -> {
                rect(2.5f, 6f, 21.5f, 18f, 2f)
                for (x in floatArrayOf(6f, 9f, 12f, 15f, 18f)) dot(x, 10f)
                for (x in floatArrayOf(7.5f, 10.5f, 13.5f, 16.5f)) dot(x, 12.8f)
                line(8f, 15.3f, 16f, 15.3f)
            }
            "pointer" -> poly(true, 6f, 3f, 6f, 19f, 10.2f, 15.2f, 13f, 21f, 15.6f, 19.8f, 12.8f, 14f, 18.5f, 14f)
            "plus" -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }
            "glasses" -> {
                c.drawCircle(6.5f, 13f, 4f, paint)
                c.drawCircle(17.5f, 13f, 4f, paint)
                path.reset()
                path.moveTo(10.5f, 12.5f)
                path.quadTo(12f, 10.5f, 13.5f, 12.5f)
                c.drawPath(path, paint)
                line(2.5f, 12f, 1.5f, 8.5f)
                line(21.5f, 12f, 22.5f, 8.5f)
            }
            "tabs" -> {
                rect(3f, 8f, 17f, 20f, 2f)
                poly(false, 7f, 8f, 7f, 4f, 21f, 4f, 21f, 16f, 17f, 16f)
            }
            "chevron" -> poly(false, 6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
            "next" -> poly(false, 9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
            "check" -> poly(false, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            "close" -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            "bolt" -> {
                paint.style = android.graphics.Paint.Style.FILL
                poly(true, 13f, 2f, 5f, 13.5f, 11f, 13.5f, 10f, 22f, 19f, 10f, 13f, 10f, 14f, 2f)
            }
            "key" -> {
                c.drawCircle(8f, 15f, 4.5f, paint)
                line(11.2f, 11.8f, 20f, 3f)
                line(16f, 7f, 18.5f, 9.5f)
                line(18f, 5f, 20.5f, 7.5f)
            }
            "server" -> {
                rect(3.5f, 4f, 20.5f, 10.5f, 2f)
                rect(3.5f, 13.5f, 20.5f, 20f, 2f)
                dot(7f, 7.25f)
                dot(7f, 16.75f)
            }
            "spark" -> {
                path.reset()
                path.moveTo(12f, 3f)
                path.quadTo(12f, 12f, 21f, 12f)
                path.quadTo(12f, 12f, 12f, 21f)
                path.quadTo(12f, 12f, 3f, 12f)
                path.quadTo(12f, 12f, 12f, 3f)
                path.close()
                c.drawPath(path, paint)
            }
            "lock" -> {
                rect(5f, 10.5f, 19f, 20.5f, 2f)
                arc(8f, 4f, 16f, 14f, 180f, 180f)
                line(8f, 9f, 8f, 10.5f)
                line(16f, 9f, 16f, 10.5f)
            }
            "info" -> {
                c.drawCircle(12f, 12f, 9f, paint)
                line(12f, 11f, 12f, 16.5f)
                dot(12f, 7.6f)
            }
            "download" -> {
                line(12f, 4f, 12f, 15f)
                poly(false, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
                line(5f, 19.5f, 19f, 19.5f)
            }
            "trash" -> {
                line(4.5f, 6.5f, 19.5f, 6.5f)
                rect(6.5f, 6.5f, 17.5f, 20.5f, 2f)
                line(9.5f, 3.5f, 14.5f, 3.5f)
            }
            "edit" -> poly(true, 4f, 20f, 4.5f, 16f, 15.5f, 5f, 19f, 8.5f, 8f, 19.5f)
            "copy" -> {
                rect(8f, 8f, 20f, 20f, 2f)
                poly(false, 16f, 5.5f, 16f, 4f, 4f, 4f, 4f, 16f, 5.5f, 16f)
            }
            "place" -> {
                rect(3f, 5f, 21f, 19f, 2.5f)
                rect(6.5f, 8.5f, 13.5f, 14.5f, 1f)
            }
            "contrast" -> {
                c.drawCircle(12f, 12f, 8.5f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                arc(3.5f, 3.5f, 20.5f, 20.5f, -90f, 180f)
                path.reset()
                path.addArc(box, -90f, 180f)
                path.close()
                c.drawPath(path, paint)
            }
            "speed" -> {
                arc(3f, 5f, 21f, 23f, 180f, 180f)
                line(12f, 14f, 16.5f, 8.5f)
                dot(12f, 14f)
            }
            "dark" -> {
                path.reset()
                path.moveTo(15f, 3.5f)
                path.cubicTo(8f, 4f, 5f, 12f, 9f, 17f)
                path.cubicTo(12f, 20.5f, 18f, 20.5f, 20.5f, 16f)
                path.cubicTo(14f, 16.5f, 10f, 10f, 15f, 3.5f)
                c.drawPath(path, paint)
            }
            "list" -> {
                for (yy in floatArrayOf(7f, 12f, 17f)) {
                    dot(5f, yy)
                    line(9f, yy, 19f, yy)
                }
            }
            "touch" -> {
                rect(3f, 5f, 21f, 19f, 2.5f)
                c.drawCircle(12f, 12f, 2.5f, paint)
            }
            else -> c.drawCircle(12f, 12f, 7f, paint)
        }
        c.restore()
    }
}

// An icon at a size, in a colour that can change.
class IconView(context: Context, kind: String, private val sizeDp: Int = 22, color: Int = Ui.TEXT) : View(context) {
    var kind: String = kind
        set(v) {
            field = v
            invalidate()
        }
    var color: Int = color
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(context.dp(sizeDp), context.dp(sizeDp))
    override fun onDraw(canvas: android.graphics.Canvas) = Icons.draw(canvas, kind, width.toFloat(), color)
}

// ------------------------------------------------ sections, actions, tiles

// A card whose body folds away under its title (a tap on the title; remembered by key), with an icon and a
// chevron. Added to this column; returns the body to fill.
fun LinearLayout.section(title: String, key: String, icon: String? = null, open: Boolean = false, hint: String? = null): LinearLayout {
    val c = context
    val prefs = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val card = c.card()
    val head = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, c.dp(2), 0, c.dp(2))
    }
    if (icon != null) head.addView(IconView(c, icon, 20, Ui.ACCENT), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = c.dp(12) })
    head.addView(c.text(title, 16f, Ui.TEXT, Ui.BOLD), lp(0, weight = 1f))
    val chevron = IconView(c, "chevron", 18, Ui.MUTED)
    head.addView(chevron)
    card.addView(head)
    val body = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, c.dp(8), 0, 0)
    }
    if (hint != null) body.addView(c.text(hint, 12.5f, Ui.FAINT).apply { setPadding(0, 0, 0, c.dp(4)) })
    card.addView(body)
    var shown = prefs.getBoolean("open_$key", open)
    val column = this
    fun apply(animate: Boolean) {
        if (animate) android.transition.TransitionManager.beginDelayedTransition(column,
            android.transition.AutoTransition().apply { duration = 220 })
        body.visibility = if (shown) View.VISIBLE else View.GONE
        if (animate) chevron.animate().rotation(if (shown) 180f else 0f).setDuration(220).start()
        else chevron.rotation = if (shown) 180f else 0f
    }
    head.setOnClickListener {
        shown = !shown
        prefs.edit().putBoolean("open_$key", shown).apply()
        apply(true)
    }
    apply(false)
    addView(card)
    return body
}

// A small heading inside a card.
fun Context.subhead(label: String): TextView = text(label.uppercase(), 11f, Ui.FAINT, Ui.BOLD).apply {
    letterSpacing = 0.14f
    setPadding(0, dp(12), 0, dp(2))
}

// A round button with an icon and a word under it, lit (the accent) while on.
class RoundAction(context: Context, icon: String, label: String, onClick: () -> Unit) : LinearLayout(context) {
    private val disc = FrameLayout(context)
    private val glyph = IconView(context, icon, 24, Ui.TEXT)
    private val word = context.text(label, 12f, Ui.MUTED, Ui.BOLD)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        disc.addView(glyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(disc, LayoutParams(context.dp(58), context.dp(58)))
        word.gravity = Gravity.CENTER
        addView(word, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(7) })
        disc.setOnClickListener { onClick() }
        set(false)
    }

    private var shown: String? = null

    fun set(on: Boolean, label: String? = null, icon: String? = null) {
        val key = "$on|$label|$icon"
        if (key == shown) return
        shown = key
        val r = context.dp(29).toFloat()
        disc.background = ripple(if (on) accentGradient(r) else rounded(Ui.RAISED, r, context.dp(1), Ui.STROKE), r)
        disc.elevation = if (on) context.dp(6).toFloat() else 0f
        glyph.color = if (on) Ui.ON_ACCENT else Ui.TEXT
        if (icon != null) glyph.kind = icon
        if (label != null) word.text = label
        word.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
    }
}

// A source on Home's grid: an icon, its kind and a line of state; lit while on the glasses, dashed where there is
// none (sub says how to get one).
class GridTile(context: Context, icon: String, title: String, onClick: () -> Unit) : LinearLayout(context) {
    private val glyph = IconView(context, icon, 22, Ui.TEXT)
    private val head = context.text(title, 15f, Ui.TEXT, Ui.BOLD)
    private val line = context.text("", 11.5f, Ui.MUTED)
    private val dot = View(context)

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(14), context.dp(12), context.dp(13))
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(glyph, lp(ViewGroup.LayoutParams.WRAP_CONTENT, weight = 0f))
        top.addView(View(context), lp(0, weight = 1f))
        top.addView(dot, LayoutParams(context.dp(8), context.dp(8)))
        addView(top)
        addView(head, lp().apply { topMargin = context.dp(10) })
        addView(line)
        setOnClickListener { onClick() }
        set(STATE_IDLE, "", 0)
    }

    private var shown: String? = null

    // state: STATE_*; sub: the line under the title; dotColor: 0 none.
    fun set(state: Int, sub: String, dotColor: Int) {
        val key = "$state|$sub|$dotColor"
        if (key == shown) return
        shown = key
        val r = context.dp(20).toFloat()
        val d = context.dp(1)
        background = when (state) {
            STATE_ON -> ripple(accentGradient(r), r)
            STATE_NONE -> rounded(0, r).apply { setStroke(d, Ui.STROKE, context.dp(5).toFloat(), context.dp(4).toFloat()) }
            else -> ripple(rounded(Ui.RAISED, r, d, Ui.STROKE), r)
        }
        elevation = if (state == STATE_ON) context.dp(8).toFloat() else 0f
        val fg = when (state) {
            STATE_ON -> Ui.ON_ACCENT
            STATE_NONE -> Ui.FAINT
            else -> Ui.TEXT
        }
        glyph.color = fg
        head.setTextColor(fg)
        line.setTextColor(if (state == STATE_ON) 0xCC05110F.toInt() else Ui.FAINT)
        line.text = sub
        dot.background = if (dotColor != 0) rounded(dotColor, context.dp(4).toFloat()) else null
    }

    companion object {
        const val STATE_IDLE = 0
        const val STATE_ON = 1
        const val STATE_NONE = 2
    }
}

// A banner that needs the user: an icon, what is wrong, and the button that fixes it.
fun Context.banner(icon: String, message: String, action: String?, colour: Int = Ui.WARN, onAction: () -> Unit = {}): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded((colour and 0xFFFFFF) or 0x1F000000, dp(16).toFloat(), dp(1), (colour and 0xFFFFFF) or 0x55000000)
        setPadding(dp(14), dp(10), dp(10), dp(10))
        layoutParams = lp().apply { topMargin = dp(8) }
    }
    row.addView(IconView(this, icon, 20, colour), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
    row.addView(text(message, 13.5f, Ui.TEXT), lp(0, weight = 1f))
    if (action != null) row.addView(chip(action, true, onAction), lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
    return row
}

// ------------------------------------------------ the glasses, live

// What the glasses show, live, in a dark lens with HUD corners: the terminal in the panel's green or the screen's
// picture (a secure surface: it never films itself into the screen sharing); over it the source's name and each
// arm's battery; a word in the middle while nothing shows. A tap opens its tab.
class GlassesView(context: Context, val terminal: TerminalView, val picture: View) : FrameLayout(context) {
    private val name = context.text("", 11.5f, Ui.TEXT, Ui.BOLD).apply { letterSpacing = 0.12f }
    private val middle = context.text("", 15f, Ui.MUTED, Ui.BOLD).apply { gravity = Gravity.CENTER }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()
    private var left = -1
    private var right = -1
    private var leftCharging = false
    private var rightCharging = false
    private var live = false
    private var dotColour = Ui.FAINT

    init {
        background = rounded(0xFF000000.toInt(), context.dp(22).toFloat(), context.dp(1), Ui.STROKE)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        setWillNotDraw(false)
        val pad = context.dp(30)
        addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = pad
            leftMargin = context.dp(10)
            rightMargin = context.dp(10)
            bottomMargin = context.dp(10)
        })
        addView(picture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(middle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        name.background = rounded(0xCC0B0F14.toInt(), context.dp(10).toFloat())
        name.setPadding(context.dp(9), context.dp(4), context.dp(10), context.dp(4))
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = context.dp(12)
            topMargin = context.dp(10)
        })
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = w * 3 / 4
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    // mode: SHOW_*; title: the source ("LAPTOP · TERMINAL"); message: in the middle (empty: none); dot: the state's
    // colour; batteries: left %, right % (-1 unknown) and charging.
    fun set(mode: Int, title: String, message: String, dot: Int, l: Int, r: Int, lc: Boolean, rc: Boolean, linked: Boolean) {
        terminal.visibility = if (mode == SHOW_TERMINAL) View.VISIBLE else View.GONE
        picture.visibility = if (mode == SHOW_SCREEN) View.VISIBLE else View.GONE
        terminal.alpha = if (mode == SHOW_TERMINAL && message.isEmpty()) 1f else 0.35f
        val span = android.text.SpannableString("●  " + title.uppercase())
        span.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
        name.text = span
        middle.text = message
        middle.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        left = l
        right = r
        leftCharging = lc
        rightCharging = rc
        live = linked
        dotColour = dot
        invalidate()
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val d = resources.displayMetrics.density
        // HUD corners.
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.color = (Ui.ACCENT and 0xFFFFFF) or 0x99000000.toInt()
        val k = 16 * d
        val i = 9 * d
        val w = width.toFloat()
        val h = height.toFloat()
        for ((x, dx) in listOf(i to 1f, w - i to -1f)) for ((y, dy) in listOf(i + 22 * d to 1f, h - i to -1f)) {
            if (dy > 0 && dx > 0) continue // the source's name sits there
            canvas.drawLine(x, y, x + dx * k, y, paint)
            canvas.drawLine(x, y, x, y + dy * k, paint)
        }
        // Each arm's battery at the top right: L and R, a small battery that fills.
        paint.textSize = 11 * d
        paint.typeface = Ui.BOLD
        var x = w - 12 * d
        for ((label, pct, charging) in listOf(Triple("R", right, rightCharging), Triple("L", left, leftCharging))) {
            val bw = 22 * d
            val bh = 11 * d
            val top = 15 * d
            box.set(x - bw, top, x - 2 * d, top + bh)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.4f * d
            paint.color = if (live) 0xFFAEB6C6.toInt() else Ui.FAINT
            canvas.drawRoundRect(box, 3 * d, 3 * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(x - 2 * d, top + bh * 0.32f, x - 0.5f * d, top + bh * 0.68f, paint)
            if (pct > 0 && live) {
                paint.color = when {
                    pct <= 15 -> Ui.BAD
                    pct <= 35 -> Ui.WARN
                    else -> Ui.GOOD
                }
                canvas.drawRoundRect(box.left + 2 * d, box.top + 2 * d, box.left + 2 * d + (box.width() - 4 * d) * pct.coerceAtMost(100) / 100f,
                    box.bottom - 2 * d, 1.5f * d, 1.5f * d, paint)
            }
            if (charging && live) Icons.draw(canvas, "bolt", 10 * d, Ui.TEXT, box.centerX() - 5 * d, box.top + 0.5f * d)
            val txt = label + " " + if (pct >= 0 && live) "$pct%" else "—"
            paint.color = if (live) Ui.TEXT else Ui.FAINT
            val tw = paint.measureText(txt)
            canvas.drawText(txt, box.left - 6 * d - tw, top + bh - 1.5f * d, paint)
            x = box.left - 14 * d - tw
        }
    }

    companion object {
        const val SHOW_NONE = 0
        const val SHOW_TERMINAL = 1
        const val SHOW_SCREEN = 2
    }
}

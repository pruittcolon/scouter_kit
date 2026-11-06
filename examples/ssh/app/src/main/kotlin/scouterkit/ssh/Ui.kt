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

// A big tile for a mode: title and a line under it; filled with the accent while selected.
class Tile(context: Context, title: String, sub: String, onClick: () -> Unit) : LinearLayout(context) {
    private val head = context.text(title, 16.5f, Ui.TEXT, Ui.BLACK)
    private val line = context.text(sub, 12f, Ui.MUTED)

    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(16), context.dp(12), context.dp(16))
        addView(head)
        addView(line)
        setOnClickListener { onClick() }
        select(false)
    }

    fun select(on: Boolean, sub: String? = null) {
        val r = context.dp(20).toFloat()
        background = ripple(if (on) accentGradient(r) else rounded(Ui.RAISED, r, context.dp(1), Ui.STROKE), r)
        head.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT)
        line.setTextColor(if (on) 0xCC05110F.toInt() else Ui.MUTED)
        if (sub != null) line.text = sub
    }
}

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

// One arm's battery: its name, the percentage large, and a battery that fills with it (green, amber, red);
// a bolt while charging; dim while the arm is not linked.
class BatteryGauge(context: Context, private val label: String) : View(context) {
    private var pct = -1
    private var charging = false
    private var live = false
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()

    fun set(percent: Int, charging: Boolean, live: Boolean) {
        if (percent == pct && charging == this.charging && live == this.live) return
        pct = percent
        this.charging = charging
        this.live = live
        invalidate()
    }

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), context.dp(70))

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val level = when {
            !live || pct < 0 -> Ui.FAINT
            pct <= 15 -> Ui.BAD
            pct <= 35 -> Ui.WARN
            else -> Ui.GOOD
        }
        paint.style = android.graphics.Paint.Style.FILL
        paint.typeface = Ui.BOLD
        paint.textSize = sp(12.5f)
        paint.color = Ui.MUTED
        canvas.drawText(label.uppercase() + if (charging && live) "  ·  CHARGING" else "", 0f, sp(13f), paint)
        paint.typeface = Ui.BLACK
        paint.textSize = sp(28f)
        paint.color = if (live && pct >= 0) Ui.TEXT else Ui.FAINT
        canvas.drawText(if (pct >= 0) "$pct%" else "—", 0f, height - 6 * d, paint)
        // The battery: an outline, its nub, the fill.
        val bw = 46 * d
        val bh = 22 * d
        val right = width - 6 * d
        val top = height - bh - 10 * d
        box.set(right - bw, top, right - 4 * d, top + bh)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = if (live) 0xFFAEB6C6.toInt() else Ui.STROKE
        canvas.drawRoundRect(box, 5 * d, 5 * d, paint)
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawRoundRect(right - 3 * d, top + bh * 0.3f, right, top + bh * 0.7f, 2 * d, 2 * d, paint)
        if (pct > 0) {
            val inner = box.width() - 6 * d
            paint.color = level
            canvas.drawRoundRect(box.left + 3 * d, box.top + 3 * d, box.left + 3 * d + inner * pct.coerceAtMost(100) / 100f,
                box.bottom - 3 * d, 3 * d, 3 * d, paint)
        }
        if (charging && live) {
            paint.color = Ui.TEXT
            paint.textSize = sp(15f)
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("⚡", box.centerX(), box.centerY() + sp(5f), paint)
            paint.textAlign = android.graphics.Paint.Align.LEFT
        }
    }
}

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

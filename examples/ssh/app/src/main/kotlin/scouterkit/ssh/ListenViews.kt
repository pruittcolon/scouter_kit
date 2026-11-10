package scouterkit.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// The Listen page's instruments: the orb (Listen's state, breathing with what it hears), the trace (the last
// seconds of sound against the room's level), stat tiles, talk-share bars, speaker avatars and colours.
object ListenLook {
    // One colour per speaker, the same everywhere: You in the accent, the others from a palette by name.
    private val PALETTE = intArrayOf(0xFF818CF8.toInt(), 0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF60A5FA.toInt(),
        0xFF34D399.toInt(), 0xFFF87171.toInt(), 0xFFA78BFA.toInt(), 0xFFFB923C.toInt())

    fun color(name: String): Int = when {
        name.isEmpty() -> Ui.MUTED
        name == "You" -> Ui.ACCENT
        else -> PALETTE[abs(name.lowercase().hashCode()) % PALETTE.size]
    }

    fun initials(name: String): String = when {
        name.isEmpty() -> "?"
        name.startsWith("Voice ") -> "V" + name.removePrefix("Voice ").take(2)
        else -> name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    }

    // A small capital label (the tiles', the rows' headers).
    fun Context.caps(label: String, color: Int = Ui.MUTED, size: Float = 10.5f): TextView =
        text(label.uppercase(), size, color, Ui.BOLD).apply { letterSpacing = 0.14f }

    // A stat tile: a big value, a small label.
    fun Context.tile(): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.RAISED, dp(16).toFloat(), dp(1), Ui.STROKE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val value = text("–", 20f, Ui.TEXT, Ui.BLACK)
        box.addView(value)
        return box to value
    }

    fun Context.tileLabel(box: LinearLayout, label: String) = box.addView(caps(label, Ui.FAINT, 9.5f))
}

// The orb: Listen's state at a glance. Off: a dim ring. Listening: indigo, breathing slowly. Speech: the accent,
// its halo following the level. Paused: amber. Tap: on / off; long press: pause / listen again.
class ListenOrb(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f // 0 ... 1
    private var shown = 0f
    private var state = 0 // 0 off, 1 listening, 2 speech, 3 paused
    private var phase = 0f

    fun set(level: Float, state: Int) {
        this.level = level.coerceIn(0f, 1f)
        this.state = state
        phase += 0.12f
        shown += (this.level - shown) * 0.35f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.52f
        val col = when (state) {
            2 -> Ui.ACCENT
            1 -> Ui.ACCENT2
            3 -> Ui.WARN
            else -> Ui.FAINT
        }
        p.style = Paint.Style.FILL
        // Halos: three rings, wider with the level, breathing when quiet.
        if (state != 0) for (i in 3 downTo 1) {
            val grow = if (state == 2) shown * 0.55f else 0.06f * (1 + sin(phase + i).toFloat())
            p.color = col
            p.alpha = (34 / i)
            c.drawCircle(cx, cy, r * (1f + 0.18f * i + grow), p)
        }
        p.alpha = 255
        p.color = Ui.RAISED
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.12f
        p.color = col
        c.drawCircle(cx, cy, r * 0.92f, p)
        // The core: a microphone glyph, as bars of the level.
        p.style = Paint.Style.FILL
        val bars = 5
        val bw = r * 0.12f
        for (i in 0 until bars) {
            val k = (i - (bars - 1) / 2f)
            val amp = if (state == 2) (0.25f + shown * (1f - abs(k) * 0.18f)) else if (state == 0) 0.12f else 0.2f
            val h = r * 0.9f * amp.coerceIn(0.1f, 0.95f)
            val x = cx + k * bw * 1.8f
            c.drawRoundRect(RectF(x - bw / 2, cy - h / 2, x + bw / 2, cy + h / 2), bw / 2, bw / 2, p)
        }
    }
}

// The trace: the last seconds of what the detector hears, a bar each tick (lit while speech is heard), the room's
// level as a dashed line, the time running right to left.
class ListenTrace(context: Context) : View(context) {
    private val n = 90
    private val levels = FloatArray(n) { -100f }
    private val speech = BooleanArray(n)
    private var at = 0
    private var floor = -100f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Ui.WARN
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    fun push(db: Float, isSpeech: Boolean, floorDb: Float) {
        levels[at] = db
        speech[at] = isSpeech
        at = (at + 1) % n
        floor = floorDb
        invalidate()
    }

    private fun y(db: Float) = height - ((db + 80f) / 70f).coerceIn(0f, 1f) * (height - 4f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val bw = w / n
        p.color = Ui.RAISED
        c.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), 18f, 18f, p)
        for (i in 0 until n) {
            val k = (at + i) % n
            val top = y(levels[k])
            p.color = if (speech[k]) Ui.ACCENT else Ui.ACCENT2
            p.alpha = if (speech[k]) 255 else 150
            c.drawRoundRect(RectF(i * bw + bw * 0.2f, top, (i + 1) * bw - bw * 0.2f, height.toFloat() - 2f), 3f, 3f, p)
        }
        p.alpha = 255
        val fy = y(floor)
        c.drawLine(0f, fy, w, fy, dash)
    }
}

// Talk share: a bar per person in their colour, their share and time.
class ShareBars(context: Context) : View(context) {
    private var rows: List<Triple<String, Float, String>> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun set(rows: List<Triple<String, Float, String>>) {
        this.rows = rows
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, maxOf(1, rows.size) * context.dp(30))
    }

    override fun onDraw(c: Canvas) {
        val rh = context.dp(30).toFloat()
        t.textSize = context.dp(12).toFloat()
        val label = context.dp(86).toFloat()
        for ((i, r) in rows.withIndex()) {
            val y = i * rh
            t.color = ListenLook.color(r.first)
            c.drawText(r.first.take(12), 0f, y + rh * 0.62f, t)
            val w = (width - label - context.dp(70)).toFloat()
            p.color = Ui.RAISED
            c.drawRoundRect(RectF(label, y + rh * 0.3f, label + w, y + rh * 0.72f), 8f, 8f, p)
            p.color = ListenLook.color(r.first)
            c.drawRoundRect(RectF(label, y + rh * 0.3f, label + w * r.second.coerceIn(0.02f, 1f), y + rh * 0.72f), 8f, 8f, p)
            t.color = Ui.MUTED
            c.drawText(r.third, label + w + context.dp(8), y + rh * 0.62f, t)
        }
    }
}

// A round avatar: the person's initials on their colour.
class Avatar(context: Context, private val name: String, private val sizeDp: Int = 40) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(sizeDp)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.color = ListenLook.color(name)
        p.alpha = 60
        c.drawCircle(r, r, r, p)
        p.alpha = 255
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.08f
        c.drawCircle(r, r, r * 0.92f, p)
        p.style = Paint.Style.FILL
        p.textSize = r * 0.72f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(ListenLook.initials(name), r, r + p.textSize * 0.36f, p)
    }
}

// A pulsing dot (the words being said now).
class Pulse(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private var t = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(14)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        t += 0.2f
        val r = width / 2f
        p.alpha = 70
        c.drawCircle(r, r, r * (0.6f + 0.4f * ((sin(t.toDouble()) + 1) / 2).toFloat()), p)
        p.alpha = 255
        c.drawCircle(r, r, r * 0.42f, p)
        if (isShown) postInvalidateDelayed(60)
    }
}

// A thin progress bar (model downloads).
class Progress(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var fraction = 0f
        set(v) {
            field = v
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(6))

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = Ui.RAISED
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
        p.color = Ui.ACCENT
        c.drawRoundRect(RectF(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat()), r, r, p)
    }
}

fun Context.rowOf(vararg views: View, gap: Int = 8): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    for ((i, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        .apply { if (i > 0) leftMargin = dp(gap) })
}

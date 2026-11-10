package scouterkit.ssh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView

// The picture the glasses get (Native.screenPreview): the last one sent, or, while nothing goes to them, the one
// they would get. PhoneScreen publishes it (a few times a second, only while `wanted`); PreviewView shows it.
object Preview {
    @Volatile var wanted = false // the home screen shows the preview
    @Volatile var listener: (() -> Unit)? = null
    private val lock = Any()
    private var pixels = ByteArray(0)
    private var width = 0
    private var height = 0
    private var sent = false
    private var stats = IntArray(11)

    fun publish(p: ByteArray, w: Int, h: Int, sent: Boolean, stats: IntArray) {
        synchronized(lock) {
            if (pixels.size < w * h) pixels = ByteArray(w * h)
            System.arraycopy(p, 0, pixels, 0, w * h)
            width = w
            height = h
            this.sent = sent
            this.stats = stats
        }
        listener?.invoke()
    }

    fun clear() {
        synchronized(lock) {
            width = 0
            height = 0
            sent = false
        }
        listener?.invoke()
    }

    // The picture (w x h, 0 ... 255; w 0: none), whether it is the one on the glasses, and Native.screenStats.
    fun <T> read(f: (ByteArray, Int, Int, Boolean, IntArray) -> T): T = synchronized(lock) { f(pixels, width, height, sent, stats) }
}

// The preview on the home screen. A secure surface: Android leaves it out of the screen capture (it shows black,
// that is see-through, on the glasses), so the preview never films itself.
class PreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var white = false
        set(v) {
            field = v
            refresh()
        }
    private var bitmap: Bitmap? = null
    private var argb = IntArray(0)
    private var aspect = 610f / 471f
    private var ready = false
    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE
        color = Ui.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = context.dp(13).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        holder.addCallback(this)
        setSecure(true)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w / aspect).toInt())
    }

    // The latest picture (main thread).
    fun refresh() {
        val shape = Preview.read { p, w, h, _, _ ->
            if (w <= 0 || h <= 0) {
                bitmap = null
                return@read 0f
            }
            if (argb.size < w * h) argb = IntArray(w * h)
            val lut = IntArray(256) { v ->
                if (white) Color.rgb(v, v, v) else Color.rgb(v * 50 / 255, v, v * 110 / 255) // the panels' green
            }
            for (i in 0 until w * h) argb[i] = lut[p[i].toInt() and 255]
            val b = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.setPixels(argb, 0, w, 0, 0, w, h)
            bitmap = b
            w.toFloat() / h
        }
        if (shape > 0f && kotlin.math.abs(shape - aspect) > 0.01f) {
            aspect = shape
            requestLayout()
        }
        paint()
    }

    private fun paint() {
        if (!ready) return
        val c: Canvas = holder.lockCanvas() ?: return
        try {
            c.drawColor(Color.BLACK)
            val b = bitmap
            if (b != null) {
                c.drawBitmap(b, null, Rect(0, 0, c.width, c.height), pixelPaint)
            } else {
                c.drawText("Share your screen to see what the glasses get", c.width / 2f, c.height / 2f, label)
            }
            val inset = edge.strokeWidth / 2
            c.drawRect(RectF(inset, inset, c.width - inset, c.height - inset), edge)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        paint()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = paint()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
    }
}

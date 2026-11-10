package scouterkit.ssh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.util.Locale

// Transitions on the glasses (native/fx.hpp, Native.transition): the old picture goes the moment a switch is
// asked for and the new one comes in with an animation of about half a second, played on the arms. Each way of
// switching has its own: Alt+Left / Right (the sources across) a data stream from that side, Alt+Up / Down
// (terminal and screen) a CRT scan line from the top or the bottom, a jump (Alt+1 ... 9, a click on the
// dashboard, voice, the TouchBar) tiles that decrypt, the tabs a quick stream, the display a CRT switching on
// and off. Between them a label says where the glasses are going.
object Fx {
    const val NONE = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val UP = 3
    const val DOWN = 4
    const val JUMP = 5
    const val ON = 6
    const val OFF = 7
    const val TAB_LEFT = 8
    const val TAB_RIGHT = 9
    const val CUT = 10 // one frame each way: a switch right after another (QUICK_MS)

    // A switch this soon after the one before goes as a cut: toggling quickly isn't held up by animations.
    const val QUICK_MS = 1500L

    // The style for a way of switching: an arrow ("left", "right", "up", "down"), next / prev, else a jump.
    fun style(how: String?): Int = when (how) {
        "left", Settings.ACTION_PREV -> LEFT
        "right", Settings.ACTION_NEXT -> RIGHT
        "up" -> UP
        "down" -> DOWN
        else -> JUMP
    }

    class Label(val packed: ByteArray, val w: Int, val h: Int)

    private const val H = 26
    private const val MAX_W = 312 // 4 bpp: w / 2 * h stays within the arms' label (4096 bytes)

    // The label in the glasses' 16 levels (black is see-through), 4 bpp: HUD corner brackets, a chevron, the
    // source's name bright and its kind dimmer ("LAPTOP // TERMINAL").
    fun label(name: String, kind: String): Label {
        val title = TextPaint().apply {
            isAntiAlias = true
            textSize = 15f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.14f
            color = grey(15)
        }
        val sub = TextPaint(title).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            color = grey(10)
        }
        val slash = TextPaint(sub).apply { color = grey(6) }
        val k = kind.uppercase(Locale.getDefault())
        val sep = "  //  "
        val fixed = 34f + slash.measureText(sep) + sub.measureText(k)
        val n = TextUtils.ellipsize(name.uppercase(Locale.getDefault()), title, MAX_W - fixed, TextUtils.TruncateAt.END).toString()
        val w = minOf(MAX_W, ((fixed + title.measureText(n)).toInt() + 3) and 1.inv())
        val bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val line = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 1f; color = grey(15) }
        val arm = 7f
        for ((x, dx) in listOf(0.5f to 1f, w - 0.5f to -1f)) {
            c.drawLine(x, 0.5f, x + dx * arm, 0.5f, line)
            c.drawLine(x, 0.5f, x, arm, line)
            c.drawLine(x, H - 0.5f, x + dx * arm, H - 0.5f, line)
            c.drawLine(x, H - 0.5f, x, H - arm, line)
        }
        // A chevron, then the words on one baseline.
        val fill = Paint().apply { isAntiAlias = true; color = grey(15) }
        val cy = H / 2f
        c.drawPath(Path().apply {
            moveTo(10f, cy - 5f)
            lineTo(16f, cy)
            lineTo(10f, cy + 5f)
            close()
        }, fill)
        val base = cy + title.textSize * 0.36f
        var x = 22f
        c.drawText(n, x, base, title)
        x += title.measureText(n)
        c.drawText(sep, x, base, slash)
        x += slash.measureText(sep)
        c.drawText(k, x, base, sub)
        val px = IntArray(w * H)
        bmp.getPixels(px, 0, w, 0, 0, w, H)
        bmp.recycle()
        val stride = w / 2
        val packed = ByteArray(stride * H)
        for (y in 0 until H) for (xx in 0 until w) {
            val v = px[y * w + xx]
            val luma = (Color.red(v) * 77 + Color.green(v) * 150 + Color.blue(v) * 29) shr 8
            val level = (luma * 15 + 127) / 255
            val i = y * stride + xx / 2
            packed[i] = (packed[i].toInt() or (if (xx % 2 == 0) (level shl 4) else level)).toByte()
        }
        return Label(packed, w, H)
    }

    private fun grey(level: Int) = Color.rgb(level * 17, level * 17, level * 17)
}

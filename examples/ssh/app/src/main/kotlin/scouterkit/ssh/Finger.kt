package scouterkit.ssh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper

// A finger held on the phone's screen, as the trackpad makes one (VoxKeys' continued strokes): put down where the
// cursor is, moved by what comes, lifted when told. The button held and moved drags (text, sliders, the
// notifications pulled down); held still it is a long press; two-finger swipes slide the page under it, both ways,
// and let it go at their speed, so a quick one flings the page on. A slide that reaches the screen's edge lets go
// and puts the finger down again where it started. One piece at a time, each sent when the one before has run.
object Finger {
    private const val PIECE_MS = 16L
    private const val LIFT_MS = 40L // the last piece of a quick slide: the app reads the fling's speed from it
    private const val STEP = 90f // px at most a piece (more waits for the next)

    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null // the stroke under way (null: none yet)
    private var x = 0f // where the finger is (the end of the last piece)
    private var y = 0f
    private var w = 1f
    private var h = 1f
    private var ox = 0f // where a slide puts it down again
    private var oy = 0f
    private var again = false // a slide (at the edge: let go, down again at ox, oy)
    private var down = false // held, or about to be
    private var mx = 0f // movement not sent yet
    private var my = 0f
    private var lift = false
    private var vx = 0f // the lift's speed, px a ms
    private var vy = 0f
    private var busy = false
    private var gen = 0 // pieces of a finger that was dropped answer to nothing

    // Down at px, py of a width x height screen. False: VoxKeys is off (nothing happens).
    fun press(px: Float, py: Float, width: Int, height: Int, slide: Boolean): Boolean {
        if (VoxKeys.service() == null) return false
        main.post {
            if (down) return@post
            w = width.coerceAtLeast(2).toFloat()
            h = height.coerceAtLeast(2).toFloat()
            x = px.coerceIn(0f, w - 1)
            y = py.coerceIn(0f, h - 1)
            ox = x
            oy = y
            again = slide
            down = true
            stroke = null
            lift = false
            mx = 0f
            my = 0f
            pump()
        }
        return true
    }

    fun move(dx: Float, dy: Float) {
        main.post {
            if (!down || lift) return@post
            mx += dx
            my += dy
            pump()
        }
    }

    // Up; speedX / speedY (px a ms): the finger keeps that speed for its last piece (a fling).
    fun release(speedX: Float = 0f, speedY: Float = 0f) {
        main.post {
            if (!down) return@post
            lift = true
            vx = speedX
            vy = speedY
            pump()
        }
    }

    // Another gesture takes the screen (a tap, a long press): Android ends the held stroke itself.
    fun drop() = main.post { reset() }

    private fun reset() {
        gen++
        busy = false
        down = false
        stroke = null
        lift = false
        mx = 0f
        my = 0f
    }

    private fun pump() {
        if (busy || !down) return
        val s = VoxKeys.service() ?: return reset()
        val prev = stroke
        if (prev == null) { // down
            val piece = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, PIECE_MS, true)
            stroke = piece
            return send(s, piece)
        }
        if (!lift && mx == 0f && my == 0f) return // held still: nothing to send
        val tx: Float
        val ty: Float
        var ms = PIECE_MS
        if (lift) {
            val quick = vx != 0f || vy != 0f
            if (quick) ms = LIFT_MS
            tx = x + mx + (vx * ms).coerceIn(-STEP * 3, STEP * 3)
            ty = y + my + (vy * ms).coerceIn(-STEP * 3, STEP * 3)
            mx = 0f
            my = 0f
        } else {
            val sx = mx.coerceIn(-STEP, STEP)
            val sy = my.coerceIn(-STEP, STEP)
            mx -= sx
            my -= sy
            tx = x + sx
            ty = y + sy
        }
        val cx = tx.coerceIn(0f, w - 1)
        val cy = ty.coerceIn(0f, h - 1)
        val edge = again && (cx != tx || cy != ty)
        val up = lift || edge
        val piece = prev.continueStroke(Path().apply { moveTo(x, y); lineTo(cx, cy) }, 0, ms, !up)
        x = cx
        y = cy
        if (up) {
            stroke = null
            if (lift) {
                down = false
                lift = false
            } else { // the edge: down again where the slide started, for what is left
                x = ox
                y = oy
            }
        } else stroke = piece
        send(s, piece)
    }

    private fun send(s: AccessibilityService, piece: GestureDescription.StrokeDescription) {
        busy = true
        val mine = gen
        val ok = runCatching {
            s.dispatchGesture(GestureDescription.Builder().addStroke(piece).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (mine != gen) return
                    busy = false
                    pump()
                }
                override fun onCancelled(g: GestureDescription?) {
                    if (mine == gen) reset()
                }
            }, main)
        }.getOrDefault(false)
        if (!ok) reset()
    }
}

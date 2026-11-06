package scouterkit.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

// The phone as a touchpad for the glasses' pointer: one finger moves it a cell at a time (cellX, cellY pixels of
// travel each), a tap clicks, a long press right-clicks, two fingers up or down scroll. Commands go to the
// service (ScouterService.ACTION_POINTER).
class Touchpad(context: Context) : View(context) {
    private val cellX = context.dp(14).toFloat()
    private val cellY = context.dp(22).toFloat()
    private var lastX = 0f
    private var lastY = 0f
    private var accX = 0f
    private var accY = 0f
    private var downAt = 0L
    private var moved = 0f
    private var fingers = 0
    private val longPress = Runnable {
        if (moved < context.dp(8) && fingers == 1) {
            send("right")
            downAt = 0L // no tap after it
        }
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.FAINT
        textSize = context.dp(13).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        background = rounded(Ui.RAISED, context.dp(18).toFloat(), context.dp(1), Ui.STROKE)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), context.dp(190))
    }

    override fun onDraw(c: Canvas) {
        c.drawText("Drag: move · tap: click · hold: right click", width / 2f, height / 2f - context.dp(6), hint)
        c.drawText("two fingers: scroll", width / 2f, height / 2f + context.dp(14), hint)
    }

    private fun send(cmd: String) = ScouterService.send(context, ScouterService.ACTION_POINTER, "cmd" to cmd)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true) // the page does not scroll under the pad
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                accX = 0f
                accY = 0f
                moved = 0f
                fingers = 1
                downAt = SystemClock.uptimeMillis()
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = e.pointerCount
                accY = 0f
                lastY = e.getY(0)
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.getX(0) - lastX
                val dy = e.getY(0) - lastY
                lastX = e.getX(0)
                lastY = e.getY(0)
                moved += abs(dx) + abs(dy)
                if (fingers >= 2) { // scroll
                    accY += dy
                    while (abs(accY) >= cellY) {
                        send(if (accY > 0) "up" else "down") // fingers down: the text goes down, as on a phone
                        accY -= if (accY > 0) cellY else -cellY
                    }
                } else {
                    accX += dx
                    accY += dy
                    val cx = (accX / cellX).toInt()
                    val cy = (accY / cellY).toInt()
                    if (cx != 0 || cy != 0) {
                        send("move:$cx:$cy")
                        accX -= cx * cellX
                        accY -= cy * cellY
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val tap = downAt > 0 && SystemClock.uptimeMillis() - downAt < 300 && moved < context.dp(10) && fingers == 1
                if (tap) send("click")
                fingers = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                fingers = 0
            }
        }
        return true
    }
}

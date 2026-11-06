package scouterkit.ssh

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.WindowManager

// This phone's screen (Android's screen sharing, MediaProjection) captured at a reduced size and handed to the
// glasses (Native.screenFrame) at most `Settings.screenFps` times a second; the native side skips unchanged
// frames and waits while the glasses are busy, so a slow link only lowers the rate. A turn of the phone
// resizes the capture. The capture runs on its own thread.
class PhoneScreen(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) {
    private val settings = Settings(context)
    private val thread = HandlerThread("phone-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var size = 0 to 0
    @Volatile private var released = false
    @Volatile var state = Native.SCREEN_SAME // the last frame's outcome

    private val turns = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) handler.post { resize() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            try {
                grab()
            } catch (e: Exception) {
                Log.w(ScouterService.TAG, "screen: ${e.message}")
            }
            handler.postDelayed(this, 1000L / settings.screenFps.coerceIn(1, 30))
        }
    }

    fun start() {
        // Android 14+: the callback before the virtual display.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    release()
                    onStopped()
                }
            }
        }, handler)
        size = captureSize()
        val r = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay("scouter-glasses", size.first, size.second,
            context.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface,
            null, handler)
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(turns, handler)
        handler.postDelayed(tick, 300)
    }

    // The screen's shape at most 1600 px on its long side: enough for 540 x 471 on the glasses (the native side
    // averages the pixels), and light to convert.
    private fun captureSize(): Pair<Int, Int> {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val scale = minOf(1f, 1600f / maxOf(b.width(), b.height()))
        return ((b.width() * scale).toInt() / 2 * 2).coerceAtLeast(2) to ((b.height() * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }

    // The phone turned: a capture of the new shape (one virtual display per projection, resized).
    private fun resize() {
        if (released) return
        val next = captureSize()
        if (next == size) return
        size = next
        val old = reader
        val r = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 2)
        reader = r
        display?.resize(size.first, size.second, context.resources.displayMetrics.densityDpi)
        display?.surface = r.surface
        old?.close()
    }

    private fun grab() {
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            state = Native.screenFrame(plane.buffer, image.width, image.height, plane.rowStride, settings.screenBand,
                settings.screenBandPos, settings.screenInvert, settings.screenLevels, settings.screenHideCase,
                settings.screenHideFolded)
        } finally {
            image.close()
        }
    }

    // From any thread: the capture's own thread lets go once a frame in progress is done.
    fun release() {
        if (released) return
        released = true
        if (android.os.Looper.myLooper() == thread.looper) close() else handler.post { close() }
    }

    private fun close() {
        handler.removeCallbacks(tick)
        runCatching { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(turns) }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        display = null
        reader = null
        thread.quitSafely()
    }
}

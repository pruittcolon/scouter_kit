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
// frames and waits while the glasses are busy, so a slow link only lowers the rate. The newest image stays:
// it goes again while the link is busy, the glasses are not showing the screen yet, a coarse version of it
// went (auto detail) or the settings changed; and it feeds the preview (Preview). A turn of the phone resizes
// the capture. The capture runs on its own thread.
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

    // The newest image; due: it (or its settings) has not reached the glasses yet.
    private var held: android.media.Image? = null
    private var due = false
    private var options = IntArray(0)
    // The preview: its buffers, when it went last, whether a newer picture waits.
    private val previewPixels = ByteArray(640 * 480)
    private val previewInfo = IntArray(3)
    private var previewAt = 0L
    private var previewDirty = true
    private var previewWanted = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val period = 1000L / settings.screenFps.coerceIn(1, 30)
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                grab()
            } catch (e: Exception) {
                Log.w(ScouterService.TAG, "screen: ${e.message}")
            }
            // Busy: try again soon (the link frees up between frames); otherwise the next frame's turn.
            val spent = android.os.SystemClock.uptimeMillis() - t0
            handler.postDelayed(this, if (due && state == Native.SCREEN_BUSY) 12L else (period - spent).coerceAtLeast(1L))
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
        val r = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 3)
        reader = r
        display = projection.createVirtualDisplay("scouter-glasses", size.first, size.second,
            context.resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface,
            null, handler)
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(turns, handler)
        handler.postDelayed(tick, 300)
    }

    // The screen at its own resolution (up to 2400 px on the long side): zoom and the band then have every
    // pixel to work with; the native side averages them down.
    private fun captureSize(): Pair<Int, Int> {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val scale = minOf(1f, 2400f / maxOf(b.width(), b.height()))
        return ((b.width() * scale).toInt() / 2 * 2).coerceAtLeast(2) to ((b.height() * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }

    // The phone turned: a capture of the new shape (one virtual display per projection, resized).
    private fun resize() {
        if (released) return
        val next = captureSize()
        if (next == size) return
        size = next
        val old = reader
        held?.close()
        held = null
        due = false
        val r = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 3)
        reader = r
        display?.resize(size.first, size.second, context.resources.displayMetrics.densityDpi)
        display?.surface = r.surface
        old?.close()
    }

    private fun grab() {
        val fresh = runCatching { reader?.acquireLatestImage() }.getOrNull()
        if (fresh != null) {
            held?.close()
            held = fresh
            due = true
        }
        val image = held ?: return
        val plane = image.planes[0]
        val now = settings.screenOptions()
        if (!now.contentEquals(options)) {
            options = now
            due = true
            previewDirty = true
        }
        if (due) {
            state = Native.screenFrame(plane.buffer, image.width, image.height, plane.rowStride, options)
            // Again later: the link was busy, a coarser version went, the panels are off (in the case), or the
            // glasses show something else now.
            due = state == Native.SCREEN_BUSY || state == Native.SCREEN_LATER || state == Native.SCREEN_STOPPED ||
                state == Native.SCREEN_HIDDEN
            if (fresh != null || state == Native.SCREEN_SHOWN || state == Native.SCREEN_LATER) previewDirty = true
        }
        preview(image, plane)
    }

    // At most four times a second, and every 2 s for the numbers, while the home screen shows it.
    private fun preview(image: android.media.Image, plane: android.media.Image.Plane) {
        val wanted = Preview.wanted
        if (wanted && !previewWanted) previewDirty = true
        previewWanted = wanted
        if (!wanted) return
        val t = android.os.SystemClock.uptimeMillis()
        if (t - previewAt < 250 || (!previewDirty && t - previewAt < 2000)) return
        previewAt = t
        previewDirty = false
        if (Native.screenPreview(plane.buffer, image.width, image.height, plane.rowStride, options, settings.dx,
                settings.dy, previewPixels, previewInfo))
            Preview.publish(previewPixels, previewInfo[0], previewInfo[1], previewInfo[2] == 1, Native.screenStats())
    }

    // From any thread: the capture's own thread lets go once a frame in progress is done.
    fun release() {
        if (released) return
        released = true
        if (android.os.Looper.myLooper() == thread.looper) close() else handler.post { close() }
    }

    private fun close() {
        handler.removeCallbacks(tick)
        runCatching { held?.close() }
        held = null
        Preview.clear()
        runCatching { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(turns) }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        display = null
        reader = null
        thread.quitSafely()
    }
}

package scouterkit.ssh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// A server's screen (the laptop's) on the glasses, over SSH: there `vox-screen WIDTH HEIGHT FPS` writes JPEG
// images one after another (each the whole desktop inside WIDTH x HEIGHT). The newest goes to the glasses
// (Native.screenFrame) the way the phone's own screen does, only while it is the source shown
// (Session.screenSource); it feeds the preview too. One thread reads, one sends.
class RemoteScreen(private val context: Context, val server: Settings.Server, private val onStopped: (String) -> Unit) {
    val key get() = "screen:${server.id}"
    @Volatile var state = Native.SCREEN_SAME
    @Volatile var images = 0 // images received so far (the service shows the screen once one has come)
    @Volatile private var released = false
    @Volatile private var stream: Bootstrap.Stream? = null
    private val latest = AtomicReference<ByteArray?>(null)
    private val wake = Semaphore(0)

    fun start() {
        Thread({ read() }, "remote-screen-read").start()
        Thread({ send() }, "remote-screen-send").start()
    }

    // Another go now (a panel over the picture changed: the options carry it).
    fun poke() = wake.release()

    fun release() {
        if (released) return
        released = true
        runCatching { stream?.close() }
        wake.release()
    }

    private fun read() {
        val settings = Settings(context)
        val w = settings.remoteWidth.coerceIn(320, 1920)
        val h = w * 10 / 16
        val fps = settings.screenFps.coerceIn(1, 30)
        val why = try {
            // A command over SSH gets no login shell: ~/.local/bin (where a user installs it) is not on its PATH.
            val s = Bootstrap(Vault(context)).stream(server.host, server.port, server.user,
                "PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\" vox-screen $w $h $fps")
            stream = s
            if (released) {
                s.close()
                return
            }
            parse(s.input)
            if (released) return
            Thread.sleep(300) // the exit status and the last errors
            val err = s.errors
            s.close()
            "${server.name} screen ended" + when {
                err.contains("not found") || s.exitStatus == 127 -> ": vox-screen is not installed there"
                err.isNotEmpty() -> ": " + err.lines().last().take(160)
                else -> ""
            }
        } catch (e: Bootstrap.UnknownHost) {
            "${server.name}: show its terminal once to trust the server first"
        } catch (e: Exception) {
            if (released) return
            "${server.name} screen: ${Bootstrap.plain(e, server.host, server.port)}"
        }
        Log.i(ScouterService.TAG, "remote screen: $why")
        if (!released) {
            released = true
            wake.release()
            onStopped(why)
        }
    }

    // JPEGs one after another: FF D8 ... FF D9 (the coded data stuffs every FF byte, so FF D9 ends an image).
    private fun parse(input: InputStream) {
        val image = ByteArrayOutputStream(256 * 1024)
        val chunk = ByteArray(64 * 1024)
        var inside = false
        var prev = -1
        while (!released) {
            val n = input.read(chunk)
            if (n < 0) return
            var from = 0
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xFF
                if (!inside) {
                    if (prev == 0xFF && b == 0xD8) {
                        inside = true
                        image.reset()
                        image.write(0xFF)
                        from = i
                    }
                } else if (prev == 0xFF && b == 0xD9) {
                    image.write(chunk, from, i - from + 1)
                    latest.set(image.toByteArray())
                    images++
                    wake.release()
                    inside = false
                }
                prev = b
            }
            if (inside) {
                image.write(chunk, from, n - from)
                if (image.size() > 16 * 1024 * 1024) { // not a JPEG stream
                    inside = false
                    image.reset()
                }
            }
        }
    }

    private fun send() {
        val settings = Settings(context)
        val period = 1000L / settings.screenFps.coerceIn(1, 30)
        var bitmap: Bitmap? = null
        var buffer: ByteBuffer? = null
        var due = false
        var options = IntArray(0)
        var previewAt = 0L
        val previewPixels = ByteArray(640 * 480)
        val info = IntArray(3)
        val decode = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var wasMine = false
        while (!released) {
            // Unseen (another source in front), the newest image waits undecoded; shown again, it goes at once.
            val mine = Session.screenSource == key
            if (mine && !wasMine) due = true
            wasMine = mine
            if (mine) latest.getAndSet(null)?.let { jpeg ->
                val b = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decode) }.getOrNull()
                if (b != null) {
                    if (buffer == null || buffer!!.capacity() < b.byteCount) buffer = ByteBuffer.allocateDirect(b.byteCount)
                    buffer!!.rewind()
                    b.copyPixelsToBuffer(buffer!!)
                    bitmap?.recycle()
                    bitmap = b
                    due = true
                }
            }
            val now = settings.screenOptions()
            if (!now.contentEquals(options)) {
                options = now
                due = true
            }
            val b = bitmap
            val buf = buffer
            if (b != null && buf != null && mine) {
                if (due) {
                    buf.rewind()
                    state = Native.screenFrame(buf, b.width, b.height, b.rowBytes, options)
                    due = state == Native.SCREEN_BUSY || state == Native.SCREEN_LATER || state == Native.SCREEN_HIDDEN ||
                        state == Native.SCREEN_STOPPED
                }
                val t = android.os.SystemClock.uptimeMillis()
                if (Preview.wanted && t - previewAt >= 250) {
                    previewAt = t
                    buf.rewind()
                    if (Native.screenPreview(buf, b.width, b.height, b.rowBytes, options, settings.dx, settings.dy,
                            previewPixels, info))
                        Preview.publish(previewPixels, info[0], info[1], info[2] == 1, Native.screenStats())
                }
            }
            // A new image, or another go (soon while the link is busy).
            wake.tryAcquire(if (due && state == Native.SCREEN_BUSY) 12L else period, TimeUnit.MILLISECONDS)
        }
        bitmap?.recycle()
    }
}

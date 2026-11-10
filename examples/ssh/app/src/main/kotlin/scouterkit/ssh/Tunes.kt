package scouterkit.ssh

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Tunes: what the synthwave scene (TunesScene on the arms) and the dashboard's Tunes bar move with. Following the
// music: Android's output mix through a Visualizer (RECORD_AUDIO): the beat (a jump in the bass over its recent
// average), the volume and twelve bands; or a steady tempo (no permission). pulse, level and bars run 0 ... 1.
object Tunes {
    const val BEAT = "beat"     // a pulse on each beat, the floor faster with the volume
    const val VOLUME = "volume" // the pulse and the floor follow the volume
    const val STEADY = "steady" // a pulse at Settings.tunesBpm while something plays

    @Volatile var pulse = 0f
    @Volatile var level = 0f
    @Volatile var bars = FloatArray(12)
    @Volatile var live = false // the music itself is followed (the Visualizer runs)
    @Volatile var problem = ""

    private var vis: Visualizer? = null
    private var bass = 0f // the bass's recent average
    private var peak = 1f // the loudest lately (the level's scale)
    private var lastBeat = 0L
    private var users = 0

    fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Following starts for one more user (the scene, the bar); the music itself when the mode wants it and Android
    // lets it.
    @Synchronized fun start(c: Context, mode: String) {
        users++
        if (vis != null || mode == STEADY) return
        if (!allowed(c)) {
            problem = "allow the microphone for Vox to follow the music (Settings, Tunes)"
            return
        }
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) analyse(fft)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            vis = v
            live = true
            problem = ""
        } catch (e: Throwable) {
            problem = "Android would not share the music (${e.message}); a steady tempo instead"
            live = false
        }
    }

    @Synchronized fun stop() {
        users = max(0, users - 1)
        if (users > 0) return
        runCatching { vis?.enabled = false }
        runCatching { vis?.release() }
        vis = null
        live = false
        pulse = 0f
        level = 0f
    }

    // One FFT frame: twelve bands on a log scale, the volume, a beat when the bass jumps over its average.
    private fun analyse(fft: ByteArray) {
        val n = fft.size / 2
        if (n < 16) return
        val mags = FloatArray(n)
        for (i in 1 until n) mags[i] = hypot(fft[2 * i].toFloat(), fft[2 * i + 1].toFloat())
        var total = 0f
        val next = FloatArray(12)
        for (b in 0 until 12) {
            val lo = max(1, (Math.pow(n.toDouble(), b / 12.0)).toInt())
            val hi = max(lo + 1, min(n, Math.pow(n.toDouble(), (b + 1) / 12.0).toInt()))
            var s = 0f
            for (i in lo until hi) s += mags[i]
            val v = s / (hi - lo)
            total += v
            next[b] = v
        }
        peak = max(peak * 0.995f, total)
        val scale = if (peak > 0f) 1f / peak else 0f
        val old = bars
        bars = FloatArray(12) { b -> max(old[b] * 0.7f, min(1f, (ln(1f + next[b]) / ln(1f + peak / 6f)).coerceAtLeast(0f))) }
        level = level * 0.6f + min(1f, total * scale) * 0.4f
        val low = next[0] + next[1] + next[2]
        val now = SystemClock.elapsedRealtime()
        if (low > bass * 1.35f && low > peak * 0.05f && now - lastBeat > 240) {
            lastBeat = now
            pulse = 1f
        }
        bass = bass * 0.92f + low * 0.08f
    }

    // Each tick (about 60 ms): the pulse fades; the steady tempo beats while something plays (or the music is not
    // followed). Returns the scene's pulse and speed (0 ... 100).
    fun tick(mode: String, bpm: Int): Pair<Int, Int> {
        val now = SystemClock.elapsedRealtime()
        if (mode == STEADY || !live) {
            val period = 60000L / bpm.coerceIn(40, 200)
            if (Media.now?.playing == true && now - lastBeat >= period) {
                lastBeat = now - (now - lastBeat) % period
                pulse = 1f
            }
            level = if (Media.now?.playing == true) 0.45f else 0.15f
            if (!live) bars = FloatArray(12) { b ->
                val t = now / 1000.0
                (0.35 + 0.3 * Math.sin(t * 2.1 + b * 0.7) * pulse + 0.2 * Math.sin(t * 1.3 + b)).toFloat().coerceIn(0.05f, 1f)
            }
        }
        val out = when (mode) {
            VOLUME -> (level * 100).toInt() to (level * 100).toInt()
            else -> (pulse * 100).toInt() to (level * 100).toInt()
        }
        pulse *= 0.8f
        return out
    }
}

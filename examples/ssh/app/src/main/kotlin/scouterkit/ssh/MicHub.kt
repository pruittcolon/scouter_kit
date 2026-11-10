package scouterkit.ssh

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// The phone's microphone, shared: one recording, every listener (Listen mode, captions, voice commands, Car mode)
// gets the same 16 kHz float blocks, so they run together. It records while anyone listens. The source (Settings
// .listenSource) is how Android tunes it: "voice" (voice recognition: no automatic gain), "raw" (unprocessed, where
// the phone has it), "camcorder" (often several microphones), "mic" (the default, with the phone's own processing).
object MicHub {
    const val RATE = 16000
    private const val BLOCK = 1600 // 100 ms

    // A listener's end: the blocks since it joined (it loses its own oldest ones if it falls behind 10 s).
    class Sub internal constructor(val name: String) {
        internal val q = ArrayBlockingQueue<FloatArray>(100)
        @Volatile internal var closed = false
        private var cur: FloatArray? = null
        private var pos = 0

        // Samples into out: n, 0 none within 200 ms, -1 closed.
        fun read(out: FloatArray): Int {
            if (closed) return -1
            var c = cur
            if (c == null || pos >= c.size) {
                c = q.poll(200, TimeUnit.MILLISECONDS) ?: return if (closed) -1 else 0
                cur = c
                pos = 0
            }
            val n = minOf(out.size, c.size - pos)
            System.arraycopy(c, pos, out, 0, n)
            pos += n
            return n
        }

        internal fun give(block: FloatArray) {
            if (!q.offer(block)) {
                q.poll()
                q.offer(block)
            }
        }
    }

    @Volatile var silenced = false // another app (a call, a recorder) has the microphone now: blocks are silence
        private set
    @Volatile var problem: String? = null // why it does not hear, null: it does
        private set
    @Volatile var running = false
        private set

    private val subs = CopyOnWriteArrayList<Sub>()
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var restart = false
    @Volatile private var gen = 0 // the reader's turn: an old reader ending late leaves a newer one alone

    private fun sourceOf(name: String) = when (name) {
        "raw" -> MediaRecorder.AudioSource.UNPROCESSED
        "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
        "mic" -> MediaRecorder.AudioSource.MIC
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    // Whether the phone offers unprocessed audio.
    fun hasRaw(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    // Joins the listeners: the subscription, or why the microphone can't be had. strict: a microphone Android
    // silences (Vox in the back without its microphone service) is an error too.
    @Synchronized
    fun open(context: Context, name: String, strict: Boolean = true): Pair<Sub?, String?> {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return null to "Vox may not use the phone's microphone yet: allow it in Vox, on the Voice tab"
        if (!running) start(context.applicationContext)?.let { return null to it }
        if (strict && silenced) return null to "Android keeps the phone's microphone from Vox in the back: open Vox once, then try again"
        val s = Sub(name)
        subs += s
        Log.i(ScouterService.TAG, "mic: $name joins (${subs.size} listening)")
        return s to null
    }

    @Synchronized
    fun close(s: Sub) {
        s.closed = true
        subs -= s
        Log.i(ScouterService.TAG, "mic: ${s.name} leaves (${subs.size} listening)")
        if (subs.isEmpty()) stop()
    }

    // The source changed (Settings): the recording starts again with it.
    fun reopen() {
        restart = true
    }

    @SuppressLint("MissingPermission")
    private fun record(context: Context): Pair<AudioRecord?, String?> {
        val fmt = AudioFormat.ENCODING_PCM_FLOAT
        val ch = AudioFormat.CHANNEL_IN_MONO
        val want = Settings(context).listenSource
        val src = if (want == "raw" && !hasRaw(context)) MediaRecorder.AudioSource.VOICE_RECOGNITION else sourceOf(want)
        val r = runCatching {
            AudioRecord(src, RATE, ch, fmt, maxOf(AudioRecord.getMinBufferSize(RATE, ch, fmt), RATE * 4))
        }.getOrNull() ?: return null to "the phone's microphone did not start"
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return null to "the phone's microphone did not start"
        }
        r.startRecording()
        Thread.sleep(150)
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { r.stop() }
            r.release()
            return null to "the phone's microphone is busy"
        }
        return r to null
    }

    private fun silencedNow(r: AudioRecord) = Build.VERSION.SDK_INT >= 29 && r.activeRecordingConfiguration?.isClientSilenced == true

    // The recording and its reader. Null: it runs; else why not.
    private fun start(context: Context): String? {
        val (r, why) = record(context)
        if (r == null) {
            problem = why
            return why
        }
        rec = r
        silenced = silencedNow(r)
        problem = null
        running = true
        restart = false
        val mine = ++gen
        thread = Thread({ read(context, r, mine) }, "mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(ScouterService.TAG, "mic: the phone's microphone on (${Settings(context).listenSource})" + if (silenced) ", silenced" else "")
        return null
    }

    private fun stop() {
        running = false
        gen++
        thread?.interrupt()
        thread = null
    }

    private fun read(context: Context, first: AudioRecord, mine: Int) {
        var r = first
        var lastCheck = 0L
        var fails = 0
        try {
            while (running && gen == mine) {
                if (restart) {
                    restart = false
                    runCatching { r.stop() }
                    r.release()
                    val (n, why) = record(context)
                    if (n == null) {
                        problem = why
                        Thread.sleep(2000)
                        restart = true
                        continue
                    }
                    r = n
                    rec = n
                    problem = null
                    Log.i(ScouterService.TAG, "mic: started again (${Settings(context).listenSource})")
                }
                val block = FloatArray(BLOCK)
                val n = r.read(block, 0, BLOCK, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    // The recording broke (an audio route change, the service lost the microphone): try again.
                    if (++fails > 30) {
                        problem = "the phone's microphone stopped"
                        break
                    }
                    Thread.sleep(1000)
                    restart = true
                    continue
                }
                fails = 0
                if (n == 0) continue
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCheck > 1000) {
                    lastCheck = now
                    val s = silencedNow(r)
                    if (s != silenced) Log.i(ScouterService.TAG, "mic: " + if (s) "silenced (another app has it)" else "back")
                    silenced = s
                }
                val out = if (n == BLOCK) block else block.copyOf(n)
                for (s in subs) s.give(out)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            problem = e.message ?: "the phone's microphone stopped"
            Log.w(ScouterService.TAG, "mic: ${e.message}")
        } finally {
            runCatching { r.stop() }
            r.release()
            synchronized(this) {
                if (rec === r) rec = null
                if (gen == mine) { // it ended by itself: the listeners hear it stopped
                    running = false
                    for (s in subs) s.closed = true
                    subs.clear()
                }
            }
            Log.i(ScouterService.TAG, "mic: the phone's microphone off")
        }
    }
}

// A Mic for VoiceListener on the shared phone microphone (captions, commands, Car mode).
class HubMic(private val context: Context, private val name: String) : Mic {
    private var sub: MicHub.Sub? = null

    override fun start(): String? {
        val (s, why) = MicHub.open(context, name)
        sub = s
        return why
    }

    override fun read(out: FloatArray): Int = sub?.read(out) ?: -1

    override fun stop() {
        sub?.let { MicHub.close(it) }
        sub = null
    }
}

package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// Car mode: talking to Claude in the car. The car's Next button (the steering wheel's skip) starts listening on the
// glasses' microphone (Parakeet, as all of Vox's voice) and Next again sends what was said to Claude: by default
// typed into the Claude session running in the server's tmux (the conversation the glasses show, so it goes on
// there), else a voice chat of its own (`claude -p`). The answer is read aloud through the car, in Kokoro's voice
// once it is downloaded (else Android's), with the music paused meanwhile. Chimes say what happens: listening,
// sent, thinking, a problem. It comes on by itself when a car's Bluetooth audio connects (Settings.carMode "auto"),
// or always ("on").

// The chimes, made here: soft bell-like sines (a quick attack, a long tail), through the car like the speech.
object CarSounds {
    enum class Sound { ON, LISTEN, SEND, THINK, ERROR, CANCEL, READY }

    private const val RATE = 44100
    private val made = HashMap<Sound, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    // notes: Hz, start (s), length (s), gain.
    private fun synth(vararg notes: FloatArray): ShortArray {
        val lead = (RATE * 0.12f).toInt() // a Bluetooth link wakes the car's speakers: the first moment is lost
        val end = notes.maxOf { it[1] + it[2] }
        val n = lead + (RATE * (end + 0.05f)).toInt()
        val buf = FloatArray(n)
        for (nt in notes) {
            val f = nt[0]
            val start = lead + (nt[1] * RATE).toInt()
            val len = (nt[2] * RATE).toInt()
            for (i in 0 until len) {
                val at = start + i
                if (at >= n) break
                val t = i.toFloat() / RATE
                val attack = minOf(1f, t / 0.006f)
                val decay = exp(-t * 5f / nt[2])
                val w = 2.0 * PI * f * t
                val s = sin(w) + 0.22 * sin(2 * w) * exp(-t * 9.0) + 0.06 * sin(3 * w) * exp(-t * 14.0)
                buf[at] += (s * attack * decay * nt[3]).toFloat()
            }
        }
        val fade = RATE / 100
        for (i in 0 until fade) buf[n - 1 - i] *= i.toFloat() / fade
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val scale = 0.55f / peak * 32767f
        return ShortArray(n) { (buf[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun note(hz: Float, at: Float, len: Float, gain: Float = 1f) = floatArrayOf(hz, at, len, gain)

    private fun samples(s: Sound): ShortArray = made.getOrPut(s) {
        when (s) {
            // C E G, rising: car mode is on.
            Sound.ON -> synth(note(523.3f, 0f, 0.5f), note(659.3f, 0.1f, 0.5f), note(784f, 0.2f, 0.7f))
            // G then C above: listening.
            Sound.LISTEN -> synth(note(784f, 0f, 0.28f, 0.8f), note(1046.5f, 0.085f, 0.4f))
            // C then G below: sent.
            Sound.SEND -> synth(note(1046.5f, 0f, 0.25f, 0.8f), note(784f, 0.08f, 0.38f, 0.9f))
            // A soft low tick while Claude thinks.
            Sound.THINK -> synth(note(587.3f, 0f, 0.22f, 0.25f), note(880f, 0f, 0.12f, 0.06f))
            // A low falling pair: a problem.
            Sound.ERROR -> synth(note(440f, 0f, 0.3f), note(349.2f, 0.16f, 0.5f))
            // One soft falling step: nothing was sent.
            Sound.CANCEL -> synth(note(659.3f, 0f, 0.22f, 0.7f), note(523.3f, 0.09f, 0.34f, 0.7f))
            // A single clear tone: done, ready again.
            Sound.READY -> synth(note(1318.5f, 0f, 0.3f, 0.5f))
        }
    }

    fun play(s: Sound) {
        val data = samples(s)
        val gain = if (s == Sound.THINK) 0.5f else 1f
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.setVolume(gain)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, data.size * 1000L / RATE + 300)
        }.onFailure { Log.w(ScouterService.TAG, "car: sound: ${it.message}") }
    }
}

// Kokoro (82M, v1.0, int8, as converted for sherpa-onnx; Apache-2.0): the car's voice, downloaded once (about
// 150 MB) like the speech model, each file checked by its SHA-256. English only: the US lexicon and the espeak-ng
// data English needs.
object KokoroModel {
    private const val HF =
        "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2"
    private fun part(name: String, size: Long, sha: String) = VoiceModel.Part(name, "$HF/$name", size, sha)
    val parts = listOf(
        part("model.int8.onnx", 114202993, "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"),
        part("voices.bin", 28200960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
        part("tokens.txt", 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        part("lexicon-us-en.txt", 5956885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
        part("LICENSE", 11358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        part("espeak-ng-data/phondata", 550424, "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3"),
        part("espeak-ng-data/phonindex", 39074, "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4"),
        part("espeak-ng-data/phontab", 55796, "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6"),
        part("espeak-ng-data/intonations", 2040, "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc"),
        part("espeak-ng-data/en_dict", 166944, "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35"),
        part("espeak-ng-data/lang/gmw/en", 140, "4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83"),
        part("espeak-ng-data/lang/gmw/en-US", 257, "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704"),
    )
    val bytes = parts.sumOf { it.size }

    // The voices offered (Kokoro's speaker ids in this model).
    val VOICES = listOf(3 to "Heart", 2 to "Bella", 6 to "Nicole", 9 to "Sarah", 16 to "Michael", 14 to "Fenrir",
        18 to "Puck", 21 to "Emma (UK)", 26 to "George (UK)")

    @Volatile var progress = -1L
    @Volatile var problem: String? = null

    fun dir(context: Context) = File(context.filesDir, "voice/kokoro-int8-multi-lang-v1_0")
    fun ready(context: Context) = parts.all { File(dir(context), it.name).length() == it.size }

    fun download(context: Context): String? {
        problem = null
        try {
            VoiceModel.fetch(dir(context), parts) { progress = it }
            return null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            return problem
        } finally {
            progress = -1
        }
    }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
    }

    // Loaded once (a second or two), kept while Car mode is on.
    @Volatile private var tts: OfflineTts? = null

    @Synchronized
    fun load(context: Context): OfflineTts? {
        tts?.let { return it }
        if (!VoiceModel.available || !ready(context)) return null
        val d = dir(context)
        return runCatching {
            OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                model = File(d, "model.int8.onnx").path, voices = File(d, "voices.bin").path,
                tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path,
                lexicon = File(d, "lexicon-us-en.txt").path), numThreads = 4), maxNumSentences = 1))
        }.onFailure { Log.w(ScouterService.TAG, "car: Kokoro did not load: ${it.message}") }.getOrNull().also { tts = it }
    }

    @Synchronized
    fun unload() {
        runCatching { tts?.release() }
        tts = null
    }
}

// The answer aloud, through the car: Kokoro's voice (Settings.carSpeaker) once downloaded, made sentence by
// sentence and played as it comes; else Android's text-to-speech (its best installed voice for the language).
class CarSpeech(private val context: Context, private val onDone: (String) -> Unit) {
    private val settings = Settings(context)
    @Volatile private var ready = false
    private var pending: (() -> Unit)? = null
    private var last = ""
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        setUp()
        ready = true
        pending?.invoke()
        pending = null
    }

    private fun setUp() {
        tts.setAudioAttributes(ATTRS)
        val loc = Locale.getDefault()
        runCatching {
            tts.voices?.filter {
                it.locale.language == loc.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }?.maxByOrNull { it.quality * 10 + (if (it.locale.country == loc.country) 5 else 0) - it.latency }?.let { tts.voice = it }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
            override fun onError(id: String?, code: Int) = finished(id)
        })
    }

    private fun finished(id: String?) {
        if (id != null && id == last) onDone(id.substringBefore('#'))
    }

    // Kokoro loaded ahead (Car mode coming on), so the first answer starts at once.
    fun preload() {
        if (useKokoro()) Thread { KokoroModel.load(context) }.start()
    }

    private fun useKokoro() = settings.carVoice != "android" && KokoroModel.ready(context)

    fun speak(text: String, id: String) {
        stop()
        val rate = settings.carRate.coerceIn(60, 160) / 100f
        val said = clean(text)
        // Kokoro: one sentence (or a clause of a long one) at a time, so each is made while the one before plays
        // (a long piece took seconds to make and left a gap after the first sentence).
        if (useKokoro()) return kokoro(phrases(said, 140), rate, id)
        android(split(said, 300), rate, id)
    }

    private fun android(parts: List<String>, rate: Float, id: String) {
        val go = {
            tts.setSpeechRate(rate)
            last = "$id#${parts.size - 1}"
            for ((i, p) in parts.withIndex()) tts.speak(p, TextToSpeech.QUEUE_ADD, null, "$id#$i")
        }
        if (ready) go() else pending = go
    }

    // Made sentence by sentence on one thread (OfflineTts.generate: no callback, since sherpa-onnx's JNI looks for a
    // lambda method that Kotlin 2's lambdas don't have, and nothing came out) and played on this one as each is
    // ready, so the next is made while one plays. Nothing made at all: the phone's own voice says it instead.
    private fun kokoro(parts: List<String>, rate: Float, id: String) {
        val my = ++gen
        val sid = settings.carSpeaker
        Thread({
            val k = KokoroModel.load(context)
            if (k == null) {
                if (gen == my) Handler(Looper.getMainLooper()).post { if (gen == my) android(parts, rate, id) }
                return@Thread
            }
            val hz = k.sampleRate()
            val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
            val end = FloatArray(0)
            var made = 0
            Thread({
                for (p in parts) {
                    if (gen != my) break
                    val t0 = SystemClock.uptimeMillis()
                    val a = runCatching { synchronized(KokoroModel) { k.generate(p, sid, rate) } }
                        .onFailure { Log.w(ScouterService.TAG, "car: Kokoro: ${it.message}") }.getOrNull()
                    if (a != null && a.samples.isNotEmpty()) {
                        Log.i(ScouterService.TAG, "car: Kokoro (voice $sid) made ${a.samples.size * 1000L / hz} ms of speech " +
                            "in ${SystemClock.uptimeMillis() - t0} ms")
                        made++
                        queue.put(a.samples)
                    }
                }
                queue.put(end)
            }, "car-voice-make").start()
            val t = runCatching {
                AudioTrack.Builder().setAudioAttributes(ATTRS)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(hz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(hz, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT), hz * 4 / 2))
                    .build()
            }.getOrNull() ?: return@Thread
            track = t
            // A moment of silence first: the car's speakers wake on the Bluetooth link.
            val lead = FloatArray(hz / 8)
            t.write(lead, 0, lead.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            var frames = lead.size.toLong()
            while (gen == my) {
                val s = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (s === end) break
                t.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
                frames += s.size
            }
            if (gen == my) {
                runCatching { t.stop() } // plays out what was written
                val until = SystemClock.uptimeMillis() + frames * 1000 / hz + 1500
                while (gen == my && SystemClock.uptimeMillis() < until && runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(frames) < frames)
                    Thread.sleep(40)
            }
            runCatching { t.release() }
            if (track === t) track = null
            if (gen != my) return@Thread
            if (made == 0) Handler(Looper.getMainLooper()).post { if (gen == my) android(parts, rate, id) }
            else onDone(id)
        }, "car-voice").start()
    }

    fun stop() {
        gen++
        pending = null
        last = ""
        track?.let { runCatching { it.pause(); it.flush() } }
        runCatching { tts.stop() }
    }

    fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

        // What reads badly aloud: markdown, code, links.
        fun clean(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (some code) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_#>|]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Each sentence on its own, a long one cut at its commas or semicolons into pieces of up to about max.
        fun phrases(text: String, max: Int): List<String> {
            if (text.isEmpty()) return listOf("Done.")
            val out = mutableListOf<String>()
            for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (s.isBlank()) continue
                if (s.length <= max) {
                    out += s
                    continue
                }
                val sb = StringBuilder()
                for (c in s.split(Regex("(?<=[,;:])\\s+"))) {
                    if (sb.isNotEmpty() && sb.length + c.length + 1 > max) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                    if (c.length > max) c.chunked(max).forEach { out += it } else sb.append(if (sb.isEmpty()) c else " $c")
                }
                if (sb.isNotEmpty()) out += sb.toString()
            }
            return out.ifEmpty { listOf("Done.") }
        }

        // Sentences, joined up to max characters (Android's voice).
        fun split(text: String, max: Int): List<String> {
            if (text.isEmpty()) return listOf("Done.")
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (sb.isNotEmpty() && (sb.length + s.length + 1 > max || out.isEmpty())) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                if (s.length > max) s.chunked(max).forEach { out += it } else sb.append(if (sb.isEmpty()) s else " $s")
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}

// The car's buttons (its media keys over Bluetooth) to Vox: a media session that also tells the car what to show.
// Android hands the keys to the app that last started playing, so Vox plays a moment of silence to have them, and
// does so again half a second after any other player starts (Spotify resuming after an answer, a new track) and
// whenever Android says the keys went elsewhere (where it lets Vox know). The music app's play / pause and previous
// are passed on to it (Media).
class CarButtons(private val context: Context, private val handler: Handler, private val onKey: (Int, Boolean) -> Unit) {
    private val session = MediaSession(context, "Vox car")
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var active: Set<AudioPlaybackConfiguration> = emptySet()
    private var claimUntil = 0L
    private var lastClaim = 0L
    private val again = Runnable { claim() }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val now = configs?.toSet() ?: emptySet()
            val started = now.any { it !in active }
            active = now
            if (started && SystemClock.uptimeMillis() > claimUntil) soon()
        }
    }

    private var unwatchKeys: (() -> Unit)? = null

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val e: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)) ?: return false
                if (e.repeatCount > 0) return true
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> onKey(e.keyCode, true)
                    KeyEvent.ACTION_UP -> onKey(e.keyCode, false)
                }
                return true
            }

            // Asked as actions instead of keys (some cars, Android's own controls).
            override fun onSkipToNext() = tap(KeyEvent.KEYCODE_MEDIA_NEXT)
            override fun onSkipToPrevious() = tap(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            override fun onPlay() = tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onPause() = tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onStop() = tap(KeyEvent.KEYCODE_MEDIA_STOP)
        }, handler)
        show("Vox", "Press next to talk to Claude", false)
        session.isActive = true
        runCatching { audio.registerAudioPlaybackCallback(playback, handler) }
        if (Build.VERSION.SDK_INT >= 33) watchKeys()
    }

    // Android 13+: told when the media keys go to another app (if Android lets Vox know: it may not).
    @android.annotation.TargetApi(33)
    private fun watchKeys() {
        val moved = MediaSessionManager.OnMediaKeyEventSessionChangedListener { pkg, _ ->
            if (pkg != context.packageName) {
                Log.i(ScouterService.TAG, "car: the media keys went to ${pkg.ifEmpty { "nobody" }}: taking them back")
                soon()
            }
        }
        runCatching {
            sessions.addOnMediaKeyEventSessionChangedListener(java.util.concurrent.Executor { handler.post(it) }, moved)
            unwatchKeys = { runCatching { sessions.removeOnMediaKeyEventSessionChangedListener(moved) } }
            Log.i(ScouterService.TAG, "car: the media keys are at ${sessions.mediaKeyEventSessionPackageName.ifEmpty { "nobody" }}")
        }.onFailure { Log.i(ScouterService.TAG, "car: cannot watch where the media keys go (${it.javaClass.simpleName})") }
    }

    private fun tap(code: Int) {
        onKey(code, true)
        onKey(code, false)
    }

    private fun soon() {
        handler.removeCallbacks(again)
        handler.postDelayed(again, 500)
    }

    // The keys to Vox: a moment of silence played (the last app to start playing gets them).
    fun claim() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaim < 800) return soon()
        lastClaim = now
        claimUntil = now + 1000
        Thread {
            runCatching {
                val n = 44100 / 4
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                track.write(ShortArray(n), 0, n)
                track.play()
                Thread.sleep(400)
                track.release()
            }.onFailure { Log.w(ScouterService.TAG, "car: claim: ${it.message}") }
        }.start()
    }

    // What the car's screen shows (its now-playing): a title, a line under it, playing or not.
    fun show(title: String, subtitle: String, playing: Boolean) {
        runCatching {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Vox")
                .build())
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f)
                .build())
        }
    }

    fun release() {
        handler.removeCallbacks(again)
        runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        unwatchKeys?.invoke()
        runCatching {
            session.isActive = false
            session.release()
        }
    }
}

// Claude on a saved server, two ways. The terminal (Settings.carTarget "terminal"): the words typed into the Claude
// session running in the server's tmux (the conversation the glasses show), told they came by voice from the car,
// and its reply read from that session's transcript once its turn ends (a small Python script there does both).
// A chat ("chat"): `claude -p` with JSON out, a conversation of its own resumed by its session id.
object CarClaude {
    class Answer(val text: String, val session: String?, val error: String?)

    const val PROMPT = "The user is talking with you by voice while driving; your reply is read aloud by text-to-speech " +
        "through the car's speakers. Answer in short, natural spoken sentences, usually one to four. No markdown, lists, " +
        "headings, code, URLs or emoji. If a full answer would be long, give the gist and offer to go on."

    private const val PATH = "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; export PATH; "

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun chatCommand(text: String, session: String?): String {
        val resume = if (session.isNullOrEmpty()) "" else "--resume ${q(session)} "
        return PATH + "cd ~ 2>/dev/null; command -v claude >/dev/null || { echo 'VOX: claude is not installed there'; exit 0; }; " +
            "printf %s ${q(text)} | claude -p --output-format json $resume--append-system-prompt ${q(PROMPT)} " +
            "--allowedTools 'WebSearch,WebFetch' 2>&1"
    }

    // Typed into tmux session `session`'s Claude pane; waits up to `limit` s for the turn's end. Prints one line of
    // JSON: {"text", "session"} or {"error"}. With "dry": only says which pane it would type into.
    const val TERMINAL_PY = """
import sys, os, json, glob, time, subprocess, re
text = sys.stdin.read().strip()
sess, limit = sys.argv[1], float(sys.argv[2])
dry = len(sys.argv) > 3 and sys.argv[3] == "dry"
TAG = "(By voice, from the car: answer briefly, in plain spoken sentences.) "
NEEDLE = "from the car: answer briefly"
def out(**k):
    print(json.dumps(k))
    sys.exit(0)
def tmux(*a):
    return subprocess.run(["tmux"] + list(a), capture_output=True, text=True)
if sess.startswith("%"):
    r = tmux("display", "-p", "-t", sess, "#{pane_id} 1 1 #{pane_pid}")
else:
    r = tmux("list-panes", "-s", "-t", sess, "-F", "#{pane_id} #{window_active} #{pane_active} #{pane_pid}")
if r.returncode != 0 or not r.stdout.strip():
    out(error="There is no Claude session called " + sess + " running there.")
panes = sorted([l.split() for l in r.stdout.split("\n") if len(l.split()) == 4], key=lambda x: (x[1] != "1", x[2] != "1"))
procs, kids = {}, {}
try:
    for l in subprocess.run(["ps", "-eo", "pid=,ppid=,args="], capture_output=True, text=True).stdout.split("\n"):
        f = l.split(None, 2)
        if len(f) >= 2:
            procs[f[0]] = f[2] if len(f) > 2 else ""
            kids.setdefault(f[1], []).append(f[0])
except Exception:
    pass
def runs_claude(pid):
    todo, seen = [pid], set()
    while todo:
        p = todo.pop()
        if p in seen:
            continue
        seen.add(p)
        a = procs.get(p, "")
        first = a.split(" ")[0] if a else ""
        if first.endswith("claude") or "/claude/versions/" in a or "claude-keepalive" in a:
            return True
        todo += kids.get(p, [])
    return False
pane = next((p[0] for p in panes if runs_claude(p[3])), panes[0][0] if panes else None)
if pane is None:
    out(error="There is no Claude session called " + sess + " running there.")
screen = tmux("capture-pane", "-p", "-t", pane).stdout
tail = "\n".join(screen.rstrip("\n").split("\n")[-25:])
if re.search(r"Do you want to|1\. Yes", tail):
    out(error="Claude is waiting for your OK on the screen.")
if dry:
    out(text="pane " + pane)
home = os.path.expanduser("~")
def sizes():
    found, now = {}, time.time()
    for p in glob.glob(os.path.join(home, ".claude", "projects", "*", "*.jsonl")):
        try:
            st = os.stat(p)
        except OSError:
            continue
        if now - st.st_mtime < 3 * 86400:
            found[p] = st.st_size
    return found
before = sizes()
tmux("send-keys", "-t", pane, "-l", TAG + text)
time.sleep(0.4)
tmux("send-keys", "-t", pane, "Enter")
deadline = time.time() + limit
target, off = None, 0
while target is None and time.time() < deadline:
    time.sleep(0.6)
    for p, size in sizes().items():
        b = before.get(p, 0)
        if size > b:
            with open(p, "rb") as f:
                f.seek(b)
                chunk = f.read().decode("utf-8", "replace")
            if NEEDLE in chunk:
                target, off = p, b
                break
if target is None:
    out(error="Claude did not take the message.")
while time.time() < deadline:
    with open(target, "rb") as f:
        f.seek(off)
        data = f.read().decode("utf-8", "replace")
    seen, texts, done = False, [], False
    for l in data.split("\n"):
        if not seen:
            seen = NEEDLE in l
            continue
        try:
            j = json.loads(l)
        except Exception:
            continue
        if j.get("type") == "assistant":
            for c in (j.get("message") or {}).get("content") or []:
                if not isinstance(c, dict):
                    continue
                if c.get("type") == "text" and (c.get("text") or "").strip():
                    texts.append(c["text"])
                elif c.get("type") == "tool_use":
                    texts = []
        elif j.get("type") == "system" and j.get("subtype") == "turn_duration":
            done = True
            break
    if done:
        out(text="\n".join(texts).strip() or "Done.", session=os.path.basename(target)[:-6])
    time.sleep(1.0)
out(error="Claude is still working on it; the answer will be on the glasses.")
"""

    fun terminalCommand(text: String, session: String, limit: Int, dry: Boolean = false): String =
        PATH + "command -v python3 >/dev/null || { echo 'VOX: python3 is not installed there'; exit 0; }; " +
            "command -v tmux >/dev/null || { echo 'VOX: tmux is not installed there'; exit 0; }; " +
            "printf %s ${q(text)} | python3 -c ${q(TERMINAL_PY)} ${q(session)} $limit${if (dry) " dry" else ""} 2>&1"

    // A new tab (tmux window) in `session` running a fresh Claude on Sonnet (quicker for talk), opened behind the
    // tab in front (the user's own conversation stays where it was); waits (12 s at most) for Claude's prompt and
    // answers its pane, which the questions then go to. Sonnet through ANTHROPIC_MODEL, which lasts only for that
    // process: `claude --model` can become the saved default, and the user's own Claude then changed model too.
    // As a backstop the saved default (~/.claude/settings.json "model") is put back if it changed meanwhile.
    fun newTab(vault: Vault, server: Settings.Server, session: String): Answer = run(vault, server, PATH + newTabScript(session))

    private const val GET_MODEL = "import json,sys\ntry: print(json.load(open(sys.argv[1])).get('model', ''))\nexcept Exception: print('-')"
    private const val PUT_MODEL = "import json,sys\np, m = sys.argv[1], sys.argv[2]\ntry: d = json.load(open(p))\n" +
        "except Exception: sys.exit(0)\nif d.get('model', '') != m:\n    d.pop('model', None) if m == '' else d.update(model=m)\n" +
        "    open(p, 'w').write(json.dumps(d, indent=2) + '\\n')\n    print('VOX-RESTORED')"

    fun newTabScript(session: String): String =
        "S=${q(session)}; F=\"\$HOME/.claude/settings.json\"; " +
            "command -v tmux >/dev/null || { echo 'VOX: tmux is not installed there'; exit 0; }; " +
            "tmux has-session -t \"\$S\" 2>/dev/null || { echo \"VOX: There is no Claude session called \$S running there.\"; exit 0; }; " +
            "M=-; command -v python3 >/dev/null && M=\$(python3 -c ${q(GET_MODEL)} \"\$F\" 2>/dev/null); " +
            "P=\$(tmux new-window -d -P -F '#{pane_id}' -c \"\$HOME\" -e \"PATH=\$PATH\" -e ANTHROPIC_MODEL=sonnet -t \"\$S:\" -n voice 'exec claude') || " +
            "{ echo 'VOX: tmux could not open a tab'; exit 0; }; " +
            "i=0; while [ \$i -lt 24 ]; do tmux capture-pane -p -t \"\$P\" 2>/dev/null | grep -q 'for shortcuts' && break; " +
            "sleep 0.5; i=\$((i+1)); done; " +
            "[ \"\$M\" != - ] && python3 -c ${q(PUT_MODEL)} \"\$F\" \"\$M\" 2>/dev/null; " +
            "printf '{\"text\": \"ok\", \"session\": \"%s\"}\\n' \"\$P\""

    fun askTerminal(vault: Vault, server: Settings.Server, text: String, session: String): Answer =
        run(vault, server, terminalCommand(text, session, 240))

    fun askChat(vault: Vault, server: Settings.Server, text: String, session: String?): Answer {
        val first = run(vault, server, chatCommand(text, session))
        // A conversation the server no longer has: a new one.
        if (first.error != null && !session.isNullOrEmpty() && first.error.contains("session", true))
            return run(vault, server, chatCommand(text, null))
        return first
    }

    private fun run(vault: Vault, s: Settings.Server, cmd: String): Answer {
        val out = try {
            Bootstrap(vault).run(s.host, s.port, s.user, cmd)
        } catch (e: Bootstrap.UnknownHost) {
            return Answer("", null, "${s.name} is not trusted yet: open its terminal once in Vox")
        } catch (e: Exception) {
            return Answer("", null, "I can't reach ${s.name}. ${e.message ?: ""}".trim())
        }
        out.lines().firstOrNull { it.startsWith("VOX: ") }?.let { return Answer("", null, it.removePrefix("VOX: ")) }
        val json = out.lines().lastOrNull { it.trimStart().startsWith("{") }?.let { runCatching { org.json.JSONObject(it.trim()) }.getOrNull() }
            ?: out.lastIndexOf("\n{").takeIf { it >= 0 }?.let { runCatching { org.json.JSONObject(out.substring(it + 1)) }.getOrNull() }
            ?: return Answer("", null, out.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Claude gave no answer")
        if (json.has("error") && !json.has("result")) return Answer("", null, json.optString("error"))
        val result = json.optString("result").ifEmpty { json.optString("text") }
        val id = json.optString("session_id").ifEmpty { json.optString("session") }.ifEmpty { null }
        if (json.optBoolean("is_error") || result.isEmpty())
            return Answer("", id, result.ifEmpty { json.optString("subtype").ifEmpty { "Claude had a problem" } })
        return Answer(result, id, null)
    }
}

// What Car mode needs from the service: the glasses' microphone through Vox's voice, the band on the glasses, the
// server Claude runs on and its tmux session.
interface CarHost {
    val handler: Handler
    // A listener on the glasses' microphone until carStopListening: onLive once it hears, onEnd(why) ("" stopped)
    // once.
    fun carListen(onLive: () -> Unit, onPartial: (String) -> Unit, onPhrase: (String) -> Unit, onSpeaking: (Boolean) -> Unit,
                  onEnd: (String) -> Unit)
    fun carStopListening()
    fun carPreload() // the speech model loaded ahead
    fun carBand(text: String, hold: Boolean)
    fun carServer(): Settings.Server?
    fun carTmux(server: Settings.Server): String // the tmux session Claude runs in there
    fun carVault(): Vault
    fun carChanged()
}

// Next: listen. Next again: send. Nothing else starts or ends a question, so it does what the button says.
class CarMode(private val context: Context, private val host: CarHost) {
    enum class State { IDLE, STARTING, LISTENING, SENDING, THINKING, SPEAKING }

    @Volatile var state = State.IDLE
        private set
    private val h get() = host.handler
    private val settings = Settings(context)
    private val speech = CarSpeech(context) { id -> h.post { spoken(id) } }
    private val buttons = CarButtons(context, h) { code, down -> key(code, down) }
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val said = StringBuilder()
    private var partial = ""
    private var micOn = false // the listener runs
    private var request = 0
    private var asked = ""
    private var lastAnswer = ""
    @Volatile private var voicePane: String? = null // the tab "New conversation" opened: questions go there
    private var lastKey = 0L
    private var lastKeyCode = 0

    private val noSpeech = Runnable { if (state == State.LISTENING && said.isBlank() && partial.isEmpty()) cancel("I didn't hear anything.") }
    private val tooLong = Runnable { if (state == State.LISTENING) send() }
    private val slowMic = Runnable { if (state == State.STARTING) host.carBand("$MIC One moment…", true) }
    private val pulse = object : Runnable {
        override fun run() {
            if (state != State.THINKING) return
            CarSounds.play(CarSounds.Sound.THINK)
            h.postDelayed(this, 2600)
        }
    }

    fun start(greet: Boolean) {
        buttons.claim()
        host.carPreload()
        speech.preload()
        show()
        if (greet) {
            CarSounds.play(CarSounds.Sound.ON)
            h.postDelayed({ if (state == State.IDLE) say("Car mode. Press next to talk to Claude, and next again to send.") }, 900)
        } else CarSounds.play(CarSounds.Sound.ON)
    }

    fun release() {
        reset()
        speech.release()
        buttons.release()
        dropFocus()
        Thread { KokoroModel.unload() }.start()
    }

    // Media says another app started playing: the keys back (CarButtons also sees players start by itself).
    fun reclaim() = buttons.claim()

    // What the car shows: Vox's state, or (idle) what plays.
    fun show() {
        val now = Media.now
        when (state) {
            State.IDLE -> if (now != null && now.title.isNotEmpty()) buttons.show(now.title, now.artist, now.playing)
                else buttons.show("Vox", "Press next to talk to Claude", false)
            State.STARTING -> buttons.show("One moment…", "Getting the microphone", true)
            State.LISTENING -> buttons.show("Listening… (next sends)", (said.toString() + partial).trim().ifEmpty { "Talk to Claude" }, true)
            State.SENDING -> buttons.show("Sending…", said.toString().trim(), true)
            State.THINKING -> buttons.show("Claude is thinking…", asked, true)
            State.SPEAKING -> buttons.show("Claude", lastAnswer.take(120), true)
        }
        host.carChanged()
    }

    // ------------------------------------------------ the buttons

    private fun key(code: Int, down: Boolean) {
        if (!down) return
        val now = SystemClock.uptimeMillis()
        // A car that sends a press twice (key and action, or a bounce): one.
        if (code == lastKeyCode && now - lastKey < 600) return
        lastKey = now
        lastKeyCode = code
        Log.i(ScouterService.TAG, "car: ${KeyEvent.keyCodeToString(code)} while ${state.name.lowercase()}")
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> talk()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> when (state) {
                State.STARTING, State.LISTENING -> send()
                State.SENDING, State.THINKING -> cancel("Cancelled.", false)
                State.SPEAKING -> quiet()
                State.IDLE -> Media.control("toggle")
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> when (state) {
                State.SPEAKING -> if (lastAnswer.isNotEmpty()) speak(lastAnswer, "answer")
                State.STARTING, State.LISTENING, State.SENDING, State.THINKING -> cancel("Cancelled.", false)
                State.IDLE -> newConversation()
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> if (state == State.IDLE) Media.control("toggle") else cancel("Stopped.", false)
        }
    }

    // Next: idle, it listens; listening, it sends; thinking or speaking, a new question instead.
    fun talk() {
        when (state) {
            State.IDLE, State.THINKING, State.SPEAKING -> listen()
            State.STARTING, State.LISTENING -> send()
            State.SENDING -> {}
        }
    }

    // ------------------------------------------------ listening

    private fun listen() {
        reset()
        state = State.STARTING
        holdFocus()
        host.carBand("$MIC Getting the microphone…", true)
        show()
        h.postDelayed(slowMic, 700)
        micOn = true
        host.carListen(
            onLive = { h.post { live() } },
            onPartial = { t -> h.post { if (state == State.LISTENING && micOn) heardSoFar(t) } },
            onPhrase = { t -> h.post { phrase(t) } },
            onSpeaking = { on -> h.post { if (on && state == State.LISTENING) h.removeCallbacks(noSpeech) } },
            onEnd = { why -> h.post { micEnded(why) } })
    }

    // The microphone hears now: the chime (not before, so nothing said is lost).
    private fun live() {
        if (state != State.STARTING) return
        h.removeCallbacks(slowMic)
        state = State.LISTENING
        CarSounds.play(CarSounds.Sound.LISTEN)
        host.carBand("$MIC Listening… press next to send", true)
        show()
        h.postDelayed(noSpeech, 30000)
        h.postDelayed(tooLong, 120000)
    }

    private fun heardSoFar(t: String) {
        partial = t
        host.carBand("$MIC ${(said.toString() + t).trim()}…", true)
        show()
    }

    private fun phrase(t: String) {
        if (state != State.LISTENING && state != State.SENDING) return
        said.append(t.trim()).append(' ')
        partial = ""
        host.carBand("$MIC ${said.trim()}…", true)
        show()
    }

    // Next again (or play / pause, or two minutes): the chime at once, then the listener ends with its last words.
    private fun send() {
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        h.removeCallbacks(slowMic)
        if (state == State.STARTING) return cancel("I wasn't listening yet. Press next, wait for the chime, then talk.")
        if (state != State.LISTENING) return
        state = State.SENDING
        CarSounds.play(CarSounds.Sound.SEND)
        host.carBand("$MIC Sending…", true)
        show()
        if (micOn) host.carStopListening() else ask() // micEnded asks
    }

    private fun micEnded(why: String) {
        micOn = false
        when (state) {
            State.SENDING -> ask()
            State.STARTING, State.LISTENING -> if (why.isNotEmpty()) fail(why) else if (said.isNotBlank()) {
                state = State.SENDING
                ask()
            } else cancel("I didn't hear anything.")
            else -> {}
        }
    }

    // ------------------------------------------------ Claude

    private fun ask() {
        val text = said.toString().trim()
        said.setLength(0)
        partial = ""
        if (text.isEmpty()) return cancel("I didn't catch that.")
        val words = VoiceCommands.normalize(text)
        val terminal = settings.carTarget != "chat"
        if (!terminal && words in NEW_CHAT) {
            settings.carSession = ""
            CarSounds.play(CarSounds.Sound.READY)
            return say("New conversation.")
        }
        if (words in NEVER_MIND) return cancel("Cancelled.", false)
        val server = host.carServer() ?: return fail("Add a server for Claude in Vox first.")
        state = State.THINKING
        asked = text
        host.carBand("$MIC “$text”  Claude is thinking…", true)
        show()
        h.postDelayed(pulse, 1800)
        val id = ++request
        val session = settings.carSession.ifEmpty { null }
        val tmux = host.carTmux(server)
        Log.i(ScouterService.TAG, "car: asking Claude on ${server.name} (${if (terminal) "tmux $tmux" else "chat"})")
        Thread {
            val t0 = SystemClock.uptimeMillis()
            val pane = voicePane
            var a = if (!terminal) CarClaude.askChat(host.carVault(), server, text, session)
                else CarClaude.askTerminal(host.carVault(), server, text, pane ?: tmux)
            // The voice tab was closed meanwhile: the conversation in front, as before it.
            if (terminal && pane != null && a.error?.contains("no Claude session", true) == true) {
                voicePane = null
                a = CarClaude.askTerminal(host.carVault(), server, text, tmux)
            }
            Log.i(ScouterService.TAG, "car: answer in ${SystemClock.uptimeMillis() - t0} ms" + (a.error?.let { ": $it" } ?: ""))
            h.post { answered(id, a, terminal) }
        }.start()
    }

    private fun answered(id: Int, a: CarClaude.Answer, terminal: Boolean) {
        if (id != request || state != State.THINKING) return
        h.removeCallbacks(pulse)
        if (!terminal) a.session?.let { settings.carSession = it }
        if (a.error != null) return fail(a.error)
        lastAnswer = a.text
        host.carBand("Claude: ${a.text}", true)
        speak(a.text, "answer")
    }

    // ------------------------------------------------ speaking

    private fun speak(text: String, id: String) {
        state = State.SPEAKING
        holdFocus()
        show()
        speech.speak(text, id)
    }

    // A line of Vox's own (not Claude's).
    private fun say(text: String) = speak(text, "vox")

    private fun spoken(id: String) {
        if (state != State.SPEAKING) return
        state = State.IDLE
        dropFocus()
        if (id == "answer") host.carBand("Claude: $lastAnswer", false)
        show()
    }

    private fun quiet() {
        speech.stop()
        state = State.IDLE
        dropFocus()
        CarSounds.play(CarSounds.Sound.CANCEL)
        show()
    }

    // ------------------------------------------------ the end of a go

    // aloud: the reason is said too (not for a cancel the user asked for: the chime says it).
    private fun cancel(why: String, aloud: Boolean = true) {
        reset()
        CarSounds.play(CarSounds.Sound.CANCEL)
        host.carBand("$MIC $why", false)
        if (aloud) h.postDelayed({ if (state == State.IDLE) say(why) }, 500) else dropFocus()
        show()
    }

    private fun fail(why: String) {
        reset()
        Log.w(ScouterService.TAG, "car: $why")
        CarSounds.play(CarSounds.Sound.ERROR)
        host.carBand("$MIC $why", false)
        h.postDelayed({ if (state == State.IDLE) say(why) }, 700)
        show()
    }

    // Whatever runs stops (a request's answer is dropped when it comes).
    private fun reset() {
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        h.removeCallbacks(slowMic)
        h.removeCallbacks(pulse)
        if (micOn) host.carStopListening()
        micOn = false
        said.setLength(0)
        partial = ""
        request++
        speech.stop()
        state = State.IDLE
    }

    // The music pauses while Vox listens, thinks and speaks, and comes back after.
    private fun holdFocus() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(CarSpeech.ATTRS)
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = f
    }

    private fun dropFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    // The voice test (Vox's Voice tab): the chimes and a line in the chosen voice.
    fun test() {
        if (state != State.IDLE) return
        CarSounds.play(CarSounds.Sound.LISTEN)
        h.postDelayed({ CarSounds.play(CarSounds.Sound.SEND) }, 700)
        h.postDelayed({ if (state == State.IDLE) say("Hi, this is how Claude sounds in the car.") }, 1500)
    }

    // Previous (idle) or the Voice tab: a new conversation. The terminal: a new tab running a fresh Claude, in front
    // (the glasses show it), where the next question goes; a chat: its session forgotten.
    fun newConversation() {
        if (state != State.IDLE) return
        settings.carSession = ""
        if (settings.carTarget == "chat") {
            CarSounds.play(CarSounds.Sound.READY)
            return say("New conversation.")
        }
        val server = host.carServer() ?: return fail("Add a server for Claude in Vox first.")
        state = State.THINKING
        asked = "A new conversation"
        holdFocus()
        host.carBand("$MIC Opening a new conversation…", true)
        show()
        h.postDelayed(pulse, 1200)
        val id = ++request
        val tmux = host.carTmux(server)
        Log.i(ScouterService.TAG, "car: a new Claude tab in tmux $tmux on ${server.name}")
        Thread {
            val a = CarClaude.newTab(host.carVault(), server, tmux)
            h.post {
                if (id != request || state != State.THINKING) return@post
                h.removeCallbacks(pulse)
                if (a.error != null) return@post fail(a.error)
                voicePane = a.session?.takeIf { it.startsWith("%") }
                CarSounds.play(CarSounds.Sound.READY)
                host.carBand("$MIC New conversation: press next to talk", false)
                say("New conversation. Press next to talk.")
            }
        }.start()
    }

    companion object {
        const val MIC = ScouterService.MIC
        private val NEW_CHAT = setOf("new conversation", "new chat", "start over", "start a new conversation", "reset",
            "new topic", "forget that")
        private val NEVER_MIND = setOf("never mind", "nevermind", "cancel", "stop", "nothing")

        // A car's audio (or its hands-free): Bluetooth class car audio or hands-free.
        fun isCar(d: android.bluetooth.BluetoothDevice): Boolean = runCatching {
            val c = d.bluetoothClass?.deviceClass ?: return false
            c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }.getOrDefault(false)
    }
}

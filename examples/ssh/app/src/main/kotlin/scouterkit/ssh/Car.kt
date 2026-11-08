package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// Car mode: talking to Claude in the car. The car's Next button (the steering wheel's skip) starts listening on the
// glasses' microphone (Parakeet, as all of Vox's voice); a pause of about a second and a half, or Next again, sends
// what was said to Claude (`claude -p` on a saved server, one conversation kept going), and the answer is read
// aloud through the car while the music ducks. Chimes say what happens: listening, sent, thinking, a problem.
// It comes on by itself when a car's Bluetooth audio connects (Settings.carMode "auto"), or always ("on").

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

// The answer aloud: Android's text-to-speech (its best voice installed for the phone's language), through the car.
class CarSpeech(context: Context, private val onDone: (String) -> Unit) {
    @Volatile private var ready = false
    private var pending: (() -> Unit)? = null
    private var last = ""
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        setUp()
        ready = true
        pending?.invoke()
        pending = null
    }

    private fun setUp() {
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        if (id != null && id == last) onDone(id)
    }

    // Said in sentences (each within the engine's limit); onDone(id) after the last.
    fun speak(text: String, rate: Float, id: String) {
        val go = {
            tts.setSpeechRate(rate)
            val parts = split(clean(text), maxOf(200, TextToSpeech.getMaxSpeechInputLength() - 100))
            last = "$id#${parts.size - 1}"
            tts.stop()
            for ((i, p) in parts.withIndex()) tts.speak(p, TextToSpeech.QUEUE_ADD, null, "$id#$i")
        }
        if (ready) go() else pending = go
    }

    fun stop() {
        pending = null
        last = ""
        runCatching { tts.stop() }
    }

    fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    companion object {
        // What reads badly aloud: markdown, code marks, links.
        fun clean(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (some code) ")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_`#>|]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun split(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text.ifEmpty { "Done." })
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > max) {
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
// Android hands the keys to the app that last started playing, so Vox plays a moment of silence to have them (and
// again whenever another app starts playing); the music app's play / pause and previous are passed on to it.
class CarButtons(context: Context, handler: Handler, private val onKey: (Int, Boolean) -> Unit) {
    private val session = MediaSession(context, "Vox car")

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
    }

    private fun tap(code: Int) {
        onKey(code, true)
        onKey(code, false)
    }

    // The keys to Vox: a moment of silence played (the last app to start playing gets them).
    fun claim() {
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
        runCatching {
            session.isActive = false
            session.release()
        }
    }
}

// Claude on a saved server: `claude -p` with JSON out, the car's conversation resumed (its session id kept), told
// that its answer is heard while driving. The prompt goes in on stdin (nothing to escape for its options).
object CarClaude {
    class Answer(val text: String, val session: String?, val error: String?)

    const val PROMPT = "The user is talking with you by voice while driving; your reply is read aloud by text-to-speech " +
        "through the car's speakers. Answer in short, natural spoken sentences, usually one to four. No markdown, lists, " +
        "headings, code, URLs or emoji. If a full answer would be long, give the gist and offer to go on."

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun command(text: String, session: String?): String {
        val resume = if (session.isNullOrEmpty()) "" else "--resume ${q(session)} "
        return "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; export PATH; " +
            "cd ~ 2>/dev/null; command -v claude >/dev/null || { echo 'VOX: claude is not installed there'; exit 0; }; " +
            "printf %s ${q(text)} | claude -p --output-format json $resume--append-system-prompt ${q(PROMPT)} " +
            "--allowedTools 'WebSearch,WebFetch' 2>&1"
    }

    fun ask(vault: Vault, server: Settings.Server, text: String, session: String?): Answer {
        val first = run(vault, server, command(text, session))
        // A conversation the server no longer has: a new one.
        if (first.error != null && !session.isNullOrEmpty() && first.error.contains("session", true))
            return run(vault, server, command(text, null))
        return first
    }

    private fun run(vault: Vault, s: Settings.Server, cmd: String): Answer {
        val out = try {
            Bootstrap(vault).run(s.host, s.port, s.user, cmd)
        } catch (e: Bootstrap.UnknownHost) {
            return Answer("", null, "${s.name} is not trusted yet: open its terminal once in Vox")
        } catch (e: Exception) {
            return Answer("", null, "I can't reach ${s.name}: ${e.message ?: "no connection"}")
        }
        out.lines().firstOrNull { it.startsWith("VOX: ") }?.let { return Answer("", null, it.removePrefix("VOX: ")) }
        // The result: one line of JSON at the end (or, spread over lines, from its last opening brace at a line's start).
        val json = out.lines().lastOrNull { it.trimStart().startsWith("{") }?.let { runCatching { org.json.JSONObject(it.trim()) }.getOrNull() }
            ?: out.lastIndexOf("\n{").takeIf { it >= 0 }?.let { runCatching { org.json.JSONObject(out.substring(it + 1)) }.getOrNull() }
            ?: return Answer("", null, out.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Claude gave no answer")
        val result = json.optString("result")
        val id = json.optString("session_id").ifEmpty { null }
        if (json.optBoolean("is_error") || result.isEmpty())
            return Answer("", id, result.ifEmpty { json.optString("subtype").ifEmpty { "Claude had a problem" } })
        return Answer(result, id, null)
    }
}

// What Car mode needs from the service: the glasses' microphone through Vox's voice, the band on the glasses, the
// server Claude runs on.
interface CarHost {
    val handler: Handler
    // A listener on the glasses' microphone that runs until carStopListening; onEnd(why) ("" stopped) once.
    fun carListen(onPartial: (String) -> Unit, onPhrase: (String) -> Unit, onSpeaking: (Boolean) -> Unit, onEnd: (String) -> Unit)
    fun carStopListening()
    fun carBand(text: String, hold: Boolean)
    fun carServer(): Settings.Server?
    fun carVault(): Vault
    fun carChanged()
}

class CarMode(private val context: Context, private val host: CarHost) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

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
    private var nextDownAt = 0L
    private var listenAt = 0L

    private val startMic = Runnable { mic() }
    private val sendSoon = Runnable { finish() }
    private val noSpeech = Runnable { if (state == State.LISTENING && said.isBlank() && partial.isEmpty()) cancel("I didn't hear anything") }
    private val tooLong = Runnable { if (state == State.LISTENING) finish() }
    private val pulse = object : Runnable {
        override fun run() {
            if (state != State.THINKING) return
            CarSounds.play(CarSounds.Sound.THINK)
            h.postDelayed(this, 2400)
        }
    }

    fun start(greet: Boolean) {
        buttons.claim()
        show()
        if (greet) {
            CarSounds.play(CarSounds.Sound.ON)
            h.postDelayed({ if (state == State.IDLE) say("Car mode. Press next to talk to Claude.") }, 900)
        }
    }

    fun release() {
        reset()
        speech.release()
        buttons.release()
        dropFocus()
    }

    // Another app started playing: the keys back to Vox (its play / pause and previous are passed on to it).
    fun reclaim() = buttons.claim()

    // What the car shows: Vox's state, or (idle) what plays.
    fun show() {
        val now = Media.now
        when (state) {
            State.IDLE -> if (now != null && now.title.isNotEmpty()) buttons.show(now.title, now.artist, now.playing)
                else buttons.show("Vox", "Press next to talk to Claude", false)
            State.LISTENING -> buttons.show("Listening…", (said.toString() + partial).trim().ifEmpty { "Talk to Claude" }, true)
            State.THINKING -> buttons.show("Claude is thinking…", asked, true)
            State.SPEAKING -> buttons.show("Claude", lastAnswer.take(120), true)
        }
        host.carChanged()
    }

    // ------------------------------------------------ the buttons

    private fun key(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (down) {
                nextDownAt = now
                talk()
            } else if (state == State.LISTENING && now - nextDownAt > 700 && listenAt >= nextDownAt) finish() // held: let go sends
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> if (down) when (state) {
                State.LISTENING -> finish()
                State.THINKING -> cancel("Cancelled")
                State.SPEAKING -> quiet()
                State.IDLE -> Media.control("toggle")
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (down) when (state) {
                State.SPEAKING -> if (lastAnswer.isNotEmpty()) speak(lastAnswer)
                State.LISTENING, State.THINKING -> cancel("Cancelled")
                State.IDLE -> Media.control("prev")
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> if (down) if (state == State.IDLE) Media.control("toggle") else cancel("Stopped")
        }
    }

    // Next: talk (listening, it sends; thinking or speaking, a new question instead).
    fun talk() {
        when (state) {
            State.IDLE -> listen()
            State.LISTENING -> finish()
            State.THINKING -> {
                request++
                listen()
            }
            State.SPEAKING -> {
                speech.stop()
                listen()
            }
        }
    }

    // ------------------------------------------------ listening

    private fun listen() {
        reset()
        state = State.LISTENING
        listenAt = SystemClock.uptimeMillis()
        holdFocus()
        CarSounds.play(CarSounds.Sound.LISTEN)
        host.carBand("$MIC Listening… (talk to Claude)", true)
        show()
        h.postDelayed(startMic, 300) // after the chime
        h.postDelayed(noSpeech, 9000)
        h.postDelayed(tooLong, 60000)
    }

    private fun mic() {
        if (state != State.LISTENING) return
        micOn = true
        host.carListen(
            onPartial = { t -> h.post { if (state == State.LISTENING && micOn) heardSoFar(t) } },
            onPhrase = { t -> h.post { phrase(t) } },
            onSpeaking = { on -> h.post { if (on && state == State.LISTENING) { h.removeCallbacks(sendSoon); h.removeCallbacks(noSpeech) } } },
            onEnd = { why -> h.post { micEnded(why) } })
    }

    private fun heardSoFar(t: String) {
        partial = t
        host.carBand("$MIC ${(said.toString() + t).trim()}…", true)
        show()
    }

    private fun phrase(t: String) {
        if (state != State.LISTENING) return
        said.append(t.trim()).append(' ')
        partial = ""
        host.carBand("$MIC ${said.trim()}…", true)
        show()
        // A pause of about a second after the phrase: sent (more speech first: it waits for that).
        h.removeCallbacks(sendSoon)
        h.postDelayed(sendSoon, 1000)
    }

    // Send now (Next again, play / pause, a pause in speech, a minute): the listener ends with its last words.
    private fun finish() {
        if (state != State.LISTENING) return
        h.removeCallbacks(sendSoon)
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        if (micOn) host.carStopListening() // micEnded asks
        else {
            h.removeCallbacks(startMic)
            ask()
        }
    }

    private fun micEnded(why: String) {
        micOn = false
        if (state != State.LISTENING) return
        if (why.isNotEmpty() && said.isBlank()) return fail(why)
        ask()
    }

    // ------------------------------------------------ Claude

    private fun ask() {
        val text = said.toString().trim()
        said.setLength(0)
        partial = ""
        if (text.isEmpty()) return cancel("I didn't catch that")
        val words = VoiceCommands.normalize(text)
        if (words in NEW_CHAT) {
            settings.carSession = ""
            state = State.SPEAKING
            CarSounds.play(CarSounds.Sound.READY)
            return say("New conversation.")
        }
        if (words in NEVER_MIND) return cancel("Cancelled")
        val server = host.carServer() ?: return fail("Add a server for Claude in Vox first")
        state = State.THINKING
        asked = text
        CarSounds.play(CarSounds.Sound.SEND)
        host.carBand("$MIC “$text”  Claude is thinking…", true)
        show()
        h.postDelayed(pulse, 1800)
        val id = ++request
        val session = settings.carSession.ifEmpty { null }
        Log.i(ScouterService.TAG, "car: asking Claude on ${server.name}")
        Thread {
            val t0 = SystemClock.uptimeMillis()
            val a = CarClaude.ask(host.carVault(), server, text, session)
            Log.i(ScouterService.TAG, "car: answer in ${SystemClock.uptimeMillis() - t0} ms" + (a.error?.let { ": $it" } ?: ""))
            h.post { answered(id, a) }
        }.start()
    }

    private fun answered(id: Int, a: CarClaude.Answer) {
        if (id != request || state != State.THINKING) return
        h.removeCallbacks(pulse)
        a.session?.let { settings.carSession = it }
        if (a.error != null) return fail(a.error)
        lastAnswer = a.text
        host.carBand("Claude: ${a.text}", true)
        speak(a.text)
    }

    // ------------------------------------------------ speaking

    private fun speak(text: String) {
        state = State.SPEAKING
        holdFocus()
        show()
        speech.speak(text, settings.carRate.coerceIn(60, 160) / 100f, "answer")
    }

    // A line of Vox's own (not Claude's).
    private fun say(text: String) {
        state = State.SPEAKING
        holdFocus()
        show()
        speech.speak(text, settings.carRate.coerceIn(60, 160) / 100f, "vox")
    }

    private fun spoken(id: String) {
        if (state != State.SPEAKING) return
        state = State.IDLE
        dropFocus()
        if (id.startsWith("answer")) host.carBand("Claude: $lastAnswer", false)
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

    private fun cancel(why: String) {
        reset()
        CarSounds.play(CarSounds.Sound.CANCEL)
        host.carBand("$MIC $why", false)
        dropFocus()
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

    // Whatever runs stops (the request's answer is dropped when it comes).
    private fun reset() {
        h.removeCallbacks(startMic)
        h.removeCallbacks(sendSoon)
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        h.removeCallbacks(pulse)
        if (micOn) host.carStopListening()
        micOn = false
        said.setLength(0)
        partial = ""
        request++
        speech.stop()
        state = State.IDLE
    }

    // The music ducks while Vox listens, thinks and speaks.
    private fun holdFocus() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = f
    }

    private fun dropFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    // The voice test (Vox's Voice tab): the chimes and a line.
    fun test() {
        if (state != State.IDLE) return
        CarSounds.play(CarSounds.Sound.LISTEN)
        h.postDelayed({ CarSounds.play(CarSounds.Sound.SEND) }, 700)
        h.postDelayed({ if (state == State.IDLE) say("This is how Claude sounds in the car.") }, 1500)
    }

    fun newConversation() {
        settings.carSession = ""
        if (state == State.IDLE) {
            CarSounds.play(CarSounds.Sound.READY)
            say("New conversation.")
        }
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

package scouterkit.ssh

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

// The speech model, downloaded once into the app's files (about 660 MB), each file checked by its SHA-256:
// NVIDIA's Parakeet TDT 0.6B v2 (English, int8, as converted for sherpa-onnx; CC-BY-4.0) and Silero VAD (MIT).
object VoiceModel {
    class Part(val name: String, val url: String, val size: Long, val sha256: String)

    private const val HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2"
    val parts = listOf(
        Part("encoder.int8.onnx", "$HF/encoder.int8.onnx", 652184296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
        Part("decoder.int8.onnx", "$HF/decoder.int8.onnx", 7257753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
        Part("joiner.int8.onnx", "$HF/joiner.int8.onnx", 1739080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
        Part("tokens.txt", "$HF/tokens.txt", 9384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
        Part("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )
    val bytes = parts.sumOf { it.size }

    @Volatile var progress = -1L // bytes in place while downloading, else -1
    @Volatile var problem: String? = null // why the last download stopped

    fun dir(context: Context) = File(context.filesDir, "voice/parakeet-tdt-0.6b-v2-int8")
    fun file(context: Context, name: String) = File(dir(context), name)

    // Every file in place (each was checked as it came).
    fun ready(context: Context) = parts.all { file(context, it.name).length() == it.size }

    // This build has the speech engine (native/third_party/fetch_sherpa.sh).
    val available: Boolean by lazy { runCatching { System.loadLibrary("sherpa-onnx-jni"); true }.getOrDefault(false) }

    // What is missing, going on from a partial file; blocks. Null when everything is in place, else why not.
    fun download(context: Context): String? {
        val dir = dir(context).apply { mkdirs() }
        var done = parts.filter { File(dir, it.name).length() == it.size }.sumOf { it.size }
        progress = done
        problem = null
        try {
            for (p in parts) {
                val target = File(dir, p.name)
                if (target.length() == p.size) continue
                val part = File(dir, p.name + ".part")
                if (part.length() >= p.size) part.delete()
                var have = part.length()
                val c = URL(p.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 30000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code !in 200..299) throw IllegalStateException("${p.name}: the server answered $code")
                val append = have > 0 && code == 206
                if (!append) have = 0
                c.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            progress = done + have
                        }
                    }
                }
                if (part.length() != p.size || sha256(part) != p.sha256) {
                    part.delete()
                    throw IllegalStateException("${p.name} did not arrive whole (size or SHA-256): try again")
                }
                if (!part.renameTo(target)) throw IllegalStateException("${p.name}: cannot keep it")
                done += p.size
                progress = done
            }
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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// Parakeet on the phone (sherpa-onnx, 4 threads): a phrase's samples (16 kHz mono) to text. Loading takes a few
// seconds; it stays loaded while the session runs.
class VoiceEngine(context: Context) {
    private val recognizer: OfflineRecognizer
    val vadConfig: VadModelConfig

    init {
        fun f(name: String) = VoiceModel.file(context, name).path
        recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = f("encoder.int8.onnx"), decoder = f("decoder.int8.onnx"),
                    joiner = f("joiner.int8.onnx")),
                tokens = f("tokens.txt"), numThreads = 4, modelType = "nemo_transducer"),
            decodingMethod = "greedy_search"))
        // A phrase ends at half a second of quiet.
        vadConfig = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = f("silero_vad.onnx"), threshold = 0.5f,
            minSilenceDuration = 0.5f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 20f), sampleRate = 16000)
    }

    @Synchronized
    fun text(samples: FloatArray): String {
        val s = recognizer.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            recognizer.decode(s)
            return recognizer.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    @Synchronized
    fun release() = recognizer.release()
}

// Floats that grow (the phrase being spoken).
private class Floats {
    var data = FloatArray(16000)
    var size = 0
    fun add(src: FloatArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }
    fun keepLast(n: Int) {
        if (size <= n) return
        System.arraycopy(data, size - n, data, 0, n)
        size = n
    }
    fun toArray() = data.copyOf(size)
    fun clear() {
        size = 0
    }
}

// The glasses' microphone to phrases: Silero VAD cuts what is said at each pause and Parakeet reads the phrase;
// with realtime on, the phrase so far is read again every 0.6 s while it is spoken (onPartial). once: until the
// first phrase (push to talk; 8 s for it to start, 20 s in all), else until stop(). onEnd: why it ended ("" for
// a phrase or stop()).
class VoiceListener(
    private val engine: VoiceEngine,
    private val realtime: Boolean,
    val once: Boolean,
    private val onPartial: (String) -> Unit,
    private val onPhrase: (String) -> Unit,
    private val onEnd: (String) -> Unit,
) {
    @Volatile private var stopped = false

    fun start() = Thread({ run() }, "voice").start()

    fun stop() {
        stopped = true
    }

    private fun run() {
        var why = ""
        var vad: Vad? = null
        try {
            vad = Vad(null, engine.vadConfig)
            if (!Native.micStart()) {
                why = "the glasses' microphone did not start (the right arm connected?)"
                return
            }
            val got = FloatArray(4800)
            val window = FloatArray(512)
            var fill = 0
            val before = Floats() // the last 0.3 s before speech: the phrase's start
            val phrase = Floats()
            var speaking = false
            var lastPartial = 0L
            var heard = false
            val started = android.os.SystemClock.elapsedRealtime()
            while (!stopped) {
                val n = Native.micRead(got)
                if (n < 0) {
                    why = "the glasses' microphone stopped"
                    break
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (n == 0) {
                    Thread.sleep(15)
                } else {
                    for (i in 0 until n) {
                        window[fill++] = got[i]
                        if (fill == window.size) {
                            vad.acceptWaveform(window.copyOf())
                            fill = 0
                        }
                    }
                    if (speaking) phrase.add(got, n) else {
                        before.add(got, n)
                        before.keepLast(4800)
                    }
                    if (!speaking && vad.isSpeechDetected()) {
                        speaking = true
                        phrase.clear()
                        phrase.add(before.toArray(), before.size)
                        before.clear()
                    }
                    if (speaking && realtime && now - lastPartial > 600 && phrase.size > 8000) {
                        lastPartial = now
                        val t = engine.text(phrase.toArray())
                        if (t.isNotEmpty() && !stopped) onPartial(t)
                    }
                    while (!vad.empty()) {
                        val seg = vad.front()
                        vad.pop()
                        speaking = false
                        phrase.clear()
                        val t = engine.text(seg.samples)
                        if (t.isNotEmpty()) {
                            heard = true
                            onPhrase(t)
                            if (once) stopped = true
                        }
                    }
                }
                if (once && !heard && !speaking && now - started > 8000) {
                    why = "nothing heard"
                    break
                }
                if (once && now - started > 20000) break
            }
        } catch (e: Exception) {
            why = e.message ?: "voice stopped"
        } finally {
            runCatching { Native.micStop() }
            runCatching { vad?.release() }
            onEnd(why)
        }
    }
}

// What a phrase does: the custom commands first (Settings.voiceCommands), then the built-in ones. Phrases are
// compared in lower case without punctuation; "type …", "run …" and "claude …" keep what follows as it was said.
object VoiceCommands {
    class Command(val phrase: String, val action: String, val arg: String = "")
    class Match(val action: String, val arg: String, val label: String)

    const val SOURCE = "source" // arg: a source key (Settings.sources)
    const val NEXT = "next"
    const val PREV = "prev"
    const val UP = "up" // the terminal of the machine shown
    const val DOWN = "down" // its screen
    const val DISPLAY_ON = "display_on"
    const val DISPLAY_OFF = "display_off"
    const val BRIGHTER = "brighter"
    const val DIMMER = "dimmer"
    const val VISIBLE_ON = "visible_on"
    const val VISIBLE_OFF = "visible_off"
    const val TYPE = "type" // arg: text, typed into the terminal
    const val RUN = "run" // arg: text, typed and Enter
    const val KEY = "key" // arg: enter, escape, tab, ctrl-c, up, down, backspace
    const val DICTATE_ON = "dictate_on"
    const val DICTATE_OFF = "dictate_off"
    const val STOP = "stop" // stop listening
    const val TAB = "tab" // arg: a tab action (Tabs.command)
    const val POINTER = "pointer" // arg: a pointer command (toggle, click, right, up, down)
    const val MEDIA = "media" // arg: toggle, next, prev (what plays on the phone)
    const val TIMER = "timer" // arg: SECONDS (set and start), toggle, reset

    // For the custom commands' editor: what a command can do (SOURCE is picked from the sources).
    val CHOICES = linkedMapOf(NEXT to "Next source", PREV to "Previous source", UP to "Terminal (this machine)",
        DOWN to "Screen (this machine)", DISPLAY_OFF to "Display off", DISPLAY_ON to "Display on", BRIGHTER to "Brighter",
        DIMMER to "Dimmer", VISIBLE_ON to "Always visible on", VISIBLE_OFF to "Only when looking up", TYPE to "Type text",
        RUN to "Type text and Enter", "key:enter" to "Press Enter", "key:escape" to "Press Escape", "key:tab" to "Press Tab",
        "key:ctrl-c" to "Press Ctrl-C", "key:up" to "Press Up", "key:down" to "Press Down", DICTATE_ON to "Start dictating",
        DICTATE_OFF to "Stop dictating", STOP to "Stop listening", "tab:new" to "New tab", "tab:next" to "Next tab",
        "tab:prev" to "Previous tab", "tab:close" to "Close the tab", "tab:claude" to "Claude's tab", "tab:cursor" to "Cursor's tab",
        "pointer:toggle" to "Pointer on / off", "pointer:click" to "Click", "media:toggle" to "Play / pause",
        "media:next" to "Next song", "media:prev" to "Previous song", "timer:toggle" to "Pause / go on with the timer",
        "timer:reset" to "Stop the timer")

    // What the built-in commands are, for the Voice tab.
    const val HELP = "Next · Back · Phone terminal · Laptop terminal · Phone screen · Laptop screen (any saved server's " +
        "name) · Terminal · Screen · Display off / on · Brighter · Dimmer · Always visible · Only when I look up · " +
        "Type … · Run … (types it and Enter) · Claude … (types it and Enter) · Enter · Escape · Tab · Control C · Up · " +
        "Down · Dictate (everything you say is typed; \"send\" presses Enter, \"stop dictating\" ends it) · Stop listening · " +
        "New tab · Next tab · Previous tab · Tab 2 · Close tab · Claude tab · Cursor tab · Pointer · Click · Right click · " +
        "Scroll up / down · Play · Pause · Next song · Previous song · Timer 5 minutes · Pause the timer · Stop the timer"

    fun normalize(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    // The wake word at the start ("vox", as Parakeet may write it), and what was said after it; null without it.
    fun afterWake(heard: String): String? {
        val m = Regex("^\\s*(vox|vocs|vocks|box|fox|fox's|vox's|vos|voks)\\b[\\s,.:!?]*", RegexOption.IGNORE_CASE).find(heard)
            ?: return null
        return heard.substring(m.range.last + 1).trim()
    }

    fun label(settings: Settings, action: String, arg: String): String = when (action) {
        SOURCE -> settings.sourceName(arg)
        TYPE -> "type \"$arg\""
        TAB -> CHOICES["tab:$arg"] ?: "tab $arg"
        POINTER -> CHOICES["pointer:$arg"] ?: "pointer $arg"
        MEDIA -> CHOICES["media:$arg"] ?: "media $arg"
        TIMER -> CHOICES["timer:$arg"] ?: arg.toLongOrNull()?.let { "timer ${Timer.text(it * 1000)}" } ?: "timer $arg"
        RUN -> "run \"$arg\""
        KEY -> arg
        else -> CHOICES[action] ?: action
    }

    fun match(settings: Settings, heard: String): Match? {
        val t = normalize(heard)
        if (t.isEmpty()) return null
        for (c in settings.voiceCommands()) if (normalize(c.phrase) == t) {
            val (action, arg) = when {
                c.action.startsWith("key:") -> KEY to c.action.removePrefix("key:")
                c.action.startsWith("tab:") -> TAB to c.action.removePrefix("tab:")
                c.action.startsWith("pointer:") -> POINTER to c.action.removePrefix("pointer:")
                c.action.startsWith("media:") -> MEDIA to c.action.removePrefix("media:")
                c.action.startsWith("timer:") -> TIMER to c.action.removePrefix("timer:")
                else -> c.action to c.arg
            }
            return Match(action, arg, label(settings, action, arg))
        }
        fun m(action: String, arg: String = "") = Match(action, arg, label(settings, action, arg))
        fun rest(word: String): String? = Regex("^\\s*$word[\\s,.:]+(.+)$", RegexOption.IGNORE_CASE).find(heard)?.groupValues?.get(1)?.trim()
        rest("type")?.let { return m(TYPE, it) }
        rest("(?:run|execute)")?.let { return m(RUN, it) }
        rest("(?:ask claude|tell claude|claude)")?.let { return m(RUN, it) }
        val fixed = listOf(
            "next|next one|next source|next screen|forward" to NEXT,
            "back|go back|previous|previous one|previous source|last one" to PREV,
            "terminal|the terminal|show the terminal" to UP,
            "screen|the screen|show the screen" to DOWN,
            "display off|screen off|glasses off|go dark|lights off|dark" to DISPLAY_OFF,
            "display on|screen on|glasses on|wake up|lights on" to DISPLAY_ON,
            "brighter|brightness up|more brightness|lighter" to BRIGHTER,
            "dimmer|darker|brightness down|less brightness" to DIMMER,
            "always visible|always show|stay visible|always on" to VISIBLE_ON,
            "only when i look up|look up mode|always visible off" to VISIBLE_OFF,
            "dictate|start dictating|dictation|dictation on" to DICTATE_ON,
            "stop dictating|dictation off|end dictation" to DICTATE_OFF,
            "stop listening|go to sleep|stop voice" to STOP,
        )
        for ((words, action) in fixed) if (t in words.split("|")) return m(action)
        val keys = listOf("enter|send|submit|send it|press enter" to "enter", "escape|cancel|press escape" to "escape",
            "tab|press tab" to "tab", "control c|interrupt|stop that|cancel that" to "ctrl-c", "up|arrow up|press up" to "up",
            "down|arrow down|press down" to "down", "backspace|delete that" to "backspace")
        for ((words, key) in keys) if (t in words.split("|")) return m(KEY, key)
        val tabs = listOf("new tab|open a tab|open tab|new shell" to "new", "next tab|tab right" to "next",
            "previous tab|last tab|tab left" to "prev", "close tab|close the tab|close this tab" to "close",
            "claude tab|claude's tab|show claude|open claude" to "claude", "cursor tab|cursor's tab|show cursor|open cursor" to "cursor")
        for ((words, tab) in tabs) if (t in words.split("|")) return m(TAB, tab)
        val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        Regex("^tab (\\w+)$").find(t)?.let { r ->
            val w = r.groupValues[1]
            val n = w.toIntOrNull() ?: numbers.indexOf(w).takeIf { it >= 0 }
            if (n != null) return m(TAB, "select:$n")
        }
        val pointer = listOf("pointer|show the pointer|mouse|cursor on|pointer off|hide the pointer" to "toggle",
            "click|click it|tap" to "click", "right click" to "right", "scroll up" to "up", "scroll down" to "down")
        for ((words, cmd) in pointer) if (t in words.split("|")) return m(POINTER, cmd)
        val media = listOf("play|pause|play music|pause music|resume|stop the music|play pause" to "toggle",
            "next song|next track|skip|skip song|next video" to "next", "previous song|previous track|last song|go back a song" to "prev")
        for ((words, cmd) in media) if (t in words.split("|")) return m(MEDIA, cmd)
        // A timer: "timer 5 minutes", "set a timer for ten minutes", "5 minute timer", "timer 30 seconds".
        val count = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        fun amount(w: String): Int? = w.toIntOrNull() ?: count.indexOf(w).takeIf { it >= 0 }
            ?: mapOf("thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "half an" to 30, "a" to 1, "an" to 1)[w]
        Regex("^(?:set |start )?(?:a )?timer (?:for )?(\\w+(?: five)?) (minute|minutes|second|seconds|hour|hours)$").find(t)?.let { r ->
            amount(r.groupValues[1])?.let { n -> return m(TIMER, (n * when (r.groupValues[2].first()) { 's' -> 1; 'h' -> 3600; else -> 60 }).toString()) }
        }
        Regex("^(?:set |start )?(?:a |an )?(\\w+) (minute|second|hour) timer$").find(t)?.let { r ->
            amount(r.groupValues[1])?.let { n -> return m(TIMER, (n * when (r.groupValues[2].first()) { 's' -> 1; 'h' -> 3600; else -> 60 }).toString()) }
        }
        if (t in listOf("pause the timer", "pause timer", "resume the timer", "resume timer", "start the timer")) return m(TIMER, "toggle")
        if (t in listOf("stop the timer", "stop timer", "cancel the timer", "cancel timer", "reset the timer", "reset timer")) return m(TIMER, "reset")
        // "<name> terminal" / "<name> screen" (show / open / go to / switch to the …).
        Regex("^(?:show |open |go to |switch to )?(?:the |my )?(.+?) (terminal|screen)$").find(t)?.let { r ->
            val name = r.groupValues[1]
            val screen = r.groupValues[2] == "screen"
            val servers = settings.servers()
            val server = servers.firstOrNull { normalize(it.name) == name }
                ?: if (name == "phone" || name == "this phone") servers.firstOrNull { it.local } else null
            if (screen && (name == "phone" || name == "this phone" || server?.local == true)) return m(SOURCE, Settings.PHONE)
            if (server != null) return m(SOURCE, if (screen) "screen:${server.id}" else "term:${server.id}")
        }
        return null
    }
}

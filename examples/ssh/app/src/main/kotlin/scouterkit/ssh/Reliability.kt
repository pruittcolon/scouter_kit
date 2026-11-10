package scouterkit.ssh

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

// Vox's own log: Android's log of this app's process (logcat --pid, which an app may read of itself), with a header
// saying what Vox was doing. Kept to a file, shared, or sent to the phone's own server, where Claude can read it
// (~/vox-logs/latest.txt); a crash saves it and it is sent the next time Vox runs. Nothing runs on the glasses.
object LogBook {
    private fun dir(context: Context) = context.getExternalFilesDir(null) ?: context.filesDir
    private fun pending(context: Context) = File(context.filesDir, "crash-log.txt")

    fun capture(context: Context, why: String): File {
        val head = buildString {
            append("Vox ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n")
            append("${Date()} · $why\n")
            append("glasses ${Session.glasses} (left ${Session.left}, right ${Session.right}) · showing ${Session.showing} · " +
                "car ${Session.car.ifEmpty { "off" }} · source ${Session.source}\n\n")
        }
        val log = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "-t", "6000")
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
        }.getOrElse { "logcat: ${it.message}" }
        val f = File(dir(context), "vox-log.txt")
        f.writeText(head + log)
        return f
    }

    // The uncaught exception's thread: the log as it is now, kept to be sent next time.
    fun crash(context: Context, e: Throwable) {
        runCatching {
            val f = capture(context, "crash: ${e.javaClass.simpleName}: ${e.message}")
            f.copyTo(pending(context), overwrite = true)
        }
    }

    // The phone's own server (else the server in use): where Claude reads it.
    fun server(settings: Settings): Settings.Server? = settings.servers().let { l -> l.firstOrNull { it.local } ?: settings.active() }

    // Off the main thread. Null: saved there (the path), else why not.
    fun send(context: Context, vault: Vault, file: File): Pair<String?, String?> {
        val s = server(Settings(context)) ?: return null to "no server to send it to"
        return runCatching {
            val out = Bootstrap(vault).runWithInput(s.host, s.port, s.user,
                "mkdir -p ~/vox-logs && f=~/vox-logs/vox-\$(date +%Y%m%d-%H%M%S).txt && cat > \"\$f\" && " +
                    "ln -sf \"\$f\" ~/vox-logs/latest.txt && echo \"SAVED \$f\"", file.readBytes())
            val saved = out.lines().firstOrNull { it.startsWith("SAVED ") }?.removePrefix("SAVED ")
            if (saved != null) saved to null else null to (out.trim().ifEmpty { "the server said nothing" })
        }.getOrElse { null to (it.message ?: "could not reach ${s.name}") }
    }

    // A crash's log from the last run, sent once the server answers (the service's start).
    fun sendPending(context: Context, vault: Vault) {
        val p = pending(context)
        if (!p.exists()) return
        Thread {
            val (saved, why) = send(context, vault, p)
            if (saved != null) {
                p.delete()
                Log.i(ScouterService.TAG, "log: the last crash's log is on the server: $saved")
            } else Log.w(ScouterService.TAG, "log: the last crash's log was not sent: $why")
        }.start()
    }

    // For a share: the newest part of the file (an intent carries about a megabyte at most).
    fun text(file: File): String {
        val t = runCatching { file.readText() }.getOrDefault("")
        return if (t.length <= 200_000) t else t.substring(0, t.indexOf('\n').coerceAtLeast(0)) + "\n…\n" + t.takeLast(190_000)
    }
}

// Updates from inside the app: the newest build on the project's release page (GitHub, public: no account),
// fetched and handed to Android's package installer, which asks the user once and keeps the app's data.
object Updater {
    private const val API = "https://api.github.com/repos/prunewhip/scouter_kit/releases/tags/scouter-phone-latest"
    private const val ASSET = "scouter-phone.apk"
    @Volatile var state = ""
    @Volatile var url: String? = null // a newer build to install
    @Volatile var busy = false

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "Vox-updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    // Off the main thread; done after (state, url set).
    fun check(context: Context, done: () -> Unit) {
        if (busy) return
        busy = true
        state = "Looking for an update…"
        Thread {
            runCatching {
                val c = open(API)
                val json = try {
                    if (c.responseCode != 200) throw IllegalStateException("GitHub said ${c.responseCode}")
                    org.json.JSONObject(c.inputStream.bufferedReader().readText())
                } finally {
                    c.disconnect()
                }
                val assets = json.getJSONArray("assets")
                val a = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == ASSET }
                    ?: throw IllegalStateException("no app on the release page yet")
                val built = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US).parse(a.getString("updated_at"))!!.time
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val newer = built > installed + 120_000
                url = if (newer) a.getString("browser_download_url") else null
                val day = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(built))
                state = if (newer) "A newer Vox (built $day) is ready to install." else "Vox is up to date (the newest was built $day)."
            }.onFailure { state = "Could not look: ${it.message}" }
            busy = false
            done()
        }.start()
    }

    // Downloads the newer build and asks Android to install it over this one. Off the main thread.
    fun install(context: Context, done: () -> Unit) {
        val u = url ?: return
        if (busy) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = "Allow Vox to install apps (Android opens the setting), then tap Install again."
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return done()
        }
        busy = true
        Thread {
            runCatching {
                val f = File(context.cacheDir, "update.apk")
                val c = open(u)
                c.instanceFollowRedirects = true
                try {
                    if (c.responseCode != 200) throw IllegalStateException("the download said ${c.responseCode}")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var have = 0L
                            var shown = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                val now = SystemClock.uptimeMillis()
                                if (now - shown > 500) {
                                    shown = now
                                    state = "Downloading… ${have / 1_000_000} of ${maxOf(total, have) / 1_000_000} MB"
                                    done()
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                state = "Installing… Android asks to update Vox."
                done()
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("vox.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val back = PendingIntent.getBroadcast(context, id, Intent(context, UpdateResult::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(back.intentSender)
                }
            }.onFailure { state = "The update stopped: ${it.message}" }
            busy = false
            done()
        }.start()
    }
}

// Android's installer answering: its question shown to the user, or why it failed.
class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ask = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state = "Updated."
            else -> Updater.state = "Not installed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it")
        }
    }
}

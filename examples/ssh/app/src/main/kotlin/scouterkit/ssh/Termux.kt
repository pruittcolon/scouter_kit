package scouterkit.ssh

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

// Termux on this phone as the server: its sshd on 127.0.0.1:8022 (only this phone can reach it) and its
// mosh-server. Commands go through Termux's RUN_COMMAND service, which needs the "Run commands in Termux
// environment" permission and allow-external-apps = true in ~/.termux/termux.properties.
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOST = "127.0.0.1"
    const val PORT = 8022
    const val USER = "termux" // Termux's sshd takes any user name: there is one user
    // Claude Code in the Ubuntu proot, in its tmux session "claude": the claude shell function attaches to it,
    // or starts it.
    const val CLAUDE = "proot-distro login ubuntu -- bash -ic claude"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val RESULT = "scouterkit.ssh.TERMUX_RESULT"
    private const val SSHD = "pgrep -x sshd >/dev/null || sshd -o ListenAddress=127.0.0.1"
    // The phone's server started again: the user's own `ssh` shortcut where there is one (sshd in the Ubuntu proot,
    // the server the glasses use), else Termux's sshd.
    const val START = "if [ -x \"${'$'}PREFIX/bin/ssh\" ] && grep -q proot \"${'$'}PREFIX/bin/ssh\"; then \"${'$'}PREFIX/bin/ssh\"; else $SSHD; fi"

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun permitted(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    // Termux defines RUN_COMMAND (the F-Droid and GitHub builds; the Google Play build does not).
    fun hasRunCommand(c: Context) = runCatching { c.packageManager.getPermissionInfo(PERMISSION, 0) }.isSuccess

    // What stands in the way, or null.
    fun problem(c: Context): String? = when {
        !installed(c) -> "Termux is not installed"
        !hasRunCommand(c) -> "this Termux (the Google Play build) cannot run commands for other apps: start the " +
            "server yourself (in Ubuntu: /usr/sbin/sshd, or in Termux: sshd)"
        !permitted(c) -> "allow \"Run commands in Termux\" first"
        else -> null
    }

    // Runs a bash script in Termux, in the background. With a tag, TermuxResult gets its output.
    fun run(c: Context, script: String, tag: String? = null): String? {
        problem(c)?.let { return it }
        val intent = Intent("com.termux.RUN_COMMAND").setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        if (tag != null) {
            val result = Intent(c, TermuxResult::class.java).setAction(RESULT).putExtra("tag", tag)
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", PendingIntent.getBroadcast(c, tag.hashCode(), result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        }
        return try {
            c.startForegroundService(intent)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    // Installs openssh and mosh in Termux if they are missing, lets this phone's key in, starts sshd on
    // 127.0.0.1 and reports the host keys' fingerprints (Session.termux, Settings.termuxKeys).
    fun setUp(c: Context, publicKey: String): String? {
        val key = publicKey.trim()
        require(Regex("[A-Za-z0-9-]+ [A-Za-z0-9+/=]+( [A-Za-z0-9._@-]+)?").matches(key)) { "not a public key line" }
        val script = """
            log=${'$'}HOME/.scouter-setup.log
            export DEBIAN_FRONTEND=noninteractive
            if ! command -v sshd >/dev/null || ! command -v mosh-server >/dev/null; then
              echo "installing openssh and mosh"
              if ! pkg install -y openssh mosh </dev/null >"${'$'}log" 2>&1; then
                echo "FAILED: pkg install openssh mosh (see ~/.scouter-setup.log)"; tail -n 5 "${'$'}log"; exit 1
              fi
            fi
            umask 077
            mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys
            grep -qxF '$key' ~/.ssh/authorized_keys || printf '%s\n' '$key' >> ~/.ssh/authorized_keys
            ssh-keygen -A >/dev/null 2>&1
            $SSHD
            for k in ${'$'}PREFIX/etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "${'$'}k"; done
            command -v proot-distro >/dev/null && echo HAS_PROOT
            echo READY
        """.trimIndent()
        Session.termux = "setting up…"
        Session.changed()
        return run(c, script, "setup")
    }

    // Before a session: sshd up (started if not), waits for its port up to 8 s. Off the main thread.
    fun ensureSshd(c: Context): String? {
        if (listening()) return null
        run(c, START)?.let { return it }
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(300)
            if (listening()) return null
        }
        return "the SSH server on 127.0.0.1:8022 is not running: start it (in Ubuntu: /usr/sbin/sshd)"
    }

    fun listening() = runCatching { Socket().use { it.connect(InetSocketAddress(HOST, PORT), 300) } }.isSuccess

    // From the setup script's output: SHA256 fingerprints.
    fun fingerprints(output: String) = Regex("SHA256:[A-Za-z0-9+/]{43}").findAll(output).map { it.value }.toSet()
}

// Termux's answer to a command run with a tag (its RUN_COMMAND pending intent): the "result" bundle.
class TermuxResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        val stdout = result.getString("stdout").orEmpty()
        val stderr = result.getString("stderr").orEmpty()
        val code = result.getInt("exitCode", -1)
        val err = result.getString("errmsg").orEmpty()
        Log.i(ScouterService.TAG, "termux ${intent.getStringExtra("tag")}: exit $code ${err.take(200)}")
        if (intent.getStringExtra("tag") != "setup") return
        if (stdout.contains("READY")) {
            val settings = Settings(context)
            settings.termuxKeys = Termux.fingerprints(stdout).joinToString(" ")
            Session.termux = "ready" + if (stdout.contains("HAS_PROOT")) "" else " (no proot-distro: the Claude command needs it)"
            // A session waiting for the server tries again.
            if (ScouterService.running) ScouterService.send(context)
        } else {
            val why = (stdout + "\n" + stderr + "\n" + err).lines().map { it.trim() }.filter { it.isNotEmpty() }
                .takeLast(4).joinToString(" / ")
            Session.termux = "error: " + why.ifEmpty { "exit $code" }
        }
        Session.changed()
    }
}

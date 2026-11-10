package scouterkit.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64

// Starts a mosh-server over SSH as mosh.pl does and returns where it listens and its key (R18). The key
// goes straight to the mosh client; SSH is closed right after (the server waits 60 s for the first datagram).
class Bootstrap(private val vault: Vault) {
    data class Mosh(val ip: String, val port: Int, val key: String, val pid: Int)

    class UnknownHost(val fingerprint: String) : Exception("unknown host key $fingerprint")

    // An SSH session with the vault's key, the server's host key checked (UnknownHost: not trusted yet).
    private fun connect(host: String, port: Int, user: String): com.jcraft.jsch.Session {
        val identity = vault.get(Vault.SSH_KEY) ?: throw IllegalStateException("no SSH key: import one in Settings")
        val ip = InetAddress.getByName(host).hostAddress!!
        val jsch = JSch()
        jsch.addIdentity("key", identity, null, vault.get(Vault.SSH_PASSPHRASE))
        val hosts = Hosts("$host:$port", vault.text(Vault.HOST_KEY))
        jsch.hostKeyRepository = hosts
        val session = jsch.getSession(user, ip, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.userInfo = Quiet
        try {
            session.connect(15000)
        } catch (e: JSchException) {
            hosts.unknown?.let { throw UnknownHost(it) }
            throw e
        }
        return session
    }

    // A command's output as it comes (a server's screen, RemoteScreen); close() ends the command and the session.
    // output: the command's input (stream(..., input = true); else null).
    class Stream(private val session: com.jcraft.jsch.Session, private val channel: ChannelExec,
                 val input: java.io.InputStream, private val err: java.io.ByteArrayOutputStream,
                 val output: java.io.OutputStream? = null) : java.io.Closeable {
        val errors get() = err.toString(Charsets.UTF_8.name()).trim()
        val exitStatus get() = channel.exitStatus
        override fun close() {
            runCatching { channel.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    fun stream(host: String, port: Int, user: String, command: String, input: Boolean = false): Stream {
        val session = connect(host, port, user)
        session.setServerAliveInterval(10000)
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val err = java.io.ByteArrayOutputStream()
        channel.setErrStream(err)
        val out = channel.inputStream
        val toCommand = if (input) channel.outputStream else null
        try {
            channel.connect(15000)
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
        return Stream(session, channel, out, err, toCommand)
    }

    // A short command's output on a connection kept open between calls (the tabs: tmux, every few seconds while
    // the terminal shows), opened again when it dropped. One per server, each with its own lock: a server that
    // does not answer holds up no other's.
    fun runShared(host: String, port: Int, user: String, command: String): String {
        val key = "$user@$host:$port"
        val lock = synchronized(Bootstrap) { locks.getOrPut(key) { Any() } }
        return synchronized(lock) { runOn(key, host, port, user, command) }
    }

    private fun runOn(key: String, host: String, port: Int, user: String, command: String): String {
        val s: com.jcraft.jsch.Session = synchronized(Bootstrap) { shared[key] }?.takeIf { it.isConnected } ?: run {
            synchronized(Bootstrap) { shared.remove(key) }?.let { old -> runCatching { old.disconnect() } }
            val fresh = connect(host, port, user)
            fresh.setServerAliveInterval(15000)
            fresh.timeout = 15000
            synchronized(Bootstrap) { shared[key] = fresh }
            fresh
        }
        return try {
            val channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(java.io.ByteArrayOutputStream())
            val input = channel.inputStream
            channel.connect(15000)
            val out = input.readBytes().toString(Charsets.UTF_8)
            channel.disconnect()
            out
        } catch (e: Exception) {
            runCatching { s.disconnect() } // the next call opens a fresh one
            synchronized(Bootstrap) { if (shared[key] === s) shared.remove(key) }
            throw e
        }
    }

    // A short command's output (tmux for the tabs), on a connection of its own; at most 15 s.
    // A command with bytes on its stdin (Vox's log sent to the server), on its own connection.
    fun runWithInput(host: String, port: Int, user: String, command: String, input: ByteArray): String {
        val session = connect(host, port, user)
        session.timeout = 15000
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(java.io.ByteArrayInputStream(input))
            channel.setErrStream(java.io.ByteArrayOutputStream())
            val out = channel.inputStream
            channel.connect(15000)
            val text = out.readBytes().toString(Charsets.UTF_8)
            channel.disconnect()
            return text
        } finally {
            session.disconnect()
        }
    }

    fun run(host: String, port: Int, user: String, command: String): String {
        val session = connect(host, port, user)
        session.timeout = 15000
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(java.io.ByteArrayOutputStream())
            val input = channel.inputStream
            channel.connect(15000)
            val out = input.readBytes().toString(Charsets.UTF_8)
            channel.disconnect()
            return out
        } finally {
            session.disconnect()
        }
    }

    fun start(host: String, port: Int, user: String, command: String, ports: String, previousPid: Int): Mosh {
        val session = connect(host, port, user)
        val ip = session.host // the address connected to, as resolved
        val watchdog = Thread {
            try {
                Thread.sleep(30000)
                session.disconnect()
            } catch (_: InterruptedException) {
            }
        }
        watchdog.start()
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setPty(true)
            channel.setCommand("sh -c '" + remote(command, ports, previousPid) + "'")
            val reader = channel.inputStream.bufferedReader()
            channel.connect(15000)
            val connect = Regex("^MOSH CONNECT (\\d+?) ([A-Za-z0-9/+]{22})\\s*$")
            val detached = Regex("mosh-server detached, pid = (\\d+)")
            val output = StringBuilder()
            var found: MatchResult? = null
            var pid = 0
            while (found == null || pid == 0) {
                val line = reader.readLine()?.trimEnd('\r') ?: break
                detached.find(line)?.let { pid = it.groupValues[1].toInt() }
                val m = connect.find(line)
                if (m != null) found = m else if (found == null) output.appendLine(line)
            }
            channel.disconnect()
            val m = found ?: throw IllegalStateException("mosh-server did not start: " + output.toString().take(500))
            return Mosh(ip, m.groupValues[1].toInt(), m.groupValues[2], pid)
        } finally {
            watchdog.interrupt()
            session.disconnect()
        }
    }

    // POSIX sh inside single quotes, so the login shell may be any (fish, zsh, ...).
    private fun remote(command: String, ports: String, previousPid: Int): String {
        val kill = if (previousPid > 0)
            "[ \"\$(cat /proc/$previousPid/comm 2>/dev/null)\" = mosh-server ] && kill $previousPid; " else ""
        val cmd = if (command.isBlank()) "" else " -- " + command.replace("'", "")
        val range = if (Regex("\\d{1,5}(:\\d{1,5})?").matches(ports)) " -p $ports" else ""
        return kill + "exec mosh-server new -c 256 -s -l LANG=C.UTF-8$range$cmd"
    }

    // Adds this phone's public key to the server's ~/.ssh/authorized_keys, as ssh-copy-id does, logging in once
    // with the password (never stored). The server's host key goes through the same trust on first use. Returns
    // whether mosh-server is installed there.
    fun installKey(host: String, port: Int, user: String, password: String, publicKey: String): Boolean {
        val key = publicKey.trim()
        require(Regex("[A-Za-z0-9-]+ [A-Za-z0-9+/=]+( [A-Za-z0-9._@-]+)?").matches(key)) { "not a public key line" }
        val ip = InetAddress.getByName(host).hostAddress!!
        val hosts = Hosts("$host:$port", vault.text(Vault.HOST_KEY))
        val jsch = JSch()
        jsch.hostKeyRepository = hosts
        val session = jsch.getSession(user, ip, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password")
        val secret = password.toByteArray()
        session.setPassword(secret)
        session.userInfo = Quiet
        try {
            session.connect(15000)
        } catch (e: JSchException) {
            hosts.unknown?.let { throw UnknownHost(it) }
            throw e
        } finally {
            secret.fill(0)
        }
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("sh -c 'umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
                "(grep -qxF \"$key\" ~/.ssh/authorized_keys || printf \"%s\\n\" \"$key\" >> ~/.ssh/authorized_keys) && " +
                "echo KEY_OK; command -v mosh-server >/dev/null && echo MOSH_OK || echo MOSH_MISSING'")
            val reader = channel.inputStream.bufferedReader()
            channel.connect(15000)
            val out = reader.readText()
            channel.disconnect()
            if (!out.contains("KEY_OK")) throw IllegalStateException("the server did not take the key: " + out.take(300))
            return out.contains("MOSH_OK")
        } finally {
            session.disconnect()
        }
    }

    // Trust on first use: the fingerprint is shown and stored once the user confirms it (UnknownHost).
    // Vault.HOST_KEY holds a "host:port fingerprint" line per trusted server (remember).
    private class Hosts(val name: String, stored: String?) : HostKeyRepository {
        private val mine = stored?.lines()?.firstOrNull { it.startsWith("$name ") }
        var unknown: String? = null

        override fun check(host: String?, key: ByteArray): Int {
            val fingerprint = fingerprint(key)
            if (mine == "$name $fingerprint") return HostKeyRepository.OK
            if (mine != null) return HostKeyRepository.CHANGED
            unknown = fingerprint
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "scouter"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private object Quiet : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
    }

    companion object {
        // A connection error in plain words, with where it was going (the cards and the log say it).
        fun plain(e: Throwable?, host: String, port: Int): String {
            val m = e?.message ?: return "failed"
            val where = "$host:$port"
            return when {
                m.contains("socket is not established", true) || m.contains("timed out", true) || m.contains("timeout", true) ->
                    "can't reach $where (is it on, on this Wi-Fi, and the address right?)"
                m.contains("refused", true) -> "$where refused: its SSH server is not running"
                e is java.net.UnknownHostException || m.contains("UnknownHost", true) -> "no such host: $host"
                m.contains("Auth", true) -> "$where did not accept Vox's key"
                m.contains("unreachable", true) -> "no route to $where (other network?)"
                else -> m
            }
        }

        // The connections runShared keeps, one per server ("user@host:port"), and a lock for each.
        private val shared = HashMap<String, com.jcraft.jsch.Session>()
        private val locks = HashMap<String, Any>()

        fun dropShared() {
            val all = synchronized(Bootstrap) { shared.values.toList().also { shared.clear() } }
            for (s in all) runCatching { s.disconnect() }
        }

        // A new key made on this phone (ECDSA P-256: JSch cannot write Ed25519 private keys): the private key goes
        // into the vault, the public key line is returned for the server.
        fun newKey(vault: Vault): String {
            val pair = com.jcraft.jsch.KeyPair.genKeyPair(JSch(), com.jcraft.jsch.KeyPair.ECDSA, 256)
            val private = java.io.ByteArrayOutputStream()
            val public = java.io.ByteArrayOutputStream()
            pair.writePrivateKey(private)
            pair.writePublicKey(public, "scouter-ssh-" + android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), ""))
            pair.dispose()
            vault.put(Vault.SSH_KEY, private.toByteArray())
            vault.delete(Vault.SSH_PASSPHRASE)
            val line = public.toString(Charsets.UTF_8.name()).trim()
            vault.putText(Vault.SSH_PUBLIC, line)
            return line
        }

        // The public key line of the key in the vault (an imported one too), or null without a key.
        fun publicKey(vault: Vault): String? {
            val private = vault.get(Vault.SSH_KEY) ?: return null
            val pair = com.jcraft.jsch.KeyPair.load(JSch(), private, null)
            try {
                if (pair.isEncrypted && !pair.decrypt(vault.get(Vault.SSH_PASSPHRASE)))
                    throw IllegalStateException("the key's passphrase does not open it")
                val out = java.io.ByteArrayOutputStream()
                pair.writePublicKey(out, "scouter-ssh")
                return out.toString(Charsets.UTF_8.name()).trim()
            } finally {
                pair.dispose()
            }
        }

        // A server's host key trusted from now on ("host:port"), beside the other servers' (Vault.HOST_KEY).
        fun remember(vault: Vault, name: String, fingerprint: String) {
            val kept = (vault.text(Vault.HOST_KEY) ?: "").lines().filter { it.isNotBlank() && !it.startsWith("$name ") }
            vault.putText(Vault.HOST_KEY, (kept + "$name $fingerprint").joinToString("\n"))
        }

        fun forget(vault: Vault, name: String) {
            val kept = (vault.text(Vault.HOST_KEY) ?: "").lines().filter { it.isNotBlank() && !it.startsWith("$name ") }
            if (kept.isEmpty()) vault.delete(Vault.HOST_KEY) else vault.putText(Vault.HOST_KEY, kept.joinToString("\n"))
        }

        // The trusted fingerprint of "host:port", or null.
        fun trusted(vault: Vault, name: String): String? =
            (vault.text(Vault.HOST_KEY) ?: "").lines().firstOrNull { it.startsWith("$name ") }?.substringAfter(" ")

        // SHA256:... of a public key line, as ssh-keygen -l shows it.
        fun keyFingerprint(line: String): String? = line.split(" ").getOrNull(1)
            ?.let { runCatching { fingerprint(Base64.getDecoder().decode(it)) }.getOrNull() }

        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

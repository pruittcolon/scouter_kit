package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair

// Settings, in cards: access, the glasses (pairing, brightness, alignment, the looking-up angle), the TouchBar's
// gestures, the phone's screen, the server in use and the phone's key (presets for Claude in this phone's
// Ubuntu), Termux, keyboard and keyer. The terminal's and the screen's everyday controls are on the main
// screen's tabs. Switches and sliders apply at once; text fields when the screen is left.
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val fields = mutableListOf<Pair<EditText, (String) -> Unit>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val page = page()
        page.addView(header("Settings", "Make the glasses yours", "Done") { finish() })

        val access = card("Access")
        permissions = text("", 13.5f, Ui.MUTED)
        access.addView(permissions)
        access.addView(button("Allow Bluetooth and notifications") { requestPermissions(ScouterService.PERMISSIONS.toTypedArray(), ASK) })
        access.addView(button("Let it run in the background (battery: unrestricted)") { unrestricted() })
        page.addView(access)

        val glasses = card("Glasses")
        glassesState = text("", 13.5f, Ui.MUTED)
        glasses.addView(glassesState)
        glasses.addView(button("Pair glasses (in the open case)", PRIMARY) { startActivity(Intent(this, PairingActivity::class.java)) })
        glasses.addView(button("Use the glasses Android already paired") { useBonded() })
        connect = toggle("Connect to the glasses", "Off: the app leaves the arms alone", settings.glassesAllowed) { settings.glassesAllowed = it }
        glasses.addToggle(connect)
        glasses.rule()
        glasses.addView(slider("Brightness", 0, 42, settings.brightness, { "${it * 100 / 42}%" }) { v ->
            settings.brightness = v
            Thread { Native.brightness(v) }.start()
        })
        glasses.addView(slider("Show when looking up by", 0, 60, settings.angle, { "$it°" }) {
            settings.angle = it
            restart()
        })
        val grid = text("", 12.5f, Ui.FAINT)
        fun showGrid(dx: Int, dy: Int) {
            val (cols, rows) = Settings.grid(dx, dy, settings.termTop, settings.termBottom)
            grid.text = "Terminal size: $cols × $rows characters"
        }
        glasses.addView(slider("Eye overlap (dx)", 0, 320, settings.dx, { "$it px" }, { showGrid(it, settings.dy) }) {
            settings.dx = it
            restart()
        })
        glasses.addView(slider("Vertical alignment (dy)", -64, 64, settings.dy, { "$it px" }, { showGrid(settings.dx, it) }) {
            settings.dy = it
            restart()
        })
        showGrid(settings.dx, settings.dy)
        glasses.addView(grid)
        if (BuildConfig.DEBUG) glasses.addToggle(toggle("Debug: show with the glasses folded", null, settings.ignoreWorn) {
            settings.ignoreWorn = it
        })
        page.addView(glasses)

        val touch = card("TouchBar", "Hold means a press of half a second. Gestures work while the glasses show " +
            "something. Next and previous go through Home's sources.")
        for (arm in intArrayOf(1, 0)) for ((gesture, name) in Settings.GESTURES) {
            val side = if (arm == 1) "Right" else "Left"
            touch.addView(choiceRow("$side $name", Settings.ACTIONS[settings.gestureAction(arm, gesture)] ?: "") { shown ->
                val keys = Settings.ACTIONS.keys.toList()
                dialog()
                    .setTitle("$side $name")
                    .setSingleChoiceItems(Settings.ACTIONS.values.toTypedArray(), keys.indexOf(settings.gestureAction(arm, gesture))) { d, which ->
                        settings.setGestureAction(arm, gesture, keys[which])
                        shown.text = Settings.ACTIONS[keys[which]]
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
        page.addView(touch)

        val phone = card("Phone screen", "Its look, place and speed are on the Screen tab; these are the rest.")
        phone.addToggle(toggle("Dark in the charging case", "A lit panel slows charging", settings.screenHideCase) {
            settings.screenHideCase = it
        })
        phone.addToggle(toggle("Dark when folded", "Uses the arms' wear guess, which can be wrong", settings.screenHideFolded) {
            settings.screenHideFolded = it
        })
        phone.rule()
        phone.addView(text("Some apps, Termux among them, are blank in screen sharing: Android 15+ hides apps with " +
            "password-style input. Developer options, \"Disable screen share protections\" shows them, along with " +
            "notifications and one-time codes, in every screen share.", 12.5f, Ui.FAINT))
        phone.addView(button("Open Developer options") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
        page.addView(phone)

        val server = card("Server in use", "Editing ${settings.active()?.name ?: "the server"}: SSH, then mosh. Add " +
            "servers and switch on the main screen (Terminal tab, Home).")
        server.addView(text("Termux on this phone: run examples/ssh/termux/scouter.sh in Termux once, then tap its preset.",
            12.5f, Ui.FAINT))
        server.addView(chipRow(
            chip("Termux on this phone", false) { preset(Termux.HOST, Termux.PORT.toString(), Termux.USER, "tmux new -A -s main") },
            chip("Claude in Ubuntu", false) { preset(Termux.HOST, Termux.PORT.toString(), "root", "bash -ic claude") },
            chip("Ubuntu shell", false) { preset(Termux.HOST, Termux.PORT.toString(), "root", "") },
            chip("tmux main", false) { commandField.setText("tmux new -A -s main") },
        ))
        hostField = editable("Host", settings.host, "an address, or a Tailscale IP") { settings.host = it.trim() }
        portField = editable("Port", settings.port.toString(), "22", numeric = true) { s ->
            s.toIntOrNull()?.takeIf { it in 1..65535 }?.let { settings.port = it }
        }
        userField = editable("User", settings.user, "") { settings.user = it.trim() }
        commandField = editable("Command", settings.command, "empty: the login shell") { settings.command = it }
        for (f in listOf(hostField, portField, userField, commandField)) server.addField(f)
        server.addField(editable("mosh UDP ports", settings.moshPorts, "e.g. 60000:60009 (empty: mosh's default)") {
            if (it.isBlank() || Regex("\\d{1,5}(:\\d{1,5})?").matches(it.trim())) settings.moshPorts = it.trim()
        })
        server.rule()
        keyState = text("", 13f, Ui.MUTED)
        server.addView(keyState)
        server.addView(button("Create a key on this phone") {
            if (Vault(this).has(Vault.SSH_KEY)) {
                dialog()
                    .setTitle("Replace the key?")
                    .setMessage("Servers that have the old key will need the new one installed.")
                    .setPositiveButton("Replace") { _, _ -> createKey() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else createKey()
        })
        serverPassword = field("Server password (used once, never saved)", "", "password", secret = true)
        server.addField(serverPassword)
        server.addView(button("Install the key on the server") {
            install(hostField.text.toString().trim(), portField.text.toString().toIntOrNull() ?: settings.port,
                userField.text.toString().trim())
        })
        server.addView(button("Copy the public key") {
            val line = Vault(this).text(Vault.SSH_PUBLIC) ?: Vault(this).let { v -> runCatching { Bootstrap.publicKey(v) }.getOrNull() }
            if (line == null) keyState.text = "No key yet: Create a key on this phone first."
            else {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Vox public key", line))
                keyState.text = "Public key copied: paste it into Termux (scouter key)."
            }
        })
        server.addView(button("Share the public key") {
            val line = Vault(this).text(Vault.SSH_PUBLIC)
            if (line == null) keyState.text = "No key made on this phone yet."
            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, line), "Public key"))
        })
        server.addView(button("Import a key file (OpenSSH)") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        })
        pasted = field("Or paste a private key", "", "-----BEGIN … PRIVATE KEY-----", lines = 4)
        server.addField(pasted)
        passphrase = field("Its passphrase (if it has one)", "", "", secret = true)
        server.addField(passphrase)
        server.addView(button("Save the pasted key") { save(pasted.text.toString().toByteArray()) })
        server.addView(button("Forget this server's host key") {
            Bootstrap.forget(Vault(this), "${settings.host}:${settings.port}")
            showKey()
        })
        page.addView(server)

        val termux = card("Termux")
        termuxState = text("", 13f, Ui.MUTED)
        termux.addView(termuxState)
        if (Termux.hasRunCommand(this)) {
            termux.addView(button("Allow running commands in Termux") { requestPermissions(arrayOf(Termux.PERMISSION), ASK) })
            termux.addView(button("Set up Termux for the glasses") { setUpTermux() })
        }
        page.addView(termux)

        val keys = card("Keyboard and keyer")
        keys.addToggle(toggle("Use a keyboard paired with this phone", "The terminal runs while this app is open", settings.keyboard) {
            settings.keyboard = it
        })
        val keyer = editable("keyer address (bonded; empty: none)", settings.keyer, "") { settings.keyer = it.trim().uppercase() }
        keys.addField(keyer)
        keys.addView(button("Use the bonded keyer") {
            bondedKeyer()?.let { keyer.setText(it) } ?: run { keyer.error = "no bonded keyer: pair it in Android's Bluetooth settings" }
        })
        page.addView(keys)

        page.addView(text("Vox ${BuildConfig.VERSION_NAME} · ${Native.version()}", 12f, Ui.FAINT).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
        showKey()
    }

    // A text field that saves when the screen is left.
    private fun editable(label: String, value: String, hint: String, numeric: Boolean = false, set: (String) -> Unit): EditText {
        val e = field(label, value, hint, numeric)
        fields += e to set
        return e
    }

    private fun preset(host: String, port: String, user: String, command: String) {
        hostField.setText(host)
        portField.setText(port)
        userField.setText(user)
        commandField.setText(command)
    }

    // Glasses settings changed: a running session picks them up (the glasses connect again if needed).
    private fun restart() {
        if (ScouterService.running) ScouterService.send(this)
    }

    private fun useBonded() {
        val arms = bondedArms()
        if (arms == null) glassesState.text = "No paired G1v0_L and G1v0_R: use \"Pair glasses\" with them in the open case."
        else {
            settings.left = arms.first
            settings.right = arms.second
            settings.glassesAllowed = true
            connect.isChecked = true
            showGlasses()
        }
    }

    private fun unrestricted() {
        @android.annotation.SuppressLint("BatteryLife")
        val ask = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(ask) }.onFailure {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showGlasses() {
        glassesState.text = if (settings.left.isEmpty() || settings.right.isEmpty()) "Not paired yet." else
            "Paired: left ${settings.left}, right ${settings.right}\nNow: ${Session.glasses}" +
                (if (Session.glasses == "ready") " (L ${if (Session.left) "up" else "down"}, R ${if (Session.right) "up" else "down"})" else "")
    }

    override fun onResume() {
        super.onResume()
        Session.listeners += listener
        showTermux()
        showPermissions()
        if (!asked && ScouterService.missing(this).isNotEmpty()) {
            asked = true
            requestPermissions(ScouterService.PERMISSIONS.toTypedArray(), ASK)
        }
        showGlasses()
        connect.isChecked = settings.glassesAllowed
        connect.isEnabled = settings.left.isNotEmpty() && settings.right.isNotEmpty()
    }

    override fun onPause() {
        Session.listeners -= listener
        for ((edit, set) in fields) set(edit.text.toString())
        settings.syncActive() // the saved server in use takes the fields
        super.onPause()
    }

    private val listener: () -> Unit = {
        showTermux()
        showGlasses()
    }

    private fun showTermux() {
        termuxState.text = when {
            !Termux.installed(this) -> "Termux is not installed"
            !Termux.hasRunCommand(this) -> "This Termux (from Google Play) cannot take commands from other apps: run " +
                "scouter.sh in Termux yourself (bash scouter.sh), then tap the \"Termux on this phone\" preset."
            !Termux.permitted(this) -> "Needed: \"Run commands in Termux\" (the button below). If Android no longer asks: " +
                "Android's Settings, Apps, Vox, Permissions, Additional permissions."
            Session.termux.isNotEmpty() -> "Termux: ${Session.termux}"
            settings.termux -> "Termux: set up (the server below is 127.0.0.1:8022)"
            else -> "Termux: not set up yet"
        }
    }

    // Termux as the server: this phone's key (made now if there is none; an imported key is used as it is),
    // the server's fields, then Termux's setup (TermuxResult reports).
    private fun setUpTermux() {
        Termux.problem(this)?.let {
            termuxState.text = it
            if (Termux.installed(this) && !Termux.permitted(this)) requestPermissions(arrayOf(Termux.PERMISSION), ASK)
            return
        }
        termuxState.text = "setting up Termux…"
        Thread {
            val vault = Vault(this)
            val line = runCatching { vault.text(Vault.SSH_PUBLIC) ?: Bootstrap.publicKey(vault) ?: Bootstrap.newKey(vault) }
            runOnUiThread {
                val key = line.getOrElse {
                    termuxState.text = "no key: ${it.message}"
                    return@runOnUiThread
                }
                hostField.setText(Termux.HOST)
                portField.setText(Termux.PORT.toString())
                userField.setText(Termux.USER)
                if (commandField.text.isBlank()) commandField.setText(Termux.CLAUDE)
                settings.host = Termux.HOST
                settings.port = Termux.PORT
                settings.user = Termux.USER
                settings.command = commandField.text.toString()
                showKey()
                runCatching { Termux.setUp(this, key) }.getOrElse { it.message }?.let { termuxState.text = "not set up: $it" }
            }
        }.start()
    }

    // Without Bluetooth the app neither links nor runs its service (Android 14+ requires it for a connectedDevice
    // service); without notifications its state does not show.
    // Without Bluetooth the app neither links nor runs its service (Android 14+ requires it for a connectedDevice
    // service); without notifications its state does not show.
    private fun showPermissions() {
        val missing = ScouterService.missing(this)
        permissions.text = if (missing.isEmpty()) "Bluetooth and notifications: allowed." else
            "Needed: Bluetooth (the glasses, keyer and the app's service) and notifications (its state). If Android no " +
                "longer asks: Android's Settings, Apps, Vox, Permissions."
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == ASK) {
            showPermissions()
            showTermux()
        }
    }

    private var asked = false
    private lateinit var termuxState: TextView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var commandField: EditText
    private lateinit var permissions: TextView
    private lateinit var glassesState: TextView
    private lateinit var connect: Switch
    private lateinit var keyState: TextView
    private lateinit var pasted: EditText
    private lateinit var passphrase: EditText
    private lateinit var serverPassword: EditText

    private fun showKey() {
        val vault = Vault(this)
        val host = Bootstrap.trusted(vault, "${settings.host}:${settings.port}")
        val made = vault.text(Vault.SSH_PUBLIC)?.let { Bootstrap.keyFingerprint(it) }
        keyState.text = (when {
            made != null -> "SSH key: made on this phone, $made"
            vault.has(Vault.SSH_KEY) -> "SSH key: imported"
            else -> "SSH key: none (create one below)"
        }) + (if (host != null) "\nthis server's host key: $host" else "")
    }

    // A new key, in the vault; the private key never leaves the phone.
    private fun createKey() {
        keyState.text = "making a key…"
        Thread {
            val result = runCatching { Bootstrap.newKey(Vault(this)) }
            runOnUiThread {
                showKey()
                result.exceptionOrNull()?.let { keyState.text = "no key: ${it.message}" }
            }
        }.start()
    }

    // The phone's public key onto the server, with the password once (ssh-copy-id); the server's host key is
    // confirmed first, as for every connection.
    private fun install(host: String, port: Int, user: String) {
        val vault = Vault(this)
        val line = vault.text(Vault.SSH_PUBLIC) ?: return run { keyState.text = "create a key on this phone first (1.)" }
        val password = serverPassword.text.toString()
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) return run { keyState.text = "fill in host, user and the password" }
        settings.host = host
        settings.port = port
        settings.user = user
        keyState.text = "installing the key on $user@$host…"
        Thread {
            val message = try {
                if (Bootstrap(vault).installKey(host, port, user, password, line))
                    "key installed on $user@$host: the app connects with it now"
                else "key installed on $user@$host, but mosh-server is missing there: install mosh on the server"
            } catch (e: Bootstrap.UnknownHost) {
                runOnUiThread { trust(host, port, e.fingerprint) { install(host, port, user) } }
                return@Thread
            } catch (e: Exception) {
                "not installed: ${e.message}"
            }
            runOnUiThread {
                serverPassword.setText("")
                showKey()
                keyState.text = message + "\n" + keyState.text
            }
        }.start()
    }

    private fun trust(host: String, port: Int, fingerprint: String, then: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Trust this server?")
            .setCancelable(false)
            .setMessage("$host:$port\n$fingerprint\n\nOn the server, ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub " +
                "(or _ed25519_) shows its fingerprint.")
            .setPositiveButton("Trust") { _, _ ->
                Bootstrap.remember(Vault(this), "$host:$port", fingerprint)
                then()
            }
            .setNegativeButton("Cancel") { _, _ -> keyState.text = "not installed: the server was not trusted" }
            .show()
    }

    // The key and its passphrase stay in the vault; the key is checked by loading it once (R6).
    private fun save(key: ByteArray) {
        val phrase = passphrase.text.toString().toByteArray()
        try {
            val pair = KeyPair.load(JSch(), key, null)
            if (pair.isEncrypted && !pair.decrypt(phrase)) throw IllegalArgumentException("wrong passphrase")
        } catch (e: Exception) {
            keyState.text = "not a usable key: ${e.message}"
            return
        }
        val vault = Vault(this)
        vault.put(Vault.SSH_KEY, key)
        vault.delete(Vault.SSH_PUBLIC) // an imported key: not one made here
        if (phrase.isNotEmpty()) vault.put(Vault.SSH_PASSPHRASE, phrase) else vault.delete(Vault.SSH_PASSPHRASE)
        pasted.setText("")
        passphrase.setText("")
        showKey()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data ?: return
        if (request == PICK && result == RESULT_OK) contentResolver.openInputStream(uri)?.use { save(it.readBytes()) }
    }

    // The bonded arms, by their names (one pair bonded); no scan, no connection.
    @android.annotation.SuppressLint("MissingPermission")
    private fun bondedArms(): Pair<String, String>? {
        if (!ScouterService.bluetooth(this)) return null
        val bonded = getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.bondedDevices
        val left = bonded.filter { it.name == "G1v0_L" }
        val right = bonded.filter { it.name == "G1v0_R" }
        return if (left.size == 1 && right.size == 1) left[0].address to right[0].address else null
    }

    // A bonded device named like keyer (𝖒𝖆𝖋.🎹 or maf.klaw): no scan, no connection.
    @android.annotation.SuppressLint("MissingPermission")
    private fun bondedKeyer(): String? = if (!ScouterService.bluetooth(this)) null else getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        .bondedDevices.firstOrNull { (it.name ?: "").let { n -> n.contains("𝖒𝖆𝖋") || n.startsWith("maf.") } }?.address

    companion object {
        const val PICK = 1
        const val ASK = 2
    }
}

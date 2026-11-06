package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair

// The app's only controls (the user's choice): the server and its key, mosh's ports, the glasses (pairing, the
// alignment that sets the eyes' overlap and the terminal's size, the looking-up angle) and keyer. The fields
// apply when the screen is left; the session follows by itself.
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val fields = mutableListOf<Pair<EditText, (String) -> Unit>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun heading(label: String) = list.addView(TextView(this).apply { text = label; textSize = 20f; setPadding(0, 32, 0, 8) })
        fun text(label: String, value: String, set: (String) -> Unit) = field(list, label, value, false, set)
        fun number(label: String, value: Int, range: IntRange, set: (Int) -> Unit) =
            field(list, "$label (${range.first}…${range.last})", value.toString(), true) { s ->
                s.toIntOrNull()?.takeIf { it in range }?.let(set)
            }
        fun button(label: String, action: () -> Unit) = list.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })

        permissions = TextView(this)
        list.addView(permissions)
        button("Allow Bluetooth and notifications") { requestPermissions(ScouterService.PERMISSIONS.toTypedArray(), ASK) }

        heading("Server")
        val host = text("Host (an address, or the server's Tailscale IP)", settings.host) { settings.host = it.trim() }
        val port = number("Port", settings.port, 1..65535) { settings.port = it }
        val user = text("User", settings.user) { settings.user = it.trim() }
        keyState = TextView(this)
        list.addView(keyState)
        button("1. Create a key on this phone") {
            if (Vault(this).has(Vault.SSH_KEY)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Replace the key?")
                    .setMessage("Servers that have the old key will need the new one installed.")
                    .setPositiveButton("Replace") { _, _ -> createKey() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else createKey()
        }
        serverPassword = EditText(this).apply {
            hint = "the server's password (used once, never saved)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        list.addView(serverPassword)
        button("2. Install the key on the server") {
            install(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: settings.port, user.text.toString().trim())
        }
        button("Share the public key (to add it to a server yourself)") {
            val line = Vault(this).text(Vault.SSH_PUBLIC)
            if (line == null) keyState.text = "no key made on this phone yet"
            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, line), "Public key"))
        }
        list.addView(TextView(this).apply { text = "Or bring a key of your own:"; setPadding(0, 24, 0, 0) })
        button("Import SSH key file (OpenSSH)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        }
        pasted = EditText(this).apply { hint = "or paste an OpenSSH private key"; minLines = 3 }
        list.addView(pasted)
        passphrase = EditText(this).apply {
            hint = "passphrase (if the key has one)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        list.addView(passphrase)
        button("Save pasted key") { save(pasted.text.toString().toByteArray()) }
        button("Forget the server's host key") { Vault(this).delete(Vault.HOST_KEY); showKey() }
        text("Command (empty: login shell; tmux new -A -s main keeps the work across sessions)", settings.command) {
            settings.command = it
        }
        text("mosh UDP ports, e.g. 60000:60009 (empty: mosh-server's default)", settings.moshPorts) {
            if (it.isBlank() || Regex("\\d{1,5}(:\\d{1,5})?").matches(it.trim())) settings.moshPorts = it.trim()
        }

        heading("Glasses")
        glassesState = TextView(this)
        list.addView(glassesState)
        connect = android.widget.CheckBox(this).apply {
            text = "Connect to the glasses while keyer is on (or, in keyboard mode, while the app is open)"
            setOnCheckedChangeListener { _, on -> settings.glassesAllowed = on }
        }
        list.addView(connect)
        button("Pair glasses (in the open case)") { startActivity(Intent(this, PairingActivity::class.java)) }
        button("Use the bonded glasses") {
            val arms = bondedArms()
            if (arms == null) glassesState.text = "no bonded G1v0_L and G1v0_R: pair them in the open case"
            else {
                settings.left = arms.first
                settings.right = arms.second
                settings.glassesAllowed = true
                onResume()
            }
        }
        val grid = TextView(this)
        val dx = number("Horizontal alignment dx: the eyes' overlap", settings.dx, 0..320) { settings.dx = it }
        val dy = number("Vertical alignment dy", settings.dy, -64..64) { settings.dy = it }
        list.addView(grid)
        val showGrid = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val (cols, rows) = Settings.grid(dx.text.toString().toIntOrNull() ?: settings.dx, dy.text.toString().toIntOrNull() ?: settings.dy)
                grid.text = "terminal: $cols x $rows characters"
            }
        }
        dx.addTextChangedListener(showGrid)
        dy.addTextChangedListener(showGrid)
        showGrid.afterTextChanged(null)
        number("Show when looking up by (degrees)", settings.angle, 0..60) { settings.angle = it }
        number("Brightness", settings.brightness, 0..42) { settings.brightness = it }
        if (BuildConfig.DEBUG) list.addView(android.widget.CheckBox(this).apply {
            text = "Debug: show the terminal with the glasses folded"
            isChecked = settings.ignoreWorn
            setOnCheckedChangeListener { _, on -> settings.ignoreWorn = on }
        })

        heading("Keyboard")
        list.addView(android.widget.CheckBox(this).apply {
            text = "Use a keyboard paired with this phone instead of keyer: the terminal runs while this app is " +
                "open (its screen stays on) and shows on the glasses whatever the head's angle"
            isChecked = settings.keyboard
            setOnCheckedChangeListener { _, on -> settings.keyboard = on }
        })

        heading("keyer")
        val keyer = field(list, "keyer address (bonded; empty: none)", settings.keyer, false) { settings.keyer = it.trim().uppercase() }
        button("Use the bonded keyer") {
            bondedKeyer()?.let { keyer.setText(it) } ?: run { keyer.error = "no bonded keyer: pair it in Android's Bluetooth settings" }
        }
        showKey()
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        showPermissions()
        if (!asked && ScouterService.missing(this).isNotEmpty()) {
            asked = true
            requestPermissions(ScouterService.PERMISSIONS.toTypedArray(), ASK)
        }
        glassesState.text = "left ${settings.left.ifEmpty { "-" }}, right ${settings.right.ifEmpty { "-" }}: ${Session.glasses}"
        connect.isChecked = settings.glassesAllowed
        connect.isEnabled = settings.left.isNotEmpty() && settings.right.isNotEmpty()
    }

    override fun onPause() {
        for ((edit, set) in fields) set(edit.text.toString())
        super.onPause()
    }

    // Without Bluetooth the app neither links nor runs its service (Android 14+ requires it for a connectedDevice
    // service); without notifications its state does not show.
    private fun showPermissions() {
        val missing = ScouterService.missing(this)
        permissions.text = if (missing.isEmpty()) "Bluetooth and notifications: allowed" else
            "Needed: Bluetooth (the glasses, keyer and the app's service) and notifications (its state). If Android no " +
                "longer asks: Android's Settings, Apps, SSH Scouter, Permissions."
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == ASK) showPermissions()
    }

    private var asked = false
    private lateinit var permissions: TextView
    private lateinit var glassesState: TextView
    private lateinit var connect: android.widget.CheckBox
    private lateinit var keyState: TextView
    private lateinit var pasted: EditText
    private lateinit var passphrase: EditText
    private lateinit var serverPassword: EditText

    private fun showKey() {
        val vault = Vault(this)
        val host = vault.text(Vault.HOST_KEY)
        val made = vault.text(Vault.SSH_PUBLIC)?.let { Bootstrap.keyFingerprint(it) }
        keyState.text = (when {
            made != null -> "SSH key: made on this phone, $made"
            vault.has(Vault.SSH_KEY) -> "SSH key: imported"
            else -> "SSH key: none (create one below)"
        }) + (if (host != null) "\nserver's host key: $host" else "")
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
                Vault(this).putText(Vault.HOST_KEY, "$host:$port $fingerprint")
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

    private fun field(list: LinearLayout, label: String, value: String, numeric: Boolean, set: (String) -> Unit): EditText {
        list.addView(TextView(this).apply { text = label })
        val edit = EditText(this).apply {
            setText(value)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        list.addView(edit)
        fields += edit to set
        return edit
    }

    companion object {
        const val PICK = 1
        const val ASK = 2
    }
}

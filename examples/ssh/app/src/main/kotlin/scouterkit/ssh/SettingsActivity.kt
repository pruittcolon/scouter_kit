package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair

// Settings, in sections that fold: the glasses (pairing, the looking-up angle, alignment), the TouchBar, the
// keyboard and trackpad, the phone's SSH key, shortcuts, Termux, access and troubleshooting. Servers are edited
// on the Terminal tab; the everyday switches are on the Glasses tab. Switches and sliders apply at once; text
// fields when the screen is left.
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val fields = mutableListOf<Pair<EditText, (String) -> Unit>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val page = page()
        page.addView(header("Settings", "Make the glasses yours", "Done") { finish() })

        val glasses = page.section("Glasses", "set_glasses", "glasses", open = true)
        glassesState = text("", 13.5f, Ui.MUTED)
        glasses.addView(glassesState)
        glasses.addView(chipRow(
            chip("Pair glasses", true) { startActivity(Intent(this, PairingActivity::class.java)) },
            chip("Use the paired ones", false) { useBonded() },
        ))
        connect = toggle("Connect to the glasses", "Off: the app leaves the arms alone", settings.glassesAllowed) { settings.glassesAllowed = it }
        glasses.addToggle(connect)
        glasses.addToggle(toggle("Start by itself", "The glasses connect when the app opens, after the phone starts and " +
            "after updates; out of the case they come back by themselves.", settings.autoStart) {
            settings.autoStart = it
            if (it && settings.startsByItself()) ScouterService.send(this)
        })
        glasses.addView(slider("Show when looking up by", 0, 60, settings.angle, { "$it°" }) {
            settings.angle = it
            restart()
        })
        val grid = text("", 12.5f, Ui.FAINT)
        fun showGrid(dx: Int, dy: Int) {
            val (cols, rows) = Settings.grid(dx, dy, settings.termTop, settings.termBottom)
            grid.text = "The terminal: $cols × $rows characters"
        }
        glasses.addView(slider("Depth (eye overlap)", 0, 320, settings.dx, { "$it px" }, { showGrid(it, settings.dy) }) {
            settings.dx = it
            restart()
        })
        glasses.addView(slider("Vertical alignment", -64, 64, settings.dy, { "$it px" }, { showGrid(settings.dx, it) }) {
            settings.dy = it
            restart()
        })
        showGrid(settings.dx, settings.dy)
        glasses.addView(grid)
        if (BuildConfig.DEBUG) glasses.addToggle(toggle("Debug: show with the glasses folded", null, settings.ignoreWorn) {
            settings.ignoreWorn = it
        })

        val touch = page.section("TouchBar", "set_touch", "touch",
            hint = "Hold is a press of half a second. Next and previous go through the sources.")
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

        val hot = page.section("Keyboard and trackpad", "set_keys", "keyboard",
            hint = "A keyboard paired with the phone: Alt+arrows walk the sources, Alt+1…9 picks one, Alt+0 heads-up mode.")
        hot.addToggle(toggle("Alt shortcuts", "Off: Alt with these keys reaches the app in front", settings.hotkeys) {
            settings.hotkeys = it
        })
        hotState = text("", 13f, Ui.MUTED)
        hot.addView(hotState)
        hot.addView(button("Turn them on in every app (Accessibility)") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        hot.addView(text("Accessibility, Installed apps, Vox keyboard shortcuts: on. It filters keys and the trackpad and " +
            "notes which app is in front (its name, for the look per app); it reads nothing on the screen. If Android " +
            "says the setting is restricted: Apps, Vox, ⋮, Allow restricted settings.", 12f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        hot.addToggle(toggle("Type on the glasses", "While the glasses show the terminal, the keyboard types into it " +
            "whatever app is in front (Alt+K: to the phone and back)", settings.keysToGlasses) {
            settings.keysToGlasses = it
        })
        hot.addToggle(toggle("Trackpad on the glasses", "On the terminal and the phone's screen, a trackpad or mouse " +
            "moves the glasses' own arrow (tap: click, two fingers: right click and scroll)", settings.trackpad) {
            settings.trackpad = it
            restart()
        })
        hot.addToggle(toggle("Pocket mode", "While the phone's screen is on the glasses, the phone stays awake (dimmed) " +
            "so it can stay in your pocket: the trackpad taps, the keyboard types, Alt+Backspace / H / R / N are Back, " +
            "Home, Recents, Notifications", settings.pocket) {
            settings.pocket = it
            restart()
        })

        // How to use it all from the keyboard: every hotkey (the same list as Alt+A on the glasses).
        val keys = page.section("Hotkeys", "set_hotkeys", "keyboard",
            hint = "With the keyboard paired, Alt+A shows these on the glasses over whatever is there.")
        keys.addView(text("Quick start: put the glasses on, put the phone away, Alt+arrows to pick a screen. On the " +
            "phone's screen the trackpad is a mouse (tap: click, two fingers: right click and scroll) and the keys " +
            "type into the app. Alt+S changes what you see, Alt+0 is heads-up mode (look up to see it), Alt+L the display off and on.", 13f, Ui.MUTED)
            .apply { setPadding(0, 0, 0, dp(6)) })
        for ((group, items) in Panels.GROUPS) {
            keys.addView(subhead(group).apply { setPadding(0, dp(10), 0, dp(4)) })
            for ((k, what) in items) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                row.addView(text(k, 14f, Ui.TEXT, Ui.MONO), android.widget.LinearLayout.LayoutParams(dp(112),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(text(what, 14f, Ui.MUTED))
                keys.addView(row)
            }
        }

        val speed = page.section("Cursor speed", "set_cursor", "pointer",
            hint = "How the trackpad moves the glasses' arrow and scrolls the terminal.")
        speed.addView(slider("Speed", 1, 10, settings.trackpadSpeed, { "$it" }) { v -> settings.trackpadSpeed = v })
        speed.addView(slider("Acceleration", 0, 10, settings.trackpadAccel, { if (it == 0) "none" else "$it" }) { v ->
            settings.trackpadAccel = v
        })
        speed.addView(slider("Scroll speed", 1, 10, settings.scrollSpeed, { "$it line" + if (it == 1) "" else "s" }) { v ->
            settings.scrollSpeed = v
        })
        speed.addToggle(toggle("Natural scrolling", "The text follows your fingers, as on a phone", settings.scrollNatural) {
            settings.scrollNatural = it
        })
        hot.addToggle(toggle("Keyboard mode", "A paired keyboard types into the terminal while this app is open", settings.keyboard) {
            settings.keyboard = it
        })
        hot.addView(subhead("keyer"))
        val keyer = editable("keyer address (bonded; empty: none)", settings.keyer, "") { settings.keyer = it.trim().uppercase() }
        hot.addField(keyer)
        hot.addView(chipRow(chip("Use the bonded keyer", false) {
            bondedKeyer()?.let { keyer.setText(it) } ?: run { keyer.error = "no bonded keyer: pair it in Android's Bluetooth settings" }
        }))

        val tunes = page.section("Tunes", "set_tunes", "spark",
            hint = "The synthwave scene (Glasses tab, Tunes; Alt+U; \"tunes mode\") and the Tunes bar on the look-up screen.")
        tunes.addView(subhead("It follows"))
        tunes.addView(segmented(listOf(Tunes.BEAT to "The beat", Tunes.VOLUME to "The volume", Tunes.STEADY to "A steady tempo"),
            settings.tunesMode) { settings.tunesMode = it })
        tunes.addView(slider("Steady tempo", 60, 160, settings.tunesBpm, { "$it BPM" }) { settings.tunesBpm = it })
        tunesState = text("", 12.5f, Ui.FAINT)
        tunes.addView(tunesState)
        tunesButton = button("Allow the microphone (to follow the music)") {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), ASK)
        }
        tunes.addView(tunesButton)

        val key = page.section("SSH key", "set_key", "key", hint = "The phone's own key: the private half never leaves it.")
        keyState = text("", 13f, Ui.MUTED)
        key.addView(keyState)
        key.addView(chipRow(
            chip("Create a key", false) {
                if (Vault(this).has(Vault.SSH_KEY)) {
                    dialog()
                        .setTitle("Replace the key?")
                        .setMessage("Servers that have the old key will need the new one installed.")
                        .setPositiveButton("Replace") { _, _ -> createKey() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else createKey()
            },
            chip("Copy the public key", false) {
                val line = Vault(this).text(Vault.SSH_PUBLIC) ?: Vault(this).let { v -> runCatching { Bootstrap.publicKey(v) }.getOrNull() }
                if (line == null) keyState.text = "No key yet: create one first."
                else {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Vox public key", line))
                    keyState.text = "Public key copied: paste it into Termux (scouter key)."
                }
            },
            chip("Share", false) {
                val line = Vault(this).text(Vault.SSH_PUBLIC)
                if (line == null) keyState.text = "No key made on this phone yet."
                else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, line), "Public key"))
            },
        ))
        key.addView(subhead("Install it on ${settings.active()?.name ?: "the server in use"}"))
        serverPassword = field("Its password (used once, never saved)", "", "password", secret = true)
        key.addField(serverPassword)
        key.addView(button("Install the key") {
            val s = settings.active()
            if (s == null) keyState.text = "Add a server first (Terminal tab)."
            else install(s.host, s.port, s.user)
        })
        key.addView(subhead("Or use a key you have"))
        key.addView(chipRow(chip("Import a key file (OpenSSH)", false) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        }))
        pasted = field("Or paste a private key", "", "-----BEGIN … PRIVATE KEY-----", lines = 4)
        key.addField(pasted)
        passphrase = field("Its passphrase (if it has one)", "", "", secret = true)
        key.addField(passphrase)
        key.addView(button("Save the pasted key") { save(pasted.text.toString().toByteArray()) })

        val tv = page.section("TV remote", "set_tv", "screen",
            hint = "A Vizio SmartCast TV on this Wi-Fi. Alt+Y turns the keyboard into its remote; Alt+Y again, or five " +
                "idle minutes, gives the keyboard back.")
        tv.addView(text(if (Vizio.paired(settings)) "Paired with the TV at ${settings.tvHost}" else "Not paired yet",
            12.5f, Ui.FAINT))
        tv.addView(button(if (Vizio.paired(settings)) "Open the remote" else "Find and pair the TV") {
            startActivity(Intent(this, TvActivity::class.java))
        })
        val pins = page.section("Shortcuts", "set_shortcuts", "spark",
            hint = "On your home screen. Long-pressing the Vox icon shows them too; Quick Settings has a \"Next source\" tile.")
        val chips = settings.sources().map { (k, name) -> chip("+ $name", false) { pin(k, name) } } +
            chip("+ Next source", false) { pin(Shortcuts.NEXT, "Next source") } +
            chip("+ Display off / on", false) { pin(Shortcuts.DISPLAY, "Display off / on") }
        for (row in chips.chunked(2)) pins.addView(chipRow(*row.toTypedArray()))

        val termux = page.section("Termux", "set_termux", "terminal")
        termuxState = text("", 13f, Ui.MUTED)
        termux.addView(termuxState)
        if (Termux.hasRunCommand(this)) {
            termux.addView(button("Allow running commands in Termux") { requestPermissions(arrayOf(Termux.PERMISSION), ASK) })
            termux.addView(button("Set up Termux for the glasses") { setUpTermux() })
        }

        val access = page.section("Access", "set_access", "lock")
        permissions = text("", 13.5f, Ui.MUTED)
        access.addView(permissions)
        access.addView(button("Allow Bluetooth and notifications") { requestPermissions(ScouterService.PERMISSIONS.toTypedArray(), ASK) })
        access.addView(button("Let it run in the background") { unrestricted() })
        access.addView(button("Allow reading notifications (the dashboard)") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        val trouble = page.section("Troubleshooting", "set_trouble", "info")
        trouble.addView(text("Some apps, Termux among them, are blank in screen sharing: Android 15+ hides apps with " +
            "password-style input. Developer options, \"Disable screen share protections\" shows them (and notifications " +
            "and one-time codes) in every screen share.", 12.5f, Ui.FAINT))
        trouble.addView(button("Open Developer options") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
        trouble.addView(button("Charging case log (connect the case by USB-C)") {
            startActivity(Intent(this, CaseLogActivity::class.java))
        })
        // Vox's own log: to Claude (the phone's server, ~/vox-logs) or shared; updates from here.
        val logState = text("", 12.5f, Ui.FAINT)
        trouble.addView(chipRow(
            chip("Send the log to Claude", true) {
                logState.text = "Sending…"
                Thread {
                    val f = LogBook.capture(this, "sent from Settings")
                    val (saved, why) = LogBook.send(this, Vault(this), f)
                    runOnUiThread { logState.text = if (saved != null) "Sent: Claude can read it ($saved)." else "Not sent: $why" }
                }.start()
            },
            chip("Share the log", false) {
                Thread {
                    val t = LogBook.text(LogBook.capture(this, "shared from Settings"))
                    runOnUiThread {
                        runCatching {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, t), "Share Vox's log"))
                        }
                    }
                }.start()
            },
        ))
        trouble.addView(logState)
        val updateState = text("", 12.5f, Ui.FAINT)
        fun updated() = runOnUiThread { updateState.text = Updater.state }
        trouble.addView(chipRow(
            chip("Check for an update", true) { Updater.check(this) { updated() } },
            chip("Install it", false) {
                if (Updater.url == null) Updater.check(this) { updated(); if (Updater.url != null) Updater.install(this) { updated() } }
                else Updater.install(this) { updated() }
            },
        ))
        trouble.addView(updateState)
        diagnostics = text("", 11.5f, Ui.FAINT, Ui.MONO).apply { setPadding(0, dp(10), 0, 0) }
        trouble.addView(diagnostics)

        page.addView(text("Vox ${BuildConfig.VERSION_NAME} · ${Native.version()}", 12f, Ui.FAINT).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
        })
        showKey()
    }

    private fun pin(key: String, name: String) {
        if (!Shortcuts.pin(this, key, name)) keyState.text = "This home screen can't add shortcuts: long-press the Vox icon instead."
    }

    // A text field that saves when the screen is left.
    private fun editable(label: String, value: String, hint: String, numeric: Boolean = false, set: (String) -> Unit): EditText {
        val e = field(label, value, hint, numeric)
        fields += e to set
        return e
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
        showDiagnostics()
        val mic = Tunes.allowed(this)
        tunesState.text = if (mic) "Follows the music playing on the phone (Android's output mix; nothing is recorded or kept)."
            else "Without the microphone it keeps a steady tempo."
        tunesButton.visibility = if (mic) android.view.View.GONE else android.view.View.VISIBLE
        hotState.text = if (Hotkeys.everywhere(this)) "In every app: on" else "In every app: off (only while Vox is in front)"
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
        showDiagnostics()
    }

    // The session's raw state, for reports.
    private fun showDiagnostics() {
        val exempt = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        diagnostics.text = (if (exempt) "" else "battery optimised · ") +
            "glasses ${Session.glasses} (L ${Session.left} R ${Session.right}" + (if (Session.shown) ", shown" else "") +
            ") · screen ${Session.screen} (${Session.screenFrames} frames) · mosh ${Session.mosh} · keyer ${Session.keyer}" +
            (if (Session.termux.isNotEmpty()) " · Termux ${Session.termux}" else "")
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

    // Termux as the server: this phone's key (made now if there is none; an imported key is used as it is), a saved
    // server for it (127.0.0.1:8022), then Termux's setup (TermuxResult reports).
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
                val old = settings.servers().firstOrNull { it.host == Termux.HOST && it.port == Termux.PORT }
                val s = Settings.Server(old?.id ?: Settings.newServerId(), old?.name ?: "Phone", Termux.HOST, Termux.PORT,
                    Termux.USER, old?.command?.ifEmpty { null } ?: Termux.CLAUDE, old?.moshPorts ?: "")
                settings.putServer(s)
                settings.useServer(s.id)
                showKey()
                runCatching { Termux.setUp(this, key) }.getOrElse { it.message }?.let { termuxState.text = "not set up: $it" }
            }
        }.start()
    }

    // Without Bluetooth the app neither links nor runs its service (Android 14+ requires it for a connectedDevice
    // service); without notifications its state does not show.
    private fun showPermissions() {
        val missing = ScouterService.missing(this)
        permissions.text = if (missing.isEmpty()) "Bluetooth and notifications: allowed." else
            "Needed: Bluetooth (the glasses, keyer and the app's service) and notifications (its state). If Android no " +
                "longer asks: Android's Settings, Apps, Vox, Permissions." +
                (if (LookUp.allowed(this)) "" else "\nNotification access (the dashboard's notifications): not allowed yet.")
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
    private lateinit var diagnostics: TextView
    private lateinit var tunesState: TextView
    private lateinit var tunesButton: android.view.View
    private lateinit var permissions: TextView
    private lateinit var glassesState: TextView
    private lateinit var connect: Switch
    private lateinit var keyState: TextView
    private lateinit var pasted: EditText
    private lateinit var passphrase: EditText
    private lateinit var serverPassword: EditText
    private lateinit var hotState: TextView

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
        val line = vault.text(Vault.SSH_PUBLIC) ?: return run { keyState.text = "create a key on this phone first" }
        val password = serverPassword.text.toString()
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) return run { keyState.text = "type the server's password first" }
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

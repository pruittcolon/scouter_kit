package scouterkit.ssh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// R11: the user scans for the scouter service with the glasses in the open case (only there are the arms
// bondable), picks a pair by its pair ID, and the app stores both addresses and pairs (bonds) with each arm in
// turn: Android 17's own Bluetooth settings refuse these arms ("needs an app to pair"). The phone takes one of
// each arm's 3 bond slots, the oldest is replaced. Nothing here runs without the user's tap.
@SuppressLint("MissingPermission")
class PairingActivity : Activity() {
    // The device as the scan found it: it carries the address type, which a bond needs.
    private class Arm(val device: android.bluetooth.BluetoothDevice, val left: Boolean) {
        val address: String get() = device.address
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var state: TextView
    private val pairs = LinkedHashMap<String, MutableMap<Boolean, Arm>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        list.addView(TextView(this).apply {
            text = "Put the glasses in the charging case and open it: the arms pair only there. The phone takes " +
                "one of each arm's 3 bond slots (the oldest is replaced), and while it holds the arms, other " +
                "hosts cannot connect."
        })
        state = TextView(this)
        list.addView(state)
        list.addView(Button(this).apply { text = "Scan for 15 s"; setOnClickListener { scan() } })
        setContentView(ScrollView(this).apply { addView(list) })
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            !ScouterService.bluetooth(this)) {
            state.text = "allow Bluetooth first"
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        // BLE_ON (Bluetooth switched off, kept for other apps' scans) would scan: off counts as off.
        if (!ScouterService.bluetoothOn(this)) {
            state.text = "Bluetooth is off"
            return
        }
        val scanner = getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: return
        pairs.clear()
        state.text = "scanning"
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val record = result.scanRecord?.getManufacturerSpecificData(0xFFFF) ?: return
                if (record.size < 3 || record[0].toInt() != 1 || record[1].toInt() and 2 != 0) return // format 1, has a panel
                val id = String(record, 2, record.size - 2, Charsets.UTF_8)
                pairs.getOrPut(id) { mutableMapOf() }[record[1].toInt() and 1 != 0] =
                    Arm(result.device, record[1].toInt() and 1 != 0)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GlassesLink.SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        handler.postDelayed({
            scanner.stopScan(callback)
            showPairs()
        }, 15000)
    }

    private fun showPairs() {
        state.text = if (pairs.isEmpty()) "no glasses found: is the case open?" else "found:"
        for ((id, arms) in pairs) {
            val left = arms[true]
            val right = arms[false]
            list.addView(Button(this).apply {
                text = "Glasses $id: ${if (left != null) "left" else "no left"}, ${if (right != null) "right" else "no right"}"
                isEnabled = left != null && right != null
                setOnClickListener {
                    val settings = Settings(this@PairingActivity)
                    settings.left = left!!.address
                    settings.right = right!!.address
                    settings.glassesAllowed = true
                    isEnabled = false
                    bond(listOf(left, right))
                }
            })
        }
    }

    // Bonds the arms one after the other (left first), each while the case is open; Android may ask to confirm.
    // Done: back to Settings.
    private fun bond(arms: List<Arm>) {
        val next = arms.firstOrNull { it.device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED }
        if (next == null) {
            state.text = "both arms paired: the app connects to them now"
            ScouterService.send(this)
            handler.postDelayed({ finish() }, 1500)
            return
        }
        val side = if (next.left) "left" else "right"
        state.text = "pairing the $side arm… (if Android asks, tap Pair)"
        var started = false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != next.address) return
                when (i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, 0)) {
                    android.bluetooth.BluetoothDevice.BOND_BONDING -> started = true
                    android.bluetooth.BluetoothDevice.BOND_BONDED -> {
                        unregisterReceiver(this)
                        bond(arms)
                    }
                    android.bluetooth.BluetoothDevice.BOND_NONE -> if (started) {
                        unregisterReceiver(this)
                        state.text = "the $side arm did not pair: keep the glasses in the open case (on USB) and scan again"
                    }
                }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        if (next.device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDING && !next.device.createBond()) {
            unregisterReceiver(receiver)
            state.text = "Android refused to pair the $side arm: is Bluetooth on?"
        }
    }
}

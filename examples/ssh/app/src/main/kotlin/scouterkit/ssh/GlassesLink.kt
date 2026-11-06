package scouterkit.ssh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.UUID

// The two arms over Android's GATT (R10), for ScouterKit's AndroidTransport: connects, asks for MTU 247 and
// the 2M PHY (connection intervals follow the arms' LINK requests), subscribes to EVT, and writes CMD/CTRL without response, one write at a time per arm.
// connect/disconnect/hold/stop/write are called from the library's Bluetooth thread; the answers go back
// through Native.link*. It connects only to arms Android lists as bonded, and stops for good (no reconnect, no
// pairing) when an arm's bond goes or its encryption fails: Android would otherwise pair again by itself, and a
// pairing request can cost another host its bond slot on the arm (docs/fps-link.md §13.1).
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class GlassesLink(
    private val context: Context,
    private val addresses: Array<String>,
    private val onArm: () -> Unit,
    private val onLost: (String) -> Unit,
) {
    private class Arm(val index: Int) {
        var gatt: BluetoothGatt? = null
        var chars = arrayOfNulls<BluetoothGattCharacteristic>(2)
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        var writing = false
        var mtu = 23
        var up = false
        var wanted = false
        var writeAt = 0L      // when the write in flight went out (its callback may never come)
        var stalls = 0        // writes without a callback in a row
        var discoveryFails = 0 // service discoveries that failed in a row
        var setup: Runnable? = null // the setup watchdog while connected but not up
        var stallCheck: Runnable? = null // the write watchdog while a write is in flight
    }

    private val arms = arrayOf(Arm(0), Arm(1))
    private val thread = HandlerThread("glasses").apply { start() }
    private val handler = Handler(thread.looper)
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    @Volatile var lost: String? = null // why the link stopped for good

    private val bonds = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val arm = addresses.indexOfFirst { it.equals(device.address, ignoreCase = true) }
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (arm >= 0 && state != BluetoothDevice.BOND_BONDED) handler.post { giveUp("the ${NAMES[arm]} arm's bond is gone") }
        }
    }

    init {
        context.registerReceiver(bonds, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun giveUp(why: String) {
        if (lost != null) return
        lost = why
        Log.w(TAG, "glasses link stopped: $why")
        for (a in arms) close(a, false)
        onLost(why)
    }

    fun connect(arm: Int, unused: Int) {
        handler.post { open(arms[arm], false) }
    }

    fun disconnect(arm: Int, unused: Int) {
        handler.post { close(arms[arm], false) }
    }

    fun hold(arm: Int, ms: Int) {
        handler.post {
            val a = arms[arm]
            close(a, false)
            a.wanted = true
            handler.postDelayed({ if (a.wanted && a.gatt == null) open(a, false) }, ms.toLong())
        }
    }

    fun stop(unused: Int, unused2: Int) {
        handler.post { for (a in arms) close(a, false) }
    }

    // Bluetooth switched off: Android dropped both links without a callback. They go down here, so the library
    // stops writing to them (each failed write was retried every 5 ms) and stops waiting for them.
    fun bluetoothOff() {
        handler.post { for (a in arms) close(a, false) }
    }

    fun write(arm: Int, channel: Int, data: ByteArray): Boolean {
        val a = arms[arm]
        synchronized(a) {
            if (!a.up) return false
            a.queue.addLast(channel to data)
        }
        handler.post { pump(a) }
        return true
    }

    fun quit() {
        runCatching { context.unregisterReceiver(bonds) }
        handler.post {
            for (a in arms) close(a, false)
            thread.quitSafely()
        }
    }

    private fun open(a: Arm, background: Boolean) {
        a.wanted = true
        if (a.gatt != null || lost != null) return
        if (!adapter.isEnabled) return Native.linkProblem(Native.ERROR_BLUETOOTH, "Bluetooth is off")
        val device = adapter.getRemoteDevice(addresses[a.index])
        if (device.bondState != BluetoothDevice.BOND_BONDED) return giveUp("the ${NAMES[a.index]} arm is not bonded")
        a.gatt = device.connectGatt(context, background, callback(a), BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    private fun close(a: Arm, reconnect: Boolean) {
        a.wanted = reconnect
        a.setup?.let { handler.removeCallbacks(it) }
        a.setup = null
        a.stallCheck?.let { handler.removeCallbacks(it) }
        a.stallCheck = null
        runCatching { a.gatt?.disconnect() } // the arm learns at once (close alone leaves it to time out)
        runCatching { a.gatt?.close() }
        a.gatt = null
        down(a)
    }

    // A link that got stuck (a setup step or a write whose callback never came, a failed discovery): a fresh one,
    // the stack waiting for the arm's advertising (autoConnect).
    private fun reset(a: Arm, why: String) {
        if (a.gatt == null || lost != null) return
        Log.w(TAG, "arm ${a.index}: $why, connecting again")
        close(a, true)
        handler.postDelayed({ if (a.wanted && a.gatt == null) open(a, true) }, 500)
    }

    private fun down(a: Arm) {
        val was = synchronized(a) {
            val was = a.up
            a.up = false
            a.queue.clear()
            was
        }
        a.writing = false
        if (was) {
            Native.linkDown(a.index)
            armState(a.index, false)
        }
    }

    private fun armState(index: Int, up: Boolean) {
        if (index == 0) Session.left = up else Session.right = up
        onArm()
    }

    private fun pump(a: Arm) {
        val gatt = a.gatt ?: return
        if (a.writing) {
            // A write's callback that never came (it happens on some stacks): go on, and after three in a row a
            // fresh link.
            if (android.os.SystemClock.uptimeMillis() - a.writeAt < WRITE_STALL_MS) return
            a.writing = false
            if (++a.stalls >= 3) return reset(a, "writes without callbacks")
        }
        val (channel, data) = synchronized(a) { a.queue.removeFirstOrNull() } ?: return
        val c = a.chars[channel] ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = data
        a.writing = gatt.writeCharacteristic(c)
        if (a.writing) {
            a.writeAt = android.os.SystemClock.uptimeMillis()
            watchWrites(a)
        } else {
            synchronized(a) { a.queue.addFirst(channel to data) }
            handler.postDelayed({ pump(a) }, 5)
        }
    }

    // One check per arm while writes are in flight: pump() notices a write whose callback is overdue.
    private fun watchWrites(a: Arm) {
        if (a.stallCheck != null) return
        val r = Runnable {
            a.stallCheck = null
            pump(a)
            if (a.writing && a.gatt != null) watchWrites(a)
        }
        a.stallCheck = r
        handler.postDelayed(r, WRITE_STALL_MS + 50)
    }

    private fun callback(a: Arm) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            Log.i(TAG, "arm ${a.index}: state $state status $status")
            if (g != a.gatt) return
            if (state == BluetoothProfile.STATE_CONNECTED) {
                // Setup (MTU, services, the EVT subscription) must end within SETUP_MS, else a fresh link.
                val watch = Runnable { if (a.gatt === g && !a.up) reset(a, "setup did not finish") }
                a.setup?.let { handler.removeCallbacks(it) }
                a.setup = watch
                handler.postDelayed(watch, SETUP_MS)
                if (!g.requestMtu(247)) handler.postDelayed({ if (a.gatt === g && !a.up) g.requestMtu(247) }, 300)
                return
            }
            a.setup?.let { handler.removeCallbacks(it) }
            a.setup = null
            g.close()
            a.gatt = null
            down(a)
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) return giveUp("the ${NAMES[a.index]} arm's bond is gone")
            if (status in SECURITY) return giveUp("the ${NAMES[a.index]} arm refused encryption (status $status)")
            if (a.wanted) open(a, true) // autoConnect: the stack waits for the arm's advertising
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            a.mtu = mtu
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            if (!g.discoverServices()) handler.postDelayed({ if (a.gatt === g && !a.up) g.discoverServices() }, 300)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val evt = service?.getCharacteristic(EVT)
            if (service == null || evt == null) {
                // A failed or empty discovery is mostly the stack's (a stale cache, a busy link): a fresh link, twice,
                // before saying the firmware is wrong.
                if (status != BluetoothGatt.GATT_SUCCESS || ++a.discoveryFails < 3)
                    return reset(a, "service discovery failed (status $status)")
                Log.w(TAG, "arm ${a.index}: no scouter service")
                Native.linkProblem(Native.ERROR_FIRMWARE, "the arm does not run the scouter firmware")
                return
            }
            a.discoveryFails = 0
            a.chars = arrayOf(service.getCharacteristic(CMD), service.getCharacteristic(CTRL))
            g.setCharacteristicNotification(evt, true)
            val cccd = evt.getDescriptor(CCCD)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "arm ${a.index}: EVT subscription failed, status $status")
                if (status in SECURITY) giveUp("the ${NAMES[a.index]} arm refused encryption (status $status)")
                return
            }
            a.setup?.let { handler.removeCallbacks(it) }
            a.setup = null
            a.stalls = 0
            synchronized(a) { a.up = true }
            Native.linkFound(a.index, g.device.address)
            Native.linkUp(a.index, a.mtu)
            armState(a.index, true)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            a.writing = false
            a.stalls = 0
            pump(a)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == EVT) Native.linkData(a.index, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == EVT) Native.linkData(a.index, value)
        }
    }

    companion object {
        const val TAG = "scouter"
        val NAMES = arrayOf("left", "right")
        // HCI authentication failure (also GATT insufficient authentication), PIN or key missing, MIC failure,
        // GATT insufficient encryption, Android's GATT_AUTH_FAIL.
        val SECURITY = setOf(5, 6, 0x3D, 15, 137)
        const val SETUP_MS = 12000L // connected to subscribed, at most
        const val WRITE_STALL_MS = 3000L // a write's callback, at most
        val SERVICE: UUID = UUID.fromString("f3da4d59-277a-4fc9-a1ed-41c9c22d4400")
        val CMD: UUID = UUID.fromString("f3da4d59-277a-4fc9-a1ed-41c9c22d4401")
        val CTRL: UUID = UUID.fromString("f3da4d59-277a-4fc9-a1ed-41c9c22d4402")
        val EVT: UUID = UUID.fromString("f3da4d59-277a-4fc9-a1ed-41c9c22d4403")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

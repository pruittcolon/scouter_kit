package scouterkit.ssh

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import java.io.File
import java.io.FileWriter

// The charging case's own log. Its USB-C port (a CH34x USB-to-serial chip inside the case: the port Even's case
// updater uses) carries what its firmware prints, at 115200 baud: each arm's NFC charging state (WLC_STATE_...), the
// arm's battery report (Rx VRECT, Battery Level, charge flag, current), "card lost", "read rx info timeout", "Multiple
// Tags detected", the case's battery, lid and temperatures. With the case on the phone by a USB-C data cable this
// screen reads it live, sums each arm up and keeps a copy (Android/data/<package>/files/caselog.txt).
// It looks at the port every second, so plugging the case in (or out) while it is open just works, and it shows what
// Android sees on the port, so "nothing there" (a charge-only cable) and "a device, but no text" (wrong set-up) can
// be told apart.
class CaseLogActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var seen: TextView
    private lateinit var summary: TextView
    private lateinit var log: TextView
    @Volatile private var running = false
    private var port: CaseSerial.Port? = null
    private val shown = ArrayDeque<String>()
    private val stats = CaseStats()
    private var out: FileWriter? = null
    private var asked: String? = null // the device Android was last asked about (one question per plug-in)
    private var failed: String? = null // a device Vox could not read (not retried until Connect or a new plug-in)
    private var failedWhy = ""
    @Volatile private var bytes = 0L
    @Volatile private var lastData = 0L
    @Volatile private var raw = "" // the last bytes, in hex, while no whole line has come

    private val tick = object : Runnable {
        override fun run() {
            scan(false)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = page()
        page.addView(header("Case log", "What the charging case says it is doing", "Done") { finish() })
        val c = card("The case", "Connect the case to this phone with a USB-C data cable (the cable that came with " +
            "the glasses only charges). Put the glasses in, lid open. Vox picks the case up by itself.")
        status = text("", 13.5f, Ui.TEXT)
        c.addView(status)
        c.addView(chipRow(
            chip("Connect", true) { scan(true) },
            chip("Clear", false) { clear() },
            chip("Share", false) { share() },
        ))
        seen = text("", 11f, Ui.MUTED, Ui.MONO)
        c.addView(seen)
        page.addView(c)
        val s = card("Each arm")
        summary = text("", 12.5f, Ui.TEXT, Ui.MONO)
        s.addView(summary)
        page.addView(s)
        val l = card("The log", "Newest first")
        log = text("", 11f, Ui.MUTED, Ui.MONO)
        l.addView(log)
        page.addView(l)
        runCatching { out = FileWriter(File(getExternalFilesDir(null), "caselog.txt"), true) }
        summary.text = stats.text()
    }

    // Opened again by plugging the case in while this screen is up.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scan(true)
    }

    // Android's permission question pauses this screen; coming back looks again.
    override fun onResume() {
        super.onResume()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stop()
        runCatching { out?.close() }
        super.onDestroy()
    }

    private fun stop() {
        running = false
        port?.let { p -> runCatching { p.conn.close() } }
        port = null
    }

    // force: Connect tapped (asks Android again, retries a device that failed, reopens the port).
    private fun scan(force: Boolean) {
        val um = getSystemService(UsbManager::class.java)
        val devs = um.deviceList.values.toList()
        seen.text = describe(devs)
        port?.let { p ->
            if (devs.none { it.deviceName == p.name }) {
                stop()
                line("— the case was unplugged —")
            } else if (!force) {
                status.text = reading()
                return
            } else stop()
        }
        val dev = devs.firstOrNull { CaseSerial.likely(it) } ?: devs.firstOrNull()
        if (dev == null) {
            asked = null
            failed = null
            status.text = "Android sees nothing on the USB port. If the case is plugged in: the cable only charges " +
                "(use a USB-C data cable, like a phone's or a laptop's), or plug it in again firmly."
            return
        }
        val id = "%04x:%04x".format(dev.vendorId, dev.productId)
        if (!force && dev.deviceName == failed) {
            status.text = failedWhy
            return
        }
        if (!um.hasPermission(dev)) {
            if (force || dev.deviceName != asked) {
                asked = dev.deviceName
                val pi = PendingIntent.getBroadcast(this, 0, Intent("scouterkit.ssh.CASE_USB").setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE)
                um.requestPermission(dev, pi)
            }
            status.text = "Found the case ($id). Allow Vox to use it in Android's question (no question? tap Connect)."
            return
        }
        open(um, dev, id)
    }

    private fun open(um: UsbManager, dev: UsbDevice, id: String) {
        val p = CaseSerial.open(um, dev)
        if (p == null) {
            failed = dev.deviceName
            failedWhy = "The device on the port ($id) has no serial port Vox can read (see what Android sees, below)."
            status.text = failedWhy
            line("— $id: no serial port —")
            return
        }
        failed = null
        port = p
        bytes = 0
        raw = ""
        lastData = SystemClock.elapsedRealtime()
        running = true
        line("— connected: ${dev.productName ?: "case"} $id ${p.kind}${if (p.setUp) "" else " (set-up not confirmed)"} —")
        status.text = reading()
        Thread({ read(p) }, "case-log").start()
    }

    private fun reading(): String {
        val p = port ?: return ""
        val quiet = (SystemClock.elapsedRealtime() - lastData) / 1000
        val sb = StringBuilder("Reading the case (${p.kind}, 115200${if (p.setUp) "" else ", set-up not confirmed"}): ")
        sb.append("$bytes bytes, ${stats.lines} lines.")
        if (bytes > 0 && stats.lines == 0 && raw.isNotEmpty())
            sb.append(" Bytes but no lines (wrong speed?): ").append(raw)
        if (quiet >= 4) sb.append(" Quiet ${quiet}s: open and close the lid, or take an arm out and put it back.")
        return sb.toString()
    }

    private fun read(p: CaseSerial.Port) {
        val buf = ByteArray(maxOf(64, p.ep.maxPacketSize))
        val pending = StringBuilder()
        while (running) {
            val t = SystemClock.elapsedRealtime()
            val n = p.conn.bulkTransfer(p.ep, buf, buf.size, 300)
            if (n <= p.skip) {
                // Unplugged fails at once: don't spin; the scan notices and says so.
                if (n < 0 && SystemClock.elapsedRealtime() - t < 50) Thread.sleep(200)
                continue
            }
            bytes += n - p.skip
            lastData = SystemClock.elapsedRealtime()
            val text = String(buf, p.skip, n - p.skip, Charsets.ISO_8859_1)
            for (ch in text) {
                if (ch == '\n') {
                    val l = pending.toString().trim()
                    pending.setLength(0)
                    if (l.isNotEmpty()) main.post { line(l) }
                } else if (ch != '\r') pending.append(ch)
            }
            if (pending.length > 400) { // no newline at all: not text at this speed; keep the tail to show
                raw = pending.takeLast(16).map { "%02x".format(it.code and 0xff) }.joinToString(" ")
                pending.setLength(0)
            }
        }
    }

    private fun line(l: String) {
        stats.feed(l)
        shown.addFirst(l)
        while (shown.size > 300) shown.removeLast()
        log.text = shown.joinToString("\n")
        summary.text = stats.text()
        if (running) status.text = reading()
        runCatching {
            out?.write(l + "\n")
            out?.flush()
        }
    }

    private fun clear() {
        shown.clear()
        stats.reset()
        log.text = ""
        summary.text = stats.text()
    }

    private fun share() {
        val text = "Vox case log\n\n" + status.text + "\n\n" + seen.text + "\n\n" + stats.text() + "\n\n" +
            shown.reversed().joinToString("\n")
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), "Share the case log"))
        }
    }

    // What Android sees on the port: each device, its chip and its interfaces.
    private fun describe(devs: List<UsbDevice>): String {
        if (devs.isEmpty()) return "On the USB port: nothing"
        val sb = StringBuilder("On the USB port:")
        for (d in devs) {
            sb.append("\n%04x:%04x".format(d.vendorId, d.productId))
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")
            if (name.isNotEmpty()) sb.append("  ").append(name)
            CaseSerial.chip(d)?.let { sb.append("  (").append(it).append(')') }
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                sb.append("\n  interface ${itf.id}: class %02x/%02x/%02x".format(itf.interfaceClass,
                    itf.interfaceSubclass, itf.interfaceProtocol))
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
                        else -> "control"
                    }
                    sb.append(", $type ${if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out"} ${ep.maxPacketSize}")
                }
            }
        }
        return sb.toString()
    }
}

// USB-to-serial chips at 115200 8N1: CH340 / CH341 (vendor 1A86; the G1 case's), CP210x (10C4), FTDI (0403), and
// anything speaking standard CDC-ACM (WCH's CH9102 / CH343 too, which share vendor 1A86 but not the CH34x protocol).
object CaseSerial {
    class Port(val conn: UsbDeviceConnection, val ep: UsbEndpoint, val kind: String, val setUp: Boolean,
               val skip: Int, val name: String) // skip: status bytes at each packet's start (FTDI: 2)

    private fun cdcLike(dev: UsbDevice) = (0 until dev.interfaceCount).any {
        val c = dev.getInterface(it).interfaceClass
        c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA
    }

    fun chip(dev: UsbDevice): String? = when {
        cdcLike(dev) -> "CDC serial"
        dev.vendorId == 0x1A86 -> "CH34x serial"
        dev.vendorId == 0x10C4 -> "CP210x serial"
        dev.vendorId == 0x0403 -> "FTDI serial"
        else -> null
    }

    fun likely(dev: UsbDevice) = chip(dev) != null

    fun open(um: UsbManager, dev: UsbDevice): Port? {
        var data: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var control: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM && control == null) control = itf
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (inEp == null && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                    data = itf
                    inEp = ep
                }
            }
        }
        if (data == null || inEp == null) return null
        val conn = um.openDevice(dev) ?: return null
        control?.let { conn.claimInterface(it, true) }
        if (!conn.claimInterface(data, true)) {
            conn.close()
            return null
        }
        val n = dev.deviceName
        return when {
            cdcLike(dev) -> Port(conn, inEp, "CDC", cdc(conn, control?.id ?: data.id), 0, n)
            dev.vendorId == 0x1A86 -> Port(conn, inEp, "CH34x", ch34x(conn), 0, n)
            dev.vendorId == 0x10C4 -> Port(conn, inEp, "CP210x", cp210x(conn, data.id), 0, n)
            dev.vendorId == 0x0403 -> Port(conn, inEp, "FTDI", ftdi(conn), 2, n)
            else -> Port(conn, inEp, "unknown chip, read as CDC", cdc(conn, data.id), 0, n)
        }
    }

    // As usb-serial-for-android's Ch34xSerialDriver: 115200 = divisor 0xcc83 / 0x0008.
    private fun ch34x(c: UsbDeviceConnection): Boolean {
        val buf = ByteArray(8)
        fun out(req: Int, value: Int, index: Int) = c.controlTransfer(0x40, req, value, index, null, 0, 1000) >= 0
        fun ask(req: Int, value: Int) = c.controlTransfer(0xC0, req, value, 0, buf, 2, 1000) >= 0
        fun baud() = out(0x9a, 0x1312, 0xcc83) and out(0x9a, 0x0f2c, 0x0008)
        ask(0x5f, 0)
        var ok = out(0xa1, 0, 0)
        ok = baud() and ok
        ask(0x95, 0x2518)
        ok = out(0x9a, 0x2518, 0xc3) and ok // receive, send, 8 bits
        ask(0x95, 0x0706)
        ok = out(0xa1, 0x501f, 0xd90a) and ok
        ok = baud() and ok
        out(0xa4, 0x60.inv() and 0xffff, 0) // DTR and RTS on (active low)
        return ok
    }

    private fun cp210x(c: UsbDeviceConnection, iface: Int): Boolean {
        fun out(req: Int, value: Int, data: ByteArray? = null) =
            c.controlTransfer(0x41, req, value, iface, data, data?.size ?: 0, 1000) >= 0
        var ok = out(0x00, 0x0001) // the UART on
        ok = out(0x1E, 0, byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00)) and ok // 115200
        ok = out(0x03, 0x0800) and ok // 8N1
        out(0x07, 0x0303) // DTR, RTS
        return ok
    }

    private fun ftdi(c: UsbDeviceConnection): Boolean {
        fun out(req: Int, value: Int) = c.controlTransfer(0x40, req, value, 0, null, 0, 1000) >= 0
        var ok = out(0, 0) // reset
        ok = out(3, 0x001A) and ok // 115200
        ok = out(4, 0x0008) and ok // 8N1
        out(2, 0) // no flow control
        out(1, 0x0303) // DTR, RTS
        return ok
    }

    private fun cdc(c: UsbDeviceConnection, iface: Int): Boolean {
        val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8) // 115200 8N1
        val ok = c.controlTransfer(0x21, 0x20, 0, iface, coding, coding.size, 1000) >= 0
        c.controlTransfer(0x21, 0x22, 0x0003, iface, null, 0, 1000) // DTR, RTS
        return ok
    }
}

// Each arm (the case's NFC0 and NFC1) from the case's lines (firmware 2.79's own messages):
//   NFC%d: [%d] Get data:%02x %02x %02x %02x, Rx VRECT: %dmV, Battery Level: %d%%, curFlg:%d,current;%d
//   NFC%d:[%d]WLC State: %s (%d)          NFC%d: card lost?.....%d....     NFC%d: read rx info timeout, goto charge..
//   NFC%d: [%d] Multiple Tags detected: %d    NFC%d: [%d] Impedance Change detected    NFC%d: [%d] WPT STOP
//   [%d] RX BatLevel--L:%d%%, R:%d%%, bat:%d    box bat: %d mV, %d%%    NFC IC0:%d, NFC IC1:%d, Bat:%d
//   usb:%02x, lid:%02x,  charge:%02x, ...     3916 Power failed,reset it now..(%d)
class CaseStats {
    class Arm {
        var state = ""
        var vrect = -1
        var level = -1
        var flag = -1
        var current = Int.MIN_VALUE
        var reports = 0
        var lines = 0
        var lost = 0
        var timeouts = 0
        var multi = 0
        var stops = 0
        var removals = 0
        var pauses = 0
        var sendFail = 0
        var errors = 0
    }

    var arms = arrayOf(Arm(), Arm())
    var left = -1
    var right = -1
    var box = -1
    var hot = 0
    var lines = 0
    var temps = ""
    var ports = ""
    var chipResets = 0
    private var lastArm = -1

    fun reset() {
        arms = arrayOf(Arm(), Arm())
        left = -1; right = -1; box = -1; hot = 0; lines = 0; temps = ""; ports = ""; chipResets = 0; lastArm = -1
    }

    private val nfc = Regex("""NFC\s*(\d)""") // not "NFC IC0" (the temperature line)
    private val vrect = Regex("""VRECT:\s*(\d+)""")
    private val level = Regex("""Battery Level:\s*(\d+)""")
    private val flag = Regex("""[Ff]lg:\s*(\d+)""")
    private val current = Regex("""current\W\s*(-?\d+)""")
    private val wlc = Regex("""WLC State:\s*(\S+)""")
    private val lr = Regex("""L:\s*(\d+)%+,\s*R:\s*(\d+)%""")
    private val boxPct = Regex("""Box BatLevel:\s*(\d+)%|box bat:\s*\d+\s*mV,\s*(\d+)%""")
    private val temp = Regex("""NFC IC0:\s*(-?\d+),\s*NFC IC1:\s*(-?\d+),\s*Bat:\s*(-?\d+)""")
    private val usb = Regex("""usb:\s*(\w+),\s*lid:\s*(\w+),\s*charge:\s*(\w+)""")

    fun feed(l: String) {
        lines++
        lr.find(l)?.let { left = it.groupValues[1].toInt(); right = it.groupValues[2].toInt() }
        boxPct.find(l)?.let { box = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() ?: box }
        temp.find(l)?.let { temps = "chip 0 ${it.groupValues[1]}°, chip 1 ${it.groupValues[2]}°, battery ${it.groupValues[3]}°" }
        usb.find(l)?.let { ports = "usb ${it.groupValues[1]}, lid ${it.groupValues[2]}, charge ${it.groupValues[3]}" }
        if (l.contains("temperature warning", true)) hot++
        if (l.contains("3916") && (l.contains("failed", true) || l.contains("reset", true))) chipResets++
        val tagged = nfc.find(l)?.groupValues?.get(1)?.toIntOrNull()
        // A report's tail can come on a line of its own: it belongs to the arm just heard from.
        val i = tagged ?: if (flag.containsMatchIn(l) || current.containsMatchIn(l)) lastArm else return
        if (i !in 0..1) return
        lastArm = i
        val a = arms[i]
        a.lines++
        wlc.find(l)?.let { a.state = it.groupValues[1].removePrefix("WLC_STATE_") }
        vrect.find(l)?.let { a.vrect = it.groupValues[1].toInt() }
        level.find(l)?.let { a.level = it.groupValues[1].toInt(); a.reports++ }
        flag.find(l)?.let { a.flag = it.groupValues[1].toInt() }
        current.find(l)?.let { a.current = it.groupValues[1].toInt() }
        when {
            l.contains("card lost", true) -> a.lost++
            l.contains("rx info timeout", true) -> a.timeouts++
            l.contains("Multiple Tags", true) -> a.multi++
            l.contains("WPT STOP", true) -> a.stops++
            l.contains("Impedance Change", true) -> a.removals++
            l.contains("recharge", true) || l.contains("Pause", true) -> a.pauses++
            l.contains("fail", true) && l.contains("glasses", true) -> a.sendFail++
            l.contains("error", true) -> a.errors++
        }
    }

    // Which NFC is which temple, from the case's own "L:%, R:%" next to each arm's last level.
    private fun side(i: Int): String {
        val lv = arms[i].level
        if (lv < 0 || left < 0 || left == right) return ""
        return when (lv) { left -> " (left)"; right -> " (right)"; else -> "" }
    }

    fun text(): String {
        val sb = StringBuilder()
        if (lines == 0) return "Nothing from the case yet."
        if (left >= 0) sb.append("Arms as the case sees them: L $left%  R $right%\n")
        if (box >= 0) sb.append("Case battery: $box%\n")
        if (ports.isNotEmpty()) sb.append("Case: $ports\n")
        if (temps.isNotEmpty()) sb.append("Temperatures: $temps\n")
        if (hot > 0) sb.append("Temperature warnings: $hot (the case stops charging both arms while hot)\n")
        if (chipResets > 0) sb.append("Charging chip failures / resets: $chipResets\n")
        for ((i, a) in arms.withIndex()) {
            sb.append("\nNFC$i${side(i)}").append(if (a.state.isNotEmpty()) "  ${a.state}" else "").append('\n')
            if (a.lines == 0) {
                sb.append("  nothing from this arm at all\n")
                continue
            }
            if (a.reports > 0) {
                sb.append("  last report: ${a.level}%")
                if (a.vrect >= 0) sb.append(", VRECT ${a.vrect} mV")
                if (a.flag >= 0) sb.append(", charging flag ${a.flag}")
                if (a.current != Int.MIN_VALUE) sb.append(", current ${a.current} mA")
                sb.append(" (${a.reports} reports)\n")
            } else sb.append("  no battery report from this arm yet\n")
            val notes = mutableListOf<String>()
            if (a.lost > 0) notes += "not found ${a.lost}× (card lost): seat it again, left temple folded first"
            if (a.timeouts > 0) notes += "no answer ${a.timeouts}× (charged blindly; the case gives up after 100)"
            if (a.multi > 0) notes += "sees two tags ${a.multi}× (arms overlapping: fold the left temple first)"
            if (a.removals > 0) notes += "impedance changes ${a.removals}× (the arm moved or was taken out)"
            if (a.stops > 0) notes += "power stopped ${a.stops}×"
            if (a.pauses > 0) notes += "full pauses / re-checks ${a.pauses}×"
            if (a.sendFail > 0) notes += "messages to the arm failed ${a.sendFail}×"
            if (a.errors > 0) notes += "chip read errors ${a.errors}×"
            if (a.reports > 0 && a.level in 0..98 && a.current in 0..5) notes += "below full but taking almost no current"
            if (a.vrect in 1..4999) notes += "low VRECT (${a.vrect} mV): weak power transfer (alignment, or a lit display)"
            for (n in notes) sb.append("  • ").append(n).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

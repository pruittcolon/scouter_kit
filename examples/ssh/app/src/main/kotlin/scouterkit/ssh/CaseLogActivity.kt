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
import android.widget.TextView
import java.io.File
import java.io.FileWriter

// The charging case's own log. Its USB-C port (a USB-to-serial chip inside the case: the port Even's case updater
// uses) carries what its firmware prints, at 115200 baud: each arm's NFC charging state (WLC_STATE_...), the arm's
// battery report (Rx VRECT, Battery Level, charge flag, current), "card lost", "read rx info timeout", "Multiple
// Tags detected", the case's battery and temperature. With the case on the phone by a USB-C data cable this screen
// reads it live, sums each arm up and keeps a copy (Android/data/<package>/files/caselog.txt).
class CaseLogActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var log: TextView
    @Volatile private var running = false
    private var port: CaseSerial.Port? = null
    private val shown = ArrayDeque<String>()
    private val stats = CaseStats()
    private var out: FileWriter? = null
    private var waitingSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = page()
        page.addView(header("Case log", "What the charging case says it is doing", "Done") { finish() })
        val c = card("The case", "Connect the case to this phone with a USB-C data cable (the cable that came with " +
            "the glasses only charges). Put the glasses in, lid open.")
        status = text("", 13.5f, Ui.TEXT)
        c.addView(status)
        c.addView(chipRow(
            chip("Connect", true) { connect() },
            chip("Clear", false) { clear() },
            chip("Share", false) { share() },
        ))
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
        connect()
    }

    override fun onDestroy() {
        stop()
        runCatching { out?.close() }
        super.onDestroy()
    }

    private fun stop() {
        running = false
        port?.let { p -> runCatching { p.conn.close() } }
        port = null
    }

    // The case: the USB device attached (a phone has nothing else on its port); Android asks once to let Vox use it.
    private fun connect() {
        stop()
        main.removeCallbacksAndMessages(null)
        val um = getSystemService(UsbManager::class.java)
        val dev = um.deviceList.values.firstOrNull()
        if (dev == null) {
            status.text = "No case on the USB port. Plug it in with a data cable, then tap Connect."
            return
        }
        val id = "%04x:%04x".format(dev.vendorId, dev.productId)
        if (!um.hasPermission(dev)) {
            status.text = "Allow Vox to use the case ($id) in Android's question…"
            val pi = PendingIntent.getBroadcast(this, 0, Intent("scouterkit.ssh.CASE_USB").setPackage(packageName),
                PendingIntent.FLAG_MUTABLE)
            um.requestPermission(dev, pi)
            waitingSince = System.currentTimeMillis()
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (um.hasPermission(dev)) return open(um, dev, id)
                    if (System.currentTimeMillis() - waitingSince < 60000) main.postDelayed(this, 500)
                    else status.text = "Vox may not use the case: tap Connect to be asked again."
                }
            }, 500)
            return
        }
        open(um, dev, id)
    }

    private fun open(um: UsbManager, dev: UsbDevice, id: String) {
        val p = CaseSerial.open(um, dev)
        if (p == null) {
            status.text = "The case ($id) has no serial port Vox can read."
            return
        }
        port = p
        running = true
        status.text = "Reading the case ($id, ${p.kind}${if (p.setUp) "" else ", set-up not confirmed"}) at 115200…"
        line("— connected: ${dev.productName ?: "case"} $id ${p.kind} —")
        Thread({ read(p) }, "case-log").start()
    }

    private fun read(p: CaseSerial.Port) {
        val buf = ByteArray(maxOf(64, p.ep.maxPacketSize))
        val pending = StringBuilder()
        var quiet = 0L
        while (running) {
            val n = p.conn.bulkTransfer(p.ep, buf, buf.size, 300)
            if (n < 0) {
                quiet += 300
                if (quiet >= 4000 && pending.isEmpty()) {
                    quiet = 0
                    main.post { if (running) status.text = "Connected; the case is quiet (open and close the lid, or move the glasses)." }
                }
                continue
            }
            quiet = 0
            val text = String(buf, p.skip, maxOf(0, n - p.skip), Charsets.ISO_8859_1)
            for (ch in text) {
                if (ch == '\n') {
                    val l = pending.toString().trim()
                    pending.setLength(0)
                    if (l.isNotEmpty()) main.post { line(l) }
                } else if (ch != '\r') pending.append(ch)
            }
        }
    }

    private fun line(l: String) {
        stats.feed(l)
        shown.addFirst(l)
        while (shown.size > 300) shown.removeLast()
        log.text = shown.joinToString("\n")
        summary.text = stats.text()
        if (running) status.text = "Reading the case: ${stats.lines} lines"
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
        val text = "Vox case log\n\n" + stats.text() + "\n\n" + shown.reversed().joinToString("\n")
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), "Share the case log"))
        }
    }
}

// USB-to-serial chips at 115200 8N1: CH340 / CH341 (vendor 1A86), CP210x (10C4), FTDI (0403), else CDC-ACM.
object CaseSerial {
    class Port(val conn: UsbDeviceConnection, val ep: UsbEndpoint, val kind: String, val setUp: Boolean,
               val skip: Int) // skip: status bytes at each packet's start (FTDI: 2)

    fun open(um: UsbManager, dev: UsbDevice): Port? {
        val conn = um.openDevice(dev) ?: return null
        var data: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var control: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM) control = itf
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (inEp == null && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                    data = itf
                    inEp = ep
                }
            }
        }
        if (data == null || inEp == null) {
            conn.close()
            return null
        }
        control?.let { conn.claimInterface(it, true) }
        conn.claimInterface(data, true)
        return when (dev.vendorId) {
            0x1A86 -> Port(conn, inEp, "CH34x", ch34x(conn), 0)
            0x10C4 -> Port(conn, inEp, "CP210x", cp210x(conn, data.id), 0)
            0x0403 -> Port(conn, inEp, "FTDI", ftdi(conn), 2)
            else -> Port(conn, inEp, "CDC", cdc(conn, control?.id ?: 0), 0)
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

// Each arm (the case's NFC0 and NFC1) from the case's lines (firmware 2.79's own messages).
class CaseStats {
    class Arm {
        var state = ""
        var vrect = -1
        var level = -1
        var flag = -1
        var current = Int.MIN_VALUE
        var reports = 0
        var lost = 0
        var timeouts = 0
        var multi = 0
        var stops = 0
        var removals = 0
        var pauses = 0
        var sendFail = 0
    }

    var arms = arrayOf(Arm(), Arm())
    var left = -1
    var right = -1
    var box = -1
    var hot = 0
    var lines = 0

    fun reset() {
        arms = arrayOf(Arm(), Arm())
        left = -1; right = -1; box = -1; hot = 0; lines = 0
    }

    private val nfc = Regex("""NFC\s*(\d)""")
    private val report = Regex("""VRECT:\s*(\d+)\s*mV.*?Level:\s*(\d+)%.*?Flg:\s*(\d+).*?current\W\s*(-?\d+)""")
    private val wlc = Regex("""WLC State:\s*(\S+)""")
    private val lr = Regex("""L:\s*(\d+)%+,\s*R:\s*(\d+)%""")
    private val boxPct = Regex("""Box BatLevel:\s*(\d+)%|box bat:\s*\d+\s*mV,\s*(\d+)%""")

    fun feed(l: String) {
        lines++
        lr.find(l)?.let { left = it.groupValues[1].toInt(); right = it.groupValues[2].toInt() }
        boxPct.find(l)?.let { box = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() ?: box }
        if (l.contains("temperature warning", true)) hot++
        val i = nfc.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (i !in 0..1) return
        val a = arms[i]
        wlc.find(l)?.let { a.state = it.groupValues[1].removePrefix("WLC_STATE_") }
        report.find(l)?.let {
            a.vrect = it.groupValues[1].toInt(); a.level = it.groupValues[2].toInt()
            a.flag = it.groupValues[3].toInt(); a.current = it.groupValues[4].toInt(); a.reports++
        }
        when {
            l.contains("card lost", true) -> a.lost++
            l.contains("rx info timeout", true) -> a.timeouts++
            l.contains("Multiple Tags", true) -> a.multi++
            l.contains("WPT STOP", true) -> a.stops++
            l.contains("Impedance Change", true) -> a.removals++
            l.contains("recharge", true) || l.contains("Pause", true) -> a.pauses++
            l.contains("failed", true) && l.contains("glasses", true) -> a.sendFail++
        }
    }

    fun text(): String {
        val sb = StringBuilder()
        if (left >= 0) sb.append("Arms as the case sees them: L $left%  R $right%\n")
        if (box >= 0) sb.append("Case battery: $box%\n")
        if (hot > 0) sb.append("Case temperature warnings: $hot (it stops charging both arms while hot)\n")
        for ((i, a) in arms.withIndex()) {
            sb.append("\nNFC$i").append(if (a.state.isNotEmpty()) "  ${a.state}" else "").append('\n')
            if (a.reports > 0)
                sb.append("  last report: ${a.level}%, VRECT ${a.vrect} mV, charging flag ${a.flag}, current ${a.current} mA (${a.reports} reports)\n")
            else sb.append("  no battery report from this arm yet\n")
            val notes = mutableListOf<String>()
            if (a.lost > 0) notes += "not found ${a.lost}× (card lost): seat it again, left temple folded first"
            if (a.timeouts > 0) notes += "no answer ${a.timeouts}× (charged blindly; the case gives up after 100)"
            if (a.multi > 0) notes += "sees two tags ${a.multi}× (arms overlapping: fold the left temple first)"
            if (a.removals > 0) notes += "impedance changes ${a.removals}× (the arm moved or was taken out)"
            if (a.stops > 0) notes += "power stopped ${a.stops}×"
            if (a.pauses > 0) notes += "full pauses / re-checks ${a.pauses}×"
            if (a.sendFail > 0) notes += "messages to the arm failed ${a.sendFail}×"
            if (a.reports > 0 && a.level in 0..98 && a.current in 0..5) notes += "below full but taking almost no current"
            if (a.vrect in 1..4999) notes += "low VRECT (${a.vrect} mV): weak power transfer (alignment, or a lit display)"
            for (n in notes) sb.append("  • ").append(n).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

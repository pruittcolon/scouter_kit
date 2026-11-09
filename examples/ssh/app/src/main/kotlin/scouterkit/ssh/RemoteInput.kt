package scouterkit.ssh

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.LinkedBlockingQueue

// The keyboard and the trackpad on a server's screen (the laptop's, on the glasses): its X11 desktop takes them from
// a small Python program there (Xlib's XTEST through ctypes: nothing to install; AGENT), sent over SSH as the
// command itself. One line each on its input:
//   p X Y        the pointer to X, Y (1/10000 of the desktop, as the picture: vox-screen sends the whole of it)
//   b N 1|0      button N (1 left, 2 middle, 3 right) down or up
//   w N COUNT    button N clicked COUNT times (4 / 5 the wheel up / down, 6 / 7 left / right)
//   k MODS SYM   the key with that X keysym, with modifiers (1 Ctrl, 2 Shift, 4 Alt, 8 Super) held for it
//   r            every button up
// It says "screen W H" once it works, and "at X Y" where the pointer is (at the start, and when the laptop's own
// mouse moved it). Its input closing lets go of every button.
class RemoteInput(context: Context, val server: Settings.Server, private val onPointer: (Int, Int) -> Unit) {
    private val vault = Vault(context)
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var ended = false
        private set
    @Volatile var usable = false // the program answered ("screen W H")
        private set
    @Volatile private var stream: Bootstrap.Stream? = null

    fun start() = Thread({ run() }, "remote-input").start()

    fun send(line: String) {
        if (!ended) queue.offer(line)
    }

    fun close() {
        ended = true
        usable = false
        queue.offer("")
        runCatching { stream?.close() }
        if (current === this) current = null
    }

    private fun run() {
        val why = try {
            val s = Bootstrap(vault).stream(server.host, server.port, server.user, COMMAND, input = true)
            stream = s
            if (ended) {
                s.close()
                return
            }
            Thread({ read(s) }, "remote-input-read").start()
            val out = s.output ?: throw IllegalStateException("no input to the command")
            val batch = ArrayList<String>()
            while (!ended) {
                batch.clear()
                batch += queue.take()
                queue.drainTo(batch)
                if (ended) break
                // Pointer moves in a row: the last of them is enough.
                val text = StringBuilder()
                for ((i, l) in batch.withIndex()) {
                    if (l.isEmpty() || (l.startsWith("p ") && batch.getOrNull(i + 1)?.startsWith("p ") == true)) continue
                    text.append(l).append('\n')
                }
                if (text.isNotEmpty()) {
                    out.write(text.toString().toByteArray())
                    out.flush()
                }
            }
            ""
        } catch (e: Bootstrap.UnknownHost) {
            "show ${server.name}'s terminal once to trust it first"
        } catch (e: Exception) {
            if (ended) "" else Bootstrap.plain(e, server.host, server.port)
        }
        if (why.isNotEmpty()) Log.w(ScouterService.TAG, "laptop input: $why")
        close()
    }

    private fun read(s: Bootstrap.Stream) {
        runCatching {
            s.input.bufferedReader().forEachLine { line ->
                val p = line.trim().split(" ")
                when (p[0]) {
                    "screen" -> {
                        usable = true
                        Log.i(ScouterService.TAG, "laptop input: ready (${server.name}, ${p.getOrNull(1)} x ${p.getOrNull(2)})")
                    }
                    "at" -> {
                        val x = p.getOrNull(1)?.toIntOrNull()
                        val y = p.getOrNull(2)?.toIntOrNull()
                        if (x != null && y != null) onPointer(x, y)
                    }
                    "err" -> Log.w(ScouterService.TAG, "laptop input: " + line.removePrefix("err ").take(200))
                }
            }
        }
        if (!ended) {
            Thread.sleep(200)
            val err = s.errors
            Log.w(ScouterService.TAG, "laptop input ended" + if (err.isNotEmpty()) ": " + err.lines().last().take(200) else "")
            close()
        }
    }

    companion object {
        @Volatile var current: RemoteInput? = null // the one for the screen on the glasses (Hotkeys' keys go to it)

        private const val CTRL = 1
        private const val SHIFT = 2
        private const val ALT = 4
        private const val SUPER = 8

        // Android key codes of keys that type no character: their X keysyms.
        private val SYMS = mapOf(
            KeyEvent.KEYCODE_ENTER to 0xFF0D, KeyEvent.KEYCODE_NUMPAD_ENTER to 0xFF0D, KeyEvent.KEYCODE_ESCAPE to 0xFF1B,
            KeyEvent.KEYCODE_DEL to 0xFF08, KeyEvent.KEYCODE_FORWARD_DEL to 0xFFFF, KeyEvent.KEYCODE_TAB to 0xFF09,
            KeyEvent.KEYCODE_SPACE to 0x20, KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51, KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53, KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54, KeyEvent.KEYCODE_MOVE_HOME to 0xFF50,
            KeyEvent.KEYCODE_MOVE_END to 0xFF57, KeyEvent.KEYCODE_PAGE_UP to 0xFF55, KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
            KeyEvent.KEYCODE_INSERT to 0xFF63, KeyEvent.KEYCODE_CAPS_LOCK to 0xFFE5, KeyEvent.KEYCODE_MENU to 0xFF67,
            KeyEvent.KEYCODE_SYSRQ to 0xFF61, KeyEvent.KEYCODE_BREAK to 0xFF13,
        ) + (0 until 12).associate { (KeyEvent.KEYCODE_F1 + it) to (0xFFBE + it) }

        private var superAlone = false // Super pressed with nothing else yet: by itself it goes on its release

        // A key from the keyboard, to the laptop. True: it was the laptop's (both its press and its release).
        // Modifiers go with the key they hold (a chord at its press), so nothing stays held on the laptop if the
        // glasses switch away meanwhile.
        fun key(e: KeyEvent): Boolean {
            val input = current?.takeIf { it.usable } ?: return false
            if (e.keyCode == KeyEvent.KEYCODE_META_LEFT || e.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) superAlone = true
                if (e.action == KeyEvent.ACTION_UP && superAlone) {
                    superAlone = false
                    input.send("k 0 ${0xFFEB}") // Super by itself (the activities, the app menu)
                }
                return true
            }
            if (KeyEvent.isModifierKey(e.keyCode)) return true
            val sym = SYMS[e.keyCode] ?: e.getUnicodeChar(0).takeIf { it > 0 && it and KeyCharacterMapCombining == 0 }?.let {
                if (it in 0x20..0x7E || it in 0xA0..0xFF) it else 0x1000000 or it
            } ?: return false
            if (e.action == KeyEvent.ACTION_DOWN) {
                superAlone = false
                val mods = (if (e.isCtrlPressed) CTRL else 0) or (if (e.isShiftPressed) SHIFT else 0) or
                    (if (e.isAltPressed) ALT else 0) or (if (e.isMetaPressed) SUPER else 0)
                input.send("k $mods $sym")
            }
            return true
        }

        private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT

        private val AGENT = """import ctypes, os, select, sys
os.environ.setdefault("DISPLAY", ":0")
if not os.environ.get("XAUTHORITY"):
    for p in ("/run/user/%d/gdm/Xauthority" % os.getuid(), os.path.expanduser("~/.Xauthority")):
        if os.path.exists(p):
            os.environ["XAUTHORITY"] = p
            break


def out(s):
    try:
        sys.stdout.write(s + "\n")
        sys.stdout.flush()
        return True
    except (OSError, ValueError):
        return False


try:
    X = ctypes.CDLL("libX11.so.6")
    T = ctypes.CDLL("libXtst.so.6")
except OSError as e:
    out("err " + str(e))
    sys.exit(1)
P, I, U, L, B = ctypes.c_void_p, ctypes.c_int, ctypes.c_uint, ctypes.c_ulong, ctypes.c_ubyte
R = ctypes.POINTER
X.XOpenDisplay.restype = P
X.XOpenDisplay.argtypes = [ctypes.c_char_p]
d = X.XOpenDisplay(None)
if not d:
    out("err no X11 desktop on " + os.environ["DISPLAY"])
    sys.exit(1)


def fn(lib, name, res, *args):
    f = getattr(lib, name)
    f.restype = res
    f.argtypes = [P] + list(args)
    return f


root = fn(X, "XDefaultRootWindow", L)(d)
geometry = fn(X, "XGetGeometry", I, L, R(L), R(I), R(I), R(U), R(U), R(U), R(U))
query = fn(X, "XQueryPointer", I, L, R(L), R(L), R(I), R(I), R(I), R(I), R(U))
motion = fn(T, "XTestFakeMotionEvent", I, I, I, I, L)
button = fn(T, "XTestFakeButtonEvent", I, U, I, L)
key = fn(T, "XTestFakeKeyEvent", I, U, I, L)
code_of = fn(X, "XKeysymToKeycode", B, L)
get_map = fn(X, "XGetKeyboardMapping", R(L), B, I, R(I))
set_map = fn(X, "XChangeKeyboardMapping", I, I, I, R(L), I)
codes = fn(X, "XDisplayKeycodes", I, R(I), R(I))
flush = fn(X, "XFlush", I)
sync = fn(X, "XSync", I, I)
X.XFree.argtypes = [P]
X.XFree.restype = I
has_xtest = fn(T, "XTestQueryExtension", I, R(I), R(I), R(I), R(I))
e1, e2, e3, e4 = I(), I(), I(), I()
if not has_xtest(d, ctypes.byref(e1), ctypes.byref(e2), ctypes.byref(e3), ctypes.byref(e4)):
    out("err the X server has no XTEST")
    sys.exit(1)


def size():
    r, x, y, w, h, b, dp = L(), I(), I(), U(), U(), U(), U()
    geometry(d, root, ctypes.byref(r), ctypes.byref(x), ctypes.byref(y), ctypes.byref(w), ctypes.byref(h),
             ctypes.byref(b), ctypes.byref(dp))
    return max(2, w.value), max(2, h.value)


def where():
    r, c, rx, ry, wx, wy, m = L(), L(), I(), I(), I(), I(), U()
    query(d, root, ctypes.byref(r), ctypes.byref(c), ctypes.byref(rx), ctypes.byref(ry), ctypes.byref(wx),
          ctypes.byref(wy), ctypes.byref(m))
    return rx.value, ry.value


def syms_of(code):
    n = I()
    m = get_map(d, code, 1, ctypes.byref(n))
    if not m:
        return []
    s = [m[i] for i in range(n.value)]
    X.XFree(m)
    return s


spares = []
turn = [0]


def spare_code():
    if not spares:
        lo, hi = I(), I()
        codes(d, ctypes.byref(lo), ctypes.byref(hi))
        for c in range(hi.value, lo.value - 1, -1):
            if not any(syms_of(c)):
                spares.append(c)
                if len(spares) == 10:
                    break
    if not spares:
        return 0
    turn[0] += 1
    return spares[turn[0] % len(spares)]


MODS = ((1, 0xFFE3), (2, 0xFFE1), (4, 0xFFE9), (8, 0xFFEB))


def chord(mods, sym):
    c = code_of(d, sym)
    shift = False
    if c:
        s = syms_of(c)
        shift = sym not in s[:1] and sym in s[1:2]
    else:
        c = spare_code()
        if not c:
            return
        pair = (L * 2)(sym, sym)
        set_map(d, c, 2, pair, 1)
        sync(d, 0)
    held = [code_of(d, k) for bit, k in MODS if mods & bit or (bit == 2 and shift)]
    for m in held:
        key(d, m, 1, 0)
    key(d, c, 1, 0)
    key(d, c, 0, 0)
    for m in reversed(held):
        key(d, m, 0, 0)


w, h = size()
lx, ly = where()
down = set()
ok = out("screen %d %d" % (w, h)) and out("at %d %d" % (lx * 10000 // (w - 1), ly * 10000 // (h - 1)))
buf = b""
while ok:
    r = select.select([0], [], [], 0.5)[0]
    if not r:
        w, h = size()
        x, y = where()
        if (x, y) != (lx, ly):
            lx, ly = x, y
            ok = out("at %d %d" % (x * 10000 // (w - 1), y * 10000 // (h - 1)))
        continue
    chunk = os.read(0, 65536)
    if not chunk:
        break
    buf += chunk
    while b"\n" in buf:
        line, buf = buf.split(b"\n", 1)
        p = line.decode("utf-8", "replace").split()
        if not p:
            continue
        try:
            if p[0] == "p":
                lx = min(max(int(p[1]), 0), 10000) * (w - 1) // 10000
                ly = min(max(int(p[2]), 0), 10000) * (h - 1) // 10000
                motion(d, -1, lx, ly, 0)
            elif p[0] == "b":
                b, press = int(p[1]), p[2] == "1"
                if press != (b in down):
                    button(d, b, 1 if press else 0, 0)
                    (down.add if press else down.discard)(b)
            elif p[0] == "w":
                for i in range(min(int(p[2]), 40)):
                    button(d, int(p[1]), 1, 0)
                    button(d, int(p[1]), 0, 0)
            elif p[0] == "k":
                chord(int(p[1]), int(p[2], 0))
            elif p[0] == "r":
                for b in list(down):
                    button(d, b, 0, 0)
                down.clear()
        except (ValueError, IndexError):
            out("err bad line: " + line.decode("utf-8", "replace")[:80])
    flush(d)
for b in list(down):
    button(d, b, 0, 0)
flush(d)"""

        private val COMMAND = "exec python3 -u -c '" + AGENT.also { require('\'' !in it) } + "'"
    }
}

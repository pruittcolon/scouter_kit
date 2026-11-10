package scouterkit.ssh

import android.view.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The text editor on the glasses (Alt+N, or E on the hub): plain .txt (and .md) files in Vox's own folder
// (Android/data/<app>/files/docs, where a computer or adb can put them too). Two views: the files (arrows, Enter
// opens, N a new one) and a file (typing edits it; it is saved by itself, Ctrl+S at once). The lines wrap at the
// panel's width; a ▌ marks the cursor.
class Editor(private val dir: File) {
    // A shown row: the text's characters [start, end) (no line break).
    class Row(val start: Int, val end: Int)

    var file: File? = null // the file open, null: the list of files
        private set
    var said = "" // what the last key did, on the title row
    private var text = StringBuilder()
    private var crlf = false // the file's lines end in CR LF (kept so when saved)
    private var stamp = 0L // the file's time when read or saved
    private var cur = 0 // the cursor, an index into text
    private var goal = -1 // the column up / down keep to (-1: the cursor's own)
    private var top = 0 // the first row shown
    var dirty = false // edited since the last save
        private set
    private var editedAt = 0L

    // The list: the files, newest first, and the one chosen; a name being typed for a new file (null: not).
    private var files: List<File> = emptyList()
    private var at = 0
    private var naming: StringBuilder? = null

    // Undo: the text and cursor before each run of edits (a new run after a pause or another kind of edit).
    private class Snap(val text: String, val cur: Int)
    private val undo = ArrayDeque<Snap>()
    private var lastKind = ""

    // The last layout (width, visible rows), for the keys that move by rows.
    private var width = 60
    private var visible = 10

    init {
        refresh()
    }

    fun refresh() {
        dir.mkdirs()
        files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".md")) }
            .sortedByDescending { it.lastModified() }
        at = at.coerceIn(0, files.size) // files.size: "new file"
    }

    // ------------------------------------------------ opening and saving

    fun open(f: File): String {
        if (f.length() > MAX) return "too big for the glasses (over 1 MB)"
        val raw = runCatching { f.readText() }.getOrElse { return "can't read ${f.name}: ${it.message}" }
        crlf = raw.contains("\r\n")
        text = StringBuilder(raw.replace("\r\n", "\n"))
        file = f
        stamp = f.lastModified()
        cur = 0
        goal = -1
        top = 0
        dirty = false
        undo.clear()
        lastKind = ""
        said = "opened"
        return ""
    }

    // Written whole to a file beside it, then moved over it (a crash never leaves half a file).
    fun save(): Boolean {
        val f = file ?: return true
        if (!dirty) return true
        return runCatching {
            val tmp = File(f.parentFile, ".${f.name}.saving")
            val out = if (crlf) text.toString().replace("\n", "\r\n") else text.toString()
            tmp.writeText(out)
            if (!tmp.renameTo(f)) { f.writeText(out); tmp.delete() }
            stamp = f.lastModified()
            dirty = false
            said = "saved"
            true
        }.getOrElse { said = "not saved: ${it.message}"; false }
    }

    // The open file changed on disk (copied in again) with nothing unsaved here: read again, the place kept.
    fun reloadIfChanged() {
        val f = file ?: return
        if (dirty || !f.exists() || f.lastModified() == stamp) return
        val keep = cur
        val keepTop = top
        if (open(f).isEmpty()) {
            cur = keep.coerceIn(0, text.length)
            top = keepTop
            said = "reloaded"
        }
    }

    // Saved and back to the list.
    fun close() {
        save()
        file = null
        refresh()
        said = ""
    }

    // Edits not saved for a while: the service saves them on its tick.
    fun due(now: Long = System.currentTimeMillis()) = dirty && now - editedAt >= AUTOSAVE_MS

    // ------------------------------------------------ the keys

    // A key (Android's key code, meta state and the character it types, 0 none). False: Esc on the list (close).
    fun key(code: Int, meta: Int, char: Int): Boolean {
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        if (file == null) return listKey(code, char)
        said = ""
        when {
            code == KeyEvent.KEYCODE_ESCAPE -> close()
            ctrl && code == KeyEvent.KEYCODE_S -> if (save()) said = "saved"
            ctrl && code == KeyEvent.KEYCODE_Z -> undoOnce()
            ctrl && code == KeyEvent.KEYCODE_DPAD_LEFT -> move(wordLeft(cur))
            ctrl && code == KeyEvent.KEYCODE_DPAD_RIGHT -> move(wordRight(cur))
            ctrl && code == KeyEvent.KEYCODE_MOVE_HOME -> move(0)
            ctrl && code == KeyEvent.KEYCODE_MOVE_END -> move(text.length)
            ctrl && code == KeyEvent.KEYCODE_DEL -> deleteTo(wordLeft(cur), "word")
            code == KeyEvent.KEYCODE_DPAD_LEFT -> move(cur - 1)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> move(cur + 1)
            code == KeyEvent.KEYCODE_DPAD_UP -> vertical(-1)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> vertical(1)
            code == KeyEvent.KEYCODE_PAGE_UP -> page(-1)
            code == KeyEvent.KEYCODE_PAGE_DOWN -> page(1)
            code == KeyEvent.KEYCODE_MOVE_HOME -> move(lineStart(cur))
            code == KeyEvent.KEYCODE_MOVE_END -> move(lineEnd(cur))
            code == KeyEvent.KEYCODE_DEL -> if (cur > 0) deleteTo(cur - 1, "del")
            code == KeyEvent.KEYCODE_FORWARD_DEL -> if (cur < text.length) { snap("fdel"); text.deleteCharAt(cur); edited() }
            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> insert("\n")
            code == KeyEvent.KEYCODE_TAB -> insert("    ")
            !ctrl && char >= 32 -> insert(String(Character.toChars(char)))
            else -> return true
        }
        return true
    }

    private fun listKey(code: Int, char: Int): Boolean {
        naming?.let { name ->
            when (code) {
                KeyEvent.KEYCODE_ESCAPE -> { naming = null; said = "" }
                KeyEvent.KEYCODE_DEL -> if (name.isNotEmpty()) name.setLength(name.length - 1)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> create(name.toString())
                else -> if (char >= 32 && name.length < 60) name.append(String(Character.toChars(char)))
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> return false
            KeyEvent.KEYCODE_DPAD_UP -> at = (at - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> at = (at + 1).coerceAtMost(files.size)
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_PAGE_UP -> at = 0
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_DOWN -> at = files.size
            KeyEvent.KEYCODE_N -> { naming = StringBuilder(); said = "" }
            KeyEvent.KEYCODE_R -> { refresh(); said = "${files.size} files" }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT ->
                files.getOrNull(at)?.let { said = open(it).ifEmpty { "opened" } } ?: run { naming = StringBuilder(); said = "" }
        }
        return true
    }

    // A new file: the name as typed (letters, digits, - _ . and spaces), .txt added unless it has .txt or .md.
    private fun create(typed: String) {
        var name = typed.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "").replace(Regex("\\s+"), "-").trim('.', '-')
        if (name.isEmpty()) { said = "type a name first"; return }
        if (!name.endsWith(".txt") && !name.endsWith(".md")) name += ".txt"
        val f = File(dir, name)
        naming = null
        if (!f.exists() && runCatching { dir.mkdirs(); f.writeText("") }.isFailure) { said = "can't make $name"; return }
        refresh()
        said = open(f).ifEmpty { "new file $name" }
    }

    // ------------------------------------------------ editing

    private fun snap(kind: String) {
        val now = System.currentTimeMillis()
        if (kind != lastKind || now - editedAt > 1500) {
            undo.addLast(Snap(text.toString(), cur))
            while (undo.size > 100) undo.removeFirst()
        }
        lastKind = kind
    }

    private fun edited() {
        dirty = true
        editedAt = System.currentTimeMillis()
        goal = -1
    }

    private fun insert(s: String) {
        if (text.length + s.length > MAX) { said = "the file is at its 1 MB limit"; return }
        snap(if (s == "\n") "line" else "type")
        text.insert(cur, s)
        cur += s.length
        edited()
    }

    private fun deleteTo(from: Int, kind: String) {
        val a = from.coerceIn(0, cur)
        if (a == cur) return
        snap(kind)
        text.delete(a, cur)
        cur = a
        edited()
    }

    private fun undoOnce() {
        val s = undo.removeLastOrNull() ?: run { said = "nothing to undo"; return }
        text = StringBuilder(s.text)
        cur = s.cur.coerceIn(0, text.length)
        lastKind = ""
        dirty = true
        editedAt = System.currentTimeMillis()
        said = "undone"
    }

    // ------------------------------------------------ moving

    private fun move(to: Int) {
        cur = to.coerceIn(0, text.length)
        goal = -1
    }

    private fun lineStart(i: Int) = text.lastIndexOf("\n", i - 1).let { if (it < 0 || i == 0) 0 else it + 1 }
    private fun lineEnd(i: Int) = text.indexOf("\n", i).let { if (it < 0) text.length else it }

    private fun wordLeft(i: Int): Int {
        var j = i
        while (j > 0 && text[j - 1].isWhitespace()) j--
        while (j > 0 && !text[j - 1].isWhitespace()) j--
        return j
    }

    private fun wordRight(i: Int): Int {
        var j = i
        while (j < text.length && !text[j].isWhitespace()) j++
        while (j < text.length && text[j].isWhitespace()) j++
        return j
    }

    // Up or down a shown row, keeping the column.
    private fun vertical(dir: Int) {
        val rows = layout(text, width)
        val r = rowOf(rows, cur)
        val col = if (goal >= 0) goal else cur - rows[r].start
        val to = (r + dir).coerceIn(0, rows.size - 1)
        if (to == r) {
            cur = if (dir < 0) 0 else text.length
        } else cur = place(rows, to, col)
        goal = col
    }

    // A page of rows up or down, the view with it.
    private fun page(dir: Int) {
        val rows = layout(text, width)
        val step = maxOf(1, visible - 1)
        val r = rowOf(rows, cur)
        val col = if (goal >= 0) goal else cur - rows[r].start
        val to = (r + dir * step).coerceIn(0, rows.size - 1)
        top = (top + dir * step).coerceIn(0, maxOf(0, rows.size - visible))
        cur = place(rows, to, col)
        goal = col
    }

    // The trackpad's wheel: the view moves `n` rows, the cursor stays in it.
    fun scroll(n: Int) {
        if (file == null) {
            at = (at + n.coerceIn(-1, 1)).coerceIn(0, files.size)
            return
        }
        val rows = layout(text, width)
        top = (top + n).coerceIn(0, maxOf(0, rows.size - visible))
        val r = rowOf(rows, cur)
        if (r < top) cur = rows[top].start
        else if (r >= top + visible) cur = rows[minOf(rows.size - 1, top + visible - 1)].start
        goal = -1
    }

    // The index for column `col` of row `r` (a wrapped row's end belongs to the next row: one before it).
    private fun place(rows: List<Row>, r: Int, col: Int): Int {
        val row = rows[r]
        val lineEnd = row.end == text.length || text[row.end] == '\n'
        val last = if (lineEnd) row.end else maxOf(row.start, row.end - 1)
        return minOf(row.start + col, last)
    }

    // ------------------------------------------------ what shows

    // The rows for the panel: `width` characters, `max` rows (the first is the title row).
    fun rows(width: Int, max: Int): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val f = file
        if (f == null) return listRows(width, max)
        this.width = maxOf(10, width - 1) // room for the cursor mark
        visible = maxOf(1, max - 1)
        val rows = layout(text, this.width)
        val r = rowOf(rows, cur)
        if (r < top) top = r
        if (r >= top + visible) top = r - visible + 1
        top = top.coerceIn(0, maxOf(0, rows.size - 1))
        val state = when {
            said.isNotEmpty() -> said
            dirty -> "edited"
            else -> "saved"
        }
        val pos = "line ${lineNumber(cur)} · ${percent(r, rows.size)}"
        out += ("▌▌ ${f.name} ▐░▒▓ $pos · $state").take(width) to 1
        for (i in top until minOf(rows.size, top + visible)) {
            val row = rows[i]
            var s = text.substring(row.start, row.end).replace('\t', ' ')
            if (i == r) {
                val c = (cur - row.start).coerceIn(0, s.length)
                s = s.substring(0, c) + CURSOR + s.substring(c)
            }
            out += s to 0
        }
        return out
    }

    private fun listRows(width: Int, max: Int): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        out += ("▌▌ TEXT FILES ▐░▒▓ ${files.size} in Vox's docs folder" + if (said.isNotEmpty()) " · $said" else "").take(width) to 1
        naming?.let { name ->
            out += "" to 0
            out += " ■ New file: type its name, Enter makes it, Esc no" to 3
            out += "   › $name$CURSOR.txt" to 1
            return out
        }
        if (files.isEmpty()) {
            out += "" to 0
            for (l in Panels.wrap("No files yet. N makes one; files put in Android/data/" +
                "io.github.pruittcolon.scouter/files/docs show here.", width)) out += l to 0
        }
        val fmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
        val shown = maxOf(1, max - 1)
        val first = (at - shown + 1).coerceAtLeast(0)
        for (i in first until minOf(files.size + 1, first + shown)) {
            val line = if (i == files.size) " +  New file (N)"
            else {
                val f = files[i]
                val size = if (f.length() < 1024) "${f.length()} B" else "${f.length() / 1024} KB"
                " ${if (i == at) "■" else "›"}  " + f.name.take(maxOf(10, width - 30)).padEnd(maxOf(10, width - 30)) +
                    " " + size.padStart(7) + "  " + fmt.format(Date(f.lastModified()))
            }
            out += line.take(width) to (if (i == at) 3 else 0)
        }
        return out
    }

    private fun lineNumber(i: Int): Int {
        var n = 1
        for (k in 0 until minOf(i, text.length)) if (text[k] == '\n') n++
        return n
    }

    private fun percent(r: Int, n: Int) = if (n <= 1) "all" else "${(r * 100 / (n - 1))}%"

    companion object {
        const val MAX = 1 shl 20
        const val AUTOSAVE_MS = 2000L
        const val CURSOR = "▌"

        // The text in rows of at most `width` characters: each line, wrapped at a space where one is near.
        fun layout(text: CharSequence, width: Int): List<Row> {
            val w = maxOf(1, width)
            val rows = ArrayList<Row>()
            var lineStart = 0
            while (true) {
                var nl = -1
                for (k in lineStart until text.length) if (text[k] == '\n') { nl = k; break }
                val end = if (nl < 0) text.length else nl
                var s = lineStart
                if (s == end) rows += Row(s, s)
                while (s < end) {
                    var e = minOf(end, s + w)
                    if (e < end) {
                        var sp = e - 1
                        while (sp > s && text[sp] != ' ') sp--
                        if (sp > s) e = sp + 1 // the space stays on this row
                    }
                    rows += Row(s, e)
                    s = e
                }
                if (nl < 0) break
                lineStart = nl + 1
            }
            if (rows.isEmpty()) rows += Row(0, 0)
            return rows
        }

        // The row the cursor is on: a wrapped row's end is the next row's start.
        fun rowOf(rows: List<Row>, cur: Int): Int {
            var lo = 0
            var hi = rows.size - 1
            while (lo < hi) { // the last row starting at or before cur
                val mid = (lo + hi + 1) / 2
                if (rows[mid].start <= cur) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}

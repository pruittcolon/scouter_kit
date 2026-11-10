package scouterkit.ssh

import java.util.Random

// The preview mode's title cards: a full-screen animation in the terminal's own cells (as sharp as the glasses'
// text and small to send), about 1.9 s: a CRT line switches on, a frame opens from it, a scan sweeps the title in
// as big block letters with a shadow, the card holds (the tag, the line under it, how far the tour is), then it
// tears, squeezes back to a line and goes out. Only characters the glasses' font has (Departure Mono's cmap and
// the block elements). One frame per call; PLAN says which and when.
object Intro {
    // The frames in order and the wait before each (ms). Frame 5 is the card itself: it stands for the 1100 ms
    // before frame 6.
    val PLAN: List<Pair<Int, Long>> = listOf(0 to 0L, 1 to 70L, 2 to 80L, 3 to 80L, 4 to 80L, 5 to 80L,
        6 to 1100L, 7 to 90L, 8 to 70L)
    const val END_WAIT = 70L // after the last frame, before the card goes

    private const val FILL = '█'
    private const val SHADOW = '░'
    private const val NOISE = "01<>/\\|:;=+*#%░▒"

    // 5 x 7 letters.
    private val FONT: Map<Char, List<String>> = mapOf(
        'A' to "01110 10001 10001 11111 10001 10001 10001", 'B' to "11110 10001 10001 11110 10001 10001 11110",
        'C' to "01111 10000 10000 10000 10000 10000 01111", 'D' to "11110 10001 10001 10001 10001 10001 11110",
        'E' to "11111 10000 10000 11110 10000 10000 11111", 'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01111 10000 10000 10011 10001 10001 01111", 'H' to "10001 10001 10001 11111 10001 10001 10001",
        'I' to "11111 00100 00100 00100 00100 00100 11111", 'J' to "00111 00010 00010 00010 00010 10010 01100",
        'K' to "10001 10010 10100 11000 10100 10010 10001", 'L' to "10000 10000 10000 10000 10000 10000 11111",
        'M' to "10001 11011 10101 10101 10001 10001 10001", 'N' to "10001 11001 10101 10011 10001 10001 10001",
        'O' to "01110 10001 10001 10001 10001 10001 01110", 'P' to "11110 10001 10001 11110 10000 10000 10000",
        'Q' to "01110 10001 10001 10001 10101 10010 01101", 'R' to "11110 10001 10001 11110 10100 10010 10001",
        'S' to "01111 10000 10000 01110 00001 00001 11110", 'T' to "11111 00100 00100 00100 00100 00100 00100",
        'U' to "10001 10001 10001 10001 10001 10001 01110", 'V' to "10001 10001 10001 10001 10001 01010 00100",
        'W' to "10001 10001 10001 10101 10101 11011 10001", 'X' to "10001 10001 01010 00100 01010 10001 10001",
        'Y' to "10001 10001 01010 00100 00100 00100 00100", 'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '0' to "01110 10011 10101 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00110 01000 10000 11111", '3' to "11110 00001 00001 01110 00001 00001 11110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
        '+' to "00000 00100 00100 11111 00100 00100 00000", '-' to "00000 00000 00000 11111 00000 00000 00000",
        '.' to "00000 00000 00000 00000 00000 01100 01100", '/' to "00001 00010 00010 00100 01000 01000 10000",
        ':' to "00000 01100 01100 00000 01100 01100 00000", '!' to "00100 00100 00100 00100 00100 00000 00100",
        ' ' to "00000 00000 00000 00000 00000 00000 00000",
    ).mapValues { it.value.split(" ") }

    // The title in words, as many per line as fit (6 cells a letter), three lines at most.
    private fun wrap(title: String, cols: Int): List<String> {
        val max = ((cols - 6) / 6).coerceAtLeast(4)
        val out = mutableListOf<String>()
        var line = ""
        for (w in title.uppercase().split(" ").filter { it.isNotEmpty() }) {
            val word = w.take(max)
            if (line.isEmpty()) line = word
            else if (line.length + 1 + word.length <= max) line += " $word"
            else { out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        return out.take(3)
    }

    private class Grid(val cols: Int, val rows: Int) {
        val c = Array(rows) { CharArray(cols) { ' ' } }
        val lit = BooleanArray(rows) // a line with something bright on it (the rest are drawn faint)
        fun put(y: Int, x: Int, ch: Char, bright: Boolean = true) {
            if (y !in 0 until rows || x !in 0 until cols) return
            c[y][x] = ch
            if (bright) lit[y] = true
        }
        fun text(y: Int, x: Int, s: String) { for ((i, ch) in s.withIndex()) put(y, x + i, ch) }
        fun box(top: Int, bottom: Int) {
            for (x in 1 until cols - 1) { put(top, x, '═'); put(bottom, x, '═') }
            for (y in top + 1 until bottom) { put(y, 0, '║'); put(y, cols - 1, '║') }
            put(top, 0, '╔'); put(top, cols - 1, '╗'); put(bottom, 0, '╚'); put(bottom, cols - 1, '╝')
        }
    }

    // One frame (Intro.PLAN's numbers): rows x cols cells, and a mark for each line (1 bright, 2 faint).
    // index / total: which step of the tour this card opens, for its tag and the squares under it.
    fun frame(cols: Int, rows: Int, title: String, sub: String, index: Int, total: Int, step: Int): Pair<List<String>, List<Int>> {
        val g = Grid(cols, rows)
        val rnd = Random(step * 7919L + title.hashCode())
        val mid = rows / 2
        // Where the letters go: centred between the tag line and the line under the title.
        val lines = wrap(title, cols)
        val h = lines.size * 8 - 1
        val top = ((rows - h) / 2).coerceIn(3, maxOf(3, rows - h - 3))
        val mask = Array(rows) { BooleanArray(cols) }
        var left0 = cols
        var right0 = 0
        for ((li, text) in lines.withIndex()) {
            val width = text.length * 6 - 1
            val left = ((cols - width) / 2).coerceAtLeast(2)
            left0 = minOf(left0, left)
            right0 = maxOf(right0, left + width)
            for ((ci, ch) in text.withIndex()) {
                val glyph = FONT[ch] ?: FONT.getValue(' ')
                for (r in 0 until 7) for (k in 0 until 5) {
                    val y = top + li * 8 + r
                    val x = left + ci * 6 + k
                    if (y in 0 until rows && x in 0 until cols && glyph[r][k] == '1') mask[y][x] = true
                }
            }
        }
        fun letters(upTo: Int, ch: Char, shift: (Int) -> Int = { 0 }) {
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!mask[y][x] || x > upTo) continue
                val sx = x + shift(y)
                if (sx + 1 < cols && y + 1 < rows && !mask[y + 1].getOrElse(sx + 1 - shift(y)) { false })
                    g.put(y + 1, sx + 1, SHADOW, false) // the shadow, one cell down and right
                g.put(y, sx, ch)
            }
        }
        fun noise(density: Double, y0: Int = 1, y1: Int = rows - 2) {
            for (y in y0..y1) for (x in 1 until cols - 1)
                if (rnd.nextDouble() < density) g.put(y, x, NOISE[rnd.nextInt(NOISE.length)], false)
        }
        val tag = "⟨ VOX // PREVIEW %02d/%02d ⟩".format(index, total)
        val squares = (1..total).joinToString(" ") { if (it <= index) "■" else "□" }
        fun dressing() {
            g.box(0, rows - 1)
            g.text(1, 2, tag)
            g.text(1, cols - 2 - squares.length, squares)
            val s = "· ${sub.uppercase()} ·".take(cols - 4)
            g.text(rows - 2, (cols - s.length) / 2, s)
        }
        when (step) {
            0 -> { // a CRT line switches on, brightest in the middle
                for (x in cols / 6 until cols - cols / 6) g.put(mid, x, '─')
                for (x in cols / 2 - 3..cols / 2 + 3) g.put(mid, x, '═')
                g.put(mid, cols / 2, FILL)
            }
            1 -> { // the frame opens from the line, static inside
                val half = maxOf(2, rows / 4)
                g.box(mid - half, mid + half)
                noise(0.10, mid - half + 1, mid + half - 1)
            }
            2, 3, 4 -> { // the scan sweeps the title in; past it, static thinning out
                val f = (step - 1) / 3.0
                val sweep = left0 + ((right0 - left0 + 2) * f).toInt()
                noise(0.06 * (1 - f), 2, rows - 3)
                letters(sweep, if (step == 4) '▓' else FILL)
                for (y in maxOf(2, top - 1)..minOf(rows - 3, top + h)) {
                    if (step < 4) {
                        g.put(y, sweep, '▓'); g.put(y, sweep + 1, '▒'); g.put(y, sweep + 2, '░', false)
                    }
                }
                dressing()
                if (step == 4) { // one tear across the letters before they settle
                    val y = top + rnd.nextInt(maxOf(1, h))
                    val row = g.c[y].copyOf()
                    for (x in 1 until cols - 1) g.c[y][x] = row.getOrElse(x - 2) { ' ' }.let { if (it == '║') ' ' else it }
                }
            }
            5 -> { letters(cols, FILL); dressing() } // the card
            6 -> { // the tear: rows slide sideways, some go dark
                letters(cols, FILL) { y -> if (y % 3 == 0) 2 else if (y % 3 == 1) -2 else 0 }
                dressing()
                for (y in 2 until rows - 2) if (rnd.nextDouble() < 0.25) java.util.Arrays.fill(g.c[y], 1, cols - 1, ' ')
            }
            7 -> { // squeezed to a line
                for (x in 2 until cols - 2) g.put(mid, x, '═')
                g.put(mid - 1, cols / 2, '·', false); g.put(mid + 1, cols / 2, '·', false)
            }
            else -> { // a dot, then nothing
                for (x in cols / 2 - 4..cols / 2 + 4) g.put(mid, x, '─')
                g.put(mid, cols / 2, FILL)
            }
        }
        return g.c.map { String(it) } to g.lit.map { if (it) 1 else 2 }
    }
}

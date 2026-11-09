package scouterkit.ssh

import java.util.Random

// The preview mode's title cards: a full-screen animation in the terminal's own cells (so it is as sharp as the
// glasses' text and cheap to send): noise decoding into big block letters, a frame closing round them, a tag and
// a line of words, then the picture collapsing to a line (as a CRT). One frame per call.
object Intro {
    const val STEPS = 12 // 0..5 decode, 6..7 hold, 8..11 collapse
    private const val FILL = '█'
    private const val NOISE = "01<>/\\|#%&*+=:;"

    // 5 x 7 letters.
    private val FONT: Map<Char, List<String>> = run {
        val g = mapOf(
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
        )
        g.mapValues { it.value.split(" ") }
    }

    // The title in words, as many per line as fit (6 cells a letter).
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
        return out
    }

    // One frame: rows x cols cells, and a mark for each line (1 bright, 0 normal: a flicker while it decodes).
    fun frame(cols: Int, rows: Int, title: String, tag: String, sub: String, step: Int): Pair<List<String>, List<Int>> {
        val g = Array(rows) { CharArray(cols) { ' ' } }
        val rnd = Random(step * 7919L + title.hashCode())
        val lines = wrap(title, cols).take(3)
        val h = lines.size * 8 - 1
        val top = ((rows - h) / 2 - 1).coerceAtLeast(3)
        val mask = Array(rows) { BooleanArray(cols) }
        for ((li, text) in lines.withIndex()) {
            val width = text.length * 6 - 1
            val left = ((cols - width) / 2).coerceAtLeast(1)
            for ((ci, ch) in text.withIndex()) {
                val glyph = FONT[ch] ?: FONT[' ']!!
                for (r in 0 until 7) for (c in 0 until 5) {
                    val y = top + li * 8 + r
                    val x = left + ci * 6 + c
                    if (y in 0 until rows && x in 0 until cols && glyph[r][c] == '1') mask[y][x] = true
                }
            }
        }
        val decode = step.coerceAtMost(5)
        val p = if (step >= 5) 1.0 else (decode + 1) / 6.0
        for (y in 0 until rows) for (x in 0 until cols) {
            g[y][x] = when {
                mask[y][x] -> if (rnd.nextDouble() < p * 1.25) FILL else NOISE[rnd.nextInt(NOISE.length)]
                step < 5 && rnd.nextDouble() < 0.30 * Math.pow(1 - p, 1.5) -> NOISE[rnd.nextInt(NOISE.length)]
                else -> ' '
            }
        }
        // The frame closing in: corners first, then the sides.
        if (step >= 2) {
            val reach = if (step >= 5) 1.0 else (step - 1) / 4.0
            val w = (cols * reach).toInt()
            for (x in 0 until w) {
                g[0][x] = '─'; g[rows - 1][cols - 1 - x] = '─'
            }
            val v = (rows * reach).toInt()
            for (y in 0 until v) {
                g[y][cols - 1] = '│'; g[rows - 1 - y][0] = '│'
            }
            g[0][0] = '┌'; g[0][cols - 1] = '┐'; g[rows - 1][0] = '└'; g[rows - 1][cols - 1] = '┘'
        }
        if (step >= 4) {
            put(g, 1, 2, tag)
            put(g, rows - 2, (cols - sub.length) / 2, sub.take(cols - 4))
            put(g, 1, cols - 12, "[ VOX ]")
        }
        // The collapse: the picture closes to a line, then goes.
        val marks = MutableList(rows) { if (step < 5) (if ((it + step) % 2 == 0) 1 else 0) else 1 }
        if (step >= 8) {
            val k = (step - 7) / 4.0
            val gone = ((rows / 2) * k).toInt()
            for (y in 0 until rows) {
                if (y < gone || y >= rows - gone) java.util.Arrays.fill(g[y], ' ')
            }
            if (step == 11) {
                for (y in 0 until rows) java.util.Arrays.fill(g[y], ' ')
                val mid = rows / 2
                for (x in cols / 4 until cols - cols / 4) g[mid][x] = '─'
            }
        }
        return g.map { String(it) } to marks
    }

    private fun put(g: Array<CharArray>, y: Int, x: Int, s: String) {
        if (y !in g.indices) return
        for ((i, c) in s.withIndex()) if (x + i in g[y].indices && x + i >= 0) g[y][x + i] = c
    }
}

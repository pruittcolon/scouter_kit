package scouterkit.ssh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Listen mode's memory (listen.db, the app's private files): conversations (a new one after 5 min without speech),
// their lines (text, word times, who, how), the people Vox knows by voice and the voices it has not been told about.
// lines_fts: the lines' words for searching (FTS4, English stemming).
class ListenDb private constructor(context: Context) : SQLiteOpenHelper(context, "listen.db", null, 4) {
    class Line(
        val id: Long, val conv: Long, val start: Long, val end: Long, val text: String,
        val person: String, val voice: String, val quiet: Boolean, val emotion: String, val sound: String,
    )

    class Conversation(val id: Long, val started: Long, val ended: Long, val title: String, val meeting: Boolean)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER NOT NULL, " +
            "title TEXT NOT NULL DEFAULT '', meeting INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE lines(id INTEGER PRIMARY KEY, conv INTEGER NOT NULL, start INTEGER NOT NULL, " +
            "end INTEGER NOT NULL, text TEXT NOT NULL, words TEXT NOT NULL DEFAULT '', person TEXT NOT NULL DEFAULT '', " +
            "voice TEXT NOT NULL DEFAULT '', quiet INTEGER NOT NULL DEFAULT 0, stage INTEGER NOT NULL DEFAULT 0, " +
            "emotion TEXT NOT NULL DEFAULT '', sound TEXT NOT NULL DEFAULT '', print BLOB)")
        db.execSQL("CREATE INDEX lines_start ON lines(start)")
        db.execSQL("CREATE INDEX lines_conv ON lines(conv)")
        db.execSQL("CREATE VIRTUAL TABLE lines_fts USING fts4(text, tokenize=porter)")
        db.execSQL("CREATE TABLE people(id TEXT PRIMARY KEY, name TEXT NOT NULL, soft INTEGER NOT NULL DEFAULT 0, " +
            "centroid BLOB, patterns BLOB, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY, person TEXT NOT NULL, print BLOB NOT NULL, " +
            "source TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE negatives(id INTEGER PRIMARY KEY, person TEXT NOT NULL, print BLOB NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE voices(id TEXT PRIMARY KEY, label TEXT NOT NULL, centroid BLOB NOT NULL, " +
            "count INTEGER NOT NULL, updated INTEGER NOT NULL)")
        v2(db)
        v3(db)
        v4(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) v2(db)
        if (oldVersion < 3) v3(db)
        if (oldVersion < 4) v4(db)
    }

    // Version 4: how a line was said (loudness dBFS, words a second), meetings and their notes.
    private fun v4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE lines ADD COLUMN loud REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lines ADD COLUMN rate REAL NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE meetings(id INTEGER PRIMARY KEY, title TEXT NOT NULL, started INTEGER NOT NULL, " +
            "ended INTEGER NOT NULL DEFAULT 0, people TEXT NOT NULL DEFAULT '', summary TEXT NOT NULL DEFAULT '', " +
            "file TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, meeting INTEGER NOT NULL, at INTEGER NOT NULL, " +
            "kind TEXT NOT NULL, text TEXT NOT NULL)")
    }

    // Version 3: a line's meaning vector (EmbeddingGemma, Llm.pack), for search by meaning.
    private fun v3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE lines ADD COLUMN vec BLOB")
    }

    // Version 2: which voiceprint model made each print (prints of different models do not compare).
    private fun v2(db: SQLiteDatabase) {
        for (t in listOf("lines", "samples", "negatives", "voices")) db.execSQL("ALTER TABLE $t ADD COLUMN model TEXT NOT NULL DEFAULT ''")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    // ------------------------------------------------ lines

    // A heard line, in the conversation going on (or a new one after GAP_MS of quiet).
    @Synchronized
    fun add(start: Long, end: Long, text: String, words: String, quiet: Boolean): Line {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var conv = -1L
            // The conversation this moment belongs to (lines may come out of order while catching up).
            db.rawQuery("SELECT id FROM conversations WHERE started - ? <= ? AND ended + ? >= ? ORDER BY ended DESC LIMIT 1",
                arrayOf(GAP_MS.toString(), start.toString(), GAP_MS.toString(), start.toString())).use { c ->
                if (c.moveToFirst()) conv = c.getLong(0)
            }
            if (conv < 0) conv = db.insert("conversations", null, ContentValues().apply {
                put("started", start)
                put("ended", end)
            })
            else db.execSQL("UPDATE conversations SET ended = MAX(ended, ?), started = MIN(started, ?) WHERE id = ?", arrayOf<Any>(end, start, conv))
            val id = db.insert("lines", null, ContentValues().apply {
                put("conv", conv)
                put("start", start)
                put("end", end)
                put("text", text)
                put("words", words)
                put("quiet", if (quiet) 1 else 0)
            })
            db.insert("lines_fts", null, ContentValues().apply {
                put("docid", id)
                put("text", text)
            })
            db.setTransactionSuccessful()
            return Line(id, conv, start, end, text, "", "", quiet, "", "")
        } finally {
            db.endTransaction()
        }
    }

    private fun lines(where: String, args: Array<String>, order: String, limit: Int): List<Line> {
        val out = ArrayList<Line>()
        readableDatabase.rawQuery("SELECT id, conv, start, end, text, person, voice, quiet, emotion, sound FROM lines " +
            "$where ORDER BY $order LIMIT $limit", args).use { c ->
            while (c.moveToNext()) out += Line(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4),
                c.getString(5), c.getString(6), c.getInt(7) != 0, c.getString(8), c.getString(9))
        }
        return out
    }

    // The newest n lines, oldest first.
    fun recent(n: Int): List<Line> = lines("", emptyArray(), "start DESC", n).reversed()

    // Lines between two times (wall clock), oldest first.
    fun between(from: Long, to: Long): List<Line> = lines("WHERE start >= ? AND start < ?", arrayOf(from.toString(), to.toString()),
        "start", 100_000)

    fun count(from: Long, to: Long = Long.MAX_VALUE): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM lines WHERE start >= ? AND start < ?", arrayOf(from.toString(), to.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun conversations(from: Long, to: Long): List<Conversation> {
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery("SELECT id, started, ended, title, meeting FROM conversations WHERE ended >= ? AND started < ? " +
            "ORDER BY started", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Conversation(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4) != 0)
        }
        return out
    }

    // Lines holding the words (each word, or a word starting so for the last one), newest first.
    fun search(query: String, limit: Int = 100): List<Line> {
        val words = query.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}']+")).map { it.replace("'", "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val match = words.mapIndexed { i, w -> if (i == words.size - 1) "$w*" else w }.joinToString(" ")
        val ids = ArrayList<Long>()
        runCatching {
            readableDatabase.rawQuery("SELECT docid FROM lines_fts WHERE lines_fts MATCH ? ORDER BY docid DESC LIMIT $limit",
                arrayOf(match)).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        }
        if (ids.isEmpty()) return emptyList()
        return lines("WHERE id IN (${ids.joinToString(",")})", emptyArray(), "start DESC", limit)
    }

    // The people's names by id ("" none).
    fun names(): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT id, name FROM people", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        readableDatabase.rawQuery("SELECT id, label FROM voices", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    // ------------------------------------------------ who spoke (Speakers.kt)

    @Synchronized
    fun setSpeaker(line: Long, person: String, voice: String, print: FloatArray?, model: String) {
        writableDatabase.update("lines", ContentValues().apply {
            put("person", person)
            put("voice", voice)
            if (print != null) {
                put("print", Speakers.blob(print))
                put("model", model)
            }
        }, "id = ?", arrayOf(line.toString()))
    }

    // The people with their samples and "not this person" examples of the model.
    fun people(model: String): List<Speakers.Person> {
        val out = ArrayList<Speakers.Person>()
        readableDatabase.rawQuery("SELECT id, name, soft FROM people ORDER BY created", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val samples = Speakers.keepClean(prints("samples", id, model))
                out += Speakers.Person(id, c.getString(1), c.getInt(2) != 0, Speakers.mean(samples), Speakers.patterns(samples),
                    prints("negatives", id, model).takeLast(20))
            }
        }
        return out
    }

    private fun prints(table: String, person: String, model: String): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        readableDatabase.rawQuery("SELECT print FROM $table WHERE person = ? AND model = ? ORDER BY created DESC LIMIT 200",
            arrayOf(person, model)).use { c -> while (c.moveToNext()) Speakers.floats(c.getBlob(0))?.let { out += it } }
        return out.reversed()
    }

    fun samples(person: String, model: String) = prints("samples", person, model)

    fun sampleCount(person: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE person = ?", arrayOf(person))
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    fun addPerson(name: String, soft: Boolean, id: String = java.util.UUID.randomUUID().toString().take(8)): String {
        writableDatabase.insertWithOnConflict("people", null, ContentValues().apply {
            put("id", id)
            put("name", name.trim())
            put("soft", if (soft) 1 else 0)
            put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun personNamed(name: String): String? = readableDatabase.rawQuery("SELECT id FROM people WHERE lower(name) = lower(?)",
        arrayOf(name.trim())).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun personList(): List<Triple<String, String, Boolean>> {
        val out = ArrayList<Triple<String, String, Boolean>>()
        readableDatabase.rawQuery("SELECT id, name, soft FROM people ORDER BY created", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getInt(2) != 0)
        }
        return out
    }

    @Synchronized
    fun setSoft(person: String, soft: Boolean) = writableDatabase.execSQL("UPDATE people SET soft = ? WHERE id = ?",
        arrayOf<Any>(if (soft) 1 else 0, person))

    @Synchronized
    fun rename(person: String, name: String) = writableDatabase.execSQL("UPDATE people SET name = ? WHERE id = ?", arrayOf<Any>(name.trim(), person))

    // A person forgotten: their samples go, their lines keep the words without a name.
    @Synchronized
    fun deletePerson(person: String) {
        val db = writableDatabase
        db.delete("samples", "person = ?", arrayOf(person))
        db.delete("negatives", "person = ?", arrayOf(person))
        db.delete("people", "id = ?", arrayOf(person))
        db.execSQL("UPDATE lines SET person = '' WHERE person = ?", arrayOf(person))
    }

    @Synchronized
    fun addSamples(person: String, prints: List<FloatArray>, source: String, model: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in prints) db.insert("samples", null, ContentValues().apply {
                put("person", person)
                put("print", Speakers.blob(p))
                put("source", source)
                put("created", System.currentTimeMillis())
                put("model", model)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addNegative(person: String, print: FloatArray, model: String) {
        writableDatabase.insert("negatives", null, ContentValues().apply {
            put("person", person)
            put("print", Speakers.blob(print))
            put("created", System.currentTimeMillis())
            put("model", model)
        })
    }

    fun voices(model: String): MutableList<Speakers.Voice> {
        val out = ArrayList<Speakers.Voice>()
        readableDatabase.rawQuery("SELECT id, label, centroid, count, updated FROM voices WHERE model = ? ORDER BY updated",
            arrayOf(model)).use { c ->
            while (c.moveToNext()) Speakers.floats(c.getBlob(2))?.let {
                out += Speakers.Voice(c.getString(0), c.getString(1), it, c.getInt(3), c.getLong(4))
            }
        }
        return out
    }

    @Synchronized
    fun saveVoice(v: Speakers.Voice, model: String) {
        writableDatabase.insertWithOnConflict("voices", null, ContentValues().apply {
            put("id", v.id)
            put("label", v.label)
            put("centroid", Speakers.blob(v.centroid))
            put("count", v.count)
            put("updated", v.updated)
            put("model", model)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // A voice group becomes a person: its lines get the name, its lines' prints become the person's samples.
    @Synchronized
    fun nameVoice(voice: String, person: String) {
        val db = writableDatabase
        val prints = ArrayList<Pair<FloatArray, String>>()
        db.rawQuery("SELECT print, model FROM lines WHERE voice = ? AND print IS NOT NULL ORDER BY start DESC LIMIT 40", arrayOf(voice)).use { c ->
            while (c.moveToNext()) Speakers.floats(c.getBlob(0))?.let { prints += it to c.getString(1) }
        }
        for ((model, list) in prints.groupBy { it.second }) addSamples(person, list.map { it.first }, "named", model)
        db.execSQL("UPDATE lines SET person = ?, voice = '' WHERE voice = ?", arrayOf<Any>(person, voice))
        db.delete("voices", "id = ?", arrayOf(voice))
    }

    // One line said by a person after all (its print teaches them); or by nobody known.
    @Synchronized
    fun assignLine(line: Long, person: String) {
        val db = writableDatabase
        db.rawQuery("SELECT print, model, person FROM lines WHERE id = ?", arrayOf(line.toString())).use { c ->
            if (c.moveToFirst()) Speakers.floats(c.getBlob(0))?.let { p ->
                val was = c.getString(2)
                if (was.isNotEmpty() && was != person) addNegative(was, p, c.getString(1))
                if (person.isNotEmpty()) addSamples(person, listOf(p), "corrected", c.getString(1))
            }
        }
        db.execSQL("UPDATE lines SET person = ?, voice = '' WHERE id = ?", arrayOf<Any>(person, line))
    }

    // Two voice groups that are one person: the later one's lines join the earlier one.
    @Synchronized
    fun mergeVoice(from: String, into: String) {
        writableDatabase.execSQL("UPDATE lines SET voice = ? WHERE voice = ?", arrayOf<Any>(into, from))
        writableDatabase.delete("voices", "id = ?", arrayOf(from))
    }

    // The voice groups heard, the newest first, with their line count and newest lines.
    fun voiceList(): List<Triple<Speakers.Voice, Int, List<String>>> {
        val out = ArrayList<Triple<Speakers.Voice, Int, List<String>>>()
        readableDatabase.rawQuery("SELECT id, label, centroid, count, updated FROM voices ORDER BY updated DESC LIMIT 40", null).use { c ->
            while (c.moveToNext()) {
                val v = Speakers.Voice(c.getString(0), c.getString(1), Speakers.floats(c.getBlob(2)) ?: FloatArray(0), c.getInt(3), c.getLong(4))
                val lines = ArrayList<String>()
                var n = 0
                readableDatabase.rawQuery("SELECT text FROM lines WHERE voice = ? ORDER BY start DESC", arrayOf(v.id)).use { l ->
                    while (l.moveToNext()) {
                        if (lines.size < 3) lines += l.getString(0)
                        n++
                    }
                }
                if (n > 0) out += Triple(v, n, lines)
            }
        }
        return out
    }

    // The voice groups of a conversation, with their lines' prints (for regrouping when it ends).
    fun conversationVoices(conv: Long): Map<String, List<FloatArray>> {
        val out = HashMap<String, MutableList<FloatArray>>()
        readableDatabase.rawQuery("SELECT voice, print FROM lines WHERE conv = ? AND voice != '' AND print IS NOT NULL",
            arrayOf(conv.toString())).use { c ->
            while (c.moveToNext()) Speakers.floats(c.getBlob(1))?.let { out.getOrPut(c.getString(0)) { ArrayList() } += it }
        }
        return out
    }

    // ------------------------------------------------ meaning vectors (Search.kt)

    // Lines worth a vector (four words or more) without one yet, newest first.
    fun needVectors(limit: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, text FROM lines WHERE vec IS NULL AND length(text) - length(replace(text, ' ', '')) >= 3 " +
            "ORDER BY start DESC LIMIT $limit", null).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getString(1) }
        return out
    }

    @Synchronized
    fun setVector(id: Long, packed: ByteArray) {
        writableDatabase.update("lines", ContentValues().apply { put("vec", packed) }, "id = ?", arrayOf(id.toString()))
    }

    // Every vector with its line id, in id order from `after` on (for the search index).
    fun vectors(after: Long, each: (Long, ByteArray) -> Unit) {
        readableDatabase.rawQuery("SELECT id, vec FROM lines WHERE vec IS NOT NULL AND id > ? ORDER BY id", arrayOf(after.toString())).use { c ->
            while (c.moveToNext()) each(c.getLong(0), c.getBlob(1))
        }
    }

    fun lines(ids: List<Long>): List<Line> = if (ids.isEmpty()) emptyList() else
        lines("WHERE id IN (${ids.joinToString(",")})", emptyArray(), "start", ids.size)

    // The lines around one (the same conversation), for context.
    fun around(id: Long, before: Int, after: Int): List<Line> {
        val l = line(id) ?: return emptyList()
        return lines("WHERE conv = ? AND start < ?", arrayOf(l.conv.toString(), l.start.toString()), "start DESC", before).reversed() +
            listOf(l) + lines("WHERE conv = ? AND start > ?", arrayOf(l.conv.toString(), l.start.toString()), "start", after)
    }

    // The words search ranked: matches of all the words, the best (FTS4's own counts: more of the words, rarer
    // words) first, then newer.
    fun searchRanked(query: String, limit: Int): List<Long> {
        val words = query.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}']+")).map { it.replace("'", "") }.filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, Double>>()
        for (match in listOf(words.joinToString(" "), words.joinToString(" OR "))) {
            runCatching {
                readableDatabase.rawQuery("SELECT docid, matchinfo(lines_fts, 'pcnalx') FROM lines_fts WHERE lines_fts MATCH ? " +
                    "ORDER BY docid DESC LIMIT 2000", arrayOf(match)).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (out.any { it.first == id }) continue
                        out += id to bm25(c.getBlob(1))
                    }
                }
            }
            if (out.size >= limit) break
        }
        return out.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // BM25 from FTS4's matchinfo('pcnalx'): p phrases, c columns, n rows, a average length, l this length, x hits.
    private fun bm25(info: ByteArray): Double {
        val bb = java.nio.ByteBuffer.wrap(info).order(java.nio.ByteOrder.nativeOrder())
        val ints = IntArray(info.size / 4) { bb.int }
        val p = ints[0]
        val cols = ints[1]
        val n = ints[2].toDouble()
        val avg = ints[3].toDouble().coerceAtLeast(1.0)
        val len = ints[3 + cols].toDouble()
        var score = 0.0
        for (i in 0 until p) {
            val at = 3 + 2 * cols + i * 3 * cols
            val hits = ints[at].toDouble()
            val docs = ints[at + 2].toDouble()
            val idf = kotlin.math.ln(1 + (n - docs + 0.5) / (docs + 0.5))
            score += idf * hits * 2.2 / (hits + 1.2 * (0.25 + 0.75 * len / avg))
        }
        return score
    }

    // ------------------------------------------------ tone and meetings (Words.kt, Meetings.kt)

    @Synchronized
    fun setTone(id: Long, emotion: String, sound: String, loud: Float, rate: Float) {
        writableDatabase.update("lines", ContentValues().apply {
            put("emotion", emotion)
            put("sound", sound)
            put("loud", loud.toDouble())
            put("rate", rate.toDouble())
        }, "id = ?", arrayOf(id.toString()))
    }

    class Meeting(val id: Long, val title: String, val started: Long, val ended: Long, val people: String, val summary: String,
                  val file: String)

    @Synchronized
    fun meetingStart(title: String, started: Long, people: String): Long = writableDatabase.insert("meetings", null, ContentValues().apply {
        put("title", title)
        put("started", started)
        put("people", people)
    })

    @Synchronized
    fun meetingEnd(id: Long, ended: Long) = writableDatabase.execSQL("UPDATE meetings SET ended = ? WHERE id = ?", arrayOf<Any>(ended, id))

    @Synchronized
    fun meetingSummary(id: Long, summary: String, file: String) = writableDatabase.execSQL(
        "UPDATE meetings SET summary = ?, file = ? WHERE id = ?", arrayOf<Any>(summary, file, id))

    fun meetings(limit: Int = 30): List<Meeting> {
        val out = ArrayList<Meeting>()
        readableDatabase.rawQuery("SELECT id, title, started, ended, people, summary, file FROM meetings ORDER BY started DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += Meeting(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4),
                c.getString(5), c.getString(6))
        }
        return out
    }

    fun meeting(id: Long): Meeting? = meetings(1000).firstOrNull { it.id == id }

    @Synchronized
    fun addNote(meeting: Long, at: Long, kind: String, text: String) {
        writableDatabase.insert("notes", null, ContentValues().apply {
            put("meeting", meeting)
            put("at", at)
            put("kind", kind)
            put("text", text)
        })
    }

    // A meeting's notes: (time, kind: bookmark / action / note, text), in time order.
    fun notes(meeting: Long): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        readableDatabase.rawQuery("SELECT at, kind, text FROM notes WHERE meeting = ? ORDER BY at", arrayOf(meeting.toString())).use { c ->
            while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    // A line cut where the speaker changed: it goes, its parts come in its place (same conversation), each with
    // its words, who and print. The new ids.
    class NewPart(val start: Long, val end: Long, val text: String, val words: String, val person: String, val voice: String,
                  val print: FloatArray?, val model: String)

    @Synchronized
    fun replaceLine(id: Long, parts: List<NewPart>): List<Long> {
        val db = writableDatabase
        val old = line(id) ?: return emptyList()
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            db.delete("lines", "id = ?", arrayOf(id.toString()))
            db.delete("lines_fts", "docid = ?", arrayOf(id.toString()))
            for (p in parts) {
                val n = db.insert("lines", null, ContentValues().apply {
                    put("conv", old.conv)
                    put("start", p.start)
                    put("end", p.end)
                    put("text", p.text)
                    put("words", p.words)
                    put("person", p.person)
                    put("voice", p.voice)
                    put("quiet", if (old.quiet) 1 else 0)
                    put("stage", 1)
                    if (p.print != null) {
                        put("print", Speakers.blob(p.print))
                        put("model", p.model)
                    }
                })
                db.insert("lines_fts", null, ContentValues().apply {
                    put("docid", n)
                    put("text", p.text)
                })
                ids += n
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    // ------------------------------------------------ deleting

    // A conversation gone for good: its lines, their search entries, the conversation.
    @Synchronized
    fun deleteConversation(conv: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM lines_fts WHERE docid IN (SELECT id FROM lines WHERE conv = ?)", arrayOf<Any>(conv))
            val n = db.delete("lines", "conv = ?", arrayOf(conv.toString()))
            db.delete("conversations", "id = ?", arrayOf(conv.toString()))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteLine(id: Long) {
        writableDatabase.delete("lines", "id = ?", arrayOf(id.toString()))
        writableDatabase.delete("lines_fts", "docid = ?", arrayOf(id.toString()))
    }

    // Conversations, newest first, each with its line count and first words.
    fun conversationList(limit: Int = 60): List<Triple<Conversation, Int, String>> {
        val out = ArrayList<Triple<Conversation, Int, String>>()
        readableDatabase.rawQuery("SELECT c.id, c.started, c.ended, c.title, c.meeting, (SELECT COUNT(*) FROM lines l WHERE l.conv = c.id), " +
            "(SELECT group_concat(text, ' ') FROM (SELECT text FROM lines l WHERE l.conv = c.id ORDER BY start LIMIT 3)) " +
            "FROM conversations c ORDER BY c.started DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += Triple(Conversation(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4) != 0),
                c.getInt(5), c.getString(6) ?: "")
        }
        return out
    }

    fun line(id: Long): Line? = lines("WHERE id = ?", arrayOf(id.toString()), "start", 1).firstOrNull()

    companion object {
        const val GAP_MS = 5 * 60_000L

        @Volatile private var one: ListenDb? = null
        fun get(c: Context): ListenDb = one ?: synchronized(this) { one ?: ListenDb(c.applicationContext).also { one = it } }

        fun dayStart(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun nextDay(dayStart: Long): Long = Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }
}

// The day's transcript as a plain file in Vox's docs folder (the glasses' editor, Alt+N, and anything that reads
// files): "Transcript 2026-11-10.md", conversation by conversation, a line each. Written again when lines change;
// the database stays what counts (edits to the file are not read back).
object Transcripts {
    fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "docs")

    fun file(c: Context, dayStart: Long) =
        File(dir(c), "Transcript " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart)) + ".md")

    fun write(c: Context, dayStart: Long) {
        val db = ListenDb.get(c)
        val end = ListenDb.nextDay(dayStart)
        val lines = db.between(dayStart, end)
        if (lines.isEmpty()) {
            file(c, dayStart).delete()
            return
        }
        val names = db.names()
        val time = SimpleDateFormat("h:mm:ss a", Locale.US)
        val short = SimpleDateFormat("h:mm a", Locale.US)
        val sb = StringBuilder()
        sb.append("# Transcript ").append(SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date(dayStart))).append("\n\n")
        sb.append("Written by Vox Listen mode. ${lines.size} lines. Edits here are not read back.\n")
        var conv = -1L
        for (l in lines) {
            if (l.conv != conv) {
                conv = l.conv
                val span = lines.filter { it.conv == conv }
                sb.append("\n## ").append(short.format(Date(span.first().start))).append(" - ")
                    .append(short.format(Date(span.last().end))).append(" (").append(span.size).append(" lines)\n\n")
            }
            val who = names[l.person.ifEmpty { l.voice }] ?: ""
            sb.append(time.format(Date(l.start))).append("  ")
            if (who.isNotEmpty()) sb.append(who).append(": ")
            sb.append(l.text)
            if (l.quiet) sb.append(" (quiet)")
            if (l.emotion.isNotEmpty() && l.emotion != "neutral") sb.append(" [").append(l.emotion).append("]")
            if (l.sound.isNotEmpty()) sb.append(" [").append(l.sound).append("]")
            sb.append("\n")
        }
        val f = file(c, dayStart)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.saving")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(sb.toString())
                tmp.delete()
            }
        }
    }
}

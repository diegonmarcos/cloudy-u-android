package com.diegonmarcos.cloudnav.maps.takeout

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared helpers of the #1853 tests: the synthetic fixtures, an in-memory sink, a JDBC SqlExec and the v5 schema as it shipped. */
object TakeoutTestKit {
    fun res(path: String): InputStream = TakeoutTestKit::class.java.getResourceAsStream("/takeout/$path") ?: error("missing fixture $path")
    fun text(path: String): String = res(path).readBytes().toString(Charsets.UTF_8)
    fun dir(): File = File(TakeoutTestKit::class.java.getResource("/takeout")!!.toURI())

    val RECORDS_FILES = listOf("records/Records.json")
    val SEMANTIC_FILES = listOf("semantic/2026/2026_MARCH.json")
    val TIMELINE_FILES = listOf("timeline/Timeline.json", "android/location-history.json")

    /** A Takeout-shaped zip of the fixtures (plus a Settings.json that holds no location data and an html file). */
    fun zip(): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("Takeout/archive_browser.html", "<html></html>".toByteArray())
            put("Takeout/Location History (Timeline)/Records.json", text("records/Records.json").toByteArray())
            put("Takeout/Location History (Timeline)/Semantic Location History/2026/2026_MARCH.json", text("semantic/2026/2026_MARCH.json").toByteArray())
            put("Takeout/Location History (Timeline)/Settings.json", "{\"deviceSettings\":[]}".toByteArray())
            put("Takeout/Location History (Timeline)/Timeline.json", text("timeline/Timeline.json").toByteArray())
            put("Takeout/Location History (Timeline)/location-history.json", text("android/location-history.json").toByteArray())
        }
        return bo.toByteArray()
    }

    class Collect : TakeoutSink {
        val rows = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        val skips = ArrayList<String>()
        override fun emit(table: String, row: Map<String, Any?>) { rows.getOrPut(table) { ArrayList() } += row }
        override fun skipped(format: TakeoutFormat, why: String) { skips += why }
        fun of(table: String) = rows[table].orEmpty()
    }

    class Jdbc(val c: Connection) : SqlExec {
        override fun exec(sql: String, args: List<Any?>) { c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } }
        override fun rows(sql: String, args: List<Any?>): List<List<Any?>> = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<List<Any?>>()
                while (rs.next()) out += (1..rs.metaData.columnCount).map { i -> when (val v = rs.getObject(i)) { is Int -> v.toLong(); is Short -> v.toLong(); is Float -> v.toDouble(); else -> v } }
                out
            }
        }
        override fun begin() { c.autoCommit = false }
        override fun commit() { c.commit(); c.autoCommit = true }
        override fun rollback() { c.rollback(); c.autoCommit = true }
        fun count(sql: String): Long = rows(sql)[0][0] as Long
    }

    /** maps.db as v5 left it (the tables the migration touches), in a fresh in-memory SQLite. */
    fun v5(): Jdbc {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        val j = Jdbc(c)
        j.exec("CREATE TABLE fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, speed REAL, provider TEXT NOT NULL DEFAULT 'gps');")
        j.exec("CREATE INDEX idx_fixes_ts ON fixes(ts);")
        j.exec("CREATE TABLE timeline_segments (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, lat REAL, lon REAL, place TEXT, steps INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0, loc_src TEXT, neighborhood TEXT, city TEXT, country TEXT);")
        j.exec("CREATE INDEX idx_timeline_start ON timeline_segments(start_ms);")
        j.exec("CREATE TABLE day_rollups (date TEXT PRIMARY KEY, steps INTEGER NOT NULL, distance_m REAL NOT NULL, moving_ms INTEGER NOT NULL, stopped_ms INTEGER NOT NULL, visits INTEGER NOT NULL, sleep_ms INTEGER NOT NULL, by_mode TEXT NOT NULL);")
        return j
    }

    fun v6(): Jdbc = v5().also { j -> TakeoutSchema.MIGRATE_V6.forEach { j.exec(it) } }

    fun parseAll(files: List<String>, sink: TakeoutSink, t: Tracker = Tracker()): Set<TakeoutFormat> =
        files.flatMap { f -> res(f).reader(Charsets.UTF_8).use { TakeoutParsers.parse(it, sink, t) } }.toSet()

    /** Every leaf path of a fixture, with the prefix each top-level key gets (the same rule FieldSpec is written in). */
    fun leaves(file: String, t: Tracker = Tracker()): Set<String> {
        val out = linkedSetOf<String>()
        val root = JsonPull(res(file).reader()).readValue()
        fun prefix(k: String) = when (k) { "rawSignals" -> "rawSignals[]"; "userLocationProfile" -> "userLocationProfile"; else -> "" }
        when (root) {
            is List<*> -> root.forEach { out += Node(it, "", t).leaves() }
            is Map<*, *> -> for ((k, v) in root) { val p = prefix(k.toString()); if (v is List<*>) v.forEach { out += Node(it, p, t).leaves() } else out += Node(v, p, t).leaves() }
            else -> {}
        }
        return out
    }
}

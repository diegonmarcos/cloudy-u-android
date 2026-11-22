package com.diegonmarcos.cloudnav.maps.takeout

/**
 * #1853 maps.db v6: Google's location history next to the tracked data. Plain SQL strings so an Android SQLiteDatabase and a
 * JVM test (sqlite-jdbc) run the very same statements.
 *
 *  - `fixes`, `timeline_segments`, `day_rollups` gain a `source` column ('tracked' = what the phone recorded, else the import's tag);
 *    imported fixes also keep altitude, vertical accuracy, heading, device tag, Google's own source, activity guesses and the whole record.
 *  - `imp_visits`, `imp_candidates`, `imp_activities`, `imp_signals`, `imp_profile` hold every Google field as typed columns, with the
 *    complete record as `raw_json` (nothing is dropped, and a field Google adds later is still in the raw copy).
 *  - Overlaps: segments carry a `prio` (tracked 0, timeline-export 1, takeout-semantic 2, takeout-records 3); a segment is hidden in the
 *    views only while a lower-prio one overlaps it. Both stay stored, so deleting the tracked day shows the import again.
 */
object TakeoutSchema {
    const val SRC_TRACKED = "tracked"
    const val SRC_RECORDS_DERIVED = "takeout-records"

    val COLS: Map<String, List<String>> = linkedMapOf(
        TkTable.FIXES to listOf("ikey", "ts", "lat", "lon", "accuracy", "speed", "altitude", "vertical_acc", "heading", "provider", "g_source", "device_tag",
            "activity_json", "extra_json", "source", "raw_json"),
        TkTable.VISITS to listOf("key", "import_id", "format", "parent_key", "start_ms", "end_ms", "start_offset_min", "end_offset_min", "lat", "lon", "place_id", "name",
            "address", "semantic_type", "location_confidence", "calibrated_probability", "place_confidence", "visit_confidence", "probability", "visit_type", "importance",
            "edit_status", "center_lat", "center_lon", "device_tag", "hierarchy_level", "timeless", "last_edited_ms", "simplified_path_json", "extra_json", "raw_json"),
        TkTable.CANDIDATES to listOf("visit_key", "rank", "place_id", "name", "address", "semantic_type", "lat", "lon", "location_confidence", "calibrated_probability",
            "device_tag", "raw_json"),
        TkTable.ACTIVITIES to listOf("key", "import_id", "format", "start_ms", "end_ms", "start_offset_min", "end_offset_min", "start_lat", "start_lon", "start_place_id",
            "start_address", "start_name", "start_device_tag", "end_lat", "end_lon", "end_place_id", "end_address", "end_name", "end_device_tag", "distance_m",
            "activity_type", "confidence", "probabilities_json", "waypoints_json", "simplified_path_json", "transit_json", "parking_json", "path_json", "memory_json",
            "edit_status", "last_edited_ms", "extra_json", "raw_json"),
        TkTable.SIGNALS to listOf("key", "import_id", "ts", "kind", "json", "raw_json"),
        TkTable.PROFILE to listOf("k", "import_id", "json"),
    )

    private val TYPES = mapOf("ikey" to "TEXT", "ts" to "INTEGER NOT NULL", "lat" to "REAL", "lon" to "REAL", "key" to "TEXT PRIMARY KEY", "import_id" to "INTEGER", "k" to "TEXT PRIMARY KEY",
        "start_ms" to "INTEGER NOT NULL", "end_ms" to "INTEGER NOT NULL", "rank" to "INTEGER NOT NULL", "visit_key" to "TEXT NOT NULL", "hierarchy_level" to "INTEGER",
        "timeless" to "INTEGER", "last_edited_ms" to "INTEGER", "start_offset_min" to "INTEGER", "end_offset_min" to "INTEGER", "device_tag" to "INTEGER",
        "start_device_tag" to "INTEGER", "end_device_tag" to "INTEGER", "place_confidence" to "", "visit_confidence" to "", "location_confidence" to "", "confidence" to "", "kind" to "TEXT NOT NULL", "format" to "TEXT NOT NULL")
    private val REALS = setOf("accuracy", "speed", "altitude", "vertical_acc", "heading", "calibrated_probability",
        "probability", "center_lat", "center_lon", "start_lat", "start_lon", "end_lat", "end_lon", "distance_m")

    private fun colType(c: String) = TYPES[c] ?: if (c in REALS) "REAL" else "TEXT"

    /** The five import tables (idempotent: IF NOT EXISTS), and the indexes the views and the rebuild use. */
    val CREATE: List<String> = buildList {
        for ((t, cols) in COLS) {
            if (t == TkTable.FIXES) continue
            val pk = if (t == TkTable.CANDIDATES) ", PRIMARY KEY (visit_key, rank)" else ""
            add("CREATE TABLE IF NOT EXISTS $t (" + cols.joinToString(", ") { "$it ${colType(it)}" } + pk + ");")
        }
        add("CREATE INDEX IF NOT EXISTS idx_imp_visits_start ON imp_visits(start_ms);")
        add("CREATE INDEX IF NOT EXISTS idx_imp_visits_place ON imp_visits(place_id);")
        add("CREATE INDEX IF NOT EXISTS idx_imp_activities_start ON imp_activities(start_ms);")
        add("CREATE INDEX IF NOT EXISTS idx_imp_signals_ts ON imp_signals(ts, kind);")
        add("CREATE TABLE IF NOT EXISTS imports (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, started_ms INTEGER NOT NULL, finished_ms INTEGER, status TEXT NOT NULL, " +
            "files INTEGER, days INTEGER, visits INTEGER, activities INTEGER, places INTEGER, fixes INTEGER, signals INTEGER, skipped INTEGER, first_ms INTEGER, last_ms INTEGER, error TEXT);")
    }

    /** v5 to v6 on the existing tables. Run once by onUpgrade (and on a fresh database after its v5 tables exist). */
    val ALTER: List<String> = buildList {
        add("ALTER TABLE fixes ADD COLUMN source TEXT NOT NULL DEFAULT 'tracked';")
        for (c in listOf("altitude REAL", "vertical_acc REAL", "heading REAL", "device_tag INTEGER", "g_source TEXT", "activity_json TEXT", "extra_json TEXT", "raw_json TEXT", "ikey TEXT"))
            add("ALTER TABLE fixes ADD COLUMN $c;")
        add("CREATE UNIQUE INDEX IF NOT EXISTS idx_fixes_ikey ON fixes(ikey) WHERE ikey IS NOT NULL;")
        add("CREATE INDEX IF NOT EXISTS idx_fixes_source_ts ON fixes(source, ts);")
        add("ALTER TABLE timeline_segments ADD COLUMN source TEXT NOT NULL DEFAULT 'tracked';")
        add("ALTER TABLE timeline_segments ADD COLUMN prio INTEGER NOT NULL DEFAULT 0;")
        add("ALTER TABLE timeline_segments ADD COLUMN ref TEXT;")
        add("CREATE INDEX IF NOT EXISTS idx_timeline_prio_start ON timeline_segments(prio, start_ms);")
        add("ALTER TABLE day_rollups ADD COLUMN source TEXT NOT NULL DEFAULT 'tracked';")
    }

    val MIGRATE_V6: List<String> get() = ALTER + CREATE

    /** Tracked fixes only (what the engine segments for the tracked timeline). */
    const val TRACKED_FIXES = "SELECT ts,lat,lon,accuracy,speed,provider FROM fixes WHERE source = 'tracked' AND ts BETWEEN ? AND ? ORDER BY ts ASC;"

    /**
     * The views: the segments overlapping [?1, ?2], except those a lower-prio segment overlaps (tracked beats the imports; a semantic
     * import beats positions derived from raw ones). Moves made only of the step counter (no place) never hide an import.
     * The 7-day bound on `t.start_ms` keeps the lookup on the start_ms index.
     */
    const val VISIBLE_SEGMENTS =
        "SELECT kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,ref FROM timeline_segments s " +
            "WHERE s.end_ms >= ? AND s.start_ms <= ? AND NOT EXISTS (SELECT 1 FROM timeline_segments t WHERE t.prio < s.prio AND t.start_ms < s.end_ms AND t.end_ms > s.start_ms " +
            "AND t.start_ms > s.start_ms - 604800000 AND COALESCE(t.loc_src,'') <> 'steps') ORDER BY s.start_ms ASC;"

    fun insertSql(table: String): String {
        val cols = COLS.getValue(table)
        return (if (table == TkTable.FIXES) "INSERT OR IGNORE" else "INSERT OR REPLACE") + " INTO $table (" + cols.joinToString(",") + ") VALUES (" + cols.joinToString(",") { "?" } + ");"
    }
}

/** What the writer and the rebuild need from a database, so the same code runs on Android SQLite and in a JVM test. */
interface SqlExec {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun rows(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
    fun begin()
    fun commit()
    fun rollback()
}

/** The sink that stores rows: idempotent by stable keys (INSERT OR REPLACE on the key, INSERT OR IGNORE for fixes), batched in transactions. */
class TakeoutWriter(private val sql: SqlExec, private val importId: Long, private val zone: java.time.ZoneId, private val batch: Int = 1000) : TakeoutSink {
    private val sqls = TakeoutSchema.COLS.keys.associateWith { TakeoutSchema.insertSql(it) }
    private var pending = 0
    private val dayIds = HashSet<Long>()
    private val placeIds = HashSet<String>()
    val counts = LinkedHashMap<String, Long>()
    val skipReasons = LinkedHashMap<String, Int>()
    var skippedCount = 0; private set
    var firstMs = Long.MAX_VALUE; private set
    var lastMs = Long.MIN_VALUE; private set
    val days get() = dayIds.size
    val places get() = placeIds.size

    fun begin() { sql.begin(); pending = 0 }

    override fun emit(table: String, row: Map<String, Any?>) {
        val cols = TakeoutSchema.COLS.getValue(table)
        sql.exec(sqls.getValue(table), cols.map { c -> if (c == "import_id") importId else row[c] })
        counts[table] = (counts[table] ?: 0L) + 1
        val t = (row["ts"] ?: row["start_ms"]) as? Long
        if (t != null && t > 0) {
            dayIds += java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay()
            if (t < firstMs) firstMs = t; if (t > lastMs) lastMs = t
        }
        if (table == TkTable.VISITS) (row["place_id"] as? String)?.let { placeIds += it }
        if (++pending >= batch) { sql.commit(); sql.begin(); pending = 0 }
    }

    fun finish(ok: Boolean) { if (ok) sql.commit() else sql.rollback() }

    override fun skipped(format: TakeoutFormat, why: String) { skippedCount++; skipReasons["${format.label}: $why"] = (skipReasons["${format.label}: $why"] ?: 0) + 1 }
}

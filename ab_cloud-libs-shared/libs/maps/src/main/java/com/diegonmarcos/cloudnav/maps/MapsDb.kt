package com.diegonmarcos.cloudnav.maps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed store for the GPS tracker. Three tables:
 *
 *   • points        — every raw GPS sample written by
 *                     [LocationTrackerService]. Lat/lon/accuracy/speed
 *                     + epoch millis. High-volume; index on ts.
 *   • stops         — derived: dwell events (radius + min duration).
 *                     [LocationTrackerService]'s stop-detector emits
 *                     a row on transition from MOVING → STOPPED. Push 3
 *                     fills place_name / neighborhood / city / country
 *                     via the active reverse-geocode + POI providers.
 *   • places_cache  — keyed cache of "what's at this rounded lat/lon"
 *                     so Push 3 doesn't double-call the geocoder for
 *                     the same Stop region. JSON blob; opaque to SQL.
 *
 * Raw SQLite (no Room) so we keep the build free of kapt/ksp plugin
 * commitments. Schema migrations done by bumping [DB_VERSION] and
 * adding an `onUpgrade` branch — kept simple by design.
 */
class MapsDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        INTEGER NOT NULL,
                lat       REAL    NOT NULL,
                lon       REAL    NOT NULL,
                accuracy  REAL,
                speed     REAL,
                bearing   REAL,
                altitude  REAL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_points_ts ON points(ts);")
        db.execSQL(
            """
            CREATE TABLE stops (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at   INTEGER NOT NULL,
                ended_at     INTEGER,
                lat          REAL    NOT NULL,
                lon          REAL    NOT NULL,
                accuracy     REAL,
                place_name   TEXT,
                neighborhood TEXT,
                city         TEXT,
                country      TEXT,
                enriched_at  INTEGER
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_stops_started_at ON stops(started_at);")
        createTimeline(db)
        createFixes(db)
        db.execSQL(
            """
            CREATE TABLE places_cache (
                latlon_key TEXT PRIMARY KEY,
                json       TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTimeline(db)   // v2 (#1114): the timeline store; filled by TimelineSync from the older tables
        if (oldVersion < 3) {   // v3 (#1851): the append-only fix log; the tracker's existing points are carried over once
            createFixes(db)
            db.execSQL("INSERT INTO fixes (ts, lat, lon, accuracy, speed, provider) SELECT ts, lat, lon, accuracy, speed, 'legacy-points' FROM points;")
        }
    }

    /** #1851 every raw fix, appended and never rewritten: time, position, accuracy, speed, provider. The segmentation engine reads only this. */
    private fun createFixes(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, " +
            "accuracy REAL, speed REAL, provider TEXT NOT NULL DEFAULT 'gps');")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fixes_ts ON fixes(ts);")
    }

    /** Appends one fix (an insert, never an update); a fix already stored for the same time and provider is not doubled. */
    fun appendFix(f: Fix): Long {
        val db = writableDatabase
        db.rawQuery("SELECT 1 FROM fixes WHERE ts = ? AND provider = ? LIMIT 1;", arrayOf(f.ts.toString(), f.provider)).use { if (it.moveToFirst()) return -1L }
        return db.insert("fixes", null, ContentValues().apply {
            put("ts", f.ts); put("lat", f.lat); put("lon", f.lon)
            if (f.accuracyM != null) put("accuracy", f.accuracyM) else putNull("accuracy")
            if (f.speedMps != null) put("speed", f.speedMps) else putNull("speed")
            put("provider", f.provider)
        })
    }

    fun fixesBetween(fromTs: Long, toTs: Long): List<Fix> {
        val out = mutableListOf<Fix>()
        readableDatabase.rawQuery("SELECT ts,lat,lon,accuracy,speed,provider FROM fixes WHERE ts BETWEEN ? AND ? ORDER BY ts ASC;",
            arrayOf(fromTs.toString(), toTs.toString())).use { c ->
            while (c.moveToNext()) out.add(Fix(c.getLong(0), c.getDouble(1), c.getDouble(2),
                if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4), c.getString(5) ?: "gps"))
        }
        return out
    }

    private fun createTimeline(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS timeline_segments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, " +
                "lat REAL, lon REAL, place TEXT, steps INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0, loc_src TEXT, " +
                "neighborhood TEXT, city TEXT, country TEXT);"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_timeline_start ON timeline_segments(start_ms);")
    }

    /** THE timeline store: every segment overlapping fromTs..toTs, oldest first. Daily, Stops, Steps and MyTrips read this. */
    fun segmentsBetween(fromTs: Long, toTs: Long): List<TlSegment> {
        val out = mutableListOf<TlSegment>()
        readableDatabase.rawQuery(
            "SELECT kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country FROM timeline_segments " +
                "WHERE end_ms >= ? AND start_ms <= ? ORDER BY start_ms ASC;",
            arrayOf(fromTs.toString(), toTs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(TlSegment(
                TlKind.parse(c.getString(0)), c.getLong(1), c.getLong(2),
                if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4),
                c.getString(5), c.getLong(6), c.getDouble(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11)))
        }
        return out
    }

    fun segmentCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM timeline_segments;", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Replaces everything that ends at or after [fromMs] with [segs] in one transaction (a rebuild of that window). */
    fun replaceSegments(fromMs: Long, segs: List<TlSegment>) = runInTransaction {
        val db = writableDatabase
        db.delete("timeline_segments", "end_ms >= ?", arrayOf(fromMs.toString()))
        for (s in segs) db.insert("timeline_segments", null, ContentValues().apply {
            put("kind", s.kind.name); put("start_ms", s.startMs); put("end_ms", s.endMs)
            if (s.lat != null) put("lat", s.lat) else putNull("lat")
            if (s.lon != null) put("lon", s.lon) else putNull("lon")
            put("place", s.place); put("steps", s.steps); put("distance_m", s.distanceM); put("loc_src", s.locSource)
            put("neighborhood", s.neighborhood); put("city", s.city); put("country", s.country)
        })
    }

    /** Stops that overlap fromTs..toTs (an open stop counts), oldest first: the timeline's input. */
    fun stopsOverlapping(fromTs: Long, toTs: Long): List<RichStop> {
        val out = mutableListOf<RichStop>()
        readableDatabase.rawQuery(
            "SELECT id,started_at,ended_at,lat,lon,place_name,neighborhood,city,country FROM stops " +
                "WHERE started_at <= ? AND (ended_at IS NULL OR ended_at >= ?) ORDER BY started_at ASC;",
            arrayOf(toTs.toString(), fromTs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(RichStop(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2),
                c.getDouble(3), c.getDouble(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8)))
        }
        return out
    }

    /** Raw GPS points fromTs..toTs, oldest first: the timeline's input for move distances. */
    fun pointsBetween(fromTs: Long, toTs: Long): List<PointRow> {
        val out = mutableListOf<PointRow>()
        readableDatabase.rawQuery("SELECT ts,lat,lon,accuracy FROM points WHERE ts BETWEEN ? AND ? ORDER BY ts;",
            arrayOf(fromTs.toString(), toTs.toString())).use { c ->
            while (c.moveToNext()) out.add(PointRow(c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3).toFloat()))
        }
        return out
    }

    fun insertPoint(p: PointRow): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ts", p.ts)
            put("lat", p.lat)
            put("lon", p.lon)
            put("accuracy", p.accuracy)
            put("speed", p.speed)
            put("bearing", p.bearing)
            put("altitude", p.altitude)
        }
        return db.insert("points", null, cv)
    }

    fun insertStop(s: StopRow): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("started_at", s.startedAt)
            put("ended_at", s.endedAt)
            put("lat", s.lat)
            put("lon", s.lon)
            put("accuracy", s.accuracy)
        }
        return db.insert("stops", null, cv)
    }

    /** Most recent N points, oldest first. Used by the map fragment
     *  to draw the user's recent track polyline. */
    fun recentPoints(limit: Int): List<PointRow> {
        val db = readableDatabase
        val out = mutableListOf<PointRow>()
        db.rawQuery(
            "SELECT ts,lat,lon,accuracy,speed,bearing,altitude FROM points ORDER BY ts DESC LIMIT ?;",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(PointRow(
                    ts        = c.getLong(0),
                    lat       = c.getDouble(1),
                    lon       = c.getDouble(2),
                    accuracy  = if (c.isNull(3)) null else c.getDouble(3).toFloat(),
                    speed     = if (c.isNull(4)) null else c.getDouble(4).toFloat(),
                    bearing   = if (c.isNull(5)) null else c.getDouble(5).toFloat(),
                    altitude  = if (c.isNull(6)) null else c.getDouble(6),
                ))
            }
        }
        return out.reversed()
    }

    /** Total stored point count — surfaces in the tracker control
     *  fragment as a "tracking is working" health check. */
    fun pointCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM points;", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun stopCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM stops;", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Stops that still lack `place_name` — drives the
     *  [StopsEnricher] worker. Returns oldest-first so newer Stops
     *  get richer enrichment context (the user's recent area cache
     *  primed first). */
    fun unenrichedStops(limit: Int = 50): List<EnrichableStop> {
        val db = readableDatabase
        val out = mutableListOf<EnrichableStop>()
        db.rawQuery(
            "SELECT id,started_at,lat,lon FROM stops WHERE place_name IS NULL ORDER BY started_at ASC LIMIT ?;",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(EnrichableStop(
                id        = c.getLong(0),
                startedAt = c.getLong(1),
                lat       = c.getDouble(2),
                lon       = c.getDouble(3),
            ))
        }
        return out
    }

    /** Write back the reverse-geocode result for one Stop. Nulls are
     *  allowed for partial responses (e.g. Photon often skips
     *  neighborhood). `enriched_at` marks the attempt time so a
     *  failed enrichment (place_name=null) isn't re-tried on every
     *  pass — see [StopsEnricher.shouldRetry]. */
    fun enrichStop(
        id: Long,
        placeName: String?,
        neighborhood: String?,
        city: String?,
        country: String?,
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("place_name", placeName)
            put("neighborhood", neighborhood)
            put("city", city)
            put("country", country)
            put("enriched_at", System.currentTimeMillis())
        }
        db.update("stops", cv, "id = ?", arrayOf(id.toString()))
    }

    /** Patch ended_at on a Stop row — called by LocationTrackerService
     *  when it observes the user leaving the dwell radius (transition
     *  STOPPED → MOVING). Until this lands, every Stop row sits with
     *  ended_at = NULL forever and the dashboards have to assume the
     *  stop is "still active through now", which skews the longest-
     *  dwell-per-day algorithm in MapsDailyFragment + dwell time math
     *  everywhere else. */
    fun updateStopEndedAt(id: Long, endedAtMs: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("ended_at", endedAtMs) }
        db.update("stops", cv, "id = ?", arrayOf(id.toString()))
    }

    /** Single Stop by row id — used by [LocationTrackerService] on
     *  service revival to restore the in-memory `activeStop` field
     *  from the persisted `MapsTrackerPrefs.activeStopId` so a kill
     *  mid-stop can still patch ended_at on the same row instead of
     *  leaving it dangling and double-emitting a fresh Stop. */
    fun getStop(id: Long): RichStop? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT id,started_at,ended_at,lat,lon,place_name,neighborhood,city,country " +
                "FROM stops WHERE id = ?;",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return RichStop(
                id            = c.getLong(0),
                startedAt     = c.getLong(1),
                endedAt       = if (c.isNull(2)) null else c.getLong(2),
                lat           = c.getDouble(3),
                lon           = c.getDouble(4),
                placeName     = c.getString(5),
                neighborhood  = c.getString(6),
                city          = c.getString(7),
                country       = c.getString(8),
            )
        }
    }

    /** All stops in the half-open range fromTs..toTs, newest first.
     *  Drives the Timeline + Stops + MyTrips dashboards. */
    fun stopsBetween(fromTs: Long, toTs: Long): List<RichStop> {
        val db = readableDatabase
        val out = mutableListOf<RichStop>()
        db.rawQuery(
            "SELECT id,started_at,ended_at,lat,lon,place_name,neighborhood,city,country " +
                "FROM stops WHERE started_at BETWEEN ? AND ? ORDER BY started_at DESC;",
            arrayOf(fromTs.toString(), toTs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(RichStop(
                id            = c.getLong(0),
                startedAt     = c.getLong(1),
                endedAt       = if (c.isNull(2)) null else c.getLong(2),
                lat           = c.getDouble(3),
                lon           = c.getDouble(4),
                placeName     = c.getString(5),
                neighborhood  = c.getString(6),
                city          = c.getString(7),
                country       = c.getString(8),
            ))
        }
        return out
    }

    /** Reverse-geocode cache lookup keyed by rounded lat/lon. Push 3 uses
     *  ~3-decimal-place rounding (≈ 100m grid) so two stops in the same
     *  block share one geocode call. */
    fun cachedReverse(latlonKey: String): String? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT json FROM places_cache WHERE latlon_key = ?;",
            arrayOf(latlonKey),
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun cacheReverse(latlonKey: String, json: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("latlon_key", latlonKey)
            put("json", json)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("places_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Generic TTL cache (same places_cache table, namespaced keys e.g.
     *  `fwd:`/`poi:`). Returns the stored json only if younger than
     *  [maxAgeMs]; null on miss OR when the entry has expired. Used by
     *  [MapsProviderClient] to cache forward-search + POI lookups so the
     *  same query/area isn't re-fetched for ~a month. */
    fun cacheGet(key: String, maxAgeMs: Long): String? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT json, updated_at FROM places_cache WHERE latlon_key = ?;",
            arrayOf(key),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val updatedAt = c.getLong(1)
            if (System.currentTimeMillis() - updatedAt > maxAgeMs) return null
            return c.getString(0)
        }
    }

    /** Write/replace a generic cache entry, stamping updated_at = now. */
    fun cachePut(key: String, json: String) = cacheReverse(key, json)

    /** Runs [block] inside a single SQLite transaction — for bulk writes
     *  (e.g. [MapsDemo.seed]'s thousands of demo rows) this is the difference
     *  between a sub-second insert and a multi-second one, since every
     *  untransacted `insert` otherwise fsyncs individually. */
    fun <T> runInTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Wipe every table — backs Configs → Maps → Reset / Clear data. */
    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM points;")
        db.execSQL("DELETE FROM stops;")
        db.execSQL("DELETE FROM timeline_segments;")
        db.execSQL("DELETE FROM fixes;")
        db.execSQL("DELETE FROM places_cache;")
    }

    data class PointRow(
        val ts: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float? = null,
        val speed: Float? = null,
        val bearing: Float? = null,
        val altitude: Double? = null,
    )

    data class StopRow(
        val startedAt: Long,
        val endedAt: Long? = null,
        val lat: Double,
        val lon: Double,
        val accuracy: Float? = null,
    )

    /** Minimal projection used by [StopsEnricher] when it walks the
     *  unenriched-rows backlog — just the fields needed to fire one
     *  reverse-geocode call and write the result back. */
    data class EnrichableStop(
        val id: Long,
        val startedAt: Long,
        val lat: Double,
        val lon: Double,
    )

    /** Full projection used by [MapsTimelineFragment],
     *  [MapsStopsFragment], [MapsExportFragment], and the MyTrips
     *  dashboards — every column the reverse-geocode enrichment can
     *  populate, plus the original spatial + temporal fields. */
    data class RichStop(
        val id: Long,
        val startedAt: Long,
        val endedAt: Long?,
        val lat: Double,
        val lon: Double,
        val placeName: String?,
        val neighborhood: String?,
        val city: String?,
        val country: String?,
    )

    companion object {
        private const val DB_NAME = "maps.db"
        private const val DB_VERSION = 3

        @Volatile private var instance: MapsDb? = null
        fun get(ctx: Context): MapsDb = instance ?: synchronized(this) {
            instance ?: MapsDb(ctx.applicationContext).also { instance = it }
        }
    }
}

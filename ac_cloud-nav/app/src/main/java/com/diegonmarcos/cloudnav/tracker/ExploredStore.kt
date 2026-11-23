package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import com.diegonmarcos.cloudnav.maps.takeout.SqlExec
import java.time.LocalDate
import java.time.ZoneId

/** #1863 the digests in maps.db's `aggregates` table (kind [KIND], key = the date): the one store, no database of its own. */
class MapsDbDigestBackend(private val app: Context) : DigestBackend {
    private val sql: SqlExec get() = MapsDb.get(app).sqlExec()
    override fun load(): Map<LocalDate, String> = sql.rows("SELECT k, json FROM aggregates WHERE kind = ?;", listOf(KIND))
        .mapNotNull { r -> runCatching { LocalDate.parse(r[0] as String) to (r[1] as String) }.getOrNull() }.toMap()
    override fun put(rows: Map<LocalDate, String>) {
        val s = sql; val now = System.currentTimeMillis()
        s.begin()
        try { for ((d, j) in rows) s.exec("INSERT OR REPLACE INTO aggregates (kind, k, json, built_ms) VALUES (?, ?, ?, ?);", listOf(KIND, d.toString(), j, now)); s.commit() }
        catch (e: Exception) { s.rollback(); throw e }
    }
    override fun remove(dates: Collection<LocalDate>) { val s = sql; for (d in dates) s.exec("DELETE FROM aggregates WHERE kind = ? AND k = ?;", listOf(KIND, d.toString())) }
    override fun clear() = sql.exec("DELETE FROM aggregates WHERE kind = ?;", listOf(KIND))

    companion object { const val KIND = "digest" }
}

/**
 * #1863 Export / Import page actions over maps.db: read the rows of a range, export them, restore an export, delete duplicates, delete by
 * dates, delete all, refresh. Every delete marks the dates it touched dirty, so the next open recomputes only those (delete all clears
 * the cache).
 */
object ExploredStore {
    private fun db(app: Context) = MapsDb.get(app).sqlExec()
    private fun l(x: Any?) = (x as? Number)?.toLong()
    private fun d(x: Any?) = (x as? Number)?.toDouble()
    private fun s(x: Any?) = x as? String

    fun segments(app: Context, fromMs: Long? = null, toMs: Long? = null): List<SegRow> {
        val where = if (fromMs != null && toMs != null) " WHERE end_ms > ? AND start_ms < ?" else ""
        return db(app).rows("SELECT id,kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,ref,prio FROM timeline_segments$where ORDER BY start_ms ASC;",
            if (where.isEmpty()) emptyList() else listOf(fromMs, toMs)).map { r ->
            SegRow(l(r[0])!!, TlSegment(TlKind.parse(s(r[1])), l(r[2])!!, l(r[3])!!, d(r[4]), d(r[5]), s(r[6]), l(r[7]) ?: 0L, d(r[8]) ?: 0.0, s(r[9]), s(r[10]), s(r[11]), s(r[12]),
                s(r[13]) ?: "tracked", s(r[14])), (l(r[15]) ?: 0L).toInt())
        }
    }

    fun fixes(app: Context, fromMs: Long? = null, toMs: Long? = null): List<FixRow> {
        val where = if (fromMs != null && toMs != null) " WHERE ts >= ? AND ts < ?" else ""
        return db(app).rows("SELECT id,ts,lat,lon,accuracy,speed,provider,source FROM fixes$where ORDER BY ts ASC;", if (where.isEmpty()) emptyList() else listOf(fromMs, toMs))
            .map { r -> FixRow(l(r[0])!!, l(r[1])!!, d(r[2])!!, d(r[3])!!, d(r[4]), d(r[5]), s(r[6]) ?: "gps", s(r[7]) ?: "tracked") }
    }

    fun bundle(app: Context, fromMs: Long?, toMs: Long?, zone: ZoneId): ExportBundle {
        val sql = db(app)
        val all = fromMs == null || toMs == null
        val a = fromMs ?: Long.MIN_VALUE; val b = toMs ?: Long.MAX_VALUE
        val slices = sql.rows("SELECT start_ms,end_ms,count,source FROM step_slices" + (if (all) "" else " WHERE end_ms > ? AND start_ms < ?") + " ORDER BY start_ms;", if (all) emptyList() else listOf(a, b))
            .map { SliceRow(l(it[0])!!, l(it[1])!!, l(it[2]) ?: 0L, s(it[3]) ?: "") }
        val sleep = sql.rows("SELECT start_ms,end_ms,source FROM sleep_sessions" + (if (all) "" else " WHERE end_ms > ? AND start_ms < ?") + " ORDER BY start_ms;", if (all) emptyList() else listOf(a, b))
            .map { SleepSessionRow(l(it[0])!!, l(it[1])!!, s(it[2]) ?: "") }
        val d0 = if (all) "0000-00-00" else java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate().toString()
        val d1 = if (all) "9999-99-99" else java.time.Instant.ofEpochMilli(b - 1).atZone(zone).toLocalDate().toString()
        val daily = sql.rows("SELECT date,steps,source FROM steps_daily WHERE date >= ? AND date <= ? ORDER BY date;", listOf(d0, d1)).map { DailyStepsRow(s(it[0])!!, l(it[1]) ?: 0L, s(it[2]) ?: "") }
        return ExportBundle(fromMs, toMs, segments(app, fromMs, toMs), fixes(app, fromMs, toMs), slices, sleep, daily)
    }

    /** Writes [bytes] to a picked document. */
    fun write(app: Context, uri: android.net.Uri, bytes: ByteArray) {
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("cannot open the chosen file")
    }

    /** Restore a Cloud Nav JSON export: inserts only what the store does not hold yet. Returns the rows added. */
    fun restore(app: Context, text: String, zone: ZoneId): Int {
        val b = ExploredExport.fromJson(text) ?: error("not a Cloud Nav export")
        val lo = (b.segments.minOfOrNull { it.seg.startMs } ?: 0L); val hi = (b.segments.maxOfOrNull { it.seg.endMs } ?: 0L)
        val p = ExploredExport.restorePlan(segments(app, lo, hi + 1), fixes(app, b.fixes.minOfOrNull { it.ts } ?: 0L, (b.fixes.maxOfOrNull { it.ts } ?: 0L) + 1), b)
        val sql = db(app)
        sql.begin()
        try {
            for (r in p.segments) { val g = r.seg
                sql.exec("INSERT INTO timeline_segments (kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,prio,ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);",
                    listOf(g.kind.name, g.startMs, g.endMs, g.lat, g.lon, g.place, g.steps, g.distanceM, g.locSource, g.neighborhood, g.city, g.country, g.source, r.prio, g.ref)) }
            for (f in p.fixes) sql.exec("INSERT INTO fixes (ts,lat,lon,accuracy,speed,provider,source) VALUES (?,?,?,?,?,?,?);", listOf(f.ts, f.lat, f.lon, f.accuracyM, f.speedMps, f.provider, f.source))
            for (x in b.slices) sql.exec("INSERT OR IGNORE INTO step_slices (start_ms,end_ms,count,source) VALUES (?,?,?,?);", listOf(x.startMs, x.endMs, x.count, x.source))
            for (x in b.sleep) sql.exec("INSERT OR IGNORE INTO sleep_sessions (start_ms,end_ms,source) VALUES (?,?,?);", listOf(x.startMs, x.endMs, x.source))
            for (x in b.daily) sql.exec("INSERT OR IGNORE INTO steps_daily (date,steps,source,updated_ms) VALUES (?,?,?,?);", listOf(x.date, x.steps, x.source, System.currentTimeMillis()))
            sql.commit()
        } catch (e: Exception) { sql.rollback(); throw e }
        if (p.segments.isNotEmpty()) ExploredCache.markDirty(MapsDbDigestBackend(app), lo, hi, zone)
        return p.segments.size + p.fixes.size
    }

    fun dedupePlan(app: Context, zone: ZoneId): DedupePlan = ExploredDedupe.plan(segments(app), fixes(app), zone)

    fun applyDedupe(app: Context, plan: DedupePlan, zone: ZoneId): Int {
        val sql = db(app)
        sql.begin()
        try {
            for (id in plan.dropSegIds) sql.exec("DELETE FROM timeline_segments WHERE id = ?;", listOf(id))
            for (id in plan.dropFixIds) sql.exec("DELETE FROM fixes WHERE id = ?;", listOf(id))
            sql.commit()
        } catch (e: Exception) { sql.rollback(); throw e }
        val be = MapsDbDigestBackend(app)
        for (f in plan.findings) ExploredCache.markDirty(be, f.drop.seg.startMs, f.drop.seg.endMs, zone)
        return plan.total
    }

    /** What Delete by dates would remove (the preview). */
    fun rangePreview(app: Context, fromMs: Long, toMs: Long): RangePlan = ExploredDelete.plan(segments(app, fromMs, toMs), fixes(app, fromMs, toMs), fromMs, toMs)

    /** Delete [fromMs, toMs): segments (cut at the edges), fixes, step slices, sleep sessions, daily steps of whole days, and the imported rows behind them. */
    fun deleteRange(app: Context, fromMs: Long, toMs: Long, zone: ZoneId): RangePlan {
        val p = rangePreview(app, fromMs, toMs)
        val sql = db(app)
        val d0 = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate(); val d1 = java.time.Instant.ofEpochMilli(toMs - 1).atZone(zone).toLocalDate()
        sql.begin()
        try {
            for (id in p.deleteIds) sql.exec("DELETE FROM timeline_segments WHERE id = ?;", listOf(id))
            for (r in p.updates) sql.exec("UPDATE timeline_segments SET start_ms = ?, end_ms = ?, steps = ?, distance_m = ? WHERE id = ?;", listOf(r.seg.startMs, r.seg.endMs, r.seg.steps, r.seg.distanceM, r.id))
            for (r in p.inserts) { val g = r.seg
                sql.exec("INSERT INTO timeline_segments (kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,prio,ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);",
                    listOf(g.kind.name, g.startMs, g.endMs, g.lat, g.lon, g.place, g.steps, g.distanceM, g.locSource, g.neighborhood, g.city, g.country, g.source, r.prio, g.ref)) }
            sql.exec("DELETE FROM fixes WHERE ts >= ? AND ts < ?;", listOf(fromMs, toMs))
            sql.exec("DELETE FROM points WHERE ts >= ? AND ts < ?;", listOf(fromMs, toMs))
            sql.exec("DELETE FROM stops WHERE started_at >= ? AND started_at < ?;", listOf(fromMs, toMs))
            sql.exec("DELETE FROM step_slices WHERE start_ms >= ? AND end_ms <= ?;", listOf(fromMs, toMs))
            sql.exec("DELETE FROM sleep_sessions WHERE start_ms >= ? AND end_ms <= ?;", listOf(fromMs, toMs))
            if (DayTimeline.dayStart(d0, zone) >= fromMs && DayTimeline.dayStart(d1.plusDays(1), zone) <= toMs) {
                sql.exec("DELETE FROM steps_daily WHERE date >= ? AND date <= ?;", listOf(d0.toString(), d1.toString()))
                sql.exec("DELETE FROM day_rollups WHERE date >= ? AND date <= ?;", listOf(d0.toString(), d1.toString()))
            }
            // the imported Google rows of the range go too, or the next import rebuild would bring their segments back
            runCatching { sql.exec("DELETE FROM imp_visits WHERE start_ms >= ? AND end_ms <= ?;", listOf(fromMs, toMs)) }
            runCatching { sql.exec("DELETE FROM imp_activities WHERE start_ms >= ? AND end_ms <= ?;", listOf(fromMs, toMs)) }
            runCatching { sql.exec("DELETE FROM imp_signals WHERE ts >= ? AND ts < ?;", listOf(fromMs, toMs)) }
            sql.commit()
        } catch (e: Exception) { sql.rollback(); throw e }
        ExploredCache.markDirty(MapsDbDigestBackend(app), fromMs, toMs, zone)
        return p
    }

    /** Delete all: every table of the one store (MapsDb.clearAll) and the cache with it. The owner's corrections and home city stay. */
    fun deleteAll(app: Context) { MapsDb.get(app).clearAll(); runCatching { MapsDbDigestBackend(app).clear() } }

    /** Refresh: drop every digest, then rebuild them and the aggregates from the stored rows. */
    fun refresh(app: Context, nowMs: Long, zone: ZoneId) { MapsDbDigestBackend(app).clear(); AggregatesStore.rebuild(app, nowMs, zone) }

    /** The counts the page shows: rows per table. */
    fun counts(app: Context): Map<String, Long> {
        val sql = db(app)
        return listOf("timeline_segments", "fixes", "step_slices", "sleep_sessions", "steps_daily").associateWith { t ->
            runCatching { l(sql.rows("SELECT COUNT(*) FROM $t;").firstOrNull()?.get(0)) ?: 0L }.getOrDefault(0L)
        } + ("cached days" to runCatching { l(sql.rows("SELECT COUNT(*) FROM aggregates WHERE kind = ?;", listOf(MapsDbDigestBackend.KIND)).firstOrNull()?.get(0)) ?: 0L }.getOrDefault(0L))
    }
}

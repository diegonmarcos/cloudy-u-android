package com.diegonmarcos.cloudnav.maps

import android.content.Context
import java.time.ZoneId

/**
 * Keeps THE timeline store ([MapsDb.segmentsBetween]) equal to what the writers recorded (#1114). The writers keep
 * their own tables: the stop-detector writes `stops`, the tracker `points`, and an app that owns more inputs (Cloud
 * Nav: step samples, engine fixes) plugs them in through [Extra]. One run rebuilds a window with [EngineTimeline] from the fix log (#1851; [TimelineBuilder] is only the legacy stop-based builder);
 * the first run over all history IS the migration, and a rebuild is idempotent, so a run can never double a row.
 */
object TimelineSync {
    /** Inputs only the host app has. Each call is blocking. */
    interface Extra {
        fun fixes(fromMs: Long, toMs: Long): List<TlFix>
        /** Stays the app derived from raw fixes (no recorded stop needed). */
        fun derivedStops(fromMs: Long, toMs: Long): List<TlStop>
        fun steps(fromMs: Long, toMs: Long): List<TlSteps>
        /** A place for a stop whose own coordinates are unusable; null = none found. */
        fun relocate(stop: TlStop, nowMs: Long): TlStop?
    }

    @Volatile var extra: Extra? = null

    /** #1851 the host's own data (Cloud Nav's engine fixes and step samples) moves into the store here, before the first full rebuild. */
    @Volatile var beforeMigrate: ((Context) -> Unit)? = null
    const val STORE_VERSION = 4

    /** Stops whose coordinates cannot be a place: 0,0, NaN, out of range. */
    fun usable(s: TlStop) = s.lat.isFinite() && s.lon.isFinite() && s.lat in -90.0..90.0 && s.lon in -180.0..180.0 && !(s.lat == 0.0 && s.lon == 0.0)

    /**
     * Rebuilds the window [fromMs, nowMs] (0 = all history). The inputs reach [TimelineBuilder.MAX_GAP_MS] further back,
     * so the move that leads into the window's first stop is rebuilt too; only segments that end inside the window
     * replace what the store held. Returns the number of segments stored.
     */
    fun run(ctx: Context, nowMs: Long = System.currentTimeMillis(), fromMs: Long = 0L, zone: ZoneId = ZoneId.systemDefault(),
            ex: Extra? = extra): Int {
        val db = MapsDb.get(ctx)
        val wf = if (fromMs <= 0L) 0L else fromMs - TimelineBuilder.MAX_GAP_MS
        // #1851 THE inputs are the store's own: the fix log, the step slices, Health Connect's daily steps and sleep sessions.
        val fixes = db.fixesBetween(wf, nowMs)
        val slices = db.stepSlices(wf, nowMs)
        val day0 = java.time.Instant.ofEpochMilli(maxOf(wf, 0L)).atZone(zone).toLocalDate().minusDays(1)
        val day1 = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val daily = db.dailySteps(day0.toString(), day1.toString()).mapKeys { java.time.LocalDate.parse(it.key) }
        val hc = db.sleepSessions(wf, nowMs).map { it.first..it.second }
        val labels = db.stopsOverlapping(wf, nowMs).map { s ->
            TlStop(s.startedAt, s.endedAt, s.lat, s.lon, s.placeName?.takeIf { it.isNotBlank() }, null, s.neighborhood, s.city, s.country)
        }
        val res = EngineTimeline.build(fixes, slices, daily, hc, labels, nowMs, zone)
        val segs = res.segments.filter { it.endMs >= fromMs }
        // a full rebuild (the migration) keeps the legacy stop-based stays of any period the fix log does not cover
        val legacy = if (fromMs > 0L) emptyList() else TimelineBuilder.build(labels, emptyList(), slices, nowMs, zone)
            .filter { l -> l.kind != TlKind.MOVE && segs.none { it.startMs < l.endMs && l.startMs < it.endMs } }
        db.replaceSegments(fromMs, (segs + legacy).sortedBy { it.startMs })
        val fromDate = java.time.Instant.ofEpochMilli(maxOf(fromMs, 0L)).atZone(zone).toLocalDate()
        db.replaceRollups(res.rollups.filter { fromMs <= 0L || it.date.isAfter(fromDate) })   // the oldest day of a window is only partly rebuilt
        return segs.size
    }

    @Volatile private var lastRefreshMs = 0L
    private const val REFRESH_EVERY_MS = 30_000L
    private const val REFRESH_WINDOW_MS = 2 * 86_400_000L

    /** What the pages call first: migrate once, keep the last two days current, then read the store. */
    fun view(ctx: Context, fromMs: Long, toMs: Long): List<TlSegment> {
        val now = System.currentTimeMillis()
        runCatching {
            migrateOnce(ctx, now)
            if (now - lastRefreshMs > REFRESH_EVERY_MS) { lastRefreshMs = now; run(ctx, now, now - REFRESH_WINDOW_MS) }
        }
        return MapsDb.get(ctx).segmentsBetween(fromMs, toMs)
    }

    /** The one-time move of existing stops, steps and raw fixes into the store; later calls do nothing. */
    fun migrateOnce(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val st = TimelineState(ctx)
        if (st.migratedAt > 0L && st.storeVersion >= STORE_VERSION) return false
        runCatching { beforeMigrate?.invoke(ctx) }
        run(ctx, nowMs, 0L)
        st.migratedAt = nowMs
        st.storeVersion = STORE_VERSION
        return true
    }
}

/** The migration marker (SharedPreferences `timeline_state`): which version of the store was filled from the older tables. */
class TimelineState(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("timeline_state", Context.MODE_PRIVATE)
    var migratedAt: Long
        get() = sp.getLong("migrated_at", 0L)
        set(v) { sp.edit().putLong("migrated_at", v).apply() }
    /** #1851: 4 = the store is built by [EngineTimeline] from the fix log; a lower value triggers one full rebuild. */
    var storeVersion: Int
        get() = sp.getInt("store_version", 0)
        set(v) { sp.edit().putInt("store_version", v).apply() }
}

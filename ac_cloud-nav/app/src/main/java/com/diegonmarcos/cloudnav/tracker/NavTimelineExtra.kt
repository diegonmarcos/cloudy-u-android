package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TlFix
import com.diegonmarcos.cloudnav.maps.TlStop
import com.diegonmarcos.cloudnav.maps.TlSteps
import java.time.ZoneId

/**
 * What Cloud Nav adds to the timeline store beyond the stop-detector's own tables (#1114): the engine's step samples and
 * fixes, the stays derived from those fixes, and a place for a stop whose coordinates are unusable. [radio] false (the
 * pages, the IPC door) never wakes a radio; the engine's 15-minute tick passes true and so may ask [StopLocator].
 */
class NavTimelineExtra(private val ctx: Context, private val radio: Boolean = false, private val allowGps: Boolean = false) : TimelineSync.Extra {
    private val db get() = EngineDb.get(ctx)

    override fun fixes(fromMs: Long, toMs: Long): List<TlFix> =
        db.fixes(fromMs, toMs).map { TlFix(it.ts, it.lat, it.lon, it.accuracyM) }

    override fun derivedStops(fromMs: Long, toMs: Long): List<TlStop> =
        PlacesDigest.stays(db.fixes(fromMs, toMs)).map {
            TlStop(it.startMs, it.endMs, it.lat, it.lon, it.label, if (it.predicted) "predicted" else "fix")
        }

    /** The engine's slices from the sample chain (a slice starts at the previous sample), cut at [fromMs] so a window
     *  rebuild counts exactly the steps a full one does. */
    override fun steps(fromMs: Long, toMs: Long): List<TlSteps> =
        db.slices(fromMs - 2 * EngineDb.SAMPLE_MS, toMs).mapNotNull { s ->
            when {
                s.endMs <= fromMs -> null
                s.startMs >= fromMs -> TlSteps(s.startMs, s.endMs, s.count)
                else -> TlSteps(fromMs, s.endMs, Math.round(s.count * ((s.endMs - fromMs).toDouble() / (s.endMs - s.startMs))))
            }
        }

    override fun relocate(stop: TlStop, nowMs: Long): TlStop? {
        // a fix the engine took around the stop (network/cell first: they are what the engine records cheaply), else live
        val near = db.fixes(stop.startMs - NEAR_MS, (stop.endMs ?: nowMs) + NEAR_MS)
            .filter { !it.predicted && it.accuracyM <= MAX_ACC_M }
            .minByOrNull { Math.abs(it.ts - stop.startMs) }
        if (near != null) return stop.copy(lat = near.lat, lon = near.lon, locSource = "fix")
        val end = stop.endMs
        val current = end == null || nowMs - end <= NEAR_MS
        if (!radio || !current) return null
        val f = StopLocator.resolve(nowMs, StopLocator.env(ctx), StopLocator.AndroidProbe(ctx), allowGps = allowGps) ?: return null
        db.addFix(PlaceFix(f.timeMs, f.lat, f.lon, false, f.accuracyM.coerceAtLeast(0f).toDouble()), if (f.source == StopLocator.GPS) "gps" else "net")
        return stop.copy(lat = f.lat, lon = f.lon, locSource = if (f.source == StopLocator.NETWORK) "net" else f.source)
    }

    companion object {
        private const val NEAR_MS = 30 * 60_000L
        private const val MAX_ACC_M = 200.0

        /** Registers this app's inputs for every timeline run in the process (idempotent). */
        fun install(ctx: Context) {
            TimelineSync.extra = NavTimelineExtra(ctx.applicationContext)
            TimelineSync.beforeMigrate = { migrateEngine(it) }
            // #1853 once a Google history is imported and its segments rebuilt, Trips / Places / Cities / World / Insights are rebuilt over it
            com.diegonmarcos.cloudnav.maps.takeout.ImportController.afterImport = { AggregatesStore.rebuild(it, System.currentTimeMillis()) }
        }

        /** #1851 the engine's own tables move into the one store: every measured fix into `fixes`, every step slice into `step_slices`. Idempotent. */
        fun migrateEngine(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
            val engine = EngineDb.get(ctx.applicationContext); val store = com.diegonmarcos.cloudnav.maps.MapsDb.get(ctx.applicationContext)
            store.runInTransaction {
                for (f in engine.fixes(0L, nowMs)) store.appendFix(com.diegonmarcos.cloudnav.maps.Fix(f.ts, f.lat, f.lon, f.accuracyM, null, "engine-" + f.source.ifBlank { "net" }))
                for (s in engine.slices(0L, nowMs)) store.appendStepSlice(TlSteps(s.startMs, s.endMs, s.count), "counter")
            }
        }

        /** The engine's tick: one-time migration, then the last two days rebuilt, with the radio allowed. */
        fun tick(ctx: Context, now: Long, zone: ZoneId = ZoneId.systemDefault(), moving: Boolean = false) {
            val app = ctx.applicationContext
            install(app)
            TimelineSync.migrateOnce(app, now)
            TimelineSync.run(app, now, now - 2 * 86_400_000L, zone, NavTimelineExtra(app, radio = true, allowGps = BatteryPolicy.allowGps(moving)))
            runCatching { AggregatesStore.rebuild(app, now, zone) }   // #1851 Trips / Places / Cities / World / Insights follow every rebuild
        }
    }
}

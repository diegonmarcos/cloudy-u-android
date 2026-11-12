package com.diegonmarcos.cloudnav.tracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.cloudnav.maps.TimelineJson
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TimelineViews
import com.diegonmarcos.superapp.devtools.AppDebugServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tracker's data for the fleet: `tracker/steps`, `tracker/places`, `tracker/trips` and `tracker/timeline` on the fleet IPC door
 * (<pkg>.fleet, FleetTokenProvider — signature permission CONSTELLATION_DATA, so only a constellation-signed app such
 * as the SuperApp's Boards ▸ ME can ask). Read-only: all ops read the stores the Steps page reads
 * ([StepsRepository]: Health Connect or the engine's own samples; the engine's fixes plus the foreground tracker's GPS
 * points) and change nothing. Registered from a provider's onCreate, the ImageDebugApiProvider way, so the route exists
 * before Application code runs. Not a real provider and not exported.
 */
class TrackerDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        NavTimelineExtra.install(app)   // before any page or op reads the timeline store
        runCatching {
            AppDebugServer.route(GROUP, listOf(
                AppDebugServer.Op("steps", "days=<1..31, default 7>", "steps today and per day (Health Connect, else this phone's counter) with the source status the Steps page shows"),
                AppDebugServer.Op("timeline", "hours=<1..72, default 24>", "the timeline store: ordered move / stop / sleep segments with time, place, steps and distance for the last hours (read-only)"),
                AppDebugServer.Op("segments", "date=<yyyy-MM-dd, default today>", "contract v1: the day as a gapless list of stay (sleep|idle) and move (mode, distance, steps, speed) segments with places"),
                AppDebugServer.Op("daily", "days=<1..31, default 7>", "contract v1: per day sleep hours and city, walking / running distance, distance per vehicle, per-mode summary"),
                AppDebugServer.Op("trips", "days=<30..365, default 90>", "contract v1: consecutive nights away from the home city (most frequent sleep city of the last 90 days, owner-overridable) grouped into trips: cities, dates, nights, distance per vehicle, route"),
                AppDebugServer.Op("places", "days=<1..31, default 7>", "the places the tracker recorded, per day: stays (centroid, time span, label)"),
                AppDebugServer.Op("location", "", "#1690 the fleet's current / last location: the newest measured fix the tracker holds (lat, lon, accuracy, time, source); the SuperApp's location views read this instead of tracking"),
            )) { op, q ->
                val days = (q["days"]?.toIntOrNull() ?: 7).coerceIn(1, 31)
                when (op) {
                    "steps" -> steps(app, days).toString()
                    "places" -> places(app, days).toString()
                    "location" -> location(app).toString()
                    "segments" -> segments(app, q["date"]).toString()
                    "daily" -> daily(app, days).toString()
                    "trips" -> trips(app, (q["days"]?.toIntOrNull() ?: 90).coerceIn(30, 365)).toString()
                    "timeline" -> timeline(app, (q["hours"]?.toIntOrNull() ?: 24).coerceIn(1, 72)).toString()
                    else -> null
                }
            }
        }
        return true
    }

    companion object {
        const val GROUP = "tracker"

        fun steps(ctx: Context, days: Int, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val ui = runBlocking { StepsRepository(ctx).load(zone) }
            val arr = JSONArray()
            for (i in days - 1 downTo 0) {
                val d = ui.today.minusDays(i.toLong())
                arr.put(JSONObject().put("date", d.toString()).put("steps", ui.daily[d] ?: 0L))
            }
            return JSONObject()
                .put("today", ui.daily[ui.today] ?: ui.totals.day)
                .put("days", arr)
                .put("source", ui.source.name)
                .put("status", ui.status.name)
                .put("status_line", ui.diag.statusLine)
                .put("hint", ui.diag.hint ?: "")
        }

        /** `tracker/timeline`: the last [hours] of THE timeline store, nothing computed here and nothing changed. */
        fun timeline(ctx: Context, hours: Int, nowMs: Long = System.currentTimeMillis()): JSONObject {
            val from = nowMs - hours * 3_600_000L
            val segs = TimelineViews.lastHours(TimelineSync.view(ctx, from, nowMs), nowMs, hours)
            return TimelineJson.toJson(segs, nowMs, hours)
        }

        /** `tracker/segments` (contract v1): the gapless day of [date] (default today; today ends at now), corrections applied. */
        fun segments(ctx: Context, date: String?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val d = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now(zone) }
            val a = DayTimeline.dayStart(d, zone); val b = minOf(DayTimeline.dayStart(d.plusDays(1), zone), nowMs)
            val cs = CorrectionStore(ctx)
            val day = DayTimeline.build(TimelineSync.view(ctx, a - 12 * 3_600_000L, b), a, b, cs.all(), cs.memory())
            return DayJson.segments(day, a, b)
        }

        /** `tracker/daily` (contract v1): aggregates for the last [days] dates, oldest first. */
        fun daily(ctx: Context, days: Int, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val today = LocalDate.now(zone)
            val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
            val cs = CorrectionStore(ctx)
            val store = TimelineSync.view(ctx, DayTimeline.dayStart(dates.first(), zone) - 12 * 3_600_000L, nowMs)
            return DayJson.daily(Aggregates.perDay(store, dates, nowMs, zone, cs.all(), cs.memory()))
        }

        /** `tracker/trips` (contract v1): the last [days] dates grouped into trips by sleep city, home from [HomeStore] else detected. */
        fun trips(ctx: Context, days: Int, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val today = LocalDate.now(zone)
            val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
            val cs = CorrectionStore(ctx)
            val store = TimelineSync.view(ctx, DayTimeline.dayStart(dates.first(), zone) - 12 * 3_600_000L, nowMs)
            return TripsJson.toJson(Trips.group(Aggregates.perDay(store, dates, nowMs, zone, cs.all(), cs.memory()), HomeStore(ctx).get()))
        }

        /** #1690 `tracker/location`: the newest measured fix of the last [NavLocationWire.WINDOW_MS] (predicted only when nothing measured). */
        fun location(ctx: Context, nowMs: Long = System.currentTimeMillis()): JSONObject =
            NavLocationWire.toJson(runCatching { StepsRepository(ctx).fixes(nowMs - NavLocationWire.WINDOW_MS, nowMs) }.getOrDefault(emptyList()), nowMs)

        fun places(ctx: Context, days: Int, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val repo = StepsRepository(ctx)
            val today = LocalDate.now(zone)
            val from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val fixes = repo.fixes(from, now)
            val stops = repo.stops(from, now)
            val out = JSONArray()
            for ((d, stays) in PlacesDigest.byDay(fixes, zone, today, days, stops, now)) {
                val v = JSONArray()
                for (s in stays) {
                    v.put(JSONObject()
                        .put("part", PlacesDigest.span(s, zone))
                        .put("label", s.label ?: repo.name(PlaceFix(s.startMs, s.lat, s.lon, s.predicted)))
                        .put("lat", s.lat).put("lon", s.lon)
                        .put("predicted", s.predicted))
                }
                out.put(JSONObject().put("date", d.toString()).put("visits", v))
            }
            return JSONObject().put("days", out).put("fixes", fixes.size).put("stops", stops.size)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

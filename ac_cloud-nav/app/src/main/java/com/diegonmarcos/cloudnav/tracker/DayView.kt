package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * The declared rules of the Tracker > Timeline day view (build.json::ui.day_timeline, baked as base64 JSON).
 * Defaults mirror the declaration so a JVM test needs no BuildConfig.
 */
data class DayRules(
    val stayRadiusM: Double = 100.0,
    val minDwellMs: Long = 5 * 60_000L,
    val maxFixAccM: Double = 200.0,
    val copyAddressFields: List<String> = listOf("street", "postcode", "city"),
    val fullAddressFields: List<String> = listOf("street", "postcode", "city", "country"),
) {
    companion object {
        fun fromJson(s: String): DayRules = runCatching {
            val o = JSONObject(s)
            val d = DayRules()
            fun list(k: String, def: List<String>) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.isNotEmpty() } ?: def
            DayRules(o.optDouble("stay_radius_m", d.stayRadiusM), (o.optDouble("min_dwell_min", d.minDwellMs / 60_000.0) * 60_000).toLong(),
                o.optDouble("max_fix_acc_m", d.maxFixAccM), list("copy_address_fields", d.copyAddressFields), list("full_address_fields", d.fullAddressFields))
        }.getOrDefault(DayRules())
    }
}

/**
 * Raw fixes to a gapless list of stays and moves. A stay is a run of consecutive fixes that all lie within [DayRules.stayRadiusM]
 * of the run's running centre and span at least [DayRules.minDwellMs]; whatever lies between two stays is a move (distance =
 * the fixes' path through both stay centres, mode from its average speed). Gaps with no fix are closed by [DayTimeline.fill], so the
 * result always covers [fromMs, toMs) exactly. Pure: no Android.
 */
object FixSegmenter {
    private class Run(first: PlaceFix) {
        val fixes = arrayListOf(first)
        var lat = first.lat; var lon = first.lon
        val startMs get() = fixes.first().ts
        val endMs get() = fixes.last().ts
        fun add(f: PlaceFix) { fixes += f; val n = fixes.size; lat += (f.lat - lat) / n; lon += (f.lon - lon) / n }
    }

    private fun d(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = TimelineBuilder.haversineM(aLat, aLon, bLat, bLon)

    private fun runs(fixes: List<PlaceFix>, r: DayRules): List<Run> {
        val out = ArrayList<Run>()
        for (f in fixes) {
            val cur = out.lastOrNull()
            if (cur != null && d(cur.lat, cur.lon, f.lat, f.lon) <= r.stayRadiusM) cur.add(f) else out += Run(f)
        }
        return out
    }

    fun segment(fixes: List<PlaceFix>, fromMs: Long, toMs: Long, rules: DayRules = DayRules(), zone: ZoneId = ZoneId.systemDefault()): List<DaySeg> {
        if (toMs <= fromMs) return emptyList()
        val good = fixes.filter { !it.predicted && it.ts >= fromMs && it.ts < toMs && (it.accuracyM <= 0.0 || it.accuracyM <= rules.maxFixAccM) }.sortedBy { it.ts }
        val all = runs(good, rules)
        val stays = all.filter { it.endMs - it.startMs >= rules.minDwellMs }
        val out = ArrayList<DaySeg>()
        // the moving fixes between [a, b] (exclusive of the stays themselves) plus the stay centres on either side
        fun path(prev: Run?, a: Long, b: Long, next: Run?): Double {
            val pts = ArrayList<Pair<Double, Double>>()
            prev?.let { pts += it.lat to it.lon }
            // an open end (no stay on that side) keeps its boundary fix; a stay side is represented by the stay centre
            good.filter { (if (prev != null) it.ts > a else it.ts >= a) && (if (next != null) it.ts < b else it.ts <= b) }.forEach { pts += it.lat to it.lon }
            next?.let { pts += it.lat to it.lon }
            var dist = 0.0
            for (i in 1 until pts.size) dist += d(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            return dist
        }
        fun move(prev: Run?, a: Long, b: Long, next: Run?) {
            if (b > a) out += DayTimeline.moveOf(a, b, path(prev, a, b, next), 0L, null, null, null)
        }
        if (stays.isEmpty()) {
            if (good.size >= 2) move(null, good.first().ts, good.last().ts, null)
            return DayTimeline.fillLinks(DayTimeline.fill(out, fromMs, toMs))
        }
        var prev: Run? = null
        for (s in stays) {
            val a = prev?.endMs ?: good.first().ts.takeIf { it < s.startMs } ?: s.startMs
            move(prev, a, s.startMs, s)
            val kind = if (TimelineBuilder.isSleep(s.startMs, s.endMs, zone)) StayKind.SLEEP else StayKind.IDLE
            out += DaySeg.Stay(s.startMs, s.endMs, kind, Place(s.lat, s.lon))
            prev = s
        }
        prev?.let { p -> good.lastOrNull()?.takeIf { it.ts > p.endMs }?.let { move(p, p.endMs, it.ts, null) } }
        return DayTimeline.fillLinks(DayTimeline.fill(out, fromMs, toMs))
    }
}

/** Total steps of the day, or n/a with the [reason] it is unavailable. */
data class StepsTotal(val count: Long?, val reason: String?)

/** The summary bar at the top of the day. */
data class DayBar(val distanceM: Double, val steps: StepsTotal, val activeMs: Long, val stoppedMs: Long)

/** What an expanded place row shows (every field already formatted for display). */
data class PlaceDetail(
    val geoCode: String, val accuracy: String, val address: String, val arrivalMs: Long, val departureMs: Long, val durationMs: Long,
    val arrivedBy: Mode?, val arrivedDistanceM: Double?, val visits: Int, val source: String, val fixCount: Int,
)

object DayDetails {
    fun bar(day: List<DaySeg>, steps: StepsTotal): DayBar =
        DayBar(day.filterIsInstance<DaySeg.Move>().sumOf { it.distanceM }, steps,
            day.filterIsInstance<DaySeg.Move>().sumOf { it.durationMs }, day.filterIsInstance<DaySeg.Stay>().sumOf { it.durationMs })

    /** "lat,lon", six decimals (about 0.1 m), a dot always. */
    fun geoCode(lat: Double, lon: Double) = "%.6f,%.6f".format(Locale.US, lat, lon)

    private fun field(p: Place, name: String): String? = when (name) {
        "street" -> p.street; "postcode" -> p.postcode; "city" -> p.city; "country" -> p.country
        "state" -> p.state; "neighborhood" -> p.neighborhood; else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** The address as the declared [fields] in order, blanks skipped ("street number" is one field: the geocoder joins them). */
    fun address(p: Place, fields: List<String>): String = fields.mapNotNull { field(p, it) }.joinToString(", ")

    fun fullAddress(p: Place, r: DayRules = DayRules()) = address(p, r.fullAddressFields)
    fun copyAddress(p: Place, r: DayRules = DayRules()) = address(p, r.copyAddressFields)

    fun sourceLabel(kind: String): String = when (kind.lowercase()) {
        "gps", "" -> "GPS"; "net", "cell", "network" -> "Network"; "fused" -> "Fused"; else -> kind
    }

    /** The most used source among [fixes] ("n/a" with none). */
    fun source(fixes: List<PlaceFix>): String =
        fixes.groupingBy { sourceLabel(it.source) }.eachCount().maxByOrNull { it.value }?.key ?: "n/a"

    fun accuracy(fixes: List<PlaceFix>): String =
        fixes.map { it.accuracyM }.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.let { "+/- %d m".format(Locale.US, Math.round(it.average())) } ?: "n/a"

    /** Detail for the located stay at [i] of [day]; null for a move, a missing stay or an unlocated place. */
    fun detail(day: List<DaySeg>, i: Int, fixes: List<PlaceFix>, r: DayRules = DayRules()): PlaceDetail? {
        val s = day.getOrNull(i) as? DaySeg.Stay ?: return null
        val p = s.place?.takeIf { it.located } ?: return null
        val inside = fixes.filter { !it.predicted && it.ts in s.startMs..s.endMs }
        val arrived = day.getOrNull(i - 1) as? DaySeg.Move
        val visits = day.filterIsInstance<DaySeg.Stay>().mapNotNull { it.place?.takeIf { q -> q.located } }.count { DayTimeline.dist(it, p) <= r.stayRadiusM }
        return PlaceDetail(geoCode(p.lat!!, p.lon!!), accuracy(inside), fullAddress(p, r).ifBlank { "n/a" }, s.startMs, s.endMs, s.durationMs,
            arrived?.mode, arrived?.distanceM, visits, source(inside), inside.size)
    }
}

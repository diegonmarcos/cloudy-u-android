package com.diegonmarcos.cloudnav.maps

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a timeline segment is: the phone travelled (MOVE), stayed (STOP) or stayed through the night (SLEEP). */
enum class TlKind { MOVE, STOP, SLEEP;
    companion object { fun parse(s: String?): TlKind = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: STOP }
}

/**
 * One row of THE timeline store ([MapsDb] table timeline_segments, #1114). Daily, Stops, Steps and the SuperApp's
 * MyTrips are all views over a list of these. [lat]/[lon] are the place of a stop or sleep, and where a move ended (null when unknown);
 * [locSource] says how that place was found (gps, net, cell, passive, fix, predicted).
 */
data class TlSegment(
    val kind: TlKind, val startMs: Long, val endMs: Long,
    val lat: Double?, val lon: Double?, val place: String?,
    val steps: Long, val distanceM: Double, val locSource: String?,
    val neighborhood: String? = null, val city: String? = null, val country: String? = null,
) {
    val durationMs: Long get() = endMs - startMs
    /** The line a view prints for the place: the name, else neighborhood and city. */
    val display: String? get() = place?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(neighborhood, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
}

/** A stop going in: [endMs] null while it lasts. [locSource] null = recorded by the GPS stop-detector. */
data class TlStop(val startMs: Long, val endMs: Long?, val lat: Double, val lon: Double, val name: String?, val locSource: String? = null,
                  val neighborhood: String? = null, val city: String? = null, val country: String? = null)

data class TlFix(val ts: Long, val lat: Double, val lon: Double, val accuracyM: Double)

data class TlSteps(val startMs: Long, val endMs: Long, val count: Long)

/**
 * Pure: stops + raw fixes + step slices in, an ordered start/move/stop/move/sleep list out. Nothing here touches
 * Android, so a JVM test holds the segmentation, the sleep rule and the migration (the same builder is the migration).
 *
 * Stops are cut so none overlaps its neighbour. Between two stops lies a MOVE (distance = the fixes' path, else the
 * straight line); a gap longer than [MAX_GAP_MS] with no fix in it is unknown time, not a move. A stop that spends at
 * least [SLEEP_MIN_MS] inside the local [NIGHT_START_H]:00..[NIGHT_END_H]:00 night is a SLEEP.
 */
object TimelineBuilder {
    const val NIGHT_START_H = 22
    const val NIGHT_END_H = 8
    const val SLEEP_MIN_MS = 3 * 3_600_000L
    const val MIN_MOVE_MS = 60_000L
    const val MAX_GAP_MS = 12 * 3_600_000L
    const val MAX_FIX_ACC_M = 200.0
    const val MIN_UNPLACED_STEPS = 20L
    const val UNPLACED_JOIN_MS = 15 * 60_000L
    /** #1851 an open stop is bounded by evidence: it ends at the first fix beyond [OPEN_RADIUS_M], and goes quiet [OPEN_SILENCE_MS] after its last fix. */
    const val OPEN_RADIUS_M = 150.0
    const val OPEN_SILENCE_MS = 6 * 3_600_000L

    fun build(stops: List<TlStop>, fixes: List<TlFix>, steps: List<TlSteps>, nowMs: Long, zone: ZoneId): List<TlSegment> {
        val good = fixes.filter { it.accuracyM <= MAX_FIX_ACC_M }.sortedBy { it.ts }
        // 1. clean stops: ordered, open ones run to now, no overlap, no empty
        val clean = ArrayList<TlStop>()
        var cursor = Long.MIN_VALUE
        for (s in stops.sortedBy { it.startMs }) {
            val a = maxOf(s.startMs, cursor)
            val b = minOf(maxOf(s.endMs ?: closeOpen(s, good, nowMs), s.startMs), nowMs)
            if (b <= a) continue
            clean += s.copy(startMs = a, endMs = b)
            cursor = b
        }
        val out = ArrayList<TlSegment>()
        clean.forEachIndexed { i, s ->
            val a = s.startMs; val b = s.endMs!!
            out += TlSegment(if (isSleep(a, b, zone)) TlKind.SLEEP else TlKind.STOP, a, b, s.lat, s.lon, s.name,
                stepsIn(steps, a, b), 0.0, s.locSource ?: "gps", s.neighborhood, s.city, s.country)
            val next = clean.getOrNull(i + 1)
            val from = LatLon(s.lat, s.lon)
            if (next != null) {
                move(good, steps, b, next.startMs, from, LatLon(next.lat, next.lon))?.let { out += it }
            } else {
                move(good, steps, b, nowMs, from, null)?.let { out += it }   // closed last stop: on the road since (an open one ends at now: no gap)
            }
        }
        return (out + unplaced(steps, out, nowMs)).sortedBy { it.startMs }
    }

    /**
     * #1851 where a stop with no recorded end really ended. Running it to "now" made one stop out of every day since the last fix
     * (one 24 h "sleep" at one place per day): it ends at the first fix beyond [OPEN_RADIUS_M], else [OPEN_SILENCE_MS] after the last fix near it.
     */
    internal fun closeOpen(s: TlStop, good: List<TlFix>, nowMs: Long): Long {
        val after = good.filter { it.ts >= s.startMs && it.ts <= nowMs }
        val left = after.firstOrNull { haversineM(s.lat, s.lon, it.lat, it.lon) > OPEN_RADIUS_M }
        if (left != null) return left.ts
        val evidence = after.lastOrNull()?.ts ?: s.startMs
        return if (nowMs - evidence > OPEN_SILENCE_MS) evidence + OPEN_SILENCE_MS else nowMs
    }

    /** Steps no stop or move accounts for (no location was recorded then) stay in the store as place-less MOVEs, so the
     *  Steps view never loses a step the counter saw. Runs under [MIN_UNPLACED_STEPS] are noise and dropped. */
    private fun unplaced(steps: List<TlSteps>, placed: List<TlSegment>, nowMs: Long): List<TlSegment> {
        val out = ArrayList<TlSegment>()
        var rs = -1L; var re = -1L; var rn = 0.0
        fun flush() { if (rs >= 0 && rn >= MIN_UNPLACED_STEPS) out += TlSegment(TlKind.MOVE, rs, re, null, null, null, Math.round(rn), 0.0, "steps"); rs = -1L }
        for (sl in steps.sortedBy { it.startMs }) {
            val len = (sl.endMs - sl.startMs).coerceAtLeast(1)
            var a = sl.startMs; val end = min(sl.endMs, nowMs)
            val free = ArrayList<Pair<Long, Long>>()
            for (c in placed) {
                if (c.endMs <= a) continue
                if (c.startMs >= end) break
                if (c.startMs > a) free += a to c.startMs
                a = maxOf(a, c.endMs)
            }
            if (a < end) free += a to end
            for ((x, y) in free) {
                val n = sl.count * ((y - x).toDouble() / len)
                if (rs >= 0 && x - re <= UNPLACED_JOIN_MS) { re = y; rn += n } else { flush(); rs = x; re = y; rn = n }
            }
        }
        flush()
        return out
    }

    private data class LatLon(val lat: Double, val lon: Double)

    private fun move(fixes: List<TlFix>, steps: List<TlSteps>, a: Long, b: Long, from: LatLon, to: LatLon?): TlSegment? {
        if (b - a < MIN_MOVE_MS) return null
        val inside = fixes.filter { it.ts in a..b }
        if (b - a > MAX_GAP_MS && inside.isEmpty()) return null
        val pts = ArrayList<LatLon>()
        pts += from
        inside.forEach { pts += LatLon(it.lat, it.lon) }
        if (to != null) pts += to
        var d = 0.0
        for (i in 1 until pts.size) d += haversineM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        val end = to ?: inside.lastOrNull()?.let { LatLon(it.lat, it.lon) }
        return TlSegment(TlKind.MOVE, a, b, end?.lat, end?.lon, null, stepsIn(steps, a, b), d, null)
    }

    /** Steps inside [a, b]: each slice counts in proportion to how much of it falls there. */
    fun stepsIn(steps: List<TlSteps>, a: Long, b: Long): Long {
        var n = 0.0
        for (s in steps) {
            val o = min(s.endMs, b) - maxOf(s.startMs, a)
            if (o <= 0) continue
            val len = (s.endMs - s.startMs).coerceAtLeast(1)
            n += s.count * (o.toDouble() / len)
        }
        return Math.round(n)
    }

    /** True when [a, b] holds at least [SLEEP_MIN_MS] of local night. */
    fun isSleep(a: Long, b: Long, zone: ZoneId): Boolean {
        if (b - a < SLEEP_MIN_MS) return false
        var day = Instant.ofEpochMilli(a).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        var night = 0L
        while (!day.isAfter(last)) {
            val ns = day.atTime(NIGHT_START_H, 0).atZone(zone).toInstant().toEpochMilli()
            val ne = day.plusDays(1).atTime(NIGHT_END_H, 0).atZone(zone).toInstant().toEpochMilli()
            night += (min(b, ne) - maxOf(a, ns)).coerceAtLeast(0)
            day = day.plusDays(1)
        }
        return night >= SLEEP_MIN_MS
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

/** The views: each reads the one segment list and nothing else. */
object TimelineViews {
    /** Daily + Stops: the stays (stop and sleep), as the rows those pages already draw. Ids are the segment's start. */
    fun richStops(segs: List<TlSegment>): List<MapsDb.RichStop> =
        segs.filter { it.kind != TlKind.MOVE && it.lat != null && it.lon != null }.map {
            MapsDb.RichStop(it.startMs, it.startMs, it.endMs, it.lat!!, it.lon!!, it.place, it.neighborhood, it.city, it.country)
        }

    /** Steps: one slice per segment, to chart and total like any other step source. */
    fun stepSlices(segs: List<TlSegment>): List<TlSteps> =
        segs.filter { it.steps > 0 }.map { TlSteps(it.startMs, it.endMs, it.steps) }

    /** MyTrips: the window [nowMs - hours, nowMs], segments cut at its edges (steps and distance in proportion). */
    fun lastHours(segs: List<TlSegment>, nowMs: Long, hours: Int): List<TlSegment> {
        val from = nowMs - hours * 3_600_000L
        return segs.filter { it.endMs > from && it.startMs < nowMs }.sortedBy { it.startMs }.map {
            val a = maxOf(it.startMs, from); val b = min(it.endMs, nowMs)
            if (a == it.startMs && b == it.endMs) it else {
                val f = (b - a).toDouble() / (it.endMs - it.startMs).coerceAtLeast(1)
                it.copy(startMs = a, endMs = b, steps = Math.round(it.steps * f), distanceM = it.distanceM * f)
            }
        }
    }
}

/** The wire shape of `tracker/timeline` (and nothing else): the SuperApp parses exactly this. */
object TimelineJson {
    fun toJson(segs: List<TlSegment>, nowMs: Long, hours: Int): JSONObject {
        val arr = JSONArray()
        for (s in segs) arr.put(JSONObject().put("kind", s.kind.name.lowercase()).put("start", s.startMs).put("end", s.endMs)
            .put("place", s.display ?: "").put("steps", s.steps).put("distance_m", Math.round(s.distanceM))
            .put("loc_src", s.locSource ?: "").apply {
                if (s.lat != null && s.lon != null) put("lat", s.lat).put("lon", s.lon)
            })
        return JSONObject().put("now", nowMs).put("hours", hours).put("segments", arr)
    }

    fun fromJson(body: String): List<TlSegment> {
        val arr = JSONObject(body).optJSONArray("segments") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TlSegment(TlKind.parse(o.optString("kind")), o.getLong("start"), o.getLong("end"),
                if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lon")) o.getDouble("lon") else null,
                o.optString("place").takeIf { it.isNotBlank() }, o.optLong("steps"), o.optDouble("distance_m", 0.0),
                o.optString("loc_src").takeIf { it.isNotBlank() })
        }
    }
}

package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.DayRollup
import com.diegonmarcos.cloudnav.maps.EngineTimeline
import com.diegonmarcos.cloudnav.maps.Fix
import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure pieces of turning imported rows into the segments the pages read. */
object TakeoutDerive {
    fun prioOf(source: String): Int = when (source) { TakeoutSchema.SRC_TRACKED -> 0; TakeoutFormat.TIMELINE.sourceTag -> 1; TakeoutFormat.SEMANTIC.sourceTag -> 2; TakeoutFormat.RECORDS.sourceTag -> 3; TakeoutFormat.KML.sourceTag -> 4; else -> 4 }

    /** Google's activity type (any spelling: IN_PASSENGER_VEHICLE, "walking", "in train") to the Day page's mode name; null = let the speed decide. */
    fun mode(type: String?): String? {
        val t = type?.trim()?.uppercase()?.replace(' ', '_') ?: return null
        return when {
            t == "RUNNING" || t == "JOGGING" -> "RUN"
            t == "WALKING" || t == "ON_FOOT" || t == "HIKING" -> "WALK"
            t == "CYCLING" || t == "ON_BICYCLE" || t == "BIKING" || t == "E_BIKING" -> "BIKE"
            t == "FLYING" -> "PLANE"
            t.contains("TRAIN") || t.contains("SUBWAY") || t.contains("TRAM") || t.contains("METRO") || t.contains("LIGHT_RAIL") || t == "TRANSIT" -> "TRAIN"
            t.contains("VEHICLE") || t.contains("BUS") || t.contains("CAR") || t.contains("MOTORCYCL") || t.contains("TAXI") || t.contains("FERRY") || t.contains("SAIL") || t.contains("BOAT") -> "CAR"
            else -> null
        }
    }

    /** "Alexanderplatz 1, 10178 Berlin, Germany" to (Berlin, Germany); best effort, the full address stays in imp_visits. */
    fun cityCountry(address: String?): Pair<String?, String?> {
        val parts = address?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null to null
        if (parts.size < 2) return null to null
        val country = parts.last().takeIf { !it.any(Char::isDigit) }
        val city = parts[parts.size - 2].replace(Regex("^\\d{3,6}\\s*"), "").replace(Regex("\\s+[A-Z0-9]{2,4}\\s?[A-Z0-9]{2,3}$"), "").trim().takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
        return city to country
    }

    /** The longest night (22:00-08:00) inside [a, b] when it is at least [TimelineBuilder.SLEEP_MIN_MS], else null. */
    fun nightOf(a: Long, b: Long, zone: ZoneId): LongRange? {
        if (b - a < TimelineBuilder.SLEEP_MIN_MS) return null
        var day = Instant.ofEpochMilli(a).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        var best: LongRange? = null
        while (!day.isAfter(last)) {
            val ns = maxOf(a, day.atTime(TimelineBuilder.NIGHT_START_H, 0).atZone(zone).toInstant().toEpochMilli())
            val ne = minOf(b, day.plusDays(1).atTime(TimelineBuilder.NIGHT_END_H, 0).atZone(zone).toInstant().toEpochMilli())
            if (ne - ns >= TimelineBuilder.SLEEP_MIN_MS && (best == null || ne - ns > best.last - best.first)) best = ns..ne
            day = day.plusDays(1)
        }
        return best
    }

    /** [offsetMin] is the UTC offset the export gave the start time (the Timeline export and its KML do), null when it gave none. */
    class VisitRow(val key: String, val format: String, val startMs: Long, val endMs: Long, val lat: Double?, val lon: Double?, val name: String?, val address: String?,
                   val offsetMin: Long? = null)
    class ActivityRow(val key: String, val format: String, val startMs: Long, val endMs: Long, val startLat: Double?, val startLon: Double?, val endLat: Double?, val endLon: Double?,
                      val distanceM: Double?, val type: String?, val pathJson: String?)

    /**
     * A visit is a stay (three rows when it holds a night: day part, SLEEP, rest; a whole stay is never one sleep). #1887 with a [geo]
     * gazetteer, a visit without an address takes the City and Country of the nearest place, and its night is the place's own local
     * night (the place's zone, else the export's UTC offset): a sleep on another continent is found on that continent's clock.
     */
    fun visitSegments(v: VisitRow, zone: ZoneId, geo: Gazetteer? = null): List<TlSegment> {
        if (v.endMs <= v.startMs) return emptyList()
        val (ac, aco) = cityCountry(v.address)
        val near = if (geo != null && v.lat != null && v.lon != null) geo.nearest(v.lat, v.lon) else null
        val city = ac ?: near?.name; val country = aco ?: near?.country
        val place = v.name?.takeIf { it.isNotBlank() } ?: v.address?.substringBefore(',')?.takeIf { it.isNotBlank() }
        val local: ZoneId? = near?.zone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() }
            ?: if (geo != null) v.offsetMin?.let { o -> runCatching { java.time.ZoneOffset.ofTotalSeconds((o * 60).toInt()) }.getOrNull() } else null
        val night = nightOf(v.startMs, v.endMs, local ?: zone)
        val parts = if (night != null) listOf(Triple(v.startMs, night.first, TlKind.STOP), Triple(night.first, night.last, TlKind.SLEEP), Triple(night.last, v.endMs, TlKind.STOP))
        else listOf(Triple(v.startMs, v.endMs, TlKind.STOP))
        return parts.filter { it.second > it.first }.map { (a, b, k) -> TlSegment(k, a, b, v.lat, v.lon, place, 0, 0.0, "import", null, city, country, v.format, v.key) }
    }

    fun activitySegment(a: ActivityRow): TlSegment? {
        if (a.endMs <= a.startMs) return null
        val pts = ArrayList<Pair<Double, Double>>()
        if (a.startLat != null && a.startLon != null) pts += a.startLat to a.startLon
        val pathPts = a.pathJson?.let { runCatching { pathPoints(it) }.getOrNull() }.orEmpty()
        val d = a.distanceM?.takeIf { it > 0 } ?: run {
            val all = if (pathPts.size >= 2) pathPts else pts + listOfNotNull(if (a.endLat != null && a.endLon != null) a.endLat to a.endLon else null)
            all.zipWithNext().sumOf { (p, q) -> TimelineBuilder.haversineM(p.first, p.second, q.first, q.second) }
        }
        val end = if (a.endLat != null && a.endLon != null) a.endLat to a.endLon else pathPts.lastOrNull()
        val m = mode(a.type)
        return TlSegment(TlKind.MOVE, a.startMs, a.endMs, end?.first, end?.second, null, 0, d, m?.let { "mode:$it" } ?: "import", null, null, null, a.format, a.key)
    }

    /** #1887 a part of a path shorter than this, left between visits, is dropped (a cut edge, not a journey). */
    const val MIN_PIECE_MS = 60_000L

    /** Disjoint covered spans (start to end), merged as they are added. */
    private class Cover {
        private val iv = java.util.TreeMap<Long, Long>()
        fun add(a: Long, b: Long) {
            if (b <= a) return
            var s = a; var e = b
            iv.floorEntry(s)?.let { if (it.value >= s) { s = it.key; e = maxOf(e, it.value) } }
            while (true) { val n = iv.ceilingEntry(s) ?: break; if (n.key > e) break; e = maxOf(e, n.value); iv.remove(n.key) }
            iv[s] = e
        }
        /** The parts of [a, b) nothing covers. */
        fun free(a: Long, b: Long): List<Pair<Long, Long>> {
            val out = ArrayList<Pair<Long, Long>>()
            var c = a
            iv.floorEntry(a)?.let { if (it.value > c) c = it.value }
            for ((s, e) in iv.subMap(a, true, b, false)) { if (s > c) out += c to s; if (e > c) c = e }
            if (c < b) out += c to b
            return out
        }
    }

    /** [s] cut to the parts [cover] leaves free (distance and steps shrink with the time), pieces shorter than [MIN_PIECE_MS] dropped. */
    private fun cut(s: TlSegment, cover: Cover): List<TlSegment> {
        val span = (s.endMs - s.startMs).coerceAtLeast(1).toDouble()
        return cover.free(s.startMs, s.endMs).filter { it.second - it.first >= MIN_PIECE_MS }.map { (a, b) ->
            if (a == s.startMs && b == s.endMs) s else s.copy(startMs = a, endMs = b, distanceM = s.distanceM * (b - a) / span, steps = Math.round(s.steps * (b - a) / span))
        }
    }

    /**
     * #1887 a Timeline KML holds visits, paths with coordinates and paths without (the export's raw two-hour windows), overlapping each
     * other. A visit wins; a path with coordinates fills only the time no visit holds; a path without coordinates only the time neither
     * holds. Rows of every other source pass unchanged.
     */
    fun layerKml(segs: List<TlSegment>): List<TlSegment> {
        val kml = TakeoutFormat.KML.sourceTag
        val (mine, rest) = segs.partition { it.source == kml }
        if (mine.isEmpty()) return segs
        val stays = mine.filter { it.kind != TlKind.MOVE }
        val cover = Cover().also { c -> stays.forEach { c.add(it.startMs, it.endMs) } }
        val drawn = mine.filter { it.kind == TlKind.MOVE && it.lat != null }.sortedBy { it.startMs }.flatMap { cut(it, cover) }
        drawn.forEach { cover.add(it.startMs, it.endMs) }
        val bare = mine.filter { it.kind == TlKind.MOVE && it.lat == null }.sortedBy { it.startMs }.flatMap { m -> cut(m, cover).also { p -> p.forEach { cover.add(it.startMs, it.endMs) } } }
        return (rest + stays + drawn + bare).sortedBy { it.startMs }
    }

    /** Points of a timelinePath / waypoint / simplifiedRawPath JSON, whichever spelling (point "geo:..", latLng, latE7/lngE7, latitudeE7/longitudeE7). */
    fun pathPoints(json: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        fun pt(o: JSONObject) {
            val g = o.optString("point").takeIf { it.isNotEmpty() } ?: o.optString("latLng").takeIf { it.isNotEmpty() }
            when {
                g != null -> TkVal.latLng(g)?.let { out += it }
                o.has("latE7") && o.has("lngE7") -> out += o.getLong("latE7") / 1e7 to o.getLong("lngE7") / 1e7
                o.has("latitudeE7") && o.has("longitudeE7") -> out += o.getLong("latitudeE7") / 1e7 to o.getLong("longitudeE7") / 1e7
            }
        }
        val t = json.trim()
        if (t.startsWith("[")) { val a = org.json.JSONArray(t); for (i in 0 until a.length()) a.optJSONObject(i)?.let(::pt) }
        else {
            val o = JSONObject(t)
            for (k in listOf("points", "waypoints")) o.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::pt) }
        }
        return out
    }

    /** Imported positions without those a tracked fix already covers (within [windowMs]): tracked wins where both exist. [trackedTs] sorted. */
    fun preferTracked(imported: List<Fix>, trackedTs: LongArray, windowMs: Long = 15 * 60_000L): List<Fix> {
        if (trackedTs.isEmpty()) return imported
        return imported.filter { f ->
            var i = java.util.Arrays.binarySearch(trackedTs, f.ts)
            if (i < 0) i = -i - 1
            val near = (i < trackedTs.size && trackedTs[i] - f.ts <= windowMs) || (i > 0 && f.ts - trackedTs[i - 1] <= windowMs)
            !near
        }
    }

    /**
     * Stays and moves from the imported raw positions, a window at a time (a Records file can hold millions of fixes). A window's last
     * row may be cut by the window's edge, so it is dropped and the next window starts at its start: no stay is split by a chunk edge
     * (only a single row longer than a whole window is, and then only at the edge).
     */
    fun fromFixes(firstMs: Long, lastMs: Long, imported: (Long, Long) -> List<Fix>, trackedTs: (Long, Long) -> LongArray, nowMs: Long, zone: ZoneId,
                  chunkMs: Long = 30 * 86_400_000L, cancelled: () -> Boolean = { false }): List<TlSegment> {
        val out = ArrayList<TlSegment>()
        var cursor = Long.MIN_VALUE
        var a = firstMs
        while (a <= lastMs && !cancelled()) {
            val hi = a + chunkMs
            val fixes = preferTracked(imported(a, hi), trackedTs(a, hi))
            val more = hi < lastMs
            if (fixes.isEmpty()) { a = hi; continue }
            val segs = EngineTimeline.build(fixes, emptyList(), emptyMap(), emptyList(), emptyList(), nowMs, zone).segments.sortedBy { it.startMs }
                .filter { !(it.kind == TlKind.MOVE && it.durationMs > TimelineBuilder.MAX_GAP_MS) }   // sparse positions days apart are no journey
            val last = segs.lastOrNull()
            val dropLast = more && last != null && last.startMs > a
            for (s in if (dropLast) segs.dropLast(1) else segs) {
                if (s.startMs < cursor) continue
                out += s.copy(source = TakeoutFormat.RECORDS.sourceTag, ref = null)
                cursor = maxOf(cursor, s.endMs)
            }
            if (!more) break
            a = if (dropLast) last!!.startMs else hi
        }
        return out
    }

    /** One rollup per local date from segments (distance and time by mode, visits, sleep); steps are not in Google's data. */
    fun rollups(segs: List<TlSegment>, zone: ZoneId): List<DayRollup> {
        class Acc { var dist = 0.0; var moving = 0L; var stopped = 0L; var visits = 0; var sleep = 0L; val byMode = LinkedHashMap<String, Double>() }
        val days = java.util.TreeMap<LocalDate, Acc>()
        for (s in segs) {
            var a = s.startMs
            while (a < s.endMs) {
                val d = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
                val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val b = minOf(s.endMs, dayEnd)
                val f = (b - a).toDouble() / (s.endMs - s.startMs).coerceAtLeast(1)
                val acc = days.getOrPut(d) { Acc() }
                when (s.kind) {
                    TlKind.MOVE -> { acc.dist += s.distanceM * f; acc.moving += b - a; val ls = s.locSource; val m = if (ls != null && ls.startsWith("mode:")) ls.removePrefix("mode:") else "UNKNOWN"; acc.byMode[m] = (acc.byMode[m] ?: 0.0) + s.distanceM * f }
                    TlKind.STOP -> { acc.stopped += b - a; if (a == s.startMs) acc.visits++ }
                    TlKind.SLEEP -> { acc.sleep += b - a }
                }
                a = b
            }
        }
        return days.map { (d, x) -> DayRollup(d, 0, x.dist, x.moving, x.stopped, x.visits, x.sleep, x.byMode) }
    }
}

/**
 * #1853 after an import: the imported rows become segments (both stay stored next to the tracked ones; the views pick by priority),
 * the imported days get rollups, and the caller then rebuilds the aggregates (Trips, Places, Cities, World, Insights).
 * Everything runs through [SqlExec], so the Android store and the JVM test share the code.
 */
object TakeoutRebuild {
    private const val INSERT_SEG = "INSERT INTO timeline_segments (kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,prio,ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);"

    fun run(sql: SqlExec, zone: ZoneId = ZoneId.systemDefault(), nowMs: Long = System.currentTimeMillis(), cancelled: () -> Boolean = { false }, geo: Gazetteer? = null): Int {
        fun d(x: Any?) = (x as? Number)?.toDouble()
        fun l(x: Any?) = (x as? Number)?.toLong()
        val segs = ArrayList<TlSegment>()
        for (r in sql.rows("SELECT key,format,start_ms,end_ms,lat,lon,name,address,start_offset_min FROM imp_visits WHERE parent_key IS NULL ORDER BY start_ms;"))
            segs += TakeoutDerive.visitSegments(TakeoutDerive.VisitRow(r[0] as String, r[1] as String, l(r[2])!!, l(r[3])!!, d(r[4]), d(r[5]), r[6] as String?, r[7] as String?, l(r[8])), zone, geo)
        for (r in sql.rows("SELECT key,format,start_ms,end_ms,start_lat,start_lon,end_lat,end_lon,distance_m,activity_type,path_json FROM imp_activities ORDER BY start_ms;"))
            TakeoutDerive.activitySegment(TakeoutDerive.ActivityRow(r[0] as String, r[1] as String, l(r[2])!!, l(r[3])!!, d(r[4]), d(r[5]), d(r[6]), d(r[7]), d(r[8]), r[9] as String?, r[10] as String?))?.let { segs += it }
        val layered = TakeoutDerive.layerKml(segs); segs.clear(); segs.addAll(layered)   // #1887 the KML's visits win over its overlapping paths
        val range = sql.rows("SELECT MIN(ts),MAX(ts) FROM fixes WHERE source <> 'tracked';").firstOrNull()
        if (range != null && range[0] != null && !cancelled()) {
            segs += TakeoutDerive.fromFixes(l(range[0])!!, l(range[1])!!,
                { a, b -> sql.rows("SELECT ts,lat,lon,accuracy,speed,provider FROM fixes WHERE source <> 'tracked' AND ts BETWEEN ? AND ? ORDER BY ts;", listOf(a, b)).map { Fix(l(it[0])!!, d(it[1])!!, d(it[2])!!, d(it[3]), d(it[4]), it[5] as String? ?: "gps") } },
                { a, b -> sql.rows("SELECT ts FROM fixes WHERE source = 'tracked' AND ts BETWEEN ? AND ? ORDER BY ts;", listOf(a, b)).map { l(it[0])!! }.toLongArray() }, nowMs, zone, cancelled = cancelled)
        }
        sql.begin()
        try {
            sql.exec("DELETE FROM timeline_segments WHERE source <> 'tracked';")
            for (s in segs) sql.exec(INSERT_SEG, listOf(s.kind.name, s.startMs, s.endMs, s.lat, s.lon, s.place, s.steps, s.distanceM, s.locSource, s.neighborhood, s.city, s.country, s.source, TakeoutDerive.prioOf(s.source), s.ref))
            // rollups of the imported days: only where the phone recorded nothing (a tracked day's rollup is never replaced here)
            sql.exec("DELETE FROM day_rollups WHERE source <> 'tracked';")
            val visible = sql.rows("SELECT kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,neighborhood,city,country,source,ref FROM timeline_segments s WHERE s.prio > 0 AND NOT EXISTS (SELECT 1 FROM timeline_segments t WHERE t.prio < s.prio AND t.start_ms < s.end_ms AND t.end_ms > s.start_ms AND t.start_ms > s.start_ms - 604800000 AND COALESCE(t.loc_src,'') <> 'steps') ORDER BY s.start_ms;")
                .map { r -> TlSegment(TlKind.parse(r[0] as String), l(r[1])!!, l(r[2])!!, d(r[3]), d(r[4]), r[5] as String?, l(r[6])!!, d(r[7])!!, r[8] as String?, r[9] as String?, r[10] as String?, r[11] as String?, r[12] as String, r[13] as String?) }
            for (x in TakeoutDerive.rollups(visible, zone))
                sql.exec("INSERT OR IGNORE INTO day_rollups (date, steps, distance_m, moving_ms, stopped_ms, visits, sleep_ms, by_mode, source) VALUES (?,?,?,?,?,?,?,?,'import');",
                    listOf(x.date.toString(), x.steps, x.distanceM, x.movingMs, x.stoppedMs, x.visits, x.sleepMs, JSONObject().also { j -> x.distanceByMode.forEach { (k, v) -> j.put(k, v) } }.toString()))
            sql.commit()
        } catch (x: Throwable) { sql.rollback(); throw x }
        return segs.size
    }
}

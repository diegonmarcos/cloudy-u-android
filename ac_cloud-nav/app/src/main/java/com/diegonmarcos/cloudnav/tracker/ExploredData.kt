package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One stored `timeline_segments` row: its id, the segment (every field) and its priority. */
data class SegRow(val id: Long, val seg: TlSegment, val prio: Int)

/** One stored `fixes` row. */
data class FixRow(val id: Long, val ts: Long, val lat: Double, val lon: Double, val accuracyM: Double?, val speedMps: Double?, val provider: String, val source: String)

data class SliceRow(val startMs: Long, val endMs: Long, val count: Long, val source: String)
data class SleepSessionRow(val startMs: Long, val endMs: Long, val source: String)
data class DailyStepsRow(val date: String, val steps: Long, val source: String)

/** #1863 what Export writes and Restore reads: the store's segments, fixes, step slices, sleep sessions and daily steps of a range. */
data class ExportBundle(
    val fromMs: Long?, val toMs: Long?, val segments: List<SegRow>, val fixes: List<FixRow>,
    val slices: List<SliceRow> = emptyList(), val sleep: List<SleepSessionRow> = emptyList(), val daily: List<DailyStepsRow> = emptyList(),
)

/** #1863 Export: JSON (round-trips through [fromJson]) and KMZ (a zip holding doc.kml: a Placemark per stay, a LineString per move). Pure. */
object ExploredExport {
    const val FORMAT = "cloudnav-export"
    const val VERSION = 1

    private fun JSONObject.optNull(k: String, v: Any?): JSONObject = put(k, v ?: JSONObject.NULL)
    private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.l(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null

    fun toJson(b: ExportBundle): String {
        val segs = JSONArray()
        for (r in b.segments) { val s = r.seg
            segs.put(JSONObject().put("id", r.id).put("kind", s.kind.name).put("start", s.startMs).put("end", s.endMs).optNull("lat", s.lat).optNull("lon", s.lon)
                .optNull("place", s.place).put("steps", s.steps).put("distance_m", s.distanceM).optNull("loc_src", s.locSource).optNull("neighborhood", s.neighborhood)
                .optNull("city", s.city).optNull("country", s.country).put("source", s.source).optNull("ref", s.ref).put("prio", r.prio)) }
        val fixes = JSONArray()
        for (f in b.fixes) fixes.put(JSONObject().put("id", f.id).put("ts", f.ts).put("lat", f.lat).put("lon", f.lon).optNull("accuracy", f.accuracyM).optNull("speed", f.speedMps)
            .put("provider", f.provider).put("source", f.source))
        val slices = JSONArray(); b.slices.forEach { slices.put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("count", it.count).put("source", it.source)) }
        val sleep = JSONArray(); b.sleep.forEach { sleep.put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("source", it.source)) }
        val daily = JSONArray(); b.daily.forEach { daily.put(JSONObject().put("date", it.date).put("steps", it.steps).put("source", it.source)) }
        return JSONObject().put("format", FORMAT).put("version", VERSION).optNull("from", b.fromMs).optNull("to", b.toMs)
            .put("segments", segs).put("fixes", fixes).put("step_slices", slices).put("sleep_sessions", sleep).put("steps_daily", daily).toString()
    }

    /** Null when [text] is not a Cloud Nav export. */
    fun fromJson(text: String): ExportBundle? = runCatching {
        val j = JSONObject(text)
        if (j.optString("format") != FORMAT) return null
        fun arr(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        ExportBundle(j.l("from"), j.l("to"),
            arr("segments").map { o -> SegRow(o.getLong("id"), TlSegment(TlKind.parse(o.getString("kind")), o.getLong("start"), o.getLong("end"), o.d("lat"), o.d("lon"), o.s("place"),
                o.getLong("steps"), o.getDouble("distance_m"), o.s("loc_src"), o.s("neighborhood"), o.s("city"), o.s("country"), o.getString("source"), o.s("ref")), o.getInt("prio")) },
            arr("fixes").map { o -> FixRow(o.getLong("id"), o.getLong("ts"), o.getDouble("lat"), o.getDouble("lon"), o.d("accuracy"), o.d("speed"), o.getString("provider"), o.getString("source")) },
            arr("step_slices").map { SliceRow(it.getLong("start"), it.getLong("end"), it.getLong("count"), it.getString("source")) },
            arr("sleep_sessions").map { SleepSessionRow(it.getLong("start"), it.getLong("end"), it.getString("source")) },
            arr("steps_daily").map { DailyStepsRow(it.getString("date"), it.getLong("steps"), it.getString("source")) })
    }.getOrNull()

    /** The rows of [b] the store does not hold yet (same kind, span, source and place): what Restore inserts, so a restore twice adds nothing. */
    fun restorePlan(existing: List<SegRow>, existingFixes: List<FixRow>, b: ExportBundle): ExportBundle {
        fun sk(s: TlSegment) = listOf(s.kind, s.startMs, s.endMs, s.source, s.lat, s.lon, s.place)
        fun fk(f: FixRow) = listOf(f.ts, f.lat, f.lon, f.provider, f.source)
        val segKeys = existing.map { sk(it.seg) }.toHashSet(); val fixKeys = existingFixes.map { fk(it) }.toHashSet()
        return b.copy(segments = b.segments.filter { segKeys.add(sk(it.seg)) }, fixes = b.fixes.filter { fixKeys.add(fk(it)) })
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun coord(lat: Double, lon: Double) = "%.6f,%.6f,0".format(Locale.US, lon, lat)

    data class KmlCounts(val placemarks: Int, val lines: Int, val skipped: Int)

    /**
     * doc.kml: a Point Placemark per located stay or sleep, a LineString per move (from where the previous row was, through the fixes
     * recorded during the move, to where the move ended). A row with no location at all is counted in [KmlCounts.skipped].
     */
    fun kml(b: ExportBundle, zone: ZoneId = ZoneId.systemDefault()): Pair<String, KmlCounts> {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>Cloud Nav export</name>\n")
        for ((id, c) in listOf("stay" to "ff14b8a6", "sleep" to "fff16366", "move" to "ff5ec522"))
            sb.append("<Style id=\"$id\"><LineStyle><color>$c</color><width>4</width></LineStyle><IconStyle><color>$c</color></IconStyle></Style>\n")
        val segs = b.segments.sortedBy { it.seg.startMs }
        val fixes = b.fixes.sortedBy { it.ts }
        var pts = 0; var lines = 0; var skipped = 0
        var prev: Pair<Double, Double>? = null
        fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().toString()
        for (r in segs) { val s = r.seg
            val here = if (s.lat != null && s.lon != null) s.lat to s.lon else null
            if (s.kind == TlKind.MOVE) {
                val path = ArrayList<Pair<Double, Double>>()
                prev?.let { path += it }
                fixes.filter { it.ts > s.startMs && it.ts < s.endMs }.forEach { path += it.lat to it.lon }
                here?.let { path += it }
                if (path.isEmpty()) { skipped++; continue }
                if (path.size == 1) path += path[0]
                sb.append("<Placemark><name>").append(esc("Move " + (s.locSource?.removePrefix("mode:") ?: ""))).append("</name><styleUrl>#move</styleUrl>")
                    .append("<TimeSpan><begin>").append(t(s.startMs)).append("</begin><end>").append(t(s.endMs)).append("</end></TimeSpan>")
                    .append("<description>").append(esc("%.0f m, %d steps, %s".format(Locale.US, s.distanceM, s.steps, s.source))).append("</description>")
                    .append("<LineString><tessellate>1</tessellate><coordinates>").append(path.joinToString(" ") { coord(it.first, it.second) }).append("</coordinates></LineString></Placemark>\n")
                lines++
            } else {
                if (here == null) { skipped++; continue }
                val name = s.place?.takeIf { it.isNotBlank() } ?: listOfNotNull(s.neighborhood, s.city, s.country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Stay" }
                sb.append("<Placemark><name>").append(esc(name)).append("</name><styleUrl>#").append(if (s.kind == TlKind.SLEEP) "sleep" else "stay").append("</styleUrl>")
                    .append("<TimeSpan><begin>").append(t(s.startMs)).append("</begin><end>").append(t(s.endMs)).append("</end></TimeSpan>")
                    .append("<description>").append(esc(listOfNotNull(s.kind.name.lowercase(), s.city, s.country, s.source).joinToString(", "))).append("</description>")
                    .append("<Point><coordinates>").append(coord(here.first, here.second)).append("</coordinates></Point></Placemark>\n")
                pts++
            }
            if (here != null) prev = here
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString() to KmlCounts(pts, lines, skipped)
    }

    /** KMZ = a zip whose first entry is doc.kml. */
    fun kmz(b: ExportBundle, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("doc.kml")); z.write(kml(b, zone).first.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        return out.toByteArray()
    }

    fun fileName(from: LocalDate?, to: LocalDate?, ext: String) = "cloud-nav-" + (if (from == null) "all" else "$from" + (if (to != null && to != from) "_$to" else "")) + "." + ext
}

/** One duplicate: [drop] is removed, [keep] stays (the richest copy, then the most recent). [exact] = same kind, span and place. */
data class DupFinding(val keep: SegRow, val drop: SegRow, val exact: Boolean)

/** The preview Delete duplicates shows before anything goes: what is dropped, per day and per kind, and what each drop keeps. */
data class DedupePlan(val findings: List<DupFinding>, val dropFixIds: List<Long>, val perDay: Map<LocalDate, Map<String, Int>>) {
    val dropSegIds: List<Long> get() = findings.map { it.drop.id }
    val total: Int get() = findings.size + dropFixIds.size
    val exact: Int get() = findings.count { it.exact }
    val overlapping: Int get() = findings.count { !it.exact }
}

/**
 * #1863 Delete duplicates: the copies repeated or overlapping imports leave (the same stay, move or fix twice, from one source or
 * several). A row is a duplicate only when a kept row of the same kind covers at least [COVER] of its own time and (for a stay)
 * lies within [SAME_PLACE_M] (or has the same name): so its time and place are still in the store after it goes, and a unique
 * row is never removed. The richest copy is kept (name, address, coordinates, steps, distance), then the tracked source, then the newest.
 */
object ExploredDedupe {
    const val COVER = 0.8
    const val SAME_PLACE_M = 150.0

    fun richness(r: SegRow): Int { val s = r.seg
        return listOf(!s.place.isNullOrBlank(), !s.city.isNullOrBlank(), !s.country.isNullOrBlank(), !s.neighborhood.isNullOrBlank(),
            s.lat != null && s.lon != null, s.steps > 0, s.distanceM > 0.0, !s.ref.isNullOrBlank()).count { it }
    }

    private fun overlap(a: TlSegment, b: TlSegment) = (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0)

    private fun samePlace(a: TlSegment, b: TlSegment): Boolean = when {
        a.lat != null && a.lon != null && b.lat != null && b.lon != null ->
            com.diegonmarcos.cloudnav.maps.TimelineBuilder.haversineM(a.lat!!, a.lon!!, b.lat!!, b.lon!!) <= SAME_PLACE_M
        !a.place.isNullOrBlank() && !b.place.isNullOrBlank() -> a.place.equals(b.place, ignoreCase = true)
        else -> a.lat == null && b.lat == null && a.place.isNullOrBlank() && b.place.isNullOrBlank()
    }

    /** [candidate] is a copy of [kept]. */
    fun isDup(kept: TlSegment, candidate: TlSegment): Boolean {
        if (kept.kind != candidate.kind) return false
        val dur = (candidate.endMs - candidate.startMs)
        if (dur <= 0) return kept.startMs <= candidate.startMs && candidate.startMs <= kept.endMs && samePlace(kept, candidate)
        val o = overlap(kept, candidate)
        if (o < COVER * dur) return false
        // a move must match both ways: a short move inside a long one is a leg, not a copy
        if (candidate.kind == TlKind.MOVE) return o >= COVER * (kept.endMs - kept.startMs)
        return samePlace(kept, candidate)
    }

    fun exact(a: TlSegment, b: TlSegment) = a.kind == b.kind && a.startMs == b.startMs && a.endMs == b.endMs && a.lat == b.lat && a.lon == b.lon && (a.place ?: "") == (b.place ?: "")

    fun kindLabel(s: TlSegment) = when (s.kind) { TlKind.MOVE -> "move"; TlKind.SLEEP -> "sleep"; else -> "stay" }

    fun plan(segs: List<SegRow>, fixes: List<FixRow> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): DedupePlan {
        val order = segs.sortedWith(compareByDescending<SegRow> { richness(it) }.thenBy { it.prio }.thenByDescending { it.id })
        val kept = ArrayList<SegRow>(); val findings = ArrayList<DupFinding>()
        // kept rows by start, so a candidate only meets the rows near it
        val byStart = java.util.TreeMap<Long, MutableList<SegRow>>()
        val maxDur = segs.maxOfOrNull { it.seg.endMs - it.seg.startMs } ?: 0L
        for (r in order) {
            val lo = r.seg.startMs - maxDur.coerceAtLeast(0L)
            val near = byStart.subMap(lo, true, maxOf(lo, r.seg.endMs, r.seg.startMs), true).values.flatten()
            val k = near.firstOrNull { isDup(it.seg, r.seg) }
            if (k != null) findings += DupFinding(k, r, exact(k.seg, r.seg))
            else { kept += r; byStart.getOrPut(r.seg.startMs) { ArrayList() } += r }
        }
        val dropFix = ArrayList<Long>(); val fixDays = ArrayList<Long>()
        val seen = HashMap<String, FixRow>()
        for (f in fixes.sortedWith(compareBy<FixRow> { if (it.source == "tracked") 0 else 1 }.thenBy { it.accuracyM ?: Double.MAX_VALUE }.thenByDescending { it.id })) {
            val key = f.ts.toString() + "|" + "%.5f|%.5f".format(Locale.US, f.lat, f.lon)
            if (seen.containsKey(key)) { dropFix += f.id; fixDays += f.ts } else seen[key] = f
        }
        val perDay = java.util.TreeMap<LocalDate, MutableMap<String, Int>>()
        fun count(ms: Long, kind: String) { val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); val m = perDay.getOrPut(d) { java.util.TreeMap() }; m[kind] = (m[kind] ?: 0) + 1 }
        findings.forEach { count(it.drop.seg.startMs, kindLabel(it.drop.seg)) }
        fixDays.forEach { count(it, "fix") }
        return DedupePlan(findings.sortedBy { it.drop.seg.startMs }, dropFix, perDay)
    }

    /** The store after [plan] (what the JVM test compares with a clean import). */
    fun apply(segs: List<SegRow>, plan: DedupePlan): List<SegRow> { val drop = plan.dropSegIds.toHashSet(); return segs.filter { it.id !in drop } }
}

/** What Delete by dates does to the segments: rows wholly inside go, a row across an edge is cut at it (two parts when it spans the range). */
data class RangePlan(val deleteIds: List<Long>, val updates: List<SegRow>, val inserts: List<SegRow>, val fixIds: List<Long>) {
    val touched: Int get() = deleteIds.size + updates.size
}

/** #1863 Delete by dates: removes [fromMs, toMs) and nothing else. Steps and distance of a cut row shrink with its time. Pure. */
object ExploredDelete {
    private fun part(s: TlSegment, a: Long, b: Long): TlSegment {
        val f = (b - a).toDouble() / (s.endMs - s.startMs).coerceAtLeast(1)
        return s.copy(startMs = a, endMs = b, steps = Math.round(s.steps * f), distanceM = s.distanceM * f)
    }

    fun plan(segs: List<SegRow>, fixes: List<FixRow>, fromMs: Long, toMs: Long): RangePlan {
        val del = ArrayList<Long>(); val upd = ArrayList<SegRow>(); val ins = ArrayList<SegRow>()
        for (r in segs) { val s = r.seg
            if (s.endMs <= fromMs || s.startMs >= toMs) continue
            val left = s.startMs < fromMs; val right = s.endMs > toMs
            when {
                !left && !right -> del += r.id
                left && right -> { upd += r.copy(seg = part(s, s.startMs, fromMs)); ins += r.copy(id = -1, seg = part(s, toMs, s.endMs)) }
                left -> upd += r.copy(seg = part(s, s.startMs, fromMs))
                else -> upd += r.copy(seg = part(s, toMs, s.endMs))
            }
        }
        return RangePlan(del, upd, ins, fixes.filter { it.ts in fromMs until toMs }.map { it.id })
    }

    fun apply(segs: List<SegRow>, p: RangePlan): List<SegRow> {
        val del = p.deleteIds.toHashSet(); val upd = p.updates.associateBy { it.id }
        return (segs.filter { it.id !in del }.map { upd[it.id] ?: it } + p.inserts).sortedBy { it.seg.startMs }
    }

    /** The local day range [first, last] as epoch ms [from, to). */
    fun range(first: LocalDate, last: LocalDate, zone: ZoneId): Pair<Long, Long> = DayTimeline.dayStart(first, zone) to DayTimeline.dayStart(last.plusDays(1), zone)
}

/** #1863 a sortable column of the World / Cities / Places tables. [key] sorts (nulls last), [text] is the cell. */
class TableCol<T>(val id: String, val label: String, val numeric: Boolean, val weight: Float, val key: (T) -> Comparable<*>?, val text: (T) -> String)

object TableSort {
    /** [rows] by column [col] ([asc] or descending); nulls always last; ties keep the given order (stable). */
    @Suppress("UNCHECKED_CAST")
    fun <T> sort(rows: List<T>, col: TableCol<T>, asc: Boolean): List<T> {
        val (nulls, vals) = rows.partition { col.key(it) == null }
        val cmp = Comparator<T> { a, b ->
            val x = col.key(a) as Comparable<Any>; val y = col.key(b) as Comparable<Any>
            if (x is String && y is String) x.compareTo(y, ignoreCase = true).let { c -> if (c != 0) c else x.compareTo(y) } else x.compareTo(y)
        }
        return vals.sortedWith(if (asc) cmp else cmp.reversed()) + nulls
    }

    fun km(m: Double) = "%.1f".format(Locale.US, m / 1000.0)
    fun hours(ms: Long) = "%.1f h".format(Locale.US, ms / 3_600_000.0)
    fun n(v: Long) = "%,d".format(Locale.US, v)

    val WORLD: List<TableCol<WorldRow>> = listOf(
        TableCol<WorldRow>("continent", "Continent", false, 1.1f, { it.continent }, { it.continent }),
        TableCol<WorldRow>("country", "Country", false, 1.3f, { it.country }, { it.country }),
        TableCol<WorldRow>("cities", "Cities", true, 0.7f, { it.cities }, { it.cities.toString() }),
        TableCol<WorldRow>("places", "Places", true, 0.7f, { it.places }, { it.places.toString() }),
        TableCol<WorldRow>("days", "Days", true, 0.6f, { it.days }, { it.days.toString() }),
        TableCol<WorldRow>("time", "Time", true, 0.8f, { it.totalMs }, { hours(it.totalMs) }),
        TableCol<WorldRow>("km", "km", true, 0.8f, { it.distanceM }, { km(it.distanceM) }),
        TableCol<WorldRow>("steps", "Steps", true, 0.9f, { it.steps }, { n(it.steps) }),
        TableCol<WorldRow>("last", "Last visit", false, 1.1f, { it.lastVisit }, { it.lastVisit?.toString() ?: "n/a" }),
    )

    val CITIES: List<TableCol<CityRow>> = listOf(
        TableCol<CityRow>("continent", "Continent", false, 1.1f, { it.continent }, { it.continent }),
        TableCol<CityRow>("country", "Country", false, 1.1f, { it.country.ifBlank { null } }, { it.country.ifBlank { "n/a" } }),
        TableCol<CityRow>("city", "City", false, 1.2f, { it.city }, { it.city }),
        TableCol<CityRow>("places", "Places", true, 0.7f, { it.places }, { it.places.toString() }),
        TableCol<CityRow>("days", "Days", true, 0.6f, { it.days }, { it.days.toString() }),
        TableCol<CityRow>("time", "Time", true, 0.8f, { it.totalMs }, { hours(it.totalMs) }),
        TableCol<CityRow>("km", "km", true, 0.8f, { it.distanceM }, { km(it.distanceM) }),
        TableCol<CityRow>("steps", "Steps", true, 0.9f, { it.steps }, { n(it.steps) }),
        TableCol<CityRow>("last", "Last visit", false, 1.1f, { it.lastVisit }, { it.lastVisit?.toString() ?: "n/a" }),
    )

    val PLACES: List<TableCol<PlaceTotal>> = listOf(
        TableCol<PlaceTotal>("place", "Place", false, 1.6f, { it.label }, { it.label }),
        TableCol<PlaceTotal>("city", "City", false, 1.1f, { it.city?.ifBlank { null } }, { it.city ?: "" }),
        TableCol<PlaceTotal>("country", "Country", false, 1.1f, { it.country?.ifBlank { null } }, { it.country ?: "" }),
        TableCol<PlaceTotal>("visits", "Visits", true, 0.7f, { it.visits }, { it.visits.toString() }),
        TableCol<PlaceTotal>("time", "Time", true, 0.8f, { it.totalMs }, { hours(it.totalMs) }),
    )
}

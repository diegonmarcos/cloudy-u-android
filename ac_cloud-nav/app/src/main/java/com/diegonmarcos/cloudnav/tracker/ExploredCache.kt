package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlSegment
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** A stay a ranking counts (named, not missing): what Places / Cities / World fold. */
data class StayPart(val label: String, val city: String?, val country: String?, val ms: Long)

/** A move's share of a city / country: distance and steps, placed where it ended (else where it began). */
data class MovePart(val city: String?, val country: String?, val distanceM: Double, val steps: Long)

/**
 * #1863 everything Explored needs from one date, computed once and kept: the [DayStats] (Trips, Insights, the fleet door's
 * `tracker/daily`), the ranked stays and moves (Places, Cities, World), the calendar [blocks] and whether the store holds a track
 * that day. [sum] is the checksum of every input the date was computed from; [version] the schema it was written with.
 */
data class DayDigest(
    val date: LocalDate, val sum: String, val stats: DayStats, val stays: List<StayPart>, val moves: List<MovePart>,
    val blocks: List<Block>, val tracks: Boolean, val version: Int = ExploredCache.SCHEMA,
)

/** Where the digests live: maps.db `aggregates` (kind `digest`, key = date) on the phone, a map in the JVM tests. */
interface DigestBackend {
    fun load(): Map<LocalDate, String>
    fun put(rows: Map<LocalDate, String>)
    fun remove(dates: Collection<LocalDate>)
    fun clear()
}

class MemoryDigestBackend : DigestBackend {
    val rows = LinkedHashMap<LocalDate, String>()
    var writes = 0
    override fun load(): Map<LocalDate, String> = LinkedHashMap(rows)
    override fun put(rows: Map<LocalDate, String>) { writes += rows.size; this.rows.putAll(rows) }
    override fun remove(dates: Collection<LocalDate>) { dates.forEach { rows.remove(it) } }
    override fun clear() { rows.clear() }
}

/** What one open found: the digests in date order, the dates it had to compute (stale or dirty), the live ones (today), the ones read. */
data class CacheResult(val digests: List<DayDigest>, val recomputed: List<LocalDate>, val live: List<LocalDate>, val reused: Int) {
    val stats: List<DayStats> get() = digests.map { it.stats }
}

/**
 * #1863 the Explored cache. A past day is immutable: its digest is computed once, persisted, and read back on every open. A day is
 * recomputed only when its checksum changes (an import, a delete or an owner correction touched one of its inputs), when it was
 * marked dirty, or when [SCHEMA] moved. Today (any date whose inputs still grow: the sleep window reaches 12 h past midnight) is
 * live: computed each time and never stored. The aggregates are folded from the digests ([ExploredFold]), so an open is one read of
 * the store's rows plus a checksum per day, never a re-analysis.
 */
object ExploredCache {
    const val SCHEMA = 1
    const val WIDE_MS = 12 * 3_600_000L

    /** Counts every digest computed in this process (the JVM test proves a second open adds nothing). */
    @Volatile var computeCount = 0L

    fun isLive(d: LocalDate, nowMs: Long, zone: ZoneId): Boolean = DayTimeline.dayStart(d.plusDays(1), zone) + WIDE_MS > nowMs

    /** The store's rows a date is computed from: those overlapping its sleep window [start - 12 h, end + 12 h). [sorted] by start. */
    class Slicer(store: List<TlSegment>) {
        private val sorted = store.sortedBy { it.startMs }
        private val starts = LongArray(sorted.size) { sorted[it].startMs }
        private val maxDur = sorted.maxOfOrNull { it.endMs - it.startMs } ?: 0L
        fun slice(from: Long, to: Long): List<TlSegment> {
            var lo = 0; var hi = starts.size
            val key = from - maxDur
            while (lo < hi) { val m = (lo + hi) ushr 1; if (starts[m] < key) lo = m + 1 else hi = m }
            val out = ArrayList<TlSegment>()
            var i = lo
            while (i < sorted.size && sorted[i].startMs < to) { val s = sorted[i]; if (s.endMs > from) out += s; i++ }
            return out
        }
    }

    fun window(d: LocalDate, zone: ZoneId): Pair<Long, Long> = DayTimeline.dayStart(d, zone) - WIDE_MS to DayTimeline.dayStart(d.plusDays(1), zone) + WIDE_MS

    /** Every input of a date: its rows (all fields), the corrections that touch its window, the remembered places, the schema. */
    fun checksum(d: LocalDate, slice: List<TlSegment>, corrections: List<Correction>, memory: List<Remembered>, zone: ZoneId): String {
        val (a, b) = window(d, zone)
        val md = MessageDigest.getInstance("SHA-256")
        fun add(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        add("v$SCHEMA"); add(d.toString()); add(zone.id)
        for (s in slice) add(s.toString())
        for (c in corrections) if (c.endMs > a && c.startMs < b) add(c.toString())
        for (m in relevantMemory(slice, memory)) add(m.toString())
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * The remembered places that can name a stay of [slice]: [DayTimeline.build] gives an unnamed located stay its nearest remembered
     * place when that one lies within its own radius, so a place farther than the largest radius from every stay of the date is never
     * the nearest one that applies. A confirm in one city therefore leaves the other cities' days cached.
     */
    fun relevantMemory(slice: List<TlSegment>, memory: List<Remembered>): List<Remembered> {
        val pts = slice.filter { it.kind != com.diegonmarcos.cloudnav.maps.TlKind.MOVE && it.lat != null && it.lon != null }
        if (pts.isEmpty()) return emptyList()
        // an entry within the largest radius can be the nearest (and so decide, even by not matching); one beyond it never is
        val maxR = memory.filter { it.place.located }.maxOfOrNull { it.radiusM } ?: return emptyList()
        return memory.filter { m -> m.place.located && pts.any { s -> DayTimeline.dist(Place(s.lat, s.lon), m.place) <= maxR } }
    }

    /** The full computation of one date (the same [Aggregates.dayOf] a full recompute runs). */
    fun compute(slice: List<TlSegment>, d: LocalDate, sum: String, nowMs: Long, zone: ZoneId, corrections: List<Correction>, memory: List<Remembered>): DayDigest {
        computeCount++
        val (stats, day) = Aggregates.dayOf(slice, d, nowMs, zone, corrections, memory)
        val a = DayTimeline.dayStart(d, zone); val e = DayTimeline.dayStart(d.plusDays(1), zone)
        val stays = day.filterIsInstance<DaySeg.Stay>().filter { !it.missing && it.place?.label != null }
            .map { StayPart(it.place!!.label!!, it.place.city, it.place.country, it.durationMs) }
        val moves = day.filterIsInstance<DaySeg.Move>().map { m -> val p = m.to ?: m.from; MovePart(p?.city, p?.country, m.distanceM, m.steps) }
        val blocks = if (day.isEmpty()) emptyList() else DaySegments.blocks(day, a, minOf(e, maxOf(nowMs, a)))
        return DayDigest(d, sum, stats, stays, moves, blocks, slice.any { it.endMs > a && it.startMs < e })
    }

    /**
     * One open: read the stored digests, keep each whose checksum still matches, compute the rest (and every [dirty] date), store
     * what was computed (live dates excepted). [dates] in order; the result keeps that order.
     */
    fun refresh(backend: DigestBackend, store: List<TlSegment>, dates: List<LocalDate>, nowMs: Long, zone: ZoneId,
                corrections: List<Correction> = emptyList(), memory: List<Remembered> = emptyList(), dirty: Set<LocalDate> = emptySet(),
                cancelled: () -> Boolean = { false }): CacheResult {
        val stored = runCatching { backend.load() }.getOrDefault(emptyMap())
        val slicer = Slicer(store)
        val out = ArrayList<DayDigest>(dates.size); val recomputed = ArrayList<LocalDate>(); val live = ArrayList<LocalDate>()
        val toPut = LinkedHashMap<LocalDate, String>()
        var reused = 0
        // #1887 cancellable between dates; what was computed before the cancel is stored, so the next refresh resumes from it
        try {
            for (d in dates) {
                if (cancelled()) throw java.util.concurrent.CancellationException("Explored refresh cancelled")
                val (a, b) = window(d, zone)
                val slice = slicer.slice(a, b)
                val sum = checksum(d, slice, corrections, memory, zone)
                if (isLive(d, nowMs, zone)) { live += d; out += compute(slice, d, sum, nowMs, zone, corrections, memory); continue }
                val old = if (d in dirty) null else stored[d]?.let { fromJson(it) }
                if (old != null && old.sum == sum && old.version == SCHEMA) { out += old; reused++; continue }
                val dg = compute(slice, d, sum, nowMs, zone, corrections, memory)
                out += dg; recomputed += d; toPut[d] = toJson(dg).toString()
            }
        } finally { if (toPut.isNotEmpty()) runCatching { backend.put(toPut) } }
        return CacheResult(out, recomputed, live, reused)
    }

    /** The dates an edit of [fromMs, toMs) touches (a date's sleep window reaches 12 h either side): drop their digests. */
    fun dirtyDates(fromMs: Long, toMs: Long, zone: ZoneId): List<LocalDate> {
        if (toMs <= fromMs) return emptyList()
        val first = java.time.Instant.ofEpochMilli(fromMs - WIDE_MS).atZone(zone).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(toMs + WIDE_MS).atZone(zone).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    fun markDirty(backend: DigestBackend, fromMs: Long, toMs: Long, zone: ZoneId) = backend.remove(dirtyDates(fromMs, toMs, zone))

    // --- the stored form ---

    private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
    private fun JSONObject.dblOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

    fun toJson(d: DayDigest): JSONObject {
        val st = d.stats
        val modes = JSONArray(); st.summary.byMode.forEach { (m, v) -> modes.put(JSONArray().put(m.name).put(v.distanceM).put(v.movingMs)) }
        val stays = JSONArray(); d.stays.forEach { stays.put(JSONArray().put(it.label).put(it.city ?: JSONObject.NULL).put(it.country ?: JSONObject.NULL).put(it.ms)) }
        val moves = JSONArray(); d.moves.forEach { moves.put(JSONArray().put(it.city ?: JSONObject.NULL).put(it.country ?: JSONObject.NULL).put(it.distanceM).put(it.steps)) }
        val blocks = JSONArray(); d.blocks.forEach { b -> blocks.put(JSONArray().put(b.startMs).put(b.endMs).put(b.state.name).put(b.mode?.name ?: JSONObject.NULL).put(b.city ?: JSONObject.NULL).put(b.label ?: JSONObject.NULL)) }
        return JSONObject().put("v", d.version).put("date", d.date.toString()).put("sum", d.sum).put("tracks", d.tracks)
            .put("sleep_ms", st.sleepMs).put("sleep_city", st.sleepCity ?: JSONObject.NULL).put("sleep_lat", st.sleepLat ?: JSONObject.NULL).put("sleep_lon", st.sleepLon ?: JSONObject.NULL)
            .put("visits", st.summary.visits).put("modes", modes).put("stays", stays).put("moves", moves).put("blocks", blocks)
    }

    fun fromJson(s: String): DayDigest? = runCatching {
        val j = JSONObject(s)
        fun JSONArray.str(i: Int): String? = if (isNull(i)) null else getString(i)
        val modes = LinkedHashMap<Mode, ModeStat>()
        j.getJSONArray("modes").let { a -> for (i in 0 until a.length()) { val r = a.getJSONArray(i); modes[Mode.parse(r.getString(0))] = ModeStat(r.getDouble(1), r.getLong(2)) } }
        val date = LocalDate.parse(j.getString("date"))
        val stats = DayStats(date, j.getLong("sleep_ms"), j.strOrNull("sleep_city"), DaySummary(j.getInt("visits"), modes), j.dblOrNull("sleep_lat"), j.dblOrNull("sleep_lon"))
        val stays = j.getJSONArray("stays").let { a -> (0 until a.length()).map { a.getJSONArray(it) }.map { StayPart(it.getString(0), it.str(1), it.str(2), it.getLong(3)) } }
        val moves = j.getJSONArray("moves").let { a -> (0 until a.length()).map { a.getJSONArray(it) }.map { MovePart(it.str(0), it.str(1), it.getDouble(2), it.getLong(3)) } }
        val blocks = j.getJSONArray("blocks").let { a -> (0 until a.length()).map { a.getJSONArray(it) }.map {
            Block(it.getLong(0), it.getLong(1), SegState.parse(it.getString(2)), it.str(3)?.let { m -> Mode.parse(m) }, it.str(4), it.str(5)) } }
        DayDigest(date, j.getString("sum"), stats, stays, moves, blocks, j.getBoolean("tracks"), j.getInt("v"))
    }.getOrNull()
}

/** A World table row: one country. [days], [totalMs] are the old World tab's fields; the rest are #1863's. */
data class WorldRow(val continent: String, val country: String, val cities: Int, val places: Int, val days: Int, val totalMs: Long,
                    val distanceM: Double, val steps: Long, val lastVisit: LocalDate?)

/** A Cities table row: one city. [days], [totalMs], [country] are the old Cities tab's fields. */
data class CityRow(val continent: String, val country: String, val city: String, val places: Int, val days: Int, val totalMs: Long,
                   val distanceM: Double, val steps: Long, val lastVisit: LocalDate?)

/**
 * #1863 the aggregates folded from the digests. [places], [cities], [countries] equal [Rankings] over the same days (the JVM parity
 * test holds it); [world] and [cityTable] add continent, cities / places counts, distance, steps and the last visit.
 */
object ExploredFold {
    fun places(ds: List<DayDigest>): List<PlaceTotal> {
        val m = LinkedHashMap<String, MutableList<StayPart>>()
        for (d in ds) for (s in d.stays) m.getOrPut(s.label + "|" + (s.city ?: "")) { ArrayList() } += s
        return m.values.map { v -> val p = v.first(); PlaceTotal(p.label, p.city, p.country, v.size, v.sumOf { it.ms }) }.sortedByDescending { it.totalMs }
    }

    fun cities(ds: List<DayDigest>): List<CityTotal> {
        val m = LinkedHashMap<String, Triple<String?, MutableSet<Int>, Long>>()
        ds.forEachIndexed { di, d -> for (s in d.stays) {
            val c = s.city ?: continue
            val o = m[c]; m[c] = Triple(o?.first ?: s.country, (o?.second ?: mutableSetOf()).also { it.add(di) }, (o?.third ?: 0L) + s.ms)
        } }
        return m.map { (c, t) -> CityTotal(c, t.first, t.second.size, t.third) }.sortedByDescending { it.totalMs }
    }

    fun countries(ds: List<DayDigest>): List<CountryTotal> {
        val m = LinkedHashMap<String, Pair<MutableSet<Int>, Long>>()
        ds.forEachIndexed { di, d -> for (s in d.stays) {
            val c = s.country ?: continue
            val o = m[c]; m[c] = Pair((o?.first ?: mutableSetOf()).also { it.add(di) }, (o?.second ?: 0L) + s.ms)
        } }
        return m.map { (c, t) -> CountryTotal(c, t.first.size, t.second) }.sortedByDescending { it.totalMs }
    }

    private class Acc { val days = HashSet<LocalDate>(); val places = HashSet<String>(); val cities = HashSet<String>(); var ms = 0L; var dist = 0.0; var steps = 0L; var last: LocalDate? = null; var country: String? = null }

    fun world(ds: List<DayDigest>): List<WorldRow> {
        val m = LinkedHashMap<String, Acc>()
        for (d in ds) {
            for (s in d.stays) { val c = s.country ?: continue
                val a = m.getOrPut(c) { Acc() }; a.days += d.date; a.places += s.label + "|" + (s.city ?: ""); s.city?.let { a.cities += it }; a.ms += s.ms
                if (a.last == null || d.date.isAfter(a.last)) a.last = d.date }
            for (mv in d.moves) { val c = mv.country ?: continue; val a = m.getOrPut(c) { Acc() }; a.dist += mv.distanceM; a.steps += mv.steps }
        }
        return m.map { (c, a) -> WorldRow(Continents.of(c), c, a.cities.size, a.places.size, a.days.size, a.ms, a.dist, a.steps, a.last) }.sortedByDescending { it.totalMs }
    }

    fun cityTable(ds: List<DayDigest>): List<CityRow> {
        val m = LinkedHashMap<String, Acc>()
        for (d in ds) {
            for (s in d.stays) { val c = s.city ?: continue
                val a = m.getOrPut(c) { Acc() }; if (a.country == null) a.country = s.country
                a.days += d.date; a.places += s.label; a.ms += s.ms
                if (a.last == null || d.date.isAfter(a.last)) a.last = d.date }
            for (mv in d.moves) { val c = mv.city ?: continue; val a = m.getOrPut(c) { Acc() }; if (a.country == null) a.country = mv.country; a.dist += mv.distanceM; a.steps += mv.steps }
        }
        return m.map { (c, a) -> CityRow(Continents.of(a.country), a.country ?: "", c, a.places.size, a.days.size, a.ms, a.dist, a.steps, a.last) }.sortedByDescending { it.totalMs }
    }
}

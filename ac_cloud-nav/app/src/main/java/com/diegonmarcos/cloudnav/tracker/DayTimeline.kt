package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import java.time.LocalDate
import java.time.ZoneId

/** How the phone travelled. Inferred from average speed plus the step cadence (the on-device activity signal). */
enum class Mode { WALK, RUN, BIKE, CAR, TRAIN, PLANE, UNKNOWN;
    /** Counted in the "distance per vehicle" dashboard (walking and running are not vehicles). */
    val vehicle: Boolean get() = this == BIKE || this == CAR || this == TRAIN || this == PLANE
    companion object { fun parse(s: String?): Mode = values().firstOrNull { it.name.equals(s, true) } ?: UNKNOWN }
}

enum class StayKind { SLEEP, IDLE }

/** A resolved place. street / postcode / state / [category] (a [PoiCategory] name) are filled by [PlaceNamer] (open reverse-geocode + POI lookup). */
data class Place(
    val lat: Double?, val lon: Double?, val name: String? = null, val street: String? = null, val postcode: String? = null,
    val neighborhood: String? = null, val city: String? = null, val state: String? = null, val country: String? = null,
    val category: String? = null,
) {
    val located: Boolean get() = lat != null && lon != null
    val poi: PoiCategory get() = PoiCategory.parse(category)
    /** Still worth asking the geocoder: located but no street, postcode, state or country yet. */
    val needsAddress: Boolean get() = located && (street.isNullOrBlank() || postcode.isNullOrBlank() || state.isNullOrBlank() || country.isNullOrBlank())
    val label: String? get() = name?.takeIf { it.isNotBlank() } ?: listOfNotNull(street, neighborhood, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    val address: String get() = listOfNotNull(street, postcode, neighborhood, city, state, country).filter { it.isNotBlank() }.joinToString(", ")
}

/** One segment of a day. A day is a gapless list of these. */
sealed class DaySeg {
    abstract val startMs: Long
    abstract val endMs: Long
    val durationMs: Long get() = endMs - startMs

    /** [confirmed] = the owner said "Yes" or edited it. [inferred] = no recorded place: shown as a missing visit. */
    data class Stay(override val startMs: Long, override val endMs: Long, val kind: StayKind, val place: Place?,
                    val confirmed: Boolean = false) : DaySeg() {
        val missing: Boolean get() = place == null || (!place.located && place.name.isNullOrBlank())
    }

    data class Move(override val startMs: Long, override val endMs: Long, val distanceM: Double, val steps: Long,
                    val avgSpeedMps: Double, val movingMs: Long, val from: Place?, val to: Place?, val mode: Mode,
                    val modeFixed: Boolean = false) : DaySeg()
}

object ModeInference {
    const val WALK_MAX_MPS = 2.2     // 7.9 km/h
    const val BIKE_MAX_MPS = 7.0     // 25 km/h
    const val CAR_MAX_MPS = 30.0     // 108 km/h
    const val TRAIN_MAX_MPS = 100.0  // 360 km/h; above is a plane
    const val RUN_CADENCE = 130.0    // steps per minute
    const val MIN_STEP_CADENCE = 40.0
    const val STILL_MPS = 0.2

    /** The Activity Recognition class a client may supply for a move (null = none available; the step cadence stands in). */
    enum class Hint { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE }

    /** [stepsPerMin] is the cadence over the moving time (0 = no steps seen); [hint] overrides only where it is consistent with the speed. */
    fun infer(avgMps: Double, stepsPerMin: Double, hint: Hint?): Mode = when {
        avgMps.isNaN() -> Mode.UNKNOWN
        hint == Hint.RUNNING && avgMps <= 6.5 -> Mode.RUN
        hint == Hint.WALKING && avgMps <= 3.0 -> Mode.WALK
        hint == Hint.ON_BICYCLE && avgMps > WALK_MAX_MPS && avgMps <= CAR_MAX_MPS -> Mode.BIKE
        hint == Hint.IN_VEHICLE && avgMps > WALK_MAX_MPS && avgMps <= BIKE_MAX_MPS -> Mode.CAR
        else -> infer(avgMps, stepsPerMin)
    }

    /** [stepsPerMin] is the cadence over the moving time (0 = no steps seen). */
    fun infer(avgMps: Double, stepsPerMin: Double): Mode = when {
        avgMps.isNaN() -> Mode.UNKNOWN
        stepsPerMin >= RUN_CADENCE && avgMps <= 6.5 -> Mode.RUN
        stepsPerMin >= MIN_STEP_CADENCE && avgMps <= 3.0 -> Mode.WALK   // steps beat speed: a phone on foot
        avgMps <= WALK_MAX_MPS -> Mode.WALK
        avgMps <= BIKE_MAX_MPS -> if (stepsPerMin >= MIN_STEP_CADENCE) Mode.RUN else Mode.BIKE
        avgMps <= CAR_MAX_MPS -> Mode.CAR
        avgMps <= TRAIN_MAX_MPS -> Mode.TRAIN
        else -> Mode.PLANE
    }
}

/** What the owner changed. Matched to a segment by time overlap, so a rebuild that shifts a boundary keeps it. */
data class Correction(
    val type: Type, val startMs: Long, val endMs: Long,
    val mode: Mode? = null, val place: Place? = null, val atMs: Long = 0L, val stayKind: StayKind = StayKind.IDLE,
) {
    enum class Type { CONFIRM, EDIT_PLACE, EDIT_MODE, DELETE, SPLIT, ADD_VISIT }
}

/** A place the owner confirmed or named: later stays near it take its name (corrections feed back into the segmentation). */
data class Remembered(val place: Place, val radiusM: Double = 100.0)

object DayTimeline {
    const val MISSING_MIN_MS = 10 * 60_000L
    const val DEFAULT_STEP_M = 0.75
    private const val MATCH = 0.5

    fun dayStart(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun placeOf(s: TlSegment) = Place(s.lat, s.lon, s.place?.takeIf { it.isNotBlank() }, null, null, s.neighborhood, s.city, null, s.country)

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0)

    /**
     * The gapless day [fromMs, toMs): the store's segments clipped to it, corrections applied, remembered names filled,
     * every gap closed (a stay stretches over it, else an unplaced stay = missing visit), moves linked to their ends.
     */
    fun build(store: List<TlSegment>, fromMs: Long, toMs: Long, corrections: List<Correction> = emptyList(),
              memory: List<Remembered> = emptyList(), stepM: Double = DEFAULT_STEP_M): List<DaySeg> {
        if (toMs <= fromMs) return emptyList()
        var cursor = fromMs
        var raw = ArrayList<DaySeg>()
        for (s in store.sortedBy { it.startMs }) {
            val a = maxOf(s.startMs, cursor); val b = minOf(s.endMs, toMs)
            if (b <= a) continue
            val f = (b - a).toDouble() / (s.endMs - s.startMs).coerceAtLeast(1)
            if (s.kind == TlKind.MOVE) {
                val steps = Math.round(s.steps * f)
                var dist = s.distanceM * f
                if (dist <= 0.0 && steps > 0) dist = steps * stepM
                raw += moveOf(a, b, dist, steps, null, null, null)
            } else raw += DaySeg.Stay(a, b, if (s.kind == TlKind.SLEEP) StayKind.SLEEP else StayKind.IDLE, placeOf(s))
            cursor = b
        }
        raw = ArrayList(raw.map { if (it is DaySeg.Stay && it.place?.name == null) it.copy(place = remember(it.place, memory)) else it })
        var segs: List<DaySeg> = raw
        for (c in corrections) segs = apply(segs, c, fromMs, toMs)
        return link(fill(segs, fromMs, toMs))
    }

    internal fun moveOf(a: Long, b: Long, dist: Double, steps: Long, from: Place?, to: Place?, fixed: Mode?): DaySeg.Move {
        val moving = b - a
        val avg = if (moving > 0) dist / (moving / 1000.0) else 0.0
        val cadence = if (moving > 0) steps / (moving / 60_000.0) else 0.0
        return DaySeg.Move(a, b, dist, steps, avg, moving, from, to, fixed ?: ModeInference.infer(avg, cadence), fixed != null)
    }

    private fun remember(p: Place?, memory: List<Remembered>): Place? {
        if (p == null || !p.located) return p
        val hit = memory.filter { it.place.located }.minByOrNull { dist(p, it.place) } ?: return p
        return if (dist(p, hit.place) <= hit.radiusM) hit.place.copy(lat = p.lat, lon = p.lon) else p
    }

    fun dist(a: Place, b: Place): Double = com.diegonmarcos.cloudnav.maps.TimelineBuilder.haversineM(a.lat!!, a.lon!!, b.lat!!, b.lon!!)

    private fun bestIdx(segs: List<DaySeg>, c: Correction, stay: Boolean?): Int {
        val span = (c.endMs - c.startMs).coerceAtLeast(1)
        var best = -1; var bestO = 0L
        segs.forEachIndexed { i, s ->
            if (stay == true && s !is DaySeg.Stay) return@forEachIndexed
            if (stay == false && s !is DaySeg.Move) return@forEachIndexed
            val o = overlap(c.startMs, c.endMs, s.startMs, s.endMs)
            if (o > bestO) { best = i; bestO = o }
        }
        return if (best >= 0 && bestO.toDouble() / span >= MATCH) best else -1
    }

    private fun apply(segs: List<DaySeg>, c: Correction, from: Long, to: Long): List<DaySeg> {
        val out = segs.toMutableList()
        when (c.type) {
            Correction.Type.CONFIRM -> bestIdx(out, c, true).takeIf { it >= 0 }?.let { i -> out[i] = (out[i] as DaySeg.Stay).copy(confirmed = true) }
            Correction.Type.EDIT_PLACE -> bestIdx(out, c, true).takeIf { it >= 0 }?.let { i -> out[i] = (out[i] as DaySeg.Stay).copy(place = c.place, confirmed = true) }
            Correction.Type.EDIT_MODE -> bestIdx(out, c, false).takeIf { it >= 0 }?.let { i -> out[i] = (out[i] as DaySeg.Move).copy(mode = c.mode ?: Mode.UNKNOWN, modeFixed = true) }
            Correction.Type.DELETE -> bestIdx(out, c, null).takeIf { it >= 0 }?.let { out.removeAt(it) }
            Correction.Type.SPLIT -> bestIdx(out, c, null).takeIf { it >= 0 }?.let { i ->
                val s = out[i]
                if (c.atMs > s.startMs && c.atMs < s.endMs) {
                    out.removeAt(i)
                    when (s) {
                        is DaySeg.Stay -> { out.add(i, s.copy(endMs = c.atMs)); out.add(i + 1, s.copy(startMs = c.atMs)) }
                        is DaySeg.Move -> {
                            val f = (c.atMs - s.startMs).toDouble() / s.durationMs
                            val st = Math.round(s.steps * f)
                            out.add(i, moveOf(s.startMs, c.atMs, s.distanceM * f, st, s.from, s.to, if (s.modeFixed) s.mode else null))
                            out.add(i + 1, moveOf(c.atMs, s.endMs, s.distanceM * (1 - f), s.steps - st, s.from, s.to, if (s.modeFixed) s.mode else null))
                        }
                    }
                }
            }
            Correction.Type.ADD_VISIT -> {
                val a = maxOf(c.startMs, from); val b = minOf(c.endMs, to)
                if (b > a) {
                    val cut = ArrayList<DaySeg>()
                    for (s in out) {
                        if (s.endMs <= a || s.startMs >= b) { cut += s; continue }
                        if (s.startMs < a) cut += trim(s, s.startMs, a)
                        if (s.endMs > b) cut += trim(s, b, s.endMs)
                    }
                    cut += DaySeg.Stay(a, b, c.stayKind, c.place, confirmed = true)
                    out.clear(); out.addAll(cut.sortedBy { it.startMs })
                }
            }
        }
        return out
    }

    private fun trim(s: DaySeg, a: Long, b: Long): DaySeg = when (s) {
        is DaySeg.Stay -> s.copy(startMs = a, endMs = b)
        is DaySeg.Move -> {
            val f = (b - a).toDouble() / s.durationMs.coerceAtLeast(1)
            moveOf(a, b, s.distanceM * f, Math.round(s.steps * f), s.from, s.to, if (s.modeFixed) s.mode else null)
        }
    }

    /** Closes every gap so the list covers [from, to) exactly. */
    fun fill(segs: List<DaySeg>, from: Long, to: Long): List<DaySeg> {
        val out = ArrayList<DaySeg>()
        var cursor = from
        for (s in segs.sortedBy { it.startMs }) {
            val a = maxOf(s.startMs, cursor); val b = minOf(s.endMs, to)
            if (b <= a) continue
            val seg = if (a == s.startMs && b == s.endMs) s else trim(s, a, b)
            if (a > cursor) {
                val last = out.lastOrNull()
                when {
                    last is DaySeg.Stay && last.kind == StayKind.IDLE -> out[out.size - 1] = last.copy(endMs = a)   // the phone stayed through the gap
                    last is DaySeg.Stay -> out += DaySeg.Stay(cursor, a, StayKind.IDLE, last.place)                  // never stretch a sleep
                    seg is DaySeg.Stay && seg.kind == StayKind.IDLE -> { out += seg.copy(startMs = cursor); cursor = b; continue }  // the next stay began earlier
                    seg is DaySeg.Stay -> out += DaySeg.Stay(cursor, a, StayKind.IDLE, seg.place)
                    else -> out += DaySeg.Stay(cursor, a, StayKind.IDLE, (last as? DaySeg.Move)?.to ?: (seg as? DaySeg.Move)?.from)
                }
            }
            out += seg
            cursor = b
        }
        if (cursor < to) {
            val last = out.lastOrNull()
            when {
                last is DaySeg.Stay && last.kind == StayKind.IDLE -> out[out.size - 1] = last.copy(endMs = to)
                last is DaySeg.Stay -> out += DaySeg.Stay(cursor, to, StayKind.IDLE, last.place)
                else -> out += DaySeg.Stay(cursor, to, StayKind.IDLE, (last as? DaySeg.Move)?.to)
            }
        }
        // an empty day is one unplaced stay
        if (out.isEmpty()) out += DaySeg.Stay(from, to, StayKind.IDLE, null)
        return out
    }

    /** Each move gets the places of the stays on either side. */
    /** Re-links moves to their neighbours' places (after a stay was renamed). */
    fun fillLinks(segs: List<DaySeg>): List<DaySeg> = link(segs)

    private fun link(segs: List<DaySeg>): List<DaySeg> = segs.mapIndexed { i, s ->
        if (s is DaySeg.Move) s.copy(from = (segs.getOrNull(i - 1) as? DaySeg.Stay)?.place, to = (segs.getOrNull(i + 1) as? DaySeg.Stay)?.place) else s
    }

    /** "Missing visit": time with no recorded place, long enough to matter. The UI offers "Add visit" for each. */
    fun missingVisits(day: List<DaySeg>): List<DaySeg.Stay> =
        day.filterIsInstance<DaySeg.Stay>().filter { it.missing && it.durationMs >= MISSING_MIN_MS }

    /** Visits the owner has not confirmed yet ("Visited X? Yes / Edit"). */
    fun unconfirmed(day: List<DaySeg>): List<DaySeg.Stay> =
        day.filterIsInstance<DaySeg.Stay>().filter { !it.confirmed && !it.missing && it.kind == StayKind.IDLE }
}

data class ModeStat(val distanceM: Double, val movingMs: Long)

data class DaySummary(val visits: Int, val byMode: Map<Mode, ModeStat>) {
    val totalDistanceM: Double get() = byMode.values.sumOf { it.distanceM }
    val totalMovingMs: Long get() = byMode.values.sumOf { it.movingMs }
}

data class DayStats(
    val date: LocalDate, val sleepMs: Long, val sleepCity: String?, val summary: DaySummary,
    val sleepLat: Double? = null, val sleepLon: Double? = null,
) {
    val walkM: Double get() = summary.byMode[Mode.WALK]?.distanceM ?: 0.0
    val runM: Double get() = summary.byMode[Mode.RUN]?.distanceM ?: 0.0
    val vehicleM: Map<Mode, Double> get() = summary.byMode.filterKeys { it.vehicle }.mapValues { it.value.distanceM }
}

object Aggregates {
    const val MAX_SLEEP_MS = 16 * 3_600_000L

    /** Per mode: distance and moving time; plus the number of (non-missing) visits. */
    fun summary(day: List<DaySeg>): DaySummary {
        val m = LinkedHashMap<Mode, ModeStat>()
        for (s in day) if (s is DaySeg.Move) {
            val o = m[s.mode]; m[s.mode] = ModeStat((o?.distanceM ?: 0.0) + s.distanceM, (o?.movingMs ?: 0L) + s.movingMs)
        }
        return DaySummary(day.count { it is DaySeg.Stay && !it.missing }, m)
    }

    /**
     * One entry per date. Distance per mode comes from the date's own gapless day. Sleep belongs to the day it ENDS on
     * (a 22:00 to 07:00 night is the morning's sleep), city = where the longest such sleep was.
     */
    fun perDay(store: List<TlSegment>, days: List<LocalDate>, nowMs: Long, zone: ZoneId,
               corrections: List<Correction> = emptyList(), memory: List<Remembered> = emptyList()): List<DayStats> = days.map { d ->
        val a = DayTimeline.dayStart(d, zone); val e = DayTimeline.dayStart(d.plusDays(1), zone); val b = minOf(e, nowMs)
        val day = DayTimeline.build(store, a, b, corrections, memory)
        val wide = DayTimeline.build(store, a - 12 * 3_600_000L, minOf(e + 12 * 3_600_000L, nowMs), corrections, memory)
        val sleeps = wide.filterIsInstance<DaySeg.Stay>().filter { it.kind == StayKind.SLEEP && it.endMs > a && it.endMs <= e }
        val longest = sleeps.maxByOrNull { it.durationMs }?.place
        val city = longest?.let { it.city ?: it.label }
        DayStats(d, sleeps.sumOf { minOf(it.durationMs, MAX_SLEEP_MS) }, city, summary(day), longest?.lat, longest?.lon)
    }
}

/** Turns an owner action into a [Correction]; the day is rebuilt with it, so the result is what the list shows next. */
object Edits {
    fun confirm(s: DaySeg.Stay) = Correction(Correction.Type.CONFIRM, s.startMs, s.endMs, place = s.place)
    fun editPlace(s: DaySeg.Stay, name: String) =
        Correction(Correction.Type.EDIT_PLACE, s.startMs, s.endMs, place = (s.place ?: Place(null, null)).copy(name = name.trim().ifBlank { null }))
    fun editMode(m: DaySeg.Move, mode: Mode) = Correction(Correction.Type.EDIT_MODE, m.startMs, m.endMs, mode = mode)
    fun delete(s: DaySeg) = Correction(Correction.Type.DELETE, s.startMs, s.endMs)
    fun split(s: DaySeg, atMs: Long) = Correction(Correction.Type.SPLIT, s.startMs, s.endMs, atMs = atMs)
    /** [near] lends its coordinates (the neighbouring stay) so the new visit sits on the map. */
    fun addVisit(startMs: Long, endMs: Long, name: String, near: Place?, kind: StayKind = StayKind.IDLE) =
        Correction(Correction.Type.ADD_VISIT, startMs, endMs, place = Place(near?.lat, near?.lon, name.trim().ifBlank { null }, near?.street, near?.postcode, near?.neighborhood, near?.city, near?.state, near?.country), stayKind = kind)
}

data class PlaceTotal(val label: String, val city: String?, val country: String?, val visits: Int, val totalMs: Long)
data class CityTotal(val city: String, val country: String?, val days: Int, val totalMs: Long)
data class CountryTotal(val country: String, val days: Int, val totalMs: Long)

/** Places / Cities / World tabs: rankings over a set of gapless days (unplaced time never counts). */
object Rankings {
    private fun stays(days: List<List<DaySeg>>) = days.map { d -> d.filterIsInstance<DaySeg.Stay>().filter { !it.missing && it.place?.label != null } }

    fun places(days: List<List<DaySeg>>): List<PlaceTotal> =
        stays(days).flatten().groupBy { (it.place!!.label!!) + "|" + (it.place?.city ?: "") }.map { (_, v) ->
            val p = v.first().place!!
            PlaceTotal(p.label!!, p.city, p.country, v.size, v.sumOf { it.durationMs })
        }.sortedByDescending { it.totalMs }

    fun cities(days: List<List<DaySeg>>): List<CityTotal> {
        val m = LinkedHashMap<String, Triple<String?, MutableSet<Int>, Long>>()
        stays(days).forEachIndexed { di, d -> for (s in d) {
            val c = s.place?.city ?: continue
            val o = m[c]; m[c] = Triple(o?.first ?: s.place?.country, (o?.second ?: mutableSetOf()).also { it.add(di) }, (o?.third ?: 0L) + s.durationMs)
        } }
        return m.map { (c, t) -> CityTotal(c, t.first, t.second.size, t.third) }.sortedByDescending { it.totalMs }
    }

    fun countries(days: List<List<DaySeg>>): List<CountryTotal> {
        val m = LinkedHashMap<String, Pair<MutableSet<Int>, Long>>()
        stays(days).forEachIndexed { di, d -> for (s in d) {
            val c = s.place?.country ?: continue
            val o = m[c]; m[c] = Pair((o?.first ?: mutableSetOf()).also { it.add(di) }, (o?.second ?: 0L) + s.durationMs)
        } }
        return m.map { (c, t) -> CountryTotal(c, t.first.size, t.second) }.sortedByDescending { it.totalMs }
    }
}

package com.diegonmarcos.cloudnav.tracker

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * #1862 / #1863 the ONE segment model the Explored calendar (Day / Week / Month) and the Tracker Timeline strips draw: a day cut into
 * [Block]s that cover it without gap or overlap, each in one [SegState]. Sleep, idle (an unnamed stay), stay (a named place), move,
 * and no data (a missing visit or a time the store holds nothing for). Pure, so a JVM test holds it.
 */
enum class SegState(val label: String, val argb: Long) {
    SLEEP("Sleep", 0xFF6366F1), IDLE("Idle", 0xFF64748B), STAY("Stay", 0xFF14B8A6), MOVE("Move", 0xFF22C55E), NONE("No data", 0xFF1F2937);
    companion object { fun parse(s: String?): SegState = values().firstOrNull { it.name.equals(s, true) } ?: NONE }
}

/** One coloured rectangle of a day. [mode] is set on a move, [city] on a sleep (where it started) and a stay. */
data class Block(val startMs: Long, val endMs: Long, val state: SegState, val mode: Mode? = null, val city: String? = null, val label: String? = null) {
    val durationMs: Long get() = endMs - startMs
}

/** When the sun rises and sets on a day; [known] = computed from a location, else the fixed 06:00 / 18:00 curve. */
data class Daylight(val sunriseMs: Long?, val sunsetMs: Long?, val known: Boolean, val polarDay: Boolean = false)

object DaySegments {
    const val DAY_MS = 86_400_000L
    const val NIGHT_ARGB = 0xFF000000
    const val DAY_ARGB = 0xFF3B82F6
    const val TWILIGHT_MS = 60 * 60_000L

    fun stateOf(s: DaySeg): SegState = when (s) {
        is DaySeg.Move -> SegState.MOVE
        is DaySeg.Stay -> when {
            s.kind == StayKind.SLEEP -> SegState.SLEEP
            s.missing -> SegState.NONE
            !s.place?.name.isNullOrBlank() || (s.place?.poi ?: PoiCategory.OTHER) != PoiCategory.OTHER || s.confirmed -> SegState.STAY
            else -> SegState.IDLE
        }
    }

    /** The state of a Tracker > Timeline row (`tracker/segments`): the same rules as [stateOf] over the wire fields. */
    fun stateOf(r: TimelineRow): SegState = when {
        r.type == "MOVE" -> SegState.MOVE
        r.mode == "sleep" -> SegState.SLEEP
        r.lat == null && r.lon == null && !r.named -> SegState.NONE
        r.named -> SegState.STAY
        else -> SegState.IDLE
    }

    private fun cityOf(p: Place?): String? = p?.city?.takeIf { it.isNotBlank() } ?: p?.label

    /** The day's segments as blocks over [from, to): every millisecond in exactly one block (gaps are [SegState.NONE]), neighbours of one kind merged. */
    fun blocks(day: List<DaySeg>, from: Long, to: Long): List<Block> = cover(day.map { s ->
        when (s) {
            is DaySeg.Move -> Block(s.startMs, s.endMs, SegState.MOVE, s.mode, cityOf(s.to ?: s.from))
            is DaySeg.Stay -> Block(s.startMs, s.endMs, stateOf(s), null, cityOf(s.place), s.place?.label)
        }
    }, from, to)

    fun blocksOfRows(rows: List<TimelineRow>, from: Long, to: Long): List<Block> = cover(rows.map { r ->
        Block(r.startMs, r.endMs, stateOf(r), if (r.type == "MOVE") Mode.parse(r.mode) else null, r.city.ifBlank { r.title.takeIf { it != "Unnamed place" && it != "Move" } }, r.title)
    }, from, to)

    /** Clips to [from, to), drops overlaps (the earlier block wins), fills gaps with NONE and merges equal neighbours. */
    fun cover(src: List<Block>, from: Long, to: Long): List<Block> {
        if (to <= from) return emptyList()
        val out = ArrayList<Block>()
        var cursor = from
        for (b in src.sortedBy { it.startMs }) {
            val a = maxOf(b.startMs, cursor); val e = minOf(b.endMs, to)
            if (e <= a) continue
            if (a > cursor) out += Block(cursor, a, SegState.NONE)
            out += b.copy(startMs = a, endMs = e)
            cursor = e
        }
        if (cursor < to) out += Block(cursor, to, SegState.NONE)
        val merged = ArrayList<Block>()
        for (b in out) {
            val l = merged.lastOrNull()
            if (l != null && l.state == b.state && l.mode == b.mode && l.city == b.city && l.label == b.label && l.endMs == b.startMs) merged[merged.size - 1] = l.copy(endMs = b.endMs)
            else merged += b
        }
        return merged
    }

    /** Icon key per transport mode (DayIcons maps it to a drawable); every mode has its own. */
    fun modeIcon(m: Mode): String = when (m) {
        Mode.WALK -> "walk"; Mode.RUN -> "run"; Mode.BIKE -> "bike"; Mode.CAR -> "car"; Mode.TRAIN -> "transit"; Mode.PLANE -> "plane"; Mode.UNKNOWN -> "unknown"
    }

    /** The city under each sleep: the place where the sleep started. */
    fun sleepCities(blocks: List<Block>): List<Pair<Block, String>> = blocks.filter { it.state == SegState.SLEEP }.map { it to (it.city ?: "unknown city") }

    /** Fraction 0..1 of [ms] in [from, to) (the strip's x axis). */
    fun x(ms: Long, from: Long, to: Long): Float = ((ms - from).toDouble() / (to - from).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

    // --- daylight (NOAA sunrise equation, zenith 90.833 deg) ---

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    /** UTC hour of sunrise ([rise]) or sunset; null = the sun does not cross the horizon that day; NaN never returned. */
    private fun utcHour(date: LocalDate, lat: Double, lon: Double, rise: Boolean): Pair<Double?, Boolean> {
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((if (rise) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l)); val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1) return null to false     // the sun never rises
        if (cosH < -1) return null to true     // the sun never sets
        val h = (if (rise) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        return norm(tt - lngHour, 24.0) to false
    }

    /** Sunrise / sunset of [date] at [lat], [lon]; no location = the fixed 06:00 / 18:00 curve ([Daylight.known] false). */
    fun daylight(date: LocalDate, lat: Double?, lon: Double?, zone: ZoneId): Daylight {
        val start = DayTimeline.dayStart(date, zone)
        if (lat == null || lon == null) return Daylight(start + 6 * 3_600_000L, start + 18 * 3_600_000L, false)
        fun at(rise: Boolean): Pair<Long?, Boolean> {
            val (h, polarDay) = utcHour(date, lat, lon, rise)
            if (h == null) return null to polarDay
            var ms = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + Math.round(h * 3_600_000)
            while (ms < start) ms += DAY_MS
            while (ms >= start + DAY_MS) ms -= DAY_MS
            return ms to false
        }
        val (r, pd) = at(true); val (s, _) = at(false)
        return Daylight(r, s, true, polarDay = r == null && pd)
    }

    /** Sky brightness 0 (night) .. 1 (day) at [ms]: ramps over [TWILIGHT_MS] around sunrise and sunset. */
    fun brightness(ms: Long, d: Daylight): Float {
        val r = d.sunriseMs; val s = d.sunsetMs
        if (r == null || s == null) return if (d.polarDay) 1f else 0f
        fun ramp(v: Long, a: Long) = ((v - (a - TWILIGHT_MS / 2)).toDouble() / TWILIGHT_MS).coerceIn(0.0, 1.0)
        val f = if (r <= s) minOf(ramp(ms, r), 1.0 - ramp(ms, s)) else maxOf(ramp(ms, r), 1.0 - ramp(ms, s))
        return f.toFloat().coerceIn(0f, 1f)
    }

    /** One ARGB between black (night) and blue (day). */
    fun skyArgb(f: Float): Long {
        fun ch(c: Long, sh: Int) = ((c shr sh) and 0xFF).toInt()
        fun mix(sh: Int) = Math.round(ch(NIGHT_ARGB, sh) + (ch(DAY_ARGB, sh) - ch(NIGHT_ARGB, sh)) * f.coerceIn(0f, 1f)).toLong()
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** The day/night strip: [steps] + 1 colour stops (fraction, ARGB) over [from, from + 24 h). */
    fun gradient(d: Daylight, from: Long, steps: Int = 48): List<Pair<Float, Long>> =
        (0..steps).map { i -> val f = i.toFloat() / steps; f to skyArgb(brightness(from + Math.round(f.toDouble() * DAY_MS), d)) }
}

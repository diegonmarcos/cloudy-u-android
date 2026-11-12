package com.diegonmarcos.clocktools

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * #1667 the world clock as a time converter. Every row is one city at ONE reference instant (live "now", or a date and
 * time the user picked in the default city); the default city is the first row; under every row is a 00-24 h day bar.
 *
 * The bars share one window: the default city's local day that holds the reference instant, from its midnight to the
 * next (23 or 25 hours on a DST change day). The default city's bar therefore reads 00 h at the left and 24 h at the
 * right; every other city's bar covers the SAME instants, so its left edge is the default city's midnight in that
 * city's local time (+5:30 starts at 05:30, -3:30 at 20:30 the day before). A vertical line at the reference instant
 * crosses every bar at that city's own local hour. Day and night on each bar come from [Sun] for the city's coordinates
 * and each local date the window touches; a zone with no known coordinates falls back to 06:00-18:00 local, marked as an
 * estimate.
 */
object WorldTable {
    data class LatLon(val lat: Double, val lon: Double)

    /** A part of the window as fractions 0..1 of it, [day] true for daylight. */
    data class Span(val from: Double, val to: Double, val day: Boolean)

    /** A local hour mark on a bar: where (0..1) and which local hour (0, 6, 12, 18). */
    data class Mark(val at: Double, val hour: Int)

    enum class Sky { TIMES, POLAR_DAY, POLAR_NIGHT, ESTIMATED }

    data class Bar(
        val windowStart: Long,
        val windowEnd: Long,
        val spans: List<Span>,
        val marks: List<Mark>,
        /** The reference instant on the bar, 0..1. */
        val reference: Double,
        /** The city's local time at the window's start, as minutes after its midnight: the bar's left edge. */
        val startMinute: Int,
        /** Sunrise and sunset (local minutes) on the city's date at the reference instant, when the sun rises and sets. */
        val rise: Int?,
        val set: Int?,
        val sky: Sky,
    )

    data class Row(val zone: String, val isDefault: Boolean, val bar: Bar)

    /** The default city's local day holding [ref]: [midnight, next midnight) as epoch ms. A DST day is 23 or 25 h long. */
    fun window(home: ZoneId, ref: Long): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(ref).atZone(home).toLocalDate()
        return date.atStartOfDay(home).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(home).toInstant().toEpochMilli()
    }

    /** The reference instant for a date and time picked in [home]; a time inside a DST gap moves forward, as an alarm's does. */
    fun reference(home: ZoneId, date: LocalDate, minuteOfDay: Int): Long {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(m / 60, m % 60)), home).toInstant().toEpochMilli()
    }

    /** The table: the default city first, then every other city in its order; a city listed twice, or the default listed again, shows once. */
    fun rows(home: String, zones: List<String>, ref: Long, coords: (String) -> LatLon?): List<Row> {
        val homeZone = zoneOrNull(home) ?: ZoneId.of("UTC")
        val (from, to) = window(homeZone, ref)
        return (listOf(home) + zones.filter { it != home }).distinct().mapNotNull { id ->
            val z = zoneOrNull(id) ?: return@mapNotNull null
            Row(id, id == home, bar(z, from, to, ref, coords(id)))
        }
    }

    fun zoneOrNull(id: String?): ZoneId? = runCatching { ZoneId.of(id ?: return null) }.getOrNull()

    /** One city's bar over the window [from, to). */
    fun bar(zone: ZoneId, from: Long, to: Long, ref: Long, at: LatLon?): Bar {
        val len = (to - from).coerceAtLeast(1).toDouble()
        fun frac(t: Long) = ((t - from) / len).coerceIn(0.0, 1.0)
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate()
        // Daylight intervals of every local date the window touches (plus the day before, whose sunset can fall after
        // midnight UTC in a far-east or far-west zone), clipped to the window.
        val days = mutableListOf<Pair<Long, Long>>()
        var polar: Sky? = null
        var d = first.minusDays(1)
        while (!d.isAfter(last.plusDays(1))) {
            val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (at == null) {
                days += reference(zone, d, 6 * 60) to reference(zone, d, 18 * 60)
            } else when (val s = Sun.day(d, at.lat, at.lon)) {
                is Sun.Day.Times -> days += s.rise to s.set
                Sun.Day.PolarDay -> { days += dayStart to dayEnd; if (d == local(zone, ref)) polar = Sky.POLAR_DAY }
                Sun.Day.PolarNight -> if (d == local(zone, ref)) polar = Sky.POLAR_NIGHT
            }
            d = d.plusDays(1)
        }
        val light = merge(days.map { (a, b) -> maxOf(a, from) to minOf(b, to) }.filter { it.first < it.second })
        val spans = mutableListOf<Span>()
        var cursor = from
        for ((a, b) in light) {
            if (a > cursor) spans += Span(frac(cursor), frac(a), false)
            spans += Span(frac(a), frac(b), true)
            cursor = b
        }
        if (cursor < to) spans += Span(frac(cursor), 1.0, false)
        val today = local(zone, ref)
        val sun = at?.let { Sun.day(today, it.lat, it.lon) }
        val sky = when {
            at == null -> Sky.ESTIMATED
            sun is Sun.Day.Times -> Sky.TIMES
            else -> polar ?: if (sun == Sun.Day.PolarDay) Sky.POLAR_DAY else Sky.POLAR_NIGHT
        }
        val times = sun as? Sun.Day.Times
        return Bar(
            windowStart = from, windowEnd = to, spans = spans, marks = marks(zone, from, to), reference = frac(ref),
            startMinute = minuteOf(zone, from),
            rise = times?.let { minuteOf(zone, it.rise) } ?: if (at == null) 6 * 60 else null,
            set = times?.let { minuteOf(zone, it.set) } ?: if (at == null) 18 * 60 else null,
            sky = sky,
        )
    }

    private fun local(zone: ZoneId, t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    fun minuteOf(zone: ZoneId, t: Long): Int = Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }

    /** Overlapping or touching intervals joined, in order. */
    fun merge(xs: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((a, b) in xs.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && a <= last.second) out[out.size - 1] = last.first to maxOf(last.second, b) else out += a to b
        }
        return out
    }

    /** Where [zone]'s local 00, 06, 12 and 18 h fall inside [from, to): at a half-hour offset they sit between the default city's. */
    fun marks(zone: ZoneId, from: Long, to: Long): List<Mark> {
        val out = mutableListOf<Mark>()
        var d = local(zone, from)
        val last = local(zone, to)
        while (!d.isAfter(last)) {
            for (h in listOf(0, 6, 12, 18)) {
                val t = ZonedDateTime.of(d, LocalTime.of(h, 0), zone).toInstant().toEpochMilli()
                if (t in from until to) out += Mark((t - from) / (to - from).toDouble(), h)
            }
            d = d.plusDays(1)
        }
        return out.distinctBy { it.at }.sortedBy { it.at }
    }

    /** "+5:30", "−3:30", "+5:45", "±0": [zone]'s offset from [home] at [ref]. */
    fun shiftText(zone: ZoneId, home: ZoneId, ref: Long): String {
        val i = Instant.ofEpochMilli(ref)
        val diff = zone.rules.getOffset(i).totalSeconds - home.rules.getOffset(i).totalSeconds
        if (diff == 0) return "±0"
        val h = abs(diff) / 3600
        val m = (abs(diff) % 3600) / 60
        return (if (diff > 0) "+" else "−") + h + ":" + "%02d".format(m)
    }

    /** "05:30": a local minute of the day. */
    fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60 % 24, minute % 60)
}

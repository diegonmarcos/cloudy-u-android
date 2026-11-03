package com.diegonmarcos.cloudme.health

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Min / mean / max over the buckets that hold data, and how many do. */
data class Stats(val min: Double, val avg: Double, val max: Double, val n: Int)

/**
 * Pure chart aggregation of every Health page (HealthChartsTest): raw readings (Health Connect records or the seeded mock) into the
 * D / W / M / Y bucket grid of [Tracked.grid], the min / avg / max strip, the headline number and the heart-rate zones.
 */
object HealthAgg {
    /**
     * [readings] into [range]'s grid ending [today]. Sum metrics ([avg] false) add up inside a slot; a Year slot then holds the
     * month's mean DAILY value (its total over [Tracked.daysCovered]). Avg metrics take the slot's mean. Readings outside the
     * window are dropped; a slot with no reading is null, never 0.
     */
    fun bucketize(readings: List<Reading>, range: TrackedRange, today: LocalDate, zone: ZoneId, avg: Boolean): List<Bucket> {
        val bySlot = readings.groupBy { Tracked.slot(range, LocalDateTime.ofInstant(it.at, zone)) }
        val values = bySlot.mapValues { (start, rs) ->
            val v = rs.map { it.value }
            if (avg) v.average() else v.sum() / Tracked.daysCovered(range, start, today)
        }
        return Tracked.grid(range, today, values)
    }

    fun stats(buckets: List<Bucket>): Stats? {
        val v = buckets.mapNotNull { it.value }
        if (v.isEmpty()) return null
        return Stats(v.min(), v.average(), v.max(), v.size)
    }

    /**
     * The detail's headline: Day shows the day's total (sum metrics) or mean (avg metrics); Week, Month and Year show the mean per
     * bucket with data, i.e. the daily average (a Year bucket is already a daily mean). Null when nothing holds data.
     */
    fun headline(m: TrackedMetric, range: TrackedRange, buckets: List<Bucket>): Double? =
        if (range == TrackedRange.DAY) Tracked.summary(m, buckets) else stats(buckets)?.avg

    /** The headline's caption, matching [headline]. */
    fun headlineLabel(m: TrackedMetric, range: TrackedRange): String = when {
        range == TrackedRange.DAY -> if (m.avg) "Today's average" else "Today's total"
        else -> if (m.avg) "${range.label} average" else "Daily average, last ${range.label.lowercase()}"
    }

    /** The five heart-rate zones by % of max HR: 50-60, 60-70, 70-80, 80-90, 90+. Below 50 % is rest, not a zone. */
    val ZONE_LABELS = listOf("Warm-up", "Fat burn", "Cardio", "Hard", "Peak")

    /** Samples per zone, zone 1 first; a sample below 50 % of [maxHr] counts in none. */
    fun zones(bpm: List<Double>, maxHr: Double): IntArray {
        val out = IntArray(5)
        for (b in bpm) {
            val pct = b / maxHr
            if (pct < 0.5) continue
            out[((pct - 0.5) / 0.1).toInt().coerceIn(0, 4)]++
        }
        return out
    }

    /** First and last values that exist, for a "change over the window" line; null with fewer than two. */
    fun change(buckets: List<Bucket>): Double? {
        val v = buckets.mapNotNull { it.value }
        return if (v.size < 2) null else v.last() - v.first()
    }
}

package com.diegonmarcos.cloudnav.tracker

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** [count] steps taken between [startMs] and [endMs] (a Health Connect StepsRecord, or a tracker sample interval). */
data class StepSlice(val startMs: Long, val endMs: Long, val count: Long)

enum class SegKind { STOP, MOVING }

/** A stretch of the day: standing still, or moving with [steps], [distanceM] over [startMs]..[endMs]. */
data class Segment(val kind: SegKind, val startMs: Long, val endMs: Long, val steps: Long = 0, val distanceM: Double = 0.0) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Stop / Moving segmentation from step cadence. The day is cut into [BIN_MS] bins, each slice's steps
 * spread over the bins it overlaps; a bin is moving when its cadence reaches [MIN_CADENCE_SPM]. Moving
 * bins separated by at most [MAX_GAP_BINS] quiet bins join (a red light is not a stop); a run with fewer
 * than [MIN_RUN_STEPS] steps is noise and becomes part of the stop around it.
 */
object Segmenter {
    const val BIN_MS = 5 * 60_000L
    const val MIN_CADENCE_SPM = 30
    const val MAX_GAP_BINS = 1
    const val MIN_RUN_STEPS = 100L

    /**
     * @param fromMs..toMs the window to describe (a day, clipped to now for today)
     * @param distanceOf the walked distance of a moving run when location says better than steps x stride
     */
    fun segment(
        slices: List<StepSlice>, fromMs: Long, toMs: Long, stepM: Double = DeadReckoning.DEFAULT_STEP_M,
        distanceOf: (Long, Long) -> Double? = { _, _ -> null },
    ): List<Segment> {
        if (toMs <= fromMs) return emptyList()
        val n = (((toMs - fromMs) + BIN_MS - 1) / BIN_MS).toInt()
        val bins = DoubleArray(n)
        for (s in slices) {
            val dur = s.endMs - s.startMs
            if (s.count <= 0) continue
            if (dur <= 0) {
                val b = ((s.startMs - fromMs) / BIN_MS).toInt()
                if (b in 0 until n) bins[b] += s.count.toDouble()
                continue
            }
            val first = max(0L, (s.startMs - fromMs) / BIN_MS).toInt()
            val last = min(n - 1L, (s.endMs - 1 - fromMs) / BIN_MS).toInt()
            for (b in first..last) {
                val bs = fromMs + b * BIN_MS; val be = bs + BIN_MS
                val ov = min(be, s.endMs) - max(bs, s.startMs)
                if (ov > 0) bins[b] += s.count * (ov.toDouble() / dur)
            }
        }
        val minPerBin = MIN_CADENCE_SPM * (BIN_MS / 60_000.0)
        val moving = BooleanArray(n) { bins[it] >= minPerBin }
        // close short gaps between moving bins
        var i = 0
        while (i < n) {
            if (!moving[i]) {
                var j = i
                while (j < n && !moving[j]) j++
                if (i > 0 && j < n && j - i <= MAX_GAP_BINS) for (k in i until j) moving[k] = true
                i = j
            } else i++
        }
        // runs -> segments
        val out = mutableListOf<Segment>()
        fun addStop(a: Long, b: Long) {
            if (b <= a) return
            val last = out.lastOrNull()
            if (last != null && last.kind == SegKind.STOP) out[out.lastIndex] = last.copy(endMs = b)
            else out += Segment(SegKind.STOP, a, b)
        }
        i = 0
        while (i < n) {
            var j = i
            while (j < n && moving[j] == moving[i]) j++
            val a = fromMs + i * BIN_MS; val b = min(toMs, fromMs + j * BIN_MS)
            if (moving[i]) {
                val steps = Math.round((i until j).sumOf { bins[it] })
                if (steps >= MIN_RUN_STEPS) {
                    val d = distanceOf(a, b) ?: (steps * stepM)
                    out += Segment(SegKind.MOVING, a, b, steps, d)
                } else addStop(a, b)
            } else addStop(a, b)
            i = j
        }
        return out
    }
}

enum class Period { DAY, WEEK, MONTH }

/** One bar of the Day / Week / Month chart: the period starting at [start]. */
data class Bucket(val start: LocalDate, val steps: Long)

data class Totals(val day: Long, val week: Long, val month: Long)

object StepStats {
    fun weekStart(d: LocalDate): LocalDate = d.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Per-day totals of [slices] in [zone] (a slice is attributed to the day it started). */
    fun perDay(slices: List<StepSlice>, zone: ZoneId): Map<LocalDate, Long> {
        val m = sortedMapOf<LocalDate, Long>()
        for (s in slices) {
            val d = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
            m[d] = (m[d] ?: 0L) + s.count
        }
        return m
    }

    /** Today / this ISO week (Mon..Sun) / this calendar month, up to and including [today]. */
    fun totals(daily: Map<LocalDate, Long>, today: LocalDate): Totals {
        val ws = weekStart(today); val ms = today.withDayOfMonth(1)
        var w = 0L; var m = 0L
        for ((d, c) in daily) {
            if (d.isAfter(today)) continue
            if (!d.isBefore(ws)) w += c
            if (!d.isBefore(ms)) m += c
        }
        return Totals(daily[today] ?: 0L, w, m)
    }

    /** The last [count] buckets of [period] ending with the one that holds [today], oldest first, zeros included. */
    fun buckets(daily: Map<LocalDate, Long>, period: Period, today: LocalDate, count: Int): List<Bucket> {
        val starts = (count - 1 downTo 0).map {
            when (period) {
                Period.DAY -> today.minusDays(it.toLong())
                Period.WEEK -> weekStart(today).minusWeeks(it.toLong())
                Period.MONTH -> today.withDayOfMonth(1).minusMonths(it.toLong())
            }
        }
        return starts.map { s ->
            val e = when (period) { Period.DAY -> s; Period.WEEK -> s.plusDays(6); Period.MONTH -> s.plusMonths(1).minusDays(1) }
            Bucket(s, daily.filterKeys { !it.isBefore(s) && !it.isAfter(e) }.values.sum())
        }
    }
}

/** A position sample for the day cards. */
data class PlaceFix(val ts: Long, val lat: Double, val lon: Double, val predicted: Boolean = false, val accuracyM: Double = 0.0,
                    /** Where the fix came from: gps, net, cell, fused ("" = not recorded). */ val source: String = "")

enum class DayPart(val targetHour: Int, val fromHour: Int, val toHour: Int, val label: String) {
    MORNING(8, 5, 11, "Morning"),
    NOON(13, 11, 17, "Noon"),
    NIGHT(21, 18, 24, "Night"),
}

/**
 * Morning / noon / night location: for each part, the fix nearest to its target time (08:00, 13:00,
 * 21:00) inside its window (05-11, 11-17, 18-24). A measured fix beats a predicted one when it is
 * at most [PREFER_MEASURED_MS] further from the target.
 */
object DayPlacePicker {
    const val PREFER_MEASURED_MS = 30 * 60_000L

    fun pick(day: LocalDate, zone: ZoneId, fixes: List<PlaceFix>): Map<DayPart, PlaceFix?> =
        DayPart.values().associateWith { part ->
            val w0 = day.atStartOfDay(zone).plusHours(part.fromHour.toLong()).toInstant().toEpochMilli()
            val w1 = day.atStartOfDay(zone).plusHours(part.toHour.toLong()).toInstant().toEpochMilli()
            val target = day.atStartOfDay(zone).plusHours(part.targetHour.toLong()).toInstant().toEpochMilli()
            val inWin = fixes.filter { it.ts in w0 until w1 }
            val nearest = inWin.minByOrNull { abs(it.ts - target) }
            if (nearest != null && nearest.predicted) {
                val measured = inWin.filter { !it.predicted }.minByOrNull { abs(it.ts - target) }
                if (measured != null && abs(measured.ts - target) <= abs(nearest.ts - target) + PREFER_MEASURED_MS) measured else nearest
            } else nearest
        }
}

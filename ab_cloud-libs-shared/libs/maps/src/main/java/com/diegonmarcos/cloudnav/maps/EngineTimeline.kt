package com.diegonmarcos.cloudnav.maps

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * #1851 the one derivation behind every Explored and Tracker page: the append-only [Fix] log, the step slices and daily totals
 * (Health Connect, else the phone counter) and the Health Connect sleep sessions in; the stored segment rows ([TlSegment]) and the
 * per-day rollups out. Pure, so a JVM test pins it. [TimelineSync.run] persists the result; no page computes its own.
 */
object EngineTimeline {
    class Result(val segments: List<TlSegment>, val rollups: List<DayRollup>)

    /** A place name already known near a stay (the stop detector's reverse-geocoded rows), within [LABEL_RADIUS_M]. */
    private const val LABEL_RADIUS_M = 150.0

    fun build(fixes: List<Fix>, slices: List<TlSteps>, daily: Map<LocalDate, Long>, hcSleep: List<LongRange>, labels: List<TlStop>,
              nowMs: Long, zone: ZoneId, p: EngineParams = EngineParams()): Result {
        val raw = TimelineEngine.segment(fixes.filter { it.ts <= nowMs }, p, zone, hcSleep)
        // steps: cut at midnight, share out per day against the day's total, then fold the pieces back into their segment
        val perDay = TreeMap<LocalDate, MutableList<Pair<Int, Seg>>>()
        raw.forEachIndexed { i, s ->
            for (piece in TimelineEngine.cutAtMidnight(listOf(s), zone))
                perDay.getOrPut(Instant.ofEpochMilli(piece.startMs).atZone(zone).toLocalDate()) { ArrayList() }.add(i to piece)
        }
        val attributed = TimelineEngine.attributeSteps(perDay.mapValues { e -> e.value.map { it.second } }, slices, daily)
        val stepsOf = LongArray(raw.size)
        for ((d, parts) in attributed) parts.forEachIndexed { k, seg -> stepsOf[perDay.getValue(d)[k].first] += stepsIn(seg) }

        val out = ArrayList<TlSegment>()
        raw.forEachIndexed { i, s ->
            when (s) {
                is Seg.Stay -> out += staySegments(s.copy(steps = stepsOf[i]), labelFor(s, labels))
                is Seg.Move -> out += TlSegment(TlKind.MOVE, s.startMs, s.endMs, s.path.lastOrNull()?.lat, s.path.lastOrNull()?.lon, null,
                    stepsOf[i], s.distanceM, "fix")
                is Seg.Gap -> {}
            }
        }
        val sleep = TimelineEngine.sleepByEndDate(raw, zone)
        val dates = (attributed.keys + daily.keys + sleep.keys).toSortedSet()
        val rollups = dates.map { d ->
            val r = TimelineEngine.rollup(d, attributed[d].orEmpty(), sleep[d] ?: 0L)
            val total = daily[d]
            if (total != null && attributed[d].isNullOrEmpty()) r.copy(steps = total) else r
        }
        return Result(out, rollups)
    }

    private fun stepsIn(s: Seg): Long = (s as? Seg.Move)?.steps ?: (s as? Seg.Stay)?.steps ?: 0L

    private fun labelFor(s: Seg.Stay, labels: List<TlStop>): TlStop? =
        labels.filter { it.name != null || it.city != null }
            .map { it to TimelineBuilder.haversineM(s.lat, s.lon, it.lat, it.lon) }
            .filter { it.second <= LABEL_RADIUS_M }.minByOrNull { it.second }?.first

    /** One stay is up to three rows: the day part before the night, the SLEEP itself, and what follows it. A whole stay is never one sleep. */
    internal fun staySegments(s: Seg.Stay, label: TlStop?): List<TlSegment> {
        val ss = s.sleepStartMs; val se = s.sleepEndMs
        val parts = if (ss != null && se != null && se > ss) listOf(Triple(s.startMs, ss, TlKind.STOP), Triple(ss, se, TlKind.SLEEP), Triple(se, s.endMs, TlKind.STOP))
        else listOf(Triple(s.startMs, s.endMs, TlKind.STOP))
        val live = parts.filter { it.second > it.first }
        var left = s.steps
        return live.mapIndexed { k, (a, b, kind) ->
            val n = if (k == live.size - 1) left else Math.round(s.steps * (b - a).toDouble() / s.durationMs.coerceAtLeast(1)).coerceAtMost(left)
            left -= n
            TlSegment(kind, a, b, s.lat, s.lon, label?.name, n, 0.0, "fix", label?.neighborhood, label?.city, label?.country)
        }
    }
}

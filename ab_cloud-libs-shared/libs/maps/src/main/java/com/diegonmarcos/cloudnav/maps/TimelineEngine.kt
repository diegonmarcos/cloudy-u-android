package com.diegonmarcos.cloudnav.maps

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** #1851 one raw location fix as the shared store keeps it: every fix is appended, none is overwritten. */
data class Fix(val ts: Long, val lat: Double, val lon: Double, val accuracyM: Double? = null, val speedMps: Double? = null, val provider: String = "gps")

/** Thresholds of [TimelineEngine]; the defaults are the declared ones (stay radius 100 m, dwell 5 min, night 22:00-08:00). */
data class EngineParams(
    val stayRadiusM: Double = 100.0,
    val minStayMs: Long = 5 * 60_000L,
    val maxAccM: Double = 200.0,
    val maxRunGapMs: Long = 12 * 3_600_000L,
    val nightStartH: Int = 22,
    val nightEndH: Int = 8,
    val sleepMinMs: Long = 3 * 3_600_000L,
    val hcSleepMinMs: Long = 2 * 3_600_000L,
    val stillMps: Double = 1.0,
)

/** One piece of the timeline: a stay (visit), a move (activity) or a gap (no fix, unknown). */
sealed class Seg {
    abstract val startMs: Long
    abstract val endMs: Long
    val durationMs: Long get() = endMs - startMs

    /** [sleepStartMs]..[sleepEndMs] is the part of the stay that is sleep (null = none): night window and stillness, or a Health Connect session. */
    data class Stay(override val startMs: Long, override val endMs: Long, val lat: Double, val lon: Double, val fixCount: Int,
                    val sleepStartMs: Long? = null, val sleepEndMs: Long? = null, val steps: Long = 0) : Seg() {
        val sleepMs: Long get() = if (sleepStartMs != null && sleepEndMs != null) (sleepEndMs - sleepStartMs).coerceAtLeast(0) else 0L
    }

    data class Move(override val startMs: Long, override val endMs: Long, val distanceM: Double, val mode: String,
                    val steps: Long = 0, val path: List<Fix> = emptyList()) : Seg()

    data class Gap(override val startMs: Long, override val endMs: Long) : Seg()
}

data class DayRollup(val date: LocalDate, val steps: Long, val distanceM: Double, val movingMs: Long, val stoppedMs: Long, val visits: Int,
                     val sleepMs: Long, val distanceByMode: Map<String, Double>)

/**
 * #1851 THE segmentation engine: raw fixes in, stays and moves out. Pure (no Android), JVM-tested with synthetic fix streams.
 *
 * A stay is a run of fixes within [EngineParams.stayRadiusM] of the run's centre lasting at least [EngineParams.minStayMs]; what lies
 * between two stays is a move (distance = the fixes' path, mode from its average speed) or, past [EngineParams.maxRunGapMs] with no fix, a gap.
 * Sleep is never a property of a whole stay: it is the part of a still stay inside the night window, or the Health Connect sleep session.
 */
object TimelineEngine {
    private class Run(first: Fix) {
        val fx = arrayListOf(first)
        var lat = first.lat; var lon = first.lon
        val startMs get() = fx.first().ts
        val endMs get() = fx.last().ts
        fun add(f: Fix) { fx += f; val n = fx.size; lat += (f.lat - lat) / n; lon += (f.lon - lon) / n }
    }

    fun segment(fixes: List<Fix>, p: EngineParams = EngineParams(), zone: ZoneId = ZoneId.systemDefault(),
                hcSleep: List<LongRange> = emptyList()): List<Seg> {
        val good = fixes.filter { it.accuracyM == null || it.accuracyM <= p.maxAccM }.sortedBy { it.ts }.distinctBy { it.ts }
        if (good.isEmpty()) return emptyList()
        val runs = ArrayList<Run>()
        for (f in good) {
            val c = runs.lastOrNull()
            if (c != null && f.ts - c.endMs <= p.maxRunGapMs && TimelineBuilder.haversineM(c.lat, c.lon, f.lat, f.lon) <= p.stayRadiusM) c.add(f) else runs += Run(f)
        }
        val stays = runs.filter { it.endMs - it.startMs >= p.minStayMs }
        val out = ArrayList<Seg>()
        if (stays.isEmpty()) {
            if (good.size >= 2) out += legOf(good.first().ts, good.last().ts, good, p)
            return out
        }
        val firstA = good.first().ts
        if (firstA < stays.first().startMs) out += legOf(firstA, stays.first().startMs, good.filter { it.ts in firstA..stays.first().startMs }, p)
        var prev: Run? = null
        for (s in stays) {
            if (prev != null) {
                val a = prev.endMs; val b = s.startMs
                if (b > a) {
                    val inside = good.filter { it.ts in a..b }
                    out += if (b - a > p.maxRunGapMs && inside.size <= 2) Seg.Gap(a, b) else legOf(a, b, inside, p)
                }
            }
            out += stayOf(s, p, zone, hcSleep)
            prev = s
        }
        val lastRun = prev; val lastTs = good.last().ts
        if (lastRun != null && lastTs > lastRun.endMs) out += legOf(lastRun.endMs, lastTs, good.filter { it.ts in lastRun.endMs..lastTs }, p)
        return out
    }

    private fun legOf(a: Long, b: Long, pts: List<Fix>, p: EngineParams): Seg.Move {
        var d = 0.0
        for (i in 1 until pts.size) d += TimelineBuilder.haversineM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        val mps = if (b > a) d / ((b - a) / 1000.0) else 0.0
        return Seg.Move(a, b, d, modeOf(mps), 0L, pts)
    }

    /** Mode from the average speed (m/s): walk, bike, car (also tram and bus), train, plane. */
    fun modeOf(mps: Double): String = when {
        mps <= 2.2 -> "WALK"
        mps <= 7.0 -> "BIKE"
        mps <= 30.0 -> "CAR"
        mps <= 100.0 -> "TRAIN"
        else -> "PLANE"
    }

    private fun stayOf(r: Run, p: EngineParams, zone: ZoneId, hc: List<LongRange>): Seg.Stay {
        val sleep = sleepInterval(r.startMs, r.endMs, r.fx, p, zone, hc)
        return Seg.Stay(r.startMs, r.endMs, r.lat, r.lon, r.fx.size, sleep?.first, sleep?.second)
    }

    /** The sound sleep rule: still (at most one fix in ten faster than [EngineParams.stillMps]) and a Health Connect session, else the night window. */
    fun sleepInterval(a: Long, b: Long, fx: List<Fix>, p: EngineParams, zone: ZoneId, hc: List<LongRange>): Pair<Long, Long>? {
        if (fx.count { (it.speedMps ?: 0.0) > p.stillMps } * 10 > fx.size) return null   // a few fast edge fixes (leaving, arriving) do not make a night restless
        val hit = hc.filter { it.first < b && it.last > a }
        if (hit.isNotEmpty()) {
            val s = maxOf(a, hit.minOf { it.first }); val e = minOf(b, hit.maxOf { it.last })
            return if (e - s >= p.hcSleepMinMs) s to e else null
        }
        var day = Instant.ofEpochMilli(a).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        var best: Pair<Long, Long>? = null
        while (!day.isAfter(last)) {
            val ns = day.atTime(p.nightStartH, 0).atZone(zone).toInstant().toEpochMilli()
            val ne = day.plusDays(1).atTime(p.nightEndH, 0).atZone(zone).toInstant().toEpochMilli()
            val s = maxOf(a, ns); val e = minOf(b, ne)
            if (e - s >= p.sleepMinMs && (best == null || e - s > best.second - best.first)) best = s to e
            day = day.plusDays(1)
        }
        return best
    }

    // ── per-day separation ──────────────────────────────────────────

    /** Cuts every segment at each local midnight, so no piece spans two dates; a move's distance and steps follow the time. */
    fun cutAtMidnight(segs: List<Seg>, zone: ZoneId): List<Seg> = segs.flatMap { s ->
        val cuts = ArrayList<Long>()
        var d = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate().plusDays(1)
        while (true) {
            val m = d.atStartOfDay(zone).toInstant().toEpochMilli()
            if (m >= s.endMs) break
            cuts += m; d = d.plusDays(1)
        }
        if (cuts.isEmpty()) return@flatMap listOf(s)
        val edges = listOf(s.startMs) + cuts + listOf(s.endMs)
        var stepsLeft = (s as? Seg.Move)?.steps ?: (s as? Seg.Stay)?.steps ?: 0L
        edges.zipWithNext().mapIndexed { i, (x, y) ->
            val f = (y - x).toDouble() / s.durationMs
            val last = i == edges.size - 2
            val n = if (last) stepsLeft else Math.round(((s as? Seg.Move)?.steps ?: (s as? Seg.Stay)?.steps ?: 0L) * f).coerceAtMost(stepsLeft)
            stepsLeft -= n
            when (s) {
                is Seg.Stay -> {
                    val ss = s.sleepStartMs; val se = s.sleepEndMs
                    val cs = if (ss != null && se != null) maxOf(ss, x) else 0L
                    val ce = if (ss != null && se != null) minOf(se, y) else 0L
                    val ok = ss != null && se != null && ce > cs
                    s.copy(startMs = x, endMs = y, steps = n, sleepStartMs = if (ok) cs else null, sleepEndMs = if (ok) ce else null)
                }
                is Seg.Move -> s.copy(startMs = x, endMs = y, distanceM = s.distanceM * f, steps = n, path = s.path.filter { it.ts in x..y })
                is Seg.Gap -> Seg.Gap(x, y)
            }
        }
    }

    fun byDay(segs: List<Seg>, zone: ZoneId): TreeMap<LocalDate, List<Seg>> {
        val m = TreeMap<LocalDate, MutableList<Seg>>()
        for (s in cutAtMidnight(segs, zone)) m.getOrPut(Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()) { ArrayList() } += s
        return TreeMap<LocalDate, List<Seg>>(m)
    }

    // ── steps ───────────────────────────────────────────────────────

    private fun stepsInD(steps: List<TlSteps>, a: Long, b: Long): Double {
        var n = 0.0
        for (s in steps) {
            val o = minOf(s.endMs, b) - maxOf(s.startMs, a)
            if (o > 0) n += s.count * (o.toDouble() / (s.endMs - s.startMs).coerceAtLeast(1))
        }
        return n
    }

    private fun withSteps(s: Seg, n: Long): Seg = when (s) { is Seg.Stay -> s.copy(steps = n); is Seg.Move -> s.copy(steps = n); is Seg.Gap -> s }

    /**
     * Steps per stay and move. A day's [slices] (Health Connect records, else the phone counter) are shared out by overlap; when
     * [totals] holds the day's own total (Health Connect's de-duplicated aggregate) the pieces are scaled so they add up to exactly that
     * number (largest remainder). A day with a total but no overlapping slice spreads it by duration.
     */
    fun attributeSteps(days: Map<LocalDate, List<Seg>>, slices: List<TlSteps>, totals: Map<LocalDate, Long> = emptyMap()): TreeMap<LocalDate, List<Seg>> {
        val out = TreeMap<LocalDate, List<Seg>>()
        for ((d, segs) in days) {
            val idx = segs.indices.filter { segs[it] !is Seg.Gap }
            val base = idx.map { stepsInD(slices, segs[it].startMs, segs[it].endMs) }
            val total = totals[d]
            val alloc: List<Long> = if (total == null) base.map { Math.round(it) } else {
                val w = if (base.sum() > 0.0) base else idx.map { segs[it].durationMs.toDouble() }
                val ws = w.sum()
                if (ws <= 0.0) base.map { Math.round(it) } else {
                    val exact = w.map { total * it / ws }
                    val fl = exact.map { Math.floor(it).toLong() }.toMutableList()
                    var rest = total - fl.sum()
                    for (i in exact.indices.sortedByDescending { exact[it] - fl[it] }) { if (rest <= 0) break; fl[i] += 1; rest-- }
                    fl
                }
            }
            val res = segs.toMutableList()
            idx.forEachIndexed { k, i -> res[i] = withSteps(segs[i], alloc[k]) }
            out[d] = res
        }
        return out
    }

    // ── rollups ─────────────────────────────────────────────────────

    /** Sleep belongs to the date the sleep ENDS on (the 22:00-07:00 night is the morning's sleep); computed on uncut segments. */
    fun sleepByEndDate(segs: List<Seg>, zone: ZoneId): Map<LocalDate, Long> {
        val m = TreeMap<LocalDate, Long>()
        for (s in segs) if (s is Seg.Stay && s.sleepEndMs != null && s.sleepMs > 0) {
            val d = Instant.ofEpochMilli(s.sleepEndMs).atZone(zone).toLocalDate()
            m[d] = (m[d] ?: 0L) + s.sleepMs
        }
        return m
    }

    fun rollup(date: LocalDate, parts: List<Seg>, sleepMs: Long = 0L): DayRollup {
        val moves = parts.filterIsInstance<Seg.Move>(); val stays = parts.filterIsInstance<Seg.Stay>()
        return DayRollup(date, parts.sumOf { (it as? Seg.Move)?.steps ?: (it as? Seg.Stay)?.steps ?: 0L }, moves.sumOf { it.distanceM },
            moves.sumOf { it.durationMs }, stays.sumOf { it.durationMs }, stays.size, sleepMs, moves.groupBy { it.mode }.mapValues { e -> e.value.sumOf { it.distanceM } })
    }
}

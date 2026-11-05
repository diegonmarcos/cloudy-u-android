package com.diegonmarcos.superapp.battery

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * #1504 The 90-day battery archive, pure. The rolling sample history keeps 14 days; the closed discharge cycles and charge
 * sessions it yields are appended to the `sess` table ONLY when the plug state changes (BatteryRepository.fold), and pruned
 * past [RETENTION_MS]. Per-app daily energy is derived from the stored cycles (BatteryCycles), so it adds no storage. The
 * trend ([trend]) is the average %/h per day, with the days that stand out from the baseline flagged.
 */
object BatteryArchive {

    const val RETENTION_MS = 90L * 24 * 3_600_000L
    /** A day is a spike when its rate is this many times the median day... */
    const val SPIKE_FACTOR = 1.5
    /** ...and it drained at least this much (a 1 % blip is not a trend). */
    const val SPIKE_MIN_PCT = 3

    data class DayTrend(val day: String, val avgPctPerHour: Double, val drainedPct: Int, val hours: Double, val spike: Boolean)

    /** Archived and live sessions as one list, oldest first; a live session replaces the archived one with the same key. */
    fun merge(archived: List<BatterySession>, live: List<BatterySession>, now: Long): List<BatterySession> {
        val by = LinkedHashMap<String, BatterySession>()
        for (s in archived + live) by["${s.charging}:${s.startTs}"] = s
        return by.values.filter { it.endTs >= now - RETENTION_MS }.sortedBy { it.startTs }
    }

    fun dayKey(ts: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = ts }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Average %/h per day (the day a discharge started), oldest first, spikes flagged against the median day. */
    fun trend(sessions: List<BatterySession>, zone: TimeZone = TimeZone.getDefault()): List<DayTrend> {
        val days = sessions.filter { !it.charging && it.durationMs >= BatteryHistory.MIN_SESSION_MS && it.deltaPct < 0 }
            .groupBy { dayKey(it.startTs, zone) }.toSortedMap()
        val raw = days.map { (d, l) ->
            val pct = l.sumOf { -it.deltaPct }
            val hours = l.sumOf { it.durationMs } / 3_600_000.0
            Triple(d, pct, hours)
        }.filter { it.third > 0.0 }
        val rates = raw.map { it.second / it.third }.sorted()
        val median = if (rates.isEmpty()) 0.0 else rates[rates.size / 2]
        return raw.map { (d, pct, h) ->
            val r = pct / h
            DayTrend(d, r, pct, h, pct >= SPIKE_MIN_PCT && median > 0.0 && r > median * SPIKE_FACTOR)
        }
    }

    /** Per-app mAh per calendar day (the day a cycle started), oldest day first; each day's apps heaviest first. */
    fun dailyApps(cycles: List<BatteryCycles.Cycle>, zone: TimeZone = TimeZone.getDefault()): Map<String, List<Pair<Int, Double>>> =
        cycles.groupBy { dayKey(it.startTs, zone) }.toSortedMap().mapValues { (_, l) ->
            l.fold(emptyMap<Int, Double>()) { acc, c -> BatteryCycles.add(acc, c.apps) }.entries
                .sortedByDescending { it.value }.map { it.key to it.value }
        }

    // ── storage codec: one pipe-separated line per session ───────────────

    fun encode(s: BatterySession): String = listOf(
        if (s.charging) 1 else 0, s.startTs, s.endTs, s.startPct, s.endPct, s.plugged,
        s.avgW?.let { String.format(Locale.US, "%.2f", it) } ?: "-", s.maxTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
    ).joinToString("|")

    fun decode(v: String): BatterySession? {
        val f = v.split('|'); if (f.size != 8) return null
        return BatterySession(
            charging = f[0] == "1", startTs = f[1].toLongOrNull() ?: return null, endTs = f[2].toLongOrNull() ?: return null,
            startPct = f[3].toIntOrNull() ?: return null, endPct = f[4].toIntOrNull() ?: return null,
            plugged = f[5].toIntOrNull() ?: return null, avgW = f[6].toDoubleOrNull(), maxTempC = f[7].toDoubleOrNull(), ongoing = false,
        )
    }
}

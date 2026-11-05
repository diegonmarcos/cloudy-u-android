package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.battery.BatteryArchive
import com.diegonmarcos.superapp.battery.BatteryCycles
import com.diegonmarcos.superapp.battery.BatterySession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** #1504 The 90-day battery history as report sections (source: app, read from the SoT db): the %/h-per-day trend with spikes, every session, per-app daily energy, screen-off drain. */
object XrayBattery {

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun whenOf(ts: Long, zone: TimeZone) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }.format(Date(ts))

    fun sections(
        sessions: List<BatterySession>, cycles: List<BatteryCycles.Cycle>, label: (Int) -> String, zone: TimeZone = TimeZone.getDefault(),
    ): List<XSection> {
        val g = XrayProbes.G_BATTERY
        val trend = BatteryArchive.trend(sessions, zone)
        val t = StringBuilder("day | avg %/h | used % | hours on battery | flag\n")
        for (d in trend) t.append("${d.day} | ${f1(d.avgPctPerHour)} | ${d.drainedPct} | ${f1(d.hours)} | ${if (d.spike) "SPIKE" else ""}\n")
        if (trend.isEmpty()) t.append("no discharge history recorded yet\n")
        else {
            val rates = trend.map { it.avgPctPerHour }.sorted()
            t.append("median ${f1(rates[rates.size / 2])} %/h over ${trend.size} days; spike = above ${BatteryArchive.SPIKE_FACTOR}x the median with at least ${BatteryArchive.SPIKE_MIN_PCT} % used\n")
        }
        val s = StringBuilder("when | kind | from%→to% | minutes | avg %/h | avg W | max °C\n")
        for (x in sessions.asReversed()) s.append("${whenOf(x.startTs, zone)} | ${if (x.charging) "charge" else "discharge"} | ${x.startPct}→${x.endPct} | ${x.durationMs / 60_000} | " +
            "${x.pctPerHour?.let { f1(it) } ?: "-"} | ${x.avgW?.let { f1(it) } ?: "-"} | ${x.maxTempC?.let { f1(it) } ?: "-"}\n")
        val a = StringBuilder()
        val daily = BatteryArchive.dailyApps(cycles, zone)
        for ((day, apps) in daily) a.append(day).append(": ").append(apps.take(5).joinToString(", ") { "${label(it.first)} ${f1(it.second)} mAh" }.ifEmpty { "no per-app reading" }).append("\n")
        if (daily.isEmpty()) a.append("no per-app daily energy recorded yet\n")
        val off = sessions.filter { !it.charging && it.screenOffMs > 0 }
        val offPct = off.sumOf { it.screenOffPct }
        val offH = off.sumOf { it.screenOffMs } / 3_600_000.0
        val on = sessions.filter { !it.charging && it.screenOnMs > 0 }
        val onPct = on.sumOf { it.screenOnPct }
        val onH = on.sumOf { it.screenOnMs } / 3_600_000.0
        val scr = "screen-off drain: ${if (offH > 0) f1(offPct / offH) else "-"} %/h over ${f1(offH)} h; screen-on drain: ${if (onH > 0) f1(onPct / onH) else "-"} %/h over ${f1(onH)} h (recent history only)\n"
        return listOf(
            XSection(g, "Battery history · average %/h per day (spikes flagged)", SRC_APP, t.toString()),
            XSection(g, "Battery history · discharge cycles and charge sessions (90 days)", SRC_APP, s.toString()),
            XSection(g, "Battery history · per-app daily energy (90 days)", SRC_APP, a.toString()),
            XSection(g, "Screen-off drain rate", SRC_APP, scr),
        )
    }
}

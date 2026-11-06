package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.launcher.NetworkTableDecl
import com.diegonmarcos.superapp.launcher.NetworkColumn

/**
 * #1523 The network dropdown's table, pure JVM. The rows are DECLARED (build.json::home_status_bar.network_table); a reader
 * (NetTableReader, SpeedMeter, SpeedTest) supplies each row's cells by id and [NetTable.build] lays out exactly the declared
 * rows, so a row whose source gave nothing still shows, as "n/a (reason)", never as a hole.
 */
internal sealed class NetValue {
    /** A reading, already formatted. */
    data class V(val text: String) : NetValue()
    /** No reading, and why. [fix] = the reason is a missing permission the Perms page can grant. */
    data class NA(val reason: String, val fix: Boolean = false) : NetValue()

    /** The cell's text: the value, "n/a (reason)", or "reason — Fix in Perms ›" for a grantable permission. */
    fun text(): String = when (this) {
        is V -> text
        is NA -> if (fix) "$reason — Fix in Perms ›" else "n/a ($reason)"
    }

    /** A grid cell: the value, or a bare "n/a" (the reason is listed once under the group). */
    fun cell(): String = when (this) {
        is V -> text
        is NA -> "n/a"
    }
}

internal data class NetRow(val id: String, val label: String, val cells: List<NetValue>)
internal data class NetGroup(val id: String, val title: String, val columns: List<NetworkColumn>, val rows: List<NetRow>) {
    /** The distinct n/a reasons of a grid group, shown under it ("n/a (reason)"). */
    fun notes(): List<String> = rows.flatMap { it.cells }.filterIsInstance<NetValue.NA>().map { it.text() }.distinct()
}

internal object NetTable {
    /** The reason of a row the reader did not supply. */
    const val NOT_READ = "no source read this row"

    /** Every declared row, in declared order, with its cells padded to the group's column count (1 for a key-value group). */
    fun build(decl: NetworkTableDecl, values: Map<String, List<NetValue>>): List<NetGroup> = decl.groups.map { g ->
        val n = maxOf(1, g.columns.size)
        NetGroup(g.id, g.label, g.columns, g.rows.map { r ->
            val got = values[r.id].orEmpty()
            NetRow(r.id, r.label, (0 until n).map { got.getOrNull(it) ?: if (got.size == 1 && n > 1) got[0] else NetValue.NA(NOT_READ) })
        })
    }

    /** Declared row ids, in order. */
    fun ids(decl: NetworkTableDecl): List<String> = decl.groups.flatMap { g -> g.rows.map { it.id } }

    // ── bandwidth units: decimal SI, 1 MB/s = 8 Mbps ────────────────────────────────────────────

    /** "MB/s" and "Mbps" cells of a rate given in Mbps; both n/a with the same reason when unknown. */
    fun rate(mbps: Double?, why: String): List<NetValue> =
        if (mbps == null || mbps.isNaN()) listOf(NetValue.NA(why), NetValue.NA(why))
        else listOf(NetValue.V(SpeedMath.fmt(SpeedMath.mbPerSec(mbps))), NetValue.V(SpeedMath.fmt(mbps)))

    /** "12.3 MB/s  98.4 Mbps" for a one-cell row. */
    fun rateLine(mbps: Double): String = "${SpeedMath.fmt(SpeedMath.mbPerSec(mbps))} MB/s  ${SpeedMath.fmt(mbps)} Mbps"

    /** Decimal bytes: "512 kB", "12.3 MB", "1.23 GB"; the 24 h total reads in MB or GB. */
    fun bytes(b: Long): String = when {
        b >= 1_000_000_000L -> "%.2f GB".format(java.util.Locale.US, b / 1e9)
        b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1e6)
        else -> "%.0f kB".format(java.util.Locale.US, b / 1e3)
    }

    /** The true 24 h average in Mbps: [bytes] over the covered span; null with no covered time. */
    fun avgMbps(bytes: Long?, coverageMs: Long): Double? = if (bytes == null || coverageMs <= 0L) null else SpeedMath.mbps(bytes, coverageMs)

    /** "12s ago", "5m ago", "3h ago". */
    fun ago(ms: Long): String = when {
        ms < 60_000L -> "${(ms / 1000).coerceAtLeast(0)}s ago"
        ms < 3_600_000L -> "${ms / 60_000L}m ago"
        else -> "${ms / 3_600_000L}h ago"
    }

    private fun bytesCells(a: Long?, b: Long?, c: Long?, d: Long?, why: String): List<NetValue> =
        listOf(a, b, c, d).map { if (it == null) NetValue.NA(why) else NetValue.V(bytes(it)) }

    /** The Data used grid: Wi-Fi / Mobile / Total, today and 24 h, down and up. */
    fun usageRows(today: NetUsageSpan?, h24: NetUsageSpan?, why: String): Map<String, List<NetValue>> = mapOf(
        "use_wifi" to bytesCells(today?.wifiRx, today?.wifiTx, h24?.wifiRx, h24?.wifiTx, why),
        "use_mobile" to bytesCells(today?.mobRx, today?.mobTx, h24?.mobRx, h24?.mobTx, why),
        "use_total" to bytesCells(today?.totRx, today?.totTx, h24?.totRx, h24?.totTx, why),
    )

    /**
     * The Bandwidth and peak grids. Now = the live 1 s delta; 24 h avg = the 24 h bytes over the time they cover (Mbps);
     * peak = the best 1 s rate seen while the dropdown was open; total = the 24 h bytes.
     */
    fun speedRows(nowDown: Double?, nowUp: Double?, h24: NetUsageSpan?, peakDown: Double?, peakUp: Double?): Map<String, List<NetValue>> {
        val noAvg = "needs two opens in 24 h"
        val cov = h24?.coverageMs ?: 0L
        return mapOf(
            "down" to rate(nowDown, "sampling") + rate(avgMbps(h24?.totRx, cov), noAvg),
            "up" to rate(nowUp, "sampling") + rate(avgMbps(h24?.totTx, cov), noAvg),
            "peak_down" to rate(peakDown, "no traffic seen while open") + listOf(h24?.let { NetValue.V(bytes(it.totRx)) } ?: NetValue.NA(noAvg)),
            "peak_up" to rate(peakUp, "no traffic seen while open") + listOf(h24?.let { NetValue.V(bytes(it.totTx)) } ?: NetValue.NA(noAvg)),
        )
    }

    /** The Speed test rows: the last run, then best / worst / average of the last 24 h in both units. */
    fun testRows(runs: List<SpeedRun>, nowMs: Long, running: Boolean, phase: String, failure: String?): Map<String, List<NetValue>> {
        val last = runs.lastOrNull()
        val none: NetValue = when {
            running -> NetValue.NA("testing: $phase")
            failure != null -> NetValue.NA("test failed: $failure")
            else -> NetValue.NA("tap Speed test")
        }
        fun mbps(v: Double?, what: String): NetValue = if (last == null) none else if (v == null) NetValue.NA("$what not measured") else NetValue.V(rateLine(v))
        fun ms(v: Double?, what: String): NetValue = if (last == null) none else if (v == null) NetValue.NA("$what not measured") else NetValue.V("%.1f ms".format(java.util.Locale.US, v))
        val dn = SpeedHist.down(runs); val up = SpeedHist.up(runs)
        val noRun: NetValue = NetValue.NA("no test in 24 h")
        fun hist(r: SpeedRange?, conv: (Double) -> Double): List<NetValue> =
            if (r == null) listOf(noRun) else listOf(r.best, r.worst, r.avg).map { NetValue.V(SpeedMath.fmt(conv(it))) }
        return mapOf(
            "st_down" to listOf(mbps(last?.downMbps, "download")),
            "st_up" to listOf(mbps(last?.upMbps, "upload")),
            "st_latency" to listOf(ms(last?.latencyMs, "latency")),
            "st_jitter" to listOf(ms(last?.jitterMs, "jitter")),
            "st_last" to listOf(if (last == null) none else NetValue.V("${ago(nowMs - last.atMs)} \u00b7 ${last.via} \u00b7 ${runs.size} in 24 h")),
            "hd_mbps" to hist(dn) { it }, "hd_mbs" to hist(dn) { SpeedMath.mbPerSec(it) },
            "hu_mbps" to hist(up) { it }, "hu_mbs" to hist(up) { SpeedMath.mbPerSec(it) },
        )
    }

    // ── Wi-Fi ────────────────────────────────────────────────────────────────────────────────

    fun band(freqMhz: Int): String? = when (freqMhz) {
        in 2412..2484 -> "2.4 GHz"
        in 5160..5885 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> null
    }

    /** 802.11 channel number of a centre frequency; null out of band. */
    fun channel(freqMhz: Int): Int? = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 5160..5885 -> (freqMhz - 5000) / 5
        in 5925..7125 -> (freqMhz - 5950) / 5
        else -> null
    }

    /** WifiInfo.getWifiStandard() (API 30) to the marketing name; null for 0 (unknown). */
    fun wifiStandard(code: Int): String? = when (code) {
        1 -> "legacy (802.11a/b/g)"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "802.11ad"
        7 -> "Wi-Fi 6 (802.11ax)"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }

    // ── mobile ───────────────────────────────────────────────────────────────────────────────

    /** Arbitrary strength unit: 5G NR dBm = ASU - 140; GSM / LTE / WCDMA dBm = 2 x ASU - 113. */
    fun asu(dbm: Int, nr: Boolean): Int = if (nr) dbm + 140 else (dbm + 113) / 2

    fun yesNo(b: Boolean): String = if (b) "yes" else "no"
}

/** One stored counter snapshot (TrafficStats): total and mobile bytes since boot. */
internal data class NetSnap(val atMs: Long, val totRx: Long, val totTx: Long, val mobRx: Long, val mobTx: Long)

/** Bytes moved in a span, per transport (Wi-Fi = total minus mobile), and the time the span really covers. */
internal data class NetUsageSpan(val wifiRx: Long, val wifiTx: Long, val mobRx: Long, val mobTx: Long, val coverageMs: Long) {
    val totRx: Long get() = wifiRx + mobRx
    val totTx: Long get() = wifiTx + mobTx
}

/**
 * The 24 h and today figures: counter snapshots recorded when the dropdown opens (and nothing else, no polling), summed
 * pairwise so a reboot (a counter that went DOWN) counts the new counter as that span's bytes.
 */
internal object NetUsage {
    const val KEEP_MS = 25L * 60 * 60 * 1000
    const val MAX_SNAPS = 300
    /** An open within this of the last snapshot replaces it instead of adding one. */
    const val MERGE_MS = 30_000L

    fun record(snaps: List<NetSnap>, s: NetSnap): List<NetSnap> {
        val kept = snaps.filter { it.atMs >= s.atMs - KEEP_MS && it.atMs <= s.atMs }.sortedBy { it.atMs }
        val base = if (kept.isNotEmpty() && s.atMs - kept.last().atMs < MERGE_MS) kept.dropLast(1) else kept
        return (base + s).takeLast(MAX_SNAPS)
    }

    private fun d(a: Long, b: Long) = if (b >= a) b - a else b

    private fun lerp(a: NetSnap, b: NetSnap, at: Long): NetSnap? {
        if (b.totRx < a.totRx || b.totTx < a.totTx || b.mobRx < a.mobRx || b.mobTx < a.mobTx || b.atMs <= a.atMs) return null
        val f = (at - a.atMs).toDouble() / (b.atMs - a.atMs)
        fun m(x: Long, y: Long) = x + ((y - x) * f).toLong()
        return NetSnap(at, m(a.totRx, b.totRx), m(a.totTx, b.totTx), m(a.mobRx, b.mobRx), m(a.mobTx, b.mobTx))
    }

    /** Usage between [fromMs] and [toMs]; null with fewer than two snapshots. coverageMs < the span when history starts later; the cut is interpolated between the two snapshots around it. */
    fun span(snaps: List<NetSnap>, fromMs: Long, toMs: Long): NetUsageSpan? {
        val all = snaps.filter { it.atMs <= toMs }.sortedBy { it.atMs }
        if (all.size < 2) return null
        val i = all.indexOfLast { it.atMs <= fromMs }
        val p = when {
            i < 0 -> all
            all[i].atMs == fromMs || i == all.lastIndex -> all.subList(i, all.size)
            // the cut falls between two snapshots: start from the counters interpolated at the cut (unless a reboot sits between them)
            else -> lerp(all[i], all[i + 1], fromMs)?.let { listOf(it) + all.subList(i + 1, all.size) } ?: all.subList(i + 1, all.size)
        }
        if (p.size < 2) return null
        var tr = 0L; var tt = 0L; var mr = 0L; var mt = 0L
        for (k in 1 until p.size) {
            tr += d(p[k - 1].totRx, p[k].totRx); tt += d(p[k - 1].totTx, p[k].totTx)
            mr += d(p[k - 1].mobRx, p[k].mobRx); mt += d(p[k - 1].mobTx, p[k].mobTx)
        }
        return NetUsageSpan((tr - mr).coerceAtLeast(0L), (tt - mt).coerceAtLeast(0L), mr, mt, p.last().atMs - p.first().atMs)
    }

    fun encode(s: List<NetSnap>): String = s.joinToString("\n") { "${it.atMs},${it.totRx},${it.totTx},${it.mobRx},${it.mobTx}" }

    fun decode(text: String): List<NetSnap> = text.lineSequence().mapNotNull { l ->
        val p = l.split(',').map { it.toLongOrNull() }
        if (p.size != 5 || p.any { it == null }) null else NetSnap(p[0]!!, p[1]!!, p[2]!!, p[3]!!, p[4]!!)
    }.toList()
}

/** One finished speed test. Rates in Mbps; latency and jitter in ms; a side that was not measured is null. */
internal data class SpeedRun(val atMs: Long, val downMbps: Double?, val upMbps: Double?, val latencyMs: Double?, val jitterMs: Double?, val via: String)

internal data class SpeedRange(val best: Double, val worst: Double, val avg: Double)

/** The speed-test history: 24 h of runs, best / worst / average per direction. */
internal object SpeedHist {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    const val MAX_RUNS = 100

    fun prune(runs: List<SpeedRun>, nowMs: Long): List<SpeedRun> =
        runs.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs }.sortedBy { it.atMs }.takeLast(MAX_RUNS)

    fun range(values: List<Double>): SpeedRange? = if (values.isEmpty()) null else SpeedRange(values.max(), values.min(), values.average())

    fun down(runs: List<SpeedRun>) = range(runs.mapNotNull { it.downMbps })
    fun up(runs: List<SpeedRun>) = range(runs.mapNotNull { it.upMbps })

    private fun n(v: Double?) = v?.toString().orEmpty()

    fun encode(r: List<SpeedRun>): String = r.joinToString("\n") { "${it.atMs},${n(it.downMbps)},${n(it.upMbps)},${n(it.latencyMs)},${n(it.jitterMs)},${it.via}" }

    fun decode(text: String): List<SpeedRun> = text.lineSequence().mapNotNull { l ->
        val p = l.split(',')
        val at = p.getOrNull(0)?.toLongOrNull()
        if (p.size != 6 || at == null) null
        else SpeedRun(at, p[1].toDoubleOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(), p[5])
    }.toList()
}

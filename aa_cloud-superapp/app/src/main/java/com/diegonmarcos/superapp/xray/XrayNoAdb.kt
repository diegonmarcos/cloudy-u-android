package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.system.DdLine
import java.util.Locale

/**
 * #1985 The no-adb X-Ray: every tracker T01..T12 is measured with what the app itself can read (see [XrayAppMeasure]). Each field is
 * one of three kinds, never blank:
 *  - direct   : a value the app reads from the platform (`key: v (direct: source)`)
 *  - fallback : the closest app-side measurement of an adb-only field (`key: v (fallback: source; limits; adb: command)`)
 *  - adb-only : truly no app-side signal, with a one-line reason (`key: adb-only: reason (adb: command)`)
 * The status of a tracker is computed from the numeric `#m key=value` lines against [THRESHOLDS] (declared, and printed in the
 * section as `thresholds:`). Per-package findings come as `#s pkg|cause|detail|points` lines and feed the suspects and the VERDICT.
 * Pure: no Android type, so the unit test builds sections from fields and checks every rule.
 */
data class AField(val key: String, val value: String, val kind: String, val src: String = "", val limits: String = "", val adb: String = "", val num: Double? = null) {
    companion object { const val DIRECT = "direct"; const val FALLBACK = "fallback"; const val ADB_ONLY = "adb-only" }
}

/** A declared status rule: measure [key] of tracker [tracker] is warn at [warn] and bad at [bad] ([high] = larger is worse, else smaller is worse). */
data class Threshold(val tracker: Int, val key: String, val warn: Double, val bad: Double, val high: Boolean = true, val unit: String = "")

/** One per-package finding that feeds the suspects. */
data class AFinding(val pkg: String, val cause: String, val detail: String, val points: Double)

object XrayNoAdb {
    const val ADB_ONLY_PREFIX = "adb-only: "

    fun id(tracker: Int) = "app.t%02d".format(Locale.US, tracker)

    val THRESHOLDS: List<Threshold> = listOf(
        Threshold(1, "own_cpu_pct", 25.0, 60.0, unit = "%"), Threshold(1, "load_per_core", 1.5, 3.0),
        Threshold(2, "jank_pct", 5.0, 15.0, unit = "%"), Threshold(2, "frame_p90_ms", 16.0, 32.0, unit = "ms"),
        Threshold(3, "data_used_pct", 80.0, 92.0, unit = "%"),
        Threshold(4, "battery_pct", 20.0, 10.0, high = false, unit = "%"), Threshold(4, "battery_temp_c", 40.0, 45.0, unit = "C"),
        Threshold(4, "drain_ma", 500.0, 1000.0, unit = "mA"), Threshold(4, "battery_health_bad", 1.0, 1.0),
        Threshold(5, "thermal_status", 1.0, 3.0), Threshold(5, "thermal_headroom", 0.8, 1.0),
        Threshold(6, "mem_avail_pct", 15.0, 8.0, high = false, unit = "%"), Threshold(6, "low_memory", 1.0, 1.0),
        Threshold(6, "own_pss_mb", 400.0, 800.0, unit = "MB"), Threshold(6, "heap_used_pct", 80.0, 92.0, unit = "%"),
        Threshold(7, "own_active_notifications", 20.0, 50.0), Threshold(7, "binder_sent_tx", 200000.0, 1000000.0),
        Threshold(8, "anr_30d", 1.0, 3.0), Threshold(8, "main_latency_ms", 100.0, 700.0, unit = "ms"),
        Threshold(9, "crash_30d", 1.0, 3.0), Threshold(9, "log_errors", 50.0, 200.0),
        Threshold(10, "net_unvalidated", 1.0, 1.0), Threshold(10, "dns_ms", 300.0, 1000.0, unit = "ms"),
        Threshold(10, "tcp_ms", 500.0, 1500.0, unit = "ms"), Threshold(10, "tls_ms", 800.0, 2500.0, unit = "ms"),
        Threshold(11, "threads", 150.0, 400.0), Threshold(11, "open_fds", 500.0, 900.0), Threshold(11, "restarts_30d", 5.0, 15.0),
        Threshold(12, "sensitive_granted", 5.0, 9.0), Threshold(12, "fleet_low_target", 1.0, 3.0),
    )

    private fun f1(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

    /** The one text line of a field. */
    fun line(f: AField): String = when (f.kind) {
        AField.DIRECT -> "${f.key}: ${f.value} (direct: ${f.src}${if (f.limits.isNotEmpty()) "; ${f.limits}" else ""})"
        AField.FALLBACK -> "${f.key}: ${f.value} (fallback: ${f.src}; ${f.limits.ifEmpty { "app view" }}; adb: ${f.adb})"
        else -> "${f.key}: $ADB_ONLY_PREFIX${f.value}${if (f.adb.isNotEmpty()) " (adb: ${f.adb})" else ""}"
    }

    /** True when [l] (a field line) carries a direct value, a fallback value with its source, or an adb-only with a reason. */
    fun validLine(l: String): Boolean {
        val v = l.substringAfter(": ", "")
        if (l.substringBefore(": ", "").isEmpty() || v.isBlank()) return false
        if (v.startsWith(ADB_ONLY_PREFIX)) return v.removePrefix(ADB_ONLY_PREFIX).substringBefore(" (adb:").isNotBlank()
        return Regex("""\((direct|fallback): .+\)$""").containsMatchIn(v)
    }

    /** The section of tracker [n]: field lines, the numeric measures, the declared thresholds and the per-package findings. */
    fun section(n: Int, group: String, fields: List<AField>, findings: List<AFinding> = emptyList()): XSection {
        val sb = StringBuilder()
        for (f in fields) sb.append(line(f)).append('\n')
        for (f in fields) f.num?.let { sb.append("#m ${f.key}=${f1(it)}\n") }
        val th = THRESHOLDS.filter { it.tracker == n }
        if (th.isNotEmpty()) sb.append("thresholds: ").append(th.joinToString("; ") { "${it.key} warn${if (it.high) ">=" else "<="}${f1(it.warn)} bad${if (it.high) ">=" else "<="}${f1(it.bad)}${it.unit}" }).append('\n')
        for (x in findings) sb.append("#s ${x.pkg}|${x.cause.replace('|', '/')}|${x.detail.replace('|', '/').replace('\n', ' ')}|${f1(x.points)}\n")
        return XSection(group, "App-side measurements, no adb (T%02d)".format(Locale.US, n), SRC_APP, sb.toString().trimEnd(), id = id(n))
    }

    private fun measures(text: String): Map<String, Double> =
        text.lineSequence().filter { it.startsWith("#m ") }.mapNotNull { l -> l.removePrefix("#m ").split('=', limit = 2).let { p -> p.getOrNull(1)?.toDoubleOrNull()?.let { p[0] to it } } }.toMap()

    private fun status(t: Threshold, v: Double): XStatus = if (t.high) { if (v >= t.bad) XStatus.BAD else if (v >= t.warn) XStatus.WARN else XStatus.OK }
        else { if (v <= t.bad) XStatus.BAD else if (v <= t.warn) XStatus.WARN else XStatus.OK }

    /** Every declared threshold the measures of [text] breach, as (threshold, value, status). */
    fun breaches(n: Int, text: String): List<Triple<Threshold, Double, XStatus>> {
        val m = measures(text)
        return THRESHOLDS.filter { it.tracker == n }.mapNotNull { t -> m[t.key]?.let { v -> status(t, v).takeIf { it == XStatus.WARN || it == XStatus.BAD }?.let { Triple(t, v, it) } } }
    }

    /** The digest of tracker [t] from its app-side section [text]: status = worst breach of the declared thresholds. */
    fun digest(t: XrayProbes.Tracker, text: String): XDigest {
        val m = measures(text)
        val br = breaches(t.num, text)
        val st = if (m.isEmpty()) XStatus.NA else br.maxOfOrNull { it.third.ordinal }?.let { XStatus.values()[it] } ?: XStatus.OK
        val measured = text.lineSequence().count { it.contains("(direct:") || it.contains("(fallback:") }
        val head = if (br.isNotEmpty()) br.joinToString(" ") { "${it.first.key} ${f1(it.second)}${it.first.unit}" }
            else (if (m.isEmpty()) "" else m.entries.take(3).joinToString(" ") { "${it.key} ${f1(it.value)}" }).ifEmpty { "no numeric signal" }
        val ls = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#m ") && !it.startsWith("#s ") }
            .map { l -> DdLine(l.substringBefore(": "), l.substringAfter(": ")) }.toList()
        return XDigest(t.label, t.num, st, "$head (no adb: $measured measured, ${ls.count { it.value.startsWith(ADB_ONLY_PREFIX) }} adb-only)", ls, emptyList())
    }

    /** The ranked suspects of a no-adb export: each per-package `#s` finding and each breached threshold (package "device"). */
    fun suspects(sections: List<XSection>): List<Suspect> {
        val raw = ArrayList<Suspect>()
        for (n in 1..12) {
            val text = sections.firstOrNull { it.id == id(n) }?.text ?: continue
            val label = XrayProbes.trackers.first { it.num == n }.label
            for ((t, v, st) in breaches(n, text))
                raw += Suspect(0, "device", (if (st == XStatus.BAD) 65.0 else 35.0) + Math.min(10.0, Math.abs(v - t.warn) / Math.max(t.warn, 1.0) * 10), "pts",
                    "T%02d %s: %s".format(Locale.US, n, label, t.key), "why: ${t.key}=${f1(v)}${t.unit} ${st.word} (warn ${if (t.high) ">=" else "<="}${f1(t.warn)}, bad ${if (t.high) ">=" else "<="}${f1(t.bad)})", false)
            for (l in text.lineSequence().filter { it.startsWith("#s ") }) {
                val p = l.removePrefix("#s ").split('|')
                if (p.size >= 4) raw += Suspect(0, p[0], p[3].toDoubleOrNull() ?: 30.0, "pts", "T%02d %s: %s".format(Locale.US, n, label, p[1]), "why: ${p[2]}", p[0].startsWith("com.diegonmarcos"))
            }
        }
        return raw.sortedByDescending { it.score }.mapIndexed { i, s -> s.copy(rank = i + 1) }
    }

    /** The VERDICT line: the worst measured status, the tracker statuses that are not ok, the top suspects. Null when nothing was measured. */
    fun verdict(dg: List<XDigest>, suspects: List<Suspect>): String? {
        val measured = dg.filter { it.num in 1..12 && it.status != XStatus.NA }
        if (measured.isEmpty()) return null
        val worst = measured.maxOf { it.status.ordinal }.let { XStatus.values()[it] }
        val bad = measured.filter { it.status == XStatus.WARN || it.status == XStatus.BAD }
        return "worst:${worst.word} measured:${measured.size}/12 " +
            (if (bad.isEmpty()) "all measured trackers within the declared thresholds" else "flagged:" + bad.joinToString(",") { "T%02d:%s".format(Locale.US, it.num, it.status.word) }) +
            (if (suspects.isEmpty()) "" else " top-suspects:" + suspects.take(3).joinToString(";") { "${it.pkg.removePrefix("com.diegonmarcos.")} ${it.cause.substringAfter(": ")}" })
    }
}

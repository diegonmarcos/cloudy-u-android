package com.diegonmarcos.superapp.fleetxray.perf

import java.util.Locale
import kotlin.math.ceil

/** One recorded run of an operation. A negative cpu / gc / alloc value means "not available on this device", never zero. */
public data class PerfRun(
    val op: String,
    val atMs: Long,
    val wallNs: Long,
    val cpuNs: Long,
    val items: Long = 0,
    val bytes: Long = 0,
    val gcCount: Long = -1,
    val gcTimeMs: Long = -1,
    val allocBytes: Long = -1,
    val ok: Boolean = true,
    val benchmark: Boolean = false,
    val note: String = "",
)

public enum class PerfVerdict(public val label: String, public val advice: String) {
    CPU_BOUND("CPU-bound", "most of the wall time is the thread computing: a native (Rust) rewrite may help"),
    IO_BOUND("IO-bound", "most of the wall time is waiting (disk, binder, network): a Rust rewrite will not help"),
    MIXED("mixed", "CPU and waiting both matter: optimise the waiting first, then profile the compute part"),
    UNKNOWN("unknown", "this device reports no thread CPU time for the operation: no verdict"),
}

public data class OpSummary(
    val op: String,
    val runs: Int,
    val failed: Int,
    val p50WallMs: Double,
    val p95WallMs: Double,
    val maxWallMs: Double,
    /** sum(cpu) / sum(wall) over the runs that report a CPU time; null = none does. May exceed 1.0 when the operation uses several threads. */
    val cpuRatio: Double?,
    val itemsPerSec: Double?,
    val mbPerSec: Double?,
    val gcCount: Long?,
    val gcTimeMs: Long?,
    val avgAllocMb: Double?,
    val verdict: PerfVerdict,
)

/** The arithmetic of the report, pure JVM so a unit test pins it. */
public object XrayPerfMath {
    public const val CPU_BOUND_ABOVE: Double = 0.6
    public const val IO_BOUND_BELOW: Double = 0.3

    /** Nearest-rank percentile of [sorted] (ascending); 0.0 for an empty list. */
    public fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    public fun verdict(cpuRatio: Double?): PerfVerdict = when {
        cpuRatio == null -> PerfVerdict.UNKNOWN
        cpuRatio > CPU_BOUND_ABOVE -> PerfVerdict.CPU_BOUND
        cpuRatio < IO_BOUND_BELOW -> PerfVerdict.IO_BOUND
        else -> PerfVerdict.MIXED
    }

    public fun summarize(op: String, runs: List<PerfRun>): OpSummary {
        val ok = runs.filter { it.ok }
        val basis = ok.ifEmpty { runs }
        val walls = basis.map { it.wallNs / 1e6 }.sorted()
        val withCpu = basis.filter { it.cpuNs >= 0 && it.wallNs > 0 }
        val cpuRatio = if (withCpu.isEmpty()) null else withCpu.sumOf { it.cpuNs }.toDouble() / withCpu.sumOf { it.wallNs }.toDouble()
        val itemRuns = basis.filter { it.items > 0 && it.wallNs > 0 }
        val byteRuns = basis.filter { it.bytes > 0 && it.wallNs > 0 }
        val gcRuns = basis.filter { it.gcCount >= 0 }
        val allocRuns = basis.filter { it.allocBytes >= 0 }
        return OpSummary(
            op = op, runs = runs.size, failed = runs.count { !it.ok },
            p50WallMs = percentile(walls, 50.0), p95WallMs = percentile(walls, 95.0), maxWallMs = walls.lastOrNull() ?: 0.0,
            cpuRatio = cpuRatio,
            itemsPerSec = if (itemRuns.isEmpty()) null else itemRuns.sumOf { it.items } / (itemRuns.sumOf { it.wallNs } / 1e9),
            mbPerSec = if (byteRuns.isEmpty()) null else (byteRuns.sumOf { it.bytes } / 1048576.0) / (byteRuns.sumOf { it.wallNs } / 1e9),
            gcCount = if (gcRuns.isEmpty()) null else gcRuns.sumOf { it.gcCount },
            gcTimeMs = gcRuns.filter { it.gcTimeMs >= 0 }.takeIf { it.isNotEmpty() }?.sumOf { it.gcTimeMs },
            avgAllocMb = if (allocRuns.isEmpty()) null else allocRuns.sumOf { it.allocBytes } / allocRuns.size / 1048576.0,
            verdict = verdict(cpuRatio),
        )
    }

    public fun summarizeAll(runs: List<PerfRun>): List<OpSummary> =
        runs.groupBy { it.op }.map { (op, rs) -> summarize(op, rs) }.sortedBy { it.op }
}

/** What one benchmark did when it was asked to run. */
public data class BenchOutcome(val op: String, val title: String, val state: String, val detail: String, val wallMs: Long)

public data class PerfDevice(
    val manufacturer: String, val model: String, val androidRelease: String, val sdk: Int,
    val appName: String, val appVersion: String, val timestamp: String,
)

/** The Markdown export of the Performance profile: header, table, verdicts, benchmark outcomes, per-run details. */
public object XrayPerfReport {
    public const val DETAIL_RUNS_PER_OP: Int = 12

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
    private fun f1(d: Double) = String.format(Locale.ROOT, "%.1f", d)
    private fun f2(d: Double) = String.format(Locale.ROOT, "%.2f", d)
    private fun na(d: Double?, fmt: (Double) -> String) = if (d == null) "n/a" else fmt(d)
    private fun table(headers: List<String>, rows: List<List<String>>): String = buildString {
        append("| ").append(headers.joinToString(" | ") { cell(it) }).append(" |\n")
        append("|").append(headers.joinToString("|") { "---" }).append("|\n")
        rows.forEach { append("| ").append(it.joinToString(" | ") { c -> cell(c) }).append(" |\n") }
    }

    public fun fileName(d: PerfDevice): String =
        "xray-performance-" + d.appName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-" +
            d.model.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-" + d.timestamp.replace(Regex("[^0-9T]"), "") + ".md"

    public fun verdictLine(s: OpSummary): String {
        val r = s.cpuRatio
        val ratio = if (r == null) "cpu/wall n/a" else "cpu/wall " + f2(r)
        return "**${s.op}**: ${s.verdict.label} ($ratio): ${s.verdict.advice}."
    }

    public fun markdown(d: PerfDevice, runs: List<PerfRun>, outcomes: List<BenchOutcome> = emptyList()): String {
        val sb = StringBuilder()
        val sums = XrayPerfMath.summarizeAll(runs)
        sb.append("# X-Ray Performance profile: ").append(cell(d.appName)).append("\n\n")
        sb.append("- Device: ").append(d.manufacturer).append(' ').append(d.model).append('\n')
        sb.append("- Android: ").append(d.androidRelease).append(" (API ").append(d.sdk).append(")\n")
        sb.append("- App: ").append(d.appName).append(' ').append(d.appVersion).append('\n')
        sb.append("- Generated: ").append(d.timestamp).append('\n')
        sb.append("- Operations: ").append(sums.size).append(", recorded runs: ").append(runs.size).append('\n')
        sb.append("- Scope: timings and counts only; no file contents, no credentials.\n")

        sb.append("\n## Summary\n\n")
        if (sums.isEmpty()) sb.append("no operation recorded yet (press Run benchmark, or use the app)\n")
        else sb.append(table(
            listOf("Operation", "Runs", "p50 ms", "p95 ms", "max ms", "Avg CPU %", "Items/s", "MB/s", "GC n", "GC ms", "Alloc MB/run", "Verdict"),
            sums.map { s -> listOf(
                s.op, if (s.failed > 0) "${s.runs} (${s.failed} failed)" else s.runs.toString(),
                f1(s.p50WallMs), f1(s.p95WallMs), f1(s.maxWallMs),
                na(s.cpuRatio) { f1(it * 100) }, na(s.itemsPerSec) { f1(it) }, na(s.mbPerSec) { f2(it) },
                s.gcCount?.toString() ?: "n/a", s.gcTimeMs?.toString() ?: "n/a", na(s.avgAllocMb) { f2(it) }, s.verdict.label) }))

        sb.append("\n## Verdicts\n\n")
        sb.append("Rule: cpu/wall above ${XrayPerfMath.CPU_BOUND_ABOVE} is CPU-bound (Rust may help), below ${XrayPerfMath.IO_BOUND_BELOW} is IO-bound (Rust will not help), in between is mixed.\n\n")
        if (sums.isEmpty()) sb.append("none\n") else sums.forEach { sb.append("- ").append(verdictLine(it)).append('\n') }

        if (outcomes.isNotEmpty()) {
            sb.append("\n## Benchmarks\n\n")
            sb.append(table(listOf("Operation", "Benchmark", "State", "Detail", "Wall ms"), outcomes.map { listOf(it.op, it.title, it.state, it.detail, it.wallMs.toString()) }))
        }

        sb.append("\n## Per-run details\n")
        if (runs.isEmpty()) sb.append("\nnone\n")
        runs.groupBy { it.op }.toSortedMap().forEach { (op, rs) ->
            sb.append("\n### ").append(cell(op)).append("\n\n")
            val shown = rs.takeLast(DETAIL_RUNS_PER_OP)
            sb.append(table(
                listOf("#", "At (epoch ms)", "Wall ms", "CPU ms", "CPU %", "Items", "Bytes", "GC n", "GC ms", "Alloc MB", "OK", "Note"),
                shown.mapIndexed { i, r -> listOf(
                    (rs.size - shown.size + i + 1).toString(), r.atMs.toString(), f1(r.wallNs / 1e6),
                    if (r.cpuNs >= 0) f1(r.cpuNs / 1e6) else "n/a",
                    if (r.cpuNs >= 0 && r.wallNs > 0) f1(r.cpuNs * 100.0 / r.wallNs) else "n/a",
                    r.items.toString(), r.bytes.toString(),
                    if (r.gcCount >= 0) r.gcCount.toString() else "n/a", if (r.gcTimeMs >= 0) r.gcTimeMs.toString() else "n/a",
                    if (r.allocBytes >= 0) f2(r.allocBytes / 1048576.0) else "n/a",
                    if (r.ok) "yes" else "no", (if (r.benchmark) "benchmark. " else "") + r.note) }))
            if (rs.size > shown.size) sb.append("\n(${rs.size - shown.size} older runs of this operation are in the summary only)\n")
        }
        sb.append("\n## Method\n\n")
        sb.append("- Wall: monotonic clock around the operation. CPU: the calling thread's CPU time (Debug.threadCpuTimeNanos); a suspend span that resumes on another thread reports no CPU.\n")
        sb.append("- GC and Alloc: ART runtime counters (art.gc.*) before and after, process-wide, so other threads add noise.\n")
        sb.append("- Items/s and MB/s: totals over the successful runs divided by their wall time.\n")
        return sb.toString()
    }

    /** Well-formedness used by the tests: the problems, empty = fine. */
    public fun lint(md: String): List<String> {
        val bad = ArrayList<String>()
        val lines = md.lines()
        if (lines.count { it.startsWith("# ") } != 1) bad += "exactly one H1 expected"
        var cols = -1
        for ((n, l) in lines.withIndex()) {
            if (l.startsWith("|")) {
                val c = l.replace("\\|", "").count { it == '|' }
                if (cols == -1) cols = c else if (c != cols) bad += "table row ${n + 1} has $c pipes, expected $cols"
            } else cols = -1
        }
        if (!md.endsWith("\n")) bad += "no trailing newline"
        return bad
    }
}

/** The persisted form of the ring buffer: one run per line, tab separated, the note escaped. Pure, so a test round-trips it. */
public object XrayPerfCodec {
    private const val HEADER = "xray-perf-v1"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    public fun encode(runs: List<PerfRun>): String = buildString {
        append(HEADER).append('\n')
        runs.forEach { r ->
            append(esc(r.op)).append('\t').append(r.atMs).append('\t').append(r.wallNs).append('\t').append(r.cpuNs).append('\t').append(r.items).append('\t')
                .append(r.bytes).append('\t').append(r.gcCount).append('\t').append(r.gcTimeMs).append('\t').append(r.allocBytes).append('\t')
                .append(if (r.ok) 1 else 0).append('\t').append(if (r.benchmark) 1 else 0).append('\t').append(esc(r.note)).append('\n')
        }
    }

    public fun decode(text: String): List<PerfRun> {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 12) return@mapNotNull null
            runCatching { PerfRun(unesc(p[0]), p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7].toLong(), p[8].toLong(), p[9] == "1", p[10] == "1", unesc(p[11])) }.getOrNull()
        }
    }
}

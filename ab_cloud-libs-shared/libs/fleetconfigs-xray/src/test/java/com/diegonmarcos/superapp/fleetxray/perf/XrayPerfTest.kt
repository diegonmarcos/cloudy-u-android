package com.diegonmarcos.superapp.fleetxray.perf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayPerfTest {
    private class FakeClock(var wall: Long = 0, var cpu: Long = 0, var gc: Long = 0, var gcMs: Long = 0, var alloc: Long = 0) : PerfClock {
        override fun wallNs() = wall
        override fun cpuNs() = cpu
        override fun gcCount() = gc
        override fun gcTimeMs() = gcMs
        override fun allocBytes() = alloc
        override fun nowMs() = 1_000L
    }

    private fun run(op: String, wallMs: Long, cpuMs: Long, items: Long = 0, bytes: Long = 0) =
        PerfRun(op, 1, wallMs * 1_000_000, if (cpuMs < 0) -1 else cpuMs * 1_000_000, items, bytes, 0, 0, 1_048_576)

    @Test fun percentilesAreNearestRank() {
        val xs = (1..100).map { it.toDouble() }
        assertEquals(50.0, XrayPerfMath.percentile(xs, 50.0), 0.0)
        assertEquals(95.0, XrayPerfMath.percentile(xs, 95.0), 0.0)
        assertEquals(100.0, XrayPerfMath.percentile(xs, 100.0), 0.0)
        assertEquals(7.0, XrayPerfMath.percentile(listOf(7.0), 95.0), 0.0)
        assertEquals(0.0, XrayPerfMath.percentile(emptyList(), 50.0), 0.0)
    }

    @Test fun cpuSplitIsSumCpuOverSumWall() {
        val s = XrayPerfMath.summarize("op", listOf(run("op", 100, 90), run("op", 300, 30)))
        assertEquals(120.0 / 400.0, s.cpuRatio!!, 1e-9)
        assertEquals(100.0, s.p50WallMs, 1e-9)
        assertEquals(300.0, s.maxWallMs, 1e-9)
    }

    @Test fun runsWithoutCpuAreExcludedFromTheSplitAndMakeNoVerdictWhenAlone() {
        val mixed = XrayPerfMath.summarize("op", listOf(run("op", 100, 80), run("op", 100, -1)))
        assertEquals(0.8, mixed.cpuRatio!!, 1e-9)
        val none = XrayPerfMath.summarize("op", listOf(run("op", 100, -1)))
        assertNull(none.cpuRatio); assertEquals(PerfVerdict.UNKNOWN, none.verdict)
    }

    @Test fun verdictThresholds() {
        assertEquals(PerfVerdict.CPU_BOUND, XrayPerfMath.verdict(0.61))
        assertEquals(PerfVerdict.MIXED, XrayPerfMath.verdict(0.6))
        assertEquals(PerfVerdict.MIXED, XrayPerfMath.verdict(0.3))
        assertEquals(PerfVerdict.IO_BOUND, XrayPerfMath.verdict(0.29))
        assertEquals(PerfVerdict.CPU_BOUND, XrayPerfMath.verdict(1.7))
        assertEquals(PerfVerdict.UNKNOWN, XrayPerfMath.verdict(null))
    }

    @Test fun throughputIsTotalOverTotalWall() {
        val s = XrayPerfMath.summarize("op", listOf(run("op", 500, 100, items = 1000, bytes = 1_048_576), run("op", 500, 100, items = 1000, bytes = 1_048_576)))
        assertEquals(2000.0, s.itemsPerSec!!, 1e-6)
        assertEquals(2.0, s.mbPerSec!!, 1e-6)
        assertNull(XrayPerfMath.summarize("op", listOf(run("op", 10, 1))).itemsPerSec)
    }

    @Test fun failedRunsAreCountedButNotTimed() {
        val rs = listOf(run("op", 100, 10), run("op", 9000, 10).copy(ok = false))
        val s = XrayPerfMath.summarize("op", rs)
        assertEquals(1, s.failed); assertEquals(100.0, s.maxWallMs, 1e-9)
    }

    @Test fun spanRecordsWallCpuGcAndAllocDeltas() {
        val c = FakeClock(); XrayProfiler.resetForTest(c)
        val r = XrayProfiler.span("scan") { s ->
            c.wall += 200_000_000; c.cpu += 150_000_000; c.gc += 2; c.gcMs += 7; c.alloc += 4_194_304
            XrayProfiler.count(items = 10, bytes = 2048); s.note = "x"; 42
        }
        assertEquals(42, r)
        val run = XrayProfiler.runs().single()
        assertEquals(200_000_000L, run.wallNs); assertEquals(150_000_000L, run.cpuNs)
        assertEquals(2L, run.gcCount); assertEquals(7L, run.gcTimeMs); assertEquals(4_194_304L, run.allocBytes)
        assertEquals(10L, run.items); assertEquals(2048L, run.bytes); assertEquals("x", run.note)
    }

    @Test fun unavailableCountersStayNegativeNotZero() {
        val c = object : PerfClock { var w = 0L
            override fun wallNs() = w.also { w += 5 }
            override fun cpuNs() = -1L; override fun gcCount() = -1L; override fun gcTimeMs() = -1L; override fun allocBytes() = -1L; override fun nowMs() = 1L }
        XrayProfiler.resetForTest(c)
        XrayProfiler.span("x") { }
        val r = XrayProfiler.runs().single()
        assertEquals(-1L, r.cpuNs); assertEquals(-1L, r.gcCount); assertEquals(-1L, r.allocBytes)
    }

    @Test fun aThrowingSpanIsRecordedAsFailedAndRethrown() {
        XrayProfiler.resetForTest(FakeClock())
        try { XrayProfiler.span("bad") { throw IllegalStateException("boom") }; error("no throw") } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        val r = XrayProfiler.runs().single()
        assertTrue(!r.ok); assertTrue(r.note.contains("boom"))
    }

    @Test fun nestedSpansCountIntoTheInnermost() {
        XrayProfiler.resetForTest(FakeClock())
        XrayProfiler.span("outer") { XrayProfiler.span("inner") { XrayProfiler.count(items = 5) }; XrayProfiler.count(items = 1) }
        val by = XrayProfiler.runs().associateBy { it.op }
        assertEquals(5L, by["inner"]!!.items); assertEquals(1L, by["outer"]!!.items)
    }

    @Test fun suspendSpanRecordsAndMarksAThreadHopAsNoCpu() {
        val c = FakeClock(); XrayProfiler.resetForTest(c)
        runBlocking { XrayProfiler.spanSuspend("s") { c.wall += 1_000_000; c.cpu += 500_000; it.add(items = 3) } }
        val r = XrayProfiler.runs().single()
        assertEquals(500_000L, r.cpuNs); assertEquals(3L, r.items)
    }

    @Test fun ringIsBoundedPerOperationAndInTotalOps() {
        XrayProfiler.resetForTest(FakeClock())
        repeat(XrayProfiler.RING_PER_OP + 30) { XrayProfiler.span("hot") { } }
        assertEquals(XrayProfiler.RING_PER_OP, XrayProfiler.runs().count { it.op == "hot" })
        repeat(XrayProfiler.MAX_OPS + 10) { XrayProfiler.span("op$it") { } }
        assertTrue(XrayProfiler.runs().map { it.op }.distinct().size <= XrayProfiler.MAX_OPS)
    }

    @Test fun disabledProfilerRecordsNothing() {
        XrayProfiler.resetForTest(FakeClock()); XrayProfiler.enabled = false
        assertEquals(1, XrayProfiler.span("x") { 1 })
        assertTrue(XrayProfiler.runs().isEmpty())
        XrayProfiler.enabled = true
    }

    @Test fun codecRoundTripsEveryField() {
        val rs = listOf(PerfRun("a\tb", 5, 6, 7, 8, 9, 1, 2, 3, ok = false, benchmark = true, note = "line\nwith\ttab \\ slash"), run("z", 1, -1))
        assertEquals(rs, XrayPerfCodec.decode(XrayPerfCodec.encode(rs)))
        assertTrue(XrayPerfCodec.decode("garbage").isEmpty())
    }

    private val dev = PerfDevice("Google", "Pixel 8 | Pro", "15", 35, "Cloud Drive", "1.2.3", "2026-11-13T10:00:00+0000")

    @Test fun reportMarkdownHasHeaderTableVerdictsAndPerRunDetails() {
        val rs = listOf(run("hash", 100, 90, items = 10, bytes = 1_048_576), run("hash", 100, 80), run("git.status", 200, 20))
        val md = XrayPerfReport.markdown(dev, rs, listOf(BenchOutcome("hash", "Hash sample", "ran", "3 run(s)", 300)))
        assertEquals(emptyList<String>(), XrayPerfReport.lint(md))
        for (needle in listOf("# X-Ray Performance profile: Cloud Drive", "- Device: Google Pixel 8 | Pro", "- Android: 15 (API 35)", "- App: Cloud Drive 1.2.3",
            "- Generated: 2026-11-13T10:00:00+0000", "## Summary", "| Operation | Runs | p50 ms | p95 ms | max ms | Avg CPU % | Items/s | MB/s |", "## Verdicts",
            "**hash**: CPU-bound", "**git.status**: IO-bound", "## Benchmarks", "## Per-run details", "### hash", "### git.status", "## Method")) assertTrue(needle, md.contains(needle))
        assertTrue(md.contains("Pixel 8 | Pro").also { } )
    }

    @Test fun emptyReportStillLintsAndSaysSo() {
        val md = XrayPerfReport.markdown(dev, emptyList())
        assertEquals(emptyList<String>(), XrayPerfReport.lint(md)); assertTrue(md.contains("no operation recorded yet"))
    }

    @Test fun perRunDetailIsCappedAndSaysSo() {
        val rs = (1..30).map { run("op", it.toLong(), 1) }
        val md = XrayPerfReport.markdown(dev, rs)
        assertTrue(md.contains("(${30 - XrayPerfReport.DETAIL_RUNS_PER_OP} older runs of this operation are in the summary only)"))
    }

    @Test fun fileNameIsSafe() {
        assertEquals("xray-performance-cloud-drive-pixel-8-pro-20261113T1000000000.md", XrayPerfReport.fileName(dev))
    }
}

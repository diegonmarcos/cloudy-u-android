package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #1887 the Explored speed (review steps 1-4): an open is served from the process-scoped snapshot (no digest load, no segment read, no
 * compute, no write), concurrent opens share one refresh, a refresh is cancellable between dates and keeps what it computed, the Day
 * tab reads one 48 h window and folds no aggregate, and only the latest date's load is applied. Each was red on the previous code.
 */
class ExploredRepositoryTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val H = 3_600_000L
    private val d0 = LocalDate.of(2026, 10, 1)
    private val days = 20
    private val dates = (0 until days).map { d0.plusDays(it.toLong()) }
    private val today = d0.plusDays(days + 1L)
    private val now = DayTimeline.dayStart(today, zone) + 10 * H
    private fun at(i: Int, h: Double) = DayTimeline.dayStart(d0.plusDays(i.toLong()), zone) + Math.round(h * H)

    /** Twenty days: nights at home in Berlin (Lisbon on days 8-10), an office day, a drive. */
    private fun store(): List<TlSegment> = dates.indices.flatMap { i ->
        val away = i in 8..10
        val city = if (away) "Lisbon" else "Berlin"; val country = if (away) "Portugal" else "Germany"
        val lat = if (away) 38.72 else 52.52; val lon = if (away) -9.14 else 13.40
        listOf(
            TlSegment(TlKind.SLEEP, at(i, -1.0), at(i, 7.0), lat, lon, "Home", 0, 0.0, "fix", null, city, country),
            TlSegment(TlKind.STOP, at(i, 7.0), at(i, 8.0), lat, lon, "Home", 0, 0.0, "fix", null, city, country),
            TlSegment(TlKind.MOVE, at(i, 8.0), at(i, 8.5), lat + 0.01, lon, null, 2_600, 2_000.0, null),
            TlSegment(TlKind.STOP, at(i, 8.5), at(i, 17.0), lat + 0.01, lon, "Office", 0, 0.0, "fix", null, city, country),
            TlSegment(TlKind.MOVE, at(i, 17.0), at(i, 17.5), lat, lon, null, 0, 9_000.0, "mode:car"),
            TlSegment(TlKind.STOP, at(i, 17.5), at(i, 23.0), lat, lon, "Home", 0, 0.0, "fix", null, city, country),
        )
    }

    private class CountingBackend : DigestBackend {
        val inner = MemoryDigestBackend()
        @Volatile var loads = 0
        val writes: Int get() = inner.writes
        override fun load(): Map<LocalDate, String> { loads++; return inner.load() }
        override fun put(rows: Map<LocalDate, String>) = inner.put(rows)
        override fun remove(dates: Collection<LocalDate>) = inner.remove(dates)
        override fun clear() = inner.clear()
    }

    /** Counts every digest load, segment read and aggregate row written; [gate] holds the segment read until released. */
    private inner class Inputs(private val segs: List<TlSegment> = store(), private val gate: CountDownLatch? = null) : ExploredInputs {
        override val backend = CountingBackend()
        @Volatile var segmentReads = 0
        @Volatile var persisted = 0
        override fun windowStart(today: LocalDate, zone: ZoneId): LocalDate = d0
        override fun segments(fromMs: Long, toMs: Long): List<TlSegment> {
            segmentReads++
            gate?.await(5, TimeUnit.SECONDS)
            return segs.filter { it.endMs >= fromMs && it.startMs <= toMs }
        }
        override fun corrections(): List<Correction> = emptyList()
        override fun memory(): List<Remembered> = emptyList()
        override fun home(): String? = null
        override fun persist(nowMs: Long, rows: Map<String, String>) { persisted += rows.size }
    }

    @Test fun `a second open is served from memory with no digest load, no segment read and no compute`() {
        val inp = Inputs(); val repo = ExploredRepository(inp, clock = { now })
        val first = runBlocking { repo.refresh(zone, today).await() }
        assertNotNull(first); assertTrue(first!!.explored.places.isNotEmpty())
        val loads = inp.backend.loads; val reads = inp.segmentReads; val computed = ExploredCache.computeCount; val builds = repo.builds
        val second = repo.open(zone, today)
        assertSame("the open returns the snapshot in memory", first, second)
        assertEquals("no digest load", loads, inp.backend.loads)
        assertEquals("no segment read", reads, inp.segmentReads)
        assertEquals("no digest computed", computed, ExploredCache.computeCount)
        assertEquals("no refresh started", builds, repo.builds)
    }

    @Test fun `concurrent opens share one refresh`() {
        val gate = CountDownLatch(1); val inp = Inputs(gate = gate); val repo = ExploredRepository(inp, clock = { now })
        val threads = (1..8).map { Thread { repo.open(zone, today) }.apply { start() } }
        Thread.sleep(300); gate.countDown(); threads.forEach { it.join(20_000) }
        runBlocking { withTimeout(20_000) { repo.state.first { it != null } } }
        assertEquals("one segment read for eight opens", 1, inp.segmentReads)
        assertEquals("one refresh for eight opens", 1, repo.builds)
    }

    @Test fun `a second open writes nothing and a refresh over unchanged inputs persists nothing`() {
        val inp = Inputs(); val repo = ExploredRepository(inp, clock = { now })
        runBlocking { repo.refresh(zone, today).await() }
        val writes = inp.backend.writes; val persisted = inp.persisted
        assertTrue("the first refresh persists the aggregates", persisted > 0)
        repo.open(zone, today)
        repo.invalidate()
        runBlocking { repo.refresh(zone, today).await() }
        assertEquals("no digest written", writes, inp.backend.writes)
        assertEquals("no aggregate row written", persisted, inp.persisted)
    }

    @Test fun `a cancelled refresh stops within one date and a later one resumes from what it stored`() {
        val be = MemoryDigestBackend(); val s = store(); val before = ExploredCache.computeCount
        var thrown = false
        try { ExploredCache.refresh(be, s, dates, now, zone, cancelled = { ExploredCache.computeCount - before >= 3 }) }
        catch (e: java.util.concurrent.CancellationException) { thrown = true }
        assertTrue("the refresh stops", thrown)
        assertEquals("it stops within one date", 3L, ExploredCache.computeCount - before)
        assertEquals("what it computed is kept", 3, be.rows.size)
        assertEquals("the next refresh computes only the rest", days - 3, ExploredCache.refresh(be, s, dates, now, zone).recomputed.size)
    }

    @Test fun `cancel stops a running refresh before it computes`() {
        val gate = CountDownLatch(1); val inp = Inputs(gate = gate); val repo = ExploredRepository(inp, clock = { now })
        val before = ExploredCache.computeCount
        val d = repo.refresh(zone, today)
        repo.cancel(); gate.countDown()
        runBlocking { runCatching { d.await() } }
        assertTrue("the refresh was cancelled", d.isCancelled)
        assertTrue("at most one date computed after the cancel", ExploredCache.computeCount - before <= 1)
    }

    @Test fun `only the latest date load is applied`() {
        val applied = ArrayList<String>(); val slow = CountDownLatch(1); val loads = LatestOnly()
        runBlocking {
            val older = loads.launch(this, Dispatchers.IO, { slow.await(5, TimeUnit.SECONDS); "older date" }) { applied += it }
            val newer = loads.launch(this, Dispatchers.IO, { "newer date" }) { applied += it }
            newer.join(); slow.countDown(); older.join()
        }
        assertEquals(listOf("newer date"), applied)
    }

    @Test fun `a date change reads one 48 h window and folds no aggregate`() {
        val s = store(); val asked = ArrayList<Pair<Long, Long>>()
        val folds = AggregatesStore.exploreCount
        val date = dates[5]
        val d = DayLoad.day({ a, b -> asked += a to b; s.filter { it.endMs >= a && it.startMs <= b } }, date, now, zone, emptyList(), emptyList())
        assertEquals("one read", 1, asked.size)
        assertTrue("the read spans at most 48 h", asked[0].second - asked[0].first <= 48 * H)
        assertEquals("no aggregate folded", folds, AggregatesStore.exploreCount)
        val a = DayTimeline.dayStart(date, zone); val b = DayTimeline.dayStart(date.plusDays(1), zone)
        assertEquals("the same day as a full read", DayTimeline.build(s, a, b), d.day)
        assertEquals("the same stats as a full read", Aggregates.dayOf(s, date, now, zone).first, d.stats)
    }
}

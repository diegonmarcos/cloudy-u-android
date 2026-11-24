package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/** #1887 what the Explored repository reads: maps.db on the phone, counting fakes in the JVM tests. */
interface ExploredInputs {
    val backend: DigestBackend
    fun windowStart(today: LocalDate, zone: ZoneId): LocalDate
    fun segments(fromMs: Long, toMs: Long): List<TlSegment>
    fun corrections(): List<Correction>
    fun memory(): List<Remembered>
    fun home(): String?
    /** The #1851 aggregate rows (kind to JSON) that changed. */
    fun persist(nowMs: Long, rows: Map<String, String>)
}

/** #1887 one built Explored state: every aggregate field the page shows, and what it was built for. */
class ExploredSnapshot(val explored: AggregatesStore.Explored, val gen: Long, val zone: ZoneId, val today: LocalDate, val first: LocalDate,
                       val builtMs: Long, val tookMs: Long, val home: String?) {
    val rangeLabel: String get() = if (first.isBefore(today.minusDays(Trips.HOME_WINDOW_DAYS - 1L))) "all recorded and imported history" else "last 30 days"
    val cacheLine: String get() = "${explored.cache.reused} days from cache, ${explored.cache.recomputed.size} recomputed, ${explored.cache.live.size} live"
}

/** #1887 the Explored state (seam: the previous behaviour, every open recomputes everything on the caller's thread). */
class ExploredRepository(private val inputs: ExploredInputs, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<ExploredSnapshot?>(null)
    val state: StateFlow<ExploredSnapshot?> get() = _state
    @Volatile var builds = 0
        private set

    fun invalidate() {}
    fun cancel() {}
    fun open(zone: ZoneId, today: LocalDate): ExploredSnapshot? = build(zone, today)
    fun refresh(zone: ZoneId, today: LocalDate): Deferred<ExploredSnapshot?> = CompletableDeferred(build(zone, today))

    private fun build(zone: ZoneId, today: LocalDate): ExploredSnapshot {
        builds++
        val now = clock()
        val first = inputs.windowStart(today, zone)
        val store = inputs.segments(DayTimeline.dayStart(first, zone) - ExploredCache.WIDE_MS, now)
        val home = inputs.home()
        val e = AggregatesStore.explore(inputs.backend, store, first, today, now, zone, inputs.corrections(), inputs.memory(), home)
        inputs.persist(now, AggregatesStore.rows(e.stats, e.trips, e.places, e.cities, e.countries))
        return ExploredSnapshot(e, 0L, zone, today, first, now, 0L, home).also { _state.value = it }
    }
}

/** #1887 the Day tab's loads (seam: the previous behaviour, every load is applied when it ends). */
class LatestOnly {
    fun <T> launch(scope: CoroutineScope, context: CoroutineContext, work: suspend () -> T, apply: (T) -> Unit): Job =
        scope.launch { apply(withContext(context) { work() }) }
}

/** #1887 one Day (seam: the previous behaviour, the aggregates' whole window is read and folded for every date). */
object DayLoad {
    class Result(val day: List<DaySeg>, val stats: DayStats, val window: List<TlSegment>)

    fun day(read: (Long, Long) -> List<TlSegment>, date: LocalDate, nowMs: Long, zone: ZoneId, corrections: List<Correction>, memory: List<Remembered>): Result {
        val first = date.minusDays(Trips.HOME_WINDOW_DAYS - 1L)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val store = read(DayTimeline.dayStart(first, zone) - ExploredCache.WIDE_MS, nowMs)
        val ex = AggregatesStore.explore(MemoryDigestBackend(), store, first, today, nowMs, zone, corrections, memory, null)
        val a = DayTimeline.dayStart(date, zone); val b = minOf(DayTimeline.dayStart(date.plusDays(1), zone), nowMs)
        val stats = ex.stats.firstOrNull { it.date == date } ?: Aggregates.dayOf(store, date, nowMs, zone, corrections, memory).first
        return Result(DayTimeline.build(store, a, b, corrections, memory), stats, store)
    }
}

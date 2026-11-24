package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.TimelineSync
import com.diegonmarcos.cloudnav.maps.TlSegment
import com.diegonmarcos.cloudnav.maps.takeout.Gazetteer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** #1887 what the Explored repository reads: maps.db on the phone ([MapsDbExploredInputs]), counting fakes in the JVM tests. */
interface ExploredInputs {
    val backend: DigestBackend
    fun windowStart(today: LocalDate, zone: ZoneId): LocalDate
    fun segments(fromMs: Long, toMs: Long): List<TlSegment>
    fun corrections(): List<Correction>
    fun memory(): List<Remembered>
    fun home(): String?
    /** The #1851 aggregate rows (kind to JSON) that changed since the last write; nothing is written when none did. */
    fun persist(nowMs: Long, rows: Map<String, String>)
}

/** #1887 one built Explored state: every aggregate field the page shows ([DayUi]), and what it was built for. */
class ExploredSnapshot(val explored: AggregatesStore.Explored, val gen: Long, val zone: ZoneId, val today: LocalDate, val first: LocalDate,
                       val builtMs: Long, val tookMs: Long, val home: String?) {
    val rangeLabel: String get() = if (first.isBefore(today.minusDays(Trips.HOME_WINDOW_DAYS - 1L))) "all recorded and imported history" else "last 30 days"
    val cacheLine: String get() = "${explored.cache.reused} days from cache, ${explored.cache.recomputed.size} recomputed, ${explored.cache.live.size} live" +
        (if (gen < 0) "  ·  stored copy, refreshing" else "  ·  built in $tookMs ms")
}

/**
 * #1887 the process-scoped Explored state (review steps 1, 3, 4): the page, the engine tick and the import read one in-memory
 * [ExploredSnapshot] and never wait for it. Stale-while-revalidate: [open] returns what is in memory at once and starts a refresh only
 * when it is missing, built for another day or zone, older than [MAX_AGE_MS] or [invalidate]d by a writer. A refresh is single-flight
 * (concurrent requests join the running one; a write during it runs one more pass), runs on the repository's own low-priority thread
 * (never the page's, so leaving the page cancels nothing and blocks nothing), checks cancellation between dates, and writes the #1851
 * aggregate rows only when they changed. A cold process paints the stored digests first ([seed]) while the refresh validates them.
 */
class ExploredRepository(private val inputs: ExploredInputs, private val clock: () -> Long = System::currentTimeMillis) {
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "explored-repository").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher())
    private val _state = MutableStateFlow<ExploredSnapshot?>(null)
    val state: StateFlow<ExploredSnapshot?> get() = _state
    private val gen = AtomicLong(0)
    private val lock = Any()
    @Volatile private var inFlight: Deferred<ExploredSnapshot?>? = null
    private val lastRows = HashMap<String, String>()
    /** Full passes run in this repository (the JVM test proves an open adds none). */
    @Volatile var builds = 0
        private set

    val generation: Long get() = gen.get()
    fun current(): ExploredSnapshot? = _state.value

    /** A writer changed the store, the corrections or the home: the next open refreshes (the snapshot in memory is served meanwhile). */
    fun invalidate() { gen.incrementAndGet() }

    fun isFresh(s: ExploredSnapshot?, zone: ZoneId, today: LocalDate): Boolean =
        s != null && s.gen == gen.get() && s.zone == zone && s.today == today && clock() - s.builtMs < MAX_AGE_MS

    /** What the page calls: the snapshot in memory now (null only before the first one), plus a background refresh when it is stale. */
    fun open(zone: ZoneId, today: LocalDate): ExploredSnapshot? {
        val s = _state.value
        if (!isFresh(s, zone, today)) refresh(zone, today)
        return s
    }

    /** One refresh at a time: a running one is joined, never duplicated. */
    fun refresh(zone: ZoneId, today: LocalDate): Deferred<ExploredSnapshot?> = synchronized(lock) {
        inFlight?.let { if (it.isActive) return it }
        val d = scope.async {
            val job = coroutineContext[Job]
            if (_state.value == null) runCatching { seed(zone, today) }.getOrNull()?.let { if (_state.value == null) _state.value = it }
            var out: ExploredSnapshot? = null
            while (true) {
                val g = gen.get()
                out = build(zone, today, g) { job?.isActive == false }
                _state.value = out
                if (gen.get() == g) break   // a write during the pass: one more pass (cheap, the digests are cached)
            }
            out
        }
        inFlight = d
        d
    }

    /** Stops the running refresh at its next date; what it computed stays stored. */
    fun cancel() { synchronized(lock) { inFlight?.cancel() } }

    /** The engine's tick and the import: invalidate, then wait for the shared refresh (off the main thread only). */
    fun rebuild(zone: ZoneId, nowMs: Long) {
        invalidate()
        runBlocking { refresh(zone, java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()).await() }
    }

    /** The stored digests folded as they are (no segment read, no checksum): a cold process paints this, then the refresh replaces it. */
    private fun seed(zone: ZoneId, today: LocalDate): ExploredSnapshot? {
        val stored = inputs.backend.load()
        if (stored.isEmpty()) return null
        val first = inputs.windowStart(today, zone)
        val (dates, rankDates) = AggregatesStore.windowDates(first, today)
        val ds = dates.mapNotNull { d -> stored[d]?.let { ExploredCache.fromJson(it) } }
        val home = inputs.home()
        val e = AggregatesStore.fold(CacheResult(ds, emptyList(), emptyList(), ds.size), rankDates, home)
        return ExploredSnapshot(e, -1L, zone, today, first, clock(), 0L, home)
    }

    private fun build(zone: ZoneId, today: LocalDate, g: Long, cancelled: () -> Boolean): ExploredSnapshot {
        builds++
        val t0 = System.nanoTime()
        val now = clock()
        val first = inputs.windowStart(today, zone)
        val store = inputs.segments(DayTimeline.dayStart(first, zone) - ExploredCache.WIDE_MS, now)
        val home = inputs.home()
        val e = AggregatesStore.explore(inputs.backend, store, first, today, now, zone, inputs.corrections(), inputs.memory(), home, cancelled)
        val rows = AggregatesStore.rows(e.stats, e.trips, e.places, e.cities, e.countries)
        val changed = rows.filter { lastRows[it.key] != it.value }
        if (changed.isNotEmpty()) { runCatching { inputs.persist(now, changed) }; lastRows.putAll(changed) }
        return ExploredSnapshot(e, g, zone, today, first, now, (System.nanoTime() - t0) / 1_000_000L, home)
    }

    companion object {
        /** A snapshot older than this is refreshed in the background on the next open (today's live numbers move). */
        const val MAX_AGE_MS = 10 * 60_000L

        @Volatile private var instance: ExploredRepository? = null

        fun get(ctx: Context): ExploredRepository = instance ?: synchronized(this) {
            instance ?: ExploredRepository(MapsDbExploredInputs(ctx.applicationContext)).also { instance = it }
        }
    }
}

/** #1887 the repository's inputs on the phone: the one store (maps.db), the corrections and the home city. */
class MapsDbExploredInputs(private val app: Context) : ExploredInputs {
    override val backend: DigestBackend = MapsDbDigestBackend(app)
    override fun windowStart(today: LocalDate, zone: ZoneId): LocalDate = AggregatesStore.windowStart(app, today, zone)
    override fun segments(fromMs: Long, toMs: Long): List<TlSegment> = TimelineSync.view(app, fromMs, toMs)
    override fun corrections(): List<Correction> = CorrectionStore(app).all()
    override fun memory(): List<Remembered> = CorrectionStore(app).memory()
    override fun home(): String? = HomeStore(app).get()
    override fun persist(nowMs: Long, rows: Map<String, String>) {
        val db = MapsDb.get(app)
        for ((kind, json) in rows) db.putAggregate(kind, AggregatesStore.keyOf(kind), json, nowMs)
    }
}

/** #1887 the shipped offline gazetteer (assets/geo/gazetteer.tsv, GeoNames cities15000), read once per process off the main thread. */
object NavGazetteer {
    @Volatile private var g: Gazetteer? = null
    fun get(ctx: Context): Gazetteer? = g ?: synchronized(this) {
        g ?: runCatching { ctx.applicationContext.assets.open("geo/gazetteer.tsv").reader(Charsets.UTF_8).use { Gazetteer.parse(it) } }.getOrNull().also { g = it }
    }
}

/**
 * #1887 the Day tab's loads (review step 4): a new date cancels the load still running and only the latest request's result is applied,
 * so a slow older date never lands over a newer one.
 */
class LatestOnly {
    private val seq = AtomicLong(0)
    @Volatile private var job: Job? = null

    fun <T> launch(scope: CoroutineScope, context: CoroutineContext, work: suspend () -> T, apply: (T) -> Unit): Job {
        val t = seq.incrementAndGet()
        job?.cancel()
        return scope.launch {
            val r = withContext(context) { work() }
            if (t == seq.get()) apply(r)
        }.also { job = it }
    }
}

/**
 * #1887 one Day (review step 2): its rows from ONE window, the date and its 12 h sleep spill either side (48 h at most), never the
 * whole history, and no aggregate is folded. [Result.day] and [Result.stats] equal what a read of every row gives.
 */
object DayLoad {
    class Result(val day: List<DaySeg>, val stats: DayStats, val window: List<TlSegment>)

    fun day(read: (Long, Long) -> List<TlSegment>, date: LocalDate, nowMs: Long, zone: ZoneId, corrections: List<Correction>, memory: List<Remembered>): Result {
        val (a, b) = ExploredCache.window(date, zone)
        val window = read(a, minOf(b, maxOf(nowMs, a)))
        val (stats, day) = Aggregates.dayOf(window, date, nowMs, zone, corrections, memory)
        return Result(day, stats, window)
    }
}

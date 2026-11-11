package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.battery.EnergyLedger
import java.util.concurrent.ConcurrentHashMap

/**
 * #1649 The in-process recorder behind Configs ▸ X-Ray App: what each ENGINE of the SuperApp (the catalogue is [EngineProfiles])
 * really did since this process started. An engine's hot path is wrapped in [time]; the recorder keeps, per (engine, path):
 *  - the wall time of every run (count, total, max, and the last [RING] samples for the percentiles and the over-budget share);
 *  - the calling thread's CPU time, split by whether the app was in the foreground ([foreground]) when the run ended;
 *  - every throw that escaped the path, and every handled failure reported through [error].
 * The CPU time also lands in the battery lib's [EnergyLedger] (#1506 / #1629 battery work) under the tag `engine.path`, so the Battery Usage Details dialog
 * and /api/energy/self see the same numbers; wakeups and bytes are the ledger's ([wake], [bytes]).
 *
 * Nothing here polls and nothing is sampled: a path that never ran has no entry, and the page says "no sample yet". Pure (the
 * clocks are seams), thread-safe, and cheap: two clock reads and one synchronized add per run.
 */
object EngineProfiler {
    const val RING = 512
    private const val LAST_ERRORS = 5

    class PathStat {
        var calls = 0L; var wallNs = 0L; var maxWallNs = 0L; var cpuNs = 0L; var bgCpuNs = 0L; var lastAtMs = 0L
        var ledgerTag = ""
        internal val ring = LongArray(RING)
        internal var ringSize = 0
        internal var ringAt = 0
    }

    /** One path's numbers, copied out. [samples] = the wall time of the last runs (ns), oldest first. */
    data class Path(
        val engine: String, val path: String, val calls: Long, val wallNs: Long, val maxWallNs: Long,
        val cpuNs: Long, val bgCpuNs: Long, val lastAtMs: Long, val samples: List<Long>,
        /** The EnergyLedger tag this path's CPU time is booked under. */
        val ledgerTag: String = "$engine.$path",
    ) {
        val tag: String get() = "$engine.$path"
        /** The [q] quantile (0..1) of the kept samples in ms, or null with no sample. */
        fun quantileMs(q: Double): Double? {
            if (samples.isEmpty()) return null
            val s = samples.sorted()
            return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] / 1e6
        }
        /** Share (0..100) of the kept samples slower than [ms], or null with no sample. */
        fun overPct(ms: Double): Double? =
            if (samples.isEmpty()) null else samples.count { it / 1e6 > ms } * 100.0 / samples.size
    }

    /** One failure: where ([engine], [where]), what, and when. */
    data class Err(val engine: String, val where: String, val what: String, val atMs: Long)

    data class Snapshot(
        val paths: List<Path>, val errorCounts: Map<String, Long>, val lastErrors: List<Err>,
        /** How long this process has been recording, and how much of that the app was NOT in the foreground. */
        val sinceMs: Long, val recordedMs: Long, val backgroundMs: Long, val records: Long,
    ) {
        fun paths(engine: String): List<Path> = paths.filter { it.engine == engine }
        fun path(engine: String, path: String): Path? = paths.firstOrNull { it.engine == engine && it.path == path }
        fun errors(engine: String): Long = errorCounts[engine] ?: 0L
        fun lastError(engine: String): Err? = lastErrors.lastOrNull { it.engine == engine }
    }

    private val stats = ConcurrentHashMap<String, PathStat>()
    private val errors = ConcurrentHashMap<String, Long>()
    private val lastErrors = ArrayDeque<Err>()

    /** Test seams: the wall clock (ns), the calling thread's CPU clock (ns; 0 when the platform has none) and the wall time (ms). */
    @Volatile var wallClock: () -> Long = { System.nanoTime() }
    @Volatile var cpuClock: () -> Long = { runCatching { android.os.Debug.threadCpuTimeNanos() }.getOrDefault(0L).coerceAtLeast(0L) }
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile private var sinceMs = nowMs()
    @Volatile private var startNs = wallClock()
    private val records = java.util.concurrent.atomic.AtomicLong()

    /** Is an activity of the app started? Set by [XrayFrameProbe]; CPU spent while false is the background (battery) share. */
    @Volatile var foreground = false
        set(v) {
            synchronized(this) {
                val now = wallClock()
                if (!field) bgNs += now - bgFrom
                bgFrom = now
                field = v
            }
        }
    private var bgNs = 0L
    private var bgFrom = wallClock()

    /**
     * Run [block] as one run of [engine]'s hot path [path]: its wall and CPU time are recorded, a throw is counted and rethrown.
     * [ledgerTag] keeps the EnergyLedger tag a path already had before #1649 ("music.session").
     */
    inline fun <T> time(engine: String, path: String, ledgerTag: String = "$engine.$path", block: () -> T): T {
        val w0 = wallClock(); val c0 = cpuClock()
        try {
            return block()
        } catch (t: Throwable) {
            if (t !is java.util.concurrent.CancellationException) error(engine, path, t)
            throw t
        } finally {
            record(engine, path, wallClock() - w0, cpuClock() - c0, ledgerTag)
        }
    }

    /** One finished run of ([engine], [path]) that took [wallNs] on the clock and [cpuNs] of CPU. A blank [ledgerTag] books no CPU in the ledger (a duration measured by the platform, like a frame). */
    fun record(engine: String, path: String, wallNs: Long, cpuNs: Long = 0L, ledgerTag: String = "$engine.$path") {
        val w = wallNs.coerceAtLeast(0L); val c = cpuNs.coerceAtLeast(0L)
        val s = stats.getOrPut("$engine.$path") { PathStat() }
        synchronized(s) {
            s.calls++; s.wallNs += w; if (w > s.maxWallNs) s.maxWallNs = w
            s.cpuNs += c; if (!foreground) s.bgCpuNs += c
            s.lastAtMs = nowMs(); s.ledgerTag = ledgerTag
            s.ring[s.ringAt] = w; s.ringAt = (s.ringAt + 1) % RING; if (s.ringSize < RING) s.ringSize++
        }
        records.incrementAndGet()
        if (ledgerTag.isNotEmpty()) EnergyLedger.addCpu(ledgerTag, c)
    }

    /** A failure of [engine] at [where] that the code handled (a caught throw, a refused call, a bad answer). */
    fun error(engine: String, where: String, t: Throwable?) {
        error(engine, where, if (t == null) "failed" else "${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
    }

    fun error(engine: String, where: String, what: String) {
        errors.merge(engine, 1L) { a, b -> a + b }
        synchronized(lastErrors) {
            lastErrors.addLast(Err(engine, where, what.take(200), nowMs()))
            if (lastErrors.count { it.engine == engine } > LAST_ERRORS) lastErrors.remove(lastErrors.first { it.engine == engine })
        }
    }

    /** [engine] pulled the process out of idle through [source] (an alarm fired, a job ran, a listener was called). */
    fun wake(engine: String, source: String) { EnergyLedger.wake("$engine.$source") }

    /** [engine] moved [n] network / IO bytes through [source]. */
    fun bytes(engine: String, source: String, n: Long) { EnergyLedger.bytes("$engine.$source", n) }

    fun snapshot(): Snapshot {
        val out = ArrayList<Path>(stats.size)
        for ((k, s) in stats) synchronized(s) {
            val samples = ArrayList<Long>(s.ringSize)
            val from = if (s.ringSize < RING) 0 else s.ringAt
            for (i in 0 until s.ringSize) samples += s.ring[(from + i) % RING]
            out += Path(k.substringBefore('.'), k.substringAfter('.'), s.calls, s.wallNs, s.maxWallNs, s.cpuNs, s.bgCpuNs, s.lastAtMs, samples, s.ledgerTag)
        }
        val now = wallClock()
        val bg = synchronized(this) { bgNs + if (!foreground) now - bgFrom else 0L }
        return Snapshot(
            out.sortedBy { it.tag }, HashMap(errors), synchronized(lastErrors) { lastErrors.toList() },
            sinceMs, (now - startNs) / 1_000_000L, bg / 1_000_000L, records.get(),
        )
    }

    /** Start a new measurement window (the page's Reset button, and the tests). */
    fun reset() {
        stats.clear(); errors.clear(); synchronized(lastErrors) { lastErrors.clear() }
        synchronized(this) { bgNs = 0L; bgFrom = wallClock() }
        sinceMs = nowMs(); startNs = wallClock(); records.set(0L)
    }
}

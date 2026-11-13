package com.diegonmarcos.superapp.fleetxray.perf

import java.io.File

/** The counters a span reads at its start and end. Injectable so a JVM test drives the profiler with a fake clock. */
public interface PerfClock {
    public fun wallNs(): Long
    /** CPU time of the calling thread in ns, or -1. */
    public fun cpuNs(): Long
    public fun gcCount(): Long
    public fun gcTimeMs(): Long
    public fun allocBytes(): Long
    public fun nowMs(): Long
}

/** Android counters: Debug.threadCpuTimeNanos and the ART runtime stats; every read that a device refuses is -1, never a crash. */
public object AndroidPerfClock : PerfClock {
    private fun stat(k: String): Long = try { android.os.Debug.getRuntimeStat(k).toLongOrNull() ?: -1L } catch (_: Throwable) { -1L }
    override fun wallNs(): Long = System.nanoTime()
    override fun cpuNs(): Long = try { android.os.Debug.threadCpuTimeNanos() } catch (_: Throwable) { -1L }
    override fun gcCount(): Long = stat("art.gc.gc-count")
    override fun gcTimeMs(): Long = stat("art.gc.gc-time")
    override fun allocBytes(): Long = stat("art.gc.bytes-allocated")
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** The open span of one operation: the code inside it reports what it processed. */
public class PerfSpan internal constructor(public val op: String) {
    @Volatile public var items: Long = 0
    @Volatile public var bytes: Long = 0
    @Volatile public var note: String = ""
    @Volatile public var failed: Boolean = false
    public fun add(items: Long = 0, bytes: Long = 0) { this.items += items; this.bytes += bytes }
}

/**
 * The fleet's performance profiler: XrayProfiler.span("op") { ... } records wall time, thread CPU time (so cpu/wall splits CPU from
 * waiting), items / bytes processed, and the GC and allocation deltas. A bounded ring per operation lives in memory and its summary
 * is persisted to one file (declared in fleet-config.json as an item of lib-fleetconfigs-xray). Idle cost: nothing runs between
 * spans; a span is six counter reads and one lock-protected insert.
 */
public object XrayProfiler {
    public const val RING_PER_OP: Int = 100
    public const val MAX_OPS: Int = 64
    public const val PERSIST_PER_OP: Int = 50
    private const val PERSIST_EVERY_MS = 15_000L
    public const val FILE_NAME: String = "xray-perf.tsv"

    @Volatile public var enabled: Boolean = true
    @Volatile internal var clock: PerfClock = AndroidPerfClock
    @Volatile private var file: File? = null
    @Volatile private var lastPersist = 0L
    private val lock = Any()
    private val rings = LinkedHashMap<String, ArrayDeque<PerfRun>>()
    private val current = ThreadLocal<ArrayList<PerfSpan>>()
    @Volatile private var outcomes: List<BenchOutcome> = emptyList()

    /** Loads the persisted ring from [dir] and keeps persisting there. Idempotent; never throws. */
    public fun init(dir: File) {
        val f = File(dir, FILE_NAME)
        if (file?.path == f.path) return
        file = f
        runCatching { if (f.isFile) XrayPerfCodec.decode(f.readText()).forEach { insert(it) } }
    }

    /** Add the work done to the innermost open span of this thread (no-op outside a span). */
    public fun count(items: Long = 0, bytes: Long = 0) { current.get()?.lastOrNull()?.add(items, bytes) }

    public fun <T> span(op: String, benchmark: Boolean = false, block: (PerfSpan) -> T): T {
        val s = PerfSpan(op)
        if (!enabled) return block(s)
        val c = clock
        val stack = current.get() ?: ArrayList<PerfSpan>().also { current.set(it) }
        stack.add(s)
        val w0 = c.wallNs(); val cpu0 = c.cpuNs(); val gc0 = c.gcCount(); val gt0 = c.gcTimeMs(); val al0 = c.allocBytes()
        try {
            return block(s)
        } catch (t: Throwable) {
            s.failed = true; if (s.note.isEmpty()) s.note = t.javaClass.simpleName + ": " + (t.message ?: "").take(120)
            throw t
        } finally {
            val w1 = c.wallNs(); val cpu1 = c.cpuNs(); val gc1 = c.gcCount(); val gt1 = c.gcTimeMs(); val al1 = c.allocBytes()
            stack.remove(s)
            record(PerfRun(op, c.nowMs(), w1 - w0, if (cpu0 >= 0 && cpu1 >= cpu0) cpu1 - cpu0 else -1,
                s.items, s.bytes, if (gc0 >= 0 && gc1 >= gc0) gc1 - gc0 else -1, if (gt0 >= 0 && gt1 >= gt0) gt1 - gt0 else -1,
                if (al0 >= 0 && al1 >= al0) al1 - al0 else -1, !s.failed, benchmark, s.note))
        }
    }

    /**
     * Suspend variant. The thread CPU time is only meaningful while the coroutine stays on the thread it started on: when it resumed
     * elsewhere the CPU is recorded as not available instead of a wrong number.
     */
    public suspend fun <T> spanSuspend(op: String, benchmark: Boolean = false, block: suspend (PerfSpan) -> T): T {
        val s = PerfSpan(op)
        if (!enabled) return block(s)
        val c = clock
        val thread = Thread.currentThread()
        val w0 = c.wallNs(); val cpu0 = c.cpuNs(); val gc0 = c.gcCount(); val gt0 = c.gcTimeMs(); val al0 = c.allocBytes()
        try {
            return block(s)
        } catch (t: Throwable) {
            s.failed = true; if (s.note.isEmpty()) s.note = t.javaClass.simpleName + ": " + (t.message ?: "").take(120)
            throw t
        } finally {
            val same = Thread.currentThread() === thread
            val w1 = c.wallNs(); val cpu1 = c.cpuNs(); val gc1 = c.gcCount(); val gt1 = c.gcTimeMs(); val al1 = c.allocBytes()
            record(PerfRun(op, c.nowMs(), w1 - w0, if (same && cpu0 >= 0 && cpu1 >= cpu0) cpu1 - cpu0 else -1,
                s.items, s.bytes, if (gc0 >= 0 && gc1 >= gc0) gc1 - gc0 else -1, if (gt0 >= 0 && gt1 >= gt0) gt1 - gt0 else -1,
                if (al0 >= 0 && al1 >= al0) al1 - al0 else -1, !s.failed, benchmark, (if (same) "" else "resumed on another thread: no cpu. ") + s.note))
        }
    }

    /** Record a run measured elsewhere (e.g. a probe thread that timed itself). */
    public fun record(r: PerfRun) {
        insert(r)
        val f = file ?: return
        val now = r.atMs
        if (now - lastPersist >= PERSIST_EVERY_MS) { lastPersist = now; persist(f) }
    }

    private fun insert(r: PerfRun) {
        synchronized(lock) {
            val ring = rings[r.op] ?: if (rings.size >= MAX_OPS) return else ArrayDeque<PerfRun>().also { rings[r.op] = it }
            ring.addLast(r)
            while (ring.size > RING_PER_OP) ring.removeFirst()
        }
    }

    /** All runs in memory, oldest first per operation. */
    public fun runs(): List<PerfRun> = synchronized(lock) { rings.values.flatMap { it.toList() } }
    public fun summaries(): List<OpSummary> = XrayPerfMath.summarizeAll(runs())

    public fun setOutcomes(o: List<BenchOutcome>) { outcomes = o }
    public fun outcomes(): List<BenchOutcome> = outcomes

    public fun flush() { file?.let { persist(it) } }

    private fun persist(f: File) {
        runCatching {
            val keep = synchronized(lock) { rings.values.flatMap { it.toList().takeLast(PERSIST_PER_OP) } }
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp"); tmp.writeText(XrayPerfCodec.encode(keep)); tmp.renameTo(f)
        }
    }

    public fun clear() {
        synchronized(lock) { rings.clear() }
        outcomes = emptyList()
        file?.let { runCatching { it.delete() } }
    }

    internal fun resetForTest(c: PerfClock) { synchronized(lock) { rings.clear() }; clock = c; file = null; lastPersist = 0; outcomes = emptyList(); enabled = true }
}

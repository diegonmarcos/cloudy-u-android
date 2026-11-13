package com.diegonmarcos.superapp.fleetxray.perf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One declared benchmark of an app. [run] executes the app's real hot path (which records its own span named [op]) and returns null,
 * or the reason it was skipped (a missing grant, nothing to measure). Benchmarks are read-only and bounded: [maxMs] is the hard
 * time limit for all [repeats] together.
 */
public class XrayBenchmark(
    public val op: String,
    public val title: String,
    public val safety: String,
    public val maxMs: Long,
    public val repeats: Int = 3,
    public val run: suspend (Context) -> String?,
)

/** The registry of the app's benchmarks and the runner behind the page's Run benchmark button. */
public object XrayBenchmarks {
    public const val MAX_MS_CAP: Long = 60_000L
    private val lock = Any()
    private val declared = LinkedHashMap<String, XrayBenchmark>()

    /** Register (or replace by op) an app benchmark. Called from the app's startup. */
    public fun register(vararg b: XrayBenchmark) { synchronized(lock) { b.forEach { declared[it.op] = it } } }
    public fun declared(): List<XrayBenchmark> = synchronized(lock) { declared.values.toList() }
    internal fun clearForTest() { synchronized(lock) { declared.clear() } }

    /** Runs every declared benchmark now, one after the other, off the main thread; never throws. [onProgress] gets the title being run. */
    public suspend fun runAll(ctx: Context, onProgress: (String) -> Unit = {}): List<BenchOutcome> {
        val out = ArrayList<BenchOutcome>()
        for (b in declared()) {
            onProgress(b.title)
            val t0 = System.nanoTime()
            var state = "ran"; var detail = ""
            val limit = b.maxMs.coerceIn(1L, MAX_MS_CAP)
            val finished = runCatching {
                withTimeoutOrNull(limit) {
                    withContext(Dispatchers.IO) {
                        var n = 0
                        while (n < b.repeats.coerceAtLeast(1)) {
                            val skip = b.run(ctx)
                            if (skip != null) { state = "skipped"; detail = skip; break }
                            n++
                            if ((System.nanoTime() - t0) / 1_000_000 > limit) break
                        }
                        detail = detail.ifEmpty { "$n run(s)" }
                    }
                    true
                }
            }
            when {
                finished.isFailure -> { state = "failed"; detail = finished.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + (it.message ?: "").take(120) } ?: "" }
                finished.getOrNull() == null -> { state = "timeout"; detail = "stopped after ${limit} ms" }
            }
            out += BenchOutcome(b.op, b.title, state, detail, (System.nanoTime() - t0) / 1_000_000)
        }
        XrayProfiler.setOutcomes(out)
        XrayProfiler.flush()
        return out
    }
}

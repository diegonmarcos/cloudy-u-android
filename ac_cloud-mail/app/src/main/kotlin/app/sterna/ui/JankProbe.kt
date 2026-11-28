package app.sterna.ui

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics

/**
 * #1952 scroll jank, measured: per-frame stage times from the window's frame metrics (JankStats' source), and named sections
 * timed on the main thread (also tagged for Perfetto with [Trace]). Once a second with frames in it, one logcat line (tag
 * `JankProbe`): frames, frames over [SLOW_MS], the p90 total, the mean of every stage over the slow frames, and every section
 * with its count, sum and max. The walk reads these lines during its scroll step and fails above its limits.
 *  - stages: input, anim, layout (measure+layout), draw (record), sync, cmd (command issue), swap (swap buffers, where a software
 *    GPU shows); the first five run on the main thread or its render hand-off, the last two on the render thread / GPU.
 *  - sections: `row.compose` (one list row's composition), `row.date`, `row.attach`, `avatar.decode.main` / `.bg`.
 */
object JankProbe {
    const val TAG = "JankProbe"
    const val SLOW_MS = 48L

    private class Acc { var n = 0; var sum = 0L; var max = 0L }
    private val sections = HashMap<String, Acc>()
    private val frames = ArrayList<LongArray>() // total, input, anim, layout, draw, sync, cmd, swap (ns)
    private var windowStart = 0L
    private var thread: HandlerThread? = null

    inline fun <T> section(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
            add(name, System.nanoTime() - t0)
        }
    }

    /** For code that cannot sit in a try block (composable calls): [begin] then [end] with what it returned. */
    fun begin(name: String): Long { Trace.beginSection(name); return System.nanoTime() }

    private val marks = HashMap<String, Long>()

    /** Two points in different functions: [mark] where the span starts, [markEnd] where it ends (main thread only). */
    fun mark(name: String) { marks[name] = System.nanoTime() }

    fun markEnd(name: String) { marks.remove(name)?.let { add(name, System.nanoTime() - it) } }

    fun end(name: String, started: Long) { Trace.endSection(); add(name, System.nanoTime() - started) }

    @PublishedApi @Synchronized internal fun add(name: String, ns: Long) {
        val a = sections.getOrPut(name) { Acc() }
        a.n++; a.sum += ns; if (ns > a.max) a.max = ns
    }

    fun attach(activity: Activity) {
        if (thread != null) return
        val t = HandlerThread("JankProbe").also { it.start() }
        thread = t
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
                record(longArrayOf(
                    m.getMetric(FrameMetrics.TOTAL_DURATION), m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                    m.getMetric(FrameMetrics.ANIMATION_DURATION), m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    m.getMetric(FrameMetrics.DRAW_DURATION), m.getMetric(FrameMetrics.SYNC_DURATION),
                    m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                ))
            }, Handler(t.looper))
        }
    }

    @Synchronized private fun record(f: LongArray) {
        val now = System.nanoTime()
        if (windowStart == 0L) windowStart = now
        frames.add(f)
        if (now - windowStart < 1_000_000_000L) return
        flush()
        windowStart = now
    }

    private fun flush() {
        val ms = { ns: Long -> ns / 1_000_000 }
        val totals = frames.map { it[0] }.sorted()
        val slow = frames.filter { ms(it[0]) > SLOW_MS }
        fun mean(i: Int) = if (slow.isEmpty()) 0 else ms(slow.sumOf { it[i] } / slow.size)
        val line = StringBuilder("window frames=${frames.size} slow=${slow.size} total-p90=${ms(totals[(totals.size * 9 / 10).coerceAtMost(totals.size - 1)])}")
        line.append(" worst=${ms(totals.last())} slow-mean(input=${mean(1)} anim=${mean(2)} layout=${mean(3)} draw=${mean(4)} sync=${mean(5)} cmd=${mean(6)} swap=${mean(7)})")
        for ((name, a) in sections) line.append(" | $name n=${a.n} sum=${ms(a.sum)} max=${ms(a.max)}")
        sections.clear(); frames.clear()
        try { Log.i(TAG, line.toString()) } catch (_: RuntimeException) {}
    }
}

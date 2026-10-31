package com.diegonmarcos.superapp.core

import android.content.Context

/**
 * THE ONE GATE for holding a `dataSync` foreground service, in every fleet app.
 *
 * Android 15+ caps `dataSync` foreground services at 6 hours per rolling 24 hours per app.
 * At the cap the system calls `Service.onTimeout(int, int)`; a service that has not stopped a few
 * seconds later takes the whole process down with
 * `ForegroundServiceDidNotStopInTimeException` (Cloud Store, WorkManager's SystemForegroundService,
 * SM-A376B / Android 16). The periodic Store passes (ConstellationWorker, UpdateWorker) and
 * Gallery's 30-minute backup each called `setForeground(dataSync)` on every unattended run, so a
 * phone spent the whole budget on background passes nobody was watching.
 *
 * The rule this object enforces:
 *  - only USER-INITIATED work (a tap, an open Store screen, a local API call) may hold the service;
 *    unattended / periodic runs are plain background work with constraints, resumable on the next run;
 *  - and only while this app has used less than [BUDGET_MS] of `dataSync` time in the last
 *    [WINDOW_MS] — headroom under the system's 6 h, so the timeout is never the thing that stops us.
 *
 * Every hold is recorded with [begin] / [end] (a token pair); the guard
 * `1_cicd/src/scripts/cloud-android-fgs-timeout-guard.py` fails any worker or service that enters
 * a `dataSync` foreground without going through [mayHold]. WorkManager >= 2.10 stops the worker on
 * `onTimeout` (the fleet pins 2.12.0); the workers poll `isStopped` so that stop lands at once.
 */
object DataSyncBudget {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    /** The system cap (Android 15+). */
    const val SYSTEM_CAP_MS = 6L * 60 * 60 * 1000
    /** Ours: two hours of headroom under the cap. */
    const val BUDGET_MS = 4L * 60 * 60 * 1000
    /** An open span whose [end] never came (the process died) counts for at most this long. */
    const val STALE_OPEN_MS = 2L * 60 * 60 * 1000

    private const val PREFS = "fleet_datasync_budget"
    private const val KEY = "spans"
    private val lock = Any()

    /** May this run enter a `dataSync` foreground service? Unattended work never may. */
    fun mayHold(ctx: Context, userInitiated: Boolean): Boolean =
        userInitiated && usedMs(ctx) < BUDGET_MS

    /** Records the start of a hold; pass the token to [end]. */
    fun begin(ctx: Context): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        write(ctx, read(ctx) + Span(now, OPEN))
        now
    }

    /** Closes the hold [begin] opened. Token 0 (nothing held) is a no-op. */
    fun end(ctx: Context, token: Long) {
        if (token <= 0L) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var closed = false
            write(ctx, read(ctx).map {
                if (!closed && it.start == token && it.end == OPEN) { closed = true; Span(it.start, now) } else it
            })
        }
    }

    /** `dataSync` time used in the last [WINDOW_MS]. */
    fun usedMs(ctx: Context, now: Long = System.currentTimeMillis()): Long =
        used(synchronized(lock) { read(ctx) }, now)

    /** Pure: the time [spans] cover inside the window ending at [now]. */
    fun used(spans: List<Span>, now: Long): Long {
        val from = now - WINDOW_MS
        return spans.sumOf { s ->
            val end = if (s.end == OPEN) minOf(now, s.start + STALE_OPEN_MS) else s.end
            (minOf(end, now) - maxOf(s.start, from)).coerceAtLeast(0L)
        }
    }

    data class Span(val start: Long, val end: Long)
    const val OPEN = -1L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context): List<Span> =
        (prefs(ctx).getString(KEY, "") ?: "").split(';').mapNotNull { e ->
            val p = e.split(':'); if (p.size != 2) null
            else p[0].toLongOrNull()?.let { s -> p[1].toLongOrNull()?.let { Span(s, it) } }
        }

    private fun write(ctx: Context, spans: List<Span>) {
        val now = System.currentTimeMillis()
        val keep = spans.filter { (if (it.end == OPEN) it.start + STALE_OPEN_MS else it.end) > now - WINDOW_MS }
        prefs(ctx).edit().putString(KEY, keep.joinToString(";") { "${it.start}:${it.end}" }).apply()
    }
}

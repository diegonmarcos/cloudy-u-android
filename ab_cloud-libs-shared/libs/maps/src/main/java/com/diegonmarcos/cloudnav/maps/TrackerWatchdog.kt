package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic watchdog that re-spawns [LocationTrackerService] when
 * Android (or Samsung's aggressive "Put apps to sleep" / "Deep sleep"
 * killer) takes the foreground service down — even with Battery
 * Optimization whitelisted.
 *
 * Why this exists: Battery Optimization controls Doze + standard
 * background restrictions. It does NOT cover Samsung's "Background
 * usage limits" (Sleeping apps / Deep-sleeping apps) which kills
 * foreground services independently. It also doesn't survive memory
 * pressure / low-memory killer or app updates. The rehydrate path in
 * LocationTrackerService.onCreate makes a revival smooth, but
 * something has to actually trigger the revival.
 *
 * The Android-canonical answer is a [PeriodicWorkRequest] under
 * WorkManager: WorkManager wraps JobScheduler, which the OS keeps
 * scheduled across process death AND across reboots. The minimum
 * periodic interval is 15 minutes — worst-case detection of a dead
 * tracker is therefore 15 min, which is acceptable given the
 * alternative (service stays dead overnight, hours of GPS data lost).
 *
 * The watchdog only does work when the user actually wants tracking
 * on (MapsTrackerPrefs.enabled = true). On the disabled path it just
 * exits, leaving WorkManager to schedule the next tick at minimal
 * cost.
 */
class TrackerWatchdog(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /*
     * #1666 NO KEEP-ALIVE. The tracker is a location foreground service and must start only from the user's Start; this worker
     * re-spawning it every 15 min (and across reboots) kept the location indicator lit with nobody asking. A schedule left by an
     * older build still fires once: it now only records that the tracker died (tracking switched off, the Maps page shows
     * Start again) and cancels itself. It never starts the service.
     */
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = MapsTrackerPrefs(ctx)
        if (prefs.enabled && decide(prefs.lastFixTs, System.currentTimeMillis(),
                MapsTrackingPrefs(ctx).intervalMovingMs.coerceAtLeast(1_000).toLong()) == Verdict.DEAD) {
            Log.w("TrackerWatchdog", "Tracker enabled but dead — NOT restarted (#1666); tracking switched off")
            prefs.enabled = false
        }
        cancel(ctx)
        return Result.success()
    }

    /** What the old watchdog would have done; kept as data so the decision stays visible and tested. */
    enum class Verdict { ALIVE, DEAD }

    companion object {
        private const val WORK_NAME = "tracker-watchdog"

        /** Threshold = 4x the moving interval, as the old watchdog judged a dead tracker. Pure. */
        fun decide(lastFixTs: Long, now: Long, intervalMs: Long): Verdict =
            if (lastFixTs <= 0L || (now - lastFixTs) > intervalMs * 4) Verdict.DEAD else Verdict.ALIVE

        /** Enqueue the periodic watchdog. Idempotent (KEEP policy) —
         *  re-calling won't disturb the existing schedule. Cadence =
         *  15 min (Android's minimum for periodic work). No network
         *  constraint so the watchdog runs in airplane mode. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<TrackerWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /** Cancel the periodic schedule. Called by
         *  LocationTrackerService.stopTracking so a deliberate Stop
         *  doesn't keep the worker firing into a disabled service.
         *  WorkManager handles in-flight workers gracefully — a
         *  currently-running doWork finishes, then no further ticks. */
        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}

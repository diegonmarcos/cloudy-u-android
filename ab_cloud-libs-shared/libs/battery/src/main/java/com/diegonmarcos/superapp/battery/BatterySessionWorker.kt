package com.diegonmarcos.superapp.battery
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The battery SoT's low-frequency tick: every 15 minutes (the
 * PeriodicWorkRequest floor) it records one battery sample
 * ([BatteryRepository.record]) and an energy-watchdog sample, so the
 * history, "since last charge" and the cycle tables stay right while the
 * SuperApp is backgrounded or killed and no battery surface is open. It also
 * folds the month into the data-usage ledger. No constraints on purpose:
 * requiring charging or battery-not-low would skip ticks exactly when the
 * discharge history matters. The work is a few milliseconds, no wakelock.
 */
class BatterySessionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        EnergyLedger.wake("bg.battery_worker")
        // The battery SoT's low-frequency tick: one history sample even when nothing else runs.
        runCatching { BatteryRepository.record(applicationContext) }
        // The per-app charge-cycle tracker: one batterystats snapshot per tick keeps the open cycle's diff
        // current across batterystats' full-charge reset and reboots (BatteryCycles).
        runCatching { BatteryRepository.snapshotCycle(applicationContext) }
        // Piggyback the energy watchdog on the same 15-min wakeup — one
        // coarse background sample per tick (cheap; the screen-on
        // foreground sampler adds the fine resolution).
        runCatching { EnergyWatchdog.sample(applicationContext) }
        // Second piggyback, same rationale: fold the current month into the
        // permanent per-month data-usage ledger. NetworkStatsManager only
        // retains a ~90-day rolling window, so a month never folded in
        // before it ages out is lost for good -- and that must not depend on
        // the user opening the Data Usage screen. The fold is idempotent and
        // only writes when the OS actually returns bytes, so steady-state
        // cost is one cheap query per tick. This is the ONLY reason
        // :libs:battery depends on :libs:datamanager -- reusing this tick
        // instead of adding a second periodic worker for a job that does
        // meaningful work once a month.
        runCatching {
            com.diegonmarcos.superapp.datamanager.DataUsageHistoryStore.refresh(applicationContext)
        }
        return Result.success()
    }

    companion object {
        /** Unique work name — KEEP policy keys on this so calling
         *  schedule() many times in a row doesn't restart the
         *  cadence. */
        const val UNIQUE_NAME = "battery_session_tick"

        /** Idempotent — call from App.onCreate. Reschedules ONLY when
         *  no existing periodic work with this name is already
         *  enqueued (ExistingPeriodicWorkPolicy.KEEP). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BatterySessionWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(Constraints.NONE).build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

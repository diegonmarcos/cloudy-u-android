package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.clouddrive.sync.SyncSchedule
import com.diegonmarcos.clouddrive.sync.UnisonSyncer
import java.util.concurrent.TimeUnit

/**
 * #1084 the scheduler of the Unison pairs, on the SAME WorkManager the git sync uses. ONE periodic request
 * ticks at the base period (WorkManager's 15-minute floor); on each tick every enabled pair that declares
 * `schedule_minutes` is checked by [SyncSchedule.isDue] against its OWN period, its last run and its
 * network rule (`require_unmetered`), and the due ones are synced one after another through [UnisonSyncer],
 * the same code the Sync now button runs. A pair with conflicts is synced like any other: its conflicts
 * stay listed (Unison skips them again, it never overwrites) until the user decides.
 *
 * The period and the pairs are the declaration's, applied with UPDATE (not KEEP) for the reason
 * [GitSyncWorker.schedule] gives.
 */
class UnisonSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val syncer = UnisonSyncer(applicationContext)
        val now = System.currentTimeMillis() / 1000
        val unmetered = GitSyncWorker.networkUnmetered(applicationContext)
        for (pair in syncer.declared.pairs) {
            if (isStopped) return Result.retry()
            if (pair.scheduleMinutes == null) continue
            // the user's switch on the Sync tab wins over the declaration's `enabled`
            if (!DrivePrefs(applicationContext).ruleEnabled(RULE_PREFIX + pair.id, pair.enabled)) continue
            val last = syncer.store.get(pair.id)?.lastRunEpochSeconds ?: 0L
            val due = SyncSchedule.isDue(
                autoSync = true, repoIntervalMinutes = pair.scheduleMinutes!!.toLong(), baseIntervalMinutes = BASE_MINUTES,
                lastSyncEpochSeconds = last, nowEpochSeconds = now, requireUnmetered = pair.requireUnmetered, networkUnmetered = unmetered,
            )
            if (!due) continue
            val result = syncer.sync(pair, UnisonSyncer.TRIGGER_SCHEDULED)
            Log.i(TAG, "${pair.id}: ${result.headline()}")
        }
        // Conflicts and failures are the user's to look at (the card says so); a retry storm fixes neither.
        return Result.success()
    }

    companion object {
        private const val TAG = "UnisonSyncWorker"
        const val WORK_NAME = "cloud-drive-unison-sync"
        /** WorkManager's floor, and the base tick: build.json's storage.git_sync period is the git worker's own. */
        const val BASE_MINUTES = 15L
        /** DrivePrefs.ruleEnabled key prefix for a pair, beside the rclone rules' own keys. */
        const val RULE_PREFIX = "unison-"

        /** Enqueue (or re-apply) the periodic tick. Only when some pair declares a schedule (the user's per-pair switch is read on each tick); otherwise any earlier tick is cancelled. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (com.diegonmarcos.clouddrive.storage.UnisonPairStore(context).declared().pairs.none { it.scheduleMinutes != null }) { wm.cancelUniqueWork(WORK_NAME); return }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<UnisonSyncWorker>(BASE_MINUTES, TimeUnit.MINUTES).setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

package com.diegonmarcos.superapp.fleetdata.provider

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The battery rules: the periodic sync runs on an UNMETERED network with the battery not low, no polling loop and no foreground service; a
 * failed run is retried with a 30 s backoff floor. "Sync now" (the user's tap) needs any network. Each provider app schedules its own.
 */
object FleetDataSchedule {
    private fun name(authority: String) = "fleetdata-$authority"

    fun ensure(ctx: Context, authority: String) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<FleetDataWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString("authority", authority).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(name(authority), ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    fun syncNow(ctx: Context, authority: String) {
        runCatching {
            val req = OneTimeWorkRequestBuilder<FleetDataWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString("authority", authority).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(name(authority) + "-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class FleetDataWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val authority = inputData.getString("authority") ?: return Result.failure()
        val r = FleetDataEndpoint.of(applicationContext, authority).runSync() ?: return Result.success()
        // Every request failed and nothing changed: the network was the problem, retry (WorkManager's backoff, 30 s floor).
        val allFailed = !r.throttled && r.updated.isEmpty() && r.notModified.isEmpty() && r.failed.isNotEmpty()
        return if (allFailed && runAttemptCount < 5) Result.retry() else Result.success()
    }
}

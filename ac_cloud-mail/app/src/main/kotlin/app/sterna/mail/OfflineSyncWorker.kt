package app.sterna.mail

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.FullSyncSchedule
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background full sync (#1768): keeps every message inside the "Keep mail offline for" window downloaded, so opening the
 * app needs no fetch. Per JMAP account [app.sterna.core.data.mail.MailRepository.fullSync] does the incremental header
 * sync (Email/changes), the older header chunks to the window's edge, the full bodies of EVERY folder and, by the
 * "Download attachments" policy, the attachments, all inside the storage cap and under the battery-saver / metered
 * throttle of #1763. IMAP accounts keep the earlier offline fill ([app.sterna.core.data.mail.MailRepository.syncOffline]).
 *
 * Runs: periodically (every 15 minutes, or every few hours while a live push connection triggers it on each state
 * change), on connectivity regain, on a push state change, right after an app update, on app open and when a setting
 * changes. A failed pass returns retry, so WorkManager backs off exponentially from 30 s.
 */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val settings = container.settingsRepository
        val window = settings.offlineWindow.first()
        val limit = settings.offlineAttachmentLimit.first()
        val policy = settings.attachmentPolicy.first()
        var failed = false
        var pending = false
        for (credentials in container.accountStore.allCredentials()) {
            if (credentials.protocol == MailProtocol.IMAP) {
                runCatching { container.mailRepository.syncOffline(credentials, window, limit) }
                    .onSuccess { Log.i(TAG, "offline sync ${credentials.id}: fetched=${it.fetched} evicted=${it.evicted}") }
                    .onFailure { failed = true; Log.w(TAG, "offline sync failed for ${credentials.id}", it) }
                continue
            }
            runCatching {
                container.mailRepository.fullSync(
                    credentials, window, limit, policy,
                    conditions = { DevicePrefetchConditions.read(applicationContext) },
                    stopRequested = { isStopped },
                )
            }
                .onSuccess {
                    Log.i(
                        TAG,
                        "full sync ${credentials.id}: folders=${it.folders} headerPages=${it.headerPages} bodies=${it.bodies} " +
                            "inline=${it.inlineImages} attachments=${it.attachments} evicted=${it.evicted} errors=${it.errors} stop=${it.stop} pending=${it.morePending}",
                    )
                    if (it.errors > 0) failed = true
                    if (it.morePending) pending = true
                }
                .onFailure { failed = true; Log.w(TAG, "full sync failed for ${credentials.id}", it) }
        }
        if (failed) {
            // Exponential backoff (BackoffPolicy.EXPONENTIAL from BACKOFF_SECONDS); a cycle that keeps failing is given up
            // and the next period tries again.
            return if (runAttemptCount + 1 >= FullSyncSchedule.MAX_ATTEMPTS) Result.success() else Result.retry()
        }
        settings.setOfflineLastSync(System.currentTimeMillis())
        // The window is not complete yet (budget of one run spent): a continuation picks up where this one stopped.
        if (pending && !isStopped) continueSoon(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "OfflineSyncWorker"
        private const val PERIODIC = "offline-sync-periodic"
        private const val NOW = "offline-sync-now"
        private const val CONTINUE = "offline-sync-continue"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Expedited work accepts the network constraint only (WorkManager rejects battery-not-low on it). */
        private fun expeditedConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Idempotent periodic schedule: every 15 minutes without a live push connection, every few hours with one (the push
         * state change then triggers [runNow]). Updated in place when [hasPush] changes.
         */
        fun ensureScheduled(context: Context, hasPush: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(FullSyncSchedule.periodMinutes(hasPush), TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FullSyncSchedule.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * One run as soon as there is a network: app open, connectivity regain, push state change, app update, setting change.
         * #1869 expedited on Android 12+ (out of quota it runs as normal work), so the window starts warming at once. An
         * expedited request may carry the network constraint only; battery saver still ends the run at each step of the
         * engine ([app.sterna.core.data.mail.FullSync]).
         */
        fun runNow(context: Context) {
            val builder = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(expeditedConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FullSyncSchedule.BACKOFF_SECONDS, TimeUnit.SECONDS)
            // Below Android 12 expedited work runs as a foreground service, which needs getForegroundInfo(); the
            // CoroutineWorker default throws "Not implemented" (the PushFetchWorker rule).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, builder.build())
        }

        /** The next chunk of an unfinished window, queued by a run that spent its budget. */
        private fun continueSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FullSyncSchedule.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

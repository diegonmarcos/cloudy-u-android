package app.sterna.mail

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.account.MailProtocol
import java.util.concurrent.TimeUnit

/**
 * #1837 phase 1: the background driver of the Rust mail core (libs:mail-core-rs). For every JMAP account one core pass
 * ([app.sterna.core.data.mail.MailRepository.syncMailCore]): the whole account mirrored into the core's store (the first
 * pass walks every mailbox newest first and keeps every finished page; later passes follow Mailbox/changes + Email/changes,
 * re-query gaps, apply deletes and moves, prefetch the newest bodies), then the Inbox's list rows brought to the store. A
 * walk longer than one pass's budget is continued by a follow-up run, so a large mailbox completes in the background.
 *
 * IMAP accounts are not touched here: phase 1 keeps them on the old path (OfflineSyncWorker and the refresh).
 *
 * Runs periodically (every 15 minutes, on a network) and on app open (MainActivity.onStart); a failed pass retries with
 * exponential backoff.
 */
class MailCoreSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        var failed = false
        var pending = false
        for (credentials in container.accountStore.allCredentials()) {
            if (credentials.protocol == MailProtocol.IMAP) continue
            if (isStopped) break
            runCatching { container.mailRepository.syncMailCore(credentials) }
                .onSuccess { summary ->
                    if (summary == null) return@onSuccess
                    Log.i(TAG, "core pass ${credentials.id}: $summary")
                    if (summary.contains("complete=false")) pending = true
                }
                .onFailure { failed = true; Log.w(TAG, "core pass failed for ${credentials.id}", it) }
        }
        if (failed) return if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.success() else Result.retry()
        if (pending && !isStopped) continueSoon(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "MailCoreSyncWorker"
        private const val PERIODIC = "mail-core-sync-periodic"
        private const val NOW = "mail-core-sync-now"
        private const val CONTINUE = "mail-core-sync-continue"
        private const val PERIOD_MINUTES = 15L
        private const val BACKOFF_SECONDS = 30L
        private const val MAX_ATTEMPTS = 5

        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent periodic schedule (Application.onCreate). */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailCoreSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One pass as soon as there is a network: app open. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<MailCoreSyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** The rest of a walk a pass's budget did not finish. */
        private fun continueSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<MailCoreSyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

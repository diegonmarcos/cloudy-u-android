package com.diegonmarcos.clouddrive.agents

import android.content.Context
import android.provider.MediaStore
import com.diegonmarcos.clouddrive.DrivePrefs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screenshot OCR's schedule (battery rules #1487/#1502: event-driven, no polling, no periodic tick).
 *
 *  - WATCH: a one-off request with a MediaStore content-URI trigger, so a new screenshot is read
 *    a few seconds after it is taken. Content triggers fire once, so every pass re-arms the watch
 *    (APPEND_OR_REPLACE: it waits for the pass that armed it, then watches again). Zero cost idle.
 *  - BACKFILL: ONE one-shot that waits for the charger ([Constraints.Builder.setRequiresCharging])
 *    and reads whatever is still unread (a first install, a dropped watch). Never periodic.
 *  - NOW: the page's Process all / Retry failed, an explicit tap.
 *
 * Retries: the passes return success (a missing engine or grant is shown in the agent row, not
 * retried in a storm); the backoff floor is [BACKOFF_SECONDS] regardless, never below 30 s.
 * Off ([ScreenshotAgentPrefs.enabled] false) cancels all of it; a pass already running finishes
 * the screenshot it is on.
 */
class ScreenshotAgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!ScreenshotAgentPrefs.enabled(ctx) && !inputData.getBoolean(KEY_MANUAL, false)) return@withContext Result.success()
        val s = ScreenshotAgent.runPass(ctx, shouldStop = { isStopped }, retryFailed = inputData.getBoolean(KEY_RETRY_FAILED, false))
        ScreenshotAgentPrefs.recordRun(ctx, s)
        // Only the watch re-arms itself (a content trigger fires once); any other pass keeps the armed one.
        if (ScreenshotAgentPrefs.enabled(ctx)) watch(ctx, if (WATCH in tags) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP)
        // A missing engine or storage grant is shown on the page, not retried in a storm: the
        // watch and the periodic tick try again on their own.
        Result.success()
    }

    companion object {
        const val WATCH = "cloud-drive-agent-screenshots-watch"
        /** Retired periodic tick: kept only so an upgrade cancels the old work. */
        private const val PERIODIC_RETIRED = "cloud-drive-agent-screenshots-periodic"
        const val BACKFILL = "cloud-drive-agent-screenshots-backfill"
        const val NOW = "cloud-drive-agent-screenshots-now"
        const val BACKOFF_SECONDS = 30L
        private const val KEY_MANUAL = "manual"
        private const val KEY_RETRY_FAILED = "retry_failed"

        /** Arm (or re-apply) the watch and the one charging backfill; called on app start and when the page turns the agent on. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!ScreenshotAgentPrefs.enabled(context)) { cancel(context); return }
            wm.cancelUniqueWork(PERIODIC_RETIRED)
            watch(context, ExistingWorkPolicy.KEEP)
            wm.enqueueUniqueWork(BACKFILL, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScreenshotAgentWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS).build())
        }

        private fun watch(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WATCH, policy, OneTimeWorkRequestBuilder<ScreenshotAgentWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS).addTag(WATCH).build())
        }

        /** Run a pass now (the page's Run now; [retryFailed] reads every failed screenshot again). */
        fun runNow(context: Context, retryFailed: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<ScreenshotAgentWorker>().setInputData(workDataOf(KEY_MANUAL to true, KEY_RETRY_FAILED to retryFailed)).setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        /** Stop the running pass and every scheduled one. */
        fun cancel(context: Context) {
            ScreenshotAgent.requestStop()
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WATCH); wm.cancelUniqueWork(BACKFILL); wm.cancelUniqueWork(PERIODIC_RETIRED); wm.cancelUniqueWork(NOW)
        }
    }
}

/** The agent's on/off switch and its last-run line (the record files are the contract; these are not). */
object ScreenshotAgentPrefs {
    /** The app's one prefs file (declared in fleet-config.json); the agent's keys sit under agents.screenshots.* */
    private const val PREFS = "cloud-drive-prefs"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** DrivePrefs owns the switch (cloud-drive-prefs ▸ agents.screenshots.enabled, on by default). */
    fun enabled(ctx: Context): Boolean = DrivePrefs(ctx).screenshotAgentEnabled()

    fun setEnabled(ctx: Context, on: Boolean) {
        DrivePrefs(ctx).setScreenshotAgentEnabled(on)
        if (on) ScreenshotAgentWorker.schedule(ctx) else ScreenshotAgentWorker.cancel(ctx)
    }

    fun recordRun(ctx: Context, s: ScreenshotAgent.Status) {
        p(ctx).edit().putLong("agents.screenshots.last_run_ms", System.currentTimeMillis()).putInt("agents.screenshots.last_done", s.done).putInt("agents.screenshots.last_failed", s.failed)
            .putString("agents.screenshots.last_message", s.message).apply()
    }

    data class LastRun(val atMs: Long, val done: Int, val failed: Int, val message: String?)

    fun lastRun(ctx: Context): LastRun? = p(ctx).takeIf { it.contains("agents.screenshots.last_run_ms") }?.let {
        LastRun(it.getLong("agents.screenshots.last_run_ms", 0), it.getInt("agents.screenshots.last_done", 0), it.getInt("agents.screenshots.last_failed", 0), it.getString("agents.screenshots.last_message", null))
    }
}

package com.diegonmarcos.cloudnav.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudnav.maps.takeout.ImportController
import com.diegonmarcos.cloudnav.maps.takeout.ImportState
import com.diegonmarcos.superapp.core.DataSyncBudget

/**
 * #1853 the Takeout import as a foreground service (type dataSync) so Android does not kill a long import: an ongoing progress
 * notification with a Cancel action, one import at a time ([ImportController] owns the state and the engine; this owns the process
 * lifetime). Cancel stops at the next record and keeps what was read. Resume = import the same export again (the keys are stable,
 * so nothing is duplicated).
 *
 * dataSync rules (1_cicd fgs-timeout guard, W4): the hold goes through [DataSyncBudget] (user-initiated: a picked file), and
 * [onTimeout] (Android 15+, 6 h per 24 h) cancels the import, drops the foreground at once and says so; the import is resumed by
 * importing again. A budget that is spent, or a start the system refuses, falls back to [ImportController.start]'s plain thread.
 */
class TakeoutImportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var token = 0L
    private var worker: Thread? = null
    private var timedOut = false
    private val tick = object : Runnable { override fun run() { post(build()); main.postDelayed(this, 1_000) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); running = true; channel(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { ImportController.cancel(); return START_NOT_STICKY }
        val uri = intent?.data
        if (uri == null || worker != null) { if (worker == null) stopSelf(); return START_NOT_STICKY }
        // startForeground first: a foreground-service start must reach it within seconds.
        runCatching {
            ServiceCompat.startForeground(this, NOTE_ID, build(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { ImportController.startClaimed(applicationContext, uri) { finished?.invoke() }; stopSelf(); return START_NOT_STICKY }
        token = DataSyncBudget.begin(this)
        started++
        main.postDelayed(tick, 1_000)
        val app = applicationContext
        worker = Thread({
            val end = runCatching { ImportController.runSync(app, uri) }.getOrNull()
            main.post { finish(end) }
        }, "takeout-import").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    /** Android 15+: the dataSync time budget ran out. Stop the import at its next record (the rows are kept), leave the foreground now. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        ImportController.cancel()
        main.removeCallbacks(tick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        post(done("Paused by Android's time limit. Import the same export again to continue."))
        DataSyncBudget.end(this, token); token = 0L
        stopSelf()
    }

    private fun finish(end: ImportState?) {
        main.removeCallbacks(tick)
        if (!timedOut) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            post(done(when (end) {
                is ImportState.Done -> if (end.summary.cancelled) "Cancelled. Import the same export again to continue." else if (end.summary.found) "Done: ${end.summary.days} days, ${end.summary.visits} visits." else "No location history found."
                is ImportState.Failed -> "Failed: ${end.message}"
                else -> "Stopped."
            }))
        }
        worker = null
        finished?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        DataSyncBudget.end(this, token); token = 0L
        running = false
        super.onDestroy()
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Cloud Nav: location history import").setOnlyAlertOnce(true).setSilent(true)

    private fun done(text: String): Notification = base().setContentText(text).setOngoing(false).setAutoCancel(true).build()

    private fun build(): Notification {
        val s = ImportController.state
        val b = base().setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Cancel", PendingIntent.getService(this, 0, Intent(this, TakeoutImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val p = (s as? ImportState.Running)?.progress
        if (s is ImportState.Running && s.phase == "rebuilding") b.setContentText("Rebuilding days, trips, places, cities and world").setProgress(0, 0, true)
        else if (p != null) b.setContentText("${(p.fraction * 100).toInt()} %  ·  ${p.filesDone} files  ·  ${p.fixes} positions  ·  ${p.visits} visits").setProgress(1000, (p.fraction * 1000).toInt(), false)
        else b.setContentText("Reading the export").setProgress(0, 0, true)
        return b.build()
    }

    /** The one place this service posts (the foreground line and its result line share the id). */
    private fun post(n: Notification) { runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, n) } }

    companion object {
        const val CHANNEL = "takeout_import"
        const val NOTE_ID = 0x7A15
        const val ACTION_CANCEL = "com.diegonmarcos.cloudnav.TAKEOUT_IMPORT_CANCEL"
        /** True while the service is alive; [started] counts the imports it has taken (the emulator test asserts the service path with them). */
        @Volatile var running = false
        @Volatile var started = 0
        /** Called on the main thread when an import the service ran ends; Day reloads its views there. */
        @Volatile var finished: (() -> Unit)? = null

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Location history import", NotificationManager.IMPORTANCE_LOW))
        }

        /**
         * Runs the import of [uri] in the foreground service. False when one is already running. Falls back to the plain thread when
         * the dataSync budget is spent or the system refuses the foreground start (the import still runs, just unprotected).
         */
        fun start(ctx: Context, uri: Uri, onFinished: () -> Unit): Boolean {
            val app = ctx.applicationContext
            finished = onFinished
            if (!DataSyncBudget.mayHold(app, userInitiated = true)) return ImportController.start(app, uri) { onFinished() }
            if (!ImportController.claim(uri)) return false
            val i = Intent(app, TakeoutImportService::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return runCatching { ContextCompat.startForegroundService(app, i); true }.getOrElse { ImportController.startClaimed(app, uri) { onFinished() }; true }
        }
    }
}

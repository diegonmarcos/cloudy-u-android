package com.diegonmarcos.cloudlib.gh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * #729 WHY A SIGN-IN NEEDS THE ENGINE TO STAY "IN USE". gh's device flow has two halves: it asks
 * GitHub for a code while the app that asked is on screen, then POLLS GitHub for the approval
 * while the user is away in the browser typing that code. This engine is bound only by that app,
 * so the moment the browser comes up the app falls to the background and this process with it.
 * Android 15 cuts the network of a process outside a valid lifecycle (NetworkPolicyManager:
 * allowed only below PROCESS_STATE_TOP_SLEEPING), and the cut shows up as a failed DNS lookup
 * in the gh-net tunnel: "Post https://github.com/login/oauth/access_token ... DNS: no address
 * for github.com". The first request had resolved fine; only the poll died.
 *
 * So for exactly the life of one `gh auth login` the engine runs this foreground service
 * (Android's documented answer for "continue a user-visible task"). It is started with a plain
 * startService while the asking app is still on screen, which leaves this process BOUND_TOP: that
 * is what lets startForeground pass the background-start check (ActiveServices allows a calling
 * uid at or above BOUND_TOP), and a plain start, unlike startForegroundService, cannot crash the
 * engine when gh ends before onStartCommand runs. Every outcome is logged under GhRunner.TAG.
 */
class GhLoginKeeper : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // #1692 Cancel on the notification: end gh and give the foreground back.
        if (intent?.action == ACTION_CANCEL) {
            Log.i(GhRunner.TAG, "gh-net: sign-in cancelled from the notification")
            val j = job
            if (j != null) j.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        val j = job
        // #1692 a start with no sign-in behind it (a restart, a late intent) never keeps a job on.
        if (j == null || j.ended != null) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub sign-in", NotificationManager.IMPORTANCE_LOW))
        val n = runCatching { notification(j) }.getOrElse {
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Signing in to GitHub")
                .setContentText("gh is waiting for the one-time code to be approved").setOngoing(true).build()
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onSuccess { Log.i(GhRunner.TAG, "gh-net: engine held in the foreground while gh signs in") }
            .onFailure {
                Log.w(GhRunner.TAG, "gh-net: Android refused to hold the engine in the foreground (${it.message}); " +
                    "gh's poll may lose the network once the browser is up")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    /** #1692 the code, the time left (a countdown to the code's expiry) and the app that asked, plus Cancel. */
    private fun notification(j: GhLogin): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, GhLoginKeeper::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val code = j.code.ifEmpty { "waiting for gh's code" }
        val mins = ((j.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
        val text = "Code $code · expires in ${mins} min · asked by ${asker ?: "an app"}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Signing in to GitHub")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("gh is waiting for the one-time code to be approved\n$text"))
            .setWhen(j.expiresAt).setShowWhen(true).setUsesChronometer(true)
            .apply { if (Build.VERSION.SDK_INT >= 24) setChronometerCountDown(true) }
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel sign-in", cancel).build())
            .build()
    }

    /** #1692 the service dying (stop, process kill by the system, task removed) also ends gh. */
    override fun onDestroy() {
        job?.takeIf { it.running }?.cancel("the sign-in keeper was stopped; the sign-in was stopped with it")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        job?.takeIf { it.running }?.cancel("the app that asked was closed; the sign-in was stopped")
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "gh-login"
        private const val ID = 729
        const val ACTION_CANCEL = "com.diegonmarcos.cloudlib.gh.CANCEL_SIGN_IN"

        @Volatile private var job: GhLogin? = null
        @Volatile private var asker: String? = null

        /**
         * Hold the engine in the foreground for [login]; the answer releases it. Never throws: a refusal
         * is a log line. #1692 [who] is the app that asked, shown on the notification.
         */
        fun hold(ctx: Context, login: GhLogin? = null, who: String? = null): () -> Unit {
            val intent = Intent(ctx, GhLoginKeeper::class.java)
            job = login
            asker = who
            login?.onChange { runCatching { ctx.startService(intent) } }
            runCatching { ctx.startService(intent) }
                .onFailure { Log.w(GhRunner.TAG, "gh-net: could not start the sign-in keeper (${it.message})") }
            return {
                if (job === login) job = null
                runCatching { ctx.stopService(intent) }
                Log.i(GhRunner.TAG, "gh-net: sign-in keeper released")
            }
        }
    }
}

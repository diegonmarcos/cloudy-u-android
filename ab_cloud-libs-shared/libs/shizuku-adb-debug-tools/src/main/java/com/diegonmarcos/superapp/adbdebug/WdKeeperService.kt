package com.diegonmarcos.superapp.adbdebug

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.core.SilentChannels

/**
 * #1467 the keeper's foreground service: while "Keep wireless debugging on" is armed, a quiet low-priority notification holds
 * this app's process up, so battery management (Samsung sleeping apps, adaptive battery) cannot kill the observer and the
 * Wi-Fi callback [WdKeeper] keeps in it. Type specialUse, declared and justified in the lib manifest and in the perms catalogue
 * (`foreground_services`). Every 30 s it beats the claim [WdKeeperLeader] reads and runs one [WdKeeper.evaluate].
 */
class WdKeeperService : Service() {
    @Volatile private var loop: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); running = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WdKeeper.armed(this)) { stopSelf(); return START_NOT_STICKY }
        val channel = SilentChannels.ensure(this, CHANNEL_ID, "Keep wireless debugging on", NotificationManager.IMPORTANCE_MIN,
            progress = true) { setShowBadge(false) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notification(channel), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, notification(channel))
        }.onFailure { Log.w(TAG, "not promoted to foreground", it) }
        // NOT WdKeeper.start() here: start() starts this service, so calling it from onStartCommand looped
        // start -> startForegroundService -> onStartCommand -> start at ~140 Hz ("tick[start]: NOTHING" every 7 ms).
        if (loop?.isAlive != true) loop = Thread({ beat() }, "wd-keeper-beat").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        loop?.interrupt(); loop = null
        super.onDestroy()
    }

    private fun beat() {
        val ctx = applicationContext
        try {
            while (!Thread.currentThread().isInterrupted) {
                val on = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                val nextDelayMs = if (on) PERIOD_MS else KeepAlivePolicy.SCREEN_OFF_MS   // #1487 never below 30 s
                Thread.sleep(nextDelayMs)
                if (!WdKeeper.armed(ctx)) { stopSelf(); return }
                runCatching { WdKeeper.evaluate(ctx, "service") }.onFailure { Log.w(TAG, "beat failed: ${it.javaClass.simpleName}") }
            }
        } catch (_: InterruptedException) { }
    }

    private fun notification(channel: String): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Keeping wireless debugging on")
            .setContentText("Turns it back on if Android switches it off")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()

    companion object {
        private const val TAG = "WdKeeperService"
        const val CHANNEL_ID = "wd_keeper_channel"
        private const val NOTIF_ID = 38229
        private const val PERIOD_MS = 30_000L

        /** True while this process's service is up; the Configs "Service" chip reads it. */
        @Volatile var running = false

        fun start(ctx: Context) {
            if (running) return // already up: a second startForegroundService only re-enters onStartCommand
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WdKeeperService::class.java)) }
                .onFailure { Log.w(TAG, "service not started: ${it.javaClass.simpleName}") }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, WdKeeperService::class.java)) } }
    }
}

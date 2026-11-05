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
import com.diegonmarcos.superapp.devtools.AppDebugServer

/**
 * Keeps the host process alive so its two pieces of process state survive: the embedded adb
 * channel (the client's connection lives in this process) and the fleet debug server (a loopback
 * socket in this process). Without it nothing holds Cloud Account or Cloud Store up between a
 * pairing and the next daily job, Android reclaims the process, the debug port goes dead and the
 * channel is gone until something relaunches the app.
 *
 * A quiet, low-importance foreground service, NOT sticky (#1487), started by [HostShell.install] and by
 * [HostShellBootReceiver], and only for a host that may run in the background (#1502: never Cloud Account, Cloud Store only
 * with auto-update on over Wi-Fi). While running it re-arms the channel when it drops and keeps the debug
 * server listening when the host's [HostShell.debugServerAllowed] says so.
 */
class HostShellService : Service() {
    @Volatile private var loop: Thread? = null
    private var channel = CHANNEL_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A keep-alive line: MIN and silent in Cloud Store (a new "_silent_v2" channel), as before elsewhere.
        channel = SilentChannels.ensure(this, CHANNEL_ID, "Cloud shell channel", NotificationManager.IMPORTANCE_MIN,
            progress = true) { setShowBadge(false) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, notification())
        }.onFailure { Log.w(TAG, "not promoted to foreground", it) }
        if (loop?.isAlive != true) loop = Thread({ keepUp() }, "host-shell-keepalive").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loop?.interrupt(); loop = null
        super.onDestroy()
    }

    private fun keepUp() {
        val ctx = applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        var failures = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val debug = HostShell.debugServerAllowed(ctx)
                // #1487 nothing to keep (debug mode off, channel off): stop, do not idle as a foreground service.
                if (!KeepAlivePolicy.needed(debug, ChannelAutoArm.enabled(ctx), HostShell.mayBackground(ctx))) { stopSelf(); return }
                val screenOn = pm.isInteractive
                runCatching {
                    if (debug) AppDebugServer.start(ctx)
                    if (KeepAlivePolicy.mayReconnect(screenOn) && !EmbeddedAdbChannel.isReady(ctx)) {
                        failures = if (HostShell.reconnect(ctx, ATTEMPTS).first) 0 else failures + 1
                    } else if (EmbeddedAdbChannel.isReady(ctx)) failures = 0
                }.onFailure { failures++; Log.w(TAG, "keep-alive pass failed: ${it.javaClass.simpleName}") }
                Thread.sleep(KeepAlivePolicy.nextDelayMs(failures, screenOn))
            }
        } catch (_: InterruptedException) { }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Cloud shell channel")
            .setContentText("Keeps the shell channel and the debug server up")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "HostShellService"
        const val CHANNEL_ID = "host_shell_channel"
        private const val NOTIF_ID = 38227
        private const val ATTEMPTS = 3

        /** startForegroundService; refused from the background on Android 12+, which is logged, not thrown. */
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, HostShellService::class.java)) }
                .onFailure { Log.w(TAG, "service not started: ${it.javaClass.simpleName}") }
        }
    }
}

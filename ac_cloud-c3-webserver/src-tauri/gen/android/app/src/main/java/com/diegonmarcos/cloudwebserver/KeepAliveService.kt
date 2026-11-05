package com.diegonmarcos.cloudwebserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * The foreground service that keeps the Rust servers bound without the UI. #1502: it exists only while Server and Keep alive are
 * both ON ([PowerState.serviceAllowed]); any start request from elsewhere (boot, Cloud Browser, the SuperApp wake, an MCP phone
 * tool) while that is not so ends it at once with no restart (START_NOT_STICKY). It never schedules a wake, loops or polls: it
 * holds the process and one notification with a Stop action (Server OFF, ports released). Follows the #1487 KeepAlivePolicy.
 */
class KeepAliveService : Service() {
    private external fun nativeStart(): Boolean
    private external fun nativeStop()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local servers", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Cloud C3 WebServer")
            .setContentText("Server and Keep alive are ON")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        // Required within 5 s of startForegroundService, even when the gate below then refuses.
        startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread { runCatching { System.loadLibrary("cloud_c3_webserver_lib"); nativeStop() } }.start()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PowerState.serviceAllowed(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Off the main thread: loading the library and binding must not hold the 5 s foreground deadline.
        Thread {
            runCatching {
                System.loadLibrary("cloud_c3_webserver_lib")
                nativeStart()
            }
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "local-servers"
        const val NOTIFICATION_ID = 8002
        const val ACTION_STOP = "com.diegonmarcos.cloudwebserver.STOP"
    }
}

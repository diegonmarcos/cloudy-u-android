package com.diegonmarcos.cloudwebserver

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.core.KeepAliveStatus

/**
 * The foreground service that keeps the Rust servers bound without the UI. #1502: it exists only while Server and Keep alive are
 * both ON ([PowerState.serviceAllowed]); any start request from elsewhere (boot, Cloud Browser, the SuperApp wake, an MCP phone
 * tool) while that is not so ends it at once with no restart (START_NOT_STICKY). It never schedules a wake, loops or polls: it
 * holds the process and one notification with a Stop action (Server OFF, ports released). Follows the #1487 KeepAlivePolicy.
 * The notification is the fleet's keep-alive badge (FleetNotify.keepAliveNotification): it shows the live state (which of
 * the app port and the sshd door answer, whether the mesh tunnel is up), probed once when the servers have started and
 * again at each start request, never on a timer.
 */
class KeepAliveService : Service() {
    private external fun nativeStart(): Boolean
    private external fun nativeStop()

    /** False once the service is stopping: a late status probe must not put the notification back. */
    @Volatile private var alive = true

    override fun onCreate() {
        super.onCreate()
        // Required within 5 s of startForegroundService, even when the gate below then refuses. No type: the manifest
        // declares none for this service (targetSdk below 34).
        FleetNotify.startKeepAlive(this, NOTIFICATION_ID, notification(listOf("Server and Keep alive are ON", "Starting servers...")), 0)
    }

    private fun notification(lines: List<String>): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        // Tap opens this app: explicit component, new task (a service has no task to join).
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return FleetNotify.keepAliveNotification(this, R.mipmap.ic_launcher, "Cloud C3 WebServer", lines, stop, open)
    }

    /** One-shot probe of the two doors and the tunnel, then one notification update. Waits for the app port a few seconds at most. */
    private fun refreshStatus() {
        for (i in 0 until 5) { if (KeepAliveStatus.portUp(PORT_APP)) break; Thread.sleep(1000) }
        val ports = linkedMapOf(PORT_APP to KeepAliveStatus.portUp(PORT_APP), PORT_SSHD to KeepAliveStatus.portUp(PORT_SSHD))
        val lines = listOf("Server and Keep alive are ON", KeepAliveStatus.portsLine("Ports", ports), KeepAliveStatus.meshLine(KeepAliveStatus.meshUp()))
        if (alive) FleetNotify.post(this, NOTIFICATION_ID, notification(lines))
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            alive = false
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
            runCatching { refreshStatus() }
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 8002
        /** build.json::runtime.port (the app's loopback server) and the nix-on-droid mesh sshd door (mesh_sshd.port). */
        private const val PORT_APP = 8002
        private const val PORT_SSHD = 8024
        const val ACTION_STOP = "com.diegonmarcos.cloudwebserver.STOP"
    }
}

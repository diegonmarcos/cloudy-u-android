package com.diegonmarcos.cloudwebserver

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
        // #1724 Required within 5 s of startForegroundService, even when the gate then refuses; onStartCommand does it again
        // for every later start request (the timeout applies to each one). Never throws.
        enterForeground(listOf("Server and Keep alive are ON", "Starting servers..."))
    }

    /**
     * startForeground with the declared type (specialUse, build.json / manifest PROPERTY_SPECIAL_USE_FGS_SUBTYPE); the channel is
     * created by FleetNotify before the notification is built. A refusal (ForegroundServiceStartNotAllowedException, SecurityException,
     * a notification that cannot be built) is caught, recorded as the real reason (Home > Keep-alive health) and answered false.
     */
    private fun enterForeground(lines: List<String>): Boolean {
        val ok = KeepAliveFlow.guarded(
            {
                FleetNotify.keepAliveChannel(this)   // the channel exists before any notification is built
                val n = runCatching { notification(lines) }.getOrElse { plainNotification() }
                FleetNotify.startKeepAlive(this, NOTIFICATION_ID, n)
            },
            { KeepAliveReason.set(this, it); Log.w(TAG, "foreground start refused: $it") },
        )
        if (ok) KeepAliveReason.clear(this)
        return ok
    }

    /** Last resort when the rich notification cannot be built: a minimal one on the same channel. */
    private fun plainNotification(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, FleetNotify.keepAliveChannel(this))
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Cloud C3 WebServer").setOngoing(true).build()

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        // #1724 KeepAliveFlow.onStartCommand calls startForeground FIRST on every path (Stop action, gate refusal, normal start).
        KeepAliveFlow.onStartCommand(host, intent?.action)

    private val host = object : KeepAliveFlow.Host {
        override fun enterForeground(): Boolean = this@KeepAliveService.enterForeground(listOf("Server and Keep alive are ON", "Starting servers..."))
        override fun serviceAllowed(): Boolean = runCatching { PowerState.serviceAllowed(this@KeepAliveService) }.getOrDefault(false)
        override fun stopServers() {
            alive = false
            Thread { runCatching { System.loadLibrary("cloud_c3_webserver_lib"); nativeStop() } }.start()
        }
        override fun stopSelfNow() {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        override fun startServers() {
            // Off the main thread: loading the library and binding must not hold the 5 s foreground deadline.
            Thread {
                runCatching {
                    System.loadLibrary("cloud_c3_webserver_lib")
                    nativeStart()
                }
                runCatching { refreshStatus() }
            }.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 8002
        /** build.json::runtime.port (the app's loopback server) and the nix-on-droid mesh sshd door (mesh_sshd.port). */
        private const val PORT_APP = 8002
        private const val PORT_SSHD = 8024
        const val ACTION_STOP = KeepAliveFlow.ACTION_STOP
        private const val TAG = "C3KeepAlive"
    }
}

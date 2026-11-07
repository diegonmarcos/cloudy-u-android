package com.diegonmarcos.clouddrive.mounts

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
import android.os.PowerManager
import com.diegonmarcos.superapp.core.FleetNotify
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.cloudlib.mounts.MountCredentialStore
import com.diegonmarcos.cloudlib.mounts.MountFsFactory
import com.diegonmarcos.cloudlib.mounts.MountLink
import com.diegonmarcos.cloudlib.mounts.MountSessions
import com.diegonmarcos.cloudlib.mounts.MountStore
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app-side owner of mount lifetime (build.json::mount_keeper). One process-scoped
 * [MountSessions] registry, configured here; the set of mounts to keep, persisted so a boot, an
 * update or a process restart restores exactly what was live. The UI never owns a mount.
 */
object MountKeeper {
    private const val PREFS = "mount-keeper"
    private const val IDS = "ids"
    val policy: MountPolicy by lazy { MountPolicy.parse(Declarations.mountKeeperJson) }

    /** Configures the registry once per process and hooks "a mount was opened" to start the keeper. */
    fun install(ctx: Context): MountSessions {
        val app = ctx.applicationContext
        val creds = MountCredentialStore(app)
        val reg = MountSessions.configure(
            { m -> MountFsFactory.open(app, m, creds.secretFor(m), creds.knownHosts) },
            policy.backoffInitialSeconds * 1000, policy.backoffMaxSeconds * 1000,
        )
        if (policy.enabled) MountSessions.onOpened = { spec -> remember(app, spec.id); start(app) }
        return reg
    }

    fun keptIds(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(IDS, emptySet()) ?: emptySet()
    private fun remember(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(IDS, (keptIds(ctx) + id).toSet()).apply()
    }
    fun forgetAll(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(IDS).apply()

    /** Mounts were on (persisted ids, or declared autoconnect): the service should be running. */
    fun wanted(ctx: Context): Boolean = policy.enabled && (keptIds(ctx).isNotEmpty() || policy.autoconnectDeclared)

    /** Starting is idempotent: a running keeper re-attaches, it never reconnects a healthy mount. */
    fun start(ctx: Context) {
        if (!policy.enabled) return
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MountKeeperService::class.java)) }
    }

    /** App opened: re-attach to whatever is live (the keeper restores only the dead). */
    fun onAppOpen(ctx: Context) {
        install(ctx)
        if (policy.startOnAppOpen && wanted(ctx)) start(ctx)
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, MountKeeperService::class.java).setAction(MountKeeperService.ACTION_STOP))
    }
}

/**
 * Foreground service (type specialUse: keeping user-opened remote file-system connections alive;
 * dataSync would be cut after 6 h on Android 15 and may not start from BOOT_COMPLETED). Started,
 * not just bound, and sticky, so it outlives every Activity; stopWithTask=false in the manifest.
 * It holds a persistent bind on the mounts engine (so its process keeps our priority instead of
 * being a killable cached process), restores the kept mounts, probes them every
 * health_interval_seconds and reopens only a dead one with backoff. Partial wake lock ONLY while a
 * transfer is in flight.
 */
class MountKeeperService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineHold: Closeable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var reg: MountSessions

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reg = MountKeeper.install(this)
        goForeground(0)
        scope.launch { engineHold = runCatching { MountLink(applicationContext).hold() }.getOrNull() }
        scope.launch { restoreThenWatch() }
        scope.launch { reg.status.collectLatest { list -> notifyCount(list.count { it.state == MountSessions.State.CONNECTED }) } }
        if (MountKeeper.policy.wakeLockDuringTransfers) scope.launch { reg.transfers.collectLatest { n -> if (n > 0) holdWake() else releaseWake() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MountKeeper.forgetAll(this)
            scope.launch { reg.disconnectAll() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(reg.liveCount())
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { /* the app's task going away is not a reason to drop mounts */ }

    override fun onDestroy() {
        scope.cancel()
        releaseWake()
        runCatching { engineHold?.close() }
        super.onDestroy()
    }

    private suspend fun restoreThenWatch() {
        val store = MountStore(File(filesDir, "mounts/mounts.json"))
        val kept = MountKeeper.keptIds(this)
        val all = store.load()
        val targets = all.filter { it.id in kept || (MountKeeper.policy.autoconnectDeclared && it.declared) }
        // acquire() re-attaches to a session already live in this process and opens only the missing.
        targets.forEach { spec -> runCatching { reg.acquire(spec) } }
        val period = MountKeeper.policy.healthIntervalSeconds.coerceAtLeast(15) * 1000
        while (scope.isActive) {
            delay(period)
            reg.healthPass()
        }
    }

    private fun goForeground(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(count), type)
    }

    private fun notifyCount(count: Int) {
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(count)) }
    }

    private fun notification(count: Int): Notification {
        val channel = MountKeeper.policy.notificationChannel
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(NotificationChannel(channel, getString(R.string.mount_keeper_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, MountKeeperService::class.java).setAction(ACTION_STOP), flags)
        // The fleet's keep-alive badge (FleetNotify.keepAliveNotification): ongoing, the live-mount count, a Stop action.
        return FleetNotify.keepAliveNotification(
            this, android.R.drawable.stat_notify_sync_noanim, getString(R.string.mount_keeper_title),
            listOf(resources.getQuantityString(R.plurals.mount_keeper_count, count, count)), stop, open,
            channelId = channel, stopLabel = getString(R.string.mount_keeper_stop),
        )
    }

    private fun holdWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clouddrive:mount-transfer").apply {
            setReferenceCounted(false)
            acquire(MountKeeper.policy.wakeLockMaxMinutes * 60_000)
        }
    }

    private fun releaseWake() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    companion object {
        const val ACTION_STOP = "com.diegonmarcos.clouddrive.mounts.STOP"
        const val NOTIFICATION_ID = 7301
    }
}

package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.datamanager.DataUsageProvider
import com.diegonmarcos.superapp.network.DataBadgeModel
import com.diegonmarcos.superapp.network.DataBadgeModel.Act
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The persistent "Data" badge: today's and this month's data usage, mobile vs
 * Wi-Fi, and the top apps, in the shade - drawn from [DataBadgeModel].
 *
 * Every figure comes from libs:datamanager's [DataUsageProvider], the engine
 * behind Configs > About > Data Usage; nothing here queries NetworkStatsManager.
 * Usage needs the Usage Access special permission; without it the badge says so
 * and offers a Grant access button.
 *
 * A DATA WIDGET, not a keep-alive: posted with plain notify ([FleetNotify.dataWidgetBuilder]), never startForeground
 * (see [DataWidgetRuntime]). NetworkStats has no change events, so a read happens on the events that make a fresh
 * number worth having: the service starting, the screen coming on (a process-level receiver), the Refresh button, and
 * one inexact [REFRESH_MS] alarm that carries the card while the process is gone. Coalesced to one read per
 * [MIN_GAP_MS]. Nothing runs while the screen is off. Without POST_NOTIFICATIONS (Android 13+) it posts and reads nothing.
 */
class DataBadgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        ensureReceiver(app)
        // A short job: read, post, schedule the next inexact read, stopSelf.
        requestRefresh(app, force = true) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    companion object {
        const val NOTIF_ID = 7718
        const val CHANNEL_ID = "network_data"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "network_data"

        /** The shell's home-action id that shows the Data Manager (Configs > About > Data Usage). */
        const val OPEN_ACTION = "action:open_data_manager"

        private const val ACTION_REFRESH = "com.diegonmarcos.superapp.network.DATA_REFRESH"
        private const val RC_GRANT = 0x4441
        private const val RC_REFRESH = 0x4442
        private const val RC_OPEN = 0x4443
        private const val RC_GRANT_PHONE = 0x4444
        private const val MIN_GAP_MS = 30_000L
        /** The one inexact alarm that carries the card while the process is gone. */
        internal const val REFRESH_MS = 60 * 60_000L

        private val main = Handler(Looper.getMainLooper())
        private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "data-badge").apply { isDaemon = true } } }
        @Volatile private var receiver: BroadcastReceiver? = null
        @Volatile private var pending: Runnable? = null
        @Volatile private var lastPostMs = 0L
        private val waiting = CopyOnWriteArrayList<() -> Unit>()

        /** The process-level screen-on receiver (application context, so no service holds it). Idempotent. */
        @Synchronized
        fun ensureReceiver(app: Context) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { requestRefresh(app, force = false) }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }

        @Synchronized
        private fun dropReceiver(app: Context) {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
        }

        /** Coalesce a burst into one read, at most one per [MIN_GAP_MS] unless [force]d by the owner; [done] runs after it. */
        fun requestRefresh(app: Context, force: Boolean, done: (() -> Unit)? = null) {
            if (done != null) waiting += done
            main.post {
                if (pending != null) return@post
                val wait = if (force) 0L else (MIN_GAP_MS - (System.currentTimeMillis() - lastPostMs)).coerceIn(0L, MIN_GAP_MS)
                val r = Runnable {
                    pending = null
                    runCatching { worker.execute { try { refreshNow(app) } finally { finish() } } }.onFailure { finish() }
                }
                pending = r
                main.postDelayed(r, wait)
            }
        }

        private fun finish() {
            val l = waiting.toList(); waiting.clear()
            main.post { l.forEach { runCatching(it) } }
        }

        // ── worker thread ────────────────────────────────────────────────

        private fun refreshNow(app: Context) {
            // Switched off (or its grants gone): stop reading; the owner retires the card.
            if (!BadgeServices.wanted(app, DataBadgeService::class.java.name)) { dropReceiver(app); return }
            if (!NetworkBadgeService.canPost(app)) return
            ensureChannel(app)
            val snap = gather(app)
            lastPostMs = System.currentTimeMillis()
            DataWidgetRuntime.scheduleNext(app, DataBadgeService::class.java, NOTIF_ID, REFRESH_MS)
            runCatching { NotifyGate.postCtx(app, NOTIF_ID, build(app, snap)) }
        }

    private fun gather(ctx: Context): DataBadgeModel.Snapshot {
        if (!DataUsageProvider.hasUsageAccess(ctx)) return DataBadgeModel.Snapshot(hasAccess = false)
        val now = System.currentTimeMillis()
        fun win(t: DataUsageProvider.Totals) = DataBadgeModel.Window(mobile = t.mobile, wifi = t.wifi)
        val monthStart = DataUsageProvider.startOfMonth(now)
        val top = DataUsageProvider.perApp(ctx, monthStart, now).take(DataBadgeModel.TOP_APPS)
            .map { DataBadgeModel.App(it.label, it.totals.total) }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = monthStart }
        val nextStart = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.MONTH, 1) }.timeInMillis
        val lastDay = java.util.Calendar.getInstance().apply { timeInMillis = nextStart - 1 }.time
        // Per SIM: the engine measures it where Android allows and says so (exact); it never splits what it cannot.
        val hasPhone = DataUsageProvider.hasPhoneState(ctx)
        // The 30-day window: the engine answers any window, and the system keeps ~90 days of history.
        val start30 = DataUsageProvider.startOfDaysAgo(30, now)
        val sims30 = if (!hasPhone) emptyMap<Int, Long>() else DataUsageProvider.mobilePerSubscription(ctx, start30, now)
            .associate { it.slot to it.totals.mobile }
        val sims = if (!hasPhone) emptyList<DataBadgeModel.Sim>() else DataUsageProvider.mobilePerSubscription(ctx, monthStart, now)
            .map { DataBadgeModel.Sim(it.slot, it.carrier, it.totals.mobile, it.exact, sims30[it.slot] ?: 0L) }
        return DataBadgeModel.Snapshot(
            hasAccess = true, hasPhone = hasPhone, sims = sims,
            last30 = win(DataUsageProvider.deviceTotals(ctx, start30, now)), last30WindowMs = now - start30,
            monthElapsedMs = now - monthStart, monthLengthMs = nextStart - monthStart,
            monthEndLabel = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(lastDay),
            today = win(DataUsageProvider.deviceTotals(ctx, DataUsageProvider.startOfToday(now), now)),
            month = win(DataUsageProvider.deviceTotals(ctx, monthStart, now)),
            topApps = top,
        )
    }

    private fun build(ctx: Context, s: DataBadgeModel.Snapshot): Notification {
        val card = DataBadgeModel.card(s)
        val pinned = BadgeServices.pinned(ctx, BADGE_ID)
        val open = openDataManager(ctx)
        val b = FleetNotify.dataWidgetBuilder(ctx, R.drawable.ic_stat_notify, card.title, listOf(card.text), open,
                channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(), subText = "Cloud SA - Data")
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.expanded))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // what the owner uses stays off the lockscreen
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(ctx, NOTIF_ID, DataBadgeService::class.java)) }
        for (a in card.actions) {
            val pi = when (a.act) {
                // #1274 both grants open the Permissions page (libs:perms) on the entry, never a settings page from here.
                Act.GRANT -> PendingIntent.getActivity(ctx, RC_GRANT,
                    com.diegonmarcos.superapp.perms.PermsActivity.intent(ctx, DataUsageProvider.PERMS_FEATURE, listOf("usage_access")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.GRANT_PHONE -> PendingIntent.getActivity(ctx, RC_GRANT_PHONE,
                    com.diegonmarcos.superapp.perms.PermsActivity.intent(ctx, DataUsageProvider.PERMS_FEATURE, listOf("phone_state")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.REFRESH -> PendingIntent.getService(ctx, RC_REFRESH,
                    Intent(ctx, DataBadgeService::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Act.OPEN -> open
            }
            BadgeModel.add(b, BadgeModel.Action(a.act.name.lowercase(), when (a.act) {
                Act.GRANT, Act.GRANT_PHONE -> R.drawable.ic_act_key
                Act.REFRESH -> R.drawable.ic_act_refresh
                Act.OPEN -> R.drawable.ic_act_open
            }, a.label, pi))
        }
        return NotifyGroups.attach(ctx, b, BADGE_ID).build()
            .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

        private fun openDataManager(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, RC_OPEN,
            Intent(ctx, MainActivity::class.java)
                .putExtra("shortcut_action", OPEN_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Data usage", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Data used today and this month, mobile vs Wi-Fi, top apps."
                    setShowBadge(false)
                },
            )
        }
    }
}

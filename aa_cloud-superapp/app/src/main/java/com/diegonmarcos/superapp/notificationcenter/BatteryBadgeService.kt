package com.diegonmarcos.superapp.notificationcenter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.battery.BatteryRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The persistent "Battery" badge: the time to full / to empty first, then the
 * rest of what the battery reports, in the shade.
 *
 * A DATA WIDGET, not a keep-alive: an ongoing notification posted with plain
 * notify ([FleetNotify.dataWidgetBuilder]), never startForeground, so Android
 * does not list the app as running in the background for it. This service is a
 * short job: ensure the process-level receiver, read the SoT once, post,
 * schedule the next read, stopSelf.
 *
 * It computes nothing. Every number is the battery SoT's
 * ([BatteryRepository.report], libs:battery BatteryTruth) and what it shows is
 * [BatteryBadgeModel]'s card of that report — the same values, and for the
 * expanded body the same strings, as the home-screen battery popup and
 * Configs › About › Battery. The receiver below is also the SoT's recorder
 * while the process lives: each coalesced event writes one history sample.
 *
 * Refresh is EVENT-DRIVEN while the process lives: a dynamic ACTION_BATTERY_CHANGED receiver (it cannot
 * be manifest-registered), plus screen on/off so the history can split
 * screen-on from screen-off drain. The system fires it at most every few
 * seconds while charging, so events are coalesced to one read per
 * [MIN_GAP_MS]. With the process gone the card is carried by the manifest's
 * plug / unplug / low / okay wake ([DataWidgetWakeReceiver]) and one inexact
 * [REFRESH_MS] alarm ([DataWidgetRuntime]); no handler loop holds the process.
 *
 * Without the Android 13+ POST_NOTIFICATIONS grant it posts nothing.
 */
class BatteryBadgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        ensureReceiver(app)
        // Renotify (a swipe on 14+, an alarm, a plug event), then let the service go.
        requestRefresh(app, immediate = true) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    companion object {
        const val NOTIF_ID = 7717
        const val CHANNEL_ID = "battery_status"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "battery_status"

        private const val RC_OPEN = 0x4231
        internal const val MIN_GAP_MS = 90_000L
        /** The inexact alarm that carries the card while the process is gone. */
        private const val REFRESH_MS = 15 * 60_000L

        private val main = Handler(Looper.getMainLooper())
        private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "battery-badge").apply { isDaemon = true } } }
        @Volatile private var receiver: BroadcastReceiver? = null
        @Volatile private var pending: Runnable? = null
        @Volatile private var lastPostMs = 0L
        @Volatile private var lastPct = -1
        @Volatile private var lastPlug = -2
        private val waiting = CopyOnWriteArrayList<() -> Unit>()

        /** The process-level receiver (application context, so no service has to hold it). Idempotent. */
        @Synchronized
        fun ensureReceiver(app: Context) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        // The screen edge is a reason to look; on is the moment the owner sees the card.
                        // Both edges bypass the gap: the history splits screen-on from screen-off drain at them.
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> requestRefresh(app, immediate = true)
                        // Voltage / temperature jitter is not a reason to wake: only a level or plug change is.
                        else -> {
                            val pct = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                            val plug = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1)
                            if (changed(pct, plug)) requestRefresh(app)
                        }
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }

        /** True (and remembered) when the level or the plug state differs from the last one acted on. */
        @Synchronized
        internal fun changed(pct: Int, plug: Int): Boolean {
            if (pct == lastPct && plug == lastPlug) return false
            lastPct = pct; lastPlug = plug
            return true
        }

        @Synchronized
        private fun dropReceiver(app: Context) {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
        }

        /** Coalesce a burst of events into one read, at most one per [MIN_GAP_MS] (a screen edge goes at once); [done] runs when it has been posted. */
        fun requestRefresh(app: Context, immediate: Boolean = false, done: (() -> Unit)? = null) {
            if (done != null) waiting += done
            main.post {
                if (pending != null) {
                    if (!immediate) return@post
                    main.removeCallbacks(pending!!); pending = null
                }
                val wait = if (immediate) 0L else (MIN_GAP_MS - (System.currentTimeMillis() - lastPostMs)).coerceIn(0L, MIN_GAP_MS)
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
            // Switched off (or its grants gone): stop recording and posting; the owner retires the card.
            if (!BadgeServices.wanted(app, BatteryBadgeService::class.java.name)) {
                dropReceiver(app); DataWidgetRuntime.retiredIfOff(app, BatteryBadgeService::class.java); return
            }
            // The SoT records the sample even when the shade may not be posted to.
            val report = runCatching { BatteryRepository.report(app) }.getOrNull() ?: return
            lastPostMs = System.currentTimeMillis()
            DataWidgetRuntime.scheduleNext(app, BatteryBadgeService::class.java, NOTIF_ID, REFRESH_MS)
            if (!canPost(app)) return
            ensureChannel(app)
            runCatching { NotifyGate.postCtx(app, NOTIF_ID, build(app, BatteryBadgeModel.card(report))) }
        }

        // ── drawing ──────────────────────────────────────────────────────

        private fun build(ctx: Context, card: BatteryBadgeModel.Card): Notification {
            val pinned = BadgeServices.pinned(ctx, BADGE_ID)
            val open = PendingIntent.getActivity(
                ctx, RC_OPEN,
                com.diegonmarcos.superapp.batterystats.BatteryStatsPage.intent(ctx),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = FleetNotify.dataWidgetBuilder(ctx, R.drawable.ic_stat_notify, card.title, listOf(card.text), open,
                    channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(), subText = "Cloud SA - Battery")
                .setStyle(NotificationCompat.BigTextStyle().bigText(card.expanded))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(ctx, NOTIF_ID, BatteryBadgeService::class.java)) }
            return NotifyGroups.attach(ctx, b, BADGE_ID).build()
                .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
        }

        /** Android 13+ gates the shade on POST_NOTIFICATIONS; below it, always allowed. */
        fun canPost(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Battery", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Battery: time to full or empty, level, current, voltage, temperature, health."
                    setShowBadge(false)
                },
            )
        }
    }
}

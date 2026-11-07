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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.profile.AccountData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * #1151 The persistent "AI" badge of the Resources group: Claude's 5-hour session and weekly used % with their reset
 * times, and OpenRouter's balance and spend (24h, 7 days, month) in dollars and millions of tokens. Everything comes from
 * c3-infra-api `GET /ai/usage` through [AiUsageClient] (mesh first, then public with the fleet bearer) and is worded by
 * [AiUsageModel]; nothing here computes a figure.
 *
 * A DATA WIDGET, not a keep-alive: plain notify ([FleetNotify.dataWidgetBuilder]), never startForeground (see
 * [DataWidgetRuntime]). Refresh: on start, when the screen comes on, on the Refresh button, every [TICK_MS] while the
 * screen is on (the tick stops with the screen; a process-level receiver), and one inexact [REFRESH_MS] alarm while the
 * process is gone. A failed read keeps the last good figures on the card and says why; data older than
 * [AiUsageModel.STALE_MS] is marked stale. Without POST_NOTIFICATIONS (Android 13+) it posts and reads nothing.
 */
class AiBadgeService : Service() {

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
        const val NOTIF_ID = 7719
        const val CHANNEL_ID = "ai_usage"
        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "ai_usage"

        private const val ACTION_REFRESH = "com.diegonmarcos.superapp.notificationcenter.AI_REFRESH"
        private const val RC_OPEN = 0x4151
        private const val RC_REFRESH = 0x4152
        private const val MIN_GAP_MS = 20_000L
        private const val TICK_MS = 5 * 60_000L
        /** The one inexact alarm that carries the card while the process is gone. */
        internal const val REFRESH_MS = 30 * 60_000L
        private const val PREFS = "ai_usage_badge"
        private const val KEY_BODY = "last_body"
        private const val KEY_AT = "last_at_ms"

        private val main = Handler(Looper.getMainLooper())
        private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ai-badge").apply { isDaemon = true } } }
        @Volatile private var receiver: BroadcastReceiver? = null
        @Volatile private var pending: Runnable? = null
        @Volatile private var lastReadMs = 0L
        private val waiting = CopyOnWriteArrayList<() -> Unit>()

        private val tick = object : Runnable {
            @Volatile var app: Context? = null
            override fun run() {
                val a = app ?: return
                val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) return // the screen-on receiver restarts the tick
                requestRefresh(a, force = false)
                main.postDelayed(this, TICK_MS)
            }
        }

        /** The process-level screen receiver (application context, so no service holds it): the tick runs only with the screen on. */
        @Synchronized
        fun ensureReceiver(app: Context) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    main.removeCallbacks(tick)
                    if (intent.action == Intent.ACTION_SCREEN_ON) {
                        requestRefresh(app, force = false)
                        tick.app = app
                        main.postDelayed(tick, TICK_MS)
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            tick.app = app
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
        }

        @Synchronized
        private fun dropReceiver(app: Context) {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            main.removeCallbacks(tick)
        }

        /** Coalesce a burst into one read, at most one per [MIN_GAP_MS] unless the owner forced it; [done] runs after it. */
        fun requestRefresh(app: Context, force: Boolean, done: (() -> Unit)? = null) {
            if (done != null) waiting += done
            main.post {
                if (pending != null) return@post
                val wait = if (force) 0L else (MIN_GAP_MS - (System.currentTimeMillis() - lastReadMs)).coerceIn(0L, MIN_GAP_MS)
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
            if (!BadgeServices.wanted(app, AiBadgeService::class.java.name)) { dropReceiver(app); return }
            if (!NetworkBadgeService.canPost(app)) return
            ensureChannel(app)
            lastReadMs = System.currentTimeMillis()
            var note = ""
            val out = runCatching { AiUsageClient.fetch(BuildConfig.UI_C3_OPS_BASE_URL, bearer(app), AiUsageClient.fleetTransport, AiUsageClient.plainTransport) }
                .getOrElse { AiUsageClient.Outcome.Failed(it.javaClass.simpleName) }
            when (out) {
                is AiUsageClient.Outcome.Ok -> {
                    if (AiUsageModel.parse(out.body) != null) {
                        prefs(app).edit().putString(KEY_BODY, out.body).putLong(KEY_AT, lastReadMs).apply()
                    } else note = "unreadable reply"
                }
                is AiUsageClient.Outcome.Failed -> note = out.reason
            }
            DataWidgetRuntime.scheduleNext(app, AiBadgeService::class.java, NOTIF_ID, REFRESH_MS)
            runCatching { FleetNotify.post(app, NOTIF_ID, build(app, drawCached(app, note))) }
        }

        private fun drawCached(ctx: Context, note: String): AiUsageModel.Card {
            val p = prefs(ctx)
            val snap = p.getString(KEY_BODY, null)?.let(AiUsageModel::parse)
            return AiUsageModel.card(snap, p.getLong(KEY_AT, 0L), System.currentTimeMillis(), note)
        }

        // ── drawing ──────────────────────────────────────────────────────────

        private fun build(ctx: Context, card: AiUsageModel.Card): Notification {
            val pinned = BadgeServices.pinned(ctx, BADGE_ID)
            val open = PendingIntent.getActivity(ctx, RC_OPEN, openIntent(ctx),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getService(
                ctx, RC_REFRESH, Intent(ctx, AiBadgeService::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = FleetNotify.dataWidgetBuilder(ctx, R.drawable.ic_stat_notify, card.title, listOf(card.text), open,
                    channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(), subText = "Cloud SA - AI")
                .setStyle(NotificationCompat.BigTextStyle().bigText(styled(card)))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .apply {
                    card.progress?.let { setProgress(100, it, false) }
                    if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(ctx, NOTIF_ID, AiBadgeService::class.java))
                }
            BadgeModel.add(b, BadgeModel.Action("refresh", R.drawable.ic_act_refresh, "Refresh", refresh))
            return NotifyGroups.attach(ctx, b, BADGE_ID).build()
                .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
        }

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The fleet bearer this device holds (Cloud Account's fleet.bearer, else the Dagu login); "" when none. Never logged. */
        private fun bearer(ctx: Context): String = AccountData.secret(ctx, "fleet.bearer").trim()
            .ifEmpty { runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("") }

        /**
         * #1543 The tap: Cloud SuperIntel's Cost page (its OPEN action with section/page = cost, package-pinned), or the
         * SuperApp itself when SuperIntel is not installed.
         */
        fun openIntent(ctx: Context): Intent {
            val l = AiUsageModel.SuperIntelLink
            val si = Intent(l.ACTION).setPackage(l.PACKAGE)
                .putExtra(l.EXTRA_SECTION, l.COST).putExtra(l.EXTRA_PAGE, l.COST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val ok = runCatching { ctx.packageManager.resolveActivity(si, 0) != null }.getOrDefault(false)
            return if (ok) si else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        /** The big text: the weekly block monospaced (its columns line up), a line whose week runs out before reset in red. */
        fun styled(card: AiUsageModel.Card): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            card.lines.forEachIndexed { i, l ->
                if (i > 0) sb.append('\n')
                val start = sb.length
                sb.append(l)
                if (i < card.weekCount) sb.setSpan(android.text.style.TypefaceSpan("monospace"), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i in card.alert) sb.setSpan(android.text.style.ForegroundColorSpan(ALERT_RED), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }

        const val ALERT_RED = 0xFFFF5252.toInt()

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI usage", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Claude weekly used % and burn rate, session %, OpenRouter balance and spend."
                    setShowBadge(false)
                },
            )
        }
    }
}

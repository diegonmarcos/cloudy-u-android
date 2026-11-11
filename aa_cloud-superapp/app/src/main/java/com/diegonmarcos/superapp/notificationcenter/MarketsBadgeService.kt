package com.diegonmarcos.superapp.notificationcenter

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
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * #517 — the "Markets" badge: live USD/BRL, EUR/USD, WTI crude and gold.
 *
 * A DATA WIDGET, not a keep-alive (like Weather, MyHealth and Battery): an ongoing card posted with plain notify
 * ([FleetNotify.dataWidgetBuilder]), never startForeground. The service is a short job (redraw, fetch if due, schedule
 * one inexact alarm via [DataWidgetRuntime], stopSelf). It is started by [BadgeServices.ensureAll] from the
 * declaration — there is no bespoke start call for it anywhere, which is the whole point of #515.
 *
 * WHAT IT QUOTES IS NOT IN HERE. The four instruments, their captions, their
 * precision and the ticker page each one opens are
 * `build.json::ui.notification_center.producers[markets_prices].instruments`.
 * This class walks whatever that list says, so a fifth instrument — or Brent
 * (`BZ=F`) instead of WTI — is a data edit and no Kotlin.
 *
 * TAP GOES NOWHERE OUT OF THE APP. #156 removed six ad-hoc
 * `startActivity(ACTION_VIEW)` sites that leaked out of cloud-sa; this is not
 * a seventh. Every button carries the `shortcut_action` extra into
 * [MainActivity], exactly as `KdeStatusNotifier` does, and ShellActivity's
 * existing router hands an http(s) target to the app's own embedded
 * [com.diegonmarcos.superapp.launcher.WebPageFragment]. No new navigation
 * code, and no way for this badge to open a browser app.
 *
 * POLLING IS DECLARED, NOT HARDCODED. `refresh_minutes` is a customization
 * option (15/30/60/120, default 60) with a 15-minute floor, and the cadence is
 * re-read every cycle so a change applies without a restart. The network is
 * only touched while the screen is on ([fetchDue]); a screen-on edge fetches if
 * the last read is older than the cadence. In system power-save mode the
 * interval is quadrupled and the fetch is skipped — but the badge is still
 * REDRAWN, so the age suffixes keep advancing and a price frozen by power
 * saving or a dark screen reads as old instead of reading as live.
 */
class MarketsBadgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        ensureReceiver(app)
        // A short job: redraw the last good face at once, fetch if the screen is on, schedule one inexact alarm, stopSelf.
        refresh(app, force = true) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    companion object {
        const val NOTIF_ID = 7714
        private const val CHANNEL_ID = "markets_prices"
        internal const val DEFAULT_REFRESH_MIN = 60L

        /** Floor. The declaration offers 15 as its tightest option; this is the
         *  guard that keeps a hand-edited value from becoming a tight loop on
         *  somebody else's phone. */
        private const val MIN_REFRESH_MIN = 15L
        private const val MAX_REFRESH_MIN = 240L
        private const val POWER_SAVE_FACTOR = 4L
        private const val STALE_CYCLES = 3L

        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "markets_prices"

        private val scope = CoroutineScope(SupervisorJob())
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var receiver: BroadcastReceiver? = null
        @Volatile private var lastFetchMs = 0L

        /** Last answer per symbol (main thread only). Seeded from [MarketsLastGood] so a restarted
         *  process shows the last saved rate + time instead of dashes. */
        private val quotes = mutableMapOf<String, MarketsQuotes.Quote>()
        private var seeded = false

        /** Whether a fetch is due: the screen is on and the last one is older than the declared cadence. Pure, for the test. */
        internal fun fetchDue(interactive: Boolean, powerSaving: Boolean, nowMs: Long, lastFetchMs: Long, refreshMs: Long, force: Boolean): Boolean =
            interactive && !powerSaving && (force || nowMs - lastFetchMs >= refreshMs)

        /** The process-level screen-on receiver (application context): a stale price is refreshed when the owner can see it. */
        @Synchronized
        fun ensureReceiver(app: Context) {
            if (receiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (BadgeServices.wanted(app, MarketsBadgeService::class.java.name)) refresh(app, force = false)
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }

        private fun interactive(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)

        private fun badge(): BadgeDeclaration.Badge? =
            BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

        private fun instruments(): List<BadgeDeclaration.Instrument> =
            badge()?.instruments ?: emptyList()

        private fun powerSaving(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        }.getOrDefault(false)

        /** Re-read every time rather than captured once, so a changed option and a change in power-save state both take
         *  effect on the next cycle instead of on the next reboot. */
        private fun refreshMs(ctx: Context): Long {
            val declared = badge()?.let { BadgeCustomization.text(ctx, it, "refresh_minutes") }
                ?.toLongOrNull() ?: DEFAULT_REFRESH_MIN
            val minutes = declared.coerceIn(MIN_REFRESH_MIN, MAX_REFRESH_MIN)
            return minutes * 60_000L * if (powerSaving(ctx)) POWER_SAVE_FACTOR else 1L
        }

        /** A quote older than a few cycles is stale. Derived from the declared
         *  cadence so the two cannot drift apart.
         *  ponytail: a flat multiple of the interval, not a market calendar — a
         *  weekend close correctly reads "2d old"; per-instrument trading hours
         *  only matter if the owner wants "closed" spelled out instead of an age. */
        private fun staleAfterMs(ctx: Context): Long = refreshMs(ctx) * STALE_CYCLES

        /**
         * Redraw, and fetch only when [fetchDue]: the screen is off or power-save is on means the badge is REDRAWN (the
         * age suffixes keep advancing, a frozen price reads as old) but the network is not touched. [done] runs once the
         * card is posted. The next read is one inexact alarm ([DataWidgetRuntime]).
         */
        fun refresh(app: Context, force: Boolean, done: (() -> Unit)? = null) = main.post {
            // Switched off: no fetch and, above all, no next alarm (this used to re-arm it on every start).
            if (DataWidgetRuntime.retiredIfOff(app, MarketsBadgeService::class.java)) { done?.invoke(); return@post }
            if (!seeded) { seeded = true; quotes.putAll(MarketsLastGood(app).load()) }
            DataWidgetRuntime.scheduleNext(app, MarketsBadgeService::class.java, NOTIF_ID, refreshMs(app))
            val list = instruments()
            if (list.isEmpty() || !fetchDue(interactive(app), powerSaving(app), System.currentTimeMillis(), lastFetchMs, refreshMs(app), force)) {
                renotify(app)
                done?.invoke()
                return@post
            }
            lastFetchMs = System.currentTimeMillis()
            scope.launch {
                val fetched = list.map { MarketsQuotes.fetch(it) }
                main.post {
                    // Merged, never cleared: a failed/zero fetch keeps the last
                    // good price (rendered with its saved time), and good prices
                    // are persisted so a process restart does not lose them.
                    val merged = MarketsQuotes.merge(quotes.toMap(), fetched, System.currentTimeMillis())
                    quotes.clear()
                    quotes.putAll(merged)
                    MarketsLastGood(app).save(merged)
                    renotify(app)
                    done?.invoke()
                }
            }
        }

        private fun renotify(ctx: Context) {
            if (!BadgeServices.wanted(ctx, MarketsBadgeService::class.java.name)) return // switched off: the owner retires the card
            runCatching { NotifyGate.postCtx(ctx, NOTIF_ID, build(ctx)) }
        }

        private fun build(ctx: Context): android.app.Notification {
            val list = instruments()
            val now = System.currentTimeMillis()
            val stale = staleAfterMs(ctx)
            val b = badge()
            val showActions = b?.let { BadgeCustomization.bool(ctx, it, "actions") } ?: true

            val text = if (list.isEmpty()) ctx.getString(R.string.badge_markets_no_instruments)
            else MarketsQuotes.summary(list, quotes, now, stale)

            // The WHY, where the owner can read it. Reasons are only present for
            // rows that failed, so a healthy badge expands to just its rows.
            val reasons = list.mapNotNull { quotes[it.symbol]?.reason?.takeIf(String::isNotBlank) }
                .distinct()
            val big = (list.map { MarketsQuotes.row(it, quotes[it.symbol], now, stale) } +
                (if (powerSaving(ctx)) listOf(ctx.getString(R.string.badge_markets_power_saving)) else emptyList()) +
                reasons)
                .joinToString("\n")

            val pinned = BadgeServices.pinned(ctx, BADGE_ID)
            val builder = FleetNotify.dataWidgetBuilder(ctx, R.drawable.ic_stat_notify, ctx.getString(R.string.badge_markets_title), listOf(text),
                    channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(), subText = ctx.getString(R.string.badge_markets_source))
                .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(ctx, NOTIF_ID, MarketsBadgeService::class.java)) }
                .let { NotifyGroups.attach(ctx, it, BADGE_ID) } // #777 group G1
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big.ifBlank { text }))

            // Body tap opens the first declared instrument's ticker page; each
            // button opens its own. Four instruments cannot share one content
            // intent, and Yahoo has no multi-symbol page that answers 200 —
            // /quotes/<csv> is a 404 — so the buttons are the per-instrument route.
            list.firstOrNull()?.let { builder.setContentIntent(tickerPi(ctx, it.url)) }
            if (showActions) {
                // Notification actions cap at five and the compact view shows
                // three; take(4) keeps every declared instrument reachable while
                // leaving the cap alone.
                BadgeModel.add(builder, list.take(4).map { BadgeModel.Action(BadgeModel.idOf(it.label), R.drawable.ic_p_markets_prices, it.label, tickerPi(ctx, it.url)) })
            }
            return builder.build().apply { if (pinned) flags = flags or android.app.Notification.FLAG_NO_CLEAR or android.app.Notification.FLAG_ONGOING_EVENT }
        }

        /**
         * The in-app route, and the only one this badge has. `shortcut_action` is
         * the extra ShellActivity's own router consumes on both a cold start
         * (onCreate) and a warm one (onNewIntent); an http(s) target reaches
         * `launchUri`, which renders it in the embedded browser.
         */
        private fun tickerPi(ctx: Context, url: String): PendingIntent = BadgeClick.pi(ctx, url)

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Market prices", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live FX, crude and gold prices from Yahoo Finance."
                    setShowBadge(false)
                },
            )
        }
    }
}

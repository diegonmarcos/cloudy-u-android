package com.diegonmarcos.cloudnav.maps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.core.BackgroundApps
import com.diegonmarcos.superapp.core.BackgroundCards
import com.diegonmarcos.superapp.core.FleetNotify

/**
 * #1944 What Cloud Nav's "running in the background" card says about navigation, and the card itself.
 *
 * The background runner is [LocationTrackerService] (a location foreground service, alive only from the user's Start); the card is
 * ITS ongoing notification, now in the fleet's one background group ([FleetNotify.GROUP_RUNNING]). Navigation has no background
 * engine of its own: the Navigation tab knows the destination, the maneuver line, distance and ETA while it runs, and publishes
 * them to [NavCardState]; the service card shows them when present. Nothing new runs or polls for this card.
 */
data class NavInfo(
    val destination: String,
    val instruction: String = "",
    val hint: String = "",
    val distance: String = "",
    val eta: String = "",
    val mode: String = "",
)

object NavCardState {
    /** Minimum gap between two card refreshes, so a 1 Hz fix stream is one notification update per few seconds. */
    const val THROTTLE_MS = 5_000L

    @Volatile var info: NavInfo? = null
        private set
    /** Set by the running tracker service: called (throttled) when [info] changes. */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var lastAt = 0L

    fun publish(next: NavInfo?, now: Long = System.currentTimeMillis()) {
        val changed = next != info
        info = next
        if (changed && (next == null || now - lastAt >= THROTTLE_MS)) { lastAt = now; onChange?.invoke() }
    }

    fun clear() = publish(null)

    /**
     * #1965 The declared "Background Apps" Nav fields: Route (destination and mode), Next turn (instruction and hint), Distance,
     * ETA while a route runs, and Tracking (the tracker's own state line) always. Unknown parts ("—", blank) are left out.
     */
    fun fields(i: NavInfo?, tracking: String): List<BackgroundApps.Field> {
        val out = mutableListOf<BackgroundApps.Field>()
        if (i != null && i.destination.isNotBlank()) {
            out += BackgroundApps.Field(BackgroundCards.Nav.ROUTE, i.destination + if (i.mode.isNotBlank()) " · ${i.mode}" else "")
            listOf(i.instruction, i.hint).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
                ?.let { out += BackgroundApps.Field(BackgroundCards.Nav.NEXT_TURN, it) }
            i.distance.takeIf { it.isNotBlank() && it != "—" }?.let { out += BackgroundApps.Field(BackgroundCards.Nav.DISTANCE, it) }
            i.eta.takeIf { it.isNotBlank() && it != "—" }?.let { out += BackgroundApps.Field(BackgroundCards.Nav.ETA, it) }
        }
        out += BackgroundApps.Field(BackgroundCards.Nav.TRACKING, tracking)
        return out
    }

    /** One line each: where to, the next instruction, distance and ETA. A blank part is left out; no info is no lines. */
    fun lines(i: NavInfo?): List<String> {
        if (i == null || i.destination.isBlank()) return emptyList()
        return listOfNotNull(
            "To ${i.destination}" + if (i.mode.isNotBlank()) " · ${i.mode}" else "",
            listOf(i.instruction, i.hint).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
            listOf(i.distance.takeIf { it.isNotBlank() && it != "—" }, i.eta.takeIf { it.isNotBlank() && it != "—" }?.let { "ETA $it" })
                .filterNotNull().joinToString(" · ").ifEmpty { null },
        )
    }
}

object NavCard {
    const val CHANNEL_ID = "maps_tracker"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Location tracker", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent notification while GPS tracking is active."
            setShowBadge(false)
        })
    }

    /** The tracker's ongoing card: [subtitle] (tracking state) first, then the navigation lines when [NavCardState] has them. */
    fun notification(ctx: Context, subtitle: String, nav: NavInfo? = NavCardState.info): Notification {
        val openMain = PendingIntent.getActivity(
            ctx, 0,
            (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val navLines = NavCardState.lines(nav)
        // #1965 the silent MIN "Background service" channel of every fleet keep-alive (was its own LOW maps_tracker channel).
        val b = NotificationCompat.Builder(ctx, FleetNotify.keepAliveChannel(ctx))
            .setSmallIcon(R.drawable.ic_maps_notif)
            .setContentTitle(if (navLines.isNotEmpty()) navLines.first() else "Cloud Maps tracker")
            .setContentText(if (navLines.size > 1) navLines[1] else subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openMain)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup(FleetNotify.GROUP_RUNNING)   // the fleet's one "running in the background" group (was its own nc_maps)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        if (navLines.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText((navLines.drop(1) + subtitle).joinToString("\n")))
        // Persistent in the notification centre: FLAG_NO_CLEAR blocks swipe-to-dismiss + "clear all" while tracking is active.
        val n = b.build().apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
        // #1965 the "Background Apps" card: the declared Nav fields and Open; published by [FleetNotify.post] / [publish].
        return FleetNotify.attachCard(n, BackgroundApps.Card(ctx.packageName, 0, BackgroundCards.NAV, "Cloud Nav",
            if (navLines.isNotEmpty()) navLines.first() else "Tracker running", NavCardState.fields(nav, subtitle),
            listOf(BackgroundApps.Button(BackgroundCards.OPEN, "Open", openMain)), openMain))
    }

    /** #1965 After startForeground (which does not go through FleetNotify): send the card to the hub's group. */
    fun publish(ctx: Context, id: Int, n: Notification) {
        BackgroundApps.fromNotification(n, id)?.let { BackgroundApps.publish(ctx, it) }
    }

    fun summary(ctx: Context): Notification =
        FleetNotify.backgroundSummary(ctx, R.drawable.ic_maps_notif, "Cloud Nav", listOf("Location tracker running"))

    fun postSummary(ctx: Context) = runCatching { FleetNotify.post(ctx, FleetNotify.SUMMARY_ID, summary(ctx)) }
    fun cancelSummary(ctx: Context) = runCatching { FleetNotify.cancelBackgroundSummary(ctx) }
}

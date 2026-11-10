package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetAlerts

/**
 * #777 — the Alerts group in the shade: one child per alert in [AlertStore]
 * (filters applied, newest first, capped at the group's `max_in_shade`) under
 * one "Cloud SA - Alerts" summary. An empty store posts NOTHING.
 *
 * It replaced InfosNotifier, whose four groups and seven messages ("Diego:
 * ping?", "backup: restic → OCI done 03:00", …) were sample data baked from
 * build.json into every APK and shown as if real.
 *
 * Two channels, by severity, and that split is the one thing the old
 * RecoveryNotifier insisted on: "this device cannot update itself" must not
 * share a switch with routine chatter, or silencing one silences the other.
 *
 * Swiping a child away removes that alert ([DismissReceiver]); tapping opens
 * its deep link and leaves it until dismissed or cleared.
 */
object AlertsNotifier {

    const val BADGE_ID = "fleet_alerts"
    /** Channel ids are the user's saved settings — never rename them. The
     *  same id as [FleetAlerts.FALLBACK_CHANNEL] on purpose (FleetAlertsTest):
     *  an alert lands on "Alerts" whichever app ends up posting it. */
    const val CHANNEL_ID = "fleet_alerts"
    const val CHANNEL_URGENT = "fleet_alerts_urgent"
    private const val CHILD_ID = 0xA1E0
    private const val SUMMARY_ID = 0xA1EF
    private const val TAG_PREFIX = "alert:"

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun badge() = BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    /** Re-draw the group from the store. Idempotent; cheap enough to call on
     *  every change. Only [fresh] (the alert just raised) may make a sound —
     *  a re-draw after a reboot or a filter change must not ring N times.
     *  Returns the alerts actually posted. */
    @Synchronized
    fun refresh(ctx: Context, fresh: String? = null): List<AlertStore.Alert> {
        val g = NotifyGroups.alertsGroup
        val on = g != null && badge()?.let { BadgeCustomization.isEnabled(ctx, it) } == true
        // The Alerts group and badge are gone (notify redesign 2026-11-08), but an alert the owner can ACT on (the mesh-down
        // Reconnect) still reaches the shade: a plain ungrouped notification, no group, no summary.
        val shown = if (on) AlertStore.visible(ctx).take(g!!.maxInShade)
        else AlertStore.visible(ctx).filter { it.dedupeKey == MeshDownAlert.DEDUPE }
        val nm = nm(ctx)
        val keep = shown.map { TAG_PREFIX + it.id }.toSet()
        runCatching {
            nm.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag !in keep }
                .forEach { NotifyGate.invalidate(it.tag, it.id); nm.cancel(it.tag, it.id) }
        }
        if (shown.isEmpty()) { NotifyGate.invalidate(null, SUMMARY_ID); runCatching { nm.cancel(SUMMARY_ID) }; return emptyList() }
        ensureChannels(ctx)
        val key = g?.let(NotifyGroups::key)
        // #883 Jev may make the FRESH alert arrive silently; no opinion = it rings, as before
        val quiet = if (fresh == null) emptySet() else JevNotify.quietIds(ctx, shown.firstOrNull { it.id == fresh })
        for (a in shown) runCatching {
            NotifyGate.post(nm, TAG_PREFIX + a.id, CHILD_ID, NotificationCompat.Builder(ctx, channelFor(a.severity))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF0A0A0A.toInt())
                .setContentTitle(a.title)
                .setContentText(a.text)
                .setSubText("${appLabel(ctx, a.app)} · ${a.severity}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
                .setWhen(a.ts).setShowWhen(true)
                .apply { if (key != null) setGroup(key) }
                .setSortKey("%020d".format(Long.MAX_VALUE - a.ts))
                .setOnlyAlertOnce(true)
                // #1596 Focus Mode holds back non-essential interruptions: while Focus is on a
                // non-urgent alert posts silently (no sound, no heads-up). An ERROR still rings.
                .setSilent(silent(a.id, fresh, quiet) || focusSilences(ctx, a.severity))
                .apply { contentIntent(ctx, a)?.let { setContentIntent(it) } }
                .apply { BadgeModel.add(this, MeshDownAlert.actionsFor(ctx, a)) }
                .setDeleteIntent(dismissIntent(ctx, a.id))
                .build())
        }
        if (g == null || key == null) return shown
        val pinned = BadgeServices.pinned(ctx, BADGE_ID)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle("Cloud SA - Alerts")
        shown.take(5).forEach { inbox.addLine("${appLabel(ctx, it.app)} · ${it.title}") }
        inbox.setSummaryText("${shown.size} alert${if (shown.size == 1) "" else "s"}")
        runCatching {
            NotifyGate.post(nm, null, SUMMARY_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF0A0A0A.toInt())
                .setContentTitle("Cloud SA - Alerts")
                .setContentText("${shown.size} alert${if (shown.size == 1) "" else "s"}")
                .setNumber(shown.size)
                .setStyle(inbox)
                .setGroup(key)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSortKey("%02d".format(NotifyGroups.ordered(ctx).indexOf(g)))
                .setOngoing(pinned)
                .setOnlyAlertOnce(true)
                .build()
                .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR })
        }
        return shown
    }

    /** Only the [fresh] alert may make a sound, and not when Jev judged it not worth one ([quiet] is empty without an opinion). */
    internal fun silent(alertId: String, fresh: String?, quiet: Set<String>): Boolean = alertId != fresh || alertId in quiet

    /** #1596 True when Focus Mode is on AND this alert is not urgent — the non-essential ones it quietens. */
    internal fun focusSilences(ctx: Context, severity: String): Boolean =
        severity != FleetAlerts.ERROR && com.diegonmarcos.superapp.settings.FocusMode.active(ctx)

    fun channelFor(severity: String) = if (severity == FleetAlerts.ERROR) CHANNEL_URGENT else CHANNEL_ID

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun contentIntent(ctx: Context, a: AlertStore.Alert): PendingIntent? =
        FleetAlerts.intentFor(ctx, a.app, a.deepLink, MainActivity::class.java)?.let {
            PendingIntent.getActivity(ctx, a.id.hashCode(), it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun dismissIntent(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, DismissReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = nm(ctx)
        if (nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Alerts raised by the Cloud apps." })
        if (nm.getNotificationChannel(CHANNEL_URGENT) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_URGENT, "Urgent alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Errors a Cloud app cannot fix by itself — e.g. this phone can no longer update." })
    }

    private const val EXTRA_ID = "alert_id"

    /** Swipe on one alert = that alert is done. Not exported: only our own
     *  PendingIntent reaches it. */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val gone = AlertStore.all(ctx).firstOrNull { it.id == id }
            AlertStore.remove(ctx, id)
            JevNotify.settle(ctx, gone)
            refresh(ctx)
        }
    }
}

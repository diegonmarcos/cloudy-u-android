package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration.Group

/**
 * #777 — the four shade groups of Configs ▸ Launcher ▸ Notify:
 *
 *   live    Markets · Health · Weather
 *   actions Quick Actions · KDE Connect
 *   media   Media Player
 *   alerts  Alerts (the fleet alerts channel, [AlertsNotifier])
 *
 * Declared in `build.json::ui.notification_center.groups`; nothing here names
 * a group or a badge. Each producer passes its builder through [attach], which
 * gives it its group's key and posts that group's summary, so the shade shows
 * one bundle per group. Before this each badge carried a group of its own
 * ("nc_markets", "nc_kde", …), which Android draws as seven loose rows.
 *
 * What the owner sets per group — on/off and order — lives here. A group
 * switched off switches its members off ([BadgeCustomization.isEnabled] asks
 * [allows]), so the restart path, the Launch button and the producers all
 * obey it without knowing groups exist.
 *
 * ORDER, honestly: Android ranks notifications across groups itself (by
 * importance, ongoing state and recency of the best member), and an app
 * cannot pin one group above another in the shade. The order drives the
 * Notify page and the summaries' sort keys, and that is all it can drive.
 */
object NotifyGroups {

    private const val PREFS = "notify_groups"
    private const val KEY_ORDER = "order"
    const val SUMMARY_CHANNEL = "nc_groups"
    private const val SUMMARY_ID = 0x6C00

    val declared: List<Group> by lazy { BadgeDeclaration.groups(BadgeServices.declaredJson) }

    /** The group whose member is the fleet alerts channel, or null. */
    val alertsGroup: Group? get() = declared.firstOrNull { it.alerts }

    fun groupOf(badgeId: String): Group? = declared.firstOrNull { badgeId in it.members }

    fun key(g: Group) = "com.diegonmarcos.superapp.nc.${g.id}"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, g: Group): Boolean = prefs(ctx).getBoolean("${g.id}.enabled", true)

    fun setEnabled(ctx: Context, g: Group, on: Boolean) {
        prefs(ctx).edit().putBoolean("${g.id}.enabled", on).apply()
        apply(ctx)
    }

    /** Does [badgeId]'s group let it into the shade? A badge in no group: yes. */
    fun allows(ctx: Context, badgeId: String): Boolean = groupOf(badgeId)?.let { isEnabled(ctx, it) } ?: true

    /** The owner's order: saved ids first, then any group declared since, in
     *  declared order. A saved id no longer declared is simply skipped. */
    fun ordered(ctx: Context): List<Group> {
        val byId = declared.associateBy { it.id }
        val saved = prefs(ctx).getString(KEY_ORDER, "").orEmpty().split(',').mapNotNull { byId[it] }
        return (saved + declared).distinct()
    }

    /** Move [g] by [delta] places (−1 = up). */
    fun move(ctx: Context, g: Group, delta: Int) {
        val l = ordered(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == g.id }
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i < 0 || i == j) return
        l.add(j, l.removeAt(i))
        prefs(ctx).edit().putString(KEY_ORDER, l.joinToString(",") { it.id }).apply()
        for (x in l) if (!x.alerts && isEnabled(ctx, x)) postSummary(ctx, x)
        AlertsNotifier.refresh(ctx)
    }

    private fun rank(ctx: Context, g: Group) = "%02d".format(ordered(ctx).indexOfFirst { it.id == g.id })

    /**
     * Put [badgeId]'s notification in its group. The summary is posted FIRST,
     * here, because a summary decided from `activeNotifications` after the
     * child's notify() races the platform's own enqueue.
     */
    fun attach(ctx: Context, b: NotificationCompat.Builder, badgeId: String): NotificationCompat.Builder {
        val g = groupOf(badgeId) ?: return b
        if (!g.alerts) postSummary(ctx, g)
        return b.setGroup(key(g)).setSortKey("%02d".format(memberSortIndex(g, badgeId)))
    }

    /** A member's place inside its group's shade bundle: the SAME card.rank
     *  order the Notify page renders ([BadgeDeclaration.rankOrder]), so the one
     *  build.json declaration drives both surfaces and they cannot drift. Falls
     *  back to plain declaration order when a member is not resolvable. */
    private fun memberSortIndex(g: Group, badgeId: String): Int {
        val members = BadgeDeclaration.rankOrder(
            BadgeServices.declared,
            g.members.mapNotNull { id -> BadgeServices.declared.firstOrNull { it.id == id } },
        )
        return members.indexOfFirst { it.id == badgeId }.let { if (it >= 0) it else g.members.indexOf(badgeId) }
    }

    /** Summary text for the alerts group is [AlertsNotifier]'s; every other
     *  group's summary just names its members, pinned so "Clear all" cannot
     *  strip the bundle off its ongoing children. */
    fun postSummary(ctx: Context, g: Group) {
        if (!isEnabled(ctx, g)) { cancelSummary(ctx, g); return }
        val nm = nm(ctx)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(SUMMARY_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(
                SUMMARY_CHANNEL, "Notification groups", NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) })
        runCatching {
            NotifyGate.post(nm, g.id, SUMMARY_ID, NotificationCompat.Builder(ctx, SUMMARY_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF0A0A0A.toInt())
                .setContentTitle(g.label)
                .setSubText("Cloud SA")
                .setGroup(key(g))
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSortKey(rank(ctx, g))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build())
        }
    }

    fun cancelSummary(ctx: Context, g: Group) = runCatching { NotifyGate.invalidate(g.id, SUMMARY_ID); nm(ctx).cancel(g.id, SUMMARY_ID) }

    /** [badgeId] is leaving the shade: drop its group's summary unless another
     *  member is still posted. The leaving badge is excluded by its declared
     *  channel — it may still be listed while its service is being destroyed. */
    fun release(ctx: Context, badgeId: String) {
        val g = groupOf(badgeId) ?: return
        val mine = BadgeServices.declared.firstOrNull { it.id == badgeId }?.channel
        val k = key(g)
        val others = runCatching {
            nm(ctx).activeNotifications.any {
                it.notification.group == k &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    it.notification.channelId != mine
            }
        }.getOrDefault(true)
        if (!others) cancelSummary(ctx, g)
    }

    /**
     * Make a switch take effect NOW: stop every badge service none of whose
     * badges is still wanted (the floating button's host stays while the
     * button is armed, #775), start the wanted ones, and re-post what the
     * running ones own so a member switched off leaves the shade.
     */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val nav = FloatingNavService::class.java.name
        BadgeDeclaration.badges(BadgeServices.declared).map { it.service }.filter(String::isNotBlank).distinct()
            .filterNot { BadgeServices.wanted(app, it) || (it == nav && FloatingNavService.armed) }
            .forEach {
                runCatching { app.stopService(Intent(app, Class.forName(it))) }
                // A data widget's card is not tied to its service: switching it off must take the card down itself.
                DataWidgetRuntime.of(it)?.let { w -> DataWidgetRuntime.retire(app, w) }
            }
        runCatching { BadgeServices.ensureAll(app) }
        if (FloatingNavService.isRunning)
            runCatching { ContextCompat.startForegroundService(app, Intent(app, FloatingNavService::class.java)) }
        AlertsNotifier.refresh(app)
        for (g in declared) if (!g.alerts && !isEnabled(app, g)) cancelSummary(app, g)
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

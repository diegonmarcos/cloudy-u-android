package com.diegonmarcos.superapp.notificationcenter

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The data-widget badges (Weather, MyHealth, Battery, Data, AI, Markets) are standing INFORMATION cards, not keep-alives: each is posted with
 * plain notify ([com.diegonmarcos.superapp.core.FleetNotify.dataWidgetNotification]) and its owner service is a short
 * job (post, schedule the next read, stopSelf). None of them calls startForeground or declares a foregroundServiceType,
 * so Android no longer lists the app under "Background activities / app is running in the background" because of them.
 *
 * What a foreground service gave them, and what replaces it:
 *  - being started: [start] is a plain startService (the app is in the foreground, or a boot / update broadcast has the
 *    short allowlist), never startForegroundService;
 *  - periodic refresh: [scheduleNext], one INEXACT alarm per widget (no permission, batched by the system), never a
 *    handler loop in a held process;
 *  - leaving the shade when switched off: [retire], because a plain notification is not removed by stopping a service.
 */
object DataWidgetRuntime {

    class Widget(val cls: Class<*>, val notifId: Int, val badgeId: String)

    /** Every data-widget badge. A badge here must never call startForeground: DataWidgetNotFgsTest holds that. */
    val widgets: List<Widget> = listOf(
        Widget(WeatherBadgeService::class.java, WeatherBadgeService.NOTIF_ID, WeatherBadgeService.BADGE_ID),
        Widget(HealthBadgeService::class.java, HealthBadgeService.NOTIF_ID, HealthBadgeService.BADGE_ID),
        Widget(BatteryBadgeService::class.java, BatteryBadgeService.NOTIF_ID, BatteryBadgeService.BADGE_ID),
        Widget(DataBadgeService::class.java, DataBadgeService.NOTIF_ID, DataBadgeService.BADGE_ID),
        Widget(AiBadgeService::class.java, AiBadgeService.NOTIF_ID, AiBadgeService.BADGE_ID),
        Widget(MarketsBadgeService::class.java, MarketsBadgeService.NOTIF_ID, MarketsBadgeService.BADGE_ID),
    )

    fun of(fqcn: String): Widget? = widgets.firstOrNull { it.cls.name == fqcn }

    fun isWidgetService(fqcn: String): Boolean = of(fqcn) != null

    /** Plain start: the owner posts and stops itself, so no foreground notification is owed within 5 s. */
    fun start(ctx: Context, cls: Class<*>) {
        ctx.startService(Intent(ctx, cls))
    }

    private fun pi(ctx: Context, cls: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getService(ctx, requestCode, Intent(ctx, cls), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One inexact, idle-tolerant alarm that starts [cls] again after about [delayMs]; replaces the previous one. */
    fun scheduleNext(ctx: Context, cls: Class<*>, requestCode: Int, delayMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi(ctx, cls, requestCode))
        }
    }

    /** The widget is switched off: no more reads, and its card leaves the shade (with its group summary if it was the last). */
    fun retire(ctx: Context, w: Widget) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pi(ctx, w.cls, w.notifId)) }
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(w.notifId) }
        NotifyGroups.release(ctx, w.badgeId)
    }
}

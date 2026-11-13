package com.diegonmarcos.superapp.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * THE two kinds of persistent notification a fleet app may post. Android files a notification under
 * "Background activities / app is running in the background" exactly when it is the notification of a
 * foreground service, so the split is the whole point:
 *
 *  1. [keepAliveNotification] is the notification of an app that IS being kept alive (a service holding the
 *     process: the web server's servers, the terminals' sessions and sshd, Mail's push connection, the Store's
 *     update queue). It is ongoing, cannot be swiped while the service lives, carries live data (a title and
 *     lines) and a Stop action, and goes to [startKeepAlive] (startForeground with the right type). It lives on
 *     the dedicated "Running / Keep-alive" channel: low importance, no sound.
 *
 *  2. [dataWidgetNotification] is a standing information card (Weather, Health, Battery). It is ongoing and
 *     pinned, on its own channel, but it is posted with plain NotificationManager.notify ([post]) and is never
 *     handed to startForeground, so it is NOT a foreground-service notification and Android does not list the
 *     app as "kept alive" for it. Nothing here starts or needs a service; the owner schedules its refreshes.
 *
 * Guard: FleetNotifyTest (JVM/Robolectric).
 */
object FleetNotify {
    const val CHANNEL_KEEP_ALIVE = "fleet_keep_alive"
    const val CHANNEL_KEEP_ALIVE_NAME = "Running / Keep-alive"
    const val CHANNEL_DATA_WIDGET = "fleet_data_widget"
    const val CHANNEL_DATA_WIDGET_NAME = "Info widgets"
    const val STOP_LABEL = "Stop"

    /** An extra button (the terminals' wake-lock toggle). */
    class Action(val icon: Int, val label: String, val intent: PendingIntent)

    /**
     * The shared channels are made through [SilentChannels.ensure] like every channel of the fleet (Cloud Store's
     * notifications are silent: there it is a `_silent_v2` id, and the id returned is the one to post on). Idempotent: a
     * channel that exists is never touched, so what the user set in Android's settings is kept. LOW importance, no sound.
     */
    @JvmStatic
    fun keepAliveChannel(ctx: Context): String =
        SilentChannels.ensure(ctx, CHANNEL_KEEP_ALIVE, CHANNEL_KEEP_ALIVE_NAME, NotificationManager.IMPORTANCE_LOW) {
            description = "An app that is kept running in the background, with what it is doing."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

    private fun dataWidgetChannel(ctx: Context): String =
        SilentChannels.ensure(ctx, CHANNEL_DATA_WIDGET, CHANNEL_DATA_WIDGET_NAME, NotificationManager.IMPORTANCE_LOW) {
            description = "Standing information cards (weather, health, battery)."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

    /** The one text rule: first line is the collapsed text, every line is the expanded one. */
    @JvmStatic
    fun body(lines: List<String>): String = lines.filter { it.isNotBlank() }.joinToString("\n")

    private fun first(lines: List<String>): String = lines.firstOrNull { it.isNotBlank() }.orEmpty()

    // ── 1. keep-alive (foreground service) ───────────────────────────────

    /**
     * The notification of a service that keeps the app alive. [stop] is the Stop action's intent (the service's
     * own ACTION_STOP, labelled [stopLabel] so an app may localise it); [extraActions] are further buttons. Pass it to [startKeepAlive], then refresh it with
     * [post] under the same id as the data changes.
     */
    @JvmStatic
    @JvmOverloads
    fun keepAliveNotification(
        ctx: Context,
        smallIcon: Int,
        title: String,
        lines: List<String>,
        stop: PendingIntent,
        contentIntent: PendingIntent? = null,
        extraActions: List<Action> = emptyList(),
        channelId: String? = null,
        color: Int = 0,
        stopLabel: String = STOP_LABEL,
    ): Notification {
        val channel = channelId ?: keepAliveChannel(ctx)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(first(lines))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(lines)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (color != 0) b.setColor(color)
        if (contentIntent != null) b.setContentIntent(contentIntent)
        b.addAction(0, stopLabel, stop)
        extraActions.forEach { b.addAction(it.icon, it.label, it.intent) }
        // Android 14 made foreground-service notifications swipeable; NO_CLEAR keeps it for the service's life.
        return b.build().apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    /** startForeground with a type; [type] 0 means none (below the API that has types). */
    @JvmStatic
    @JvmOverloads
    fun startKeepAlive(service: Service, id: Int, notification: Notification, type: Int = specialUseType()) {
        ServiceCompat.startForeground(service, id, notification, type)
    }

    /** specialUse where the platform has it (API 34), else 0: the type of a keep-alive that fits no predefined one. */
    @JvmStatic
    fun specialUseType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    /** dataSync where the platform has it (API 29), else 0. */
    @JvmStatic
    fun dataSyncType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    // ── 2. data widget (NOT a foreground service) ────────────────────────

    /**
     * The builder of a standing info card, for an owner that adds its own touches (group, actions) before build().
     * [pinned] false lets the user swipe it. It sets no foreground-service behaviour and starts nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun dataWidgetBuilder(
        ctx: Context,
        smallIcon: Int,
        title: String,
        lines: List<String>,
        contentIntent: PendingIntent? = null,
        channelId: String? = null,
        pinned: Boolean = true,
        color: Int = 0,
        subText: String? = null,
    ): NotificationCompat.Builder {
        val channel = channelId ?: dataWidgetChannel(ctx)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(first(lines))
            .setOngoing(pinned)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (lines.size > 1) b.setStyle(NotificationCompat.BigTextStyle().bigText(body(lines)))
        if (color != 0) b.setColor(color)
        if (subText != null) b.setSubText(subText)
        if (contentIntent != null) b.setContentIntent(contentIntent)
        return b
    }

    @JvmStatic
    @JvmOverloads
    fun dataWidgetNotification(
        ctx: Context,
        smallIcon: Int,
        title: String,
        lines: List<String>,
        contentIntent: PendingIntent? = null,
        channelId: String? = null,
        pinned: Boolean = true,
        color: Int = 0,
        subText: String? = null,
    ): Notification {
        val n = dataWidgetBuilder(ctx, smallIcon, title, lines, contentIntent, channelId, pinned, color, subText).build()
        return if (pinned) n.apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT } else n
    }

    /** Plain notify: how a data widget (and a keep-alive refresh) is posted. Never startForeground. */
    @JvmStatic
    fun post(ctx: Context, id: Int, notification: Notification) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }
}

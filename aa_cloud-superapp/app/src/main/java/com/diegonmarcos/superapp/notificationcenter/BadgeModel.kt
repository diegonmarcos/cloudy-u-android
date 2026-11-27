package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.PendingIntent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

/**
 * One model for a badge's buttons. A badge's notification and its Notify card are two renderings of the SAME declaration:
 * a producer states its actions here (icon, label, intent) and its notification gets them through [add], the only place
 * in the badge code that calls `addAction`. The Notify card reads them back with [of] from the notification the badge
 * posted, so whatever the notification centre carries the card carries: no producer has a card-side action list, and a
 * card cannot define its own (NotifyCardParityTest fails if one appears).
 */
object BadgeModel {

    /** One tappable action: [id] is stable per action (also its test tag), [icon] a thin-line drawable (never 0). */
    data class Action(val id: String, @DrawableRes val icon: Int, val label: String, val intent: PendingIntent,
                      /** #1966 an app's real launcher icon; [icon] stays the non-zero fallback the Notify card reads. */
                      val iconBitmap: android.graphics.Bitmap? = null)

    /** The only `addAction` of a badge notification. An icon-less action is a bug here, not a blank button on the card. */
    fun add(b: NotificationCompat.Builder, actions: List<Action>): NotificationCompat.Builder {
        for (a in actions) {
            require(a.icon != 0) { "badge action '${a.label}' has no icon" }
            if (a.iconBitmap != null) b.addAction(NotificationCompat.Action.Builder(
                androidx.core.graphics.drawable.IconCompat.createWithBitmap(a.iconBitmap), a.label, a.intent).build())
            else b.addAction(a.icon, a.label, a.intent)
        }
        return b
    }

    fun add(b: NotificationCompat.Builder, action: Action?): NotificationCompat.Builder =
        add(b, listOfNotNull(action))

    /** A stable id from a label: "Allow location" -> "allow-location". */
    fun idOf(label: String): String = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "action" }

    /** What a notification carries, in order: the actions the card draws as icon buttons. */
    @Suppress("DEPRECATION")
    fun of(n: Notification): List<Action> = (n.actions ?: emptyArray()).mapNotNull { a ->
        val pi = a.actionIntent ?: return@mapNotNull null
        val label = a.title?.toString().orEmpty()
        val res = a.icon.takeIf { it != 0 }
            ?: if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { a.getIcon()?.resId ?: 0 }.getOrDefault(0) else 0
        Action(idOf(label), res, label, pi)
    }
}

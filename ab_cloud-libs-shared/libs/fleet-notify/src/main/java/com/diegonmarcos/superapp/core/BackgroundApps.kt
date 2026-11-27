package com.diegonmarcos.superapp.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * #1965 THE ONE cross-app "Background Apps" group.
 *
 * Android groups notifications per posting package, so #1944's shared group key ([FleetNotify.GROUP_RUNNING]) could never put
 * Cloud Mail, Cloud Store, Cloud Nav and the WebServer under one header: each app got its own group of one. The design here:
 *
 *  - ONE owner posts the group: the hub ([HUB_PACKAGE], the SuperApp, top of the constellation). It posts a summary
 *    ([TITLE]) and one rich child card per running app, on its own visible LOW channel [CHANNEL_HUB].
 *  - Each app keeps its legal foreground-service notification (startForeground, FGS type, POST_NOTIFICATIONS unchanged), but
 *    that card moves to the MIN "Background service" channel ([FleetNotify.CHANNEL_BACKGROUND_SERVICE]): silent, collapsed
 *    below, and still carrying every line it had (rule 16: the hub card is a superset, the app card loses nothing).
 *  - The data travels as a [Card] (declared fields + buttons, see [BackgroundCards]) in an EXPLICIT broadcast to the hub's
 *    [Receiver], guarded both ways by the fleet's signature permission [PERMISSION] (one shared signing key, SIGNING.md).
 *    [FleetNotify.startKeepAlive] / [FleetNotify.post] publish automatically for every keep-alive built by
 *    [FleetNotify.keepAliveNotification], so every fleet app with a background service joins without its own IPC.
 *  - The buttons are the app's OWN PendingIntents carried in the card: a tap acts in that app (Sync now, Pause, Stop, Open).
 *  - Removal: [FleetNotify.cancelBackgroundSummary] (every #1944 runner calls it on stop) and [retract] send ACTION_RETRACT;
 *    the hub's notification listener also drops a card whose source notification left the shade (process death).
 *
 * Guard: BackgroundAppsTest (Robolectric).
 */
object BackgroundApps {
    const val HUB_PACKAGE = "com.diegonmarcos.superapp"
    const val ACTION_CARD = "com.diegonmarcos.fleet.action.BACKGROUND_CARD"
    const val ACTION_RETRACT = "com.diegonmarcos.fleet.action.BACKGROUND_CARD_RETRACT"
    const val PERMISSION = "com.diegonmarcos.fleet.permission.BACKGROUND_CARD"

    const val GROUP = "fleet_background_apps"
    const val TITLE = "Background Apps"
    const val CHANNEL_HUB = "fleet_background_apps"
    const val CHANNEL_HUB_NAME = "Background Apps"
    const val SUMMARY_TAG = "bg:summary"
    const val SUMMARY_ID = 0x6C65

    // Card wire format (a Bundle; the PendingIntents are Parcelable and cross the process boundary as they are).
    const val X_MARK = "fleet.bg.card"
    private const val X_PKG = "fleet.bg.pkg"
    private const val X_ID = "fleet.bg.id"
    private const val X_KIND = "fleet.bg.kind"
    private const val X_APP = "fleet.bg.app"
    private const val X_TITLE = "fleet.bg.title"
    private const val X_KEYS = "fleet.bg.keys"
    private const val X_VALUES = "fleet.bg.values"
    private const val X_BTN_KINDS = "fleet.bg.btn.kinds"
    private const val X_BTN_LABELS = "fleet.bg.btn.labels"
    private const val X_BTN_INTENTS = "fleet.bg.btn.intents"
    private const val X_CONTENT = "fleet.bg.content"
    private const val X_AT = "fleet.bg.at"
    /** On a hub child: the one line the summary shows for it. */
    const val X_SUMMARY_LINE = "fleet.bg.summary"
    const val X_SOURCE_PKG = "fleet.bg.source.pkg"
    const val X_SOURCE_ID = "fleet.bg.source.id"

    /** One field: [key] blank means a free line (an app that declared no keys, e.g. a fork's keep-alive). */
    data class Field(val key: String, val value: String)

    /** One icon button. [kind] picks the hub's icon ([iconFor]); [intent] is the publishing app's own action. */
    class Button(val kind: String, val label: String, val intent: PendingIntent)

    data class Card(
        val pkg: String,
        val id: Int,
        val kind: String,
        val app: String,
        val title: String,
        val fields: List<Field>,
        val buttons: List<Button> = emptyList(),
        val content: PendingIntent? = null,
        val at: Long = System.currentTimeMillis(),
    ) {
        fun keys(): List<String> = fields.map { it.key }.filter { it.isNotEmpty() }.distinct()
        fun buttonKinds(): List<String> = buttons.map { it.kind }
    }

    /** The one text rule of a field: "Key: value", or the bare value for a free line. Blank values are left out. */
    fun line(f: Field): String = if (f.key.isEmpty()) f.value else "${f.key}: ${f.value}"

    fun lines(card: Card): List<String> = card.fields.filter { it.value.isNotBlank() }.map(::line)

    /** The summary's one line for an app: its name and its headline. */
    fun summaryLine(card: Card): String = listOf(card.app, card.title).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /** Free lines (an app's existing keep-alive lines) as fields with no key. */
    fun freeFields(lines: List<String>): List<Field> = lines.filter { it.isNotBlank() }.map { Field("", it) }

    fun toBundle(card: Card): Bundle = Bundle().apply {
        putBoolean(X_MARK, true)
        putString(X_PKG, card.pkg)
        putInt(X_ID, card.id)
        putString(X_KIND, card.kind)
        putString(X_APP, card.app)
        putString(X_TITLE, card.title)
        putStringArrayList(X_KEYS, ArrayList(card.fields.map { it.key }))
        putStringArrayList(X_VALUES, ArrayList(card.fields.map { it.value }))
        putStringArrayList(X_BTN_KINDS, ArrayList(card.buttons.map { it.kind }))
        putStringArrayList(X_BTN_LABELS, ArrayList(card.buttons.map { it.label }))
        putParcelableArray(X_BTN_INTENTS, card.buttons.map { it.intent }.toTypedArray())
        card.content?.let { putParcelable(X_CONTENT, it) }
        putLong(X_AT, card.at)
    }

    @Suppress("DEPRECATION")
    fun fromBundle(b: Bundle?): Card? {
        if (b == null || !b.getBoolean(X_MARK)) return null
        val keys = b.getStringArrayList(X_KEYS).orEmpty()
        val values = b.getStringArrayList(X_VALUES).orEmpty()
        val kinds = b.getStringArrayList(X_BTN_KINDS).orEmpty()
        val labels = b.getStringArrayList(X_BTN_LABELS).orEmpty()
        val intents = b.getParcelableArray(X_BTN_INTENTS).orEmpty().map { it as? PendingIntent }
        return Card(
            pkg = b.getString(X_PKG).orEmpty(),
            id = b.getInt(X_ID),
            kind = b.getString(X_KIND).orEmpty(),
            app = b.getString(X_APP).orEmpty(),
            title = b.getString(X_TITLE).orEmpty(),
            fields = keys.indices.filter { it < values.size }.map { Field(keys[it], values[it]) },
            buttons = kinds.indices.mapNotNull { i -> intents.getOrNull(i)?.let { Button(kinds[i], labels.getOrElse(i) { kinds[i] }, it) } },
            content = b.getParcelable(X_CONTENT),
            at = b.getLong(X_AT),
        )
    }

    /** The card a keep-alive notification carries (put there by [FleetNotify.keepAliveNotification]), with the id it is posted under. */
    fun fromNotification(n: Notification, id: Int): Card? = fromBundle(n.extras.getBundle(X_MARK))?.copy(id = id)

    // ── publisher side (any fleet app) ───────────────────────────────────

    /** Send [card] to the hub. Never throws: the app's own card stays whatever happens here. The hub posting for itself skips IPC. */
    fun publish(ctx: Context, card: Card) {
        runCatching {
            if (ctx.packageName == HUB_PACKAGE) { hubPost(ctx, card); return }
            // INCLUDE_STOPPED: a hub never opened since its install is in the stopped state and would get nothing.
            val i = Intent(ACTION_CARD).setPackage(HUB_PACKAGE).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra(X_MARK, toBundle(card))
            ctx.sendBroadcast(i, PERMISSION)
        }
    }

    /** Take this app's card ([id]) or all its cards ([id] null) out of the group. */
    fun retract(ctx: Context, id: Int? = null) {
        runCatching {
            if (ctx.packageName == HUB_PACKAGE) { hubRetract(ctx, ctx.packageName, id); return }
            val i = Intent(ACTION_RETRACT).setPackage(HUB_PACKAGE).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra(X_PKG, ctx.packageName)
            if (id != null) i.putExtra(X_ID, id)
            ctx.sendBroadcast(i, PERMISSION)
        }
    }

    // ── hub side (the SuperApp) ──────────────────────────────────────────

    fun hubChannel(ctx: Context): String =
        SilentChannels.ensure(ctx, CHANNEL_HUB, CHANNEL_HUB_NAME, NotificationManager.IMPORTANCE_LOW) {
            description = "One card per fleet app running in the background, with what it is doing and its buttons."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

    fun tagOf(pkg: String): String = "bg:$pkg"

    /** The hub's icon for a button kind: the hub draws the icons, an icon id of another package means nothing here. */
    fun iconFor(kind: String): Int = when (kind) {
        BackgroundCards.SYNC -> android.R.drawable.ic_popup_sync
        BackgroundCards.PAUSE -> android.R.drawable.ic_media_pause
        BackgroundCards.STOP -> android.R.drawable.ic_menu_close_clear_cancel
        BackgroundCards.OPEN -> android.R.drawable.ic_menu_view
        else -> android.R.drawable.ic_menu_more
    }

    fun smallIconFor(kind: String): Int = when (kind) {
        BackgroundCards.MAIL -> android.R.drawable.ic_dialog_email
        BackgroundCards.STORE -> android.R.drawable.stat_sys_download
        BackgroundCards.NAV -> android.R.drawable.ic_menu_mylocation
        BackgroundCards.WEBSERVER -> android.R.drawable.stat_sys_upload
        else -> android.R.drawable.stat_notify_sync
    }

    /** One app's rich child card in the group: its headline, every field, its buttons with icons, a tap that opens it. */
    fun childNotification(ctx: Context, card: Card): Notification {
        val lines = lines(card)
        val b = NotificationCompat.Builder(ctx, hubChannel(ctx))
            .setSmallIcon(smallIconFor(card.kind))
            .setContentTitle(listOf(card.app, card.title).filter { it.isNotBlank() }.distinct().joinToString(" · "))
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSubText(card.app)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey(card.app)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setShowWhen(true).setWhen(card.at)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(Bundle().apply {
                putString(X_SUMMARY_LINE, summaryLine(card))
                putString(X_SOURCE_PKG, card.pkg)
                putInt(X_SOURCE_ID, card.id)
            })
        card.content?.let { b.setContentIntent(it) }
        card.buttons.forEach { b.addAction(iconFor(it.kind), it.label, it.intent) }
        return b.build().apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    /** The group header: [TITLE], one line per running app. */
    fun summaryNotification(ctx: Context, lines: List<String>): Notification {
        val style = NotificationCompat.InboxStyle().setBigContentTitle(TITLE)
        lines.forEach { style.addLine(it) }
        style.setSummaryText("${lines.size} running")
        return NotificationCompat.Builder(ctx, hubChannel(ctx))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(TITLE)
            .setContentText(lines.joinToString(" · "))
            .setStyle(style)
            .setGroup(GROUP).setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hubPost(ctx: Context, card: Card) {
        nm(ctx).notify(tagOf(card.pkg), card.id, childNotification(ctx, card))
        refreshSummary(ctx)
    }

    fun hubRetract(ctx: Context, pkg: String, id: Int?) {
        val m = nm(ctx)
        m.activeNotifications.filter { it.tag == tagOf(pkg) && (id == null || it.id == id) }.forEach { m.cancel(it.tag, it.id) }
        refreshSummary(ctx)
    }

    /** Drop every child whose source (package, id) is not in [live]; the hub's own cards are judged by the hub's own posts. */
    fun reconcile(ctx: Context, live: Set<Pair<String, Int>>) {
        val m = nm(ctx)
        m.activeNotifications.filter { it.tag != SUMMARY_TAG && it.notification.group == GROUP }.forEach {
            val pkg = it.notification.extras.getString(X_SOURCE_PKG) ?: return@forEach
            if (pkg != ctx.packageName && (pkg to it.notification.extras.getInt(X_SOURCE_ID)) !in live) m.cancel(it.tag, it.id)
        }
        refreshSummary(ctx)
    }

    /** The summary follows the children: rebuilt from the hub's own live cards, removed with the last one. */
    fun refreshSummary(ctx: Context) {
        val m = nm(ctx)
        val children = m.activeNotifications.filter { it.tag != SUMMARY_TAG && it.notification.group == GROUP }
        if (children.isEmpty()) { m.cancel(SUMMARY_TAG, SUMMARY_ID); return }
        val lines = children.mapNotNull { it.notification.extras.getString(X_SUMMARY_LINE) }.sorted()
        m.notify(SUMMARY_TAG, SUMMARY_ID, summaryNotification(ctx, lines))
    }

    /**
     * The hub's door, declared in the SuperApp manifest only (exported, android:permission=[PERMISSION]): a card or a retraction
     * from a fleet app. The sender package of a retraction is the one in the intent; a card names its own package.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                when (intent.action) {
                    ACTION_CARD -> fromBundle(intent.getBundleExtra(X_MARK))?.let { hubPost(context, it) }
                    ACTION_RETRACT -> {
                        val pkg = intent.getStringExtra(X_PKG) ?: return
                        hubRetract(context, pkg, if (intent.hasExtra(X_ID)) intent.getIntExtra(X_ID, 0) else null)
                    }
                }
            }
        }
    }
}

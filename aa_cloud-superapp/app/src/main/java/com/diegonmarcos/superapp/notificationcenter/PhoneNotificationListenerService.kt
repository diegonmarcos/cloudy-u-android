package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Captures every system notification the user grants us access to and
 * funnels them into [PhoneNotificationStore]. The store is then read
 * back by the Infos/Admin "Phone Notifications" panel.
 *
 * Wiring:
 *   • Manifest declares this service with BIND_NOTIFICATION_LISTENER_SERVICE.
 *   • User opens Settings → Special access → Notification access and
 *     toggles us on (the panel CTA fires that intent directly).
 *   • Android then calls [onListenerConnected] and starts delivering
 *     onNotificationPosted callbacks. On connect we also seed the store
 *     with [getActiveNotifications] so the panel isn't empty on first
 *     grant.
 *
 * Filtered out: our own SuperApp's notifications (already shown in the
 * in-app Notifications panel right above this one) and notifications
 * with empty title AND text (those are usually FLAG_FOREGROUND_SERVICE
 * ongoing service notifications with no displayable body — Bluetooth
 * connected, VPN active, etc.).
 *
 * Not every post is recorded in full: [PhoneNotifIntake] decides per notification key. A re-post with the same title and
 * text only moves the entry's time; a burst of changes is recorded at most once per [PhoneNotifIntake.MIN_GAP_MS], the
 * last state always included. The history shows the same entries; an app that re-posts every second no longer costs a
 * PNG encode and a disk write every second.
 */
class PhoneNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            for (sbn in activeNotifications.orEmpty()) record(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) com.diegonmarcos.superapp.xray.EngineProfiler.time("notify", "intake") { record(sbn) }
    }

    override fun onListenerDisconnected() {
        flushHeld()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        flushHeld()
        super.onDestroy()
    }

    /** The posts held back by the per-key gap, newest per key. Main thread only (the listener's callbacks arrive there). */
    private val held = HashMap<String, StatusBarNotification>()
    /** Keys with a wait already queued. Main thread only. */
    private val scheduled = HashSet<String>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The service is going: nothing held is lost, and the history is on disk. */
    private fun flushHeld() {
        runCatching {
            val l = held.values.toList(); held.clear()
            l.forEach { recordHeld(it) }
            PhoneNotificationStore.flush(this)
        }
    }

    private fun recordHeld(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text  = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        PhoneNotifIntake.commit(sbn.key, PhoneNotifIntake.signature(title, text), SystemClock.elapsedRealtime())
        store(sbn, n, title, text)
    }

    private fun record(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (!AppSwitches.pushAllowed(this, pkg)) return // Apps Push Notifications: the owner muted this app
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text  = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val key = sbn.key
        val sig = PhoneNotifIntake.signature(title, text)
        when (PhoneNotifIntake.decide(key, sig, SystemClock.elapsedRealtime())) {
            // RECORD and TOUCH are the newest state of this key: an older post still held must not be recorded after it.
            PhoneNotifIntake.Verdict.RECORD -> { held.remove(key); store(sbn, n, title, text) }
            PhoneNotifIntake.Verdict.TOUCH -> {
                held.remove(key)
                // Same title and text: only the time moved. The thread / reply intents are refreshed (they are in memory).
                if (PhoneNotificationStore.touch(this, key, sbn.postTime)) keepIntents(key, n)
                else { PhoneNotifIntake.commit(key, sig, SystemClock.elapsedRealtime()); store(sbn, n, title, text) }
            }
            PhoneNotifIntake.Verdict.HOLD -> {
                // Changed again inside the gap: keep the newest. When the wait ends it goes through this same door again,
                // so it is recorded only if the gap is really over (else it is held once more), never twice, never early.
                held[key] = sbn
                if (scheduled.add(key))
                    main.postDelayed({ scheduled.remove(key); held.remove(key)?.let { runCatching { record(it) } } }, PhoneNotifIntake.MIN_GAP_MS)
            }
        }
    }

    private fun keepIntents(key: String, n: Notification) {
        // Boards ▸ Inboxes opens the thread itself when the app left one.
        com.diegonmarcos.superapp.boards.ThreadIntents.put(key, n.contentIntent)
        // The inline-reply action, if the app offers one: the Reply pill of the Notify / Inbox boxes sends through it.
        ReplyActions.capture(key, n)
    }

    /** The full record: the history entry, the avatar, the thread and reply intents. */
    private fun store(sbn: StatusBarNotification, n: Notification, title: String, text: String) {
        val pkg = sbn.packageName
        val appLabel = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        PhoneNotificationStore.push(this, PhoneNotificationStore.Entry(
            key         = sbn.key,
            ts          = sbn.postTime,
            packageName = pkg,
            appLabel    = appLabel,
            title       = title,
            text        = text,
        ))
        // The sender / large-icon avatar, 40dp in the cache, drawn round on the right of the row.
        NotifAvatars.capture(this, sbn.key, n)
        keepIntents(sbn.key, n)
    }
}

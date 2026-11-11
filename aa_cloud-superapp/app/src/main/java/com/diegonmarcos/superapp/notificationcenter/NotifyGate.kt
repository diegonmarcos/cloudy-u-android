package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The one door every repeating notification goes through. A batterystats wakelock showed 217,685 posts in an hour
 * (about 60 a second) from this app: nothing between a producer and NotificationManager.notify() asked whether the
 * card had changed or how often it was being posted. This does both, per (tag, id):
 *  - DEDUPE: a post whose visible content equals the last one posted is dropped ([force] skips only this check, for
 *    "put it back after a swipe" paths). A data widget card ([postCtx]) is forced only when it is really NOT in the
 *    shade: an unchanged card that is still there is not posted again (every screen-on used to re-post all of them);
 *  - DEBOUNCE: at most one post per [MIN_GAP_MS]; a post inside the gap is held and only the LAST one goes out when
 *    the gap ends, so a burst of N updates costs at most 2 notify() calls and the final state is never lost.
 * A cancel must call [invalidate], so the next post goes out even if its content equals the cancelled one.
 */
object NotifyGate {
    const val MIN_GAP_MS = 1_000L

    private class Slot {
        var sig: String? = null
        var lastAt = 0L
        var held: Notification? = null
        var heldSig: String? = null
        var heldForce = false
        var scheduled = false
    }

    private val slots = HashMap<String, Slot>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Test seams. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var defer: (Long, Runnable) -> Unit = { d, r -> main.postDelayed(r, d) }

    @Volatile internal var notifyCount = 0

    fun post(nm: NotificationManager, tag: String?, id: Int, n: Notification, force: Boolean = false): Boolean =
        com.diegonmarcos.superapp.xray.EngineProfiler.time("notify", "post") { synchronized(this) {
            val key = "$tag#$id"
            val s = slots.getOrPut(key) { Slot() }
            val sig = signature(n)
            // Back to what is posted: a held CHANGE is void. A held FORCED post (the card is missing) is not.
            if (!force && sig == s.sig) { if (!s.heldForce) { s.held = null; s.heldSig = null }; return false }
            val now = clock()
            val gap = now - s.lastAt
            if (s.lastAt != 0L && gap < MIN_GAP_MS) {
                s.held = n; s.heldSig = sig; s.heldForce = s.heldForce || force
                if (!s.scheduled) {
                    s.scheduled = true
                    defer(MIN_GAP_MS - gap, Runnable { flush(nm, tag, id, key) })
                }
                return false
            }
            send(nm, tag, id, n, s, sig, now)
            true
        } }

    /**
     * A data widget card: goes out when its content changed OR when it is not in the shade (swiped away, cleared, never
     * posted), never more than once per gap. The shade is asked ([inShade]), not assumed: an unchanged card that is
     * still posted costs no notify().
     */
    fun postCtx(ctx: android.content.Context, id: Int, n: Notification): Boolean {
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        return post(nm, null, id, n, force = !inShade(nm, null, id))
    }

    /** Is (tag, id) posted right now? An unanswerable shade counts as "not there", so the card is posted rather than lost. */
    internal fun inShade(nm: NotificationManager, tag: String?, id: Int): Boolean =
        runCatching { nm.activeNotifications.any { it.id == id && it.tag == tag } }.getOrDefault(false)

    private fun flush(nm: NotificationManager, tag: String?, id: Int, key: String) = synchronized(this) {
        val s = slots[key] ?: return
        s.scheduled = false
        val n = s.held ?: return
        val sig = s.heldSig
        val force = s.heldForce
        s.held = null; s.heldSig = null; s.heldForce = false
        if (!force && sig == s.sig) return
        send(nm, tag, id, n, s, sig, clock())
    }

    private fun send(nm: NotificationManager, tag: String?, id: Int, n: Notification, s: Slot, sig: String?, now: Long) {
        // What goes out is the newest state: anything still held is older and must not overwrite it later.
        s.sig = sig; s.lastAt = now; s.held = null; s.heldSig = null; s.heldForce = false; notifyCount++
        runCatching { nm.notify(tag, id, n) }
    }

    /** The notification was cancelled (or the owner is retiring it): forget it so the next post is not seen as a duplicate. */
    fun invalidate(tag: String?, id: Int) = synchronized(this) {
        slots["$tag#$id"]?.let { it.sig = null; it.held = null; it.heldSig = null; it.heldForce = false }
        Unit
    }

    /** Forget everything and put the real clock and scheduler back (a test that swapped them must not leak them). */
    internal fun reset() = synchronized(this) {
        slots.clear(); notifyCount = 0
        clock = { SystemClock.elapsedRealtime() }
        defer = { d, r -> main.postDelayed(r, d) }
    }

    /** What the user would see: channel, group, text, actions, flags. Never the time and never the PendingIntents. */
    internal fun signature(n: Notification): String {
        val e = n.extras
        val sb = StringBuilder()
        sb.append(n.channelId).append('|').append(n.group).append('|').append(n.sortKey).append('|')
            .append(n.flags).append('|').append(n.number).append('|').append(n.color).append('|')
            .append(n.getLargeIcon() != null).append('|')
        for (k in KEYS) {
            val v = e?.get(k)
            sb.append(if (v is Array<*>) v.contentToString() else v).append('\u0001')
        }
        n.actions?.forEach { sb.append(it.title).append('\u0002') }
        return sb.toString()
    }

    private val KEYS = listOf(
        Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_PROGRESS, Notification.EXTRA_PROGRESS_MAX, Notification.EXTRA_TEXT_LINES,
    )
}

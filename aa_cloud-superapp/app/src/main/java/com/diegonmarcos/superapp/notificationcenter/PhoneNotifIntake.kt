package com.diegonmarcos.superapp.notificationcenter

/**
 * What the notification listener does with ONE post, decided per notification key. Pure (no Android type), so the rule
 * is tested as it runs.
 *
 * The listener hears every notification every app posts, including the ones an app re-posts over and over: a media
 * player's position, a download's progress, a navigation app's next turn. Each one used to be recorded in full (app
 * label lookup, the avatar drawn and PNG-encoded to the cache, the whole history parsed and written back to disk), so
 * one app updating once a second cost a PNG and two disk writes a second for as long as it ran, screen on or off.
 *
 *  - [Verdict.TOUCH]: same title and text as the last one recorded for this key. Nothing the history shows changed
 *    except the time, so only the time is moved (in memory); no avatar, no label lookup, no immediate disk write.
 *  - [Verdict.HOLD]: the content changed, but this key was recorded less than [MIN_GAP_MS] ago. The post is kept and
 *    the LAST one held is recorded when the gap ends ([commit]), so a burst costs two records and the final state is
 *    never lost.
 *  - [Verdict.RECORD]: record it in full, now.
 */
internal object PhoneNotifIntake {

    /** At most one full record per notification key in this long. */
    const val MIN_GAP_MS = 3_000L
    private const val MAX_KEYS = 256

    enum class Verdict { RECORD, TOUCH, HOLD }

    private class Slot(var sig: String, var lastAt: Long)

    /** Least recently used first, capped: a key that stopped posting is forgotten (and is then recorded in full again). */
    private val slots = object : LinkedHashMap<String, Slot>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>?): Boolean = size > MAX_KEYS
    }

    /** What the history shows of a notification: its title and its text. */
    fun signature(title: String, text: String): String = title + '\u0001' + text

    @Synchronized
    fun decide(key: String, sig: String, nowMs: Long): Verdict {
        val s = slots[key]
        if (s == null) { slots[key] = Slot(sig, nowMs); return Verdict.RECORD }
        if (s.sig == sig) return Verdict.TOUCH
        if (nowMs >= s.lastAt && nowMs - s.lastAt < MIN_GAP_MS) return Verdict.HOLD
        s.sig = sig; s.lastAt = nowMs
        return Verdict.RECORD
    }

    /** A held post is being recorded now (the gap ended), or a TOUCH found nothing to touch and is recorded in full. */
    @Synchronized
    fun commit(key: String, sig: String, nowMs: Long) {
        val s = slots[key]
        if (s == null) slots[key] = Slot(sig, nowMs) else { s.sig = sig; s.lastAt = nowMs }
    }

    @Synchronized
    fun reset() = slots.clear()
}

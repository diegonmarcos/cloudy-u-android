package com.diegonmarcos.superapp.notificationcenter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted ring buffer of phone notifications captured by
 * [PhoneNotificationListenerService]. Last 50 entries, deduped by
 * StatusBarNotification.key so a music-player progress update doesn't
 * spam 30 rows for the same notification — the newest update replaces
 * the prior entry in place.
 *
 * Persistence: single JSONArray string in SharedPreferences. Compact,
 * fast to read on panel render, survives process death. Cleared on
 * user demand (clear()) — not auto-cleared on dismissal: the whole
 * point of a launcher-side notification history is to see what flew
 * by even after the system dismissed it.
 *
 * COST. The listener calls [push] for every notification every app posts, and each call used to parse the whole array
 * from the prefs, rebuild it and write it back to disk: a media player or a download updating its notification once a
 * second was a full parse plus a disk write a second, screen on or off. Now the list is held in memory ([all] parses the
 * prefs once per process) and the disk write is coalesced: a burst of changes is ONE write [WRITE_GAP_MS] later, and an
 * update that only moved the time ([touch]) is written at most every [LAZY_GAP_MS]. Readers in this process always see
 * the list as it is now; nothing is dropped, only written later ([flush] writes at once).
 */
object PhoneNotificationStore {
    private const val PREF = "phone_notif_store"
    private const val KEY = "entries"
    private const val MAX = 50

    data class Entry(
        val key: String,
        val ts: Long,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
    )

    /** A changed entry is on disk within this long. */
    internal const val WRITE_GAP_MS = 2_000L
    /** An entry whose content did not change (only its time moved) is on disk within this long. */
    internal const val LAZY_GAP_MS = 60_000L

    /** The list as this process holds it; null until the first read. Guarded by this object's lock. */
    private var mem: List<Entry>? = null
    private var dirty = false
    /** Clock time of the earliest write already queued; [Long.MAX_VALUE] when none is. */
    private var writeDueAt = Long.MAX_VALUE
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Test seams. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var defer: (Long, Runnable) -> Unit = { d, r -> main.postDelayed(r, d) }
    @Volatile internal var diskWrites = 0
    @Volatile internal var diskReads = 0

    @Synchronized
    fun all(ctx: Context): List<Entry> = mem ?: load(ctx).also { mem = it }

    /** #1649 How many entries the history holds (X-Ray App, Notify engine). */
    fun count(ctx: Context): Int = all(ctx).size

    private fun load(ctx: Context): List<Entry> {
        diskReads++
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Entry(
                        key         = o.optString("key"),
                        ts          = o.optLong("ts"),
                        packageName = o.optString("pkg"),
                        appLabel    = o.optString("app"),
                        title       = o.optString("title"),
                        text        = o.optString("text"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** package -> app label of every app that has posted into the history. */
    fun apps(ctx: Context): Map<String, String> =
        all(ctx).associate { it.packageName to it.appLabel.ifBlank { it.packageName } }

    /** Upsert by key, newest first, capped at MAX. In memory at once, on disk within [WRITE_GAP_MS]. */
    @Synchronized
    fun push(ctx: Context, e: Entry) {
        mem = (listOf(e) + all(ctx).filter { it.key != e.key }).take(MAX)
        scheduleWrite(ctx, WRITE_GAP_MS)
    }

    /**
     * The notification under [key] was posted again with the same title and text: only its time moves (and, as with
     * [push], it goes to the top). False when no such entry is held (evicted, or the history was cleared), so the caller
     * records it in full. In memory at once, on disk within [LAZY_GAP_MS].
     */
    @Synchronized
    fun touch(ctx: Context, key: String, ts: Long): Boolean {
        val cur = all(ctx)
        val e = cur.firstOrNull { it.key == key } ?: return false
        mem = listOf(e.copy(ts = ts)) + cur.filter { it.key != key }
        scheduleWrite(ctx, LAZY_GAP_MS)
        return true
    }

    private fun scheduleWrite(ctx: Context, delayMs: Long) {
        dirty = true
        val due = clock() + delayMs
        if (due >= writeDueAt) return // a write that soon is already queued
        writeDueAt = due
        val app = ctx.applicationContext
        defer(delayMs, Runnable { flush(app) })
    }

    /** Write what is held, now, if anything changed since the last write. */
    @Synchronized
    fun flush(ctx: Context) {
        writeDueAt = Long.MAX_VALUE
        if (!dirty) return
        dirty = false
        val next = mem ?: return
        diskWrites++
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (x in next) {
            arr.put(JSONObject()
                .put("key", x.key)
                .put("ts", x.ts)
                .put("pkg", x.packageName)
                .put("app", x.appLabel)
                .put("title", x.title)
                .put("text", x.text))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun clear(ctx: Context) {
        mem = emptyList(); dirty = false
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Forget the in-memory copy (the next read parses the prefs again). For tests. */
    @Synchronized
    internal fun reset() {
        mem = null; dirty = false; writeDueAt = Long.MAX_VALUE; diskWrites = 0; diskReads = 0
        clock = { SystemClock.elapsedRealtime() }
        defer = { d, r -> main.postDelayed(r, d) }
    }
}

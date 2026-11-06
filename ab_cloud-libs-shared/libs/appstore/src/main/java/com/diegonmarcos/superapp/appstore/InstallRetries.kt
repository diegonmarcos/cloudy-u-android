package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.devtools.RetryBackoff
import org.json.JSONObject

/**
 * #1551 THE INSTALL RETRY LEDGER of the auto chain: per queued app, how many times its install did not finish for THIS build, when
 * the next try is due and why the last one ended. Pure Kotlin plus org.json (a JVM test pins it); persisted by [StoreAuto] next to its
 * queue, so it survives process death, a reboot and an update of this app, and a new chain carries it over instead of starting at 0.
 *
 * A failure is counted once (by the time it happened, so the same install note read by two passes is one failure); a new build of
 * the app starts the count again; the wait after failure n is [RetryBackoff.afterFailures] (never below the 30 s floor). After [max]
 * failures the queue stops and reports the final failure with the last real reason.
 */
object InstallRetries {

    data class Entry(
        val id: String,
        /** The build the failures were counted for (digest, else version): a new one resets the count. */
        val build: String,
        val attempts: Int,
        val nextAt: Long,
        /** When the counted failure happened: the de-duplication key. */
        val lastAt: Long,
        val reason: String,
    ) {
        fun due(now: Long): Boolean = now >= nextAt
        fun exhausted(max: Int): Boolean = attempts > max
        /** "retry 2/3 · next 14:05" for the Activity panel and the row. */
        fun text(max: Int, clock: (Long) -> String): String =
            if (exhausted(max)) "failed after $attempts attempts" else "retry $attempts/$max · next ${clock(nextAt)}"
    }

    /** Count the failure that happened at [at] for [build] of [id]. Returns [prev] unchanged when that failure is already counted. */
    fun register(
        prev: Entry?, id: String, build: String, reason: String, at: Long,
        backoff: (Int) -> Long = { RetryBackoff.afterFailures(it) },
    ): Entry {
        val base = prev?.takeIf { it.build == build }
        if (base != null && at <= base.lastAt) return base
        val n = (base?.attempts ?: 0) + 1
        return Entry(id, build, n, at + backoff(n).coerceAtLeast(RetryBackoff.MIN_MS), at, reason)
    }

    /** The ledger entry still applies to [build]; a different build drops it. */
    fun forBuild(e: Entry?, build: String): Entry? = e?.takeIf { it.build == build }

    /** The wait before the earliest due retry among [entries], floored at 30 s; null when nothing waits. */
    fun nextDelay(entries: Collection<Entry>, max: Int, now: Long): Long? =
        entries.filter { !it.exhausted(max) }.minOfOrNull { it.nextAt }?.let { (it - now).coerceAtLeast(RetryBackoff.MIN_MS) }

    fun toJson(all: Map<String, Entry>): JSONObject = JSONObject().also { o ->
        for ((k, e) in all) o.put(k, JSONObject().put("build", e.build).put("attempts", e.attempts).put("nextAt", e.nextAt)
            .put("lastAt", e.lastAt).put("reason", e.reason))
    }

    fun fromJson(o: JSONObject?): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (o == null) return out
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            out[k] = Entry(k, e.optString("build"), e.optInt("attempts"), e.optLong("nextAt"), e.optLong("lastAt"), e.optString("reason"))
        }
        return out
    }
}

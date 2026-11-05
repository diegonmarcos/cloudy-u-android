package com.diegonmarcos.superapp.appstore.dl

import java.util.concurrent.FutureTask

/**
 * #1517 UPDATE CHECKS ARE METADATA READS, AND THEY ARE DECOUPLED FROM DOWNLOADS.
 *
 * A short-TTL cache plus a de-duplicated in-flight check: the same key asked twice at once runs ONCE and both callers get its
 * answer; asked again inside [ttlMs] it is answered from the cache without a network call. [force] (the user's own "Check") skips
 * the cache but still joins an in-flight check. Nothing here knows the download engine exists: a check reads, it never writes,
 * cancels or clears anything. A tab switch, a recomposition or a resume asks without [force], so it costs nothing.
 */
class CheckGate<K : Any, V : Any>(private val ttlMs: Long, private val now: () -> Long = { System.currentTimeMillis() }) {
    private class Entry<V>(val at: Long, val value: V)
    private val cache = HashMap<K, Entry<V>>()
    private val inFlight = HashMap<K, FutureTask<V>>()

    fun peek(key: K): V? = synchronized(this) { cache[key]?.value }

    fun invalidate(key: K) = synchronized(this) { cache.remove(key); Unit }

    fun get(key: K, force: Boolean = false, compute: () -> V): V {
        var cached: V? = null
        var task: FutureTask<V>? = null
        synchronized(this) {
            if (!force) cached = cache[key]?.takeIf { now() - it.at < ttlMs }?.value
            if (cached == null) task = inFlight[key] ?: FutureTask<V> { compute() }.also { inFlight[key] = it }
        }
        cached?.let { return it }
        val t = task!!
        // The creator runs it; a joiner's run() is a no-op once it has started, and its get() waits for the one answer.
        t.run()
        try {
            val v = t.get()
            synchronized(this) { cache[key] = Entry(now(), v); if (inFlight[key] === t) inFlight.remove(key) }
            return v
        } catch (e: java.util.concurrent.ExecutionException) {
            synchronized(this) { if (inFlight[key] === t) inFlight.remove(key) }
            throw e.cause ?: e
        }
    }
}

/** #1517 At most one acquisition per [ttlMs]: what makes a tab switch unable to start a second "updates first" chain. */
class Throttle(private val ttlMs: Long, private val now: () -> Long = { System.currentTimeMillis() }) {
    private var last = Long.MIN_VALUE / 2
    @Synchronized fun tryAcquire(): Boolean {
        val t = now()
        if (t - last < ttlMs) return false
        last = t
        return true
    }
}

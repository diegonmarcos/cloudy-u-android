package app.sterna.core.data.mail

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * #1952 returning to the list: a NEW collector (the list screen back in the composition) is first handed what the screen
 * showed last ([kept], null when nothing was shown), before [upstream] is even subscribed; the live pages follow and replace
 * it. So the return paints the same rows in its first frame whatever the store query is doing (a generation still loading
 * at 40 000 messages took seconds and showed an empty list meanwhile), and the return itself starts no query.
 */
fun <T : Any> keptFirst(kept: () -> T?, upstream: Flow<T>): Flow<T> = flow {
    kept()?.let { emit(it) }
    emitAll(upstream)
}

/** Longest a store invalidation of the list waits before it starts the next query. */
const val LIST_INVALIDATE_MAX_DELAY_MS = 1_000L

/** Below this a query is cheap enough to follow every write at once. */
const val LIST_INVALIDATE_FAST_QUERY_MS = 100L

/**
 * #1952 one query at a time on a large folder: how long an invalidation of the list's source waits, from how long its own
 * load took ([lastLoadMs]). A fast list (every ordinary mailbox) follows each write at once; a slow one (hundreds of ms at
 * 40 000 messages) lets the writes of that span fall into ONE new query, so a sync in the background cannot queue a full
 * query per write behind the scroll.
 */
fun listInvalidateDelayMs(lastLoadMs: Long): Long =
    if (lastLoadMs < LIST_INVALIDATE_FAST_QUERY_MS) 0L else minOf(lastLoadMs, LIST_INVALIDATE_MAX_DELAY_MS)

package app.sterna.core.data.mail

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** Rows one network chunk asks for, newest first: the first page of a folder and every scroll page
 *  (the mediator's `PAGE_SIZE`) alike. A folder is never loaded whole in one request (#1752). */
internal const val INITIAL_PAGE_LIMIT = 50

/** Chunks one catch-up may chain before it gives up: 10 x 50 = at most 500 new messages per
 *  refresh. A gap longer than that falls back to the window walk, which is bounded by the window. */
internal const val CATCH_UP_MAX_PAGES = 10

/** One newest-first chunk as the catch-up consumes it. [rows] are in server order, newest first. */
internal data class CatchUpPage<E>(
    val rows: List<E>,
    val queryState: String?,
    val emailState: String?,
)

/**
 * What a catch-up did. [reachedCached] is true when it met a row the cache already held (or the end
 * of the folder): everything newer than that row is now cached and the folder may be re-armed on
 * [queryState]/[emailState]. False means a longer gap than [CATCH_UP_MAX_PAGES] chunks, or an anchor
 * that left the folder: the caller falls back to its window walk. [fetchedIds] are the NEW rows.
 */
internal data class CatchUp(
    val fetchedIds: List<String>,
    val reachedCached: Boolean,
    val queryState: String?,
    val emailState: String?,
    val pages: Int,
)

/**
 * Cache-first, incremental folder sync (#1752): the list already shows [cachedIds]; this asks the
 * server for the newest chunks only, chained by anchor (the last row of the previous chunk, so a
 * message arriving meanwhile shifts nothing), and stops at the first chunk that touches a cached
 * row. Each chunk is handed to [writePage] before the next request, so a failure in chunk N leaves
 * chunks 1..N-1 in the cache and DELETES NOTHING: this function has no delete at all. [fetchPage]
 * answers null when the anchor left the folder (the caller then falls back).
 */
internal suspend fun <E> catchUpNewestFirst(
    cachedIds: Set<String>,
    idOf: (E) -> String,
    fetchPage: suspend (anchorId: String?, limit: Int) -> CatchUpPage<E>?,
    writePage: suspend (List<E>) -> Unit,
    pageLimit: Int = INITIAL_PAGE_LIMIT,
    maxPages: Int = CATCH_UP_MAX_PAGES,
): CatchUp {
    val fetched = ArrayList<String>()
    var anchor: String? = null
    var queryState: String? = null
    var emailState: String? = null
    var pages = 0
    while (pages < maxPages) {
        val page = fetchPage(anchor, pageLimit)
            ?: return CatchUp(fetched, false, queryState, emailState, pages)
        pages++
        if (pages == 1) {
            queryState = page.queryState
            emailState = page.emailState
        }
        val fresh = page.rows.filter { idOf(it) !in cachedIds }
        if (fresh.isNotEmpty()) {
            writePage(fresh)
            fresh.forEach { fetched += idOf(it) }
        }
        // A chunk that met a cached row, came back short, or came back empty: the gap is closed.
        val touchedCached = fresh.size < page.rows.size
        if (touchedCached || page.rows.size < pageLimit) {
            return CatchUp(fetched, true, queryState, emailState, pages)
        }
        anchor = idOf(page.rows.last())
    }
    return CatchUp(fetched, false, queryState, emailState, pages)
}

/**
 * Whether a finished window walk may reconcile (delete) against the cache. The walk is newest
 * first, so a walk whose newest row is OLDER than the newest row the cache held before it is a
 * stale or degraded answer (an index behind, a gateway serving an old snapshot), not a folder that
 * lost its newest mail: reconciling against it deletes exactly the mail the reader saw the day
 * before. Unknown dates (null or 0) never veto. A skipped reconcile only postpones pruning.
 */
internal fun reconcileAnswerIsCurrent(cachedNewestBefore: Long?, walkNewest: Long?): Boolean {
    if (cachedNewestBefore == null || cachedNewestBefore <= 0L) return true
    if (walkNewest == null || walkNewest <= 0L) return true
    return walkNewest >= cachedNewestBefore
}

/**
 * When each Inbox may re-read the server's WHOLE id list ([walkAllIds], up to 200 000 ids). It used
 * to run on every refresh, so every switch to the Inbox cost a full listing before any delta. Now
 * once per process and then at most once per [minIntervalMs]; a refresh in between runs the delta
 * only. In memory and monotonic, like [GhostSweepSchedule].
 */
internal class InboxSnapshotSchedule(
    private val minIntervalMs: Long = INBOX_SNAPSHOT_MIN_INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val last = ConcurrentHashMap<String, Long>()

    private fun key(accountId: String, mailboxId: String) = "$accountId\u0000$mailboxId"

    /** True (and recorded) when a snapshot may run now. */
    fun claim(accountId: String, mailboxId: String): Boolean {
        val now = clock()
        var due = false
        last.compute(key(accountId, mailboxId)) { _, at ->
            due = at == null || now - at >= minIntervalMs || now < at
            if (due) now else at
        }
        return due
    }

    /** The claimed snapshot failed: it may be retried by the next refresh. */
    fun release(accountId: String, mailboxId: String) {
        last.remove(key(accountId, mailboxId))
    }
}

internal const val INBOX_SNAPSHOT_MIN_INTERVAL_MS = 10 * 60_000L

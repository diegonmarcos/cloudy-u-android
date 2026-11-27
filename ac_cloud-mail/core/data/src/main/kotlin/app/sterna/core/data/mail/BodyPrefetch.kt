package app.sterna.core.data.mail

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Body prefetch (#1763): opening a mail must read the body from disk. After every list sync chunk the
 * bodies of the messages inside the offline window are fetched in the background, newest first, in
 * batched Email/get calls, and stored in Room ([BodyPrefetchPort.fetchAndStore]).
 */

/** What the device says right now about whether background downloads are welcome. */
data class PrefetchConditions(val connected: Boolean, val unmetered: Boolean, val batterySaver: Boolean) {
    companion object {
        val UNRESTRICTED = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    }
}

/** Who asked: the list sync (the whole window, newest first), the reader (the neighbours of the open mail, in the
 *  pager's order) or the list on screen (#1869 step 8: the rows the user is looking at). */
enum class PrefetchTrigger { LIST_SYNC, NEIGHBOUR, VISIBLE }

/** How much one pass may do: at most [maxIds] ids, [batchSize] per Email/get, [concurrency] calls at once. */
data class PrefetchBudget(val maxIds: Int, val batchSize: Int, val concurrency: Int) {
    val allowed: Boolean get() = maxIds > 0 && concurrency > 0

    companion object {
        val NONE = PrefetchBudget(0, 0, 0)
    }
}

/** Messages per Email/get on an unmetered link. */
internal const val PREFETCH_BATCH = 25

/** Parallel Email/get calls, all folders together. */
internal const val PREFETCH_MAX_CONCURRENCY = 2

/** What a metered link may spend on the list: one batch (the newest messages), one call at a time. */
internal const val PREFETCH_METERED_MAX_IDS = 25

/** Ids one list pass reads and fetches at most, newest first: the read is bounded in the statement, and the next
 *  sync chunk's pass continues with what is still missing. */
internal const val PREFETCH_PASS_MAX_IDS = 2_000

/** Neighbours prefetched on each side of the open mail. */
internal const val PREFETCH_NEIGHBOURS_EACH_SIDE = 2

/** #1869 step 8: rows on screen prefetched at most per pass, and how long the list must rest before they are. */
internal const val PREFETCH_VISIBLE_MAX = 12
const val PREFETCH_VISIBLE_DEBOUNCE_MS = 150L

/**
 * #1869 step 8: the [each] entries on each side of [page] in the PAGER's order (what a swipe reaches next), nearest
 * first, alternating after / before, so a budget cut keeps the closest. [at] answers null for an entry not loaded yet.
 */
fun <T : Any> pagerNeighbours(page: Int, pageCount: Int, each: Int, at: (Int) -> T?): List<T> = buildList {
    for (d in 1..each) {
        if (page + d < pageCount) at(page + d)?.let(::add)
        if (page - d >= 0) at(page - d)?.let(::add)
    }
}

/** [pagerNeighbours] with the reader's [PREFETCH_NEIGHBOURS_EACH_SIDE]. */
fun <T : Any> pagerPrefetchOrder(page: Int, pageCount: Int, at: (Int) -> T?): List<T> =
    pagerNeighbours(page, pageCount, PREFETCH_NEIGHBOURS_EACH_SIDE, at)

/**
 * The throttle. No network: nothing. Battery saver: no list prefetch at all, and the neighbours only on an
 * unmetered link. Metered: the newest [PREFETCH_METERED_MAX_IDS] only, serially. Unmetered (Wi-Fi): the whole
 * window, [PREFETCH_MAX_CONCURRENCY] calls at a time.
 */
fun prefetchBudget(c: PrefetchConditions, trigger: PrefetchTrigger): PrefetchBudget = when {
    !c.connected -> PrefetchBudget.NONE
    trigger == PrefetchTrigger.NEIGHBOUR -> when {
        c.batterySaver && !c.unmetered -> PrefetchBudget.NONE
        else -> PrefetchBudget(PREFETCH_NEIGHBOURS_EACH_SIDE * 2, PREFETCH_NEIGHBOURS_EACH_SIDE * 2, 1)
    }
    // #1869 step 8: what is on screen is fetched on a metered link too (text bodies, one call); battery saver on a
    // metered link stops it, as it stops the neighbours.
    trigger == PrefetchTrigger.VISIBLE -> when {
        c.batterySaver && !c.unmetered -> PrefetchBudget.NONE
        else -> PrefetchBudget(PREFETCH_VISIBLE_MAX, PREFETCH_VISIBLE_MAX, 1)
    }
    c.batterySaver -> PrefetchBudget.NONE
    !c.unmetered -> PrefetchBudget(PREFETCH_METERED_MAX_IDS, PREFETCH_BATCH, 1)
    else -> PrefetchBudget(PREFETCH_PASS_MAX_IDS, PREFETCH_BATCH, PREFETCH_MAX_CONCURRENCY)
}

/** The two storage/network operations a prefetch needs; the repository implements them over Room and JMAP. */
interface BodyPrefetchPort {
    /** Newest first, at most [limit]: ids inside the cache window that have no stored body. */
    suspend fun missingNewest(limit: Int): List<String>

    /** The members of [ids] that have no stored body, in the given order. */
    suspend fun missingAmong(ids: List<String>): List<String>

    /** One batched Email/get for [ids], every body written to the cache. */
    suspend fun fetchAndStore(ids: List<String>)
}

data class PrefetchResult(val requested: Int, val batches: Int, val skipped: String?)

/** Runs one prefetch pass under the [PrefetchBudget] the current [PrefetchConditions] allow. [gate] bounds the
 *  concurrent Email/get calls of ALL passes (folders, neighbours) together. */
class BodyPrefetcher(
    private val port: BodyPrefetchPort,
    private val conditions: () -> PrefetchConditions,
    private val gate: Semaphore = Semaphore(PREFETCH_MAX_CONCURRENCY),
) {
    /** [ids] null = the newest missing bodies of the window; otherwise exactly those (the neighbours). */
    suspend fun run(trigger: PrefetchTrigger, ids: List<String>? = null): PrefetchResult {
        val budget = prefetchBudget(conditions(), trigger)
        if (!budget.allowed) return PrefetchResult(0, 0, skipped = "conditions")
        val wanted = (if (ids == null) port.missingNewest(budget.maxIds) else port.missingAmong(ids)).take(budget.maxIds)
        if (wanted.isEmpty()) return PrefetchResult(0, 0, skipped = null)
        val batches = wanted.chunked(budget.batchSize)
        val width = Semaphore(budget.concurrency)
        var done = 0
        val lock = Any()
        coroutineScope {
            for (batch in batches) {
                launch {
                    width.withPermit {
                        gate.withPermit {
                            // Re-read per batch: battery saver switched on mid-pass stops the rest.
                            if (!prefetchBudget(conditions(), trigger).allowed) return@withPermit
                            try {
                                port.fetchAndStore(batch)
                                synchronized(lock) { done++ }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                runCatching { Log.w(TAG, "prefetch batch of ${batch.size} failed: ${t.message}") }
                            }
                        }
                    }
                }
            }
        }
        return PrefetchResult(wanted.size, done, skipped = if (done < batches.size) "incomplete" else null)
    }

    private companion object {
        const val TAG = "MailPrefetch"
    }
}

// ---- eviction ---------------------------------------------------------------------------------------

/** Stored body bytes one account may hold before the oldest go, even inside the window: the default of the
 *  "Body cache limit" setting ([BodyCacheLimit.DEFAULT], build.json::mail_cache.bodies.cap_mb). */
internal const val BODY_CACHE_MAX_BYTES_PER_ACCOUNT = 3072L * 1024 * 1024

/**
 * Which stored bodies to drop: every body of a message received before [cutoffMillis] (null = no age limit:
 * "all"), then, if what is left still exceeds [maxBytes], the oldest of it until it fits. Headers are not
 * touched here: the list rows stay under the #1752 rules.
 */
internal fun planBodyEviction(
    rows: List<app.sterna.core.data.db.BodyCacheRow>,
    cutoffMillis: Long?,
    maxBytes: Long = BODY_CACHE_MAX_BYTES_PER_ACCOUNT,
): List<String> {
    val (old, kept) = rows.partition { cutoffMillis != null && it.sortKey < cutoffMillis }
    val drop = old.map { it.id }.toMutableList()
    var total = kept.sumOf { it.bytes }
    for (row in kept.sortedBy { it.sortKey }) {
        if (total <= maxBytes) break
        drop += row.id
        total -= row.bytes
    }
    return drop
}

// ---- timing ----------------------------------------------------------------------------------------

/** Timing logs for opening a mail (tag `MailOpen`): body fetch, sanitise/render, inline images, WebView init.
 *  A JVM unit test has no android.util.Log, so a failing log call is swallowed, never the open. */
object MailTiming {
    const val TAG = "MailOpen"

    inline fun <T> phase(emailId: String, name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            log(emailId, name, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    fun log(emailId: String, name: String, ms: Long) {
        try {
            Log.i(TAG, "id=$emailId phase=$name ms=$ms")
        } catch (_: RuntimeException) {
        }
    }
}

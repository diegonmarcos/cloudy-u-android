package com.diegonmarcos.cloudme

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException

/**
 * #1938 the pure rules of the section pager's background fetch (one implementation for every Cloud Me section):
 * every page of the shown section is fetched, ONE at a time, the page on screen first, then the rest in nav order. A failed page is marked failed (its error and Retry
 * show on that page) and the queue goes on. At most [maxRetained] fetched pages are kept: past that the least recently viewed one is RELEASED (its cached copy is
 * dropped by the caller) and is fetched again only when it becomes the page on screen or Retry is pressed. Nothing here touches Android.
 */
class PageFetchQueue(private val order: List<String>, private val maxRetained: Int) {
    enum class State { IDLE, LOADING, DONE, FAILED, RELEASED }

    private val state = LinkedHashMap<String, State>().apply { order.forEach { put(it, State.IDLE) } }
    private val errors = HashMap<String, String>()
    private val views = LinkedHashMap<String, Long>()      // page -> last-viewed tick
    private var tick = 0L
    private var visible: String? = null
    private var inFlight: String? = null

    fun stateOf(id: String): State = state[id] ?: State.IDLE
    fun errorOf(id: String): String? = errors[id]
    fun inFlight(): String? = inFlight
    fun retained(): List<String> = state.filterValues { it == State.DONE }.keys.toList()

    /** The page now on screen: it jumps the queue (the next pick), and counts as viewed. */
    fun show(id: String) { visible = id; views[id] = ++tick }

    /** The next page to fetch, or null when one is already in flight or nothing is left. Marks it LOADING. */
    fun next(): String? {
        if (inFlight != null) return null
        val v = visible
        val pick = if (v != null && state[v] in setOf(State.IDLE, State.RELEASED)) v else order.firstOrNull { state[it] == State.IDLE }
        if (pick != null) { state[pick] = State.LOADING; inFlight = pick; errors.remove(pick) }
        return pick
    }

    /** [id] was fetched. Returns the pages released to stay within [maxRetained] (least recently viewed first, never the visible one). */
    fun complete(id: String): List<String> {
        state[id] = State.DONE; if (inFlight == id) inFlight = null
        val out = ArrayList<String>()
        while (maxRetained > 0 && retained().size > maxRetained) {
            val victim = retained().filter { it != visible }.minByOrNull { views[it] ?: 0L } ?: break
            state[victim] = State.RELEASED; out += victim
        }
        return out
    }

    /** [id] could not be fetched: it shows [message] and Retry, and the queue is NOT stopped. */
    fun fail(id: String, message: String) { state[id] = State.FAILED; errors[id] = message; if (inFlight == id) inFlight = null }

    /** Retry on a failed (or released) page: it is fetched next if it is on screen, else in its turn. */
    fun retry(id: String) { if (state[id] == State.FAILED || state[id] == State.RELEASED) { state[id] = State.IDLE; errors.remove(id) } }
}

/** The loop that drives a [PageFetchQueue]: [fetch] is the one network/disk call (a test hands in one that blocks or fails). */
class PagePrefetcher(
    val queue: PageFetchQueue,
    private val fetch: suspend (String) -> Result<Unit>,
    private val onRelease: (String) -> Unit,
    private val onChange: () -> Unit = {},
) {
    private val kick = Channel<Unit>(Channel.CONFLATED)
    fun poke() { kick.trySend(Unit) }
    fun show(id: String) { queue.show(id); onChange(); poke() }
    fun retry(id: String) { queue.retry(id); onChange(); poke() }

    suspend fun run() {
        poke()
        while (true) {
            val id = queue.next()
            if (id == null) { kick.receive(); continue }
            onChange()
            val r = try { fetch(id) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
            r.onSuccess { queue.complete(id).forEach(onRelease) }.onFailure { queue.fail(id, it.message ?: it.javaClass.simpleName) }
            onChange()
        }
    }
}

/** The pager's fetch of one page: warms the page's cached copy (the Lnktree cache) for a remote web page, reads the bundled stack for the others; the test seam is [LocalPageFetch]. */
object PageFetch {
    fun default(ctx: android.content.Context, sectionId: String): suspend (String) -> Result<Unit> = { pageId ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val stack = Sections.stack(ctx, sectionId, pageId)
                val cfg = LnktreeCache.config
                val page = LnktreePages.fromStack(pageId, stack, cfg)
                if (page != null) {
                    val failed = LnktreePrefetch.run(listOf(page), cfg, LnktreeCache.store(ctx), LnktreeCache::fetch)
                    // offline with a cached copy is not a failure: the page shows from its copy
                    if (failed.isNotEmpty() && LnktreeCache.store(ctx).get(pageId, page.url) == null) error("could not fetch ${page.url}")
                }
            }
        }
    }
}

val LocalPageFetch = androidx.compose.runtime.compositionLocalOf<(suspend (String) -> Result<Unit>)?> { null }

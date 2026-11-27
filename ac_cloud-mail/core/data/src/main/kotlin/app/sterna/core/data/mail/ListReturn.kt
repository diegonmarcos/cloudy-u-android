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

// ---- the first rows, kept across a process death --------------------------------------------------------------------

/** Rows of the list's top kept for the next cold start: one screen and a half. */
const val KEPT_ROWS_MAX = 30

@kotlinx.serialization.Serializable
internal data class KeptRow(
    val email: app.sterna.core.jmap.model.Email,
    val threadCount: Int,
    val unread: Boolean,
    val threadExpandable: Boolean,
    val unreadCount: Int,
)

@kotlinx.serialization.Serializable
internal data class KeptList(val view: String, val rows: List<KeptRow>)

/**
 * #1952 cold start: the top of the list as the screen last showed it, written next to the store (the app's private files,
 * like the database) and read back when the process starts, so the first rows are painted before the database, the
 * settings and the folder list have even answered. [view] names the account, the folder and the grouping: rows kept for
 * another view are never shown ([decode] answers null). The live pages replace them as soon as they load.
 */
object KeptRows {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(view: String, rows: List<InboxRow>): String = json.encodeToString(
        KeptList.serializer(),
        KeptList(view, rows.take(KEPT_ROWS_MAX).map { KeptRow(it.email, it.threadCount, it.unread, it.threadExpandable, it.unreadCount) }),
    )

    fun decode(text: String, view: String): List<InboxRow>? = runCatching {
        val kept = json.decodeFromString(KeptList.serializer(), text)
        if (kept.view != view) return null
        kept.rows.map { InboxRow(it.email, it.threadCount, it.unread, it.threadExpandable, it.unreadCount) }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

// ---- the background fill gives way to the screen ----------------------------------------------------------------------

/** Pause between two body batches of the background fill while the app is on screen. */
const val UI_RESUMED_BATCH_PAUSE_MS = 250L

/**
 * #1952: with the app on screen the background fill (bodies of 40 000 messages) kept three of four cores busy and every
 * frame of the list paid for it. While an activity is resumed each body batch waits [UI_RESUMED_BATCH_PAUSE_MS] first;
 * in the background (a worker, the screen off) the fill runs flat out as before.
 */
object UiLoad {
    @Volatile var uiResumed: Boolean = false

    fun pauseMs(resumed: Boolean = uiResumed): Long = if (resumed) UI_RESUMED_BATCH_PAUSE_MS else 0L

    suspend fun yieldToUi() {
        val ms = pauseMs()
        if (ms > 0) kotlinx.coroutines.delay(ms)
    }
}

package app.sterna.core.data.mail

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.delay

/**
 * #1952 what the list screen collects, held ABOVE the screen (in the list's ViewModel, which outlives every page change).
 * It is a [SharedFlow] with one replayed value on purpose: the paging list reads that value in its FIRST composition, so
 * whatever sits in it is what a returning screen draws at once, with no store query.
 *  - on screen: every live generation of pages ([live]) goes straight through;
 *  - the screen leaves ([left], with the rows it showed): the replayed value stays the loaded generation, so the return
 *    draws it in the first frame. Only if a NEW generation starts while away (a write invalidated the list and its query,
 *    seconds long at 40 000 messages, is still running) the replayed value becomes the kept rows, never that loading
 *    generation: this is the empty list the owner saw on every return;
 *  - the screen is back ([shown]): the newest live generation is handed over and replaces the kept rows when it has loaded;
 *  - a cold start ([seed]): the rows kept from the last run are drawn before the store has answered.
 */
class ReturnHolder<P : Any> {
    private val out = MutableSharedFlow<P>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<P> = out.asSharedFlow()

    private var live: P? = null
    private var kept: P? = null
    private var away = false

    @Synchronized fun seed(keptRows: P) {
        kept = keptRows
        away = true
        out.tryEmit(keptRows)
    }

    @Synchronized fun live(pages: P) {
        live = pages
        val held = kept
        if (away && held != null) out.tryEmit(held) else out.tryEmit(pages)
    }

    @Synchronized fun left(keptRows: P?) {
        kept = keptRows
        away = true
    }

    @Synchronized fun shown() {
        away = false
        val newest = live ?: return
        if (out.replayCache.firstOrNull() !== newest) out.tryEmit(newest)
    }
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

/** #1952 the role that marks the folder whose headers are never pruned by age. */
fun isInboxRole(role: String?): Boolean = role?.trim()?.lowercase() == "inbox"

/** #1952 the store holds no folder of the account yet (its first sync): the folder list is stored before the messages. */
fun firstSyncNeedsFolders(storedRoleOfTarget: String?, storedFolders: Int): Boolean = storedRoleOfTarget == null && storedFolders == 0

// ---- the drawer's aggregates follow a sync in steps -------------------------------------------------------------------

/** Least gap between two results of an aggregate the screen reads while a sync writes (the drawer's unread badges). */
const val AGGREGATE_MIN_GAP_MS = 1_000L

/**
 * #1952 scroll jank while a sync runs: the per-folder unread aggregates re-ran over the whole message table on every write
 * and each result went through the screen's state on the main thread. The first result is passed at once, then at most one
 * per [gapMs] (the newest of the span, so the last write is never lost).
 */
fun <T> Flow<T>.stepped(gapMs: Long = AGGREGATE_MIN_GAP_MS): Flow<T> =
    conflate().transform { emit(it); delay(gapMs) }

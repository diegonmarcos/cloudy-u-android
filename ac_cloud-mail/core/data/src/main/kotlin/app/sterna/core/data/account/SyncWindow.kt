package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/**
 * Per-account "messages to sync" window — how much of a mailbox to keep cached,
 * either by recency ([maxAgeDays]) or by message [limit] (ARCHITECTURE.md →
 * "Storage & retention"). Age windows still cap the fetch with [limit].
 *
 * On an age window the two numbers are read TOGETHER, and [limit] is a FLOOR on what is kept,
 * not only a ceiling on what is fetched: retention keeps the newest [limit] messages of a folder
 * whatever their age, plus everything inside [maxAgeDays]. A folder of ten old messages therefore
 * keeps its ten, while a busy folder still keeps only its ninety days (Codeberg #110 — before
 * this, the age was the only number retention looked at, so the setting could empty a quiet
 * folder down to whatever happened to be recent).
 *
 * [limit] counts MESSAGES, not threads: folder syncs are uncollapsed (the cache is
 * WYSIWYG and holds every thread member), so a window now matches its "N messages"
 * settings label exactly.
 *
 * On IMAP the window is where the reader's mail ends. Measured on the bench, same device, same
 * gesture, counted in the database per account and per folder: the IMAP Archive stayed flat at
 * 1 000 messages while it was scrolled to the bottom, where the JMAP Archive grew from 1 000 to
 * 1 150 under the same gesture.
 *
 * WHY it does not hold on IMAP is NOT established. Nothing below is a cause, only what the code
 * says. The scroll-to-load-more mediator is attached without testing the protocol ([pagedFolder]),
 * and its IMAP branch does ask the server for older pages ([folderMediator], then
 * `ImapMailService.fetchOlderPage` and `upsertAll`) — so "the app never asks" is not the answer.
 * Two candidates were read off the code and never told apart: every IMAP refresh re-walks the
 * window and its reconcile deletes what falls outside it (no cursor, so this runs every time), and
 * `endOfPaginationReached` may be frozen true by an early `Success`. What the JMAP branch has and
 * the IMAP one has not is a thread-aware fill (`MAX_APPEND_FILL_PAGES`, `APPEND_THREAD_TARGET`):
 * the IMAP branch appends one page of fifty and stops there. The unified inbox is paged with no
 * mediator at all ([pagedMailbox]), so it stops at the window on both protocols.
 */
@Serializable
enum class SyncWindow(val limit: Int, val maxAgeDays: Int?) {
    DAYS_30(limit = 200, maxAgeDays = 30),
    DAYS_90(limit = 200, maxAgeDays = 90),
    YEAR_1(limit = 500, maxAgeDays = 365),

    // Count scale ([syncWindowChoices]): 10 000 is safe only because pages land incrementally.
    COUNT_100(limit = 100, maxAgeDays = null),
    COUNT_1000(limit = 1_000, maxAgeDays = null),
    COUNT_10000(limit = 10_000, maxAgeDays = null),

    // Retired from the picker but ALIVE in the format (see [ALL]): never remove or rename.
    COUNT_50(limit = 50, maxAgeDays = null),
    COUNT_200(limit = 200, maxAgeDays = null),
    COUNT_500(limit = 500, maxAgeDays = null),

    /**
     * "Everything" — retired from the picker, capped at 10 000 (largest offered window).
     *
     * NAME may not change or be deleted: it's the persisted format
     * ([StoredAccount.syncWindow]); an unknown name fails the WHOLE list to decode, but not
     * permanently — [AccountBlobGate] holds records until a build that knows it again.
     */
    ALL(limit = 10_000, maxAgeDays = null),
    ;

    /**
     * How many messages a JMAP full walk may read (#1763). A count window is its [limit]. An AGE window is
     * bounded by its age ([walkCutoffMillis]), not by 200 messages: [limit] stays the retention floor and
     * the IMAP size, and this is only a safety ceiling above any real mailbox's ninety days.
     */
    val walkLimit: Int get() = if (maxAgeDays != null) AGE_WINDOW_SAFETY_CEILING else limit

    /** Epoch millis of the oldest mail an age window walks, or null for a count window. */
    fun walkCutoffMillis(nowMillis: Long): Long? = maxAgeDays?.let { nowMillis - it.toLong() * 86_400_000L }
}

/** Messages an age window may walk at most; the age ends the walk long before this. */
const val AGE_WINDOW_SAFETY_CEILING = 10_000

/** Windows the row offers, in order. A WHITELIST, not `entries` minus something (see [SyncWindow.ALL]). */
fun syncWindowChoices(): List<SyncWindow> = listOf(
    SyncWindow.DAYS_30,
    SyncWindow.DAYS_90,
    SyncWindow.YEAR_1,
    SyncWindow.COUNT_100,
    SyncWindow.COUNT_1000,
    SyncWindow.COUNT_10000,
)

/** Whether [chosen] vs [current] drops JMAP sync cursors: the delta path ignores the window. */
fun syncWindowChanged(current: SyncWindow, chosen: SyncWindow): Boolean = chosen != current

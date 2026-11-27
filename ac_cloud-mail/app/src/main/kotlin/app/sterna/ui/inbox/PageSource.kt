package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.components.EmptyArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the list is showing: a single folder, the unified inbox, or one account's unread mail. */
internal sealed interface Sel {
    data class Folder(val id: String?) : Sel
    data object Unified : Sel

    /** Every folder of the current account except Trash, Junk/Spam, Sent and Drafts, filtered to
     * unread. Its folders live in [PageKey.unreadScopes] and are not re-read when used, so the
     *  rows on screen and the rows a bulk action reaches are one set. */
    data object Unread : Sel
}

/** Inputs that, together, determine the current paged source. */
internal data class PageKey(
    val sel: Sel,
    // (account id, folder id) pairs, never bare ids: a bare id also matches a same-server
    // sibling's colliding folder and a removed account's leftovers (#121).
    val unifiedScopes: List<Pair<String, String>>,
    val unreadScopes: List<Pair<String, String>>,
    val sort: SortOrder,
    val unreadOnly: Boolean,
    val conversationView: Boolean,
    // The active account, so a switch re-subscribes the pager even when the new inbox shares the
    // old one's mailbox id (JMAP numbers mailboxes per account, so they often collide).
    val accountId: String? = null,
    // The extra filters and the sender grouping of the view (starred, attachments, codes, by sender).
    val shape: ListShape = ListShape.NONE,
)

/** The key [InboxViewModel.pagedEmails] pages from. Every emission rebuilds the pager, so the list
 *  jumps to the top — hence [distinctUntilChanged], here and not in the caller, DataStore
 * republishing all `Preferences` on every write. Needs equality on every [PageKey] member. */
internal fun pageKeyFlow(
    selection: Flow<Sel>,
    unifiedInboxScopes: Flow<List<Pair<String, String>>>,
    unreadViewScopes: Flow<List<Pair<String, String>>>,
    sortOrder: Flow<SortOrder>,
    unreadOnly: Flow<Boolean>,
    conversationView: Flow<Boolean>,
    currentAccountId: Flow<String?>,
    listShape: Flow<ListShape>,
): Flow<PageKey> =
    combine(selection, unifiedInboxScopes, sortOrder, unreadOnly, conversationView) {
            sel, scopes, sort, unread, conversation ->
        PageKey(sel, scopes, emptyList(), sort, unread, conversation)
    }.combine(unreadViewScopes) { key, scopes -> key.copy(unreadScopes = scopes) }
        .combine(currentAccountId) { key, accountId -> key.copy(accountId = accountId) }
        .combine(listShape) { key, shape -> key.copy(shape = shape) }
        .distinctUntilChanged()

/**
 * #1952 (applied by [InboxViewModel.pagedEmails], after [pageKeyFlow]): a key carries only the scopes ITS view pages. The unread view's folder list starts empty and arrives from the store
 * a moment later; left in a folder's key it rebuilt that folder's pager (and re-ran its query) on every cold start, for a
 * list that never reads it. The same for the unified inbox's scopes.
 */
internal fun pageKeyForItsView(key: PageKey): PageKey = when (key.sel) {
    is Sel.Folder -> key.copy(unifiedScopes = emptyList(), unreadScopes = emptyList())
    Sel.Unified -> key.copy(unreadScopes = emptyList())
    Sel.Unread -> key.copy(unifiedScopes = emptyList())
}

/** The folders the "unread" view pages: every folder of [accountId] except Trash, Junk/Spam, Sent
 * and Drafts. Pairs, never bare folder ids (#121/#31); the role is judged, never the name. A
 *  losing IMAP trash stays in, so a shared `Shared/…/Trash` is paged, deleted mail and all (debt). */
internal fun unreadViewScopes(accountId: String?, folders: List<Mailbox>): List<Pair<String, String>> {
    if (accountId == null) return emptyList()
    return folders.filterNot { it.role?.trim()?.lowercase() in UNREAD_VIEW_EXCLUDED_ROLES }
        .map { accountId to it.id }
}

/** The folders a pull-to-refresh in the "unread" view must sync on top of the inbox: [scopes] of
 *  [accountId], minus [inboxId] (which `includeInbox` covers) and minus other accounts' (#121/#31).
 *  Syncing the inbox alone answers "no unread mail" for folders the cache was never asked about. */
internal fun unreadRefreshTargets(
    accountId: String?,
    scopes: List<Pair<String, String>>,
    inboxId: String?,
): Set<String> {
    if (accountId == null) return emptySet()
    return scopes.filter { it.first == accountId }
        .map { it.second }
        .filterNot { it == inboxId }
        .toSet()
}

/** The mailbox the unarchive-on-reply hook (#50) may fire for after a multi-folder refresh: the
 * account's inbox, and only if [refreshedIds] says it came back. Never "the first refresh":
 *  `onInboxRefreshed` re-files threads and would move mail nobody asked to move. */
internal fun refreshedInboxId(refreshedIds: List<String>, inboxId: String?): String? =
    inboxId?.takeIf { it in refreshedIds }

/** Whether the list is filtered to unread — one answer, for the pager and for "Select all".
 * One function called from both: the pager forcing the filter while `selectAll` reads the raw
 *  toggle is the destructive half of #126 — "Select all" then "Delete" trashes every read message. */
internal fun listUnreadOnly(sel: Sel, toggle: Boolean): Boolean = listUnreadOnly(sel == Sel.Unread, toggle)

/** The same decision asked by the screen, which holds a flag and not a [Sel]. An overload that
 *  delegates, never a second spelling of `|| toggle`: the empty state and the pager must agree. */
internal fun listUnreadOnly(unreadView: Boolean, toggle: Boolean): Boolean = unreadView || toggle

/** The number the drawer's "unread" entry carries: [Mailbox.unreadForList] summed over the scope
 * that view pages. Never a second exclusion rule: no role is named here, the scope is the
 * argument. Over-counts in conversation view (debt). */
internal fun unreadViewCount(
    accountId: String?,
    scopes: List<Pair<String, String>>,
    folders: List<Mailbox>,
): Int {
    if (accountId == null) return 0
    val inScope = scopes.filter { it.first == accountId }.map { it.second }.toSet()
    return folders.filter { it.id in inScope }.sumOf { it.unreadForList }
}

/** What an empty list draws. String ids rather than text: the words are resolved in the screen's
 *  configuration, so a hot locale switch redraws them. */
internal data class EmptyScene(
    val art: EmptyArt,
    val title: Int,
    val body: Int,
    val clearsFilter: Boolean,
)

/** Which empty scene an empty list shows. The unread view selects no mailbox, so the
 *  `selectedMailboxId == null` branch would claim "your inbox is empty" over the whole account; it
 *  gets folder art and unread words, and no button, having no funnel to lift. */
internal fun emptyListScene(
    unreadView: Boolean,
    unreadToggle: Boolean,
    unified: Boolean,
    selectedMailboxId: String?,
    folderRole: String?,
): EmptyScene {
    val art = when {
        unreadView -> EmptyArt.FOLDER
        unified || selectedMailboxId == null || folderRole == "inbox" -> EmptyArt.INBOX_ZERO
        folderRole == "trash" -> EmptyArt.TRASH
        else -> EmptyArt.FOLDER
    }
    val filtered = listUnreadOnly(unreadView, unreadToggle)
    return EmptyScene(
        art = art,
        title = when {
            filtered -> R.string.empty_unread_title
            art == EmptyArt.TRASH -> R.string.empty_trash_title
            art == EmptyArt.FOLDER -> R.string.empty_folder_title
            else -> R.string.empty_inbox_title
        },
        body = when {
            // Before the funnel's arm: empty_unread_body ends on "turn off the unread filter",
            // which this view has no button for, and its own sentence says "your folders".
            unreadView -> R.string.empty_unread_view_body
            filtered -> R.string.empty_unread_body
            art == EmptyArt.TRASH -> R.string.empty_trash_body
            art == EmptyArt.FOLDER -> R.string.empty_folder_body
            else -> R.string.empty_inbox_body
        },
        clearsFilter = unreadToggle && !unreadView,
    )
}

/** The roles the "unread" view never pages: Trash and Junk/Spam because what is thrown away is
 *  not waiting to be read, Sent and Drafts because their unread state means nothing (#82). */
private val UNREAD_VIEW_EXCLUDED_ROLES = setOf("trash", "junk", "spam", "sent", "drafts")

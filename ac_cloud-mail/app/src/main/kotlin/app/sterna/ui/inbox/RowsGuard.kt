package app.sterna.ui.inbox

import app.sterna.core.data.mail.InboxRow

/** How long the list waits, after the selection changed, before handing the screen back to the
 *  rows Paging is presenting. Safety valve only — rule 6 of [advanceRowsGuard] spends it. */
internal const val ROWS_GUARD_GIVE_UP_MS = 800L

/** A floor under [ROWS_GUARD_GIVE_UP_MS], pinned by `RowsGuardTest`: shortened to a few
 *  milliseconds the valve fires before the new pager exists (`Sel.Folder` awaits a DataStore read)
 *  and the screen goes straight back to the previous folder's rows. */
internal const val ROWS_GUARD_GIVE_UP_FLOOR_MS = 500L

/** The selection the rows on screen are supposed to be describing. Invariant the whole guard
 *  rests on: every member here must also be a member of [PageKey], or a change arms the guard
 *  without rebuilding the pager, and nothing is left to disarm it. */
internal data class ListKey(
    val accountId: String?,
    val selectedMailboxId: String?,
    val unified: Boolean,
    val unreadView: Boolean,
)

/** The cheap identity of the rows Paging is presenting: how many, and which row is first — keyed by
 *  account and id, never the id alone, or an account switch reads as "nothing moved". O(1) on
 *  purpose: it runs on every recomposition. */
internal fun rowsSignature(itemCount: Int, first: InboxRow?): String =
    "$itemCount|${first?.email?.accountId}|${first?.email?.id}"

/** Whether the rows Paging is presenting demonstrably belong to a selection other than [key].
 * Both ends of the snapshot are asked and one suffices: `withLocalDrafts` prefixes rows of the
 *  selected folder, so a genuine Drafts row can vouch for twenty foreign ones. [memberFolder]: the folder also lists rows filed
 *  under another mailbox (#1837), so a row's mailbox proves nothing there. */
internal fun rowsForeign(key: ListKey, first: InboxRow?, last: InboxRow?, memberFolder: Boolean = false): Boolean = when {
    first == null -> false
    key.unified || key.unreadView -> false
    key.accountId == null || key.selectedMailboxId == null -> false
    else -> listOfNotNull(first, last).any {
        // #1837: a folder that lists messages filed under ANOTHER mailbox ([memberFolder], from `email_mailboxes`) owns rows whose
        // `mailboxId` is not its own, so there the mailbox cannot prove a row foreign (the account still can).
        it.email.accountId != key.accountId || (!memberFolder && it.email.mailboxId != key.selectedMailboxId)
    }
}

/** Whether the rows on screen still belong to the previous selection. [baseline] is the signature
 *  of what was on screen when the selection changed; [sawLoading] that a refresh has been seen. */
internal data class RowsGuard(
    val key: ListKey,
    val baseline: String,
    val sawLoading: Boolean,
    val stale: Boolean,
)

/**
 * One step of the guard, run on every recomposition. `LazyPagingItems` keeps presenting the previous
 */
internal fun advanceRowsGuard(
    prev: RowsGuard,
    key: ListKey,
    presented: String,
    refreshLoading: Boolean,
    gaveUp: Boolean,
    // No default, on purpose: an omitted belonging test is a guard that silently goes back to
    // disarming on the previous selection's own activity. A source lint pins what each site says.
    foreign: Boolean,
): RowsGuard = when {
    key != prev.key -> RowsGuard(key, baseline = presented, sawLoading = refreshLoading, stale = true)
    foreign -> prev.copy(stale = true)
    !prev.stale -> prev
    presented != prev.baseline -> prev.copy(stale = false)
    prev.sawLoading && !refreshLoading -> prev.copy(stale = false)
    gaveUp && !refreshLoading -> prev.copy(stale = false)
    else -> prev.copy(sawLoading = prev.sawLoading || refreshLoading)
}

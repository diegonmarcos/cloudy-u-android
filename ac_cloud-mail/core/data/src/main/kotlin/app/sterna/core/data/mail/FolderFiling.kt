package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email

/**
 * #1837: one list row per message (the Room key is account + id), so a message that is in the Inbox AND a label is filed under
 * ONE mailbox. Which one is decided by the Rust core (mail-core-rs query.rs `folder_rank`: Trash, Junk, Drafts, Inbox, Sent, a
 * label, then Archive and the other view-only roles; a tie takes the smaller id) from the message's memberships alone, and the
 * feed carries it as `primary`. So the Inbox pass and the label pass write the SAME row: nothing is re-filed back and forth.
 */
internal object FolderFiling {
    /** What one feed page of [mailboxId] writes: [rows] (each message with the mailbox it is filed under) and the ids to delete. */
    class Plan(val rows: List<Pair<Email, String>>, val gone: List<String>)

    /**
     * [emails]: the page's messages (members of [mailboxId]); [removed]: memberships that ended here; [moved]: the removed ones the
     * store still holds in another mailbox, which are re-filed under their [primary] instead of deleted; [inRoom]: the ids the app
     * holds under [mailboxId]. A row the app mutated a moment ago is never touched ([isProtected]).
     */
    fun plan(
        mailboxId: String,
        emails: List<Email>,
        moved: List<Email>,
        removed: List<String>,
        primary: Map<String, String>,
        inRoom: Set<String>,
        isProtected: (String) -> Boolean,
    ): Plan {
        val kept = moved.filter { primary[it.id] != null }
        val keptIds = kept.map { it.id }.toHashSet()
        val rows = (emails.map { it to (primary[it.id] ?: mailboxId) } + kept.map { it to primary.getValue(it.id) })
            .filterNot { (e, _) -> isProtected(e.id) }
        val gone = removed.filter { it in inRoom && it !in keptIds && !isProtected(it) }
        return Plan(rows, gone)
    }

    /**
     * The mailbox the Kotlin window sync files each fetched message of [mailboxId] under: the core's [primary] when it knows the
     * message (so the window sync and the core's projection write the SAME row, never re-filing a message that is in the Inbox and
     * a label back and forth while a folder is not yet complete), else [mailboxId], the sync's own rule.
     */
    fun windowFiling(mailboxId: String, emails: List<Email>, primary: Map<String, String>): List<Pair<Email, String>> =
        emails.map { it to (primary[it.id] ?: mailboxId) }
}

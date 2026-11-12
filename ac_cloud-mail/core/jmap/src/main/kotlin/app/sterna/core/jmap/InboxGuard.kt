package app.sterna.core.jmap

import app.sterna.core.jmap.model.Mailbox

/**
 * #1674 owner rule: EVERY message keeps the default Inbox mailbox, whatever other mailboxes
 * (labels, sorter categories such as "01 Inbox - noAlerts", Archive) it is also in. The only app
 * action that may take a message out of the Inbox is an explicit delete (a move to Trash, or
 * `Email/set` destroy) — and a report as spam (move to Junk), the one other place the server's
 * own Inbox heal (jmap-sorter inbox_guard.rs) leaves a message alone.
 *
 * Move, tag, archive, swipe, rule actions all reach the server through [putMembershipPatch];
 * [inboxSafeRemove] decides, per patch, whether the `mailboxIds/<inbox>: null` half survives.
 */
data class InboxGuard(
    val inboxId: String,
    /** Mailboxes a message may leave the Inbox FOR: the Trash and Junk roles. */
    val leaveInboxFor: Set<String>,
) {
    companion object {
        /** The roles a message may leave the Inbox for (an explicit delete, a spam report). */
        val LEAVE_INBOX_ROLES = setOf("trash", "junk")

        /** The guard for one account's mailbox list, or null when it has no Inbox role. */
        fun of(mailboxes: List<Mailbox>): InboxGuard? {
            val inbox = mailboxes.firstOrNull { it.role.equals("inbox", ignoreCase = true) }?.id ?: return null
            val exits = mailboxes.filter { m -> m.role?.lowercase() in LEAVE_INBOX_ROLES }.map { it.id }.toSet()
            return InboxGuard(inbox, exits)
        }
    }
}

/**
 * The mailbox a membership patch may REMOVE, given the [guard]: [remove] unchanged unless it is the
 * Inbox and [add] is not a delete / spam target, in which case null (the patch only ADDS [add]).
 * A null [guard] (the account's mailboxes were never fetched) leaves [remove] unchanged.
 */
fun inboxSafeRemove(guard: InboxGuard?, add: String?, remove: String?): String? {
    if (guard == null || remove == null || remove != guard.inboxId) return remove
    return if (add != null && add in guard.leaveInboxFor) remove else null
}

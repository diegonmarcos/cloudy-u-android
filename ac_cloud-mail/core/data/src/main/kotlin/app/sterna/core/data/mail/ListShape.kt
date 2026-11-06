package app.sterna.core.data.mail

import app.sterna.core.data.text.AuthClass
import app.sterna.core.data.text.AuthClassifier

/** The "G0 _ Auth" filter: off, Ga Code, Gb Link to auth, or either (Ga + Gb). Exclusive among themselves. */
enum class AuthFilter(val classes: List<AuthClass>) {
    OFF(emptyList()),
    CODES(listOf(AuthClass.CODE)),
    LINKS(listOf(AuthClass.LINK)),
    ANY(listOf(AuthClass.CODE, AuthClass.LINK)),
}

/**
 * What the message list is narrowed and grouped by, beyond the unread funnel and the sort: the
 * combinable filters (starred, with attachments, the auth class) and whether rows are grouped by SENDER
 * (one address) or by sender DOMAIN ([SenderDomain]) instead of by conversation. All-off is the list as it
 * always was, and adds nothing to its SQL.
 */
data class ListShape(
    val starred: Boolean = false,
    val attachments: Boolean = false,
    val auth: AuthFilter = AuthFilter.OFF,
    val bySender: Boolean = false,
    val byDomain: Boolean = false,
    /** One side of the Alerts split ([InboxSplit]); [alertRules] must be set for it to narrow. */
    val alerts: AlertScope = AlertScope.ALL,
    val alertRules: InboxSplitRules? = null,
) {
    /** Grouped by who sent it (an address or a domain), so the Sent folders add nothing to a group. */
    val bySenderOrDomain: Boolean get() = bySender || byDomain

    /** The extra WHERE terms for rows of [table] (each begins with " AND "), constants only. */
    fun whereSql(table: String): String = buildString {
        if (starred) append(" AND $table.flagged = 1")
        if (attachments) append(" AND $table.hasAttachment = 1")
        if (auth != AuthFilter.OFF) append(" AND $table.authClass IN (${auth.classes.joinToString { it.value.toString() }})")
        append(InboxSplit.whereSql(alerts, alertRules, table))
    }

    companion object {
        val NONE = ListShape()

        /** The grouping key's prefix for a sender group, so a sender key can never equal a thread id. */
        const val SENDER_KEY_PREFIX = "\u0001sender\u0001"

        fun senderKey(fromEmail: String?): String? =
            fromEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { SENDER_KEY_PREFIX + it }
    }
}

/**
 * The auth-class index: decided ONCE when a row is cached (from subject + preview), refined when the body
 * arrives, and filled in for older rows by [MailRepository.indexAuth], so the list filters on a column
 * instead of re-parsing every message. The rule itself is [AuthClassifier] ("G0 _ Auth").
 */
object AuthIndex {
    /** What a mapper stores: null while there is no preview to judge, so the row is classified later. */
    fun indexed(subject: String?, preview: String?): Int? =
        if (preview == null) null else AuthClassifier.classify(subject, preview).value

    /**
     * The backfill loop, apart from Room so it can be run on its own: take a batch of unclassified rows with
     * [next], classify each, store it with [store], until none is left. Returns how many it classified.
     */
    suspend fun backfill(
        batch: Int,
        next: suspend (limit: Int) -> List<app.sterna.core.data.db.AuthIndexRow>,
        store: suspend (accountId: String, id: String, authClass: Int) -> Unit,
    ): Int {
        var total = 0
        while (true) {
            val rows = next(batch)
            if (rows.isEmpty()) return total
            rows.forEach { store(it.accountId, it.id, AuthClassifier.classify(it.subject, it.preview).value) }
            total += rows.size
        }
    }
}

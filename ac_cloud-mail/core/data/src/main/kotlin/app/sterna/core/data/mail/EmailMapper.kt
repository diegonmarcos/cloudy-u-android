package app.sterna.core.data.mail

import app.sterna.core.data.db.ConversationRow
import app.sterna.core.data.db.EmailAttachments
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.FtsHit
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections

/**
 * The `$draft` keyword of cached list rows, kept in memory only — the `emails` table stores just
 */
private const val RECENT_DRAFTS_MAX = 2000
private val recentDrafts: MutableSet<String> = Collections.synchronizedSet(
    Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > RECENT_DRAFTS_MAX
        },
    ),
)

/** Remember whether [accountId]'s email [id] is a draft, so [toEmail] can replay the `$draft`
 *  keyword. Keyed by (accountId, id): JMAP ids are unique only within their account (issue #31),
 *  so a bare id would flag a sibling account's message as a draft. */
internal fun recordDraft(accountId: String, id: String, isDraft: Boolean) {
    val key = draftKey(accountId, id)
    if (isDraft) recentDrafts.add(key) else recentDrafts.remove(key)
}

private fun draftKey(accountId: String, id: String) = "$accountId\u0000$id"

/** Map a grouped conversation row to the domain [InboxRow] (unread = any in thread). */
internal fun ConversationRow.toInboxRow(): InboxRow =
    InboxRow(
        email = email.toEmail(),
        threadCount = threadCount,
        unread = threadUnread == 0,
        threadExpandable = threadTotal > 1,
        unreadCount = groupUnread,
    )

internal fun Email.toEntity(accountId: String, mailboxId: String): EmailEntity {
    val sender = from.firstOrNull()
    // The draft flag still doesn't fit the row schema — remember it aside for [toEmail].
    recordDraft(accountId, id, isDraft)
    return EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        threadId = threadId,
        subject = subject,
        preview = preview,
        receivedAt = receivedAt,
        fromName = sender?.name,
        fromEmail = sender?.email,
        seen = isSeen,
        flagged = isFlagged,
        hasAttachment = hasAttachment,
        sortKey = epochMillis(receivedAt),
        // Persisted since v17: every fetch path that caches a row asks the server for `to`, so
        // what lands here is the message's real recipient set — empty included (a draft with no
        // addressee yet), which is exactly what the row should then show.
        recipientsJson = EmailRecipients.encode(to),
        // Persisted since v20: where the sender says an answer must go when that is not the From
        // (a list, a support@ behind a no-reply@). Empty for almost every message, which stores
        // nothing and reads back as "answer the sender".
        replyToJson = EmailRecipients.encode(replyTo),
        // Persisted since v21: the recipients in copy. The row is what a reopened draft and a
        // "reply all" are rebuilt from, so a Cc that stops at the JMAP object is a Cc the user
        // loses. Two separate columns: a blind copy is blind, and is never folded into the Cc.
        ccJson = EmailRecipients.encode(cc),
        bccJson = EmailRecipients.encode(bcc),
        // Persisted since v28: which files this message carries, so the LIST can name them. Metadata
        // only -- no bytes. Like the three fields above it, every caching path must supply it or the
        // `@Upsert` erases it; that is why the JMAP fetches share one property list.
        attachmentsJson = EmailAttachments.encode(attachments),
        // Schema v30: the auth class (G0 _ Auth), decided once here from the preview (see [AuthIndex]).
        authClass = AuthIndex.indexed(subject, preview),
        // Schema v31: the sender's registrable domain ("Group by Domain"), from the same address as fromEmail.
        fromDomain = SenderDomain.indexed(sender?.email),
        // Schema v32 (#1802): the Message-ID, the fleet mail API's key for a sent message's delivery stages.
        messageId = messageId.firstOrNull()?.takeIf { it.isNotBlank() },
    )
}

internal fun EmailEntity.toEmail(): Email = Email(
    id = id,
    accountId = accountId,
    mailboxId = mailboxId,
    threadId = threadId,
    subject = subject,
    preview = preview,
    receivedAt = receivedAt,
    from = if (fromEmail != null || fromName != null) {
        listOf(EmailAddress(name = fromName, email = fromEmail.orEmpty()))
    } else {
        emptyList()
    },
    to = EmailRecipients.decode(recipientsJson),
    messageId = listOfNotNull(messageId),
    // Persisted since v20: a row cached before the column existed, or a message with no
    // Reply-To at all, is NULL and decodes to an empty list — "no instruction, answer the sender".
    replyTo = EmailRecipients.decode(replyToJson),
    // Persisted since v21: each copy field comes back from its OWN column. Reading the blind
    // copies into `cc` would put an address the sender hid into the composer's visible Cc, and
    // the next reply-all would mail it to everyone.
    cc = EmailRecipients.decode(ccJson),
    bcc = EmailRecipients.decode(bccJson),
    hasAttachment = hasAttachment,
    // Persisted since v28. A row cached before the column existed decodes to an empty list, so the
    // row draws no chips rather than a wrong number of them -- and `fileAttachmentParts()` is then
    // the SAME classifier the reader uses, applied to the same parts, so a message cannot show two
    // chips in the list and three rows when opened.
    attachments = EmailAttachments.decode(attachmentsJson),
    keywords = buildMap {
        if (seen) put("\$seen", true)
        if (flagged) put("\$flagged", true)
        if (draftKey(accountId, id) in recentDrafts) put("\$draft", true)
    },
)

/**
 * A crawled/​cached [Email] → a search-index row. Headers only: body search is served live by the
 * server's own full-text index (unioned into the results), not re-indexed client-side.
 */
internal fun Email.toFts(accountId: String): EmailFtsEntity {
    val sender = from.firstOrNull()
    return EmailFtsEntity(
        emailId = id,
        accountId = accountId,
        mailboxId = mailboxIds.keys.firstOrNull() ?: mailboxId.orEmpty(),
        threadId = threadId,
        subject = subject.orEmpty(),
        sender = listOfNotNull(sender?.name, sender?.email).joinToString(" ").trim(),
        body = "",
        preview = preview,
        receivedAt = receivedAt,
        fromName = sender?.name,
        fromEmail = sender?.email,
        seen = isSeen,
        flagged = isFlagged,
        hasAttachment = hasAttachment,
        sortKey = epochMillis(receivedAt),
    )
}

/** A full-text search hit → a list-renderable [Email] (self-contained; no join to `emails`). */
internal fun FtsHit.toEmail(): Email = Email(
    id = emailId,
    accountId = accountId,
    mailboxId = mailboxId,
    threadId = threadId,
    subject = subject.ifBlank { null },
    preview = preview,
    receivedAt = receivedAt,
    from = if (fromEmail != null || fromName != null) {
        listOf(EmailAddress(name = fromName, email = fromEmail.orEmpty()))
    } else {
        emptyList()
    },
    hasAttachment = hasAttachment,
    keywords = buildMap {
        if (seen) put("\$seen", true)
        if (flagged) put("\$flagged", true)
        if (draftKey(accountId, emailId) in recentDrafts) put("\$draft", true)
    },
)

private fun epochMillis(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()
        ?.toEpochMilli() ?: 0L
}

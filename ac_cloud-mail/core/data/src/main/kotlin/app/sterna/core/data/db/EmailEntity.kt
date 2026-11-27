package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index

/** Cached email for a mailbox list view. Composite `(accountId, id)` key: a JMAP id is unique
 *  only within its account; two accounts of one login (#31) can share an [id]. Index column
 *  order matters — it serves scope/sort/join queries in one (#121). */
@Entity(
    tableName = "emails",
    primaryKeys = ["accountId", "id"],
    // #1952: the last index COVERS the three aggregates of the domain-grouped list (the default view), so they read the
    // narrow index and never the wide message rows; `id` is in it because the grouping key falls back to it.
    indices = [
        Index("mailboxId"), Index(value = ["accountId", "mailboxId", "sortKey"]),
        Index(value = ["accountId", "mailboxId", "fromDomain", "sortKey", "seen", "id"]),
    ],
)
data class EmailEntity(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String?,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    /** Epoch millis derived from receivedAt, for ordering. */
    val sortKey: Long,
    /** `To:` recipients, JSON-encoded (v17); shown instead of sender in Sent/Drafts (#59). Null
     *  decodes to []. */
    val recipientsJson: String? = null,
    /** `Reply-To:` addresses, JSON-encoded (v20); null decodes to [] ("answer the sender"). */
    val replyToJson: String? = null,
    /** `Cc:` recipients, JSON-encoded (v21); rebuilds a reopened draft's Cc and "reply all". */
    val ccJson: String? = null,
    /** `Bcc:` recipients, JSON-encoded (v21). Kept separate from [ccJson] — a blind copy
     *  must not show to others. */
    val bccJson: String? = null,
    /** IMAP UIDVALIDITY [id]'s UID was read under (v25, #99); decoupled from
     *  `mailbox_uidvalidity` so a renumbered folder can't make a purge hit different, live mail. */
    val uidValidity: Long? = null,
        /** The message's file parts, JSON-encoded (v28) -- metadata only, never bytes. What lets the
         *  LIST draw a chip per file, and lets a tap on one resolve to a download without opening the
         *  message first. Null decodes to [], which is what a row cached before this column existed
         *  reads back as: no chips, the honest answer for a row nothing has said anything about yet.
         *  Every fetch path that caches a row must fill it ([EMAIL_LIST_PROPERTIES]) -- the `@Upsert`
         *  replaces the row whole, so one path omitting it erases what the others stored. */
    val attachmentsJson: String? = null,
    // Schema v30: rule "G0 _ Auth" -- 1 Ga Code, 2 Gb Link to auth, 0 Gc No Auth; NULL = not classified yet.
    val authClass: Int? = null,
    // Schema v31: the sender's registrable domain ("Group by Domain", [app.sterna.core.data.mail.SenderDomain]);
    // "" when the From address has none, NULL = not indexed yet.
    val fromDomain: String? = null,
    /** Schema v32 (#1802): the RFC 5322 Message-ID, the key the fleet mail API files a SENT message under. NULL = not known. */
    val messageId: String? = null,
)

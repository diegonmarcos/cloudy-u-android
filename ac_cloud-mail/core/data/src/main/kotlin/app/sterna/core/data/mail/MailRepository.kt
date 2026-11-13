package app.sterna.core.data.mail

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.sterna.core.data.text.AuthClassifier
import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.plainTextToHtml
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.DiscoveredMailAccount
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.UnifiedInbox
import app.sterna.core.data.account.accountKeyOf
import app.sterna.core.data.account.autocryptHeaderValue
import app.sterna.core.data.account.resolveExistingLogin
import app.sterna.core.data.account.resolveExistingLoginAmong
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ForeignScript
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.VACATION_SCRIPT_NAME
import app.sterna.core.data.filter.enabledRuleCount
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.unsubscribe.UnsubscribeClient
import app.sterna.core.data.unsubscribe.UnsubscribeResult
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailBodyEntity
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEdit
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxLogic.outboxPgpModeName
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RECENT_EMAIL_ROW_COLUMNS
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.db.SnoozedEntity
import app.sterna.core.data.db.SnoozedListRow
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.RecentContactEntity
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.data.db.MailboxUnread
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.OAuthTokens
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.WindowWalk
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.inlineArmorKind
import app.sterna.core.imap.decodeMailboxPath
import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.SieveScript
import app.sterna.core.jmap.model.SubmissionEnvelope
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.util.UUID

/** Cap on changes applied incrementally before falling back to a full query. Also this file's
 *  SQLite bound-variable bound: every `IN (...)` id list is chunked at it, under SQLite's 999. */
internal const val MAX_CHANGES = 200

/** How long a locally mutated id is protected from sync eviction (ms). Must outlast the server's
 *  read-after-write delay on a move-back, or the next reconcile prunes the message. */
private const val RECENT_MUTATION_MS = 45_000L

private const val PAGE_SIZE = 50

/** The per-id SetError type meaning the id does not exist in the account (RFC 8620 §5.3). Action
 *  paths receiving it prune the cached row. */
private const val SET_ERROR_NOT_FOUND = "notFound"

/** Per-APPEND fill target for the conversation list: rows are threads, so one network page of
 *  messages can add almost no visible rows. Keep fetching until this many NEW representatives... */
private const val APPEND_THREAD_TARGET = 10

/** ...but never more than this many pages per APPEND: one giant thread must not chain unbounded
 *  fetches. */
private const val MAX_APPEND_FILL_PAGES = 4

/** How many of the inbox's newest messages to prefetch (bodies) into the cache per sync. */
private const val PREFETCH_COUNT = 20

/** Upper bound of messages one offline-window sync asks the server for. */
private const val OFFLINE_MAX_MESSAGES = 20_000

/** Wall-clock budget for the IMAP envelope read the SEND path makes before it may destroy the draft
 *  a message was edited from. It bounds the connect and the reads, NOT the wait for the account's
 *  IMAP mutex, which nothing bounds. Expiry throws, read as "could not be established". */
private const val SEND_PROOF_BUDGET_MS = 10_000

/** The same budget for the EMPTYING gestures, before they may destroy the server copy of a draft
 *  judged empty. Bounded rather than [ImapBudget.NO_BUDGET]: this gesture is reached when there is
 *  no network (#95), and an addressing that could not be established is not an empty one. */
private const val EMPTIED_DRAFT_PROOF_BUDGET_MS = 10_000

/** Largest body row we will write (chars of JSON, body + inline images). A row past SQLite's 2 MB
 *  cursor window can be inserted but never read back. */
internal const val MAX_CACHED_BODY_CHARS = 1_000_000

/** Whether a body row of this size may be written without becoming unreadable later. */
internal fun fitsBodyCache(bodyJson: String, inlineImagesJson: String): Boolean =
    bodyJson.length + inlineImagesJson.length <= MAX_CACHED_BODY_CHARS

/** [cached]'s read state made honest again: `$seen` taken from the `emails` row ([rowSeen]). A
 *  cached body is a snapshot whose `$seen` is frozen at write time, so every later open would
 *  replay the unread → read transition (#148). [rowSeen] null means UNKNOWN. */
internal fun reconcileCachedSeen(cached: MessageBody, rowSeen: Boolean?): MessageBody {
    if (rowSeen == null || cached.email.isSeen == rowSeen) return cached
    return cached.copy(email = cached.email.copy(keywords = cached.email.keywords + ("\$seen" to rowSeen)))
}

/** The read state [read] reports, or null when it cannot be read — the "unknown"
 *  [reconcileCachedSeen] answers by handing the body back. A locked database must not cost the open. */
internal suspend fun seenOrUnknown(read: suspend () -> Boolean?): Boolean? = runCatching { read() }.getOrNull()

/** Folders an IMAP search walks, in [MailboxDao.searchOrder]'s order. Trash and Junk are left out.
 *  The role decides, never the name, so it holds in every language. */
internal fun searchableFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filterNot { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

/** The complement of [searchableFolderIds]: JMAP (`inMailboxOtherThan`) and the FTS crawl need the
 *  ids. One [NOT_SEARCHED_ROLES] source, so they cannot disagree. */
internal fun excludedSearchFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filter { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

/** The roles a search never walks, as a server or a name table names them. */
internal val NOT_SEARCHED_BASE_ROLES = setOf("trash", "junk", "spam")

/** What a role carries when its folder CLAIMED it and another folder of the same account was
 *  elected to it. Only the roles of [NOT_SEARCHED_BASE_ROLES] are marked; it lives on the CACHE ROW
 *  only, while the FACT of the loss crosses as [isLostRoleClaim] (#174). */
internal const val UNELECTED_ROLE_MARK = "~"

/** The stored role as the search filters may not act on it: a marked role answers as no role at
 *  all. Shared by [MailboxEntity.toMailbox] and [folderRoleMap]. */
internal fun unmarkedRole(role: String?): String? =
    role?.takeUnless { it.startsWith(UNELECTED_ROLE_MARK) }

/** Whether [role], as the CACHE stores it, says "this folder claimed a role and LOST the election".
 *  A VISIBILITY question, never a routing one: it names no role. */
internal fun isLostRoleClaim(role: String?): Boolean =
    role != null && role.startsWith(UNELECTED_ROLE_MARK)

internal val NOT_SEARCHED_ROLES =
    NOT_SEARCHED_BASE_ROLES + NOT_SEARCHED_BASE_ROLES.map { UNELECTED_ROLE_MARK + it }

/** What an action that MOVED NOTHING does with the search-index row — see [noOpEvictionFor]. */
internal enum class NoOpEviction {
    /** Drop the cached row, LEAVE the index row: the message is still in a searched folder. */
    SPARE_INDEX_ROW,

    /** Take both rows: no search looks at the folder, and no re-seed can ever clear a spared row. */
    TAKE_INDEX_ROW,
}

/** Which eviction an action that MOVED NOTHING must use: the index row may stay only where a search
 *  looks. Decided through [excludedSearchFolderIds], so it cannot drift from a search. */
internal fun noOpEvictionFor(mailboxId: String, role: String?): NoOpEviction =
    if (excludedSearchFolderIds(listOf(MailboxIdRole(mailboxId, role))).isEmpty()) {
        NoOpEviction.SPARE_INDEX_ROW
    } else {
        NoOpEviction.TAKE_INDEX_ROW
    }

/** Which of [ids] the cache says were ALREADY in [mailboxId] — the server cannot be asked, an
 *  `Email/set` into the folder a message is in succeeds like any other. [rows] are read before the
 *  move, within the acting account (#31); an id with no cached row is NOT a no-op. */
internal fun idsAlreadyIn(rows: List<EmailEntity>, ids: Set<String>, mailboxId: String): List<String> =
    rows.filter { it.id in ids && it.mailboxId == mailboxId }.map { it.id }

/** The push pass's rows with the opening lines the cache ALREADY holds copied back onto them
 *  (#187): `@Upsert` replaces the whole row, so `preview = null` would blank the column. */
internal fun keepingCachedPreviews(rows: List<EmailEntity>, cached: Map<String, String>): List<EmailEntity> =
    rows.map { row -> if (row.preview != null) row else cached[row.id]?.let { row.copy(preview = it) } ?: row }

/** How a confirmed batch `UID MOVE` splits — see [imapMoveOutcome]. */
internal data class ImapMoveOutcome(
    /** The ids the session can PROVE left the source folder: credit them, drop their rows. */
    val gone: List<String>,
    /** The ids it cannot: NOT "they stayed", but "nothing said". They fail. */
    val unproven: List<String>,
)

/** Which ids of a batch IMAP move really left the source folder, told from the ids nothing said
 *  anything about; both lists keep the arrival order. An empty [confirmedGone] means "nothing is
 *  confirmed", never "nothing moved". */
internal fun imapMoveOutcome(uidToId: Map<Long, String>, confirmedGone: Set<Long>): ImapMoveOutcome {
    val (proved, rest) = uidToId.entries.partition { it.key in confirmedGone }
    return ImapMoveOutcome(gone = proved.map { it.value }, unproven = rest.map { it.value })
}

/** Whether a search answer may be presented as a TOTAL rather than as a floor. [scopeCoversAccount]
 *  is a fact about the account's FOLDER LIST, never a list DERIVED from it. */
internal fun searchAnswerIsTotal(
    scopeCoversAccount: Boolean,
    scanComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = scopeCoversAccount && scanComplete && found < limit

/** [searchAnswerIsTotal] as the IMAP walk asks it: with [knownFolders] empty the walk falls back to
 *  the inbox alone, so what came back describes one folder rather than the account. */
internal fun imapSearchComplete(
    knownFolders: List<String>,
    walkComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = knownFolders.isNotEmpty(),
    scanComplete = walkComplete,
    found = found,
    limit = limit,
)

/** [searchAnswerIsTotal] as a whole-account JMAP query asks it: with nothing cached the search
 *  spreads to the whole account, Trash and Junk included. [cachedFolders] is the RAW folder list,
 *  never the exclusion list derived from it. */
internal fun jmapSearchComplete(
    cachedFolders: List<MailboxIdRole>,
    matchedIds: Int?,
    fetched: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = cachedFolders.isNotEmpty(),
    scanComplete = matchedIds != null && fetched >= matchedIds,
    found = matchedIds ?: fetched,
    limit = limit,
)

/** Refuse an address carrying a line break: the SMTP envelope and RFC 5322 headers are built by
 *  concatenation, so a CR or LF is an injection primitive. */
internal fun requireSingleLineAddresses(addresses: List<String>) {
    require(addresses.none { addr -> addr.any { it == '\r' || it == '\n' } }) {
        "An address contains a line break."
    }
}

/** The composer's chosen `From:` as a header string. It is string-built on the IMAP/SMTP path, so
 *  the display name must be RFC 5322-quoted or 2047-encoded here; JMAP is unaffected (#77). */
internal fun formatFromAddress(name: String?, email: String?): String? = when {
    email.isNullOrBlank() -> null
    else -> OutgoingMime.formatAddress(name, email)
}

/** The composer draft a queued outbox row reopens as (#70). Every per-message choice the ROW holds
 *  has to be here: a field missing from this mapping is not "not filled yet", it is cancelled. */
internal fun outboxDraftOf(
    item: OutboxEntity,
    attachments: List<EmailBodyPart>,
): MailRepository.OutboxDraft = MailRepository.OutboxDraft(
    to = item.recipients.split(",").joinToString(", ") { it.trim() },
    cc = item.cc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
    bcc = item.bcc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
    subject = item.subject,
    body = item.textBody,
    htmlBody = item.htmlBody,
    fromAccountId = item.accountId,
    fromEmail = item.fromEmail,
    attachments = attachments,
    inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    pgpMode = item.pgpMode,
    draftEmailId = item.draftEmailId,
    draftUidValidity = item.draftUidValidity,
    outboxId = item.id,
    requestReceipt = item.requestReceipt,
)

/** The Drafts-list row for a just-saved draft (#63). Every addressing field with a column must be
 *  here: the `@Upsert` replaces the row whole. [inReplyTo]/[references] are dropped by `toEntity`. */
internal fun savedDraftRow(
    accountId: String,
    emailId: String,
    draftMailboxId: String,
    from: EmailAddress,
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    bcc: List<EmailAddress>,
    subject: String,
    body: String,
    inReplyTo: List<String>,
    references: List<String>,
    hasAttachment: Boolean,
): EmailEntity = Email(
    id = emailId,
    accountId = accountId,
    mailboxId = draftMailboxId,
    subject = subject.ifBlank { null },
    preview = body.replace(Regex("\\s+"), " ").trim().take(256).ifBlank { null },
    receivedAt = java.time.Instant.now().toString(),
    from = listOf(from),
    to = to,
    cc = cc,
    bcc = bcc,
    inReplyTo = inReplyTo,
    references = references,
    hasAttachment = hasAttachment,
    keywords = mapOf("\$draft" to true, "\$seen" to true),
).toEntity(accountId, draftMailboxId)

/** Read a cached row through [read]; if reading it fails at all, drop it via [purge] and report a
 *  miss. SQLite throws on reading a row past its cursor window, and throws again on every retry. */
internal suspend fun <T> readCachedOrPurge(
    read: suspend () -> T?,
    purge: suspend () -> Unit,
): T? = runCatching { read() }.getOrElse {
    runCatching { purge() }
    null
}

/** The `List-Unsubscribe` / `List-Unsubscribe-Post` pair read off an IMAP raw source, in that
 *  order. Being a Map, a repeated header keeps the LAST occurrence — accepted. */
internal fun unsubscribeHeadersOf(raw: String): Pair<String?, String?> =
    MimeParser.headerOf(raw, "List-Unsubscribe") to MimeParser.headerOf(raw, "List-Unsubscribe-Post")

/** `Disposition-Notification-To` (RFC 8098) read off an IMAP raw source. That name and nothing
 *  else: `Return-Receipt-To` and `X-Confirm-Reading-To` are neither written nor read. */
internal fun readReceiptHeaderOf(raw: String): String? =
    MimeParser.headerOf(raw, "Disposition-Notification-To")

/** The address a relay says REALLY wrote this message. ONE function for both protocols (#160). The
 *  rule is the SHAPE of the field name, never a list of vendors: lowercased it must BEGIN with `x-`
 *  AND CONTAIN `original-from`. The FIRST occurrence, because it is deterministic. */
internal fun originalSenderHeaderOf(headers: List<Pair<String, String>>): String? =
    headers.firstOrNull { (name, _) ->
        val lower = name.lowercase()
        lower.startsWith("x-") && lower.contains("original-from")
    }
        ?.let { MimeParser.decodedHeaderValue(it.second) }
        ?.takeIf { it.isNotBlank() }

/** Whether an opened message's body may be written to the body cache. On JMAP a null origin means
 *  both "carries none" and "the read did not happen", and caching the second says "no origin" FOR
 *  EVER; IMAP always caches, the header was read off the source already in hand. */
internal fun cachesBodyAfterOriginRead(protocol: MailProtocol, originReadSucceeded: Boolean): Boolean =
    protocol == MailProtocol.IMAP || originReadSucceeded

/** Whether the IMAP message whose source is [raw] asks for a read receipt — `null` for "cannot
 *  say". A blank source is UNKNOWN: `fetchSource` answers "" when the FETCH carries no body item. */
internal fun receiptRequestedInSource(raw: String): Boolean? {
    if (raw.isBlank()) return null
    return ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)).isNotEmpty()
}

/** The same for JMAP, from the property answer. */
internal fun receiptRequestedInProperty(header: String?, serverRefusesProperty: Boolean): Boolean? =
    if (serverRefusesProperty) null else ReadReceiptHeader.recipients(header).isNotEmpty()

/** Whether the message source [raw] is an UNKNOWN rather than a message. A blank source is NOT "an
 *  empty draft": parsed it is a blank composer that looks usable, whose send destroys the server
 *  original. It judges the SOURCE, never a body. */
internal fun sourceCouldNotBeRead(raw: String): Boolean = raw.isBlank()

/** The octets to write when a message is saved as `.eml`: [raw] one byte per char, ISO-8859-1 and
 *  never UTF-8 — that String is a byte CONTAINER, and UTF-8 would double every octet above 0x7F. */
internal fun rawSourceBytes(emailId: String, raw: String): ByteArray {
    if (sourceCouldNotBeRead(raw)) {
        throw MessageUnavailableException("Source of $emailId came back empty; the server did not give us the message.")
    }
    return raw.toByteArray(Charsets.ISO_8859_1)
}

/** An attachment's octets never came back: zero of them. `isEmpty()`, NOT the blank-equivalent
 *  [sourceCouldNotBeRead] uses: a file of spaces IS a file. */
internal fun attachmentCouldNotBeRead(bytes: ByteArray): Boolean = bytes.isEmpty()

/** Refuse to hand on an attachment that [attachmentCouldNotBeRead]: a 0-byte `rapport.pdf` in
 *  Downloads carries the real name she chose, and the save path announces `message_export_saved` on
 *  the next line. `MessageUnavailableException` is reused for `readFailureStringRes` (#159). */
fun requireAttachmentBytes(emailId: String, partKey: String, bytes: ByteArray) {
    if (attachmentCouldNotBeRead(bytes)) {
        throw MessageUnavailableException("Attachment $partKey of $emailId came back empty; the server did not give us the bytes.")
    }
}

/** Run [stage] — persisting an outbox item's payload after its row is inserted — and, if it throws,
 *  [rollback] the row: an orphan would be re-armed by [MailRepository.unfinishedOutbox] and sent
 *  amputated (#70). */
internal suspend fun <T> stageOrRollback(rollback: suspend () -> Unit, stage: suspend () -> T): T =
    try {
        stage()
    } catch (t: Throwable) {
        rollback()
        throw t
    }

/** Arm delivery of the outbox row [id] after [holdMs], and answer [id] either way — a failure is
 *  written through [note] instead of being thrown: the row is already staged and `unfinishedOutbox`
 *  re-arms it, so a throw costs a DELAY, while relaying it makes a quick reply send twice. */
internal suspend fun armOrNote(
    id: Long,
    holdMs: Long,
    scheduler: OutboxScheduler?,
    note: suspend (String) -> Unit,
): Long {
    val failure = runCatching { scheduler?.schedule(id, holdMs) }
        .rethrowIfCancelled()
        .exceptionOrNull()
        ?: return id
    // Recording the reason must not become a second way to fail the queueing it reports on.
    runCatching { note(unarmedNote(failure)) }.rethrowIfCancelled()
    return id
}

/** What an unarmed row is told to carry. Diagnostic English, NOT a translated string: the row takes
 *  the sentinel `OutboxLogic.NOT_ARMED` and the screen translates it. Its opening is
 *  [OutboxLogic.NOT_ARMED_LEGACY_PREFIX]: rows written by 1.5.2/1.5.3 are recognised by it. */
internal fun unarmedNote(t: Throwable): String {
    val reason = t.message?.takeIf { it.isNotBlank() }
        ?: t.javaClass.simpleName.takeIf { it.isNotBlank() }
        ?: t.javaClass.name
    return "${OutboxLogic.NOT_ARMED_LEGACY_PREFIX} $reason. The message stays in the Outbox."
}

/**
 * What queueing a send does with a pre-built entity, and what it keeps in the row.
 * @property redactBody whether the body, the HTML and the attachments must be blanked in the row.
 */
internal data class OutboxPayload(
    val entityFile: String?,
    val redactBody: Boolean,
)

/** The two decisions [MailRepository.enqueueSend] makes about a pre-built entity. Storing the
 *  entity depends on the entity and nothing else; blanking the row still depends on the mode, and
 *  must: only ciphertext may not be kept in the clear at rest. */
internal fun outboxPayload(prebuiltEntity: String?, pgpMode: PgpMode?): OutboxPayload =
    OutboxPayload(
        entityFile = prebuiltEntity,
        redactBody = pgpMode?.encrypts == true && prebuiltEntity != null,
    )

/** The pre-built entity of an outbox row about to be delivered: null when there is none, a hard
 *  failure when the named file cannot be read — the row's own body is blank or a shadow of the
 *  payload, so falling back would send a shell nobody wrote. Called ONCE, before the fork. */
internal fun prebuiltEntityAt(path: String?, readText: (String) -> String?): String? {
    if (path == null) return null
    return readText(path) ?: error("The prepared message payload is missing.")
}

/** The addresses a queued send teaches to the contact suggestions — all of them, or NONE. [learn]
 *  is false on exactly one path: a read receipt is queued on a gesture addressed to nobody. */
internal fun recipientsToLearn(recipients: List<String>, learn: Boolean): List<String> =
    if (learn) recipients else emptyList()

/** The name of the file a queued send's pre-built entity is written to. Nothing looks it up by
 *  name: [OutboxEntity.pgpEntityPath] stores the absolute path, so older rows keep working. */
internal const val PREBUILT_ENTITY_FILE = "prebuilt-entity.mime"

/** Max full-text search matches returned to the UI. */
private const val LOCAL_SEARCH_LIMIT = 100

// Header crawl. What it WANTS, not what it sends: every request is capped to the server's
// advertised maxObjectsInGet ([requestPageSize]).
private const val HEADER_PAGE = 500
private const val HEADER_MAX = 200_000
private const val INDEX_TTL_MS = 10 * 60 * 1000L
/** Give up a crawl pass after this many consecutive page failures (vs. skipping isolated bad pages). */
private const val MAX_CRAWL_ERRORS = 3

/** Rows per INSERT when persisting an Empty-trash snapshot (keeps one statement modest). */
private const val PURGE_SNAPSHOT_INSERT_BATCH = 500

/** Page size when resolving unread ids server-side (RFC 8620 maxObjectsInGet floor). */
private const val UNREAD_RESOLVE_PAGE = 500

/** Upper bound on server-resolved unread ids for one "Mark all read" (20 pages of 500). */
private const val UNREAD_RESOLVE_MAX = 10_000

/** Build the dynamic ORDER BY / WHERE for the paged list. Mailbox ids are bound as parameters; the
 *  sort expression is a fixed whitelist. Pinning is its own entry, [SortOrder.FLAGGED_FIRST] (#111). */
private fun pagingQuery(
    // The folders this list covers, as (account id, mailbox id) PAIRS — see [folderScopeSql].
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    unreadOnly: Boolean,
    shape: ListShape = ListShape.NONE,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    pagingSql(scopes.size, sort, unreadOnly, shape),
    scopes.flatMap { listOf(it.first, it.second) }.toTypedArray(),
)

/** The folder scope of a list query, for rows of [table]: a disjunction of (account id, mailbox id)
 *  PAIRS, ACCOUNT id bound first. A mailbox id is unique only within its account, so a bare
 *  `mailboxId IN (…)` also selects a sibling account's folder of that id (#121). */
internal fun folderScopeSql(scopeCount: Int, table: String): String =
    if (scopeCount == 0) "0" else List(scopeCount) { "($table.accountId = ? AND $table.mailboxId = ?)" }.joinToString(" OR ")

/** The flat (uncollapsed) list SQL. Bind order: [scopeCount] (account id, mailbox id) pairs,
 *  account first — see [folderScopeSql]. Spanning accounts is a matter of HOW MANY pairs are bound,
 *  never of dropping the account. */
internal fun pagingSql(
    scopeCount: Int,
    sort: SortOrder,
    unreadOnly: Boolean,
    shape: ListShape = ListShape.NONE,
): String {
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "sortKey DESC"
        SortOrder.DATE_ASC -> "sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(fromName, fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "seen ASC, sortKey DESC"
        SortOrder.FLAGGED_FIRST -> "flagged DESC, sortKey DESC"
    }
    return "SELECT * FROM emails WHERE ${listRowsWhereSql(scopeCount, unreadOnly, shape)} ORDER BY $orderBy"
}

/** WHICH ROWS the flat list holds, sort excluded. ONE clause, shared by the two readers that must
 *  not disagree — [pagingSql] and [selectionIdsSql], what "Select all" takes (#126). */
internal fun listRowsWhereSql(scopeCount: Int, unreadOnly: Boolean, shape: ListShape = ListShape.NONE): String {
    val scope = folderScopeSql(scopeCount, "emails")
    val seenFilter = (if (unreadOnly) " AND seen = 0" else "") + shape.whereSql("emails")
    val notSnoozed = " AND ${notSnoozedSql("emails")}"
    return "($scope)$seenFilter$notSnoozed"
}

/** The rows of `MailboxDao.observeRoles` (#115), keyed by (account, folder), not by folder: servers
 *  number mailboxes per account (#121/#31). A role-less folder is left OUT rather than mapped to a
 *  blank — absent means "unknown, fall back" — and so is a MARKED role ([unmarkedRole]). */
internal fun folderRoleMap(rows: List<AccountMailboxRole>): Map<Pair<String, String>, String> =
    rows.mapNotNull { row -> unmarkedRole(row.role)?.let { (row.accountId to row.id) to it } }.toMap()

/** How many rows the flat list holds for [shape]: [listRowsWhereSql] itself, so a count and the list it
 *  labels cannot disagree. Bind order is [pagingQuery]'s. */
internal fun listCountSql(scopeCount: Int, shape: ListShape = ListShape.NONE): String =
    "SELECT COUNT(*) FROM emails WHERE ${listRowsWhereSql(scopeCount, unreadOnly = false, shape = shape)}"

/** The keys "Select all" may take: the same rows [pagingSql] draws, projected to (accountId, id).
 *  Bind order is [pagingQuery]'s. */
internal fun selectionIdsSql(scopeCount: Int, unreadOnly: Boolean, shape: ListShape = ListShape.NONE): String =
    "SELECT accountId, id FROM emails WHERE ${listRowsWhereSql(scopeCount, unreadOnly, shape)}"

internal fun selectionIdsQuery(
    scopes: List<Pair<String, String>>,
    unreadOnly: Boolean,
    shape: ListShape = ListShape.NONE,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    selectionIdsSql(scopes.size, unreadOnly, shape),
    scopes.flatMap { listOf(it.first, it.second) }.toTypedArray(),
)

/** The home-screen widget's read of the unified inbox, as one bounded statement. WHICH ROWS is
 *  [listRowsWhereSql] with the unread funnel off, so the PAIR scope (#121) and the snooze filter
 *  come free. `, id DESC` is a tie-break: rows commonly share a sortKey. */
internal fun recentUnifiedSql(scopeCount: Int): String =
    "SELECT $RECENT_EMAIL_ROW_COLUMNS FROM emails " +
        "WHERE ${listRowsWhereSql(scopeCount, unreadOnly = false)} " +
        "ORDER BY sortKey DESC, id DESC LIMIT ?"

/** The most rows a widget read will ever ask SQLite for. A count derived from the cell's height
 *  reaches 0 on a short cell and goes negative on a bad one, and `LIMIT -1` is not a limit at all. */
internal const val RECENT_ROWS_MAX: Int = 50

/** [RECENT_ROWS_MAX], and at least one row: see the reasoning there. */
internal fun recentRowBound(requested: Int): Int = requested.coerceIn(1, RECENT_ROWS_MAX)

/** [recentUnifiedSql], bound the way [pagingQuery] binds its scopes, plus the row bound last. */
internal fun recentUnifiedQuery(scopes: List<Pair<String, String>>, limit: Int): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        recentUnifiedSql(scopes.size),
        (scopes.flatMap { listOf<Any>(it.first, it.second) } + recentRowBound(limit)).toTypedArray(),
    )

/** What a widget read answers. [configured] is the difference between "no message" and "no answer":
 *  no scope means no account, an undecodable account blob, or an `inboxId` still null after a
 *  restore, so `configured = false` may not be drawn as "no account, add one". */
data class RecentInbox(val configured: Boolean, val rows: List<RecentEmailRow>)

internal inline fun recentUnifiedInboxFrom(
    scopes: List<Pair<String, String>>,
    read: (List<Pair<String, String>>) -> List<RecentEmailRow>,
): RecentInbox =
    if (scopes.isEmpty()) RecentInbox(configured = false, rows = emptyList())
    else RecentInbox(configured = true, rows = read(scopes))

/** The unified unread total BROKEN DOWN per account. A JMAP inbox is looked up by BOTH halves of
 *  its scope (#121); an IMAP inbox contributes [storedUnread], not the live aggregate. Built from
 *  [scopes], so an account whose inbox is unknown is ABSENT, not present at 0. */
internal fun unreadByAccountFrom(
    scopes: List<Pair<String, String>>,
    live: List<MailboxUnread>,
    isImap: (String) -> Boolean,
    storedUnread: (String) -> Int,
): Map<String, Int> {
    val (imapScopes, jmapScopes) = scopes.partition { (accountId, _) -> isImap(accountId) }
    return jmapScopes.associate { (accountId, inboxId) ->
        accountId to (live.firstOrNull { it.accountId == accountId && it.mailboxId == inboxId }?.count ?: 0)
    } + imapScopes.associate { (accountId, _) -> accountId to storedUnread(accountId) }
}

/** Build the conversation-collapsed paged query: one row per thread, its count covering the view's
 *  members plus the Sent-role replies in [sentMailboxes] so the chip always equals the expansion.
 *  The account-wide total only keeps the row expandable. */
internal fun conversationQuery(
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    unreadOnly: Boolean,
    // The account a folder view is pinned to. It no longer filters the rows; it selects which Sent
    // folders count.
    accountId: String?,
    // Each account's Sent folder as an (accountId, mailboxId) PAIR: bare Sent ids across accounts
    // would let a colliding mailbox id inflate that account's chip. NO DEFAULT, deliberately.
    sentMailboxes: List<Pair<String, String>>,
    shape: ListShape = ListShape.NONE,
): SimpleSQLiteQuery {
    // Bind order matches the clauses left-to-right, and EVERY clause binds the same shape —
    // (accountId, mailboxId) per scope, account first; the account-wide total binds nothing.
    val sent = ConversationScope.sentFolders(sentMailboxes, accountId)
    val perClause = scopes.flatMap { listOf(it.first, it.second) }
    val chipClause = perClause + sent.flatMap { listOf(it.first, it.second) }
    val args = perClause + chipClause + perClause
    return SimpleSQLiteQuery(
        conversationSql(scopes.size, sort, unreadOnly, sent.size, shape),
        args.toTypedArray(),
    )
}

/** The conversation-grouping SQL. Threads are grouped by the PAIR (accountId, thread key), never
 *  the key alone: a bare `GROUP BY tkey` collapses two accounts' conversations into one row. Bind
 *  order: `g` takes [scopeCount] pairs, account first; `c` the same pairs then one pair per
 *  [sentMailboxCount] Sent folder; the outer WHERE binds like `g`; `t` takes none. */
internal fun conversationSql(
    scopeCount: Int,
    sort: SortOrder,
    unreadOnly: Boolean,
    sentMailboxCount: Int = 0,
    shape: ListShape = ListShape.NONE,
): String {
    // The grouping key: the conversation (thread id, else the message's own id) — or, when grouping by
    // SENDER, the lower-cased address (else the message's own id, which keeps an address-less row alone) —
    // or, by DOMAIN, the stored registrable domain ("" groups the address-less rows as "(unknown)"); a row
    // not indexed yet stays alone under a key no domain can equal until the backfill reaches it.
    fun key(t: String) = when {
        shape.byDomain -> "COALESCE($t.fromDomain, char(1) || $t.id)"
        shape.bySender -> "COALESCE(NULLIF(LOWER(TRIM($t.fromEmail)), ''), $t.id)"
        else -> "COALESCE($t.threadId, $t.id)"
    }
    val shapeWhere = shape.whereSql("emails")
    val scope = folderScopeSql(scopeCount, "emails")
    val scopeOuter = folderScopeSql(scopeCount, "e")
    val sentAlternatives = " OR (accountId = ? AND mailboxId = ?)".repeat(sentMailboxCount)
    val notSnoozed = notSnoozedSql("emails")
    val notSnoozedOuter = notSnoozedSql("e")
    val having = if (unreadOnly) " HAVING MIN(seen) = 0" else ""
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "e.sortKey DESC"
        SortOrder.DATE_ASC -> "e.sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(e.subject)) ASC"
        // Ranked by sender, a domain group is ranked by its domain: the name its heading shows.
        SortOrder.SENDER -> if (shape.byDomain) "g.tkey ASC, e.sortKey DESC" else "LOWER(TRIM(COALESCE(e.fromName, e.fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "g.threadUnread ASC, e.sortKey DESC"
        // e.flagged — the REPRESENTATIVE row's star — and not MAX(flagged) over the thread: sorting
        // on "any message is starred" pins a row wearing an empty star tapping cannot dislodge (#111).
        SortOrder.FLAGGED_FIRST -> "e.flagged DESC, e.sortKey DESC"
    }
    return """
        SELECT e.*, c.threadCount AS threadCount, t.threadTotal AS threadTotal, g.threadUnread AS threadUnread,
            c.groupUnread AS groupUnread
        FROM emails e
        JOIN (
            SELECT accountId AS gacc, ${key("emails")} AS tkey, MAX(sortKey) AS maxKey, MIN(seen) AS threadUnread
            FROM emails
            WHERE ($scope) AND $notSnoozed$shapeWhere
            GROUP BY gacc, tkey$having
        ) g ON ${key("e")} = g.tkey AND e.accountId = g.gacc AND e.sortKey = g.maxKey
        JOIN (
            SELECT accountId AS cacc, ${key("emails")} AS ckey, COUNT(*) AS threadCount,
                SUM(CASE WHEN seen = 0 THEN 1 ELSE 0 END) AS groupUnread
            FROM emails
            WHERE (($scope)$sentAlternatives) AND $notSnoozed
            GROUP BY cacc, ckey
        ) c ON c.ckey = g.tkey AND c.cacc = g.gacc
        JOIN (
            SELECT accountId AS tacc, ${key("emails")} AS tkey2, COUNT(*) AS threadTotal
            FROM emails
            WHERE $notSnoozed
            GROUP BY tacc, tkey2
        ) t ON t.tkey2 = g.tkey AND t.tacc = g.gacc
        WHERE ($scopeOuter) AND $notSnoozedOuter
        GROUP BY g.gacc, g.tkey
        ORDER BY $orderBy
    """.trimIndent()
}

/** The predicate that hides messages snoozed into the future, for rows of [table]. Correlated on
 *  accountId as well as the email id: snoozes are keyed per account (#31). */
internal fun notSnoozedSql(table: String): String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = $table.id " +
        "AND snoozed.accountId = $table.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/** One row in the paged list. [threadCount] counts this view's members plus their Sent replies;
 *  [threadExpandable] marks a thread with 2+ cached messages account-wide, so the row unfolds when
 *  the others sit outside this view. */
data class InboxRow(
    val email: Email,
    val threadCount: Int,
    val unread: Boolean,
    val threadExpandable: Boolean = threadCount > 1,
    /** Unread messages among the [threadCount]: the heading of a domain group shows it. */
    val unreadCount: Int = if (unread) 1 else 0,
)

/** An account-qualified message key. Same-server accounts can cache COLLIDING email ids, so an
 *  action on id X would hit both. [accountId] is null only for an email never from the cache. */
data class EmailKey(val accountId: String?, val emailId: String)

/** One line of the per-sender screen. [email] is the grouping key (case-insensitively) and the
 *  value a filter rule is written on. [total]/[unread] count MESSAGES cached in one account. */
data class SenderVolume(
    val email: String,
    val name: String?,
    val total: Int,
    val unread: Int,
    val latest: Long,
)

fun Email.emailKey(): EmailKey = EmailKey(accountId, id)

data class MailboxMeta(
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

data class AccountInboxMeta(
    val accountId: String,
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/** Outcome of a unified refresh across every account (#65/#92): the inboxes that synced, plus the
 *  per-account failures, so "I am offline" can be told from "one account is unreachable". */
data class UnifiedRefreshResult(
    val metas: List<AccountInboxMeta>,
    val failures: List<Throwable>,
) {
    /** Only when at least one account failed and NONE synced: one good account proves the link is
     *  up. All failing is the VPN-killswitch case the #65 banner exists for. */
    val isConnectivityFailure: Boolean get() = metas.isEmpty() && failures.isNotEmpty()
}

data class FolderRefresh(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val emails: List<Email>,
    /** The ids this folder is to REMEMBER, or NULL when this refresh cannot say what the folder
     *  holds. An empty list cannot carry that refusal: `NewMailNotifier.seed` replaces, so an
     *  unknown taken as empty makes the next pass announce the whole day's mail. */
    val baselineIds: List<String>?,
    /** The ids the SERVER said LEFT this folder during the refresh; the notifier cancels exactly
     *  these banners (#134). ALWAYS EMPTY ON IMAP: that path replaces the cache with what it read,
     *  so inferring departure would cancel the banners of live mail. */
    val departedIds: List<String>,
    /** Where the opening line of each of these messages could be read from, keyed by cache id.
     *  ALWAYS EMPTY ON JMAP: a JMAP envelope brings `preview` with it. */
    val previewSources: Map<String, PreviewSource>,
)

/** What one notification pass read from the cache for one folder. [emails] are hydrated, so capped
 *  ([NOTIFY_CANDIDATE_MAX]); [baselineIds] are bounded by the time floor ALONE, because
 *  `NewMailNotifier.seed` replaces the baseline and a capped one forgets whatever the cap shed. */
data class NotifyRead(val emails: List<Email>, val baselineIds: List<String>)

/** Outcome of loading an account's server-side filter rules. */
sealed interface FilterRulesState {
    /** No Sieve support (IMAP account, or capability absent). */
    data object Unsupported : FilterRulesState
    data class Loaded(
        val rules: List<FilterRule>,
        /** True if the write must be refused: another script is the active one and saving takes it
         *  over, OR the `sterna` script could not be parsed and saving replaces unread content. */
        val foreignActiveScript: Boolean = false,
        /** The second of those two causes, kept apart so a screen can NAME it. It cannot be
         *  recovered from the script list, which carries names and flags, never content. */
        val scriptUnreadable: Boolean = false,
        /** The FIRST of those two causes, with the script's own text: what the account is being
         *  filtered by right now, and what Save would switch off. Null when nothing foreign is
         *  active. [rules] is what this app can model; this is everything it cannot — the two are
         *  separate fields so a save can carry the first and never silently drop the second. */
        val foreignScript: ForeignScript? = null,
    ) : FilterRulesState
}

/** Outcome of loading an account's server-side vacation responder. */
sealed interface VacationState {
    /** The account's server has no vacation-responder support (IMAP, or capability absent). */
    data object Unsupported : VacationState
    data class Loaded(val response: VacationResponse) : VacationState
}

/** A message body ready to render: the full [Email] plus its inline images (cid → data: URI). */
data class MessageBody(
    val email: Email,
    val inlineImages: Map<String, String>,
    /** OpenPGP state, or null for ordinary mail. */
    val crypto: MessageCrypto? = null,
)

/** OpenPGP state of an opened message. */
sealed interface MessageCrypto {
    /** Crypto content detected but not yet decrypted/verified. */
    data class Locked(val kind: CryptoKind) : MessageCrypto

    /** Decrypted and/or signature-verified; plaintext lives only in memory. */
    data class Decrypted(
        val signature: PgpSignatureState,
        val signatureUserId: String?,
        val signatureKeyId: Long,
        val wasEncrypted: Boolean,
    ) : MessageCrypto
}

/** A device-flow sign-in was refused, cancelled, or expired. [failure] carries the parsed error and
 *  (for Microsoft) the AADSTS code, so the UI can map it to a specific, actionable message. */
class OAuthDeniedException(val failure: DeviceTokenResult.Failed) :
    Exception(failure.description.ifBlank { failure.error })

/** The window every message list pages in. Top-level rather than a member of [MailRepository]: the
 *  refresh key in `RefreshAnchor.kt` is only correct BECAUSE `enablePlaceholders` is false here. */
internal fun pagingConfig() = PagingConfig(
    pageSize = PAGE_SIZE,
    initialLoadSize = PAGE_SIZE * 3,
    prefetchDistance = PAGE_SIZE,
    enablePlaceholders = false,
)

class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val imap: ImapMailService,
    private val scheduledSendDao: ScheduledSendDao,
    private val snoozedDao: SnoozedDao,
    private val recentContactDao: RecentContactDao,
    private val accountStore: AccountStore,
    private val outboxDao: OutboxDao,
    /** The frozen destroy list of a confirmed Empty trash (#99). */
    private val purgeSnapshotDao: PurgeSnapshotDao,
    /** Drafts written on the phone and not yet on the server (#95). */
    private val localDraftDao: LocalDraftDao,
    /** The folder numbering each mailbox was last seen under, frozen when a draft is saved over another (#99). */
    private val mailboxUidValidityDao: MailboxUidValidityDao,
    private val outboxFilesDir: java.io.File,
    /**
     * Where a local draft's attachment bytes are staged. Its own tree, not [outboxFilesDir]: an outbox item's
     * files die with the send, a draft's wait as long as the draft does.
     */
    private val localDraftFilesDir: java.io.File,
    private val oauthClient: OAuthClient = OAuthClient(),
    /** OpenPGP operations (null = no provider wired; all PGP features disabled). */
    private val pgpEngine: PgpEngine? = null,
    /** App settings (null in tests): consulted for behavior toggles like mark-read-on-delete. */
    private val settings: SettingsRepository? = null,
    /** Persisted sync cursors (null in tests): deltas survive process death (issue #17). */
    private val syncStateStore: SyncStateStore? = null,
    /** Where the offline-window sync keeps downloaded attachments (null in tests: none kept). */
    private val offlineAttachmentsDir: java.io.File? = null,
) {
    /**
     * Schedules the WorkManager job that delivers an outbox item. Set by the app layer at startup: the data
     * module cannot reference the worker.
     */
    var outboxScheduler: OutboxScheduler? = null

    /**
     * The same seam for a draft the server could not be given (#95): [saveDraft] books the deferred upload
     * itself. Set by the app layer at startup.
     */
    var localDraftScheduler: LocalDraftScheduler? = null

    /**
     * App-layer teardown for a linked sub-account pruned on reconcile (#31): clears its notification
     * baselines. Set by the app layer at startup.
     */
    var onAccountPruned: ((String) -> Unit)? = null
    private class Context(
        val credentials: AccountCredentials,
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
        // This account's own mailboxes: the global cache holds only the last-synced account.
        val mailboxes: List<Mailbox> = emptyList(),
    )

    @Volatile
    private var context: Context? = null

    /**
     * App-layer teardown for a folder the server has renumbered (#99). Without it the next push pass diffs
     */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = imap.onMailboxRenumbered
        set(value) { imap.onMailboxRenumbered = value }

    private val tokenRefresher = OAuthTokenRefresher(oauthClient, accountStore)

    /** Background scope for fire-and-forget work (body prefetch) that must outlive a sync call. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tolerant JSON for the on-disk body cache (schema may add fields across versions). */
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val inlineImagesSerializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Where an IMAP message was moved (for undo): emailId → (destination folder, new UID, and that folder's
     * numbering as COPYUID stated it). In memory only, like the Undo itself.
     */
    private val lastImapMove = java.util.concurrent.ConcurrentHashMap<String, ImapLoc>()

    /**
     * Per-mailbox JMAP state for incremental sync, written through to [syncStateStore] so cursors survive
     * process death (#17).
     */
    private val syncStates = SyncCursors(syncStateStore)

    private fun putSyncState(key: String, state: SyncState) = syncStates.put(key, state)

    private fun dropSyncState(key: String) = syncStates.drop(key)

    private fun loadSyncState(key: String): SyncState? = syncStates.load(key)

    /**
     * Advance the affected mailboxes' stored emailState to what a local mutation returned, so the next
     */
    private fun advanceEmailState(newState: String?, accountId: String, vararg mailboxIds: String?) {
        val s = newState ?: return
        mailboxIds.filterNotNull().distinct().forEach { mb ->
            val k = syncKey(accountId, mb)
            loadSyncState(k)?.let { putSyncState(k, it.copy(emailState = s)) }
        }
    }

    // Keyed by (account, mailbox), not mailbox alone: same-server accounts can share a mailbox id.
    private fun syncKey(accountId: String, mailboxId: String) = "$accountId$mailboxId"

    // Ids whose flag/seen we just changed locally, with the time: a delta run from the pre-change
    // queryState can report such a row `removed`. Keyed "$accountId:$emailId" — ids collide (#31).
    private val recentlyMutated = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun mutationKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /** Ids the app itself just moved between folders, for the notifier's diff filter (#50). For IMAP
     *  the marked id is the message's id AT ITS DESTINATION, since an IMAP move changes the id. */
    val recentLocalMoves = RecentLocalMoves()

    private fun markRecentlyMutated(accountId: String, emailId: String) {
        recentlyMutated[mutationKey(accountId, emailId)] = System.currentTimeMillis()
    }
    private fun isRecentlyMutated(accountId: String, emailId: String): Boolean =
        isRecentlyMutatedKey(mutationKey(accountId, emailId))
    private fun isRecentlyMutatedKey(key: String): Boolean {
        val at = recentlyMutated[key] ?: return false
        if (System.currentTimeMillis() - at > RECENT_MUTATION_MS) {
            recentlyMutated.remove(key)
            return false
        }
        return true
    }

    private fun recentlyMutatedIds(accountId: String): List<String> {
        val prefix = "$accountId:"
        return recentlyMutated.keys
            .filter { it.startsWith(prefix) && isRecentlyMutatedKey(it) }
            .map { it.removePrefix(prefix) }
    }

    /**
     * What one [syncMailbox] pass brought back: [fetchedIds], spared by the retention prune (#110), and
     * [departedIds], the only ground for cancelling a banner (#134).
     */
    internal data class MailboxSync(
        val fetchedIds: List<String>,
        val departedIds: List<String>,
    )

    /**
     * #1527: the JMAP Inbox lists EVERY message the server holds, not the newest window. For the
     * Inbox the server's whole id list is read (ids only, paged), the window sync below keeps every
     * cached row the server still holds (its full-query reconcile deletes only what LEFT the server),
     * and a bounded batch of the missing ids is fetched per refresh ([JmapBacklogPlan]: new mail
     * first, then older mail newest first). Other folders are unchanged (window + scroll mediator).
     */
    private suspend fun syncMailbox(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        sizing: FolderSyncSizing,
        localAccountId: String,
    ): MailboxSync {
        val isInbox = client.inboxGuardOf(accountId)?.inboxId == mailboxId
        if (!isInbox) return syncMailboxWindow(session, accountId, auth, mailboxId, sizing, localAccountId, inboxServerIds = emptySet())
        // Null = the server's list could not be read: the window sync then deletes nothing this pass.
        val serverIds: List<String>? = try {
            walkAllIds(pageSize = session.getBatchSize().coerceAtLeast(JMAP_BACKLOG_CHUNK)) { position, limit ->
                val page = client.queryEmailIds(session, accountId, mailboxId, limit, auth, position = position, calculateTotal = true)
                page.ids to page.total
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val window = syncMailboxWindow(session, accountId, auth, mailboxId, sizing, localAccountId, inboxServerIds = serverIds?.toHashSet())
        if (serverIds == null) return window
        val cached = emailDao.idsForMailbox(localAccountId, mailboxId).toHashSet()
        val plan = JmapBacklogPlan.next(serverIds, cached)
        if (plan.isEmpty()) return window
        val fetched = plan.chunked(JMAP_BACKLOG_CHUNK).flatMap { client.getEmailsByIds(session, accountId, it, auth) }
        if (fetched.isNotEmpty()) {
            checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
            emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
        }
        android.util.Log.i(
            "MailSync",
            "inbox backlog $localAccountId/$mailboxId: server ${serverIds.size}, cached ${cached.size}, +${fetched.size}",
        )
        return window.copy(fetchedIds = window.fetchedIds + fetched.map { it.id })
    }

    /** Both paths report what they fetched: an id fetched and not reported is deleted by the
     *  retention prune in the same cycle (#110). See [MailboxSync]. [inboxServerIds]: the ids the
     *  server's Inbox holds, kept by the full-query reconcile beside its window; empty for any other
     *  folder; null = the Inbox list could not be read, so the reconcile deletes nothing. */
    private suspend fun syncMailboxWindow(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        sizing: FolderSyncSizing,
        // Local StoredAccount id used to tag cached rows (distinct from the JMAP [accountId]).
        localAccountId: String,
        inboxServerIds: Set<String>?,
    ): MailboxSync {
        // Same belt as [refresh] (#121): a blank local account id strands every row this writes.
        require(localAccountId.isNotBlank()) {
            "syncMailbox() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        // And one notch further: an id that WAS an account and is not one any more. It only refuses
        // to START — every branch below asks again immediately before each of its writes.
        checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
        val key = syncKey(localAccountId, mailboxId)
        val stored = loadSyncState(key)
        if (stored != null) {
            val queryChanges = client.emailQueryChanges(session, accountId, mailboxId, stored.queryState, MAX_CHANGES, auth)
            val changes = client.emailChanges(session, accountId, stored.emailState, MAX_CHANGES, auth)
            val canApply = queryChanges.calculated && changes.calculated &&
                !changes.hasMoreChanges && queryChanges.newQueryState != null && changes.newState != null
            if (canApply) {
                // Asked AGAIN: two round-trips have passed since the entry check, and all below writes.
                checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                // A reorder shows up in both removed and added: only genuinely-gone ids are removed.
                val added = queryChanges.added.toSet()
                // Never evict an id we just flagged/read locally: a delta from the pre-mutation
                // query state can report it removed. Memoised so eviction and log cannot disagree.
                val protectionVerdict = HashMap<String, Boolean>()
                val isProtected: (String) -> Boolean = { id ->
                    protectionVerdict.getOrPut(id) { isRecentlyMutated(localAccountId, id) }
                }
                val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                // "Gone from THIS folder" is not "gone": `deleteByIds` names no mailbox, so evicting
                // a row that has already moved deletes the message from the folder it is NOW in.
                val inThisMailbox =
                    if (toRemove.isEmpty()) emptySet() else emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toDelete = mailboxScopedEvictions(toRemove, inThisMailbox)
                if (toDelete.isNotEmpty()) emailDao.deleteByIds(localAccountId, toDelete)
                // Every eviction the spare skipped; a `destroy` here is the one-shot loss [deltaEvictions] documents.
                val spared = sparedEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                if (spared.isNotEmpty()) {
                    android.util.Log.i(
                        "MailSync",
                        "spared $localAccountId/$mailboxId: ${spared.size} not evicted " +
                            "(locally mutated < ${RECENT_MUTATION_MS}ms ago): " +
                            spared.joinToString { (id, reason) -> "$id ${reason.log}" },
                    )
                }
                val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toFetch = deltaFetches(added, cachedIds, changes.updated)
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)
                    checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                    emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
                }
                putSyncState(key, SyncState(queryChanges.newQueryState!!, changes.newState!!))
                // Ghost sweep: a destroy can reach us through NEITHER delta (Stalwart omits a
                // delegated account's), or be eaten by the recently-mutated spare while the cursors
                // advance past it. [shouldSweepGhosts] gates it — these states are ACCOUNT-WIDE (#107).
                val stateAdvanced = queryChanges.newQueryState != stored.queryState ||
                    changes.newState != stored.emailState
                val vanishedFromMailbox = queryChanges.removed.any { it !in added }
                val claim = ghostSweeps.claim(localAccountId, mailboxId, stateAdvanced, vanishedFromMailbox)
                android.util.Log.i(
                    "MailSync",
                    "incremental $localAccountId/$mailboxId: +${toFetch.size} -${toDelete.size} " +
                        "moved=${toRemove.size - toDelete.size} spared=${spared.size} sweep=${claim.reason}",
                )
                val swept = if (claim.sweep) {
                    val ghosts = pruneGhostRows(session, accountId, auth, mailboxId, localAccountId)
                    // A failed sweep (null) pruned nothing: give its once-per-process credit back.
                    // It reports NO departure — an unanswered question is not "the message is gone".
                    if (ghosts == null) ghostSweeps.releaseFailed(localAccountId, mailboxId, claim)
                    ghosts.orEmpty()
                } else {
                    emptyList()
                }
                // The rows this delta wrote are mostly ids the cache NEVER held, so the prune must
                // spare them like a full query's page (#110). Beside them, `toRemove` — not
                // `queryChanges.removed` (a reorder) and not `toDelete` (folder-bounded) — #134.
                return MailboxSync(fetchedIds = toFetch, departedIds = toRemove + swept)
            }
        }
        // Cold cache, or the server can't compute changes — full query.
        // ONE call, MANY writes, ONE delete: pages are upserted as they land and the DELETE runs
        // once at the end. [fullQueryWriteThrough] enforces that nothing is deleted unless the walk
        // RAN TO COMPLETION. Sized on the ACCOUNT, not by its caller: this branch REPLACES the folder.
        val full = fullQuerySizing(
            accountStore.account(localAccountId)?.syncWindow?.limit,
            sizing.windowTarget,
            session.getBatchSize(),
        )
        val walked = fullQueryWriteThrough<List<Email>, WindowWalk>(
            folderIds = { emailDao.idsForMailbox(localAccountId, mailboxId).toSet() },
            walk = { onPage -> client.queryEmailsWindow(session, accountId, mailboxId, full.windowTarget, full.pageSize, auth, onPage) },
            writePage = { fresh ->
                checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                emailDao.upsertAll(fresh.map { it.toEntity(localAccountId, mailboxId) })
            },
            keepIds = { reconcilableWindowIds(it) },
            spareIds = { recentlyMutatedIds(localAccountId) },
            reconcile = { _, keepIds, spare, evictable -> if (inboxServerIds != null) emailDao.reconcileMailbox(localAccountId, mailboxId, keepIds + inboxServerIds, spare, evictableIds = evictable) },
        )
        android.util.Log.i(
            "MailSync",
            "full query $mailboxId: ${walked.ids.size} emails " +
                "(window ${full.windowTarget} in pages of ${full.pageSize}, " +
                "asked for ${sizing.windowTarget} in pages of ${sizing.pageSize}) " +
                "reconcile=${fullQueryReconcileReason(walked)}",
        )
        val queryState = walked.queryState
        val emailState = walked.emailState
        if (queryState != null && emailState != null) {
            putSyncState(key, SyncState(queryState, emailState))
        } else {
            dropSyncState(key)
        }
        // NO departures from a full query: it knows what the folder HOLDS, not what left it (#134).
        return MailboxSync(fetchedIds = walked.ids, departedIds = emptyList())
    }

    /**
     * Apply an account's sync window once a refresh has landed: drop what falls outside it, EXCEPT [freshIds]
     */
    private suspend fun pruneRetention(
        accountId: String,
        mailboxId: String,
        cutoffMillis: Long,
        freshIds: Set<String>?,
        keepNewest: Int,
    ) {
        val gone = retentionEvictions(
            cached = emailDao.retentionRows(accountId, mailboxId),
            cutoffMillis = cutoffMillis,
            freshIds = freshIds,
            spareIds = recentlyMutatedIds(accountId).toSet(),
            keepNewest = keepNewest,
        )
        if (gone.isEmpty()) return
        // The INDEX SURVIVES this, hence `evictFromCacheKeepingIndex`: every id here belongs to a
        // message the server still has. Chunked because the ids go into an `IN (...)`.
        gone.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
        android.util.Log.i(
            "MailSync",
            "retention $accountId/$mailboxId: pruned ${gone.size}, " +
                "spared ${freshIds?.size ?: -1} fetched + floor $keepNewest",
        )
    }

    /**
     * When each (account, mailbox) may be existence-swept again: once on the first sync of a process, then at
     * most once per [GHOST_SWEEP_MIN_INTERVAL_MS].
     */
    private val ghostSweeps = GhostSweepSchedule()

    /** Any failure prunes NOTHING and returns NULL, which must stay distinct from an empty list.
     *  What it did prune may be reported as departed (#134). */
    private suspend fun pruneGhostRows(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        localAccountId: String,
    ): List<String>? {
        val cached = emailDao.idsForMailbox(localAccountId, mailboxId)
        if (cached.isEmpty()) return emptyList()
        // null = the check never answered, which is what [ghostEvictions] prunes nothing for.
        val notFound: Set<String>? = runCatching {
            // Chunked for the server's maxObjectsInGet. A chunk that throws fails the WHOLE attempt.
            cached.chunked(MAX_CHANGES).flatMapTo(mutableSetOf()) { chunk ->
                client.missingEmailIds(session, accountId, chunk, auth)
            }
        }.getOrElseUnlessCancelled { null }
        val ghosts = ghostEvictions(cached, notFound)
        if (ghosts.isNotEmpty()) pruneServerGone(localAccountId, ghosts)
        if (notFound == null) {
            android.util.Log.i("MailSync", "ghost sweep $localAccountId/$mailboxId: failed over ${cached.size} cached")
            return null
        }
        android.util.Log.i(
            "MailSync",
            "ghost sweep $localAccountId/$mailboxId: -${ghosts.size} of ${cached.size} cached",
        )
        return ghosts
    }

    /** The only ground on which the notification layer may take a banner down (#134): a delta cannot
     *  tell a departure from a keyword change. A failed read confirms nothing and does not throw. */
    suspend fun confirmDepartures(
        credentials: AccountCredentials,
        mailboxId: String,
        emailIds: List<String>,
    ): List<String> {
        if (emailIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        val confirmed = confirmedDepartures(emailIds, mailboxId) { ids ->
            // Inside the supplier: a pass with nothing to confirm never even opens a session.
            val ctx = connect(credentials)
            client.mailboxIdsOf(ctx.session, ctx.accountId, ids, ctx.auth)
        }
        if (confirmed.size != emailIds.size) {
            android.util.Log.i(
                "MailSync",
                "banner departures ${credentials.id}/$mailboxId: ${confirmed.size} of " +
                    "${emailIds.size} confirmed gone by the server",
            )
        }
        return confirmed
    }

    /** [budgetMs] is the only bound and has no default: the block it reaches is blocking and has no
     *  suspension point, so a caller's `withTimeout` would cancel the coroutine while the thread
     *  stayed on the socket. */
    suspend fun notificationPreview(
        credentials: AccountCredentials,
        mailboxId: String,
        source: PreviewSource,
        budgetMs: Int,
    ): String? = imap.fetchPreview(credentials, mailboxId, source.uid, source.part, budgetMs)

    /**
     * The UIDVALIDITY this folder was last observed under, or null — for the notification pre-pass, which
     */
    suspend fun recordedNumbering(accountId: String, mailboxId: String): Long? =
        imap.recordedUidValidity(accountId, mailboxId)

    /** Only for an explicit per-id `notFound`. The index delete is guarded: an index that cannot be
     *  written (#71) must never abort the removal of the cached row. */
    private suspend fun pruneServerGone(localAccountId: String, emailIds: List<String>) {
        // Chunked: every id goes into an `IN (...)`, and SQLite refuses more than 999 bindings.
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val ids = emailDao.emailsByIds(localAccountId, chunk).map { it.id }
            if (ids.isEmpty()) return@forEach
            emailDao.deleteByIds(localAccountId, ids)
            runCatching { emailFtsDao.deleteByIds(localAccountId, ids) }
            ids.forEach { runCatching { emailBodyDao.deleteById(localAccountId, it) } }
        }
    }

    private fun observeUnreadByMailbox(accountId: String): Flow<Map<String, Int>> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            (if (conversation) threads else messages)
                .filter { it.accountId == accountId }
                .associate { it.mailboxId to it.count }
        }
    }

    /** The overload below, over the stored accounts. An account whose inbox is unknown is ABSENT
     *  from the map, never 0. */
    fun observeUnifiedInboxUnreadByAccount(): Flow<Map<String, Int>> = UnifiedInbox.unreadByAccount(
        accountStore.accountsFlow,
    ) { scopes -> observeUnifiedInboxUnreadByAccount(scopes) }

    /**
     * "All inboxes (N)" as ONE number — [observeUnifiedInboxUnreadByAccount] totalled, so nothing is observed
     * a second time. The addition is [UnifiedInbox.total], executed by a test.
     */
    fun observeUnifiedInboxUnread(): Flow<Int> =
        observeUnifiedInboxUnreadByAccount().map(UnifiedInbox::total)

    /**
     * #913 the fleet agent door (app.sterna.agentapi.AgentMailProvider): the cached rows a read-only
     * SELECT selects. [sql] is built by AgentMailContract from validated criteria, every value a bound
     * argument; nothing here writes.
     */
    suspend fun agentMessages(sql: String, args: Array<Any>): List<RecentEmailRow> =
        emailDao.recentUnified(SimpleSQLiteQuery(sql, args))

    /**
     * #913 one message for the agent door: the body cache first, else fetched WITHOUT marking it read.
     * Null when the account is gone or the message cannot be had; the caller says so in words.
     */
    suspend fun agentBody(accountId: String, emailId: String): MessageBody? {
        cachedMessage(accountId, emailId)?.let { return it }
        val credentials = accountStore.credentials(accountId) ?: return null
        return openMessage(credentials, emailId, markRead = false)
    }

    /** The read behind the "latest messages" widget, sharing the list's predicate [listRowsWhereSql]
     *  (the same (account, folder) PAIR scope, #121). The list's sort and filters do not apply. */
    suspend fun recentUnifiedInbox(limit: Int): RecentInbox =
        recentUnifiedInboxFrom(accountStore.allInboxScopes()) { scopes ->
            emailDao.recentUnified(recentUnifiedQuery(scopes, limit))
        }

    /** [recentUnifiedInbox] observed, so the widget follows the mail (#112). The emitted value is a
     *  TRIGGER, not a snapshot: `RecentMailWidgetDraw.read` also reads the setting and the app lock. */
    fun observeRecentUnifiedInbox(limit: Int): Flow<RecentInbox> = UnifiedInbox.recentRows(
        accountStore.accountsFlow,
    ) { scopes -> emailDao.observeRecentUnified(recentUnifiedQuery(scopes, limit)) }

    /**
     * Live unread across every account's inbox, ONE ENTRY PER ACCOUNT. JMAP inboxes contribute the same
     */
    fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>): Flow<Map<String, Int>> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            unreadByAccountFrom(
                scopes = scopes,
                live = if (conversation) threads else messages,
                isImap = ::isImapAccount,
                storedUnread = { accountId -> accountStore.account(accountId)?.unread ?: 0 },
            )
        }
    }

    /**
     * Cached mailboxes of [accountId], updated reactively. JMAP folders carry a live local
     */
    fun observeMailboxes(accountId: String): Flow<List<Mailbox>> =
        // EVERY protocol, not JMAP alone (#247). IMAP folder rows used to be handed straight through
        // with the `unreadEmails = 0` that `imapMailboxEntity` writes — the LIST reply carries no
        // counts — so every IMAP folder row badged a hard 0 and the drawer could say nothing about
        // unread mail at all. The aggregate below is the SAME two DAO flows this already collected
        // for JMAP, read from the local `emails` table: switching IMAP onto it adds ZERO network
        // per drawer open, which no per-folder `STATUS (UNSEEN)` round trip could have matched.
        // ponytail: cached-only, so an IMAP folder reads low until its mail is synced. The upgrade
        // is to fill `unreadEmails` from a batched `STATUS (UNSEEN)` during the folder sync — one
        // exchange for the whole list, refreshed on sync rather than on open — not to widen this.
        combine(mailboxDao.observeAll(accountId), observeUnreadByMailbox(accountId)) { rows, unread ->
            rows.map { row ->
                val mailbox = row.toMailbox()
                // ABSENT IS NOT ZERO. The aggregate above is a `GROUP BY accountId, mailboxId` over
                // the cached `emails` table, so a folder holding no cached rows — any folder the
                // user has not opened yet — produces NO GROUP AT ALL rather than a group of 0.
                // Read through `?: 0` that absence overwrote the row's stored counter, and on JMAP
                // that counter is real: `Mailbox/get` names no `properties`, so the server returns
                // `unreadEmails` for every mailbox and `toMailbox` has already put it here. The
                // drawer therefore badged the one folder whose mail was in the cache and left the
                // rest blank — which, to the person holding the phone, is indistinguishable from
                // having no unread mail anywhere.
                //
                // The fallback is per FOLDER, not per account, because that is the grain the
                // absence has: a synced folder keeps the live count (mode-appropriate, snooze-
                // aware), an unsynced one keeps the server's. On IMAP the stored counter is 0
                // anyway (`imapMailboxEntity` writes no count), so this changes nothing there.
                unread[row.id]?.let { mailbox.copy(unreadForList = it) } ?: mailbox
            }
        }

    /**
     * Paged list of cached emails for [scopes] — the unified inbox's (account id, inbox id) pairs. PAIRS, not
     */
    fun pagedMailbox(
        scopes: List<Pair<String, String>>,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // Each account's Sent-role folder as an (accountId, mailboxId) pair — the conversation chip
        // counts the thread's Sent replies. NO DEFAULT: an omission is a chip that counts too few.
        sentMailboxes: List<Pair<String, String>>,
        shape: ListShape = ListShape.NONE,
    ): Flow<PagingData<InboxRow>> {
        if (scopes.isEmpty()) return flowOf(PagingData.empty())
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, accountId = null, sentMailboxes = sentMailboxes, shape = shape))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly, shape))) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /** The live All / Alerts / no-Alerts counts of [scopes], each from the list's own WHERE ([listCountSql]). */
    fun observeAlertCounts(scopes: List<Pair<String, String>>, rules: InboxSplitRules): Flow<AlertCounts> {
        val args = scopes.flatMap { listOf(it.first, it.second) }.toTypedArray()
        fun count(scope: AlertScope) = emailDao.observeCount(
            SimpleSQLiteQuery(listCountSql(scopes.size, ListShape(alerts = scope, alertRules = rules)), args),
        )
        return combine(count(AlertScope.ALL), count(AlertScope.ALERTS), count(AlertScope.NO_ALERTS)) { all, alerts, noAlerts ->
            AlertCounts(all, alerts, noAlerts)
        }.distinctUntilChanged()
    }

    /**
     * Paged view of a single folder, backed by a [RemoteMediator]: scrolling past the cached rows fetches the
     * next older page, so a large folder doesn't stop at the sync window.
     */
    @OptIn(ExperimentalPagingApi::class)
    fun pagedFolder(
        credentials: AccountCredentials,
        mailboxId: String,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // The account's Sent-role folder as an (accountId, mailboxId) pair — see [pagedMailbox]. NO DEFAULT.
        sentMailboxes: List<Pair<String, String>>,
        shape: ListShape = ListShape.NONE,
    ): Flow<PagingData<InboxRow>> {
        val scopes = listOf(credentials.id to mailboxId)
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = true),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, credentials.id, sentMailboxes, shape))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = false),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly, shape))) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    @OptIn(ExperimentalPagingApi::class)
    private fun <V : Any> folderMediator(
        credentials: AccountCredentials,
        mailboxId: String,
        conversationView: Boolean,
    ): RemoteMediator<Int, V> {
        return object : RemoteMediator<Int, V>() {
            // The cache is populated by refresh()/sync; only extend it on scroll.
            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, V>,
            ): MediatorResult {
                if (loadType != LoadType.APPEND) {
                    return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
                }
                return try {
                    val (added, total) = if (credentials.protocol == MailProtocol.IMAP) {
                        val offset = emailDao.countForMailbox(credentials.id, mailboxId)
                        val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE, listPreviewCache(credentials.id))
                        if (entities.isNotEmpty()) emailDao.upsertAll(entities)
                        entities.size to exists
                    } else {
                        val ctx = connect(credentials)
                        // Anchor on the oldest cached representative rather than an absolute offset:
                        // the anchor doesn't shift when new mail arrives, so no page is skipped or
                        // duplicated. It overlaps at worst, which upsert-only insertion makes harmless.
                        val anchorId = emailDao.oldestRepresentativeEmailId(credentials.id, mailboxId)
                        val before = emailDao.countForMailbox(credentials.id, mailboxId)
                        val repsBefore = if (conversationView) {
                            emailDao.representativeCountForMailbox(credentials.id, mailboxId)
                        } else {
                            0
                        }
                        var page = try {
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = anchorId,
                                anchorOffset = if (anchorId != null) 1 else 0,
                            )
                        } catch (e: JmapException) {
                            // The anchor can have dropped out of the folder; fall back once to an
                            // absolute position at the cached row count.
                            if (e.errorType != "anchorNotFound") throw e
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                position = before, calculateTotal = true,
                            )
                        }
                        if (page.emails.isNotEmpty()) {
                            emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                        }
                        // The collapsed list's rows are threads: keep fetching at the window edge
                        // until enough NEW rows land, bounded.
                        val maxFetches = if (conversationView) MAX_APPEND_FILL_PAGES else 2
                        val fillTarget = if (conversationView) APPEND_THREAD_TARGET else 1
                        var fetches = 1
                        while (page.emails.isNotEmpty() && fetches < maxFetches) {
                            val cachedNow = emailDao.countForMailbox(credentials.id, mailboxId)
                            val gained = if (conversationView) {
                                emailDao.representativeCountForMailbox(credentials.id, mailboxId) - repsBefore
                            } else {
                                cachedNow - before
                            }
                            if (gained >= fillTarget) break
                            val serverTotal = page.total
                            if (serverTotal != null && cachedNow >= serverTotal) break
                            // Chain each follow-up on the page just fetched, never on the cached row
                            // count: the cache is not a contiguous prefix, so a gap would never heal.
                            page = client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = page.emails.last().id,
                                anchorOffset = 1,
                            )
                            if (page.emails.isNotEmpty()) {
                                emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                            }
                            fetches++
                        }
                        page.emails.size to page.total
                    }
                    val cached = emailDao.countForMailbox(credentials.id, mailboxId)
                    val reachedEnd = added == 0 || (total != null && cached >= total)
                    MediatorResult.Success(endOfPaginationReached = reachedEnd)
                } catch (t: Throwable) {
                    MediatorResult.Error(t)
                }
            }
        }
    }


    /** All cached (account, id) keys for the given (account, mailbox) scopes — the whole folder,
     *  filters included, so it drives cache eviction and NOT "select all" (see [selectableIds]). */
    suspend fun cachedIds(scopes: List<Pair<String, String>>): List<EmailKey> =
        scopes.flatMap { (accountId, mailboxId) ->
            emailDao.idsForMailbox(accountId, mailboxId).map { EmailKey(accountId, it) }
        }

    /**
     * The keys "Select all" may take outside a search: the rows the flat list is PAGING, through
     */
    suspend fun selectableIds(scopes: List<Pair<String, String>>, unreadOnly: Boolean, shape: ListShape = ListShape.NONE): List<EmailKey> =
        emailDao.keysForSelection(selectionIdsQuery(scopes, unreadOnly, shape))
            .map { EmailKey(it.accountId, it.id) }

    /**
     * The keys of the unread messages this phone HOLDS in the given scopes — what "mark all read" patches on
     */
    suspend fun cachedUnreadKeys(scopes: List<Pair<String, String>>): List<EmailKey> {
        return scopes.flatMap { (accountId, mailboxId) ->
            emailDao.unreadKeys(accountId, mailboxId, UNREAD_RESOLVE_MAX)
        }.map { EmailKey(it.accountId, it.id) }
    }

    /**
     * What one new-mail notification pass over [mailboxId] reads: the rows it may announce, and the ids it
     */
    suspend fun notifyRead(accountId: String, mailboxId: String): NotifyRead {
        val since = notifyCandidateFloor(System.currentTimeMillis())
        return NotifyRead(
            emails = emailDao.receivedSince(accountId, mailboxId, since, NOTIFY_CANDIDATE_MAX).map { it.toEmail() },
            baselineIds = emailDao.idsReceivedSince(accountId, mailboxId, since),
        )
    }

    /** Cached emails for account-qualified keys (drives bulk actions on a selection). Each id is
     *  resolved ONLY inside its own account; a null accountId falls back to an unscoped lookup. */
    suspend fun cachedEmailsByIds(keys: Collection<EmailKey>): List<Email> {
        if (keys.isEmpty()) return emptyList()
        return keys.groupBy({ it.accountId }, { it.emailId }).flatMap { (accountId, ids) ->
            // Chunked ([byIdsChunked]): a select-all enters the bulk paths through this read.
            byIdsChunked(ids) { chunk ->
                if (accountId != null) emailDao.emailsByIds(accountId, chunk) else emailDao.emailsByIds(chunk)
            }
        }.map { it.toEmail() }
    }

    /**
     * What this phone holds from each sender, for one account. A one-shot read on [Dispatchers.IO]: it is a
     * full scan of `emails`, and a reactive version would replay it on every sync write.
     */
    suspend fun senderVolumes(accountId: String): List<SenderVolume> = withContext(Dispatchers.IO) {
        emailDao.senderVolumes(accountId).map {
            SenderVolume(email = it.email, name = it.name, total = it.total, unread = it.unread, latest = it.latest)
        }
    }

    /**
     * The ids [senderVolumes] counted for [email] in [accountId] — the set a per-sender delete may act on,
     * produced by the same scope clause as the count itself.
     */
    suspend fun senderMessageIds(accountId: String, email: String): List<String> =
        withContext(Dispatchers.IO) { emailDao.senderMessageIds(accountId, email) }

    /**
     * Every unread message id in [mailboxId], resolved SERVER-side (uncollapsed Email/query on `notKeyword
     */
    suspend fun unreadIds(credentials: AccountCredentials, mailboxId: String): List<String> {
        // Bounded by the SAME [UNREAD_RESOLVE_MAX] as the server walk below: one question, one size.
        suspend fun cached() =
            emailDao.unreadKeys(credentials.id, mailboxId, UNREAD_RESOLVE_MAX).map { it.id }
        if (credentials.protocol == MailProtocol.IMAP) return cached()
        return runCatching {
            val ctx = connect(credentials)
            val ids = mutableListOf<String>()
            while (ids.size < UNREAD_RESOLVE_MAX) {
                val page = client.queryEmailIds(
                    ctx.session, ctx.accountId, mailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                    position = ids.size, calculateTotal = true, unseenOnly = true,
                )
                if (page.ids.isEmpty()) break
                ids += page.ids
                val total = page.total
                if (total != null && ids.size >= total) break
            }
            ids
        }.getOrElse { cached() }
    }

    /**
     * Instant coverage floor for the search index: re-seed the rows in the display cache, without clearing
     * crawled-only rows. The whole-mailbox crawl ([syncSearchIndex]) runs separately.
     */
    suspend fun seedIndexFromCache() = emailFtsDao.seedFromEmails(NOT_SEARCHED_ROLES)

    private val lastIndexAt = mutableMapOf<String, Long>()

    /**
     * Crawl the whole mailbox's HEADERS into the local search index so as-you-type search covers all mail
     */
    suspend fun syncSearchIndex(
        credentials: AccountCredentials,
        force: Boolean = false,
        onPage: (suspend () -> Unit)? = null,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastIndexAt[credentials.id] ?: 0L) < INDEX_TTL_MS) return
        val ctx = runCatching { connect(credentials) }.getOrNull() ?: return
        // Don't crawl Trash/Junk into the index: same role source as searchableFolderIds, excluded
        // server-side.
        val excluded = excludedSearchFolderIds(mailboxDao.searchOrder(credentials.id))
        // Bound ONCE for "what to ask for", "how far to skip a failed page" and "was that page
        // short?": a hardcoded 500 in all three refused every page on a server admitting less.
        val pageSize = requestPageSize(HEADER_PAGE, ctx.session.getBatchSize())
        var position = 0
        var failed = false
        var consecutiveErrors = 0
        while (position < HEADER_MAX) {
            val page = try {
                client.crawlHeaders(ctx.session, ctx.accountId, position, pageSize, ctx.auth, excluded)
            } catch (e: CancellationException) {
                throw e // search closed / VM cleared: bail WITHOUT stamping so we resume next time
            } catch (e: Exception) {
                // A page error must NOT hide every older mail behind it: skip it and keep crawling.
                failed = true
                if (++consecutiveErrors >= MAX_CRAWL_ERRORS) break
                position += pageSize
                continue
            }
            consecutiveErrors = 0
            // Paginate on Email/query's id count, NOT Email/get's: a short get must not stop the walk.
            if (page.queryCount == 0) break
            if (page.emails.isNotEmpty()) {
                // Guarded like the two folder walks: an account signed out mid-crawl would keep
                // handing its subjects and senders to local search under an unowned id (#121).
                checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
                emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })
                onPage?.invoke()
            }
            if (page.queryCount < pageSize) break
            position += page.queryCount
        }
        // Only throttle once the crawl finished cleanly; a partial run must stay retryable.
        if (!failed) lastIndexAt[credentials.id] = now
    }

    /** Local full-text search over [syncSearchIndex]'s index, capped at [limit]. [NOT_SEARCHED_ROLES]
     *  only sees rows LABELLED with an excluded folder; `EmailDao` un-indexes the rest. */
    suspend fun searchIndex(query: String, limit: Int = LOCAL_SEARCH_LIMIT): List<Email> {
        val match = ftsMatch(query) ?: return emptyList()
        val accountIds = accountStore.accounts().map { it.id }
        return emailFtsDao.search(match, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)
            .map { it.toEmail() }
    }

    private fun ftsMatch(query: String): String? {
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Refresh every account's inbox into the cache. Per-account failures are skipped so one bad account
     * doesn't sink the unified view. Returns metadata for the accounts that synced.
     */
    suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): UnifiedRefreshResult {
        val results = mutableListOf<AccountInboxMeta>()
        val failures = mutableListOf<Throwable>()
        for (credentials in accounts) {
            try {
                if (credentials.protocol == MailProtocol.IMAP) {
                    // Same write-through as the single-account refresh, and sized on the account's
                    // window, not the 50 passed here.
                    val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)
                    mailboxDao.replaceAll(credentials.id, load.mailboxes)
                    results += AccountInboxMeta(
                        credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,
                    )
                    continue
                }
                val resolved = resolve(credentials)
                val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
                    ?: resolved.mailboxes.firstOrNull()
                    ?: continue
                syncMailbox(
                    resolved.session, resolved.accountId, resolved.auth, inbox.id,
                    folderSyncSizing(limit, resolved.session.getBatchSize()), credentials.id,
                )
                // Folder counters AFTER the row sync so badge and list move together.
                mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
                val name = resolved.session.accounts[resolved.accountId]?.name ?: credentials.username
                results += AccountInboxMeta(credentials.id, name, inbox.id, inbox.name, inbox.unreadEmails)
                bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }
            } catch (gone: AccountGoneException) {
                // ABOVE the cancellation catch, and it must stay there. This account was signed out
                // mid-pass: not a failure (nothing goes into [failures], so no offline banner for a
                // deliberate gesture) and not a reason to abandon the accounts after it. Letting the
                // cancellation out would leave `InboxViewModel.refresh` spinning for ever.
                android.util.Log.i(
                    "MailRepository",
                    "unified refresh: ${accountGoneCause(credentials.id, accountStore.accountsUnreadable(), passKind = "walk")}, skipped",
                    gone,
                )
            } catch (c: CancellationException) {
                // A superseding refresh cancelled us: propagate, so the caller records no false success (#65).
                throw c
            } catch (t: Throwable) {
                // One account failing must not sink the unified view, nor vanish without trace (#92).
                failures += t
                android.util.Log.w("MailRepository", "unified refresh: account ${credentials.id} failed", t)
            }
        }
        return UnifiedRefreshResult(results, failures)
    }

    sealed interface DiscoveryResult {
        /** A server host responded and authenticated; store this as the account `server`. */
        data class Found(val server: String) : DiscoveryResult
        /** A server was reached but rejected the credentials (HTTP 401/403). */
        data object BadCredentials : DiscoveryResult
        /** No candidate host responded as a JMAP server. */
        data object NotFound : DiscoveryResult
    }

    /** JMAP autodiscovery (RFC 8620 §2.2). A 401/403 from a reachable candidate means the server was
     *  found but the password is wrong. Candidates are probed AT ONCE; rank decides, not latency. */
    suspend fun discoverJmapServer(email: String, password: String, token: String? = null): DiscoveryResult {
        val hosts = Jmap.autodiscoverHosts(email)
        if (hosts.isEmpty()) return DiscoveryResult.NotFound
        // A non-null [token] is a bearer credential (an API token, or a just-granted access token).
        val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)
        // No budget passed on purpose: the shipped value lives once, in JMAP_DISCOVERY_BUDGET_MS.
        return discoverJmapAmong(hosts) { host ->
            probeHost { client.fetchSession(Jmap.sessionUrlFor(host), auth) }
        }
    }

    /**
     * Build JMAP auth for [credentials]: Bearer for API-token accounts (the token lives in the password slot)
     * and for OAuth (refreshing within 60 s of expiry), Basic otherwise.
     */
    private suspend fun jmapAuth(credentials: AccountCredentials, force: Boolean = false): JmapAuth {
        if (credentials.authType == AuthType.API_TOKEN) return BearerAuth(credentials.password)
        val token = tokenRefresher.freshAccessToken(credentials, force)
            ?: return BasicAuth(credentials.username, credentials.password)
        return BearerAuth(token)
    }

    /**
     * Resolve a manually-entered JMAP server to the value to persist as the account's `server`: the first
     */
    suspend fun resolveJmapServerInput(serverInput: String, auth: JmapAuth): String {
        val candidates = Jmap.sessionUrlCandidates(serverInput)
        var firstError: Throwable? = null
        for (url in candidates) {
            val session = try {
                client.fetchSession(url, auth)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                continue
            }
            if (session.mailAccountId() != null) {
                return if (candidates.size == 1) serverInput.trim() else url
            }
            if (firstError == null) firstError = JmapException("This user has no JMAP mail account.")
        }
        throw firstError ?: JmapException("No JMAP server found at $serverInput")
    }

    /**
     * The address the server itself associates with [auth] at [server]: the session's `username` (RFC 8620
     */
    suspend fun sessionIdentity(server: String, auth: JmapAuth): String? {
        val session = client.fetchSession(Jmap.sessionUrlFor(server), auth)
        val accountName = session.mailAccountId()?.let { session.accounts[it]?.name }
        val looksLikeEmail = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
        return sequenceOf(session.username, accountName.orEmpty())
            .map { it.trim() }
            .firstOrNull { looksLikeEmail.matches(it) }
    }

    /**
     * Validate an account's credentials without persisting anything or disturbing the active session: JMAP
     * fetches the session and checks for a mail account, IMAP connects.
     */
    suspend fun testConnection(credentials: AccountCredentials): Result<Unit> = runCatching {
        if (credentials.protocol == MailProtocol.IMAP) {
            imap.testConnection(credentials)
        } else {
            val session = client.fetchSession(
                Jmap.sessionUrlFor(credentials.server),
                jmapAuth(credentials),
            )
            requireNotNull(session.mailAccountId()) { "This user has no JMAP mail account." }
            Unit
        }
    }

    /**
     * OAuth metadata for a host, or null if it advertises none. One that advertises OAuth without a
     */
    suspend fun discoverOAuth(host: String, addressDomain: String): OAuthMetadata? =
        oauthClient.discoverMetadata(host, addressDomain)

    /** #1693 Cloud Account runs the OAuth device grant: set by the app (a provider call). Null = it cannot, and every start says so. */
    var oauthDeviceBroker: OAuthDeviceBroker? = null

    private fun broker(): OAuthDeviceBroker = oauthDeviceBroker
        ?: throw app.sterna.core.jmap.JmapException("Cloud Account runs the OAuth sign-in", oauthError = "cloud_account_unavailable")

    /** The address the request carries when the caller has none to give: the endpoints' own host (they were vetted by the discovery already). */
    private fun requestEmail(email: String, tokenEndpoint: String): String =
        email.takeIf { it.contains('@') } ?: ("device@" + tokenEndpoint.removePrefix("https://").substringBefore('/').substringBefore(':'))

    private fun hostOf(endpoint: String) = endpoint.removePrefix("https://").substringBefore('/').substringBefore(':')

    /** Begin the device flow against [metadata] using Sterna's client id + scopes, run by Cloud Account (#1693). */
    suspend fun startDeviceAuthorization(metadata: OAuthMetadata, email: String = ""): DeviceAuthorization =
        broker().start(
            requestEmail(email, metadata.tokenEndpoint), hostOf(metadata.issuer.ifBlank { metadata.tokenEndpoint }),
            metadata.deviceAuthorizationEndpoint ?: throw app.sterna.core.jmap.JmapException("Server has no device-authorization endpoint"),
            metadata.tokenEndpoint, Jmap.OAUTH_CLIENT_ID, Jmap.OAUTH_SCOPE,
        )

    /** Poll once for a pending device authorization ([deviceCode] is the handle Cloud Account issued). */
    suspend fun pollDeviceToken(metadata: OAuthMetadata, deviceCode: String): DeviceTokenResult = broker().poll(deviceCode)

    /** #1703 Cloud Account runs the OAuth authorization-code grant (PKCE, browser, state): set by the app (a provider call). Null = it cannot, and every start says so. */
    var oauthCodeBroker: OAuthCodeBroker? = null

    private fun codeBroker(): OAuthCodeBroker = oauthCodeBroker
        ?: throw JmapException("Cloud Account runs the OAuth sign-in", oauthError = "cloud_account_unavailable")

    /** Begin the authorization-code grant against [metadata] with Sterna's client id + scopes: the handle Cloud Account issued. Throws (never a crash) when Cloud Account cannot. */
    suspend fun startAuthorizationCode(metadata: OAuthMetadata, host: String, email: String, redirectUri: String): String =
        codeBroker().start(
            requestEmail(email, metadata.tokenEndpoint), host,
            metadata.authorizationEndpoint ?: throw JmapException("Server has no authorization endpoint"),
            metadata.tokenEndpoint, Jmap.OAUTH_CLIENT_ID, Jmap.OAUTH_SCOPE, redirectUri,
        )

    /** The redirect's code + state (or error) for [handle]: Cloud Account checks the state and redeems the code with the verifier it holds (RFC 7636). */
    suspend fun finishAuthorizationCode(handle: String, code: String?, state: String?, error: String?): DeviceTokenResult =
        codeBroker().finish(handle, code, state, error)

    /** Throws, persisting nothing, if [tokens] don't authenticate against [host]. Returns true when
     *  THIS add created the account — a re-add must land on the existing one and succeed. */
    suspend fun addOAuthAccount(
        host: String,
        email: String,
        metadata: OAuthMetadata,
        tokens: OAuthTokens,
        accountName: String,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()
            ?: error("This user has no JMAP mail account.")
        // [host] proved the tokens; it does not decide the identity WHEN THE APP guessed it. The
        // candidate list drops the apex, so a guessed host is `mail.<domain>` where the password
        // path stores `<domain>` — one mailbox added both ways became two accounts (#55). Only the
        // guessed ones: a typed host is stored verbatim by its own button. See oauthHostWasGuessed.
        val guessedHost = oauthHostWasGuessed(host, Jmap.autodiscoverHosts(email))
        val server = if (guessedHost) {
            oauthServerToStore(
                discoverJmapServer(email, password = "", token = tokens.accessToken),
                host,
            )
        } else {
            host
        }
        // Said out loud: the account is written under the host we wanted to improve, so #55 stands.
        if (guessedHost && server == host) {
            android.util.Log.w("MailRepository", "OAuth add: nothing autodiscovered for $host; stored as-is (#55)")
        }
        // Asked BEFORE the store writes: addOAuth refreshes the login already stored under this
        // identity. On BOTH keys this route has written, in the ORDER the store resolves them.
        val created = resolveExistingLoginAmong(
            accountStore.accounts(),
            listOf(
                accountKeyOf(MailProtocol.JMAP, server, "", email),
                accountKeyOf(MailProtocol.JMAP, host, "", email),
            ),
        ) == null
        val id = accountStore.addOAuth(
            server = server,
            username = email,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = metadata.tokenEndpoint,
            clientId = Jmap.OAUTH_CLIENT_ID,
            documentHost = host,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
        // ONLY on a real creation. A re-add lands on the login already there — with its shared
        // sub-accounts — and eviction reads the RAW discovered list: a session listing the login's
        // own account but not the shares would prune every sub-account and purge five tables (#129).
        if (created) reconcileLinkedAccountsAfterAdd(id)
        return created
    }

    /** Begin the device flow for a built-in OAuth provider (Microsoft, …). */
    suspend fun startProviderDeviceAuth(provider: OAuthProvider, email: String = ""): DeviceAuthorization =
        broker().start(
            requestEmail(email, provider.metadata.tokenEndpoint), hostOf(provider.metadata.issuer.ifBlank { provider.metadata.tokenEndpoint }),
            provider.metadata.deviceAuthorizationEndpoint ?: throw app.sterna.core.jmap.JmapException("Provider has no device-authorization endpoint"),
            provider.metadata.tokenEndpoint, provider.clientId, provider.scope,
        )

    /** Poll a built-in provider's grant once for a pending device authorization (run by Cloud Account, #1693). */
    suspend fun pollProviderToken(provider: OAuthProvider, deviceCode: String): DeviceTokenResult = broker().poll(deviceCode)

    /**
     * Run a provider device flow to completion: start it, hand the user code to [onCode], then poll until the
     */
    suspend fun runProviderDeviceFlow(
        provider: OAuthProvider,
        onCode: (DeviceAuthorization) -> Unit,
    ): Result<OAuthTokens> = runCatching {
        val device = startProviderDeviceAuth(provider)
        onCode(device)
        var interval = device.interval.coerceAtLeast(1).toLong()
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            when (val r = pollProviderToken(provider, device.deviceCode)) {
                is DeviceTokenResult.Success -> return@runCatching r.tokens
                DeviceTokenResult.Pending -> Unit
                DeviceTokenResult.SlowDown -> interval += 5
                is DeviceTokenResult.Failed -> throw OAuthDeniedException(r)
            }
        }
        throw OAuthDeniedException(DeviceTokenResult.Failed("expired_token"))
    }

    /**
     * Validate freshly granted OAuth [tokens] against [provider]'s IMAP server with XOAUTH2, then persist an
     */
    suspend fun addOAuthImapAccount(
        provider: OAuthProvider,
        email: String,
        tokens: OAuthTokens,
        accountName: String,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        // Microsoft IMAP wants the account's exact primary address as the XOAUTH2 user=,
        // not whatever alias the user typed — take it from the signed-in identity (id_token).
        val username = emailFromIdToken(tokens.idToken) ?: email
        val probe = AccountCredentials(
            server = "",
            username = username,
            password = "",
            protocol = MailProtocol.IMAP,
            imap = provider.imap,
            smtp = provider.smtp,
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try {
            imap.testConnection(probe)
        } finally {
            runCatching { imap.disconnect("") }
        }
        // On the values the store is actually given: the IMAP host is the endpoint an IMAP account
        // is keyed on, and `username` is the address the id_token named, not the typed one.
        val created = resolveExistingLogin(
            accountStore.accounts(),
            accountKeyOf(MailProtocol.IMAP, "", provider.imap.host, username),
        ) == null
        val id = accountStore.addOAuth(
            server = "",
            username = username,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
            protocol = MailProtocol.IMAP,
            imapHost = provider.imap.host,
            imapPort = provider.imap.port,
            imapSecurity = provider.imap.security,
            smtpHost = provider.smtp.host,
            smtpPort = provider.smtp.port,
            smtpSecurity = provider.smtp.security,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
        return created
    }

    /** Complete OAuth sign-in for an already-imported (inert) account [accountId] using freshly
     *  granted [tokens]. Validates via IMAP XOAUTH2 first; throws (account left inert) on failure. */
    suspend fun signInImportedOAuth(accountId: String, provider: OAuthProvider, tokens: OAuthTokens) {
        val account = accountStore.account(accountId) ?: error("Account not found.")
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        val username = emailFromIdToken(tokens.idToken) ?: account.username
        val probe = AccountCredentials(
            server = "", username = username, password = "",
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            smtp = MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try { imap.testConnection(probe) } finally { runCatching { imap.disconnect("") } }
        accountStore.attachOAuth(
            id = accountId, username = username,
            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt, tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
        )
        val credentials = accountStore.credentials(accountId) ?: error("Account could not be loaded.")
        val meta = refresh(credentials)
        accountStore.saveInboxMetaFor(accountId, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    private fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val obj = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            (obj["preferred_username"] ?: obj["email"])?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun refresh(
        credentials: AccountCredentials,
        mailboxId: String? = null,
        limit: Int = 50,
        // Prune cached messages older than this epoch-millis cutoff; null prunes nothing. When set,
        // [limit] is also the window's retention FLOOR, so the two must come from one SyncWindow.
        pruneBeforeMillis: Long? = null,
    ): MailboxMeta {
        // The belt behind ConnectViewModel's braces (#121): a blank credentials.id files every row
        // under an account that does not exist. Throw rather than skip, so a fourth path is caught.
        require(credentials.id.isNotBlank()) {
            "refresh() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)

        val mailboxes = client.getMailboxes(session, accountId, auth)
        context = Context(
            credentials = credentials,
            session = session,
            accountId = accountId,
            auth = auth,
            rolesToMailboxId = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap(),
            mailboxes = mailboxes,
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        // The window's two halves, computed ONCE: the request is capped to what this server accepts
        // in one go, the retention floor is not (capping both is #110 reopened).
        val sizing = folderSyncSizing(limit, session.getBatchSize())
        // The folder rows must land even when the message sync throws: otherwise the drawer keeps
        // stale folders although the list we already fetched is good. The prune stays success-only.
        val sync = runCatching { syncMailbox(session, accountId, auth, target.id, sizing, credentials.id) }
        val syncError = sync.exceptionOrNull()
        // …but a signed-out account is NOT a server hiccup, and this `runCatching` would absorb its
        // refusal. Carrying on would write the WHOLE FOLDER LIST under an id no account owns any
        // more, after the sign-out's orphan sweep ran — #121's ghost by another door.
        if (syncError is AccountGoneException) throw syncError
        // The prune spares whatever this very sync fetched. Passing `getOrNull()` through unmapped
        // keeps the second lock in place: a null there prunes nothing rather than everything (#110).
        if (syncError == null && pruneBeforeMillis != null) {
            // `sizing.retentionFloor`, NEVER `sizing.pageSize`: the floor is the user's window, and
            // the server's per-request limit has no business shortening it.
            pruneRetention(credentials.id, target.id, pruneBeforeMillis, sync.getOrNull()?.fetchedIds?.toSet(), sizing.retentionFloor)
        }
        // Folder counters land AFTER the email rows, so a concurrent optimistic nudge survives.
        mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })
        if (syncError != null) throw syncError
        // Warm the body cache for the top of the inbox. Fire-and-forget so it never delays the list.
        if (target.role == "inbox") {
            bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }
        }

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return MailboxMeta(accountName, target.id, target.name, target.unreadEmails)
    }

    /** Null means "read no opening lines at all" (#187). It answers id AND value: `@Upsert` replaces
     *  the whole row, so dropping the cached values here blanks the column. */
    private suspend fun listPreviewCache(accountId: String): (suspend (List<String>, Long?) -> Map<String, String>)? {
        val prefs = settings ?: return null
        val wanted = runCatching {
            previewWantedInList(prefs.previewLines.first(), prefs.notificationContent.first())
        }.getOrElseUnlessCancelled { false }
        if (!wanted) return null
        return { ids, numbering -> emailDao.cachedPreviews(accountId, ids, numbering).associate { it.id to it.preview } }
    }

    /** MANY writes, at most ONE delete, and none when the walk cannot vouch for what it read.
     *  [limit] does not size this: the window is read off the account. `folderIds` (#165) is passed
     *  NULL, never `emptySet()`. */
    private suspend fun imapWriteThrough(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
    ): ImapFolderLoad {
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val window = fullQueryWindowTarget(accountStore.account(credentials.id)?.syncWindow?.limit, limit)
        return fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            folderIds = { mailboxId?.let { emailDao.idsForMailbox(credentials.id, it).toSet() } },
            walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id)) },
            writePage = { page ->
                checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
                emailDao.upsertAll(page)
            },
            keepIds = { load -> reconcilableIds(load, credentials.id) },
            spareIds = { recentlyMutatedIds(credentials.id) },
            reconcile = { load, keepIds, spare, evictable ->
                // Beyond the newest window: fetch the next bounded batch of what the server holds and the
                // cache lacks, and KEEP the older mail it still holds, so this reconcile deletes only what
                // LEFT the server. Null = the server's list could not be read: nothing is deleted this pass.
                val kept = syncImapBacklog(credentials, load)
                if (kept != null) emailDao.reconcileMailbox(
                    credentials.id, load.targetMailboxId, keepIds + kept, spare,
                    evictableIds = evictable.takeIf { mailboxId != null && mailboxId == load.targetMailboxId },
                )
            },
        )
    }

    /** The folder's UIDs on the server, or null when they cannot be read (cancellation still propagates). */
    private suspend fun serverUidsOrNull(credentials: AccountCredentials, mailbox: String): ImapUidSnapshot? = try {
        imap.snapshotUids(credentials, mailbox, IMAP_SNAPSHOT_CAP)
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    /**
     * The folder as the server holds it, beyond the newest window the walk just wrote: reads the UIDs
     * on the server, fetches the next bounded batch of the missing ones ([ImapBacklogPlan]: new mail
     * above the last cached UID first, then older mail newest first) and answers the ids of every
     * cached row the server still has. Null when the server's list cannot be read.
     */
    private suspend fun syncImapBacklog(credentials: AccountCredentials, load: ImapFolderLoad): Set<String>? {
        if (reconcilableIds(load, credentials.id) == null) return emptySet()
        val mailbox = load.targetMailboxId
        val snapshot = serverUidsOrNull(credentials, mailbox) ?: return null
        val serverIds = snapshot.uids.associateBy { ImapMailService.emailId(credentials.id, mailbox, it) }
        val cached = emailDao.idsForMailbox(credentials.id, mailbox).filterTo(HashSet()) { it in serverIds }
        val plan = ImapBacklogPlan.next(snapshot.uids, cached.mapNotNullTo(HashSet()) { serverIds[it] })
        val fetched = plan.chunked(IMAP_BACKLOG_CHUNK).flatMap { imap.fetchByUids(credentials, mailbox, it) }
        if (fetched.isNotEmpty()) {
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            emailDao.upsertAll(fetched)
        }
        return cached + fetched.map { it.id }
    }

    private suspend fun refreshImap(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
        pruneBeforeMillis: Long?,
    ): MailboxMeta {
        val load = imapWriteThrough(credentials, mailboxId, limit)
        mailboxDao.replaceAll(credentials.id, load.mailboxes)
        // Spares the window just fetched — every page of it: IMAP re-queries the folder on every
        // refresh (#110). The SAME decision the reconcile took, called again rather than restated.
        if (pruneBeforeMillis != null) {
            pruneRetention(
                credentials.id,
                load.targetMailboxId,
                pruneBeforeMillis,
                reconcilableIds(load, credentials.id),
                limit,
            )
        }
        return MailboxMeta(load.accountName, load.targetMailboxId, load.targetName, load.unread)
    }

    /**
     * Fetch a single message (with body). Marks it read when [markRead]; the message pager passes false and
     * marks the entry read once it settles, not while it is flicked past.
     */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        if (credentials.protocol == MailProtocol.IMAP) {
            return refineAuthClass(credentials.id, openEmailImap(credentials, emailId, markRead))
        }
        val email = refineAuthClass(
            credentials.id,
            AuthRetry.withRefreshOn401(refresh = { invalidateSession() }) {
                val ctx = connect(credentials)
                client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
            },
        )
        if (markRead && !email.isSeen) {
            // Through setRead, never inline: it also nudges the drawer count, protects the id from
            // the next reconcile and advances the mailbox emailState.
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        return email
    }

    /**
     * The outcome of ONE best-effort origin read (#160): [header] is what was found, [succeeded] whether the
     */
    private class OriginRead(val header: String?, val succeeded: Boolean)

    /** Best effort, and called from [openMessage] so a failed read is visible where the decision to
     *  CACHE is taken. NOT `headers` in `EMAIL_BODY_PROPERTIES`: one refused property fails the get. */
    private suspend fun originReadFor(
        credentials: AccountCredentials,
        emailId: String,
        email: Email,
    ): OriginRead {
        if (credentials.protocol == MailProtocol.IMAP) {
            return OriginRead(email.originalSender, succeeded = true)
        }
        return try {
            val ctx = connect(credentials)
            val headers = client.getEmailHeaders(ctx.session, ctx.accountId, emailId, ctx.auth)
                .map { it.name to it.value }
            // Autocrypt rides on the read already happening: no second round trip.
            importAutocryptKey(headers, email.from.firstOrNull()?.email, { email.receivedAt })
            OriginRead(header = originalSenderHeaderOf(headers), succeeded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w("MailRepository", "origin header of $emailId unreadable", failure)
            OriginRead(header = null, succeeded = false)
        }
    }

    /** Fire and forget on [bgScope]: a binder call into a foreign process can hang, and call sites
     *  sit on a path someone is waiting on. [receivedAt] is a PRODUCER, so an IMAP date is fetched
     *  inside the launch; it is also the clock a key more than 24 h ahead is refused against. */
    private fun importAutocryptKey(
        headers: List<Pair<String, String>>,
        fromAddress: String?,
        receivedAt: suspend () -> String?,
    ) {
        bgScope.launch {
            runCatching {
                importAutocryptPeer(pgpEngine, headers, fromAddress, receivedAt(), System.currentTimeMillis())
            }
        }
    }

    /** The server's own `INTERNALDATE`, never `envelope.receivedAt`: on IMAP that is the message's
     *  own `Date:`, so a forged one would file an attacker's key under the impersonated peer with a
     *  date no authentic key can beat. Null, without asking the server, when there is no header. */
    private suspend fun autocryptInternalDate(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        headers: List<Pair<String, String>>,
    ): String? {
        if (headers.count { (name, _) -> name.trim().equals("Autocrypt", ignoreCase = true) } != 1) return null
        val millis = try {
            imap.fetchInternalDate(credentials, mailboxId, uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w("MailRepository", "INTERNALDATE unreadable; no Autocrypt key imported", failure)
            null
        }
        return millis?.let { java.time.Instant.ofEpochMilli(it).toString() }
    }

    /**
     * The same read for MANY messages, keyed by id: ONE grouped `Email/get`, best effort, cancellation
     */
    private suspend fun originalSendersOf(ctx: Context, emails: List<Email>): Map<String, String?>? = try {
        val byId = client.getEmailsHeaders(ctx.session, ctx.accountId, emails.map { it.id }, ctx.auth)
        for (email in emails) {
            val headers = byId[email.id] ?: continue
            importAutocryptKey(headers.map { it.name to it.value }, email.from.firstOrNull()?.email, { email.receivedAt })
        }
        byId.mapValues { (_, headers) -> originalSenderHeaderOf(headers.map { it.name to it.value }) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        android.util.Log.w("MailRepository", "origin headers of ${emails.size} prefetched messages unreadable", failure)
        // null, never emptyMap(): an empty map would be cached as "none of them carries one".
        null
    }

    /** The envelope comes from the cache, or from the server when there is none (#159). No row of
     *  `emails` is written here: it would reach `reconcileMailbox`, the counts and the FTS index. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        // FIRST, before the cache is even read: the sanction for an id that is not an IMAP message.
        // A `local-draft:` id sent down this path is the one copy of a text that exists nowhere else.
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()?.toEmail()
        val fetched = if (cached != null) null else try {
            imap.fetchById(credentials, emailId)?.toEmail()
        } catch (cancelled: CancellationException) {
            // Rethrown as-is, which is why this is a `try` and not a `runCatching`:
            // AccountGoneException IS a CancellationException, and must keep its job upstream.
            throw cancelled
        } catch (failure: Throwable) {
            throw MessageUnavailableException("Could not fetch $emailId from the server.", failure)
        }
        // A fetch that came back empty is a message the server no longer holds: same answer.
        val envelope = cached ?: fetched
            ?: throw MessageUnavailableException("Message $emailId is not in the cache and the server did not return it.")
        val mailboxId = envelope.mailboxId ?: error("Unknown mailbox for message.")
        val raw = imap.fetchSource(credentials, mailboxId, uid)
        // Before the parse, because parsing "" succeeds: it yields a body the composer paints as a
        // usable blank draft. Raising sets editingDraftLossyOnOpen, so `performSend` destroys nothing.
        if (sourceCouldNotBeRead(raw)) {
            throw MessageUnavailableException("Source of $emailId came back empty; the server did not give us the message.")
        }
        val body = MimeParser.parseBody(raw)
        if (body.tooLarge) {
            throw ContentTooLargeException(
                "Message source is ${raw.length} characters, over the ${MimeParser.MAX_BODY_CHARS} parse limit.",
                bytes = raw.length.toLong(),
                maxBytes = MimeParser.MAX_BODY_CHARS.toLong(),
            )
        }
        if (markRead && !envelope.isSeen) {
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        // The cache holds the thread KEY but not the threading headers; lift them from the source so
        // a reply carries In-Reply-To/References. The unsubscribe headers ride along for free.
        val unsubscribe = unsubscribeHeadersOf(raw)
        val headers = MimeParser.rawHeaders(raw)
        // Autocrypt (the receiving half): the correspondent's key, off the source already in hand.
        // A key arrives only from a message whose WHOLE source had to be fetched anyway. What keeps
        // it off a message nothing touched is `MessagePaging.needsLoad`'s `settled` argument.
        importAutocryptKey(headers, envelope.from.firstOrNull()?.email, { autocryptInternalDate(credentials, mailboxId, uid, headers) })
        return envelope.withBody(body).copy(
            messageId = headerIds(MimeParser.headerOf(raw, "Message-ID")),
            references = headerIds(MimeParser.headerOf(raw, "References")),
            listUnsubscribe = unsubscribe.first,
            listUnsubscribePost = unsubscribe.second,
            dispositionNotificationTo = readReceiptHeaderOf(raw),
            // Read through `rawHeaders` rather than `headerOf`: that one is a Map where the LAST
            // occurrence wins, which for a relay header names the outermost alias, not the hop.
            originalSender = originalSenderHeaderOf(headers),
        )
    }

    private fun headerIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val bracketed = Regex("<[^<>]+>").findAll(value).map { it.value }.toList()
        return bracketed.ifEmpty { value.trim().split(Regex("\\s+")).map { "<$it>" } }
    }

    private fun Email.withBody(body: MimeBody): Email {
        val attachments = body.attachments.map {
            val isInlineImage = !it.cid.isNullOrBlank() && it.type.startsWith("image/")
            EmailBodyPart(
                partId = it.section,
                name = it.name,
                type = it.type,
                size = it.size.toLong(),
                cid = it.cid,
                disposition = if (isInlineImage) "inline" else "attachment",
                encoding = it.encoding,
            )
        }
        val html = body.html
        val text = body.text
        return when {
            // The type MUST be set: htmlContent() only returns a part typed text/html
            // (issue #4 hardening), so a type-less part renders as an empty body.
            !html.isNullOrBlank() -> copy(
                htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
                bodyValues = mapOf("html" to EmailBodyValue(value = html)),
                attachments = attachments,
            )
            !text.isNullOrBlank() -> copy(
                textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
                bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                attachments = attachments,
            )
            else -> copy(attachments = attachments)
        }
    }

    // ---- Body cache + prefetch -------------------------------------------------------------

    /**
     * Open a message for display, body cache first: a cached body renders with no network round-trip, a miss
     * fetches and persists it. Inline images are resolved before returning. Marks read when [markRead].
     */
    suspend fun openMessage(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): MessageBody {
        // A message decrypted earlier this process renders instantly (memory only, never persisted).
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { entry ->
            // Read the row BEFORE the marking below is launched: this open reports the read state as
            // it stood on opening. Guarded, since a database refusing to answer must not error out.
            val body = reconcileCachedSeen(
                cached = entry.body,
                rowSeen = seenOrUnknown { emailDao.seenOf(credentials.id, emailId) },
            )
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            return body
        }
        // Offline-first: a stored body is rendered with NO network call; inline images missing from it
        // are filled in the background, never awaited.
        cachedMessage(credentials.id, emailId)?.let { cached ->
            if (cached.email.inlineImageParts().isNotEmpty() && cached.inlineImages.isEmpty()) {
                bgScope.launch { runCatching { ensureInlineImages(credentials, emailId, cached) } }
            }
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            // A prefetched body can hold ciphertext: route it to the decrypt flow, not to armor.
            cryptoKindOf(cached.email)?.let { kind ->
                return cached.copy(crypto = MessageCrypto.Locked(kind))
            }
            return cached
        }
        val email = openEmail(credentials, emailId, markRead)
        cryptoKindOf(email)?.let { kind ->
            // Never persist crypto message bodies: the plaintext must not land in the Room cache.
            return MessageBody(email, emptyMap(), crypto = MessageCrypto.Locked(kind))
        }
        // Who a forwarding service says really wrote this (#160), read HERE and not in `openEmail`:
        // only this caller shows the line, and only it can act on a failed read, by not caching.
        val origin = originReadFor(credentials, emailId, email)
        val shown = email.copy(originalSender = origin.header)
        val inline = fetchInlineImages(credentials, shown, emailId)
        // The cache write is CONDITIONAL: a body cached after a failed read would say "no origin"
        // for the life of the install, since this cache is served before any network call.
        if (cachesBodyAfterOriginRead(credentials.protocol, origin.succeeded)) {
            persistBody(credentials.id, emailId, shown, inline)
        }
        return MessageBody(shown, inline)
    }

    /**
     * The raw header fields of a message, in original order with duplicates kept (#60). Fetched on demand for
     * "view headers": JMAP uses the cheap `headers` property, IMAP parses the source.
     */
    suspend fun rawHeaders(credentials: AccountCredentials, emailId: String): List<EmailHeader> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val email = cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)
            val raw = fetchRawSource(credentials, email, emailId)
            return MimeParser.rawHeaders(raw).map { (name, value) -> EmailHeader(name, value) }
        }
        val ctx = connect(credentials)
        return client.getEmailHeaders(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    /** The raw RFC 5322 source, for "Save as .eml". NEVER the clear text of an OpenPGP message: an
     *  encrypted message is saved as received (README: "Decrypted content is never written to
     *  disk"). JMAP needs the `blobId`, which the cache has no column for, hence [openEmail]. */
    suspend fun rawSource(credentials: AccountCredentials, emailId: String, frozen: FrozenNumbering = FrozenNumbering.NothingFrozen): ByteArray {
        val email = if (credentials.protocol == MailProtocol.IMAP) {
            cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)
        } else {
            openEmail(credentials, emailId, markRead = false)
        }
        return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))
    }

    // ---- OpenPGP read path -------------------------------------------------------------------

    /** Decrypted message bodies + their raw decrypted MIME entity, memory only, small LRU. */
    private class DecryptedEntry(val body: MessageBody, val decryptedEntity: String?)

    // Both crypto caches are keyed "$accountId:$emailId" (see [cryptoKey]): same-server accounts can
    // cache colliding email ids (#31), and a bare-id key would serve the sibling account's message.
    private val decryptedCache = android.util.LruCache<String, DecryptedEntry>(8)

    /** Raw sources of crypto messages being decrypted (avoids refetching on interaction retries). */
    private val rawSourceCache = android.util.LruCache<String, String>(4)

    private fun cryptoKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /**
     * Structural check for OpenPGP content on an already-fetched [Email]. Public because the reader's cache
     */
    fun cryptoKindOf(email: Email): CryptoKind? {
        val parts = email.attachments
        if (parts.any { it.type == "application/pgp-encrypted" }) return CryptoKind.PGP_ENCRYPTED
        if (parts.any { it.type == "application/pgp-signature" }) return CryptoKind.PGP_SIGNED
        return inlineArmorKind(email.textContent().orEmpty())
    }

    private suspend fun fetchRawSource(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): String {
        rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }
        val raw = if (credentials.protocol == MailProtocol.IMAP) {
            val mailboxId = emailDao.mailboxOf(credentials.id, emailId) ?: email.mailboxId
                ?: error("Unknown mailbox for message.")
            val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
            // The stamp goes on the wire: without it `onMailbox` selects under the folder's own record,
            // which a cross-account loop realigns on a renumbering. A tick that froze nothing is refused (#189).
            val numbering = when (val verdict = numberingToOppose(frozen)) {
                NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)
                is NumberingToOppose.Select -> verdict.stamp
            }
            imap.fetchSource(credentials, mailboxId, uid, numbering)
        } else {
            val blobId = email.blobId ?: error("Message has no blob id.")
            val ctx = connect(credentials)
            // ISO-8859-1, like the IMAP branch: a raw source is a byte container, one char per octet.
            client.downloadBlob(ctx.session, ctx.accountId, blobId, "message/rfc822", "message.eml", ctx.auth)
                .toString(Charsets.ISO_8859_1)
        }
        rawSourceCache.put(cryptoKey(credentials.id, emailId), raw)
        return raw
    }

    /** Decrypt and/or verify an OpenPGP message via [PgpEngine]. Nothing decrypted is persisted;
     *  [PgpResult.UserInteractionRequired] asks the caller to run the PendingIntent and retry. */
    suspend fun decryptMessage(
        credentials: AccountCredentials,
        emailId: String,
        interactionResult: android.content.Intent? = null,
    ): PgpResult<MessageBody> {
        val pgp = pgpEngine ?: return PgpResult.NotAvailable
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { return PgpResult.Success(it.body) }
        val email = runCatching { openEmail(credentials, emailId, markRead = false) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message") }
        val raw = runCatching { fetchRawSource(credentials, email, emailId) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message source") }
        val envelope = runCatching { MimeParser.detectCrypto(raw) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot parse message structure") }
            ?: return PgpResult.Error("No OpenPGP content found")
        val sender = email.from.firstOrNull()?.email

        // Contain any escaping exception as an Error so the reader shows the status card (#14).
        val result = runCatching {
            when (envelope.kind) {
                CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_INLINE -> pgp.decryptVerify(
                    envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8),
                    senderAddress = sender,
                    interactionResult = interactionResult,
                )
                // ISO-8859-1, not UTF-8: only this reading hands the verifier the octets the sender hashed.
                CryptoKind.PGP_SIGNED -> pgp.decryptVerify(
                    canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),
                    senderAddress = sender,
                    detachedSignature = envelope.signatureArmor!!.toByteArray(Charsets.UTF_8),
                    interactionResult = interactionResult,
                )
            }
        }.getOrElse { return PgpResult.Error(it.message ?: it.javaClass.simpleName) }
        return when (result) {
            is PgpResult.Success -> {
                val entry = runCatching { buildDecrypted(email, envelope, result.value) }
                    .getOrElse { return PgpResult.Error(it.message ?: "Cannot rebuild decrypted body") }
                decryptedCache.put(cryptoKey(credentials.id, emailId), entry)
                rawSourceCache.remove(cryptoKey(credentials.id, emailId))
                PgpResult.Success(entry.body)
            }
            is PgpResult.UserInteractionRequired -> result
            is PgpResult.Error -> result
            PgpResult.NotAvailable -> PgpResult.NotAvailable
        }
    }

    /** RFC 3156: signatures are computed over the entity with CRLF line endings. */
    private fun canonicalizeCrlf(entity: String): String =
        entity.replace("\r\n", "\n").replace("\n", "\r\n")

    private fun buildDecrypted(
        email: Email,
        envelope: CryptoEnvelope,
        decrypted: PgpDecrypted,
    ): DecryptedEntry {
        val crypto = MessageCrypto.Decrypted(
            signature = decrypted.signature,
            signatureUserId = decrypted.signatureUserId,
            signatureKeyId = decrypted.signatureKeyId,
            wasEncrypted = decrypted.wasEncrypted || envelope.kind == CryptoKind.PGP_ENCRYPTED,
        )
        return when (envelope.kind) {
            CryptoKind.PGP_ENCRYPTED -> {
                // Parts are marked "pgp:" so attachment downloads slice the decrypted entity in memory.
                // ISO-8859-1: UTF-8 would lose the octets of any attachment inside.
                val entity = decrypted.plaintext.toString(Charsets.ISO_8859_1)
                val body = MimeParser.parseBody(entity)
                val display = email.withBody(body).run {
                    copy(
                        // RFC 9788 protected headers (#128): this copy stays in memory, never the Room row,
                        // the index, a notification or the widget, and nothing outgoing reads its subject.
                        subject = decryptedSubject(subject, entity),
                        attachments = attachments.map { part ->
                            part.copy(partId = part.partId?.let { "pgp:$it" })
                        },
                        hasAttachment = body.attachments.any { it.cid == null },
                    )
                }
                val inline = display.inlineImageParts().mapNotNull { part ->
                    val section = part.partId?.removePrefix("pgp:") ?: return@mapNotNull null
                    val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val (cte, encoded) = MimeParser.partAt(entity, section) ?: return@mapNotNull null
                    val bytes = MimeParser.decodeBytes(encoded, cte)
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    cid to "data:${part.type ?: "image/jpeg"};base64,$base64"
                }.toMap()
                DecryptedEntry(MessageBody(display, inline, crypto), entity)
            }
            CryptoKind.PGP_INLINE -> {
                val text = decrypted.plaintext.toString(Charsets.UTF_8)
                val display = email.copy(
                    htmlBody = emptyList(),
                    textBody = listOf(EmailBodyPart(partId = "text")),
                    bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                )
                DecryptedEntry(MessageBody(display, emptyMap(), crypto), null)
            }
            CryptoKind.PGP_SIGNED -> {
                DecryptedEntry(MessageBody(email, emptyMap(), crypto), null)
            }
        }
    }

    private fun pgpAttachmentBytes(accountId: String, emailId: String, part: EmailBodyPart): ByteArray {
        val section = part.partId?.removePrefix("pgp:")
            ?: error("Not a decrypted attachment part.")
        val entity = decryptedCache.get(cryptoKey(accountId, emailId))?.decryptedEntity
            ?: error("Message is no longer decrypted — reopen it first.")
        val (cte, encoded) = MimeParser.partAt(entity, section)
            ?: error("Attachment not found in the decrypted message.")
        return MimeParser.decodeBytes(encoded, cte)
    }

    /** [accountId]'s cached body for [emailId], or null if not yet fetched. No network. The READ is
     *  inside the guard, not just the decoding: a row past SQLite's cursor window throws on every read. */
    suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {
        val cached = readCachedOrPurge(
            read = {
                val row = emailBodyDao.byId(accountId, emailId) ?: return@readCachedOrPurge null
                MessageBody(
                    email = cacheJson.decodeFromString(Email.serializer(), row.bodyJson),
                    inlineImages = cacheJson.decodeFromString(inlineImagesSerializer, row.inlineImagesJson),
                )
            },
            purge = { emailBodyDao.deleteById(accountId, emailId) },
        ) ?: return null
        return reconcileCachedSeen(
            cached = cached,
            rowSeen = seenOrUnknown { emailDao.seenOf(accountId, emailId) },
        )
    }

    /** Drop every cached message body of every account; called once after an upgrade. */
    suspend fun clearCachedBodies() {
        emailBodyDao.deleteAll()
    }

    private suspend fun ensureInlineImages(
        credentials: AccountCredentials,
        emailId: String,
        cached: MessageBody,
    ): MessageBody {
        if (cached.email.inlineImageParts().isEmpty() || cached.inlineImages.isNotEmpty()) return cached
        val inline = fetchInlineImages(credentials, cached.email, emailId)
        if (inline.isNotEmpty()) persistBody(credentials.id, emailId, cached.email, inline)
        return cached.copy(inlineImages = inline)
    }

    /**
     * Inline images as `data:` URIs by Content-ID; one past the size ceiling is skipped (see
     * [oversizedInlineImages]).
     */
    private suspend fun fetchInlineImages(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): Map<String, String> {
        val parts = email.inlineImageParts()
        if (parts.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (part in parts) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: continue
            runCatching {
                val bytes = downloadAttachment(
                    credentials, part, emailId, DownloadLimits.INLINE_IMAGE_MAX_BYTES,
                )
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                map[cid] = "data:${part.type ?: "image/jpeg"};base64,$base64"
            }
        }
        return map
    }

    /** Persist a fetched body, then LRU-prune the account. A row bigger than SQLite's cursor window
     *  can be written but never read back, so an oversized body is not cached at all. */
    private suspend fun persistBody(
        accountId: String,
        emailId: String,
        email: Email,
        inlineImages: Map<String, String>,
    ) {
        runCatching {
            val bodyJson = cacheJson.encodeToString(Email.serializer(), email)
            val inlineJson = cacheJson.encodeToString(inlineImagesSerializer, inlineImages)
            if (!fitsBodyCache(bodyJson, inlineJson)) return
            emailBodyDao.upsert(
                EmailBodyEntity(
                    id = emailId,
                    accountId = accountId,
                    bodyJson = bodyJson,
                    inlineImagesJson = inlineJson,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Prefetch the newest [PREFETCH_COUNT] inbox bodies. JMAP only; best-effort, never throws. */
    private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        runCatching {
            // Bounded in the statement, not with a `take`: `runCatching` does not catch an OutOfMemoryError.
            val newest = emailDao.newestIds(credentials.id, mailboxId, PREFETCH_COUNT)
            if (newest.isEmpty()) return
            val already = emailBodyDao.cachedIds(credentials.id, newest).toSet()
            val missing = newest.filter { it !in already }
            if (missing.isEmpty()) return
            val ctx = connect(credentials)
            val emails = client.getEmailsWithBody(ctx.session, ctx.accountId, missing, ctx.auth)
            // The origin header (#160) is posed here too, not only in `openEmail`. `?: return` — a window
            // whose origin read failed is not persisted, or those bodies would say "no origin" for good.
            val origins = originalSendersOf(ctx, emails) ?: return
            for (email in emails) {
                val withOrigin = email.copy(originalSender = origins[email.id])
                persistBody(credentials.id, email.id, withOrigin, emptyMap())
            }
        }
    }

    suspend fun setRead(credentials: AccountCredentials, emailId: String, seen: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        // Capture before the change so we only move the folder counter on a real transition.
        val wasSeen = emailDao.seenOf(credentials.id, emailId)
        val mailboxId = emailDao.mailboxOf(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            emailDao.setSeen(credentials.id, emailId, seen)
            adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
            return
        }
        val ctx = connect(credentials)
        val newState = try {
            client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side: prune the zombie row; any other failure keeps it untouched.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        emailDao.setSeen(credentials.id, emailId, seen)
        adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
        advanceEmailState(newState, credentials.id, mailboxId)
    }

    /** Mark many messages read/unread. JMAP: one `Email/set` per server-sized chunk, applied only to
     *  ids the server confirmed. IMAP flags are per-message, so that branch stays on [setRead]. */
    suspend fun setReadAll(credentials: AccountCredentials, emailIds: List<String>, seen: Boolean) {
        if (emailIds.isEmpty()) return
        if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.forEach { runCatching { setRead(credentials, it, seen) } }
            return
        }
        val ctx = connect(credentials)
        // The chunk is the server's own limit, so [JmapClient.move] never has to re-split it.
        emailIds.chunked(ctx.session.setBatchSize()).forEach { chunk ->
            // Captured before the write so only real transitions nudge the counters (#46). Chunked again
            // on purpose: the network limit above must not push this SELECT past SQLite's 999 bindings.
            val rows = byIdsChunked(chunk) { slice -> emailDao.emailsByIds(credentials.id, slice) }
                .associateBy { it.id }
            val result = client.setSeenAll(ctx.session, ctx.accountId, chunk, seen, ctx.auth)
            val gone = chunk.filter { result.failed[it] == SET_ERROR_NOT_FOUND }
            if (gone.isNotEmpty()) pruneServerGone(credentials.id, gone)
            val done = chunk.filter { it in result.done }
            done.forEach { markRecentlyMutated(credentials.id, it); emailDao.setSeen(credentials.id, it, seen) }
            done.mapNotNull { rows[it] }.filter { it.seen != seen }
                .groupBy { it.mailboxId }
                .forEach { (mailboxId, group) ->
                    val delta = if (seen) -group.size else group.size
                    accountStore.adjustInboxUnread(credentials.id, mailboxId, delta)
                    mailboxDao.adjustCounts(credentials.id, mailboxId, totalDelta = 0, unreadDelta = delta)
                }
            advanceEmailState(result.newState, credentials.id, *done.mapNotNull { rows[it]?.mailboxId }.toTypedArray())
        }
    }

    /** Keep the drawer's cached folder unread counter fresh; only moves on a real change (#46). */
    private suspend fun adjustFolderUnreadOnRead(accountId: String, mailboxId: String?, wasSeen: Boolean?, seen: Boolean) {
        if (mailboxId == null || wasSeen == null || wasSeen == seen) return
        val delta = if (seen) -1 else 1
        accountStore.adjustInboxUnread(accountId, mailboxId, delta)
        if (!isImapAccount(accountId)) mailboxDao.adjustCounts(accountId, mailboxId, totalDelta = 0, unreadDelta = delta)
    }

    /** IMAP folder rows carry no server unread counts (a hard 0), so nudging deltas onto that baseline
     *  would manufacture bogus badges. The stored inbox meta is NOT gated: its baseline is real. */
    private fun isImapAccount(accountId: String): Boolean =
        accountStore.account(accountId)?.protocol == MailProtocol.IMAP

    /**
     * Whether [accountId] DEFAULT-FOLDS its custom parent folders (#185) — the only thing this now
     * decides, through `defaultCollapsedFolderIds`.
     *
     * ⛔ The name is older than the answer and is kept only because three source lints pin the
     * identifier. It once meant "can a folder row badge unread at all?", and while IMAP rows held
     * the hard 0 the two questions had one answer. #247 gave IMAP the same live count JMAP has, so
     * badging is now universal and the questions have come apart.
     *
     * It stays `!isImapAccount` DELIBERATELY, and the value is a fold policy, not a capability. #185
     * folds a custom parent by default because the folded row still shows what it hides; every IMAP
     * account has nonetheless spent its whole life with folding off, and deriving the default from
     * the new counts would fold parents open since the day they were made — the drawer would
     * rearrange itself as a side effect of gaining counts, which is not what was asked for. Whether
     * IMAP should now fold like JMAP is a question for the owner, not a consequence to be inherited.
     */
    fun folderRowsBadgeUnread(accountId: String): Boolean = !isImapAccount(accountId)

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(credentials.id, emailId, flagged)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        emailDao.setFlagged(credentials.id, emailId, flagged)
        advanceEmailState(newState, credentials.id, mb)
    }

    // ---- labels: JMAP mailbox membership, and per-message keywords ----
    //
    // THE TWO ARE DIFFERENT THINGS AND THIS APP KEEPS THEM APART.
    //
    //   mailboxIds   RFC 8621 §4.1.1. A message belongs to a SET of mailboxes AT ONCE. This is
    //                what a label is on a JMAP server, and what the drawer's categories are here.
    //                Editing it means ADD one or REMOVE one; "move" is those two together, and on
    //                a message in three mailboxes it is not even a well-defined word.
    //   keywords     RFC 8621 §4.1.1 as well, but a per-message FLAG set: `$seen`, `$flagged`,
    //                `$draft`, `$answered`, `$forwarded` are protocol, and anything without the
    //                leading `$` is a name the user (or their server-side filters) chose.
    //
    // A surface that showed one and called it the other would be lying about which server state a
    // tap changes, so both are offered and both are named.

    /**
     * Every mailbox [emailId] is currently in, straight from the server.
     *
     * Asked of the server rather than of the cache on purpose: the local row carries ONE
     * `mailboxId` — the folder the listing filed it under — and that single value is exactly the
     * misconception this whole area exists to correct. A cache that cannot represent the answer
     * must not be the one that gives it.
     *
     * Empty on IMAP, where a message really does live in one folder and there is no set to read.
     */
    suspend fun mailboxMembership(credentials: AccountCredentials, emailId: String): Set<String> {
        if (credentials.protocol == MailProtocol.IMAP) return emptySet()
        val ctx = connect(credentials)
        return client.mailboxIdsOf(ctx.session, ctx.accountId, listOf(emailId), ctx.auth)[emailId].orEmpty()
    }

    /**
     * Put [emailId] in [mailboxId] as well, keeping every mailbox it is already in.
     *
     * JMAP only. On IMAP this would have to be a COPY, which duplicates the message and gives the
     * duplicate its own uid, its own read state and its own life — that is not what "add a label"
     * promises, so it is refused rather than approximated.
     */
    suspend fun addToMailbox(credentials: AccountCredentials, emailId: String, mailboxId: String) {
        require(credentials.protocol != MailProtocol.IMAP) { "adding a mailbox needs JMAP" }
        markRecentlyMutated(credentials.id, emailId)
        val ctx = connect(credentials)
        val newState = client.addToMailbox(ctx.session, ctx.accountId, emailId, mailboxId, ctx.auth)
        advanceEmailState(newState, credentials.id, emailDao.mailboxOf(credentials.id, emailId))
    }

    /**
     * Take [emailId] out of [mailboxId], keeping every other mailbox it is in.
     *
     * The local row is NOT deleted here even when the mailbox removed is the one it was filed
     * under. The message still exists and is still in its other mailboxes; deleting the row would
     * be this app deciding that its own single-folder cache is the truth, which is the same
     * mistake one level down. The next sync files it under a mailbox it is actually in.
     */
    suspend fun removeFromMailbox(credentials: AccountCredentials, emailId: String, mailboxId: String) {
        require(credentials.protocol != MailProtocol.IMAP) { "removing a mailbox needs JMAP" }
        markRecentlyMutated(credentials.id, emailId)
        val ctx = connect(credentials)
        val newState = client.removeFromMailbox(ctx.session, ctx.accountId, emailId, mailboxId, ctx.auth)
        advanceEmailState(newState, credentials.id, emailDao.mailboxOf(credentials.id, emailId))
    }

    /**
     * Set or clear one user keyword on [emailId] — the other kind of tag.
     *
     * Refuses the `$`-prefixed system keywords: `$seen` and `$flagged` have their own paths that
     * also nudge the unread counters and the star, and a second writer of the same server state
     * that skips those is how the two come to disagree.
     */
    suspend fun setUserKeyword(
        credentials: AccountCredentials,
        emailId: String,
        keyword: String,
        value: Boolean,
    ) {
        require(!keyword.startsWith("$")) { "system keywords go through their own paths" }
        markRecentlyMutated(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, keyword, value) }
            return
        }
        val ctx = connect(credentials)
        val newState = client.setKeyword(ctx.session, ctx.accountId, emailId, keyword, value, ctx.auth)
        advanceEmailState(newState, credentials.id, emailDao.mailboxOf(credentials.id, emailId))
    }

    private fun imapTarget(emailId: String): Pair<String, Long>? = ImapMailService.targetOf(emailId)

    /** Archive a message. Resolution order — a real Archive folder, else All Mail (Gmail-style
     *  accounts have none), else create an Archive folder as a last resort. */
    suspend fun archive(credentials: AccountCredentials, emailId: String): String? {
        // Leaving a folder withdraws the message from any pending Empty-trash order (#99).
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        // Opt-in: flag the message read on its way out. Best-effort BEFORE the move (the id changes
        // with an IMAP move) and before [row] is read, so the count nudge sees the new state (#67).
        if (settings?.markReadOnArchive?.first() == true && emailDao.seenOf(credentials.id, emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            var noop = false
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == dest) { noop = true; return@let } // already in the archive/all folder
                // The numbering [mb] was last seen under, opposed to what the server states in the SELECT this
                // move goes out under: a renumbered folder refuses instead of archiving another message (#99).
                val landed = imap.move(credentials, mb, uid, dest, recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(dest, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                }
            }
            if (noop) {
                evictAlreadyThere(credentials.id, dest, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), dest)
            return dest
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        if (mb == target) return null // already in the archive/all folder — nothing to do
        // Network-first: the local row is dropped only after the server acknowledged, so a failed
        // archive never hides a message that is still on the server.
        val newState = try {
            // `mb` is the folder this message is LEAVING. Threading it through is what makes the
            // patch an edit of the membership rather than a replacement of it — see putMembershipPatch.
            client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth, sourceMailboxId = mb)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(row), target)
        advanceEmailState(newState, credentials.id, mb)
        return target
    }

    /** Whether deleting [email] would permanently destroy it: does it already sit in the Trash? An
     *  account with no Trash is NOT a destroy — [delete] resolves-or-creates the bin, so a `true` here
     *  would send the ordinary swipe into `MessageDestroyWorker` (#23). Never creates the bin. */
    suspend fun deleteWouldDestroy(credentials: AccountCredentials, email: Email): Boolean {
        val trash = if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, "trash")
        else trashMailboxId(connect(credentials))
        return deleteDestroysMessage(trash, email.mailboxId)
    }

    /** Whether this account has a Trash at all — [deleteWouldDestroy]'s half that does not depend on a
     *  message's own folder, for a caller whose row carries a `mailboxId` it cannot believe. */
    suspend fun accountHasTrash(credentials: AccountCredentials): Boolean =
        roleMailboxId(credentials, "trash") != null

    /** Whether the folder CACHE names a Trash for [accountId], for a caller that cannot afford a
     *  socket. It answers about the cache, so it says "no Trash" for an account never synced. */
    suspend fun hasCachedTrash(accountId: String): Boolean =
        mailboxDao.idForRole(accountId, "trash") != null

    /** The IMAP numbering last observed for [mailboxId], to FREEZE at a user's confirmation and oppose
     *  much later. Null on JMAP and on a folder never observed: nothing to oppose destroys nothing (#99).
     *  Read at the confirmation and never again: re-read at execution the guard always passes. */
    suspend fun recordedUidValidity(credentials: AccountCredentials, mailboxId: String): Long? =
        if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, mailboxId) else null

    /** The IMAP numbering each of [emailIds] was READ under, off the cached rows (#99). THE ROWS, NOT
     *  THE FOLDER: a background pass that met the renumbering has already realigned the folder's record
     *  while the rows still carry the old UIDs. An absent id reads as null; empty on JMAP. Chunked. */
    suspend fun numberingRowsWereReadUnder(
        credentials: AccountCredentials,
        emailIds: List<String>,
    ): Map<String, Long?> {
        return numberingStampsOfRows(credentials, emailIds) { chunk -> emailDao.numberingOfRows(credentials.id, chunk) }
    }

    /** The numbering to FREEZE for the draft [emailId], opposed later by [discardDraft] and the replace
     *  half of [saveDraft]. CALL IT WHEN THE ID IS READ, NEVER WHEN SAVING: at the save it reads the
     *  number recorded after the renumbering and the expunge hits a UID that names another draft (#99). */
    suspend fun recordedUidValidityForDraft(credentials: AccountCredentials, emailId: String): Long? {
        if (credentials.protocol != MailProtocol.IMAP) return null
        val folder = emailDao.mailboxOf(credentials.id, emailId)
            ?: mailboxDao.idForRole(credentials.id, "drafts")
            ?: return null
        return recordedUidValidity(credentials, folder)
    }

    /** The folder the SERVER draft [emailId] sits in — what a held-back destroy has to carry, or on
     *  JMAP `destroyAll` spares every wave in silence (#122). The pick is [draftDestroyFolder]. */
    suspend fun draftMailboxOf(credentials: AccountCredentials, emailId: String): String? =
        draftDestroyFolder(
            cachedMailboxId = emailDao.mailboxOf(credentials.id, emailId),
            draftsRoleMailboxId = mailboxDao.idForRole(credentials.id, "drafts"),
        )

    /** Move a message to an arbitrary mailbox. Returns the destination it ended up in (null = a
     *  no-op, already there), so the caller can offer an Undo that also reverses the count nudge. */
    suspend fun moveToMailbox(credentials: AccountCredentials, emailId: String, targetMailboxId: String): String? {
        // Rescue path of #99: filing a message elsewhere during the undo window takes it off the list.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        markReadOnMoveOutOfInbox(credentials, listOf(emailId), targetMailboxId)
        // Captured before the local row is dropped, to nudge the drawer's cached counts (INV-COUNT).
        val moved = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (moved != null && moved.mailboxId == targetMailboxId) return null
        // Network-first: report-spam / not-spam reach this path and don't restore the row on failure,
        // so a failed move must never leave the cache short of a row still on the server.
        if (credentials.protocol == MailProtocol.IMAP) {
            val already = imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == targetMailboxId) return@let true
                // Same guard as [archive] and [delete]: the source folder's recorded numbering (#99).
                val landed = imap.move(credentials, mb, uid, targetMailboxId, recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(targetMailboxId, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, targetMailboxId, it))
                }
                false
            } ?: false
            if (already) {
                evictAlreadyThere(credentials.id, targetMailboxId, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
            return targetMailboxId
        }
        val ctx = connect(credentials)
        val mb = moved?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, targetMailboxId, ctx.auth, sourceMailboxId = mb)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
        advanceEmailState(newState, credentials.id, mb)
        return targetMailboxId
    }

    /** Nudge the drawer's cached counters when [rows] leave for [destMailboxId] (null when destroyed).
     *  Each row is decremented from its OWN mailboxId (INV-SOURCE); the next sync reconciles. */
    private suspend fun adjustCountsForRemoval(rows: List<EmailEntity>, destMailboxId: String?) =
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            nudgeCounts(accountId, group.map { it.mailboxId to it.seen }, destMailboxId)
        }

    /** The count-nudge primitive: [sources] is (sourceMailboxId, seen) per row; an Undo reuses it in
     *  reverse. Scoped to [accountId], or a nudge from the unified inbox moves a sibling's badge. */
    private suspend fun nudgeCounts(accountId: String, sources: List<Pair<String, Boolean>>, destMailboxId: String?) {
        val imap = isImapAccount(accountId)
        sources.groupBy { it.first }.forEach { (src, group) ->
            if (src == destMailboxId) return@forEach
            val unread = group.count { !it.second }
            accountStore.adjustInboxUnread(accountId, src, -unread)
            if (!imap) mailboxDao.adjustCounts(accountId, src, totalDelta = -group.size, unreadDelta = -unread)
        }
        if (destMailboxId != null) {
            val incoming = sources.filter { it.first != destMailboxId }
            if (incoming.isNotEmpty()) {
                val unread = incoming.count { !it.second }
                accountStore.adjustInboxUnread(accountId, destMailboxId, unread)
                if (!imap) mailboxDao.adjustCounts(accountId, destMailboxId, totalDelta = incoming.size, unreadDelta = unread)
            }
        }
    }

    // ---- bulk (batched) actions (Codeberg #29) ----------------------------------------

    /** Which ids of a batch went through, and which failed. [dest] is the folder the succeeded ids
     *  were moved to (null = destroyed), so an undoable bulk op can reverse the count nudge. */
    class BulkResult(val succeeded: Set<String>, val failed: Set<String>, val dest: String? = null) {
        companion object { val EMPTY = BulkResult(emptySet(), emptySet()) }
    }

    /**
     * Cache+index removal chunked to [MAX_CHANGES]: `email_fts` is FTS4 with `emailId` `notindexed`, so one
     * un-index is a full scan.
     */
    private suspend fun deleteFromCacheAndIndex(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(MAX_CHANGES).forEach { emailDao.deleteByIds(accountId, it) }
    }

    /** Local cleanup of an action that MOVED NOTHING. The cached row goes; the index row goes only in
     *  Trash/Junk/Spam, where a spared row is an orphan nothing brings back ([noOpEvictionFor]). */
    private suspend fun evictAlreadyThere(accountId: String, mailboxId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        when (noOpEvictionFor(mailboxId, mailboxDao.roleForId(accountId, mailboxId))) {
            NoOpEviction.SPARE_INDEX_ROW ->
                ids.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
            NoOpEviction.TAKE_INDEX_ROW -> deleteFromCacheAndIndex(accountId, ids)
        }
    }

    /** Batch-move one IMAP source folder's [ids] to [dest] with one `UID MOVE`, recording each new UID
     *  and [dest]'s numbering in [lastImapMove] so Undo can oppose it. Every id the move did not PROVE
     *  left [source] is failed: an `OK` says the command ran, not that any message moved.
     *  [expectedUidValidity] is [source]'s numbering AS THE CALLER FROZE IT, with no default (#99). */
    private suspend fun imapMoveGroup(
        credentials: AccountCredentials, source: String, ids: List<String>, dest: String,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long?,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        // Captured before the local rows are dropped, to nudge the drawer counts (INV-COUNT).
        val rows = byIdsChunked(uidToId.values.toList()) { chunk -> emailDao.emailsByIds(credentials.id, chunk) }
        runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }
            .onSuccess { landed ->
                uidToId.forEach { (uid, id) ->
                    landed.uids[uid]?.let {
                        lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)
                        recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                    }
                }
                // The verdict FIRST, and nothing is dropped before it: a UID MOVE on a uid already gone is a
                // no-op the server accepts. What is unproved is unknown, and unknown fails.
                val outcome = imapMoveOutcome(uidToId, landed.confirmedGone)
                succeeded += outcome.gone
                failed += outcome.unproven
                deleteFromCacheAndIndex(credentials.id, outcome.gone)
                adjustCountsForRemoval(rows.filter { it.id in outcome.gone }, dest)
            }
            .onFailure { failed += uidToId.values }
    }

    /** Batch-destroy one IMAP folder's [ids] with `UID STORE`+`UID EXPUNGE`. A failed command THROWS
     *  rather than marking the ids failed: the worker must retry a confirmed destroy, not abandon it. */
    private suspend fun imapDestroyGroup(
        credentials: AccountCredentials, source: String, ids: List<String>,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long? = null,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        val rows = byIdsChunked(uidToId.values.toList()) { chunk -> emailDao.emailsByIds(credentials.id, chunk) }
        imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)
        deleteFromCacheAndIndex(credentials.id, uidToId.values)
        succeeded += uidToId.values
        adjustCountsForRemoval(rows, destMailboxId = null)
    }

    /** JMAP: move every id to [target], then drop the local rows. Only ids the server confirmed are
     *  dropped; anything else keeps its row and lands in [BulkResult.failed]. */
    private suspend fun jmapMoveAll(ctx: Context, emailIds: List<String>, target: String): BulkResult {
        val localAccountId = ctx.credentials.id
        val rows = byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(localAccountId, chunk) }
        // Per ID, not one source for the batch: a bulk move drains SEVERAL folders at once (the
        // unified inbox does exactly that), and one shared source would clear a membership the
        // other messages never had while leaving theirs in place.
        val sources = rows.associate { it.id to it.mailboxId }
        return runCatching { client.move(ctx.session, ctx.accountId, emailIds, target, ctx.auth, sources) }
            .map { result ->
                val moved = emailIds.filter { it in result.done }.toSet()
                moved.forEach { recentLocalMoves.mark(localAccountId, it) }
                val alreadyThere = idsAlreadyIn(rows, moved, target)
                evictAlreadyThere(localAccountId, target, alreadyThere)
                deleteFromCacheAndIndex(localAccountId, moved - alreadyThere.toSet())
                adjustCountsForRemoval(rows.filter { it.id in moved }, target)
                // Prune the notFound rows but keep them in `failed`: they must not feed a move-Undo.
                pruneServerGone(localAccountId, emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND })
                BulkResult(moved, emailIds.toSet() - moved, dest = target)
            }
            .getOrElse { BulkResult(emptySet(), emailIds.toSet()) }
    }

    /** JMAP: destroy every id, then drop the confirmed rows. A failed request THROWS rather than marking
     *  ids failed; the worker replays the list and an already-destroyed id returns `notFound`. */
    private suspend fun jmapDestroyAll(ctx: Context, emailIds: List<String>): BulkResult {
        val localAccountId = ctx.credentials.id
        val rows = byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(localAccountId, chunk) }
        val result = client.destroy(ctx.session, ctx.accountId, emailIds, ctx.auth)
        val destroyed = emailIds.filter { it in result.done }.toSet()
        deleteFromCacheAndIndex(localAccountId, destroyed)
        adjustCountsForRemoval(rows.filter { it.id in destroyed }, destMailboxId = null)
        // Already destroyed: the requested end state holds, so prune the row and report success.
        val gone = emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND }.toSet()
        pruneServerGone(localAccountId, gone.toList())
        return BulkResult(destroyed + gone, emailIds.toSet() - destroyed - gone)
    }

    /** Opt-in mark-read-on-archive/delete for a bulk action. MUST run BEFORE the mover reads its rows
     *  for the count nudge, or the unread badge is decremented twice. */
    private suspend fun markSelectionRead(credentials: AccountCredentials, emailIds: List<String>) {
        // Account-scoped read (#31): same-server sub-accounts can hold colliding email ids.
        val unread = byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(credentials.id, chunk) }
            .filter { !it.seen }.map { it.id }
        if (unread.isEmpty()) return
        runCatching { setReadAll(credentials, unread, true) }
    }

    /** Opt-in mark-read-on-move (#67), scoped to messages LEAVING the Inbox: a move between two other
     *  folders is left alone, and so is a move INTO it. Called before the movers' count-nudge read. */
    private suspend fun markReadOnMoveOutOfInbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
    ) {
        if (settings?.markReadOnMove?.first() != true) return
        val inbox = runCatching { roleMailboxId(credentials, "inbox") }.getOrNull() ?: return
        if (targetMailboxId == inbox) return
        val leaving = if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.filter { ImapMailService.mailboxOf(it) == inbox }
        } else {
            // Account-scoped (issue #31): resolve source folders within this account only.
            byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(credentials.id, chunk) }
                .filter { it.mailboxId == inbox }.map { it.id }
        }
        if (leaving.isNotEmpty()) markSelectionRead(credentials, leaving)
    }

    /** Archive a whole selection (one account). [expectedUidValidity] is each SOURCE FOLDER's numbering
     *  as the caller froze it; empty means "nothing frozen", which refuses (#99). */
    suspend fun archiveAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // leaving a folder cancels a pending purge order (#99)
        // Opt-in mark-read-on-archive: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnArchive?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the archive: nothing moved, and [evictAlreadyThere] decides the index row.
                    source == dest -> { evictAlreadyThere(credentials.id, dest, ids); succeeded += ids }
                    else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = dest)
        }
        val ctx = connect(credentials)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        return jmapMoveAll(ctx, emailIds, target)
    }

    /** #50 (opt-out): when a genuinely-new reply lands in the Inbox, pull the thread's archived members
     *  back. JMAP only, server-first. Guarded three times against a sign-out landing mid-pass (#121);
     *  none of the three closes the race, they narrow it. */
    suspend fun unarchiveThreadsOnReply(credentials: AccountCredentials, threadIds: Set<String>): List<Email> {
        if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        // Below the early return (which writes nothing anywhere), above the session (#121).
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val ctx = connect(credentials)
        val inbox = ctx.rolesToMailboxId["inbox"] ?: return emptyList()
        val archive = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: return emptyList()
        if (archive == inbox) return emptyList()
        val members = emailDao.threadMembersInMailbox(credentials.id, archive, threadIds.toList())
        if (members.isEmpty()) return emptyList()
        // Uniform rule (#99): whatever moves leaves the destroy list, exceptionless.
        unlistFromTrashPurge(credentials.id, members.map { it.id })
        // Protect the rows BEFORE the server call, so a sync firing mid-move can't evict them.
        members.forEach { markRecentlyMutated(credentials.id, it.id) }
        // The widest window of the three: a sign-out landing inside it un-archives the thread ON THE
        // ACCOUNT with no Undo. Outside the runCatching, so its refusal is not read as a failed move.
        // Every member was selected FROM the archive folder, so that is the membership each one
        // leaves. Built here rather than inside the runCatching, which is pinned line for line by
        // SignedOutAccountWiringTest to hold the sign-out guard immediately above the server call.
        val fromArchive = members.associate { it.id to archive }
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val result = runCatching {
            client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth, fromArchive)
        }.getOrNull() ?: return emptyList()
        val moved = members.filter { it.id in result.done }
        if (moved.isEmpty()) return emptyList()
        // Self-moves too: a concurrent pass on another watched folder must not see them as fresh.
        moved.forEach { recentLocalMoves.mark(credentials.id, it.id) }
        val refiled = moved.map { it.copy(mailboxId = inbox) }
        // Ask again: only a check HERE stands between a sign-out and rows written under an id no
        // account owns. Outside the `runCatching` above on purpose.
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        emailDao.upsertAll(refiled)
        adjustCountsForRemoval(moved, inbox)
        return refiled.map { it.toEmail() }
    }

    /** Move a whole selection (one account) to [targetMailboxId]. [expectedUidValidity]: see
     *  [archiveAll] — the source folders' frozen numbering, by folder. */
    suspend fun moveAllToMailbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // bulk rescue out of the Trash (#99)
        markReadOnMoveOutOfInbox(credentials, emailIds, targetMailboxId)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the destination: nothing moved; [evictAlreadyThere] decides the index row.
                    source == targetMailboxId -> {
                        evictAlreadyThere(credentials.id, targetMailboxId, ids); succeeded += ids
                    }
                    else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = targetMailboxId)
        }
        return jmapMoveAll(connect(credentials), emailIds, targetMailboxId)
    }

    /** Delete a whole selection (one account): move everything to Trash. Like [delete] it NEVER destroys
     *  inline — the caller routes that subset through [destroyAll] (#23) — and it resolves or CREATES the bin. */
    suspend fun deleteAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // see [archiveAll] (#99)
        // Opt-in mark-read-on-delete: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnDelete?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            // Resolve or create, as [delete] does. Offline it throws and the batch goes down, creating nothing.
            val trash = imapRoleFolder(credentials, "trash") ?: run { imap.createFolder(credentials, "Trash"); "Trash" }
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in Trash: drop the row locally, in NEITHER set, and un-indexed (an orphan otherwise).
                    source == trash -> deleteFromCacheAndIndex(credentials.id, ids)
                    else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = trash)
        }
        val ctx = connect(credentials)
        // Role, then name, then a creation; the id is written back into the context, so ONE bin per batch.
        val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)
        return jmapMoveAll(ctx, emailIds, trash)
    }

    /** Permanently destroy a whole selection — the held-back bulk destroy (#23/#29). THROWS on a
     *  transport failure so the worker retries; [BulkResult.failed] carries per-id rejections and the
     *  ids this refused. [expectedMailboxId] is the folder the ids were in WHEN THE USER CONFIRMED,
     *  verified on JMAP (#122); IMAP uses [expectedUidValidity]. Unverifiable destroys NOTHING. */
    suspend fun destroyAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedMailboxId: String?,
        expectedUidValidity: Long? = null,
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            // The net under the numbering the caller froze: with nothing to oppose, an expunge would go out
            // against whatever the folder is numbered now (#99). Refused ids go to `failed`, never done.
            val plan = UidValidity.imapDestroyPlan(emailIds, expectedUidValidity)
            failed += plan.refused
            plan.byFolder.forEach { (source, ids) ->
                imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)
            }
            return BulkResult(succeeded, failed)
        }
        val ctx = connect(credentials)
        val stillThere = idsStillOnlyIn(ctx, emailIds, expectedMailboxId)
        val spared = emailIds.toSet() - stillThere.toSet()
        // ONE line for the whole wave: a server that never reports `mailboxIds` spares everything for
        // ever, in silence, and that is indistinguishable from a guard doing its job.
        if (spared.isNotEmpty()) {
            android.util.Log.i(
                "MailSync",
                "destroy spared ${credentials.id}/$expectedMailboxId: ${spared.size} of " +
                    "${emailIds.size} not in that folder alone: ${spared.joinToString()}",
            )
        }
        if (stillThere.isEmpty()) return BulkResult(emptySet(), spared)
        val result = jmapDestroyAll(ctx, stillThere)
        return BulkResult(result.succeeded, result.failed + spared)
    }

    /** The ids of [emailIds] the server reports in [expectedMailboxId] AND NOWHERE ELSE — the only
     *  ones a destroy may touch (#122). Deliberately unguarded: a failed location read throws and the
     *  worker retries; falling back on [emailIds] would bring the data loss back whole. */
    private suspend fun idsStillOnlyIn(
        ctx: Context,
        emailIds: List<String>,
        expectedMailboxId: String?,
    ): List<String> {
        val located = client.mailboxIdsOf(ctx.session, ctx.accountId, emailIds, ctx.auth)
        return TrashPurge.destroyableIds(emailIds, expectedMailboxId, located)
    }

    /** Report a whole selection as spam (move to Junk) in one batch. [expectedUidValidity] is not
     *  optional in practice: an empty map refuses the whole batch on IMAP (#99). */
    suspend fun reportSpamAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        val junk = roleMailboxId(credentials, "junk") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, junk, expectedUidValidity)
    }

    /** Move a whole selection out of Junk back to the Inbox. [expectedUidValidity]: see [reportSpamAll]. */
    suspend fun notSpamAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        val inbox = roleMailboxId(credentials, "inbox") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, inbox, expectedUidValidity)
    }

    /** The cached role of an account's mailbox (e.g. "junk", "inbox"), or null. [accountId] null
     *  falls back to the current account — same-server accounts can share a bare mailbox id. */
    suspend fun mailboxRole(accountId: String?, mailboxId: String?): String? {
        if (mailboxId == null) return null
        val account = accountId ?: accountStore.currentId() ?: return null
        return mailboxDao.roleForId(account, mailboxId)
    }

    /** Move a message to the Junk folder (Report spam). */
    suspend fun reportSpam(credentials: AccountCredentials, emailId: String) {
        val junk = roleMailboxId(credentials, "junk") ?: error("This account has no Junk folder.")
        moveToMailbox(credentials, emailId, junk)
    }

    /** Move a message out of Junk back to the Inbox (Not spam). */
    suspend fun notSpam(credentials: AccountCredentials, emailId: String) {
        val inbox = roleMailboxId(credentials, "inbox") ?: error("This account has no Inbox.")
        moveToMailbox(credentials, emailId, inbox)
    }

    /** A role's mailbox id for a SPECIFIC account. On JMAP it comes from that account's own connection
     *  context, not the folder cache, which holds nothing for an account never listed. */
    private suspend fun roleMailboxId(credentials: AccountCredentials, role: String): String? =
        if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, role)
        else connect(credentials).rolesToMailboxId[role]

    /** The first matching [roles] in a SPECIFIC IMAP account, by listing it (see [roleMailboxId]). */
    private suspend fun imapRoleFolder(credentials: AccountCredentials, vararg roles: String): String? {
        val folders = imap.listMailboxes(credentials)
        for (role in roles) folders.firstOrNull { it.role == role }?.let { return it.id }
        return null
    }

    // ---- recipient suggestions ----

    /** Record people we send to, so they surface in recipient autocomplete next time. */
    suspend fun rememberRecipients(addresses: List<String>) {
        val now = System.currentTimeMillis()
        addresses.map { it.trim() }.filter { it.contains('@') }.distinctBy { it.lowercase() }.forEach { addr ->
            val (name, email) = parseAddress(addr)
            recentContactDao.upsert(RecentContactEntity(email = email.lowercase(), name = name, lastSeen = now))
        }
    }

    /** Deduped recipient suggestions for [query]: people we've sent to, plus cached senders. */
    suspend fun suggestContacts(query: String, limit: Int = 6): List<ContactRow> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return (recentContactDao.search(q, limit) + emailDao.suggestSenders(q, limit))
            .filter { it.email.contains('@') }
            .distinctBy { it.email.lowercase() }
            .take(limit)
    }

    private fun parseAddress(s: String): Pair<String?, String> {
        val m = Regex("^(.*?)<([^>]+)>").find(s.trim())
        return if (m != null) m.groupValues[1].trim().ifBlank { null } to m.groupValues[2].trim()
        else null to s.trim()
    }

    // ---- snooze ----

    /** Snooze a message until [until] (hidden from lists; re-appears at that time). */
    suspend fun snooze(emailId: String, accountId: String, until: Long) =
        snoozedDao.upsert(SnoozedEntity(emailId, accountId, until))

    /** Un-snooze one account's message now (re-appears in its list). */
    suspend fun unsnooze(accountId: String, emailId: String) = snoozedDao.delete(accountId, emailId)

    /** The deadline of the active snooze on [accountId]'s [emailId], or null when not snoozed (a
     *  lapsed row counts as not snoozed — same predicate as the list SQL). Keyed per account (#31). */
    suspend fun snoozedUntil(accountId: String, emailId: String): Long? =
        snoozedDao.byId(accountId, emailId)?.until?.takeIf { it > System.currentTimeMillis() }

    /** Live list of snoozed messages with their cached headers, for the "Snoozed" screen. */
    fun snoozedFlow(): Flow<List<SnoozedListRow>> = snoozedDao.observeAll()

    /** The ids an active snooze currently hides, account-qualified, AS A LIVE READING: read once into a
     *  snapshot, the unfolded conversation's members could not follow a snooze ending. */
    fun observeActiveSnoozed(): Flow<Set<EmailKey>> =
        snoozedDao.observeAll()
            .map { rows ->
                val now = System.currentTimeMillis()
                rows.filter { it.until > now }.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }
            }
            // The underlying query joins `emails`, so it re-emits far more often than this answer changes.
            .distinctUntilChanged()

    /** A single cached email of one account by id (e.g. to notify when a snooze fires). */
    suspend fun cachedEmail(accountId: String, emailId: String): Email? =
        emailDao.emailsByIds(accountId, listOf(emailId)).firstOrNull()?.toEmail()

    // ---- folder management ----

    /** Create a folder, then refresh the cache. [name] and [parentId] are both Unicode — an IMAP folder
     *  id is its path DECODED from modified UTF-7 (#101) — so the encoding happens at the socket. */
    suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String? = null) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // IMAP nests by path; the parent's id is its full path.
            val path = if (parentId.isNullOrEmpty()) {
                name.trim()
            } else {
                val delim = if (parentId.contains('/')) "/" else if (parentId.contains('.')) "." else "/"
                "$parentId$delim${name.trim()}"
            }
            imap.createFolder(credentials, path)
        } else {
            val ctx = connect(credentials)
            client.createMailbox(ctx.session, ctx.accountId, name.trim(), role = null, ctx.auth, parentId = parentId)
        }
        refreshMailboxes(credentials)
    }

    /** Rename a folder, then refresh; returns its id. IMAP ids are paths, so both per-folder registries
     *  are re-keyed to follow: the watch flags (#16) and the drawer's fold choices (#185). */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            val delim = imap.listImapFolders(credentials).firstOrNull { it.path == mailboxId }?.delimiter
                ?: if (mailboxId.contains('/')) "/" else if (mailboxId.contains('.')) "." else "/"
            val parent = mailboxId.substringBeforeLast(delim, "")
            val newPath = if (parent.isEmpty()) newName.trim() else "$parent$delim${newName.trim()}"
            imap.renameFolder(credentials, mailboxId, newPath)
            accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)
            accountStore.replaceCollapsedFolder(credentials.id, mailboxId, newPath, delim)
            refreshMailboxes(credentials)
            return newPath
        }
        val ctx = connect(credentials)
        client.renameMailbox(ctx.session, ctx.accountId, mailboxId, newName.trim(), ctx.auth)
        refreshMailboxes(credentials)
        return mailboxId
    }

    /** Delete a folder AND its subfolders, deepest first (servers refuse to destroy a parent that still
     *  has children), plus their cached messages and watch flags. Children match on Unicode paths (#101). */
    suspend fun deleteFolder(credentials: AccountCredentials, mailboxId: String): List<String> {
        val targets: List<String>
        if (credentials.protocol == MailProtocol.IMAP) {
            val folders = imap.listImapFolders(credentials)
            // The folder's OWN delimiter from LIST: a guess breaks on names containing '/' or '.'.
            val delim = folders.firstOrNull { it.path == mailboxId }?.delimiter ?: "/"
            targets = folders.map { it.path }
                .filter { it == mailboxId || it.startsWith(mailboxId + delim) }
                .sortedByDescending { it.length }
                .ifEmpty { listOf(mailboxId) } // already gone server-side: still clean up locally
            targets.forEach { runCatching { imap.deleteFolder(credentials, it) } }
        } else {
            val ctx = connect(credentials)
            val childrenOf = client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).groupBy { it.parentId }
            val ordered = mutableListOf<String>()
            fun visit(id: String) { // post-order: children before their parent
                childrenOf[id].orEmpty().forEach { visit(it.id) }
                ordered += id
            }
            visit(mailboxId)
            targets = ordered
            targets.forEach { client.deleteMailbox(ctx.session, ctx.accountId, it, ctx.auth) }
        }
        targets.forEach {
            accountStore.setFolderWatched(credentials.id, it, watched = false)
            emailDao.replaceMailbox(credentials.id, it, emptyList())
        }
        refreshMailboxes(credentials)
        return targets
    }

    /** Drop folders from the local cache only (a folder delete's undo window); a refresh restores them. */
    suspend fun hideMailboxesLocally(accountId: String, mailboxIds: List<String>) =
        mailboxDao.deleteByIds(accountId, mailboxIds)

    /** Re-fetch ONE account's folder list under a STATED [onlySubscribed] (#174). The value is an
     *  ARGUMENT, not a second reading of the store: `LSUB` only goes out on a listing that asked. */
    suspend fun refreshFolderList(accountId: String, onlySubscribed: Boolean) {
        val credentials = accountStore.credentials(accountId) ?: return
        refreshMailboxes(credentials, onlySubscribed)
    }

    private suspend fun refreshMailboxes(credentials: AccountCredentials) =
        refreshMailboxes(credentials, accountStore.showOnlySubscribedFolders(credentials.id))

    /** [refreshMailboxes] for a caller that already knows what to ask the server about (#174). */
    private suspend fun refreshMailboxes(credentials: AccountCredentials, onlySubscribed: Boolean) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Empty `onPage` and null `cachedPreviewsFor`: this caller caches no message. Still SELECTs the inbox.
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)
            mailboxDao.replaceAll(credentials.id, load.mailboxes)
            return
        }
        val ctx = connect(credentials)
        mailboxDao.replaceAll(credentials.id, client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity(credentials.id) })
    }

    /** The account's archive folder id: by JMAP `archive` role, else by a recognised name, cached into
     *  the context's role map so a bulk archive resolves it once. */
    private suspend fun archiveMailboxId(ctx: Context): String? {
        ctx.rolesToMailboxId["archive"]?.let { return it }
        // THIS account's own folders by name, not the global mailbox cache — otherwise archiving a
        // non-current account's message would target the current account's Archive id, a silent no-op.
        val byName = archiveFolderByName(ctx.mailboxes) ?: return null
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createArchiveFolder(ctx: Context): String {
        // Many servers reject a client-set special-use role: fall back to a plain folder, then an existing one.
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            mbs.firstOrNull { it.role == "archive" }?.id
                ?: archiveFolderByName(mbs)
                ?: throw err
        }
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)
        return id
    }

    /** The account's Trash folder id: by JMAP `trash` role, else by a recognised bin name. JMAP ONLY.
     *  No `all` fallback: "all mail" is not an acceptable bin. */
    private suspend fun trashMailboxId(ctx: Context): String? {
        ctx.rolesToMailboxId["trash"]?.let { return it }
        // THIS account's own folders, not the global mailbox cache — see [archiveMailboxId].
        val byName = trashFolderByName(ctx.mailboxes) ?: return null
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createTrashFolder(ctx: Context): String {
        // Many servers reject a client-set special-use role: plain folder, then an existing bin; else throw.
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Trash", "trash", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Trash", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            mbs.firstOrNull { it.role == "trash" }?.id
                ?: trashFolderByName(mbs)
                ?: throw err
        }
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to id), ctx.mailboxes)
        return id
    }

    /** One message to move back on an Undo: [emailId] returns to [sourceMailboxId] from
     *  [destMailboxId] (null when it was a destroy), which lets the undo reverse the count nudge. */
    data class RestoreTarget(val emailId: String, val sourceMailboxId: String, val destMailboxId: String?)

    /** Undo a batched move/delete for one account, grouped so each (current → source) route is ONE
     *  command (#29). An OPTIMISTIC restore that STICKS: restored ids are marked recently-mutated and
     *  the count nudge is reversed here, so the caller must NOT fire a reconciling refresh. */
    suspend fun restoreAll(credentials: AccountCredentials, targets: List<RestoreTarget>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        // Undoing a delete pulls the message out of the Trash: it must leave the destroy list too (#99).
        unlistFromTrashPurge(credentials.id, targets.map { it.emailId })
        // Protect the restored ids from the next reconcile BEFORE any server call.
        targets.forEach { markRecentlyMutated(credentials.id, it.emailId) }
        val restored = mutableSetOf<String>()
        if (credentials.protocol == MailProtocol.IMAP) {
            // Group by (current folder, source folder, numbering that current folder stated on landing).
            val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) { lastImapMove[it] }
            routes.forEach { route ->
                // The numbering to oppose is the one the forward move landed in, opposed FROM THE FREEZE.
                // The two folders are NAMED IN THE CALL: swapping the `String`s sends inbox mail to the Trash.
                val moved = runCatching {
                    imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, route.frozenUidValidity)
                }.getOrNull() ?: return@forEach
                // Credit only the uids the COPYUID mapping confirms moved back; the rest feed the failure toast.
                restored += route.messages.filter { it.first in moved.uids }.map { it.second }
                // The DESTINATION uids of the move back, i.e. where the mail is NOW: `.values`,
                // never `.keys`, which are the Trash-side uids the mail has just left.
                val newUids = moved.uids.values.toList()
                if (newUids.isNotEmpty()) {
                    runCatching { imap.fetchByUids(credentials, route.sourceFolder, newUids) }
                        .getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }
                        ?.let { fetched ->
                            emailDao.upsertAll(fetched)
                            fetched.forEach { markRecentlyMutated(credentials.id, it.id); recentLocalMoves.mark(credentials.id, it.id) }
                        }
                }
            }
            restored.forEach { lastImapMove.remove(it) }
            restoreCounts(credentials.id, targets.filter { it.emailId in restored })
            return targets.map { it.emailId }.toSet() - restored
        }
        // JMAP: ids are stable across moves, so one Email/set per source folder, then a re-fetch.
        val ctx = connect(credentials)
        targets.groupBy { it.sourceMailboxId }.forEach { (source, group) ->
            val undone = group.associate { it.emailId to it.destMailboxId }
            val result = runCatching {
                // Undo puts them BACK in `source`; what they LEAVE is wherever the original move
                // put them, which is that move's own destination — per id, since one Undo batch can
                // hold messages that went to different folders.
                client.move(ctx.session, ctx.accountId, group.map { it.emailId }, source, ctx.auth, undone)
            }.getOrNull() ?: return@forEach
            val ids = group.map { it.emailId }.filter { it in result.done }
            if (ids.isEmpty()) return@forEach
            restored += ids
            // A self-move into the source folder: the next notifier pass must not announce them as new.
            ids.forEach { recentLocalMoves.mark(credentials.id, it) }
            val fetched = runCatching { client.getEmailsByIds(ctx.session, ctx.accountId, ids, ctx.auth) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, source) })
                fetched.forEach { markRecentlyMutated(credentials.id, it.id) }
            }
        }
        restoreCounts(credentials.id, targets.filter { it.emailId in restored })
        return targets.map { it.emailId }.toSet() - restored
    }

    /** Reverse the forward removal's count nudge; a row with no re-cached seen state counts as read. */
    private suspend fun restoreCounts(accountId: String, targets: List<RestoreTarget>) {
        val seenById = byIdsChunked(targets.map { it.emailId }) { chunk -> emailDao.emailsByIds(accountId, chunk) }
            .associate { it.id to it.seen }
        val moves = targets.mapNotNull { t ->
            val dest = t.destMailboxId ?: return@mapNotNull null // a destroy can't be undone
            (dest to (seenById[t.emailId] ?: true))
                .takeIf { dest != t.sourceMailboxId }
                ?.let { t.sourceMailboxId to it }
        }
        moves.groupBy({ it.first }, { it.second }).forEach { (source, rows) ->
            nudgeCounts(accountId, rows, destMailboxId = source)
        }
    }

    /** Move to Trash and drop from the local list. A delete here NEVER destroys: permanent deletion is
     *  only reachable through [evictAll] + [destroyAll] and its Undo window (#23). The bin is resolved
     *  by role, then by name, then created — never All Mail. [frozen] (see [FrozenNumbering]):
     *  NothingFrozen falls back on the folder's record, `Frozen(null)` is refused (#189). */
    suspend fun delete(
        credentials: AccountCredentials,
        emailId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): String? {
        // Same rule as every other mover (#99): an individual gesture leaves the destroy list.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        // Opt-in: flag the message read on its way out, best-effort BEFORE the move (the id changes).
        if (settings?.markReadOnDelete?.first() == true && emailDao.seenOf(credentials.id, emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val trash = imapRoleFolder(credentials, "trash") ?: run { imap.createFolder(credentials, "Trash"); "Trash" }
            imapTarget(emailId)?.let { (mb, uid) ->
                // Already in the Trash: nothing moves, but the cached row AND its index row go ([deleteAll]).
                if (mb == trash) return@let
                // "delete" is a MOVE, so the folder's recorded numbering is opposed to the SELECT (#99).
                // `Frozen(null)` is refused (#189); `NothingFrozen` is Select(null) and takes the `?:` below,
                // which has to stay.
                val numbering = when (val verdict = numberingToOppose(frozen)) {
                    NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mb, null)
                    is NumberingToOppose.Select -> verdict.stamp
                }
                val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(trash, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, trash, it))
                }
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), trash)
            return trash
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth, sourceMailboxId = mb)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(row), trash)
        advanceEmailState(newState, credentials.id, mb)
        return trash
    }

    /** Move [email] from [source] into [targetMailboxId] of [target], another account (#189): a copy
     *  confirmed on B, then the original trashed on A. The ORDER is [crossAccountMove]; the copy carries
     *  on the read nothing is written on B; refused at the bin the user keeps a duplicate. */
    suspend fun moveToAccount(
        source: AccountCredentials,
        email: Email,
        target: AccountCredentials,
        targetMailboxId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): CrossAccountMove {
        val from = withCachedFallback(email, cachedEmail(source.id, email.id))
        val steps = object : CrossAccountMoveSteps {
            var messageId: String? = null
            override fun checkSourceConfigured() = checkAccountStillConfigured(source.id, accountStore.accounts().map { it.id })
            override fun checkTargetConfigured() = checkAccountStillConfigured(target.id, accountStore.accounts().map { it.id })
            override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }
            override suspend fun writeTarget(bytes: ByteArray): String? {
                if (target.protocol == MailProtocol.IMAP) {
                    val uid = imap.appendBytes(target, targetMailboxId, bytes, imapFlagsOf(from.keywords), internalDateOf(from.receivedAt))
                    return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }
                }
                val ctx = connect(target)
                val blobId = client.uploadBlob(ctx.session, ctx.accountId, bytes, "message/rfc822", ctx.auth).blobId
                return client.importEmail(ctx.session, ctx.accountId, ctx.auth, blobId, setOf(targetMailboxId), jmapKeywordsOf(from.keywords), from.receivedAt)
            }
            override suspend fun findTarget(): String? {
                if (target.protocol != MailProtocol.IMAP) return null
                val uid = imap.findByMessageId(target, targetMailboxId, messageId ?: return null)
                return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }
            }
            override fun markTargetLocalMove(targetId: String) = recentLocalMoves.mark(target.id, targetId)
            override suspend fun trashSource() { delete(source, email.id, frozen) }
            override suspend fun refreshTargetFolder() { refresh(target, targetMailboxId) }
        }
        return crossAccountMove(steps)
    }

    /** A confirmed "Empty trash": the identifier of its frozen destroy list and its size. */
    data class TrashPurgeSnapshot(val purgeId: String, val messageCount: Int)

    /** Freeze what an "Empty trash" may destroy, AT CONFIRMATION TIME (#99): the set is read here once
     *  and [purgeSnapshot] destroys exactly it. MUST be called BEFORE the caller evicts the folder's
     *  cached rows — the offline fallback reads that very cache. Destroying less than asked is the safe
     *  error, so neither branch throws; a cancelled confirmation is the one failure that propagates. */
    suspend fun snapshotTrashPurge(
        credentials: AccountCredentials,
        trashMailboxId: String,
    ): TrashPurgeSnapshot {
        val now = System.currentTimeMillis()
        // Collect anything a killed process or a raced Undo left behind before adding to it.
        purgeSnapshotDao.deleteOlderThan(now - TrashPurge.SNAPSHOT_TTL_MS)
        suspend fun cached() = cachedIds(listOf(credentials.id to trashMailboxId)).map { it.emailId }
        // The numbering the frozen ids belong to (#99), read BEFORE the enumeration: a fallback on the
        // cached ids belongs to the OLD numbering, which a later read would stamp as current.
        val uidValidityBefore =
            if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)
            else null
        var observedUidValidity: Long? = null
        val ids = if (credentials.protocol == MailProtocol.IMAP) {
            TrashPurge.imapSnapshotIds(
                accountId = credentials.id,
                mailboxId = trashMailboxId,
                serverUids = {
                    val snapshot = imap.snapshotUids(credentials, trashMailboxId, TrashPurge.SNAPSHOT_MAX)
                    observedUidValidity = snapshot.uidValidity
                    snapshot.uids
                },
                cached = { cached() },
            )
        } else {
            runCatching {
                val ctx = connect(credentials)
                // Ids-only, paged: the purge photo needs identifiers, not bodies. Chaining an Email/get of up to
                // SNAPSHOT_MAX threw on a server enforcing maxObjectsInGet, dropping the whole thing (#99).
                val collected = mutableListOf<String>()
                while (collected.size < TrashPurge.SNAPSHOT_MAX) {
                    val page = client.queryEmailIds(
                        ctx.session, ctx.accountId, trashMailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                        position = collected.size, calculateTotal = true,
                    )
                    if (page.ids.isEmpty()) break
                    collected += page.ids
                    // Stop on the server's total: a server clamping the limit must not end the walk early.
                    val total = page.total
                    if (total != null && collected.size >= total) break
                }
                collected.take(TrashPurge.SNAPSHOT_MAX)
            }.getOrElseUnlessCancelled {
                // The cache stands in for an unreachable server — but NOT for a cancelled caller: an Undo tapped
                // during this crawl would otherwise leave the withdrawn confirmation standing.
                cached()
            }
        }
        val purgeId = UUID.randomUUID().toString()
        val uidValidity = TrashPurge.snapshotUidValidity(observedUidValidity, uidValidityBefore)
        val rows = TrashPurge.snapshotRows(
            purgeId, credentials.id, trashMailboxId, ids, now, uidValidity = uidValidity,
        )
        rows.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.insertAll(it) }
        return TrashPurgeSnapshot(purgeId, rows.size)
    }

    /** Destroy the snapshot taken by [snapshotTrashPurge] — nothing else, wave by wave. An empty snapshot
     *  destroys NOTHING: no order. On IMAP the order also carries a numbering, checked first (#99): none
     *  drops the snapshot, and one that no longer matches aborts the purge, unretried. */
    suspend fun purgeSnapshot(credentials: AccountCredentials, purgeId: String): Int {
        var destroyed = 0
        // Set by any wave that spared ids: the confirmation evicted every cached Trash row, so no delta reports them.
        var refusedAny = false
        val head = purgeSnapshotDao.head(purgeId, credentials.id)
        val imapPurge = credentials.protocol == MailProtocol.IMAP
        if (imapPurge && head != null && !UidValidity.mayDestroy(head.uidValidity)) {
            purgeSnapshotDao.deleteSnapshot(purgeId)
            return 0
        }
        val expectedUidValidity = if (imapPurge) head?.uidValidity else null
        // The folder the confirmation was about, read off the snapshot itself — not the folder on screen
        // now — so a message rescued out of the Trash by ANOTHER client is left alone (#122).
        val expectedMailboxId = head?.mailboxId
        try {
            for (wave in 1..TrashPurge.MAX_WAVES) {
                // Scoped to (purgeId, accountId): an id means something only inside its own account (#31).
                val ids = purgeSnapshotDao.wave(purgeId, credentials.id, TrashPurge.DESTROY_WAVE)
                if (ids.isEmpty()) break
                val result = destroyAll(credentials, ids, expectedMailboxId, expectedUidValidity)
                // The WHOLE wave leaves the snapshot, spared ids included, or this loop would spin forever.
                purgeSnapshotDao.deleteIds(purgeId, credentials.id, ids)
                destroyed += result.succeeded.size
                if (result.failed.isNotEmpty()) refusedAny = true
            }
        } catch (renumbered: ImapUidValidityChanged) {
            // Not swallowed and not retried: the folder is not the one the user confirmed emptying.
            purgeSnapshotDao.deleteSnapshot(purgeId)
            refreshMailboxes(credentials)
            return destroyed
        } finally {
            // In `finally` because two exits leave early: the abandoned purge, and a propagating failure.
            if (refusedAny) resetSyncState()
        }
        purgeSnapshotDao.deleteSnapshot(purgeId)
        refreshMailboxes(credentials)
        return destroyed
    }

    /** Undo: withdraw the confirmation by erasing that account's Trash destroy list. The snapshot IS
     *  the order, so erasing it is what makes the undo final, even against a purge already started. */
    suspend fun discardTrashPurge(accountId: String, trashMailboxId: String) =
        purgeSnapshotDao.deleteForMailbox(accountId, trashMailboxId)

    /** Drop a snapshot by id (the purge gave up for good). */
    suspend fun discardPurgeSnapshot(purgeId: String) = purgeSnapshotDao.deleteSnapshot(purgeId)

    /** THE CHOKEPOINT for a message leaving a folder: it is withdrawn from every pending "Empty trash"
     *  list of its account (#99). A JMAP id does not change on a move and nothing else removes one id
     *  from a snapshot, so a message rescued from the Trash was destroyed IN ITS NEW FOLDER. */
    private suspend fun unlistFromTrashPurge(accountId: String, emailIds: List<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.unlistEmails(accountId, it) }
    }

    /** Structured search across the account (results are transient, not cached). IMAP sends the same
     *  criteria as `SEARCH` keys across every cached folder. The answer says whether it is whole
     *  ([MailSearchResult.complete]): a full page, a capped scan, a refusal, or a partial folder list. */
    suspend fun search(credentials: AccountCredentials, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty()) return MailSearchResult(emptyList())
        if (credentials.protocol == MailProtocol.IMAP) {
            // Folders come from the cache; an account whose folders were never synced falls back to the inbox.
            val known = searchableFolderIds(mailboxDao.searchOrder(credentials.id))
            val folders = known.ifEmpty { listOfNotNull(mailboxDao.idForRole(credentials.id, "inbox")) }
            // Nothing to search at all — not even an inbox id. That is "we looked nowhere", which must
            // never be reported as the confident "No results" the empty state states as a fact.
            if (folders.isEmpty()) return MailSearchResult(emptyList(), complete = false)
            val hits = imap.search(credentials, folders, query.toImapCriteria(), query.requiresLocalScan(), limit)
            return MailSearchResult(
                emails = hits.messages.map { it.toEmail() },
                complete = imapSearchComplete(known, hits.complete, hits.messages.size, limit),
            )
        }
        val ctx = connect(credentials)
        // Exclude Trash/Junk server-side too, so JMAP matches the IMAP walk. The raw folder list is kept:
        // an empty cache excludes NOTHING, and that answer must not come back wearing a total.
        val cachedFolders = mailboxDao.searchOrder(credentials.id)
        val excluded = excludedSearchFolderIds(cachedFolders)
        val hits = client.searchEmails(ctx.session, ctx.accountId, query, limit, ctx.auth, excluded)
        // A hit can live in several mailboxes: resolve its folder like [fetchThreadMembers], never the
        // server map's arbitrary first key, which could feed a search-row action a Trash/Junk folder.
        val cachedMailbox = byIdsChunked(hits.emails.map { it.id }) { chunk ->
            emailDao.emailsByIds(credentials.id, chunk)
        }.associate { it.id to it.mailboxId }
        val resolved = hits.emails.map { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
            e.copy(mailboxId = mailbox ?: e.mailboxId)
        }
        // A full page, a short get, and a scope an empty folder cache chose: [jmapSearchComplete] holds all three.
        return MailSearchResult(
            resolved,
            complete = jmapSearchComplete(cachedFolders, hits.matchedIds, resolved.size, limit),
        )
    }

    /** Unified search across several accounts, each [search] in parallel. A failing account is skipped,
     *  logged, and makes the answer incomplete rather than passing for the whole result. */
    suspend fun search(accounts: List<AccountCredentials>, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty() || accounts.isEmpty()) return MailSearchResult(emptyList())
        if (accounts.size == 1) {
            val only = accounts.first()
            val one = search(only, query, limit)
            return one.copy(emails = one.emails.map { it.copy(accountId = only.id) })
        }
        val perAccount = coroutineScope {
            accounts.map { credentials ->
                async {
                    runCatching {
                        val hits = search(credentials, query, limit)
                        hits.copy(emails = hits.emails.map { it.copy(accountId = credentials.id) })
                    }.onFailure { error ->
                        // Cancellation is not a failure and must keep propagating — runCatching catches it too.
                        if (error is CancellationException) throw error
                        // The account id is a local UUID; this is the only trace a dropped account leaves.
                        android.util.Log.w(
                            "MailSearch",
                            "account ${credentials.id} dropped from unified search: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    }.getOrDefault(MailSearchResult(emptyList(), complete = false))
                }
            }.awaitAll()
        }
        return mergeAccountSearches(perAccount, limit)
    }

    /** Fetch an email (with body) without marking it read — used to build replies/forwards. */
    suspend fun fetchEmail(credentials: AccountCredentials, emailId: String): Email {
        if (credentials.protocol == MailProtocol.IMAP) {
            return refineAuthClass(credentials.id, openEmailImap(credentials, emailId, markRead = false))
        }
        val ctx = connect(credentials)
        return refineAuthClass(credentials.id, client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth))
    }

    /** All emails in a conversation (lightweight, no body). */
    suspend fun threadEmails(credentials: AccountCredentials, threadId: String): List<Email> {
        val ctx = connect(credentials)
        return client.getThreadEmails(ctx.session, ctx.accountId, threadId, ctx.auth)
    }

    /** Fetch a thread's FULL membership (JMAP Thread/get) so an inline-expanded conversation shows members
     *  outside the folder's sync window. Persists each under its real mailbox; empty on IMAP and failure. */
    suspend fun fetchThreadMembers(
        credentials: AccountCredentials,
        threadId: String,
        viewMailboxIds: List<String> = emptyList(),
    ): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        val emails = runCatching { threadEmails(credentials, threadId) }.getOrNull() ?: return emptyList()
        // Skip any member with no folder rather than invent one. The pick is DETERMINISTIC, never the map's
        // arbitrary first key, which could re-key a correctly-filed row into Trash.
        val cachedMailbox = byIdsChunked(emails.map { it.id }) { chunk ->
            emailDao.emailsByIds(credentials.id, chunk)
        }.associate { it.id to it.mailboxId }
        val entities = emails.mapNotNull { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: e.mailboxId
                ?: viewMailboxIds.firstOrNull { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
                ?: return@mapNotNull null
            e.toEntity(credentials.id, mailbox)
        }
        if (entities.isNotEmpty()) runCatching {
            emailDao.upsertAll(entities)
            // Guard the fresh rows through the reconcile window, or the next full re-query prunes every
            // non-representative member back out, leaving the chip at 1 with its members unreachable.
            entities.forEach { markRecentlyMutated(credentials.id, it.id) }
        }
        // Wire members carry no accountId; stamp it so no downstream key falls back to an unscoped lookup.
        return emails.map { if (it.accountId == null) it.copy(accountId = credentials.id) else it }
    }

    /** A message in several mailboxes: inbox > archive > other > junk > trash, ids sorted as tie-break. */
    private suspend fun rankedMailboxPick(accountId: String, mailboxIds: Set<String>): String? =
        mailboxIds.sorted().minByOrNull { id ->
            when (mailboxDao.roleForId(accountId, id)) {
                "inbox" -> 0
                "archive" -> 1
                "junk" -> 3
                "trash" -> 4
                else -> 2
            }
        }

    /** Cached members of a thread for inline expansion, newest-first. LIVE, and that is the point: the
     *  chip on the collapsed row queries the same table, so both sides read the same write. */
    fun observeThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<Email>> =
        if (mailboxIds.isEmpty()) flowOf(emptyList())
        else if (threadKey.startsWith(ListShape.SENDER_KEY_PREFIX)) {
            // A SENDER group's members: the same table the chip counted, keyed by the address.
            emailDao.cachedSenderEmails(accountId, mailboxIds, threadKey.removePrefix(ListShape.SENDER_KEY_PREFIX))
                .map { rows -> rows.map { it.toEmail() } }
        } else if (threadKey.startsWith(SenderDomain.DOMAIN_KEY_PREFIX)) {
            // A DOMAIN group's members, by the stored domain the chip counted.
            emailDao.cachedDomainEmails(accountId, mailboxIds, threadKey.removePrefix(SenderDomain.DOMAIN_KEY_PREFIX))
                .map { rows -> rows.map { it.toEmail() } }
        } else emailDao.cachedThreadEmails(accountId, mailboxIds, threadKey).map { rows -> rows.map { it.toEmail() } }

    /**
     * Backfill the auth class ("G0 _ Auth") for rows cached before it existed: [AuthClassifier] over each
     * row's subject and preview, in bounded batches, once per row. Returns how many it classified.
     */
    suspend fun indexAuth(batch: Int = 200): Int =
        AuthIndex.backfill(batch, next = { emailDao.unclassifiedAuth(it) }, store = emailDao::setAuthClass)

    /**
     * Backfill the sender domain ("Group by Domain") for rows cached before it existed, in bounded batches,
     * one transaction per batch. Returns how many it indexed.
     */
    suspend fun indexDomains(batch: Int = 500): Int =
        SenderDomain.backfill(batch, next = { emailDao.unindexedDomains(it) }, store = { emailDao.setFromDomains(it) })

    /**
     * Re-classify one message from its BODY, which is more than the preview the row was first judged on.
     * Best effort and quiet: a failure leaves the preview's class in place.
     */
    suspend fun refineAuthClass(accountId: String, email: Email): Email {
        runCatching {
            val html = email.htmlContent()?.takeIf { it.isNotBlank() }
            val textPart = email.textBody.firstOrNull()?.partId?.let { email.bodyValues[it]?.value }
            val text = textPart?.takeIf { it.isNotBlank() } ?: html?.let { htmlToText(it) } ?: return@runCatching
            emailDao.setAuthClass(accountId, email.id, AuthClassifier.classify(email.subject, text, html).value)
        }
        return email
    }

    /** One reading of [observeThreadEmails], for callers that act on a thread once rather than draw it. */
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<Email> =
        observeThreadEmails(accountId, mailboxIds, threadKey).first()

    /** The cached Sent-role folder of each of [accountIds]. No one-shot variant on purpose: two lookups
     *  are two answers, and the reader sees a chip that lies. */
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<Pair<String, String>>> =
        mailboxDao.observeSentMailboxes(accountIds.distinct())
            .map { rows -> rows.map { it.accountId to it.id } }
            .distinctUntilChanged()

    /** Every known (account, folder) → role of [accountIds], reactively (#115). A folder missing from the
     *  map is unknown, not role-less: callers must fall back rather than conclude. */
    fun observeFolderRoles(accountIds: List<String>): Flow<Map<Pair<String, String>, String>> =
        mailboxDao.observeRoles(accountIds.distinct())
            .map(::folderRoleMap)
            .distinctUntilChanged()

    /** Remove [emailIds] from the cache only, decounting each row's OWN folder, chunk by chunk. */
    suspend fun evictAll(accountId: String, emailIds: Collection<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val rows = emailDao.emailsByIds(accountId, chunk)
            deleteFromCacheAndIndex(accountId, chunk)
            adjustCountsForRemoval(rows, destMailboxId = null)
        }
    }

    /** Drop in-memory sync bookkeeping; call after clearing the cache, or sync reads stale state. */
    fun resetSyncState() {
        syncStates.clear()
        context = null
    }

    /** Drop ONE account's sync cursors; the full-query branch sizes itself on the ACCOUNT. */
    fun dropSyncCursors(accountId: String) {
        if (accountId.isBlank()) return
        syncStates.dropAccount(accountId, accountStore.accounts().map { it.id })
    }

    suspend fun disconnectImap(accountId: String) = imap.disconnect(accountId)

    suspend fun hasArchiveFolder(credentials: AccountCredentials): Boolean =
        archiveMailboxId(connect(credentials)) != null

    private class Resolved(
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val mailboxes: List<Mailbox>,
    )

    private suspend fun resolve(credentials: AccountCredentials): Resolved {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)
        val mailboxes = client.getMailboxes(session, accountId, auth)
        return Resolved(session, accountId, auth, mailboxes)
    }

    private fun jmapAccountIdFor(credentials: AccountCredentials, session: JmapSession): String =
        credentials.jmapAccountId
            ?: session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

    /** #31: several mail accounts per login; #129 probes non-primary ones for admission only. */
    private suspend fun reconcileLinkedAccounts(
        credentials: AccountCredentials,
        session: JmapSession,
        auth: JmapAuth,
    ) {
        val mailAccountIds = session.mailAccountIds()
        val loginId = accountStore.account(credentials.id)?.loginKey() ?: credentials.id
        if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return
        val discovered = mailAccountIds.map { DiscoveredMailAccount(it, session.accounts[it]?.name.orEmpty()) }
        val probes = discovered.drop(1).associate { candidate ->
            candidate.jmapAccountId to
                runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()
        }
        val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered, probes) }
            .getOrDefault(emptyList())
        pruned.forEach { prunedId ->
            // App-layer teardown first (notification baselines); each step best-effort so one
            // failure never leaves the rest of a revoked account behind.
            onAccountPruned?.let { hook -> runCatching { hook(prunedId) } }
            bgScope.launch {
                // One runCatching PER delete: a failure in one table must not leave the
                // remaining tables' rows of a revoked account behind.
                runCatching { emailDao.deleteForAccount(prunedId) }
                runCatching { emailFtsDao.clearAccount(prunedId) }
                runCatching { emailBodyDao.deleteForAccount(prunedId) }
                runCatching { mailboxDao.deleteForAccount(prunedId) }
                runCatching { snoozedDao.deleteForAccount(prunedId) }
            }
        }
    }

    /** #31: discover linked sub-accounts for the new login [id]; best-effort, except cancellation. */
    suspend fun reconcileLinkedAccountsAfterAdd(id: String) {
        runCatching {
            val credentials = accountStore.credentials(id) ?: return
            val cached = context
                ?.takeIf { it.credentials.server == credentials.server && it.credentials.username == credentials.username }
                ?: return
            // The cached context's own auth, so the #129 probe costs no token work of its own.
            reconcileLinkedAccounts(credentials, cached.session, cached.auth)
        }.rethrowIfCancelled()
    }

    /** Re-key persisted watched-folder ids and persist the correction (#101). */
    private fun rekeyWatchedFolders(accountId: String, watched: Set<String>): Set<String> {
        if (watched.isEmpty()) return watched
        val corrected = watched.associateWith { decodeMailboxPath(it) }.filter { it.key != it.value }
        corrected.forEach { (stored, path) ->
            runCatching { accountStore.replaceWatchedFolder(accountId, stored, path) }
        }
        return watched.map { corrected[it] ?: it }.toSet()
    }

    /** Refresh the inbox (unless [includeInbox] is false) plus the watched [extraFolderIds] (#16). */
    suspend fun refreshAccountFolders(
        credentials: AccountCredentials,
        extraFolderIds: Set<String>,
        includeInbox: Boolean = true,
        limit: Int = 50,
        onMissing: (String) -> Unit = {},
    ): List<FolderRefresh> {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Refuse to START for an account already gone: a whole background pass would be spent.
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            val (loads, missing) = imap.loadWatchedFolders(
                credentials, rekeyWatchedFolders(credentials.id, extraFolderIds), includeInbox, limit,
            )
            missing.forEach(onMissing)
            // Ask AGAIN: nothing cancels this pass when the account goes, and seconds of network
            // sit between the entry check and this write (#121). One call, outside the loop.
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            // Rebuilt before they are written: `@Upsert` replaces the WHOLE row and this pass
            // reads no body, so raw rows would BLANK the `preview` column (#187).
            loads.forEach { load ->
                val cached = emailDao.cachedPreviews(credentials.id, load.messages.map { it.id }, load.uidValidity)
                emailDao.upsertAll(keepingCachedPreviews(load.messages, cached.associate { it.id to it.preview }))
            }
            // No departures on IMAP: re-reading a folder says what it holds, never what left (#134).
            return loads.map { load ->
                // The baseline is what this page can VOUCH for: nothing when it came back empty
                // without the server stating the folder empty (`watchedBaselineIds`).
                val page = load.messages.map { it.toEmail() }
                FolderRefresh(
                    load.mailboxId, load.name, load.role, page, watchedBaselineIds(load),
                    departedIds = emptyList(),
                    previewSources = load.previewSources,
                )
            }
        }
        val resolved = resolve(credentials)
        val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
            ?: resolved.mailboxes.firstOrNull()
            ?: error("No mailboxes found.")
        val byId = resolved.mailboxes.associateBy { it.id }
        // INVARIANT held five hops away: on JMAP `targets` is never empty, `includeInbox = false`
        // coming only from `MailFetchWorker`, whose `pollExtras` is always false on JMAP.
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraFolderIds.forEach { id ->
                val mailbox = byId[id]
                when {
                    mailbox == null -> onMissing(id)
                    mailbox.id != inbox.id -> add(mailbox)
                }
            }
        }
        val refreshes = targets.map { mailbox ->
            val sync = syncMailbox(
                resolved.session, resolved.accountId, resolved.auth, mailbox.id,
                folderSyncSizing(limit, resolved.session.getBatchSize()), credentials.id,
            )
            // The notification read, not the folder: the whole cache in a worker is a crash loop.
            val read = notifyRead(credentials.id, mailbox.id)
            FolderRefresh(
                mailboxId = mailbox.id,
                name = mailbox.name,
                role = mailbox.role,
                emails = read.emails,
                baselineIds = read.baselineIds,
                // Read AFTER the sync, so a departed id is already out of [emails] (#134).
                departedIds = sync.departedIds,
                // Nothing to convey: a JMAP envelope already carries `preview` in the cache row.
                previewSources = emptyMap(),
            )
        }
        // The JMAP side of the question the IMAP branch asks twice (#121). Narrowed, not closed.
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        // Persist the fetched folder counters, after the row syncs, or the drawer badge undercounts.
        mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
        return refreshes
    }

    // ---- JMAP PushSubscription (issue #17), session-level and context-free ----

    suspend fun createPushSubscription(credentials: AccountCredentials, subscription: PushSubscription): PushSubscription {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.createPushSubscription(session, auth, subscription)
    }

    suspend fun verifyPushSubscription(credentials: AccountCredentials, subscriptionId: String, verificationCode: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.verifyPushSubscription(session, auth, subscriptionId, verificationCode)
    }

    suspend fun renewPushSubscription(credentials: AccountCredentials, subscriptionId: String, expires: String): String {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.updatePushSubscriptionExpires(session, auth, subscriptionId, expires)
    }

    /** Destroy a subscription (sign-out / endpoint rotation); already-gone is success. */
    suspend fun destroyPushSubscription(credentials: AccountCredentials, subscriptionId: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.destroyPushSubscription(session, auth, subscriptionId)
    }

    /** The server's VAPID key (RFC 9749) when advertised; null otherwise (e.g. Stalwart). */
    suspend fun pushVapidKey(credentials: AccountCredentials): String? {
        val auth = jmapAuth(credentials)
        return client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth).vapidPublicKey()
    }

    /** Open a push connection for one account; [onChanged] on mail change, [onClosed] on drop. */
    suspend fun openAccountPush(
        credentials: AccountCredentials,
        onChanged: () -> Unit,
        onClosed: () -> Unit = {},
        // The account ids whose Email state should wake [onChanged] (issue #31); Stalwart pushes
        // nothing for an ACL-shared one. Empty = this credential's own account.
        watchedJmapAccountIds: Set<String> = emptySet(),
    ): Closeable {
        if (credentials.protocol == MailProtocol.IMAP) {
            return imap.openIdle(credentials, onChanged = onChanged, onClosed = onClosed)
        }
        val resolved = resolve(credentials)
        val watched = watchedJmapAccountIds.ifEmpty { setOf(resolved.accountId) }
        return client.openEventSource(
            session = resolved.session,
            auth = resolved.auth,
            onStateChange = { change -> if (watched.any { change.emailChanged(it) }) onChanged() },
            onClosed = onClosed,
        )
    }

    /** SMTP OutgoingMessage from compose fields (IMAP). [requestReceipt] has no default. */
    private fun outgoing(
        credentials: AccountCredentials,
        recipients: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        html: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        requestReceipt: Boolean,
    ): OutgoingMessage {
        val from = formatFromAddress(fromName, fromEmail) ?: credentials.username
        // The read receipt is addressed to this `from` string, so the check below covers it.
        requireSingleLineAddresses(listOf(from) + recipients + cc + bcc)
        return OutgoingMessage(
            from = from,
            to = recipients,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            // ALWAYS an html part: [OutgoingMime] then writes multipart/alternative. A null here
            // would be a text/plain-only message, which this app never sends.
            html = html ?: plainTextToHtml(body),
            inReplyTo = inReplyTo.firstOrNull(),
            references = references.joinToString(" ").ifBlank { null },
            messageId = newMessageId(credentials.username),
            dateMillis = System.currentTimeMillis(),
            requestReceipt = requestReceipt,
        )
    }

    /**
     * Save a draft — on the phone first, then on the server, always (#95). A failed upload keeps
     */
    suspend fun saveDraft(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        /** The `text/html` alternative for [body] (#131), null when plain. Never derived here. */
        html: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        replacesEmailId: String? = null,
        /**
         * The IMAP numbering [replacesEmailId] belongs to, FROZEN by the caller (#99). No
         * default: read at save time it would be the number recorded AFTER a renumbering.
         */
        replacesUidValidity: Long?,
        attachments: List<EmailBodyPart> = emptyList(),
        bodyIsLossy: Boolean = false,
        /**
         * Whether THIS COMPOSER lost the body it hands over — NOT [bodyIsLossy], the licence to
         * destroy a server original (#63); this weighs the WRITE. No default, either way losing.
         */
        composerBodyWasLost: Boolean,
        /**
         * Whether the composer had NOTHING TYPED — its own `body.text`, NOT `[body].isBlank()`:
         * [body] is the PROJECTION, and a bullet on an empty composer projects to `"- "`.
         */
        typedBodyIsBlank: Boolean,
        // The composer's chosen From, so a delegated sub-account's draft is honest (issue #31).
        fromName: String? = null,
        fromEmail: String? = null,
        /** Whether the draft asks for a read receipt (RFC 8098); not put back on reopen (D1). */
        requestReceipt: Boolean = false,
    ): DraftSaveOutcome {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        // Minted or reloaded BEFORE anything is attempted, and by BOTH keys — never keyed on
        // the `Message-ID`, which names one version of the text.
        val target = localDraftTarget(
            replacesEmailId,
            byId = { localDraftDao.byId(credentials.id, it) },
            forServerDraft = { localDraftDao.forServerDraft(credentials.id, it) },
        )
        val existing = target.existing
        // A local id is not a server id: it names nothing the server could be asked to destroy.
        val serverReplaced = serverDraftReplacedBy(replacesEmailId)
        val row = localDraftRow(
            accountId = credentials.id,
            id = target.id,
            messageId = localDraftMessageId(existing) { newMessageId(credentials.username) },
            to = recipients,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            html = html,
            fromName = fromName,
            fromEmail = fromEmail,
            inReplyTo = inReplyTo,
            references = references,
            replacesEmailId = serverReplaced,
            // Read now, while it is still true: this row may wait weeks (#99).
            replacesUidValidity = localDraftUidValidity(serverReplaced) { mailboxId ->
                mailboxUidValidityDao.recorded(credentials.id, mailboxId)
            },
            bodyIsLossy = bodyIsLossy,
            composerBodyWasLost = composerBodyWasLost,
            // Handed DOWN, never re-derived: `body` is a projection (see the parameter's KDoc).
            typedBodyIsBlank = typedBodyIsBlank,
            requestReceipt = requestReceipt,
            existing = existing,
            nowMillis = System.currentTimeMillis(),
        )
        return saveDraftLocalFirst(
            row = row,
            // The row as it was BEFORE this save: `discard` takes the whole DRAFT away.
            existing = existing,
            nowMillis = System.currentTimeMillis(),
            upsert = { localDraftDao.upsert(it) },
            stage = { stageLocalDraftAttachments(localDraftDir(it.id), attachments) },
            discard = { discardLocalDraft(credentials.id, it.id) },
            upload = { saved -> uploadDraft(credentials, saved, attachments) },
            record = { saved, attempt ->
                localDraftDao.recordAttempt(
                    credentials.id, saved.id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,
                    attempt.atMillis, attempt.notBeforeMillis,
                )
            },
            // The draft's own work item. NOT suspending: the composer's coroutine may be dead.
            schedule = { job -> localDraftScheduler?.schedule(job.accountId, job.id, job.delayMillis) },
        )
    }

    suspend fun accountsWithLocalDrafts(): List<String> = localDraftDao.accountsWithDrafts()

    /** One account's local drafts, newest first (#95). No state filter: `STAGING` shows too. */
    fun observeLocalDrafts(accountId: String): Flow<List<LocalDraftEntity>> =
        localDraftDao.observeForAccount(accountId)

    /** Open a local draft in the composer (#95), held [LocalDraftState.EDITING] against the worker. */
    suspend fun takeLocalDraftForEdit(accountId: String, id: String): LocalDraftEdit =
        takeLocalDraftEdit(
            id,
            load = { localDraftDao.byId(accountId, it) },
            setState = { localDraftDao.setState(accountId, id, it) },
        )

    /** Give an edited row back, re-armed; the ONLY way out of [LocalDraftState.EDITING]. */
    suspend fun releaseLocalDraftEdit(accountId: String, id: String) =
        giveLocalDraftEditBack(
            id,
            nowMillis = System.currentTimeMillis(),
            load = { localDraftDao.byId(accountId, it) },
            setState = { localDraftDao.setState(accountId, id, it) },
            schedule = { delay -> localDraftScheduler?.schedule(accountId, id, delay) },
        )

    /**
     * Take one of this phone's own drafts away — the row AND the files staged with it (#95), and
     * only after the other row exists: from a `finally`, it deletes the only copy of the text.
     */
    suspend fun consumeLocalDraft(accountId: String, id: String) = discardLocalDraft(accountId, id)

    /**
     * Bring ONE account's rows back from a process death mid-upload or mid-staging (#95), never
     */
    suspend fun revertUnfinishedLocalDrafts(accountId: String) {
        localDraftDao.revertUploadingToPending(accountId)
        localDraftDao.revertStagedToPendingLossy(accountId)
    }

    /** Every row still owed to the server, backed-off ones included: a startup re-arm needs them. */
    suspend fun localDraftsAwaitingUpload(accountId: String): List<LocalDraftEntity> =
        localDraftDao.pending(accountId, Long.MAX_VALUE)

    /** One deferred attempt at [id] (#95), through [uploadDraft] — the save's own upload path. */
    suspend fun uploadLocalDraft(credentials: AccountCredentials, id: String): LocalDraftUploadResult {
        val now = System.currentTimeMillis()
        return uploadLocalDraftOnce(
            row = localDraftDao.byId(credentials.id, id),
            nowMillis = now,
            markUploading = { localDraftDao.setState(credentials.id, id, LocalDraftState.UPLOADING) },
            // TWO bounds, neither replacing the other: per READ ([LOCAL_DRAFT_UPLOAD_BUDGET_MS]),
            // `appendDraft` naming none; and per ATTEMPT ([LOCAL_DRAFT_ATTEMPT_BUDGET_MS]), the
            // wait for the IMAP mutex having none. The expiry must arrive as an ORDINARY failure.
            upload = { row ->
                withLocalDraftAttemptBudget(LOCAL_DRAFT_ATTEMPT_BUDGET_MS) {
                    withContext(ImapReadBudget(LOCAL_DRAFT_UPLOAD_BUDGET_MS)) {
                        uploadDraft(credentials, row, localDraftUploadAttachments(row.attachmentsJson))
                    }
                }
            },
            discard = { discardLocalDraft(credentials.id, it.id) },
            record = { attempt ->
                localDraftDao.recordAttempt(
                    credentials.id, id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,
                    attempt.atMillis, attempt.notBeforeMillis,
                )
            },
        )
    }

    private fun localDraftDir(id: String): java.io.File =
        java.io.File(localDraftFilesDir, localDraftDirName(id))

    private suspend fun discardLocalDraft(accountId: String, id: String) {
        runCatching { localDraftDao.deleteById(accountId, id) }
        runCatching { localDraftDir(id).deleteRecursively() }
    }

    /** [row] is already durable: a throw here means "not uploaded", not "lost". */
    private suspend fun uploadDraft(
        credentials: AccountCredentials,
        row: LocalDraftEntity,
        attachments: List<EmailBodyPart>,
    ): DraftSaveOutcome {
        val ccTrimmed = localDraftAddresses(row.cc)
        val bccTrimmed = localDraftAddresses(row.bcc)
        val replacesEmailId = row.replacesEmailId
        // Read off the ROW, never the composer or the folder: both guards below outlive the screen
        // that answered them, and `replacesUidValidity` was frozen when the row was minted (#99).
        val requestReceipt = row.requestReceipt
        val replacesUidValidity = row.replacesUidValidity
        if (credentials.protocol == MailProtocol.IMAP) {
            val drafts = mailboxDao.idForRole(credentials.id, "drafts") ?: error("This account has no Drafts folder.")
            val parts = imapDraftAttachments(attachments)
            // The bytes go out at most once per ATTEMPT, and a retry asks first. `false` means
            // this attempt appended NOTHING, so the destroy guard below has no replacement to weigh.
            val wentOut = appendDraftUnlessAlreadyThere(
                row = row,
                alreadyThere = { messageId ->
                    imap.draftIsAlreadyThere(credentials = credentials, draftsMailbox = drafts, messageId = messageId)
                },
                append = {
                    imap.appendDraft(
                        credentials, drafts,
                        // Built from the ROW: the `Message-ID` sent is the one the phone kept.
                        localDraftOutgoing(row, credentials.username, parts, System.currentTimeMillis()),
                    )
                },
            )
            // What the copy about to be destroyed was addressed to, read from the server: a cache
            // row's cc/bcc hold only what some sync put there, so an unknown forbids the destroy.
            // Cc AND Bcc, this APPEND building with `BlindCopies.WRITTEN`; if that changes, back
            // to the Cc alone (#63).
            val addressingIsCarried = draftAddressingIsCarried(
                replacesEmailId = replacesEmailId,
                replacement = draftReplacementAddressing(ccTrimmed, bccTrimmed),
                // NO_BUDGET explicitly: blocking under the save, bounded by the ambient
                // ImapReadBudget under the deferred upload (#95). Expired means UNKNOWN.
                originalAddressing = { id -> imapDraftAddressing(credentials, id, ImapBudget.NO_BUDGET) },
            )
            // The same question for the read receipt, whose box comes back clear on reopen (D1).
            val receiptIsCarried = draftReceiptRequestIsCarried(
                replacesEmailId = replacesEmailId,
                replacement = requestReceipt,
                originalRequested = { id -> imapDraftReceiptRequested(credentials, id) },
            )
            val outcome = finishDraftSave(
                credentials, replacesEmailId,
                faithful = draftReplacementIsFaithful(
                    replacementWentOut = wentOut,
                    attachments.size, parts.size, row.bodyIsLossy,
                    addressingIsCarried = addressingIsCarried,
                    receiptRequestIsCarried = receiptIsCarried,
                ),
                frozenUidValidity = replacesUidValidity,
            )
            // IMAP APPEND returns no stable id to key a row on, so reload Drafts (#63).
            runCatching { refresh(credentials, drafts) }
            return outcome
        }
        val ctx = connect(credentials)
        val recipients = localDraftAddresses(row.toAddresses).map { EmailAddress(email = it) }
        // The composer's chosen identity, then the stored ones, then the signed-in address — never
        // a live Identity/get, which is member-only and refused on a sub-account (issue #31).
        val from = if (!row.fromEmail.isNullOrBlank()) {
            EmailAddress(name = row.fromName, email = row.fromEmail)
        } else {
            val stored = accountStore.identities(credentials.id).firstOrNull()
            EmailAddress(name = stored?.name, email = stored?.email ?: credentials.username)
        }
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: error("This account has no Drafts folder.")
        val blobs = jmapDraftAttachments(credentials, attachments)
        val inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val references = row.references?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val savedId = client.saveDraft(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            from = from,
            to = recipients,
            cc = ccTrimmed.map { EmailAddress(email = it) },
            bcc = bccTrimmed.map { EmailAddress(email = it) },
            subject = row.subject,
            textBody = row.textBody,
            // Read off the ROW: the styling the save stored (#131). Null is wrapped from the text
            // INSIDE saveDraft - a JMAP draft always carries an htmlBody part.
            htmlBody = row.htmlBody,
            draftMailboxId = draftsId,
            inReplyTo = inReplyTo,
            references = references,
            attachments = blobs,
            requestReceipt = row.requestReceipt,
        )
        // Optimistically cache the just-saved draft, keyed on the server-returned id (#63).
        // Best-effort: a throw would be caught as KEPT_ON_DEVICE and uploaded a second time.
        if (savedId != null) {
            runCatching {
                cacheSavedDraft(
                    credentials, savedId, draftsId, from, recipients,
                    cc = ccTrimmed.map { EmailAddress(email = it) },
                    bcc = bccTrimmed.map { EmailAddress(email = it) },
                    row.subject, row.textBody,
                    inReplyTo, references, hasAttachment = blobs.isNotEmpty(),
                )
            }
        }
        // A read on JMAP too: Email/get returns recipients but not the receipt checkbox (D1).
        val receiptIsCarried = draftReceiptRequestIsCarried(
            replacesEmailId = replacesEmailId,
            replacement = requestReceipt,
            originalRequested = { id -> jmapDraftReceiptRequested(ctx, id) },
        )
        // Evicts the replaced original AFTER the new row is in, so the list never flickers empty.
        return finishDraftSave(
            credentials, replacesEmailId,
            faithful = draftReplacementIsFaithful(
                // The server's ANSWER, never the request: `saveDraft` answers NULL when the
                // response names neither a created id nor a refusal, and an unknown never destroys.
                replacementWentOut = savedId != null,
                attachments.size, blobs.size, row.bodyIsLossy,
                // True, and not a read: Email/get returns to/cc/bcc, so the composer's fields ARE
                // the original's addressing. A failed reopen carries `editingDraftLossy` instead.
                addressingIsCarried = true,
                receiptRequestIsCarried = receiptIsCarried,
            ),
            // Null on JMAP, where nothing is numbered — carried so neither branch reads one itself.
            frozenUidValidity = replacesUidValidity,
        )
    }

    /** An IMAP draft's cc+bcc as the server holds it; `null` is the unknown that keeps it. */
    private suspend fun imapDraftAddressing(credentials: AccountCredentials, emailId: String, budgetMs: Int): Set<String>? {
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return imap.ccAndBccOf(credentials, cached.mailboxId, uid, budgetMs)
    }

    /** Whether the IMAP draft asks for a receipt, as the SERVER holds it; `null` keeps the original. */
    private suspend fun imapDraftReceiptRequested(credentials: AccountCredentials, emailId: String): Boolean? {
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        val raw = imap.fetchSource(credentials, cached.mailboxId, uid)
        return receiptRequestedInSource(raw)
    }

    /** The same for a JMAP draft; a server refusing the header property answers unknown, not "no". */
    private suspend fun jmapDraftReceiptRequested(ctx: Context, emailId: String): Boolean? {
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        return receiptRequestedInProperty(
            header = email.dispositionNotificationTo,
            serverRefusesProperty = client.refusesReceiptHeader(ctx.session),
        )
    }

    /** Whether the send provably carries the addressing of the draft it destroys; unknown forbids. */
    private suspend fun sendCarriesDraftAddressing(
        credentials: AccountCredentials,
        replacesEmailId: String,
        cc: List<String>,
        bcc: List<String>,
    ): Boolean {
        if (credentials.protocol != MailProtocol.IMAP) return true
        return draftAddressingIsCarried(
            replacesEmailId = replacesEmailId,
            replacement = draftReplacementAddressing(cc, bcc),
            // Bounded, unlike the save's twin read: this runs BEFORE the outbox row is inserted.
            originalAddressing = { id -> imapDraftAddressing(credentials, id, SEND_PROOF_BUDGET_MS) },
        )
    }

    private suspend fun cacheSavedDraft(
        credentials: AccountCredentials,
        emailId: String,
        draftMailboxId: String,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        inReplyTo: List<String>,
        references: List<String>,
        hasAttachment: Boolean,
    ) {
        emailDao.upsertAll(
            listOf(
                savedDraftRow(
                    accountId = credentials.id,
                    emailId = emailId,
                    draftMailboxId = draftMailboxId,
                    from = from,
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    inReplyTo = inReplyTo,
                    references = references,
                    hasAttachment = hasAttachment,
                ),
            ),
        )
    }

    /** The edited original is destroyed ONLY when [faithful] (#63). */
    private suspend fun finishDraftSave(
        credentials: AccountCredentials,
        replacesEmailId: String?,
        faithful: Boolean,
        frozenUidValidity: Long?,
    ): DraftSaveOutcome {
        if (replacesEmailId == null) return DraftSaveOutcome.SAVED
        if (!faithful) return DraftSaveOutcome.ORIGINAL_KEPT
        runCatching { destroyDraft(credentials, replacesEmailId, frozenUidValidity) }
        return DraftSaveOutcome.SAVED
    }

    /** Staged attachment files as MIME parts; an unreadable part is DROPPED, keeping the original. */
    private fun imapDraftAttachments(attachments: List<EmailBodyPart>): List<OutgoingAttachment> =
        attachments.mapNotNull { part ->
            val path = part.partId ?: return@mapNotNull null
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
            val inline = part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()
            OutgoingAttachment(
                part.name ?: "attachment", part.type ?: "application/octet-stream", bytes,
                cid = part.cid, inline = inline,
            )
        }

    /** Blob-backed parts for a JMAP draft; a part that is neither is DROPPED, keeping the original. */
    private suspend fun jmapDraftAttachments(
        credentials: AccountCredentials,
        attachments: List<EmailBodyPart>,
    ): List<EmailBodyPart> = attachments.mapNotNull { part ->
        if (part.blobId != null) return@mapNotNull part
        val path = part.partId ?: return@mapNotNull null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
        runCatching {
            uploadAttachment(
                credentials, bytes, part.type, part.name,
                part.disposition ?: "attachment", part.cid,
            )
        }.getOrNull()
    }

    /**
     * Was the draft [emailId] PROVABLY addressed to nobody but the people the composer is showing?
     */
    suspend fun emptiedDraftAddressingIsProvenEmpty(
        credentials: AccountCredentials,
        emailId: String,
    ): Boolean {
        if (credentials.protocol == MailProtocol.IMAP) {
            // The server's own envelope: an EMPTY replacement can only include an empty original.
            return draftAddressingIsCarried(
                replacesEmailId = emailId,
                replacement = emptySet(),
                originalAddressing = { id -> imapDraftAddressing(credentials, id, EMPTIED_DRAFT_PROOF_BUDGET_MS) },
            )
        }
        // JMAP proves it without a read: Email/get hands back the server's to/cc/bcc, so the
        // emptied fields ARE the original's addressing. A failed reopen arrives `editingDraftLossy`.
        return true
    }

    /**
     * Discard a draft the user emptied while editing it (#69): the same permanent server+cache
     */
    suspend fun discardDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) {
        destroyDraft(credentials, emailId, frozenUidValidity)
    }

    /**
     * [frozenUidValidity] is the numbering the CALLER froze when it read [emailId] — nothing here
     * reads one; even a local `val` shadowing it hands the guard a POST-renumbering number.
     */
    private suspend fun destroyDraft(
        credentials: AccountCredentials,
        emailId: String,
        frozenUidValidity: Long?,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Account-scoped (issue #31): the draft's folder must be resolved within this account.
            val folder = emailDao.mailboxOf(credentials.id, emailId)
                ?: mailboxDao.idForRole(credentials.id, "drafts")
                ?: return
            // An EXPUNGE names messages by UID, and a UID means a message only inside ONE
            // numbering (#99). Nothing to oppose licenses nothing: the draft stays.
            val destroyable = UidValidity.destroyableUnderNumbering(listOf(emailId), frozenUidValidity)
            try {
                imapDestroyGroup(credentials, folder, destroyable, mutableSetOf(), mutableSetOf(), frozenUidValidity)
            } catch (unconfirmed: ImapNumberingUnconfirmed) {
                // Refused, not failed: nothing left the client. ONLY this type.
            } catch (renumbered: ImapUidValidityChanged) {
                // The refusal a FROZEN numbering makes reachable, silent as above. `onMailbox` has
                // invalidated the folder, so the draft's dead LIST ROW is re-read best-effort.
                runCatching { refresh(credentials, folder) }
            }
            return
        }
        // The JMAP half of the same trade (#122): a JMAP id still names the draft after another
        // client has moved it OUT of Drafts. Through `destroyAll`, which asks the server where the
        // id is and spares what is not in that folder ALONE. The folder is READ here, not frozen.
        // REFUSED IS NOT FAILED, and it is silent; a transport failure still throws.
        destroyAll(credentials, listOf(emailId), draftMailboxOf(credentials, emailId))
    }

    // ---- outbox (persistent send queue) ----

    /** The single send path: queue in the outbox and arm the worker; [holdMs] > 0 HELDs it. */
    suspend fun enqueueSend(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        holdMs: Long = 0,
        pgpMode: PgpMode? = null,
        /** A complete MIME entity replacing the body byte for byte. NOT conditional on [pgpMode]. */
        prebuiltEntity: String? = null,
        draftEmailId: String? = null,
        /**
         * The IMAP numbering [draftEmailId] was read under, frozen by whoever read it (#99). No
         * default: the destroy happens days later, and `null` there means "destroy nothing".
         */
        draftUidValidity: Long?,
        /** Whether the composer could NOT reproduce the draft (#63). An unknown answers `true`. */
        bodyIsLossy: Boolean = true,
        /** Ask for a read receipt (RFC 8098), per message; persisted so the worker still asks. */
        requestReceipt: Boolean = false,
        /** Teach these addresses to the contact suggestions. False for a receipt — [recipientsToLearn]. */
        learnRecipients: Boolean = true,
    ): Long {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        recipientsToLearn(recipients + ccTrimmed + bccTrimmed, learnRecipients)
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { rememberRecipients(it) } }

        // performSend destroys whatever draft id this row carries, so the id is written ONLY when
        // the outgoing message provably reproduces the draft it replaces.
        val replaceableDraftId =
            replaceableDraftIdOrNull(draftEmailId, attachments.size, bodyIsLossy) { id ->
                sendCarriesDraftAddressing(credentials, id, ccTrimmed, bccTrimmed)
            }

        // An encrypted payload replaces the plaintext at rest, and both halves of that are
        // [outboxPayload]'s: headers on the row, ciphertext in a file, attachments in the entity.
        val payload = outboxPayload(prebuiltEntity, pgpMode)
        val encrypted = payload.redactBody
        val now = System.currentTimeMillis()
        val held = holdMs > 0
        val id = outboxDao.insert(
            OutboxEntity(
                accountId = credentials.id,
                recipients = recipients.joinToString(","),
                cc = ccTrimmed.joinToString(",").ifBlank { null },
                bcc = bccTrimmed.joinToString(",").ifBlank { null },
                subject = subject,
                textBody = if (encrypted) "" else body,
                // HTML always (a text-only caller gets its text wrapped, see [plainTextToHtml]): a
                // row without an html part is how a quick reply once left as plain text.
                htmlBody = if (encrypted) null else htmlBody ?: plainTextToHtml(body),
                fromName = fromName,
                fromEmail = fromEmail,
                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                references = references.joinToString(" ").ifBlank { null },
                attachmentsJson = "[]",
                createdAtMillis = now,
                notBeforeMillis = now + holdMs,
                state = if (held) OutboxState.HELD else OutboxState.QUEUED,
                pgpMode = outboxPgpModeName(pgpMode),
                draftEmailId = replaceableDraftId,
                draftUidValidity = draftUidValidity,
                requestReceipt = requestReceipt,
            ),
        )
        // Staging runs AFTER the insert (it needs the id), and a throw must roll the row back: an
        // orphan with attachmentsJson "[]" is re-armed at startup and sent amputated (#70).
        stageOrRollback(rollback = { deleteOutbox(id) }) {
            if (payload.entityFile != null) {
                val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
                val entityFile = java.io.File(dir, PREBUILT_ENTITY_FILE).apply { writeText(payload.entityFile) }
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(pgpEntityPath = entityFile.absolutePath)) }
            } else {
                val durable = persistAttachments(id, attachments)
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(attachmentsJson = OutboxAttachments.encode(durable))) }
            }
        }
        // The row is durable and its payload staged, so the send IS queued — and arming is not
        // queueing. A scheduler that throws must not be relayed, or the caller hears "nothing was
        // queued" about a message in the Outbox and a quick reply goes out twice. The failure goes
        // ON the row as OutboxLogic.NOT_ARMED — lastError only, never its state.
        return armOrNote(id, holdMs, outboxScheduler) { note ->
            android.util.Log.w("MailRepository", "outbox $id: $note")
            outboxDao.byId(id)?.let { outboxDao.update(it.copy(lastError = OutboxLogic.NOT_ARMED)) }
        }
    }

    private fun persistAttachments(id: Long, attachments: List<EmailBodyPart>): List<OutboxAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
        return attachments.mapNotNull { part ->
            when {
                part.blobId != null -> OutboxAttachment(
                    kind = OutboxAttachments.KIND_JMAP_BLOB,
                    blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                    cid = part.cid, disposition = part.disposition,
                )
                part.partId != null -> {
                    val sourcePath = part.partId!!
                    // Refuse rather than drop an unreadable attachment: it would send amputated.
                    val bytes = runCatching { java.io.File(sourcePath).readBytes() }.getOrNull()
                        ?: error("Couldn't read the attachment ${part.name ?: sourcePath} to queue it.")
                    val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dest = java.io.File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                    OutboxAttachment(
                        kind = OutboxAttachments.KIND_IMAP_FILE,
                        path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                        cid = part.cid, disposition = part.disposition,
                    )
                }
                else -> null
            }
        }
    }

    /** Outbox rows for the list, through [OutboxLogic.isWaitingInOutbox] so the counts agree. */
    fun outboxFlow(): Flow<List<OutboxEntity>> =
        outboxDao.observeAll().map { rows -> rows.filter { OutboxLogic.isWaitingInOutbox(it.state) } }

    /** Failed items plus those waiting past their undo window — see [OutboxLogic.activeCount]. */
    fun outboxActiveCount(): Flow<Int> = OutboxLogic.badgeCount(outboxDao.observeBadgeItems())

    /** How many messages are in the Outbox right now — no grace period, unlike [outboxActiveCount]. */
    fun outboxQueuedCount(): Flow<Int> =
        outboxDao.observeBadgeItems().map { OutboxLogic.queuedCount(it) }

    suspend fun outboxItem(id: Long): OutboxEntity? = outboxDao.byId(id)

    suspend fun unfinishedOutbox(): List<OutboxEntity> = outboxDao.unfinished()

    suspend fun updateOutboxState(id: Long, state: OutboxState, attemptCount: Int, lastError: String?) {
        outboxDao.updateState(id, state, attemptCount, lastError, System.currentTimeMillis())
    }

    suspend fun deleteOutbox(id: Long) {
        outboxDao.delete(id)
        runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }
    }

    /** Re-queue for an immediate retry; `notBeforeMillis` is also the anchor the badge counts from. */
    suspend fun retryOutbox(id: Long) {
        val item = outboxDao.byId(id) ?: return
        val now = System.currentTimeMillis()
        outboxDao.update(item.copy(state = OutboxState.QUEUED, attemptCount = 0, lastError = null, notBeforeMillis = now))
        outboxScheduler?.schedule(id, 0)
    }

    /** Fields to reopen a queued/failed item in compose (#70); the row is NOT deleted ([outboxId]). */
    data class OutboxDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        /** The queued row's `text/html` part (#131). No default; it is the SEND's html. */
        val htmlBody: String?,
        val fromAccountId: String?,
        val fromEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** The PGP mode the item was queued with (#35/#70): a signed/encrypted item reopens as such. */
        val pgpMode: String?,
        /** The saved draft the item was edited from (#63), kept so re-sending still replaces it. */
        val draftEmailId: String? = null,
        /** The numbering [draftEmailId] was frozen under (#99). No default: the row is its home. */
        val draftUidValidity: Long?,
        val outboxId: Long,
        /** Whether the queued item asks for a read receipt. No default: the row is its only home. */
        val requestReceipt: Boolean,
    )

    /** Mark the row EDITING (#70); it stays, so a process death costs the edit and not the mail. */
    suspend fun takeOutboxForEdit(id: Long, stagingDir: java.io.File): OutboxDraft? {
        val item = outboxDao.byId(id) ?: return null
        // State guard at the choke point (#70): EDITING on a SENDING row races updateOutboxState.
        val unreplayable = OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)
        if (!OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) return null
        // Stage attachments BEFORE changing state: `OutboxEdit.take` throws on an unreadable one,
        // and a throw must leave the row still QUEUED and deliverable.
        val attachments = OutboxEdit.take(OutboxAttachments.decode(item.attachmentsJson), stagingDir)
        val draft = outboxDraftOf(item, attachments)
        outboxDao.setState(id, OutboxState.EDITING)
        return draft
    }

    /** Give a message taken out with [takeOutboxForEdit] back to the queue unchanged (#70). */
    suspend fun releaseOutboxEdit(id: Long) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        // A FAILED item reopened then closed untouched must NOT restart on its own (#70).
        val next = OutboxLogic.stateAfterEdit(row.attemptCount)
        outboxDao.setState(id, next)
        if (next != OutboxState.QUEUED) return
        // Held or scheduled items keep whatever is left of their wait; everything else goes now.
        outboxScheduler?.schedule(id, (row.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /**
     * Park a row taken out with [takeOutboxForEdit] in [state] instead of giving it back to the
     */
    suspend fun parkOutboxEdit(id: Long, state: OutboxState) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        outboxDao.setState(id, state)
    }

    /**
     * Park a row whose edit was INTERRUPTED: the composer closed over a body it had lost, on the
     */
    suspend fun parkInterruptedOutboxEdit(id: Long) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        if (row.attemptCount >= OutboxLogic.MAX_ATTEMPTS) outboxDao.setState(id, OutboxState.FAILED)
        else outboxDao.parkEditingAsInterrupted(id, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)
    }

    /**
     * Startup recovery (#70): PARK every row stranded in EDITING by a process death — FAILED at the
     */
    suspend fun parkInterruptedOutboxEdits() {
        outboxDao.revertEditingExhaustedToFailed(OutboxLogic.MAX_ATTEMPTS)
        outboxDao.parkAllEditingAsInterrupted(OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)
    }

    /** Actually deliver one outbox item (no queue indirection); exceptions propagate to the worker. */
    suspend fun performSend(credentials: AccountCredentials, item: OutboxEntity) {
        performDelivery(credentials, item)
        // Delivered: the draft this item replaces (#63) leaves Drafts, best-effort and AFTER
        // success. The numbering is CARRIED BY THE ROW, never looked up here (#99); on JMAP it is
        // null, that branch opposing the FOLDER instead (#122).
        item.draftEmailId?.let {
            runCatching { destroyDraft(credentials, it, item.draftUidValidity) }
        }
    }

    private suspend fun performDelivery(credentials: AccountCredentials, item: OutboxEntity) {
        val to = item.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val subject = item.subject
        val body = item.textBody
        val htmlBody = item.htmlBody
        val fromName = item.fromName
        val fromEmail = item.fromEmail
        val inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val ccTrimmed = item.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val bccTrimmed = item.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val stored = OutboxAttachments.decode(item.attachmentsJson)

        // A pre-built entity (PGP/MIME, or an RFC 8098 receipt), read ONCE above the fork: what
        // sends the message verbatim is the entity, never the PGP mode.
        val prebuiltEntity = prebuiltEntityAt(item.pgpEntityPath) { path ->
            runCatching { java.io.File(path).readText() }.getOrNull()
        }

        val sendingAccount = accountStore.account(credentials.id)

        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            val outAttachments = stored.mapNotNull { a ->
                val path = a.path ?: return@mapNotNull null
                // Refuse rather than deliver amputated: the item stays in the outbox with its error.
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                    ?: error("Couldn't read the attachment ${a.name ?: path} to send it.")
                val inline = a.disposition.equals("inline", ignoreCase = true) && !a.cid.isNullOrBlank()
                OutgoingAttachment(
                    a.name ?: "attachment", a.type ?: "application/octet-stream", bytes,
                    cid = a.cid, inline = inline,
                )
            }
            val message = outgoing(
                credentials, recipients, subject, body, inReplyTo, references, htmlBody,
                fromName, fromEmail, ccTrimmed, bccTrimmed,
                requestReceipt = item.requestReceipt,
            ).copy(
                attachments = if (prebuiltEntity != null) emptyList() else outAttachments,
                prebuiltEntity = prebuiltEntity,
                autocryptHeader = autocryptHeaderValue(
                    sendingAccount,
                    fromEmail?.takeIf { it.isNotBlank() } ?: credentials.username,
                ),
            )
            // The Sent copy is a SECOND write, and some servers file it themselves;
            // sentMailboxToUpload decides which folder (if any) reaches the APPEND.
            imap.send(
                credentials,
                message,
                sentMailboxToUpload(
                    accountStore.uploadSentCopy(credentials.id),
                    mailboxDao.idForRole(credentials.id, "sent"),
                ),
            )
            return
        }
        val attachments = stored.map { a ->
            EmailBodyPart(
                blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                disposition = a.disposition ?: "attachment", cid = a.cid,
            )
        }
        val ctx = connect(credentials)
        val recipients = to.map { EmailAddress(email = it) }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccAddrs = ccTrimmed.map { EmailAddress(email = it) }
        val bccAddrs = bccTrimmed.map { EmailAddress(email = it) }

        // Delegated sub-account (issue #31): member-only methods are refused on the shared account,
        // but a submission created on the LOGIN's account with the delegated address in From is
        // accepted; the Sent copy is re-filed into the sub-account's own Sent below.
        val submissionAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        val onBehalf = submissionAccountId != ctx.accountId

        val serverIdentities = client.getIdentities(ctx.session, submissionAccountId, ctx.auth)
        // Which identity to submit under, and whether the envelope must be named (issue #172), is
        // [SubmissionIdentity]'s rule; null means no server identity at all.
        val choice = SubmissionIdentity.choose(fromEmail, serverIdentities, onBehalf)
            ?: error("This account has no sending identity.")
        val identity = choice.identity
        // Trimmed like the envelope below: untrimmed, From: " a@b " faces an `a@b` MAIL FROM.
        val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail.trim())
        else EmailAddress(name = identity.name, email = identity.email)
        // rcptTo carries to + cc + bcc: RFC 8621 §7.5 makes both envelope fields mandatory.
        val envelope = choice.envelopeMailFrom?.let {
            SubmissionEnvelope(
                mailFrom = it,
                rcptTo = (recipients + ccAddrs + bccAddrs).map { a -> a.email },
            )
        }
        // Mailbox ids are per-account: an on-behalf send files in the login's account, so its
        // roles must come from there.
        val submissionRoles = if (onBehalf) {
            client.getMailboxes(ctx.session, submissionAccountId, ctx.auth)
                .mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
        } else {
            ctx.rolesToMailboxId
        }
        val draftsId = submissionRoles["drafts"]
            ?: submissionRoles["sent"]
            ?: error("This account has no Drafts or Sent folder.")
        val sentId = submissionRoles["sent"] ?: draftsId

        val sentEmailId = if (prebuiltEntity != null) {
            // A pre-built entity must reach the wire byte-exact — the bytes the signature covers
            // do not survive re-encoding from Email/set fields.
            val raw = OutgoingMime.build(
                outgoing(
                    credentials, to, subject, "", inReplyTo, references, null,
                    from.name ?: fromName, from.email, ccTrimmed, bccTrimmed,
                    requestReceipt = item.requestReceipt,
                ).copy(
                    prebuiltEntity = prebuiltEntity,
                    autocryptHeader = autocryptHeaderValue(sendingAccount, from.email),
                ),
            )
            client.importAndSendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                rawMessage = raw.toByteArray(Charsets.UTF_8),
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
                envelope = envelope,
            )
        } else {
            client.sendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                from = from,
                to = recipients,
                cc = ccAddrs,
                bcc = bccAddrs,
                subject = subject,
                textBody = body,
                htmlBody = htmlBody,
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
                inReplyTo = inReplyTo,
                references = references,
                attachments = attachments,
                requestReceipt = item.requestReceipt,
                autocryptHeader = autocryptHeaderValue(sendingAccount, from.email),
                envelope = envelope,
            )
        }

        // On-behalf: move the Sent copy into the sub-account's own Sent, best-effort AFTER the
        // send stands — a failed copy leaves the message in the login's Sent.
        val ownSentId = ctx.rolesToMailboxId["sent"]
        if (onBehalf && sentEmailId != null && ownSentId != null) {
            runCatching {
                client.copyEmailToAccount(
                    session = ctx.session,
                    auth = ctx.auth,
                    fromAccountId = submissionAccountId,
                    toAccountId = ctx.accountId,
                    emailId = sentEmailId,
                    mailboxId = ownSentId,
                )
            }
        }
        // Pull the Sent copy into the cache at once (best-effort): the list counts a thread from
        // the cache, so an un-cached reply leaves the conversation looking single.
        runCatching {
            val sizing = folderSyncSizing(PAGE_SIZE, ctx.session.getBatchSize())
            if (onBehalf) {
                ownSentId?.let { syncMailbox(ctx.session, ctx.accountId, ctx.auth, it, sizing, credentials.id) }
            } else {
                syncMailbox(ctx.session, ctx.accountId, ctx.auth, sentId, sizing, credentials.id)
            }
        }
    }

    /** iTIP REPLY through the outbox; the decisive signal is the in-body METHOD:REPLY line. */
    suspend fun sendCalendarReply(
        credentials: AccountCredentials,
        organizerEmail: String,
        subject: String,
        textBody: String,
        replyIcs: ByteArray,
    ) {
        require(organizerEmail.isNotBlank()) { "The invite has no organizer to reply to." }
        val attachment = if (credentials.protocol == MailProtocol.IMAP) {
            // No blob store for IMAP — stage the bytes as a temp file enqueueSend copies durably.
            val file = java.io.File.createTempFile("sterna-reply", ".ics").apply { writeBytes(replyIcs) }
            EmailBodyPart(
                partId = file.absolutePath,
                type = "text/calendar; method=REPLY; charset=utf-8",
                size = replyIcs.size.toLong(),
                name = "invite.ics",
                disposition = "attachment",
            )
        } else {
            uploadAttachment(credentials, replyIcs, "text/calendar", "invite.ics")
        }
        // The account's own identity: a sub-account's reply submits through its login (issue #31).
        val identity = accountStore.identities(credentials.id).firstOrNull()
        enqueueSend(
            credentials = credentials,
            to = listOf(organizerEmail),
            subject = subject,
            body = textBody,
            attachments = listOf(attachment),
            fromName = identity?.name,
            fromEmail = identity?.email,
            // An invitation reply replaces no draft: nothing to freeze, nothing to destroy.
            draftUidValidity = null,
        )
    }

    // ---- unsubscribe (RFC 2369 / RFC 8058) ----

    private val unsubscribeClient by lazy { UnsubscribeClient() }

    /**
     * RFC 8058 one-click unsubscribe: one POST, no `Authorization` header — [UnsubscribeClient]
     * exists so this cannot inherit the account's credentials.
     */
    suspend fun unsubscribeOneClick(url: String, isOnline: () -> Boolean): UnsubscribeResult =
        unsubscribeClient.oneClick(url, isOnline)

    /** Queue the `mailto:` unsubscribe through the outbox; [UNSUBSCRIBE_SUBJECT] is untranslated. */
    suspend fun sendUnsubscribeMail(
        credentials: AccountCredentials,
        mailto: MailtoUnsubscribe,
    ) {
        val identity = accountStore.identities(credentials.id).firstOrNull()
        val mail = unsubscribeMail(mailto, identity?.name, identity?.email)
        enqueueSend(
            credentials = credentials,
            to = mail.to,
            subject = mail.subject,
            body = mail.body,
            fromName = mail.fromName,
            fromEmail = mail.fromEmail,
            draftUidValidity = null,
        )
    }

    /**
     * Queue the RFC 8098 read receipt answering [receiptTo]. Nothing calls this yet: a receipt
     * tells a stranger when their mail was opened, never sent without the reader saying so.
     */
    suspend fun sendReadReceipt(
        credentials: AccountCredentials,
        receiptTo: List<String>,
        originalSubject: String?,
        originalMessageId: String?,
        deliveredTo: String? = null,
    ): ReadReceiptPreview? {
        val identity = receiptIdentity(accountStore.identities(credentials.id), deliveredTo)
        val mail = readReceiptMail(
            receiptTo = receiptTo,
            originalSubject = originalSubject,
            originalMessageId = originalMessageId,
            identityName = identity?.name,
            identityEmail = identity?.email,
            deliveredTo = deliveredTo,
            accountAddress = credentials.username,
            // Drawn HERE, never inside the builder: a random number makes bytes no test can pin.
            boundary = "----sterna_mdn_${java.util.UUID.randomUUID().toString().replace("-", "")}",
        ) ?: return null
        enqueueSend(
            credentials = credentials,
            to = mail.to,
            subject = mail.subject,
            body = mail.body,
            fromName = mail.fromName,
            fromEmail = mail.fromEmail,
            prebuiltEntity = mail.prebuiltEntity,
            learnRecipients = mail.learnRecipients,
            draftUidValidity = null,
        )
        return mail.preview()
    }

    // ---- scheduled send ----

    suspend fun insertScheduledSend(entity: ScheduledSendEntity): Long = scheduledSendDao.insert(entity)

    suspend fun scheduledSend(id: Long): ScheduledSendEntity? = scheduledSendDao.byId(id)

    suspend fun deleteScheduledSend(id: Long) = scheduledSendDao.delete(id)

    suspend fun scheduledSends(): List<ScheduledSendEntity> = scheduledSendDao.all()

    fun scheduledSendsFlow(): Flow<List<ScheduledSendEntity>> = scheduledSendDao.observeAll()

    /** The bytes of an attachment part; [maxBytes] is the ceiling this caller accepts. */
    suspend fun downloadAttachment(
        credentials: AccountCredentials,
        part: EmailBodyPart,
        emailId: String,
        maxBytes: Long = DownloadLimits.ATTACHMENT_MAX_BYTES,
    ): ByteArray {
        if (part.partId?.startsWith("pgp:") == true) return pgpAttachmentBytes(credentials.id, emailId, part)
        // The size is announced by the message (JMAP) or its BODYSTRUCTURE (IMAP): refuse before
        // the round-trip, and before the branch, so IMAP is held to the same ceiling as JMAP.
        DownloadLimits.enforce(part.size, maxBytes)
        offlineAttachmentFile(credentials.id, emailId, part)?.takeIf { it.isFile }?.let { return it.readBytes() }
        if (credentials.protocol == MailProtocol.IMAP) {
            val (mb, uid) = imapTarget(emailId) ?: error("Couldn't locate the message.")
            val section = part.partId ?: error("Attachment has no section.")
            return imap.fetchAttachment(credentials, mb, uid, section, part.encoding)
        }
        val ctx = connect(credentials)
        val blobId = part.blobId ?: error("Attachment has no blob.")
        return client.downloadBlob(
            ctx.session, ctx.accountId, blobId, part.type, part.name, ctx.auth, maxBytes,
        )
    }

    suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String = "attachment",
        cid: String? = null,
    ): EmailBodyPart {
        val ctx = connect(credentials)
        // Blobs are account-scoped, and a sub-account's outgoing Email is created under the
        // LOGIN's account (see performSend). Upload where the send will reference it.
        val uploadAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        val blob = client.uploadBlob(ctx.session, uploadAccountId, bytes, type, ctx.auth)
        return EmailBodyPart(
            blobId = blob.blobId,
            type = blob.type,
            size = blob.size,
            name = name,
            disposition = disposition,
            cid = cid,
        )
    }

    /** The server-side vacation responder; Unsupported for IMAP and without the capability. */
    suspend fun loadVacation(credentials: AccountCredentials): VacationState {
        if (credentials.protocol == MailProtocol.IMAP) return VacationState.Unsupported
        val ctx = connect(credentials)
        val response = client.getVacationResponse(ctx.session, ctx.accountId, ctx.auth)
            ?: return VacationState.Unsupported
        return VacationState.Loaded(response)
    }

    suspend fun saveVacation(credentials: AccountCredentials, vacation: VacationResponse): VacationResponse {
        val ctx = connect(credentials)
        return client.setVacationResponse(ctx.session, ctx.accountId, ctx.auth, vacation)
    }

    /** Identities read NOW from the server and stored (#172). It THROWS; empty means "none". */
    suspend fun serverIdentities(credentials: AccountCredentials): List<StoredIdentity> {
        if (credentials.protocol == MailProtocol.IMAP) {
            throw IllegalStateException("Identity/get is JMAP-only; an IMAP alias is local")
        }
        val ctx = connect(credentials)
        val identities = storedServerIdentities(client.getIdentities(ctx.session, ctx.accountId, ctx.auth))
        accountStore.setServerIdentities(credentials.id, identities)
        return identities
    }

    /** Create one sending identity (`Identity/set`, #172) and return its id; IMAP throws. */
    suspend fun createIdentity(credentials: AccountCredentials, name: String, email: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            throw IllegalStateException("Identity/set is JMAP-only; an IMAP alias is local")
        }
        val ctx = connect(credentials)
        // Named, not positional: a swap compiles silently and posts the display name as address.
        val created = client.createIdentity(ctx.session, ctx.accountId, name = name, email = email, auth = ctx.auth)
        // #172: `connect` hands back a CACHED context, so nothing re-reads Identity/get until the
        // next cold start. Re-read here, only once the server has confirmed the creation.
        refreshServerIdentities(ctx.session, ctx.accountId, credentials.id, ctx.auth)
        return created
    }

    /** Server-side quotas (RFC 9425); empty for IMAP, without the capability, and on any error. */
    suspend fun loadQuotas(credentials: AccountCredentials): List<Quota> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        return runCatching {
            val ctx = connect(credentials)
            client.getQuotas(ctx.session, ctx.accountId, ctx.auth)
        }.getOrDefault(emptyList())
    }

    /** The account's Sterna-managed Sieve rules; Unsupported for IMAP and without the capability. */
    suspend fun loadFilterRules(credentials: AccountCredentials): FilterRulesState {
        if (credentials.protocol == MailProtocol.IMAP) return FilterRulesState.Unsupported
        val ctx = connect(credentials)
        if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
            return FilterRulesState.Unsupported
        }
        val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
        suspend fun bodyOf(script: SieveScript): String = client.downloadBlob(
            ctx.session, ctx.accountId, script.blobId, "application/sieve", "${script.name}.siv", ctx.auth,
        ).toString(Charsets.UTF_8)

        val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        val script = managed?.let { bodyOf(it) }
        // The active script that is not ours is DOWNLOADED, not merely counted (#209). Its blobId
        // was already in hand and went unused, so the screen could say "another script is active"
        // and "no rules yet" in the same breath, over an account whose mail was being filtered.
        val foreign = scripts.firstOrNull { it.isActive && it.name != SieveCodec.SCRIPT_NAME }?.let { other ->
            ForeignScript(
                name = other.name,
                // A body we could not fetch stays null rather than "": an unknown script must not
                // read as an empty one, which is the single case where replacing it is free.
                body = runCatching { bodyOf(other) }.getOrElseUnlessCancelled { null },
            )
        }
        return loadedFilterRules(sternaScript = script, foreignScript = foreign)
    }

    /**
     * Whether the filter rules are actually running server-side, and whether the vacation responder
     */
    suspend fun loadFilterScriptStatus(credentials: AccountCredentials): FilterScriptStatus? {
        if (credentials.protocol == MailProtocol.IMAP) return FilterScriptStatus()
        return runCatching {
            val ctx = connect(credentials)
            if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
                return@runCatching FilterScriptStatus()
            }
            val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
            val vacation = scripts.any { it.name == VACATION_SCRIPT_NAME }
            // Existence and activity are two facts: the prediction needs the first, the red line
            // above Save the second (that script is running, so Save stops it).
            val vacationActive = scripts.any { it.name == VACATION_SCRIPT_NAME && it.isActive }
            val foreign = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }
            val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
                ?: return@runCatching FilterScriptStatus(
                    vacationScriptExists = vacation,
                    vacationScriptActive = vacationActive,
                    foreignActive = foreign,
                )
            val bytes = client.downloadBlob(
                ctx.session, ctx.accountId, managed.blobId, "application/sieve", "sterna.siv", ctx.auth,
            )
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = managed.isActive,
                enabledRuleCount = enabledRuleCount(SieveCodec.parseRules(bytes.toString(Charsets.UTF_8))),
                vacationScriptExists = vacation,
                vacationScriptActive = vacationActive,
                foreignActive = foreign,
            )
        }.getOrElseUnlessCancelled { null }
    }

    /** Compile [rules] to Sieve, validate server-side, then save and activate. Throws on refusal. */
    suspend fun saveFilterRules(credentials: AccountCredentials, rules: List<FilterRule>) {
        val ctx = connect(credentials)
        val script = SieveCodec.generate(rules)
        val blob = client.uploadBlob(
            ctx.session, ctx.accountId, script.toByteArray(Charsets.UTF_8), "application/sieve", ctx.auth,
        )
        client.validateSieve(ctx.session, ctx.accountId, blob.blobId, ctx.auth)?.let {
            throw IllegalStateException("The server rejected the filters: $it")
        }
        val existing = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
            .firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        client.saveSieveScript(
            ctx.session, ctx.accountId, SieveCodec.SCRIPT_NAME, blob.blobId, existing?.id, ctx.auth,
        )
    }

    private suspend fun connect(credentials: AccountCredentials): Context {
        context?.let { if (it.credentials == credentials && !tokenRefresher.isExpiring(credentials)) return it }
        // A 401 (expired/revoked bearer, or the edge gate refusing it) refreshes the token and the
        // session ONCE; a second refusal surfaces as SignInRequiredException, never a raw HTTP code.
        var auth = jmapAuth(credentials)
        return AuthRetry.withRefreshOn401(
            refresh = {
                context = null
                auth = jmapAuth(accountStore.credentials(credentials.id) ?: credentials, force = true)
            },
        ) {
            val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
            val accountId = jmapAccountIdFor(credentials, session)
            reconcileLinkedAccounts(credentials, session, auth)
            refreshServerIdentities(session, accountId, credentials.id, auth)
            val mailboxes = client.getMailboxes(session, accountId, auth)
            val roles = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
            Context(credentials, session, accountId, auth, roles, mailboxes).also { context = it }
        }
    }

    /** Drop the cached session so the next call reconnects (used after a 401 on a method call). */
    private fun invalidateSession() { context = null }

    /** Best-effort (#32). [accountId] is the JMAP account, [localAccountId] the record. */
    private suspend fun refreshServerIdentities(
        session: JmapSession,
        accountId: String,
        localAccountId: String,
        auth: JmapAuth,
    ) {
        runCatching {
            val serverIdentities = storedServerIdentities(client.getIdentities(session, accountId, auth))
            accountStore.setServerIdentities(localAccountId, serverIdentities)
        }
    }

    // ---- Offline window sync ----------------------------------------------------------------

    private fun offlineAttachmentFile(accountId: String, emailId: String, part: EmailBodyPart): java.io.File? {
        val root = offlineAttachmentsDir ?: return null
        val key = part.blobId ?: part.partId ?: return null
        fun safe(v: String) = v.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return java.io.File(java.io.File(java.io.File(root, safe(accountId)), safe(emailId)), safe(key))
    }

    /** Bytes held for offline reading: stored bodies plus downloaded attachments, all accounts. */
    suspend fun offlineCacheBytes(): Long {
        val bodies = runCatching { emailBodyDao.totalBytes() }.getOrDefault(0L)
        val files = (offlineAttachmentsDir ?: return bodies).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return bodies + files
    }

    /** Download every message of [credentials] inside [window] for offline reading; evict older ones. */
    suspend fun syncOffline(
        credentials: AccountCredentials,
        window: OfflineWindow,
        limit: OfflineAttachmentLimit,
        nowMillis: Long = System.currentTimeMillis(),
    ): OfflineSyncResult = OfflineSync(offlinePort(credentials)).run(window, limit, nowMillis)

    private fun offlinePort(credentials: AccountCredentials) = object : OfflineSyncPort {
        override suspend fun idsInWindow(cutoffMillis: Long?): List<String> {
            val local = emailDao.idsSince(credentials.id, cutoffMillis ?: Long.MIN_VALUE)
            if (credentials.protocol == MailProtocol.IMAP) return local
            val ctx = connect(credentials)
            val server = AuthRetry.withRefreshOn401(refresh = { invalidateSession() }) {
                val c = connect(credentials)
                client.searchEmails(
                    c.session, c.accountId, SearchQuery(afterMillis = cutoffMillis), OFFLINE_MAX_MESSAGES, c.auth,
                    excludeMailboxIds = listOfNotNull(ctx.rolesToMailboxId["trash"], ctx.rolesToMailboxId["junk"]),
                ).emails.map { it.id }
            }
            return (server + local).distinct()
        }

        override suspend fun cachedIds(ids: List<String>): Set<String> =
            ids.chunked(500).flatMap { emailBodyDao.cachedIds(credentials.id, it) }.toSet()

        override suspend fun fetchAndStore(ids: List<String>, attachmentMaxBytes: Long) {
            val stored: List<Email> = if (credentials.protocol == MailProtocol.IMAP) {
                ids.mapNotNull { id -> runCatching { openMessage(credentials, id, markRead = false).email }.getOrNull() }
            } else {
                val emails = AuthRetry.withRefreshOn401(refresh = { invalidateSession() }) {
                    val ctx = connect(credentials)
                    client.getEmailsWithBody(ctx.session, ctx.accountId, ids, ctx.auth)
                }
                val ctx = connect(credentials)
                val origins = originalSendersOf(ctx, emails) ?: emptyMap()
                emails.map { email ->
                    val withOrigin = email.copy(originalSender = origins[email.id])
                    if (cryptoKindOf(withOrigin) == null) {
                        val inline = runCatching { fetchInlineImages(credentials, withOrigin, email.id) }.getOrDefault(emptyMap())
                        persistBody(credentials.id, email.id, withOrigin, inline)
                    }
                    withOrigin
                }
            }
            if (attachmentMaxBytes <= 0) return
            for (email in stored) {
                for (part in email.attachments) {
                    if (part.size <= 0 || part.size > attachmentMaxBytes) continue
                    val file = offlineAttachmentFile(credentials.id, email.id, part) ?: continue
                    if (file.isFile) continue
                    runCatching {
                        val bytes = downloadAttachment(credentials, part, email.id, attachmentMaxBytes)
                        file.parentFile?.mkdirs()
                        val tmp = java.io.File(file.path + ".part")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(file)
                    }
                }
            }
        }

        override suspend fun evictOlderThan(cutoffMillis: Long): Int {
            val old = emailDao.idsBefore(credentials.id, cutoffMillis)
            old.chunked(500).forEach { emailBodyDao.deleteIds(credentials.id, it) }
            val orphans = emailBodyDao.fetchedBefore(credentials.id, cutoffMillis)
            orphans.chunked(500).forEach { emailBodyDao.deleteIds(credentials.id, it) }
            val accountDir = offlineAttachmentFile(credentials.id, "x", EmailBodyPart(blobId = "x"))?.parentFile?.parentFile
            (old + orphans).forEach { id ->
                accountDir?.let { java.io.File(it, id.replace(Regex("[^A-Za-z0-9._-]"), "_")).deleteRecursively() }
            }
            return old.size + orphans.size
        }
    }
}

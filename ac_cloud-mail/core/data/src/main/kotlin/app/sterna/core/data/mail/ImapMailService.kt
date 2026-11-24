package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.db.EmailAttachments
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.BlindCopies
import app.sterna.core.imap.IMAP_FOLDER_PAGE
import app.sterna.core.imap.IMAP_PREVIEW_FETCH_BYTES
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolder
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.ImapIdleConnection
import app.sterna.core.imap.ImapMailboxStatus
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapMoved
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.ImapTextPart
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import app.sterna.core.imap.MimeAttachment
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.SmtpClient
import app.sterna.core.imap.buildImapSearch
import app.sterna.core.imap.searchFolders
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A folder's mailboxes and what the walk saw; it holds no messages, they went to the caller page by page. */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val walk: ImapFolderWalk,
)

/**
 * Settle the folder's numbering, then walk it: [walk] hands its pages to the caller as they land, so
 * settling afterwards lists a row of the new numbering beside a body cached under the old one (#99).
 */
internal suspend fun <R> withNumberingSettled(settle: suspend () -> Unit, walk: suspend () -> R): R {
    settle()
    return walk()
}

/**
 * The cache ids a finished walk MAY be reconciled against — null when it cannot vouch for what it
 * read. Null is a refusal, never an empty set, which `reconcileMailbox` reads as "delete them all".
 */
internal fun reconcilableIds(load: ImapFolderLoad, accountId: String): Set<String>? {
    if (load.walk.moved) return null
    if (load.walk.uids.isEmpty() && !load.walk.folderStatedEmpty) return null
    return load.walk.uids.mapTo(HashSet()) { ImapMailService.emailId(accountId, load.targetMailboxId, it) }
}

/** Whether the SELECT STATED the folder empty. A page that read nothing is not a folder that holds nothing. */
internal fun pageVouchesForEmpty(status: ImapMailboxStatus): Boolean =
    status.existsObserved && status.exists == 0

/**
 * The ids a page MAY be remembered as — null when it cannot say what the folder holds:
 * `NewMailNotifier.seed` REPLACES a baseline, so remembering an empty page erases it.
 */
internal fun watchedBaselineIds(load: ImapWatchedLoad): List<String>? {
    if (load.messages.isEmpty() && !load.folderStatedEmpty) return null
    return load.messages.map { it.id }
}

/** One watched folder's fetched page (multi-folder push, issue #16). */
data class ImapWatchedLoad(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val messages: List<EmailEntity>,
    /** Where each opening line could be READ from. No default: a forgotten branch announces mail with no preview. */
    val previewSources: Map<String, PreviewSource>,
    /** Whether the server STATED this folder empty. No default: `true` lets an empty page wipe the baseline. */
    val folderStatedEmpty: Boolean,
    /** The numbering the SELECT that enumerated [messages] stated; without it, cached UIDs collide (#99, #187). */
    val uidValidity: Long?,
)

/** Where one message's opening line can be read from, carried out of the sync page that already had both. */
data class PreviewSource(val uid: Long, val part: ImapTextPart)

/**
 * The [PreviewSource] of each message of [page] with readable text. One with no `text/…` part is
 * ABSENT, not present-and-null: it must not consume one of the pass's few preview fetches.
 */
internal fun previewSourcesOf(
    accountId: String,
    mailboxId: String,
    page: List<ImapMessage>,
): Map<String, PreviewSource> = page.mapNotNull { message ->
    message.textPart?.let { part ->
        ImapMailService.emailId(accountId, mailboxId, message.uid) to PreviewSource(message.uid, part)
    }
}.toMap()

/**
 * Whether the MESSAGE LIST wants an opening line. Also true when only notifications ask for one
 * ([NotificationContent.BODY_PREVIEW]): a snoozed message is re-announced from the bare cache row.
 */
internal fun previewWantedInList(lines: PreviewLines, content: NotificationContent): Boolean =
    lines != PreviewLines.NONE || content == NotificationContent.BODY_PREVIEW

/**
 * The opening line of each message of [page] with no cached one yet (#187); only [missing] is asked
 * for. UIDs are grouped BY SECTION: one call per message is hundreds of round trips under one mutex.
 */
internal fun previewsForPage(
    accountId: String,
    mailboxId: String,
    page: List<ImapMessage>,
    missing: Set<String>,
    fetch: (section: String, uids: List<Long>) -> Map<Long, String>,
): Map<String, String> {
    val wanted = previewSourcesOf(accountId, mailboxId, page).filterKeys { it in missing }
    val out = LinkedHashMap<String, String>()
    for ((section, sources) in wanted.values.groupBy { it.part.section }) {
        val bodies = fetch(section, sources.map { it.uid })
        for (source in sources) {
            val raw = bodies[source.uid] ?: continue
            BodyPreview.fromPart(raw, source.part)?.let {
                out[ImapMailService.emailId(accountId, mailboxId, source.uid)] = it
            }
        }
    }
    return out
}

/**
 * What a page WRITES into the preview column: `@Upsert` REPLACES THE WHOLE ROW, so [read] alone blanks the
 * rest (#187).
 */
internal fun pageOpenings(cached: Map<String, String>, read: Map<String, String>): Map<String, String> = cached + read

/** The deadline arithmetic behind a time-bounded IMAP call: a retry must not double the wait. */
internal object ImapBudget {

    /** No deadline. Also the socket value meaning "block", which is why they are one constant. */
    const val NO_BUDGET = 0

    fun deadline(budgetMs: Int, nowMs: Long): Long = if (budgetMs > 0) nowMs + budgetMs else NO_BUDGET.toLong()

    /**
     * The budget one call runs under: the CALLER's, failing that the ambient [ImapReadBudget] — a FLOOR,
     * never a ceiling.
     */
    fun effective(callerMs: Int, ambientMs: Int): Int = when {
        callerMs > 0 -> callerMs
        ambientMs > 0 -> ambientMs
        else -> NO_BUDGET
    }

    /**
     * What is left of [deadline] at [nowMs], and an exception once the budget is spent. A
     * [SocketTimeoutException], not a CancellationException, which would look like the user quitting.
     */
    fun remaining(deadline: Long, nowMs: Long): Int {
        if (deadline == NO_BUDGET.toLong()) return NO_BUDGET
        val left = deadline - nowMs
        if (left <= 0) throw SocketTimeoutException("IMAP budget exhausted")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * A read budget carried by the COROUTINE, for calls whose bound cannot be written at the call site
 * (#95): a cancellation does not end a blocking read, so the thread keeps the account's IMAP mutex.
 */
internal class ImapReadBudget(val millis: Int) : AbstractCoroutineContextElement(ImapReadBudget) {
    companion object Key : CoroutineContext.Key<ImapReadBudget>
}

/**
 * IMAP read+write path, parallel to the JMAP path in [MailRepository]. One connection is pooled per
 * account and reused across calls (IMAP is stateful, so access is serialised).
 */
class ImapMailService(
    private val imapClient: ImapClient,
    private val smtpClient: SmtpClient,
    private val tokenRefresher: OAuthTokenRefresher,
    /** Where each folder's UIDVALIDITY is remembered; [UidValidityStore.None] verifies nothing. */
    private val uidValidity: UidValidityStore = UidValidityStore.None,
) {
    private class Pooled {
        val mutex = Mutex()
        var session: ImapSession? = null
        var config: MailServerConfig? = null
    }

    private val pool = ConcurrentHashMap<String, Pooled>()

    /**
     * Run [block] on the account's pooled IMAP session, serialised per account and retried once on a
     * connection error. [retryOnFailure] = false for [appendDraft]: a read replays, an `APPEND` cannot.
     */
    private suspend fun <T> withSession(
        credentials: AccountCredentials,
        budgetMs: Int = 0,
        retryOnFailure: Boolean = true,
        // Suspending for ONE caller, [loadFolder], whose walk writes each page between requests.
        block: suspend (ImapSession) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            NoNetwork.check("imap")
            val config = config(credentials.imap ?: error("Account has no IMAP server configured."), credentials)
            val pooled = pool.getOrPut(credentials.id) { Pooled() }
            pooled.mutex.withLock {
                if (pooled.config != null && pooled.config != config) {
                    pooled.session?.let { runCatching { it.close() } }
                    pooled.session = null
                }
                pooled.config = config
                runWithRetry(pooled, config, budgetMs, retryOnFailure, block)
            }
        }

    private suspend fun <T> runWithRetry(
        pooled: Pooled,
        config: MailServerConfig,
        budgetMs: Int,
        retryOnFailure: Boolean,
        block: suspend (ImapSession) -> T,
    ): T {
        val ambientMs = currentCoroutineContext()[ImapReadBudget]?.millis ?: ImapBudget.NO_BUDGET
        val deadline = ImapBudget.deadline(ImapBudget.effective(budgetMs, ambientMs), System.currentTimeMillis())
        var attempt = 0
        while (true) {
            // What is left of the budget bounds the connect, what is left after it bounds the reads.
            val session = pooled.session
                ?: imapClient.connect(config, ImapBudget.remaining(deadline, System.currentTimeMillis()))
                    .also { pooled.session = it }
            val forReads = ImapBudget.remaining(deadline, System.currentTimeMillis())
            try {
                return session.withReadTimeoutSuspending(forReads) { block(session) }
            } catch (cancelled: CancellationException) {
                runCatching { session.close() }
                pooled.session = null
                throw cancelled
            } catch (renumbered: ImapUidValidityChanged) {
                // Not a transport failure: a fresh connection would answer the same (#99).
                throw renumbered
            } catch (t: Throwable) {
                runCatching { session.close() }
                pooled.session = null
                // The connection is dropped either way; it is the REPLAY that is refused for a
                // caller whose command is not idempotent ([withSession]'s [retryOnFailure]).
                if (!retryOnFailure || ++attempt >= 2) throw t
            }
        }
    }

    /**
     * THE CHOKEPOINT for anything addressed by UID: SELECT the folder, check its numbering is still
     * the one our UIDs belong to, then run [block] (#99). [expectedUidValidity] overrides the record.
     */
    private suspend fun <T> onMailbox(
        credentials: AccountCredentials,
        mailboxId: String,
        budgetMs: Int = 0,
        expectedUidValidity: Long? = null,
        block: suspend (ImapSession, ImapMailboxStatus) -> T,
    ): T {
        val recorded = uidValidity.recorded(credentials.id, mailboxId)
        var observed = 0L
        val result = try {
            withSession(credentials, budgetMs) { session ->
                val status = session.select(mailboxId, expectedUidValidity ?: recorded)
                observed = status.uidValidity
                block(session, status)
            }
        } catch (renumbered: ImapUidValidityChanged) {
            uidValidity.invalidate(credentials.id, mailboxId, renumbered.observed)
            throw renumbered
        }
        rememberNumbering(credentials.id, mailboxId, recorded, observed)
        return result
    }

    /**
     * Apply what a SELECT just observed WITHOUT refusing anything: the body cache has to be dropped
     * before the caller shows a message from it.
     */
    private suspend fun rememberNumbering(accountId: String, mailboxId: String, recorded: Long?, observed: Long) {
        when (UidValidity.verdict(recorded, observed)) {
            UidValidity.Verdict.CHANGED -> uidValidity.invalidate(accountId, mailboxId, observed)
            // Rewriting the same number would be a database write per folder per sync.
            UidValidity.Verdict.SAME, UidValidity.Verdict.UNVERIFIABLE -> Unit
            UidValidity.Verdict.FIRST_SIGHT -> uidValidity.record(accountId, mailboxId, observed)
        }
    }

    /** [rememberNumbering] for a caller that had nothing in hand beforehand (the discovery reads). */
    private suspend fun reconcileNumbering(accountId: String, mailboxId: String, observed: Long) {
        if (observed <= 0L) return
        rememberNumbering(accountId, mailboxId, uidValidity.recorded(accountId, mailboxId), observed)
    }

    /** The numbering last observed for a folder, for a caller that has to record it elsewhere
     *  (the Empty-trash snapshot, when it falls back to the cached ids). */
    suspend fun recordedUidValidity(accountId: String, mailboxId: String): Long? =
        uidValidity.recorded(accountId, mailboxId)

    /** Set by `:app` at startup: the notification baseline it must drop on a renumbering lives there. */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = uidValidity.onRenumbered
        set(value) { uidValidity.onRenumbered = value }

    /** A dedicated IDLE connection on the account's INBOX for push, separate from the pooled one (IDLE blocks). */
    suspend fun openIdle(credentials: AccountCredentials, onChanged: () -> Unit, onClosed: () -> Unit): java.io.Closeable {
        val endpoint = credentials.imap ?: error("Account has no IMAP server configured.")
        // Resolved fresh: if the token expires mid-IDLE the connection drops and the caller reopens.
        return ImapIdleConnection(imapClient, config(endpoint, credentials), "INBOX", onChanged, onClosed)
    }

    /** Raw folder list (paths + the server's REAL hierarchy delimiter from LIST). */
    suspend fun listImapFolders(credentials: AccountCredentials) =
        withSession(credentials) { it.listFolders() }

    suspend fun disconnect(accountId: String) {
        val pooled = pool.remove(accountId) ?: return
        pooled.mutex.withLock {
            pooled.session?.let { runCatching { it.close() } }
            pooled.session = null
        }
    }

    /** Send via SMTP, then APPEND a \Seen copy into the Sent folder (if known). */
    suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) {
        val smtp = credentials.smtp ?: error("Account has no SMTP server configured.")
        val token = tokenRefresher.freshAccessToken(credentials)
        smtpClient.send(
            MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.username, credentials.password, token),
            message,
        )
        if (sentMailbox != null) {
            runCatching { withSession(credentials) { it.append(sentMailbox, OutgoingMime.build(message), "\\Seen") } }
        }
    }

    /**
     * APPEND a draft (\Draft flag) into Drafts. `retryOnFailure = false` is the point: a replay
     * appends a second copy before the deferred loop, which alone de-duplicates by id, can see it.
     */
    suspend fun appendDraft(credentials: AccountCredentials, draftsMailbox: String, message: OutgoingMessage) =
        withSession(credentials, retryOnFailure = false) {
            it.append(draftsMailbox, OutgoingMime.build(message, BlindCopies.WRITTEN), "\\Draft")
        }

    /**
     * APPEND octets read from ANOTHER account into [mailbox] (#189); null uid without UIDPLUS.
     * `retryOnFailure = false`: a replay puts TWO copies in, and the caller trashes the original.
     */
    suspend fun appendBytes(
        credentials: AccountCredentials,
        mailbox: String,
        message: ByteArray,
        flags: String,
        internalDate: Long?,
    ): Long? {
        return withSession(credentials, retryOnFailure = false) { it.append(mailbox, message, flags, internalDate) }
    }

    /**
     * The uid of the ONE message of [mailbox] named [messageId], null otherwise (#189). Stalwart
     * answers EMPTY, so null stops the move at "copied, not removed"; "the newest" would be wrong.
     */
    suspend fun findByMessageId(credentials: AccountCredentials, mailbox: String, messageId: String): Long? {
        return onMailbox(credentials, mailbox) { session, _ -> session.uidSearchHeader("Message-ID", "<$messageId>").singleOrNull() }
    }

    /**
     * Whether Drafts already holds the draft named [messageId] (#95). Stalwart answers `SEARCH
     * HEADER "Message-ID"` empty, which reads as "not there", hence the [DRAFT_LOOKUP_SCAN] scan.
     */
    suspend fun draftIsAlreadyThere(
        credentials: AccountCredentials,
        draftsMailbox: String,
        messageId: String,
    ): Boolean {
        // Block body so LocalDraftUploadWiringTest can read it; an expression body has no braces.
        return withSession(credentials) { session ->
            session.select(draftsMailbox)
            session.fetchUids(session.newestUids(DRAFT_LOOKUP_SCAN))
                .any { localDraftMessageIdMatches(messageId, it.messageId) }
        }
    }

    suspend fun testConnection(credentials: AccountCredentials) {
        withSession(credentials) { it.listFolders() }
    }

    /**
     * A SPECIFIC account's folders, no messages — the global cache holds only the last-synced one.
     * A BARE `listFolders()`, no LSUB, and it must stay so (#174): its caller writes none of them.
     */
    suspend fun listMailboxes(credentials: AccountCredentials): List<MailboxEntity> =
        withSession(credentials) { session ->
            session.listFolders().mapIndexed { index, folder ->
                imapMailboxEntity(credentials.id, folder, index)
            }
        }

    /**
     * Connect, list folders, and WALK the newest [limit] of the target folder, handing each page to
     */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
        onlySubscribed: Boolean = false,
        onPage: suspend (List<EmailEntity>) -> Unit,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): ImapFolderLoad {
        return withSession(credentials) { session ->
            val folders = session.listFolders(onlySubscribed)
            val mailboxes = folders.mapIndexed { index, folder ->
                imapMailboxEntity(credentials.id, folder, index)
            }
            val target = folders.firstOrNull { it.path == requestedMailboxId }
                ?: folders.firstOrNull { it.role == "inbox" }
                ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
                ?: folders.first()

            val status = session.select(target.path)
            val unread = session.unseenCount()
            val walk = withNumberingSettled(
                settle = { reconcileNumbering(credentials.id, target.path, status.uidValidity) },
                walk = {
                    session.walkFolder(status, limit, IMAP_FOLDER_PAGE) { page ->
                        val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)
                        onPage(
                            page.map {
                                val preview = previews[emailId(credentials.id, target.path, it.uid)]
                                it.toEntity(credentials.id, target.path, status.uidValidity, preview)
                            },
                        )
                    }
                },
            )

            ImapFolderLoad(
                mailboxes = mailboxes,
                targetMailboxId = target.path,
                targetName = target.name,
                unread = unread,
                accountName = credentials.username,
                walk = walk,
            )
        }
    }

    /**
     * The newest [limit] of the inbox plus each watched folder in [extraPaths], on one session
     * (#16); paths gone from the server come back second. It records numbering but refuses none.
     */
    suspend fun loadWatchedFolders(
        credentials: AccountCredentials,
        extraPaths: Set<String>,
        includeInbox: Boolean,
        limit: Int,
    ): Pair<List<ImapWatchedLoad>, Set<String>> {
        val numbering = mutableListOf<Pair<String, Long>>()
        val result = withSession(credentials) { session ->
            val folders = session.listFolders()
            val inbox = folders.firstOrNull { it.role == "inbox" }
                ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
                ?: folders.first()
            val targets = buildList {
                if (includeInbox) add(inbox)
                extraPaths.forEach { path ->
                    val folder = folders.firstOrNull { it.path == path }
                    if (folder != null && folder.path != inbox.path) add(folder)
                }
            }
            val missing = extraPaths.filterTo(mutableSetOf()) { path -> folders.none { it.path == path } }
            val loads = targets.map { folder ->
                val status = session.select(folder.path)
                numbering += folder.path to status.uidValidity
                // Kept whole one line longer than the row: [toEntity] drops the BODYSTRUCTURE.
                val page = session.fetchPage(status.exists, offset = 0, limit = limit)
                ImapWatchedLoad(
                    folder.path,
                    folder.name,
                    folder.role,
                    // No preview READ for the row: the NOTIFICATION path, with its own budget. The
                    // caller copies the cached openings back on before writing (#187).
                    page.map { it.toEntity(credentials.id, folder.path, status.uidValidity, null) },
                    previewSourcesOf(credentials.id, folder.path, page),
                    folderStatedEmpty = pageVouchesForEmpty(status),
                    // Same SELECT: what bounds the caller's cache read (#99 in #187).
                    uidValidity = UidValidity.stated(status.uidValidity),
                )
            }
            loads to missing
        }
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        return result
    }

    /** The page just older than the [offset] newest; previews are read under the SELECT that enumerated these UIDs. */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): Pair<List<EmailEntity>, Int> = onMailbox(credentials, mailboxId) { session, status ->
        val page = session.fetchPage(status.exists, offset, limit)
        val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)
        val messages = page.map {
            val preview = previews[emailId(credentials.id, mailboxId, it.uid)]
            it.toEntity(credentials.id, mailboxId, status.uidValidity, preview)
        }
        messages to status.exists
    }

    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        onMailbox(credentials, mailboxId) { session, _ -> session.setFlag(uid, flag, set) }

    /**
     * Move a message to [destMailbox], answering its new UID and the destination numbering `COPYUID`
     */
    suspend fun move(
        credentials: AccountCredentials,
        sourceMailbox: String,
        uid: Long,
        destMailbox: String,
        expectedUidValidity: Long? = null,
    ): ImapMoved {
        val (moved, landed) = onMailbox(credentials, sourceMailbox, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayMove = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            mayMove to if (mayMove) session.move(uid, destMailbox) else ImapMoved.NONE
        }
        if (!moved) throw ImapNumberingUnconfirmed(sourceMailbox, expectedUidValidity)
        return landed
    }

    /**
     * Move many messages to [destMailbox] in one SELECT + one `UID MOVE <set>` (#29), returning
     * COPYUID's mapping so Undo can move them back. [expectedUidValidity] is read at the tap.
     */
    suspend fun moveBatch(
        credentials: AccountCredentials,
        sourceMailbox: String,
        uids: List<Long>,
        destMailbox: String,
        expectedUidValidity: Long? = null,
    ): ImapMoved {
        val (moved, landed) = onMailbox(credentials, sourceMailbox, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayMove = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            mayMove to if (mayMove) session.move(uids, destMailbox) else ImapMoved.NONE
        }
        if (!moved) throw ImapNumberingUnconfirmed(sourceMailbox, expectedUidValidity)
        return landed
    }

    /**
     * Permanently delete many messages from one folder in a single session (#29). A folder
     */
    suspend fun deleteBatch(
        credentials: AccountCredentials,
        mailboxId: String,
        uids: List<Long>,
        expectedUidValidity: Long? = null,
    ) {
        val destroyed = onMailbox(credentials, mailboxId, expectedUidValidity = expectedUidValidity) { session, status ->
            val mayDestroy = UidValidity.mayDestroyUnderStatedNumbering(expectedUidValidity, status.uidValidity)
            if (mayDestroy) session.delete(uids)
            mayDestroy
        }
        if (!destroyed) throw ImapNumberingUnconfirmed(mailboxId, expectedUidValidity)
    }

    /**
     * At most [cap] UIDs currently in [mailboxId] — the folder as the SERVER holds it, "Empty trash"
     * covering the messages below the synced window too (#99); past [cap] the OLDEST are kept.
     */
    suspend fun snapshotUids(credentials: AccountCredentials, mailboxId: String, cap: Int): ImapUidSnapshot =
        onMailbox(credentials, mailboxId, budgetMs = ENUMERATE_BUDGET_MS) { session, status ->
            // The numbering comes back WITH the ids: they are only an order for as long as it holds.
            ImapUidSnapshot(session.allUids(cap), status.uidValidity)
        }

    suspend fun fetchByUids(credentials: AccountCredentials, mailboxId: String, uids: List<Long>): List<EmailEntity> =
        if (uids.isEmpty()) emptyList()
        else onMailbox(credentials, mailboxId) { session, status ->
            // No preview: brand-new UIDs, colliding with nothing, so there is nothing to preserve.
            session.fetchUids(uids).map { it.toEntity(credentials.id, mailboxId, status.uidValidity, null) }
        }

    suspend fun createFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.createFolder(path) }

    suspend fun renameFolder(credentials: AccountCredentials, oldPath: String, newPath: String) =
        withSession(credentials) { it.renameFolder(oldPath, newPath) }

    suspend fun deleteFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.deleteFolder(path) }

    /**
     * Server-side search across [mailboxIds], newest first, one SELECT + UID SEARCH per folder. Each
     * hit becomes an entity under THE FOLDER IT CAME FROM, a UID meaning nothing outside its mailbox.
     */
    suspend fun search(
        credentials: AccountCredentials,
        mailboxIds: List<String>,
        criteria: ImapSearchCriteria,
        requireAttachment: Boolean,
        limit: Int,
    ): ImapSearchHits {
        if (mailboxIds.isEmpty() || limit <= 0) return ImapSearchHits(emptyList(), complete = true)
        val command = buildImapSearch(criteria)
        // The walk holds this account's only connection: stop between folders if the caller is gone.
        val caller = currentCoroutineContext()[Job]
        // What each folder's SELECT stated, recorded after the walk (the store is suspending):
        // unrecorded, a folder reached only by search refuses a move on a healthy server (#99).
        val numbering = mutableListOf<Pair<String, Long>>()
        val result = withSession(credentials) { session ->
            val folders = session.searchFolders(
                mailboxIds,
                command,
                requireAttachment,
                limit,
                stillWanted = { caller?.isActive != false },
                onFolderError = { mailbox, error ->
                    android.util.Log.w("ImapSearch", "search failed in $mailbox: ${error.message}")
                },
            )
            folders.forEach { hits -> numbering += hits.mailbox to hits.uidValidity }
            ImapSearchHits(
                messages = folders
                    // No preview: a search hit is never written to `emails`, so it would be dropped.
                    .flatMap { hits -> hits.messages.map { it.toEntity(credentials.id, hits.mailbox, hits.uidValidity, null) } }
                    .sortedByDescending { it.sortKey }
                    .take(limit),
                complete = folders.none { it.incomplete },
            )
        }
        numbering.forEach { (path, observed) -> reconcileNumbering(credentials.id, path, observed) }
        return result
    }

    suspend fun fetchAttachment(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        section: String,
        encoding: String?,
    ): ByteArray = onMailbox(credentials, mailboxId) { session, _ ->
        MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
    }

    /** One message's ENVELOPE, by folder and UID — `null` when the folder no longer holds it (#159). */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        onMailbox(credentials, mailboxId) { session, status ->
            // No preview: this row is not cached either, and the whole body is one request away.
            session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId, status.uidValidity, null)
        }

    /**
     * The envelope of a message named by CACHE ID ALONE (#159), through [fetchByUid] and so through
     */
    suspend fun fetchById(credentials: AccountCredentials, emailId: String): EmailEntity? {
        if (accountOf(emailId) != credentials.id) return null
        val (mailboxId, uid) = targetOf(emailId) ?: return null
        return fetchByUid(credentials, mailboxId, uid)
    }

    /**
     * The cc+bcc of one message, off the envelope — `null` when it is not there. Read from the
     * SERVER: the cache row has no cc/bcc column, so "none" and "never loaded" would look alike.
     */
    suspend fun ccAndBccOf(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        budgetMs: Int,
    ): Set<String>? {
        // Block body so DraftAddressingWiringTest can read it; an expression body has no braces.
        return onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->
            val message = session.fetchByUid(uid) ?: return@onMailbox null
            copiedAddressesOrNull(message.cc + message.bcc)
        }
    }

    /**
     * Raw RFC822 source. [expectedUidValidity] is the numbering [uid] was read under for a caller
     * that froze one (#189), opposed in this SELECT: renumbered since, nothing is read at all.
     */
    suspend fun fetchSource(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        expectedUidValidity: Long? = null,
    ): String {
        // #1869 step 9: a source read in this account's batch session a moment ago, unless a numbering is to be opposed.
        if (expectedUidValidity == null) prefetchedSources.remove(sourceKey(credentials, mailboxId, uid))?.let { return it }
        // #1837 phase 3: the Rust core's IMAP client, only with MailCore.imapEnabled (default off) and no frozen numbering to oppose.
        if (expectedUidValidity == null && com.diegonmarcos.cloudlib.mailcore.MailCore.imapEnabled) {
            CoreImap.fetchSource(config(credentials.imap ?: error("Account has no IMAP server configured."), credentials), mailboxId, uid)?.let { return it }
        }
        return onMailbox(credentials, mailboxId, expectedUidValidity = expectedUidValidity) { session, _ -> session.fetchSource(uid) }
    }

    /**
     * #1869 step 9: the raw sources of [uids] of ONE folder read in ONE session (one connection, one SELECT) for the
     * offline sync's batches, held until [fetchSource] takes each once (a read carrying no frozen numbering only) or
     * [dropPrefetchedSources] clears what was not taken. A uid the server no longer holds is simply absent.
     */
    suspend fun prefetchSources(credentials: AccountCredentials, mailboxId: String, uids: List<Long>): Int =
        if (uids.isEmpty()) 0
        else onMailbox(credentials, mailboxId) { session, _ ->
            var n = 0
            for (uid in uids) runCatching { session.fetchSource(uid) }.getOrNull()?.let { prefetchedSources[sourceKey(credentials, mailboxId, uid)] = it; n++ }
            n
        }

    fun dropPrefetchedSources(credentials: AccountCredentials, mailboxId: String, uids: List<Long>) {
        uids.forEach { prefetchedSources.remove(sourceKey(credentials, mailboxId, it)) }
    }

    private val prefetchedSources = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun sourceKey(credentials: AccountCredentials, mailboxId: String, uid: Long) = "${credentials.id}\u0000$mailboxId\u0000$uid"

    /**
     * The SERVER's delivery timestamp (`INTERNALDATE`), not `EmailEntity.receivedAt`, which here is
     * the sender's own `Date:` header: the one caller dates an Autocrypt key with this, permanently.
     */
    suspend fun fetchInternalDate(credentials: AccountCredentials, mailboxId: String, uid: Long): Long? =
        onMailbox(credentials, mailboxId) { session, _ -> session.fetchInternalDate(uid) }

    /**
     * The opening line of every message of [page] with no CACHED one, read on the session that has
     * just SELECTed the folder (#99, #187). NOTHING ESCAPES: a throw would fail the folder read.
     */
    private suspend fun pagePreviews(
        session: ImapSession,
        accountId: String,
        mailboxId: String,
        page: List<ImapMessage>,
        uidValidity: Long?,
        cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
    ): Map<String, String> {
        if (cachedPreviewsFor == null || page.isEmpty()) return emptyMap()
        val ids = page.map { emailId(accountId, mailboxId, it.uid) }
        var cached = emptyMap<String, String>()
        return try {
            cached = cachedPreviewsFor(ids, uidValidity)
            pageOpenings(
                cached,
                previewsForPage(accountId, mailboxId, page, ids.toSet() - cached.keys) { section, uids ->
                    session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            android.util.Log.w("ImapPreview", "no list previews for $mailboxId: ${failed.message}")
            cached
        }
    }

    /**
     * The opening of one message's text for a notification; [part] null means NO CONNECTION.
     * [budgetMs] is the ONLY bound: the block blocks and holds the connection, so `withTimeout` cannot.
     */
    suspend fun fetchPreview(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        part: ImapTextPart?,
        budgetMs: Int,
    ): String? {
        if (part == null) return null
        return try {
            onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->
                BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            android.util.Log.w("ImapPreview", "no preview for $mailboxId:$uid: ${failed.message}")
            null
        }
    }
    private suspend fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.username,
        password = credentials.password,
        // OAuth accounts authenticate with a fresh bearer token (XOAUTH2); null = password.
        accessToken = tokenRefresher.freshAccessToken(credentials),
    )

    private fun ConnectionSecurity.toMailSecurity(): MailSecurity = when (this) {
        ConnectionSecurity.TLS -> MailSecurity.TLS
        ConnectionSecurity.STARTTLS -> MailSecurity.STARTTLS
        ConnectionSecurity.NONE -> MailSecurity.NONE
    }

    companion object {
        /**
         * Wall-clock budget for a whole-folder enumeration ([allUids]), reconnect included. It does
         * NOT bound their sum: the timeout is per read, so a trickling peer stretches the call.
         */
        const val ENUMERATE_BUDGET_MS = 15_000

        /**
         * How many of the NEWEST drafts [draftIsAlreadyThere] reads envelopes for (#95). Deeper than
         * this the answer is "not found", i.e. APPEND: a copy too many is deletable, a draft is not.
         */
        const val DRAFT_LOOKUP_SCAN = 50

        /** Stable, globally-unique cache id for an IMAP message. */
        fun emailId(accountId: String, mailboxId: String, uid: Long): String = "imap:$accountId:$mailboxId:$uid"

        /** The IMAP UID encoded in a cache id, or null if not an IMAP id. */
        fun uidOf(emailId: String): Long? =
            if (emailId.startsWith("imap:")) emailId.substringAfterLast(':').toLongOrNull() else null

        /** The mailbox path encoded in a cache id (handles ':' in the path), or null. */
        fun mailboxOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts.subList(2, parts.size - 1).joinToString(":") else null
        }

        /** The ACCOUNT encoded in a cache id: folder + UID alone are valid coordinates on another server too. */
        fun accountOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts[1] else null
        }

        /** Folder path and UID from an id alone; it says nothing about WHOSE account — see [accountOf]. */
        fun targetOf(emailId: String): Pair<String, Long>? {
            val mailbox = mailboxOf(emailId) ?: return null
            val uid = uidOf(emailId) ?: return null
            return mailbox to uid
        }
    }
}

/**
 * The ONE place a listed IMAP folder becomes a cache row. A folder that CLAIMED trash or junk and
 */
internal fun imapMailboxEntity(accountId: String, folder: ImapFolder, index: Int): MailboxEntity {
    val role = folder.role
        ?: folder.unelectedRole
            ?.takeIf { it in NOT_SEARCHED_BASE_ROLES }
            ?.let { UNELECTED_ROLE_MARK + it }
    return MailboxEntity(
        accountId = accountId,
        id = folder.path,
        name = folder.name,
        role = role,
        // A marked role falls in the `else`, where a role-less folder already fell.
        sortOrder = rolePriority(role) * 1000 + index,
        totalEmails = 0,
        unreadEmails = 0,
        // Copied as the listing gave it (#174); `ImapFolder` defaults it to `true`.
        isSubscribed = folder.isSubscribed,
    )
}

private fun rolePriority(role: String?): Int = when (role) {
    "inbox" -> 0
    "archive" -> 1
    "sent" -> 2
    "drafts" -> 3
    "junk" -> 4
    "trash" -> 5
    else -> 6
}

/**
 * The ONE place an IMAP message becomes a cache row; it does NOT go through [EmailMapper.toEntity],
 * and the two must agree column by column. [uidValidity] and [preview] must gain NO DEFAULT (#187).
 */
internal fun ImapMessage.toEntity(
    accountId: String,
    mailboxId: String,
    uidValidity: Long?,
    preview: String?,
): EmailEntity {
    val id = ImapMailService.emailId(accountId, mailboxId, uid)
    return EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        // The conversation key, rebuilt from the threading headers of THIS message alone; `@Upsert`
        // replacing the whole row is what makes recomputing it on every pass enough.
        threadId = imapThreadKey(references, inReplyTo, messageId),
        subject = subject,
        preview = preview,
        receivedAt = if (dateMillis > 0) Instant.ofEpochMilli(dateMillis).toString() else null,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        flagged = flagged,
        hasAttachment = hasAttachment,
        sortKey = dateMillis,
        recipientsJson = EmailRecipients.encode(
            to.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        replyToJson = EmailRecipients.encode(
            replyTo.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        ccJson = EmailRecipients.encode(
            cc.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // Envelope Bcc, in ITS OWN column, never folded into ccJson: a blind copy stays blind, and a
        // DRAFT this app appended does carry a Bcc: header.
        bccJson = EmailRecipients.encode(
            bcc.map { EmailAddress(name = it.name, email = it.email.orEmpty()) },
        ),
        // The numbering this message was enumerated under (schema v25, #99), normalised here and
        // never at the six call sites: a stated 0 becomes null, which destroys nothing.
        uidValidity = UidValidity.stated(uidValidity),
        // Column v28, and the reason this mapper's header says it must agree with [EmailMapper]
        // column by column: leave it out here and every IMAP sync pass would erase the chips a JMAP
        // path stored -- on a unified inbox holding both, on the same screen.
        attachmentsJson = EmailAttachments.encode(attachments.map { it.toBodyPart() }),
        // Schema v30: agrees with [EmailMapper] column by column (an upsert would erase it).
        authClass = AuthIndex.indexed(subject, preview),
        // Schema v31: the sender's registrable domain, column for column with [EmailMapper].
        fromDomain = SenderDomain.indexed(fromEmail),
        // Schema v32 (#1802): the envelope Message-ID, column for column with [EmailMapper].
        messageId = messageId?.takeIf { it.isNotBlank() },
    )
}

    /** An IMAP file part as the shared [EmailBodyPart] the rest of the app speaks. [partId] is the
     *  BODY section a fetch addresses (IMAP has no blob ids), which is exactly what
     *  [MailRepository.downloadAttachment] branches on to decide which protocol to ask.
     *
     *  An EMPTY name means the sender declared none -- [attachmentParts] does not invent one -- so it
     *  becomes null here and the UI shows its own placeholder. "" would be a filename, and it would
     *  reach the disk as one. */
private fun MimeAttachment.toBodyPart(): EmailBodyPart = EmailBodyPart(
    partId = section,
    size = size.toLong(),
    type = type.ifBlank { null },
    name = name.ifBlank { null },
    disposition = "attachment",
    cid = cid,
    encoding = encoding.ifBlank { null },
)

/** What an IMAP search found; [complete] is false when a folder's local attachment filter hit its scan cap. */
data class ImapSearchHits(val messages: List<EmailEntity>, val complete: Boolean)

/**
 * A folder's UIDs and the numbering they belong to, read in the same SELECT: a list of UIDs without
 * its UIDVALIDITY is a list of numbers that may already mean something else (#99).
 */
data class ImapUidSnapshot(val uids: List<Long>, val uidValidity: Long)

/**
 * The IMAP-expressible part of a [SearchQuery]. `hasAttachment` is absent — IMAP `SEARCH` has no key
 * for it, so it is applied locally — but `flagged` is here, `FLAGGED` being a standard key.
 */
internal fun SearchQuery.toImapCriteria() = ImapSearchCriteria(
    text = text,
    from = from,
    recipient = recipient,
    subject = subject,
    flagged = flagged,
    afterMillis = afterMillis,
    beforeMillis = beforeMillis,
)

/**
 * Whether the walk must fetch candidates and filter them HERE — a FETCH per candidate, a scan cap,
 * a truncated count. `flagged` must NEVER appear here: `FLAGGED` is a standard `SEARCH` key.
 */
internal fun SearchQuery.requiresLocalScan(): Boolean = hasAttachment

package app.sterna.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.evictableCachedIds
import app.sterna.core.data.mail.reconcileEvictions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Hides messages snoozed into the future — the list's own predicate
 */
const val NOT_SNOOZED_EMAILS_SQL: String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
        "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/**
 * Which cached rows the per-sender screen speaks for — the one clause behind both
 */
const val SENDER_VOLUME_SCOPE_SQL: String =
    "accountId = :accountId AND fromEmail IS NOT NULL AND fromEmail != '' " +
        "AND mailboxId IN (SELECT id FROM mailboxes WHERE accountId = :accountId " +
        "AND (role IS NULL OR LOWER(role) NOT IN ('sent','drafts','trash','junk','spam'))) " +
        "AND " + NOT_SNOOZED_EMAILS_SQL

/**
 * Cached mail per sender, grouped on `LOWER(fromEmail)`: the address is authoritative, the display
 */
const val SENDER_VOLUMES_SQL: String =
    "SELECT fromEmail AS email, fromName AS name, COUNT(*) AS total, " +
        "SUM(CASE WHEN seen = 0 THEN 1 ELSE 0 END) AS unread, MAX(sortKey) AS latest " +
        "FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL +
        " GROUP BY LOWER(fromEmail) ORDER BY total DESC, latest DESC"

/** The ids behind one line of [SENDER_VOLUMES_SQL] — same rows, same clause text. */
const val SENDER_MESSAGE_IDS_SQL: String =
    "SELECT id FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL + " AND LOWER(fromEmail) = LOWER(:email)"

@Dao
interface EmailDao {

    /** Offline window: ids of an account's messages received at or after [cutoff] (epoch millis). */
    @Query("SELECT DISTINCT id FROM emails WHERE accountId = :accountId AND sortKey >= :cutoff")
    suspend fun idsSince(accountId: String, cutoff: Long): List<String>

    /** Offline window: ids of an account's messages received before [cutoff]. */
    @Query("SELECT DISTINCT id FROM emails WHERE accountId = :accountId AND sortKey < :cutoff")
    suspend fun idsBefore(accountId: String, cutoff: Long): List<String>

    /**
     * Paged source for the list; the query is built dynamically (MailRepository.pagingQuery)
     * because the ORDER BY and mailbox-id set vary per view.
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EmailEntity>

    /** Paged source for the conversation (collapsed-thread) list, built dynamically by
     *  MailRepository.conversationQuery. */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun conversationPagingSource(query: SupportSQLiteQuery): PagingSource<Int, ConversationRow>

    /** One live count of a list's rows, built by MailRepository.listCountSql from the list's own WHERE. */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    /**
     * One-shot bounded read of the latest messages across every account's inbox, for the widget
     */
    @RawQuery
    suspend fun recentUnified(query: SupportSQLiteQuery): List<RecentEmailRow>

    /**
     * [recentUnified] as a Flow (#112). Both entities in [observedEntities]: the shared predicate
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun observeRecentUnified(query: SupportSQLiteQuery): Flow<List<RecentEmailRow>>

    /** Distinct recent senders matching [q] (for recipient autocomplete). */
    @Query(
        "SELECT fromEmail AS email, fromName AS name FROM emails " +
            "WHERE fromEmail IS NOT NULL AND fromEmail != '' " +
            "AND (fromEmail LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%') " +
            "GROUP BY LOWER(fromEmail) ORDER BY MAX(sortKey) DESC LIMIT :limit",
    )
    suspend fun suggestSenders(q: String, limit: Int): List<ContactRow>

    /**
     * Cached mail per sender ([SENDER_VOLUMES_SQL]). `suspend`, not a `Flow`: Room invalidates on
     * every write to `emails`, so a reactive version would replay a full table scan per sync page.
     */
    @Query(SENDER_VOLUMES_SQL)
    suspend fun senderVolumes(accountId: String): List<SenderVolumeRow>

    /** The ids of one sender's counted messages ([SENDER_MESSAGE_IDS_SQL]). */
    @Query(SENDER_MESSAGE_IDS_SQL)
    suspend fun senderMessageIds(accountId: String, email: String): List<String>

    // The four per-folder reads below are scoped by accountId as well as mailboxId: servers
    // (e.g. Stalwart) number mailboxes per account, so two accounts' inboxes can share an id

    /** The newest [limit] ids in one folder — the bound is the statement's, not the caller's. */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun newestIds(accountId: String, mailboxId: String, limit: Int): List<String>

    /**
     * The account-qualified keys of a folder's unread messages, newest first, at most [limit].
     */
    @Query(
        "SELECT accountId, id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND seen = 0 ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun unreadKeys(accountId: String, mailboxId: String, limit: Int): List<EmailKeyRow>

    /**
     * A folder's rows received at or after [since], newest first, at most [limit] — the candidates
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0) ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun receivedSince(accountId: String, mailboxId: String, since: Long, limit: Int): List<EmailEntity>

    /**
     * The ids [receivedSince] selects from, without its row cap — what a notification pass writes
     */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0)",
    )
    suspend fun idsReceivedSince(accountId: String, mailboxId: String, since: Long): List<String>

    // The id-keyed reads/writes below are scoped by accountId as well: with the composite
    // (accountId, id) key (#31) an email id is not unique across accounts, and an unscoped
    // UPDATE/DELETE would hit a same-server sibling account's row.

    /** The cached mailbox an email lives in (used to advance that mailbox's sync cursor). */
    @Query("SELECT mailboxId FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun mailboxOf(accountId: String, id: String): String?

    /**
     * The opening lines this account already holds for [ids] under the numbering [uidValidity] — id
     */
    @Query(
        "SELECT id, preview FROM emails WHERE accountId = :accountId AND id IN (:ids) " +
            "AND uidValidity IS :uidValidity AND preview IS NOT NULL",
    )
    suspend fun cachedPreviews(accountId: String, ids: List<String>, uidValidity: Long?): List<CachedPreview>

    @Query("SELECT seen FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun seenOf(accountId: String, id: String): Boolean?

    /** Rows cached before the auth index existed (or before their preview arrived): the next batch to index. */
    @Query("SELECT accountId, id, subject, preview FROM emails WHERE authClass IS NULL AND preview IS NOT NULL LIMIT :limit")
    suspend fun unclassifiedAuth(limit: Int): List<AuthIndexRow>

    @Query("UPDATE emails SET authClass = :authClass WHERE accountId = :accountId AND id = :id")
    suspend fun setAuthClass(accountId: String, id: String, authClass: Int)

    /** All cached messages from one sender (lower-cased address) in [mailboxIds], newest first: the members of a sender group. */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND LOWER(TRIM(fromEmail)) = :sender ORDER BY sortKey DESC",
    )
    fun cachedSenderEmails(accountId: String, mailboxIds: List<String>, sender: String): Flow<List<EmailEntity>>

    /** Rows cached before the domain index existed: the next batch to index ([app.sterna.core.data.mail.SenderDomain]). */
    @Query("SELECT accountId, id, fromEmail FROM emails WHERE fromDomain IS NULL LIMIT :limit")
    suspend fun unindexedDomains(limit: Int): List<DomainIndexRow>

    /** One batch of the domain backfill, in one transaction. */
    @Update(entity = EmailEntity::class)
    suspend fun setFromDomains(rows: List<FromDomainUpdate>)

    /** All cached messages from one sender DOMAIN ("" = no usable address) in [mailboxIds], newest first: a domain group. */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND fromDomain = :domain ORDER BY sortKey DESC",
    )
    fun cachedDomainEmails(accountId: String, mailboxIds: List<String>, domain: String): Flow<List<EmailEntity>>

    @Upsert
    suspend fun upsertAll(emails: List<EmailEntity>)

    /**
     * Prune a mailbox's cached page down to [keepIds] — window eviction, not removal: the messages
     */
    @Query("DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(accountId: String, mailboxId: String, keepIds: List<String>)

    /**
     * Like [deleteNotIn] but never touches [spareIds] — ids just mutated locally, protected until
     * the server reflects the change, so a stale re-query page cannot clobber an optimistic Undo.
     */
    @Query(
        "DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND id NOT IN (:keepIds) AND id NOT IN (:spareIds)",
    )
    suspend fun deleteNotInSparing(accountId: String, mailboxId: String, keepIds: List<String>, spareIds: List<String>)

    /**
     * Evict [ids] from the display cache and leave their index rows alone — [deleteNotIn]'s by-id
     */
    suspend fun evictFromCacheKeepingIndex(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        deleteRowsByIds(accountId, ids)
    }

    @Query("UPDATE emails SET seen = :seen WHERE accountId = :accountId AND id = :id")
    suspend fun setSeen(accountId: String, id: String, seen: Boolean)

    @Query("UPDATE emails SET flagged = :flagged WHERE accountId = :accountId AND id = :id")
    suspend fun setFlagged(accountId: String, id: String, flagged: Boolean)

    /**
     * Take one message out of this place: cached row and search-index row dropped together in one
     */
    suspend fun deleteById(accountId: String, id: String) {
        try {
            deleteRowAndIndexById(accountId, id)
        } catch (e: CancellationException) {
            throw e // the caller went away: commit neither half, let the next attempt do both
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting $id with its index row failed", e)
            deleteRowById(accountId, id)
            runCatching { unindexById(accountId, id) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of $id failed; its cached row is gone anyway", it)
            }
        }
    }

    /** [deleteById]'s two halves as one all-or-nothing statement pair — see its comment for why
     *  nothing is caught in here. */
    @Transaction
    suspend fun deleteRowAndIndexById(accountId: String, id: String) {
        deleteRowById(accountId, id)
        unindexById(accountId, id)
    }

    /**
     * [deleteById] for several ids of one account. Prefer it to a loop of [deleteById]: `email_fts`
     * is FTS4 with a `notindexed` `emailId`, so every un-index scans the whole index.
     */
    suspend fun deleteByIds(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            deleteRowsAndIndexByIds(accountId, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting ${ids.size} ids with their index rows failed", e)
            deleteRowsByIds(accountId, ids)
            runCatching { unindexByIds(accountId, ids) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of ${ids.size} ids failed; cached rows gone anyway", it)
            }
        }
    }

    /** [deleteByIds]' two halves as one all-or-nothing statement pair — see [deleteById]. */
    @Transaction
    suspend fun deleteRowsAndIndexByIds(accountId: String, ids: List<String>) {
        deleteRowsByIds(accountId, ids)
        unindexByIds(accountId, ids)
    }

    // The two halves of [deleteRowAndIndexById] / [deleteRowsAndIndexByIds] — and, for the cache
    // half alone, [evictFromCacheKeepingIndex]. Call those, not these.
    @Query("DELETE FROM emails WHERE accountId = :accountId AND id = :id")
    suspend fun deleteRowById(accountId: String, id: String)

    @Query("DELETE FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteRowsByIds(accountId: String, ids: List<String>)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId = :id")
    suspend fun unindexById(accountId: String, id: String)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId IN (:ids)")
    suspend fun unindexByIds(accountId: String, ids: List<String>)

    /** Cached message count for one account's mailbox (end-of-pagination check). */
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun countForMailbox(accountId: String, mailboxId: String): Int

    /**
     * Oldest cached row that is its thread's newest within the mailbox (anchor for the next older
     */
    @Query(
        "SELECT id FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id)) " +
            "ORDER BY sortKey ASC LIMIT 1",
    )
    suspend fun oldestRepresentativeEmailId(accountId: String, mailboxId: String): String?

    /** Cached thread-representative row count for the mailbox (the collapsed list's length). */
    @Query(
        "SELECT COUNT(*) FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id))",
    )
    suspend fun representativeCountForMailbox(accountId: String, mailboxId: String): Int

    /** All cached ids in one account's mailbox, for cache eviction. NOT what "Select all" reads:
     *  the list on screen is filtered, and an unfiltered read of the folder is #126 — see
     *  [keysForSelection]. */
    @Query("SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun idsForMailbox(accountId: String, mailboxId: String): List<String>

    /** The (account, id) keys "Select all" may take, built by MailRepository.selectionIdsQuery
     *  from the same WHERE clause the flat list pages with. */
    @RawQuery
    suspend fun keysForSelection(query: SupportSQLiteQuery): List<EmailKeyRow>

    /** Cached rows by id across accounts (unified-view selections; callers disambiguate
     *  by the returned rows' accountId). */
    @Query("SELECT * FROM emails WHERE id IN (:ids)")
    suspend fun emailsByIds(ids: List<String>): List<EmailEntity>

    /** Cached rows by id within one account (bulk actions running under its credentials). */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun emailsByIds(accountId: String, ids: List<String>): List<EmailEntity>

    /**
     * The IMAP numbering each of [ids] was read under, for a caller that freezes it and opposes it
     */
    @Query("SELECT id, uidValidity FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun numberingOfRows(accountId: String, ids: List<String>): List<RowNumbering>

    /** One account's cached members of the given threads filed under [mailboxId] (#50).
     *  Thread-less rows (NULL threadId) never match. */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND threadId IN (:threadIds)")
    suspend fun threadMembersInMailbox(accountId: String, mailboxId: String, threadIds: List<String>): List<EmailEntity>

    /**
     * All cached messages of one thread, newest first, scoped to one account and [mailboxIds] (the
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND COALESCE(threadId, id) = :threadKey ORDER BY sortKey DESC",
    )
    fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<EmailEntity>>

    /**
     * Per-folder count of unread THREADS (the conversation-mode drawer badge). Mirrors the
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM (" +
            "SELECT accountId, mailboxId, COALESCE(threadId, id) AS tk FROM emails " +
            "WHERE NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId, tk HAVING MIN(seen) = 0" +
            ") GROUP BY accountId, mailboxId",
    )
    fun observeThreadUnreadCounts(): Flow<List<MailboxUnread>>

    /**
     * Per-folder count of unread MESSAGES (the flat-mode drawer badge), with the flat list's
     * not-snoozed filter. Reactive, as [observeThreadUnreadCounts].
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM emails " +
            "WHERE seen = 0 AND NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId",
    )
    fun observeMessageUnreadCounts(): Flow<List<MailboxUnread>>

    /** Cached-message count per account, for the storage usage breakdown. */
    @Query("SELECT accountId, COUNT(*) AS messageCount FROM emails GROUP BY accountId")
    suspend fun countsByAccount(): List<AccountMessageCount>

    /**
     * What the Home page (#501) says about each account beyond the drawer's own numbers, from ONE
     * aggregate over the cache: starred, with-attachment, dated since [sinceMillis], and the oldest
     * dated message. `sortKey` 0 is "no parseable date" (EmailMapper.epochMillis), so it neither
     * counts as recent nor as the oldest. An account with no cached mail has no row at all.
     */
    @Query(
        "SELECT accountId, " +
            "SUM(CASE WHEN flagged = 1 THEN 1 ELSE 0 END) AS starred, " +
            "SUM(CASE WHEN hasAttachment = 1 THEN 1 ELSE 0 END) AS withAttachments, " +
            "SUM(CASE WHEN sortKey > 0 AND sortKey >= :sinceMillis THEN 1 ELSE 0 END) AS recent, " +
            "MIN(CASE WHEN sortKey > 0 THEN sortKey END) AS oldest " +
            "FROM emails GROUP BY accountId",
    )
    suspend fun homeCountsByAccount(sinceMillis: Long): List<AccountHomeCounts>

    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("DELETE FROM emails")
    suspend fun deleteAll()

    @Query("DELETE FROM emails WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /**
     * Age (and id) of every cached row in one account's mailbox — the input the retention prune
     */
    @Query("SELECT id, sortKey FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun retentionRows(accountId: String, mailboxId: String): List<EmailRetentionRow>

    /**
     * Replace one account's cached contents of a mailbox with a fresh snapshot. [spareIds] survive
     */
    @Transaction
    suspend fun replaceMailbox(
        accountId: String,
        mailboxId: String,
        emails: List<EmailEntity>,
        spareIds: List<String> = emptyList(),
    ) {
        upsertAll(emails)
        reconcileMailboxRows(accountId, mailboxId, emails.mapTo(HashSet()) { it.id }, spareIds, evictableIds = null)
    }

    /**
     * The second half of [replaceMailbox] alone: hold nothing beyond [keepIds] (and [spareIds])
     */
    @Transaction
    suspend fun reconcileMailbox(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String> = emptyList(),
        evictableIds: Set<String>?,
    ) {
        reconcileMailboxRows(accountId, mailboxId, keepIds, spareIds, evictableIds)
    }

    /**
     * The reconcile itself — read the folder, decide the complement once against the whole [keepIds],
     */
    suspend fun reconcileMailboxRows(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String>,
        evictableIds: Set<String>?,
    ) {
        reconcileEvictions(
            cachedIds = evictableCachedIds(idsForMailbox(accountId, mailboxId), evictableIds),
            keepIds = keepIds,
            spareIds = spareIds.toHashSet(),
        ).forEach { batch -> evictFromCacheKeepingIndex(accountId, batch) }
    }
}

/**
 * Projection for [EmailDao.retentionRows]. [sortKey] is epoch millis, and 0 on a message whose date
 * could not be parsed — undated, not ancient, and never evicted on age.
 */
data class EmailRetentionRow(
    val id: String,
    val sortKey: Long,
)

/**
 * Projection for [EmailDao.numberingOfRows]. [uidValidity] is null for a row cached before schema
 * v25 and for every JMAP row — "nothing to oppose", which destroys nothing.
 */
data class RowNumbering(
    val id: String,
    val uidValidity: Long?,
)

/**
 * Projection for [EmailDao.cachedPreviews]. [preview] is non-null because the query selects only
 * rows whose column is not null: "no opening line" is an absent entry here, never a blank one.
 */
data class CachedPreview(
    val id: String,
    val preview: String,
)

/** Projection for [EmailDao.keysForSelection]: the account-qualified key of one selectable row. */
data class EmailKeyRow(
    val accountId: String,
    val id: String,
)

/**
 * Projection for [EmailDao.senderVolumes]. [email] is the address as stored, [name] the display
 */
data class SenderVolumeRow(
    val email: String,
    val name: String?,
    val total: Int,
    val unread: Int,
    val latest: Long,
)

/** Projection for [EmailDao.countsByAccount]. */
data class AccountMessageCount(
    val accountId: String,
    val messageCount: Int,
)

/** Projection for [EmailDao.homeCountsByAccount]; [oldest] is null when no message has a date. */
data class AccountHomeCounts(
    val accountId: String,
    val starred: Int,
    val withAttachments: Int,
    val recent: Int,
    val oldest: Long?,
)

/** Per-(account, folder) unread aggregate for the drawer badge (see [EmailDao.observeThreadUnreadCounts]). */
data class MailboxUnread(
    val accountId: String,
    val mailboxId: String,
    val count: Int,
)

/** The columns [EmailDao.unclassifiedAuth] reads: what the classifier needs, nothing else. */
data class AuthIndexRow(val accountId: String, val id: String, val subject: String?, val preview: String)

/** The columns [EmailDao.unindexedDomains] reads: what the domain index needs, nothing else. */
data class DomainIndexRow(val accountId: String, val id: String, val fromEmail: String?)

/** A partial [EmailEntity] for [EmailDao.setFromDomains]: the key and the one column it writes. */
data class FromDomainUpdate(val accountId: String, val id: String, val fromDomain: String)

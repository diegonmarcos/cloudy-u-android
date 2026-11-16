package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** `LIKE … ESCAPE '\'`, not a subquery: body rows must go regardless of list rows; caller
 *  escapes `%`, `_`, `\`. */
const val BODY_CACHE_ID_PREFIX_SQL: String =
    "DELETE FROM email_bodies WHERE accountId = :accountId AND id LIKE :idPrefix ESCAPE '\\'"

/** DELETE for [EmailBodyDao.deleteAll], shared with its JVM test. */
const val BODY_CACHE_CLEAR_SQL: String = "DELETE FROM email_bodies"

/** One cached body as the eviction plan sees it: [sortKey] is the message's receive time (the fetch time when the
 *  list row is gone or undated), [bytes] its stored size. */
data class BodyCacheRow(val id: String, val sortKey: Long, val bytes: Long)

@Dao
interface EmailBodyDao {

    /** Every stored body of [accountId] with the facts [app.sterna.core.data.mail.planBodyEviction] decides on. */
    @Query(
        "SELECT b.id AS id, " +
            "COALESCE(NULLIF((SELECT MAX(e.sortKey) FROM emails e WHERE e.accountId = b.accountId AND e.id = b.id), 0), b.fetchedAt) AS sortKey, " +
            "(LENGTH(b.bodyJson) + LENGTH(b.inlineImagesJson)) AS bytes " +
            "FROM email_bodies b WHERE b.accountId = :accountId",
    )
    suspend fun bodyRows(accountId: String): List<BodyCacheRow>

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteIds(accountId: String, ids: List<String>)

    /** Stored bodies with no list row left, fetched before [cutoff]: outside the offline window. */
    @Query(
        "SELECT b.id FROM email_bodies b WHERE b.accountId = :accountId AND b.fetchedAt < :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM emails e WHERE e.accountId = b.accountId AND e.id = b.id)",
    )
    suspend fun fetchedBefore(accountId: String, cutoff: Long): List<String>

    @Query("SELECT COALESCE(SUM(LENGTH(bodyJson) + LENGTH(inlineImagesJson)), 0) FROM email_bodies")
    suspend fun totalBytes(): Long

    @Upsert
    suspend fun upsert(body: EmailBodyEntity)

    // Keyed (accountId, id) (#31): an id alone could match another account's cached body.
    @Query("SELECT * FROM email_bodies WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun byId(accountId: String, id: String): EmailBodyEntity?

    @Query("SELECT id FROM email_bodies WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun cachedIds(accountId: String, ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM email_bodies WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    /** LRU eviction: keep the [keep] most recently fetched bodies for an account, drop the rest. */
    @Query(
        "DELETE FROM email_bodies WHERE accountId = :accountId AND id NOT IN " +
            "(SELECT id FROM email_bodies WHERE accountId = :accountId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneForAccount(accountId: String, keep: Int)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /** Drops cached bodies whose id starts with [idPrefix] (one IMAP folder). Does not
     *  self-heal: a stale body under a renumbered UID could render under the wrong text (#99). */
    @Query(BODY_CACHE_ID_PREFIX_SQL)
    suspend fun deleteForIdPrefix(accountId: String, idPrefix: String)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Every account id with a cached body here, for the orphan sweep
     *  ([app.sterna.core.data.storage.StorageRepository.purgeOrphanedAccounts]). */
    @Query("SELECT DISTINCT accountId FROM email_bodies")
    suspend fun accountIds(): List<String>

    /** Drops every cached body for every account, once per upgrade. Scope pinned to this table
     *  only — widening it would silently wipe messages/attachments/search too. */
    @Query(BODY_CACHE_CLEAR_SQL)
    suspend fun deleteAll()
}

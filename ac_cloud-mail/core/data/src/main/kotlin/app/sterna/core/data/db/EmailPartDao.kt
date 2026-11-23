package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Shared with the JVM test. */
const val EMAIL_PARTS_ORPHAN_SWEEP_SQL: String =
    "DELETE FROM email_parts WHERE NOT EXISTS (SELECT 1 FROM email_bodies b WHERE b.accountId = email_parts.accountId AND b.id = email_parts.emailId)"

/** #1869 v34: the `email_parts` index (attachment metadata, inline image files). */
@Dao
interface EmailPartDao {
    @Upsert
    suspend fun upsertAll(parts: List<EmailPartEntity>)

    @Query("SELECT * FROM email_parts WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun forEmail(accountId: String, emailId: String): List<EmailPartEntity>

    /** The stored part answering `cid:` [cid] in one message. */
    @Query("SELECT * FROM email_parts WHERE accountId = :accountId AND emailId = :emailId AND cid = :cid LIMIT 1")
    suspend fun byCid(accountId: String, emailId: String, cid: String): EmailPartEntity?

    /** Rows whose body is gone (evicted, deleted, a cleared cache): every body delete path ends in this sweep. */
    @Query(EMAIL_PARTS_ORPHAN_SWEEP_SQL)
    suspend fun deleteOrphans()

    /** Inline image bytes on disk, counted against the attachments cap. */
    @Query("SELECT COALESCE(SUM(fileBytes), 0) FROM email_parts WHERE file IS NOT NULL")
    suspend fun fileBytes(): Long

    @Query("SELECT * FROM email_parts WHERE accountId = :accountId AND emailId IN (:emailIds) AND file IS NOT NULL")
    suspend fun filesFor(accountId: String, emailIds: List<String>): List<EmailPartEntity>

    @Query("DELETE FROM email_parts WHERE accountId = :accountId AND emailId IN (:emailIds)")
    suspend fun deleteForEmails(accountId: String, emailIds: List<String>)

    @Query("DELETE FROM email_parts WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM email_parts")
    suspend fun deleteAll()
}

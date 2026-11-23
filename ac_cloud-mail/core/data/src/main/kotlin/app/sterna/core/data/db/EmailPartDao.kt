package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** #1869 v34: the `email_parts` index (attachment metadata, inline image files). */
@Dao
interface EmailPartDao {
    @Upsert
    suspend fun upsertAll(parts: List<EmailPartEntity>)

    @Query("SELECT * FROM email_parts WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun forEmail(accountId: String, emailId: String): List<EmailPartEntity>

    /** The stored part answering `cid:` [cid] in one message; blocking, for `shouldInterceptRequest`'s worker thread. */
    @Query("SELECT * FROM email_parts WHERE accountId = :accountId AND emailId = :emailId AND cid = :cid LIMIT 1")
    fun byCidBlocking(accountId: String, emailId: String, cid: String): EmailPartEntity?

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

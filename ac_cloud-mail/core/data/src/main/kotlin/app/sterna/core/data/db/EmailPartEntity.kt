package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * #1869 v34: one body part of a cached message: attachment metadata and, for an inline image, the `cid:` it answers and
 * the file under `offline-attachments` that holds its bytes ([file] relative to that directory, null until stored).
 * Metadata and file names only: no part bytes are ever in this table, and nothing decrypted is ever written here.
 */
@Entity(
    tableName = "email_parts",
    primaryKeys = ["accountId", "emailId", "partKey"],
    indices = [Index(value = ["accountId", "emailId", "cid"])],
)
data class EmailPartEntity(
    val accountId: String,
    val emailId: String,
    /** The part's blobId, else its partId: the same key [app.sterna.core.data.mail.MailRepository] names files by. */
    val partKey: String,
    val cid: String?,
    val type: String?,
    val name: String?,
    val size: Long,
    val inline: Boolean,
    val file: String?,
    val fileBytes: Long,
)

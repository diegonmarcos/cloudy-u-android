package app.sterna.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Cached full body of an opened/prefetched message; separate from [EmailEntity] since bodies
 */
@Entity(
    tableName = "email_bodies",
    primaryKeys = ["accountId", "id"],
)
data class EmailBodyEntity(
    val id: String,
    val accountId: String,
    val bodyJson: String,
    val inlineImagesJson: String,
    /** Epoch millis when fetched, for LRU eviction. */
    val fetchedAt: Long,
    /** #1869 v34: the reader's sanitised document fragment (`cid:` references kept), or null until it is built. */
    val fragment: String? = null,
    /** #1869 v34: the [app.sterna.core.data.mail.ReaderFragment.VERSION] [fragment] was built by; a mismatch is rebuilt lazily. */
    @ColumnInfo(defaultValue = "0")
    val fragmentVersion: Int = 0,
    /** #1869 v34: [InlineState] of the message's `cid:` images. */
    @ColumnInfo(defaultValue = "0")
    val inlineState: Int = InlineState.UNKNOWN,
    /** #1869 v34: the stored size the caps add up (`SUM(bytes)`), so no cap query reads a body. */
    @ColumnInfo(defaultValue = "0")
    val bytes: Long = storedBodyBytes(bodyJson, inlineImagesJson, fragment),
)

/** The size a body row counts against the body cap: what `LENGTH(...)` read before v34, plus the fragment. */
fun storedBodyBytes(bodyJson: String, inlineImagesJson: String, fragment: String?): Long =
    bodyJson.length.toLong() + inlineImagesJson.length + (fragment?.length ?: 0)

/**
 * #1869: where a message's inline (`cid:`) images stand. [UNKNOWN] is every row stored before v34; it is classified
 * lazily when the row is next read or swept, never by a scan over the table.
 */
object InlineState {
    const val UNKNOWN = 0
    const val NONE = 1
    const val PENDING = 2
    const val COMPLETE = 3
    const val FAILED_PERMANENT = 4
}

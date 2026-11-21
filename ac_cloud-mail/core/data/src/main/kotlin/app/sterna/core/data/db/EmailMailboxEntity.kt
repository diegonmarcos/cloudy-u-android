package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * #1837 phase 3: one row per (message, mailbox) the SERVER files the message in (JMAP `mailboxIds`), mirrored from the Rust core's
 * feed. `emails` keeps ONE row per message under its primary mailbox (the rule in mail-core-rs `folder_rank`), so a message in
 * the Inbox and a label is one row; this table is what lets the label's list (and unread count) include it too, as Gmail does.
 * Only the core's projection writes it, replacing a message's whole set at once; a message that `EmailDao.upsertAll` files under a DIFFERENT mailbox
 * (a local move) loses its set, and the next projection brings it back. No row outlives its message for long: a
 * delete of `emails` through the projection or of a whole account deletes the memberships with it ([EmailDao.applyProjection],
 * [EmailDao.deleteForAccount], [EmailDao.deleteAll]), and [EmailDao.deleteOrphanMemberships] sweeps what the other delete paths left.
 */
@Entity(
    tableName = "email_mailboxes",
    primaryKeys = ["accountId", "emailId", "mailboxId"],
    indices = [Index(value = ["accountId", "mailboxId"])],
)
data class EmailMailboxEntity(
    val accountId: String,
    val emailId: String,
    val mailboxId: String,
)

package app.sterna.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL that creates the `outbox` table. A constant so the 9→10 migration and a JVM unit test
 */
const val OUTBOX_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `outbox` (" +
        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
        "`accountId` TEXT NOT NULL, " +
        "`recipients` TEXT NOT NULL, " +
        "`cc` TEXT, " +
        "`bcc` TEXT, " +
        "`subject` TEXT NOT NULL, " +
        "`textBody` TEXT NOT NULL, " +
        "`htmlBody` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`inReplyTo` TEXT, " +
        "`references` TEXT, " +
        "`attachmentsJson` TEXT NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`notBeforeMillis` INTEGER NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`attemptCount` INTEGER NOT NULL, " +
        "`lastError` TEXT, " +
        "`lastAttemptMillis` INTEGER)"

/**
 * Additive 9→10: add the persistent `outbox` table. The outbox holds unsent mail, so it must never
 * be destroyed on upgrade — unlike the disposable cache.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(OUTBOX_CREATE_SQL)
    }
}

/** Additive 11→12: OpenPGP columns on `outbox` (mode + path of the pre-built PGP/MIME entity). */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
    }
}

/**
 * 12→13: `mailboxes` gains `accountId` (composite key). The table is a disposable server mirror —
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `mailboxes`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mailboxes` (" +
                "`accountId` TEXT NOT NULL, " +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`role` TEXT, " +
                "`parentId` TEXT, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`totalEmails` INTEGER NOT NULL, " +
                "`unreadEmails` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `id`))",
        )
    }
}

/**
 * The `emails` table with the composite `(accountId, id)` primary key. A constant so the 15→16
 */
const val EMAILS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `emails` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, " +
        "`subject` TEXT, " +
        "`preview` TEXT, " +
        "`receivedAt` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, " +
        "`flagged` INTEGER NOT NULL, " +
        "`hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The index Room derives from `@Index("mailboxId")` on `emails` (name: `index_<table>_<column>`). */
const val EMAILS_MAILBOX_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_emails_mailboxId` ON `emails` (`mailboxId`)"

/** The `email_bodies` table with the composite key ([EmailBodyEntity]); shared with the JVM test. */
const val EMAIL_BODIES_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `email_bodies` (" +
        "`id` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`bodyJson` TEXT NOT NULL, " +
        "`inlineImagesJson` TEXT NOT NULL, " +
        "`fetchedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/** The `snoozed` table with the composite key ([SnoozedEntity]); shared with the JVM test. */
const val SNOOZED_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `snoozed` (" +
        "`emailId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`until` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `emailId`))"

/** Ordered column lists of the three rebuilt tables (identical in v13 and v14 — only the PK changes). */
const val EMAILS_COLUMNS: String =
    "`id`, `accountId`, `mailboxId`, `threadId`, `subject`, `preview`, `receivedAt`, " +
        "`fromName`, `fromEmail`, `seen`, `flagged`, `hasAttachment`, `sortKey`"
const val EMAIL_BODIES_COLUMNS: String = "`id`, `accountId`, `bodyJson`, `inlineImagesJson`, `fetchedAt`"
const val SNOOZED_COLUMNS: String = "`emailId`, `accountId`, `until`"

/**
 * Rebuild [table] under its new composite-key DDL [createSql], copying every row by explicit
 */
private fun SupportSQLiteDatabase.rebuildTable(table: String, createSql: String, columns: String) {
    execSQL(createSql.replace("`$table`", "`${table}_new`"))
    execSQL("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
}

/**
 * Additive 13→14 (released as 1.3.10): `outbox` and `scheduled_sends` gain `draftEmailId` — the
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftEmailId` TEXT")
    }
}

/**
 * Exact `CREATE VIRTUAL TABLE` Room generates for [EmailFtsEntity] with the `remove_diacritics=1`
 */
const val EMAIL_FTS_CREATE_SQL: String =
    "CREATE VIRTUAL TABLE IF NOT EXISTS `email_fts` USING FTS4(" +
        "`emailId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
        "`threadId` TEXT, `subject` TEXT NOT NULL, `sender` TEXT NOT NULL, `body` TEXT NOT NULL, " +
        "`preview` TEXT, `receivedAt` TEXT, `fromName` TEXT, `fromEmail` TEXT, " +
        "`seen` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, `hasAttachment` INTEGER NOT NULL, " +
        "`sortKey` INTEGER NOT NULL, tokenize=unicode61 `remove_diacritics=1`, " +
        "notindexed=`emailId`, notindexed=`accountId`, notindexed=`mailboxId`, " +
        "notindexed=`threadId`, notindexed=`preview`, notindexed=`receivedAt`, " +
        "notindexed=`fromName`, notindexed=`fromEmail`, notindexed=`seen`, notindexed=`flagged`, " +
        "notindexed=`hasAttachment`, notindexed=`sortKey`)"

/**
 * 14→15: rebuild the `email_fts` search index with `remove_diacritics=1`. The old table used
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `email_fts`")
        db.execSQL(EMAIL_FTS_CREATE_SQL)
        db.execSQL(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
                "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
                "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
                "FROM emails",
        )
    }
}

/**
 * 15→16: widen the primary keys of `emails`, `email_bodies` and `snoozed` from the email id alone
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.rebuildTable("emails", EMAILS_CREATE_SQL, EMAILS_COLUMNS)
        db.execSQL(EMAILS_MAILBOX_INDEX_SQL)
        // The old accountId index is subsumed by the new key's prefix.
        db.execSQL("DROP INDEX IF EXISTS `index_email_bodies_accountId`")
        db.rebuildTable("email_bodies", EMAIL_BODIES_CREATE_SQL, EMAIL_BODIES_COLUMNS)
        db.rebuildTable("snoozed", SNOOZED_CREATE_SQL, SNOOZED_COLUMNS)
    }
}

/**
 * Additive 16→17: `emails` gains `recipientsJson` ([EmailEntity.recipientsJson]) — Sent/Drafts rows
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `recipientsJson` TEXT")
    }
}

/** The `purge_snapshot` table ([PurgeSnapshotEntity]); shared with the JVM test. */
const val PURGE_SNAPSHOT_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `purge_snapshot` (" +
        "`purgeId` TEXT NOT NULL, " +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`emailId` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`purgeId`, `accountId`, `emailId`))"

/**
 * Additive 17→18: the `purge_snapshot` table (#99). "Empty trash" records the exact messages the
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_CREATE_SQL)
    }
}

/**
 * The UIDVALIDITY a snapshot was taken under ([PurgeSnapshotEntity.uidValidity]). NULLABLE, and the
 */
const val PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL: String =
    "ALTER TABLE `purge_snapshot` ADD COLUMN `uidValidity` INTEGER"

/** The `mailbox_uidvalidity` table ([MailboxUidValidityEntity]); shared with the JVM test. */
const val MAILBOX_UIDVALIDITY_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `mailbox_uidvalidity` (" +
        "`accountId` TEXT NOT NULL, " +
        "`mailboxId` TEXT NOT NULL, " +
        "`uidValidity` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `mailboxId`))"

/**
 * Additive 18→19: remember which numbering an IMAP folder's UIDs belong to (#99). A server that
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PURGE_SNAPSHOT_ADD_UIDVALIDITY_SQL)
        db.execSQL(MAILBOX_UIDVALIDITY_CREATE_SQL)
    }
}

/**
 * Additive 19→20: `emails` gains `replyToJson` ([EmailEntity.replyToJson]). A sender that sets
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `replyToJson` TEXT")
    }
}

/**
 * Additive 20→21: `emails` gains `ccJson` and `bccJson`. The row is what a reopened draft and a
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `ccJson` TEXT")
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `bccJson` TEXT")
    }
}

/**
 * Additive 21→22: `outbox` and `scheduled_sends` each gain `requestReceipt`.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `requestReceipt` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive 22→23: `outbox` and `scheduled_sends` each gain `draftUidValidity`, the IMAP numbering
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `draftUidValidity` INTEGER")
        db.execSQL("ALTER TABLE `scheduled_sends` ADD COLUMN `draftUidValidity` INTEGER")
    }
}

/**
 * The `local_drafts` table ([LocalDraftEntity]) — drafts written on the phone and not yet on the
 */
const val LOCAL_DRAFTS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `local_drafts` (" +
        "`accountId` TEXT NOT NULL, " +
        "`id` TEXT NOT NULL, " +
        "`messageId` TEXT NOT NULL, " +
        "`toAddresses` TEXT NOT NULL, " +
        "`cc` TEXT, " +
        "`bcc` TEXT, " +
        "`subject` TEXT NOT NULL, " +
        "`textBody` TEXT NOT NULL, " +
        "`htmlBody` TEXT, " +
        "`fromName` TEXT, " +
        "`fromEmail` TEXT, " +
        "`inReplyTo` TEXT, " +
        "`references` TEXT, " +
        "`attachmentsJson` TEXT NOT NULL, " +
        "`replacesEmailId` TEXT, " +
        "`replacesUidValidity` INTEGER, " +
        "`bodyIsLossy` INTEGER NOT NULL, " +
        "`requestReceipt` INTEGER NOT NULL, " +
        "`createdAtMillis` INTEGER NOT NULL, " +
        "`updatedAtMillis` INTEGER NOT NULL, " +
        "`notBeforeMillis` INTEGER NOT NULL, " +
        "`attemptCount` INTEGER NOT NULL, " +
        "`lastError` TEXT, " +
        "`lastAttemptMillis` INTEGER, " +
        "`state` TEXT NOT NULL, " +
        "PRIMARY KEY(`accountId`, `id`))"

/**
 * Additive 23→24: the `local_drafts` table (#95), where a draft saved with no network waits for one
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(LOCAL_DRAFTS_CREATE_SQL)
    }
}

/**
 * Additive 24→25: `emails` gains `uidValidity`, the IMAP numbering each cached row was READ under
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `uidValidity` INTEGER")
    }
}

/**
 * Additive 25→26: `mailboxes` gains `isSubscribed`, what the server reports about the folder's
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `mailboxes` ADD COLUMN `isSubscribed` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Additive 26→27: `emails` gains the composite index `(accountId, mailboxId, sortKey)` — the three
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_emails_accountId_mailboxId_sortKey` " +
                "ON `emails` (`accountId`, `mailboxId`, `sortKey`)",
        )
    }
}

/**
 * Additive 27->28: `emails` gains `attachmentsJson`, the message's file parts as the server described
 * them -- name, MIME type, size and the blob/section id a download addresses. Metadata only: no bytes
 * of any attachment are in this database, and none ever will be.
 *
 * ADDITIVE AND NULLABLE, with no backfill, because there is nothing to backfill FROM. The column
 * holds what a fetch was told, and rows cached before this version were fetched without asking. They
 * read back as null -> no chips, and gain their chips the next time sync touches them, which is the
 * same way `replyTo`, `cc` and `bcc` each arrived. Rewriting the table to invent a value would mean
 * inventing one.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `emails` ADD COLUMN `attachmentsJson` TEXT")
    }
}

/** The `message_text_cache` table ([MessageTextCacheEntity]); shared with the JVM test. */
const val MESSAGE_TEXT_CACHE_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `message_text_cache` (" +
        "`accountId` TEXT NOT NULL, " +
        "`emailId` TEXT NOT NULL, " +
        "`kind` TEXT NOT NULL, " +
        "`lang` TEXT NOT NULL, " +
        "`sourceHash` TEXT NOT NULL, " +
        "`payload` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`accountId`, `emailId`, `kind`, `lang`))"

/**
 * Additive 28→29: the `message_text_cache` table. A message's in-place translation and its summary
 * are kept per message and per language, so reopening one shows them without an engine call.
 *
 * ADDITIVE AND SELF-CONTAINED: a new table, no existing row is read or rewritten. The rows are a
 * cache - the worst a lost one costs is one engine call - so there is nothing to backfill.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MESSAGE_TEXT_CACHE_CREATE_SQL)
    }
}

/**
 * v29 -> v30: the auth-class index ("G0 _ Auth"). One nullable column, no rewrite: NULL means "not classified"
 * and `MailRepository.indexAuth` backfills it in the background, so the migration reads no row.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE emails ADD COLUMN authClass INTEGER")
    }
}

/**
 * v30 -> v31: the sender-domain index ("Group by Domain"). One nullable column, no rewrite: NULL means "not
 * indexed" and `MailRepository.indexDomains` backfills it in the background, so the migration reads no row.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE emails ADD COLUMN fromDomain TEXT")
    }
}

/**
 * v31 -> v32 (#1802): the message's RFC 5322 Message-ID, so a Sent row can ask the fleet mail API for its delivery
 * stages without opening the message. One nullable column, no rewrite: NULL means "not known yet", and the next
 * sync of the folder fills it (every list fetch now asks for it).
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE emails ADD COLUMN messageId TEXT")
    }
}

/**
 * v32 -> v33 (#1837 phase 3): `email_mailboxes`, the (message, mailbox) membership the Rust core's feed mirrors, so a label lists a
 * message that is also in the Inbox. A new empty table and its index, no row rewritten: until the core's projection fills it every
 * list reads exactly as before (the scope falls back to `emails.mailboxId`). The DDL is what Room generates for
 * [EmailMailboxEntity] (names, NOT NULL, primary key order, the index name), which `validateMigration` checks at open.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `email_mailboxes` (`accountId` TEXT NOT NULL, `emailId` TEXT NOT NULL, " +
                "`mailboxId` TEXT NOT NULL, PRIMARY KEY(`accountId`, `emailId`, `mailboxId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_email_mailboxes_accountId_mailboxId` ON `email_mailboxes` (`accountId`, `mailboxId`)")
    }
}

/** The v34 `email_parts` table and its index, exactly as Room generates them for [EmailPartEntity]; shared with the JVM test. */
const val EMAIL_PARTS_CREATE_SQL: String =
    "CREATE TABLE IF NOT EXISTS `email_parts` (`accountId` TEXT NOT NULL, `emailId` TEXT NOT NULL, `partKey` TEXT NOT NULL, " +
        "`cid` TEXT, `type` TEXT, `name` TEXT, `size` INTEGER NOT NULL, `inline` INTEGER NOT NULL, `file` TEXT, " +
        "`fileBytes` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `emailId`, `partKey`))"
const val EMAIL_PARTS_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_email_parts_accountId_emailId_cid` ON `email_parts` (`accountId`, `emailId`, `cid`)"

/**
 * v33 -> v34 (#1869 steps 5-7): `email_bodies` gains `fragment`, `fragmentVersion`, `inlineState` and `bytes`, and the new
 * `email_parts` table indexes attachment metadata and inline image files.
 *
 * ADDITIVE, NEVER DESTRUCTIVE: the database falls back to a destructive rebuild on a missing step, which would wipe the
 * outbox and the local drafts, so this step is registered in [SternaDatabase.ALL_MIGRATIONS]. No body is rewritten:
 * `fragment` starts NULL (built lazily), `fragmentVersion` 0 (older than any builder), `inlineState` 0 = UNKNOWN (classified
 * lazily on the next read or sweep). `bytes` is filled ONCE here with the size the caps measured before (`LENGTH` of the two
 * JSON columns), so every later cap query is `SUM(bytes)` and reads no body text.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `email_bodies` ADD COLUMN `fragment` TEXT")
        db.execSQL("ALTER TABLE `email_bodies` ADD COLUMN `fragmentVersion` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `email_bodies` ADD COLUMN `inlineState` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `email_bodies` ADD COLUMN `bytes` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `email_bodies` SET `bytes` = LENGTH(`bodyJson`) + LENGTH(`inlineImagesJson`)")
        db.execSQL(EMAIL_PARTS_CREATE_SQL)
        db.execSQL(EMAIL_PARTS_INDEX_SQL)
    }
}

/** #1952 the index that covers the domain-grouped list's aggregates ([EmailEntity]); the name and columns are Room's. */
const val EMAILS_DOMAIN_LIST_INDEX_SQL: String =
    "CREATE INDEX IF NOT EXISTS `index_emails_accountId_mailboxId_fromDomain_sortKey_seen_id` " +
        "ON `emails` (`accountId`, `mailboxId`, `fromDomain`, `sortKey`, `seen`, `id`)"

/**
 * v34 -> v35 (#1952): one more index on `emails`. At 40 000 messages the default list (grouped by sender domain) spent
 * its time reading every wide message row three times; with this index the aggregates are index-only.
 * ADDITIVE, NEVER DESTRUCTIVE (see [MIGRATION_33_34]): no row is touched.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(EMAILS_DOMAIN_LIST_INDEX_SQL)
    }
}

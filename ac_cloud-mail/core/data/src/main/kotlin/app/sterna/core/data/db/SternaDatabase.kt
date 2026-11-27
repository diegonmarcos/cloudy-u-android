package app.sterna.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/** Schema version Room opens, named since `@Database` isn't readable at runtime. Bumping
 *  without a step in [ALL_MIGRATIONS] drops every table. */
internal const val SCHEMA_VERSION = 35

@Database(
    entities = [
        EmailEntity::class, EmailFtsEntity::class, EmailBodyEntity::class, MailboxEntity::class,
        ScheduledSendEntity::class, SnoozedEntity::class, RecentContactEntity::class, OutboxEntity::class,
        PurgeSnapshotEntity::class, MailboxUidValidityEntity::class, LocalDraftEntity::class, MessageTextCacheEntity::class,
        EmailMailboxEntity::class, EmailPartEntity::class,
    ],
    version = SCHEMA_VERSION,
    exportSchema = false,
)
abstract class SternaDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun emailFtsDao(): EmailFtsDao
    abstract fun emailBodyDao(): EmailBodyDao
    abstract fun mailboxDao(): MailboxDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun snoozedDao(): SnoozedDao
    abstract fun recentContactDao(): RecentContactDao
    abstract fun outboxDao(): OutboxDao
    abstract fun purgeSnapshotDao(): PurgeSnapshotDao
    abstract fun mailboxUidValidityDao(): MailboxUidValidityDao
    abstract fun localDraftDao(): LocalDraftDao
    abstract fun messageTextCacheDao(): MessageTextCacheDao
    abstract fun emailPartDao(): EmailPartDao

    companion object {
        /**
         * The migrations Room is actually handed — absent here means it never runs, and
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_9_10, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
            MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35,
        )

        fun build(context: Context): SternaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SternaDatabase::class.java,
                "sterna.db",
            )
                .addMigrations(*ALL_MIGRATIONS)
                // Disposable mirror of the server: a missing migration rebuilds the cache.
                .fallbackToDestructiveMigration()
                .build()
    }
}

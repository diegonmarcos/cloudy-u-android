package app.sterna.core.data

import android.content.Context
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.db.MessageTextCacheDao
import app.sterna.core.data.db.SternaDatabase
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.LOCAL_DRAFT_FILES_DIR
import app.sterna.core.data.mail.MailboxUidValidityStore
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthTokenRefresher
import app.sterna.core.data.mail.SyncStateStore
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient

/** Builds data-layer components, keeping Room (the database) internal to this module. */
object DataFactory {
    /** Data-layer repositories that share a single database instance. */
    class DataLayer(
        val mailRepository: MailRepository,
        val storageRepository: StorageRepository,
        /** The per-message, per-language translation and summary cache (message_text_cache). */
        val messageTextCache: MessageTextCacheDao,
    )

    fun create(
        context: Context,
        client: JmapClient,
        accountStore: AccountStore,
        pgpEngine: PgpEngine? = null,
        settings: SettingsRepository? = null,
        /**
         * `BuildConfig.VERSION_NAME` of `:app`, handed down to [ImapClient] so it can name itself
         */
        clientVersion: String? = null,
    ): DataLayer {
        val appContext = context.applicationContext
        val database = SternaDatabase.build(appContext)
        // The IMAP service verifies each folder's UIDVALIDITY through this store, and it is the
        // store — not the service — that says what a renumbering invalidates (Codeberg #99).
        val uidValidity = MailboxUidValidityStore(
            database.mailboxUidValidityDao(), database.emailBodyDao(), database.purgeSnapshotDao(),
        )
        // ONE tree, handed to both readers: the repository that stages a draft's bytes there and
        // the sign-out that has to clear them (#95). Two literals would be one rename away from a
        // purge that sweeps an empty directory and reports success.
        val localDraftFiles = java.io.File(appContext.filesDir, LOCAL_DRAFT_FILES_DIR)
        val imapService = ImapMailService(
            ImapClient(clientVersion), SmtpClient(), OAuthTokenRefresher(OAuthClient(), accountStore),
            uidValidity,
        )
        return DataLayer(
            messageTextCache = database.messageTextCacheDao(),
            mailRepository = MailRepository(
                client, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), imapService,
                database.scheduledSendDao(), database.snoozedDao(), database.recentContactDao(),
                accountStore,
                outboxDao = database.outboxDao(),
                purgeSnapshotDao = database.purgeSnapshotDao(),
                localDraftDao = database.localDraftDao(),
                mailboxUidValidityDao = database.mailboxUidValidityDao(),
                outboxFilesDir = java.io.File(appContext.filesDir, "outbox"),
                // Its own tree: a draft's staged bytes outlive any send, and the outbox's purge
                // has nothing to say about them (#95).
                localDraftFilesDir = localDraftFiles,
                pgpEngine = pgpEngine,
                settings = settings,
                syncStateStore = SyncStateStore(appContext),
                offlineAttachmentsDir = java.io.File(appContext.filesDir, "offline-attachments"),
                // #1837 the Rust mail core (JMAP accounts; IMAP keeps the old path in phase 1).
                mailCore = app.sterna.core.data.mail.RustMailEngine.create(appContext),
            ),
            storageRepository = StorageRepository(
                appContext, database.emailDao(), database.emailFtsDao(), database.emailBodyDao(),
                database.mailboxDao(), database.snoozedDao(), database.purgeSnapshotDao(),
                database.mailboxUidValidityDao(),
                // Signing out takes the account's unsent drafts and their staged bytes with it —
                // user data, so it goes on THAT event and on no cache purge (#95, #121).
                localDraftDao = database.localDraftDao(),
                localDraftFilesDir = localDraftFiles,
            ),
        )
    }
}

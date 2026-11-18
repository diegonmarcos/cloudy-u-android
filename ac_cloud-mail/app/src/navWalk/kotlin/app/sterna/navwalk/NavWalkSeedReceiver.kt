package app.sterna.navwalk

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sterna.container
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.SternaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Poses ONE local account so the emulator nav walk (1_cicd/src/scripts/cloud-android-nav-walk.py, workflow test-nav-runtime.yml)
 * gets past onboarding and reaches Mail's shared chrome (first row, tabs, bottom nav, vertical side menu). The account points at
 * an unresolvable host and holds a throwaway placeholder password: nothing is ever contacted or sent, the screens simply open
 * with an empty, offline mailbox, which is all the walk needs.
 *
 * Exists only in the `-PtestApp` build (src/navWalk, beside src/testApp, is registered on no other variant, see app/build.gradle.kts), so the release
 * APK has neither this class nor its manifest entry. The walk builds that variant itself in CI; it is never published.
 *
 *   adb shell am broadcast -n com.diegonmarcos.comms.mail.test/app.sterna.navwalk.NavWalkSeedReceiver -a app.sterna.test.navwalk.SEED
 */
class NavWalkSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SYNC -> { runSeedSync(context); return }
            ACTION_COUNT -> { logCounts(context); return }
        }
        // #1803: `--ez recipientcheck true` binds the scripted deliverability checker (verdicts by domain, see
        // ScriptedRecipientChecker), before the already-seeded return so it can be sent on its own too.
        if (intent.getBooleanExtra("recipientcheck", false)) {
            app.sterna.ui.compose.RecipientChecks.checker = ScriptedRecipientChecker
            Log.i(TAG, "recipient check: scripted checker bound")
        }
        val store = (context.applicationContext as Application).container.accountStore
        if (store.hasAccount()) { Log.i(TAG, "seeded: an account is already present"); return }
        val id = store.add(
            server = "https://mail.navwalk.invalid",
            username = "walk@navwalk.invalid",
            password = "navwalk-placeholder",
            accountName = "Nav walk",
        )
        // #1800: `--ez htmlsig true` gives the seeded identity an HTML signature with five links (the shape of the one reported as
        // inserted as bare words) and turns "signature on replies" on, so the emulator proof can shoot New, Reply and Forward with it.
        if (intent.getBooleanExtra("htmlsig", false)) {
            val sig = app.sterna.core.data.account.StoredSignature.of(
                id = "navwalk-sig", name = "Sincerely,",
                source = "Sincerely,<br><b>Nav Walk</b><br>" +
                    "<a href=\"https://linktree.example.org\">https://linktree.example.org</a><br>" +
                    "<a href=\"https://wa.me/10000000000\">Whatsapp</a> | <a href=\"https://www.linkedin.com/in/navwalk\">LinkedIn</a> | " +
                    "<a href=\"mailto:walk@navwalk.invalid\">E-mail</a> | <a href=\"https://github.com/navwalk\">GitHub</a>",
            )
            store.setIdentities(id, listOf(
                app.sterna.core.data.account.StoredIdentity(
                    id = "navwalk-identity", name = "Nav walk", email = "walk@navwalk.invalid",
                    signatures = listOf(sig), defaultSignatureId = sig.id,
                ),
            ))
            val settings = (context.applicationContext as Application).container.settingsRepository
            runBlocking(Dispatchers.IO) { settings.setSignatureOnReplies(true) }
        }
        // The folders a synced mailbox would have cached: the section's vertical side menu lists them, and without any it has nothing to draw.
        val folders = listOf("inbox" to "Inbox", "drafts" to "Drafts", "sent" to "Sent", "archive" to "Archive", "junk" to "Spam", "trash" to "Trash")
        // The list scopes to the account's saved Inbox id: without it the seeded rows are never selected (#1752).
        store.saveInboxMetaFor(id, "navwalk-inbox", "Inbox", "Nav walk", 0)
        val db = SternaDatabase.build(context)
        try {
            runBlocking(Dispatchers.IO) {
                db.mailboxDao().upsertAll(folders.mapIndexed { i, (role, name) ->
                    MailboxEntity(accountId = id, id = "navwalk-$role", name = name, role = role, sortOrder = i, totalEmails = 0, unreadEmails = 0)
                })
                // #1752: `--ei emails N` caches N Inbox messages, newest first by date, so the emulator proof can show the cached list
                // (kept across the failed sync of the unresolvable host) and the 50-row chunks loading as the list is scrolled.
                val n = intent.getIntExtra("emails", 0)
                if (n > 0) {
                    val now = System.currentTimeMillis()
                    db.emailDao().upsertAll((1..n).map { i ->
                        EmailEntity(
                            id = "navwalk-m$i", accountId = id, mailboxId = "navwalk-inbox", threadId = "navwalk-t$i",
                            subject = "Seed message ${n - i + 1} of $n", preview = "Cached mail number ${n - i + 1}",
                            receivedAt = java.time.Instant.ofEpochMilli(now - i * 3_600_000L).toString(),
                            fromName = "Sender $i", fromEmail = "sender$i@d$i.example", fromDomain = "d$i.example",
                            seen = i % 3 != 0, flagged = false, hasAttachment = false, sortKey = now - i * 3_600_000L,
                        )
                    })
                    // #1763: `--ei bodies M` also stores the full BODY of the M newest seeded messages, as a prefetch would, so the
                    // emulator proof can open one from disk (no network call is possible: the host is unresolvable).
                    val bodies = intent.getIntExtra("bodies", 0).coerceAtMost(n)
                    if (bodies > 0) {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        for (i in 1..bodies) {
                            val email = app.sterna.core.jmap.model.Email(
                                id = "navwalk-m$i", accountId = id, mailboxId = "navwalk-inbox", threadId = "navwalk-t$i",
                                subject = "Seed message ${n - i + 1} of $n",
                                receivedAt = java.time.Instant.ofEpochMilli(now - i * 3_600_000L).toString(),
                                from = listOf(app.sterna.core.jmap.model.EmailAddress(name = "Sender $i", email = "sender$i@d$i.example")),
                                keywords = mapOf("\$seen" to true),
                                textBody = listOf(app.sterna.core.jmap.model.EmailBodyPart(partId = "text", type = "text/plain")),
                                bodyValues = mapOf("text" to app.sterna.core.jmap.model.EmailBodyValue(value = "Cached body of seed message ${n - i + 1}.\n\n" + "Lorem ipsum dolor sit amet. ".repeat(40))),
                            )
                            db.emailBodyDao().upsert(
                                app.sterna.core.data.db.EmailBodyEntity(
                                    id = email.id, accountId = id,
                                    bodyJson = json.encodeToString(app.sterna.core.jmap.model.Email.serializer(), email),
                                    inlineImagesJson = "{}", fetchedAt = now,
                                ),
                            )
                        }
                    }
                }
                // #1768: `--ei archive K` caches K Archive messages (no body yet): the background full sync has to fill a folder that is
                // not the inbox. The newest one is ciphertext (its body is stored raw and must never trigger a key import).
                val k = intent.getIntExtra("archive", 0)
                if (k > 0) {
                    val now = System.currentTimeMillis()
                    db.emailDao().upsertAll((1..k).map { i ->
                        EmailEntity(
                            id = "navwalk-a$i", accountId = id, mailboxId = "navwalk-archive", threadId = "navwalk-at$i",
                            subject = if (i == 1) "Archive encrypted message" else "Archive message ${k - i + 1} of $k",
                            preview = "Archived mail number ${k - i + 1}",
                            receivedAt = java.time.Instant.ofEpochMilli(now - i * 7_200_000L).toString(),
                            fromName = "Archiver $i", fromEmail = "archiver$i@a$i.example", fromDomain = "a$i.example",
                            seen = true, flagged = false, hasAttachment = false, sortKey = now - i * 7_200_000L,
                        )
                    })
                }
            }
        } finally { db.close() }
        Log.i(TAG, "seeded: one offline account with ${folders.size} folders")
    }

    /**
     * #1768: "run the worker" on the emulator. No JMAP server exists in CI, so the real [app.sterna.core.data.mail.FullSync] engine, the
     * one OfflineSyncWorker drives, runs here over a port that serves a synthetic body for every cached message that has none (the
     * Inbox first, then the Archive, ciphertext kept raw) and stores it in Room exactly as a fetch would.
     */
    private fun runSeedSync(context: Context) {
        val store = (context.applicationContext as Application).container.accountStore
        val accountId = store.allCredentials().firstOrNull()?.id ?: run { Log.w(TAG, "sync: no account"); return }
        val db = SternaDatabase.build(context)
        try {
            val port = SeedSyncPort(db, accountId)
            val result = runBlocking(Dispatchers.IO) {
                app.sterna.core.data.mail.FullSync(
                    port, { app.sterna.core.data.mail.PrefetchConditions.UNRESTRICTED }, app.sterna.core.data.mail.AttachmentPolicy.DEFAULT,
                ).run(app.sterna.core.data.mail.OfflineWindow.DEFAULT, System.currentTimeMillis())
            }
            Log.i(TAG, "sync: bodies=${result.bodies} folders=${result.folders} errors=${result.errors} stop=${result.stop} keyImports=0")
        } finally { db.close() }
    }

    private class SeedSyncPort(private val db: SternaDatabase, private val accountId: String) : app.sterna.core.data.mail.FullSyncPort {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        override suspend fun folders() = listOf(
            app.sterna.core.data.mail.FullSyncFolder("navwalk-archive", inbox = false),
            app.sterna.core.data.mail.FullSyncFolder("navwalk-inbox", inbox = true),
        )
        override suspend fun syncHeaders(folder: app.sterna.core.data.mail.FullSyncFolder) {}
        override suspend fun backfillHeaders(folder: app.sterna.core.data.mail.FullSyncFolder, cutoffMillis: Long?) = true
        override suspend fun missingBodies(folder: app.sterna.core.data.mail.FullSyncFolder, limit: Int) =
            db.emailDao().newestWithoutBody(accountId, folder.id, Long.MIN_VALUE, limit)
        override suspend fun fetchBodies(folder: app.sterna.core.data.mail.FullSyncFolder, ids: List<String>) {
            for (row in db.emailDao().emailsByIds(accountId, ids)) {
                val cipher = row.subject?.contains("encrypted") == true
                val text = if (cipher) {
                    "-----BEGIN PGP MESSAGE-----\n\nhQEMA2seedciphertext\n-----END PGP MESSAGE-----\n"
                } else {
                    "Synced body of ${row.subject}.\n\n" + "Lorem ipsum dolor sit amet. ".repeat(40)
                }
                val email = app.sterna.core.jmap.model.Email(
                    id = row.id, accountId = accountId, mailboxId = row.mailboxId, threadId = row.threadId, subject = row.subject,
                    receivedAt = row.receivedAt,
                    from = listOf(app.sterna.core.jmap.model.EmailAddress(name = row.fromName, email = row.fromEmail.orEmpty())),
                    keywords = mapOf("\$seen" to true),
                    textBody = listOf(app.sterna.core.jmap.model.EmailBodyPart(partId = "text", type = "text/plain")),
                    bodyValues = mapOf("text" to app.sterna.core.jmap.model.EmailBodyValue(value = text)),
                )
                db.emailBodyDao().upsert(
                    app.sterna.core.data.db.EmailBodyEntity(
                        id = row.id, accountId = accountId,
                        bodyJson = json.encodeToString(app.sterna.core.jmap.model.Email.serializer(), email),
                        inlineImagesJson = "{}", fetchedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        override suspend fun missingAttachments(limit: Int) = emptyList<app.sterna.core.data.mail.AttachmentJob>()
        override suspend fun fetchAttachment(job: app.sterna.core.data.mail.AttachmentJob) {}
        override suspend fun bodyBytes() = db.emailBodyDao().bytesForAccount(accountId)
        override suspend fun attachmentBytes() = 0L
        override suspend fun evict() = 0
    }

    /** One line the workflow greps: bodies stored per folder, and how many of them are ciphertext kept raw. */
    private fun logCounts(context: Context) {
        val store = (context.applicationContext as Application).container.accountStore
        val accountId = store.allCredentials().firstOrNull()?.id ?: return
        val db = SternaDatabase.build(context)
        try {
            runBlocking(Dispatchers.IO) {
                val missingInbox = db.emailDao().newestWithoutBody(accountId, "navwalk-inbox", Long.MIN_VALUE, 100_000).size
                val missingArchive = db.emailDao().newestWithoutBody(accountId, "navwalk-archive", Long.MIN_VALUE, 100_000).size
                val raw = db.emailBodyDao().byId(accountId, "navwalk-a1")?.bodyJson?.contains("BEGIN PGP MESSAGE") == true
                Log.i(TAG, "counts: inboxMissingBody=$missingInbox archiveMissingBody=$missingArchive encryptedArchiveStoredRaw=$raw")
            }
        } finally { db.close() }
    }

    private companion object {
        const val TAG = "NavWalkSeed"
        const val ACTION_SYNC = "app.sterna.test.navwalk.SYNC"
        const val ACTION_COUNT = "app.sterna.test.navwalk.COUNT"
    }
}

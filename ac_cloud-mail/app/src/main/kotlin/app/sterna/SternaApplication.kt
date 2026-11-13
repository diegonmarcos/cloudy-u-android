package app.sterna

import android.app.Application
import android.content.Context
import android.util.Log
import app.sterna.core.data.DataFactory
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.GatewayAuthStore
import app.sterna.core.data.account.VaultMailImport
import app.sterna.core.data.mail.BodyCachePurge
import app.sterna.core.data.mail.LocalDraftScheduler
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OutboxScheduler
import app.sterna.core.data.mail.localDraftUploadJobs
import app.sterna.core.data.mail.scheduledSendJobs
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.JmapClient
import app.sterna.drafts.LocalDraftUploads
import app.sterna.pgp.OpenKeychainPgpEngine
import app.sterna.push.UnifiedPushManager
import app.sterna.push.UnifiedPushStateStore
import app.sterna.security.AppLock
import app.sterna.send.Outbox
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.ui.connect.OAuthCodeSignIn
import app.sterna.ui.connect.OutlookSignIn
import app.sterna.widget.RecentMailWidgetDraw
import app.sterna.widget.RecentMailWidgetPresence
import app.sterna.widget.RecentMailWidgetPush
import app.sterna.widget.UnreadWidgetDraw
import app.sterna.widget.UnreadWidgetPresence
import app.sterna.widget.UnreadWidgetPush
import com.diegonmarcos.superapp.updater.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PendingReplySuggestion(val emailId: String, val text: String)

class AppContainer(context: Context) {
    /** Content Uris shared into the app (ACTION_SEND), awaiting attachment by the next compose
     *  screen (#45). A one-shot handoff; the compose screen reads and clears it. */
    var pendingShareUris: List<android.net.Uri> = emptyList()

    /** The reply the reader suggested for [PendingReplySuggestion.emailId], handed to the NEXT reply-all
     *  composer of that message and cleared by it. A one-shot handoff, like the share Uris above: the
     *  text is never sent from here, it only becomes the first lines of an editable draft. */
    @Volatile var pendingReplySuggestion: PendingReplySuggestion? = null

    val accountStore: AccountStore = AccountStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    /** Per-account Authelia sign-in (the edge gate in front of the public JMAP URLs); its cookies go
     *  back on the wire here, before any sync can run. */
    val gatewayAuth: GatewayAuthStore = GatewayAuthStore.of(context.applicationContext)
        .also { g -> runCatching { g.restore(accountStore.accounts()) } }
    private val appContext: Context = context.applicationContext
    /** #1090 The fleet accounts' two endpoints (mesh + public) and bearer as Cloud Account published them; put back
     *  on the transport here, before any sync can run. Nothing about them is built into the app. */
    val fleetEndpointStore: app.sterna.core.data.account.FleetEndpointStore = app.sterna.core.data.account.FleetEndpointStore.of(appContext)
    val fleetEndpoints: app.sterna.core.data.account.FleetEndpointWiring = app.sterna.core.data.account.FleetEndpointWiring(
        fleetEndpointStore, com.diegonmarcos.superapp.fleetendpoints.FleetTransport.router, com.diegonmarcos.superapp.fleetendpoints.FleetTransport.bearers,
    ).also { w -> runCatching { w.restore(accountStore.accounts()) } }

    /** Asks Cloud Account for the fleet accounts' endpoints and applies them. BLOCKS: call off the main thread. */
    fun syncFleetEndpoints(): app.sterna.fleet.MailEndpointSync.Outcome =
        app.sterna.fleet.MailEndpointSync.pull(appContext, fleetEndpoints, accountStore.accounts())
    /** `core/jmap` is plain Kotlin/JVM and cannot see `android.util.Log`, so the app hands it the
     *  sink. The tag is half of the `adb logcat` filter given to reporters and is pinned by a test;
     *  info/warn only, release stripping debug and verbose. */
    private val jmapClient: JmapClient = JmapClient { message, error ->
        if (error == null) Log.i("JmapClient", message) else Log.w("JmapClient", message, error)
    }

    /** OpenPGP via the OpenKeychain provider (binds lazily; harmless when not installed). */
    val pgpEngine: OpenKeychainPgpEngine =
        OpenKeychainPgpEngine(context.applicationContext, settingsRepository)
    private val dataLayer =
        DataFactory.create(
            context.applicationContext, jmapClient, accountStore, pgpEngine, settingsRepository,
            // What the IMAP client names itself as to the server (RFC 2971 ID, #173). It can only
            // come from here: core/imap and core/data have no BuildConfig.
            clientVersion = BuildConfig.VERSION_NAME,
        )
    val mailRepository: MailRepository = dataLayer.mailRepository
    val storageRepository: StorageRepository = dataLayer.storageRepository
    val messageTextCache = dataLayer.messageTextCache
    val appLock: AppLock = AppLock(accountStore)

    /** UnifiedPush transport state machine (issue #17); inert without a distributor. */
    val unifiedPushManager: UnifiedPushManager = UnifiedPushManager(
        context.applicationContext,
        accountStore,
        mailRepository,
        UnifiedPushStateStore(context.applicationContext),
    )

    /** App-lifetime scope for work that must outlive a screen — the Undo-send hold-back, or putting
     *  a queued message back in the outbox as its composer is popped (#70). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sendOutbox: SendOutbox = SendOutbox(appScope)

    /** The startup recovery of the outbox — parking every row left EDITING by a process death, then
     *  re-arming the unfinished sends — as a Job a rebuilt composer `join()`s before taking its row
     * back, that row being parked FAILED otherwise. A Job to join, never a `runBlocking` in
     *  `onCreate`: this touches Room, and blocking the main thread on a migration is an ANR. */
    val outboxRecovery: Job

    /** Drives the Outlook OAuth device flow app-scoped, so it survives the browser round-trip. */
    val outlookSignIn: OutlookSignIn = OutlookSignIn(mailRepository, appScope, context.applicationContext)

    /** Drives the authorization-code grant app-scoped: that flow LEAVES the app for a browser and
     *  comes back through MainActivity, long after a screen-scoped owner would be gone (#55). */
    val oauthCodeSignIn: OAuthCodeSignIn =
        OAuthCodeSignIn(mailRepository, appScope, context.applicationContext)

    init {
        val appContext = context.applicationContext
        // Fallback new-mail poll, for when live push is killed (#11). A healthy push makes each
        // run a no-op.
        app.sterna.push.MailFetchWorker.ensureScheduled(appContext)
        // A UnifiedPush transport change re-evaluates which accounts need a direct connection.
        unifiedPushManager.onTransportStateChanged = {
            // Background trigger: diff, never reseed (gap mail must still notify).
            app.sterna.push.PushController.apply(appContext, userInitiated = false)
        }
        // Let the data layer arm the delivery worker from any send call site (compose, RSVP, …).
        // #1693 Cloud Account runs the OAuth device grant (a provider call): the repository hands it the endpoints, the screen keeps its states.
        mailRepository.oauthDeviceBroker = app.sterna.fleet.CloudAccountOAuthBroker(appContext)
        mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }
        // The same for a draft the server could not be given (#95): the save books the deferred
        // upload itself. Without this the only bookings were the startup re-arm and the worker's.
        mailRepository.localDraftScheduler = LocalDraftScheduler { accountId, id, delay ->
            LocalDraftUploads.enqueue(appContext, accountId, id, delay)
        }
        // A linked sub-account pruned on reconcile (#31) drops its notification baselines too,
        // exactly like a sign-out — the data layer cannot reach them.
        mailRepository.onAccountPruned = { app.sterna.push.NewMailNotifier.clear(appContext, it) }
        // A folder the server renumbered (#99): every id in it is new, so its baseline describes
        // messages that no longer exist. Cleared here, the next pass seeds it silently.
        mailRepository.onMailboxRenumbered = { accountId, mailboxId ->
            app.sterna.push.NewMailNotifier.clear(appContext, accountId, mailboxId)
        }
        // Catch-up sweep of cached mail belonging to accounts that no longer exist (#121). The
        // account list is handed over as a READER, so an account created while the database was
        // opening (#31) cannot be mistaken for an orphan; an empty list sweeps nothing.
        appScope.launch {
            runCatching { storageRepository.purgeOrphanedAccounts { accountStore.accounts().map { it.id } } }
                .onFailure { android.util.Log.w("Sterna", "orphaned-cache sweep failed; retried next start", it) }
        }
        // One-shot on crossing a version THRESHOLD, never on every update: drop the cached bodies.
        // A body serialised by an older build is short of whatever field the new one learned to
        // read, and openMessage serves the cache first. Bodies only, and they refill on the next open.
        appScope.launch {
            runCatching {
                BodyCachePurge(appContext).onceForVersion(BuildConfig.VERSION_CODE) {
                    mailRepository.clearCachedBodies()
                }
            }.onFailure { android.util.Log.w("Sterna", "body-cache upgrade purge failed; retried next start", it) }
        }
        // Re-arm any send left mid-flight. `keepExisting = true`: WorkManager may already have
        // restored the job mid-delivery, and a REPLACE would cancel a healthy send — after which
        // every cold start reads "interrupted, may already have been sent".
        outboxRecovery = appScope.launch {
            // First park any row stranded in EDITING by a process death (#70): FAILED, visible and
            // reopenable, so only a gesture makes it leave. Requeued here it would go out at 0
            // while the restored composer holds the same text, twice.
            mailRepository.parkInterruptedOutboxEdits()
            mailRepository.unfinishedOutbox().forEach { item ->
                val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)
                Outbox.enqueue(appContext, item.id, delay, keepExisting = true)
            }
        }
        // The same safety net for a draft the phone kept because the server could not be reached
        // (#95): a reboot can leave one waiting on nothing, and nothing else asks about it.
        //
        // What the rescue picks up is TWO states and no more: a row left UPLOADING by a process
        // that died mid-attempt, and one left STAGING mid file-copy, which comes back flagged
        // `bodyIsLossy` so it cannot destroy the server original it names.
        //
        // And NOT an EDITING row — do not "repair" that back in. This runs before the killed
        // composer has read its row back, so flipping it to PENDING hands the text being typed to
        // the upload worker, which deletes the row on success: two drafts on the server and the edit
        // lost. Measured, on IMAP and on JMAP. Per account, and the rescue BEFORE the scheduling.
        appScope.launch {
            runCatching {
                mailRepository.accountsWithLocalDrafts().forEach { accountId ->
                    mailRepository.revertUnfinishedLocalDrafts(accountId)
                    localDraftUploadJobs(
                        accountId,
                        mailRepository.localDraftsAwaitingUpload(accountId),
                        System.currentTimeMillis(),
                    ).forEach { LocalDraftUploads.enqueue(appContext, it.accountId, it.id, it.delayMillis) }
                }
            }.onFailure { Log.w("Sterna", "local-draft re-arm failed; retried next start", it) }
        }
        // And the same for a message the user asked to send LATER. Of the three durable queues this
        // one waits the longest, so it is the likeliest to outlive its job — and it is the only copy
        // of the message. An overdue row is re-armed at a delay of zero: it leaves LATE, not never.
        //
        // NOT a restore: `allowBackup` is false and `sterna.db` is excluded from backup. What is
        // readable here is that the table and WorkManager's jobs are two different files, and that
        // `scheduleSend` writes the row and books its job as two operations. It also gives
        // `ScheduledSendWorker`'s departure check a caller it did not have.
        //
        // `keepExisting = true`, one notch worse here: the worker deposits the message and THEN
        // deletes the row, not in one transaction, so a REPLACE between them sends it twice.
        appScope.launch {
            runCatching {
                scheduledSendJobs(mailRepository.scheduledSends(), System.currentTimeMillis())
                    .forEach { ScheduledSends.enqueue(appContext, it.id, it.delayMillis, keepExisting = true) }
            }.onFailure { Log.w("Sterna", "scheduled-send re-arm failed; retried next start", it) }
        }
        // The home-screen widget's number, pushed as the mail moves (#112). The widget polls
        // nothing, so without this it sits on a stale figure while the drawer decrements. ONE
        // collection covers every write, live push and foreground refresh; wiring those call sites
        // one by one is a list that goes out of date the first time a gesture is added.
        //
        // Gated on a widget being PLACED: collecting the unified total for the life of every
        // process subscribes two global Room aggregates on behalf of the majority who never place
        //
        appScope.launch {
            UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext), accountStore.accountsFlow) {
                mailRepository.observeUnifiedInboxUnreadByAccount()
            }.collect { unreadByAccount ->
                runCatching { UnreadWidgetDraw.redraw(appContext, unreadByAccount) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "unread widget not redrawn; the cell keeps its number", it) }
            }
        }
        // The SECOND widget's rows, pushed the same way and for the same defect: a message marked
        // read in the app left its row on the home screen carrying an unread dot, the data being
        // right and the trigger missing.
        //
        // Its own launch and its own gate: RecentMailWidgetPresence is seeded from THIS provider's
        // cells, and sharing the counter's would let each widget decide the other's freshness.
        appScope.launch {
            RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext), accountStore.accountsFlow) {
                mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT)
            }.collect {
                RecentMailWidgetDraw.forget()
                runCatching { RecentMailWidgetDraw.refresh(appContext) }
                    .rethrowIfCancelled()
                    .onFailure { Log.w("Sterna", "latest-messages widget not redrawn; the cell keeps its rows", it) }
            }
        }
    }
}

class SternaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The vault's mail section, when the fleet config contract left it in the account prefs
        // (the migration / Fleet Setup restarted us for it): accounts in, keys out, once.
        VaultMailImport.run(this, container.accountStore)
        // #1090 the fleet accounts' mesh + public endpoints and bearer, from Cloud Account (the source of truth),
        // once per start and off the main thread; and the mesh is re-probed whenever the network changes.
        app.sterna.net.EndpointNetworkWatch.start(this)
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.syncFleetEndpoints() } }
        // The fleet setup contract serves the signatures out of the accounts blob (store mail_signatures).
        app.sterna.fleet.SignaturesSetupHandler.register(container.accountStore)
        // and the settings DataStore (store datastore:settings), through the app's own instance.
        app.sterna.fleet.SettingsSetupHandler.register()
        // Arm the periodic self-update check. Idempotent, and it CANCELS itself when
        // the Auto-update toggle is off, so this one call is both the arm and the
        // disarm - there is no second place that has to remember to stop it.
        //
        // In Application rather than MainActivity because the schedule should survive
        // however the app was entered: a notification tap, a share, a widget. Nothing
        // is downloaded here; the worker decides that, and on mobile data it asks
        // first. See ui/settings/UpdateScreen.kt for what the owner sees.
        Updater.start(this)
        // Offline window sync (unmetered + charging); idempotent.
        app.sterna.mail.OfflineSyncWorker.ensureScheduled(this)
    }
}

val Application.container: AppContainer
    get() = (this as SternaApplication).container

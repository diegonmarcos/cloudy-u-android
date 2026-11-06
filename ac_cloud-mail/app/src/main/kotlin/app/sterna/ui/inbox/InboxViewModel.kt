package app.sterna.ui.inbox

import android.app.Application
import android.os.SystemClock
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.sterna.container
import app.sterna.R
import app.sterna.folders.FolderDeleteWorker
import app.sterna.mail.MessageDestroyWorker
import app.sterna.net.ConnectivityWatcher
import app.sterna.net.hasUsableNetwork
import app.sterna.net.isOfflineFailure
import app.sterna.net.ReconnectRefresh
import app.sterna.push.FetchAndNotify
import app.sterna.push.NewMailNotifier
import app.sterna.push.Notifications
import app.sterna.push.PushController
import app.sterna.snooze.Snoozes
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.attachment.AttachmentOpen
import app.sterna.ui.components.attachmentKey
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.AlertCounts
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FrozenNumbering
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.data.mail.UidValidity
import app.sterna.core.data.mail.emailKey
import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.mail.SenderDomain
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.send.SendOutbox
import app.sterna.ui.NotificationFolderSwitch
import app.sterna.ui.search.SearchDisplay
import app.sterna.ui.search.searchComplete
import app.sterna.ui.search.searchDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MailUi(
    /** The current account, whatever view is on screen; the unified inbox spans every account and
     * still has one. Null while no account is configured. Mailbox ids are numbered per account
     *  (#31/#121): compare to the ROW's account before trusting a lookup in [mailboxes]. */
    val accountId: String? = null,
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val selectedMailboxId: String?,
    /** True when showing the cross-account unified inbox (no single folder selected). */
    val unified: Boolean,
    /** True when showing THIS account's unread mail across its folders ([Sel.Unread]) — no single
     *  folder selected either, so nothing else on screen can tell that view from the unified one. */
    val unreadView: Boolean = false,
    /** For the drawer entry that leads to the view above: the same aggregate the folder rows are
     *  badged with, summed over the folders that view pages. Zero means no number at all. */
    val unreadViewCount: Int = 0,
    /** True when the current view is the inbox home (unified, or the account's Inbox folder).
     *  Drives Back: from any other folder, Back returns here instead of leaving the app. */
    val atInbox: Boolean = true,
    /** The normal browse list is paged separately ([InboxViewModel.pagedEmails]); this
     *  holds the (bounded) results shown while inline search is active. */
    val searchResults: List<Email> = emptyList(),
    /** False when the search stopped short (server cap, or an account that failed and was
     *  dropped): the count above the results then says "at least N". */
    val searchComplete: Boolean = true,
    val mailboxes: List<Mailbox>,
    /** The same list minus what the account's "subscribed folders only" setting hides (#174,
     * [visibleFolders]) — what the drawer draws, and nothing else. A second field, never a
     *  replacement for [mailboxes], which is read for subfolder deletes ([InboxViewModel.subfolderIdsOf]),
     *  to tell a deleted folder from a hidden one (#89), and to name a row's folder and role. */
    val visibleMailboxes: List<Mailbox> = emptyList(),
    val refreshing: Boolean,
    val error: String?,
    /** Whether the refresh indicator is drawn — never a synonym of [refreshing], which is the truth
     *  ("a reconcile is running") and also empties the centre of the screen so the empty-folder
     *  scene cannot lie while the first fetch is out (#63). This field is the drawing decision
     *  alone, held back by [REFRESH_INDICATOR_GRACE_MS] and [REFRESH_INDICATOR_MIN_SHOW_MS] (#178). */
    val showRefreshIndicator: Boolean = false,
    /** Event-driven "no usable network" flag from [ConnectivityWatcher] (#65): true the moment
     *  WiFi/mobile drops, without waiting for a refresh to fail. Drives the offline banner. */
    val offline: Boolean = false,
    /** Inline search-on-the-list state. */
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchLoading: Boolean = false,
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    val unreadOnly: Boolean = false,
)

/** Intermediate holder so the search state can be folded into [MailUi] without a 6-arg combine. */
private data class Base(
    /** Folded in after the five-flow combine below, so it is seeded here and copied there. */
    val accountId: String? = null,
    val mailboxes: List<Mailbox>,
    /** Folded in after the five-flow combine below, from the setting's own flow. */
    val visibleMailboxes: List<Mailbox> = emptyList(),
    val selectedMailboxId: String?,
    val unified: Boolean,
    val unreadView: Boolean,
    /** Folded in after the five-flow combine below, so it is seeded here and copied there. */
    val unreadViewCount: Int = 0,
    val atInbox: Boolean,
    val accountName: String,
    val mailboxName: String,
    val unread: Int,
    val refreshing: Boolean,
    val error: String?,
    /** Folded in after the five-flow combine below, from [refreshIndicatorVisibility]. */
    val showRefreshIndicator: Boolean = false,
)

private data class SearchUi(
    val active: Boolean = false,
    val query: String = "",
    val results: List<Email>? = null,
    val loading: Boolean = false,
    /** False when the server leg stopped short — its cap, or an account that failed and was
     *  dropped. The count then says "at least N" (see [app.sterna.core.data.mail.MailSearchResult]). */
    val complete: Boolean = true,
)

/** Typing pause before the (unioned-in) server full-text search fires; local FTS has no debounce. */
private const val SERVER_SEARCH_DEBOUNCE_MS = 350L

/** Max hits requested from the server search (per query). */
private const val SERVER_SEARCH_LIMIT = 200

/**
 * The Undo window of a local removal: how long the message can be put back, and how long its
 * notification banner outlives the gesture. The only clock: the snackbar is `Indefinite` and is
 * taken down by this deadline clearing `_undo`. A bar with its own duration drifts from it, and the
 * drift ends with Undo pressed after the banner was already cancelled. Not a setting.
 */
private const val UNDO_BANNER_DISMISS_MS = 6_000L

/**
 * How many recent messages the "unread" view's pull-to-refresh looks at PER FOLDER
 * ([InboxViewModel.refreshUnreadScope]). Not the account's sync window, and the difference is the
 * heap: that refresh syncs N folders in one pass and its IMAP branch holds every folder's envelopes
 * until the loop ends. The cost: in a folder never opened, only the 50 newest are looked at.
 */
internal const val UNREAD_SCOPE_FOLDER_LIMIT = 50

/** The actions bound to the two swipe directions (from Settings → Reading). */
data class SwipeConfig(val right: SwipeAction, val left: SwipeAction)

/** What [InboxViewModel.swipeConfig] carries before DataStore has answered: nothing bound to either
 *  direction, so a swipe in that window is a gesture lost, never an action nobody asked for.
 * Not the repository's defaults: a second copy of them is free to drift. */
internal val SWIPE_CONFIG_UNLOADED = SwipeConfig(SwipeAction.NONE, SwipeAction.NONE)

/** One message that can be moved back to its original mailbox by an Undo. [mailboxId] is the
 *  SOURCE folder to restore to; [destMailboxId] is where the forward action put it (Trash /
 *  Archive / target), so the undo can reverse the drawer-count nudge (null = it was destroyed). */
data class UndoEntry(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
    val destMailboxId: String? = null,
)

/**
 * A reversible swipe action, surfaced as an "Undo" snackbar. Holds one entry for a single-message
 * swipe, or every message of a thread for a collapsed-conversation swipe.
 */
data class UndoAction(
    val entries: List<UndoEntry>,
    val label: String,
)

/** The saved-state keys of the reading pane's anchor (#103), the quintuplet of the `message/…` route. */
private const val PANE_EMAIL_ID = "pane.emailId"
private const val PANE_ACCOUNT_ID = "pane.accountId"
private const val PANE_SRC = "pane.src"
private const val PANE_INDEX = "pane.index"
private const val PANE_THREAD = "pane.thread"

/**
 * Built by the framework through `SavedStateViewModelFactory`. The handle is the "inbox" entry's
 * own and survives process death; it carries the reading pane's anchor (#103) and nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val storage = application.container.storageRepository
    private val settings = application.container.settingsRepository
    private val outbox = application.container.sendOutbox

    /** Undo-send: a message held in the outbox during its cancellable window. */
    val outboxPending: StateFlow<SendOutbox.Pending?> = outbox.pending
    fun undoSend() = outbox.undo()

    /** The draft handed back when the user undoes a send, waiting to reopen compose. A dedicated
     *  collector, not a call from the snackbar's Undo handler: `undoSend()` clears `outboxPending`,
     *  tearing down the very coroutine that would fire the reopen. */
    val restoredDraft: StateFlow<SendOutbox.ComposeDraft?> = outbox.restored

    /** Discreet badge: outbox items that failed, or that are still waiting a while after sending. */
    val outboxCount: StateFlow<Int> = repo.outboxActiveCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /** The count on the overflow menu's Outbox entry: what is in the Outbox now, with no delay
     *  (#70). Not [outboxCount], whose grace exists because the dot is always in view; during that
     *  grace the dot is absent while this reads 1. */
    val outboxQueuedCount: StateFlow<Int> = repo.outboxQueuedCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /** Whether any outbox item is parked as failed (drives the failure banner). Through
     *  [OutboxLogic.needsFailureBanner], never by testing a state here: a row saved as a draft on
     *  the phone (#95) is listed and counted, but nothing failed. */
    val outboxHasFailures: StateFlow<Boolean> = repo.outboxFlow()
        .map { items -> items.any { OutboxLogic.needsFailureBanner(it.state) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Configured swipe-right / swipe-left actions, observed for the list rows. */
    val swipeConfig: StateFlow<SwipeConfig> =
        combine(settings.swipeRightAction, settings.swipeLeftAction) { right, left ->
            SwipeConfig(right, left)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SWIPE_CONFIG_UNLOADED,
        )

    /** A just-performed swipe action that can be undone (move the message back). */
    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    /** The deadline that takes the CURRENT Undo action's banners down, cancelled by [undo]. */
    private var undoDepartureJob: Job? = null

    /** Non-null label while an "empty trash" purge is held back and can still be undone. */
    private val _pendingPurge = MutableStateFlow<String?>(null)
    val pendingPurge: StateFlow<String?> = _pendingPurge.asStateFlow()
    private var purgeJob: Job? = null
    /** The (accountId, Trash) whose purge is currently held back — the account keys the unique
     *  work, since same-server accounts can share a mailbox id. */
    private var pendingPurgeTarget: Pair<String, String>? = null

    /** Non-null label while a permanent (Trash) delete is held back and can still be undone. */
    private val _pendingDelete = MutableStateFlow<String?>(null)
    val pendingDelete: StateFlow<String?> = _pendingDelete.asStateFlow()
    private var pendingDeleteJob: Job? = null
    /** The messages whose permanent destroy is held back: account, email id, and the folder the
     *  message was in at confirmation — the destroy re-checks that against the server (#122). */
    private var pendingDeleteTargets: List<Triple<AccountCredentials, String, String>> = emptyList()

    /** The IMAP numbering (UIDVALIDITY) each of those MESSAGES was read under, keyed by (accountId,
     *  emailId) — the stamp its cached row carries, not the folder's current record (#99). Null or
     * missing means "nothing to oppose", which destroys nothing. Per message: the folder's record
     *  is realigned by any background pass that meets a renumbering, so opposed to itself it reads
     *  SAME. Keyed by account too — an email id is unique only inside its account (#31). */
    private var pendingDeleteNumbering: Map<Pair<String, String>, Long?> = emptyMap()

    /** The held-back messages themselves — kept so [undoDelete] can re-complete their threads,
     *  and so [completeThreadsAfterAction] never re-caches a row whose destroy is pending. */
    private var pendingDeleteEmails: List<Email> = emptyList()

    /** A transient message to surface in a snackbar (e.g. an action error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    // ---- Attachment chips: opening a file from the LIST, without opening the message ----

        /** Which attachment a tap is currently downloading, as [attachmentKey] spells it. ONE chip
         *  shows the progress, not every chip on the row and not a bar at the top of the screen: the
         *  thing the user touched is the thing that has to answer. Null when nothing is downloading. */
    private val _openingAttachment = MutableStateFlow<String?>(null)
    val openingAttachment: StateFlow<String?> = _openingAttachment.asStateFlow()

        /** A tapped attachment held back for an answer because it is large and this network charges
         *  for what it carries. Held with its resolved credentials so confirming cannot re-resolve
         *  them against an account list that changed while the dialog was up. */
    data class MeteredAttachment(
        val credentials: AccountCredentials,
        val email: Email,
        val part: EmailBodyPart,
        val name: String,
        val bytes: Long,
    )

    private val _meteredAttachment = MutableStateFlow<MeteredAttachment?>(null)
    val meteredAttachment: StateFlow<MeteredAttachment?> = _meteredAttachment.asStateFlow()

        /**
         * Open [part] of [email] in another application, WITHOUT opening the message.
         *
         * A chip lives in a scrolling list, so this begins by deciding whether to ask. The rule is
         * [DownloadLimits.needsMeteredConfirmation] and it is stated there: the tap itself is explicit
         * consent, so nothing is refused for being on mobile data -- but a LARGE file gets a question
         * first, because a brushed finger is a real way to arrive at a chip and the owner's data
         * allowance is a real cost. Under the threshold, or on an unmetered network, it just opens.
         */
    fun openAttachment(email: Email, part: EmailBodyPart) {
        if (_openingAttachment.value != null) return
        val credentials = credentialsFor(email) ?: return
        val app = getApplication<Application>()
        val name = part.name?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.message_attachment_fallback)
        if (DownloadLimits.needsMeteredConfirmation(part.size, AttachmentOpen.isMetered(app))) {
            _meteredAttachment.value = MeteredAttachment(credentials, email, part, name, part.size)
            return
        }
        download(credentials, email, part, name)
    }

    /** The answer to the metered question was yes. */
    fun confirmMeteredAttachment() {
        val pending = _meteredAttachment.value ?: return
        _meteredAttachment.value = null
        download(pending.credentials, pending.email, pending.part, pending.name)
    }

    /** The answer was no. Nothing is fetched and nothing is said -- the user just declined. */
    fun dismissMeteredAttachment() {
        _meteredAttachment.value = null
    }

    private fun download(
        credentials: AccountCredentials,
        email: Email,
        part: EmailBodyPart,
        name: String,
    ) {
        val app = getApplication<Application>()
        val key = attachmentKey(email, part)
        _openingAttachment.value = key
        viewModelScope.launch {
            try {
                // unguarded: `_openingAttachment` above is this call's leave guard, and it is
                // set before the launch and cleared in the `finally` below. It is checked at
                // the tap, which is the only place that can also refuse a second download.
                AttachmentOpen.openExternally(app, repo, storage, credentials, part, email.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: ContentTooLargeException) {
                // Our own ceiling, not a failure of theirs: say it plainly, without byte counts.
                _message.value = app.getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                // A tap that does nothing at all is the worst outcome here -- the user cannot tell a
                // dead chip from a slow one. The exception text is raw and English-only, so it goes
                // to logcat and the snackbar gets the named file.
                android.util.Log.w("SternaInbox", "opening attachment failed", t)
                _message.value = app.getString(R.string.status_open_attachment_failed, name)
            } finally {
                // Released when the chooser is up, not when the user comes back: the file is theirs
                // to open again as often as they like.
                _openingAttachment.value = null
            }
        }
    }

    /** A read/flag server write failed after the list showed the change optimistically. The
     *  optimistic patch is left in place; the next sync reconciles it. */
    private fun reportActionFailed(op: String, t: Throwable) {
        android.util.Log.w("SternaInbox", "$op failed", t)
        _message.value = getApplication<Application>().getString(R.string.status_action_failed)
    }

    // The id is staged on open and promoted to [highlightId] only when the list resumes, so the
    // flash plays on the way back, not under the opening message.
    private var pendingHighlightId: String? = null
    private val _highlightId = MutableStateFlow<String?>(null)
    val highlightId: StateFlow<String?> = _highlightId.asStateFlow()

    /** Stage the just-opened message's row to flash when the user returns to the list. */
    fun onEmailOpened(id: String) {
        pendingHighlightId = id
    }

    /** Promote the staged id when the list resumes (i.e. we're back from the message). */
    fun activatePendingHighlight() {
        pendingHighlightId?.let { _highlightId.value = it; pendingHighlightId = null }
    }

    fun clearHighlight() {
        _highlightId.value = null
    }

    // -- the reading pane beside the list, on a wide window (#103) --------------------------------

    /**
     * The message the pane shows, restored from the saved state after process death. The session
     * restarts at 0: there is no pager alive to keep.
     */
    private val _readingPane = MutableStateFlow(ReadingPaneState(anchor = readSavedAnchor(), session = 0))
    val readingPane: StateFlow<ReadingPaneState> = _readingPane.asStateFlow()

    /**
     * A tap on a row, on a wide window: the message opens in the pane instead of on a route. The
     * flash staged by [onEmailOpened] is dropped — the list never leaves the screen here.
     */
    fun openInPane(anchor: MessageAnchor) {
        setReadingPane(ReadingPaneRule.open(_readingPane.value, anchor))
        pendingHighlightId = null
    }

    /** The message a tapped notification posts in the pane — parked as well as opened. A
     *  notification for another account switches account first, and [onAccountChanged] empties the
     *  pane; the park puts this one message back, so the order of the host's effects stops mattering. */
    fun openFromNotification(anchor: MessageAnchor) {
        notificationAnchor = anchor
        openInPane(anchor)
    }

    /** The pane's pager settled on a page: the anchor — and the row painted current — follow it. */
    fun followPage(emailId: String, accountId: String?, index: Int) {
        setReadingPane(ReadingPaneRule.follow(_readingPane.value, emailId, accountId, index))
    }

    /**
     * Empties the pane; true if it was showing something. The boolean guards a second tap on
     * Archive / Delete / Move in the pane's bar. Never "the next message": the settle marks a
     * message read, and nothing may happen to a message nobody chose to open (`SECURITY.md`).
     */
    fun closePane(): Boolean {
        val had = _readingPane.value.anchor != null
        setReadingPane(ReadingPaneRule.close(_readingPane.value))
        return had
    }

    private fun setReadingPane(next: ReadingPaneState) {
        _readingPane.value = next
        persistAnchor(next.anchor)
    }

    /**
     * A switch of scope empties the pane: what was open belongs to the list it was opened in.
     * Not from a collector on [selection] — the restored selection is emitted at start-up and
     * would erase the restored anchor — and not on the folder a notification came from.
     */
    private fun paneOnViewChanged() {
        setReadingPane(ReadingPaneRule.onViewChanged(_readingPane.value))
    }

    /** Through [ReadingPaneRule.restored]: the message comes back alone, never with a pager on a list that restarts. */
    private fun readSavedAnchor(): MessageAnchor? {
        val emailId = savedState.get<String>(PANE_EMAIL_ID) ?: return null
        val saved = MessageAnchor.fromRoute(
            emailId = emailId,
            accountId = savedState[PANE_ACCOUNT_ID],
            index = savedState.get<Int>(PANE_INDEX) ?: 0,
            src = savedState[PANE_SRC],
            thread = savedState[PANE_THREAD],
        )
        return ReadingPaneRule.restored(saved)
    }

    /** Five keys for the five fields; an empty pane removes them all, so nothing stale is restored. */
    private fun persistAnchor(anchor: MessageAnchor?) {
        if (anchor == null) {
            for (key in listOf(PANE_EMAIL_ID, PANE_ACCOUNT_ID, PANE_SRC, PANE_INDEX, PANE_THREAD)) {
                savedState.remove<Any>(key)
            }
            return
        }
        savedState[PANE_EMAIL_ID] = anchor.emailId
        savedState[PANE_ACCOUNT_ID] = anchor.accountId
        savedState[PANE_SRC] = anchor.src
        savedState[PANE_INDEX] = anchor.index
        savedState[PANE_THREAD] = anchor.thread
    }

    /**
     * Rows whose dismissing swipe was played to the edge and whose server write then failed —
     * account-qualified keys, never bare ids (#92). The row is gone from the screen but the list key
     * is stable, so [refresh] re-emits it into the SAME composition, still parked off screen; it
     * clears its own key through [swipeRewound]. Also fed by [swipeRemove] for the reader's own
     * move/archive/delete (#73), where no row ever flew, so every value put back must be idempotent.
     */
    private val _swipeRewind = MutableStateFlow<Set<EmailKey>>(emptySet())
    val swipeRewind: StateFlow<Set<EmailKey>> = _swipeRewind.asStateFlow()

    /** The row [key] is back at rest — forget it, or it rewinds again on every recomposition. */
    fun swipeRewound(key: EmailKey) {
        _swipeRewind.value = _swipeRewind.value - key
    }

    // ---- inline conversation expansion ----

    /** The conversations currently unfolded inline, as (account, thread) keys — see [ThreadKey]: in
     *  the unified inbox two accounts can carry the same thread id. Kept here, not in the paged
     *  list, so a Paging snapshot swap doesn't reset what's open. */
    private val _expandedThreads = MutableStateFlow<Set<ThreadKey>>(emptySet())
    val expandedThreads: StateFlow<Set<ThreadKey>> = _expandedThreads.asStateFlow()

    /**
     * The whole membership of each expanded thread — the thread's messages in the viewed folder(s)
     * plus its Sent replies, newest-first: what the row's chip counts. Folder-scoped by design: a
     * member deleted to Trash leaves THIS conversation. A live reading of the local cache, kept in
     * step by [observeThreadMembers], never a snapshot taken at the tap. No network on this path.
     */
    private val _threadMembers = MutableStateFlow<Map<ThreadKey, List<Email>>>(emptyMap())
    val threadMembers: StateFlow<Map<ThreadKey, List<Email>>> = _threadMembers.asStateFlow()

    /** Thread keys already completed from the server this session — fetched at most once each. */
    private val completedThreads = mutableSetOf<ThreadKey>()

    /**
     * The conversation a message was opened FROM, as the reading view's swipe context: the messages
     * the unfolded row showed at the tap, in that order. Recorded by [recordThreadOrder], never
     * rebuilt from a representative remembered since the unfold — the row re-picks it on every write.
     */
    private val threadOrder = ThreadOrders()

    /**
     * Record what the unfolded [key] row is showing — [representative] on the row, [members]
     * beneath it — as the swipe context of a message opened from it, and answer with that order.
     */
    fun recordThreadOrder(key: ThreadKey, representative: Email, members: List<Email>): List<Pair<String, String?>> =
        threadOrder.record(key, representative, members)

    /**
     * The conversation a message was opened from. Empty when the thread is unknown — the reader
     * then falls back to showing the single message it was given.
     */
    fun threadEntries(key: ThreadKey): List<Pair<String, String?>> = threadOrder.entries(key)

    /** The conversation an email belongs to: its account plus its threadId (or its own id when
     *  thread-less). Account-qualified — see [ThreadKey]. */
    fun threadKeyOf(email: Email): ThreadKey =
        // Grouped by sender, the group is the address (the SQL's key), not the thread; by domain, the domain.
        SenderDomain.domainKey(email.from.firstOrNull()?.email).takeIf { listView.value.group == GroupMode.DOMAIN }
            ?.let { ThreadKey(email.accountId, it) }
            ?: ListShape.senderKey(email.from.firstOrNull()?.email)?.takeIf { listView.value.group == GroupMode.SENDER }
                ?.let { ThreadKey(email.accountId, it) }
            ?: ConversationExpansion.threadKey(email.accountId, email.threadId, email.id)

    /** Fold/unfold a conversation row in place. The cached members render at once (offline-safe) and
     *  stay live while the row is open ([observeThreadMembers]); for JMAP threads a background
     *  Thread/get also fills the cache with messages outside the folder's short window. */
    fun toggleThreadExpanded(rep: Email) {
        val key = threadKeyOf(rep)
        if (key in _expandedThreads.value) {
            _expandedThreads.value = _expandedThreads.value - key
            return
        }
        _expandedThreads.value = _expandedThreads.value + key
        viewModelScope.launch { expandThread(rep, key) }
    }

    /**
     * The server completion of a freshly unfolded conversation. JMAP only (IMAP has no Thread/get),
     * once per thread. It fetches for its PERSISTENCE: members outside the folder's short sync
     * window are written into the cache and the observed query picks them up.
     */
    private suspend fun expandThread(rep: Email, key: ThreadKey) {
        if (key in completedThreads) return
        val threadId = rep.threadId ?: return
        val credentials = credentialsFor(rep) ?: return
        // The folder hint that decides where a multi-mailbox member is filed: the folders the list
        // was built with.
        val fetched = runCatching { repo.fetchThreadMembers(credentials, threadId, listScope.value.viewedMailboxIds) }
            .getOrDefault(emptyList())
        if (fetched.isEmpty()) return // offline/failed — not completed, so a later expand retries
        completedThreads += key
    }

    /** Keep [_threadMembers] equal to what the cache says the unfolded conversations hold — live, on
     *  the same folders and write the chips are counted from ([ThreadMemberStream]). Started once,
     *  for the ViewModel's life: idle while nothing is unfolded. */
    private fun observeThreadMembers() {
        viewModelScope.launch {
            ThreadMemberStream.members(
                expanded = _expandedThreads,
                scope = listScope,
                // The snooze table, observed — not asked once per emission: the members are read
                // from `emails` alone, so nothing there notices a snooze starting or lapsing.
                snoozed = repo.observeActiveSnoozed(),
                fallbackAccountId = { store.load()?.id },
                read = { accountId, folders, threadKey -> repo.observeThreadEmails(accountId, folders, threadKey) },
            ).collect { live -> drawThreadMembers(live) }
        }
    }

    /**
     * Draw a live reading: membership from the cache, content from what is already on screen, minus
     * the members a call in flight has taken away ([ThreadMemberStream.reconcile], [maskedMembers]).
     */
    private fun drawThreadMembers(live: Map<ThreadKey, List<Email>>) {
        val drawn = _threadMembers.value
        val next = live.mapValues { (key, members) ->
            ThreadMemberStream.reconcile(drawn = drawn[key].orEmpty(), live = members, removed = maskedMembers.keys)
        }
        // Equal maps are not re-emitted (StateFlow conflates on equality) and the reconcile keeps
        // the drawn instances, so an unfolded conversation nothing happened to does not recompose.
        _threadMembers.value = next
    }

    /** The members currently masked from that live reading, and the only way to mask one: see
     *  [ThreadMemberMask]. Raised for the length of the call that removes a member and lowered by
     *  the same call, so no path can leave a message buried. */
    private val maskedMembers = ThreadMemberMask()

    /**
     * Take [keys] off the unfolded rows for the length of [op] — the window in which the gesture has
     * already flown the row away while its cache row would put it straight back.
     *
     * Taking the mask down is not a redraw: a member is listed again only on the next write to the
     * message table. Putting it back from here would re-insert held copies into a list the cache owns.
     */
    private suspend fun <T> hidingThreadMembers(keys: Set<EmailKey>, op: suspend () -> T): T {
        if (_threadMembers.value.isNotEmpty()) {
            _threadMembers.value = _threadMembers.value
                .mapValues { (_, members) -> members.filterNot { it.emailKey() in keys } }
                .filterValues { it.isNotEmpty() }
        }
        return maskedMembers.hiding(keys, op)
    }

    /** Toggle the favourite star on one message inside an expanded conversation. The write reaches
     *  the cache only once the server acknowledged it, so the new state goes into [_threadMembers]
     *  at once ([ThreadMemberStream.reconcile] keeps the drawn copy meanwhile). */
    fun toggleChildFlag(child: Email) {
        val flagged = !child.isFlagged
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                // Account-qualified: in the unified view two accounts' conversations can hold the
                // same JMAP id (#92).
                if (m.emailKey() != child.emailKey()) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (flagged) put("\$flagged", true) else remove("\$flagged")
                    },
                )
            }
        }
        viewModelScope.launch {
            val credentials = credentialsFor(child) ?: return@launch
            runCatching { repo.setFlagged(credentials, child.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (thread child)", it) }
        }
    }

    /** Optimistically rewrite the seen keyword on any expanded-conversation members among [keys].
     *  The cache is written only on the server's ack, so every read/unread mutation must be written
     *  here — and into the search-results snapshot — or those rows keep a stale state. */
    private fun patchThreadMembersSeen(keys: Set<EmailKey>, seen: Boolean) {
        patchSearchResults(keys) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (seen) put("\$seen", true) else remove("\$seen")
                },
            )
        }
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                if (m.emailKey() !in keys) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (seen) put("\$seen", true) else remove("\$seen")
                    },
                )
            }
        }
    }

    // ---- search-results snapshot patching ----
    // The results are a static snapshot: every removal must be written back into it or the swiped
    // row stays frozen on screen until search is left.

    /** Results removed by an action, with their position, so an Undo can put them back. Doubles as
     *  a tombstone set: the FTS index outlives evicted/deleted cache rows, so later crawl/server
     *  merges must not resurrect a row the user just removed. Account-qualified [EmailKey] (#31). */
    private val searchRemoved = mutableMapOf<EmailKey, IndexedValue<Email>>()

    /** Remove [keys] from the search-results snapshot, stashing them for a possible Undo. */
    private fun dropSearchResults(keys: Set<EmailKey>) {
        val results = searchState.value.results ?: return
        val remaining = ArrayList<Email>(results.size)
        results.forEachIndexed { index, email ->
            if (email.emailKey() in keys) searchRemoved[email.emailKey()] = IndexedValue(index, email)
            else remaining += email
        }
        if (remaining.size != results.size) searchState.value = searchState.value.copy(results = remaining)
    }

    /** Undo: put the stashed entries among [keys] back at (best-effort) their original position. */
    private fun restoreSearchResults(keys: Collection<EmailKey>) {
        val entries = keys.mapNotNull { searchRemoved.remove(it) }.sortedBy { it.index }
        if (entries.isEmpty()) return
        val restored = searchState.value.results?.toMutableList() ?: return
        entries.forEach { (index, email) -> restored.add(index.coerceAtMost(restored.size), email) }
        searchState.value = searchState.value.copy(results = restored)
    }

    /** Optimistically rewrite any search-result rows among [keys] (read state, star). */
    private fun patchSearchResults(keys: Set<EmailKey>, transform: (Email) -> Email) {
        val results = searchState.value.results ?: return
        if (results.none { it.emailKey() in keys }) return
        searchState.value = searchState.value.copy(
            results = results.map { if (it.emailKey() in keys) transform(it) else it },
        )
    }

    /** Re-sync the expanded conversations' members with the cache. The live reading keeps the copy
     *  already drawn, so a change made OUTSIDE this ViewModel — chiefly the reader marking a child
     *  read — needs saying explicitly, or the unfolded row keeps a stale unread dot. */
    fun refreshThreadMembers() {
        val snapshot = _threadMembers.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val keys = snapshot.values.flatten().mapTo(mutableSetOf()) { it.emailKey() }
            val fresh = repo.cachedEmailsByIds(keys).associateBy { it.emailKey() }
            _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
                members.map { m -> fresh[m.emailKey()]?.let { f -> m.copy(keywords = f.keywords) } ?: m }
            }
        }
    }

    /**
     * The view on screen. Seeded from what was stored last time ([restoredSelection]), falling back
     * on the current account's Inbox whenever the memory is empty, another account's, or names a
     * view this install does not offer. Through [restoredSelection], never a store read written
     * out here: `RootViewModel` needs the same answer before this ViewModel exists at all.
     */
    private val selection = MutableStateFlow<Sel>(
        restoredSelection(store) ?: Sel.Folder(store.inboxMailboxId()),
    )
    private val currentAccountId = MutableStateFlow(store.currentId())
    private val unifiedInboxScopes = MutableStateFlow(store.allInboxScopes())

    /** Whether the CURRENT account hides the folders it is not subscribed to (#174). Off until one
     *  ticks the box. Hung off `accountsFlow` — the single point every account write is published
     *  from — so ticking the box redraws the drawer without a restart. */
    private val showOnlySubscribed: StateFlow<Boolean> =
        combine(store.accountsFlow, currentAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The last folder list [mailboxes] delivered, WITH the account it belonged to — raw, unfiltered.
     * [mailboxes] carries side effects (#91, #89) and must be collected ONCE, so the setting
     * cannot be combined into it. The account travels WITH the list: re-reading
     * `currentAccountId.value` at derivation time pairs the new account with the old ids (#121/#31).
     */
    private val folderSnapshot = MutableStateFlow<Pair<String?, List<Mailbox>>>(null to emptyList())

    /**
     * The folders the "unread" view ([Sel.Unread]) pages — the current account's VISIBLE ones,
     * minus Trash, Junk/Spam, Sent and Drafts ([visibleFolders], [unreadViewScopes]). Starts EMPTY:
     * the store knows the account, not its folder list, and an empty scope list is what
     * [MailRepository.pagedMailbox] answers `PagingData.empty()` to.
     */
    private val unreadScopes: StateFlow<List<Pair<String, String>>> =
        combine(folderSnapshot, showOnlySubscribed) { (accountId, folders), onlySubscribed ->
            unreadViewScopes(accountId, visibleFolders(folders, onlySubscribed))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Back to the Inbox when the folder ON SCREEN stops being reachable — DELETED (#89), or hidden
     * by "show only subscribed folders" (#174). The decision is [selectionIsUnreachable]'s, and it
     * needs the WHOLE list plus the flag to tell a folder taken away from one nobody has loaded yet.
     * Derived from [folderSnapshot], never a second collection of [mailboxes], whose side effects
     * (#91) a second collector would replay on every tick of the box.
     */
    private val inboxFallback: Job =
        combine(folderSnapshot, showOnlySubscribed) { (_, folders), onlySubscribed ->
            selectionIsUnreachable((selection.value as? Sel.Folder)?.id, folders, onlySubscribed)
        }.onEach { unreachable -> if (unreachable) showInbox() }
            .launchIn(viewModelScope)

    /**
     * The header the list opens on, for the view [selection] was RESTORED to ([restoredMeta]).
     * Reads `selection.value`, so it must stay declared AFTER [selection]: it is a plain
     * initialiser and the order is the whole guarantee it is not null here.
     */
    private val meta = MutableStateFlow(
        restoredMeta(
            selection.value,
            store.inboxMailboxId(),
            store.accountLabel(),
            store.inboxMailboxName(),
            store.unreadCount(),
            store.totalUnreadCount(),
        ),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    /**
     * The clock every refresh-indicator and freshness rule reads.
     *
     * `elapsedRealtime`, pinned by `RefreshIndicatorWiringLintTest`. `System.currentTimeMillis` is
     * a WALL clock a time correction moves while the scheduling `delay` is monotonic; `nanoTime`
     * turns the 250 ms grace into 250 ns. `elapsedRealtime` counts through sleep and survives a cold
     * start, which the freshness register needs (#178).
     */
    private val clock: () -> Long = SystemClock::elapsedRealtime

    /** The stretch of refreshing currently on the screen's mind — see [RefreshRun]. */
    private val refreshRun = MutableStateFlow<RefreshRun?>(null)

    /** Set by [refreshRequested] and consumed by the very next [refresh], so the reader's own pull
     *  or Retry is drawn with no grace. A one-shot flag, not a parameter on [refresh]: every other
     *  caller would have to spell `instant = false`, and one pasted `true` brings the blink back. */
    private var refreshAnswersAGesture = false

    /**
     * A folder a tapped notification asked the list to show, as (accountId, mailboxId, emailId),
     * waiting for that account's folder list. [applyNotificationFolder] consumes it either way, so
     * it can never resurface and yank the user out of a folder they picked. The message id is what
     * [ReadingPaneRule.onFolderFromNotification] judges the pane against (#103) — read from here,
     * not from [notificationAnchor], which the account switch spends.
     */
    private var notificationFolder: Triple<String, String, String>? = null

    /**
     * The message a tapped notification posted in the reading pane, kept so the account switch that
     * same notification asks for can put it back ([openFromNotification], [onAccountChanged]).
     * A park for ONE switch that nothing reads as a flag: it is spent by the time the folder list
     * arrives, and [applyNotificationFolder] judges the pane against its own message id (#103).
     */
    private var notificationAnchor: MessageAnchor? = null

    /** The RESTORED folder whose name [restoredMeta] left blank, waiting for a folder list to name
     *  it — the cache's list, so this lands in airplane mode too. Consumed by [applyRestoredMeta],
     *  so it can never resurface and retitle a folder the reader chose herself. */
    private var restoredFolder: String? =
        (selection.value as? Sel.Folder)?.id?.takeIf { it != store.inboxMailboxId() }

    // The drawer shows the CURRENT account's folders, so the flow re-scopes on an account switch.
    // SIDE EFFECTS, collected exactly once — a second collector replays them. Everything here is
    // judged when a folder LIST ARRIVES, which is why the Inbox fallback hangs off [inboxFallback].
    private val mailboxes = currentAccountId.flatMapLatest { accountId ->
        if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
    }.onEach { folders ->
        // #91: a notification for a message in another folder parks its request until the folder
        // list it is judged against exists. Handed the WHOLE list ([notificationFolderToShow]).
        applyNotificationFolder(folders)
        // The restored view's missing folder name, from this cached list. AFTER the line above:
        // a tapped notification already wrote the header, and [restoredFolderMeta] lets it win.
        applyRestoredMeta(folders)
        // Recorded for what derives from it: [unreadScopes] and [inboxFallback].
        folderSnapshot.value = currentAccountId.value to folders
    }

    /**
     * (account, folder) → role, for EVERY configured account — what the rows of an unfolded
     * conversation are judged by (#115). Those rows span the viewed folder(s) plus Sent and, in the
     * unified list, other accounts, so neither [mailboxes] nor the selected folder's role answers.
     *
     * Never `replayExpirationMillis = 0`: the map would restart at `emptyMap` on the way back to
     * the screen and the phone's own drafts would blink out of the list. Pinned by a source lint.
     */
    val folderRoles: StateFlow<Map<Pair<String, String>, String>> =
        folderRolesFlow(store.accountsFlow) { accountIds -> repo.observeFolderRoles(accountIds) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

    /** Seeds the local full-text index from cache when a search opens; the first query joins it. */
    private var indexJob: Job? = null

    /** Background crawl of the whole mailbox into the index; re-runs the query when it completes. */
    private var crawlJob: Job? = null

    /**
     * This view's choices (group, rank, filters), per account and folder, kept in [ListViewPrefs].
     * [listViewKey] names the view on screen; [viewOverrides] holds what was written this run so a
     * tap shows at once, and anything not in it is read from the preferences once.
     */
    private val listViewKey: Flow<String> = combine(selection, currentAccountId) { sel, account ->
        when (sel) {
            is Sel.Folder -> ListViewPrefs.keyFor(account, sel.id)
            Sel.Unified -> ListViewPrefs.keyFor(null, "unified")
            Sel.Unread -> ListViewPrefs.keyFor(account, "unread")
        }
    }.distinctUntilChanged()
    private val viewOverrides = MutableStateFlow<Map<String, ListView>>(emptyMap())
    private var currentViewKey: String = ""

    private fun viewFor(key: String, overrides: Map<String, ListView>): ListView =
        overrides[key] ?: ListViewPrefs.load(getApplication<Application>(), key)

    /** The choices of the view on screen. */
    internal val listView: StateFlow<ListView> =
        combine(listViewKey, viewOverrides) { key, overrides -> currentViewKey = key; viewFor(key, overrides) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListView())

    private fun updateView(change: (ListView) -> ListView) {
        val key = currentViewKey
        val next = change(viewFor(key, viewOverrides.value))
        ListViewPrefs.save(getApplication<Application>(), key, next)
        viewOverrides.value = viewOverrides.value + (key to next)
    }

    internal fun setGroupMode(mode: GroupMode) = updateView { it.copy(group = mode) }
    internal fun setRankMode(mode: RankMode) = updateView { it.copy(rank = mode) }
    internal fun toggleListFilter(filter: ListFilter) = updateView { it.toggled(filter) }

    /** Reset to defaults: forget this folder's saved choices and show it on the declared defaults. */
    internal fun resetListView() {
        val key = currentViewKey
        ListViewPrefs.reset(getApplication<Application>(), key)
        viewOverrides.value = viewOverrides.value + (key to ListView())
    }

    /**
     * The Inbox's All / Alerts / no-Alerts counts (null in any other folder): three live counts from the
     * list's own WHERE, so the owner can check that Alerts + no-Alerts = All.
     */
    private val alertCounts: Flow<AlertCounts?> =
        combine(selection, currentAccountId) { sel, account ->
            val id = (sel as? Sel.Folder)?.id
            if (account != null && id != null && id == store.inboxMailboxId()) account to id else null
        }.distinctUntilChanged().flatMapLatest { scope ->
            if (scope == null) flowOf<AlertCounts?>(null) else repo.observeAlertCounts(listOf(scope), ListViewDefaults.alertRules)
        }

    /** What the top bar's three icon groups and the overflow show. */
    internal val listViewUi: StateFlow<ListViewUi> =
        combine(listView, settings.conversationView, settings.sortOrder, alertCounts) { view, conversation, sort, counts ->
            ListViewUi.of(view, conversation, sort, counts)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ListViewUi())

    /** The sort the pager uses: this view's rank, else the global setting. */
    private val effectiveSort: Flow<SortOrder> =
        combine(listView, settings.sortOrder) { view, global -> view.effectiveSort(global) }.distinctUntilChanged()

    /** Grouped at all (by subject or by sender), else the flat list. */
    private val effectiveConversation: Flow<Boolean> =
        combine(listView, settings.conversationView) { view, global -> view.effectiveGroup(global) != GroupMode.NONE }
            .distinctUntilChanged()

    private val effectiveShape: Flow<ListShape> = listView.map { it.shape() }.distinctUntilChanged()

    /** The unread filter of the view on screen (one of the four filters). */
    private val unreadOnly: StateFlow<Boolean> =
        listView.map { ListFilter.UNREAD in it.filters }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Multi-select mode: which messages are selected (empty + inactive = off). Account-qualified
     *  keys, not bare ids: the unified inbox can show two accounts' same-id rows, and a bare-id
     *  selection would silently cover — and act on — both (issue #31). */
    private val _selectedKeys = MutableStateFlow<Set<EmailKey>>(emptySet())
    val selectedKeys: StateFlow<Set<EmailKey>> = _selectedKeys.asStateFlow()
    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    /**
     * The IMAP numbering the row of each selected key carried AT THE MOMENT OF THE TICK (#99): an
     * IMAP key does not say which numbering it belongs to, so a renumbering server makes the next
     * walk upsert that key with another message's envelope, on screen already ticked. Written from
     * ONE place, the collector on `_selectedKeys`. Null and absent both mean "no numbering to
     * offer" and neither is evidence of a swap, so the upstream split refuses none; the cross-account
     * move wraps the value in `FrozenNumbering.Frozen(...)`, refused at both ends (#189).
     */
    private val tickedUnderFlow = MutableStateFlow<Map<EmailKey, Long?>>(emptyMap())

    /** The same field, as the rest of this class writes and reads it. A property over
     *  [tickedUnderFlow], not a plain `var`: the selection gestures run in another coroutine from
     *  the collector that fills it, so they must WAIT for a write instead of sampling. */
    private var tickedUnder: Map<EmailKey, Long?>
        get() = tickedUnderFlow.value
        set(value) { tickedUnderFlow.value = value }

    /** True when every selected message is already read — drives the read/unread toggle's icon. */
    private val _selectionAllRead = MutableStateFlow(false)
    val selectionAllRead: StateFlow<Boolean> = _selectionAllRead.asStateFlow()

    /**
     * The account the current selection belongs to: a single account, or null when empty or spanning
     * accounts. The move-to-folder picker offers THIS account's folders — mailbox ids collide across
     * same-server accounts, so the active account's id would move the message nowhere (#73).
     */
    private val selectionAccountId: StateFlow<String?> = _selectedKeys
        .map(::selectionAccount)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The account the selection's move-to-folder picker BELONGS to — the selection's, falling back
     *  to the active account's. Its folders are the ones listed unless another account is chosen on
     *  the picker's account row (#189). */
    val moveOwnerAccountId: StateFlow<String?> =
        combine(selectionAccountId, currentAccountId) { selId, curId -> selId ?: curId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The account chosen on the picker's account row (#189), null for the owner's. Held here so it
     * survives a rotation, which destroys the sheet. Nothing reads this field directly;
     *  everything reads [moveAccountId], which drops the choice as soon as the selection no longer
     *  resolves a single account. */
    private val _moveAccountId = MutableStateFlow<String?>(null)

    /** The choice as the picker and the gesture must see it: neutralised on a selection that spans
     *  accounts. The ONE place it is neutralised, so what the picker lists and where the tap goes
     *  cannot answer differently from the account row. */
    val moveAccountId: StateFlow<String?> =
        combine(_moveAccountId, _selectedKeys) { chosen, keys -> selectionMoveAccount(chosen, keys) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun chooseMoveAccount(id: String?) {
        _moveAccountId.value = id
    }

    /** The account whose folders the picker LISTS ([pickerAccount]): the chosen one, else the owner. */
    private val movePickerAccountId: StateFlow<String?> =
        combine(moveAccountId, moveOwnerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Folders the move-to-folder picker offers: ONE account's — [movePickerAccountId]'s — never a
     *  mix, because mailbox ids collide between accounts of the same server (#92). */
    val selectionMailboxes: StateFlow<List<Mailbox>> =
        movePickerAccountId.flatMapLatest { accountId ->
            if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the picker above must hide unsubscribed folders — the setting of the SAME account
     * [selectionMailboxes] is scoped to, never [showOnlySubscribed] (#92, #174, #189). Beside the
     * list rather than folded into it: [moveTargets] filters only the targets, and resolves the
     * parent paths shown under them against the whole list (#109).
     */
    val selectionOnlySubscribed: StateFlow<Boolean> =
        combine(movePickerAccountId, store.accountsFlow) { accountId, accounts -> showOnlySubscribedFor(accountId, accounts) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The browse list, paged from Room. A single folder uses the RemoteMediator-backed pager; the
     * unified inbox pages cached rows only. The key comes from [pageKeyFlow], which is deduped:
     * every emission cancels the running pager and restarts at the first page, so an equal key must
     * not reach `flatMapLatest`. [cachedIn] caches the pages; it does not stop a rebuild.
     */
    val pagedEmails: Flow<PagingData<InboxRow>> =
        pageKeyFlow(
            selection = selection,
            unifiedInboxScopes = unifiedInboxScopes,
            unreadViewScopes = unreadScopes,
            sortOrder = effectiveSort,
            unreadOnly = unreadOnly,
            conversationView = effectiveConversation,
            currentAccountId = currentAccountId,
            listShape = effectiveShape,
        )
        .flatMapLatest { key ->
            when (val sel = key.sel) {
                is Sel.Folder -> {
                    val id = sel.id
                    val credentials = store.load()
                    if (id == null || credentials == null) {
                        flowOf(PagingData.empty())
                    } else {
                        // Conversation chips also count the thread's Sent replies, resolved
                        // reactively from the folder cache as account-pinned pairs.
                        sentScopes(key, listOf(credentials.id)).flatMapLatest { sent ->
                            repo.pagedFolder(credentials, id, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent, key.shape)
                        }
                    }
                }
                Sel.Unified -> {
                    // The accounts the key was built from, not a fresh read of the store: the
                    // pager's rows and its Sent scope must describe the same set of accounts.
                    sentScopes(key, key.unifiedScopes.map { it.first }.distinct()).flatMapLatest { sent ->
                        repo.pagedMailbox(key.unifiedScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent, key.shape)
                    }
                }
                Sel.Unread -> {
                    // The unread filter is [listUnreadOnly]'s answer, not the key's raw toggle:
                    // this scope forces it on (WYSIWYG). All three branches and [selectAll] ask the
                    // SAME function — a filter decided twice is the destructive half of #126.
                    sentScopes(key, listOfNotNull(key.accountId)).flatMapLatest { sent ->
                        repo.pagedMailbox(key.unreadScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent, key.shape)
                    }
                }
            }
        }.cachedIn(viewModelScope)

    /**
     * What `local_drafts` contributes to the list on screen — empty everywhere but this account's
     * Drafts folder (#95). The role comes from [folderRoles], not [mailboxes] and its `onEach`
     * side effects. A `StateFlow` because [selectAll] READS IT AT THE TAP: a bulk action counting
     * the hidden server rows would delete a message never on screen (#126).
     *
     * NEVER add `replayExpirationMillis = 0`: the cache would drop back to an empty
     * [LocalDraftRows], and a [selectAll] in the re-subscription window subtracts nothing.
     */
    private val localDraftRows: StateFlow<LocalDraftRows> =
        localDraftRowsFlow(
            currentAccountId = currentAccountId,
            selectedMailboxId = selection.map { (it as? Sel.Folder)?.id },
            folderRoles = folderRoles,
            unreadOnly = unreadOnly,
            localDrafts = { accountId -> repo.observeLocalDrafts(accountId) },
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDraftRows())

    /**
     * What the LIST screen pages, and nothing else may: [pagedEmails] with the phone's own drafts
     * on top and the server copies they replace taken out (see [withLocalDrafts]).
     * `MessageScreen` keeps paging [pagedEmails]: a local-draft id in its pager is a page that
     * cannot load and a swipe-delete announcing a deletion IMAP never performed.
     */
    val pagedListRows: Flow<PagingData<InboxRow>> =
        combine(pagedEmails, localDraftRows) { paged, local -> withLocalDrafts(paged, local) }

    /** The accounts' Sent folders backing the chip's "plus Sent replies" scope — live from the
     *  folder cache in conversation mode, none in flat mode. Kept in [listScope] with the folder(s)
     *  [key] pages: unfolding a row must reuse what that row's chip counted. The viewed folders come
     *  from the paging key, never from the selection. */
    private fun sentScopes(key: PageKey, accountIds: List<String>): Flow<List<Pair<String, String>>> =
        (if (key.conversationView && !key.shape.bySenderOrDomain) repo.observeSentMailboxes(accountIds) else flowOf(emptyList<Pair<String, String>>()))
            .onEach { listScope.value = ListScope(viewedMailboxIds(key), it) }

    /** The folder(s) [key] pages — one folder, or every account's inbox when unified. Bare ids
     *  here on purpose: this feeds the unfold's folder set ([ConversationScope.folders]), which
     *  is account-pinned by its own caller; the LIST's scope keeps the account (see [PageKey]). */
    private fun viewedMailboxIds(key: PageKey): List<String> = when (val sel = key.sel) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> key.unifiedScopes.map { it.second }.distinct()
        Sel.Unread -> key.unreadScopes.map { it.second }.distinct()
    }

    /**
     * The scope the list on screen was built with — the folders it pages and the Sent pairs its
     * chips counted over. Recorded by [sentScopes], read back by [observeThreadMembers], so the
     * unfold and the chip describe one conversation. A StateFlow: "the resolution changed while a
     * row was open" is the case a snapshot could not answer.
     */
    private val listScope = MutableStateFlow(ListScope())

    // "All inboxes (N)" sums the SAME live per-inbox aggregates the folder badges read, so the two
    // drawer numbers agree for JMAP; IMAP inboxes keep contributing their stored server counter.
    private val unifiedUnread = repo.observeUnifiedInboxUnread()

    /** #65: the offline empty state promises a resync, so watch the network — the transports *and*
     *  the route this app is given, so a VPN tunnel coming back counts — and re-run the current
     *  view's refresh once connectivity returns. Declared before [state]. */
    private val connectivity = ConnectivityWatcher(application) { onReconnected() }
    private var reconnectJob: Job? = null

    private val baseState = combine(mailboxes, selection, meta, status, unifiedUnread) { mailboxes, sel, meta, status, unifiedUnread ->
        Base(
            mailboxes = mailboxes,
            selectedMailboxId = (sel as? Sel.Folder)?.id,
            unified = sel is Sel.Unified,
            unreadView = sel is Sel.Unread,
            // The rule lives in [isAtInbox] and is not restated here: atInbox true means the
            // system Back gesture LEAVES THE APP.
            atInbox = isAtInbox(sel) { store.inboxMailboxId() },
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unread = if (sel is Sel.Unified) unifiedUnread else meta.unread,
            refreshing = status.refreshing,
            error = status.error,
        )
    }

    /**
     * The drawer's unread badge, folded into [baseState] rather than combined into [state] (the
     * typed `combine` stops at five flows). The folder list comes back out of [Base] and is NOT
     * collected again — [mailboxes] carries side effects (#91, #89) — which is also why the
     * subscription filter (#174) applies here, into a SECOND field: the drawer draws
     * [MailUi.visibleMailboxes], everything else the whole [MailUi.mailboxes]. Not "the number of
     * rows you will see": a thread unread in two folders is summed twice and drawn once.
     */
    private val indicatorShowing = refreshIndicatorVisibility(refreshRun, clock)

    private val badgedState = combine(baseState, unreadScopes, currentAccountId, showOnlySubscribed, indicatorShowing) { base, scopes, accountId, onlySubscribed, showing ->
        base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, onlySubscribed), showRefreshIndicator = showing)
    }

    val state: StateFlow<MailUi> = combine(badgedState, searchState, settings.sortOrder, unreadOnly, connectivity.online) { base, search, sortOrder, unreadOnly, online ->
        MailUi(
            accountId = base.accountId,
            accountName = base.accountName,
            mailboxName = base.mailboxName,
            unreadCount = base.unread,
            selectedMailboxId = base.selectedMailboxId,
            unified = base.unified,
            unreadView = base.unreadView,
            unreadViewCount = base.unreadViewCount,
            atInbox = base.atInbox,
            searchResults = search.results.orEmpty(),
            searchComplete = search.complete,
            mailboxes = base.mailboxes,
            visibleMailboxes = base.visibleMailboxes,
            refreshing = base.refreshing,
            error = base.error,
            showRefreshIndicator = base.showRefreshIndicator,
            offline = !online,
            searching = search.active,
            searchQuery = search.query,
            searchLoading = search.loading,
            sortOrder = sortOrder,
            unreadOnly = unreadOnly,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUi(
            accountName = meta.value.accountName,
            mailboxName = meta.value.mailboxName,
            unreadCount = meta.value.unread,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unified = selection.value is Sel.Unified,
            // NOT the field's default: this placeholder is a SECOND SITE of every rule the combine
            // above states. Left out, a RESTORED unread view starts on `unreadView = false`.
            unreadView = selection.value is Sel.Unread,
            atInbox = isAtInbox(selection.value) { store.inboxMailboxId() },
            mailboxes = emptyList(),
            refreshing = true,
            // Spelled out, not left to the field's default. `refreshing = true` above is the TRUTH
            // (#63); this is the DISPLAY, which must not light before the grace passed (#178).
            showRefreshIndicator = false,
            error = null,
            offline = !connectivity.online.value,
        ),
    )

    init {
        // Backfill the "G0 _ Auth" class of rows cached before it existed (bounded batches, once per row).
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.indexAuth() } }
        // And the sender domain ("Group by Domain"), the same way.
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.indexDomains() } }
        refreshUnlessFresh()
        connectivity.start()
        observeThreadMembers()
        // Recompute the read/unread toggle state whenever the selection set changes.
        viewModelScope.launch {
            _selectedKeys.collect { refreshSelectionReadState(it) }
        }
        // Remember, for every key AS IT IS TICKED, the numbering its row carries — the fact a
        // destruction opposes later to what the row carries then (#99). Here and nowhere else.
        viewModelScope.launch {
            _selectedKeys.collect { rememberTickedNumbering(it) }
        }
        // Tell the push side which inbox is on screen (see shouldResetBaseline). The only place the
        // answer exists: nothing persists the selection.
        viewModelScope.launch {
            selection.collect { PushController.unifiedInboxVisible = it is Sel.Unified }
        }
        // Remember the view so a cold start reopens on it. The account is read HERE, at the write:
        // switchAccount() calls setCurrent(id) BEFORE onAccountChanged(). That leaves a window
        // where a refreshFolder() stores a stale pair — never one crossed between accounts.
        viewModelScope.launch {
            selection.collect { store.setStoredView(encodeSelection(store.currentId(), it)) }
        }
        // The END of an indicator stretch is DERIVED from the truth, in one place, not written into
        // the three arms of refresh()'s `when`. Every path that stops a refresh goes through `status`.
        viewModelScope.launch {
            status.map { it.refreshing }.distinctUntilChanged().collect { busy ->
                if (!busy) refreshRun.value = refreshRun.value?.copy(endedAt = clock())
            }
        }
    }

    override fun onCleared() {
        connectivity.stop()
        // No list on screen: fall back to the safe answer (announce rather than swallow).
        PushController.unifiedInboxVisible = false
        super.onCleared()
    }

    /**
     * Refresh a beat after the network came back — settled, so a flapping connection coalesces into
     * a single reconcile, then retried a few times on a widening gap ([ReconnectRefresh]). An error
     * left over from a refresh that failed *during* the outage is stale — [refreshNotice] would show
     * it as a technical failure — so it is dropped right away; the attempts below put a real one
     * back if the server is genuinely unreachable.
     */
    private fun onReconnected() {
        reconnectJob?.cancel()
        clearRefreshError()
        reconnectJob = viewModelScope.launch {
            // The watcher fires as soon as the link is back, before captive-portal validation (#65).
            // The link may not be routable yet, so an early failure is retried, not reported.
            repeat(ReconnectRefresh.MAX_TRIES) { attempt ->
                delay(ReconnectRefresh.delayBeforeMs(attempt))
                refresh()
                val mine = refreshJob
                mine?.join()
                // Someone refreshed on top of us (a pull, an account switch): that result is the
                // one the user is looking at — leave it alone and stop retrying behind their back.
                if (refreshJob !== mine) return@launch
                val error = status.value.error ?: return@launch
                status.value = status.value.copy(
                    error = ReconnectRefresh.errorAfterAttempt(attempt, error),
                )
            }
        }
    }

    /** Drop a stale refresh failure, leaving the in-flight state alone. */
    private fun clearRefreshError() {
        if (status.value.error != null) status.value = status.value.copy(error = null)
    }

    /** Re-point the inbox at the now-current account. Selection and header are reset from the
     *  *cached* metadata immediately, so the list shows the new account's cached mail at once
     *  instead of lingering on the previous account's, then a refresh runs. */
    fun onAccountChanged() {
        collapseThreads()
        // The pane empties with the account, by the SAME door as the other switches of scope (#103).
        // Never "keep it when the anchor is already the arriving account's": a row of B opened
        // while A is current would survive into B's list, with A's paging index still on it.
        paneOnViewChanged()
        // The exception: the message a tapped notification parked. Put back only onto the account it
        // belongs to, and dropped WHATEVER the verdict — a kept park replays elsewhere (#92).
        ReadingPaneRule.replayOnAccountChange(notificationAnchor, store.currentId())?.let { openInPane(it) }
        notificationAnchor = null
        currentAccountId.value = store.currentId()
        selection.value = Sel.Folder(store.inboxMailboxId())
        unifiedInboxScopes.value = store.allInboxScopes()
        // Cleared, not recomputed: the folder list in hand is the PREVIOUS account's, and pairs built
        // from it under the new account's id would page a sibling account's folders (#121). The
        // RECORD is cleared, not the scope derived from it, which the next derivation rewrites.
        folderSnapshot.value = null to emptyList()
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshWatchedFolders()
        // The arriving account's own fold choices. IMAP ids are paths, so keeping the departing
        // account's registry folds its namesake folders here (#92/#121).
        refreshCollapsedFolders()
        refreshUnlessFresh()
    }

    /** The in-flight refresh, so a new one cancels-and-replaces it (never two reconciles at once). */
    private var refreshJob: Job? = null

    /**
     * The refresh the READER asked for: the pull gesture and the two Retry buttons. Its indicator is
     * drawn at once, with none of the grace that keeps the tern off a folder tap's fetch (#178).
     * The flag is raised BEFORE the call: `viewModelScope` is `Main.immediate`, so refresh()'s
     * `launch` body has already begun by the time refresh() returns — there is no safe "after".
     */
    fun refreshRequested() {
        refreshAnswersAGesture = true
        refresh()
    }

    /**
     * Reconcile, UNLESS this very view came back successfully less than 30 s ago (#178). The
     * register is persisted and the decision is [isFresh]'s.
     *
     * Only for the reconciles that accompany a change of view. Never in front of [onReconnected]
     * — the only thing that clears a FAILED reachability (#65) — nor [refreshRequested], nor
     * [forceRefresh], nor a reconcile that follows a write. The stale error is dropped FIRST: a
     * change of view never inherits the failure of the view before it.
     */
    private fun refreshUnlessFresh() {
        clearRefreshError()
        if (isFresh(decodeFreshness(store.refreshFreshness()), currentScopes(), clock())) return
        refresh()
    }

    /** Stamp [scopes] as reconciled now, pruning whatever fell out of the window ([recordFresh]).
     *  Called from ONE place: the Delivered arm of [refresh]. A failed or abandoned refresh brought
     *  nothing back, and stamping it would hold a stale list on screen for 30 s. */
    private fun rememberReconciled(scopes: List<Pair<String, String>>) {
        val register = recordFresh(decodeFreshness(store.refreshFreshness()), scopes, clock())
        store.setRefreshFreshness(encodeFreshness(register))
    }

    fun refresh() {
        refreshJob?.cancel()
        val instant = refreshAnswersAGesture
        refreshAnswersAGesture = false
        refreshRun.value = startRefreshRun(refreshRun.value, clock(), instant)
        status.value = Status(refreshing = true, error = null)
        refreshJob = viewModelScope.launch {
            // Read HERE, not in the Delivered arm: the selection can move while this refresh is in
            // flight, and stamping a folder it never read is how a stale list passes for fresh.
            val reconciled = currentScopes()
            try {
            // #130: the fourth end of a refresh — never coming back. The bound and its reason live
            // in [REFRESH_BUDGET_MS]; nothing is passed here, so the budget has exactly one home.
                val outcome = refreshWithin {
                    when (val sel = selection.value) {
                        Sel.Unified -> refreshUnified()
                        Sel.Unread -> refreshUnreadScope()
                        is Sel.Folder -> refreshFolder(sel.id)
                    }
                }
                when (outcome) {
                    RefreshOutcome.Delivered -> {
                        status.value = Status(refreshing = false, error = null)
                        // #65: every refresh doubles as the connectivity probe. A killswitch VPN is
                        // invisible to the framework; only what the requests live can correct it.
                        connectivity.reportSuccess()
                        // #178: only a refresh that LANDED answers for its view. The scopes are
                        // the ones read when this refresh started.
                        rememberReconciled(reconciled)
                    }
                    is RefreshOutcome.Failed -> {
                        val cause = outcome.cause
                        status.value = Status(refreshing = false, error = app.sterna.core.jmap.GatewayErrors.syncMessage(cause) ?: cause.message ?: cause.javaClass.simpleName)
                        connectivity.reportFailure(cause)
                    }
                    RefreshOutcome.AbandonedAtBudget -> {
                        // No connectivity.reportFailure here: nothing said the device is offline,
                        // and an offline banner on a phone plainly online IS defect #65. An
                        // abandoned wait is an error with its own wording.
                        val timedOut = getApplication<Application>().getString(R.string.sync_error_timed_out)
                        status.value = Status(refreshing = false, error = timedOut)
                    }
                }
            } catch (c: CancellationException) {
                throw c // a superseding refresh cancelled us — don't stomp its status
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = app.sterna.core.jmap.GatewayErrors.syncMessage(t) ?: t.message ?: t.javaClass.simpleName)
                connectivity.reportFailure(t)
            }
        }
    }

    private suspend fun refreshFolder(mailboxId: String?) {
        val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        val window = store.syncWindow(credentials.id)
        val pruneBefore = window.maxAgeDays?.let {
            System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
        }
        // The Inbox is never pruned by age: it lists every message the server holds (the window sizes
        // only the fast newest pass; older mail is backfilled, see MailRepository.imapWriteThrough).
        val inboxWhole = mailboxId == null || mailboxId == store.inboxMailboxId()
        val updated = repo.refresh(credentials, mailboxId, window.limit, if (inboxWhole) null else pruneBefore)
        if (updated.mailboxId == store.inboxMailboxId() || mailboxId == null) {
            // Keep the cached inbox metadata fresh for offline display.
            store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
            // Codeberg #50: a reply landed by THIS refresh must trigger unarchive-on-reply too —
            // in the foreground this is the path new mail arrives by. No-op unless the toggle is on.
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, updated.mailboxId) }
        }
        selection.value = Sel.Folder(updated.mailboxId)
        meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
    }

    /**
     * Pull-to-refresh in the "unread" view: the account's inbox AND every other folder the view
     * pages, in one pass — `refreshAccountFolders`, which covers both protocols. The `limit` is
     * [UNREAD_SCOPE_FOLDER_LIMIT], not the account's sync window (reason on the constant). It
     * writes neither [selection] nor [meta] — that is why it is not [refreshFolder], whose last line
     * would drop the reader into the Inbox. `onMissing` stays a no-op: WATCHED is a user intent.
     */
    private suspend fun refreshUnreadScope() {
        val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        // [refreshAccountFolders] opens on `rekeyWatchedFolders`, which PERSISTS a re-key of the
        // watched set that EXISTS: it can rename an entry, never add a watch this view invented.
        val refreshes = repo.refreshAccountFolders(
            credentials,
            extraFolderIds = unreadRefreshTargets(credentials.id, unreadScopes.value, store.inboxMailboxId()),
            includeInbox = true,
            limit = UNREAD_SCOPE_FOLDER_LIMIT,
        )
        // #50: a reply landed by THIS refresh must trigger unarchive-on-reply too. The inbox is
        // CHOSEN out of what came back ([refreshedInboxId]), not taken as the first refresh: a wrong
        // aim advances another folder's notification baseline. No-op unless the toggle is on.
        val refreshedInbox = refreshedInboxId(refreshes.map { it.mailboxId }, store.inboxMailboxId())
        if (refreshedInbox != null) {
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, refreshedInbox) }
        }
    }

    private suspend fun refreshUnified() {
        val credentials = store.allCredentials()
        val result = repo.refreshAllInboxes(credentials)
        val metas = result.metas
        metas.forEach { store.saveInboxMetaFor(it.accountId, it.mailboxId, it.mailboxName, it.accountName, it.unreadCount) }
        // Codeberg #50: same foreground trigger as refreshFolder, per refreshed inbox.
        metas.forEach { meta ->
            credentials.firstOrNull { it.id == meta.accountId }?.let { cred ->
                runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), cred, meta.mailboxId) }
            }
        }
        unifiedInboxScopes.value = store.allInboxScopes()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        // #65/#92: the unified refresh is also the connectivity probe. Every account failed is a
        // failure; otherwise refresh() calls reportSuccess and erases the offline banner.
        if (result.isConnectivityFailure) throw result.failures.first()
    }

    /** Switch to the cross-account unified inbox. */
    fun selectUnified() {
        if (selection.value is Sel.Unified) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Unified
        unifiedInboxScopes.value = store.allInboxScopes()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        refreshUnlessFresh()
    }

    /**
     * Switch to this account's unread mail across its folders ([Sel.Unread]). [meta] is REWRITTEN
     * for the account label: `meta.accountName` still holds the literal `UNIFIED_LABEL` when the
     * unified inbox was last shown, so the subtitle would read "All inboxes", in English. The
     * scope is NOT recomputed here — it is written in one place, the [mailboxes] `onEach` (#121).
     */
    fun selectUnread() {
        if (selection.value is Sel.Unread) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Unread
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshUnlessFresh()
    }

    fun select(mailbox: Mailbox) {
        select(mailbox, emptyPane = true)
    }

    /** [emptyPane] is false on ONE path: the folder a tapped notification came from (#91,
     *  [applyNotificationFolder]). It lands after the anchor that notification posted in the pane,
     *  and emptying there would take the very message the tap asked for off the right (#103). */
    private fun select(mailbox: Mailbox, emptyPane: Boolean) {
        if (selection.value == Sel.Folder(mailbox.id)) return
        collapseThreads()
        if (emptyPane) paneOnViewChanged()
        selection.value = Sel.Folder(mailbox.id)
        meta.value = Meta(store.accountLabel(), mailbox.name, mailbox.unreadEmails)
        refreshUnlessFresh()
    }

    /**
     * Put the list on the folder a tapped new-mail notification came from (#91), so Back out of the
     * message lands in a list that holds it. The account half is
     * [app.sterna.ui.NotificationAccountSwitch]. The verdict is
     * [app.sterna.ui.NotificationFolderSwitch]'s and needs the folder list, which may not be loaded
     * (empty means "not known yet"): the request is parked, judged on the first loaded list, and
     * consumed on that first verdict whatever it is — never kept to fire later.
     */
    fun showFolderFromNotification(accountId: String, mailboxId: String, emailId: String) {
        notificationFolder = Triple(accountId, mailboxId, emailId)
        if (currentAccountId.value == accountId) applyNotificationFolder(state.value.mailboxes)
    }

    /** Judge a parked [notificationFolder] against a freshly loaded [folders] list. */
    private fun applyNotificationFolder(folders: List<Mailbox>) {
        val (accountId, mailboxId, emailId) = notificationFolder ?: return
        // An empty list is "not known yet" — same conservative reading as [selectionIsGone].
        // Keep waiting rather than deciding on nothing.
        if (folders.isEmpty()) return
        notificationFolder = null
        if (currentAccountId.value != accountId) return
        val target = NotificationFolderSwitch.resolve(
            notificationMailboxId = mailboxId,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unifiedView = selection.value is Sel.Unified,
            knownMailboxIds = folders.map { it.id },
        )
        // [NotificationFolderSwitch.resolve] gets the WHOLE list: a hidden folder is not an
        // unknown one, whose branch means deleted. Its RESULT is judged ([notificationFolderToShow]).
        val show = notificationFolderToShow(target, folders, showOnlySubscribed.value) ?: return
        // Not a flat `emptyPane = false`, nor "was a message parked?" — that park is spent by the
        // ACCOUNT SWITCH. The order-proof question: is what the pane holds MY message?
        setReadingPane(ReadingPaneRule.onFolderFromNotification(_readingPane.value, emailId, accountId))
        folders.firstOrNull { it.id == show }?.let { select(it, emptyPane = false) }
    }

    /**
     * Name the RESTORED folder from a freshly loaded [folders] list ([restoredFolderMeta]).
     * Known and deliberately unguarded: if Room's FIRST [mailboxes] emission lands after a
     * successful [refreshFolder], this overwrites that header's count — both come out of the same
     * database, and the count is drawn nowhere today. No guard for it (rules 5, 6).
     */
    private fun applyRestoredMeta(folders: List<Mailbox>) {
        val step = restoredFolderMeta(restoredFolder, selection.value, folders, store.accountLabel())
        if (!step.settled) return
        restoredFolder = null
        step.meta?.let { meta.value = it }
    }

    /** Return to the account's Inbox — Back from any other folder lands here. */
    fun showInbox() {
        val inboxId = store.inboxMailboxId()
        if (selection.value == Sel.Folder(inboxId)) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Folder(inboxId)
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshUnlessFresh()
    }

    /** Drop all inline-expansion state — e.g. when the list's contents change underneath it. */
    private fun collapseThreads() {
        _expandedThreads.value = emptySet()
        _threadMembers.value = emptyMap()
        completedThreads.clear()
        threadOrder.clear()
        maskedMembers.clear()
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        val targetSeen = !email.isSeen
        patchThreadMembersSeen(setOf(email.emailKey()), targetSeen)
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, targetSeen) }
                .onFailure { reportActionFailed("setRead (swipe)", it) }
            if (targetSeen) dismissReadNotifications(listOf(email.emailKey()))
        }
    }

    /** Swipe action: delete (move to Trash). */
    fun delete(email: Email) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // A delete that only moves to Trash is reversible immediately; one that would DESTROY is
            // held behind the Undo window (#23). The probe can hit the network; on failure fall back
            // to the move path, whose own failure can never destroy anything.
            if (runCatching { repo.deleteWouldDestroy(credentials, email) }.getOrDefault(false)) {
                heldBackDestroy(listOf(email), getApplication<Application>().getString(R.string.status_message_deleted_forever))
            } else {
                swipeRemove(email, getApplication<Application>().getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }
            }
        }
    }

    /**
     * Permanently destroy [emails], but hold the destroy back behind the Undo window: evict the rows
     * now, fire the destroy when the window elapses, and let [undoDelete] cancel it (#23). A new
     * held-back delete supersedes a pending one, destroying the earlier set at once. The destroy is
     * PERSISTED WorkManager work with an initial delay, so it survives the process; the inner
     * coroutine only times the snackbar. Batched per account (#29).
     */
    private suspend fun heldBackDestroy(emails: List<Email>, label: String) {
        val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
        if (targets.isEmpty()) return
        // Frozen HERE, with the confirmation, and carried by the work: an IMAP UID means something
        // only inside one numbering, and the destroy fires minutes later (#99).
        val numbering = recordedNumbering(targets)
        flushPendingDestroy()
        pendingDeleteTargets = targets
        pendingDeleteNumbering = numbering
        pendingDeleteEmails = emails
        viewModelScope.launch {
            // The eviction is local and immediate, so the mask only has to cover it: once the rows
            // are out of the cache the live reading has nothing to put back.
            hidingThreadMembers(emails.mapTo(mutableSetOf()) { it.emailKey() }) {
                // Batched per account: evicting one id at a time rescanned the whole search index.
                targets.groupBy({ it.first.id }, { it.second }).forEach { (accountId, ids) ->
                    repo.evictAll(accountId, ids)
                }
            }
            dropSearchResults(emails.mapTo(mutableSetOf()) { it.emailKey() })
            _pendingDelete.value = label
            targets.groupBy { it.first.id }.forEach { (accountId, rows) ->
                MessageDestroyWorker.schedule(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering), PURGE_HOLD_BACK_MS)
            }
            pendingDeleteJob = viewModelScope.launch {
                delay(PURGE_HOLD_BACK_MS)
                pendingDeleteTargets = emptyList()
                pendingDeleteNumbering = emptyMap()
                pendingDeleteEmails = emptyList()
                _pendingDelete.value = null
            }
        }
    }

    /**
     * One account's held-back rows, split into the requests the worker will fire: by the folder each
     * message was in when the user confirmed (#122) AND by the numbering it was READ under, which an
     * IMAP destroy opposes to the server (#99). An empty folder key destroys nothing, the safe end.
     * The split is [UidValidity.imapDestroyRoutes]: two rows of one folder read under two
     * numberings become two requests. On JMAP every numbering is null.
     */
    private fun foldersToDestroy(
        accountId: String,
        rows: List<Triple<AccountCredentials, String, String>>,
        numbering: Map<Pair<String, String>, Long?>,
    ): List<MessageDestroyWorker.FolderDestroy> {
        val routes = UidValidity.imapDestroyRoutes(rows.map { it.second to it.third }) { numbering[accountId to it] }
        return routes.map { MessageDestroyWorker.FolderDestroy(it.mailboxId, it.emailIds, it.uidValidity) }
    }

    /** The IMAP numbering every message in [targets] was READ under — the stamp on its own cached
     * row, read at the confirmation, the only moment this may be read. Not the folder's record,
     *  already realigned by a background pass: opposing it to itself licenses the expunge (#99). */
    private suspend fun recordedNumbering(
        targets: List<Triple<AccountCredentials, String, String>>,
    ): Map<Pair<String, String>, Long?> {
        return targets.groupBy({ it.first }, { it.second })
            .flatMap { (credentials, emailIds) ->
                repo.numberingRowsWereReadUnder(credentials, emailIds).map { (emailId, uidValidity) -> (credentials.id to emailId) to uidValidity }
            }.toMap()
    }

    /** Commit any held-back destroy immediately (a new delete supersedes the pending one). */
    private fun flushPendingDestroy() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        val targets = pendingDeleteTargets
        // Not a suspending function, and it must not become one: what it replays is the destroy
        // the user confirmed EARLIER, so it re-enqueues the numbering captured then (#99).
        val numbering = pendingDeleteNumbering
        pendingDeleteTargets = emptyList()
        pendingDeleteNumbering = emptyMap()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        targets.groupBy { it.first.id }.forEach { (accountId, rows) ->
            MessageDestroyWorker.flushNow(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering))
        }
    }

    /** Cancel the held-back destroy and restore the rows (nothing was destroyed yet). */
    fun undoDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteTargets.map { it.first.id }.distinct().forEach {
            MessageDestroyWorker.cancelDestroy(getApplication(), it)
        }
        val restored = pendingDeleteEmails
        pendingDeleteTargets = emptyList()
        pendingDeleteNumbering = emptyMap()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        restoreSearchResults(restored.map { it.emailKey() })
        // A full re-query, not an incremental refresh: the messages were only evicted locally and
        // are still in Trash on the server, so queryChanges reports no change (the view stays empty).
        forceRefresh()
        // The re-query brings back representatives only — complete the restored conversations.
        completeThreadsAfterAction(restored)
    }

    /** Re-query the current folder from scratch (drops sync cursors first) so locally-evicted but
     *  server-present messages reappear — an incremental refresh alone re-fetches nothing. */
    private fun forceRefresh() {
        repo.resetSyncState()
        refresh()
    }

    /** Swipe action: archive. */
    fun archive(email: Email) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_archived)) { c, id -> repo.archive(c, id) }

    /** Swipe action when already inside Archive: move the message back to the Inbox. */
    fun unarchive(email: Email, inboxId: String) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_unarchived)) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /** Move ONE message to [targetMailboxId] — the reader's move-to-folder (#73). The same
     *  [swipeRemove] path archive and delete take: count nudge, row leaving on the server's ack, and
     *  the Undo. [email] carries its own account, so a unified-inbox row moves inside ITS one (#92). */
    fun moveTo(email: Email, targetMailboxId: String, targetAccountId: String? = null) {
        // Into another account's folder (#189): a different path entirely, with no Undo.
        if (isCrossAccountMove(targetAccountId, email.accountId)) {
            moveToAccount(email, requireNotNull(targetAccountId), targetMailboxId)
            return
        }
        swipeRemove(email, getApplication<Application>().getString(R.string.status_message_moved)) { c, id ->
            repo.moveToMailbox(c, id, targetMailboxId)
        }
    }

    /**
     * Move ONE message into a folder of ANOTHER account (#189): the copy is confirmed on B, then the
     * original goes to A's bin ([MailRepository.moveToAccount]); nothing is ever destroyed.
     *
     * No Undo: taking it back would be a second two-sided operation. `_undo` is never written
     * here — ONE banner says what happened. [CrossAccountMove.CopiedNotRemoved] means the copy
     * exists and A's original stayed, a duplicate the user can see and never a loss. The verdict's
     * reason goes to logcat, not to the banner.
     */
    private fun moveToAccount(email: Email, targetAccountId: String, targetMailboxId: String) {
        val source = credentialsFor(email) ?: return
        val app = getApplication<Application>()
        val target = store.credentials(targetAccountId)
        if (target == null) {
            _message.value = app.getString(R.string.status_action_failed)
            return
        }
        viewModelScope.launch {
            val key = email.emailKey()
            dropSearchResults(setOf(key))
            val result = hidingThreadMembers(setOf(key)) {
                // NOTHING FROZEN, said out loud IN THE TYPE. This gesture starts on the OPEN
                // message: no tick, so no earlier numbering to oppose. NOT `Frozen(null)`, which
                // means "a tick froze nothing at all" and is refused downstream.
                runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.NothingFrozen) }
                    .getOrElseUnlessCancelled { CrossAccountMove.Failed(it.toString()) }
            }
            val folder = folderLabel(target.id, targetMailboxId)
            // Which sentence belongs to which verdict is crossAccountMessageRes's.
            val words = crossAccountMessageRes(result, online = hasUsableNetwork(app))
            when (result) {
                CrossAccountMove.Moved ->
                    _message.value = app.getString(words, accountLabel(target.id), folder)
                is CrossAccountMove.CopiedNotRemoved -> {
                    android.util.Log.w("SternaInbox", "move to account: copied, original not removed: ${result.reason}")
                    restoreSearchResults(listOf(key))
                    _message.value = app.getString(words, accountLabel(target.id), folder, accountLabel(source.id))
                }
                is CrossAccountMove.Failed -> {
                    android.util.Log.w("SternaInbox", "move to account failed: ${result.reason}")
                    _message.value = app.getString(words)
                    restoreSearchResults(listOf(key))
                    // As swipeRemove's failure leg, minus the rewind: no row flew off — reconcile.
                    refresh()
                }
            }
        }
    }

    /** How the banners name an account: the drawer's own label, or the id when it is gone. */
    private fun accountLabel(accountId: String?): String =
        store.accountsFlow.value.firstOrNull { it.id == accountId }?.label() ?: accountId.orEmpty()

    /** How the banners name a folder: as the picker showed it ([mailboxLabel]), from the cache. */
    private suspend fun folderLabel(accountId: String, mailboxId: String): String {
        val mailbox = repo.observeMailboxes(accountId).first().firstOrNull { it.id == mailboxId }
        return mailbox?.let { mailboxLabel(getApplication<Application>(), it.role, it.name) } ?: mailboxId
    }

    // ---- whole-thread swipe (collapsed conversation rows act on the whole conversation) ----

    /** A thread's full membership from the cache (representative included), or [rep] alone. */
    private suspend fun threadMessages(rep: Email): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return listOf(rep)
        return repo.cachedThreadEmails(accountId, currentMailboxIds(), threadKeyOf(rep).threadId)
            .ifEmpty { listOf(rep) }
    }

    /** Toggle read across a whole thread: mark all read if any is unread, else all unread. */
    fun toggleReadThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val targetSeen = members.any { !it.isSeen }
            patchThreadMembersSeen(members.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            members.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setRead(credentials, m.id, targetSeen) }
                    .onFailure { reportActionFailed("setRead (thread)", it) }
            }
            if (targetSeen) dismissReadNotifications(members.map { it.emailKey() })
        }
    }

    /** Toggle flag across a whole thread, following the representative's current state. */
    fun toggleFlagThread(rep: Email) {
        viewModelScope.launch {
            val flagged = !rep.isFlagged
            threadMessages(rep).forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setFlagged(credentials, m.id, flagged) }
                    .onFailure { reportActionFailed("setFlagged (thread)", it) }
            }
        }
    }

    /**
     * Delete a whole conversation. Members whose delete would permanently destroy them go through
     * the held-back destroy — a cancelable Undo window, never an inline destroy — while the rest
     * take move-to-Trash. A stale cached folder can at worst hold a move back behind Undo.
     */
    fun deleteThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val (destroy, move) = members.partition { m ->
                credentialsFor(m)?.let { c -> runCatching { repo.deleteWouldDestroy(c, m) }.getOrDefault(false) } ?: false
            }
            dropThreadExpansion(threadKeyOf(rep))
            if (destroy.isNotEmpty()) {
                heldBackDestroy(destroy, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo — two snackbars would
                // queue on one host while the destroy clock runs (a pure move keeps its own).
                threadSwipeRemove(rep, R.string.status_conversation_deleted, members = move, offerUndo = destroy.isEmpty()) { c, id -> repo.delete(c, id) }
            }
        }
    }
    fun archiveThread(rep: Email) = threadSwipeRemove(rep, R.string.status_conversation_archived) { c, id -> repo.archive(c, id) }
    fun unarchiveThread(rep: Email, inboxId: String) =
        threadSwipeRemove(rep, R.string.status_conversation_unarchived) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /** Drop a thread's inline-expansion state — its conversation is leaving the list. */
    private fun dropThreadExpansion(key: ThreadKey) {
        _expandedThreads.value = _expandedThreads.value - key
        _threadMembers.value = _threadMembers.value - key
        completedThreads -= key
        threadOrder.drop(key)
    }

    /**
     * Best-effort follow-up to an action that moved or restored whole conversations: drop the stale
     * expansion snapshot and re-fetch the full membership so every member is re-cached under its
     * REAL current folder. Waits out any in-flight [refresh] first, skips threads with a held-back
     * destroy pending (re-caching would resurrect the evicted rows), and caps the fan-out.
     */
    private fun completeThreadsAfterAction(emails: List<Email>) {
        val heldKeys = pendingDeleteEmails.mapTo(mutableSetOf()) { threadKeyOf(it) }
        val reps = emails
            .filter { it.threadId != null && threadKeyOf(it) !in heldKeys }
            .distinctBy { threadKeyOf(it) }
        if (reps.isEmpty()) return
        // Invalidate every affected snapshot so the next expand reloads fresh, even past the cap.
        reps.forEach { dropThreadExpansion(threadKeyOf(it)) }
        viewModelScope.launch {
            refreshJob?.join()
            reps.take(MAX_THREAD_COMPLETIONS).forEach { rep ->
                val credentials = credentialsFor(rep) ?: return@forEach
                val threadId = rep.threadId ?: return@forEach
                runCatching { repo.fetchThreadMembers(credentials, threadId, currentMailboxIds()) }
            }
        }
    }

    /** Remove every message of a thread ([members], or its full cached membership), run [op] per
     *  message, then offer one Undo that restores the whole batch. Mirrors [swipeRemove]. */
    private fun threadSwipeRemove(
        rep: Email,
        labelRes: Int,
        members: List<Email>? = null,
        offerUndo: Boolean = true,
        op: suspend (AccountCredentials, String) -> String?,
    ) {
        viewModelScope.launch {
            val acting = members ?: threadMessages(rep)
            dropThreadExpansion(threadKeyOf(rep))
            val entries = mutableListOf<UndoEntry>()
            var failed = false
            acting.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                val mailboxId = m.mailboxId ?: return@forEach
                // The repo op is network-first: it drops the row and nudges counts on ack.
                runCatching { op(credentials, m.id) }
                    .onSuccess { dest -> entries += UndoEntry(m.id, m.accountId, mailboxId, dest) }
                    .onFailure { failed = true }
            }
            if (offerUndo && entries.isNotEmpty()) {
                _undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))
                scheduleUndoneDeparture()
            }
            if (failed) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                refresh() // failed rows were never dropped locally — just reconcile the list
            }
            // Re-cache the moved conversation's members under their new folder.
            completeThreadsAfterAction(acting)
        }
    }

    /** Remove [email] optimistically (so the row leaves instantly — never stuck mid-swipe), run the
     *  server [op], then either offer Undo on success or restore the row and report the error. */
    private fun swipeRemove(email: Email, label: String, op: suspend (AccountCredentials, String) -> String?) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // No pre-evict: the repo op is network-first — it drops the row AND nudges the drawer
            // counts once the server acknowledged. It returns the destination, for the Undo.
            dropSearchResults(setOf(email.emailKey()))
            // The UI row may not carry its source folder (e.g. a server-fetched thread member) —
            // fall back to the cached row, captured before the op drops it, so Undo still works.
            val source = email.mailboxId ?: repo.cachedEmail(credentials.id, email.id)?.mailboxId
            hidingThreadMembers(setOf(email.emailKey())) { runCatching { op(credentials, email.id) } }
                .onSuccess { dest ->
                    if (source != null) {
                        _undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, source, dest)), label)
                        scheduleUndoneDeparture()
                    }
                }
                .onFailure {
                    val app = getApplication<Application>()
                    // The exception text is raw and English-only: logcat, not the snackbar.
                    android.util.Log.w("SternaInbox", "swipe action failed", it)
                    _message.value = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app)))
                    restoreSearchResults(listOf(email.emailKey()))
                    // The browse list's row was never removed: it FLEW OFF, and the flight is not
                    // undone by re-emitting it. Say the swipe did not take; the row rewinds.
                    _swipeRewind.value = _swipeRewind.value + email.emailKey()
                    refresh() // the failed row was never dropped locally — just reconcile the list
                }
        }
    }

    /** Move the last deleted/archived message(s) back to their original mailbox(es). */
    fun undo() {
        val action = _undo.value ?: return
        _undo.value = null
        // The gesture is being reversed, so its banners must stay: kill the deadline before the
        // restore, not after — the restore suspends, and the window is measured in seconds.
        undoDepartureJob?.cancel()
        restoreSearchResults(action.entries.map { EmailKey(it.accountId, it.emailId) })
        viewModelScope.launch {
            // One batch per account, so a large undo does not hit per-message server limits (#29).
            var unrestored = 0
            action.entries.groupBy { it.accountId }.forEach { (accountId, entries) ->
                val credentials = accountId?.let { store.credentials(it) } ?: store.load() ?: return@forEach
                // Optimistic restore that STICKS: restoreAll re-tags the rows, marks them
                // recently-mutated and reverses the count nudge. No refresh — it would race it.
                runCatching {
                    repo.restoreAll(
                        credentials,
                        entries.map { MailRepository.RestoreTarget(it.emailId, it.mailboxId, it.destMailboxId) },
                    )
                }
                    .onSuccess { unrestored += it.size }
                    .onFailure {
                        unrestored += entries.size
                        val app = getApplication<Application>()
                        // As the swipe, plus one thing: this banner must never be null. A null error
                        // is RefreshNotice.NONE, a banner saying nothing (OFFLINE beats ERROR, #65).
                        android.util.Log.w("SternaInbox", "undo restore failed", it)
                        status.value = Status(refreshing = false, error = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))))
                    }
            }
            if (unrestored > 0) {
                _message.value = getApplication<Application>().getString(R.string.status_restore_failed)
            }
            // restoreAll re-cached only the restored rows — complete their conversations (no refresh).
            completeThreadsAfterAction(repo.cachedEmailsByIds(action.entries.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }))
        }
    }

    fun clearUndo() {
        _undo.value = null
    }

    /**
     * Take the banners of the Undo action just offered down once its window closes unused.
     * `departedIds` may not be fed from here (see `Notifications.departuresToCancel`).
     * In `viewModelScope`, not an effect of the screen: a `LaunchedEffect` cancelled with the
     * composition runs NEITHER branch and leaves the banner up for good. The PREVIOUS gesture's
     * deadline is not cancelled here — that message did depart. Only [undo] cancels its own.
     */
    private fun scheduleUndoneDeparture() {
        val action = _undo.value ?: return
        val keys = undoBannerKeys(action.entries)
        undoDepartureJob = viewModelScope.launch {
            delay(UNDO_BANNER_DISMISS_MS)
            // The window closes HERE, not on screen: the bar is Indefinite, so clearing `_undo` takes
            // it down with the banners — one clock. Identity check: a second gesture may own it now.
            if (_undo.value === action) _undo.value = null
            dismissBanners(keys)
        }
    }

    /**
     * Empty the current Trash folder. The view clears at once, but the permanent delete is held back
     * for a few seconds so it can be undone: PERSISTED WorkManager work with an initial delay; the
     * coroutine below only times the snackbar. What gets destroyed is decided HERE, not when the
     * work runs (#99) — the ids are snapshotted at confirmation, BEFORE evicting the cached rows the
     * IMAP path reads. Scoped to `credentials.id`, never a sibling's folder (#31).
     */
    fun emptyTrash() {
        val trashId = (selection.value as? Sel.Folder)?.id ?: return
        val credentials = store.load() ?: return
        val target = credentials.id to trashId
        // Tapping Empty again on a Trash ALREADY being emptied changes NOTHING (#99). A second tap
        // would freeze an EMPTY list and move the snackbar's dismissal past the destroy, offering an
        // Undo on mail already destroyed. Doing nothing keeps the two clocks aligned.
        if (pendingPurgeTarget == target) return
        _pendingPurge.value = getApplication<Application>().getString(R.string.status_trash_emptied)
        pendingPurgeTarget = target
        purgeJob?.cancel()
        purgeJob = viewModelScope.launch {
            val purge = runCatching { repo.snapshotTrashPurge(credentials, trashId) }.getOrElseUnlessCancelled {
                // A cancellation is NOT a snapshot failure: this job is cancelled by an Undo or by a
                // confirmation on another folder, and the teardown would clear a pending purge that
                // is no longer its own. Nothing recorded, nothing scheduled — drop the snackbar.
                if (pendingPurgeTarget == target) {
                    pendingPurgeTarget = null
                    _pendingPurge.value = null
                }
                return@launch
            }
            // One batched eviction: per-message eviction rescanned the search index each time.
            repo.evictAll(credentials.id, repo.cachedIds(listOf(target)).map { it.emailId })
            // An empty photo is not an order (#99): no ids behind the work arms no destroy.
            if (purge.messageCount > 0) {
                MessageDestroyWorker.schedulePurge(
                    getApplication(), credentials.id, trashId, purge.purgeId, PURGE_HOLD_BACK_MS,
                )
            }
            delay(PURGE_HOLD_BACK_MS)
            if (pendingPurgeTarget == target) {
                pendingPurgeTarget = null
                _pendingPurge.value = null
            }
        }
    }

    /** Cancel a held-back trash purge and restore the rows (nothing was destroyed yet). */
    fun undoEmptyTrash() {
        val target = pendingPurgeTarget
        pendingPurgeTarget = null
        purgeJob?.cancel()
        purgeJob = null
        _pendingPurge.value = null
        target?.let { (accountId, trashId) ->
            MessageDestroyWorker.cancelPurge(getApplication(), accountId, trashId)
            // Erase the destroy list itself, not just the job: the snapshot IS the order, so a purge
            // that ran anyway finds nothing to destroy (#99).
            viewModelScope.launch { repo.discardTrashPurge(accountId, trashId) }
        }
        // Full re-query, not incremental: the rows were only evicted locally and are still on the
        // server, so a delta refresh brings nothing back (Codeberg #23).
        forceRefresh()
    }

    /** Swipe action: toggle flag/star. */
    fun toggleFlag(email: Email) {
        val flagged = !email.isFlagged
        patchSearchResults(setOf(email.emailKey())) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            )
        }
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setFlagged(credentials, email.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (swipe)", it) }
        }
    }

    /**
     * The MESSAGE behind a list row (#514), for the row's own Resume / Copy Code actions.
     *
     * A row is an [Email] the list mapper built: headers, preview, flags — and no body, because the
     * body lives in its own table and is written when a message is opened. The row's two actions
     * were handed that row, so `bodySource()` fell through to [Email.preview] and both of them
     * answered about a snippet instead of the mail. They read the message here instead, through the
     * SAME door the reader opens, cache first and `markRead = false`: acting on a message is not
     * the same as having read it, and a list row must not silently clear its own unread mark.
     *
     * Returns [email] unchanged when the row has no account or the fetch fails, so a tap under a
     * thumb degrades to the tool's own "nothing to work with" wording rather than throwing.
     */
    suspend fun messageWithBody(email: Email): Email {
        val credentials = credentialsFor(email) ?: return email
        return runCatching { repo.openMessage(credentials, email.id, markRead = false).email }
            .getOrDefault(email)
    }

    /** Route an action to the email's own account (unified inbox), else the current one. */
    private fun credentialsFor(email: Email): AccountCredentials? = credentialsFor(email.emailKey())

    private fun credentialsFor(key: EmailKey): AccountCredentials? =
        key.accountId?.let { store.credentials(it) } ?: store.load()

    /** Clear the new-mail notifications of the messages [keys] just marked read locally, grouped by
     *  account so the group summary is refreshed once per account (#19). Keys and not messages:
     *  "mark all read" reaches this with a folder's worth of them. */
    private fun dismissReadNotifications(keys: List<EmailKey>) = dismissBanners(keys)

    /** The same thing without "read" in its name: the account-qualified dismissal of [keys]'
     *  banners. Nothing in it ever depended on the messages having been read. */
    private fun dismissBanners(keys: List<EmailKey>) {
        if (keys.isEmpty()) return
        val app = getApplication<Application>()
        keys.mapNotNull { e -> credentialsFor(e)?.let { it.id to it.username to e.emailId } }
            .groupBy({ it.first }, { it.second })
            .forEach { (account, ids) ->
                val (accountId, label) = account
                Notifications.dismiss(app, accountId, label, ids)
            }
    }

    /** Mailbox ids backing the current view (one folder, or all inboxes when unified). */
    private fun currentMailboxIds(): List<String> = when (val sel = selection.value) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> unifiedInboxScopes.value.map { it.second }.distinct()
        Sel.Unread -> unreadScopes.value.map { it.second }.distinct()
    }

    /** (account id, mailbox id) scopes backing the current view. Bulk cache reads must carry BOTH
     *  ids — same-server accounts can share a mailbox id (Stalwart numbers them per-account), and a
     *  mailbox-only read would sweep a sibling account's messages into the operation. */
    private fun currentScopes(): List<Pair<String, String>> = when (val sel = selection.value) {
        is Sel.Folder -> {
            val accountId = store.currentId()
            if (accountId != null && sel.id != null) listOf(accountId to sel.id) else emptyList()
        }
        Sel.Unified -> store.allInboxScopes()
        // The LIVE value, which can differ from what the pager on screen was built with. Accepted
        // only because [listUnreadOnly] forces the filter on for this scope, so the window can add
        // only UNREAD mail of a folder this view covers. [listScope] is not safer.
        Sel.Unread -> unreadScopes.value
    }

    // ---- sort / filter / bulk ----

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun toggleUnreadOnly() {
        toggleListFilter(ListFilter.UNREAD)
    }

    /** Mark every message in the current view as read. */
    fun markAllRead() {
        viewModelScope.launch { markScopesRead(currentScopes(), "markAllRead") }
    }

    /**
     * Mark one drawer folder read, whatever is on screen — the folder-options submenu's entry.
     *
     * Goes through [markScopesRead], the SAME path the toolbar's [markAllRead] uses, so there is one
     * implementation of "mark these read" and not a second one to drift from it. The only difference
     * is the scope: one named mailbox instead of whatever the current view covers.
     */
    fun markFolderRead(mailboxId: String) {
        val accountId = store.currentId() ?: return
        viewModelScope.launch { markScopesRead(listOf(accountId to mailboxId), "markFolderRead") }
    }

    /**
     * Mark every unread message in [scopes] read, on the SERVER and in the local mirror.
     *
     * `$seen` is a server keyword (RFC 8621 §4.1.1), so this is not a local flag flip: each scope's
     * ids go out as `Email/set` through [MailRepository.setReadAll], which splits them into batches
     * of the server's OWN advertised `maxObjectsInSet` ([JmapSession.setBatchSize] — 500 on
     * Stalwart, 100 when nothing usable is advertised). That is why no page size is chosen here: a
     * number picked in the UI layer would be a second, weaker opinion about a limit the server
     * already states, and it would be wrong the day the server changed it.
     *
     * The local rows are patched FIRST ([patchThreadMembersSeen]) so the drawer's counts — which are
     * local Room flows and cost no network — drop the moment the item is tapped. [refresh] then
     * reconciles against the server, which is what puts a count BACK if the write failed: without it
     * a failed "mark all read" would leave a zero badge over mail that is still unread.
     */
    private suspend fun markScopesRead(scopes: List<Pair<String, String>>, op: String) {
        // The unread keys, resolved by the statement — not a whole folder read then filtered.
        val cachedUnread = repo.cachedUnreadKeys(scopes)
        patchThreadMembersSeen(cachedUnread.toSet(), true)
        var allMarked = true
        scopes.forEach { (accountId, mailboxId) ->
            val credentials = store.credentials(accountId) ?: return@forEach
            // Server-resolved targets (cached fallback offline): the cached rows alone leave
            // out-of-window unread untouched. One bulk call per folder, not one per message.
            val ids = repo.unreadIds(credentials, mailboxId)
            runCatching { repo.setReadAll(credentials, ids, seen = true) }
                .onFailure { allMarked = false; reportActionFailed("$op ($accountId)", it) }
        }
        // Clear the notifications only if everything was actually marked: offline, setReadAll
        // fails and nothing was read, so dropping them would hide mail that is still unread.
        if (allMarked) dismissReadNotifications(cachedUnread)
        // Reconcile: rows marked beyond the cache don't nudge the badge — converge now.
        refresh()
    }

    // ---- multi-select ----

    fun enterSelection(email: Email) {
        // A draft only this phone holds is not selectable: every action a selection leads to is a
        // no-op or an error over an id no server knows. See [isLocalDraftRow].
        if (isLocalDraftRow(email.id)) return
        _selectionActive.value = true
        _selectedKeys.value = setOf(email.emailKey())
    }

    fun toggleSelect(email: Email) {
        // Same refusal as [enterSelection]: a tap in selection mode must not put one in either.
        if (isLocalDraftRow(email.id)) return
        val key = email.emailKey()
        val next = _selectedKeys.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
        _selectedKeys.value = next
        // Deselecting the last message leaves selection mode (else swipes hit the drawer).
        if (next.isEmpty()) _selectionActive.value = false
    }

    /** Enter selection mode from a collapsed conversation row: the selection covers every cached
     *  member of the thread, not just the representative, so the later bulk action treats the row
     *  the way it reads (one conversation). */
    fun enterSelectionThread(rep: Email) {
        _selectionActive.value = true
        _selectedKeys.value = setOf(rep.emailKey())
        viewModelScope.launch {
            _selectedKeys.value = _selectedKeys.value + threadMessages(rep).map { it.emailKey() }
        }
    }

    /** Toggle a collapsed conversation row in/out of the selection — all members at once. */
    fun toggleSelectThread(rep: Email) {
        viewModelScope.launch {
            val keys = threadMessages(rep).mapTo(mutableSetOf()) { it.emailKey() } + rep.emailKey()
            val current = _selectedKeys.value
            val next = if (rep.emailKey() in current) current - keys else current + keys
            _selectedKeys.value = next
            if (next.isEmpty()) _selectionActive.value = false
        }
    }

    /**
     * Select everything the list on screen is showing — the search results when they are what is
     * drawn, the folder otherwise (#126). The choice is [selectAllKeys]. Outside a search the folder
     * is read through [MailRepository.selectableIds], which pages the list's OWN WHERE clause.
     * A server draft a local one stands in for is out of the list but still filed in `emails`, so
     * [selectableKeysMinusHidden] subtracts it (#95): "Delete" must not reach an unshown row.
     */
    fun selectAll() {
        _selectionActive.value = true
        val search = searchState.value
        // The SAME decision the pager was built with ([listUnreadOnly]), never the raw toggle:
        // otherwise "Delete" reaches mail never on screen — the destructive half of #126.
        val filtered = listUnreadOnly(selection.value, unreadOnly.value)
        val hidden = localDraftRows.value.replacedServerIds
        viewModelScope.launch {
            _selectedKeys.value = selectAllKeys(
                searching = search.active,
                query = search.query,
                results = search.results?.map { it.emailKey() },
                loading = search.loading,
                complete = search.complete,
                folderKeys = selectableKeysMinusHidden(repo.selectableIds(currentScopes(), filtered, listView.value.shape()), hidden),
            )
        }
    }

    fun clearSelection() {
        _selectionActive.value = false
        _selectedKeys.value = emptySet()
    }

    /**
     * Keep [tickedUnder] in step with [keys]: drop what is no longer ticked, and read the numbering
     * of the rows that have just been (#99). Only the NEW keys are read — a re-read would answer
     * what the row carries now. One read per ACCOUNT, never one per message.
     *
     * The read suspends, so only keys STILL ticked are written back; a key ticked and confirmed
     * inside that window keeps no stamp and travels as `FrozenNumbering.Frozen(null)`, refused there.
     */
    private suspend fun rememberTickedNumbering(keys: Set<EmailKey>) {
        val known = tickedUnder.filterKeys { it in keys }
        val fresh = keys.filterNot { it in known }
        val read = mutableMapOf<EmailKey, Long?>()
        fresh.groupBy { credentialsFor(it) }.forEach { (credentials, group) ->
            if (credentials == null) return@forEach
            val numbering = repo.numberingRowsWereReadUnder(credentials, group.map { it.emailId })
            group.forEach { key -> read[key] = numbering[key.emailId] }
        }
        val still = _selectedKeys.value
        tickedUnder = (known + read).filterKeys { it in still }
    }

    /**
     * THE ONE BARRIER, and both selection gestures go through it (#99, #189).
     * [rememberTickedNumbering] SUSPENDS; a "Select all" over a large folder publishes the stamps a
     * dozen queries later, so a gesture that sampled the field got a half-full map. A WAIT, NEVER
     * A RE-READ: reading the rows again hands the guard the realigned number. ONE function.
     */
    private suspend fun awaitTickedNumbering(keys: Set<EmailKey>): Map<EmailKey, Long?> =
        tickedNumberingWhenCovered(keys, tickedUnderFlow)

    /**
     * Apply a bulk action to the selected messages; exits selection mode unless [clearAfter] is false.
     * Every selected key is resolved first ([resolveSelectionTargets]), rows drawn by a search
     * included. This path's op is the snooze, so a message with no local row cannot be snoozed:
     * [planSelectionSnooze] refuses it, and it counts as an attempt AND a failure.
     */
    private fun bulk(
        clearAfter: Boolean = true,
        undoLabel: String? = null,
        op: suspend (AccountCredentials, String) -> Unit,
    ) {
        val keys = _selectedKeys.value
        if (clearAfter) clearSelection()
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            // A message with no local row cannot be snoozed: SnoozeWorker looks it up in that table
            // at the due date. Refused rather than half-written — see [planSelectionSnooze].
            val plan = planSelectionSnooze(resolved.targets)
            // A key nothing resolves, and a row the plan refused, are attempts that failed — or a
            // selection nothing could be written for lands on "nothing failed" and says nothing.
            var failed = resolved.unresolved.size + plan.refused.size
            // For a reversible bulk op, capture each message the op wrote so the whole batch can be
            // moved back (#23). Whether an Undo may be offered is [selectionUndoEntries]' decision.
            val undoCandidates = mutableListOf<UndoCandidate>()
            // The mask lasts the batch and no longer: the op here is a snooze, which removes nothing
            // but a date, so a mask left standing keeps the message off screen past the due date.
            hidingThreadMembers(keys) {
                plan.snooze.forEach { target ->
                    val email = target.email
                    val credentials = credentialsFor(email)
                    if (credentials == null) { failed++; return@forEach }
                    runCatching { op(credentials, email.id) }
                        .onSuccess { undoCandidates += UndoCandidate(target, null) }
                        .onFailure {
                            failed++
                            android.util.Log.w("SternaBulk", "bulk op failed for ${email.id}", it)
                        }
                }
            }
            // A large selection can empty the whole loaded window, and an incremental refresh
            // re-fetches nothing: drop the sync cursors so a full page repopulates from the server.
            repo.resetSyncState()
            refresh()
            val undoEntries = selectionUndoEntries(undoCandidates)
            if (undoLabel != null && undoEntries.isNotEmpty()) {
                _undo.value = UndoAction(undoEntries, undoLabel)
            }
            // Partial and total are not the same news.
            when (bulkOutcome(attempted = keys.size, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * Batched bulk action: group the selection by account, then hand each account's ids to a single
     * repo call that itself batches per source folder — one `UID MOVE <set>` or one `Email/set`
     * instead of one command per message (#29). The batches receive the WHOLE selection
     * ([resolveSelectionTargets]), rows drawn by a search included. [attemptedBefore] and
     * [failedBefore] are what a delegating caller handled or lost before getting here, counted
     * separately: 100 attempts with 1 failure is not a total failure.
     */
    private fun bulkBatched(
        clearAfter: Boolean = true,
        @PluralsRes undoLabelRes: Int? = null,
        keys: Set<EmailKey>? = null,
        attemptedBefore: Int = 0,
        failedBefore: Int = 0,
        batchOp: suspend (AccountCredentials, List<String>) -> MailRepository.BulkResult,
    ) {
        val targetKeys = keys ?: _selectedKeys.value
        if (clearAfter) clearSelection()
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = targetKeys,
                cached = repo.cachedEmailsByIds(targetKeys),
                displayed = searchState.value.results,
            )
            val targets = resolved.targets
            // Only the acted-on rows leave the search snapshot; the failed ones come back after.
            dropSearchResults(targets.mapTo(mutableSetOf()) { it.email.emailKey() })
            val failedKeys = mutableSetOf<EmailKey>()
            val undoCandidates = mutableListOf<UndoCandidate>()
            // The FIRST throwable a batch lets escape, kept for the message at the end. Null is the
            // ordinary case — a refusal or an unconfirmed numbering is `BulkResult.failed`.
            var batchFailure: Throwable? = null
            hidingThreadMembers(targetKeys) {
                // AccountCredentials is a data class: each batch receives exactly ITS ids.
                targets.groupBy { credentialsFor(it.email) }.forEach { (credentials, group) ->
                    if (credentials == null) { failedKeys += group.map { it.email.emailKey() }; return@forEach }
                    val result = runCatching { batchOp(credentials, group.map { it.email.id }) }
                        .getOrElse {
                            android.util.Log.w("SternaBulk", "batch op failed for ${credentials.id}", it)
                            if (batchFailure == null) batchFailure = it
                            MailRepository.BulkResult(emptySet(), group.mapTo(mutableSetOf()) { e -> e.email.id })
                        }
                    failedKeys += group.filter { it.email.id in result.failed }.map { it.email.emailKey() }
                    if (undoLabelRes != null) {
                        group.forEach { target ->
                            if (target.email.id in result.succeeded) undoCandidates += UndoCandidate(target, result.dest)
                        }
                    }
                }
            }
            restoreSearchResults(failedKeys)
            repo.resetSyncState()
            refresh()
            // Re-cache the touched conversations' members under their new folders.
            completeThreadsAfterAction(targets.map { it.email })
            // Whether an Undo may be offered is [selectionUndoEntries]' decision: a source folder
            // read off a row that only exists on screen sends the message somewhere it never was.
            val undoEntries = selectionUndoEntries(undoCandidates)
            // What the banner says is [selectionBanner]'s decision too, and it counts the CANDIDATES:
            // a batch that wrote ten messages of which only four can be put back still deleted ten.
            selectionBanner(undoLabelRes, undoCandidates, undoEntries)?.let { banner ->
                val resources = getApplication<Application>().resources
                // Twice on purpose: the first picks the CLDR plural category, the second fills %1$d.
                val label = resources.getQuantityString(banner.labelRes, banner.count, banner.count)
                _undo.value = UndoAction(undoEntries, label)
                scheduleUndoneDeparture()
            }
            // Partial and total are not the same news, counted over the WHOLE selection: a key
            // nothing resolved for was attempted and failed.
            val failed = failedKeys.size + resolved.unresolved.size + failedBefore
            // Which sentence a TOTAL failure gets is the swipe's decision: the throwable plus live
            // connectivity. Only here — PARTIAL cannot be an outage, and a count says nothing.
            val app = getApplication<Application>()
            val failure = batchFailure
            when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = app.getString(if (failure == null) R.string.status_action_failed else actionFailureMessage(failure, online = hasUsableNetwork(app)))
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * The IMAP numbering of every source folder [keys] name, as recorded RIGHT NOW — at the gesture,
     * the only moment this may be read. Account first, then folder: the same mailbox id in two
     * accounts is two different folders. The folder comes from the ID, exactly as `MailRepository`
     * groups its batches — not from the cached row's `mailboxId`, which can disagree. Empty for a
     * JMAP account. Twin of [recordedNumbering], keyed by the id's folder rather than the row's.
     */
    private suspend fun selectionNumbering(keys: Collection<EmailKey>): Map<String, Map<String, Long?>> {
        val byAccount = mutableMapOf<String, MutableMap<String, Long?>>()
        keys.forEach { key ->
            val credentials = credentialsFor(key) ?: return@forEach
            val folder = ImapMailService.mailboxOf(key.emailId) ?: return@forEach
            val folders = byAccount.getOrPut(credentials.id) { mutableMapOf() }
            if (folder !in folders) folders[folder] = repo.recordedUidValidity(credentials, folder)
        }
        return byAccount
    }

    /**
     * Delete the selection: the subset whose delete would permanently destroy is held back behind
     * Undo like a swipe delete (#23); the rest keeps the move-to-Trash bulk path. Which row is in
     * which subset is [planSelectionDelete]'s decision, never from an untrusted row's folder: on a
     * Trash-less account a row is left alone rather than destroyed on a supposition.
     */
    fun deleteSelected() {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            // The numbering each ticked row carried WHEN IT WAS TICKED — AWAITED, not sampled, and
            // FIRST INSIDE THE LAUNCH (#99, #189): everything below suspends or clears the
            // selection. Never a re-read: a re-read IS the realigned number, agreeing with itself.
            val ticked = awaitTickedNumbering(keys)
            // Frozen AT THE GESTURE and carried into the batch: an IMAP UID means something only
            // inside one numbering, and a bulk delete is a MOVE that runs after resolution (#99).
            val numbering = selectionNumbering(keys)
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            val plan = planSelectionDelete(
                targets = resolved.targets,
                hasTrash = { email -> credentialsFor(email)?.let { c -> runCatching { repo.accountHasTrash(c) }.getOrDefault(false) } ?: false },
                wouldDestroy = { email -> credentialsFor(email)?.let { c -> runCatching { repo.deleteWouldDestroy(c, email) }.getOrDefault(false) } ?: false },
            )
            // THE UPSTREAM HALF OF #99, the only half that can see a row SWAPPED UNDER A TICK.
            // `recordedNumbering` answers what each row carries NOW; opposed to the stamp of the
            // tick, a disagreement means another message wears the same UID text.
            val destroyTargets = plan.destroy.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
            val now = recordedNumbering(destroyTargets)
            val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(
                rows = plan.destroy,
                tickedUnder = { ticked[it.emailKey()] },
                readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },
            )
            clearSelection()
            val lostBefore = resolved.unresolved.size + plan.untreated.size
            // The rows the split refused were ATTEMPTED and failed: only the failures side grows.
            val lost = lostBefore + split.drifted.size
            if (split.kept.isNotEmpty()) {
                heldBackDestroy(split.kept, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (plan.move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo (see deleteThread).
                // The question is whether a destroy is actually PENDING (`split.kept`), not whether
                // one was planned: `plan.destroy` withholds the move's Undo for an unseen snackbar.
                bulkBatched(
                    undoLabelRes = R.plurals.status_selection_deleted.takeIf { split.kept.isEmpty() },
                    keys = plan.move.mapTo(mutableSetOf()) { it.emailKey() },
                    attemptedBefore = plan.destroy.size + lostBefore,
                    failedBefore = lost,
                ) { c, batch -> repo.deleteAll(c, batch, numbering[c.id].orEmpty()) }
            } else {
                // Nothing to move: delegating an empty batch just to speak would drop the sync
                // cursors and re-query the whole folder for an action that wrote nothing.
                when (bulkOutcome(attempted = keys.size, failed = lost)) {
                    BulkOutcome.NONE -> Unit
                    BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                    BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
                }
            }
            // LAST, after the branch above: `bulkBatched` calls clearSelection() synchronously, so
            // a re-selection written earlier would be wiped. What was refused goes back, still ticked.
            if (split.drifted.isNotEmpty()) {
                // THE STAMPS GO BACK FIRST, and they are the stamps OF THE TICK. The collector
                // only reads keys it does not know, so keys handed back bare are read AS THEY ARE
                // NOW and the second tap destroys what this refusal saved. Nothing suspends here.
                tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }
                _selectionActive.value = true
                _selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }
            }
        }
    }
    /** Archive the selection. A block, not an expression body: freezing the numbering is a
     * suspending read that has to happen HERE, at the tap (#99). The selection is cleared
     *  SYNCHRONOUSLY first — else a second tap fires the whole batch again. */
    fun archiveSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(
                undoLabelRes = R.plurals.status_selection_archived,
                keys = keys,
            ) { c, ids -> repo.archiveAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    /**
     * Move the selection to [targetMailboxId] (unarchive → Inbox, and move-to-folder). The messages
     * another account's folder cannot take are set aside, counted, and announced once.
     */
    /** #883 mail_move_target: Jev's suggested folder for the (first) selected message, listed first in the picker. */
    @Volatile private var moveCall: String? = null

    suspend fun suggestMoveTarget(offered: List<Pair<String, String>>): String? = withContext(Dispatchers.IO) {
        val key = _selectedKeys.value.firstOrNull() ?: return@withContext null
        val email = runCatching { repo.cachedEmailsByIds(listOf(key)).firstOrNull() }.getOrNull() ?: return@withContext null
        val s = MailJev.suggest(getApplication<Application>(), email.from.firstOrNull()?.email.orEmpty(), email.subject.orEmpty(), offered)
        moveCall = s?.call
        s?.pick
    }

    /** The person chose [folderId] in the picker: that is the outcome of the suggestion, right or wrong. */
    fun reportMoveChoice(folderId: String) {
        val call = moveCall ?: return
        moveCall = null
        com.diegonmarcos.superapp.decisions.link.Advice.explicit(
            getApplication<Application>(), "mail_move_target", call, org.json.JSONObject().put("folder", folderId))
    }

    fun moveSelectedTo(targetMailboxId: String, targetAccountId: String? = null) {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            // AWAITED, not sampled, and FIRST INSIDE THE LAUNCH — [deleteSelected]'s rule (#99,
            // #189). [moveSelectedToAccount] calls clearSelection() at its head, which empties the
            // field. The KEYS stay captured outside the launch: they say WHICH selection is awaited.
            val ticked = awaitTickedNumbering(keys)
            // The picker offered the SELECTION's account folders, so the move targets that account
            // (#73). Only that account's messages move; any from another are left untouched and
            // reported — the same target id in a sibling account is a different folder.
            val moveAccountId = selectionAccount(keys) ?: store.currentId()
            // Into another account's folder (#189): its own path, one message after the other.
            if (isCrossAccountMove(targetAccountId, moveAccountId)) {
                moveSelectedToAccount(keys, moveAccountId, requireNotNull(targetAccountId), targetMailboxId, ticked)
                return@launch
            }
            val (movable, skipped) = keys.partition { it.accountId == moveAccountId }
            clearSelection()
            if (movable.isNotEmpty()) {
                // Frozen at the gesture, for the movable subset only — the other account's
                // messages are not moved and have no folder here to oppose (#99).
                val numbering = selectionNumbering(movable)
                // Undoable like archive and delete (#73). The set-aside messages travel with the
                // delegation, so ONE message counts the whole selection.
                bulkBatched(
                    undoLabelRes = R.plurals.status_selection_moved,
                    keys = movable.toMutableSet(),
                    attemptedBefore = skipped.size,
                    failedBefore = skipped.size,
                ) { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId, numbering[c.id].orEmpty()) }
            } else if (skipped.isNotEmpty()) {
                // Nothing movable: an empty batch would drop the sync cursors and re-query the whole
                // folder. Say it here, and say the specific sentence — here it is exactly true.
                _message.value = getApplication<Application>().getString(R.string.status_move_other_account)
            }
        }
    }

    /**
     * The selection into a folder of ANOTHER account (#189), one message after the other through
     * [MailRepository.moveToAccount]. The partition is [moveSelectedTo]'s. No Undo (see
     * [moveToAccount]). ONE banner at the end, chosen by [crossAccountBanner].
     *
     * Whatever wrote NOTHING on account B comes back ticked, stamps first ([crossAccountGiveBack])
     * — every outcome that is neither `Moved` nor `CopiedNotRemoved`, because there is no Undo here.
     * Not "whatever did not leave A": a `CopiedNotRemoved` did not leave A either, and handing it
     * back would put a second copy on B at the next tap.
     */
    private suspend fun moveSelectedToAccount(
        keys: Set<EmailKey>,
        ownerAccountId: String?,
        targetAccountId: String,
        targetMailboxId: String,
        ticked: Map<EmailKey, Long?>,
    ) {
        val (movable, skipped) = keys.partition { it.accountId == ownerAccountId }
        // THE TARGET IS READ, AND REFUSED, BEFORE THE SELECTION IS TOUCHED. Account B can be
        // signed out between the tap that opened the picker and the tap that chose the folder;
        // `store.credentials` then answers null and this returns with nothing sent. Below
        // `clearSelection()` that return would drop the whole selection in silence.
        val app = getApplication<Application>()
        val target = store.credentials(targetAccountId)
        if (target == null) {
            _message.value = app.getString(R.string.status_action_failed)
            return
        }
        clearSelection()
        val emails = repo.cachedEmailsByIds(movable).associateBy { it.emailKey() }
        // THE HALF THAT CAN SEE A ROW SWAPPED UNDER A TICK (#99). `recordedNumbering` answers what
        // each row about to leave carries NOW; opposed to the stamp of the tick, a disagreement means
        // the line was replaced since the user pointed at it — and this move would copy it to B with
        // no Undo. Not the folder's own record, which a background pass has already realigned.
        val crossAccountTargets = emails.values.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
        val now = recordedNumbering(crossAccountTargets)
        val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(
            rows = emails.values.toList(),
            tickedUnder = { ticked[it.emailKey()] },
            readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },
        )
        val refused = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }
        // KEYED, AND FILLED AT EVERY EXIT OF THIS LOOP. `CrossAccountMove.Failed` carries a log
        // string and nothing else, so a bare list of verdicts answers HOW MANY did not move and
        // never WHICH. The give-back needs the WHICH, and `results[i]` matching `movable[i]` is a
        // positional invariant nobody wrote down.
        val outcomes = LinkedHashMap<EmailKey, CrossAccountMove>(movable.size)
        for (key in movable) {
            val source = credentialsFor(key)
            val email = emails[key]
            if (source == null || email == null) {
                outcomes[key] = CrossAccountMove.Failed("no cached row for ${key.emailId}")
                continue
            }
            // NOT ATTEMPTED: no copy on B, no Trash on A, and no search-result surgery — the row on
            // screen is not the row that was ticked. It counts as a failure, and crossAccountBanner
            // turns that verdict into an existing line either way.
            if (key in refused) {
                outcomes[key] = CrossAccountMove.Failed("renumbered since it was ticked: ${key.emailId}")
                continue
            }
            dropSearchResults(setOf(key))
            val result = hidingThreadMembers(setOf(key)) {
                // AND THE STAMP GOES DOWN THE WIRE, so a folder renumbered WHILE the loop runs is
                // refused by the server in the SELECT the read and the bin go out under. Without it
                // both fall back on the folder's record, which the first iteration that met the
                // renumbering has already realigned. `ticked[key]`, never a re-read.
                // AND IT IS WRAPPED IN `Frozen`, absent value included: a tick DID freeze here, so
                // a null means "frozen, and there was nothing" — refused downstream at both ends.
                runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.Frozen(ticked[key])) }
                    .getOrElseUnlessCancelled { CrossAccountMove.Failed(it.toString()) }
            }
            if (result !is CrossAccountMove.Moved) {
                android.util.Log.w("SternaInbox", "move to account, ${key.emailId}: $result")
                restoreSearchResults(listOf(key))
            }
            outcomes[key] = result
        }
        val results = outcomes.values.toList()
        // Which line goes with which outcome is crossAccountSelectionPlural's and
        // crossAccountSelectionFailedRes's; only the counts and the labels are built here.
        val outcome = crossAccountBanner(results, skipped.size)
        when (outcome) {
            CrossAccountOutcome.ALL_MOVED ->
                _message.value = app.resources.getQuantityString(
                    checkNotNull(crossAccountSelectionPlural(outcome)), results.size, results.size,
                )
            CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> {
                val copied = results.count { it is CrossAccountMove.CopiedNotRemoved }
                val where = crossAccountTargetLabel(accountLabel(target.id), folderLabel(target.id, targetMailboxId))
                _message.value = app.resources.getQuantityString(
                    checkNotNull(crossAccountSelectionPlural(outcome)), copied, copied, where, accountLabel(ownerAccountId),
                )
            }
            CrossAccountOutcome.PARTLY_FAILED, CrossAccountOutcome.ALL_FAILED ->
                _message.value = app.getString(checkNotNull(crossAccountSelectionFailedRes(outcome)))
        }
        if (results.any { it is CrossAccountMove.Failed }) refresh()
        // LAST, after the banner and never before it: clearSelection() runs at the head of this
        // body, so a re-selection written earlier would be wiped by the gesture it belongs to. What
        // did not move goes back under the user's eyes, still ticked — this path has no Undo.
        // AND IT IS INDEXED ON THE OUTCOME, not on a class of refusal ([crossAccountGiveBack]):
        // keyed on `split.drifted` it was empty by construction for the refusal raised at the READ.
        val givenBack = crossAccountGiveBack(outcomes, emails.keys)
        if (givenBack.isNotEmpty()) {
            // THE STAMPS GO BACK FIRST, and they are the stamps OF THE TICK. The collector on
            // _selectedKeys only reads keys it does not already know, so keys handed back without
            // their stamps get read AS THEY ARE NOW and the second tap sends what this refusal just
            // saved. The entry is written EVEN WHEN THE STAMP IS null: it is the PRESENCE of the
            // key that stops the collector re-reading, not its value.
            tickedUnder = givenBack.associateWith { ticked[it] }
            _selectionActive.value = true
            _selectedKeys.value = givenBack.toMutableSet()
        }
    }

    /**
     * Report the selection as spam / take it back out of Junk — both MOVES on IMAP, so both need the
     * numbering freeze: with nothing frozen the batch refuses every id while
     * `markReadOnMoveOutOfInbox` has already flagged them `\Seen` on the server (#99).
     *
     * Blocks, not expression bodies, for [archiveSelected]'s reason: freezing is a suspending read.
     */
    fun reportSpamSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(keys = keys) { c, ids -> repo.reportSpamAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    fun notSpamSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(keys = keys) { c, ids -> repo.notSpamAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    /** Snooze the whole selection until [until] (hidden now, returns to the inbox then). */
    fun snoozeSelected(until: Long) = bulk { c, id ->
        repo.snooze(id, c.id, until)
        Snoozes.enqueue(getApplication(), id, c.id, until)
    }

    // ---- folder management ----

    fun createFolder(name: String, parentId: String? = null) = folderOp { c -> repo.createFolder(c, name, parentId) }

    fun renameFolder(mailboxId: String, newName: String) = folderOp { c ->
        val newId = repo.renameFolder(c, mailboxId, newName)
        // IMAP ids are paths: the repo re-keyed the watch flags; follow with the baseline.
        if (newId != mailboxId) NewMailNotifier.rename(getApplication(), c.id, mailboxId, newId)
        // The fold registry IS re-read here, because the repo re-keys it exactly like the watch
        // flags (IMAP ids are paths). Without it the flow holds the OLD key, the renamed folder
        // reads as "nobody decided" and the default folds it on the spot (#185).
        refreshWatchedFolders()
        refreshCollapsedFolders()
    }

    /**
     * Subfolders (recursive) of a folder, from the cached drawer list — JMAP `parentId`
     * or the IMAP path prefix, mirroring the drawer's own tree building.
     */
    fun subfolderIdsOf(mailboxId: String): List<String> {
        val all = state.value.mailboxes
        val result = mutableListOf<String>()
        fun childrenOf(id: String): List<Mailbox> = all.filter { m ->
            if (m.id == id) return@filter false
            if (m.parentId != null) return@filter m.parentId == id
            val delim = when {
                m.id.contains('/') -> "/"
                m.id.contains('.') -> "."
                else -> return@filter false
            }
            m.id.substringBeforeLast(delim, "") == id
        }
        fun visit(id: String) {
            childrenOf(id).forEach { child ->
                if (result.add(child.id)) visit(child.id)
            }
        }
        visit(mailboxId)
        return result
    }

    private var folderDeleteJob: Job? = null
    /** (accountId, mailboxId) of the folder whose held-back delete is undoable. */
    private var pendingDeleteMailboxId: Pair<String, String>? = null
    private val _pendingFolderDelete = MutableStateFlow<String?>(null)

    /** Snackbar label while a folder delete waits out its undo window; null = none. */
    val pendingFolderDelete: StateFlow<String?> = _pendingFolderDelete.asStateFlow()

    /**
     * Delete a folder (and its subfolders) with an undo window: the folder leaves the drawer
     * immediately, and the server delete is PERSISTED WorkManager work with an initial delay, so a
     * confirmed delete cannot be silently dropped. The coroutine below only times the snackbar.
     */
    fun deleteFolder(mailboxId: String, folderName: String) {
        val credentials = store.load() ?: return
        val ids = listOf(mailboxId) + subfolderIdsOf(mailboxId)
        viewModelScope.launch { repo.hideMailboxesLocally(credentials.id, ids) }
        _pendingFolderDelete.value =
            getApplication<Application>().getString(R.string.inbox_folder_deleted, folderName)
        FolderDeleteWorker.schedule(getApplication(), credentials.id, mailboxId, PURGE_HOLD_BACK_MS)
        pendingDeleteMailboxId = credentials.id to mailboxId
        folderDeleteJob?.cancel()
        folderDeleteJob = viewModelScope.launch {
            delay(PURGE_HOLD_BACK_MS)
            if (pendingDeleteMailboxId == credentials.id to mailboxId) {
                pendingDeleteMailboxId = null
                _pendingFolderDelete.value = null
                refreshWatchedFolders()
            }
        }
    }

    /** Cancel a held-back folder delete (nothing was destroyed yet) and restore the drawer. */
    fun undoDeleteFolder() {
        pendingDeleteMailboxId?.let { (accountId, mailboxId) -> FolderDeleteWorker.cancel(getApplication(), accountId, mailboxId) }
        pendingDeleteMailboxId = null
        folderDeleteJob?.cancel()
        folderDeleteJob = null
        _pendingFolderDelete.value = null
        refresh()
    }

    // ---- folder watch (multi-folder push, issue #16) ----

    // Initialised inline, NOT from the init block: init runs before this declaration's
    // initialiser, so touching the flow there would NPE during ViewModel construction.
    private val _watchedFolders = MutableStateFlow(notifyingFolders())

    /** Folders watched for new mail on the current account (the inbox is always watched). */
    val watchedFolders: StateFlow<Set<String>> = _watchedFolders

    private fun refreshWatchedFolders() {
        _watchedFolders.value = notifyingFolders()
    }

    /** The folders that notify: the extras watched, plus the Inbox unless its "Notify about new mail" is off. */
    private fun notifyingFolders(): Set<String> {
        val id = store.currentId() ?: return emptySet()
        return notifyingFolderIds(store.watchedFolders(id), store.inboxMailboxId(), store.mutedFolders(id))
    }

    // ---- drawer folder tree: the fold/unfold choice, kept per account ----

    // Initialised inline for the same reason as [_watchedFolders] above: init runs before this
    // declaration's initialiser.
    private val _collapsedFolders =
        MutableStateFlow(store.currentId()?.let { store.collapsedFolders(it) } ?: emptyMap())

    /**
     * The current account's EXPLICIT fold/unfold choice per drawer folder; a folder with no key is
     * one nobody decided anything about. Per account and re-read on every account switch: IMAP ids
     * are paths, so a namesake folder on the neighbouring account would be drawn folded (#92/#121).
     */
    val collapsedFolders: StateFlow<Map<String, Boolean>> = _collapsedFolders

    // Initialised inline for the same reason as [_collapsedFolders] above.
    // `false` with no current account, and that is the SAFE side: it means "fold nothing by
    // itself", where `true` hides mail behind a row that may have no badge. `AccountStore.accounts()`
    // answers an EMPTY list when the stored blob failed to decode too, so this is reachable.
    private val _folderRowsBadgeUnread =
        MutableStateFlow(store.currentId()?.let { repo.folderRowsBadgeUnread(it) } ?: false)

    /**
     * Whether the current account's drawer folder rows can carry an unread count at all
     * ([MailRepository.folderRowsBadgeUnread]) — false on IMAP, whose rows hold a hard 0.
     *
     * The DEFAULT fold rests on that badge: a folder folded on the first run is honest only while
     * the folded row says how much mail it is hiding. Per account, re-read on every switch (#185).
     */
    val folderRowsBadgeUnread: StateFlow<Boolean> = _folderRowsBadgeUnread

    /**
     * Re-read everything the fold decision needs FOR THE CURRENT ACCOUNT: the explicit registry and
     * whether this account's rows can badge unread at all. One function, because both must move
     * together on a switch.
     *
     * The account id is read ONCE: every `store.` call decodes the whole account blob afresh, and
     * this runs on the main thread at every tap of a chevron.
     */
    private fun refreshCollapsedFolders() {
        val accountId = store.currentId()
        _collapsedFolders.value = accountId?.let { store.collapsedFolders(it) } ?: emptyMap()
        _folderRowsBadgeUnread.value = accountId?.let { repo.folderRowsBadgeUnread(it) } ?: false
    }

    /**
     * Store the drawer's fold/unfold choice for one folder, then re-read it. Nothing else is
     * armed: this is display state. [collapsed] is written both ways — writing false is a CHOICE.
     */
    fun setFolderCollapsed(mailboxId: String, collapsed: Boolean) {
        val accountId = store.currentId() ?: return
        store.setFolderCollapsed(accountId, mailboxId, collapsed)
        refreshCollapsedFolders()
    }

    /** Toggle new-mail notifications for one folder, then re-arm push to pick it up. */
    fun setFolderWatched(mailboxId: String, watched: Boolean) {
        val accountId = store.currentId() ?: return
        if (mailboxId == store.inboxMailboxId()) {
            // The Inbox is always refreshed (its baseline keeps advancing while it is off, so switching it
            // back on announces only mail that arrives afterwards): the choice is a mute, not a watch.
            store.setFolderMuted(accountId, mailboxId, muted = !watched)
            refreshWatchedFolders()
            return
        }
        store.setFolderWatched(accountId, mailboxId, watched)
        // Dropping the baseline means a later re-watch reseeds silently (no stale diff).
        if (!watched) NewMailNotifier.clear(getApplication(), accountId, mailboxId)
        refreshWatchedFolders()
        PushController.apply(getApplication(), userInitiated = true)
    }

    private fun folderOp(op: suspend (AccountCredentials) -> Unit) {
        viewModelScope.launch {
            val credentials = store.load() ?: return@launch
            runCatching { op(credentials) }
                .onFailure { _message.value = it.message ?: getApplication<Application>().getString(R.string.status_folder_op_failed) }
        }
    }

    /**
     * Toggle read/unread for the selection — marks read if any are unread, else marks unread — and
     * keeps the selection.
     *
     * Every selected key is resolved first ([resolveSelectionTargets]), rows drawn by a search
     * included: walking the cache's ANSWER dropped every hit with no local row, so the toggle acted
     * on some of the selection and let the untouched rows vote on the direction. Nothing here reads
     * the row's own `mailboxId`, so a drawn row is as good as a cached one.
     */
    fun toggleSelectedRead() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            val emails = resolved.targets.map { it.email }
            val targetSeen = !emails.all { it.isSeen }
            patchThreadMembersSeen(emails.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            // A key no row could be found for was never written: it counts as a failed attempt, or a
            // selection nothing resolved for lands on "nothing failed" and says nothing.
            var failed = resolved.unresolved.size
            emails.forEach { email ->
                val credentials = credentialsFor(email)
                if (credentials == null) { failed++; return@forEach }
                runCatching { repo.setRead(credentials, email.id, targetSeen) }
                    .onFailure { failed++; android.util.Log.w("SternaInbox", "setRead (selection) failed for ${email.id}", it) }
            }
            if (targetSeen) dismissReadNotifications(emails.map { it.emailKey() })
            // Reflect the new state immediately so the toggle icon flips without re-selecting.
            _selectionAllRead.value = targetSeen
            // Partial and total are not the same news, counted over the WHOLE selection, unresolved
            // keys included.
            when (bulkOutcome(attempted = keys.size, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * Which way the toggle's icon points, over the same resolution the action uses — otherwise the
     * icon speaks for the cached subset of a selection the action will act on whole. "All read"
     * needs at least one target: `emptyList().all { }` is `true`.
     */
    private suspend fun refreshSelectionReadState(keys: Set<EmailKey>) {
        val targets = resolveSelectionTargets(
            keys = keys,
            cached = repo.cachedEmailsByIds(keys),
            displayed = searchState.value.results,
        ).targets
        _selectionAllRead.value = targets.isNotEmpty() && targets.all { it.email.isSeen }
    }

    // ---- inline search ----

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        searchRemoved.clear()
        searchState.value = if (active) SearchUi(active = true) else SearchUi()
        if (!active) {
            // Deliberately DON'T cancel the crawl: on a large mailbox it needs to run to completion,
            // and it is throttled + idempotent. Only the live query stops here.
            return
        }
        // Instant coverage floor from the cache (the first query awaits this), then crawl the whole
        // mailbox's headers into the index in the background. The crawl walks newest→oldest and
        // merges after EACH page; merges only ever ADD, so nothing flickers away.
        indexJob = viewModelScope.launch { runCatching { repo.seedIndexFromCache() } }
        // Guard against a second concurrent crawl if search is reopened while one is still running.
        if (crawlJob?.isActive == true) return
        crawlJob = viewModelScope.launch {
            val refresh: suspend () -> Unit = {
                searchState.value.query.takeIf { it.isNotBlank() }?.let { q ->
                    val local = runCatching { repo.searchIndex(q) }.getOrNull()
                    if (local != null && searchState.value.query == q) {
                        searchState.value = searchState.value.copy(
                            results = mergeHits(searchState.value.results.orEmpty(), local),
                        )
                    }
                }
            }
            searchAccounts().forEach { runCatching { repo.syncSearchIndex(it, onPage = refresh) } }
            refresh()
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchState.value = searchState.value.copy(query = query, results = null, loading = false)
            return
        }
        searchState.value = searchState.value.copy(query = query, loading = true, complete = true)
        searchJob = viewModelScope.launch {
            // 1) Local FTS first: instant on every keystroke, offline, accent-folded, prefix-matched
            //    ("eco*" finds écologie/écologique/…), over the header index of the whole mailbox.
            indexJob?.join()
            // A failed local index gets the same treatment as a failed server leg below: a leg of
            // the answer that didn't run. A locked or damaged FTS table (the ground of #71) dropped
            // every hit only this index finds, in silence, under a count calling itself a total.
            var localComplete = true
            val local = runCatching { repo.searchIndex(query) }.getOrElse { error ->
                if (error is CancellationException) throw error
                localComplete = false
                emptyList()
            }
            if (searchState.value.query != query) return@launch
            searchState.value = searchState.value.copy(
                results = local.filterNot { it.emailKey() in searchRemoved },
                loading = true,
                complete = localComplete,
            )
            // 2) Server full-text after a short typing pause: the server's index sees message bodies
            //    and the whole archive. UNION only — server hits can add to what's shown, never
            //    remove it; cancellation plus the current-query check discard stale responses.
            delay(SERVER_SEARCH_DEBOUNCE_MS)
            // A failed server leg (or one that dropped an unreachable account) must not pass for
            // a complete answer: the count says "at least N" instead of a total it can't back.
            val server = runCatching {
                repo.search(searchAccounts(), SearchQuery(text = query), SERVER_SEARCH_LIMIT)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                MailSearchResult(emptyList(), complete = false)
            }
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(
                    results = mergeHits(searchState.value.results.orEmpty(), server.emails),
                    loading = false,
                    // BOTH legs must have run to the end for this to be a total; a good server
                    // answer doesn't undo a local index that fell over.
                    complete = searchComplete(local = localComplete, server = server.complete),
                )
            }
        }
    }

    /** The accounts the current view searches over (unified inbox → all, single folder → current). */
    private fun searchAccounts(): List<AccountCredentials> = when (selection.value) {
        Sel.Unified -> store.allCredentials()
        // One account's folders, so one account's search — same answer as a single folder.
        Sel.Unread -> listOfNotNull(store.load())
        is Sel.Folder -> listOfNotNull(store.load())
    }

    /** Union of two hit lists (by account+id), newest first. Rows the user just removed stay out:
     *  the FTS index and the server both still know an evicted message (see [searchRemoved]). */
    private fun mergeHits(a: List<Email>, b: List<Email>): List<Email> =
        (a + b).distinctBy { it.accountId to it.id }
            .filterNot { it.emailKey() in searchRemoved }
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .sortedByDescending { it.receivedAt ?: "" }

    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val PURGE_HOLD_BACK_MS = 5_000L

        /** Most threads re-completed per action — bounds the Thread/get fan-out on a select-all. */
        const val MAX_THREAD_COMPLETIONS = 10
    }
}

/**
 * The banners a just-performed local removal must take down: one key per Undo entry.
 *
 * Two things it must not get wrong, both silent: the id is [UndoEntry.emailId], the id the message
 * wore AT THE GESTURE (an IMAP id IS `imap:account:folder:uid`, so the move rewrites it, and the
 * banner only answers to the one that was live when it was posted); and the key carries the entry's
 * OWN [UndoEntry.accountId], a null one staying null — two accounts on one server share message
 * ids, so an unqualified id cancels the neighbour's banner (#92), and null resolves downstream.
 */
internal fun undoBannerKeys(entries: List<UndoEntry>): List<EmailKey> =
    entries.map { EmailKey(it.accountId, it.emailId) }

/**
 * The single account a multi-select belongs to, or null when it is empty or spans accounts. Pure, so
 * the move-to-folder picker's account resolution can be unit-tested (#73): the picker offers this
 * account's folders and the move targets it, instead of the active account's.
 */
internal fun selectionAccount(keys: Set<EmailKey>): String? =
    keys.map { it.accountId }.distinct().singleOrNull()

/**
 * Whether the LIST's move picker may name an account other than the one the gesture will act on —
 * true only while the selection resolves to ONE account ([selectionAccount]).
 *
 * On a selection spanning accounts the move takes the retained account's messages and counts the
 * others as skipped, while [moveOwnerAccountId] has no selection account and falls back to the
 * ACTIVE one. Handed that fallback as its owner, the picker would offer every account and announce
 * a move half the selection never makes.
 */
internal fun selectionAllowsAnotherAccount(keys: Set<EmailKey>): Boolean = selectionAccount(keys) != null

/**
 * The accounts the LIST's move picker may offer, given what is selected: all of them while the
 * selection allows another account, none at all otherwise. An empty list is enough to draw nothing —
 * [MoveAccountRow] returns straight away below two entries.
 */
internal fun selectionPickerAccounts(
    accounts: List<StoredAccount>,
    keys: Set<EmailKey>,
): List<StoredAccount> = if (selectionAllowsAnotherAccount(keys)) accounts else emptyList()

/**
 * The account choice the LIST's picker is allowed to ACT on: the chosen one while the selection
 * allows another account, null otherwise — back to the owner's own folders.
 *
 * Hiding the account row is not enough: the choice is held by the ViewModel so it survives a
 * rotation, and a sheet closed another way leaves the next opening, on a DIFFERENT selection,
 * listing the chosen account's folders and still sending the gesture there, with no row on screen
 * to say so (#189).
 */
internal fun selectionMoveAccount(chosen: String?, keys: Set<EmailKey>): String? =
    chosen.takeIf { selectionAllowsAnotherAccount(keys) }

/**
 * What "Select all" ([selectAllKeys]) is allowed to take: the list that is ON SCREEN.
 *
 * Two branches, because the two lists are not the same kind of thing. The search results are held
 * whole in the ViewModel, so "everything shown" is a value we have; the navigation list is a
 * `LazyPagingItems`, which does not expose the pages it has not loaded, so outside a search the
 * folder's cached ids stay the answer. The predicate is the SCREEN's ([searchDisplay]): two
 * predicates for one question drift apart, and the user selects one list and deletes another.
 */
/**
 * The roles map of [InboxViewModel.folderRoles], as a flow of its own so the RE-SCOPING can be run
 * by a test (#115). [accounts] is the account list as a live value; [roles] opens the (account,
 * folder) → role map for a set of account ids.
 *
 * It hangs off the account list and not off `unifiedInboxScopes`: those are (account, inbox) PAIRS,
 * an account has no inbox id until its first folder sync, and a StateFlow does not re-emit an equal
 * value — so adding an account moved nothing at all. [distinctUntilChanged] on the id set keeps the
 * store's other writes from re-subscribing to Room for nothing.
 */
internal fun folderRolesFlow(
    accounts: Flow<List<StoredAccount>>,
    roles: (List<String>) -> Flow<Map<Pair<String, String>, String>>,
): Flow<Map<Pair<String, String>, String>> =
    accounts
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest(roles)

internal fun selectAllKeys(
    searching: Boolean,
    query: String,
    results: List<EmailKey>?,
    loading: Boolean,
    complete: Boolean,
    folderKeys: List<EmailKey>,
): Set<EmailKey> {
    val shown = results.orEmpty()
    val onScreen = searching && query.isNotBlank() &&
        searchDisplay(shown.size, loading, complete) == SearchDisplay.RESULTS
    return if (onScreen) shown.toSet() else folderKeys.toSet()
}

/**
 * Which sentence a failed row action shows: the offline one, or the generic one.
 *
 * The exception's own text never reaches the screen: `it.message` on a transport failure is the
 * platform resolver's string, in English whatever the app's language. It is logged instead.
 *
 * "offline" is a claim about the DEVICE ([app.sterna.net.isOfflineFailure]), so it is only made
 * when the transport died AND the connectivity check confirms there is no usable network. A server
 * that is down, a rejected password, a VPN killswitch on a live Wi-Fi keep the generic wording (#65).
 */
@StringRes
internal fun actionFailureMessage(t: Throwable, online: Boolean): Int =
    if (isOfflineFailure(t, online)) R.string.status_action_offline else R.string.status_action_failed

/**
 * The folders that notify about new mail: the extras watched, plus the Inbox unless it is muted. The Inbox
 * is ON by default (it is in no stored set), and muting it touches no other folder.
 */
internal fun notifyingFolderIds(watched: Set<String>, inboxId: String?, muted: Set<String>): Set<String> =
    watched + listOfNotNull(inboxId?.takeIf { it !in muted })

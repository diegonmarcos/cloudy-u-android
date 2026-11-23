package app.sterna.ui.message

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.OcrResult
import app.sterna.core.data.mail.CoreReaderDoc
import app.sterna.core.data.mail.ReaderSnapshot
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.MessageUnavailableException
import app.sterna.core.data.mail.requireAttachmentBytes
import app.sterna.ui.attachment.AttachmentOpen
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.CryptoKind
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.push.Notifications
import app.sterna.snooze.Snoozes
import app.sterna.ui.compose.receivingAddress
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.data.calendar.ICalendar
import app.sterna.core.data.calendar.ParsedEvent
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.alreadyBlocked
import app.sterna.core.data.filter.blockableSender
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeHeader
import app.sterna.core.data.mail.UnsubscribeMailPreview
import app.sterna.core.data.mail.UnsubscribeBodyScan
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.mail.confirmationTarget
import app.sterna.core.data.mail.preferredAction
import app.sterna.core.data.mail.unsubscribePreview
import app.sterna.core.data.text.LanguageGuess
import app.sterna.core.data.text.ReaderTextAi
import app.sterna.core.data.text.SummaryOutcome
import app.sterna.core.data.text.TranslationOutcome
import app.sterna.core.data.text.InPlaceHtmlTranslation
import app.sterna.ui.text.MailTextToolsPrefs
import app.sterna.ui.text.TextToolsReaderEngine
import app.sterna.ui.text.textToolsClient
import app.sterna.core.data.settings.REPLY_BAR_DEFAULT
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import app.sterna.core.data.unsubscribe.UnsubscribeResult
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import app.sterna.net.hasUsableNetwork
import app.sterna.ui.readerActionFailureText
import app.sterna.ui.inbox.isSelfAuthored
import app.sterna.ui.inbox.moveTargets
import app.sterna.ui.inbox.pickerAccount
import app.sterna.ui.inbox.pickerExcludedMailbox
import app.sterna.ui.inbox.showOnlySubscribedFor
import app.sterna.ui.sender.trashFilePath
import app.sterna.ui.showsRecipients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    /** [signIn]: the server refused the login even after a token refresh; offer 'Sign in again'. */
    data class Error(val message: String, val signIn: Boolean = false) : MessageState
}

/** The opened message. [header] has the summary fields shown at once; [body] is the full email and its
 *  [inlineImages]. The reader shows a single message — the conversation lives in the list's unfold. */
data class ThreadMessage(
    val id: String,
    val header: Email,
    val body: Email? = null,
    val inlineImages: Map<String, String> = emptyMap(),
)

/**
 * Download/parse state for a calendar invite. [failed] is true if the .ics could not be fetched or
 * parsed, and the card then offers to open the raw invitation.
 */
data class CalendarInvite(
    val loading: Boolean = false,
    val event: ParsedEvent? = null,
    val failed: Boolean = false,
    val part: EmailBodyPart? = null,
    val ownerId: String? = null,
    /** State of the RSVP reply for this invite (idle until the user taps Accept/Decline/…). */
    val response: InviteResponse = InviteResponse.Idle,
)

/** RSVP reply lifecycle for a calendar invite, reflected on the event card. */
sealed interface InviteResponse {
    data object Idle : InviteResponse
    data object Sending : InviteResponse
    /** Reply sent; [partstat] is ACCEPTED / DECLINED / TENTATIVE. */
    data class Sent(val partstat: String) : InviteResponse
    data object Failed : InviteResponse
}

/** OpenPGP state of the opened message, driving the status card + header badges. */
sealed interface CryptoUiState {
    /** Ordinary mail — no crypto UI at all. */
    data object None : CryptoUiState

    /** Crypto detected; [decrypting] while a decrypt/verify call is in flight. */
    data class Locked(val kind: CryptoKind, val decrypting: Boolean = false) : CryptoUiState

    /** The provider needs the user (passphrase/key); launch [pendingIntent] to continue. */
    data class NeedsInteraction(val pendingIntent: PendingIntent) : CryptoUiState

    /** Decrypted and/or verified. */
    data class Decrypted(val result: MessageCrypto.Decrypted) : CryptoUiState

    /** Decrypt/verify failed; null message = no OpenPGP provider installed. */
    data class Failed(val message: String?) : CryptoUiState
}

/** What the sender's row in the participants panel offers about the per-sender filter rule. */
enum class SenderRuleEntry {
    /** No entry at all. */
    ABSENT,

    /** There, and tappable. */
    OFFERED,

    /** There, greyed: the account's script already sends this address away. */
    ALREADY_RULED,

    /** There, greyed: another Sieve script is active, so saving would switch it off. */
    FOREIGN_SCRIPT,
}

/**
 * What the reader knows about the account's Sieve script — THREE states, because "not read yet" and
 */
sealed interface SenderScript {
    /** Nothing has been asked. The panel arms the read; until it answers there is no opinion. */
    data object Unread : SenderScript

    /** A read was made and did not answer — offline, a dead connection, a server that threw. */
    data object Unreachable : SenderScript

    /** The server answered, with whatever it had to say. */
    data class Read(val state: FilterRulesState) : SenderScript
}

/**
 * Whether the reader may add "file future mail from this address into the Trash, marked read" from the
 */
fun senderRuleEntry(
    isSender: Boolean,
    trashPath: String?,
    script: SenderScript,
    address: String,
    ownAddresses: List<String>,
): SenderRuleEntry {
    val rules = (script as? SenderScript.Read)?.state
    return when {
        !isSender -> SenderRuleEntry.ABSENT
        // Before the account's script is consulted at all: no state of the server makes a rule on
        // one's own address, or on no address, worth offering.
        !blockableSender(address, ownAddresses) -> SenderRuleEntry.ABSENT
        trashPath == null -> SenderRuleEntry.ABSENT
        script is SenderScript.Unread -> SenderRuleEntry.ABSENT
        rules is FilterRulesState.Unsupported -> SenderRuleEntry.ABSENT
        // "Already there" answers before "cannot", as addBlockRule does: neither case writes
        // anything, and "another script is active" on an address already handled reports an obstacle
        // where there is none.
        rules is FilterRulesState.Loaded && alreadyBlocked(rules.rules, address) ->
            SenderRuleEntry.ALREADY_RULED
        rules is FilterRulesState.Loaded && rules.foreignActiveScript -> SenderRuleEntry.FOREIGN_SCRIPT
        else -> SenderRuleEntry.OFFERED
    }
}

/** The words each state of the entry wears — greyed entries say WHY, they do not just look dead. */
fun senderRuleLabel(entry: SenderRuleEntry): Int = when (entry) {
    SenderRuleEntry.ALREADY_RULED -> R.string.sender_volume_block_done
    SenderRuleEntry.FOREIGN_SCRIPT -> R.string.sender_volume_block_foreign
    else -> R.string.sender_volume_block
}

/**
 * Lifecycle of an unsubscribe on the open message. Session-only, like [InviteResponse]: remembering
 * that a list was left would mean a column in the message table.
 */
sealed interface UnsubscribeState {
    data object Idle : UnsubscribeState

    /** The POST is in flight (the `mailto:` form never lingers here — the outbox takes it). */
    data object Sending : UnsubscribeState

    /** The sender's server accepted the one-click request. */
    data object Sent : UnsubscribeState

    /** The unsubscribe mail is in the outbox; delivery and retry are the outbox's business. */
    data object Queued : UnsubscribeState

    data class Failed(val reason: UnsubscribeFailure) : UnsubscribeState
}

/**
 * An unsubscribe waiting for the reader to confirm it, WITH the options it was offered for: they
 */
data class PendingUnsubscribe(
    val action: UnsubscribeAction,
    val options: UnsubscribeOptions,
) {
    /** What the confirmation names — the same decision the action itself follows. */
    val target: String? get() = options.confirmationTarget(action)

    /** The subject and body the mail would carry, or null when this gesture is not a mail. Read from
     *  [unsubscribePreview], which is also what `sendUnsubscribeMail` builds the outbox row from: the
     *  dialog cannot show one text and send another. */
    val mailPreview: UnsubscribeMailPreview? get() = options.mailto?.let { unsubscribePreview(it) }
}

/**
 * The gesture still on offer for [options] in [state], or null. ONE decision, executed by the banner,
 */
fun offeredUnsubscribeAction(options: UnsubscribeOptions?, state: UnsubscribeState): UnsubscribeAction? =
    when (state) {
        UnsubscribeState.Sending, UnsubscribeState.Sent, UnsubscribeState.Queued -> null
        UnsubscribeState.Idle, is UnsubscribeState.Failed -> options?.preferredAction()
    }

/**
 * Which of its four shapes the unsubscribe strip draws. [ACTION], [SENDING] and [DONE] are the same
 */
enum class UnsubscribeStripBody {
    /** Nothing done yet: one button, no sentence. */
    ACTION,

    /** In flight: the same button, its icon replaced by a spinner, and it cannot be pressed. */
    SENDING,

    /** Sent or queued: no button at all, one terminal line. */
    DONE,

    /** Refused, or the network died: the reason, and the button again. */
    FAILED,
    ;

    /** Whether the strip's button may START an unsubscribe here. It must agree with
     *  [offeredUnsubscribeAction] in every state, and a test holds the two together: a live button
     *  where the action refuses to run does nothing, and the reverse is a second POST. */
    val acts: Boolean get() = this == ACTION || this == FAILED
}

/** See [UnsubscribeStripBody]. */
fun unsubscribeStripBody(state: UnsubscribeState): UnsubscribeStripBody = when (state) {
    UnsubscribeState.Idle -> UnsubscribeStripBody.ACTION
    UnsubscribeState.Sending -> UnsubscribeStripBody.SENDING
    UnsubscribeState.Sent, UnsubscribeState.Queued -> UnsubscribeStripBody.DONE
    is UnsubscribeState.Failed -> UnsubscribeStripBody.FAILED
}

/**
 * The raw-headers viewer's state (issue #60). Null (see [MessageViewModel.headers]) means the
 * viewer is closed; the states below drive it while open.
 */
sealed interface HeadersState {
    data object Loading : HeadersState
    data class Loaded(val headers: List<EmailHeader>) : HeadersState
    data class Error(val message: String) : HeadersState
}

/**
 * The attached-message sheet's state: what a tap on a `message/rfc822` part opens. Null (see
 * [MessageViewModel.attachedMessage]) means closed. Same shape as [HeadersState].
 */
sealed interface AttachedMessageState {
    data object Loading : AttachedMessageState
    data class Loaded(val message: AttachedMessage) : AttachedMessageState
    data class Error(val message: String) : AttachedMessageState
}

/** A copy with the $seen keyword set, so a just-opened/expanded message renders as read. */
private fun Email.markRead(): Email =
    if (isSeen) this else copy(keywords = keywords + ("\$seen" to true))

/** What [MessageViewModel.confirmLinks] carries before DataStore has answered: ask. The reader can
 *  dismiss a confirmation she did not want but cannot un-open a page — seeded `false`, a link tapped
 *  in the reader's first frames was fetched straight away, silently. */
internal const val CONFIRM_LINKS_UNLOADED = true

/**
 * Which failure to open a message earns a sentence of OUR own, and which keeps the text it carries.
 */
internal fun readFailureStringRes(t: Throwable): Int? = when (t) {
    is ContentTooLargeException -> R.string.status_message_too_large
    // A 401 that survived the automatic refresh-and-retry: a clear action, never 'HTTP 401'.
    is app.sterna.core.data.mail.SignInRequiredException -> R.string.message_sign_in_required
    // ONE sentence, naming no cause. Offline, destroyed server-side and a renumbered folder are
    // the expected three, but the repository wraps EVERY failure of that fetch — an expired password
    // and a bad certificate land here too.
    is MessageUnavailableException -> R.string.status_message_unavailable
    else -> null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val storage = application.container.storageRepository
    private val settings = application.container.settingsRepository

    /** Whether tapped links should have tracking params stripped before opening. */
    val stripTracking = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    /** Whether to show the destination and ask before opening a tapped link. */
    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CONFIRM_LINKS_UNLOADED,
    )

    /**
     * Whether an opened message that asked for a read receipt may raise the question — the setting of
     */
    val readReceiptSetting: StateFlow<ReadReceiptSetting> = settings.askReadReceipt
        .map { if (it) ReadReceiptSetting.ON else ReadReceiptSetting.OFF }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ReadReceiptSetting.NOT_LOADED,
        )

    /**
     * #1837 "Load remote images": on by default. Eagerly started with the ViewModel, so the stored value is in by the time a page
     * composes; the first frame is the default the stored value is read against ([app.sterna.core.data.settings.REMOTE_IMAGES_DEFAULT]).
     */
    val remoteImages = settings.remoteImages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = app.sterna.core.data.settings.REMOTE_IMAGES_DEFAULT,
    )

    /** Sender addresses whose remote images load automatically. */
    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    /** Whether the reader shows the bottom Reply/Forward bar at all (#63). Global, so the bar does
     *  not come and go while paging through a unified inbox. The first frame is REPLY_BAR_DEFAULT,
     *  the same definition the stored value is read against. */
    val replyBar = settings.replyBar.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = REPLY_BAR_DEFAULT,
    )

    /** Reading text size for the message body. */
    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    /** Add/remove a sender from the always-show-images allowlist. */
    fun setImagesAlwaysAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    /** The whole conversation as a stack of collapsible messages, oldest first. */
    private val _messages = MutableStateFlow<List<ThreadMessage>>(emptyList())

    /**
     * #1837 phase 2: the open message's reader document from the Rust core's store (MIME decoded in the right charset, HTML
     * sanitised, inline images embedded, a Gmail-like stylesheet), or null while it is not there: the screen then draws the old
     * path. Read from the store with no network, so it is there at once and offline; logged under the tag MailCore.
     */
    private val _coreDoc = MutableStateFlow<CoreReaderDoc?>(null)
    val coreDoc: StateFlow<CoreReaderDoc?> = _coreDoc.asStateFlow()
    val messages = _messages.asStateFlow()

    /** Transient status while downloading/opening an attachment. */
    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus = _attachmentStatus.asStateFlow()

    /** The opened message's calendar invite (if any): download/parse state for the event card. */
    private val _calendar = MutableStateFlow<CalendarInvite?>(null)
    val calendar = _calendar.asStateFlow()

    /** Guards against re-downloading the invite on every recomposition of the same message. */
    private var calendarLoadedFor: String? = null

    /** An attachment is already on its way to a viewer app: tapping one starts a download, so the
     *  chooser appears a beat later and a second tap used to start the whole thing again.
     *  `rememberLeaveOnce` cannot cover it — the hand-off happens after a suspending download, and a
     *  ViewModel has no composition to latch onto. */
    private var openingAttachment = false

    /** An image attachment is already being scanned (task #461): the tap starts a suspending
     *  download, and a second tap would start the whole thing again. Held across the download like
     *  [openingAttachment] — the guard has to cover the download, not just the engine call. */
    private var scanningAttachment = false

    /** The ONE shared image-scan engine (libs:ml-l-image), created lazily: most sessions
     *  never scan anything, and ML Kit's text-recognition client is a singleton per options, so
     *  one instance per reading-scope is already reuse, not churn. */
    private val scanEngine: ImageScanEngine by lazy { ImageScanEngine(application) }

    private fun updateMessage(id: String, transform: (ThreadMessage) -> ThreadMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    /** Role of the folder the opened message is filed under: the single source of truth behind the
     *  folder-dependent reader actions (spam ↔ not-spam, destroy vs. move-to-Trash, and what
     *  Drafts/Sent must not offer at all — #82). */
    private val _mailboxRole = MutableStateFlow<String?>(null)
    val mailboxRole = _mailboxRole.asStateFlow()

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    val inJunk = _mailboxRole
        .map { it == "junk" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message sits in Trash, so the reader's delete destroys (Codeberg #23). */
    val inTrash = _mailboxRole
        .map { it == "trash" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message is the user's own outgoing mail, so the header names the
     *  recipients instead of the sender — yourself (Codeberg #59). */
    private val _ownMessage = MutableStateFlow(false)
    val ownMessage = _ownMessage.asStateFlow()

    /** Which of the account's own addresses the open message was addressed to, named in the
     *  participants panel so an account with several aliases can tell which one received it (#81).
     *  Null when none is in To/Cc — then nothing is shown rather than a guess. */
    private val _deliveredTo = MutableStateFlow<String?>(null)
    val deliveredTo = _deliveredTo.asStateFlow()

    /** Deadline of the snooze in force on the open message, or null. Lets the Snooze menu say WHEN
     *  the message comes back instead of listing mute delays (#82). */
    private val _snoozedUntil = MutableStateFlow<Long?>(null)
    val snoozedUntil = _snoozedUntil.asStateFlow()

    /** The folder the open message is filed under, resolved reliably (the body fetch can drop it).
     *  Handed to the inbox's delete so it can tell move-to-Trash from a permanent destroy (#23). */
    private val _mailboxId = MutableStateFlow<String?>(null)
    val mailboxId = _mailboxId.asStateFlow()

    /** Local account the open message belongs to, resolved once per [load] (the page's own
     *  account in the unified inbox, else the current one). Scopes [moveTargets]. */
    private val _ownerAccountId = MutableStateFlow<String?>(null)

    /** The owner, for the picker's account row (#189). */
    val moveOwnerAccountId: StateFlow<String?> = _ownerAccountId.asStateFlow()

    /**
     * The account chosen on the picker's account row (#189), null for the owner's. Survives a
     * rotation; put back to null when the picker closes and when another message is loaded.
     */
    private val _moveAccountId = MutableStateFlow<String?>(null)
    val moveAccountId: StateFlow<String?> = _moveAccountId.asStateFlow()

    fun chooseMoveAccount(id: String?) {
        _moveAccountId.value = id
    }

    /** The configured accounts, for the picker's account row. */
    val accounts: StateFlow<List<StoredAccount>> get() = store.accountsFlow

    /** The account whose folders the picker LISTS ([pickerAccount]): the chosen one, else the owner. */
    private val movePickerAccountId: Flow<String?> =
        combine(_moveAccountId, _ownerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }

    /**
     * Folders offered by the reader's move-to-folder picker (#73): the folders of THE OPEN MESSAGE'S
     */
    val moveTargets: StateFlow<List<Mailbox>> = combine(
        movePickerAccountId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
        },
        combine(_moveAccountId, _ownerAccountId, _mailboxId) { chosen, owner, current -> pickerExcludedMailbox(chosen, owner, current) },
        combine(store.accountsFlow, movePickerAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) },
    ) { folders, current, onlySubscribed -> moveTargets(folders, current, onlySubscribed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The OPEN MESSAGE's account's folders, WHOLE — scoped by [_ownerAccountId] and nothing else. What
     */
    val accountMailboxes: StateFlow<List<Mailbox>> = _ownerAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The LISTED account's folders, WHOLE — the context the picker resolves a folder's parent path
     *  against (#109). [moveTargets] cannot serve: it drops the folder the message is filed under,
     * which is often the very parent that names its siblings. The picker's only. */
    val pickerMailboxes: StateFlow<List<Mailbox>> = movePickerAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The account's filter rules as last read; see [senderRuleEntry] for what each of the three states
     */
    private val _senderRules = MutableStateFlow<SenderScript>(SenderScript.Unread)
    val senderRules = _senderRules.asStateFlow()

    /** Every address that IS the user on the open message's account, so the rule gesture can refuse its
     *  own sender. Per message and not per app: the reader is a pager, and the unified inbox makes it
     *  cross ACCOUNTS. */
    private val _accountAddresses = MutableStateFlow<List<String>>(emptyList())
    val accountAddresses = _accountAddresses.asStateFlow()

    /**
     * The addresses the user has DECLARED as her own on this account — her send-as identities, and
     */
    private val _identityAddresses = MutableStateFlow<List<String>>(emptyList())
    val identityAddresses = _identityAddresses.asStateFlow()

    /** One-shot word about the rule gesture (added / already there / failed), shown and cleared. */
    private val _senderRuleStatus = MutableStateFlow<String?>(null)
    val senderRuleStatus = _senderRuleStatus.asStateFlow()

    fun clearSenderRuleStatus() { _senderRuleStatus.value = null }

    /**
     * One-shot word about an action taken ON the open message (report spam / not spam). Its own flow,
     */
    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus = _actionStatus.asStateFlow()

    fun clearActionStatus() { _actionStatus.value = null }

    /** Read the account's script, once, when the participants panel opens. Idempotent while it holds an
     *  ANSWER, so reopening the panel does not go back to the server; a read that failed leaves
     *  [SenderScript.Unreachable], which is not an answer, so the next opening tries again. */
    fun loadSenderRules() {
        if (_senderRules.value is SenderScript.Read) return
        val credentials = credentials() ?: return
        viewModelScope.launch {
            _senderRules.value = readSenderScript(credentials)
        }
    }

    /** One read of the account's script, as the three-state answer the entry is decided on.
     *  `getOrElseUnlessCancelled` and not `getOrDefault` (#99): a cancelled read is not a failed one,
     *  and this coroutine dies every time a page is swiped away. */
    private suspend fun readSenderScript(credentials: AccountCredentials): SenderScript =
        runCatching { SenderScript.Read(repo.loadFilterRules(credentials)) }
            .getOrElseUnlessCancelled { SenderScript.Unreachable }

    /**
     * Add the "future mail to Trash, marked read" rule for [address] to the account's script. The whole
     */
    fun blockSender(address: String) {
        val credentials = credentials() ?: return
        viewModelScope.launch {
            val trashPath = trashFilePath(accountMailboxes.value)
            if (trashPath == null) {
                _senderRuleStatus.value =
                    getApplication<Application>().getString(R.string.status_action_failed)
                return@launch
            }
            val outcome = addBlockRule(
                address = address,
                trashFolder = trashPath,
                load = { repo.loadFilterRules(credentials) },
                save = { rules -> repo.saveFilterRules(credentials, rules) },
            )
            _senderRuleStatus.value = getApplication<Application>().getString(
                when (outcome) {
                    BlockOutcome.ADDED -> R.string.sender_volume_block_added
                    BlockOutcome.ALREADY_PRESENT -> R.string.sender_volume_block_done
                    BlockOutcome.FAILED -> R.string.status_action_failed
                },
            )
            // Re-read, so the entry greys itself out for the address just handled instead of going
            // on offering a rule that is now there.
            _senderRules.value = readSenderScript(credentials)
        }
    }

    /** OpenPGP state of the opened message (status card + header badges). */
    private val _crypto = MutableStateFlow<CryptoUiState>(CryptoUiState.None)
    val crypto = _crypto.asStateFlow()

    /** Whether the bottom Reply/Forward bar should show for this page. Written by the page's body,
     *  read by the pager-level chrome: the visible bar is FIXED outside the pager (#62) and follows
     *  the SETTLED page's value, so it never blinks during the swipe. */
    private val _replyBarVisible = MutableStateFlow(false)
    val replyBarVisible = _replyBarVisible.asStateFlow()

    fun setReplyBarVisible(visible: Boolean) {
        _replyBarVisible.value = visible
    }

    /** One-time "show remote images" override for the open message. Here rather than in a composable
     *  because its writer and reader are split across the pager boundary (#62). Reset on load. */
    private val _manualShowImages = MutableStateFlow(false)
    val manualShowImages = _manualShowImages.asStateFlow()

    fun showImagesOnce() {
        _manualShowImages.value = true
    }

    /** One-shot "print this message" signal, same shape as the image override and for the same
     *  reason: the overflow menu is fixed chrome at the pager level, the page that builds the
     *  document is one level down. Reset on load so a request never crosses to the next message. */
    private val _printRequested = MutableStateFlow(false)
    val printRequested = _printRequested.asStateFlow()

    fun print() {
        _printRequested.value = true
    }

    fun printConsumed() {
        _printRequested.value = false
    }

    /** The raw-headers viewer (#60): null while closed. Headers are pulled on demand so the normal
     *  reader path never fetches them. */
    private val _headers = MutableStateFlow<HeadersState?>(null)
    val headers = _headers.asStateFlow()

    /** Open the raw-headers viewer and fetch this message's headers (cheap over JMAP). */
    fun viewHeaders() {
        val id = loadedId ?: return
        _headers.value = HeadersState.Loading
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                _headers.value = HeadersState.Loaded(repo.rawHeaders(credentials, id))
            } catch (t: Throwable) {
                _headers.value = HeadersState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Close the raw-headers viewer. */
    fun dismissHeaders() {
        _headers.value = null
    }

    /**
     * The routing and identity headers the sender panel shows — Authentication-Results,
     * Return-Path, Date, List-Id. Empty until the panel has been opened once.
     *
     * THE SAME on-demand fetch [viewHeaders] makes, and deliberately not a widening of the body
     * fetch: adding four `header:...:asText` properties to the request every message pays for
     * would cost every reader a bigger response, and give every server a fourth property to reject
     * (which is why the two that ARE on that fetch each need their own refusal fallback). One
     * round-trip, paid when somebody taps the sender, exactly as the per-sender filter rule on the
     * same panel already does.
     *
     * Its own state, NOT [_headers]: that one being non-null is what puts the raw-headers sheet on
     * screen, so filling it here would open a sheet nobody asked for.
     */
    private val _metadataHeaders = MutableStateFlow<List<EmailHeader>>(emptyList())
    val metadataHeaders = _metadataHeaders.asStateFlow()

    /** Fetch the panel's headers once per opened message; a failure leaves the rows simply absent,
     *  which is the same thing the panel does for a message that carries none. */
    fun loadMetadataHeaders() {
        val id = loadedId ?: return
        if (_metadataHeaders.value.isNotEmpty()) return
        viewModelScope.launch {
            val fetched = runCatching {
                val credentials = credentials() ?: return@runCatching emptyList<EmailHeader>()
                repo.rawHeaders(credentials, id)
            }.getOrDefault(emptyList())
            if (loadedId == id) _metadataHeaders.value = fetched
        }
    }

    // ---- labels: mailbox membership and user keywords (RFC 8621 §4.1.1) ----
    //
    // TWO DIFFERENT THINGS, kept apart on purpose — see MessageMetadata.kt, where the reader's own
    // rule for what a tag IS lives. `mailboxIds` is the SET of mailboxes the message belongs to at
    // once (this fleet's categories are mailboxes, so that is what a label is); `keywords` is the
    // per-message flag set, of which everything without a `$` is a user tag.

    /** Every mailbox the OPEN message belongs to, read from the server when the reader settles.
     *  Empty on IMAP, where there is no set to read — the label surface says so rather than
     *  offering an edit that would have to be a copy. */
    private val _mailboxIds = MutableStateFlow<Set<String>>(emptySet())
    val mailboxIds = _mailboxIds.asStateFlow()

    /** Re-read the membership from the server. Called after every add/remove, because the local row
     *  holds ONE mailbox id and cannot represent the answer. #1869 step 9: on load ([local]) it is read
     *  from the local store instead (the memberships every list sync mirrors), so an open makes no call. */
    fun refreshMailboxIds(local: Boolean = false) {
        val id = loadedId ?: return
        viewModelScope.launch {
            val fetched = runCatching {
                if (local) {
                    val account = accountId ?: store.currentId() ?: return@runCatching emptySet<String>()
                    return@runCatching repo.localMailboxMembership(account, id)
                }
                val credentials = credentials() ?: return@runCatching emptySet<String>()
                repo.mailboxMembership(credentials, id)
            }.getOrDefault(emptySet())
            if (loadedId == id) _mailboxIds.value = fetched
        }
    }

    /**
     * Put the open message in [mailboxId] as well, keeping every mailbox it is already in.
     *
     * ADD, not "move": the patch names one membership and says nothing about the others. A failure
     * lands in [actionStatus] like every other gesture on the open message and never destroys the
     * message the user is reading.
     */
    fun addMailbox(mailboxId: String) = act({ refreshMailboxIds() }) { c, id ->
        repo.addToMailbox(c, id, mailboxId)
    }

    /**
     * Take the open message OUT of [mailboxId], keeping every other mailbox it is in.
     *
     * The message is NOT closed afterwards, even when the mailbox removed is the folder it was
     * opened from. It still exists and is still somewhere; closing the reader would tell the user
     * it had been deleted, which is the misconception this whole surface exists to correct.
     */
    fun removeMailbox(mailboxId: String) = act({ refreshMailboxIds() }) { c, id ->
        repo.removeFromMailbox(c, id, mailboxId)
    }

    /** Set or clear one USER keyword — the other kind of tag. `$`-prefixed system keywords are the
     *  star's and the unread state's, and the repository refuses them here. */
    fun setUserKeyword(keyword: String, value: Boolean) = act({ reloadKeywords(keyword, value) }) { c, id ->
        repo.setUserKeyword(c, id, keyword, value)
    }

    /** Reflect a confirmed keyword change on the message already on screen, so the chip row moves
     *  with the tap instead of waiting for the next sync. */
    private fun reloadKeywords(keyword: String, value: Boolean) {
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        _state.value = MessageState.Loaded(
            current.copy(
                keywords = current.keywords.toMutableMap().apply {
                    if (value) put(keyword, true) else remove(keyword)
                },
            ),
        )
    }

    /**
     * "Save as .eml": [MailRepository.rawSource]'s bytes, one per octet, into the document picked at
     */
    fun exportSource(uri: Uri, proposedName: String, ownerId: String) {
        viewModelScope.launch {
            try {
                val id = loadedId
                if (id == null || id != ownerId) {
                    discardDocument(uri)
                    _actionStatus.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.rawSource(credentials, id)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("no output stream")
                }
                val name = displayNameOf(uri, proposedName)
                _actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)
            } catch (t: Throwable) {
                discardDocument(uri)
                // The unavailable message gets the sentence the reader already shows for that case;
                // everything else keeps its own text, like "View headers".
                _actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
                    ?: readerActionFailureText(t) { res -> getApplication<Application>().getString(res) }
            }
        }
    }

    private val exporter = MessageExporter(application, repo)
    /** The running "Export..." job (progress), or null. */
    val exportState = exporter.state

    fun cancelExport() = exporter.cancel()

    /** "Export...": see [MessageExporter]. [uri] null = hand the file to the share sheet. */
    fun exportMessage(format: app.sterna.export.ExportFormat, uri: Uri?, ownerId: String) =
        exporter.start(
            viewModelScope, format, uri, _messages.value.firstOrNull { it.id == ownerId }, credentials(),
        ) { _actionStatus.value = it }

    /**
     * "Save" on an attachment row: the part's bytes, straight from the server into the document she
     */
    fun saveAttachment(uri: Uri, proposedName: String, ownerId: String, partKey: String) {
        viewModelScope.launch {
            try {
                val part = _messages.value.firstOrNull { it.id == ownerId }
                    ?.body?.fileAttachmentParts()
                    ?.firstOrNull { (it.partId ?: it.blobId) == partKey }
                if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {
                    discardDocument(uri)
                    _actionStatus.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, ownerId)
                requireAttachmentBytes(ownerId, partKey, bytes)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("no output stream")
                }
                val name = displayNameOf(uri, proposedName)
                _actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)
            } catch (cancelled: CancellationException) {
                // Cancellation is NOT a failure to report, but the document still has to go: each
                // pager page owns its ViewModel, so swiping two messages away mid-download cancels
                // this scope and would leave the empty file the picker created in her folder, with
                // no toast to hint at it. [discardDocument] runs NonCancellable for this moment.
                discardDocument(uri)
                throw cancelled
            } catch (t: ContentTooLargeException) {
                // Our own refusal, not a failure — the same sentence the open path gives it, which
                // names no action precisely so both paths can wear it.
                discardDocument(uri)
                _actionStatus.value = getApplication<Application>().getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                discardDocument(uri)
                // [readFailureStringRes] FIRST, as exportSource's catch does. The sentence below
                // interpolates `t.message` raw, and an unavailable part names the part key and the
                // message id in English (#159): on a French screen that reads "Impossible
                // d'enregistrer la pièce jointe : Attachment 2.1 of M8fa4… came back empty".
                _actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
                    ?: getApplication<Application>()
                        .getString(R.string.status_save_attachment_failed, t.message ?: "error")
            }
        }
    }

    /** Delete the document the picker created at [uri] — nothing was, or could be, written into it.
     * [NonCancellable], because the one moment this matters most is a cancelled scope: a plain
     *  `withContext` would throw here instead of deleting, leaving the empty file behind for good. */
    private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
    }

    /** The document's name as its provider reports it (she may have renamed it in the picker),
     *  else [fallback]. A provider that answers nothing, or throws, costs the name and nothing more. */
    private suspend fun displayNameOf(uri: Uri, fallback: String): String = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** The attached-message sheet: null while closed, else its load/loaded/error state. Opened by
     *  [openAttachment] when the part is a `message/rfc822`; nothing on that path touches the disk. */
    private val _attachedMessage = MutableStateFlow<AttachedMessageState?>(null)
    val attachedMessage = _attachedMessage.asStateFlow()

    /** The download behind the sheet, so that closing it can stop it: a `Loaded` landing after a
     *  dismiss — or after the page moved on — would reopen the sheet on its own, with the previous
     *  message's attachment on top of the new one. */
    private var attachedMessageJob: Job? = null

    /** Close the attached-message sheet, and stop the download behind it. */
    fun dismissAttachedMessage() {
        attachedMessageJob?.cancel()
        attachedMessageJob = null
        _attachedMessage.value = null
    }

    // ---- unsubscribe (RFC 2369 / RFC 8058) ----

    /** What the open message offers as a way out of its mailing list; null = no banner, no menu
     *  entry, and no error either — a message that offers nothing is simply mail. */
    private val _unsubscribe = MutableStateFlow<UnsubscribeOptions?>(null)
    val unsubscribe = _unsubscribe.asStateFlow()

    private val _unsubscribeState = MutableStateFlow<UnsubscribeState>(UnsubscribeState.Idle)
    val unsubscribeState = _unsubscribeState.asStateFlow()

    /** The gesture awaiting confirmation, or null when no dialog is up. Confirmation is systematic
     *  and has no setting (D7): the dialog is where the app says what is about to leave and to whom. */
    private val _unsubscribeConfirm = MutableStateFlow<PendingUnsubscribe?>(null)
    val unsubscribeConfirm = _unsubscribeConfirm.asStateFlow()

    /** Ask before acting; a no-op when the message offers nothing, or when it is already done. */
    fun askUnsubscribe() {
        val options = _unsubscribe.value ?: return
        val action = offeredUnsubscribeAction(options, _unsubscribeState.value) ?: return
        // The options are CAPTURED here, not read again when the button is pressed: a reload behind
        // the open dialog would otherwise leave what it names and what it sends disagreeing.
        _unsubscribeConfirm.value = PendingUnsubscribe(action, options)
    }

    fun dismissUnsubscribeConfirm() {
        _unsubscribeConfirm.value = null
    }

    /**
     * Carry out the confirmed unsubscribe: the one-click POST, or the mail through the outbox.
     */
    fun unsubscribe() {
        val pending = _unsubscribeConfirm.value ?: return
        if (offeredUnsubscribeAction(pending.options, _unsubscribeState.value) == null) return
        _unsubscribeConfirm.value = null
        val ownerId = loadedId ?: return
        val ownerAccount = accountId
        val app = getApplication<Application>()
        _unsubscribeState.value = UnsubscribeState.Sending
        viewModelScope.launch {
            val outcome: UnsubscribeState = try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                when (pending.action) {
                    UnsubscribeAction.ONE_CLICK -> {
                        val url = pending.options.oneClickUrl ?: error("no one-click url")
                        // The connectivity read is a lambda so it is answered when the POST fails
                        // and not now: it is the app's one connectivity check (#70), and `core:data`
                        // must not reach for Android. Without it a dead endpoint is reported as "No
                        // network" to a phone that is plainly online.
                        when (val result = repo.unsubscribeOneClick(url) { hasUsableNetwork(app) }) {
                            is UnsubscribeResult.Sent -> UnsubscribeState.Sent
                            is UnsubscribeResult.Failed -> UnsubscribeState.Failed(result.reason)
                        }
                    }
                    UnsubscribeAction.MAIL -> {
                        val mailto = pending.options.mailto ?: error("no mailto")
                        repo.sendUnsubscribeMail(credentials, mailto)
                        UnsubscribeState.Queued
                    }
                    // Not ours to run; leaving the state untouched keeps the banner as it was.
                    UnsubscribeAction.OPEN_PAGE -> UnsubscribeState.Idle
                }
            } catch (cancelled: CancellationException) {
                // A cancellation is not a failure to report: the page it belonged to is gone.
                throw cancelled
            } catch (t: Throwable) {
                // Said out loud, because the screen cannot: "Unsubscribe failed" is one sentence for
                // a refused enqueueSend, a missing identity and a broken database alike.
                android.util.Log.w("SternaUnsubscribe", "unsubscribe failed: ${pending.action}", t)
                UnsubscribeState.Failed(UnsubscribeFailure.REFUSED)
            }
            if (loadedId == ownerId && accountId == ownerAccount) {
                _unsubscribeState.value = outcome
            }
        }
    }

    // ---- read receipt (RFC 8098) ----

    /**
     * The question standing on the open message, or null when there is none to ask. RAISED in
     */
    private val _readReceiptOffer = MutableStateFlow<ReadReceiptRequest?>(null)
    val readReceiptOffer = _readReceiptOffer.asStateFlow()

    /** Where a receipt this reader accepted has got to; [ReadReceiptState.Idle] until they say so. */
    private val _readReceiptState = MutableStateFlow<ReadReceiptState>(ReadReceiptState.Idle)
    val readReceiptState = _readReceiptState.asStateFlow()

    /**
     * Whether the reader has had their say on THIS message's question — see [ReadReceiptAnswer].
     */
    private var readReceiptAnswer = ReadReceiptAnswer.PENDING

    init {
        // BOTH directions, through ONE decision replayed: turning the switch off takes a standing
        // question away, turning it on with a message open puts it, and the cold-start race closes
        // in passing. Not an amnesty — `readReceiptAnswer` goes into the decision.
        viewModelScope.launch {
            readReceiptSetting.collect { maybeOfferReadReceipt() }
        }
    }

    /**
     * The reader said yes: queue the receipt through the outbox and report what was queued.
     */
    fun sendReadReceipt() {
        val pending = offeredReadReceipt(
            setting = readReceiptSetting.value,
            marking = ReadMarking.READER_SETTLE_UNREAD,
            request = _readReceiptOffer.value,
            answered = readReceiptAnswer.answered,
        ) ?: return
        readReceiptAnswer = ReadReceiptAnswer.ACCEPTED
        _readReceiptOffer.value = null
        val ownerId = loadedId ?: return
        val ownerAccount = accountId
        val app = getApplication<Application>()
        _readReceiptState.value = ReadReceiptState.Sending
        viewModelScope.launch {
            val outcome: ReadReceiptState = try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                readReceiptSendOutcome(
                    repo.sendReadReceipt(
                        credentials = credentials,
                        receiptTo = pending.receiptTo,
                        originalSubject = pending.originalSubject,
                        originalMessageId = pending.originalMessageId,
                        deliveredTo = pending.deliveredTo,
                    ),
                )
            } catch (cancelled: CancellationException) {
                // The page it belonged to is gone; not a failure to report.
                throw cancelled
            } catch (t: Throwable) {
                // Said out loud for the unsubscribe's reason: the banner has one sentence for a
                // refused enqueue, a missing identity and a broken database alike.
                android.util.Log.w("SternaReadReceipt", "read receipt failed", t)
                ReadReceiptState.Failed
            }
            // The reader is a pager: a swipe while the enqueue is in flight would otherwise land
            // this answer on the neighbouring message (#31).
            if (loadedId == ownerId && accountId == ownerAccount) {
                _readReceiptState.value = outcome
                // A FAILURE IS A RETRY, NOT A DEAD END: nothing was queued, so leaving her ACCEPTED
                // would freeze a red sentence on the message. A REFUSAL is not a failure. Put back
                // through the DECISION rather than by restoring the captured request — the setting may
                // have moved, and there must not be a second way to raise a question.
                if (outcome == ReadReceiptState.Failed) {
                    readReceiptAnswer = ReadReceiptAnswer.PENDING
                    maybeOfferReadReceipt()
                }
            }
        }
    }

    /** The reader said no: the question goes, and nothing leaves. Remembered for THIS PAGE only, so
     *  replaying the decision cannot put the question back; nothing is written anywhere, which is the
     *  trade that avoids a column, a migration and a resync. */
    fun declineReadReceipt() {
        readReceiptAnswer = ReadReceiptAnswer.DECLINED
        _readReceiptOffer.value = null
    }

    /** One automatic decrypt attempt per opened message (when the page settles). */
    private var autoDecryptTried = false

    /** The detected crypto kind, kept so a cancelled unlock returns to Locked. */
    private var lockedKind: CryptoKind? = null


    // ---- in-place translation and summary of the opened message -----------------------------------
    //
    // Both are cached per message and per language in message_text_cache, so reopening a message is a
    // row read. State belongs to the message on this page: load() drops it, and a result that
    // arrives for a message the page has since left is discarded.

    private val textAi by lazy {
        val app = getApplication<Application>()
        ReaderTextAi(app.container.messageTextCache, TextToolsReaderEngine(textToolsClient(app), app), LanguageGuess)
    }

    private val _translation = MutableStateFlow(ReaderTranslation())
    val translation = _translation.asStateFlow()

    private val _summary = MutableStateFlow(ReaderSummary())
    val summary = _summary.asStateFlow()

    private var translationJob: Job? = null
    private var summaryJob: Job? = null

    private fun cacheAccount(): String = accountId ?: ""

    /**
     * The reader has a body on screen: bring back whatever is already kept for it, then do what the
     * owner switched on. [fragment] is what the reader renders (the translation source); [summarySource]
     * is the quote-free text a summary is made from.
     */
    fun onReaderOpened(fragment: String, summarySource: String) {
        val id = loadedId ?: return
        val app = getApplication<Application>()
        val target = MailTextToolsPrefs.translateTarget(app)
        val summaryLang = MailTextToolsPrefs.summaryLanguage(app)
        if (_translation.value.source != fragment) {
            translationJob?.cancel()
            translationJob = viewModelScope.launch {
                val kept = textAi.cachedTranslation(cacheAccount(), id, target, fragment)
                if (loadedId != id) return@launch
                if (kept != null) {
                    _translation.value = ReaderTranslation(target, fragment, kept, shown = true)
                } else {
                    _translation.value = ReaderTranslation(target, fragment)
                    val auto = MailTextToolsPrefs.autoTranslate(app) &&
                        textAi.needsTranslation(InPlaceHtmlTranslation.visibleText(fragment), target)
                    if (auto && loadedId == id) runTranslation(id, fragment, target)
                }
            }
        }
        if (_summary.value.text == null && !_summary.value.running) {
            summaryJob?.cancel()
            summaryJob = viewModelScope.launch {
                val kept = textAi.cachedSummaryParts(cacheAccount(), id, summaryLang, summarySource)
                if (loadedId != id) return@launch
                if (kept != null) _summary.value = ReaderSummary(text = kept.summary, reply = kept.reply)
                else if (MailTextToolsPrefs.autoSummary(app) && summarySource.isNotBlank()) runSummary(id, summarySource, summaryLang)
            }
        }
    }

    /**
     * The Translate tool: translate the reader's fragment in place into the chosen language. A tap
     * never fails on language detection: the language is detected once on the whole message, and when
     * no engine can place it the reader is asked ([ReaderTranslation.needsSource]) and passes
     * [chosenSource] back here.
     */
    fun translateNow(fragment: String, chosenSource: String? = null) {
        val id = loadedId ?: return
        if (_translation.value.running) return
        val target = MailTextToolsPrefs.translateTarget(getApplication<Application>())
        translationJob?.cancel()
        translationJob = viewModelScope.launch { runTranslation(id, fragment, target, chosenSource, interactive = true) }
    }

    private suspend fun runTranslation(
        id: String, fragment: String, target: String, chosenSource: String? = null, interactive: Boolean = false,
    ) {
        _translation.value = ReaderTranslation(target, fragment, running = true)
        val outcome = textAi.translate(cacheAccount(), id, target, fragment, chosenSource)
        if (loadedId != id) return
        _translation.value = when (outcome) {
            is TranslationOutcome.Done -> ReaderTranslation(target, fragment, outcome.fragment, shown = true)
            is TranslationOutcome.Failed -> ReaderTranslation(target, fragment, error = outcome.reason)
            // Auto-translate stays quiet when unsure; only a tap asks the reader which language it is.
            is TranslationOutcome.NeedsSource ->
                if (interactive) ReaderTranslation(target, fragment, needsSource = true) else ReaderTranslation(target, fragment)
        }
    }

    /** Show Original / Show Translated. A no-op until a translation exists. */
    fun toggleTranslated() {
        _translation.value = _translation.value.toggled()
    }

    fun dismissTranslationError() {
        _translation.value = _translation.value.copy(error = null, needsSource = false)
    }

    /** The Resume tool: summarise [source] into the chosen summary language. */
    fun summariseNow(source: String) {
        val id = loadedId ?: return
        if (_summary.value.running) return
        val lang = MailTextToolsPrefs.summaryLanguage(getApplication<Application>())
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch { runSummary(id, source, lang) }
    }

    private suspend fun runSummary(id: String, source: String, lang: String) {
        _summary.value = ReaderSummary(running = true)
        val outcome = textAi.summarise(cacheAccount(), id, lang, source)
        if (loadedId != id) return
        _summary.value = when (outcome) {
            is SummaryOutcome.Done -> ReaderSummary(text = outcome.text, reply = outcome.reply)
            is SummaryOutcome.Failed -> ReaderSummary(error = outcome.reason)
        }
    }

    fun dismissSummaryError() {
        _summary.value = _summary.value.copy(error = null)
    }

    private var loadedId: String? = null
    /** Owning account when opened from the unified inbox; null = current account. */
    private var accountId: String? = null

    /**
     * The (account, id) whose cached body [warmFromCache] has already painted on this page — the
     */
    private var warmedId: String? = null
    private var warmedAccountId: String? = null

    // Mark-as-read is deferred to "settle": the pager composes neighbouring pages while swiping, so
    // reading on load() would mark messages read just by flicking past them.
    private var active = false
    private var anchorMarked = false

    /**
     * How this page's message came to be marked read, once the pager settled on it. Written in
     */
    private var settleMarking: ReadMarking? = null

    /**
     * The subject of the ENVELOPE — the cover — captured before anything is decrypted and never
     */
    private var coverSubject: String? = null

    /** Credentials for the message's own account (unified inbox), else the current one. */
    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /**
     * Paint this page's body from the LOCAL CACHE, before the finger reaches it — and from nothing else.
     */
    fun warmFromCache(emailId: String, accountId: String?) {
        if (loadedId != null) return
        // The page's OWN account, so `credentials()` answers for this message and not for whatever
        // account happens to be current (#92).
        this.accountId = accountId
        viewModelScope.launch {
            // #1869 the account id is all a local read needs: no credentials (keystore decrypt) per warmed page.
            val localAccount = accountId ?: store.currentId() ?: return@launch
            val cached = runCatching { repo.cachedMessage(localAccount, emailId) }.getOrNull() ?: return@launch
            val warmable = MessagePaging.warmable(
                cryptoKind = repo.cryptoKindOf(cached.email),
                inlineParts = cached.email.inlineImageParts().size,
                cachedInlineImages = cached.inlineImages.size,
                // Read and compared exactly as MessageScreen does it, lower-cased against a list
                // SettingsRepository lower-cases on write. What this argument buys is COMFORT, not
                senderAllowedRemoteImages =
                    cached.email.from.firstOrNull()?.email?.lowercase()?.let { it in settings.imageAllowlist.first() } == true,
            )
            if (!warmable) return@launch
            // The settle may have overtaken the cache read: `load` owns the page from that moment
            // on, and a warm body painted over a fetch in flight would be a second render.
            if (loadedId != null) return@launch
            _state.value = MessageState.Loaded(cached.email)
            _messages.value = listOf(
                ThreadMessage(
                    id = cached.email.id,
                    header = cached.email,
                    body = cached.email,
                    inlineImages = cached.inlineImages,
                ),
            )
            warmedId = emailId
            warmedAccountId = accountId
        }
    }

    /** Fetches the inline images of [emailId] after its text has been shown, then re-paints the row with them. */
    private fun loadInlineImagesLater(credentials: AccountCredentials, emailId: String) {
        viewModelScope.launch {
            val images = runCatching { repo.inlineImagesFor(credentials, emailId) }.getOrNull().orEmpty()
            if (images.isEmpty()) return@launch
            _messages.value = _messages.value.map { if (it.id == emailId) it.copy(inlineImages = images) else it }
        }
    }

    /** Loads the conversation once per (id, account) pair, and ONLY for the page the pager has settled
     *  on. Does NOT mark read — [onActiveChanged] does that.
     *  screen (#92). */
    fun load(emailId: String, accountId: String?, settled: Boolean) {
        val failed = _state.value is MessageState.Error
        if (!MessagePaging.needsLoad(settled, loadedId, this.accountId, emailId, accountId, failed)) {
            return
        }
        loadedId = emailId
        this.accountId = accountId
        // Is there already a body on this page, put there by [warmFromCache] for THIS account and
        // THIS id? If so the two lines that would erase it are skipped — erased, the WebView leaves
        // composition at the instant the finger lands. Every other reset in this prologue stays
        // unconditional: they guard against the PREVIOUS message's state.
        val warm = warmedId == emailId && warmedAccountId == accountId
        // FIRST, before the coroutine below: the unsubscribe of the message we are leaving. It is a
        // BUTTON, and until the fetch returns it would still be wired to the previous message's
        // list — a tap unsubscribing from something the reader is no longer looking at.
        _unsubscribe.value = null
        if (_coreDoc.value?.emailId != emailId) _coreDoc.value = null
        _mailboxIds.value = emptySet()
        _metadataHeaders.value = emptyList()
        _unsubscribeState.value = UnsubscribeState.Idle
        _unsubscribeConfirm.value = null
        // The previous message's translation and summary, and the work still making them.
        translationJob?.cancel()
        summaryJob?.cancel()
        _translation.value = ReaderTranslation()
        _summary.value = ReaderSummary()
        // And the read receipt, one degree worse: its button answers a NAMED stranger, captured from
        // the message we are leaving.
        _readReceiptOffer.value = null
        _readReceiptState.value = ReadReceiptState.Idle
        readReceiptAnswer = ReadReceiptAnswer.PENDING
        // Re-point the move picker at the account this page's message belongs to, before any
        // of its state is read (the folders come from the cache, so no network is involved).
        _ownerAccountId.value = credentials()?.id
        _moveAccountId.value = null
        anchorMarked = false
        settleMarking = null
        if (!warm) {
            _state.value = MessageState.Loading
            _messages.value = emptyList()
        }
        _mailboxRole.value = null
        _ownMessage.value = false
        _deliveredTo.value = null
        coverSubject = null
        _snoozedUntil.value = null
        _mailboxId.value = null
        _calendar.value = null
        calendarLoadedFor = null
        _crypto.value = CryptoUiState.None
        autoDecryptTried = false
        _replyBarVisible.value = false
        _manualShowImages.value = false
        _printRequested.value = false
        _headers.value = null
        // The same three lines as [dismissAttachedMessage], written out: ReaderStateResetOnLoadTest
        // reads this prologue for `_attachedMessage.value`, and a helper would hide it.
        attachedMessageJob?.cancel()
        attachedMessageJob = null
        _attachedMessage.value = null
        _attachmentStatus.value = null
        // The per-sender rule, all three halves. The status belongs to the message we are leaving, and
        // the script is worse than stale: the pager crosses ACCOUNTS, so the previous account's rules
        // would decide whether THIS account's sender is already ruled. The addresses are taken here
        // rather than left to a round-trip: the reason a gesture is refused must never arrive late.
        _senderRules.value = SenderScript.Unread
        _senderRuleStatus.value = null
        // Same argument, one gesture over: "could not report as spam" belongs to the message we are
        // leaving, and toasted on the next page it would name the wrong mail.
        _actionStatus.value = null
        _accountAddresses.value = ownAddresses()
        // Identities ONLY, no login — see the flow's own comment. Same line, same moment, so neither
        // can be the previous account's.
        _identityAddresses.value = store.identities(accountId).map { it.email }
        viewModelScope.launch {
            // #1869 ONE local read for the first frame (LocalReaderStore.snapshot, under the NoNetwork tripwire): the body
            // (stored, or this process's decrypted copy), the list row, the core's document when it is ALREADY stored, the
            // snooze. By account id only: no credentials, so no keystore decrypt, before the page is painted.
            val localAccount = accountId ?: store.currentId()
            val snapshot = localAccount?.let { id -> runCatching { repo.readerSnapshot(id, emailId) }.getOrNull() }
            val localBody = (snapshot as? ReaderSnapshot.Hit)?.body
            // The core's document is used only if it was stored before this open; it is never swapped in after paint.
            val storedDoc = snapshot?.coreDoc
            if (storedDoc != null) _coreDoc.value = storedDoc
            // Account-scoped: a snooze belongs to one account's message (issue #31).
            _snoozedUntil.value = snapshot?.snoozedUntil
            // Paint the cached header immediately so the screen shows the tapped message at once;
            // the body fills in when the fetch returns. The cached list row carries the folder it is
            // filed under, reliable on both protocols, while the fetched body's mailboxId can be
            // absent over JMAP — so junk detection keys off this.
            val listEmail = snapshot?.header
            listEmail?.let { cached ->
                // HEADER-ONLY (body = null): this repaint drops the WebView just as surely as the
                // prologue does, so it is skipped when the warm-up already put the real body there,
                // and (#1869) when the snapshot holds the body: the page is then painted ONCE, below.
                if (!warm && localBody == null) {
                    _messages.value = listOf(ThreadMessage(id = cached.id, header = cached))
                    _state.value = MessageState.Loaded(cached)
                }
                val role = repo.mailboxRole(cached.accountId ?: accountId, cached.mailboxId)
                _mailboxRole.value = role
                _ownMessage.value = isOwnMessage(cached, role)
                _deliveredTo.value = receivingAddress(cached, ownAddresses())
                coverSubject = cached.subject ?: coverSubject
                _mailboxId.value = cached.mailboxId
            }
            try {
                // The tapped message: from the snapshot when it holds the body (no network, no credentials before the
                // paint); a miss is the one case that fetches. Inline images follow the text either way. Not marked
                // read here — see [onActiveChanged].
                val opened = localBody
                    ?: repo.openMessage(credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account)), emailId, markRead = false, lazyInlineImages = true)
                // Resolved AFTER the paint inputs are in hand: every use below is a background reconcile.
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // A message the core store lacks is fetched into it in the background, never awaited (#1869).
                if (storedDoc == null) repo.queueCoreFetch(credentials, emailId)
                // Stamp the owning account: the fetched body never carries one, and downstream
                // actions route by it.
                val anchor = opened.email.copy(accountId = opened.email.accountId ?: accountId)
                _state.value = MessageState.Loaded(anchorDisplay(anchor))
                // Show the opened message IMMEDIATELY. The thread fetch below is a separate network
                // round-trip and must not gate the body the user came to read.
                val anchorBody = anchorDisplay(anchor)
                _messages.value = listOf(
                    ThreadMessage(
                        id = anchor.id,
                        header = anchorBody,
                        body = anchorBody,
                        inlineImages = opened.inlineImages,
                    ),
                )
                val anchorRole = repo.mailboxRole(anchor.accountId, anchor.mailboxId ?: listEmail?.mailboxId)
                _mailboxRole.value = anchorRole
                _ownMessage.value = isOwnMessage(anchor, anchorRole)
                // The fetched original carries the full To AND Cc; the cached row above remembers To
                // only, so this is where a message addressed to an alias in Cc gets named. Never
                // erases what the cache already found (#81).
                _deliveredTo.value = receivingAddress(anchor, ownAddresses()) ?: _deliveredTo.value
                // The cover subject, with the same care — but not unconditionally: `openMessage`
                // serves the decrypt cache FIRST, so reopening a message decrypted earlier hands
                // back a body whose subject is the PROTECTED one (#128). `opened.crypto` says which.
                coverSubject = coverSubjectAfterOpen(coverSubject, anchor, opened.crypto)
                _mailboxId.value = anchor.mailboxId ?: listEmail?.mailboxId
                // Read off the OPENED message: the two headers only ever ride with the body fetch,
                // never with the cached list row painted a moment ago.
                // The HEADERS decide first and are never overridden: RFC 2369 / RFC 8058 are a
                // machine-readable promise from the sender, and a guess must not outrank one.
                // Only when they gave nothing does the body scan run — see UnsubscribeBodyScan for
                // what that guess is worth and why its result is only ever "open a page".
                _unsubscribe.value = UnsubscribeHeader.parse(anchor.listUnsubscribe, anchor.listUnsubscribePost)
                    ?: scanBodyForUnsubscribe(anchor)
                // The message's own mailbox set, which the cached row cannot hold: read locally (#1869 step 9).
                refreshMailboxIds(local = true)
                // OpenPGP: reflect the crypto state; a decrypt is attempted once the page settles in
                // front of the user, not while the pager pre-composes neighbours.
                when (val c = opened.crypto) {
                    is MessageCrypto.Locked -> {
                        lockedKind = c.kind
                        _crypto.value = CryptoUiState.Locked(c.kind)
                        maybeAutoDecrypt()
                    }
                    is MessageCrypto.Decrypted -> _crypto.value = CryptoUiState.Decrypted(c)
                    null -> {}
                }
                // An inline image we refused to download leaves a hole in the body. Say it — showing
                // less without a word is how a problem gets hidden.
                if (anchor.oversizedInlineImageCount() > 0) {
                    _attachmentStatus.value =
                        getApplication<Application>().getString(R.string.status_inline_images_too_large)
                }
                // A calendar invite (text/calendar part) is fetched + parsed off the body so the
                // reader can show an event card above it.
                loadCalendarFor(_messages.value.first())
                // The text is on screen: the inline images follow (#1763), and the messages either side of this one
                // are fetched in the background so a swipe opens them from disk.
                if (opened.crypto == null && opened.inlineImages.isEmpty() && anchor.inlineImageParts().isNotEmpty()) {
                    loadInlineImagesLater(credentials, anchor.id)
                }
                repo.prefetchNeighbours(credentials, anchor.id)
                // If the page already settled before the body arrived, read it now.
                maybeMarkRead()
                // …and put the receipt question now that the body — the only fetch carrying
                // `Disposition-Notification-To` — is in hand. A page that settled while the cached
                // row was on screen decided on a row that could not name a single address.
                maybeOfferReadReceipt()
                // The reader shows the single opened message; the conversation (the rest of the
                // thread) now lives only in the list's inline unfold, so no thread merge here.
            } catch (t: Throwable) {
                // The unsubscribe again, for a different reason than in the prologue: the read may
                // have failed AFTER the options were set, or with a dialog open on them. A banner
                // that outlives its message never goes away.
                _unsubscribe.value = null
                _unsubscribeState.value = UnsubscribeState.Idle
                _unsubscribeConfirm.value = null
                // The receipt question likewise: there is no message under it any more, and a
                // banner that outlives its message is one that never goes away.
                _readReceiptOffer.value = null
                _readReceiptState.value = ReadReceiptState.Idle
                // The screen gets a sentence naming no cause, and for MessageUnavailableException it
                // REPLACES the technical text (#159) — so unless the throwable is logged here, the
                // reason this message would not open exists nowhere.
                android.util.Log.w("SternaMessage", "message load failed: $emailId", t)
                // #1837 offline (or the server refused): the core holds the message. Show it from the store rather than an error.
                val offlineDoc = _coreDoc.value ?: storedDoc
                if (offlineDoc != null && listEmail != null && t !is CancellationException) {
                    val shown = anchorDisplay(
                        listEmail.copy(
                            accountId = listEmail.accountId ?: accountId,
                            textBody = listOf(EmailBodyPart(partId = "core", type = "text/plain")),
                            bodyValues = mapOf("core" to EmailBodyValue(value = offlineDoc.text)),
                        ),
                    )
                    _coreDoc.value = offlineDoc
                    _state.value = MessageState.Loaded(shown)
                    _messages.value = listOf(ThreadMessage(id = shown.id, header = shown, body = shown))
                    app.sterna.core.data.mail.RustMailEngine.log("reader $emailId: opened from the store after the old path failed (${t.javaClass.simpleName})")
                    return@launch
                }
                _state.value = MessageState.Error(
                    readFailureText(t),
                    signIn = app.sterna.core.data.mail.AuthRetry.isAuthFailure(t),
                )
            }
        }
    }

    /**
     * The last resort for a message that offers no `List-Unsubscribe` header: the link its own body
     * buries in the footer. Null far more often than not, which is the point — an icon that appears
     * on every message and fails on most is worse than one that appears on half and always works.
     *
     * The words are a localised `string-array` resource, not a list in Kotlin: which words mean
     * "unsubscribe" is a per-language fact, and the translators already own that file.
     */
    private suspend fun scanBodyForUnsubscribe(email: Email): UnsubscribeOptions? {
        val words = getApplication<Application>().resources
            .getStringArray(R.array.unsubscribe_body_words).toList()
        // #1869 off the main thread: a regex walk over a whole newsletter, after the page is painted.
        return withContext(Dispatchers.Default) { UnsubscribeBodyScan.scan(email.htmlContent(), email.textContent(), words) }
    }

    /**
     * What to show when a message will not open: the sentence [readFailureStringRes] picks, else the
     * exception's own text. The throwable itself is logged by the caller's catch.
     */
    private fun readFailureText(t: Throwable): String =
        readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
            ?: (t.message ?: t.javaClass.simpleName)

    /** The anchor as displayed: shown read once it has been settled on (else its true state). */
    private fun anchorDisplay(anchor: Email): Email = if (anchorMarked) anchor.markRead() else anchor

    /** Every address that IS the user on the message's account: its send-as identities plus the
     *  login it authenticates with. A delegated sub-account needs no special case (#31). */
    private fun ownAddresses(): List<String> =
        accountAddresses(store.identities(accountId), credentials()?.username)

    /** Whether the reader shows this message by its recipients instead of its sender — the SAME decision
     *  the list row makes ([showsRecipients]), so the header cannot contradict the line the user just
     *  tapped (#115). `unified = false`: the reader resolves the real folder itself. */
    private fun isOwnMessage(email: Email, mailboxRole: String?): Boolean = showsRecipients(
        role = mailboxRole,
        unified = false,
        selfAuthored = isSelfAuthored(email.from, store.identities(accountId)),
    )

    /**
     * Called by the pager as this page becomes (or stops being) the settled, front-most one. Reading
     * is tied to settling, not to composition, so a message swiped past is not read.
     */
    fun onActiveChanged(isActive: Boolean) {
        active = isActive
        if (isActive) {
            maybeMarkRead()
            maybeAutoDecrypt()
        }
    }

    /** Kick off one silent decrypt attempt when the page is settled and the message is crypto-locked.
     *  With OpenKeychain's passphrase cached this resolves without any UI; otherwise the state becomes
     *  [CryptoUiState.NeedsInteraction]. */
    private fun maybeAutoDecrypt() {
        if (!active || autoDecryptTried) return
        if (_crypto.value !is CryptoUiState.Locked) return
        autoDecryptTried = true
        decrypt()
    }

    /**
     * Decrypt/verify the opened message. [interactionResult] is the data Intent from the provider's
     * PendingIntent round-trip when retrying after [CryptoUiState.NeedsInteraction].
     */
    fun decrypt(interactionResult: Intent? = null) {
        val emailId = loadedId ?: return
        (_crypto.value as? CryptoUiState.Locked)?.let { _crypto.value = it.copy(decrypting = true) }
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            // Fail into the status card, never crash: `decryptMessage` returns `PgpResult.Error` for
            // the failures it anticipates, but an exception ESCAPING it (provider IPC death, parcel
            // mismatch, OOM on a huge raw source) used to take the app down — #14. Mirror load().
            val result = try {
                repo.decryptMessage(credentials, emailId, interactionResult)
            } catch (t: Throwable) {
                PgpResult.Error(t.message ?: t.javaClass.simpleName)
            }
            when (result) {
                is PgpResult.Success -> {
                    val opened = result.value
                    val display = anchorDisplay(opened.email)
                    _messages.value = listOf(
                        ThreadMessage(
                            id = opened.email.id,
                            header = display,
                            body = display,
                            inlineImages = opened.inlineImages,
                        ),
                    )
                    _state.value = MessageState.Loaded(display)
                    _crypto.value = when (val c = opened.crypto) {
                        is MessageCrypto.Decrypted -> CryptoUiState.Decrypted(c)
                        else -> CryptoUiState.None
                    }
                }
                is PgpResult.UserInteractionRequired ->
                    _crypto.value = CryptoUiState.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _crypto.value = CryptoUiState.Failed(result.message)
                PgpResult.NotAvailable -> _crypto.value = CryptoUiState.Failed(null)
            }
        }
    }

    /** The user dismissed the provider's dialog: back to an unlockable state. */
    fun cancelDecrypt() {
        _crypto.value = CryptoUiState.Locked(lockedKind ?: CryptoKind.PGP_ENCRYPTED)
    }

    /**
     * Mark the anchor read (once) when this page is both settled and loaded. The one place
     */
    private fun maybeMarkRead() {
        if (!active || anchorMarked) return
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        anchorMarked = true
        // Recorded BEFORE the early return below, because "already read" is one of the answers the
        // decision owes: reopening a message that was read must NOT ask again, which is why this
        // work stores no "already offered / already refused" anywhere.
        settleMarking = if (current.isSeen) {
            ReadMarking.READER_SETTLE_ALREADY_READ
        } else {
            ReadMarking.READER_SETTLE_UNREAD
        }
        maybeOfferReadReceipt()
        if (current.isSeen) return
        // Reflect the read state immediately so the unread dot clears on settle…
        updateMessage(current.id) { it.copy(header = it.header.markRead(), body = it.body?.markRead()) }
        _state.value = MessageState.Loaded(current.markRead())
        // …then persist it (server + cache).
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setRead(credentials, current.id, true) }
            // Clear the mail's notification now that it's been read (Codeberg #19).
            Notifications.dismiss(getApplication(), credentials.id, credentials.username, listOf(current.id))
        }
    }

    /**
     * Put — or take away — the read-receipt question, by RUNNING the decision against the state as it
     */
    private fun maybeOfferReadReceipt() {
        val marking = settleMarking ?: return
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        _readReceiptOffer.value = offeredReadReceipt(
            setting = readReceiptSetting.value,
            marking = marking,
            request = readReceiptRequest(current, _deliveredTo.value, coverSubject),
            answered = readReceiptAnswer.answered,
        )
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart, ownerId: String) {
        if (openingAttachment) return
        openingAttachment = true
        // A message/rfc822 part has no viewer app to go to: it is read here, in a sheet, and never
        // written to the cache. [openingAttachment] is held and released by that path too.
        if (isAttachedMessage(part.type)) { openAttachedMessage(part, ownerId); return }
        val emailId = ownerId
        val app = getApplication<Application>()
        _attachmentStatus.value = "Opening ${part.name ?: "attachment"}…"
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // The download, the safe name, the FileProvider grant and the MIME decision are all
                // [AttachmentOpen]'s, shared with the message LIST, which now offers the same files a
                // screen earlier. Two copies of this would be two chances to stop granting read
                // permission, or for one of them to keep trusting the sender's Content-Type.
                // unguarded: the startActivity inside it is not wrapped in leaveOnce because
                // the tap was handled above, where [openingAttachment] holds the second one
                // back -- across the download too, which leaveOnce could not cover.
                AttachmentOpen.openExternally(app, repo, storage, credentials, part, emailId)
                _attachmentStatus.value = null
            } catch (t: ContentTooLargeException) {
                // Our own refusal, not a failure: say it plainly instead of showing byte counts.
                _attachmentStatus.value = app.getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                _attachmentStatus.value =
                    app.getString(R.string.status_open_attachment_failed, t.message ?: "error")
            } finally {
                // Released as soon as the chooser is up, not when the user comes back: the file is
                // theirs to open again as often as they like.
                openingAttachment = false
            }
        }
    }

    /**
     * Task #461 — scan the CONTENT of an image attachment (barcode + OCR) through
     * the ONE shared image-scan engine ([ImageScanEngine] in libs:ml-l-image),
     * the same module cloud-drive, cloud-media-center and cloud-camera run — never
     * a copy of the decode path (#170/#261). The part's bytes go through the SAME
     * download and cache path as [openAttachment] (the sender's name reaches the
     * cache only through [StorageRepository.cacheAttachment]'s SafeFileName) and
     * the engine runs off the main thread. Reports through [_attachmentStatus],
     * the status line the attachment section already renders; every outcome is
     * said, including "no barcode and no text" — a silent empty box is the defect
     * this fleet bans.
     */
    fun scanAttachment(part: EmailBodyPart, ownerId: String) {
        if (scanningAttachment) return
        scanningAttachment = true
        val app = getApplication<Application>()
        _attachmentStatus.value = app.getString(R.string.scan_contents_in_progress)
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(app.getString(R.string.status_no_saved_account))
                // The download, the SafeFileName cache write and the part-key
                // guard are AttachmentOpen's: the ONE cache path. This function
                // only RUNS the engine over the file it returns — the VM never
                // writes an attachment itself (SaveAttachmentCallSiteTest pins
                // exactly that).
                val file = AttachmentOpen.cacheForScan(
                    app, repo, storage, credentials, part, ownerId,
                )
                val result = withContext(Dispatchers.IO) {
                    ContentScan(scanEngine.decodeBarcode(file), scanEngine.recognizeText(file))
                }
                _attachmentStatus.value = result.statusMessage(app)
            } catch (cancelled: CancellationException) {
                // The reader page was swiped away mid-download: not an error to show.
                throw cancelled
            } catch (t: ContentTooLargeException) {
                // Our own refusal, named with the same sentence the open path uses.
                _attachmentStatus.value = app.getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                _attachmentStatus.value = app.getString(R.string.scan_contents_failed)
            } finally {
                scanningAttachment = false
            }
        }
    }

    /**
     * What one scanned attachment produced, and how it is said. The barcode and
     * OCR halves are both always asked (decodeBarcode returns null for "no
     * barcode" AND for "could not load"; the OCR error string is the tiebreaker
     * — but a showing of "no content" is enough for the UI, whose every state
     * names itself).
     */
    private data class ContentScan(
        val barcode: BarcodeScan?,
        val ocr: OcrResult,
    ) {
        val hasAnyContent: Boolean get() = barcode != null || ocr.text.isNotBlank()

        fun statusMessage(app: Application): String {
            if (!hasAnyContent) return app.getString(R.string.scan_contents_nothing_found)
            return buildString {
                barcode?.let {
                    append(app.getString(R.string.scan_contents_barcode_label))
                    append(it.format)
                    append(" ")
                    append(it.rawValue)
                    append("\n")
                }
                ocr.text.trim().takeIf { it.isNotEmpty() }?.let {
                    append(app.getString(R.string.scan_contents_ocr_label))
                    append(it)
                }
            }.trim()
        }
    }

    /** Download a `message/rfc822` part and show it in the attached-message sheet. Same download path as
     *  any attachment (50 MiB ceiling, `pgp:` guard, BODY.PEEK), but nothing is cached, no URI is
     *  exposed and no app is started. Releases [openingAttachment]. */
    private fun openAttachedMessage(part: EmailBodyPart, ownerId: String) {
        val app = getApplication<Application>()
        _attachedMessage.value = AttachedMessageState.Loading
        attachedMessageJob = viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, ownerId)
                // Parsing a stranger's message is untrusted work on a large input: off the main
                // thread, like the invitation.
                val message = withContext(Dispatchers.Default) { attachedMessageOf(bytes) }
                _attachedMessage.value = AttachedMessageState.Loaded(message)
            } catch (cancelled: CancellationException) {
                // The sheet was closed under us: not an error to show, and nothing to reopen.
                throw cancelled
            } catch (t: ContentTooLargeException) {
                _attachedMessage.value = AttachedMessageState.Error(app.getString(R.string.status_attachment_too_large))
            } catch (t: Throwable) {
                _attachedMessage.value = AttachedMessageState.Error(t.message ?: t.javaClass.simpleName)
            } finally {
                openingAttachment = false
            }
        }
    }

    /** Download and parse the message's calendar invite into [calendar], through the attachment download
     *  path and the part charset. A miss flips [CalendarInvite.failed] so the card can still offer to
     *  open the raw invitation. */
    private fun loadCalendarFor(msg: ThreadMessage) {
        val part = msg.body?.calendarParts()?.firstOrNull() ?: return
        if (calendarLoadedFor == msg.id) return
        calendarLoadedFor = msg.id
        _calendar.value = CalendarInvite(loading = true, part = part, ownerId = msg.id)
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // Fetched because the message is open, not because anyone asked: bounded like the
                // invitation the parser will accept, not like a tapped attachment.
                val bytes = repo.downloadAttachment(
                    credentials, part, msg.id, DownloadLimits.CALENDAR_MAX_BYTES,
                )
                val charset = runCatching {
                    java.nio.charset.Charset.forName(part.charset ?: "UTF-8")
                }.getOrDefault(Charsets.UTF_8)
                // Parsing a stranger's .ics is untrusted work on an unbounded input: keep it off the
                // main thread so a pathological invitation can never freeze the reader.
                val event = withContext(Dispatchers.Default) {
                    ICalendar.parse(String(bytes, charset))
                }
                _calendar.value = CalendarInvite(
                    loading = false,
                    event = event,
                    failed = event == null,
                    part = part,
                    ownerId = msg.id,
                )
            } catch (t: Throwable) {
                _calendar.value = CalendarInvite(loading = false, failed = true, part = part, ownerId = msg.id)
            }
        }
    }

    /** RSVP to the open invite: build an iTIP REPLY .ics for [partstat] and mail it to the organiser
     *  through the normal send path, reflected on the card for this session only. The reply's timestamp
     *  is taken here and passed into the pure builder so it stays testable. */
    fun respondToInvite(partstat: String) {
        val invite = _calendar.value ?: return
        val event = invite.event ?: return
        val organizer = event.organizerEmail?.takeIf { it.isNotBlank() } ?: return
        if (invite.response is InviteResponse.Sending) return
        val nowMillis = System.currentTimeMillis()
        _calendar.value = invite.copy(response = InviteResponse.Sending)
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                // The replying attendee is this account; reuse its CN from the invite if it lists us.
                val accountEmail = credentials.username
                val cn = event.attendees.firstOrNull { it.email.equals(accountEmail, true) }?.cn
                val ics = ICalendar.buildReply(event, accountEmail, cn, partstat, nowMillis)
                val summary = event.title ?: app.getString(R.string.calendar_event_untitled)
                val subject = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_subject_accepted, summary)
                    "DECLINED" -> app.getString(R.string.calendar_reply_subject_declined, summary)
                    else -> app.getString(R.string.calendar_reply_subject_tentative, summary)
                }
                val who = cn?.takeIf { it.isNotBlank() } ?: accountEmail
                val body = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_body_accepted, who)
                    "DECLINED" -> app.getString(R.string.calendar_reply_body_declined, who)
                    else -> app.getString(R.string.calendar_reply_body_tentative, who)
                }
                repo.sendCalendarReply(credentials, organizer, subject, body, ics.toByteArray(Charsets.UTF_8))
                // The message may have changed underneath us while sending — guard by owner.
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Sent(partstat))
                }
            } catch (t: Throwable) {
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Failed)
                }
            }
        }
    }

    fun toggleFlag() {
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        val flagged = !current.isFlagged
        // Optimistic local update.
        _state.value = MessageState.Loaded(
            current.copy(
                keywords = current.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            ),
        )
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setFlagged(credentials, current.id, flagged) }
        }
    }

    fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.setRead(c, id, false) }
    // Archive + delete route through the shared inbox VM so the reader reuses the same count nudge
    // and Undo as swipe/bulk (#23); no local copies.
    fun reportSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.reportSpam(c, id) }
    fun notSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.notSpam(c, id) }

    /** Snooze the open message until [until]: hide it now, re-surface (and notify) at that time. */
    fun snooze(until: Long, onDone: () -> Unit) {
        val email = (_state.value as? MessageState.Loaded)?.email ?: return
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching {
                repo.snooze(email.id, credentials.id, until)
                Snoozes.enqueue(getApplication(), email.id, credentials.id, until)
            }.onSuccess { _snoozedUntil.value = until }
            onDone()
        }
    }

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                // NEVER `MessageState.Error` HERE, whatever went wrong: what act() drives are gestures
                // ON the open message, and none is a reason to destroy what the user is reading. This
                // used to put "Could not load message: <exception text>" over a message that had loaded
                // perfectly well — the echoed IMAP command, or jargon about a "destroy" (#99).
                _actionStatus.value = readerActionFailureText(t) { res -> getApplication<Application>().getString(res) }
            }
        }
    }
}

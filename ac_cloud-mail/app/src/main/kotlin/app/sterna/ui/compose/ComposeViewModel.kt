package app.sterna.ui.compose

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.contacts.AndroidContacts
import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.mergeSuggestions
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.StoredSignature
import app.sterna.core.data.account.pgpPublicKeyBackfill
import app.sterna.core.data.account.pgpPublicKeyCacheValue
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.DraftOpenRoute
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.LocalDraftEdit
import app.sterna.core.data.mail.SendDraftTarget
import app.sterna.core.data.mail.abandonPlan
import app.sterna.core.data.mail.carriedDraftAccountId
import app.sterna.core.data.mail.commitThenConsumeLocalDraft
import app.sterna.core.data.mail.draftOpenRoute
import app.sterna.core.data.mail.draftSaveConsumesTheQueuedRow
import app.sterna.core.data.mail.draftSaveNeedsNotice
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import app.sterna.core.data.mail.localDraftLeaseAfterSave
import app.sterna.core.data.mail.localDraftPrefill
import app.sterna.core.data.mail.reopenedDraftIsLossy
import app.sterna.core.data.mail.scheduledSendDelayMillis
import app.sterna.core.data.mail.sendDraftTarget
import app.sterna.core.data.mail.takeLocalDraftEditOrGiveItBack
import app.sterna.core.data.mail.unlessBodyIsLossy
import app.sterna.core.data.mail.unlessDraftBelongsElsewhere
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.text.BLANK_OPENING_HTML
import app.sterna.core.data.text.BLOCK_FORWARD
import app.sterna.core.data.text.BLOCK_QUOTE
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.draftHtmlIsLossy
import app.sterna.core.data.text.cidsReferencedBy
import app.sterna.core.data.text.composeOpeningHtml
import app.sterna.core.data.text.composeSignatureHtml
import app.sterna.core.data.text.signatureHtmlCandidates
import app.sterna.core.data.text.draftHtmlToSave
import app.sterna.core.data.text.editorBlock
import app.sterna.core.data.text.htmlBodyFrom
import app.sterna.core.data.text.htmlForWire
import app.sterna.core.data.text.signatureEditorBlock
import app.sterna.core.data.text.withoutUnusedInlineParts
import app.sterna.core.data.text.safeFileName
import app.sterna.core.data.text.toHtml
import app.sterna.core.data.text.toPlainText
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.htmlToPlainText
import app.sterna.core.imap.PgpMime
import app.sterna.mail.MessageDestroyWorker
import app.sterna.send.Outbox
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.send.composeDraftOf
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.net.hasUsableNetwork
import app.sterna.net.isOfflineFailure
import app.sterna.util.MailDates
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Done : ComposeState

    /** [whileSaving] distinguishes a failed draft save from a failed send, for the banner text. */
    data class Error(val message: String, val whileSaving: Boolean = false) : ComposeState

    /** The OpenPGP provider needs the user (passphrase/key pick) to finish the send:
     *  launch [pendingIntent] and hand the result to ComposeViewModel.retryPgpSend. */
    data class PgpInteraction(val pendingIntent: PendingIntent) : ComposeState
}

/** Initial field values, e.g. for a reply, forward, or a restored (undone-send) draft. */
data class DraftFields(
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    /**
     * The body as HTML - THE model the composer edits. [body] is its plain projection (what the
     * dirty checks and the "forgot the attachment" scan read). Null only for a prefill that has no
     * body at all (headers only): the editor then keeps its empty line.
     */
    val bodyHtml: String? = null,
    /** The inline styling [body] carries (#131), beside it and not inside it. A route that reopens a
     *  styled body and drops this is not showing "no styling yet": it is showing the styling REMOVED,
     *  and the next save writes that removal. */
    val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
    /**
     * The list blocks [body] carries (#131), beside [bodyRanges] for the same reason. A route that
     * reopens a body holding a list and drops this is showing the list REMOVED.
     */
    val bodyBlocks: List<Block> = emptyList(),
    /** The links [body] carries (#131), the sharpest of the three: a draft holding a link is judged
     *  REPRODUCIBLE (`draftHtmlIsLossy`), so the next save DESTROYS the server original — a route that
     *  drops the links replaces the only copy that still had them. */
    val bodyLinks: List<Link> = emptyList(),
    /** Reveal the Cc/Bcc row (used when restoring a draft that had them). */
    val expand: Boolean = false,
    /**
     * Show EVERY recipient chip rather than the usual "two and +N" summary.
     *
     * Set for a reply-all, and it is a safety property rather than a preference. Reply-all is the
     * default reply action on the reader now, which is precisely the configuration in which a
     * private answer reaches an entire list — and a To field that has folded eight recipients into
     * "+6" is how nobody notices. Every address the message will go to is on screen, above the
     * cursor, before a word is typed.
     */
    val showAllRecipients: Boolean = false,
    /**
     * Tick the "ask for a read receipt" box (RFC 8098) — set when reopening a message that already
     */
    val requestReceipt: Boolean = false,
    /**
     * This body ALREADY carries the quoted original: true only for the reply prefill built on the path
     */
    val quoted: Boolean = false,
)

/** A "From" choice: one identity belonging to a specific account. */
data class FromOption(val accountId: String, val identity: StoredIdentity)

/**
 * What a "From" switch asks of the body's signature (D5): the identity being left had one, so its
 */
sealed interface SignatureChange {
    /** Whether the block is rewritten with the standard "-- " delimiter line (#90). Carried here
     *  because the rewrite happens in the screen, while the setting lives in DataStore. */
    val delimiter: Boolean

    /** The new signature as an editor block (see [signatureEditorBlock]); empty when there is none. */
    val blockHtml: String

    data class Swap(
        val from: String,
        val to: String,
        override val delimiter: Boolean,
        override val blockHtml: String = "",
    ) : SignatureChange
    data class Insert(
        val signature: String,
        val belowQuote: Boolean,
        override val delimiter: Boolean,
        override val blockHtml: String = "",
    ) : SignatureChange
}

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore

    /**
     * Plain-text compose (#1800): OFF on every compose. The editor is WYSIWYG by default; the owner can
     * switch one message to plain text, which hides the formatting tools, inserts the signature as
     * text and sends a formatting-free html part beside the text/plain one (still
     * multipart/alternative). Switching back restores rich editing and the HTML signature.
     */
    private val _plainTextMode = MutableStateFlow(false)
    val plainTextMode: StateFlow<Boolean> = _plainTextMode.asStateFlow()
    private val repo = application.container.mailRepository
    private val outbox = application.container.sendOutbox
    private val pgp = application.container.pgpEngine
    private val settings = application.container.settingsRepository

    /** Outlives this screen: putting a queued message back happens as the composer is popped (#70). */
    private val appScope = application.container.appScope

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    /**
     * The id of the queued row this composer was opened on (#70), if any. The row stays in the outbox
     */
    private var editingOutboxId: Long? = null

    /**
     * Whether a queued row is really parked behind this composer — the observable face of
     */
    private val _editingOutbox = MutableStateFlow(holdsQueuedOutboxRow(true, outbox.restored.value))
    val editingOutbox: StateFlow<Boolean> = _editingOutbox.asStateFlow()

    private val _onlyCopy = MutableStateFlow(false)
    /**
     * True when the message on screen exists nowhere else and nothing will put it back: a send the
     */
    val onlyCopy: StateFlow<Boolean> = _onlyCopy.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    /**
     * The quoted original for a reply, delivered separately from [prefill]: the headers come instantly
     */
    private val _replyQuote = MutableStateFlow<String?>(null)
    val replyQuote: StateFlow<String?> = _replyQuote.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentsTouched = MutableStateFlow(false)
    /**
     * Whether the user added or removed an attachment (#70/#94). The unsaved-changes guard needs "did
     */
    val attachmentsTouched: StateFlow<Boolean> = _attachmentsTouched.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /**
     * One-shot string resources to surface after an action that closes the screen, where an inline
     * banner would never be read. The screen shows them as a toast, which outlives the navigation.
     */
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    /**
     * The draft this phone was keeping has been deleted, and the screen may go (#95 × #127).
     */
    private val _localDraftDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localDraftDeleted: SharedFlow<Unit> = _localDraftDeleted.asSharedFlow()

    /**
     * Whether deleting the draft this phone is keeping would LEAVE the server copy behind it — the one
     */
    private val _deleteKeepsServerCopy = MutableStateFlow(false)
    val deleteKeepsServerCopy: StateFlow<Boolean> = _deleteKeepsServerCopy.asStateFlow()

    /**
     * Whether the trash icon's confirmation is up (#127).
     */
    private val _pendingDraftDelete = MutableStateFlow(false)
    val pendingDraftDelete: StateFlow<Boolean> = _pendingDraftDelete.asStateFlow()

    /** Raise the delete confirmation. Only ever asks — the deletion itself is a separate tap. */
    fun askDraftDelete() {
        _pendingDraftDelete.value = true
    }

    /** Close it, whichever way out was taken (Cancel, a tap outside, or the confirmed delete). */
    fun clearDraftDelete() {
        _pendingDraftDelete.value = false
    }

    /**
     * Whether the leave dialog is up — "You haven't saved your changes" (#35, #127). HERE for
     */
    private val _pendingDiscard = MutableStateFlow(false)
    val pendingDiscard: StateFlow<Boolean> = _pendingDiscard.asStateFlow()

    /** Raise the leave dialog. Only ever asks — leaving is a separate tap, on one of its buttons. */
    fun askDiscard() {
        _pendingDiscard.value = true
    }

    /** Close it, whichever way out was taken (Cancel, a tap outside, Discard, or Save draft). */
    fun clearDiscard() {
        _pendingDiscard.value = false
    }

    /**
     * Whether the pre-send "did you forget an attachment?" question is up. HERE for
     */
    private val _pendingForgotAttachment = MutableStateFlow(false)
    val pendingForgotAttachment: StateFlow<Boolean> = _pendingForgotAttachment.asStateFlow()

    /** Raise the forgotten-attachment question. Only ever asks — the send is the next tap. */
    fun askForgotAttachment() {
        _pendingForgotAttachment.value = true
    }

    /** Close it (Back to the message, a tap outside, or on the way to the next guard). */
    fun clearForgotAttachment() {
        _pendingForgotAttachment.value = false
    }

    /**
     * Whether the pre-send "this goes to many recipients" question is up — the second link of the
     */
    private val _pendingManyRecipients = MutableStateFlow(false)
    val pendingManyRecipients: StateFlow<Boolean> = _pendingManyRecipients.asStateFlow()

    /** Raise the many-recipients question. Only ever asks — the send is the next tap. */
    fun askManyRecipients() {
        _pendingManyRecipients.value = true
    }

    /** Close it (Back to the message, a tap outside, or just before the send goes out). */
    fun clearManyRecipients() {
        _pendingManyRecipients.value = false
    }

    /**
     * Whether the "pick date and time" CALENDAR is up (#161). HERE for [pendingDraftDelete]'s
     * reason. The two halves are CHAINED, never up together, and must not be merged.
     */
    private val _pendingScheduleDay = MutableStateFlow(false)
    val pendingScheduleDay: StateFlow<Boolean> = _pendingScheduleDay.asStateFlow()

    /** Raise the calendar. Only ever asks — nothing is scheduled until the clock's OK is tapped. */
    fun askScheduleDay() {
        _pendingScheduleDay.value = true
    }

    /** Close it (Cancel, a tap outside, or on the way to the clock). */
    fun clearScheduleDay() {
        _pendingScheduleDay.value = false
    }

    /**
     * Whether the "pick date and time" CLOCK is up — the second half, raised once a day is chosen
     * (#161). HERE for [pendingDraftDelete]'s reason; see [pendingScheduleDay] for the chaining.
     */
    private val _pendingScheduleTime = MutableStateFlow(false)
    val pendingScheduleTime: StateFlow<Boolean> = _pendingScheduleTime.asStateFlow()

    /** Raise the clock. The send is the next tap, and only if the instant is still ahead. */
    fun askScheduleTime() {
        _pendingScheduleTime.value = true
    }

    /** Close it (Cancel, a tap outside, or the confirmed schedule). */
    fun clearScheduleTime() {
        _pendingScheduleTime.value = false
    }

    /**
     * The day already chosen in the calendar, as the picker gives it: UTC midnight, never local —
     */
    private val _scheduleDay = MutableStateFlow<Long?>(null)
    val scheduleDay: StateFlow<Long?> = _scheduleDay.asStateFlow()

    /** Keep the day the calendar handed back, on its way to the clock. */
    fun chooseScheduleDay(dayUtcMidnightMillis: Long) {
        _scheduleDay.value = dayUtcMidnightMillis
    }

    /** Drop it once the clock is closed, so the next opening starts from nothing. */
    fun forgetScheduleDay() {
        _scheduleDay.value = null
    }

    /** Every identity across all accounts, and the chosen one (which sets the sending account). */
    private val _fromOptions = MutableStateFlow<List<FromOption>>(emptyList())
    val fromOptions: StateFlow<List<FromOption>> = _fromOptions.asStateFlow()
    private val _selectedFrom = MutableStateFlow<FromOption?>(null)
    val selectedFrom: StateFlow<FromOption?> = _selectedFrom.asStateFlow()

    /**
     * Switch the sending identity, and report what that asks of the body's signature (D5), or null when
     */
    suspend fun selectFrom(option: FromOption, isReplyOrForward: Boolean = false): SignatureChange? {
        val previous = selectedIdentity()
        _selectedFrom.value = option
        refreshPgp()
        val old = signatureTextOf(previous)
        val new = signatureTextOf(option.identity)
        if (old == new) return null
        val delimiter = settings.signatureDelimiter.first()
        val block = signatureEditorBlock(signatureHtmlOf(option.identity), new, delimiter)
        if (old.isNotBlank()) return SignatureChange.Swap(old, new, delimiter, block)
        if (new.isBlank()) return null
        if (isReplyOrForward && !settings.signatureOnReplies.first()) return null
        return SignatureChange.Insert(
            signature = new,
            belowQuote = isReplyOrForward && settings.signatureBelowQuote.first(),
            delimiter = delimiter,
            blockHtml = block,
        )
    }

    private fun selectedIdentity(): StoredIdentity? = _selectedFrom.value?.identity

    /**
     * Say that the quoted original was dropped (B6). On a slow link the quote arrives after compose
     */
    fun noticeQuoteNotAdded() {
        _notices.tryEmit(R.string.compose_quote_not_added)
    }

    /**
     * The screen has dealt with [replyQuote] — dropped it in, or said it could not: retire it so it is
     */
    fun consumeReplyQuote() {
        _replyQuote.value = null
    }

    /**
     * The signature the composer's picker is on, or null while it is on the identity's default (#206).
     *
     * Null is not "no signature": it means "whatever this identity's default is", so switching From
     * before touching the picker follows the new identity's own default rather than pinning the one
     * that happened to be showing.
     */
    private val _selectedSignature = MutableStateFlow<StoredSignature?>(null)
    val selectedSignature: StateFlow<StoredSignature?> = _selectedSignature.asStateFlow()

    /** What the compose screen's signature picker offers: the signatures of the identity being sent
     *  from, migrated on read, so a pre-#206 account offers exactly its one existing signature. */
    val signatureOptions: StateFlow<List<StoredSignature>> =
        _selectedFrom
            .map { it?.identity?.resolvedSignatures().orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The signature this compose is actually using: the picker's choice when that choice still belongs
     * to [identity], else [identity]'s own default (#206).
     *
     * The re-check against the identity's own list is the point. A choice made before a "From" switch
     * names a signature the new identity may never have had, and carrying it over would put one
     * persona's sign-off on another's mail — the same degradation [StoredAccount.defaultIdentity] does
     * for a stale identity id, for the same reason.
     */
    private fun activeSignature(identity: StoredIdentity?): StoredSignature? {
        val available = identity?.resolvedSignatures().orEmpty()
        val chosen = _selectedSignature.value?.id
        return available.firstOrNull { it.id == chosen } ?: identity?.defaultSignature()
    }

    /** The chosen signature's plain text, with a legacy raw-HTML one flattened first (by the
     *  migration in [StoredIdentity.resolvedSignatures]). */
    private fun signatureTextOf(identity: StoredIdentity?): String =
        activeSignature(identity)?.text.orEmpty()

    /**
     * The chosen signature as the HTML the body opens with (#1800): its own HTML source, else the HTML
     * twin stored for the same address on the sending account (an older text-only copy, or a manual
     * identity shadowing the server's HTML one), else its text with every URL and address linked.
     * Empty only in plain-text mode (the owner's opt-in, [plainTextMode]) or when there is no signature.
     */
    private fun signatureHtmlOf(identity: StoredIdentity?): String {
        if (_plainTextMode.value) return ""
        val signature = activeSignature(identity) ?: return ""
        val account = _selectedFrom.value?.accountId?.let { store.account(it) }
        return composeSignatureHtml(signature, signatureHtmlCandidates(account, identity?.email.orEmpty()))
    }


    /**
     * Turn plain-text mode on or off, and report the signature block the body now needs (the same
     * [SignatureChange] the pickers report, so the swap leaves the typed body alone), or null.
     */
    suspend fun setPlainTextMode(on: Boolean): SignatureChange? {
        if (_plainTextMode.value == on) return null
        _plainTextMode.value = on
        val identity = selectedIdentity()
        val text = signatureTextOf(identity)
        if (text.isBlank()) return null
        val delimiter = settings.signatureDelimiter.first()
        return SignatureChange.Swap(text, text, delimiter, signatureEditorBlock(signatureHtmlOf(identity), text, delimiter))
    }

    /**
     * Switch the signature mid-compose, and report what that asks of the body — the SAME
     * [SignatureChange] the "From" picker reports (#206).
     *
     * Deliberately not a second rewrite mechanism: swapping one signature's block for another's in a
     * half-written body is exactly what changing identity already had to do, down to leaving an edited
     * block alone. Reusing it means the two pickers cannot drift about what "the signature moved" does
     * to the owner's text.
     */
    suspend fun selectSignature(signature: StoredSignature): SignatureChange? {
        val identity = selectedIdentity()
        val old = signatureTextOf(identity)
        _selectedSignature.value = signature
        val new = signatureTextOf(identity)
        if (old == new) return null
        val delimiter = settings.signatureDelimiter.first()
        val block = signatureEditorBlock(signatureHtmlOf(identity), new, delimiter)
        if (old.isNotBlank()) return SignatureChange.Swap(old, new, delimiter, block)
        if (new.isBlank()) return null
        return SignatureChange.Insert(
            signature = new,
            belowQuote = settings.signatureBelowQuote.first(),
            delimiter = delimiter,
            blockHtml = block,
        )
    }

    /**
     * Add a signature to the sending identity from the composer's Signature section ("Add…", #1800),
     * then pick it, reporting the body swap the same way the picker does. The identity is stored as a
     * manual identity (a server identity is copied, its signatures carried over), so the new signature
     * survives the next server refresh. Null when no identity is selected.
     */
    suspend fun addSignature(name: String, source: String, makeDefault: Boolean): SignatureChange? {
        val from = _selectedFrom.value ?: return null
        val account = store.account(from.accountId) ?: return null
        val key = from.identity.email.trim().lowercase()
        val base = account.identities.firstOrNull { it.email.trim().lowercase() == key } ?: from.identity
        val existing = base.resolvedSignatures()
        val added = StoredSignature.of("sig-" + java.util.UUID.randomUUID().toString().take(8), name, source)
        val updated = base.copy(
            signatures = existing + added,
            defaultSignatureId = if (makeDefault || existing.isEmpty()) added.id else base.defaultSignatureId,
        )
        val manual = if (account.identities.any { it.email.trim().lowercase() == key }) {
            account.identities.map { if (it.email.trim().lowercase() == key) updated else it }
        } else {
            account.identities + updated
        }
        store.setIdentities(from.accountId, manual)
        val option = FromOption(from.accountId, updated)
        _fromOptions.value = _fromOptions.value.map { if (it.accountId == from.accountId && it.identity.email.trim().lowercase() == key) option else it }
        _selectedFrom.value = option
        return selectSignature(added)
    }

    /**
     * The body every opening starts from, AS HTML: the owner's first line (the reader's suggested reply
     * or a mailto: link's body, else an empty line to type on), the signature block per the three
     * signature settings, and [quoteBlock] - the reply's quoted original or the forwarded one - with
     * the signature above it or below it ([signatureBelowQuote]). A fresh message ([isReply] false)
     * always opens with its signature; a reply or forward obeys "signature on replies".
     */
    private suspend fun openingHtml(isReply: Boolean, quoteBlock: String, typed: String = suggestedReply.orEmpty()): String {
        val identity = selectedIdentity()
        val signature = if (!isReply || settings.signatureOnReplies.first()) {
            signatureEditorBlock(signatureHtmlOf(identity), signatureTextOf(identity), settings.signatureDelimiter.first())
        } else {
            ""
        }
        return composeOpeningHtml(typed, signature, quoteBlock, isReply && settings.signatureBelowQuote.first())
    }

    /**
     * The thread being answered, as plain text, for Answer Prediction. Set when the quote is built and
     * empty for a fresh message, which has nothing to answer.
     */
    @Volatile
    var answerSource: String = ""
        private set

    /** The reply the reader suggested, for a reply-all composer only; see [PendingReplySuggestion]. */
    private var suggestedReply: String? = null

    // --- OpenPGP -----------------------------------------------------------------------------

    /** The per-message crypto mode (lock toggle in the top bar). */
    private val _pgpMode = MutableStateFlow(PgpMode.OFF)
    val pgpMode: StateFlow<PgpMode> = _pgpMode.asStateFlow()

    /** Whether the toggle is offered: sending account has PGP set up + provider bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable: StateFlow<Boolean> = _pgpAvailable.asStateFlow()

    /** Per-address key availability for ENCRYPT mode (address → has a usable key). */
    private val _recipientKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val recipientKeys: StateFlow<Map<String, Boolean>> = _recipientKeys.asStateFlow()

    private var recipientKeysJob: Job? = null

    /** The recipient set [_recipientKeys] was last computed for; null when it never was, or when a
     *  change invalidated the answer. Gate of the anti-flicker rule, see [recipientKeysStale] (#35). */
    private var keyedRecipients: List<String>? = null

    /** True once the user sets the lock by hand: the mode then reflects their intent and is never
     *  auto-downgraded. While false the mode is derived from recipient-key availability (#35). */
    private var pgpModeUserSet = false

    /** Recipients with no usable key that kept an opportunistic default from encrypting, surfaced
     *  as an inline hint so it's clear why the message won't be encrypted (#35). */
    private val _pgpKeylessRecipients = MutableStateFlow<List<String>>(emptyList())
    val pgpKeylessRecipients: StateFlow<List<String>> = _pgpKeylessRecipients.asStateFlow()

    /** Emits the new mode only when the user cycles the lock by hand, so the confirmation snackbar
     *  fires on a deliberate toggle and never on an automatic opportunistic switch (#35). */
    private val _pgpToggleAnnounce = MutableSharedFlow<PgpMode>(extraBufferCapacity = 1)
    val pgpToggleAnnounce: SharedFlow<PgpMode> = _pgpToggleAnnounce.asSharedFlow()

    /** The sending account when its PGP is enabled with a key; else null. */
    private fun pgpAccount(): StoredAccount? =
        (_selectedFrom.value?.accountId ?: accountId ?: store.load()?.id)
            ?.let { store.account(it) }
            ?.takeIf { it.pgpEnabled && it.pgpSignKeyId != 0L }

    /** Re-evaluate availability (on open, on From changes, and once a restored message has put its own
     *  mode back); drops the mode if PGP is gone, and SAYS SO when that costs the user a lock they
     *  were shown (#35). The verdict itself is [pgpRefresh]. */
    private fun refreshPgp() {
        viewModelScope.launch {
            val account = pgpAccount()
            val available = account != null && pgp.isAvailable()
            _pgpAvailable.value = available
            // Another account means another keyring: whatever was looked up no longer answers for
            // these recipients (#35).
            keyedRecipients = null
            val outcome = pgpRefresh(
                current = _pgpMode.value,
                available = available,
                accountConfigured = account != null,
                encryptByDefault = account?.pgpEncryptByDefault == true,
                userSet = pgpModeUserSet,
            )
            _pgpMode.value = outcome.mode
            if (!available) _pgpKeylessRecipients.value = emptyList()
            // The padlock is the one indicator that has to be trusted, and it is about to disappear
            // from the top bar entirely. Losing it in silence is how a message the user encrypted
            // comes back looking ordinary, so name what went missing (#35).
            when (outcome.dropped) {
                PgpDropReason.NONE -> Unit
                PgpDropReason.NOT_CONFIGURED -> _notices.tryEmit(R.string.compose_pgp_not_configured)
                PgpDropReason.NO_PROVIDER -> _notices.tryEmit(R.string.message_pgp_no_provider)
            }
            // Last, and never in front of the mode: this writes nothing on screen and must not delay
            // what does.
            if (available) cachePgpPublicKey(account)
        }
    }

    /** Accounts whose public key this composer has already tried to read; see
     *  [pgpPublicKeyBackfill] for why one attempt per opening and not one per refresh. */
    private val pgpPublicKeyTried = mutableSetOf<String>()

    /**
     * Read this account's own public key from the provider and cache it, for accounts configured before
     */
    private suspend fun cachePgpPublicKey(account: StoredAccount?) {
        val id = pgpPublicKeyBackfill(account, pgpPublicKeyTried) ?: return
        val keyId = account?.pgpSignKeyId ?: return
        pgpPublicKeyTried += id
        val read = pgp.getPublicKey(keyId, account.username)
        if (read is PgpResult.Success) {
            store.setPgpPublicKey(id, keyId, pgpPublicKeyCacheValue(read.value))
        }
    }

    /** Lock toggle (short tap): OFF → SIGN → ENCRYPT → OFF. The cycle itself is [nextPgpMode]. */
    fun cyclePgpMode() = setPgpMode(nextPgpMode(_pgpMode.value))

    /**
     * The mode the user picked by hand — the lock's short tap, or the long-press menu, the only way to
     */
    fun setPgpMode(mode: PgpMode) {
        pgpModeUserSet = true
        _pgpKeylessRecipients.value = emptyList()
        _pgpMode.value = mode
        _pgpToggleAnnounce.tryEmit(_pgpMode.value)
    }

    /**
     * Refresh per-recipient key availability, then re-derive the opportunistic default mode (#35).
     */
    fun updateRecipientKeys(to: String, cc: String, bcc: String) {
        val account = pgpAccount()
        val care = _pgpAvailable.value &&
            (account?.pgpEncryptByDefault == true || _pgpMode.value.encrypts)
        if (!care) {
            _recipientKeys.value = emptyMap()
            keyedRecipients = null
            return
        }
        val addresses = encryptionRecipients(to, cc, bcc)
        if (!recipientKeysStale(keyedRecipients, addresses)) return
        keyedRecipients = addresses
        recipientKeysJob?.cancel()
        recipientKeysJob = viewModelScope.launch {
            _recipientKeys.value =
                if (addresses.isEmpty()) emptyMap()
                else runCatching { pgp.findKeysEach(addresses) }.getOrDefault(emptyMap())
            deriveAutoMode(addresses)
        }
    }

    /**
     * Opportunistic default (#35): with encrypt-by-default on and the lock not set by hand, encrypt
     */
    private fun deriveAutoMode(addresses: List<String>) {
        val account = pgpAccount() ?: return
        if (pgpModeUserSet || !account.pgpEncryptByDefault) return
        // Encrypt is the default intent; back off only when a recipient is CONFIRMED to have no key,
        // so a pending lookup leaves it optimistic and the lock does not flicker while typing.
        val keyless = addresses.filter { _recipientKeys.value[it] == false }
        if (keyless.isEmpty()) {
            _pgpMode.value = PgpMode.ENCRYPT
            _pgpKeylessRecipients.value = emptyList()
        } else {
            _pgpMode.value = PgpMode.OFF
            _pgpKeylessRecipients.value = keyless
        }
    }

    private val contactsEnabled =
        settings.contactSuggestions.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether device-contact recipient suggestions are enabled (drives the priming gate). */
    val contactSuggestionsEnabled = contactsEnabled

    /**
     * Whether the contacts-permission priming has already been offered. Initial value true so the
     * priming sheet never flashes before DataStore has loaded.
     */
    val contactsPrimed = settings.hasPrimedContacts.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Remember that the priming has been offered, so it is never shown again. */
    fun markContactsPrimed() {
        viewModelScope.launch { settings.setHasPrimedContacts(true) }
    }

    /** Enable (or disable) device-contact suggestions — the same setting as Settings > Privacy. */
    fun setContactSuggestions(enabled: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(enabled) }
    }

    /** Recipient autocomplete suggestions for the field currently being typed. */
    private val _suggestions = MutableStateFlow<List<ContactSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ContactSuggestion>> = _suggestions.asStateFlow()

    /** Suggest recipients for the last token in [fieldValue] (after the final comma/semicolon). */
    fun suggest(fieldValue: String) {
        val token = fieldValue.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            val recent = repo.suggestContacts(token, 6)
            // The address book is a content-provider query: off the main thread, since this runs on
            // every keystroke while the keyboard needs it.
            val device = if (contactsEnabled.value) {
                withContext(Dispatchers.IO) { AndroidContacts.query(getApplication(), token, 6) }
            } else {
                emptyList()
            }
            _suggestions.value = mergeSuggestions(recent, device, 6)
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    private var prepared = false
    // Threading headers for a reply (empty for new/forward).
    private var inReplyTo: List<String> = emptyList()
    private var references: List<String> = emptyList()
    /**
     * For a forward: the original carried verbatim to send time, appended below the user's note in
     * both alternatives so its formatting survives. Null for new/reply/replyAll.
     */
    private var forwarded: ForwardedBlocks? = null
    /** Account to send from: the replied-to message's account (unified inbox), else current. */
    private var accountId: String? = null

    /**
     * The saved draft being edited (#63): compose was opened from a message in Drafts. Sending or
     */
    private var editingDraftId: String? = null

    /**
     * The IMAP numbering [editingDraftId] belongs to, FROZEN at the moment that id was read (#99).
     */
    private var editingDraftUidValidity: Long? = null

    /**
     * The account [editingDraftId] was read from, FROZEN in the same breath as its numbering (#63 × #31).
     */
    private var editingDraftAccountId: String? = null

    /**
     * What the draft held WHEN IT WAS OPENED and this composer cannot put back: a genuine HTML body,
     */
    private var editingDraftLossyOnOpen = false

    /**
     * …and what THIS COMPOSER has lost since, on the way into an activity's saved-state parcel
     */
    private var composerBodyWasLost = false

    /**
     * THE LICENCE TO DESTROY, and the only name every destroy route reads — eight read sites over
     */
    private val editingDraftLossy: Boolean get() = editingDraftLossyOnOpen || composerBodyWasLost

    /**
     * The composer could not park its body, and the text is gone. Raised from the screen, once,
     * and idempotent: there is no gesture, anywhere, that makes a lost body come back.
     */
    fun onComposerBodyLost() {
        composerBodyWasLost = true
    }

    /**
     * The row of `local_drafts` this composer holds a lease on (#95), and the account it belongs to.
     */
    private val _editingLocalDraftId = MutableStateFlow<String?>(null)
    val editingLocalDraftId: StateFlow<String?> = _editingLocalDraftId.asStateFlow()
    private var editingLocalDraftAccountId: String? = null

    /**
     * The SERVER draft the reopened local row stands in for, and the numbering that id was frozen under
     */
    private var replacedServerDraftId: String? = null
    private var replacedServerDraftUidValidity: Long? = null

    /**
     * Which account the draft id this composer would carry was read under — handed to
     */
    private fun carriedDraftAccount(): String? = carriedDraftAccountId(
        editingDraftId = editingDraftId,
        serverDraftAccountId = editingDraftAccountId,
        localRowAccountId = editingLocalDraftAccountId,
    )

    /**
     * The saved draft behind this composer AS A MESSAGE — the cached row, ready to be deleted — or null
     */
    private val _editingDraft = MutableStateFlow<Email?>(null)
    val editingDraft: StateFlow<Email?> = _editingDraft.asStateFlow()

    /**
     * Whether a draft saved on the SERVER is behind this composer — what the leave dialog may not
     */
    private val _savedDraftBehind = MutableStateFlow(false)
    val savedDraftBehind: StateFlow<Boolean> = _savedDraftBehind.asStateFlow()

    /**
     * Whether the saved draft this composer was opened on is still being fetched — the window in which
     */
    private val _draftLoading = MutableStateFlow(false)
    val draftLoading: StateFlow<Boolean> = _draftLoading.asStateFlow()

    /**
     * The string id of what to say when the draft could not be read at all ([draftLoadNoticeFor]).
     */
    private val _draftLoadFailed = MutableStateFlow<Int?>(null)
    val draftLoadFailed: StateFlow<Int?> = _draftLoadFailed.asStateFlow()

    /**
     * The draft to delete, handed over exactly once. Refuses while a send or a save is in flight
     */
    fun takeEditingDraft(): Email? {
        if (_state.value is ComposeState.Sending) return null
        return _editingDraft.value?.also { _editingDraft.value = null }
    }

    private fun credentials(): AccountCredentials? =
        (_selectedFrom.value?.accountId ?: accountId)?.let { store.credentials(it) } ?: store.load()

    /**
     * Every address that IS the user on this account: the login plus each of its identities. A
     */
    private fun selves(credentials: AccountCredentials): Set<String> =
        (listOf(credentials.username) + store.identities(credentials.id).map { it.email })
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Reply/forward from the address the original was addressed to (#81): pre-select [accountId]'s
     */
    private fun preselectReceivingIdentity(accountId: String, original: Email) {
        receivingFromOption(_fromOptions.value, accountId, original)?.let { option ->
            if (option != _selectedFrom.value) {
                _selectedFrom.value = option
                refreshPgp()
            }
        }
    }

    /** Upload a picked document and add it to the outgoing attachments. */
    fun attach(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val resolver = app.contentResolver
                val type = resolver.getType(uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error(getApplication<Application>().getString(R.string.status_read_file_failed))
                val part = stageOutgoing(credentials, bytes, type, name, disposition = "attachment", cid = null)
                _attachments.value = _attachments.value + part
                _attachmentsTouched.value = true
                _attachmentStatus.value = null
            } catch (t: Throwable) {
                // The exception text is a resolver/HTTP string; keep it in logcat, where diagnosis
                // needs it.
                Log.w(TAG, "Attachment failed", t)
                // Offline, the upload cannot go anywhere and the raw "Unable to resolve host …" told
                // the reader nothing they could act on. Say what happened in a sentence — but only
                // when connectivity confirms it; every other failure keeps its technical message.
                _attachmentStatus.value = if (isOfflineFailure(t, online = hasUsableNetwork(app))) {
                    app.getString(R.string.status_attach_offline)
                } else {
                    app.getString(R.string.status_attach_failed, t.message ?: "error")
                }
            }
        }
    }

    /**
     * Stage a picked picture as an INLINE part (its own Content-ID) and hand the screen what the
     * editor needs to show it: the Content-ID and a preview data: URI. The editor tags the `<img>`
     * with the Content-ID and the send swaps the preview for `cid:` (multipart/related) - see
     * [htmlForWire].
     */
    fun attachInlineImage(uri: Uri, onPicked: (cid: String, previewDataUri: String, name: String) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _attachmentStatus.value = app.getString(R.string.status_attaching)
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                val resolver = app.contentResolver
                val type = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "image"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error(app.getString(R.string.status_read_file_failed))
                val cid = "img${System.nanoTime()}@cloud-mail"
                val part = stageOutgoing(credentials, bytes, type, name, disposition = "inline", cid = cid)
                val preview = withContext(Dispatchers.Default) { inlinePreviewDataUri(type, bytes) }
                _attachments.value = _attachments.value + part
                _attachmentsTouched.value = true
                _attachmentStatus.value = null
                onPicked(cid, preview, name)
            } catch (t: Throwable) {
                Log.w(TAG, "Inline image failed", t)
                _attachmentStatus.value = app.getString(R.string.status_attach_failed, t.message ?: "error")
            }
        }
    }

    fun removeAttachment(part: EmailBodyPart) {
        _attachments.value = _attachments.value.filterNot { it == part }
        _attachmentsTouched.value = true
    }

    /**
     * Take off the list every attachment whose staged bytes are no longer there, and say so. Called as
     */
    fun dropVanishedAttachments() {
        if (editingDraftId != null || editingOutboxId != null) return
        val app = getApplication<Application>()
        val sweep = sweepStagedAttachments(_attachments.value, File(app.cacheDir, "outgoing"), File::exists)
        if (sweep.gone.isEmpty()) return
        _attachments.value = sweep.kept
        _attachmentsTouched.value = true
        _attachmentStatus.value = app.getString(R.string.status_attach_vanished)
    }

    /**
     * Bind everything about a queued or undone message that is NOT the text of its fields — the two
     */
    private fun bindQueuedRow(d: SendOutbox.ComposeDraft, options: List<FromOption>) {
        _attachments.value = d.attachments
        inReplyTo = d.inReplyTo
        references = d.references
        // Reopening an undone forward: restore the carried original so it is still sent.
        if (d.forwardedText != null && d.forwardedHtml != null) {
            forwarded = ForwardedBlocks(d.forwardedText, d.forwardedHtml)
        }
        val match = options.firstOrNull {
            it.accountId == d.fromAccountId && it.identity.email == d.fromIdentityEmail
        } ?: options.firstOrNull { it.accountId == d.fromAccountId }
        if (match != null) _selectedFrom.value = match
        editingDraftId = d.draftEmailId
        // …with the numbering that id was frozen under, carried by the outbox row through the queue
        // and back (#99). Restored rather than re-read: looking it up again would answer the number a
        // sync recorded after any renumbering, which is what lets the expunge through on another
        // message. Null on a row queued before v23.
        editingDraftUidValidity = d.draftUidValidity
        // …and the ACCOUNT that pair belongs to. The outbox row has no column of its own for it and
        // is not getting one: `fromAccountId` answers for the draft too, because a queued row may
        // only carry a draft id belonging to the SAME account as itself. Restored, never re-read: the
        // identity on show can have been switched since.
        editingDraftAccountId = d.fromAccountId
        // The fidelity verdict travels WITH the id: before it did, undoing a send restored the id
        // while this flag fell back to false, and re-sending destroyed an original the first send had
        // deliberately spared.
        editingDraftLossyOnOpen = d.draftBodyIsLossy
        // The saved draft behind this screen (#35) follows the binding: settled at the head of
        // prepare() from the hand-over, which a composer rebuilt after a process death did not have.
        _savedDraftBehind.value = savedDraftBehindScreen(null, d.draftEmailId)
        // Restore the PGP mode the message carried (#35/#70): a signed item reopens signed, an undone
        // encrypted send reopens with the padlock on — never silently downgraded.
        d.pgpMode?.let { mode ->
            runCatching { PgpMode.valueOf(mode) }.getOrNull()?.let {
                _pgpMode.value = it
                pgpModeUserSet = true
                // Re-check OpenPGP now that the mode is back, instead of relying on the check started
                // above landing after this line: whether it has run depends on how far it got before
                // waiting for the provider, so a mode restored into an app that can no longer sign
                // survived or not by accident of timing. The drop, if any, is announced exactly once.
                refreshPgp()
            }
        }
        // Reopened from the outbox: its row waits in the queue marked EDITING until this composer
        // commits the message somewhere or hands it back (#70). An undone send carries no id — the
        // one case where the message only lives on this screen.
        _editingOutbox.value = d.editingOutboxId != null
        editingOutboxId = d.editingOutboxId
        _onlyCopy.value = d.editingOutboxId == null
    }

    /**
     * Take back, by [id], the queued row a composer rebuilt after a process death was opened on (#70).
     */
    private suspend fun resumeOutboxEdit(id: Long) {
        val app = getApplication<Application>()
        val draft = resumeOutboxRow(id, app.container.outboxRecovery) { rowId ->
            runCatching { repo.takeOutboxForEdit(rowId, File(app.cacheDir, "outgoing")) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't resume the outbox item $rowId", it) }
                .getOrNull()
        } ?: return
        bindQueuedRow(composeDraftOf(draft), _fromOptions.value)
    }

    /**
     * Build initial fields when opening as a reply/reply-all/forward of [replyToId], or, when [to] is
     */
    fun prepare(
        replyToId: String?,
        mode: String?,
        accountId: String? = null,
        restore: Boolean = false,
        to: String? = null,
        cc: String? = null,
        bcc: String? = null,
        subject: String? = null,
        body: String? = null,
        draftId: String? = null,
        quoteAlreadyOnScreen: Boolean = false,
        outboxId: Long? = null,
    ) {
        if (prepared) return
        prepared = true
        // Settle, for every way of opening the composer, whether a queued row is really parked behind
        // it (#96). The navigation argument survives the app being killed; the handed-over draft does
        // not, so `restore=true` with nothing in hand is an EMPTY composer and must be described as
        // one. Set here rather than in the restore branch alone so no path inherits a stale answer.
        _editingOutbox.value = holdsQueuedOutboxRow(restore, outbox.restored.value)
        // The OTHER thing that can survive this screen (#35): a draft saved on the server. Settled
        // here from the same two facts. Not in the branches below: the restore branch consumes
        // `outbox.restored`, so after it there is nothing left to ask.
        _savedDraftBehind.value =
            savedDraftBehindScreen(draftId, if (restore) outbox.restored.value?.draftEmailId else null)
        this.accountId = accountId
        val options = store.accounts().flatMap { acc ->
            store.identities(acc.id).map { FromOption(acc.id, it) }
        }
        _fromOptions.value = options
        val preferred = accountId ?: store.load()?.id
        // Prefer the preferred account's chosen default identity, then any identity of that account,
        // then the very first option. A reply/forward/restore path below overrides this.
        val defaultIdentityId = preferred?.let { store.defaultIdentityId(it) }
        _selectedFrom.value =
            options.firstOrNull { it.accountId == preferred && it.identity.id == defaultIdentityId }
                ?: options.firstOrNull { it.accountId == preferred }
                ?: options.firstOrNull()
        refreshPgp()

        // Reopening an undone send: restore every field the user had, including the
        // "From" identity, Cc/Bcc, and attachments, so nothing is lost.
        if (restore) {
            // Nothing handed over means the app was killed while this composer was open. With no id
            // in the route either — the Undo route after such a death — the fields stay empty and
            // nothing is guarded, since an empty screen has nothing to lose (#96). With an id, the
            // resume below reattaches the row once the startup sweep is over.
            val handed = outbox.restored.value
            handed?.let { d ->
                _prefill.value = DraftFields(
                    to = d.to, cc = d.cc, bcc = d.bcc, subject = d.subject, body = d.body,
                    // The model: the markup as it was queued, bold and links and all.
                    bodyHtml = d.bodyHtml,
                    // The styling comes back with the text (#131): held in memory for an undone send,
                    // parsed out of the queued row's html for an Outbox → Edit. Dropped here, undoing
                    // a send hands back a message with its bold removed, and the re-send delivers the
                    // removal.
                    bodyRanges = d.bodyRanges,
                    bodyBlocks = d.bodyBlocks,
                    bodyLinks = d.bodyLinks,
                    expand = d.cc.isNotBlank() || d.bcc.isNotBlank(),
                    // The box comes back ticked for a message that asked for a receipt (#70): this
                    // composer will enqueue a NEW row, so a clear box here cancels the request.
                    requestReceipt = d.requestReceipt,
                )
                bindQueuedRow(d, options)
            }
            outbox.consumeRestored()
            // After the consume, on purpose (OnlyCopyWiringTest pins the lambda against it), and on
            // viewModelScope: a composer popped before the sweep is over must not take a row that
            // nobody will release.
            outboxRowToResume(handed, outboxId)?.let { id -> viewModelScope.launch { resumeOutboxEdit(id) } }
            return
        }

        // Editing a saved draft: reopen it with every field it carried, and remember its id so sending
        // or re-saving replaces it instead of duplicating (#63).
        when (draftOpenRoute(draftId)) {
            DraftOpenRoute.LOCAL -> {
                // Raised HERE, before the coroutine, and NOT on its first line — the SERVER arm's reason,
                // and it holds on a Room read too: set inside, the editor is on screen, editable, with
                _draftLoading.value = true
                // The `finally` is at THIS level, not inside prepareLocalDraft: that function leaves
                // by three bare `return`s as well as by any throw from Room, and a flag only one path
                // lowers is a spinner for good.
                viewModelScope.launch {
                    try { prepareLocalDraft(draftId!!) } finally { _draftLoading.value = false }
                }
                return
            }
            DraftOpenRoute.SERVER -> {
                // The name stays `draftId` below, deliberately: the numbering freeze, the id and the
                // lossy verdict of this branch are pinned line by line by four wiring rules.
                val draftId: String = draftId!!
                // Raised HERE, before the coroutine, and NOT on its first line: the flag is up before
                // the first suspension point, so before any frame the fetch could delay. Set inside,
                // the editor is on screen — editable, with Save, Send and the padlock — which is
                // exactly the window a fast finger types into. Same reservation as the LOCAL arm.
                _draftLoading.value = true
                // THE ID AND ITS VERDICT, ARMED AS ONE GESTURE — before the first suspension point.
                // The invariant is no longer "the id is laid LAST" but "the LICENCE TO DESTROY is laid
                editingDraftId = draftId
                // …AND THE ACCOUNT IT WAS READ UNDER, IN THE SAME BREATH: `unlessDraftBelongsElsewhere`
                // lets a NULL draft account THROUGH on purpose, so an id armed beside a null account
                editingDraftAccountId = credentials()?.id
                editingDraftLossyOnOpen = true
                viewModelScope.launch {
                    try {
                        // No account to ask means the draft cannot be read either, so this path says
                        // so like any other failure. It used to fall through to a blank editable
                        // composer. Not "offline": the link may be perfect.
                        val credentials = credentials() ?: run {
                            _draftLoadFailed.value = draftLoadNoticeFor(offline = false)
                            return@launch
                        }
                        val draft = repo.fetchEmail(credentials, draftId)
                        // The row the Delete button would act on (#127), taken now, from the cache the
                        // Drafts list itself was drawn from. Only here: a draft that could not be read
                        // must not be offered a delete. Read scoped to this account (#31).
                        _editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()
                        // That same row is also the ONLY place a recipient the server dropped can
                        // still be found (#96), so the prefill is handed it — it fills an addressing
                        // field the server returned empty, never one the server filled.
                        _prefill.value = draftFieldsOf(draft, _editingDraft.value)
                        inReplyTo = draft.inReplyTo
                        references = draft.references
                        // The numbering the draft's id belongs to, frozen HERE and never read again:
                        // at save time it would be the number a refresh recorded after the folder was
                        editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)
                        // THE ACCOUNT IS NOT FROZEN HERE: it is armed at the entry, beside the id it
                        // describes. On this line it would be frozen only on the paths that reach it,
                        val lossy = reopenedDraftIsLossy(
                            hasHtmlBody = draftHtmlIsLossy(draft.htmlContent()),
                            inlineImageCount = draft.inlineImageParts().size,
                            calendarPartCount = draft.calendarParts().size,
                            receiptHeader = draft.dispositionNotificationTo,
                        )
                        // Its own `val`, and NEVER `lossy || !carryDraftAttachments(…)`: `||`
                        // short-circuits, so a draft already judged lossy would never have its
                        // attachments carried at all. The carry runs on every reopen; only the
                        // ASSIGNMENT waits.
                        val carried = carryDraftAttachments(credentials, draft)
                        // THE LICENCE TO DESTROY, LAID LAST — the final statement of this branch, and
                        // the only place the pessimistic verdict armed on entry is lowered. Until this
                        editingDraftLossyOnOpen = lossy || !carried
                    } catch (t: Throwable) {
                        // The draft could not be read. NOTHING is prefilled and, above all, no editor is
                        // drawn: a blank editable composer titled "Draft" cannot be told from a new
                        editingDraftLossyOnOpen = true
                        val app = getApplication<Application>()
                        _draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))
                    } finally {
                        // EVERY way out of the body lowers it, not just the two above: the silent
                        // `return@launch` with no credentials, and any throw from outside the try,
                        // would otherwise leave the composer waiting on a fetch that is over.
                        _draftLoading.value = false
                    }
                }
                return
            }
            DraftOpenRoute.NONE -> Unit
        }

        // A fresh mail pre-addressed to a participant, or prefilled from a mailto: link
        // (Codeberg #15) — no original to fetch.
        if (!to.isNullOrBlank() || !cc.isNullOrBlank() || !bcc.isNullOrBlank() ||
            !subject.isNullOrBlank() || !body.isNullOrBlank()
        ) {
            // Launched, not inline: the delimiter setting lives in DataStore and reading it suspends.
            // The screen already applies the prefill from a LaunchedEffect.
            viewModelScope.launch {
                _prefill.value = DraftFields(
                    to = to.orEmpty(),
                    cc = cc.orEmpty(),
                    bcc = bcc.orEmpty(),
                    subject = subject.orEmpty(),
                    body = "",
                    expand = !cc.isNullOrBlank() || !bcc.isNullOrBlank(),
                    // A fresh mail, so it opens with the signature below whatever the link carried: a
                    // mailto: body or shared text is plain text, wrapped as HTML here.
                ).withBodyHtml(openingHtml(false, "", typed = body.orEmpty()))
            }
            return
        }

        // A blank new message: it still opens with the signature in the body, where it can be read and
        // edited before sending. No draft/undo path reaches here — those return above with their own
        // body, which already contains the signature it was saved with.
        if (replyToId == null) {
            viewModelScope.launch {
                val html = openingHtml(false, "", typed = "")
                // An empty line alone is what the editor starts with anyway: only a signature is news.
                if (html != BLANK_OPENING_HTML) {
                    _prefill.value = DraftFields(to = "", subject = "", body = "").withBodyHtml(html)
                }
            }
            return
        }
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            // Parsed ONCE: the three branches below read this and never the raw string, so a mode one
            // of them does not know cannot open, in silence, a threaded quoted reply.
            val opening = composeOpening(mode)
            // The reader's suggestion, taken once and only by a reply-all of THAT message: any other
            // opening, or another message, clears it unread.
            val pending = getApplication<Application>().container.pendingReplySuggestion
            getApplication<Application>().container.pendingReplySuggestion = null
            suggestedReply = pending?.takeIf { it.emailId == replyToId && opening == ComposeOpening.REPLY_ALL }?.text
            fun prefillFailed() {
                _attachmentStatus.value =
                    getApplication<Application>().getString(R.string.compose_prefill_failed)
            }

            // Cache-FIRST, and never blocked on the network: To/Subject of a reply come from the
            // original's headers, which the cached list row already holds. Offline, fetchEmail below
            val cached = runCatching { repo.cachedEmail(credentials.id, replyToId) }.getOrNull()
            if (cached != null) {
                // BEFORE the prefill: the identity decides which signature the body opens with, so the
                // alias must be picked first (#81). Since schema v21 the cached row carries To, Cc AND
                // Bcc; the second attempt below on the fetched original stays for rows cached before
                // that migration, which are not back-filled.
                preselectReceivingIdentity(credentials.id, cached)
                _prefill.value = buildPrefill(cached, mode, selves(credentials), quoteBody = false)
                if (opening.threads) {
                    // Threading ids aren't cached on the list row — the fetch below supplies the real
                    // ones; keep whatever the cache has meanwhile.
                    inReplyTo = cached.messageId
                    references = cached.references + cached.messageId
                }
            }

            // THEN, in the background, fetch the full original — only to enrich the body. This is the
            // call that stalls offline, which is why the headers above did not wait on it.
            val original = runCatching { repo.fetchEmail(credentials, replyToId) }.getOrNull()

            if (original == null) {
                // No full body available. The headers are already prefilled from the cache; either way,
                // say the original couldn't load so the missing quote is not a silent surprise.
                // Unless the quote is already on screen: the banner would be drawn on top of the very
                // thing it says could not be loaded (G6). Only the sentence is dropped.
                if (!quoteAlreadyOnScreen) prefillFailed()
                return@launch
            }

            // The fetched original has the complete To AND Cc, so it can settle the sending identity
            // the cached row could not (#81). Still before any body is rebuilt below.
            preselectReceivingIdentity(credentials.id, original)

            if (opening.carriesOriginal) {
                // A forward's editable body stays empty; the original is carried at send time. Its
                // "Fwd: …" subject came from the cache above — set it now only if nothing was cached.
                if (cached == null) _prefill.value = buildPrefill(original, mode, selves(credentials))
                runCatching {
                    val blocks = buildForwarded(credentials, original)
                    forwarded = blocks
                    // The original goes INTO the editor, header block, HTML body and all, so what the
                    // owner sees is what is sent - and can be trimmed. Its attachments were staged by
                    // [buildForwarded]. Not again after a process death: the editor came back with it.
                    if (!quoteAlreadyOnScreen) {
                        _replyQuote.value = openingHtml(true, editorBlock(BLOCK_FORWARD, blocks.html))
                    }
                }.onFailure { prefillFailed() }
                return@launch
            }

            if (opening.attachesSource) {
                // Forward as attachment: the original's exact server bytes, staged as ONE
                // message/rfc822 part. Empty body, no threading ids, and no buildForwarded.
                if (cached == null) _prefill.value = buildPrefill(original, mode, selves(credentials))
                // Say so while the source is on its way, as attach() does: the composer is sendable
                // before the part is, and a "Fwd:" with an empty body must never look finished.
                _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
                runCatching {
                    // The attachment ceiling, restated on what actually came back. Over JMAP the
                    // download already refuses a blob over it; over IMAP the parser drains a literal
                    // over its own 32 MiB into an EMPTY source, which rawSource refuses. So this line
                    // is reached only if one of those two stops holding.
                    val bytes = repo.rawSource(credentials, replyToId)
                    DownloadLimits.enforce(bytes.size.toLong(), DownloadLimits.ATTACHMENT_MAX_BYTES)
                    stageOutgoing(
                        credentials, bytes, type = "message/rfc822",
                        name = safeFileName(original.subject, "message") + ".eml",
                        disposition = "attachment", cid = null,
                    )
                }.onSuccess { part ->
                    // In a straight line, NOT through attach(): that one arms _attachmentsTouched, and
                    // the composer would open "dirty" — a discard box on the way back without a key
                    // typed (#70).
                    _attachments.value = _attachments.value + part
                    _attachmentStatus.value = null
                }.onFailure { t ->
                    Log.w(TAG, "Forward as attachment: source not staged", t)
                    if (t is ContentTooLargeException) {
                        _attachmentStatus.value =
                            getApplication<Application>().getString(R.string.status_attachment_too_large)
                    } else {
                        prefillFailed()
                    }
                }
                return@launch
            }

            // Reply / reply-all: take the real threading ids from the fetched original, then hand the
            // quoted body to the screen out-of-band via [replyQuote] so it lands WITHOUT touching the
            // To/Subject already on screen. When nothing was cached, emit the full prefill instead.
            inReplyTo = original.messageId
            references = original.references + original.messageId
            if (cached == null) {
                _prefill.value = buildPrefill(original, mode, selves(credentials))
            } else if (!quoteAlreadyOnScreen) {
                // The whole body, not just the quote: the screen swaps it in wholesale and must keep the
                // signature the cache-first prefill put there.
                _replyQuote.value = openingHtml(true, quoteBlock(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))
            }
        }
    }

    /**
     * Reopen one of THIS PHONE's own drafts — a row of `local_drafts` the server has not got (#95).
     */
    private suspend fun prepareLocalDraft(id: String) {
        val credentials = credentials()
        if (credentials == null) {
            noticeLocalDraftNoAccount()
            return
        }
        // The lease is taken, remembered and — if this screen is popped while the take is still out
        // — GIVEN BACK BY THIS COROUTINE, under the cancellation shelter the decision carries. Armed
        // after the call instead, the id was never remembered when viewModelScope died inside it: the
        // row stayed EDITING and the draft reached the server only after the next cold start (#95).
        val lease = takeLocalDraftEditOrGiveItBack(
            id = id,
            take = { repo.takeLocalDraftForEdit(credentials.id, it) },
            // Remembered synchronously, and BEFORE any field is written: the back gesture can arrive
            // at the very next frame. Both halves, because ids collide between two accounts of one
            // server (#31) and an unscoped release hands ANOTHER account's open row to the worker.
            arm = { taken ->
                editingLocalDraftAccountId = credentials.id
                _editingLocalDraftId.value = taken.id
            },
            giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },
        )
        val row = when (lease) {
            // The row is gone — the worker consumed it between the list being drawn and the tap, or
            // the user threw it away. Nothing will bring it back, so the sentence must not ask for
            // anything.
            LocalDraftEdit.Gone -> {
                noticeLocalDraftGone()
                return
            }
            // The row is there and something else really owns it right now: its files are being
            // staged, or the worker is uploading it. A different sentence, because it asks for
            // something that WORKS: close this screen and open it again.
            // An EDITING row is NOT here: it is this phone's own stale lease, taken back above.
            LocalDraftEdit.Busy -> {
                noticeLocalDraftBusy()
                return
            }
            is LocalDraftEdit.Taken -> lease.row
        }
        val fields = localDraftPrefill(row)
        _prefill.value = DraftFields(
            to = fields.to,
            cc = fields.cc,
            bcc = fields.bcc,
            subject = fields.subject,
            body = fields.body,
            bodyHtml = fields.bodyHtml,
            // …and the styling the row stored, out of the SAME projection as the text.
            bodyRanges = fields.bodyRanges,
            bodyBlocks = fields.bodyBlocks,
            bodyLinks = fields.bodyLinks,
            expand = fields.expand,
            showAllRecipients = fields.showAllRecipients,
        )
        _attachments.value = fields.attachments
        inReplyTo = fields.inReplyTo
        references = fields.references
        // The address the draft was written as, so a delegated sub-account's draft reopens under the
        // identity it will send as and not under the login's (#31).
        fields.fromEmail?.let { written ->
            _fromOptions.value.firstOrNull {
                it.accountId == credentials.id && it.identity.email.equals(written, ignoreCase = true)
            }
        }?.let {
            _selectedFrom.value = it
            refreshPgp()
        }
        // The fidelity verdict travels WITH the id, as for an undone send: `false` here would launder
        // a body the composer knows it cannot reproduce, and the next send would destroy the original
        // this row was written to spare (#63).
        editingDraftLossyOnOpen = fields.bodyIsLossy
        // The row's OWN id: `localDraftTarget` looks a `local-draft:` id up by id, so a re-save lands
        // on this very row. The server draft it stands in for is NOT armed here — a local id names
        editingDraftId = fields.editingDraftId
        // The server draft this row stands in for, carried to the gestures that consume the row,
        // since each of those lifts the mask it holds over that copy. Kept apart from the pair above:
        // this id is not what a re-save lands on, and its numbering must never end up beside an id the
        // server did not issue.
        replacedServerDraftId = fields.replacedServerDraftId
        replacedServerDraftUidValidity = fields.replacedServerDraftUidValidity
        // Armed from the two terms the row carries, at the one place they are read, so the delete
        // confirmation can say BEFORE the tap that the copy on the server stays (#95).
        _deleteKeepsServerCopy.value = emptiedLocalDraftKeepsServerCopy(
            fields.replacedServerDraftId,
            fields.bodyIsLossy,
            addressingIsProvenEmpty = true,
        )
    }

    /**
     * The user emptied a draft this phone was keeping, and saved: destroy the SERVER draft it stood in
     */
    private suspend fun destroyReplacedServerDraft(localDraftId: String) {
        // The account the ROW was leased under, and never the one being written as. Handed
        // `credentials()`, this destroyed under whichever account the "From" picker shows: the lookup,
        val credentials = credentialsDestroyingReplacedServerDraft(
            leasedUnderAccountId = editingLocalDraftAccountId,
            composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,
            lookup = { store.credentials(it) },
        ) ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        val serverDraft = replacedServerDraftId
        val plan = planForEmptiedLocalDraft(
            replacedServerDraftId = serverDraft,
            // Frozen when the row was written, carried, never re-read (#99).
            replacedServerDraftUidValidity = replacedServerDraftUidValidity,
            mailboxId = serverDraft?.let { repo.draftMailboxOf(credentials, it) },
            bodyIsLossy = editingDraftLossy,
            // Read here, like `mailboxId` above, because establishing it means asking the server while
            // the decision stays pure. Under the account the ROW was leased under. The two guards in
            addressingIsProvenEmpty = serverDraft != null && !editingDraftLossy &&
                repo.emptiedDraftAddressingIsProvenEmpty(credentials, serverDraft),
        )
        destroyThenConsumeEmptiedLocalDraft(
            localDraftId = localDraftId,
            plan = plan,
            destroy = { MessageDestroyWorker.destroyDurably(getApplication(), credentials.id, listOf(it)) },
            evict = { ids -> repo.evictAll(credentials.id, ids) },
            consume = { id -> consumeEditingLocalDraft(id) },
            tell = { _notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept) },
        )
    }

    /**
     * Delete the draft this phone is KEEPING, from the composer that holds it (#95 × #127).
     */
    fun deleteEditingLocalDraft() {
        val id = heldEditingLocalDraft() ?: return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            var destroyed = false
            try {
                destroyReplacedServerDraft(id)
                destroyed = true
                _localDraftDeleted.tryEmit(Unit)
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            } finally {
                localDraftLeaseAfterDelete(_editingLocalDraftId.value, destroyed)?.let { releaseLocalDraftEdit(it) }
            }
        }
    }

    /**
     * The lease on this phone's own draft row as this composer holds it — read, never cleared. Who
     */
    private fun heldEditingLocalDraft(): String? {
        if (_state.value is ComposeState.Sending) return null
        return _editingLocalDraftId.value
    }

    /**
     * Say a draft of the PHONE's own store could not be opened because no account resolved (#95).
     */
    private fun noticeLocalDraftNoAccount() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Say the draft the phone was keeping is not on the phone any more (#95) — the upload worker consumed
     */
    private fun noticeLocalDraftGone() {
        _draftLoadFailed.value = R.string.compose_local_draft_gone
    }

    /**
     * Say the draft is on the phone but not this composer's to open right now — its files are being
     */
    private fun noticeLocalDraftBusy() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Take the reopened local draft away — its row AND its staged files — because the composer has just
     */
    private suspend fun consumeEditingLocalDraft(id: String) {
        val account = editingLocalDraftAccountId ?: return
        _editingLocalDraftId.value = null
        repo.consumeLocalDraft(account, id)
    }

    /**
     * Leaving the composer without committing the message anywhere. A message reopened from the outbox
     */
    fun abandon(body: String, emptying: Boolean = false) {
        // All three answers are decided in one place, in values, and executed here (#95 × #70). A send
        // in flight gives back NOTHING — it owns the message and consumes both rows itself — and
        // outside one the local row is independent. `abandonPlan` is executed in SendDraftTargetTest.
        val plan = abandonPlan(
            sending = _state.value is ComposeState.Sending,
            emptying = emptying,
            parkOutbox = closingParksQueuedRow(composerBodyWasLost, body),
            localDraftId = _editingLocalDraftId.value,
            outboxId = editingOutboxId,
        )
        plan.localDraftToRelease?.let { releaseLocalDraftEdit(it) }
        // The row never left the queue — it only sat in EDITING — so giving it back is flipping its
        // state to QUEUED and re-arming delivery. Nothing is rebuilt, nothing can be lost (#70).
        plan.outboxToRelease?.let { id ->
            editingOutboxId = null
            _editingOutbox.value = false
            appScope.launch {
                runCatching { repo.releaseOutboxEdit(id) }
                    .onFailure { android.util.Log.w("SternaCompose", "couldn't release the edited outbox item", it) }
            }
        }
        // The other door, and NEVER releaseOutboxEdit: parked FAILED with its reason, off the
        // worker, reopenable.
        plan.outboxToPark?.let { id ->
            editingOutboxId = null
            _editingOutbox.value = false
            appScope.launch {
                runCatching { repo.parkInterruptedOutboxEdit(id) }
                    .onFailure { android.util.Log.w("SternaCompose", "couldn't park the interrupted outbox item", it) }
            }
        }
    }

    /**
     * Give back the lease taken by [prepareLocalDraft] (#95): the row goes from EDITING to PENDING and
     */
    private fun releaseLocalDraftEdit(id: String) {
        val account = editingLocalDraftAccountId ?: return
        _editingLocalDraftId.value = null
        appScope.launch {
            runCatching { repo.releaseLocalDraftEdit(account, id) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't release the edited local draft", it) }
        }
    }

    /**
     * Consume the queued row this composer was editing (#70): the message has just been committed
     */
    private suspend fun consumeEditingOutbox() {
        val id = editingOutboxId ?: return
        editingOutboxId = null
        _editingOutbox.value = false
        runCatching { repo.deleteOutbox(id) }
    }

    /**
     * Park the queued row this composer was editing in [state] (#95 × #70) — the other end of
     */
    private fun parkEditingOutbox(state: OutboxState) {
        val id = editingOutboxId ?: return
        editingOutboxId = null
        _editingOutbox.value = false
        appScope.launch {
            runCatching { repo.parkOutboxEdit(id, state) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't park the edited outbox item", it) }
        }
    }

    /**
     * Queue the message in the persistent outbox with a hold-back window (Undo-send): validate +
     */
    fun send(to: String, cc: String, bcc: String, subject: String, body: RichBody, requestReceipt: Boolean) =
        sendInternal(SendArgs(to, cc, bcc, subject, body, requestReceipt), interactionResult = null)

    /**
     * The composer's field values, kept so a PGP interaction round-trip can retry the send. The
     */
    private data class SendArgs(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: RichBody,
        val requestReceipt: Boolean,
    )

    private var pendingSendArgs: SendArgs? = null

    /**
     * Continue a send paused on [ComposeState.PgpInteraction]. [interactionResult]
     * is the provider dialog's result Intent, or null when the user cancelled.
     */
    fun retryPgpSend(interactionResult: Intent?) {
        val args = pendingSendArgs
        pendingSendArgs = null
        if (args == null || interactionResult == null) {
            _state.value = ComposeState.Idle
            return
        }
        sendInternal(args, interactionResult)
    }

    /** Thrown inside a send when the OpenPGP provider needs the user first. */
    private class PgpInteractionNeeded(val pendingIntent: PendingIntent) : Exception()

    private fun sendInternal(args: SendArgs, interactionResult: Intent?) {
        // A body this composer LOST never leaves, and never takes the only copy of the text with it
        // (#95): the row in `local_drafts` under an offline draft is the ONLY place that text exists,
        val lostBody = lostBodySendWording(
            bodyWasLost = composerBodyWasLost,
            body = args.body.text,
            holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || editingOutboxId != null,
        )
        if (lostBody != null) {
            _notices.tryEmit(
                when (lostBody) {
                    LostBodyWording.REOPEN_DRAFT -> R.string.compose_body_lost_reopen_draft
                    LostBodyWording.RETYPE -> R.string.compose_body_lost_cannot_send
                },
            )
            return
        }
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        val (to, cc, bcc, subject, body) = args
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val ccList = parseAddrs(cc)
                val bccList = parseAddrs(bcc)
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesForSend(body)
                // A picture the owner took out of the body again must not ride along as an orphan part.
                val attachments = withoutUnusedInlineParts(_attachments.value, htmlBody)
                val replyTo = inReplyTo
                val refs = references
                // Sign/encrypt NOW, while the provider can still show UI — the outbox worker runs
                // headless later. The outbox then only ever holds the signed/encrypted entity.
                val mode = _pgpMode.value
                val pgpEntity = if (mode != PgpMode.OFF) {
                    try {
                        buildPgpEntity(
                            credentials, recipients + ccList + bccList,
                            textBody, htmlBody, attachments, mode,
                            protectedSubject(mode, subject), interactionResult,
                        )
                    } catch (e: PgpInteractionNeeded) {
                        pendingSendArgs = args
                        _state.value = ComposeState.PgpInteraction(e.pendingIntent)
                        return@launch
                    }
                } else {
                    null
                }
                // Which server draft this send replaces, and under which numbering — ONE answer for
                // both, and the same one for the outbox row and for the record an undone send reopens
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // Persist the send held for the undo window; the worker delivers it after.
                // And ONLY THEN take the phone's own draft row away (#95). The order is grid rule 1:
                val id = commitThenConsumeLocalDraft(
                    localDraftId = _editingLocalDraftId.value,
                    commit = {
                        repo.enqueueSend(
                            credentials, recipients, subject, textBody, replyTo, refs,
                            attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                            holdMs = SendOutbox.HOLD_MS,
                            pgpMode = mode.takeIf { it != PgpMode.OFF },
                            prebuiltEntity = pgpEntity,
                            // Editing a saved draft (#63): once this send is delivered the worker
                            // destroys the draft it came from. Never one of this phone's own ids,
                            // which names nothing a server could be asked to destroy.
                            draftEmailId = sendTarget.emailId,
                            // …under the numbering frozen when that id was read (#99), off the SAME
                            // answer as the id: the two halves are meaningful only together. Persisted
                            // with the row because the destroy runs at DELIVERY, and the folder may
                            // have been renumbered by then.
                            draftUidValidity = sendTarget.uidValidity,
                            // — and ONLY against proof: the composer's verdict rides along, and
                            // enqueueSend leaves the id off the row when it cannot show the send
                            // reproduces the draft. The message still goes out; the original survives
                            // in Drafts instead of being destroyed behind an amputated send.
                            bodyIsLossy = editingDraftLossy,
                            // Asked for on this screen, written at DELIVERY time by a headless worker —
                            // so it has to be on the row, not in this process.
                            requestReceipt = args.requestReceipt,
                        )
                    },
                    consume = { local -> consumeEditingLocalDraft(local) },
                )
                // The edits are queued now under this fresh row, so the row taken out for editing is
                // consumed — dropped from the queue rather than given back (#70).
                consumeEditingOutbox()
                // Keep the raw draft so undoing the send can reopen compose with it intact.
                val draft = SendOutbox.ComposeDraft(
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body.text,
                    // The styling as the composer held it — NOT re-parsed from the html that went out,
                    // which carries the signature substituted verbatim and would not parse back (#131).
                    bodyRanges = body.ranges,
                    bodyBlocks = body.blocks,
                    bodyLinks = body.links,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                    // The forwarded original is a block of the body now, so undoing the send hands it
                    // back inside [bodyHtml] - carrying it a second time would send it twice.
                    bodyHtml = body.html,
                    pgpMode = mode.takeIf { it != PgpMode.OFF }?.name,
                    // The same answer as the row above, and deliberately the same VALUE: undoing this
                    // send reopens the composer from THIS record, whose local row has already been
                    // consumed, so the server draft it stood in for can only be named here now.
                    draftEmailId = sendTarget.emailId,
                    // Undoing this send reopens the composer from THIS record: without the frozen
                    // numbering the reopened message could no longer destroy the draft it replaces.
                    draftUidValidity = sendTarget.uidValidity,
                    draftBodyIsLossy = editingDraftLossy,
                    requestReceipt = args.requestReceipt,
                )
                val app = getApplication<Application>()
                // WYSIWYG (#70): every send is queued behind the undo window, and the message only
                // leaves once the worker submits it. So never claim "sent" here: say "Sending…" when
                // there is a connection to hand it to, and "queued" when offline. This stays honest
                // under a VPN killswitch too — the real outcome surfaces via the Outbox.
                val queuedOffline = !hasUsableNetwork(app)
                outbox.hold(
                    label = app.getString(
                        if (queuedOffline) R.string.status_message_queued
                        else R.string.status_message_sending,
                    ),
                    draft = draft,
                ) {
                    // Undo within the window: drop the queued row so nothing is sent and nothing stays
                    // parked. Delete the row first, then cancel its worker; should the cancel be a
                    // no-op, a later run finds no row and exits.
                    repo.deleteOutbox(id)
                    Outbox.cancel(app, id)
                }
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Schedule the message to be sent at [sendAtMillis]. Persisted to Room and fired by WorkManager.
     */
    fun scheduleSend(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: RichBody,
        sendAtMillis: Long,
        requestReceipt: Boolean,
    ): Boolean {
        // A scheduled send is handed to a headless worker that can neither sign nor encrypt (so any
        // PgpMode but OFF would go out unsigned, #35) and its table carries no attachments yet (so it
        // would send amputated, A2/A3). The toolbar disables the button for both; this is the choke
        // point. Only the pgp case has user-facing wording — attachments cannot reach here.
        if (!scheduleSendAllowed(_pgpMode.value, _attachments.value.isNotEmpty())) {
            if (_pgpMode.value != PgpMode.OFF) _notices.tryEmit(R.string.compose_pgp_no_draft)
            return false
        }
        // …and the same refusal as the immediate send, for the same reason: a scheduled send commits
        // just as truly and consumes the phone's own draft row on the way out. `body.text` is this
        // function's own raw parameter; the `textBody` computed below carries the signature.
        val lostBody = lostBodySendWording(
            bodyWasLost = composerBodyWasLost,
            body = body.text,
            holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || editingOutboxId != null,
        )
        if (lostBody != null) {
            _notices.tryEmit(
                when (lostBody) {
                    LostBodyWording.REOPEN_DRAFT -> R.string.compose_body_lost_reopen_draft
                    LostBodyWording.RETYPE -> R.string.compose_body_lost_cannot_send
                },
            )
            return false
        }
        if (_state.value is ComposeState.Sending) return false
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesForSend(body)
                // The same one answer as the send above: id and numbering together, the server draft
                // named off the row when this composer was reopened from one (#95), then withheld
                // WHOLE when the composer cannot show the message reproduces the draft (#63). The
                // scheduled row has nowhere to say so, so the id is the carrier.
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessBodyIsLossy(editingDraftLossy)
                    // …and withheld again, WHOLE, when the draft belongs to another account than the
                    // one this row is written under: a different question from the verdict above. The
                    // row carries `accountId = credentials.id`, so an id of A on it fires against B's
                    // server at the hour chosen.
                    .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // Same order as the send above (#95): the scheduled row is written and its worker
                // booked FIRST, and only then does the phone's own draft row go.
                commitThenConsumeLocalDraft(
                    localDraftId = _editingLocalDraftId.value,
                    commit = {
                        val id = repo.insertScheduledSend(
                            ScheduledSendEntity(
                                accountId = credentials.id,
                                recipients = recipients.joinToString(","),
                                cc = parseAddrs(cc).joinToString(",").ifBlank { null },
                                bcc = parseAddrs(bcc).joinToString(",").ifBlank { null },
                                subject = subject,
                                textBody = textBody,
                                htmlBody = htmlBody,
                                fromName = identity?.name,
                                fromEmail = identity?.email,
                                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                                references = references.joinToString(" ").ifBlank { null },
                                sendAtMillis = sendAtMillis,
                                // A scheduled send of an edited draft still replaces it (#63), but this
                                // row cannot carry the fidelity verdict, so the id itself is the
                                draftEmailId = sendTarget.emailId,
                                // The row waits for hours, so the frozen numbering waits with it (#99):
                                // read at the hour the send fires it would be the number recorded after
                                // a renumbering, and the expunge would land on another draft.
                                draftUidValidity = sendTarget.uidValidity,
                                // The row waits for hours; the request has to wait with it, or the
                                // hand-over to the outbox drops it.
                                requestReceipt = requestReceipt,
                            ),
                        )
                        ScheduledSends.enqueue(getApplication(), id, scheduledSendDelayMillis(sendAtMillis, System.currentTimeMillis()))
                    },
                    consume = { local -> consumeEditingLocalDraft(local) },
                )
                // The message now waits in the scheduled table instead; the outbox row it came from is
                // consumed rather than given back, or it would send twice (#70).
                consumeEditingOutbox()
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
        // Accepted — the coroutine above owns what happens next. A failure inside it lands on
        // `ComposeState.Error`; this answer is about the GESTURE.
        return true
    }

    /**
     * The outgoing (text, html) pair for [userBody], whose HTML is the model: the wire html is
     * [htmlForWire] of exactly what the editor holds - signature, quote or forwarded original are
     * blocks IN it - and the text alternative is derived from THAT html ([htmlToPlainText]), so the
     * two halves of one message cannot disagree. The html is never null: a message leaves as
     * multipart/alternative or not at all.
     */
    private fun bodiesForSend(userBody: RichBody): Pair<String, String> {
        val html = htmlForWire(userBody.html ?: toHtml(userBody))
        // Plain-text mode (#1800, opt-in) only strips the formatting off the html part.
        if (_plainTextMode.value) return composeSendBodies(html, plainTextMode = true)
        return htmlToPlainText(html) to html
    }

    /**
     * Carry a reopened draft's file attachments back into compose (#63), staged like a forward's; inline
     */
    private suspend fun carryDraftAttachments(credentials: AccountCredentials, o: Email): Boolean {
        val parts = o.fileAttachmentParts()
        val staged = mutableListOf<EmailBodyPart>()
        for (part in parts) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged
        return staged.size == parts.size
    }

    /**
     * A draft is worth persisting only if it carries real content (#69). A wholly empty compose does
     */
    private fun hasDraftContent(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
    ): Boolean = draftHasContent(to, cc, bcc, subject, body, _attachments.value.isNotEmpty())

    fun saveDraft(to: String, cc: String, bcc: String, subject: String, body: RichBody, requestReceipt: Boolean) {
        // A save is the CAUTIOUS gesture, and on one route it was the destructive one: a queued message
        // reopened by Outbox → Edit and redrawn empty is saved as an empty draft, and
        if (lostBodySaveDestroysQueuedRow(bodyWasLost = composerBodyWasLost, body = body.text, holdsQueuedRow = editingOutboxId != null)) {
            _notices.tryEmit(R.string.compose_body_lost_reopen_draft)
            return
        }
        // The plaintext guarantee, enforced where the upload happens (#35): an encrypted message never
        // leaves a readable copy on the server, draft included. The toolbar and the leave dialog
        // already refuse, so this is belt-and-braces — but it is the one choke point every save route
        // goes through. Refuse and say so: the composer stays open with the text intact.
        if (!draftSaveAllowed(_pgpMode.value)) {
            _notices.tryEmit(R.string.compose_pgp_no_draft)
            return
        }
        // Empty by the #69 rule: persist nothing. A brand-new compose leaves no trace at all; an opened
        // draft the user has emptied has its original deleted so no empty shell lingers in Drafts.
        if (!hasDraftContent(to, cc, bcc, subject, body.text)) {
            val original = editingDraftId
            // The numbering frozen when that id was read — carried, never re-read (#99).
            val originalUidValidity = editingDraftUidValidity
            // …and the ACCOUNT both were read from, captured with them and for the same reason: the
            // expunge below is addressed to it, never to the identity the "From" picker is showing —
            // that picker covers every account (#31 × #63).
            val originalAccountId = editingDraftAccountId
            // The phone's own row is handed back to NOBODY on this close, and the "nobody" is said to
            // abandon() rather than by blanking the field: abandon() would hand the row back PENDING and
            abandon(emptying = true, body = body.text)
            if (original == null) {
                _state.value = ComposeState.Done
            } else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {
                // NOT repo.discardDraft: for an id no server ever issued the IMAP route destroys
                // NOTHING, and the screen closed as a success while the emptied draft went up on the next
                submit(to) { _, _ -> destroyReplacedServerDraft(original) }
            } else {
                // …and the SERVER route asks the same question, because it is the same draft and the
                // same expunge: a draft never read WHOLE keeps its server copy (emptiedServerDraftIsKept).
                submit(to) { _, _ ->
                    // The account the draft was READ FROM, resolved here and nowhere else. `?:
                    // credentials` appended is the whole defect back: an id JMAP's destroyAll spares, or an
                    fun destroyingCredentials(): AccountCredentials =
                        credentialsDestroyingEmptiedServerDraft(
                            openedUnderAccountId = originalAccountId,
                            composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,
                            lookup = { store.credentials(it) },
                        ) ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                    // NOT a decision taken here — the decision is the function below, and this is the
                    // term it reads. `!editingDraftLossy &&` skips a server read whose answer cannot
                    val addressingIsProvenEmpty = !editingDraftLossy &&
                        repo.emptiedDraftAddressingIsProvenEmpty(destroyingCredentials(), original)
                    if (emptiedServerDraftIsKept(editingDraftLossy, addressingIsProvenEmpty = addressingIsProvenEmpty)) {
                        _notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept)
                    } else {
                        repo.discardDraft(destroyingCredentials(), original, originalUidValidity)
                    }
                }
            }
            return
        }
        submit(to) { credentials, recipients ->
            // Keep a reply draft threaded, carry the attachments the chips are showing into the saved
            // copy, and replace the draft being edited (#63) rather than piling up a copy per save —
            val identity = selectedIdentity()
            // What this save REPLACES: the id frozen at the open and its numbering, as ONE pair (#99) —
            // and NOTHING AT ALL when that pair was read under another account (#31 × #63). Unlike every
            val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)
                .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
            val outcome = repo.saveDraft(
                // `toPlainText(body)` and never `body.text` (#131): the text part is the alternative
                // a client with no HTML shows, and it is the ONE plain-text answer the send already
                // uses. `body.text` here stores a draft whose text part has the list markers nowhere
                // and every URL missing, while the message built from the same body has both.
                credentials, recipients, subject, toPlainText(body),
                // The styling as a SECOND part, decided by the one pure function every route uses
                // (`draftHtmlToSave`): null for a body with no styling, so a plain draft is stored
                // exactly as before #131. NOT `htmlBodyWithSignature`, which is the SEND's html — it
                // substitutes an imported signature verbatim and would not parse back on reopen.
                html = draftHtmlToSave(body),
                cc = parseAddrs(cc), bcc = parseAddrs(bcc),
                inReplyTo = inReplyTo, references = references,
                replacesEmailId = replaces.emailId,
                replacesUidValidity = replaces.uidValidity,
                attachments = _attachments.value,
                bodyIsLossy = editingDraftLossy,
                composerBodyWasLost = composerBodyWasLost,
                // The emptiness travels APART from the text, because the text no longer answers it:
                // `toPlainText(body)` of an empty body carrying one bullet is `"- "`, which is not
                // blank. Judged there, the lost-body guard stands down and `"- "` is written over the
                // only copy of a local draft. `body.text` is what the user TYPED.
                typedBodyIsBlank = body.text.isBlank(),
                fromName = identity?.name, fromEmail = identity?.email,
                requestReceipt = requestReceipt,
            )
            // Which outcomes are worth a word is decided in the data layer and EXECUTED there
            // (draftSaveNeedsNotice): a draft kept on the phone because the network was down is a
            // successful save (#95).
            if (draftSaveNeedsNotice(outcome)) {
                _notices.tryEmit(R.string.compose_draft_original_kept)
            }
            // A queued row the save did NOT put on the server is PARKED, and parked FIRST: left as
            // the composer found it (EDITING) it is on no screen at all, and the next launch hands it
            // back to the queue and sends the pre-edit message with no undo window — after the user
            // tapped "save as draft". Before the consume, which clears editingOutboxId (#95 × #70).
            draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }
            // The queued row it came from is consumed — its row AND its staged files — only once the
            // draft has really reached Drafts (draftSaveConsumesTheQueuedRow). Unconditional, a save
            // made with no network destroyed the only copy the user could still see (#95 × #70).
            if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()
        }
    }

    private inline fun submit(
        to: String,
        crossinline op: suspend (AccountCredentials, List<String>) -> Unit,
    ) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            // The success as a VALUE, not as a position in the try: what the `finally` below does with
            // the row depends on whether `op` came back.
            var saved = false
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, parseAddrs(to))
                saved = true
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                // Saving a draft, not sending: the error banner must say so (#63 — a failed draft save
                // used to read "couldn't send", pointing users at the wrong step).
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            } finally {
                // A save gives the phone's own row back too, and until this line nothing did
                // (#95 × #31). `abandon()` is the only other release and only the CANCEL calls it, this
                localDraftLeaseAfterSave(_editingLocalDraftId.value, saved)?.let { releaseLocalDraftEdit(it) }
            }
        }
    }

    /**
     * Initial fields for a reply/reply-all/forward of [original]. [quoteBody] is false when the full body
     */
    private suspend fun buildPrefill(
        original: Email,
        mode: String?,
        selves: Collection<String>,
        quoteBody: Boolean = true,
    ): DraftFields {
        // Resolved by SUSPENDING on the stored setting (#120), like the signature settings: a composer
        // opened moments after launch must not fall back to the default while DataStore is still
        // opening — with the switch on, that beat would write local time.
        val zone = resolveOutgoingDateZone(settings.quotedDatesUtc)
        val opening = composeOpening(mode)
        return when (opening) {
            // Same fields for both forwards: what differs (carried inline vs. attached as .eml)
            // happens in prepare(), after this prefill.
            ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT -> DraftFields(
                to = "",
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = "",
                // The editable body starts with just the user's line and the signature when the setting
                // is on; the original lands in the editor as its own block once it is fetched. See
                // [buildForwarded].
            ).withBodyHtml(openingHtml(true, ""))
            ComposeOpening.REPLY_ALL -> DraftFields(
                to = replyAllRecipients(original, selves),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = "",
                // The one field a reply-all sets and a reply does not, and the reason is the reader's
                // toolbar: reply-all is the DEFAULT action there now, so the composer has to make
                // "this goes to all of them" impossible to miss rather than something to go looking
                // for behind a "+6". Every recipient chip stays on screen.
                showAllRecipients = true,
                // Travels with the body above and says exactly what that body holds: when the original
                // was not cached, THIS is how the quote reaches the screen, so it is the only thing
                // that can let the composer keep quiet about a quote it is showing after a process
                // death.
                quoted = quoteBody,
            ).withBodyHtml(openingHtml(true, if (quoteBody) quoteBlock(original, zone) else ""))
            ComposeOpening.REPLY -> DraftFields(
                to = replyRecipient(original),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = "",
                // Same claim as the reply-all above, and `quoteBody` and not `true`: when the full body
                // could not be loaded the quote was skipped, and claiming otherwise would silence a
                // failure the reader must hear about.
                quoted = quoteBody,
            ).withBodyHtml(openingHtml(true, if (quoteBody) quoteBlock(original, zone) else ""))
        }
    }

    /**
     * The quoted original for a reply, as the editor's quote block: a localised attribution line with
     * the date formatted for the owner, then the original's own sanitised HTML in a `<blockquote>`
     * (a text-only original is escaped, line breaks kept) - see [buildQuoteHtml].
     */
    private fun quoteBlock(o: Email, zone: ZoneId): String {
        val app = getApplication<Application>()
        val sender = o.from.firstOrNull()?.display() ?: app.getString(R.string.compose_quote_someone)
        val date = MailDates.formatFull(o.receivedAt, zone)
        val attribution = app.getString(R.string.compose_quote_attribution, date, sender)
        val (raw, isHtml) = bodySource(o)
        answerSource = originalPlainText(o)
        return editorBlock(BLOCK_QUOTE, buildQuoteHtml(attribution, raw.takeIf { isHtml }, raw))
    }

    /** [html] as this prefill's body: the model, and its plain projection beside it. */
    private fun DraftFields.withBodyHtml(html: String): DraftFields =
        copy(bodyHtml = html, body = htmlToPlainText(html))

    /**
     * Prebuild the forwarded-original blocks (text + cleaned html) carried to send time, and re-stage the
     */
    private suspend fun buildForwarded(credentials: AccountCredentials, o: Email): ForwardedBlocks {
        val carriedCids = mutableSetOf<String>()
        val staged = mutableListOf<EmailBodyPart>()

        for (part in o.inlineImageParts()) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() } ?: continue
            val outPart = runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "inline", cid = cid)
            }.getOrNull()
            // Carry the image only if it staged; otherwise its cid stays out of [carriedCids] so
            // cleanForwardedHtml neutralises that specific image.
            if (outPart != null) {
                staged += outPart
                carriedCids += cid
            }
        }
        val fileParts = o.fileAttachmentParts()
        var carried = 0
        for (part in fileParts) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it; carried++ }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged
        // A12 (data loss): a file that would not download used to be dropped without a word, so the
        // forward opened looking complete while it was short of what the original carried. Say so
        // rather than sending an amputated forward silently.
        if (carried < fileParts.size) _notices.tryEmit(R.string.compose_forward_attachment_dropped)

        val app = getApplication<Application>()
        return buildForwardedBlocks(
            from = o.from.joinToString { it.display() },
            subject = o.subject.orEmpty(),
            date = MailDates.formatFull(o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc)),
            to = o.to.joinToString { it.display() },
            // The whole original is forwarded, signature included: it is the message being passed on,
            // not a quote of it.
            originalText = originalPlainText(o),
            originalHtml = o.htmlContent()?.takeIf { it.isNotBlank() },
            carriedCids = carriedCids,
            labels = ForwardLabels(
                from = app.getString(R.string.compose_from),
                subject = app.getString(R.string.compose_subject),
                date = app.getString(R.string.compose_forward_date),
                to = app.getString(R.string.compose_to),
            ),
        )
    }

    /**
     * Stage outgoing-attachment bytes the same way for a picked file or a carried forward part: IMAP
     */
    private suspend fun stageOutgoing(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String,
        cid: String?,
    ): EmailBodyPart {
        val app = getApplication<Application>()
        // IMAP has no blob store; and with PGP on, JMAP also stages locally — the attachment travels
        // INSIDE the signed/encrypted entity, so uploading a plaintext blob would leak it for nothing.
        return if (credentials.protocol == MailProtocol.IMAP || _pgpMode.value != PgpMode.OFF) {
            val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = withContext(Dispatchers.IO) {
                File(app.cacheDir, "outgoing").apply { mkdirs() }
                    .let { File(it, "${System.nanoTime()}-$safe") }
                    .apply { writeBytes(bytes) }
            }
            EmailBodyPart(
                partId = file.absolutePath,
                type = type,
                size = bytes.size.toLong(),
                name = name,
                disposition = disposition,
                cid = cid,
            )
        } else {
            repo.uploadAttachment(credentials, bytes, type, name, disposition, cid)
        }
    }

    /**
     * Build the PGP/MIME entity: assemble the inner MIME entity with the same builder normal sends use,
     */
    private suspend fun buildPgpEntity(
        credentials: AccountCredentials,
        allRecipients: List<String>,
        textBody: String,
        htmlBody: String?,
        attachments: List<EmailBodyPart>,
        mode: PgpMode,
        protectedSubject: String?,
        interactionResult: Intent?,
    ): String {
        val app = getApplication<Application>()
        val account = pgpAccount()
            ?: error(app.getString(R.string.compose_pgp_not_configured))
        // NAMED FOR THE ROLE THAT SURVIVES BOTH MODES. This key is the signer AND a recipient
        // (encrypt-to-self, so the Sent copy can be reopened). Calling it "signKeyId" is what makes
        // "drop the signer" read as "drop this key", which would make every unsigned encrypted message
        // unreadable for ever. See [pgpEncryptArgs].
        val selfKeyId = account.pgpSignKeyId

        // Attachment bytes must be local to ride inside the entity. Staged files read back directly; a
        // blob attached BEFORE the user enabled PGP is fetched back once.
        val outAttachments = attachments.map { part ->
            val bytes = when {
                part.partId != null -> withContext(Dispatchers.IO) { File(part.partId!!).readBytes() }
                part.blobId != null -> repo.downloadAttachment(credentials, part, emailId = "")
                else -> error(app.getString(R.string.status_read_file_failed))
            }
            OutgoingAttachment(
                name = part.name ?: "attachment",
                type = part.type ?: "application/octet-stream",
                bytes = bytes,
                cid = part.cid,
                inline = part.disposition.equals("inline", true) && !part.cid.isNullOrBlank(),
            )
        }
        require(outAttachments.sumOf { it.bytes.size.toLong() } <= PGP_MAX_ATTACHMENT_BYTES) {
            app.getString(R.string.compose_pgp_too_large)
        }
        val boundarySeed = java.util.UUID.randomUUID().toString().replace("-", "")
        // The subject goes in TWICE, and only one of them is this one. The message's real `Subject:`
        // is written by `OutgoingMime.build` on the envelope, in the clear — the `subject = ""` below
        val inner = OutgoingMime.buildBodyEntity(
            OutgoingMessage(
                from = "-", to = listOf("-"), subject = "",
                body = textBody, html = htmlBody,
                messageId = "$boundarySeed@pgp", dateMillis = System.currentTimeMillis(),
                attachments = outAttachments,
            ),
            protectedSubject = protectedSubject,
        )
        val payload = PgpMime.signablePayload(inner)
        return when (mode) {
            PgpMode.SIGN -> {
                when (val r = pgp.detachedSign(payload.toByteArray(Charsets.UTF_8), selfKeyId, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapSigned(payload, r.value.armor, r.value.micalg, "----sterna_sig_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED -> {
                val recipientKeys = when (val r = pgp.findKeys(allRecipients, interactionResult)) {
                    is PgpResult.Success -> r.value
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(app.getString(R.string.compose_pgp_missing_keys))
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
                // Who signs and who can read — [pgpEncryptArgs], the pure decision a JVM test runs.
                // selfKeyId is a recipient in BOTH modes; it is the signer only in ENCRYPT. Do not
                // rebuild either array here.
                val args = pgpEncryptArgs(mode, selfKeyId, recipientKeys.toList())
                when (val r = pgp.signAndEncrypt(payload.toByteArray(Charsets.UTF_8), args.signKeyId, args.recipientKeyIds, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapEncrypted(r.value, "----sterna_enc_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.OFF -> error("unreachable")
        }
    }

    private companion object {
        const val TAG = "ComposeViewModel"

        /** v1 cap: the whole entity is signed/encrypted in memory. */
        const val PGP_MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}

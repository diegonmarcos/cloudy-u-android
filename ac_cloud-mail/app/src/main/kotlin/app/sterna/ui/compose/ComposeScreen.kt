package app.sterna.ui.compose

import com.diegonmarcos.superapp.perms.rememberPermsResumes
import com.diegonmarcos.superapp.perms.PermsLink
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LocalContentColor
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.jmap.model.Email
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.InsertPhoto
import androidx.compose.ui.graphics.Color
import app.sterna.core.data.text.TEXT_COLORS
import app.sterna.core.data.text.TEXT_COLOR_OF
import app.sterna.core.data.text.withTextColor
import app.sterna.ui.text.rememberTextToolRunner
import app.sterna.ui.text.TextToolScope
import app.sterna.ui.text.TextToolPanel
import app.sterna.ui.text.TextToolIconRow
import app.sterna.ui.text.TextToolInPlaceBar
import app.sterna.ui.text.TextToolNamedItems
import app.sterna.ui.text.TextToolUndo
import app.sterna.ui.text.TextToolSurface
import app.sterna.ui.text.TextTool
import app.sterna.core.data.text.BLOCK_FORWARD
import app.sterna.core.data.text.BLOCK_SIGNATURE
import app.sterna.core.data.text.extractEditorBlock
import app.sterna.core.data.text.plainTextToHtml
import app.sterna.core.imap.htmlToPlainText
import app.sterna.core.data.text.Span
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.clear as clearFormatting
import app.sterna.core.data.text.linkAt
import app.sterna.core.data.text.remapAfterEdit
import app.sterna.core.data.text.stylesAt
import app.sterna.core.data.text.toggle as toggleFormatting
import app.sterna.core.data.text.toggleBlock
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.container
import app.sterna.contacts.AndroidContacts
import app.sterna.ui.theme.MailMetrics
import app.sterna.util.isValidEmail
import app.sterna.ui.FORCE_ONBOARDING_PREVIEW
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.components.ContactAvatar
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.drawTern
import app.sterna.contacts.ContactSuggestion
import java.time.ZoneId

// ExperimentalLayoutApi: the leave dialog's FlowRow, which wraps its three answers instead of
// truncating them where the labels are long (German, Russian) — same as the settings screens'.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Send the draft this composer is editing to the Trash the way the list does it (#127), through the
     */
    onDeleteDraft: (take: () -> Email?) -> Unit,
    replyTo: String? = null,
    mode: String? = null,
    accountId: String? = null,
    restore: Boolean = false,
    to: String? = null,
    cc: String? = null,
    bcc: String? = null,
    subject: String? = null,
    body: String? = null,
    draftId: String? = null,
    /**
     * The queued row this composer was opened on by Outbox → Edit (#70), from the route — the twin of
     */
    outboxId: Long? = null,
    viewModel: ComposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val replyQuote by viewModel.replyQuote.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val signatureOptions by viewModel.signatureOptions.collectAsStateWithLifecycle()
    val selectedSignature by viewModel.selectedSignature.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpMode by viewModel.pgpMode.collectAsStateWithLifecycle()
    val recipientKeys by viewModel.recipientKeys.collectAsStateWithLifecycle()
    val pgpKeylessRecipients by viewModel.pgpKeylessRecipients.collectAsStateWithLifecycle()
    val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()
    val editingOutbox by viewModel.editingOutbox.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    // The lease on a draft the PHONE is keeping and the server has not got (#95) — non-null only while
    // this composer really holds the row. Not `draftId` and not the route it belongs to: on a row the
    // upload worker consumed between the list being drawn and the tap, both still say "local draft"
    // while this screen holds nothing.
    val editingLocalDraftId by viewModel.editingLocalDraftId.collectAsStateWithLifecycle()
    // Whether deleting that row would LEAVE the server copy it masks behind (#95) — true only when
    // this phone never read that copy whole. The confirmation has to say so before the finger lands:
    // the row goes, the mask lifts, and the pre-edit draft comes back at the top of Drafts.
    val deleteKeepsServerCopy by viewModel.deleteKeepsServerCopy.collectAsStateWithLifecycle()
    // The trash icon's confirmation (#127), unconditional unlike the leave dialog: this one is about
    // removing the draft itself. A flag lives where what it acts on lives, so it is the ViewModel's:
    // a bare `remember` fell back to false on every rotation and took the dialog off screen without a
    // word, while `rememberSaveable` outlives the process, which the draft it names does not.
    val pendingDraftDelete by viewModel.pendingDraftDelete.collectAsStateWithLifecycle()
    // The leave dialog (#35, #127) and the two chained pre-send guards. All three are the
    // ViewModel's for the reason above: a rotation restarts the composition and a bare `remember`
    // fell back to false, so the question left the screen as if it had been answered. Not
    // `rememberSaveable` either; their real lifetime is the composer's NAVIGATION ENTRY.
    val showDiscard by viewModel.pendingDiscard.collectAsStateWithLifecycle()
    val showForgotAttachment by viewModel.pendingForgotAttachment.collectAsStateWithLifecycle()
    val showManyRecipients by viewModel.pendingManyRecipients.collectAsStateWithLifecycle()
    // The "pick date and time" pair (#161): the calendar, then the clock, and the day chosen between
    // the two. All three are the ViewModel's for the reason above — and the DAY especially: a
    // rotation between the two dialogs would lose it and bring the clock back over nothing.
    val pendingScheduleDay by viewModel.pendingScheduleDay.collectAsStateWithLifecycle()
    val pendingScheduleTime by viewModel.pendingScheduleTime.collectAsStateWithLifecycle()
    val scheduleDay by viewModel.scheduleDay.collectAsStateWithLifecycle()
    // Whether a draft saved on the SERVER survives leaving — what the leave dialog may not announce as
    // destroyed (#35). Not `draftId != null`: an undone send reopens with restore=true and no draftId
    // while its draft is still in Drafts.
    val savedDraftBehind by viewModel.savedDraftBehind.collectAsStateWithLifecycle()
    val attachmentsTouched by viewModel.attachmentsTouched.collectAsStateWithLifecycle()
    // Reopening a saved draft: whether its text is still being fetched, and what to say if it never
    // arrived. Two flows and not one state, because ComposeState is a single slot. The decision itself
    // is [draftReopenView], out in ComposeText so a JVM test can execute it, and it is taken further
    // down once `applied` exists.
    val draftLoading by viewModel.draftLoading.collectAsStateWithLifecycle()
    val draftLoadFailed by viewModel.draftLoadFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attach)
    }
    // The body editor: a WebView page whose single contenteditable element IS the body - HTML, end to
    // end. An inline picture is staged as an inline part with its own Content-ID and put into the page
    // at the caret as an <img> tagged with it; the send swaps the preview for `cid:` (see [htmlForWire]).
    val editor = remember { HtmlEditorController() }
    val inlineImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attachInlineImage(it) { cid, preview, name -> editor.image(preview, cid, name) } }
    }
    // OpenKeychain's passphrase/key dialog round-trip during a PGP send.
    val pgpLauncher = rememberPgpInteractionLauncher { data -> viewModel.retryPgpSend(data) }
    LaunchedEffect(state) {
        (state as? ComposeState.PgpInteraction)?.let { pgpLauncher(it.pendingIntent) }
    }
    // Plain-language feedback when the user cycles the lock toggle, so the icon states are not a
    // mystery. Only manual toggles announce; an automatic opportunistic switch stays silent (#35).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.pgpToggleAnnounce.collect { mode ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = context.getString(
                    when (mode) {
                        PgpMode.OFF -> R.string.compose_pgp_snack_off
                        PgpMode.SIGN -> R.string.compose_pgp_snack_sign
                        PgpMode.ENCRYPT -> R.string.compose_pgp_snack_encrypt
                        PgpMode.ENCRYPT_UNSIGNED -> R.string.compose_pgp_snack_encrypt_unsigned
                    },
                ),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Outcomes worth reporting after the screen closes: a toast, because compose navigates away the
    // moment the save succeeds.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { res -> Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }

    // Whether the body ON SCREEN already carries the reply's quoted original.
    // Declared HERE because the effect below reads it and Kotlin does not read ahead.
    var quoteLanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.prepare(
            replyTo, mode, accountId, restore, to, cc, bcc, subject, body, draftId,
            quoteAlreadyOnScreen = quoteLanded,
            outboxId = outboxId,
        )
        // Attach any files shared into the app (ACTION_SEND) — a one-shot handoff we read and clear,
        // so it only lands on this compose screen (#45).
        val app = context.applicationContext as android.app.Application
        app.container.pendingShareUris.takeIf { it.isNotEmpty() }?.let { shared ->
            app.container.pendingShareUris = emptyList()
            shared.forEach { viewModel.attach(it) }
        }
    }

    // A picked file is staged in cacheDir, which is not ours to keep: Clear cache empties it and
    // Android evicts it under storage pressure. Neither closes this screen, so the chip went on being
    // drawn over nothing and the save was what found out. Swept ON_START — the composer coming back to
    // the front is the only moment it can be noticed, and a LaunchedEffect(Unit) would have run once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.dropVanishedAttachments()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Contacts permission priming (offered once, on first compose) ---
    val contactsPrimed by viewModel.contactsPrimed.collectAsStateWithLifecycle()
    val contactSuggestionsOn by viewModel.contactSuggestionsEnabled.collectAsStateWithLifecycle()
    // #1515 READ_CONTACTS is granted in Configs > Perms (libs:perms); the outcome is read when the screen resumes.
    var contactsAsked by rememberSaveable { mutableStateOf(false) }
    val contactsResumes = rememberPermsResumes()
    LaunchedEffect(contactsResumes) {
        if (contactsAsked) { contactsAsked = false; viewModel.setContactSuggestions(AndroidContacts.hasPermission(context)) }
    }
    var showContactsPriming by remember { mutableStateOf(false) }
    var primingHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(contactsPrimed, contactSuggestionsOn) {
        if (primingHandled) return@LaunchedEffect
        // Real gate: suggestions off AND permission not granted AND not yet primed. The preview flag
        // forces the sheet regardless.
        val gateOpen = !contactSuggestionsOn && !AndroidContacts.hasPermission(context) && !contactsPrimed
        if (FORCE_ONBOARDING_PREVIEW || gateOpen) {
            showContactsPriming = true
            primingHandled = true
        }
    }
    if (showContactsPriming) {
        // Both "Not now" and a swipe-dismiss mark it primed, so it is never offered again. The toggle
        // still lives in Settings > Privacy.
        val dismissPriming = {
            showContactsPriming = false
            viewModel.markContactsPrimed()
        }
        ModalBottomSheet(onDismissRequest = dismissPriming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MailMetrics.s24)
                    .padding(bottom = MailMetrics.s32),
                verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
            ) {
                Text(
                    stringResource(R.string.compose_contacts_priming_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.compose_contacts_priming_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8, Alignment.End),
                ) {
                    TextButton(onClick = dismissPriming) {
                        Text(stringResource(R.string.compose_contacts_priming_not_now))
                    }
                    Button(onClick = {
                        showContactsPriming = false
                        viewModel.markContactsPrimed()
                        if (AndroidContacts.hasPermission(context)) {
                            viewModel.setContactSuggestions(true)
                        } else {
                            contactsAsked = true; PermsLink.open(context, "mail", listOf("contacts_read"))
                        }
                    }) {
                        Text(stringResource(R.string.compose_contacts_priming_enable))
                    }
                }
            }
        }
    }

    // The body and its baseline are the two big things on this screen, and the ONLY two that do not
    // travel in the activity's saved-state parcel: `Parcel.writeString` writes UTF-16, so a ~324 kB
    val slotId = rememberSaveable { newComposerSlotId() }
    val resumeSlot = remember(context, slotId) { ComposerResumeSlot(composerResumeFile(context, slotId)) }
    val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot)) {
        ComposerResumeState(TextFieldValue(), "")
    }
    // THE BODY DID NOT COME BACK, AND THE SAVE MUST BE TOLD. Above the inline bound a failed disk
    // write parks nothing, so the saver carries the LOSS instead of the text — everything else comes
    LaunchedEffect(resume.bodyWasLost) { if (resume.bodyWasLost) viewModel.onComposerBodyLost() }

    // "Takes flight": when the message is queued (Done), play a brief lift-off, then navigate back.
    // The send already fired; this is purely cosmetic and must not delay it.
    val motionOn = rememberMotionEnabled()
    var flying by remember { mutableStateOf(false) }
    val fly by animateFloatAsState(
        targetValue = if (flying) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "send-flight",
    )
    LaunchedEffect(state) {
        if (state is ComposeState.Done) {
            if (motionOn) {
                flying = true
                delay(320)
            }
            // Sent, saved as a draft or scheduled: every one lands on Done, and the parked body must
            // go with the screen — see the `closed` flag in [ComposerResumeState] for why a bare
            // clear() here would be written straight back out at teardown.
            resume.close(resumeSlot)
            onDone()
        }
    }

    // A draft the phone was keeping has just been deleted: leave the way the X leaves — a bare
    // popBackStack() onto the Drafts list. NOT through ComposeState.Done, which plays the tern's
    // flight and lands on the inbox. The signal comes from the ViewModel and not from the tap, because
    // the destroy has to be durable and the row consumed first.
    LaunchedEffect(Unit) {
        viewModel.localDraftDeleted.collect {
            resume.close(resumeSlot)
            onCancel()
        }
    }

    // Which field opens focused — decided once, before the prefill parameters are shadowed by the
    // editable state below. See [initialComposeFocus].
    val initialFocus = remember {
        initialComposeFocus(
            isDraft = draftId != null,
            isReply = replyTo != null && composeOpening(mode).quotes,
            linkTo = to,
            linkSubject = subject,
        )
    }
    // How much body the composer was OPENED with — a mailto: link's `body=`, nothing otherwise. Read
    // here because below, the editable state shadows the parameter. It tells the prefilled body's own
    // text from the signature appended under it, so the caret can start after the former (#83).
    val linkBodyLength = remember { if (body.isNullOrBlank()) 0 else body.length }

    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    // Like the body, the subject carries its caret (a TextFieldValue, not a bare String) so a tap
    // before its first character can put the cursor at the very start (#26).
    var subject by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // The body carries its caret with it (a TextFieldValue, not a bare String), so a prefilled
    // compose can open with the cursor where writing continues (#63).
    var body by resume.bodyState
    // The body's inline styling (#131) lives BESIDE the TextFieldValue, not inside it: `body` holds
    // plain text and the spans are laid over it at render time, so the ~70 sites that read
    // `body.text` see exactly what they saw before.
    var ranges by resume.rangesState
    // …and its list blocks (#131), beside the spans and beside the text for the same reason: a
    // block is a run of LINES, and `body.text` is what the field, the save and the send still read.
    var blocks by resume.blocksState
    // …and its links (#131), held the same way and for the same reason: `body` stays plain text,
    // and a link is a span over it that happens to carry an address.
    var links by resume.linksState
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Whether the To field may fold its chips into a "+N" summary. False for everything except a
    // reply-all, where the whole point is that the recipient list is visible before a word is typed.
    var showAllRecipients by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // "Ask for a read receipt" (RFC 8098), per message, from the overflow menu.
    // rememberSaveable, and NOT a ViewModel flow like the padlock beside it: the padlock survives a
    var requestReceipt by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialCc by rememberSaveable { mutableStateOf("") }
    var initialBcc by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by resume.baselineState
    var baselineRanges by resume.baselineRangesState
    var baselineBlocks by resume.baselineBlocksState
    var baselineLinks by resume.baselineLinksState
    // THE MODEL: the body as HTML, and the HTML it was opened with. `body` above is only its plain
    // projection (htmlToPlainText) for the ~70 sites that ask "is there text", "is it the prefill";
    // `ranges`, `blocks` and `links` stay empty - the markup is not described a second time.
    var bodyHtml by resume.htmlState
    var initialHtml by resume.htmlBaselineState
    // The next page report is the baseline (a programmatic load, or a signature swap on a clean body).
    var rebaseOnLoad by remember { mutableStateOf(false) }
    var rebaseOnChange by remember { mutableStateOf(false) }
    // …the box included: a reopened outbox message can OPEN ticked, so "ticked" is not an edit while
    // ticking or clearing it is. Without this baseline, clearing the box then leaving sent the message
    // still asking (see [ComposeDirty]).
    var initialRequestReceipt by rememberSaveable { mutableStateOf(false) }
    // The body and its styling as one value: derived on every recomposition, never stored twice.
    val rich = RichBody(body.text, ranges, blocks, links, html = bodyHtml.ifEmpty { null })
    // The page's markup RIGHT NOW: the page reports edits after a short debounce, so every gesture
    // that commits the body (send, schedule, save) asks the page itself first.
    suspend fun freshRich(): RichBody {
        val html = editor.currentHtml(bodyHtml)
        bodyHtml = html
        return RichBody(htmlToPlainText(html), emptyMap(), html = html)
    }
    // Take [html] as the whole body - a prefill, a reply's quote, a restored composer - into the page
    // and into the plain projection, and (with [asBaseline]) as what "unchanged" means.
    fun adoptHtml(html: String, asBaseline: Boolean) {
        val text = htmlToPlainText(html)
        bodyHtml = html
        body = TextFieldValue(text)
        ranges = emptyMap()
        blocks = emptyList()
        links = emptyList()
        if (asBaseline) {
            initialBody = text
            baselineRanges = emptyMap()
            baselineBlocks = emptyList()
            baselineLinks = emptyList()
            rebaseOnLoad = true
        }
        editor.load(html)
    }

    // Text tools on a DRAFT. Unlike the reader's, this one applies: the body is the user's own
    // and the result belongs back in the field.
    val textTools = rememberTextToolRunner(TextToolSurface.COMPOSE)
    val textToolScope = rememberCoroutineScope()
    // WHAT was sent and from WHERE, captured at the tap. The engines are network calls and the
    // user keeps typing while one is in flight; applying a reply against a range that has since
    // moved would overwrite whatever drifted into it. Same stale guard the keyboard's Enhance
    // makes, for the same reason.
    var textToolTarget by remember { mutableStateOf<EditorScope?>(null) }
    // The draft as it was before the last in-place tool changed it: one step, restored by the Undo icon.
    var textToolUndo by remember { mutableStateOf<TextToolUndo?>(null) }
    fun runTextTool(tool: TextTool) {
        textToolScope.launch {
            if (tool.readsThread) {
                // Answer Prediction reads the THREAD and writes at the caret; the draft is not what is sent.
                val thread = viewModel.answerSource
                if (thread.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.text_tool_no_thread), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                textToolTarget = EditorScope("caret", "")
                textTools.run(textToolScope, tool, thread)
                return@launch
            }
            // The selection, else the owner's own lines above the signature and the quote - asked of
            // the page, which is where the body is.
            val scopeNow = editor.scopeText() ?: return@launch
            textToolTarget = scopeNow
            textTools.run(textToolScope, tool, scopeNow.text)
        }
    }
    // Put a result into the draft as ONE undoable edit. Nothing here sends anything.
    fun applyTextTool(result: String) {
        val target = textToolTarget
        textToolScope.launch {
            val stillThere = target != null && (target.mode == "caret" || editor.scopeText() == target)
            if (!stillThere) {
                Toast.makeText(context, context.getString(R.string.text_tool_stale), Toast.LENGTH_SHORT).show()
            } else {
                textToolUndo = TextToolUndo(body, RichBody.ofHtml(editor.currentHtml(bodyHtml)))
                editor.applyScope(target!!.mode, result)
            }
            textToolTarget = null
        }
    }
    TextToolPanel(textTools) { result -> applyTextTool(result) }
    // Answer Prediction, Enhance and Check Grammar have no dialog: their result lands here, applied as an
    // undoable edit, or their own error is shown verbatim.
    val finished = textTools.outcome
    LaunchedEffect(finished) {
        if (finished != null && finished.tool.appliesInPlace) {
            val text = finished.text
            if (text != null) applyTextTool(text.trim('\n'))
            else Toast.makeText(context, finished.error.orEmpty(), Toast.LENGTH_LONG).show()
            textTools.dismiss()
        }
    }

    // Which compose this is, for the signature rules: both a reply and a forward obey the two settings.
    // Whether the body HOLDS a quote is no longer asked here — the body answers that itself, at its
    // QUOTE_DIVIDER, which stays true as the owner edits it.
    val isReplyOrForward = replyTo != null
    // Changing "From" reads those settings from DataStore, which suspends.
    val scope = rememberCoroutineScope()

    /**
     * Apply a [SignatureChange] to the body (D5, #206): the signature is a BLOCK of the HTML body, so
     * the page swaps it for the new one - or inserts it above the quote, or below when the setting
     * says so - unless the owner edited it, in which case the page leaves it alone.
     *
     * ONE implementation, reached by both the "From" picker and the signature picker. On a body that
     * was untouched the swap is not an edit: the next report from the page becomes the baseline.
     */
    fun applySignatureChange(change: SignatureChange?) {
        if (change == null) return
        rebaseOnChange = !htmlIsDirty(bodyHtml, initialHtml)
        editor.replaceBlock(
            BLOCK_SIGNATURE,
            change.blockHtml,
            belowQuote = (change as? SignatureChange.Insert)?.belowQuote ?: false,
        )
    }

    // Land in the recipient field with the keyboard up, unless the recipients are already filled — a
    // reply, a reopened draft, or a mailto: link — in which case the subject or the body takes the
    // focus. The To field self-focuses via its `autoFocus` flag further down.
    val toFocus = remember { FocusRequester() }
    val subjectFocus = remember { FocusRequester() }

    LaunchedEffect(prefill) {
        prefill?.let {
            if (!applied) {
                // Trailing ", " so prefilled (reply) recipients render as committed chips.
                val prefilledTo = if (it.to.isNotBlank()) it.to.trimEnd(',', ';', ' ') + ", " else ""
                to = prefilledTo
                cc = if (it.cc.isNotBlank()) it.cc.trimEnd(',', ';', ' ') + ", " else ""
                bcc = if (it.bcc.isNotBlank()) it.bcc.trimEnd(',', ';', ' ') + ", " else ""
                if (it.expand) expanded = true
                // A reply-all shows every recipient it is about to answer — see DraftFields.
                showAllRecipients = it.showAllRecipients
                // Caret at the end of the prefilled subject ("Re: …"), which is where an edit
                // continues; it used to sit at offset 0 by accident of the String field.
                subject = TextFieldValue(it.subject, TextRange(it.subject.length))
                // The body IS html: the prefill's own markup (a reopened draft, a reply with its quote and
                // signature), else its text wrapped. The caret opens on the first line, above the
                // quote and the signature, which is where the answer is written.
                adoptHtml(it.bodyHtml ?: plainTextToHtml(it.body), asBaseline = true)
                initialTo = prefilledTo
                initialCc = cc
                initialBcc = bcc
                initialSubject = it.subject
                // This prefill CARRIED the quote — the original was not cached, so `prepare` built the
                // whole body with it inside and there is no out-of-band hand-over to raise the flag.
                // Without this post the flag stays false while the quote is plainly in the body, and
                // after a process death the rebuilt ViewModel draws "Couldn't load…" over it (G6).
                if (it.quoted) quoteLanded = true
                // A reopened message that asked for a read receipt still asks: re-editing it enqueues
                // a NEW row, so a box left clear here cancels the request. Applied inside the same
                // `!applied` gate, so nothing overwrites a box the user has ticked.
                requestReceipt = it.requestReceipt
                initialRequestReceipt = it.requestReceipt
                applied = true
                // The To field self-focuses on first composition; the other two are asked here, once
                // the prefill they open on is in place.
                when (initialFocus) {
                    ComposeFocus.BODY -> editor.focusBody()
                    ComposeFocus.SUBJECT -> runCatching { subjectFocus.requestFocus() }
                    ComposeFocus.RECIPIENTS -> Unit
                }
            }
        }
    }

    // A reply opens with To/Subject prefilled instantly from the cache; the quoted original arrives a
    // moment later. Drop it into the body only while the body is still the untouched initial prefill,
    // and re-baseline so it is not seen as an unsaved edit.
    LaunchedEffect(replyQuote) {
        // THE re-entry guard, load-bearing with the consume below: retiring the quote writes `null`
        // into the flow, which is a NEW value, so this effect is relaunched with `replyQuote == null`.
        // Drop this `?:` and that second pass carries an empty quote into the branch below — the body
        // and its baseline would both be emptied, the reply losing its quote AND its signature.
        val quote = replyQuote ?: return@LaunchedEffect
        val forwardOriginal = if (composeOpening(mode).carriesOriginal) extractEditorBlock(quote, BLOCK_FORWARD) else null
        if (canApplyReplyQuote(applied, body.text, initialBody)) {
            // The whole opening - first line, signature, quote - replaces the plain prefill, and is the
            // new baseline: it is not an edit.
            adoptHtml(quote, asBaseline = true)
            // Dropped in exactly once, AFTER the write: the activity is recreated by a rotation while
            // the ViewModel survives, and an unconsumed quote replays here and puts the caret back at
            // offset 0. The write-then-retire ORDER guards nothing today — no line between the two
            // suspends — but it is pinned so a suspending call added later cannot empty the flow first.
            viewModel.consumeReplyQuote()
            // The body now carries the quote, and this is the ONLY place that may say so: the flag is
            // saved, so after a kill it is what tells the fresh ViewModel to keep quiet. Never
            // posted at the top of this effect nor in the toast branch — it would then mean "the
            // effect ran", and a reply that genuinely has no quote would go out without a word.
            quoteLanded = true
        } else if (applied && forwardOriginal != null) {
            // A forward without its original is not a forward: it goes in at the END of what the owner
            // has begun, as its own block - nothing is dropped, so nothing is announced.
            editor.appendHtml(forwardOriginal)
            quoteLanded = true
            viewModel.consumeReplyQuote()
        } else if (applied) {
            // The user started writing before it arrived, so it is not dropped in over their text. Say
            // so instead of silently sending a reply with no quote (B6).
            viewModel.noticeQuoteNotAdded()
            // Said exactly once, AFTER the notice — otherwise each activity recreation posts the toast
            // again, and they stack up in LENGTH_LONG.
            viewModel.consumeReplyQuote()
        }
        // No consume on the third way out (the quote came back before the header prefill landed):
        // nothing has posted it and nothing has announced it, so retiring it there would lose it.
    }

    // A composer rebuilt by a rotation or a process death reopens the page on the markup it parked;
    // a new one opens on an empty line (the prefill, when it comes, replaces it).
    LaunchedEffect(Unit) { editor.load(bodyHtml) }

    // Which of the three screens a reopened draft gets. Taken HERE because it needs `applied`: once
    // the prefill has landed, the editor is drawn whatever the fetch is doing. `applied` survives
    // process death while the ViewModel does not, so after a kill it is the only thing that knows the
    // text on screen is the reader's — see [draftReopenView].
    val reopen = draftReopenView(draftLoading, draftLoadFailed, applied)

    val sending = state is ComposeState.Sending

    // The Save-as-draft icon greys out while there is nothing worth saving — the same #69 rule the
    // save itself uses, so the button's state matches what tapping it would do. A typed recipient
    // counts, so the icon lights up as soon as an address is entered.
    val canSaveDraft = draftHasContent(to, cc, bcc, subject.text, body.text, attachments.isNotEmpty())

    // Leaving without sending or saving. A message reopened from the Outbox goes back to the queue
    // (#70) — closing its editor is not deleting it — unless this composer LOST its body: then the row
    // is parked instead, because queued it leaves in under a second, when the screen has just said to
    // close and reopen it. The editor's raw text is what decides, hence `body.text`.
    val cancel = {
        viewModel.abandon(body.text)
        resume.close(resumeSlot)
        onCancel()
    }

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits. A message pulled back
    // out of a send (Undo) is guarded even untouched: it lives on this screen only (#70). The verdict
    val dirty = ComposeDirty.isDirty(
        onlyCopy = onlyCopy,
        to = to, initialTo = initialTo,
        cc = cc, initialCc = initialCc,
        bcc = bcc, initialBcc = initialBcc,
        subject = subject.text, initialSubject = initialSubject,
        body = RichBody(body.text, ranges, blocks, links), initialBody = RichBody(initialBody, baselineRanges, baselineBlocks, baselineLinks),
        requestReceipt = requestReceipt, initialRequestReceipt = initialRequestReceipt,
        attachmentsTouched = attachmentsTouched,
        // Formatting alone is an edit too: the text did not change when a word went bold.
        htmlEdited = htmlIsDirty(bodyHtml, initialHtml),
    )
    val attemptClose = { if (dirty && !sending) viewModel.askDiscard() else cancel() }

    // Pre-send guards, chained: "forgot attachment?" then "many recipients?". Both flags are read from
    // the ViewModel, so each gate is opened and closed through it.
    val recipientCount = listOf(to, cc, bcc).sumOf { field -> field.split(',', ';').count { it.isNotBlank() } }
    // Can send only with at least one To recipient and every entered address valid.
    val allRecipients = listOf(to, cc, bcc).flatMap { recipientTokens(it) }
    // When encrypting, every recipient must have a resolvable key (false = known-missing; absent = not
    // yet checked, allowed — the send resolves keys and reports precisely).
    val keysReady = !pgpMode.encrypts || allRecipients.none { recipientKeys[it] == false }
    val canSend = recipientTokens(to).isNotEmpty() && allRecipients.all(::isValidEmail) && keysReady
    val sendNow: () -> Unit = { scope.launch { viewModel.send(to, cc, bcc, subject.text, freshRich(), requestReceipt) } }
    val proceedAfterAttachment = {
        if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()
    }
    val attemptSend = {
        if (attachments.isEmpty() && mentionsAttachment("${subject.text}\n${body.text}")) {
            viewModel.askForgotAttachment()
        } else {
            proceedAfterAttachment()
        }
    }

    if (showForgotAttachment) {
        AlertDialog(
            onDismissRequest = { viewModel.clearForgotAttachment() },
            title = { Text(stringResource(R.string.compose_forgot_attachment_title)) },
            text = { Text(stringResource(R.string.compose_forgot_attachment_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearForgotAttachment()
                    proceedAfterAttachment()
                }) { Text(stringResource(R.string.compose_forgot_attachment_send)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearForgotAttachment() }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    if (showManyRecipients) {
        AlertDialog(
            onDismissRequest = { viewModel.clearManyRecipients() },
            title = { Text(stringResource(R.string.compose_many_recipients_title)) },
            text = { Text(stringResource(R.string.compose_many_recipients_message, recipientCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearManyRecipients()
                    sendNow()
                }) { Text(stringResource(R.string.compose_many_recipients_send)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearManyRecipients() }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    // The menu's fifth entry, in two steps: a calendar, then a clock (#161). Drawn HERE, at the top
    // level with the other dialogs, and NOT inside the toolbar's Box, which sits under the reopen gate
    if (pendingScheduleDay) {
        // In LANDSCAPE the calendar's GRID does not fit: `DatePickerDialog` lays its surface out at a
        // MAX height, never a min, so turned, the ceiling is the SCREEN's 411 dp and Material's content
        val dayConfiguration = LocalConfiguration.current
        val dayAsInput = schedulePickerAsInput(dayConfiguration.screenHeightDp, dayConfiguration.screenWidthDp)
        // Two bounds, one apiece, not interchangeable. `yearRange` decides which years the chevron
        // DRAWS: Material's default of 1900..2100 put a hundred and twenty-six dead years in front of
        // this one. `isSelectableDate` is the lock. No upper bound of ours.
        val dayState = rememberDatePickerState(
            yearRange = scheduleFirstYear(System.currentTimeMillis(), ZoneId.systemDefault())..DatePickerDefaults.YearRange.last,
            initialDisplayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    scheduleDaySelectable(utcTimeMillis, System.currentTimeMillis(), ZoneId.systemDefault())
            },
        )
        // `rememberDatePickerState` SAVES its displayMode: the restored state comes back on the face
        // it was opened with, and `initialDisplayMode` is never consulted again. Without this effect
        // the defect's OWN gesture survives the fix — open in portrait, then turn. Keyed on the shape
        // of the window and nothing else.
        LaunchedEffect(dayAsInput) {
            dayState.displayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker
        }
        // Both faces answer with the SAME two buttons, so their bodies exist once and are handed to
        // whichever shell is drawn. They are `@Composable` slots, not values.
        val dayOk: @Composable () -> Unit = {
            TextButton(
                // Nothing is scheduled here: the day is kept, this dialog closes and the clock opens.
                // Until a day is picked the picker hands back null, so OK stays grey.
                enabled = dayState.selectedDateMillis != null,
                onClick = {
                    viewModel.chooseScheduleDay(dayState.selectedDateMillis ?: 0L)
                    viewModel.clearScheduleDay()
                    viewModel.askScheduleTime()
                },
            ) { Text(stringResource(R.string.settings_ok)) }
        }
        val dayCancel: @Composable () -> Unit = {
            TextButton(onClick = { viewModel.clearScheduleDay() }) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
        if (dayAsInput) {
            SchedulePickerDialog(
                onDismissRequest = { viewModel.clearScheduleDay() },
                picker = {
                    // The calendar is composed here ONLY on the typed face, and this guard is not the
                    // same test as the branch above: `rememberDatePickerState` restores the face it was
                    if (dayState.displayMode == DisplayMode.Input) {
                        DatePicker(state = dayState, showModeToggle = false)
                    }
                },
                buttons = { dayCancel(); dayOk() },
            )
        } else {
            DatePickerDialog(
                onDismissRequest = { viewModel.clearScheduleDay() },
                // `usePlatformDefaultWidth = false` is not an addition of ours: it is
                // `DatePickerDialog`'s OWN default being put back, since handing it a `DialogProperties`
                // replaces the whole object. `decorFitsSystemWindows = false` and `safeDrawingPadding()`
                // stay on this branch too: Material's own pencil is up here.
                modifier = Modifier.safeDrawingPadding(),
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                confirmButton = dayOk,
                dismissButton = dayCancel,
            ) {
                // Material's own face toggle stays exactly as Material puts it on THIS branch: the
                // grid fits here. It is the shell above that hides it, because there the pencil leads
                // into a crash.
                DatePicker(state = dayState, showModeToggle = true)
            }
        }
    }

    if (pendingScheduleTime) {
        val zone = ZoneId.systemDefault()
        // 0L is 1970 — a day that is never STRICTLY ahead, so a clock that somehow opened with no day
        // behind it can only refuse.
        val day = scheduleDay ?: 0L
        // NOT Material's default of midnight: for TODAY that is an hour long gone, so the dial would
        // open on precisely the state the OK button refuses, greyed out with no wording to explain it.
        val opensAt = scheduleClockOpensAt(day, System.currentTimeMillis(), zone)
        // The device's own 12/24-hour setting, not a forced 24 h: this dial is read by whoever set
        // that preference.
        val timeState = rememberTimePickerState(
            initialHour = opensAt.hour,
            initialMinute = opensAt.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        // In LANDSCAPE Material lays its own dial out BESIDE the time display, wider than the dialog.
        // Measured on `emu` at 914×411 dp: the right half of the dial was off screen, its digits drew
        // over the AM/PM selector, and `PM` was unreachable. Scrolling is not the answer: the cut is
        // HORIZONTAL. So the keyboard entry, on Material's own threshold.
        val configuration = LocalConfiguration.current
        val clockAsInput = schedulePickerAsInput(configuration.screenHeightDp, configuration.screenWidthDp)
        // [SchedulePickerDialog], a shell of ours, and NOT Material's `AlertDialog`: what makes the
        // difference is the SOFTWARE KEYBOARD, and Material puts the focus in `TimeInput`'s hour field
        // so the keys come up on their own. The reading that bought that shell is written over it.
        SchedulePickerDialog(
            onDismissRequest = { viewModel.clearScheduleTime() },
            picker = {
                if (clockAsInput) TimeInput(state = timeState) else TimePicker(state = timeState)
            },
            buttons = {
                TextButton(onClick = { viewModel.clearScheduleTime() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
                TextButton(
                    // Lock 2, the one the calendar cannot give: "today, at an hour already gone". Read
                    // from the picker's own state, so the button lights and unlights while the dial
                    // turns.
                    enabled = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis()) != null,
                    onClick = {
                        // Asked AGAIN, with a fresh clock. A button lit at composition and tapped
                        // after the minute turned would otherwise schedule into the past, which is to
                        // say send at once, irreversibly.
                        val millis = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis())
                        // The dialog closes only on the way that SCHEDULES something: closing on a
                        // refusal would take the clock off screen having scheduled nothing and said
                        // nothing. And only correcting it greys the button — `enabled` reads the wall
                        // clock, which is not snapshot state — a window at most the tail of one minute.
                        if (millis != null) {
                            viewModel.clearScheduleTime()
                            // The confirmation belongs to the schedule, not to the tap: the view
                            // model answers `false` on every refusal it owns. Announced regardless,
                            // "Scheduled — …" is a sentence about a message that was not scheduled.
                            scope.launch {
                                if (viewModel.scheduleSend(to, cc, bcc, subject.text, freshRich(), millis, requestReceipt)) {
                                    // The INSTANT, formatted as the Scheduled list formats it — never a
                                    // preset label, a sentence about another time.
                                    val shown = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.compose_scheduled_toast, shown),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.settings_ok)) }
            },
        )
    }

    // The day is dropped once the CLOCK is closed, whichever way out was taken, so the next opening
    // starts clean. Keyed on the flag rather than written into those ways out: a ROTATION is none of
    // them, and the day has to survive it.
    LaunchedEffect(pendingScheduleTime) { if (!pendingScheduleTime) viewModel.forgetScheduleDay() }

    BackHandler(enabled = !showDiscard) { attemptClose() }

    if (pendingDraftDelete) {
        // Same shape as the Outbox's own delete confirmation — but NOT its words: that message has
        // never been sent and exists nowhere else, while this draft goes to the Trash exactly as it
        // would from the list.
        AlertDialog(
            onDismissRequest = { viewModel.clearDraftDelete() },
            title = { Text(stringResource(R.string.compose_delete_draft_title)) },
            // Material's text slot is a height-bounded box with no scrolling of its own: a bare Text in
            // it is CUT, without an ellipsis and without anything saying so. At font_scale 2.0 in
            // German the body stopped at "…getippt hast, wird", losing the sentence that says what was
            // typed since the composer opened is not saved (#127).
            text = {
                Text(
                    // One title, two BODIES, because the object is not the same one (#95): a draft the
                    // phone is keeping has never reached a server, so there is no Trash for it and no
                    listOfNotNull(
                        stringResource(
                            if (editingLocalDraftId != null) {
                                R.string.compose_delete_draft_body_local
                            } else {
                                R.string.compose_delete_draft_body
                            },
                        ),
                        if (deleteKeepsServerCopy) {
                            stringResource(R.string.compose_emptied_draft_server_copy_kept)
                        } else {
                            null
                        },
                    ).joinToString("\n\n"),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearDraftDelete()
                    if (editingLocalDraftId != null) {
                        // A draft the phone is keeping is deleted BY THE COMPOSER: there is no server
                        // row for the list's move-to-Trash to act on, and the gesture is the emptying
                        // one (#95 × #69) reached by a button. The screen closes on
                        // viewModel.localDraftDeleted, with a bare pop — never on ComposeState.Done.
                        viewModel.deleteEditingLocalDraft()
                    } else {
                        // The message is taken INSIDE the navigation guard: a tap the guard refuses must
                        // not have consumed the draft on its way to being ignored.
                        onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }
                    }
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearDraftDelete() }) {
                    Text(stringResource(R.string.inbox_cancel))
                }
            },
        )
    }

    if (showDiscard) {
        // The leave dialog obeys the same TWO rules as the toolbar's Save action (#35): an encrypted
        // message may not leave a plaintext copy on the server, so the dialog says WHY instead of
        val mayKeepDraft = draftSaveAllowed(pgpMode)
        // What the dialog is allowed to CLAIM, decided by [discardWording] (#70): a message pulled back
        // out of the Outbox is not destroyed by leaving, so it may not be announced as "Discard
        // message?" — nor promised delivery, since a failed send returns to FAILED.
        val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft = savedDraftBehind)
        // "Discard changes" wherever a copy of the message survives leaving — the outbox row, and the
        // draft still in Drafts. The DRAFT variant used to say "Discard" beside a title and a body that
        // both spoke of changes only.
        val discardLabel = stringResource(
            if (wording.keepsMessage) {
                R.string.compose_discard_changes
            } else {
                R.string.compose_discard_discard
            },
        )
        AlertDialog(
            onDismissRequest = { viewModel.clearDiscard() },
            title = {
                Text(
                    stringResource(
                        when {
                            wording.fromOutbox -> R.string.compose_discard_title_outbox
                            // A draft already saved on the server is not destroyed by leaving: cancel()
                            // → abandon() is a no-op outside the outbox. "Discard message?" was
                            // literally false, while the body beside it said "changes" (#35, #127).
                            wording == DiscardWording.DRAFT ||
                                wording == DiscardWording.ENCRYPTED_DRAFT ->
                                R.string.compose_discard_title_changes
                            else -> R.string.compose_discard_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        when (wording) {
                            // Two outbox wordings, because the body has to be true of BOTH buttons
                            // beside it: "Save draft" takes the message OUT of the outbox (#70).
                            DiscardWording.OUTBOX -> R.string.compose_discard_message_outbox
                            DiscardWording.OUTBOX_ENCRYPTED ->
                                R.string.compose_discard_message_outbox_encrypted
                            DiscardWording.ENCRYPTED -> R.string.compose_discard_message_encrypted
                            // Encrypting a draft already on the server: it still owes the user the
                            // reason the Save button is gone, but may not end on "leaving discards the
                            // message" — the copy in Drafts survives (#35).
                            DiscardWording.ENCRYPTED_DRAFT ->
                                R.string.compose_discard_message_encrypted_draft
                            // "You haven't saved your changes" is true of both, and of both
                            // buttons beside it; only the title has to tell them apart.
                            DiscardWording.DRAFT,
                            DiscardWording.PLAIN,
                            -> R.string.compose_discard_message
                        },
                    ),
                )
            },
            // AlertDialog has two button slots and this exit needs three answers, so all of them go in
            // the confirm slot as a FlowRow: wrapped where the labels do not fit, never truncated. Same
            confirmButton = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8, Alignment.End),
                ) {
                    discardChoices(mayKeepDraft, canSaveDraft).forEach { (choice, enabled) ->
                        when (choice) {
                            // Back to the composer, text intact, still encrypted if it was. This is
                            // what tapping outside the dialog has always done; it now has a button.
                            DiscardChoice.CANCEL -> TextButton(onClick = { viewModel.clearDiscard() }, enabled = enabled) {
                                Text(stringResource(R.string.compose_discard_cancel))
                            }
                            DiscardChoice.DISCARD -> TextButton(onClick = {
                                viewModel.clearDiscard()
                                // Discards the edits, not the queued message: that one goes back
                                // (#70), and a saved draft stays in Drafts (#35, #127).
                                cancel()
                            }, enabled = enabled) { Text(discardLabel, color = MaterialTheme.colorScheme.error) }
                            DiscardChoice.SAVE_DRAFT -> TextButton(onClick = {
                                viewModel.clearDiscard()
                                scope.launch { viewModel.saveDraft(to, cc, bcc, subject.text, freshRich(), requestReceipt) }
                            }, enabled = enabled) { Text(stringResource(R.string.compose_discard_save)) }
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = {
            // Above the keyboard. What shows here is the security verdict of the lock toggle, and the
            // keyboard is up at exactly the moment it is tapped, so left at the window's bottom edge
            // the warning appeared UNDER the keyboard and was never seen (#35). Same inset the writing
            // area uses: whichever of the keyboard or the navigation bar is taller, never both.
            SnackbarHost(
                snackbarHostState,
                Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        },
        // Don't reserve a bottom nav-bar inset here: the body already pads with max(ime, nav bar), and
        // consuming it twice left a nav-bar-tall composer-coloured strip between the keyboard and the
        // body text (#26).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // The rule itself is [composeTitle], out of here so it can be tested (#96);
                    // this only turns its answer into words.
                    Text(
                        stringResource(
                            when (composeTitle(draftId, mode, replyTo, restore, editingOutbox)) {
                                ComposeTitle.DRAFT -> R.string.draft_label
                                ComposeTitle.FORWARD -> R.string.message_forward
                                ComposeTitle.REPLY -> R.string.compose_title_reply
                                ComposeTitle.OUTBOX_EDIT -> R.string.outbox_edit
                                ComposeTitle.NEW -> R.string.compose_title_new
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = attemptClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_discard))
                    }
                },
                actions = {
                  // Not one button while a reopened draft is still being read, or once it turned out it
                  // cannot be. Whole block, not each button disabled one by one: every one acts on
                  // fields that hold nothing. The X in navigationIcon deliberately stays.
                  if (reopen is DraftReopenView.Editor) {
                    // OpenPGP lock. SHORT tap keeps the three-stop cycle ([nextPgpMode]); LONG press
                    // opens the list of all four modes, the only way to reach ENCRYPT_UNSIGNED — a
                    // short tap must not walk anyone into sending unsigned mail. The gesture is
                    // announced in the button's own contentDescription.
                    if (pgpAvailable) {
                        var pgpMenu by remember { mutableStateOf(false) }
                        // Box, not IconButton: an IconButton has no long press. The 48 dp size and the
                        // circular clip are what it was giving for free. `enabled = !sending` sits on
                        // the clickable, so NEITHER gesture acts while a send is in flight.
                        Box {
                            Icon(
                                imageVector = pgpModeIcon(pgpMode),
                                contentDescription = stringResource(
                                    R.string.compose_pgp_toggle_desc,
                                    stringResource(pgpModeLabel(pgpMode)),
                                ),
                                // The alpha is what IconButton gave for free. Neither gesture acts
                                // while a send is in flight, and a padlock at full strength would say
                                // the opposite (WYSIWYG).
                                tint = (
                                    if (pgpMode == PgpMode.OFF) {
                                        LocalContentColor.current
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                    ).copy(alpha = if (sending) 0.38f else 1f),
                                modifier = Modifier
                                    .size(MailMetrics.s48)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = !sending,
                                        role = Role.Button,
                                        onClick = viewModel::cyclePgpMode,
                                        onLongClick = { pgpMenu = true },
                                    )
                                    .padding(MailMetrics.s12),
                            )
                            DropdownMenu(expanded = pgpMenu, onDismissRequest = { pgpMenu = false }) {
                                PgpMode.entries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(pgpModeLabel(entry))) },
                                        leadingIcon = {
                                            Icon(pgpModeIcon(entry), contentDescription = null)
                                        },
                                        // The current mode is ticked; the others carry nothing, so
                                        // what is on screen says what is in force (WYSIWYG).
                                        trailingIcon = {
                                            if (entry == pgpMode) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            pgpMenu = false
                                            // Same ViewModel path the cycle takes, so a mode picked
                                            // here is user-set (never auto-downgraded) and says so
                                            // in the same snackbar.
                                            viewModel.setPgpMode(entry)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Attachments are allowed in every mode (they ride inside the encrypted entity).
                    IconButton(onClick = { picker.launch("*/*") }, enabled = !sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                    }
                    IconButton(
                        onClick = attemptSend,
                        enabled = !sending && canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.compose_send))
                    }
                    // FOUR SLOTS AT MOST, AND THE TITLE GETS THE REST (#164). This bar carried up to
                    // EIGHT 48 dp slots and its title was clipped mid-word on a narrow screen. Save,
                    Box {
                        var moreMenu by remember { mutableStateOf(false) }
                        var scheduleMenu by remember { mutableStateOf(false) }
                        // The instant the preset list is DRAWN for, stamped when the PRESETS open — NOT
                        // at the ⋮, or the time spent reading the menu is added to the presets' expiry.
                        // State, not a call in the composition: the wall clock is not snapshot state,
                        // and re-stamping is what redraws the menu after a refused tap.
                        var menuOpenedAt by remember { mutableLongStateOf(0L) }
                        IconButton(onClick = { moreMenu = true }, enabled = !sending) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                        }
                        DropdownMenu(
                            expanded = moreMenu,
                            onDismissRequest = { moreMenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            // Encrypting can't carry plaintext to the server: no draft, no schedule.
                            // Same rule, same function as the leave dialog above (#35).
                            if (draftSaveAllowed(pgpMode)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_save_draft)) },
                                    onClick = { moreMenu = false; scope.launch { viewModel.saveDraft(to, cc, bcc, subject.text, freshRich(), requestReceipt) } },
                                    enabled = !sending && canSaveDraft,
                                )
                                // A scheduled send is fired by a headless worker that can't sign, and
                                // its table carries no attachments: disabled for a SIGN/ENCRYPT message
                                // or one with attachments (A2/A3). The tap closes this menu and opens
                                // the presets beside it — no submenu nested inside an overflow.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_schedule_send)) },
                                    onClick = { moreMenu = false; menuOpenedAt = System.currentTimeMillis(); scheduleMenu = true },
                                    enabled = !sending && canSend && scheduleSendAllowed(pgpMode, attachments.isNotEmpty()),
                                )
                            }
                            // Deleting the draft (#127). OUTSIDE the draftSaveAllowed block: a delete
                            // persists nothing, so it has no reason to disappear when the padlock
                            // closes. When it is offered is [draftDeleteOffered].
                            if (draftDeleteOffered(restore, draftId, editingDraft != null, editingLocalDraftId != null)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.compose_delete_draft)) },
                                    // Asks first (#127): everything typed since this composer opened is
                                    // unsaved, and on a server draft the delete takes the SERVER copy —
                                    // so the Undo restores the draft as it was there, never the text on
                                    // screen. On a draft the phone is keeping there is no Undo at all.
                                    onClick = { moreMenu = false; viewModel.askDraftDelete() },
                                    enabled = !sending,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_request_receipt)) },
                                trailingIcon = { Checkbox(checked = requestReceipt, onCheckedChange = null) },
                                onClick = { moreMenu = false; requestReceipt = !requestReceipt },
                            )
                            // The in-place tools as NAMED items, so the overflow spells out what the toolbar icons do.
                            TextToolNamedItems(textTools.surface, enabled = !sending && textTools.busy == null) { tool ->
                                moreMenu = false
                                runTextTool(tool)
                            }
                            // The Text tools this surface offers, drawn from TextToolSurface.COMPOSE
                            // rather than listed here. Enhance belongs on THIS side and only this
                            // side: the text is the user's own, they can still change it, and the
                            // rewrite has somewhere to land. AI Resume is not here for the mirror
                            // reason — summarising a draft you are still writing answers a question
                            // nobody asked. Closed while a send is in flight, which is a separate
                            // question from membership: see TextToolIconRow.
                            //
                            // One ICON ROW, the same composable the reader draws (#308). It was a
                            // stack of full-width entries here while the reader had had the row
                            // since #293, which is the asymmetry the owner kept reporting as
                            // missing: the arrangement was asked for on both surfaces and landed on
                            // one. No `skip` — every COMPOSE tool applies to any draft, including an
                            // empty one, because Enhance and Translate on a blank body report their
                            // own reason (see TextEnhancer) rather than needing to be hidden, and
                            // the reader's Resume veto exists because a message with no body has
                            // nothing to summarise, which is a fact about received mail.
                            TextToolIconRow(textTools.surface, enabled = !sending, skip = { it.appliesInPlace }) { tool ->
                                moreMenu = false
                                runTextTool(tool)
                            }
                        }
                        // The presets, anchored to this same Box as the overflow: the two menus are
                        // siblings, never one inside the other.
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, shape = MaterialTheme.shapes.medium) {
                            // All three fields travel to the tap: the preset says WHICH KIND of promise
                            // the entry made, and `drawnAt` is the instant it was drawn for — the answer
                            // itself for the three ABSOLUTE entries.
                            schedulePresets(context, menuOpenedAt).forEach { (preset, label, drawnAt) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        // Decided AGAIN at the tap, against a fresh clock: the menu can
                                        // stand open for minutes, and an entry drawn at 5:55 PM still
                                        val sendAt = presetMillisAtTap(preset, drawnAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                                        // Closing, scheduling and confirming happen together or not at
                                        // all. On the refused path the menu STAYS UP and is redrawn from
                                        // a new stamp: the lapsed entry vanishes under the finger, and
                                        // that is the message.
                                        if (sendAt != null) {
                                            scheduleMenu = false
                                            // Scheduled AND confirmed, or neither: the view model
                                            // answers `false` on the refusals it owns, and the banner
                                            // below is a sentence about a message that would not have
                                            // been scheduled at all.
                                            scope.launch {
                                                if (viewModel.scheduleSend(to, cc, bcc, subject.text, freshRich(), sendAt, requestReceipt)) {
                                                    // The LABEL, true of what was just scheduled: "In 1
                                                    // hour" is an hour from this tap, and "6 PM" is the 6 PM
                                                    // the entry was drawn for.
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.compose_scheduled_toast, label),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        } else {
                                            menuOpenedAt = System.currentTimeMillis()
                                        }
                                    },
                                )
                            }
                            // Last, under the presets, as Gmail does it: choose the instant yourself
                            // (#161). Nothing is decided here — the two dialogs hold the refusal.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schedule_pick_date_time)) },
                                onClick = {
                                    scheduleMenu = false
                                    viewModel.askScheduleDay()
                                },
                            )
                        }
                    }
                  }
                },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        // The editor is drawn or it is not. Every rememberSaveable above (`applied` first of all),
        // and both LaunchedEffects that apply the prefill, stay OUTSIDE this `when`: the success path
        // must land exactly as it does today. Only the drawing moves.
        when (reopen) {
          // Still reading the draft. No sentence: a spinner already says "wait", and a line of text
          // under it would be read as a verdict on a fetch that has not answered.
          DraftReopenView.Waiting -> LoadingRing(Modifier.align(Alignment.Center))
          // Read and failed. One sentence and the X, nothing else: there is no draft to edit, and an
          // editor here would invite a save that adds a second draft beside the first.
          is DraftReopenView.DeadEnd -> Text(
              text = stringResource(reopen.notice),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              // No maxLines: German and Russian run to three or four lines here, and clipping would
              // leave the only explanation on the screen half-said.
              modifier = Modifier.align(Alignment.Center).padding(horizontal = MailMetrics.s24),
          )
          DraftReopenView.Editor ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The header stays put and the body fills the space above the keyboard; the body owns
                // its own scroll, so writing a long message keeps the cursor in view (#26). Pad the
                // bottom by whichever is taller — the keyboard or the nav bar — with no double inset.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .graphicsLayer {
                    translationY = -fly * MailMetrics.s64.toPx()
                    alpha = 1f - fly
                },
        ) {
          // The header sits on a faint tint so the writing area below reads as a distinct zone; its
          // dividers run full width.
          Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))) {
            // From — switch sending account / identity (only when there's a choice).
            if (fromOptions.size > 1) {
                var fromMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fromMenu = true }
                            .padding(horizontal = MailMetrics.s16),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(stringResource(R.string.compose_from))
                        Text(
                            text = selectedFrom?.identity?.display() ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // An IconButton (not a bare Icon) so this chevron lines up with the To field's
                        // expand chevron, which is also an IconButton.
                        IconButton(onClick = { fromMenu = true }) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.compose_choose_sender))
                        }
                    }
                    DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }, shape = MaterialTheme.shapes.medium) {
                        fromOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.identity.display()) },
                                onClick = {
                                    // Changing "From" makes the signature follow the identity (D5): the
                                    // block is swapped while still there verbatim, an edited or deleted
                                    scope.launch {
                                        applySignatureChange(viewModel.selectFrom(option, isReplyOrForward))
                                    }
                                    fromMenu = false
                                },
                            )
                        }
                    }
                }
                FieldDivider()
            }

            // Signature — switch which of the identity's signatures this message carries (#206). Shown
            // on the same terms as the "From" row above: only when there is more than one to choose
            // between, because a picker with a single entry asks the owner a question with one answer.
            if (signatureOptions.size > 1) {
                var signatureMenu by remember { mutableStateOf(false) }
                // What the body is CURRENTLY carrying: the picker's choice, or — before it has been
                // touched — the identity's default, which is what the prefill inserted.
                val showing = signatureOptions.firstOrNull { it.id == selectedSignature?.id }
                    ?: selectedFrom?.identity?.defaultSignature()
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { signatureMenu = true }
                            .padding(horizontal = MailMetrics.s16),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(stringResource(R.string.compose_signature))
                        Text(
                            text = showing?.let { signatureMenuLabel(it) } ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { signatureMenu = true }) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.compose_choose_signature),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = signatureMenu,
                        onDismissRequest = { signatureMenu = false },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        signatureOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(signatureMenuLabel(option)) },
                                onClick = {
                                    // Reaches the body through the SAME rewrite the "From" picker uses,
                                    // so an edited block is left alone here exactly as it is there.
                                    scope.launch {
                                        applySignatureChange(viewModel.selectSignature(option))
                                    }
                                    signatureMenu = false
                                },
                            )
                        }
                    }
                }
                FieldDivider()
            }

            // Keep per-recipient key availability current while encrypting.
            LaunchedEffect(pgpMode, to, cc, bcc) {
                viewModel.updateRecipientKeys(to, cc, bcc)
            }
            val missingKeyFor: (String) -> Boolean = { addr ->
                pgpMode.encrypts && recipientKeys[addr] == false
            }
            RecipientChipsField(
                label = stringResource(R.string.compose_to),
                value = to,
                onValueChange = { to = it },
                suggestions = suggestions,
                onSuggest = viewModel::suggest,
                onClearSuggestions = viewModel::clearSuggestions,
                focusRequester = toFocus,
                // Reply-all: never summarise the recipients. See DraftFields.showAllRecipients.
                neverCollapse = showAllRecipients,
                // Whenever the recipients still have to be typed: a fresh mail and a forward. A reply
                // and a reopened draft already have them and focus the body (#63); a mailto: link
                // focuses the first field it left empty (#83).
                autoFocus = initialFocus == ComposeFocus.RECIPIENTS,
                missingKey = missingKeyFor,
                trailing = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc,
                            ),
                        )
                    }
                },
            )
            if (expanded) {
                RecipientChipsField(
                    stringResource(R.string.compose_cc), cc, { cc = it }, suggestions,
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
                )
                RecipientChipsField(
                    stringResource(R.string.compose_bcc), bcc, { bcc = it }, suggestions,
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
                )
            }
            // Subject has no fixed label: the localized string is the in-field placeholder, so the
            // subject starts further left than the labelled recipient rows (K-9 style).
            ComposeField(
                subject,
                { subject = it },
                placeholder = stringResource(R.string.compose_subject),
                focusRequester = subjectFocus,
            )
            TextToolInPlaceBar(
                textTools.surface,
                enabled = !sending,
                running = textTools.busy,
                canUndo = textToolUndo != null,
                onUndo = {
                    // The markup the tool found, put back whole.
                    textToolUndo?.rich?.html?.let { adoptHtml(it, asBaseline = false) }
                    textToolUndo = null
                },
                onPick = ::runTextTool,
            )
            // Only when the body is actually being encrypted to someone, so a fresh encrypt-by-default
            // compose with no recipients doesn't claim it yet (#35).
            if (pgpMode.encrypts && recipientKeys.values.any { it }) {
                Text(
                    stringResource(R.string.compose_pgp_subject_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                )
            }
            // Encrypt-by-default couldn't encrypt because these recipients have no key: say so and name
            // them (#35).
            if (pgpKeylessRecipients.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.compose_pgp_not_encrypted_no_key,
                        pgpKeylessRecipients.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                )
            }

            if (attachments.isNotEmpty()) {
                // Same as the recipient chips: cap the attachment list and scroll it so many files
                // don't grow the header without limit (#26).
                Column(
                    Modifier
                        .heightIn(max = MailMetrics.s132)
                        .verticalScroll(rememberScrollState()),
                ) {
                    attachments.forEach { att ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s6),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = MailMetrics.s8))
                            Text(
                                text = att.name ?: stringResource(R.string.compose_attachment_fallback),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.removeAttachment(att) }) {
                                Text(stringResource(R.string.compose_remove))
                            }
                        }
                    }
                }
            }
            attachmentStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16),
                )
            }
            // Above the body so they stay visible while it fills the rest of the screen.
            if (sending) LoadingRing(Modifier.padding(horizontal = MailMetrics.s16))
            (state as? ComposeState.Error)?.let {
                Text(
                    text = stringResource(
                        if (it.whileSaving) R.string.compose_could_not_save else R.string.compose_could_not_send,
                        it.message,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16),
                )
            }
          }

            // THE BODY: one editor page whose markup is the message. The formatting bar sits under it
            // while it has the focus (bold, italic, underline, strike, headings, lists, quote, link,
            // colour, alignment, picture, clear), and "Source" swaps the page for the raw HTML.
            // `weight(1f)` so the page fills the space under the header and scrolls its own content (#26).
            HtmlBodyPane(
                controller = editor,
                bodyHtml = bodyHtml,
                placeholder = stringResource(R.string.compose_body_placeholder),
                onChange = { html ->
                    bodyHtml = html
                    val text = htmlToPlainText(html)
                    if (text != body.text) body = TextFieldValue(text)
                    if (rebaseOnChange) {
                        initialHtml = html
                        initialBody = text
                        rebaseOnChange = false
                    }
                },
                onLoaded = { html ->
                    bodyHtml = html
                    val text = htmlToPlainText(html)
                    if (text != body.text) body = TextFieldValue(text)
                    if (rebaseOnLoad) {
                        initialHtml = html
                        initialBody = text
                        rebaseOnLoad = false
                    }
                },
                onSourceEdit = { html ->
                    bodyHtml = html
                    val text = htmlToPlainText(html)
                    if (text != body.text) body = TextFieldValue(text)
                },
                onInlineImage = { inlineImagePicker.launch("image/*") },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        }
        // The tern that carries the message off-screen, top-right.
        if (flying) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(MailMetrics.s44)
                    .graphicsLayer {
                        translationX = fly * size.width * 3f
                        translationY = -fly * size.height * 6f
                        alpha = 1f - fly
                        rotationZ = fly * 16f
                    },
            ) { drawTern(spread = 1f, flap = 0.5f, color = ink) }
        }
      }
    }
}

/**
 * The dialog BOTH "pick date and time" pickers wear on their typed face (#161), where the software
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchedulePickerDialog(
    onDismissRequest: () -> Unit,
    picker: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(MailMetrics.s24)) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .align(Alignment.CenterHorizontally)
                        .verticalScroll(rememberScrollState()),
                ) {
                    picker()
                }
                // A FLOW row, not a plain one, because Material's `AlertDialogFlowRow` — which this
                // shell replaces — wraps its actions onto a second line. At 360 dp with the font
                FlowRow(
                    modifier = Modifier.align(Alignment.End).padding(top = MailMetrics.s24),
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                    verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                ) {
                    buttons()
                }
            }
        }
    }
}

/**
 * A frameless, full-width input with an in-field placeholder (no fixed leading label) and an
 */
@Composable
private fun ComposeField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Tapping anywhere on the row (not just the thin input) focuses the field, so the whole line is
    // the tap target (#26).
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    // Before the first character the caret goes to the very start; anywhere else the
                    // field keeps the caret it had (#26).
                    geometry.caretFor(value.text.length)?.let { caret ->
                        onValueChange(value.copy(selection = TextRange(caret)))
                    }
                    focus.requestFocus()
                }
                // At least a 48dp tap target even when the field is empty (accessibility).
                .heightIn(min = MailMetrics.s48)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = MailMetrics.s16),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // The subject is a sentence, so the keyboard arms its first capital (#184).
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .headerTapText(geometry)
                    .weight(1f)
                    .padding(vertical = MailMetrics.s10)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            trailing?.invoke()
        }
        FieldDivider()
    }
}

/** Above this many recipients (To + Cc + Bcc), sending asks for confirmation. */
private const val MANY_RECIPIENTS = 5

/**
 * A recipient line showing committed addresses as chips (invalid ones in the error colour) plus an
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientChipsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactSuggestion>,
    onSuggest: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** Open and focus the field on first composition (the To field on a fresh compose). */
    autoFocus: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** When encrypting, flags a recipient with no available public key. */
    missingKey: (String) -> Boolean = { false },
    /**
     * Never fold the chips into a "+N" summary, however many there are.
     *
     * Set by a reply-all. The summary is a space-saving default and a perfectly good one on a
     * message the user addressed themselves; on a reply-all it hides exactly the fact the user has
     * to see, which is how many people the answer is about to reach.
     */
    neverCollapse: Boolean = false,
) {
    val (chips, input) = splitRecipients(value)

    fun rebuild(newChips: List<String>, newInput: String) = joinRecipients(newChips, newInput)

    // Expanded shows the editable chip area with the input; collapsed shows chips only (or a one-line
    // "+N" summary), so tapping another field leaves no unused space. Start expanded only for a fresh
    // empty field so it can auto-focus (#26).
    var expanded by remember { mutableStateOf(false) }
    // Guards against the input's initial onFocusChanged(false) collapsing the field before the focus
    // request lands: only a loss AFTER a real gain collapses it.
    var wasFocused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    // The inline input carries its caret, so a tap before its first character can put the cursor at the
    // start (#26). The parent string stays the single source of truth: as soon as the derived [input]
    // differs, the field is rebuilt around it with the caret at the end.
    var inputState by remember { mutableStateOf(TextFieldValue()) }
    // The field's on-screen width, so the suggestion menu can float directly under it at the same
    // width, instead of pushing the subject and body down as an inline list did.
    var fieldWidthPx by remember { mutableStateOf(0) }
    // …and its bottom edge inside the compose root, plus the root's own height, so the menu can be
    // capped at the room left below the field once the keyboard is up (#143). On API 30+ the window is
    var fieldBottomPx by remember { mutableStateOf(0) }
    var windowHeightPx by remember { mutableStateOf(0) }
    val inputValue = if (inputState.text == input) {
        inputState
    } else {
        TextFieldValue(input, TextRange(input.length))
    }
    // Enter validates the address being typed, exactly as a comma does, and the field keeps the focus
    // so the next recipient can follow (#83). An empty input is left alone: Enter only ever commits
    // what is in front of it.
    fun commitInput() {
        val token = inputValue.text.trim()
        if (token.isEmpty()) return
        inputState = TextFieldValue()
        onValueChange(rebuild(chips + token, ""))
        onClearSuggestions()
    }

    val chipScroll = rememberScrollState()
    // Collapse to a one-line summary past what fits without scrolling (about two per line).
    val collapsed = !expanded && chips.size > 2 && !neverCollapse
    // Open on first composition when asked (the To field on a fresh compose), so it self-focuses.
    LaunchedEffect(Unit) { if (autoFocus) expanded = true }
    // The input isn't composed while collapsed, so focus it once the field expands.
    LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }
    // Follow the growing chip area so the blinking input stays in view. Instantly, not animated (#94):
    // sliding the chips into place is the one moving part of this field, it plays whenever an address
    // is committed, and it shows nothing the jump doesn't.
    LaunchedEffect(chipScroll.maxValue) { if (expanded) chipScroll.scrollTo(chipScroll.maxValue) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    expanded = true
                    // An already-expanded field doesn't re-run the effect below, so ask here too —
                    // this is the case where a tap on the label used to do nothing at all (#26).
                    runCatching { focus.requestFocus() }
                    // On the label, or in the empty space left of what is being typed: caret to the
                    // start of that text. Committed chips aren't text, and an empty input has no start
                    // to aim at, so both leave the caret alone.
                    geometry.caretFor(inputValue.text.length)?.let { caret ->
                        inputState = inputValue.copy(selection = TextRange(caret))
                    }
                }
                // At least a 48dp tap target even when the field is empty/collapsed (accessibility).
                .heightIn(min = MailMetrics.s48)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = MailMetrics.s16)
                .onGloballyPositioned {
                    fieldWidthPx = it.size.width
                    fieldBottomPx = (it.positionInRoot().y + it.size.height).roundToInt()
                    windowHeightPx = it.findRootCoordinates().size.height
                },
        ) {
            FieldLabel(label)
            if (collapsed) {
                // One-line summary: the first recipient plus a count of the rest. Tapping expands.
                Row(
                    modifier = Modifier.weight(1f).padding(vertical = MailMetrics.s14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chips.first(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chips.size > 1) {
                        Text(
                            "+${chips.size - 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = MailMetrics.s8),
                        )
                    }
                }
            } else {
              FlowRow(
                // Cap the chip area (~2.5 lines) and scroll it, so adding many recipients doesn't grow
                // the field without limit and eat the message body (#26).
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = MailMetrics.s104)
                    .verticalScroll(chipScroll)
                    .padding(vertical = MailMetrics.s6),
                horizontalArrangement = Arrangement.spacedBy(MailMetrics.s6),
              ) {
                chips.forEachIndexed { index, chip ->
                    // Invalid address OR (while encrypting) no key for it → flagged.
                    val valid = isValidEmail(chip) && !missingKey(chip)
                    InputChip(
                        selected = false,
                        // A tap hands the address back as plain text (#94): it leaves the chip row for
                        // the input, where the field opens it with the caret at its end, and a second
                        // tap puts the caret exactly where the typo is. Two taps, no double-tap timing
                        // to guess at. Whatever was half-typed is committed rather than lost.
                        onClick = {
                            expanded = true
                            inputState = TextFieldValue()
                            onValueChange(recipientsWithChipEdited(value, index))
                            onClearSuggestions()
                            runCatching { focus.requestFocus() }
                        },
                        label = { Text(chip, maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.compose_remove),
                                modifier = Modifier
                                    .size(MailMetrics.s16)
                                    .clickable { onValueChange(rebuild(chips.filterIndexed { i, _ -> i != index }, input)) },
                            )
                        },
                        colors = if (valid) {
                            InputChipDefaults.inputChipColors()
                        } else {
                            InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        // No elevation, and therefore none of Material's interaction-driven elevation
                        // animation (#94). An input chip's elevations are all 0dp anyway, so this
                        // changes nothing on screen — it stops an animated shadow being computed.
                        elevation = null,
                    )
                }
                // The input exists only while expanded, so an unfocused field shows no empty input line
                // (and no stray blinking cursor) — it collapses to its chips.
                if (expanded) {
                  BasicTextField(
                    value = inputValue,
                    onValueChange = { typed ->
                        val raw = typed.text
                        val last = raw.lastOrNull()
                        if (last == ',' || last == ';' || last == ' ' || last == '\n') {
                            val token = raw.dropLast(1).trim()
                            inputState = TextFieldValue()
                            onValueChange(rebuild(if (token.isNotEmpty()) chips + token else chips, ""))
                            onClearSuggestions()
                        } else {
                            inputState = typed
                            onValueChange(rebuild(chips, raw))
                            onSuggest(raw)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // Enter now commits the address instead of merely closing the keyboard (#83). The
                    // action is named here rather than left to `singleLine`'s implicit one, because a
                    // hardware Enter is routed to THIS action and an unnamed one does nothing at all.
                    // Not calling the default action is what keeps the keyboard up.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier = Modifier
                        .headerTapText(geometry)
                        // Fill the rest of the line so a tap in the empty area past the last chip lands
                        // the caret at the end; wrap to a new line once space runs short.
                        .weight(1f)
                        .widthIn(min = MailMetrics.s90)
                        .padding(vertical = MailMetrics.s6)
                        .onFocusChanged { fs ->
                            if (fs.isFocused) {
                                wasFocused = true
                            } else if (wasFocused) {
                                // Commit what was typed but not yet turned into a chip, so it stays
                                // visible when the field collapses and its input is hidden (#26).
                                val pending = input.trim()
                                if (pending.isNotEmpty()) onValueChange(rebuild(chips + pending, ""))
                                expanded = false
                                wasFocused = false
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                                input.isEmpty() && chips.isNotEmpty()
                            ) {
                                onValueChange(rebuild(chips.dropLast(1), ""))
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(focus),
                  )
                }
              }
            }
            trailing?.invoke()
        }
        FieldDivider()
        val density = LocalDensity.current
        // The keyboard is read HERE, in the field's own composition, and never inside the Popup: a
        // Popup is a separate window and its insets are not the screen's. Reading it as an inset is the
        // only way to know the keyboard is up, since the window is not resized (edge to edge).
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        // The height the menu may take, or null when there is not even one row of room left under the
        // field, in which case no menu is drawn at all (#143). Keyed on every quantity that moves: the
        // keyboard's height changes without any of this composable's own state changing.
        val menuMaxHeight = remember(windowHeightPx, fieldBottomPx, imeBottomPx, density) {
            suggestionMenuMaxHeight(
                windowHeightPx = windowHeightPx,
                fieldBottomPx = fieldBottomPx,
                imeHeightPx = imeBottomPx,
                density = density,
            )
        }
        if (expanded && suggestions.isNotEmpty() && menuMaxHeight != null) {
            // A floating contextual menu, not an inline list: it hangs under the field and over
            // whatever is below, so the subject and body never shift as suggestions appear. Positioned
            // by the popup's own anchor bounds and sized to the field's measured width. Not focusable,
            // so the keyboard stays up and each keystroke keeps filtering.
            val below = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
                }
            }
            Popup(
                popupPositionProvider = below,
                onDismissRequest = onClearSuggestions,
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = if (fieldWidthPx > 0) {
                        Modifier.width(with(density) { fieldWidthPx.toDp() })
                    } else {
                        Modifier
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MailMetrics.s3,
                    shadowElevation = MailMetrics.s6,
                ) {
                    // Cap the menu at the room measured under the field with the keyboard up, and let it
                    // scroll on its own — a lower cap therefore loses no suggestion, it only stops the
                    // list covering the keys that filter it.
                    Column(
                        Modifier
                            .heightIn(max = menuMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEachIndexed { index, contact ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                            // Picture on the left when the address book has one, monogram otherwise —
                            // the slot is the same size either way, so rows keep their height while
                            // photos decode and the list never jumps under the finger.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(rebuild(chips + contact.email, ""))
                                        onClearSuggestions()
                                    }
                                    .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(
                                    email = contact.email,
                                    name = contact.name,
                                    photoUri = contact.photoUri,
                                )
                                Spacer(Modifier.width(MailMetrics.s12))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.name ?: contact.email,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (contact.name != null) {
                                        Text(
                                            contact.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where a header row was last pressed and where its editable text starts — the two things needed to
 *  tell a tap on the label (or on the space before the first character) from a tap on the text itself.
 *  Read by the row's click handler, never during composition: plain fields, no snapshot state. */
private class HeaderTapGeometry {
    /** Last press, in the row's coordinates. */
    var pressX: Float = Float.NaN

    /** Row and editable-text origins in root coordinates; their difference is row-local. */
    var rowX: Float = Float.NaN
    var textX: Float = Float.NaN

    /** The caret a tap should force in a field holding [textLength] characters, or null for none. */
    fun caretFor(textLength: Int): Int? = headerTapCaret(pressX, textX - rowX, textLength)
}

/** The whole header row is the field's tap target (#26). Watches the pointer on the way down WITHOUT
 *  consuming it, so a tap on the text is still handled by the field exactly as before, and records
 *  where it landed. Still a plain `clickable`, so what TalkBack sees is unchanged. */
@Composable
private fun Modifier.headerTapRow(geometry: HeaderTapGeometry, onTap: () -> Unit): Modifier = this
    .onGloballyPositioned { geometry.rowX = it.positionInRoot().x }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                    if (change.pressed && !change.previousPressed) geometry.pressX = change.position.x
                }
            }
        }
    }
    .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onTap() }

/** Marks the editable text of a header row, so [headerTapRow] can tell a tap before it apart. */
private fun Modifier.headerTapText(geometry: HeaderTapGeometry): Modifier =
    onGloballyPositioned { geometry.textX = it.positionInRoot().x }

@Composable
private fun FieldLabel(text: String) {
    // A fixed, tight width so From / To / Cc / Bcc all line up and the input starts just past the label
    // (K-9 style); still wide enough for the short field labels across locales.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(MailMetrics.s48),
    )
}

@Composable
private fun FieldDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The label for each mode: the menu entry, and the mode named in the button's contentDescription. */
private fun pgpModeLabel(mode: PgpMode): Int = when (mode) {
    PgpMode.OFF -> R.string.compose_pgp_off
    PgpMode.SIGN -> R.string.compose_pgp_sign
    PgpMode.ENCRYPT -> R.string.compose_pgp_encrypt
    PgpMode.ENCRYPT_UNSIGNED -> R.string.compose_pgp_encrypt_unsigned
}

/**
 * Quick "send later" presets → (preset, label, epoch-millis) for a menu drawn at [nowMillis]. The zone
 */
private fun schedulePresets(context: android.content.Context, nowMillis: Long): List<Triple<SchedulePreset, String, Long>> =
    schedulePresetsAt(nowMillis, java.time.ZoneId.systemDefault())
        .map { (preset, millis) ->
            val label = when (preset) {
                SchedulePreset.IN_1_HOUR -> R.string.schedule_in_1_hour
                SchedulePreset.THIS_EVENING -> R.string.schedule_this_evening
                SchedulePreset.TOMORROW_MORNING -> R.string.schedule_tomorrow_morning
                SchedulePreset.TOMORROW_EVENING -> R.string.schedule_tomorrow_evening
            }
            Triple(preset, context.getString(label), millis)
        }

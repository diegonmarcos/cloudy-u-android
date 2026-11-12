package app.sterna.ui.message

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import app.sterna.BuildConfig
import app.sterna.appLocale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.mail.preferredAction
import app.sterna.core.data.text.attachmentFileName
import app.sterna.core.data.text.safeFileName
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import app.sterna.core.imap.CryptoKind
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import app.sterna.ui.text.rememberTextToolRunner
import app.sterna.ui.text.LocalTextToolRunner
import app.sterna.ui.text.onReader
import app.sterna.PendingReplySuggestion
import app.sterna.container
import app.sterna.ui.text.TextTool
import app.sterna.ui.text.TextToolScope
import app.sterna.ui.text.TextToolIconRow
import app.sterna.ui.text.TextToolSurface
import app.sterna.core.data.text.extractVerificationCode
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.markDeceptiveLinks
import app.sterna.ui.compose.bodySource
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import android.widget.Toast
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.paging.compose.collectAsLazyPagingItems
import app.sterna.R
import app.sterna.core.data.calendar.ParsedEvent
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import android.text.format.DateUtils
import app.sterna.ui.canSnoozeIn
import app.sterna.ui.browser.InAppBrowser
import app.sterna.ui.inbox.FolderPickerRow
import app.sterna.ui.inbox.MoveAccountRow
import app.sterna.ui.inbox.filterFolderRows
import app.sterna.ui.inbox.mailboxDisplayName
import app.sterna.ui.inbox.mailboxPathLabel
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.isOutgoingFolder
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.sender.trashFilePath
import app.sterna.ui.snoozed.SnoozeDeadlineHeader
import app.sterna.ui.theme.MailMetrics
import app.sterna.ui.theme.LocalAppStyle
import app.sterna.ui.assigned.AssignTaskDialog
import app.sterna.ui.assigned.MailTasks
import app.sterna.core.data.tasks.tasksOfMail
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import app.sterna.core.data.settings.AppStyle
import app.sterna.util.LinkCleaner
import app.sterna.util.MailDates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TEST BUILD ONLY — set back to false before integrating. When true,
 * [WebViewLayerGuard.useSoftwareLayer] returns true unconditionally, so the software-layer path can
 * be exercised on a device that never hits the GPU-functor SIGSEGV.
 */
private const val FORCE_SOFTWARE_LAYER = false

/**
 * True while the nav destination hosting the reading view is animating. On the GL HWUI pipeline,
 * drawing the body WebView's hardware functor into the fade's offscreen layer can hit an unguarded
 * null SkSurface in AOSP's GLFunctorDrawable — a SIGSEGV on every back press (#10). [NavFadeGuard]
 * arms a crash sentinel while a hardware body is exposed to a fade, and
 * [WebViewLayerGuard.markProven] is deferred until no fade runs.
 */
val LocalNavTransitionActive = compositionLocalOf { false }

/**
 * The identity of a pager entry — (email id, owning account) — as one string. Same-server accounts
 * under a single login can hold messages with identical ids (#31), so the pager identifies entries
 * by the pair. The composition lives in [MessagePaging.entryKey] so it can be unit-tested.
 */
private fun pagerKey(entry: Pair<String, String?>): String =
    MessagePaging.entryKey(entry.first, entry.second)

/**
 * The reading view. A [HorizontalPager] swipes between the entries of the context the reader came
 * from: the inbox's paged flow, the bounded search results, the messages of an unfolded conversation
 * (#13), or none of them for a lone message. Each page owns its own [MessageViewModel] and account
 * context, and mark-as-read fires on settle, never while a message is flicked past.
 */
/** Where "Sign in again" goes from a message the server refused to load: the account's settings. */
val LocalOpenAccountSettings = androidx.compose.runtime.staticCompositionLocalOf<(String?) -> Unit> { {} }

@Composable
fun MessageScreen(
    anchorEmailId: String,
    anchorAccountId: String?,
    initialIndex: Int,
    listSource: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<app.sterna.core.data.mail.InboxRow>>?,
    searchResults: List<Email>?,
    threadEntries: List<Pair<String, String?>>? = null,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String, String?) -> Unit,
    onComposeTo: (address: String) -> Unit,
    /** Composed in the reading pane beside the list (#103): no back arrow — the system Back and
     *  the actions suffice, as on Gmail's tablet layout — and nothing else changes. */
    paneMode: Boolean = false,
    /** The pager settled on a page: (emailId, accountId, page). The settle is a human gesture —
     *  the only moment the pane's anchor follows the pager. Null on the full-screen route. */
    onPageSettled: ((emailId: String, accountId: String?, page: Int) -> Unit)? = null,
) {
    when {
        listSource != null -> {
            val items = listSource.collectAsLazyPagingItems()
            // The pager pages over a STICKY merge of the live flow: rows removed while reading keep
            // their slot for the whole session. Following the live removals would re-bind the
            // settled page to the next row — under the unread filter, read-on-settle removed the
            // row, the next unread slid in and got marked read too, cascading through every one.
            val liveEntries = items.itemSnapshotList.items.map { it.email.id to it.email.accountId }
            var entries by remember { mutableStateOf(listOf<Pair<String, String?>>()) }
            // Identified by (account, id), never by the bare id: two accounts of one login can list
            // a message under the SAME server id (#31), and a bare key would swallow the second row
            // in the merge below.
            entries = MessagePaging.mergeEntries(entries, liveEntries, ::pagerKey)
            if (entries.isEmpty()) {
                // The shared paged flow replays its cached pages within a frame or two; show a
                // brief loader until the entry list is known so the pager opens on the right page.
                MessageLoadingScaffold(onBack, paneMode)
            } else {
                // Resolve the opening page once: by the anchor's key when it's in the loaded
                // window (robust to the list having shifted), else the tapped index.
                val initialPage = remember {
                    MessagePaging.resolveInitialPage(
                        entries.map(::pagerKey),
                        pagerKey(anchorEmailId to anchorAccountId),
                        initialIndex,
                    )
                }
                val liveIndexById = liveEntries.withIndex().associate { (i, e) -> pagerKey(e) to i }
                MessagePager(
                    pageCount = entries.size,
                    initialPage = initialPage,
                    // Indexing the paged items near the end triggers paging, so older entries swipe
                    // in as on scroll; the entry's index in the LIVE list can trail its sticky index
                    // once rows have been removed, hence the id→live-index lookup.
                    entryAt = { i ->
                        entries.getOrNull(i)?.also { entry ->
                            // Guard the index against the LIVE count: deleting from the reader
                            // invalidates the paged flow, and the item provider re-runs this lambda
                            // during drainChanges — before recomposition rebuilds liveIndexById —
                            // while the presenter is transiently EMPTY (#13).
                            liveIndexById[pagerKey(entry)]
                                ?.takeIf { it < items.itemCount }
                                ?.let { liveIndex -> items[liveIndex] }
                        }
                    },
                    onBack = onBack,
                    onReply = onReply,
                    onDelete = onDelete,
                    onArchive = onArchive,
                    onMove = onMove,
                    onComposeTo = onComposeTo,
                    paneMode = paneMode,
                    onPageSettled = onPageSettled,
                )
            }
        }
        !searchResults.isNullOrEmpty() -> {
            val initialPage = remember(searchResults) {
                MessagePaging.resolveInitialPage(
                    searchResults.map { pagerKey(it.id to it.accountId) },
                    pagerKey(anchorEmailId to anchorAccountId),
                    initialIndex,
                )
            }
            MessagePager(
                pageCount = searchResults.size,
                initialPage = initialPage,
                entryAt = { i -> searchResults.getOrNull(i)?.let { it.id to it.accountId } },
                onBack = onBack,
                onReply = onReply,
                onDelete = onDelete,
                onArchive = onArchive,
                onMove = onMove,
                onComposeTo = onComposeTo,
                paneMode = paneMode,
                onPageSettled = onPageSettled,
            )
        }
        // Opened from an unfolded conversation: page over that conversation only, on a snapshot of
        // what it showed, so the pager cannot run past its first or last message (#13).
        !threadEntries.isNullOrEmpty() -> {
            val initialPage = remember(threadEntries) {
                // By the pair like the other three branches. A conversation's members are all one
                // account's, so this is defence in depth rather than a second #92 — but a wrongly
                // keyed conversation FAILS to find the anchor and falls back to the tapped index,
                // instead of matching a foreign message that happens to share the id.
                MessagePaging.resolveInitialPage(
                    threadEntries.map(::pagerKey),
                    pagerKey(anchorEmailId to anchorAccountId),
                    initialIndex,
                )
            }
            MessagePager(
                pageCount = threadEntries.size,
                initialPage = initialPage,
                entryAt = { i -> threadEntries.getOrNull(i) },
                // A conversation has a known, small number of messages, so the reader can say where
                // in it you are — and offer the two chevrons for people who don't swipe.
                showPosition = true,
                onBack = onBack,
                onReply = onReply,
                onDelete = onDelete,
                onArchive = onArchive,
                onMove = onMove,
                onComposeTo = onComposeTo,
                paneMode = paneMode,
                onPageSettled = onPageSettled,
            )
        }
        // No list context: a lone message (e.g. opened from global search).
        else -> MessagePager(
            pageCount = 1,
            initialPage = 0,
            entryAt = { anchorEmailId to anchorAccountId },
            onBack = onBack,
            onReply = onReply,
            onDelete = onDelete,
            onArchive = onArchive,
            onMove = onMove,
            onComposeTo = onComposeTo,
            paneMode = paneMode,
            onPageSettled = onPageSettled,
        )
    }
}

/** The settled page's identity + ViewModel, published to the pager-level fixed chrome (#62). */
private class ActiveMessage(
    val emailId: String,
    val accountId: String?,
    val viewModel: MessageViewModel,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MessagePager(
    pageCount: Int,
    initialPage: Int,
    entryAt: (Int) -> Pair<String, String?>?,
    /** Show the "2 / 5" position line with its two chevrons (conversation context only — the list
     *  context is unbounded and pages more entries in, so a running total there would be both wrong
     *  and restless). */
    showPosition: Boolean = false,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String, String?) -> Unit,
    onComposeTo: (address: String) -> Unit,
    paneMode: Boolean = false,
    onPageSettled: ((emailId: String, accountId: String?, page: Int) -> Unit)? = null,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    // The chrome is FIXED: it lives OUTSIDE the pager, so swiping between messages never moves it
    // (#62). It acts on the SETTLED page, which publishes its ViewModel here on settle — so the
    // bars' content switches on settle, never mid-gesture.
    var activeMessage by remember { mutableStateOf<ActiveMessage?>(null) }
    // The page the reader has settled on, held as the MESSAGE and not as its rank.
    // `PagerState.settledPage` is an index refreshed only around a scroll: mail prepended under an
    // open reader re-anchors the pager while that index keeps its old number, naming the neighbour
    // ABOVE — whose page then believed itself settled and LOADED (bench G-décalage, 2026-09-04).
    // `currentPage` is read ONLY at rest, and `isScrollInProgress` covers drag and fling alike.
    var settledEntry by remember { mutableStateOf(entryAt(pagerState.currentPage)) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .collect { (scrolling, page) ->
                settledEntry = MessagePaging.settledEntry(settledEntry, entryAt(page), scrolling)
            }
    }
    val settledKey = settledEntry?.let(::pagerKey)
    // Settling on a page whose entry hasn't loaded means there is no message: drop the published
    // one, or the fixed chrome would keep starring the message the user swiped away from. The page
    // republishes itself as soon as its entry arrives. The settle is also the one moment the reading
    // pane's anchor follows the pager (#103) — it fires on the settled MESSAGE changing, never on a
    // page number moving under it.
    LaunchedEffect(settledEntry) {
        val settled = settledEntry
        if (settled == null) activeMessage = null
        else onPageSettled?.invoke(settled.first, settled.second, pagerState.currentPage)
    }
    val scope = rememberCoroutineScope()
    // The finger's travel across the pager, watched without consuming anything, so the fling below
    // can demand 25 dp of it. See PagerFlingDistance.kt.
    val pagerTravel = remember { PagerTouchTravel() }
    // ONE runner for the whole reader. AI Resume is started from the toolbar — fixed chrome at THIS
    // level (#62) — and shown in a box under the sender, which is inside the swiped page. Provided
    // rather than threaded so four signatures, two of them pinned line for line by tests, do not
    // each gain a parameter they never look at. See LocalTextToolRunner.
    val textTools = rememberTextToolRunner(TextToolSurface.READ)
    // Swiping to another message drops the previous one's summary and any error with it. A box
    // headed by this sender holding a summary of the last one is the worst of the two failures
    // available here: it looks right.
    LaunchedEffect(activeMessage?.emailId) { textTools.dismiss() }
    val application = LocalContext.current.applicationContext as android.app.Application
    CompositionLocalProvider(
        LocalTextToolRunner provides textTools,
        LocalReplyAllWith provides { emailId, accountId, text ->
            // Hand the suggestion over, then open the ordinary Reply all. Nothing is sent from here.
            application.container.pendingReplySuggestion = PendingReplySuggestion(emailId, text)
            onReply("replyAll", emailId, accountId)
        },
    ) {
    Scaffold(
        topBar = {
            Column {
                MessageTopBar(activeMessage, onBack, onReply, onDelete, onArchive, onMove, paneMode)
                // The position line belongs to the header, under the app bar rather than in its
                // title slot: the toolbar already carries five actions, and on a narrow screen a
                // counter squeezed between them would clip. Hidden outright for a one-message
                // context.
                if (showPosition && pageCount > 1) {
                    MessagePositionBar(
                        page = pagerState.currentPage,
                        pageCount = pageCount,
                        onGo = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().then(pagerTravel.modifier),
                // Key by entry identity — (account, id), never the bare id — so pages keep their
                // identity when rows are inserted around them. The bare id made two messages of two
                // same-server accounts ONE page for Compose, which reused the page and its ViewModel
                // and showed the other account's message (#92).
                key = { i -> entryAt(i)?.let(::pagerKey) ?: "page-$i" },
                // One page composed each side of the one being read. What composition buys is the
                // page and its ViewModel, NOT a WebView: the render used to start at the finger, the
                // ~1 s hourglass per swipe. MessagePage's warmFromCache buys it from the LOCAL CACHE.
                // A neighbour is still never `load`ed: on IMAP that imports the sender's announced
                // key into OpenKeychain, and no gesture can take it back.
                beyondViewportPageCount = 1,
                // Demand 25 dp of FINGER travel before a flick may change message: the library's own
                // rule is velocity-only, so a 10-20 dp brush of the thumb turned the page. The travel
                // is read from the pointer stream, NOT from the pager's own offset, which is short by
                // the touch slop and by whatever the body WebView held back.
                flingBehavior = rememberPageFlingBehavior(pagerState, pagerTravel),
            ) { page ->
                val entry = entryAt(page)
                if (entry == null) {
                    // Paged item for this page hasn't loaded yet. No toolbar here — the fixed one
                    // above stays in place.
                    Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }
                } else {
                    MessagePage(
                        emailId = entry.first,
                        accountId = entry.second,
                        // Settle-gating, and it governs TWO things: the page the reader lands on is
                        // the only one that marks its message read, and the only one that LOADS at
                        // all — on IMAP a load imports the sender's announced key into OpenKeychain.
                        // Compared by IDENTITY, never by page number: an index goes stale the
                        // moment the list moves under a motionless reader (G-décalage).
                        active = pagerKey(entry) == settledKey,
                        onActivated = { vm ->
                            activeMessage = ActiveMessage(entry.first, entry.second, vm)
                        },
                        onComposeTo = onComposeTo,
                    )
                }
            }
            // The bottom Reply/Forward bar, fixed over the pager. Its visibility follows the SETTLED
            // page's scroll-end reveal, and the settled page does not change during a drag, so the
            // bar cannot flicker mid-swipe.
            val active = activeMessage
            if (active != null) {
                val barVisible by active.viewModel.replyBarVisible.collectAsStateWithLifecycle()
                androidx.compose.animation.AnimatedVisibility(
                    visible = barVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                    exit = androidx.compose.animation.fadeOut() +
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
                ) {
                    ReplyForwardBar { mode -> onReply(mode, active.emailId, active.accountId) }
                }
            }
        }
    }
    }
}

/**
 * "2 / 5" between two chevrons, telling the reader where they are in the conversation, and letting
 * them step through it by tap as well as by swipe. The chevrons grey out at the ends.
 */
@Composable
private fun MessagePositionBar(
    page: Int,
    pageCount: Int,
    onGo: (Int) -> Unit,
) {
    val hasPrevious = MessagePaging.hasPrevious(page, pageCount)
    val hasNext = MessagePaging.hasNext(page, pageCount)
    val spokenPosition = stringResource(R.string.message_position_spoken, page + 1, pageCount)
    Row(
        // Same container colour as the app bar above it, so the header reads as one block.
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onGo(page - 1) }, enabled = hasPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.message_previous),
            )
        }
        Text(
            text = stringResource(R.string.message_position, page + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Read out as a sentence rather than "2 slash 5".
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = spokenPosition
            },
        )
        IconButton(onClick = { onGo(page + 1) }, enabled = hasNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.message_next),
            )
        }
    }
}

@Composable
private fun MessagePage(
    emailId: String,
    accountId: String?,
    active: Boolean,
    onActivated: (MessageViewModel) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // Each page needs its own MessageViewModel (the pager composes several at once). A per-page
    // ViewModelStore, cleared when the page leaves the pager, bounds memory.
    val owner = rememberDisposableViewModelStoreOwner()
    val viewModel: MessageViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer { MessageViewModel(app) } },
    )
    // The neighbours are made READY, from the local cache and nothing else: composition buys the page
    // and its ViewModel but not a WebView, so the whole render used to start at the finger.
    // No `active` in the keys or the arguments: BOTH neighbours are what the pager composes.
    // Idempotent, and it reaches no server — the whole difference with the warm-up 798a5fd1 removed.
    LaunchedEffect(emailId, accountId) { viewModel.warmFromCache(emailId, accountId) }
    // `active` is BOTH a key of this effect and the argument: as a key because the page is composed
    // before it settles and the effect must replay the moment it does; as the argument because the
    // pager warms a page each side, and on IMAP loading one imports the sender's announced key into
    // OpenKeychain. Ungated, opening a message deposits the keys of its two neighbours.
    LaunchedEffect(emailId, accountId, active) { viewModel.load(emailId, accountId, active) }
    LaunchedEffect(active) {
        viewModel.onActiveChanged(active)
        // Hand this page's ViewModel to the fixed chrome once the user has settled on it.
        if (active) onActivated(viewModel)
    }
    // Has the reader EVER settled on this page? A warmed page keeps collecting the allowlist through
    // its own ViewModel, so without this "Always show images from this sender" flips `senderAllowed`
    // on the NEIGHBOUR too, against PRIVACY.md:288.
    // A latch, never a bare `!active`: `settledEntry` answers null at rest, and a bare reading would
    // flip `blockRemote` under the reader and reload the message she is on.
    // And it lives here, not as a ViewModel flow, which load()'s prologue would reset one frame
    // early — the frame that fetches.
    var everActive by remember { mutableStateOf(false) }
    if (active) {
        everActive = true
    }
    MessageContent(
        viewModel = viewModel,
        emailId = emailId,
        accountId = accountId,
        everActive = everActive,
        onComposeTo = onComposeTo,
    )
}

/** A ViewModelStoreOwner whose store is cleared when this composable leaves composition. */
@Composable
private fun rememberDisposableViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

/** Toolbar + centred spinner shown while the pager's entry list is still being resolved
 *  (before [MessagePager] — and its fixed toolbar — can compose at all). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageLoadingScaffold(onBack: () -> Unit, paneMode: Boolean = false) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.message_title_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // In the reading pane the slot stays empty, as on the settled bar below (#103).
                navigationIcon = {
                    if (!paneMode) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.message_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
            LoadingRing()
        }
    }
}

/**
 * The reader's FIXED top app bar: ONE instance at the pager level, outside the horizontal swipe, so
 * paging never moves it (#62). It renders the actions for the SETTLED page's message; while no page
 * has settled it shows just the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageTopBar(
    active: ActiveMessage?,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String, String?) -> Unit,
    paneMode: Boolean = false,
) {
    TopAppBar(
        // The subject is shown in full inside the message (#44), so the bar has no title — that frees
        // the width for a Follow (star) action.
        title = {},
        // No back arrow in the reading pane (#103): the list is right there, the system Back
        // empties the pane, and Gmail's tablet reader shows none either.
        navigationIcon = {
            if (!paneMode) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.message_back),
                    )
                }
            }
        },
        actions = {
            if (active != null) {
                MessageActions(active, onBack, onReply, onDelete, onArchive, onMove)
            }
        },
    )
}

/**
 * The text a reading action SENDS for [email]. Flattened here, where the reader's own HTML-vs-text
 * notion already lives, and only ever to be sent: the message keeps its markup, and nothing flattened
 * is ever written back. The quoted thread is cut — a translation of the whole history is not what was
 * asked for, and it is what the user pays for by the token. ONE function, because the overflow's
 * named entries and the icon row under the tags both run tools and must send the same text.
 *
 * `internal` since #500: the message-list row's new action icons run the SAME tool over the SAME
 * text shape, and a second copy of this flattening decision would be the thing #500 was told not
 * to write.
 */
internal fun receivedTextToolSource(email: Email): String {
    val (raw, isHtml) = bodySource(email)
    return TextToolScope.receivedScope(if (isHtml) htmlToText(raw) else raw)
}

/**
 * What a tap on a reading tool does on a RECEIVED message. Translate rewrites the text of the page in
 * place (the fragment the reader renders, cached per message and language) and Resume writes the summary
 * box under the sender; neither opens a dialog and neither writes the stored message. Enhance is not on
 * this surface, so there is nothing for it to do here. ONE function for the icon row and the overflow, so
 * the two cannot start different things.
 */
private fun runReaderTool(
    viewModel: MessageViewModel,
    tool: TextTool,
    email: Email,
    noContent: String,
) = tool.onReader(
    translate = { viewModel.translateNow(readerBody(email, noContent).fragment) },
    summarise = { viewModel.summariseNow(receivedTextToolSource(email)) },
)

/**
 * The ONE "Copy Code" gesture (#438), shared by the reading row's icon and the overflow's named
 * entry so the two cannot drift apart: the extractor's confident candidate goes to the clipboard
 * with a word, and a message that offers nothing confident gets a word that says so — never a
 * low-confidence guess copied under the user's thumb.
 *
 * `internal` since #500: the list row's Copy Code icon calls this SAME gesture rather than a
 * second extractor wired to a second toast.
 */
internal fun copyVerificationCodeOrSayNone(clipboard: ClipboardManager, context: Context, email: Email?) {
    val code = email?.let { verificationCodeFromMessage(it) }
    if (code != null) {
        clipboard.setText(AnnotatedString(code))
        Toast.makeText(context, R.string.message_code_copied, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, R.string.message_code_not_found, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The Copy Code ICON, declared once (#500): the reading row and the message-list row both draw this,
 * so the glyph and its spoken label cannot drift apart the way a second `Icons.Filled.ContentCopy`
 * literal in the list adapter would let them.
 */
@Composable
internal fun CopyCodeIcon() {
    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.message_copy_code))
}

/**
 * The message a "Copy Code" tap hands the extractor: subject AND body, in both forms the extractor
 * reads — the flattened text, for line isolation and phrases, and the raw markup, for the
 * large-font-cell signal. Nothing is cached, flattened or sent anywhere.
 */
internal fun verificationCodeFromMessage(email: Email): String? {
    val (raw, isHtml) = bodySource(email)
    return extractVerificationCode(
        subject = email.subject,
        bodyText = if (isHtml) htmlToText(raw) else raw,
        html = if (isHtml) raw else null,
    )
}

/**
 * The literal `|` that separates the reading row's icon groups (#438). A character, not a
 * `HorizontalDivider`: the ask was for a group marker between icons on one row, and a divider is
 * a full-width bar that would read as a third group of its own.
 *
 * `internal` since #500: the list row's new action row uses this SAME literal character, per #303
 * — a divider View/Composable is the mistake this repo already made once.
 */
@Composable
internal fun ReadingGroupSeparator() {
    Text(
        text = "|",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = MailMetrics.s2),
    )
}

/**
 * The toolbar actions (star / move / reply-all / mark unread / overflow) for the settled message. All
 * state comes from that page's own [MessageViewModel], so everything updates when the pager settles
 * on a new page.
 */
@Composable
private fun MessageActions(
    active: ActiveMessage,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String, String?) -> Unit,
) {
    val viewModel = active.viewModel
    val accountId = active.accountId
    // The reader shows a single message; reply / reply-all / forward all target exactly the
    // opened message (the conversation itself lives in the list's inline unfold).
    val replyTargetId = active.emailId
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inJunk by viewModel.inJunk.collectAsStateWithLifecycle()
    val folderRole by viewModel.mailboxRole.collectAsStateWithLifecycle()
    val snoozedUntil by viewModel.snoozedUntil.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    // Per-message manual override; the sender allowlist auto-shows without it.
    val manualShow by viewModel.manualShowImages.collectAsStateWithLifecycle()
    // Folders the move-to-folder entry offers: this message's OWN account's, minus its current one
    // (#73). Empty hides the entry rather than opening a picker with nothing to pick.
    val folders by viewModel.moveTargets.collectAsStateWithLifecycle()
    // The LISTED account's whole folder list, only to spell out a target's parent path (#109) — the
    // picker's own flow, which follows the account row (#189). Never `accountMailboxes`: that one
    // is the message's OWN account's, and the sender rule is computed on it.
    val accountFolders by viewModel.pickerMailboxes.collectAsStateWithLifecycle()
    // The picker's account row (#189): the open message's account, the one chosen on the row, and
    // the accounts to list — all from the ViewModel, so the choice survives a rotation.
    val moveOwnerAccountId by viewModel.moveOwnerAccountId.collectAsStateWithLifecycle()
    val moveAccountId by viewModel.moveAccountId.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    // The open message's way out of its mailing list, if it offers one (RFC 2369 / RFC 8058),
    // and how far it has got — the menu entry stands down once the gesture has been made.
    val unsubscribe by viewModel.unsubscribe.collectAsStateWithLifecycle()
    val unsubscribeState by viewModel.unsubscribeState.collectAsStateWithLifecycle()
    // Only to know whether the open message has its BODY: a message never opened while online holds
    // its cached header and nothing else, and a "Print" entry offered on it would print a blank page.
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val printable = messages.firstOrNull()?.body != null
    val loaded = state as? MessageState.Loaded ?: return
    val senderEmail = loaded.email.from.firstOrNull()?.email
    val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = showRemoteImages(manualShow, senderAllowed)
    val noContent = stringResource(R.string.message_no_content)
    // Text tools on a RECEIVED message. onApply is null and stays null: this body is a record
    // of what somebody sent, so the result is shown to read or copy and the stored message is
    // never written to. See TextToolScope.
    //
    // The reader's ONE runner, not a fresh one: AI Resume is started here and drawn under the
    // sender, which is a different subtree of the same composition.
    val textTools = LocalTextToolRunner.current
    // Translate and the summary no longer open a dialog: Translate rewrites the text of the page in
    // place and the summary is a box under the sender. Both are driven by the page's ViewModel.
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val readerSummary by viewModel.summary.collectAsStateWithLifecycle()
    val inTrash by viewModel.inTrash.collectAsStateWithLifecycle()
    val resolvedMailbox by viewModel.mailboxId.collectAsStateWithLifecycle()
    // The tag/label surface (part five) and its state: the mailboxes this message is in, and the
    // account's mailboxes to name them by. Read here because the icon's own presence depends on
    // whether this protocol HAS a set to edit.
    var labelSheet by remember(active.emailId) { mutableStateOf(false) }
    val messageMailboxIds by viewModel.mailboxIds.collectAsStateWithLifecycle()

    // ── THE ACTION ROW, AND WHY IT HOLDS WHAT IT HOLDS (#293) ──────────────────────────────────
    //
    // The owner's words: "as top is actions: starred, move folders, reply all, mark as unread". So
    // the row is, left to right, exactly:
    //
    //     [Star] [Move to folder] [Reply-all] [Mark unread] [⋮]
    //
    // Five slots of the SIX a 360 dp bar has after the navigation icon (48 dp each of ~312 dp), so
    // nothing clips. What left the bar for it — the Tag icon and the Unsubscribe icon — did not
    // disappear: both are NAMED entries in the overflow, which lists every function as text, and
    // Unsubscribe also keeps its strip under the sender.
    //
    // The reading actions (AI Resume, Translate, Show images, plain text) are NOT here and NOT in
    // the overflow as icons: they are an icon row under the tags, drawn by MessageContent.
    //
    // Resume's condition is about the MESSAGE: absent while there is no body to summarise, and
    // absent unless this surface offers Resume at all — it asks the same list the runner asks.
    val resumable = messages.firstOrNull()?.body != null &&
        TextTool.RESUME in textTools.surface.tools
    // Keyed on the settled message so an open menu never carries over across a page settle.
    var menuOpen by remember(active.emailId) { mutableStateOf(false) }
    var assignOpen by remember(active.emailId) { mutableStateOf(false) }
    if (assignOpen) {
        val mail = (state as? MessageState.Loaded)?.email
        val owner = accountId ?: mail?.accountId
        if (mail != null && owner != null) {
            LaunchedEffect(Unit) { MailTasks.load(context) }
            val all by MailTasks.tasks.collectAsStateWithLifecycle()
            val assignedMessage = stringResource(R.string.assigned_engine_missing)
            AssignTaskDialog(
                accountId = owner,
                emailId = mail.id,
                mailSubject = mail.subject.orEmpty(),
                messageId = mail.messageId.firstOrNull(),
                existing = tasksOfMail(all, owner, mail.id).firstOrNull(),
                onDone = { stored ->
                    assignOpen = false
                    if (stored == false) android.widget.Toast.makeText(context, assignedMessage, android.widget.Toast.LENGTH_LONG).show()
                },
            )
        } else {
            assignOpen = false
        }
    }
    var snoozeSubmenu by remember(active.emailId) { mutableStateOf(false) }
    var movePicker by remember(active.emailId) { mutableStateOf(false) }
    // Follow (flag) toggle, promoted from the overflow menu to the bar now that
    // the subject no longer takes the title space (Codeberg #44).
    val flagged = loaded.email.isFlagged
    IconButton(onClick = { viewModel.toggleFlag() }) {
        Icon(
            if (flagged) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = stringResource(
                if (flagged) R.string.message_unflag else R.string.message_flag,
            ),
            tint = if (flagged) MaterialTheme.colorScheme.tertiary else LocalContentColor.current,
        )
    }
    // Move to folder (#73) — AFTER the star, as asked. The same picker the overflow entry opens, and
    // absent under the same condition: no other folder to move to means no picker worth opening.
    if (folders.isNotEmpty()) {
        IconButton(onClick = { movePicker = true }) {
            Icon(
                Icons.AutoMirrored.Filled.DriveFileMove,
                contentDescription = stringResource(R.string.inbox_move_to_folder),
            )
        }
    }
    // REPLY-ALL is the default now, with the double arrow that conventionally means it. Plain
    // single reply moved into the overflow and is one tap away there.
    //
    // The owner asked for this explicitly and it is built as asked — but reply-all as a default is
    // precisely the configuration in which a private answer reaches a whole list, so the composer
    // is opened with its recipient fields EXPANDED (see ComposeScreen's `expandRecipients`): every
    // address the reply will go to is on screen, above the cursor, before a word is typed. The
    // default is then never a surprise; it is a list the sender is looking at while they write.
    IconButton(onClick = { onReply("replyAll", replyTargetId, accountId) }) {
        Icon(
            Icons.AutoMirrored.Filled.ReplyAll,
            contentDescription = stringResource(R.string.message_reply_all),
        )
    }
    // Mark unread — last before the overflow, as asked (#293). The same ViewModel call as the
    // overflow entry: it clears $seen on the server and leaves the message, so the list shows it
    // bold again.
    IconButton(onClick = { viewModel.markUnread(onBack) }) {
        Icon(
            Icons.Filled.MarkEmailUnread,
            contentDescription = stringResource(R.string.message_mark_unread),
        )
    }
    // "Save as .eml": the system picker, then the message's exact server bytes into the document it
    // returns. Declared HERE, outside the DropdownMenu: registered inside it, the launcher is
    // disposed when the menu closes on the very tap that opened the picker, and the result has
    // nowhere to land. `exportFor` remembers WHICH message it was launched for; the ViewModel
    // refuses a result for another page, and an empty value after a lost state too.
    val exportName = remember(loaded.email.subject) { safeFileName(loaded.email.subject, "message") + ".eml" }
    var exportFor by rememberSaveable { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("message/rfc822"),
    ) { uri ->
        // A cancelled picker (null) does nothing — no toast.
        if (uri != null) viewModel.exportSource(uri, exportName, exportFor)
    }
    // "Export...": the sheet (format, then the system document picker or the share sheet) is its own
    // composable; `exportFor` remembers WHICH message the menu entry was tapped for.
    var exportSheet by rememberSaveable { mutableStateOf(false) }
    if (exportSheet) ExportSheetHost(viewModel, exportFor, loaded.email) { exportSheet = false }
    IconButton(onClick = { menuOpen = true }) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.message_more),
        )
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false; snoozeSubmenu = false },
        shape = MaterialTheme.shapes.medium,
    ) {
        if (snoozeSubmenu) {
            // An already-snoozed message opens this menu with its deadline spelled out, rather
            // than a mute list of delays (Codeberg #82).
            snoozedUntil?.let { at ->
                SnoozeDeadlineHeader(
                    DateUtils.formatDateTime(
                        context,
                        at,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                )
            }
            snoozePresets(context).forEach { (label, until) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false; snoozeSubmenu = false
                        viewModel.snooze(until, onBack)
                    },
                )
            }
        } else {
            // Reply variants (reply-ALL is the toolbar icon now). Plain single reply lives here —
            // still one tap from the same place forward and forward-as-attachment always were, and
            // deliberately first in the menu, since it is the entry someone opens this menu FOR
            // once the default answers everybody.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_reply)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                onClick = { menuOpen = false; onReply("reply", replyTargetId, accountId) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_reply_all)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = null) },
                onClick = { menuOpen = false; onReply("replyAll", replyTargetId, accountId) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_forward)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                onClick = { menuOpen = false; onReply("forward", replyTargetId, accountId) },
            )
            // The same message, but as a .eml the composer attaches (its exact server bytes),
            // with an empty body: the recipient gets it whole, headers included.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_forward_attachment)) },
                leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                onClick = { menuOpen = false; onReply("forwardAttachment", replyTargetId, accountId) },
            )
            // Assigned: attach a task (deadline + target date and details) to this mail.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_assign)) },
                leadingIcon = { Icon(Icons.Filled.AssignmentTurnedIn, contentDescription = null) },
                onClick = { menuOpen = false; assignOpen = true },
            )
            // EVERY function is a NAMED entry in this menu (#293) — the owner's "right menu should
            // show all functions as LISTS", "LIST NAMES NOT ICONS". The reading actions are ALSO the
            // icon row under the tags; here they are text, one per row, like everything else. An
            // icon row inside this menu is exactly what #293 had shipped, and what was reported.
            //
            // The text tools are generated from the surface's own declaration, so a tool taken off
            // TextToolSurface.READ loses its entry with no edit to this screen. Enhance is absent for
            // the reason it always was: it rewrites a text, and this one is a record of what somebody
            // else sent.
            HorizontalDivider()
            textTools.surface.tools.forEach { tool ->
                if (tool == TextTool.RESUME && !resumable) return@forEach
                DropdownMenuItem(
                    text = { Text(stringResource(tool.label)) },
                    leadingIcon = { Icon(tool.icon, contentDescription = null) },
                    enabled = !translation.running && !readerSummary.running,
                    onClick = {
                        menuOpen = false
                        runReaderTool(viewModel, tool, loaded.email, noContent)
                    },
                )
            }
            // Show images, once, for this message: offered only while images are actually blocked.
            val imagesOnceOffered = !showRemote
            if (imagesOnceOffered) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_show_images)) },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                    onClick = { menuOpen = false; viewModel.showImagesOnce() },
                )
            }
            // Copy Code (#438). The reading row's icon and this named entry run the SAME gesture —
            // extract a two-factor code, copy it, or say none was found — and this menu lists every
            // function as text, so the code grabber is a text entry here, like everything else.
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_copy_code)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    copyVerificationCodeOrSayNone(clipboard, context, loaded.email)
                },
            )
            // The per-sender image allowlist. It changes what the NEXT message from this sender does,
            // which is why it is not also an icon in the reading row under the tags: its meaning —
            // this sender, every message, from now on — no tooltip can carry.
            //
            if (senderEmail != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (senderAllowed) R.string.message_images_stop_sender
                                else R.string.message_images_always_sender,
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        viewModel.setImagesAlwaysAllowed(senderEmail, !senderAllowed)
                    },
                )
            }

            // Triage actions. Archive and Delete are HERE rather than on the row: the row's slots
            // went to the actions the owner named, and these two are the only ones on this screen
            // with a faster route already — a swipe on the message list, which is where triage
            // actually happens. Nothing was dropped; see the count note above the row.
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_archive)) },
                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    // The resolved folder and the page's own account, for the same reason the
                    // toolbar passed them: the fetched body can carry neither, and an archive
                    // without them misroutes and loses its Undo (#23).
                    onArchive(
                        loaded.email.copy(
                            mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                            accountId = accountId ?: loaded.email.accountId,
                        ),
                    )
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (inTrash) R.string.inbox_delete_forever else R.string.message_delete,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (inTrash) Icons.Filled.DeleteForever else Icons.Filled.Delete,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete(
                        loaded.email.copy(
                            mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                            accountId = accountId ?: loaded.email.accountId,
                        ),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (flagged) R.string.message_unflag else R.string.message_flag)) },
                leadingIcon = { Icon(if (flagged) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null) },
                onClick = { menuOpen = false; viewModel.toggleFlag() },
            )
            // Tags and mailboxes (part five): the label sheet, under the condition its bar icon had —
            // only when the account HAS a mailbox set to edit.
            if (folders.isNotEmpty() && messageMailboxIds.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_labels)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    onClick = { menuOpen = false; labelSheet = true },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_mark_unread)) },
                leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) },
                onClick = { menuOpen = false; viewModel.markUnread(onBack) },
            )
            // Move to folder (#73): the same action the list's selection bar carries, so a message
            // can be filed without going back to the list — the way OUT of Trash or Spam. It is also
            // the bar's second icon (#293); named here too, because this menu lists every function.
            if (folders.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_move_to_folder)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; movePicker = true },
                )
            }
            // Spam-reporting acts on incoming mail; in Drafts and Sent the open message is the user's
            // own outgoing mail, so it is not offered there (#82).
            if (!isOutgoingFolder(folderRole)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (inJunk) R.string.message_not_spam
                                else R.string.message_report_spam,
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        // Confirm the move once it lands, naming the destination
                        // folder — junk → Inbox ("Not spam"), inbox → Spam.
                        val destName = context.getString(
                            if (inJunk) R.string.folder_inbox else R.string.folder_junk,
                        )
                        val confirmMove: () -> Unit = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.status_moved_to_folder, destName),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onBack()
                        }
                        if (inJunk) viewModel.notSpam(confirmMove) else viewModel.reportSpam(confirmMove)
                    },
                )
            }
            // Snoozing is a promise to come back to a message, so it goes further: also gone in Spam
            // and in the Trash, where nothing is waiting to be dealt with (#82).
            if (canSnoozeIn(folderRole)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_snooze)) },
                    leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    onClick = { snoozeSubmenu = true },
                )
            }
            // Unsubscribe (RFC 2369 / RFC 8058) — shown only when the message carries a usable way
            // out AND that way out has not already been taken, so the entry is never a dead end and
            // never a second request. The same decision drives the banner's button: the two used to
            // disagree, and going round by the menu sent the POST again. The label says which
            // gesture it is — "Open page" is a browser and everything a page load implies.
            offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (action == UnsubscribeAction.OPEN_PAGE) {
                                    R.string.message_unsubscribe_open_page
                                } else {
                                    R.string.message_unsubscribe
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Unsubscribe, contentDescription = null) },
                    onClick = { menuOpen = false; viewModel.askUnsubscribe() },
                )
            }
            // Show Original / Show Translated: flips the page between the message as it arrived and
            // its cached in-place translation. Greyed until a translation exists for this text.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (translation.shown) R.string.message_show_original else R.string.message_show_translated,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                enabled = translation.exists,
                onClick = { menuOpen = false; viewModel.toggleTranslated() },
            )
            // Read-only raw-headers view (issue #60). Headers are fetched on demand here, so
            // the normal reader path never pulls them.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_view_headers)) },
                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                onClick = { menuOpen = false; viewModel.viewHeaders() },
            )
            ExportMenuItem { menuOpen = false; exportFor = active.emailId; exportSheet = true }
            // Save the message as the server holds it (.eml); an encrypted message stays encrypted.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_export_eml)) },
                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                onClick = { menuOpen = false; exportFor = active.emailId; exportLauncher.launch(exportName) },
            )
            // Print, last. The gesture itself is the page's: it has the body, the reading mode and
            // the remote-image decision, this menu has none of them. Absent until the body is here.
            if (printable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_print)) },
                    leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    onClick = { menuOpen = false; viewModel.print() },
                )
            }
        }
    }
    // The label surface (part five). Fed the SERVER's membership set, never the cached row's single
    // mailboxId — that one value is the misconception this sheet exists to correct. The account's
    // mailboxes name the ids and supply what "add" can offer, which is every mailbox the message is
    // not already in.
    if (labelSheet) {
        // `mailboxDisplayName` is @Composable — it resolves a standard folder's name from
        // resources — but every consumer below needs a PLAIN `(Mailbox) -> String`: `messageTags` is
        // a pure function that SORTS by the resolved label, and on the header's path it runs inside a
        // `remember`, which is not a composable context at all. So the names are resolved HERE, where
        // resources are reachable, and what goes down is a lookup. A mailbox with no entry falls back
        // to its own name rather than to a blank chip.
        val folderNames = accountFolders.associate { it.id to mailboxDisplayName(it.role, it.name) }
        val tags = messageTags(
            mailboxIds = messageMailboxIds,
            mailboxes = accountFolders,
            keywords = loaded.email.keywords,
            // Nothing is excluded here, unlike the chip row under the sender: this is the sheet
            // where a message's membership is EDITED, and hiding the folder it was opened from
            // would hide the one row a user came here to remove.
            currentMailboxId = null,
            nameOf = { folderNames[it.id] ?: it.name },
        )
        LabelSheet(
            tags = tags,
            addableMailboxes = accountFolders.filter { it.id !in messageMailboxIds },
            nameOf = { folderNames[it.id] ?: it.name },
            onAddMailbox = { id -> labelSheet = false; viewModel.addMailbox(id) },
            onRemoveMailbox = { id -> labelSheet = false; viewModel.removeMailbox(id) },
            onRemoveKeyword = { name -> labelSheet = false; viewModel.setUserKeyword(name, false) },
            onDismiss = { labelSheet = false },
        )
    }
    // The move-to-folder picker (#73): the same dialog the list's selection bar opens, fed the open
    // message's own account's folders. Picking one hands the message (stamped with the resolved
    // folder and its owning account) to the shared inbox ViewModel.
    if (movePicker) {
        // The filter field's text (#182). Held INSIDE the `if`, so leaving the dialog drops the
        // composition group and the field comes back empty next time.
        var folderQuery by remember { mutableStateOf("") }
        // Same rows as the list's picker, parent path included (#109), resolved against the account's
        // WHOLE folder list. Memoized: each call walks a map rebuilt over that whole list, and the
        // filter field re-runs this block on every keystroke.
        val folderPaths = remember(folders, accountFolders) {
            folders.map { folder -> mailboxPathLabel(folder, accountFolders) }
        }
        // Painted over the WHOLE offered list, never over the filtered one: what the filter compares
        // is what the row shows, and how many rows are painted must not depend on what has been
        // typed.
        val folderRows = ArrayList<FolderPickerRow>(folders.size)
        for ((folder, path) in folders.zip(folderPaths)) {
            folderRows += FolderPickerRow(folder, mailboxDisplayName(folder.role, folder.name), path)
        }
        val shownFolderRows = filterFolderRows(folderRows, folderQuery)
        AlertDialog(
            onDismissRequest = { movePicker = false; viewModel.chooseMoveAccount(null) },
            title = { Text(stringResource(R.string.inbox_move_to_folder)) },
            text = {
                Column {
                    // The account whose folders are listed, and the way to list another's (#189).
                    MoveAccountRow(accounts, moveOwnerAccountId, moveAccountId, viewModel::chooseMoveAccount)
                    // The filter field (#182), OUTSIDE the scroller so it stays put while the list
                    // moves under it. Deliberately WITHOUT a FocusRequester — the keyboard does not
                    // come up on its own — and without an IME "search" action: this filters what is
                    // already on screen and asks the server nothing.
                    Box(Modifier.fillMaxWidth()) {
                        if (folderQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.inbox_filter_folders),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Bounded, for the reason spelled out in InboxScreen's copy: a
                                // Box is as tall as its tallest child.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = MailMetrics.s16),
                            )
                        }
                        TextField(
                            value = folderQuery,
                            onValueChange = { folderQuery = it },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                if (folderQuery.isNotEmpty()) {
                                    IconButton(onClick = { folderQuery = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.inbox_clear),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // `weight(1f, fill = false)` on the scroller and nothing on the field, as
                    // in InboxScreen's copy: the unweighted child is measured first.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        if (shownFolderRows.isEmpty() && folderQuery.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.inbox_no_folder_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MailMetrics.s12),
                            )
                        }
                        shownFolderRows.forEach { row ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        movePicker = false
                                        onMove(
                                            loaded.email.copy(
                                                mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                                                accountId = accountId ?: loaded.email.accountId,
                                            ),
                                            // The row's OWN folder, never a rank in the list.
                                            row.folder.id,
                                            // The account chosen on the row, null for the message's own (#189).
                                            moveAccountId,
                                        )
                                        viewModel.chooseMoveAccount(null)
                                    }
                                    .semantics(mergeDescendants = true) { role = Role.Button }
                                    .padding(vertical = MailMetrics.s12),
                            ) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (row.path != null) {
                                    Text(
                                        text = row.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        // Two lines, for the reason spelled out in InboxScreen's
                                        // copy of this row: one line elides at the end, in dp, and
                                        // would cut off the nearest parent at a large font size.
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movePicker = false; viewModel.chooseMoveAccount(null) }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }
    // The unsubscribe confirmation (D7: systematic, and no setting to switch it off). Rendered here
    // rather than next to the banner because both the banner and the overflow entry open it, and they
    // live on either side of the pager boundary.
    val pendingUnsubscribe by viewModel.unsubscribeConfirm.collectAsStateWithLifecycle()
    val leaveOnce = rememberLeaveOnce()
    pendingUnsubscribe?.let { pending ->
        val action = pending.action
        // Named from the options the confirmation was OPENED for, never re-read from the live state:
        // what this dialog says and what the button then does are one and the same thing. Which form
        // of the target each gesture names is decided in :core:data.
        val target = pending.target
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnsubscribeConfirm() },
            title = { Text(stringResource(R.string.message_unsubscribe_confirm_title)) },
            text = {
                // The same Column-with-weight as the external-link dialog above, for the same reason:
                // Material's text slot is a height-bounded box with no scrolling of its own, and an
                // unsubscribe URL would eat the whole slot and leave the button measured at zero
                // height — gone, not merely crowded.
                Column {
                    // Both lines scroll together, inside the SAME bounded box, and now also because
                    // the previewed body is a stranger's text of unknown length.
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = when (action) {
                                UnsubscribeAction.ONE_CLICK ->
                                    stringResource(R.string.message_unsubscribe_confirm_post, target.orEmpty())
                                UnsubscribeAction.MAIL ->
                                    stringResource(R.string.message_unsubscribe_confirm_mail, target.orEmpty())
                                // The full URL, not just the host, like the reader's ordinary
                                // external-link dialog: consenting to "open a page on this host" is
                                // not consenting to open THIS address.
                                UnsubscribeAction.OPEN_PAGE ->
                                    stringResource(R.string.message_unsubscribe_confirm_open, target.orEmpty())
                            },
                        )
                        // The mail path, and only it, sends a TEXT chosen by the sender of the
                        // received message: `?subject=` and `?body=` are used verbatim, under the
                        // account's own identity, with a copy in Sent. Naming only the address
                        // described a narrower gesture than the one being run, so both are shown
                        // here as plain text — never rendered, never a link. They come from
                        // `unsubscribePreview`, which is also what the outbox row is built from.
                        pending.mailPreview?.takeIf { action == UnsubscribeAction.MAIL }?.let { preview ->
                            Spacer(Modifier.height(MailMetrics.s12))
                            Text(
                                text = stringResource(
                                    R.string.message_unsubscribe_confirm_mail_preview,
                                    preview.subject,
                                    preview.body,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (action == UnsubscribeAction.OPEN_PAGE) {
                        // A page load is a hand-off to another app, so it takes the same one-shot
                        // guard every other "leave the app" action does. The URL is the CAPTURED one,
                        // not whatever the live state holds when the button is pressed.
                        val page = pending.options.pageUrl
                        viewModel.dismissUnsubscribeConfirm()
                        if (page != null) leaveOnce { InAppBrowser.openLink(context, Uri.parse(page)) }
                    } else {
                        viewModel.unsubscribe()
                    }
                }) {
                    Text(
                        stringResource(
                            if (action == UnsubscribeAction.OPEN_PAGE) {
                                R.string.message_unsubscribe_open_page
                            } else {
                                R.string.message_unsubscribe
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnsubscribeConfirm() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    // The raw-headers sheet: open only while the VM holds a non-null headers state.
    val headersState by viewModel.headers.collectAsStateWithLifecycle()
    headersState?.let { hs ->
        MessageHeadersSheet(state = hs, onDismiss = { viewModel.dismissHeaders() })
    }
    // The attached-message sheet (a tapped message/rfc822 part): open while the VM holds a state.
    val attachedState by viewModel.attachedMessage.collectAsStateWithLifecycle()
    attachedState?.let { AttachedMessageSheet(state = it, onDismiss = { viewModel.dismissAttachedMessage() }) }
}

/**
 * Read-only raw-headers viewer (#60): the header fields as `Name: value` in monospace, in original
 * order (duplicates kept), scrollable and fully selectable. No parsing or prettifying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageHeadersSheet(
    state: HeadersState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MailMetrics.s24),
        ) {
            Text(
                stringResource(R.string.message_headers_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
            )
            HorizontalDivider()
            when (state) {
                is HeadersState.Loading -> Box(
                    Modifier.fillMaxWidth().padding(MailMetrics.s32),
                    contentAlignment = Alignment.Center,
                ) { LoadingRing() }
                is HeadersState.Error -> Text(
                    stringResource(R.string.message_headers_error, state.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s16),
                )
                is HeadersState.Loaded -> if (state.headers.isEmpty()) {
                    Text(
                        stringResource(R.string.message_headers_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s16),
                    )
                } else {
                    // Long values (DKIM signatures, Received chains) wrap rather than clip.
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s12),
                            verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                        ) {
                            state.headers.forEach { header ->
                                // JMAP's `headers` value keeps the raw leading space after the
                                // colon; trim it so the line matches the IMAP path.
                                Text(
                                    text = "${header.name}: ${header.value.trim()}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = MailMetrics.t13,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Read-only view of a `message/rfc822` attachment, parsed in memory ([attachedMessageOf]). Nothing
 * here is clickable — its own attachments are named, not opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachedMessageSheet(
    state: AttachedMessageState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MailMetrics.s24),
        ) {
            val subject = (state as? AttachedMessageState.Loaded)?.message?.subject
            Text(
                if (subject.isNullOrEmpty()) stringResource(R.string.message_attached_title) else subject,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
            )
            HorizontalDivider()
            when (state) {
                // Not `) { LoadingRing() }` on its own line: LoadingRingTest pins that exact line to
                // the headers sheet, once per file.
                is AttachedMessageState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(MailMetrics.s32), Alignment.Center) { LoadingRing() }
                is AttachedMessageState.Error -> Text(
                    stringResource(R.string.message_attached_error, state.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s16),
                )
                is AttachedMessageState.Loaded -> SelectionContainer {
                    val message = state.message
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s12),
                        verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                    ) {
                        listOf(
                            R.string.participants_from to message.from,
                            R.string.participants_to to message.to,
                            R.string.participants_cc to message.cc,
                            R.string.compose_forward_date to message.date,
                        ).forEach { (label, value) ->
                            if (!value.isNullOrEmpty()) {
                                Text(
                                    text = "${stringResource(label)}: $value",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (message.body.isEmpty()) {
                            Text(
                                stringResource(R.string.message_attached_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (message.html != null) {
                            // HTML shown as HTML: the message's own markup, or its text wrapped and linkified.
                            HtmlFragmentView(
                                message.html,
                                Modifier.fillMaxWidth().height(MailMetrics.s256 * 2),
                            )
                        } else {
                            Text(message.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (message.attachmentNames.isNotEmpty()) {
                            Text(
                                stringResource(R.string.message_attached_files, message.attachmentNames.joinToString(", ")),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One page of the reading view: the conversation body (header overlay + WebView) for a single list
 * entry. The toolbar and the Reply/Forward bar are NOT here — they are fixed chrome at the pager
 * level (#62).
 */
@Composable
private fun MessageContent(
    viewModel: MessageViewModel,
    emailId: String,
    accountId: String?,
    /** True once the reader has settled on this page — see the latch in [MessagePage]. */
    everActive: Boolean,
    onComposeTo: (address: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val calendar by viewModel.calendar.collectAsStateWithLifecycle()
    val ownMessage by viewModel.ownMessage.collectAsStateWithLifecycle()
    val deliveredTo by viewModel.deliveredTo.collectAsStateWithLifecycle()
    val crypto by viewModel.crypto.collectAsStateWithLifecycle()
    val unsubscribe by viewModel.unsubscribe.collectAsStateWithLifecycle()
    val unsubscribeState by viewModel.unsubscribeState.collectAsStateWithLifecycle()
    // The read-receipt question (#148) and where an accepted one got to. The OFFER decides whether
    // there is a strip at all, and it is null unless the switch is on AND the reader settled on a
    // message that was unread AND the sender asked — see `offeredReadReceipt`.
    val readReceiptOffer by viewModel.readReceiptOffer.collectAsStateWithLifecycle()
    val readReceiptState by viewModel.readReceiptState.collectAsStateWithLifecycle()
    // OpenKeychain's passphrase/key dialogs round-trip through this launcher.
    val pgpLauncher = rememberPgpInteractionLauncher { data ->
        if (data != null) viewModel.decrypt(data) else viewModel.cancelDecrypt()
    }
    val stripTracking by viewModel.stripTracking.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    val replyBarEnabled by viewModel.replyBar.collectAsStateWithLifecycle()
    // Per-message manual override, latched on `everActive` too: this is the SECOND producer of the
    // remote-image answer (`manualShow || senderAllowed`), so closing the allowlist half below and
    // leaving this one open closes nothing — a WARMED page composes the same live button, and the
    // only reset lives in `load()`, which never runs there.
    // On this `val`, never on the `showRemoteImages(...)` call: ReaderBodyTest counts that line.
    val manualShowRaw by viewModel.manualShowImages.collectAsStateWithLifecycle()
    val manualShow = everActive && manualShowRaw
    val senderEmail = (state as? MessageState.Loaded)?.email?.from?.firstOrNull()?.email
    // `everActive` first, and on THIS line rather than on `blockRemote`: `senderAllowed` is the
    // single producer of the answer, so gating it here closes the rendered page, the printed document
    // and `shouldInterceptRequest` in one move. Allowing a sender from the open message must not let
    // the neighbour beside it fetch his pictures and report that it was opened.
    val senderAllowed = everActive && senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = showRemoteImages(manualShow, senderAllowed)
    // The per-sender filter rule offered from the participants panel. The Trash is named from the
    // account's OWN cached folder list (no network), the script state is read once when the panel
    // opens, and which of the four answers all that makes is senderRuleEntry()'s.
    // The message's tags (parts three and five). BOTH kinds, because JMAP has two: the mailboxes
    // this message belongs to (`mailboxIds` — a SET, which is what a label is on this fleet's
    // server, where every drawer category is a mailbox) and its user keywords (`keywords` without
    // the `$` prefix, which mark a message and file nothing).
    val messageMailboxIds by viewModel.mailboxIds.collectAsStateWithLifecycle()
    val currentMailboxId by viewModel.mailboxId.collectAsStateWithLifecycle()
    val senderRules by viewModel.senderRules.collectAsStateWithLifecycle()
    val accountAddresses by viewModel.accountAddresses.collectAsStateWithLifecycle()
    // The header's arrival line counts IDENTITIES, not `accountAddresses`: the latter folds in the
    // login, which is an authentication identifier and not an address.
    val identityAddresses by viewModel.identityAddresses.collectAsStateWithLifecycle()
    val accountMailboxes by viewModel.accountMailboxes.collectAsStateWithLifecycle()
    val senderRuleStatus by viewModel.senderRuleStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(senderRuleStatus) {
        val status = senderRuleStatus ?: return@LaunchedEffect
        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
        viewModel.clearSenderRuleStatus()
    }
    // The word a failed action on the OPEN message leaves behind (#99). A toast and not the error
    // screen: the message is still there and still readable.
    // It belongs HERE, outside every menu and dialog: moved inside one, it is disposed the moment
    // the menu closes on the tap that caused the failure, and the refusal is never shown at all.
    val actionStatus by viewModel.actionStatus.collectAsStateWithLifecycle()
    LaunchedEffect(actionStatus) {
        val status = actionStatus ?: return@LaunchedEffect
        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
        viewModel.clearActionStatus()
    }
    // "Save" on an attachment row. Declared HERE, outside every section, menu and loop: registered
    // further down it is disposed the moment its host recomposes away — the header rebuilds on every
    // settle — and the picker's result has nowhere to land. The three keys are rememberSaveable
    // because the picker is another activity.
    // The MIME type is "*/*", fixed ONCE: a CreateDocument contract freezes its type at
    // registration. What types the file is its extension, carried by the proposed name.
    var saveAttachmentFor by rememberSaveable { mutableStateOf("") }
    var saveAttachmentPart by rememberSaveable { mutableStateOf("") }
    var saveAttachmentName by rememberSaveable { mutableStateOf("") }
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        // A cancelled picker (null) does nothing — no word, no file.
        if (uri != null) viewModel.saveAttachment(uri, saveAttachmentName, saveAttachmentFor, saveAttachmentPart)
    }
    // Print (the overflow menu's last entry). The document is built HERE, not in the menu, because
    // this is where the three decisions the reader took live — the body it renders, the reading mode,
    // and whether remote images are shown. Every string is resolved in the composable's body and
    // captured: the effect runs outside composition.
    val printRequested by viewModel.printRequested.collectAsStateWithLifecycle()
    val printLabels = PrintLabels(
        from = stringResource(R.string.participants_from),
        to = stringResource(R.string.participants_to),
        cc = stringResource(R.string.participants_cc),
        date = stringResource(R.string.compose_forward_date),
    )
    val printNoSubject = stringResource(R.string.message_no_subject)
    val printNoContent = stringResource(R.string.message_no_content)
    val appName = stringResource(R.string.app_name)
    LaunchedEffect(printRequested) {
        if (!printRequested) return@LaunchedEffect
        // Consumed first, whatever happens next: a request that stayed raised would fire again on the
        // next recomposition, and on the next message.
        viewModel.printConsumed()
        val msg = messages.firstOrNull() ?: return@LaunchedEffect
        val full = msg.body ?: return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        // The date the header on screen shows, read through a local so the neighbouring lint on the
        // header's own date line is not tripped by a line that is not the one it pins.
        val receivedAt = msg.header.receivedAt
        val header = PrintHeader(
            subject = (full.subject ?: msg.header.subject)?.takeIf { it.isNotBlank() } ?: printNoSubject,
            from = printAddresses(full.from),
            to = printAddresses(full.to),
            cc = printAddresses(full.cc),
            date = formatFull(receivedAt),
        )
        val body = readerBody(full, printNoContent)
        val doc = buildPrintDocument(header, printLabels, body, msg.inlineImages)
        printDocument(activity, doc, printJobName(header.subject, appName), blockRemote = !showRemote)
    }
    // Decided HERE, once, and handed down already decided — the header renders, it does not judge.
    // An id with no mailbox to name it is dropped rather than drawn raw, and the folder the message
    // was OPENED from is left out: repeating it to a reader standing in it is noise, while every
    // other mailbox is the news the row exists to carry.
    val tagEmail = (state as? MessageState.Loaded)?.email
    // See the label sheet: the same resolution, for the same reason, and here the `remember` below is
    // the proof it cannot be done inside — a `remember` block is not a composable context.
    val mailboxNames = accountMailboxes.associate { it.id to mailboxDisplayName(it.role, it.name) }
    val tags = remember(messageMailboxIds, accountMailboxes, tagEmail, currentMailboxId, mailboxNames) {
        messageTags(
            mailboxIds = messageMailboxIds,
            mailboxes = accountMailboxes,
            keywords = tagEmail?.keywords.orEmpty(),
            currentMailboxId = currentMailboxId,
            nameOf = { mailboxNames[it.id] ?: it.name },
        )
    }
    // The sender panel's routing/identity rows. Decided here, and only from what the message
    // ACTUALLY carries: the two timestamps (and the second only when it disagrees with the first),
    // Return-Path, Authentication-Results, List-Id, Message-ID. `metadataHeaders` is empty until the
    // panel has been opened once, so on a message nobody taps the sender of, this costs nothing.
    val metadataHeaders by viewModel.metadataHeaders.collectAsStateWithLifecycle()
    val metadataEmail = (state as? MessageState.Loaded)?.email
    val metadata = remember(metadataEmail, metadataHeaders) {
        if (metadataEmail == null) {
            emptyList()
        } else {
            messageMetadata(
                email = metadataEmail,
                headers = metadataHeaders,
                receivedAtMillis = parseIsoMillis(metadataEmail.receivedAt),
                sentAtMillis = parseHeaderDateMillis(sentAtHeader(metadataHeaders)),
                formatTime = { millis -> MailDates.formatFull(java.time.Instant.ofEpochMilli(millis).toString()) },
            )
        }
    }
    // The full message information (#1661): what a tap on the subject opens and what "Copy all"
    // copies. Decided HERE like the rows above and from the same on-demand header fetch, so it adds
    // no property to the fetch every message pays for. Every tag is named, the folder the message
    // was opened from included: this block is where the complete membership is read.
    val detailsProjectLabel = stringResource(R.string.message_tags_project)
    val detailsMetaLabel = stringResource(R.string.message_tags_meta)
    val details = remember(
        metadataEmail, metadataHeaders, messageMailboxIds, accountMailboxes, mailboxNames,
        detailsProjectLabel, detailsMetaLabel,
    ) {
        if (metadataEmail == null) {
            emptyList<DetailSection>()
        } else {
            // Guarded as a whole: a message no rule here anticipated shows fewer details, never a crash.
            runCatching {
                val memberships = messageMailboxIds.ifEmpty {
                    (metadataEmail.mailboxIds.filterValues { it }.keys + listOfNotNull(metadataEmail.mailboxId)).toSet()
                }
                messageDetails(
                    email = metadataEmail,
                    headers = metadataHeaders,
                    tags = everyTagOf(metadataEmail, memberships, accountMailboxes) { mailboxNames[it.id] ?: it.name },
                    mailboxIds = memberships,
                    mailboxNames = mailboxNames,
                    projectLabel = detailsProjectLabel,
                    metaLabel = detailsMetaLabel,
                    formatTime = { millis -> MailDates.formatFull(java.time.Instant.ofEpochMilli(millis).toString()) },
                    formatSize = { bytes -> formatSize(bytes) },
                )
            }.getOrDefault(emptyList<DetailSection>())
        }
    }
    // THE READING ROW (#293), the owner's "reading view with the ai icons below the tags + show
    // images + show as plain text ICONS". Built HERE, where this page's ViewModel and the reading
    // decisions already live, and handed down as ONE slot drawn under the tags. Always drawn —
    // Translate is always on READ — so the header's measured height does not change when an icon
    // comes or goes: that height keys the body document, and a row that appeared would reload the
    // message under the reader.
    //
    // The reading-mode verdict is this page's own: the same function the overflow asks, over the
    // same localised lines the page renders (resolved above for the printer).
    val textTools = LocalTextToolRunner.current
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val readerSummary by viewModel.summary.collectAsStateWithLifecycle()
    val replyAllWith = LocalReplyAllWith.current
    val readingEmail = (state as? MessageState.Loaded)?.email
    val clipboard = LocalClipboardManager.current
    val readingActions: @Composable () -> Unit = {
        TextToolIconRow(
            surface = textTools.surface,
            // Show Original / Show Translated, right beside Translate where it is found: greyed until
            // a translation exists, one tap flips the page both ways.
            afterTranslate = {
                IconButton(enabled = translation.exists, onClick = viewModel::toggleTranslated) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(
                            if (translation.shown) R.string.message_show_original else R.string.message_show_translated,
                        ),
                    )
                }
            },
            enabled = !translation.running && !readerSummary.running,
            skip = { it == TextTool.RESUME && messages.firstOrNull()?.body == null },
            trailing = {
                // The row reads "Translate Resume | Show images | Copy Code" (#438): the literal `|`
                // separates the AI group from the display group and the display group from Copy
                // Code. The display group exists only when its icon is honest to draw; Copy Code is
                // always its own group.
                if (!showRemote) {
                    ReadingGroupSeparator()
                    IconButton(onClick = viewModel::showImagesOnce) {
                        Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.message_show_images))
                    }
                }
                ReadingGroupSeparator()
                IconButton(onClick = { copyVerificationCodeOrSayNone(clipboard, context, readingEmail) }) {
                    CopyCodeIcon()
                }
            },
        ) { tool ->
            readingEmail?.let { runReaderTool(viewModel, tool, it, printNoContent) }
        }
    }
    val senderRule = SenderRuleOffer(
        entryFor = { isSender, address ->
            senderRuleEntry(
                isSender,
                trashFilePath(accountMailboxes),
                senderRules,
                address,
                accountAddresses,
            )
        },
        onOpened = viewModel::loadSenderRules,
        onBlock = viewModel::blockSender,
    )

    // The text this page renders, and the quote-free text a summary is made from. Once the body is
    // there the ViewModel brings back whatever is kept for it (translation, summary) and does what
    // Configs switched on - nothing here decides that, it only says WHICH text.
    val hasBody = messages.firstOrNull()?.body != null
    val readerFragment = remember(readingEmail, hasBody, printNoContent) {
        readingEmail?.takeIf { hasBody }
            ?.let { readerBody(it, printNoContent).fragment }
    }
    LaunchedEffect(readerFragment) {
        val email = readingEmail
        if (readerFragment != null && email != null) {
            viewModel.onReaderOpened(readerFragment, receivedTextToolSource(email))
        }
    }
    // The engine's reason when a translation fails: a word on screen, not a dialog.
    val translationError = translation.error
    LaunchedEffect(translationError) {
        if (translationError != null) {
            Toast.makeText(context, translationError, Toast.LENGTH_LONG).show()
            viewModel.dismissTranslationError()
        }
    }
    CompositionLocalProvider(
        LocalReaderSummary provides ReaderSummaryUi(
            readerSummary,
            onReplyAll = { text -> replyAllWith(emailId, accountId, text) },
            dismissError = viewModel::dismissSummaryError,
        ),
    ) {
    Box(Modifier.fillMaxSize()) {
        if (translation.needsSource && readerFragment != null) {
            SourceLanguagePicker(
                Modifier.align(Alignment.TopCenter),
                onPick = { viewModel.translateNow(readerFragment, it) },
                onDismiss = viewModel::dismissTranslationError,
            )
        }
        when (val s = state) {
            is MessageState.Loading -> LoadingRing(Modifier.align(Alignment.Center))
            is MessageState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(MailMetrics.s24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MailMetrics.s12),
            ) {
                Text(
                    stringResource(R.string.message_load_error, s.message),
                    color = MaterialTheme.colorScheme.error,
                )
                if (s.signIn) {
                    val openAccountSettings = LocalOpenAccountSettings.current
                    Button(onClick = { openAccountSettings(accountId) }) {
                        Text(stringResource(R.string.gw_sign_in_again))
                    }
                }
                Button(onClick = { viewModel.load(emailId, accountId, settled = true) }) {
                    Text(stringResource(R.string.message_retry))
                }
            }
            is MessageState.Loaded -> ConversationBody(
                messages = messages,
                blockRemote = !showRemote,
                // Not the same question as `blockRemote`: this one says whether the reader ever had
                // anything held back on this page, and it is read ONCE.
                senderAllowed = senderAllowed,
                stripTracking = stripTracking,
                confirmLinks = confirmLinks,
                attachmentStatus = attachmentStatus,
                onOpenAttachment = viewModel::openAttachment,
                onScanAttachment = viewModel::scanAttachment,
                onSaveAttachment = { part, ownerId ->
                    saveAttachmentFor = ownerId
                    saveAttachmentPart = part.partId ?: part.blobId ?: ""
                    saveAttachmentName = attachmentFileName(part.name)
                    saveAttachmentLauncher.launch(saveAttachmentName)
                },
                calendar = calendar,
                onRespondToInvite = viewModel::respondToInvite,
                textZoom = messageTextSize.zoom,
                replyBarEnabled = replyBarEnabled,
                onBarVisibleChanged = viewModel::setReplyBarVisible,
                onComposeTo = onComposeTo,
                senderRule = senderRule,
                showRecipients = ownMessage,
                deliveredTo = deliveredTo,
                // Which of the reader's own addresses this arrived on, as the header's third line
                // (#180). Decided HERE and once, like the "Original sender" line: the guard is a
                // plain function a unit test executes, and the header only paints the answer.
                // `ownMessage` is the SAME value that turns the line above into "To: …".
                deliveredToLine = deliveredToInHeader(deliveredTo, identityAddresses, ownMessage),
                crypto = crypto,
                onCryptoAction = {
                    when (val c = crypto) {
                        is CryptoUiState.NeedsInteraction -> pgpLauncher(c.pendingIntent)
                        else -> viewModel.decrypt()
                    }
                },
                unsubscribe = unsubscribe,
                unsubscribeState = unsubscribeState,
                // The banner never acts on its own: it opens the same confirmation the overflow entry
                // does (D7).
                onUnsubscribe = viewModel::askUnsubscribe,
                readReceiptOffer = readReceiptOffer,
                readReceiptState = readReceiptState,
                // The two ends of the one gesture, and they must not be swapped: the first queues
                // mail to a stranger, the second sends NOTHING and takes the question away.
                onSendReadReceipt = viewModel::sendReadReceipt,
                onDeclineReadReceipt = viewModel::declineReadReceipt,
                tags = tags,
                metadata = metadata,
                onSenderPanelOpened = viewModel::loadMetadataHeaders,
                details = details,
                readingActions = readingActions,
                translatedFragment = translation.fragmentFor(readerFragment),
                translating = translation.running,
            )
        }
    }
    }
}

/**
 * How long a present-but-not-yet-revealed body may stay hidden behind the spinner before the reader
 * shows it anyway. Comfortably past the normal path, so a legitimately slow body still reveals
 * itself the accurate way; it only catches a body whose height report was lost for good.
 */
private const val BODY_REVEAL_FAILSAFE_MS = 2_500L

@Composable
private fun ConversationBody(
    messages: List<ThreadMessage>,
    blockRemote: Boolean,
    /** Whether this sender is on the image allowlist, i.e. nothing was ever held back here. */
    senderAllowed: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    onScanAttachment: (EmailBodyPart, String) -> Unit,
    onSaveAttachment: (EmailBodyPart, String) -> Unit,
    calendar: CalendarInvite?,
    onRespondToInvite: (String) -> Unit,
    textZoom: Int,
    /** Whether the bottom Reply/Forward bar is wanted at all (#63). It gates the bar's visibility and
     *  the blank the document reserves for it, and NOTHING else. */
    replyBarEnabled: Boolean,
    onBarVisibleChanged: (Boolean) -> Unit,
    onComposeTo: (address: String) -> Unit,
    senderRule: SenderRuleOffer,
    showRecipients: Boolean = false,
    deliveredTo: String? = null,
    /** The header's arrival line, already decided by [deliveredToInHeader] at the call site: the
     *  address to name, or null for no line. Nothing is re-judged on the way down. */
    deliveredToLine: String? = null,
    crypto: CryptoUiState = CryptoUiState.None,
    onCryptoAction: () -> Unit = {},
    unsubscribe: UnsubscribeOptions? = null,
    unsubscribeState: UnsubscribeState = UnsubscribeState.Idle,
    onUnsubscribe: () -> Unit = {},
    readReceiptOffer: ReadReceiptRequest? = null,
    readReceiptState: ReadReceiptState = ReadReceiptState.Idle,
    onSendReadReceipt: () -> Unit = {},
    onDeclineReadReceipt: () -> Unit = {},
    /** The message's tags, already decided by [messageTags] where the ViewModel is in scope: its
     *  mailbox memberships and its user keywords, as one list that says which each one is. Decided
     *  ONCE, up there, so the chip row here and the label sheet on the toolbar cannot answer "what
     *  is a tag" differently. Declared LAST and passed by name — everything above it up to
     *  `onCryptoAction` is positional, so an insertion higher up shifts `crypto` by one. */
    tags: List<MessageTag> = emptyList(),
    /** The sender panel's routing/identity rows, already decided by [messageMetadata] where the
     *  ViewModel is in scope. Empty until [onSenderPanelOpened] has been called and answered. */
    metadata: List<MetadataRow> = emptyList(),
    /** Pull the routing headers, once, when the sender panel is opened — never when a message is. */
    onSenderPanelOpened: () -> Unit = {},
    /** The full message information the subject opens (#1661), already decided by [messageDetails]. */
    details: List<DetailSection> = emptyList(),
    /** The reading icon row, drawn by [MessageHeader] under the tags (#293). Built by MessageContent. */
    readingActions: @Composable () -> Unit = {},
    /** The page's text translated in place, or null for the message as it arrived. */
    translatedFragment: String? = null,
    /** An in-place translation is running: a thin bar at the top of the page says so. */
    translating: Boolean = false,
) {
    val msg = messages.firstOrNull() ?: return
    val full = msg.body
    val density = LocalDensity.current
    // The body WebView OWNS all vertical scroll. It fills the viewport (so Blink culls offscreen
    // tiles — #5) and there is no outer Compose vertical scroll: a sideways drag reaches the pager
    // (#6) only under one touch slop of sideways travel, never on two fingers (#152). The header
    // overlays and collapses with the scroll; the bar overlays the bottom and takes no scroll space.
    // Both start HIDDEN and are revealed only once the body has laid out: seeded `!hasBody` the bar
    // flashed in on the header-only frame — a blink with "Remove animations" on (#63). The rest of
    // #63 was two writers deciding the same value from geometry measured at different instants, so a
    // later, larger measurement pulled a bar already on screen back down. [BarReveal] folds both
    // reports and is the one place the ordering is decided; it is a plain holder, NOT Compose state,
    // because it tracks the live scroll offset and observing that recomposes once per frame.
    var bodyReady by remember(msg.id) { mutableStateOf(false) }
    val barReveal = remember(msg.id) { BarReveal() }
    var showBar by remember(msg.id) { mutableStateOf(false) }
    // The VISIBLE Reply/Forward bar is fixed chrome at the pager level (#62): this page only reports
    // whether its state wants the bar. The invisible measuring copy below stays in-page — it only
    // reserves the bar's height in the document.
    val barVisible = replyBarVisible(replyBarEnabled, bodyReady, showBar)
    LaunchedEffect(barVisible) { onBarVisibleChanged(barVisible) }
    // Measured header height (device px) and the live body scroll offset. scrollY is read only in the
    // layout phase, so updating it every scroll frame re-lays-out the header WITHOUT a recomposition.
    var headerHeightPx by remember(msg.id) { mutableIntStateOf(0) }
    // The body's own viewport height (device px), measured in the SAME layout pass as the header. It
    // is the denominator of both document spacers: they are reserved in `vh`, one hundredth of this,
    // which is the only unit the page's own scale does not change. See [bodySpacerCss].
    var bodyViewportPx by remember(msg.id) { mutableIntStateOf(0) }
    val scrollY = remember(msg.id) { mutableIntStateOf(0) }
    var spinnerDue by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id) { delay(500); spinnerDue = true }
    // Failsafe reveal. [bodyReady] is driven by ONE height poll started by onPageFinished; a load
    // superseded before it finishes never delivers that callback and nothing re-arms the poll, so the
    // body stayed at alpha 0 for the life of the page. It hit the page the reader OPENS ON most,
    // whose OpenPGP auto-decrypt resizes the header and re-keys the document. Past a grace period
    // show it regardless: a late reveal is a blink, a body that never arrives is unreadable mail.
    LaunchedEffect(msg.id, full != null) {
        if (full == null) return@LaunchedEffect
        delay(BODY_REVEAL_FAILSAFE_MS)
        bodyReady = true
    }
    val revealThresholdPx = with(density) { MailMetrics.s4.roundToPx() }
    // The body reserves the overlaying bar's measured height plus a little clearance, so the bar never
    // covers the last line when it reveals. A default until measured avoids any cut on the first frame.
    var barHeightPx by remember { mutableIntStateOf(0) }
    // Zero when the bar is switched off: the blank is a DIV inside the document, so leaving it there
    // would end every message with a strip of white under nothing (#63). The fallback height and the
    // clearance live inside bodyBottomInsetPx, where a test can exercise them: as two `val`s here they
    // could be swapped, putting ~72 dp of white at the end of every message with nothing to see.
    val bottomInsetPx = bodyBottomInsetPx(replyBarEnabled, barHeightPx, density.density)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { bodyViewportPx = it.height }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Invisible, no-op copy of the bar, used ONLY to measure its height up front so the body
        // reserves the right space from the first frame. It is the bottom-most child, so the WebView
        // above it takes all touches.
        // Composed only when the bar is switched on (#63): alpha(0f) hides it from the eye and not
        // from the finger, so with the setting OFF and no body on top of it a band the height of a bar
        // it was told not to show would sit at the bottom taking taps.
        if (replyBarEnabled) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(0f)
                    .onSizeChanged { barHeightPx = it.height }
                    .clearAndSetSemantics {},
            ) {
                ReplyForwardBar {}
            }
        }
        if (full != null) {
            val scheme = MaterialTheme.colorScheme
            val dark = scheme.surface.luminance() < 0.5f
            val emailTheme = EmailTheme(
                background = scheme.surface.toCssHex(),
                text = scheme.onSurface.toCssHex(),
                link = scheme.primary.toCssHex(),
                dark = dark,
                eink = LocalAppStyle.current == AppStyle.CLOUD_EINK,
            )
            // The document carries a transparent TOP spacer of the header's height, so the collapsing
            // header overlays blank space and the content begins right below it. We wait for BOTH
            // measurements before loading, so the body loads ONCE with the two right spacers.
            if (headerHeightPx > 0 && bodyViewportPx > 0) {
                // Reserved as a SHARE OF THE VIEW (vh), not as a count of CSS pixels: a newsletter
                // with its own <meta viewport> lays out at a scale of its own, and a px count then
                // paints fewer screen pixels than the opaque header covers — message hidden under the
                // header, unreachable by scrolling (#171). A scale applied without a re-layout (a
                // pinch, or shrink-to-fit) is out of reach of any CSS length — see [bodySpacerCss].
                val topSpacerCss = bodySpacerCss(headerHeightPx, bodyViewportPx)
                // Bottom spacer is a real DOCUMENT element (scrollable content), NOT WebView view
                // padding: view padding with clipToPadding CLIPS the last lines instead of letting
                // them scroll above it. In dark mode it is transparent so it shows the native surface.
                val bottomSpacerCss = bodySpacerCss(bottomInsetPx, bodyViewportPx)
                // The "derived text" and "no content" lines are resolved here, where a Composable can
                // read resources, and handed to the document already localised.
                val noContent = stringResource(R.string.message_no_content)
                // The quote-fold button's label, resolved here for the same reason as the two
                // above. A locale change recreates the activity, so it is safe in the key below.
                val quoteLabel = stringResource(R.string.message_quoted_text)
                val deceptiveLinkLabel = stringResource(R.string.message_link_goes_to)
                // The two spacers enter the key as the LENGTHS themselves, which depend on two
                // screen measurements only — never on the page's scale. Anything a pinch can change
                // must stay out of this key: it would rebuild the document under the reader's finger,
                // flash white, send her back to the top and reset the zoom, in a loop.
                val html = remember(
                    full, msg.inlineImages, emailTheme, topSpacerCss, bottomSpacerCss,
                    noContent, quoteLabel, deceptiveLinkLabel,
                    translatedFragment,
                ) {
                    buildHtmlDocument(
                        full, msg.inlineImages, emailTheme, topSpacerCss, bottomSpacerCss,
                        noContent, quoteLabel, deceptiveLinkLabel,
                        translatedFragment = translatedFragment,
                    )
                }
                EmailWebView(
                    html = html,
                    blockRemote = blockRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    backgroundColor = scheme.surface.toArgb(),
                    textZoom = textZoom,
                    // ONE reveal, not two (#63). [resting] is the geometry measured by the height poll
                    // itself, non-null only from two agreeing readings, so the bar's resting state is
                    // decided in the SAME recomposition that reveals the body. Null means nothing was
                    // learned: report nothing and let the settle poll decide. Both callbacks are
                    // REPORTS, not verdicts — [BarReveal] alone decides.
                    onReady = { resting ->
                        showBar = barReveal.bodyReady(resting, revealThresholdPx)
                        bodyReady = true
                    },
                    onScroll = { m, lastWord ->
                        scrollY.intValue = m.scrollY
                        showBar = barReveal.scrolled(m, revealThresholdPx, lastWord)
                    },
                    // The body she is reading changed height under her own finger — the quote fold.
                    // The scroll offset goes with the verdict: the WebView clamps its own scroll as it
                    // shrinks, and the collapsing header reads this value. This is the ONLY door
                    // allowed to forget the range of the body as it was before the fold.
                    onResized = { m ->
                        scrollY.intValue = m.scrollY
                        showBar = barReveal.resized(m, revealThresholdPx)
                    },
                    modifier = Modifier.fillMaxSize().alpha(if (bodyReady) 1f else 0f),
                )
            }
        }
        // The collapsing header: translated up by how far the body has scrolled (clamped to its own
        // height). It is opaque and drawn ON TOP of the body, covering the document's top spacer.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset { IntOffset(0, -minOf(scrollY.intValue, headerHeightPx)) }
                .onSizeChanged { headerHeightPx = it.height }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            MessageHeader(
                msg, full, attachmentStatus, onOpenAttachment, onScanAttachment, onSaveAttachment, calendar, onRespondToInvite,
                onComposeTo, senderRule, showRecipients, deliveredTo, crypto, onCryptoAction,
                deliveredToLine = deliveredToLine,
                unsubscribe = unsubscribe,
                unsubscribeState = unsubscribeState,
                onUnsubscribe = onUnsubscribe,
                readReceiptOffer = readReceiptOffer,
                readReceiptState = readReceiptState,
                // Named for the reason given at the strip's own call site: two lambdas of the same
                // type, one of which queues mail to a stranger.
                onSendReadReceipt = onSendReadReceipt,
                onDeclineReadReceipt = onDeclineReadReceipt,
                tags = tags,
                metadata = metadata,
                onSenderPanelOpened = onSenderPanelOpened,
                details = details,
                readingActions = readingActions,
            )
        }
        // The translation is in flight: a thin bar across the top, over the header, until the text
        // is swapped. Nothing else on the page moves.
        if (translating) {
            LinearProgressIndicator(Modifier.align(Alignment.TopStart).fillMaxWidth())
        }
        // Spinner until the body has laid out (cached/prefetched mail beats the 500ms, so none flashes).
        if (full != null && !bodyReady && spinnerDue) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = MailMetrics.s80).padding(MailMetrics.s24).align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                LoadingRing(Modifier.size(MailMetrics.s24), strokeWidth = MailMetrics.s2)
            }
        }
        // No visible Reply/Forward bar here: it is rendered once, fixed, by MessagePager (#62).
    }
}

/** The Reply / Forward action bar: a divider above a full-width Reply button + Forward button. */
@Composable
private fun ReplyForwardBar(onReply: (mode: String) -> Unit) {
    // Opaque surface background: the bar overlays the bottom of the body, so it must hide the content
    // scrolling beneath it.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
            horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12),
        ) {
            // The main reply is REPLY ALL (the toolbar's default action already is); plain Reply stays in
            // the overflow menu.
            Button(
                onClick = { onReply("replyAll") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = null)
                Spacer(Modifier.width(MailMetrics.s8))
                Text(stringResource(R.string.message_reply_all))
            }
            OutlinedButton(
                onClick = { onReply("forward") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null)
                Spacer(Modifier.width(MailMetrics.s8))
                Text(stringResource(R.string.message_forward))
            }
        }
    }
}

/**
 * The collapsing message header (sender, date, star/attachment, plus any attachment list and calendar
 * invite). Rendered as an overlay above the body WebView and translated up with the scroll.
 */
// combinedClickable: the sender row answers a tap (open the panel) and a LONG press (copy the
// address) from one modifier. Two separate modifiers would race for the same gesture.
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageHeader(
    msg: ThreadMessage,
    full: Email?,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    onScanAttachment: (EmailBodyPart, String) -> Unit,
    /** Declared right after [onOpenAttachment] and handed over POSITIONALLY, like everything up to
     *  `onCryptoAction`: two lambdas of the same type, and swapped, the row's name would save and its
     *  icon would open. */
    onSaveAttachment: (EmailBodyPart, String) -> Unit,
    calendar: CalendarInvite?,
    onRespondToInvite: (String) -> Unit,
    onComposeTo: (address: String) -> Unit,
    senderRule: SenderRuleOffer,
    showRecipients: Boolean = false,
    deliveredTo: String? = null,
    crypto: CryptoUiState = CryptoUiState.None,
    onCryptoAction: () -> Unit = {},
    /** The arrival line to paint under the date, already decided by [deliveredToInHeader]. Not
     * re-judged here. Declared AFTER [onCryptoAction] on purpose: the call above passes everything
     *  up to it POSITIONALLY, so a parameter slipped in earlier silently shifts `crypto` by one. */
    deliveredToLine: String? = null,
    unsubscribe: UnsubscribeOptions? = null,
    unsubscribeState: UnsubscribeState = UnsubscribeState.Idle,
    onUnsubscribe: () -> Unit = {},
    readReceiptOffer: ReadReceiptRequest? = null,
    readReceiptState: ReadReceiptState = ReadReceiptState.Idle,
    onSendReadReceipt: () -> Unit = {},
    onDeclineReadReceipt: () -> Unit = {},
    /** The message's tags, already decided by [messageTags] where the ViewModel is in scope: its
     *  mailbox memberships and its user keywords, as one list that says which each one is. Decided
     *  ONCE, up there, so the chip row here and the label sheet on the toolbar cannot answer "what
     *  is a tag" differently. Declared LAST and passed by name — everything above it up to
     *  `onCryptoAction` is positional, so an insertion higher up shifts `crypto` by one. */
    tags: List<MessageTag> = emptyList(),
    /** The sender panel's routing/identity rows, already decided by [messageMetadata] where the
     *  ViewModel is in scope. Empty until [onSenderPanelOpened] has been called and answered. */
    metadata: List<MetadataRow> = emptyList(),
    /** Pull the routing headers, once, when the sender panel is opened — never when a message is. */
    onSenderPanelOpened: () -> Unit = {},
    /** The full message information the subject opens (#1661), already decided by [messageDetails]
     *  where the ViewModel is in scope. Passed by name. */
    details: List<DetailSection> = emptyList(),
    /** The reading icon row (#293), drawn under the tags. Declared LAST and passed by name. */
    readingActions: @Composable () -> Unit = {},
) {
    val sender = msg.header.from.firstOrNull()
    // The user's own message (Sent/Drafts, or sent under one of the account's identities): the sender
    // is yourself, so the header line names the recipients instead (#59). Falls back to the sender
    // while no recipients are known.
    val recipients = if (showRecipients) msg.header.to.ifEmpty { full?.to.orEmpty() } else emptyList()
    val recipient = recipients.firstOrNull()
    val unread = !msg.header.isSeen
    // Tapping the sender opens a panel with every participant (From / To / Cc) and per-contact
    // actions. To/Cc live on the full body, so they populate once it has loaded.
    var showParticipants by remember(msg.id) { mutableStateOf(false) }
    // Tapping the SUBJECT opens the full message information (#1661), the way tapping the sender
    // opens the participants panel.
    var showDetails by remember(msg.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // The full subject, wrapping. The toolbar can only show a truncated single line (it shares its
        // width with the action icons), so the complete text lives here (#44).
        Text(
            text = msg.header.subject?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.message_no_subject),
            style = MaterialTheme.typography.titleLarge,
            fontSize = MailMetrics.t20,
            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.message_details_title)) { showDetails = true }
                .padding(start = MailMetrics.s16, end = MailMetrics.s16, top = MailMetrics.s14),
        )
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        // Tap opens the panel; LONG-PRESS copies the address. Copied in the form a mail client
        // pastes back into a recipient field — `Display Name <a@b.example>`, RFC 5322 name-addr —
        // rather than the bare address, because the name is half of what makes a pasted recipient
        // legible. formatAddress quotes a name holding a comma or a colon: unquoted, "Doe, Jane"
        // pastes as TWO recipients and the second one bounces.
        //
        // The address copied is the one the row SHOWS. On the user's own mail (Sent, Drafts) that
        // row names the recipients, so this copies the recipient; anywhere else it is the sender.
        val copyTarget = recipient ?: sender
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = stringResource(R.string.message_participants_title),
                    onLongClickLabel = stringResource(R.string.message_copy_address),
                    onLongClick = copyTarget?.let {
                        {
                            clipboard.setText(AnnotatedString(formatAddress(it)))
                            Toast.makeText(context, R.string.message_address_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClick = { showParticipants = true },
                )
                .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(
                seed = (recipient ?: sender)?.email ?: "?",
                label = (recipient ?: sender)?.display() ?: "?",
            )
            Spacer(Modifier.width(MailMetrics.s12))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (recipient != null) {
                        stringResource(R.string.list_to_recipients, recipients.joinToString { it.display() })
                    } else {
                        sender?.display() ?: stringResource(R.string.message_unknown_sender)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFull(msg.header.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Which of YOUR addresses this arrived on (#180). Already decided by
                // `deliveredToInHeader` at the call site — null on a single-address account — so
                // nothing is judged here, and the participants panel's own ReceivedAtGroup stays
                // unconditional: the panel is what one opens FOR this.
                if (deliveredToLine != null) {
                    Text(
                        text = stringResource(R.string.message_received_at, deliveredToLine),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 2 lines, not 1. The rule above OriginalSenderGroup — "only what comes from
                        // the sender is clamped" — cannot apply word by word here, since the label and
                        // the address share ONE formatted string. Two lines let the label wrap in de
                        // and ru while a long alias still cannot grow the header without end.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // OpenPGP badges: a lock when the message is/was encrypted, a seal for the signature
            // state. Mirrors the star/paperclip style.
            if (crypto != CryptoUiState.None) {
                val decrypted = (crypto as? CryptoUiState.Decrypted)?.result
                val encrypted = decrypted?.wasEncrypted
                    ?: (crypto !is CryptoUiState.Decrypted) // locked/failed = still sealed
                if (encrypted) {
                    Spacer(Modifier.width(MailMetrics.s8))
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.a11y_pgp_encrypted),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MailMetrics.s18),
                    )
                }
                val sig = decrypted?.signature
                if (sig != null && sig != PgpSignatureState.NONE) {
                    Spacer(Modifier.width(MailMetrics.s6))
                    Icon(
                        Icons.Filled.VerifiedUser,
                        contentDescription = stringResource(R.string.a11y_pgp_signature),
                        tint = signatureTint(sig),
                        modifier = Modifier.size(MailMetrics.s18),
                    )
                }
            }
            // Flagged star and an attachment paperclip, mirroring the message-list row.
            if (msg.header.isFlagged) {
                Spacer(Modifier.width(MailMetrics.s8))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.a11y_flagged),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(MailMetrics.s18),
                )
            }
            if (msg.header.hasAttachment) {
                Spacer(Modifier.width(MailMetrics.s6))
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = stringResource(R.string.a11y_has_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MailMetrics.s18),
                )
            }
        }
        // The message's tags, under the sender (part three). Read-only here: a chip says where this
        // message is and what it is marked with, and the overflow's "Tags and mailboxes" entry is
        // where those are edited. Two kinds are drawn and they are told apart, because they are not
        // the same thing — a mailbox is somewhere the message IS, a keyword is something it is MARKED with, and
        // removing one of each does very different amounts of damage. See MessageTags.
        //
        // Absent entirely when the message has none, which on a single-folder account is every
        // message: an always-present empty strip is a row of furniture.
        //
        // Drawn as TWO sections (#1661), in this order: Project tags, open; then Meta tags, closed
        // behind "See more". Every tag is in exactly one of them — see MessageTagClasses.
        MessageTagSections(tags, msg.id)
        // The reading icon row (#293) — AI Resume, Translate, Show images, plain text — UNDER the
        // tags as asked, and above the Resume box its Resume icon fills.
        readingActions()
        // AI Resume — the summary, in a box under the sender, exactly where the owner asked for it.
        // It draws the SHARED runner's state (the same progress and the same verbatim error Enhance
        // and Translate show) and writes nothing back to the message. See ResumeBox.
        ResumeBox(LocalReaderSummary.current, msg.id)
        // OpenPGP status card: locked/unlock prompt, progress, verdict, or failure.
        if (crypto != CryptoUiState.None) {
            HorizontalDivider()
            PgpStatusCard(crypto, onCryptoAction)
        }
        if (full != null) {
            val attachments = full.fileAttachmentParts()
            if (attachments.isNotEmpty()) {
                HorizontalDivider()
                AttachmentSection(
                    attachments,
                    attachmentStatus,
                    onOpen = { part -> onOpenAttachment(part, msg.id) },
                    onScan = { part -> onScanAttachment(part, msg.id) },
                    onSave = { part -> onSaveAttachment(part, msg.id) },
                )
            }
            // A calendar invite renders as an event preview card above the body.
            if (calendar != null && full.calendarParts().isNotEmpty()) {
                HorizontalDivider()
                CalendarEventCard(
                    invite = calendar,
                    onRespond = onRespondToInvite,
                    onOpenInvitation = {
                        calendar.part?.let { onOpenAttachment(it, calendar.ownerId ?: msg.id) }
                    },
                )
            }
        }
        // The way out of a mailing list, when the sender offers one. Last of the strips, so it never
        // pushes the crypto verdict or a meeting invitation below the fold.
        unsubscribe?.let { options ->
            HorizontalDivider()
            UnsubscribeStrip(options, unsubscribeState, onUnsubscribe)
        }
        // The answer to a sender who asked to be told this was displayed (#148). LAST of the strips,
        // for the unsubscribe strip's reason. Its own presence is `readReceiptStrip`'s decision — null
        // for very nearly every message — so the divider goes with it rather than framing an empty row.
        readReceiptStrip(readReceiptOffer, readReceiptState)?.let { strip ->
            HorizontalDivider()
            // NAMED, both of them, and not as a matter of style: the two lambdas have the same type,
            // so passed positionally a swap in the callee's signature compiles and makes the refusal
            // QUEUE a receipt while the button does nothing.
            ReadReceiptStrip(
                strip,
                onSendReadReceipt = onSendReadReceipt,
                onDeclineReadReceipt = onDeclineReadReceipt,
            )
        }
        HorizontalDivider()
    }
    if (showParticipants) {
        ParticipantsSheet(
            from = msg.header.from,
            to = full?.to ?: emptyList(),
            cc = full?.cc ?: emptyList(),
            // Both off the FULL body, like To and Cc: the cached list row carries neither, so they
            // populate when the fetch lands rather than being wrongly reported as absent.
            bcc = full?.bcc ?: emptyList(),
            // Filtered HERE to the case worth a row: a Reply-To that is simply the sender again
            // says nothing, and a row that says nothing on ordinary mail is a row nobody reads on
            // the one message where it matters.
            replyTo = (full?.replyTo ?: emptyList()).filter { reply ->
                msg.header.from.none { it.email.equals(reply.email, ignoreCase = true) }
            },
            metadata = metadata,
            onOpened = onSenderPanelOpened,
            deliveredTo = deliveredTo,
            // Decided HERE and handed over already decided: the panel renders, it does not judge.
            originalSender = originalSenderToShow(full?.originalSender, msg.header.from),
            onComposeTo = { address -> showParticipants = false; onComposeTo(address) },
            senderRule = senderRule,
            onDismiss = { showParticipants = false },
        )
    }
    if (showDetails) {
        // What MessageContent decided; before the message has settled, what this header itself knows.
        val projectLabel = stringResource(R.string.message_tags_project)
        val metaLabel = stringResource(R.string.message_tags_meta)
        val shown = details.ifEmpty {
            runCatching {
                messageDetails(
                    email = full ?: msg.header,
                    headers = emptyList(),
                    tags = tags,
                    mailboxIds = emptySet(),
                    mailboxNames = emptyMap(),
                    projectLabel = projectLabel,
                    metaLabel = metaLabel,
                    formatTime = { millis -> MailDates.formatFull(java.time.Instant.ofEpochMilli(millis).toString()) },
                    formatSize = { bytes -> formatSize(bytes) },
                )
            }.getOrDefault(emptyList())
        }
        MessageDetailsSheet(
            sections = shown,
            // The same on-demand header fetch the participants panel makes, paid when it is opened.
            onOpened = onSenderPanelOpened,
            onDismiss = { showDetails = false },
        )
    }
}

/**
 * The message's tags as the reading panel's two sections (#1661): Project tags first and OPEN,
 * Meta tags second and CLOSED behind "See more". [tagSections] decides which tag is in which, and
 * every tag is in exactly one; a section with no tag is not drawn at all.
 *
 * The open/closed state is kept per message and per section, so opening the Meta tags of one
 * message says nothing about the next one.
 */
@Composable
private fun MessageTagSections(tags: List<MessageTag>, messageId: String) {
    val sections = remember(tags) { tagSections(tags) }
    for (section in sections) {
        var expanded by rememberSaveable(messageId, section.kind) {
            mutableStateOf(tagSectionExpandedByDefault(section.kind))
        }
        MessageTagSection(section.kind, section.tags, expanded, onToggle = { expanded = !expanded })
    }
}

/**
 * One section: a title line that opens and closes it, then the pills. Closed, the title carries
 * the number of tags behind it, and the Meta section says "See more".
 */
@Composable
private fun MessageTagSection(
    kind: TagSectionKind,
    tags: List<MessageTag>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val title = stringResource(
        when (kind) {
            TagSectionKind.PROJECT -> R.string.message_tags_project
            TagSectionKind.META -> R.string.message_tags_meta
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "\u25BE $title" else "\u25B8 $title (${tags.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!expanded && kind == TagSectionKind.META) {
            Spacer(Modifier.width(MailMetrics.s8))
            Text(
                text = stringResource(R.string.message_tags_see_more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (expanded) {
        if (tags.isNotEmpty()) {
            MessageTagRow(tags)
        }
    }
}

/**
 * The message's tags under the sender: where it IS, and what it is MARKED with.
 *
 * The two are drawn differently on purpose. A mailbox pill carries a folder icon because it names
 * somewhere the message can be found; a keyword pill carries a label icon because it names
 * something written on the message. They are the two multi-valued fields JMAP gives a message
 * (`mailboxIds` and `keywords`) and they behave differently under removal, so a reader who cannot
 * tell them apart here will be surprised by the sheet that edits them.
 *
 * READ-ONLY. Editing lives behind the toolbar's tag icon, where removing a mailbox can ask first.
 * A chip with a close button in a header that scrolls under a finger is a mailbox removed by
 * accident.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageTagRow(tags: List<MessageTag>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = MailMetrics.s16, end = MailMetrics.s16, bottom = MailMetrics.s4),
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s6),
        // Wrapped lines used to sit flush against each other, because `FlowRow` separates them by
        // nothing and every gap the eye read as separation was really blank reserved INSIDE the
        // chips. With the pills measuring their own content that separation has to be stated.
        verticalArrangement = Arrangement.spacedBy(MailMetrics.s4),
    ) {
        for (tag in tags) {
            TagPill(tag)
        }
    }
}

/**
 * One tag drawn as a dense pill: its kind icon, then its full label, and nothing else.
 *
 * Deliberately NOT `AssistChip`, which is what this used to be. A chip is a Material `Surface` with
 * an `onClick`, and that `Surface` applies `minimumInteractiveComponentSize` unconditionally — the
 * modifier sits ahead of `clickable(enabled = …)` in the chain, so `enabled = false` suppressed the
 * tap and kept the 48dp reservation. Under a 20dp line of text that is 28dp of blank per line, on a
 * strip whose entire job is to say two or three short words, and it was 60% of the strip's height.
 * The chip's own 32dp container floor plus 8dp/8dp/8dp of internal chrome spent the rest.
 *
 * Nothing here is tappable and nothing here needs to be: editing tags lives behind the toolbar's
 * tag icon, so there is no touch target to protect and no reason to reserve one. The pill is 24dp —
 * a 20dp `labelLarge` line box, unchanged from the chip, inside 2dp of padding.
 *
 * The label keeps the chip's `maxLines = 1` and ellipsis, so a long folder name truncates exactly
 * where it did before; the 10dp of chrome saved per pill is 10dp more of it visible first.
 */
@Composable
private fun TagPill(tag: MessageTag) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = MailMetrics.s6, vertical = MailMetrics.s2),
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (tag.kind) {
                TagKind.MAILBOX -> Icons.Filled.Folder
                TagKind.KEYWORD -> Icons.AutoMirrored.Filled.Label
            },
            contentDescription = stringResource(
                when (tag.kind) {
                    TagKind.MAILBOX -> R.string.message_tag_mailbox
                    TagKind.KEYWORD -> R.string.message_tag_keyword
                },
            ),
            // Full-strength `onSurfaceVariant`, where the disabled chip faded both icon and label to
            // `onSurface` at 38%: a strip nobody can read is not denser, it is just smaller.
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MailMetrics.s14),
        )
        Text(
            tag.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Everything the participants panel needs to offer the per-sender filter rule, in one value so the
 * reader's composables gain ONE parameter rather than three. [entryFor] is [senderRuleEntry] with the
 * screen's state already bound, called here rather than restated.
 */
private class SenderRuleOffer(
    val entryFor: (isSender: Boolean, address: String) -> SenderRuleEntry =
        { _, _ -> SenderRuleEntry.ABSENT },
    val onOpened: () -> Unit = {},
    val onBlock: (address: String) -> Unit = {},
)

/**
 * Slide-up panel listing every participant of the open message, grouped From / To / Cc, each with
 * their full address and actions — and, above them, which of the reader's OWN addresses received it
 * (#81). To/Cc come from the full body, so they are empty until it has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(
    from: List<EmailAddress>,
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    /** Blind copies. Present only on the user's OWN mail — a received message never carries the
     *  Bcc it was sent under, which is the entire point of a Bcc. Shown when it exists, absent
     *  when it does not, exactly like every other row here. */
    bcc: List<EmailAddress>,
    /** Where an answer actually goes, when that is not the sender. See [MetadataLabel.REPLY_TO]. */
    replyTo: List<EmailAddress>,
    /** The routing and identity rows, already decided by [messageMetadata] at the call site: the
     *  timestamps, Return-Path, Authentication-Results, List-Id and Message-ID this message
     *  actually carries, and NOTHING for the ones it does not. An empty "DKIM:" line reads as a
     *  verdict rather than as an absence, which is why this is a list and not a record. */
    metadata: List<MetadataRow>,
    deliveredTo: String?,
    originalSender: EmailAddress?,
    onComposeTo: (address: String) -> Unit,
    senderRule: SenderRuleOffer,
    /** Pull the routing headers. Called once when the panel opens, alongside the filter rule's own
     *  round-trip — never on opening a MESSAGE, which is the common case and pays for neither. */
    onOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The round-trips this panel costs, paid when it is OPENED and not when a message is.
    LaunchedEffect(Unit) { senderRule.onOpened(); onOpened() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MailMetrics.s24),
        ) {
            Text(
                stringResource(R.string.message_participants_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
            )
            // First, because it is the one line the panel is opened for on a multi-alias account:
            // scanning a long To/Cc list for your own address is exactly what this spares (#81).
            ReceivedAtGroup(deliveredTo)
            // Under "Received at" and ABOVE "From", the order the reader asks the panel its question
            // in: what did this arrive on, what does it claim about where it came from, who sent it.
            OriginalSenderGroup(originalSender)
            // isSender is TRUE for the From group and false for the other two, and it is an argument
            // of the decision rather than "whichever group got a callback": a rule on FROM aimed at
            // someone who was merely in Cc is a rule about mail that person has not sent.
            ParticipantGroup(R.string.participants_from, from, isSender = true, onComposeTo, senderRule)
            ParticipantGroup(R.string.participants_to, to, isSender = false, onComposeTo, senderRule)
            ParticipantGroup(R.string.participants_cc, cc, isSender = false, onComposeTo, senderRule)
            // The blind copies, on the user's own mail. A received message carries none — that is
            // what "blind" means — so the group renders nothing there rather than an empty heading.
            ParticipantGroup(R.string.participants_bcc, bcc, isSender = false, onComposeTo, senderRule)
            // Reply-To gets a group of its own rather than a metadata line: it is a PARTICIPANT, it
            // is who a reply reaches, and it is writable-to like the others. Already filtered at the
            // call site to the case where it differs from the sender.
            ParticipantGroup(R.string.participants_reply_to, replyTo, isSender = false, onComposeTo, senderRule)
            MetadataGroup(metadata)
        }
    }
}

/**
 * The routing and identity block: what this message says about its own delivery, so a reader can
 * judge whether it is what it claims to be.
 *
 * Renders EXACTLY the rows it was given and invents none. [messageMetadata] has already dropped
 * every field the message does not carry, because the failure mode here is not a missing row — it
 * is a present, empty one. "Authentication-Results:" with nothing after it reads as a verdict of
 * nothing, and a reader who learns to see that on ordinary mail will not notice it on the one
 * message where the verdict matters.
 *
 * The verdicts are shown VERBATIM, never summarised into a tick or a cross. `Authentication-Results`
 * is the receiving server's own sentence about SPF, DKIM and DMARC; condensing it would be this app
 * passing a judgement it is not in a position to pass, on a header it did not write.
 */
@Composable
private fun MetadataGroup(rows: List<MetadataRow>) {
    if (rows.isEmpty()) return
    HorizontalDivider()
    Text(
        stringResource(R.string.participants_delivery),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
    )
    for (row in rows) {
        Text(
            stringResource(
                when (row.label) {
                    MetadataLabel.RECEIVED_AT -> R.string.metadata_received
                    MetadataLabel.SENT_AT -> R.string.metadata_sent
                    MetadataLabel.REPLY_TO -> R.string.metadata_reply_to
                    MetadataLabel.RETURN_PATH -> R.string.metadata_return_path
                    MetadataLabel.AUTHENTICATION -> R.string.metadata_authentication
                    MetadataLabel.LIST_ID -> R.string.metadata_list_id
                    MetadataLabel.MESSAGE_ID -> R.string.metadata_message_id
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24, top = MailMetrics.s6),
        )
        // Bounded at four lines: Authentication-Results is routinely long and entirely
        // sender-adjacent text, and an unbounded one could push everything below it off the panel.
        Text(
            row.value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24),
        )
    }
    Spacer(Modifier.height(MailMetrics.s8))
}

/**
 * Which of YOUR addresses the message came in on (#81). An account with several aliases has to spot
 * its own address among the others, and this is what decides the identity a reply goes out under. One
 * label, one address, no actions. Absent when no address of the account is named.
 */
@Composable
private fun ReceivedAtGroup(address: String?) {
    if (address.isNullOrBlank()) return
    HorizontalDivider()
    Text(
        stringResource(R.string.participants_received_at),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
    )
    Text(
        address,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24, bottom = MailMetrics.s8),
    )
}

/**
 * What the message SAYS about who wrote it, when a forwarding service relayed it (#160): an alias
 * puts its own address in both `From` and `To`, and nothing on screen then names the author.
 * Rendered from [address], already decided by [originalSenderToShow].
 *
 * No avatar, no menu, no action: this is a claim the message makes about its own origin, not a
 * participant to write to. And the `From` above is not replaced by it.
 */
@Composable
private fun OriginalSenderGroup(address: EmailAddress?) {
    if (address == null) return
    HorizontalDivider()
    // The LABEL is not bounded: it wraps, like `participants_received_at`, already 19 characters in
    // fr and ru. Only what comes from the sender is clamped.
    Text(
        stringResource(R.string.participants_original_sender),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
    )
    // maxLines = 1 + ellipsis on both lines, as [ParticipantRow] clamps a display name: this text is
    // ENTIRELY sender-controlled, and `stripBidiAndControls` lets 0x09 and 0x0A through, so an
    // unbounded Text could be pushed down the panel until the groups under it leave the screen.
    Text(
        address.display(),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24),
    )
    // The bare address under the name, as [ParticipantRow] does — and here it is the whole point: a
    // display name is precisely what a forgery gets right. Omitted when there is no name, since
    // `display()` IS the address then.
    if (!address.name.isNullOrBlank()) {
        Text(
            address.email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24),
        )
    }
    Spacer(Modifier.height(MailMetrics.s8))
}

/** One labelled block (From / To / Cc) in [ParticipantsSheet]; renders nothing when [people] empty. */
@Composable
private fun ParticipantGroup(
    titleRes: Int,
    people: List<EmailAddress>,
    isSender: Boolean,
    onComposeTo: (address: String) -> Unit,
    senderRule: SenderRuleOffer,
) {
    if (people.isEmpty()) return
    HorizontalDivider()
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
    )
    people.forEach { addr -> ParticipantRow(addr, isSender, onComposeTo, senderRule) }
}

/** A single participant: avatar, name + address, add-to-contacts icon, and an overflow menu. */
@Composable
private fun ParticipantRow(
    addr: EmailAddress,
    isSender: Boolean,
    onComposeTo: (address: String) -> Unit,
    senderRule: SenderRuleOffer,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.status_address_copied)
    val noContactsAppMsg = stringResource(R.string.participant_no_contacts_app)
    // Adding to Contacts CREATES something, and the contacts editor is slow enough to come up that a
    // second tap lands while the screen is still ours: without this it filed the same person twice.
    // One latch per row: the next participant is another intention, not a stutter.
    val leaveOnce = rememberLeaveOnce()
    val hasName = !addr.name.isNullOrBlank()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRule by remember { mutableStateOf(false) }
    val entry = senderRule.entryFor(isSender, addr.email)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = addr.email, label = addr.display())
        Spacer(Modifier.width(MailMetrics.s12))
        Column(Modifier.weight(1f)) {
            Text(
                addr.display(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasName) {
                Text(
                    addr.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = {
            leaveOnce {
                val opened = addToContacts(context, addr)
                // No contacts app: say so and keep the button live — nothing was created, so there is
                // nothing to protect against a second tap.
                if (!opened) Toast.makeText(context, noContactsAppMsg, Toast.LENGTH_SHORT).show()
                opened
            }
        }) {
            Icon(
                Icons.Filled.PersonAdd,
                contentDescription = stringResource(R.string.participant_add_to_contacts),
            )
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.a11y_participant_more),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MaterialTheme.shapes.medium,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_compose)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                onClick = { menuOpen = false; onComposeTo(addr.email) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_copy_address)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    clipboard.setText(AnnotatedString(addr.email))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_copy_name_address)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val text = if (hasName) "${addr.name} <${addr.email}>" else addr.email
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            // The per-sender rule, on the sender's row only. Which of the four states this is, is
            // senderRuleEntry()'s answer; all that happens here is a rendering of each. Deliberately
            // NOT in the message's own ⋮: a rule per sender is unconditional, so it would appear on
            // every message ever opened and take that menu to eight permanent entries.
            if (entry != SenderRuleEntry.ABSENT) {
                DropdownMenuItem(
                    enabled = entry == SenderRuleEntry.OFFERED,
                    text = { Text(stringResource(senderRuleLabel(entry))) },
                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                    onClick = { menuOpen = false; confirmRule = true },
                )
            }
        }
    }
    if (confirmRule) {
        // A dialog, and this is the point of putting the gesture here: a list row has nowhere to say
        // what the rule does, and this one says it before anything is written.
        AlertDialog(
            onDismissRequest = { confirmRule = false },
            title = { Text(stringResource(R.string.sender_volume_block_title, addr.email)) },
            text = {
                // The body SCROLLS. Material's text slot is a height-bounded box with no scrolling of
                // its own, and a bare Text in it is CLIPPED — not ellipsised, just cut, with nothing
                // saying more text exists. At the largest font scale this sentence runs to seventeen
                // lines in German and was measured cut mid-word, losing "nothing already received
                // moves" and where the rule can be removed.
                Text(
                    stringResource(
                        R.string.sender_volume_block_body,
                        stringResource(R.string.inbox_settings),
                        stringResource(R.string.settings_filters_title),
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // A verb, not the menu entry's sentence. MEASURED at font_scale 2.0: with the sentence
                // here, this label wrapped to four lines and "Cancel" was drawn INSIDE this button —
                // one tap landing on both, on a write that is permanent and has no Undo. What is
                // certain is that the label must not wrap. The menu entry keeps
                // sender_volume_block, where a full line has room to say what the gesture does.
                TextButton(onClick = { confirmRule = false; senderRule.onBlock(addr.email) }) {
                    Text(stringResource(R.string.sender_volume_block_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRule = false }) {
                    Text(stringResource(R.string.inbox_cancel))
                }
            },
        )
    }
}

/** Fire an ACTION_INSERT contacts intent prefilled with [addr]; false if no app can handle it. */
private fun addToContacts(context: Context, addr: EmailAddress): Boolean = try {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.EMAIL, addr.email)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addr.name?.takeIf { it.isNotBlank() }?.let {
        intent.putExtra(ContactsContract.Intents.Insert.NAME, it)
    }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

@Composable
private fun AttachmentSection(
    attachments: List<EmailBodyPart>,
    status: String?,
    onOpen: (EmailBodyPart) -> Unit,
    onScan: (EmailBodyPart) -> Unit,
    onSave: (EmailBodyPart) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
        Text(
            text = stringResource(R.string.message_attachments, attachments.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(att) }
                    .padding(vertical = MailMetrics.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = MailMetrics.s12).size(MailMetrics.s20),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = att.name ?: stringResource(R.string.message_attachment_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(formatSize(att.size).takeIf { it.isNotEmpty() }, att.type)
                        .joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Save this attachment into a document of her choosing. Beside the row rather than
                // behind a long press, as K-9 and Gmail both put it: the row's body keeps its own
                // tap, so the name opens and the icon saves. The name has maxLines = 1 and an
                // ellipsis and the column above has weight(1f), so a long name shortens instead of
                // pushing this button off the screen.
                IconButton(onClick = { onSave(att) }) {
                    Icon(
                        Icons.Filled.SaveAlt,
                        contentDescription = stringResource(R.string.message_save_attachment),
                    )
                }
                // #461 — scan this image's CONTENT (barcode + OCR) through the ONE shared
                // ImageScanEngine. Images only: a PDF or a .doc may be a picture of nothing, and
                // the engine reads pixels, not formats — a row with no readable pixels must not
                // carry an action that promises the opposite.
                if (att.type?.startsWith("image/") == true) {
                    IconButton(onClick = { onScan(att) }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.message_scan_attachment),
                        )
                    }
                }
            }
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Badge tint for an OpenPGP signature verdict. */
@Composable
private fun signatureTint(state: PgpSignatureState): androidx.compose.ui.graphics.Color =
    when (state) {
        PgpSignatureState.VALID_CONFIRMED -> MaterialTheme.colorScheme.primary
        PgpSignatureState.VALID_UNCONFIRMED,
        PgpSignatureState.SENDER_MISMATCH,
        -> MaterialTheme.colorScheme.tertiary
        PgpSignatureState.KEY_MISSING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

/** One-line human verdict for an OpenPGP signature. */
@Composable
private fun signatureSummary(sig: PgpSignatureState, signer: String?): String? = when (sig) {
    PgpSignatureState.NONE -> null
    PgpSignatureState.VALID_CONFIRMED ->
        stringResource(R.string.message_pgp_sig_valid, signer ?: "?")
    PgpSignatureState.VALID_UNCONFIRMED ->
        stringResource(R.string.message_pgp_sig_unconfirmed, signer ?: "?")
    PgpSignatureState.KEY_MISSING -> stringResource(R.string.message_pgp_sig_missing_key)
    PgpSignatureState.INVALID -> stringResource(R.string.message_pgp_sig_invalid)
    PgpSignatureState.KEY_REVOKED -> stringResource(R.string.message_pgp_sig_revoked)
    PgpSignatureState.KEY_EXPIRED -> stringResource(R.string.message_pgp_sig_expired)
    PgpSignatureState.INSECURE -> stringResource(R.string.message_pgp_sig_insecure)
    PgpSignatureState.SENDER_MISMATCH -> stringResource(R.string.message_pgp_sig_mismatch)
}

/**
 * The OpenPGP status strip above the body: unlock prompt / progress while decrypting, then the verdict
 * or the failure with a retry. Compact — one row plus an optional action button.
 */
@Composable
private fun PgpStatusCard(crypto: CryptoUiState, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s10),
    ) {
        when (crypto) {
            is CryptoUiState.Locked -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MailMetrics.s20),
                )
                Text(
                    text = stringResource(
                        if (crypto.kind == CryptoKind.PGP_SIGNED) {
                            R.string.message_pgp_signed_title
                        } else {
                            R.string.message_pgp_encrypted_title
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (crypto.decrypting) {
                    CircularProgressIndicator(Modifier.size(MailMetrics.s18), strokeWidth = MailMetrics.s2)
                } else {
                    OutlinedButton(onClick = onAction) {
                        Text(
                            stringResource(
                                if (crypto.kind == CryptoKind.PGP_SIGNED) {
                                    R.string.message_pgp_verify
                                } else {
                                    R.string.message_pgp_unlock
                                },
                            ),
                        )
                    }
                }
            }
            is CryptoUiState.NeedsInteraction -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MailMetrics.s20),
                )
                Text(
                    text = stringResource(R.string.message_pgp_encrypted_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onAction) {
                    Text(stringResource(R.string.message_pgp_unlock))
                }
            }
            is CryptoUiState.Decrypted -> {
                val sig = crypto.result.signature
                Icon(
                    if (crypto.result.wasEncrypted) Icons.Filled.Lock else Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = if (crypto.result.wasEncrypted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        signatureTint(sig)
                    },
                    modifier = Modifier.size(MailMetrics.s20),
                )
                Column(Modifier.weight(1f)) {
                    if (crypto.result.wasEncrypted) {
                        Text(
                            text = stringResource(R.string.message_pgp_decrypted),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    signatureSummary(sig, crypto.result.signatureUserId)?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = signatureTint(sig),
                        )
                    }
                }
            }
            is CryptoUiState.Failed -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MailMetrics.s20),
                )
                Text(
                    text = crypto.message
                        ?: stringResource(R.string.message_pgp_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (crypto.message != null) {
                    OutlinedButton(onClick = onAction) {
                        Text(stringResource(R.string.message_retry))
                    }
                }
            }
            CryptoUiState.None -> Unit
        }
    }
}

/**
 * The unsubscribe strip above the body: one button to leave the list, or — once left — one line
 * saying what happened.
 * A button, not a sentence, and the reason is frequency: the two RARE strips are an icon plus a
 * sentence plus an [OutlinedButton], because those are decisions being asked for, while
 * `List-Unsubscribe` now marks commercial mail in general and there is no honest filter to add.
 * The constant height is not tidiness: the header's height keys the body document's `remember`, and
 * the header is where an unsubscribe happens. Only the failure shape grows. R8 once inlined a short
 * @Composable into drawing NOTHING in a release build; do not move this into a file of its own.
 */
@Composable
private fun UnsubscribeStrip(
    options: UnsubscribeOptions,
    state: UnsubscribeState,
    onUnsubscribe: () -> Unit,
) {
    // The label of the button, when there is one to draw — the same decision the overflow entry and the
    // action itself take. `preferredAction` alone would answer for a gesture already made.
    val action = options.preferredAction() ?: return
    val body = unsubscribeStripBody(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No vertical padding of its own: the button's own minimum IS the strip's height, and it
            // is the floor for the two states that draw less.
            .heightIn(min = ButtonDefaults.MinHeight)
            .padding(horizontal = MailMetrics.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (body == UnsubscribeStripBody.DONE) {
            // Done is done: no button left to press, and nothing that could be pressed twice.
            Text(
                text = stringResource(
                    if (state == UnsubscribeState.Queued) {
                        R.string.message_unsubscribe_queued
                    } else {
                        R.string.message_unsubscribe_sent
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val failed = state as? UnsubscribeState.Failed
            if (failed != null) {
                // The one shape allowed to grow: a refusal has to say what happened, and a failure is
                // a retry rather than a dead end — the button comes back beside it.
                Text(
                    text = stringResource(
                        when (failed.reason) {
                            UnsubscribeFailure.REDIRECT -> R.string.message_unsubscribe_failed_redirect
                            UnsubscribeFailure.OFFLINE -> R.string.message_unsubscribe_failed_offline
                            UnsubscribeFailure.UNREACHABLE ->
                                R.string.message_unsubscribe_failed_unreachable
                            UnsubscribeFailure.REFUSED -> R.string.message_unsubscribe_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).padding(end = MailMetrics.s8),
                )
            }
            TextButton(onClick = onUnsubscribe, enabled = body.acts) {
                if (body == UnsubscribeStripBody.SENDING) {
                    // In the icon's place, at the icon's size: the button keeps its height, so the
                    // header does, so the body underneath is not reloaded mid-gesture.
                    CircularProgressIndicator(Modifier.size(MailMetrics.s18), strokeWidth = MailMetrics.s2)
                } else {
                    Icon(
                        Icons.Filled.Unsubscribe,
                        // The sentence this strip used to spend a whole line on, recycled where it
                        // costs no height: the icon was announced as nothing at all before.
                        contentDescription = stringResource(R.string.message_unsubscribe_banner),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MailMetrics.s18),
                    )
                }
                Spacer(Modifier.width(MailMetrics.s8))
                Text(
                    stringResource(
                        when {
                            body == UnsubscribeStripBody.SENDING -> R.string.message_unsubscribe_sending
                            action == UnsubscribeAction.OPEN_PAGE -> R.string.message_unsubscribe_open_page
                            else -> R.string.message_unsubscribe
                        },
                    ),
                )
            }
        }
    }
}

/**
 * The read-receipt strip above the body (#148): the question a sender's `Disposition-Notification-To`
 * raises, and — once answered — where the answer got to.
 * It ASKS, and it is the only thing in this app that can send a receipt. The refusal sends nothing
 * and is written nowhere. The line names WHO would be told, and says *displayed* rather than *read*.
 * There is no "sent" state under any wording, and once queued the line names the subject
 * `sendReadReceipt` handed back.
 * The height does not move BETWEEN THE STATES, and only the failure may grow. That does not claim
 * the strip appearing or leaving leaves the header alone.
 */
@Composable
private fun ReadReceiptStrip(
    // What [readReceiptStrip] decided, HANDED IN: the caller ran the decision once, to know whether to
    // draw this row and its divider at all. Not the offer and the state again — a second reading of
    // the same rule is a rule no test can hold.
    strip: ReadReceiptStrip,
    onSendReadReceipt: () -> Unit,
    onDeclineReadReceipt: () -> Unit,
) {
    val body = strip.body
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No vertical padding of its own: the button's own minimum IS the strip's height.
            .heightIn(min = ButtonDefaults.MinHeight)
            .padding(horizontal = MailMetrics.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (body.names) stringResource(body.line, strip.named) else stringResource(body.line),
            style = MaterialTheme.typography.bodyMedium,
            color = if (body == ReadReceiptStripBody.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            // ONE line in the three ordinary states, and this is a height rule, not typography.
            maxLines = if (body.shape == ReadReceiptStripShape.BUTTON_ROW) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = MailMetrics.s8),
        )
        if (body.declines) {
            // Saying no: the strip goes, and NOTHING leaves. Offered only while the question stands
            // untouched — once a receipt is queued there is nothing left to refuse.
            IconButton(onClick = onDeclineReadReceipt) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.message_read_receipt_decline),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MailMetrics.s18),
                )
            }
        }
        // Drawn in EVERY state, under no condition at all: the same button, in the same place, at the
        // same size, wearing the label of the state it is in. Wrapping it in `if (body.acts)` reads
        // like a tidy-up and is the measured defect — the header would lose the button's height.
        TextButton(
            onClick = onSendReadReceipt,
            enabled = body.acts,
            // A disabled TextButton otherwise takes onSurface at 38 % alpha, and the only confirmation
            // that the answer was queued would be the palest thing in the header.
            colors = ButtonDefaults.textButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            if (body == ReadReceiptStripBody.SENDING) {
                // Inside the button, at the icon's size: the button keeps its height, so the header
                // does, so the body underneath is not reloaded mid-gesture.
                CircularProgressIndicator(Modifier.size(MailMetrics.s18), strokeWidth = MailMetrics.s2)
                Spacer(Modifier.width(MailMetrics.s8))
            }
            Text(
                stringResource(body.button),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * An event preview for a calendar invite, shown above the body. A compact placeholder while the .ics
 * downloads, then the parsed event with an "Add to calendar" action (an ACTION_INSERT intent, no
 * permission). Degrades to "Open invitation" when it cannot be parsed or there is no calendar app.
 */
@Composable
private fun CalendarEventCard(
    invite: CalendarInvite,
    onRespond: (String) -> Unit,
    onOpenInvitation: () -> Unit,
) {
    val context = LocalContext.current
    // "Add to calendar" CREATES something too: the event editor takes a moment to appear, and until
    // this guard a second tap put the same meeting in the calendar twice.
    val leaveOnce = rememberLeaveOnce()
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = MailMetrics.s8).size(MailMetrics.s20),
            )
            Text(
                text = stringResource(R.string.calendar_invite),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (invite.loading) {
                Spacer(Modifier.width(MailMetrics.s8))
                CircularProgressIndicator(Modifier.size(MailMetrics.s14), strokeWidth = MailMetrics.s2)
            }
        }
        val event = invite.event
        when {
            invite.loading -> Unit // the placeholder above (label + spinner) is enough
            event != null -> {
                Spacer(Modifier.height(MailMetrics.s6))
                Text(
                    text = event.title ?: stringResource(R.string.calendar_event_untitled),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (event.cancelled) {
                    Text(
                        text = stringResource(R.string.calendar_cancelled),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = formatEventWhen(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.location?.let {
                    Text(
                        text = stringResource(R.string.calendar_where, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                event.organizer?.let {
                    Text(
                        text = stringResource(R.string.calendar_organizer, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.attendeeCount > 0) {
                    Text(
                        text = stringResource(R.string.calendar_guests, event.attendeeCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.recurs) {
                    Text(
                        text = stringResource(R.string.calendar_repeats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // RSVP is offered only for an actionable request (a REQUEST naming an organiser and
                // not cancelled). CANCEL/REPLY methods get no buttons.
                if (event.method == "REQUEST" && !event.cancelled && !event.organizerEmail.isNullOrBlank()) {
                    InviteRsvp(invite.response, onRespond)
                }
                Spacer(Modifier.height(MailMetrics.s10))
                Button(onClick = {
                    leaveOnce {
                        val opened = addToCalendar(context, event)
                        if (!opened) {
                            // No event editor on the device — fall back to opening the raw invite.
                            // Reported as "did not leave": the fallback is a download first, it can
                            // still fail, and nothing has been created for a second tap to double.
                            Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_SHORT).show()
                            onOpenInvitation()
                        }
                        opened
                    }
                }) {
                    Icon(Icons.Filled.Event, contentDescription = null)
                    Spacer(Modifier.width(MailMetrics.s8))
                    Text(stringResource(R.string.calendar_add))
                }
            }
            else -> {
                // Couldn't parse the .ics: let the user hand it to their calendar app directly.
                Spacer(Modifier.height(MailMetrics.s6))
                Text(
                    text = stringResource(R.string.calendar_invite_unparsed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invite.part != null) {
                    Spacer(Modifier.height(MailMetrics.s10))
                    OutlinedButton(onClick = onOpenInvitation) {
                        Text(stringResource(R.string.calendar_open_invitation))
                    }
                }
            }
        }
    }
}

/**
 * RSVP controls for a meeting request: Accept / Decline / Tentative. Progress while sending, a
 * confirmation line once sent, an error and a retry on failure. Session only.
 */
@Composable
private fun InviteRsvp(response: InviteResponse, onRespond: (String) -> Unit) {
    Spacer(Modifier.height(MailMetrics.s12))
    when (response) {
        is InviteResponse.Sent -> {
            val msg = when (response.partstat) {
                "ACCEPTED" -> R.string.calendar_responded_accepted
                "DECLINED" -> R.string.calendar_responded_declined
                else -> R.string.calendar_responded_tentative
            }
            Text(
                text = stringResource(msg),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        InviteResponse.Sending -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(MailMetrics.s16), strokeWidth = MailMetrics.s2)
                Spacer(Modifier.width(MailMetrics.s8))
                Text(
                    text = stringResource(R.string.calendar_reply_sending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            if (response is InviteResponse.Failed) {
                Text(
                    text = stringResource(R.string.calendar_reply_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(MailMetrics.s8))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
            ) {
                Button(onClick = { onRespond("ACCEPTED") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_accept))
                }
                OutlinedButton(onClick = { onRespond("DECLINED") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_decline))
                }
                OutlinedButton(onClick = { onRespond("TENTATIVE") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_tentative))
                }
            }
        }
    }
}

/** Fire an ACTION_INSERT calendar intent prefilled with [event]; false if no app can handle it. */
private fun addToCalendar(context: Context, event: ParsedEvent): Boolean = try {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    event.endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
    event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    event.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

private val eventDateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", appLocale)
private val eventDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", appLocale)
private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)

/** "When" line for the event card: date for all-day, else start–end in the device zone. */
private fun formatEventWhen(event: ParsedEvent): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    if (event.allDay) return start.format(eventDateFormatter)
    val startStr = start.format(eventDateTimeFormatter)
    val end = event.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return startStr
    val endStr = if (start.toLocalDate() == end.toLocalDate()) {
        end.format(eventTimeFormatter)
    } else {
        end.format(eventDateTimeFormatter)
    }
    return "$startStr – $endStr"
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * Decides how the body WebView is composited, and self-heals devices whose GPU-functor path SIGSEGVs
 * (the S7: older hardware on a newer ROM reporting a modern API level, so it cannot be gated by
 * version). Crash-prone devices fall back to the software layer and its internal-scroll path.
 *
 * Detection is a persisted sentinel (synchronous `commit`, so it survives a process-killing SIGSEGV):
 * each launch [armIfUnproven] bumps a counter once, [markProven] latches and resets it, and after
 * [LATCH_THRESHOLD] unproven launches the software layer is latched — so a real SIGSEGV latches after
 * 2 crashes while a healthy device that closed the first mail early re-probes.
 */
private object WebViewLayerGuard {
    private const val PREFS = "webview_layer_guard"
    private const val KEY_FORCE_SOFTWARE = "force_software"
    private const val KEY_HARDWARE_PROVEN = "hardware_proven"
    // Consecutive process launches that armed a hardware draw but never proved it survived. A single
    // premature close no longer latches the software layer — only a device that fails to prove
    // REPEATEDLY is latched, which a real SIGSEGV does after 2 crashes.
    private const val KEY_UNPROVEN_STARTS = "unproven_starts"
    private const val LATCH_THRESHOLD = 2
    // One-time migration marker: the previous logic latched software on a SINGLE unproven arm, which
    // falsely condemned healthy devices. On first run of this version that latch is cleared; genuinely
    // crash-prone GPUs are re-caught by the preseed or the counter.
    private const val KEY_GUARD_RESET_V2 = "guard_reset_v2"
    // A one-time pre-seed that latches known-risky old GPUs onto the software layer BEFORE their first
    // hardware draw. KEY_PRESEED_DONE makes it run once; KEY_GL_RENDERER caches the offscreen probe.
    private const val KEY_PRESEED_DONE = "preseed_done"
    private const val KEY_GL_RENDERER = "gl_renderer"

    // At most one unproven-start increment per process launch (a session may load several bodies).
    @Volatile private var armedThisProcess = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True if this device must use the software layer. Latches it only after REPEATED unproven
     *  hardware draws, never on a single premature close. */
    fun useSoftwareLayer(context: Context): Boolean {
        // Test override: force every device onto the software path (no prefs touched, so flipping the
        // flag back leaves no latched state behind).
        if (FORCE_SOFTWARE_LAYER) return true
        val p = prefs(context)
        // One-time migration: wipe the OLD fragile latch (and the preseed marker, so known-risky GPUs
        // are re-caught with 0 crashes). Genuine crashers re-latch via the preseed or the counter.
        if (!p.getBoolean(KEY_GUARD_RESET_V2, false)) {
            p.edit()
                .putBoolean(KEY_GUARD_RESET_V2, true)
                .remove(KEY_FORCE_SOFTWARE)
                .remove(KEY_HARDWARE_PROVEN)
                .remove(KEY_PRESEED_DONE)
                .remove(KEY_UNPROVEN_STARTS)
                .commit()
        }
        if (p.getBoolean(KEY_FORCE_SOFTWARE, false)) return true
        // Before this device's first hardware draw, conservatively pre-seed the software layer for
        // known-risky old GPUs (S7-class Exynos/Mali-T, Exynos-9820 S10) so they never crash even
        // once. Runs once and latches; the counter below is the catch-all for the rest.
        if (!p.getBoolean(KEY_PRESEED_DONE, false)) {
            val risky = isRiskyOldGpu(context, p)
            val e = p.edit().putBoolean(KEY_PRESEED_DONE, true)
            if (risky) e.putBoolean(KEY_FORCE_SOFTWARE, true)
            e.commit()
            if (risky) return true
        }
        // No "proven" marker after repeated arms means the hardware draw never reported a safe finish
        // on several launches — a GPU-functor SIGSEGV takes the app down before [markProven] every
        // time. One unlucky launch does NOT latch.
        if (!p.getBoolean(KEY_HARDWARE_PROVEN, false) &&
            p.getInt(KEY_UNPROVEN_STARTS, 0) >= LATCH_THRESHOLD
        ) {
            p.edit().putBoolean(KEY_FORCE_SOFTWARE, true).commit()
            return true
        }
        return false
    }

    /**
     * Conservative pre-seed test for a GPU known to SIGSEGV on the WebView hardware functor. A false
     * positive only costs a modern device the full-height benefit; a false negative is caught by the
     * crash sentinel. So we err towards flagging. The GL_RENDERER probe is the most reliable signal;
     * Build heuristics are the fallback when it cannot run.
     */
    private fun isRiskyOldGpu(context: Context, p: android.content.SharedPreferences): Boolean {
        // Cheap Build signals first: old Exynos SoCs (the S7's 8890 and the 74xx/75xx/54xx
        // generations) all shipped the crash-prone Mali-T (Midgard) GPU.
        if (buildSignalsRiskyOldExynos()) return true
        // GL_RENDERER probe (cached): the authoritative signal. Old Mali-T is the family that
        // dereferences a null SkSurface in the WebView functor.
        val renderer = cachedGlRenderer(context, p)?.lowercase()
        return renderer != null && (renderer.contains("mali-t") || renderer.contains("mali t"))
    }

    /** True for SoC identifiers of old Exynos parts (S7 Exynos 8890 and older) that paired a Mali-T GPU. */
    private fun buildSignalsRiskyOldExynos(): Boolean {
        val oldExynos = listOf(
            "exynos9820", "universal9820", // Galaxy S10 / S10+ / Note10 (Mali-G76) — SIGSEGVs the
                                           // WebView GL functor on stock One UI 12 (verified on an S10+)
            "exynos8890", "universal8890", // Galaxy S7 / S7 edge (Mali-T880)
            "exynos7420", "universal7420", // S6 (Mali-T760)
            "exynos7580", "universal7580", // A-series (Mali-T720)
            "exynos5433", "universal5433", // Note 4 (Mali-T760)
            "exynos5420", "universal5420", "exynos5410", "universal5410", // Note 3 / S4 (Mali-T6xx)
        )
        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        if (oldExynos.any { hw.contains(it) || board.contains(it) }) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = (Build.SOC_MODEL ?: "").lowercase()
            if (oldExynos.any { soc.contains(it) }) return true
        }
        return false
    }

    /** The GL_RENDERER string, probed once via a tiny offscreen EGL context and cached in prefs. */
    private fun cachedGlRenderer(context: Context, p: android.content.SharedPreferences): String? {
        if (p.contains(KEY_GL_RENDERER)) return p.getString(KEY_GL_RENDERER, null)?.takeIf { it.isNotEmpty() }
        val renderer = probeGlRenderer()
        // Cache the empty string on failure so the probe is attempted at most once per install.
        p.edit().putString(KEY_GL_RENDERER, renderer ?: "").commit()
        return renderer
    }

    /**
     * Read GL_RENDERER from a 1x1 offscreen EGL14 pbuffer context. Fully torn down in `finally`; any
     * failure returns null and the caller leans on Build heuristics plus the crash sentinel.
     */
    private fun probeGlRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                display = EGL14.EGL_NO_DISPLAY // nothing to terminate
                return null
            }
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val nCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] <= 0) return null
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (t: Throwable) {
            return null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }

    /** Count this process launch as an unproven hardware attempt, before the first-ever hardware body
     *  draw (no-op once proven, at most once per process). A crash before [markProven] leaves the
     *  bumped counter persisted. */
    fun armIfUnproven(context: Context) {
        if (armedThisProcess) return
        val p = prefs(context)
        if (p.getBoolean(KEY_HARDWARE_PROVEN, false)) return
        armedThisProcess = true
        p.edit().putInt(KEY_UNPROVEN_STARTS, p.getInt(KEY_UNPROVEN_STARTS, 0) + 1).commit()
    }

    /** A hardware body has safely drawn: latch "proven", reset the failure counter, never arm again. */
    fun markProven(context: Context) {
        prefs(context).edit().putBoolean(KEY_HARDWARE_PROVEN, true).putInt(KEY_UNPROVEN_STARTS, 0).commit()
    }
}

/**
 * Crash sentinel for the message route's nav fade (#10): compositing a hardware WebView through the
 * fade's offscreen layer can SIGSEGV in AOSP's GLFunctorDrawable. The [FrameLayout] wrap steers
 * healthy geometry away from it; this is the net for devices where it still lines up badly.
 *
 * [arm]/[disarm] bracket the exposure window, ref-counted; [onActivityStop]/[onActivityStart] clear
 * the flag while the activity is stopped; [fadeDisabled] reconciles at startup, latching the fade OFF
 * when a leftover armed flag says the process died inside that window. One crash is the threshold,
 * and the latch records [Build.FINGERPRINT], so a ROM update re-probes once.
 */
internal object NavFadeGuard {
    private const val PREFS = "webview_layer_guard" // shared file, namespaced keys
    private const val KEY_FADE_DISABLED = "fade_disabled"
    private const val KEY_FADE_ARMED = "fade_armed"
    private const val KEY_FADE_LATCH_FP = "fade_latch_fp"

    @Volatile private var latched = false
    private var initialized = false
    private var refCount = 0
    private var stopped = false
    private var diskArmed = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read once per process at NavHost composition (the latch only ever changes via a process
     *  death). Also performs startup reconciliation. */
    @Synchronized
    fun fadeDisabled(context: Context): Boolean {
        if (initialized) return latched
        initialized = true
        val p = prefs(context)
        if (p.getBoolean(KEY_FADE_ARMED, false)) {
            // The previous process died while a hardware body was exposed to a running fade: that is
            // the #10 SIGSEGV (or a freak coincidence we accept). Latch the fade off.
            p.edit()
                .putBoolean(KEY_FADE_ARMED, false)
                .putBoolean(KEY_FADE_DISABLED, true)
                .putString(KEY_FADE_LATCH_FP, Build.FINGERPRINT)
                .commit()
        }
        latched = p.getBoolean(KEY_FADE_DISABLED, false)
        if (latched && p.getString(KEY_FADE_LATCH_FP, null) != Build.FINGERPRINT) {
            // ROM changed since the latch: re-probe the fade once on the new build.
            p.edit().remove(KEY_FADE_DISABLED).remove(KEY_FADE_LATCH_FP).commit()
            latched = false
        }
        return latched
    }

    @Synchronized
    fun arm(context: Context) {
        refCount++
        sync(context)
    }

    @Synchronized
    fun disarm(context: Context) {
        refCount--
        sync(context)
    }

    @Synchronized
    fun onActivityStop(context: Context) {
        stopped = true
        sync(context)
    }

    @Synchronized
    fun onActivityStart(context: Context) {
        stopped = false
        sync(context)
    }

    private fun sync(context: Context) {
        val want = refCount > 0 && !stopped && !latched
        if (want == diskArmed) return
        diskArmed = want
        // Synchronous commit: runs on the UI thread during applyChanges, i.e. strictly before the
        // frame in which the fade's offscreen layer is first rendered.
        prefs(context).edit().putBoolean(KEY_FADE_ARMED, want).commit()
    }
}

/**
 * How much longer the horizontal side of a drag on the message body must be than its vertical side
 * before the gesture is handed to the pager. THE calibration knob — see [BodyWebView.onTouchEvent].
 *
 * 3.0 is within about 18° of the horizontal (atan(1/3)). It was 1.5, i.e. 34°, and that took
 * gestures meant as scrolling: the decision is made after one touch slop (~1.3 mm), so at 1.5 a
 * thumb only had to wander 0.85 mm across to lose the message (#97), against 0.43 mm at 3.0. It
 * cannot be postponed to a longer drag: whatever the body has not claimed by the pager's own slop
 * is already the pager's.
 */
private const val SWIPE_HORIZONTAL_DOMINANCE = 3.0f

/**
 * Who owns a drag on the message body once its axis is settled: [CLAIM] keeps it in the body, either
 * because the drag is not clearly horizontal or because the body has a USABLE course that way (at
 * least a touch slop, see [bodyCanTravel]); [RELEASE] leaves it to the `HorizontalPager`; [PENDING]
 * means neither side has cleared the slop yet.
 */
internal enum class BodyDrag { PENDING, CLAIM, RELEASE }

/**
 * The body's once-per-gesture axis decision, factored out of [BodyWebView.onTouchEvent] so it can be
 * unit-tested. A clearly horizontal drag used to go to the pager unconditionally, which made a body
 * wider than the viewport unreachable (#152), so the sideways course LEFT decides.
 *
 * ON THE HORIZONTAL AXIS THIS IS NOW UNREACHABLE, deliberately, [bodyTakesGestureAtDown] settling the
 * gesture at ACTION_DOWN; it is kept because it still decides EVERY gesture on a body with no usable
 * course (the #97 near-vertical guard). The caller passes both courses through [bodyCanTravel], since
 * there must be ONE rule, and they come from the offset/range/extent — `scrollX == 0` is wrong in RTL.
 */
internal fun bodyDragOwner(
    dx: Float,
    dy: Float,
    slop: Float,
    canScrollBack: Boolean,
    canScrollForward: Boolean,
): BodyDrag {
    val ax = kotlin.math.abs(dx)
    val ay = kotlin.math.abs(dy)
    if (ax <= slop && ay <= slop) return BodyDrag.PENDING
    val clearlyHorizontal = ax > ay * SWIPE_HORIZONTAL_DOMINANCE
    if (!clearlyHorizontal) return BodyDrag.CLAIM
    val roomThatWay = if (dx > 0f) canScrollBack else canScrollForward
    return if (roomThatWay) BodyDrag.CLAIM else BodyDrag.RELEASE
}

/**
 * Whether the body must claim the whole gesture at ACTION_DOWN, before anyone knows which way the
 * finger will go. True as soon as the body can follow it for at least [minTravel] pixels either way.
 * THE THRESHOLD IS THE POINT: claiming on ANY travel, i.e. on `canScrollHorizontally`, whose
 * threshold is one pixel, had a visibly whole 900px table report THREE pixels of course and take the
 * gesture. A negative course, read before the layout settles, is not a course — never abs() it.
 * Decided at DOWN because [bodyDragOwner] is unreachable horizontally (#152): unclaimed, an
 * ACTION_MOVE reaches a view only on the Final pass, already consumed, while ACTION_DOWN always
 * arrives on the Initial one — and its claim cannot be handed back.
 */
internal fun bodyTakesGestureAtDown(travelBack: Int, travelForward: Int, minTravel: Int): Boolean =
    bodyCanTravel(travelBack, minTravel) || bodyCanTravel(travelForward, minTravel)

/**
 * Whether [travel] pixels of remaining sideways course are worth taking a gesture for: they are once
 * they reach [minTravel], the touch slop. ONE rule for both doors a gesture can come through, so
 * neither drifts into its own idea of "has travel". It replaces `canScrollHorizontally`, whose
 * threshold of a pixel or two let 3px of border kill swipe-between-messages.
 */
internal fun bodyCanTravel(travel: Int, minTravel: Int): Boolean = travel >= minTravel

/**
 * The "Original sender" line of the participants panel: the address to show, or null for no line at
 * all (#160). [headerValue] is `Email.originalSender`; [from] is the `From`, which this NEVER
 * replaces. The address is parsed on `MailRepository.parseAddress`'s rule, restated here rather than
 * widening the repository's surface.
 *
 * A plain function and not a branch inside the composable: R8 has already inlined a small
 * `@Composable` of this module until it painted nothing at all. Two refusals: nothing
 * address-shaped, the header being sender-controlled; the same address as the first `From`.
 */
internal fun originalSenderToShow(headerValue: String?, from: List<EmailAddress>): EmailAddress? {
    val value = headerValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val bracketed = Regex("^(.*?)<([^>]+)>").find(value)
    val name = bracketed?.groupValues?.get(1)?.trim()?.ifBlank { null }
    val email = bracketed?.groupValues?.get(2)?.trim() ?: value
    if (!email.contains('@')) return null
    if (email.equals(from.firstOrNull()?.email?.trim(), ignoreCase = true)) return null
    return EmailAddress(name = name, email = email)
}

/**
 * Which of the reader's OWN addresses this message came in on, as the third line of the header (#180),
 * or null for no line. A plain function, for [originalSenderToShow]'s reason.
 * Four refusals: [ownMessage], an outgoing message having no arrival; nothing delivered, a label
 * with nothing after it being worse than none; fewer than two DISTINCT own addresses; a
 * [deliveredTo] that is not one of [identityAddresses], since the label asserts it is YOURS.
 * WHICH list this is, is the whole trap, and why it is not `accountAddresses`: that one folds in the
 * LOGIN, an authentication identifier that would carry this line on every message of a single-address
 * account. An account whose identities have not synced yet gets NO line, which is honest.
 */
internal fun deliveredToInHeader(deliveredTo: String?, identityAddresses: List<String>, ownMessage: Boolean): String? {
    if (ownMessage) return null
    val address = deliveredTo?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val distinct = identityAddresses.map { it.trim().lowercase() }.filter { '@' in it }.toSet()
    if (distinct.size < 2) return null
    if (address.lowercase() !in distinct) return null
    return address
}

/**
 * The message-body WebView. It fills the viewport and OWNS its vertical scroll, so Blink culls
 * offscreen content (#5). Horizontal drags are ROUTED in [onTouchEvent]: a sideways drag reaches the
 * pager (#6) only on a body with less than one touch slop of sideways travel, and a second finger
 * never reaches it (#152). Beyond that it adds a scroll callback and layout metrics.
 */
private class BodyWebView(context: Context) : WebView(context) {
    /** Invoked after every internal scroll so the host can collapse the header / reveal the bar. */
    var onScrolled: (() -> Unit)? = null

    /** Gates scroll reporting until layout has settled, so the scroll-reset and reflow during load do
     *  not report a transient (tiny) range and flash the bar. */
    var reportingEnabled = false

    /** Identity token for the in-flight settle poll. Each load installs a fresh one, so a poll left
     *  over from a previous load bails instead of revealing the bar on stale content. */
    var settleToken: Any? = null

    /** Invoked once the view actually has a size. The reveal poll reads the content range, which is
     *  floored at the view's own height and therefore ZERO until the view is laid out; a poll that ran
     *  entirely inside that window learned nothing, and none is ever restarted. This is the host's
     *  second, layout-driven chance to notice the body is there. */
    var onSized: (() -> Unit)? = null

    /** Invoked when a gesture that could have ACTIVATED something in the document happens: an
     *  ACTION_UP, or an activation key pressed or released (which of the two the document acts on is
     *  not knowable here — see [BodyReveal.keyArmsResizeProbe]). The document has no script and no
     *  size callback, so this is the host's only warning that the quote fold may have moved. It says
     *  "she touched it", never "it resized": the host reads the geometry itself. */
    var onActivated: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSized?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The keyboard's half of [onActivated]. It hangs off dispatchKeyEvent, not onKeyUp: onKeyUp
        // is only reached for a key NOBODY consumed, and Blink consumes the one that works the fold.
        // Armed BEFORE super, like the touch door: super is where the document acts on the key, so
        // this is the last instant at which the range still holds the pre-toggle height. Which of the
        // press and the release the document acts on is not knowable here, so both arm.
        // THIS OBSERVES, IT NEVER CONSUMES: the answer is super's, unchanged.
        if (BodyReveal.keyArmsResizeProbe(event.action, event.keyCode, event.repeatCount)) {
            onActivated?.invoke()
        }
        return super.dispatchKeyEvent(event)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axisDecided = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; axisDecided = false
                // The ONLY instant this view speaks before the pager: DOWN is dispatched on the Initial
                // pass, every ACTION_MOVE only on the Final one — so a sideways MOVE reaches us as an
                // ACTION_CANCEL and the axis decision below never runs. The two courses are measured
                // HERE, in pixels, and not asked of canScrollHorizontally: its threshold is one pixel,
                // and 3px of table border killed the swipe. BodySideScrollWiringTest pins these three
                // lines as text, since nothing in a JVM test can build a WebView.
                val travelBack = computeHorizontalScrollOffset()
                val travelForward =
                    computeHorizontalScrollRange() - computeHorizontalScrollExtent() - travelBack
                val takesIt = bodyTakesGestureAtDown(
                    travelBack = travelBack,
                    travelForward = travelForward,
                    minTravel = touchSlop,
                )
                // Outside the claim on purpose: a DOWN that took nothing is a RESULT — the line carries
                // the two courses and the slop they were judged against, so "the body had nothing to
                // show" is distinguishable from "the code never ran".
                if (BuildConfig.DEBUG) logDownVerdict(takesIt, travelBack, travelForward)
                if (takesIt) {
                    axisDecided = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            // A second finger is never a swipe between messages. Pinch-to-zoom is supported here, and
            // it is the very gesture that creates the sideways travel a wide body needs — yet event.x
            // stays pointer 0's, so two fingers spreading sideways make the first MOVE look clearly
            // horizontal. Claim it outright and freeze the verdict; this branch always arrives BEFORE
            // the first two-finger MOVE.
            MotionEvent.ACTION_POINTER_DOWN -> {
                axisDecided = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!axisDecided) {
                // Signed deltas: bodyDragOwner needs the DIRECTION of the drag. The claim goes through
                // parent? on purpose — the WebView is not the AndroidView's root, there is a
                // FrameLayout in between (#10). The two answers are the SAME thresholded measure DOWN
                // took, not raw canScrollHorizontally. This branch rescues nothing sideways: it is not
                // reached (see above).
                val travelBack = computeHorizontalScrollOffset()
                val travelForward =
                    computeHorizontalScrollRange() - computeHorizontalScrollExtent() - travelBack
                val owner = bodyDragOwner(
                    dx = event.x - downX,
                    dy = event.y - downY,
                    slop = touchSlop.toFloat(),
                    canScrollBack = bodyCanTravel(travelBack, touchSlop),
                    canScrollForward = bodyCanTravel(travelForward, touchSlop),
                )
                if (owner != BodyDrag.PENDING) {
                    axisDecided = true
                    // Debug-only measurement of the verdict, at the instant it is taken and BEFORE the
                    // claim (nothing below changes the geometry, but the reading must not be arguable).
                    // The deltas are recomputed rather than hoisted: the call above is pinned argument
                    // by argument by BodySideScrollWiringTest.
                    if (BuildConfig.DEBUG) {
                        logDragVerdict(
                            owner, event.x - downX, event.y - downY, travelBack, travelForward,
                        )
                    }
                    if (owner == BodyDrag.CLAIM) parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            // The end of a gesture the body kept. ACTION_CANCEL is deliberately NOT here: a cancelled
            // gesture is one the pager took off us, and it activated nothing in this document.
            MotionEvent.ACTION_UP -> onActivated?.invoke()
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke()
    }

    /** The body's true content height (device px) from the layout. */
    fun contentRangePx(): Int = computeVerticalScrollRange()

    /** Visible content height (device px): the WebView viewport minus its padding. */
    fun visibleExtentPx(): Int = computeVerticalScrollExtent()

    /**
     * One line per settled drag verdict, debug builds only (see [BODY_DRAG_TAG]). Call it INSIDE an
     * `if (BuildConfig.DEBUG)`: the touch path is hot, and the guard must swallow the string building.
     *
     * IT HAS ALREADY ANSWERED, on 2026-08-12: `verdict=CLAIM canFwd=true hRange=1833 hExtent=720`.
     * The reading this doc was written to test was WRONG; what was broken was the instant, not the API.
     * READ `travelBack`/`travelFwd` FIRST, against the `slop=` threshold. `canBack`/`canFwd` are the
     * RAW View-API answers, kept only to line these up with older readings; they no longer decide.
     */
    @Suppress("DEPRECATION")
    // getScale() is deprecated with no replacement on the View API: the modern answer is
    // window.visualViewport, and JavaScript is disabled on this view — which is the very reason this
    // measurement has to come from the View side. It now serves as the cross-check that the geometry
    // reported here is the horizontal one.
    private fun logDragVerdict(
        owner: BodyDrag,
        dx: Float,
        dy: Float,
        travelBack: Int,
        travelForward: Int,
    ) {
        // Numbers only, plus an enum name. NEVER any part of the document: some benches carry real
        // mail.
        Log.d(
            BODY_DRAG_TAG,
            "drag view=${System.identityHashCode(this)} verdict=$owner dx=$dx dy=$dy slop=$touchSlop " +
                "travelBack=$travelBack travelFwd=$travelForward " +
                "canBack=${canScrollHorizontally(-1)} canFwd=${canScrollHorizontally(1)} " +
                "scrollX=$scrollX hOffset=${computeHorizontalScrollOffset()} " +
                "hRange=${computeHorizontalScrollRange()} hExtent=${computeHorizontalScrollExtent()} " +
                "width=$width scale=$scale",
        )
    }

    /**
     * One line per finger landing on the body, debug builds only. Call it INSIDE an
     * `if (BuildConfig.DEBUG)` and OUTSIDE the claim: a `takes=false` line is the finding, not the
     * absence of a line. It exists because a bench pass could not tell "the body read no sideways
     * travel" from "the decision was never reached".
     *
     * READ `takes=` BEFORE CONCLUDING: `takes=true` freezes the verdict, so no `drag` line follows —
     * the normal case. Only `takes=false` with no `drag` means the MOVE never arrived.
     */
    @Suppress("DEPRECATION") // getScale(): same reason as [logDragVerdict].
    private fun logDownVerdict(takes: Boolean, travelBack: Int, travelForward: Int) {
        Log.d(
            BODY_DRAG_TAG,
            "down view=${System.identityHashCode(this)} takes=$takes " +
                "travelBack=$travelBack travelFwd=$travelForward minTravel=$touchSlop " +
                "canBack=${canScrollHorizontally(-1)} canFwd=${canScrollHorizontally(1)} " +
                "scrollX=$scrollX hOffset=${computeHorizontalScrollOffset()} " +
                "hRange=${computeHorizontalScrollRange()} hExtent=${computeHorizontalScrollExtent()} " +
                "width=$width scale=$scale",
        )
    }

    /**
     * One line per load, debug builds only, where the settle poll enables reporting — the geometry AT
     * REST. [template] names which of [buildHtmlDocument]'s two templates was rendered, since the
     * dark-rich one puts `filter: invert(1)` on the root. That question is CLOSED (a real horizontal
     * range was found on 2026-08-12); the line is kept for the geometry at rest, which the same pass
     * showed disagreeing with the geometry during a drag. Read it as "not reflowed yet".
     *
     * Even `settled=true` attests only that the VERTICAL range stopped moving.
     * It does NOT survive a relayout that skips a reload: re-open the message before trusting it.
     */
    @Suppress("DEPRECATION") // getScale(): same reason as [logDragVerdict].
    fun logSettledGeometry(template: String, settled: Boolean, blockRemote: Boolean) {
        Log.d(
            BODY_DRAG_TAG,
            "settle view=${System.identityHashCode(this)} template=$template settled=$settled " +
                "blockRemote=$blockRemote " +
                "canBack=${canScrollHorizontally(-1)} canFwd=${canScrollHorizontally(1)} " +
                "scrollX=$scrollX hOffset=${computeHorizontalScrollOffset()} " +
                "hRange=${computeHorizontalScrollRange()} hExtent=${computeHorizontalScrollExtent()} " +
                "width=$width scale=$scale",
        )
    }
}

/** The single logcat tag of the #152 sideways-drag instrumentation. `Log.d` on purpose: the guard
 *  is `BuildConfig.DEBUG`, and proguard-rules.pro additionally strips `Log.d`/`Log.v` from release. */
private const val BODY_DRAG_TAG = "SternaBodyDrag"

/**
 * Which of [buildHtmlDocument]'s two templates produced [html] — debug instrumentation only, and
 * deliberately NOT a decision anything on screen depends on. Only the dark-rich branch puts a
 * `filter:` in the document head, and the head is entirely ours, so no email can forge either answer.
 */
private fun bodyTemplateName(html: String): String =
    if ("filter: invert(1)" in html.substringBefore("<body")) "dark-rich-invert" else "light-or-plain"

/**
 * How long after a document load the body's height report may go missing before the WebView takes the
 * height from the view itself. Just past the post-load height poll's own window, so the accurate
 * report always wins when it comes at all.
 */
private const val HEIGHT_REPORT_BACKSTOP_MS = 1_200L

/**
 * The resize probe's window: [RESIZE_PROBE_TICKS] readings [RESIZE_PROBE_MS] apart, ~1 s, armed by
 * the END of a gesture the body kept.
 * Why a whole second: the probe must see the relayout reach a PLATEAU ([BodyReveal.resizeStep]).
 * Expiring is FINAL — a folded body that fits never scrolls again — so a budget too short does not
 * delay the bar, it loses it; and it stays BOUNDED for the symmetric reason (#63).
 *
 * EVERY gesture the body keeps ends in an ACTION_UP, so a scroll arms one probe — once per GESTURE,
 * not per frame, and nothing is added to the scroll or draw paths (#5/#6).
 */
private const val RESIZE_PROBE_TICKS = 20
private const val RESIZE_PROBE_MS = 50L

@Composable
private fun EmailWebView(
    html: String,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    backgroundColor: Int,
    textZoom: Int,
    onReady: (resting: BodyMetrics?) -> Unit,
    onScroll: (metrics: BodyMetrics, lastWord: Boolean) -> Unit,
    onResized: (metrics: BodyMetrics) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val linkCopiedMsg = stringResource(R.string.status_link_copied)
    // Both ways out of this body go through ONE latch, because they are one action: tapping a link.
    // The confirmation dialog is not itself protection — two taps in the same frame both reach its
    // Open button — and with confirmation OFF, the default, there is no dialog at all.
    val leaveOnce = rememberLeaveOnce()
    // When confirmation is on, a tapped link is held here until the user approves it.
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    // Body laid out: JS is disabled, so this comes from the native scroll range, reported once it has
    // stabilised. Until then the parent keeps the WebView invisible and shows a spinner.
    var heightPx by remember { mutableIntStateOf(0) }
    // Compositing mode for this device, decided once. Most devices render hardware-accelerated;
    // crash-prone ones latch the software layer. The WebView fills the viewport, so it stays within
    // that layer's bitmap cap with no extra clamping. See [WebViewLayerGuard].
    val useSoftwareLayer = remember(context) { WebViewLayerGuard.useSoftwareLayer(context) }
    val navTransitionActive = LocalNavTransitionActive.current
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri ->
        if (confirmLinks) pendingLink = uri else leaveOnce { InAppBrowser.openLink(context, uri) }
    }
    // The body's resting scroll geometry as measured by the height poll, when it actually measured
    // rather than fell back. It is what lets the host decide the Reply/Forward bar in the same frame
    // as the body's reveal (#63); null keeps the old, bias-to-hidden path.
    var restingMetrics by remember { mutableStateOf<BodyMetrics?>(null) }
    client.onContentHeight = { px, resting -> heightPx = px; restingMetrics = resting }
    val ready = heightPx > 0
    // Keyed on the metrics too: a height that arrived from a fallback can still be followed by a real
    // measurement, and that measurement should still get to settle the bar.
    LaunchedEffect(ready, restingMetrics) { if (ready) onReady(restingMetrics) }
    // Report the body's scroll position so the host can collapse the header and reveal the bar at the
    // end. Reporting is gated until layout settles, so the load-time scroll-reset never reports a
    // transient tiny range (which read as "fits" and flashed the bar). The RAW geometry is reported:
    // `contentRangePx()` is floored at the view's height, so whether a report measured anything
    // belongs to [BarReveal] alone. `lastWord` marks the settle poll's CAPPED report — the only
    // resting geometry the reader is guaranteed.
    val reportScroll: (BodyWebView, Boolean) -> Unit = { wv, lastWord ->
        onScroll(
            BodyMetrics(
                scrollY = wv.scrollY,
                rangePx = wv.contentRangePx(),
                viewportPx = wv.visibleExtentPx(),
                contentHeightPx = wv.contentHeight,
            ),
            lastWord,
        )
    }
    // The same raw geometry, through the THIRD door: this body is already on screen and just changed
    // height under the reader's finger. Only [BarReveal.resized] may set aside the "a measured range
    // only grows" rule, and only because this report is armed by a gesture — so it has its own
    // callback rather than a flag on the scroll one.
    val reportResize: (BodyWebView) -> Unit = { wv ->
        onResized(
            BodyMetrics(
                scrollY = wv.scrollY,
                rangePx = wv.contentRangePx(),
                viewportPx = wv.visibleExtentPx(),
                contentHeightPx = wv.contentHeight,
            ),
        )
    }
    // On the hardware layer, once the body has drawn safely, latch the device as "hardware proven".
    // The delay outlives the first frame: an S7-class functor would already have SIGSEGV'd. Gated on
    // the nav fade being over, so a fade-window crash ([NavFadeGuard]'s territory) is never counted as
    // steady-state proof.
    LaunchedEffect(ready, useSoftwareLayer, navTransitionActive) {
        if (ready && !useSoftwareLayer && !navTransitionActive) {
            delay(500)
            WebViewLayerGuard.markProven(context)
        }
    }
    // Arm the fade-crash sentinel exactly while a laid-out hardware body is exposed to a running nav
    // fade — the only window where the #10 SIGSEGV can strike. DisposableEffect covers every exit
    // uniformly, so only a process death leaves the flag set. The commit() runs strictly before the
    // frame in which the fade's offscreen layer is first rendered. Software-layer devices never arm.
    val fadeExposure = ready && !useSoftwareLayer && navTransitionActive
    DisposableEffect(fadeExposure) {
        if (fadeExposure) NavFadeGuard.arm(context)
        onDispose { if (fadeExposure) NavFadeGuard.disarm(context) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // UNTRUSTED_HTML (#1371): no JS, no storage, no file/content access, never mixed content, remote images
            // gated (the client below and CloudWeb.remoteImages), pinch zoom; the software latch below still wins
            val webView = CloudWeb.configure(BodyWebView(ctx), WebProfile.UNTRUSTED_HTML).apply {
                // Compositing path. The hardware-accelerated GLFunctor lets the WebView tile its own
                // paint, but on devices whose HWUI/GPU blobs are mismatched it dereferences a null
                // SkSurface in RenderThread and the app SIGSEGVs the instant a body is drawn. Such
                // devices are latched onto the software layer by [WebViewLayerGuard]; for a static,
                // JS-disabled body the software rendering cost is negligible.
                setLayerType(if (useSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)
                applyFitSettings()
                // Keep the text READABLE once [FIT_CSS] has squeezed a desktop-authored page into a
                // phone's width. Without this the two settings above are free to satisfy "it fits" by
                // scaling the whole page down until the body is unreadable — technically fitting, and
                // useless. TEXT_AUTOSIZING rescales text against the viewport instead, so narrowing a
                // 1200px layout costs column width, not legibility. NARROW_COLUMNS/SINGLE_COLUMN are
                // the deprecated predecessors and are deliberately not used.
                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                // Explicitly deny every path from email markup to the local filesystem, on-device
                // storage or geolocation. Off by default on modern API levels, but untrusted HTML
                // email warrants asserting it.
                settings.setGeolocationEnabled(false)
                // The way out for content that genuinely cannot fit — a wide invoice table shrunk by
                // [FIT_CSS] until it is hard to read is only half a fix if he cannot zoom back into
                // it. Stated explicitly rather than left to the platform default, because the cap
                // above is what makes it matter. The on-screen +/- buttons stay off (obsolete, and
                // they overlap the Reply/Forward bar); pinch is the gesture.
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = client
            }
            // The WebView is deliberately NOT the AndroidView root: an intermediate FrameLayout changes
            // the clip geometry HWUI hands the GL functor when the nav fade composites this subtree
            // into its offscreen layer, steering it away from the empty-clip path whose unguarded null
            // SkSurface SIGSEGVs GL-pipeline devices (#10). Same shape as react-native-webview's
            // shipped fix. [NavFadeGuard] remains the net for devices where the geometry still lines
            // up badly.
            FrameLayout(ctx).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { frame ->
            val webView = frame.getChildAt(0) as BodyWebView
            CloudWeb.remoteImages(webView, !blockRemote)
            // Match the WebView's own background to the theme so it doesn't flash white.
            webView.setBackgroundColor(backgroundColor)
            webView.settings.textZoom = textZoom
            // Report scroll on each internal scroll, but only once reporting is enabled, so load-time
            // scroll-resets do not flash the bar.
            webView.onScrolled = { if (webView.reportingEnabled) reportScroll(webView, false) }
            // Second chance to notice the body is laid out: the post-load height poll can run and
            // expire entirely while the view still has no size, and it is never restarted.
            webView.onSized = {
                if (heightPx <= 0) heightPx = webView.contentRangePx().coerceAtLeast(webView.height)
            }
            // The third door. Closing the <details> that folds a reply's quoted history really SHORTENS
            // the document, and nothing announces it: no script, no content-size callback, and a body
            // at the top does not scroll as it shrinks — so the remembered range stays that of the
            // unfolded body and Reply/Forward are gone until the message is reopened.
            // Every gesture the body keeps ends here, not just a tap on the fold; the rest read the
            // same number twice and expire in silence (see [ResizeProbe]).
            // This LOOKS ONLY: nothing here re-keys the document or touches the load key.
            webView.onActivated = {
                if (webView.reportingEnabled) {
                    // The load this gesture belongs to, and the height the document had when her finger
                    // left the screen. Both are captured now: the probe compares against THIS reading,
                    // and stands down if a newer load replaced the token.
                    val loadToken = webView.settleToken
                    val atGesture = webView.contentRangePx()
                    fun probe(last: Int, triesLeft: Int) {
                        if (webView.settleToken !== loadToken || webView.parent == null) return
                        val now = webView.contentRangePx()
                        when (BodyReveal.resizeStep(now, atGesture, last, triesLeft)) {
                            ResizeProbe.Report -> reportResize(webView)
                            ResizeProbe.Retry -> webView.postDelayed({ probe(now, triesLeft - 1) }, RESIZE_PROBE_MS)
                            ResizeProbe.Done -> Unit
                        }
                    }
                    webView.postDelayed({ probe(atGesture, RESIZE_PROBE_TICKS) }, RESIZE_PROBE_MS)
                }
            }
            // update() runs on every recomposition; only (re)load when the document actually changed.
            // blockRemote is part of the key so toggling "show images" reloads the page — otherwise the
            // already-intercepted image requests are never re-issued and stay broken.
            val loadKey = Pair(blockRemote, html)
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                // Arm the crash sentinel right before a hardware-accelerated draw on a device that has
                // not yet proven the functor is safe. If this draw SIGSEGVs, the flag survives to the
                // next launch. No-op once proven.
                if (!useSoftwareLayer) WebViewLayerGuard.armIfUnproven(context)
                webView.reportingEnabled = false
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                // Keep the bar HIDDEN until the body has SETTLED, then evaluate the resting reveal ONCE
                // with the final scroll range. A fixed 250 ms timer expired while a heavy body was
                // still laying out, so the tiny live range was judged "fits" and the bar flashed in.
                // Instead poll the live content range until it stops changing, or a cap elapses;
                // reporting stays OFF throughout: bias-to-hidden, zero flash.
                // Since #63 this is no longer what usually reveals the bar — the height poll settles
                // first — so it stays as the gate for live reporting and the fallback for loads whose
                // height never settled.
                val settleToken = Any()
                webView.settleToken = settleToken
                var settleLast = -1
                var settleStable = 0
                fun settlePoll(triesLeft: Int) {
                    // A newer load started (token replaced) or the view was detached: abandon WITHOUT
                    // touching reporting, so a stale poll cannot reveal the bar.
                    if (webView.settleToken !== settleToken || webView.parent == null) return
                    val range = webView.contentRangePx()
                    // A reading that MEASURED nothing can never settle this poll, however often it
                    // repeats: the range is floored at the view's height, so three agreeing readings of
                    // that floor are what a body reads while its renderer is booting. Concluding there
                    // announced "the body fits" for the first message opened after process start. So
                    // keep polling until the document is laid out — the cap still reports regardless.
                    if (BodyReveal.rangeMeasured(range, webView.visibleExtentPx(), webView.contentHeight) &&
                        range == settleLast
                    ) {
                        settleStable++
                    } else {
                        settleStable = 0
                        settleLast = range
                    }
                    // Stable across three consecutive reads, or the ~2.5 s cap reached (some bodies never
                    // fully settle): enable reporting and evaluate the resting reveal once.
                    if (settleStable >= 2 || triesLeft <= 0) {
                        webView.reportingEnabled = true
                        reportScroll(webView, settleStable < 2)
                        // Debug-only: the body's HORIZONTAL geometry at rest, once per load, from the
                        // one point that already knows the layout has stopped moving (#152 follow-up).
                        if (BuildConfig.DEBUG) {
                            webView.logSettledGeometry(
                                bodyTemplateName(html), settleStable >= 2, blockRemote,
                            )
                        }
                        return
                    }
                    webView.postDelayed({ settlePoll(triesLeft - 1) }, 50)
                }
                webView.postDelayed({ settlePoll(50) }, 50)
                // Re-arm the height report from the LOAD, not just from onPageFinished. The report is
                // what reveals the body, and it only ever rode on onPageFinished — which a superseded
                // load never delivers, leaving nothing to start the poll and no way back. The load
                // itself always happens, so hang a backstop off it. Token-guarded like the settle poll.
                webView.postDelayed({
                    if (webView.settleToken === settleToken && webView.parent != null && heightPx <= 0) {
                        heightPx = webView.contentRangePx().coerceAtLeast(webView.height).coerceAtLeast(1)
                    }
                }, HEIGHT_REPORT_BACKSTOP_MS)
            }
        },
    )

    pendingLink?.let { uri ->
        val link = uri.toString()
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.message_open_link_title)) },
            text = {
                Column {
                    // The address takes what is LEFT once the button has its height, and scrolls inside
                    // that. Material's text slot is a height-bounded box with no scrolling of its own:
                    // written as a plain Column, a long address ate the whole slot and the button was
                    // measured at zero height — not crowded, gone, and untappable. The addresses that
                    // need this dialog most are exactly the long ones.
                    Text(
                        link,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    )
                    // Copy the address instead of handing it to whatever app claims it (#108): the
                    // confirmation used to be a dead end. It sits UNDER the address, not as a third
                    // action button: Material's dialog has exactly two action slots, and a third label
                    // crammed into one shares that slot's single row, where it cannot wrap.
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(link))
                            // Every other copy in the app says so. From Android 13 the system announces
                            // the copy itself and ours would double it, so ours stands down there and
                            // only there; minSdk is 26, so without this five Android versions get no
                            // answer at all beyond the dialog closing.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, linkCopiedMsg, Toast.LENGTH_SHORT).show()
                            }
                            pendingLink = null
                        },
                        contentPadding = PaddingValues(horizontal = MailMetrics.s4, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(MailMetrics.s16),
                        )
                        Spacer(Modifier.width(MailMetrics.s6))
                        Text(stringResource(R.string.message_open_link_copy))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { leaveOnce { InAppBrowser.openLink(context, uri) }; pendingLink = null }) {
                    Text(stringResource(R.string.message_open_link_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** A Compose [Color] as a CSS hex string (#RRGGBB). */
private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/**
 * The body's resting scroll geometry at the instant its height poll ended, or null when that poll did
 * not measure anything — in which case nothing is claimed and the settle poll decides, as before #63.
 *
 * The range used is the TALLEST reading of this load: the only mistake that shows on screen is calling
 * a long body "fits" and taking the bar away again on the first scroll, so erring this way costs at
 * most a late bar.
 */
private fun restingMetrics(wv: WebView, step: HeightPoll.Report, maxSeen: Int): BodyMetrics? {
    if (!step.settled) return null
    val body = wv as? BodyWebView ?: return null
    return BodyMetrics(
        scrollY = body.scrollY,
        rangePx = maxOf(step.px, maxSeen),
        viewportPx = body.visibleExtentPx(),
        contentHeightPx = body.contentHeight,
    )
}

/** Blocks remote (http/https) resource loads while [blockRemote]; opens links externally. */
private class BlockingWebViewClient : CloudWebClient(WebProfile.UNTRUSTED_HTML) {
    var blockRemote: Boolean = true
    var stripTracking: Boolean = true

    /** Reports the final (possibly cleaned) URL to open; the composable decides how. */
    var onOpenUrl: (Uri) -> Unit = {}

    /** Reports the rendered content height (Android px). The host does not size anything from it — it
     *  uses it purely as "the body has laid out, reveal it". The second argument carries the resting
     *  scroll geometry measured at that same instant, non-null ONLY when the height was actually
     *  measured, and lets the host reveal the bottom bar in the same frame as the body (#63). */
    var onContentHeight: (px: Int, resting: BodyMetrics?) -> Unit = { _, _ -> }

    /** Called once the page has finished loading, after the height poll above has been armed. The
     *  print path hangs its "hand the page to the print service" on it; the reader leaves it empty.
     *  Not named after the callback it rides on, so a call reads as what it is. */
    var onPageLoaded: (WebView) -> Unit = {}

    override fun onPageFinished(view: WebView?, url: String?) {
        val wv = view ?: return
        // No JS to measure with, so poll the native content height until it stabilises (two consecutive
        // equal readings), then report the final height ONCE. This absorbs the brief reflow as text
        // lays out and inline images decode, so the body is revealed a single time. Caps at ~1 s.
        var last = -1
        var maxSeen = 0
        fun poll(triesLeft: Int) {
            if (wv.parent == null) return // detached (recycled/closed) — stop
            // Measure with the layout-accurate scroll range (device px), not the legacy
            // getContentHeight(), which ignores trailing padding and is unreliable on heavy HTML at
            // first paint: the under-report made long bodies un-scrollable to their true end (#5/#6).
            val px = (wv as? BodyWebView)?.contentRangePx()
                ?: (wv.contentHeight * wv.resources.displayMetrics.density).toInt()
            if (px > maxSeen) maxSeen = px
            // Some bodies (deeply nested tables + inline images) never settle — the range oscillates in
            // a relayout loop. Reporting the LAST reading could pin a too-short height and cut off the
            // tail; the cap reports the TALLEST seen. A little trailing slack is harmless; lost content
            // is not. And the cap ALWAYS reports, even when every reading was zero: this poll is the
            // only thing that reveals the body.
            when (val step = BodyReveal.step(px, last, maxSeen, triesLeft, wv.height)) {
                is HeightPoll.Report -> onContentHeight(step.px, restingMetrics(wv, step, maxSeen))
                HeightPoll.Retry -> {
                    last = px
                    wv.postDelayed({ poll(triesLeft - 1) }, 32)
                }
            }
        }
        wv.post { last = -1; maxSeen = 0; poll(30) }
        onPageLoaded(wv)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!blockRemote) return null
        // Default-deny: only inert, local sources are allowed through. Anything else — http(s),
        // protocol-relative URLs (which arrive with a null/empty scheme), ws, ftp, prefetch — is
        // blocked. Keying on "http"/"https" alone let "//evil.com/x.gif" slip past.
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == "data" || scheme == "cid" || scheme == "about") {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme?.lowercase()
        // Only hand off web/contact schemes to the system. Never forward intent:, javascript:, file:,
        // content:, data: — an <a href="intent://…"> in a hostile email could redirect into another app.
        if (scheme !in SAFE_OPEN_SCHEMES) return true // swallow: don't navigate, don't open
        // Act only on a genuine user tap. Auto-navigations (<meta refresh>, scripted redirects) arrive
        // without a gesture; ignoring them stops a message acting just by being viewed.
        if (!request.hasGesture()) return true
        // Strip tracking params (utm_*, fbclid, …) so the sender can't tell the link was clicked.
        val target = if (stripTracking) Uri.parse(LinkCleaner.strip(url.toString())) else url
        onOpenUrl(target)
        return true
    }

    private companion object {
        val SAFE_OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")
    }
}

/**
 * Hand [html] — the document [buildPrintDocument] made — to Android's print service, rendered by a
 * WebView of its OWN, never attached to any view hierarchy. Not the reader's: that one sits inside
 * the guards that keep the fade from crashing (#10) and its page carries the screen's spacer bands.
 * [HoldingAdapter] holds this one until the service is done, so a rotation costs nothing.
 * [blockRemote] is the SAME decision the screen took, enforced by the same client.
 */
private fun printDocument(activity: Activity, html: String, jobName: String, blockRemote: Boolean) {
    val webView = CloudWeb.create(activity, WebProfile.UNTRUSTED_HTML)
    CloudWeb.remoteImages(webView, !blockRemote)
    // Held HERE from creation, not only from the moment the print service gets its adapter: between
    // `loadDataWithBaseURL` and `onPageFinished` nothing else references this detached view, and a page
    // with remote images on a slow network keeps that window open for seconds.
    printingWebViews += webView
    // One print job per tap, however many times the client reports the page finished.
    var handed = false
    webView.webViewClient = BlockingWebViewClient().apply {
        this.blockRemote = blockRemote
        onPageLoaded = { wv ->
            if (!handed) {
                handed = true
                // A device without a print framework has no PrintManager; the entry is offered
                // everywhere, so the gesture does nothing there rather than crash the reader.
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (pm == null) {
                    releasePrintingWebView(wv)
                } else {
                    val adapter = wv.createPrintDocumentAdapter(jobName)
                    pm.print(jobName, HoldingAdapter(adapter, wv), PrintAttributes.Builder().build())
                }
            }
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

/** The detached WebViews currently rendering a print job, so they outlive the composable that started
 *  them and survive a GC between load and print. Emptied by [releasePrintingWebView]. */
private val printingWebViews = mutableSetOf<WebView>()

private fun releasePrintingWebView(webView: WebView) {
    printingWebViews -= webView
    webView.destroy()
}

/**
 * A [PrintDocumentAdapter] that forwards everything to the WebView's own [delegate] and does one thing
 * more: it KEEPS the WebView alive for as long as the print service is using it. Nothing else
 * references that WebView, so without this the job could lose its page mid-preview. `destroy()`
 * happens through [releasePrintingWebView], once the service has finished.
 */
private class HoldingAdapter(
    private val delegate: PrintDocumentAdapter,
    private val webView: WebView,
) : PrintDocumentAdapter() {
    override fun onStart() = delegate.onStart()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) = delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

    override fun onFinish() {
        delegate.onFinish()
        releasePrintingWebView(webView)
    }
}

/** The [Activity] behind a composable's context, unwrapped through the theme/locale wrappers
 *  Compose and the app put in between; null when there is none (a preview, a service). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Content-Security-Policy for rendered email. JavaScript is already disabled on the WebView; this is
 * defense-in-depth that also kills plugins, iframes and form submissions outright, while still
 * allowing inline styles and images. Remote images are permitted by the policy but gated at load time
 * by [BlockingWebViewClient], so the "show images" toggle keeps working.
 */
internal const val CSP_META =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
        "default-src 'none'; img-src data: cid: http: https:; style-src 'unsafe-inline'; " +
        "font-src data:; media-src data: cid: http: https:; " +
        "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'\">"

/**
 * The viewport meta of both reader templates: `width=device-width` and NOTHING ELSE.
 *
 * `initial-scale=1` is deliberately absent. A page-declared initial scale pins the first zoom, and
 * with it pinned `loadWithOverviewMode` (see [applyFitSettings]) is switched off: the engine will no
 * longer shrink a page that is still wider than the screen after [FIT_CSS], which is the last line
 * of defence for the messages the CSS cannot narrow. Without it the layout width is the device width
 * (so ordinary mail is untouched, scale 1) and anything that still overflows is zoomed OUT to fit.
 * No `maximum-scale`/`user-scalable=no` either: pinch must keep working.
 */
internal const val FIT_VIEWPORT_META = """<meta name="viewport" content="width=device-width">"""

/**
 * The WebView half of "every message fits the width". Both settings are required together:
 *  - `useWideViewPort` makes the engine honour the page's viewport meta (width=device-width) instead
 *    of laying out at the legacy 980px desktop width;
 *  - `loadWithOverviewMode` zooms a page that is still wider than that viewport out to fit it.
 * `setInitialScale(0)` hands the first scale back to the engine (0 = automatic), so nothing the host
 * set earlier can pin it. One function, called from the single place a body WebView is built, so the
 * settings cannot be dropped from one path and kept in another.
 */
internal fun WebView.applyFitSettings() {
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    setInitialScale(0)
}

/**
 * The rules that make a DESKTOP-AUTHORED email fit a phone's width, carried VERBATIM by both reader
 * templates — the inverted dark one and the light one. They share no other CSS (each duplicates its
 * own copy of `.s-deceptive`, `details.s-quote` and the rest), and a fit rule present in only one of
 * them would mean the page fits in one theme and overflows in the other.
 *
 * Every rule here is `!important`, and that is the whole point rather than a flourish. A viewport
 * meta, `useWideViewPort` and `loadWithOverviewMode` were ALL already set and the page still
 * overflowed, because none of them outranks what the message itself declares:
 *  - `style` is a GLOBAL_ATTRIBUTE and `width` is allowed on img/table/td/th/col in
 *    [sanitiseReceivedHtml], so `<table width="1200">` and `style="width:900px"` arrive INTACT;
 *  - an inline `style` attribute beats any stylesheet rule that is not `!important`.
 * The previous `img { max-width: 100% }` was the only width rule in either template, it was not
 * `!important`, and it named the one element (`img`) that is rarely what actually overflows.
 *
 * `body *` rather than a list of tags: email holds its width on whatever element is to hand — a
 * table, a td, a wrapper div, a figure. Capping every descendant at the width of its own containing
 * block is what a phone needs and costs a well-behaved responsive email nothing, since its elements
 * are already inside that bound.
 *
 * TRADE, named: `table-layout: fixed` is deliberately NOT set. It would force even a table whose
 * cells are individually wider than the screen to fit, but it re-proportions columns and destroys
 * layouts that were rendering correctly. Capping the table instead lets auto layout reflow its cells,
 * which fits every table whose content can wrap — and content that genuinely cannot wrap (a wide
 * image, an unbreakable token) is handled by the rules below. What survives all of it stays
 * reachable by pinch-zoom (`setSupportZoom`/`builtInZoomControls`), which is why that stays on.
 */
internal const val FIT_CSS = """
              /* Nothing the message declares may be wider than the screen. */
              /* `min-width` has to be beaten as well, and beaten HERE, because the cascade
                 resolves min-width AFTER max-width: a wrapper carrying `style="min-width:600px"`
                 — the Mailchimp and HubSpot house style — wins outright over `max-width: 100%
                 !important` and holds the page 600 pixels wide however emphatic we are about the
                 maximum. That single line of CSS precedence is most of "the same kind of mail
                 fits sometimes and not others". Zero is the initial value of min-width for
                 everything that is not a flex item, so this takes nothing away; for a flex item
                 it REPLACES the `auto` that refuses to shrink below content size, which is the
                 one case where declaring it also helps. */
              body * { max-width: 100% !important; min-width: 0 !important; }
              /* The two roots `body *` does not reach: a message that ships a full document brings its
                 own <html>/<body> sizing (`<body style="width:800px">`, `<body width="700">`), and a
                 cap on the children is no help while the container they are capped AGAINST is wide. */
              html, body { max-width: 100% !important; min-width: 0 !important; }
              /* Cap the width WITHOUT distorting the picture: clamping width alone squashes a
                 2000px-wide image into the phone's aspect ratio. `height: auto` restores the
                 intrinsic ratio, and must be !important for the same cascade reason as the rest —
                 a `height` attribute or inline height is exactly what it has to beat. */
              img, video, svg, canvas { height: auto !important; }
              /* One 200-character URL is a single unbreakable token: no width cap above can split
                 it, so it alone keeps the page wider than the screen until it is allowed to break. */
              td, th, li, p, div, a, span, blockquote {
                     overflow-wrap: break-word !important; word-break: break-word !important; }
              /* A <pre> the MESSAGE wrote keeps `white-space: pre` and scrolls forever. (`pre.plain`,
                 the body we paint ourselves, is wrapped by its own rule in the light template — this
                 is the message's own, in both templates.) */
              pre, code { white-space: pre-wrap !important; overflow-wrap: break-word !important; }
              /* `white-space: nowrap` on a table cell forbids line breaking outright, and the rule
                 above is then powerless — `overflow-wrap` only says WHERE a line may break, never
                 THAT one may. A cell told not to wrap holds its whole content on one line and the
                 table around it grows to match, which is the other half of why some mails overflow
                 and others do not: inline `nowrap` is the house style for button cells, price
                 columns and date columns in every newsletter builder there is.
                 Only td and th, deliberately. `white-space` is inherited, so overriding it on
                 every element would also overwrite the `pre-wrap` and `pre-line` that a mail uses
                 on a div to keep the line breaks of a text body it converted to HTML — collapsing
                 those is not fitting the mail, it is mangling it. Table cells are the one place
                 nowrap is common and preserved whitespace is not, and an inner element that does
                 declare pre-wrap still wins over what it inherits from its cell. */
              td, th { white-space: normal !important; }"""

// `internal`, not private, since #149: what the page does with [ReaderBody.richHtml] — invert the page
// or paint it in the app's colours — is the half of the reading-mode decision that lives HERE, and it
// was the half nothing could see.
internal fun buildHtmlDocument(
    email: Email,
    inlineImages: Map<String, String> = emptyMap(),
    theme: EmailTheme = EmailTheme("#ffffff", "#111111", "#0b5fff", false),
    // CSS LENGTHS, unit included — not pixel counts. Built by [bodySpacerCss], which explains why
    // the unit is `vh`; "0" is a valid CSS length and means "nothing measured yet".
    topSpacerCss: String = "0",
    bottomSpacerCss: String = "0",
    // NO DEFAULT, unlike every parameter above it. A default of "" would let a caller build the
    // document of a body-less message and get `<p></p>` back: the empty screen this string exists to
    // prevent. Whoever builds a document says what the reader is told when there is nothing to show.
    noContent: String,
    // THIS one HAS a default, for the opposite reason: a caller who forgets it gets a document with
    // no quote fold, which is the page as it has always been — never an empty one.
    quoteLabel: String = "",
    // The wording of the marker put beside a link whose text names one host and whose target is
    // another. Defaults to off for [quoteLabel]'s reason — a caller who forgets it gets the page as it
    // has always been. Empty means the pass does not run at all, so the print document (whose links
    // cannot be tapped) simply omits it.
    deceptiveLinkLabel: String = "",
    // The fragment with its TEXT translated in place (InPlaceHtmlTranslation), standing in for the
    // message's own fragment; null = the message as it arrived. Everything downstream - inline images,
    // the dark-mode rewrite, the quote fold - runs on it exactly as it would on the original.
    translatedFragment: String? = null,
): String {
    // Which part of the message the document carries, and what that implies for the page, is
    // decided in ONE place a test can run — see [readerBody].
    val body = readerBody(email, noContent)
    var inner = translatedFragment ?: body.fragment
    // The two rewrites below UNDO MARKUP the message wrote, so they apply only while the document
    // actually carries that markup — [ReaderBody.richHtml], NOT "the message has HTML" (#149). A body
    // we paint ourselves is HTML-escaped text, and escaping touches neither ':' nor '-': a message
    // whose TEXT reads literally `prefers-color-scheme: dark` would otherwise come out mutilated, in
    // the mode whose whole promise is to show the text as it is.
    if (body.richHtml) {
        // Embed inline images: replace cid: references with their data URIs. Shared with the
        // print document ([buildPrintDocument]), which must embed them the same way.
        inner = embedInlineImages(inner, inlineImages)
        // Neutralise the email's own dark-mode styles. On a dark-mode device the WebView matches
        // `prefers-color-scheme: dark`, so a marketing email renders its dark variant — which our
        // invert then turns light (the "white band in dark theme" bug). A declared color-scheme is
        // ignored by Android WebView, so the media queries are defanged directly.
        inner = neutraliseDarkModeStyles(inner)
        // Name the real target of a link that claims a different one (#193). BEFORE the quote fold,
        // so a deceptive link inside quoted history is marked too — a phishing mail that forges a
        // thread is exactly where one hides. The marker is a <span>, so folding still only has
        // blockquotes to find.
        if (deceptiveLinkLabel.isNotBlank()) {
            inner = markDeceptiveLinks(inner) { host ->
                "<span class=\"s-deceptive\">${htmlEscape(deceptiveLinkLabel.format(host))}</span>"
            }
        }
        // Fold the TRAILING quoted block behind a native <details> (no script, so the CSP is
        // untouched). INSIDE the richHtml guard, after the two rewrites: it wraps the message's OWN
        // blockquotes. NOT shared with [buildPrintDocument] — paper does not unfold.
        inner = foldTopLevelQuotes(inner, quoteLabel)
    }
    // Bracket the content with two real DOCUMENT elements (scrollable, unlike body padding which Blink
    // drops, or view padding which clips the last lines): a transparent TOP spacer of the header's
    // height and a BOTTOM spacer for the overlaying bar, the latter COLOURED so it does not invert to
    // white in dark mode.
    // The height is emitted VERBATIM, unit and all, and that unit is `vh` (see [bodySpacerCss]): a
    // `px` suffix appended here puts a RATIO where a pixel count used to be (#171).
    inner = "<div aria-hidden=\"true\" style=\"height:${topSpacerCss}\"></div>" + inner +
        "<div aria-hidden=\"true\" class=\"s-end\" style=\"height:${bottomSpacerCss}\"></div>"
    // What is RENDERED, not what is available: in plain-text mode a message that HAS HTML is still
    // painted by us, in the app's own colours, and inverting that page would give dark text on a
    // light band — the "white band in dark theme" bug seen from the other side.
    val richHtml = body.richHtml
    if (theme.dark && richHtml) {
        // Rich HTML carries its own (usually white) backgrounds we cannot restyle reliably, so render
        // it light and invert the whole page. The filter MUST sit on the root <html>: marketing emails
        // are full documents and the parser hoists their <body> out of any wrapper <div>, so a div
        // filter would invert nothing. hue-rotate keeps colours roughly intact.
        return """
            <!DOCTYPE html><html><head>
            $CSP_META
            $FIT_VIEWPORT_META
            <meta name="color-scheme" content="only light">
            <style>
              /* Force the email to render its LIGHT design before we invert: many marketing
                 emails ship a prefers-color-scheme:dark variant, which the WebView would pick
                 on a dark-mode device — inverting an already-dark email yields a wrong, light
                 result (e.g. a white band in dark theme). "only light" opts the page out of the
                 system dark preference so its dark media queries don't fire. */
              html { color-scheme: only light; }
              /* Transparent page background: the filter only inverts the document's own
                 painting, not the WebView's native background (set to the app surface).
                 So empty areas show the app's dark surface instead of a pure-black box
                 (white inverted) that clashed with it. !important beats the document-level
                 background many emails set via an inline style on <body> (which otherwise
                 leaves a bright band below the content where the body shows through).
                 Inner wrappers keep their own backgrounds and still get inverted. */
              html { filter: invert(1) hue-rotate(180deg); background: transparent !important; }
              body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: #111111;
                     background: transparent !important;
                     word-wrap: break-word; overflow-wrap: break-word; }
              /* Emoji are colour glyphs, so the page filter turns a yellow face blue (issue #58).
                 Counter-invert them exactly like media, restoring their real colours. */
              img, picture, video, svg, iframe, .s-emo { filter: invert(1) hue-rotate(180deg); }
              $FIT_CSS
              a { color: #0b57d0; }
              /* Bottom spacer reserving room for the overlaying Reply/Forward bar. Transparent so it
                 shows the WebView's native surface (same trick as the page background above): a fixed
                 colour would invert to pure black (#fff -> #000), which doesn't match the app's dark
                 surface and left a visibly-off rectangle at the end of the mail. */
              .s-end { background: transparent; }
              /* First-level quote folded behind a native <details> ([foldTopLevelQuotes]).
                 ⛔ NO counter-invert on the summary, unlike img/svg/.s-emo above: the button is
                 text of ours, drawn in the page's own colours, so it must invert WITH the page.
                 Counter-inverting it would paint dark-on-dark. */
              details.s-quote { margin: 12px 0; }
              details.s-quote > summary { cursor: pointer; padding: 4px 0; font-size: 0.9em; opacity: 0.8; }
              /* The real target of a link that named a different one ([markDeceptiveLinks]). Text of
                 ours, so — like the quote summary above — it inverts WITH the page and is NOT
                 counter-inverted. Not a colour warning: a red that survives invert+hue-rotate is not
                 worth guessing at, and the words are the warning. */
              .s-deceptive { margin-left: 0.4em; font-size: 0.85em; opacity: 0.85; white-space: nowrap; }
            </style></head><body>${wrapEmoji(inner)}</body></html>
        """.trimIndent()
    }
    // Plain/simple text (or light mode): paint with the resolved theme colours directly,
    // so the body's background matches the app surface (no seam below the message).
    val bg = theme.background
    val fg = theme.text
    val link = theme.link
    // Rich HTML reaches here only in light theme: pin it to its light design, so a
    // prefers-color-scheme:dark email does not render dark on a dark-mode device.
    val colorScheme = if (richHtml) "only light" else if (theme.dark) "dark" else "light"
    return """
        <!DOCTYPE html><html><head>
        $CSP_META
        $FIT_VIEWPORT_META
        <meta name="color-scheme" content="$colorScheme">
        <style>
          html { color-scheme: $colorScheme; }
          html, body { background-color: $bg; }
          body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: $fg;
                 word-wrap: break-word; overflow-wrap: break-word; }
          $FIT_CSS
          a { color: $link; }
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
          ${einkReaderCss(theme)}
          /* The "text derived from HTML" line (#149). Only ever reached here: a derived body is
             painted by us, so it is never in the inverted branch above. */
          p.s-note { margin: 0 0 12px; font-size: 0.85em; opacity: 0.7; }
          /* Bottom spacer reserving room for the overlaying Reply/Forward bar; surface colour so it
             blends with the body background. */
          .s-end { background: $bg; }
          /* The quote-fold button ([foldTopLevelQuotes]). Both templates carry this rule: they
             share no CSS, and a rule in only one of them leaves the other with the browser's
             default disclosure triangle. */
          details.s-quote { margin: 12px 0; }
          details.s-quote > summary { cursor: pointer; padding: 4px 0; font-size: 0.9em; opacity: 0.8; }
          /* See the inverted template: both carry this rule, because they share no CSS and a marker
             styled in only one of them is invisible in the other theme. */
          .s-deceptive { margin-left: 0.4em; font-size: 0.85em; opacity: 0.85; white-space: nowrap; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

/** Resolved theme colours (CSS hex) handed to the email WebView so it matches the app. */
internal data class EmailTheme(
    val background: String,
    val text: String,
    val link: String,
    val dark: Boolean,
    /** Cloud E-ink: a larger serif reading face, no colour (rich mail greyscaled), underlined black links. */
    val eink: Boolean = false,
)

/** The extra reading CSS of the Cloud E-ink style, or nothing for the other styles. */
internal fun einkReaderCss(theme: EmailTheme): String = if (!theme.eink) "" else """
          html { filter: grayscale(1) contrast(1.15); }
          body { font-family: Georgia, 'Noto Serif', serif !important; font-size: 1.2em !important; line-height: 1.6 !important; }
          pre.plain { font-family: Georgia, 'Noto Serif', serif !important; }
          a { color: #000000 !important; text-decoration: underline !important; }
          * { animation: none !important; transition: none !important; box-shadow: none !important; }"""

// escapeHtml() lived here; it was character-for-character `core.data.text.htmlEscape`, which the
// body decision now uses (see ReaderBody.kt). One escaper, not two.

private const val ZWJ = '\u200D' // zero-width joiner: glues a multi-part emoji into one glyph
private const val VS15 = '\uFE0E' // variation selector: force TEXT (monochrome) presentation
private const val VS16 = '\uFE0F' // variation selector: force EMOJI (colour) presentation
private const val KEYCAP = '\u20E3' // combining enclosing keycap (1⃣)

/** Elements whose content is not markup (or is counter-inverted already): copied verbatim. */
private val OPAQUE_ELEMENTS = setOf("style", "script", "title", "textarea", "svg")

/**
 * BMP code points that default to emoji (colour) presentation. Everything else in the BMP is a text
 * glyph the mail font paints in the body colour, so it must NOT be counter-inverted — unless the
 * author forced colour with a VS16, which [emojiClusterEnd] honours.
 */
private val EMOJI_BMP = listOf(
    0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
    0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
    0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
    0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
    0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
    0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
    0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
)

private fun isEmojiPresentation(cp: Int): Boolean = when {
    cp < 0x231A -> false
    cp in 0x1F000..0x1FAFF -> true // pictographs, faces, transport, flags, symbols
    cp > 0xFFFF -> false
    else -> EMOJI_BMP.any { cp in it }
}

/**
 * Whether [cp] may carry a variation selector, i.e. whether it is an `Emoji=Yes` base. In ASCII
 * only `#`, `*` and the digits qualify (keycap bases); everything else starts at U+00A9 (©).
 */
private fun isEmojiBase(cp: Int): Boolean =
    cp >= 0x00A9 || cp == '#'.code || cp == '*'.code || cp in '0'.code..'9'.code

/**
 * End index of the emoji cluster starting at [i] (base + variation selector, skin tone, keycap or
 * flag-tag modifiers), or -1 if there is no emoji there.
 */
private fun emojiClusterEnd(s: String, i: Int): Int {
    if (i >= s.length) return -1
    val cp = s.codePointAt(i)
    var j = i + Character.charCount(cp)
    val emoji = when {
        j < s.length && s[j] == VS15 -> false // author asked for the monochrome text glyph
        isEmojiPresentation(cp) -> true
        // ✔️, ©️, keycap bases: colour forced by the author. Only a real Emoji=Yes base can carry a
        // VS16; without that guard a stray U+FE0F right after an HTML character reference would split
        // the entity (`&#127876;️` -> `&#127876<span…>;️</span>`, rendering as "🎄;").
        j < s.length && s[j] == VS16 && isEmojiBase(cp) -> true
        else -> false
    }
    if (!emoji) return -1
    while (j < s.length) {
        val m = s.codePointAt(j)
        val modifier = m == VS16.code || m == KEYCAP.code ||
            m in 0x1F3FB..0x1F3FF || m in 0xE0020..0xE007F
        if (!modifier) break
        j += Character.charCount(m)
    }
    return j
}

/**
 * End index of the run of emoji starting at [start], or [start] if none. Clusters joined by a ZWJ
 * (👨‍👩‍👧, 🏳️‍🌈) render as ONE glyph, so the run must keep them together; adjacent emoji are
 * folded into the same run too, which just means fewer spans.
 */
private fun emojiRunEnd(s: String, start: Int): Int {
    var i = start
    while (true) {
        val end = emojiClusterEnd(s, i)
        if (end < 0) break
        i = end
        if (i < s.length && s[i] == ZWJ && emojiClusterEnd(s, i + 1) > 0) i++
    }
    return i
}

/** Copies the markup starting at `<` in [s] to [out]; returns the index just past it. */
private fun copyMarkup(s: String, start: Int, out: StringBuilder): Int {
    if (s.startsWith("<!--", start)) {
        val end = s.indexOf("-->", start + 4)
        val stop = if (end < 0) s.length else end + 3
        out.append(s, start, stop)
        return stop
    }
    var i = start + 1
    var quote = ' '
    while (i < s.length) {
        val c = s[i]
        if (quote != ' ') {
            if (c == quote) quote = ' '
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            i++
            break
        }
        i++
    }
    val tagEnd = minOf(i, s.length)
    out.append(s, start, tagEnd)
    if (start + 1 < s.length && s[start + 1] == '/') return tagEnd
    var n = start + 1
    while (n < s.length && s[n].isLetterOrDigit()) n++
    val name = s.substring(start + 1, n).lowercase()
    if (name !in OPAQUE_ELEMENTS || s.regionMatches(tagEnd - 2, "/>", 0, 2)) return tagEnd
    val close = s.indexOf("</$name", tagEnd, ignoreCase = true)
    val stop = if (close < 0) s.length else close
    out.append(s, tagEnd, stop)
    return stop
}

/**
 * Wraps every emoji in the mail's TEXT in a `.s-emo` span carrying the counter-filter, so the
 * page-wide invert of the dark reader is undone on colour glyphs and a yellow face stays yellow (#58).
 * Tags, attributes, URLs and the content of `<style>`/`<script>`/`<svg>` are copied verbatim: a wrong
 * edit there would corrupt the message.
 */
internal fun wrapEmoji(html: String): String {
    val out = StringBuilder(html.length + 64)
    var i = 0
    while (i < html.length) {
        val c = html[i]
        val next = if (i + 1 < html.length) html[i + 1] else ' '
        if (c == '<' && (next.isLetter() || next == '/' || next == '!' || next == '?')) {
            i = copyMarkup(html, i, out)
            continue
        }
        val end = emojiRunEnd(html, i)
        if (end > i) {
            out.append("<span class=\"s-emo\">").append(html, i, end).append("</span>")
            i = end
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Reflow RFC 3676 `format=flowed` plain text: join soft-wrapped lines (those ending in a space) into
 * one logical line, keeping hard breaks and blank lines so the `<pre>` render wraps at the viewport
 * instead of showing the sender's ~72-char breaks (#4).
 *
 * JMAP exposes the body without the `format` parameter, so we key off the convention itself: a
 * trailing space before the newline. Non-flowed text passes through unchanged; a single leading space
 * is space-stuffing and is removed; `-- ` is a hard break despite its trailing space.
 */
internal fun reflowFormatFlowed(text: String): String {
    val lines = text.split("\n")
    val sb = StringBuilder()
    for ((i, raw) in lines.withIndex()) {
        var line = raw.removeSuffix("\r")
        if (line.startsWith(" ")) line = line.substring(1) // undo space-stuffing
        sb.append(line)
        val soft = line.endsWith(" ") && line != "-- "
        if (!soft && i != lines.lastIndex) sb.append('\n')
    }
    return sb.toString()
}

// Date formatting lives in [MailDates] — the composer writes the same formatted date into a reply's
// attribution and a forward's header, and the two must not drift.
private fun formatFull(iso: String?): String = MailDates.formatFull(iso)

private fun formatWith(iso: String?, formatter: DateTimeFormatter): String =
    MailDates.formatWith(iso, formatter)

/** Snooze presets (label → epoch-millis), computed in the device's time zone. Shared by the message
 *  view and the inbox selection menu. */
internal fun snoozePresets(context: android.content.Context): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    val nextWeek = at(now.with(java.time.DayOfWeek.MONDAY).plusWeeks(1), 8)
    return listOf(
        context.getString(R.string.snooze_in_1_hour) to now.plusHours(1),
        context.getString(R.string.snooze_this_evening) to thisEvening,
        context.getString(R.string.snooze_tomorrow) to at(now.plusDays(1), 8),
        context.getString(R.string.snooze_next_week) to nextWeek,
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}

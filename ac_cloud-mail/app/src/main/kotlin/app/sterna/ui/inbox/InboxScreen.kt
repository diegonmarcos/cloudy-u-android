package app.sterna.ui.inbox

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.message.snoozePresets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import app.sterna.core.data.settings.AppStyle
import app.sterna.ui.theme.LocalAppStyle
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.R
import app.sterna.ui.canSnoozeIn
import app.sterna.ui.components.EmailListItem
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.TernRefreshIndicator
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.components.verticalScrollbar
import app.sterna.ui.theme.LocalMailListPalette
import app.sterna.ui.theme.LocalSternaDarkTheme
import app.sterna.ui.mailNav
import app.sterna.ui.isOutgoingFolder
import app.sterna.ui.messageFolderRole
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.showsDraftBadge
import app.sterna.ui.DRAWER_SHEET_WIDTH_DP
import app.sterna.ui.drawerRowHeight
import app.sterna.ui.FOLDER_LABEL_TEXT_SIZE_SP
import app.sterna.ui.FOLDER_LABEL_LINE_HEIGHT_SP
import app.sterna.ui.DRAWER_FOLDER_MENU_TAP_SIZE_DP
import app.sterna.ui.PaneLayout
import app.sterna.ui.PaneSplit
import app.sterna.ui.showsRecipients
import app.sterna.ui.showsRecipientsInThread
import app.sterna.ui.search.SearchCount
import app.sterna.ui.search.SearchDisplay
import app.sterna.ui.search.searchCount
import app.sterna.ui.search.searchDisplay
import app.sterna.ui.theme.MailMetrics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** Folder roles whose menu has no "Notify about new mail": notifying about one's own sent/drafts/trash/junk
 *  would be noise. The Inbox HAS it (on by default; unticked it stops announcing, see InboxViewModel.setFolderWatched). */
private val watchMenuHiddenRoles = setOf("sent", "drafts", "trash", "junk")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (emailId: String, accountId: String?, index: Int, fromSearch: Boolean) -> Unit,
    /** Open the reading view on one message of an inline-expanded conversation. The thread key and
     *  the message's position travel along, so the reader pages over that conversation only. */
    onOpenThreadMessage: (emailId: String, accountId: String?, threadKey: ThreadKey, index: Int) -> Unit,
    onCompose: () -> Unit,
    /** Reopen compose with the draft of a send the user just undid. */
    onReopenDraft: () -> Unit,
    /** Open a saved draft in compose for editing (#63) — tapping a row in the Drafts folder. */
    onEditDraft: (emailId: String, accountId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    /** The Home page: this phone's mail statistics, per account. Reached from the drawer only. */
    onOpenHome: () -> Unit,
    /** Advanced search, carrying whatever is already typed in the search bar so it isn't retyped. */
    onOpenSearch: (query: String) -> Unit,
    /** The drawer's Starred entry: the search screen on `$flagged`, already run. Not a folder. */
    onOpenStarred: () -> Unit,
    /** The pane's Assigned view: the mails that have a task. */
    onOpenAssigned: () -> Unit = {},
    onOpenScheduled: () -> Unit,
    onOpenSnoozed: () -> Unit,
    onOpenOutbox: () -> Unit,
    /** "Mail by sender": what this phone holds, per sender, for the current account. */
    onOpenMailBySender: () -> Unit,
    accounts: List<app.sterna.core.data.account.StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onOpenAccountSettings: (String) -> Unit,
    /** The inbox entry's own ViewModel — required, so the host names the owner (#103). */
    viewModel: InboxViewModel,
    /** The split the host decided from the window width; null = one pane, as before (#103). */
    panes: PaneSplit? = null,
    /** What the right pane shows when there is a split; null = no pane at all. */
    detail: (@Composable () -> Unit)? = null,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val listRows = viewModel.pagedListRows.collectAsLazyPagingItems()
    val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()
    // Which attachment is downloading, and the question asked before a large one is fetched over a
    // metered network. Collected once here rather than per row: one download runs at a time.
    val openingAttachmentKey by viewModel.openingAttachment.collectAsStateWithLifecycle()
    val meteredAttachment by viewModel.meteredAttachment.collectAsStateWithLifecycle()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedKeys.collectAsStateWithLifecycle()
    val selectionAllRead by viewModel.selectionAllRead.collectAsStateWithLifecycle()
    // Folders the move-to-folder picker offers: the SELECTED message's account's, not the active
    // account's (#73). Empty until something is selected.
    val moveTargetMailboxes by viewModel.selectionMailboxes.collectAsStateWithLifecycle()
    // …and whether THAT account hides its unsubscribed folders (#174). Read next to the list, not
    // folded into it: moveTargets needs the whole list for the parent paths it shows (#109).
    val moveTargetsOnlySubscribed by viewModel.selectionOnlySubscribed.collectAsStateWithLifecycle()
    // The picker's account row (#189): the account the selection belongs to, and the one chosen
    // on the row, if any — both live in the ViewModel so the choice survives a rotation.
    val moveOwnerAccountId by viewModel.moveOwnerAccountId.collectAsStateWithLifecycle()
    val moveAccountId by viewModel.moveAccountId.collectAsStateWithLifecycle()
    // Inline conversation expansion: which threads are unfolded, and their lazily-loaded members.
    val expandedThreads by viewModel.expandedThreads.collectAsStateWithLifecycle()
    val threadMembers by viewModel.threadMembers.collectAsStateWithLifecycle()
    // (account, folder) → role, every account: an unfolded conversation's rows span the viewed
    // folder(s) plus Sent, and in the unified list they are not all in the current account (#115).
    val folderRoles by viewModel.folderRoles.collectAsStateWithLifecycle()
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Mailbox?>(null) }
    var folderToDelete by remember { mutableStateOf<Mailbox?>(null) }
    var folderToDeleteRecursive by remember { mutableStateOf<Mailbox?>(null) }
    var folderToAddChild by remember { mutableStateOf<Mailbox?>(null) }
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val watchedFolders by viewModel.watchedFolders.collectAsStateWithLifecycle()
    // The current account's explicit fold/unfold choice per folder — persisted. A folder with no
    // key in here is one nobody decided anything about.
    val collapsedFolders by viewModel.collapsedFolders.collectAsStateWithLifecycle()
    // Can a folder row of THIS account badge unread at all? False on IMAP, whose rows carry a hard
    // 0 — and the default fold rests on that badge, so where there is none nothing folds itself.
    val folderRowsBadgeUnread by viewModel.folderRowsBadgeUnread.collectAsStateWithLifecycle()
    val pendingPurge by viewModel.pendingPurge.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val pendingFolderDelete by viewModel.pendingFolderDelete.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val outboxPending by viewModel.outboxPending.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraft.collectAsStateWithLifecycle()
    val outboxCount by viewModel.outboxCount.collectAsStateWithLifecycle()
    // Two counts on purpose (#70): this one has no grace and feeds the menu entry only.
    val outboxQueuedCount by viewModel.outboxQueuedCount.collectAsStateWithLifecycle()
    val outboxHasFailures by viewModel.outboxHasFailures.collectAsStateWithLifecycle()
    val highlightId by viewModel.highlightId.collectAsStateWithLifecycle()
    // The message open in the reading pane beside the list, on a wide window: its row is painted
    // "current" (#103). Empty under 600 dp, where nothing ever writes it.
    val pane by viewModel.readingPane.collectAsStateWithLifecycle()
    // Rows whose swipe was played to the edge over a write that then failed. They are still in the
    // table (network-first), only gone from the screen — see [InboxViewModel.swipeRewind].
    val swipeRewind by viewModel.swipeRewind.collectAsStateWithLifecycle()
    // Promote the just-opened row's highlight on ON_START rather than ON_RESUME, so the flash is
    // already underway as the list reappears, reading as part of the back gesture.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.activatePendingHighlight()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // From 1 200 dp the folder drawer is drawn beside the list and never slides (#103). Read ONCE
    // here and handed to everything that would otherwise offer a way to open it.
    val permanentDrawer = panes?.layout == PaneLayout.Desk
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Codeberg #30: the rows hand a narrow strip at the start edge back to the drawer's own drag,
    // but only in three-button navigation, where Android reports no gesture inset there. Read above
    // the Scaffold, so no consumed inset can make the edge look free.
    val density = LocalDensity.current
    val edgeBandPx = drawerBandPx(
        systemGestureInsetPx = WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current),
        bandPx = with(density) { DRAWER_EDGE_BAND_DP.dp.toPx() },
        drawerCanOpen = !permanentDrawer,
    )
    // Hoisted strings for snackbars shown from non-composable LaunchedEffect coroutines.
    val undoLabel = stringResource(R.string.inbox_undo)
    val context = LocalContext.current

    // When the user switches accounts, re-point the inbox at the new one (skip the first
    // composition — the ViewModel already loads on init).
    var lastAccount by rememberSaveable { mutableStateOf(currentAccountId) }
    LaunchedEffect(currentAccountId) {
        if (currentAccountId != lastAccount) {
            lastAccount = currentAccountId
            viewModel.onAccountChanged()
        }
    }

    // Expanded-conversation members are a static snapshot in the ViewModel; re-sync it with the
    // cache on every (re)entry, so a child read in the reader loses its unread dot on return.
    LaunchedEffect(Unit) { viewModel.refreshThreadMembers() }

    // Surface transient action errors (e.g. "no Archive folder") in a snackbar.
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearMessage()
    }

    // Back peels the list's modes off one at a time before the app is left; the order lives in
    // [inboxBackAction] so exactly one handler is ever enabled. Search is one of those modes, not a
    // screen (#86), and so is the reading pane, between the selection and the search (#103).
    val backAction = inboxBackAction(selectionActive, detailOpen = detail != null && pane.anchor != null, ui.searching, ui.atInbox)
    BackHandler(enabled = backAction == InboxBackAction.CLEAR_SELECTION) { viewModel.clearSelection() }
    BackHandler(enabled = backAction == InboxBackAction.CLOSE_DETAIL) { viewModel.closePane() }
    BackHandler(enabled = backAction == InboxBackAction.CLOSE_SEARCH) { viewModel.setSearchActive(false) }
    BackHandler(enabled = backAction == InboxBackAction.SHOW_INBOX) { viewModel.showInbox() }

    // Move-to-folder picker for the current selection. The offered folders (and their order)
    // come from [moveTargets], shared with the reader's own picker (#73).
    if (showMoveSheet) {
        // The filter field's text (#182). Held INSIDE the `if`, so leaving the dialog drops the
        // composition group and the field comes back empty next time.
        var moveQuery by remember { mutableStateOf("") }
        // The folder on screen is left out of the list ONLY while the list is the selection's own
        // account's: listing another account's, its id names nothing there, or a homonym (#92).
        val moveExcludedMailbox = pickerExcludedMailbox(moveAccountId, moveOwnerAccountId, ui.selectedMailboxId)
        val targets = remember(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed) {
            moveTargets(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed)
        }
        // Nested folders sharing a leaf are one and the same row without their parent path (#109).
        // Resolved against the WHOLE folder list, not the offered subset. Memoized like [targets]:
        // the filter field re-runs this block on every keystroke (#182).
        // #883 Jev may suggest ONE of the offered folders; it is listed first, nothing else changes (no opinion = as before)
        val offeredNames = targets.map { it.id to mailboxDisplayName(it.role, it.name) }
        val suggestedMove by produceState<String?>(initialValue = null, targets) {
            value = viewModel.suggestMoveTarget(offeredNames)
        }
        val movePaths = remember(targets, moveTargetMailboxes) {
            targets.map { folder -> mailboxPathLabel(folder, moveTargetMailboxes) }
        }
        // Painted over the WHOLE offered list and never over the filtered one: what the filter
        // compares is what the row shows, and the rows painted must not depend on what was typed.
        val moveRows = ArrayList<FolderPickerRow>(targets.size)
        for ((folder, path) in targets.zip(movePaths)) {
            moveRows += FolderPickerRow(folder, mailboxDisplayName(folder.role, folder.name), path)
        }
        MailJev.promoteInPlace(moveRows, suggestedMove) { it.folder.id }   // #883: Jev's suggestion first; none = untouched
        val shownMoveRows = filterFolderRows(moveRows, moveQuery)
        AlertDialog(
            onDismissRequest = { showMoveSheet = false; viewModel.chooseMoveAccount(null) },
            title = { Text(stringResource(R.string.inbox_move_to_folder)) },
            text = {
                Column {
                    // The account whose folders are listed, and the way to list another's (#189).
                    // Not drawn at all when the selection spans accounts, where the move only takes
                    // the owner's messages (selectionPickerAccounts).
                    MoveAccountRow(selectionPickerAccounts(accounts, selectedKeys), moveOwnerAccountId, moveAccountId, viewModel::chooseMoveAccount)
                    // The filter field (#182), OUTSIDE the scroller so it stays put while the list
                    // moves under it. Deliberately WITHOUT a FocusRequester and without an IME
                    // "search" action: it filters what is on screen, it asks the server nothing.
                    Box(Modifier.fillMaxWidth()) {
                        if (moveQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.inbox_filter_folders),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Bounded, like the search bar this shape is copied from: a Box is
                                // as tall as its tallest child, so an unbounded hint at a large font
                                // scale would steal that height from the list underneath.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = MailMetrics.s16),
                            )
                        }
                        TextField(
                            value = moveQuery,
                            onValueChange = { moveQuery = it },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                if (moveQuery.isNotEmpty()) {
                                    IconButton(onClick = { moveQuery = "" }) {
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
                    // `weight(1f, fill = false)` on the scroller and NOTHING on the field: in a
                    // Column the unweighted child is measured FIRST, so the field keeps its height
                    // and it is the list that gives way.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        if (shownMoveRows.isEmpty() && moveQuery.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.inbox_no_folder_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MailMetrics.s12),
                            )
                        }
                        shownMoveRows.forEach { row ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // The row's OWN folder, never a rank in the filtered list.
                                        viewModel.reportMoveChoice(row.folder.id)
                                        viewModel.moveSelectedTo(row.folder.id, moveAccountId)
                                        showMoveSheet = false
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
                                        // TWO lines, not one: a single line elides at the END, in
                                        // dp, after the path was already trimmed by character count,
                                        // eating the nearest parent at a large font size.
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
                TextButton(onClick = { showMoveSheet = false; viewModel.chooseMoveAccount(null) }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }

    // Create folder.
    if (showCreateFolder) {
        var name by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() { if (name.isNotBlank()) { viewModel.createFolder(name); showCreateFolder = false } }
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.inbox_new_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Create a subfolder under the chosen parent.
    folderToAddChild?.let { parent ->
        var name by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() {
            if (name.isNotBlank()) {
                viewModel.createFolder(name, parentId = parent.id)
                viewModel.setFolderCollapsed(parent.id, false) // reveal the new child
                folderToAddChild = null
            }
        }
        AlertDialog(
            onDismissRequest = { folderToAddChild = null },
            title = { Text(stringResource(R.string.inbox_new_subfolder_in, mailboxDisplayName(parent.role, parent.name))) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { folderToAddChild = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // The one question a chip tap ever asks. Nothing is REFUSED here -- the tap was consent -- but a
    // chip lives in a scrolling list, where a brushed finger is a real way to arrive, and this is
    // where the fleet's "never spend the owner's mobile data unasked" rule lands for a file the user
    // did choose. Small files and unmetered networks never see it: [DownloadLimits.needsMeteredConfirmation].
    meteredAttachment?.let { pending ->
        AlertDialog(
            // Dismissing by tapping outside is the same answer as Cancel, and it downloads nothing.
            onDismissRequest = { viewModel.dismissMeteredAttachment() },
            title = { Text(stringResource(R.string.attachment_metered_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.attachment_metered_body,
                        pending.name,
                        // The size is SAID, not implied by a warning. The chip itself shows only the
                        // name -- width on a phone is spent on the one thing that identifies the file
                        // -- so this sentence is the first and only place the cost is stated, which
                        // is exactly where a decision about cost needs it.
                        formatAttachmentSize(pending.bytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmMeteredAttachment() }) {
                    Text(stringResource(R.string.attachment_metered_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMeteredAttachment() }) {
                    Text(stringResource(R.string.inbox_cancel))
                }
            },
        )
    }

    // Rename folder.
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() { if (name.isNotBlank()) { viewModel.renameFolder(folder.id, name); folderToRename = null } }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.inbox_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_rename)) }
            },
            dismissButton = { TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Delete folder. A folder with subfolders gets a second, recursive-delete warning.
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.inbox_delete_folder_title)) },
            text = { Text(stringResource(R.string.inbox_delete_folder_body, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.subfolderIdsOf(folder.id).isNotEmpty()) {
                        folderToDeleteRecursive = folder
                    } else {
                        viewModel.deleteFolder(folder.id, folder.name)
                    }
                    folderToDelete = null
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Second confirmation: the folder has subfolders, which go down with it.
    folderToDeleteRecursive?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDeleteRecursive = null },
            title = { Text(stringResource(R.string.inbox_delete_folder_recursive_title)) },
            text = { Text(stringResource(R.string.inbox_delete_folder_recursive_body, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id, folder.name)
                    folderToDeleteRecursive = null
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDeleteRecursive = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Bumped by an Undo that restores a message, to reveal it if it lands back at the very top
    // of the list (LazyColumn otherwise anchors to the old first row, hiding it — Codeberg #23).
    var revealTopSignal by remember { mutableIntStateOf(0) }

    // Show an Undo snackbar whenever a swipe deletes/archives a message.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.label,
            actionLabel = undoLabel,
            withDismissAction = true,
            // Indefinite ON PURPOSE: the ViewModel's deadline closes this window by clearing `undo`,
            // and this effect is keyed on it. A duration here would be a SECOND clock, and every
            // drift ends with Undo pressed after the banner was already cancelled.
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undo()
            revealTopSignal++
        } else viewModel.clearUndo()
    }

    // Undo-send: while a message is held in the outbox, offer an Undo. The label is set at send
    // time (#70), so the snackbar says what actually happened rather than always "sent". It is
    // dismissed automatically when the hold-back elapses (pending clears → restart).
    LaunchedEffect(outboxPending) {
        val pending = outboxPending ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = pending.label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            // Drop the queued row and hand the draft back; the reopen is driven separately by the
            // restoredDraft collector below, so it can't be lost when this coroutine is torn down.
            viewModel.undoSend()
        }
    }
    // Reopen compose with the draft of an undone send. Kept out of the Undo snackbar handler above:
    // undoSend() clears outboxPending, which cancels that handler's coroutine, so reopening from
    // there raced the teardown and could be silently dropped.
    LaunchedEffect(restoredDraft) {
        if (restoredDraft != null) onReopenDraft()
    }
    // A send that failed past its retries is no longer a transient snackbar: it stays in the
    // outbox and is surfaced by the badge + failure banner below.
    // Empty-trash hold-back: offer Undo until the purge fires (pending clears → dismiss).
    LaunchedEffect(pendingPurge) {
        val label = pendingPurge ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoEmptyTrash()
            revealTopSignal++
        }
    }
    // Permanent (Trash) delete hold-back: the destroy is deferred behind this Undo, so
    // deleting from Trash is undoable too (Codeberg #23). Pending clears when it fires.
    LaunchedEffect(pendingDelete) {
        val label = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
            revealTopSignal++
        }
    }
    // Folder-delete hold-back: same pattern (pending clears when the delete fires).
    LaunchedEffect(pendingFolderDelete) {
        val label = pendingFolderDelete ?: return@LaunchedEffect
        // The delete is triggered from the drawer, which would cover the snackbar —
        // close it so the Undo is actually visible during its window.
        if (drawerState.isOpen) drawerState.close()
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteFolder()
    }
    val scope = rememberCoroutineScope()
    // Make sure the drawer is shut whenever the inbox returns to the foreground: a drawer item
    // animates `drawerState.close()` then navigates, and instant navigation disposes this screen
    // before that animation finishes. Snap it closed, with no visible animation.
    DisposableEffect(lifecycleOwner, drawerState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && drawerState.isOpen) {
                scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listState = rememberLazyListState()
    // exitUntilCollapsed pairs with the MediumTopAppBar: the folder + account get a
    // full-width second line at the top, then collapse into a compact bar on scroll.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // When an Undo restores a message that belongs at the top, wait for the row to repopulate and
    // pin the list to the top — otherwise LazyColumn keeps the old anchor and the restored message
    // sits just above the viewport, invisible (#23).
    LaunchedEffect(revealTopSignal) {
        if (revealTopSignal == 0 || listState.firstVisibleItemIndex != 0) return@LaunchedEffect
        val before = listState.layoutInfo.totalItemsCount
        withTimeoutOrNull(3_000) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > before }
        }
        listState.animateScrollToItem(0)
    }

    // Opening a *different* folder starts at the top of that folder's list; returning to the same
    // folder must keep the position the user left. So the reset fires on a genuine folder change,
    // tracked via a saved key, not on every re-entry of this screen.
    var lastFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        val key = "${ui.unified}:${ui.selectedMailboxId}"
        if (lastFolderKey != null && lastFolderKey != key) listState.scrollToItem(0)
        lastFolderKey = key
    }

    // Newly arrived mail is prepended ABOVE the viewport: LazyColumn anchors the scroll to the
    // previously-first row. If the list was already at the very top and no drag/fling is in flight,
    // follow it up to reveal the arrival; anywhere below, never move the user.
    val searchActive = ui.searching && ui.searchQuery.isNotBlank()
    LaunchedEffect(ui.selectedMailboxId, ui.unified, searchActive) {
        if (searchActive) return@LaunchedEffect
        var prevKey: String? = null
        var wasAtTop = true
        snapshotFlow {
            val key = if (listRows.itemCount > 0) listRows.peek(0)?.email?.let { "${it.accountId}|${it.id}" } else null
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            key to atTop
        }.collect { (key, atTop) ->
            // Sequential collect + snapshotFlow conflation coalesce rapid arrivals into one
            // settled animation instead of queueing one per message.
            if (prevKey != null && key != null && key != prevKey &&
                wasAtTop && !listState.isScrollInProgress
            ) {
                listState.animateScrollToItem(0)
                wasAtTop = true
            } else {
                wasAtTop = atTop
            }
            prevKey = key
        }
    }

    // Staggered first-screen entry: the first rows of a freshly-opened folder fade + slide in once.
    // ONLY the first screen and ONLY on the initial show — the cascade self-locks after it plays
    // and on the first scroll, so rows recycled back into view never re-fade. Honours reduced motion.
    val listMotionOn = rememberMotionEnabled()
    var entryPlayed by rememberSaveable(ui.selectedMailboxId, ui.unified) { mutableStateOf(false) }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        if (entryPlayed) return@LaunchedEffect
        snapshotFlow { listRows.itemCount }.first { it > 0 }
        delay(ENTRY_CAP * ENTRY_STEP_MS + ENTRY_ROW_MS + 80L)
        entryPlayed = true
    }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        entryPlayed = true
    }

    // The list and its reading pane, hoisted into one lambda so they are composed ONCE and wrapped
    // by whichever of the two drawer envelopes this window gets: written twice, the two copies
    // drift, and crossing 1 200 dp would throw away the whole list's state.
    val content: @Composable () -> Unit = {
        // The message list inside the account's folders and labels as the side menu (or rows), per the section's mode (#1189).
        MailFolderNav(ui, { viewModel.select(it) }) {
        // The list's Scaffold, beside the reading pane when the host decided a split (#103): the
        // pane is a SIBLING of this Scaffold (each takes the status bar once) and lives inside the
        // drawer's envelope. The Scaffold block below is deliberately NOT re-indented.
        ListDetailPanes(panes, detail) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            // The list's ground is the message-list PANE — palette's dark grey behind the black
            // cards (#472). Each row lifts itself off it as its own rounded card.
            containerColor = LocalMailListPalette.current.pane,
            // And what is drawn ON the pane (#518). The pane is a palette colour, not a scheme role,
            // so the default `contentColorFor(containerColor)` answers Unspecified and falls back to
            // LocalContentColor -- which nothing above this Scaffold sets, leaving material3's
            // default: Color.Black, on a near-black pane. Stated, because it cannot be inferred.
            contentColor = LocalMailListPalette.current.unreadText,
            // imePadding: deleting from search happens with the keyboard open, which would
            // otherwise cover the Undo snackbar for its whole window (zero inset when closed).
            snackbarHost = { SnackbarHost(snackbarHostState, Modifier.imePadding()) },
            topBar = {
                if (selectionActive) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_cancel_selection))
                            }
                        },
                        title = {
                            // A check + the count: compact and language-proof. The full localized
                            // label is kept for screen readers.
                            val countLabel = stringResource(R.string.inbox_selected_count, selectedKeys.size)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = countLabel },
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(MailMetrics.s22),
                                )
                                Spacer(Modifier.width(MailMetrics.s8))
                                Text(selectedKeys.size.toString(), maxLines = 1)
                            }
                        },
                        actions = {
                            // Toggle read/unread, keeping the selection (only the state changes).
                            IconButton(onClick = { viewModel.toggleSelectedRead() }) {
                                Icon(
                                    if (selectionAllRead) Icons.Filled.MarkEmailUnread else Icons.Filled.DoneAll,
                                    contentDescription = stringResource(if (selectionAllRead) R.string.inbox_mark_unread else R.string.inbox_mark_read),
                                )
                            }
                            val currentRole = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                            if (currentRole == "archive" || currentRole == "all") {
                                val inboxId = ui.mailboxes.firstOrNull { it.role == "inbox" }?.id
                                IconButton(
                                    onClick = { inboxId?.let { viewModel.moveSelectedTo(it) } },
                                    enabled = inboxId != null,
                                ) {
                                    Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.inbox_unarchive))
                                }
                            } else {
                                IconButton(onClick = { viewModel.archiveSelected() }) {
                                    Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.inbox_archive))
                                }
                            }
                            IconButton(onClick = { showMoveSheet = true }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.inbox_move_to_folder))
                            }
                            IconButton(onClick = { viewModel.deleteSelected() }) {
                                // In Trash the button destroys, not moves-to-Trash, so a Trash-can
                                // icon is misleading (#23). The SELECTION bar acts on many rows and
                                // has no single row to be judged by, so it keeps the view's role.
                                val trash = isTrashContext(visibleFolderRole(ui))
                                Icon(
                                    if (trash) Icons.Filled.DeleteForever else Icons.Filled.Delete,
                                    contentDescription = stringResource(if (trash) R.string.inbox_delete_forever else R.string.inbox_delete),
                                )
                            }
                            // Overflow: snooze + report/not-spam for the whole selection.
                            var selMenu by remember { mutableStateOf(false) }
                            var selSnooze by remember { mutableStateOf(false) }
                            val selContext = LocalContext.current
                            // Boxed with its button for the anchoring reason spelled out on the
                            // browse-bar overflow menu (#74). This one swaps its whole content for
                            // the snooze presets, so unboxed it could jump sideways while open.
                            Box {
                                IconButton(onClick = { selMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                                }
                                DropdownMenu(
                                    expanded = selMenu,
                                    onDismissRequest = { selMenu = false; selSnooze = false },
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    if (selSnooze) {
                                        snoozePresets(selContext).forEach { (label, until) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = { selMenu = false; selSnooze = false; viewModel.snoozeSelected(until) },
                                            )
                                        }
                                    } else {
                                        val inJunk = currentRole == "junk"
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_select_all)) },
                                            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                            onClick = { selMenu = false; viewModel.selectAll() },
                                        )
                                        // Spam-reporting acts on incoming mail; in Drafts and Sent
                                        // the selection is the user's own, so it is not offered (#82).
                                        if (!isOutgoingFolder(currentRole)) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(stringResource(if (inJunk) R.string.message_not_spam else R.string.message_report_spam))
                                                },
                                                leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                                                onClick = {
                                                    selMenu = false
                                                    if (inJunk) viewModel.notSpamSelected() else viewModel.reportSpamSelected()
                                                },
                                            )
                                        }
                                        // Snoozing is a promise to come back to a message, so it is
                                        // also gone in Spam and in the Trash (#82).
                                        if (canSnoozeIn(currentRole)) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.message_snooze)) },
                                                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                                onClick = { selSnooze = true },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                } else if (ui.searching) {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.setSearchActive(false) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_close_search))
                            }
                        },
                        title = {
                            val searchRole = ui.mailboxes
                                .firstOrNull { it.id == ui.selectedMailboxId }?.role
                            // The hint says what the search covers. In the unread view that is the
                            // whole account, while ui.mailboxName still holds the Inbox's name.
                            // Same string as the title, no tenth translation of the same word.
                            val scopeLabel = if (ui.unreadView) {
                                stringResource(R.string.inbox_unread_view)
                            } else if (ui.unified) {
                                stringResource(R.string.inbox_all_inboxes)
                            } else {
                                mailboxDisplayName(searchRole, ui.mailboxName)
                            }
                            val focusManager = LocalFocusManager.current
                            // The input stays single-line (a filled TextField can't grow past the
                            // app bar's height without clipping), but the hint is drawn as a
                            // separate Text behind it so a long folder name wraps to two lines.
                            Box(Modifier.fillMaxWidth()) {
                                if (ui.searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.inbox_search_in, scopeLabel),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(horizontal = MailMetrics.s16),
                                    )
                                }
                                TextField(
                                    value = ui.searchQuery,
                                    onValueChange = viewModel::setSearchQuery,
                                    singleLine = true,
                                    // Live search-as-you-type; the Search key just folds the keyboard.
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                )
                            }
                        },
                        actions = {
                            if (ui.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_clear))
                                }
                            }
                            IconButton(onClick = { onOpenSearch(ui.searchQuery) }) {
                                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.search_advanced_toggle))
                            }
                        },
                    )
                } else {
                    MediumTopAppBar(
                        title = {
                            Column {
                                // Both titles are resolved HERE, not in the ViewModel: a ViewModel
                                // outlives a configuration change and would keep the old language.
                                val selectedRole = ui.mailboxes
                                    .firstOrNull { it.id == ui.selectedMailboxId }?.role
                                Text(
                                    // The unread view selects no folder either, so without an arm
                                    // of its own the title would name the LAST FOLDER VISITED.
                                    if (ui.unreadView) {
                                        stringResource(R.string.inbox_unread_view)
                                    } else if (ui.unified) {
                                        stringResource(R.string.inbox_all_inboxes)
                                    } else {
                                        mailboxDisplayName(selectedRole, ui.mailboxName)
                                    },
                                    // titleMedium (not titleLarge) so the folder + account
                                    // both fit the Medium bar's title area at large font scales.
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!ui.unified && ui.accountName.isNotBlank()) {
                                    Text(
                                        ui.accountName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            // Nothing to open where the drawer is already beside the list; the slot
                            // is kept so the bar keeps ONE shape in the source. What an empty slot
                            // does to the title's start inset is NOT measured.
                            if (!permanentDrawer) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.inbox_menu))
                                }
                            }
                        },
                        actions = {
                            // The view controls, in the owner's order: View mode, Rank, Filter. Search
                            // follows as the LAST icon, then the overflow. The unread view forces its
                            // filter on, so there it is shown checked and cannot be lifted.
                            val listViewUi by viewModel.listViewUi.collectAsStateWithLifecycle()
                            val listViewActions = remember(viewModel) {
                                ListViewActions(
                                    onGroup = viewModel::setGroupMode,
                                    onRank = viewModel::setRankMode,
                                    onFilter = viewModel::toggleListFilter,
                                    onReset = viewModel::resetListView,
                                )
                            }
                            val shownViewUi = if (ui.unreadView) listViewUi.copy(filters = listViewUi.filters + ListFilter.UNREAD) else listViewUi
                            ListViewIconGroups(shownViewUi, listViewActions, unreadForced = ui.unreadView)
                            IconButton(onClick = { viewModel.setSearchActive(true) }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.inbox_search))
                            }
                            var overflowOpen by remember { mutableStateOf(false) }
                            val currentRole = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                            val isTrash = currentRole == "trash"
                            // The menu must be boxed WITH its button: a DropdownMenu anchors on the
                            // layout node that contains it, so emitted straight into the actions row
                            // a narrow menu opened under the leftmost icon (#74).
                            Box {
                                IconButton(onClick = { overflowOpen = true }) {
                                    BadgedBox(
                                        badge = {
                                            // Discreet dot when the outbox has pending or failed items;
                                            // error-tinted if any failed, otherwise the neutral accent.
                                            if (outboxCount > 0) {
                                                Badge(
                                                    containerColor = if (outboxHasFailures) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                                    }
                                }
                                // Frequent actions first, nearest the anchor; the rarely-visited
                                // Outbox comes after them (#48). In the Trash the destructive
                                // "Empty trash" is pushed to the very bottom.
                                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }, shape = MaterialTheme.shapes.medium) {
                                    // Every view function by name, checked when active, then Search.
                                    ListViewOverflowItems(shownViewUi, listViewActions, ui.unreadView) { overflowOpen = false }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_search)) },
                                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                        onClick = { viewModel.setSearchActive(true); overflowOpen = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_select_all)) },
                                        leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                        onClick = { viewModel.selectAll(); overflowOpen = false },
                                    )
                                    // Unread state is meaningless in Drafts and Sent — the mail
                                    // there is your own — so the bulk mark-as-read is dropped there
                                    // (#82). It stays in Trash: a deleted message can still be unread.
                                    if (!isOutgoingFolder(currentRole)) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_mark_all_read)) },
                                            leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                                            onClick = { viewModel.markAllRead(); overflowOpen = false },
                                        )
                                    }
                                    // The Trash trades the scheduled-messages shortcut for "Empty trash",
                                    // which is appended below rather than taking this slot.
                                    if (!isTrash) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_scheduled)) },
                                            leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                            onClick = { overflowOpen = false; onOpenScheduled() },
                                        )
                                        // Where snoozed messages can be found again (Codeberg #82) —
                                        // right beside the other "waiting on a clock" list.
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_snoozed)) },
                                            leadingIcon = { Icon(Icons.Filled.Snooze, contentDescription = null) },
                                            onClick = { overflowOpen = false; onOpenSnoozed() },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_outbox)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                                        // outboxQueuedCount, NOT the dot's outboxCount: it appears
                                        // the instant a message is queued, while the dot keeps its
                                        // 30 s grace (#70). Do not wire them to the same flow.
                                        trailingIcon = {
                                            if (outboxQueuedCount > 0) {
                                                Badge(
                                                    containerColor = if (outboxHasFailures) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                ) { Text(outboxQueuedCount.toString()) }
                                            }
                                        },
                                        onClick = { overflowOpen = false; onOpenOutbox() },
                                    )
                                    // After the Outbox, before anything destructive (#48). Absent in
                                    // the Trash for the same reason as the two lists above: it counts
                                    // the folders one keeps mail in.
                                    if (!isTrash) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_by_sender)) },
                                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                            onClick = { overflowOpen = false; onOpenMailBySender() },
                                        )
                                    }
                                    // Destructive, so it sits last (#48).
                                    if (isTrash) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.inbox_empty_trash),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.DeleteSweep,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = { overflowOpen = false; viewModel.emptyTrash() },
                                        )
                                    }
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            floatingActionButton = {
                // Gmail Dark wears Gmail's Compose button: the outlined pen on the (wallpaper's)
                // secondary container, in the extended FAB's 16dp-cornered shape.
                val gmail = LocalAppStyle.current == AppStyle.GMAIL_DARK
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.inbox_compose)) },
                    // When collapsed (scrolled) only the icon shows, so it must carry the
                    // label; when expanded the text already provides it.
                    icon = {
                        Icon(
                            if (gmail) Icons.Outlined.Edit else Icons.Filled.Create,
                            contentDescription = if (fabExpanded) null else stringResource(R.string.inbox_compose),
                        )
                    },
                    expanded = fabExpanded,
                    onClick = onCompose,
                    shape = if (gmail) MaterialTheme.shapes.large else FloatingActionButtonDefaults.extendedFabShape,
                    containerColor = if (gmail) MaterialTheme.colorScheme.secondaryContainer else FloatingActionButtonDefaults.containerColor,
                    contentColor = if (gmail) MaterialTheme.colorScheme.onSecondaryContainer else contentColorFor(FloatingActionButtonDefaults.containerColor),
                )
            },
        ) { padding ->
            // Grouped by domain, each group's row carries a heading naming the domain ([DomainGroupHeader]).
            val domainGrouped = viewModel.listViewUi.collectAsStateWithLifecycle().value.group == GroupMode.DOMAIN
            // One row renderer, shared by the search list and the paged browse list. Takes the row
            // modifier so the caller can pass `animateItem()` from its own LazyItemScope.
            val emailRow: @Composable (InboxRow, Modifier, Boolean, Int, Boolean) -> Unit = { row, rowModifier, animateEntry, entryIndex, fromSearch ->
                val email = row.email
                if (domainGrouped && !fromSearch) DomainGroupHeader(DomainGroupHeading.of(row))
                val ownerAccount = if (ui.unified) accounts.firstOrNull { it.id == email.accountId } else null
                // A conversation in the browse list can unfold inline; search results stay flat.
                val expandable = !fromSearch && row.threadExpandable
                // Account-qualified: two accounts of one server share thread ids, and a bare id
                // unfolded (and paged) the sibling account's homonymous conversation (#92).
                val threadKey = viewModel.threadKeyOf(email)
                val isExpanded = expandable && threadKey in expandedThreads
                // The role of the folder THIS ROW is filed in, not the one on screen: a list can
                // span several folders of one account, where "Delete permanently" and "Unarchive"
                // are true of some rows and false of their neighbours ([rowFolderRole]).
                val rowRole = rowFolderRole(email, ui, folderRoles, folderTrusted = !fromSearch)
                // And WHICH folder, in the one view that pages several: naming the folder is what
                // tells apart the rows the unread view shows once per folder. `row.threadCount`,
                // not 1: a conversation row can stand for messages in several folders.
                val rowFolder = unreadRowFolder(email, ui, folderTrusted = !fromSearch, inViewCount = row.threadCount)
                SwipeableEmailRow(
                    email = email,
                    originLabel = ownerAccount?.label() ?: rowFolder?.let { mailboxDisplayName(it.role, it.name) },
                    originColor = accountColorOf(ownerAccount?.color),
                    rightAction = swipe.right,
                    leftAction = swipe.left,
                    unarchiveContext = isUnarchiveContext(rowRole),
                    trashContext = isTrashContext(rowRole),
                    // Search results keep the sender line whatever folder they came from. Otherwise
                    // the shared decision: Sent/Drafts by folder (#59), a self-authored mail in the
                    // Trash by authorship (#69), but NOT one you sent yourself in the Inbox (#115).
                    showRecipients = !fromSearch && showsRecipients(
                        role = visibleFolderRole(ui),
                        unified = ui.unified,
                        selfAuthored = isSelfAuthored(email.from, sendAsIdentities(email, accounts)),
                    ),
                    // "(Draft)" by the keyword OR by the row's own folder: the keyword is memoised,
                    // not stored, so it left Drafts unmarked after a kill and forever on IMAP. The
                    // search branch keeps the keyword alone — `false` there would REMOVE a chip. The
                    // second chip says the draft has not reached the server yet (#95).
                    showNotUploadedBadge = isLocalDraftRow(email.id),
                    // The chips, and the tap that downloads one. Search results reach this list too
                    // and their rows carry no parts (the FTS table has no attachment column), so
                    // they simply draw none -- the chip cannot appear without something to open.
                    onOpenAttachment = { part -> viewModel.openAttachment(email, part) },
                    openingAttachmentKey = openingAttachmentKey,
                    // The row's Resume / Copy Code act on the MESSAGE, which a row does not carry
                    // (#514). This is the fetch, and it is the reader's own.
                    onLoadMessage = viewModel::messageWithBody,
                    showDraftBadge = if (fromSearch) email.isDraft else showsDraftBadge(
                        isDraft = email.isDraft,
                        accountId = email.accountId,
                        mailboxId = email.mailboxId,
                        roles = folderRoles,
                    ),
                    // A collapsed conversation acts on the whole thread; a flat row on its one message.
                    onSwipe = { action ->
                        if (expandable) performThreadSwipe(action, email, viewModel, ui, rowRole)
                        else performSwipe(action, email, viewModel, ui, rowRole)
                    },
                    onClick = {
                        // The decision lives in [rowTapAction]; this `when` only acts on it. A
                        // local draft opens in the composer like any other (#95) and is the FIRST
                        // argument, because the selection branch would refuse the row and the tap
                        // would do nothing. In Drafts a tap EDITS rather than opening the reader.
                        when (rowTapAction(isLocalDraftRow(email.id), selectionActive, expandable, fromSearch, rowRole)) {
                            RowTap.EDIT_DRAFT -> onEditDraft(email.id, email.accountId)
                            // A collapsed conversation selects/deselects all its members at once.
                            RowTap.SELECT_THREAD -> viewModel.toggleSelectThread(email)
                            RowTap.SELECT_ROW -> viewModel.toggleSelect(email)
                            RowTap.OPEN -> {
                                viewModel.onEmailOpened(email.id)
                                onOpenEmail(email.id, email.accountId, entryIndex, fromSearch)
                            }
                        }
                    },
                    onLongClick = {
                        if (expandable) viewModel.enterSelectionThread(email) else viewModel.enterSelection(email)
                    },
                    // The star is a gesture too, on every row, and reachable by TalkBack (#95):
                    // toggleFlag over a local id touches no row at all on IMAP and raises "Action
                    // failed" on JMAP. A local draft gets no star rather than a dead one.
                    onToggleFavourite = if (showsFavouriteStar(email.id)) {
                        {
                            val favouriting = !email.isFlagged
                            viewModel.toggleFlag(email)
                            // Only "Favourites first" moves the row on starring, so only there is
                            // there anywhere to follow it to (#111). Search results are excluded:
                            // `listState` is the BROWSE list's, so a jump scrolls another list.
                            if (favouriting && !fromSearch && ui.sortOrder == SortOrder.FLAGGED_FIRST) {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        }
                    } else null,
                    selected = email.emailKey() in selectedKeys,
                    // Account-qualified, like `selected` above (#92).
                    current = pane.anchor?.matches(email.accountId, email.id) == true,
                    // LOAD-BEARING BEYOND THIS ROW: one of the guards that make "selection active
                    // while the search results are empty" unreachable — simplify it to `true` and
                    gesturesEnabled = rowGesturesEnabled(email.id, selectionActive),
                    unread = row.unread,
                    threadCount = row.threadCount,
                    threadExpandable = expandable,
                    // The pill unfolds the thread in place; suppressed during multi-select.
                    onToggleExpand = if (expandable && !selectionActive) {
                        { viewModel.toggleThreadExpanded(email) }
                    } else null,
                    expanded = isExpanded,
                    animateEntry = animateEntry,
                    entryIndex = entryIndex,
                    highlighted = email.id == highlightId,
                    onHighlightShown = viewModel::clearHighlight,
                    // The swipe this row played did not take: bring it back. Account-qualified (#92).
                    rewindSwipe = needsSwipeRewind(swipeRewind, email.accountId, email.id),
                    onSwipeRewound = { viewModel.swipeRewound(email.emailKey()) },
                    drawerBandPx = edgeBandPx,
                    drawerCanOpen = !permanentDrawer,
                    modifier = rowModifier,
                )
                if (expandable) {
                    // The conversation minus the message THIS ROW is drawing, subtracted here
                    // because here is the only place both are in hand. The representative is a live
                    // value, re-picked on every write, so subtracting an id remembered from the tap
                    // drew the newcomer twice and dropped the message it displaced.
                    val members = ConversationExpansion.membersBelow(threadMembers[threadKey].orEmpty(), email.id)
                    ThreadChildren(
                        onOpenAttachment = { child, part -> viewModel.openAttachment(child, part) },
                        openingAttachmentKey = openingAttachmentKey,
                        // An unfolded child is a row like any other, and carries no body either.
                        onLoadMessage = viewModel::messageWithBody,
                        visible = isExpanded,
                        members = members,
                        unified = ui.unified,
                        accounts = accounts,
                        rightAction = swipe.right,
                        leftAction = swipe.left,
                        unarchiveContextFor = { child -> isUnarchiveContext(rowFolderRole(child, ui, folderRoles, folderTrusted = true)) },
                        trashContextFor = { child -> isTrashContext(rowFolderRole(child, ui, folderRoles, folderTrusted = true)) },
                        // Per child, through the SAME decision as the top-level row and the reader,
                        // on the child's OWN folder: an unfolded conversation spans the viewed
                        // folder(s) plus Sent, so your own reply there keeps its "To: …" (#69) while
                        // your own message echoed back into the Inbox reads by its sender (#115).
                        showRecipientsFor = { child ->
                            showsRecipientsInThread(
                                accountId = child.accountId,
                                mailboxId = child.mailboxId,
                                roles = folderRoles,
                                selfAuthored = isSelfAuthored(child.from, sendAsIdentities(child, accounts)),
                            )
                        },
                        // Same shape, same reason: a child is judged by the folder IT is filed in,
                        // and the role map only exists in this scope.
                        showDraftBadgeFor = { child ->
                            showsDraftBadge(
                                isDraft = child.isDraft,
                                accountId = child.accountId,
                                mailboxId = child.mailboxId,
                                roles = folderRoles,
                            )
                        },
                        // Same shape, same reason, for the origin chip. Trusted: these children come
                        // from the cache the list pages, never from the search index. Count of 1
                        // because a child IS one message, so each of them can name its folder.
                        folderFor = { child -> unreadRowFolder(child, ui, folderTrusted = true, inViewCount = 1) },
                        highlightId = highlightId,
                        paneAnchor = pane.anchor,
                        selectionActive = selectionActive,
                        selectedKeys = selectedKeys,
                        onOpenChild = { child ->
                            // Judged by ITS OWN folder, like its swipe below: in the Drafts folder
                            // a child that IS a draft edits in compose, while the reply already
                            // sent in the same conversation keeps the reader.
                            when (childTapAction(rowFolderRole(child, ui, folderRoles, folderTrusted = true))) {
                                RowTap.EDIT_DRAFT -> onEditDraft(child.id, child.accountId)
                                // Only OPEN can land here: a child's selection is settled before
                                // onOpenChild is called, so no SELECT_* comes out of childTapAction.
                                else -> {
                                    viewModel.onEmailOpened(child.id)
                                    // The reading view pages over exactly what this row was showing
                                    // when the reader tapped, recorded from here rather than rebuilt
                                    // from a representative remembered since the unfold. The index is
                                    // only a fallback — the reader resolves the opening page by id.
                                    val order = viewModel.recordThreadOrder(threadKey, email, members)
                                    val childIndex = order.indexOfFirst { it.first == child.id }
                                    onOpenThreadMessage(child.id, child.accountId, threadKey, childIndex.coerceAtLeast(0))
                                }
                            }
                        },
                        onSwipeChild = { action, child ->
                            performSwipe(action, child, viewModel, ui, rowFolderRole(child, ui, folderRoles, folderTrusted = true))
                        },
                        onToggleChildFavourite = { child -> viewModel.toggleChildFlag(child) },
                        onEnterSelectionChild = { child -> viewModel.enterSelection(child) },
                        onToggleSelectChild = { child -> viewModel.toggleSelect(child) },
                        onHighlightShown = viewModel::clearHighlight,
                        // A child swipes through performSwipe → swipeRemove like the row above it.
                        // Its rewind is a no-op, knowingly: the view model's mask drops the child out
                        // of the members flow before the call. What this hands the child is the way
                        // to give its key back, on the fold and unfold that brings it home.
                        swipeRewindKeys = swipeRewind,
                        onSwipeRewound = { child -> viewModel.swipeRewound(child.emailKey()) },
                        drawerBandPx = edgeBandPx,
                        drawerCanOpen = !permanentDrawer,
                    )
                }
            }

            val refreshState = rememberPullToRefreshState()
            // The list pane the cards sit on (#472): the gutter around every card is this colour
            // showing through, so it must be the palette's pane — never a scheme surface, which is
            // close enough to the card in the dark scheme to erase the separation.
            Column(Modifier.fillMaxSize().padding(padding).background(LocalMailListPalette.current.pane)) {
            // Offline, failed, or neither — never "offline" for a failure the device did not
            // confirm. See [refreshNotice]: the VPN-killswitch case (#65) still reads offline; what
            // no longer does is a server that answered and refused.
            val notice = refreshNotice(ui.offline, ui.error)
            // Thin line above the list, but only when there are cached rows to sit above (WYSIWYG).
            // The zero-rows case shows the matching centred state below instead.
            if (listRows.itemCount > 0) {
                when (notice) {
                    RefreshNotice.OFFLINE -> OfflineBanner()
                    RefreshNotice.ERROR -> SyncErrorBanner(ui.error.orEmpty())
                    RefreshNotice.NONE -> Unit
                }
            }
            // A calm, tappable line when a send has permanently failed: route to the outbox.
            if (outboxHasFailures) {
                OutboxFailureBanner(onClick = onOpenOutbox)
            }
            PullToRefreshBox(
                // The DISPLAY, not the truth: fed ui.refreshing the tern lit the instant refresh()
                // was called, on every folder tap and cold start (#178). ui.refreshing keeps its own
                // two readers — the centre-empty arm below (#63) and the seed of the state flow.
                isRefreshing = ui.showRefreshIndicator,
                onRefresh = {
                    viewModel.refreshRequested()
                    // Also re-attempt a failed fetch-older append, so the pull gesture
                    // clears the sticky "couldn't load more" footer, not just Retry.
                    listRows.retry()
                },
                modifier = Modifier.fillMaxSize().weight(1f),
                state = refreshState,
                indicator = {
                    TernRefreshIndicator(
                        state = refreshState,
                        isRefreshing = ui.showRefreshIndicator,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                val searchActive = ui.searching && ui.searchQuery.isNotBlank()
                val refreshLoading = listRows.loadState.refresh is LoadState.Loading
                // Paging keeps presenting the PREVIOUS selection's snapshot until the new PagingData
                // inserts its first page. Without this guard the rows branch draws another folder's
                // mail under the new header; every field of listKey is also a PageKey member.
                val listKey = ListKey(ui.accountId, ui.selectedMailboxId, ui.unified, ui.unreadView)
                // ONE read of the presented snapshot, shared by the two derivations below, or the
                // guard compares one folder's row against another folder's ownership.
                val presentedRows = listRows.itemSnapshotList.items
                val firstRow = presentedRows.firstOrNull()
                val presented = rowsSignature(listRows.itemCount, firstRow)
                // Do those rows PROVE they belong to another selection? Every other signal the guard
                // has can be produced by the OLD generation on its own. Both ENDS are asked: in
                // Drafts the first row is the phone's own draft and would vouch for the old folder's
                // mail behind it. Only for a single folder is the question decidable — rowsForeign.
                val foreign = rowsForeign(listKey, firstRow, presentedRows.lastOrNull())
                // Valve: after ROWS_GUARD_GIVE_UP_MS with nothing loading and nothing moved, give the
                // screen back to the rows. Keyed on listKey, so the next switch re-arms the guard.
                val gaveUp = remember(listKey) { mutableStateOf(false) }
                LaunchedEffect(listKey) { delay(ROWS_GUARD_GIVE_UP_MS); gaveUp.value = true }
                // Written BEFORE it is read in the same pass, and idempotent: advanceRowsGuard
                // converges, so this settles instead of recomposing for ever.
                val guard = remember { mutableStateOf(RowsGuard(listKey, presented, refreshLoading, false)) }
                guard.value = advanceRowsGuard(guard.value, listKey, presented, refreshLoading, gaveUp.value, foreign)
                // AFTER the step, never hoisted above it: read first, this is the previous frame's
                // answer, and the frame that arms the guard draws the old folder's rows anyway.
                val staleRows = guard.value.stale
                // The centred ring runs under the SAME grace as the tern (#178) and speaks for ONE
                // thing: a page loading in the view already on screen — hence refreshLoading &&
                // !staleRows. The arm below still has to keep CATCHING whether or not it draws:
                // letting it fall through hands the centre to the empty-folder or offline scene.
                val ringShowing = rememberGracedIndicator(refreshLoading && !staleRows)
                when {
                    searchActive -> when (searchDisplay(ui.searchResults.size, ui.searchLoading, ui.searchComplete)) {
                        SearchDisplay.RESULTS ->
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s6),
                                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        // "At least N" when the search stopped short, and while the
                                        // server leg is in flight: on a cold index the local pass can
                                        // be a small slice, and a total read as final (#102).
                                        text = when (searchCount(ui.searchComplete, ui.searchLoading)) {
                                            SearchCount.EXACT ->
                                                pluralStringResource(R.plurals.search_result_count, ui.searchResults.size, ui.searchResults.size)
                                            SearchCount.AT_LEAST ->
                                                pluralStringResource(R.plurals.search_result_count_capped, ui.searchResults.size, ui.searchResults.size)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        // weight, so a long count in a large font scale wraps instead
                                        // of squeezing the progress hint beside it to 0 dp.
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // "At least N" alone can't say whether the search is still
                                    // running or stopped short, so a spinner sits beside it while a
                                    // leg is in flight, in a slot reserved whether or not it shows.
                                    // A spinner says nothing to a screen reader: the slot says it.
                                    val progressHint = when {
                                        ui.searchLoading -> stringResource(R.string.search_still_running)
                                        !ui.searchComplete -> stringResource(R.string.search_incomplete)
                                        else -> null
                                    }
                                    Box(
                                        Modifier.size(MailMetrics.s12).semantics {
                                            progressHint?.let { contentDescription = it }
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (ui.searchLoading) {
                                            CircularProgressIndicator(
                                                Modifier.size(MailMetrics.s12),
                                                strokeWidth = MailMetrics.hair,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                    itemsIndexed(ui.searchResults, key = { _, it -> "${it.accountId}|${it.id}" }) { index, email ->
                                        emailRow(InboxRow(email, threadCount = 1, unread = !email.isSeen), Modifier.animateItem(), false, index, true)
                                    }
                                }
                            }
                        SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))
                        // Found nothing, but didn't get to the end (a leg failed, or its cap): that
                        // has NOT proven there is nothing, and "try other words" would send the user
                        // rewording a query that never fully ran. Same wording as the search screen.
                        SearchDisplay.INCOMPLETE_EMPTY -> EmptyState(
                            art = EmptyArt.SEARCH,
                            title = stringResource(R.string.search_incomplete),
                            modifier = Modifier.align(Alignment.Center),
                        )
                        SearchDisplay.EMPTY -> EmptyState(
                            art = EmptyArt.SEARCH,
                            title = stringResource(R.string.inbox_no_results),
                            body = stringResource(R.string.empty_search_body),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    // !staleRows: these rows may still be the PREVIOUS selection's, and drawing
                    // them under the new folder's header is the defect this guard exists for.
                    listRows.itemCount > 0 && !staleRows ->
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                                .verticalScrollbar(listState, listRows.itemCount),
                        ) {
                            items(
                                count = listRows.itemCount,
                                key = listRows.itemKey { "${it.email.accountId}|${it.email.id}" },
                            ) { index ->
                                // animateItem keeps each row identified across Paging snapshot swaps
                                // so a read/unread toggle re-binds in place instead of blinking.
                                // Placement-only (no fade), so a new page doesn't stutter the scroll.
                                listRows[index]?.let { row ->
                                    emailRow(
                                        row,
                                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                        listMotionOn && !entryPlayed && index < ENTRY_CAP,
                                        index,
                                        false,
                                    )
                                }
                            }
                            // Footer: server fetch-on-scroll (RemoteMediator) progress/errors.
                            when (listRows.loadState.append) {
                                is LoadState.Loading -> item(key = "append-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(MailMetrics.s16),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        LoadingRing(Modifier.size(MailMetrics.s28), strokeWidth = MailMetrics.s2)
                                    }
                                }
                                is LoadState.Error -> item(key = "append-error") {
                                    // Column (not Row): a long localized message must not squeeze
                                    // the Retry button down to a 1-char-wide, multi-line stub.
                                    Column(
                                        Modifier.fillMaxWidth().padding(MailMetrics.s16),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                                    ) {
                                        Text(
                                            stringResource(R.string.inbox_load_more_failed),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Button(onClick = { listRows.retry() }) { Text(stringResource(R.string.inbox_retry)) }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    // A refresh is already spoken for by the tern at the top (#63). The centre stays
                    // EMPTY rather than falling through to the branches below, where the
                    // empty-folder scene would claim the folder holds nothing mid-fetch.
                    ui.refreshing -> Unit
                    // No tern for a paging refresh, so the centred indicator is the only thing here.
                    // staleRows has to stay ABOVE the offline/error/empty scenes, or a switch that
                    // runs no refresh falls through and the screen claims the folder is empty.
                    // `gaveUp && !refreshLoading` does not bound it: inert while a query is out.
                    refreshLoading || staleRows -> if (ringShowing) LoadingRing(Modifier.align(Alignment.Center)) else Unit
                    notice == RefreshNotice.OFFLINE -> PullableCenter {
                        EmptyState(
                            art = EmptyArt.OFFLINE,
                            title = stringResource(R.string.empty_offline_title),
                            body = stringResource(R.string.empty_offline_body),
                            modifier = Modifier.align(Alignment.Center),
                            action = {
                                Button(onClick = viewModel::refreshRequested) { Text(stringResource(R.string.inbox_retry)) }
                            },
                        )
                    }
                    // Nothing cached AND the refresh failed: the same "it failed, here is what it
                    // said, try again" shape as the load-more footer — never the offline scene,
                    // whose promise ("we'll sync as soon as you're back") would be untrue here.
                    notice == RefreshNotice.ERROR -> PullableCenter {
                        Column(
                            Modifier.align(Alignment.Center).padding(MailMetrics.s32),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                        ) {
                            Text(
                                stringResource(R.string.sync_error_banner, ui.error.orEmpty()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = viewModel::refreshRequested) { Text(stringResource(R.string.inbox_retry)) }
                        }
                    }
                    else -> {
                        // Which scene, which words, and whether a way out is offered: one decision,
                        // taken by emptyListScene — the inbox (hero), the trash, any other folder,
                        // the unread filter, or the unread VIEW, which selects no folder.
                        val role = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                        val scene = emptyListScene(
                            unreadView = ui.unreadView,
                            unreadToggle = ui.unreadOnly,
                            unified = ui.unified,
                            selectedMailboxId = ui.selectedMailboxId,
                            folderRole = role,
                        )
                        // Empty list still pulls to refresh; tapping the empty space opens the
                        // drawer — except from 1 200 dp, where the drawer is on screen and the tap
                        // has nothing to do: null, so the empty state is not even clickable.
                        PullableCenter(onClick = if (permanentDrawer) null else ({ scope.launch { drawerState.open() } })) {
                            EmptyState(
                                art = scene.art,
                                title = stringResource(scene.title),
                                body = stringResource(scene.body),
                                modifier = Modifier.align(Alignment.Center),
                                // Filtered-empty offers a one-tap way out (clear the filter). A
                                // genuinely empty inbox has none: the corner Compose FAB covers it,
                                // so this avoids two compose buttons (#25).
                                action = if (scene.clearsFilter) {
                                    {
                                        Button(onClick = { viewModel.toggleUnreadOnly() }) {
                                            Text(stringResource(R.string.empty_unread_action))
                                        }
                                    }
                                } else {
                                    null
                                },
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
    // ONE drawer content, two envelopes. `drawerState` is closed from the content in BOTH branches,
    // deliberately: close() on an already-Closed state does nothing, so the six
    // `drawerState.close()` inside [DrawerContent] need no branch of their own (#103).
    if (permanentDrawer) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(DRAWER_SHEET_WIDTH_DP.dp)) {
                    DrawerContent(
                        accounts = accounts,
                        currentAccountId = currentAccountId,
                        ui = ui,
                        watchedFolders = watchedFolders,
                        collapsedFolders = collapsedFolders,
                        folderRowsBadgeUnread = folderRowsBadgeUnread,
                        viewModel = viewModel,
                        scope = scope,
                        drawerState = drawerState,
                        onSwitchAccount = onSwitchAccount,
                        onOpenAccountSettings = onOpenAccountSettings,
                        onOpenSettings = onOpenSettings,
                        onOpenHome = onOpenHome,
                        onOpenStarred = onOpenStarred,
                        onOpenAssigned = onOpenAssigned,
                        onCreateFolder = { showCreateFolder = true },
                        onAddSubfolder = { folderToAddChild = it },
                        onRenameFolder = { folderToRename = it },
                        onDeleteFolder = { folderToDelete = it },
                    )
                }
            },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(DRAWER_SHEET_WIDTH_DP.dp)) {
                    DrawerContent(
                        accounts = accounts,
                        currentAccountId = currentAccountId,
                        ui = ui,
                        watchedFolders = watchedFolders,
                        collapsedFolders = collapsedFolders,
                        folderRowsBadgeUnread = folderRowsBadgeUnread,
                        viewModel = viewModel,
                        scope = scope,
                        drawerState = drawerState,
                        onSwitchAccount = onSwitchAccount,
                        onOpenAccountSettings = onOpenAccountSettings,
                        onOpenSettings = onOpenSettings,
                        onOpenHome = onOpenHome,
                        onOpenStarred = onOpenStarred,
                        onOpenAssigned = onOpenAssigned,
                        onCreateFolder = { showCreateFolder = true },
                        onAddSubfolder = { folderToAddChild = it },
                        onRenameFolder = { folderToRename = it },
                        onDeleteFolder = { folderToDelete = it },
                    )
                }
            },
            content = content,
        )
    }
}

/**
 * The ONE folder drawer, drawn by both envelopes (#103). The carousel's `remember`s belong to the
 */
@Composable
private fun DrawerContent(
    accounts: List<app.sterna.core.data.account.StoredAccount>,
    currentAccountId: String,
    ui: MailUi,
    watchedFolders: Set<String>,
    collapsedFolders: Map<String, Boolean>,
    folderRowsBadgeUnread: Boolean,
    viewModel: InboxViewModel,
    scope: CoroutineScope,
    drawerState: DrawerState,
    onSwitchAccount: (String) -> Unit,
    onOpenAccountSettings: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenStarred: () -> Unit,
    /** The pane's Assigned view: the mails that have a task. */
    onOpenAssigned: () -> Unit = {},
    /** The four dialogs the drawer opens live with the screen, not with the sheet: hoisted as
     *  callbacks so a dialog outlives the modal sheet closing under it. */
    onCreateFolder: () -> Unit,
    onAddSubfolder: (Mailbox) -> Unit,
    onRenameFolder: (Mailbox) -> Unit,
    onDeleteFolder: (Mailbox) -> Unit,
) {
              // Scroll the whole drawer so long folder lists (and Settings below them) stay reachable (#7).
              Column(Modifier.verticalScroll(rememberScrollState())) {
                // Resolves the folder labels the SORT reads; the rows themselves still paint
                // through `mailboxDisplayName`, and both answer out of the same resources.
                val context = LocalContext.current
                // ONE modifier for every row in this drawer, deliberately shared rather than
                // repeated: the selected row is drawn as a filled pill, and a pill that is a
                // different height from the rows around it reads as a rendering fault. Sharing the
                // value is what makes "all the rows agree" true by construction instead of by
                // five call sites happening to match.
                val drawerRowModifier = Modifier
                    .padding(horizontal = MailMetrics.s12)
                    .heightIn(max = drawerRowHeight(LocalDensity.current))
                val currentAccount = accounts.firstOrNull { it.id == currentAccountId }
                val currentLabel = currentAccount?.label()
                    ?: ui.accountName.ifBlank { stringResource(R.string.inbox_app_name) }
                val otherAccounts = accounts.filter { it.id != currentAccountId }
                var accountsExpanded by remember { mutableStateOf(false) }
                val accountOffset = remember { Animatable(0f) }
                var chipWidth by remember { mutableIntStateOf(0) }
                val curIdx = accounts.indexOfFirst { it.id == currentAccountId }
                val nextAccount = if (accounts.size > 1 && curIdx >= 0) accounts[(curIdx + 1) % accounts.size] else null
                val prevAccount = if (accounts.size > 1 && curIdx >= 0) accounts[(curIdx - 1 + accounts.size) % accounts.size] else null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                    modifier = Modifier.fillMaxWidth().padding(start = MailMetrics.s16, end = MailMetrics.s4, top = MailMetrics.s8, bottom = MailMetrics.s8),
                ) {
                    // The active account as a little carousel: drag it sideways to bring the
                    // next/previous account in; releasing past a threshold switches account in place.
                    // A tap opens the account's settings; the chevron still lists all accounts.
                    Box(
                        modifier = Modifier.weight(1f).clipToBounds()
                            .onSizeChanged { chipWidth = it.width }
                            .then(
                                if (accounts.size > 1) {
                                    Modifier.pointerInput(accounts, currentAccountId) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, delta ->
                                                change.consume()
                                                val w = chipWidth.toFloat().coerceAtLeast(1f)
                                                scope.launch { accountOffset.snapTo((accountOffset.value + delta).coerceIn(-w, w)) }
                                            },
                                            onDragEnd = {
                                                val w = chipWidth.toFloat().coerceAtLeast(1f)
                                                val o = accountOffset.value
                                                scope.launch {
                                                    if (kotlin.math.abs(o) > w * 0.3f) {
                                                        val goNext = o < 0
                                                        val target = if (goNext) nextAccount else prevAccount
                                                        accountOffset.animateTo(if (goNext) -w else w, tween(200, easing = FastOutSlowInEasing))
                                                        if (target != null) onSwitchAccount(target.id)
                                                        accountOffset.snapTo(0f)
                                                    } else {
                                                        accountOffset.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                                                    }
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        AccountChip(
                            label = currentLabel,
                            color = accountColorOf(currentAccount?.color),
                            modifier = Modifier
                                .graphicsLayer { translationX = accountOffset.value }
                                .clickable {
                                    // The account you are LOOKING AT, shared or not: the settings a
                                    // shared mailbox owns (colour, notifications) are stored under
                                    // ITS id, so the login's settings would silence the wrong one (#31).
                                    onOpenAccountSettings(currentAccountId)
                                    scope.launch { drawerState.close() }
                                },
                        )
                        // The account being dragged toward, peeking in from the opposite edge.
                        val peek = if (accountOffset.value < 0f) nextAccount
                        else if (accountOffset.value > 0f) prevAccount else null
                        if (peek != null) {
                            AccountChip(
                                label = peek.label(),
                                color = accountColorOf(peek.color),
                                modifier = Modifier.graphicsLayer {
                                    translationX = accountOffset.value +
                                        if (accountOffset.value < 0f) chipWidth.toFloat() else -chipWidth.toFloat()
                                },
                            )
                        }
                    }
                    // Chevron → unfold the other accounts to switch to.
                    if (otherAccounts.isNotEmpty()) {
                        IconButton(onClick = { accountsExpanded = !accountsExpanded }) {
                            Icon(
                                if (accountsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.inbox_switch_account),
                            )
                        }
                    }
                    // The same settings the row at the very bottom of this drawer opens — the SAME
                    // onOpenSettings, so there is one destination and not two that have to be kept
                    // agreeing. It is repeated up here because reaching it otherwise means
                    // scrolling past 28 folders, which is the whole complaint. Last in the row, so
                    // it sits outside the account chevron rather than displacing it.
                    IconButton(
                        onClick = {
                            onOpenSettings()
                            scope.launch { drawerState.close() }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.inbox_settings),
                        )
                    }
                }
                if (accountsExpanded) {
                    otherAccounts.forEach { account ->
                        val label = account.label()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12),
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    onSwitchAccount(account.id)
                                    accountsExpanded = false
                                    scope.launch { drawerState.close() }
                                }
                                .padding(start = MailMetrics.s28, end = MailMetrics.s16, top = MailMetrics.s10, bottom = MailMetrics.s10),
                        ) {
                            Monogram(seed = label, label = label, color = accountColorOf(account.color))
                            Column {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // A delegated account switches like any other, so it keeps its row
                                // here — but you send as someone else, rights may be partial, and it
                                // vanishes if the owner unshares, so it says so quietly (#31).
                                if (account.isShared) {
                                    Text(
                                        stringResource(R.string.account_shared),
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
                HorizontalDivider(Modifier.padding(bottom = MailMetrics.s12))
                // The pane's three views: Inbox, Starred, Assigned (owner, 2026-10-13). Inbox is the
                // unified inbox when there are several accounts, else this account's own; Home, All
                // inboxes and Unread left the pane (Home is on the bottom nav).
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                    label = { DrawerLabel(stringResource(R.string.inbox_drawer_inbox)) },
                    selected = ui.unified || (!ui.unreadView && ui.mailboxes.any { it.role == "inbox" && it.id == ui.selectedMailboxId }),
                    onClick = {
                        if (accounts.size > 1) viewModel.selectUnified() else ui.mailboxes.firstOrNull { it.role == "inbox" }?.let { viewModel.select(it) }
                        scope.launch { drawerState.close() }
                    },
                    modifier = drawerRowModifier,
                )
                // Starred. LAST of the views and immediately above the folder list, so it sits
                // directly against Inbox — where Gmail puts it — without being one of the folder
                // rows. It is NOT one of them, and must not become one:
                //
                //   ⛔ THERE IS NO "STARRED" MAILBOX AND THIS APP MUST NEVER MAKE ONE. `$flagged`
                //   is a KEYWORD on the message (RFC 8621 §4.1.1), `\Flagged` a message flag
                //   (RFC 3501 §2.3.2) — an attribute, not a container. A real Mailbox named
                //   "Starred" would appear in every other client he uses, and anything that then
                //   MOVED mail into it would take that mail out of the folder it belongs in. That
                //   is the #67 shape of bug (a MOVE that clobbered `mailboxIds`) and it is data
                //   loss from the reader's side. This row creates nothing and moves nothing.
                //
                // Hence no [folderRank] either: ranking it would mean synthesising a fake Mailbox
                // to rank, which is the very thing above. It is a DESTINATION like Home — it opens
                // another screen — so like Home it never draws selected and closes the drawer.
                //
                // NO BADGE, deliberately. Its content is resolved BY THE SERVER (`Email/query`
                // hasKeyword / `SEARCH FLAGGED`), while every number in this drawer comes from the
                // local Room mirror at zero network. A cached count under a server-resolved list
                // would be a number that disagrees with the list it labels, and the only way to
                // make them agree is a network call per drawer open — which #247 explicitly bought
                // its way out of. No count is honest; a cheap wrong one is not.
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { DrawerLabel(stringResource(R.string.folder_flagged)) },
                    selected = false,
                    onClick = {
                        onOpenStarred()
                        scope.launch { drawerState.close() }
                    },
                    modifier = drawerRowModifier,
                )
                // Assigned: the mails that have a task (deadline + target date). A destination, like Starred.
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.AssignmentTurnedIn, contentDescription = null) },
                    label = { DrawerLabel(stringResource(R.string.assigned_title)) },
                    selected = false,
                    onClick = {
                        onOpenAssigned()
                        scope.launch { drawerState.close() }
                    },
                    modifier = drawerRowModifier,
                )
                // All | Unread (#247). Drawer-local and not persisted on purpose: a filter the
                // reader cannot see from outside the sheet must not be able to greet them, weeks
                // later, as a folder list with folders missing from it.
                var folderTab by remember { mutableStateOf(FolderTab.ALL) }
                // The fleet's pill strip (libs:ui-nav-bars, #868). In a sheet: no live top inset.
                val folderTabs = listOf(
                    NavPage(FolderTab.ALL.name, stringResource(R.string.inbox_folders_tab_all), "inbox"),
                    NavPage(FolderTab.UNREAD.name, stringResource(R.string.inbox_folders_tab_unread), "mark_email_unread"),
                )
                NavBarTopSecondRow(
                    pages = folderTabs,
                    selectedId = folderTab.name,
                    onSelect = { folderTab = FolderTab.valueOf(it.id) },
                    modifier = Modifier.padding(horizontal = MailMetrics.s12),
                    underTopChrome = false,
                    // build.json declares the Mail section's background "theme": the drawer is light in light mode.
                    surface = mailNav.stripSurface("mail", LocalSternaDarkTheme.current),
                )
                // ONLY the drawn list narrows with the tab. The fold registry below and the badge
                // arithmetic further down still resolve against the WHOLE account: a folded parent
                // badges descendants the Unread tab is hiding, and it must still count them.
                val drawnFolders = foldersForTab(ui.visibleMailboxes, folderTab)
                // The name the sort orders by is the name the row prints, resolved through the
                // CONTEXT rather than `mailboxDisplayName`: identical text, minus the @Composable.
                val folderDisplayName: (Mailbox) -> String = { mailboxLabel(context, it.role, it.name) }
                // The one place the registry AND the default become "what is folded"; see
                // [collapsedFolderIds]. The list it resolves against is the one drawn just below.
                val collapsedIds = collapsedFolderIds(ui.visibleMailboxes, collapsedFolders, folderRowsBadgeUnread)
                mailboxTree(drawnFolders, collapsedIds, folderDisplayName).forEach { node ->
                    val mailbox = node.mailbox
                    val displayName = mailboxDisplayName(mailbox.role, mailbox.name)
                    // A FOLDED row badges the unread it hides — its own plus its descendants' — or
                    // the mail under a folder folded by default is on no row at all; see
                    // [drawerUnreadCount].
                    val unread = drawerUnreadCount(mailbox, ui.visibleMailboxes, collapsedIds)
                    val label = if (unread > 0) {
                        stringResource(R.string.inbox_folder_unread, displayName, unread)
                    } else {
                        displayName
                    }
                    val collapsed = mailbox.id in collapsedIds
                    NavigationDrawerItem(
                        icon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Indent children; a chevron toggles collapse for parents.
                                Spacer(Modifier.width((node.depth * 16).dp))
                                if (node.hasChildren) {
                                    Icon(
                                        if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                        contentDescription = stringResource(
                                            if (collapsed) R.string.inbox_folder_expand else R.string.inbox_folder_collapse,
                                        ),
                                        modifier = Modifier.clickable {
                                            viewModel.setFolderCollapsed(mailbox.id, !collapsed)
                                        },
                                    )
                                } else {
                                    Spacer(Modifier.width(MailMetrics.s24))
                                }
                                Icon(folderIcon(mailbox.role), contentDescription = null)
                            }
                        },
                        label = { DrawerLabel(label) },
                        // EVERY folder carries the options menu, because "Mark all as read" applies
                        // to every folder — and the Inbox, which had no menu at all, is the one with
                        // thousands of unread in it. The ITEMS stay gated exactly as they were: the
                        // inbox is always watched, and notifying about one's own sent/drafts/trash/
                        // junk would be noise (#16), so Watch is still hidden for those five roles;
                        // management actions are still limited to user-created folders (no role).
                        // What changed is the menu's existence, not any item's audience.
                        badge = {
                            Box {
                                var folderMenu by remember { mutableStateOf(false) }
                                // Deliberately NOT an IconButton. That applies
                                // `minimumInteractiveComponentSize` and so reserved 48dp to draw
                                // a 24dp glyph, IGNORING the row's own cap — which is why the
                                // sidebar could not get under 48dp however far the cap came
                                // down, and why it was reported as "still too much line
                                // spacing" twice after being fixed. The tap lives on a
                                // DRAWER_FOLDER_MENU_TAP_SIZE_DP box instead, stated rather
                                // than inherited, and the glyph and its contentDescription are
                                // untouched. Same structural fix as the tag strip's (a01045f34).
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.inbox_folder_options),
                                    modifier = Modifier
                                        .size(DRAWER_FOLDER_MENU_TAP_SIZE_DP.dp)
                                        .clip(CircleShape)
                                        .clickable { folderMenu = true }
                                        .padding(MailMetrics.s4),
                                )
                                DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }, shape = MaterialTheme.shapes.medium) {
                                    // The Inbox shows it too, ticked by default; the others are noise on one's own
                                    // sent/drafts/trash/junk.
                                    if (mailbox.role !in watchMenuHiddenRoles) {
                                        val watched = mailbox.id in watchedFolders
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_folder_watch)) },
                                            trailingIcon = { Checkbox(checked = watched, onCheckedChange = null) },
                                            onClick = { folderMenu = false; viewModel.setFolderWatched(mailbox.id, !watched) },
                                        )
                                    }
                                    // Mark all as read. Every folder, no confirmation — the same as
                                    // the toolbar's overflow entry this shares its code with, and
                                    // the same as every other bulk action in this app (none of them
                                    // confirms). A dialog here would be a style this app does not
                                    // have, on the one action that already existed unconfirmed.
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_mark_all_read)) },
                                        onClick = { folderMenu = false; viewModel.markFolderRead(mailbox.id) },
                                    )
                                    if (mailbox.role == null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_new_subfolder)) },
                                            onClick = { folderMenu = false; onAddSubfolder(mailbox) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_rename)) },
                                            onClick = { folderMenu = false; onRenameFolder(mailbox) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_delete)) },
                                            onClick = { folderMenu = false; onDeleteFolder(mailbox) },
                                        )
                                    }
                                }
                            }
                        },
                        selected = mailbox.id == ui.selectedMailboxId,
                        onClick = {
                            viewModel.select(mailbox)
                            scope.launch { drawerState.close() }
                        },
                        modifier = drawerRowModifier,
                    )
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    label = { DrawerLabel(stringResource(R.string.inbox_new_folder)) },
                    selected = false,
                    onClick = onCreateFolder,
                    modifier = drawerRowModifier,
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { DrawerLabel(stringResource(R.string.inbox_settings)) },
                    selected = false,
                    onClick = {
                        onOpenSettings()
                        scope.launch { drawerState.close() }
                    },
                    modifier = drawerRowModifier,
                )
              }
}

/**
 * The centred ring's own grace, from the SAME functions that hold the tern back (#178) — never a
 */
@Composable
private fun rememberGracedIndicator(active: Boolean): Boolean {
    val run = remember { mutableStateOf<RefreshRun?>(null) }
    val showing = remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        // The same DURATION clock the ViewModel's tern runs on, never a wall clock: the wake-up
        // below is a monotonic `delay`, and an NTP correction between the two would skip the grace.
        val now = SystemClock.elapsedRealtime()
        run.value = if (active) {
            startRefreshRun(run.value, now, instant = false)
        } else {
            run.value?.copy(endedAt = now)
        }
        val stretch = run.value
        while (true) {
            val instant = SystemClock.elapsedRealtime()
            showing.value = refreshIndicatorShowing(stretch, instant)
            val next = nextRefreshIndicatorChange(stretch, instant) ?: break
            delay(next - instant)
        }
    }
    return showing.value
}

/**
 * Hosts an empty/error state inside a full-screen scrollable so pull-to-refresh still fires:
 */
@Composable
private fun PullableCenter(onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier.fillParentMaxSize().then(
                    if (onClick != null) {
                        // No ripple — a full-screen flash on an empty list reads as a glitch.
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

/** The account drawer header's monogram + name, reused for the current and peeking accounts. */
@Composable
private fun AccountChip(label: String, color: Color?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s12),
        modifier = modifier.fillMaxWidth().padding(vertical = MailMetrics.s8),
    ) {
        Monogram(seed = label, label = label, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEmailRow(
    email: Email,
    /** Where this row comes from, for [EmailListItem]'s single chip: the owning ACCOUNT in the
     *  unified inbox, the row's own FOLDER in the unread view ([unreadRowFolder]). The two never
     *  coexist — one slot, one question. Null draws no chip. */
    originLabel: String?,
    originColor: Color?,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    unarchiveContext: Boolean,
    trashContext: Boolean,
    showRecipients: Boolean,
    /** No default: both call sites hold the folder-role map and must answer with it, or a row
     *  loses its "(Draft)" after process death and never has one on IMAP. */
    showDraftBadge: Boolean,
    /** Whether the row is a draft this phone holds and the server has not got (#95). Defaults to
     *  false, like [EmailListItem]'s own parameter: no other list has such rows in it. */
    showNotUploadedBadge: Boolean = false,
    /** Tapping one of the row's attachment chips. Threaded rather than read from a CompositionLocal
     *  because it is an ACTION, not a presentation preference: a list that cannot download one must
     *  be able to say so by passing null, and the row then draws no chip to tap. */
    onOpenAttachment: ((EmailBodyPart) -> Unit)? = null,
    /** The attachment being downloaded right now, whichever row it belongs to, as [attachmentKey]
     *  spells it. Only the chip whose key matches spins. */
    openingAttachmentKey: String? = null,
    /** How this row reaches its own MESSAGE for the row's Resume / Copy Code (#514). Threaded, not
     *  defaulted at the call sites: a row carries no body, and a list that cannot fetch one must
     *  say so by passing null rather than letting the actions answer about the preview. */
    onLoadMessage: (suspend (Email) -> Email)? = null,
    onSwipe: (SwipeAction) -> Unit,
    onClick: () -> Unit,
    // Nullable so inline conversation children can omit long-press selection and the star.
    onLongClick: (() -> Unit)? = null,
    onToggleFavourite: (() -> Unit)? = null,
    selected: Boolean,
    /** The message open in the reading pane beside the list (#103). */
    current: Boolean = false,
    gesturesEnabled: Boolean,
    unread: Boolean,
    threadCount: Int,
    threadExpandable: Boolean = threadCount > 1,
    onToggleExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    animateEntry: Boolean = false,
    entryIndex: Int = 0,
    highlighted: Boolean = false,
    onHighlightShown: () -> Unit = {},
    /** True when the view model reported THIS row's swipe as failed: the take-off must be undone.
     *  No default — both call sites can fly a row off, and a forgotten one is a row that never
     *  comes back. Read by the rewind effect only: it is not, and must not become, a second way
     *  to refuse the gesture (that one lives on [gesturesEnabled], #95). */
    rewindSwipe: Boolean,
    /** Called once the row is back at rest, so its key stops being reported. */
    onSwipeRewound: () -> Unit,
    /** Width of the start-edge strip left to the drawer's own drag; 0 where the system
     *  owns that edge (gesture navigation). See [DrawerGesture]. */
    drawerBandPx: Float = 0f,
    /** Is there a drawer to hand a drag to at all? False from 1 200 dp. Passed as a VALUE and keyed
     *  on below, never read out of a captured State: the drag block runs outside composition and
     * would keep the first one it saw. No default: a third call site must ANSWER this question,
     *  and a default would let it forget silently ([DrawerGesture]). */
    drawerCanOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionOn = rememberMotionEnabled()
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    // Where this row starts in the window, so the edge strip is measured from the screen
    // edge and not from the row's own left — conversation children are indented.
    var rowLeftPx by remember { mutableFloatStateOf(0f) }
    // The speed of the swipe in progress, published from the drag block so COMPOSITION can see it
    // (#125): the reveal below arms "the moment releasing would trigger the action", and a flick
    // commits too. Zero means the finger has left — never read a finished gesture's velocity.
    var swipeVelocityX by remember { mutableFloatStateOf(0f) }
    var swipeVelocityY by remember { mutableFloatStateOf(0f) }

    // The drag handler below lives in a pointerInput block that only restarts when the configured
    // actions change — it must NOT capture onSwipe directly. The paged row re-binds with fresh state
    // after each action, and a stale capture kept dispatching the previous state's action.
    val currentOnSwipe by rememberUpdatedState(onSwipe)

    // Staggered first-screen entry (fade + slight rise), cascaded by row index. Plays at most once
    // per row; rows recycled in during scroll arrive with animateEntry false and snap to rest.
    val enter = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateEntry) {
            delay(entryIndex * ENTRY_STEP_MS)
            enter.animateTo(1f, tween(ENTRY_ROW_MS, easing = FastOutSlowInEasing))
        }
    }

    // Swipe "takes flight": on a dismissing swipe the row lifts off in a short arc — rising,
    // tilting, fading — then is actually removed. Static slide-off under reduced motion.
    val lift = remember { Animatable(0f) }
    var flyDir by remember { mutableIntStateOf(0) }

    // …and the take-off wound back, when the write it was played for failed. commitSwipe runs the
    // WHOLE arc before calling onSwipe, and nothing else resets these three: the list key is stable,
    // so the reconcile hands this composition back, still parked. snapTo, not animateTo.
    LaunchedEffect(rewindSwipe) {
        if (rewindSwipe) {
            offsetX.snapTo(0f)
            lift.snapTo(0f)
            flyDir = 0
            onSwipeRewound()
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * MailMetrics.s14.toPx()
            }
            .onGloballyPositioned { rowLeftPx = it.positionInWindow().x }
            .onSizeChanged { rowWidth = it.width }
            .pointerInput(gesturesEnabled, rightAction, leftAction, drawerBandPx, drawerCanOpen) {
                if (!gesturesEnabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val minOffset = if (leftAction == SwipeAction.NONE) 0f else -rowWidth.toFloat()
                val maxOffset = if (rightAction == SwipeAction.NONE) 0f else rowWidth.toFloat()
                // Codeberg #125: a flick commits without travelling the distance, so its speed has
                // to be measured. addPointerInputChange, never addPosition by hand — it unfolds the
                // sub-frame samples and accumulates DELTAS, so it is blind to the row moving.
                val tracker = VelocityTracker()
                // "This gesture is over, and nothing may be read from it." The same while(true)
                // scope serves every gesture the row sees: without this, one swipe's samples leak
                // into the next and the published velocity arms the reveal while the row springs back.
                fun forgetVelocity() {
                    tracker.resetTracking()
                    swipeVelocityX = 0f
                    swipeVelocityY = 0f
                }
                coroutineScope {
                    while (true) {
                        val down = awaitPointerEventScope {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        val pointerId = down.id
                        forgetVelocity()
                        tracker.addPointerInputChange(down)
                        // Codeberg #30: a drag STARTING in the start-edge strip belongs to
                        // the drawer, so nothing here consumes it (the strip is 0-wide, and
                        // this never triggers, wherever the system owns that edge).
                        if (startsInDrawerBand(rowLeftPx + down.position.x, drawerBandPx)) {
                            forgetVelocity()
                            continue
                        }
                        // Direction-lock: treat this as a swipe only once it is clearly more
                        // horizontal than vertical. The three-way decision is swipeDirectionLock()'s;
                        // see there for the arc-rejection rationale (#97).
                        val horizontal = awaitPointerEventScope {
                            var dx = 0f
                            var dy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) return@awaitPointerEventScope false
                                // Fed HERE as well as in horizontalDrag: the travel of this phase is
                                // thrown away, so a short sharp flick is almost entirely consumed by
                                // it and a later tracker would see only the deceleration.
                                tracker.addPointerInputChange(change)
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                when (swipeDirectionLock(dx, dy, slop)) {
                                    SwipeLock.VERTICAL -> return@awaitPointerEventScope false
                                    SwipeLock.HORIZONTAL -> {
                                        // Codeberg #30: a direction with no action assigned has
                                        // nothing to do with this drag — leave it unconsumed so
                                        // the drawer, whose own drag spans the content, takes it.
                                        if (!rowKeepsDrag(dx, rightAction, leftAction, drawerCanOpen)) {
                                            return@awaitPointerEventScope false
                                        }
                                        change.consume()
                                        return@awaitPointerEventScope true
                                    }
                                    SwipeLock.PENDING -> Unit // still ambiguous — keep watching
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (!horizontal) {
                            forgetVelocity()
                            continue
                        }

                        offsetX.stop()
                        // horizontalDrag answers false when the drag was CANCELLED — another node
                        // consumed the pointer — rather than ended by the finger lifting. With a
                        // flick in the rule that is a way in, carrying a snatched-away speed.
                        val dragCompleted = awaitPointerEventScope {
                            horizontalDrag(pointerId) { change ->
                                tracker.addPointerInputChange(change)
                                val moving = tracker.calculateVelocity()
                                swipeVelocityX = moving.x
                                swipeVelocityY = moving.y
                                val target = (offsetX.value + change.positionChange().x)
                                    .coerceIn(minOffset, maxOffset)
                                launch { offsetX.snapTo(target) }
                                change.consume()
                            }
                        }
                        val release = tracker.calculateVelocity()
                        // Read the release speed, THEN forget it, before the row is animated: the
                        // reveal reads the published velocity in composition, and a stale one would
                        // light it during the spring home, for an action that will not happen.
                        forgetVelocity()

                        // What commits, and what arms the reveal below, are the SAME call: two
                        // hand-written copies had already drifted apart (SwipeCommit.kt). The
                        // threshold is computed HERE — a captured value would freeze at first run.
                        val commitThresholdPx = swipeCommitThresholdPx(
                            rowWidthPx = rowWidth.toFloat(),
                            distancePx = SWIPE_COMMIT_DISTANCE_DP.dp.toPx(),
                        )
                        val committed = swipeCommitDirection(
                            offsetPx = offsetX.value,
                            thresholdPx = commitThresholdPx,
                            velocityPxPerSec = release.x,
                            crossVelocityPxPerSec = release.y,
                            escapeVelocityPxPerSec = SWIPE_ESCAPE_VELOCITY_DP_PER_SEC.dp.toPx(),
                            slopPx = slop,
                            dragCompleted = dragCompleted,
                        )
                        val action = when (committed) {
                            1 -> rightAction
                            -1 -> leftAction
                            else -> SwipeAction.NONE
                        }
                        if (action == SwipeAction.NONE) {
                            launch { offsetX.animateTo(0f) }
                        } else {
                            // width here is the FLIGHT distance, not a threshold: how far
                            // the row travels as it clears the screen.
                            val width = rowWidth.toFloat().coerceAtLeast(1f)
                            commitSwipe(action, committed, width, motionOn, offsetX, lift, { currentOnSwipe(it) }) { flyDir = it }
                        }
                    }
                }
            },
    ) {
        val draggingRight = offsetX.value > 0f
        val action = if (draggingRight) rightAction else leftAction
        if (offsetX.value != 0f && action != SwipeAction.NONE) {
            val destructive = action == SwipeAction.DELETE
            // Flag reveal uses the coral tertiary to echo the favourite star; other non-destructive
            // actions keep the calmer secondary. The label text carries the meaning either way.
            val isFlag = action == SwipeAction.FLAG
            val color = when {
                destructive -> MaterialTheme.colorScheme.errorContainer
                isFlag -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            val onColor = when {
                destructive -> MaterialTheme.colorScheme.onErrorContainer
                isFlag -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            // Commit-threshold feedback: neutral while the swipe is short of committing, then the
            // action colour the moment releasing would trigger it — the same call the drag handler
            // makes on lift, hence the LIVE velocity here too. It deliberately does NOT tick:
            // arming follows the finger's speed, and long-press is this list's only tick.
            val density = LocalDensity.current
            val viewConfiguration = LocalViewConfiguration.current
            val commitThresholdPx = remember(rowWidth, density) {
                swipeCommitThresholdPx(
                    rowWidthPx = rowWidth.toFloat(),
                    distancePx = with(density) { SWIPE_COMMIT_DISTANCE_DP.dp.toPx() },
                )
            }
            val armed = swipeCommitDirection(
                offsetPx = offsetX.value,
                thresholdPx = commitThresholdPx,
                velocityPxPerSec = swipeVelocityX,
                crossVelocityPxPerSec = swipeVelocityY,
                escapeVelocityPxPerSec = with(density) { SWIPE_ESCAPE_VELOCITY_DP_PER_SEC.dp.toPx() },
                slopPx = viewConfiguration.touchSlop,
                dragCompleted = true,
            ) != 0
            val bg by animateColorAsState(
                targetValue = if (armed) color else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = if (motionOn) tween(120) else snap(),
                label = "swipeRevealBg",
            )
            val fg by animateColorAsState(
                targetValue = if (armed) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = if (motionOn) tween(120) else snap(),
                label = "swipeRevealFg",
            )
            val labelScale by animateFloatAsState(
                targetValue = if (armed) 1.12f else 1f,
                animationSpec = if (motionOn) spring() else snap(),
                label = "swipeRevealScale",
            )
            Box(
                Modifier.matchParentSize().background(bg).padding(horizontal = MailMetrics.s24),
                contentAlignment = if (draggingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                val labelRes = swipeActionLabel(action, email, unarchiveContext, trashContext)
                if (labelRes != 0) {
                    Text(
                        stringResource(labelRes),
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.graphicsLayer {
                            scaleX = labelScale
                            scaleY = labelScale
                        },
                    )
                }
            }
        }
        Box(
            // graphicsLayer (draw phase) instead of offset (layout phase): the swipe translation is
            // GPU-cheap and the row is cached as a layer. offsetX is read here, not in composition.
            // On a dismissing swipe `lift` adds the take-off arc: rise, tilt, and fade.
            modifier = Modifier.graphicsLayer {
                translationX = offsetX.value
                if (lift.value > 0f) {
                    val p = lift.value
                    translationY = -size.height * 0.6f * p
                    rotationZ = flyDir * 10f * p
                    alpha = 1f - p
                    val sc = 1f - 0.06f * p
                    scaleX = sc
                    scaleY = sc
                }
            },
        ) {
            EmailListItem(
                email = email,
                onClick = onClick,
                originLabel = originLabel,
                originColor = originColor,
                onToggleFavourite = onToggleFavourite,
                selected = selected,
                current = current,
                onLongClick = onLongClick,
                unread = unread,
                threadCount = threadCount,
                threadExpandable = threadExpandable,
                onToggleExpand = onToggleExpand,
                expanded = expanded,
                highlighted = highlighted,
                onHighlightShown = onHighlightShown,
                showRecipients = showRecipients,
                showDraftBadge = showDraftBadge,
                showNotUploadedBadge = showNotUploadedBadge,
                onOpenAttachment = onOpenAttachment,
                openingAttachmentKey = openingAttachmentKey,
                onLoadMessage = onLoadMessage,
            )
        }
    }
}

/** Horizontal travel must exceed touch-slop × this before a swipe locks in. */
private const val SWIPE_SLOP_FACTOR = 1.5f

/**
 * How decisively horizontal a drag must be to arm a swipe: |dx| must reach this multiple of |dy|.
 */
private const val SWIPE_HORIZONTAL_DOMINANCE = 2.0f

/** Outcome of the row's direction-lock for the accumulated drag (dx, dy). */
internal enum class SwipeLock { PENDING, HORIZONTAL, VERTICAL }

/**
 * Three-way direction lock on each accumulated drag delta: VERTICAL abandons to the list's scroll
 */
internal fun swipeDirectionLock(dx: Float, dy: Float, slop: Float): SwipeLock {
    val ax = abs(dx)
    val ay = abs(dy)
    if (ay > slop && ax < ay * SWIPE_HORIZONTAL_DOMINANCE) return SwipeLock.VERTICAL
    if (ax > slop * SWIPE_SLOP_FACTOR && ax >= ay * SWIPE_HORIZONTAL_DOMINANCE) return SwipeLock.HORIZONTAL
    return SwipeLock.PENDING
}

// Staggered first-screen entry: only the first ENTRY_CAP rows cascade, ENTRY_STEP_MS apart, each
// fading over ENTRY_ROW_MS. The take-off arc on a dismissing swipe runs over FLIGHT_MS.
private const val ENTRY_CAP = 12
private const val ENTRY_STEP_MS = 28L
private const val ENTRY_ROW_MS = 220
private const val FLIGHT_MS = 300

/** Whether a swipe action removes the row from the list (vs. snapping back). */
private fun dismissesRow(action: SwipeAction): Boolean =
    action == SwipeAction.DELETE || action == SwipeAction.ARCHIVE

/**
 * Whether this row must undo its swipe animation: it is one of the rows the view model reported as
 */
internal fun needsSwipeRewind(keys: Set<EmailKey>, accountId: String?, emailId: String): Boolean =
    EmailKey(accountId, emailId) in keys

/**
 * Commit a swipe: a dismissing action with motion enabled lifts the row off in a short arc ([lift]
 */
private fun CoroutineScope.commitSwipe(
    action: SwipeAction,
    dir: Int,
    width: Float,
    motionOn: Boolean,
    offsetX: Animatable<Float, *>,
    lift: Animatable<Float, *>,
    onSwipe: (SwipeAction) -> Unit,
    setFlyDir: (Int) -> Unit,
) {
    if (dismissesRow(action) && motionOn) {
        setFlyDir(dir)
        launch {
            launch { offsetX.animateTo(dir * width, tween(FLIGHT_MS, easing = FastOutSlowInEasing)) }
            lift.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
            onSwipe(action)
        }
    } else {
        onSwipe(action)
        launch { offsetX.animateTo(if (dismissesRow(action)) dir * width else 0f) }
    }
}

/**
 * True when [role] is one where an "archive" swipe should instead unarchive: the Archive folder,
 * or a Gmail-style "All Mail" (role "all").
 */
private fun isUnarchiveContext(role: String?): Boolean = role == "archive" || role == "all"

/** True when [role] is the Trash, where a delete destroys instead of moving there — so the
 *  affordance should read "delete permanently", not the Trash-can (Codeberg #23). */
private fun isTrashContext(role: String?): Boolean = role == "trash"

/** The role of the folder on screen, or null in the unified view (which selects none) and for a
 *  folder whose role the server never gave. */
private fun visibleFolderRole(ui: MailUi): String? =
    ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role

/**
 * The role of the folder THE ROW is filed in — what "delete permanently", "unarchive" and "a tap
 */
internal fun rowFolderRole(
    email: Email,
    ui: MailUi,
    roles: Map<Pair<String, String>, String>,
    folderTrusted: Boolean,
): String? {
    if (!folderTrusted) return visibleFolderRole(ui)
    return messageFolderRole(email.accountId, email.mailboxId, roles) ?: visibleFolderRole(ui)
}

/**
 * WHERE a row of the unread view comes from — the folder to name under it, or null to say nothing.
 */
internal fun unreadRowFolder(
    email: Email,
    ui: MailUi,
    folderTrusted: Boolean,
    inViewCount: Int,
): Mailbox? {
    if (!ui.unreadView || !folderTrusted) return null
    if (inViewCount != 1) return null
    val account = ui.accountId ?: return null
    if (email.accountId != account) return null
    return ui.mailboxes.firstOrNull { it.id == email.mailboxId }
}

/**
 * True when [from] is one of the user's own send-as [identities], matched case-insensitively on the
 */
internal fun isSelfAuthored(from: List<EmailAddress>, identities: List<StoredIdentity>): Boolean {
    val sender = from.firstOrNull()?.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    return identities.any { it.email.trim().lowercase() == sender }
}

/**
 * The addresses the row's own account can send as. Mirrors AccountStore.identities: a linked
 */
private fun sendAsIdentities(email: Email, accounts: List<StoredAccount>): List<StoredIdentity> {
    val own = accounts.firstOrNull { it.id == email.accountId } ?: return emptyList()
    return own.resolvedIdentities().ifEmpty {
        own.loginId?.let { login -> accounts.firstOrNull { it.id == login }?.resolvedIdentities() }.orEmpty()
    }
}

/** True when [role] is Drafts, where tapping a row edits it in compose (#63). */
private fun isDraftsContext(role: String?): Boolean = role == "drafts"

/** What a tap on a list row does — the decision, apart from the composable that acts on it. */
internal enum class RowTap { EDIT_DRAFT, SELECT_THREAD, SELECT_ROW, OPEN }

/**
 * What a tap on the browse/search row does. Order is load-bearing: a local draft ([localDraft], #95)
 */
internal fun rowTapAction(
    localDraft: Boolean,
    selectionActive: Boolean,
    expandable: Boolean,
    fromSearch: Boolean,
    rowRole: String?,
): RowTap = when {
    localDraft -> RowTap.EDIT_DRAFT
    selectionActive -> if (expandable) RowTap.SELECT_THREAD else RowTap.SELECT_ROW
    !fromSearch && isDraftsContext(rowRole) -> RowTap.EDIT_DRAFT
    else -> RowTap.OPEN
}

/**
 * What a tap on a child of an unfolded conversation does, from the role of ITS OWN folder — the one
 */
internal fun childTapAction(childRole: String?): RowTap = rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = childRole)

/** The string resource shown on the swipe background for [action] on [email] (0 = none). */
private fun swipeActionLabel(action: SwipeAction, email: Email, unarchiveContext: Boolean, trashContext: Boolean): Int = when (action) {
    SwipeAction.NONE -> 0
    SwipeAction.TOGGLE_READ -> if (email.isSeen) R.string.inbox_mark_unread else R.string.inbox_mark_read
    // In Trash a delete destroys, so the swipe reads "Delete permanently" (Codeberg #23).
    SwipeAction.DELETE -> if (trashContext) R.string.inbox_delete_forever else R.string.inbox_delete
    SwipeAction.ARCHIVE -> if (unarchiveContext) R.string.inbox_unarchive else R.string.inbox_archive
    SwipeAction.FLAG -> if (email.isFlagged) R.string.inbox_unflag else R.string.inbox_flag
}

/** Dispatch a configured swipe action to the view model for [email]. */
private fun performSwipe(action: SwipeAction, email: Email, viewModel: InboxViewModel, ui: MailUi, rowRole: String?) {
    // A draft only this phone holds is inert (#95): repo.delete / archive / moveToMailbox read a
    // UID out of an id that carries none, and none of them removes the local row. Left through, a
    // "delete" swipe would announce a deletion that did not happen ([isLocalDraftRow]).
    if (isLocalDraftRow(email.id)) return
    when (action) {
        SwipeAction.NONE -> Unit
        SwipeAction.TOGGLE_READ -> viewModel.toggleRead(email)
        SwipeAction.DELETE -> viewModel.delete(email)
        // Inside the Archive folder — or Gmail-style "All Mail" — an "archive" swipe means unarchive
        // → move back to Inbox. NB: a still-in-Inbox message viewed from All Mail cannot be told
        // apart from an archived one without per-message membership, so it unarchives too.
        SwipeAction.ARCHIVE -> {
            if (isUnarchiveContext(rowRole)) {
                ui.mailboxes.firstOrNull { it.role == "inbox" }?.id?.let { viewModel.unarchive(email, it) }
            } else {
                viewModel.archive(email)
            }
        }
        SwipeAction.FLAG -> viewModel.toggleFlag(email)
    }
}

/** Dispatch a configured swipe action to the whole conversation behind a collapsed row. */
private fun performThreadSwipe(action: SwipeAction, rep: Email, viewModel: InboxViewModel, ui: MailUi, rowRole: String?) {
    when (action) {
        SwipeAction.NONE -> Unit
        SwipeAction.TOGGLE_READ -> viewModel.toggleReadThread(rep)
        SwipeAction.DELETE -> viewModel.deleteThread(rep)
        SwipeAction.ARCHIVE -> {
            if (isUnarchiveContext(rowRole)) {
                ui.mailboxes.firstOrNull { it.role == "inbox" }?.id?.let { viewModel.unarchiveThread(rep, it) }
            } else {
                viewModel.archiveThread(rep)
            }
        }
        SwipeAction.FLAG -> viewModel.toggleFlagThread(rep)
    }
}

/**
 * The inline-expanded members of a conversation, indented under the collapsed row behind a left
 */
@Composable
private fun ThreadChildren(
    visible: Boolean,
    members: List<Email>,
    unified: Boolean,
    accounts: List<app.sterna.core.data.account.StoredAccount>,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    /** Decided per child, like the two below: an unfolded conversation spans the viewed folder(s)
     *  PLUS Sent, and "Delete permanently" must be true of the row it is drawn on. */
    unarchiveContextFor: (Email) -> Boolean,
    trashContextFor: (Email) -> Boolean,
    /** Decided per child so a self reply in an incoming conversation shows "To: …" (Codeberg #69). */
    showRecipientsFor: (Email) -> Boolean,
    /** Decided per child too: the "(Draft)" chip reads the child's own folder, not the one on
     *  screen — an unfolded conversation spans the viewed folder(s) plus Sent. */
    showDraftBadgeFor: (Email) -> Boolean,
    /** Same shape again, for the origin chip: an unfolded child IS a row, and in the unread view it
     *  is filed in a folder of its own. Null (the common case) draws no chip ([unreadRowFolder]). */
    folderFor: (Email) -> Mailbox?,
    /** An unfolded child IS a row, so it offers its own attachments. Passed down rather than left
     *  off: a file reachable from the collapsed conversation and unreachable once it is expanded
     *  would be the affordance disappearing exactly when the user went looking for it. */
    onOpenAttachment: (Email, EmailBodyPart) -> Unit,
    openingAttachmentKey: String?,
    /** How a child row reaches its own MESSAGE for Resume / Copy Code (#514) — the same loader the
     *  top-level rows get, for the same reason: a child is a row, and a row carries no body. */
    onLoadMessage: (suspend (Email) -> Email)?,
    highlightId: String?,
    /** The reading pane's anchor: the child it names is painted current (#103). */
    paneAnchor: MessageAnchor?,
    selectionActive: Boolean,
    selectedKeys: Set<EmailKey>,
    onOpenChild: (Email) -> Unit,
    onSwipeChild: (SwipeAction, Email) -> Unit,
    onToggleChildFavourite: (Email) -> Unit,
    onEnterSelectionChild: (Email) -> Unit,
    onToggleSelectChild: (Email) -> Unit,
    onHighlightShown: () -> Unit,
    /** The rows whose swipe was played over a write that failed, as account-qualified keys: a
     *  child is matched on its OWN (account, id), because two accounts of one server share ids
     *  (#92) and an unfolded conversation can span both. */
    swipeRewindKeys: Set<EmailKey>,
    onSwipeRewound: (Email) -> Unit,
    /** The drawer's start-edge strip, applied to the children too — measured from the window edge,
     *  so the indent doesn't shift it ([DrawerGesture]). */
    drawerBandPx: Float,
    /** And whether there is a drawer to open at all, for the same reason: a child is a row. */
    drawerCanOpen: Boolean,
) {
    val motionOn = rememberMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motionOn) expandVertically() + fadeIn() else EnterTransition.None,
        exit = if (motionOn) shrinkVertically() + fadeOut() else ExitTransition.None,
    ) {
        // Each unfolded child is its own card, so the column behind them wears the pane colour and
        // there is no hairline between children — the gutters ARE the separation (#472).
        Column(Modifier.background(LocalMailListPalette.current.pane)) {
            members.forEach { child ->
                key(child.accountId, child.id) {
                    val ownerAccount = if (unified) accounts.firstOrNull { it.id == child.accountId } else null
                    // Indented so the children read as belonging to the conversation above.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(MailMetrics.s16))
                        SwipeableEmailRow(
                            email = child,
                            originLabel = ownerAccount?.label() ?: folderFor(child)?.let { mailboxDisplayName(it.role, it.name) },
                            originColor = accountColorOf(ownerAccount?.color),
                            rightAction = rightAction,
                            leftAction = leftAction,
                            unarchiveContext = unarchiveContextFor(child),
                            trashContext = trashContextFor(child),
                            showRecipients = showRecipientsFor(child),
                            showDraftBadge = showDraftBadgeFor(child),
                            onOpenAttachment = { part -> onOpenAttachment(child, part) },
                            openingAttachmentKey = openingAttachmentKey,
                            onLoadMessage = onLoadMessage,
                            onSwipe = { action -> onSwipeChild(action, child) },
                            // Children join multi-select like top-level rows.
                            onClick = { if (selectionActive) onToggleSelectChild(child) else onOpenChild(child) },
                            onLongClick = { onEnterSelectionChild(child) },
                            // Children carry the same star and attachment indicator as top-level
                            // rows, so the unfolded preview matches the collapsed one.
                            onToggleFavourite = { onToggleChildFavourite(child) },
                            selected = child.emailKey() in selectedKeys,
                            current = paneAnchor?.matches(child.accountId, child.id) == true,
                            gesturesEnabled = !selectionActive,
                            unread = !child.isSeen,
                            threadCount = 1,
                            highlighted = child.id == highlightId,
                            onHighlightShown = onHighlightShown,
                            rewindSwipe = needsSwipeRewind(swipeRewindKeys, child.accountId, child.id),
                            onSwipeRewound = { onSwipeRewound(child) },
                            drawerBandPx = drawerBandPx,
                            drawerCanOpen = drawerCanOpen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One folder in the drawer tree: the mailbox, its [depth], and whether it has children. */
internal data class MailboxNode(val mailbox: Mailbox, val depth: Int, val hasChildren: Boolean)

/**
 * Flatten mailboxes into a depth-first tree. Nesting comes from the JMAP `parentId` or, for IMAP,
 */
internal fun mailboxTree(
    mailboxes: List<Mailbox>,
    collapsed: Set<String>,
    displayName: (Mailbox) -> String = Mailbox::name,
): List<MailboxNode> {
    val byId = mailboxes.associateBy { it.id }
    fun parentOf(m: Mailbox): String? = folderParentId(m, byId)
    val childrenOf = mailboxes.groupBy { parentOf(it) }
        .mapValues { (_, kids) -> kids.sortedWith(drawerFolderOrder(displayName)) }
    val result = mutableListOf<MailboxNode>()
    val visited = mutableSetOf<String>()
    fun visit(parent: String?, depth: Int) {
        childrenOf[parent].orEmpty().forEach { m ->
            if (!visited.add(m.id)) return@forEach // guard against pathological cycles
            result += MailboxNode(m, depth, !childrenOf[m.id].isNullOrEmpty())
            if (m.id !in collapsed) visit(m.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

/**
 * How one level of the drawer is ordered: standard folders by role first, then EVERYTHING ELSE BY
 * NAME. The second half of that is the fix for #247 — before it, every folder the user made tied at
 * [folderRank] 6 and `sortedBy` is stable, so the drawer showed them in whatever order the server
 * happened to list them. The drawer had never sorted by name at all.
 *
 * The name compared is the DISPLAYED one, not `mailbox.name`. Nine roles are shown through a
 * localized string resource (`mailboxRoleNameRes`) while only six of them are ranked above — `all`,
 * `flagged` and `important` are translated AND rank 6, so they are sorted among the user's own
 * folders. Sorting those on the raw name would order a Spanish drawer by the English word nobody on
 * that phone can see.
 *
 * ⛔ CODE POINT ORDER, deliberately, and NOT the `Collator` that `FolderSelection.comparePath` uses
 * for the move-to-folder picker. This is a knowing divergence and it is the whole point of the
 * request. The owner names folders so that a sort produces group headers followed by their own
 * members — `AO SIZE` then `Aa Large`, `Ab Medium`, `Ac Small`; `BO TIME` then `Ba`, `Bc`, `Bd`.
 * That structure is carried in the CASE of the second character, and a collator compares base
 * letters at primary strength before it ever looks at case: `Collator.compare("AO SIZE", "Aa
 * Large")` weighs 'o' against 'a', answers "Aa" first, and files every group header AFTER the
 * members it introduces. No collator setting recovers this — case is a tertiary difference and
 * never reached when the base letters already differ, so `setUpperCaseFirst` would not help either.
 * The cost is real and accepted: an accented name sorts by code point here (é after z) where the
 * picker would place it next to e.
 */
internal fun drawerFolderOrder(displayName: (Mailbox) -> String): Comparator<Mailbox> =
    compareBy<Mailbox> { folderRank(it.role) }.thenBy { displayName(it) }

/** Drawer ordering rank for a folder's role: standard folders first, custom folders last. */
internal fun folderRank(role: String?): Int = when (role) {
    "inbox" -> 0
    "drafts" -> 1
    "sent" -> 2
    "trash" -> 3
    "junk" -> 4
    "archive" -> 5
    else -> 6
}

/** A leading icon for a folder, chosen by its JMAP role (falls back to a generic list icon). */
private fun folderIcon(role: String?): ImageVector = when (role) {
    "inbox" -> Icons.Filled.Email
    "drafts" -> Icons.Filled.Create
    "sent" -> Icons.AutoMirrored.Filled.Send
    "trash" -> Icons.Filled.Delete
    "junk" -> Icons.Filled.Report
    "archive" -> Icons.Filled.Archive
    else -> Icons.Filled.Folder
}

/**
 * One row's label in the folder sidebar, at [FOLDER_LABEL_TEXT_SIZE_SP] instead of Material's
 * labelLarge. Every row goes through here, not just the folders: a sidebar where the folders are
 * one size and "All inboxes" another reads as a rendering fault rather than as a smaller list.
 *
 * No `maxLines` and no `overflow` on purpose. The width and the point size are the two things
 * moved against wrapping (see PaneLayout); a name still too long for them wraps and stays
 * readable, which is the outcome an ellipsis would take away.
 *
 * The LINE HEIGHT is set as well as the font size, and it has to be: `copy(fontSize = …)` alone
 * left labelLarge's 20sp line box in place, so every row drew 12sp of glyph in a box sized for
 * 14sp. That stranded 4sp of leading on every line of every row and, because the row cap must hold
 * two boxes for the names that wrap, it set a 40dp floor under the whole sidebar.
 */
@Composable
private fun DrawerLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge.copy(
        fontSize = FOLDER_LABEL_TEXT_SIZE_SP.sp,
        lineHeight = FOLDER_LABEL_LINE_HEIGHT_SP.sp,
    ),
)

/**
 * The name to show for a folder. Standard folders — those the server tags with an RFC 8621 /
 */
@Composable
fun mailboxDisplayName(role: String?, name: String): String =
    mailboxRoleNameRes(role)?.let { stringResource(it) } ?: name

/** A discreet, non-tappable banner shown above the list while there's no usable network (#65).
 *  Calmer surface tone than [OutboxFailureBanner]: offline is a state, not a failure. */
@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null)
            Spacer(Modifier.width(MailMetrics.s12))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * A discreet banner for a refresh that FAILED while the device is online — the error surface, not
 */
@Composable
private fun SyncErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(MailMetrics.s12))
            Text(
                stringResource(R.string.sync_error_banner, message),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A discreet, tappable banner shown above the list when a send has permanently failed. */
@Composable
private fun OutboxFailureBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(MailMetrics.s12))
            Text(
                stringResource(R.string.outbox_banner_failed),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

    /** An announced attachment size, for the metered question. Its own copy rather than a shared one
     *  with the reader's [app.sterna.ui.message.formatSize]: that one returns "" for an unknown size,
     *  which is right in a list of files and wrong in a sentence asking permission to spend data. A
     *  size of 0 never reaches here -- [DownloadLimits.needsMeteredConfirmation] does not ask about a
     *  message that announced nothing. */
internal fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

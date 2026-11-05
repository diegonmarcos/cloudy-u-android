package app.sterna.ui

import android.app.Application
import android.net.Uri
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.EmailOpenTarget
import app.sterna.MailtoDraft
import app.sterna.container
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushController
import app.sterna.security.LockScreen
import app.sterna.ui.assigned.AssignedScreen
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.compose.ComposeScreen
import app.sterna.ui.compose.IncognitoKeyboard
import app.sterna.ui.connect.ConnectScreen
import app.sterna.core.jmap.model.Email
import app.sterna.ui.home.HomeScreen
import app.sterna.ui.home.openAgents
import app.sterna.ui.home.openHermesAi
import app.sterna.ui.home.openWorkflows
import app.sterna.ui.inbox.InboxScreen
import app.sterna.ui.inbox.InboxViewModel
import app.sterna.ui.inbox.MessageAnchor
import app.sterna.ui.inbox.ReadingPane
import app.sterna.ui.inbox.Sel
import app.sterna.ui.inbox.restoredSelection
import app.sterna.ui.inbox.ThreadKey
import app.sterna.ui.message.LocalNavTransitionActive
import app.sterna.ui.message.MessageScreen
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.outbox.OutboxScreen
import app.sterna.ui.scheduled.ScheduledSendsScreen
import app.sterna.ui.sender.MailBySenderScreen
import app.sterna.ui.snoozed.SnoozedScreen
import app.sterna.ui.search.SearchScreen
import app.sterna.ui.onboarding.WelcomeScreen
import app.sterna.ui.settings.SettingsScreen
import app.sterna.ui.rss.RssScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** TEST BUILD ONLY, set back to false before integrating: the welcome and the contacts priming then
 *  appear on every launch regardless of their real gating. */
const val FORCE_ONBOARDING_PREVIEW = false

/** Top-level route: no account, an account list that could not be read, or a signed-in account. */
sealed interface RootState {
    data object Loading : RootState
    data object NeedAccount : RootState

    /** The stored account list did not decode. Distinct from [NeedAccount]: the store refuses to
     *  write over a list it could not read, so "add an account" would save nothing. */
    data object AccountsUnreadable : RootState
    data class Authenticated(val accountId: String) : RootState
}

class RootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore
    private val settings = application.container.settingsRepository

    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state = _state.asStateFlow()

    // null while the flag loads, so neither the connect screen nor the welcome flashes.
    val hasSeenWelcome: StateFlow<Boolean?> =
        settings.hasSeenWelcome.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markWelcomeSeen() {
        viewModelScope.launch { settings.setHasSeenWelcome(true) }
    }

    init {
        refresh()
    }

    /** The accounts to list, live from the store rather than read once per composition: a shared
     *  mailbox appears as soon as discovery persists it, and a revoked one leaves (#31). */
    val accounts: StateFlow<List<StoredAccount>> = accountStore.accountsFlow

    fun refresh() {
        // Only an account with a stored credential counts as authenticated.
        val current = accountStore.currentId()?.takeIf { accountStore.credentials(it) != null }
            ?: accountStore.accounts().firstOrNull { accountStore.credentials(it.id) != null }?.id
        if (current != null && accountStore.currentId() != current) accountStore.setCurrent(current)
        // Seeded here, and BEFORE the arm: `unifiedInboxVisible` mirrors the list's selection and
        // on a cold start there is no list yet, so left at false a launch reopening on "All inboxes"
        // (#179) reseeds fewer accounts than it should. Before, not "inside this call": on the
        // direct branch the flag is read later, on the service's own coroutine.
        PushController.unifiedInboxVisible = restoredUnifiedView()
        PushController.apply(getApplication(), userInitiated = true)
        // `accountsUnreadable()` re-reads storage on the spot, so the alert never outlives it.
        _state.value = RootRoute.resolve(current, accountStore.accountsUnreadable())
    }

    /** Whether the view the app REOPENS on is the unified inbox — asked before the list is composed,
     * by the push arm and by the account switch a notification asks for. It is the memory's
     *  answer, not the screen's: a live list always overrules it. */
    fun restoredUnifiedView(): Boolean = restoredSelection(accountStore) is Sel.Unified

    fun switchAccount(id: String) {
        accountStore.setCurrent(id)
        // Redundant with the seed refresh() does on its way in, and kept on purpose: it states
        // the switch's own reason at the switch. Removing it is a decision, not a tidy-up.
        PushController.unifiedInboxVisible = false
        refresh()
    }
}

@Composable
fun SternaApp(
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
    pendingUnifiedOpen: Boolean = false,
    onUnifiedOpenConsumed: () -> Unit = {},
    viewModel: RootViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    // Preview-only gate: force the welcome at startup, then fall through to the normal routing.
    var previewWelcomeDone by rememberSaveable { mutableStateOf(false) }
    if (FORCE_ONBOARDING_PREVIEW && !previewWelcomeDone) {
        WelcomeScreen(onDone = { previewWelcomeDone = true })
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                LoadingRing()
            }
            // First genuine launch: the welcome once, then connect. A spinner while the flag loads.
            RootState.NeedAccount -> when (hasSeenWelcome) {
                null -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }
                false -> WelcomeScreen(onDone = viewModel::markWelcomeSeen)
                true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)
            }
            // Deliberately not gated on hasSeenWelcome — this is not a first run.
            RootState.AccountsUnreadable -> AccountsUnreadableScreen()
            // No key(accountId): switching updates currentAccountId in place, so the drawer's
            // carousel stays open across it.
            is RootState.Authenticated -> MainNavHost(
                accounts = accounts,
                currentAccountId = s.accountId,
                onSwitchAccount = viewModel::switchAccount,
                onAccountsChanged = viewModel::refresh,
                restoredUnifiedView = viewModel::restoredUnifiedView,
                pendingMailto = pendingMailto,
                onMailtoConsumed = onMailtoConsumed,
                pendingEmailOpen = pendingEmailOpen,
                onEmailOpenConsumed = onEmailOpenConsumed,
                pendingUnifiedOpen = pendingUnifiedOpen,
                onUnifiedOpenConsumed = onUnifiedOpenConsumed,
            )
        }
        if (locked) LockScreen(onUnlocked = appLock::unlock)
        DistributorPickerDialog()
    }
}

@Composable
private fun MainNavHost(
    accounts: List<StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onAccountsChanged: () -> Unit,
    restoredUnifiedView: () -> Boolean,
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
    pendingUnifiedOpen: Boolean = false,
    onUnifiedOpenConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    // The window WIDTH decides whether a reading pane sits beside the list (#103), never the
    // orientation. null = one pane.
    val panes = paneSplit(LocalConfiguration.current.screenWidthDp)
    // A tapped mailto: opens the composer prefilled (#15), consumed at once so a recomposition
    // cannot re-navigate.
    LaunchedEffect(pendingMailto) {
        val m = pendingMailto ?: return@LaunchedEffect
        onMailtoConsumed()
        // unguarded: a single consumption, not a repeatable tap — dropping it would lose the
        // user's action rather than de-duplicate it. See navigateOnce's KDoc.
        nav.navigate(
            "compose?to=${Uri.encode(m.to)}&cc=${Uri.encode(m.cc)}&bcc=${Uri.encode(m.bcc)}" +
                "&subject=${Uri.encode(m.subject)}&body=${Uri.encode(m.body)}",
        )
    }
    // The inbox's own ViewModel, so the notification path can ask what the list behind shows.
    // Deliberately not remembered and not collected as state, so it neither subscribes this host to
    // every list update nor caches a stale answer. Null until the NavHost has composed its start
    // destination — NOT a moment where the answer is known to be "a plain folder" (#179).
    val listHostEntry = runCatching { nav.getBackStackEntry("inbox") }.getOrNull()
    val listViewModel: InboxViewModel? = listHostEntry?.let { viewModel(it) }
    // The folder half of the same rule (#91), handed to the list rather than applied here: the
    // verdict needs a folder list that is not loaded yet after a switch. Its own one-shot, since
    // [listViewModel] is null on a cold start in the composition that consumes the tap.
    var pendingFolderOpen by remember { mutableStateOf<EmailOpenTarget?>(null) }
    // The message a tapped notification posts in the reading pane, held until the list exists (#103).
    var pendingPaneOpen by remember { mutableStateOf<MessageAnchor?>(null) }
    // A tapped new-mail notification opens that message standalone, and makes its account current
    // (#31) so Back returns to the mailbox just read.
    LaunchedEffect(pendingEmailOpen) {
        val target = pendingEmailOpen ?: return@LaunchedEffect
        onEmailOpenConsumed()
        NotificationAccountSwitch.resolve(
            notificationAccountId = target.accountId,
            currentAccountId = currentAccountId,
            knownAccountIds = accounts.map { it.id },
            // `?:`, not `== true`. A composed list has the last word; the memory speaks only when
            // there is nobody to answer, and `== true` would drop the reader into one account's
            // folder over a restored "All inboxes".
            unifiedView = listViewModel?.state?.value?.unified ?: restoredUnifiedView(),
        )?.let(onSwitchAccount)
        // Both ids or nothing: the folder is judged inside its own account, two accounts on one
        // server routinely sharing mailbox ids.
        pendingFolderOpen = target.takeIf { it.accountId != null && it.mailboxId != null }
        // Wide window with the list on screen: the reading pane (#103); anywhere else the reader
        // stacks. [PaneOrders] owns that verdict: do not inline it.
        when (PaneOrders.emailOpen(panes != null, nav.currentBackStack.value.map { it.destination.route })) {
            // unguarded: a single consumption, and it can arrive mid-transition — adding the guard
            // here could lose the opening of a notification the user just tapped.
            EmailOpenOrder.Navigate -> nav.navigate(
                "message/${Uri.encode(target.emailId)}?accountId=${Uri.encode(target.accountId.orEmpty())}",
            )
            // A lone message, no paging context: the route above carries none either.
            EmailOpenOrder.ShowInPane -> {
                pendingPaneOpen = MessageAnchor(target.emailId, target.accountId, src = null, index = 0, thread = null)
            }
        }
    }
    // Keyed on [listViewModel] too, so the request is handed over in the first composition where
    // the list exists rather than lost on a cold start.
    LaunchedEffect(pendingFolderOpen, listViewModel) {
        val target = pendingFolderOpen ?: return@LaunchedEffect
        val list = listViewModel ?: return@LaunchedEffect
        pendingFolderOpen = null
        // The message id travels with the folder: the folder switch decides whether the pane keeps
        // what is in it, and must not depend on which effect lands first (#103).
        list.showFolderFromNotification(target.accountId.orEmpty(), target.mailboxId.orEmpty(), target.emailId)
    }
    // Declared AFTER the folder effect so the folder switch lands first. remember, never
    // rememberSaveable — same reason as [heldUnifiedOpen] below.
    LaunchedEffect(pendingPaneOpen, listViewModel) {
        val anchor = pendingPaneOpen ?: return@LaunchedEffect
        val list = listViewModel ?: return@LaunchedEffect
        pendingPaneOpen = null
        // openFromNotification, never openInPane: this message must survive the account switch the
        // same notification asked for a moment ago (#103).
        list.openFromNotification(anchor)
    }
    // A tapped home-screen widget puts the list on the unified inbox (#112).
    //
    // Through selectUnified(), the drawer's own call: it also collapses expanded threads,
    // re-derives the unified scopes, rewrites the header meta and refreshes, where "just move the
    // selection" leaves the previous folder's name over a stale list.
    //
    // The order is CONSUMED FIRST and held until the list exists: [listViewModel] is looked up
    // imperatively, so on a cold start an effect that returned would return for good. remember,
    // NEVER rememberSaveable — a held `true` surviving a rotation would re-navigate over whatever
    // the user is reading. While held this is the only copy.
    var heldUnifiedOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pendingUnifiedOpen, heldUnifiedOpen, listViewModel) {
        when (WidgetTapRelay.step(pendingUnifiedOpen, heldUnifiedOpen, listViewModel != null)) {
            WidgetTapStep.Ignore -> return@LaunchedEffect
            WidgetTapStep.Hold -> {
                onUnifiedOpenConsumed()
                heldUnifiedOpen = true
            }
            WidgetTapStep.Deliver -> {
                onUnifiedOpenConsumed()
                heldUnifiedOpen = false
                val list = listViewModel ?: return@LaunchedEffect
                list.selectUnified()
                // selectUnified() moves the list, a destination BELOW whatever is on screen, so the
                // reader is unwound too — and only the reader. The whole stack is handed over:
                // popBackStack("inbox") drops everything above the list, taking search results or a
                // live draft with it. [WidgetTapNavigation] owns that verdict.
                val stack = nav.currentBackStack.value.map { it.destination.route }
                if (WidgetTapNavigation.from(stack) == WidgetTapReturn.BackToList) {
                    // unguarded: a one-shot order, already consumed above — navigateOnce's
                    // de-duplication would drop it on a mid-transition tap rather than protect
                    // anything. Same reason as the mailto: and notification effects above.
                    nav.popBackStack("inbox", inclusive = false)
                }
            }
        }
    }
    // Devices that SIGSEGV'd inside a message fade (#10) have it latched off. Read once per process.
    val navContext = LocalContext.current
    val messageFadeDisabled = remember { NavFadeGuard.fadeDisabled(navContext) }
    // rememberMotionEnabled() is @Composable, so it is captured here and read inside the transition
    // lambdas, which do not run in a composable context.
    val motionEnabled = rememberMotionEnabled()
    // Instant transitions by default: the cross-fade sticks on the bare window background during
    // rapid back/forth navigation. The message route opts back into a soft fade.
    NavHost(
        navController = nav,
        startDestination = "inbox",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable("inbox") { entry ->
            BottomNavBar(nav = nav, currentRoute = "inbox") {
            // The "inbox" entry's own ViewModel, shared with the reader and the composer.
            val inboxViewModel: InboxViewModel = viewModel(entry)
            // What the reading pane shows (null anchor = the invitation line).
            val pane by inboxViewModel.readingPane.collectAsStateWithLifecycle()
            // Half of the width conversion (#103): the window narrowed under a message in the pane,
            // so the anchor goes back to the `message/…` route. The only navigation this app plays
            val anchor = pane.anchor
            if (PaneConversion.paneToReader(panes != null, anchor != null) && anchor != null) {
                LaunchedEffect(anchor) {
                    entry.lifecycle.withResumed {
                        entry.navigateOnce { nav.navigate(anchor.toRoute(Uri::encode)) }
                        inboxViewModel.closePane()
                        inboxViewModel.onEmailOpened(anchor.emailId)
                    }
                }
            }
            InboxScreen(
                onOpenEmail = { id, accountId, index, fromSearch ->
                    // Carry the tapped position and its context so the reader pages over the same
                    // entries.
                    val anchor = MessageAnchor(id, accountId, if (fromSearch) "search" else "list", index, null)
                    if (panes != null) inboxViewModel.openInPane(anchor)
                    else entry.navigateOnce { nav.navigate(anchor.toRoute(Uri::encode)) }
                },
                // A message tapped inside an expanded conversation reads in the context of THAT
                // conversation, so the reader stops at its ends (#13). The key travels
                // account-qualified, a bare thread id having let the reader page over the sibling
                // account's homonymous conversation (#92).
                onOpenThreadMessage = { id, accountId, threadKey, index ->
                    val anchor = MessageAnchor(id, accountId, "thread", index, threadKey.encode())
                    if (panes != null) inboxViewModel.openInPane(anchor)
                    else entry.navigateOnce { nav.navigate(anchor.toRoute(Uri::encode)) }
                },
                onCompose = { entry.navigateOnce { nav.navigate("compose") } },
                // unguarded: reopening an undone send is a deliberate one-shot driven off the
                // restored-draft flow, not a stale list tap, so it must not be gated by the
                // resumed-entry guard (which was dropping it and leaving the message parked with
                // no compose to return to).
                onReopenDraft = { nav.navigate("compose?restore=true") },
                // A message tapped in Drafts opens in compose for editing (#63).
                onEditDraft = { id, accountId ->
                    entry.navigateOnce {
                        nav.navigate("compose?draftId=${Uri.encode(id)}&accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
                onOpenSettings = { entry.navigateOnce { nav.navigate("settings") } },
                onOpenHome = { entry.navigateOnce { nav.navigate("home") } },
                // The words already typed in the search bar travel with the navigation.
                onOpenSearch = { q -> entry.navigateOnce { nav.navigate("search?q=${Uri.encode(q)}") } },
                // The drawer's Starred entry: the SAME search screen, opened on the one criterion
                // and already run. No mailbox id anywhere in this route — a star is a keyword, so
                // "Starred" is a query and never a folder the app could move mail into.
                onOpenStarred = { entry.navigateOnce { nav.navigate("search?flagged=true") } },
                onOpenAssigned = { entry.navigateOnce { nav.navigate("assigned") } },
                onOpenScheduled = { entry.navigateOnce { nav.navigate("scheduled") } },
                onOpenSnoozed = { entry.navigateOnce { nav.navigate("snoozed") } },
                onOpenOutbox = { entry.navigateOnce { nav.navigate("outbox") } },
                onOpenMailBySender = { entry.navigateOnce { nav.navigate("bysender") } },
                accounts = accounts,
                currentAccountId = currentAccountId,
                onSwitchAccount = onSwitchAccount,
                onOpenAccountSettings = { id -> entry.navigateOnce { nav.navigate("settings?accountId=$id") } },
                viewModel = inboxViewModel,
                panes = panes,
                detail = panes?.let {
                    {
                        ReadingPane(
                            state = pane,
                            inboxViewModel = inboxViewModel,
                            // Guarded on the INBOX entry being resumed, as the full-screen reader
                            // guards the same hand-offs on its own.
                            onReply = { mode, replyToId, replyAccountId ->
                                entry.navigateOnce {
                                    val accountArg = replyAccountId?.let { "&accountId=${Uri.encode(it)}" }.orEmpty()
                                    nav.navigate("compose?replyTo=${Uri.encode(replyToId)}&mode=$mode$accountArg")
                                }
                            },
                            onComposeTo = { address ->
                                entry.navigateOnce { nav.navigate("compose?to=${Uri.encode(address)}") }
                            },
                        )
                    }
                },
            )
        }
        }
        composable(
            route = "message/{emailId}?accountId={accountId}&index={index}&src={src}&thread={thread}",
            arguments = listOf(
                navArgument("emailId") { type = NavType.StringType },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                // Position of the tapped entry, and its context: "list", "search", "thread", or
                // absent for a lone message.
                navArgument("index") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("src") { type = NavType.StringType; nullable = true; defaultValue = null },
                // Which conversation, when src=thread.
                navArgument("thread") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // A short soft cross-fade on open and close, unless [NavFadeGuard] has latched after a
            // fade-window crash (#10) or reduced motion is on — symmetrically, so there is no
            // half-animated open/close (#100).
            enterTransition = {
                if (messageFadeDisabled || !motionEnabled) EnterTransition.None else fadeIn(tween(200))
            },
            popExitTransition = {
                if (messageFadeDisabled || !motionEnabled) ExitTransition.None else fadeOut(tween(200))
            },
        ) { entry ->
            val emailId = Uri.decode(entry.arguments?.getString("emailId").orEmpty())
            val accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null }
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val src = entry.arguments?.getString("src")
            // The conversation key exactly as the route carries it; [threadKey] below is decoded.
            val threadArg = entry.arguments?.getString("thread")?.let { Uri.decode(it) }?.ifBlank { null }
            // The other half of the width conversion (#103): a rotation restores this reader full
            // screen over a list that is now two panes wide. The reader is NOT composed on this
            // pass: a second MessageViewModel would settle the message twice — a read receipt
            // offered twice, a second decryption, a message marked read from a screen nobody saw.
            val stack = remember(entry) { nav.currentBackStack.value.map { it.destination.route } }
            if (PaneConversion.readerToPane(panes != null, stack)) {
                val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
                val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
                LaunchedEffect(entry) {
                    entry.lifecycle.withResumed {
                        inboxViewModel.openInPane(MessageAnchor.fromRoute(emailId, accountId, index, src, threadArg))
                        entry.navigateOnce { nav.popBackStack() }
                    }
                }
                // Nothing to draw: the message is in the pane, one destination below.
                Box(Modifier.fillMaxSize())
                return@composable
            }
            // The inbox's own ViewModel, so the reader pages over the exact list the user was
            // looking at rather than a copy.
            val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
            val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
            val listSource = if (src == "list") inboxViewModel.pagedEmails else null
            val searchResults = if (src == "search") {
                remember(inboxViewModel) { inboxViewModel.state.value.searchResults }
            } else {
                null
            }
            // Opened from an unfolded conversation: page over the expansion the list already holds,
            // snapshotted once so a background completion cannot shift the pages.
            val threadKey = threadArg?.let { ThreadKey.decode(it) }
            val threadEntries = if (src == "thread" && threadKey != null) {
                remember(inboxViewModel, threadKey) { inboxViewModel.threadEntries(threadKey) }
            } else {
                null
            }
            // The fades composite this destination through an offscreen graphics layer, and the body
            // WebView must not draw its GL functor into it (null-SkSurface SIGSEGV, #10).
            val navTransitionActive = transition.currentState != EnterExitState.Visible ||
                transition.targetState != EnterExitState.Visible
            CompositionLocalProvider(
                LocalNavTransitionActive provides navTransitionActive,
                app.sterna.ui.message.LocalOpenAccountSettings provides { id ->
                    entry.navigateOnce { nav.navigate(if (id != null) "settings?accountId=$id" else "settings") }
                },
            ) {
                MessageScreen(
                    anchorEmailId = emailId,
                    anchorAccountId = accountId,
                    initialIndex = index,
                    listSource = listSource,
                    searchResults = searchResults,
                    threadEntries = threadEntries,
                    // Guard both actions on the message entry being resumed: during its fade-out
                    // pop the screen is still composed and its Back arrow still tappable, so an
                    // unguarded onBack would popBackStack a second time and empty the stack
                    // (the white-screen freeze). A reply navigate is gated for the same reason.
                    onBack = { entry.navigateOnce { nav.popBackStack() } },
                    // Delete via the shared inbox VM, so the reader reuses the same held-back
                    // destroy and Undo as swipe and bulk (#23).
                    onDelete = { email ->
                        entry.navigateOnce {
                            inboxViewModel.delete(email)
                            nav.popBackStack()
                        }
                    },
                    // Archive via the same shared VM, so the reader gets the count nudge and Undo.
                    onArchive = { email ->
                        entry.navigateOnce {
                            inboxViewModel.archive(email)
                            nav.popBackStack()
                        }
                    },
                    // Move to folder through the same shared VM (#73): the screen returns to the
                    // list because the message just left the folder read.
                    onMove = { email, targetMailboxId, targetAccountId ->
                        entry.navigateOnce {
                            inboxViewModel.moveTo(email, targetMailboxId, targetAccountId)
                            nav.popBackStack()
                        }
                    },
                    onReply = { mode, replyToId, replyAccountId ->
                        entry.navigateOnce {
                            val accountArg = replyAccountId?.let { "&accountId=${Uri.encode(it)}" }.orEmpty()
                            nav.navigate("compose?replyTo=${Uri.encode(replyToId)}&mode=$mode$accountArg")
                        }
                    },
                    onComposeTo = { address ->
                        entry.navigateOnce {
                            nav.navigate("compose?to=${Uri.encode(address)}")
                        }
                    },
                )
            }
        }
        composable(
            route = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}&restore={restore}" +
                "&to={to}&cc={cc}&bcc={bcc}&subject={subject}&body={body}&draftId={draftId}&outboxId={outboxId}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                // A saved draft reopened for editing (#63).
                navArgument("draftId") { type = NavType.StringType; nullable = true; defaultValue = null },
                // A queued row reopened by Outbox → Edit (#70), the twin of draftId: the id rides in
                // the route, which is what Android restores after a process death.
                navArgument("outboxId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("restore") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                // mailto: prefills (Codeberg #15).
                navArgument("cc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bcc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("subject") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            // The inbox's own ViewModel, so deleting the draft being edited is the Drafts list's own
            // gesture: a move to Trash, held-back destroy and Undo snackbar (#23, #127).
            val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
            val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
            // The composer, and only the composer, asks the keyboard not to learn what is typed in
            // it (#120). Wrapped here, at the single route that mounts it: the scope is identical,
            // the interceptor being a CompositionLocal read at each field's node.
            IncognitoKeyboard {
                ComposeScreen(
                    // After a send, return to the inbox rather than the message underneath.
                    onDone = { entry.navigateOnce { nav.popBackStack("inbox", inclusive = false) } },
                    onCancel = { entry.navigateOnce { nav.popBackStack() } },
                    // A single pop, like onCancel: the Drafts list is where the "Message deleted /
                    // Undo" snackbar has to appear. The draft is TAKEN inside the guard (#127):
                    // takeEditingDraft() hands the row over exactly once, so consuming it outside
                    // meant a tap the guard drops threw the draft away without deleting anything.
                    onDeleteDraft = { take ->
                        entry.navigateOnce {
                            val email = take() ?: return@navigateOnce
                            inboxViewModel.delete(email)
                            nav.popBackStack()
                        }
                    },
                    replyTo = entry.arguments?.getString("replyTo")?.let { Uri.decode(it) },
                    mode = entry.arguments?.getString("mode"),
                    accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null },
                    restore = entry.arguments?.getString("restore") == "true",
                    to = entry.arguments?.getString("to")?.let { Uri.decode(it) }?.ifBlank { null },
                    cc = entry.arguments?.getString("cc")?.let { Uri.decode(it) }?.ifBlank { null },
                    bcc = entry.arguments?.getString("bcc")?.let { Uri.decode(it) }?.ifBlank { null },
                    subject = entry.arguments?.getString("subject")?.let { Uri.decode(it) }?.ifBlank { null },
                    body = entry.arguments?.getString("body")?.let { Uri.decode(it) }?.ifBlank { null },
                    draftId = entry.arguments?.getString("draftId")?.let { Uri.decode(it) }?.ifBlank { null },
                    outboxId = entry.arguments?.getString("outboxId")?.toLongOrNull(),
                )
            }
        }
        composable(
            route = "search?q={q}&from={from}&flagged={flagged}",
            // Read straight from the entry's SavedStateHandle, so all three survive a process death.
            // `q` and `from` each fill their field and neither runs the search; `flagged` is the
            // exception and runs it (the drawer's Starred entry — see SEARCH_FLAGGED_ARG).
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("flagged") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            SearchScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenEmail = { id, accountId ->
                    entry.navigateOnce {
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
            )
        }
        composable(
            route = "settings?accountId={accountId}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // An opaque slide rather than the default cross-fade, which flashes the bare window
            // background. Off when reduced motion is on (#100).
            enterTransition = {
                if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { it }
            },
            popExitTransition = {
                if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { it }
            },
        ) { entry ->
            SettingsScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onAccountsChanged = onAccountsChanged,
                initialAccountId = entry.arguments?.getString("accountId")?.ifBlank { null },
            )
        }
        // The mail statistics of every configured account. A destination like "snoozed" and not a
        // start destination: the app still opens on the inbox, which is what the reader came for.
        composable("home") { entry ->
            val homeContext = LocalContext.current
            val leaveOnce = rememberLeaveOnce(entry)
            BottomNavBar(nav = nav, currentRoute = "home") {
                HomeScreen(
                    onBack = { entry.navigateOnce { nav.popBackStack() } },
                    onOpenCompose = { entry.navigateOnce { nav.navigate("compose") } },
                    onOpenSearch = { q -> entry.navigateOnce { nav.navigate("search?q=${Uri.encode(q)}") } },
                    onOpenStarred = { entry.navigateOnce { nav.navigate("search?flagged=true") } },
                    onOpenAppSettings = { entry.navigateOnce { nav.navigate("settings") } },
                    onOpenScheduled = { entry.navigateOnce { nav.navigate("scheduled") } },
                    onOpenSnoozed = { entry.navigateOnce { nav.navigate("snoozed") } },
                    onOpenOutbox = { entry.navigateOnce { nav.navigate("outbox") } },
                    onOpenMailBySender = { entry.navigateOnce { nav.navigate("bysender") } },
                    onOpenRss = { entry.navigateOnce { nav.navigate("rss") } },
                    onOpenAgents = { leaveOnce { openAgents(homeContext) } },
                    onOpenWorkflows = { leaveOnce { openWorkflows(homeContext) } },
                    onOpenAi = { leaveOnce { openHermesAi(homeContext) } },
                )
            }
        }
        // Assigned: the mails that have a task (pane entry). A tap opens the mail.
        composable("assigned") { entry ->
            AssignedScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenEmail = { id, accountId ->
                    entry.navigateOnce {
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId)}")
                    }
                },
            )
        }
        composable("scheduled") { entry ->
            ScheduledSendsScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("snoozed") { entry ->
            SnoozedScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("outbox") { entry ->
            OutboxScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onEditDraft = { id -> entry.navigateOnce { nav.navigate("compose?restore=true&outboxId=$id") } },
            )
        }
        composable("bysender") { entry ->
            MailBySenderScreen(
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                // The address travels as the search's `from` criterion.
                onOpenSearch = { from ->
                    entry.navigateOnce { nav.navigate("search?from=${Uri.encode(from)}") }
                },
            )
        }
        composable("rss") { entry ->
            BottomNavBar(nav = nav, currentRoute = "rss") {
                RssScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
            }
        }
    }
}

/** UnifiedPush distributor picker (#17): shown only when several distributors are installed and
 *  none has been chosen. One installed is used silently; none means nothing happens. */
@Composable
private fun DistributorPickerDialog() {
    val context = LocalContext.current
    val manager = (context.applicationContext as Application).container.unifiedPushManager
    val needsChoice by manager.needsDistributorChoice.collectAsStateWithLifecycle()
    if (!needsChoice) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { manager.dismissDistributorChoice() },
        title = { androidx.compose.material3.Text(stringResource(R.string.up_picker_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                val distributors = manager.distributors()
                // Two distributors can share an app label (#17), so the package name is appended
                // only for the colliding ones.
                val labelCounts = distributors.groupingBy { appLabelOf(context, it) }.eachCount()
                distributors.forEach { pkg ->
                    val label = appLabelOf(context, pkg)
                    val shown = if ((labelCounts[label] ?: 0) > 1) "$label ($pkg)" else label
                    androidx.compose.material3.TextButton(onClick = { manager.distributorChosen(pkg) }) {
                        androidx.compose.material3.Text(shown)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { manager.dismissDistributorChoice() }) {
                androidx.compose.material3.Text(stringResource(R.string.inbox_cancel))
            }
        },
    )
}

/** Best-effort human app label for a package (falls back to the package name). */
internal fun appLabelOf(context: android.content.Context, packageName: String?): String {
    packageName ?: return "UnifiedPush"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

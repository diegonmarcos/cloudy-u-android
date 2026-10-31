package com.diegonmarcos.clouddrive.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.StoreSeedWorker
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.scrollFadeEnd
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusDot
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.configs.DriveAuthApply
import com.diegonmarcos.clouddrive.configs.DriveGitChain
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.auth.AuthMission
import com.diegonmarcos.cloudlib.auth.ConfigArtifact
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #608 SYNC ▸ GIT, redesigned whole and DECLARED whole (build.json::ui.sync.git — the
 * sections, the personal split, the login ways, the endpoint, the remote modes and the
 * fourteen per-repository operations all come from there; this file names no repository,
 * no owner, no URL and no operation label).
 *
 * DATA-DENSE: one COMPACT row per repository — the light, the name, a handful of inline
 * monospace stats, its clone state — so a long list is scannable, with every operation
 * COLLAPSED behind the row's own disclosure instead of a large box per repository. The
 * expanded row is the operations box: fetch · pull · stage & commit · push · force push ·
 * force pull · metadata · open on web · stats · commit history · remote switch (HTTPS /
 * SSH / read-only) · hooks · GitHub Actions workflows · size, every git verb reached
 * through libs:git-sync's GitEngine and never a second git.
 *
 * TWO SECTIONS (ui.sync.git.sections):
 *  · public   — the declared set of the owner's PUBLIC repositories in the cloud and
 *               front name families. No login: they clone over plain HTTPS.
 *  · personal — the credential the #566 vault config import delivers (#629), or the user's
 *               existing SSH key. The account's own listing is fetched with that credential,
 *               with no tap, and split into the declared groups, Public then Private. #641
 *               the fleet's own GitHub device grant is gone. #689 the GitHub card is gh
 *               ITSELF (libs:gh): gh's own sign-in, `gh repo list`, and a clone on the
 *               credential gh holds — no OAuth-App client of the fleet's own, anywhere.
 *
 * #1138 THE LIST IS A TABLE (RepoTable): tap a header to sort by it, tap again to flip; Name, Pvt/Pub,
 * Size main, Size total, Branches, Dirty and Ahead/Behind per row, the remote facts from the cached REST
 * answer (pull to refresh) and the local ones from the git engine's status. An expanded repository shows
 * its actions grouped one row per command type (#1289 the declared ui.sync.git.repo_menu groups).
 *
 * Every clone lands in the store's git folder (#606, SharedStore.gitRoot), the same folder
 * and the same registry the first-run seed uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
/** #1149 which sub-page of Git this screen is: Me (sign-in, my repositories, parentless folders) or Cloud (the public cloud* list). */
enum class GitView { ME, CLOUD }

/** Which half of Me is drawn: TABLE is the Git page (bulk bar, summary, table, Local, History); SETUP is Configs ▸ Git (everything that used to sit above the table). */
enum class GitPart { TABLE, SETUP }

/** One row of THE table, whatever listing it came from; [onClone] clones it from the leg that listed it, [note] is a line under it. */
private class TableEntry(
    val key: String, val label: String, val name: String, val owner: String, val isPrivate: Boolean?, val fork: Boolean, val source: String,
    val row: RepoTableRow, val cloned: ManagedRepo?, val onClone: () -> Unit, val note: String, val cloneUrl: String = "",
)

/** #1149 Cloud's live public list: loading, the filtered repositories, or why there are none (the declared set then stands in). */
private data class CloudPublic(val loading: Boolean = false, val loaded: Boolean = false, val repos: List<GitHubRepos.Repo> = emptyList(), val error: String = "")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GitReposScreen(coordinator: GitSyncCoordinator, actions: DriveActions, nextRunMinutes: Long?, modifier: Modifier = Modifier, view: GitView = GitView.ME, prefs: DrivePrefs? = null, part: GitPart = GitPart.TABLE) {
    val setup = view == GitView.ME && part == GitPart.SETUP
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = Declarations.sync.git
    // #1149 Cloud: the live PUBLIC cloud* list (no sign-in); the declared set stands in when the network refuses.
    var cloudPublic by remember { mutableStateOf(CloudPublic()) }
    LaunchedEffect(view) {
        if (view == GitView.CLOUD) {
            cloudPublic = CloudPublic(loading = true)
            val r = withContext(Dispatchers.IO) { CloudRepos.fetch(page.api.baseUrl, Declarations.cloudList) }
            cloudPublic = r.fold(
                onSuccess = { CloudPublic(loaded = true, repos = it.repos) },
                onFailure = { CloudPublic(loaded = true, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }
    // The registry (baked snapshot, replaced by the newer live copy when RepoSotLive fetched one) feeds the declared fallback, so a stale build never lists names the registry has since renamed or retired.
    val sotRev by coordinator.sotRev.collectAsState()
    val fullProgress by coordinator.fullProgress.collectAsState()
    val registryRows = remember(sotRev) {
        Declarations.repoSot.repos.filter { !it.private && !it.pending && page.inNameFamilies(it.github) }.map { Declarations.GitPublicRepoDecl(it.github, it.github) }.sortedBy { it.name }
    }
    val publicRows = if (cloudPublic.repos.isNotEmpty()) cloudPublic.repos.map { Declarations.GitPublicRepoDecl(it.name, it.name) } else registryRows.ifEmpty { page.publicRepos }
    // #1149 Me: the declared hosts beyond GitHub and Cloud git (token sign-in, their own listings).
    val hostsState = remember { GitHostsState(ctx.applicationContext, scope) }
    LaunchedEffect(view) { if (view == GitView.ME) hostsState.restore() }
    val repos by coordinator.repos.collectAsState()
    val glances by coordinator.glances.collectAsState()
    val running by coordinator.running.collectAsState()
    val events by coordinator.events.collectAsState()
    val details by coordinator.details.collectAsState()
    val opResults by coordinator.opResults.collectAsState()
    val cloning by coordinator.cloning.collectAsState()
    // #1138 the repo table: the cached REST facts, the clone settings, the sort and the one scroll the header and rows share.
    val remoteStats by coordinator.remoteStats.collectAsState()
    val diskSizes by coordinator.diskSizes.collectAsState()
    val autoSettings = rememberAutoSettings(AutoChecks.Page.GIT, prefs)
    val refreshingRemote by coordinator.refreshingRemote.collectAsState()
    val cloneDepth by coordinator.cloneDepth.collectAsState()
    val cloneSsh by coordinator.cloneViaSsh.collectAsState()
    // #1256 the sort and the filters are the coordinator's, persisted in the page prefs: they survive page changes and minimize.
    val sortState by coordinator.sort.collectAsState()
    val sortColumn = sortState.first
    val sortAscending = sortState.second
    val filterSel by coordinator.filterSelection.collectAsState()
    // #1356 the page's state outlives the page: read back from the activity-scoped store, written on every change below.
    val memoryStore = LocalGitPageMemory.current
    val memoryKey = GitPageMemoryStore.keyOf(view, part)
    val memory = remember(memoryKey) { memoryStore.get(memoryKey) }
    val lastCommits by coordinator.lastCommits.collectAsState()
    // #1254 the declared depth command in flight or last finished; #1255 the unlisted git folders.
    val depthRun by coordinator.depthRun.collectAsState()
    val unlistedFolders by coordinator.unlisted.collectAsState()
    // #1315 the ONE inventory (every folder classified once): the Parentless rows and the duplicate / bare / debris sections read it.
    val inventoryState by coordinator.inventory.collectAsState()
    var depthConfirm by remember { mutableStateOf<Declarations.GitDepthActionDecl?>(null) }
    var showDepthReport by remember { mutableStateOf(false) }
    val tableScroll = rememberScrollState()
    // #1356 the repo id whose settings sheet is open ("" = none), resolved against the live list when drawn.
    var settingsFor by remember { mutableStateOf(memory.settingsFor) }
    // #1238 the row whose full-screen status window is open (TableEntry.key; "" = none).
    var statusFor by remember { mutableStateOf(memory.statusFor) }
    // #1258 Activity Logs (was History): a top button opens it as its own view.
    var showActivity by remember { mutableStateOf(memory.showActivity) }
    // #1356 which Logs tab opens: Logs in the top nav -> the first declared tab (repos); the job line -> app.
    var logsTab by remember { mutableStateOf(memory.logsTab) }
    var expanded by remember { mutableStateOf(memory.expanded) }
    // #1356 the open sheet's sub-menu, and the repo it belongs to.
    var panelRepo by remember { mutableStateOf(memory.panelRepo) }
    var panel by remember { mutableStateOf(memory.panel) }
    // A closed sheet forgets its sub-menu: the next repo opens on its actions.
    LaunchedEffect(expanded) { if (expanded.isEmpty()) { panelRepo = ""; panel = "" } }
    var login by remember { mutableStateOf(GitLogin()) }
    var listing by remember { mutableStateOf(GitListing()) }
    // #646 what the DECLARED credential chain is about to do, and afterwards what it did.
    // The declared order is read once, off the declaration, so this page describes the
    // ranking it will actually follow rather than a ranking somebody typed here.
    var chain by remember { mutableStateOf(GitChainState(order = DriveGitChain.declaredOrder())) }
    // #684 THE SECOND LISTING. Two parallel way-cards mean two independent listings: the
    // GitHub card fills [listing] (gh repo list, or the #629 vault token), the Cloud git card
    // fills this one off the Authelia session. Neither card touches the other's.
    var cloudListing by remember { mutableStateOf(GitListing()) }
    // Which way's sign-in is in flight, and the declared fallback a phone without the fleet
    // browser shows for the Cloud sign-in (it SAYS it is the fallback).
    var signingInWay by remember { mutableStateOf("") }
    var pendingWay by remember { mutableStateOf("") }
    var cloudFallback by remember { mutableStateOf(false) }
    // #689 THE GITHUB CARD IS gh: who gh is signed in as, and the one-time code its own
    // `auth login` is waiting on. Neither is a secret; the credential stays in gh's config.
    var ghAuth by remember { mutableStateOf(GhAuth()) }
    // #850 once per entry to the page: the bounded, cached status read, and the auto pull
    // when it is on and the network is unmetered. Recomposition never re-runs this.
    LaunchedEffect(Unit) { coordinator.onPageOpened() }
    // The page closed: stop the status reads still queued (a running probe finishes, bounded by its timeout).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { coordinator.cancelStatusReads() } }
    // #1195 the periodic check, only while this page is open and only when an interval is set (off by default).
    LaunchedEffect(autoSettings.intervalMinutes) {
        while (autoSettings.intervalMinutes > 0) { delay(autoSettings.intervalMinutes * 60_000L); coordinator.onTick() }
    }
    val bulk by coordinator.bulk.collectAsState()
    val autoPull by coordinator.autoPullOnOpen.collectAsState()
    val autoPullNote by coordinator.autoPullNote.collectAsState()
    var confirmPullAll by remember { mutableStateOf(false) }

    /**
     * #653 THE FLEET SIGN-IN'S HOST. The browser login's session arrives through
     * [SignInHost.onWebSession] — NOT through SignInResult.bearer, which libs:auth leaves
     * empty for this way — and is kept in [FleetSession], a process-lifetime field with no
     * writer to any store. Nothing here persists a credential, which is what
     * test-drive-configs-sign-in's "the drive host stores no bearer" requires.
     *
     * The artifact a fleet login also returns is NOT applied here: the app's own
     * configs surface owns that, and applying it twice would be a second apply path.
     * Deliberately worded without naming another page — this page states its own
     * state and never sends the owner somewhere else (#639).
     */
    val fleetHost = remember {
        object : SignInHost {
            override fun onSignedIn(result: SignInResult) {
                chain = chain.copy(signedIn = FleetSession.present)
            }
            override fun onWebSession(cookie: String) {
                FleetSession.remember(cookie)
                chain = chain.copy(signedIn = FleetSession.present)
            }
        }
    }

    // #606 the store's git folder — the ONE place a clone lands, resolved from the declaration.
    val root = remember { SharedStore.gitRoot().absolutePath }
    // #683 THE FIRST-RUN SEED'S LAST VERDICT, on the page that owns the store. The tally was
    // persisted for exactly this and then read by nothing: an incomplete pass, or one blocked
    // for want of a credential, was only readable off Download/drive-debug/. What is shown is
    // the report's OWN tally line — the same words the log carries, never new prose and never
    // a credential — and a cleanly complete pass draws nothing.
    // A file read: off the main thread (#1195), blank until it lands.
    val seedTally by produceState("") { value = withContext(Dispatchers.IO) { StoreSeedWorker.lastReport(ctx).trim().lines().lastOrNull().orEmpty() } }
    // A clone folder under an OLD name (cloud-infra) is the same repository as its renamed row (cloudy-t-infra): keyed by the current name, never listed twice.
    val clonedByName = repos.associateBy { Declarations.repoSot.canonical(File(it.path).name) }
    // #1315 the declared top bar's own state: the search, the Filters sheet, the legend, a confirmation in flight, the executor's jobs.
    var query by remember { mutableStateOf(memory.query) }
    var showFilters by remember { mutableStateOf(memory.showFilters) }
    var showAdvanced by remember { mutableStateOf(memory.showAdvanced) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(memory.scrollIndex, memory.scrollOffset)
    LaunchedEffect(memoryKey) {
        androidx.compose.runtime.snapshotFlow {
            GitPageMemory(
                expanded = expanded, panelRepo = panelRepo, panel = panel, settingsFor = settingsFor, statusFor = statusFor,
                showActivity = showActivity, logsTab = logsTab, query = query, showFilters = showFilters, showAdvanced = showAdvanced,
                scrollIndex = listState.firstVisibleItemIndex, scrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }.collect { memoryStore.put(memoryKey, it) }
    }
    var showLegend by remember { mutableStateOf(false) }
    var topConfirm by remember { mutableStateOf<Declarations.GitTopItemDecl?>(null) }
    val jobsState by coordinator.jobs.jobs.collectAsState()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    // #1138 one table row of one repository: the cached remote facts and the local glance, no network here.
    // The Provider cell comes from the clone's own remote URL, else the listing's clone URL, through the declared host map.
    fun tableRow(name: String, owner: String, declaredPrivate: Boolean?, listedUrl: String = ""): RepoTableRow {
        val cloned = clonedByName[name]
        val url = cloned?.remoteUrl?.takeIf { it.isNotBlank() } ?: listedUrl
        return RepoTable.rowOf(name, declaredPrivate, cloned != null, cloned?.let { GitSyncCoordinator.glanceFor(it, glances) }, remoteStats[RepoRemoteStats.keyOf(owner, name)], RepoProvider.labelOf(url, Declarations.providers), cloned?.let { diskSizes[File(it.path).absolutePath]?.bytes }, Declarations.repoSot.lfsOf(name), cloned?.let { lastCommits[LastCommits.keyOf(it.path)] })
    }
    // Pull-to-refresh re-reads the REST facts of every repository the table lists (GitHub-listed or declared; the fleet's own git is not GitHub).
    fun refreshTargets(): List<Pair<String, String>> {
        val named = publicRows.map { it.name }.toSet() + listing.repos.map { it.name }.toSet()
        return publicRows.map { page.owner to it.name } +
            listing.repos.map { it.owner.ifBlank { page.owner } to it.name } +
            repos.map { Declarations.repoSot.canonical(File(it.path).name) }.filter { it !in named }.map { page.owner to it }
    }
    // #1256 the Last commit fallback of a not-cloned row is the forge's pushed_at: fetched once when nothing cached has it.
    LaunchedEffect(Unit) { coordinator.fillRemoteStatsOnce(refreshTargets(), login.token) }

    // #642 what the last handoff to cloud-terminal-nix resolved to. Never empty after a
    // clone attempt: a handoff that reported nothing is the silent no-op this feature exists
    // to make impossible.
    var handoff by remember { mutableStateOf("") }
    val askRunCommand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // #639: state the fact, do not narrate a route through Settings.
        handoff = ctx.getString(if (ok) R.string.git_terminal_granted else R.string.git_terminal_refused)
    }

    /**
     * #642 CLONE IS THE TERMINAL'S JOB. The argv goes to cloud-terminal-nix, which has a real
     * git and the credential for it; this app keeps none. Every outcome is turned into one
     * factual line, including where the clone will land, because the store folder it lands in
     * is the folder Files already lists — so the result is visible without a second step.
     *
     * The declaration's absence is the ONLY case that falls back to the in-process JGit path,
     * and it says so: a build with no terminal block is a misconfiguration, not a mode.
     */
    fun cloneViaTerminal(name: String, url: String): Boolean {
        val dest = SharedStore.repoDir(name)
        val outcome = TerminalGit.run(ctx, page.terminal, Declarations.GIT_OP_CLONE, url, dest, cloneDepth)
        handoff = when (outcome) {
            is TerminalGit.Outcome.Sent ->
                ctx.getString(R.string.git_terminal_sent, name, outcome.dest.absolutePath)
            is TerminalGit.Outcome.NotInstalled ->
                ctx.getString(R.string.git_terminal_absent, outcome.pkg)
            is TerminalGit.Outcome.NeedsPermission -> {
                askRunCommand.launch(outcome.permission)
                ctx.getString(R.string.git_terminal_asking)
            }
            is TerminalGit.Outcome.NoService ->
                ctx.getString(R.string.git_terminal_no_service, outcome.action)
            is TerminalGit.Outcome.Refused ->
                ctx.getString(R.string.git_terminal_refused_by, outcome.why)
            is TerminalGit.Outcome.NoSuchOp ->
                ctx.getString(R.string.git_terminal_no_op, outcome.op)
            TerminalGit.Outcome.NotDeclared ->
                ctx.getString(R.string.git_terminal_not_declared)
        }
        return outcome is TerminalGit.Outcome.Sent
    }

    /**
     * Clone [name] through the terminal; only an undeclared handoff falls back to JGit.
     *
     * #669 THE URL IS THE LISTING'S, NEVER RE-TEMPLATED. [listedUrl]/[listedSshUrl] are the
     * clone URLs the server that LISTED the repository declared for it (gitea's items point
     * at the fleet's own git host, GitHub's at github.com). Rebuilding the URL from the
     * page's declared owner and host — what this function used to do for every row — sent a
     * gitea-listed repository to github.com/<declared owner>/<name>: a clone from the WRONG
     * LEG that reads as the right one until the repository is not there. Blank means the row
     * is a DECLARED public repo with no listing item, for which the declared template is the
     * truth; a LISTED item with a blank URL never reaches here (the row says so instead).
     */
    fun clone(name: String, listedUrl: String = "", listedSshUrl: String = "") {
        if (page.terminal != null) {
            // #1138 clone settings: the persisted SSH | Web OAuth choice picks the URL, the depth rides the argv.
            cloneViaTerminal(name, if (cloneSsh) listedSshUrl.ifBlank { page.cloneUrl(name, Declarations.REMOTE_SSH) } else listedUrl.ifBlank { page.cloneUrl(name, Declarations.REMOTE_HTTPS) })
            return
        }
        handoff = ctx.getString(R.string.git_terminal_not_declared)
        val ssh = (login.ssh || cloneSsh) && login.sshKeyPath.isNotBlank()
        val mode = if (ssh) Declarations.REMOTE_SSH else Declarations.REMOTE_HTTPS
        coordinator.cloneInto(
            name = name,
            url = (if (ssh) listedSshUrl else listedUrl).ifBlank { page.cloneUrl(name, mode) },
            authKind = when {
                ssh -> GitSyncCoordinator.AUTH_SSH
                login.token.isNotBlank() -> GitSyncCoordinator.AUTH_HTTPS
                else -> GitSyncCoordinator.AUTH_NONE
            },
            username = login.identity,
            token = if (ssh) "" else login.token,
            sshKeyPath = if (ssh) login.sshKeyPath else "",
        )
    }

    /**
     * #669 A FLEET-LISTED REPOSITORY CLONES ON THE SESSION THAT LISTED IT. The
     * terminal handoff is deliberately NOT taken here: the terminal's git cannot
     * present the app's Authelia session cookie, so a fleet clone through it can
     * only ever bounce off the gate. The IN-PROCESS engine carries the session on
     * the rung's DECLARED header, against the rung's DECLARED clone URL for this
     * item's owner/name (FleetGit.cloneUrl — template and order both data, and both
     * fleet hosts, so the session can never travel to a third party). Every refusal
     * is loud and names its next step; nothing falls through to another host.
     */
    fun cloneViaFleet(rungId: String, gh: GitHubRepos.Repo) {
        if (!FleetSession.present) {
            handoff = ctx.getString(R.string.git_fleet_clone_no_session)
            return
        }
        val url = FleetGit.cloneUrl(rungId, gh.owner, gh.name, gh.cloneUrl)
        if (url.isBlank()) {
            handoff = ctx.getString(R.string.git_clone_url_missing, gh.name)
            return
        }
        coordinator.cloneInto(
            name = gh.name,
            url = url,
            authKind = GitSyncCoordinator.AUTH_SESSION,
            username = "",
            token = "",
        )
    }

    /**
     * #653 THE SAME LISTING, SERVED BY THE FLEET, with no GitHub credential anywhere.
     * Only the fleet session is presented; the proxy's answer is a projection that cannot
     * carry a credential back. Every non-success is reported in words, and the three
     * states are kept APART on purpose — a redirect means the edge answered and says
     * nothing about the service, so it must never read as either working or broken.
     */
    fun fetchFleetListing(rungId: String) {
        cloudListing = cloudListing.copy(loading = true, error = "")
        scope.launch {
            // #669 the listing is fetched from the rung that ANSWERED — gitea and
            // git-proxy-api are both declared under this kind now, and asking the
            // family's first-ranked endpoint after the SECOND one answered would
            // list from a service the chain just measured as unreachable.
            val outcome = withContext(Dispatchers.IO) { FleetGit.repos(FleetSession.cookie, rungId) }
            cloudListing = when (outcome) {
                is FleetGit.Outcome.Listed -> GitListing(loaded = true, repos = outcome.repos, complete = true, rungId = rungId)
                is FleetGit.Outcome.Refused -> GitListing(loaded = true, error = outcome.why)
                is FleetGit.Outcome.Blocked -> GitListing(
                    loaded = true,
                    error = ctx.getString(R.string.git_fleet_blocked, outcome.code),
                )
                is FleetGit.Outcome.Unreachable -> GitListing(loaded = true, error = outcome.why)
            }
        }
    }

    /** The account's own repositories, with the token the sign-in proved. */
    fun fetchListing(token: String) {
        listing = listing.copy(loading = true, error = "")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { GitHubRepos.fetch(page.api, token) }
            listing = outcome.fold(
                onSuccess = { GitListing(loaded = true, repos = it.repos, complete = it.complete) },
                onFailure = { GitListing(loaded = true, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /**
     * #646 WALK THE DECLARED CHAIN — fleet first, GitHub second, both from ONE ordered
     * declaration. Nothing about the ranking is decided here: [DriveGitChain] maps
     * `AuthDeclaration.gitChain` straight through, so the order this follows is the order
     * in the JSON.
     *
     * EVERY STEP IS RENDERED, including the ones that were skipped and why — "Cloud fleet
     * unreachable → GitHub signed in" is a sentence the owner can read, not a state he has
     * to infer from a credential appearing. That is the #639/#452 lesson: a chain that
     * degrades silently looks exactly like a chain that works.
     *
     * #653 NOTHING IS TYPED BY THE OWNER ON ANY RUNG. The code display, the verification
     * URI display and the phase callback that fed them are DELETED, along with the device
     * grant behind them: the fleet rung needs no GitHub credential at all, and the gh rung
     * uses one that is already on the phone. So the only things this state ever carries are
     * the declared order, the narrative and the answering provider's label — no token, and
     * now no code either, because there is no longer a code to carry.
     */
    fun startChain() {
        chain = chain.copy(running = true, narrative = "", answeredBy = "")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DriveGitChain.resolve(ctx.applicationContext, session = FleetSession.cookie)
            }
            chain = chain.copy(
                running = false,
                narrative = outcome.narrative(),
                answeredBy = outcome.answeredByLabel.orEmpty(),
            )
            val token = outcome.token?.takeIf { it.isNotBlank() }
            when {
                // #653 THE FLEET ANSWERED AND THERE IS NO TOKEN — the whole point. The
                // listing comes from the proxy, which holds the GitHub credential on its
                // own side, so this branch runs on a phone with ZERO GitHub credential.
                outcome.ok && token == null -> fetchFleetListing(outcome.answeredBy.orEmpty())
                token != null -> {
                    login = login.copy(identity = page.owner, token = token, fromVault = false)
                    fetchListing(token)
                }
            }
        }
    }

    // #684 THE HOSTS THE MISSION BROWSER MAY VISIT for the Cloud sign-in: the portal, the
    // config endpoint whose cookie authorises the fleet, and the rung's own git host. Derived
    // from the declaration, never typed — a host list in Kotlin would be a second source of truth.
    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    fun cloudPortalUrl(): String = SignIn.byKind(SignIn.Kind.AUTHELIA_WEB)?.portalUrl.orEmpty()
    fun cloudAllowHosts(rungId: String): List<String> = listOf(
        hostOf(cloudPortalUrl()), hostOf(ConfigArtifact.endpoint()),
        hostOf(DriveGitChain.rung(rungId)?.config?.optString("repos_url").orEmpty()),
    ).filter { it.isNotBlank() }.distinct()

    // #684 ONE FACTUAL LINE per mission outcome, in the TerminalGit style: not-declared,
    // not-installed, a signer mismatch, no answering activity, or the browser's own refusal.
    // Everything but a refusal announces the in-app fallback the block then shows.
    fun missionWord(outcome: AuthMission.Outcome): String = when (outcome) {
        AuthMission.Outcome.NotDeclared -> ctx.getString(R.string.git_way_mission_not_declared)
        is AuthMission.Outcome.NotInstalled -> ctx.getString(R.string.git_way_mission_not_installed, outcome.pkg)
        is AuthMission.Outcome.NotGranted -> ctx.getString(R.string.git_way_mission_not_granted, outcome.permission)
        is AuthMission.Outcome.NoActivity -> ctx.getString(R.string.git_way_mission_no_activity, outcome.action)
        is AuthMission.Outcome.Refused -> ctx.getString(R.string.git_way_mission_refused, outcome.why)
        AuthMission.Outcome.Sent -> ""
    }

    // #684 THE CLOUD SIGN-IN COMPLETES: the browser earned an Authelia session cookie; it goes
    // to FleetSession (process memory, never a store — the f45bbbe31 posture) and the listing
    // rides it. The same cookie the existing SignInWays fallback delivers through onWebSession.
    fun completeCloud(cookie: String, rungId: String) {
        FleetSession.remember(cookie)
        chain = chain.copy(signedIn = FleetSession.present)
        signingInWay = ""
        fetchFleetListing(rungId)
    }

    // #684 THE MISSION'S RESULT comes back here, read off the DECLARED result keys. A cookie is
    // the Cloud sign-in; the other three are loud.
    val missionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val contract = AuthDeclaration.browserMission
        if (contract == null) { signingInWay = "" } else when (val cap = AuthMission.read(contract, res.resultCode, res.data)) {
            is AuthMission.Capture.Cookie -> completeCloud(cap.value, pendingWay)
            AuthMission.Capture.Cancelled -> { handoff = ctx.getString(R.string.git_way_mission_cancelled); signingInWay = "" }
            is AuthMission.Capture.Refused -> { handoff = ctx.getString(R.string.git_way_mission_refused, cap.why); signingInWay = "" }
            is AuthMission.Capture.Malformed -> { handoff = ctx.getString(R.string.git_way_mission_malformed, cap.why); signingInWay = "" }
        }
    }

    // #689 THE GITHUB CARD IS gh ITSELF. #705 gh runs in the gh ENGINE (Cloud-Lib-Gh.apk, which
    // the Store installs) and the card reaches it through GhEngine; this app no longer carries the
    // binary, so a gh bump no longer republishes Cloud Drive. Where it points and how much it lists
    // are the github rung's declaration. Every failure is ONE loud line naming the next step.
    val ghEngine = remember { GhEngine(ctx.applicationContext) }
    val ghHost = remember { DriveGitChain.ghHost() }
    val ghLimit = remember { DriveGitChain.ghListLimit() }

    /** Every step of the GitHub card, to logcat AND the on-device log (Download/<debug dir>/). */
    fun ghLog(msg: String) = DriveDebugLog.i(ctx, GhEngine.TAG, msg)

    /** Why the gh leg cannot run on this phone at all, or "" when it can. #705: the engine's
     *  handshake first — missing and too old are two different next steps. */
    fun ghUnavailable(): String = when (val engine = ghEngine.check()) {
        is GhEngine.Check.NotInstalled -> ctx.getString(R.string.git_gh_missing, engine.pkg)
        is GhEngine.Check.TooOld -> ctx.getString(R.string.git_gh_engine_old, engine.pkg, engine.found, engine.needed)
        GhEngine.Check.Ready -> if (ghHost.isBlank() || ghLimit <= 0) ctx.getString(R.string.git_gh_undeclared) else ""
    }

    /** #689 `gh repo list`: the account's own repositories, split below by gh's own isPrivate. */
    fun fetchGhListing() {
        listing = listing.copy(loading = true, error = "")
        scope.launch {
            val r = withContext(Dispatchers.IO) { ghEngine.repoList(ghLimit, GitHubRepos.GH_FIELDS) }
            val repos = if (r.ok) GitHubRepos.parseGh(r.output) else null
            ghLog(if (repos != null) "gh repo list: ${repos.count { !it.private }} public, ${repos.count { it.private }} private" else "gh repo list: no listing (exit ${r.exitCode})")
            listing = when {
                !r.ok -> GitListing(loaded = true, error = ctx.getString(R.string.git_gh_list_failed, r.exitCode, GhEngine.why(r.output)))
                repos == null -> GitListing(loaded = true, error = ctx.getString(R.string.git_gh_list_unreadable))
                else -> GitListing(loaded = true, repos = repos, complete = repos.size < ghLimit, viaGh = true)
            }
        }
    }

    /** #689 who gh is signed in as, and its listing when it is; true when signed in. */
    suspend fun ghCheck(): Boolean {
        val why = ghUnavailable()
        if (why.isNotBlank()) { ghAuth = ghAuth.copy(checked = true, signedIn = false, error = why); return false }
        val r = withContext(Dispatchers.IO) { ghEngine.status(ghHost) }
        val st = GitHubRepos.ghStatus(r.output, ghHost)
        ghAuth = when {
            st.signedIn -> ghAuth.copy(checked = true, signedIn = true, login = st.login, error = "")
            // gh holds a login GitHub did not confirm (offline, revoked): say that, not "signed out".
            st.state.isNotBlank() -> ghAuth.copy(checked = true, signedIn = false, login = "", error = ctx.getString(R.string.git_gh_unconfirmed, st.login, st.state))
            // gh could not answer at all (it would not start, or failed before any JSON): its words, not "signed out".
            !r.ok -> ghAuth.copy(checked = true, signedIn = false, login = "", error = ctx.getString(R.string.git_gh_status_failed, r.exitCode, GhEngine.why(r.output), BuildConfig.GH_ENGINE_PACKAGE))
            else -> ghAuth.copy(checked = true, signedIn = false, login = "")
        }
        ghLog(if (st.signedIn) "gh is signed in as ${st.login}" else "gh is not signed in" + ghAuth.error.let { if (it.isBlank()) "" else ": $it" })
        if (st.signedIn) fetchGhListing()
        return st.signedIn
    }

    /**
     * Open the page gh named in the FLEET'S OWN BROWSER (Cloud Browser, the package the declared
     * browser mission names; it answers VIEW for https). Only when it is not on the phone does
     * any browser get it — and the log says which one did. Nothing at all: say so, loudly.
     */
    fun openGhPage(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fleet = AuthDeclaration.browserMission?.pkg.orEmpty()
        runCatching {
            if (fleet.isBlank()) error("no fleet browser is declared")
            ctx.startActivity(Intent(view).setPackage(fleet)); "Cloud Browser ($fleet)"
        }.recoverCatching { why ->
            ghLog("sign-in: Cloud Browser could not open the page (${why.message}); trying any browser")
            ctx.startActivity(view); "the phone's default browser"
        }.onSuccess { ghLog("sign-in: opened $url in $it") }
            .onFailure {
                ghLog("sign-in: nothing on this phone opened $url: ${it.message}")
                ghAuth = ghAuth.copy(error = ctx.getString(R.string.git_gh_no_browser, url))
            }
    }

    /** gh's one-time code onto the clipboard: on arrival, and again from the card's Copy button. */
    fun copyGhCode(code: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.git_gh_code_clip), code))
    }

    /**
     * #689 SIGN IN WITH gh's OWN FLOW: `gh auth login` — gh's device flow against the client id
     * GitHub CLI compiles into its own binary. gh prints a one-time code and the page to enter it
     * at; the card shows the code, copies it and opens that page (only ever on the declared host).
     * gh finishes by itself once the code is approved: exit 0 re-reads who it is signed in as and
     * lists; any other exit is ONE loud line with gh's own last words and the next step.
     */
    fun startGhLogin() {
        val why = ghUnavailable()
        if (why.isNotBlank()) { ghLog("sign-in: cannot start: $why"); ghAuth = ghAuth.copy(checked = true, error = why); return }
        ghLog("sign-in: Sign in with GitHub tapped")
        ghAuth = ghAuth.copy(running = true, code = "", url = "", error = "")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ghEngine.login(ghHost) { prompt, page ->
                    val code = prompt.ifBlank { null }
                    val url = GhEngine.pageOnHost(page, ghHost)
                    if (code != null || url != null) scope.launch {
                        if (code != null && ghAuth.code.isBlank()) {
                            ghAuth = ghAuth.copy(code = code)
                            copyGhCode(code)
                        }
                        if (url != null && ghAuth.url.isBlank()) { ghAuth = ghAuth.copy(url = url); openGhPage(url) }
                    }
                }
            }
            ghAuth = ghAuth.copy(running = false, code = "", url = "")
            if (r.ok) ghCheck()
            else ghAuth = ghAuth.copy(error = ctx.getString(R.string.git_gh_login_failed, GhEngine.why(r.output)))
        }
    }

    /**
     * #689 A ROW gh LISTED CLONES ON THE CREDENTIAL gh HOLDS, in-process, into the ONE store's git
     * folder (#676) with the listing's own https URL as origin — so the scheduler's later fetch and
     * push dial the same URL with the same credential. Not the terminal: neither terminal declares
     * a git credential or carries gh, so its git could not clone a private row. The credential goes
     * from gh's git-credential helper straight into the engine's credential store; nothing shows it.
     */
    fun cloneViaGh(repo: GitHubRepos.Repo) {
        if (repo.cloneUrl.isBlank()) { handoff = ctx.getString(R.string.git_clone_url_missing, repo.name); return }
        scope.launch {
            val cred = withContext(Dispatchers.IO) { if (ghHost.isBlank()) null else ghEngine.credential(ghHost) }
            if (cred == null) { handoff = ctx.getString(R.string.git_gh_clone_no_credential, repo.name); return@launch }
            ghLog("clone ${repo.name}: on gh's credential, into the shared store")
            coordinator.cloneInto(
                name = repo.name,
                url = repo.cloneUrl,
                authKind = GitSyncCoordinator.AUTH_HTTPS,
                username = cred.username,
                token = cred.secret,
            )
        }
    }

    // #684 START ONE WAY'S SIGN-IN, dispatched on the rung's KIND. A fleet_proxy rung opens the
    // portal for a cookie through the fleet browser (AuthMission); when the browser is not on the
    // phone the declared in-app fallback is shown and the handoff line SAYS it is the fallback.
    // #689 the github rung is gh's own `auth login`.
    fun startWay(way: Declarations.GitWayDecl) {
        when (DriveGitChain.rung(way.rung)?.kind) {
            DriveGitChain.RUNG_GITHUB -> startGhLogin()
            DriveGitChain.RUNG_FLEET -> {
                signingInWay = way.id
                pendingWay = way.rung
                cloudFallback = false
                val contract = AuthDeclaration.browserMission
                val req = AuthMission.Request(
                    url = cloudPortalUrl(), title = way.label, allowHosts = cloudAllowHosts(way.rung),
                    capture = AuthMission.CAPTURE_COOKIE, cookieUrl = ConfigArtifact.endpoint(),
                )
                val (intent, outcome) = AuthMission.plan(ctx, contract, req)
                if (intent != null) missionLauncher.launch(intent)
                else { handoff = missionWord(outcome); cloudFallback = true; signingInWay = "" }
            }
        }
    }

    // #629 THE VAULT IS THE ONLY ROAD (#641). The credential the #566 config import delivers already
    // has `repo` scope — it is the token that pushes all day — so the personal section authenticates
    // from it and the account's own repositories list themselves with no tap and no browser. There is
    // no browser login beside it: the device grant that used to be here ran against a GitHub App,
    // whose Device Flow the provider ships OFF, so it could never start.
    LaunchedEffect(Unit) {
        // #689 gh first: when gh itself is signed in, the GitHub card is gh's and lists through gh.
        if (ghCheck()) return@LaunchedEffect
        val vault = withContext(Dispatchers.IO) { DriveAuthApply.vaultGitToken(ctx.applicationContext) }
        if (vault.isNotBlank() && login.token.isBlank()) {
            login = login.copy(identity = page.owner, token = vault, fromVault = true)
            fetchListing(vault)
        }
    }

    // A fleet session earned on Configs ▸ Git (FleetSession is process-wide) lists on the table too, with no second sign-in.
    LaunchedEffect(view, setup) {
        if (view == GitView.ME && !setup && FleetSession.present) {
            page.ways.firstOrNull { DriveGitChain.rung(it.rung)?.kind == DriveGitChain.RUNG_FLEET }?.let { fetchFleetListing(it.rung) }
        }
    }

    /**
     * #1254 a declared bulk depth command. clone_missing: every repository of the REGISTRY list (not pending, not retired) is a
     * candidate, at its declared clone URL with the page's credential; the coordinator drops each one already cloned anywhere
     * on the device. keep_depth: every registered clone.
     */
    fun runDepthAll(a: Declarations.GitDepthActionDecl) {
        when (a.kind) {
            Declarations.DEPTH_CLONE_MISSING -> coordinator.cloneMissing(a, Declarations.repoSot.repos.filter { !it.pending && !it.archived }.map { r ->
                val ssh = cloneSsh && login.sshKeyPath.isNotBlank()
                GitSyncCoordinator.CloneTarget(r.github, page.cloneUrl(r.github, if (ssh) Declarations.REMOTE_SSH else Declarations.REMOTE_HTTPS),
                    if (ssh) GitSyncCoordinator.AUTH_SSH else GitSyncCoordinator.AUTH_HTTPS, login.identity, if (ssh) "" else login.token)
            })
            Declarations.DEPTH_KEEP -> coordinator.keepDepth(a)
            // #1282 one bare mirror per registry repository (an existing mirror is updated, never doubled).
            Declarations.DEPTH_CLONE_BARE -> coordinator.cloneBare(a, Declarations.repoSot.repos.filter { !it.pending && !it.archived }.map { r ->
                GitSyncCoordinator.CloneTarget(r.github, page.cloneUrl(r.github, Declarations.REMOTE_HTTPS), GitSyncCoordinator.AUTH_HTTPS, login.identity, login.token)
            })
        }
    }

    // THE ONE TABLE: every repository of every listing (GitHub, Cloud Gitea, the other hosts, the clones no listing named)
    // is one entry; public and private are not separate groups, the header sorts the lot (default: name, then visibility).
    val allEntries: List<TableEntry> = buildList {
        if (view == GitView.CLOUD) {
            publicRows.forEach { d ->
                add(TableEntry("pub-" + d.name, d.label, d.name, page.owner, false, false, "", tableRow(d.name, page.owner, false, page.cloneUrl(d.name, Declarations.REMOTE_HTTPS)), clonedByName[d.name], { clone(d.name) }, "", page.cloneUrl(d.name, Declarations.REMOTE_HTTPS)))
            }
        } else {
            page.ways.forEach { way ->
                val wayListing = if (DriveGitChain.rung(way.rung)?.kind == DriveGitChain.RUNG_FLEET) cloudListing else listing
                if (wayListing.loaded && wayListing.error.isBlank()) wayListing.repos.forEach { gh ->
                    val owner = gh.owner.ifBlank { page.owner }
                    val cloned = clonedByName[gh.name]
                    add(TableEntry(
                        "own-" + way.id + "-" + gh.owner + "/" + gh.name, gh.name, gh.name, owner, gh.private, gh.fork, way.label, tableRow(gh.name, owner, gh.private, gh.cloneUrl), cloned,
                        {
                            // #684 the row clones from the LEG THAT LISTED IT (#669's rule, per way).
                            if (wayListing.rungId.isNotBlank()) cloneViaFleet(wayListing.rungId, gh)
                            else if (wayListing.viaGh) cloneViaGh(gh)
                            else if (gh.cloneUrl.isBlank()) handoff = ctx.getString(R.string.git_clone_url_missing, gh.name)
                            else clone(gh.name, gh.cloneUrl, gh.sshUrl)
                        },
                        if (gh.private && cloned == null && gh.cloneUrl.isBlank() && wayListing.rungId.isBlank()) ctx.getString(R.string.git_way_private_needs_credential, gh.name) else "",
                        gh.cloneUrl,
                    ))
                }
            }
            Declarations.gitHosts.forEach { host ->
                val hs = hostsState.hosts[host.id] ?: GitHostsState.Host()
                if (hs.signedIn && hs.loaded && hs.error.isBlank()) hs.repos.forEach { hr ->
                    val owner = hr.owner.ifBlank { page.owner }
                    add(TableEntry(
                        "host-" + host.id + "-" + hr.owner + "/" + hr.name, hr.name, hr.name, owner, hr.private, hr.fork, host.label, tableRow(hr.name, hr.owner, hr.private, hr.cloneUrl), clonedByName[hr.name],
                        {
                            scope.launch {
                                val token = withContext(Dispatchers.IO) { hostsState.tokenOf(host.id) }
                                if (hr.cloneUrl.isBlank()) handoff = ctx.getString(R.string.git_clone_url_missing, hr.name)
                                else if (token.isBlank()) handoff = ctx.getString(R.string.git_host_no_token, host.label)
                                else coordinator.cloneInto(name = hr.name, url = hr.cloneUrl, authKind = GitSyncCoordinator.AUTH_HTTPS, username = GitHosts.cloneUser(host, hs.user), token = token)
                            }
                        },
                        "",
                        hr.cloneUrl,
                    ))
                }
            }
            // Any clone the store holds that no listing named: shown, never hidden (the scheduler still syncs it).
            val named = listing.repos.map { it.name }.toSet() + cloudListing.repos.map { it.name }.toSet() + hostsState.hosts.values.flatMap { h -> h.repos.map { it.name } }.toSet()
            // The store can hold the same clone twice (one id, two records): one row each, or LazyColumn throws on the repeated key.
            repos.filter { Declarations.repoSot.canonical(File(it.path).name) !in named }.distinctBy { it.id }.forEach { r ->
                add(TableEntry("other-" + r.id, r.name, r.name, page.owner, null, false, "", tableRow(Declarations.repoSot.canonical(File(r.path).name), page.owner, null), r, {}, ""))
            }
        }
    }.distinctBy { it.key } // a LazyColumn key must be unique: a repo listed twice keeps its first row
    // #1315 the DECLARED filters (predicates as data) and the search leave the rows; archived rows are a filter whose default hides them.
    val factsOf: (TableEntry) -> RepoFilters.Facts = { e ->
        RepoFilters.Facts(e.cloned != null, e.row.status, e.row.lfs, e.row.isPrivate, Declarations.repoSot.hubOf(e.name),
            archived = Declarations.repoSot.isArchived(e.name), level = e.cloned?.let { GitSyncCoordinator.glanceFor(it, glances)?.level }.orEmpty(), name = e.name)
    }
    val entries = RepoFilters.apply(allEntries, page.filters, filterSel, query, factsOf)
    fun countsOf(f: Declarations.GitFilterDecl): Map<String, Int> = RepoFilters.counts(allEntries, page.filters, filterSel, query, f, factsOf)
    val sortedEntries = RepoTable.sort(entries, sortColumn, sortAscending) { it.row }
    val summary = RepoTable.summarize(entries.map { (it.cloned != null) to it.row })

    // #1315 THE TOP BAR's dispatch: every declared item on its `kind` alone (test-drive-git-top-bar.sh diffs the kinds both ways);
    // a scoped verb works on the clones the filters and the search leave; confirm / danger ask first with that count.
    val scopeTargets: List<ManagedRepo> = sortedEntries.mapNotNull { it.cloned }
    val writingJob: String? = jobsState.firstOrNull { !it.finished && it.kind !in CHECK_KINDS }?.label
    val jobLine: com.diegonmarcos.clouddrive.sync.jobs.GitJobs.State? = jobsState.firstOrNull { !it.finished } ?: jobsState.lastOrNull()
    val checkProgress = RepoStatus.progress(repos.map { GitSyncCoordinator.statusKey(it) }.distinct(), glances)
    val summaryLine = GitTopBarState.summary(
        page.topBar.counters, entries.size, entries.count { it.cloned != null }, entries.count { it.row.status?.light == StatusLight.State.OFF },
        entries.count { (it.row.ahead ?: 0) > 0 }, entries.count { (it.row.behind ?: 0) > 0 },
        if (checkProgress.lastCheckMs > 0) RepoStatus.ageWord(System.currentTimeMillis() - checkProgress.lastCheckMs) + " (${checkProgress.done}/${checkProgress.total})" else "", page.topBar::label,
    )
    fun runTopItem(item: Declarations.GitTopItemDecl) {
        expanded = ""
        when (item.kind) {
            "check_light" -> coordinator.lightCheckAll()
            "check_full" -> coordinator.fullCheckAll()
            "check_remote" -> coordinator.refreshRemoteStats(refreshTargets(), login.token)
            "check_sizes" -> coordinator.refreshSizes(force = true)
            "scan_inventory" -> coordinator.scanUnlisted()
            "cancel_checks" -> coordinator.cancelStatusReads()
            "op" -> if (item.op == GitSyncCoordinator.OP_FETCH) coordinator.fetchAll(scopeTargets)
            "compound" -> coordinator.runCompoundAll(item.op, scopeTargets)
            "pull" -> coordinator.pullAll(force = item.force, only = scopeTargets.map { it.id }.toSet())
            "depth" -> page.depthAction(item.action)?.let { a -> if (a.confirm) depthConfirm = a else runDepthAll(a) }
            "open_logs" -> { logsTab = page.topBar.logs.tabs.firstOrNull()?.first.orEmpty(); showActivity = true }
            "open_report" -> showDepthReport = true
            "toggle_filter" -> page.filters.firstOrNull { it.id == item.filter }?.let { f -> coordinator.setFilter(f.id, RepoFilters.toggle(f, filterSel, item.option)) }
            "legend" -> showLegend = true
            // #1356 More > Advanced: the parentless and unlisted folder panels, out of the default view.
            "open_advanced" -> showAdvanced = true
        }
    }
    fun onTopItem(item: Declarations.GitTopItemDecl) { if ((item.confirm || item.danger) && item.kind != "depth") topConfirm = item else runTopItem(item) }

    // The table block: the ONE sticky block (toolbar, job line, search, rail, summary, column header), then the rows.
    fun LazyListScope.tableBlock() {
        // #1195 ORDER: the actions bar (bulk buttons + Settings gear), the summary and the legend come FIRST; the sortable column header
        // is the sticky item right below them and stays pinned while the rows scroll.
        // #1315 ONE STICKY BLOCK: the declared toolbar, the job line, the search + Filters, the quick-filter rail, the summary and the column header.
        stickyHeader(key = "table-top") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                GitTopBar(page.topBar, prefs, enabledReason = { item -> GitTopBarState.reason(item, writingJob, scopeTargets.size, page.topBar::label) }, onItem = ::onTopItem)
                GitJobLine(jobLine, page.topBar::label, onCancel = { jobLine?.let { coordinator.jobs.cancel(it.id) } }, onDismiss = { jobLine?.let { coordinator.jobs.dismiss(it.id) } },
                    onOpen = { if (depthRun != null) showDepthReport = true else { logsTab = "app"; showActivity = true } })
                fullProgress?.let { p -> Text(stringResource(R.string.repo_full_progress, p.done, p.total), Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset).testTag(DriveTags.SYNC_GIT_FULL_PROGRESS), style = MaterialTheme.typography.labelSmall) }
                GitSearchRow(page.topBar, query, { query = it }, RepoFilters.active(page.filters, filterSel), onFilters = { showFilters = true }, onClear = { coordinator.clearFilters(); query = "" })
                QuickFilterRail(page.filters, filterSel, ::countsOf) { f, o -> coordinator.setFilter(f.id, RepoFilters.toggle(f, filterSel, o)) }
                Text(summaryLine, Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_SUMMARY), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // #642 what the last clone handoff did, right under the bar that can trigger it: no press of Clone is silent.
                if (handoff.isNotBlank()) Text(
                    handoff,
                    Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_HANDOFF).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RepoTableHeader(sortColumn, sortAscending, tableScroll) { c ->
                    val next = RepoTable.tap(c, sortColumn, sortAscending)
                    coordinator.setSort(next.first, next.second)
                    expanded = ""
                }
            }
        }
        items(sortedEntries, key = { it.key }) { e ->
            val cloned = e.cloned
            GitRepoRow(
                name = e.label, repoName = e.name, cells = e.row, scroll = tableScroll, fork = e.fork, copies = inventoryState?.cloneOf(e.name)?.copies ?: 1, copiesLabel = page.inventory.label("copies_badge"),
                repo = cloned, glance = cloned?.let { GitSyncCoordinator.glanceFor(it, glances) }, running = cloned?.let { running[it.id] },
                icons = RepoStatus.icons(cloned != null, cloned?.let { GitSyncCoordinator.glanceFor(it, glances) }, cloned?.let { running.containsKey(it.id) } == true, events, cloned?.id.orEmpty()),
                cloning = e.name in cloning, expanded = expanded == e.key,
                // A second tap on the same repo closes its menu; a tap on another repo moves it.
                onToggle = { expanded = RepoTable.toggle(expanded, e.key) },
                onOpenStatus = { statusFor = e.key },
            )
            if (e.note.isNotBlank()) Text(
                e.note, Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.labelSmall, color = colorResource(R.color.status_light_off),
            )
            Hairline()
        }
    }

    GitPageFrame(
        state = listState,
        refreshing = refreshingRemote,
        onRefresh = { coordinator.refresh(force = true); coordinator.refreshRemoteStats(refreshTargets(), login.token); coordinator.scanUnlisted(); coordinator.refreshLastCommits() },
        modifier = modifier,
    ) {
        // #642 WHAT THE HANDOFF DID, at the top of the page, above every row that could have
        // triggered it. Shown for every outcome including success (which names the folder the
        // clone lands in, the one Files already lists) so no press of Clone is ever silent.
        if (setup && handoff.isNotBlank()) item {
            Text(
                handoff,
                Modifier.fillMaxWidth()
                    .testTag(DriveTags.SYNC_GIT_HANDOFF)
                    .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (setup && seedTally.isNotBlank() && !seedTally.endsWith("complete")) item {
            Text(
                seedTally,
                Modifier.fillMaxWidth()
                    .testTag(DriveTags.SYNC_GIT_SEED)
                    .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (seedTally.contains("STOPPED")) TextButton(onClick = { StoreSeedWorker.kick(ctx.applicationContext) }, modifier = Modifier.padding(horizontal = DriveMetrics.sectionInset)) {
                Text(stringResource(R.string.git_seed_retry))
            }
        }
        if (setup) item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Commit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(DriveMetrics.pad))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_store) + " · " + root, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.sync_repos_count, repos.size) + " · " +
                            stringResource(R.string.sync_schedule, BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) R.string.sync_network_unmetered else R.string.sync_network_any)) + " · " +
                            (nextRunMinutes?.let { stringResource(R.string.sync_next_run, it) } ?: stringResource(R.string.sync_next_run_unknown)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    )
                }
                Pill(stringResource(R.string.sync_add_repo), { actions.openEngine(EngineActivity.ENGINE_GIT, root) }, filled = true)
            }
        }
        // #850 THE PAGE'S PULL ACTIONS: force pull every clone (asks first — it is the
        // declared destructive verb), and the persisted Auto pull on open (Wi-Fi only).
        if (setup) item {
            Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_PULL_ALL).padding(horizontal = DriveMetrics.sectionInset)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.git_force_pull_all), { confirmPullAll = true }, icon = Icons.Filled.Sync, enabled = bulk?.finished != false && repos.isNotEmpty())
                    Spacer(Modifier.width(DriveMetrics.pad))
                    Pill(stringResource(R.string.git_pull_all), { coordinator.pullAll(force = false) }, enabled = bulk?.finished != false && repos.isNotEmpty())
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.git_auto_pull_on_open), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(DriveMetrics.gap))
                    Switch(checked = autoPull, onCheckedChange = { coordinator.setAutoPullOnOpen(it) }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_AUTO_PULL))
                }
                if (page.autoPull.requireUnmetered) Text(stringResource(R.string.git_auto_pull_wifi_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (autoPullNote) {
                    GitSyncCoordinator.AUTO_PULL_METERED -> Text(stringResource(R.string.git_auto_pull_skipped_metered), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GitSyncCoordinator.AUTO_PULL_BUSY -> Text(stringResource(R.string.git_auto_pull_skipped_busy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                bulk?.let { b ->
                    if (!b.finished) {
                        val total = if (b.total == Int.MAX_VALUE) 0 else b.total
                        Text(stringResource(R.string.git_pull_all_progress, b.done, total), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(progress = { if (total == 0) 0f else b.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.git_pull_all_done, b.total - b.failed, b.failed), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    b.results.toSortedMap().forEach { (name, r) ->
                        Text(
                            "$name: " + r.summary,
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else colorResource(R.color.status_light_off),
                        )
                    }
                }
            }
        }

        // #1149 Cloud's one-line state: the live list's count, or why the declared set stands in.
        if (view == GitView.CLOUD) item {
            Text(
                when {
                    cloudPublic.loading || !cloudPublic.loaded -> stringResource(R.string.git_cloud_loading)
                    cloudPublic.error.isNotBlank() -> stringResource(R.string.git_cloud_failed, cloudPublic.error)
                    else -> stringResource(R.string.git_cloud_count, Declarations.cloudList.owner, Declarations.cloudList.namePrefix, cloudPublic.repos.size)
                },
                Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // THE TWO DECLARED SECTIONS, in declared order. The dispatch on a section id is the
        // ONE place Kotlin names them; test-drive-git-page.sh diffs it both ways.
        // #1149 Me draws the personal section, Cloud the public one.
        page.sections.filter { (it.id == Declarations.GIT_SECTION_PUBLIC) == (view == GitView.CLOUD) }.forEach { section ->
            when (section.id) {
                "public" -> {
                    item {
                        SectionHeader(section.label, count = publicRows.size)
                    }
                    tableBlock()
                }
                "personal" -> {
                    // #684 TWO PARALLEL, ALWAYS-VISIBLE WAY-CARDS — GitHub and Cloud git — in
                    // declared order (ui.sync.git.ways). Each states its OWN status, hosts its OWN
                    // sign-in and lists its OWN repositories below it; neither gates or hides the
                    // other, and both can be signed in and listing at once. A way whose declared
                    // rung is missing renders one line saying so, never a placeholder card (#648).
                    if (setup) item { SectionHeader(section.label) }
                    if (setup) page.ways.forEach { way ->
                        val rungKind = DriveGitChain.rung(way.rung)?.kind
                        val isCloud = rungKind == DriveGitChain.RUNG_FLEET
                        val wayListing = if (isCloud) cloudListing else listing
                        item {
                            GitWayCard(
                                way = way, isCloud = isCloud, page = page,
                                login = login, listing = wayListing, chain = chain,
                                ghAuth = ghAuth,
                                signingIn = if (isCloud) signingInWay == way.id else ghAuth.running,
                                rungDeclared = rungKind != null,
                                cloudFallback = cloudFallback && isCloud,
                                fleetHost = fleetHost,
                                onSignIn = { startWay(way) },
                                onOpenGhPage = { openGhPage(it) },
                                onCopyGhCode = { copyGhCode(it) },
                                onRetry = {
                                    if (isCloud) { if (FleetSession.present) fetchFleetListing(way.rung) }
                                    else if (ghAuth.signedIn) fetchGhListing()
                                    else if (login.token.isNotBlank()) fetchListing(login.token)
                                },
                                onStartChain = { startChain() },
                                onSshKeyPath = { login = login.copy(sshKeyPath = it) },
                                onUseSsh = { login = login.copy(ssh = it) },
                            )
                        }
                    }
                    // #1149 THE OTHER HOSTS (GitLab, Bitbucket, Codeberg/Gitea, Azure DevOps): a token card each, and once
                    // signed in the account's repositories in the same sortable table, Clone when no local folder matches.
                    if (setup) Declarations.gitHosts.forEach { host ->
                        val hs = hostsState.hosts[host.id] ?: GitHostsState.Host()
                        item(key = "host-card-" + host.id) {
                            GitHostCard(
                                decl = host, state = hs,
                                onSignIn = { u, t -> hostsState.signIn(host, u, t) },
                                onSignOut = { hostsState.signOut(host) },
                                onRefresh = { hostsState.refresh(host) },
                                onOpenTokenPage = { actions.openUrl(host.tokenUrl) },
                            )
                        }
                    }
                    // #1149 ONE TABLE for every listing above: the sticky header, the bulk bar, the rows.
                    if (!setup) tableBlock()
                    // #1356 the parentless and unlisted folder panels live in More > Advanced (a simple default view).
                    // #1315 THE ONE INVENTORY's other sections: duplicate clones, bare mirrors, leftover folders (drawn only when they have rows).
                    if (!setup) item(key = "inventory") { InventorySections(inventoryState, page.inventory, glances, lastCommits, depthRun, coordinator, onOpenReport = { showDepthReport = true }) }
                }
                else -> item { EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "") }
            }
        }

        if (view == GitView.ME && !setup && repos.isEmpty()) item { EmptyState(Icons.Filled.Commit, stringResource(R.string.sync_empty_title), stringResource(R.string.sync_empty_hint)) }

    }

    // #1238 the full-screen status window of the tapped row: what the app already knows, no mock data; Clone repo is its last section.
    entries.firstOrNull { it.key == statusFor }?.let { e ->
        RepoStatusWindow(
            name = e.name, owner = e.owner, row = e.row, repo = e.cloned, glance = e.cloned?.let { GitSyncCoordinator.glanceFor(it, glances) },
            icons = RepoStatus.icons(e.cloned != null, e.cloned?.let { GitSyncCoordinator.glanceFor(it, glances) }, e.cloned?.let { running.containsKey(it.id) } == true, events, e.cloned?.id.orEmpty()),
            cloneUrl = e.cloneUrl.ifBlank { page.cloneUrl(e.name, Declarations.REMOTE_HTTPS) }, remote = remoteStats[RepoRemoteStats.keyOf(e.owner, e.name)],
            cloning = e.name in cloning, fmt = fmt, onClone = e.onClone, depthOutcome = depthRun?.outcomeOf(e.name), onCheckWorktree = { e.cloned?.let { coordinator.checkWorktree(it) } }, onClose = { statusFor = "" },
        )
    }
    // #1315 THE DETAIL IS A SHEET: a tap on a row opens its actions over the table (the list never changes height); a second tap or a swipe closes it.
    sortedEntries.firstOrNull { it.key == expanded }?.let { e ->
        ModalBottomSheet(onDismissRequest = { expanded = "" }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_DETAIL_SHEET)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = DriveMetrics.padWide)) {
            if (e.cloned == null) {
                GitRepoNotClonedBox(
                    page = page, repoName = e.name, owner = e.owner, row = e.row, isPrivate = e.isPrivate,
                    remote = remoteStats[RepoRemoteStats.keyOf(e.owner, e.name)], cloneUrl = e.cloneUrl,
                    cloning = e.name in cloning, coordinator = coordinator, actions = actions,
                    onClone = e.onClone, onClose = { expanded = "" },
                )
            }
            if (e.cloned != null) {
                GitRepoOpsBox(
                    repo = e.cloned, page = page, details = details[e.cloned.id], result = opResults[e.cloned.id],
                    running = running[e.cloned.id], glance = GitSyncCoordinator.glanceFor(e.cloned, glances), fmt = fmt,
                    coordinator = coordinator, actions = actions,
                    repoName = e.name, owner = e.owner, remote = remoteStats[RepoRemoteStats.keyOf(e.owner, e.name)],
                    initialPanel = if (panelRepo == e.cloned.id) panel else "", onPanel = { id -> panelRepo = e.cloned.id; panel = id },
                    cloneDepth = cloneDepth, cloneSsh = cloneSsh, onSettings = { settingsFor = e.cloned.id }, onClose = { expanded = "" },
                    row = e.row, isPrivate = e.isPrivate, nextRunMinutes = nextRunMinutes,
                    repoint = { from, to -> prefs?.let { p -> LocalRepoRefs.repoint(ctx.applicationContext, p, from, to).let { r -> ctx.getString(R.string.parentless_refs, r.repos, r.jobs, r.bookmarks) } }.orEmpty() },
                )
            }
            }
        }
    }
    repos.firstOrNull { settingsFor.isNotEmpty() && it.id == settingsFor }?.let { repo -> RepoSettingsSheet(repo, coordinator, onDismiss = { settingsFor = "" }) }
    // #1258 ACTIVITY LOGS: the one sync / operation history, opened from the top button, full screen.
    // #1356 LOGS (Repos | App): App is the Activity Logs view that used to open from More.
    if (showActivity) GitLogsWindow(page.topBar.logs, logsTab, coordinator, events, fmt, onClose = { showActivity = false })
    if (showDepthReport) depthRun?.let { r -> DepthReportWindow(r, onCancel = { coordinator.cancelDepthRun() }, onClose = { showDepthReport = false }) }
    topConfirm?.let { item -> GitTopConfirm(item, page.topBar, scopeTargets.size, onConfirm = { runTopItem(item); topConfirm = null }, onDismiss = { topConfirm = null }, cancelLabel = stringResource(R.string.chrome_cancel)) }
    if (showFilters) GitFilterSheet(page.topBar, page.filters, filterSel, ::countsOf, onPick = { f, o -> coordinator.setFilter(f.id, RepoFilters.toggle(f, filterSel, o)) }, onClear = { coordinator.clearFilters(); query = "" }, onDismiss = { showFilters = false })
    // #1356 MORE > ADVANCED: the local repos parentless and the unlisted git folders, in one sheet over the page.
    if (showAdvanced) ModalBottomSheet(onDismissRequest = { showAdvanced = false }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_ADVANCED)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = DriveMetrics.padWide)) {
            page.topBar.groups.flatMap { it.items }.firstOrNull { it.kind == "open_advanced" }?.let { adv ->
                Text(adv.label, Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), style = MaterialTheme.typography.titleMedium)
            }
            if (prefs != null) ParentlessPanel(prefs, coordinator, glances, lastCommits, depthRun, onOpenReport = { showDepthReport = true }) { coordinator.refresh(force = true) }
            UnlistedPanel(page.unlisted.label, unlistedFolders, glances, lastCommits, depthRun, coordinator, onOpenReport = { showDepthReport = true })
        }
    }
    if (showLegend) GitLegendDialog(page.topBar, page.status.icons, { showLegend = false }, stringResource(R.string.repo_status_window_close))
    depthConfirm?.let { a ->
        AlertDialog(
            onDismissRequest = { depthConfirm = null },
            title = { Text(a.label) },
            text = { Text(if (a.kind == Declarations.DEPTH_CLONE_BARE) stringResource(R.string.repo_bare_confirm, Declarations.sync.git.bareRoot) else stringResource(if (a.kind == Declarations.DEPTH_KEEP) R.string.repo_depth_confirm_keep else R.string.repo_depth_confirm_clone, a.depth)) },
            confirmButton = { TextButton(onClick = { runDepthAll(a); depthConfirm = null }) { Text(a.label) } },
            dismissButton = { TextButton(onClick = { depthConfirm = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
    if (confirmPullAll) {
        AlertDialog(
            onDismissRequest = { confirmPullAll = false },
            title = { Text(stringResource(R.string.git_force_pull_all)) },
            text = { Text(stringResource(R.string.git_confirm_force_pull_all, repos.size)) },
            confirmButton = { TextButton(onClick = { coordinator.pullAll(force = true); confirmPullAll = false }) { Text(stringResource(R.string.git_force_pull_all)) } },
            dismissButton = { TextButton(onClick = { confirmPullAll = false }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

/**
 * #689 what gh ITSELF says about this phone: whether it has been asked yet, who it is signed in
 * as, the one-time code its own `auth login` waits on (and the page to enter it at), and the ONE
 * loud line when the gh leg failed. No credential lives here — gh keeps its own.
 */
private data class GhAuth(
    val checked: Boolean = false,
    val signedIn: Boolean = false,
    val login: String = "",
    val running: Boolean = false,
    val code: String = "",
    val url: String = "",
    val error: String = "",
)

/**
 * What the personal section is authenticated as, for the process only: never stored here, never
 * logged. #629 [fromVault] says the token came from the VAULT-DELIVERED config import (through
 * [DriveAuthApply.vaultGitToken], out of libs:git-sync's own keystore store) rather than from a
 * browser device grant — which is the difference between the supported path and the fallback.
 */
private data class GitLogin(
    val identity: String = "",
    val token: String = "",
    val ssh: Boolean = false,
    val sshKeyPath: String = "",
    val fromVault: Boolean = false,
) {
    val signedIn: Boolean get() = token.isNotBlank() || (ssh && sshKeyPath.isNotBlank())
}

/**
 * #646 THE DECLARED CREDENTIAL CHAIN, as the page sees it. [order] is built from the
 * declaration so the page states the ranking it will actually follow; [narrative] is the
 * chain's account of what happened, every skipped rung included.
 *
 * NO TOKEN LIVES HERE, AND #653 NO CODE EITHER. This state used to carry a `code` and a
 * `prompt` — the device grant's short user code and the URL to type it at. Both fields are
 * deleted with the flow that filled them: there is nothing for the owner to read off this
 * screen and retype anywhere. What is left describes the chain, never a secret.
 */
private data class GitChainState(
    val running: Boolean = false,
    val order: String = "",
    val narrative: String = "",
    val answeredBy: String = "",
    /** #653 a fleet browser login has landed in THIS process (memory only, never stored). */
    val signedIn: Boolean = false,
)

/**
 * The authenticated listing, or why there is none. #669 [rungId] is the declared id of
 * the FLEET rung that served it — blank when the listing came from GitHub with the vault
 * token. It is what routes a row's clone back to the leg that listed it: a fleet-listed
 * repository clones in-process with the fleet session on the rung's declared clone URL,
 * because the terminal's git cannot present an Authelia session cookie.
 */
private data class GitListing(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val repos: List<GitHubRepos.Repo> = emptyList(),
    val complete: Boolean = true,
    val error: String = "",
    val rungId: String = "",
    /** #689 gh listed it, so a row clones on the credential gh holds (cloneViaGh). */
    val viaGh: Boolean = false,
)

/**
 * #684 ONE OF THE TWO PARALLEL WAY-CARDS. GitHub and Cloud git each render this: a prominent
 * DriveCard with a big "Sign in with <way>" button, a status light and a one-line state, its own
 * repository listing state below, and the chain narrative moved BEHIND a details disclosure. The
 * card dispatches on [isCloud] (the way's rung kind), never on a name typed here.
 *
 * WHAT #641 STILL FORBIDS holds: no libs:auth GitHub provider and no fleet-owned device grant.
 * #689 the GitHub card's sign-in is gh's OWN `auth login` (gh's flow, gh's client id; the card
 * shows gh's one-time code and opens the page gh names); the Cloud card's is the fleet browser
 * mission, whose in-app fallback is libs:auth's own SignInWays scoped to the DECLARED
 * session_provider — the one libs:auth sign-in surface this page may host.
 */
@Composable
private fun GitWayCard(
    way: Declarations.GitWayDecl,
    isCloud: Boolean,
    page: Declarations.GitPageDecl,
    login: GitLogin,
    listing: GitListing,
    chain: GitChainState,
    ghAuth: GhAuth,
    signingIn: Boolean,
    rungDeclared: Boolean,
    cloudFallback: Boolean,
    fleetHost: SignInHost,
    onSignIn: () -> Unit,
    onOpenGhPage: (String) -> Unit,
    onCopyGhCode: (String) -> Unit,
    onRetry: () -> Unit,
    onStartChain: () -> Unit,
    onSshKeyPath: (String) -> Unit,
    onUseSsh: (Boolean) -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    // #689 the GitHub card is signed in when gh itself is, or when the #629 vault credential is here.
    val signedIn = if (isCloud) chain.signedIn else ghAuth.signedIn || login.token.isNotBlank()
    val identity = if (isCloud) stringResource(R.string.git_way_cloud_identity) else ghAuth.login.ifBlank { login.identity.ifBlank { page.owner } }
    // Only an undeclared rung disables the button: the GitHub way has no client of its own to lack.
    val disabled = !rungDeclared
    DriveCard(
        way.label,
        light = if (signedIn) StatusLight.State.ON else StatusLight.State.UNKNOWN,
        summary = when {
            signingIn -> stringResource(if (isCloud) R.string.git_way_signing_in else R.string.git_gh_waiting)
            // #689 gh's own sign-in speaks first on the GitHub card: it IS the GitHub leg.
            !isCloud && ghAuth.signedIn && listing.loaded -> stringResource(R.string.git_way_repos, identity, listing.repos.size)
            !isCloud && ghAuth.signedIn -> stringResource(R.string.git_way_signed_in, identity)
            // #629 the vault-delivered credential state, read at a glance (kept on the GitHub card).
            !isCloud && login.fromVault -> stringResource(R.string.git_login_from_vault, login.identity)
            signedIn && listing.loaded -> stringResource(R.string.git_way_repos, identity, listing.repos.size)
            signedIn -> stringResource(R.string.git_way_signed_in, identity)
            !isCloud && ghAuth.checked -> stringResource(R.string.git_gh_not_signed_in)
            else -> stringResource(R.string.git_way_signed_out)
        },
        tag = DriveTags.SYNC_GIT_WAY,
    ) {
        PillRow {
            Pill(
                stringResource(R.string.git_way_signin, way.label),
                onSignIn,
                filled = true,
                enabled = !signingIn && !disabled,
                modifier = Modifier.testTag(DriveTags.SYNC_GIT_WAY_SIGNIN),
            )
            Pill(stringResource(if (detailsOpen) R.string.git_way_details_hide else R.string.git_way_details), { detailsOpen = !detailsOpen })
        }
        // The ONE bold line that names the next step when the way cannot start.
        if (!rungDeclared) Text(
            stringResource(R.string.git_way_rung_undeclared, way.rung),
            Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_STATE),
            style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off), fontWeight = FontWeight.SemiBold,
        )
        // #689 a failed gh leg says so, in gh's own words where it has them, with the next step.
        else if (!isCloud && ghAuth.error.isNotBlank()) Text(
            ghAuth.error,
            Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_STATE),
            style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off), fontWeight = FontWeight.SemiBold,
        )
        // #689 gh's one-time code while its own `auth login` waits on it: copied already, and the
        // page gh named is one tap away (it was opened once, automatically).
        if (!isCloud && ghAuth.code.isNotBlank()) {
            Text(
                stringResource(R.string.git_gh_code, ghAuth.code),
                Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_GH_CODE),
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            )
            PillRow {
                Pill(stringResource(R.string.git_gh_copy), { onCopyGhCode(ghAuth.code) }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_GH_COPY))
                if (ghAuth.url.isNotBlank()) Pill(stringResource(R.string.git_gh_open, ghAuth.url), { onOpenGhPage(ghAuth.url) })
            }
        }

        if (detailsOpen) Column(Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_DETAILS)) {
            if (isCloud) {
                // The declared credential chain, described BEHIND the disclosure (#684): the order,
                // the automatic "Get a credential" walk, and — when the browser is absent — the
                // declared in-app fallback sign-in, scoped to the fleet rung's session_provider.
                Text(stringResource(R.string.git_chain_order, chain.order), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PillRow {
                    Pill(stringResource(if (chain.running) R.string.git_chain_running else R.string.git_chain_start), onStartChain, filled = !chain.running)
                }
                if (cloudFallback) SignInWays(
                    host = fleetHost,
                    policy = listOf(FleetGit.sessionProvider()),
                    modifier = Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_FLEET_LOGIN),
                    pill = { label, tag, onClick -> Pill(label, onClick, modifier = Modifier.testTag(tag)) },
                )
                if (chain.narrative.isNotBlank()) Text(
                    chain.narrative, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall,
                    color = if (chain.answeredBy.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else colorResource(R.color.status_light_off),
                )
                if (chain.answeredBy.isNotBlank()) Text(
                    stringResource(R.string.git_chain_answered, chain.answeredBy),
                    Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
            } else {
                // #629 the GitHub card states which of its two credential states it is in.
                // gh signed in IS a git credential on this phone (the clone asks gh for it), so the
                // card never says "no credential" beside a signed-in gh.
                Text(
                    if (ghAuth.signedIn) stringResource(R.string.git_gh_credential_note, ghAuth.login)
                    else stringResource(if (login.fromVault) R.string.git_login_vault_note else R.string.git_login_vault_absent),
                    Modifier.testTag(DriveTags.SYNC_GIT_VAULT_NOTE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val ssh = page.sshWay
                if (ssh != null) {
                    PillRow { Pill(ssh.label, { onUseSsh(!login.ssh) }, icon = IconCatalog.vectorOrDefault(ssh.icon), filled = login.ssh) }
                    if (login.ssh) {
                        OutlinedTextField(
                            login.sshKeyPath, onSshKeyPath,
                            label = { Text(stringResource(R.string.sync_auth_key_path)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide),
                        )
                        Text(stringResource(R.string.git_login_ssh_cannot_list), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        when {
            listing.loading -> Text(stringResource(R.string.git_listing_loading), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
            listing.error.isNotBlank() -> {
                Text(stringResource(R.string.git_listing_failed, AuthDeclaration.explain(listing.error)), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off))
                PillRow { Pill(stringResource(R.string.chrome_retry), onRetry) }
            }
            listing.loaded && !listing.complete && listing.viaGh -> Text(stringResource(R.string.git_gh_list_truncated, listing.repos.size), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.loaded && !listing.complete -> Text(stringResource(R.string.git_listing_truncated, page.api.maxPages), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.loaded && listing.repos.isEmpty() -> Text(stringResource(R.string.git_listing_empty), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * ONE dense row. Single line where it fits: the light, the name, the private/fork badges,
 * the inline monospace glance (branch ↑ahead ↓behind clean|N changed) and either the clone
 * state or the disclosure. No card, no per-repository box — that is what expanding does.
 */
@Composable
private fun GitRepoRow(
    name: String,
    repoName: String,
    cells: RepoTableRow,
    scroll: ScrollState,
    fork: Boolean,
    copies: Int = 1,
    copiesLabel: String = "",
    repo: ManagedRepo?,
    glance: GitSyncCoordinator.Glance?,
    running: GitSyncCoordinator.Running?,
    icons: RepoStatus.Icons,
    cloning: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    // #1138 THE TABLE ROW: the light and the name, then one cell per column, then the clone / disclosure
    // control; the whole row scrolls sideways on the ONE scroll state the header shares.
    Row(
        Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_ROW)
            .heightIn(min = DriveMetrics.rowHeight)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // #1356 Status and Name are pinned; the fact columns scroll sideways under them on the ONE scroll state the header shares.
        Row(Modifier.padding(start = DriveMetrics.sectionInset, top = DriveMetrics.gap, bottom = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
            // #1238 the leftmost column is the status icon on its own: a tap opens the full-screen status window.
            RepoStatusCell(icons, name, onOpenStatus, RepoStatus.levelCaption(glance, System.currentTimeMillis()))
            Column(Modifier.width(DriveMetrics.tableName)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (fork) CapsuleBadge(stringResource(R.string.git_fork))
                    // #1315 ONE CLONE PER REPOSITORY: a second folder holding it is a DUPLICATE (the Duplicate clones section offers Archive / Delete on the extra copies only).
                    if (copies > 1) CapsuleBadge("$copies$copiesLabel")
                }
                // What the cells cannot say: the clone folder is gone, a conflict, or an unfinished merge / rebase.
                // #1238 no "GitHub" (the listing's label) and no "Not Cloned" text under a repo that is not cloned: the status window says it.
                if (repo != null && glance != null && glance.gone) Text(stringResource(R.string.sync_glance_gone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (repo != null && (glance == null || !glance.read)) Text(stringResource(R.string.sync_glance_unchecked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (repo != null && glance != null && glance.error != null) Text(
                    statusErrorText(RepoStatus.errorKind(glance.error), glance.error), style = MaterialTheme.typography.labelSmall,
                    color = colorResource(R.color.status_light_off), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                else if (repo != null && glance != null && glance.read) {
                    if (glance.conflicts > 0) Text(stringResource(R.string.sync_conflicts, glance.conflicts), style = MaterialTheme.typography.labelSmall, color = colorResource(R.color.status_light_off))
                    if (glance.repositoryState != "SAFE") Text(glance.repositoryState, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.weight(1f).scrollFadeEnd(scroll.canScrollForward).horizontalScroll(scroll).padding(end = DriveMetrics.sectionInset, top = DriveMetrics.gap, bottom = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
            RepoCells(cells)
            Box(Modifier.width(DriveMetrics.tableName), contentAlignment = Alignment.CenterEnd) {
                when {
                    cloning -> Text(stringResource(R.string.git_cloning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // #1238 the Clone repo button left the row: it is the last section of the status window.
                    else -> Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.git_collapse else R.string.git_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset))
}

/** The light of one row: the REAL status (RepoStatus.light), "?" only when nothing can be said, and then the row says why. */
private fun lightOf(repo: ManagedRepo?, glance: GitSyncCoordinator.Glance?): StatusLight.State = RepoStatus.light(RepoStatus.kindOf(repo != null, glance))

/** One human sentence for a probe failure (RepoStatus.errorKind picks the sentence, the raw reason rides along as detail). */
@Composable
internal fun statusErrorText(kind: RepoStatus.ErrorKind?, detail: String?): String {
    val d = detail.orEmpty()
    return when (kind) {
        RepoStatus.ErrorKind.TIMEOUT -> stringResource(R.string.repo_err_timeout, d)
        RepoStatus.ErrorKind.NOT_A_REPOSITORY -> stringResource(R.string.repo_err_not_repo, d)
        RepoStatus.ErrorKind.FILES_ACCESS -> stringResource(R.string.repo_err_files_access, androidx.compose.ui.platform.LocalContext.current.let { remember(it) { EngineGrant.label(it) } }, d)
        RepoStatus.ErrorKind.PERMISSION -> stringResource(R.string.repo_err_permission, d)
        RepoStatus.ErrorKind.AUTH -> stringResource(R.string.repo_err_auth, d)
        RepoStatus.ErrorKind.ENGINE -> stringResource(R.string.repo_err_engine, d)
        RepoStatus.ErrorKind.OTHER -> stringResource(R.string.repo_err_other, d)
        null -> stringResource(R.string.repo_status_none)
    }
}

/** The STATUS block at the top of the open repository panel: cloned, branch, dirty, ahead/behind, not pushed, last fetch, last error. */
@Composable
internal fun RepoStatusBlock(b: RepoStatus.Block, fmt: DateFormat) {
    val dash = RepoTable.DASH
    Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide).testTag(DriveTags.SYNC_GIT_STATUS_BLOCK)) {
        Text(stringResource(R.string.repo_status_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetaLine(stringResource(R.string.repo_status_cloned, stringResource(if (b.cloned) R.string.manage_yes else R.string.manage_no)))
        MetaLine(stringResource(R.string.repo_status_branch, b.branch ?: dash, b.upstream ?: stringResource(R.string.git_meta_no_upstream)))
        MetaLine(
            if (b.dirty == null) stringResource(if (b.level == "light") R.string.repo_status_dirty_light else R.string.repo_status_dirty_unknown)
            else stringResource(R.string.repo_status_dirty, b.dirty!!, b.modified ?: 0, b.untracked ?: 0, b.conflicts ?: 0),
        )
        if (b.level != null && b.readAtMs != null) MetaLine(stringResource(if (b.level == "light") R.string.repo_status_level_light else R.string.repo_status_level_full, RepoStatus.ageWord(System.currentTimeMillis() - b.readAtMs)))
        MetaLine(stringResource(R.string.repo_status_ab, b.ahead?.toString() ?: dash, b.behind?.toString() ?: dash))
        MetaLine(stringResource(R.string.repo_status_unpushed, b.unpushed?.toString() ?: dash))
        MetaLine(stringResource(R.string.repo_status_fetch, b.lastFetchMs?.let { fmt.format(Date(it)) } ?: stringResource(R.string.repo_status_never)))
        MetaLine(stringResource(R.string.repo_status_error, if (b.error == null) stringResource(R.string.repo_status_none) else statusErrorText(b.error, b.errorDetail)))
    }
}

/**
 * THE per-repository operations box, the row's disclosure. Every DECLARED operation is a
 * pill in declared order; the dispatch on an operation id is the ONE place Kotlin names
 * them and test-drive-git-page.sh diffs it against ui.sync.git.ops in both directions.
 * The six transport/commit verbs go to the coordinator (GitEngine); the eight reads open a
 * panel below. A destructive operation opens a confirmation first, never runs on the tap.
 */
@Composable
private fun GitRepoOpsBox(
    repo: ManagedRepo,
    page: Declarations.GitPageDecl,
    details: GitSyncCoordinator.Details?,
    result: com.diegonmarcos.cloudlib.gitsync.GitOpResult?,
    running: GitSyncCoordinator.Running?,
    glance: GitSyncCoordinator.Glance?,
    fmt: DateFormat,
    coordinator: GitSyncCoordinator,
    actions: DriveActions,
    repoName: String,
    owner: String,
    remote: RepoRemoteStats.Stats?,
    cloneDepth: Int,
    cloneSsh: Boolean,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    row: RepoTableRow,
    /** #1356 the sub-menu this sheet reopens on, and where a change of sub-menu is reported (the page keeps it across navigation). */
    initialPanel: String = "",
    onPanel: (String) -> Unit = {},
    isPrivate: Boolean?,
    /** #1289 minutes until the periodic worker next ticks (null = not known); Configs-AutoSync's next-run line. */
    nextRunMinutes: Long? = null,
    /** #1288/#1289 Others > Rename re-points the registry, jobs and bookmarks at the moved folder. */
    repoint: (File, File) -> String = { _, _ -> "" },
) {
    val ctx = LocalContext.current
    val off = colorResource(R.color.status_light_off)
    var panel by remember(repo.id) { mutableStateOf(initialPanel) }
    LaunchedEffect(repo.id, panel) { onPanel(panel) }
    // #1289 Workflow: the working tree read when a Workflow panel opens, the per-file choices, the confirms.
    var tree by remember(repo.id) { mutableStateOf<Result<com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot>?>(null) }
    var choices by remember(repo.id) { mutableStateOf<Map<String, RepoWorkflow.Choice>>(emptyMap()) }
    var solveAsk by remember(repo.id) { mutableStateOf<RepoWorkflow.Plan?>(null) }
    var folderAsk by remember(repo.id) { mutableStateOf<FolderConfirm?>(null) }
    var confirm by remember(repo.id) { mutableStateOf<String?>(null) }
    var runs by remember(repo.id) { mutableStateOf<List<String>?>(null) }
    var prs by remember(repo.id) { mutableStateOf<List<String>?>(null) }
    var unpushed by remember(repo.id) { mutableStateOf<Result<List<com.diegonmarcos.cloudlib.gitsync.GitCommitInfo>>?>(null) }
    var message by remember(repo.id) { mutableStateOf("") }
    var depthAsk by remember(repo.id) { mutableStateOf<Declarations.GitDepthActionDecl?>(null) }
    val readOnly = page.remoteMode(Declarations.REMOTE_READONLY)?.urlFor(page.owner, repoName) == repo.remoteUrl &&
        repo.authKind == GitSyncCoordinator.AUTH_NONE
    LaunchedEffect(repo.id) { coordinator.loadDetails(repo, page.historyMax) }
    // #1138 the CI/CD run feed is read when its panel opens (a network read the owner asked for), never on render.
    LaunchedEffect(repo.id, panel) {
        if (panel == RepoActions.RUNS && runs == null) runs = coordinator.runFeed(owner, repoName).getOrElse { listOf(ctx.getString(R.string.repo_runs_failed, it.message.orEmpty())) }
    }

    // The unpushed commits are read when their panel opens (and again when the ahead count moves), never on render.
    val aheadNow = glance?.takeIf { it.read && it.upstream != null }?.ahead
    LaunchedEffect(repo.id, panel, aheadNow) {
        if (panel == RepoActions.UNPUSHED) unpushed = if (aheadNow == null) Result.success(emptyList()) else coordinator.unpushedCommits(repo, aheadNow)
    }

    LaunchedEffect(repo.id, panel, running == null) {
        if (panel in WORKFLOW_PANELS && running == null) tree = coordinator.workTree(repo)
    }

    LaunchedEffect(repo.id, panel) {
        if (panel == RepoActions.PRS && prs == null) prs = coordinator.prFeed(owner, repoName).getOrElse { listOf(ctx.getString(R.string.repo_prs_failed, it.message.orEmpty())) }
    }

    // The DECLARED operations' dispatch: the ONE place Kotlin names them, diffed against ui.sync.git.ops by test-drive-git-page.sh.
    fun declared(op: Declarations.GitOpDecl) {
        when (op.id) {
            "fetch" -> coordinator.runOp(repo, op.id)
            "pull" -> coordinator.runOp(repo, op.id)
            "commit" -> panel = if (panel == op.id) "" else op.id
            "push" -> coordinator.runOp(repo, op.id)
            "force_push" -> confirm = op.id
            "force_pull" -> confirm = op.id
            "metadata" -> panel = if (panel == op.id) "" else op.id
            "web" -> actions.openUrl(page.webUrl(repoName))
            "stats" -> panel = if (panel == op.id) "" else op.id
            "history" -> panel = if (panel == op.id) "" else op.id
            "remote" -> panel = if (panel == op.id) "" else op.id
            "hooks" -> panel = if (panel == op.id) "" else op.id
            "actions" -> panel = if (panel == op.id) "" else op.id
            "size" -> panel = if (panel == op.id) "" else op.id
            else -> panel = ""
        }
    }
    // Every chip of the grouped detail: Dangerous and Repo clean ask first (RepoActions.route), the rest run.
    fun act(id: String) {
        if (RepoActions.route(id) == RepoActions.Route.CONFIRM) { confirm = id; return }
        when (id) {
            RepoActions.SYNC_NO_WINNER, RepoActions.SYNC_REMOTE_WINS, RepoActions.SYNC_LOCAL_WINS -> coordinator.runCompound(repo, id)
            // #1289 Goto > Folder: the clone's folder in Drive Files (Explorer ▸ Local).
            RepoActions.OPEN_FOLDER -> actions.openInFiles(repo.path)
            RepoActions.CHECKOUT_MAIN -> coordinator.checkoutMain(repo)
            RepoActions.BRANCHES, RepoActions.PRS, RepoActions.UNPUSHED -> panel = if (panel == id) "" else id
            RepoActions.SYNC_NOW -> coordinator.syncNow(repo)
            RepoActions.SETTINGS -> onSettings()
            RepoActions.META_BRANCH, RepoActions.META_SYNC, RepoActions.RUNS -> panel = if (panel == id) "" else id
            RepoActions.CLONE_FULL -> coordinator.setCloneDepth(0)
            RepoActions.CLONE_DEPTH1 -> coordinator.setCloneDepth(1)
            RepoActions.CLONE_OAUTH -> coordinator.setCloneViaSsh(false)
            RepoActions.CLONE_SSH -> coordinator.setCloneViaSsh(true)
            RepoActions.LIST_DIRTY, RepoActions.LIST_CONFLICTS, RepoActions.SOLVE_CONFLICTS -> { panel = if (panel == id) "" else id; choices = emptyMap() }
            else -> {
                // #1289 Clone: a declared scope-repo depth command, dispatched on its kind (the id stays in build.json).
                page.depthActions(Declarations.DEPTH_SCOPE_REPO).firstOrNull { it.id == id }?.let {
                    val target = GitSyncCoordinator.CloneTarget(repo.name, repo.remoteUrl, GitSyncCoordinator.AUTH_HTTPS, "", "")
                    when {
                        it.kind == Declarations.DEPTH_KEEP -> if (it.confirm) depthAsk = it else coordinator.keepDepth(it, repo)
                        it.kind == Declarations.DEPTH_CLONE_BARE -> coordinator.cloneBare(it, listOf(target))
                        it.kind == Declarations.DEPTH_CLONE_MISSING -> coordinator.cloneMissing(it, listOf(target))
                    }
                    return
                }
                // #1289 Others: the declared folder commands on this clone's folder (each asks first, Delete strictly when dirty).
                page.folderActions.firstOrNull { it.id == id }?.let { folderAsk = FolderConfirm(it, listOf(FolderActions.Target(repo.path, repo.remoteUrl, RegistryNames.live().currentName(repoName)))); return }
                page.op(id)?.let { declared(it) }
            }
        }
    }

    DriveCard(repo.name, light = lightOf(repo, glance), tag = DriveTags.SYNC_REPO_CARD) {
        val statusBlock = RepoStatus.block(true, glance)
        RepoStatusBlock(statusBlock, fmt)
        Text(
            if (repo.lastSyncEpochSeconds > 0) stringResource(R.string.sync_last, fmt.format(Date(repo.lastSyncEpochSeconds * 1000)), repo.lastSyncSummary) else stringResource(R.string.sync_never),
            Modifier.padding(top = DriveMetrics.tight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (glance != null && glance.conflicts > 0) {
            Row(Modifier.padding(top = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = off, modifier = Modifier.padding(horizontal = DriveMetrics.gap))
                Text(stringResource(R.string.sync_conflicts, glance.conflicts), color = off, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            PillRow { Pill(stringResource(R.string.sync_resolve_conflicts, glance.conflicts), { actions.openEngine(EngineActivity.ENGINE_GIT, repo.path) }, filled = true) }
        }
        if (running != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gapWide))
            Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
                GitSyncCoordinator.Step.values().forEach { step ->
                    val active = step == running.step
                    Text(stepLabel(step), style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running.step == GitSyncCoordinator.Step.PUSHING) Text(stringResource(R.string.sync_step_uninterruptible), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // #1138 THE OPERATIONS, GROUPED: one row per command type, chips wrapping, #1289 in the declared ui.sync.git.repo_menu order.
        Box(Modifier.testTag(DriveTags.SYNC_GIT_OPS)) {
            RepoActionGroupsView(
                page = page, busy = running != null,
                blocked = { op ->
                    val blocked = readOnly && (op.id == GitSyncCoordinator.OP_PUSH || op.id == GitSyncCoordinator.OP_FORCE_PUSH)
                    blocked
                },
                active = { id -> id == panel || (id == RepoActions.CLONE_FULL && cloneDepth == 0) || (id == RepoActions.CLONE_DEPTH1 && cloneDepth == 1) || (id == RepoActions.CLONE_OAUTH && !cloneSsh) || (id == RepoActions.CLONE_SSH && cloneSsh) },
                onAction = { act(it) },
                slot = { g -> if (g.id == RepoActions.G_AUTOSYNC) { AutoSyncControls(repo, page.repoMenu.autoSync, g, nextRunMinutes, coordinator); true } else false },
            )
        }
        // #1282 the "bare present" line under the menu: a mirror under the declared bare root already holds this repository.
        BarePresent(coordinator, repo.name, repo.remoteUrl)
        if (readOnly) Text(stringResource(R.string.git_readonly_blocked), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { r ->
            Text(
                r.summary, Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else off, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }

        // THE PANELS — one open at a time, each a read the declaration named.
        val d = details
        when (panel) {
            "commit" -> {
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.git_commit_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide))
                PillRow { Pill(page.op("commit")?.label.orEmpty(), { coordinator.runOp(repo, "commit", message); panel = "" }, filled = true, enabled = running == null) }
            }
            "metadata" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                MetaLine(stringResource(R.string.git_meta_path, repo.path))
                MetaLine(stringResource(R.string.git_meta_branch, glance?.branch ?: stringResource(R.string.sync_no_branch), glance?.upstream ?: stringResource(R.string.git_meta_no_upstream)))
                MetaLine(stringResource(R.string.git_meta_auth, repo.authKind, repo.remoteUrl.ifBlank { stringResource(R.string.git_meta_no_remote) }))
                MetaLine(stringResource(R.string.git_meta_author, repo.authorName.ifBlank { GitSyncCoordinator.DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { GitSyncCoordinator.DEFAULT_EMAIL }))
                if (d != null && !d.loading) {
                    d.remotes.forEach { r -> MetaLine(stringResource(R.string.git_meta_remote, r.name, r.fetchUrl, r.pushUrl)) }
                    MetaLine(stringResource(R.string.git_meta_branches, d.branches.count { !it.isRemote }, d.branches.count { it.isRemote }))
                }
                d?.error?.let { why -> MetaLine(stringResource(R.string.sync_glance_unreadable, why)) }
            }
            "stats" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                MetaLine(stringResource(R.string.git_stats_state, if (glance?.changed == 0) stringResource(R.string.sync_clean) else stringResource(R.string.sync_changed, glance?.changed ?: 0), glance?.ahead ?: 0, glance?.behind ?: 0, glance?.conflicts ?: 0))
                if (d != null && !d.loading) MetaLine(stringResource(R.string.git_stats_commits, d.commits.size, d.hooks.size, d.workflows.size))
            }
            "history" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.commits.isEmpty()) Text(stringResource(R.string.git_history_empty), style = MaterialTheme.typography.bodySmall)
                else d.commits.forEach { c ->
                    MetaLine(stringResource(R.string.git_commit_line, c.shortSha, fmt.format(Date(c.time * 1000)), c.author, c.summary))
                }
            }
            "remote" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                Text(stringResource(R.string.git_remote_switch), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = DriveMetrics.gapWide).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                    page.remoteModes.forEach { mode ->
                        val url = mode.urlFor(page.owner, repoName)
                        Pill(mode.label, { coordinator.setRemote(repo, mode, page.owner, repoName) }, icon = IconCatalog.vectorOrDefault(mode.icon), filled = repo.remoteUrl == url)
                    }
                }
                page.remoteModes.forEach { mode -> MetaLine(stringResource(R.string.git_remote_mode_line, mode.label, mode.urlFor(page.owner, repoName))) }
            }
            "hooks" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.hooks.isEmpty()) Text(stringResource(R.string.git_hooks_none), style = MaterialTheme.typography.bodySmall)
                else d.hooks.forEach { h -> MetaLine(h) }
            }
            "actions" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.workflows.isEmpty()) Text(stringResource(R.string.git_actions_none), style = MaterialTheme.typography.bodySmall)
                else d.workflows.forEach { w -> MetaLine(w) }
            }
            RepoActions.META_BRANCH -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                MetaLine(stringResource(R.string.repo_meta_default_branch, remote?.defaultBranch ?: stringResource(R.string.repo_unknown)))
                MetaLine(stringResource(R.string.git_meta_branch, glance?.branch ?: stringResource(R.string.sync_no_branch), glance?.upstream ?: stringResource(R.string.git_meta_no_upstream)))
            }
            RepoActions.META_SYNC -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                MetaLine(stringResource(R.string.repo_meta_last_sync, if (repo.lastSyncEpochSeconds > 0) fmt.format(Date(repo.lastSyncEpochSeconds * 1000)) + " " + repo.lastSyncSummary else stringResource(R.string.sync_never)))
            }
            RepoActions.RUNS -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                val feed = runs
                if (feed == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (feed.isEmpty()) Text(stringResource(R.string.repo_runs_none), style = MaterialTheme.typography.bodySmall)
                else feed.forEach { MetaLine(it) }
            }
            RepoActions.BRANCHES -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else d.branches.forEach { b -> MetaLine((if (b.isCurrent) "* " else "  ") + b.name + " · " + b.sha.take(7)) }
            }
            RepoActions.UNPUSHED -> Column(Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.SYNC_GIT_UNPUSHED)) {
                val u = unpushed
                if (statusBlock.unpushed == null) Text(stringResource(R.string.repo_unpushed_no_upstream), style = MaterialTheme.typography.bodySmall)
                else if (u == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else u.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) Text(stringResource(R.string.repo_unpushed_none), style = MaterialTheme.typography.bodySmall)
                        else list.forEach { c -> MetaLine(stringResource(R.string.repo_unpushed_line, c.shortSha, c.summary, fmt.format(Date(c.time * 1000)))) }
                    },
                    onFailure = { MetaLine(stringResource(R.string.repo_unpushed_failed, it.message ?: it.javaClass.simpleName)) },
                )
            }
            RepoActions.PRS -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                val feed = prs
                if (feed == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (feed.isEmpty()) Text(stringResource(R.string.repo_prs_none), style = MaterialTheme.typography.bodySmall)
                else feed.forEach { MetaLine(it) }
                PillRow { Pill(stringResource(R.string.repo_prs_open_web), { actions.openUrl(page.webUrl(repoName) + "/pulls") }) }
            }
            "size" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else MetaLine(stringResource(R.string.git_size_line, GitRepoScan.humanBytes(d.size.bytes), d.size.files, d.size.folders, GitRepoScan.humanBytes(d.size.gitBytes)))
            }
            // #1289 Workflow: List Dirty / List Conflicts / Solve Conflicts, from the one status read.
            in WORKFLOW_PANELS -> Column(Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.SYNC_GIT_WORKFLOW), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                val t = tree
                if (t == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else t.fold(
                    onFailure = { MetaLine(stringResource(R.string.sync_glance_unreadable, it.message ?: it.javaClass.simpleName)) },
                    onSuccess = { snap ->
                        val conflicts = RepoWorkflow.conflicts(snap)
                        when (panel) {
                            RepoActions.LIST_DIRTY -> RepoWorkflow.dirty(snap).let { list ->
                                if (list.isEmpty()) Text(stringResource(R.string.repo_wf_clean), style = MaterialTheme.typography.bodySmall)
                                else list.forEach { MetaLine(it.code + " " + it.path) }
                            }
                            RepoActions.LIST_CONFLICTS -> if (conflicts.isEmpty()) Text(stringResource(R.string.repo_wf_no_conflicts), style = MaterialTheme.typography.bodySmall) else conflicts.forEach { MetaLine("UU " + it) }
                            else -> {
                                if (conflicts.isEmpty()) Text(stringResource(R.string.repo_wf_no_conflicts), style = MaterialTheme.typography.bodySmall)
                                conflicts.forEach { path ->
                                    MetaLine(path)
                                    PillRow {
                                        Pill(stringResource(R.string.repo_wf_ours), { choices = choices + (path to RepoWorkflow.Choice.OURS) }, filled = choices[path] == RepoWorkflow.Choice.OURS)
                                        Pill(stringResource(R.string.repo_wf_theirs), { choices = choices + (path to RepoWorkflow.Choice.THEIRS) }, filled = choices[path] == RepoWorkflow.Choice.THEIRS)
                                        Pill(stringResource(R.string.repo_wf_editor), {
                                            actions.openEngine(EngineActivity.ENGINE_EDITOR, File(repo.path, path).absolutePath)
                                            choices = choices + (path to RepoWorkflow.Choice.EDITED)
                                        }, filled = choices[path] == RepoWorkflow.Choice.EDITED)
                                    }
                                }
                                val plan = RepoWorkflow.solvePlan(conflicts, choices, snap.repositoryState)
                                if (conflicts.isNotEmpty()) PillRow {
                                    Pill(stringResource(R.string.repo_wf_stage_continue), { solveAsk = plan }, filled = true, enabled = plan.ready && running == null)
                                }
                                if (plan.missing.isNotEmpty() && conflicts.isNotEmpty()) Text(stringResource(R.string.repo_wf_missing, plan.missing.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                )
            }
        }
        RepoMetaTable(listOf(
            stringResource(R.string.repo_meta_k_name) to repoName,
            stringResource(R.string.repo_meta_k_owner) to owner,
            stringResource(R.string.repo_meta_k_visibility) to RepoTable.visibility(isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub)),
            stringResource(R.string.repo_meta_k_path) to repo.path,
            stringResource(R.string.repo_meta_k_remote) to repo.remoteUrl.ifBlank { stringResource(R.string.git_meta_no_remote) },
            stringResource(R.string.repo_meta_k_branch) to (glance?.branch ?: stringResource(R.string.sync_no_branch)),
            stringResource(R.string.repo_meta_k_upstream) to (glance?.upstream ?: stringResource(R.string.git_meta_no_upstream)),
            stringResource(R.string.repo_meta_k_default_branch) to (remote?.defaultBranch ?: stringResource(R.string.repo_unknown)),
            stringResource(R.string.repo_meta_k_auth) to repo.authKind,
            stringResource(R.string.repo_meta_k_last_sync) to (if (repo.lastSyncEpochSeconds > 0) fmt.format(Date(repo.lastSyncEpochSeconds * 1000)) + " " + repo.lastSyncSummary else stringResource(R.string.sync_never)),
            stringResource(R.string.repo_meta_k_size) to (RepoTable.size(row.sizeMainBytes) + " / " + RepoTable.size(row.sizeTotalBytes)),
            stringResource(R.string.repo_meta_k_size_disk) to RepoTable.size(row.sizeDiskBytes),
            stringResource(R.string.repo_meta_k_branches) to RepoTable.count(row.branches),
        ))
        PillRow { Pill(stringResource(R.string.repo_menu_close), onClose) }
        Text(
            if (repo.autoSync) stringResource(R.string.sync_auto, if (repo.syncIntervalMinutes > 0) repo.syncIntervalMinutes else BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (repo.syncRequireUnmetered) R.string.sync_network_unmetered else R.string.sync_network_any)) else stringResource(R.string.sync_manual),
            Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirm?.let { id ->
        val label = actionLabel(id, page.op(id))
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(label) },
            text = {
                Text(
                    when {
                        RepoActions.cleanDepth(id) > 0 -> stringResource(R.string.repo_confirm_clean, repo.name, RepoActions.cleanDepth(id))
                        id == GitSyncCoordinator.OP_FORCE_PUSH -> stringResource(R.string.git_confirm_force_push, repo.name)
                        else -> stringResource(R.string.git_confirm_force_pull, repo.name)
                    },
                    color = off,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (RepoActions.isCompound(id)) coordinator.runCompound(repo, id) else coordinator.runOp(repo, id); confirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(label) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
    solveAsk?.let { plan ->
        AlertDialog(
            onDismissRequest = { solveAsk = null },
            title = { Text(stringResource(R.string.repo_wf_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                    plan.steps.forEach { st ->
                        MetaLine(when (st) {
                            is RepoWorkflow.Step.Resolve -> stringResource(if (st.side == com.diegonmarcos.cloudlib.gitsync.ConflictSide.OURS) R.string.repo_wf_step_ours else R.string.repo_wf_step_theirs, st.path)
                            is RepoWorkflow.Step.Stage -> stringResource(R.string.repo_wf_step_stage, st.path)
                            is RepoWorkflow.Step.Continue -> stringResource(R.string.repo_wf_step_continue, st.what.name.lowercase())
                        })
                    }
                    Text(stringResource(R.string.repo_wf_never_push), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { coordinator.solveConflicts(repo, plan); solveAsk = null; choices = emptyMap() }) { Text(stringResource(R.string.repo_wf_stage_continue)) } },
            dismissButton = { TextButton(onClick = { solveAsk = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
    folderAsk?.let { c -> FolderConfirmDialog(c, coordinator, repoint, onDone = { folderAsk = null }) }
    depthAsk?.let { a ->
        AlertDialog(
            onDismissRequest = { depthAsk = null },
            title = { Text(a.label) },
            text = { Text(stringResource(R.string.repo_depth_confirm_keep_one, repo.name, a.depth)) },
            confirmButton = { TextButton(onClick = { coordinator.keepDepth(a, repo); depthAsk = null }) { Text(a.label) } },
            dismissButton = { TextButton(onClick = { depthAsk = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

/**
 * #1195 THE PANEL OF A REPOSITORY THAT IS NOT CLONED: it opens on the same tap as a cloned one. What needs no clone works
 * (Open on web, Clone, Copy URL, View PRs, CI runs, the metadata the forge's cached answer holds); every action that needs
 * the local clone is drawn disabled and the line under the chips says why ([RepoActions.needsClone]).
 */
@Composable
private fun GitRepoNotClonedBox(
    page: Declarations.GitPageDecl,
    repoName: String,
    owner: String,
    row: RepoTableRow,
    isPrivate: Boolean?,
    remote: RepoRemoteStats.Stats?,
    cloneUrl: String,
    cloning: Boolean,
    coordinator: GitSyncCoordinator,
    actions: DriveActions,
    onClone: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var prs by remember(repoName) { mutableStateOf<List<String>?>(null) }
    var runs by remember(repoName) { mutableStateOf<List<String>?>(null) }
    var panel by remember(repoName) { mutableStateOf("") }
    val url = cloneUrl.ifBlank { page.cloneUrl(repoName, Declarations.REMOTE_HTTPS) }
    // The two feeds are network reads the owner asked for by opening their panel; never on render.
    LaunchedEffect(repoName, panel) {
        if (panel == RepoActions.PRS && prs == null) prs = coordinator.prFeed(owner, repoName).getOrElse { listOf(ctx.getString(R.string.repo_prs_failed, it.message.orEmpty())) }
        if (panel == RepoActions.RUNS && runs == null) runs = coordinator.runFeed(owner, repoName).getOrElse { listOf(ctx.getString(R.string.repo_runs_failed, it.message.orEmpty())) }
    }
    DriveCard(repoName, light = StatusLight.State.UNKNOWN, tag = DriveTags.SYNC_GIT_NOT_CLONED_PANEL) {
        PillRow {
            Pill(stringResource(R.string.repo_act_open_web), { actions.openUrl(page.webUrl(repoName)) })
            Pill(stringResource(R.string.sync_clone_into_store), onClone, filled = true, enabled = !cloning)
            Pill(stringResource(R.string.repo_act_copy_url), {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(repoName, url))
            })
        }
        RepoActionGroupsView(
            page = page, busy = false, blocked = { false },
            active = { it == panel },
            onAction = { id ->
                when (id) {
                    "web" -> actions.openUrl(page.webUrl(repoName))
                    RepoActions.PRS, RepoActions.RUNS -> panel = if (panel == id) "" else id
                    RepoActions.CLONE_FULL -> coordinator.setCloneDepth(0)
                    RepoActions.CLONE_DEPTH1 -> coordinator.setCloneDepth(1)
                    RepoActions.CLONE_OAUTH -> coordinator.setCloneViaSsh(false)
                    RepoActions.CLONE_SSH -> coordinator.setCloneViaSsh(true)
                    // #1254 Clone (depth 1) / Clone bare from the Clone group: the same one-clone rule as the bulk command.
                    else -> page.depthActions(Declarations.DEPTH_SCOPE_REPO).firstOrNull { it.id == id }?.let {
                        val target = GitSyncCoordinator.CloneTarget(repoName, url, GitSyncCoordinator.AUTH_HTTPS, "", "")
                        when {
                            it.kind == Declarations.DEPTH_CLONE_MISSING -> coordinator.cloneMissing(it, listOf(target))
                            it.kind == Declarations.DEPTH_CLONE_BARE -> coordinator.cloneBare(it, listOf(target))
                        }
                    }
                }
            },
            // A clone that does not exist yet can be made from here; everything else that needs it is drawn disabled.
            unavailable = { id -> page.depthActions(Declarations.DEPTH_SCOPE_REPO).firstOrNull { it.id == id }?.let { it.kind == Declarations.DEPTH_KEEP || cloning } ?: RepoActions.needsClone(id) },
        )
        BarePresent(coordinator, repoName, url)
        Text(stringResource(R.string.repo_not_cloned_why), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (panel) {
            RepoActions.PRS -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                val feed = prs
                if (feed == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (feed.isEmpty()) Text(stringResource(R.string.repo_prs_none), style = MaterialTheme.typography.bodySmall)
                else feed.forEach { MetaLine(it) }
            }
            RepoActions.RUNS -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                val feed = runs
                if (feed == null) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (feed.isEmpty()) Text(stringResource(R.string.repo_runs_none), style = MaterialTheme.typography.bodySmall)
                else feed.forEach { MetaLine(it) }
            }
        }
        RepoMetaTable(listOf(
            stringResource(R.string.repo_meta_k_name) to repoName,
            stringResource(R.string.repo_meta_k_owner) to owner,
            stringResource(R.string.repo_meta_k_visibility) to RepoTable.visibility(isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub)),
            stringResource(R.string.repo_meta_k_remote) to url.ifBlank { stringResource(R.string.git_meta_no_remote) },
            stringResource(R.string.repo_meta_k_default_branch) to (remote?.defaultBranch ?: stringResource(R.string.repo_unknown)),
            stringResource(R.string.repo_meta_k_size) to (RepoTable.size(row.sizeMainBytes) + " / " + RepoTable.size(row.sizeTotalBytes)),
            stringResource(R.string.repo_meta_k_size_disk) to RepoTable.size(row.sizeDiskBytes),
            stringResource(R.string.repo_meta_k_branches) to RepoTable.count(row.branches),
        ))
        PillRow { Pill(stringResource(R.string.repo_menu_close), onClose) }
    }
}

/** One monospace fact line of a panel. */
@Composable
private fun MetaLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun stepLabel(step: GitSyncCoordinator.Step): String = when (step) {
    GitSyncCoordinator.Step.STAGING -> stringResource(R.string.sync_step_staging)
    GitSyncCoordinator.Step.COMMITTING -> stringResource(R.string.sync_step_committing)
    GitSyncCoordinator.Step.PULLING -> stringResource(R.string.sync_step_pulling)
    GitSyncCoordinator.Step.PUSHING -> stringResource(R.string.sync_step_pushing)
}

@Composable
private fun HistoryRow(e: SyncEvent, fmt: DateFormat, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val state = if (e.ok) StatusLight.State.ON else StatusLight.State.OFF
    Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HISTORY).clickable { open = !open }.padding(horizontal = if (compact) DriveMetrics.none else DriveMetrics.sectionInset, vertical = DriveMetrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLightRow(state, e.repoName)
            Spacer(Modifier.width(DriveMetrics.pad))
            Text(fmt.format(Date(e.epochSeconds * 1000)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(DriveMetrics.pad))
            if (!compact) Text(e.repoName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            CapsuleBadge(stringResource(if (e.trigger == SyncHistory.TRIGGER_SCHEDULED) R.string.sync_trigger_scheduled else R.string.sync_trigger_manual))
        }
        Text(e.summary, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        if (open && e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12)
    }
}

/** Per-repository settings: schedule (on/off, period, network), auth, author, rebase, message, remove. Writes the ManagedRepo the engine reads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSettingsSheet(repo: ManagedRepo, coordinator: GitSyncCoordinator, onDismiss: () -> Unit) {
    var autoSync by remember { mutableStateOf(repo.autoSync) }
    var interval by remember { mutableStateOf(repo.syncIntervalMinutes) }
    var unmetered by remember { mutableStateOf(repo.syncRequireUnmetered) }
    var authKind by remember { mutableStateOf(repo.authKind) }
    var username by remember { mutableStateOf(repo.authUsername) }
    var secret by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf(repo.sshKeyPath) }
    var authorName by remember { mutableStateOf(repo.authorName) }
    var authorEmail by remember { mutableStateOf(repo.authorEmail) }
    var rebase by remember { mutableStateOf(repo.pullRebase) }
    var message by remember { mutableStateOf(repo.syncMessage) }
    var confirmRemove by remember { mutableStateOf(false) }
    val hasSecret = remember(repo.id) { coordinator.hasSecret(repo) }
    val periods = Declarations.sync.gitPeriodsMinutes

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.padWide).padding(bottom = DriveMetrics.padWide)) {
            Text(repo.name, style = MaterialTheme.typography.titleLarge)
            Text(repo.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(DriveMetrics.gapWide))
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = autoSync, onCheckedChange = { autoSync = it }); Spacer(Modifier.width(DriveMetrics.pad)); Text(stringResource(R.string.sync_settings_auto)) }
            Text(stringResource(R.string.sync_settings_period), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = DriveMetrics.gapWide), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                (listOf(0L) + periods.map { it.toLong() }).forEach { p ->
                    Pill(if (p == 0L) stringResource(R.string.sync_settings_minutes, BuildConfig.GIT_SYNC_INTERVAL_MINUTES) else stringResource(R.string.sync_settings_minutes, p), { interval = p }, filled = interval == p)
                }
            }
            Text(stringResource(R.string.sync_settings_network), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = DriveMetrics.gapWide), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                Pill(stringResource(R.string.sync_network_unmetered), { unmetered = true }, filled = unmetered)
                Pill(stringResource(R.string.sync_network_any), { unmetered = false }, filled = !unmetered)
            }
            Text(stringResource(R.string.sync_settings_auth), Modifier.padding(top = DriveMetrics.padWide), style = MaterialTheme.typography.labelLarge)
            Row {
                listOf("none" to R.string.sync_auth_none, "https" to R.string.sync_auth_https, "ssh" to R.string.sync_auth_ssh).forEach { (k, label) ->
                    Row(Modifier.clickable { authKind = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = authKind == k, onClick = { authKind = k }); Text(stringResource(label), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (authKind == "https") {
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.sync_auth_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(if (hasSecret) R.string.sync_auth_token_stored else R.string.sync_auth_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (authKind == "ssh") {
                OutlinedTextField(keyPath, { keyPath = it }, label = { Text(stringResource(R.string.sync_auth_key_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.sync_auth_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.sync_settings_author), Modifier.padding(top = DriveMetrics.padWide), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(authorName, { authorName = it }, label = { Text(stringResource(R.string.sync_settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text(stringResource(R.string.sync_settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) { Switch(checked = rebase, onCheckedChange = { rebase = it }); Spacer(Modifier.width(DriveMetrics.pad)); Text(stringResource(R.string.sync_settings_rebase)) }
            OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.sync_settings_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = DriveMetrics.padWide), horizontalArrangement = Arrangement.SpaceBetween) {
                Pill(stringResource(R.string.sync_settings_remove), { confirmRemove = true })
                Pill(stringResource(R.string.chrome_save), {
                    if (secret.isNotBlank()) coordinator.setSecret(repo, secret)
                    coordinator.save(repo.copy(
                        autoSync = autoSync, syncIntervalMinutes = interval, syncRequireUnmetered = unmetered,
                        authKind = authKind, authUsername = username.trim(), sshKeyPath = keyPath.trim(),
                        authorName = authorName.trim(), authorEmail = authorEmail.trim(), pullRebase = rebase,
                        syncMessage = message.trim().ifBlank { repo.syncMessage },
                    ))
                    onDismiss()
                }, filled = true)
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.sync_settings_remove)) },
            text = { Text(stringResource(R.string.sync_settings_remove_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; coordinator.remove(repo); onDismiss() }) { Text(stringResource(R.string.sync_settings_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.chrome_keep)) } },
        )
    }
}

/** #1282 the "bare ✓" indicator when a mirror already holds this repository (the Clone bare chip sits in the menu's Clone group). */
@Composable
private fun BarePresent(coordinator: GitSyncCoordinator, name: String, url: String) {
    val mirrors by coordinator.bareMirrors.collectAsState()
    val at = mirrors[BulkDepth.identity(url.ifBlank { name }) { Declarations.repoSot.canonical(it) }]
    if (at != null) Text(stringResource(R.string.repo_bare_present, at), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * #1289 CONFIGS-AUTOSYNC of one repository: the On/Off switch and "Every X mins" (declared options, default 15, floor 15),
 * stored on the repository and served by the ONE periodic GitSyncWorker (re-applied on save); the line under says when it next runs.
 */
@Composable
private fun AutoSyncControls(repo: ManagedRepo, decl: Declarations.GitAutoSyncDecl, g: Declarations.GitMenuGroupDecl, tickInMinutes: Long?, coordinator: GitSyncCoordinator) {
    val lang = java.util.Locale.getDefault().language
    val period = AutoSyncPlan.minutes(repo.syncIntervalMinutes, decl.defaultMinutes)
    Column(Modifier.testTag(DriveTags.SYNC_GIT_AUTOSYNC), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
        g.items.firstOrNull { it.id == RepoActions.AUTOSYNC_TOGGLE }?.let { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = repo.autoSync, onCheckedChange = { coordinator.setAutoSync(repo, it, repo.syncIntervalMinutes) }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_MENU_ITEM + "_" + item.id))
                Spacer(Modifier.width(DriveMetrics.pad))
                Text(item.labelFor(lang) + ": " + stringResource(if (repo.autoSync) R.string.repo_autosync_on else R.string.repo_autosync_off), style = MaterialTheme.typography.bodySmall)
            }
        }
        g.items.firstOrNull { it.id == RepoActions.AUTOSYNC_EVERY }?.let { item ->
            FlowPills(decl.optionsMinutes.map { m -> m to runCatching { item.labelFor(lang).format(m) }.getOrDefault("$m") }, period) { m -> coordinator.setAutoSync(repo, repo.autoSync, m) }
        }
        val next = AutoSyncPlan.nextRunMinutes(repo.autoSync, period, repo.lastSyncEpochSeconds, System.currentTimeMillis() / 1000, tickInMinutes)
        Text(
            if (next == null) stringResource(R.string.repo_autosync_next_off) else stringResource(R.string.repo_autosync_next, next),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowPills(options: List<Pair<Long, String>>, selected: Long, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        options.forEach { (m, label) -> Pill(label, { onPick(m) }, filled = m == selected) }
    }
}

/** #1289 the Workflow panels that read the working tree. */
private val WORKFLOW_PANELS = setOf(RepoActions.LIST_DIRTY, RepoActions.LIST_CONFLICTS, RepoActions.SOLVE_CONFLICTS)

/** #1315 the job kinds that read only (a check never counts as "a command is running" for the menus). */
private val CHECK_KINDS = setOf("check_light", "check_full", "check_remote", "check_sizes", "scan_inventory", GitSyncCoordinator.CLEANUP_DEBRIS)

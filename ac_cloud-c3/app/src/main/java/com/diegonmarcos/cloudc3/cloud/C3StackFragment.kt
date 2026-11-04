package com.diegonmarcos.cloudc3.cloud

import android.content.Context
import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.pages.ApiFleetLive
import com.diegonmarcos.cloudc3.pages.ContainersSource
import com.diegonmarcos.cloudc3.pages.c3KitPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * #648 the SuperApp's AggregatorStackFragment, MOVED with the c3 surface it renders and
 * trimmed to EXACTLY the panel kinds the carried c3 stacks declare — nothing the two
 * stacks use is missing, and nothing they never used came along:
 *
 *   c3_public / c3_private → the address cards ([C3HealthFragment.Tables], scope-filtered)
 *   wg_mesh                → the WG mesh ([MeshView.Render])
 *   section_title          → plain heading between cards (Addresses / Containers / Stack)
 *   tile_row               → the Index and More rows ([IndexTiles], `anchor:`/`page:` targets)
 *   feed                   → the FIVE feed cards; `source` picks the fetcher
 *                            (github_run_stats | github_runs | dagu_runs |
 *                             github_commits | gitea_commits)
 *   notification_center    → the NTFY centre, stream=channels: one group per ntfy topic,
 *                            live verdicts, swipe-to-read, per-box Archive
 *   cloud_dashboard        → the three container dashboards over data/cloud_services.json,
 *                            TCP-ping status lights, [ContainerSheet] on tap
 *
 * The stream arms the c3 stacks do not declare (`phone`, `cloud`) are stated as absent
 * rather than silently dropped — the SuperApp's phone-notification stores belong to the
 * launcher and did not move.
 *
 * All panels default to **expanded**. Tap the header chevron to collapse/expand. Wording,
 * colours, timeouts, cache rules, the one-request ntfy poll, the pull-down refresh
 * arithmetic and the anchor mechanics are the ones that shipped.
 *
 * Navigation leaves through ONE seam: [TargetListener.onTargetClicked], which the host
 * activity implements — the same role MainActivity's tile dispatcher played, sized to an
 * app whose targets are `page:`/`extapp:`/URLs.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a [KitComposeFragment]: the
 * cards are composables, the bodies' loads are LaunchedEffects scoped to the view, and the
 * embedded health / mesh pages are composables rather than child fragments.
 */
class C3StackFragment : KitComposeFragment() {

    /** Where a non-anchor target goes. The host decides; this fragment only reports it. */
    interface TargetListener {
        fun onTargetClicked(target: String)
    }

    private val stackId: String get() = arguments?.getString(ARG_STACK).orEmpty()

    /** The Containers sub-page's project id (null = unscoped); an id the project declaration does not know is All. */
    private val projectId: String? get() = arguments?.getString(ARG_PROJECT)

    /** In-page `anchor:` links for this stack. Generic — the registry is fed
     *  from the panels' own declarations. */
    private val anchors = StackAnchors()

    // ── page state ─────────────────────────────────────────────────────
    private val collapsedPanels: SnapshotStateMap<Int, Boolean> = mutableStateMapOf()
    private val feedItems: SnapshotStateMap<Int, List<FeedItem>> = mutableStateMapOf()
    private val lights: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    private var sheet by mutableStateOf<ContainerSheet.Target?>(null)
    private var stackPanels: List<Stacks.StackPanel> = emptyList()

    // ── pull-down to refresh ───────────────────────────────────────────
    private var refreshing by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)
    /** The [refreshKey] value a user pull produced: its polls are forced, the first build's are not. */
    private var forcedKey by mutableIntStateOf(-1)
    private var refreshNote by mutableStateOf<Pair<String, Color>?>(null)
    private var refreshPending = 0
    private val pageRowIds = LinkedHashSet<String>()
    private var refreshBefore: Set<String> = emptySet()

    /** The page-level Archive: ONE collapsed section at the bottom holding every channel
     *  box the user archived. */
    private var archived by mutableStateOf<Set<String>>(emptySet())
    private var archiveOpen by mutableStateOf(false)
    private var filterPage = ""
    private val sortMode = "time"
    private val showMode = "all"
    /** Start of this visit's NEW window: the ts of the PREVIOUS visit. This is what a
     *  group's "N new" chip counts — arrival, not attention. */
    private var visitSeenAt = 0L
    /** Ids the user has explicitly swiped read on this page. */
    private var readIds by mutableStateOf<Set<String>>(emptySet())

    /** Read is EXPLICIT — the user swiped this row away. A row with no stable identity
     *  has nothing to remember, so it falls back to the arrival watermark. */
    private fun isUnread(r: NotifRow): Boolean =
        if (r.id.isBlank()) r.ts > visitSeenAt else r.id !in readIds

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()

        val panels = remember(stackId) {
            when (stackId) {
                STACK_TOPOLOGY -> Stacks.stackTopology()
                STACK_OBSERVABILITY -> Stacks.stackObservability()
                else -> emptyList()
            }
        }
        stackPanels = panels
        val scoped = projectId?.let { id -> Declarations.containerProjects.project(id) }
        if (scoped != null && Stacks.cloudServices(scoped.id).groups.all { g -> g.subgroups.all { it.containers.isEmpty() } }) {
            Text("No container is declared for ${scoped.label}", color = p.textSecondary,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return
        }
        if (panels.isEmpty()) {
            Text("No panels declared for stack: $stackId", color = p.textSecondary,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            return
        }
        remember(stackId) {
            // The page id is the prefs key: swipes, the visit watermark and the Archive all
            // land under it, never in a blank-keyed bucket shared with the other stack page.
            filterPage = stackId
            readIds = StackFilters.readKeys(ctx, filterPage)
            visitSeenAt = StackFilters.lastSeen(ctx, filterPage)
            StackFilters.markSeen(ctx, filterPage, System.currentTimeMillis())
            archiveOpen = StackFilters.selected(ctx, filterPage, yesNo(FILTER_ARCHIVE_OPEN)) == "yes"
            archived = panels.filter { it.kind == "notification_center" }
                .flatMap { ntfyTopics(it) }.filterTo(HashSet()) { isArchived(ctx, it) }
            collapsedPanels.clear()
            panels.forEachIndexed { i, panel -> collapsedPanels[i] = panel.collapsed }
            anchors.reset(scroll, scope) { index -> collapsedPanels[index] = false }
            panels.forEachIndexed { i, panel ->
                anchors.register(panel.anchor, i)
                for (a in panel.anchors) anchors.register(a.id, i)
            }
            true
        }

        val refreshable = panels.any { it.kind == "notification_center" }
        val page: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).then(anchors.host()).padding(12.dp),
            ) {
                refreshNote?.let { (text, colour) ->
                    Text(text, color = colour, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp).testTag("c3:stack:refresh-note"))
                }
                panels.forEachIndexed { i, panel ->
                    if (panel.kind == "section_title") SectionTitle(panel.title, Modifier.then(anchors.mark(panel.anchor)))
                    else PanelCard(i, panel)
                }
                ArchiveSection(panels)
            }
        }
        if (refreshable) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { startRefresh(scope) }, modifier = Modifier.fillMaxSize()) {
                page()
            }
        } else page()

        sheet?.let { target ->
            ContainerSheet.Sheet(target = target, onOpenUrl = { url -> onTargetClicked(url) }, onDismiss = { sheet = null })
        }
    }

    /** kind=section_title — a plain heading between cards (NOT a card). */
    @Composable
    private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
        val p = LocalKitPalette.current
        Text(text, color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 6.dp))
    }

    // ── Panel card (header + collapsable body) ─────────────────────────

    @Composable
    private fun PanelCard(index: Int, panel: Stacks.StackPanel) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val open = collapsedPanels[index] != true

        // The loads belong to the CARD, not its body: a collapsed card still loads once, and
        // opening it again does not refetch.
        when (panel.kind) {
            "feed" -> LaunchedEffect(index) { feedItems[index] = loadFeed(ctx, panel) }
            "cloud_dashboard" -> {
                // #1148 the live document first (the Table page reads the same one), then the lights for what it lists.
                LaunchedEffect(index) { ContainersSource.refresh(ApiFleetLive(ctx.applicationContext)) }
                LaunchedEffect(index, ContainersSource.version) { pingAll(panel) }
            }
            "notification_center" -> LaunchedEffect(index, refreshKey) { pollPanel(ctx, panel, forced = refreshKey == forcedKey) }
            else -> Unit
        }

        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).then(anchors.mark(panel.anchor))
                .background(p.surface, shape).testTag("c3:stack:panel:$index"),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth().clickable { collapsedPanels[index] = open }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(panel.title.ifBlank { panel.kind.replace('_', ' ') }, color = p.accent,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                val turn by animateFloatAsState(if (open) 90f else 0f, label = "c3-chevron")
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(turn))
            }
            // Optional subtitle line
            if (panel.subtitle.isNotBlank()) {
                Text(panel.subtitle, color = p.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp))
            }
            if (open) {
                // A notification shade is edge-to-edge: the group headers and rows supply their
                // own structure, so the card's inner gutter is chrome that only steals width.
                val gutter = if (panel.kind == "notification_center") 6.dp else 16.dp
                Column(Modifier.fillMaxWidth().padding(start = gutter, top = 8.dp, end = gutter, bottom = 16.dp)) {
                    PanelBody(index, panel)
                }
            }
        }
    }

    // ── Body kind dispatcher ───────────────────────────────────────────

    @Composable
    private fun PanelBody(index: Int, panel: Stacks.StackPanel) {
        when (panel.kind) {
            "c3_public"           -> C3HealthFragment.Tables(C3HealthFragment.SCOPE_PUBLIC) { onTargetClicked(it) }
            "c3_private"          -> C3HealthFragment.Tables(C3HealthFragment.SCOPE_PRIVATE) { onTargetClicked(it) }
            "wg_mesh"             -> MeshView.Render()
            "tile_row"            -> TileRow(panel.tiles)
            "notification_center" -> NotificationCenter(panel)
            "feed"                -> FeedBody(index, panel)
            "cloud_dashboard"     -> CloudDashboard(index, panel)
            else                  -> EmptyRow("(unknown panel kind '${panel.kind}' — this app carries no renderer for it)")
        }
    }

    // ── kind=feed ──────────────────────────────────────────────────────
    //
    // ONE config-driven kind for every repo/run integration on this page, per build.json's
    // own instruction: a new integration is a new `when` arm here plus a client, never a
    // new panel `kind`.

    /** One line of a feed card. */
    private sealed class FeedItem {
        class Loading(val text: String) : FeedItem()
        class Empty(val text: String) : FeedItem()
        class Header(val title: String, val sub: String) : FeedItem()
        class Row(
            val title: String, val meta: String, val url: String, val severity: String,
            val rerun: Pair<Stacks.RepoRef, String>? = null,
        ) : FeedItem()
    }

    @Composable
    private fun FeedBody(index: Int, panel: Stacks.StackPanel) {
        val items = feedItems[index] ?: listOf(FeedItem.Loading(feedLoadingLabel(panel)))
        for (item in items) {
            when (item) {
                is FeedItem.Loading -> Text(item.text, color = Color(0x88FFFFFF), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
                is FeedItem.Empty -> EmptyRow(item.text)
                is FeedItem.Header -> RepoHeader(item.title, item.sub)
                is FeedItem.Row -> GithubRow(item)
            }
        }
    }

    private fun feedLoadingLabel(panel: Stacks.StackPanel): String = when {
        panel.source == "github_runs" -> "Loading workflow runs…"
        panel.source == "github_run_stats" -> "Counting workflow runs…"
        panel.source.startsWith("dagu") -> "Loading Dagu runs…"
        else -> "Loading commits…"
    }

    private suspend fun loadFeed(ctx: Context, panel: Stacks.StackPanel): List<FeedItem> = when (panel.source) {
        "github_runs"      -> githubRunsFeed(ctx, panel)
        "github_run_stats" -> runStatsFeed(ctx, panel)
        "github_commits"   -> repoCommitsFeed(ctx, panel, gitea = false)
        "gitea_commits"    -> repoCommitsFeed(ctx, panel, gitea = true)
        "dagu_runs"        -> daguRunsFeed(ctx, panel)
        else -> listOf(FeedItem.Empty(
            "(kind=feed needs a \"source\": github_runs | github_run_stats | " +
                "github_commits | gitea_commits | dagu_runs — got '${panel.source}')"))
    }

    /** Last commit per repo, one group per repo — "GH Repos" (source=github_commits) and
     *  "Gitea Repos" (source=gitea_commits). The two only differ in which client fetches
     *  the rows. */
    private suspend fun repoCommitsFeed(ctx: Context, panel: Stacks.StackPanel, gitea: Boolean): List<FeedItem> {
        if (panel.repos.isEmpty()) {
            return listOf(FeedItem.Empty("No repos declared. Add a `repos: [{owner, repo, label}]` array to the panel."))
        }
        val perRepo = panel.limit.takeIf { it > 0 } ?: 1
        val now = System.currentTimeMillis()
        val results = coroutineScope {
            panel.repos.mapIndexed { i, ref ->
                // Stagger the fan-out: a dozen repos hitting either API at once beats up
                // the anonymous GitHub quota just the same as it would beat up the Gitea
                // container, so both sources share the same trickle.
                async {
                    delay(i * GitHubFeed.STAGGER_MS)
                    val feed = if (gitea) GiteaFeed.commits(ctx, ref.owner, ref.repo, perRepo)
                               else GitHubFeed.commits(ctx, ref.owner, ref.repo, perRepo)
                    ref to feed
                }
            }.awaitAll()
        }
        val out = mutableListOf<FeedItem>()
        for ((ref, feed) in results) {
            out += FeedItem.Header(ref.label, "${ref.owner}/${ref.repo}")
            if (feed.items.isEmpty()) {
                out += FeedItem.Empty(feedEmptyLabel(feed.status, "commits"))
                continue
            }
            if (feed.status != GitHubFeed.Status.OK) {
                out += FeedItem.Empty(feedStaleLabel(feed.status))
            }
            for (c in feed.items) {
                out += FeedItem.Row(
                    title    = c.message,
                    meta     = "${c.sha} · ${c.author} · ${GitHubFeed.ago(now - c.tsMillis)}",
                    url      = c.htmlUrl,
                    severity = "info",
                )
            }
        }
        return out
    }

    /** "GHA": the last [limit] workflow runs across EVERY declared repo, merged into one
     *  chronological list. Each row is labelled with its repo. */
    private suspend fun githubRunsFeed(ctx: Context, panel: Stacks.StackPanel): List<FeedItem> {
        if (panel.repos.isEmpty()) {
            return listOf(FeedItem.Empty("No repos declared. Add a `repos` array to the panel."))
        }
        val limit = panel.limit.takeIf { it > 0 } ?: 5
        val now = System.currentTimeMillis()
        val results = coroutineScope {
            panel.repos.mapIndexed { i, ref ->
                async { delay(i * GitHubFeed.STAGGER_MS)
                        ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, limit) }
            }.awaitAll()
        }
        val flat = results
            .flatMap { (ref, feed) -> feed.items.map { ref to it } }
            .sortedByDescending { it.second.tsMillis }
            .take(limit)
        if (flat.isEmpty()) {
            val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK }
                ?: GitHubFeed.Status.OK
            return listOf(FeedItem.Empty(feedEmptyLabel(worst, "workflow runs")))
        }
        return flat.map { (ref, r) ->
            val sev = when (r.conclusion) {
                "success" -> "info"
                "failure", "timed_out", "cancelled" -> "error"
                else      -> "warn"
            }
            val statusLabel = if (r.conclusion.isNotBlank()) r.conclusion else r.status
            FeedItem.Row(
                title    = r.displayTitle.ifBlank { r.name },
                meta     = "${ref.label} · ${r.name} · $statusLabel · ${GitHubFeed.ago(now - r.tsMillis)}",
                url      = r.htmlUrl,
                severity = sev,
                // Only rows whose run identified its workflow file can be re-dispatched.
                rerun    = r.workflowFile.takeIf { it.isNotBlank() }?.let { ref to it },
            )
        }
    }

    /** "Analytics": the only card on this page that COUNTS rather than lists. Over the
     *  last [limit] runs per declared repo: how many ran, what share of the FINISHED ones
     *  went green, how many failed, and how long ago the last green was. THE DENOMINATOR
     *  IS THE FINISHED RUNS — a rate over an empty denominator is not a rate. CANCELLED IS
     *  NOT A FAILURE (cancel-in-progress). Reads the SAME GitHubFeed.runs the GHA card
     *  reads, through the same cache, so the two cards cost ONE request per repo between
     *  them and cannot disagree about a number. Tapping the fleet row hands off to
     *  panel.url (extapp:c3-watchtower) through the ordinary dispatcher. */
    private suspend fun runStatsFeed(ctx: Context, panel: Stacks.StackPanel): List<FeedItem> {
        if (panel.repos.isEmpty()) {
            return listOf(FeedItem.Empty("No repos declared. Add a `repos` array to the panel."))
        }
        val limit = panel.limit.takeIf { it > 0 } ?: 30
        val now = System.currentTimeMillis()
        val results = coroutineScope {
            panel.repos.mapIndexed { i, ref ->
                async { delay(i * GitHubFeed.STAGGER_MS)
                        ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, limit) }
            }.awaitAll()
        }
        val all = results.flatMap { it.second.items }
        if (all.isEmpty()) {
            // The WORST status, not the first: one reachable repo with an empty window
            // must not read as a healthy fleet when the rest were rate limited.
            val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK }
                ?: GitHubFeed.Status.OK
            return listOf(FeedItem.Empty(feedEmptyLabel(worst, "workflow runs")))
        }
        val out = mutableListOf<FeedItem>()
        if (results.any { it.second.status != GitHubFeed.Status.OK }) {
            out += FeedItem.Empty(feedStaleLabel(
                results.map { it.second.status }.first { it != GitHubFeed.Status.OK }))
        }
        out += runStatsRow("Fleet", all, now, panel.url)
        for ((ref, feed) in results) {
            if (feed.items.isEmpty()) {
                out += FeedItem.Empty("${ref.label} — ${feedEmptyLabel(feed.status, "workflow runs")}")
                continue
            }
            out += runStatsRow(ref.label, feed.items, now, "")
        }
        return out
    }

    /** One aggregate line. [url] is blank for the per-repo rows — only the fleet row is a
     *  handoff, so a tap anywhere in the card has exactly one meaning. */
    private fun runStatsRow(label: String, runs: List<GitHubFeed.Run>, now: Long, url: String): FeedItem.Row {
        val finished = runs.filter { it.conclusion.isNotBlank() }
        val green    = finished.filter { it.conclusion == "success" }
        val failed   = finished.count { it.conclusion == "failure" || it.conclusion == "timed_out" }
        val lastGreen = green.maxOfOrNull { it.tsMillis } ?: 0L

        val verdict = if (finished.isEmpty()) {
            "no verdicts yet (${runs.size} still running or queued)"
        } else {
            val pct = (green.size * 100) / finished.size
            val last = if (lastGreen > 0L) "last green ${GitHubFeed.ago(now - lastGreen)}"
                       else "no green run in this window"
            "${runs.size} runs · $pct% green · $failed failed · $last"
        }
        return FeedItem.Row(
            title    = label,
            meta     = verdict,
            url      = url,
            severity = when {
                finished.isEmpty() -> "idle"
                failed > 0         -> "error"
                else               -> "info"
            },
        )
    }

    /** "Dagu": the last [limit] runs across EVERY registered DAG. Server + bearer token
     *  come from libs:ops' DaguPrefs, shared with the native Dagu page, so there is one
     *  Dagu config on the device rather than two that can disagree. */
    private suspend fun daguRunsFeed(ctx: Context, panel: Stacks.StackPanel): List<FeedItem> {
        val limit = panel.limit.takeIf { it > 0 } ?: 5
        val prefs = com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx)
        val now = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            runCatching { DaguRunsFeed.recentRuns(prefs.serverUrl, prefs.bearerToken, limit) }
        }
        outcome.exceptionOrNull()?.let { e ->
            return listOf(FeedItem.Empty(
                "(Dagu unreachable — ${e.message ?: "no response"}. " +
                    "If the token expired, sign in again on the Dagu page.)"))
        }
        val runs = outcome.getOrDefault(emptyList())
        if (runs.isEmpty()) return listOf(FeedItem.Empty("(no runs recorded on ${prefs.serverUrl})"))
        return runs.map { r ->
            val ts = if (r.finishedAtMs > 0L) r.finishedAtMs else r.startedAtMs
            FeedItem.Row(
                title    = r.name,
                meta     = "${daguStatusLabel(r.status)} · ${GitHubFeed.ago(now - ts)}",
                url      = "page:observ/dagu",
                severity = daguSeverity(r.status),
            )
        }
    }

    /** What an EMPTY GitHub feed actually means — a spent quota says "rate limited" in as
     *  many words, because rendering a 403 as an empty repo is a silent failure. */
    private fun feedEmptyLabel(status: GitHubFeed.Status, noun: String): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED ->
            "(rate limited — GitHub's anonymous 60 requests/hour quota is spent; retry in a few minutes)"
        GitHubFeed.Status.UNREACHABLE ->
            "(unreachable — GitHub did not answer; check the network)"
        GitHubFeed.Status.OK -> "(no recent $noun)"
    }

    /** Shown ABOVE rows that came from cache because the live fetch failed. */
    private fun feedStaleLabel(status: GitHubFeed.Status): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED -> "(rate limited — showing the last cached rows)"
        else                           -> "(offline — showing the last cached rows)"
    }

    /** Dagu run-status code → words. Codes are Dagu's own. */
    private fun daguStatusLabel(code: Int): String = when (code) {
        0    -> "never run"
        1    -> "running"
        2    -> "failed"
        3    -> "cancelled"
        4    -> "succeeded"
        6    -> "partially succeeded"
        7    -> "queued"
        else -> "status $code"
    }

    /** Never-run maps to "idle", NOT to the default row colour. */
    private fun daguSeverity(code: Int): String = when (code) {
        0          -> "idle"
        2, 3       -> "error"
        1, 6, 7    -> "warn"
        else       -> "info"
    }

    @Composable
    private fun RepoHeader(title: String, sub: String) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            Text(title, color = p.accent, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = Color(0x77FFFFFF), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun EmptyRow(label: String) {
        Text(label, color = Color(0x77FFFFFF), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
    }

    @Composable
    private fun GithubRow(row: FeedItem.Row) {
        val p = LocalKitPalette.current
        val tint = when (row.severity) {
            "error" -> Color(0x55B91C1C)
            "warn"  -> Color(0x55D97706)
            // "idle" = declared but never run: a neutral dim wash, legible as its own
            // state at a glance.
            "idle"  -> Color(0x22FFFFFF)
            else    -> Color(0x331A0033)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp).background(tint).testTag("c3:feed:${row.title}")
                .clickable { if (row.url.isNotBlank()) onTargetClicked(row.url) }.padding(8.dp),
        ) {
            Text(row.title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(row.meta, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp))
            row.rerun?.let { (ref, file) -> GhaTriggerRow(ref, file) }
        }
    }

    /**
     * "Re-run" control for one GitHub Actions workflow row. Dispatch goes through
     * c3-infra-api (POST /workflows/dispatch) with the Authelia bearer this device already
     * stores, NOT with a GitHub token: a PAT with actions:write shipped inside the APK
     * would be extractable by anyone who unzips it. The server holds the PAT. Every
     * outcome is displayed in place — the control never finishes without saying what
     * happened.
     */
    @Composable
    private fun GhaTriggerRow(ref: Stacks.RepoRef, workflowFile: String) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    busy = true
                    status = "Dispatching $workflowFile…" to Color(0x88FFFFFF)
                    val bearer = runCatching {
                        com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx).bearerToken
                    }.getOrDefault("")
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            OpsClient.dispatchWorkflow(
                                repo = "${ref.owner}/${ref.repo}",
                                workflow = workflowFile,
                                ref = "main",
                                bearer = bearer,
                            )
                        }
                        busy = false
                        status = when (outcome) {
                            is OpsClient.Outcome.Ok -> outcome.message to SIGNAL_OK_TEXT
                            is OpsClient.Outcome.Failed -> "${outcome.kind}: ${outcome.message}" to SIGNAL_BAD_TEXT
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("c3:feed:rerun:$workflowFile"),
            ) { Text("Re-run", fontSize = 12.sp) }
            status?.let { (text, colour) -> Text(text, color = colour, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
        }
    }

    // ── kind=notification_center, stream=channels ──────────────────────
    //
    // These bodies re-run on every pull-down: the card's poll effect is keyed by [refreshKey],
    // and the groups are drawn from [ntfyCache] and the read / archive state alone.

    /** One publisher and everything it has posted. [key] is the ntfy topic we grouped on;
     *  [label] is what the user reads. */
    private data class NotifGroup(
        val key: String,
        val label: String,
        val sub: String,
        val url: String = "",
    )

    private data class NotifRow(
        val ts: Long,
        val title: String,
        val text: String,
        val severity: String = "info",
        /** Stable per-entry identity, NAMESPACED per topic ("ntfy:<topic>:…") so
         *  [StackFilters.pruneRead] can retire one topic's read keys without seeing the
         *  others. Blank means this row has no identity to remember. */
        val id: String = "",
    )

    /** A group's one-line verdict. Same four-state vocabulary as the C3 health page:
     *  the two things this card must never confuse are a channel that has genuinely
     *  posted nothing, and a stream we could not read. */
    private data class GroupState(val text: String, val color: Color)

    private data class NtfyResult(val ok: Boolean, val error: String, val rows: List<NotifRow>)

    /** Per-topic poll results for this visit — also the ordering key for Sort=Time. */
    private val ntfyCache: SnapshotStateMap<String, NtfyResult> = mutableStateMapOf()

    /** The newest timestamp we have actually MEASURED for [topic], or Long.MIN_VALUE.
     *  Unmeasured and unreachable both sort last — the honest placement. */
    private fun newestMeasured(topic: String): Long =
        ntfyCache[topic]?.takeIf { it.ok }?.rows?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE

    /** Which groups the user has folded away. Held on the fragment, not on the view. */
    private val collapsedGroups: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** The topics a notification card draws, from the baked ui.ntfy catalog narrowed to the card's scopes. */
    private fun ntfyTopics(panel: Stacks.StackPanel): List<String> {
        if (panel.stream != "channels") return emptyList()
        val scopes = NtfyScopes.load()
        val catalog = NtfyScopes.fallbackChannels()
        return if (panel.scopes.isEmpty()) catalog else catalog.filter {
            NtfyScopes.scopeOf(it, scopes).id in panel.scopes
        }
    }

    @Composable
    private fun NotificationCenter(panel: Stacks.StackPanel) {
        when (panel.stream) {
            // C3 Obsv's stream: the ntfy CHANNELS and nothing else.
            "channels" -> NtfyGroups(panel)
            // The launcher's phone/cloud streams did not move with the c3 surface: they
            // read the SuperApp's notification-listener stores, which belong to the
            // launcher. Stated rather than silently dropped.
            "phone", "cloud" -> {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares " +
                    "stream=${panel.stream}, which is the SuperApp launcher's store and is " +
                    "not carried in this app")
                StateLine("stream '${panel.stream}' is the SuperApp " +
                    "launcher's and is not carried here", SIGNAL_UNKNOWN)
            }
            else -> {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares no " +
                    "`stream`: add \"stream\": \"channels\" in build.json")
                StateLine("no stream declared", SIGNAL_UNKNOWN)
            }
        }
    }

    /**
     * One group per ntfy topic in this card's declared scopes, each with a live verdict:
     *
     *   N · 5m ago  — messages arrived (green).
     *   silent 24h  — the poll SUCCEEDED and the topic is empty (amber): a dead publisher
     *                 looks exactly like a quiet one and amber says we cannot tell them
     *                 apart.
     *   unavailable — the poll itself failed. GREY, never red and never absent: a failed
     *                 fetch is a claim about THIS PHONE'S network, not about the fleet.
     *
     * The topic list comes from the baked ui.ntfy catalog. The order is decided ONCE from
     * the last measurement [ntfyCache] holds, and the poll landing never re-sorts.
     */
    @Composable
    private fun NtfyGroups(panel: Stacks.StackPanel) {
        val topics = remember(panel) { ntfyTopics(panel) }
        if (topics.isEmpty()) {
            android.util.Log.w(TAG, "ntfy panel '${panel.title}' matched no channel: no " +
                "build.json::ui.ntfy topic falls in scopes (" +
                panel.scopes.joinToString(", ").ifBlank { "all" } + ")")
            StateLine("no channels in scope", SIGNAL_UNKNOWN)
            return
        }
        val byLabel = compareBy(String.CASE_INSENSITIVE_ORDER) { t: String -> NtfyCatalog.labelOf(t) }
        val ordered = remember(panel, refreshKey) {
            if (sortMode == "app") topics.sortedWith(byLabel)
            else topics.sortedWith(compareByDescending<String> { newestMeasured(it) }.then(byLabel))
        }
        for (topic in ordered) {
            if (topic in archived) continue   // filed under the page's Archive
            GroupBlock(groupOf(topic), panel.limit)
        }
    }

    private fun groupOf(topic: String) = NotifGroup(
        key   = topic,
        label = NtfyCatalog.labelOf(topic),
        sub   = topic,
        // The HUMAN address, deliberately the gated public one: it opens in a
        // browser that can carry the Authelia session. Only the programmatic poll
        // needs the open route.
        url   = "${NtfyCatalog.webBaseUrl()}/$topic",
    )

    /** Polls the card's topics: the ones nothing is known about yet, or every one when the user pulled. */
    private suspend fun pollPanel(ctx: Context, panel: Stacks.StackPanel, forced: Boolean) {
        val topics = ntfyTopics(panel)
        val wanted = topics.filter { ntfyCache[it] == null || forced }
        if (wanted.isEmpty()) return
        // ONE REQUEST FOR ALL OF THEM. ntfy takes a comma-separated topic list and stamps
        // every envelope with its own `topic`. Per-topic requests spend the visitor burst
        // quota (60, one token back per 10s) and hand back scattered 429s — grey cards
        // that move around between visits, the hardest kind of broken to report.
        val byTopic = withContext(Dispatchers.IO) { pollTopics(wanted) }
        for ((topic, result) in byTopic) {
            ntfyCache[topic] = result
            if (!result.ok) continue
            // A SUCCESSFUL poll is the complete current window for this topic, and only then
            // may its read keys be retired — a mesh hiccup does not resurrect swiped rows.
            StackFilters.pruneRead(ctx, filterPage, ntfyNs(topic), result.rows.mapTo(HashSet()) { it.id })
            for (r in shownRows(result, panel.limit)) if (r.id.isNotBlank()) pageRowIds += r.id
        }
        if (forced) ntfyPollSettled()
    }

    /**
     * ntfy's poll API, for EVERY topic on the card in one request. Any non-200, any
     * exception and any unparseable body is UNAVAILABLE — the honest answer is that we
     * did not measure. Every requested topic gets an entry back, so a card can never be
     * left reading "checking…". THE ADDRESS comes from [NtfyCatalog.pollUrl] — one
     * declared place, the mesh origin ahead of the public edge's authorization gate.
     * NO REDIRECTS: a 3xx is the SSO bounce, and followed it becomes a 200 of login HTML
     * that parses to zero messages.
     */
    private fun pollTopics(topics: List<String>): Map<String, NtfyResult> {
        fun all(r: NtfyResult) = topics.associateWith { r }
        return try {
            val url = java.net.URL(NtfyCatalog.pollUrl(topics.joinToString(",")))
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                // Long enough to actually finish: health_resources alone replays ~851 KB
                // in the declared window, and a 4-second read turned the busiest channel
                // on the fleet into "no answer".
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                if (conn.responseCode != 200)
                    all(NtfyResult(false, NtfyCatalog.readVerdict(conn.responseCode), emptyList()))
                else {
                    val rows = topics.associateWith { mutableListOf<NotifRow>() }
                    conn.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) runCatching {
                            val o = org.json.JSONObject(line)
                            // Each envelope names its own topic — that field is the whole
                            // reason one request can fill many cards.
                            val topic = o.optString("topic")
                            if (o.optString("event") == "message") rows[topic]?.let { bucket ->
                                val ts = o.optLong("time", 0L) * 1000L
                                bucket += NotifRow(
                                    ts    = ts,
                                    title = o.optString("title").ifBlank { topic },
                                    text  = o.optString("message"),
                                    id    = ntfyNs(topic) +
                                        o.optString("id").ifBlank { ts.toString() },
                                )
                            }
                        }
                    }
                    rows.mapValues { (_, v) -> NtfyResult(true, "", v) }
                }
            } finally { conn.disconnect() }
        } catch (_: Throwable) {
            // Nothing answered at all. On a phone that is almost always the mesh being
            // down, and saying so is the difference between the owner reconnecting
            // WireGuard and the owner filing another bug about the fleet.
            all(NtfyResult(false, NtfyCatalog.UNREACHABLE_VERDICT, emptyList()))
        }
    }

    /** Show=Unread inside one group: everything not swiped away, newest-first. */
    private fun withinGroup(rows: List<NotifRow>): List<NotifRow> {
        val shown = if (showMode == "unread") rows.filter { isUnread(it) } else rows
        return shown.sortedByDescending { it.ts }
    }

    /** The rows a group draws: `limit` caps how many of this GROUP's rows are shown (the c3
     *  card wants "last 5 per channel"); 0 means every row the poll window returned. */
    private fun shownRows(result: NtfyResult, limit: Int): List<NotifRow> =
        withinGroup(result.rows).let { if (limit > 0) it.take(limit) else it }

    /** The verdict chip of a group, from its poll result alone (null = not polled yet). */
    private fun verdictOf(result: NtfyResult?, limit: Int): GroupState {
        // "checking…", never OK: an unpolled channel must not spend even its first
        // frame looking healthy.
        if (result == null) return GroupState("checking…", SIGNAL_UNKNOWN)
        if (!result.ok) {
            // The verdict already says what went wrong and what to do about it.
            return GroupState(result.error, SIGNAL_UNKNOWN)
        }
        val rows = shownRows(result, limit)
        if (rows.isEmpty()) {
            // Empty channel and hidden-by-filter are different facts.
            return if (result.rows.isEmpty()) GroupState("silent ${NtfyCatalog.pollWindow()} · publisher?", SIGNAL_WARN)
                   else GroupState("nothing new", Color(0x88FFFFFF))
        }
        return GroupState("${rows.size} · ${ago(System.currentTimeMillis() - rows.first().ts)}", SIGNAL_OK)
    }

    /**
     * One publisher drawn as a notification-shade GROUP: a real header bar — monogram,
     * name, count chip — with its notifications folded under it. Tapping the header
     * COLLAPSES the group; launching lives on the monogram.
     */
    @Composable
    private fun GroupBlock(g: NotifGroup, limit: Int) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val result = ntfyCache[g.key]
        val state = verdictOf(result, limit)
        val open = collapsedGroups[g.key] != true
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("c3:ntfy:${g.key}")) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x1FFFFFFF), RoundedCornerShape(10.dp))
                    .clickable { collapsedGroups[g.key] = open }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupAvatar(g)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(g.label, color = Color(0xFFF2E9FF), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (g.sub.isNotBlank() && !g.sub.equals(g.label, ignoreCase = true)) {
                        Text(g.sub, color = Color(0x66FFFFFF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // The count chip. ONE Text holding the whole verdict, because an async ntfy
                // group repaints it with a failure sentence that has no count in it at all.
                Text(state.text, color = state.color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(9.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp).testTag("c3:ntfy:${g.key}:state"))
                // Clear all — the group-level twin of a swipe.
                Icon(painterResource(R.drawable.ic_check_all), contentDescription = stringResource(R.string.notif_mark_group_read),
                    modifier = Modifier.padding(start = 8.dp).size(18.dp).graphicsLayer { alpha = 0.55f }
                        .clickable {
                            Haptics.tap(view)
                            val shown = result?.takeIf { it.ok }?.let { shownRows(it, limit) }.orEmpty()
                            for (r in shown) if (r.id.isNotBlank()) readIds = StackFilters.markRead(ctx, filterPage, r.id, true)
                        })
                // Archive — the tick's twin, one level up: about the CHANNEL, not the rows.
                ArchivePill(g.key)
                val turn by animateFloatAsState(if (open) 90f else 0f, label = "c3-group-chevron")
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp).graphicsLayer { alpha = 0.5f }.rotate(turn))
            }
            if (open && result != null && result.ok) {
                for (r in shownRows(result, limit)) NotifRowView(r)
            }
        }
    }

    // ── archived channel boxes ─────────────────────────────────────────
    //
    // Archived state is a StackFilters SELECTION under this page id: same prefs file, same
    // "<page>/<id>" key shape, same commit() discipline. The id carries the `__`
    // page-setting prefix; there is one key per box, discovered at runtime from what
    // exists, so the yes/no option set is supplied here rather than declared.

    private fun yesNo(id: String) = Stacks.StackFilter(
        id      = id,
        label   = "",
        default = "no",
        options = listOf(Stacks.FilterOption("yes", "yes"), Stacks.FilterOption("no", "no")),
    )

    private fun isArchived(ctx: Context, key: String): Boolean =
        StackFilters.selected(ctx, filterPage, yesNo(ARCHIVED_PREFIX + key)) == "yes"

    private fun setArchived(ctx: Context, key: String, archived: Boolean) {
        StackFilters.select(ctx, filterPage, ARCHIVED_PREFIX + key, if (archived) "yes" else "no")
    }

    /** The Archive control on a box's header. A WORD, not a glyph, and it MOVES THE BOX
     *  immediately — writing the preference alone would read as a dead button. */
    @Composable
    private fun ArchivePill(key: String) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val isIn = key in archived
        Text(
            if (isIn) ARCHIVE_RESTORE_LABEL else ARCHIVE_LABEL,
            color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).background(Color(0x22FFFFFF), RoundedCornerShape(9.dp))
                .testTag("c3:ntfy:$key:archive")
                .clickable {
                    Haptics.tap(view)
                    val now = key !in archived
                    setArchived(ctx, key, now)
                    archived = if (now) archived + key else archived - key
                }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }

    /** The page's Archive section: one collapsed line over the boxes the user filed away.
     *  Hidden entirely while it holds nothing. */
    @Composable
    private fun ArchiveSection(panels: List<Stacks.StackPanel>) {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        // Every archived box, with the card limit of the panel that draws it.
        val boxes = panels.filter { it.kind == "notification_center" }
            .flatMap { panel -> ntfyTopics(panel).filter { it in archived }.map { it to panel.limit } }
        if (boxes.isEmpty()) return
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Archive  ${if (archiveOpen) "▾" else "▸"}  ${boxes.size}", color = p.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("c3:stack:archive")
                    .clickable {
                        Haptics.tap(view)
                        archiveOpen = !archiveOpen
                        StackFilters.select(ctx, filterPage, FILTER_ARCHIVE_OPEN, if (archiveOpen) "yes" else "no")
                    }.padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 6.dp))
            if (archiveOpen) for ((topic, limit) in boxes) GroupBlock(groupOf(topic), limit)
        }
    }

    /** A coloured monogram per channel — an ntfy topic has no launcher icon, and a shade
     *  with a blank column where every icon should be reads as broken rather than as
     *  cloud. Tapping opens the channel's web page through the dispatcher. */
    @Composable
    private fun GroupAvatar(g: NotifGroup) {
        val tap = if (g.url.isBlank()) Modifier else Modifier.clickable { onTargetClicked(g.url) }
        Box(Modifier.size(30.dp).background(monogramColor(g.key), CircleShape).then(tap), contentAlignment = Alignment.Center) {
            Text(g.label.trim().take(1).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    /** A stable colour per publisher, derived from the grouping key, so the same topic
     *  keeps the same monogram between visits and no colour table has to be maintained
     *  alongside the channel catalog. */
    private fun monogramColor(key: String): Color {
        val hues = longArrayOf(0xFF6D5AE6, 0xFF1F8A70, 0xFFB5556D, 0xFF2C6FB5, 0xFF9A6A2F, 0xFF4E7A2A)
        return Color(hues[((key.hashCode() % hues.size) + hues.size) % hues.size])
    }

    /**
     * One notification as a shade ROW: a status stripe, then title and relative time on
     * one line with the snippet under it. Dense on purpose. Unread is said three times
     * over: the stripe lights up, the title goes bold, and the row takes a faint tint.
     * READ IS A SWIPE: sideways, either direction, flips its read state.
     */
    @Composable
    private fun NotifRowView(r: NotifRow) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val offset = remember { Animatable(0f) }
        var width by remember { mutableIntStateOf(1) }
        val slop = with(LocalDensity.current) { 64.dp.toPx() }
        val unread = isUnread(r)

        // All three unread cues in one place, so a repaint after a swipe cannot set two of
        // them and forget the third.
        val rowTint = when {
            r.severity == "error" -> Color(0x33B91C1C)
            r.severity == "warn"  -> Color(0x33D97706)
            unread                -> Color(0x14FFFFFF)
            else                  -> Color.Transparent
        }
        val stripe = when {
            r.severity == "error" -> Color(0xFFFF6B6B)
            r.severity == "warn"  -> Color(0xFFFFB020)
            unread                -> Color(0xFF7C5CFF)
            else                  -> Color.Transparent
        }

        /** Slide out towards [toRight], persist the flipped state, then bring it back from
         *  the opposite edge already repainted. */
        fun flip(toRight: Boolean) {
            scope.launch {
                val makeRead = isUnread(r)
                val out = (if (toRight) 1f else -1f) * width
                offset.animateTo(out, androidx.compose.animation.core.tween(150))
                readIds = StackFilters.markRead(ctx, filterPage, r.id, makeRead)
                offset.snapTo(-out)
                offset.animateTo(0f, androidx.compose.animation.core.tween(170))
            }
        }
        fun settle() { scope.launch { offset.animateTo(0f, androidx.compose.animation.core.tween(140)) } }

        Column(
            Modifier.fillMaxWidth().testTag("c3:ntfy:row:${r.id.ifBlank { r.title }}")
                .onSizeChanged { width = max(1, it.width) }
                .graphicsLayer {
                    translationX = offset.value
                    alpha = 1f - (abs(offset.value) / (width * 2f)).coerceIn(0f, 0.55f)
                }
                .then(if (r.id.isBlank()) Modifier else Modifier.pointerInput(r.id) {
                    // Only a clearly sideways drag is claimed, so vertical scrolling is preserved.
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > max(slop, width * 0.22f)) flip(total > 0) else settle()
                        },
                        onDragCancel = { settle() },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            total += dx
                            scope.launch { offset.snapTo(offset.value + dx) }
                        },
                    )
                }),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(rowTint).padding(top = 7.dp, end = 10.dp, bottom = 7.dp)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title, color = if (unread) Color(0xFFF2E9FF) else Color(0xBBE9D8FD), fontSize = 13.sp,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(ago(System.currentTimeMillis() - r.ts), color = Color(0x77FFFFFF), fontSize = 10.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    if (r.text.isNotBlank()) {
                        Text(r.text, color = if (unread) Color(0xAAE9D8FD) else Color(0x77E9D8FD), fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x11FFFFFF)))
        }
    }

    /** A card-level verdict line, for the states that apply to a whole stream. */
    @Composable
    private fun StateLine(text: String, color: Color) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000     -> "just now"
        ms < 3_600_000  -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else            -> "${ms / 86_400_000}d ago"
    }

    /** Mini-tile index row — the card look and the wrap-at-tile_columns layout live in
     *  [IndexTiles]. openUrlOrTarget, not the listener directly, so `anchor:` tiles
     *  scroll this page instead of being handed to the navigating dispatcher. */
    @Composable
    private fun TileRow(tiles: List<Stacks.AggTile>) {
        val ctx = LocalContext.current
        IndexTiles.Grid(
            Stacks.tileColumns(),
            tiles.map { tile ->
                IndexTiles.Cell(
                    label   = tile.label,
                    iconRes = Stacks.iconResFor(ctx, tile.iconName),
                    onClick = { openUrlOrTarget(tile.target) },
                )
            },
        )
    }

    // ── kind=cloud_dashboard ───────────────────────────────────────────

    /** The anchor id [panel] declared for the header identified by [groupId] + [subLabel]
     *  (blank [subLabel] = the group header itself), or null when the panel declared none
     *  — an undeclared header is simply not an anchor target, which is what makes
     *  build.json the whole truth. */
    private fun declaredAnchor(
        panel: Stacks.StackPanel, groupId: String, subLabel: String,
    ): String? = panel.anchors
        .firstOrNull { it.group == groupId && it.subgroup == subLabel }?.id

    private data class CloudTile(
        val label: String, val icon: String, val openUrl: String,
        val showLight: Boolean, val ping: Pair<String, Int>?,
        /** Container name, the key every declarative lookup and every ops route is
         *  addressed by. Blank for a provider console. */
        val name: String = "",
    )

    /** The tiles of one sub-table or provider list, by the rules the dashboard has always had. */
    private fun containerTiles(sub: Stacks.CloudSub): List<CloudTile> = sub.containers.map {
        when {
            // Explicit open-URL (e.g. VM dashboard) — open it verbatim but
            // still light up from the url:port ping.
            it.link.isNotBlank() -> CloudTile(it.label, sub.icon, it.link, showLight = true,
                ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
            it.external -> CloudTile(it.label, sub.icon, it.url, showLight = false, ping = null,
                name = it.name)
            else -> CloudTile(it.label, sub.icon, "https://${it.url}", showLight = true,
                ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
        }
    }

    /** The TCP-ping lights of a card's tiles, at most eight in flight (green up / red down). */
    private suspend fun pingAll(panel: Stacks.StackPanel) {
        val dash = Stacks.cloudServices()
        val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
        val pings = dash.groups.filter { it.id in wanted }
            .flatMap { g -> g.subgroups.flatMap { containerTiles(it) } }
            .mapNotNull { it.ping }.distinct()
        val gate = Semaphore(8)
        coroutineScope {
            for ((host, port) in pings) launch {
                gate.withPermit {
                    val up = withContext(Dispatchers.IO) {
                        try {
                            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1200); true }
                        } catch (_: Throwable) { false }
                    }
                    lights["$host:$port"] = up
                }
            }
        }
    }

    /** kind=cloud_dashboard — live container map. Decodes the baked service inventory
     *  (containers-declared.json via ContainersSource: the API copy, else the baked one), buckets every
     *  container by category into the card's dashGroups, draws a TCP-ping status dot per
     *  container (green up / red down / grey checking, meaningful only over WireGuard) and
     *  opens [ContainerSheet] on tap. Provider groups render external consoles (no ping). */
    @Composable
    private fun CloudDashboard(index: Int, panel: Stacks.StackPanel) {
        val dash = remember(ContainersSource.version, projectId) { Stacks.cloudServices(projectId) }
        val cols = Stacks.tileColumns()
        val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
        val groups = dash.groups.filter { it.id in wanted }
        // Only label the group inside the card when one card shows >1 group.
        val showGroupHeader = groups.size > 1
        for (group in groups) {
            if (showGroupHeader) {
                // The card's sub-tables are anchor targets in their own right
                // (`anchor:stack/providers`). Only ids the panel DECLARED are registered.
                val id = declaredAnchor(panel, group.id, "")
                GroupHeader(group.label, if (id != null) anchors.mark(id) else Modifier)
            }
            if (group.providers.isNotEmpty()) {
                CloudGrid(cols, group.providers.map {
                    CloudTile(it.label, group.icon, it.url, showLight = false, ping = null)
                })
            }
            for (sub in group.subgroups) {
                if (sub.containers.isEmpty()) continue
                // Subgroups carry no id in cloud_services.json, so the panel's declaration
                // names the label it binds to.
                val id = declaredAnchor(panel, group.id, sub.label)
                SubHeader(sub.label, if (id != null) anchors.mark(id) else Modifier)
                CloudGrid(cols, containerTiles(sub))
            }
        }
    }

    /** Wrap-flowing `cols`-column grid of cloud tiles. */
    @Composable
    private fun CloudGrid(cols: Int, tiles: List<CloudTile>) {
        for (row in tiles.chunked(cols.coerceAtLeast(1))) {
            Row(Modifier.fillMaxWidth()) {
                for (t in row) Box(Modifier.weight(1f)) { CloudTileCell(t) }
                repeat(cols.coerceAtLeast(1) - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }

    /** Icon + STATUS LIGHT (under the icon) + label tile. Tap opens [ContainerSheet] —
     *  Infos / Actions for the box and the app inside it; a provider console has no
     *  container behind it and still opens its URL. */
    @Composable
    private fun CloudTileCell(t: CloudTile) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        Column(
            Modifier.fillMaxWidth().testTag("c3:cloud:${t.label}")
                .clickable {
                    if (t.name.isNotBlank()) sheet = ContainerSheet.Target(t.name, t.label, t.openUrl)
                    else openUrlOrTarget(t.openUrl)
                }.padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val resId = remember(t.icon) { Stacks.iconResFor(ctx, t.icon) }
            if (resId != 0) Image(painterResource(resId), contentDescription = null, Modifier.size(28.dp),
                colorFilter = ColorFilter.tint(p.accent))
            if (t.showLight) {
                val up = t.ping?.let { (host, port) -> lights["$host:$port"] }
                Box(Modifier.padding(top = 3.dp).size(8.dp).background(
                    when (up) { true -> Color(0xFF34C759); false -> Color(0xFFFF3B30); null -> Color(0x66FFFFFF) }, CircleShape))
            }
            Text(t.label, color = p.textPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
        }
    }

    @Composable
    private fun GroupHeader(text: String, modifier: Modifier) {
        val p = LocalKitPalette.current
        Text(text, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.padding(top = 14.dp, bottom = 4.dp))
    }

    @Composable
    private fun SubHeader(text: String, modifier: Modifier) {
        val p = LocalKitPalette.current
        Text(text, color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
    }

    // ── pull-down to refresh ───────────────────────────────────────────

    /**
     * The gesture's whole job: re-ask every source this page has, then SAY what came back.
     * The watermark is deliberately NOT advanced here — moving it on a refresh would clear
     * every "N new" chip at the exact moment the refresh had earned them. Neither is
     * [ntfyCache] cleared, though every channel IS re-polled: the cache is also the
     * ordering key for Sort=Time.
     */
    private fun startRefresh(scope: kotlinx.coroutines.CoroutineScope) {
        val cards = stackPanels.filter { it.kind == "notification_center" && ntfyTopics(it).isNotEmpty() }
        if (cards.isEmpty()) {
            refreshing = false
            return
        }
        refreshBefore = HashSet(pageRowIds)
        pageRowIds.clear()
        refreshPending = cards.size
        refreshing = true
        // Every card's poll effect is keyed by this: bumping it re-runs them, forced.
        forcedKey = refreshKey + 1
        refreshKey += 1
        // WATCHDOG. A poll that never settles is a spinner that never stops. It waits on
        // the SAME scope the polls do — cancelled with the view, it can only fire while
        // there is still a spinner to stop.
        scope.launch {
            delay(REFRESH_TIMEOUT_MS)
            if (refreshing) {
                refreshPending = 0
                finishRefresh(timedOut = true)
            }
        }
    }

    /** One channel poll landed, or failed to be scheduled. */
    private fun ntfyPollSettled() {
        if (refreshPending > 0) refreshPending--
        if (refreshPending == 0 && refreshing) finishRefresh(timedOut = false)
    }

    /**
     * Stop the spinner and state the OUTCOME — which is a different claim from "done". A
     * refresh that fetched nothing must not read like one that fetched something, and a
     * channel we could not reach is not a channel that was quiet: grey wins whenever any
     * part of the answer is unknown.
     */
    private fun finishRefresh(timedOut: Boolean) {
        refreshing = false
        val ctx = context ?: return
        val fresh = pageRowIds.count { it !in refreshBefore }
        val unreachable = ntfyCache.count { !it.value.ok }
        val at = android.text.format.DateFormat.getTimeFormat(ctx)
            .format(java.util.Date())
        val outcome = when {
            timedOut  -> "did not finish · sources did not answer"
            fresh > 0 -> "$fresh new"
            else      -> "nothing new"
        }
        val notReached = if (unreachable > 0)
            " · $unreachable channel${if (unreachable == 1) "" else "s"} not reached" else ""
        val color = when {
            timedOut || unreachable > 0 -> SIGNAL_UNKNOWN
            fresh > 0                   -> SIGNAL_OK
            else                        -> Color(0x88FFFFFF)
        }
        // One line, reused rather than appended.
        refreshNote = "Refreshed $at · $outcome$notReached" to color
    }

    // ── Click dispatch ─────────────────────────────────────────────────

    /**
     * Targets follow the SuperApp's grammar: `anchor:` never leaves the page — it scrolls
     * this stack to the panel (or dashboard sub-table) that claimed the id, checked before
     * anything else because it is not a URI. Everything else bubbles to the host activity.
     */
    private fun openUrlOrTarget(target: String) {
        when {
            target.isEmpty() -> Unit
            anchors.dispatch(target) -> Unit
            else -> onTargetClicked(target)
        }
    }

    /** Forward non-anchor targets to the activity-level dispatcher. */
    private fun onTargetClicked(target: String) {
        (activity as? TargetListener)?.onTargetClicked(target)
    }

    override fun onDestroyView() {
        pageRowIds.clear()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STACK = "stack"
        private const val ARG_PROJECT = "project"
        const val STACK_TOPOLOGY = "stack_topology"
        const val STACK_OBSERVABILITY = "stack_observability"

        /** Archived-box keys, and the Archive section's own open/closed flag. Both
         *  `__`-prefixed page settings, stored by [StackFilters] under the page id. */
        private const val ARCHIVED_PREFIX     = "__archived/"
        private const val FILTER_ARCHIVE_OPEN = "__archive_open"
        private const val ARCHIVE_LABEL         = "Archive"
        private const val ARCHIVE_RESTORE_LABEL = "Restore"

        /** Comfortably past the poll's own 4s connect timeout, so a slow-but-alive mesh
         *  reports its real answer rather than a timeout. */
        private const val REFRESH_TIMEOUT_MS = 20_000L

        private const val TAG = "C3Stack"

        /** Grey = we do not know. Deliberately NOT red: red is a claim about the fleet,
         *  and a failed fetch is a claim about this phone's network. */
        private val SIGNAL_UNKNOWN = Color(0xFF9E9E9E)
        private val SIGNAL_OK      = Color(0xFF34C759)
        private val SIGNAL_WARN    = Color(0xFFFFB020)
        private val SIGNAL_OK_TEXT  = Color(0xFF7BE38B)
        private val SIGNAL_BAD_TEXT = Color(0xFFFF8888)

        /** One namespace PER TOPIC: an ntfy poll is only ever complete for the topic it
         *  polled, so that is the largest set a successful poll is entitled to prune. */
        private fun ntfyNs(topic: String) = "ntfy:$topic:"

        fun newInstance(stackId: String, project: String? = null): C3StackFragment =
            C3StackFragment().apply {
                arguments = Bundle().apply { putString(ARG_STACK, stackId); project?.let { putString(ARG_PROJECT, it) } }
            }
    }
}

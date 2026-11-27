package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.cloudc3.cloud.DaguRunsFeed
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Test tags of the #1954 Orch pages. */
object OrchTags {
    const val HOME = "orch_home"
    const val SUMMARY = "orch_home_summary"
    const val REFRESH = "orch_home_refresh"
    const val LANES = "orch_lanes"
    const val WORKFLOWS = "orch_workflows"
    const val DEPLOYS = "orch_deploys"
    fun stage(id: String) = "orch_stage_$id"
    fun stageOpen(id: String) = "orch_stage_open_$id"
    fun stageItem(id: String, n: Int) = "orch_stage_item_${id}_$n"
    fun lane(pipeline: String, stage: String) = "orch_lane_${pipeline}_$stage"
    fun wfRepo(repo: String) = "orch_wf_repo_$repo"
    fun wf(repo: String, name: String) = "orch_wf_${repo}_$name"
    fun deploy(n: Int) = "orch_deploy_$n"
    fun deployService(s: String) = "orch_deploy_service_$s"
}

/** The live half of the three pipeline pages, read once per 60 s and shared (Home, Workflows and Deploy history open on the same read). Every failure is a named reason. */
class OrchLiveState {
    var inputs by mutableStateOf<OrchFlow.Inputs?>(null)
    var loading by mutableStateOf(false)
    var at by mutableStateOf(0L)

    suspend fun refresh(ctx: Context, force: Boolean = false) {
        if (loading || (!force && at > 0 && System.currentTimeMillis() - at < TTL_MS)) return
        loading = true
        try { inputs = OrchLive.load(ctx.applicationContext, Declarations.orchFlow, DEPLOY_LIMIT); at = System.currentTimeMillis() } finally { loading = false }
    }

    companion object {
        private const val TTL_MS = 60_000L
        const val DEPLOY_LIMIT = 100
        val shared = OrchLiveState()
    }
}

/** Reads the sources the Orch pages already use: GitHubFeed runs and releases (15-minute cache), DaguRunsFeed, c3-infra-api /db/deploy/history and /health/deployed/:vm. */
object OrchLive {
    private fun <T> feed(f: GitHubFeed.Feed<T>): HomeLive.Live<List<T>> =
        if (f.status != GitHubFeed.Status.OK && f.items.isEmpty()) HomeLive.Live.Failed(if (f.status == GitHubFeed.Status.RATE_LIMITED) "GitHub rate limit reached" else "GitHub unreachable")
        else HomeLive.Live.Ok(f.items)

    suspend fun load(ctx: Context, d: OrchFlow.Decl, deployLimit: Int): OrchFlow.Inputs = coroutineScope {
        val owner = Declarations.topology.release?.owner?.takeIf { it.isNotBlank() } ?: "diegonmarcos"
        val repos = d.pipelines.map { it.repo }.filter { it.isNotBlank() }.distinct()
        val api = ApiFleetLive(ctx)
        val runs = repos.map { r -> async { r to feed(GitHubFeed.runs(ctx, owner, r, perPage = d.runsPerRepo)) } }
        val rels = repos.map { r -> async { r to feed(GitHubFeed.releases(ctx, owner, r, perPage = 5)) } }
        val dagu = async<HomeLive.Live<List<DaguRunsFeed.Run>>> {
            runCatching {
                val prefs = com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx)
                DaguRunsFeed.recentRuns(prefs.serverUrl, prefs.bearerToken, 50)
            }.fold({ HomeLive.Live.Ok(it) }, { HomeLive.Live.Failed(it.message ?: it.javaClass.simpleName) })
        }
        val deploys = async<HomeLive.Live<List<OrchFlow.Deploy>>> {
            when (val r = api.get("/db/deploy/history?limit=$deployLimit")) {
                is FleetLive.Result.Body -> OrchFlow.parseDeploys(r.text)?.let { HomeLive.Live.Ok(it) } ?: HomeLive.Live.Failed("unreadable answer from /db/deploy/history")
                is FleetLive.Result.Failed -> HomeLive.Live.Failed(r.why)
            }
        }
        val running = async {
            val slice = Declarations.fleet
            runCatching { ContainersSource.refresh(api) }
            runCatching { TopologyLiveState.shared.refresh(slice, api, { emptyList<TopologyModel.Run>() to null }, force = true) }
            OrchFlow.running(TopologyModel.Inputs(ContainersSource.current(), slice, TopologyModel.parseInfra(Declarations.infraSlice), TopologyLiveState.shared.live, Declarations.topology))
        }
        val pkgs = OrchRegistries.packages(ContainersSource.current().entries.map { it.image }, Declarations.containerProjects.repos.filter { it.role == "repo" }.map { it.name }).size
        OrchFlow.Inputs(
            runs = runs.map { it.await() }.toMap(), releases = rels.map { it.await() }.toMap(),
            dagu = dagu.await(), deploys = deploys.await(), running = running.await(),
            reposDeclared = Declarations.containerProjects.repos.size, ghcrPackages = pkgs, nowMs = System.currentTimeMillis(),
        )
    }
}

@Composable
private fun orchColor(h: TopologyModel.Health): Color {
    val p = LocalKitPalette.current
    return when (h) {
        TopologyModel.Health.OK -> p.ok
        TopologyModel.Health.WARN -> p.warn
        TopologyModel.Health.BAD -> p.bad
        else -> p.textSecondary
    }
}

/** A glyph beside the colour, so colour is never the only signal. */
fun orchGlyph(h: TopologyModel.Health): String = when (h) {
    TopologyModel.Health.OK -> "o"
    TopologyModel.Health.WARN -> "!"
    TopologyModel.Health.BAD -> "x"
    TopologyModel.Health.UNKNOWN -> "?"
    TopologyModel.Health.NONE -> "-"
}

private fun orchClock(ms: Long): String = if (ms <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

/**
 * Orch > Home (#1954): the pipeline flow, one numbered row per declared stage with its live status and headline, a tap lists the stage's items in place
 * and "open" goes to the page that owns the stage; under it the lane matrix (rows = declared pipelines, columns = stages, sideways scroll). Pure of Android.
 */
@Composable
fun OrchHomeScreen(decl: OrchFlow.Decl, reading: OrchFlow.Reading?, loading: Boolean, at: Long, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    val p = LocalKitPalette.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag(OrchTags.HOME)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(
                (reading?.summary() ?: "Pipeline  reading...") + if (at > 0) "   updated ${orchClock(at)}" else "",
                color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.weight(1f).testTag(OrchTags.SUMMARY),
            )
            Text(if (loading) "Reading..." else "Refresh", color = p.accent, fontSize = KitDensity.caption,
                modifier = Modifier.clickable(enabled = !loading, onClick = onRefresh).padding(6.dp).testTag(OrchTags.REFRESH))
        }
        decl.stages.forEachIndexed { n, st ->
            val sr = reading?.stages?.firstOrNull { it.stage.id == st.id }
            val h = sr?.health ?: TopologyModel.Health.UNKNOWN
            if (n > 0) Text("v", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 14.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, p.hairline, RoundedCornerShape(6.dp)).testTag(OrchTags.stage(st.id))) {
                Row(Modifier.fillMaxWidth().clickable { expanded[st.id] = expanded[st.id] != true }.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${n + 1} ${st.label.uppercase()}", color = p.textPrimary, fontSize = KitDensity.body)
                        Text(sr?.text ?: "reading...", color = orchColor(h), fontSize = KitDensity.caption)
                    }
                    Text(orchGlyph(h), color = orchColor(h), fontSize = KitDensity.body, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(">", color = p.accent, fontSize = KitDensity.body,
                        modifier = Modifier.clickable { onOpen(st.open) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag(OrchTags.stageOpen(st.id)))
                }
                if (expanded[st.id] == true) sr?.items.orEmpty().forEachIndexed { k, item ->
                    Text("${orchGlyph(item.health)} ${item.label}  ${item.detail}", color = orchColor(item.health), fontSize = KitDensity.caption,
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, bottom = 2.dp).testTag(OrchTags.stageItem(st.id, k)))
                }
                if (expanded[st.id] == true && sr?.items.isNullOrEmpty()) Text("nothing to list", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
            }
        }
        Text("Pipelines", color = p.accent, fontSize = KitDensity.body, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        Column(Modifier.horizontalScroll(scroll).testTag(OrchTags.LANES)) {
            Row {
                Text("pipeline", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.width(90.dp).padding(2.dp))
                decl.stages.forEach { Text(it.label, color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.width(120.dp).padding(2.dp)) }
            }
            decl.pipelines.forEach { pl ->
                Row {
                    Text(pl.label, color = p.textPrimary, fontSize = KitDensity.caption, modifier = Modifier.width(90.dp).padding(2.dp))
                    decl.stages.forEach { st ->
                        val c = if (st.id in pl.stages) reading?.cells?.firstOrNull { it.pipeline == pl.id && it.stage == st.id } else null
                        Box(Modifier.width(120.dp).padding(2.dp).let { m -> if (st.id in pl.stages) m.testTag(OrchTags.lane(pl.id, st.id)) else m }) {
                            Text(if (st.id !in pl.stages) "" else if (c == null) "..." else "${orchGlyph(c.health)} ${c.text}", color = orchColor(c?.health ?: TopologyModel.Health.NONE), fontSize = KitDensity.caption, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** Opens a declared page from a pipeline page: on this tab when it has it, else through the shell (the Containers pages live on another tab). */
internal fun openDeclaredPage(host: PagedFragment?, activity: android.app.Activity?, page: String) {
    if (host != null && host.hasPage(page)) host.openPage(page)
    else (activity as? C3StackFragment.TargetListener)?.onTargetClicked("page:c3/$page")
}

/** Reads [OrchLiveState] when a pipeline page opens and offers a forced re-read. */
@Composable
private fun rememberOrchLive(): Pair<OrchLiveState, () -> Unit> {
    val ctx = LocalContext.current
    val state = OrchLiveState.shared
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { state.refresh(ctx) } }
    return state to { scope.launch { runCatching { state.refresh(ctx, force = true) } } ; Unit }
}

class OrchHomeFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val decl = remember { Declarations.orchFlow }
        val (state, reread) = rememberOrchLive()
        val reading = remember(state.inputs) { state.inputs?.let { OrchFlow.fold(decl, it) } }
        OrchHomeScreen(decl, reading, state.loading, state.at, { openDeclaredPage(parentFragment as? PagedFragment, activity, it) }, reread)
    }

    companion object { fun newInstance() = OrchHomeFragment() }
}

/** Orch > Workflows: every GitHub Actions workflow of the pipeline repos with its last run (the distinct run names of the runs feed), a tap lists its recent runs; then the Dagu DAGs. */
@Composable
fun OrchWorkflowsScreen(decl: OrchFlow.Decl, inputs: OrchFlow.Inputs?, onUrl: (String) -> Unit) {
    val p = LocalKitPalette.current
    val open = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag(OrchTags.WORKFLOWS)) {
        if (inputs == null) Text("reading...", color = p.textSecondary, fontSize = KitDensity.caption)
        val now = inputs?.nowMs ?: 0L
        decl.pipelines.filter { it.repo.isNotBlank() }.map { it.repo }.distinct().forEach { repo ->
            val live = inputs?.runs?.get(repo)
            val runs = live?.let { OrchFlow.okOrNull(it) }.orEmpty()
            val wfs = OrchFlow.newestPerWorkflow(runs)
            Text((if (open[repo] == false) "> " else "v ") + "$repo (${wfs.size} workflows)" + ((live as? HomeLive.Live.Failed)?.let { " - ${it.why}" } ?: ""), color = p.accent, fontSize = KitDensity.body,
                modifier = Modifier.fillMaxWidth().clickable { open[repo] = open[repo] == false }.padding(vertical = 6.dp).testTag(OrchTags.wfRepo(repo)))
            if (open[repo] != false) wfs.forEach { w ->
                val h = OrchFlow.runHealth(w)
                val key = "$repo/${w.name}"
                Text("${orchGlyph(h)} ${w.name}  ${w.conclusion.ifBlank { w.status }}  ${GitHubFeed.ago(maxOf(0L, now - w.tsMillis))}  ${w.displayTitle}", color = orchColor(h), fontSize = KitDensity.caption,
                    modifier = Modifier.fillMaxWidth().clickable { open[key] = open[key] != true }.padding(start = 14.dp, top = 2.dp, bottom = 2.dp).testTag(OrchTags.wf(repo, w.name)))
                if (open[key] == true) runs.filter { it.name == w.name }.take(8).forEach { r ->
                    Text("${orchGlyph(OrchFlow.runHealth(r))} ${r.conclusion.ifBlank { r.status }}  ${GitHubFeed.ago(maxOf(0L, now - r.tsMillis))}  ${r.displayTitle}", color = orchColor(OrchFlow.runHealth(r)), fontSize = KitDensity.caption,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = r.htmlUrl.isNotBlank()) { onUrl(r.htmlUrl) }.padding(start = 30.dp, top = 1.dp, bottom = 1.dp))
                }
            }
        }
        val dagu = inputs?.dagu
        Text("Dagu DAGs" + ((dagu as? HomeLive.Live.Failed)?.let { " - ${it.why}" } ?: ""), color = p.accent, fontSize = KitDensity.body, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        dagu?.let { OrchFlow.okOrNull(it) }?.let { rs ->
            rs.groupBy { it.name }.values.map { g -> g.maxByOrNull { it.startedAtMs }!! }.forEach { r ->
                val h = OrchFlow.daguHealth(r)
                Text("${orchGlyph(h)} ${r.name}  status ${r.status}  ${GitHubFeed.ago(maxOf(0L, now - r.startedAtMs))}", color = orchColor(h), fontSize = KitDensity.caption, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
            }
        }
    }
}

class OrchWorkflowsFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val (state, _) = rememberOrchLive()
        OrchWorkflowsScreen(Declarations.orchFlow, state.inputs) { (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(it) }
    }

    companion object { fun newInstance() = OrchWorkflowsFragment() }
}

/** Orch > Deploys: the deploy history of c3-infra-api (ts, service, step, result, duration), newest first, filtered by service. */
@Composable
fun OrchDeploysScreen(inputs: OrchFlow.Inputs?) {
    val p = LocalKitPalette.current
    var service by remember { mutableStateOf("") }
    val live = inputs?.deploys
    val rows = (live?.let { OrchFlow.okOrNull(it) }.orEmpty()).sortedByDescending { it.tsMillis }
    val services = rows.map { it.service }.distinct().sorted()
    val shown = rows.filter { service.isBlank() || it.service == service }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp).testTag(OrchTags.DEPLOYS)) {
        when {
            inputs == null || live == null || live is HomeLive.Live.Pending -> Text("reading...", color = p.textSecondary, fontSize = KitDensity.caption)
            live is HomeLive.Live.Failed -> Text("deploy history unavailable: ${live.why}", color = p.warn, fontSize = KitDensity.caption)
            rows.isEmpty() -> Text("no deploy recorded", color = p.textSecondary, fontSize = KitDensity.caption)
        }
        if (services.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            (listOf("") + services).forEach { s ->
                Text(if (s.isBlank()) "All" else s, color = if (s == service) p.tileInk else p.textPrimary, fontSize = KitDensity.caption,
                    modifier = Modifier.padding(end = 6.dp).background(if (s == service) p.accent else p.surface, RoundedCornerShape(6.dp)).border(1.dp, p.hairline, RoundedCornerShape(6.dp))
                        .clickable { service = s }.padding(horizontal = 10.dp, vertical = 4.dp).testTag(OrchTags.deployService(s.ifBlank { "all" })))
            }
        }
        if (rows.isNotEmpty()) Text("${shown.size} of ${rows.size} deploys", color = p.textSecondary, fontSize = KitDensity.caption)
        shown.forEachIndexed { n, d ->
            val h = if (d.ok) TopologyModel.Health.OK else TopologyModel.Health.BAD
            val whenText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(d.tsMillis))
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(OrchTags.deploy(n))) {
                Text("${orchGlyph(h)} $whenText  ${d.service}  ${d.step}  ${if (d.ok) "ok" else "FAILED"}  ${d.durationMs} ms", color = orchColor(h), fontSize = KitDensity.caption)
                if (d.output.isNotBlank()) Text(d.output.lineSequence().first().take(160), color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 14.dp))
            }
        }
    }
}

class OrchDeploysFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val (state, _) = rememberOrchLive()
        OrchDeploysScreen(state.inputs)
    }

    companion object { fun newInstance() = OrchDeploysFragment() }
}

/** What each mode of the merged GH Releases page draws (#1954): Table is the per-repo release table, Graph is the release topology view (last Ship runs, then VM > service > declared vs running tag). */
object GhReleasesViewer {
    const val TABLE = "table"
    const val GRAPH = "graph"
    val MODES = listOf(TABLE, GRAPH)
    const val PAGE = "orch_gh_releases"
}

/** Orch > GH Releases: one page, [Table | Graph]. Table is the release table of every declared repo, Graph is the release topology view (the former Release page). */
class GhReleasesViewerFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val prefs = remember { NetViewerPrefs(ctx.applicationContext) }
        val src = remember { OrchRegistries.Live(ctx.applicationContext) }
        var mode by remember { mutableStateOf(prefs.get(GhReleasesViewer.PAGE, GhReleasesViewer.TABLE)) }
        Column(Modifier.fillMaxSize()) {
            ViewerSwitch(GhReleasesViewer.PAGE, GhReleasesViewer.MODES, mode) { m -> mode = m; prefs.set(GhReleasesViewer.PAGE, m) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (mode == GhReleasesViewer.GRAPH) TopologyViewBody(TopologyModel.RELEASE, parentFragment as? PagedFragment, activity)
                else OrchReleasesScreen(Declarations.containerProjects.repos.map { it.name }, src)
            }
        }
    }

    companion object { fun newInstance() = GhReleasesViewerFragment() }
}

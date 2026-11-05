package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Test tags of the Ops topology pages. */
object TopologyTags {
    fun screen(view: String) = "topo_screen_$view"
    fun row(view: String, id: String) = "topo_row_${view}_$id"
    fun pivot(view: String, to: String) = "topo_pivot_${view}_$to"
    fun missing(view: String) = "topo_missing_$view"
    fun mode(view: String, mode: String) = "topo_mode_${view}_$mode"
    const val FOCUS = "topo_focus"
    const val FOCUS_CLEAR = "topo_focus_clear"
}

/** The entity the five views are currently opened on (a pivot from one view sets it, every view filters to it). */
object TopologyFocus {
    var key by mutableStateOf<String?>(null)
}

/**
 * The live half, shared by the five pages so one open costs one read: `docker ps` and disk per mesh VM (the same per-VM
 * c3-infra-api routes the Home down list and the Infra pages use) and the declared workflow's runs. Every failure is kept as a
 * note and the page says it; nothing absent is drawn as a zero.
 */
class TopologyLiveState {
    var live by mutableStateOf(TopologyModel.Live())
    private var loadedAt = 0L

    suspend fun refresh(slice: FleetModel.Slice, src: FleetLive, runs: suspend () -> Pair<List<TopologyModel.Run>, String?>, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < TTL_MS && live.deployed.isNotEmpty()) return
        coroutineScope {
            val vms = slice.vms.filter { it.wgIp != null }
            val dep = vms.map { v -> async { v to src.get("/health/deployed/${v.id}") } }
            val disk = vms.map { v -> async { v to src.get("/health/tier3/${v.id}") } }
            val runsJob = async { runs() }
            val deployed = dep.awaitAll().associate { (v, r) ->
                v.alias to when (r) {
                    is FleetLive.Result.Body -> TopologyModel.parseDeployed(v.id, r.text)
                    is FleetLive.Result.Failed -> TopologyModel.Deployed.Blind(r.why)
                }
            }
            val disks = mutableMapOf<String, FleetModel.DiskUsage>()
            disk.awaitAll().forEach { (_, r) -> if (r is FleetLive.Result.Body) disks.putAll(FleetModel.parseDisks(r.text)) }
            val (r, note) = runsJob.await()
            live = TopologyModel.Live(deployed, disks, r, note)
        }
        loadedAt = System.currentTimeMillis()
    }

    companion object {
        private const val TTL_MS = 60_000L
        val shared = TopologyLiveState()
    }
}

/** The declared workflow's recent runs from GitHub (unauthenticated, cached by [GitHubFeed]); a failure is a note, never an empty list that reads as "no runs". */
internal suspend fun releaseRuns(ctx: Context, src: TopologyModel.ReleaseSource?): Pair<List<TopologyModel.Run>, String?> {
    if (src == null) return emptyList<TopologyModel.Run>() to "ui.topology.release is not declared"
    val feed = GitHubFeed.runs(ctx, src.owner, src.repo, perPage = 30)
    val runs = feed.items.filter { it.name.contains(src.workflow, ignoreCase = true) }.take(8)
        .map { TopologyModel.Run(it.displayTitle.ifBlank { it.name }, it.status, it.conclusion, it.htmlUrl, it.tsMillis) }
    val note = when {
        feed.status == GitHubFeed.Status.RATE_LIMITED -> "GitHub rate limit reached" + if (runs.isEmpty()) "" else " (cached runs shown)"
        feed.status == GitHubFeed.Status.UNREACHABLE -> "GitHub unreachable" + if (runs.isEmpty()) "" else " (cached runs shown)"
        runs.isEmpty() -> "no ${src.workflow} runs among the latest 30 of ${src.repo}"
        else -> null
    }
    return runs to note
}

/** One topology page by its declared view id, over the inputs and a focus. Pure of Android so the JVM suite renders it. */
@Composable
fun TopologyScreen(
    view: String,
    inputs: TopologyModel.Inputs,
    focus: String?,
    labels: Map<String, String>,
    onFocus: (String?) -> Unit,
    onPivot: (view: String, key: String) -> Unit,
) {
    val p = LocalKitPalette.current
    val decl = inputs.decl.view(view)
    val tree = remember(view, inputs, focus) { TopologyModel.focus(TopologyModel.build(view, inputs), focus) }
    var list by rememberSaveable(view) { mutableStateOf(false) }
    var selected by remember(view) { mutableStateOf<String?>(null) }
    val open = remember(view) { mutableStateMapOf<String, Boolean>() }
    // ONE vertical scroller for the whole page: the tree and the list are plain columns inside it, never scrollers of their own.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp).testTag(TopologyTags.screen(view))) {
        decl?.let {
            Note("Grouped by: ${it.grouping}", p.textSecondary)
            Note("Dominant metric: ${it.metric}", p.textSecondary)
            Note("Question: ${it.question}", p.textSecondary)
            if (it.missing.isNotEmpty()) {
                Box(Modifier.testTag(TopologyTags.missing(view))) {
                    Note("No fleet endpoint provides yet: ${it.missing.joinToString(", ")}.", p.warn)
                }
            }
        }
        inputs.live.deployed.filterValues { it is TopologyModel.Deployed.Blind }.forEach { (vm, d) ->
            Note("Live read of $vm failed: ${(d as TopologyModel.Deployed.Blind).why}", p.warn)
        }
        if (inputs.live.deployed.isEmpty() && view != TopologyModel.NETWORK) Note("Live state not read yet: showing the declared fleet.", p.textSecondary)
        if (view == TopologyModel.RELEASE) inputs.live.runsNote?.let { Note("Runs: $it", p.warn) }
        if (focus != null) {
            Row(Modifier.fillMaxWidth().testTag(TopologyTags.FOCUS), verticalAlignment = Alignment.CenterVertically) {
                Text("Opened on $focus", color = p.accent, fontSize = KitDensity.caption, modifier = Modifier.weight(1f))
                Text("Show all", color = p.accent, fontSize = KitDensity.caption,
                    modifier = Modifier.clickable { onFocus(null) }.padding(6.dp).testTag(TopologyTags.FOCUS_CLEAR))
            }
        }
        Row(Modifier.padding(vertical = 3.dp)) {
            ModeTab(TopologyTags.mode(view, "tree"), "Tree", !list) { list = false }
            ModeTab(TopologyTags.mode(view, "list"), "List", list) { list = true }
        }
        if (tree.isEmpty()) Note(if (focus != null) "Nothing in this view for $focus." else "Nothing declared for this view.", p.textSecondary)
        val pick: (String) -> Unit = { id -> selected = if (selected == id) null else id }
        val pivots: @Composable (TopologyModel.Node) -> Unit = { n ->
            if (selected == n.id) PivotRow(view, n, labels, onPivot)
        }
        if (!list) {
            fun visible(nodes: List<TopologyModel.Node>, depth: Int): List<Pair<TopologyModel.Node, Int>> = nodes.flatMap { n ->
                val isOpen = open[n.id] ?: (depth < 2 || focus != null)
                listOf(n to depth) + if (isOpen) visible(n.children, depth + 1) else emptyList()
            }
            visible(tree, 0).forEach { (n, depth) ->
                val isOpen = open[n.id] ?: (depth < 2 || focus != null)
                NodeRow(view, n, depth, if (n.children.isEmpty()) null else isOpen, { open[n.id] = !isOpen }, { pick(n.id) })
                pivots(n)
            }
        } else {
            TopologyModel.flatten(tree).forEach { row ->
                NodeRow(view, row.node, 0, null, {}, { pick(row.node.id) }, path = row.path)
                pivots(row.node)
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
}

@Composable
private fun ModeTab(tag: String, label: String, on: Boolean, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(6.dp)
    Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitDensity.caption,
        modifier = Modifier.padding(end = 6.dp).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = 10.dp, vertical = 4.dp).testTag(tag))
}

@Composable
private fun healthColor(h: TopologyModel.Health): Color {
    val p = LocalKitPalette.current
    return when (h) {
        TopologyModel.Health.OK -> p.ok
        TopologyModel.Health.WARN -> p.warn
        TopologyModel.Health.BAD -> p.bad
        else -> p.textSecondary
    }
}

/** One node: a gutter with the tree's connector, an expand arrow when it has children, a state dot, the label and its metric. */
@Composable
private fun NodeRow(view: String, n: TopologyModel.Node, depth: Int, open: Boolean?, toggle: () -> Unit, select: () -> Unit, path: String = "") {
    val p = LocalKitPalette.current
    val line = p.hairline
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 14).dp, top = 1.dp, bottom = 1.dp)
            .drawBehind {
                if (depth > 0) {
                    val x = -7.dp.toPx()
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    drawLine(line, Offset(x, size.height / 2), Offset(0f, size.height / 2), 1.dp.toPx())
                }
            }
            .testTag(TopologyTags.row(view, n.id)),
        verticalAlignment = Alignment.Top,
    ) {
        Text(if (open == null) "  " else if (open) "v " else "> ", color = p.accent, fontSize = KitDensity.body,
            modifier = Modifier.clickable(enabled = open != null, onClick = toggle).padding(end = 2.dp))
        Column(Modifier.weight(1f).clickable(onClick = select)) {
            if (path.isNotBlank()) Text(path, color = p.textSecondary, fontSize = KitDensity.caption)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.health != TopologyModel.Health.NONE) Box(Modifier.padding(end = 5.dp).size(KitDensity.dot).background(healthColor(n.health), CircleShape))
                Text(n.label, color = p.textPrimary, fontSize = KitDensity.body)
            }
            if (n.metric.isNotBlank()) Text(n.metric, color = healthColor(n.health), fontSize = KitDensity.caption)
        }
    }
}

/** The pivot links of a selected node: the same entity, opened in each of the other views. */
@Composable
private fun PivotRow(view: String, n: TopologyModel.Node, labels: Map<String, String>, onPivot: (String, String) -> Unit) {
    val p = LocalKitPalette.current
    val key = n.pivot ?: return
    Row(Modifier.padding(start = 20.dp, bottom = 3.dp)) {
        Text("Open in", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(end = 6.dp, top = 4.dp))
        TopologyModel.pivots(n, view).forEach { to ->
            Text(labels[to] ?: to, color = p.accent, fontSize = KitDensity.caption,
                modifier = Modifier.padding(end = 6.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp))
                    .clickable { onPivot(to, key) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag(TopologyTags.pivot(view, to)))
        }
    }
}

/** One Ops page: the baked declarations, refreshed live when the page opens. A pivot sets the shared focus and selects the other view's page. */
class TopologyViewFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val view = remember { arguments?.getString(ARG_VIEW).orEmpty() }
        val ctx = LocalContext.current
        val slice = remember { Declarations.fleet }
        val decl = remember { Declarations.topology }
        val version = ContainersSource.version
        val state = TopologyLiveState.shared
        LaunchedEffect(Unit) {
            ContainersSource.refresh(ApiFleetLive(ctx.applicationContext))
            runCatching {
                state.refresh(slice, ApiFleetLive(ctx.applicationContext), { releaseRuns(ctx.applicationContext, decl.release) })
            }
        }
        val inputs = remember(version, state.live) {
            TopologyModel.Inputs(ContainersSource.current(), slice, TopologyModel.parseInfra(Declarations.infraSlice), state.live, decl)
        }
        TopologyScreen(
            view, inputs, TopologyFocus.key, decl.views.mapNotNull { v -> Declarations.opsViewPage(v.id)?.let { v.id to it.label } }.toMap(),
            onFocus = { TopologyFocus.key = it },
            onPivot = { to, key ->
                TopologyFocus.key = key
                (parentFragment as? PagedFragment)?.openPage(Declarations.opsViewPage(to)?.id ?: to)
            },
        )
    }

    companion object {
        private const val ARG_VIEW = "topology_view"
        fun newInstance(view: String) = TopologyViewFragment().apply {
            arguments = Bundle().apply { putString(ARG_VIEW, view) }
        }
    }
}

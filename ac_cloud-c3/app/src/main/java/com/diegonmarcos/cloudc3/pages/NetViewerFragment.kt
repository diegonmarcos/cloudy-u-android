package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.ContainerSheet
import com.diegonmarcos.cloudc3.cloud.MeshBody
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.launch

/** Where each Network page's viewer mode is remembered. */
interface ModeStore {
    fun get(page: String, default: String): String
    fun set(page: String, mode: String)
}

/** #1955 A The viewer mode of each Network page, remembered per page (SharedPreferences `c3_net_viewer`, key = page id). */
class NetViewerPrefs(context: Context) : ModeStore {
    private val prefs = context.getSharedPreferences("c3_net_viewer", Context.MODE_PRIVATE)
    override fun get(page: String, default: String): String = prefs.getString(page, default) ?: default
    override fun set(page: String, mode: String) { prefs.edit().putString(page, mode).apply() }
}

/** What the six Network pages' Table mode is: the SAME body each page had before the switch (same composable, same inputs). */
object NetPages {
    /** The map id an Ops map page reads, or null when the page is not a map page. */
    fun mapOf(view: String): String? = when (view) { "wg", "auth", "dns" -> view; "security" -> "hier_$view"; else -> null }
    val VIEWS = listOf("wg", "auth", "dns", "network", "security", "mesh")
}

/** [Table|Graph] over one page: [table] is today's body (with the row Open in Table asked for), [graph] the Graph, given the Open in Table action. */
@Composable
fun NetViewerScreen(
    page: String, viewers: NetGraph.Viewers, store: ModeStore,
    table: @Composable (focus: String?) -> Unit,
    graph: @Composable (onOpenTable: (NetGraph.GNode) -> Unit) -> Unit,
) {
    var mode by remember(page) { mutableStateOf(store.get(page, viewers.default)) }
    var focus by remember(page) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ViewerSwitch(page, viewers.modes, mode) { m -> mode = m; store.set(page, m) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (mode == NetGraph.GRAPH) graph { n ->
                focus = n.rows.firstOrNull()?.name ?: n.label
                mode = NetGraph.TABLE; store.set(page, mode)
            } else table(focus)
        }
    }
}

/**
 * #1955 A One Network subpage with its [Table|Graph] switch. Table is today's body ([OpsMapBody], [TopologyViewBody], [MeshBody]);
 * Graph is [NetGraphScreen] over [NetGraph.page], coloured by [NetLive] from the existing live sources.
 */
class NetViewerFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val page = remember { arguments?.getString(ARG_PAGE).orEmpty() }
        val view = page.removePrefix("net_")
        val ctx = LocalContext.current
        val viewers = remember { NetGraph.parseViewers(Declarations.opsMaps) }
        val prefs = remember { NetViewerPrefs(ctx.applicationContext) }
        NetViewerScreen(page, viewers, prefs,
            table = { focus ->
                when {
                    NetPages.mapOf(view) != null -> OpsMapBody(NetPages.mapOf(view)!!, focus)
                    view == "network" -> TopologyViewBody(TopologyModel.NETWORK, parentFragment as? PagedFragment, activity)
                    view == "mesh" -> MeshBody()
                }
            },
            graph = { open ->
                NetGraphHost(page, viewers) { n ->
                    // Routes: the Table opens on the node's entity (the same "Opened on X / Show all" focus a pivot sets)
                    if (view == "network") TopologyFocus.key = n.bind.firstOrNull { it.startsWith("vm:") || it.startsWith("container:") }
                    open(n)
                }
            })
    }

    companion object {
        private const val ARG_PAGE = "net_page"
        fun newInstance(leafId: String) = NetViewerFragment().apply { arguments = Bundle().apply { putString(ARG_PAGE, leafId) } }
    }
}

/** Graph mode: builds the page's graph from the same inputs as its Table, reads the live sources (60 s TTL), draws [NetGraphScreen]. */
@Composable
fun NetGraphHost(page: String, viewers: NetGraph.Viewers, onOpenTable: (NetGraph.GNode) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val version = ContainersSource.version
    val topo = TopologyLiveState.shared
    val net = NetLiveState.shared
    val probes = RoutesProbeStore.shared
    val scope = rememberCoroutineScope()
    var container by remember { mutableStateOf<String?>(null) }
    val slice = remember { Declarations.fleet }
    suspend fun read(force: Boolean) {
        val src = ApiFleetLive(ctx.applicationContext)
        ContainersSource.refresh(src)
        runCatching { topo.refresh(slice, src, { releaseRuns(ctx.applicationContext, Declarations.topology.release) }, force) }
        net.refresh(src, viewers.liveTtlS, force)
    }
    LaunchedEffect(page) {
        read(false)
        // the host bindings read the Table's device probes (cached 5 min, 8 in flight): the same results the Containers Table shows
        if (page != "net_mesh" && page != "net_security") runCatching { probes.run(Declarations.routes, NetProber(ctx.applicationContext), force = false) }
    }
    val inputs = remember(version, topo.live) {
        NetGraph.Inputs(opsMapSources(), slice, TopologyModel.Inputs(ContainersSource.current(), slice, TopologyModel.parseInfra(Declarations.infraSlice), topo.live, Declarations.topology),
            Declarations.meshJson)
    }
    val built = remember(inputs) { NetGraph.page(viewers, page, inputs) } ?: return
    val snapshot = netSnapshot(inputs, topo.live, net, probes.results.toMap())
    val readings = remember(built, snapshot) { NetLive.applyLive(built.second, snapshot, viewers) }
    val roots = remember(readings) { NetLive.rootCause(built.second, readings, snapshot) }
    val why = listOf(net.apiWhy.takeIf { it.isNotBlank() }?.let { "c3 API: $it" },
        topo.live.deployed.values.filterIsInstance<TopologyModel.Deployed.Blind>().firstOrNull()?.why?.let { "docker ps: $it" }).filterNotNull().joinToString("; ")
    NetGraphScreen(page, GraphState(built.second, readings, roots, snapshot.anyRead, why, if (net.at > 0) clock(net.at) else ""), viewers,
        onOpenTable = onOpenTable,
        onOpenContainer = { container = it },
        onReprobe = { n ->
            scope.launch {
                val hosts = n.bind.filter { it.startsWith("host:") }.map { it.removePrefix("host:") }.toSet()
                val names = n.bind.filter { it.startsWith("container:") }.map { it.removePrefix("container:") }.toSet()
                val rows = Declarations.routes.filter { r -> r.container in names || hostOf(r.publicUrl) in hosts || hostOf(r.privateUrl) in hosts }
                rows.forEach { probes.retest(it, NetProber(ctx.applicationContext)) }
                if (rows.isEmpty()) read(true)
            }
        },
        onReread = { scope.launch { read(true) } },
    )
    container?.let { name ->
        ContainerSheet.Sheet(ContainerSheet.Target(name, name, ""), onOpenUrl = { url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(url) }, onDismiss = { container = null })
    }
}

/** The host of a URL-ish table cell ("https://a.b/x", "a.b:443", "a.b"); blank when none. */
fun hostOf(url: String): String = url.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

/** The live snapshot the bindings read, from the shared states (pure enough for the JVM suite: no Android here). */
fun netSnapshot(i: NetGraph.Inputs, live: TopologyModel.Live, net: NetLiveState, probes: Map<String, ProbeModel.RowProbe>): NetLive.Snapshot {
    val containers = if (live.deployed.isEmpty()) emptyMap() else i.topology.catalog.entries.associate { it.name to TopologyModel.containerState(i.topology, it) }
    val hosts = mutableMapOf<String, Pair<MailStack.Health, String>>()
    Declarations.routes.forEach { row ->
        val probe = probes[row.key] ?: return@forEach
        listOf(ProbeModel.Leg.PUBLIC to row.publicUrl, ProbeModel.Leg.PRIVATE to row.privateUrl).forEach { (leg, url) ->
            val h = hostOf(url); val r = probe.legs[leg]
            if (h.isBlank() || r == null) return@forEach
            hosts[h] = when (r.status) {
                ProbeModel.Status.UP -> MailStack.Health.OK to "HTTP ${r.http ?: "?"} ${r.latencyMs ?: "?"} ms"
                ProbeModel.Status.DOWN -> MailStack.Health.BAD to r.error.ifBlank { "down" }
                ProbeModel.Status.UNREACHABLE -> MailStack.Health.UNKNOWN to "device is off the mesh"
            }
        }
    }
    val mesh = (i.mesh["nodes"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }
    fun s(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val wgIp = i.slice.vms.mapNotNull { v -> v.wgIp?.let { v.alias to it } }.toMap() +
        mesh.filter { s(it, "wg_ip").isNotBlank() }.associate { s(it, "alias") to s(it, "wg_ip") } +
        i.ops.infra.subnets.flatMap { it.peers }.filter { it.ip.isNotBlank() }.associate { it.name to it.ip }
    val role = i.ops.infra.subnets.flatMap { it.peers }.groupBy { it.name }.mapValues { it.value.first().role }
    return NetLive.Snapshot(live.deployed, containers, hosts, net.tunnels, net.tunnelsWhy, net.api, net.apiWhy, wgIp, role,
        i.slice.vms.associate { it.id to it.alias }, net.at)
}

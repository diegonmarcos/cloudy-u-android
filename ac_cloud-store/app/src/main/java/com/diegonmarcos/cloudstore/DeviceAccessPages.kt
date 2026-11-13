package com.diegonmarcos.cloudstore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.appstore.StoreRow
import com.diegonmarcos.superapp.fleetdata.DeviceAccess
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataSchedule

/**
 * Store > Access > MCP (device), APIs and the gateway half of IPC (#1718), all three drawn from ONE model: libs:fleet-data-client
 * [DeviceAccess], the runtime dataset `shared/device-access` this app's own fleet data provider serves (observed through FleetData). Cloud C3 Webserver > API
 * draws the same model, so the two pages hold one list. Nothing here names an app, a route or a tool.
 *
 * Every page takes an optional [DeviceAccess.Doc] (the page tests hand it the served file); on the device it is observed off the
 * main thread and the page says "loading" or the reason (with a Sync now action) while there is nothing to draw. No path here can throw.
 */
object DeviceAccessTags {
    const val SUMMARY = "device-access-summary:"
    const val PROBLEM = "device-access-problem"
    const val SYNC = "device-access-sync"
    const val GATEWAY = "device-access-gateway:"
    const val TOOL = "device-access-tool:"
    const val API_ROW = "device-access-api:"
    const val API_COUNT = "device-access-api-count:"
    const val IPC_ROW = "device-access-ipc:"
    const val IPC_COUNT = "device-access-ipc-count:"
    const val FILTER = "device-access-filter:"
    const val ZERO = "device-access-zero"
}

/**
 * The model: [given] when a caller has one, else the served dataset, observed (the page redraws when the provider announces a new
 * copy); null while loading. With nothing served yet the model is empty with the reason, and one sync is asked for.
 */
@Composable
internal fun rememberDeviceAccess(given: DeviceAccess.Doc?): DeviceAccess.Doc? {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf(given) }
    LaunchedEffect(given) {
        if (given != null) return@LaunchedEffect
        val app = ctx.applicationContext
        var asked = false
        try {
            FleetData.observe(app, DeviceAccess.DATASET) { DeviceAccess.parse(it) }.collect { d ->
                doc = d ?: DeviceAccess.EMPTY.copy(problem = NOT_SERVED)
                if (d == null && !asked) { asked = true; syncNow(app) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            doc = DeviceAccess.EMPTY.copy(problem = "The device access dataset could not be read: ${e.javaClass.simpleName}.")
        }
    }
    return doc
}

private const val NOT_SERVED = "The device access dataset is not on this phone yet. Cloud Store syncs it from the fleet data manifest; a sync was asked for just now (it needs a network)."

/** Asks this app's own fleet data provider for one sync now; never throws. */
private fun syncNow(ctx: Context) { runCatching { FleetDataSchedule.syncNow(ctx.applicationContext, FleetDataRouting.STORE) } }

/** The frame every model page shares: the caption, loading / the reason, then [body] over a parsed model. */
@Composable
private fun ModelPage(given: DeviceAccess.Doc?, caption: String, body: @Composable ColumnScope.(DeviceAccess.Doc) -> Unit) {
    val ctx = LocalContext.current
    val doc = rememberDeviceAccess(given)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dpOf(StoreDensity.S12)), verticalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S2))) {
        Text(caption, color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
        when {
            doc == null -> Text("loading the device access model…", color = DIM, fontSize = StoreDensity.T_META.sp)
            doc.problem != null && doc.apps.isEmpty() -> {
                Text(doc.problem ?: "", Modifier.testTag(DeviceAccessTags.PROBLEM), color = MISSING, fontSize = StoreDensity.T_META.sp)
                Text("Sync now", Modifier.background(IDLE).clickable { syncNow(ctx) }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)).testTag(DeviceAccessTags.SYNC),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
            }
            else -> {
                Text("Copy", Modifier.background(IDLE).clickable { copy(ctx, DeviceAccess.report(doc)) }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
                body(doc)
            }
        }
    }
}

private fun copy(ctx: Context, text: String) = runCatching {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Device access", text))
    Toast.makeText(ctx, "Device access copied", Toast.LENGTH_SHORT).show()
}

@Composable private fun AccessMono(text: String, color: Color = Color.White, tag: String? = null) =
    Text(text, if (tag == null) Modifier else Modifier.testTag(tag), color = color, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
@Composable private fun AccessDim(text: String) = Text(text, color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
@Composable private fun AccessHead(text: String) = Text(text, Modifier.padding(top = dpOf(StoreDensity.S4)), color = DIM, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)

@Composable
private fun GatewayCard(doc: DeviceAccess.Doc, g: DeviceAccess.Gateway, extra: @Composable ColumnScope.() -> Unit = {}) {
    StoreRow.Card(tag = DeviceAccessTags.GATEWAY + g.id) {
        Column(Modifier.padding(dpOf(StoreDensity.S12), dpOf(StoreDensity.S6))) {
            Text((if (g.id == "mesh") "Mesh gateway" else "On-phone API gateway") + "  ·  " + (doc.app(g.pkg)?.label ?: g.app.ifEmpty { g.pkg }),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
            AccessMono(g.address + (if (g.id == "mesh") g.ipcRoute + "/<app>/<route>" else g.route))
            AccessDim(g.what)
            if (g.auth.isNotEmpty()) AccessDim("auth: ${g.auth}")
            if (g.cidrs.isNotEmpty()) AccessDim("peers from ${g.cidrs.joinToString(", ")}")
            if (g.roster.isNotEmpty()) AccessDim("roster ${g.roster}")
            if (g.logsRoute.isNotEmpty() || g.endpointsRoute.isNotEmpty()) AccessDim("also ${listOf(g.logsRoute, g.endpointsRoute).filter { it.isNotEmpty() }.joinToString(", ")}")
            if (g.aliases.isNotEmpty()) AccessDim("aliases " + g.aliases.entries.joinToString(", ") { "${it.key} = ${it.value}" })
            extra()
        }
    }
}

/**
 * MCP (device): the doors an MCP tool comes in through on this phone (the one mesh gateway, the one on-phone API gateway), the
 * server that carries the device tools, every device tool with the route it lands on, and the apps that declare MCP endpoints
 * of their own (the MCP kind of the former Apps MCP-API page).
 */
@Composable
fun DeviceMcpPage(model: DeviceAccess.Doc? = null) = ModelPage(model,
    "MCP (device): the MCP endpoints on this phone. An agent's tool call reaches the phone through one of two doors; nothing else listens for MCP.") { doc ->
    StoreRow.Summary("${doc.mcp.tools.size} device MCP tools  ·  ${doc.tools("mesh").size} through the mesh gateway  ·  ${doc.tools("debug").size} through the on-phone gateway  ·  " +
        "${doc.members().count { !it.declared["mcp"].isNullOrEmpty() }}/${doc.members().size} apps declare MCP endpoints", DeviceAccessTags.SUMMARY + PAGE_MCP)
    StoreRow.Card {
        Column(Modifier.padding(dpOf(StoreDensity.S12), dpOf(StoreDensity.S6))) {
            Text("MCP server  ·  ${doc.mcp.server.ifEmpty { "not declared" }}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
            if (doc.mcp.endpoint.isNotEmpty()) AccessMono(doc.mcp.endpoint)
            AccessDim("auth: ${doc.mcp.auth.ifEmpty { "?" }}" + (if (doc.mcp.aliases.isEmpty()) "" else "  ·  aliases ${doc.mcp.aliases.joinToString(", ")}") + "  ·  device tools under ${doc.mcp.node}")
        }
    }
    for (g in doc.gateways.sortedBy { if (it.id == "mesh") 0 else 1 }) GatewayCard(doc, g) {
        val tools = doc.tools(g.id)
        AccessHead("${tools.size} tool(s) through this door")
        for (t in tools) AccessMono(DeviceAccess.line(t), tag = DeviceAccessTags.TOOL + t.tool)
    }
    val kind = doc.kinds.firstOrNull { it.id == "mcp" }
    AccessHead((kind?.label ?: "MCP") + " endpoints the apps declare" + (kind?.what?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""))
    for (a in doc.members()) {
        val eps = a.declared["mcp"].orEmpty()
        if (eps.isEmpty()) continue
        Text(a.label, color = Color.White, fontSize = StoreDensity.T_META.sp)
        for (e in eps) AccessMono("${e.method} ${e.url}  ·  ${e.what}")
    }
    AccessDim(doc.members().filter { it.declared["mcp"].isNullOrEmpty() }.let { none -> if (none.isEmpty()) "Every app declares one." else "MISSING (declares none): " + none.joinToString(", ") { it.label } })
}

/** The member filter both list pages share: the apps, the libs, or every member. */
@Composable
private fun MemberFilter(doc: DeviceAccess.Doc, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S4))) {
        for ((id, label, n) in listOf(Triple("app", "Apps", doc.members("app").size), Triple("lib", "Libs", doc.members("lib").size), Triple("all", "All", doc.apps.size)))
            Text("$label ($n)", Modifier.weight(1f).background(if (id == selected) UP else IDLE).clickable { onSelect(id) }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4))
                .testTag(DeviceAccessTags.FILTER + id), color = Color.White, fontWeight = if (id == selected) FontWeight.Bold else FontWeight.Normal, fontSize = StoreDensity.T_META.sp, maxLines = 1)
    }
}

/**
 * APIs: each app's own API. One row per member; the head carries the number of routes it really exposes (its ops behind the
 * on-phone gateway, its own listener's routes, the endpoints it declares), so an app that declares an API never reads zero.
 * Open: the gateway address, every op, every listener route, and the declared endpoints by kind (MISSING where a kind has none;
 * the debug kind inherits the shared default), as the Apps MCP-API page listed them.
 */
@Composable
fun DeviceApisPage(model: DeviceAccess.Doc? = null, expandAll: Boolean = false) = ModelPage(model,
    "APIs: each app's own API routes. Counts come from the declared maps (the fleet IPC registry, the webserver's route map, each app's api_endpoints), not from a probe.") { doc ->
    var filter by remember { mutableStateOf("app") }
    var open by remember { mutableStateOf(setOf<String>()) }
    val rows = doc.members(filter.takeIf { it != "all" })
    StoreRow.Summary("${rows.size} members  ·  ${rows.sumOf { it.apiCount }} API routes  ·  ${rows.sumOf { it.ops.size }} ops  ·  ${rows.sumOf { it.routes.size }} listener routes  ·  " +
        doc.kinds.joinToString("  ·  ") { k -> "${k.label} ${rows.count { doc.declared(it, k.id).isNotEmpty() }}/${rows.size}" }, DeviceAccessTags.SUMMARY + PAGE_APIS)
    val zero = doc.zeroApis()
    if (zero.isNotEmpty()) Text("⚠ ${zero.size} app(s) expose the fleet door but list no route: " + zero.joinToString(", ") { it.label }, Modifier.testTag(DeviceAccessTags.ZERO), color = BLOCKED, fontSize = StoreDensity.T_META.sp)
    doc.gateway("debug")?.let { GatewayCard(doc, it) }
    MemberFilter(doc, filter) { filter = it }
    for (a in rows) {
        val isOpen = expandAll || a.id in open
        val n = a.apiCount
        StoreRow.Item(a.label + (if (a.kind == "lib") "  ·  lib" else ""), isOpen, { open = if (a.id in open) open - a.id else open + a.id }, tag = DeviceAccessTags.API_ROW + a.id,
            dot = if (n > 0) "✓" else "◯", dotColor = if (n > 0) OK else GAP,
            meta = if (n > 0) "$n routes" else if (a.door) "0 routes" else "no API door", metaColor = if (n > 0) OK else GAP, metaTag = DeviceAccessTags.API_COUNT + a.id) {
            AccessMono(a.pkg, DIM)
            AccessDim("fleet door ${if (a.door) "content://" + doc.doorAuthority.replace("<pkg>", a.pkg) + " (${doc.doorMethod})" else "none"}" + (a.port?.let { "  ·  debug port $it" } ?: ""))
            if (a.ops.isNotEmpty()) { AccessHead("${a.ops.size} ops behind the on-phone gateway"); for (op in a.ops) AccessMono(doc.opUrl(a, op)) }
            if (a.routes.isNotEmpty()) { AccessHead("${a.routes.size} routes of its own listener"); for (r in a.routes) AccessMono(DeviceAccess.line(r)) }
            for (k in doc.kinds) {
                val eps = doc.declared(a, k.id)
                AccessHead(k.label + " - " + k.what + if (doc.inherits(a, k.id)) "  (default: ${doc.sharedDebug.lib})" else "")
                if (eps.isEmpty()) AccessMono("MISSING", Color(GAP)) else for (e in eps) AccessMono("${e.method} ${e.url}")
            }
        }
    }
}

/**
 * IPC > Gateway routes: the per-app IPC routes behind the one mesh gateway (the webserver's api-ipc-map): the gateway, its ACL
 * scopes and peers, then one row per member with every `<ipc_route>/<app>/<route>` (authority, methods, scope, status, the MCP
 * tool that uses it, the per-app endpoint it retires) and the IPC endpoints every member carries.
 */
@Composable
fun DeviceIpcPage(model: DeviceAccess.Doc? = null, expandAll: Boolean = false) = ModelPage(model,
    "IPC: the per-app IPC routes. On the phone, apps talk over Android IPC only; off the phone, the one mesh gateway fronts each app's IPC behind its own route and ACL.") { doc ->
    var filter by remember { mutableStateOf("app") }
    var open by remember { mutableStateOf(setOf<String>()) }
    val rows = doc.members(filter.takeIf { it != "all" })
    StoreRow.Summary("${rows.size} members  ·  ${rows.sumOf { it.ipcCount }} gateway routes  ·  ${doc.scopes.size} scopes  ·  ${doc.peers.size} peers  ·  " +
        "${rows.sumOf { doc.ipcEndpoints(it).size }} per-app endpoints", DeviceAccessTags.SUMMARY + PAGE_IPC)
    doc.gateway("mesh")?.let { g ->
        GatewayCard(doc, g) {
            AccessHead("${doc.scopes.size} ACL scopes"); for ((k, v) in doc.scopes) AccessMono("$k  ·  $v")
            AccessHead("${doc.peers.size} mesh peers (access held once, here)"); for (p in doc.peers) AccessMono("${p.id}  ·  ${if (p.full) "full access" else p.scopes.joinToString(", ")}  ·  ${p.what}")
        }
    }
    MemberFilter(doc, filter) { filter = it }
    for (a in rows) {
        val isOpen = expandAll || a.id in open
        val eps = doc.ipcEndpoints(a)
        StoreRow.Item(a.label + (if (a.kind == "lib") "  ·  lib" else ""), isOpen, { open = if (a.id in open) open - a.id else open + a.id }, tag = DeviceAccessTags.IPC_ROW + a.id,
            dot = if (a.ipcCount > 0) "✓" else "◯", dotColor = if (a.ipcCount > 0) OK else GAP,
            meta = "${a.ipcCount} routes  ·  ${eps.size} endpoints", metaColor = if (a.ipcCount > 0) OK else GAP, metaTag = DeviceAccessTags.IPC_COUNT + a.id) {
            AccessMono(a.pkg, DIM)
            AccessHead("${a.ipcCount} routes behind the mesh gateway")
            for (r in a.ipc) { AccessMono(DeviceAccess.line(r)); if (r.what.isNotEmpty()) AccessDim(r.what) }
            AccessHead("${eps.size} IPC endpoints of this app")
            for (e in eps) { AccessMono("${e.kind}  ${e.authority}  ·  ${e.methods}"); AccessDim(listOf(e.guard, e.what).filter { it.isNotEmpty() }.joinToString("  ·  ")) }
        }
    }
}

private val OK = 0xFF48BB78.toInt()
private val GAP = 0xFFED8936.toInt()

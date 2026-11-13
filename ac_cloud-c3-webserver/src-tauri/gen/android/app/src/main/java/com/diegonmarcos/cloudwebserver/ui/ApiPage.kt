package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.superapp.fleetdata.DeviceAccess
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1718 The API section shows ONLY this device (the fleet services' public and mesh URLs are Cloud C3's), and every sub-page opens
 * with the device access model: [DeviceAccessCards], drawn from libs:fleet-data-client DeviceAccess over the dataset the core
 * serves at [Api.ACCESS]. Cloud Store > Access draws the same model from the same dataset, so the two pages hold one list.
 *
 * The API section: Topology (MCP -> API -> IPC, the web shell's renderTopology: filter, grouping, the node list with the selection's
 * edge neighbours marked, a Swagger-style detail with Try it for an app GET route), then the declared sub-pages of build.json::subpages
 * (tab api: MCP, Public / Mesh / Apps Endpoints, Apps IPC); Apps Endpoints keeps the route table listing below it (renderRoutes).
 */
@Composable
fun ApiSection(page: String) {
    val (tick, bump) = rememberTick()
    if (page == Decl.PAGE_TOPOLOGY) { TopologyPage(tick, bump); return }
    val subs = rememberArr(Api.SUBPAGES, tick)
    PageColumn("c3_api_$page") {
        Acts { Act("Refresh", "c3_api_refresh") { bump() } }
        Loaded(subs, "sub-pages") { arr ->
            val sp = arr.objs().firstOrNull { it.optString("tab") == "api" && it.optString("id") == page }
            DeviceAccessCards(page, tick)
            if (sp == null) ErrorNote("build.json::subpages declares no api page '$page'") else SubPage(sp.optString("route"), tick, bump)
            if (page == "apps") RoutesCard(tick)
        }
    }
}

/** renderRoutes(ROUTES, 'api'): the app's own API route table (kind api), each with its summary and tab. */
@Composable
private fun RoutesCard(tick: Int) {
    val routes = rememberArr(Api.ROUTES, tick)
    Loaded(routes, "routes") { arr ->
        val api = arr.objs().filter { it.optString("kind") == "api" }
        Card("${api.size} API routes of this app", "c3_api_routes") {
            api.forEach { r -> Kv(r.str("path"), r.str("summary") + (if (r.has("tab") && !r.isNull("tab")) "  ·  tab: ${r.str("tab")}" else ""), mono = true) }
        }
    }
}

private fun byId(t: JSONObject): Map<String, JSONObject> = t.optJSONArray("nodes").objs().associateBy { it.optString("id") }

private fun reach(t: JSONObject, id: String, dir: String): List<String> {
    val (k, o) = if (dir == "from") "from" to "to" else "to" to "from"
    val edges = t.optJSONArray("edges").objs()
    val seen = linkedSetOf<String>(); val todo = ArrayDeque(listOf(id))
    while (todo.isNotEmpty()) { val c = todo.removeLast(); edges.forEach { e -> if (e.optString(k) == c && seen.add(e.optString(o))) todo.addLast(e.optString(o)) } }
    return seen.toList()
}

private fun rows(t: JSONObject, n: JSONObject): List<JSONObject> {
    val m = n.optJSONObject("meta") ?: JSONObject()
    return t.optJSONArray("routes").objs().filter { r ->
        when (n.optString("layer")) {
            "mcp" -> if (m.optString("kind") == "tool") r.optString("mcp_tool") == n.optString("label") && r.optString("mcp_server") == m.optString("server") else r.optString("mcp_server") == n.optString("label")
            "api" -> r.optString("api_route") == n.optString("label")
            else -> r.optString("ipc_route") == n.optString("id")
        }
    }
}

private fun mcpOnly(t: JSONObject, n: JSONObject) = n.optString("layer") == "mcp" && n.optJSONObject("meta")?.optString("kind") == "tool" && rows(t, n).all { it.optString("status") == "mcp_only" }
private fun remote(t: JSONObject, n: JSONObject) = reach(t, n.optString("id"), "from").any { it.startsWith("api:c3:") } || n.optString("id").startsWith("api:c3:")

private fun matches(n: JSONObject, q: String): Boolean {
    if (q.isBlank()) return true
    val m = n.optJSONObject("meta")
    val h = listOf(n.optString("id"), n.optString("label"), m?.optString("server"), m?.optString("summary"), m?.optString("surface")).joinToString(" ").lowercase()
    return q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in h }
}

private fun groupKey(group: String, n: JSONObject): String {
    val m = n.optJSONObject("meta") ?: JSONObject()
    val layer = n.optString("layer")
    return when (group) {
        "server" -> if (layer == "mcp") (if (m.optString("kind") == "tool") m.optString("server") else n.optString("label")) else if (layer == "api") "api: " + m.optString("surface") else "ipc"
        "surface" -> if (layer == "api") m.optString("surface") else if (layer == "mcp") "mcp" else "ipc"
        else -> layer
    }
}

private fun badges(t: JSONObject, n: JSONObject): List<String> {
    val m = n.optJSONObject("meta") ?: JSONObject()
    val b = mutableListOf<String>()
    if (n.optString("layer") == "mcp" && m.optString("kind") == "tool") b += if (mcpOnly(t, n)) "mcp_only" else if (remote(t, n)) "remote c3-api, no local IPC" else "local route"
    if (n.optString("layer") == "api") { m.optString("method").takeIf { it.isNotEmpty() }?.let { b += it }; b += m.optString("surface") }
    return b
}

@Composable
private fun TopologyPage(tick: Int, bump: () -> Unit) {
    val topo = rememberObj(Api.TOPOLOGY, tick)
    var q by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("server") }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    PageColumn("c3_api_topology") {
        Loaded(topo, "topology") { t ->
            val nodes = t.optJSONArray("nodes").objs()
            val counts = nodes.groupingBy { it.optString("layer") }.eachCount()
            Card("API topology: MCP → API → IPC", "c3_topo", trailing = { Act("Refresh", "c3_topo_refresh") { bump() } }) {
                Note("${counts["mcp"] ?: 0} mcp / ${counts["api"] ?: 0} api / ${counts["ipc"] ?: 0} ipc nodes, ${t.optJSONArray("edges")?.length() ?: 0} edges, ${nodes.count { mcpOnly(t, it) }} mcp_only")
                OutlinedTextField(q, { q = it }, label = { Text("Filter: tool, server, route, ipc id") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("c3_topo_q"))
                Acts { listOf("layer" to "By layer", "server" to "By server", "surface" to "By surface").forEach { (id, l) -> Act((if (group == id) "● " else "") + l, "c3_topo_group_$id") { group = id } } }
            }
            TopoDetail(t, sel) { sel = it }
            val rel = sel?.let { (reach(t, it, "from") + reach(t, it, "to")).toSet() }.orEmpty()
            val shown = nodes.filter { matches(it, q) }
            if (shown.isEmpty()) Note("No node matches.")
            shown.groupBy { groupKey(group, it) }.forEach { (name, ns) ->
                Card(name, "c3_topo_group", badges = { Badge(ns.size.toString()) }) {
                    ns.forEach { n ->
                        val id = n.optString("id")
                        val color = if (id == sel) MaterialTheme.colorScheme.primary else if (id in rel) Tone.Warn else MaterialTheme.colorScheme.onSurface
                        Column(Modifier.fillMaxWidth().clickable { sel = id }.padding(horizontal = 12.dp, vertical = 3.dp).testTag("c3_topo_node")) {
                            Text("${n.optString("layer")}  ${n.optString("label")}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = color)
                            val bs = badges(t, n)
                            if (bs.isNotEmpty()) androidx.compose.foundation.layout.Row { bs.forEach { Badge(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopoDetail(t: JSONObject, sel: String?, pick: (String) -> Unit) {
    val n = sel?.let { byId(t)[it] }
    Card("Detail", "c3_topo_detail") {
        if (n == null) { Note("Select an MCP tool, an API route or an IPC node below."); return@Card }
        val m = n.optJSONObject("meta") ?: JSONObject()
        val id = n.optString("id")
        val rs = rows(t, n)
        fun label(x: String) = byId(t)[x]?.optString("label") ?: x
        Text("${n.optString("layer")} ${m.optString("kind")}  " + badges(t, n).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        Kv(m.optString("method").ifEmpty { "path" }, n.optString("label"), mono = true)
        listOf("summary" to "Summary", "what" to "What", "auth" to "Auth", "authority" to "Authority", "server" to "MCP server", "endpoint" to "Endpoint", "surface" to "Surface")
            .forEach { (k, l) -> if (m.has(k) && !m.isNull(k) && m.optString(k).isNotEmpty()) Kv(l, m.optString(k)) }
        if (m.optString("kind") == "server") Kv("Tools", "${m.str("tools_count")} (${m.str("tools_listed")} listed)")
        val tools = if (n.optString("layer") == "api") reach(t, id, "to").filter { it.startsWith("mcp:") && it.indexOf('/') > 0 } else emptyList()
        val apis = if (n.optString("layer") == "mcp" && m.optString("kind") == "tool") reach(t, id, "from").filter { it.startsWith("api:") } else emptyList()
        val ipcs = if (n.optString("layer") == "ipc") emptyList() else reach(t, id, "from").filter { it.startsWith("ipc:") }
        if (tools.isNotEmpty()) LinkRow("MCP tool(s)", tools, ::label, pick)
        if (apis.isNotEmpty()) LinkRow("API route", apis, ::label, pick)
        if (n.optString("layer") == "ipc") {
            val back = reach(t, id, "to").filter { it.startsWith("api:") }
            if (back.isEmpty()) Kv("API route(s)", "none") else LinkRow("API route(s)", back, ::label, pick)
            if (m.optBoolean("per_app")) Kv("Per app", "${m.str("apps")} app(s)")
        } else if (m.optString("kind") != "server") {
            if (ipcs.isNotEmpty()) LinkRow("IPC route", ipcs, ::label, pick)
            else Kv("IPC route", if (mcpOnly(t, n)) "none (mcp_only)" else if (remote(t, n)) "remote c3-api, no local IPC" else "none")
            Kv("IPC state", rs.map { it.optString("ipc_state") }.distinct().joinToString(", ").ifEmpty { "n/a" })
        }
        if (n.optString("layer") != "ipc") Kv("Status", (rs.firstOrNull()?.optString("status") ?: "unknown") + " (live status later)")
        val tryable = n.optString("layer") == "api" && m.optString("surface") == "app" && m.optString("method") == "GET" && m.optString("kind") == "api" && Regex("^/__app__/api/[a-z0-9_/-]+$").matches(n.optString("label"))
        if (tryable) TryIt(n.optString("label"))
    }
}

@Composable
private fun LinkRow(k: String, ids: List<String>, label: (String) -> String, pick: (String) -> Unit) {
    Kv(k, "")
    Acts { ids.forEach { x -> LinkText(label(x), "c3_topo_link") { pick(x) } } }
}

/** Try it (GET): the route's status and the first 4000 characters of its answer. */
@Composable
private fun TryIt(path: String) {
    var out by rememberSaveable(path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Acts { Act("Try it (GET)", "c3_topo_try") { scope.launch { out = when (val r = Api.get(path)) { is Api.Reply.Ok -> "200\n" + (if (r.value is JSONArray) (r.value as JSONArray).toString(1) else (r.value as JSONObject).toString(1)).take(4000); is Api.Reply.Err -> r.reason } } } }
    out?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp).testTag("c3_topo_try_out")) }
}


/**
 * #1718 THE device access model on this page: the same [DeviceAccess] document Cloud Store > Access draws (one data source, not
 * two lists). MCP: the device MCP doors and tools. Mesh Endpoints: the one mesh gateway with its scopes and peers. Apps Endpoints:
 * each app's own API routes with the real count (never zero for an app that exposes one). Apps IPC: each app's IPC routes behind
 * the gateway and its IPC endpoints. A dataset that cannot be read is a reason, never a crash.
 */
@Composable
private fun DeviceAccessCards(page: String, tick: Int) {
    val raw = rememberObj(Api.ACCESS, tick)
    Loaded(raw, "device access model") { o ->
        val doc = remember(o) { DeviceAccess.parse(o.toString()) }
        val problem = doc.problem
        if (problem != null && doc.apps.isEmpty()) ErrorNote(problem)
        else when (page) {
            "mcp" -> AccessMcp(doc)
            "mesh" -> doc.gateway("mesh")?.let { AccessGateway(doc, it, full = true) } ?: Note("The device access model declares no mesh gateway.")
            "apps" -> AccessApis(doc)
            "ipc" -> AccessIpc(doc)
        }
    }
}

@Composable
private fun AccessGateway(doc: DeviceAccess.Doc, g: DeviceAccess.Gateway, full: Boolean = false) {
    Card((if (g.id == "mesh") "Mesh gateway" else "On-phone API gateway") + " · " + (doc.app(g.pkg)?.label ?: g.app.ifEmpty { g.pkg }), "c3_access_gateway_${g.id}", badges = { Badge("this device") }) {
        Kv("address", g.address + (if (g.id == "mesh") g.ipcRoute + "/<app>/<route>" else g.route), mono = true)
        Note(g.what)
        if (g.auth.isNotEmpty()) Kv("auth", g.auth)
        if (g.cidrs.isNotEmpty()) Kv("peers from", g.cidrs.joinToString(", "), mono = true)
        if (g.roster.isNotEmpty()) Kv("roster", g.roster, mono = true)
        if (g.logsRoute.isNotEmpty() || g.endpointsRoute.isNotEmpty()) Kv("also", listOf(g.logsRoute, g.endpointsRoute).filter { it.isNotEmpty() }.joinToString(", "), mono = true)
        g.aliases.forEach { (k, v) -> Kv("alias $k", v, mono = true) }
        if (full) {
            Note("${doc.scopes.size} ACL scopes · ${doc.peers.size} mesh peers · ${doc.ipcTotal} routes over ${doc.apps.count { it.ipcCount > 0 }} members")
            doc.scopes.forEach { (k, v) -> Kv(k, v) }
            doc.peers.forEach { p -> Kv("peer ${p.id}", (if (p.full) "full access" else p.scopes.joinToString(", ")) + " · " + p.what) }
        }
    }
}

@Composable
private fun AccessMcp(doc: DeviceAccess.Doc) {
    Card("This device · ${doc.mcp.tools.size} MCP tools", "c3_access_mcp", badges = { Badge("this device"); Badge(doc.mcp.server.ifEmpty { "no server" }) }) {
        if (doc.mcp.endpoint.isNotEmpty()) Kv("server", doc.mcp.endpoint, mono = true)
        Kv("auth", doc.mcp.auth.ifEmpty { "?" } + (if (doc.mcp.aliases.isEmpty()) "" else " · aliases " + doc.mcp.aliases.joinToString(", ")))
        Note("An agent's tool call reaches this phone through one of two doors; ${doc.tools("mesh").size} tools come in through the mesh gateway, ${doc.tools("debug").size} through the on-phone gateway.")
    }
    doc.gateways.sortedBy { if (it.id == "mesh") 0 else 1 }.forEach { g ->
        AccessGateway(doc, g)
        Card("${doc.tools(g.id).size} tools through the ${if (g.id == "mesh") "mesh" else "on-phone"} gateway", "c3_access_tools_${g.id}") {
            doc.tools(g.id).forEach { t -> Kv(t.tool, if (t.routes.isEmpty()) (t.legacy?.let { "was $it" } ?: "") else t.routes.joinToString("  |  ") { "${it.method} ${it.path}" + if (it.acl.isEmpty()) "" else " [${it.acl}]" }, mono = true) }
        }
    }
}

/** One member row that unfolds: the head (label, the counts) and, open, [detail]. */
@Composable
private fun AccessRow(id: String, head: String, meta: String, ok: Boolean, tag: String, open: Boolean, toggle: () -> Unit, detail: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 12.dp, vertical = 3.dp).testTag(tag + id)) {
        Text((if (open) "▾ " else "▸ ") + head + "  ·  " + meta, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurface else Tone.Warn)
    }
    if (open) detail()
}

@Composable
private fun AccessApis(doc: DeviceAccess.Doc) {
    var open by rememberSaveable { mutableStateOf("") }
    doc.gateway("debug")?.let { AccessGateway(doc, it) }
    val rows = doc.apps.sortedBy { it.label.lowercase() }
    Card("This device · ${rows.size} members · ${doc.apiTotal} API routes", "c3_access_apis", badges = { Badge("this device"); Badge("${doc.members().size} apps"); Badge("${doc.members("lib").size} libs") }) {
        val zero = doc.zeroApis()
        if (zero.isNotEmpty()) ErrorNote("${zero.size} app(s) expose the fleet door but list no route: " + zero.joinToString(", ") { it.label })
        Note("Counts come from the declared maps (the fleet IPC registry, this app's route map, each app's api_endpoints), not from a probe.")
        rows.forEach { a ->
            AccessRow(a.id, a.label + (if (a.kind == "lib") " (lib)" else ""), "${a.apiCount} routes" + (a.port?.let { " · :$it" } ?: ""), a.apiCount > 0, "c3_access_api_", open == a.id, { open = if (open == a.id) "" else a.id }) {
                Kv("package", a.pkg, mono = true)
                a.ops.forEach { op -> Kv("op", doc.opUrl(a, op), mono = true) }
                a.routes.forEach { r -> Kv("route", DeviceAccess.line(r), mono = true) }
                doc.kinds.forEach { k ->
                    val eps = doc.declared(a, k.id)
                    if (eps.isEmpty()) Kv(k.label, "MISSING") else eps.forEach { e -> Kv(k.label + if (doc.inherits(a, k.id)) " (default)" else "", "${e.method} ${e.url}", mono = true) }
                }
            }
        }
    }
}

@Composable
private fun AccessIpc(doc: DeviceAccess.Doc) {
    var open by rememberSaveable { mutableStateOf("") }
    val rows = doc.apps.sortedBy { it.label.lowercase() }
    Card("This device · ${doc.ipcTotal} IPC routes behind the mesh gateway", "c3_access_ipc", badges = { Badge("this device"); Badge("${doc.scopes.size} scopes"); Badge("${doc.peers.size} peers") }) {
        doc.gateway("mesh")?.let { g -> Kv("gateway", g.address + g.ipcRoute + "/<app>/<route>", mono = true) }
        rows.forEach { a ->
            val eps = doc.ipcEndpoints(a)
            AccessRow(a.id, a.label + (if (a.kind == "lib") " (lib)" else ""), "${a.ipcCount} routes · ${eps.size} endpoints", a.ipcCount > 0, "c3_access_ipc_", open == a.id, { open = if (open == a.id) "" else a.id }) {
                Kv("package", a.pkg, mono = true)
                a.ipc.forEach { r -> Kv(r.route, DeviceAccess.line(r), mono = true); if (r.what.isNotEmpty()) Note(r.what) }
                eps.forEach { e -> Kv(e.id, "${e.kind} ${e.authority} · ${e.methods}", mono = true) }
            }
        }
    }
}

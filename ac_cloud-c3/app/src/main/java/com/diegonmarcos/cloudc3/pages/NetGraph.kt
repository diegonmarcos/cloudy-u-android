package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1955 A The Graph view of every Ops > Network subpage, as pure data (the JVM suite runs the exact code the phone runs).
 *
 * The TABLE a page shows today is turned into a [Table] (its rows with every printed field, its edges); the page's declared rule
 * (data/ops_maps.json `viewers.pages.<id>.graph`) turns the same input into a [Graph] of lanes, columns, clusters, nodes and edges.
 * Nothing else may be drawn: [parity] proves every table row is exactly one node, every table edge exactly one graph edge (or listed
 * as undrawn when its target is blank), and every other node or edge is a declared root, target, overflow or `depends`.
 * Node identity is `<ns>:<name>` with the namespace declared per section, so a tier and an auth kind of the same name stay two nodes,
 * and one host on two subnets is one node with two rows. No service, VM or host name is typed here: they come from the data.
 */
object NetGraph {

    /** One table row this node stands for: the section it is printed under, its name, and every field the table prints for it. */
    data class RowRef(val section: String, val name: String, val fields: List<Pair<String, String>>) {
        val key get() = "$section|$name"
    }

    /** One table edge (a row's "-> to  label" line, a tree parent-child line, a mesh peering). */
    data class EdgeRef(val section: String, val from: String, val to: String, val label: String, val state: String = "", val extra: String = "") {
        val key get() = "$section|$from>$to#$label"
    }

    /** Which end needs the other: the dependency relation the root cause walks. */
    enum class Dep { FROM_NEEDS_TO, TO_NEEDS_FROM, NONE }

    data class GNode(
        val id: String, val ns: String, val label: String, val sub: String, val kind: String,
        val lane: String, val column: Int, val cluster: String,
        val rows: List<RowRef>, val bind: List<String>,
        /** row | target | root | overflow */
        val declared: String,
    )

    data class GEdge(
        val id: String, val from: String, val to: String, val label: String, val kind: String,
        val dashed: Boolean, val rows: List<EdgeRef>, val dep: Dep, val bind: List<String> = emptyList(),
        /** table | root | depends */
        val declared: String = "table",
    )

    data class GLane(val id: String, val label: String, val sub: String = "")

    /** A group of nodes the layout may collapse into one aggregate. [head] is the tree node that heads it (Routes), else null (a synthetic aggregate). */
    data class GCluster(val id: String, val label: String, val lane: String, val column: Int, val members: List<String>, val head: String? = null, val openByDefault: Boolean = true)

    data class Graph(
        val page: String, val lanes: List<GLane>, val columns: List<String>, val clusters: List<GCluster>,
        val nodes: List<GNode>, val edges: List<GEdge>, val depends: List<GEdge>, val undrawn: List<EdgeRef> = emptyList(),
    ) {
        private val byId by lazy { nodes.associateBy { it.id } }
        fun node(id: String): GNode? = byId[id]
        fun inEdges(id: String) = edges.filter { it.to == id }
        fun outEdges(id: String) = edges.filter { it.from == id }
    }

    /** What the table of a page prints: its rows with their fields and its edges. The parity reference. */
    data class Table(val page: String, val rows: List<RowRef>, val edges: List<EdgeRef>)

    /** The declared renames a rule may apply to an edge target: VM id to alias, WG IP to alias; and the mesh DNS primary. */
    data class Resolver(val vmAlias: Map<String, String> = emptyMap(), val ipAlias: Map<String, String> = emptyMap(), val dnsPrimary: String = "") {
        fun apply(kinds: List<String>, v: String): String = kinds.fold(v) { acc, k ->
            when (k) { "vm_id_to_alias" -> vmAlias[acc] ?: acc; "wg_ip_to_alias" -> ipAlias[acc] ?: acc; else -> acc }
        }
        fun aliasOf(v: String): String = ipAlias[v] ?: vmAlias[v] ?: v
    }

    /** The `viewers` block. */
    data class Viewers(
        val modes: List<String>, val default: String, val nodeW: Float, val nodeH: Float, val gapX: Float, val gapY: Float, val laneHeader: Float,
        val collapseOver: Int, val bundleMin: Int, val pulseMaxEdges: Int, val liveTtlS: Int,
        val handshakeOkS: Int, val handshakeWarnS: Int, val staleIs: Map<String, String>,
        val pages: Map<String, JsonObject>,
        /** #1955 A rev2 the whole `viewers` block, for the declared layout and scene numbers ([num], [layoutOf]). */
        val raw: JsonObject = JsonObject(emptyMap()),
    ) {
        fun graph(page: String): JsonObject? = pages[page]?.get("graph") as? JsonObject
        /** A declared number by dotted key: `pages.<page>.<key>` first, then `viewers.<key>`, else [d]. */
        fun num(page: String?, key: String, d: Float): Float {
            fun at(o: JsonObject?, k: List<String>): Float? {
                var cur: JsonObject = o ?: return null
                k.dropLast(1).forEach { cur = cur[it] as? JsonObject ?: return null }
                return (cur[k.last()] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
            }
            val k = key.split('.')
            return (page?.let { at(pages[it], k) }) ?: at(raw, k) ?: d
        }
        /** A declared string list of a page (`pages.<page>.<key>`), empty when absent. */
        fun strs(page: String, key: String): List<String> = (pages[page]?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        /** The declared layout of a page: radial | star | layered | dns_bands | clusters | lanes (the swimlane fallback). */
        fun layoutOf(page: String): String = (pages[page]?.get("layout") as? JsonPrimitive)?.contentOrNull ?: "lanes"
    }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.smap(k: String): Map<String, String> = obj(k)?.mapNotNull { (a, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { a to it } }?.toMap().orEmpty()
    private fun JsonObject.intMap(k: String): Map<String, Int> = obj(k)?.mapNotNull { (a, v) -> (v as? JsonPrimitive)?.intOrNull?.let { a to it } }?.toMap().orEmpty()

    fun parseViewers(opsMaps: JsonObject): Viewers {
        val v = opsMaps.obj("viewers") ?: JsonObject(emptyMap())
        fun f(k: String, d: Float) = (v[k] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: d
        val st = v.obj("status") ?: JsonObject(emptyMap())
        return Viewers(
            v.strs("modes").ifEmpty { listOf(TABLE, GRAPH) }, v.str("default").ifBlank { TABLE },
            f("node_w", 168f), f("node_h", 56f), f("gap_x", 72f), f("gap_y", 12f), f("lane_header", 28f),
            v.int("collapse_over") ?: 12, v.int("bundle_min") ?: 3, v.int("pulse_max_edges") ?: 40, v.int("live_ttl_s") ?: 60,
            st.int("handshake_ok_s") ?: 180, st.int("handshake_warn_s") ?: 900, st.smap("stale_is"),
            v.obj("pages")?.mapNotNull { (k, o) -> (o as? JsonObject)?.let { k to it } }?.toMap().orEmpty(),
            v,
        )
    }

    const val TABLE = "table"
    const val GRAPH = "graph"

    // ── tables (what each page prints today) ────────────────────────────────

    fun rowFields(r: OpsMaps.Row): List<Pair<String, String>> =
        listOf("name" to r.name, "note" to r.note, "state" to r.state) +
            r.edges.map { e -> "edge" to ("-> ${e.to}" + (if (e.label.isBlank()) "" else "  ${e.label}") + (if (e.state.isBlank()) "" else "  [${e.state}]")) }

    fun tableOfSections(page: String, sections: List<OpsMaps.Section>): Table = Table(page,
        sections.flatMap { s -> s.rows.map { RowRef(s.title, it.name, rowFields(it)) } },
        sections.flatMap { s -> s.rows.flatMap { r -> r.edges.map { EdgeRef(s.title, r.name, it.to, it.label, it.state) } } })

    fun treeFields(n: TopologyModel.Node, path: String): List<Pair<String, String>> =
        listOf("label" to n.label, "kind" to n.kind, "metric" to n.metric, "health" to n.health.name.lowercase(), "path" to path)

    fun tableOfTree(page: String, roots: List<TopologyModel.Node>): Table {
        val rows = mutableListOf<RowRef>(); val edges = mutableListOf<EdgeRef>()
        fun walk(n: TopologyModel.Node, root: String, path: String) {
            rows += RowRef(root, n.id, treeFields(n, path))
            n.children.forEach { c -> edges += EdgeRef(root, n.id, c.id, ""); walk(c, root, "$path / ${c.label}") }
        }
        roots.forEach { walk(it, it.id, it.label) }
        return Table(page, rows, edges)
    }

    /** The mesh transport a node is drawn under, exactly as the WG Mesh cards split them (MeshView: wg0 direct, wg0-tcp wstunnel clients). */
    fun meshLaneOf(node: JsonObject, transports: List<String>): String {
        val tcp = (node["wstunnel_client"] as? JsonPrimitive)?.booleanOrNull == true
        return when {
            tcp && "wg0-tcp" in transports -> "wg0-tcp"
            !tcp && "wg0" in transports -> "wg0"
            else -> transports.firstOrNull().orEmpty()
        }
    }

    fun meshFields(n: JsonObject, routes: List<JsonObject>): List<Pair<String, String>> =
        listOf("name", "alias", "role", "wg_ip", "public_ip", "region", "provider", "os", "public_key_fp").map { it to n.str(it) } +
            listOf("ports_public" to (n["ports_public"] as? JsonArray).orEmpty().joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() },
                "wstunnel_server" to n.str("wstunnel_server"), "wstunnel_client" to n.str("wstunnel_client")) +
            routes.filter { it.str("src_node") == n.str("name") }.map { "route" to "${it.str("dst_subnet")} via ${it.str("via_transport")}" }

    fun tableOfMesh(page: String, mesh: JsonObject): Table {
        val ts = mesh.arr("transports").map { it.str("name") }
        val routes = mesh.arr("routes")
        return Table(page,
            mesh.arr("nodes").map { RowRef(meshLaneOf(it, ts), it.str("name"), meshFields(it, routes)) },
            mesh.arr("peers").map { p ->
                EdgeRef(PEERS, p.str("from"), p.str("to"), p.strs("allowed_ips").joinToString(", "), "", "keepalive ${p.str("persistent_keepalive").ifBlank { "-" }}")
            })
    }

    const val PEERS = "peers"

    // ── graph builders ──────────────────────────────────────────────────────

    private class Acc(var node: GNode)

    private fun lanesOf(rule: JsonObject, seen: List<String>): List<GLane> {
        val declared = (rule["lanes"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { GLane(it.str("id"), it.str("label")) }
        return declared ?: seen.distinct().map { GLane(it, it) }
    }

    /** wg, auth, dns, security: the map's sections (uncapped) and the page's rule. */
    fun fromSections(page: String, rule: JsonObject, sections: List<OpsMaps.Section>, res: Resolver = Resolver()): Graph {
        val rules = rule.arr("sections")
        val resolve = rule.strs("resolve")
        val nodes = LinkedHashMap<String, Acc>()
        val edges = LinkedHashMap<String, GEdge>()
        val undrawn = mutableListOf<EdgeRef>()
        val clusters = mutableListOf<GCluster>()
        val depends = mutableListOf<GEdge>()
        fun ruleOf(title: String) = rules.firstOrNull { it.str("match").isNotBlank() && title.startsWith(it.str("match")) }
        fun put(n: GNode) {
            val old = nodes[n.id]
            if (old == null) nodes[n.id] = Acc(n)
            else old.node = old.node.copy(rows = old.node.rows + n.rows, bind = (old.node.bind + n.bind).distinct(),
                declared = if (n.declared == "row") "row" else old.node.declared, kind = if (n.declared == "row") n.kind else old.node.kind)
        }
        fun columnOf(r: JsonObject, row: OpsMaps.Row): Int = r.int("column") ?: r.obj("column_by")?.intMap("note_has")?.entries
            ?.firstOrNull { (w, _) -> row.note.split(' ', '-').any { it == w } }?.value ?: 0
        fun laneOf(r: JsonObject, row: OpsMaps.Row): String = when (r.str("lane_from")) {
            "edge_label" -> row.edges.firstOrNull()?.label?.let { res.aliasOf(it) }.orEmpty()
            "edge_to" -> row.edges.firstOrNull()?.to?.let { res.aliasOf(it) }.orEmpty()
            else -> r.str("lane")
        }
        // page root (Security)
        rule.obj("root")?.let { root ->
            put(GNode(root.str("id"), "root", root.str("label"), "page", "root", "", 0, "", emptyList(), root.strs("bind"), "root"))
        }
        // pass 1: one node per row
        val rowRule = mutableMapOf<String, JsonObject>()
        sections.forEach { s ->
            val r = ruleOf(s.title) ?: JsonObject(emptyMap())
            val ns = r.str("ns").ifBlank { "row" }
            r.obj("root")?.let { sr ->
                put(GNode(sr.str("id"), "server", sr.str("label"), s.title, "server", "", 1, "", emptyList(), sr.strs("bind"), "root"))
                rule.obj("root")?.let { pr ->
                    val id = "${pr.str("id")}->${sr.str("id")}#${r.str("root_edge")}"
                    edges[id] = GEdge(id, pr.str("id"), sr.str("id"), r.str("root_edge"), "root", false, emptyList(), Dep.TO_NEEDS_FROM, declared = "root")
                }
            }
            s.rows.forEach { row ->
                val id = "$ns:${row.name}"
                val bind = r.strs("bind").mapNotNull { b -> when (b) { "vm" -> "vm:${res.aliasOf(row.name)}"; "host" -> "host:${row.name}"; else -> null } } +
                    listOfNotNull(r.smap("bind_rows")[row.name])
                put(GNode(id, ns, row.name, row.note, ns, laneOf(r, row), columnOf(r, row), "", listOf(RowRef(s.title, row.name, rowFields(row))), bind, "row"))
                rowRule[id] = r
            }
        }
        // pass 2: edges, target nodes
        sections.forEach { s ->
            val r = ruleOf(s.title) ?: JsonObject(emptyMap())
            val ns = r.str("ns").ifBlank { "row" }
            val dep = when (r.str("dep")) { "from_needs_to" -> Dep.FROM_NEEDS_TO; "to_needs_from" -> Dep.TO_NEEDS_FROM; else -> Dep.NONE }
            r.obj("root")?.let { sr ->
                s.rows.forEach { row ->
                    val id = "${sr.str("id")}->$ns:${row.name}#${r.str("row_edge")}"
                    edges[id] = GEdge(id, sr.str("id"), "$ns:${row.name}", r.str("row_edge"), "root", false, emptyList(), dep, declared = "root")
                }
            }
            s.rows.forEach { row ->
                val from = "$ns:${row.name}"
                val src = nodes.getValue(from).node
                val members = mutableListOf<String>()
                row.edges.forEach { e ->
                    val ref = EdgeRef(s.title, row.name, e.to, e.label, e.state)
                    if (e.to.isBlank()) { undrawn += ref; return@forEach }
                    val overflow = e.to.startsWith("+") && e.to.endsWith(" more")
                    val to = res.apply(resolve, e.to)
                    val tid = if (overflow) "more:$from" else r.strs("targets").map { "$it:$to" }.firstOrNull { it in nodes }
                        ?: "${r.str("target_ns").ifBlank { r.strs("targets").firstOrNull() ?: ns }}:$to"
                    if (tid !in nodes) {
                        val tns = tid.substringBefore(':')
                        val tbind = r.strs("target_bind").mapNotNull { b ->
                            when (b) { "host" -> "host:$to"; "container" -> "container:$to"; "vm" -> "vm:${res.aliasOf(e.to)}"; else -> null }
                        }
                        put(GNode(tid, tns, if (overflow) e.to else to, if (tns == "address") res.aliasOf(e.to) else "", tns, src.lane,
                            r.int("target_column") ?: (src.column + 1), "", emptyList(), tbind, if (overflow) "overflow" else "target"))
                    }
                    members += tid
                    val id = "$from->$tid#${e.label}"
                    val old = edges[id]
                    val bind = if ("tunnel" in r.strs("bind")) listOf("tunnel:${res.aliasOf(row.name)}->${nodes.getValue(tid).node.label}") else emptyList()
                    edges[id] = old?.copy(rows = old.rows + ref) ?: GEdge(id, from, tid, e.label, r.str("edge_kind").ifBlank { "edge" },
                        e.state in DASHED || r.str("edge_kind") == "planned", listOf(ref), dep, bind)
                }
                if (r.bool("cluster_targets") && members.isNotEmpty()) {
                    val ms = members.distinct().filter { m -> clusters.none { m in it.members } }
                    val t = nodes.getValue(ms.firstOrNull() ?: members.first()).node
                    if (ms.isNotEmpty()) clusters += GCluster("cl:$from", "${ms.size} ${t.ns}s of ${row.name}", src.lane, t.column, ms)
                }
            }
        }
        // gates: a node whose rule says lane_by_target sits in its first target's lane
        nodes.values.forEach { a ->
            if (rowRule[a.node.id]?.bool("lane_by_target") == true) {
                val t = edges.values.firstOrNull { it.from == a.node.id }?.to?.let { nodes[it]?.node?.lane }
                a.node = a.node.copy(lane = t ?: lanesOf(rule, emptyList()).firstOrNull()?.id.orEmpty())
            }
        }
        val list = nodes.values.map { it.node }
        if (rule.str("cluster_by") == "lane_column") {
            list.groupBy { it.lane to it.column }.forEach { (k, ms) ->
                val label = rule.strs("columns").getOrNull(k.second) ?: "nodes"
                clusters += GCluster("cl:${k.first}:${k.second}", "${ms.size} ${label.lowercase()}", k.first, k.second, ms.map { it.id })
            }
        }
        val clusterOf = clusters.flatMap { c -> c.members.map { it to c.id } }.toMap()
        val placed = list.map { it.copy(cluster = clusterOf[it.id].orEmpty()) }
        // declared depends (DNS: the mesh names of each lane are resolved by the primary's address)
        rule.arr("depends").forEach { d ->
            val on = d.str("on").replace("{infra.dns.primary}", res.dnsPrimary)
            if (on !in nodes) return@forEach
            val fromIds = placed.filter { n -> n.rows.any { it.section.startsWith(d.str("from_section")) } }
            fromIds.groupBy { it.cluster.ifBlank { it.id } }.forEach { (cl, _) ->
                depends += GEdge("${d.str("id")}:$cl", cl, on, d.str("label"), "depends", true, emptyList(), Dep.FROM_NEEDS_TO, declared = "depends")
            }
        }
        val seenLanes = placed.map { it.lane }
        return Graph(page, lanesOf(rule, seenLanes), rule.strs("columns"), clusters, placed, edges.values.toList(), depends, undrawn)
    }

    private val DASHED = setOf("planned", "fallback")

    /** Routes: every node of the Network topology tree by its path id, parent to child; a node with children heads a cluster, closed from `open_depth`. */
    fun fromTree(page: String, rule: JsonObject, roots: List<TopologyModel.Node>): Graph {
        val cols = rule.strs("columns")
        val openDepth = rule.int("open_depth") ?: 2
        val labels = rule.smap("edge_label")
        val bindKeys = rule.smap("bind_keys")
        val bindKind = rule.smap("bind_kind")
        val nodes = mutableListOf<GNode>(); val edges = mutableListOf<GEdge>(); val clusters = mutableListOf<GCluster>()
        fun desc(n: TopologyModel.Node): List<String> = n.children.flatMap { listOf(it.id) + desc(it) }
        fun walk(n: TopologyModel.Node, lane: String, depth: Int, path: String) {
            val bind = n.keys.mapNotNull { k ->
                val (kind, v) = k.substringBefore(':') to k.substringAfter(':', "")
                bindKeys[kind]?.let { "$it:$v" }
            } + listOfNotNull(bindKind[n.kind]?.let { "$it:${n.label}" })
            nodes += GNode(n.id, "topo", n.label, n.metric, n.kind, lane, depth.coerceAtMost((cols.size - 1).coerceAtLeast(0)),
                "", listOf(RowRef(lane, n.id, treeFields(n, path))), bind.distinct(), "row")
            if (n.children.isNotEmpty()) clusters += GCluster("cl:${n.id}", "${n.label} (${n.children.size})", lane, depth, desc(n), head = n.id, openByDefault = depth < openDepth)
            n.children.forEach { c ->
                val label = labels[c.kind].orEmpty()
                edges += GEdge("${n.id}->${c.id}#$label", n.id, c.id, label, "tree", false, listOf(EdgeRef(lane, n.id, c.id, "")), Dep.TO_NEEDS_FROM)
                walk(c, lane, depth + 1, "$path / ${c.label}")
            }
        }
        roots.forEach { walk(it, it.id, 0, it.label) }
        val lanes = roots.map { GLane(it.id, it.label, it.metric) }
        return Graph(page, lanes, cols, clusters, nodes, edges, emptyList())
    }

    /** WG Mesh: lanes are the transports (the cards), nodes the mesh nodes, edges each directed peering; spokes depend on the hub. */
    fun fromMesh(page: String, rule: JsonObject, mesh: JsonObject): Graph {
        val transports = mesh.arr("transports")
        val tnames = transports.map { it.str("name") }
        val byRole = rule.obj("column_by")?.intMap("role").orEmpty()
        val routes = mesh.arr("routes")
        val nodes = LinkedHashMap<String, GNode>()
        mesh.arr("nodes").forEach { n ->
            val name = n.str("name"); val alias = n.str("alias").ifBlank { name }
            val lane = meshLaneOf(n, tnames)
            nodes["node:$name"] = GNode("node:$name", "node", alias, n.str("wg_ip").ifBlank { "no WG IP declared" }, n.str("role"), lane,
                byRole[n.str("role")] ?: byRole["client"] ?: 0, "", listOf(RowRef(lane, name, meshFields(n, routes))),
                if ("vm" in rule.strs("bind")) listOf("vm:$alias") else emptyList(), "row")
        }
        val pe = rule.obj("edges")?.obj("peers")
        val edges = LinkedHashMap<String, GEdge>()
        mesh.arr("peers").forEach { p ->
            val ref = EdgeRef(PEERS, p.str("from"), p.str("to"), p.strs("allowed_ips").joinToString(", "), "", "keepalive ${p.str("persistent_keepalive").ifBlank { "-" }}")
            val f = "node:${ref.from}"; val t = "node:${ref.to}"
            listOf(f to ref.from, t to ref.to).forEach { (id, name) ->
                if (id !in nodes) nodes[id] = GNode(id, "node", name, "not a declared mesh node", "target", nodes[f]?.lane ?: tnames.firstOrNull().orEmpty(), 1, "", emptyList(), emptyList(), "target")
            }
            val id = "$f->$t#${ref.label}"
            val bind = if ("tunnel" in rule.strs("bind")) listOf("tunnel:${nodes.getValue(f).label}->${nodes.getValue(t).label}") else emptyList()
            edges[id] = edges[id]?.let { it.copy(rows = it.rows + ref) } ?: GEdge(id, f, t, ref.label, pe?.str("kind")?.ifBlank { null } ?: "wg_peering", false, listOf(ref), Dep.NONE, bind)
        }
        val depends = rule.arr("depends").flatMap { d ->
            val hubs = nodes.values.filter { it.kind == d.str("on_role") }
            nodes.values.filter { it.kind == d.str("from_role") }.flatMap { s ->
                hubs.map { h -> GEdge("${d.str("id")}:${s.id}->${h.id}", s.id, h.id, d.str("label"), "depends", true, emptyList(), Dep.FROM_NEEDS_TO, declared = "depends") }
            }
        }
        val lanes = transports.map { t ->
            GLane(t.str("name"), t.str("name"), listOf("${t.str("protocol").uppercase()}/${t.str("port")}", t.str("endpoint"),
                if (t.bool("primary")) "primary" else if (t.bool("fallback")) "fallback" else "", "${t.str("active_peers").ifBlank { "0" }} active").filter { it.isNotBlank() }.joinToString("  "))
        }
        return Graph(page, lanes, rule.strs("columns"), emptyList(), nodes.values.toList(), edges.values.toList(), depends)
    }

    // ── one page: its table and its graph from the same inputs ────────────

    /** What every builder reads: the Ops map sources, the fleet slice (the resolver), the topology inputs (Routes) and mesh.json. */
    data class Inputs(val ops: OpsMaps.Sources, val slice: FleetModel.Slice, val topology: TopologyModel.Inputs, val mesh: JsonObject)

    fun resolver(slice: FleetModel.Slice, infra: TopologyModel.Infra): Resolver = Resolver(
        slice.vms.associate { it.id to it.alias }, slice.vms.mapNotNull { v -> v.wgIp?.let { it to v.alias } }.toMap(), infra.dnsPrimary)

    /** The table and the graph of [page], or null when the page declares no graph. The graph reads UNCAPPED sections. */
    fun page(v: Viewers, page: String, i: Inputs): Pair<Table, Graph>? {
        val rule = v.graph(page) ?: return null
        return when (rule.str("from")) {
            "map" -> {
                val sections = OpsMaps.build(rule.str("map"), i.ops, Int.MAX_VALUE)
                tableOfSections(page, sections) to fromSections(page, rule, sections, resolver(i.slice, i.ops.infra))
            }
            "topology" -> {
                val roots = TopologyModel.build(rule.str("view"), i.topology)
                tableOfTree(page, roots) to fromTree(page, rule, roots)
            }
            "mesh" -> tableOfMesh(page, i.mesh) to fromMesh(page, rule, i.mesh)
            else -> null
        }
    }

    // ── the sheet and parity ───────────────────────────────────────────────

    /** Every line the node sheet prints: the fields of every table row it stands for, then its edges, dependencies and binding. */
    fun sheet(n: GNode, g: Graph): List<Pair<String, String>> =
        listOf("id" to n.id, "kind" to n.kind, "lane" to n.lane, "declared" to n.declared) +
            n.rows.flatMap { r -> listOf("row" to r.section) + r.fields } +
            g.inEdges(n.id).map { "in" to "${g.node(it.from)?.label ?: it.from}  ${it.label}" } +
            g.outEdges(n.id).map { "out" to "${g.node(it.to)?.label ?: it.to}  ${it.label}" } +
            g.depends.filter { it.from == n.id || it.from == n.cluster }.map { "depends on" to (g.node(it.to)?.label ?: it.to) } +
            n.bind.map { "bound to" to it }

    /** Table against graph: empty = parity. */
    fun parity(t: Table, g: Graph): List<String> = buildList {
        val ids = g.nodes.groupingBy { it.id }.eachCount()
        ids.filter { it.value > 1 }.forEach { add("node ${it.key} built ${it.value} times") }
        val eids = g.edges.groupingBy { it.id }.eachCount()
        eids.filter { it.value > 1 }.forEach { add("edge ${it.key} built ${it.value} times") }
        val inGraph = g.nodes.flatMap { it.rows.map { r -> r.key } }.groupingBy { it }.eachCount()
        val inTable = t.rows.map { it.key }.groupingBy { it }.eachCount()
        inTable.forEach { (k, c) -> if ((inGraph[k] ?: 0) != c) add("table row $k is ${inGraph[k] ?: 0} nodes' row (want $c)") }
        (inGraph.keys - inTable.keys).forEach { add("node row $it is not a table row") }
        val eg = (g.edges.flatMap { it.rows } + g.undrawn).map { it.key }.groupingBy { it }.eachCount()
        val et = t.edges.map { it.key }.groupingBy { it }.eachCount()
        et.forEach { (k, c) -> if ((eg[k] ?: 0) != c) add("table edge $k is ${eg[k] ?: 0} graph edges (want $c)") }
        (eg.keys - et.keys).forEach { add("graph edge $it is not a table edge") }
        val endpoints = g.edges.filter { it.rows.isNotEmpty() }.flatMap { listOf(it.from, it.to) }.toSet()
        g.nodes.forEach { n ->
            when (n.declared) {
                "row" -> if (n.rows.isEmpty()) add("row node ${n.id} stands for no row")
                "target", "overflow" -> if (n.id !in endpoints) add("target ${n.id} is no table edge's end")
                "root" -> if (g.edges.none { it.from == n.id || it.to == n.id }) add("root ${n.id} has no declared edge")
                else -> add("node ${n.id} drawn but not declared (${n.declared})")
            }
        }
        g.edges.forEach { e ->
            if (e.declared == "table" && e.rows.isEmpty()) add("edge ${e.id} drawn but not declared")
            if (ids[e.from] == null || ids[e.to] == null) add("edge ${e.id} has a missing end")
        }
        val clusterIds = g.clusters.map { it.id }.toSet()
        g.depends.forEach { d -> if (ids[d.to] == null || (ids[d.from] == null && d.from !in clusterIds)) add("depends ${d.id} has a missing end") }
        g.nodes.forEach { n ->
            val shown = sheet(n, g)
            n.rows.flatMap { it.fields }.forEach { f -> if (f !in shown) add("sheet of ${n.id} misses ${f.first}=${f.second}") }
        }
    }
}

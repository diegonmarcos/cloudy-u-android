package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1480 The Ops maps (Network, Mail, the Orchestration flow) as pure functions: data/ops_maps.json plus the fleet's own data in,
 * sections of nodes with their outgoing edges out. A section with `rows` is drawn as declared; a section with `from` is DERIVED
 * here from mesh.json, infra_slice.json, containers_declared.json or the fleet slice, so nothing the fleet already declares is
 * typed twice. Names of services, VMs or hosts never appear in this file: they come from the data.
 */
object OpsMaps {

    data class Edge(val to: String, val label: String = "", val state: String = "")
    data class Row(val name: String, val note: String = "", val state: String = "", val edges: List<Edge> = emptyList())
    data class Section(val title: String, val rows: List<Row>)

    /** What the derived sections read. */
    data class Sources(
        val decl: JsonObject,
        val mesh: JsonObject,
        val infra: TopologyModel.Infra,
        val infraRaw: JsonObject,
        val catalog: ContainersModel.Catalog,
        val domains: List<FleetModel.Domain>,
    )

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** The one-line summary of a map. */
    fun summary(decl: JsonObject, id: String): String = decl.obj("maps")?.obj(id)?.str("summary").orEmpty()

    fun build(id: String, s: Sources): List<Section> =
        s.decl.obj("maps")?.obj(id)?.arr("sections").orEmpty().flatMap { sec ->
            val title = sec.str("title")
            val from = sec.str("from")
            if (from.isNotBlank()) derived(from, title, sec, s) else listOf(Section(title, sec.arr("rows").map(::row)))
        }.filter { it.rows.isNotEmpty() }

    private fun row(o: JsonObject) = Row(o.str("name"), o.str("note"), o.str("state"),
        o.arr("edges").map { Edge(it.str("to"), it.str("label"), it.str("state")) })

    /** At most the declared `edge_cap` edges, then one "+n more" edge, so a tier with sixty members stays a row. */
    private fun capped(s: Sources, edges: List<Edge>): List<Edge> {
        val cap = (s.decl["edge_cap"] as? JsonPrimitive)?.intOrNull ?: edges.size
        return if (edges.size <= cap) edges else edges.take(cap) + Edge("+${edges.size - cap} more")
    }

    private fun derived(from: String, title: String, sec: JsonObject, s: Sources): List<Section> = when (from) {
        "infra_wireguard" -> s.infra.subnets.map { n ->
            Section("$title: ${n.name} ${n.cidr}", n.peers.map { p ->
                Row(p.name, listOf(p.ip, p.role).filter { it.isNotBlank() }.joinToString(" "), "",
                    if (p.name == n.hub || p.role == "hub") emptyList() else listOf(Edge(n.hub, n.name)))
            })
        }
        "mesh_transports" -> listOf(Section(title, s.mesh.arr("transports").map { t ->
            val state = if ((t["primary"] as? JsonPrimitive)?.contentOrNull == "true") "primary" else if ((t["fallback"] as? JsonPrimitive)?.contentOrNull == "true") "fallback" else ""
            val peers = (t["active_peers"] as? JsonPrimitive)?.intOrNull ?: 0
            Row(t.str("name"), "${t.str("label")} - ${t.str("protocol")}/${(t["port"] as? JsonPrimitive)?.contentOrNull.orEmpty()}", state,
                listOf(Edge(s.mesh.arr("nodes").firstOrNull { it.str("role") == "hub" }?.str("alias").orEmpty(), "$peers active peers")))
        }))
        "auth_routes" -> listOf(Section(title, s.infra.routes.groupBy { it.auth }.map { (tier, rs) ->
            Row(tier, s.decl.obj("auth_kinds")?.str(tier).orEmpty(), "", capped(s, rs.map { Edge(it.host, it.service) }))
        }))
        "auth_containers" -> listOf(Section(title, s.catalog.entries.groupBy { it.authType }.map { (kind, es) ->
            Row(kind.ifBlank { "-" }, "${es.size} containers - ${s.decl.obj("auth_kinds")?.str(kind).orEmpty()}", "",
                capped(s, es.map { Edge(it.name, it.host) }))
        }))
        "caddy_routes" -> s.infra.routes.groupBy { it.vm }.map { (vm, rs) ->
            Section("$title: $vm", rs.map { r ->
                Row(r.host, listOf(r.auth, if (r.wgOnly) "wg only" else "public").filter { it.isNotBlank() }.joinToString(" - "), "",
                    listOf(Edge(r.service, r.vm)))
            })
        }
        "mesh_dns" -> listOf(Section("$title (primary ${s.infra.dnsPrimary})", s.infra.dns.map { Row(it.name, "", "", listOf(Edge(it.value, it.service))) }))
        "containers" -> {
            val want = sec.strs("names").toSet()
            listOf(Section(title, s.catalog.entries.filter { it.name in want }.map { e ->
                Row(e.name, listOf(e.host, e.port?.toString().orEmpty(), e.authType).filter { it.isNotBlank() }.joinToString(" - "), "", emptyList())
            }))
        }
        "mail_hosts" -> listOf(Section(title, s.infraRaw.obj("mail")?.arr("hosts").orEmpty().map { Row(it.str("host"), it.str("service"), "", listOf(Edge(it.str("vm")))) }))
        "mail_routes" -> listOf(Section(title, s.infraRaw.obj("mail")?.arr("routes").orEmpty().map {
            Row(it.str("name"), "${it.str("address")}:${(it["port"] as? JsonPrimitive)?.contentOrNull.orEmpty()}", if ((it["default"] as? JsonPrimitive)?.contentOrNull == "true") "default" else "")
        }))
        "mail_mx" -> listOf(Section(title, s.infraRaw.obj("mail")?.obj("mta_sts")?.strs("mx").orEmpty().map { Row(it) }))
        "mail_dns" -> {
            val groups = s.decl.strs("mail_dns_groups")
            listOf(Section(title, s.domains.flatMap { d -> d.records.filter { it.group in groups } }.map { Row(it.name, it.type, it.group) }.sortedBy { groups.indexOf(it.state) }))
        }
        else -> emptyList()
    }
}

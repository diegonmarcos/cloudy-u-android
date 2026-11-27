package com.diegonmarcos.cloudc3.pages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1926 Ops > Mail: the declaration (data/mail_stack.json), the answer of cloudy-c3-api GET /mail/stack, and the pure functions
 * between them (health colour, filters, declared rows, the render plan of a topology and its geometry). Text in, models out, so the
 * JVM suite runs the exact code the phone runs. No host name lives here: tables, graphs and probe bindings are DATA.
 */
object MailStack {

    // ── declaration ────────────────────────────────────────────────────────

    data class Column(val key: String, val label: String)
    /** [map] / [section] (#1955 B): a declared table read from that section of data/ops_maps.json maps.<map> (the old Mail subpage, merged into Tables). */
    data class Table(val id: String, val title: String, val source: String, val filters: List<String>, val columns: List<Column>, val map: String = "", val section: String = "")
    data class Node(
        val id: String, val label: String, val kind: String, val x: Float, val y: Float, val role: String, val host: String, val vm: String,
        val container: String, val ports: List<Int>, val tls: List<String>, val probes: List<String>, val queueProbe: String, val logs: List<String>,
    )
    data class Edge(val id: String, val from: String, val to: String, val label: String, val probes: List<String>, val dashed: Boolean)
    data class Topology(val id: String, val title: String, val nodes: List<Node>, val edges: List<Edge>)
    data class Decl(
        val apiPath: String, val staleAfterMinutes: Int, val nodeW: Float, val nodeH: Float,
        val tables: List<Table>, val topologies: List<Topology>,
    ) {
        fun topology(id: String): Topology? = topologies.firstOrNull { it.id == id }
        fun table(id: String): Table? = tables.firstOrNull { it.id == id }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonObject.num(k: String): Float = (this[k] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
    private fun JsonObject.objs(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun parseDecl(text: String): Decl {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Decl("/mail/stack", 360, 168f, 72f, emptyList(), emptyList())
        val tables = o.objs("tables").map { t ->
            Table(t.str("id"), t.str("title"), t.str("source"), t.strs("filters"), t.objs("columns").map { Column(it.str("key"), it.str("label")) }, t.str("map"), t.str("section"))
        }
        val topos = (o["topologies"] as? JsonObject).orEmpty().mapNotNull { (id, v) ->
            val t = v as? JsonObject ?: return@mapNotNull null
            Topology(id, t.str("title"), t.objs("nodes").map { n ->
                Node(n.str("id"), n.str("label"), n.str("kind"), n.num("x"), n.num("y"), n.str("role"), n.str("host"), n.str("vm"), n.str("container"),
                    (n["ports"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }, n.strs("tls"), n.strs("probes"), n.str("queue_probe"), n.strs("logs"))
            }, t.objs("edges").map { e -> Edge(e.str("id"), e.str("from"), e.str("to"), e.str("label"), e.strs("probes"), (e["dashed"] as? JsonPrimitive)?.booleanOrNull == true) })
        }
        return Decl(o.str("api_path").ifBlank { "/mail/stack" }, (o["stale_after_minutes"] as? JsonPrimitive)?.intOrNull ?: 360,
            o.num("node_w").takeIf { it > 0 } ?: 168f, o.num("node_h").takeIf { it > 0 } ?: 72f, tables, topos)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    /** Problems in a declaration itself: a duplicate id, an edge to a node that is not declared, a blank probe id. Empty = sound. */
    fun validate(d: Decl): List<String> = buildList {
        if (d.tables.map { it.id }.toSet().size != d.tables.size) add("duplicate table id")
        d.tables.forEach { t -> if (t.columns.map { it.key }.toSet().size != t.columns.size) add("table ${t.id}: duplicate column") }
        d.topologies.forEach { t ->
            val ids = t.nodes.map { it.id }
            if (ids.toSet().size != ids.size) add("${t.id}: duplicate node id")
            if (t.edges.map { it.id }.toSet().size != t.edges.size) add("${t.id}: duplicate edge id")
            t.edges.forEach { e ->
                if (e.from !in ids) add("${t.id}/${e.id}: from ${e.from} is not a node")
                if (e.to !in ids) add("${t.id}/${e.id}: to ${e.to} is not a node")
            }
            (t.nodes.flatMap { it.probes + it.queueProbe } + t.edges.flatMap { it.probes }).filter { it.isNotEmpty() }.forEach { if (it.isBlank()) add("${t.id}: blank probe id") }
        }
    }

    // ── the answer ─────────────────────────────────────────────────────────

    enum class Health { OK, WARN, BAD, UNKNOWN }

    fun healthOf(state: String): Health = when (state) { "ok" -> Health.OK; "warn" -> Health.WARN; "bad" -> Health.BAD; else -> Health.UNKNOWN }

    data class Probe(
        val id: String, val state: Health, val detail: String, val latencyMs: Int?, val checkedAt: String, val queue: Int?,
        val errors: List<String>, val checks: Int, val source: String,
    )
    data class ReportInfo(val generated: String, val ageText: String, val stale: Boolean, val durationMs: Int)
    data class NotServed(val section: String, val field: String, val reason: String)
    /** A table row: only the keys the API served; an absent key is "not served", an empty value is a blank. */
    typealias Row = Map<String, String>

    data class Answer(
        val probes: Map<String, Probe>, val tables: Map<String, List<Row>>, val notServed: List<NotServed>,
        val report: ReportInfo?, val reportError: String, val liveError: String, val generatedAt: String,
    )

    private fun JsonElement?.text(): String? = when (this) { null, JsonNull -> null; is JsonPrimitive -> contentOrNull; else -> toString() }

    /** Null when the body is not a mail stack answer (so the page says "unreadable", not "all quiet"). */
    fun parseAnswer(text: String): Answer? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (o["probes"] !is JsonArray || o["tables"] !is JsonObject) return null
        val probes = o.objs("probes").map { p ->
            Probe(p.str("id"), healthOf(p.str("state")), p.str("detail"), (p["latencyMs"] as? JsonPrimitive)?.doubleOrNull?.toInt(), p.str("checkedAt"),
                (p["queue"] as? JsonPrimitive)?.doubleOrNull?.toInt(), p.strs("errors"), (p["checks"] as? JsonPrimitive)?.intOrNull ?: 0, p.str("source"))
        }.associateBy { it.id }
        val tables = (o["tables"] as JsonObject).mapValues { (_, v) ->
            (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { r -> r.entries.associate { (k, e) -> k to (e.text() ?: "") } }
        }
        val rep = (o["report"] as? JsonObject)?.let { r ->
            ReportInfo(r.str("generated"), r.str("ageText"), (r["stale"] as? JsonPrimitive)?.booleanOrNull == true, (r["durationMs"] as? JsonPrimitive)?.intOrNull ?: 0)
        }
        return Answer(probes, tables, o.objs("notServed").map { NotServed(it.str("section"), it.str("field"), it.str("reason")) }, rep,
            o.str("reportError"), o.str("liveError"), o.str("generatedAt"))
    }

    // ── health ─────────────────────────────────────────────────────────────

    private fun rank(h: Health) = when (h) { Health.BAD -> 3; Health.WARN -> 2; Health.OK -> 1; Health.UNKNOWN -> 0 }

    /** A report-sourced green from a stale report is amber: an old "ok" is not evidence. */
    fun effective(p: Probe, a: Answer): Health =
        if (p.state == Health.OK && p.source == "report" && a.report?.stale == true) Health.WARN else p.state

    /** Worst served state of [ids]; UNKNOWN when none of them is served (a probe that is not served never makes a block green). */
    fun aggregate(ids: List<String>, a: Answer?): Health {
        if (a == null) return Health.UNKNOWN
        val served = ids.mapNotNull { a.probes[it] }.map { effective(it, a) }
        return served.maxByOrNull(::rank) ?: Health.UNKNOWN
    }

    fun nodeHealth(n: Node, a: Answer?): Health = aggregate(n.probes, a)

    /** A wire with its own probes takes their worst state; one without takes the worst of its two ends (never grey between two coloured blocks). */
    fun edgeHealth(e: Edge, t: Topology, a: Answer?): Health {
        if (e.probes.isNotEmpty()) {
            val own = aggregate(e.probes, a)
            if (own != Health.UNKNOWN) return own
        }
        val ends = listOfNotNull(t.nodes.firstOrNull { it.id == e.from }, t.nodes.firstOrNull { it.id == e.to }).map { nodeHealth(it, a) }
        return ends.maxByOrNull(::rank) ?: Health.UNKNOWN
    }

    /** green / amber / red from the palette's three state tokens; grey (secondary text) for unknown. */
    fun healthColor(h: Health, p: KitPalette): Color = when (h) {
        Health.OK -> p.ok
        Health.WARN -> p.warn
        Health.BAD -> p.bad
        Health.UNKNOWN -> p.textSecondary
    }

    fun healthLabel(h: Health): String = when (h) { Health.OK -> "healthy"; Health.WARN -> "degraded"; Health.BAD -> "down"; Health.UNKNOWN -> "unknown" }

    // ── detail sheet ───────────────────────────────────────────────────────

    data class ProbeLine(val id: String, val health: Health, val detail: String, val latencyMs: Int?, val checkedAt: String)
    data class NodeDetail(
        val node: Node, val health: Health, val probes: List<ProbeLine>, val notServed: List<String>, val lastCheck: String,
        val latencyMs: Int?, val queue: Int?, val errors: List<String>, val tls: List<ProbeLine>,
    )

    fun detail(n: Node, a: Answer?): NodeDetail {
        val served = n.probes.mapNotNull { a?.probes?.get(it) }
        val lines = served.map { ProbeLine(it.id, effective(it, a!!), it.detail, it.latencyMs, it.checkedAt) }
        val tls = n.tls.mapNotNull { h -> a?.probes?.get("live.tls.$h")?.let { ProbeLine(it.id, effective(it, a), it.detail, it.latencyMs, it.checkedAt) } }
        return NodeDetail(
            n, nodeHealth(n, a), lines, n.probes.filter { a?.probes?.containsKey(it) != true },
            served.mapNotNull { it.checkedAt.takeIf { c -> c.isNotBlank() } }.maxOrNull().orEmpty(),
            served.mapNotNull { it.latencyMs }.maxOrNull(),
            n.queueProbe.takeIf { it.isNotBlank() }?.let { a?.probes?.get(it)?.queue },
            served.flatMap { it.errors }.take(20), tls,
        )
    }

    // ── tables ─────────────────────────────────────────────────────────────

    const val NOT_SERVED = "not served"

    /** A cell: the served value, a dash for a served blank, "not served" when the API does not carry the key at all. */
    fun cell(r: Row, key: String): String = r[key]?.let { it.ifBlank { "-" } } ?: NOT_SERVED

    /** The rows of a declared-source table, from the app's own declarations (zone records, hosts, relays, MX). */
    fun declaredRows(id: String, infraRaw: JsonObject, domains: List<FleetModel.Domain>): List<Row> = when (id) {
        "dns_declared" -> domains.flatMap { d ->
            d.records.filter { it.group in FleetModel.MAIL_GROUPS }.map { r ->
                mapOf("domain" to d.name, "group" to r.group, "type" to r.type, "name" to r.name, "content" to r.content, "ttl" to (r.ttl?.toString() ?: ""),
                    "priority" to (r.priority?.toString() ?: ""), "comment" to (r.comment ?: ""))
            }
        }
        "hosts_declared" -> (infraRaw["mail"] as? JsonObject).orEmpty().objs("hosts").map { mapOf("service" to it.str("service"), "host" to it.str("host"), "vm" to it.str("vm")) }
        "relays_declared" -> {
            val mail = (infraRaw["mail"] as? JsonObject).orEmpty()
            val sts = (mail["mta_sts"] as? JsonObject).orEmpty()
            mail.objs("routes").map {
                mapOf("kind" to "relay", "name" to it.str("name"), "address" to it.str("address"), "port" to it.str("port"),
                    "default" to ((it["default"] as? JsonPrimitive)?.booleanOrNull == true).toString(), "desc" to it.str("desc"))
            } + sts.strs("mx").map {
                mapOf("kind" to "mx", "name" to it, "address" to it, "port" to "25", "default" to "", "desc" to "MX host named by the MTA-STS policy")
            } + (if (sts.str("domain").isBlank()) emptyList() else listOf(
                mapOf("kind" to "mta-sts", "name" to sts.str("domain"), "address" to sts.str("policy_path"), "port" to "443", "default" to "",
                    "desc" to "mode ${sts.str("mode")}, max_age ${sts.str("max_age")}")))
        }
        else -> emptyList()
    }

    /**
     * #1955 B the rows of a map-bound table: a path section (Inbound, Outbound) gives one row per edge (node, note, state, to, label, edge_state;
     * a node with no edge is one row with a blank edge), the Containers section the catalog entries it names (name, host, port, auth_type).
     */
    fun mapRows(t: Table, src: OpsMaps.Sources): List<Row> {
        val sec = (src.decl["maps"] as? JsonObject)?.let { it[t.map] as? JsonObject }?.objs("sections").orEmpty().firstOrNull { it.str("title") == t.section }
        if (sec?.str("from") == "containers") {
            val want = sec.strs("names")
            return want.mapNotNull { n -> src.catalog.entry(n) }.map { e ->
                mapOf("name" to e.name, "host" to e.host, "port" to (e.port?.toString() ?: ""), "auth_type" to e.authType)
            }
        }
        return OpsMaps.build(t.map, src).filter { it.title == t.section }.flatMap { section ->
            section.rows.flatMap { r ->
                val base = mapOf("node" to r.name, "note" to r.note, "state" to r.state)
                if (r.edges.isEmpty()) listOf(base + mapOf("to" to "", "label" to "", "edge_state" to ""))
                else r.edges.map { e -> base + mapOf("to" to e.to, "label" to e.label, "edge_state" to e.state) }
            }
        }
    }

    /** The rows a section shows: declared sources from the app's data (a map-bound table from [maps]), the rest from the API answer (empty when it has none). */
    fun rows(t: Table, a: Answer?, infraRaw: JsonObject, domains: List<FleetModel.Domain>, maps: OpsMaps.Sources? = null): List<Row> = when {
        t.source == "declared" && t.map.isNotBlank() -> maps?.let { mapRows(t, it) }.orEmpty()
        t.source == "declared" -> declaredRows(t.id, infraRaw, domains)
        else -> a?.tables?.get(t.id).orEmpty()
    }

    /** Case-insensitive search over every declared column of the row, then the exact-value filters (column key to value). */
    fun filter(t: Table, rows: List<Row>, query: String, picked: Map<String, String>): List<Row> {
        val q = query.trim().lowercase()
        return rows.filter { r ->
            (q.isEmpty() || t.columns.any { c -> r[c.key].orEmpty().lowercase().contains(q) }) &&
                picked.all { (k, v) -> r[k].orEmpty() == v }
        }
    }

    /** The distinct values of a filter column, in first-seen order. */
    fun choices(rows: List<Row>, key: String): List<String> = rows.mapNotNull { it[key]?.takeIf { v -> v.isNotBlank() } }.distinct()

    /** Why a section has no rows: the API's own reason, else that the API has not answered. */
    fun emptyReason(t: Table, a: Answer?): String = when {
        t.source == "declared" -> "nothing declared"
        a == null -> "not served (the API has not answered)"
        else -> a.notServed.firstOrNull { it.section == t.id }?.reason ?: "no rows"
    }

    /** Declared keys the served rows never carry: the app prints them as "not served". Empty when the API serves every declared field. */
    fun missingFields(t: Table, rows: List<Row>): List<String> =
        if (rows.isEmpty()) emptyList() else t.columns.map { it.key }.filter { k -> rows.none { k in it } }

    // ── topology plan (what the canvas draws, once each) ───────────────────

    data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun contains(p: Offset) = p.x in l..r && p.y in t..b
    }
    data class NodePrim(val id: String, val label: String, val sub: String, val rect: Rect, val health: Health, val color: Color, val kind: String)
    data class EdgePrim(
        val id: String, val from: String, val to: String, val label: String, val p0: Offset, val p1: Offset, val p2: Offset, val p3: Offset,
        val health: Health, val color: Color, val dashed: Boolean,
    ) {
        /** UNKNOWN wires carry no light: grey and still says "no data is known to flow". */
        val flowing get() = health != Health.UNKNOWN
    }
    data class Plan(val nodes: List<NodePrim>, val edges: List<EdgePrim>, val bounds: Rect)

    private fun anchors(f: Rect, t: Rect): List<Offset> {
        val dx = ((t.l - f.r).coerceAtLeast(40f)) * 0.5f
        return if (t.l >= f.r - 1f) listOf(Offset(f.r, f.cy), Offset(f.r + dx, f.cy), Offset(t.l - dx, t.cy), Offset(t.l, t.cy))
        else if (t.t >= f.b) { val m = (t.t - f.b) / 2f + 10f; listOf(Offset(f.cx, f.b), Offset(f.cx, f.b + m), Offset(t.cx, t.t - m), Offset(t.cx, t.t)) }
        else { val m = (f.t - t.b) / 2f + 10f; listOf(Offset(f.cx, f.t), Offset(f.cx, f.t - m), Offset(t.cx, t.b + m), Offset(t.cx, t.b)) }
    }

    fun plan(t: Topology, a: Answer?, d: Decl, p: KitPalette): Plan {
        val nodes = t.nodes.map { n ->
            val h = nodeHealth(n, a)
            NodePrim(n.id, n.label, n.host, Rect(n.x, n.y, n.x + d.nodeW, n.y + d.nodeH), h, healthColor(h, p), n.kind)
        }
        val byId = nodes.associateBy { it.id }
        val edges = t.edges.mapNotNull { e ->
            val f = byId[e.from] ?: return@mapNotNull null
            val to = byId[e.to] ?: return@mapNotNull null
            val h = edgeHealth(e, t, a)
            val g = anchors(f.rect, to.rect)
            EdgePrim(e.id, e.from, e.to, e.label, g[0], g[1], g[2], g[3], h, healthColor(h, p), e.dashed)
        }
        val b = if (nodes.isEmpty()) Rect(0f, 0f, 1f, 1f) else Rect(nodes.minOf { it.rect.l }, nodes.minOf { it.rect.t }, nodes.maxOf { it.rect.r }, nodes.maxOf { it.rect.b })
        return Plan(nodes, edges, b)
    }

    /** Declaration against render: every declared node and edge appears exactly once, and nothing undeclared is drawn. Empty = parity. */
    fun parity(t: Topology, plan: Plan): List<String> = buildList {
        val n = plan.nodes.groupingBy { it.id }.eachCount()
        val e = plan.edges.groupingBy { it.id }.eachCount()
        t.nodes.forEach { if ((n[it.id] ?: 0) != 1) add("node ${it.id} drawn ${n[it.id] ?: 0} times") }
        t.edges.forEach { if ((e[it.id] ?: 0) != 1) add("edge ${it.id} drawn ${e[it.id] ?: 0} times") }
        (n.keys - t.nodes.map { it.id }.toSet()).forEach { add("node $it drawn but not declared") }
        (e.keys - t.edges.map { it.id }.toSet()).forEach { add("edge $it drawn but not declared") }
    }

    // ── geometry (pan, zoom, pulses, hit test) ─────────────────────────────

    data class View(val scale: Float, val ox: Float, val oy: Float) {
        fun toScreen(w: Offset) = Offset(w.x * scale + ox, w.y * scale + oy)
        fun toWorld(s: Offset) = Offset((s.x - ox) / scale, (s.y - oy) / scale)
        /** Zoom by [factor] about the screen point [c] (it stays under the finger), then pan by [pan]. */
        fun transform(c: Offset, pan: Offset, factor: Float): View {
            val s = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val k = s / scale
            return View(s, c.x - (c.x - ox) * k + pan.x, c.y - (c.y - oy) * k + pan.y)
        }
    }
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4f

    /** The view that shows all of [b] centred in a [w] x [h] canvas with [pad] px around it. */
    fun fit(b: Rect, w: Float, h: Float, pad: Float = 24f): View {
        val bw = (b.r - b.l).coerceAtLeast(1f)
        val bh = (b.b - b.t).coerceAtLeast(1f)
        val s = minOf((w - 2 * pad) / bw, (h - 2 * pad) / bh).coerceIn(MIN_SCALE, 1.5f)
        return View(s, (w - bw * s) / 2 - b.l * s, (h - bh * s) / 2 - b.t * s)
    }

    fun bezier(e: EdgePrim, t: Float): Offset {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return Offset(a * e.p0.x + b * e.p1.x + c * e.p2.x + d * e.p3.x, a * e.p0.y + b * e.p1.y + c * e.p2.y + d * e.p3.y)
    }

    const val PULSES_PER_EDGE = 3

    /** Positions of the light pulses of [e] at animation phase [phase] in 0..1: always ordered along from -> to, advancing with the phase. */
    fun pulses(e: EdgePrim, phase: Float): List<Offset> =
        if (!e.flowing) emptyList() else (0 until PULSES_PER_EDGE).map { i -> bezier(e, (phase + i.toFloat() / PULSES_PER_EDGE) % 1f) }

    /** The node under the screen point (the last drawn wins), null on empty canvas. */
    fun hit(plan: Plan, v: View, screen: Offset): String? = v.toWorld(screen).let { w -> plan.nodes.lastOrNull { it.rect.contains(w) }?.id }

    /** What the canvas exposes to accessibility and the tests: the ids it draws, in order. */
    fun drawnSummary(plan: Plan): String = "nodes:" + plan.nodes.joinToString(",") { it.id } + " edges:" + plan.edges.joinToString(",") { it.id }
}

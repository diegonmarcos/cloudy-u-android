package com.diegonmarcos.cloudc3.pages

import com.diegonmarcos.cloudc3.cloud.Services
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * #1955 C A2 The Health debugger's model. NO check logic is here (owner rule: health checks are Rust): the phased probes, the dependency
 * graph, the root cause and the layer lights come from libs:health-core-rs ([com.diegonmarcos.cloudlib.healthcore.HealthCore]); this file
 * declares the layers, turns the app's own readings into c3-report/v1 checks, parses what the engine answers and what the fleet engines
 * publish, and lays out the rows. A reading the phone cannot take is BLIND or NOT_SERVED with its reason; it never becomes a number or a VM's fault.
 */
object HealthModel {

    /** The statuses of the c3-report/v1 envelope. Only OK, WARN and DOWN may print a number. */
    enum class St(val id: String) {
        OK("ok"), WARN("warn"), DOWN("down"), PENDING("pending"), BLIND("blind"), NOT_SERVED("not_served"), GATED("gated");
        val carriesNumber get() = this == OK || this == WARN || this == DOWN
        companion object { fun parse(v: String): St = values().firstOrNull { it.id == v } ?: if (v == "unknown") BLIND else PENDING }
    }

    /** One declared layer: [rank] is the attribution order (lower is more fundamental); the list below is the display order. */
    data class Layer(val id: String, val label: String, val rank: Int, val dependsOn: List<String>)

    /** The layers, in the owner's display order: Lights, Containers, VMs, Mesh, then the rest. */
    val LAYERS: List<Layer> = listOf(
        Layer("lights", "Lights (Public / Private URLs)", 9, listOf("edge", "auth", "mesh", "containers", "certs")),
        Layer("containers", "Containers", 3, listOf("vms")),
        Layer("vms", "VMs", 1, listOf("vantage")),
        Layer("mesh", "Network / Mesh", 2, listOf("vms")),
        Layer("edge", "Edge (DNS, Caddy, routes)", 5, listOf("vms", "mesh", "containers", "certs")),
        Layer("auth", "Auth", 6, listOf("containers", "edge")),
        Layer("data", "Databases and backups", 7, listOf("containers")),
        Layer("mail", "Mail", 10, listOf("containers", "mesh", "edge", "certs")),
        Layer("certs", "Certificates and domains", 4, emptyList()),
        Layer("delivery", "CI / Deploys / Reports", 11, listOf("control", "containers")),
        Layer("alerts", "Alerts / Incidents", 12, listOf("control")),
        Layer("devices", "Phones and peers", 13, listOf("mesh")),
        Layer("control", "Control plane (C3 API, MCP)", 8, listOf("edge", "mesh", "auth", "containers")),
        Layer("vantage", "This phone", 0, emptyList()),
    )

    /** The layers that have a phone reading today; every other layer reads a fleet engine report, which is not served until #1977. */
    val PHONE_LAYERS = listOf("lights", "containers", "vms", "mesh", "control", "delivery", "vantage")
    const val NOT_SERVED_ROUTE = "route not served yet (#1977)"
    const val NO_TOKEN_NOTE = "this device has no C3 token: Configs → Profile → Config import"

    data class Phases(val dns: Long? = null, val tcp: Long? = null, val tls: Long? = null, val http: Long? = null) {
        val total: Long get() = listOfNotNull(dns, tcp, tls, http).sum()
        val any get() = dns != null || tcp != null || tls != null || http != null
        fun list() = listOf("dns" to dns, "tcp" to tcp, "tls" to tls, "http" to http)
    }

    data class Check(
        val id: String, val layer: String, val name: String, val status: St,
        val details: String = "", val entity: String = "", val kind: String = "",
        val phases: Phases = Phases(), val code: Int? = null, val durationMs: Long? = null,
        val needs: List<String> = emptyList(), val evidence: List<String> = emptyList(),
        val explainedBy: String? = null, val confidence: String = "measured", val vantage: String = "phone",
        /** Labelled facts of the declared row (the Lights columns), printed on the row and its detail. */
        val facts: List<Pair<String, String>> = emptyList(),
    ) {
        /** A number (a total time) is printed only for a status that carries one. */
        val timeText: String get() = if (status.carriesNumber && phases.any) "${phases.total} ms" else ""
    }

    // ── the engine's answers ────────────────────────────────────────────────

    data class ProbeOut(val status: St, val phases: Phases, val code: Int?, val error: String, val addr: String)
    data class Light(val layer: String, val label: String, val rank: Int, val light: String, val ok: Int, val warn: Int, val down: Int, val caused: Int, val blocked: Int, val notServed: Int)
    data class Analysis(val status: String, val roots: List<String>, val explained: Map<String, String?>, val lights: List<Light>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.o(k: String) = this[k] as? JsonObject
    private fun JsonObject.a(k: String) = (this[k] as? JsonArray).orEmpty()
    private fun JsonObject.strs(k: String) = a(k).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun parseObj(text: String) = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    private fun phasesOf(o: JsonObject?) = if (o == null) Phases() else Phases(o.l("dns"), o.l("tcp"), o.l("tls"), o.l("http"))

    fun parseProbe(text: String): ProbeOut? {
        val o = parseObj(text) ?: return null
        return ProbeOut(St.parse(o.s("status")), phasesOf(o.o("phases")), o.l("code")?.toInt(), o.s("error"), o.s("addr"))
    }

    fun parseAnalysis(text: String): Analysis? {
        val o = parseObj(text) ?: return null
        val lights = o.a("lights").mapNotNull { it as? JsonObject }.map { l ->
            val k = l.o("counts")
            fun n(x: String) = k?.l(x)?.toInt() ?: 0
            Light(l.s("layer"), l.s("label"), l.l("rank")?.toInt() ?: 0, l.s("light"), n("ok"), n("warn"), n("down"), n("caused"), n("blocked"), n("not_served"))
        }
        val explained = o.a("checks").mapNotNull { it as? JsonObject }.associate { c -> c.s("id") to c.s("explained_by").ifBlank { null } }
        return Analysis(o.s("status"), o.strs("roots").let { r -> r }, explained, lights)
    }

    /** The layers as the engine takes them: `[{id,label,rank,depends_on[]}]`. */
    fun layersJson(layers: List<Layer> = LAYERS): String = layers.joinToString(",", "[", "]") { l ->
        "{\"id\":${q(l.id)},\"label\":${q(l.label)},\"rank\":${l.rank},\"depends_on\":${l.dependsOn.joinToString(",", "[", "]") { q(it) }}}"
    }

    private fun q(s: String) = JsonPrimitive(s).toString()
    private fun n(v: Long?) = v?.toString() ?: "null"

    /** A c3-report/v1 envelope of [checks] for the engine's `analyze`. */
    fun reportJson(checks: List<Check>, vantage: String = "phone", at: String = ""): String {
        val cs = checks.joinToString(",", "[", "]") { c ->
            "{\"id\":${q(c.id)},\"layer\":${q(c.layer)},\"entity\":{\"kind\":${q(c.kind)},\"id\":${q(c.entity)}},\"name\":${q(c.name)}," +
                "\"status\":${q(c.status.id)},\"details\":${q(c.details)},\"duration_ms\":${n(c.durationMs)}," +
                "\"phases\":{\"dns\":${n(c.phases.dns)},\"tcp\":${n(c.phases.tcp)},\"tls\":${n(c.phases.tls)},\"http\":${n(c.phases.http)}}," +
                "\"code\":${c.code?.toString() ?: "null"},\"needs\":${c.needs.joinToString(",", "[", "]") { q(it) }}," +
                "\"evidence\":${c.evidence.joinToString(",", "[", "]") { q(it) }},\"explained_by\":null," +
                "\"confidence\":${q(c.confidence)},\"vantage\":${q(c.vantage)},\"at\":${q(at)}}"
        }
        return "{\"schema\":\"c3-report/v1\",\"engine\":\"health-core\",\"engine_version\":\"0\",\"run\":{\"id\":\"phone\",\"started\":${q(at)},\"finished\":${q(at)},\"runner\":\"phone\",\"vantage\":${q(vantage)}}," +
            "\"checks\":$cs,\"attribution\":{\"roots\":[]},\"missing\":[],\"redactions\":0}"
    }

    /** The checks with the engine's attribution applied (`explained_by`). */
    fun applied(checks: List<Check>, a: Analysis): List<Check> = checks.map { it.copy(explainedBy = a.explained[it.id]) }

    // ── a published report (c3-report/v1), from a fleet engine ──────────────

    data class Row(val key: String, val value: String, val volatile: Boolean)
    data class Section(val id: String, val title: String, val layer: String, val status: String, val rows: List<Row>)
    data class Finding(val id: String, val severity: String, val rank: Int, val layer: String, val title: String, val detail: String, val root: Boolean, val explains: Int)
    data class Report(
        val engine: String, val engineVersion: String, val runId: String, val runner: String, val vantage: String, val started: String, val finished: String,
        val subject: String, val status: String, val summary: Map<String, Long>, val sections: List<Section>, val checks: List<Check>, val findings: List<Finding>,
        val roots: List<String>, val missing: List<Pair<String, String>>, val redactions: Long,
    )

    fun parseCheck(c: JsonObject): Check {
        val ent = c.o("entity")
        return Check(c.s("id"), c.s("layer"), c.s("name").ifBlank { c.s("id") }, St.parse(c.s("status")), c.s("details"), ent?.s("id").orEmpty(), ent?.s("kind").orEmpty(),
            phasesOf(c.o("phases")), c.l("code")?.toInt(), c.l("duration_ms"), c.strs("needs"), c.strs("evidence"), c.s("explained_by").ifBlank { null },
            c.s("confidence").ifBlank { "measured" }, c.s("vantage").ifBlank { "fleet" })
    }

    /** null when [text] is not a c3-report/v1 envelope. */
    fun parseReport(text: String): Report? {
        val o = parseObj(text) ?: return null
        if (o.s("schema") != "c3-report/v1") return null
        val run = o.o("run"); val sm = o.o("summary"); val at = o.o("attribution")
        return Report(
            o.s("engine"), o.s("engine_version"), run?.s("id").orEmpty(), run?.s("runner").orEmpty(), run?.s("vantage").orEmpty(), run?.s("started").orEmpty(), run?.s("finished").orEmpty(),
            run?.o("subject")?.let { "${it.s("kind")}:${it.s("id")}" }.orEmpty(), o.s("status"),
            sm?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty(),
            o.a("sections").mapNotNull { it as? JsonObject }.map { s ->
                Section(s.s("id"), s.s("title"), s.s("layer"), s.s("status"), s.a("rows").mapNotNull { it as? JsonObject }.map { r ->
                    Row(r.s("key"), r.s("value"), (r["volatile"] as? JsonPrimitive)?.contentOrNull == "true")
                })
            },
            o.a("checks").mapNotNull { it as? JsonObject }.map(::parseCheck),
            o.a("findings").mapNotNull { it as? JsonObject }.map { f ->
                Finding(f.s("id"), f.s("severity"), f.l("rank")?.toInt() ?: 0, f.s("layer"), f.s("title"), f.s("detail"), (f["root"] as? JsonPrimitive)?.contentOrNull == "true", f.l("explains")?.toInt() ?: 0)
            },
            at?.strs("roots").orEmpty(),
            o.a("missing").mapNotNull { it as? JsonObject }.map { it.s("id") to it.s("text") },
            o.l("redactions") ?: 0L,
        )
    }

    // ── the app's own readings as checks ────────────────────────────────────

    /** What the phone knows about itself before any probe: a probe of a public host is gated on [internet], a private one on [vpn]. */
    data class Vantage(val token: Boolean?, val vpn: Boolean?, val internet: ProbeOut?, val internetRan: Boolean)

    fun vantageChecks(v: Vantage): List<Check> = listOf(
        when (v.token) {
            true -> Check("vantage.token", "vantage", "C3 token", St.OK, "an Authelia token is on this device", kind = "device")
            false -> Check("vantage.token", "vantage", "C3 token", St.BLIND, NO_TOKEN_NOTE, kind = "device")
            null -> Check("vantage.token", "vantage", "C3 token", St.PENDING, "not read yet", kind = "device")
        },
        when (v.vpn) {
            true -> Check("vantage.vpn", "vantage", "VPN (mesh) network", St.OK, "a VPN network is active on this phone", kind = "device")
            false -> Check("vantage.vpn", "vantage", "VPN (mesh) network", St.WARN, "no VPN network is active: private names and the mesh are not probed", kind = "device")
            null -> Check("vantage.vpn", "vantage", "VPN (mesh) network", St.PENDING, "not read yet", kind = "device")
        },
        if (!v.internetRan) Check("vantage.internet", "vantage", "Internet (tcp 1.1.1.1:443)", St.PENDING, "not probed yet", kind = "device")
        else v.internet.let { p ->
            if (p == null) Check("vantage.internet", "vantage", "Internet (tcp 1.1.1.1:443)", St.NOT_SERVED, "${com.diegonmarcos.cloudlib.healthcore.HealthCore.NOT_IN_BUILD}", kind = "device")
            else Check("vantage.internet", "vantage", "Internet (tcp 1.1.1.1:443)", if (p.status == St.OK) St.OK else St.WARN, p.error.ifBlank { "tcp answered" }, kind = "device", phases = p.phases)
        },
    )

    /** What a probe of one URL is: the URL the engine is given, or null (no probe). */
    fun publicUrl(s: Services.PublicService) = "https://${s.publicUrl}/"
    /** null when the declaration gives no port: the engine would call that a DNS failure, which is not what happened. */
    fun privateTarget(s: Services.PrivateService): String? = s.privateDns.takeIf { Regex(":\\d{1,5}$").containsMatchIn(it) }?.let { "tcp://$it" }

    private fun probed(id: String, layer: String, name: String, p: ProbeOut?, ran: Boolean, needs: List<String>, gate: String?, notServed: String, facts: List<Pair<String, String>>, kind: String, entity: String): Check {
        val base = Check(id, layer, name, St.PENDING, "not probed yet", entity, kind, needs = needs, facts = facts)
        return when {
            gate != null -> base.copy(status = St.BLIND, details = gate)
            p == null && !ran -> base
            p == null -> base.copy(status = St.NOT_SERVED, details = notServed)
            else -> base.copy(status = p.status, details = p.error.ifBlank { "answered" }, phases = p.phases, code = p.code, durationMs = p.phases.total.takeIf { p.status.carriesNumber })
        }
    }

    /**
     * The Lights: every public and private service of the old Health page, each a check of its own. Public rows need the phone's internet and
     * private rows its VPN; a blocked vantage makes them BLIND with that reason (never DOWN: the service was not looked at).
     */
    fun lightsChecks(pub: List<Services.PublicService>, priv: List<Services.PrivateService>, probes: Map<String, ProbeOut>, v: Vantage, engineReason: String?, ran: Boolean): List<Check> {
        val noNet = if (v.internetRan && (v.internet == null || v.internet.status != St.OK)) "not probed: this phone has no internet (tcp 1.1.1.1:443 failed)" else null
        val noVpn = if (v.vpn == false) "not probed: this phone is not on the VPN" else null
        val publicRows = pub.map { s ->
            probed("lights.public:${s.name}", "lights", s.name, probes["lights.public:${s.name}"], ran, listOf("vantage.internet", "edge.caddy"), noNet,
                engineReason ?: "probe produced no answer",
                listOf("scope" to "Public", "auth" to s.auth, "public_url" to "https://${s.publicUrl}", "private_dns" to s.privateDns, "vm" to s.vm, "service" to s.service, "category" to s.category),
                "service", s.name)
        }
        val privateRows = priv.map { s ->
            probed("lights.private:${s.name}", "lights", s.name, probes["lights.private:${s.name}"], ran, listOf("vantage.vpn", "mesh.hub"), noVpn ?: if (privateTarget(s) == null) "not probed: the declaration gives no port for this private name" else null,
                engineReason ?: "probe produced no answer",
                listOf("scope" to "Private", "protocol" to s.protocol, "db_engine" to s.dbEngine, "service" to s.service, "private_dns" to s.privateDns, "vm" to s.vm, "category" to s.category),
                "service", s.name)
        }
        return publicRows + privateRows
    }

    /** A layer read from a fleet engine report: the explicit not-served state when no report arrived. */
    fun fleetNotServed(layer: String, why: String): Check =
        Check("$layer.fleet_report", layer, "${LAYERS.first { it.id == layer }.label}: fleet report", St.NOT_SERVED, why, kind = "report", vantage = "fleet")

    // ── views ───────────────────────────────────────────────────────────────

    fun glyph(s: St) = when (s) { St.OK -> "o"; St.WARN -> "!"; St.DOWN -> "x"; St.BLIND -> "?"; St.GATED -> "-"; St.NOT_SERVED -> "~"; St.PENDING -> "." }
    fun word(s: St) = when (s) { St.OK -> "up"; St.WARN -> "degraded"; St.DOWN -> "down"; St.BLIND -> "blind"; St.GATED -> "not probed"; St.NOT_SERVED -> "not served"; St.PENDING -> "not run" }
    fun lightGlyph(l: String) = when (l) { "red" -> "x"; "red_hollow" -> "(x)"; "amber" -> "!"; "green" -> "o"; else -> "?" }
    fun lightWord(l: String) = when (l) { "red" -> "root cause here"; "red_hollow" -> "consequence of another layer"; "amber" -> "attention"; "green" -> "all ok"; else -> "nothing measured" }

    /** Worst first: down, warn, blind, gated, not served, pending, ok; then by name. */
    fun worstFirst(cs: List<Check>) = cs.sortedWith(compareBy<Check>({ when (it.status) { St.DOWN -> 0; St.WARN -> 1; St.BLIND -> 2; St.GATED -> 3; St.NOT_SERVED -> 4; St.PENDING -> 5; St.OK -> 6 } }, { it.name }))

    /** The Lights rows of a scope ("All" | "Public" | "Private"), the old page's two sections kept as the scope. */
    fun scoped(cs: List<Check>, scope: String) = cs.filter { c -> scope == "All" || c.facts.firstOrNull { it.first == "scope" }?.second == scope }

    /** The checks a root explains (its impact), in graph order. */
    fun impact(cs: List<Check>, root: String) = cs.filter { it.explainedBy == root }

    /** The chain of needs from [id] to its root, for the cause view. */
    fun chain(cs: List<Check>, id: String): List<Check> {
        val by = cs.associateBy { it.id }
        val out = mutableListOf<Check>(); var at = by[id]; val seen = mutableSetOf<String>()
        while (at != null && seen.add(at.id)) { out += at; at = at.explainedBy?.let { by[it] } }
        return out
    }

    /** One phase bar: the phases in order, each with its share of the total, for a check that carries a number. */
    fun bar(c: Check): List<Pair<String, Float>> {
        if (!c.status.carriesNumber || !c.phases.any) return emptyList()
        val t = c.phases.total.coerceAtLeast(1).toFloat()
        return c.phases.list().mapNotNull { (k, v) -> v?.let { k to it / t } }
    }
}

package com.diegonmarcos.cloudc3.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #1955 A The live colour of the Network graphs, read ONLY from sources that already exist: the shared topology live state
 * (`/health/deployed/:vm`), the containers' docker state, the Table probes (RoutesProbeStore), `GET /security/wireguard` and whether
 * the c3 API answered at all. A node with no binding stays grey "declared" and is never green; a failed read is "not read: <why>".
 * [rootCause] walks the declared dependencies to the lowest failing node.
 */
object NetLive {

    const val WIREGUARD_PATH = "/security/wireguard"

    data class WgPeer(val endpoint: String, val allowedIps: List<String>, val latestHandshake: String, val transfer: String)
    data class WgVm(val vm: String, val peers: List<WgPeer>)

    /** One reading of one binding. [unreadable] = the read failed (the node is failing, but only "suspected"); [positive] = a real observation. */
    data class Reading(
        val health: MailStack.Health, val word: String, val source: String, val at: Long = 0L,
        val unreadable: Boolean = false, val why: String = "",
    ) {
        val positive get() = !unreadable && health != MailStack.Health.UNKNOWN
    }

    /** Everything the bindings read, as a snapshot (the fragment fills it; the JVM suite builds it directly). */
    data class Snapshot(
        val deployed: Map<String, TopologyModel.Deployed> = emptyMap(),
        val containers: Map<String, Pair<TopologyModel.Health, String>> = emptyMap(),
        val hosts: Map<String, Pair<MailStack.Health, String>> = emptyMap(),
        val tunnels: List<WgVm>? = null,
        val tunnelsWhy: String = "",
        val api: Boolean? = null,
        val apiWhy: String = "",
        /** alias to WG IP (fleet slice and mesh.json), alias to WG role, VM id to alias */
        val wgIp: Map<String, String> = emptyMap(),
        val role: Map<String, String> = emptyMap(),
        val vmAlias: Map<String, String> = emptyMap(),
        val at: Long = 0L,
    ) {
        /** True when at least one source answered: without it there is no root cause, only the "not read" banner. */
        val anyRead get() = api == true || deployed.values.any { it is TopologyModel.Deployed.Seen } || tunnels != null || hosts.values.any { it.first != MailStack.Health.UNKNOWN }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** `[{vm, peers:[{endpoint, allowedIps, latestHandshake, transfer}]}]`; null when it is not that shape. */
    fun parseWireguard(body: String): List<WgVm>? {
        val a = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        return a.mapNotNull { it as? JsonObject }.map { o ->
            WgVm(o.s("vm"), (o["peers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { p ->
                val ips = when (val v = p["allowedIps"]) {
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> v.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
                    else -> emptyList()
                }
                WgPeer(p.s("endpoint"), ips, p.s("latestHandshake"), p.s("transfer"))
            })
        }
    }

    /** `wg show` handshake text to seconds: "1 minute, 12 seconds ago" = 72; "never", "(none)" or blank = null (no handshake). */
    fun handshakeAgeS(text: String): Long? {
        val t = text.trim().lowercase()
        if (t.isBlank() || t == "never" || t == "(none)" || t == "0") return null
        t.toLongOrNull()?.let { return it }
        val units = mapOf("second" to 1L, "minute" to 60L, "hour" to 3600L, "day" to 86400L, "week" to 604800L)
        var total = 0L; var any = false
        Regex("(\\d+)\\s+(second|minute|hour|day|week)s?").findAll(t).forEach { m -> total += m.groupValues[1].toLong() * units.getValue(m.groupValues[2]); any = true }
        return if (any) total else null
    }

    private fun of(h: TopologyModel.Health) = when (h) {
        TopologyModel.Health.OK -> MailStack.Health.OK
        TopologyModel.Health.WARN -> MailStack.Health.WARN
        TopologyModel.Health.BAD -> MailStack.Health.BAD
        else -> MailStack.Health.UNKNOWN
    }

    val DECLARED = Reading(MailStack.Health.UNKNOWN, "declared", "declaration")

    /** One binding's reading; null when the binding kind is unknown (it then counts as unbound). */
    fun read(bind: String, s: Snapshot, v: NetGraph.Viewers): Reading? {
        val kind = bind.substringBefore(':'); val key = bind.substringAfter(':')
        return when (kind) {
            "vm" -> when (val d = s.deployed[key] ?: s.deployed[s.vmAlias[key] ?: key]) {
                is TopologyModel.Deployed.Seen -> Reading(MailStack.Health.OK, "docker ps read (${d.boxes.size})", "/health/deployed", s.at)
                is TopologyModel.Deployed.Blind -> Reading(MailStack.Health.UNKNOWN, "not read: ${d.why}", "/health/deployed", s.at, unreadable = true, why = d.why)
                null -> if (s.deployed.isEmpty()) Reading(MailStack.Health.UNKNOWN, "not read yet", "/health/deployed")
                    else Reading(MailStack.Health.UNKNOWN, "no live read for this VM (no WG IP declared)", "/health/deployed")
            }
            "container" -> s.containers[key]?.let { (h, t) -> Reading(of(h), t.ifBlank { h.name.lowercase() }, "docker ps", s.at) }
                ?: Reading(MailStack.Health.UNKNOWN, "not read yet", "docker ps")
            "host" -> s.hosts[key]?.let { (h, t) -> Reading(h, t, "device probe", s.at) } ?: Reading(MailStack.Health.UNKNOWN, "not probed", "device probe")
            "api" -> when (s.api) {
                true -> Reading(MailStack.Health.OK, "answered", "c3 API", s.at)
                false -> Reading(MailStack.Health.BAD, "unreachable: ${s.apiWhy}", "c3 API", s.at, why = s.apiWhy)
                null -> Reading(MailStack.Health.UNKNOWN, "not asked yet", "c3 API")
            }
            "tunnel" -> tunnel(key.substringBefore("->"), key.substringAfter("->"), s, v)
            else -> null
        }
    }

    private fun tunnel(a: String, b: String, s: Snapshot, v: NetGraph.Viewers): Reading {
        val src = WIREGUARD_PATH
        val tun = s.tunnels ?: return if (s.tunnelsWhy.isNotBlank()) Reading(MailStack.Health.UNKNOWN, "not read: ${s.tunnelsWhy}", src, s.at, unreadable = true, why = s.tunnelsWhy)
            else Reading(MailStack.Health.UNKNOWN, "reading tunnels...", src)
        fun side(from: String, to: String): Reading? {
            val vm = tun.firstOrNull { it.vm == from || s.vmAlias[it.vm] == from } ?: return null
            if (vm.peers.isEmpty()) return Reading(MailStack.Health.UNKNOWN, "no peers read on $from (no WireGuard, or SSH failed)", src, s.at)
            val ip = s.wgIp[to] ?: return Reading(MailStack.Health.UNKNOWN, "no WG IP declared for $to", src, s.at)
            val peer = vm.peers.firstOrNull { p -> p.allowedIps.any { it.substringBefore('/') == ip } }
                ?: return Reading(MailStack.Health.BAD, "$from has no peer for $to ($ip)", src, s.at)
            val age = handshakeAgeS(peer.latestHandshake)
            return when {
                age != null && age <= v.handshakeOkS -> Reading(MailStack.Health.OK, "handshake ${age}s ago", src, s.at)
                age != null && age <= v.handshakeWarnS -> Reading(MailStack.Health.WARN, "handshake ${age}s ago", src, s.at)
                else -> when ((s.role[to] ?: s.role[from])?.let { v.staleIs[it] } ?: "down") {
                    "unknown" -> Reading(MailStack.Health.UNKNOWN, "idle: " + (age?.let { "handshake ${it}s ago" } ?: "no handshake"), src, s.at)
                    else -> Reading(MailStack.Health.BAD, "stale: " + (age?.let { "handshake ${it}s ago" } ?: "no handshake"), src, s.at)
                }
            }
        }
        return side(a, b) ?: side(b, a) ?: Reading(MailStack.Health.UNKNOWN, "neither end is a VM the API reads", src, s.at)
    }

    /** The readings of every node and edge of [g]: the worst of its bound readings; an unbound one is [DECLARED] (never OK). */
    fun applyLive(g: NetGraph.Graph, s: Snapshot, v: NetGraph.Viewers): Map<String, Reading> {
        fun fold(binds: List<String>): Reading {
            val rs = binds.mapNotNull { read(it, s, v) }
            if (rs.isEmpty()) return DECLARED
            val bad = rs.firstOrNull { it.health == MailStack.Health.BAD }
            return bad ?: rs.firstOrNull { it.unreadable } ?: rs.firstOrNull { it.health == MailStack.Health.WARN }
                ?: rs.firstOrNull { it.health == MailStack.Health.UNKNOWN } ?: rs.first()
        }
        return g.nodes.associate { it.id to fold(it.bind) } + g.edges.associate { it.id to fold(it.bind) }
    }

    /** One root cause: the failing node with no failing dependency, everything that depends on it, and whether a positive reading confirms it. */
    data class Root(val id: String, val impacted: Set<String>, val confirmed: Boolean, val why: String)

    /** What each node depends on: the declared `dep` of every edge plus the page's declared `depends` (a cluster stands for all its members). */
    fun dependsOn(g: NetGraph.Graph): Map<String, Set<String>> {
        val m = mutableMapOf<String, MutableSet<String>>()
        fun add(a: String, b: String) { m.getOrPut(a) { mutableSetOf() } += b }
        val members = g.clusters.associate { it.id to it.members }
        g.edges.forEach { e -> when (e.dep) { NetGraph.Dep.FROM_NEEDS_TO -> add(e.from, e.to); NetGraph.Dep.TO_NEEDS_FROM -> add(e.to, e.from); else -> {} } }
        g.depends.forEach { d -> (members[d.from] ?: listOf(d.from)).forEach { add(it, d.to) } }
        return m
    }

    fun rootCause(g: NetGraph.Graph, readings: Map<String, Reading>, s: Snapshot): List<Root> {
        if (!s.anyRead) return emptyList()
        val deps = dependsOn(g)
        fun failing(id: String) = readings[id]?.let { it.health == MailStack.Health.BAD || it.unreadable } == true
        fun closure(id: String): Set<String> {
            val seen = mutableSetOf<String>(); val todo = ArrayDeque(deps[id].orEmpty())
            while (todo.isNotEmpty()) { val x = todo.removeFirst(); if (seen.add(x)) todo.addAll(deps[x].orEmpty()) }
            return seen - id
        }
        val dependents = mutableMapOf<String, MutableSet<String>>()
        g.nodes.forEach { n -> closure(n.id).forEach { d -> dependents.getOrPut(d) { mutableSetOf() } += n.id } }
        return g.nodes.filter { failing(it.id) && closure(it.id).none(::failing) }.map { n ->
            val r = readings.getValue(n.id)
            val seenStale = g.edges.any { e -> (e.to == n.id || e.from == n.id) && readings[e.id]?.health == MailStack.Health.BAD && readings[e.id]?.unreadable == false }
            Root(n.id, dependents[n.id].orEmpty(), r.health == MailStack.Health.BAD || seenStale, r.why.ifBlank { r.word })
        }.sortedWith(compareByDescending<Root> { it.impacted.size }.thenBy { it.id })
    }

    /** The node label of [Root.confirmed]. */
    fun verdict(r: Root) = if (r.confirmed) "confirmed" else "suspected"
}

/** The process-wide live state of the Network graphs (60 s TTL, as the declaration says): the tunnel read is the only source of its own. */
class NetLiveState {
    var tunnels by mutableStateOf<List<NetLive.WgVm>?>(null)
    var tunnelsWhy by mutableStateOf("")
    var api by mutableStateOf<Boolean?>(null)
    var apiWhy by mutableStateOf("")
    var at by mutableStateOf(0L)

    suspend fun refresh(src: FleetLive, ttlS: Int, force: Boolean = false, now: () -> Long = System::currentTimeMillis) {
        if (!force && at > 0 && now() - at < ttlS * 1000L) return
        when (val r = src.get(NetLive.WIREGUARD_PATH)) {
            is FleetLive.Result.Body -> {
                api = true; apiWhy = ""
                val p = NetLive.parseWireguard(r.text)
                tunnels = p; tunnelsWhy = if (p == null) "the answer is not a tunnel list" else ""
            }
            is FleetLive.Result.Failed -> { api = false; apiWhy = r.why; tunnels = null; tunnelsWhy = r.why }
        }
        at = now()
    }

    companion object { val shared = NetLiveState() }
}

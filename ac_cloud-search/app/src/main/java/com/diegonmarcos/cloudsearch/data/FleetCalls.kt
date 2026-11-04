package com.diegonmarcos.cloudsearch.data

import com.diegonmarcos.cloudsearch.core.Cgc
import com.diegonmarcos.cloudsearch.core.CgcConfig
import com.diegonmarcos.cloudsearch.core.CgcEdge
import com.diegonmarcos.cloudsearch.core.CgcHit
import com.diegonmarcos.cloudsearch.core.CgcNode
import com.diegonmarcos.cloudsearch.core.FleetEndpoint
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.McpClient
import com.diegonmarcos.cloudsearch.core.Outcome
import org.json.JSONObject

/**
 * #1184 The calls to the fleet services Cloud-CGC and Me-CGC use. Every address comes from the fleet declaration
 * ([endpoints], baked from ac_cloud-account/build.json::fleet_endpoints); a route is tried in the declared policy order, and the
 * fleet bearer ([bearer], Cloud Account's, read over its IPC on use and never kept) goes to a public origin only.
 */
class FleetCalls(private val http: Http, val endpoints: Map<String, FleetEndpoint>, private val bearer: () -> String?) {
    /** [kind] says what the page shows; [detail] is the service's or the transport's own words. */
    enum class Kind { OK, EMPTY, UNAVAILABLE, REFUSED, NOTE }
    data class Result<T>(val items: List<T> = emptyList(), val kind: Kind = Kind.OK, val detail: String = "", val via: String = "")

    fun semantic(cfg: CgcConfig, ix: CgcConfig.Index, repo: String, q: String): Result<CgcHit> =
        ask(cfg, ix, Cgc.SEARCH_TOOL, Cgc.semanticArgs(repo, q, cfg.threshold)) { Cgc.hits(repo, it, cfg.limit) }

    fun graph(cfg: CgcConfig, ix: CgcConfig.Index, repo: String, q: String): Result<CgcNode> =
        ask(cfg, ix, Cgc.GRAPH_TOOL, Cgc.graphSearchArgs(repo, q)) { Cgc.nodes(repo, it, cfg.limit) }

    fun related(cfg: CgcConfig, ix: CgcConfig.Index, repo: String, node: String): Result<CgcEdge> =
        ask(cfg, ix, Cgc.GRAPH_TOOL, Cgc.relationshipsArgs(repo, node)) { Cgc.edges(it) }

    private fun <T> ask(cfg: CgcConfig, ix: CgcConfig.Index, tool: String, args: JSONObject, read: (String) -> com.diegonmarcos.cloudsearch.core.CgcAnswer<T>): Result<T> {
        val ep = endpoints[ix.service] ?: return Result(kind = Kind.UNAVAILABLE, detail = "${ix.service} is not in the fleet endpoints")
        val token = bearer()
        if (ix.needsAccount && token == null) return Result(kind = Kind.UNAVAILABLE, detail = "Cloud Account has no fleet sign-in")
        val o = ep.attempt(token) { r -> McpClient(http, r.base + ix.mcpPath, r.headers, cfg.timeoutMs).call(tool, args) to r }
        val ok = when (o) {
            is Outcome.Down -> return Result(kind = Kind.UNAVAILABLE, detail = o.reasons.joinToString("; "))
            is Outcome.Ok -> o
        }
        val (reply, route) = ok.value
        val via = ok.via
        if (reply.isError) return Result(kind = Kind.REFUSED, detail = reply.text.trim().take(300), via = via)
        val a = runCatching { read(reply.text) }.getOrElse { return Result(kind = Kind.NOTE, detail = "unreadable answer: " + it.message, via = via) }
        if (a.items.isNotEmpty()) return Result(a.items, Kind.OK, via = via)
        if (a.note.isNotBlank()) return Result(kind = Kind.NOTE, detail = a.note, via = via)
        // An empty answer from the private index may mean it was never built: say what the service itself reports.
        val health = if (ix.needsAccount) runCatching { Cgc.health(get(route.base + ix.healthPath, route.headers, cfg.timeoutMs)) }.getOrElse { "health: " + it.message } else ""
        return Result(kind = Kind.EMPTY, detail = health, via = via)
    }

    private fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String {
        val r = http.get(url, headers, timeoutMs)
        if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code}")
        return r.body
    }
}

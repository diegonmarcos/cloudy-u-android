package com.diegonmarcos.superapp.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Cloud Mesh's TCP/443 fallback as it crosses [INetBackend]: WireGuard datagrams the engine
 * takes on 127.0.0.1:[Route.listen] and carries as WebSocket frames over TLS to a wstunnel server
 * at [host]:[port], which hands them to [Route.remote] (a hub's WireGuard listener). The app decides
 * (pinned map, DNS, DoH); the engine only dials what it is given: [addrs] are IP literals, so the
 * engine never resolves a name. [prefix] is the server's upgrade-path secret; it lives in the
 * client's prefs and in this one call, never in a log, a status or an export.
 */
data class RelaySpec(
    val host: String,
    val port: Int,
    val addrs: List<String>,
    val prefix: String,
    val routes: List<Route>,
    /** False only in tests: plain ws:// to a local server. */
    val tls: Boolean = true,
) {
    data class Route(val listen: Int, val remote: String)

    fun toJson(): String = JSONObject()
        .put("host", host).put("port", port).put("prefix", prefix).put("tls", tls)
        .put("addrs", JSONArray(addrs))
        .put("routes", JSONArray(routes.map { JSONObject().put("listen", it.listen).put("remote", it.remote) }))
        .toString()

    companion object {
        /** Null for a blank text (= stop the relay); throws on a malformed one. */
        fun parse(json: String?): RelaySpec? {
            if (json.isNullOrBlank()) return null
            val o = JSONObject(json)
            val a = o.getJSONArray("addrs")
            val r = o.getJSONArray("routes")
            return RelaySpec(
                o.getString("host"), o.optInt("port", 443),
                (0 until a.length()).map { a.getString(it) }, o.optString("prefix"),
                (0 until r.length()).map { r.getJSONObject(it).let { x -> Route(x.getInt("listen"), x.getString("remote")) } },
                o.optBoolean("tls", true),
            )
        }
    }
}

/** What the engine's relay is doing: one [Leg] per route. [state] is off | idle | up | error. */
data class RelayStatus(val state: String, val host: String, val legs: List<Leg>) {
    data class Leg(val listen: Int, val remote: String, val up: Boolean, val via: String, val rx: Long, val tx: Long, val error: String)

    fun toJson(): String = JSONObject().put("state", state).put("host", host).put("legs", JSONArray(legs.map {
        JSONObject().put("listen", it.listen).put("remote", it.remote).put("up", it.up).put("via", it.via)
            .put("rx", it.rx).put("tx", it.tx).put("error", it.error)
    })).toString()

    companion object {
        fun parse(json: String?): RelayStatus {
            val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return RelayStatus("off", "", emptyList())
            val l = o.optJSONArray("legs") ?: JSONArray()
            return RelayStatus(o.optString("state", "off"), o.optString("host"), (0 until l.length()).map { i ->
                l.getJSONObject(i).let { Leg(it.optInt("listen"), it.optString("remote"), it.optBoolean("up"), it.optString("via"), it.optLong("rx"), it.optLong("tx"), it.optString("error")) }
            })
        }
    }
}

/** One handshake against a relay: TCP to [addr], TLS ([tls] = negotiated protocol, "" = none), then the upgrade's HTTP [code]. */
/**
 * [attempts] (#1798): every address the dialer tried or skipped, in order, one per line ("v4 129.151.228.66 ok 41 ms",
 * "v6 2603:... skipped: no route"). Optional on the wire: an older engine sends none and an older app ignores it.
 */
data class RelayProbe(val addr: String, val tls: String, val code: Int, val ms: Long, val error: String, val attempts: String = "") {
    /** 101: the relay took the tunnel. */
    val ok: Boolean get() = code == 101

    fun toJson(): String = JSONObject().put("addr", addr).put("tls", tls).put("code", code).put("ms", ms).put("error", error).put("attempts", attempts).toString()

    companion object {
        fun parse(json: String?): RelayProbe = runCatching {
            JSONObject(json.orEmpty()).let { RelayProbe(it.optString("addr"), it.optString("tls"), it.optInt("code"), it.optLong("ms"), it.optString("error"), it.optString("attempts")) }
        }.getOrElse { RelayProbe("", "", 0, 0, json.orEmpty().ifBlank { "no answer from the engine" }) }
    }
}

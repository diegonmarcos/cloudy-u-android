package com.diegonmarcos.superapp.network.mesh

import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #1900 THE pipeline of the VPN page: status, profiling and probing are one thing. A row cannot report a status without
 * a probe, and a probe does not start before the whole route is profiled (interface, DNS, route, mesh peer, fallback):
 * every [Row] carries its [Route] (the profile), the probe's measurement (ok, ms, path, detail) and the status drawn from it.
 *
 * Six groups, each a block of the Prober section: A private names (the mesh namespace), B public, C each WireGuard peer,
 * D every fallback path (Direct UDP, Pinned IP, DoH, TLS-443 relay), E IPv4 literals, F IPv6 literals. What is probed is
 * declared (`build.json::ui.mesh_page.prober`, [Decl]); nothing here names a host or an address.
 *
 * It never blocks a caller: [run] is handed a worker thread by the store, every probe runs in its own pool thread under its
 * own timeout, rows stream out through `onRow` as they finish, and `cancelled` stops it between probes. Pure but for the
 * [C3MeshProbe.Io] it is given, so a scripted network asserts every verdict on the JVM.
 */
object MeshProber {
    val GROUPS = listOf("private", "public", "peers", "fallbacks", "ipv4", "ipv6")
    val TITLES = mapOf("private" to "Private names (mesh namespace)", "public" to "Public", "peers" to "WireGuard peers",
        "fallbacks" to "Fallbacks", "ipv4" to "IPv4", "ipv6" to "IPv6")
    val LETTERS = mapOf("private" to "A", "public" to "B", "peers" to "C", "fallbacks" to "D", "ipv4" to "E", "ipv6" to "F")

    data class Named(val id: String, val label: String, val host: String, val url: String)
    data class IpTarget(val id: String, val label: String, val ip: String, val port: Int) {
        val family: Int get() = if (':' in ip) 6 else 4
    }
    data class Decl(
        val timeoutMs: Int = 4000,
        val parallel: Int = 6,
        val privateNames: List<Named> = emptyList(),
        val publicUrls: List<Named> = emptyList(),
        val ips: List<IpTarget> = emptyList(),
    )

    fun parse(o: JSONObject?): Decl {
        if (o == null) return Decl()
        fun named(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map {
            val x = a.getJSONObject(it)
            Named(x.getString("id"), x.optString("label", x.getString("id")), x.optString("host"), x.optString("url"))
        }
        val ia = o.optJSONArray("ip_targets")
        return Decl(
            timeoutMs = o.optInt("timeout_ms", 4000).coerceAtLeast(200),
            parallel = o.optInt("parallel", 6).coerceIn(1, 16),
            privateNames = named(o.optJSONArray("private_names")),
            publicUrls = named(o.optJSONArray("public_urls")),
            ips = if (ia == null) emptyList() else (0 until ia.length()).map {
                val x = ia.getJSONObject(it)
                IpTarget(x.getString("id"), x.optString("label", x.getString("id")), x.getString("ip"), x.optInt("port", 443))
            },
        )
    }

    /** One thing to probe. [kind]: name (resolve, connect, HTTPS) | tcp | peer | fallbacks. [via]: the path ([C3MeshProbe.VIA_TUNNEL] ...). */
    data class Spec(
        val group: String, val id: String, val label: String, val kind: String,
        val host: String = "", val port: Int = 0, val url: String = "", val via: String = C3MeshProbe.VIA_DEFAULT,
        val family: Int = 0, val peerKey: String = "",
        /** A row expanded from a placeholder (the fallback ladder) names it here. */
        val parent: String = "",
    )

    /** The route profile of one probe: how this probe is expected to travel, read BEFORE it is sent. */
    data class Route(val iface: String, val dns: String, val route: String, val peer: String, val fallback: String) {
        fun line() = "iface $iface · dns $dns · route $route · peer $peer · fallback $fallback"
    }

    /** The profile row: route (the profile), the measurement and the status drawn from it. [done] false = queued or running. */
    data class Row(
        val spec: Spec, val route: Route, val ok: Boolean?, val ms: Long, val path: String, val detail: String, val atMs: Long, val done: Boolean,
    ) {
        val status: String get() = if (!done) "queued" else when (ok) { true -> "PASS"; false -> "FAIL"; null -> "n/a" }
    }

    /** What the profile reads: the tunnel, Android's DNS, the peers (live), the fallback path. */
    data class Ctx(
        val engine: Boolean = true,
        val up: Boolean = false,
        val tunnelName: String = "",
        val addresses: List<String> = emptyList(),
        val dns: List<String> = emptyList(),
        val peers: List<PeerLive> = emptyList(),
        val path: String = "",
        val pathDetail: String = "",
        val mode: String = "",
        val meshes: List<C3MeshProbe.Target> = emptyList(),
        val reachHosts: List<String> = emptyList(),
        val reachLabels: Map<String, String> = emptyMap(),
    )

    fun ctxOf(s: MeshSnapshot?, cfg: ConfigView?, t: TransportView?, d: MeshDecl.Decl): Ctx {
        val c = s?.cfg ?: cfg
        return Ctx(
            engine = s?.engineInstalled ?: true, up = s?.up == true, tunnelName = c?.tunnelName.orEmpty(),
            addresses = C3MeshProbe.cidrs(c?.address.orEmpty()), dns = MeshStore.ipLiterals(c?.dnsInEffect.orEmpty()),
            peers = s?.peers.orEmpty(), path = t?.path.orEmpty(), pathDetail = t?.detail.orEmpty(), mode = t?.mode.orEmpty(),
            meshes = d.meshes, reachHosts = d.reachHosts, reachLabels = d.reachLabels,
        )
    }

    /**
     * The ONE verdict on a peer's handshake, for every place that shows it (the lights, the profiler, the Prober's peer rows):
     * true = fresh, false = aging, stale, none or the tunnel down, null = the engine cannot say. Two sources of truth showed
     * "2 of 2 FRESH" beside "tunnel down" for the same peers; there is one now.
     */
    fun handshakeOk(h: Health): Boolean? = when (h) { Health.FRESH -> true; Health.UNKNOWN -> null; else -> false }

    // ── plan ──

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
    private fun portOf(url: String): Int =
        url.substringAfter("://").substringBefore('/').substringAfter(':', "").toIntOrNull() ?: if (url.startsWith("http://")) 80 else 443

    /** Every probe of the matrix, in group order A..F. */
    fun plan(d: Decl, c: Ctx): List<Spec> {
        val out = mutableListOf<Spec>()
        val seen = mutableSetOf<String>()
        fun priv(id: String, label: String, host: String, url: String) {
            if (!seen.add(host + "|" + url)) return
            var uid = id; var n = 2
            while (out.any { it.group == "private" && it.id == uid }) uid = "$id-${n++}"
            out += Spec("private", uid, label, "name", host, portOf(url), url, C3MeshProbe.VIA_TUNNEL)
        }
        for (n in d.privateNames) priv(n.id, n.label, n.host, n.url.ifBlank { "https://${n.host}/" })
        for (h in c.reachHosts) priv(h.substringBefore('.'), c.reachLabels[h] ?: h, h, "https://$h/")
        for (m in c.meshes) if (m.serviceUrl.isNotBlank()) priv("svc-${m.id}", "${m.label} service", hostOf(m.serviceUrl), m.serviceUrl)
        for (n in d.publicUrls) out += Spec("public", n.id, n.label, "name", hostOf(n.url), portOf(n.url), n.url, C3MeshProbe.VIA_DEFAULT)
        for (p in c.peers) out += Spec("peers", p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }, p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) },
            "peer", host = p.cfg.endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]"), port = p.cfg.endpoint.substringAfterLast(':', "").toIntOrNull() ?: 0,
            via = C3MeshProbe.VIA_DIRECT, peerKey = p.cfg.publicKey)
        out += Spec("fallbacks", "ladder", "Fallback ladder", "fallbacks")
        for (m in c.meshes) {
            out += Spec("ipv4", "${m.id}-hub", "${m.label} hub", "tcp", m.hub, 53, via = C3MeshProbe.VIA_TUNNEL, family = 4)
        }
        for (t in d.ips.filter { it.family == 4 }) out += Spec("ipv4", t.id, t.label, "tcp", t.ip, t.port, via = C3MeshProbe.VIA_DEFAULT, family = 4)
        for (m in c.meshes) if (m.hub6.isNotBlank()) out += Spec("ipv6", "${m.id}-hub6", "${m.label} hub", "tcp", m.hub6, 53, via = C3MeshProbe.VIA_TUNNEL, family = 6)
        for (t in d.ips.filter { it.family == 6 }) out += Spec("ipv6", t.id, t.label, "tcp", t.ip, t.port, via = C3MeshProbe.VIA_DEFAULT, family = 6)
        return out
    }

    // ── profile ──

    private fun peerCarrying(ip: String, c: Ctx): PeerLive? =
        c.peers.firstOrNull { p -> C3MeshProbe.cidrs(p.cfg.allowedIps).any { C3MeshProbe.contains(it, ip) } }

    private fun peerWord(p: PeerLive) = "${p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }} ${p.health.name.lowercase()}" +
        (p.handshakeAgeS?.let { " (handshake ${MeshFormat.age(it)} ago)" } ?: "")

    /** The whole route of [s] before it is probed. */
    fun profile(s: Spec, c: Ctx): Route {
        val fam = if (s.family == 6) 6 else 4
        val addr = c.addresses.map { it.substringBefore('/') }.filter { (':' in it) == (fam == 6) }
        val iface = when (s.via) {
            C3MeshProbe.VIA_TUNNEL -> when {
                !c.engine -> "tunnel (engine not installed)"
                !c.up -> "tunnel ${c.tunnelName} DOWN"
                s.family == 6 && addr.isEmpty() -> "tunnel ${c.tunnelName}, no IPv6 address"
                else -> "tunnel ${c.tunnelName} ${addr.joinToString(", ").ifBlank { "" }}".trim()
            }
            C3MeshProbe.VIA_DIRECT -> "non-VPN network (bound outside the tunnel)"
            else -> if (c.up) "the OS route (inside the VPN while it is up)" else "the OS route (no VPN up)"
        }
        val dns = c.dns.joinToString(", ").ifBlank { "none held" }
        val literal = s.host.isNotBlank() && (C3MeshProbe.isV4(s.host) || ':' in s.host)
        val carrier = if (literal) peerCarrying(s.host, c) else null
        val route = when {
            s.kind == "fallbacks" -> "the rungs of the Auto ladder"
            s.kind == "peer" -> "to the peer's endpoint ${s.host}:${s.port}, outside the tunnel"
            literal && carrier != null -> "${s.host} ⊂ ${carrier.cfg.name.ifBlank { "peer" }} allowed ${carrier.cfg.allowedIps}"
            literal && s.via == C3MeshProbe.VIA_TUNNEL -> "${s.host}: no peer's allowed IPs cover it"
            literal -> "${s.host}: literal address, no lookup, the OS route"
            s.group == "private" -> "mesh namespace name, answered by the fleet resolver"
            else -> "public name, answered by Android's resolver"
        }
        val peer = when {
            s.kind == "peer" -> c.peers.firstOrNull { it.cfg.publicKey == s.peerKey }?.let(::peerWord) ?: "not configured"
            carrier != null -> peerWord(carrier)
            s.via == C3MeshProbe.VIA_TUNNEL -> if (c.peers.isEmpty()) "no peers" else c.peers.joinToString(" · ") { peerWord(it) }
            else -> "-"
        }
        val fallback = "path ${c.path.ifBlank { "unknown" }}" + (if (c.mode.isNotBlank()) " · mode ${c.mode}" else "") + (if (c.pathDetail.isNotBlank()) " · ${c.pathDetail}" else "")
        return Route(iface, dns, route, peer, fallback)
    }

    fun queued(s: Spec, c: Ctx): Row = Row(s, profile(s, c), null, 0, "", "queued: route profiled, probe not sent yet", 0, false)

    // ── probe ──

    private fun carried(local: String, c: Ctx, via: String): String = when {
        local.isBlank() -> via
        c.addresses.any { it.substringBefore('/') == local } -> "tunnel (from $local)"
        via == C3MeshProbe.VIA_TUNNEL -> "NOT the tunnel (from $local)"
        else -> "from $local"
    }

    private inline fun <T> step(nowMs: () -> Long, body: () -> T): Triple<T?, Long, String?> {
        val t0 = nowMs()
        return try { val v = body(); Triple(v, nowMs() - t0, null) } catch (t: Throwable) {
            if (t is InterruptedException) throw t
            Triple(null, nowMs() - t0, t.message?.takeIf { it.isNotBlank() }?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.simpleName)
        }
    }

    /** One probe, to its row (a placeholder spec may give several: [fallbacks]). Blocking: runs on a pool thread under a timeout. */
    fun probeOne(s: Spec, route: Route, c: Ctx, io: C3MeshProbe.Io, timeoutMs: Int, nowMs: () -> Long): Row {
        val t0 = nowMs()
        fun row(ok: Boolean?, path: String, detail: String) = Row(s, route, ok, nowMs() - t0, path, detail, nowMs(), true)
        return when (s.kind) {
            "tcp" -> {
                val (local, ms, err) = step(nowMs) { io.tcpFrom(s.host, s.port, timeoutMs, s.via) }
                if (err != null) row(false, s.via, "tcp ${hostPort(s)}: $err")
                else row(true, carried(local.orEmpty(), c, s.via), "tcp ${hostPort(s)} open in $ms ms")
            }
            "name" -> {
                val parts = mutableListOf<String>()
                val (addrs, dnsMs, dnsErr) = step(nowMs) { io.systemResolve(s.host).also { check(it.isNotEmpty()) { "no address" } } }
                if (dnsErr != null) return row(false, s.via, "dns ${s.host}: $dnsErr")
                parts += "dns ${addrs!!.take(2).joinToString(", ")} ($dnsMs ms)"
                val first = addrs.first()
                val (local, tcpMs, tcpErr) = step(nowMs) { io.tcpFrom(first, s.port, timeoutMs, s.via) }
                if (tcpErr != null) return row(false, s.via, (parts + "tcp $first:${s.port}: $tcpErr").joinToString(" · "))
                parts += "tcp :${s.port} ($tcpMs ms)"
                val path = carried(local.orEmpty(), c, s.via)
                val (code, httpMs, httpErr) = step(nowMs) { io.httpsVia(s.url, timeoutMs, s.via).also { check(it in 100..499) { "HTTP $it" } } }
                if (httpErr != null) return row(false, path, (parts + "${s.url.substringBefore(':')} ${s.url}: $httpErr").joinToString(" · "))
                parts += "${s.url.substringBefore(':')} HTTP $code ($httpMs ms)"
                row(true, path, parts.joinToString(" · "))
            }
            "peer" -> {
                val p = c.peers.firstOrNull { it.cfg.publicKey == s.peerKey }
                if (p == null) return row(null, "-", "the peer is not in the tunnel configuration")
                val hs = handshakeOk(p.health)
                val info = "handshake ${if (p.handshakeAgeS == null) "none" else MeshFormat.age(p.handshakeAgeS) + " ago"} (${p.health.name}) · endpoint ${p.cfg.endpoint.ifBlank { "-" }} · allowed ${p.cfg.allowedIps.ifBlank { "-" }} · keepalive ${p.cfg.keepalive.ifBlank { "-" }}"
                if (s.port == 443 && s.host.isNotBlank()) {
                    val (_, ms, err) = step(nowMs) { io.tcpFrom(s.host, 443, timeoutMs, C3MeshProbe.VIA_DIRECT) }
                    val ep = if (err == null) "endpoint tcp :443 open ($ms ms)" else "endpoint tcp :443: $err"
                    row(if (hs == null) null else hs && err == null, "direct", "$info · $ep")
                } else row(hs, "udp", "$info · UDP endpoint: no TCP probe, the handshake is the proof")
            }
            else -> row(null, "-", "nothing to probe for ${s.kind}")
        }
    }

    private fun hostPort(s: Spec) = if (':' in s.host) "[${s.host}]:${s.port}" else "${s.host}:${s.port}"

    private fun limitOf(s: Spec, timeoutMs: Int): Long = when (s.kind) { "name" -> 3L; "fallbacks" -> 8L; else -> 2L } * timeoutMs + 500

    private fun daemons(prefix: String) = java.util.concurrent.ThreadFactory { r -> Thread(r, prefix).apply { isDaemon = true } }

    /** Wait for [f] up to [limitMs], polling [cancelled] so a cancel does not wait out a slow probe. */
    private fun <T> await(f: java.util.concurrent.Future<T>, limitMs: Long, cancelled: () -> Boolean): T? {
        val end = System.nanoTime() + limitMs * 1_000_000
        while (System.nanoTime() < end) {
            if (cancelled()) { f.cancel(true); return null }
            val left = (end - System.nanoTime()) / 1_000_000
            try { return f.get(minOf(maxOf(left, 1L), 100L), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* poll again */ }
        }
        f.cancel(true)
        throw TimeoutException("no answer within $limitMs ms")
    }

    /**
     * Probe every [specs]: [parallel] at a time, each under its own timeout, rows streamed to [onRow] as they finish. A probe
     * that hangs becomes a FAIL row ("no answer within N ms") instead of holding the page. [fallbacks] runs the engine's
     * Test fallbacks once (the D group) and its lines become rows. Blocking until done or [cancelled]: call it from a worker.
     */
    fun run(
        specs: List<Spec>, c: Ctx, io: C3MeshProbe.Io, fallbacks: () -> List<ProbeLine>, timeoutMs: Int, parallel: Int,
        nowMs: () -> Long = System::currentTimeMillis, cancelled: () -> Boolean = { false },
        slots: ExecutorService = Executors.newFixedThreadPool(parallel.coerceIn(1, 16), daemons("mesh-prober-slot")),
        work: ExecutorService = Executors.newCachedThreadPool(daemons("mesh-prober")),
        onRow: (Row) -> Unit,
    ) {
        try {
            val jobs = specs.map { s ->
                slots.submit(Runnable {
                    if (cancelled()) return@Runnable
                    val route = profile(s, c)                       // profile first: the whole route, then the probe
                    val limit = limitOf(s, timeoutMs)
                    val job = work.submit(Callable<List<Row>> {
                        if (s.kind == "fallbacks") {
                            val lines = fallbacks()
                            if (lines.isEmpty()) listOf(Row(s, route, null, 0, "-", "the engine reported no fallback rung", nowMs(), true))
                            else lines.map { l -> Row(s.copy(id = l.id, label = l.id, parent = s.id), route.copy(fallback = "rung ${l.id}"), l.ok, 0, l.id, l.detail, nowMs(), true) }
                        } else listOf(probeOne(s, route, c, io, timeoutMs, nowMs))
                    })
                    val rows = try { await(job, limit, cancelled) } catch (e: TimeoutException) {
                        listOf(Row(s, route, false, limit, s.via, "no answer within $limit ms: the probe was abandoned", nowMs(), true))
                    } catch (e: InterruptedException) {
                        null
                    } catch (e: java.util.concurrent.ExecutionException) {
                        listOf(Row(s, route, false, 0, s.via, e.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "failed", nowMs(), true))
                    }
                    if (rows != null && !cancelled()) rows.forEach(onRow)
                })
            }
            for (j in jobs) try { j.get() } catch (_: Exception) { }
        } finally { slots.shutdownNow(); work.shutdownNow() }
    }

    /** [rows] with [r] in: it replaces its own queued row, or the placeholder it expands, or joins the end of its group. */
    fun merge(rows: List<Row>, r: Row): List<Row> {
        val m = rows.toMutableList()
        val same = m.indexOfFirst { it.spec.group == r.spec.group && it.spec.id == r.spec.id }
        if (same >= 0) { m[same] = r; return m }
        val ph = m.indexOfFirst { it.spec.group == r.spec.group && it.spec.id == r.spec.parent && !it.done }
        if (ph >= 0) { m[ph] = r; return m }
        val last = m.indexOfLast { it.spec.group == r.spec.group }
        m.add(if (last >= 0) last + 1 else m.size, r)
        return m
    }

    // ── read-out ──

    fun groupLine(rows: List<Row>, g: String): String = rows.filter { it.spec.group == g }.let { r ->
        "${r.count { it.done && it.ok == true }} of ${r.size} pass" + r.count { it.done && it.ok == false }.let { if (it > 0) " · $it fail" else "" } +
            r.count { !it.done }.let { if (it > 0) " · $it pending" else "" }
    }

    /** The whole matrix as text: one block per group, one line per probe (route, then result), for Copy all and the dump. */
    fun text(rows: List<Row>): String = buildString {
        if (rows.isEmpty()) { append("(not run)"); return@buildString }
        for (g in GROUPS) {
            val gr = rows.filter { it.spec.group == g }
            if (gr.isEmpty()) continue
            appendLine("${LETTERS[g]} ${g} (${groupLine(rows, g)})")
            for (r in gr) {
                appendLine("  ${r.status} ${r.spec.id} · ${r.ms} ms · path ${r.path.ifBlank { "-" }} · ${r.detail}")
                appendLine("    route: ${r.route.line()}")
            }
        }
    }.trimEnd()
}

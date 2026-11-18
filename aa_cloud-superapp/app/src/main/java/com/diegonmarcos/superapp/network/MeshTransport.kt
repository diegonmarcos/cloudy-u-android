package com.diegonmarcos.superapp.network

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.DnsLadder
import com.diegonmarcos.superapp.net.RelaySpec
import com.diegonmarcos.superapp.network.mesh.MeshDecl
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress

/**
 * Cloud Mesh's FALLBACK LADDER (build.json::ui.mesh_transport, its _doc is the whole argument).
 * Public Wi-Fi that blocks or hijacks DNS, blocks port 53 or drops the mesh's UDP ports leaves
 * WireGuard with no handshake. A connect walks, and the Status tab and the mesh badge say which rung
 * carries the tunnel:
 *
 *  1. Direct UDP on the configured endpoints. An endpoint that is a NAME is resolved here, not by
 *     the engine: the system resolver, trusted only when its answer shares an address with the
 *     PINNED map (data/mesh.json::bootstrap, derived from the Cloudflare zone by cloud-infra), then
 *     the pinned answer, then DNS-over-HTTPS by IP (DnsLadder.doh, the Store's own rung).
 *  2. No handshake within handshake_wait_ms: the TLS-443 relay. Its host walks the same ladder;
 *     the WireGuard engine dials it (MeshRelay: real TLS on 443, SNI and certificate checked, its
 *     socket protect()ed from the tunnel), and each hub the relay reaches gets its endpoint pointed
 *     at the relay's loopback port. A rung whose address does not take the relay is skipped.
 *
 * What a connect chose is a [Plan] in wireguard_prefs; [route] applies it to every bring-up
 * (WireGuardPrefs.toTunnelConfig), so a DNS-preset re-apply or the badge's Connect keeps the path.
 * The relay key never leaves the prefs except into the engine call that dials with it.
 */
object MeshTransport {

    const val MODE_AUTO = "auto"
    const val MODE_DIRECT = "direct"
    const val MODE_RELAY = "relay"
    const val RUNG_SYSTEM = "system"
    const val RUNG_PINNED = "pinned"
    private const val LOOPBACK = "127.0.0.1"

    // ── declarations ────────────────────────────────────────────────────

    data class Doh(val label: String, val url: String)
    data class Decl(val defaultMode: String, val handshakeWaitMs: Long, val relayListenBase: Int, val probeTimeoutMs: Int, val doh: List<Doh>)

    data class RelayRoute(val remote: String, val hub: String, val publicKey: String)
    data class Relay(val host: String, val port: Int, val routes: List<RelayRoute>)
    /** [pinned]: host -> its declared public addresses (v4 first). */
    data class Bootstrap(val pinned: Map<String, List<String>>, val relay: Relay?)

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun parseDecl(json: String): Decl {
        val o = JSONObject(json)
        val d = o.optJSONArray("doh") ?: JSONArray()
        return Decl(o.optString("default_mode", MODE_AUTO), o.optLong("handshake_wait_ms", 8000L).coerceAtLeast(1000L),
            o.optInt("relay_listen_base", 51830), o.optInt("probe_timeout_ms", 6000).coerceAtLeast(500),
            (0 until d.length()).map { d.getJSONObject(it).let { x -> Doh(x.getString("label"), x.getString("url")) } })
    }

    /** data/mesh.json's `bootstrap` (empty when the snapshot predates it). */
    fun parseBootstrap(meshJson: String): Bootstrap {
        val b = JSONObject(meshJson).optJSONObject("bootstrap") ?: return Bootstrap(emptyMap(), null)
        val ph = b.optJSONArray("pinned_hosts") ?: JSONArray()
        val pinned = (0 until ph.length()).associate { i ->
            ph.getJSONObject(i).let { it.getString("host").lowercase() to (it.optJSONArray("v4").strings() + it.optJSONArray("v6").strings()) }
        }
        val relay = b.optJSONObject("relay")?.let { r ->
            val rs = r.optJSONArray("routes") ?: JSONArray()
            Relay(r.getString("host").lowercase(), r.optInt("port", 443), (0 until rs.length()).map { i ->
                rs.getJSONObject(i).let { RelayRoute(it.getString("remote"), it.optString("hub"), it.getString("public_key")) }
            })
        }
        return Bootstrap(pinned, relay)
    }

    val decl: Decl by lazy {
        runCatching { parseDecl(String(Base64.decode(BuildConfig.UI_MESH_TRANSPORT_B64, Base64.DEFAULT))) }
            .getOrDefault(Decl(MODE_AUTO, 8000L, 51830, 6000, emptyList()))
    }
    val boot: Bootstrap by lazy {
        runCatching { parseBootstrap(String(Base64.decode(BuildConfig.MESH_JSON_B64, Base64.DEFAULT))) }.getOrDefault(Bootstrap(emptyMap(), null))
    }

    // ── the plan a connect chose ────────────────────────────────────────

    enum class Path(val id: String, val label: String) {
        DIRECT("direct", "Direct UDP"), PINNED("pinned", "Pinned IP"), DOH("doh", "DoH"), RELAY("relay", "TLS-443 relay"), NONE("none", WgLink.NO_PATH);
        companion object { fun of(id: String?): Path? = entries.firstOrNull { it.id == id } }
    }

    /** [endpoints]: peer public key -> the endpoint to use instead of the configured one. [relay] carries no key. */
    data class Plan(val path: Path, val detail: String, val endpoints: Map<String, String>, val relay: RelaySpec?) {
        fun toJson(): String = JSONObject().put("path", path.id).put("detail", detail)
            .put("endpoints", JSONObject(endpoints))
            .put("relay", relay?.copy(prefix = "")?.toJson()?.let { JSONObject(it) } ?: JSONObject.NULL)
            .toString()

        companion object {
            fun parse(json: String?): Plan? = runCatching {
                val o = JSONObject(json.orEmpty())
                val e = o.optJSONObject("endpoints") ?: JSONObject()
                Plan(Path.of(o.optString("path")) ?: return null, o.optString("detail"),
                    e.keys().asSequence().associateWith { e.getString(it) },
                    o.optJSONObject("relay")?.let { RelaySpec.parse(it.toString()) })
            }.getOrNull()
        }
    }

    // ── pure helpers (MeshTransportTest) ────────────────────────────────

    /** "host:port" / "[v6]:port" -> host, port; null without a port. */
    fun splitEndpoint(ep: String): Pair<String, Int>? {
        val e = ep.trim()
        val i = e.lastIndexOf(':')
        if (i <= 0) return null
        val port = e.substring(i + 1).toIntOrNull() ?: return null
        return e.substring(0, i).removePrefix("[").removeSuffix("]") to port
    }

    fun joinEndpoint(ip: String, port: Int): String = if (':' in ip) "[$ip]:$port" else "$ip:$port"

    private val V4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    fun isIpLiteral(host: String): Boolean = V4.matches(host) || (':' in host && Regex("^[0-9a-fA-F:.]+$").matches(host))

    /** The system's answer for a pinned host is trusted only when it shares an address with the pin. */
    fun agrees(answers: Collection<String>, pinned: Collection<String>): Boolean = pinned.isEmpty() || answers.any { it in pinned }

    /** RFC 1918 / ULA: an answer only a private resolver (the mesh's, or a captive portal's) gives. */
    fun isPrivate(a: InetAddress): Boolean = a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
        (a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC)

    /** Which rung an answer came from, as a path. */
    fun pathOf(rung: String?): Path = when {
        rung == null -> Path.NONE
        rung == RUNG_PINNED -> Path.PINNED
        rung.startsWith("DoH") -> Path.DOH
        else -> Path.DIRECT
    }

    /** The more-fallback of two paths (DoH over pinned over direct), for a plan resolved from several names. */
    fun worse(a: Path, b: Path): Path = if (b.ordinal > a.ordinal) b else a

    /** Each peer whose key a relay route names, with the loopback port it gets (base, base + 1, ...). */
    fun relayLegs(peerKeys: List<String>, relay: Relay, base: Int): List<Pair<String, RelaySpec.Route>> =
        relay.routes.filter { it.publicKey in peerKeys }.mapIndexed { i, r -> r.publicKey to RelaySpec.Route(base + i, r.remote) }

    /** [wgQuick] with the Endpoint of every [Peer] whose PublicKey is in [endpoints] replaced (or added). */
    fun rewrite(wgQuick: String, endpoints: Map<String, String>): String {
        if (endpoints.isEmpty()) return wgQuick
        val out = ArrayList<String>()
        var section = ArrayList<String>()
        fun flush() {
            val key = section.firstOrNull { it.trim().startsWith("PublicKey", ignoreCase = true) }?.substringAfter('=')?.trim()
            val to = if (section.firstOrNull()?.trim().equals("[Peer]", ignoreCase = true)) key?.let { endpoints[it] } else null
            if (to == null) out += section
            else {
                val kept = section.filterNot { it.trim().startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
                out += kept.first(); out += "Endpoint = $to"; out += kept.drop(1)
            }
            section = ArrayList()
        }
        for (l in wgQuick.lines()) {
            if (l.trim().startsWith("[") && section.isNotEmpty()) flush()
            section += l
        }
        flush()
        return out.joinToString("\n")
    }

    /** The resolver ladder for [host]: system (checked against the pin), the pin, each DoH endpoint. */
    fun rungs(host: String, b: Bootstrap = boot, d: Decl = decl,
              system: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): List<DnsLadder.Rung> {
        val pin = b.pinned[host.lowercase()].orEmpty()
        val sys = DnsLadder.Rung(RUNG_SYSTEM) { h ->
            val a = system(h)
            val ips = a.mapNotNull { it.hostAddress }
            if (!agrees(ips, pin)) throw IOException("answered ${ips.joinToString(", ")}, the fleet pins ${pin.joinToString(", ")} (hijacked or stale)")
            a
        }
        val pinned = if (pin.isEmpty()) emptyList() else listOf(DnsLadder.Rung(RUNG_PINNED) { pin.map { InetAddress.getByName(it) } })
        return listOf(sys) + pinned + d.doh.map { DnsLadder.doh(it.label, it.url, d.probeTimeoutMs) }
    }

    // ── on the phone ────────────────────────────────────────────────────

    fun plan(ctx: Context): Plan? = Plan.parse(WgState.prefs(ctx).transportPlan)

    private fun save(ctx: Context, p: Plan?) {
        val before = WgState.prefs(ctx).transportPlan
        WgState.prefs(ctx).transportPlan = p?.toJson().orEmpty(); ensured = null
        // #1153 a switch between the UDP rungs and the TLS-443 relay is an event the Logs section shows
        if (p != null && p.toJson() != before) com.diegonmarcos.superapp.network.mesh.MeshEventLog.log(ctx, "transport",
            "path ${Plan.parse(before)?.path?.label ?: "none"} -> ${p.path.label}: ${p.detail}")
    }

    /**
     * The config a bring-up uses: [cfg] with the endpoints the last connect chose. A relay plan whose
     * relay cannot run (no key, an engine without it) brings up the configured endpoints instead.
     */
    fun route(ctx: Context, cfg: Config): Config {
        val p = plan(ctx) ?: return cfg
        if (p.endpoints.isEmpty()) return cfg
        if (p.path == Path.RELAY && !ensureRelay(ctx, p)) return cfg
        return runCatching { Config.parse(BufferedReader(StringReader(rewrite(cfg.toWgQuickString(), p.endpoints)))) }.getOrDefault(cfg)
    }

    @Volatile private var ensured: Pair<Int, Long>? = null

    /** The engine runs [p]'s relay (idempotent there; asked again at most every 15 s from here). */
    private fun ensureRelay(ctx: Context, p: Plan): Boolean {
        val spec = p.relay?.copy(prefix = WgState.prefs(ctx).relayKey) ?: return false
        if (spec.prefix.isBlank()) return false
        val sig = spec.hashCode()
        val now = System.currentTimeMillis()
        ensured?.let { if (it.first == sig && now - it.second < 15_000) return true }
        val st = WgState.backend(ctx).setRelay(spec)
        if (st.legs.isEmpty()) return false
        ensured = sig to now
        return true
    }

    /** Down: the relay stops and the next connect walks the ladder afresh. */
    fun release(ctx: Context) {
        save(ctx, null)
        runCatching { WgState.backend(ctx).let { if (it.hasRelay()) it.setRelay(null) } }
    }

    /** The plan for UDP: every NAME endpoint resolved through the ladder, IP literals as they are. */
    private fun directPlan(peers: List<WireGuardPrefs.PeerData>): Plan {
        var path = Path.DIRECT
        val eps = HashMap<String, String>()
        val notes = ArrayList<String>()
        for (p in peers) {
            val (host, port) = splitEndpoint(p.endpoint) ?: continue
            if (isIpLiteral(host)) continue
            val w = DnsLadder.walk(host, rungs(host))
            val a = w.addrs.firstOrNull()?.hostAddress
            if (a == null) { notes += "$host: ${w.trail}"; continue }
            eps[p.publicKey] = joinEndpoint(a, port)
            path = worse(path, pathOf(w.via))
            notes += "$host -> $a (${w.via})"
        }
        val detail = if (notes.isEmpty()) "configured endpoints, no DNS needed" else notes.joinToString("; ")
        return Plan(path, detail, eps, null)
    }

    /** The newest handshake of any peer reaches [since] within [waitMs]. */
    private fun handshakeSince(ctx: Context, since: Long, waitMs: Long): Boolean {
        val prefs = WgState.prefs(ctx)
        val backend = WgState.backend(ctx)
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val newest = runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull()?.let { st ->
                prefs.peers().maxOfOrNull { p -> runCatching { st.peer(Key.fromBase64(p.publicKey))?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L) }
            } ?: 0L
            if (newest >= since - 2_000) return true
            if (System.currentTimeMillis() >= until) return false
            Thread.sleep(500)
        }
    }

    /**
     * Bring the tunnel up the first way that handshakes. [bringUp] raises it with
     * WireGuardPrefs.toTunnelConfig(), which [route]s through the plan saved just before; it answers
     * the engine's state. Returns the line for the notice; throws when the engine refuses to come up.
     */
    fun connect(ctx: Context, bringUp: () -> Tunnel.State): String {
        val prefs = WgState.prefs(ctx)
        val mode = prefs.transportMode
        val peers = prefs.peers()
        val direct = directPlan(peers)
        val trail = ArrayList<String>()
        if (mode != MODE_RELAY) {
            save(ctx, direct)
            runCatching { WgState.backend(ctx).let { if (it.hasRelay()) it.setRelay(null) } }
            val t0 = System.currentTimeMillis()
            val r = bringUp()
            check(r == Tunnel.State.UP) { "the engine answered $r" }
            if (mode == MODE_DIRECT) return "${direct.path.label} (fallbacks off): ${direct.detail}"
            if (handshakeSince(ctx, t0, decl.handshakeWaitMs)) return "${direct.path.label}: ${direct.detail}"
            trail += "UDP: no handshake in ${decl.handshakeWaitMs / 1000} s"
        }
        val relay = relayPlan(ctx, peers, trail)
        if (relay == null) {
            save(ctx, direct.copy(path = Path.NONE, detail = trail.joinToString("; ")))
            if (mode == MODE_RELAY) check(bringUp() == Tunnel.State.UP) { "the engine refused the tunnel" }
            return "${Path.NONE.label}: ${trail.joinToString("; ")} - still on UDP, it recovers when the network lets it through"
        }
        save(ctx, relay)
        val t1 = System.currentTimeMillis()
        val r = bringUp()
        check(r == Tunnel.State.UP) { "the engine answered $r on the relay" }
        return if (handshakeSince(ctx, t1, decl.handshakeWaitMs)) "${relay.path.label}: ${relay.detail}"
        else "${relay.path.label} up, no handshake yet: ${relay.detail}"
    }

    /** The relay plan, or null with the reason in [trail]. */
    private fun relayPlan(ctx: Context, peers: List<WireGuardPrefs.PeerData>, trail: MutableList<String>): Plan? {
        val relay = boot.relay ?: run { trail += "no TLS-443 relay is declared in data/mesh.json"; return null }
        val key = WgState.prefs(ctx).relayKey
        if (key.isBlank()) { trail += "the TLS-443 relay needs its key (Controls > Fallbacks > relay key)"; return null }
        val backend = WgState.backend(ctx)
        if (!backend.hasRelay()) { trail += backend.relayMissing(); return null }
        val legs = relayLegs(peers.map { it.publicKey }, relay, decl.relayListenBase)
        if (legs.isEmpty()) { trail += "no peer of this tunnel is a hub the relay reaches (${relay.routes.joinToString { it.hub }})"; return null }
        val skip = HashSet<String>()
        repeat(1 + decl.doh.size + 1) {
            val w = DnsLadder.walk(relay.host, rungs(relay.host), skip)
            val rung = w.via ?: run { trail += "relay ${relay.host}: ${w.trail}"; return null }
            val addrs = w.addrs.mapNotNull { it.hostAddress }
            val spec = RelaySpec(relay.host, relay.port, addrs, key, legs.map { it.second })
            val pr = backend.probeRelay(spec)
            if (pr.ok) {
                val ordered = listOf(pr.addr) + addrs.filter { it != pr.addr }
                return Plan(Path.RELAY, "${relay.host} -> ${pr.addr} (${rungWord(rung)}), ${pr.tls}, ${pr.ms} ms",
                    legs.associate { it.first to "$LOOPBACK:${it.second.listen}" }, spec.copy(addrs = ordered))
            }
            trail += "relay via ${rungWord(rung)} (${addrs.joinToString(", ")}): ${pr.error.ifBlank { "HTTP ${pr.code}" }}"
            // The server answered and refused: the key or the route, which no other address changes.
            if (pr.code in 400..499) return null
            skip += rung
        }
        return null
    }

    private fun rungWord(rung: String): String = when (rung) {
        RUNG_SYSTEM -> "system DNS"
        RUNG_PINNED -> "pinned IP"
        else -> rung
    }

    // ── what the page and the badge show ────────────────────────────────

    /** The path in force: the plan's, NONE-with-reason, or null before any connect of this install. */
    fun current(ctx: Context): Plan? = plan(ctx)

    /** "TLS-443 relay up via 129.151.228.66 · 1.2 KB in, 3.4 KB out" / its error; "" when no relay runs. */
    fun relayLine(ctx: Context): String {
        val b = WgState.backend(ctx)
        if (!b.hasRelay()) return ""
        val st = b.relayStatus()
        if (st.state == "off") return ""
        return st.legs.joinToString("; ") { l ->
            (if (l.up) "up via ${l.via}" else if (l.error.isNotBlank()) "down: ${l.error}" else "waiting for the first packet") +
                " · 127.0.0.1:${l.listen} -> ${l.remote} · ${NetworkBadgeModel.bytes(l.rx)} in, ${NetworkBadgeModel.bytes(l.tx)} out"
        }
    }

    // ── Test fallbacks ──────────────────────────────────────────────────

    /** One rung's verdict: [ok] null = not testable right now (says why). */
    data class Probe(val id: String, val ok: Boolean?, val detail: String)

    /** Every rung probed on its own, in ladder order: udp, dns, pinned, doh, relay. Blocking. */
    fun test(ctx: Context): List<Probe> {
        val out = ArrayList<Probe>()
        out += udpProbe(ctx)
        val relay = boot.relay
        if (relay == null) { out += Probe("relay", false, "no TLS-443 relay is declared in data/mesh.json"); out += udpPerPeer(ctx); return out }
        val host = relay.host
        val pin = boot.pinned[host].orEmpty()
        val sys = DnsLadder.walk(host, listOf(DnsLadder.Rung(RUNG_SYSTEM) { InetAddress.getAllByName(it).toList() }))
        val sysIps = sys.addrs.mapNotNull { it.hostAddress }
        val tunnelUp = runCatching { WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        out += when {
            sys.via == null -> Probe("dns", false, "$host: ${sys.trail}")
            agrees(sysIps, pin) -> Probe("dns", true, "$host -> ${sysIps.joinToString(", ")}, as pinned")
            // With the mesh up, its own resolver answers the hub's mesh address (split horizon): DNS works.
            tunnelUp && sys.addrs.all { isPrivate(it) } -> Probe("dns", true, "$host -> ${sysIps.joinToString(", ")}: the mesh's split-horizon answer; off the mesh it is ${pin.joinToString(", ")}")
            else -> Probe("dns", false, "$host -> ${sysIps.joinToString(", ")}, but the fleet pins ${pin.joinToString(", ")}: this network answers wrong")
        }
        val backend = WgState.backend(ctx)
        val key = WgState.prefs(ctx).relayKey
        val route = relay.routes.firstOrNull()?.let { RelaySpec.Route(0, it.remote) }
        val engine = backend.hasRelay()
        out += when {
            pin.isEmpty() -> Probe("pinned", false, "data/mesh.json pins no address for $host")
            !engine -> Probe("pinned", null, backend.relayMissing())
            route == null -> Probe("pinned", false, "the relay declares no route")
            else -> backend.probeRelay(RelaySpec(host, relay.port, pin, key, listOf(route))).let { p ->
                if (p.tls.isNotBlank() && p.tls != "none") Probe("pinned", true, "${p.addr}:${relay.port} answers ${p.tls} with a certificate valid for $host (${p.ms} ms)")
                else Probe("pinned", false, "${pin.joinToString(", ")}: ${p.error.ifBlank { "no TLS" }}")
            }
        }
        val doh = decl.doh.map { d ->
            val w = DnsLadder.walk(host, listOf(DnsLadder.doh(d.label, d.url, decl.probeTimeoutMs)))
            val ips = w.addrs.mapNotNull { it.hostAddress }
            Triple(d.label, w.via != null && agrees(ips, pin), if (w.via == null) w.trail else ips.joinToString(", "))
        }
        out += Probe("doh", doh.any { it.second }, doh.joinToString("; ") { "${it.first}: ${it.third}" }.ifBlank { "no DoH endpoint declared" })
        out += when {
            key.isBlank() -> Probe("relay", false, "no relay key: paste the server's key in Controls > Fallbacks")
            !engine -> Probe("relay", null, backend.relayMissing())
            route == null -> Probe("relay", false, "the relay declares no route")
            else -> {
                val w = DnsLadder.walk(host, rungs(host))
                val addrs = w.addrs.mapNotNull { it.hostAddress }
                if (w.via == null) Probe("relay", false, "$host: ${w.trail}")
                else backend.probeRelay(RelaySpec(host, relay.port, addrs, key, listOf(route))).let { p ->
                    if (p.ok) Probe("relay", true, "wss://$host via ${p.addr} (${rungWord(w.via!!)}): ${p.tls}, upgrade 101 in ${p.ms} ms" +
                        relayLine(ctx).let { if (it.isBlank()) "" else "; carrying: $it" } + tried(p))
                    else Probe("relay", false, "${p.addr.ifBlank { addrs.joinToString(", ") }}: ${p.error.ifBlank { "HTTP ${p.code}" }}" + tried(p))
                }
            }
        }
        // #1797 one line per fallback step for Status > lights. Additions only: the five lines above are unchanged.
        out += udpPerPeer(ctx)
        for ((label, ok, detail) in doh) out += Probe("doh:$label", ok, detail)
        val blocked: String? = when {
            key.isBlank() -> "no relay key"
            !engine -> backend.relayMissing()
            route == null -> "the relay declares no route"
            else -> null
        }
        val w = if (blocked == null) DnsLadder.walk(host, rungs(host)) else null
        val all = w?.addrs?.mapNotNull { it.hostAddress }.orEmpty()
        for ((fam, ip6) in listOf("v4" to false, "v6" to true)) {
            val addrs = all.filter { (':' in it) == ip6 }
            out += when {
                blocked != null -> Probe("relay:$fam", if (key.isBlank()) false else null, blocked)
                w?.via == null -> Probe("relay:$fam", false, "$host: ${w?.trail.orEmpty()}")
                addrs.isEmpty() -> Probe("relay:$fam", null, "$host has no IPv${if (ip6) 6 else 4} address (${all.joinToString(", ")})")
                else -> backend.probeRelay(RelaySpec(host, relay.port, addrs, key, listOf(route!!))).let { p ->
                    if (p.ok) Probe("relay:$fam", true, "wss://$host via ${p.addr}: ${p.tls}, upgrade 101 in ${p.ms} ms")
                    else Probe("relay:$fam", false, "${p.addr.ifBlank { addrs.joinToString(", ") }}: ${p.error.ifBlank { "HTTP ${p.code}" }}")
                }
            }
        }
        out += Probe("relay_key", key.isNotBlank(),
            if (key.isBlank()) "no relay key: paste the server's key in Controls > Fallbacks" else "a relay key is stored (${key.length} characters, never shown)")
        return out
    }

    /** #1798 Every address the relay dialer tried or skipped (Happy Eyeballs); "" from an engine too old to report them. */
    private fun tried(p: com.diegonmarcos.superapp.net.RelayProbe): String =
        if (p.attempts.isBlank()) "" else "; tried: " + p.attempts.lines().joinToString(" | ")

    /** #1797 Direct UDP per peer: a fresh WireGuard handshake on that peer, when it is not behind the relay. */
    private fun udpPerPeer(ctx: Context): List<Probe> {
        val backend = WgState.backend(ctx)
        val up = runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val relayed = plan(ctx)?.takeIf { it.path == Path.RELAY }?.endpoints?.keys.orEmpty()
        val st = if (up) runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull() else null
        val stale = MeshDecl.baked.handshakeStaleS * 1000
        val now = System.currentTimeMillis()
        return WgState.prefs(ctx).peers().map { p ->
            val id = "udp:" + p.name.ifBlank { p.publicKey.take(8) }
            val hs = runCatching { st?.peer(Key.fromBase64(p.publicKey))?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L)
            when {
                !up -> Probe(id, null, "the tunnel is down")
                p.publicKey in relayed -> Probe(id, null, "${p.endpoint}: this peer runs on the relay, so UDP is not what carries it")
                hs > 0 && now - hs <= stale -> Probe(id, true, "handshake at ${p.endpoint}, ${(now - hs) / 1000} s ago")
                hs > 0 -> Probe(id, false, "last handshake at ${p.endpoint} ${(now - hs) / 1000} s ago (stale after ${stale / 1000} s)")
                else -> Probe(id, false, "no handshake at ${p.endpoint}")
            }
        }
    }

    /** UDP is proven only by a WireGuard handshake on a peer that is NOT behind the relay. */
    private fun udpProbe(ctx: Context): Probe {
        val backend = WgState.backend(ctx)
        val up = runCatching { backend.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        if (!up) return Probe("udp", null, "the tunnel is down: connect, a WireGuard handshake is the only proof UDP passes")
        val relayed = plan(ctx)?.takeIf { it.path == Path.RELAY }?.endpoints?.keys.orEmpty()
        val st = runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull()
        val stale = MeshDecl.baked.handshakeStaleS * 1000
        val now = System.currentTimeMillis()
        val direct = WgState.prefs(ctx).peers().filter { it.publicKey !in relayed }
        val fresh = direct.mapNotNull { p ->
            val hs = runCatching { st?.peer(Key.fromBase64(p.publicKey))?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L)
            if (hs > 0 && now - hs <= stale) "${p.name.ifBlank { p.publicKey.take(8) }} at ${p.endpoint}, ${(now - hs) / 1000} s ago" else null
        }
        return when {
            fresh.isNotEmpty() -> Probe("udp", true, "handshake " + fresh.joinToString("; "))
            direct.isEmpty() -> Probe("udp", false, "every hub runs on the relay: UDP gave no handshake at connect")
            else -> Probe("udp", false, "no UDP handshake within ${stale / 1000} s on ${direct.joinToString { it.endpoint }}")
        }
    }
}

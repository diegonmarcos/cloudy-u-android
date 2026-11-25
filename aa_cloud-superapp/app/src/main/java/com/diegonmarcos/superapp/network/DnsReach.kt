package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * #1899 The DNS page's reachability test BY IP: no name is resolved, so the page can tell a DNS
 * failure from a network failure.
 *
 * Every declared target (`build.json::ui.dns.reachability.targets`, read through [FleetDns.decl];
 * no address or name is written here) is a well-known public site reached at its fixed address.
 * Every declared check runs against it: a TCP connect, and for a TLS check a handshake whose
 * certificate is verified against the target's name (sent as SNI). Port 53 is only opened, no DNS
 * query is sent. Each check runs on two paths:
 *  - [PATH_VPN] the normal socket path every fleet app uses: inside the VPN while one is up;
 *  - [PATH_DIRECT] a socket bound to the non-VPN network (Network.bindSocket, the bind
 *    FleetDnsBridge uses for its underlying routes). VpnService.protect is not available in this
 *    process: the engine app owns the VPN.
 * A row names the interface that really carried it, read from the connected socket's local address.
 *
 * [verdict] and [compare] are pure: DnsReachTest walks every combination.
 */
object DnsReach {

    data class Target(val id: String, val label: String, val ip: String, val tlsName: String)
    data class Check(val id: String, val label: String, val port: Int, val tls: Boolean)
    data class Decl(val timeoutMs: Int = 0, val checks: List<Check> = emptyList(), val targets: List<Target> = emptyList())

    const val PATH_VPN = "vpn"
    const val PATH_DIRECT = "direct"

    private val V4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val V6 = Regex("""[0-9a-fA-F:]*:[0-9a-fA-F:.]*""")

    /** True for an IPv4 / IPv6 literal: the only thing a target may name, because a hostname would need DNS. */
    fun isIpLiteral(s: String): Boolean =
        V4.matchEntire(s)?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true || (s.count { it == ':' } >= 2 && V6.matches(s))

    /** `ui.dns.reachability`; absent = nothing to test. A target that is not an IP literal, or a TLS check with a nameless target, is refused. */
    fun parse(o: JSONObject?): Decl {
        if (o == null) return Decl()
        val cs = o.optJSONArray("checks") ?: JSONArray()
        val ts = o.optJSONArray("targets") ?: JSONArray()
        val checks = (0 until cs.length()).map {
            val c = cs.getJSONObject(it)
            Check(c.getString("id"), c.getString("label"), c.getInt("port"), c.optBoolean("tls", false))
        }
        val targets = (0 until ts.length()).map {
            val t = ts.getJSONObject(it)
            Target(t.getString("id"), t.getString("label"), t.getString("ip"), t.optString("tls_name"))
        }
        for (t in targets) {
            require(isIpLiteral(t.ip)) { "reachability target ${t.id}: '${t.ip}' is not an IP literal (a name would need DNS)" }
            require(t.tlsName.isNotBlank() || checks.none { it.tls }) { "reachability target ${t.id} has no tls_name for the TLS check" }
        }
        for (c in checks) require(c.port in 1..65535) { "reachability check ${c.id}: port ${c.port}" }
        return Decl(o.optInt("connect_timeout_ms", 4000), checks, targets)
    }

    // ── one check ────────────────────────────────────────────────────────

    /** One interface of the phone as the test saw it: its label, whether it is the VPN, its own addresses. */
    data class Net(val label: String, val vpn: Boolean, val addresses: List<String>)

    data class Row(
        val target: Target,
        val check: Check,
        /** The path asked for: [PATH_VPN] or [PATH_DIRECT]. */
        val path: String,
        val reachable: Boolean,
        val latencyMs: Long,
        /** The interface that carried it, from the connected socket ("" = no connection was made). */
        val carriedBy: String,
        val error: String,
    )

    /** The interface owning [local] (a connected socket's own address): the path that really carried the check. */
    fun carriedBy(local: String?, nets: List<Net>): String {
        if (local.isNullOrEmpty()) return ""
        val n = nets.firstOrNull { local in it.addresses } ?: return "interface $local"
        return if (n.vpn) "VPN mesh tunnel" else "direct (${n.label})"
    }

    /**
     * [check] against [target] on one path. [bind] puts the socket on a network before it connects
     * (null = the normal path). Never throws: a failure is a row with the error.
     */
    fun probe(target: Target, check: Check, path: String, timeoutMs: Int, nets: List<Net>, bind: ((Socket) -> Unit)?): Row {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var local: String? = null
        return try {
            require(isIpLiteral(target.ip)) { "${target.ip} is not an IP literal" }
            Socket().use { s ->
                bind?.invoke(s)
                s.connect(InetSocketAddress(InetAddress.getByName(target.ip), check.port), timeoutMs) // a literal: no lookup
                local = s.localAddress?.hostAddress?.substringBefore('%')
                if (check.tls) handshake(s, target.tlsName, check.port, timeoutMs)
            }
            Row(target, check, path, true, ms(), carriedBy(local, nets), "")
        } catch (e: Exception) {
            Row(target, check, path, false, ms(), carriedBy(local, nets), "${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /** TLS over the connected [raw]: SNI [name], the chain checked by the system trust store and the certificate against [name]. */
    private fun handshake(raw: Socket, name: String, port: Int, timeoutMs: Int) {
        raw.soTimeout = timeoutMs
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, port, true) as SSLSocket
        ssl.use {
            val p = it.sslParameters
            p.serverNames = listOf(SNIHostName(name))
            p.endpointIdentificationAlgorithm = "HTTPS"
            it.sslParameters = p
            it.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, it.session))
                throw SSLPeerUnverifiedException("the certificate does not match $name")
        }
    }

    // ── the verdict (pure) ───────────────────────────────────────────────

    enum class Reach { ALL, SOME, NONE, NOT_RUN }
    enum class Lookups { OK, FAILING, MIXED, NOT_RUN }

    enum class Verdict(val ok: Boolean, val text: String) {
        HEALTHY(true, "No issue found: every IP target is reachable and the names resolve."),
        DNS_ONLY(false, "DNS is the only issue: every IP target is reachable, the name lookups fail."),
        NETWORK(false, "Network/VPN issue, not DNS: the IP targets are unreachable too."),
        PARTIAL(false, "Partial: some checks pass and some fail (see the rows)."),
        NAMES_UNTESTED(true, "Every IP target is reachable; no name lookup was run to compare."),
        NOT_RUN(false, "Not run: no reachability target is declared."),
    }

    /** How many of [path]'s checks passed. */
    fun reach(rows: List<Row>, path: String): Reach {
        val mine = rows.filter { it.path == path }
        return when {
            mine.isEmpty() -> Reach.NOT_RUN
            mine.all { it.reachable } -> Reach.ALL
            mine.none { it.reachable } -> Reach.NONE
            else -> Reach.SOME
        }
    }

    /** One entry per name asked: did it resolve. */
    fun lookups(resolved: List<Boolean>): Lookups = when {
        resolved.isEmpty() -> Lookups.NOT_RUN
        resolved.all { it } -> Lookups.OK
        resolved.none { it } -> Lookups.FAILING
        else -> Lookups.MIXED
    }

    /** The normal path's IP reachability held against the name lookups. */
    fun verdict(ip: Reach, names: Lookups): Verdict = when (ip) {
        Reach.NOT_RUN -> Verdict.NOT_RUN
        Reach.NONE -> Verdict.NETWORK
        Reach.SOME -> Verdict.PARTIAL
        Reach.ALL -> when (names) {
            Lookups.OK -> Verdict.HEALTHY
            Lookups.FAILING -> Verdict.DNS_ONLY
            Lookups.MIXED -> Verdict.PARTIAL
            Lookups.NOT_RUN -> Verdict.NAMES_UNTESTED
        }
    }

    enum class PathCause(val text: String) {
        BOTH_OK("Both paths reach every target."),
        VPN_PATH("The normal (VPN) path is at fault: the direct path reaches every target."),
        DIRECT_BLOCKED("Only the normal path reaches every target: direct sockets are blocked or the underlying network filters them (expected under VPN lockdown)."),
        UNDERLYING("Neither path reaches every target: the underlying network is at fault, not the VPN alone."),
        NO_DIRECT("No non-VPN network to bind to: the paths are not compared."),
        NOT_RUN("Not run."),
    }

    /** The two paths compared: which one is at fault. */
    fun compare(vpn: Reach, direct: Reach): PathCause = when {
        vpn == Reach.NOT_RUN -> PathCause.NOT_RUN
        direct == Reach.NOT_RUN -> PathCause.NO_DIRECT
        vpn == Reach.ALL && direct == Reach.ALL -> PathCause.BOTH_OK
        direct == Reach.ALL -> PathCause.VPN_PATH
        vpn == Reach.ALL -> PathCause.DIRECT_BLOCKED
        else -> PathCause.UNDERLYING
    }

    // ── the run ──────────────────────────────────────────────────────────

    /** One name asked of Android's resolver for the verdict. */
    data class NameRow(val name: String, val resolved: Boolean, val detail: String)

    class Outcome(
        val rows: List<Row>,
        val names: List<NameRow>,
        /** A VPN is up: the normal path is inside it. */
        val vpnUp: Boolean,
        /** The non-VPN network the direct run was bound to (null = none). */
        val directNet: String?,
    ) {
        val vpn get() = DnsReach.reach(rows, PATH_VPN)
        val direct get() = DnsReach.reach(rows, PATH_DIRECT)
        val verdict get() = DnsReach.verdict(vpn, lookups(names.map { it.resolved }))
        val cause get() = DnsReach.compare(vpn, direct)
    }

    private fun kind(c: NetworkCapabilities): String = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "network"
    }

    /**
     * Every declared check on both paths (in parallel, each bounded by the declared timeout), then
     * [names] asked of Android's resolver, which is what every fleet app asks.
     */
    @Suppress("DEPRECATION")
    fun run(ctx: Context, d: Decl, names: List<String>): Outcome {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val all: List<Pair<Network, NetworkCapabilities>> =
            runCatching { cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } } }.getOrDefault(emptyList())
        val nets = all.map { (n, c) ->
            Net(kind(c), c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                runCatching { cm.getLinkProperties(n)?.linkAddresses?.mapNotNull { it.address.hostAddress?.substringBefore('%') } }.getOrNull().orEmpty())
        }
        val outside = all.filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val direct = outside.firstOrNull { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } ?: outside.firstOrNull()
        val jobs = ArrayList<Callable<Row>>()
        for (t in d.targets) for (c in d.checks) {
            jobs += Callable { probe(t, c, PATH_VPN, d.timeoutMs, nets, null) }
            direct?.let { (n, _) -> jobs += Callable { probe(t, c, PATH_DIRECT, d.timeoutMs, nets) { s -> n.bindSocket(s) } } }
        }
        val rows = if (jobs.isEmpty()) emptyList() else {
            val pool = Executors.newFixedThreadPool(jobs.size)
            try { pool.invokeAll(jobs).map { it.get() } } finally { pool.shutdownNow() }
        }
        val asked = names.filter { it.isNotEmpty() }.map { name ->
            runCatching { InetAddress.getAllByName(name).mapNotNull { it.hostAddress }.joinToString(", ") }
                .fold({ NameRow(name, true, it) }, { NameRow(name, false, "${it.javaClass.simpleName}: ${it.message ?: "no detail"}") })
        }
        return Outcome(rows, asked, nets.any { it.vpn }, direct?.let { (_, c) -> kind(c) })
    }

    // ── the read-out ─────────────────────────────────────────────────────

    /** What the page shows before a run: the declared targets and checks. */
    fun planText(d: Decl): String =
        if (d.targets.isEmpty()) Verdict.NOT_RUN.text
        else d.targets.joinToString(" · ") { "${it.label} ${it.ip}" } + "\n" +
            d.checks.joinToString(" + ") { "${it.label} :${it.port}" } + ", on the normal path and direct"

    fun pathLabel(path: String, vpnUp: Boolean, directNet: String?): String = when {
        path == PATH_DIRECT -> "direct, bound to ${directNet ?: "the network"}"
        vpnUp -> "inside VPN"
        else -> "normal path (no VPN up)"
    }

    private fun tally(rows: List<Row>, path: String): String =
        rows.filter { it.path == path }.let { "${it.count { r -> r.reachable }}/${it.size} reachable" }

    /** The full read-out: the verdict, the paths compared, the names, then one line per target, check and path. */
    fun text(r: Outcome): String = buildString {
        append(if (r.verdict.ok) "✓ " else "✗ ").append(r.verdict.text).append('\n')
        append("Paths: ").append(r.cause.text).append('\n')
        append("  ${pathLabel(PATH_VPN, r.vpnUp, r.directNet)}: ${tally(r.rows, PATH_VPN)}\n")
        append(if (r.directNet == null) "  direct: not run (no non-VPN network)\n" else "  ${pathLabel(PATH_DIRECT, r.vpnUp, r.directNet)}: ${tally(r.rows, PATH_DIRECT)}\n")
        append("Name lookups (Android's resolver):")
        if (r.names.isEmpty()) append(" none run")
        for (n in r.names) append("\n  ${n.name}: ${if (n.resolved) "✓ ${n.detail}" else "✗ FAILED (${n.detail})"}")
        for ((t, rows) in r.rows.groupBy { it.target }) {
            append("\n\n${t.label} ${t.ip}")
            for (x in rows) {
                append("\n  ${x.check.label} :${x.check.port}${if (x.check.tls) " (${t.tlsName})" else ""} · path: ${pathLabel(x.path, r.vpnUp, r.directNet)}")
                append("\n    reachable: ${if (x.reachable) "yes" else "NO"} · latency: ${x.latencyMs} ms · carried by: ${x.carriedBy.ifEmpty { "nothing (no connection)" }}")
                if (x.error.isNotEmpty()) append("\n    error: ${x.error}")
            }
        }
    }
}

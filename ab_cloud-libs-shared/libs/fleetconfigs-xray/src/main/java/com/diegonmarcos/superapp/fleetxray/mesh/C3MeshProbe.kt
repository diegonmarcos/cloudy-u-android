package com.diegonmarcos.superapp.fleetxray.mesh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * #1797 Cloud C3's mesh profiler. Owner: Cloud C3. This module (libs:fleetconfigs-xray) is C3's X-Ray
 * engine, compiled by Cloud C3 and by the SuperApp. The probes live here. The SuperApp's Cloud Mesh >
 * Status page only renders [Report]: the per-mesh table ([Row], one per declared mesh) and the profiler
 * steps ([Step]). Nothing here draws.
 *
 * Every cell is a real probe through [Io]. Nothing is inferred from another reading, and a cell that
 * cannot be measured right now says why (ok = null) instead of borrowing a neighbour's verdict. The probes:
 *  - DNS per resolver: one plain A query of each mesh's declared name to EVERY resolver Android holds.
 *    A public resolver answering NXDOMAIN for a mesh name is the #1797 failure, shown as it happens.
 *  - DNS through Android: the OS resolver (InetAddress), the path every app takes. It is never bypassed;
 *    it is measured.
 *  - which resolver answered: the OS answer matched against the direct answers.
 *  - TCP to each hub's resolver port over IPv4 and IPv6, and HTTPS (TCP + TLS + any HTTP answer < 500)
 *    to each mesh's declared service by name.
 *  - allowed-IPs / route: each mesh's IPv4 and IPv6 subnet is covered by the allowed IPs of the peer
 *    that carries its hub.
 *  - handshake age per peer, against the fresh threshold.
 *  - MTU: DNS queries padded (EDNS0 padding, RFC 7830) to each declared IP packet size, sent to the
 *    first hub's resolver through the tunnel. The largest size that gets an answer is reported.
 */
object C3MeshProbe {

    /** One declared mesh (`ui.mesh_page.meshes[]`). */
    data class Target(
        val id: String,
        val label: String,
        val cidr: String,
        val cidr6: String,
        val hub: String,
        val hub6: String,
        val resolver: String,
        /** A private name this mesh's resolver must answer. */
        val name: String,
        /** An HTTPS service on this mesh, by name; blank = the hub's resolver port is the mesh's service. */
        val serviceUrl: String,
    )

    data class Peer(val name: String, val publicKey: String, val endpoint: String, val allowedIps: String, val handshakeAgeS: Long?)

    data class Input(
        val tunnelUp: Boolean,
        val peers: List<Peer>,
        /** Every DNS server Android holds right now, in the order it reports them. */
        val resolvers: List<String>,
        /** The fleet's own resolvers (the profile's DNS field). */
        val fleetResolvers: List<String>,
        val interfaceMtu: String,
        val freshS: Long,
        val targets: List<Target>,
        /** IP packet sizes the MTU probe tries, ascending. */
        val mtuSizes: List<Int>,
        val timeoutMs: Int = 2000,
        /** The private names Status > Reachability probes; each also gets TCP :443 here. */
        val names: List<String> = emptyList(),
        /** #1798 The rung carrying the tunnel (direct | pinned | doh | relay | none; "" unknown) and its detail. */
        val path: String = "",
        val pathDetail: String = "",
    )

    /** One profiler step. [ok] null = not testable right now ([detail] says why). */
    data class Step(val group: String, val id: String, val ok: Boolean?, val ms: Long, val detail: String)

    data class Cell(val ok: Boolean?, val text: String, val detail: String)

    data class Row(val target: Target, val cells: Map<String, Cell>, val atMs: Long) {
        fun cell(id: String): Cell = cells[id] ?: Cell(null, "-", "not measured")
    }

    data class Report(val atMs: Long, val input: Input, val rows: List<Row>, val steps: List<Step>, val mtuLargest: Int?)

    /** The per-mesh table's columns, in order. [OVERALL] is green only when every measured cell is. */
    const val OVERALL = "overall"
    val COLUMNS = listOf(OVERALL, "inside", "path", "route", "resolver", "system", "answered_by", "hub", "service", "ipv6", "latency", "last")
    /** The groups of [Report.steps], in the order the profiler shows them. */
    val GROUPS = listOf("dns", "system", "tcp", "https", "route", "handshake", "mtu")

    /** The network, injectable so every verdict is asserted on the JVM. */
    interface Io {
        /** One plain-DNS A query: the first A record, "NXDOMAIN", "rcode N" or "no A record"; throws on timeout or no route. */
        fun query(server: String, name: String, timeoutMs: Int, padTo: Int = 0): String
        /** Android's own resolver: what every app gets. */
        fun systemResolve(name: String): List<String>
        fun tcp(host: String, port: Int, timeoutMs: Int)
        /**
         * #1900 [tcp] over the path [via] ([VIA_TUNNEL] bound to the VPN network, [VIA_DEFAULT] the OS route, [VIA_DIRECT]
         * bound outside the VPN); returns the local address the connected socket used ("" when the Io cannot say), so a
         * row can name the interface that really carried it.
         */
        fun tcpFrom(host: String, port: Int, timeoutMs: Int, via: String): String { tcp(host, port, timeoutMs); return "" }
        /** The HTTP status code of a GET of [url]. */
        fun https(url: String, timeoutMs: Int): Int
        /** #1900 [https] over the path [via]. */
        fun httpsVia(url: String, timeoutMs: Int, via: String): Int = https(url, timeoutMs)
    }

    const val VIA_TUNNEL = "tunnel"
    const val VIA_DEFAULT = "default"
    const val VIA_DIRECT = "direct"

    /**
     * #1900 Puts a probe's socket on a chosen network BEFORE it connects. The tunnel's probes were left unbound and Android
     * sourced them from the Wi-Fi address (10.0.0.1:53 and 10.1.0.1:53 left from 192.168.x.y) and sent every IPv6 probe to the
     * Wi-Fi network, which has no IPv6 route to fd0c:1d00::/64 (ENETUNREACH). Bound to the VPN network they use the tunnel.
     * Throws [java.io.IOException] with the reason when the network does not exist (the tunnel is down).
     */
    interface SocketBinder {
        fun bind(s: Socket, via: String)
        fun bind(s: DatagramSocket, via: String)
        /** A connection to [url] on [via], or null to use the platform default. */
        fun open(url: URL, via: String): java.net.URLConnection? = null
    }

    fun run(input: Input, io: Io = SystemIo, nowMs: () -> Long = System::currentTimeMillis): Report {
        val steps = mutableListOf<Step>()
        val at = nowMs()
        fun timed(group: String, id: String, body: () -> String): Step {
            val t0 = nowMs()
            val st = try { Step(group, id, true, nowMs() - t0, body()).let { it.copy(ms = nowMs() - t0) } } catch (t: Throwable) {
                Step(group, id, false, nowMs() - t0, t.message ?: t.javaClass.simpleName)
            }
            steps += st
            return st
        }
        fun skip(group: String, id: String, why: String): Step = Step(group, id, null, 0, why).also { steps += it }

        // ── DNS per resolver: every server Android holds × every mesh's name ──
        val direct = mutableMapOf<Pair<String, String>, String>()
        val resolvers = (input.resolvers + input.fleetResolvers + input.targets.map { it.resolver }).filter { it.isNotBlank() }.distinct()
        for (t in input.targets) for (r in resolvers) {
            timed("dns", "$r ${t.name}") {
                val a = io.query(r, t.name, input.timeoutMs)
                direct[r to t.name] = a
                check(isV4(a)) { "$a${if (r !in input.fleetResolvers) " (not a fleet resolver: it has no mesh records)" else ""}" }
                a
            }
        }

        val rows = input.targets.map { t ->
            val cells = linkedMapOf<String, Cell>()
            val peer = input.peers.firstOrNull { p -> cidrs(p.allowedIps).any { contains(it, t.hub) } }

            // inside the mesh: the tunnel is up and the peer carrying this mesh's hub has a fresh handshake
            cells["inside"] = when {
                !input.tunnelUp -> Cell(false, "down", "the tunnel is down")
                peer == null -> Cell(false, "no peer", "no peer's allowed IPs hold the hub ${t.hub}")
                peer.handshakeAgeS == null -> Cell(false, "no handshake", "${peer.name}: no handshake yet")
                peer.handshakeAgeS > input.freshS -> Cell(false, "stale", "${peer.name}: handshake ${age(peer.handshakeAgeS)} ago (fresh is ≤ ${age(input.freshS)})")
                else -> Cell(true, age(peer.handshakeAgeS), "${peer.name} ${peer.endpoint}: handshake ${age(peer.handshakeAgeS)} ago")
            }

            // #1798 path: which rung carries the tunnel to this mesh's hub (the engine's plan; switches are in the Log)
            cells["path"] = when (input.path) {
                "" -> Cell(null, "unknown", "no connect recorded the path yet")
                "none" -> Cell(false, "none", "no rung handshook: ${input.pathDetail}")
                else -> Cell(true, when (input.path) { "direct" -> "UDP"; "relay" -> "relay"; else -> input.path },
                    "the tunnel's path to ${peer?.name ?: t.hub}: ${input.path}" + if (input.pathDetail.isNotBlank()) " · ${input.pathDetail}" else "")
            }

            // route: this mesh's subnets sit inside that peer's allowed IPs
            val routeStep = if (peer == null) Step("route", t.label, false, 0, "no peer carries ${t.hub}").also { steps += it } else {
                val nets = listOf(t.cidr, t.cidr6).filter { it.isNotBlank() }
                val missing = nets.filter { n -> cidrs(peer.allowedIps).none { covers(it, n) } }
                Step("route", t.label, missing.isEmpty(), 0,
                    if (missing.isEmpty()) "${nets.joinToString(", ")} ⊂ ${peer.name} allowed ${peer.allowedIps}"
                    else "${missing.joinToString(", ")} not in ${peer.name} allowed ${peer.allowedIps}").also { steps += it }
            }
            cells["route"] = Cell(routeStep.ok, if (routeStep.ok == true) "covered" else "gap", routeStep.detail)

            // the mesh's own resolver answers its private name (the direct query above)
            val own = direct[t.resolver to t.name]
            val ownStep = steps.lastOrNull { it.group == "dns" && it.id == "${t.resolver} ${t.name}" }
            cells["resolver"] = Cell(ownStep?.ok, own ?: "no answer", "${t.resolver} → ${t.name}: ${ownStep?.detail ?: "not asked"} (${ownStep?.ms ?: 0} ms)")

            // Android's resolver: the path every app takes
            var sys: List<String> = emptyList()
            val sysStep = timed("system", t.name) {
                sys = io.systemResolve(t.name)
                check(sys.isNotEmpty()) { "no address" }
                sys.joinToString(", ")
            }
            cells["system"] = Cell(sysStep.ok, if (sysStep.ok == true) sys.first() else "fails", "${t.name}: ${sysStep.detail} (${sysStep.ms} ms)")

            // which resolver answered: Android's answer matched against the direct ones
            val same = resolvers.filter { r -> direct[r to t.name]?.let { it in sys } == true }
            val nx = resolvers.filter { r -> direct[r to t.name] == "NXDOMAIN" }
            cells["answered_by"] = when {
                sysStep.ok == true && same.any { it in input.fleetResolvers } -> Cell(true, same.first { it in input.fleetResolvers }, "Android's answer ${sys.first()} matches fleet resolver(s) ${same.filter { it in input.fleetResolvers }.joinToString(", ")}")
                sysStep.ok == true && same.isNotEmpty() -> Cell(false, same.first(), "Android's answer matches ${same.joinToString(", ")}, not a fleet resolver")
                sysStep.ok == true -> Cell(null, "unmatched", "Android answered ${sys.joinToString(", ")}; no direct answer matches (cached or another server)")
                nx.isNotEmpty() -> Cell(false, nx.first(), "Android failed (${sysStep.detail}); these resolvers answer NXDOMAIN for it: ${nx.joinToString(", ")}")
                else -> Cell(false, "none", "Android failed (${sysStep.detail}) and no resolver it holds answered")
            }

            // private IPs answer: the hub's resolver port over IPv4 and IPv6, and the declared service
            val hub = timed("tcp", "${t.hub}:53") { io.tcp(t.hub, 53, input.timeoutMs); "${t.hub}:53 open" }
            cells["hub"] = Cell(hub.ok, if (hub.ok == true) "${hub.ms} ms" else "no", "TCP ${t.hub}:53: ${hub.detail} (${hub.ms} ms)")
            val v6 = if (t.hub6.isBlank()) skip("tcp", "[${t.label}]:53", "no IPv6 hub declared") else
                timed("tcp", "[${t.hub6}]:53") { io.tcp(t.hub6, 53, input.timeoutMs); "[${t.hub6}]:53 open" }
            cells["ipv6"] = Cell(v6.ok, when (v6.ok) { true -> "${v6.ms} ms"; false -> "no"; null -> "n/a" }, "TCP [${t.hub6}]:53: ${v6.detail} (${v6.ms} ms)")
            val svc = if (t.serviceUrl.isBlank()) Step("https", t.label, hub.ok, hub.ms, "the hub's resolver port is this mesh's service: ${hub.detail}").also { steps += it }
            else timed("https", t.serviceUrl) {
                val code = io.https(t.serviceUrl, input.timeoutMs)
                check(code in 100..499) { "HTTP $code" }
                "HTTP $code"
            }
            cells["service"] = Cell(svc.ok, if (svc.ok != true) "no" else if (t.serviceUrl.isBlank()) "${svc.ms} ms :53" else svc.detail, "${t.serviceUrl.ifBlank { "${t.hub}:53" }}: ${svc.detail} (${svc.ms} ms)")
            cells["latency"] = Cell(if (hub.ok == true) true else null, if (hub.ok == true) "${hub.ms} ms" else "-", "TCP connect to ${t.hub}:53 through the tunnel")
            cells["last"] = Cell(null, clock(at), "probed at epoch ms $at")
            val measured = cells.filterKeys { it != "last" && it != "latency" }.values.mapNotNull { it.ok }
            val bad = cells.filter { (k, v) -> k != "last" && v.ok == false }.keys
            cells[OVERALL] = Cell(measured.isNotEmpty() && measured.all { it }, if (bad.isEmpty() && measured.isNotEmpty()) "OK" else "FAIL",
                if (bad.isEmpty()) "every measured step passes" else "failing: ${bad.joinToString(", ")}")
            Row(t, COLUMNS.associateWith { cells[it] ?: Cell(null, "-", "not measured") }, at)
        }

        // TCP :443 to each Reachability name by Android's answer (TCP/HTTPS per private name)
        for (n in input.names) {
            val a = runCatching { io.systemResolve(n) }.getOrDefault(emptyList())
            if (a.isEmpty()) { skip("tcp", "$n:443", "no address from Android for $n") }
            else { timed("tcp", "$n:443") { io.tcp(a.first(), 443, input.timeoutMs); "${a.first()}:443 open" } }
        }

        // handshake age per peer
        for (p in input.peers) steps += Step("handshake", p.name.ifBlank { p.publicKey.take(8) }, when {
            !input.tunnelUp -> null
            p.handshakeAgeS == null -> false
            else -> p.handshakeAgeS <= input.freshS
        }, 0, if (!input.tunnelUp) "tunnel down" else if (p.handshakeAgeS == null) "no handshake yet · ${p.endpoint}" else "${age(p.handshakeAgeS)} ago · ${p.endpoint}")

        // MTU: padded DNS queries to the first hub's resolver, through the tunnel
        var largest: Int? = null
        val first = input.targets.firstOrNull()
        if (first == null) {
            skip("mtu", "path", "no mesh declared")
        } else {
            for (size in input.mtuSizes.sorted()) {
                val st = timed("mtu", "$size B") {
                    val a = io.query(first.resolver, first.name, input.timeoutMs, padTo = size - IP_UDP_OVERHEAD)
                    "a $size-byte packet reached ${first.resolver} and was answered ($a)"
                }
                if (st.ok == true) largest = size
            }
        }
        if (first != null) steps += Step("mtu", "largest", largest != null, 0,
            (largest?.let { "largest answered: $it B" } ?: "no padded query was answered") + " · interface MTU ${input.interfaceMtu.ifBlank { "default" }}" +
                " (a size above the interface MTU is fragmented by the kernel; a FAIL there means the path drops fragments)")
        return Report(at, input, rows, steps, largest)
    }

    /** The whole profile as text: Export / Copy. Public data only (no key but public keys). */
    fun text(r: Report): String = buildString {
        appendLine("Cloud C3 mesh profile @ ${clock(r.atMs)} (epoch ms ${r.atMs})")
        appendLine("tunnel ${if (r.input.tunnelUp) "UP" else "DOWN"} · Android DNS ${r.input.resolvers.joinToString(", ").ifEmpty { "none" }} · fleet ${r.input.fleetResolvers.joinToString(", ")}")
        appendLine("== Meshes (${COLUMNS.joinToString(" | ")})")
        for (row in r.rows) {
            appendLine("${row.target.label} ${row.target.id} ${row.target.cidr} ${row.target.cidr6}: " + COLUMNS.joinToString(" | ") { "${it}=${row.cell(it).text}" })
            for (c in COLUMNS) appendLine("  ${c.padEnd(12)} ${verdict(row.cell(c).ok)} ${row.cell(c).detail}")
        }
        for (g in GROUPS) {
            val gs = r.steps.filter { it.group == g }
            if (gs.isEmpty()) continue
            appendLine("== ${g}")
            for (s in gs) appendLine("  ${verdict(s.ok)} ${s.id} · ${s.ms} ms · ${s.detail}")
        }
    }.trimEnd()

    fun verdict(ok: Boolean?): String = when (ok) { true -> "PASS"; false -> "FAIL"; null -> "n/a " }

    // ── addresses ──

    const val IP_UDP_OVERHEAD = 28

    fun isV4(s: String): Boolean = s.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

    fun cidrs(csv: String): List<String> = csv.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun literal(s: String): ByteArray? {
        val h = s.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || !(isV4(h) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' }))) return null
        return runCatching { InetAddress.getByName(h).address }.getOrNull()
    }

    private fun net(cidr: String): Pair<BigInteger, Int>? {
        val a = literal(cidr.substringBefore('/')) ?: return null
        val bits = a.size * 8
        val len = cidr.substringAfter('/', bits.toString()).toIntOrNull()?.coerceIn(0, bits) ?: return null
        val v = BigInteger(1, a)
        val mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE).xor(BigInteger.ONE.shiftLeft(bits - len).subtract(BigInteger.ONE))
        return v.and(mask) to len
    }

    /** True when address [ip] lies inside [cidr] (same family). */
    fun contains(cidr: String, ip: String): Boolean {
        val a = literal(ip) ?: return false
        val n = net(cidr) ?: return false
        if ((literal(cidr.substringBefore('/'))?.size ?: 0) != a.size) return false
        val bits = a.size * 8
        val v = BigInteger(1, a).shiftRight(bits - n.second)
        return v == n.first.shiftRight(bits - n.second)
    }

    /** True when [outer] holds every address of [inner]. */
    fun covers(outer: String, inner: String): Boolean {
        val o = net(outer) ?: return false
        val i = net(inner) ?: return false
        if (literal(outer.substringBefore('/'))?.size != literal(inner.substringBefore('/'))?.size) return false
        return i.second >= o.second && contains(outer, inner.substringBefore('/'))
    }

    fun age(s: Long?): String = when {
        s == null -> "-"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${(s % 60).toString().padStart(2, '0')}s"
        else -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}m"
    }

    fun clock(epochMs: Long): String {
        val s = Math.floorMod((epochMs + java.util.TimeZone.getDefault().getOffset(epochMs)) / 1000, 86_400L)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ── the wire ──

    /** A plain-DNS A query, optionally padded with an EDNS0 Padding option to [padTo] bytes of DNS message. */
    fun buildQuery(id: Int, name: String, padTo: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        val edns = padTo > 0
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, (if (edns) 1 else 0).toByte()))
        for (label in name.trimEnd('.').split('.')) { val b = label.toByteArray(); out.write(b.size); out.write(b) }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        val questionEnd = out.size()
        if (edns) {
            val fixed = questionEnd + 11 + 4 // OPT RR + one option header
            val pad = (padTo - fixed).coerceAtLeast(0)
            val rdlen = 4 + pad
            out.write(byteArrayOf(0, 0, 41, (4096 shr 8).toByte(), (4096 and 0xFF).toByte(), 0, 0, 0, 0, (rdlen shr 8).toByte(), rdlen.toByte()))
            out.write(byteArrayOf(0, 12, (pad shr 8).toByte(), pad.toByte()))
            out.write(ByteArray(pad))
        }
        return out.toByteArray() to questionEnd
    }

    fun parseAnswer(b: ByteArray, len: Int, id: Int, questionEnd: Int): String {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        check(len >= 12 && u16(0) == id) { "reply does not match the query" }
        val rcode = b[3].toInt() and 0x0F
        if (rcode != 0) return if (rcode == 3) "NXDOMAIN" else "rcode $rcode"
        var i = questionEnd
        repeat(u16(6)) {
            while (true) {
                val l = b[i].toInt() and 0xFF
                if (l == 0) { i++; break }
                if ((l and 0xC0) == 0xC0) { i += 2; break }
                i += l + 1
            }
            val type = u16(i); val rdlen = u16(i + 8); val rd = i + 10
            if (type == 1 && rdlen == 4) return (0..3).joinToString(".") { (b[rd + it].toInt() and 0xFF).toString() }
            i = rd + rdlen
        }
        return "no A record"
    }

    /**
     * #1845 the phone's own address in the SAME /24 as [dest], or null. The tunnel carries two IPv4 identities (10.0.0.x on Mesh-1, 10.1.0.x on Mesh-2) and
     * Android sources an unbound socket from the FIRST, so a Mesh-2 probe left unbound left as 10.0.0.x and the Mesh-2 hub dropped it (cryptokey routing):
     * "10.1.0.1:53 timed out from /10.0.0.10" while the hub was fine. A probe is bound to its own mesh's address.
     */
    fun sourceInMesh(dest: String, locals: List<String>): String? {
        val d = dest.split('.'); if (d.size != 4) return null
        return locals.firstOrNull { l -> l != dest && l.split('.').let { it.size == 4 && it[0] == d[0] && it[1] == d[1] && it[2] == d[2] } }
    }

    private fun localV4(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { i -> i.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress } }
    }.getOrDefault(emptyList())

    object SystemIo : Io {
        override fun query(server: String, name: String, timeoutMs: Int, padTo: Int): String {
            val id = (System.nanoTime() and 0xFFFF).toInt()
            val (q, qEnd) = buildQuery(id, name, padTo)
            val b = binder
            val from = if (b != null) null else sourceInMesh(InetAddress.getByName(server).hostAddress.orEmpty(), localV4())
            (if (from != null) DatagramSocket(InetSocketAddress(InetAddress.getByName(from), 0)) else DatagramSocket()).use { s ->
                b?.bind(s, VIA_TUNNEL)
                s.soTimeout = timeoutMs
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                return parseAnswer(buf, pkt.length, id, qEnd)
            }
        }
        override fun systemResolve(name: String): List<String> = InetAddress.getAllByName(name).mapNotNull { it.hostAddress }
        /** #1900 Set once by the app (network/TunnelBinder.kt); null on the JVM, where the legacy same-/24 source bind stays. */
        @Volatile var binder: SocketBinder? = null
        override fun tcp(host: String, port: Int, timeoutMs: Int) { tcpFrom(host, port, timeoutMs, VIA_TUNNEL) }
        override fun tcpFrom(host: String, port: Int, timeoutMs: Int, via: String): String {
            val dest = InetAddress.getByName(host)
            Socket().use { s ->
                bindTcp(s, dest, via)
                s.connect(InetSocketAddress(dest, port), timeoutMs)
                return s.localAddress?.hostAddress?.substringBefore('%').orEmpty()
            }
        }
        /** The one place a TCP probe's socket takes its network: the binder when installed, else the same-/24 source address. */
        fun bindTcp(s: Socket, dest: InetAddress, via: String) {
            val b = binder
            if (b != null) { if (via != VIA_DEFAULT) b.bind(s, via); return }
            val from = if (via == VIA_TUNNEL) sourceInMesh(dest.hostAddress.orEmpty(), localV4()) else null
            if (from != null) s.bind(InetSocketAddress(InetAddress.getByName(from), 0))
        }
        override fun httpsVia(url: String, timeoutMs: Int, via: String): Int {
            val u = URL(url)
            val c = (binder?.takeIf { via != VIA_DEFAULT }?.open(u, via) ?: u.openConnection()) as HttpURLConnection
            return try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.responseCode
            } finally { c.disconnect() }
        }
        override fun https(url: String, timeoutMs: Int): Int = httpsVia(url, timeoutMs, VIA_TUNNEL)
    }
}

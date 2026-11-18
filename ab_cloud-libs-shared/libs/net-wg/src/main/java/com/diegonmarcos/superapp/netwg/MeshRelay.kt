package com.diegonmarcos.superapp.netwg

import com.diegonmarcos.superapp.net.RelayProbe
import com.diegonmarcos.superapp.net.RelaySpec
import com.diegonmarcos.superapp.net.RelayStatus
import java.io.BufferedInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The Cloud Mesh's TCP/443 fallback, engine side. Public Wi-Fi that drops UDP (or the mesh's ports)
 * never lets a WireGuard handshake through; this carries the same datagrams over a TLS connection to
 * port 443, which such networks pass because it is what HTTPS looks like.
 *
 * For each [RelaySpec.Route] it listens on UDP 127.0.0.1:listen (the app points that hub's peer
 * endpoint there) and, on the first datagram, opens TCP to one of [RelaySpec.addrs] on 443, does a
 * real TLS handshake with SNI = the relay's host name and the certificate checked against that name
 * (so a captive portal or a hijacked address fails here, not later), and upgrades to a WebSocket
 * tunnel ([WsTunnel]) to the route's remote. A failed connection is dropped and re-dialled by the
 * next datagram; nothing is retried in a loop, so a relay that is down costs nothing but the
 * datagrams WireGuard keeps sending anyway.
 *
 * [protect] is VpnService.protect (GoBackend.protectSocket): without it a full-tunnel profile would
 * route the relay's own connection into the tunnel it carries.
 *
 * Pure JVM, so MeshRelayTest drives it against a local server.
 */
class MeshRelay(
    private val protect: (Socket) -> Boolean,
    private val verifyHost: (String, SSLSession) -> Boolean = { _, _ -> true },
    private val tlsFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val clock: () -> Long = System::currentTimeMillis,
    /** #1798 Whether this network routes an address's family; a test injects one (no IPv6 route on a v4-only Wi-Fi). */
    private val hasRoute: (String) -> Boolean = HappyEyeballs::systemRoute,
) {
    private var spec: RelaySpec? = null
    private val legs = ArrayList<Leg>()

    /** Run [next] (null stops). The same spec again is a no-op that answers the status. */
    @Synchronized fun apply(next: RelaySpec?): String {
        if (next != null && next == spec && legs.none { it.closed }) return status()
        stop()
        if (next != null) {
            spec = next
            for (r in next.routes) legs += runCatching { Leg(this, next, r) }.getOrElse { Leg.failed(this, next, r, it) }
        }
        return status()
    }

    @Synchronized fun stop() {
        legs.forEach { it.close() }
        legs.clear()
        spec = null
    }

    @Synchronized fun status(): String {
        val s = spec ?: return RelayStatus("off", "", emptyList()).toJson()
        val l = legs.map { RelayStatus.Leg(it.route.listen, it.route.remote, it.up, it.via, it.rx, it.tx, it.error) }
        val state = when {
            l.any { it.up } -> "up"
            l.any { it.error.isNotBlank() } -> "error"
            else -> "idle"
        }
        return RelayStatus(state, s.host, l).toJson()
    }

    /**
     * TCP, TLS and the upgrade against the first address of [s] that takes them, without carrying anything.
     * #1798 The addresses race (Happy Eyeballs, [HappyEyeballs]); [RelayProbe.attempts] lists every one tried or skipped.
     */
    fun probe(s: RelaySpec): String {
        val remote = s.routes.firstOrNull()?.remote ?: return RelayProbe("", "", 0, 0, "the relay spec names no route").toJson()
        val t0 = clock()
        return try {
            val (sock, a, log) = open(s, remote)
            val tls = (sock as? SSLSocket)?.session?.protocol ?: "none"
            runCatching { sock.close() }
            RelayProbe(a, tls, 101, clock() - t0, "", HappyEyeballs.text(log)).toJson()
        } catch (e: Opened) {
            RelayProbe(e.addr, e.tls, e.code, clock() - t0, e.message.orEmpty(), HappyEyeballs.text(e.log)).toJson()
        }
    }

    /** Why [open] gave up: the address and TLS it reached (if any), the server's refusal code (if any) and every attempt. */
    private class Opened(msg: String, val addr: String, val tls: String, val code: Int, val log: List<HappyEyeballs.Attempt>) : IOException(msg)

    /**
     * #1798 A socket through which [remote] is tunnelled: race TCP over the routable addresses, then TLS and the upgrade on
     * the winner; an address whose TLS or upgrade fails is dropped and the rest race again. A server refusal (4xx: the key
     * or the route) ends it at once, since no other address changes that.
     */
    private fun open(s: RelaySpec, remote: String): Triple<Socket, String, List<HappyEyeballs.Attempt>> {
        val (order, skipped) = HappyEyeballs.plan(s.addrs, hasRoute)
        val log = ArrayList(skipped)
        val left = order.toMutableList()
        var lastAddr = ""; var lastTls = ""
        while (left.isNotEmpty()) {
            val (won, tried) = HappyEyeballs.race(left, { a -> tcp(s, a) }, clock = clock)
            log += tried
            if (won == null) break
            val (raw, a) = won
            left.removeAll(tried.filter { it.ok == false && !it.detail.startsWith("abandoned") }.map { it.addr }.toSet() + a)
            lastAddr = a
            try {
                val sock = secure(s, raw, a)
                lastTls = (sock as? SSLSocket)?.session?.protocol ?: "none"
                try { WsTunnel.upgrade(sock, s.host, s.prefix, remote) }
                catch (e: WsTunnel.Refused) { runCatching { sock.close() }; throw Opened(why(e), a, lastTls, e.code, log + HappyEyeballs.Attempt(a, false, 0, "relay refused: ${why(e)}")) }
                catch (e: Exception) { runCatching { sock.close() }; throw e }
                return Triple(sock, a, log)
            } catch (e: Opened) { throw e } catch (e: Exception) {
                log += HappyEyeballs.Attempt(a, false, 0, "TCP ok, then ${why(e)}")
            }
        }
        val summary = if (log.isEmpty()) "no address for ${s.host}" else log.joinToString("; ") { it.line() }
        throw Opened(summary, lastAddr, lastTls, 0, log)
    }

    /** TCP to [addr] (protected, by address: no DNS), bounded by [ATTEMPT_MS]. */
    private fun tcp(s: RelaySpec, addr: String): Socket {
        val target = InetAddress.getByName(addr)
        val raw = Socket()
        try {
            // A bare Socket() has no file descriptor until it is bound, and protect() marks that fd:
            // bind first (wildcard of the target's family, ephemeral port), then protect, then connect.
            raw.bind(InetSocketAddress(InetAddress.getByName(if (target is Inet6Address) "::" else "0.0.0.0"), 0))
            runCatching { protect(raw) }
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(target, s.port), ATTEMPT_MS)
            raw.soTimeout = CONNECT_MS
            return raw
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /** Unless [RelaySpec.tls] is off, a verified TLS session over [raw]. */
    private fun secure(s: RelaySpec, raw: Socket, addr: String): Socket {
        if (!s.tls) return raw
        try {
            val ssl = tlsFactory().createSocket(raw, s.host, s.port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(s.host))
                endpointIdentificationAlgorithm = "HTTPS"
                // HTTP/1.1, as a browser's WebSocket asks for (API 29+; older Androids send no ALPN).
                runCatching { applicationProtocols = arrayOf("http/1.1") }
            }
            ssl.startHandshake()
            if (!verifyHost(s.host, ssl.session)) throw SSLPeerUnverifiedException("the certificate at $addr is not valid for ${s.host}")
            return ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /** A tunnelled socket to [remote] through the first address that completes the upgrade, and that address. */
    private fun dial(s: RelaySpec, remote: String): Pair<Socket, String> = try {
        open(s, remote).let { it.first to it.second }
    } catch (e: Opened) { throw IOException(e.message, e) }

    /** One route: the loopback UDP socket WireGuard sends to, and the tunnel that carries it. */
    private class Leg private constructor(val relay: MeshRelay, val spec: RelaySpec, val route: RelaySpec.Route, val udp: DatagramSocket?) {
        constructor(relay: MeshRelay, spec: RelaySpec, route: RelaySpec.Route) : this(relay, spec, route,
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), route.listen)) })

        @Volatile var ws: Socket? = null
        @Volatile var peer: SocketAddress? = null
        @Volatile var via = ""
        @Volatile var error = ""
        @Volatile var rx = 0L
        @Volatile var tx = 0L
        @Volatile var closed = false
        private var lastDial = 0L
        val up: Boolean get() = ws != null

        /** Owns [udp]'s receive; joined on [close] (see there). */
        private val pump: Thread? = udp?.let { Thread({ pumpOut() }, "mesh-relay-udp-${route.listen}").apply { isDaemon = true; start() } }

        /** WireGuard -> relay. */
        private fun pumpOut() {
            val sock = udp ?: return
            val buf = ByteArray(65535)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                try { p.setData(buf); sock.receive(p) } catch (e: IOException) { if (closed) return else continue }
                peer = p.socketAddress
                val out = tunnel() ?: continue
                // Count before the write: the echo can be back (and status() read) before a count made after it.
                tx += p.length
                try {
                    synchronized(out) { WsTunnel.writeFrame(out.getOutputStream(), WsTunnel.OP_BINARY, buf, 0, p.length) }
                } catch (e: IOException) { tx -= p.length; drop(out, "send: ${relay.why(e)}") }
            }
        }

        /** The open tunnel, or a fresh one; null while a dial failed less than [RETRY_MS] ago. */
        @Synchronized private fun tunnel(): Socket? {
            ws?.let { return it }
            val now = relay.clock()
            if (now - lastDial < RETRY_MS) return null
            lastDial = now
            return try {
                val (sock, addr) = relay.dial(spec, route.remote)
                sock.soTimeout = IDLE_MS
                ws = sock; via = addr; error = ""
                Thread({ pumpIn(sock) }, "mesh-relay-ws-${route.listen}").apply { isDaemon = true }.start()
                sock
            } catch (e: Exception) { error = relay.why(e); null }
        }

        /** Relay -> WireGuard. */
        private fun pumpIn(sock: Socket) {
            try {
                val i = BufferedInputStream(sock.getInputStream())
                while (!closed) {
                    val m = WsTunnel.readMessage(i) { ping -> synchronized(sock) { WsTunnel.writeFrame(sock.getOutputStream(), WsTunnel.OP_PONG, ping) } }
                    val to = peer ?: continue
                    rx += m.size
                    udp?.send(DatagramPacket(m, m.size, to))
                }
            } catch (e: Exception) { if (!closed) drop(sock, "receive: ${relay.why(e)}") }
        }

        @Synchronized private fun drop(sock: Socket, why: String) {
            if (ws === sock) { ws = null; error = why }
            runCatching { sock.close() }
        }

        fun close() {
            closed = true
            runCatching { udp?.close() }
            ws?.let { runCatching { it.close() } }
            ws = null
            // DatagramSocket is NIO-backed (JDK 13+): closing it while pumpOut is blocked in receive() defers the
            // real descriptor close until that thread leaves receive, so the port would still be bound when
            // stop() returns. Waiting for the pump makes "stopped" mean "the port is free".
            pump?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MS)
        }

        companion object {
            const val JOIN_MS = 2_000L
            /** A route whose loopback port could not be bound: listed with the reason, carrying nothing. */
            fun failed(relay: MeshRelay, spec: RelaySpec, route: RelaySpec.Route, t: Throwable) =
                Leg(relay, spec, route, null).apply { error = "cannot listen on $LOOPBACK:${route.listen}: ${relay.why(t)}"; closed = true }
        }
    }

    private fun why(t: Throwable): String = (t.message ?: t.javaClass.simpleName).replace(Regex("\\s+"), " ").take(200)

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_MS = 8_000
        /** #1798 One TCP attempt of the race: short, so a dead family never holds the relay for 8 s. */
        const val ATTEMPT_MS = 3_000
        /** No frame (data or the server's ping) for this long: the connection is dead; the next datagram re-dials. */
        const val IDLE_MS = 120_000
        const val RETRY_MS = 2_000L
    }
}

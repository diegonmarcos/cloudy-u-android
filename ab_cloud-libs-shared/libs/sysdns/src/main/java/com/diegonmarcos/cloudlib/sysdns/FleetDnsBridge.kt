package com.diegonmarcos.cloudlib.sysdns

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * #874 ONE RESOLVER PER FLEET PROCESS, AND THE PRESET DECIDES WHERE IT ASKS.
 *
 * [SystemDnsBridge] answered a terminal's shell from Android's resolver and
 * nothing else; an app's own Kotlin code resolved beside it with
 * InetAddress, so the Store's downloader and the DNS page's Test could
 * disagree about the same name on the same phone. Now every lookup of the
 * process — a shell's query on 127.0.0.1:<bridge_port>, a bundled binary's,
 * and the app's own through [resolve] — goes through the one bridge, whose
 * upstream walks [routes]: the host's reading of the DNS preset for that
 * name. A route is either Android's resolver on a [Route.network] (Mirror
 * Android: each underlying, non-VPN network, then the uid's default) or a
 * plain-DNS server the preset names (Public → its list, Private → the fleet
 * resolver and the ordered fallbacks). The first NOERROR/NXDOMAIN answer
 * wins; a total miss names every route tried in [lastFailure], and the last
 * answering route is [lastRoute] — what the DNS page and /api/net/dns show.
 *
 * #875 MEASURED 2026-10-06, mobile data, one "Install all": cloud-calc and
 * c3-morpheus downloaded while c3-watchdog and c3-watchtower failed every
 * rung on "cannot resolve github.com" — the same resolver, the same minute.
 * N rungs × N apps asked at once, each lookup a single UDP shot with one
 * timeout and no retry. So: a route is asked [TRIES_PER_ROUTE] times with a
 * backoff before the next route; at most [MAX_IN_FLIGHT] upstream queries
 * run at once; a positive answer is cached for its TTL, at least
 * [MIN_TTL_MS], so one github.com answer serves the whole batch; and the
 * failure says how many tries it took.
 *
 * Nothing here names a server: [routes] is the host's; the default plan is
 * Mirror, for an app without a DNS page (Cloud Store).
 */
object FleetDnsBridge {
    /** One way to an answer: [servers] empty = Android's resolver on [network] (null = the uid's default). */
    class Route(val label: String, val servers: List<String> = emptyList(), val network: Network? = null,
                /** #1924 true = the servers are the fleet (mesh) resolver: every answer or miss is reported to [meshObserver]. */
                val mesh: Boolean = false)

    /** #1924 Called with false for a mesh route that gave no definitive answer (SERVFAIL, REFUSED, timeout), true when it answered. */
    @Volatile var meshObserver: ((healthy: Boolean) -> Unit)? = null

    /** #1924 One plain-DNS exchange with [server]; replaceable so a test can stand fake resolvers in (null / throw = timeout). */
    @Volatile var exchange: (q: ByteArray, server: String, network: Network?) -> ByteArray? = { q, s, n -> forward(q, s, n) }

    /** The host's plan for a name, in order. Default: [mirror]. */
    @Volatile var routes: (name: String) -> List<Route> = { mirror() }
    @Volatile var timeoutMs = 2500
    /** Each route is asked this many times (timeout / SERVFAIL / no answer) before the next route. */
    const val TRIES_PER_ROUTE = 2
    /** Backoff before a retry, × the attempt number. */
    const val BACKOFF_MS = 300L
    /** Upstream queries in flight at once, bridge-wide. */
    const val MAX_IN_FLIGHT = 4
    /** A positive answer is served from cache for its TTL, but at least this long. */
    const val MIN_TTL_MS = 60_000L

    @Volatile private var app: Context? = null
    @Volatile private var bridge: SystemDnsBridge? = null
    @Volatile private var bindError: String? = null
    /** The route that answered the last successful query, e.g. "Android resolver on Wi-Fi". */
    @Volatile var lastRoute: String? = null
        private set
    /** The routes the last failed query walked, " → "-joined, "after N tries"; null after a success. */
    @Volatile var lastFailure: String? = null
        private set
    /** Upstream attempts the last query took. */
    @Volatile var lastTries = 0
        private set
    /** Queries answered from the TTL cache. */
    @Volatile var cached = 0L
        private set

    /** Where the bridge listens: the configured port, or an ephemeral one when that was taken. */
    val port: Int get() = bridge?.port() ?: 0
    val label: String get() = "bridge 127.0.0.1:$port"

    /** Mirror Android: the system resolver on each underlying (non-VPN) network, then the uid's default. */
    fun mirror(preset: String? = null): List<Route> =
        listOfNotNull(vpnOrdered()) + underlying() + Route("Android system resolver" + (preset?.let { " ($it)" } ?: ""))

    /**
     * #900 The VPN's DNS list asked IN ITS ORDER (the Cloud Mesh preset: fleet resolver first).
     * Android's resolver picks among those servers by its own statistics, so with the mesh up a
     * public fallback could answer a fleet name with its public address (a wg_only host then 403s).
     */
    private fun vpnOrdered(): Route? {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } ?: return null
        val servers = cm.getLinkProperties(vpn)?.dnsServers?.map { it.hostAddress ?: "" }?.filter { it.isNotEmpty() }.orEmpty()
        return if (servers.isEmpty()) null else Route("VPN DNS in order", servers, vpn)
    }

    private fun underlying(): List<Route> {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val kind = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "network"
            }
            Route("Android resolver on $kind", network = n)
        }
    }

    /**
     * Start once, at the app's start. [port] is data/sysdns.json::bridge_port (BuildConfig.BRIDGE_PORT):
     * the SHELLS' port, which only a terminal binds (#889). This bridge answers this process's own
     * lookups on an ephemeral port. It once took [port] when it was first up, and then every shell
     * on the phone depended on THIS app staying alive and unfrozen: the Store held it on 2026-10-07
     * and each terminal lookup died whenever the Store was cached or frozen.
     */
    fun start(ctx: Context, port: Int) {
        app = ctx.applicationContext
        val log = SystemDnsBridge.Log { Log.i(TAG, it) }
        bindError = "127.0.0.1:$port is the terminals' shell port (#889); answering in-process only"
        bridge = runCatching { SystemDnsBridge(0, upstream, log) }
            .onFailure { Log.w(TAG, "DNS bridge not started", it) }
            .getOrNull()
    }

    private val walkers = Executors.newCachedThreadPool { r -> Thread(r, "fleet-dns-walk").apply { isDaemon = true } }
    private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "fleet-dns-netd").apply { isDaemon = true } }

    /** The bridge's upstream: off the serve thread, since a walk may wait out several timeouts. */
    private val upstream = SystemDnsBridge.Upstream { q, done -> walkers.execute { done.reply(walk(q)) } }

    private class Cached(val answer: ByteArray, val until: Long)
    /** (name, qtype) → the last positive answer, until its TTL runs out. */
    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = Semaphore(MAX_IN_FLIGHT, true)

    /** #899 Drop the cached answers for [name]: the address it gave did not connect, so the next lookup must ask again. */
    fun forget(name: String) { cache.keys.removeAll { it.substringBeforeLast('/').equals(name.trimEnd('.'), ignoreCase = true) } }

    /** The walk over [routes] for one query (public so FleetDnsBridgeTest can drive it with fake resolvers). */
    fun walk(q: ByteArray): ByteArray? {
        val name = DnsWire.name(q)
        val key = "$name/${DnsWire.qtype(q)}"
        cache[key]?.let { c -> if (c.until > System.currentTimeMillis()) { cached++; return c.answer } else cache.remove(key) }
        val tried = ArrayList<String>()
        var tries = 0
        for (r in runCatching { routes(name) }.getOrDefault(mirror())) {
            tried += r.label
            for (attempt in 1..TRIES_PER_ROUTE) {
                if (attempt > 1) Thread.sleep(BACKOFF_MS * (attempt - 1))
                tries++
                val a = ask(q, r)
                if (a != null && DnsWire.rcode(a) in DEFINITIVE) {
                    lastRoute = r.label; lastFailure = null; lastTries = tries
                    if (DnsWire.rcode(a) == 0 && DnsWire.addresses(a).isNotEmpty())
                        cache[key] = Cached(a, System.currentTimeMillis() + maxOf(DnsWire.ttl(a) * 1000L, MIN_TTL_MS))
                    return a
                }
            }
        }
        lastTries = tries
        lastFailure = tried.joinToString(" → ") + " after $tries tries"
        return null
    }

    /** One attempt at [r], under the in-flight cap: null = timeout, no route or no answer. */
    private fun ask(q: ByteArray, r: Route): ByteArray? {
        inFlight.acquire()
        try {
            return runCatching {
                if (r.servers.isEmpty()) android(q, r.network)
                // in order; a SERVFAIL / REFUSED from one server moves on to the next (#900)
                else r.servers.firstNotNullOfOrNull { s -> runCatching { exchange(q, s, r.network) }.getOrNull()?.takeIf { DnsWire.rcode(it) in DEFINITIVE } }
                    .also { a -> if (r.mesh) runCatching { meshObserver?.invoke(a != null) } }
            }.getOrNull()
        } finally { inFlight.release() }
    }

    /** Android's resolver, raw, on [network] (null = this uid's default). */
    private fun android(q: ByteArray, network: Network?): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val slot = arrayOfNulls<ByteArray>(1)
        val done = CountDownLatch(1)
        DnsResolver.getInstance().rawQuery(network, q, DnsResolver.FLAG_EMPTY, callbacks, null, object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) { slot[0] = answer; done.countDown() }
            override fun onError(error: DnsResolver.DnsException) { done.countDown() }
        })
        done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return slot[0]
    }

    /** [q] as it is to [server]:53 over UDP, on [network] when given. */
    private fun forward(q: ByteArray, server: String, network: Network?): ByteArray? = DatagramSocket().use { s ->
        network?.bindSocket(s)
        s.soTimeout = timeoutMs
        s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        buf.copyOf(p.length).takeIf { it.size >= 12 && it[0] == q[0] && it[1] == q[1] }
    }

    /**
     * The app's own lookup, through the bridge: A then AAAA. Throws
     * [java.net.UnknownHostException] (named with [lastFailure]) on a miss, so a
     * caller's existing DNS classification holds.
     */
    @Throws(IOException::class)
    fun resolve(host: String): List<InetAddress> {
        if (DnsWire.isLiteral(host)) return listOf(InetAddress.getByName(host))
        val b = bridge ?: throw IOException("DNS bridge not running: $bindError")
        // The whole walk may take every route × TRIES_PER_ROUTE × the timeout, plus backoff and the cap's queue.
        val budget = timeoutMs.toLong() * TRIES_PER_ROUTE * 4 + BACKOFF_MS * TRIES_PER_ROUTE * 4
        val found = listOf(DnsWire.A, DnsWire.AAAA).flatMap { type ->
            DnsWire.addresses(b.query(DnsWire.query(host, type), budget))
        }
        if (found.isEmpty()) throw java.net.UnknownHostException("Unable to resolve host \"$host\": no answer via $label (tried ${lastFailure ?: lastRoute})")
        return found
    }

    /** The bridge's /api/sysdns/state body, plus the route in effect. */
    fun stateJson(): String {
        val base = bridge?.stateJson() ?: SystemDnsBridge.notListeningJson(0, bindError ?: "not started")
        return base.dropLast(1) + ",\"route\":" + SystemDnsBridge.quote(lastRoute) +
            ",\"tried\":" + SystemDnsBridge.quote(lastFailure) + ",\"tries\":" + lastTries +
            ",\"cached\":" + cached + ",\"cache_size\":" + cache.size + ",\"bind\":" + SystemDnsBridge.quote(bindError) + "}"
    }

    private val DEFINITIVE = setOf(0, 3)
    private const val TAG = "FleetDnsBridge"
}

/** The few bytes of DNS the bridge's own code needs: a question out, the addresses in. */
object DnsWire {
    const val A = 1
    const val AAAA = 28

    fun isLiteral(host: String): Boolean =
        host.isNotEmpty() && (':' in host || host.all { it.isDigit() || it == '.' })

    /** One RD query for [name], [type] IN, with a fresh id. */
    fun query(name: String, type: Int): ByteArray {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trimEnd('.').split('.')) { val b = label.toByteArray(); out.write(b.size); out.write(b) }
        out.write(byteArrayOf(0, (type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun rcode(m: ByteArray): Int = m[3].toInt() and 0x0F

    /** The question's QTYPE, or 0 for a malformed message. */
    fun qtype(m: ByteArray): Int = runCatching { u16(m, skipName(m, 12)) }.getOrDefault(0)

    /** The smallest TTL over the answer records, in seconds; 0 when there is none. */
    fun ttl(m: ByteArray): Long = runCatching {
        if (m.size < 12) return 0L
        var p = 12
        repeat(u16(m, 4)) { p = skipName(m, p) + 4 }
        var min = Long.MAX_VALUE
        repeat(u16(m, 6)) {
            p = skipName(m, p)
            val ttl = (u16(m, p + 4).toLong() shl 16) or u16(m, p + 6).toLong()
            val len = u16(m, p + 8); p += 10 + len
            min = minOf(min, ttl)
        }
        if (min == Long.MAX_VALUE) 0L else min
    }.getOrDefault(0L)

    /** The question's name, or "" for a malformed message. */
    fun name(m: ByteArray): String = runCatching {
        val labels = ArrayList<String>()
        var i = 12
        while (i < m.size && m[i].toInt() != 0) {
            val n = m[i].toInt() and 0xFF
            labels += String(m, i + 1, n); i += 1 + n
        }
        labels.joinToString(".")
    }.getOrDefault("")

    private fun u16(m: ByteArray, i: Int) = ((m[i].toInt() and 0xFF) shl 8) or (m[i + 1].toInt() and 0xFF)

    /** Past a name at [i] (labels or a compression pointer). */
    private fun skipName(m: ByteArray, i: Int): Int {
        var p = i
        while (p < m.size) {
            val n = m[p].toInt() and 0xFF
            if (n == 0) return p + 1
            if (n and 0xC0 == 0xC0) return p + 2
            p += 1 + n
        }
        return p
    }

    /** Every A / AAAA record in the answer section of [m]; empty for a miss. */
    fun addresses(m: ByteArray): List<InetAddress> = runCatching {
        if (m.size < 12 || rcode(m) != 0) return emptyList()
        var p = 12
        repeat(u16(m, 4)) { p = skipName(m, p) + 4 }
        val out = ArrayList<InetAddress>()
        repeat(u16(m, 6)) {
            p = skipName(m, p)
            val type = u16(m, p); val len = u16(m, p + 8); p += 10
            if ((type == A && len == 4) || (type == AAAA && len == 16)) out += InetAddress.getByAddress(m.copyOfRange(p, p + len))
            p += len
        }
        out
    }.getOrDefault(emptyList())
}

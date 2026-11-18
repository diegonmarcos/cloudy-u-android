package com.diegonmarcos.superapp.netwg

import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #1798 Happy Eyeballs (RFC 8305) for the TLS-443 relay. The relay used to dial its addresses one by one, in the order DNS
 * returned them, with 8 s each. On a Wi-Fi that has no IPv6 route that order put 2603:...:443 first, and the result
 * showed only that address's ENETUNREACH: IPv4 never carried the tunnel. Now:
 *  - [plan]: families interleaved (the first address's family first, then alternating), and a family with no route on this
 *    network is skipped up front, the skip recorded as an attempt with its reason;
 *  - [race]: attempts start [STAGGER_MS] apart (or at once when the previous one fails), each bounded by its own timeout;
 *    the first that connects wins, the rest are closed;
 *  - every attempt, won, failed, skipped or abandoned, is in the returned list, so a failure names each address.
 * Pure JVM: [hasRoute] and the attempt itself are injected, so HappyEyeballsTest drives it with no network.
 */
object HappyEyeballs {
    const val STAGGER_MS = 250L

    data class Attempt(val addr: String, val ok: Boolean?, val ms: Long, val detail: String) {
        val family: String get() = if (':' in addr) "v6" else "v4"
        /** "v4 129.151.228.66 ok 41 ms" / "v6 2603:... FAIL 0 ms: Network is unreachable" / "... skipped: no route". */
        fun line(): String = "$family $addr " + when (ok) { true -> "ok $ms ms"; false -> "FAIL $ms ms: $detail"; null -> "skipped: $detail" }
    }

    /** Whether this network has a route to [addr]: a UDP connect does the route lookup and sends nothing. */
    fun systemRoute(addr: String): Boolean = runCatching {
        DatagramSocket().use { it.connect(InetSocketAddress(InetAddress.getByName(addr), 443)) }
        true
    }.getOrDefault(false)

    /** The addresses to try, families interleaved, and the ones skipped because their family has no route. */
    fun plan(addrs: List<String>, hasRoute: (String) -> Boolean = ::systemRoute): Pair<List<String>, List<Attempt>> {
        val distinct = addrs.distinct()
        val skipped = ArrayList<Attempt>()
        val routed = ArrayList<String>()
        for (a in distinct) {
            val v6 = ':' in a
            if (hasRoute(a)) routed += a else skipped += Attempt(a, null, 0, "no IPv${if (v6) 6 else 4} route on this network")
        }
        val first = routed.firstOrNull()?.let { ':' in it } ?: false
        val a = routed.filter { (':' in it) == first }; val b = routed.filter { (':' in it) != first }
        val out = ArrayList<String>()
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(out::add); b.getOrNull(i)?.let(out::add) }
        return out to skipped
    }

    /** Race [attempt] over [cands]: the first success and every attempt's outcome. Losers that connect late are closed. */
    fun <T : Closeable> race(cands: List<String>, attempt: (String) -> T, staggerMs: Long = STAGGER_MS,
                             clock: () -> Long = System::currentTimeMillis): Pair<Pair<T, String>?, List<Attempt>> {
        if (cands.isEmpty()) return null to emptyList()
        val ex = Executors.newCachedThreadPool { r -> Thread(r, "relay-happy-eyeballs").apply { isDaemon = true } }
        val cs = ExecutorCompletionService<Pair<String, Result<T>>>(ex)
        val started = HashMap<String, Long>()
        val done = ArrayList<Attempt>()
        var next = 0
        var running = 0
        var winner: Pair<T, String>? = null
        try {
            while (winner == null && (next < cands.size || running > 0)) {
                if (next < cands.size) {
                    val a = cands[next++]
                    started[a] = clock()
                    cs.submit { a to runCatching { attempt(a) } }
                    running++
                }
                val f = if (next < cands.size) cs.poll(staggerMs, TimeUnit.MILLISECONDS) else cs.take()
                if (f == null) continue
                running--
                val (a, r) = f.get()
                val ms = clock() - started.getValue(a)
                val got = r.getOrNull()
                if (got != null) { winner = got to a; done += Attempt(a, true, ms, "connected") }
                else done += Attempt(a, false, ms, why(r.exceptionOrNull()))
            }
        } finally {
            val late = running
            for (a in cands) if (a in started && done.none { it.addr == a }) done += Attempt(a, false, clock() - started.getValue(a), "abandoned: another address connected first")
            if (late > 0) ex.execute { repeat(late) { runCatching { cs.take().get().second.getOrNull()?.close() } } }
            ex.shutdown()
        }
        return winner to done
    }

    fun text(attempts: List<Attempt>): String = attempts.joinToString("\n") { it.line() }

    private fun why(t: Throwable?): String = (t?.message ?: t?.javaClass?.simpleName ?: "failed").replace(Regex("\\s+"), " ").take(200)
}

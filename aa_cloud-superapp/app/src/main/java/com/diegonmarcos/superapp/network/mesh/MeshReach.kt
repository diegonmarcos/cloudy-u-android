package com.diegonmarcos.superapp.network.mesh

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * #1153 Status > Reachability: can this phone actually use the private mesh names? For each declared mesh
 * URL the steps run in order - DNS resolve, TCP connect to :443, (#1797) TLS: the certificate's issuer and
 * Android's trust verdict, HTTPS request - each with its own
 * pass/fail and milliseconds. A step whose predecessor failed is reported failed ("skipped") rather than
 * hidden, so the table never has holes. Any HTTP answer below 500 passes the HTTPS step: an auth-gated
 * service answering 401/403 is reachable, which is what is being measured.
 */
object MeshReach {
    const val PORT = 443

    data class Step(val id: String, val ok: Boolean, val ms: Long, val detail: String)
    data class Result(val host: String, val steps: List<Step>) {
        val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }
    }

    /** The three network operations, injectable so the logic is asserted on the JVM. */
    interface Io {
        fun resolve(host: String): List<String>
        fun connect(address: String, port: Int, timeoutMs: Int)
        /** The HTTP status code of a GET of [url]. */
        fun https(url: String, timeoutMs: Int): Int
        /**
         * #1797 The server certificate of [host]:[port] and Android's own verdict on it (its default trust store and
         * hostname check: never a trust-all, never verification turned off). Null = this Io cannot tell (no TLS step).
         */
        fun cert(host: String, port: Int, timeoutMs: Int): Cert? = null
    }

    /** #1797 What the TLS step read: the leaf certificate's issuer and subject, and whether Android trusts it for the host. */
    data class Cert(val issuer: String, val subject: String, val trusted: Boolean, val why: String)

    object SystemIo : Io {
        override fun resolve(host: String) = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        override fun connect(address: String, port: Int, timeoutMs: Int) {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(address), port), timeoutMs) }
        }
        override fun https(url: String, timeoutMs: Int): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            return try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.responseCode
            } finally { c.disconnect() }
        }
        override fun cert(host: String, port: Int, timeoutMs: Int): Cert {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            var chain: Array<X509Certificate>? = null
            var refused: String? = null
            // Records the chain the server sent, then hands it to Android's own trust manager, whose verdict stands:
            // a refusal is rethrown, so the handshake fails exactly as it does for any app.
            val recorder = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, authType: String) = system.checkClientTrusted(c, authType)
                override fun checkServerTrusted(c: Array<X509Certificate>, authType: String) {
                    chain = c
                    try { system.checkServerTrusted(c, authType) } catch (e: CertificateException) { refused = e.message ?: e.javaClass.simpleName; throw e }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
            val sock = ctx.socketFactory.createSocket() as SSLSocket
            var hostOk = false
            try {
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.sslParameters = sock.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                try {
                    sock.startHandshake()
                    hostOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sock.session)
                } catch (e: Exception) { if (chain == null) throw e }
            } finally { runCatching { sock.close() } }
            val leaf = chain?.firstOrNull() ?: throw java.io.IOException("the server sent no certificate")
            val why = refused?.let { "not trusted by Android: $it" }
                ?: if (!hostOk) "trusted chain, but the certificate is not valid for $host" else "trusted by Android"
            return Cert(leaf.issuerX500Principal.name, leaf.subjectX500Principal.name, refused == null && hostOk, why)
        }
    }

    fun run(hosts: List<String>, io: Io = SystemIo, timeoutMs: Int = 4000, nowMs: () -> Long = System::currentTimeMillis): List<Result> =
        hosts.map { host -> probe(host, io, timeoutMs, nowMs) }

    private inline fun timed(nowMs: () -> Long, id: String, body: () -> String): Step {
        val t0 = nowMs()
        return try {
            val d = body(); Step(id, true, nowMs() - t0, d)
        } catch (t: Throwable) {
            Step(id, false, nowMs() - t0, t.message ?: t.javaClass.simpleName)
        }
    }

    fun probe(host: String, io: Io, timeoutMs: Int, nowMs: () -> Long): Result {
        var addrs: List<String> = emptyList()
        val dns = timed(nowMs, "dns") {
            addrs = io.resolve(host)
            check(addrs.isNotEmpty()) { "no address" }
            addrs.joinToString(", ")
        }
        val tcp = if (!dns.ok) Step("tcp", false, 0, "skipped: no address") else timed(nowMs, "tcp") {
            io.connect(addrs.first(), PORT, timeoutMs); "${addrs.first()}:$PORT"
        }
        // #1797 the certificate and Android's trust verdict, when the Io can read them (the phone's can)
        val tls = if (!tcp.ok) null else {
            val t0 = nowMs()
            try {
                io.cert(host, PORT, timeoutMs)?.let { c -> Step("tls", c.trusted, nowMs() - t0, "${c.why} · issuer ${c.issuer} · subject ${c.subject}") }
            } catch (t: Throwable) { Step("tls", false, nowMs() - t0, t.message ?: t.javaClass.simpleName) }
        }
        val http = if (!tcp.ok) Step("https", false, 0, "skipped: no TCP") else timed(nowMs, "https") {
            val code = io.https("https://$host/", timeoutMs)
            check(code in 100..499) { "HTTP $code" }
            "HTTP $code"
        }
        return Result(host, listOfNotNull(dns, tcp, tls, http))
    }

    /** One line per host and step: `host  dns PASS 12 ms (ip) | tcp ... | https ...`. */
    fun text(results: List<Result>): String = results.joinToString("\n") { r ->
        r.host + "  " + r.steps.joinToString(" | ") { s -> "${s.id} ${if (s.ok) "PASS" else "FAIL"} ${s.ms} ms (${s.detail})" }
    }
}

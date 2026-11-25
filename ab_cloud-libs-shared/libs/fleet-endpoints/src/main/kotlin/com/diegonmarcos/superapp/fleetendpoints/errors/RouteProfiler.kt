package com.diegonmarcos.superapp.fleetendpoints.errors

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * #1925 The minimal route profiler, taken at failure time: DNS answers and the resolver, mesh or public path, TCP connect,
 * TLS handshake with the certificate name, per-phase timing and the VPN state. Every phase is bounded by [phaseTimeoutMs]
 * and the whole profile by roughly three phases, so a dead hub costs seconds, never a hang. The network is behind [Io]
 * so a scripted one asserts every field on the JVM.
 */
object RouteProfiler {
    interface Io {
        /** Resolves [host]; the pair is (addresses, resolver name). Throws when nothing answers. */
        fun resolve(host: String): Pair<List<String>, String>
        fun connect(address: String, port: Int, timeoutMs: Int)
        /** Handshakes TLS with [host] over a fresh connection; answers the certificate name (CN or first SAN). */
        fun handshake(address: String, host: String, port: Int, timeoutMs: Int): String
        fun now(): Long
    }

    object SystemIo : Io {
        override fun resolve(host: String): Pair<List<String>, String> =
            InetAddress.getAllByName(host).map { it.hostAddress.orEmpty() } to "system"
        override fun connect(address: String, port: Int, timeoutMs: Int) {
            Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
        }
        override fun handshake(address: String, host: String, port: Int, timeoutMs: Int): String {
            val raw = Socket()
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            return ssl.use {
                it.startHandshake()
                val cert = it.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                    ?: return@use ""
                val san = runCatching { cert.subjectAlternativeNames?.firstOrNull { n -> n[0] == 2 }?.get(1)?.toString() }.getOrNull()
                san ?: cert.subjectX500Principal.name.substringAfter("CN=", "").substringBefore(',')
            }
        }
        override fun now(): Long = System.nanoTime() / 1_000_000
    }

    /** `.app` hosts are the private mesh namespace; anything else travels the public path. */
    fun pathOf(host: String): String = if (host.endsWith(".app") || host.endsWith(".internal")) "mesh" else "public"

    fun profile(
        host: String, port: Int, https: Boolean, vpn: String, io: Io = SystemIo, phaseTimeoutMs: Int = 2_000,
    ): RouteProfile {
        val t0 = io.now()
        var answers: List<String> = emptyList(); var resolver = "-"; var dnsMs: Long? = null
        var tcpMs: Long? = null; var tcpErr = ""; var tlsMs: Long? = null; var cert = ""; var tlsErr = ""
        run {
            val s = io.now()
            try { val r = io.resolve(host); answers = r.first; resolver = r.second; dnsMs = io.now() - s }
            catch (e: Exception) { dnsMs = io.now() - s; resolver = "none"; tcpErr = "dns: ${e.javaClass.simpleName}: ${e.message}" }
        }
        val addr = answers.firstOrNull()
        if (addr != null) {
            val s = io.now()
            try { io.connect(addr, port, phaseTimeoutMs); tcpMs = io.now() - s }
            catch (e: Exception) { tcpErr = "${e.javaClass.simpleName}: ${e.message}" }
            if (https && tcpMs != null) {
                val h = io.now()
                try { cert = io.handshake(addr, host, port, phaseTimeoutMs); tlsMs = io.now() - h }
                catch (e: Exception) { tlsErr = "${e.javaClass.simpleName}: ${e.message}" }
            }
        }
        return RouteProfile(
            host = host, dnsAnswers = answers, resolver = resolver, path = pathOf(host), tcpMs = tcpMs, tcpError = tcpErr,
            tlsMs = tlsMs, certName = cert, tlsError = tlsErr, dnsMs = dnsMs, totalMs = io.now() - t0, vpn = vpn,
        )
    }
}

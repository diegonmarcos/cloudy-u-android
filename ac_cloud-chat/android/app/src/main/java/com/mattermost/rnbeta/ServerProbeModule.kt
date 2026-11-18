package com.mattermost.rnbeta

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Cloud fleet #1804: the single login screen's server check, the two steps the JS network client cannot
 * report on its own. resolveHost = the DNS step (the platform resolver, so the mesh's private DNS counts the
 * way it does for every other request). checkTls = the TLS-trust step: a handshake with the platform trust
 * (this app's network_security_config: system + user CAs, so an installed fleet CA counts) and hostname
 * verification; it reports the certificate's issuer either way. When the chain is NOT trusted, a second
 * handshake reads the certificate only to NAME its issuer in the error; nothing is sent on that socket and
 * it is closed at once, so no request ever goes over an untrusted connection.
 */
@ReactModule(name = ServerProbeModule.NAME)
class ServerProbeModule(ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName(): String = NAME

    @ReactMethod
    fun resolveHost(host: String, promise: Promise) {
        Thread {
            val r = Arguments.createMap()
            try {
                val addrs = InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
                r.putBoolean("ok", addrs.isNotEmpty())
                r.putString("addresses", addrs.joinToString(", "))
            } catch (e: Exception) {
                r.putBoolean("ok", false)
                r.putString("error", e.message ?: e.javaClass.simpleName)
            }
            promise.resolve(r)
        }.start()
    }

    @ReactMethod
    fun checkTls(host: String, port: Int, promise: Promise) {
        Thread {
            val r = Arguments.createMap()
            try {
                val cert = handshake(host, port, SSLSocketFactory.getDefault() as SSLSocketFactory, verifyHost = true)
                r.putBoolean("trusted", true)
                putCert(r, cert)
            } catch (e: Exception) {
                r.putBoolean("trusted", false)
                r.putString("error", e.message ?: e.javaClass.simpleName)
                try {
                    putCert(r, handshake(host, port, captureOnlyFactory(), verifyHost = false))
                } catch (_: Exception) {
                    // no certificate to name (connection refused, timeout, not TLS): the error above says why
                }
            }
            promise.resolve(r)
        }.start()
    }

    private fun handshake(host: String, port: Int, factory: SSLSocketFactory, verifyHost: Boolean): X509Certificate? {
        connectAny(host, port).use { raw ->
            raw.soTimeout = TIMEOUT_MS
            (factory.createSocket(raw, host, port, true) as SSLSocket).use { s ->
                // SNI named explicitly: a layered socket does not always send it, and a server that picks its
                // certificate by name (Caddy) closes a handshake without one ("connection closed", no issuer).
                val p: SSLParameters = s.sslParameters
                if (!host.isIpLiteral()) p.serverNames = listOf(SNIHostName(host))
                if (verifyHost) p.endpointIdentificationAlgorithm = "HTTPS"
                s.sslParameters = p
                s.startHandshake()
                return s.session.peerCertificates.firstOrNull() as? X509Certificate
            }
        }
    }

    /** Every resolved address in turn (an emulator or a v4-only network cannot route the v6 one). */
    private fun connectAny(host: String, port: Int): Socket {
        var last: Exception? = null
        for (addr in InetAddress.getAllByName(host)) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(addr, port), TIMEOUT_MS)
                return sock
            } catch (e: Exception) {
                last = e
                try { sock.close() } catch (_: Exception) {}
            }
        }
        throw last ?: java.net.UnknownHostException(host)
    }

    private fun putCert(r: com.facebook.react.bridge.WritableMap, cert: X509Certificate?) {
        cert ?: return
        r.putString("issuer", cert.issuerX500Principal.name)
        r.putString("subject", cert.subjectX500Principal.name)
        r.putDouble("notAfter", cert.notAfter.time.toDouble())
    }

    /** Reads the presented chain without judging it: used only to name the issuer of an UNTRUSTED certificate. */
    private fun captureOnlyFactory(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    private fun String.isIpLiteral(): Boolean = contains(':') || all { it.isDigit() || it == '.' }

    companion object {
        const val NAME = "CloudServerProbe"
        private const val TIMEOUT_MS = 8000
    }
}

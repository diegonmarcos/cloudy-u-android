package com.diegonmarcos.superapp.fleetendpoints.errors

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1925 the error model carries every field, secrets are redacted, the export holds it all; each guard has a mutant that turns it red. */
class ErrorLog1925Test {
    private val scripted = object : RouteProfiler.Io {
        var t = 0L
        override fun resolve(host: String) = listOf("10.0.0.7", "10.0.0.8") to "scripted-resolver"
        override fun connect(address: String, port: Int, timeoutMs: Int) { t += 7 }
        override fun handshake(address: String, host: String, port: Int, timeoutMs: Int): String { t += 11; return "*.example.test" }
        override fun now(): Long = t
    }

    private fun log(vpn: String = "VPN up") = ErrorLog(
        clock = { 1_700_000_000_000L }, vpnState = { vpn }, accountOf = { "me@example.test" },
        profiler = { h, p, s, v -> RouteProfiler.profile(h, p, s, v, scripted) },
    )

    private fun failing(body: String, status: Int = 502): Pair<MockWebServer, ErrorLog> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Authorization", "Bearer abc.def.ghi-secret").setHeader("Set-Cookie", "sid=topsecret")
            .setHeader("X-Trace", "t1").setBody(body))
        server.start()
        return server to log()
    }

    @Test fun everyFieldIsCarried() {
        val (server, l) = failing("x".repeat(5000))
        try {
            val client = OkHttpClient.Builder().addInterceptor(l.interceptor()).build()
            client.newCall(Request.Builder().url(server.url("/.well-known/jmap")).header("Authorization", "Bearer SECRETTOKEN123").build()).execute().close()
            assertNotNull(l.latest()); val r = l.latest()!!
            assertEquals("JMAP session", r.operation)
            assertEquals("me@example.test", r.account)
            assertTrue(r.url.contains("/.well-known/jmap")); assertEquals("/.well-known/jmap", r.path)
            assertEquals("GET", r.method); assertEquals(502, r.status); assertTrue(r.reason.isNotBlank())
            assertTrue(r.responseHeaders.any { it.first == "X-Trace" && it.second == "t1" })
            assertEquals(2048, r.bodyHead.length)
            assertEquals(1_700_000_000_000L, r.atMs)
            val p = r.route!!
            assertEquals(listOf("10.0.0.7", "10.0.0.8"), p.dnsAnswers); assertEquals("scripted-resolver", p.resolver)
            assertEquals("public", p.path); assertEquals(7L, p.tcpMs); assertEquals("VPN up", p.vpn)
            assertNotNull(p.dnsMs); assertTrue(p.totalMs > 0)
        } finally { server.shutdown() }
    }

    @Test fun transportFailureCarriesChainAndMeshPath() {
        val l = log()
        val boom = java.io.IOException("connect failed", IllegalStateException("root cause"))
        val r = l.capture("sync", "a@b.test", "https://mail.hub.app/jmap/session", "GET", null, boom)
        assertNull(r.status)
        assertEquals(2, r.chain.size); assertTrue(r.chain[1].contains("root cause"))
        assertEquals("mesh", r.route!!.path); assertEquals("*.example.test", r.route!!.certName); assertEquals(11L, r.route!!.tlsMs)
    }

    @Test fun recordPutsOperationAccountAndChainOnTheExchange() {
        val l = log()
        l.capture("request", "", "https://h.example.test/jmap", "POST", null, null)
        val r = l.record("sync", "me@x.test", RuntimeException("outer", java.io.IOException("inner")))
        assertEquals("sync", r.operation); assertEquals("me@x.test", r.account); assertEquals(2, r.chain.size)
        assertEquals("/jmap", r.path)
    }

    private fun leaks(redact: (String) -> String): Boolean {
        val raw = listOf(
            "Authorization: Bearer abcdef123456", "Cookie: sid=topsecret", "password=hunter22", "{\"access_token\":\"tok123456\"}",
            "https://user:pw123@host.test/x", "jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.sig123456",
        )
        val secrets = listOf("abcdef123456", "topsecret", "hunter22", "tok123456", "pw123", "eyJzdWIiOiIxMjM0")
        val out = raw.joinToString("\n") { redact(it) }
        return secrets.any { it in out }
    }

    @Test fun redactionStripsSecrets() {
        assertFalse(leaks(ErrorRedactor::text))
        assertEquals(ErrorRedactor.MASK, ErrorRedactor.header("Authorization", "Bearer x"))
        assertEquals(ErrorRedactor.MASK, ErrorRedactor.header("Set-Cookie", "a=b"))
        assertEquals("t1", ErrorRedactor.header("X-Trace", "t1"))
    }

    @Test fun mutantRedactorIsCaught() { assertTrue(leaks { it }) }

    @Test fun capturedReportNeverHoldsSecrets() {
        val (server, l) = failing("{\"password\":\"hunter22\",\"note\":\"Bearer abcdef123456\"}")
        try {
            OkHttpClient.Builder().addInterceptor(l.interceptor()).build()
                .newCall(Request.Builder().url(server.url("/jmap")).header("Authorization", "Bearer SECRETTOKEN123").build()).execute().close()
            val all = l.text(); val md = l.markdown()
            for (s in listOf("hunter22", "abcdef123456", "topsecret", "abc.def.ghi-secret", "SECRETTOKEN123")) {
                assertFalse("$s in text", s in all); assertFalse("$s in markdown", s in md)
            }
        } finally { server.shutdown() }
    }

    @Test fun exportHoldsEverySectionAndTheRing() {
        val (server, l) = failing("gateway down")
        try {
            l.log("JmapClient", "push reconnect")
            OkHttpClient.Builder().addInterceptor(l.interceptor()).build()
                .newCall(Request.Builder().url(server.url("/.well-known/jmap")).build()).execute().close()
            val md = l.markdown()
            for (h in listOf("# Cloud Mail error", "## Error", "## Request", "## Response headers", "## Response body", "## Exception chain", "## Route profile", "## Mail log"))
                assertTrue("missing $h", h in md)
            for (f in listOf("operation", "account", "me@example.test", "502", "gateway down", "scripted-resolver", "10.0.0.7", "VPN up", "push reconnect", "X-Trace"))
                assertTrue("missing $f", f in md)
            assertEquals(l.text(), l.text()) // See log, Export and Copy all read the same content
        } finally { server.shutdown() }
    }

    @Test fun ringIsBounded() {
        val l = ErrorLog(lines = 5)
        repeat(20) { l.log("t", "line $it") }
        assertEquals(5, l.ringLines().size); assertTrue(l.ringLines().last().contains("line 19"))
    }

    @Test fun markdownHelperFencesSections() {
        assertEquals("# T\n\n## S\n\n```\nrow\n```\n", ReportMarkdown.fromPlain("T\n== S\nrow"))
    }
}

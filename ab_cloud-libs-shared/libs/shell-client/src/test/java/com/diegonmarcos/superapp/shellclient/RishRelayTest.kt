package com.diegonmarcos.superapp.shellclient

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** #1512 rish over the broker: the relay's request rules and a real loopback round trip. */
class RishRelayTest {
    private val token = "tok-123"
    private var relay: RishRelay? = null

    @After fun stop() { relay?.stop() }

    private fun head(cmd: String, auth: String? = "Bearer $token", extra: String = ""): List<String> =
        listOfNotNull("GET /exec?cmd=${URLEncoder.encode(cmd, "UTF-8")}$extra HTTP/1.1", "Host: 127.0.0.1", auth?.let { "Authorization: $it" })

    @Test fun aValidRequestBecomesACommandWithAClampedTimeout() {
        val p = RishRelay.parse(head("pm list packages", extra = "&t=99999999"), token)
        assertTrue(p is RishRelay.Parsed.Cmd)
        p as RishRelay.Parsed.Cmd
        assertEquals("pm list packages", p.cmd); assertEquals(ShellWire.MAX_TIMEOUT_MS, p.timeoutMs)
        assertEquals(ShellWire.DEFAULT_TIMEOUT_MS, (RishRelay.parse(head("id"), token) as RishRelay.Parsed.Cmd).timeoutMs)
    }

    @Test fun aMissingOrWrongTokenIs401() {
        for (a in listOf(null, "Bearer wrong", "Bearer ", "tok-123", "Basic tok-123"))
            assertEquals(a, 401, (RishRelay.parse(head("id", auth = a), token) as RishRelay.Parsed.Bad).status)
        assertEquals(401, (RishRelay.parse(head("id", auth = "Bearer "), "") as RishRelay.Parsed.Bad).status)   // an empty server token never matches
    }

    @Test fun onlyGetExecIsServed() {
        for (l in listOf("POST /exec?cmd=id HTTP/1.1", "GET /other?cmd=id HTTP/1.1", "GET /exec HTTP/1.1", "GET /exec?cmd=id HTTP/2"))
            assertTrue(l, RishRelay.parse(listOf(l, "Authorization: Bearer $token"), token) is RishRelay.Parsed.Bad)
        assertEquals(400, (RishRelay.parse(head(" "), token) as RishRelay.Parsed.Bad).status)
    }

    @Test fun theBrokersAnswersMapToExitCodesAndStatuses() {
        fun reply(r: ShellResult) = RishRelay(token) { _, _ -> r }.answer(head("id"))
        assertEquals(RishRelay.Reply(200, "out", 3), reply(ShellResult.Ok(3, "o", "ut", "x")))
        assertEquals(403, reply(ShellResult.Denied("no rule")).status)
        assertEquals(503, reply(ShellResult.NeedsArm).status)
        assertEquals(124, reply(ShellResult.Failed("timed out", true)).exit)
        assertTrue(reply(ShellResult.Denied("no rule")).body.contains("no rule"))
    }

    @Test fun aRealLoopbackRoundTripCarriesTheCommandAndTheExitCode() {
        var seen = ""
        val r = RishRelay(token) { cmd, _ -> seen = cmd; ShellResult.Ok(7, "hello\n", "", "x") }.also { relay = it }
        val port = r.start()
        val c = URL("http://127.0.0.1:$port/exec?cmd=" + URLEncoder.encode("getprop ro.build.id", "UTF-8")).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        assertEquals(200, c.responseCode)
        assertEquals("hello\n", c.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals("7", c.getHeaderField("X-Exit-Code"))
        assertEquals("getprop ro.build.id", seen)
    }

    @Test fun aWrongTokenNeverReachesTheBroker() {
        var called = false
        val r = RishRelay(token) { _, _ -> called = true; ShellResult.Ok(0, "", "", "x") }.also { relay = it }
        val c = URL("http://127.0.0.1:${r.start()}/exec?cmd=id").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer nope")
        assertEquals(401, c.responseCode)
        assertFalse(called)
    }

    @Test fun theRelayListensOnLoopbackOnly() {
        val r = RishRelay(token) { _, _ -> ShellResult.NeedsArm }.also { relay = it }
        r.start()
        val f = RishRelay::class.java.getDeclaredField("server").apply { isAccessible = true }
        assertTrue((f.get(r) as java.net.ServerSocket).inetAddress.isLoopbackAddress)
    }

    @Test fun tokensAreRandomPerCall() {
        assertEquals(64, RishRelay.newToken().length)
        assertFalse(RishRelay.newToken() == RishRelay.newToken())
    }

    @Test fun theLauncherAndEnvCarryNoSecretInSourceAndTheRightVariables() {
        val l = ShellRish.launcher()
        assertTrue(l.startsWith("#!/bin/sh"))
        assertTrue(l.contains("\$RISH_BRIDGE_TOKEN") && l.contains("\$RISH_BRIDGE_URL") && l.contains("X-Exit-Code") || l.contains("[Xx]-[Ee]xit-[Cc]ode"))
        assertFalse(l.contains('@'))
        assertTrue(ShellRish.env(4242, "abc").contains("RISH_BRIDGE_URL=http://127.0.0.1:4242/exec"))
    }
}

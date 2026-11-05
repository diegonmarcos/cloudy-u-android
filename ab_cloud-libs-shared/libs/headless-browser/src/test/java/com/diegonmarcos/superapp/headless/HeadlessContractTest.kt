package com.diegonmarcos.superapp.headless

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1498 the headless-api v1 contract, the backend selector, the idle policy, the token gate, the CDP policy and the dispatcher. */
class HeadlessContractTest {
    private fun j(s: String) = JSONObject(s)
    private fun code(f: () -> Unit): String? = try { f(); null } catch (e: HeadlessApi.Err) { e.code }
    private fun errCode(o: JSONObject) = o.getJSONObject("error").getString("code")

    private class Fake : HeadlessEngine {
        var closed = 0
        override fun newSession(profile: String): Any = profile
        override fun closeSession(handle: Any) { closed++ }
        override fun run(handle: Any, cmd: JSONObject): Any? { if (cmd.optLong("ms") == 99L) Thread.sleep(400); return JSONObject().put("ran", cmd.getString("cmd")) }
    }

    @Test fun commandListMatchesTheSharedSchema() {
        val f = File("../headless-api/headless-api.json")
        if (!f.exists()) return
        val keys = j(f.readText()).getJSONObject("commands").keys().asSequence().toList()
        assertEquals(HeadlessApi.CMDS.sorted(), keys.sorted())
    }

    @Test fun validateRejectsBadCommands() {
        assertEquals("bad_request", code { HeadlessApi.validate(j("""{"cmd":"nope"}""")) })
        assertEquals("bad_request", code { HeadlessApi.validate(j("""{"cmd":"open","url":"file:///x"}""")) })
        assertEquals("bad_request", code { HeadlessApi.validate(j("""{"cmd":"wait","selector":"a","ms":1}""")) })
        assertEquals("bad_request", code { HeadlessApi.validate(j("""{"cmd":"click","session":"bad id","selector":"a"}""")) })
        assertEquals("bad_request", code { HeadlessApi.validate(j("""{"cmd":"html","timeoutMs":999999}""")) })
        assertNull(code { HeadlessApi.validate(j("""{"cmd":"wait","idle":true}""")) })
        assertEquals("bad_request", code { HeadlessApi.validateRun(j("""{"steps":[]}""")) })
    }

    @Test fun selectorDefaultsToThisApp() {
        assertEquals(HeadlessBackend.THIS_APP, HeadlessBackend.parse(null))
        assertEquals(HeadlessBackend.THIS_APP, HeadlessBackend.parse("junk"))
        assertTrue(HeadlessSelector.resolve({ null }, { null }).local)
        assertEquals(listOf("cloudflare-browser-run", "cloudy-browser-headless", "this-app"), HeadlessBackend.entries.map { it.id })
    }

    @Test fun selectorResolvesRemotesFromStoreKeyNames() {
        val store = mapOf("my.url" to "https://h.example/x/", "my.tok" to " tok ", "cloudflare.worker_url" to "https://w.example", "cloudflare.worker_token" to "cf")
        val s = mapOf("headless_backend" to "cloudy-browser-headless", "headless_cloudy_url_key" to "my.url", "headless_cloudy_token_key" to "my.tok")
        assertEquals(HeadlessRoute(HeadlessBackend.CLOUDY, "https://h.example/x", "tok"), HeadlessSelector.resolve({ s[it] }, { store[it] }))
        val cf = HeadlessSelector.resolve({ mapOf("headless_backend" to "cloudflare-browser-run")[it] }, { store[it] })
        assertEquals("https://w.example", cf.baseUrl); assertTrue(cf.usable)
        val missing = HeadlessSelector.resolve({ mapOf("headless_backend" to "cloudy-browser-headless")[it] }, { null })
        assertFalse(missing.usable); assertEquals("no URL in the store", missing.reason)
        val http = HeadlessSelector.resolve({ mapOf("headless_backend" to "cloudy-browser-headless")[it] }, { if (it.endsWith("url")) "http://x" else "t" })
        assertEquals("the URL must be https", http.reason)
    }

    @Test fun idlePolicy() {
        assertTrue(HeadlessPolicy.sessionExpired(1000, 0, 1000)); assertFalse(HeadlessPolicy.sessionExpired(999, 0, 1000))
        assertTrue(HeadlessPolicy.serviceShouldStop(1000, 0, 0, 1000)); assertFalse(HeadlessPolicy.serviceShouldStop(1000, 0, 1, 1000))
        assertFalse(HeadlessPolicy.mayRun(false)); assertTrue(HeadlessPolicy.mayRun(true))
        assertFalse(HeadlessPolicy.mayOpen(3, 3)); assertTrue(HeadlessPolicy.mayOpen(2, 3))
        assertEquals(8, HeadlessPolicy.clampSessions(99)); assertEquals(1, HeadlessPolicy.clampSessions(0))
    }

    @Test fun tokenGateFailsClosed() {
        assertEquals("server token not configured", HeadlessTokenGate("").check("Bearer x"))
        val g = HeadlessTokenGate.generate()
        assertEquals(64, g.token.length)
        assertEquals("bearer required", g.check(null))
        assertEquals("bad token", g.check("Bearer nope"))
        assertNull(g.check("Bearer ${g.token}"))
        assertTrue(HeadlessTokenGate.loopbackOnly("127.0.0.1")); assertFalse(HeadlessTokenGate.loopbackOnly("0.0.0.0"))
    }

    @Test fun cdpIsOptInTokenGatedAndSelfExpiring() {
        assertFalse(CdpPolicy.mayArm(true, false)); assertFalse(CdpPolicy.mayArm(false, true)); assertTrue(CdpPolicy.mayArm(true, true))
        assertEquals("adb forward tcp:9222 localabstract:webview_devtools_remote_123", CdpPolicy.forwardCommand(123, 9222))
        assertTrue(CdpPolicy.allowedCommand(CdpPolicy.forwardCommand(123, 9222))); assertTrue(CdpPolicy.allowedCommand(CdpPolicy.unforwardCommand(9222)))
        assertFalse(CdpPolicy.allowedCommand("adb forward tcp:9222 tcp:22")); assertFalse(CdpPolicy.allowedCommand("rm -rf /"))
        assertFalse(CdpPolicy.expired(59_999, 0, 1)); assertTrue(CdpPolicy.expired(60_000, 0, 1))
        assertEquals(60, CdpPolicy.clampMinutes(9999)); assertEquals(1024, CdpPolicy.clampPort(80))
    }

    @Test fun auditRingIsCappedAndTagged() {
        var l = emptyList<HeadlessAudit.Entry>()
        repeat(HeadlessAudit.CAP + 5) { l = HeadlessAudit.add(l, HeadlessAudit.Entry(it.toLong(), "bot", "open", "https://a", true)) }
        assertEquals(HeadlessAudit.CAP, l.size)
        assertEquals("[agent:bot] open", HeadlessAudit.title(l.last()))
        assertEquals("[agent] click (failed)", HeadlessAudit.title(HeadlessAudit.Entry(0, "", "click", "", false)))
        assertEquals(l, HeadlessAudit.fromJson(HeadlessAudit.toJson(l)))
    }

    @Test fun dispatcherEnforcesLimitsIdleTimeoutScreenAndAudits() {
        var t = 0L; var screen = true
        val audit = mutableListOf<HeadlessAudit.Entry>()
        val e = Fake()
        val d = HeadlessDispatcher(e, maxSessions = 2, idleMs = 1000, callTimeoutMs = 100, now = { t }, screenOn = { screen }, audit = { audit += it })
        assertTrue(d.command(j("""{"cmd":"open","url":"https://a.test","session":"a","agent":"bot"}""")).getBoolean("ok"))
        assertTrue(d.command(j("""{"cmd":"open","url":"https://a.test","session":"b"}""")).getBoolean("ok"))
        assertEquals("limit", errCode(d.command(j("""{"cmd":"open","url":"https://a.test","session":"c"}"""))))
        assertEquals("timeout", errCode(d.command(j("""{"cmd":"wait","ms":99,"session":"a"}"""))))
        screen = false
        assertEquals("denied", errCode(d.command(j("""{"cmd":"html","session":"a"}"""))))
        screen = true
        t = 1000
        assertEquals(listOf("a", "b"), d.sweep())
        assertEquals("not_found", errCode(d.command(j("""{"cmd":"html","session":"a"}"""))))
        assertEquals(2, e.closed)
        assertTrue(audit.any { it.agent == "bot" && it.cmd == "open" && it.ok })
        assertTrue(audit.any { !it.ok })
    }

    @Test fun runStepsStopOnError() {
        val d = HeadlessDispatcher(Fake())
        val r = d.run(j("""{"steps":[{"cmd":"open","url":"https://a.test","session":"x"},{"cmd":"text"},{"cmd":"html","session":"ghost"},{"cmd":"text"}]}"""))
        assertFalse(r.getBoolean("ok"))
        assertEquals(3, r.getJSONArray("steps").length())
        assertTrue(r.getJSONArray("steps").getJSONObject(1).getBoolean("ok"))
    }
}

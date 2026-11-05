package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrokerCoreTest {
    private val list = Allowlist.parse("""{"callers":{"app.a":{"allow":[{"id":"r","re":"^(dumpsys battery|getprop [\\w.]+|sleep \\d+|echo [\\w=.]+)$"}]}}}""")

    private class FakeChannel(var ready: Boolean = true, val body: (String) -> String? = { "ok" }) : BrokerChannel {
        val ran = ArrayList<String>()
        override fun name() = "fake"
        override fun ready() = ready
        override fun run(cmd: String, stdin: File?): String? { ran += cmd; return body(cmd) }
    }

    private fun core(ch: BrokerChannel, audit: AuditLog = AuditLog(), now: () -> Long = { 1_000L }, max: Int = 3, arm: () -> Boolean = { false }) =
        BrokerCore({ list }, ch, audit, now, max, arm)

    @Test fun okCarriesExitStdoutAndSource() {
        val r = core(FakeChannel { "level: 90\n\n__SB_EXIT__0\n" }).exec("app.a", "dumpsys battery", 5_000)
        assertEquals(ShellResult.Ok(0, "level: 90\n", "", "fake"), r)
    }

    @Test fun aNonZeroExitIsReported() {
        val r = core(FakeChannel { "boom\n__SB_EXIT__3\n" }).exec("app.a", "getprop x.y", 5_000)
        assertTrue(r is ShellResult.Ok && r.exitCode == 3 && !r.success)
    }

    @Test fun deniedNeverReachesTheChannelAndIsAudited() {
        val ch = FakeChannel(); val audit = AuditLog()
        val r = core(ch, audit).exec("app.stranger", "dumpsys battery", 5_000)
        assertTrue(r is ShellResult.Denied)
        assertTrue(ch.ran.isEmpty())
        assertEquals("denied", audit.entries().single().outcome)
        assertEquals("app.stranger", audit.entries().single().caller)
    }

    @Test fun auditHasCallerCmdExitDurationAndNoOutput() {
        var t = 1_000L
        val audit = AuditLog()
        val ch = FakeChannel { t += 40; "SECRET-OUTPUT-value\n__SB_EXIT__0\n" }
        core(ch, audit, now = { t }).exec("app.a", "getprop ro.x", 5_000)
        val e = audit.entries().single()
        assertEquals("app.a", e.caller); assertEquals("getprop ro.x", e.cmd); assertEquals(0, e.exit); assertEquals("ok", e.outcome)
        assertTrue(e.ms >= 40); assertEquals("fake", e.source)
        assertFalse(AuditLog.line(e).contains("SECRET-OUTPUT"))
    }

    @Test fun auditMasksCredentialsInTheCommand() {
        val c = AuditLog.redact("curl -H token=abcdef123 Bearer sometoken ${"A".repeat(40)}")
        assertFalse(c.contains("abcdef123")); assertFalse(c.contains("sometoken")); assertFalse(c.contains("A".repeat(32)))
    }

    @Test fun auditRingIsBounded() {
        val a = AuditLog(capacity = 3)
        repeat(10) { a.add(AuditLog.Entry(it.toLong(), "c", "x", "ok", 0, 1, "s")) }
        assertEquals(listOf(7L, 8L, 9L), a.entries().map { it.ts })
    }

    @Test fun everyCallTimesOut() {
        val release = CountDownLatch(1)
        val audit = AuditLog()
        val ch = FakeChannel { release.await(10, TimeUnit.SECONDS); "late" }
        val r = core(ch, audit, now = System::currentTimeMillis).exec("app.a", "sleep 99", 1_000)
        release.countDown()
        assertTrue(r is ShellResult.Failed && r.timedOut)
        assertEquals("timeout", audit.entries().single().outcome)
    }

    @Test fun unarmedChannelIsNeedsArm() {
        val ch = FakeChannel(ready = false)
        val audit = AuditLog()
        assertEquals(ShellResult.NeedsArm, core(ch, audit).exec("app.a", "dumpsys battery", 5_000))
        assertTrue(ch.ran.isEmpty())
        assertEquals("needs_arm", audit.entries().single().outcome)
    }

    @Test fun anArmAttemptThatBringsTheChannelUpServesTheCall() {
        val ch = FakeChannel(ready = false) { "x\n__SB_EXIT__0\n" }
        val r = core(ch, arm = { ch.ready = true; true }).exec("app.a", "dumpsys battery", 5_000)
        assertTrue(r is ShellResult.Ok)
    }

    @Test fun aChannelThatReturnsNothingIsNeedsArm() {
        assertEquals(ShellResult.NeedsArm, core(FakeChannel { null }).exec("app.a", "dumpsys battery", 5_000))
    }

    @Test fun concurrencyIsCapped() {
        val gate = CountDownLatch(1)
        val ch = FakeChannel { gate.await(10, TimeUnit.SECONDS); "x\n__SB_EXIT__0\n" }
        val c = core(ch, max = 2, now = System::currentTimeMillis)
        val ts = (1..2).map { Thread { c.exec("app.a", "dumpsys battery", 20_000) }.apply { start() } }
        while (ch.ran.size < 2) Thread.sleep(10)
        val third = c.exec("app.a", "dumpsys battery", 5_000)
        gate.countDown(); ts.forEach { it.join(5_000) }
        assertTrue(third is ShellResult.Failed && third.reason.startsWith("busy"))
        assertTrue(c.exec("app.a", "dumpsys battery", 5_000) is ShellResult.Ok)   // the slots came back
    }

    @Test fun outputIsCapped() {
        val r = core(FakeChannel { "x".repeat(500) + "\n__SB_EXIT__0\n" }).exec("app.a", "dumpsys battery", 5_000, maxBytes = 100) as ShellResult.Ok
        assertTrue(r.stdout.startsWith("x".repeat(100)) && r.stdout.contains("[truncated"))
    }

    @Test fun aChannelErrorLandsInStderr() {
        val r = core(FakeChannel { "ERR: no such file" }).exec("app.a", "dumpsys battery", 5_000) as ShellResult.Ok
        assertEquals("no such file", r.stderr); assertEquals("", r.stdout); assertEquals(1, r.exitCode)
    }

    @Test fun theBrokerGoesIdleAndStopsItsWorkers() {
        var t = 1_000L
        val c = core(FakeChannel { "x\n__SB_EXIT__0\n" }, now = { t })
        assertFalse(c.poolAlive())
        c.exec("app.a", "dumpsys battery", 5_000)
        assertTrue(c.poolAlive())
        assertFalse("not idle yet", c.reapIfIdle(t + IdlePolicy.IDLE_MS - 1))
        assertTrue(c.reapIfIdle(t + IdlePolicy.IDLE_MS))
        assertFalse(c.poolAlive())
        t += 100_000
        assertTrue("the next call recreates the workers", c.exec("app.a", "dumpsys battery", 5_000) is ShellResult.Ok)
        assertTrue(c.poolAlive())
    }

    @Test fun idlePolicyNeverStopsAWorkingBroker() {
        assertFalse(IdlePolicy.shouldStop(inflight = 1, lastActiveMs = 0, nowMs = 10_000_000))
        assertTrue(IdlePolicy.shouldStop(0, 0, IdlePolicy.IDLE_MS))
        assertEquals(30_000L, IdlePolicy.IDLE_MS)
    }

    @Test fun armGateHonoursTheThirtySecondFloor() {
        val g = ArmGate()
        assertTrue(g.mayTry(0))
        g.failed(0)
        assertFalse(g.mayTry(29_999)); assertTrue(g.mayTry(30_000))
        g.failed(30_000)
        assertFalse(g.mayTry(30_000 + 59_999)); assertTrue(g.mayTry(30_000 + 60_000))
        g.succeeded(); assertTrue(g.mayTry(30_001))
    }

    // #1521 status is a LIVE round trip: id -u == 2000 now, never the channel's ready flag.
    @Test fun statusIsGreenOnlyOnASuccessfulLiveRoundTrip() {
        val ch = FakeChannel(ready = true) { "2000\n" }
        val s = core(ch).status()
        assertTrue(s.armed); assertEquals("fake", s.channel); assertEquals(1_000L, s.checkedAt)
        assertEquals(listOf("id -u"), ch.ran)
    }

    @Test fun aConnectedFlagWithADeadLinkIsRed() {
        val s = core(FakeChannel(ready = true) { null }).status()
        assertFalse(s.armed); assertTrue(s.reason.contains("connection refused"))
    }

    @Test fun aWrongUidIsRed() {
        val s = core(FakeChannel(ready = true) { "10123\n" }).status()
        assertFalse(s.armed); assertTrue(s.reason.contains("10123"))
    }

    @Test fun anUnreadyChannelIsRedWithItsReasonAndNeverRunsAnything() {
        val ch = object : BrokerChannel {
            var ran = false
            override fun name() = "x"; override fun ready() = false
            override fun run(cmd: String, stdin: File?): String? { ran = true; return "2000" }
            override fun whyDown() = "wireless debugging is off"
        }
        val s = core(ch).status()
        assertFalse(s.armed); assertEquals("wireless debugging is off", s.reason); assertFalse(ch.ran)
    }

    @Test fun aHangingChannelTimesOutRed() {
        val s = core(FakeChannel(ready = true) { Thread.sleep(6_000); "2000" }).status()
        assertFalse(s.armed); assertTrue(s.reason.contains("timeout"))
    }

    @Test fun theVerdictIsCachedForAFewSecondsOnly() {
        var now = 1_000L
        val ch = FakeChannel(ready = true) { "2000" }
        val c = core(ch, now = { now })
        c.status(); c.status()
        assertEquals(1, ch.ran.size)
        now += BrokerCore.STATUS_TTL_MS
        c.status()
        assertEquals(2, ch.ran.size)
    }

    @Test fun aFlipOfTheVerdictCallsTheChangeCallback() {
        var now = 1_000L; var up = true
        val c = core(FakeChannel(ready = true) { if (up) "2000" else null }, now = { now })
        var seen: Boolean? = null
        c.onChange = { seen = it.armed }
        c.status(); assertEquals(null, seen)
        now += 10_000; up = false; c.status()
        assertEquals(false, seen)
    }
}

package com.diegonmarcos.superapp.shellclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellClientTest {
    private fun client(answer: (String, String, Map<String, String>) -> String?) =
        ShellClient { m, c, e -> answer(m, c, e) }

    @Test fun okRoundTrip() {
        val r = ShellWire.decode(ShellWire.encode(ShellResult.Ok(0, "out", "err", "embedded-adb")))
        assertEquals(ShellResult.Ok(0, "out", "err", "embedded-adb"), r)
    }

    @Test fun unarmedBrokerIsNeedsArm() {
        val c = client { _, _, _ -> ShellWire.encode(ShellResult.NeedsArm) }
        assertSame(ShellResult.NeedsArm, c.exec("dumpsys battery"))
    }

    @Test fun deniedCarriesTheReason() {
        val c = client { _, _, _ -> ShellWire.encode(ShellResult.Denied("no allowlist entry")) }
        assertEquals(ShellResult.Denied("no allowlist entry"), c.exec("rm -rf /"))
    }

    @Test fun blankCommandNeverReachesTheBroker() {
        var called = false
        val c = client { _, _, _ -> called = true; null }
        assertTrue(c.exec("  ") is ShellResult.Denied)
        assertFalse(called)
    }

    @Test fun unreachableBrokerIsFailedNotAThrow() {
        assertTrue(client { _, _, _ -> null }.exec("id") is ShellResult.Failed)
        assertTrue(client { _, _, _ -> throw IllegalStateException("boom") }.exec("id") is ShellResult.Failed)
    }

    @Test fun everyCallCarriesAClampedTimeout() {
        var seen = ""
        val c = client { _, _, e -> seen = e[ShellWire.K_TIMEOUT]!!; ShellWire.encode(ShellResult.Ok(0, "", "", "x")) }
        c.exec("id", timeoutMs = 10_000_000L)
        assertEquals(ShellWire.MAX_TIMEOUT_MS.toString(), seen)
        c.exec("id", timeoutMs = 1L)
        assertEquals("1000", seen)
    }

    @Test fun aSlowBrokerTimesOutOnTheClientToo() {
        val c = client { _, _, _ -> Thread.sleep(6_000); null }
        val r = c.exec("id", timeoutMs = 1_000L)
        assertTrue(r is ShellResult.Failed && r.timedOut)
    }

    @Test fun streamStopsEarlyAndIsBounded() {
        var asked = 0
        val c = client { _, _, e -> asked = e[ShellWire.K_MAX_BYTES]!!.toInt(); ShellWire.encode(ShellResult.Ok(0, "a\nb\nc", "", "x")) }
        val got = ArrayList<String>()
        c.stream("logcat -d", maxBytes = 10_000_000) { got += it; got.size < 2 }
        assertEquals(listOf("a", "b"), got)
        assertEquals(ShellWire.EXEC_MAX_BYTES, asked)
    }

    @Test fun statusWhenTheBrokerIsGone() {
        val s = client { _, _, _ -> null }.status()
        assertFalse(s.brokerInstalled); assertFalse(s.armed)
    }

    @Test fun statusRoundTrip() {
        val c = client { _, _, _ -> ShellWire.encodeStatus(ShellStatus(true, true, "embedded-adb", "ready", 5_000L)) }
        assertTrue(c.status().armed)
        assertEquals("embedded-adb", c.status().channel)
    }

    @Test fun garbageIsFailed() {
        assertTrue(ShellWire.decode("not json") is ShellResult.Failed)
        assertTrue(ShellWire.decode(null) is ShellResult.Failed)
    }

    @Test fun anOldBrokerWithoutALiveCheckIsNeverGreen() {
        val c = client { _, _, _ -> """{"brokerInstalled":true,"armed":true,"channel":"embedded-adb","detail":"armed"}""" }
        val s = c.status()
        assertFalse(s.armed)
        assertTrue(s.line().startsWith("ADB shell: down"))
    }

    @Test fun downCarriesTheReasonAndTheCheckTime() {
        val c = client { _, _, _ -> ShellWire.encodeStatus(ShellStatus(true, false, "none", "d", 1_700_000_000_000L, "wireless debugging is off")) }
        val s = c.status()
        assertFalse(s.armed)
        assertEquals("wireless debugging is off", s.reason)
        assertTrue(s.line().contains("wireless debugging is off") && s.line().contains("checked"))
    }

    @Test fun greenLineOnlyWhenArmedAndItShowsTheCheckTime() {
        val s = ShellStatus(true, true, "embedded-adb", "", 1_700_000_000_000L)
        assertTrue(s.line().startsWith("ADB shell: up via embedded-adb") && s.line().contains("checked"))
    }

    @Test fun brokerUnreachableIsDownWithAReason() {
        val s = client { _, _, _ -> null }.status()
        assertFalse(s.armed)
        assertTrue(s.reason.contains("unreachable") && s.checkedAt > 0)
    }
}

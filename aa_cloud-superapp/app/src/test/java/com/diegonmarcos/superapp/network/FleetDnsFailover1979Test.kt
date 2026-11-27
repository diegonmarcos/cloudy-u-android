package com.diegonmarcos.superapp.network

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1979 Health-driven DNS failover of the system resolver: 3 misses in a row move the VPN's DNS to the public resolvers, 3
 * answers in a row move it back, a switch is held back within the rate limit, and the status text tells the real mode.
 * Thresholds, interval, probe name and rate limit come from build.json::ui.dns.failover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetDnsFailover1979Test {
    private val d = FleetDns.decl
    private val f = d.failover
    private val FLEET = listOf("10.0.0.1", "10.1.0.1")
    private fun fo() = FleetDns.DnsFailover(f.failAfter, f.recoverAfter, f.minReapplyS * 1000L)
    private val gap = f.intervalS * 1000L

    @Test fun theDeclarationIsThe30sProbeAnd3And3And2Minutes() {
        assertEquals(30, f.intervalS); assertEquals(800, f.timeoutMs); assertEquals(3, f.failAfter); assertEquals(3, f.recoverAfter); assertEquals(120, f.minReapplyS)
        assertTrue(f.probeName.endsWith(".mesh.diegonmarcos.com") && FleetDns.isMeshName(d, f.probeName))
    }

    @Test fun failsOverToPublicDnsAfterExactlyThreeMisses() {
        val x = fo(); var t = 1_000_000L
        assertFalse(x.observe(false, t)); t += gap
        assertFalse(x.observe(false, t)); t += gap
        assertFalse("still mesh DNS after two misses", x.publicMode)
        assertTrue("the third miss switches", x.observe(false, t))
        assertTrue(x.publicMode)
        assertEquals(d.publicResolvers, FleetDns.vpnServers(d, "private_only", emptyList(), FLEET, true, x.publicMode))
        assertEquals(FLEET, FleetDns.vpnServers(d, "private_only", emptyList(), FLEET, true, false))
    }

    @Test fun anAnswerInTheMiddleResetsTheMissStreak() {
        val x = fo(); var t = 1_000_000L
        for (ok in listOf(false, false, true, false, false)) { x.observe(ok, t); t += gap }
        assertFalse(x.publicMode)
    }

    @Test fun recoversToMeshDnsAfterThreeAnswersOnceTheRateLimitHasPassed() {
        val x = fo(); var t = 1_000_000L
        repeat(3) { x.observe(false, t); t += gap }
        assertTrue(x.publicMode)
        t += f.minReapplyS * 1000L
        assertFalse(x.observe(true, t)); t += gap
        assertFalse(x.observe(true, t)); t += gap
        assertTrue(x.publicMode)
        assertTrue(x.observe(true, t))
        assertFalse(x.publicMode)
    }

    @Test fun aSwitchIsHeldBackWithinTheRateLimitAndTakenOnTheNextProbeAfterIt() {
        val x = fo(); val t0 = 5_000_000L
        repeat(3) { x.observe(false, t0 + it * gap) }
        assertTrue(x.publicMode)
        val t1 = t0 + 2 * gap // the switch happened here
        // three answers within 120 s of the switch: the streak is complete but the switch waits
        assertFalse(x.observe(true, t1 + 5_000)); assertFalse(x.observe(true, t1 + 35_000))
        assertFalse("rate limited", x.observe(true, t1 + 65_000)); assertTrue(x.publicMode)
        assertFalse(x.observe(true, t1 + 95_000)); assertTrue(x.publicMode)
        assertTrue("first probe after 2 min", x.observe(true, t1 + f.minReapplyS * 1000L + 1)); assertFalse(x.publicMode)
    }

    @Test fun theStatusTextSaysTheRealMode() {
        val x = fo()
        assertEquals("mesh DNS", x.text { "never" })
        var t = 1_000_000L
        repeat(3) { x.observe(false, t); t += gap }
        assertEquals("public DNS: hub resolver unreachable since 12:34", x.text { "12:34" })
        t += f.minReapplyS * 1000L
        repeat(3) { x.observe(true, t); t += gap }
        assertEquals("mesh DNS", x.text { "never" })
    }

    @Test fun theVerdictAcceptsThePublicListWhileFailedOver() {
        assertEquals(d.publicResolvers, FleetDns.promised(d, "private_only", emptyList(), true, FLEET, true, true, true))
        assertEquals(FLEET, FleetDns.promised(d, "private_only", emptyList(), true, FLEET, true, true, false))
    }
}

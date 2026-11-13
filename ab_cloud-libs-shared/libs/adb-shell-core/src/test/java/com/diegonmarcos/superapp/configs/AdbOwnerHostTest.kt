package com.diegonmarcos.superapp.configs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1682 P3 step 4d: the host hooks start inert (flag undeclared, profiler a plain call) and an installed host replaces them. */
class AdbOwnerHostTest {
    @After fun reset() {
        AdbOwnerHost.declaredFlag = { _, _ -> false }
        AdbOwnerHost.profiler = { _, _, block -> block() }
        AdbOwnerHost.plane = AdbOwnerHost.NoPlane
    }

    @Test fun defaultsAreInert() {
        assertFalse(AdbOwnerHost.declaredFlag("wireless_debugging_keepalive", "default_on"))
        var ran = 0
        AdbOwnerHost.profile("system", "wdkeeper") { ran++ }
        assertEquals(1, ran)
    }

    @Test fun timedKeepsTheResultAndSurvivesABrokenProfiler() {
        assertEquals(7, AdbOwnerHost.timed("e", "p") { 7 })
        AdbOwnerHost.profiler = { _, _, _ -> error("profiler down") }
        assertEquals(8, AdbOwnerHost.timed("e", "p") { 8 })
    }

    @Test fun defaultPlaneIsInert() = assertSame(AdbOwnerHost.NoPlane, AdbOwnerHost.plane)

    @Test fun installedHostReplacesTheDefaults() {
        AdbOwnerHost.declaredFlag = { id, flag -> id == "x" && flag == "default_on" }
        val seen = ArrayList<String>()
        AdbOwnerHost.profiler = { e, p, block -> seen += "$e.$p"; block() }
        assertTrue(AdbOwnerHost.declaredFlag("x", "default_on"))
        assertFalse(AdbOwnerHost.declaredFlag("y", "default_on"))
        AdbOwnerHost.profile("system", "wdkeeper") {}
        assertEquals(listOf("system.wdkeeper"), seen)
    }
}

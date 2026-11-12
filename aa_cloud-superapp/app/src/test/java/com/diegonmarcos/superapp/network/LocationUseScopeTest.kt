package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.bluetooth.BtLifecycle
import com.diegonmarcos.superapp.perms.LocationUse
import com.diegonmarcos.superapp.wifi.WifiLifecycle
import com.diegonmarcos.superapp.xray.LocationUseView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1666 The lifecycle rules behind "a location-class call runs only while its own screen is visible, released on leave", the
 * ledger's round trip and the X-Ray system view. The static guard (test/test-location-use-scoped.sh) proves every call site is
 * owned by one of these scopes; this proves the scopes themselves.
 */
class LocationUseScopeTest {

    @Test fun `Wi-Fi page attaches once while started and detaches on stop and on leave`() {
        var attach = 0; var detach = 0
        val s = WifiLifecycle.Scope({ attach++ }, { detach++ })
        s.started(); s.started()
        assertEquals("one attach however often ON_START repeats", 1, attach)
        assertTrue(s.attached)
        s.stopped()
        assertEquals("ON_STOP (app to the background with the page open) detaches", 1, detach)
        assertFalse(s.attached)
        s.started(); s.left()
        assertEquals("coming back re-attaches", 2, attach)
        assertEquals("leaving the page detaches", 2, detach)
        s.left(); s.stopped()
        assertEquals("no double detach", 2, detach)
    }

    @Test fun `Wi-Fi page left while stopped does not detach twice`() {
        var detach = 0
        val s = WifiLifecycle.Scope({}, { detach++ })
        s.started(); s.stopped(); s.left()
        assertEquals(1, detach)
    }

    @Test fun `Bluetooth release stops the LE scan and the classic inquiry`() {
        val calls = mutableListOf<String>()
        BtLifecycle.release(object : BtLifecycle.BleScanOwner {
            override fun stopScan() { calls += "scan" }
            override fun stopDiscovery() { calls += "discovery" }
        })
        assertEquals(listOf("scan", "discovery"), calls)
    }

    @Test fun `the ledger round-trips one entry per call site`() {
        val raw = LocationUse.encode(1_790_000_000_000L, "WifiManager.startScan", LocationUse.Trigger.SCREEN)
        val e = LocationUse.decode("libs:wifi", raw)!!
        assertEquals(1_790_000_000_000L, e.atMs)
        assertEquals("WifiManager.startScan", e.api)
        assertEquals(LocationUse.Trigger.SCREEN, e.trigger)
        assertNull(LocationUse.decode("x", null))
        assertNull(LocationUse.decode("x", "garbage"))
        assertNull(LocationUse.decode("x", "notanumber\u001fa\u001fb"))
    }

    @Test fun `X-Ray reads the location app-ops and flags a running one`() {
        val out = """
            Uid mode: COARSE_LOCATION: foreground
            FINE_LOCATION: allow; time=+2m3s12ms ago; duration=+1s
            COARSE_LOCATION: allow; time=+40s ago; duration=-1ms (running)
            RUN_IN_BACKGROUND: allow
        """.trimIndent()
        val ops = LocationUseView.opsLines(out)
        assertEquals(2, ops.size)
        assertTrue(LocationUseView.running(ops[1]))
        assertFalse(LocationUseView.running(ops[0]))
        assertEquals("RUNNING COARSE_LOCATION · accessed FINE_LOCATION COARSE_LOCATION", LocationUseView.summary(out))
        assertEquals("no location op recorded", LocationUseView.summary(null))
        assertEquals("one appops get per op", LocationUseView.OPS.size, LocationUseView.APPOPS_CMD.split("; ").size)
        assertTrue(LocationUseView.ledgerText(emptyList()).startsWith("no location-class access"))
    }
}

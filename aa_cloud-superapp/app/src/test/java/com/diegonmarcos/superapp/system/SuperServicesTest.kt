package com.diegonmarcos.superapp.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1689 after entering the mode no SuperApp foreground service remains except the declared keep ones; restore is exact. */
class SuperServicesTest {
    private val decl: List<SuperServices.Decl> by lazy {
        val groups = JSONObject(File("build.json").readText()).getJSONObject("ui").getJSONObject("control_panel").getJSONArray("groups")
        val c = (0 until groups.length()).flatMap { g -> groups.getJSONObject(g).optJSONArray("controls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .single { it.optString("id") == "super_power_saving" }
        val a = c.getJSONArray("services")
        (0 until a.length()).map { a.getJSONObject(it) }.map { SuperServices.Decl(it.getString("class"), it.getString("label"), it.optBoolean("adb_last"), it.optBoolean("keep")) }
    }

    @Test fun `every requested service is declared and exists`() {
        val names = decl.map { it.cls.substringAfterLast('.') }
        for (n in listOf("FloatingNavService", "ScreensaverService", "KdeStatusService", "NetworkBadgeService", "HostShellService", "WdKeeperService",
                "AdbPairingService", "ClockService", "LocationTrackerService", "GhLoginKeeper", "LightService", "DlService", "BatchForegroundService", "FirewallVpnService"))
            assertTrue(n, n in names)
        assertTrue(decl.filter { it.keep }.all { it.cls.contains("firewall") })
    }

    @Test fun `entering stops everything running but the kept VPN, ADB keepers last`() {
        val running = decl.map { it.cls }.toSet() + "com.diegonmarcos.superapp.Undeclared"
        val (stop, kept) = SuperServices.stopPlan(running, decl, stopKept = false)
        assertEquals(running, (stop + kept).toSet())
        assertTrue(kept.all { c -> decl.single { it.cls == c }.keep })
        val firstAdb = stop.indexOfFirst { c -> decl.any { it.cls == c && it.adbLast } }
        assertTrue(stop.drop(firstAdb).all { c -> decl.any { it.cls == c && it.adbLast } })
        val (all, none) = SuperServices.stopPlan(running, decl, stopKept = true)
        assertEquals(running, all.toSet()); assertTrue(none.isEmpty())
    }

    @Test fun `only running ones are remembered and restored, ADB first`() {
        val running = setOf("com.diegonmarcos.superapp.floatingnav.FloatingNavService", "com.diegonmarcos.superapp.adbdebug.WdKeeperService")
        val (stop, _) = SuperServices.stopPlan(running, decl, false)
        assertEquals(running, stop.toSet())
        assertEquals("com.diegonmarcos.superapp.adbdebug.WdKeeperService", SuperServices.restoreOrder(stop.toSet(), decl).first())
    }
}

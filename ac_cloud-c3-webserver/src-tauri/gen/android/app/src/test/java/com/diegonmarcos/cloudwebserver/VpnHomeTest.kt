package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.cloudwebserver.ui.AdbLive
import com.diegonmarcos.cloudwebserver.ui.HomeData
import com.diegonmarcos.cloudwebserver.ui.HomeModel
import com.diegonmarcos.cloudwebserver.ui.HomeReport
import com.diegonmarcos.cloudwebserver.ui.VpnMeshDecl
import com.diegonmarcos.cloudwebserver.ui.VpnMeshReading
import com.diegonmarcos.cloudwebserver.ui.VpnModel
import com.diegonmarcos.cloudwebserver.ui.VpnPeer
import com.diegonmarcos.cloudwebserver.ui.VpnReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1845 the VPN table's rows, the Summary causes and the Markdown export. */
class VpnHomeTest {
    private val m1 = VpnMeshDecl("wg0", "Mesh-1", "10.0.0.0/24", "10.0.0.1", "10.0.0.1", "c3-infra-api.mesh.diegonmarcos.com", "https://c3-infra-api.mesh.diegonmarcos.com/")
    private val m2 = VpnMeshDecl("wg-public", "Mesh-2", "10.1.0.0/24", "10.1.0.1", "10.1.0.1", "maddy.mesh.diegonmarcos.com", "")
    private val now = 1_000_000_000L
    private fun reading(up: Boolean = true, hub2: Boolean = true) = VpnReading(
        true, "config-v4-split", if (up) "UP" else "DOWN", "1.2", true, false,
        listOf(VpnPeer("a", now - 42_000, 1, 2), VpnPeer("b", now - 30_000, 3, 4)), "off", "", listOf("10.0.0.1", "10.1.0.1"), "tun0",
        listOf(
            VpnMeshReading(m1, "10.0.0.10", true, true, true, "TCP answers in 12 ms", true, "resolves to 10.0.0.6"),
            VpnMeshReading(m2, "10.1.0.12", true, true, hub2, if (hub2) "TCP answers in 9 ms" else "SocketTimeoutException", true, "resolves to 10.0.0.3"),
        ), now)

    @Test fun `the table has the tunnel rows and the same five rows for each mesh`() {
        val keys = VpnModel.allRows(reading()).map { it.key }
        for (k in listOf("Tunnel", "Handshakes", "Android DNS", "Active path", "Fallbacks", "Always-on")) assertTrue(k, k in keys)
        for (l in listOf("Mesh-1", "Mesh-2")) for (r in listOf("interface", "route", "mesh DNS", "hub IP", "private name")) assertTrue("$l $r", "$l $r" in keys)
        assertEquals("2 of 2 fresh · peer-1 42s · peer-2 30s", VpnModel.tunnelRows(reading()).first { it.key == "Handshakes" }.value)
    }

    @Test fun `a failing hub turns the badge to ATTENTION and a down tunnel to DOWN, never a guess`() {
        assertEquals("UP" to true, VpnModel.verdict(reading()))
        assertEquals("ATTENTION" to false, VpnModel.verdict(reading(hub2 = false)))
        assertEquals("DOWN" to false, VpnModel.verdict(reading(up = false)))
        assertEquals("?" to null, VpnModel.verdict(null))
        assertEquals(false, VpnModel.meshRows(reading(hub2 = false).meshes[1]).first { it.key == "Mesh-2 hub IP" }.up)
    }

    @Test fun `a stale handshake is not fresh and an absent engine is named`() {
        assertFalse(VpnModel.fresh(VpnPeer("a", now - 400_000, 0, 0), now))
        assertFalse(VpnModel.fresh(VpnPeer("a", 0, 0, 0), now))
        assertEquals(false, VpnModel.tunnelRows(VpnReading(false, null, "DOWN", "-", null, null, emptyList(), "", "", emptyList(), null, emptyList(), now)).single().up)
    }

    @Test fun `cidr containment decides which address belongs to which mesh`() {
        assertTrue(VpnModel.cidrContains("10.0.0.0/24", "10.0.0.10")); assertFalse(VpnModel.cidrContains("10.0.0.0/24", "10.1.0.12"))
        assertTrue(VpnModel.cidrContains("10.1.0.0/24", "10.1.0.12")); assertFalse(VpnModel.cidrContains("bad", "10.1.0.12"))
    }

    private val pwOn = JSONObject("""{"server":true,"running":true,"keep_alive":true,"pid":1,"uptime_s":5,"ports":[{"name":"admin","port":8002,"proto":"tcp"},{"name":"diagnostics","port":8003,"proto":"tcp"},{"name":"mesh","port":8004,"proto":"tcp","addr":"10.0.0.10"},{"name":"mesh","port":8004,"proto":"tcp","addr":"10.1.0.12"}],"svc":{"pages":{"enabled":true},"api":{"enabled":true},"sshd":{"enabled":true}}}""")
    private val served = JSONObject("""{"rows":[{"name":"Termux","port":8000,"role":"external","serving":false},{"name":"Local","port":8002,"role":"drive","serving":true},{"name":"X-Ray Device","port":8003,"role":"diag","serving":true},{"name":"front-html","port":8100,"role":"place","serving":false}]}""")
    private val door = { v: String, reason: String -> """{"id":"d","title":"Nix","port":8024,"state":"listening","status":{"verdict":"$v","reason":"$reason","checks":[{"id":"process","ok":true}]}}""" }
    private val sshd = JSONObject("""{"doors":[${door("amber", "banner not read")},${door("red", "off")}]}""")
    private val adb = JSONObject("""{"server_up":false,"server_port":38099}""")
    private val live = AdbLive(true, true, false)

    @Test fun `with every service on, pages that are another app's or an unstarted site no longer make Pages unhealthy, and every cause is named`() {
        val rows = HomeModel.summaryRows(pwOn, JSONObject("""{"ok":true}"""), served, sshd, adb, live)
        val by = rows.associateBy { it.name }
        assertEquals(true, by["Pages"]!!.lights.healthy)
        assertEquals("", by["Pages"]!!.cause)
        assertTrue("the closed apps are named on the healthy row", by["Pages"]!!.msg.contains("Termux") && by["Pages"]!!.msg.contains("not a fault"))
        assertEquals(true, by["API"]!!.lights.healthy)
        assertEquals(false, by["SSHD"]!!.lights.healthy)
        assertTrue("reconciles ON with 0 doors up", by["SSHD"]!!.cause.contains("ON means a process answers") && by["SSHD"]!!.cause.contains("banner not read"))
        assertTrue(by["ADB Shell"]!!.cause.contains("38099") && by["ADB Shell"]!!.cause.contains("no echo"))
        assertTrue(HomeModel.attentionWhy(rows).let { it.contains("SSHD") && it.contains("ADB Shell") && !it.contains("Pages") })
    }

    @Test fun `a pages row this server serves that does not answer is still a fault, named`() {
        val bad = JSONObject("""{"rows":[{"name":"Local","port":8002,"role":"drive","serving":false}]}""")
        assertEquals(false, HomeModel.pages(pwOn, bad).healthy)
        assertTrue(HomeModel.pagesCause(bad).contains("Local :8002 (drive)"))
        assertTrue(HomeModel.apiCause(null).contains(":8003"))
    }

    @Test fun `the Markdown holds every section, the causes, the bound mesh addresses and the VPN rows`() {
        val h = HomeData(pwOn, JSONObject("""{"ok":true}"""), null, served, sshd, null, null, adb, null, null, live, {}, { _, _ -> }, {}, { _, _ -> }, {})
        val md = HomeReport.markdown(h, reading(hub2 = false), "exported test")
        for (s in listOf("# Cloud C3 Webserver / Home", "## Summary", "## Pages", "## API", "## SSHD", "## ADB Shell", "## Server", "## VPN (ATTENTION)")) assertTrue(s, s in md)
        assertTrue(md.contains("tcp 10.0.0.10:8004, tcp 10.1.0.12:8004"))
        assertTrue(md.contains("**Mesh-2 hub IP**") && md.contains("FAIL **Mesh-2 hub IP**"))
        assertTrue(md.contains("cause: "))
        assertFalse(md.contains("PrivateKey"))
    }
}

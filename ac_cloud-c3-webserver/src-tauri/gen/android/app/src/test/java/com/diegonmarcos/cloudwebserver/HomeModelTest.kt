package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.cloudwebserver.ui.AdbLive
import com.diegonmarcos.cloudwebserver.ui.HomeModel
import com.diegonmarcos.cloudwebserver.ui.Lights
import com.diegonmarcos.cloudwebserver.ui.SummaryState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1834 the Summary lights read the real state of each service: each of the three lights moves on its own, none is derived from another. */
class HomeModelTest {
    private fun pw(server: Boolean, running: Boolean, ka: Boolean) = JSONObject("""{"server":$server,"running":$running,"keep_alive":$ka}""")
    private fun served(vararg serving: Boolean) = JSONObject("""{"rows":[${serving.joinToString(",") { """{"name":"p","port":1,"serving":$it}""" }}]}""")
    private fun door(verdict: String, process: Boolean) = """{"id":"d","state":"listening","status":{"verdict":"$verdict","checks":[{"id":"process","ok":$process}]}}"""

    @Test fun `pages light one is a port answering, light three needs every port, keep-alive is its own switch`() {
        val l = HomeModel.pages(pw(true, true, false), served(true, false))
        assertEquals(true, l.on); assertEquals(false, l.keepAlive); assertEquals(false, l.healthy)
        val all = HomeModel.pages(pw(true, true, true), served(true, true))
        assertEquals(true, all.healthy); assertEquals(true, all.keepAlive)
        assertEquals(false, HomeModel.pages(pw(true, true, true), served(false)).on)
        assertNull(HomeModel.pages(null, null).on)
    }

    @Test fun `api healthy is the health route answer, not the server flag`() {
        assertEquals(false, HomeModel.api(pw(true, true, true), null).healthy)
        assertEquals(false, HomeModel.api(pw(true, true, true), JSONObject("""{"ok":false}""")).healthy)
        assertEquals(true, HomeModel.api(pw(true, true, false), JSONObject("""{"ok":true}""")).healthy)
        assertEquals(false, HomeModel.api(pw(false, false, true), null).on)
    }

    @Test fun `sshd on is the process check, healthy is every door green, keep-alive is the sshd switch`() {
        val d = JSONObject("""{"keep_alive":true,"doors":[${door("green", true)},${door("amber", true)}]}""")
        val l = HomeModel.sshd(d)
        assertEquals(true, l.on); assertEquals(true, l.keepAlive); assertEquals(false, l.healthy)
        assertEquals(false, HomeModel.sshd(JSONObject("""{"keep_alive":false,"doors":[${door("red", false)}]}""")).on)
        assertEquals(true, HomeModel.sshd(JSONObject("""{"doors":[${door("green", true)}]}""")).healthy)
    }

    @Test fun `adb lights come from the port probe, the keep-alive switch and the echo round trip separately`() {
        val up = JSONObject("""{"server_up":true,"server_port":38099}""")
        val l = HomeModel.adb(up, AdbLive(true, false, false))
        assertEquals(true, l.on); assertEquals(false, l.keepAlive); assertEquals(false, l.healthy)
        val none = HomeModel.adb(null, AdbLive(false, null, null))
        assertNull(none.on); assertNull(none.keepAlive); assertNull(none.healthy)
    }

    @Test fun `status messages name the live state`() {
        assertTrue(HomeModel.startStopMsg(pw(true, true, true), JSONObject("""{"server_up":false}""")).contains("ADB server not answering"))
        assertTrue(HomeModel.keepAliveMsg(pw(true, true, false), AdbLive(true, true, null)).contains("Keep alive OFF"))
        assertTrue(HomeModel.keepAliveMsg(pw(true, true, false), AdbLive(false, null, null)).contains("n/a here"))
        assertTrue(HomeModel.probeMsg(served(true, false), null, null, null, AdbLive(false, null, null)).startsWith("Pages 1/2 serving"))
    }

    // #1836 per-service enable flags: Server is the master, each service has its own flag, Stop on one leaves the others serving
    private fun pws(server: Boolean, pages: Boolean, api: Boolean, sshd: Boolean, ports: String = "") = JSONObject(
        """{"server":$server,"running":$server,"keep_alive":true,"svc":{"pages":{"enabled":$pages},"api":{"enabled":$api},"sshd":{"enabled":$sshd}},"ports":[$ports]}""")

    @Test fun `a service is on only while the server and its own flag are on, and an old status without svc means enabled`() {
        assertEquals(true, HomeModel.svcOn(pws(true, true, true, true), "api"))
        assertEquals(false, HomeModel.svcOn(pws(true, true, false, true), "api"))
        assertEquals(true, HomeModel.svcOn(pws(true, true, false, true), "pages"))
        assertEquals(false, HomeModel.svcOn(pws(false, true, true, true), "pages"))
        assertEquals(true, HomeModel.svcOn(pw(true, true, true), "sshd"))
        assertNull(HomeModel.svcOn(null, "api"))
    }

    @Test fun `stopping API leaves the Pages and SSHD lights on, and the other way round`() {
        val served = served(true); val door = JSONObject("""{"doors":[${door("green", true)}]}""")
        val apiOff = pws(true, true, false, true)
        assertNull(HomeModel.api(apiOff, JSONObject("""{"ok":true}""")).on)
        assertEquals(true, HomeModel.api(apiOff, JSONObject("""{"ok":true}""")).disabled)
        assertEquals(true, HomeModel.pages(apiOff, served).on)
        assertEquals(true, HomeModel.sshd(door, apiOff).on)
        val pagesOff = pws(true, false, true, true)
        assertNull(HomeModel.pages(pagesOff, served).on)
        assertEquals(true, HomeModel.pages(pagesOff, served).disabled)
        assertEquals(true, HomeModel.api(pagesOff, JSONObject("""{"ok":true}""")).healthy)
        assertEquals(true, HomeModel.sshd(door, pws(true, true, true, false)).disabled)
    }

    @Test fun `a service switch posts only its own flag, and Start also turns the server on`() {
        val stop = HomeModel.svcSet("api", false)
        assertEquals(false, stop.getBoolean("svc_api")); assertTrue(!stop.has("server")); assertTrue(!stop.has("svc_pages"))
        val start = HomeModel.svcSet("pages", true)
        assertEquals(true, start.getBoolean("svc_pages")); assertEquals(true, start.getBoolean("server"))
    }

    @Test fun `every service table gets the same status rows in the same order, read from the live state`() {
        val keys = listOf("Service status", "Enabled", "Server (master)", "Listening on", "Probe", "Keep-alive")
        val on = Lights(true, true, true)
        for (rows in listOf(HomeModel.statusRows(true, true, on, "tcp 1", "ok"), HomeModel.statusRows(null, true, on, "tcp 2", "echo"), HomeModel.statusRows(false, true, Lights(false, true, false), "tcp 3", "x"))) {
            assertEquals(keys, rows.map { it.key })
        }
        val off = HomeModel.statusRows(false, true, Lights(false, true, false), "tcp 3", "x")
        assertTrue(off[0].value.startsWith("stopped (service disabled")); assertEquals(false, off[1].up); assertEquals(true, off[2].up)
        assertTrue(HomeModel.statusRows(true, false, Lights(false, null, false), "", "x")[0].value.contains("Server OFF"))
        assertEquals("none", HomeModel.statusRows(true, true, on, "", "ok")[3].value)
    }

    @Test fun `the server light needs the admin and diagnostics listeners bound`() {
        val ports = """{"name":"admin","port":8002,"proto":"tcp"},{"name":"diagnostics","port":8003,"proto":"tcp"}"""
        val l = HomeModel.server(pws(true, true, true, true, ports))
        assertEquals(true, l.on); assertEquals(true, l.keepAlive); assertEquals(true, l.healthy)
        assertEquals(false, HomeModel.server(pws(true, true, true, true, """{"name":"admin","port":8002,"proto":"tcp"}""")).healthy)
        assertEquals(false, HomeModel.server(pws(false, true, true, true)).on)   // Keep alive ON + Server OFF is a fault, not a choice
        assertNull(HomeModel.server(null).on)
        assertEquals("tcp 8002, tcp 8003", HomeModel.portsOf(pws(true, true, true, true, ports), "admin", "diagnostics"))
        assertEquals("Pages ON · API OFF · SSHD ON", HomeModel.servicesMsg(pws(true, true, false, true)))
    }

    @Test fun `#1845 the mesh listener shows one port entry per bound mesh address`() {
        val ports = """{"name":"mesh","port":8004,"proto":"tcp","addr":"10.0.0.10"},{"name":"mesh","port":8004,"proto":"tcp","addr":"10.1.0.12"}"""
        assertEquals("tcp 10.0.0.10:8004, tcp 10.1.0.12:8004", HomeModel.portsOf(pws(true, true, true, true, ports), "mesh"))
        assertEquals("", HomeModel.portsOf(pws(true, true, true, true), "mesh"))   // nothing bound: nothing claimed
    }

    private fun pwk(server: Boolean, ka: Boolean, pages: Boolean = true) = JSONObject("""{"server":$server,"running":$server,"keep_alive":$ka,"svc":{"pages":{"enabled":$pages}}}""")
    private val up = Lights(true, true, true)

    @Test fun `a disabled service is neutral and does not raise ATTENTION, an enabled unhealthy one does`() {
        val p = pwk(true, true, pages = false)
        val pages = HomeModel.pages(p, served(true))
        assertTrue(pages.disabled)
        assertEquals(SummaryState.ALL_UP, HomeModel.summary(p, listOf(pages, up, up), null))
        assertEquals(SummaryState.ALL_DISABLED, HomeModel.summary(p, listOf(pages, pages, pages), null))
        assertEquals(SummaryState.ATTENTION, HomeModel.summary(p, listOf(pages, Lights(true, true, false), up), null))
        assertEquals(SummaryState.ATTENTION, HomeModel.summary(pwk(true, true), listOf(up, up, up), false))
        assertEquals(SummaryState.ALL_UP, HomeModel.summary(pwk(true, true), listOf(up, up, up), true))
    }

    @Test fun `server off by choice is a neutral Stopped, but a fault while Keep alive wants it up`() {
        val choice = pwk(false, false)
        assertEquals(SummaryState.STOPPED, HomeModel.summary(choice, listOf(HomeModel.pages(choice, served(false)), HomeModel.api(choice, null), HomeModel.sshd(null, choice)), null))
        assertTrue(HomeModel.server(choice).disabled); assertTrue(HomeModel.api(choice, null).disabled)
        val fault = pwk(false, true)
        assertEquals(SummaryState.ATTENTION, HomeModel.summary(fault, listOf(HomeModel.pages(fault, served(false))), null))
        assertTrue(!HomeModel.server(fault).disabled); assertTrue(!HomeModel.api(fault, null).disabled)
        assertEquals(SummaryState.UNKNOWN, HomeModel.summary(null, emptyList(), null))
    }
}

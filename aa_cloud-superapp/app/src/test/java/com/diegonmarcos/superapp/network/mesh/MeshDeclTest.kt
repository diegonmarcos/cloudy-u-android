package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import com.diegonmarcos.superapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #877 The declaration this build baked (build.json::ui.mesh_page) is whole and every control is wired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshDeclTest {
    private val d = MeshDecl.baked
    private val ids = MeshStore(FakePort(), d, FakeHost()).engineIds

    @Test fun `the strip has the six pages, the default one among them`() {
        assertEquals(listOf("status", "peers", "profiles", "routes", "controls", "log"), d.pages.map { it.id })
        assertTrue(d.page(d.defaultPage) != null)
        assertEquals(d.defaultPage, d.startPage.id)
    }

    @Test fun `three tabs - Status, Profile, Logs - cover every section exactly once`() {
        assertEquals(listOf("status", "profile", "logs"), d.tabs.map { it.id })
        assertEquals("status", d.startTab.id)
        assertEquals(listOf("status", "routes", "peers"), d.tab("status")!!.sections)
        assertEquals("the profile table comes before every control", listOf("profiles", "controls"), d.tab("profile")!!.sections)
        assertEquals(listOf("log"), d.tab("logs")!!.sections)
        val placed = d.tabs.flatMap { it.sections }
        assertEquals("a section sits in one tab only", placed.size, placed.toSet().size)
        assertEquals("no section is left without a tab", d.pages.map { it.id }.toSet(), placed.toSet())
        for (id in placed) assertTrue("tab names undeclared section $id", d.page(id) != null)
    }

    @Test fun `the guard - every control the page had before is still declared and reachable from a tab`() {
        assertTrue("the guard list is empty", d.previousControls.size >= 36)
        val declared = d.pages.flatMap { it.controls }.map { it.id }.toSet()
        assertEquals("previous controls that are no longer declared", emptyList<String>(), d.previousControls.filter { it !in declared })
        assertEquals("previous controls no tab reaches", emptyList<String>(), d.previousControls.filter { it !in d.reachableControls })
        // every former Controls-page control sits in the Profile tab, below the profile table
        for (c in d.page("controls")!!.controls) assertTrue("${c.id} is not on the Profile tab", c.id in d.tab("profile")!!.sections.flatMap { s -> d.page(s)!!.controls.map { it.id } })
    }

    @Test fun `the guard names the exact controls the page shipped with`() {
        assertEquals(
            listOf("peer_sort", "peer_add", "peer_remove", "peer_copy", "provider"),
            d.previousControls.take(5),
        )
        for (id in listOf("connect", "reconnect", "auto_boot", "auto_wifi", "auto_mobile", "kill_switch", "vpn_settings", "dns_preset", "mtu", "keepalive",
            "tunnel_name", "address", "listen_port", "private_key", "generate_key", "transport_mode", "relay_key", "test_fallbacks", "log_copy", "log_clear", "excluded_apps", "route_filter"))
            assertTrue("$id missing from the guard", id in d.previousControls)
    }

    @Test fun `cloudflare is declared - warp ready, one waiting on the zero trust apply`() {
        val cf = d.cloudflare
        assertEquals("engage.cloudflareclient.com:2408", cf.warp.endpoint)
        assertEquals("diegonmarcos", cf.one.team); assertEquals("diegonmarcos.cloudflareaccess.com", cf.one.loginHost)
        assertTrue("One stays off until #994 is applied", !cf.one.enabled); assertEquals("#994", cf.one.waitingOn)
        for (id in listOf("cf_warp_register", "cf_warp_forget", "cf_one_enrol")) assertTrue(id, d.control(id) != null)
    }

    @Test fun `a control acts through a real engine call or says why it cannot`() {
        for (c in d.pages.flatMap { it.controls }) {
            assertTrue("${c.id} declares neither an engine call nor an unsupported reason", c.engine.isNotBlank() || c.unsupported.isNotBlank())
            if (c.engine.isNotBlank()) assertTrue("${c.id} names engine call ${c.engine}, which the store does not dispatch", c.engine in ids)
        }
    }

    @Test fun `no dispatch entry is orphaned`() {
        val used = d.pages.flatMap { it.controls }.map { it.engine }.toSet()
        assertEquals("engine calls no control names: ${ids - used}", emptySet<String>(), ids - used)
    }

    @Test fun `#1120 the four start-up controls are honoured - each acts through an engine call, none is greyed out for good`() {
        // They were declared `unsupported` with no engine call (the kill switch a read-only one): drawn disabled for ever.
        val start = listOf("auto_boot", "auto_wifi", "auto_mobile", "kill_switch")
        assertEquals(setOf("startup.boot", "startup.wifi", "startup.mobile", "lockdown.set"), start.map { d.control(it)!!.engine }.toSet())
        assertEquals("no control is declared unsupported any more", emptyList<String>(), d.pages.flatMap { it.controls }.filter { !it.honoured }.map { it.id })
        for (id in listOf("auto_boot", "auto_wifi", "auto_mobile")) assertEquals("$id defaults on (connected by default)", "true", d.control(id)!!.default)
    }

    @Test fun `#1120 Status opens with the VPN row - Connect, Disconnect, Reconnect and the Always-on switch`() {
        // #1797 the lights lead, then the tunnel state and the VPN row; Copy all / Export all (#1153) sit above the breakdown
        val rows = d.page("status")!!.rows
        assertEquals(listOf("lights", "state", "vpn"), rows.take(3))
        assertTrue(rows.indexOf("export") < rows.indexOf("interface"))
        // every #1120 / #1153 control, in its order, then the #1797 additions
        val cids = d.page("status")!!.controls.map { it.id }
        assertEquals(listOf("status_copy_all", "status_export_all", "status_export_md", "reach_run"), cids.take(4))
        assertEquals(listOf("vpn_connect", "vpn_disconnect", "vpn_reconnect", "vpn_always_on"), cids.takeLast(4))
        assertEquals(setOf("reach_meshes_run", "status_profiler_copy", "status_profiler_export", "status_log_open", "status_prober_run", "status_prober_cancel"), (cids - cids.take(4).toSet() - cids.takeLast(4).toSet()).toSet())
        assertEquals("alwayson.set", d.control("vpn_always_on")!!.engine)
        assertEquals("switch", d.control("vpn_always_on")!!.kind)
    }

    @Test fun `defaults resolve from their seeds and stay in range`() {
        val seeds = MeshDecl.seeds()
        val mtu = d.control("mtu")!!
        assertEquals(BuildConfig.UI_WG_INTERFACE_MTU, MeshDecl.defaultOf(mtu, seeds))
        assertTrue(MeshDecl.defaultOf(mtu, seeds).toLong() in mtu.min!!..mtu.max!!)
        assertEquals(BuildConfig.UI_WG_TUNNEL_NAME, MeshDecl.defaultOf(d.control("tunnel_name")!!, seeds))
        assertTrue(MeshDecl.defaultOf(d.control("dns_preset")!!, seeds).isNotBlank())
        val ka = d.control("keepalive")!!
        assertTrue(MeshDecl.defaultOf(ka, seeds).toLong() in ka.min!!..ka.max!!)
        for (c in d.pages.flatMap { it.controls }.filter { it.kind == "choice" && it.choicesFrom.isBlank() })
            assertTrue("${c.id}'s default is not one of its choices", c.default in c.choices)
    }

    @Test fun `every status row is declared once and the thresholds are ordered`() {
        val rows = d.page("status")!!.rows
        assertEquals(rows.size, rows.toSet().size)
        assertTrue(d.handshakeFreshS in 1 until d.handshakeStaleS)
        assertTrue(d.pollMs >= 250)
    }

    @Test fun `parse reads a hand-written declaration`() {
        val p = MeshDecl.parse("""{"default_page":"b","poll_ms":10,"pages":[{"id":"a","label":"A"},{"id":"b","controls":[{"id":"x","kind":"number","min":1,"max":9,"default":5,"engine":"e"}]}]}""")
        assertEquals("b", p.startPage.id); assertEquals(250L, p.pollMs)
        assertEquals("5", p.control("x")!!.default); assertEquals(9L, p.control("x")!!.max)
    }

    @Test fun `#1153 Reachability follows DNS and probes three declared mesh URLs`() {
        val rows = d.page("status")!!.rows
        assertEquals(rows.indexOf("dns") + 1, rows.indexOf("reachability"))
        // #1153 three, #1797 plus Vault, Chat, Mail and Drive, each labelled
        assertEquals(7, d.reachHosts.size)
        assertEquals(listOf("Authelia", "Gitea", "C3 Infra API", "Vault", "Chat", "Mail", "Drive"), d.reachHosts.map { d.reachLabels[it] })
        assertTrue(d.reachHosts.all { it.endsWith(".mesh.diegonmarcos.com") })
        assertTrue("an auth service is probed", d.reachHosts.any { it.startsWith("auth") })
        assertEquals("reach.run", d.control("reach_run")!!.engine)
    }

    @Test fun `#1153 the fleet topology has no show-hide switch any more`() {
        assertEquals(null, d.control("topology"))
        assertTrue("view.topology" !in ids)
        assertEquals("clipboard.all", d.control("status_copy_all")!!.engine)
        assertEquals("export.all", d.control("status_export_all")!!.engine)
        assertEquals("export.md", d.control("status_export_md")!!.engine)
        assertEquals(listOf("all", "warn", "error"), d.control("log_level")!!.choices)
        assertTrue("the log keeps a bounded ring of at least 500 lines", d.logCap >= 500)
    }
}

package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1928 FIELD PARITY of the VPN page after every tab became a strip of subpages (owner rule 16: a reorganisation moves a field, it never
 * drops one). Held with mutations that turn each check red:
 *  1. every control of `previous_controls` is listed by exactly one subpage ([controlProblems]);
 *  2. every row of the status page's `previous_rows` is in exactly one status section that a Status subpage draws ([rowProblems]);
 *  3. every former page id still opens the subpage that holds it (aliases + [MeshDecl.Decl.resolve]);
 *  4. the additions: the Copy / Export of each subpage, the endpoint in use per peer, the DNS servers in use, the Log export, the DNS link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshSubpages1928Test {

    companion object {
        /** What is wrong with [subs] as a partition of [previous] controls (empty = each is listed by exactly one subpage). */
        fun controlProblems(previous: List<String>, subs: List<MeshDecl.Subpage>): List<String> {
            val listed = subs.flatMap { it.controls }
            return previous.mapNotNull { c ->
                when (listed.count { it == c }) { 0 -> "control $c is on no subpage"; 1 -> null; else -> "control $c is on several subpages" }
            }
        }

        /** What is wrong with the status [sections] drawn by [subs] as a partition of [previous] rows. */
        fun rowProblems(previous: List<String>, sections: List<MeshDecl.Section>, subs: List<MeshDecl.Subpage>): List<String> {
            val drawn = subs.map { it.statusSection }.filter { it.isNotBlank() }
            val rows = sections.filter { it.id in drawn }.flatMap { it.rows } 
            return previous.mapNotNull { r ->
                when (rows.count { it == r }) { 0 -> "row $r is drawn by no subpage"; 1 -> null; else -> "row $r is drawn twice" }
            }
        }
    }

    private val decl = MeshDecl.baked
    private val subs get() = decl.tabs.flatMap { it.subpages }

    private fun rawStatus(): org.json.JSONObject {
        val raw = org.json.JSONObject(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.UI_MESH_PAGE_B64, android.util.Base64.DEFAULT)))
        val pages = raw.getJSONArray("pages")
        return (0 until pages.length()).map { pages.getJSONObject(it) }.single { it.getString("id") == "status" }
    }

    @Test fun `the tabs are strips of subpages with the declared names`() {
        assertEquals(listOf("overview", "interface", "peers", "routes", "probes"), decl.tab("status")!!.subpages.map { it.id })
        assertEquals(listOf("profiles", "import_export", "tunnel", "connection", "dns_transport", "cloudflare"), decl.tab("profile")!!.subpages.map { it.id })
        assertEquals(listOf("log"), decl.tab("logs")!!.subpages.map { it.id })
        assertEquals("subpage ids are unique", subs.size, subs.map { it.id }.toSet().size)
        assertEquals(listOf("Overview", "Interface", "Peers", "Routes", "Probes"), decl.tab("status")!!.subpages.map { it.label })
        assertEquals(listOf("Profiles", "Import-Export", "Tunnel", "Connection", "DNS & Transport", "Cloudflare"), decl.tab("profile")!!.subpages.map { it.label })
    }

    @Test fun `the Profile subpages list the controls of the ticket, each once`() {
        val p = decl.tab("profile")!!
        assertEquals(listOf("provider", "profile_select", "profile_diff"), p.subpage("profiles")!!.controls)
        assertEquals(listOf("profile_import_clipboard", "profile_import_vault", "conf_import", "profile_export_text", "profile_export_qr", "profile_export_folder", "conf_export"), p.subpage("import_export")!!.controls)
        assertEquals(listOf("tunnel_name", "address", "listen_port", "private_key", "generate_key", "mtu", "keepalive"), p.subpage("tunnel")!!.controls)
        assertEquals(listOf("connect", "reconnect", "auto_boot", "auto_wifi", "auto_mobile", "kill_switch", "vpn_settings"), p.subpage("connection")!!.controls)
        assertTrue("dns_preset, the link to Configs > Network > DNS, then the transport", p.subpage("dns_transport")!!.controls == listOf("dns_preset", "dns_fleet_link", "transport_mode", "relay_key", "test_fallbacks"))
        assertEquals(listOf("cf_warp_register", "cf_warp_forget", "cf_one_enrol"), p.subpage("cloudflare")!!.controls)
    }

    @Test fun `every previous control is on exactly one subpage`() {
        assertTrue(decl.previousControls.size >= 36)
        assertEquals(emptyList<String>(), controlProblems(decl.previousControls, subs))
        val declared = decl.pages.flatMap { it.controls }.map { it.id }.toSet()
        for (c in subs.flatMap { it.controls }) assertTrue("subpage lists undeclared control $c", c in declared)
    }

    @Test fun `mutation - a control dropped from a subpage, listed twice, or newly guarded turns the parity red`() {
        val dropped = subs.map { if (it.id == "tunnel") it.copy(controls = it.controls - "mtu") else it }
        assertTrue(controlProblems(decl.previousControls, dropped).any { "mtu" in it && "no subpage" in it })
        val twice = subs.map { if (it.id == "connection") it.copy(controls = it.controls + "mtu") else it }
        assertTrue(controlProblems(decl.previousControls, twice).any { "mtu" in it && "several" in it })
        assertTrue(controlProblems(decl.previousControls + "ghost", subs).any { "ghost" in it })
        val logless = subs.filter { it.id != "log" }
        assertTrue(controlProblems(decl.previousControls, logless).any { "log_copy" in it })
    }

    @Test fun `every previous status row is in exactly one section a Status subpage draws`() {
        val raw = rawStatus().getJSONArray("previous_rows").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(raw.size >= 14)
        assertEquals(emptyList<String>(), rowProblems(raw, decl.sections, decl.tab("status")!!.subpages))
        // the four sections the ticket names
        assertEquals(listOf("lights", "state", "vpn", "meshes", "export"), decl.sections.first { it.id == "overview" }.rows)
        assertEquals(listOf("profile", "interface", "pubkey", "transport", "endpoint", "dns", "cloudflare"), decl.sections.first { it.id == "interface" }.rows)
        assertEquals(listOf("peers", "handshake", "traffic", "latency"), decl.sections.first { it.id == "peers" }.rows)
        assertTrue(decl.sections.first { it.id == "probes" }.rows.containsAll(listOf("profiler", "reachability", "prober")))
    }

    @Test fun `mutation - a status subpage removed, or a row drawn twice, turns the row parity red`() {
        val raw = rawStatus().getJSONArray("previous_rows").let { a -> (0 until a.length()).map { a.getString(it) } }
        val st = decl.tab("status")!!.subpages
        assertTrue(rowProblems(raw, decl.sections, st.filter { it.id != "probes" }).any { "reachability" in it })
        val twice = decl.sections.map { if (it.id == "probes") it.copy(rows = it.rows + "dns") else it }
        assertTrue(rowProblems(raw, twice, st).any { "dns" in it && "twice" in it })
        assertTrue(rowProblems(raw, decl.sections.map { it.copy(rows = it.rows - "latency") }, st).any { "latency" in it })
    }

    @Test fun `every former page id still opens the subpage that holds it`() {
        for (p in decl.pages) {
            val (tab, sub) = decl.resolve(p.id) ?: error("page ${p.id} resolves to nothing")
            assertTrue("${p.id} opens a tab that draws it", p.id in tab.sections)
            assertTrue("${p.id} opens a subpage naming it", sub != null && p.id in sub.sections)
        }
        assertEquals("connection", decl.resolve("controls")!!.second!!.id)
        assertEquals("profiles", decl.resolve("profiles")!!.second!!.id)
        assertEquals("peers", decl.resolve("peers")!!.second!!.id)
        assertEquals("routes", decl.resolve("routes")!!.second!!.id)
        assertEquals("dns_transport", decl.resolve("dns_transport")!!.second!!.id)
        assertEquals("profile", decl.resolve("profile")!!.first.id)
        assertEquals(null, decl.resolve("no-such-page"))
    }

    @Test fun `the copy and export of each report subpage cover every heading of the status dump exactly once`() {
        val store = MeshStore(FakePort(up = true), decl, FakeHost(), exec = { it.run() })
        store.poll()
        val dump = store.reportText()
        val headings = dump.lines().filter { it.startsWith("== ") }.map { it.removePrefix("== ").substringBefore(" (").trim() }
        val claimed = subs.flatMap { it.report }
        for (h in headings) assertEquals("heading $h", 1, claimed.count { it == h })
        for (c in claimed) assertTrue("declared heading $c is not in the dump", c in headings)
        for (sp in subs.filter { it.report.isNotEmpty() }) {
            val text = store.sectionText(sp.id)
            for (h in sp.report) assertTrue("${sp.id} lacks == $h", text.lines().any { it.removePrefix("== ").substringBefore(" (").trim() == h && it.startsWith("== ") })
            for (h in headings - sp.report.toSet()) assertTrue("${sp.id} carries a foreign section $h", text.lines().none { it.startsWith("== $h") })
        }
    }

    @Test fun `mutation - a section cut with the wrong headings loses the section`() {
        val dump = "title\n== Tunnel\nstate UP\n== Peers\np1 x\n== Log (1)\nl"
        assertEquals("title\n== Tunnel\nstate UP", MeshReport.pick(dump, listOf("Tunnel")))
        assertEquals("title\n== Peers\np1 x\n== Log (1)\nl", MeshReport.pick(dump, listOf("Peers", "Log")))
        assertTrue("== Tunnel" !in MeshReport.pick(dump, listOf("Peers")))
    }

    @Test fun `Copy this section, Export this section and Export the log act through the host`() {
        val host = FakeHost()
        val store = MeshStore(FakePort(up = true), decl, host, exec = { it.run() })
        store.poll(); store.run("log.level", "all")
        store.run("section.copy", "interface")
        assertTrue(host.clip.contains("== Interface") && host.clip.contains("== DNS") && !host.clip.contains("== Peers"))
        store.run("section.export", "peers")
        assertEquals("cloud-mesh-peers.txt", host.exported!!.first)
        assertTrue(host.exported!!.second.contains("== Peers"))
        store.run("log.export")
        assertEquals("cloud-mesh-log.txt", host.exported!!.first)
        store.run("nav.configsDns")
        assertTrue("configsDns" in host.events)
    }

    @Test fun `each subpage with a stamp says when it was last updated`() {
        var now = 1_700_000_000_000L
        val store = MeshStore(FakePort(up = true), decl, FakeHost(), clock = { now }, exec = { it.run() })
        assertEquals(0L, store.stampMs("snapshot"))
        store.poll()
        assertEquals(now, store.stampMs("snapshot"))
        assertEquals(0L, store.stampMs("probes"))
        now += 5_000; store.run("reach.run")
        assertEquals(now, store.stampMs("probes"))
        assertTrue(store.stampMs("log") > 0L)
        for (sp in subs.filter { it.stamp.isNotBlank() }) assertTrue("${sp.id}: unknown stamp ${sp.stamp}", sp.stamp in setOf("snapshot", "probes", "log"))
    }

    @Test fun `the DNS servers in use are read from the port and written to the dump`() {
        val port = FakePort(up = true).also { it.dnsServers = listOf("10.0.0.1", "fd0c:1d00::1") }
        val store = MeshStore(port, decl, FakeHost(), exec = { it.run() })
        store.poll()
        assertEquals(listOf("10.0.0.1", "fd0c:1d00::1"), store.dnsServers)
        assertTrue(store.reportText().lines().any { it.startsWith("servers in use") && it.contains("10.0.0.1, fd0c:1d00::1") })
    }

    @Test fun `the endpoint in use per peer is v4, v6, relay or a name`() {
        assertEquals("v4", MeshEndpoint.inUse("35.226.147.64:443", "direct"))
        assertEquals("v6", MeshEndpoint.inUse("[2603:c026:c104:8f00::1]:51821", "direct"))
        assertEquals("v6", MeshEndpoint.inUse("2603:c026::1", ""))
        assertEquals("relay", MeshEndpoint.inUse("35.226.147.64:443", "relay"))
        assertEquals("name", MeshEndpoint.inUse("hub.example.com:443", "doh"))
        assertEquals("-", MeshEndpoint.inUse("", "direct"))
        // mutation: a peer field list without the new field, or a wrong family, is caught
        val store = MeshStore(FakePort(up = true), decl, FakeHost(), exec = { it.run() }); store.poll()
        val s = store.snapshot!!
        val f = peerFields(s.peers[0], s, "direct").toMap()
        assertEquals("v4", f["endpoint_in_use"])
        assertEquals("relay", peerFields(s.peers[0], s, "relay").toMap()["endpoint_in_use"])
        assertTrue(store.reportText().lines().any { it.trim().startsWith("endpoint in use") })
    }
}

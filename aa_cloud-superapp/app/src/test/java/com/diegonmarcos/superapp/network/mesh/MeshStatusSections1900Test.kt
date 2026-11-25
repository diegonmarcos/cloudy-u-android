package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1900 FIELD PARITY of the VPN page after it became three sections - Status, Profiler, Prober - so each datum is drawn once
 * (owner rule 16: a reorganisation moves a field, it never drops one). Held three ways, each with a mutation that turns it red:
 *  1. every row the page drew before [PREVIOUS_ROWS] sits in exactly one section ([problems]);
 *  2. every value of the per-peer lines that the Peers row now carries alone is still on the page ([missing]);
 *  3. the page draws the three titles and every row's tag.
 * Also here: the cleartext rule of the https probe (an http:// probe URL needs its mesh host in the network security config).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h6000dp-night-450dpi")
class MeshStatusSections1900Test : KitPageHarness() {

    companion object {
        /** ui.mesh_page.pages[status].rows at the commit before #1900. */
        val PREVIOUS_ROWS = listOf("lights", "state", "vpn", "meshes", "export", "profile", "interface", "pubkey", "transport", "endpoint",
            "peers", "handshake", "traffic", "latency", "dns", "reachability", "cloudflare", "profiler", "log")

        /** What is wrong with [sections] as a partition of [rows] (empty = every row of [previous] is drawn, exactly once). */
        fun problems(previous: List<String>, rows: List<String>, sections: List<MeshDecl.Section>): List<String> {
            val placed = sections.flatMap { it.rows }
            val out = mutableListOf<String>()
            for (r in previous) when (placed.count { it == r }) { 0 -> out += "row $r is in no section"; 1 -> {}; else -> out += "row $r is drawn twice" }
            for (r in placed) if (r !in rows) out += "section row $r is not a declared row"
            for (r in rows) if (r !in placed) out += "declared row $r is in no section"
            return out
        }

        /** The [expected] values the page does not show. */
        fun missing(expected: List<String>, shown: (String) -> Boolean): List<String> = expected.filter { !shown(it) }

        /** An http:// probe URL needs its host listed as cleartext-permitted in [nsc]; https needs nothing. */
        fun cleartextOk(nsc: String, url: String): Boolean {
            if (!url.startsWith("http://")) return true
            val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
            val from = nsc.indexOf("cleartextTrafficPermitted=\"true\"")
            return from >= 0 && Regex("<domain[^>]*>\\s*${Regex.escape(host)}\\s*</domain>").containsMatchIn(nsc.substring(from))
        }
    }

    private val decl = MeshDecl.baked

    private fun seeded(): MeshStore {
        val port = FakePort(up = true).also { it.hs = System.currentTimeMillis() - 20_000; it.path = "udp" }
        val s = MeshStore(port, decl, FakeHost(), exec = { it.run() }, meshIo = MeshProber1900Test.ScriptIo(),
            reachIo = object : MeshReach.Io {
                override fun resolve(host: String) = listOf("10.0.0.6")
                override fun connect(address: String, port: Int, timeoutMs: Int) {}
                override fun https(url: String, timeoutMs: Int) = 401
            })
        s.poll(); s.run("transport.test"); s.run("reach.run"); s.run("mesh.probe"); s.run("prober.run"); s.poll()
        return s
    }

    // ── 1. every row, in exactly one section ──

    @Test fun `the three sections hold every previous row exactly once`() {
        val page = decl.page("status")!!
        assertEquals(listOf("status", "profiler", "prober"), decl.sections.map { it.id })
        assertEquals(emptyList<String>(), problems(PREVIOUS_ROWS, page.rows, decl.sections))
        assertTrue("the Prober row is declared", "prober" in page.rows && "prober" in decl.sections.last().rows)
        assertEquals("the lights open the Status section", "lights", decl.sections.first().rows.first())
    }

    @Test fun `mutation - a row dropped from a section, drawn twice, or left out of the declaration turns the parity red`() {
        val page = decl.page("status")!!
        val dropped = decl.sections.map { if (it.id == "status") it.copy(rows = it.rows - "dns") else it }
        assertTrue(problems(PREVIOUS_ROWS, page.rows, dropped).any { "dns" in it })
        val twice = decl.sections.map { if (it.id == "prober") it.copy(rows = it.rows + "handshake") else it }
        assertTrue(problems(PREVIOUS_ROWS, page.rows, twice).any { "handshake" in it && "twice" in it })
        assertTrue(problems(PREVIOUS_ROWS + "ghost", page.rows, decl.sections).any { "ghost" in it })
        assertTrue(problems(PREVIOUS_ROWS - "log", page.rows, decl.sections.map { it.copy(rows = it.rows - "log") }).isNotEmpty())
    }

    // ── 2. every value, still on the page ──

    @Test fun `every per-peer value the handshake, traffic and latency rows used to repeat is on the page once, in Peers`() {
        val s = seeded()
        showKit { MeshStatusPage(s) }
        compose.waitForIdle()
        fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val s0 = s.snapshot!!
        val expected = s0.peers.flatMap { p -> peerFields(p, s0).filter { it.first in setOf("handshake", "health", "rx", "tx", "latency") }.map { it.second } }
        assertTrue(expected.size >= 10)
        assertEquals(emptyList<String>(), missing(expected, ::shows))
        // the mutation: the same check against a page that lost one value reports it
        val lost = expected.first()
        assertEquals(listOf(lost), missing(expected) { it != lost }.distinct())
        // the aggregates the rows keep
        assertTrue(shows(MeshFormat.latency(s0.hubLatencyMs)))
    }

    // ── 3. the page ──

    @Test fun `the page draws the three section titles and every row`() {
        val s = seeded()
        showKit { MeshStatusPage(s) }
        compose.waitForIdle()
        fun has(tag: String) = assertTrue("no node $tag", compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        for (sec in decl.sections) has("mesh:section:${sec.id}")
        for (r in decl.page("status")!!.rows) has(MeshTags.row(r))
        for (c in listOf("status_prober_run", "status_prober_cancel", "status_copy_all", "status_export_md")) has(MeshTags.control(c))
        for (r in s.prober) has("mesh:prober:${r.spec.group}/${r.spec.id}")
        for (g in MeshProber.GROUPS) has("mesh:prober:group:$g")
        assertEquals("the page and the store agree on the state", s.prober.size, s.prober.count { it.done })
    }

    // ── the cleartext probe ──

    private fun nsc(): String =
        listOf("src/main/res/xml/network_security_config.xml", "app/src/main/res/xml/network_security_config.xml").map(::File).first { it.exists() }.readText()

    @Test fun `every http probe URL of the page has its mesh host cleartext-permitted`() {
        val nsc = nsc()
        val urls = decl.meshes.map { it.serviceUrl }.filter { it.isNotBlank() } + decl.prober.privateNames.map { it.url } + decl.prober.publicUrls.map { it.url }
        assertTrue("the Mesh-1 service is probed over http on the mesh", urls.any { it.startsWith("http://c3-infra-api.mesh.diegonmarcos.com") })
        for (u in urls) assertTrue("$u would fail with 'Cleartext HTTP traffic not permitted'", cleartextOk(nsc, u))
        for (u in MeshProber.plan(decl.prober, MeshProber.Ctx(meshes = decl.meshes, reachHosts = decl.reachHosts)).map { it.url }.filter { it.isNotBlank() })
            assertTrue("probe URL $u", cleartextOk(nsc, u))
    }

    @Test fun `mutation - a mesh host missing from the network security config is caught`() {
        val nsc = nsc()
        val u = "http://c3-infra-api.mesh.diegonmarcos.com:8081/"
        assertTrue(cleartextOk(nsc, u))
        assertFalse(cleartextOk(nsc.replace("c3-infra-api.mesh.diegonmarcos.com", "elsewhere.example"), u))
        assertFalse("outside the permitted block it does not count", cleartextOk(nsc.replace("cleartextTrafficPermitted=\"true\"", "cleartextTrafficPermitted=\"false\""), u))
        assertTrue("https needs nothing", cleartextOk("", "https://vaultwarden.mesh.diegonmarcos.com/"))
    }
}

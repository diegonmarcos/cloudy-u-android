package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * #1797 FIELD PARITY (owner rule 16: a reorganisation moves fields, it never drops one). The Status page was
 * reorganised (lights first, the per-mesh table, the full breakdown, Cloud C3's profiler, the Log); this test
 * holds it to the page as it was before:
 *  1. every row the previous page drew ([PREVIOUS_ROWS], copied from the declaration at the previous commit) is
 *     still declared, still drawn (its tag on screen) and still carries its values;
 *  2. the status dump: [PreviousMeshReport] is the previous MeshReport.build copied VERBATIM from git; every line
 *     it writes must appear, whole and in the same order, in today's dump (nothing missing, nothing truncated),
 *     with or without a mesh profile;
 *  3. every field of the dump is enumerated by name (Tunnel, Interface, DNS, Transport, every peer field with its
 *     public key, Traffic, every Reachability probe with its detail, the whole Log) and found in both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h6000dp-night-450dpi")
class MeshStatusParityTest : KitPageHarness() {

    companion object {
        /** ui.mesh_page.pages[status].rows at the commit before #1797. */
        val PREVIOUS_ROWS = listOf("export", "vpn", "state", "interface", "pubkey", "endpoint", "transport", "handshake",
            "traffic", "latency", "dns", "reachability", "profile", "cloudflare")
        /** The status dump's sections and keys at the commit before #1797, in order. */
        val DUMP_SECTIONS = listOf("== Tunnel", "== Interface", "== DNS", "== Transport", "== Peers", "== Traffic", "== Reachability", "== Log (")
        val DUMP_KEYS = listOf("state", "tunnel", "profile", "provider", "address", "mtu", "listen port", "public key",
            "in effect", "fleet", "preset", "path", "mode", "detail", "relay", "down", "up", "hub latency")
        val PEER_KEYS = listOf(" hs ", " ep ", " allowed ", " rx ", " tx ", " lat ")
    }

    private val decl = MeshDecl.baked
    private val tz = TimeZone.getTimeZone("UTC")

    private class ReachIo : MeshReach.Io {
        override fun resolve(host: String) = if (host.startsWith("gitea")) throw java.net.UnknownHostException("No address associated with hostname") else listOf("10.0.0.1")
        override fun connect(address: String, port: Int, timeoutMs: Int) {}
        override fun https(url: String, timeoutMs: Int) = if (url.contains("authelia")) 302 else 401
    }

    /** A connected phone with probes, Reachability, a Log of every kind and (optionally) a mesh profile. */
    private fun seeded(profile: Boolean): MeshStore {
        val port = FakePort(up = true).also {
            it.peers = mutableListOf(
                PeerCfg("gcp-proxy", "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc=", "", "35.226.147.64:443", "10.0.0.0/24, fd0c:1d00::/64", "25"),
                PeerCfg("oci-analytics", "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc=", "", "129.151.228.66:51821", "10.1.0.0/24, fd0c:1d01::/64, 2603:c026:c104:8f00:ff9b::/96", "25"),
            )
            it.hs = System.currentTimeMillis() - 115_000
            it.path = "udp"
        }
        var now = System.currentTimeMillis()
        val io = C3MeshProbeTest.FakeMeshIo(mapOf(), { listOf("10.0.0.6") })
        val s = MeshStore(port, decl, FakeHost(), clock = { now.also { now += 1000 } }, exec = { it.run() }, reachIo = ReachIo(), meshIo = io)
        s.poll()
        s.run("transport.test")
        s.run("reach.run")
        s.journal.add(LogEvent(now, "warn", "peer oci-analytics stale: handshake 6m00s ago"))
        s.journal.add(LogEvent(now, "error", "read the engine failed: binder died"))
        s.journal.add(LogEvent(now, "dns", "DNS in effect: Private only · 10.0.0.1"))
        if (profile) s.run("mesh.probe")
        s.poll()
        return s
    }

    private fun previous(s: MeshStore) = PreviousMeshReport.build(s.snapshot, s.dnsPreset, s.provider, s.transport, s.probes, s.reach, s.journal.list(), 1_700_000_000_000L, tz)
    private fun current(s: MeshStore) = MeshReport.build(s.snapshot, s.dnsPreset, s.provider, s.transport, s.probes, s.reach, s.journal.list(), 1_700_000_000_000L, tz, profile = s.meshProfile)

    /** [old] lines appear in [new], whole, in the same order. */
    private fun assertSubsequence(old: String, new: String) {
        val n = new.lines()
        var at = 0
        for (l in old.lines()) {
            val i = (at until n.size).firstOrNull { n[it] == l }
            assertTrue("the previous dump's line is missing or changed: [$l]", i != null)
            at = i!! + 1
        }
    }

    @Test fun `every previous row is still declared, in the new order, with the additions`() {
        val rows = decl.page("status")!!.rows
        for (r in PREVIOUS_ROWS) assertTrue("row $r was removed", r in rows)
        for (r in listOf("lights", "meshes", "peers", "profiler", "log")) assertTrue("row $r is missing", r in rows)
        assertEquals("the lights come first", "lights", rows.first())
        assertTrue("the per-mesh table sits above the breakdown", rows.indexOf("meshes") < rows.indexOf("interface"))
        val raw = org.json.JSONObject(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.UI_MESH_PAGE_B64, android.util.Base64.DEFAULT)))
        val pages = raw.getJSONArray("pages")
        val status = (0 until pages.length()).map { pages.getJSONObject(it) }.single { it.getString("id") == "status" }
        val declared = status.getJSONArray("previous_rows").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals("previous_rows in build.json is the page before #1797", PREVIOUS_ROWS, declared)
        assertEquals(decl.meshes.map { it.label }, listOf("Mesh-1", "Mesh-2"))
    }

    @Test fun `the dump keeps every previous line whole and in order, with and without a profile`() {
        for (withProfile in listOf(false, true)) {
            val s = seeded(withProfile)
            val old = previous(s); val new = current(s)
            assertSubsequence(old, new)
            assertTrue("the dump grew: ${new.length} <= ${old.length}", new.length > old.length)
            assertTrue(new, "== Meshes" in new)
            if (withProfile) assertTrue(new, "Mesh-1 wg0 10.0.0.0/24" in new && "== mtu" in new)
        }
    }

    @Test fun `every field of the previous dump is enumerated and present`() {
        val s = seeded(true)
        val old = previous(s); val new = current(s)
        for (dump in listOf(old, new)) {
            var at = -1
            for (sec in DUMP_SECTIONS) { val i = dump.indexOf(sec); assertTrue("section $sec", i > at); at = i }
            for (k in DUMP_KEYS) assertTrue("key $k", Regex("(?m)^${Regex.escape(k)}\\s").containsMatchIn(dump))
            for (p in s.snapshot!!.peers) {
                val line = dump.lines().first { it.startsWith(p.cfg.name) }
                for (k in PEER_KEYS) assertTrue("${p.cfg.name} $k", k in line)
                assertTrue(p.cfg.allowedIps in line)
                assertTrue("${p.cfg.name} public key whole", dump.lines().any { it.trim().startsWith("public key") && it.endsWith(p.cfg.publicKey) })
            }
            for (pr in s.probes) assertTrue("probe ${pr.id}", dump.contains("probe ${pr.id}") && dump.contains(pr.detail))
            for (r in s.reach) for (st in r.steps) assertTrue("reach ${r.host} ${st.id}", "${st.id} ${if (st.ok) "PASS" else "FAIL"} ${st.ms} ms (${st.detail})" in dump)
            assertTrue("== Log (${s.journal.list().size})" in dump)
            for (e in s.journal.list()) assertTrue("log entry whole: ${e.text}", dump.contains(e.text))
        }
    }

    @Test fun `the page draws every previous row and every value, plus the additions`() {
        val s = seeded(true)
        showKit { MeshStatusPage(s) }
        compose.waitForIdle()
        fun has(tag: String) = assertTrue("no node $tag", compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        fun shows(text: String) = assertTrue("not on the page: $text", compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        for (r in PREVIOUS_ROWS) if (r != "reachability") has(MeshTags.row(r))
        for (r in s.reach) { shows(r.host); for (st in r.steps) has("mesh:reach:${r.host}:${st.id}") }
        for (pr in s.probes) has("mesh:probe:${pr.id}")
        val c = s.snapshot!!.cfg
        for (v in listOf(c.address, c.mtu, c.publicKey, c.dnsInEffect, c.dns, s.provider)) shows(v)
        for (p in s.snapshot!!.peers) { shows(p.cfg.endpoint); shows(p.cfg.allowedIps); shows(p.cfg.publicKey) }
        for (r in listOf("lights", "meshes", "peers", "profiler", "log")) has(MeshTags.row(r))
        for (l in listOf("tunnel", "handshake", "dns", "names", "ips", "reach")) has("mesh:light:$l")
        // the fallback lights: the active path, fallbacks ready, one per Test fallbacks step, from the same run the Path section lists
        has("mesh:light:path"); has("mesh:light:fallbacks_ready")
        assertTrue(s.probes.isNotEmpty())
        for (pr in s.probes) { has("mesh:light:fb:${pr.id}"); has("mesh:probe:${pr.id}") }
        val lights = lightLines(s.snapshot!!, s.meshProfile, s.reach, s.probes, s.probesAtMs, s.transport)
        for (pr in s.probes) assertEquals(pr.ok, lights.single { it.id == "fb:${pr.id}" }.ok)
        assertTrue(lights.single { it.id == "fb:${s.probes.first().id}" }.detail.startsWith(s.probes.first().detail))
        // every Reachability host with its label
        for (h in decl.reachHosts) shows(decl.reachLabels.getValue(h) + " · " + h)
        for (m in decl.meshes) for (col in com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe.COLUMNS) has("mesh:mesh:${m.id}:$col")
        // the complete Log, behind its switch: every entry once it is on
        compose.runOnUiThread { s.run("view.statusLog", "on") }
        compose.waitForIdle()
        for (e in s.journal.list()) shows(e.text.take(60))
    }
}

/** The previous MeshReport.build, copied verbatim from the commit before #1797: the reference the dump is held to. */
private object PreviousMeshReport {
    fun build(
        s: MeshSnapshot?,
        dnsPreset: String,
        provider: String,
        transport: TransportView?,
        probes: List<ProbeLine>,
        reach: List<MeshReach.Result>,
        journal: List<LogEvent>,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): String {
        val out = StringBuilder()
        fun line(t: String = "") { out.append(t).append('\n') }
        fun kv(k: String, v: String) = line("${k.padEnd(14)} ${v.ifBlank { "-" }}")
        line("Cloud Mesh status @ ${MeshFormat.clock(nowMs, tz)} (epoch ms $nowMs)")
        if (s == null) line("(no reading yet)")
        else {
            val c = s.cfg
            line("== Tunnel")
            kv("state", when { !s.engineInstalled -> "engine not installed"; s.up -> "UP (${s.light})"; else -> "DOWN" })
            kv("tunnel", c.tunnelName); kv("profile", c.activeProfile.substringAfterLast('/')); kv("provider", provider)
            line("== Interface")
            kv("address", c.address); kv("mtu", c.mtu); kv("listen port", c.listenPort); kv("public key", c.publicKey)
            line("== DNS")
            kv("in effect", c.dnsInEffect); kv("fleet", c.dns); kv("preset", dnsPreset)
            line("== Transport")
            kv("path", transport?.path.orEmpty()); kv("mode", transport?.mode.orEmpty()); kv("detail", transport?.detail.orEmpty()); kv("relay", transport?.relay.orEmpty())
            for (p in probes) kv("probe ${p.id}", when (p.ok) { true -> "PASS"; false -> "FAIL"; null -> "n/a" } + " " + p.detail)
            line("== Peers")
            for (p in s.peers) {
                kv(p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) },
                    "${p.health} hs ${MeshFormat.age(p.handshakeAgeS)} ep ${p.cfg.endpoint} allowed ${p.cfg.allowedIps} rx ${p.rx} tx ${p.tx} lat ${MeshFormat.latency(p.latencyMs)}")
                kv("  public key", p.cfg.publicKey)
            }
            line("== Traffic")
            kv("down", MeshFormat.bytes(s.rx)); kv("up", MeshFormat.bytes(s.tx)); kv("hub latency", MeshFormat.latency(s.hubLatencyMs))
        }
        line("== Reachability")
        if (reach.isEmpty()) line("(not run)") else line(MeshReach.text(reach))
        line("== Log (${journal.size})")
        for (e in journal) line("${MeshFormat.clock(e.atMs, tz)} ${MeshJournal.levelOf(e.kind).uppercase().padEnd(5)} ${e.kind.padEnd(9)} ${e.text}")
        return MeshReport.redact(out.toString().trimEnd())
    }
}

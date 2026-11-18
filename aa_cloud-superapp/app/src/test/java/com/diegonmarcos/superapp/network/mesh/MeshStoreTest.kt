package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #877 The page's verbs: each control reaches the engine port it names, and nothing ends silently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshStoreTest {
    private var now = 1_000_000L
    private fun store(port: FakePort = FakePort(), host: FakeHost = FakeHost()) =
        MeshStore(port, MeshDecl.baked, host, clock = { now.also { now += 1000 } }, exec = { it.run() })

    @Test fun `a poll builds the snapshot and a down tunnel is DOWN`() {
        val s = store(); s.poll()
        assertEquals(Light.DOWN, s.snapshot!!.light); assertFalse(s.connected())
        assertEquals(2, s.snapshot!!.cfg.peers.size)
    }

    @Test fun `no engine is CANNOT TELL, never down`() {
        val s = store(FakePort(engine = false)); s.poll()
        assertEquals(Light.UNKNOWN, s.snapshot!!.light)
        assertEquals(Block.ENGINE_MISSING, s.blockOf(s.decl.control("connect")!!))
        assertEquals(Block.ENGINE_MISSING, s.blockOf(s.decl.control("dns_preset")!!))
        assertNull("a prefs-only control stays usable without the engine", s.blockOf(s.decl.control("mtu")!!))
    }

    @Test fun `connect without consent drives the engine at once`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("state.set", "on")
        assertTrue("connect" in port.calls); assertTrue(s.connected()); assertTrue(s.notice.startsWith("connect:"))
    }

    @Test fun `connect with consent asks the host first and runs after the answer`() {
        val port = FakePort(consent = Intent("consent")); val host = FakeHost(); val s = store(port, host); s.poll()
        s.run("state.set", "on")
        assertEquals(listOf("consent"), host.events); assertTrue(port.calls.isEmpty())
        host.consentThen!!.invoke()
        assertEquals(listOf("connect"), port.calls)
    }

    @Test fun `disconnect and reconnect go through the same port`() {
        val port = FakePort(up = true); val s = store(port); s.poll()
        s.run("state.reconnect"); s.run("state.set", "off")
        assertEquals(listOf("reconnect", "disconnect"), port.calls); assertFalse(s.connected())
    }

    @Test fun `a refusal becomes the notice and a journal error, never silence`() {
        val port = FakePort(); port.failWith = "no handshake possible"; val s = store(port); s.poll()
        s.run("prefs.mtu", "1400")
        assertEquals("MTU failed: no handshake possible", s.notice)
        assertTrue(s.journal.list().any { it.kind == "error" && it.text.contains("no handshake possible") })
    }

    @Test fun `connect is blocked without a key but a connected tunnel can still be dropped`() {
        val port = FakePort(key = false); val s = store(port); s.poll()
        assertEquals(Block.NO_KEY, s.blockOf(s.decl.control("connect")!!))
        port.up = true; s.poll()
        assertNull(s.blockOf(s.decl.control("connect")!!))
    }

    @Test fun `interface controls write through the port`() {
        val port = FakePort(); val s = store(port)
        s.run("prefs.mtu", "1400"); s.run("prefs.keepalive", "40"); s.run("prefs.tunnelName", "x"); s.run("prefs.address", "10.9.9.9/32"); s.run("prefs.listenPort", "")
        assertEquals(listOf("mtu=1400", "keepalive=40", "name=x", "address=10.9.9.9/32", "port="), port.calls)
        assertEquals("1400", s.snapshot!!.cfg.mtu); assertEquals("40", s.snapshot!!.cfg.peers.first().keepalive)
    }

    @Test fun `the fallback controls reach the port and Test fallbacks lands rung by rung`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("transport.mode", "relay"); s.run("prefs.relayKey", "k".repeat(16)); s.run("transport.test")
        assertEquals(listOf("mode=relay", "relaykey", "test"), port.calls)
        assertEquals("relay", s.transport!!.mode); assertTrue(s.hasRelayKey)
        assertEquals(listOf("udp", "dns", "relay"), s.probes.map { it.id })
        assertTrue(s.notice, s.notice.contains("1 of 3 pass") && s.notice.contains("dns fails"))
    }

    @Test fun `excluded apps, dns preset, generate key and peers`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("prefs.excludedApps", "com.a, org.b"); s.run("fleetdns.preset", "public_open"); s.run("prefs.generateKey")
        s.run("prefs.peer.add"); s.run("prefs.peer.remove", index = 2)
        assertEquals(listOf("excluded=com.a,org.b", "dns=public_open", "genkey", "peer.add", "peer.remove=2"), port.calls)
        assertEquals(listOf("com.a", "org.b"), s.excluded); assertEquals("public_open", s.dnsPreset)
    }

    @Test fun `a profile is activated when stored and installed from the declaration when not`() {
        val port = FakePort(); val s = store(port)
        s.run("prefs.profile.activate", "v4-split")
        port.stored["config-v4-split"] = "x"
        s.run("prefs.profile.activate", "v4-split")
        assertEquals(listOf("install=v4-split", "activate=config-v4-split"), port.calls)
    }

    @Test fun `clipboard re-import names the profile and says so when the clipboard is empty`() {
        val port = FakePort(); val host = FakeHost(); val s = store(port, host)
        s.run("mesh.applyMesh", "v4-split")
        assertTrue(port.calls.isEmpty()); assertTrue(s.notice.contains("clipboard"))
        host.clip = "[Interface]\nPrivateKey = x"
        s.run("mesh.applyMesh", "v4-split")
        assertEquals(listOf("import=config-v4-split"), port.calls)
    }

    @Test fun `copies never carry a key and say what they copied`() {
        val host = FakeHost(); val s = store(host = host); s.poll()
        s.run("clipboard.peer", index = 0)
        assertTrue(host.clip.contains("PublicKey = KEY-HUB")); assertFalse(host.clip.contains("PrivateKey"))
        s.run("clipboard.profile", "v4-split")
        assertTrue(host.events.contains("copy:v4-split")); assertTrue(host.clip.contains("PrivateKey = "))
        assertFalse("a declared template has an empty key line", Regex("(?m)^PrivateKey = \\S").containsMatchIn(host.clip))
        s.run("clipboard.log"); assertTrue(s.notice.contains("journal lines copied"))
    }

    @Test fun `cloud over a custom config asks first`() {
        val s = store(); s.run("prefs.provider", "custom"); assertFalse(s.confirmCloud)
        val port = FakePort(); val drift = object : MeshPort by port {
            override fun matchesCloudPreset() = false
            override fun applyCloudPreset() = port.applyCloudPreset()
        }
        val t = MeshStore(drift, MeshDecl.baked, FakeHost(), exec = { it.run() })
        t.run("prefs.provider", "cloud")
        assertTrue("a drifted config must be confirmed, not replaced", t.confirmCloud); assertTrue(port.calls.isEmpty())
        t.confirmCloudPreset()
        assertEquals(listOf("cloud"), port.calls); assertFalse(t.confirmCloud)
    }

    @Test fun `view controls are state, seeded from the declaration`() {
        val s = store()
        assertEquals("config", s.view["peer_sort"]); assertEquals("all", s.view["route_filter"]); assertNull("#1153 no topology switch", s.view["topology"])
        s.run("view.sort", "traffic"); s.run("view.routeFilter", "nat64")
        assertEquals("traffic", s.view["peer_sort"]); assertEquals("nat64", s.view["route_filter"])
        s.run("view.diff", "v4-split"); assertEquals("v4-split", s.diffFor); s.run("view.diff", "v4-split"); assertNull(s.diffFor)
    }

    @Test fun `the journal fills from polls and actions and clears`() {
        val s = store(); s.poll(); s.run("prefs.mtu", "1400")
        assertTrue(s.journal.list().size >= 2)
        s.run("log.clear"); assertTrue(s.journal.list().isEmpty())
    }

    @Test fun `polling runs only while resumed`() {
        val s = store(); assertTrue(s.shouldPoll(true)); assertFalse(s.shouldPoll(false))
    }

    @Test fun `an unknown engine id is reported`() {
        val s = store(); s.run("no.such.call"); assertTrue(s.notice.contains("no.such.call"))
    }

    @Test fun `latency is probed on the Status tab every Nth poll and carried between`() {
        val s = store(FakePort(up = true)); s.tab = "status"
        s.poll()
        assertEquals(12, s.snapshot!!.hubLatencyMs); assertEquals(20, s.snapshot!!.peers[0].latencyMs)
        s.poll()
        assertEquals("the last reading is carried on a thinned tick", 12, s.snapshot!!.hubLatencyMs)
        val off = store(FakePort(up = true)); off.tab = "logs"; off.poll()
        assertNull("no probing from a tab that does not show latency", off.snapshot!!.hubLatencyMs)
    }

    @Test fun `cloudflare warp registers through the port and shows as the active transport`() {
        val port = FakePort(); val s = store(port); s.poll()
        assertEquals(CfView(), s.cf)
        s.run("cf.warp.use")
        assertTrue(port.calls.contains("warp.use")); assertTrue(s.cf.warp); assertEquals("wg-cloudflare-warp", s.cf.active)
        s.run("cf.warp.forget")
        assertTrue(port.calls.contains("warp.forget")); assertTrue(!s.cf.warp)
    }

    @Test fun `cloudflare one says it waits on the zero trust apply instead of failing`() {
        val port = FakePort(); val host = FakeHost(); val s = MeshStore(port, MeshDecl.baked, host, exec = { it.run() })
        assertTrue("the shipped declaration keeps One off until #994 is applied", !MeshDecl.baked.cloudflare.one.enabled)
        s.run("cf.one.enrol")
        assertTrue(s.notice, s.notice.contains("waiting on #994"))
        assertTrue("no login opens and nothing is called while waiting", host.events.none { it.startsWith("tab:") } && port.calls.isEmpty())
    }

    @Test fun `cloudflare one opens the team login once enabled, and enrols with the token`() {
        val on = MeshDecl.baked.copy(cloudflare = MeshDecl.baked.cloudflare.copy(one = MeshDecl.baked.cloudflare.one.copy(enabled = true)))
        val port = FakePort(); val host = FakeHost(); val s = MeshStore(port, on, host, exec = { it.run() })
        s.run("cf.one.enrol")
        assertEquals(listOf("tab:https://diegonmarcos.cloudflareaccess.com/warp"), host.events)
        s.run("cf.one.enrol", "TOKEN")
        assertTrue(port.calls.contains("one.enrol=TOKEN")); assertTrue(s.cf.one)
    }

    @Test fun `a pending apply is surfaced`() {
        val port = FakePort(up = true); port.pendingApply = true; val s = store(port); s.poll()
        assertTrue(s.pending)
    }

    // ── #1153 reachability, copy/export all, the persisted log ──

    private class FakeIo(val failDns: Set<String> = emptySet(), val failTcp: Set<String> = emptySet(), val code: Int = 200) : MeshReach.Io {
        var t = 0L
        override fun resolve(host: String): List<String> { if (host in failDns) error("NXDOMAIN"); return listOf("10.0.0.7") }
        override fun connect(address: String, port: Int, timeoutMs: Int) { if (address == "10.0.0.9" || failTcp.isNotEmpty()) error("refused") }
        override fun https(url: String, timeoutMs: Int) = code
    }

    @Test fun `reachability reports dns, tcp and https per URL with ms and logs each result`() {
        val s = MeshStore(FakePort(up = true), MeshDecl.baked, FakeHost(), clock = { now.also { now += 7 } }, exec = { it.run() }, reachIo = FakeIo())
        s.poll(); s.run("reach.run")
        assertEquals(MeshDecl.baked.reachHosts, s.reach.map { it.host })
        for (r in s.reach) { assertEquals(listOf("dns", "tcp", "https"), r.steps.map { it.id }); assertTrue(r.ok); assertTrue(r.steps.all { it.ms >= 0 }) }
        // #1797 seven declared URLs (the #1153 three plus Vault, Chat, Mail, Drive)
        val n = MeshDecl.baked.reachHosts.size
        assertEquals(7, n)
        assertEquals(n, s.journal.list().count { it.kind == "reach" })
        assertTrue(s.notice, s.notice.contains("$n of $n reachable"))
    }

    @Test fun `a failing step is shown failed and the later steps are skipped, logged as a warning`() {
        val bad = MeshDecl.baked.reachHosts.first()
        val s = MeshStore(FakePort(up = true), MeshDecl.baked, FakeHost(), clock = { now }, exec = { it.run() }, reachIo = FakeIo(failDns = setOf(bad)))
        s.poll(); s.run("reach.run")
        val r = s.reach.first { it.host == bad }
        assertEquals(listOf(false, false, false), r.steps.map { it.ok })
        assertTrue(r.steps[1].detail.startsWith("skipped"))
        assertEquals(2, s.reach.count { it.ok })
        assertTrue(s.journal.list().any { it.kind == "warn" && bad in it.text })
        assertEquals(MeshJournal.levelOf("warn"), "warn")
    }

    @Test fun `an HTTP 5xx fails the https step, a 401 passes it`() {
        assertTrue(MeshReach.probe("h", FakeIo(code = 401), 100) { 0L }.ok)
        val r = MeshReach.probe("h", FakeIo(code = 502), 100) { 0L }
        assertFalse(r.ok); assertEquals("https", r.steps.last().id); assertEquals("HTTP 502", r.steps.last().detail)
    }

    @Test fun `copy all and export all carry the whole status and never a private or pre-shared key`() {
        val port = FakePort(up = true); val host = FakeHost()
        port.peers[0] = port.peers[0].copy(presharedKey = "PSKSECRETVALUE")
        val s = MeshStore(port, MeshDecl.baked, host, clock = { now }, exec = { it.run() }, reachIo = FakeIo())
        s.poll(); s.run("reach.run")
        s.journal.add(LogEvent(now, "action", "private key = SUPERSECRETKEY123="))
        s.run("clipboard.all"); s.run("export.all")
        assertEquals(host.clip, host.exported!!.second)
        assertEquals("cloud-mesh-status.txt", host.exported!!.first)
        val t = host.clip
        for (needle in listOf("== Tunnel", "== Peers", "== DNS", "== Reachability", "== Log", "public key", "KEY-HUB", "gcp-proxy", MeshDecl.baked.reachHosts.first()))
            assertTrue("missing $needle", needle in t)
        assertFalse(t.contains("PSKSECRETVALUE")); assertFalse(t.contains("SUPERSECRETKEY123"))
        assertTrue(t.contains("[redacted]"))
    }

    @Test fun `the journal persists in a file, survives a new instance, and a second writer shows up in the screen's log`() {
        val f = java.io.File.createTempFile("mesh-journal", ".log"); f.delete()
        try {
            val screen = MeshStore(FakePort(up = true), MeshDecl.baked, FakeHost(), clock = { now }, exec = { it.run() }, journalFile = f)
            // another component (the supervisor / a boot receiver / the engine side) appends to the same file
            MeshJournal(MeshEventLog.CAP, f).add(LogEvent(5L, "supervisor", "reconnected after Wi-Fi change\nsecond line\tTab"))
            val seen = screen.journal.list()
            assertEquals("reconnected after Wi-Fi change\nsecond line\tTab", seen.single { it.kind == "supervisor" }.text)
            screen.poll()
            // app restart: a brand-new store over the same file still has both
            val again = MeshStore(FakePort(up = true), MeshDecl.baked, FakeHost(), clock = { now }, exec = { it.run() }, journalFile = f)
            assertTrue(again.journal.list().any { it.kind == "supervisor" })
            assertTrue(again.journal.list().any { it.kind == "engine" && it.text.startsWith("page open") })
            again.run("log.clear"); assertTrue(MeshJournal(10, f).list().isEmpty())
        } finally { f.delete() }
    }

    @Test fun `the journal file is bounded`() {
        val f = java.io.File.createTempFile("mesh-journal", ".log"); f.delete()
        try {
            val j = MeshJournal(50, f)
            repeat(6000) { j.add(LogEvent(it.toLong(), "info", "line $it " + "x".repeat(60))) }
            assertTrue(f.length() <= MeshJournal.MAX_BYTES + 200)
            val l = j.list(); assertEquals(50, l.size); assertEquals(5999L, l.last().atMs)
        } finally { f.delete() }
    }

    @Test fun `the level filter hides lower levels, and engine surroundings changes are journalled`() {
        val port = FakePort(up = true); val s = store(port); s.poll()
        s.journal.add(LogEvent(1, "error", "boom")); s.journal.add(LogEvent(2, "stale", "peer stale"))
        val all = s.journal.list()
        s.run("log.level", "error"); assertEquals(listOf("error"), all.filter { MeshJournal.shows(it, s.logLevel) }.map { it.kind }.distinct())
        s.run("log.level", "warn"); assertEquals(setOf("error", "stale"), all.filter { MeshJournal.shows(it, s.logLevel) }.map { it.kind }.toSet())
        s.run("log.level", "bogus"); assertEquals("all", s.logLevel)
        port.aoOn = true; s.poll()
        assertTrue(s.journal.list().any { it.kind == "engine" && it.text.startsWith("always-on") })
    }

    @Test fun `testing fallbacks journals every probe, a failed one as a warning`() {
        val s = store(); s.poll(); s.run("transport.test")
        val probe = s.journal.list().filter { it.kind == "probe" || it.kind == "warn" }
        assertEquals(3, probe.size); assertTrue(probe.any { it.kind == "warn" && it.text.startsWith("dns FAIL") })
    }
}

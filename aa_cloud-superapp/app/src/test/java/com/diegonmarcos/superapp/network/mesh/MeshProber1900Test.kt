package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import com.diegonmarcos.superapp.network.TunnelBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #1900 The VPN page's probe pipeline: profile, then probe, never on the page's thread, one verdict per fact. Each bug the
 * owner saw has its regression here (the freeze, the two handshake truths, the cleartext https probe lives in
 * [MeshStatusSections1900Test], the unbound sockets: the Wi-Fi source address and the IPv6 ENETUNREACH).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshProber1900Test {

    /** A scripted network: every call is recorded with the path it was asked for. */
    class ScriptIo(
        val local: String = "10.0.0.9",
        val tcpOk: (String, Int, String) -> Boolean = { _, _, _ -> true },
        val resolveGate: (String) -> Unit = {},
    ) : C3MeshProbe.Io {
        val calls = CopyOnWriteArrayList<String>()
        override fun query(server: String, name: String, timeoutMs: Int, padTo: Int) = "10.0.0.6"
        override fun systemResolve(name: String): List<String> { calls += "dns $name"; resolveGate(name); return listOf("10.0.0.6") }
        override fun tcp(host: String, port: Int, timeoutMs: Int) { tcpFrom(host, port, timeoutMs, C3MeshProbe.VIA_TUNNEL) }
        override fun tcpFrom(host: String, port: Int, timeoutMs: Int, via: String): String {
            calls += "tcp $host:$port $via"
            if (!tcpOk(host, port, via)) throw java.net.ConnectException("failed to connect to /$host (port $port): ENETUNREACH")
            return local
        }
        override fun https(url: String, timeoutMs: Int) = 401
        override fun httpsVia(url: String, timeoutMs: Int, via: String): Int { calls += "https $url $via"; return 401 }
    }

    private var now = System.currentTimeMillis()
    private val decl = MeshDecl.baked

    private class FakeReach : MeshReach.Io {
        override fun resolve(host: String) = listOf("10.0.0.6")
        override fun connect(address: String, port: Int, timeoutMs: Int) {}
        override fun https(url: String, timeoutMs: Int) = 401
    }

    private fun store(port: FakePort, io: C3MeshProbe.Io, sync: Boolean = true, auto: Boolean = false, host: FakeHost = FakeHost()) =
        if (sync) MeshStore(port, decl, host, clock = { now.also { now += 10 } }, exec = { it.run() }, meshIo = io, autoProbe = auto, reachIo = FakeReach())
        else MeshStore(port, decl, host, clock = System::currentTimeMillis, meshIo = io, reachIo = FakeReach())

    private fun upPort(hsAgeMs: Long = 20_000) = FakePort(up = true).also { it.hs = System.currentTimeMillis() - hsAgeMs }

    // ── the matrix ──

    @Test fun `the matrix has the six groups, the four private names, IPv4 and IPv6, all from the declaration`() {
        val s = store(upPort(), ScriptIo()); s.poll()
        s.run("prober.run")
        val rows = s.prober
        assertEquals(MeshProber.GROUPS, MeshProber.GROUPS.filter { g -> rows.any { it.spec.group == g } })
        val hosts = rows.filter { it.spec.group == "private" }.map { it.spec.host }
        for (h in listOf("vaultwarden", "caddy", "maddy", "c3-infra-mcp")) assertTrue("$h.mesh is probed", hosts.any { it.startsWith("$h.mesh.") || (h == "maddy" && it.startsWith("stalwart.mesh.")) })
        for (h in decl.reachHosts) assertTrue("existing mesh name $h", h in hosts)
        assertTrue("every private name is in the mesh namespace, never a public TLD", hosts.all { it.endsWith(".mesh.diegonmarcos.com") })
        assertTrue(rows.any { it.spec.group == "public" })
        assertEquals("one row per peer", s.snapshot!!.peers.size, rows.count { it.spec.group == "peers" })
        assertTrue("the fallback ladder expands into its rungs", rows.count { it.spec.group == "fallbacks" } >= 3)
        assertTrue(rows.filter { it.spec.group == "ipv4" }.any { it.spec.host == "1.1.1.1" })
        assertTrue("IPv6 hubs of both meshes", rows.filter { it.spec.group == "ipv6" }.map { it.spec.host }.containsAll(decl.meshes.map { it.hub6 }))
        assertTrue("declared IP targets, not Kotlin literals", decl.prober.ips.any { it.family == 6 })
        assertTrue(rows.all { it.done })
    }

    @Test fun `every row carries its route profile and a queued row has it before the probe is sent`() {
        val s = store(upPort(), ScriptIo()); s.poll()
        val ctx = MeshProber.ctxOf(s.snapshot, null, s.transport, decl)
        for (spec in MeshProber.plan(decl.prober, ctx)) {
            val q = MeshProber.queued(spec, ctx)
            assertFalse(q.done); assertEquals("queued", q.status)
            val r = q.route
            for ((k, v) in listOf("iface" to r.iface, "dns" to r.dns, "route" to r.route, "peer" to r.peer, "fallback" to r.fallback)) assertTrue("${spec.id} $k profiled", v.isNotBlank())
        }
        s.run("prober.run")
        for (r in s.prober) assertTrue("${r.spec.id}: profile -> probe -> status", r.route.line().contains("iface") && r.done && r.status in setOf("PASS", "FAIL", "n/a"))
    }

    @Test fun `a hung probe becomes a FAIL row under its own timeout while the others stream in`() {
        val io = ScriptIo(resolveGate = { n -> if (n.startsWith("caddy")) Thread.sleep(30_000) })
        val s = store(upPort(), io); s.poll()
        val ctx = MeshProber.ctxOf(s.snapshot, null, s.transport, decl)
        val specs = MeshProber.plan(decl.prober, ctx).filter { it.group == "private" }
        val got = CopyOnWriteArrayList<MeshProber.Row>()
        val t0 = System.nanoTime()
        MeshProber.run(specs, ctx, io, { emptyList() }, 200, 4, onRow = { got += it })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the run returned in $ms ms, not the 30 s the probe would take", ms < 8_000)
        assertEquals(specs.size, got.size)
        val caddy = got.single { it.spec.id == "caddy" }
        assertEquals(false, caddy.ok); assertTrue(caddy.detail, "no answer within" in caddy.detail)
        assertEquals("the hung one is last, the rest did not wait for it", "caddy", got.last().spec.id)
        assertTrue(got.filter { it.spec.id != "caddy" }.all { it.ok == true })
    }

    @Test fun `cancel stops the run between probes and drops no measured row`() {
        val io = ScriptIo()
        val s = store(upPort(), io); s.poll()
        val ctx = MeshProber.ctxOf(s.snapshot, null, s.transport, decl)
        val specs = MeshProber.plan(decl.prober, ctx)
        var cancel = false
        val got = CopyOnWriteArrayList<MeshProber.Row>()
        MeshProber.run(specs, ctx, io, { emptyList() }, 500, 1, cancelled = { cancel }, onRow = { got += it; cancel = true })
        assertTrue("stopped early: ${got.size} of ${specs.size}", got.size in 1 until specs.size)
    }

    // ── the freeze ──

    @Test fun `probes never block the page - Disconnect works while every probe hangs`() {
        val gate = CountDownLatch(1)
        val port = upPort()
        val s = store(port, ScriptIo(resolveGate = { gate.await(20, TimeUnit.SECONDS) }), sync = false)
        s.poll()
        val t0 = System.nanoTime()
        s.run("prober.run"); s.run("reach.run"); s.run("transport.test")
        assertTrue("starting the probes returned at once", (System.nanoTime() - t0) / 1_000_000 < 1500)
        assertFalse("a probe is not a busy action: no control is disabled by it", s.busy)
        s.run("state.disconnect")
        val end = System.currentTimeMillis() + 5_000
        while ("disconnect" !in port.calls && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("Disconnect ran while the probes were still hanging: ${port.calls}", "disconnect" in port.calls)
        gate.countDown()
        val end2 = System.currentTimeMillis() + 10_000
        while (s.probing > 0 && System.currentTimeMillis() < end2) Thread.sleep(20)
        assertEquals("no probe task is left running", 0, s.probing)
    }

    @Test fun `rows stream in one at a time and the queued ones keep their profile`() {
        val gate = CountDownLatch(1)
        val s = store(upPort(), ScriptIo(resolveGate = { n -> if (!n.startsWith("vaultwarden")) gate.await(20, TimeUnit.SECONDS) }), sync = false)
        s.poll(); s.run("prober.run")
        val end = System.currentTimeMillis() + 8_000
        while (s.prober.none { it.done } && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("the first answer is on the page while the rest still wait", s.prober.any { it.done } && s.prober.any { !it.done })
        assertTrue(s.prober.filter { !it.done }.all { it.route.iface.isNotBlank() })
        s.cancelProber(); gate.countDown()
    }

    // ── the two sources of truth for a handshake ──

    @Test fun `one verdict on a handshake - the lights and the Prober agree for every health`() {
        for ((hsAgeMs, fresh) in listOf(20_000L to true, 200_000L to false, 400_000L to false)) {
            val s = store(upPort(hsAgeMs), ScriptIo()); s.poll(); s.run("prober.run")
            val light = lightLines(s.snapshot!!, s.meshProfile, s.reach).single { it.id == "handshake" }
            val peers = s.prober.filter { it.spec.group == "peers" }
            assertEquals("age $hsAgeMs: the light", fresh, light.ok)
            assertTrue("age $hsAgeMs: every peer row", peers.all { (it.ok == true) == fresh })
        }
        assertEquals(true, MeshProber.handshakeOk(Health.FRESH))
        for (h in listOf(Health.AGING, Health.STALE, Health.NONE, Health.DOWN)) assertEquals("$h", false, MeshProber.handshakeOk(h))
        assertEquals(null, MeshProber.handshakeOk(Health.UNKNOWN))
    }

    @Test fun `a profile taken in the other tunnel state is replaced when the tunnel changes state`() {
        val port = FakePort(up = false).also { it.hs = System.currentTimeMillis() - 10_000 }
        val s = store(port, ScriptIo(), auto = true)
        s.poll(); assertTrue("nothing probed while down", s.prober.isEmpty())
        port.up = true; s.poll()
        val first = s.proberAtMs
        assertTrue("probed when the tunnel came up", first > 0 && s.prober.isNotEmpty())
        port.up = false; s.poll(); port.up = true; s.poll()
        assertTrue("probed again after the tunnel went down and up", s.proberAtMs > first)
        assertTrue("the profile says the tunnel is up", s.meshProfile!!.input.tunnelUp)
    }

    // ── the sockets: the tunnel, not the Wi-Fi ──

    private class RecBinder(val fail: String? = null) : C3MeshProbe.SocketBinder {
        val vias = CopyOnWriteArrayList<String>()
        override fun bind(s: Socket, via: String) { vias += via; fail?.let { throw java.io.IOException(it) } }
        override fun bind(s: DatagramSocket, via: String) { vias += "udp:$via" }
    }

    @Test fun `a tunnel probe's socket is bound to the VPN network before it connects`() {
        val rec = RecBinder()
        val old = C3MeshProbe.SystemIo.binder
        C3MeshProbe.SystemIo.binder = rec
        try {
            ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { srv ->
                val from = C3MeshProbe.SystemIo.tcpFrom("127.0.0.1", srv.localPort, 1000, C3MeshProbe.VIA_TUNNEL)
                assertEquals(listOf(C3MeshProbe.VIA_TUNNEL), rec.vias.toList())
                assertEquals("127.0.0.1", from)
                C3MeshProbe.SystemIo.tcpFrom("127.0.0.1", srv.localPort, 1000, C3MeshProbe.VIA_DEFAULT)
                assertEquals("the default path is not bound", 1, rec.vias.size)
                C3MeshProbe.SystemIo.tcpFrom("127.0.0.1", srv.localPort, 1000, C3MeshProbe.VIA_DIRECT)
                assertEquals(listOf("tunnel", "direct"), rec.vias.toList())
            }
            C3MeshProbe.SystemIo.binder = RecBinder("no VPN network: the tunnel is down")
            val e = runCatching { C3MeshProbe.SystemIo.tcp("10.0.0.1", 53, 500) }.exceptionOrNull()
            assertNotNull(e); assertTrue(e!!.message, "no VPN network" in e.message.orEmpty())
        } finally { C3MeshProbe.SystemIo.binder = old }
    }

    @Test fun `the network a probe takes is chosen by path - the VPN, or the best non-VPN`() {
        fun c(n: String, vpn: Boolean, inet: Boolean = true, ok: Boolean = false) = TunnelBinder.Cand(n, vpn, inet, ok)
        val nets = listOf(c("wifi-unvalidated", false), c("vpn", true, ok = true), c("cell", false, ok = true))
        assertEquals("vpn", TunnelBinder.pick(C3MeshProbe.VIA_TUNNEL, nets))
        assertEquals("cell", TunnelBinder.pick(C3MeshProbe.VIA_DIRECT, nets))
        assertEquals("wifi-unvalidated", TunnelBinder.pick(C3MeshProbe.VIA_DIRECT, nets.filter { it.net != "cell" }))
        assertTrue(runCatching { TunnelBinder.pick(C3MeshProbe.VIA_TUNNEL, nets.filter { !it.vpn }) }.exceptionOrNull()!!.message!!.contains("tunnel is down"))
        assertTrue(runCatching { TunnelBinder.pick(C3MeshProbe.VIA_DIRECT, nets.filter { it.vpn }) }.isFailure)
    }

    @Test fun `a probe that left from the Wi-Fi address says it was not in the tunnel`() {
        val s = store(upPort(), ScriptIo(local = "192.168.46.133")); s.poll(); s.run("prober.run")
        val hub = s.prober.single { it.spec.group == "ipv4" && it.spec.id == "wg0-hub" }
        assertEquals("PASS", hub.status)
        assertTrue(hub.path, hub.path.startsWith("NOT the tunnel") && "192.168.46.133" in hub.path)
        val inTunnel = store(upPort(), ScriptIo(local = "10.0.0.9")); inTunnel.poll(); inTunnel.run("prober.run")
        assertTrue(inTunnel.prober.single { it.spec.group == "ipv4" && it.spec.id == "wg0-hub" }.path.startsWith("tunnel"))
    }

    @Test fun `IPv6 probes go through the tunnel path and a missing IPv6 address is said in the profile`() {
        val io = ScriptIo(tcpOk = { h, _, via -> !(':' in h && via != C3MeshProbe.VIA_TUNNEL && h.startsWith("fd0c")) })
        val s = store(upPort(), io); s.poll(); s.run("prober.run")
        val hubs6 = s.prober.filter { it.spec.group == "ipv6" && it.spec.id.endsWith("hub6") }
        assertTrue(hubs6.isNotEmpty() && hubs6.all { it.spec.via == C3MeshProbe.VIA_TUNNEL && it.ok == true })
        assertTrue("the tunnel's own IPv6 hubs were asked through the tunnel path: ${io.calls}", io.calls.any { it.startsWith("tcp fd0c:1d00::1:53") && it.endsWith("tunnel") })
        assertTrue("the FakePort tunnel has no IPv6 address: the profile says so", hubs6.all { "no IPv6 address" in it.route.iface })
        val v6 = decl.prober.ips.first { it.family == 6 }
        assertTrue("a public v6 target uses the OS route", s.prober.any { it.spec.host == v6.ip && it.spec.via == C3MeshProbe.VIA_DEFAULT })
    }

    // ── the page-wide exports ──

    @Test fun `Copy all and Export to Md carry the whole matrix`() {
        val host = FakeHost()
        val s = store(upPort(), ScriptIo(), host = host); s.poll(); s.run("prober.run")
        s.run("clipboard.all")
        assertTrue(host.clip.contains("== Prober"))
        for (r in s.prober) assertTrue("${r.spec.id} whole in Copy all", host.clip.contains(r.detail) && host.clip.contains(r.route.line()))
        s.run("export.md")
        val md = host.exported!!.second
        assertEquals("cloud-mesh-status.md", host.exported!!.first)
        assertTrue("## Prober" in md)
        assertEquals("the same sections as Export all", s.reportText().lines().count { it.startsWith("== ") }, md.lines().count { it.startsWith("## ") })
        s.run("export.all"); assertTrue(host.exported!!.second.contains("A private"))
    }

    @Test fun `merge puts a finished row where its queued one was and a fallback rung where its placeholder was`() {
        val c = MeshProber.Ctx()
        val a = MeshProber.Spec("private", "a", "A", "name"); val b = MeshProber.Spec("private", "b", "B", "name")
        val ph = MeshProber.Spec("fallbacks", "ladder", "L", "fallbacks")
        var rows = listOf(a, b, ph).map { MeshProber.queued(it, c) }
        val done = MeshProber.Row(b, rows[1].route, true, 5, "p", "d", 1, true)
        rows = MeshProber.merge(rows, done)
        assertEquals(listOf("a", "b", "ladder"), rows.map { it.spec.id }); assertTrue(rows[1].done && !rows[0].done)
        val rung1 = MeshProber.Row(ph.copy(id = "udp", parent = "ladder"), rows[2].route, true, 0, "udp", "x", 1, true)
        val rung2 = rung1.copy(spec = rung1.spec.copy(id = "doh"))
        rows = MeshProber.merge(MeshProber.merge(rows, rung1), rung2)
        assertEquals(listOf("a", "b", "udp", "doh"), rows.map { it.spec.id })
    }
}

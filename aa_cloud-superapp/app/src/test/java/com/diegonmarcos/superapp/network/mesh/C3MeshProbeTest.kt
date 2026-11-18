package com.diegonmarcos.superapp.network.mesh

import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * #1797 Cloud C3's mesh probes (libs:fleetconfigs-xray C3MeshProbe) on a scripted network. Two phones:
 * the one reported (tunnel and both handshakes UP, Android holding the hubs AND the public resolvers, the
 * public resolvers answering NXDOMAIN, Android failing with "No address associated with hostname"), and the
 * fixed one (fleet resolvers only). Every column and step is asserted, with a control for each verdict.
 */
class C3MeshProbeTest {

    /** A scripted network: direct answers per (server, name), Android's answer per name, TCP/HTTPS, and the largest DNS message the path carries. */
    class FakeMeshIo(
        val answers: Map<Pair<String, String>, String>,
        val system: (String) -> List<String>,
        val tcpOk: (String, Int) -> Boolean = { _, _ -> true },
        val code: (String) -> Int = { 401 },
        val maxDnsBytes: Int = Int.MAX_VALUE,
    ) : C3MeshProbe.Io {
        val queries = mutableListOf<String>()
        override fun query(server: String, name: String, timeoutMs: Int, padTo: Int): String {
            queries += "$server $name $padTo"
            if (padTo > maxDnsBytes) throw SocketTimeoutException("Read timed out")
            return answers[server to name] ?: throw SocketTimeoutException("Read timed out")
        }
        override fun systemResolve(name: String) = system(name)
        override fun tcp(host: String, port: Int, timeoutMs: Int) { if (!tcpOk(host, port)) throw java.net.ConnectException("failed to connect to /$host (port $port)") }
        override fun https(url: String, timeoutMs: Int) = code(url)
    }

    private val decl = listOf(
        C3MeshProbe.Target("wg0", "Mesh-1", "10.0.0.0/24", "fd0c:1d00::/64", "10.0.0.1", "fd0c:1d00::1", "10.0.0.1",
            "c3-infra-api.mesh.diegonmarcos.com", "https://c3-infra-api.mesh.diegonmarcos.com/"),
        C3MeshProbe.Target("wg-public", "Mesh-2", "10.1.0.0/24", "fd0c:1d01::/64", "10.1.0.1", "fd0c:1d01::1", "10.1.0.1",
            "maddy.mesh.diegonmarcos.com", ""),
    )
    private val fleet = listOf("10.0.0.1", "10.1.0.1")
    private val publics = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")
    private val peers = listOf(
        C3MeshProbe.Peer("gcp-proxy", "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc=", "35.226.147.64:443", "10.0.0.0/24, fd0c:1d00::/64", 115),
        C3MeshProbe.Peer("oci-analytics", "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc=", "129.151.228.66:51821", "10.1.0.0/24, fd0c:1d01::/64, 2603:c026:c104:8f00:ff9b::/96", 40),
    )
    /** What the hubs answer (hickory's `*` wildcard for wg0, unbound forwarding to it for wg-public); every public resolver says NXDOMAIN. */
    private val wire: Map<Pair<String, String>, String> = buildMap {
        for (t in decl) {
            val a = if (t.id == "wg0") "10.0.0.6" else "10.0.0.3"
            for (f in fleet) put(f to t.name, a)
            for (p in publics) put(p to t.name, "NXDOMAIN")
        }
    }
    private fun input(resolvers: List<String>, peers: List<C3MeshProbe.Peer> = this.peers, up: Boolean = true) = C3MeshProbe.Input(
        tunnelUp = up, peers = peers, resolvers = resolvers, fleetResolvers = fleet, interfaceMtu = "1280", freshS = 150,
        targets = decl, mtuSizes = listOf(576, 1200, 1280, 1380, 1420), names = listOf("authelia.mesh.diegonmarcos.com"),
    )
    private var t = 0L
    private val clock = { (t++) * 7 }
    private val nxAndroid: (String) -> List<String> = { throw UnknownHostException("Unable to resolve host \"$it\": No address associated with hostname") }
    private val fixedAndroid: (String) -> List<String> = { n -> listOf(if (n.startsWith("maddy")) "10.0.0.3" else "10.0.0.6") }

    @Test fun `the reported phone - hubs answer, a public resolver answers first, Android fails`() {
        val r = C3MeshProbe.run(input(fleet + publics), FakeMeshIo(wire, nxAndroid), clock)
        for (row in r.rows) {
            assertEquals(row.target.label, true, row.cell("inside").ok)
            assertEquals(row.target.label, true, row.cell("route").ok)
            assertEquals("the mesh's own resolver answers: ${row.cell("resolver").detail}", true, row.cell("resolver").ok)
            assertEquals(false, row.cell("system").ok)
            assertTrue(row.cell("system").detail, "No address associated with hostname" in row.cell("system").detail)
            val by = row.cell("answered_by")
            assertEquals(by.detail, false, by.ok)
            assertTrue(by.detail, "NXDOMAIN" in by.detail && "1.1.1.1" in by.detail && "8.8.8.8" in by.detail)
            assertFalse(by.detail, "10.0.0.1," in by.detail)
            assertEquals(false, row.cell(C3MeshProbe.OVERALL).ok)
            assertTrue(row.cell(C3MeshProbe.OVERALL).detail, "system" in row.cell(C3MeshProbe.OVERALL).detail)
        }
        // DNS per resolver: every server Android holds was asked, the public ones say NXDOMAIN and are named as not fleet
        val dns = r.steps.filter { it.group == "dns" }
        assertEquals(decl.size * (fleet + publics).size, dns.size)
        assertTrue(dns.filter { it.id.startsWith("1.1.1.1 ") }.all { it.ok == false && "not a fleet resolver" in it.detail && "NXDOMAIN" in it.detail })
        assertTrue(dns.filter { it.id.startsWith("10.0.0.1 ") }.all { it.ok == true })
        // the report carries the evidence as text
        val text = C3MeshProbe.text(r)
        assertTrue(text, "FAIL 1.1.1.1 c3-infra-api.mesh.diegonmarcos.com" in text)
        assertTrue(text, "PASS 10.0.0.1 c3-infra-api.mesh.diegonmarcos.com" in text)
        println("#1797 probe, reported phone (DNS in effect: ${(fleet + publics).joinToString(", ")}):\n$text")
    }

    @Test fun `the fixed phone - fleet resolvers only, every mesh passes`() {
        val r = C3MeshProbe.run(input(fleet), FakeMeshIo(wire, fixedAndroid), clock)
        for (row in r.rows) {
            for (c in C3MeshProbe.COLUMNS) assertTrue("${row.target.label} $c: ${row.cell(c).detail}", row.cell(c).ok != false)
            assertEquals(true, row.cell(C3MeshProbe.OVERALL).ok)
            assertTrue(row.cell("answered_by").text in fleet)
        }
        assertEquals("10.0.0.1", r.rows[0].cell("answered_by").text)
        assertEquals("HTTP 401", r.rows[0].cell("service").text)
        assertTrue("Mesh-2's service is its hub's resolver port", r.rows[1].cell("service").detail.startsWith("10.1.0.1:53"))
        println("#1797 probe, fixed phone (DNS in effect: ${fleet.joinToString(", ")}):\n" + C3MeshProbe.text(r))
    }

    @Test fun `a route gap, a stale handshake and a dead hub each fail their own cell only`() {
        val gap = peers.mapIndexed { i, p -> if (i == 0) p.copy(allowedIps = "10.0.0.0/24") else p }
        val r1 = C3MeshProbe.run(input(fleet, gap), FakeMeshIo(wire, fixedAndroid), clock)
        assertEquals(false, r1.rows[0].cell("route").ok)
        assertTrue(r1.rows[0].cell("route").detail, "fd0c:1d00::/64 not in gcp-proxy" in r1.rows[0].cell("route").detail)
        assertEquals("control: Mesh-2's route is whole", true, r1.rows[1].cell("route").ok)

        val stale = peers.mapIndexed { i, p -> if (i == 1) p.copy(handshakeAgeS = 900) else p }
        val r2 = C3MeshProbe.run(input(fleet, stale), FakeMeshIo(wire, fixedAndroid), clock)
        assertEquals(false, r2.rows[1].cell("inside").ok)
        assertEquals(true, r2.rows[0].cell("inside").ok)
        assertEquals(false, r2.steps.single { it.group == "handshake" && it.id == "oci-analytics" }.ok)

        val r3 = C3MeshProbe.run(input(fleet), FakeMeshIo(wire, fixedAndroid, tcpOk = { h, _ -> h != "10.1.0.1" && h != "fd0c:1d01::1" }), clock)
        assertEquals(false, r3.rows[1].cell("hub").ok); assertEquals(false, r3.rows[1].cell("ipv6").ok); assertEquals(false, r3.rows[1].cell("service").ok)
        assertEquals(true, r3.rows[0].cell("hub").ok)
        assertNull("no latency without a hub answer", r3.rows[1].cell("latency").ok)

        val down = C3MeshProbe.run(input(fleet, up = false), FakeMeshIo(wire, nxAndroid), clock)
        assertTrue(down.rows.all { it.cell("inside").ok == false && it.cell("inside").detail == "the tunnel is down" })
    }

    @Test fun `the path column names the rung carrying the tunnel`() {
        val relay = C3MeshProbe.run(input(fleet).copy(path = "relay", pathDetail = "vpn.diegonmarcos.com -> 129.151.228.66"), FakeMeshIo(wire, fixedAndroid), clock)
        assertEquals("relay", relay.rows[0].cell("path").text); assertEquals(true, relay.rows[0].cell("path").ok)
        assertTrue(relay.rows[0].cell("path").detail.contains("129.151.228.66"))
        val none = C3MeshProbe.run(input(fleet).copy(path = "none", pathDetail = "UDP: no handshake in 8 s"), FakeMeshIo(wire, fixedAndroid), clock)
        assertEquals(false, none.rows[0].cell("path").ok); assertEquals(false, none.rows[0].cell(C3MeshProbe.OVERALL).ok)
        assertEquals("UDP", C3MeshProbe.run(input(fleet).copy(path = "direct"), FakeMeshIo(wire, fixedAndroid), clock).rows[1].cell("path").text)
    }

    @Test fun `the MTU probe reports the largest padded query the path answers`() {
        val io = FakeMeshIo(wire, fixedAndroid, maxDnsBytes = 1380 - C3MeshProbe.IP_UDP_OVERHEAD)
        val r = C3MeshProbe.run(input(fleet), io, clock)
        assertEquals(1380, r.mtuLargest)
        val mtu = r.steps.filter { it.group == "mtu" }
        assertEquals(false, mtu.single { it.id == "1420 B" }.ok)
        assertEquals(true, mtu.single { it.id == "1280 B" }.ok)
        assertTrue(mtu.last().detail, "largest answered: 1380 B" in mtu.last().detail && "interface MTU 1280" in mtu.last().detail)
        assertTrue(io.queries.any { it.endsWith(" ${1420 - C3MeshProbe.IP_UDP_OVERHEAD}") })
    }

    @Test fun `a padded query is exactly the size asked and its answer parses`() {
        for (size in listOf(548, 1172, 1352, 1392)) {
            val (q, _) = C3MeshProbe.buildQuery(0x1234, "c3-infra-api.mesh.diegonmarcos.com", size)
            assertEquals(size, q.size)
        }
        val (plain, end) = C3MeshProbe.buildQuery(0x1234, "maddy.mesh.diegonmarcos.com", 0)
        // a reply: the query with QR set, ANCOUNT 1, then one A record by compression pointer
        val reply = plain.copyOf(end) + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, 0, 3)
        reply[2] = 0x81.toByte(); reply[3] = 0x80.toByte(); reply[7] = 1
        assertEquals("10.0.0.3", C3MeshProbe.parseAnswer(reply, reply.size, 0x1234, end))
        reply[3] = 0x83.toByte()
        assertEquals("NXDOMAIN", C3MeshProbe.parseAnswer(reply, reply.size, 0x1234, end))
    }

    @Test fun `subnet containment, both families`() {
        assertTrue(C3MeshProbe.contains("10.0.0.0/24", "10.0.0.1"))
        assertFalse(C3MeshProbe.contains("10.0.0.0/24", "10.1.0.1"))
        assertTrue(C3MeshProbe.contains("fd0c:1d00::/64", "fd0c:1d00::1"))
        assertFalse(C3MeshProbe.contains("fd0c:1d00::/64", "fd0c:1d01::1"))
        assertFalse("families never mix", C3MeshProbe.contains("10.0.0.0/8", "fd0c:1d00::1"))
        assertTrue(C3MeshProbe.covers("10.0.0.0/8", "10.1.0.0/24"))
        assertFalse(C3MeshProbe.covers("10.1.0.0/24", "10.0.0.0/8"))
        assertTrue(C3MeshProbe.covers("0.0.0.0/0", "10.1.0.0/24"))
        assertFalse("a name is never a literal", C3MeshProbe.contains("10.0.0.0/24", "maddy.mesh.diegonmarcos.com"))
    }

    @Test fun `the store reads the servers Android holds out of the DNS line`() {
        val line = "Public open: 10.0.0.1, 10.1.0.1, 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 8.8.8.8, 8.8.4.4, 2001:4860:4860::8888 via VPN"
        assertEquals(fleet + publics, MeshStore.ipLiterals(line))
        assertEquals(listOf("10.0.0.1"), MeshStore.ipLiterals("Private only · 10.0.0.1"))
        assertEquals(emptyList<String>(), MeshStore.ipLiterals("none"))
    }
}

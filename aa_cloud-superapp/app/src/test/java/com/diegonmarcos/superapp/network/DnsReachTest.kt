package com.diegonmarcos.superapp.network

import android.app.Application
import com.diegonmarcos.superapp.network.DnsReach.Lookups
import com.diegonmarcos.superapp.network.DnsReach.PathCause
import com.diegonmarcos.superapp.network.DnsReach.Reach
import com.diegonmarcos.superapp.network.DnsReach.Verdict
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1899 The DNS page's reachability test by IP: the targets come from the declaration this build
 * baked (BuildConfig.UI_DNS_B64 ← build.json::ui.dns.reachability), and the verdict and the path
 * comparison are held for EVERY combination of their inputs. No declared address is named here:
 * the fixtures use TEST-NET-1 and loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DnsReachTest {

    private val d = FleetDns.decl.reach
    private val tls = DnsReach.Check("tls", "TLS", 443, true)
    private val tcp = DnsReach.Check("tcp", "TCP", 53, false)
    private val site = DnsReach.Target("a", "Site A", "192.0.2.1", "a.example")
    private fun row(path: String, ok: Boolean, c: DnsReach.Check = tls, t: DnsReach.Target = site, error: String = if (ok) "" else "SocketTimeoutException: timeout") =
        DnsReach.Row(t, c, path, ok, 42, if (ok) "direct (Wi-Fi)" else "", error)

    // ── the declared targets ─────────────────────────────────────────────

    @Test fun theShippedDeclarationNamesTheThreeSitesByIpWithATlsNameEach() {
        assertEquals(listOf("cloudflare", "google", "quad9"), d.targets.map { it.id })
        for (t in d.targets) {
            assertTrue("${t.id}: ${t.ip} is not an IP literal", DnsReach.isIpLiteral(t.ip))
            assertTrue("${t.id} has no label", t.label.isNotBlank())
            assertTrue("${t.id}: tls_name ${t.tlsName} must be a name, not an address", t.tlsName.contains('.') && !DnsReach.isIpLiteral(t.tlsName))
        }
        assertEquals("each site at its own address", d.targets.size, d.targets.map { it.ip }.distinct().size)
        assertEquals("a TLS handshake on 443", listOf(443), d.checks.filter { it.tls }.map { it.port })
        assertEquals("a plain TCP connect on 53", listOf(53), d.checks.filter { !it.tls }.map { it.port })
        assertTrue("connect_timeout_ms ${d.timeoutMs}", d.timeoutMs in 500..15000)
    }

    @Test fun theTargetsAreReadFromTheDeclarationInOrder() {
        val got = DnsReach.parse(JSONObject("""{"connect_timeout_ms": 1234,
            "checks": [{"id": "tls", "label": "TLS", "port": 8443, "tls": true}, {"id": "p", "label": "TCP", "port": 5353}],
            "targets": [{"id": "b", "label": "Site B", "ip": "192.0.2.2", "tls_name": "b.example"},
                        {"id": "six", "label": "Site 6", "ip": "2001:db8::1", "tls_name": "six.example"}]}"""))
        assertEquals(1234, got.timeoutMs)
        assertEquals(listOf(DnsReach.Check("tls", "TLS", 8443, true), DnsReach.Check("p", "TCP", 5353, false)), got.checks)
        assertEquals(listOf(DnsReach.Target("b", "Site B", "192.0.2.2", "b.example"), DnsReach.Target("six", "Site 6", "2001:db8::1", "six.example")), got.targets)
        // what the page shows before a run is those targets and checks
        val plan = DnsReach.planText(got)
        for (w in listOf("Site B 192.0.2.2", "Site 6 2001:db8::1", "TLS :8443", "TCP :5353")) assertTrue("$w not in: $plan", w in plan)
    }

    @Test fun anAbsentBlockDeclaresNothingAndSaysNotRun() {
        val none = DnsReach.parse(null)
        assertEquals(DnsReach.Decl(), none)
        assertEquals(Verdict.NOT_RUN.text, DnsReach.planText(none))
        assertEquals(DnsReach.Decl(), FleetDns.parse("""{"default_preset": "m", "presets": [], "mesh_down": {"tunnel_name": "t", "addresses": []}}""").reach)
    }

    @Test fun aTargetThatIsANameIsRefusedBecauseItWouldNeedDns() {
        fun decl(ip: String, name: String = "a.example") = JSONObject(
            """{"checks": [{"id": "tls", "label": "TLS", "port": 443, "tls": true}], "targets": [{"id": "a", "label": "A", "ip": "$ip", "tls_name": "$name"}]}""")
        assertThrows(IllegalArgumentException::class.java) { DnsReach.parse(decl("a.example")) }
        assertThrows(IllegalArgumentException::class.java) { DnsReach.parse(decl("192.0.2.256")) }
        assertThrows("a TLS check needs the name the certificate is checked against", IllegalArgumentException::class.java) { DnsReach.parse(decl("192.0.2.1", "")) }
        assertEquals(1, DnsReach.parse(decl("192.0.2.1")).targets.size) // control
    }

    @Test fun onlyAddressLiteralsAreIpLiterals() {
        for (ok in listOf("192.0.2.1", "0.0.0.0", "255.255.255.255", "2001:db8::1", "::1", "fd00:1:1:2::1")) assertTrue(ok, DnsReach.isIpLiteral(ok))
        for (no in listOf("", "a.example", "192.0.2", "192.0.2.1.5", "192.0.2.300", "192.0.2.1:53", "localhost", "dead.beef", "1.2.3.4 "))
            assertFalse(no, DnsReach.isIpLiteral(no))
    }

    // ── the verdict: every combination ───────────────────────────────────

    @Test fun theVerdictCoversEveryCombinationOfIpReachabilityAndNameLookups() {
        val want = mapOf(
            (Reach.ALL to Lookups.OK) to Verdict.HEALTHY,
            (Reach.ALL to Lookups.FAILING) to Verdict.DNS_ONLY,
            (Reach.ALL to Lookups.MIXED) to Verdict.PARTIAL,
            (Reach.ALL to Lookups.NOT_RUN) to Verdict.NAMES_UNTESTED,
            (Reach.SOME to Lookups.OK) to Verdict.PARTIAL,
            (Reach.SOME to Lookups.FAILING) to Verdict.PARTIAL,
            (Reach.SOME to Lookups.MIXED) to Verdict.PARTIAL,
            (Reach.SOME to Lookups.NOT_RUN) to Verdict.PARTIAL,
            (Reach.NONE to Lookups.OK) to Verdict.NETWORK,
            (Reach.NONE to Lookups.FAILING) to Verdict.NETWORK,
            (Reach.NONE to Lookups.MIXED) to Verdict.NETWORK,
            (Reach.NONE to Lookups.NOT_RUN) to Verdict.NETWORK,
            (Reach.NOT_RUN to Lookups.OK) to Verdict.NOT_RUN,
            (Reach.NOT_RUN to Lookups.FAILING) to Verdict.NOT_RUN,
            (Reach.NOT_RUN to Lookups.MIXED) to Verdict.NOT_RUN,
            (Reach.NOT_RUN to Lookups.NOT_RUN) to Verdict.NOT_RUN,
        )
        assertEquals("every combination is listed", Reach.values().size * Lookups.values().size, want.size)
        for (ip in Reach.values()) for (names in Lookups.values())
            assertEquals("$ip / $names", want.getValue(ip to names), DnsReach.verdict(ip, names))
        assertEquals("every verdict is reached", Verdict.values().toSet(), want.values.toSet())
    }

    @Test fun theThreeVerdictsOfTheTicketReadAsStated() {
        assertTrue(Verdict.DNS_ONLY.text.startsWith("DNS is the only issue"))
        assertTrue(Verdict.NETWORK.text.startsWith("Network/VPN issue, not DNS"))
        assertTrue(Verdict.PARTIAL.text.startsWith("Partial"))
        assertEquals("only a clean or untested-names run is not red", setOf(Verdict.HEALTHY, Verdict.NAMES_UNTESTED), Verdict.values().filter { it.ok }.toSet())
    }

    @Test fun thePathComparisonCoversEveryCombinationOfTheTwoPaths() {
        fun want(vpn: Reach, direct: Reach) = when {
            vpn == Reach.NOT_RUN -> PathCause.NOT_RUN
            direct == Reach.NOT_RUN -> PathCause.NO_DIRECT
            else -> mapOf(
                (Reach.ALL to Reach.ALL) to PathCause.BOTH_OK,
                (Reach.ALL to Reach.SOME) to PathCause.DIRECT_BLOCKED,
                (Reach.ALL to Reach.NONE) to PathCause.DIRECT_BLOCKED,
                (Reach.SOME to Reach.ALL) to PathCause.VPN_PATH,
                (Reach.NONE to Reach.ALL) to PathCause.VPN_PATH,
                (Reach.SOME to Reach.SOME) to PathCause.UNDERLYING,
                (Reach.SOME to Reach.NONE) to PathCause.UNDERLYING,
                (Reach.NONE to Reach.SOME) to PathCause.UNDERLYING,
                (Reach.NONE to Reach.NONE) to PathCause.UNDERLYING,
            ).getValue(vpn to direct)
        }
        val seen = HashSet<PathCause>()
        for (vpn in Reach.values()) for (direct in Reach.values()) {
            assertEquals("$vpn / $direct", want(vpn, direct), DnsReach.compare(vpn, direct))
            seen += DnsReach.compare(vpn, direct)
        }
        assertEquals("every cause is reached", PathCause.values().toSet(), seen)
    }

    @Test fun reachCountsOnePathAtATimeAndLookupsCountNames() {
        val rows = listOf(row(DnsReach.PATH_VPN, true), row(DnsReach.PATH_VPN, false, tcp), row(DnsReach.PATH_DIRECT, true), row(DnsReach.PATH_DIRECT, true, tcp))
        assertEquals(Reach.SOME, DnsReach.reach(rows, DnsReach.PATH_VPN))
        assertEquals(Reach.ALL, DnsReach.reach(rows, DnsReach.PATH_DIRECT))
        assertEquals(Reach.NONE, DnsReach.reach(rows.filter { !it.reachable }, DnsReach.PATH_VPN))
        assertEquals(Reach.NOT_RUN, DnsReach.reach(emptyList(), DnsReach.PATH_VPN))
        assertEquals(Reach.NOT_RUN, DnsReach.reach(rows.filter { it.path == DnsReach.PATH_VPN }, DnsReach.PATH_DIRECT))
        assertEquals(Lookups.NOT_RUN, DnsReach.lookups(emptyList()))
        assertEquals(Lookups.OK, DnsReach.lookups(listOf(true, true)))
        assertEquals(Lookups.FAILING, DnsReach.lookups(listOf(false, false)))
        assertEquals(Lookups.MIXED, DnsReach.lookups(listOf(true, false)))
    }

    @Test fun theVerdictOfARunIsTheNormalPathHeldAgainstTheNames() {
        fun run(vpnOk: Boolean, directOk: Boolean, nameOk: Boolean) = DnsReach.Outcome(
            listOf(row(DnsReach.PATH_VPN, vpnOk), row(DnsReach.PATH_DIRECT, directOk)),
            listOf(DnsReach.NameRow("a.example", nameOk, if (nameOk) "192.0.2.9" else "UnknownHostException: a.example")), vpnUp = true, directNet = "Wi-Fi")
        assertEquals(Verdict.DNS_ONLY, run(vpnOk = true, directOk = true, nameOk = false).verdict)
        assertEquals(Verdict.HEALTHY, run(vpnOk = true, directOk = false, nameOk = true).verdict)
        assertEquals(PathCause.DIRECT_BLOCKED, run(vpnOk = true, directOk = false, nameOk = true).cause)
        // the direct path answering does not hide a dead normal path: that is the VPN, not DNS
        assertEquals(Verdict.NETWORK, run(vpnOk = false, directOk = true, nameOk = false).verdict)
        assertEquals(PathCause.VPN_PATH, run(vpnOk = false, directOk = true, nameOk = false).cause)
        assertEquals(PathCause.UNDERLYING, run(vpnOk = false, directOk = false, nameOk = false).cause)
    }

    // ── a row: reachable, latency, path, error ───────────────────────────

    @Test fun theCarryingInterfaceIsTheOneOwningTheSocketsLocalAddress() {
        val nets = listOf(DnsReach.Net("VPN", true, listOf("192.0.2.77", "2001:db8::77")), DnsReach.Net("Wi-Fi", false, listOf("198.51.100.4")))
        assertEquals("VPN mesh tunnel", DnsReach.carriedBy("192.0.2.77", nets))
        assertEquals("VPN mesh tunnel", DnsReach.carriedBy("2001:db8::77", nets))
        assertEquals("direct (Wi-Fi)", DnsReach.carriedBy("198.51.100.4", nets))
        assertEquals("interface 203.0.113.1", DnsReach.carriedBy("203.0.113.1", nets))
        assertEquals("", DnsReach.carriedBy(null, nets))
    }

    @Test fun aCheckConnectsByAddressAndATlsCheckIsNotMetByABareTcpAccept() {
        val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 8, lo).use { server ->
            val accept = Thread { runCatching { while (true) server.accept().close() } }.apply { isDaemon = true; start() }
            val here = DnsReach.Target("lo", "Loopback", "127.0.0.1", "lo.example")
            val nets = listOf(DnsReach.Net("loopback", false, listOf("127.0.0.1")))
            var bound = 0
            val open = DnsReach.probe(here, DnsReach.Check("tcp", "TCP", server.localPort, false), DnsReach.PATH_DIRECT, 2000, nets) { bound++ }
            assertTrue(open.error, open.reachable)
            assertEquals("", open.error)
            assertEquals("direct (loopback)", open.carriedBy)
            assertEquals("the direct run binds its socket before it connects", 1, bound)
            assertTrue("latency ${open.latencyMs}", open.latencyMs in 0..2000)
            // the same port, as a TLS check: the TCP connect succeeds, the handshake does not
            val noTls = DnsReach.probe(here, DnsReach.Check("tls", "TLS", server.localPort, true), DnsReach.PATH_VPN, 2000, nets, null)
            assertFalse("a bare TCP accept passed as TLS", noTls.reachable)
            assertTrue(noTls.error, noTls.error.isNotBlank())
            assertEquals("the connect was made, so the interface is known", "direct (loopback)", noTls.carriedBy)
            accept.interrupt()
        }
        val closed = ServerSocket(0, 1, lo).use { it.localPort } // free again: nothing listens
        val shut = DnsReach.probe(DnsReach.Target("lo", "Loopback", "127.0.0.1", ""), DnsReach.Check("tcp", "TCP", closed, false), DnsReach.PATH_VPN, 2000, emptyList(), null)
        assertFalse(shut.reachable)
        assertTrue(shut.error, shut.error.startsWith("ConnectException"))
        assertEquals("no connection: no interface", "", shut.carriedBy)
    }

    @Test fun aNameIsNeverLookedUpByACheck() {
        val named = DnsReach.probe(DnsReach.Target("n", "Named", "a.example", "a.example"), tcp, DnsReach.PATH_VPN, 2000, emptyList(), null)
        assertFalse(named.reachable)
        assertTrue(named.error, "not an IP literal" in named.error)
    }

    @Test fun theReadOutShowsVerdictPathsNamesAndEveryRowWithItsFourFields() {
        val out = DnsReach.Outcome(
            listOf(row(DnsReach.PATH_VPN, true), row(DnsReach.PATH_VPN, false, tcp), row(DnsReach.PATH_DIRECT, true), row(DnsReach.PATH_DIRECT, true, tcp)),
            listOf(DnsReach.NameRow("a.example", false, "UnknownHostException: a.example")), vpnUp = true, directNet = "Wi-Fi")
        val t = DnsReach.text(out)
        for (w in listOf(
            "✗ " + Verdict.PARTIAL.text, "Paths: " + PathCause.VPN_PATH.text, "inside VPN: 1/2 reachable", "direct, bound to Wi-Fi: 2/2 reachable",
            "a.example: ✗ FAILED (UnknownHostException: a.example)", "Site A 192.0.2.1", "TLS :443 (a.example) · path: inside VPN", "TCP :53 · path: direct, bound to Wi-Fi",
            "reachable: yes · latency: 42 ms · carried by: direct (Wi-Fi)", "reachable: NO · latency: 42 ms · carried by: nothing (no connection)",
            "error: SocketTimeoutException: timeout",
        )) assertTrue("'$w' not in:\n$t", w in t)
        assertEquals("one error line per failed row", 1, t.split("\n").count { it.trim().startsWith("error: ") })
        // with no VPN up the normal path says so, and a missing direct network is named
        val bare = DnsReach.text(DnsReach.Outcome(listOf(row(DnsReach.PATH_VPN, true)), emptyList(), vpnUp = false, directNet = null))
        for (w in listOf("normal path (no VPN up): 1/1 reachable", "direct: not run (no non-VPN network)", "Paths: " + PathCause.NO_DIRECT.text, "✓ " + Verdict.NAMES_UNTESTED.text, "none run"))
            assertTrue("'$w' not in:\n$bare", w in bare)
    }
}

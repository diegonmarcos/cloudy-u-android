package com.diegonmarcos.superapp.network

import android.app.Application
import com.wireguard.android.backend.BackendException
import com.wireguard.config.InetNetwork
import com.wireguard.crypto.KeyPair
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #740 Fleet DNS: what each declared preset puts on the VPN, on the
 * declaration THIS build baked (BuildConfig.UI_DNS_B64 ← build.json::ui.dns).
 * No resolver address of the fleet is named here: [FLEET] is a stand-in for
 * the Cloud Mesh DNS field, and every expectation is read off the declaration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetDnsTest {

    private val d = FleetDns.decl
    private val FLEET = listOf("192.0.2.53", "192.0.2.54") // TEST-NET-1, stands in for the mesh field
    private val publics = d.presets.filter { it.kind == FleetDns.KIND_PUBLIC && it.available }
    private val privateOnly = d.presets.single { it.kind == FleetDns.KIND_PRIVATE && !it.fallbackAllowed }
    private val privateFb = d.presets.single { it.kind == FleetDns.KIND_PRIVATE && it.fallbackAllowed }
    private fun servers(id: String, fb: List<String> = emptyList(), fleet: List<String> = FLEET) =
        FleetDns.vpnServers(d, id, fb, fleet)

    @Test fun defaultPresetIsDeclaredAndUsable() {
        val p = d.preset(d.defaultPreset)
        assertTrue("default ${d.defaultPreset} is not a declared preset", p != null)
        assertTrue("default ${d.defaultPreset} is unavailable", p!!.available)
    }

    @Test fun eachPublicPresetIsItsServersThenItsFallback() {
        assertEquals("expected the open and the encrypted public presets", 2, publics.size)
        for (p in publics) {
            assertTrue("${p.id} declares no primary", p.servers.isNotEmpty())
            assertTrue("${p.id} declares no fallback", p.fallback.isNotEmpty())
            // #1924 a public preset IS its declared list on the VPN, mesh up or not: never a mesh address
            assertEquals(p.id, (p.servers + p.fallback).distinct(), servers(p.id))
            assertTrue(p.id, servers(p.id).none { it in FLEET })
            // mesh down: the preset's own servers, primary then fallback
            assertEquals(p.id, (p.servers + p.fallback).distinct(), down(p.id))
        }
    }

    /** #1924 the owner's phone: Public open advertised 10.0.0.1/10.1.0.1 while the hub was down. */
    @Test fun publicOpenIsExactlyCloudflareAndGoogleAndNeverAMeshAddress() {
        val p = d.preset("public_open")!!
        val want = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "2606:4700:4700::1111", "2001:4860:4860::8888")
        assertEquals(want.toSet(), servers("public_open", fleet = listOf("10.0.0.1", "10.1.0.1")).toSet())
        assertTrue(servers("public_open", fleet = listOf("10.0.0.1", "10.1.0.1")).none { it.startsWith("10.") })
        for (healthy in listOf(true, false)) assertEquals(want.toSet(),
            FleetDns.vpnServers(d, "public_open", emptyList(), listOf("10.0.0.1", "10.1.0.1"), healthy).toSet())
        // mutation: a mesh IP injected into the declaration is caught by the same assertion
        val bad = d.copy(presets = d.presets.map { if (it.id == p.id) it.copy(servers = it.servers + "10.0.0.1") else it })
        val got = FleetDns.vpnServers(bad, "public_open", emptyList(), listOf("10.0.0.1", "10.1.0.1"))
        assertTrue("mutation must be visible", got.any { it.startsWith("10.") })
    }

    @Test fun privateWithFallbacksSwitchesItsAdvertisedDnsWithMeshHealth() {
        val fb = listOf("public_open")
        assertEquals(FLEET, FleetDns.vpnServers(d, privateFb.id, fb, FLEET, true))
        val pub = d.preset("public_open")!!
        assertEquals((pub.servers + pub.fallback).distinct(), FleetDns.vpnServers(d, privateFb.id, fb, FLEET, false))
    }

    @Test fun queryPlanSplitsMeshNamesAndOrdersByHealth() {
        val fb = listOf("public_open"); val pub = d.preset("public_open")!!; val own = (pub.servers + pub.fallback).distinct()
        fun plan(id: String, name: String, healthy: Boolean) = FleetDns.queryPlan(d, name, true, healthy, id, fb, true, FLEET)
        assertEquals(FLEET, plan("public_open", "x.mesh.diegonmarcos.com", true))
        assertEquals(own, plan("public_open", "github.com", true))
        assertEquals(own, plan("public_open", "github.com", false))
        assertEquals(FLEET + own, plan(privateFb.id, "github.com", true))
        assertEquals(own + FLEET, plan(privateFb.id, "github.com", false))
        assertEquals(FLEET, plan(privateOnly.id, "github.com", false))
        assertEquals(own, FleetDns.queryPlan(d, "github.com", false, true, "public_open", fb, true, FLEET))
    }

    @Test fun servfailRefusedAndTimeoutAreMeshFailures() {
        assertTrue(FleetDns.meshFailure(2)); assertTrue(FleetDns.meshFailure(5)); assertTrue(FleetDns.meshFailure(null))
        assertFalse(FleetDns.meshFailure(0)); assertFalse(FleetDns.meshFailure(3))
    }

    @Test fun healthFlipsAtOnceAndReappliesOnlyAfterTheDebounce() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val h = FleetDns.MeshHealth(debounceMs = 150) { calls += it }
        h.report(false, "x"); assertFalse(h.healthy)
        h.report(true, "y"); h.report(false, "z") // flapping inside the window: one settle only
        Thread.sleep(80); assertTrue("not before the debounce", calls.isEmpty())
        Thread.sleep(400); assertEquals(listOf(false), calls.toList())
        assertTrue(h.text(), h.text().contains("UNHEALTHY"))
        h.report(true, "back"); Thread.sleep(400); assertEquals(listOf(false, true), calls.toList())
    }

    @Test fun mirrorPutsOnlyTheFleetResolversOnTheVpn() {
        val m = d.presets.single { it.kind == FleetDns.KIND_MIRROR }
        assertEquals(FLEET, servers(m.id))
        // no fleet resolver declared: Mirror stays empty (Android's own)
        assertEquals(emptyList<String>(), servers(m.id, fleet = emptyList()))
        // control: the same call on a public preset with no fleet resolver is its own servers
        assertTrue(servers(publics.first().id, fleet = emptyList()).isNotEmpty())
    }

    @Test fun privateOnlyIsTheFleetResolverAndNothingElse() {
        val everyFallback = publics.map { it.id }
        assertEquals(FLEET, servers(privateOnly.id, everyFallback))
        // control: the fallback preset given the same choices DOES add them once the mesh is down
        assertNotEquals(FLEET, down(privateFb.id, everyFallback))
    }

    @Test fun privateWithFallbacksIsFleetFirstThenTheChosenFallbackInOrder() {
        val first = publics.first()
        // #1797 mesh up: the fleet resolvers only; the chosen fallbacks are the mesh-down list, in order
        assertEquals(FLEET, servers(privateFb.id, listOf(first.id)))
        assertEquals((first.servers + first.fallback).distinct(), down(privateFb.id, listOf(first.id)))
        // no fallback ticked = fleet only (it never invents one)
        assertEquals(FLEET, servers(privateFb.id, emptyList()))
    }

    @Test fun anUnavailablePresetIsNeverChosenNorUsedAsAFallback() {
        val off = d.presets.filter { !it.available }
        assertTrue("WARP is declared unavailable — expected at least one", off.isNotEmpty())
        for (p in off) {
            assertTrue("${p.id} gives no reason", p.unavailableReason.isNotBlank())
            assertEquals(d.defaultPreset, FleetDns.effective(d, p.id).id)
            assertEquals(FLEET, servers(privateFb.id, listOf(p.id)))
        }
    }

    @Test fun aPrivatePresetWithNoFleetResolverFailsLoudly() {
        for (p in listOf(privateOnly, privateFb)) {
            assertThrows(IllegalStateException::class.java) { servers(p.id, publics.map { it.id }, emptyList()) }
        }
        // control: a public preset does not need the fleet resolver
        assertTrue(servers(publics.first().id, fleet = emptyList()).isNotEmpty())
    }

    @Test fun meshNamesGoToTheFleetResolverWhileTheMeshIsUp() {
        assertTrue(d.meshZones.isNotEmpty())
        val preset = down(publics.first().id) // #1797 the preset's own list (mesh up carries the fleet resolvers only)
        for (z in d.meshZones) {
            val name = "probe.$z"
            assertEquals(name, FLEET, FleetDns.upstreamsFor(d, name, true, preset, FLEET))
            // control: mesh down → the preset answers
            assertEquals(name, preset, FleetDns.upstreamsFor(d, name, false, preset, FLEET))
            // a zone is a label boundary, not a string suffix
            assertFalse("probe$z", FleetDns.isMeshName(d, "probe$z"))
        }
        assertTrue(FleetDns.isMeshName(d, d.testMesh))
        assertFalse(FleetDns.isMeshName(d, d.testPublic))
        assertEquals(preset, FleetDns.upstreamsFor(d, d.testPublic, true, preset, FLEET))
    }

    /** #1152 every exportable mesh profile (split and full) routes its own DNS servers through a peer. */
    @Test fun everyMeshProfileRoutesItsFleetResolversThroughThePeers() {
        val profiles = WireGuardProfiles.mesh
        assertTrue("no mesh profiles baked", profiles.any { it.id == "v4-split" } && profiles.any { it.id.endsWith("full") })
        for (pr in profiles) {
            val dns = FleetDns.splitServers(pr.dns)
            assertTrue("${pr.id} has no DNS", dns.isNotEmpty())
            val nets = pr.peers.flatMap { FleetDns.splitServers(it.allowedIps) }.map { InetNetwork.parse(it) }
            for (s in dns) {
                val ip = java.math.BigInteger(1, InetAddress.getByName(s).address)
                val bytes = InetAddress.getByName(s).address.size
                val covered = nets.any { n ->
                    val nb = n.address.address
                    if (nb.size != bytes) return@any false
                    val shift = bytes * 8 - n.mask
                    java.math.BigInteger(1, nb).shiftRight(shift) == ip.shiftRight(shift)
                }
                assertTrue("${pr.id}: DNS $s is not inside any peer's AllowedIPs", covered)
            }
        }
    }

    // ── #751 the mesh-down tunnel ───────────────────────────────────────

    private fun down(id: String, fb: List<String> = emptyList(), fleet: List<String> = FLEET) =
        FleetDns.meshDownServers(d, id, fb, fleet)
    private val self = KeyPair()
    private val sink = KeyPair()
    private fun downConfig(id: String, fb: List<String> = emptyList()) =
        FleetDns.meshDownConfig(d, down(id, fb), FLEET, self, sink)

    @Test fun withoutTheMeshEachPresetKeepsWhatItCanStillReach() {
        for (p in publics) assertEquals(p.id, (p.servers + p.fallback).distinct(), down(p.id))
        val first = publics.first()
        // the fleet resolver is behind the mesh: Private with fallbacks is its fallbacks alone
        assertEquals((first.servers + first.fallback).distinct(), down(privateFb.id, listOf(first.id)))
        // control: the same choice with the mesh up does put the fleet resolver first
        assertEquals(FLEET, servers(privateFb.id, listOf(first.id)).take(FLEET.size))
        // nothing else to fall back to: it stays the fleet resolver, never a public one
        assertEquals(FLEET, down(privateOnly.id, publics.map { it.id }))
        assertEquals(FLEET, down(privateFb.id))
        assertEquals(emptyList<String>(), down(d.presets.single { it.kind == FleetDns.KIND_MIRROR }.id))
        assertThrows(IllegalStateException::class.java) { down(privateOnly.id, fleet = emptyList()) }
    }

    @Test fun theMeshDownTunnelRoutesNothingButAnUnreachableFleetResolver() {
        assertTrue("ui.dns.mesh_down.tunnel_name is empty", d.meshDownTunnel.isNotBlank())
        assertTrue("ui.dns.mesh_down.addresses is empty", d.meshDownAddresses.isNotEmpty())
        // Mirror releases the slot
        assertEquals(null, downConfig(d.presets.single { it.kind == FleetDns.KIND_MIRROR }.id))
        // a public preset: its DNS servers on the VPN, the declared addresses, no peer = no route
        val pub = downConfig(publics.first().id)!!
        // compared through the parser: Java prints an IPv6 address in its long form
        assertEquals(down(publics.first().id).map { InetAddress.getByName(it).hostAddress },
                     pub.getInterface().dnsServers.map { it.hostAddress })
        assertEquals(d.meshDownAddresses.map { InetNetwork.parse(it).toString() },
                     pub.getInterface().addresses.map { it.toString() })
        assertTrue("a public mesh-down tunnel routes something", pub.peers.isEmpty())
        // Private only: one endpoint-less peer whose only routes are the fleet resolver's own /32s
        val priv = downConfig(privateOnly.id)!!
        assertEquals(1, priv.peers.size)
        val hole = priv.peers.single()
        assertFalse("the blackhole peer has an endpoint", hole.endpoint.isPresent)
        assertEquals(FLEET.map { "$it/32" }.toSet(), hole.allowedIps.map { it.toString() }.toSet())
        // control: with fallbacks to reach, the fleet resolver is neither a server nor a route
        val fb = downConfig(privateFb.id, listOf(publics.first().id))!!
        assertTrue(fb.peers.isEmpty())
        assertTrue(fb.getInterface().dnsServers.none { it.hostAddress in FLEET })
        // same choice, same keys -> the same text, so re-pushing it does not rebuild the VPN
        assertEquals(pub.toWgQuickString(), downConfig(publics.first().id)!!.toWgQuickString())
    }

    @Test fun theWireParserReadsAnAAndAnNxdomain() {
        // header id=0x1234 QR+RD+RA, 1 question "a.", 1 answer c00c A IN ttl=60 → 1.2.3.4
        val q = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
            1, 'a'.code.toByte(), 0, 0, 1, 0, 1)
        val ans = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        val pkt = q + ans
        assertEquals("1.2.3.4", FleetDns.parseAnswer(pkt, pkt.size, 0x1234, q.size))
        val nx = pkt.copyOf().also { it[3] = 0x83.toByte() }
        assertEquals("NXDOMAIN", FleetDns.parseAnswer(nx, nx.size, 0x1234, q.size))
        assertThrows(IllegalStateException::class.java) { FleetDns.parseAnswer(pkt, pkt.size, 0x4321, q.size) }
    }

    // ── #794 is the choice in effect? ───────────────────────────────────

    private val NET = listOf("198.51.100.53") // TEST-NET-2, stands in for the Wi-Fi's own resolver
    private val NOT_AUTHORIZED = "DOWN: " + BackendException.Reason.VPN_NOT_AUTHORIZED.name
    private fun android(servers: List<String>, onVpn: Boolean, mode: String? = "opportunistic", spec: String? = null) =
        FleetDns.AndroidDns(mode, spec, false, null, servers, onVpn)
    private fun verdict(id: String, chosen: Boolean, up: Boolean, idle: String, a: FleetDns.AndroidDns, fb: List<String> = emptyList()) =
        FleetDns.verdict(d, id, fb, chosen, FLEET, up, idle, a, "ENGINE")

    @Test fun aPrivatePresetIsBypassedWhileAndroidPrivateDnsIsInUseOnTheVpn() {
        // #794 Automatic Private DNS sends every lookup over DoT to a validated public server first:
        // the promised servers ARE on the VPN, yet the fleet resolver is never asked.
        val priv = d.presets.first { it.kind == FleetDns.KIND_PRIVATE }
        val want = verdict(priv.id, true, true, "UP", android(emptyList(), true)).promised
        val dot = FleetDns.AndroidDns("opportunistic", null, true, null, want, true)
        val v = verdict(priv.id, true, true, "UP", dot)
        assertFalse("DoT to a public server pre-empts the fleet resolver", v.ok)
        assertTrue(v.why, "Private DNS" in v.why && d.modeLabel(d.privatePresetsRequire) in v.why)
        assertTrue("Off: the servers are asked in order", verdict(priv.id, true, true, "UP", FleetDns.AndroidDns("off", null, false, null, want, true)).ok)
        assertTrue("Automatic with nothing validated is plain DNS in order", verdict(priv.id, true, true, "UP", android(want, true)).ok)
    }

    @Test fun aChosenPresetTheEngineCannotStartForWantOfConsentIsRedAndAsksForIt() {
        // the owner's phone, 2026-10-03: Public open chosen, mesh down, no consent, Android on the Wi-Fi's DNS
        val pub = publics.first()
        val v = verdict(pub.id, chosen = true, up = false, idle = NOT_AUTHORIZED, a = android(NET, onVpn = false))
        assertFalse(v.why, v.ok)
        assertTrue("missing consent is the cause a tap fixes", v.needsConsent)
        assertEquals((pub.servers + pub.fallback).distinct(), v.promised)
        assertEquals(NET, v.actual)
        assertTrue(v.why, "ENGINE" in v.why && "NOT in effect" in v.why && NET.single() in v.why)
        // control: once consent is given and the tunnel carries the choice, the same preset is in effect
        val ok = verdict(pub.id, true, false, "UP", android(v.promised, onVpn = true))
        assertTrue(ok.why, ok.ok)
        assertFalse(ok.needsConsent)
    }

    @Test fun aPresetIsInEffectOnlyWhenAndroidResolvesWithExactlyItsServers() {
        val pub = publics.first().id
        val want = down(pub) // the mesh is down in these calls: the preset's own servers
        assertTrue("the order is Android's business", verdict(pub, true, false, "UP", android(want.reversed(), true)).ok)
        assertFalse("a VPN carrying other servers", verdict(pub, true, false, "UP", android(want.drop(1), true)).ok)
        assertFalse("the right servers, but not on the VPN", verdict(pub, true, false, "UP", android(want, false)).ok)
        // with the mesh up the promise is the mesh tunnel's list
        val up = verdict(privateOnly.id, false, true, "STANDBY", android(FLEET, true))
        assertTrue(up.why, up.ok)
        assertEquals(FLEET, up.promised)
        assertFalse(verdict(privateOnly.id, false, true, "STANDBY", android(NET, false)).ok)
        // #1797 a public preset with the mesh up promises the fleet resolvers only, so it is only in effect carrying them
        val pubUp = verdict(pub, true, true, "UP", android(servers(pub), true))
        assertTrue(pubUp.why, pubUp.ok); assertEquals(down(pub), pubUp.promised)
        assertFalse("mesh resolvers on a public preset are not in effect", verdict(pub, true, true, "UP", android(FLEET, true)).ok)
        // #1797 the phone's reported state: fleet resolvers FIRST but public ones beside them is NOT in effect, and says why
        val mixed = verdict(privateOnly.id, true, true, "UP", android(FLEET + down(pub), true))
        assertFalse(mixed.why, mixed.ok)
        assertTrue(mixed.why, "NXDOMAIN" in mixed.why && down(pub).first() in mixed.why)
    }

    @Test fun onlyMissingConsentAsksForConsent() {
        val pub = publics.first().id
        for (idle in listOf("UP", "STANDBY", "OFF", "DOWN: UNABLE_TO_START_VPN", "DOWN: the engine is not installed")) {
            val v = verdict(pub, true, false, idle, android(NET, false))
            assertFalse(idle, v.ok)
            assertFalse(idle, v.needsConsent)
        }
        // nor when nothing was chosen here or Mirror was: nothing needs the VPN without the mesh then
        assertFalse(verdict(pub, false, false, NOT_AUTHORIZED, android(NET, false)).needsConsent)
        val mirror = d.presets.single { it.kind == FleetDns.KIND_MIRROR }.id
        assertFalse(verdict(mirror, true, false, NOT_AUTHORIZED, android(NET, false)).needsConsent)
        // control: the same idle state on a chosen preset does
        assertTrue(verdict(pub, true, false, NOT_AUTHORIZED, android(NET, false)).needsConsent)
    }

    @Test fun mirrorAndAnUnchosenDefaultWithoutTheMeshAreAndroidsOwnAndFine() {
        val mirror = d.presets.single { it.kind == FleetDns.KIND_MIRROR }.id
        val m = verdict(mirror, true, false, "OFF", android(NET, false))
        assertTrue(m.why, m.ok)
        assertEquals(emptyList<String>(), m.promised)
        val unchosen = verdict(d.defaultPreset, false, false, "OFF", android(NET, false))
        assertTrue(unchosen.why, unchosen.ok)
        assertTrue(unchosen.why, "no preset was chosen" in unchosen.why)
        // control: the same default, chosen, promises servers and is not in effect on the Wi-Fi's DNS
        assertFalse(verdict(d.defaultPreset, true, false, "UP", android(NET, false)).ok)
    }

    @Test fun strictPrivateDnsBypassesAnyPresetAndSaysSo() {
        val pub = publics.first().id
        val v = verdict(pub, true, false, "UP", android(down(pub), true, mode = "hostname", spec = "dot.example"))
        assertFalse(v.ok)
        assertTrue(v.why, "dot.example" in v.why)
        // control: Automatic with the same servers is in effect
        assertTrue(verdict(pub, true, false, "UP", android(down(pub), true)).ok)
    }

    @Test fun aPrivatePresetWithNoFleetResolverIsRedNotAndroidsOwn() {
        val v = FleetDns.verdict(d, privateOnly.id, emptyList(), true, emptyList(), true, "STANDBY", android(NET, false), "ENGINE")
        assertFalse(v.why, v.ok)
        assertTrue(v.why, "cannot be applied" in v.why)
    }
}

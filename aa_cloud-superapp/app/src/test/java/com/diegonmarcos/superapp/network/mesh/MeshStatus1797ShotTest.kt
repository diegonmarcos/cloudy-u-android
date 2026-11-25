package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.hasTestTag
import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import com.diegonmarcos.superapp.network.FleetDns
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1797 CI evidence: the reorganised Cloud Mesh > Status drawn by the real page at Galaxy A37 width, full height,
 * for the phone as reported (tunnel and both handshakes up, Android holding the hubs AND the public resolvers,
 * every mesh name failing) and the same phone after the fix (FleetDns.vpnServers puts the fleet resolvers only
 * on the tunnel). Each PNG is also an assertion of what it shows; each probe report is printed to the test's
 * stdout (the JUnit XML) as the probe output. configs-screens.yml uploads build/configs-screens/mesh-status-1797-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h3200dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeshStatus1797ShotTest : KitPageHarness() {
    private val decl = MeshDecl.baked
    private val publics = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")

    private fun phone(dnsLine: String, io: C3MeshProbe.Io): MeshStore {
        val port = FakePort(up = true).also {
            it.peers = mutableListOf(
                PeerCfg("gcp-proxy", "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc=", "", "35.226.147.64:443", "10.0.0.0/24, fd0c:1d00::/64", "25"),
                PeerCfg("oci-analytics", "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc=", "", "129.151.228.66:51821", "10.1.0.0/24, fd0c:1d01::/64, 2603:c026:c104:8f00:ff9b::/96", "25"),
            )
            it.hs = System.currentTimeMillis() - 115_000
            it.preset = "public_open"
            it.dnsInEffect = dnsLine
            it.path = "udp"
        }
        val store = MeshStore(port, decl, FakeHost(), exec = { it.run() }, meshIo = io)
        store.tab = "status"
        store.poll()
        store.run("mesh.probe")
        return store
    }

    private fun wire(): Map<Pair<String, String>, String> = buildMap {
        for (t in decl.meshes) {
            val a = if (t.id == "wg0") "10.0.0.6" else "10.0.0.3"
            put(t.resolver to t.name, a)
            for (o in decl.meshes) put(o.resolver to t.name, a)
            for (p in publics) put(p to t.name, "NXDOMAIN")
        }
    }

    @Test fun `before - public resolvers beside the hubs, every mesh name fails`() {
        val fleet = FleetDns.splitServers("10.0.0.1, 10.1.0.1")
        val line = "Public open: " + (fleet + publics).joinToString(", ") + " via VPN"
        val s = phone(line, C3MeshProbeTest.FakeMeshIo(wire(), { throw java.net.UnknownHostException("Unable to resolve host \"$it\": No address associated with hostname") }))
        val r = s.meshProfile!!
        assertTrue(r.rows.all { it.cell(C3MeshProbe.OVERALL).ok == false && it.cell("system").ok == false && it.cell("resolver").ok == true })
        assertEquals(false, lightLines(s.snapshot!!, r, s.reach).single { it.id == "dns" }.ok)
        println("#1797 BEFORE probe report:\n" + C3MeshProbe.text(r))
        shoot(s, "mesh-status-1797-before.png")
    }

    @Test fun `after - the fleet resolvers only, every mesh passes`() {
        val fleet = FleetDns.splitServers("10.0.0.1, 10.1.0.1")
        // what the fixed tunnel carries for the reported preset: FleetDns.vpnServers, the code the phone runs
        val carried = FleetDns.vpnServers(FleetDns.decl, "private_only", emptyList(), fleet)
        assertEquals(fleet, carried)
        val line = "Private only: " + carried.joinToString(", ") + " via VPN"
        val s = phone(line, C3MeshProbeTest.FakeMeshIo(wire(), { n -> listOf(if (n.startsWith("maddy")) "10.0.0.3" else "10.0.0.6") }))
        val r = s.meshProfile!!
        for (row in r.rows) assertEquals(row.cell(C3MeshProbe.OVERALL).detail, true, row.cell(C3MeshProbe.OVERALL).ok)
        assertTrue(lightLines(s.snapshot!!, r, s.reach).filter { it.id in setOf("tunnel", "handshake", "dns", "names", "ips") }.all { it.ok == true })
        println("#1797 AFTER probe report:\n" + C3MeshProbe.text(r))
        shoot(s, "mesh-status-1797-after.png")
    }

    private fun shoot(s: MeshStore, name: String) {
        showKit { MeshStatusPage(s) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(MeshTags.row("meshes")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bmp.width step 9) for (y in 0 until bmp.height step 9) seen += bmp.getPixel(x, y)
        assertTrue("$name is blank (${seen.size} colours)", seen.size > 8)
    }
}

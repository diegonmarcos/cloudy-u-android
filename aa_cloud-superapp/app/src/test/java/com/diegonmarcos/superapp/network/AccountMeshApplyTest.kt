package com.diegonmarcos.superapp.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1693 the mesh-apply half of the old VaultCockpitTest (that suite moved to Cloud Account with libs:account):
 * a vault mesh profile lands in THIS app's WireGuard prefs through the upstream parser, and a text the parser
 * rejects writes nothing. Same fixture conf (Address v4 + v6, two peers) and the same assertions; the
 * "matches afterwards" comparison is now the tunnel's own state against the conf (address, peer keys).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountMeshApplyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val peerA = "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc="
    private val peerB = "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc="

    private fun conf(address: String, vararg peers: String) = buildString {
        appendLine("# fixture"); appendLine("[Interface]")
        appendLine("PrivateKey = $zeroKey"); appendLine("Address = $address"); appendLine("DNS = 10.0.0.1")
        peers.forEach { appendLine(); appendLine("[Peer]"); appendLine("PublicKey = $it"); appendLine("Endpoint = 1.2.3.4:51820"); appendLine("AllowedIPs = 10.0.0.0/24") }
    }

    @Test fun `applying a profile writes the tunnel through the WireGuard parser, and then it matches`() {
        val prefs = WireGuardPrefs(ctx)
        val name = "config-v4-split"
        val conf = conf("10.0.0.9/24, fd0c:1d00::9/64", peerA, peerB)
        val wantKeys = setOf(peerA, peerB)
        assertTrue("nothing applied yet", prefs.peers().map { it.publicKey }.toSet() != wantKeys)

        val line = AccountMesh.applyMesh(prefs, name, conf)
        assertTrue(line, line.startsWith("✓"))

        val parsed = com.wireguard.config.Config.parse(java.io.BufferedReader(java.io.StringReader(conf)))
        assertEquals(parsed.getInterface().addresses.joinToString(", "), prefs.interfaceAddress)
        assertTrue(prefs.interfaceAddress.contains("10.0.0.9/24"))
        assertEquals(wantKeys, prefs.peers().map { it.publicKey }.toSet())
        assertEquals(WireGuardPrefs.PROVIDER_CUSTOM, prefs.configProvider)
        assertEquals(name, prefs.tunnelName)
    }

    @Test fun `a conf the parser rejects writes nothing`() {
        val prefs = WireGuardPrefs(ctx)
        val addressBefore = prefs.interfaceAddress
        val line = AccountMesh.applyMesh(prefs, "broken", "[Interface]\nAddress = not-an-address\n")
        assertTrue(line, line.startsWith("✗"))
        assertEquals(addressBefore, prefs.interfaceAddress)
    }
}

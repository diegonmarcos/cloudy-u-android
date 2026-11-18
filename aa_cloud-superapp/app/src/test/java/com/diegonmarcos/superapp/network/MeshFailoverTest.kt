package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.network.MeshTransport.Failover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1798 Auto failover (UDP -> relay -> UDP) and the relay key's source, both pure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MeshFailoverTest {
    private val stale = 300L
    private val now = 10_000_000L
    private fun f(mode: String = "auto", up: Boolean = true, path: String = "direct", age: Long? = 20, since: Long = 0) =
        MeshTransport.failover(mode, up, path, age, stale, since, now)

    @Test fun `UDP that stops handshaking falls to the fallbacks`() {
        assertEquals(Failover.STAY, f(age = 20))
        assertEquals(Failover.TO_FALLBACK, f(age = stale + 1))
        assertEquals("no handshake at all", Failover.TO_FALLBACK, f(age = null))
        for (p in listOf("pinned", "doh")) assertEquals("$p is UDP too", Failover.TO_FALLBACK, f(path = p, age = 900))
    }

    @Test fun `the relay tries UDP again after the retry interval, not before`() {
        assertEquals(Failover.STAY, f(path = "relay", since = now - MeshTransport.UDP_RETRY_MS + 1))
        assertEquals(Failover.RETRY_UDP, f(path = "relay", since = now - MeshTransport.UDP_RETRY_MS))
        assertEquals("a relay with no recorded start waits", Failover.STAY, f(path = "relay", since = 0))
    }

    @Test fun `only Auto, only an up tunnel, never a NONE path`() {
        assertEquals(Failover.STAY, f(mode = "direct", age = null))
        assertEquals(Failover.STAY, f(mode = "relay", path = "relay", since = 1))
        assertEquals(Failover.STAY, f(up = false, age = null))
        assertEquals(Failover.STAY, f(path = "none", age = null))
    }

    @Test fun `the relay key comes from the fleet, a paste overrides it, and neither is ever printed`() {
        val fleet = AppCredentialsContract.Credential("mesh.relay_key", "FLEET-KEY", "from Cloud Account")
        val none = AppCredentialsContract.Credential("mesh.relay_key", "", "TLS-443 relay key is not filed")
        assertEquals(MeshTransport.RelayKey("FLEET-KEY", MeshTransport.SOURCE_FLEET), MeshTransport.pickRelayKey("", fleet))
        assertEquals(MeshTransport.RelayKey("PASTED", MeshTransport.SOURCE_OVERRIDE), MeshTransport.pickRelayKey(" PASTED ", fleet))
        val missing = MeshTransport.pickRelayKey("", none)
        assertEquals("", missing.value)
        assertTrue(missing.source, "not in Cloud Account" in missing.source && "paste it" in missing.source)
        assertFalse(MeshTransport.pickRelayKey("", fleet).toString().contains("FLEET-KEY"))
        assertEquals("the catalogue declares it for the SuperApp", listOf(AppCredentialsContract.PKG_SUPERAPP),
            AppCredentialsContract.entry(MeshTransport.RELAY_KEY_CREDENTIAL)!!.packages)
    }
}

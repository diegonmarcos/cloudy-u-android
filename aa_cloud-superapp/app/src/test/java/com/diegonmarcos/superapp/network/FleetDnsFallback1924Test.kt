package com.diegonmarcos.superapp.network

import android.app.Application
import com.diegonmarcos.cloudlib.sysdns.DnsWire
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1924 The bridge walks FleetDns.queryPlan; a SERVFAIL / REFUSED / timeout from the mesh resolver is a miss, the lookup
 * is retried on the fallbacks and the mesh is reported unhealthy. Fake resolvers stand in for the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetDnsFallback1924Test {
    private val d = FleetDns.decl
    private val MESH = listOf("10.0.0.1", "10.1.0.1")
    private val savedExchange = FleetDnsBridge.exchange

    @After fun restore() { FleetDnsBridge.exchange = savedExchange; FleetDnsBridge.meshObserver = null }

    private fun reply(q: ByteArray, rcode: Int, ip: String? = null): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(q, 0, 2)
        out.write(byteArrayOf(0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, if (ip != null) 1 else 0, 0, 0, 0, 0))
        out.write(q, 12, q.size - 12)
        if (ip != null) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
            out.write(ip.split('.').map { it.toInt().toByte() }.toByteArray())
        }
        return out.toByteArray()
    }

    private fun routes(name: String, preset: String, fb: List<String>, healthy: Boolean) =
        FleetDns.queryPlan(d, name, true, healthy, preset, fb, true, MESH).map {
            FleetDnsBridge.Route("$preset $it", listOf(it), mesh = it in MESH)
        }

    private fun walk(mesh: (ByteArray) -> ByteArray?, preset: String, fb: List<String>, name: String = "github.com", healthy: Boolean = true,
                     asked: MutableList<String> = mutableListOf()): Triple<ByteArray?, List<String>, List<Boolean>> {
        val seen = mutableListOf<Boolean>()
        FleetDnsBridge.forget(name) // the bridge caches answers; an earlier test's answer would hide the walk
        FleetDnsBridge.meshObserver = { seen += it }
        FleetDnsBridge.exchange = { q, s, _ -> asked += s; if (s in MESH) mesh(q) else reply(q, 0, "140.82.112.3") }
        FleetDnsBridge.routes = { routes(name, preset, fb, healthy) }
        return Triple(FleetDnsBridge.walk(DnsWire.query(name, DnsWire.A)), asked, seen)
    }

    @Test fun servfailFromTheMeshResolverFallsBackToPublicAndReportsUnhealthy() {
        // #1979 only a mesh name asks the mesh resolver now; a SERVFAIL there is reported unhealthy and the name is a miss
        val (a, asked, seen) = walk({ q -> reply(q, 2) }, "private_fallbacks", listOf("public_open"), name = "x.mesh.diegonmarcos.com")
        assertNull(a)
        assertTrue(asked.toString(), asked.isNotEmpty() && asked.all { it in MESH })
        assertTrue(seen.toString(), seen.isNotEmpty() && seen.none { it })
        val (b, asked2, _) = walk({ q -> reply(q, 2) }, "private_fallbacks", listOf("public_open"))
        assertNotNull("a public name is answered without the mesh", b)
        assertTrue(asked2.toString(), asked2.none { it in MESH })
    }

    @Test fun refusedAndTimeoutOnAMeshNameAreMissesAndPublicNamesStillResolve() {
        val (a, _, seen) = walk({ q -> reply(q, 5) }, "private_fallbacks", listOf("public_open"), name = "x.mesh.diegonmarcos.com")
        assertNull(a); assertTrue(seen.none { it })
        val (b, _, seen2) = walk({ _ -> throw java.net.SocketTimeoutException("timeout") }, "private_fallbacks", listOf("public_open"))
        assertNotNull(b); assertTrue(seen2.isEmpty())
    }

    @Test fun aHealthyMeshAnswerIsReported() {
        val (a, _, seen) = walk({ q -> reply(q, 0, "10.0.0.9") }, "private_only", emptyList(), name = "x.mesh.diegonmarcos.com")
        assertEquals("10.0.0.9", DnsWire.addresses(a!!).single().hostAddress)
        assertEquals(listOf(true), seen)
    }

    @Test fun publicOpenNeverAsksAMeshAddressForAPublicName() {
        val (a, asked, _) = walk({ q -> reply(q, 2) }, "public_open", emptyList())
        assertNotNull(a); assertTrue(asked.toString(), asked.none { it in MESH })
    }

    @Test fun privateOnlyWithADeadMeshHasNowhereToFall() {
        val (a, asked, _) = walk({ q -> reply(q, 2) }, "private_only", emptyList(), name = "x.mesh.diegonmarcos.com")
        assertNull(a); assertFalse(asked.any { it !in MESH })
    }
}

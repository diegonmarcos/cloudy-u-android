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
 * #1979 Split DNS while the mesh is up: public pages (linktree, the apex, www) go to the public resolvers and never to the
 * mesh resolver (the hub answers the whole zone with its own address); mesh and declared private names go to 10.0.0.1; a
 * stalled mesh resolver neither hangs a public lookup nor is waited on twice for a mesh one. The zones, the private names,
 * the public resolvers and the 800 ms come from build.json::ui.dns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetDnsSplit1979Test {
    private val d = FleetDns.decl
    private val MESH = listOf("10.0.0.1", "10.1.0.1")
    private val savedExchange = FleetDnsBridge.exchange

    @After fun restore() { FleetDnsBridge.exchange = savedExchange; FleetDnsBridge.meshObserver = null }

    private fun plan(name: String, preset: String = "private_only") = FleetDns.queryPlan(d, name, true, true, preset, emptyList(), true, MESH)

    private fun reply(q: ByteArray, ip: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(q, 0, 2)
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        out.write(q, 12, q.size - 12)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
        out.write(ip.split('.').map { it.toInt().toByte() }.toByteArray())
        return out.toByteArray()
    }

    /** The bridge's walk over the plan, mesh resolver [mesh] (null = stalls 5 s), public resolvers answer 129.151.228.66. */
    private fun lookup(name: String, mesh: ((ByteArray) -> ByteArray?)?, asked: MutableList<String>): Pair<ByteArray?, Long> {
        FleetDnsBridge.forget(name)
        FleetDnsBridge.exchange = { q, s, _ ->
            asked += s
            if (s in MESH) (mesh?.invoke(q) ?: run { Thread.sleep(5_000); null }) else reply(q, "129.151.228.66")
        }
        FleetDnsBridge.routes = {
            plan(name).map { FleetDnsBridge.Route(it, listOf(it), mesh = it in MESH, timeoutMs = if (it in MESH) d.meshTimeoutMs else 0) }
        }
        val t0 = System.nanoTime()
        val a = FleetDnsBridge.walk(DnsWire.query(name, DnsWire.A))
        return a to (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun publicPagesGoToThePublicResolversAndNeverToTheMesh() {
        for (n in listOf("linktree.diegonmarcos.com", "diegonmarcos.com", "www.diegonmarcos.com", "nexus.diegonmarcos.com", "github.com")) {
            assertEquals(n, d.publicResolvers, plan(n))
            assertTrue(n, d.publicResolvers.isNotEmpty() && d.publicResolvers.none { it in MESH })
            assertFalse(n, FleetDns.isMeshName(d, n))
        }
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), d.publicResolvers)
    }

    @Test fun meshAndPrivateNamesGoToTheMeshResolver() {
        for (n in listOf("maddy.mesh.diegonmarcos.com", "mesh.diegonmarcos.com") + d.privateNames) {
            assertEquals(n, MESH, plan(n))
            assertTrue(n, FleetDns.isMeshName(d, n))
        }
        assertTrue(d.privateNames.any { it.startsWith("c3-") })
        assertEquals(MESH, plan("x.mesh.diegonmarcos.com", "public_open"))
    }

    @Test fun withTheMeshResolverStalledPublicNamesStillResolveWithinASecond() {
        val asked = mutableListOf<String>()
        val (a, ms) = lookup("linktree.diegonmarcos.com", null, asked)
        assertNotNull(a)
        assertEquals("129.151.228.66", DnsWire.addresses(a!!).single().hostAddress)
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(asked.toString(), asked.none { it in MESH })
    }

    @Test fun aStalledMeshNameIsAskedOnceAndReportedUnreachable() {
        assertEquals(800, d.meshTimeoutMs)
        val asked = mutableListOf<String>()
        FleetDnsBridge.forget("x.mesh.diegonmarcos.com")
        FleetDnsBridge.exchange = { _, s, _ -> asked += s; throw java.net.SocketTimeoutException("timeout") }
        FleetDnsBridge.routes = { listOf(FleetDnsBridge.Route("mesh", MESH.take(1), mesh = true, timeoutMs = d.meshTimeoutMs)) }
        assertNull(FleetDnsBridge.walk(DnsWire.query("x.mesh.diegonmarcos.com", DnsWire.A)))
        assertEquals("one try, no retry: $asked", 1, asked.size)
        assertNotNull(FleetDnsBridge.lastFailure)
    }
}

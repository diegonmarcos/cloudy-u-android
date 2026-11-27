package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.MailStack
import com.diegonmarcos.cloudc3.pages.NetGraph
import com.diegonmarcos.cloudc3.pages.NetLive
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.TopologyModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1955 A Live colour and root cause of the Network graphs: only real readings colour a node, and the root is the lowest failing node. */
class NetLiveTest {
    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val opsMaps = json("ops_maps.json")
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val infra = TopologyModel.parseInfra(json("infra_slice.json"))
    private val slice = FleetFixtures.slice()
    private val inputs = NetGraph.Inputs(OpsMaps.Sources(opsMaps, json("mesh.json"), infra, json("infra_slice.json"), catalog, slice.domains),
        slice, TopologyModel.Inputs(catalog, slice, infra), json("mesh.json"))
    private val v = NetGraph.parseViewers(opsMaps)
    private fun graph(id: String) = NetGraph.page(v, id, inputs)!!.second
    private val hub = slice.vms.first { it.id == infra.subnets.first().hub }
    private val wgIp = slice.vms.mapNotNull { vm -> vm.wgIp?.let { vm.alias to it } }.toMap()
    private val roles = infra.subnets.flatMap { it.peers }.groupBy { it.name }.mapValues { it.value.first().role }

    /** The hub's live read failed; every other mesh VM answered. */
    private fun hubBlind(tunnels: List<NetLive.WgVm>? = null) = NetLive.Snapshot(
        deployed = slice.vms.filter { it.wgIp != null }.associate { vm ->
            vm.alias to if (vm.alias == hub.alias) TopologyModel.Deployed.Blind("timeout") else TopologyModel.Deployed.Seen(emptyList())
        },
        tunnels = tunnels, api = true, wgIp = wgIp, role = roles, vmAlias = slice.vms.associate { it.id to it.alias }, at = 1L)

    @Test fun `the handshake text parses to seconds, and never or blank is no handshake`() {
        assertEquals(72L, NetLive.handshakeAgeS("1 minute, 12 seconds ago"))
        assertEquals(7380L, NetLive.handshakeAgeS("2 hours, 3 minutes ago"))
        assertNull(NetLive.handshakeAgeS("never"))
        assertNull(NetLive.handshakeAgeS(""))
        val p = NetLive.parseWireguard("""[{"vm":"a","peers":[{"endpoint":"1.2.3.4:51820","allowedIps":"10.0.0.3/32, 10.0.0.9/32","latestHandshake":"5 seconds ago","transfer":"1 KiB"}]}]""")!!
        assertEquals(listOf("10.0.0.3/32", "10.0.0.9/32"), p[0].peers[0].allowedIps)
        assertNull(NetLive.parseWireguard("{}"))
    }

    @Test fun `a VM with no peers read is unknown, never down`() {
        val s = hubBlind(listOf(NetLive.WgVm(hub.alias, emptyList())))
        val other = slice.vms.first { it.wgIp != null && it.alias != hub.alias }
        val r = NetLive.read("tunnel:${hub.alias}->${other.alias}", s, v)!!
        assertEquals(MailStack.Health.UNKNOWN, r.health)
    }

    @Test fun `an unbound node is never OK`() {
        listOf("net_wg", "net_auth", "net_dns", "net_network", "net_security", "net_mesh").forEach { id ->
            val g = graph(id)
            val live = NetLive.applyLive(g, hubBlind().copy(hosts = mapOf("x" to (MailStack.Health.OK to "200"))), v)
            g.nodes.filter { it.bind.isEmpty() }.forEach { n -> assertFalse("$id ${n.id} has no binding but reads OK", live.getValue(n.id).health == MailStack.Health.OK) }
            g.nodes.filter { it.bind.isEmpty() }.forEach { n -> assertEquals("$id ${n.id}", "declared", live.getValue(n.id).word) }
        }
    }

    @Test fun `root cause - the hub unreadable is the suspected root of the WireGuard spokes and of every mesh name`() {
        val wg = graph("net_wg")
        val s = hubBlind()
        val roots = NetLive.rootCause(wg, NetLive.applyLive(wg, s, v), s)
        assertEquals("host:${hub.alias}", roots.first().id)
        assertFalse(roots.first().confirmed)
        val spokes = infra.subnets.first().peers.filter { it.name != hub.alias }.map { "host:${it.name}" }
        assertTrue("every wg0 peer is impacted", roots.first().impacted.containsAll(spokes))

        val dns = graph("net_dns")
        val dr = NetLive.rootCause(dns, NetLive.applyLive(dns, s, v), s)
        assertEquals("address:${infra.dnsPrimary}", dr.first().id)
        val names = dns.nodes.filter { it.ns == "meshname" }.map { it.id }.toSet()
        assertEquals(infra.dns.map { it.name }.toSet().size, names.size)
        assertEquals("every mesh name depends on the primary", names, dr.first().impacted.filter { it.startsWith("meshname:") }.toSet())
    }

    @Test fun `root cause - a stale handshake seen from a spoke confirms the hub`() {
        val spoke = slice.vms.first { it.wgIp != null && it.alias != hub.alias && roles[it.alias] == "spoke" }
        val s = hubBlind(listOf(NetLive.WgVm(spoke.id, listOf(NetLive.WgPeer("x:51820", listOf("${hub.wgIp}/32"), "3 hours ago", "")))))
        val wg = graph("net_wg")
        val live = NetLive.applyLive(wg, s, v)
        val r = NetLive.rootCause(wg, live, s).first()
        assertEquals("host:${hub.alias}", r.id)
        assertTrue(r.confirmed)
    }

    @Test fun `every read failed - no root cause`() {
        val s = NetLive.Snapshot(deployed = slice.vms.filter { it.wgIp != null }.associate { it.alias to TopologyModel.Deployed.Blind("no route") },
            api = false, apiWhy = "no route", tunnelsWhy = "no route")
        val wg = graph("net_wg")
        assertEquals(emptyList<NetLive.Root>(), NetLive.rootCause(wg, NetLive.applyLive(wg, s, v), s))
    }
}

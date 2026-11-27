package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.Services
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.FleetLive
import com.diegonmarcos.cloudc3.pages.HealthModel
import com.diegonmarcos.cloudc3.pages.HealthModel.St
import com.diegonmarcos.cloudc3.pages.HealthPhone
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.XrayModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1955 C A2 / A3 The Health debugger's model: layers in the owner's order, no number for an unread status, a missing token blames no VM, parity with the old Health page, the X-ray scope. */
class HealthModelTest {
    private val pub = HealthFixtures.pub()
    private val priv = HealthFixtures.priv()
    private val vantage = HealthModel.Vantage(true, true, null, false)
    private val slice = FleetFixtures.slice()

    @Test fun `the layers are in the owner's order and the attribution ranks are distinct`() {
        assertEquals(listOf("lights", "containers", "vms", "mesh"), HealthModel.LAYERS.take(4).map { it.id })
        assertEquals(14, HealthModel.LAYERS.size)
        assertEquals(HealthModel.LAYERS.size, HealthModel.LAYERS.map { it.rank }.toSet().size)
        assertEquals(0, HealthModel.LAYERS.first { it.id == "vantage" }.rank)
        assertTrue(HealthModel.LAYERS.first { it.id == "vms" }.rank < HealthModel.LAYERS.first { it.id == "containers" }.rank)
        assertTrue(HealthModel.layersJson().startsWith("[{\"id\":\"lights\""))
    }

    @Test fun `parity - every field of the old Health page rows is on its Lights row`() {
        val rows = HealthModel.lightsChecks(pub, priv, emptyMap(), vantage, null, false)
        assertEquals(pub.size + priv.size, rows.size)
        assertTrue(pub.isNotEmpty() && priv.isNotEmpty())
        pub.forEach { s ->
            val c = rows.first { it.id == "lights.public:${s.name}" }
            val f = c.facts.toMap()
            assertEquals(s.name, c.name)
            assertEquals("Public", f["scope"]); assertEquals(s.auth, f["auth"]); assertEquals("https://${s.publicUrl}", f["public_url"])
            assertEquals(s.privateDns, f["private_dns"]); assertEquals(s.vm, f["vm"]); assertEquals(s.service, f["service"])
        }
        priv.forEach { s ->
            val c = rows.first { it.id == "lights.private:${s.name}" }
            val f = c.facts.toMap()
            assertEquals("Private", f["scope"]); assertEquals(s.protocol, f["protocol"]); assertEquals(s.dbEngine, f["db_engine"])
            assertEquals(s.service, f["service"]); assertEquals(s.privateDns, f["private_dns"]); assertEquals(s.vm, f["vm"])
        }
        assertEquals(pub.size, HealthModel.scoped(rows, "Public").size)
        assertEquals(priv.size, HealthModel.scoped(rows, "Private").size)
        assertEquals(rows.size, HealthModel.scoped(rows, "All").size)
    }

    @Test fun `an unread status never carries a number or a phase bar`() {
        val ph = HealthModel.Phases(5, 6, null, null)
        listOf(St.BLIND, St.NOT_SERVED, St.GATED, St.PENDING).forEach { s ->
            val c = HealthModel.Check("x", "lights", "x", s, phases = ph)
            assertEquals("", c.timeText)
            assertTrue(HealthModel.bar(c).isEmpty())
        }
        val up = HealthModel.Check("x", "lights", "x", St.OK, phases = ph)
        assertEquals("11 ms", up.timeText)
        assertEquals(listOf("dns", "tcp"), HealthModel.bar(up).map { it.first })
    }

    @Test fun `a device with no token is a device-side state - no VM and no container is down, none is blamed`() {
        val why = "NO_TOKEN: No Authelia token on this device."
        val deployed = slice.vms.filter { it.wgIp != null }.associate { it.alias to TopologyModel.Deployed.Blind(why) }
        val vms = HealthPhone.vmChecks(slice.vms, deployed)
        assertTrue(vms.isNotEmpty())
        vms.forEach { assertEquals(St.BLIND, it.status); assertEquals(HealthModel.NO_TOKEN_NOTE, it.details) }
        val catalog = ContainersModel.parse(File(FleetFixtures.moduleDir(), "data/containers_declared.json").readText())!!
        val cs = HealthPhone.containerChecks(catalog.entries) { TopologyModel.Health.UNKNOWN to "VM unreadable: $why" }
        assertTrue(cs.none { it.status == St.DOWN })
        assertTrue(cs.all { it.details == HealthModel.NO_TOKEN_NOTE })
        val mesh = HealthPhone.meshChecks(null, why, 180, 600)
        assertEquals(St.BLIND, mesh.single().status); assertEquals(HealthModel.NO_TOKEN_NOTE, mesh.single().details)
        assertEquals(St.BLIND, HealthPhone.controlChecks(false, why).single().status)
        val tok = HealthModel.vantageChecks(HealthModel.Vantage(false, null, null, false)).first { it.id == "vantage.token" }
        assertEquals(St.BLIND, tok.status); assertEquals(HealthModel.NO_TOKEN_NOTE, tok.details)
    }

    @Test fun `a blocked vantage makes a Lights row blind with the reason, never down`() {
        val offVpn = HealthModel.lightsChecks(pub.take(1), priv.take(1), emptyMap(), HealthModel.Vantage(true, false, null, false), null, true)
        assertEquals(St.BLIND, offVpn.first { it.id.startsWith("lights.private") }.status)
        val noNet = HealthModel.lightsChecks(pub.take(1), emptyList(), emptyMap(), HealthModel.Vantage(true, true, HealthModel.ProbeOut(St.DOWN, HealthModel.Phases(), null, "tcp: x", ""), true), null, true)
        assertEquals(St.BLIND, noNet.single().status)
        assertTrue(noNet.single().details.contains("no internet"))
        val noEngine = HealthModel.lightsChecks(pub.take(1), emptyList(), emptyMap(), vantage, "phone probe engine not in this build: x", true)
        assertEquals(St.NOT_SERVED, noEngine.single().status)
    }

    @Test fun `the engine's analysis and a fleet report parse - the shared c3-report v1 fixture`() {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "ab_cloud-libs-shared").isDirectory) dir = dir.parentFile
        val fx = File(dir, "ab_cloud-libs-shared/libs/health-core-rs/rust/fixtures/report_gcp_proxy_oom.json")
        assertTrue("the shared fixture exists: $fx", fx.isFile)
        val r = HealthModel.parseReport(fx.readText())!!
        assertEquals("RED", r.status)
        assertEquals(listOf("vms.alive:gcp-E2-f_0"), r.roots)
        assertTrue(r.checks.isNotEmpty())
        assertEquals(St.DOWN, r.checks.first { it.id == "vms.alive:gcp-E2-f_0" }.status)
        assertNull(HealthModel.parseReport("{\"schema\":\"other\"}"))
        val a = HealthModel.parseAnalysis("""{"status":"RED","roots":["a"],"checks":[{"id":"a","explained_by":null},{"id":"b","explained_by":"a"}],"lights":[{"layer":"vms","label":"VMs","rank":1,"light":"red","counts":{"ok":0,"warn":0,"down":1,"caused":0,"blocked":0,"not_served":0}}]}""")!!
        assertEquals(listOf("a"), a.roots); assertEquals("a", a.explained["b"]); assertEquals(1, a.lights.single().down)
        val c = HealthModel.applied(listOf(HealthModel.Check("b", "x", "b", St.DOWN)), a)
        assertEquals("a", c.single().explainedBy)
        assertTrue(HealthModel.reportJson(c).contains("\"schema\":\"c3-report/v1\""))
    }

    @Test fun `X-ray - the tabs, the allow-list and the not-served states`() {
        assertEquals(listOf("full", "vms", "services", "suites"), XrayModel.TABS.map { it.first })
        assertEquals(listOf("xray-vm", "xray-service", "xray-full"), XrayModel.KINDS)
        assertEquals("xray-full" to "fleet", XrayModel.runRequest("xray-full", "x", emptyList()))
        assertEquals("xray-vm" to "oci-apps", XrayModel.runRequest("xray-vm", "oci-apps", listOf("oci-apps")))
        assertNull("a subject must be declared", XrayModel.runRequest("xray-vm", "evil", listOf("oci-apps")))
        assertNull("a subject must be a plain name", XrayModel.runRequest("xray-vm", "a b;rm", listOf("a b;rm")))
        assertNull("a kind outside the allow-list", XrayModel.runRequest("shell", "oci-apps", listOf("oci-apps")))
        assertEquals(XrayModel.State.NotServed("route not served yet (#1977)"), XrayModel.classify(FleetLive.Result.Failed("NOT_FOUND: 404")))
        assertTrue(XrayModel.classify(FleetLive.Result.Failed("NO_TOKEN: none")) is XrayModel.State.Device)
        assertTrue(XrayModel.classify(FleetLive.Result.Failed("NETWORK: down")) is XrayModel.State.Blind)
        assertTrue(XrayModel.classify(FleetLive.Result.Body("{}")) is XrayModel.State.Invalid)
        assertEquals("/reports/data/cloud_xray_vm_oci-apps", XrayModel.dataPath(XrayModel.vmFamily("oci-apps")))
    }

    @Test fun `X-ray export goes through the fleet's X-ray format`() {
        val rep = HealthModel.Report("cloud-xray", "0.1", "r1", "dagu", "oci-apps", "t0", "t1", "vm:oci-apps", "GREEN", emptyMap(),
            listOf(HealthModel.Section("cpu", "CPU, memory", "vms", "GREEN", listOf(HealthModel.Row("mem used", "17 of 24 GB", true)))), emptyList(), emptyList(), emptyList(), emptyList(), 0)
        val md = XrayModel.markdown(rep, "oci-apps")
        assertTrue(md.contains("# X-Ray") && md.contains("## CPU, memory") && md.contains("- mem used: 17 of 24 GB"))
        assertNotNull(XrayModel.html(rep, "oci-apps"))
        assertFalse(md.isBlank())
    }
}

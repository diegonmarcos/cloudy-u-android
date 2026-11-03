package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.FleetModel
import java.time.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Infra section's model, declaration to model: the shipped data/fleet_slice.json parses to exactly what it declares. */
class FleetModelTest {

    private val raw = FleetFixtures.rawSlice()
    private val slice = FleetFixtures.slice()

    @Test
    fun `every declared provider, vm, bucket, backend, device and domain survives the parse`() {
        assertEquals(raw["providers"]!!.jsonArray.size, slice.providers.size)
        assertEquals(raw["vms"]!!.jsonArray.size, slice.vms.size)
        assertEquals(raw["serverless"]!!.jsonArray.size, slice.serverless.size)
        assertEquals(raw["storage"]!!.jsonObject["buckets"]!!.jsonArray.size, slice.buckets.size)
        assertEquals(raw["storage"]!!.jsonObject["backends"]!!.jsonArray.size, slice.backends.size)
        assertEquals(raw["electronics"]!!.jsonArray.size, slice.devices.size)
        assertEquals(raw["domains"]!!.jsonArray.size, slice.domains.size)
        assertEquals(raw["domains"]!!.jsonArray[0].jsonObject["records"]!!.jsonArray.size, slice.domains[0].records.size)
        assertTrue("the fleet declares providers, VMs and a domain", slice.providers.isNotEmpty() && slice.vms.isNotEmpty() && slice.domains.isNotEmpty())
    }

    @Test
    fun `every declared device lands on exactly one Personal page`() {
        val pages = listOf("desktop", "mobile", "gadgets")
        slice.devices.forEach { d ->
            assertEquals("device ${d.id} (${d.kind})", 1, pages.count { d.kind in FleetModel.personalKinds(it, slice) })
        }
    }

    @Test
    fun `the references inside the declaration resolve`() {
        slice.vms.forEach { assertNotNull("vm ${it.id} names provider ${it.provider}", slice.provider(it.provider)) }
        slice.providers.forEach { p -> p.vms.forEach { id -> assertTrue("provider ${p.id} lists vm $id", slice.vms.any { it.id == id }) } }
        // A vendor provider owns the VMs that name it and has a console to open; the self-hosted Cloud is the VMs on the mesh, a
        // grouping over the vendor ones, with no console of its own (`selfHosted` is the declaration's own flag).
        val vendors = slice.providers.filter { !it.selfHosted }
        vendors.forEach { p -> assertEquals(p.vms, slice.vmsOf(p.id).map { it.id }) }
        vendors.forEach { assertTrue("provider ${it.id} has a console link", it.links.isNotEmpty() && it.links.all { l -> l.url.startsWith("https://") }) }
    }

    @Test
    fun `the self-hosted Cloud provider is declared beside the vendors and holds exactly the mesh VMs`() {
        val cloud = slice.providers.filter { it.selfHosted }
        assertEquals("one self-hosted provider", 1, cloud.size)
        assertEquals("Cloud", cloud.single().label)
        assertTrue("the vendor providers are still listed", slice.providers.count { !it.selfHosted } >= 3)
        assertEquals("its VMs are every VM with a WireGuard mesh IP", slice.vms.filter { it.wgIp != null }.map { it.id }, cloud.single().vms)
    }

    @Test
    fun `the domain carries its registration and the mail signing records`() {
        val d = slice.domains.single()
        assertEquals("Cloudflare", d.registrar)
        assertEquals("Cloudflare", d.admin)
        assertTrue("renewal was paid in October", d.renewalPaid.endsWith("-10"))
        assertNotNull("expiry is an ISO date", FleetModel.daysUntil(d.expires, LocalDate.of(2026, 10, 20)))
        assertNotNull("next renewal is an ISO date", FleetModel.daysUntil(d.nextRenewal, LocalDate.of(2026, 10, 20)))
        FleetModel.MAIL_GROUPS.forEach { g ->
            assertTrue("the zone declares no $g record - the mail page would silently lose it", FleetModel.recordsOf(d, g).isNotEmpty())
        }
        assertTrue("DKIM names a selector", FleetModel.recordsOf(d, "DKIM").all { it.name.contains("._domainkey.") || it.name.contains("domainkey") })
        assertTrue("SPF is a v=spf1 TXT", FleetModel.recordsOf(d, "SPF").first().content.startsWith("v=spf1"))
        assertTrue("DMARC is a v=DMARC1 TXT", FleetModel.recordsOf(d, "DMARC").single().content.startsWith("v=DMARC1"))
        assertTrue("TLS-RPT is a v=TLSRPTv1 TXT", FleetModel.recordsOf(d, "TLS-RPT").single().content.startsWith("v=TLSRPTv1"))
        assertEquals("enforce", d.mtaStsMode)
    }

    @Test
    fun `storage rows are hardware and backends only, never a database container`() {
        val rows = FleetModel.storageRows(slice)
        assertTrue(rows.all { it.kind in FleetModel.STORAGE_KINDS })
        assertEquals(slice.buckets.size, rows.count { it.kind == "S3 bucket" })
        assertEquals(slice.backends.size, rows.count { it.kind == "Cloud drive" })
        assertEquals(slice.vms.size, rows.count { it.kind == "VM disk" })
        val dbs = FleetFixtures.dbContainerNames()
        assertTrue("the Databases subgroup must still be declared under Containers", dbs.isNotEmpty())
        rows.forEach { r -> assertFalse("DB container ${r.name} is a Storage row", r.name in dbs) }
        slice.buckets.forEach { assertFalse(it.name in dbs) }
    }

    @Test
    fun `a disk row shows the live usage when the API answered and the declared size when not`() {
        val vm = slice.vms.first { it.wgIp != null }
        val live = mapOf(vm.id to FleetModel.DiskUsage("45G", "34G", "77%"))
        val withLive = FleetModel.storageRows(slice, live).first { it.kind == "VM disk" && it.detail.startsWith(vm.id) }
        assertEquals("34G / 45G (77%)", withLive.usage)
        val without = FleetModel.storageRows(slice).first { it.kind == "VM disk" && it.detail.startsWith(vm.id) }
        assertNull(without.usage)
        assertTrue(FleetModel.storageRows(slice, thisPhone = "1.0 GB free of 2.0 GB").any { it.name == "This phone (live)" })
    }

    @Test
    fun `a disk of zero is not a declared size`() {
        val v = slice.vms.first().copy(diskGb = 0, cpu = null, ramGb = null)
        assertFalse(FleetModel.vmSpec(v).contains("GB disk"))
        assertTrue(FleetModel.vmSpec(v.copy(diskGb = 30)).contains("30 GB disk"))
    }

    @Test
    fun `the tier3 answer parses by vm id and alias, and a garbled one parses to nothing`() {
        val body = """[{"vm":"vm-x","alias":"x-alias","ip":"192.0.2.1","reachable":true,"resources":{"diskTotal":"45G","diskUsed":"34G","diskAvailable":"9G","diskPercent":"77%"}},
            {"vm":"vm-y","alias":"y-alias","reachable":false}]"""
        val m = FleetModel.parseDisks(body)
        assertEquals(setOf("vm-x", "x-alias"), m.keys)
        assertEquals("34G / 45G (77%)", m["x-alias"]!!.line())
        assertTrue(FleetModel.parseDisks("not json").isEmpty())
        assertTrue(FleetModel.parseDisks("""{"error":"boom"}""").isEmpty())
    }

    @Test
    fun `the costs answer parses to its TOTAL row`() {
        val oci = """{"ok":true,"costs":[{"service":"Object Storage","amount":5.98,"currency":"EUR","period":"October 2026"},
            {"service":"X TOTAL","amount":9.74,"currency":"EUR","period":"October 2026"}]}"""
        assertEquals("9.74 EUR (October 2026)", FleetModel.parseCostTotal(oci))
        val gcp = """{"ok":true,"costs":[{"service":"GCP TOTAL ESTIMATED","amount":0,"currency":"EUR","period":"October 2026 — Billing account: A (estimates)"}]}"""
        assertEquals("0.00 EUR (October 2026)", FleetModel.parseCostTotal(gcp))
        assertNull(FleetModel.parseCostTotal("""{"ok":false,"costs":[],"error":"unavailable"}"""))
        assertNull(FleetModel.parseCostTotal("<html>"))
    }

    @Test
    fun `the cost line prefers live, then the declared fallback, and says when it has neither`() {
        val liveOnly = slice.providers.first { it.cost?.live != null }
        assertTrue(FleetModel.costLine(liveOnly, "9.74 EUR", null).endsWith("(live)"))
        assertTrue(FleetModel.costLine(liveOnly, null, "NETWORK: down").contains("live unavailable: NETWORK: down"))
        val declared = slice.providers.first { it.cost?.amount != null }
        assertTrue(FleetModel.costLine(declared, null, null).endsWith("(declared)"))
        val none = slice.providers.first { it.cost == null }
        assertEquals("Monthly cost: not declared", FleetModel.costLine(none, null, null))
    }

    @Test
    fun `the live zone answer parses to records`() {
        val body = """{"zone":"example.test","records":[{"name":"a.example.test","type":"CNAME","content":"example.test","proxied":true,"service":"svc","origin":"build.json"}]}"""
        val r = FleetModel.parseLiveDns(body).single()
        assertEquals("CNAME", r.type); assertEquals("svc", r.comment); assertEquals(true, r.proxied)
        assertTrue(FleetModel.parseLiveDns("[]").isEmpty())
    }

    @Test
    fun `days until counts whole days and tolerates a bad date`() {
        assertEquals(5L, FleetModel.daysUntil("2026-10-25", LocalDate.of(2026, 10, 20)))
        assertEquals(-1L, FleetModel.daysUntil("2026-10-19", LocalDate.of(2026, 10, 20)))
        assertNull(FleetModel.daysUntil("soon", LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `device kinds label and an unknown kind is shown, not dropped`() {
        assertEquals("PC", FleetModel.kindLabel("notebook"))
        assertEquals("Smartwatch", FleetModel.kindLabel("smartwatch"))
        assertEquals("Console", FleetModel.kindLabel("console"))
    }

    @Test
    fun `garbage parses to an empty slice, not a crash`() {
        val s = FleetModel.parse("{{{")
        assertTrue(s.providers.isEmpty() && s.vms.isEmpty() && s.domains.isEmpty() && s.devices.isEmpty())
    }
}

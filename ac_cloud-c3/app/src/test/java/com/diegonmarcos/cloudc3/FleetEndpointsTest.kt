package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ApiProviders
import com.diegonmarcos.cloudc3.pages.FleetEndpoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1718 Infra > APIs > C3 Endpoints: the fleet services' public and mesh URLs, moved here from Cloud C3 Webserver > API. The shipped
 * block parses, answers a declared page, and holds EVERY row the webserver listed: the rows are recomputed from the fleet
 * endpoint declaration (ac_cloud-account/build.json) with the webserver's URL rules and each must be drawn here, whole.
 */
class FleetEndpointsTest {
    private val text = File(FleetFixtures.moduleDir(), "data/api_providers.json").readText()
    private val decl = FleetEndpoints.parse(text)
    private val declared = Json.parseToJsonElement(File(FleetFixtures.moduleDir().parentFile, "ac_cloud-account/build.json").readText())
        .jsonObject["fleet_endpoints"]!!.jsonObject["services"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    @Test
    fun `the endpoints group answers a declared page and lists every declared service`() {
        val group = ApiProviders.parse(text).groups.firstOrNull { it.id == decl.group }
        assertNotNull("fleet_endpoints.group ${decl.group} is not a declared API group", group)
        assertEquals(declared.map { it.s("id") }, decl.services.map { it.id })
        assertTrue(decl.source.startsWith("ac_cloud-account/build.json#"))
    }

    @Test
    fun `every row of the webserver's Public Endpoints page and fleet mesh group is drawn here`() {
        var public = 0; var mesh = 0
        for (d in declared) {
            val s = decl.services.first { it.id == d.s("id") }
            val lines = FleetEndpoints.lines(s).joinToString("\n"); val links = FleetEndpoints.links(s).map { it.url }
            if (d.s("public_domain").isNotEmpty()) {
                val url = d.s("public_scheme").ifEmpty { "https" } + "://" + d.s("public_domain") + d.s("public_path")
                public++
                assertEquals(url, s.publicUrl); assertTrue(url in links && lines.contains("Public: $url"))
                assertEquals(d.s("kind"), s.kind); assertEquals(d.s("policy"), s.policy)
                if (d.s("health").isNotEmpty()) assertTrue(lines.contains("Health: ${d.s("health")}"))
            } else assertTrue(s.publicUrl == null && lines.contains("Public: none"))
            if (d.s("mesh_dns").isNotEmpty()) {
                val port = (d["mesh_port"] as? JsonPrimitive)?.intOrNull?.let { ":$it" }.orEmpty()
                val url = d.s("mesh_scheme").ifEmpty { "http" } + "://" + d.s("mesh_dns") + port + d.s("mesh_path")
                mesh++
                assertEquals(url, s.meshUrl); assertTrue(url in links && lines.contains("Mesh: $url")); assertEquals(d.s("policy"), s.policy)
            }
            if (d.s("source").isNotEmpty()) assertTrue(lines.contains("Declared in: ${d.s("source")}"))
        }
        assertEquals(public, decl.public().size); assertEquals(mesh, decl.mesh().size)
        assertTrue("the declaration lists $public public and $mesh mesh URLs", public >= 3 && mesh >= 10)
        assertEquals("${decl.services.size} fleet services - $public public URLs - $mesh on the mesh", decl.summary())
    }

    @Test
    fun `a missing or broken block is an empty page, never a throw`() {
        for (bad in listOf("", "not json", "[]", "{}", """{"fleet_endpoints": 3}""", """{"fleet_endpoints": {"services": [1, {"kind": "x"}, {"id": ""}]}}"""))
            assertTrue(bad, FleetEndpoints.parse(bad).services.isEmpty())
        val one = FleetEndpoints.parse("""{"fleet_endpoints": {"group": "g", "services": [{"id": "a", "public_url": null, "mesh_url": "", "aliases": "x"}]}}""").services.single()
        assertTrue(one.publicUrl == null && one.meshUrl == null && one.aliases.isEmpty())
        assertTrue(FleetEndpoints.lines(one).first() == "Public: none (mesh only)" && FleetEndpoints.links(one).isEmpty())
    }
}

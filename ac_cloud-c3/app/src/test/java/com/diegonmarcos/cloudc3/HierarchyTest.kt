package com.diegonmarcos.cloudc3

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1495 hierarchy parity, app side: the app tree the phone builds (libs:bottomnav NavDecl over ui.sections) must be the tree of
 * data/c3_hierarchy.json, the declaration cloudy-c3-api and cloudy-c3-mcp are generated against. The route and tool side is
 * asserted in cloudy-u-containers (cloudy-c3-api and cloudy-c3-mcp hierarchy tests); test/test_c3_hierarchy.py keeps the file fresh.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyTest {

    private fun appDir(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return dir
    }

    private val ui: JsonObject by lazy { Json.parseToJsonElement(File(appDir(), "build.json").readText()).jsonObject["ui"]!!.jsonObject }
    private val hierarchy: JsonObject by lazy { Json.parseToJsonElement(File(appDir(), "data/c3_hierarchy.json").readText()).jsonObject }

    private fun nav(): NavDecl = NavDecl.parse(
        JSONArray(ui["sections"]!!.toString()),
        ui["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content },
        ui["default_section"]!!.jsonPrimitive.content,
    )

    /** The leaf ids of a page list, a nested page with its parent's `<parent>_` prefix dropped. */
    private fun leaves(pages: List<NavPage>): List<String> = pages.flatMap { p ->
        if (p.pages.isEmpty()) listOf(p.id) else p.pages.map { it.id.removePrefix(p.id + "_") }
    }

    private fun hierLeaves(nodes: JsonArray): List<String> = nodes.flatMap { n ->
        val kids = n.jsonObject["children"]?.jsonArray
        if (kids == null) listOf(n.jsonObject["id"]!!.jsonPrimitive.content) else hierLeaves(kids)
    }

    @Test
    fun `the hierarchy has the sections of the app in the app's order`() {
        val declared = ui["sections"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(declared, hierarchy["sections"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(ui["bottom_nav"]!!.jsonArray.map { it.jsonPrimitive.content }, hierarchy["bottom_nav"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `every section of the hierarchy has exactly the leaf pages the app nav builds`() {
        val nav = nav()
        hierarchy["sections"]!!.jsonArray.map { it.jsonObject }.forEach { s ->
            val id = s["id"]!!.jsonPrimitive.content
            val app = leaves(nav.section(id)!!.pages)
            val hier = s["children"]?.jsonArray?.let { hierLeaves(it) } ?: emptyList()
            assertEquals("section $id: app leaf pages vs hierarchy leaves", app.sorted(), hier.sorted())
        }
    }

    @Test
    fun `the new categories are declared pages with a catalog map`() {
        val ops = Json.parseToJsonElement(File(appDir(), "data/ops_maps.json").readText()).jsonObject["maps"]!!.jsonObject
        val nav = nav()
        listOf("infra" to "control", "ops" to "net_security", "ops" to "ops_health_tests", "ops" to "obs_metrics", "ops" to "obs_alerts", "ops" to "obs_notify", "configs" to "plugins")
            .forEach { (sec, page) ->
                val ids = nav.section(sec)!!.pages.flatMap { listOf(it.id) + it.pages.map { q -> q.id } }
                assertTrue("$page is not declared in $sec", page in ids)
                assertTrue("maps.hier_$page is missing from data/ops_maps.json (run data/gen_c3_hierarchy.py)", "hier_$page" in ops)
            }
    }

    @Test
    fun `the Containers filters and projects of the hierarchy are the declared ones`() {
        val cp = Json.parseToJsonElement(File(appDir(), "data/container_projects.json").readText()).jsonObject
        val c = hierarchy["containers"]!!.jsonObject
        assertEquals(cp["kinds"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, c["filters"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(cp["projects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, c["projects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a catalog map reads its rows with no derived source`() {
        val decl = Json.parseToJsonElement(File(appDir(), "data/ops_maps.json").readText()).jsonObject
        val secs = com.diegonmarcos.cloudc3.pages.OpsMaps.rows(decl, "hier_net_security")
        assertEquals(listOf("cloudy-c3-api routes", "cloudy-c3-mcp tools"), secs.map { it.title })
        assertTrue(secs.flatMap { it.rows }.any { it.name == "/security/*" && it.note.startsWith("also /ops/net/security/") })
        assertTrue(secs[1].rows.any { it.name.startsWith("c3_ops ") })
    }
}

package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ApiProviders
import com.diegonmarcos.cloudc3.pages.C3Mcp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1725 Infra > APIs > C3 MCP: the MCP servers, tools and c3-api routes moved here from Cloud C3 Webserver > API. The shipped
 * block parses, answers a declared page, and every catalogue tool is drawn here whole or is a device tool that stayed on the
 * webserver; every remote topology node (a tool's c3-api node) is one of the page's API nodes.
 */
class C3McpTest {
    private val root = FleetFixtures.moduleDir().parentFile
    private val text = File(FleetFixtures.moduleDir(), "data/api_providers.json").readText()
    private val decl = C3Mcp.parse(text)
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private val catalogue = Json.parseToJsonElement(File(root, "ac_cloud-c3-webserver/data/mcp-catalogue.json").readText()).jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
    private val device = Json.parseToJsonElement(File(root, "data/fleet/device-access.json").readText()).jsonObject["mcp"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.s("tool") }.toSet()

    @Test
    fun `the group answers a declared page and lists every catalogue server`() {
        assertNotNull(ApiProviders.parse(text).groups.firstOrNull { it.id == decl.group })
        assertEquals(catalogue.map { it.s("name") }, decl.servers.map { it.name })
        assertTrue(decl.source.startsWith("ac_cloud-c3-webserver/data/mcp-catalogue.json"))
    }

    @Test
    fun `every catalogue tool is drawn here whole or stayed on the webserver as a device tool`() {
        var moved = 0; var kept = 0; var nodes = 0
        for (sv in catalogue) {
            val here = decl.servers.first { it.name == sv.s("name") }
            val head = C3Mcp.lines(here).joinToString("\n")
            assertTrue(head.contains("Endpoint: ${sv.s("endpoint").ifBlank { "not declared" }}") && head.contains("Auth: ${sv.s("auth")}") && head.contains("Tools: ${sv.s("tools_count")}"))
            for (t in sv["tools"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                val row = here.tools.firstOrNull { it.tool == t.s("tool") }
                if (t.s("tool") in device) { kept++; assertTrue("${t.s("tool")} is in both apps", row == null); continue }
                assertNotNull("${t.s("tool")} is in neither app", row); moved++
                val lines = C3Mcp.lines(row!!).joinToString("\n")
                assertTrue(lines.contains("API route ${t.s("route")}") && lines.contains("tree ${t.s("tree")}") && lines.contains("node ${t.s("node")}"))
                if (t.s("legacy").isNotEmpty()) assertTrue(lines.contains("old name ${t.s("legacy")}"))
                if (!t.s("route").contains("no API route")) { nodes++; assertTrue(here.apiNodes().any { (node, tools) -> node == t.s("node") && row in tools }) }
            }
        }
        assertEquals(device.size, kept); assertEquals(moved, decl.tools)
        assertTrue("moved $moved tools, $nodes remote node links", moved >= 100 && nodes >= 50)
    }

    @Test
    fun `a missing or broken block is an empty page, never a throw`() {
        for (bad in listOf("", "x", "[]", "{}", """{"mcp_catalogue": 1}""", """{"mcp_catalogue": {"servers": [2, {"auth": "a"}, {"name": ""}]}}"""))
            assertTrue(bad, C3Mcp.parse(bad).servers.isEmpty())
        val one = C3Mcp.parse("""{"mcp_catalogue": {"group": "g", "servers": [{"name": "s", "tools_count": "x", "tools": [3, {"tool": ""}, {"tool": "t", "route": "/a (no API route: MCP only)"}]}]}}""").servers.single()
        assertEquals(0, one.toolsCount); assertEquals(1, one.tools.size); assertTrue(one.tools[0].mcpOnly && one.apiNodes().isEmpty())
        assertTrue(C3Mcp.lines(one).isNotEmpty() && C3Mcp.lines(one.tools[0]).first().startsWith("API route "))
    }
}

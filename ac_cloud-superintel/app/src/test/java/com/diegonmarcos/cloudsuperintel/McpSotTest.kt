package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The MCP page against the names of the cloudy-u-linux SOT policy it is derived from (tools/derive-ai-data.py writes the digest and the page). */
class McpSotTest {
    private val sot = JSONObject(File("src/test/resources/mcp-sot.json").readText())
    private val groups = AiData.groups(Data.ai("mcp.json"))
    private fun strings(k: String) = sot.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun group(prefix: String) = groups.first { it.title.startsWith(prefix) }
    private val aliases = sot.getJSONObject("aliases").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    @Test fun everyCurrentServerOfTheSotIsListedOnceUnderItsTier() {
        val default = group("Default").rows.map { it.title }
        assertEquals(strings("default"), default)
        assertEquals(strings("opt-in").filter { it !in aliases }, group("Opt-in").rows.map { it.title })
    }

    @Test fun theRebrandNamesAreThereAsNewNames() {
        val new = sot.getJSONObject("new").keys().asSequence().filter { it !in aliases }.toSet()
        assertEquals(new, group("New names").rows.map { it.title }.toSet())
        for (n in listOf("cloudy-c3-mcp", "cloudy-all-mcp", "cloudy-apps-communication-mcp", "cloudy-apps-drive-mcp", "cloudy-cgc-pub-mcp", "cloud-me-cgc-pvt-mcp"))
            assertTrue(n, n in group("New names").rows.map { it.title })
        assertTrue(group("Opt-in").rows.any { it.title.startsWith("google-") })
    }

    @Test fun everyOldNameIsListedAsAnAliasOfItsNewName() {
        val rows = group("Aliases").rows
        assertEquals(aliases.keys, rows.map { it.title }.toSet())
        for (r in rows) {
            assertTrue(r.title + " is marked as an alias", "alias" in r.caption)
            assertEquals("-> " + aliases.getValue(r.title), r.end)
        }
    }

    @Test fun cloudyC3McpShowsItsMergedPeersControlAndTheBrowserToolGroup() {
        val rows = group("cloudy-c3-mcp tool groups").rows.map { it.title }
        assertTrue("peers-control merged", "infra_peers" in rows)
        assertTrue("browser.* tools", "browser.*" in rows)
        assertTrue(rows.any { it.startsWith("c3_") })
    }

    @Test fun theMeshOnlyServersAreCurrentNames() {
        val all = strings("default") + strings("opt-in")
        for (r in group("Mesh-only").rows) assertTrue(r.title, r.title in all && r.title !in aliases)
    }
}

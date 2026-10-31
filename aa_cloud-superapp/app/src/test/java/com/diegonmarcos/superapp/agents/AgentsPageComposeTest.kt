package com.diegonmarcos.superapp.agents

import android.app.Application
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The fleet agent registry, drawn by Configs ▸ Observability ▸ X-Ray (AGENTS group) since the Agents page was removed.
 *
 *   G1 the baked registry is data/fleet-agents.json, every agent present
 *   G2 no agents page is declared; page:config/agents and its two old tabs resolve to X-Ray
 *   G3 the SuperApp scope lists only this app's rows; the Fleet scope lists every app's
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentsPageComposeTest : KitPageHarness() {

    private fun committedIds(): List<String> {
        val arr = JSONObject(File("../data/fleet-agents.json").readText()).getJSONArray("agents")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    @Test
    fun `G1 the baked registry carries every declared agent`() {
        val baked = FleetAgents.all().map { it.id }
        assertEquals(committedIds(), baked)
        for (id in listOf("cloud-drive/screenshots", "cloud-tasks/screenshot-events", "cloud-code/claude",
                "cloud-code/hermes", "cloud-code/goose", "cloud-code/deepseek", "cloud-code/openclaw",
                "cloud-superapp/auto-update"))
            assertTrue("$id missing", id in baked)
    }

    @Test
    fun `G2 the Agents page is folded into X-Ray and its old ids land there`() {
        val section = Sections.byId("config")!!
        val ids = section.allPages.map { it.id }
        for (old in listOf("agents", "agents-superapp", "agents-fleet")) {
            assertTrue("$old is still declared", old !in ids)
            assertEquals("config" to "xray", Sections.canonicalPage("config", old))
        }
        assertEquals("Observability", section.allPages.first { it.id == "xray" }.group)
    }

    @Test
    fun `G3 each scope lists its own rows`() {
        val all = FleetAgents.all()
        val mine = FleetAgents.forScope(all, FleetAgents.SCOPE_SUPERAPP)
        assertTrue(mine.isNotEmpty() && mine.all { it.isSelf })
        assertEquals(all.size, FleetAgents.forScope(all, FleetAgents.SCOPE_FLEET).size)
    }

    @Test
    fun `G6 a provider row reads as a word`() {
        assertEquals("3 pending", AgentStatusReader.providerLive(mapOf("pending" to "3", "last_scan_ms" to "1")).word)
        assertEquals("up to date", AgentStatusReader.providerLive(mapOf("pending" to "0", "last_scan_ms" to "1700000000000")).word)
        assertEquals("no runs yet", AgentStatusReader.providerLive(emptyMap()).word)
    }
}

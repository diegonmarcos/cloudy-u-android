package com.diegonmarcos.cloudbrowser.search

import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.superapp.ai.AiRelay
import com.diegonmarcos.superapp.ai.AiRelayed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1801 the Search add-on's chat holds no key: the body it hands the relay (use browser_search_ai) carries none, the
 * provider's answer comes back as the assistant turn, and an engine refusal (no engine, no key) is the turn's error in the
 * engine's own words, exactly where the missing token used to be reported.
 */
class SearchAddonRelayTest {

    private fun searchCfg(): SearchConfig = SearchConfig.parse(
        JSONObject(File("../../ac_cloud-search/build.json").readText()).getJSONObject("search"))

    private val addon by lazy { SearchAddon(searchCfg()) }

    @Test
    fun `the relayed body carries no key and the provider's answer is the assistant turn`() {
        val bodies = ArrayList<String>()
        val o = addon.send(null, "hello", "m/x", false, AiRelay { b ->
            bodies.add(b)
            AiRelayed(200, """{"choices":[{"message":{"role":"assistant","content":"hi there"}}]}""")
        })
        assertNull(o.error)
        assertEquals("hi there", o.session.messages.last().content)
        assertEquals(1, bodies.size)
        val b = JSONObject(bodies[0])
        assertEquals("m/x", b.getString("model"))
        assertFalse(bodies[0].contains("Authorization") || bodies[0].contains("Bearer"))
        assertTrue(addon.outcomeJson(o).getBoolean("ok"))
    }

    @Test
    fun `an engine refusal is the turn's error, a provider error is read as before`() {
        val refused = addon.send(null, "x", null, false, AiRelay { AiRelayed(0, "", "No OpenRouter key in the AI keys engine") })
        assertEquals("No OpenRouter key in the AI keys engine", refused.error)
        val http = addon.send(null, "x", null, false, AiRelay { AiRelayed(401, """{"error":{"message":"User not found."}}""") })
        assertTrue(http.error.orEmpty(), http.error.orEmpty().contains("User not found"))
    }

    @Test
    fun `the declared use is the one the engine serves the browser`() {
        assertEquals("browser_search_ai", SearchAddon.USE)
        assertEquals("chat", SearchAddon.ENDPOINT)
        val uses = JSONObject(File("../../fleet-private-data/decisions.json").readText())
            .getJSONObject("relay").getJSONObject("uses")
        val u = uses.getJSONObject(SearchAddon.USE)
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudbrowser"))
        assertTrue(u.getJSONArray("endpoints").toString().contains(SearchAddon.ENDPOINT))
    }
}

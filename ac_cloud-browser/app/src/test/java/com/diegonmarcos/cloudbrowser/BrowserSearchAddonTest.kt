package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.ai.AiRelay
import com.diegonmarcos.superapp.ai.AiRelayed
import com.diegonmarcos.cloudbrowser.search.SearchAddon
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.superapp.browser.BrowserConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #802 I8 the Search add-on reads cloud-search's declaration, and never touches the network without a token. */
class BrowserSearchAddonTest {

    private val search = JSONObject(File("../../ac_cloud-search/build.json").readText()).getJSONObject("search")
    private val addon = SearchAddon(SearchConfig.parse(search))

    /** What app/build.gradle does: cloud-search's engines join the add-on's block. */
    private fun browserCfg(): BrowserConfig {
        val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
        val arr = ui.getJSONArray("addons")
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("id") == "search" }
            .forEach { it.put("engines", search.getJSONArray("engines")) }
        return BrowserConfig.parse(ui.toString())
    }

    @Test
    fun `the add-on's engines are cloud-search's, in its order`() {
        val want = (0 until search.getJSONArray("engines").length()).map { search.getJSONArray("engines").getJSONObject(it).getString("id") }
        assertTrue(want.isNotEmpty())
        assertEquals(want, addon.engines().map { it.id })
        assertEquals(want, browserCfg().addons.searchEngines().map { it.id })
        assertTrue(browserCfg().addons.enabled("search", null))
    }

    @Test
    fun `open builds the engine's own URL, and an unknown engine is refused`() {
        val e = addon.engines()[1]
        val url = addon.searchUrl("berlin wall", e.id)!!
        assertTrue(url, url.startsWith(e.template.substringBefore("{q}")))
        assertTrue(url, "berlin" in url && " " !in url)
        assertEquals(addon.searchUrl("x", addon.engines().first().id), addon.searchUrl("x", null))
        assertNull(addon.searchUrl("x", "no-such-engine"))
    }

    @Test
    fun `without a key in the engine the turn is recorded and answered with the engine's reason`() {
        var asked = 0
        val o = addon.send(null, "hi", null, false, AiRelay { asked++; AiRelayed(0, "", "no openrouter key in the AI keys engine") })
        assertEquals(1, asked)
        assertEquals("no openrouter key in the AI keys engine", o.error)
        assertEquals(listOf("user"), o.session.messages.map { it.role })
        assertEquals(addon.ai.defaultModel, o.session.model)
        val j = addon.outcomeJson(o)
        assertFalse(j.getBoolean("ok"))
        assertTrue(j.isNull("message"))
        assertEquals(1, addon.sessionsJson().length())
        val again = addon.send(o.session.id, "again", "m/x", false, AiRelay { AiRelayed(0, "", "none") })
        assertEquals(o.session.id, again.session.id)
        assertEquals(2, again.session.messages.size)
        assertEquals(1, addon.sessionsJson().length())
    }
}

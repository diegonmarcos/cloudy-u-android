package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.FleetScrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1856 the Scrapper tab's fleet tools: declared in build.json, parsed by libs:browser, run on the page URL. */
class FleetScrapperTest {
    private val browser = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
    private val scraper = (0 until browser.getJSONArray("addons").length()).map { browser.getJSONArray("addons").getJSONObject(it) }.first { it.getString("id") == "scraper" }
    private val fleet = FleetScrapper.parse(scraper.getJSONObject("fleet"))

    @Test fun `every declared fleet tool has a Scrapper menu row and the other way round`() {
        val rows = browser.getJSONObject("menu").getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.getString("section") == "scrapper" && it.getString("id").startsWith("fleet_") }.map { it.getString("id") }
        assertEquals(fleet.tools.keys.sorted(), rows.sorted())
        assertTrue(fleet.tools.isNotEmpty())
    }

    @Test fun `the page URL is put into every body and the declaration is not mutated`() {
        fleet.tools.values.forEach { t -> assertTrue("${t.id} carries the url", FleetScrapper.fill(t.body, "https://example.org/a").toString().contains("https://example.org/a")) }
        assertTrue(fleet.tools.getValue("fleet_readable").body.toString().contains("{url}"))
    }

    @Test fun `services are mesh first and a bearer is a key name, never a value`() {
        fleet.services.values.forEach { s -> assertTrue(s.bases.first().startsWith("http://") && ".mesh." in s.bases.first()) }
        assertEquals("headless.cloudy.token", fleet.services.getValue("headless").bearerKey)
        assertEquals(null, fleet.services.getValue("scrapper_api").bearerKey)
    }

    @Test fun `a run answer shows the chosen step's text, a failed step in words`() {
        val t = fleet.tools.getValue("fleet_readable")
        val ok = JSONObject("""{"ok":true,"steps":[{"ok":true,"result":{}},{"ok":true,"result":{"markdown":"# Title"}},{"ok":true,"result":{"closed":true}}]}""")
        assertEquals("# Title", FleetScrapper.display(ok, t))
        val bad = JSONObject("""{"ok":false,"steps":[{"ok":false,"error":{"code":"timeout","message":"slow"}}]}""")
        assertEquals("Failed: slow", FleetScrapper.display(bad, t))
        assertEquals("Failed: bearer required", FleetScrapper.display(JSONObject("""{"ok":false,"error":{"code":"unauthorized","message":"bearer required"}}"""), t))
    }
}

package com.diegonmarcos.cloudnav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1156: the nav declaration (build.json::ui, the single source the shell is baked from): island order, renamed sections, sub-page sets. */
class NavDeclJsonTest {
    private val ui: JSONObject = listOf(File("../build.json"), File("build.json"), File("ac_cloud-nav/build.json")).first { it.isFile }
        .let { JSONObject(it.readText()).getJSONObject("ui") }

    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    private fun section(id: String): JSONObject? = (0 until ui.getJSONArray("sections").length()).map { ui.getJSONArray("sections").getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    private fun pages(id: String) = ids(section(id)!!.getJSONArray("pages"))

    @Test fun `the island is Routes Navigation Places Explored Tracker, Configs is out of it`() {
        val bar = (0 until ui.getJSONArray("bottom_nav").length()).map { ui.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("routes", "navigation", "places", "explored", "tracker"), bar)
        assertFalse("configs" in bar)
        assertEquals("routes", ui.getString("default_section"))
    }

    @Test fun `Timeline is renamed Explored and Configs stays a declared section`() {
        assertNull(section("timeline"))
        assertEquals("Explored", section("explored")!!.getString("label"))
        assertEquals("Tracker", section("tracker")!!.getString("label"))
        assertTrue(section("configs") != null)
    }

    @Test fun `sub-page sets`() {
        assertEquals(listOf("trips", "maps"), pages("explored"))
        assertEquals(listOf("timeline", "steps", "sleep"), pages("tracker"))
        assertEquals(listOf("general", "perms", "about", "tracker", "apis", "update", "cache", "layers", "device"), pages("configs"))
        assertEquals(listOf("Trips", "Maps"), (0 until 2).map { section("explored")!!.getJSONArray("pages").getJSONObject(it).getString("label") })
    }

    @Test fun `every island item is a declared section`() {
        for (i in 0 until ui.getJSONArray("bottom_nav").length()) assertTrue(section(ui.getJSONArray("bottom_nav").getString(i)) != null)
    }

    @Test fun `the Health deep link points at Tracker Steps`() {
        val d = ui.getJSONArray("deep_links").getJSONObject(0)
        assertEquals("page:tracker/steps", d.getString("target"))
    }
}

package com.diegonmarcos.cloudbrowser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Domains > Headless Browsers: the first tab and its three services are declared in build.json. */
class DomainsHeadlessDeclTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")

    @Test
    fun `Headless Browsers is the first Domains tab`() {
        val secs = ui.getJSONArray("sections")
        val pages = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "domains" }.getJSONArray("pages")
        assertEquals(listOf("headless", "files", "site_perms", "history", "visited"), (0 until pages.length()).map { pages.getJSONObject(it).getString("id") })
        assertEquals("Headless Browsers", pages.getJSONObject(0).getString("label"))
    }

    @Test
    fun `the three services and their links are declared`() {
        val a = ui.getJSONObject("domains").getJSONArray("headless")
        val by = (0 until a.length()).map { a.getJSONObject(it) }
        assertEquals(listOf("Cloudflare Browser Run", "Cloud Headless", "Cloud Stealth"), by.map { it.getString("title") })
        assertEquals(
            listOf("https://developers.cloudflare.com/browser-rendering/rest-api/", "https://mcp.diegonmarcos.com/cloudy-browser-headless", "https://steel-browser.mesh.diegonmarcos.com:3000"),
            by.map { it.getJSONObject("link").getString("url") })
        by.forEach { s ->
            assertTrue(s.getString("tagline").isNotEmpty())
            listOf("engine", "proxy", "stealth", "output").forEach { k -> assertTrue("${s.getString("id")} $k", s.getJSONObject("stack").getString(k).isNotEmpty()) }
        }
    }
}

package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/**
 * #1906 every social account and company on Pers and Prof has its real link. The table data/web/me-links.json is generated from the SoT
 * (data/regen-me-links.py; `sot_url` is the SoT value at generation), this test fails when an entry lacks a link, differs from its SoT
 * snapshot, or when a page file's `link` differs from the table. test/test-me-links.sh proves each check red by mutation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeLinksTest {
    private val entries = JSONObject(BuroLlcTestData.text("data/web/me-links.json")).getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `the tabs and companies of Pers and Prof are all declared`() {
        val want = setOf("pers-personal", "pers-ig0", "pers-ig1", "pers-ig2", "pers-face0", "pers-pinterest", "pers-youtube", "pers-tidal", "pers-linkedin", "pers-orkut", "pers-astro", "prof-professional", "prof-nexus", "prof-leafy", "prof-stark", "lnktree-default", "proj-unix-superapp", "proj-lecole42-dev")
        assertEquals(emptySet<String>(), want - entries.map { it.getString("id") }.toSet())
    }

    @Test fun `every entry has a real link equal to the SoT snapshot`() {
        for (e in entries) {
            val id = e.getString("id")
            assertTrue("$id has no link", e.optString("url").startsWith("https://"))
            assertEquals("$id differs from the SoT", e.getString("sot_url"), e.getString("url"))
        }
    }

    @Test fun `the page file of each tab carries the same link`() {
        for (e in entries.filter { it.has("path") }) {
            val path = (0 until e.getJSONArray("path").length()).joinToString("/") { e.getJSONArray("path").getString(it) }
            val block = JSONArray(BuroLlcTestData.text("data/ui/${e.getString("section")}/$path.json")).getJSONObject(0)
            assertEquals(e.getString("id"), e.getString("url"), block.optString("link"))
        }
    }
}

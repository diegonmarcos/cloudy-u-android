package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1918 Prof > Profiles is LinkedIn | CV (the old More pages were removed by #1937, ids kept), Proj has a Repos page after 42, every Proj page wears the Linktree emoji and every
 * main category its Linktree banner. Declared data only (build.json, data/web/me-proj-emoji.json, data/web/me-links.json). test/test-proj-emoji.sh mutates each red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProjEmojiTest {
    private fun obj(p: String) = JSONObject(BuroLlcTestData.text(p))
    private val ui = obj("build.json").getJSONObject("ui")
    private fun section(id: String) = (0 until ui.getJSONArray("sections").length()).map { ui.getJSONArray("sections").getJSONObject(it) }.first { it.getString("id") == id }
    private fun flat(o: JSONObject): List<JSONObject> = (o.optJSONArray("pages") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.flatMap { listOf(it) + flat(it) }
    private val table = obj("data/web/me-proj-emoji.json")

    @Test fun `every Proj page label starts with its Linktree emoji`() {
        val pages = flat(section("proj"))
        assertEquals(35, table.getJSONObject("emoji").length())
        assertTrue(pages.size >= 35)
        for (p in pages) {
            val e = table.getJSONObject("emoji").getJSONObject(p.getString("id")).getString("emoji")
            assertTrue("${p.getString("id")} label ${p.getString("label")} lacks $e", p.getString("label").startsWith("$e "))
        }
        assertEquals("🐧", table.getJSONObject("emoji").getJSONObject("pj-unix").getString("emoji"))   // Linktree's Unix penguin
        assertEquals("areas/1/icon", table.getJSONObject("emoji").getJSONObject("pj-unix").getString("source"))
    }

    @Test fun `every main category has a banner served by the Linktree site`() {
        val b = table.getJSONObject("banners")
        for (g in listOf("pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos")) {
            val u = b.getJSONObject(g).getString("url")
            assertTrue("$g $u", u.startsWith("https://linktree.diegonmarcos.com/public/images/banners/") && (u.endsWith(".gif") || u.endsWith(".webp")))
        }
        assertEquals("https://linktree.diegonmarcos.com/public/images/banners/cloud.gif", b.getJSONObject("pj-unix").getString("url"))
        val home = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/ProjHome.kt")
        assertTrue(home.contains("me-proj-emoji.json") && home.contains("ProjHomeTags.banner(g.id)") && home.contains("AnimatedImageDrawable"))
    }

    @Test fun `Repos follows 42 and embeds the bundled repos tree`() {
        val ids = (0 until section("proj").getJSONArray("pages").length()).map { section("proj").getJSONArray("pages").getJSONObject(it).getString("id") }
        assertEquals("pj-repos", ids[ids.indexOf("pj-lecole42") + 1])
        assertEquals("repos-tree.html", JSONArray(BuroLlcTestData.text("data/ui/proj/pj-repos.json")).getJSONObject(0).getString("url"))
        val html = BuroLlcTestData.text("data/web/repos-tree.html")
        assertTrue(html.length > 50_000 && html.contains("Repos"))
    }

    @Test fun `Profiles is LinkedIn and CV and every old Profiles page is still a page of Prof`() {
        val prof = section("prof")
        val web = flat(prof).first { it.getString("id") == "prof-web" }
        assertEquals(listOf("prof-linkedin", "prof-cv"), flat(web).map { it.getString("id") })
        assertEquals("https://diegonmarcos.github.io/mySocials/linkedin.html", JSONArray(BuroLlcTestData.text("data/ui/prof/prof-web/prof-linkedin.json")).getJSONObject(0).getString("url"))
        assertEquals("https://diegonmarcos.github.io/cv_web/", JSONArray(BuroLlcTestData.text("data/ui/prof/prof-web/prof-cv.json")).getJSONObject(0).getString("url"))
        val ids = flat(prof).map { it.getString("id") }
        // #1937 owner-ordered removal: More (prof-profiles) with Professional, Work and Study (CV, Roadmap) is gone; the company sites stay
        for (kept in listOf("leafy", "nexus", "stark")) assertTrue("$kept is gone from Prof", kept in ids)
        for (gone in listOf("prof-profiles", "professional", "work", "study", "cv", "roadmap")) assertFalse("$gone is declared again", gone in ids)
        assertFalse(table.toString().isBlank())
    }
}

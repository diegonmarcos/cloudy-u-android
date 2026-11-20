package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1832 rule 16 of the rename MY-PM -> MARKZ: against the frozen baseline src/test/resources/nav-parity-baseline-1832.json (the MY-PM section before the
 * rename), read through build.json ui.renamed, MARKZ keeps the section's place on the bottom nav, its icon and nav mode, every sub-page with its label and
 * icon, every stack entry with every field and its function mnemonic, the former Homepage (as MARKZ > Index), and the old deep links and command word.
 */
class Rename1832ParityTest {
    private val base = JSONObject(DataFiles.resource("nav-parity-baseline-1832.json"))
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val renamed = ui.getJSONObject("renamed")
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private val oldSec = base.getJSONObject("section")
    private val newId = renamed.getJSONObject("sections").getString(oldSec.getString("id"))
    private val sec = arr(ui.getJSONArray("sections")).first { it.getString("id") == newId }
    private val stacks = ui.getJSONObject("stacks").toString()
    private val entries = Pages.entries(stacks, newId)
    private fun pageNow(id: String) = renamed.getJSONObject("pages").optString(id).ifEmpty { id }

    @Test fun myPmIsMarkzAtTheSamePlace() {
        assertEquals("1832", renamed.getString("ticket"))
        assertEquals("markz", newId)
        assertEquals("MARKZ", sec.getString("label"))
        assertEquals("MARKZ", renamed.getJSONObject("labels").getString(oldSec.getString("label")))
        val was = strs(base.getJSONArray("bottom_nav"))
        assertEquals(was.map { renamed.getJSONObject("sections").optString(it).ifEmpty { it } }, strs(ui.getJSONArray("bottom_nav")))
        assertEquals(was.indexOf("my-pm"), strs(ui.getJSONArray("bottom_nav")).indexOf("markz"))
        assertEquals(oldSec.getString("icon"), sec.getString("icon"))
        assertEquals(oldSec.getString("nav_mode"), sec.getString("nav_mode"))
        // the old id is gone from the declarations: one name only
        assertTrue(arr(ui.getJSONArray("sections")).none { it.getString("id") == "my-pm" })
        assertTrue(!ui.getJSONObject("stacks").has("my-pm"))
    }

    @Test fun everyMyPmPageIsAMarkzPageWithItsLabelAndIcon() {
        val now = arr(sec.getJSONArray("pages")).associateBy { it.getString("id") }
        for (p in arr(oldSec.getJSONArray("pages"))) {
            val id = pageNow(p.getString("id"))
            val q = now[id]
            assertNotNull("page ${p.getString("id")} (now $id) is gone", q)
            assertEquals(p.getString("label"), q!!.getString("label"))
            assertEquals(p.getString("icon"), q.getString("icon"))
        }
        // Home is still first: the section opens on its Homepage
        assertEquals(Pages.homeId(newId), arr(sec.getJSONArray("pages")).first().getString("id"))
    }

    @Test fun everyMyPmEntryKeepsEveryField() {
        val today = arr(ui.getJSONObject("stacks").getJSONArray(newId))
        for (b in arr(base.getJSONArray("entries"))) {
            val e = today.firstOrNull { it.optString("kind") == b.optString("kind") && it.optString("page") == b.optString("page") && it.optString("title") == b.optString("title") }
            assertNotNull("entry ${b.optString("title")} is gone", e)
            for (k in b.keys()) assertEquals("entry ${b.optString("title")} lost or changed $k", b.get(k).toString(), e!!.opt(k)?.toString())
        }
        // the holdings page is still drawn by its own kind
        assertEquals(Pages.KIND_HOLDINGS, Pages.entryOf(entries, "holdings")?.kind)
    }

    @Test fun theFormerHomepageIsStillReachableAsIndex() {
        val grid = Pages.entryOf(entries, "markz-index")
        assertEquals(Pages.KIND_HOME_GRID, grid?.kind)
        val triples = arr(sec.getJSONArray("pages")).map { Triple(it.getString("id"), it.getString("label"), it.getString("icon")) }
        val cards = Pages.homeCards(triples, entries).map { it.pageId }
        // the former Homepage's card (Personal portfolio) and every new page are cards of it
        for (p in arr(oldSec.getJSONArray("pages")).map { pageNow(it.getString("id")) }.filter { !it.endsWith("-home") }) assertTrue(p, p in cards)
        assertEquals(triples.map { it.first }.drop(1).filter { it != "markz-index" }, cards)
    }

    @Test fun theOldDeepLinksAndCommandWordStillOpenIt() {
        val redirects = ui.getJSONArray("redirects").toString()
        assertEquals("markz" to Pages.PAGE_HOME, Pages.redirect(redirects, oldSec.getString("id")))
        for (p in arr(oldSec.getJSONArray("pages"))) {
            val id = p.getString("id")
            if (pageNow(id) != id) assertEquals("markz" to Pages.PAGE_HOME, Pages.redirect(redirects, id))
        }
        val withPages = arr(ui.getJSONArray("sections")).filter { (it.optJSONArray("pages")?.length() ?: 0) > 0 }.map { it.getString("id") to it.getString("label") }
        val functions = Pages.functions(stacks, withPages)
        val word = base.getString("command_word")
        assertNull(Pages.function(functions, word))
        assertEquals("markz", Pages.redirected(redirects, word)?.section)
        assertEquals("markz", Pages.function(functions, "MARKZ")?.section)
        for (b in arr(base.getJSONArray("entries"))) if (b.optString("fn").isNotEmpty()) assertEquals(b.optString("page"), Pages.function(functions, b.getString("fn"))?.page)
        // a non-dashboard page is opened in its section and highlighted in the one-row nav
        assertEquals("holdings", FinNavStack.selectedPage(FinNavStack.openPage("holdings"), newId))
    }

    @Test fun theAboutPageStillNamesTheSection() {
        val about = JSONObject(DataFiles.buildJson).getJSONObject("configs_pages").getJSONObject("about")
        val screens = arr(about.getJSONArray("screens"))
        val was = base.getJSONObject("about_screen")
        val s = screens.firstOrNull { it.getString("name") == "MARKZ" }
        assertNotNull(s)
        assertTrue(s!!.getString("what").startsWith(was.getString("what").removeSuffix(".")))
        assertTrue(strs(about.getJSONArray("features")).any { it.startsWith(base.getString("about_feature").replace("MY-PM", "MARKZ")) })
    }
}

package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1961 Proj tabs: README.html first, then GH Repo Page, README.canvas, README.md, on every repo entry, CG's site entries included (their site stays a last tab). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProjTabs1961Test {
    private val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
    private val order = listOf("html", "gh", "canvas", "md")

    private fun leaves(o: JSONObject, path: List<String>, out: MutableList<List<String>>) {
        val kids = o.optJSONArray("pages")
        if (kids == null || kids.length() == 0) { out.add(path); return }
        for (i in 0 until kids.length()) kids.getJSONObject(i).let { leaves(it, path + it.getString("id"), out) }
    }
    private fun blocks(): Map<String, JSONObject> {
        val secs = ui.getJSONArray("sections"); val proj = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "proj" }
        val all = ArrayList<List<String>>(); leaves(proj, emptyList(), all)
        return all.filter { it.isNotEmpty() }.mapNotNull { p ->
            val t = runCatching { BuroLlcTestData.text("data/ui/proj/" + p.joinToString("/") + ".json") }.getOrNull() ?: return@mapNotNull null
            val b = JSONArray(t).getJSONObject(0); if (b.optString("id") == "web" && b.has("repo")) p.last() to b else null
        }.toMap()
    }

    @Test fun `the tab order is README html, GH Repo Page, README canvas, README md`() {
        assertEquals(order + "site", RepoViews.IDS)
        assertEquals(listOf(RepoViews.HTML, RepoViews.GH, RepoViews.CANVAS, RepoViews.MD), RepoViews.declared(listOf("md", "canvas", "gh", "html")))
    }

    @Test fun `README html is the default tab`() { assertEquals(RepoViews.HTML, RepoViews.initial(null)) }

    @Test fun `every Proj repo entry declares the four tabs in order`() {
        val b = blocks(); assertTrue("Proj has repo entries (${b.size})", b.size >= 24)
        for ((id, blk) in b) {
            val d = RepoViewerDecl.of(blk); assertNotNull("$id has tabs", d)
            assertEquals("$id tab order", order, d!!.second.take(4))
        }
    }

    @Test fun `CG entries have the README tabs from their repo and keep their site`() {
        val b = blocks()
        for ((id, repo) in listOf("pj-cg-omniverse" to "CG-galaxy", "pj-cg-zirkusverse" to "CG-circus")) {
            val blk = b[id]; assertNotNull("$id is a repo entry", blk)
            val d = RepoViewerDecl.of(blk!!)!!
            assertEquals(repo, d.first.repo); assertEquals(order + "site", d.second)
            assertTrue("$id keeps its site url", blk.getString("url").startsWith("https://diegonmarcos.github.io/"))
            assertEquals("https://github.com/diegonmarcos/$repo", RepoViewerDecl.ghPage(blk).getString("url"))
        }
    }

    @Test fun `a GitHub entry's GH Repo Page is still its own block`() {
        val blk = blocks().getValue("pj-unix-superapp")
        assertTrue(RepoViewerDecl.ownsGh(blk)); assertTrue(RepoViewerDecl.ghPage(blk) === blk)
    }
}

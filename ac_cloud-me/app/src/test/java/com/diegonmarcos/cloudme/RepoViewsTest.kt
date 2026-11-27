package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1932 the four-view selector of every Proj repo entry: derivation from the repo, declaration on every entry, and the GH page still reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepoViewsTest {
    private val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")

    /** Every leaf page id of the Proj section, walked from build.json. */
    private fun leaves(o: JSONObject, path: List<String>, out: MutableList<List<String>>) {
        val kids = o.optJSONArray("pages")
        if (kids == null || kids.length() == 0) { out.add(path); return }
        for (i in 0 until kids.length()) kids.getJSONObject(i).let { leaves(it, path + it.getString("id"), out) }
    }
    private fun projBlocks(): List<Pair<String, JSONObject>> {
        val proj = (0 until ui.getJSONArray("sections").length()).map { ui.getJSONArray("sections").getJSONObject(it) }.first { it.getString("id") == "proj" }
        val all = ArrayList<List<String>>(); leaves(proj, emptyList(), all)
        return all.filter { it.isNotEmpty() }.mapNotNull { p ->
            val text = runCatching { BuroLlcTestData.text("data/ui/proj/" + p.joinToString("/") + ".json") }.getOrNull() ?: return@mapNotNull null
            val a = JSONArray(text)
            if (a.length() == 1 && a.getJSONObject(0).optString("id") == "web") p.last() to a.getJSONObject(0) else null
        }
    }

    @Test fun `views are derived from owner and repo`() {
        val r = RepoViews.parse("https://github.com/diegonmarcos/cloudy-u-android")!!
        assertEquals("diegonmarcos", r.owner); assertEquals("cloudy-u-android", r.repo)
        assertEquals("https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/README.md", r.rawUrl("README.md"))
        assertEquals("https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/README.canvas", r.rawUrl(RepoViews.fileOf(RepoViews.CANVAS)!!))
        assertEquals("https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/README.html", r.rawUrl(RepoViews.fileOf(RepoViews.HTML)!!))
        assertEquals("https://github.com/diegonmarcos/cloudy-u-android", r.githubUrl())
        val d = RepoViews.parse("https://github.com/diegonmarcos/lib-chronos/tree/main/1.Modern-science")!!
        assertEquals("1.Modern-science", d.dir); assertEquals("https://raw.githubusercontent.com/diegonmarcos/lib-chronos/main/1.Modern-science/README.md", d.rawUrl("README.md"))
        assertNull(RepoViews.parse("https://diegonmarcos.github.io/games/")); assertNull(RepoViews.parse("repos-tree.html"))
        assertEquals(listOf("html", "gh", "canvas", "md", "site"), RepoViews.IDS)   // #1961 tab order
    }

    @Test fun `the remembered view wins, else README html`() {
        assertEquals("html", RepoViews.initial(null)); assertEquals("html", RepoViews.initial("bogus"))   // #1961 README.html first
        for (v in RepoViews.IDS) assertEquals(v, RepoViews.initial(v))
    }

    @Test fun `every Proj repo entry declares the four views`() {
        val blocks = projBlocks()
        val repos = blocks.filter { RepoViews.parse(it.second.optString("url")) != null }
        assertTrue("Proj has repo entries", repos.size >= 20)
        for ((id, b) in repos) {
            val d = RepoViewerDecl.of(b)
            assertNotNull("$id declares views", d)
            assertEquals("$id declares all four", listOf("html", "gh", "canvas", "md"), d!!.second)
        }
    }

    @Test fun `the GH page of every repo entry is still reachable and is the entry's own url`() {
        for ((id, b) in projBlocks().filter { RepoViews.parse(it.second.optString("url")) != null }) {
            val d = RepoViewerDecl.of(b)!!
            assertTrue("$id keeps GH as a view", RepoViews.GH in d.second)
            assertEquals("$id's GH page is its url", b.getString("link"), b.getString("url"))
            assertTrue(b.getString("url").startsWith("https://github.com/"))
        }
    }

    @Test fun `entries that are not repos stay the plain page`() {
        for ((id, b) in projBlocks().filter { RepoViews.parse(it.second.optString("url")) == null && !it.second.has("repo") }) assertNull("$id", RepoViewerDecl.of(b))
    }

    @Test fun `failures are classified, never blank`() {
        fun f(status: Int?, auth: Boolean) = RepoViews.Failure("u", status, if (status == null) "IOException" else null, auth, auth)
        assertEquals(RepoViews.Kind.PrivateNoCredential, RepoViews.kind(f(404, false)))
        assertEquals(RepoViews.Kind.NotFound, RepoViews.kind(f(404, true)))
        assertEquals(RepoViews.Kind.CredentialRefused, RepoViews.kind(f(401, true)))
        assertEquals(RepoViews.Kind.Server, RepoViews.kind(f(503, false)))
        assertEquals(RepoViews.Kind.Network, RepoViews.kind(f(null, false)))
        assertEquals(RepoViews.Kind.Network, RepoViews.kind(f(429, false)))
    }
}

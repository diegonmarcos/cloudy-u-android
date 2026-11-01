package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1398 Tasks rows, checked on what the build actually baked (BuildConfig.UI_SECTIONS_B64) and on the shipped
 * vault-rows.json: Inboxes | Backlogs | RoadMaps | Library | Projects, each vault chip a page, and Projects being the
 * very pages (ids, labels, order) of Lnktree's Proj row.
 */
class VaultRowsTest {
    private val baked = JSONArray(String(java.util.Base64.getMimeDecoder().decode(BuildConfig.UI_SECTIONS_B64)))
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }
    private val rows = VaultRows.parse(BuroLlcTestData.text("data/repos/vault-rows.json"))

    private fun groups(s: String) = baked.getValue(s).getJSONArray("groups").let { g -> (0 until g.length()).map { g.getJSONObject(it) } }
    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun pages(s: String) = baked.getValue(s).getJSONArray("pages").let { p -> (0 until p.length()).map { p.getJSONObject(it) }.associateBy { it.getString("id") } }

    @Test fun `Tasks rows are Inboxes Backlogs RoadMaps Library Projects on the many-rows nav`() {
        assertEquals("horizontal_two_rows", baked.getValue("tasks").getString("nav_mode"))
        assertEquals(listOf("Inboxes", "Backlogs", "RoadMaps", "Library", "Projects"), groups("tasks").map { it.getString("label") })
    }

    @Test fun `every vault chip is a page of its row, in the generated order, with its clean label`() {
        val g = groups("tasks").associateBy { it.getString("id") }
        val p = pages("tasks")
        for (r in rows) {
            val lead = listOfNotNull(mapOf("backlogs" to "backlogs", "roadmaps" to "roadmaps")[r.id])
            assertEquals(r.id, lead + r.chips.map { it.page }, ids(g.getValue(r.id).getJSONArray("pages")))
            r.chips.forEach { assertEquals(it.label, p.getValue(it.page).getString("label")) }
        }
        assertTrue(rows.first { it.id == "inboxes" }.chips.size >= 10)
    }

    @Test fun `Projects is Lnktree's Proj row`() {
        val proj = groups("lnktree").first { it.getString("id") == "proj" }
        val mine = groups("tasks").first { it.getString("id") == "projects" }
        assertEquals(ids(proj.getJSONArray("pages")), ids(mine.getJSONArray("pages")))
        val lp = pages("lnktree"); val tp = pages("tasks")
        ids(proj.getJSONArray("pages")).forEach { assertEquals(lp.getValue(it).toString(), tp.getValue(it).toString()) }
    }

    @Test fun `a chip path finds its folder, a Library sub-folder too, and a bad file is no rows`() {
        val lib = rows.first { it.id == "library" }.chips.first { it.children.isNotEmpty() }
        assertNotNull(VaultRows.find(rows, lib.children.first().path))
        assertEquals(lib, VaultRows.find(rows, lib.path))
        assertEquals(emptyList<VaultRow>(), VaultRows.parse(JSONObject().put("_schema", "x").toString()))
        assertEquals(emptyList<VaultRow>(), VaultRows.parse("{"))
    }
}

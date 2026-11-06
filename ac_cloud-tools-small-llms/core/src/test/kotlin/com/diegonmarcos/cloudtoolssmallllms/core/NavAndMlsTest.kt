package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The navigation, the MLs page and the Apps filter, asserted on the declarations themselves: this build.json, the app's
 * tools-models.json and the Cloud Store's own constellation-fleet.json the MLs page is derived from (never a copy).
 */
class NavAndMlsTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")
    private val models = JSONObject(File("../app/src/main/assets/tools-models.json").readText())
    private val fleet = File("../../aa_cloud-superapp/data/constellation-fleet.json").readText()

    private fun ids(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun sections() = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun pagesOf(section: String) = sections().first { it.getString("id") == section }.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun `the bottom nav is Models, MLs, Apps, Store, Sensors and every item is a declared section`() {
        assertEquals(listOf("models", "mls", "apps", "store", "sensors"), ids(ui.getJSONArray("bottom_nav")))
        val declared = sections().map { it.getString("id") }
        assertTrue(declared.containsAll(ids(ui.getJSONArray("bottom_nav"))))
        assertEquals("MLs", sections().first { it.getString("id") == "mls" }.getString("label"))
        assertEquals("Models", sections().first { it.getString("id") == "models" }.getString("label"))
        assertEquals("models", ui.getString("default_section"))
    }

    @Test fun `Sensors has the sub-pages Inbuilt and Connected`() {
        assertEquals(listOf("sensors_inbuilt" to "Inbuilt", "sensors_connected" to "Connected"), pagesOf("sensors").map { it.getString("id") to it.getString("label") })
        assertEquals("Sensors", sections().first { it.getString("id") == "sensors" }.getString("label"))
    }

    @Test fun `Costs is gone from the nav and the sections`() {
        assertFalse("costs" in ids(ui.getJSONArray("bottom_nav")))
        assertFalse("costs" in sections().map { it.getString("id") })
        assertFalse(File("../build.json").readText().contains("\"label\": \"Costs\""))
    }

    @Test fun `MLs has the sub-pages Lite MLs and Tiny MLs, each bound to a Cloud Store group`() {
        assertEquals(listOf("ml_lite" to "Lite MLs", "ml_tiny" to "Tiny MLs"), pagesOf("mls").map { it.getString("id") to it.getString("label") })
        val pages = MlCatalogue.pages(ui.getJSONObject("ml_pages"))
        assertEquals(setOf("ml_lite", "ml_tiny"), pages.keys)
        assertEquals(pagesOf("mls").map { it.getString("id") }.toSet(), pages.keys)
        assertEquals(setOf("libs-l-ml", "libs-t-ml"), pages.values.toSet())
    }

    @Test fun `the MLs groups, categories and lib names are the Cloud Store's own`() {
        val groups = MlCatalogue.parse(fleet, MlCatalogue.pages(ui.getJSONObject("ml_pages")))
        val src = JSONObject(fleet)
        val declared = src.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("id") }
        assertEquals(2, groups.size)
        for (g in groups) {
            val d = declared.getValue(g.id)
            assertEquals(d.getString("label"), g.label)
            assertEquals(ids(d.getJSONArray("members")), g.libs.map { it.id })
            assertTrue(g.libs.isNotEmpty())
            assertTrue(g.libs.all { it.label.isNotBlank() && it.application != null })
        }
        val lite = groups.first { it.page == "ml_lite" }
        val tiny = groups.first { it.page == "ml_tiny" }
        assertTrue(lite.libs.all { it.id.startsWith("lib-ml-l-") })
        assertTrue(tiny.libs.all { it.id.startsWith("lib-ml-t-") })
        // The Tiny-ML TFLite lib and the application categories come from the ids the Store declares.
        assertTrue(tiny.libs.any { it.label.contains("TFLite") })
        assertTrue(setOf("voice", "text", "image", "sensor", "tabular", "sound").containsAll(groups.flatMap { g -> g.libs.mapNotNull { it.application } }))
        assertNotNull(lite.libs.first { it.id == "lib-ml-l-image-mlkit" })
    }

    @Test fun `a group the Store does not declare, or a member it has no row for, is an error`() {
        try { MlCatalogue.parse(fleet, mapOf("p" to "libs-nope")); org.junit.Assert.fail("unknown group accepted") } catch (_: IllegalArgumentException) { }
        val broken = JSONObject(fleet)
        broken.getJSONArray("groups").getJSONObject(0).getJSONArray("members").put("lib-ml-x-none-ghost")
        val gid = broken.getJSONArray("groups").getJSONObject(0).getString("id")
        try { MlCatalogue.parse(broken.toString(), mapOf("p" to gid)); org.junit.Assert.fail("member without a row accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `the Apps filter is All, Hybrids, Models Only, ML Only and every app has exactly one of the three classes`() {
        val f = models.getJSONArray("app_filters").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("all" to "All", "hybrid" to "Hybrids", "models_only" to "Models Only", "ml_only" to "ML Only"), f.map { it.getString("id") to it.getString("label") })
        assertFalse(f[0].has("class"))
        assertEquals(listOf("hybrid", "models_only", "ml_only"), f.drop(1).map { it.getString("class") })
        assertTrue(f.all { it.getString("glyph").isNotBlank() })
        val classes = f.drop(1).map { it.getString("class") }
        val apps = models.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(25, apps.size)
        for (a in apps) assertTrue("${a.getString("id")} has class ${a.optString("class")}", a.optString("class") in classes)
        val count = classes.associateWith { c -> apps.count { it.getString("class") == c } }
        assertEquals(apps.size, count.values.sum())
        assertTrue("each class is used", count.values.all { it > 0 })
        assertEquals(mapOf("hybrid" to 12, "models_only" to 9, "ml_only" to 4), count)
    }
}

package com.diegonmarcos.cloudme.health

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Data loading: the declared files parse, and they agree with build.json (every page declared once, with a stack file, in the home index). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthContentTest {
    private val c = HealthTestData.content()

    private fun healthSection(): JSONObject {
        val secs = JSONObject(HealthTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
        return (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "health" }
    }

    private fun groupPages(): Map<String, List<String>> {
        val gs = healthSection().getJSONArray("groups")
        return (0 until gs.length()).associate { i ->
            val g = gs.getJSONObject(i)
            g.getString("id") to (0 until g.getJSONArray("pages").length()).map { g.getJSONArray("pages").getString(it) }
        }
    }

    @Test fun `the four data files parse into a plan with its windows`() {
        assertEquals(listOf("lunch", "prelift", "train", "dinner", "bed"), c.plan.timeline.map { it.id })
        assertEquals(listOf("12:30", "17:00", "18:30", "21:00", "22:45"), c.plan.timeline.map { it.time })
        assertEquals(listOf("ferritin", "zinc", "rbc_mg"), c.plan.biomarkers.map { it.id })
        assertEquals("pull", c.workout.single().id)
        assertEquals(3, c.workout.single().exercises.size)
        assertTrue(c.formulas.circadian.single().points.size >= 2)
    }

    @Test fun `Health declares the Plan and Trackers groups on the many rows second row, every page once`() {
        val s = healthSection()
        assertEquals("horizontal_two_rows", s.getString("nav_mode"))
        val flat = s.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        val groups = groupPages()
        // #1390: Health opens on Tracked, so the Trackers row is the first row and Tracked the first flat page.
        assertEquals(listOf("trackers", "plan"), groups.keys.toList())
        assertEquals("tracked", flat.first())
        assertEquals(listOf("bio", "circadian", "biomarkers", "timeline", "objectives", "matrix", "culinary", "clash", "thermal", "hemo", "shop", "dossier", "workout"), groups.getValue("plan").drop(1))
        // The Workout Plan is a plan (a programme to follow), not a tracker: it sits on the Plan line.
        assertEquals("workout", groups.getValue("plan").last())
        assertEquals(listOf("tracked", "activities", "bodycomp", "hr", "sleep"), groups.getValue("trackers"))
        val all = groups.values.flatten()
        assertEquals("a page is in two groups", all.size, all.toSet().size)
        assertEquals("every flat page is in exactly one group", flat.toSet(), all.toSet())
        for (g in 0 until s.getJSONArray("groups").length()) assertTrue("group icon", s.getJSONArray("groups").getJSONObject(g).getString("icon").isNotBlank())
    }

    @Test fun `every declared page has a stack file that hosts the Health page of its own id`() {
        for (id in groupPages().values.flatten()) {
            val arr = org.json.JSONArray(HealthTestData.text("data/ui/health/$id.json"))
            assertEquals(1, arr.length())
            val o = arr.getJSONObject(0)
            assertEquals("fragment", o.getString("kind"))
            assertEquals("healthpage", o.getString("id"))
            assertEquals(id, o.getString("page"))
        }
    }

    @Test fun `the home clusters are the six of the spec and only name declared pages`() {
        assertEquals(listOf("Bio-Analytics", "Chrono-Protocols", "Simulators", "Logistics", "Training", "Recovery"), c.home.clusters.map { it.label })
        val declared = groupPages().values.flatten().toSet()
        val named = c.home.clusters.flatMap { it.pages }
        assertTrue("a cluster names an undeclared page: ${named - declared}", declared.containsAll(named))
        assertEquals("every page except the index is reachable from a cluster", declared - "home", named.toSet())
    }

    @Test fun `every item of the plan names its pack and a positive quantity`() {
        for (e in c.plan.timeline) for (i in e.items + e.supplements) {
            assertTrue("${i.id} qty", i.qty > 0)
            assertTrue("${i.id} pack", i.pack.size > 0 && i.pack.label.isNotBlank())
        }
    }

    @Test fun `no page declares a placeholder measurement and no data file carries sample values for the user`() {
        val text = listOf(HealthContent.HOME, HealthContent.FORMULAS, HealthContent.PLAN, HealthContent.WORKOUT).joinToString("\n") { HealthTestData.text("data/health/$it") }
        for (banned in listOf("mock", "lorem", "sample", "placeholder", "\"mass\"", "\"weight_kg\"")) assertTrue("data mentions $banned", banned !in text.lowercase())
    }

    @Test fun `the user store is declared in the fleet config as a migrating config store used by cloud-me`() {
        val f = File("").absoluteFile.let { var d: File? = it; var r: File? = null; while (d != null && r == null) { File(d, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json").takeIf { x -> x.isFile }?.let { x -> r = x }; d = d.parentFile }; r }
            ?: throw AssertionError("fleet-config.json not found")
        val store = JSONObject(f.readText()).getJSONObject("stores").getJSONObject(HealthUserStore.PREFS)
        assertEquals("config", store.getString("class"))
        assertEquals("prefs", store.getString("kind"))
        assertEquals("ac_cloud-me", store.getJSONArray("used_by").getString(0))
    }
}

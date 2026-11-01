package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Experiences' Done | Planned view: the split, "Mark done", the per-category counts and the By 30 / 40 / 50 milestones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExperiencesTest {
    private val pages = listOf("work", "study/roadmap") + listOf("geo", "endurance", "growth", "community", "by30", "by40", "by50").map { "achieves/$it" }
    private fun block(p: String): JSONObject = JSONArray(BuroLlcTestData.text("data/ui/experiences/$p.json")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.single { it.optString("kind") == "ledger" }
    }
    private fun defs(p: String) = block(p).getJSONArray("ledgers").let { a -> (0 until a.length()).map { LedgerDef.parse(a.getJSONObject(it)) } }

    /** By 30's two collections: two done, one planned, one in progress. */
    private val by30 = defs("achieves/by30")
    private val data = LedgerData()
        .added("by30_done", mapOf("title" to "Degree", "category" to "💼 Career and Finance", "date" to "2015-12-01"))
        .added("by30_done", mapOf("title" to "Marathon", "category" to "🌍 Health & Wellness", "date" to "2017-05-01"))
        .added("by30_todo", mapOf("title" to "Pilot licence", "category" to "✨ Creativity & Fun", "date" to "2030-01-01"))
        .added("by30_todo", mapOf("title" to "Fund", "category" to "💼 Career and Finance", "status" to "In progress"))

    @Test fun `every Experiences tab is a Done and Planned view with a status, a target date and a link`() {
        for (p in pages) {
            val b = block(p)
            assertEquals(p, "done_plan", b.getString("view"))
            assertEquals(p, LedgerStore.EXP, b.getString("store"))
            assertTrue("$p emoji", b.optString("emoji").isNotBlank())
            val d = defs(p)
            val done = ExperiencesModel.doneDef(d)!!; val plan = ExperiencesModel.planDef(d)!!
            assertEquals(p, plan.collection, done.moveTo); assertEquals(p, done.collection, plan.moveTo)
            assertEquals(p, listOf("Planned", "In progress"), plan.fields.single { it.id == "status" }.options)
            assertEquals(p, "Target date", plan.fields.single { it.id == "date" }.label)
            for (def in listOf(done, plan)) {
                assertTrue("$p link", def.fields.any { it.id == "link" && it.type == "text" })
                for (r in def.builtin + def.suggestions) assertTrue("$p $r", LedgerModel.validate(def, r).isEmpty())
            }
        }
    }

    @Test fun `items split into done and planned, in progress counting as planned`() {
        val (done, planned) = ExperiencesModel.split(ExperiencesModel.items(by30, data))
        assertEquals(listOf("Degree", "Marathon"), done.map { it.title }.sorted())
        assertEquals(listOf("Fund", "Pilot licence"), planned.map { it.title }.sorted())
        assertTrue(done.all { it.status == ExpStatus.DONE && it.collection == "by30_done" })
        assertEquals(ExpStatus.IN_PROGRESS, planned.single { it.title == "Fund" }.status)
        assertEquals(ExpStatus.PLANNED, planned.single { it.title == "Pilot licence" }.status)
        // Declared rows land on their side read-only; an accepted suggestion stops showing as one.
        val end = defs("achieves/endurance")
        val (eDone, ePlan) = ExperiencesModel.split(ExperiencesModel.items(end, LedgerData()))
        assertEquals(end[0].builtin.size, eDone.size); assertTrue(eDone.all { it.readOnly })
        assertEquals(end[1].suggestions.size, ePlan.count { it.suggestion >= 0 })
        val accepted = LedgerData().added("endurance_todo", end[1].suggestions[0])
        assertEquals(end[1].suggestions.size - 1, ExperiencesModel.items(end, accepted).count { it.suggestion >= 0 })
    }

    @Test fun `Mark done moves the plan to Done and stamps today, keeping the target and dropping the status`() {
        val m = ExperiencesModel.markDone(data, "by30_todo", "by30_done", "r1", "2026-11-01")
        assertEquals(listOf("Fund"), m.rows("by30_todo").map { it["title"] })
        val moved = m.rows("by30_done").single { it["title"] == "Pilot licence" }
        assertEquals("2026-11-01", moved["date"])
        assertEquals("2030-01-01", moved[ExperiencesModel.TARGET])
        // A plan with no target date, in progress: stamped, status gone.
        val m2 = ExperiencesModel.markDone(data, "by30_todo", "by30_done", "r2", "2026-11-01")
        val fund = m2.rows("by30_done").single { it["title"] == "Fund" }
        assertEquals("2026-11-01", fund["date"]); assertNull(fund["status"]); assertNull(fund[ExperiencesModel.TARGET])
        assertEquals(ExpStatus.DONE, ExperiencesModel.split(ExperiencesModel.items(by30, m2)).first.single { it.title == "Fund" }.status)
        // An unknown id changes nothing; Mark undone restores the target date.
        assertEquals(data.toJson(), ExperiencesModel.markDone(data, "by30_todo", "by30_done", "r9", "2026-11-01").toJson())
        val back = ExperiencesModel.markUndone(m, "by30_done", "by30_todo", m.rows("by30_done").single { it["title"] == "Pilot licence" }["id"]!!)
        assertEquals("2030-01-01", back.rows("by30_todo").single { it["title"] == "Pilot licence" }["date"])
        // Start / Pause.
        val started = ExperiencesModel.withStatus(data, "by30_todo", "r1", ExpStatus.IN_PROGRESS)
        assertEquals("In progress", started.rows("by30_todo").single { it["id"] == "r1" }["status"])
        assertNull(ExperiencesModel.withStatus(started, "by30_todo", "r1", ExpStatus.PLANNED).rows("by30_todo").single { it["id"] == "r1" }["status"])
    }

    @Test fun `counts are per category, done vs planned, in progress counted as planned`() {
        val c = ExperiencesModel.counts(ExperiencesModel.items(by30, data)).associateBy { it.category }
        assertEquals(CatCount("💼 Career and Finance", 1, 1), c["💼 Career and Finance"])
        assertEquals(CatCount("🌍 Health & Wellness", 1, 0), c["🌍 Health & Wellness"])
        assertEquals(CatCount("✨ Creativity & Fun", 0, 1), c["✨ Creativity & Fun"])
        assertEquals("1 done · 1 planned", c.getValue("💼 Career and Finance").label)
        assertEquals("💼 Career and Finance", ExperiencesModel.counts(ExperiencesModel.items(by30, data)).first().category)
        assertEquals(0.5f, ExperiencesModel.progress(2, 2)); assertEquals(0f, ExperiencesModel.progress(0, 0))
        // Geo groups by continent; a record with none is "Other".
        val geo = defs("achieves/geo")
        val g = ExperiencesModel.items(geo, LedgerData().added("geo_todo", mapOf("title" to "Tokyo", "country" to "Japan")), "continent")
        assertEquals("Other", g.single { it.title == "Tokyo" }.category)
        assertTrue(ExperiencesModel.counts(g).any { it.category == "Europe" && it.done > 0 })
    }

    @Test fun `done sorts newest first, planned by nearest target with in progress first, undated last`() {
        val (done, planned) = ExperiencesModel.split(ExperiencesModel.items(by30, data
            .added("by30_done", mapOf("title" to "Undated", "category" to "x"))
            .added("by30_todo", mapOf("title" to "Soon", "category" to "x", "date" to "2027-01-01"))))
        assertEquals(listOf("Marathon", "Degree", "Undated"), ExperiencesModel.sorted(done).map { it.title })
        assertEquals(listOf("Fund", "Soon", "Pilot licence"), ExperiencesModel.sorted(planned).map { it.title })
        val groups = ExperiencesModel.groups(done)
        assertEquals(done.size, groups.sumOf { it.second.size })
        assertEquals(listOf("Fund"), ExperiencesModel.filter(planned, "fUND", null).map { it.title })
        assertEquals(listOf("Pilot licence"), ExperiencesModel.filter(planned, "", "✨ Creativity & Fun").map { it.title })
        assertTrue(ExperiencesModel.filter(planned, "nothing like this", null).isEmpty())
    }

    @Test fun `milestones bucket each item by the age it was done or planned before`() {
        val ages = listOf(30, 40, 50)
        assertEquals(30, ExperiencesModel.bucket(2015, 40, 1988, ages))
        assertEquals("the year of the 30th birthday is no longer before 30", 40, ExperiencesModel.bucket(2018, 30, 1988, ages))
        assertEquals(30, ExperiencesModel.bucket(2017, 50, 1988, ages))
        assertEquals(50, ExperiencesModel.bucket(2045, 30, 1988, ages))
        assertEquals(50, ExperiencesModel.bucket(null, 50, 1988, ages))
        val d = data
            .added("by40_done", mapOf("title" to "Early", "date" to "2016-01-01"))
            .added("by40_todo", mapOf("title" to "Planned 2027", "date" to "2027-06-01"))
            .added("by50_todo", mapOf("title" to "Someday"))
        val m = ExperiencesModel.milestones(1988, ages, d, 2026)
        assertEquals(listOf(30, 40, 50), m.map { it.age }); assertEquals(listOf(2018, 2028, 2038), m.map { it.year })
        // by30: Degree 2015, Marathon 2017 and by40's Early 2016 were all done before 30.
        assertEquals(listOf(3, 0, 0), m.map { it.done })
        // Planned: Pilot licence 2030 is past 40 (2028), so 50; Fund is undated, so its own 30; Planned 2027 -> 40; Someday -> 50.
        assertEquals(listOf(1, 1, 2), m.map { it.planned })
        assertEquals(listOf(true, false, false), m.map { it.reached })
        val declared = block("achieves/by40").getJSONObject("milestones")
        assertEquals(40, declared.getInt("current")); assertTrue(declared.getInt("born") in 1900..2026)
        assertFalse(block("achieves/geo").has("milestones"))
    }

    @Test fun `the category icon is the leading emoji, else the tab's`() {
        assertEquals("🌿", ExperiencesModel.icon("🌿 Personal Growth", "⭐"))
        assertEquals("Personal Growth", ExperiencesModel.name("🌿 Personal Growth"))
        assertEquals("⭐", ExperiencesModel.icon("Europe", "⭐")); assertEquals("Europe", ExperiencesModel.name("Europe"))
        assertEquals("⭐", ExperiencesModel.icon("Other", "⭐"))
    }
}

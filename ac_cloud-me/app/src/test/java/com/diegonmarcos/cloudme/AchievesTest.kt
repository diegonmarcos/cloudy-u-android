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

/** #1350 Achieves: one tab per kind of experience, Done and To do lists, Geo stats, and the move of the old records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AchievesTest {
    init { BuroLlcTestData.installRenamed() }
    private val tabs = listOf("geo", "endurance", "growth", "community", "by30", "by40", "by50")
    private val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
    private val sections = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val experiences = sections.first { it.getString("id") == "pers" }   // #1901 Personal Archives moved from Experiences to Pers
    private val prof = sections.first { it.getString("id") == "prof" }
    private fun sub(o: JSONObject) = o.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val section = sub(experiences).first { it.getString("id") == "achieves" }
    /** Every page id of a section, at every depth (a deep link names any of them). */
    private fun flat(o: JSONObject): List<String> = if (!o.has("pages")) emptyList() else sub(o).flatMap { listOf(it.getString("id")) + flat(it) }
    private fun page(p: String) = JSONArray(BuroLlcTestData.text("data/ui/pers/achieves/$p.json"))
    private fun ledger(p: String) = page(p).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.single { it.optString("kind") == "ledger" } }
    private fun defs(p: String) = ledger(p).getJSONArray("ledgers").let { a -> (0 until a.length()).map { LedgerDef.parse(a.getJSONObject(it)) } }
    private val allSections = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
    private val spec = AchievesMigration.declared(allSections, LedgerStore.EXP)!!

    /** The old Experiences store, one record per category it offered, in both collections, plus a category it never had. */
    private val oldCats = listOf("🎸 Live concert", "🎪 Festival", "🏁 Track / place", "🥾 Hike", "🏕️ Camping", "⚓ Diving",
        "🪂 Skydiving / air", "🌊 Water / sea", "🏔️ Mountain / snow", "🌐 Travel", "something typed by hand")
    private val legacy: LedgerData = oldCats.foldIndexed(LedgerData()) { i, d, c ->
        d.added("done", mapOf("title" to "done $i", "category" to c, "date" to "2024-0${i % 9 + 1}-01", "place" to "Lisbon, Portugal", "note" to "n$i"))
            .added("todo", mapOf("title" to "todo $i", "category" to c))
    }

    @Test fun `the tabs are Geo Endurance Personal Growth Community By 30 By 40 By 50 and Trips is gone`() {
        val pages = section.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(tabs, pages.map { it.getString("id") })
        assertEquals(listOf("Geo", "Endurance", "Personal Growth", "Community", "By 30", "By 40", "By 50"), pages.map { it.getString("label") })
        assertTrue(pages.none { it.getString("label").contains("Trips") || it.getString("label").contains("International") })
    }

    @Test fun `every tab is a Done and To do ledger over experiences_user that toggles both ways and stamps the done date`() {
        for (t in tabs) {
            assertEquals(t, LedgerStore.EXP, ledger(t).getString("store"))
            val (done, todo) = defs(t)
            assertEquals(listOf("${t}_done", "${t}_todo"), listOf(done.collection, todo.collection))
            assertEquals("${t}_todo", done.moveTo); assertEquals("${t}_done", todo.moveTo)
            assertEquals("date", todo.moveStamp)
            assertTrue(t, todo.fields.any { it.id == "date" && it.type == "date" })
            for (d in listOf(done, todo)) for (r in d.builtin + d.suggestions) assertTrue("$t $r", LedgerModel.validate(d, r).isEmpty())
        }
    }

    @Test fun `each tab offers the categories it holds`() {
        fun cats(t: String, id: String) = defs(t)[0].fields.single { it.id == id }.options
        assertEquals(listOf("🌿 Personal Growth", "🌍 Health & Wellness", "✨ Creativity & Fun", "💼 Career and Finance"), cats("growth", "category"))
        assertEquals(listOf("❤️ Relationship & Community"), cats("community", "category"))
        assertEquals("🌐 Travel & Adventure", ledger("endurance").getString("title"))
        assertEquals(oldCats.dropLast(1) - "🌐 Travel", cats("endurance", "category"))
        assertEquals("🌱 (By 30) Foundational goals", ledger("by30").getString("title"))
        assertEquals("🌳 (By 40) Mastery & long-term planning", ledger("by40").getString("title"))
        assertEquals("🍁 (By 50) Legacy goals", ledger("by50").getString("title"))
    }

    @Test fun `migration moves every old record to its tab, keeps every value and drops the old collections`() {
        val m = AchievesMigration.migrate(legacy, spec)
        assertEquals(setOf("endurance_done", "endurance_todo", "geo_done", "geo_todo"), m.collections())
        assertEquals(legacy.rows("done").size + legacy.rows("todo").size, m.collections().sumOf { m.rows(it).size })
        // Travel goes to Geo, its place becomes the country; everything else, unknown categories too, goes to Endurance.
        assertEquals(listOf("done 9"), m.rows("geo_done").map { it["title"] })
        assertEquals("Lisbon, Portugal", m.rows("geo_done")[0]["country"]); assertNull(m.rows("geo_done")[0]["place"])
        assertEquals(listOf("todo 9"), m.rows("geo_todo").map { it["title"] })
        assertEquals(10, m.rows("endurance_done").size)
        assertTrue(m.rows("endurance_done").any { it["category"] == "something typed by hand" })
        for (old in legacy.rows("done")) {
            val now = m.collections().flatMap { m.rows(it) }.single { it["title"] == old["title"] }
            assertEquals(old["date"], now["date"]); assertEquals(old["note"], now["note"]); assertEquals(old["category"], now["category"])
        }
        // Endurance records stay valid under the new form (their categories are its options).
        val endDone = defs("endurance")[0]
        assertTrue(m.rows("endurance_done").filter { it["category"] in endDone.fields[1].options }.all { LedgerModel.validate(endDone, it - "id").isEmpty() })
        assertEquals(9, m.rows("endurance_done").count { it["category"] in endDone.fields[1].options })
    }

    @Test fun `migration runs once and never overwrites a value or touches the new collections`() {
        val m = AchievesMigration.migrate(legacy.added("geo_done", mapOf("title" to "Lisbon", "country" to "Portugal")), spec)
        assertEquals(m.toJson(), AchievesMigration.migrate(m, spec).toJson())
        assertEquals("Lisbon", m.rows("geo_done")[0]["title"])
        val kept = AchievesMigration.migrate(LedgerData().added("done", mapOf("title" to "x", "category" to "🌐 Travel", "place" to "p", "country" to "Spain")), spec)
        assertEquals("Spain", kept.rows("geo_done")[0]["country"]); assertEquals("p", kept.rows("geo_done")[0]["place"])
    }

    @Test fun `every migration target is a declared tab with that collection`() {
        for (tab in spec.byValue.values + spec.default) for (suffix in spec.legacy.values)
            assertTrue("$tab$suffix", defs(tab).any { it.collection == tab + suffix })
        assertNull(AchievesMigration.declared(allSections, LedgerStore.BURO))
    }

    @Test fun `marking done stamps today only when the record has no date`() {
        val d = LedgerData().added("a_todo", mapOf("title" to "x")).added("a_todo", mapOf("title" to "y", "date" to "2020-01-01"))
        val m = d.moved("a_todo", "r1", "a_done", "date", "2026-10-30").moved("a_todo", "r2", "a_done", "date", "2026-10-30")
        assertEquals(listOf("2026-10-30", "2020-01-01"), m.rows("a_done").map { it["date"] })
        assertNull(d.moved("a_todo", "r1", "a_done").rows("a_done")[0]["date"])
        assertEquals("y", m.moved("a_done", "r2", "a_todo").rows("a_todo")[0]["title"])
    }

    @Test fun `Geo stats count places, countries and continents, share of world, visit range and grouping`() {
        val g = ledger("geo").getJSONObject("geo")
        val user = LedgerData()
            .added("geo_done", mapOf("title" to "Lisbon", "country" to "Portugal", "continent" to "Europe", "date" to "2019-05-01", "last" to "2023-02-01"))
            .added("geo_done", mapOf("title" to " berlin ", "country" to "germany", "continent" to "Europe", "date" to "2018-01-01"))
            .added("geo_done", mapOf("title" to "Tokyo", "country" to "Japan", "continent" to "Asia", "last" to "2025-12-24"))
            .added("geo_todo", mapOf("title" to "Lima", "country" to "Peru", "continent" to "South America"))
        val s = GeoStats.compute(g, defs("geo"), user)
        // builtin: 9 cities over Brazil, Germany, Spain, United Kingdom, France, Morocco, Thailand; user adds Lisbon and Tokyo
        assertEquals(11, s.cities)
        assertEquals(9, s.countries)
        assertEquals(4, s.continents)
        assertEquals("4.6%", s.pctWorld)
        assertEquals("2018-01-01", s.first); assertEquals("2025-12-24", s.last)
        assertEquals(1, s.wishlist)
        assertEquals(listOf("Africa", "Asia", "Europe", "South America"), s.byContinent.keys.toList())
        assertEquals(listOf("Japan", "Thailand"), s.byContinent.getValue("Asia").keys.toList())
        assertEquals(listOf("Rio de Janeiro", "São Paulo"), s.byContinent.getValue("South America").getValue("Brazil"))
        assertTrue(s.declared!!.contains("175 cities"))
        val empty = GeoStats.summarize(emptyList(), 0, 195, 7)
        assertEquals(0, empty.cities); assertEquals("0.0%", empty.pctWorld); assertNull(empty.first)
    }

    @Test fun `the owner's old Achieves text moved into the new tabs`() {
        val all = tabs.joinToString("") { BuroLlcTestData.text("data/ui/pers/achieves/$it.json") }
        for (w in listOf("B1) Personal", "B2) Personal Pursuits", "B3) International Experience", "https://nomadmania.com/profile/73889",
                "Trip management", "Before you go", "My Bucket 50 (things before 50)", "Oceans Seven endurance swims", "🪂 Skydiving"))
            assertTrue(w, all.contains(w))
        assertTrue(BuroLlcTestData.text("data/ui/pers/achieves/geo.json").contains("B3) International Experience"))
    }

    @Test fun `the bar is Pers Prof Proj Inventory then the rest, Pers holds Personal Archives and Prof holds Profiles (LinkedIn, CV) and the three company sites`() {
        val bar = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("pers", "prof", "proj", "lnktree", "inventory", "buro", "llc"), bar)
        assertTrue(sections.none { it.getString("id") in setOf("study", "achieves", "experiences", "health", "socials") })
        assertEquals(listOf("pers-profiles", "health", "astro", "dd", "achieves"), sub(experiences).map { it.getString("id") })
        assertEquals(listOf("Profiles", "Health", "Astro", "DD", "Personal Archives"), sub(experiences).map { it.getString("label") })
        // #1937 owner-ordered removal: Prof > More (Professional, Work, Study > CV, Roadmap) is gone; Prof is Profiles (LinkedIn, CV) | Leaf.y, Nexus.z, Stark.x
        assertEquals(listOf("prof-web", "leafy", "nexus", "stark"), sub(prof).map { it.getString("id") })
        assertEquals(listOf("prof-professional", "prof-linkedin", "prof-portfolio", "prof-developer", "prof-cv"), sub(sub(prof)[0]).map { it.getString("id") })
        val ids = flat(experiences)
        assertEquals("page ids are unique in Pers, so page:pers/<id> is never ambiguous", ids.size, ids.toSet().size)
        val pids = flat(prof)
        assertEquals("page ids are unique in Prof", pids.size, pids.toSet().size)
    }

    @Test fun `the removed Prof pages leave no page file behind (#1937)`() {
        for (f in listOf("professional", "work", "study/cv", "study/roadmap"))
            assertFalse("data/ui/prof/prof-profiles/$f.json is back", runCatching { BuroLlcTestData.text("data/ui/prof/prof-profiles/$f.json") }.isSuccess)
    }

    @Test fun `old Study and Achieves routes redirect to a declared Prof or Pers page`() {
        val persIds = flat(experiences).toSet(); val profIds = flat(prof).toSet()
        val want = mapOf(("study" to null) to ("prof" to "prof-web"), ("study" to "cv") to ("prof" to "prof-web"), ("study" to "roadmap") to ("prof" to "prof-web"),
            ("achieves" to null) to ("pers" to "geo"), ("achieves" to "personal") to ("pers" to "growth"), ("achieves" to "pursuits") to ("pers" to "endurance"),
            ("achieves" to "international") to ("pers" to "geo"), ("achieves" to "trips") to ("pers" to "geo"), ("achieves" to "experiences") to ("pers" to "endurance"),
            ("achieves" to "geo") to ("pers" to "geo"), ("achieves" to "by30") to ("pers" to "by30"), ("achieves" to "by50") to ("pers" to "by50"))
        for ((old, to) in want) {
            assertEquals("$old", to.first to to.second, LegacyRoutes.resolve(old.first, old.second))
            assertTrue(to.second!! in (if (to.first == "pers") persIds else profIds))
        }
        // #1901 the Experiences ids of the layout in between: the Achieves tabs went to Pers; #1937 Work, Study, CV and Roadmap (removed by owner order) resolve to Prof > Profiles
        for (p in listOf("work", "study", "cv", "roadmap")) assertEquals("prof" to "prof-web", LegacyRoutes.resolve("experiences", p))
        for (p in listOf("prof-profiles", "professional", "work", "study", "cv", "roadmap")) assertEquals("prof" to "prof-web", LegacyRoutes.resolve("prof", p))
        for (p in listOf("achieves", "geo", "endurance", "growth", "community", "by30", "by40", "by50")) assertEquals("pers" to p, LegacyRoutes.resolve("experiences", p))
    }
}

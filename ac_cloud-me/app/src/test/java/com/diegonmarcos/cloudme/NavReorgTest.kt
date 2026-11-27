package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1901 Health became Pers, Experiences became Prof, Socials became Proj and the Lnktree section was spread over them. Three tests on the
 * BAKED tree (what the app reads): the navigation tree (labels, order, defaults), the rename map (every id of the old tree still lands on
 * a page, so deep links and the SuperApp Me row resolve) and the embedded URLs (each tile opens its own site, declared in its page file).
 * The field parity against the baseline tree is test/test-nav-reorg.sh (mutation-proven).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavReorgTest {
    init { BuroLlcTestData.installRenamed() }
    private fun arr(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
    private val baseline = JSONObject(BuroLlcTestData.text("test/fixtures/nav-baseline-1901.json"))
    private fun ids(pages: List<Page>) = pages.map { it.id }
    private fun leafOf(section: String, page: String?) = Sections.byId(section)!!.page(page)?.leaf()?.id

    // ---- the navigation tree

    @Test fun `the bar is Pers Prof Proj Lnktree Inventory Buro LLC and the app opens on Lnktree`() {
        assertEquals(listOf("Pers", "Prof", "Proj", "Lnktree", "Inventory", "Buro", "LLC"), Sections.bottom().map { it.label })
        assertEquals(listOf("pers", "prof", "proj", "lnktree", "inventory", "buro", "llc"), Sections.bottom().map { it.id })
        assertEquals("lnktree", Sections.default()?.id)
        // #1901 correction: the Lnktree bottom-nav entry is not removed, it keeps its sixteen pages
        assertEquals(16, Sections.byId("lnktree")!!.pages.size)
        assertEquals("lt-lnktree-site", leafOf("lnktree", null))
        for (gone in listOf("health", "experiences", "socials")) assertEquals("$gone moved, it is no section", null, Sections.byId(gone))
    }

    @Test fun `Pers is two rows, Profiles Media Health Astro DD over Achieves Geo to By 50, with Health holding the whole former Health section`() {
        val pers = Sections.byId("pers")!!
        // #1976 one flat strip of pages, drawn as the two declared rows (nav_mode horizontal_two_rows, groups)
        assertEquals(listOf("Media", "Health", "Astro", "DD", "Geo", "Endurance", "Personal Growth", "Community", "By 30", "By 40", "By 50"), pers.pages.map { it.label })
        assertEquals(listOf(listOf("Profiles", "pers-profiles", "health", "astro", "dd"), listOf("Achieves", "geo", "endurance", "growth", "community", "by30", "by40", "by50")),
            pers.nav.rowGroups().map { g -> listOf(g.label) + g.pages.map { it.id } })
        // Media is ONE flat strip, Personal (the mySocials home of the others) first, no Instagram / Social grouping level
        assertEquals(listOf("personal", "shelf", "vinyl", "bar", "menu", "theater", "story", "ig0", "ig1", "ig2", "face0", "pinterest", "youtube", "tidal", "linkedin", "orkut", "icq", "dms"), ids(pers.page("pers-profiles")!!.pages))
        assertTrue(pers.page("pers-profiles")!!.pages.all { it.pages.isEmpty() })
        val health = pers.page("health")!!
        assertEquals(listOf("trackers", "plan", "health-profile"), ids(health.pages))
        assertEquals(listOf("tracked", "activities", "bodycomp", "hr", "sleep"), ids(pers.page("trackers")!!.pages))
        assertEquals(14, pers.page("plan")!!.pages.size)
        // the page chain IS the folder path of the page's content file
        assertEquals(listOf("health", "trackers", "tracked"), pers.path("tracked").map { it.id })
        assertEquals(listOf("geo"), pers.path("geo").map { it.id })
        assertEquals(listOf("pers-profiles", "ig0"), pers.path("ig0").map { it.id })
        // each container opens its first leaf; Pers opens on Media > Personal, Health on Tracked
        assertEquals("personal", leafOf("pers", null))
        assertEquals("tracked", leafOf("pers", "health"))
        // the retired container ids resolve through ui.renamed
        assertEquals("pers" to "pers-profiles", LegacyRoutes.resolve("pers", "pers-profiles"))   // the SuperApp Me-Pers tile target stays a declared page
        assertEquals("pers" to "ig0", LegacyRoutes.resolve("pers", "pers-instagram")); assertEquals("pers" to "face0", LegacyRoutes.resolve("pers", "pers-social"))
        assertEquals("pers" to "geo", LegacyRoutes.resolve("pers", "achieves"))
    }

    @Test fun `Prof is Profiles then Leaf y Nexus z Stark x and Proj is Lnktree then the areas, Lnktree first and default`() {
        val prof = Sections.byId("prof")!!
        assertEquals(listOf("Profiles", "Leaf.y", "Nexus.z", "Stark.x"), prof.pages.map { it.label })   // #1937 More (and Professional, Work, Study) removed by owner order
        assertEquals(listOf("prof-professional", "prof-linkedin", "prof-portfolio", "prof-developer", "prof-cv"), ids(prof.page("prof-web")!!.pages))
        for (gone in listOf("prof-profiles", "professional", "work", "study", "cv", "roadmap")) assertTrue("$gone is removed", prof.path(gone).isEmpty())
        assertEquals("prof-professional", leafOf("prof", null))   // #1948 Prof opens on Profiles > Professional
        val proj = Sections.byId("proj")!!
        assertEquals(listOf("pj-home", "pj-repos", "pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42"), ids(proj.pages))
        assertEquals(listOf("Home", "Repos", "Unix", "CG", "Capital", "Hardtech", "Wiki", "Projects", "42"), proj.pages.map { Regex("^[^\\p{L}\\p{N}]+ ").replace(it.label, "") })   // #1918 each label carries its Linktree emoji
        assertEquals("proj opens on its Home grid", "pj-home", leafOf("proj", null))
        assertEquals("the Lnktree bottom-nav entry opens on the Lnktree site", "lt-lnktree-site", leafOf("lnktree", null))
        // the groups are the declared rows: Prof is `Profiles | Leaf.y, Nexus.z, Stark.x`, Proj is `Lnktree` over the areas
        fun groups(s: String) = arr(ui.getJSONArray("sections")).first { it.getString("id") == s }.getJSONArray("groups").let { g -> arr(g).associate { it.getString("id") to (0 until it.getJSONArray("pages").length()).map { i -> it.getJSONArray("pages").getString(i) } } }
        assertEquals(mapOf("profiles" to listOf("prof-web"), "companies" to listOf("leafy", "nexus", "stark")), groups("prof"))
        assertEquals(listOf("lnktree", "prof", "pers", "proj"), groups("lnktree").keys.toList())
        assertEquals(listOf("lt-lnktree-site"), groups("lnktree").getValue("lnktree"))
    }

    // ---- the rename map

    @Test fun `every page of the old tree still resolves to a page of the new one`() {
        val pages = baseline.getJSONObject("pages")
        var moved = 0
        for (key in pages.keys()) {
            val old = key.substringBefore('/'); val page = key.substringAfter('/')
            val (s, p) = LegacyRoutes.resolve(old, page)
            val section = Sections.byId(s)
            assertNotNull("$key -> $s/$p: the section is gone", section)
            if (Sections.byId(old) == null) moved++
            assertTrue("$key -> $s/$p: the page is gone", p == null || section!!.path(p).isNotEmpty())
        }
        assertEquals("the baseline holds the 19 + 12 + 2 pages of the three moved sections", 33, moved)
        for (old in listOf("health", "experiences", "socials", "lnktree", "me", "study", "achieves")) {
            val (s, _) = LegacyRoutes.resolve(old, null)
            assertNotNull("$old -> $s: the section is gone", Sections.byId(s))
        }
    }

    @Test fun `deep links of the old ids land where the content went, the SuperApp Me row among them`() {
        val want = mapOf(
            ("health" to "home") to ("pers" to "home"), ("health" to "tracked") to ("pers" to "tracked"), ("health" to "workout") to ("pers" to "workout"),
            ("health" to null) to ("pers" to null),
            ("experiences" to "work") to ("prof" to "prof-web"), ("experiences" to "cv") to ("prof" to "prof-web"), ("prof" to "prof-profiles") to ("prof" to "prof-web"), ("prof" to "study") to ("prof" to "prof-web"), ("experiences" to "achieves") to ("pers" to "geo"),
            ("experiences" to "geo") to ("pers" to "geo"), ("experiences" to "by50") to ("pers" to "by50"),
            ("socials" to "socials-professional") to ("prof" to "prof-web"), ("socials" to "socials-personal") to ("pers" to "ig0"), ("pers" to "instagram") to ("pers" to "ig0"), ("pers" to "social") to ("pers" to "ig0"),
            ("lnktree" to "professional") to ("lnktree" to "lt-professional"), ("lnktree" to "personal") to ("lnktree" to "lt-personal"),
            ("lnktree" to "leafy") to ("lnktree" to "lt-leafy"), ("lnktree" to "dd") to ("lnktree" to "lt-dd"), ("lnktree" to "health") to ("lnktree" to "lt-health"),
            ("lnktree" to "lnktree-site") to ("lnktree" to "lt-lnktree-site"), ("proj" to "lnktree-site") to ("lnktree" to "lt-lnktree-site"), ("proj" to "area-unix") to ("proj" to "pj-unix"), ("proj" to "area-front") to ("proj" to "pj-projects"), ("lnktree" to "area-unix") to ("lnktree" to "lt-area-unix"),
            ("lnktree" to null) to ("lnktree" to null), ("socials" to null) to ("proj" to null),
            // ids of the layouts before the previous one chain through both tables
            ("me" to "leafy") to ("lnktree" to "lt-leafy"), ("me" to "lnktree") to ("lnktree" to "lt-lnktree-site"), ("study" to "cv") to ("prof" to "prof-web"),
            ("achieves" to "personal") to ("pers" to "growth"),
        )
        for ((old, to) in want) assertEquals("${old.first}/${old.second}", to, LegacyRoutes.resolve(old.first, old.second))
        // the live ids pass through untouched
        for (live in listOf("pers" to "geo", "prof" to "prof-web", "proj" to "pj-home", "buro" to "ids", "tasks" to "backlogs")) assertEquals(live, LegacyRoutes.resolve(live.first, live.second))
    }

    @Test fun `ui renamed names only pages and sections of the live tree and the asset the app reads is build json's`() {
        val ren = ui.getJSONObject("renamed")
        // app/build.gradle writes build.json::ui.renamed to the renamed.json asset; LegacyRoutes.load reads it at start-up
        val baked = LegacyRoutes.Renamed.fromAsset(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(BuroLlcTestData.renamed(), baked)
        assertEquals(ren.getJSONObject("pages").length(), baked.pages.size)
        assertEquals(ren.getJSONObject("sections").length(), baked.sections.size)
        for ((old, to) in baked.pages) {
            assertTrue("a renamed page is no live page: $old", Sections.byId(old.substringBefore('/'))?.path(old.substringAfter('/')).isNullOrEmpty())
            assertTrue("$old -> $to", Sections.byId(to.substringBefore('/'))?.path(to.substringAfter('/'))?.isNotEmpty() == true)
        }
        for ((old, to) in baked.sections) { assertEquals("$old is a live section", null, Sections.byId(old)); assertNotNull(to, Sections.byId(to)) }
        assertFalse(LegacyRoutes.Renamed.EMPTY == baked)
        LegacyRoutes.install(baked)   // the same table drives the app: every old id still resolves with it
        assertEquals("pers" to "home", LegacyRoutes.resolve("health", "home"))
    }

    // ---- the embedded URLs

    @Test fun `each tile opens its own site and the URL is declared data in its page file`() {
        val want = listOf(
            Triple("prof", "leafy", "https://diegonmarcos.github.io/leafy/"), Triple("prof", "nexus", "https://diegonmarcos.github.io/nexus/"),
            Triple("prof", "stark", "https://diegonmarcos.github.io/stark/"),
            Triple("pers", "personal", "https://diegonmarcos.github.io/mySocials/"), Triple("pers", "ig0", "https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html"),
            Triple("pers", "astro", "https://diegonmarcos.github.io/myAstro/"), Triple("lnktree", "lt-lnktree-site", "https://linktree.diegonmarcos.com/"),
        )
        for ((section, page, url) in want) {
            val chain = Sections.byId(section)!!.path(page).map { it.id }
            assertTrue("$section/$page is not declared", chain.isNotEmpty())
            val stack = JSONArray(BuroLlcTestData.text("data/ui/$section/${chain.joinToString("/")}.json"))
            assertEquals(1, stack.length())
            val b = stack.getJSONObject(0)
            assertEquals("fragment", b.getString("kind")); assertEquals("web", b.getString("id")); assertEquals("$section/$page", url, b.getString("url"))
        }
        // the https sites are exactly the pages the cache holds (prefetch and the stale-while-revalidate disk cache)
        val cfg = LnktreeCacheConfig.parse(ui.getJSONObject("lnktree_cache"))
        val cached = want.mapNotNull { (s, p, _) ->
            val chain = Sections.byId(s)!!.path(p).map { it.id }
            LnktreePages.fromStack(p, JSONArray(BuroLlcTestData.text("data/ui/$s/${chain.joinToString("/")}.json")), cfg)
        }
        assertEquals(setOf("leafy", "nexus", "stark", "personal", "ig0", "astro", "lt-lnktree-site"), cached.map { it.id }.toSet())
        assertTrue(cfg.cachedSections().containsAll(listOf("pers", "prof", "proj")))   // #1951 every section with https pages is cached
    }
}

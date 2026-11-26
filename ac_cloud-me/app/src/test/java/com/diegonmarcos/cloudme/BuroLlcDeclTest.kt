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

/** The declarations of Buro and LLC: sections, many_rows groups, page files and the ledger blocks they carry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BuroLlcDeclTest {
    private val sections = BuroLlcTestData.text("build.json").let { JSONObject(it).getJSONObject("ui").getJSONArray("sections") }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }

    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    private fun groups(s: String) = sections.getValue(s).getJSONArray("groups").let { g ->
        (0 until g.length()).associate { i -> g.getJSONObject(i).getString("id") to g.getJSONObject(i).getJSONArray("pages").let { p -> (0 until p.length()).map { p.getString(it) } } }
    }
    private fun page(s: String, p: String) = JSONArray(BuroLlcTestData.text("data/ui/$s/$p.json"))

    @Test fun `the bar is Pers Prof Proj Inventory Buro LLC and House, Work, RoadMap, Duties and the Lnktree section are gone`() {
        val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
        val bar = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("pers", "prof", "proj", "lnktree", "inventory", "buro", "llc"), bar)
        assertEquals(listOf("Pers", "Prof", "Proj", "Lnktree", "Inventory", "Buro", "LLC"), bar.map { sections.getValue(it).getString("label") })
        assertFalse("house" in sections); assertFalse("work" in sections); assertFalse("roadmap" in sections); assertFalse("duties" in sections)
        for (gone in listOf("health", "experiences", "socials")) assertFalse("$gone is a section of the layout before #1901", gone in sections)
        assertEquals("lnktree", ui.getString("default_section"))   // #1901 correction: the app opens on the restored Lnktree entry
        assertEquals(listOf("pj-home", "pj-unix", "pj-cg", "pj-capital", "pj-hardtech", "pj-wiki", "pj-projects", "pj-lecole42", "pj-repos"), ids(sections.getValue("proj").getJSONArray("pages")))
        assertEquals(listOf("roadmaps", "backlogs"), ids(sections.getValue("tasks").getJSONArray("pages")))
        assertEquals("tasks", page("tasks", "backlogs").getJSONObject(0).getString("id"))
    }

    @Test fun `#1901 the Socials tabs live on in Prof and Pers on the mysocials pages, and Tasks stays declared off the bar`() {
        val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
        val bar = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertFalse("tasks" in bar)
        assertEquals("https://diegonmarcos.github.io/mySocials/instagram-diegocnmarcos_.html", page("pers/pers-profiles", "ig0").getJSONObject(0).getString("url"))
        for ((d, p) in listOf("pers/pers-profiles" to "ig0")) assertEquals("web", page(d, p).getJSONObject(0).getString("id"))
        // nothing dropped: Pers keeps Personal, Tasks keeps Tree and Due with their groups (#1937: Prof > More, with Professional, was removed by owner order)
        val profiles = { s: String -> sections.getValue(s).getJSONArray("pages").getJSONObject(0).getJSONArray("pages") }
        assertTrue("personal" in ids(profiles("pers")))
        assertEquals(listOf("roadmaps", "backlogs"), ids(sections.getValue("tasks").getJSONArray("pages")))
        assertEquals(setOf("backlogs", "roadmaps"), groups("tasks").keys)
    }

    @Test fun `Buro follows the owner hierarchy ID&DD, Secrets Web, Secrets CLI, Configs, Financial, Portfolio, Events and Docs is gone`() {
        assertEquals("horizontal_two_rows", sections.getValue("buro").getString("nav_mode"))
        assertEquals(mapOf(
            "iddd" to listOf("ids", "personal-dd", "paycards", "autocomplete", "wallet", "account"),
            "sec-web" to listOf("logins", "totp", "passkeys"),
            "sec-cli" to listOf("ssh", "apitokens", "jwt", "certs"),
            "cfg" to listOf("repos", "android", "desktop", "c3", "backup"),
            "financial" to listOf("accounts", "cards", "statements", "ir", "cashflow", "budgeting", "services"),
            "portfolio" to listOf("assets", "liabilities", "nav-value", "performance"),
            "events" to listOf("events-list", "calendar"),
        ), groups("buro"))
        assertFalse("docs" in groups("buro"))
        val flat = ids(sections.getValue("buro").getJSONArray("pages"))
        assertTrue(listOf("assets", "liabilities").all { it in flat })
        assertTrue("services" in flat)
    }

    @Test fun `the removed Docs route still lands on a kept page`() {
        val (s, p) = LegacyRoutes.resolve("buro", "docs")
        assertEquals("buro", s); assertTrue(p == "assets" || p == "liabilities")
    }

    @Test fun `each Secrets page hosts the secrets fragment bound to the me-secrets pack`() {
        for (p in listOf("logins", "totp", "passkeys", "ssh", "apitokens", "jwt", "certs")) {
            val frag = page("buro", p).let { st -> (0 until st.length()).map { st.getJSONObject(it) } }
                .single { it.optString("kind") == "fragment" }
            assertEquals("secrets", frag.getString("id"))
            assertEquals("me-secrets", frag.getString("pack"))
            assertTrue("secret_type set", frag.getString("secret_type").isNotBlank())
        }
    }

    @Test fun `LLC follows the hierarchy Employed, Side hustles, Assets and Portfolio, Calendar`() {
        assertEquals("horizontal_two_rows", sections.getValue("llc").getString("nav_mode"))
        assertEquals(mapOf("income" to listOf("employed", "side-hustles"), "manage" to listOf("company-assets", "portfolio"), "calendar" to listOf("llc-calendar")), groups("llc"))
    }

    @Test fun `every page is in exactly one group and has a data file`() {
        for (s in listOf("buro", "llc")) {
            val flat = ids(sections.getValue(s).getJSONArray("pages"))
            val all = groups(s).values.flatten()
            assertEquals("$s: a page is in two groups", all.size, all.toSet().size)
            assertEquals("$s: every page is in a group", flat.toSet(), all.toSet())
            for (p in flat) assertTrue("$s/$p has no file", page(s, p).length() > 0)
        }
    }

    @Test fun `no page file is left for the retired sections`() {
        for (rel in listOf("data/ui/work/employed.json", "data/ui/house/manage.json", "data/ui/buro/summary.json", "data/ui/me/tasks.json")) {
            val found = runCatching { BuroLlcTestData.text(rel) }.isSuccess
            assertFalse("$rel is back", found)
        }
    }

    @Test fun `every ledger block names its section's store, typed fields, a known report and no seeded rows`() {
        val known = setOf(LedgerReports.BUDGET_VS_ACTUAL, LedgerReports.ACCOUNTS_VS_CASHFLOW, LedgerReports.PORTFOLIO, LedgerReports.INCOME)
        var ledgers = 0
        for ((s, store) in mapOf("buro" to LedgerStore.BURO, "llc" to LedgerStore.LLC)) for (p in ids(sections.getValue(s).getJSONArray("pages"))) {
            val stack = page(s, p)
            for (b in (0 until stack.length()).map { stack.getJSONObject(it) }.filter { it.optString("kind") == "ledger" }) {
                assertEquals("$s/$p store", store, b.getString("store"))
                for (r in (b.optJSONArray("reports") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }) assertTrue("$s/$p report $r", r in known)
                val defs = b.getJSONArray("ledgers")
                for (i in 0 until defs.length()) {
                    ledgers++
                    val def = LedgerDef.parse(defs.getJSONObject(i))
                    assertTrue("$s/$p ${def.collection} has fields", def.fields.isNotEmpty())
                    assertTrue("$s/$p ${def.collection} first field is required text (the row title)", def.fields.first().let { it.required && it.type == "text" })
                    assertTrue("$s/$p ${def.collection} says what empty means", def.empty.isNotBlank())
                    for (f in def.fields) assertTrue("${f.id} type", f.type in setOf("text", "money", "date", "choice"))
                    assertFalse("seeded rows", defs.getJSONObject(i).has("rows") || defs.getJSONObject(i).has("seed"))
                }
            }
        }
        assertEquals("the declared ledgers: 11 in Buro, 4 in LLC", 15, ledgers)
    }

    @Test fun `the reports read collections the pages really declare`() {
        fun collections(s: String) = ids(sections.getValue(s).getJSONArray("pages")).flatMap { p ->
            page(s, p).let { st -> (0 until st.length()).map { st.getJSONObject(it) } }.filter { it.optString("kind") == "ledger" }
                .flatMap { b -> b.getJSONArray("ledgers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("collection") } } }
        }.toSet()
        assertTrue(collections("buro").containsAll(listOf("budget", "cashflow", "accounts")))
        assertTrue(collections("llc").containsAll(listOf("employment", "side_income", "holdings")))
    }

    @Test fun `the calendar pages host the calendar engine tasks view filtered to their subject, and writing is Cloud Tasks`() {
        for ((s, pg, subject) in listOf(Triple("buro", "calendar", "Buro"), Triple("llc", "llc-calendar", "LLC"))) {
            val b = page(s, pg).getJSONObject(0)
            assertEquals("fragment", b.getString("kind")); assertEquals("tasks", b.getString("id"))
            assertEquals(subject, b.getString("subject")); assertEquals("extapp:cloud-tasks", b.getString("open"))
        }
    }

    @Test fun `the user data stores are declared in fleet-config so the backup carries them`() {
        val stores = JSONObject(BuroLlcTestData.text("../fleet-private-data/fleet-config.json")).getJSONObject("stores")
        for (s in listOf(LedgerStore.BURO, LedgerStore.LLC)) {
            val st = stores.getJSONObject(s)
            assertEquals("config", st.getString("class")); assertEquals("prefs", st.getString("kind"))
            assertTrue("ac_cloud-me" in st.getJSONArray("used_by").let { a -> (0 until a.length()).map { a.getString(it) } })
        }
    }

    @Test fun `the Work history moved into LLC`() {
        val employed = page("llc", "employed"); val side = page("llc", "side-hustles")
        assertEquals("ledger", employed.getJSONObject(0).getString("kind"))
        assertEquals("ledger", side.getJSONObject(0).getString("kind"))
        val cards = { a: JSONArray -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("kind") == "cards" }.sumOf { it.getJSONArray("items").length() } }
        assertTrue("the LinkedIn roles are still there", cards(employed) >= 1)
        assertTrue("the own ventures are still there", cards(side) >= 1)
    }

    @Test fun `Proj is two rows, Lnktree then the areas, and Prof is Profiles then the three company sites`() {
        val proj = sections.getValue("proj")
        // #1914 Proj is one row: Home | Unix, CG, Capital, Hardtech, Wiki, Projects, 42; the Lnktree bottom-nav section holds the Lnktree row, first and default
        assertEquals("pj-home", ids(proj.getJSONArray("pages")).first())
        assertEquals(mapOf("lnktree" to listOf("lt-lnktree-site")), groups("lnktree").filterKeys { it == "lnktree" })
        assertEquals("lt-lnktree-site", ids(sections.getValue("lnktree").getJSONArray("pages")).first())
        assertEquals(mapOf("profiles" to listOf("prof-web"), "companies" to listOf("leafy", "nexus", "stark")), groups("prof"))
        assertEquals(listOf("prof-web", "leafy", "nexus", "stark"), ids(sections.getValue("prof").getJSONArray("pages")))
    }

    @Test fun `the six embedded sites open by their https URL, each declared in its own page file`() {
        val want = mapOf("prof" to "leafy" to "https://diegonmarcos.github.io/leafy/", "prof" to "nexus" to "https://diegonmarcos.github.io/nexus/",
            "prof" to "stark" to "https://diegonmarcos.github.io/stark/", "pers" to "astro" to "https://diegonmarcos.github.io/myAstro/",
            "pers/pers-profiles" to "personal" to "https://diegonmarcos.com/", "lnktree" to "lt-lnktree-site" to "https://linktree.diegonmarcos.com/")
        for (entry in want) {
            val (loc, url) = entry.key to entry.value
            val b = page(loc.first, loc.second).getJSONObject(0)
            assertEquals("fragment", b.getString("kind")); assertEquals("web", b.getString("id")); assertEquals(url, b.getString("url"))
        }
    }

    @Test fun `the Achieves store experiences_user is a declared config store, so the backup carries every tab`() {
        val fc = JSONObject(BuroLlcTestData.text("../fleet-private-data/fleet-config.json"))
        assertEquals("config", fc.getJSONObject("stores").getJSONObject(LedgerStore.EXP).getString("class"))
    }
}

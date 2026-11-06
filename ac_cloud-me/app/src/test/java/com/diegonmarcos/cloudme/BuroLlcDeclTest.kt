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

    @Test fun `the bar is Health Experiences Tasks Lnktree Inventory Buro LLC and House, Work, RoadMap and Duties are gone`() {
        val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
        val bar = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("health", "experiences", "tasks", "lnktree", "inventory", "buro", "llc"), bar)
        assertEquals(listOf("Health", "Experiences", "Tasks", "Lnktree", "Inventory", "Buro", "LLC"), bar.map { sections.getValue(it).getString("label") })
        assertFalse("house" in sections); assertFalse("work" in sections); assertFalse("roadmap" in sections); assertFalse("duties" in sections)
        assertEquals(listOf("professional", "leafy", "nexus", "stark", "personal", "social", "health", "dd", "astro", "lnktree-site", "area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front"), ids(sections.getValue("lnktree").getJSONArray("pages")))
        assertEquals(listOf("roadmaps", "backlogs"), ids(sections.getValue("tasks").getJSONArray("pages")))
        assertEquals("tasks", page("tasks", "backlogs").getJSONObject(0).getString("id"))
    }

    @Test fun `Buro follows the owner hierarchy ID&DD, Secrets Web, Secrets CLI, Configs, Financial, Portfolio, Events and Docs is gone`() {
        assertEquals("horizontal_two_rows", sections.getValue("buro").getString("nav_mode"))
        assertEquals(mapOf(
            "iddd" to listOf("ids", "dd", "paycards", "autocomplete", "wallet", "account"),
            "sec-web" to listOf("logins", "totp", "passkeys"),
            "sec-cli" to listOf("ssh", "apitokens", "jwt", "certs"),
            "cfg" to listOf("repos", "android", "desktop", "c3", "backup"),
            "financial" to listOf("accounts", "cards", "statements", "ir", "cashflow", "budgeting", "services"),
            "portfolio" to listOf("assets", "liabilities", "nav", "performance"),
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
        assertEquals(mapOf("income" to listOf("employed", "side-hustles"), "manage" to listOf("assets", "portfolio"), "calendar" to listOf("calendar")), groups("llc"))
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
        for ((s, subject) in mapOf("buro" to "Buro", "llc" to "LLC")) {
            val b = page(s, "calendar").getJSONObject(0)
            assertEquals("fragment", b.getString("kind")); assertEquals("tasks", b.getString("id"))
            assertEquals(subject, b.getString("subject")); assertEquals("extapp:cloud-tasks", b.getString("open"))
        }
    }

    @Test fun `the user data stores are declared in fleet-config so the backup carries them`() {
        val stores = JSONObject(BuroLlcTestData.text("../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")).getJSONObject("stores")
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

    @Test fun `Lnktree is three rows Prof, Pers and Proj with the declared separators`() {
        val me = sections.getValue("lnktree")
        assertEquals("horizontal_two_rows", me.getString("nav_mode"))
        assertEquals(mapOf("prof" to listOf("professional", "leafy", "nexus", "stark"),
            "pers" to listOf("personal", "social", "health", "dd", "astro", "lnktree-site"),
            "proj" to listOf("area-unix", "area-cg", "area-capital", "area-hardtech", "area-lecole42", "area-front")), groups("lnktree"))
        val pages = me.getJSONArray("pages")
        val sep = (0 until pages.length()).map { pages.getJSONObject(it) }.filter { it.optBoolean("separator_before") }.map { it.getString("id") }
        assertEquals(listOf("leafy", "health", "lnktree-site"), sep)
    }

    @Test fun `the five published sites are embedded by their https URL`() {
        val want = mapOf("leafy" to "https://diegonmarcos.github.io/leafy/", "nexus" to "https://diegonmarcos.github.io/nexus/",
            "stark" to "https://diegonmarcos.github.io/stark/", "astro" to "https://diegonmarcos.github.io/myAstro/",
            "lnktree-site" to "https://linktree.diegonmarcos.com/")
        for ((p, url) in want) {
            val b = page("lnktree", p).getJSONObject(0)
            assertEquals("fragment", b.getString("kind")); assertEquals("web", b.getString("id")); assertEquals(url, b.getString("url"))
        }
    }

    @Test fun `the Achieves store experiences_user is a declared config store, so the backup carries every tab`() {
        val fc = JSONObject(BuroLlcTestData.text("../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"))
        assertEquals("config", fc.getJSONObject("stores").getJSONObject(LedgerStore.EXP).getString("class"))
    }
}

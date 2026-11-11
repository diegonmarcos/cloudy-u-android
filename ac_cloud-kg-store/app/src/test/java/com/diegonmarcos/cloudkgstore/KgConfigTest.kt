package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.cloudkgstore.Data.arr
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kg dataset (data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json, served at runtime, #1547) held to build.json::ui, to Cloud Search's declaration and to the fleet declaration, each both ways. */
class KgConfigTest {
    private val kg = KgConfig.parse(Data.kgText)
    private val ui = Data.buildJson.getJSONObject("ui")

    private fun section(id: String): JSONObject = ui.arr("sections") { it }.first { it.getString("id") == id }
    private fun pageIds(id: String): List<String> = section(id).arr("pages") { it.getString("id") }

    @Test fun theBottomNavIsApiScrappedHomeMeCloud() {
        val nav = ui.getJSONArray("bottom_nav")
        assertEquals(listOf("api", "scrapped", "home", "me", "cloud"), (0 until nav.length()).map { nav.getString(it) })
        assertEquals("home", ui.getString("default_section"))
        assertEquals(listOf("API", "Scrapped", "Home", "Me", "Cloud"), listOf("api", "scrapped", "home", "me", "cloud").map { section(it).getString("label") })
    }

    /** #1640 rule 16: the reshape renamed web to API and took Phone off the nav; every page, label and icon of the previous nav is still declared. */
    @Test fun noFieldOfThePreviousNavIsLost() {
        assertEquals(listOf("News", "Media", "Shopping", "Real Estate", "Jobs", "Business"), section("api").arr("pages") { it.getString("label") })
        assertEquals(listOf("web_news", "web_media", "web_shopping", "web_realestate", "web_jobs", "web_business"), pageIds("api"))
        assertEquals(listOf("public"), listOf(section("api").getString("icon")))
        assertEquals(listOf("phone_apps"), pageIds("phone"))
        assertEquals("smartphone", section("phone").getString("icon"))
        assertEquals(listOf("models", "backend", "about"), pageIds("settings"))
        assertEquals("cloud", section("cloud").getString("id"))
        assertEquals(10, pageIds("cloud").size)
    }

    @Test fun scrappedListsTheSharedHeadlessDeclarationByReference() {
        val ref = ui.getJSONObject("scrapped")
        assertEquals("../ac_cloud-browser/build.json", ref.getString("source"))
        val shared = Data.browserJson.getJSONObject("ui").getJSONObject("domains").getJSONArray("headless")
        val ids = (0 until shared.length()).map { shared.getJSONObject(it).getString("id") }
        assertEquals(listOf("cloudflare_browser_run", "cloudy_browser_headless", "steel_browser"), ids)
        val parsed = HeadlessService.parse(shared.toString())
        assertEquals(listOf("Cloudflare Browser Run", "Cloud Headless", "Cloud Stealth"), parsed.map { it.title })
        for (s in parsed) { assertTrue(s.stack.isNotEmpty()); assertNotNull(s.link); assertTrue(s.link!!.url.isNotBlank()) }
        assertEquals("every stack key survives", shared.getJSONObject(0).getJSONObject("stack").length(), parsed[0].stack.size)
    }

    @Test fun meDeclaresTheRepoToCloneAtRuntime() {
        val me = MeRepo.parse(ui.getJSONObject("me").toString())
        assertTrue(me.owner.isNotBlank() && me.repo.isNotBlank())
        assertFalse("no credential in the declaration", ui.getJSONObject("me").has("token"))
    }

    /** #1656 Me sources from the library repo cloned at runtime (HardTech-Chronos today, Projects-Library-KG after #1609), never from baked stub collections. */
    @Test fun meSourcesFromTheLibraryRepoNotBakedCollections() {
        val me = MeRepo.parse(ui.getJSONObject("me").toString())
        assertTrue("the live library repo or its #1609 rename, got ${me.repo}", me.repo.lowercase() in setOf("hardtech-chronos", "projects-library-kg", "project-library-kg"))
        val cwd = java.io.File(System.getProperty("user.dir"))
        val main = java.io.File(if (java.io.File(cwd, "src/main").isDirectory) cwd else java.io.File(cwd, "app"), "src/main")
        val baked = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "json", "html", "xml") }
            .filter { f -> listOf("Science & Math", "Tech & Dev", "Saved collections").any { f.readText().contains(it) } }.map { it.name }.toList()
        assertTrue("no collection data is baked into the APK: $baked", baked.isEmpty())
    }

    @Test fun everyDataPageIsDeclaredByTheNavAndEveryDeclaredPageHasItsData() {
        val navPages = listOf("cloud", "phone", "api").flatMap { pageIds(it) }.toSet()
        assertEquals("kg.json::pages and build.json::ui (cloud, phone, api) name the same pages", navPages, kg.pages.keys)
    }

    @Test fun cloudIsTwoRowsOfTwoGroups() {
        val cloud = section("cloud")
        assertEquals("horizontal_two_rows", cloud.getString("nav_mode"))
        val groups = cloud.arr("groups") { it }
        assertEquals(listOf("Application", "Repo"), groups.map { it.getString("label") })
        val pages = cloud.arr("pages") { it.getString("id") to it.getString("label") }.toMap()
        fun labels(g: JSONObject) = g.getJSONArray("pages").let { a -> (0 until a.length()).map { pages.getValue(a.getString(it)) } }
        assertEquals(listOf("Binary", "Web/Debug", "MCP", "API Data", "API Debug"), labels(groups[0]))
        assertEquals(listOf("Code", "CGC", "MCP", "API Semantic", "API GraphRAG"), labels(groups[1]))
        assertEquals(pages.keys, groups.flatMap { g -> g.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getString(it) } } }.toSet())
    }

    @Test fun apiIsSixPagesAndPhoneIsTheAppsIndex() {
        assertEquals(listOf("News", "Media", "Shopping", "Real Estate", "Jobs", "Business"), section("api").arr("pages") { it.getString("label") })
        assertEquals(listOf("Apps"), section("phone").arr("pages") { it.getString("label") })
        assertEquals(KgConfig.KIND_PHONE_APPS, kg.page("phone_apps")?.kind)
    }

    @Test fun everyIconIsNamedAndTheIdsAreUniqueAcrossTheApp() {
        val ids = ArrayList<String>()
        for (s in ui.arr("sections") { it }) {
            ids += s.getString("id")
            for (p in (s.optJSONArray("pages") ?: org.json.JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }) { ids += p.getString("id"); assertTrue("page ${p.getString("id")} has an icon", p.getString("icon").isNotBlank()) }
        }
        assertEquals("section and page ids are unique", ids.size, ids.toSet().size)
    }

    @Test fun theWebPagesReadVerticalsCloudSearchDeclares() {
        val cfg = SearchConfig.parse(Data.searchJson.toString())
        val used = kg.pages.values.filter { it.kind == KgConfig.KIND_VERTICALS }
        assertEquals(setOf("web_media", "web_shopping", "web_realestate", "web_jobs", "web_business"), used.map { it.id }.toSet())
        for (p in used) for (v in p.verticals) {
            val vertical = cfg.vertical(v)
            assertNotNull("${p.id} names vertical $v, which Cloud Search does not declare", vertical)
            assertTrue("$v has a listing subpage", vertical!!.subpages.any { cfg.subpage(it)?.kind in setOf("listing", "things") })
        }
        assertTrue("News reads Cloud Search's declared news pages", cfg.newsPages.isNotEmpty() && kg.page("web_news")?.kind == KgConfig.KIND_NEWS)
    }

    @Test fun theRepoPagesReadTheCodeIndexesCloudSearchDeclares() {
        val cfg = SearchConfig.parse(Data.searchJson.toString())
        val ix = cfg.cgc!!.indexes
        assertEquals(setOf("cgc-pub-mcp", "cgc-pvt-mcp"), ix.map { it.service }.toSet())
        assertEquals(ix.map { it.service }.toSet(), kg.page("repo_mcp")!!.services.toSet())
        assertNotNull("Cloud Search declares the code search the Code page opens", cfg.cloud)
    }

    @Test fun everyFleetServiceAPageNamesIsInTheFleetDeclaration() {
        val declared = Data.accountJson.getJSONObject("fleet_endpoints").arr("services") { it.getString("id") }.toSet()
        val named = kg.pages.values.flatMap { it.services + listOfNotNull(it.service.takeIf { s -> s.isNotBlank() }, it.rerank?.service) }.toSet()
        assertTrue("the pages name fleet services", named.isNotEmpty())
        assertEquals("named but not declared", emptySet<String>(), named - declared)
    }

    @Test fun anEmptyServiceListCarriesTheHonestSentence() {
        for (p in kg.pages.values.filter { it.kind == KgConfig.KIND_MCP && it.services.isEmpty() }) {
            assertTrue("${p.id} says what is missing", p.missing.length > 40)
        }
        assertTrue("the app MCP page is the one with nothing declared (#1211)", kg.page("app_mcp")!!.services.isEmpty())
        assertTrue(kg.page("app_mcp")!!.missing.contains("#1211"))
    }

    @Test fun theConsolesAreGetAndPost() {
        for (id in listOf("app_api_data", "app_api_debug")) {
            val p = kg.page(id)!!
            assertEquals(listOf("GET", "POST"), p.methods)
            assertFalse(p.services.isEmpty())
        }
        assertEquals(listOf("c3-infra-api"), kg.page("app_api_debug")!!.services)
    }

    @Test fun anUnknownKindIsRefused() {
        val bad = JSONObject(Data.kgText)
        bad.getJSONObject("pages").getJSONObject("phone_apps").put("kind", "guess")
        assertTrue(runCatching { KgConfig.parse(bad.toString()) }.isFailure)
    }

    @Test fun theComposerDeclarationEnablesTheSharedControls() {
        val c = Data.composer
        assertEquals("cloud-kg-store", c.getString("app"))
        val controls = c.getJSONArray("controls")
        assertEquals(listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more"), (0 until controls.length()).map { controls.getString(it) })
    }

    @Test fun theAssistantIsDeclaredData() {
        assertEquals("ask", kg.ask.page)
        assertTrue(kg.ask.suggestions.size >= 3)
        assertTrue(kg.ask.system.isNotBlank())
    }
}

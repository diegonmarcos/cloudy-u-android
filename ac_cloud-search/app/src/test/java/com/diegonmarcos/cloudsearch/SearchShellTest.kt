package com.diegonmarcos.cloudsearch

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.debugapi.SearchDebugApi
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags
import com.diegonmarcos.cloudsearch.ui.AgentTags as AT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UI smoke test PER VERTICAL AND SUBPAGE: the real shell composed under Robolectric against a
 * fake network, every declared vertical reached through the app's own bottom nav (#797) and every
 * one of its declared subpages composed. Every id comes from the declaration, none is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchShellTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    /** One picture from Openverse, a refusal from everything else; records what was asked. */
    private class FakeHttp : Http {
        val asked = mutableListOf<String>()
        val headersAsked = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url; headersAsked += headers }
            return when {
                "api.openverse.org" in url -> Http.Response(200,
                    """{"results":[{"id":"img-1","title":"Orange Cat","creator":"Ada","provider":"flickr","url":"https://example.org/cat.jpg","foreign_landing_url":"https://example.org/cat","license":"by","license_version":"2.0"}]}""")
                else -> Http.Response(503, "")
            }
        }
        val posted = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url; posted += Triple(url, headers, body) }
            if (headers["X-Agent-Mode"] != null) return Http.Response(200, """{"choices":[{"message":{"role":"assistant","content":"harness answer"}}]}""")
            return Http.Response(503, "")
        }
    }

    private val http = FakeHttp()
    private lateinit var services: Services
    private lateinit var state: SearchState
    private lateinit var savedReader: (android.content.Context, String) -> Account.Token
    private val savedFleetRead = com.diegonmarcos.cloudsearch.data.FleetBearer.reader

    @Before fun offline() {
        services = Services(RuntimeEnvironment.getApplication(), http)
        Services.install(services)
        state = SearchState(services)
        savedReader = Account.reader
        Account.reader = { _, _ -> Account.Token(null, "no token in this test") }
    }

    @After fun online() {
        com.diegonmarcos.cloudsearch.data.FleetBearer.reader = savedFleetRead
        Account.reader = savedReader
        Services.install(null)
    }

    private fun launch() = compose.setContent { SearchShell(state) }

    /** #913b a vertical through the app's own nav: the assistant is the island's Chat; every other one a pill of Searches' strip. */
    private fun openVertical(id: String) {
        if (id == state.chatVertical) compose.goTo(state, "chat")
        else {
            compose.goTo(state, state.sectionOf(id))
            compose.waitForIdle()
            // a one-page section (Image, Videos) draws no second row
            if (com.diegonmarcos.cloudsearch.ui.NAV.section(state.sectionOf(id))!!.pages.size > 1) compose.onNodeWithTag(Tags.subpage(id)).performScrollTo().performClick()
        }
        compose.waitForIdle()
    }

    // A timeout names the tag, the vertical and the subpage it was waiting on: run 37474747245 (arm64
    // only, Release) reported nothing but "Condition still not satisfied after 60000 ms".
    private fun waitFor(tag: String) = try {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("no node tagged '$tag' within 60 s (vertical=${state.vertical})", e)
    }

    @Test fun everyVerticalAndSubpageIsReachableAndComposes() {
        launch()
        val cfg = Decl.config
        for (v in com.diegonmarcos.cloudsearch.ui.appVerticals(cfg)) {
            openVertical(v.id)
            assertEquals(v.id, state.vertical)
            // A vertical with one subpage draws no sub-nav (every remaining vertical has one).
            if (v.subpages.first() !in cfg.verticals.map { it.id } + NAV_PAGES("chat"))
                assertEquals(v.subpages.size > 1, compose.onAllNodesWithTag(Tags.subpage(state.navId(v, v.subpages.first()))).fetchSemanticsNodes().isNotEmpty())
            if (v.subpages.size > 1) compose.onNodeWithTag(Tags.subpage(state.navId(v, v.subpages.last()))).performClick()
            for (sub in v.subpages) {
                compose.runOnIdle { state.showSubpage(v, sub) }
                // Bounded wait, not an instant check: a heavy page (a heavy page
                // while composing) was not yet composed on one runner (run 37036021906) and was on
                // the other (run 37403502266: arm64 timed out at 10 s while the same step passed on x86_64, with
                // PIT running beside it). A page that never appears still fails here, after 60 s.
                waitFor(Tags.page(cfg.subpage(sub)!!.kind))
                assertEquals(sub, state.subpageOf(v))
            }
        }
    }

    @Test fun imageListingShowsWhatTheSourceReturned() {
        state.listing("image").let { it.q = "cat"; it.stale = true }
        launch()
        openVertical("image")
        waitFor(Tags.card("openverse-images:img-1"))
        compose.onNodeWithTag(Tags.card("openverse-images:img-1")).assertTextContains("Orange Cat", substring = true)
        assertTrue(synchronized(http.asked) { http.asked.any { "api.openverse.org" in it && "q=cat" in it } })
    }

    @Test fun chatWithoutATokenSaysWhyAndSendsNothing() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        compose.onNodeWithTag(Tags.CHAT_INPUT).performTextInput("What is the Grundfreibetrag?")
        compose.onNodeWithTag(Tags.CHAT_SEND).performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("no token in this test", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("nothing is posted without a token", synchronized(http.asked) { http.asked.any { it == Decl.config.ai.chatUrl } })
        assertEquals("What is the Grundfreibetrag?", services.sessions.all().single().messages.single().content)
    }

    /**
     * The shared composer (libs:ai): (+), Task, Agent, Model, Effort, Perms, MCP and More sit under the input in that order; picking a task greys
     * Agent and Model and sets them from the task mapping; clearing the task gives them back; the Model button opens the catalogue with the five subpages.
     */
    @Test fun theComposerRowIsTheSharedOneAndATaskGreysAgentAndModel() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        val ai = com.diegonmarcos.superapp.ai.AiTags
        val order = listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more")
        val lefts = order.map { compose.onNodeWithTag(ai.ctl(it)).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x }
        assertEquals("(+), Task, Agent, Model, Effort, Perms, MCP, (More), left to right", lefts.sorted(), lefts)
        compose.onNodeWithTag(ai.ctl("agent")).assertIsEnabled()
        compose.onNodeWithTag(ai.ctl("model")).assertIsEnabled()

        compose.onNodeWithTag(ai.ctl("task")).performClick()
        waitFor(ai.task("translate"))
        compose.onNodeWithTag(ai.task("translate")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ai.ctl("agent")).assertIsNotEnabled()
        compose.onNodeWithTag(ai.ctl("model")).assertIsNotEnabled()
        assertEquals("openrouter", state.chat.agent)
        assertEquals("mistralai/mistral-small-3.2-24b-instruct", state.chat.model)

        compose.onNodeWithTag(ai.ctl("task")).performClick()
        waitFor(ai.TASK_NONE)
        compose.onNodeWithTag(ai.TASK_NONE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ai.ctl("agent")).assertIsEnabled()
        compose.onNodeWithTag(ai.ctl("model")).assertIsEnabled()

        compose.onNodeWithTag(ai.ctl("model")).performClick()
        for (tab in listOf("frontier", "core", "specialist", "medium", "small")) waitFor(ai.catTab(tab))
    }

    /** The whole config bar collapses to one compact chip behind the chevron, and the state is remembered per app. */
    @Test fun theConfigBarCollapsesToOneChipAndRemembersIt() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        val ai = com.diegonmarcos.superapp.ai.AiTags
        compose.onNodeWithTag(ai.ctl("model")).assertExists()
        compose.onNodeWithTag(ai.BAR_TOGGLE).performClick()
        compose.waitForIdle()
        assertTrue("the chip row is gone", compose.onAllNodesWithTag(ai.ctl("model")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(ai.BAR_COMPACT).assertExists()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("ai_composer", android.content.Context.MODE_PRIVATE)
        assertTrue("remembered for this app", prefs.getBoolean("ai_composer_collapsed.cloud-search", false))
        compose.onNodeWithTag(ai.BAR_TOGGLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ai.ctl("model")).assertExists()
        assertTrue(compose.onAllNodesWithTag(ai.BAR_COMPACT).fetchSemanticsNodes().isEmpty())
        assertFalse(prefs.getBoolean("ai_composer_collapsed.cloud-search", true))
    }

    /** (+) offers New chat; Effort lists its levels; More is grouped under headers and its Usage item says so when nothing was reported. */
    @Test fun plusEffortAndMoreOpenTheirDeclaredContents() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        val ai = com.diegonmarcos.superapp.ai.AiTags
        compose.onNodeWithTag(ai.ctl("plus")).performClick()
        waitFor(ai.NEW_CHAT)
        compose.onNodeWithTag(ai.NEW_CHAT).performClick()
        compose.onNodeWithTag(ai.ctl("effort")).performClick()
        for (l in listOf("auto", "low", "medium", "high", "max")) waitFor(ai.effort(l))
        compose.onNodeWithTag(ai.effort("high")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ai.ctl("more")).performScrollTo().performClick()
        for (g in listOf("usage", "optimisation", "skills", "session", "persona", "help")) waitFor("ai_more_group_$g")
        for (id in listOf("usage", "skills", "memory", "export", "retry", "undo", "system", "shortcuts", "about")) waitFor(ai.more(id))
        for (id in listOf("headroom", "rtk", "caveman", "principles")) waitFor(ai.morePlugin(id))
        compose.onNodeWithTag(ai.more("usage")).performClick()
        waitFor(ai.MORE_DIALOG)
    }

    /**
     * The chat's Agent field lists the shared descriptor's agents (DeepSeek Harness with its real capabilities);
     * picking it routes the send through the fleet agent gateway with X-Agent-Mode: deepseek and a DeepSeek model,
     * and needs no OpenRouter token on the phone.
     */
    @Test fun deepSeekHarnessIsPickedFromTheSharedDescriptorAndRoutesThroughTheGateway() {
        launch()
        openVertical("search")
        waitFor(Tags.CHAT_INPUT)
        compose.onNodeWithTag(com.diegonmarcos.superapp.ai.AiTags.AGENT).performClick()
        compose.waitForIdle()
        val tag = com.diegonmarcos.superapp.ai.AiTags.agent("deepseek")
        compose.onNodeWithTag(tag).assertTextContains("DeepSeek Harness", substring = true)
        compose.onNodeWithTag(tag).assertTextContains("tools (MCP) · reasoning · text only", substring = true)
        listOf("claude", "hermes", "goose").forEach { compose.onNodeWithTag(com.diegonmarcos.superapp.ai.AiTags.agent(it)).assertExists() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        assertEquals("deepseek", services.prefs.agent)
        compose.onNodeWithTag(Tags.CHAT_INPUT).performTextInput("Plan my week")
        compose.onNodeWithTag(Tags.CHAT_SEND).performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("harness answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val (url, headers, body) = synchronized(http.asked) { http.posted.single() }
        assertEquals(BuildConfig.AGENT_GATEWAY_URL + "/v1/chat/completions", url)
        assertEquals("deepseek", headers["X-Agent-Mode"])
        assertFalse("no OpenRouter token goes to the gateway", headers.containsKey("Authorization"))
        assertTrue(org.json.JSONObject(body).getString("model").startsWith("deepseek/"))
    }

    /**
     * #1161 the shared nav pattern: the first row (no hamburger, breadcrumb title, AI, Profile top-right, Configs), the bottom nav of
     * the seven declared sections (each item shows its label at rest) and the second row of the section's pages, with the
     * search field of the page still there and typeable.
     */
    @Test fun theSharedNavPatternRendersWithTheSearchFieldInside() {
        launch()
        assertEquals(com.diegonmarcos.superapp.bottomnav.NavStyle.Fleet, com.diegonmarcos.cloudsearch.ui.NAV.style)
        waitFor(Tags.nav("scrapers"))
        compose.onNodeWithTag(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.ROW).assertExists()
        for (tag in listOf(Tags.ISLAND, Tags.PROFILE, Tags.CONFIGS)) compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithTag(com.diegonmarcos.superapp.bottomnav.TopBarTags.MENU).assertDoesNotExist()
        val ids = com.diegonmarcos.cloudsearch.ui.NAV.bottomNav
        assertEquals(listOf("scrapers", "research", "cgc", "image", "video", "models3d"), ids)
        compose.onNodeWithTag(com.diegonmarcos.superapp.navbarbottom.BottomTags.BAR).assertExists()
        for (id in ids) compose.onNodeWithTag(Tags.nav(id)).assertExists()
        for (id in listOf("news", "shopping", "brokers")) compose.onNodeWithTag(Tags.nav(id)).assertDoesNotExist()
        openVertical("movies")
        waitFor(Tags.WEB_STRIP)
        for (v in MEDIA_PAGES) compose.onNodeWithTag(Tags.subpage(v)).assertExists()
        waitFor(Tags.SEARCH_BOX)
        compose.onNodeWithTag(Tags.SEARCH_BOX).performTextInput("Matrix")
        compose.onNodeWithTag(Tags.SEARCH_BOX).assertTextContains("Matrix", substring = true)
    }

    /** #1472 the parked Media section holds the two verticals that are not a tab of their own. */
    private val MEDIA_PAGES = listOf("movies", "books")
    private val SEARCH_PAGES get() = listOf(state.chatVertical, "engines", "apps", "messages", "code")

    /** The six tabs route, and each shows its second row; the parked sections (off the island, opened through the state) keep theirs. */
    @Test fun theSixTabsRouteAndKeepTheirTopTabs() {
        launch()
        val tabs = mapOf(
            "scrapers" to listOf("sitefiles", "apify", "cloudflare", "selfhosted"),
            "image" to emptyList(),
            "video" to emptyList(),
            "models3d" to emptyList(),
            "research" to listOf("reports", "models", "agenthub"),
            "cgc" to listOf("cloud_semantic", "cloud_graph", "me_semantic", "me_graph"),
            "chat" to SEARCH_PAGES,
            "media" to MEDIA_PAGES,
        )
        for ((section, pages) in tabs) {
            compose.goTo(state, section)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(section, state.section) }
            for (id in pages) compose.onNodeWithTag(Tags.subpage(id)).assertExists()
        }
        compose.goTo(state, "media")
        compose.waitForIdle()
        waitFor(Tags.WEB_STRIP)
        // #1472 News, Shopping and Brokers are not sections any more: the declaration names none of them
        for (gone in listOf("news", "shopping", "brokers")) assertEquals(null, com.diegonmarcos.cloudsearch.ui.NAV.section(gone))
    }

    @Test fun themeToggleFlipsAndIsRemembered() {
        launch()
        val before = state.dark
        compose.onNodeWithTag(Tags.CONFIGS).performClick()
        waitFor(Tags.THEME)
        compose.onNodeWithTag(Tags.THEME).performClick()
        compose.runOnIdle { assertEquals(!before, state.dark) }
        assertEquals(!before, services.prefs.dark)
    }

    @Test fun theTitleDropdownOpensEveryCategoryAndSavedItems() {
        launch()
        compose.onNodeWithTag(Tags.ISLAND).performClick()
        waitFor(Tags.drawer("movies"))
        for (gone in listOf("jobs", "house", "groceries", "things")) compose.onNodeWithTag(Tags.drawer(gone)).assertDoesNotExist()
        compose.onNodeWithTag(Tags.drawer("movies")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("movies", state.vertical); assertEquals(null, state.menu) }
        compose.onNodeWithTag(Tags.ISLAND).performClick()
        waitFor(Tags.drawer("saved"))
        compose.onNodeWithTag(Tags.drawer("saved")).performScrollTo().performClick()
        waitFor(Tags.SAVED)
        // the title path ends with the page; while a search still runs the island says Searching, so wait for the settled title
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(Tags.ISLAND).fetchSemanticsNodes().any { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.contains("Saved Items") } == true } }
        compose.onNodeWithTag(Tags.ISLAND).assertTextContains("Saved Items", substring = true)
        openVertical("image")
        compose.runOnIdle { assertFalse(state.saved); assertEquals("image", state.vertical) }
    }

    @Test fun extensiveFiltersApplyTheChosenRange() {
        launch()
        openVertical("image")
        waitFor(Tags.MORE_FILTERS)
        compose.onNodeWithTag(Tags.MORE_FILTERS).performClick()
        waitFor(Tags.APPLY_FILTERS)
        compose.onNodeWithTag(Tags.option("min")).performTextInput("5")
        compose.onNodeWithTag(Tags.APPLY_FILTERS).performClick()
        compose.runOnIdle {
            assertEquals(5.0, state.filtersOf("image").min!!, 0.0)
            assertEquals(null, state.menu)
        }
    }

    /**
     * #803 the owner's renderUnifiedSearchChat: the Search tab is ONE page holding both the engine
     * boxes and the AI chat, not a web page and a chat page. Which vertical that is comes from the
     * declaration (the one whose subpage is of kind `assistant`).
     */
    @Test fun theSearchTabIsOnePageWithTheEnginesAndTheChat() {
        launch()
        val cfg = Decl.config
        val app = RuntimeEnvironment.getApplication()
        val search = cfg.verticals.single { v -> v.subpages.any { cfg.subpage(it)?.kind == "assistant" } }
        openVertical(search.id)
        waitFor(Tags.page("assistant"))
        // One page: the assistant's is the only page composed, and no sub-nav offers another.
        for (kind in cfg.subpages.map { it.kind }.toSet())
            assertEquals("pages of kind $kind", if (kind == "assistant") 1 else 0, compose.onAllNodesWithTag(Tags.page(kind)).fetchSemanticsNodes().size)
        for (sub in cfg.subpages)
            assertTrue("no sub-nav tab ${sub.id}", compose.onAllNodesWithTag(Tags.subpage(sub.id)).fetchSemanticsNodes().isEmpty())

        // Both parts at once, inside that one page, in the mockup's order of appearance.
        val onPage = hasAnyAncestor(hasTestTag(Tags.page("assistant")))
        val hello = com.diegonmarcos.superapp.ai.AiStrings().hello
        val top = { m: SemanticsMatcher -> compose.onNode(m and onPage).fetchSemanticsNode().boundsInRoot.top }
        val greeting = top(hasText(hello))
        val engines = cfg.engines.map { top(hasTestTag(Tags.engine(it.id))) }
        assertTrue("Sessions sits above the greeting", top(hasTestTag(Tags.SESSIONS)) < greeting)
        assertTrue("the composer's icon row sits under the input, Cloud Code's layout", top(hasTestTag(Tags.MODEL)) > top(hasTestTag(Tags.CHAT_INPUT)))
        assertTrue("the greeting sits above the engine boxes", greeting < engines.first())
        assertEquals("the engine boxes run in declared order", engines.sorted(), engines)
        assertTrue("the chat input sits below the last engine box", top(hasTestTag(Tags.CHAT_INPUT)) > engines.last())
        for (id in listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more")) compose.onNode(hasTestTag(com.diegonmarcos.superapp.ai.AiTags.ctl(id)) and onPage).assertExists()
        compose.onNode(hasTestTag(Tags.CHAT_SEND) and onPage).assertExists()

        // An engine box opens that engine's own results in cloud-browser.
        val shadow = shadowOf(app)
        while (shadow.nextStartedActivity != null) Unit
        val first = cfg.engines.first()
        compose.onNodeWithTag(Tags.engine(first.id)).performTextInput("kotlin jobs")
        compose.onNodeWithTag(Tags.engine(first.id)).performImeAction()
        val opened = shadow.nextStartedActivity
        assertEquals(Templates.fill(first.url, "kotlin jobs", cfg.city(state.city)), opened?.dataString)
        assertEquals(BuildConfig.BROWSER_PACKAGE, opened?.`package`)

        // Sending a message hides the initial view; the chat goes on in the same page.
        compose.onNodeWithTag(Tags.CHAT_INPUT).performTextInput("Hello")
        compose.onNodeWithTag(Tags.CHAT_SEND).performClick()
        compose.waitUntil(60_000) { !state.chat.sending && compose.onAllNodes(hasText("Hello") and onPage).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the greeting is gone", compose.onAllNodesWithText(hello).fetchSemanticsNodes().isEmpty())
        for (e in cfg.engines)
            assertTrue("engine box ${e.id} is gone", compose.onAllNodesWithTag(Tags.engine(e.id)).fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodesWithTag(Tags.page("assistant")).fetchSemanticsNodes().size)
        compose.onNode(hasTestTag(Tags.CHAT_INPUT) and onPage).assertExists()

        // A new chat brings the initial view back; Sessions opens its menu.
        compose.onNodeWithTag(Tags.SESSIONS).performClick()
        compose.runOnIdle { assertEquals(com.diegonmarcos.cloudsearch.ui.Menu.SESSIONS, state.menu) }
        compose.onNodeWithTag(Tags.DRAWER_NEW).performClick()
        for (e in cfg.engines) waitFor(Tags.engine(e.id))
    }

    @Test fun debugRoutesAnswerFromTheSameEngine() {
        val v = SearchDebugApi.verticals(services)
        assertEquals(com.diegonmarcos.cloudsearch.ui.appVerticals(Decl.config).size, v.getJSONArray("verticals").length())
        assertEquals(5, v.getJSONArray("verticals").length())
        assertFalse(SearchDebugApi.query(services, mapOf("v" to "jobs")).getBoolean("ok"))
        val q = SearchDebugApi.query(services, mapOf("v" to "image", "q" to "cat"))
        assertTrue(q.getBoolean("ok"))
        assertEquals("Orange Cat", q.getJSONArray("listings").getJSONObject(0).getString("title"))
        assertFalse(SearchDebugApi.query(services, mapOf("v" to "nope")).getBoolean("ok"))
    }

    // ── #913 the island, Agents and Reports; #1472 Scrappers, Research, CGC Search, Image, Videos, 3D Models ──────
    @Test fun theIslandHoldsTheSixTabsAndEachOpens() {
        launch()
        assertEquals(listOf("scrapers", "research", "cgc", "image", "video", "models3d"), NAV_IDS)
        assertEquals(listOf("Scrappers", "Research", "CGC Search", "Image", "Videos", "3D Models"), NAV_IDS.map { com.diegonmarcos.cloudsearch.ui.NAV.section(it)!!.label })
        for (id in NAV_IDS) {
            compose.goTo(state, id)
            compose.waitForIdle()
            assertEquals(id, state.section)
        }
        // Media (parked) holds Movies and Books; Search (parked) is the assistant.
        compose.goTo(state, "media")
        waitFor(Tags.WEB_STRIP)
        assertEquals(MEDIA_PAGES, NAV_PAGES("media"))
        for (v in MEDIA_PAGES) compose.onNodeWithTag(Tags.subpage(v)).assertExists()
        openVertical("books")
        compose.runOnIdle { assertEquals("books", state.vertical); assertEquals("media", state.section) }
        compose.goTo(state, "chat")
        waitFor(Tags.page("assistant"))
        assertEquals(state.chatVertical, state.vertical)
    }

    /** #1197 Search > Engines: every declared major engine is a card; a tap opens that engine's results for the typed query in cloud-browser. */
    @Test fun theEnginesPageOpensAMajorEngineWithTheTypedQuery() {
        launch()
        val cfg = Decl.config
        compose.goTo(state, "chat")
        compose.onNodeWithTag(Tags.subpage("engines")).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.EnginesTags.QUERY)
        assertTrue("the list declares the engines the owner named", listOf("google", "bing", "duckduckgo", "brave", "qwant", "kagi", "perplexity").all { id -> cfg.majorEngines.any { it.id == id } })
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.EnginesTags.QUERY).performTextInput("kotlin jobs")
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        while (shadow.nextStartedActivity != null) Unit
        val first = cfg.majorEngines.first()
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.EnginesTags.engine(first.id)).performClick()
        val opened = shadow.nextStartedActivity
        assertEquals(Templates.fill(first.url, "kotlin jobs", cfg.city(state.city)), opened?.dataString)
        assertEquals(BuildConfig.BROWSER_PACKAGE, opened?.`package`)
    }

    /** #1184 the Agents pages are Research's third page. */
    private fun openAgents() {
        compose.onNodeWithTag(Tags.nav("research")).performClick()
        compose.onNodeWithTag(Tags.subpage("agenthub")).performClick()
        compose.waitForIdle()
    }

    private val NAV_IDS get() = com.diegonmarcos.cloudsearch.ui.NAV.bottomNav
    /** #1199 a page id names one page in the whole app: a second owner made the title path resolve to the first ("Jobs" for House's Listing). */
    @Test fun everyNavPageIdIsUniqueInTheApp() {
        val nav = com.diegonmarcos.cloudsearch.ui.NAV
        val ids = mutableListOf<String>()
        fun walk(pages: List<com.diegonmarcos.superapp.bottomnav.NavPage>) { pages.forEach { ids += it.id; walk(it.pages) } }
        nav.sections.forEach { walk(it.pages) }
        assertEquals("duplicate page ids: " + ids.groupBy { it }.filter { it.value.size > 1 }.keys, ids.size, ids.toSet().size)
        for (gone in listOf("news", "shopping", "brokers")) assertEquals(null, nav.section(gone))
    }

    /** #1472 the Scrappers and 3D Models pages draw the rows build.json::sample_pages declares. */
    @Test fun theSampleTabsDrawTheirDeclaredRows() {
        launch()
        for ((section, page) in listOf("scrapers" to "sitefiles", "scrapers" to "apify", "scrapers" to "cloudflare", "scrapers" to "selfhosted", "models3d" to "models3d")) {
            compose.goTo(state, section)
            compose.runOnIdle { state.samplePage[section] = page }
            waitFor(com.diegonmarcos.cloudsearch.ui.SampleTags.page(page))
            val rows = com.diegonmarcos.cloudsearch.ui.SamplePages.page(page)!!.rows
            assert(rows.isNotEmpty())
            for (i in rows.indices) compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.SampleTags.row(i)).assertExists()
        }
    }

    private fun NAV_PAGES(section: String) = com.diegonmarcos.cloudsearch.ui.NAV.section(section)!!.pages.map { it.id }

    /** A click through the node's own action: a node scrolled to the list's edge may sit under the island. */
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Until the agent's run has finished and [n] runs are stored; a timeout says what the run reported. */
    private fun waitRuns(a: com.diegonmarcos.cloudsearch.data.AgentService, n: Int) = try {
        compose.waitUntil(60_000) { state.agentsModel.running == null && a.runs.all().size >= n }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("runs=${a.runs.all().size} want $n, running=${state.agentsModel.running}, status='${state.agentsModel.status}'", e)
    }

    /** A node that may sit below the fold of a lazy list: scroll the list to it first. */
    private fun scrollTo(list: String, tag: String) {
        waitFor(list)
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        waitFor(tag)
    }

    private class FakeMail : com.diegonmarcos.cloudsearch.core.agents.MailSource {
        override fun messages(from: String, subject: String, sinceMs: Long, limit: Int) = listOf(
            com.diegonmarcos.cloudsearch.core.agents.MailHeader("m1", "acc", "Neue Angebote", "WG", "info@wg-gesucht.de", "2026-10-07T10:00:00Z", 1L, false),
        )
        override fun body(accountId: String, id: String) = com.diegonmarcos.cloudsearch.core.agents.MailBody(
            "", """<a href="https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Mitte.11223344.html?x=1">Zimmer</a>""", false)
    }

    private class FakePages : com.diegonmarcos.cloudsearch.core.agents.PageSource {
        override fun text(url: String, maxChars: Int) = com.diegonmarcos.cloudsearch.core.agents.PageText(true, url, "Helles Zimmer", "Ruhige WG sucht Mitbewohner.", false, "")
    }

    private fun installAgents(): com.diegonmarcos.cloudsearch.data.AgentService {
        val a = com.diegonmarcos.cloudsearch.data.AgentService(
            RuntimeEnvironment.getApplication(), http, services.cfg, services.cfg.agents!!, services.models, "p.mail", "p.browser",
            mailOverride = FakeMail(), pagesOverride = FakePages(),
            llmOverride = object : com.diegonmarcos.cloudsearch.core.agents.Llm {
                override fun complete(req: com.diegonmarcos.cloudsearch.core.agents.LlmRequest) = com.diegonmarcos.cloudsearch.core.agents.LlmReply("Ich koche gern.", null, 10, 5, 0.0001)
            },
        )
        a.prefs.setProfile("name", "Ada Test"); a.prefs.setProfile("about", "Ich arbeite als Entwicklerin.")
        services.installAgents(a)
        return a
    }

    @Test fun anAgentRunDraftsForReviewAndNothingIsSent() {
        val a = installAgents()
        launch()
        openAgents()
        scrollTo(AT.LIST, AT.row("rs_house_rental"))
        click(AT.row("rs_house_rental"))
        waitFor(AT.run("rs_house_rental"))
        compose.onNodeWithTag(AT.notice("rs_house_rental")).assertExists()
        click(AT.run("rs_house_rental"))
        waitRuns(a, 1)
        scrollTo(AT.screen("rs_house_rental"), AT.result("11223344"))
        scrollTo(AT.screen("rs_house_rental"), AT.copy("11223344"))
        scrollTo(AT.screen("rs_house_rental"), AT.report("11223344"))
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.report("11223344")).assertTextContains("Ich koche gern.", substring = true)
        scrollTo(AT.screen("rs_house_rental"), AT.open("11223344"))
        // The review buttons: copy puts the message on the clipboard, open hands the listing to Cloud Browser.
        click(com.diegonmarcos.cloudsearch.ui.AgentTags.copy("11223344"))
        val app = RuntimeEnvironment.getApplication()
        val clip = (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        assertTrue(clip!!.getItemAt(0).text.toString().contains("Ada Test"))
        val shadow = shadowOf(app)
        while (shadow.nextStartedActivity != null) Unit
        click(com.diegonmarcos.cloudsearch.ui.AgentTags.open("11223344"))
        val opened = shadow.nextStartedActivity!!
        assertEquals(Decl.config.agents!!.browser.openAction, opened.action)
        assertEquals("https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Mitte.11223344.html", opened.getStringExtra("url"))
        assertEquals("House search", opened.getStringExtra("group"))
        // Nothing was posted anywhere, and nothing but a view of the page was started.
        assertTrue(synchronized(http.asked) { http.asked.none { it == Decl.config.ai.chatUrl } })
        assertEquals(1, a.runs.all().size)
        assertEquals(1, a.reports.newestFirst().single().items.size)
        // Scrolling the page hid every bar (scroll-hide, on by default: they stay away until an upward scroll): switch it off for the rest of this walk.
        com.diegonmarcos.superapp.bottomnav.NavUiPrefs.of(RuntimeEnvironment.getApplication()).setScrollHide(false)
        compose.waitForIdle()
        // Reports lists it, newest first, and opens the detail.
        compose.onNodeWithTag(Tags.nav("research")).performClick()
        compose.onNodeWithTag(Tags.subpage("reports")).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.report("row_" + a.reports.newestFirst().single().id))
        compose.onNodeWithTag(com.diegonmarcos.cloudsearch.ui.AgentTags.report("row_" + a.reports.newestFirst().single().id)).performClick()
        waitFor(com.diegonmarcos.cloudsearch.ui.AgentTags.copy("11223344"))
    }

    @Test fun agentsSubSectionsAreReachable() {
        installAgents()
        launch()
        openAgents()
        for (p in listOf("runs", "templates", "settings", "agents")) {
            compose.onNodeWithTag(Tags.subpage(p)).performClick()
            waitFor(Tags.page("agents_$p"))
        }
    }

    /** #913b the Cloud page's three searches moved to Chat's strip, beside Search; each still opens. */
    @Test fun theFleetSearchesAreReachableOnChatsStrip() {
        installAgents()
        launch()
        compose.goTo(state, "chat")
        waitFor(Tags.CHAT_STRIP)
        assertEquals(SEARCH_PAGES, NAV_PAGES("chat"))
        for (p in listOf("apps", "messages", "code")) {
            compose.onNodeWithTag(Tags.subpage(p)).performClick()
            waitFor(Tags.page("cloud_$p"))
            compose.runOnIdle { assertEquals("chat", state.section); assertEquals(p, state.chatPage) }
        }
        compose.onNodeWithTag(Tags.subpage(state.chatVertical)).performClick()
        waitFor(Tags.page("assistant"))
    }

    /** #937 + #913b a query from outside still lands on Chat › Search, whichever page was showing. */
    @Test fun anOutsideQueryLandsOnChatSearchFromAnyPage() {
        installAgents()
        launch()
        compose.goTo(state, "chat")
        compose.onNodeWithTag(Tags.subpage("messages")).performClick()
        waitFor(Tags.page("cloud_messages"))
        openAgents()
        compose.runOnIdle { state.ask("kotlin jobs berlin", kotlinx.coroutines.MainScope()) }
        waitFor(Tags.page("assistant"))
        compose.runOnIdle {
            assertEquals("chat", state.section); assertEquals(state.chatVertical, state.chatPage); assertEquals(state.chatVertical, state.vertical)
        }
        compose.waitUntil(60_000) { !state.chat.sending && state.chat.session.messages.isNotEmpty() }
        assertEquals("kotlin jobs berlin", state.chat.session.messages.first { it.role == "user" }.content)
    }

    /** #913b the agents in their groups, Buy-Side then Sell-Side; each opens its own screen, which says it only drafts. */
    @Test fun theAgentsPageGroupsTheTenAgentsAndEachOpens() {
        val a = installAgents()
        launch()
        openAgents()
        for (g in a.agents.groups()) scrollTo(AT.LIST, AT.group(g.key))
        assertEquals(listOf("buy/real_estate", "buy/things", "buy/services", "sell/"), a.agents.groups().map { it.key })
        for (x in a.agents.agents) {
            scrollTo(AT.LIST, AT.row(x.id))
            click(AT.row(x.id))
            waitFor(AT.screen(x.id))
            compose.onNodeWithTag(AT.notice(x.id)).assertExists()
            compose.onNodeWithTag(AT.goal(x.id)).assertExists()
            compose.onNodeWithTag(AT.run(x.id)).assertExists()
            click(AT.BACK)
            compose.runOnIdle { assertEquals(null, state.agentOpen) }
        }
    }

    /** An agent's filters are kept per agent and fill its goal; a source switched off is not read. */
    @Test fun anAgentsFiltersAndSourcesAreKept() {
        val a = installAgents()
        launch()
        compose.runOnIdle { state.openAgent("job_placement") }
        waitFor(AT.screen("job_placement"))
        scrollTo(AT.screen("job_placement"), AT.filter("job_placement", "location"))
        compose.onNodeWithTag(AT.filter("job_placement", "location")).performTextInput("Köln")
        compose.onNodeWithTag(AT.goal("job_placement")).assertTextContains("in Köln", substring = true)
        val agent = a.agents.agent("job_placement")!!
        assertEquals("Köln", a.prefs.filters(agent)["location"])
        scrollTo(AT.screen("job_placement"), AT.source("job_placement", "linkedin"))
        click(AT.source("job_placement", "linkedin"))
        compose.runOnIdle { assertEquals(listOf("stepstone", "arbeitsagentur"), a.prefs.sources(agent).map { it.id }) }
    }

    /** #913b the house agent became RS_House-Rental: what the owner saved under house_search is still theirs. */
    @Test fun theHouseAgentKeepsItsSavedConfigAndRuns() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(Services.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString("agents_seen_house_search", "[\"11223344\"]")
            .putString("agents_template_wg_gesucht_room", "Mein Text {{name}}")
            .putString("agents_profile_name", "Ada Test").commit()
        val runs = java.io.File(app.filesDir, "agents/runs.json")
        runs.parentFile!!.mkdirs()
        val old = com.diegonmarcos.cloudsearch.core.agents.RunRecord("old-run", "house_search", 10, 20, "ok", "1 alert mail(s)", 1, 1, 1, 1, 0, 0.0, emptyList())
        runs.writeText(org.json.JSONArray(listOf(old.toJson())).toString())
        val a = installAgents()
        assertEquals(setOf("11223344"), a.prefs.seen("rs_house_rental"))
        assertEquals("Mein Text {{name}}", a.prefs.templateBody("wg_gesucht_room"))
        assertEquals("Ada Test", a.prefs.profile()["name"])
        assertFalse(app.getSharedPreferences(Services.PREFS, android.content.Context.MODE_PRIVATE).contains("agents_seen_house_search"))
        launch()
        compose.runOnIdle { state.openAgent("rs_house_rental") }
        scrollTo(AT.screen("rs_house_rental"), AT.runRow("old-run"))
        // A run now skips what was drafted before the rename.
        scrollTo(AT.screen("rs_house_rental"), AT.run("rs_house_rental"))
        click(AT.run("rs_house_rental"))
        waitRuns(a, 2)
        assertEquals(0, a.runs.all().first { it.id != "old-run" }.drafts)
    }
}

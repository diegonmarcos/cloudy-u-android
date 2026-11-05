package com.diegonmarcos.cloudsearch

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Prefs
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.ai.AiTags
import com.diegonmarcos.superapp.modelcataloguepage.ModelTags
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Chat › Search's Model button opens the full Model Catalogue (libs:ai) at once: its subpages Frontier · Core · Specialist · Medium · Small,
 * Core being the A Text / B Search / C Audio & Speech / D Visual Media tables; the Task button opens the Task Catalogue with its two groups,
 * and selecting a task greys Agent and Model. A tap on a chat model picks it
 * (persisted in the same preference the chat reads) and returns to the chat; a model of a section
 * that is not a chat model cannot be picked. Offline: every OpenRouter call fails here, so the page
 * draws the bundled snapshot and says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelCatalogueShellTest {
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

    private class Offline : Http {
        val asked = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            synchronized(asked) { asked += url }
            return Http.Response(503, "")
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(503, "")
    }

    private val http = Offline()
    private lateinit var services: Services
    private lateinit var state: SearchState
    private lateinit var savedReader: (Context, String) -> Account.Token

    @Before fun offline() {
        services = Services(RuntimeEnvironment.getApplication(), http)
        Services.install(services)
        state = SearchState(services)
        savedReader = Account.reader
        Account.reader = { _, _ -> Account.Token(null, "no token in this test") }
    }

    @After fun online() {
        Account.reader = savedReader
        Services.install(null)
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun openCatalogue() {
        compose.goTo(state, "chat")
        waitFor(Tags.MODEL)
        compose.onNodeWithTag(Tags.MODEL).performClick()
        waitFor(AiTags.CATALOGUE_PAGE)
        waitFor(ModelTags.PAGE)
        waitFor(ModelTags.section("D0"))
    }

    @Test fun theChipOpensTheCatalogueAndARowPicksTheModelForTheChat() {
        compose.setContent { SearchShell(state) }
        openCatalogue()
        assertTrue("the chat gives way to the page", compose.onAllNodesWithTag(Tags.CHAT_INPUT).fetchSemanticsNodes().isEmpty())
        waitFor(ModelTags.AS_OF)
        val id = "meta-llama/llama-3.3-70b-instruct"
        val row = ModelTags.row("A2", id)
        // The row is wider than the phone (its table scrolls sideways), so the tap is its click action, not a touch at its centre.
        compose.onNodeWithTag(row).performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        waitFor(Tags.CHAT_INPUT)
        assertTrue("picking returns to the chat", compose.onAllNodesWithTag(ModelTags.PAGE).fetchSemanticsNodes().isEmpty())
        assertEquals(id, state.chat.model)
        assertEquals(id, services.prefs.model)
        val app = RuntimeEnvironment.getApplication()
        assertEquals("the pick survives a restart", id, Prefs(app.getSharedPreferences(Services.PREFS, Context.MODE_PRIVATE), services.cfg).model)
        assertTrue("the catalogue asked OpenRouter's public models API", synchronized(http.asked) { http.asked.any { it.startsWith("https://openrouter.ai/api/v1/") } })
    }

    @Test fun aModelThatIsNotAChatModelCannotBePicked() {
        compose.setContent { SearchShell(state) }
        val before = state.chat.model
        openCatalogue()
        compose.onNodeWithTag(ModelTags.reference("B0")).performScrollTo()
        for ((section, id) in listOf("B0" to "baai/bge-m3", "C0" to "mistralai/voxtral-small-24b-2507", "D0" to "qwen/qwen-image-3"))
            compose.onNodeWithTag(ModelTags.row(section, id)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AiTags.CATALOGUE_CLOSE).performClick()
        waitFor(Tags.CHAT_INPUT)
        assertEquals("nothing was picked", before, state.chat.model)
    }

    @Test fun closingTheCatalogueInResearchReturnsToResearchReports() {
        compose.setContent { SearchShell(state) }
        compose.runOnUiThread { state.openSection("research"); state.researchPage = "models" }
        waitFor(AiTags.CATALOGUE_PAGE)
        compose.onNodeWithTag(AiTags.CATALOGUE_CLOSE).performClick()
        waitFor(Tags.page("research_reports"))
        assertEquals("reports", state.researchPage)
        assertEquals("research", state.section)
        assertTrue("the catalogue is gone", compose.onAllNodesWithTag(AiTags.CATALOGUE_PAGE).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theCatalogueHasTheFiveSubpagesAndEachListsItsModels() {
        compose.setContent { SearchShell(state) }
        openCatalogue()
        for (tab in listOf("frontier", "core", "specialist", "medium", "small")) compose.onNodeWithTag(AiTags.catTab(tab)).assertExists()
        compose.onNodeWithTag(AiTags.catTab("frontier")).performClick()
        compose.onNodeWithTag(AiTags.catRow("anthropic/claude-opus-5.5")).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(AiTags.catTab("specialist")).performClick()
        compose.onNodeWithTag(AiTags.catRow("mistralai/codestral-2508")).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(AiTags.catTab("medium")).performClick()
        compose.onNodeWithTag(AiTags.catRow("microsoft/phi-4")).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(AiTags.catTab("small")).performClick()
        compose.onNodeWithTag(AiTags.catRow("smollm2-360m-instruct")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AiTags.catRow("google/gemma-3-4b-it")).performScrollTo().performClick()
        waitFor(Tags.CHAT_INPUT)
        assertEquals("google/gemma-3-4b-it", state.chat.model)
    }

    @Test fun theTaskButtonOpensTheTaskCatalogueAndATaskGreysAgentAndModel() {
        compose.setContent { SearchShell(state) }
        compose.goTo(state, "chat")
        waitFor(Tags.MODEL)
        compose.waitUntil(60_000) { state.chat.tasks.isNotEmpty() }
        compose.onNodeWithTag(AiTags.ctl("task")).performClick()
        waitFor(AiTags.TASK_PAGE)
        assertTrue("the chat gives way to the page", compose.onAllNodesWithTag(Tags.CHAT_INPUT).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(AiTags.taskGroup("big")).assertExists()
        compose.onNodeWithTag(AiTags.taskGroup("small")).assertExists()
        compose.onNodeWithTag(AiTags.task("code_architecture")).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(AiTags.task("sentiment")).performScrollTo().assertIsEnabled().performClick()
        waitFor(Tags.CHAT_INPUT)
        assertEquals("sentiment", state.chat.task)
        compose.onNodeWithTag(Tags.MODEL).assertIsNotEnabled()
        compose.onNodeWithTag(AiTags.AGENT).assertIsNotEnabled()
        assertEquals("ibm-granite/granite-4.2-8b", state.chat.model)
    }
}

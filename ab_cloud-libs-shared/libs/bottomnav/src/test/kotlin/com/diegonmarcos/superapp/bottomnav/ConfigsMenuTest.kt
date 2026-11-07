package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * Cloud Store > Settings > Configs is a PAGE with its own top tabs: Sources | ADB Shell | AI | Notifications | Torrent | Data | About.
 * The tabs are READ from the Store's own build.json::ui (never restated here), then driven through the real
 * [PageTabs]: every declared page has a tab, in order, the first is open, a tap opens exactly that page's body and
 * only that one, and Configs stays off the bottom island. The host below mirrors MainActivity's contract (the strip
 * over a body chosen by the open page id), so a page the declaration names but the host cannot open turns this red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigsMenuTest {

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

    private fun storeUi(): JSONObject {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "ac_cloud-store/build.json").exists() }
        assumeTrue("not run from a full checkout", repo != null)
        return JSONObject(File(repo, "ac_cloud-store/build.json").readText()).getJSONObject("ui")
    }

    private fun decl(): NavDecl {
        val ui = storeUi()
        return NavDecl.parse(ui.getJSONArray("sections"), ui.getJSONArray("bottom_nav").toString(), ui.getString("default_section"))
    }

    private fun configs(): List<NavPage> = decl().section("settings")!!.pages

    private var openPage by mutableStateOf("")

    /** The shape of MainActivity: the declared strip, then the open page's body (one at a time). */
    private fun show(pages: List<NavPage>) {
        openPage = pages.first().id
        compose.setContent {
            Column {
                PageTabsImpl(pages, openPage, { openPage = it.id }, Modifier.testTag("strip"), {}, false, WindowInsets(0, 0, 0, 0))
                Text("body of $openPage", Modifier.testTag("body_$openPage"))
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `Configs declares exactly Sources, ADB Shell, AI, Notifications, Torrent, Data, About, in that order`() {
        assertEquals(listOf("sources", "adb", "ai", "notifications", "torrent", "data", "about"), configs().map { it.id })
        assertEquals(listOf("Sources", "ADB Shell", "AI", "Notifications", "Torrent", "Data", "About"), configs().map { it.label })
    }

    @Test
    fun `Configs is a page of the Store and not an island item`() {
        val ui = storeUi()
        val bar = (0 until ui.getJSONArray("bottom_nav").length()).map { ui.getJSONArray("bottom_nav").getString(it) }
        assertFalse("Configs is opened from the top bar's Settings, not the island", "settings" in bar)
        val settings = ui.getJSONObject("top_bar").getJSONArray("settings")
        assertTrue("the top bar's Settings menu offers Configs", (0 until settings.length()).any { settings.getString(it) == "configs" })
        assertEquals("the Store opens on Cloud (cold start), not on Configs", "cloud", ui.getString("default_section"))
    }

    @Test
    fun `every declared Configs page has a tab, the first is open and the others wait`() {
        val pages = configs()
        show(pages)
        for (p in pages) compose.onNodeWithTag(PageTabsTags.tab(p.id), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("body_sources").assertIsDisplayed()
        for (p in pages.drop(1)) assertEquals("only the open page's body exists", 0, compose.onAllNodesWithTag("body_${p.id}").fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on each tab opens that page and only that page`() {
        val pages = configs()
        show(pages)
        for (p in pages) {
            // the strip scrolls: bring the tab on screen first, or the tap lands on its neighbour (CI: "about" opened "notifications")
            compose.onNodeWithTag(PageTabsTags.tab(p.id), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(p.id, openPage)
            compose.onNodeWithTag("body_${p.id}").assertIsDisplayed()
            for (other in pages.filter { it.id != p.id })
                assertEquals("${other.id} is closed while ${p.id} is open", 0, compose.onAllNodesWithTag("body_${other.id}").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `the tab labels are drawn as declared`() {
        show(configs())
        for (p in configs())
            compose.onNodeWithTag(PageTabsTags.label(p.id), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the ADB Shell tab and the AI tab are launch-free destinations`() {
        val byId = configs().associateBy { it.id }
        assertTrue("ADB Shell is a page inside Configs, not a launch of another screen", byId.getValue("adb").action.isBlank())
        assertTrue(byId.getValue("ai").action.isBlank())
        assertTrue("a tab with no sub-strip", configs().all { it.pages.isEmpty() })
    }
}

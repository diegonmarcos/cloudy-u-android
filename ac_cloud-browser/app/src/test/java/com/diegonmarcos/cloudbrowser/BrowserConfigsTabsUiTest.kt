package com.diegonmarcos.cloudbrowser

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserConfigsPageScreen
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.io.File

/**
 * #900 Configs is a section of TOP TABS, one per category, drawn by libs:ui-nav-bars from the SHIPPED declaration:
 * the order, the sideways scroll, the selection, and each page drawing only its own settings and rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserConfigsTabsUiTest {

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

    private val root = JSONObject(File("../build.json").readText())
    private val ui = root.getJSONObject("ui")
    private val cfg = BrowserConfig.parse(ui.getJSONObject("browser").toString())
    private val decl = NavDecl.parse(ui.getJSONArray("sections"), (0 until ui.getJSONArray("bottom_nav").length()).joinToString(",") { ui.getJSONArray("bottom_nav").getString(it) }, ui.getString("default_section"))
    private val palette = KitPalette.fromArgb(0xFF1A0033.toInt(), 0xFF2D0A55.toInt(), 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF7C3AED.toInt(), 0x33FFFFFF, 0xFF000000.toInt())
    private val order = listOf("sites", "permissions", "autocomplete", "browsing", "headless", "gpu")   // #1402/#1443 (General | Perms | About are the fleet pages the shell draws)

    private var selected by mutableStateOf<String?>(null)
    private val taps = mutableListOf<String>()

    private fun showTabs() {
        compose.setContent { CloudKitTheme(palette) { Box(Modifier.fillMaxSize()) {
            NavBarTopSecondRow(decl.section("configs")!!.pages, selected, onSelect = { selected = it.id; taps += it.id }, underTopChrome = false)
        } } }
        compose.waitForIdle()
    }

    @Test
    fun `the Configs section declares the categories as its tabs, in the owner's order`() {
        val pages = decl.section("configs")!!.pages
        assertEquals(listOf("general", "perms", "about") + order, pages.map { it.id })   // #1443 the fleet's three first
        assertEquals(listOf("General", "Perms", "About", "Sites", "Permissions", "Autocomplete", "Browser", "Headless Browser", "GPU"), pages.map { it.label })
        assertTrue("the declaration is valid", decl.problems().isEmpty())
    }

    @Test
    fun `the tabs are drawn left to right in that order, and the strip scrolls sideways`() {
        showTabs()
        // positionInRoot, not boundsInRoot: a tab scrolled out of the strip has a clipped (empty) visible rect but a real position
        val lefts = order.map { id -> compose.onNodeWithTag(PageTabsTags.tab(id), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x }
        assertEquals("tabs run left to right in the declared order", lefts.sorted(), lefts)
        assertEquals("every tab is distinct", order.size, lefts.toSet().size)
        compose.onNodeWithTag(PageTabsTags.STRIP, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
    }

    @Test
    fun `a tap selects a tab and reports the page`() {
        showTabs()
        compose.onNodeWithTag(PageTabsTags.tab("autocomplete"), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("autocomplete"), taps)
        compose.onNodeWithTag(PageTabsTags.tab("autocomplete"), useUnmergedTree = true).assertIsSelected()
    }

    private var page by mutableStateOf("browsing")
    private var composed = false
    private var bare = false

    /** One setContent per test (the rule allows no more): later calls only move the page. [bare] = no values, no rows (the exactly-once count). */
    private fun showPage(p: String, bareMode: Boolean = false) {
        page = p; bare = bareMode
        if (!composed) {
            composed = true
            compose.setContent { CloudKitTheme(palette) {
                BrowserConfigsPageScreen(page, cfg.configs.title(page), cfg.settings, value = { if (bare) null else cfg.settings[it]?.default }, onSet = { _, _ -> },
                    rows = if (bare) emptyList() else cfg.configs.resolve(page, emptyMap(), cfg.menu.whys), onRow = {},
                    groups = cfg.configs.groupsFor(page))
            } }
        }
        compose.waitForIdle()
    }

    private fun on(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a page draws its own settings and rows and nobody else's`() {
        showPage("browsing")
        assertTrue(on("browser:setting:homepage")); assertTrue(on("browser:setting:translate_engine")); assertTrue(on("browser:menu:bookmarks"))
        // #1402 General holds every other setting, grouped: Appearance and Privacy are sections of it
        assertTrue(on("browser:setting:text_zoom")); assertTrue(on("browser:menu:clear_data"))
        assertTrue(on("browser:configs:group:appearance")); assertTrue(on("browser:configs:group:privacy"))
        assertTrue("no Autocomplete setting on General", !on("browser:setting:autofill_enabled"))
    }

    @Test
    fun `every setting of the catalogue appears on exactly one page`() {
        val seen = HashMap<String, Int>()
        for (p in order) {
            showPage(p, bareMode = true)
            cfg.settings.settings.forEach { s -> if (on("browser:setting:${s.key}")) seen[s.key] = (seen[s.key] ?: 0) + 1 }
        }
        // a `set` setting (the enabled add-ons) is drawn by its own screen (Advanced > Manage add-ons), not as a row
        cfg.settings.settings.filter { it.type != "set" }.forEach { assertEquals("${it.key} is drawn on exactly one page", 1, seen[it.key]) }
    }

    @Test
    fun `General's privacy group holds its rows, and Autocomplete's vault group holds the add-on's global rows`() {
        showPage("browsing")
        assertTrue(on("browser:menu:clear_data")); assertTrue(on("browser:menu:history")); assertTrue(on("browser:menu:storage_manage"))
        assertTrue(on("browser:setting:block_trackers"))
        showPage("autocomplete")
        assertTrue(on("browser:configs:group:sync"))
        assertTrue(on("browser:menu:vault_open")); assertTrue(on("browser:setting:autofill_use_vault"))
    }
}

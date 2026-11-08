package com.diegonmarcos.cloudbrowser

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSheet
import com.diegonmarcos.superapp.browser.sheet
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
 * #900 the page dropdown DRAWN, from the SHIPPED build.json: it holds the page and domain rows and no global setting,
 * and a tap on a row reports its id. Compose under Robolectric, the way libs:bottomnav's PageTabsTest runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserPageDropdownUiTest {

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

    private val cfg = BrowserConfig.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString())
    private val palette = KitPalette.fromArgb(0xFF1A0033.toInt(), 0xFF2D0A55.toInt(), 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF7C3AED.toInt(), 0x33FFFFFF, 0xFF000000.toInt())
    private val picked = mutableListOf<String>()

    private fun show(facts: Map<String, Boolean>) {
        val iconRows = cfg.menu.iconRows(facts).map { r -> r.map { it.sheet() } }
        val sections = cfg.menu.listSections(facts).map { (s, rows) -> s.label to rows.map { it.sheet() } }
        compose.setContent { CloudKitTheme(palette) { BrowserSheet(iconRows.firstOrNull().orEmpty(), sections, onPick = { picked += it }, onDismiss = {}, moreIcons = iconRows.drop(1), title = "page-tools") } }
        compose.waitForIdle()
    }

    private fun openTab(i: Int) { compose.onNodeWithTag("browser:menu:tab:$i", useUnmergedTree = true).performClick(); compose.waitForIdle() }

    /** the sheet is tabbed: show the tab of the section [id] is declared in, then tap the row. */
    private fun tap(id: String) {
        openTab(cfg.menu.sections.indexOfFirst { it.id == cfg.menu.item(id)!!.section })
        compose.onNodeWithTag("browser:menu:$id", useUnmergedTree = true).performScrollTo().performClick()
    }

    /** is the row drawn in ANY tab */
    private fun has(id: String) = cfg.menu.sections.indices.any { i ->
        openTab(i)
        compose.onAllNodesWithTag("browser:menu:$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private val pageFacts = mapOf("page" to true, "can_go_back" to true, "can_go_forward" to true, "text_tools" to true, "profile_present" to true)

    @Test
    fun `the dropdown shows the page and domain rows`() {
        show(pageFacts)
        listOf("back", "forward", "reload", "bookmark", "download_page", "find", "share", "copy_url", "open_external", "desktop", "zoom", "translate",
            "reader", "view_source", "print", "add_to_home", "save_page", "site_settings", "shield_trackers", "clear_site_cookies", "clear_site_data",
            "site_autocomplete").forEach { assertTrue("$it is in the dropdown", has(it)) }
    }

    @Test
    fun `the dropdown shows no global setting`() {
        show(pageFacts)
        listOf("settings", "profile", "offline_manage", "storage_manage", "default_browser", "clear_data", "history", "bookmarks", "downloads",
            "addons_manage", "new_tab", "new_private_tab", "tab_groups", "pin_tab", "close_tab", "search_with", "search_chat", "vault_open", "vault_set_service")
            .forEach { assertTrue("$it must not be in the page dropdown", !has(it)) }
    }

    @Test
    fun `the sheet is titled page-tools with the tabs Actions, Page Tools, Site Data, Site Perms, Dev Tools`() {
        show(pageFacts)
        assertTrue("title is drawn", compose.onAllNodesWithTagOrText("page-tools"))
        listOf("Actions", "Page Tools", "Site Data", "Site Perms", "Dev Tools").forEachIndexed { i, title ->
            assertTrue("tab $title is drawn", compose.onAllNodesWithTag("browser:menu:tab:$i", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("tab $title is labelled", compose.onAllNodesWithTagOrText(title))
        }
    }

    @Test
    fun `Page Tools shows its category sub-headers`() {
        show(pageFacts)
        openTab(1)
        listOf("Reading", "Save & Export", "Find & Share", "View mode", "Autofill").forEach { c ->
            assertTrue("category $c is drawn", compose.onAllNodesWithTag("browser:menu:category:$c", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun `a tap on a row reports its id, a disabled row does nothing`() {
        show(pageFacts)
        tap("find"); tap("reload")
        assertEquals(listOf("find", "reload"), picked)
        picked.clear()
    }

    @Test
    fun `with no page open the page rows are shown but cannot be tapped`() {
        show(emptyMap())
        picked.clear()
        tap("find"); tap("site_settings")
        assertTrue("a disabled row is not picked", picked.isEmpty())
        // the desktop toggle needs no page
        tap("desktop")
        assertEquals(listOf("desktop"), picked)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagOrText(text: String): Boolean =
        this.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

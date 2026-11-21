package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.UrlUriDecl
import com.diegonmarcos.superapp.bottomnav.UrlUriIds
import com.diegonmarcos.superapp.bottomnav.UrlUriItem
import com.diegonmarcos.superapp.bottomnav.urlUriModel
import com.diegonmarcos.superapp.themebg.ThemeBackground
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

/**
 * #1842 amendment 7, Rule 16: ONE control. The path's dropdown carries the Url-Uri structure (A Pages, B Tools) and the tree of parents, and keeps
 * every entry of both previous dropdowns (the path popup: crumbs, related, extra, sections, Configs General / About; the Url-Uri popup: every
 * `urluri_*` entry, header, separator and container tag). The field-parity assertions are the lists below, taken from the previous dropdowns' tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MergedPathDropdownTest {
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

    private val labels = AppTopBarLabels("Menu", "Close", "Drawer", "AI", "AI panel", "Settings", "Profile", "Configs")
    private val files = NavSection("files", "Files", "folder", listOf(NavPage("recent", "Recent", "history"), NavPage("shared", "Shared", "groups", pages = listOf(NavPage("links", "Links", "link")))))
    private val tools = NavSection("tools", "Tools", "build", listOf(NavPage("a", "A", "a")))
    private val decl = UrlUriDecl(listOf(UrlUriItem("x", "Extra", "star", "tools")), listOf(UrlUriItem("t", "Tool", "add")))
    private val sections = listOf(files, tools)

    private fun show(
        onNavigate: (HierarchyEntry) -> Unit = {}, onAi: () -> Unit = {}, onAction: (String) -> Unit = {},
        configs: Boolean = true, onTitleClick: (() -> Unit)? = null, current: String = "links",
    ) {
        var general = 0
        compose.setContent {
            NavBarTopFirstRow(
                "Files", ThemeBackground.colors(true), labels, titleHierarchy(files, current), onNavigate, onAi, {}, {},
                appName = "Cloud Files", sections = sections, onTitleClick = onTitleClick,
                extra = { close -> Text("Reload", Modifier.testTag("act_reload").clickable { close() }) },
                onConfigsUi = if (configs) ({ general++ }) else null, onConfigsAbout = if (configs) ({}) else null,
                urlUri = UrlUriMenu(sections, decl, "files", onAi, {}, {}, onAction),
            )
        }
    }

    private fun open() { compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick() }

    /** A tap on the title's TEXT (its left part), not the arrow: the island's centre can fall on the arrow, which opens the dropdown instead. */
    private fun tapTitle() { compose.onNodeWithTag(TopFirstRowTags.ISLAND).performTouchInput { click(Offset(20f, centerY)) } }

    @Test fun `there is no chain-link button on the path row`() {
        show()
        compose.onNodeWithTag(UrlUriTags.BUTTON).assertDoesNotExist()
    }

    @Test fun `field parity, every entry and tag of the old Url-Uri dropdown is in the merged one`() {
        show(); open()
        val model = urlUriModel(sections, "files", decl, canUp = true)
        val oldIds = model.pages.map { it.id } + model.tools.map { it.id }
        // the model itself still holds what the old dropdown listed (section pages, AI, slot, Profile, Configs; the six tools, the slot)
        for (id in listOf("section:files", "section:tools", UrlUriIds.AI, "slot_page:x", UrlUriIds.PROFILE, UrlUriIds.CONFIGS) + UrlUriIds.FIXED_TOOLS + "slot_tool:t") assertTrue("model has $id", id in oldIds)
        for (id in oldIds) compose.onNodeWithTag(UrlUriTags.entry(id)).assertExists()
        for (tag in listOf(UrlUriTags.HEADER_PAGES, UrlUriTags.HEADER_TOOLS, UrlUriTags.SEPARATOR, UrlUriTags.DROPDOWN)) compose.onNodeWithTag(tag).assertExists()
    }

    @Test fun `field parity, every entry and tag of the old path popup is in the merged one`() {
        show(); open()
        // crumbs: app, section, the pages down to the current one (Cloud Files / Files / Shared / Links)
        for (i in 0..3) compose.onNodeWithTag(TopFirstRowTags.crumb(i)).assertExists()
        // related (siblings of Links), the page's actions and their separators, sections, Configs General and About
        compose.onNodeWithTag(TopFirstRowTags.entry("links")).assertExists()
        for (tag in listOf(TopFirstRowTags.DROPDOWN, TopFirstRowTags.SEPARATOR_PAGES, TopFirstRowTags.SEPARATOR_ACTIONS, TopFirstRowTags.ACTIONS, "act_reload",
            TopFirstRowTags.SEPARATOR_SECTIONS, TopFirstRowTags.entry("tools"), TopFirstRowTags.CONFIGS_GENERAL, TopFirstRowTags.CONFIGS_ABOUT)) compose.onNodeWithTag(tag).assertExists()
    }

    @Test fun `the order is tree, related, Pages, actions, Tools, sections, Configs`() {
        show(); open()
        fun y(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        val order = listOf(
            TopFirstRowTags.crumb(3), TopFirstRowTags.entry("links"), UrlUriTags.HEADER_PAGES, TopFirstRowTags.SEPARATOR_ACTIONS,
            UrlUriTags.HEADER_TOOLS, TopFirstRowTags.SEPARATOR_SECTIONS, TopFirstRowTags.CONFIGS_GENERAL, TopFirstRowTags.CONFIGS_ABOUT,
        )
        val ys = order.map { y(it) }
        assertEquals("order $order at $ys", ys.sorted(), ys)
        assertTrue(y(TopFirstRowTags.crumb(0)) < y(TopFirstRowTags.crumb(1)) && y(TopFirstRowTags.crumb(1)) < y(TopFirstRowTags.crumb(2)))
    }

    @Test fun `the tree is indented by depth and each ancestor is a tap that navigates`() {
        val picked = ArrayList<HierarchyEntry>()
        show(onNavigate = { picked += it }); open()
        fun x(i: Int) = compose.onNodeWithTag(TopFirstRowTags.crumb(i)).fetchSemanticsNode().boundsInRoot.left
        assertTrue("indented: ${(0..3).map { x(it) }}", x(0) < x(1) && x(1) < x(2) && x(2) < x(3))
        compose.onNodeWithTag(TopFirstRowTags.crumb(2)).performClick()
        assertEquals("shared", picked.single().id)
    }

    @Test fun `Url-Uri taps still route from the merged dropdown`() {
        val picked = ArrayList<HierarchyEntry>(); var ai = 0; val acted = ArrayList<String>()
        show({ picked += it }, { ai++ }, { acted += it })
        open(); compose.onNodeWithTag(UrlUriTags.entry("section:tools")).performScrollTo().performClick()
        open(); compose.onNodeWithTag(UrlUriTags.entry("slot_page:x")).performScrollTo().performClick()
        open(); compose.onNodeWithTag(UrlUriTags.entry(UrlUriIds.AI)).performScrollTo().performClick()
        open(); compose.onNodeWithTag(UrlUriTags.entry("slot_tool:t")).performScrollTo().performClick()
        assertEquals(listOf("tools", "tools"), picked.map { it.sectionId }); assertEquals(1, ai); assertEquals(listOf("t"), acted)
    }

    @Test fun `a host with onTitleClick keeps its tap and the arrow opens the merged dropdown`() {
        var clicks = 0
        show(onTitleClick = { clicks++ })
        tapTitle()
        assertEquals(1, clicks)
        compose.onNodeWithTag(UrlUriTags.HEADER_PAGES).assertDoesNotExist()
        compose.onNodeWithTag(TopFirstRowTags.MORE).performClick()
        compose.onNodeWithTag(UrlUriTags.HEADER_PAGES).assertExists()
        compose.onNodeWithTag(UrlUriTags.HEADER_TOOLS).assertExists()
        compose.onNodeWithTag(TopFirstRowTags.crumb(0)).assertExists()
    }
}

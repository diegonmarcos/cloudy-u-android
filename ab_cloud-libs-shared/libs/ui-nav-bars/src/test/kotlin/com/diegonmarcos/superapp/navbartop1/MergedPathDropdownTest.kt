package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextEquals
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
 * every entry of both previous dropdowns (the path popup: crumbs, related, extra, sections, Configs General / About; #1850 removed the sections list and the page drawer, see the reachability test; the Url-Uri popup: every
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
        shown: List<NavSection> = sections, actions: Boolean = true,
    ) {
        var general = 0
        compose.setContent {
            NavBarTopFirstRow(
                "Files", ThemeBackground.colors(true), labels, titleHierarchy(files, current), onNavigate, onAi, {}, {},
                appName = "Cloud Files", sections = sections, onTitleClick = onTitleClick,
                extra = if (actions) ({ close -> Text("Reload", Modifier.testTag("act_reload").clickable { close() }) }) else null,
                onConfigsUi = if (configs) ({ general++ }) else null, onConfigsAbout = if (configs) ({}) else null,
                urlUri = UrlUriMenu(shown, decl, "files", onAi, {}, {}, onAction),
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
        // related (siblings of Links), the page's own actions and their separators, Configs General and About. #1850 the sections list is gone:
        // its entries are reached from Parent Pages (see the reachability test), so its separator tag no longer exists.
        compose.onNodeWithTag(TopFirstRowTags.entry("links")).assertExists()
        for (tag in listOf(TopFirstRowTags.DROPDOWN, TopFirstRowTags.SEPARATOR_PAGES, TopFirstRowTags.SEPARATOR_ACTIONS, TopFirstRowTags.ACTIONS, "act_reload",
            TopFirstRowTags.HEADER_TREE, TopFirstRowTags.HEADER_SIBLINGS, TopFirstRowTags.HEADER_ACTIONS,
            TopFirstRowTags.CONFIGS_GENERAL, TopFirstRowTags.CONFIGS_ABOUT)) compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithTag(TopFirstRowTags.entry("tools")).assertDoesNotExist()
    }

    @Test fun `the headers read Parents Tree, Siblings, Parent Pages`() {
        show(); open()
        compose.onNodeWithTag(TopFirstRowTags.HEADER_TREE).assertTextEquals("Parents Tree")
        compose.onNodeWithTag(TopFirstRowTags.HEADER_SIBLINGS).assertTextEquals("Siblings")
        compose.onNodeWithTag(UrlUriTags.HEADER_PAGES).assertTextEquals("Parent Pages")
        compose.onNodeWithTag(UrlUriTags.HEADER_TOOLS).assertTextEquals("Tools")
    }

    @Test fun `a header is skipped when its section is empty`() {
        // Files at depth 0 with a single declared section and no pages: no siblings, no children, and no page actions
        val lone = NavSection("lone", "Lone", "folder")
        compose.setContent {
            NavBarTopFirstRow("Lone", ThemeBackground.colors(true), labels, titleHierarchy(lone, null), {}, {}, {}, {}, appName = "App", sections = listOf(lone),
                urlUri = UrlUriMenu(listOf(lone), decl, "lone", {}, {}, {}, {}))
        }
        open()
        compose.onNodeWithTag(TopFirstRowTags.HEADER_TREE).assertExists()
        // the Siblings list always holds the current page itself (Breadcrumb: depth 0 lists the section peers), so its header stays
        compose.onNodeWithTag(TopFirstRowTags.HEADER_SIBLINGS).assertExists()
        compose.onNodeWithTag(TopFirstRowTags.entry("lone")).assertExists()
        // no page actions: the Actions header and its separator are skipped
        compose.onNodeWithTag(TopFirstRowTags.HEADER_ACTIONS).assertDoesNotExist()
        compose.onNodeWithTag(TopFirstRowTags.SEPARATOR_ACTIONS).assertDoesNotExist()
    }

    @Test fun `a line sits between Settings Profile and Settings Configs`() {
        show(); open()
        fun y(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        val ys = listOf(UrlUriTags.entry(UrlUriIds.PROFILE), UrlUriTags.SEPARATOR_SETTINGS, UrlUriTags.entry(UrlUriIds.CONFIGS)).map { y(it) }
        assertEquals("profile, line, configs at $ys", ys.sorted(), ys)
    }

    @Test fun `no destination is lost, every section of the removed lists is reachable from Parent Pages or Siblings`() {
        // an off-bar section the app declares but the bar does not show: the removed sections list was its only listing at depth > 0
        val extras = NavSection("extras", "Extras", "star", listOf(NavPage("deep", "Deep", "x")))
        val declared = listOf(files, tools, extras)
        show(shown = listOf(files, tools), current = "links"); open()
        // the removed lists were: the page drawer (the sections, as rows) and the sections list (every declared section)
        val parentPages = mergedParentPages(listOf(files, tools), declared)
        for (s in declared) assertTrue("${s.id} is a Parent Page", parentPages.any { it.id == s.id })
        for (s in listOf(files, tools)) compose.onNodeWithTag(UrlUriTags.entry("section:${s.id}")).assertExists()
        // at depth 0 the declared sections are the Siblings
        val top = titleNav("Cloud Files", "Files", titleHierarchy(files, null), declared)
        for (s in declared) assertTrue("${s.id} is a Sibling at depth 0", top.related.any { it.id == s.id })
        // configs is reached by Settings / Configs, not repeated
        assertTrue(mergedParentPages(listOf(files), listOf(files, NavSection("configs", "Configs", "gear"))).none { it.id == "configs" })
    }

    @Test fun `an off-bar section is a Parent Page in the dropdown`() {
        val extras = NavSection("extras", "Extras", "star")
        compose.setContent {
            NavBarTopFirstRow("Files", ThemeBackground.colors(true), labels, titleHierarchy(files, "links"), {}, {}, {}, {},
                appName = "Cloud Files", sections = listOf(files, tools, extras), urlUri = UrlUriMenu(listOf(files, tools), decl, "files", {}, {}, {}, {}))
        }
        open()
        compose.onNodeWithTag(UrlUriTags.entry("section:extras")).performScrollTo().assertExists()
    }

    @Test fun `the order is Parents Tree, Siblings, Parent Pages, actions, Tools, Configs`() {
        show(); open()
        fun y(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        val order = listOf(
            TopFirstRowTags.HEADER_TREE, TopFirstRowTags.crumb(3), TopFirstRowTags.HEADER_SIBLINGS, TopFirstRowTags.entry("links"), UrlUriTags.HEADER_PAGES,
            UrlUriTags.entry(UrlUriIds.PROFILE), UrlUriTags.SEPARATOR_SETTINGS, UrlUriTags.entry(UrlUriIds.CONFIGS), TopFirstRowTags.SEPARATOR_ACTIONS, TopFirstRowTags.HEADER_ACTIONS,
            UrlUriTags.HEADER_TOOLS, TopFirstRowTags.CONFIGS_GENERAL, TopFirstRowTags.CONFIGS_ABOUT,
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

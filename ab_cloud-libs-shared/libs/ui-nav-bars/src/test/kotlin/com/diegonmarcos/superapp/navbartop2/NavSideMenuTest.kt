package com.diegonmarcos.superapp.navbartop2

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavCollapse
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.MemoryNavModeStore
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.SideMenuPresence
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

/** The side menu (expanded + rail), the per-section mode switch, and the scroll machine acting on the menu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavSideMenuTest {
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

    private fun p(id: String, label: String) = NavPage(id, label, "folder")
    private val folders = NavGroup("folders", "Folders", listOf(p("inbox", "Inbox"), p("sent", "Sent mail")), "ic")
    private val labels = NavGroup("labels", "Labels", listOf(p("work", "Work")), "ic")
    private val mail = NavSection("mail", "Mail", "mail", folders.pages + labels.pages, rows = SecondRowMode.SideMenu, groups = listOf(folders, labels))

    private fun tags(id: String) = SideMenuTags.item(id)

    @Test fun `expanded shows the icon and full name per page, a heading per group, the selected row selected`() {
        compose.setContent { NavSideMenu(mail, "sent", {}, SideMenuPresence.Expanded, {}) }
        compose.onNodeWithTag(SideMenuTags.MENU).assertIsDisplayed()
        for (id in listOf("inbox", "sent", "work")) {
            compose.onNodeWithTag(tags(id)).assertIsDisplayed()
            compose.onNodeWithTag(SideMenuTags.label(id), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(SideMenuTags.icon(id), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag(SideMenuTags.heading("group:folders")).assertExists()
        compose.onNodeWithTag(SideMenuTags.heading("group:labels")).assertExists()
        compose.onNodeWithTag(tags("sent")).assertIsSelected()
    }

    @Test fun `rail shows the icon with the first three letters, no full labels, narrower than the menu`() {
        compose.setContent { NavSideMenu(mail, "inbox", {}, SideMenuPresence.Rail, {}) }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.rail("sent"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SideMenuTags.rail("sent"), useUnmergedTree = true).assertTextEquals("Sen")
        compose.onNodeWithTag(SideMenuTags.label("sent"), useUnmergedTree = true).assertDoesNotExist()
        val w = compose.onNodeWithTag(SideMenuTags.MENU).fetchSemanticsNode().boundsInRoot.width
        assertEquals("the rail box is RAIL_WIDTH less the left gutter", SideMenuSpec.RAIL_WIDTH.value - SideMenuSpec.SIDE.value, w, 1.5f)
    }

    @Test fun `away draws nothing`() {
        compose.setContent { NavSideMenu(mail, "inbox", {}, SideMenuPresence.Away, {}) }
        compose.onNodeWithTag(SideMenuTags.MENU).assertDoesNotExist()
    }

    @Test fun `a tap selects the page and the toggle calls back`() {
        var picked: String? = null
        var toggled = 0
        compose.setContent { NavSideMenu(mail, "inbox", { picked = it.id }, SideMenuPresence.Expanded, { toggled++ }) }
        compose.onNodeWithTag(tags("work")).performClick()
        assertEquals("work", picked)
        compose.onNodeWithTag(SideMenuTags.TOGGLE).performClick()
        assertEquals(1, toggled)
    }

    private val oneRowSection = NavSection("s", "S", "i", (1..3).map { p("p$it", "Page $it") })

    @Test fun `the three modes, the user's choice swaps one row, two rows and the side menu on the same section`() {
        val store = MemoryNavModeStore()
        val mode = androidx.compose.runtime.mutableStateOf(SecondRowMode.OneRow)
        compose.setContent {
            store.setMode("s", mode.value)
            NavSectionLayout(oneRowSection, "p1", {}, store = store, underTopChrome = false) { Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("page")) { Text("body") } }
        }
        compose.onNodeWithTag(SecondRowTags.STRIP).assertIsDisplayed()
        compose.onNodeWithTag(SideMenuTags.MENU).assertDoesNotExist()
        compose.onNodeWithTag("page").assertExists()

        mode.value = SecondRowMode.ManyRows; compose.waitForIdle()
        compose.onNodeWithTag(SecondRowGroupTags.BOX).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowTags.STRIP).assertDoesNotExist()

        mode.value = SecondRowMode.SideMenu; compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.BOX).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowTags.STRIP).assertDoesNotExist()
        compose.onNodeWithTag("page").assertExists()
    }

    @Test fun `the declared side menu mode is the default and the page sits beside it`() {
        compose.setContent { NavSectionLayout(mail, "inbox", {}) { Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("page")) { Text("body") } } }
        compose.onNodeWithTag(SideMenuTags.MENU).assertIsDisplayed()
        val menu = compose.onNodeWithTag(SideMenuTags.MENU).fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        assertTrue("the page starts where the docked menu ends", page.left >= menu.right - 1f)
    }

    @Test fun `on a narrow phone the rail docks by default and the expanded menu is an overlay drawer`() {
        val store = MemoryNavModeStore()
        compose.setContent { NavSectionLayout(mail, "inbox", {}, store = store) { Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("page")) { Text("body") } } }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertDoesNotExist()
        compose.onNodeWithTag(SideMenuTags.TOGGLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertIsDisplayed()
        compose.onNodeWithTag(SideMenuTags.SCRIM).assertExists()
        val dock = compose.onNodeWithTag(SideMenuTags.DOCK).fetchSemanticsNode().boundsInRoot
        assertEquals("the docked part is the rail", SideMenuSpec.RAIL_WIDTH.value, dock.width, 1.5f)
        // the scrim folds the menu back to the rail
        // a tap on the scrim, to the right of the menu (the menu itself covers the left part of it)
        compose.onNodeWithTag(SideMenuTags.SCRIM).performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 8f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertDoesNotExist()
    }

    @Test fun `the panel background is opaque in both themes`() {
        for (dark in listOf(true, false)) assertEquals("island is the panel fill", 1f, com.diegonmarcos.superapp.themebg.ThemeBackground.colors(dark).island.alpha, 0f)
    }

    @Test fun `expanded overlay draws one list only, no rail beside it, with the scrim`() {
        val store = MemoryNavModeStore(expanded = true)
        compose.setContent { NavSectionLayout(mail, "inbox", {}, store = store) { Box(androidx.compose.ui.Modifier.fillMaxSize().testTag("page")) { Text("body") } } }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertIsDisplayed()
        compose.onNodeWithTag(SideMenuTags.SCRIM).assertExists()
        compose.onAllNodesWithTag(SideMenuTags.MENU).assertCountEquals(1)
        compose.onAllNodesWithTag(SideMenuTags.rail("inbox"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(SideMenuTags.icon("inbox"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(SideMenuTags.label("inbox"), useUnmergedTree = true).assertExists()
    }

    @Test fun `collapsed draws the rail only, no full labels and no overlay`() {
        val store = MemoryNavModeStore(expanded = false)
        compose.setContent { NavSectionLayout(mail, "inbox", {}, store = store) { Box(androidx.compose.ui.Modifier.fillMaxSize()) {} } }
        compose.waitForIdle()
        compose.onAllNodesWithTag(SideMenuTags.MENU).assertCountEquals(1)
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertDoesNotExist()
        compose.onNodeWithTag(SideMenuTags.SCRIM).assertDoesNotExist()
        compose.onNodeWithTag(SideMenuTags.label("inbox"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `the rail choice is remembered in the store`() {
        val store = MemoryNavModeStore(expanded = false)
        compose.setContent { NavSectionLayout(mail, "inbox", {}, store = store) { Box(androidx.compose.ui.Modifier.fillMaxSize()) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertDoesNotExist()
        compose.onNodeWithTag(SideMenuTags.rail("inbox"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SideMenuTags.TOGGLE).performClick()
        compose.waitForIdle()
        assertEquals(true, store.sideExpanded())
    }

    @Test fun `never chosen, the memory store has no rail state`() {
        assertEquals(null, MemoryNavModeStore().sideExpanded())
    }

    @Test fun `scroll machine on the menu, hidden takes it away, shrunk folds it to the rail, up restores`() {
        val c = BottomNavCollapse()
        val store = MemoryNavModeStore(expanded = true)
        compose.setContent {
            CompositionLocalProvider(LocalNavChromeCollapse provides c) {
                NavSectionLayout(mail, "inbox", {}, store = store) { Box(androidx.compose.ui.Modifier.fillMaxSize()) {} }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertExists()
        compose.runOnIdle { c.onDragDelta(10f) }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.MENU).assertDoesNotExist()
        compose.runOnIdle { c.onRelease() }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertDoesNotExist()
        compose.onNodeWithTag(SideMenuTags.rail("inbox"), useUnmergedTree = true).assertExists()
        compose.runOnIdle { c.restore() }
        compose.waitForIdle()
        compose.onNodeWithTag(SideMenuTags.OVERLAY).assertExists()
    }

    @Test fun `the Configs card sets a section's mode, the declared choice clears it, and one-page sections are not offered`() {
        val store = MemoryNavModeStore()
        val single = NavSection("one", "One", "i", listOf(p("x", "X")))
        compose.setContent { NavModeConfigCard(listOf(oneRowSection, single, mail), store = store) }
        compose.onNodeWithTag(NavModeConfigTags.section("s")).assertIsDisplayed()
        compose.onNodeWithTag(NavModeConfigTags.section("one")).assertDoesNotExist()
        compose.onNodeWithTag(NavModeConfigTags.choice("s", SecondRowMode.SideMenu)).performClick()
        assertEquals(SecondRowMode.SideMenu, store.mode("s"))
        compose.onNodeWithTag(NavModeConfigTags.choice("s", SecondRowMode.SideMenu)).assertIsSelected()
        compose.onNodeWithTag(NavModeConfigTags.choice("s", SecondRowMode.OneRow)).performClick()
        assertEquals("the declared choice stores no override", null, store.mode("s"))
        // Mail declares the side menu: that is its default, choosing a strip overrides it
        compose.onNodeWithTag(NavModeConfigTags.choice("mail", SecondRowMode.SideMenu)).assertIsSelected()
        compose.onNodeWithTag(NavModeConfigTags.choice("mail", SecondRowMode.ManyRows)).performClick()
        assertEquals(SecondRowMode.ManyRows, store.mode("mail"))
    }
}

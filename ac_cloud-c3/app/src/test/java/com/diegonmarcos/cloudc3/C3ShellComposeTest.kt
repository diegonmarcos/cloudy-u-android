package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The shell's chrome, DRIVEN: the shared top bar over the page and the island, with this repository's own baked
 * declarations. The page is a stub, so what is asserted is the chrome: the bar (title, no hamburger, AI, Settings),
 * the drawer tree, the start page and the island's order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C3ShellComposeTest {

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

    private val opened = mutableListOf<C3Nav.Target>()
    private var accounts = 0
    private var configs = 0

    private fun show(tab: String? = C3Nav.start(Declarations.nav, Declarations.aliases)!!.section, page: String? = null) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                C3Chrome(
                    selectedTab = tab, selectedPage = page,
                    onOpen = { opened += it }, onAccount = { accounts++ }, onConfigs = { configs++ },
                    aiPanel = { Text("the shared chat", Modifier.testTag("ai_body")) },
                ) { Text("page body", Modifier.testTag("page_body")) }
            }
        }
        compose.waitForIdle()
    }

    /** A row inside the title dropdown's own scroll: brought into view first. */
    private fun tapIn(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); compose.waitForIdle() }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // ── the top bar ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the first row is the shared top bar, declared in build json`() {
        val decl = Declarations.topBar
        assertTrue("ui.top_bar must be baked", decl != null)
        assertTrue(decl!!.drawer && decl.ai)
        assertEquals(listOf("account", "configs"), decl.settings)
        assertTrue(decl.problems().isEmpty())
        show()
        // #1154 spec change: AI, Profile and Configs are three plain icons (no Settings dropdown any more)
        for (tag in listOf(TopBarTags.BAR, TopBarTags.TITLE, TopBarTags.AI, TopBarTags.ACCOUNT, TopBarTags.CONFIGS)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        assertFalse(exists(TopBarTags.SETTINGS))
        assertFalse("no hamburger: the drawer moved into the title dropdown", exists(TopBarTags.MENU))
        compose.onNodeWithTag("page_body").assertIsDisplayed()
    }

    @Test
    fun `AI opens the shared chat panel`() {
        show()
        assertFalse(exists("ai_body"))
        tap(TopBarTags.AI)
        compose.onNodeWithTag("ai_body").assertIsDisplayed()
    }

    @Test
    fun `Profile and Configs are plain icons and each is wired`() {
        show()
        tap(TopBarTags.ACCOUNT)
        assertEquals(1, accounts)
        tap(TopBarTags.CONFIGS)
        assertEquals(1, configs)
    }

    @Test
    fun `the title island lists the open section's tree and a pick opens that place`() {
        val start = C3Nav.start(Declarations.nav, Declarations.aliases)!!.section
        show(start)
        val section = Declarations.nav.section(start)!!
        tap(TopBarTags.TITLE)
        compose.onNodeWithTag(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.entry(section.id)).assertIsDisplayed()
        val page = section.pages.firstOrNull() ?: return
        tap(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.entry(page.id))
        assertEquals(C3Nav.Target(section.id, page.id), opened.last())
    }

    // ── the island ───────────────────────────────────────────────────────────────────────

    /** libs:bottomnav's own tag for an island item (internal there, so spelled here). */
    private fun item(id: String) = "bottomnav_item_$id"

    @Test
    fun `the island reads Infra, Containers, Home, Ops, Observ left to right, with no Configs`() {
        show()
        val ids = listOf("infra", "containers", "home", "ops", "observ")
        assertEquals(ids, Declarations.nav.bottomNav)
        assertEquals(listOf("Infra", "Containers", "Home", "Ops", "Observ"), Declarations.tabs.map { it.label })
        val lefts = ids.map { compose.onNodeWithTag(item(it)).fetchSemanticsNode().boundsInRoot.left }
        assertEquals("island order", lefts.sorted(), lefts)
        assertEquals(5, lefts.toSet().size)
        assertFalse("Configs is off the bar", exists(item("configs")))
    }

    @Test
    fun `tapping an island item opens that tab`() {
        show()
        tap(item("containers"))
        assertEquals(listOf(C3Nav.Target("containers")), opened)
    }

    @Test
    fun `the app starts on Home`() {
        val start = C3Nav.start(Declarations.nav, Declarations.aliases)!!
        assertEquals("home", start.section)
        assertEquals("home", Declarations.defaultTab)
    }

    // ── the title dropdown (the former drawer) ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the title dropdown ends with the full tree of every section and page, tappable`() {
        show()
        assertFalse(exists(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.DROPDOWN))
        tap(TopBarTags.TITLE)
        compose.onNodeWithTag(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.DROPDOWN).assertIsDisplayed()
        for (s in Declarations.nav.sections) {
            assertTrue("section ${s.id} in the dropdown", exists(C3Tags.drawerSection(s.id)))
            for (p in s.allPages()) assertTrue("page ${s.id}/${p.id} in the dropdown", exists(C3Tags.drawerPage(s.id, p.id)))
        }
    }

    @Test
    fun `a tree page opens its section and page, and closes the dropdown`() {
        show()
        tap(TopBarTags.TITLE)
        tapIn(C3Tags.drawerPage("containers", "all_table"))
        assertEquals(listOf(C3Nav.Target("containers", "all_table")), opened)
        assertFalse("the dropdown closes after a tap", exists(com.diegonmarcos.superapp.navbartop1.TopFirstRowTags.DROPDOWN))
    }

    @Test
    fun `a tree section header opens the section`() {
        show()
        tap(TopBarTags.TITLE)
        tapIn(C3Tags.drawerSection("ops"))
        assertEquals(listOf(C3Nav.Target("ops")), opened)
    }

    @Test
    fun `Configs is reachable from the title dropdown though it is off the island`() {
        show()
        tap(TopBarTags.TITLE)
        assertTrue(exists(C3Tags.drawerSection("configs")))
    }
}

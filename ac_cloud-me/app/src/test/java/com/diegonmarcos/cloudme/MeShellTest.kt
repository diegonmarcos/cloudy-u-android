package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.navbartop1.TopFirstRowTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
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
 * #1150 the shell's top row, MEASURED in [MeShell] (the very composable MainActivity draws):
 *  - no hamburger, the title at the left, and AI, Profile and Configs at the right as ICONS (no label text);
 *  - the former left menu is the end of the title dropdown, opened by TAPPING the title and by nothing else: an edge swipe does not open it (#1144);
 *  - scrolling the page down hides the top row and collapses the bottom-nav labels to icons, scrolling up brings both back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeShellTest {

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

    private val opened = mutableListOf<String>()
    private val targets = mutableListOf<String>()
    private val pagesOpened = mutableListOf<Pair<String, String>>()
    private var selected by mutableStateOf<String?>("lnktree")

    private fun show() {
        compose.setContent {
            CloudKitTheme(KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Magenta, Color.Gray, Color.Black)) {
                MeShell(
                    "Lnktree", selected, onOpen = { opened += it; selected = it }, onTarget = { targets += it },
                    onOpenPage = { section, page -> pagesOpened += section to page },
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(PAGE)) { Spacer(Modifier.height(6000.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun barShown() = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(TopBarTags.BAR)).fetchSemanticsNodes().isNotEmpty()

    private fun navLabels() = Sections.bottom().count {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(BottomNavTags.label(it.id)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the right side is three icons without labels and each one acts`() {
        show()
        compose.onNodeWithTag(TopBarTags.MENU).assertDoesNotExist()
        for (tag in listOf(TopBarTags.AI, TopBarTags.ACCOUNT, TopBarTags.CONFIGS)) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.SETTINGS).assertDoesNotExist()
        // icons only: none of the three draws its name as text (the title is the only text in the bar)
        for (name in listOf("AI", "Profile account", "Configs")) {
            assertEquals("a label '$name' is drawn in the bar", 0, compose.onAllNodes(hasText(name)).fetchSemanticsNodes().size)
        }
        compose.onNodeWithTag(TopBarTags.AI).performClick()
        compose.onNodeWithTag(TopBarTags.ACCOUNT).performClick()
        compose.onNodeWithTag(TopBarTags.CONFIGS).performClick()
        compose.waitForIdle()
        assertEquals(listOf(MeTopBar.AI_TARGET), targets)
        assertEquals(listOf("profile", "configs"), opened)
    }

    @Test
    fun `tapping the title island lists the shown section's hierarchy and a pick opens that page (#1154)`() {
        show()
        val section = Sections.byId("lnktree")!!
        compose.onNodeWithTag(TopBarTags.TITLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TopFirstRowTags.entry(section.id)).assertIsDisplayed()
        // Lnktree lists 16 pages (#1298 Proj): the later entries are reached by scrolling the menu.
        // #1483 the section list follows the pages, and a page id may equal a section id (Health): the page entry is the first node
        for (p in section.pages) compose.onAllNodesWithTag(TopFirstRowTags.entry(p.id)).onFirst().performScrollTo().assertIsDisplayed()
        val last = section.pages.last()
        compose.onAllNodesWithTag(TopFirstRowTags.entry(last.id)).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf(section.id to last.id), pagesOpened)
    }

    @Test
    fun `the former left menu is in the title dropdown, lists every section and closes on a pick`() {
        show()
        compose.onNodeWithTag(TopFirstRowTags.DROPDOWN).assertDoesNotExist()
        compose.onNodeWithTag(TopBarTags.TITLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TopFirstRowTags.DROPDOWN).assertIsDisplayed()
        for (s in Sections.all()) compose.onNodeWithTag(MeTopBar.drawerItem(s.id)).assertExists()
        // The dropdown is capped at 520dp and scrolls: with the Profile pages open, the section list starts below the fold.
        compose.onNodeWithTag(MeTopBar.drawerItem("inventory")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("inventory"), opened)
        compose.onNodeWithTag(TopFirstRowTags.DROPDOWN).assertDoesNotExist()
    }

    @Test
    fun `an edge swipe never opens the left menu`() {
        show()
        compose.onNodeWithTag(PAGE).performTouchInput { swipe(Offset(0f, centerY), Offset(width * 0.6f, centerY)) }
        compose.waitForIdle()
        compose.onNodeWithTag(TopBarTags.DRAWER).assertDoesNotExist()
        compose.onNodeWithTag(TopBarTags.SCRIM).assertDoesNotExist()
    }

    @Test
    fun `scrolling down hides the top row and collapses the nav labels, scrolling up restores both`() {
        show()
        assertTrue("the top row is not shown at rest", barShown())
        val labels = Sections.bottom().size
        assertEquals("the nav starts with its labels", labels, navLabels())

        compose.onNodeWithTag(PAGE).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("scrolling down left the top row on screen", !barShown())
        // #1159 scrolled down only the selected item keeps its label; the others fold away.
        assertTrue("scrolling down left the non-selected nav labels (${navLabels()} of $labels)", navLabels() <= 1 && navLabels() < labels)

        compose.onNodeWithTag(PAGE).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue("scrolling up did not bring the top row back", barShown())
        assertEquals("scrolling up did not bring the nav labels back", labels, navLabels())
    }

    private companion object { const val PAGE = "me:page" }
}

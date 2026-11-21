package com.diegonmarcos.superapp.navbartop1

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.UrlUriDecl
import com.diegonmarcos.superapp.bottomnav.UrlUriIds
import com.diegonmarcos.superapp.bottomnav.UrlUriItem
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

/** #1842 the Url-Uri dropdown: the far-left control, Pages then Tools with icons, taps routed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UrlUriDropdownTest {
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

    private val a = NavSection("a", "Alpha", "home", listOf(NavPage("p", "P", "link")))
    private val b = NavSection("b", "Beta", "cloud")
    private val decl = UrlUriDecl(listOf(UrlUriItem("x", "Extra", "star", "b")), listOf(UrlUriItem("t", "Tool", "add")))

    private fun show(onNavigate: (HierarchyEntry) -> Unit = {}, onAi: () -> Unit = {}, onAction: (String) -> Unit = {}) {
        compose.setContent {
            UrlUriDropdown(ThemeBackground.colors(true), titleHierarchy(a, "p"), listOf(a, b), decl, "a", onNavigate, onAi, {}, {}, onAction)
        }
    }

    @Test fun `the control is there and the right icons are status-bar sized`() {
        assertEquals(17f, TopFirstRowSpec.ICON_RIGHT.value, 0.01f)
        show()
        compose.onNodeWithTag(UrlUriTags.BUTTON).assertIsDisplayed()
    }

    @Test fun `a tap lists Pages then Tools, every entry with its row`() {
        show()
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick()
        compose.onNodeWithTag(UrlUriTags.HEADER_PAGES).assertIsDisplayed()
        for (id in listOf("section:a", "section:b", UrlUriIds.AI, "slot_page:x", UrlUriIds.PROFILE, UrlUriIds.CONFIGS)) compose.onNodeWithTag(UrlUriTags.entry(id)).assertExists()
        compose.onNodeWithTag(UrlUriTags.HEADER_TOOLS).assertExists()
        for (id in UrlUriIds.FIXED_TOOLS + "slot_tool:t") compose.onNodeWithTag(UrlUriTags.entry(id)).assertExists()
    }

    @Test fun `a page entry navigates, the slot page opens the declared page, AI and the tool slot call their handlers`() {
        val picked = ArrayList<HierarchyEntry>(); var ai = 0; val acted = ArrayList<String>()
        show({ picked += it }, { ai++ }, { acted += it })
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick(); compose.onNodeWithTag(UrlUriTags.entry("section:b")).performClick()
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick(); compose.onNodeWithTag(UrlUriTags.entry("slot_page:x")).performClick()
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick(); compose.onNodeWithTag(UrlUriTags.entry(UrlUriIds.AI)).performClick()
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick(); compose.onNodeWithTag(UrlUriTags.entry("slot_tool:t")).performClick()
        assertEquals(listOf("b", "b"), picked.map { it.sectionId }); assertEquals(1, ai); assertEquals(listOf("t"), acted)
        assertTrue(picked.all { it.depth == 0 })
    }

    @Test fun `home opens the declared default section`() {
        val picked = ArrayList<HierarchyEntry>(); show({ picked += it })
        compose.onNodeWithTag(UrlUriTags.BUTTON).performClick(); compose.onNodeWithTag(UrlUriTags.entry(UrlUriIds.HOME)).performClick()
        assertEquals("a", picked.single().sectionId)
    }
}

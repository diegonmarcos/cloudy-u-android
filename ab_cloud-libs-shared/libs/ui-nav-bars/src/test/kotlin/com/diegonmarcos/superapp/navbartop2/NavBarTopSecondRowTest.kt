package com.diegonmarcos.superapp.navbartop2

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbarbottom.BottomSpec
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavBarTopSecondRowTest {
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

    private fun pages(n: Int) = (1..n).map { NavPage("p$it", "Page $it", "folder") }

    private fun show(n: Int, selected: String = "p1", onSelect: (NavPage) -> Unit = {}) =
        compose.setContent { NavBarTopSecondRow(pages(n), selected, onSelect, dark = true) }

    @Test fun `it is clearly smaller than the bottom nav in height, icon and font`() {
        val max = SecondRowSpec.SCALE_MAX
        assertTrue(SecondRowSpec.TAB_HEIGHT.value / BottomSpec.CELL.value <= max)
        assertTrue(SecondRowSpec.ICON.value / BottomSpec.ICON.value <= max)
        assertTrue(SecondRowSpec.LABEL.value / BottomSpec.LABEL.value <= max)
        assertTrue("label stays legible", SecondRowSpec.LABEL.value >= 8f)
        assertTrue("the whole strip is shorter than the whole bar",
            SecondRowSpec.TAB_HEIGHT.value + 2 * SecondRowSpec.STRIP_PAD_V.value < BottomSpec.HEIGHT.value)
    }

    @Test fun `every tab renders its icon (never a letter) and, at rest, its name`() {
        show(3)
        for (i in 1..3) {
            val icon = compose.onNodeWithTag(SecondRowTags.icon("p$i"), useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("p$i icon node is a glyph, not text", !icon.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text))
            compose.onNodeWithTag(SecondRowTags.label("p$i"), useUnmergedTree = true).assertExists()
        }
    }

    @Test fun `an emoji_only tab draws no glyph and always its name (#1937), a sibling without the flag still draws its icon`() {
        val ps = listOf(NavPage("e1", "E1", "folder", emojiOnly = true), NavPage("e2", "E2", "folder", emojiOnly = true), NavPage("n1", "N1", "folder"))
        compose.setContent { NavBarTopSecondRow(ps, "e1", {}, dark = true, compact = true) }
        compose.waitForIdle()
        compose.onNodeWithTag(SecondRowTags.icon("e1"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowTags.icon("e2"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowTags.label("e2"), useUnmergedTree = true).assertExists()   // compact would hide a name; an emoji-only tab has nothing else
        compose.onNodeWithTag(SecondRowTags.icon("n1"), useUnmergedTree = true).assertExists()
    }

    @Test fun `compact keeps only the selected name and shortens the tab, the icons stay`() {
        compose.setContent { NavBarTopSecondRow(pages(3), "p2", {}, dark = true, compact = true) }
        compose.waitForIdle()
        compose.onNodeWithTag(SecondRowTags.label("p2"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SecondRowTags.label("p1"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(SecondRowTags.label("p3"), useUnmergedTree = true).assertDoesNotExist()
        for (i in 1..3) compose.onNodeWithTag(SecondRowTags.icon("p$i"), useUnmergedTree = true).assertExists()
        assertEquals(SecondRowSpec.COMPACT_TAB_HEIGHT.value, compose.onNodeWithTag(SecondRowTags.tab("p1")).fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    @Test fun `the rendered tab is the declared height`() {
        show(3)
        val tab = compose.onNodeWithTag(SecondRowTags.tab("p2")).fetchSemanticsNode().boundsInRoot
        assertEquals(SecondRowSpec.TAB_HEIGHT.value, tab.height, 0.5f)
    }

    @Test fun `a single tab is centred`() {
        show(1)
        val strip = compose.onNodeWithTag(SecondRowTags.STRIP).fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithTag(SecondRowTags.tab("p1")).fetchSemanticsNode().boundsInRoot
        assertEquals((strip.left + strip.right) / 2, (tab.left + tab.right) / 2, 1.5f)
    }

    @Test fun `several tabs are spaced evenly including the borders`() {
        show(3, selected = "p2")
        val strip = compose.onNodeWithTag(SecondRowTags.STRIP).fetchSemanticsNode().boundsInRoot
        val t = (1..3).map { compose.onNodeWithTag(SecondRowTags.tab("p$it")).fetchSemanticsNode().boundsInRoot }
        val gaps = listOf(t[0].left - strip.left, t[1].left - t[0].right, t[2].left - t[1].right, strip.right - t[2].right)
        for (g in gaps) assertEquals("gaps $gaps", gaps[0], g, 1.5f)
    }

    /** #1831 a selected tab beyond the screen edge is scrolled into view (Cloud Wiki's simulator tabs sit after the era pages). */
    @Test fun `the selected tab is brought on screen in a crowded row`() {
        show(14, selected = "p13")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val strip = compose.onNodeWithTag(SecondRowTags.STRIP).fetchSemanticsNode().boundsInRoot
        val t = compose.onNodeWithTag(SecondRowTags.tab("p13")).fetchSemanticsNode().boundsInRoot
        assertTrue("p13 $t is inside the strip $strip", t.left >= strip.left - 1f && t.right <= strip.right + 1f)
    }

    @Test fun `selecting calls back with the page`() {
        var picked: String? = null
        show(3, onSelect = { picked = it.id })
        compose.onNodeWithTag(SecondRowTags.tab("p3")).performClick()
        assertEquals("p3", picked)
    }

    @Test fun `the selected tab is the SuperApp violet rounded rectangle`() {
        val c = ThemeBackground.colors(true)
        assertEquals(ThemeBackground.TAB_SELECTED, c.tabSelected)
        assertTrue("a rounded rectangle, not a circle: radius below half the tab height", SecondRowSpec.TAB_RADIUS.value < SecondRowSpec.TAB_HEIGHT.value / 2)
        assertTrue(c.tabSelected != Color.Gray && c.tabSelected.alpha == 1f)
    }

    @Test fun `the selected strip tab is neutral, no saturated accent (1852)`() {
        for (dark in listOf(true, false)) {
            val c = ThemeBackground.colors(dark)
            val f = SecondRowTone.selectedFill(c)
            fun sat(x: Color): Float { val mx = maxOf(x.red, x.green, x.blue); val mn = minOf(x.red, x.green, x.blue); return mx - mn }   // chroma: HSV saturation reads the light palette's dark slate ink as vivid
            assertTrue("fill saturation ${sat(f)} stays neutral", sat(f) < 0.15f)
            assertTrue(f != c.tabSelected && sat(c.tabSelected) > 0.4f)
            assertEquals(c.ink, SecondRowTone.selectedInk(c))
            assertTrue("a translucent tint, not an opaque block", f.alpha < 0.3f)
        }
    }

    @Test fun `a request from the title dropdown selects the page, or the container that holds it and waits`() {
        val requests = SubPageRequests()
        val tree = listOf(NavPage("a", "A", "home"), NavPage("box", "Box", "inventory_2", pages = listOf(NavPage("deep", "Deep", "folder"))))
        val picked = mutableListOf<String>()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalSubPageRequests provides requests) {
                NavBarTopSecondRow(tree, "a", { picked += it.id }, dark = true)
            }
        }
        compose.runOnUiThread { requests.request("a") }
        compose.waitForIdle()
        assertEquals("the selected page asked for again selects nothing and clears the request", emptyList<String>(), picked)
        assertEquals(null, requests.pending)
        compose.runOnUiThread { requests.request("deep") }
        compose.waitForIdle()
        assertEquals("the container holding the page is selected", listOf("box"), picked)
        assertEquals("the request waits for the strip one level down", "deep", requests.pending)
    }

    @Test fun `a request for the only page of the selected container is consumed so a tapped tab stays selected`() {
        val requests = SubPageRequests()
        val tree = listOf(
            NavPage("one", "One", "home", pages = listOf(NavPage("one_a", "A", "folder"))),
            NavPage("two", "Two", "inventory_2", pages = listOf(NavPage("two_a", "A", "folder"), NavPage("two_b", "B", "folder"))),
        )
        val picked = mutableListOf<String>()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalSubPageRequests provides requests) {
                NavBarTopSecondRow(tree, "one", { picked += it.id }, dark = true)
            }
        }
        compose.runOnUiThread { requests.request("one_a") }
        compose.waitForIdle()
        assertEquals("a container with one page draws no strip below: nothing is left pending", null, requests.pending)
        assertEquals(emptyList<String>(), picked)
    }
}

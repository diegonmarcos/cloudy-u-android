package com.diegonmarcos.superapp.navbartop2

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import org.junit.Assert.assertEquals
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
 * #1854 `groups` on a one-row section draw a "|" between the groups on the one strip, in group order, with no headers; no groups, no "|";
 * a two-rows section keeps its own rows (its pages carry no divider). Rule 16: every page stays on the strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupDividerTest {
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

    private fun pg(id: String) = """{"id":"$id","label":"$id","icon":"folder"}"""
    private val ids = listOf("a", "b", "c", "d", "e")
    private fun strip(extra: String): List<NavPage> {
        val json = """[{"id":"s","label":"S","icon":"menu","pages":[${ids.joinToString(",") { pg(it) }}]$extra}]"""
        return NavDecl.parse(json, "s").section("s")!!.pages
    }
    private val twoGroups = ""","groups":[{"id":"g1","label":"G1","pages":["a"]},{"id":"g2","label":"G2","pages":["b","c","d","e"]}]"""

    private fun show(pages: List<NavPage>) {
        compose.setContent { NavBarTopSecondRow(pages, pages.first().id, {}, dark = true) }
        compose.waitForIdle()
    }
    private fun drawn(pages: List<NavPage>) = pages.filter {
        compose.onAllNodesWithTag("secondrow_sep_" + it.id, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.map { it.id }

    @Test fun `a divider is drawn between the declared groups and only there`() {
        val pages = strip(twoGroups)
        show(pages)
        assertEquals(listOf("b"), drawn(pages))
    }

    @Test fun `three groups draw two dividers, before each group but the first`() {
        val pages = strip(""","groups":[{"id":"g1","label":"G1","pages":["a","b"]},{"id":"g2","label":"G2","pages":["c"]},{"id":"g3","label":"G3","pages":["d","e"]}]""")
        show(pages)
        assertEquals(listOf("c", "d"), drawn(pages))
    }

    @Test fun `no groups draw no divider (the negative case)`() {
        val pages = strip("")
        show(pages)
        assertEquals(emptyList<String>(), drawn(pages))
    }

    @Test fun `the pages follow the group order and none is lost`() {
        val pages = strip(""","groups":[{"id":"g1","label":"G1","pages":["c","a"]},{"id":"g2","label":"G2","pages":["e"]}]""")
        assertEquals(listOf("c", "a", "e", "b", "d"), pages.map { it.id })
        show(pages)
        assertEquals(listOf("e"), drawn(pages))
        for (id in ids) compose.onNodeWithTag(SecondRowTags.tab(id)).assertExists()
    }

    @Test fun `a two-rows section keeps its own rows and its strip pages carry no divider`() {
        val pages = strip(twoGroups.replace("\"groups\"", "\"nav_mode\":\"horizontal_two_rows\",\"groups\""))
        assertEquals(ids, pages.map { it.id })
        assertEquals(emptyList<String>(), pages.filter { it.separatorBefore }.map { it.id })
    }
}

package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
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
 * #1144 — the Samsung Health page tabs, the Settings entries and the bar order, MEASURED on what the
 * app draws: [PageTabs] in its `iconFor` mode over Health's real pages (build.json, through [Sections]),
 * and [MeTopBar] over the real `ui.settings`.
 *
 *  - every tab is drawn as an icon; NO tab but the selected one draws its name;
 *  - a tap moves the name with the selection;
 *  - Settings is two visible entries, Lnktree and Configs, each opening its own section;
 *  - the bar reads Health, Experiences, Tasks, Lnktree, Inventory, Buro, LLC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeTabsTest {

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

    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Magenta, Color.Gray, Color.Black)
    private val health get() = Sections.byId("health")!!.pages
    private var selected by mutableStateOf<String?>(null)

    private fun showTabs(select: String) {
        selected = select
        compose.setContent {
            CloudKitTheme(palette) {
                val ctx = LocalContext.current
                PageTabs(
                    pages = health,
                    selectedId = selected,
                    onSelect = { selected = it.id },
                    underTopChrome = false,
                    iconFor = { p ->
                        // A tab icon is an app drawable, or (Health's pages) a name of libs:ui-icons.
                        val id = ctx.resources.getIdentifier(p.icon, "drawable", ctx.packageName)
                        if (id != 0) painterResource(id) else androidx.compose.ui.graphics.vector.rememberVectorPainter(com.diegonmarcos.superapp.uiicons.UiIcons.vector(p.icon))
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun nameCount(id: String) =
        compose.onAllNodesWithTag(PageTabsTags.label(id), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `every tab is an icon and only the selected tab shows its name`() {
        assertEquals(19, health.size)
        showTabs(health[2].id)
        for (p in health) {
            compose.onAllNodesWithTag(PageTabsTags.icon(p.id), useUnmergedTree = true).assertCountEquals(1)
        }
        for (p in health) {
            assertEquals("tab ${p.id} name visibility", if (p.id == selected) 1 else 0, nameCount(p.id))
        }
        compose.onNodeWithTag(PageTabsTags.label(health[2].id), useUnmergedTree = true).assertTextEquals(health[2].label)
        compose.onNodeWithTag(PageTabsTags.tab(health[2].id)).assertIsSelected()
    }

    @Test
    fun `a tap moves the pill and the name together`() {
        showTabs(health[0].id)
        compose.onNodeWithTag(PageTabsTags.tab(health[1].id)).performClick()
        compose.waitForIdle()
        assertEquals(health[1].id, selected)
        assertEquals("the old tab kept its name", 0, nameCount(health[0].id))
        assertEquals("the new tab shows its name", 1, nameCount(health[1].id))
        compose.onNodeWithTag(PageTabsTags.tab(health[1].id)).assertIsSelected()
        // The strip scrolls rather than clipping: the last tab is reachable and selectable too.
        compose.onNodeWithTag(PageTabsTags.tab(health.last().id)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(health.last().id, selected)
        assertEquals("the previous tab kept its name", 0, nameCount(health[1].id))
        assertEquals("the last tab shows its name", 1, nameCount(health.last().id))
    }

    @Test
    fun `Profile and Configs are the sections the top bar's icons open`() {
        assertEquals(listOf("Profile", "Configs"), Sections.settings().map { it.label })  // #1439 the avatar opens the shared profile page
        assertTrue("the declared top bar has the hamburger and the inline actions", MeTopBar.decl.drawer && MeTopBar.decl.inlineActions)
    }

    @Test
    fun `the bar reads Health, Experiences, Tasks, Lnktree, Inventory, Buro, LLC`() {
        assertEquals(
            listOf("Health", "Experiences", "Tasks", "Lnktree", "Inventory", "Buro", "LLC"),
            Sections.bottom().map { it.label },
        )
        assertTrue("a launch section is back on the bar", Sections.bottom().none { it.target.isNotBlank() })
    }
}

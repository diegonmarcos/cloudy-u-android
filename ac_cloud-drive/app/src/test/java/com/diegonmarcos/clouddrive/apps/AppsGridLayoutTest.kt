package com.diegonmarcos.clouddrive.apps

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.ui.DriveShell
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
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
 * #813 Home ▸ Apps: the LAST row of the grid is fully drawn, inside the grid and above the
 * bottom-nav island, on a short phone screen. The grid used to be a LazyVerticalGrid held to
 * `appTile * rows`, too short for its own padding and tiles, so the last row was clipped (and,
 * lazy, never composed). Labels are read from the declaration, never restated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppsGridLayoutTest {

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

    private val actions = object : DriveActions {
        override fun requestStorageAccess() {}
        override fun requestTreeGrant() {}
        override fun openEngine(engine: String, target: String, url: String) {}
        override fun openImage(path: String, siblings: List<String>) {}
        override fun openPdf(path: String) {}
        override fun openWith(path: String) {}
        override fun share(paths: List<String>) {}
        override fun shareText(title: String, text: String) {}
        override fun copyText(text: String) {}
        override fun openUrl(url: String) {}
        override fun launchApp(packageName: String, fallbackUrl: String): Boolean = false
        override fun openAuthProfile(pkg: String, target: String, extra: String): Boolean = false
    }

    @Test
    fun `the last row of the Apps grid is fully visible above the bottom nav`() {
        val apps = Declarations.apps
        assertTrue("a grid with more than one row", apps.size > 3)
        var routed: Pair<String, String>? = null
        compose.setContent {
            DriveTheme {
                DriveShell { _, _ ->
                    // Home's shape: headers above the grid inside one LazyColumn.
                    LazyColumn(Modifier.testTag(LIST)) {
                        items(HEADER_ROWS) { androidx.compose.material3.Text("header $it", Modifier.testTag("h$it")) }
                        item { AppsGrid(actions, { tab, page -> routed = tab to page }) }
                    }
                }
            }
        }
        compose.onNodeWithTag(LIST).performScrollToIndex(HEADER_ROWS)
        compose.waitForIdle()
        val grid = compose.onNodeWithTag(DriveTags.APPS_GRID).fetchSemanticsNode().boundsInRoot
        val island = compose.onNodeWithTag(BottomNavTags.ISLAND).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag(DriveTags.appTile(apps.last().label)).fetchSemanticsNode()
        val lastBounds = last.boundsInRoot
        // A clipped tile is shorter than an unclipped one: the last must be as tall as the first.
        val first = compose.onNodeWithTag(DriveTags.appTile(apps.first().label)).fetchSemanticsNode().boundsInRoot
        assertEquals("the last tile is not clipped: $lastBounds vs the first $first", first.height, lastBounds.height, 0.5f)
        assertTrue("the last tile ends inside the grid: $lastBounds in $grid", lastBounds.bottom <= grid.bottom + 0.5f)
        assertTrue("the last tile ends above the island: $lastBounds over $island", lastBounds.bottom <= island.top + 0.5f)
        assertTrue("the last tile has height", lastBounds.height > 0f)

        // The Disk Management tile routes to Manage ▸ Apps (Disk Management moved out of Home).
        val disk = apps.first { it.routePage == Declarations.PAGE_MANAGE }
        assertEquals(Declarations.SECTION_HOME, disk.routeTab)
        compose.onNodeWithTag(DriveTags.appTile(disk.label)).performClick()
        assertEquals(Declarations.SECTION_HOME to Declarations.PAGE_MANAGE, routed)
    }

    private companion object {
        const val LIST = "apps_grid_test_list"
        const val HEADER_ROWS = 12
    }
}

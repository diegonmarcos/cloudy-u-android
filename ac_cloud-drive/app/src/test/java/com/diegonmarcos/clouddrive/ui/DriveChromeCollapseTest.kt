package com.diegonmarcos.clouddrive.ui

import com.diegonmarcos.cloudlib.gitui.*

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.gitui.RepoColumn
import com.diegonmarcos.clouddrive.sync.RepoTableHeader
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
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
 * RUNTIME PROOF of the shared chrome collapse on the Drive pages. Each page is opened inside the real DriveShell with a
 * page-shaped body (the page's own island, strip and scroll container, with enough test-only rows to scroll). Swiping the
 * list up must hide the first row, hide the non-selected bottom labels and shrink the bottom island (and the second
 * row's tabs where the page has one); swiping down restores all of it. The Git body mirrors the real page: the strip,
 * PullToRefreshBox, a LazyColumn with the sticky RepoTableHeader, so a sticky header that swallowed the nested scroll fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveChromeCollapseTest {
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

    private val LIST = "chrome_probe_list"
    private val STRIP = "chrome_probe_strip"
    private val rows = (1..300).map { "repo-$it" }

    @Composable private fun Rows(modifier: Modifier = Modifier, sticky: Boolean = false) {
        @OptIn(ExperimentalFoundationApi::class)
        LazyColumn(modifier.fillMaxSize().testTag(LIST)) {
            if (sticky) stickyHeader(key = "h") { RepoTableHeader(RepoColumn.NAME, true, rememberScrollState()) {} }
            items(rows, key = { it }) { Text(it, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(8.dp)) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Page(id: String) {
        when (id) {
            "git" -> Column(Modifier.fillMaxSize()) {
                ToolbarIsland(title = "Git")
                NavBarTopSecondRow(pages = Declarations.navPages(Declarations.SECTION_GIT), selectedId = "me", onSelect = {}, modifier = Modifier.testTag(STRIP), underTopChrome = false)
                Box(Modifier.weight(1f)) { PullToRefreshBox(isRefreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) { Rows(sticky = true) } }
            }
            // #1173 Fsync (Backup | Folders Sync) and Mounts (Rclone | FUSE) carry a second row like Git: the same island, strip and scroll container shape the real pages build.
            // #1216 Fsync has no sub-pages, so no strip: the island and the scroll container only.
            "mounts" -> Column(Modifier.fillMaxSize()) {
                ToolbarIsland(title = id)
                NavBarTopSecondRow(pages = Declarations.navPages(id), selectedId = Declarations.navPages(id).first().id, onSelect = {}, modifier = Modifier.testTag(STRIP), underTopChrome = false)
                Box(Modifier.weight(1f)) { Rows() }
            }
            else -> Column(Modifier.fillMaxSize()) {
                ToolbarIsland(title = id)
                Box(Modifier.weight(1f)) { Rows() }
            }
        }
    }

    private fun h(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.height ?: 0f
    /** A bottom label is in the (unmerged) tree only while it is shown; collapsing removes it. */
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun check(tab: String, strip: Boolean) {
        compose.setContent { DriveTheme { DriveShell(select = tab) { id, _ -> Page(id) } } }
        compose.waitForIdle()
        val other = Declarations.tabs.first { it.id != tab }.id
        val bar0 = h(TopBarTags.BAR_SLOT); val island0 = h(BottomNavTags.ISLAND)
        val strip0 = if (strip) h(STRIP) else 0f
        assertTrue("$tab: first row visible at rest", bar0 > 0f)
        assertTrue("$tab: unselected label visible at rest", shown(BottomNavTags.label(other)))

        repeat(3) { compose.onNodeWithTag(LIST).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        assertTrue("$tab: first row hides on scroll down (was $bar0, now ${h(TopBarTags.BAR_SLOT)})", h(TopBarTags.BAR_SLOT) < bar0)
        assertFalse("$tab: unselected bottom label hides on scroll down", shown(BottomNavTags.label(other)))
        assertTrue("$tab: bottom island shrinks (was $island0, now ${h(BottomNavTags.ISLAND)})", h(BottomNavTags.ISLAND) < island0)
        if (strip) assertTrue("$tab: second row shrinks (was $strip0, now ${h(STRIP)})", h(STRIP) < strip0)

        repeat(3) { compose.onNodeWithTag(LIST).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        assertEquals("$tab: first row restores", bar0, h(TopBarTags.BAR_SLOT), 1f)
        assertTrue("$tab: unselected label restores", shown(BottomNavTags.label(other)))
        assertEquals("$tab: island restores", island0, h(BottomNavTags.ISLAND), 1f)
        if (strip) assertEquals("$tab: second row restores", strip0, h(STRIP), 1f)
    }

    @Test fun `git page collapses and restores the chrome`() = check("git", strip = true)
    @Test fun `fsync page collapses and restores the chrome`() = check("fsync", strip = false)
    @Test fun `mounts page collapses and restores the chrome`() = check("mounts", strip = true)
    @Test fun `manage page collapses and restores the chrome`() = check("manage", strip = false)
    @Test fun `home page collapses and restores the chrome`() = check("home", strip = false)
}

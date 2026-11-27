package com.diegonmarcos.clouddrive.sync

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudlib.gitui.GitSheetPart
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.SyncOptions
import com.diegonmarcos.cloudlib.gitui.GitControlSheetView
import com.diegonmarcos.cloudlib.gitui.GitSheet
import com.diegonmarcos.cloudlib.gitui.GitSheetActions
import com.diegonmarcos.cloudlib.gitui.GitSheetHost
import com.diegonmarcos.cloudlib.gitui.SheetControl
import com.diegonmarcos.cloudlib.gitui.rememberGitSheetState
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
 * #1934 the control sheet RENDERS from the declaration: five tabs, the header with its banner and Clone Now, the Sync tab's option groups and a command
 * preview that changes with the options, the Clone tab, and the disabled-with-reason state of a repository that is not cloned. The sheet is the real
 * libs:git-ui composable fed with the real build.json declaration (no copy of it here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlSheetRenderTest {
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

    private val decl: JsonObject = Json.parseToJsonElement(File("../build.json").readText()).jsonObject["ui"]!!.jsonObject["git_control_sheet"]!!.jsonObject
    private val sheet = GitSheet.parse(decl, "en")
    private val seen = mutableListOf<String>()
    private val actions = object : GitSheetActions {
        override fun action(c: SheetControl) { seen += c.id }
        override fun runSync(o: SyncOptions) { seen += "run_sync:" + o.steps().joinToString(" && ") { it.line() } }
        override fun runClone(o: CloneOptions) { seen += "run_clone" }
        override fun copy(text: String) { seen += "copy" }
        override fun setAutoSync(on: Boolean, minutes: Int) {}
    }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    private fun show(cloned: Boolean, bulk: Boolean = false, readOnly: Boolean = false) {
        compose.setContent {
            val state = rememberGitSheetState(sheet)
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { GitControlSheetView(sheet, state, GitSheetHost(cloned = cloned, bulk = bulk, readOnly = readOnly, values = mapOf("name" to "repo-x", "branch" to "main"), cloneUrl = "https://github.com/o/r.git", repoName = "r", targets = if (bulk) listOf("a", "b") else emptyList()), actions) }
        }
    }

    @Test fun fiveStickyTabsAndTheHeaderAreDrawn() {
        show(cloned = true)
        for (t in listOf("overview", "sync", "clone", "workflow", "manage")) compose.onNodeWithTag("git_sheet_tab_$t").assertIsEnabled()
        for (id in listOf("hd.repo_list", "hd.fetch_status", "hd.check_lite", "hd.check_full", "hd.refresh")) compose.onNodeWithTag("git_sheet_$id").assertIsEnabled()
        compose.onNodeWithTag("git_sheet_group_ov_status").assertExists()
        compose.onNodeWithTag("git_sheet_group_ov_goto").assertExists()
        compose.onNodeWithTag("git_sheet_group_ov_meta").assertExists()
    }

    @Test fun notClonedShowsTheBannerAndGreysTheLocalActionsWithTheirReason() {
        show(cloned = false)
        compose.onNodeWithTag("git_sheet_banner").assertExists()
        compose.onNodeWithTag("git_sheet_hd.clone_now").assertIsEnabled()
        compose.onNodeWithTag("git_sheet_hd.fetch_status").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_hd.check_full").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_ctl_ov.goto.folder").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_ctl_ov.goto.web").assertIsEnabled()
        click("git_sheet_tab_workflow")
        compose.onNodeWithTag("git_sheet_ctl_wf.commit").assertIsNotEnabled()
        // every greyed control says why: the declared reason is on screen
        compose.onAllNodesWithText("Needs a local clone", substring = true).onFirst().assertExists()
        compose.onNodeWithTag("git_sheet_ctl_wf.prs").assertIsEnabled()
    }

    // #1934 walk finding: the greyed header actions and the greyed Stage & Commit showed no reason of their own (only the generic banner)
    @Test fun everyGreyedControlOnANotClonedRepoCarriesItsOwnReasonLine() {
        show(cloned = false)
        compose.onNodeWithTag("git_sheet_header_reasons").assertExists()
        compose.onAllNodesWithText("Fetch status, Check lite, Check full: Needs a local clone", substring = true).onFirst().assertExists()
        click("git_sheet_tab_workflow")
        compose.onAllNodesWithText("Stage & Commit: Needs a local clone", substring = true).onFirst().assertExists()
    }

    @Test fun aClonedRepoShowsNoReasonLines() {
        show(cloned = true)
        compose.onNodeWithTag("git_sheet_header_reasons").assertDoesNotExist()
    }

    @Test fun syncTabPreviewFollowsTheOptions() {
        show(cloned = true)
        click("git_sheet_tab_sync")
        compose.onNodeWithTag("git_sheet_preview_sync").assertTextEquals("git pull --ff-only && git push")
        click("git_sheet_ctl_sy.direction.fetch")
        compose.onNodeWithTag("git_sheet_preview_sync").assertTextEquals("git fetch")
        click("git_sheet_ctl_sy.direction.pull")
        click("git_sheet_ctl_sy.strategy.reset")
        compose.onNodeWithTag("git_sheet_preview_sync").assertTextEquals("git fetch && git reset --hard @{u}")
        compose.onNodeWithTag("git_sheet_ctl_sy.flag.submodules").assertIsNotEnabled()
        click("git_sheet_ctl_sy.flag.dryRun")
        compose.onNodeWithTag("git_sheet_preview_sync").assertTextEquals("git fetch --dry-run && git status -sb")
    }

    @Test fun destructiveSyncAsksBeforeItRuns() {
        show(cloned = true)
        click("git_sheet_tab_sync")
        click("git_sheet_ctl_sy.direction.push")
        click("git_sheet_ctl_sy.flag.forceRaw")
        compose.onNodeWithTag("git_sheet_preview_sync").assertTextEquals("git push --force")
        click("git_sheet_ctl_sy.run")
        compose.onNodeWithTag("git_sheet_confirm").assertExists()
        assertTrue("nothing ran before the confirm: $seen", seen.none { it.startsWith("run_sync") })
        compose.onNodeWithTag("git_sheet_confirm_yes").performClick()
        assertEquals(listOf("run_sync:git push --force"), seen)
    }

    @Test fun readOnlyRemoteDisablesEveryPush() {
        show(cloned = true, readOnly = true)
        click("git_sheet_tab_sync")
        compose.onNodeWithTag("git_sheet_ctl_sy.direction.push").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_ctl_sy.flag.forceRaw").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_ctl_sy.direction.fetch").assertIsEnabled()
    }

    @Test fun cloneTabPreviewAndProfileName() {
        show(cloned = false)
        click("git_sheet_tab_clone")
        compose.onNodeWithTag("git_sheet_preview_clone").assertTextEquals("git clone https://github.com/o/r.git store/r")
        click("git_sheet_ctl_cl.preset.bare_blobless")
        compose.onNodeWithTag("git_sheet_ctl_cl.run").assertIsEnabled()
    }

    @Test fun bulkSheetHasTheSameFiveTabsAndConfirmListsTheRepos() {
        show(cloned = true, bulk = true)
        for (t in listOf("overview", "sync", "clone", "workflow", "manage")) compose.onNodeWithTag("git_sheet_tab_$t").assertIsEnabled()
        compose.onNodeWithTag("git_sheet_group_bov_counts").assertExists()
        click("git_sheet_tab_manage")
        click("git_sheet_ctl_bo.mg.shrink_all")
        compose.onNodeWithTag("git_sheet_confirm").assertExists()
    }

    @Test fun settingsAndFolderRouteToTheirActionIds() {
        show(cloned = true)
        click("git_sheet_tab_manage"); click("git_sheet_ctl_mg.tools.settings")
        click("git_sheet_tab_overview"); click("git_sheet_ctl_ov.goto.folder")
        assertEquals(listOf("mg.tools.settings", "ov.goto.folder"), seen)
    }

    @Test fun selectingATabTellsThePageSoItCanScrollTheSheetToItsTop() {
        val tabs = mutableListOf<String>()
        compose.setContent {
            val state = rememberGitSheetState(sheet)
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                GitControlSheetView(sheet, state, GitSheetHost(cloned = true), actions, onTabSelected = { tabs += it })
            }
        }
        click("git_sheet_tab_sync"); click("git_sheet_tab_clone"); click("git_sheet_tab_manage")
        assertEquals(listOf("sync", "clone", "manage"), tabs)
    }

    @Test fun theTabRowStaysPinnedAndTappableWhileTheBodyScrolls() {
        compose.setContent {
            val state = rememberGitSheetState(sheet)
            val host = GitSheetHost(cloned = true, values = mapOf("name" to "repo-x"))
            // the page's layout: header + tab row OUTSIDE the scrolling column, the tab body inside it
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.height(400.dp)) {
                GitControlSheetView(sheet, state, host, actions, part = GitSheetPart.TOP)
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("body_scroll")) {
                    GitControlSheetView(sheet, state, host, actions, part = GitSheetPart.BODY)
                }
            }
        }
        compose.onNodeWithTag("git_sheet_tab_sync").assertIsDisplayed()
        compose.onNodeWithTag("git_sheet_tab_manage").performScrollTo().performClick()
        compose.onNodeWithTag("body_scroll").performScrollToNode(androidx.compose.ui.test.hasTestTag("git_sheet_ctl_mg.tools.settings"))
        // scrolled to the end of the Manage body: the tab row is still on screen and a tap on it still switches the tab
        compose.onNodeWithTag("git_sheet_tab_manage").assertIsDisplayed()
        compose.onNodeWithTag("git_sheet_tab_sync").performScrollTo().performClick()
        compose.onNodeWithTag("git_sheet_group_sy_direction").assertExists()
        compose.onNodeWithTag("git_sheet_ctl_sy.direction.fetch").assertExists()
    }
}

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
import com.diegonmarcos.cloudlib.gitui.BulkProgress
import com.diegonmarcos.cloudlib.gitui.BulkScopes
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
 * #1934 the BULK sheet RENDERS from the same declaration as the per-repo one: the scope selector, the per-repo groups and controls in every scope, greyed run
 * controls with their own reason, the confirm naming scope and count, the progress rows, the summary and Cancel. Real libs:git-ui composable, real build.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BulkSheetRenderTest {
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
        override fun runSync(o: SyncOptions) { seen += "run_sync" }
        override fun runClone(o: CloneOptions) { seen += "run_clone" }
        override fun copy(text: String) {}
        override fun setAutoSync(on: Boolean, minutes: Int) {}
        override fun setScope(id: String) { seen += "scope:$id" }
        override fun togglePick(name: String) { seen += "pick:$name" }
        override fun cancelRun() { seen += "cancel" }
    }
    private val repos = listOf(BulkScopes.Entry("alpha", true), BulkScopes.Entry("beta", true), BulkScopes.Entry("gamma", false))

    private fun show(scope: String, picked: Set<String> = emptySet(), progress: List<BulkProgress.Row> = emptyList(), runActive: Boolean = false) {
        val scoped = BulkScopes.targets(scope, repos, picked)
        compose.setContent {
            val state = rememberGitSheetState(sheet, "bulk")
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                GitControlSheetView(sheet, state, GitSheetHost(
                    cloned = true, bulk = true, targets = scoped.map { it.name }, scope = scope, scopeLabel = sheet.scopes.first { it.id == scope }.label,
                    unavailable = BulkScopes.unavailableText(sheet, scope, scoped, runActive), picks = repos.map { it.name }, picked = picked, progress = progress, runActive = runActive,
                ), actions)
            }
        }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    @Test fun theScopeSelectorDrawsThreeScopesAndReportsTheChoice() {
        show("all")
        for (id in listOf("all", "selected", "cloned")) compose.onNodeWithTag("git_bulk_scope_$id").assertIsEnabled()
        compose.onNodeWithTag("git_bulk_scope_count").assertTextEquals("3 repos")
        click("git_bulk_scope_selected")
        assertEquals(listOf("scope:selected"), seen)
    }

    private fun sameControlsIn(scope: String) {
        show(scope, picked = setOf("alpha"))
        for (t in listOf("overview", "sync", "clone", "workflow", "manage")) compose.onNodeWithTag("git_sheet_tab_$t").assertExists()
        click("git_sheet_tab_manage")
        for (id in listOf("mg.auth.test", "mg.auth.https", "mg.clean.5", "mg.life.archive_remote", "mg.life.delete_remote")) compose.onNodeWithTag("git_sheet_ctl_$id").assertExists()
        compose.onNodeWithTag("git_sheet_ctl_mg.life.rename").assertDoesNotExist()   // per-repo-only
        click("git_sheet_tab_sync")
        for (id in listOf("sy.rec.stash", "sy.rec.auto_commit", "sy.direction.pull", "sy.preset.safe")) compose.onNodeWithTag("git_sheet_ctl_$id").assertExists()
        click("git_sheet_tab_clone")
        for (id in listOf("cl.preset.depth3", "cl.dest.workspace", "cl.data.blobs")) compose.onNodeWithTag("git_sheet_ctl_$id").assertExists()
    }
    @Test fun allReposDrawsTheSameTabsAndThePerRepoControls() = sameControlsIn("all")
    @Test fun selectedOnesDrawsTheSameTabsAndThePerRepoControls() = sameControlsIn("selected")
    @Test fun clonedOnesDrawsTheSameTabsAndThePerRepoControls() = sameControlsIn("cloned")

    @Test fun selectedWithNoPickGreysEveryRunControlWithItsReasonAndOffersThePickBoxes() {
        show("selected")
        compose.onNodeWithTag("git_bulk_pick_alpha").assertExists()
        click("git_sheet_tab_sync")
        compose.onNodeWithTag("git_sheet_ctl_bo.sync.run").assertIsNotEnabled()
        compose.onNodeWithTag("git_sheet_ctl_sy.rec.stash").assertIsNotEnabled()
        compose.onAllNodesWithText("No repository is selected", substring = true).onFirst().assertExists()
        click("git_bulk_pick_beta")
        assertEquals(listOf("pick:beta"), seen)
    }

    @Test fun clonedScopeGreysCloneWithItsReasonButStashStillRuns() {
        show("cloned")
        click("git_sheet_tab_clone")
        compose.onNodeWithTag("git_sheet_ctl_bo.clone.run").assertIsNotEnabled()
        compose.onAllNodesWithText("Every repository in this scope is already cloned", substring = true).onFirst().assertExists()
        click("git_sheet_tab_sync")
        compose.onNodeWithTag("git_sheet_ctl_sy.rec.stash").assertIsEnabled()
    }

    @Test fun aDestructiveBulkControlConfirmsNamingTheScopeAndTheCount() {
        show("all")
        click("git_sheet_tab_manage")
        click("git_sheet_ctl_mg.life.delete_remote")
        compose.onNodeWithTag("git_sheet_confirm").assertExists()
        compose.onNodeWithTag("git_sheet_confirm_scope").assertTextEquals("Scope: All Repos \u00b7 3 repositories")
        assertTrue("nothing ran before the confirm", seen.isEmpty())
        compose.onNodeWithTag("git_sheet_confirm_yes").performClick()   // a dialog window: no scrolling parent
        assertEquals(listOf("mg.life.delete_remote"), seen)
    }

    @Test fun progressRowsSummaryAndCancelAreDrawn() {
        val rows = BulkProgress.rows(listOf("alpha", "beta", "gamma"), setOf("beta"), mapOf("alpha" to "done"), emptyMap(), emptyMap(), mapOf("gamma" to "needs a local clone"))
        show("all", progress = rows, runActive = true)
        compose.onNodeWithTag("git_bulk_summary").assertTextEquals("Running 3 repos: 1 ok, 1 failed, 0 skipped")
        compose.onNodeWithTag("git_bulk_cancel").assertIsEnabled()
        click("git_sheet_tab_workflow")
        compose.onNodeWithTag("git_bulk_row_alpha").assertTextEquals("alpha  ok: done")
        compose.onNodeWithTag("git_bulk_row_beta").assertTextEquals("beta  running")
        compose.onNodeWithTag("git_bulk_row_gamma").assertTextEquals("gamma  failed: needs a local clone")
        click("git_bulk_cancel")
        assertEquals(listOf("cancel"), seen)
    }
}

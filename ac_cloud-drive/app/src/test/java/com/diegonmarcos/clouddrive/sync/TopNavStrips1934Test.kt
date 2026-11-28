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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
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
 * #1934 on a 360 dp phone the top nav's two chip rows (scopes, group tabs) never show a chip that is merely cut off: every chip is fully inside its strip, or the strip
 * shows a scroll hint at the cut edge (`<strip>_more_end`) and scrolling reaches the chip. Real libs:git-ui composable, real build.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopNavStrips1934Test {
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
    private val actions = object : GitSheetActions {
        override fun action(c: SheetControl) {}
        override fun runSync(o: SyncOptions) {}
        override fun runClone(o: CloneOptions) {}
        override fun copy(text: String) {}
        override fun setAutoSync(on: Boolean, minutes: Int) {}
        override fun setScope(id: String) {}
        override fun togglePick(name: String) {}
        override fun cancelRun() {}
    }

    private fun show() {
        val repos = listOf(BulkScopes.Entry("alpha", true), BulkScopes.Entry("beta", false))
        compose.setContent {
            val state = rememberGitSheetState(sheet, "bulk")
            Box(Modifier.width(240.dp)) {   // narrower than the chips' total width, so a cut is certain whatever the font metrics
            GitControlSheetView(sheet, state, GitSheetHost(cloned = true, bulk = true, targets = repos.map { it.name }, scope = "all", scopeLabel = "All Repos",
                picks = repos.map { it.name }), actions, part = GitSheetPart.TOP,
                scopeTrailing = { Box(Modifier.size(56.dp)); Box(Modifier.size(56.dp)) })
            }
        }
    }

    // boundsInRoot is CLIPPED by the strip, so a cut chip would look fully inside: the chip's real extent is its unclipped position plus its size
    private fun extent(tag: String): Pair<Float, Float> {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return n.positionInRoot.x to n.positionInRoot.x + n.size.width
    }

    private fun assertReachable(strip: String, chips: List<String>) {
        val sb = compose.onNodeWithTag(strip + "_viewport").fetchSemanticsNode().boundsInRoot
        for (c in chips) {
            val (l, r) = extent(c)
            val inside = l >= sb.left - 1f && r <= sb.right + 1f
            if (!inside) {
                assertTrue("$c is cut off in $strip with no scroll hint", compose.onAllNodesWithTag(strip + "_more_end").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag(c).performScrollTo()   // reachable by scrolling the strip
                val (al, ar) = extent(c)
                assertTrue("$c still cut off after scrolling $strip", al >= sb.left - 1f && ar <= sb.right + 1f)
            }
        }
    }

    @Test fun everyScopeChipIsFullyVisibleOrTheRowHintsItScrolls() {
        show()
        assertReachable("git_bulk_scopes", sheet.scopes.map { "git_bulk_scope_" + it.id })
    }

    @Test fun everyGroupTabIsFullyVisibleOrTheRowHintsItScrolls() {
        show()
        assertReachable("git_sheet_tabs", listOf("overview", "sync", "clone", "workflow", "manage").map { "git_sheet_tab_$it" })
    }
}

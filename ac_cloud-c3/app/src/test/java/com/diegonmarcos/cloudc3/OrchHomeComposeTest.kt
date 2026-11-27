package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import androidx.compose.ui.test.assertIsDisplayed
import com.diegonmarcos.cloudc3.pages.HomeLive
import com.diegonmarcos.cloudc3.pages.OrchFlow
import com.diegonmarcos.cloudc3.pages.OrchHomeScreen
import com.diegonmarcos.cloudc3.pages.OrchTags
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

/** #1954 Orch > Home over a fake reading: six stage rows, the lane matrix with the declared stages only, a tap lists a stage's items, an unread stage prints why. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2000dp-h2000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrchHomeComposeTest {

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

    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)
    private val decl = Declarations.orchFlow
    private val now = 1_800_000_000_000L

    private fun reading(runs: HomeLive.Live<List<GitHubFeed.Run>>): OrchFlow.Reading {
        val repos = decl.pipelines.map { it.repo }.filter { it.isNotBlank() }.distinct()
        return OrchFlow.fold(decl, OrchFlow.Inputs(runs = repos.associateWith { runs }, reposDeclared = 51, ghcrPackages = 60, nowMs = now))
    }

    @Test
    fun `six stage rows, the lane matrix and the items of a tapped stage`() {
        val run = GitHubFeed.Run("Ship", "t", "completed", "failure", "https://example.test/r", now - 60_000)
        var opened = ""
        compose.setContent { CloudKitTheme(palette) { OrchHomeScreen(decl, reading(HomeLive.Live.Ok(listOf(run))), false, now, { opened = it }, {}) } }
        compose.waitForIdle()
        decl.stages.forEach { compose.onNodeWithTag(OrchTags.stage(it.id)).assertExists() }
        decl.pipelines.forEach { p -> p.stages.forEach { s -> compose.onNodeWithTag(OrchTags.lane(p.id, s)).assertExists() } }
        assertTrue("a stage a pipeline does not cross has no cell", compose.onAllNodesWithTag(OrchTags.lane("front", "deploys")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(OrchTags.stage("builds")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OrchTags.stageItem("builds", 0)).assertExists()
        compose.onNodeWithTag(OrchTags.stageOpen("workflows")).performClick()
        assertEquals("long path to the owning page", decl.stages.first { it.id == "workflows" }.open, opened)
    }

    @Test
    fun `an unreadable source prints its reason on the stage, never a zero`() {
        compose.setContent { CloudKitTheme(palette) { OrchHomeScreen(decl, reading(HomeLive.Live.Failed("GitHub rate limit reached")), false, now, {}, {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("builds unread", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("rate limit reached", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}

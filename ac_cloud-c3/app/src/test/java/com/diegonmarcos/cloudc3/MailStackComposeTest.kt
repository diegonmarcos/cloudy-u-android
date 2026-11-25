package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudc3.pages.MailNodeDetail
import com.diegonmarcos.cloudc3.pages.MailStack
import com.diegonmarcos.cloudc3.pages.MailTablesScreen
import com.diegonmarcos.cloudc3.pages.MailTags
import com.diegonmarcos.cloudc3.pages.MailTopologyScreen
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** #1926 The three Mail pages render: the Tables page has a section per declared table, a topology opens the detail of the node shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MailStackComposeTest {
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

    private val dir = FleetFixtures.moduleDir()
    private val decl = MailStack.parseDecl(File(dir, "data/mail_stack.json").readText())
    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)

    @Test fun `Tables has one section per declared table`() {
        val infra = Json.parseToJsonElement(File(dir, "data/infra_slice.json").readText()) as JsonObject
        compose.setContent { CloudKitTheme(palette) { MailTablesScreen(decl, null, "banner", infra, FleetFixtures.slice().domains) } }
        decl.tables.forEach { compose.onAllNodesWithTag(MailTags.section(it.id)).assertCountEquals(1) }
        compose.onNodeWithTag(MailTags.SEARCH).assertExists()
    }

    @Test fun `a topology page draws its canvas and a node detail opens on request`() {
        val t = decl.topology("inbound")!!
        compose.setContent { CloudKitTheme(palette) { MailTopologyScreen(t, decl, null, "", initialOpen = t.nodes[1].id) } }
        compose.onNodeWithTag(MailTags.canvas("inbound")).assertExists()
        compose.onNodeWithTag(MailTags.detail(t.nodes[1].id)).assertExists()
        compose.onAllNodesWithTag(MailTags.detail(t.nodes[0].id)).assertCountEquals(0)
        compose.onNodeWithTag(MailTags.DETAIL_CLOSE).performClick()
        compose.onAllNodesWithTag(MailTags.detail(t.nodes[1].id)).assertCountEquals(0)
    }

    @Test fun `the detail sheet is for the node it was given`() {
        val n = decl.topology("outbound")!!.nodes[2]
        compose.setContent { CloudKitTheme(palette) { MailNodeDetail(MailStack.detail(n, null)) {} } }
        compose.onNodeWithTag(MailTags.detail(n.id)).assertExists()
        assertTrue(n.id.isNotBlank())
    }
}

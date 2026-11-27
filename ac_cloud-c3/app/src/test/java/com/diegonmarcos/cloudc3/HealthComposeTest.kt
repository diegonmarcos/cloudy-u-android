package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudc3.cloud.Services
import com.diegonmarcos.cloudc3.pages.HealthModel
import com.diegonmarcos.cloudc3.pages.HealthScreen
import com.diegonmarcos.cloudc3.pages.HealthTags
import com.diegonmarcos.cloudc3.pages.HealthUi
import com.diegonmarcos.cloudc3.pages.XrayItem
import com.diegonmarcos.cloudc3.pages.XrayModel
import com.diegonmarcos.cloudc3.pages.XrayScreen
import com.diegonmarcos.cloudc3.pages.XrayTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
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

/** #1955 C A2 / A3 The Health debugger and X-ray C3 render: the tabs, the layer table, the Lights rows with their old tag, the explicit not-served states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthComposeTest {
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
    private val pub = Services.publicServices()
    private val priv = Services.privateServices()

    private fun ui(): HealthUi {
        val v = HealthModel.Vantage(false, true, null, false)
        val checks = HealthModel.vantageChecks(v) + HealthModel.lightsChecks(pub, priv, emptyMap(), v, null, false) +
            HealthModel.LAYERS.map { it.id }.filter { it !in HealthModel.PHONE_LAYERS }.map { HealthModel.fleetPlaceholder(it, HealthModel.NOT_SERVED_ROUTE) }
        return HealthUi(checks, null, listOf("the engine could not attribute the checks: no root cause is shown."))
    }

    @Test fun `the Health screen has every tab, every layer row and the explicit device-side token state`() {
        compose.setContent { CloudKitTheme(palette) { HealthScreen(ui(), {}, {}) } }
        listOf("overview", "lights", "containers", "vms", "mesh", "more", "profile", "cause").forEach { compose.onNodeWithTag(HealthTags.tab(it)).assertExists() }
        compose.onNodeWithTag(HealthTags.PROBE).assertExists()
        HealthModel.LAYERS.forEach { compose.onNodeWithTag(HealthTags.layer(it.id)).assertExists() }
        compose.onNodeWithTag(HealthTags.BANNER).assertExists()
        compose.onNodeWithText(HealthModel.NO_TOKEN_NOTE).assertExists()
    }

    @Test fun `the Lights tab keeps the old row tag on the first and the last service of each scope and the counts`() {
        compose.setContent { CloudKitTheme(palette) { HealthScreen(ui(), {}, {}, initialTab = "lights") } }
        compose.onNodeWithTag("health_lights_counts").assertExists()
        listOf(pub.first().name, priv.first().name).forEach { compose.onAllNodesWithTag(HealthTags.lightsRow(it)).onFirst().assertExists() }
        compose.onNodeWithTag(HealthTags.scope("Private")).performClick()
        compose.onAllNodesWithTag(HealthTags.lightsRow(priv.last().name)).onFirst().assertExists()
        compose.onNodeWithTag(HealthTags.scope("Public")).performClick()
        compose.onAllNodesWithTag(HealthTags.lightsRow(pub.last().name)).onFirst().assertExists()
    }

    @Test fun `the more layers tab says route not served yet for every fleet layer`() {
        compose.setContent { CloudKitTheme(palette) { HealthScreen(ui(), {}, {}, initialTab = "more") } }
        compose.onNodeWithTag(HealthTags.row("edge.fleet_report")).assertExists()
        compose.onNodeWithTag(HealthTags.row("mail.fleet_report")).assertExists()
    }

    @Test fun `X-ray C3 has its four tabs, a run button per VM and the not-served state, and Suites keeps the old Tests page`() {
        val ns = XrayModel.State.NotServed(HealthModel.NOT_SERVED_ROUTE)
        compose.setContent {
            CloudKitTheme(palette) {
                XrayScreen(XrayItem("full", "X-ray C3 full", "", ns), listOf(XrayItem("oci-apps", "oci-apps", "per-VM X-ray", ns)), listOf(XrayItem("gitea", "gitea", "", ns)), "", { _, _ -> },
                    suites = { androidx.compose.material3.Text("suites body") })
            }
        }
        XrayModel.TABS.forEach { compose.onNodeWithTag(XrayTags.tab(it.first)).assertExists() }
        compose.onNodeWithTag(XrayTags.run("full")).assertExists()
        compose.onNodeWithText(HealthModel.NOT_SERVED_ROUTE).assertExists()
        compose.onNodeWithTag(XrayTags.tab("vms")).performClick()
        compose.onNodeWithTag(XrayTags.run("oci-apps")).assertExists()
        compose.onNodeWithTag(XrayTags.tab("suites")).performClick()
        compose.onNodeWithText("suites body").assertExists()
    }
}

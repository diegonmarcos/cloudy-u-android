package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudc3.pages.EngineItem
import com.diegonmarcos.cloudc3.pages.EngineTags
import com.diegonmarcos.cloudc3.pages.EnginesBody
import com.diegonmarcos.cloudc3.pages.EnginesModel
import com.diegonmarcos.cloudc3.pages.HealthModel
import com.diegonmarcos.cloudc3.pages.XrayItem
import com.diegonmarcos.cloudc3.pages.XrayModel
import com.diegonmarcos.cloudc3.pages.XrayScreen
import com.diegonmarcos.cloudc3.pages.XrayTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
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

/** #1983 The Tests page draws a card for every engine, the real mail report drills down to each check with its fields, and a missing report prints its reason. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnginesComposeTest {
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
    private val mailText = File(FleetFixtures.moduleDir(), "test/fixtures/cloud_mail_full.json").readText()

    private fun show(ran: MutableList<String>) {
        val items = EnginesModel.ENGINES.map { e ->
            EngineItem(e, null, if (e.id == "mail") EnginesModel.Body.Got(EnginesModel.parse(e, mailText)!!) else EnginesModel.Body.Why(EnginesModel.BODY_GAP))
        }
        compose.setContent {
            CloudKitTheme(palette) {
                Column(Modifier.verticalScroll(rememberScrollState())) { EnginesBody(items, emptyList(), "n/a") { ran += it.kind.orEmpty() } }
            }
        }
    }

    @Test fun `every engine has a card and a run button`() {
        show(mutableListOf())
        EnginesModel.ENGINES.forEach {
            compose.onNodeWithTag(EngineTags.card(it.id)).assertExists()
            compose.onNodeWithTag(EngineTags.run(it.id)).assertExists()
        }
    }

    @Test fun `the mail card shows the verdict, counts and duration, and drills down to a check with its fields`() {
        val ran = mutableListOf<String>()
        show(ran)
        compose.onNodeWithTag(EngineTags.verdict("mail")).assertExists()
        compose.onNodeWithText("FAIL").assertExists()
        compose.onNodeWithText("last run 2026-11-26T17:22:23.463437904+00:00 · 118.4 s · pass 48 · warn 21 · fail 8").assertExists()
        compose.onNodeWithText("cloud-mail-health-full  [rust]").performClick()
        compose.onNodeWithTag(EngineTags.phase("mail", "path_checks")).performClick()
        val c0 = hasAnyAncestor(hasTestTag(EngineTags.check("mail", "path_checks", 0)))
        compose.onNodeWithTag(EngineTags.check("mail", "path_checks", 0)).assertExists()
        listOf("PASS OUT\u21921 Maddy :465 (SMTPS)", "    passed: true", "    details: tcp 10.0.0.3:465 OPEN", "    duration_ms: 129", "    error: null", "    severity: Info")
            .forEach { compose.onNode(hasText(it) and c0).assertExists() }
        compose.onNodeWithTag(EngineTags.run("mail")).performClick()
        assertEquals(listOf("mail"), ran)
    }

    @Test fun `a missing report prints its reason and the analytics button says there is no trigger`() {
        val ran = mutableListOf<String>()
        show(ran)
        compose.onNodeWithTag(EngineTags.verdict("url")).assertTextEquals("no verdict: ${EnginesModel.BODY_GAP}")
        compose.onNodeWithTag(EngineTags.run("analytics")).performClick()
        assertEquals(emptyList<String>(), ran)
    }

    private fun eng(id: String) = EnginesModel.ENGINES.first { it.id == id }

    @Test fun `the no-token reason is a tappable action on every engine card`() {
        var taps = 0
        val noToken = EnginesModel.Body.Why("device: ${HealthModel.NO_TOKEN_NOTE}")
        compose.setContent { CloudKitTheme(palette) { Column(Modifier.verticalScroll(rememberScrollState())) { EnginesBody(listOf(EngineItem(eng("url"), null, noToken, onNoToken = { taps++ })), emptyList(), "n/a") { } } } }
        compose.onNodeWithTag(EngineTags.noToken("url")).assertTextEquals("no verdict: device: ${HealthModel.NO_TOKEN_NOTE}")
        compose.onNodeWithTag(EngineTags.noToken("url")).performClick()
        assertEquals(1, taps)
    }

    @Test fun `a workflow card shows its tag, the rewrite note, its last run and verdict, and a Run only when allow-listed`() {
        val ran = mutableListOf<String>()
        val run = EnginesModel.Run("gha", "46066063430", "completed", "failure", "2026-11-28T10:00:00Z", "2026-11-28T10:01:30Z", "push")
        val items = listOf(
            EngineItem(eng("leak-scan"), null, EnginesModel.Body.Last(run), listOf(run)),
            EngineItem(eng("cgc-surfaces"), null, EnginesModel.Body.Why(eng("cgc-surfaces").note)),
            EngineItem(eng("daily"), null, EnginesModel.Body.Why(EnginesModel.BODY_GAP)),
        )
        compose.setContent { CloudKitTheme(palette) { Column(Modifier.verticalScroll(rememberScrollState())) { EnginesBody(items, emptyList(), "n/a") { ran += it.id } } } }
        compose.onNodeWithText(".github/workflows/leak-scan.yml  [gha]").assertExists()
        compose.onNodeWithTag(EngineTags.verdict("leak-scan")).assertTextEquals("FAIL (workflow completed/failure)")
        compose.onNodeWithText("last run 2026-11-28T10:00:00Z · 90s · push · #46066063430 · completed/failure").assertExists()
        compose.onNodeWithText("[gha] ${EnginesModel.REWRITE}").assertExists()
        compose.onNodeWithText("1_cicd/src/ops/cloud-cgc-verify-surfaces.sh  [shell]").assertExists()
        compose.onNodeWithTag(EngineTags.run("leak-scan")).performClick()
        compose.onNodeWithTag(EngineTags.run("cgc-surfaces")).performClick()
        assertEquals(listOf("leak-scan"), ran)
        compose.onNodeWithText("replaces 1_cicd/src/ops/cloud-health-all-vms.sh: ${EnginesModel.REPLACED.first { it.script == "cloud-health-all-vms.sh" }.why}").assertExists()
        compose.onNodeWithText("replaces 1_cicd/src/ops/cloud-health-full.sh: ${EnginesModel.REPLACED.first { it.script == "cloud-health-full.sh" }.why}").assertExists()
    }

    @Test fun `the VMs tab lists the three devices after the VMs, with no X-ray run chip and a tappable no-token line`() {
        val ns = XrayModel.State.NotServed("n")
        val dev = XrayModel.State.Device(HealthModel.NO_TOKEN_NOTE)
        compose.setContent {
            CloudKitTheme(palette) {
                XrayScreen(XrayItem("full", "X-ray C3 full", "", ns), listOf(XrayItem("oci-apps", "oci-apps", "per-VM X-ray", ns)), emptyList(), "", { _, _ -> }, { },
                    devices = listOf("SamsungA37", "SamsungS21", "SurfacePro8").map { XrayItem(it, it, "[app] X-Ray Device", dev, runnable = false) })
            }
        }
        compose.onNodeWithTag(XrayTags.tab("vms")).performClick()
        compose.onNodeWithTag(XrayTags.run("oci-apps")).assertExists()
        listOf("SamsungA37", "SamsungS21", "SurfacePro8").forEach {
            compose.onNodeWithTag(XrayTags.state(it)).assertExists()
            compose.onNodeWithTag(XrayTags.run(it)).assertDoesNotExist()
            compose.onNodeWithTag("xray_notoken_$it").assertExists()
        }
    }

    /** The real mail report drawn alone, two phases open: its pixels are written for the evidence (best effort, the asserts above are the proof). */
    @Test fun `the mail drill-down renders the real fields and is captured`() {
        val e = EnginesModel.ENGINES.first { it.id == "mail" }
        val item = EngineItem(e, null, EnginesModel.Body.Got(EnginesModel.parse(e, mailText)!!))
        compose.setContent { CloudKitTheme(palette) { Column(Modifier.verticalScroll(rememberScrollState())) { EnginesBody(listOf(item), emptyList(), "n/a") { } } } }
        compose.onNodeWithText("cloud-mail-health-full  [rust]").performClick()
        compose.onNodeWithTag(EngineTags.phase("mail", "e2e_delivery")).performClick()
        compose.onNodeWithTag(EngineTags.phase("mail", "config_drift")).performClick()
        compose.onNodeWithTag(EngineTags.check("mail", "e2e_delivery", 0)).assertExists()
        runCatching {
            val out = File(FleetFixtures.moduleDir(), "app/build/c3-1983").apply { mkdirs() }
            java.io.FileOutputStream(File(out, "c3-1983-mail-drill-down.png")).use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.diegonmarcos.cloudc3.pages.ContainerFilter
import com.diegonmarcos.cloudc3.pages.ContainersChrome
import com.diegonmarcos.cloudc3.pages.ContainersNav
import com.diegonmarcos.cloudc3.pages.ContainersTags
import com.diegonmarcos.superapp.navbartop2.SecondRowGroupTags
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** #1982 the Containers top nav DRAWN: the Projects row and the VM row, each led by its label, both visible at once, the VM chips in the declared order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContainersTwoRowsComposeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                ContainerFilter.reset()
                base.evaluate()
            }
        }
    }).around(compose)

    private val decl = Declarations.containerProjects
    private val all = Declarations.containersPages
    private val declared = all.filterNot { decl.isVmPage(it.id) }
    private val vmPages = all.filter { decl.isVmPage(it.id) }

    private fun show() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ContainersChrome(
                    categories = ContainersNav.categories(decl, declared), selectedCategory = "All", onCategory = {},
                    projects = emptyList(), selectedProject = null, onProject = {},
                    vmPages = vmPages, selectedVm = decl.vm.prefix + decl.vm.all, onVm = {},
                    views = declared.first().pages, selectedView = null, onView = {},
                ) { m -> Box(m) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `#1982 both rows render with their labels, Projects over VM, no toggle`() {
        show()
        compose.onNodeWithTag(ContainersTags.ROW_CATEGORY).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.row("projects")).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.row("vm")).assertIsDisplayed()
        compose.onNodeWithText("Projects ▾").assertIsDisplayed()
        compose.onNodeWithText("VM ▾").assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.divider("projects")).assertIsDisplayed()
        compose.onNodeWithTag(SecondRowGroupTags.divider("vm")).assertIsDisplayed()
        assertTrue("Projects is above VM", compose.onNodeWithTag(SecondRowGroupTags.row("projects")).getBoundsInRoot().top < compose.onNodeWithTag(SecondRowGroupTags.row("vm")).getBoundsInRoot().top)
        // All leads both rows
        assertEquals(2, compose.onAllNodesWithText("All").fetchSemanticsNodes().size)
        listOf("Unix", "CG", "Capital", "Hardtech", "Wiki", "oci-apps", "oci-analytics", "oci-mail", "gcp-proxy", "SurfacePro8", "SamsungA37", "SamsungS21", "Unknown host")
            .forEach { assertTrue("$it is a chip", compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()) }
    }

    @Test
    fun `#1982 the VM chips are drawn left to right in the declared host_order`() {
        show()
        val cp = Json.parseToJsonElement(File(FleetFixtures.moduleDir(), "data/container_projects.json").readText()).jsonObject["vm_view"]!!.jsonObject
        val short = cp["device_short"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val want = cp["host_order"]!!.jsonArray.map { it.jsonPrimitive.content }.map { short[it] ?: it } + "Unknown host"
        val xs = want.map { l -> compose.onAllNodesWithText(l)[0].getBoundsInRoot().left.value }
        assertEquals("the VM chips $want are not in drawn order: $xs", xs.sorted(), xs)
        val allX = compose.onAllNodesWithText("All")[1].getBoundsInRoot().left.value
        assertTrue("All leads the VM row", allX < xs.first())
    }
}

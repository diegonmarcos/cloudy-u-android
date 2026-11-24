package com.diegonmarcos.superapp.fleetconfigs

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1890 General never draws a section whose content has its own Configs tab (Perms, Agents, About, X-Ray, Logcat): decided from the tab
 * list, for every app of the fleet; and nothing is lost (rule 16): every row General's Perms section drew is in the Perms tab, every
 * other General section stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneralTabOwnedTest {
    @Before fun clean() = FleetConfigsExtensions.clearForTest()
    @After fun reset() = FleetConfigsExtensions.clearForTest()

    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "1_cicd/src/data/configs-pages.json").isFile) d = d.parentFile
        return requireNotNull(d) { "repo root not found from ${File("").absolutePath}" }
    }

    private fun pagesSrc(): String =
        File(root(), "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsPages.kt").readText()

    /** Every tab id of the Configs strip when all opt-in libs registered theirs. */
    private fun allTabIds(): Set<String> {
        listOf("agents", "logcat", "xray", "own").forEach { FleetConfigsExtensions.register(FleetConfigsTab(it, it, "x") { }) }
        return FleetConfigsExtensions.stripOrder().map { it.id }.toSet()
    }

    @Test fun general_section_ids_never_intersect_tab_ids_for_every_app() {
        val tabs = allTabIds() - "general"
        val apps = root().listFiles { f -> f.isDirectory && Regex("a[acd]_.*").matches(f.name) && File(f, "build.json").isFile }.orEmpty()
        var checked = 0
        for (app in apps) {
            val cp = JSONObject(File(app, "build.json").readText()).optJSONObject("configs_pages") ?: continue
            val sections = cp.optJSONObject("general")?.optJSONArray("sections") ?: continue
            val declared = (0 until sections.length()).map { sections.getJSONObject(it).getString("id") }
            assertTrue("${app.name} declares ${declared.intersect(tabs)} in general (own tab)", declared.intersect(tabs).isEmpty())
            val shown = FleetConfigs.parse("""{"general":${cp.getJSONObject("general")}}""").general.map { it.id }
            assertTrue("${app.name} General would draw ${shown.intersect(tabs)}", shown.intersect(tabs).isEmpty())
            checked++
        }
        assertTrue("fleet walk found only $checked apps", checked >= 30)
    }

    @Test fun a_declaration_naming_a_tab_is_filtered_by_rule_not_by_name() {
        FleetConfigsExtensions.register(FleetConfigsTab("custom", "Custom", "x") { })
        val d = FleetConfigs.parse("""{"general":{"sections":[{"id":"perms"},{"id":"agents"},{"id":"about"},{"id":"xray"},{"id":"logcat"},{"id":"custom"},{"id":"battery"}]}}""")
        assertEquals(listOf("update", "battery"), d.general.map { it.id })
    }

    @Test fun update_is_first_and_every_other_section_remains() {
        val ids = FleetConfigs.decode("").general.map { it.id }
        assertEquals(listOf("update", "battery", "storage", "notifications", "ui", "language"), ids)
        assertEquals(listOf("battery", "storage", "notifications", "ui", "language"), FleetConfigs.REQUIRED_SECTIONS)
    }

    @Test fun general_perms_rows_all_live_in_the_perms_tab() {
        val src = pagesSrc()
        // the one composable General's Perms section was: libs:perms AppPermsSection (every grant row + Grant all) and the App info action
        val section = src.substringAfter("private fun PermsSection(").substringBefore("@Composable")
        assertTrue(section.contains("AppPermsSection(tick,"))
        assertTrue(section.contains("ActionButton(\"App info\", c, \"fleetcfg_open_perms\""))
        // the Perms tab draws that very composable
        val tab = src.substringAfter("public fun FleetPermsPage(").substringBefore("// ──")
        assertTrue(tab.contains("PermsSection(c, tick)"))
        // and General no longer draws it
        val general = src.substringAfter("public fun FleetGeneralPage(").substringBefore("private fun AnchorGrid(")
        assertTrue(!general.contains("PermsSection("))
        // every other General section still has its body
        for (id in listOf("UPDATE_SECTION -> UpdateSection(", "\"battery\" -> BatterySection(", "\"storage\" -> StorageSection(",
            "\"notifications\" -> NotificationsSection(", "\"ui\" -> NavUiConfigCard(", "\"language\" -> LanguageSection(", "APP_SECTION -> ActionButton(")) {
            assertTrue("General lost $id", general.contains(id))
        }
    }
}

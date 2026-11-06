package com.diegonmarcos.uibaseline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.appTopBarLabels
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRow
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRows
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.themeBackground
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSamples
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * #1320 M4: the reference screenshots of the web mimic (cloudy-h-webapps-mob), rendered from the SAME shared libs and the SAME
 * build.json declarations the apps draw, at the web's Playwright sizes: phone 412x915 @2.625 (Pixel 7) and the Pixel Tablet
 * 1280x800 / 800x1280 @2. Files: build/outputs/roborazzi/<device>/<name>.png, zipped by screenshot-baseline.yml as
 * Cloud-UI-Baseline.zip on the rolling release. No assertion: the web repo's compare-baseline.mjs is the judge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class PhoneShots : BaselineShots("phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletLandShots : BaselineShots("tablet-land")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-xhdpi")
class TabletPortShots : BaselineShots("tablet-port")

abstract class BaselineShots(private val device: String) {
    companion object {
        private val repo = File(System.getProperty("baseline.repo") ?: "../../..")
        private fun build(dir: String) = JSONObject(File(repo, "$dir/build.json").readText())
        private val colors: Map<String, Long> by lazy {
            Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{8})</color>""")
                .findAll(File(repo, "aa_cloud-superapp/app/src/main/res/values/colors.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2].toLong(16) }
        }
        /** The launcher theme's palette, resolved from build.json ui.launcher_themes[id].palette -> colors.xml (LauncherPalette). */
        fun palette(themeId: String): KitPalette {
            val themes = build("aa_cloud-superapp").getJSONObject("ui").getJSONArray("launcher_themes")
            val t = (0 until themes.length()).map { themes.getJSONObject(it) }.first { it.getString("id") == themeId }.getJSONObject("palette")
            fun c(role: String) = Color(colors.getValue(t.getString(role)))
            return KitPalette(c("surface"), c("surface_selected"), c("text_primary"), c("text_secondary"), c("accent"), c("hairline"), c("tile_ink"))
        }
    }

    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        ShadowLog.stream = System.out
        compose.setContent(content)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$device/$name.png")
    }

    /** bg_gradient_black_purple (angle 270): #2D1B69 -> black -> #2D1B69 top to bottom. */
    private val window = Brush.verticalGradient(listOf(Color(0xFF2D1B69), Color.Black, Color(0xFF2D1B69)))

    @Test fun island() {
        val ui = build("aa_cloud-superapp").getJSONObject("ui")
        val sections = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val bar = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        shot("island") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val entries = bar.map { id ->
                val s = sections.first { it.getString("id") == id }
                val res = ctx.resources.getIdentifier(s.getString("icon"), "drawable", ctx.packageName)
                BottomNavEntry(id, s.getString("label"), painterResource(res))
            }
            Box(Modifier.fillMaxSize().background(window), contentAlignment = Alignment.BottomCenter) {
                @Suppress("DEPRECATION")
                BottomNavIsland(entries, "home", {})
            }
        }
    }

    @Test fun cloudTabs() {
        val cloud = build("aa_cloud-superapp").getJSONObject("ui").getJSONArray("sections").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "cloud" }
        }
        val pages = cloud.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { !it.optBoolean("hidden") }.map { NavPage(it.getString("id"), it.optString("label", it.getString("id")), it.optString("icon")) }
        shot("cloud-tabs") {
            Box(Modifier.fillMaxSize().background(window)) { @Suppress("DEPRECATION") PageTabs(pages, "apps", {}, flushTop = true) }
        }
    }

    @Test fun kitCloud() = kit("cloud")
    @Test fun kitMinimalist() = kit("cloud_minimalist_black")
    private fun kit(theme: String) = shot("kit-$theme") {
        CloudKitTheme(palette(theme)) {
            Box(Modifier.fillMaxSize().background(if (theme == "cloud") window else Brush.verticalGradient(listOf(Color.Black, Color.Black)))) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) { KitSamples.All() }
            }
        }
    }

    @Test fun fleetCalc() = fleet("ac_cloud-calc", "Cloud Calc", "calculator", "calc")
    @Test fun fleetTasks() = fleet("ac_cloud-tasks", "Cloud Tasks", "tasks", "calendar")
    @Test fun fleetC3() = fleet("ac_cloud-c3", "Cloud C3", "containers", "all_table")

    /** A fleet app's chrome as its shell draws it: NavBarTopFirstRow, NavBarTopSecondRow, the page, NavBarBottom. */
    private fun fleet(dir: String, app: String, sectionId: String, pageId: String) {
        val ui = build(dir).getJSONObject("ui")
        val decl = NavDecl.parse(ui.getJSONArray("sections"), ui.optJSONArray("bottom_nav")?.toString() ?: "", ui.optString("default_section"))
        val section = decl.section(sectionId)!!
        shot("fleet-${dir.removePrefix("ac_")}") {
            Column(Modifier.fillMaxSize().themeBackground(dark = true)) {
                NavBarTopFirstRow(
                    title = section.label, colors = ThemeBackground.colors(true), labels = appTopBarLabels(),
                    hierarchy = titleHierarchy(section, pageId), onNavigate = {}, onAi = {}, onProfile = {}, onConfigs = {},
                    appName = app, sections = decl.bottomSections(),
                )
                // the section's declared nav_mode: one row, or one row per group (horizontal_two_rows, e.g. Calc #1409), as the app draws it
                // a two-level section (C3 Containers: All | Cloudy | ... each holding <group>_topology | <group>_table): the shell selects the
                // first-row page that HOLDS the leaf and draws that page's own row under it; passing the leaf id selected nothing (#1539)
                val held = section.pages.firstOrNull { it.holdsPage(pageId) }
                if (section.pages.size > 1) NavBarTopSecondRows(section, held?.id ?: pageId, {}, Modifier.fillMaxWidth(), underTopChrome = false)
                if (held != null && held.id != pageId && held.pages.size > 1) NavBarTopSecondRow(held.pages, pageId, {}, Modifier.fillMaxWidth(), underTopChrome = false)
                Box(Modifier.weight(1f).fillMaxWidth())
                NavBarBottom(decl.islandEntries(), sectionId, {}, dark = true)
            }
        }
    }
}

private fun NavPage.holdsPage(id: String): Boolean = this.id == id || pages.any { it.holdsPage(id) }

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** #1651 the keyboard's nav: five bottom tabs in order, the fleet default bars, the default Configs tail plus the app's own tab. */
class KeyboardNavChromeTest {
    // Gradle runs a module's tests from the module directory (ab_cloud-libs-shared/libs/keyboard)
    private val app = File("../../../ac_cloud-keyboard")
    private fun appFile(path: String): String { assumeTrue(File(app, path).exists()); return File(app, path).readText() }
    private val nav get() = File("src/main/java/helium314/keyboard/settings/SettingsNavHost.kt").readText()

    @Test fun bottomNavTabsAreTheFiveInOrder() {
        assertEquals(listOf("Toolbar", "Themes", "Writer", "Autocomplete", "Clipboard"), BottomNavTab.entries.map { it.label })
    }

    @Test fun everyTabOpensAnExistingPageAndNoTwoShareOne() {
        assertEquals(BottomNavTab.entries.size, BottomNavTab.entries.map { it.route }.toSet().size)
        assertEquals(BottomNavTab.entries.size, BottomNavTab.entries.map { it.id }.toSet().size)
        BottomNavTab.entries.forEach {
            val const = Regex("""const val (\w+) = "${it.route}"""").find(nav)!!.groupValues[1]
            assertTrue(it.name, nav.contains("composable(SettingsDestination.$const)"))
        }
    }

    @Test fun topAndBottomUseTheSharedDefaultNavBars() {
        val chrome = appFile("app/src/main/java/com/diegonmarcos/cloudkeyboard/KeyboardChrome.kt")
        assertTrue(chrome.contains("import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold"))
        assertTrue(chrome.contains("import com.diegonmarcos.superapp.navbarbottom.NavBarBottom"))
        assertTrue(chrome.contains("NavBarTopFirstRowScaffold("))
        assertTrue(chrome.contains("NavBarBottom("))
        assertTrue(chrome.contains("BottomNavTab.entries")) // drawn from the declaration, not a bespoke list
        assertTrue(appFile("app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt").contains("KeyboardSettingsExtras.chrome"))
        assertTrue(nav.contains("KeyboardSettingsExtras.chrome"))
    }

    @Test fun configsShowsTheDefaultTailPlusTheAppsOwnTab() {
        // Agents, Logcat, X-Ray App are linked libs that register their tab (Perms and About come with ui-nav-bars)
        val gradle = appFile("app/build.gradle")
        val json = appFile("build.json")
        listOf("agents", "logcat", "xray").forEach {
            assertTrue("gradle $it", gradle.contains("implementation project(':libs:fleetconfigs-$it')"))
            assertTrue("build.json $it", json.contains("\"libs:fleetconfigs-$it\""))
        }
        assertTrue(gradle.contains("implementation project(':libs:ui-nav-bars')"))
        // the keyboard's own tab sits before the `|` and opens the grouped All settings (#1650)
        assertTrue(appFile("app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt").contains("FleetConfigsTab(\"keyboard\""))
        assertEquals(SettingsDestination.Settings, SettingsDestination.externalRoute("all_settings"))
    }

    @Test fun theChromeClearsTheStatusBarOnceAndPagesDrawNoSecondTitleBar() {
        // #1652 edge-to-edge window: the status-bar inset is applied (and consumed) by the chrome, not drawn over
        val chrome = appFile("app/src/main/java/com/diegonmarcos/cloudkeyboard/KeyboardChrome.kt")
        assertTrue(chrome.contains("Modifier.statusBarsPadding()"))
        assertTrue(chrome.contains("consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))"))
        // one top treatment: under the chrome a page draws its actions row (search stays), not a TopAppBar
        val search = File("src/main/java/helium314/keyboard/settings/SearchScreen.kt").readText()
        assertTrue(search.contains("if (KeyboardSettingsExtras.chromeActive) Row("))
        assertTrue(search.contains("actions = actionsRow"))
        assertTrue(File("src/main/java/helium314/keyboard/settings/KeyboardSettingsExtras.kt").readText().contains("val chromeActive: Boolean get() = chrome != null"))
    }
}

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

    private fun tab(t: KbTab) = t.allPages.map { it.label }

    @Test fun theFourBottomTabsAreInOrder() {
        assertEquals(listOf("Keyboard", "Writer", "Mediaboards", "Textboards"), KbTab.entries.map { it.label })
    }

    @Test fun keyboardHasThemesDefaultDevSettingsLanguagesAndToolbarInside() {
        val k = KbTab.KEYBOARD
        assertEquals(listOf("Themes", "Settings", "Languages", "Toolbar"), k.pages.map { it.label })
        assertEquals(listOf("Default", "Dev"), k.pages.first { it.label == "Themes" }.children.map { it.label })
        // Settings hosts the theme-config settings: Appearance and Advanced fold in; Secondary layouts fold under Languages
        assertTrue(tab(k).containsAll(listOf("Appearance", "Advanced")))
        assertEquals(listOf("Secondary layouts"), k.pages.first { it.label == "Languages" }.children.map { it.label })
    }

    @Test fun writerHasVoiceTranscriptionAndDictionary() {
        assertTrue(tab(KbTab.WRITER).containsAll(listOf("Voice Transcription", "Dictionary", "Enhancer", "Drafter", "Translator", "Grammar")))
    }

    @Test fun mediaboardsAndTextboardsSubpages() {
        assertEquals(listOf("Emojis", "GIF", "Stickers"), KbTab.MEDIABOARDS.pages.map { it.label })
        assertEquals(listOf("Autocomplete", "Clipboard"), KbTab.TEXTBOARDS.pages.map { it.label })
    }

    /** Rule 16: every page the old nav (#1651 five tabs, Emoji, Secondary layouts, Languages, Appearance, Advanced) reached is still reachable. */
    @Test fun everyPageReachableBeforeIsReachableAfter() {
        val before = setOf(
            SettingsDestination.Toolbar, SettingsDestination.Themes, SettingsDestination.TextEnhance, SettingsDestination.Autocomplete,
            SettingsDestination.Clipboard, SettingsDestination.Emoji, SettingsDestination.Layouts, SettingsDestination.Languages,
            SettingsDestination.Appearance, SettingsDestination.Advanced, SettingsDestination.Preferences, SettingsDestination.GestureTyping,
            SettingsDestination.Settings, SettingsDestination.DraftAnswer, SettingsDestination.Translation, SettingsDestination.GrammarCheck,
            SettingsDestination.AiRouting, SettingsDestination.VoiceTranscript, SettingsDestination.TextResume, SettingsDestination.Dictionaries,
            SettingsDestination.PersonalDictionaries,
        )
        val after = KbTab.entries.flatMap { it.allPages }.map { it.route }.toSet()
        assertEquals(emptySet<String>(), before - after)
        // each declared route is an existing screen, and no page id repeats
        after.forEach { r -> assertTrue(r, nav.contains("composable(SettingsDestination.${Regex("""const val (\w+) = "$r"""").find(nav)!!.groupValues[1]})")) }
        val ids = KbTab.entries.flatMap { it.allPages }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun topAndBottomUseTheSharedDefaultNavBars() {
        val chrome = appFile("app/src/main/java/com/diegonmarcos/cloudkeyboard/KeyboardChrome.kt")
        assertTrue(chrome.contains("import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold"))
        assertTrue(chrome.contains("import com.diegonmarcos.superapp.navbarbottom.NavBarBottom"))
        assertTrue(chrome.contains("NavBarTopFirstRowScaffold("))
        assertTrue(chrome.contains("NavBarBottom("))
        assertTrue(chrome.contains("KbTab.entries")) // drawn from the declaration, not a bespoke list
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

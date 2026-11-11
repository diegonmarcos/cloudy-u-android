// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1650 field parity: the All settings reorg dropped no subpage; each sits under exactly one of Keyboard / Text / Configs. */
class AllSettingsSectionsParityTest {
    /** The subpages of All settings BEFORE the reorg (13 ConfigsEntry rows, the host extras, the 10 base-menu rows). */
    private val before = setOf(
        "TOOLBAR", "CLIPBOARD", "AUTOCOMPLETE", "EMOJI", "TRANSLATION", "TEXT_ENHANCE", "DRAFT_ANSWER", "GRAMMAR_CHECK",
        "VOICE_TRANSCRIPT", "AI_ROUTING", "DEV_KEYBOARD", "ANDROID_SETTINGS", "UPDATE",
        "HOST_EXTRAS",
        "LANGUAGES", "DICTIONARIES", "PREFERENCES", "ADVANCED", "SECONDARY_LAYOUTS", "APPEARANCE",
        "GESTURE_TYPING", "DATA_GATHERING", "TEXT_CORRECTION", "ABOUT",
    )

    @Test fun everySubpageFromBeforeIsStillPresentAndNothingWasAdded() {
        assertEquals(before, AllSettingsPage.entries.map { it.name }.toSet())
        assertEquals(before.size, AllSettingsPage.entries.size)
    }

    @Test fun everySubpageIsUnderExactlyOneSection() {
        val grouped = HomeGroup.entries.flatMap { AllSettingsPage.of(it) }
        assertEquals(AllSettingsPage.entries.size, grouped.size) // none twice, none ungrouped
        assertEquals(before, grouped.map { it.name }.toSet())
    }

    @Test fun keyboardTextConfigsAreTheOnlySections() {
        assertEquals(listOf("KEYBOARD", "TEXT", "CONFIGS"), HomeGroup.entries.map { it.name })
        assertEquals(HomeGroup.entries.toSet(), AllSettingsPage.entries.map { it.section }.toSet())
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains(">Keyboard</string>") && strings.contains(">Text</string>") && strings.contains(">Configs</string>"))
    }

    @Test fun everyEntryOfTheOldConfigsListIsStillAPage() {
        ConfigsEntry.entries.forEach { assertTrue(it.name, AllSettingsPage.entries.any { p -> p.name == it.name }) }
    }

    @Test fun theScreenDrawsEveryPageUnderItsSectionHeading() {
        val screen = File("src/main/java/helium314/keyboard/settings/screens/MainSettingsScreen.kt").readText()
        assertTrue(screen.contains("PreferenceCategory(stringResource(group.title))"))
        AllSettingsPage.entries.forEach { assertTrue(it.name, screen.contains("AllSettingsPage.${it.name} ->")) }
    }
}

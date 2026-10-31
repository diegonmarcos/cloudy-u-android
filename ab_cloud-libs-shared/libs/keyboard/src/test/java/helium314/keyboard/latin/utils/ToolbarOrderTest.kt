// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.FakePrefs
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.DefaultsMigration
import helium314.keyboard.latin.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1103 item 6: the first-row order, the new Android keyboard settings key, and the migration that carries it to phones that have the old order. */
class ToolbarOrderTest {
    private fun enabled(pref: String) = pref.split(Separators.ENTRY).filter { it.endsWith("true") }.map { it.substringBefore(Separators.KV) }

    private val row get() = enabled(defaultToolbarPref)

    @Test fun autocompleteAndTranslateSwappedPlaces() {
        // before #1103: FULL_LEFT, GRAMMAR, AUTOCOMPLETE, ENHANCE, TRANSLATE
        assertEquals(listOf("FULL_LEFT", "GRAMMAR", "TRANSLATE", "ENHANCE", "AUTOCOMPLETE"), row.take(5))
    }

    @Test fun clipboardAndNumpadSwappedPlaces() {
        // before #1103: ... NUMPAD, CLIPBOARD, EMOJI; #1384: Draft Answer took Emoji's place
        assertEquals(listOf("CLIPBOARD", "NUMPAD", "ANSWER"), row.subList(5, 8))
    }

    @Test fun draftAnswerTakesEmojisPlaceAndEmojiOpensPageTwoAfterVault() {
        // #1384: page one is the first 11 keys (11 / 11 / 2 at the shipped width)
        assertTrue("ANSWER" in row.take(11))
        assertFalse("EMOJI" in row.take(11))
        assertEquals(listOf("VAULT", "EMOJI"), row.subList(11, 13))
        assertEquals(1, row.count { it == "ANSWER" })
        val source = java.io.File("src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt").readText()
        assertTrue(source.contains("ANSWER -> KeyCode.ANSWER_BAR"))
    }

    @Test fun vaultFollowsSwitchLanguageAndAndroidSettingsComesRightBeforeVault() {
        val i = row.indexOf("LANGUAGE_SWITCH")
        assertEquals(listOf("LANGUAGE_SWITCH", "ANDROID_SETTINGS", "VAULT"), row.subList(i, i + 3))
        assertEquals(1, row.count { it == "ANDROID_SETTINGS" })
        assertEquals(1, row.count { it == "VAULT" })
    }

    @Test fun theWholeFirstRowIsExactlyThis() {
        assertEquals(listOf("FULL_LEFT", "GRAMMAR", "TRANSLATE", "ENHANCE", "AUTOCOMPLETE", "CLIPBOARD", "NUMPAD", "ANSWER", "VOICE",
            "LANGUAGE_SWITCH", "ANDROID_SETTINGS", "VAULT", "EMOJI", "FULL_RIGHT", "SELECT_ALL", "WORD_RIGHT", "WORD_LEFT", "REDO", "UNDO",
            "SELECT_WORD", "COPY", "PASTE", "CUT", "PAGE_START"), row)
    }

    @Test fun noKeyIsForcedOntoAPageOfItsOwnAnyMore() {
        assertTrue(toolbarKeysOnOwnPage.isEmpty())
    }

    @Test fun theNewKeyIsADeclaredToolbarKeyWithACode() {
        assertTrue(ToolbarKey.ANDROID_SETTINGS in ToolbarKey.entries)
        assertTrue("it is among the disabled-by-default keys of the other rows",
            defaultSecondRowToolbarPref.contains("ANDROID_SETTINGS" + Separators.KV + "false"))
        // the key's code is declared in ToolbarUtils (reading it would need the Settings singleton, which the JVM suite has not)
        val source = java.io.File("src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt").readText()
        assertTrue(source.contains("ANDROID_SETTINGS -> KeyCode.ANDROID_IME_SETTINGS"))
    }

    @Test fun theLayoutRevisionIsBumpedSoPhonesWithTheOldOrderMoveOnce() {
        assertEquals(5, TOOLBAR_LAYOUT_REVISION) // 5: #1384 Draft Answer on page one, Emoji to page two
        val old = FakePrefs(mapOf(Settings.PREF_TOOLBAR_LAYOUT_REVISION to 4, Settings.PREF_TOOLBAR_KEYS to "FULL_LEFT,GRAMMAR,AUTOCOMPLETE"))
        assertTrue(toolbarLayoutRevisionPending(old))
        adoptToolbarLayoutRevision(old)
        assertEquals(defaultToolbarPref, old.getString(Settings.PREF_TOOLBAR_KEYS, null))
        assertEquals(5, old.getInt(Settings.PREF_TOOLBAR_LAYOUT_REVISION, 0))
        assertFalse(toolbarLayoutRevisionPending(old))
        // a phone already on revision 5 keeps what it has (the user may have re-arranged it since)
        val mine = FakePrefs(mapOf(Settings.PREF_TOOLBAR_LAYOUT_REVISION to 5, Settings.PREF_TOOLBAR_KEYS to "COPY,true"))
        adoptToolbarLayoutRevision(mine)
        assertEquals("COPY,true", mine.getString(Settings.PREF_TOOLBAR_KEYS, null))
    }

    @Test fun theDefaultsMigrationDrivesTheToolbarStepOnceAndDoesNotRepeatTheEarlierGroups() {
        assertEquals(2, DefaultsMigration.REVISION)
        assertTrue(DefaultsMigration.adoptsToolbarLayout(0))
        assertTrue(DefaultsMigration.adoptsToolbarLayout(1)) // a phone migrated by #1077 gets the new rows
        assertFalse(DefaultsMigration.adoptsToolbarLayout(2))
        // a phone at revision 1 whose space-bar choice is true on purpose is NOT reset again by the old group
        val stored = mapOf(Settings.PREF_SPACE_TO_CHANGE_LANG to true)
        assertTrue(DefaultsMigration.keysToReset(stored, fromRevision = 1).isEmpty())
        assertEquals(listOf(Settings.PREF_SPACE_TO_CHANGE_LANG), DefaultsMigration.keysToReset(stored, fromRevision = 0))

        val prefs = FakePrefs(mapOf(Settings.PREF_DEFAULTS_REVISION to 1, Settings.PREF_TOOLBAR_LAYOUT_REVISION to 3,
            Settings.PREF_TOOLBAR_KEYS to "OLD,true", Settings.PREF_SPACE_TO_CHANGE_LANG to true))
        DefaultsMigration.apply(prefs)
        assertEquals(defaultToolbarPref, prefs.getString(Settings.PREF_TOOLBAR_KEYS, null))
        assertEquals(defaultSecondRowToolbarPref, prefs.getString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, null))
        assertEquals(true, prefs.getBoolean(Settings.PREF_SPACE_TO_CHANGE_LANG, false))
        assertEquals(2, prefs.getInt(Settings.PREF_DEFAULTS_REVISION, 0))
        // running again changes nothing
        val after = prefs.data.toMap()
        DefaultsMigration.apply(prefs)
        assertEquals(after, prefs.data.toMap())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.common.Constants.Separators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1793 parity (rule 16, never simplify): the reorganised toolbar of TOOLBAR_LAYOUT_REVISION 9 keeps EVERY entry of revision 8.
 * Revision 8 is written out below, exactly as it shipped (ToolbarUtils at #1781); the only change allowed is order, plus the one
 * new key (GRABBER). Each key keeps its icon, its tap code and its enabled state, and the other rows and the customiser lose
 * nothing either.
 */
class ToolbarParityTest {
    private fun entries(pref: String) = pref.split(Separators.ENTRY).map { it.substringBefore(Separators.KV) to it.substringAfter(Separators.KV).toBoolean() }
    private fun enabled(pref: String) = entries(pref).filter { it.second }.map { it.first }

    /** The first row of revision 8 (#1781), enabled, in order. */
    private val revision8Row = listOf(
        "FULL_LEFT", "GRAMMAR", "TRANSLATE", "ENHANCE", "AUTOCOMPLETE", "CLIPBOARD", "NUMPAD", "ANSWER", "VOICE", "LANGUAGE_SWITCH",
        "ANDROID_SETTINGS", "VAULT", "FULL_RIGHT", "SELECT_ALL", "WORD_RIGHT", "WORD_LEFT", "REDO", "UNDO", "SELECT_WORD", "COPY",
        "PASTE", "PAGE_START", "CUT", "EMOJI", "DEV_KEYBOARD", "HANDWRITE",
    )

    /** Every ToolbarKey of revision 8. */
    private val revision8Keys = listOf(
        "VOICE", "CLIPBOARD", "NUMPAD", "UNDO", "REDO", "SETTINGS", "SELECT_ALL", "SELECT_WORD", "COPY", "CUT", "PASTE", "ONE_HANDED",
        "FLOATING", "SPLIT", "INCOGNITO", "CLEAR_CLIPBOARD", "CLOSE_HISTORY", "EMOJI", "LEFT", "RIGHT", "UP", "DOWN", "WORD_LEFT",
        "WORD_RIGHT", "PAGE_UP", "PAGE_DOWN", "FULL_LEFT", "FULL_RIGHT", "PAGE_START", "PAGE_END", "BACKGROUND_GATHERING", "TRANSLATE",
        "GRAMMAR", "AUTOCOMPLETE", "ENHANCE", "LANGUAGE_SWITCH", "VAULT", "ANDROID_SETTINGS", "ANSWER", "DEV_KEYBOARD", "HANDWRITE",
    )

    @Test fun noKeyOfRevision8LeftTheEnum() {
        val now = ToolbarKey.entries.map { it.name }
        val lost = revision8Keys - now.toSet()
        assertTrue("toolbar keys lost: $lost", lost.isEmpty())
        assertEquals("only GRABBER is new", listOf("GRABBER"), now - revision8Keys.toSet())
    }

    @Test fun theFirstRowKeepsEveryEnabledKeyAndAddsOnlyGrabber() {
        val now = enabled(defaultToolbarPref)
        val lost = revision8Row - now.toSet()
        assertTrue("first-row keys lost: $lost", lost.isEmpty())
        assertEquals(listOf("GRABBER"), now - revision8Row.toSet())
        assertEquals(revision8Row.size + 1, now.size)
        assertEquals("no key twice", now.size, now.toSet().size)
    }

    @Test fun theFirstRowCustomiserStillListsEveryOtherKeyDisabled() {
        val all = entries(defaultToolbarPref).map { it.first }
        val expected = ToolbarKey.entries.map { it.name }.filterNot { it == "CLOSE_HISTORY" || it == "CLEAR_CLIPBOARD" }
        assertEquals(expected.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun theSecondRowAndTheClipboardRowLoseNothing() {
        assertEquals(listOf("SETTINGS"), enabled(defaultSecondRowToolbarPref))
        assertEquals(ToolbarKey.entries.map { it.name }.filterNot { it == "CLOSE_HISTORY" || it == "CLEAR_CLIPBOARD" }.toSet(),
            entries(defaultSecondRowToolbarPref).map { it.first }.toSet())
        assertEquals(listOf("UP", "DOWN", "LEFT", "RIGHT", "UNDO", "CUT", "COPY", "PASTE", "SELECT_WORD", "CLOSE_HISTORY"), enabled(defaultClipboardToolbarPref))
        assertTrue("GRABBER is offered in the clipboard-row customiser too", entries(defaultClipboardToolbarPref).any { it.first == "GRABBER" && !it.second })
    }

    @Test fun everyKeyKeepsItsIconAndItsTapCode() {
        val icons = File("src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt").readText()
        val utils = File("src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt").readText()
        // the three icon maps (default, Material, Holo) each name every key; the `when` over the enum is exhaustive at compile time
        for (k in ToolbarKey.entries) {
            assertEquals("$k has an icon in each of the 3 theme maps", 3, Regex("""ToolbarKey\.${k.name} ->""").findAll(icons).count())
        }
        assertTrue(utils.contains("GRABBER -> KeyCode.GRABBER"))
        assertTrue(utils.contains("DEV_KEYBOARD -> KeyCode.DEV_KEYBOARD"))
        assertTrue(utils.contains("HANDWRITE -> KeyCode.HANDWRITE"))
        assertTrue(utils.contains("VAULT -> KeyCode.VAULT"))
        assertTrue(File("src/main/res/drawable/ic_toolbar_grabber.xml").isFile)
        assertTrue("Dev Keyboard wears the terminal glyph", File("src/main/res/drawable/ic_toolbar_dev_keyboard.xml").readText().contains("\"terminal\""))
    }
}

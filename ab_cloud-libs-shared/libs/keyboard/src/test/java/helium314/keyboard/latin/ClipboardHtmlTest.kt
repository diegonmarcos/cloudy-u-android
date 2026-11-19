// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1813: clipboard history keeps the HTML half of a copied clip. */
class ClipboardHtmlTest {
    private val html = "<b>bold</b> text"

    @Test fun htmlIsStoredWhenPresentAndToggleOn() {
        assertEquals(html, ClipboardHtml.htmlToStore(true, html))
    }

    @Test fun htmlIsNotStoredWhenToggleOff() {
        assertNull(ClipboardHtml.htmlToStore(false, html))
    }

    @Test fun blankOrMissingHtmlIsNotStored() {
        assertNull(ClipboardHtml.htmlToStore(true, null))
        assertNull(ClipboardHtml.htmlToStore(true, "  \n"))
    }

    @Test fun htmlEntryWithToggleOnPastesAsHtmlClip() {
        assertEquals(ClipboardHtml.PasteMode.HTML_CLIP_THEN_PASTE, ClipboardHtml.pasteMode(true, html, false))
    }

    @Test fun otherwiseTheTextIsCommittedAsToday() {
        assertEquals(ClipboardHtml.PasteMode.COMMIT_TEXT, ClipboardHtml.pasteMode(false, html, false)) // toggle off
        assertEquals(ClipboardHtml.PasteMode.COMMIT_TEXT, ClipboardHtml.pasteMode(true, null, false)) // plain entry
        assertEquals(ClipboardHtml.PasteMode.COMMIT_TEXT, ClipboardHtml.pasteMode(true, "", false))
        assertEquals(ClipboardHtml.PasteMode.COMMIT_TEXT, ClipboardHtml.pasteMode(true, html, true)) // file clip
    }

    @Test fun keepHtmlIsOnByDefault() {
        assertTrue(ClipboardHtml.DEFAULT_KEEP_HTML)
        val src = File("src/main/java/helium314/keyboard/latin/settings")
        assertTrue(File(src, "Defaults.kt").readText().contains("PREF_CLIPBOARD_KEEP_HTML = helium314.keyboard.latin.ClipboardHtml.DEFAULT_KEEP_HTML"))
        assertTrue(File(src, "Settings.java").readText().contains("PREF_CLIPBOARD_KEEP_HTML = \"${ClipboardHtml.PREF_KEEP_HTML}\""))
    }

    @Test fun settingsScreenAndPanelDriveTheSamePref() {
        val main = File("src/main/java/helium314/keyboard")
        val store = mutableMapOf<String, Boolean>()
        val read = { k: String, d: Boolean -> store[k] ?: d }
        assertTrue(ClipboardHtml.isKeepHtml(read)) // default on in both places
        val settings = File(main, "settings/screens/ClipboardSettingsScreen.kt").readText()
        val panel = File(main, "keyboard/clipboard/ClipboardHistoryView.kt").readText()
        assertTrue(settings.contains("Settings.PREF_CLIPBOARD_KEEP_HTML"))
        assertTrue(File(main, "latin/settings/Settings.java").readText().contains("PREF_CLIPBOARD_KEEP_HTML = \"${ClipboardHtml.PREF_KEEP_HTML}\""))
        assertTrue(panel.contains("putBoolean(ClipboardHtml.PREF_KEEP_HTML") && panel.contains("ClipboardHtml.isKeepHtml("))
        // the panel writes, the settings switch (same key) reads it, and the other way round
        store[ClipboardHtml.PREF_KEEP_HTML] = ClipboardHtml.toggled(ClipboardHtml.isKeepHtml(read))
        assertEquals(false, ClipboardHtml.isKeepHtml(read))
        store[ClipboardHtml.PREF_KEEP_HTML] = true // switched on in the settings screen
        assertEquals(true, ClipboardHtml.isKeepHtml(read))
    }

    @Test fun migrationKeepsOldRowsWithNullHtml() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                // the schema at database version 4, as shipped before this change
                s.execute("CREATE TABLE CLIPBOARD (ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER NOT NULL, PINNED TINYINT NOT NULL, TEXT TEXT)")
                s.execute("ALTER TABLE CLIPBOARD ADD COLUMN FILE TEXT")
                s.execute("ALTER TABLE CLIPBOARD ADD COLUMN MIME_TYPE TEXT")
                s.execute("ALTER TABLE CLIPBOARD ADD COLUMN LIST_NAME TEXT")
                s.execute("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT, LIST_NAME) VALUES (42, 1, 'old clip', 'Pins-1')")
                ClipboardHtml.migrationStatements(4).forEach { s.execute(it) }
                s.executeQuery("SELECT TIMESTAMP, TEXT, LIST_NAME, HTML FROM CLIPBOARD").use { r ->
                    assertTrue(r.next())
                    assertEquals(42L, r.getLong(1))
                    assertEquals("old clip", r.getString(2))
                    assertEquals("Pins-1", r.getString(3))
                    assertNull(r.getString(4))
                    assertTrue(!r.next())
                }
            }
        }
        assertTrue(ClipboardHtml.migrationStatements(ClipboardHtml.DB_VERSION_WITH_HTML).isEmpty())
    }
}

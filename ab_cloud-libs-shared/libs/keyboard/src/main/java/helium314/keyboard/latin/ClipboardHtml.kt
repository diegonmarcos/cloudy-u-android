// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

/**
 * #1813 Pure decisions for keeping the HTML half of a copied clip: what to store, how to paste an
 * entry, the pref and the schema step. No Android types, so the JVM tests cover them.
 */
object ClipboardHtml {
    const val PREF_KEEP_HTML = "clipboard_keep_html"
    const val DEFAULT_KEEP_HTML = true

    /** The toggle's value in [getBoolean] (a SharedPreferences, or any stand-in): the settings screen and the clipboard panel both read it here. */
    fun isKeepHtml(getBoolean: (String, Boolean) -> Boolean): Boolean = getBoolean(PREF_KEEP_HTML, DEFAULT_KEEP_HTML)

    /** The value a tap on the toggle writes under [PREF_KEEP_HTML]. */
    fun toggled(current: Boolean): Boolean = !current

    /** heliboard.db version that added the nullable HTML column of the clipboard table. */
    const val DB_VERSION_WITH_HTML = 5
    const val ADD_HTML_COLUMN = "ALTER TABLE CLIPBOARD ADD COLUMN HTML TEXT"

    /** The statements that bring a clipboard table written at [oldVersion] up to the HTML schema; existing rows are kept. */
    fun migrationStatements(oldVersion: Int): List<String> =
        if (oldVersion < DB_VERSION_WITH_HTML) listOf(ADD_HTML_COLUMN) else emptyList()

    /** The HTML to save next to the text: only when the toggle is on and the clip item carries non-blank HTML. */
    fun htmlToStore(keepHtml: Boolean, htmlText: String?): String? =
        if (keepHtml && !htmlText.isNullOrBlank()) htmlText else null

    enum class PasteMode { HTML_CLIP_THEN_PASTE, COMMIT_TEXT }

    /** A tap pastes as HTML only for an entry that has HTML while the toggle is on; everything else is today's text commit. */
    fun pasteMode(keepHtml: Boolean, entryHtml: String?, hasFile: Boolean): PasteMode =
        if (keepHtml && !hasFile && !entryHtml.isNullOrBlank()) PasteMode.HTML_CLIP_THEN_PASTE else PasteMode.COMMIT_TEXT
}

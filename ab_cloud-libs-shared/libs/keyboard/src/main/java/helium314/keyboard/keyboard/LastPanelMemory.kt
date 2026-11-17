// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.SharedPreferences
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * The panel the user was last on (letters, numpad, Clipboard, Autocomplete), kept across keyboard
 * hides and IME process deaths. Cloud Keyboard addition, not upstream. Written with commit()
 * (the IME process gets killed right after a hide, apply() would lose the write); read back in
 * onStartInputView. A field whose inputType demands its own layout (number / phone / password)
 * wins for that session and leaves the memory untouched; only a manual switch rewrites it.
 */
class LastPanelMemory(private val prefs: SharedPreferences) {
    enum class Panel { LETTERS, NUMPAD, CLIPBOARD, AUTOCOMPLETE }

    fun remembered(): Panel =
        Panel.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: Panel.LETTERS

    /** Called on a manual switch only. */
    fun remember(panel: Panel): Boolean = prefs.edit().putString(KEY, panel.name).commit()

    /** The panel to reopen for a field of [inputType], or null when the field's own layout wins. */
    fun panelToRestore(inputType: Int): Panel? = if (fieldDemandsPanel(inputType)) null else remembered()

    companion object {
        const val KEY = "last_keyboard_panel"

        private const val CLASS_NUMBER = 0x2
        private const val CLASS_PHONE = 0x3
        private const val CLASS_MASK = 0xf

        fun fieldDemandsPanel(inputType: Int): Boolean {
            val cls = inputType and CLASS_MASK
            return cls == CLASS_NUMBER || cls == CLASS_PHONE || InputTypeUtils.isAnyPasswordInputType(inputType)
        }
    }
}

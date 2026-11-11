// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import helium314.keyboard.keyboard.CornerSwipe
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * #1622: the keyboard's Themes, declared once. Today there are two: the ordinary keyboard ([DEFAULT]) and
 * [UNEXPECTED], which restyles the keyboard to match the Unexpected Keyboard app — dark keys, white centred
 * glyphs (the [KeyboardTheme.THEME_UNEXPECTED] colours) and the corner-swipe legends of the "unexpected"
 * main layout (the same flag the Dev Keyboard toggle drives). The Themes picker screen draws
 * [entries] in order, so a new theme is one enum constant plus its branch in [apply].
 */
enum class KbThemePreset(
    val id: String,
    @StringRes val label: Int,
    @StringRes val summary: Int,
) {
    DEFAULT("default", R.string.kb_theme_default, R.string.kb_theme_default_summary),
    UNEXPECTED("unexpected", R.string.kb_theme_unexpected, R.string.kb_theme_unexpected_summary);

    companion object {
        const val PREF = "pref_kb_theme_preset"
        // Where the colour theme chosen before Unexpected was applied is parked, so DEFAULT can put it back
        // rather than guessing. Absent until Unexpected is first applied.
        private const val PREV_DAY = "pref_kb_theme_prev_colors"
        private const val PREV_NIGHT = "pref_kb_theme_prev_colors_night"

        /** The theme currently selected, or [DEFAULT] when none has been chosen. */
        fun current(context: Context): KbThemePreset =
            context.prefs().getString(PREF, DEFAULT.id).let { id -> entries.firstOrNull { it.id == id } ?: DEFAULT }

        /** Select [preset]: swap the colour theme and the corner-swipe flag, then redraw the keyboard. */
        fun apply(context: Context, preset: KbThemePreset) {
            val prefs = context.prefs()
            val wasUnexpected = current(context) == UNEXPECTED
            when (preset) {
                UNEXPECTED -> {
                    if (!wasUnexpected) prefs.edit {
                        putString(PREV_DAY, prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))
                        putString(PREV_NIGHT, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT))
                    }
                    prefs.edit {
                        putString(PREF, UNEXPECTED.id)
                        putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_UNEXPECTED)
                        putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_UNEXPECTED)
                        putBoolean(CornerSwipe.PREF, true)
                    }
                    CornerSwipe.enabled = true
                }
                DEFAULT -> {
                    val day = prefs.getString(PREV_DAY, Defaults.PREF_THEME_COLORS) ?: Defaults.PREF_THEME_COLORS
                    val night = prefs.getString(PREV_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) ?: Defaults.PREF_THEME_COLORS_NIGHT
                    prefs.edit {
                        putString(PREF, DEFAULT.id)
                        // Only undo the colour theme if Unexpected itself set it — never clobber a user's own pick.
                        if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_UNEXPECTED)
                            putString(Settings.PREF_THEME_COLORS, day)
                        if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == KeyboardTheme.THEME_UNEXPECTED)
                            putString(Settings.PREF_THEME_COLORS_NIGHT, night)
                        putBoolean(CornerSwipe.PREF, false)
                    }
                    CornerSwipe.enabled = false
                }
            }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    }
}

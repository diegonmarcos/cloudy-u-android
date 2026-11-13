// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import helium314.keyboard.keyboard.CornerSwipe
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.UnexpectedKb
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs

/**
 * #1622: the keyboard's Themes, declared once: the ordinary keyboard ([DEFAULT]), [UNEXPECTED] (the Unexpected keyboard rebuilt from
 * a data file) and, since #1665,
 * [DEV], the developer theme designed after the Unexpected Keyboard app — dark keys, white centred
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
    // #1665 named Dev in the UI; its stored id stays "unexpected" so a phone that picked it before keeps it
    DEV("unexpected", R.string.kb_theme_dev, R.string.kb_theme_dev_summary),
    // the "Unexpected" theme (its own keyboard: assets/unexpected_kb/layout.json). Its stored id differs from DEV's legacy "unexpected"
    UNEXPECTED("unexpected_kb", R.string.kb_theme_unexpected, R.string.kb_theme_unexpected_summary);

    /** Both dark themes (Dev, Unexpected) draw with the Unexpected colours and switch the corner swipes on. */
    val isDark: Boolean get() = this != DEFAULT

    /** #1665 the nav page id (Keyboard > Themes > Default / Dev) and route of this theme's own page. */
    val pageId: String get() = "themes_" + name.lowercase()
    val route: String get() = SettingsDestination.themeRoute(this)

    companion object {
        const val PREF = "pref_kb_theme_preset"
        // Where the colour theme chosen before Unexpected was applied is parked, so DEFAULT can put it back
        // rather than guessing. Absent until Unexpected is first applied.
        private const val PREV_DAY = "pref_kb_theme_prev_colors"
        private const val PREV_NIGHT = "pref_kb_theme_prev_colors_night"
        // the style and key borders in use before Unexpected, parked the same way (Unexpected draws rounded, bordered keys)
        private const val PREV_STYLE = "pref_kb_theme_prev_style"
        private const val PREV_BORDERS = "pref_kb_theme_prev_borders"
        // the toolbar mode before Unexpected (it hides the toolbar / clipboard strip so the five rows get the design's height)
        private const val PREV_TOOLBAR = "pref_kb_theme_prev_toolbar_mode"

        /** The theme currently selected, or [DEFAULT] when none has been chosen. */
        fun current(context: Context): KbThemePreset = current(context.prefs())

        /** #1665 [current] on a prefs store (pure, testable). */
        fun current(prefs: SharedPreferences): KbThemePreset =
            prefs.let { p ->
                // a phone that only ever flipped Dev Keyboard (before #1665 joined the two) has no theme stored: the flag decides
                p.getString(PREF, null) ?: if (p.getBoolean(CornerSwipe.PREF, CornerSwipe.DEFAULT)) DEV.id else DEFAULT.id
            }.let { id -> entries.firstOrNull { it.id == id } ?: DEFAULT }

        /** #1665 the colour theme [preset] draws with: Dev its own; Default the user's pick (parked while Dev is on). */
        fun colorThemeName(context: Context, preset: KbThemePreset): String {
            val prefs = context.prefs()
            return when {
                preset.isDark -> KeyboardTheme.THEME_UNEXPECTED
                current(prefs).isDark -> prefs.getString(PREV_DAY, Defaults.PREF_THEME_COLORS) ?: Defaults.PREF_THEME_COLORS
                else -> prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) ?: Defaults.PREF_THEME_COLORS
            }.let { if (preset == DEFAULT && it == KeyboardTheme.THEME_UNEXPECTED) Defaults.PREF_THEME_COLORS else it }
        }

        /** #1665 Dev Keyboard on / off (toolbar key, Configs switch) = the Dev / Default theme. */
        fun setDev(context: Context, on: Boolean) = apply(context, if (on) DEV else DEFAULT)

        /** Select [preset]: swap the colour theme and the corner-swipe flag, then redraw the keyboard. */
        fun apply(context: Context, preset: KbThemePreset) {
            applyTo(context.prefs(), preset)
            CornerSwipe.enabled = preset.isDark
            UnexpectedKb.fn = false
            CornerSwipe.refresh(context) // re-reads the prefs just written: the new theme's flag is current before the views are rebuilt
            // #1665 the switch is real: drop the cached layouts too, so the Dev layout (or the subtype's own) loads on the next draw
            runCatching { helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser.clearCache() }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }

        /** #1665 the prefs half of [apply] (pure, testable): theme id, colour themes (the user's own parked and restored), Dev flag. */
        fun applyTo(prefs: SharedPreferences, preset: KbThemePreset) {
            val wasDev = current(prefs).isDark
            when (preset) {
                DEV, UNEXPECTED -> {
                    if (preset == UNEXPECTED && !prefs.contains(PREV_STYLE)) prefs.edit {
                        putString(PREV_STYLE, prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE))
                        putBoolean(PREV_BORDERS, prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS))
                        putString(PREV_TOOLBAR, prefs.getString(Settings.PREF_TOOLBAR_MODE, Defaults.PREF_TOOLBAR_MODE))
                    }
                    if (!wasDev) prefs.edit {
                        putString(PREV_DAY, prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))
                        putString(PREV_NIGHT, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT))
                    }
                    prefs.edit {
                        putString(PREF, preset.id)
                        putBoolean(UnexpectedKb.PREF, preset == UNEXPECTED)
                        if (preset == UNEXPECTED) { // dark grey rounded keys with visible key backgrounds, as designed
                            putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_ROUNDED)
                            putBoolean(Settings.PREF_THEME_KEY_BORDERS, true)
                            // the toolbar and clipboard strip are hidden (Keyboard > Toolbar brings them back; the clipboard, emoji and settings
                            // keys are on the keyboard itself), so the five rows get the design's height
                            putString(Settings.PREF_TOOLBAR_MODE, ToolbarMode.HIDDEN.name)
                        } else if (prefs.contains(PREV_STYLE)) { // Dev after Unexpected: the user's own style and borders come back
                            putString(Settings.PREF_THEME_STYLE, prefs.getString(PREV_STYLE, Defaults.PREF_THEME_STYLE) ?: Defaults.PREF_THEME_STYLE)
                            putBoolean(Settings.PREF_THEME_KEY_BORDERS, prefs.getBoolean(PREV_BORDERS, Defaults.PREF_THEME_KEY_BORDERS))
                            prefs.getString(PREV_TOOLBAR, null)?.let { putString(Settings.PREF_TOOLBAR_MODE, it) }
                            remove(PREV_STYLE); remove(PREV_BORDERS); remove(PREV_TOOLBAR)
                        }
                        putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_UNEXPECTED)
                        putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_UNEXPECTED)
                        putBoolean(CornerSwipe.PREF, true)
                    }
                }
                DEFAULT -> {
                    val day = prefs.getString(PREV_DAY, Defaults.PREF_THEME_COLORS) ?: Defaults.PREF_THEME_COLORS
                    val night = prefs.getString(PREV_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) ?: Defaults.PREF_THEME_COLORS_NIGHT
                    prefs.edit {
                        putString(PREF, DEFAULT.id)
                        // leaving Unexpected: its parked style and borders come back (only if it set them)
                        if (prefs.contains(PREV_STYLE)) {
                            putString(Settings.PREF_THEME_STYLE, prefs.getString(PREV_STYLE, Defaults.PREF_THEME_STYLE) ?: Defaults.PREF_THEME_STYLE)
                            putBoolean(Settings.PREF_THEME_KEY_BORDERS, prefs.getBoolean(PREV_BORDERS, Defaults.PREF_THEME_KEY_BORDERS))
                            prefs.getString(PREV_TOOLBAR, null)?.let { putString(Settings.PREF_TOOLBAR_MODE, it) }
                            remove(PREV_STYLE); remove(PREV_BORDERS); remove(PREV_TOOLBAR)
                        }
                        putBoolean(UnexpectedKb.PREF, false)
                        // Only undo the colour theme if Unexpected itself set it — never clobber a user's own pick.
                        if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_UNEXPECTED)
                            putString(Settings.PREF_THEME_COLORS, day)
                        if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == KeyboardTheme.THEME_UNEXPECTED)
                            putString(Settings.PREF_THEME_COLORS_NIGHT, night)
                        putBoolean(CornerSwipe.PREF, false)
                    }
                }
            }
        }
    }
}

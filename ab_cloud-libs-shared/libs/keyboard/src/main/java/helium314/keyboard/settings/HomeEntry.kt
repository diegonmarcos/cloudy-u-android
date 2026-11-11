// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/**
 * #1622: the keyboard's Home Page, declared once. The toolbar's Config icon opens an icon grid instead of the
 * old scrolling menu; the grid is these tiles, laid out group by group in declaration order, and each tile
 * navigates to an existing settings screen ([route] is a [SettingsDestination]). A new tile is one enum
 * constant — the [HomeScreen] draws whatever is declared, so the grid cannot drift from this list.
 *
 * Groups (ticket): A) Keyboard, B) Text, C) Configs (everything else). The Configs group's "All settings" tile
 * opens the full scrolling menu ([SettingsDestination.Settings]), so nothing is lost by moving to the grid.
 */
enum class HomeGroup(@StringRes val title: Int) {
    KEYBOARD(R.string.home_group_keyboard),
    TEXT(R.string.home_group_text),
    CONFIGS(R.string.home_group_configs),
}

enum class HomeTile(
    val group: HomeGroup,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val route: String,
) {
    // A) Keyboard
    TOOLBAR(HomeGroup.KEYBOARD, R.string.settings_screen_toolbar, R.drawable.ic_settings_toolbar, SettingsDestination.Toolbar),
    EMOJI(HomeGroup.KEYBOARD, R.string.settings_screen_emoji, R.drawable.ic_settings_about, SettingsDestination.Emoji),
    THEMES(HomeGroup.KEYBOARD, R.string.kb_themes, R.drawable.ic_settings_appearance, SettingsDestination.Themes),
    CLIPBOARD(HomeGroup.KEYBOARD, R.string.settings_screen_clipboard, R.drawable.ic_settings_preferences, SettingsDestination.Clipboard),

    // B) Text
    AUTOCOMPLETE(HomeGroup.TEXT, R.string.settings_screen_autocomplete, R.drawable.ic_toolbar_autocomplete, SettingsDestination.Autocomplete),
    TRANSLATION(HomeGroup.TEXT, R.string.settings_screen_translation, R.drawable.ic_settings_about, SettingsDestination.Translation),
    TEXT_ENHANCE(HomeGroup.TEXT, R.string.settings_screen_enhance, R.drawable.ic_toolbar_enhance, SettingsDestination.TextEnhance),
    DRAFT_ANSWER(HomeGroup.TEXT, R.string.settings_screen_answer, R.drawable.ic_toolbar_answer, SettingsDestination.DraftAnswer),
    GRAMMAR_CHECK(HomeGroup.TEXT, R.string.settings_screen_grammar, R.drawable.ic_settings_correction, SettingsDestination.GrammarCheck),
    VOICE_TRANSCRIPT(HomeGroup.TEXT, R.string.settings_screen_voice_transcript, R.drawable.ic_settings_about, SettingsDestination.VoiceTranscript),

    // C) Configs (everything else). "All settings" is the old full menu, kept one tap away.
    ALL_SETTINGS(HomeGroup.CONFIGS, R.string.home_all_settings, R.drawable.ic_settings_preferences, SettingsDestination.Settings),
    APPEARANCE(HomeGroup.CONFIGS, R.string.settings_screen_appearance, R.drawable.ic_settings_appearance, SettingsDestination.Appearance),
    LANGUAGES(HomeGroup.CONFIGS, R.string.language_and_layouts_title, R.drawable.ic_settings_languages, SettingsDestination.Languages),
    PREFERENCES(HomeGroup.CONFIGS, R.string.settings_screen_preferences, R.drawable.ic_settings_preferences, SettingsDestination.Preferences),
    ADVANCED(HomeGroup.CONFIGS, R.string.settings_screen_advanced, R.drawable.ic_settings_advanced, SettingsDestination.Advanced),
    ABOUT(HomeGroup.CONFIGS, R.string.settings_screen_about, R.drawable.ic_settings_about, SettingsDestination.About);

    companion object {
        /** Tiles of [group], in declaration order. */
        fun of(group: HomeGroup): List<HomeTile> = entries.filter { it.group == group }
    }
}

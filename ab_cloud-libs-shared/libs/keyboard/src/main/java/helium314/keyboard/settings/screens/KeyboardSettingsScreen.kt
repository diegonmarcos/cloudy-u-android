// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.KbThemePreset
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

/**
 * #1665 Keyboard > Settings: every setting that configures the keyboard and its theme, on one page, grouped (Theme, Size and
 * layout, Keys, Feedback), followed by doors to the full pages they come from (Appearance, Preferences, Gesture typing, Gesture
 * data, Advanced) where every remaining keyboard setting lives. Each row is an existing setting (same key, same store).
 */
@Composable
fun KeyboardSettingsScreen(
    onClickBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    val dev = KbThemePreset.current(ctx) == KbThemePreset.DEV
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kb_settings_title), settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Preference(
                name = stringResource(R.string.kb_themes),
                description = stringResource(KbThemePreset.current(ctx).label),
                onClick = { onOpen(helium314.keyboard.settings.SettingsDestination.Themes) },
                icon = R.drawable.ic_settings_appearance,
            ) { NextScreenIcon() }
            keyboardSettingKeys(
                dayNight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT),
                borders = prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS),
                dev = dev,
            ).forEach {
                when (it) {
                    null -> Unit
                    is Int -> PreferenceCategory(stringResource(it))
                    else -> it?.let { k -> SettingsActivity.settingsContainer[k]?.Preference() }
                }
            }
            PreferenceCategory(stringResource(R.string.kb_settings_more))
            keyboardSettingPages.forEach { (label, route) ->
                Preference(name = stringResource(label), onClick = { onOpen(route) }) { NextScreenIcon() }
            }
        }
    }
}

/**
 * #1665 the settings Keyboard > Settings draws, in order (an Int is a section title, null is skipped). Pure, so a test can check
 * every key is an existing setting. The Dev theme always has its number row, so its number-row switches are not offered then.
 */
internal fun keyboardSettingKeys(dayNight: Boolean = true, borders: Boolean = true, dev: Boolean = false): List<Any?> = listOf(
    R.string.settings_screen_theme,
    Settings.PREF_THEME_STYLE,
    Settings.PREF_ICON_STYLE,
    Settings.PREF_THEME_COLORS,
    Settings.PREF_THEME_KEY_BORDERS,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Settings.PREF_THEME_DAY_NIGHT else null,
    if (dayNight) Settings.PREF_THEME_COLORS_NIGHT else null,
    Settings.PREF_NAVBAR_COLOR,
    SettingsWithoutKey.BACKGROUND_IMAGE,
    R.string.kb_settings_size,
    Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
    Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
    if (borders) Settings.PREF_KEY_GAP_SCALE_PREFIX else null,
    Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
    Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
    Settings.PREF_ENABLE_SPLIT_KEYBOARD,
    SettingsWithoutKey.CUSTOM_FONT,
    Settings.PREF_FONT_SCALE,
    Settings.PREF_SPACE_BAR_TEXT,
    R.string.settings_category_additional_keys,
    if (dev) null else Settings.PREF_SHOW_NUMBER_ROW,
    if (dev) null else Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS,
    Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY,
    Settings.PREF_SHOW_EMOJI_KEY,
    Settings.PREF_SHOW_HINTS,
    Settings.PREF_POPUP_KEYS_ORDER,
    R.string.kb_settings_feedback,
    Settings.PREF_POPUP_ON,
    Settings.PREF_VIBRATE_ON,
    Settings.PREF_SOUND_ON,
)

/** #1665 the full keyboard pages behind Keyboard > Settings (label, route). */
internal val keyboardSettingPages: List<Pair<Int, String>> = listOf(
    R.string.settings_screen_appearance to helium314.keyboard.settings.SettingsDestination.Appearance,
    R.string.settings_screen_preferences to helium314.keyboard.settings.SettingsDestination.Preferences,
    R.string.settings_screen_gesture to helium314.keyboard.settings.SettingsDestination.GestureTyping,
    R.string.gesture_data_screen to helium314.keyboard.settings.SettingsDestination.DataGathering,
    R.string.settings_screen_advanced to helium314.keyboard.settings.SettingsDestination.Advanced,
)

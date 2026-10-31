// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.KeyboardUpdate
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.ConfigsEntry
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickGrammarCheck: () -> Unit, // SuperApp addition (patch 0002)
    onClickClipboard: () -> Unit, // SuperApp addition
    onClickAutocomplete: () -> Unit, // #1077
    onClickTranslation: () -> Unit, // SuperApp addition
    onClickEmoji: () -> Unit, // SuperApp addition
    onClickVoiceTranscript: () -> Unit, // SuperApp addition
    onClickTextEnhance: () -> Unit, // SuperApp addition — Text Enhancements
    onClickAiRouting: () -> Unit, // SuperApp addition — AI Model Routing
    onClickUpdate: () -> Unit, // #776 Config ▸ Update
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
    onClickDraftAnswer: () -> Unit = {}, // #1384 Draft Answer (last: positional callers stay valid)
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                // ── SuperApp section (patch 0011 reorder): our own additions first, in ConfigsEntry order (#1077) ──
                ConfigsEntry.entries.forEach { entry ->
                    when (entry) {
                        ConfigsEntry.TOOLBAR -> Preference(
                            name = stringResource(R.string.settings_screen_toolbar),
                            onClick = onClickToolbar,
                            icon = R.drawable.ic_settings_toolbar
                        ) { NextScreenIcon() }
                        ConfigsEntry.CLIPBOARD -> Preference(
                            name = stringResource(R.string.settings_screen_clipboard),
                            onClick = onClickClipboard,
                            icon = R.drawable.ic_settings_preferences
                        ) { NextScreenIcon() }
                        ConfigsEntry.AUTOCOMPLETE -> Preference(
                            name = stringResource(R.string.settings_screen_autocomplete),
                            onClick = onClickAutocomplete,
                            icon = R.drawable.ic_toolbar_autocomplete
                        ) { NextScreenIcon() }
                        ConfigsEntry.EMOJI -> Preference(
                            name = stringResource(R.string.settings_screen_emoji),
                            onClick = onClickEmoji,
                            icon = R.drawable.ic_settings_about
                        ) { NextScreenIcon() }
                        ConfigsEntry.TRANSLATION -> Preference(
                            name = stringResource(R.string.settings_screen_translation),
                            onClick = onClickTranslation,
                            icon = R.drawable.ic_settings_about
                        ) { NextScreenIcon() }
                        ConfigsEntry.TEXT_ENHANCE -> Preference(
                            name = stringResource(R.string.settings_screen_enhance),
                            onClick = onClickTextEnhance,
                            icon = R.drawable.ic_toolbar_enhance
                        ) { NextScreenIcon() }
                        ConfigsEntry.DRAFT_ANSWER -> Preference(
                            name = stringResource(R.string.settings_screen_answer),
                            onClick = onClickDraftAnswer,
                            icon = R.drawable.ic_toolbar_answer
                        ) { NextScreenIcon() }
                        ConfigsEntry.GRAMMAR_CHECK -> Preference(
                            name = stringResource(R.string.settings_screen_grammar),
                            onClick = onClickGrammarCheck,
                            icon = R.drawable.ic_settings_correction
                        ) { NextScreenIcon() }
                        ConfigsEntry.VOICE_TRANSCRIPT -> Preference(
                            name = stringResource(R.string.settings_screen_voice_transcript),
                            onClick = onClickVoiceTranscript,
                            icon = R.drawable.ic_settings_about
                        ) { NextScreenIcon() }
                        // AI Model Routing: the shared provider/key/model used by Text Enhancements,
                        // Grammar Check (AI engine) and translation.
                        ConfigsEntry.AI_ROUTING -> Preference(
                            name = stringResource(R.string.settings_screen_ai_routing),
                            description = stringResource(R.string.settings_screen_ai_routing_summary),
                            onClick = onClickAiRouting,
                            icon = R.drawable.ic_settings_advanced
                        ) { NextScreenIcon() }
                        // #1103: shortcut to Android's own keyboard (input method) settings, same intent as the toolbar key
                        ConfigsEntry.ANDROID_SETTINGS -> {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            Preference(
                                name = stringResource(R.string.android_settings),
                                onClick = { helium314.keyboard.latin.utils.AndroidKeyboardSettings.launch(ctx) },
                                icon = R.drawable.ic_toolbar_android_settings
                            ) { NextScreenIcon() }
                        }
                        // #776 Config ▸ Update: shown when the consuming app's build.json declares an update block.
                        ConfigsEntry.UPDATE -> if (KeyboardUpdate.enabled) {
                            Preference(
                                name = stringResource(R.string.keyboard_update),
                                description = stringResource(R.string.keyboard_update_summary),
                                onClick = onClickUpdate,
                                icon = R.drawable.ic_settings_about
                            ) { NextScreenIcon() }
                        }
                    }
                }
                // #1352 the host app's entries (Cloud Keyboard: Configs ▸ General / ▸ About, the fleet's shared pages)
                val extrasCtx = androidx.compose.ui.platform.LocalContext.current
                helium314.keyboard.settings.KeyboardSettingsExtras.entries.forEach { extra ->
                    Preference(
                        name = extra.label,
                        description = extra.description,
                        onClick = { extra.open(extrasCtx) },
                        icon = R.drawable.ic_settings_about
                    ) { NextScreenIcon() }
                }

                // ── Everything below is the base keyboard menu ─────────────────────
                PreferenceCategory("Keyboard defaults")
                Preference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    onClick = onClickLanguage,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.dictionary_settings_category),
                    onClick = onClickDictionaries,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_preferences),
                    onClick = onClickPreferences,
                    icon = R.drawable.ic_settings_preferences
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_advanced),
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_secondary_layouts),
                    onClick = onClickLayouts,
                    icon = R.drawable.ic_ime_switcher
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_appearance),
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_appearance
                ) { NextScreenIcon() }
                if (JniUtils.sHaveGestureLib)
                    Preference(
                        name = stringResource(R.string.settings_screen_gesture),
                        onClick = onClickGestureTyping,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
                    Preference(
                        name = stringResource(R.string.gesture_data_screen),
                        onClick = onClickDataGathering,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_correction),
                    onClick = onClickTextCorrection,
                    icon = R.drawable.ic_settings_correction
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

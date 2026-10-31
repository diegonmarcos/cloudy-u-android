// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.setToolbarKeyEnabled
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference

// #1384 "Draft Answer": the Text Enhancement panel in reply mode (ANSWER toolbar key). Same shape as Text
// Enhancement: the model in use (read-only, the Text Enhancement model, linked to AI Routing), the toolbar
// switch, the tone / length / language menus from build.json::keyboard_ai, and the composed prompt. The input
// is a message (the newest clipboard item by default), never the screen, so there is no scope setting.

fun createDraftAnswerSettings(context: Context): List<Setting> = listOf(
    // The same read-only row Text Enhancement shows: Draft Answer has no model of its own.
    Setting(context, Settings.PREF_ANSWER_MODEL, R.string.ai_enhance_model_title, R.string.answer_model_summary) { AiEnhanceModelRow(it) },
    Setting(context, Settings.PREF_ANSWER_TOOLBAR_KEY, R.string.answer_toolbar_title, R.string.answer_toolbar_summary) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        val prefs = ctx.prefs()
        val enabled = ToolbarKey.ANSWER in getEnabledToolbarKeys(prefs)
        Preference(name = setting.title, description = setting.description,
            onClick = { setToolbarKeyEnabled(prefs, ToolbarKey.ANSWER, !enabled) }) {
            Switch(checked = enabled, onCheckedChange = { setToolbarKeyEnabled(prefs, ToolbarKey.ANSWER, it) })
        }
    },
    Setting(context, Settings.PREF_ANSWER_TONE, R.string.enhance_tone_title, R.string.answer_tone_summary) { setting ->
        ListPreference(setting, AiRouter.tones.map { it.label to it.id }, AiRouter.defaultTone)
    },
    Setting(context, Settings.PREF_ANSWER_LENGTH, R.string.enhance_length_title, R.string.answer_length_summary) { setting ->
        ListPreference(setting, AiRouter.lengths.map { it.label to it.id }, AiRouter.defaultLength)
    },
    Setting(context, Settings.PREF_ANSWER_LANGUAGE, R.string.enhance_language_title, R.string.answer_language_summary) { setting ->
        ListPreference(setting, AiRouter.languages.map { it.label to it.id }, AiRouter.defaultLanguage)
    },
    // Stores nothing: the system prompt the panel sends, built by the very function Generate calls.
    Setting(context, Settings.PREF_ANSWER_PROMPT, R.string.enhance_prompt_title, R.string.answer_prompt_summary) { setting ->
        PromptPreview(setting, AiRouter.answerStyle(LocalContext.current).prompt)
    },
)

@Composable
fun DraftAnswerScreen(onClickBack: () -> Unit) {
    val items = listOf(
        Settings.PREF_ANSWER_MODEL,
        Settings.PREF_ANSWER_TOOLBAR_KEY,
        Settings.PREF_ANSWER_TONE,
        Settings.PREF_ANSWER_LENGTH,
        Settings.PREF_ANSWER_LANGUAGE,
        Settings.PREF_ANSWER_PROMPT,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_answer),
        settings = items,
    )
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            DraftAnswerScreen {}
        }
    }
}

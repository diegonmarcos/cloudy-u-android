// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.settings.EngineOrigin
import helium314.keyboard.latin.settings.GrammarEngines
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.PreferenceCategory
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

// SuperApp addition (patch 0002) — Grammar check settings screen.
// Mirrors the shape of TextCorrectionScreen / createCorrectionSettings.

fun createGrammarSettings(context: Context): List<Setting> = listOf(
    // #1077: Auto Correction and Grammar Check merged: ONE mode. (The old "Grammar mode" list, the engine, is labelled "Grammar engine".)
    Setting(
        context,
        Settings.PREF_GRAMMAR_CHECK_MODE,
        R.string.grammar_check_mode_title,
        R.string.grammar_check_mode_summary,
    ) { setting ->
        val items = listOf(
            context.getString(R.string.grammar_check_mode_auto_correct) to GrammarMode.AUTO_CORRECT.id,
            context.getString(R.string.grammar_check_mode_highlight) to GrammarMode.HIGHLIGHT.id,
            context.getString(R.string.grammar_check_mode_off) to GrammarMode.OFF.id,
        )
        ListPreference(setting, items, GrammarMode.read(LocalContext.current.prefs()).id)
    },
    // Explicit routing: ML libs (on-device rules) vs OpenRouter (Grammar Only model from AI Routing).
    Setting(context, AiTaskRouting.key(AiTask.GRAMMAR), R.string.ai_routing_grammar_title, R.string.ai_routing_grammar_summary) {
        AiRoutingSwitch(it, AiTask.GRAMMAR)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_MODE,
        R.string.grammar_mode_title,
    ) { setting ->
        val items = listOf(
            context.getString(R.string.grammar_mode_off) to "off",
            context.getString(R.string.grammar_mode_local) to "local",
            context.getString(R.string.grammar_mode_remote) to "remote",
            context.getString(R.string.grammar_mode_ai) to "ai", // SuperApp: AI Model Routing provider, grammar-only prompt
        )
        ListPreference(setting, items, Defaults.PREF_GRAMMAR_MODE)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,
        R.string.grammar_fix_capitalize_i,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,
        R.string.grammar_fix_sentence_caps,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
        R.string.grammar_fix_repeated_words,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_REMOTE_URL,
        R.string.grammar_remote_url_title,
        R.string.grammar_remote_url_summary,
    ) { setting ->
        TextInputPreference(setting, Defaults.PREF_GRAMMAR_REMOTE_URL)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_PT_VARIANT,
        R.string.grammar_pt_variant_title,
        R.string.grammar_pt_variant_summary,
    ) { setting ->
        val items = listOf(
            context.getString(R.string.grammar_pt_variant_pt) to "pt-PT",
            context.getString(R.string.grammar_pt_variant_br) to "pt-BR",
        )
        ListPreference(setting, items, Defaults.PREF_GRAMMAR_PT_VARIANT)
    },
    // Placeholder for future n-gram confusion-pair API (not yet wired to anything).
    Setting(
        context,
        Settings.PREF_GRAMMAR_NGRAM_URL,
        R.string.grammar_ngram_url_title,
        R.string.grammar_ngram_url_summary,
    ) { setting ->
        TextInputPreference(setting, Defaults.PREF_GRAMMAR_NGRAM_URL)
    },
)

@Composable
fun GrammarCheckScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val autoCorrect = GrammarMode.read(prefs).autoCorrects
    val items = listOf(
        Settings.PREF_GRAMMAR_CHECK_MODE,
        AiTaskRouting.key(AiTask.GRAMMAR),
        // the Auto Correction settings that used to live in Text correction: they only act in Auto Correct mode
        if (autoCorrect) Settings.PREF_MORE_AUTO_CORRECTION else null,
        if (autoCorrect) Settings.PREF_AUTOCORRECT_SHORTCUTS else null,
        if (autoCorrect) Settings.PREF_AUTO_CORRECT_CONFIDENCE else null,
        if (autoCorrect) Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT else null,
        Settings.PREF_GRAMMAR_MODE,
        R.string.grammar_category_fixes,
        Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,
        Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,
        Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
        Settings.PREF_GRAMMAR_REMOTE_URL,
        Settings.PREF_GRAMMAR_PT_VARIANT,
        Settings.PREF_GRAMMAR_NGRAM_URL,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_grammar),
        settings = items,
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                items.forEach {
                    if (it is Int) PreferenceCategory(stringResource(it))
                    else if (it != null) SettingsActivity.settingsContainer[it]?.Preference()
                }
                EnginesList()
            }
        }
    }
}

/** #1077: every engine the merged Grammar Check uses, from [GrammarEngines], each marked native or external. */
@Composable
private fun EnginesList() {
    PreferenceCategory(stringResource(R.string.grammar_engines_category))
    GrammarEngines.ALL.forEach { e ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
            Row {
                Text(e.title, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Text(
                    (if (e.origin == EngineOrigin.NATIVE) "native" else "external") + (if (e.wired) "" else " · not active"),
                    fontSize = 12.sp, color = if (e.origin == EngineOrigin.NATIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(e.what, fontSize = 11.sp, modifier = Modifier.alpha(0.7f), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            GrammarCheckScreen {}
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.translate.TranslateEngines
import com.diegonmarcos.superapp.translate.TranslatePrefs
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.RoutedTranslateEngineClient
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference

// SuperApp addition — Translate bar settings + engine status.
// Pref KEYS and DEFAULTS come from libs:translate's TranslatePrefs (the bar reads
// them at use time); this screen only renders them with the keyboard's own
// preference composables, same shape as GrammarCheckScreen / createGrammarSettings.
// Storage is the device-protected default prefs both sides share.

private fun langName(code: String) = "${java.util.Locale(code).displayLanguage} ($code)"

fun createTranslateSettings(context: Context): List<Setting> = listOf(
    // Explicit routing: ML libs (ML Kit on the phone) vs OpenRouter (Translation Only model from AI Routing).
    Setting(context, AiTaskRouting.key(AiTask.TRANSLATE), R.string.ai_routing_translate_title, R.string.ai_routing_translate_summary) {
        AiRoutingSwitch(it, AiTask.TRANSLATE)
    },
    Setting(context, TranslatePrefs.KEY_DEFAULT_TARGET, R.string.translate_default_target_title) { setting ->
        val langs = TranslateEngines.client?.supportedLanguages().orEmpty()
            .ifEmpty { TranslatePrefs.FALLBACK_LANGS }
            .sortedBy { java.util.Locale(it).displayLanguage }
        val items = listOf(context.getString(R.string.translate_default_target_active) to TranslatePrefs.DEFAULT_TARGET) +
            langs.map { langName(it) to it }
        ListPreference(setting, items, TranslatePrefs.DEFAULT_TARGET)
    },
    Setting(context, TranslatePrefs.KEY_AUTO_DETECT, R.string.translate_auto_detect_title, R.string.translate_auto_detect_summary) {
        SwitchPreference(it, TranslatePrefs.DEFAULT_AUTO_DETECT)
    },
    Setting(context, TranslatePrefs.KEY_APPLY_MODE, R.string.translate_apply_mode_title) { setting ->
        val items = listOf(
            context.getString(R.string.translate_apply_insert) to TranslatePrefs.APPLY_INSERT,
            context.getString(R.string.translate_apply_replace) to TranslatePrefs.APPLY_REPLACE,
        )
        ListPreference(setting, items, TranslatePrefs.DEFAULT_APPLY_MODE)
    },
    Setting(context, TranslatePrefs.KEY_LIVE_COMMIT, R.string.translate_live_commit_title, R.string.translate_live_commit_summary) {
        SwitchPreference(it, TranslatePrefs.DEFAULT_LIVE_COMMIT)
    },
)

@Composable
fun TranslationInfoScreen(onClickBack: () -> Unit) {
    val keys = listOf(
        AiTaskRouting.key(AiTask.TRANSLATE),
        TranslatePrefs.KEY_DEFAULT_TARGET,
        TranslatePrefs.KEY_AUTO_DETECT,
        TranslatePrefs.KEY_APPLY_MODE,
        TranslatePrefs.KEY_LIVE_COMMIT,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_translation),
        settings = keys,
    ) {
        // Custom content (it replaces the default list) so the engine status can
        // sit under the preferences — the prefs themselves still come from the
        // container, so they stay searchable from the settings search box.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PreferenceCategory(stringResource(R.string.translate_category_behavior))
            keys.forEach { SettingsActivity.settingsContainer[it]?.Preference() }

            // The on-device engine under the routing wrapper (RoutedTranslateEngineClient).
            val client = RoutedTranslateEngineClient.onDevice()
            val liveLangs = client?.supportedLanguages().orEmpty()
            PreferenceCategory("Engine")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Tap the translate icon on the toolbar: type in the bar, see the live " +
                        "translation underneath, then Insert / Replace / Copy (Enter runs the " +
                        "primary action). Long-press the icon to translate the selection or the " +
                        "whole field in place.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Type: ${client?.javaClass?.simpleName ?: "none registered"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // client != null only means the client OBJECT exists — for the AIDL
                // client that's true even when the underlying bind to the companion
                // service failed or is still pending. isConnected() is the real state.
                Text(
                    when {
                        client == null -> "Not connected — no engine registered."
                        client.isConnected() -> "Connected — translation is functional."
                        else -> "NOT connected — client exists but the underlying service " +
                            "isn't bound. On the standalone Cloud Keyboard app this means the " +
                            "cloud-keyboard-libs companion app is either not installed, or its " +
                            "service failed to bind (see logcat tag AidlTranslateEngine). " +
                            "The bar reports this as a status line instead of translating."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Languages: " + liveLangs.ifEmpty { TranslatePrefs.FALLBACK_LANGS }.joinToString(", ") { langName(it) } +
                        if (liveLangs.isEmpty()) " — defaults, engine not reporting yet" else " — from engine",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TranslationInfoScreen {}
        }
    }
}

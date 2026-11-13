// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import helium314.keyboard.settings.KbDensity

// SuperApp addition — "Text Resume".
//
// RESUME MEANS SUMMARISE. It is the owner's product name for condensing a piece of text to its
// essentials, and it is kept exactly as they spell it. It is NOT a curriculum vitae and it is NOT
// resuming a paused operation; anyone reading this file later should read the word as "summary"
// everywhere it appears. cloud-mail's reader calls the same feature "AI Resume".
//
// Every menu here comes from build.json::keyboard_ai.summaries (AiRouter.summaries), for the same
// reason Text Enhancement's do: the prompt is shown on this screen, and a prompt a screen can show
// is configuration rather than a string literal in Kotlin.
//
// No provider or key of its own: this shares AI Model Routing's, exactly as Text Enhancement does.

fun createTextResumeSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_SUMMARY_STYLE, R.string.summary_style_title, R.string.summary_style_summary) { setting ->
        ListPreference(setting, AiRouter.summaries.map { it.label to it.id }, AiRouter.defaultSummary)
    },
    // Stores nothing: it SHOWS what the chosen entry above actually sends. Built by the very
    // function the engine calls, so the screen cannot drift from the request.
    Setting(context, Settings.PREF_SUMMARY_PROMPT, R.string.summary_prompt_title, R.string.summary_prompt_summary) { setting ->
        PromptPreview(setting, AiRouter.summaryStyle(LocalContext.current).prompt)
    },
    Setting(context, Settings.PREF_SUMMARY_TEST, R.string.summary_test_title, R.string.summary_test_summary) { setting ->
        SummaryTestBox(setting)
    },
)

/**
 * Try the setting above without leaving the screen: the text typed here goes through the very same
 * [AiRouter.summaryStyle] prompt cloud-mail's AI Resume sends, so what shows up below is what the
 * reader would have put in its summary box.
 *
 * Deliberately the same shape as [EnhanceTestBox] rather than a cleverer one — a second dialect of
 * "try it" on the neighbouring screen is a thing to learn twice.
 */
@Composable
private fun SummaryTestBox(setting: Setting) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = KbDensity.rowPadV, horizontal = KbDensity.rowPadH)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        setting.description?.let {
            Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(top = KbDensity.innerGap),
            minLines = 3,
            placeholder = { Text(stringResource(R.string.summary_test_hint)) },
        )
        Row(Modifier.padding(top = KbDensity.innerGap), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    copied = false
                    output = ""
                    scope.launch {
                        val style = AiRouter.summaryStyle(ctx)
                        output = withContext(Dispatchers.IO) {
                            runCatching { AiRouter.complete(ctx, style.prompt, input.take(AiRouter.maxChars)) }
                                .getOrElse { ctx.getString(R.string.enhance_failed, it.message ?: it.javaClass.simpleName) }
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.enhance_test_run)) }
            Text(
                AiRouter.provider(ctx).label,
                Modifier.padding(start = 12.dp),
                color = dim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (busy) {
            Text(
                stringResource(R.string.enhance_test_running, AiRouter.provider(ctx).label),
                Modifier.padding(top = KbDensity.innerGap),
                color = dim,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (output.isNotEmpty()) {
            OutlinedTextField(
                value = output,
                onValueChange = { output = it; copied = false },
                modifier = Modifier.fillMaxWidth().padding(top = KbDensity.innerGap),
                minLines = 3,
                label = { Text(stringResource(R.string.enhance_test_output)) },
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(output))
                    copied = true
                }) { Text(stringResource(R.string.enhance_test_copy)) }
                if (copied) {
                    Text(
                        stringResource(R.string.enhance_test_copied),
                        Modifier.padding(start = 12.dp),
                        color = dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun TextResumeScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_resume),
        settings = listOf(
            Settings.PREF_SUMMARY_STYLE,
            Settings.PREF_SUMMARY_PROMPT,
            Settings.PREF_SUMMARY_TEST,
        ),
    )
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TextResumeScreen {}
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.R
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.KbDensity

/**
 * SuperApp addition — THE PROMPT, shown where the settings that build it are.
 *
 * Every AI feature on this device sends a system prompt the user never saw, assembled out of a
 * preamble and whichever menu entries they picked. Until this row, "Polish tone and flow" was the
 * whole of what a person could know about what their text was going to be told to become. A
 * feature whose behaviour is a paragraph of English should show that paragraph.
 *
 * AND IT IS THE REASON THE PROMPTS ARE DATA. This row can only exist because the prompts live in
 * build.json::keyboard_ai and are read through [AiRouter]; a prompt written as a Kotlin literal
 * inside the code that sends it cannot be displayed without a second copy of the same words in the
 * screen, and the day the two disagree the screen is lying about what the app does. So: if a
 * prompt can be shown in Settings, it is configuration, and it belongs in the registry.
 *
 * READ-ONLY, and composed exactly as the engine composes it — [text] is passed in already built by
 * the same function the feature calls, never re-assembled here. Copyable, because the useful thing
 * to do with a prompt you disagree with is paste it somewhere and say so.
 */
@Composable
fun PromptPreview(setting: Setting, text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = KbDensity.rowPadV, horizontal = KbDensity.rowPadH)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        setting.description?.let {
            Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium)
        }
        // A disabled text field rather than a Text: it scrolls, it selects, and it looks like what
        // it is — a value the app holds, which this screen does not let you edit.
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            modifier = Modifier.fillMaxWidth().padding(top = KbDensity.innerGap),
            minLines = 3,
            maxLines = 12,
        )
        Button(
            onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.enhance_test_copy)) }
        if (copied) {
            Text(
                stringResource(R.string.enhance_test_copied),
                Modifier.padding(top = 2.dp),
                color = dim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

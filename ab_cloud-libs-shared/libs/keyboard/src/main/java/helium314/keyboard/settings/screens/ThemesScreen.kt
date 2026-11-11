// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.KbThemePreset
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference

/**
 * #1622: the Themes picker. Declaration-driven — one row per [KbThemePreset]; picking one applies it
 * (swaps the colour theme + corner-swipe flag and redraws the keyboard) through [KbThemePreset.apply].
 */
@Composable
fun ThemesScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(KbThemePreset.current(ctx)) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.kb_themes),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KbThemePreset.entries.forEach { preset ->
                Preference(
                    name = stringResource(preset.label),
                    description = stringResource(preset.summary),
                    onClick = {
                        KbThemePreset.apply(ctx, preset)
                        selected = preset
                    },
                    icon = R.drawable.ic_settings_appearance,
                ) {
                    RadioButton(selected = selected == preset, onClick = {
                        KbThemePreset.apply(ctx, preset)
                        selected = preset
                    })
                }
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
            ThemesScreen({})
        }
    }
}

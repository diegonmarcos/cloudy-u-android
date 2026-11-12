// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.HomeGroup
import helium314.keyboard.settings.HomeTile
import helium314.keyboard.settings.IconOrImage
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory

private const val COLUMNS = 4

/**
 * #1622 Home Page: an icon grid grouped into Keyboard / Text / Configs ([HomeGroup]), replacing the old
 * bottom-nav / scrolling Configs menu. Each tile opens an existing settings screen via [onOpen]; the grid
 * is whatever [HomeTile] declares.
 */
@Composable
fun HomeScreen(
    onOpen: (route: String) -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_home),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HomeGroup.entries.forEach { group ->
                PreferenceCategory(stringResource(group.title))
                HomeTile.of(group).chunked(COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        row.forEach { tile -> Tile(tile, Modifier.weight(1f), onOpen) }
                        // pad a short last row so tiles keep their column width
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(tile: HomeTile, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(
        modifier = modifier
            .clickable { onOpen(tile.route) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconOrImage(tile.icon, stringResource(tile.label), 36)
        Text(
            text = stringResource(tile.label),
            textAlign = TextAlign.Center,
            maxLines = 2,
            // #1698 a dense tile label: small enough that a word fits its column (no mid-word breaks), ellipsis past two lines
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            HomeScreen({}, {})
        }
    }
}

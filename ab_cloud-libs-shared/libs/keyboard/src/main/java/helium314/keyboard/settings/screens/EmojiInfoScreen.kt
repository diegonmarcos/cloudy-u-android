// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.media.GifProvider
import com.diegonmarcos.superapp.media.MediaItem
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.media.StickerRepo
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1665 Mediaboards > Emojis: the emoji panel's own settings (the same settings as on Appearance / Preferences / Advanced /
 * Text correction, shown together here) and the emoji-name search dictionaries. GIF and Stickers are their own pages
 * ([GifScreen], [StickersScreen]); together the three carry everything the one shared info page carried before.
 */
@Composable
fun EmojiInfoScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.mediaboards_emojis),
        settings = emptyList(),
    ) {
        val context = LocalContext.current
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.mediaboards_emoji_search_hint), style = MaterialTheme.typography.bodyMedium)
                val emojiLocales = runCatching { DictionaryInfoUtils.getLocalesWithEmojiDicts(context) }.getOrDefault(emptyList())
                Text(
                    if (emojiLocales.isEmpty()) stringResource(R.string.mediaboards_emoji_no_dict)
                    else stringResource(R.string.mediaboards_emoji_dicts, emojiLocales.joinToString(", ") { it.displayLanguage }),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            SettingRows(emojiSettingKeys(
                fontScaled = prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE) != 1f,
                skinTones = prefs.getInt(Settings.PREF_EMOJI_MAX_SDK, 0) >= 24,
            ))
        }
    }
}

/** #1665 the emoji settings the Emojis page lists, grouped (an Int is a category title); every key is an existing setting. */
internal fun emojiSettingKeys(fontScaled: Boolean = true, skinTones: Boolean = true): List<Any?> = listOf(
    R.string.mediaboards_emoji_panel,
    Settings.PREF_SHOW_EMOJI_KEY,
    Settings.PREF_ABC_AFTER_EMOJI,
    Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
    if (skinTones) Settings.PREF_EMOJI_SKIN_TONE else null,
    R.string.mediaboards_emoji_look,
    SettingsWithoutKey.CUSTOM_EMOJI_FONT,
    Settings.PREF_EMOJI_FONT_SCALE,
    if (fontScaled) Settings.PREF_EMOJI_KEY_FIT else null,
    Settings.PREF_EMOJI_MAX_SDK,
    R.string.mediaboards_emoji_typing,
    Settings.PREF_SUGGEST_EMOJIS,
    Settings.PREF_INLINE_EMOJI_SEARCH,
    Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
)

@Composable
private fun ColumnScope.SettingRows(keys: List<Any?>) {
    keys.forEach {
        when (it) {
            null -> Unit
            is Int -> PreferenceCategory(stringResource(it))
            else -> it?.let { k -> SettingsActivity.settingsContainer[k]?.Preference() }
        }
    }
}

/**
 * #1665 Mediaboards > GIF: the GIF source (provider, key state, page size, categories) and a live search that runs the same
 * provider the panel uses, so the page shows whether GIF search works on this phone. Never throws: no provider, no key or no
 * network each show their reason.
 */
@Composable
fun GifScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.mediaboards_gif), settings = emptyList()) {
        val ctx = LocalContext.current
        val gif = MediaRuntime.gif
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        val noResults = stringResource(R.string.mediaboards_gif_no_results)
        val failed = stringResource(R.string.mediaboards_gif_failed)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreferenceCategory(stringResource(R.string.mediaboards_gif_source))
            Fact(stringResource(R.string.mediaboards_gif_panel), stringResource(if (MediaRuntime.enabled) R.string.kb_theme_on else R.string.kb_theme_off))
            Fact(stringResource(R.string.mediaboards_gif_provider), gif?.provider ?: stringResource(R.string.mediaboards_gif_none))
            Fact(stringResource(R.string.mediaboards_gif_key), stringResource(if (MediaRuntime.gifEnabled) R.string.mediaboards_gif_key_set else R.string.mediaboards_gif_key_missing))
            if (gif != null) {
                Fact(stringResource(R.string.mediaboards_gif_limit), gif.limit.toString())
                Fact(stringResource(R.string.mediaboards_gif_categories), gif.categories.joinToString(", ") { it.label }.ifEmpty { "-" })
            }
            PreferenceCategory(stringResource(R.string.mediaboards_gif_search))
            if (gif == null || !MediaRuntime.gifEnabled) {
                Text(stringResource(R.string.mediaboards_gif_unavailable), style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.mediaboards_gif_query)) })
                Button(onClick = {
                    status = "…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { GifProvider.from(gif).fetch(query.trim(), gif.limit) } }
                        items = r.getOrDefault(emptyList())
                        status = when {
                            r.isFailure -> failed
                            items.isEmpty() -> noResults
                            else -> ctx.getString(R.string.mediaboards_gif_results, items.size)
                        }
                    }
                }) { Text(stringResource(R.string.mediaboards_gif_run)) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                MediaStrip(items)
            }
        }
    }
}

/** #1665 Mediaboards > Stickers: the sticker source and every installed pack, each with its sticker count and a preview strip. */
@Composable
fun StickersScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.mediaboards_stickers), settings = emptyList()) {
        val ctx = LocalContext.current
        var packs by remember { mutableStateOf<List<Pair<StickerRepo.Pack, List<MediaItem>>>?>(null) }
        LaunchedEffect(Unit) {
            packs = withContext(Dispatchers.IO) {
                runCatching { StickerRepo.packs(ctx).map { p -> p to runCatching { StickerRepo.stickers(ctx, StickerRepo.packCategory(p).id) }.getOrDefault(emptyList()) } }
                    .getOrDefault(emptyList())
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreferenceCategory(stringResource(R.string.mediaboards_sticker_source))
            Fact(stringResource(R.string.mediaboards_sticker_panel), stringResource(if (MediaRuntime.stickersEnabled) R.string.kb_theme_on else R.string.kb_theme_off))
            Text(stringResource(R.string.mediaboards_sticker_source_desc), style = MaterialTheme.typography.bodyMedium)
            PreferenceCategory(stringResource(R.string.mediaboards_sticker_packs))
            val list = packs
            when {
                list == null -> Text("…")
                list.isEmpty() -> Text(stringResource(R.string.mediaboards_sticker_none), style = MaterialTheme.typography.bodyMedium)
                else -> list.forEach { (p, s) ->
                    Fact(p.name, ctx.getString(R.string.mediaboards_sticker_count, s.size))
                    MediaStrip(s.take(12))
                }
            }
        }
    }
}

@Composable
private fun Fact(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A row of thumbnails, loaded by libs:media's own image loader (the one the panel uses). */
@Composable
private fun MediaStrip(items: List<MediaItem>) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { v -> runCatching { MediaRuntime.load(ctx, v, item.previewUrl) } },
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            EmojiInfoScreen {}
        }
    }
}

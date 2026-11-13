// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.CornerSwipe
import helium314.keyboard.keyboard.UnexpectedKb
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.KbThemePreset
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory

/**
 * #1622 / #1665 Keyboard > Themes: exactly the two [KbThemePreset]s, Default and Dev, each with a live preview drawn from its real
 * colours and layout. Picking one applies it ([KbThemePreset.apply]: colours, Dev layout, fn key, number row) and redraws the keyboard.
 * Each theme also has its own page ([ThemePageScreen], Keyboard > Themes > Default / Dev).
 */
@Composable
fun ThemesScreen(
    onClickBack: () -> Unit,
    onOpenTheme: (KbThemePreset) -> Unit = {},
) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(KbThemePreset.current(ctx)) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.kb_themes),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            KbThemePreset.entries.forEach { preset ->
                ThemeCard(ctx, preset, selected == preset,
                    onSelect = { KbThemePreset.apply(ctx, preset); selected = preset },
                    onOpen = { onOpenTheme(preset) })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ThemeCard(ctx: Context, preset: KbThemePreset, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { onSelect() }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(stringResource(preset.label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(preset.summary), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.kb_theme_open)) }
        }
        Spacer(Modifier.height(8.dp))
        ThemePreview(ctx, preset)
    }
}

/**
 * #1665 one theme's own page: its preview, Use this theme, and what the theme sets. The Default page also links the colour /
 * style settings it draws with; the Dev page lists its layout behaviour (number row on letters and symbols, double Shift = Caps
 * Lock, corner swipes, fn key: double tap holds, a tap releases).
 */
@Composable
fun ThemePageScreen(
    preset: KbThemePreset,
    onClickBack: () -> Unit,
    onOpenAppearance: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(KbThemePreset.current(ctx) == preset) }
    var redraw by remember { mutableIntStateOf(0) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(preset.label),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(preset.summary), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (redraw >= 0) ThemePreview(ctx, preset)
            Spacer(Modifier.height(8.dp))
            if (active) OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kb_theme_in_use)) }
            else Button(onClick = { KbThemePreset.apply(ctx, preset); active = true; redraw++ }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.kb_theme_use))
            }
            PreferenceCategory(stringResource(R.string.kb_theme_what_it_sets))
            themeFacts(ctx, preset).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (preset == KbThemePreset.DEFAULT) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenAppearance, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_screen_appearance)) }
            }
        }
    }
}

/** #1665 the facts a theme's page lists (label to value), read from the live prefs. */
internal fun themeFacts(ctx: Context, preset: KbThemePreset): List<Pair<String, String>> {
    val p = ctx.prefs()
    val on = ctx.getString(R.string.kb_theme_on)
    val off = ctx.getString(R.string.kb_theme_off)
    fun b(v: Boolean) = if (v) on else off
    return when (preset) {
        KbThemePreset.DEV -> listOf(
            ctx.getString(R.string.kb_theme_fact_colors) to ctx.getString(R.string.kb_theme_dev),
            ctx.getString(R.string.kb_theme_fact_layout) to CornerSwipe.LAYOUT,
            ctx.getString(R.string.number_row) to on,
            ctx.getString(R.string.number_row_in_symbols) to on,
            ctx.getString(R.string.kb_theme_fact_caps) to on,
            ctx.getString(R.string.kb_theme_fact_corners) to on,
            ctx.getString(R.string.kb_theme_fact_fn) to on,
        )
        KbThemePreset.UNEXPECTED -> listOf(
            ctx.getString(R.string.kb_theme_fact_colors) to ctx.getString(R.string.kb_theme_unexpected),
            ctx.getString(R.string.kb_theme_fact_layout) to UnexpectedKb.ASSET,
            ctx.getString(R.string.number_row) to on,
            ctx.getString(R.string.kb_theme_fact_corners) to on,
            ctx.getString(R.string.kb_theme_fact_fn) to on,
        )
        KbThemePreset.DEFAULT -> listOf(
            ctx.getString(R.string.kb_theme_fact_colors) to KbThemePreset.colorThemeName(ctx, preset),
            ctx.getString(R.string.theme_style) to (p.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE) ?: ""),
            ctx.getString(R.string.key_borders) to b(p.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)),
            ctx.getString(R.string.number_row) to b(p.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)),
            ctx.getString(R.string.kb_theme_fact_layout) to ctx.getString(R.string.kb_theme_fact_layout_subtype),
        )
    }
}

private data class Palette(val bg: Color, val key: Color, val fn: Color, val text: Color, val hint: Color, val accent: Color)

private fun palette(ctx: Context, preset: KbThemePreset): Palette {
    val c = KeyboardTheme.previewColors(ctx, KbThemePreset.colorThemeName(ctx, preset))
    fun col(t: ColorType, fallback: Long) = runCatching { c?.get(t)?.let { Color(it) } }.getOrNull() ?: Color(fallback)
    return Palette(
        bg = col(ColorType.MAIN_BACKGROUND, 0xFF0E0E0E),
        key = col(ColorType.KEY_BACKGROUND, 0xFF333333),
        fn = col(ColorType.FUNCTIONAL_KEY_BACKGROUND, 0xFF1F1F1F),
        text = col(ColorType.KEY_TEXT, 0xFFFFFFFF),
        hint = col(ColorType.KEY_HINT_TEXT, 0xFFB8B8B8),
        accent = col(ColorType.GESTURE_TRAIL, 0xFF5C9DED),
    )
}

/** #1665 a live miniature of [preset]: its real colours, its number row, and for Dev the corner-swipe legends and fn key. */
@Composable
fun ThemePreview(ctx: Context, preset: KbThemePreset) {
    val pal = remember(preset) { palette(ctx, preset) }
    val dev = preset.isDark
    val numberRow = dev || ctx.prefs().getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)
    // Unexpected: the miniature is drawn from the same data file as the real keyboard (never a second copy of the keys)
    val data = if (preset == KbThemePreset.UNEXPECTED) remember { UnexpectedKb.load(ctx)?.layer(false) } else null
    val dataKeys = data?.all?.associateBy { it.id }.orEmpty()
    val rows = if (data != null) buildList {
        data.rows.forEachIndexed { i, r ->
            val ks = r.map { it.center }
            add(if (i == data.rows.lastIndex) listOf("⇧") + ks + listOf("⌫") else ks)
        }
        add(listOf("Ctrl", "Fn", "space", "◎", "⏎"))
    } else buildList {
        if (numberRow) add("1234567890".map { it.toString() })
        add("qwertyuiop".map { it.toString() })
        add("asdfghjkl".map { it.toString() })
        add(listOf("⇧") + "zxcvbnm".map { it.toString() } + listOf("⌫"))
        add(if (dev) listOf("?123", "fn", ",", "space", ".", "⏎") else listOf("?123", ",", "space", ".", "⏎"))
    }
    Column(Modifier.fillMaxWidth().background(pal.bg, RoundedCornerShape(6.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { k ->
                    val functional = (r == 0 && numberRow && data == null) || k.length > 1 || k == "⇧" || k == "⌫" || k == "⏎"
                    val w = when (k) { "space" -> 4f; "?123", "⇧", "⌫", "⏎", "Ctrl" -> 1.5f; else -> 1f }
                    val corners = if (data != null) (dataKeys[k.lowercase()]?.corners?.take(4)?.map { it?.label ?: "" }.orEmpty())
                        else if (dev) CornerSwipe.corners[k].orEmpty() else emptyList()
                    Box(
                        Modifier.weight(w).height(30.dp)
                            .background(if (functional && k != "space") pal.fn else pal.key, RoundedCornerShape(4.dp))
                    ) {
                        Text(if (k == "space") "" else k, Modifier.align(Alignment.Center),
                            color = if (k == "⏎" || k == "⇧") pal.accent else pal.text, fontSize = 12.sp, textAlign = TextAlign.Center)
                        val at = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)
                        corners.take(4).forEachIndexed { i, a ->
                            if (a.isNotEmpty() && a != k)
                                Text((if (data != null) a else CornerSwipe.labelOf(a)).take(3), Modifier.align(at[i]).padding(horizontal = 1.dp), color = pal.hint, fontSize = 6.sp)
                        }
                    }
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

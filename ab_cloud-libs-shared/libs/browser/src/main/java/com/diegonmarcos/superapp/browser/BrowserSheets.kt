package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #802 the browser's Compose surfaces, drawn over the page by the Views host
 * (Context.kitComposeView — the #773 interop, no Dialog or PopupWindow). Each one is
 * a pure function of what it is handed; the host owns every action.
 */

/** One row of a sheet: what [BrowserMenuRow] resolves to, or an ad-hoc row (the tab card menu). */
data class SheetRow(val id: String, val label: String, val enabled: Boolean = true, val why: String? = null, val checked: Boolean? = null, val caption: String? = null)

fun BrowserMenuRow.sheet() = SheetRow(item.id, item.label, enabled, why, checked, item.caption)

/** #1141 icon cell: a 48 dp touch target, a 28 sp glyph, an optional one-word caption; a lit (checked) toggle shows in the accent. */
const val ICON_TOUCH_DP = 48
const val ICON_GLYPH_SP = 28

/** A bottom sheet over a scrim: an optional icon row, then titled sections. Scrim tap dismisses. */
@Composable
fun BrowserSheet(
    icons: List<SheetRow>,
    sections: List<Pair<String, List<SheetRow>>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** #1141 further icon rows under [icons] (row 2 …). */
    moreIcons: List<List<SheetRow>> = emptyList(),
) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}   // the sheet itself does not dismiss
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("browser:sheet"),
        ) {
            (listOf(icons) + moreIcons).filter { it.isNotEmpty() }.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { r -> IconCell(r, Modifier.weight(1f), onPick) }
                }
            }
            sections.forEach { (title, rows) ->
                HorizontalDivider(color = p.hairline)
                Text(title, color = p.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                rows.forEach { r ->
                    val suffix = when (r.checked) { true -> "  ✓"; false -> ""; null -> "" }
                    KitSettingsRow(
                        title = r.label + suffix,
                        subtitle = if (r.enabled) "" else r.why.orEmpty(),
                        modifier = Modifier.testTag("browser:menu:${r.id}"),
                        onClick = if (r.enabled) ({ onPick(r.id) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconCell(r: SheetRow, modifier: Modifier, onPick: (String) -> Unit) {
    val p = LocalKitPalette.current
    val ink = when { !r.enabled -> p.textSecondary; r.checked == true -> p.accent; else -> p.textPrimary }
    Column(
        modifier.heightIn(min = ICON_TOUCH_DP.dp).clickable(enabled = r.enabled) { onPick(r.id) }.padding(vertical = 4.dp)
            .testTag("browser:menu:${r.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(r.label, color = ink, fontSize = ICON_GLYPH_SP.sp)
        r.caption?.let { Text(it, color = ink, style = MaterialTheme.typography.labelSmall) }
    }
}

/** Find in page: the query, the match count, previous / next / close. */
@Composable
fun BrowserFindBar(count: Int?, onQuery: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var q by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(8.dp).testTag("browser:find"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(q, { q = it; onQuery(it) }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Find in page") })
        Text(count?.let { " $it " } ?: "", color = p.textSecondary)
        TextButton({ onNext(false) }) { Text("▲") }
        TextButton({ onNext(true) }) { Text("▼") }
        TextButton(onClose) { Text("✕") }
    }
}

/**
 * Settings, drawn from the catalogue: a switch per bool, chips per enum, a slider per int, a field per
 * string. Nothing here names a setting — add one to build.json and it appears.
 *
 * #886 BY TOPIC: [sections] (build.json::ui.browser.settings_sections) is the order and the title of
 * each topic; a setting lands in the one its `section` names, and a section nobody declared still
 * shows (titled from its id) after the declared ones. [extraIn] puts a menu row (a screen) at the end
 * of its topic, [extra] at the page's foot. Dense on purpose: one line per switch, chips instead of a
 * list per choice, a sticky-free single scroll, all of it libs:ui-kit's rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserSettingsScreen(
    catalogue: BrowserSettingsCatalogue,
    value: (String) -> Any?,
    onSet: (String, Any) -> Unit,
    extra: List<SheetRow>,
    onExtra: (String) -> Unit,
    onClose: () -> Unit,
    sections: List<Pair<String, String>> = emptyList(),
    extraIn: Map<String, List<SheetRow>> = emptyMap(),
) {
    val p = LocalKitPalette.current
    var tick by remember { mutableStateOf(0) }
    val order = BrowserSettingsLayout.order(catalogue.settings, sections, extraIn.keys)
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("browser:settings"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Configs", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        order.forEach { (id, title) ->
            KitSectionHeader(title, "", Modifier.padding(top = 8.dp).testTag("browser:settings:section:$id"))
            catalogue.settings.filter { it.section == id }.forEach { s ->
                val v = tick.let { value(s.key) }   // reading tick re-reads the store after a write
                BrowserSettingRow(s, v) { x -> onSet(s.key, x); tick += 1 }
            }
            extraIn[id].orEmpty().forEach { r ->
                KitSettingsRow(r.label, if (r.enabled) "" else r.why.orEmpty(), Modifier.testTag("browser:menu:${r.id}"),
                    onClick = if (r.enabled) ({ onExtra(r.id) }) else null)
            }
        }
        if (extra.isNotEmpty()) {
            HorizontalDivider(color = p.hairline)
            extra.forEach { r ->
                KitSettingsRow(r.label, if (r.enabled) "" else r.why.orEmpty(), Modifier.testTag("browser:menu:${r.id}"),
                    onClick = if (r.enabled) ({ onExtra(r.id) }) else null)
            }
        }
    }
}

/**
 * One catalogue setting as its dense row: a switch per bool, chips per enum, a slider per int, a field per
 * string. Shared by the settings screen (site permissions, zoom) and every Configs page (#900).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserSettingRow(s: BrowserSetting, v: Any?, set: (Any) -> Unit) {
    when (s.type) {
        "bool" -> KitSwitchRow(s.label, s.doc, v == true, { set(it) }, Modifier.testTag("browser:setting:${s.key}"))
        "enum" -> {
            KitSettingsRow(s.label, s.doc.take(90), Modifier.testTag("browser:setting:${s.key}"))
            FlowRow(Modifier.padding(start = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.values.forEach { opt ->
                    FilterChip(selected = v == opt, onClick = { set(opt) },
                        label = { Text(BrowserSettingsLayout.optionLabel(s, opt)) },
                        modifier = Modifier.testTag("browser:setting:${s.key}:$opt"))
                }
            }
        }
        "int" -> {
            var f by remember(v) { mutableFloatStateOf(((v as? Int) ?: s.min ?: 0).toFloat()) }
            KitSettingsRow(s.label, "${f.toInt()}", Modifier.testTag("browser:setting:${s.key}"))
            Slider(f, { f = it }, valueRange = (s.min ?: 0).toFloat()..(s.max ?: 100).toFloat(),
                onValueChangeFinished = { set(f.toInt()) })
        }
        "string" -> {
            var t by remember(v) { mutableStateOf(v?.toString().orEmpty()) }
            OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().testTag("browser:setting:${s.key}"),
                singleLine = true, label = { Text(s.label) }, supportingText = { Text(s.doc) },
                trailingIcon = { TextButton({ set(t) }) { Text("Save") } })
        }
    }
}

/** #886 the topic order and option wording of the Configs page, pure so it is JVM-tested. */
object BrowserSettingsLayout {

    /** (id, title) of every topic to draw: declared ones first (when they hold something), then any other. */
    fun order(settings: List<BrowserSetting>, declared: List<Pair<String, String>>, extraSections: Set<String>): List<Pair<String, String>> {
        val used = settings.map { it.section }.toSet() + extraSections
        val known = declared.filter { it.first in used }
        val rest = settings.map { it.section }.filter { s -> declared.none { it.first == s } }.distinct()
            .map { it to it.replaceFirstChar { c -> c.uppercase() } }
        val restExtra = extraSections.filter { s -> declared.none { it.first == s } && rest.none { it.first == s } }
            .map { it to it.replaceFirstChar { c -> c.uppercase() } }
        return known + rest + restExtra
    }

    /** `on_device` → `On device`; an engine id → its label is not known here, so it is shown as written. */
    fun optionLabel(s: BrowserSetting, opt: String): String =
        s.valueLabels[opt] ?: if (opt.isEmpty()) "Default" else opt.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** A titled, scrollable block of text over a scrim (translation, tab groups). */
@Composable
fun BrowserTextPanel(title: String, text: String, rows: List<SheetRow> = emptyList(), onPick: (String) -> Unit = {}, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp).heightIn(max = 600.dp)
                .background(p.surface, RoundedCornerShape(16.dp)).clickable(remember { MutableInteractionSource() }, null) {}   // the sheet itself does not dismiss
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:panel"),
        ) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            if (text.isNotBlank()) Text(text, color = p.textPrimary, modifier = Modifier.padding(top = 8.dp))
            rows.forEach { r -> KitSettingsRow(r.label, r.why.orEmpty(), onClick = { onPick(r.id) }) }
            TextButton(onClose, Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** One line of a library list (a bookmark, a download, a visit), or a folder header when [header]. */
data class ListRow(val id: String, val title: String, val subtitle: String = "", val header: Boolean = false)

/**
 * #802 the Library pages (bookmarks, downloads, history): a titled list over the page.
 * Tap opens a row; ✕ removes it (or the whole folder, on a header); [headerAction] is
 * a page-wide button (Clear). Rename on a folder header asks for the new path inline.
 */
@Composable
fun BrowserListScreen(
    title: String,
    rows: List<ListRow>,
    empty: String,
    onOpen: (ListRow) -> Unit,
    onRemove: ((ListRow) -> Unit)?,
    onRename: ((ListRow, String) -> Unit)? = null,
    headerAction: Pair<String, () -> Unit>? = null,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("browser:list:$title"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            headerAction?.let { (label, act) -> TextButton(act) { Text(label) } }
        }
        if (rows.isEmpty()) Text(empty, color = p.textSecondary, modifier = Modifier.padding(16.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onOpen(r) }.padding(vertical = 8.dp)) {
                    Text(if (r.header) "▸ ${r.title}" else r.title, color = if (r.header) p.accent else p.textPrimary,
                        style = if (r.header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (r.subtitle.isNotBlank()) Text(r.subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (r.header && onRename != null) TextButton({ renaming = r.id; newName = r.id }) { Text("✎") }
                onRemove?.let { rm -> TextButton({ rm(r) }) { Text("✕") } }
            }
            if (renaming == r.id && onRename != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true)
                    TextButton({ onRename(r, newName); renaming = null }) { Text("Save") }
                }
            }
        }
    }
}

/** #802 "Clear browsing data": one checkbox per declared box, then Clear. */
@Composable
fun BrowserClearScreen(boxes: List<Pair<String, String>>, note: String, onClear: (Set<String>) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var picked by remember { mutableStateOf(boxes.map { it.first }.toSet()) }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:clear")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Clear browsing data", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        boxes.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { picked = if (id in picked) picked - id else picked + id },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in picked, onCheckedChange = null)
                Text(label, color = p.textPrimary)
            }
        }
        Text(note, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        TextButton({ onClear(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("browser:clear:go")) { Text("Clear") }
    }
}

/**
 * #802 the Web Scraper sheet: a CSS selector (typed, or picked by tapping the page), an
 * optional attribute, pages to follow through a "next" link, then Run on the phone or on
 * the server; Export writes the last result to Downloads as CSV.
 */
@Composable
fun BrowserScraperScreen(
    result: String,
    onPick: () -> Unit,
    onUsePicked: ((String) -> Unit) -> Unit,
    onRun: (css: String, attr: String, pages: Int, next: String) -> Unit,
    onRemote: (css: String) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var css by remember { mutableStateOf("") }
    var attr by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf("1") }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:scraper")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Web scraper", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(css, { css = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("CSS selector") })
        Row {
            TextButton(onPick) { Text("Pick on page") }
            TextButton({ onUsePicked { css = it } }) { Text("Use picked") }
        }
        OutlinedTextField(attr, { attr = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Attribute (optional: href, src…)") })
        OutlinedTextField(next, { next = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Next-page link selector (optional)") })
        OutlinedTextField(pages, { pages = it.filter { c -> c.isDigit() }.take(2) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pages") })
        Row {
            TextButton({ onRun(css, attr, pages.toIntOrNull() ?: 1, next) }, enabled = css.isNotBlank()) { Text("Run here") }
            TextButton({ onRemote(css) }) { Text("Run on server") }
            TextButton(onExport) { Text("Export CSV") }
        }
        Text(result, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * #893 Fav: the bookmarks in two views over the same data. List = icon + title + URL; grid = launcher-style tiles
 * (favicon, else the first-letter tile; label below). Both are grouped by folder. Tap opens;
 * long-press opens the edit / move / delete dialog.
 * #1422 no back button (the island is the way out); the declared filters are chips under the title (a chip with an
 * `open` link opens it instead); the grid is [BrowserFavourites.GRID_COLUMNS] weighted tiles per row, so a row fits
 * the screen; a group header tap collapses / opens the group ([collapsed] is persisted by the host).
 */
@Composable
fun BrowserFavScreen(
    sections: List<Pair<String, List<BrowserBookmark>>>,
    view: String,
    icon: (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onToggleView: () -> Unit,
    onOpen: (BrowserBookmark) -> Unit,
    onDelete: (BrowserBookmark) -> Unit,
    onEdit: (BrowserBookmark, String, String) -> Unit,
    filters: List<FavFilter> = emptyList(),
    collapsed: Set<String> = emptySet(),
    onToggleGroup: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val p = LocalKitPalette.current
    var acting by remember { mutableStateOf<BrowserBookmark?>(null) }
    var title by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    val grid = view == BrowserFavourites.VIEW_GRID
    var q by remember { mutableStateOf("") }
    var chip by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val shown = BrowserFavourites.groups(BrowserFavourites.filter(BrowserFavourites.byFilter(sections, chip), q), collapsed)
    fun tile(b: BrowserBookmark, size: androidx.compose.ui.unit.Dp): @Composable () -> Unit = {
        val img = icon(b.url)
        Box(Modifier.size(size).background(p.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (img != null) androidx.compose.foundation.Image(img, null, Modifier.size(size * 0.62f))
            else Text(BrowserFavourites.letter(b.title, b.url), color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        }
    }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp).testTag("browser:fav")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Favourites", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            TextButton(onToggleView, modifier = Modifier.testTag("browser:fav:view")) { Text(if (grid) "☰ List" else "▦ Grid") }
        }
        if (filters.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp).testTag("browser:fav:chips"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filters.forEach { f ->
                val on = chip == f.label
                Text(f.label, color = if (on) p.surface else p.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    modifier = Modifier.background(if (on) p.accent else p.accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                        .clickable { val o = f.open; if (o != null) onOpenUrl(o) else { chip = if (on) null else f.label } }
                        .padding(horizontal = 12.dp, vertical = 6.dp).testTag("browser:fav:chip:${f.id}"))
            }
        }
        // The live filter: title and address, list and grid alike. One thin row, no label.
        androidx.compose.foundation.text.BasicTextField(
            q, { q = it },
            Modifier.fillMaxWidth().height(36.dp).background(p.accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp).testTag("browser:fav:search"),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Filter favourites by title or address", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        inner()
                    }
                    if (q.isNotEmpty()) Text("✕", color = p.textSecondary, modifier = Modifier.clickable { q = "" }.padding(start = 8.dp).testTag("browser:fav:search:clear"))
                }
            })
        if (sections.isEmpty()) Text("No favourites yet: ☆ in the page menu adds this page.", color = p.textSecondary, modifier = Modifier.padding(16.dp))
        else if (shown.isEmpty()) Text("No favourite matches “${q.trim()}”.", color = p.textSecondary, modifier = Modifier.padding(16.dp).testTag("browser:fav:nomatch"))
        shown.forEach { g ->
            if (g.folder.isNotEmpty()) Text("${if (g.collapsed) "▸" else "▾"} ${if (chip != null) BrowserFavourites.subOf(g.folder) else g.folder}  (${g.count})",
                color = p.accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().clickable { onToggleGroup(g.folder) }.padding(top = 8.dp, bottom = 2.dp).testTag("browser:fav:group"))
            if (grid) {
                BrowserFavourites.gridRows(g.items).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { b ->
                            Column(Modifier.weight(1f).padding(vertical = 3.dp, horizontal = 1.dp)
                                .pointerInput(b.url) { detectTapGestures(onTap = { onOpen(b) }, onLongPress = { acting = b; title = b.title; folder = b.folder }) }
                                .testTag("browser:fav:tile"), horizontalAlignment = Alignment.CenterHorizontally) {
                                tile(b, 36.dp)()
                                Text(b.title, color = p.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        repeat(BrowserFavourites.GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            } else g.items.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .pointerInput(b.url) { detectTapGestures(onTap = { onOpen(b) }, onLongPress = { acting = b; title = b.title; folder = b.folder }) }
                    .testTag("browser:fav:row"), verticalAlignment = Alignment.CenterVertically) {
                    tile(b, 44.dp)()
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(b.title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(b.url, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
    acting?.let { b ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text("Edit favourite") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(folder, { folder = it }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                    Text(b.url, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton({ onEdit(b, title, folder); acting = null }) { Text("Save") } },
            dismissButton = { Row { TextButton({ onDelete(b); acting = null }) { Text("Delete") }; TextButton({ acting = null }) { Text("Cancel") } } },
        )
    }
}

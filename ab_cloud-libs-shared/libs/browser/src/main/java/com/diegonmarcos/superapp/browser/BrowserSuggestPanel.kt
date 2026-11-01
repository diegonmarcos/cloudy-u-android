package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #886 what the address bar's dropdown shows, as Compose state the host writes and the panel reads.
 * [engineLabel] titles the first section (the default engine: DuckDuckGo out of the box).
 */
class SuggestState {
    var sections by mutableStateOf(BrowserSuggest.Sections(emptyList(), emptyList()))
    var engineLabel by mutableStateOf("")
    var visible by mutableStateOf(false)
    /** The text the sections answer, so a late remote answer for an older query is dropped. */
    var query by mutableStateOf("")

    val showing: Boolean get() = visible && !sections.isEmpty
}

/** One flat row of the panel: a section title or a suggestion. */
sealed class SuggestRow {
    data class Title(val text: String) : SuggestRow()
    data class Item(val s: BrowserSuggest.Suggestion) : SuggestRow()

    companion object {
        /** "Web search" names what the first section is; the engine says whose suggestions they are. */
        fun searchTitle(engineLabel: String) = if (engineLabel.isBlank()) "Web search" else "Web search · $engineLabel"

        /** The labelled sections as one list; a section with nothing in it draws no title. */
        fun build(sections: BrowserSuggest.Sections, engineLabel: String): List<SuggestRow> = buildList {
            if (sections.search.isNotEmpty()) { add(Title(searchTitle(engineLabel))); sections.search.forEach { add(Item(it)) } }
            if (sections.history.isNotEmpty()) { add(Title("History")); sections.history.forEach { add(Item(it)) } }
            if (sections.favourites.isNotEmpty()) { add(Title("Favorites")); sections.favourites.forEach { add(Item(it)) } }
        }
    }
}

/**
 * THE DROPDOWN, laid out IN the page area under the address bar (it is a child of the frame that holds
 * the WebView, not a PopupWindow anchored to a field), so it cannot cover the bar by construction.
 * Its height is capped by the frame's own height, which shrinks when the keyboard opens (the window
 * resizes), so it never runs under the keyboard either; a long list scrolls; a tap on the dimmed page,
 * the back button or the keyboard's Go dismisses it.
 */
/** The rows of [sections] under their titles; used by the overlay and by the New tab screen. */
@Composable
internal fun SuggestRows(rows: List<SuggestRow>, onPick: (BrowserSuggest.Suggestion) -> Unit, modifier: Modifier) {
    val p = LocalKitPalette.current
    LazyColumn(modifier) {
        items(rows) { r ->
            when (r) {
                is SuggestRow.Title -> Column {
                    Text(r.text, color = p.accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 1.dp))
                    HorizontalDivider(color = p.hairline)
                }
                is SuggestRow.Item -> Row(
                    Modifier.fillMaxWidth().clickable { onPick(r.s) }.padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("browser:suggest:${r.s.source.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(when (r.s.source) { BrowserSuggest.Source.SEARCH -> "🔍"; BrowserSuggest.Source.TAB -> "▣"; BrowserSuggest.Source.FAV -> "★"; else -> "⏱" },
                        color = p.textSecondary, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.s.label, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        if (r.s.source != BrowserSuggest.Source.SEARCH && r.s.subtitle.isNotBlank())
                            Text(r.s.subtitle, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * THE DROPDOWN, laid out IN the page area under the address bar (it is a child of the frame that holds
 * the WebView, not a PopupWindow anchored to a field), so it cannot cover the bar by construction.
 * Its height is capped by the frame's own height, which shrinks when the keyboard opens (the window
 * resizes), so it never runs under the keyboard either; a long list scrolls; a tap on the dimmed page,
 * the back button or the keyboard's Go dismisses it.
 */
@Composable
fun BrowserSuggestOverlay(state: SuggestState, onPick: (BrowserSuggest.Suggestion) -> Unit, onDismiss: () -> Unit) {
    if (!state.showing) return
    val p = LocalKitPalette.current
    val rows = remember(state.sections, state.engineLabel) { SuggestRow.build(state.sections, state.engineLabel) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss).testTag("browser:suggest:scrim")) {
        SuggestRows(rows, onPick,
            Modifier.fillMaxWidth().heightIn(max = maxHeight).background(p.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}   // the list itself does not dismiss
                .testTag("browser:suggest"))
    }
}

/**
 * The New tab screen: a field and, under it, the same three sections, filling the rest of the screen
 * above the keyboard (the screen resizes with it). Enter opens what was typed; a row opens that row.
 */
@Composable
fun BrowserOpenScreen(
    title: String,
    hint: String,
    state: SuggestState,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPick: (BrowserSuggest.Suggestion) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rows = remember(state.sections, state.engineLabel) { SuggestRow.build(state.sections, state.engineLabel) }
    Column(Modifier.fillMaxSize().background(p.surface).testTag("browser:open")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(text, { text = it; onQuery(it) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus).testTag("browser:open:field"),
            singleLine = true, placeholder = { Text(hint) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(text) }))
        SuggestRows(rows, onPick, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * #1410 the Search page's first box: the browser's own address/search box. As you type, [onQuery]
 * refreshes [state] with the address bar's sections (history + bookmarks, same ranking) and the
 * dropdown is the address bar's own [SuggestRows]. Enter searches or opens; a row opens that row.
 */
@Composable
fun BrowserSearchBox(
    hint: String,
    state: SuggestState,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPick: (BrowserSuggest.Suggestion) -> Unit,
) {
    val p = LocalKitPalette.current
    var text by remember { mutableStateOf("") }
    val rows = remember(state.sections, state.engineLabel) { SuggestRow.build(state.sections, state.engineLabel) }
    Column(Modifier.fillMaxWidth().background(p.surface).testTag("browser:searchbox")) {
        OutlinedTextField(text, { text = it; state.visible = it.isNotBlank(); onQuery(it) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("browser:searchbox:field"),
            singleLine = true, placeholder = { Text(hint) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(text) }))
        if (state.showing) SuggestRows(rows, onPick, Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("browser:searchbox:suggest"))
    }
}

package com.diegonmarcos.cloudlib.jsonviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.uikit.KitDensity

object JsonViewerTags {
    const val ROOT = "json_viewer"
    const val SEARCH = "json_search"
    const val ERROR = "json_error"
    const val COPY_PATH = "json_copy_path"
    const val COPY_VALUE = "json_copy_value"
    fun row(path: String) = "json_row_$path"
}

/** The words of the viewer; the host passes its translation. */
data class JsonLabels(val search: String = "Search", val notJson: String = "This file is not valid JSON", val copyPath: String = "Copy path", val copyValue: String = "Copy value", val selected: String = "Select a line")

/** The colours of the syntax, taken from the Material roles of the host theme (the GitHub-dark look of the webserver's viewer on the fleet's tokens). */
data class JsonColors(val key: Color, val string: Color, val number: Color, val keyword: Color, val punct: Color, val text: Color, val selected: Color) {
    companion object {
        @Composable
        fun themed() = JsonColors(
            key = MaterialTheme.colorScheme.primary,
            string = MaterialTheme.colorScheme.tertiary,
            number = MaterialTheme.colorScheme.secondary,
            keyword = MaterialTheme.colorScheme.error,
            punct = MaterialTheme.colorScheme.onSurfaceVariant,
            text = MaterialTheme.colorScheme.onSurface,
            selected = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** A JSON text as a collapsible, searchable tree. Tap a line to open or close it and select it; the bar below copies its path or value. */
@Composable
fun JsonViewer(text: String, modifier: Modifier = Modifier, labels: JsonLabels = JsonLabels(), colors: JsonColors = JsonColors.themed()) {
    val tree = remember(text) { JsonTree.parse(text) }
    if (tree == null) {
        Text(labels.notJson, modifier.padding(KitDensity.large).testTag(JsonViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var expanded by remember(tree) { mutableStateOf(tree.defaultExpanded()) }
    var query by remember(tree) { mutableStateOf("") }
    var selected by remember(tree) { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    val rows = remember(tree, expanded, query) { tree.rows(expanded, query) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = KitDensity.body, color = colors.text)

    Column(modifier.fillMaxSize().testTag(JsonViewerTags.ROOT)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2).clip(RoundedCornerShape(KitDensity.small)).background(MaterialTheme.colorScheme.surfaceVariant).padding(KitDensity.small)) {
            BasicTextField(
                query, { query = it }, Modifier.fillMaxWidth().testTag(JsonViewerTags.SEARCH), singleLine = true,
                textStyle = mono, cursorBrush = SolidColor(colors.key),
                decorationBox = { inner -> if (query.isEmpty()) Text(labels.search, style = mono.copy(color = colors.punct)); inner() },
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.rule)) {
            items(rows, key = { it.path }) { r ->
                Row(
                    Modifier.fillMaxWidth().testTag(JsonViewerTags.row(r.path))
                        .background(if (r.path == selected) colors.selected else Color.Transparent)
                        .clickable {
                            selected = r.path
                            if (r.expandable) expanded = if (r.expanded) expanded - r.path else expanded + r.path
                        }
                        .padding(start = KitDensity.rowPadH + KitDensity.large * r.depth, end = KitDensity.rowPadH, top = KitDensity.rowPadV / 2, bottom = KitDensity.rowPadV / 2),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(if (!r.expandable) " " else if (r.expanded) "▾" else "▸", Modifier.width(KitDensity.large), style = mono.copy(color = colors.punct))
                    if (r.key != null) {
                        Text(r.key, style = mono.copy(color = colors.key, fontWeight = FontWeight.Medium))
                        Text(": ", style = mono.copy(color = colors.punct))
                    }
                    val ink = when (r.kind) {
                        JsonKind.String -> colors.string
                        JsonKind.Number -> colors.number
                        JsonKind.Boolean, JsonKind.Null -> colors.keyword
                        else -> colors.punct
                    }
                    Text(r.text, Modifier.weight(1f), style = mono.copy(color = ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV), horizontalArrangement = Arrangement.spacedBy(KitDensity.medium), verticalAlignment = Alignment.CenterVertically) {
            Text(selected ?: labels.selected, Modifier.weight(1f), style = mono.copy(color = colors.punct), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(labels.copyPath, Modifier.testTag(JsonViewerTags.COPY_PATH).clickable(enabled = selected != null) { selected?.let { clip.setText(AnnotatedString(tree.pathOf(it))) } }, fontSize = KitDensity.body, color = colors.key)
            Text(labels.copyValue, Modifier.testTag(JsonViewerTags.COPY_VALUE).clickable(enabled = selected != null) { selected?.let { p -> tree.plainAt(p)?.let { clip.setText(AnnotatedString(it)) } } }, fontSize = KitDensity.body, color = colors.key)
        }
    }
}

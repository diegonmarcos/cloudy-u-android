package com.diegonmarcos.cloudlib.odf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.cloudlib.wysiwyg.DocEditor
import com.diegonmarcos.cloudlib.wysiwyg.DocView
import com.diegonmarcos.superapp.uikit.KitDensity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object OdfViewerTags {
    const val ERROR = "odf_error"
    const val SHEET_TABS = "odf_sheet_tabs"
    fun sheet(i: Int) = "odf_sheet_$i"
    fun cell(r: Int, c: Int) = "odf_cell_${r}_$c"
    fun slide(i: Int) = "odf_slide_$i"
    const val TEXT = "odf_text"
}

/** The words of the viewer; the host passes its translation. */
data class OdfLabels(val cannotOpen: String = "Cannot open this file", val slide: String = "Slide %1\$d")

/**
 * Shows an odt (in the WYSIWYG engine), an ods (as a table, one tab per sheet) or an odp (slides with their text). With [editing]
 * on, edits go into the file's own XML and are written back a moment after the last keystroke, everything else in the package untouched.
 */
@Composable
fun OdfViewer(file: File, editing: Boolean, modifier: Modifier = Modifier, labels: OdfLabels = OdfLabels()) {
    var revision by remember(file) { mutableIntStateOf(0) }
    val odf = remember(file, revision) { runCatching { OdfFile.open(file.readBytes()) }.getOrNull() }
    if (odf == null) {
        Text(labels.cannotOpen, modifier.padding(KitDensity.large).testTag(OdfViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // The edits are in the DOM at once; the file is written once the typing stops.
    var dirty by remember(odf) { mutableIntStateOf(0) }
    val pending = remember(odf) { AtomicBoolean(false) }
    val touch: () -> Unit = { pending.set(true); dirty++ }
    LaunchedEffect(dirty) {
        if (dirty > 0) {
            delay(SAVE_DELAY_MS)
            if (pending.getAndSet(false)) withContext(Dispatchers.IO) { runCatching { file.writeBytes(odf.save()) } }
        }
    }
    // Leaving the tab inside the delay must not lose the last keystrokes.
    DisposableEffect(odf) { onDispose { if (pending.getAndSet(false)) runCatching { file.writeBytes(odf.save()) } } }
    when (odf.kind) {
        OdfKind.Text -> {
            val doc = remember(odf) { odf.toDoc() }
            Box(modifier.fillMaxSize().testTag(OdfViewerTags.TEXT)) {
                if (editing) DocEditor(doc, { runCatching { odf.applyDoc(it) }; touch() }) else DocView(doc)
            }
        }
        OdfKind.Spreadsheet -> SheetsView(odf, editing, modifier, onEdit = touch)
        OdfKind.Presentation -> SlidesView(odf, editing, labels, modifier, onEdit = touch)
    }
}

@Composable
private fun SheetsView(odf: OdfFile, editing: Boolean, modifier: Modifier, onEdit: () -> Unit) {
    val sheets = remember(odf) { odf.sheets() }
    var current by remember(odf) { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        if (sheets.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2).testTag(OdfViewerTags.SHEET_TABS), horizontalArrangement = Arrangement.spacedBy(KitDensity.rowPadH / 2)) {
                sheets.forEachIndexed { i, s ->
                    Text(
                        s.name,
                        Modifier.clip(RoundedCornerShape(KitDensity.small)).background(if (i == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable { current = i }.padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV / 2),
                        fontSize = KitDensity.body, color = if (i == current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val sheet = sheets.getOrNull(current) ?: return@Column
        val cols = maxOf(sheet.width, MIN_COLS) + if (editing) 1 else 0
        val rows = sheet.rows.size + if (editing) 1 else 0
        val cellWidth = KitDensity.avatar * 2
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(OdfViewerTags.sheet(current))) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(List(rows) { it }) { _, r ->
                    Row(Modifier.height(KitDensity.glyph + KitDensity.small)) {
                        Text("${r + 1}", Modifier.width(KitDensity.large * 2).padding(horizontal = KitDensity.rowPadV / 2), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (c in 0 until cols) {
                            val value = sheet.rows.getOrNull(r)?.getOrNull(c).orEmpty()
                            Cell(current, r, c, value, editing, cellWidth) { text -> runCatching { odf.setCell(current, r, c, text) }; onEdit() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(sheet: Int, r: Int, c: Int, value: String, editing: Boolean, width: androidx.compose.ui.unit.Dp, onSet: (String) -> Unit) {
    val ink = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = KitDensity.body)
    val frame = Modifier.width(width).fillMaxHeightCell().background(MaterialTheme.colorScheme.surface).padding(horizontal = KitDensity.rowPadV, vertical = KitDensity.rowPadV / 2)
    if (editing) {
        var text by remember(sheet, r, c) { mutableStateOf(value) }
        BasicTextField(text, { text = it; onSet(it) }, frame.testTag(OdfViewerTags.cell(r, c)), textStyle = ink, singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
    } else {
        Text(value, frame.testTag(OdfViewerTags.cell(r, c)), style = ink, maxLines = 1)
    }
}

private fun Modifier.fillMaxHeightCell(): Modifier = this.height(KitDensity.glyph + KitDensity.small)

@Composable
private fun SlidesView(odf: OdfFile, editing: Boolean, labels: OdfLabels, modifier: Modifier, onEdit: () -> Unit) {
    val slides = remember(odf) { odf.slides() }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = KitDensity.rowPadH), verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadH)) {
        itemsIndexed(slides) { si, slide ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner)).background(MaterialTheme.colorScheme.surfaceVariant).padding(KitDensity.medium).testTag(OdfViewerTags.slide(si)),
                verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadV),
            ) {
                Text(slide.name.ifBlank { String.format(labels.slide, si + 1) }, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                slide.texts.forEachIndexed { ti, text ->
                    val ink = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = if (ti == 0) KitDensity.title else KitDensity.body)
                    if (editing) {
                        var value by remember(odf, si, ti) { mutableStateOf(text) }
                        BasicTextField(value, { value = it; runCatching { odf.setSlideText(si, ti, it) }; onEdit() }, Modifier.fillMaxWidth(), textStyle = ink, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                    } else Text(text, style = ink)
                }
            }
        }
    }
}

private const val SAVE_DELAY_MS = 600L
private const val MIN_COLS = 6

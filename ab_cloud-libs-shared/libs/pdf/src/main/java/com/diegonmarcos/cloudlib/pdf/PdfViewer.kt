package com.diegonmarcos.cloudlib.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import com.diegonmarcos.superapp.uikit.KitDensity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfViewerTags {
    const val LIST = "pdf_list"
    fun page(i: Int) = "pdf_page_$i"
    fun tool(id: String, page: Int) = "pdf_tool_${id}_$page"
    const val FORM = "pdf_form"
    const val ERROR = "pdf_error"
}

/** The words of the viewer; the host passes its translation. */
data class PdfLabels(
    val cannotOpen: String = "Cannot open this PDF",
    val ink: String = "Draw",
    val inkDone: String = "Done",
    val note: String = "Note",
    val text: String = "Text",
    val delete: String = "Delete page",
    val up: String = "Move up",
    val down: String = "Move down",
    val rotate: String = "Rotate",
    val form: String = "Fill form",
    val ok: String = "OK",
    val cancel: String = "Cancel",
    val pageOf: String = "Page %1\$d of %2\$d",
    val noForm: String = "This PDF has no form fields",
)

private enum class Tap { None, Note, Text }

/**
 * Shows [file] page by page with the platform PdfRenderer. With [editing] on, every page gets its tools: draw (ink), note and text
 * (tap the page, type), rotate, move and delete, and the Form button fills the text fields. Each edit is applied to the file with
 * PdfBox-Android and the pages are reloaded, so what is on screen is what is on disk.
 */
@Composable
fun PdfViewer(file: File, editing: Boolean, modifier: Modifier = Modifier, labels: PdfLabels = PdfLabels()) {
    val context = LocalContext.current
    var revision by remember(file) { mutableIntStateOf(0) }
    val pages = remember(file, revision) { runCatching { PdfPages(file) }.getOrNull() }
    DisposableEffect(pages) { onDispose { pages?.close() } }
    LaunchedEffect(Unit) { runCatching { PDFBoxResourceLoader.init(context.applicationContext) } }
    var formOpen by remember(file) { mutableStateOf(false) }

    fun edit(block: (PdfEditSession) -> Unit) {
        runCatching { PdfEditSession.edit(file, block) }
        revision++
    }

    if (pages == null) {
        Text(labels.cannotOpen, modifier.padding(KitDensity.large).testTag(PdfViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier.fillMaxSize()) {
        if (editing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2), horizontalArrangement = Arrangement.End) {
                Text(
                    labels.form,
                    Modifier.testTag(PdfViewerTags.FORM).clip(RoundedCornerShape(KitDensity.small)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { formOpen = true }.padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV / 2),
                    fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            LazyColumn(Modifier.fillMaxSize().testTag(PdfViewerTags.LIST), verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadV)) {
                itemsIndexed(List(pages.count) { it }, key = { _, i -> "$revision-$i" }) { _, i ->
                    PdfPageItem(pages, i, widthPx, editing, labels, count = pages.count, onEdit = ::edit)
                }
            }
        }
    }
    if (formOpen) FormDialog(file, labels, onDismiss = { formOpen = false }, onApply = { values -> formOpen = false; edit { it.fillForm(values) } })
}

@Composable
private fun PdfPageItem(pages: PdfPages, index: Int, widthPx: Int, editing: Boolean, labels: PdfLabels, count: Int, onEdit: ((PdfEditSession) -> Unit) -> Unit) {
    var bitmap by remember(pages, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pages, index, widthPx) { bitmap = withContext(Dispatchers.IO) { runCatching { pages.render(index, widthPx) }.getOrNull() } }
    var inking by remember(index) { mutableStateOf(false) }
    var tap by remember(index) { mutableStateOf(Tap.None) }
    var pending by remember(index) { mutableStateOf<Offset?>(null) }
    val strokes = remember(index) { mutableStateListOf<List<Offset>>() }
    var shown by remember(index) { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxWidth()) {
        Text(String.format(labels.pageOf, index + 1, count), Modifier.padding(horizontal = KitDensity.rowPadH), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (editing) {
            Row(Modifier.padding(horizontal = KitDensity.rowPadH), horizontalArrangement = Arrangement.spacedBy(KitDensity.rowPadH / 2), verticalAlignment = Alignment.CenterVertically) {
                PageTool("ink", index, Icons.Filled.Draw, labels.ink, inking) {
                    if (inking && strokes.isNotEmpty() && shown.width > 0) {
                        val drawn = strokes.map { s -> s.map { o -> o.x / shown.width to o.y / shown.height } }
                        strokes.clear()
                        onEdit { s -> val ps = s.pageSize(index); s.addInk(index, drawn.map { st -> st.map { (u, v) -> PdfGeometry.toPdf(u, v, ps) } }) }
                    }
                    inking = !inking
                    tap = Tap.None
                }
                PageTool("note", index, Icons.Filled.StickyNote2, labels.note, tap == Tap.Note) { tap = if (tap == Tap.Note) Tap.None else Tap.Note; inking = false }
                PageTool("text", index, Icons.Filled.TextFields, labels.text, tap == Tap.Text) { tap = if (tap == Tap.Text) Tap.None else Tap.Text; inking = false }
                PageTool("rotate", index, Icons.Filled.RotateRight, labels.rotate, false) { onEdit { it.rotatePage(index) } }
                PageTool("up", index, Icons.Filled.ArrowUpward, labels.up, false) { if (index > 0) onEdit { it.movePage(index, index - 1) } }
                PageTool("down", index, Icons.Filled.ArrowDownward, labels.down, false) { if (index < count - 1) onEdit { it.movePage(index, index + 1) } }
                PageTool("delete", index, Icons.Filled.Delete, labels.delete, false) { if (count > 1) onEdit { it.deletePage(index) } }
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Box(Modifier.fillMaxWidth().testTag(PdfViewerTags.page(index))) {
                Image(
                    bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().pointerInput(index, tap) {
                        if (tap != Tap.None) detectTapGestures { pending = it }
                    },
                )
                if (editing && inking) {
                    Canvas(
                        Modifier.matchParentSize().pointerInput(index) {
                            var current = ArrayList<Offset>()
                            detectDragGestures(
                                onDragStart = { current = arrayListOf(it); strokes.add(current.toList()) },
                                onDrag = { change, _ ->
                                    current.add(change.position)
                                    strokes[strokes.size - 1] = current.toList()
                                },
                            )
                        },
                    ) {
                        for (s in strokes) {
                            val path = Path()
                            s.forEachIndexed { k, o -> if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                            drawPath(path, SolidColor(INK_PREVIEW), style = Stroke(width = KitDensity.rule.toPx() * 2, cap = StrokeCap.Round))
                        }
                    }
                }
            }
            LaunchedEffect(bmp) { shown = IntSize(bmp.width, bmp.height) }
        } else {
            Box(Modifier.fillMaxWidth().height(KitDensity.avatar * 4))
        }
    }
    val at = pending
    if (at != null && tap != Tap.None && shown.width > 0) {
        TextDialog(if (tap == Tap.Note) labels.note else labels.text, labels, onDismiss = { pending = null }) { text ->
            val kind = tap
            pending = null
            onEdit { s ->
                val p = PdfGeometry.toPdf(at.x / shown.width, at.y / shown.height, s.pageSize(index))
                if (kind == Tap.Note) s.addNote(index, p.x, p.y, text) else s.addText(index, p.x, p.y, text)
            }
        }
    }
}

private val INK_PREVIEW = Color(0xFF000000.toInt() or PdfEditSession.DEFAULT_INK)

@Composable
private fun PageTool(id: String, page: Int, icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.testTag(PdfViewerTags.tool(id, page)).clip(RoundedCornerShape(KitDensity.small))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(KitDensity.rowPadV),
    ) {
        Icon(icon, contentDescription = label, Modifier.size(KitDensity.glyph - KitDensity.small), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextDialog(title: String, labels: PdfLabels, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = KitDensity.title) },
        text = { BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = KitDensity.body), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)) },
        confirmButton = { Text(labels.ok, Modifier.clickable { if (text.isNotBlank()) onOk(text) }.padding(KitDensity.medium), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.primary) },
        dismissButton = { Text(labels.cancel, Modifier.clickable(onClick = onDismiss).padding(KitDensity.medium), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun FormDialog(file: File, labels: PdfLabels, onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    var fields by remember(file) { mutableStateOf<List<PdfFormField>?>(null) }
    LaunchedEffect(file) { fields = withContext(Dispatchers.IO) { runCatching { PdfEditSession.open(file).use { it.formFields() } }.getOrDefault(emptyList()) } }
    val values = remember(file) { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(fields) { values.clear(); fields?.filter { it.kind == PdfFormField.Kind.Text || it.kind == PdfFormField.Kind.Checkbox }?.forEach { values += it.name to it.value } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.form, fontSize = KitDensity.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadV)) {
                if (values.isEmpty()) Text(labels.noForm, fontSize = KitDensity.body)
                values.forEachIndexed { i, (name, value) ->
                    Text(name, fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(value, { values[i] = name to it }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = KitDensity.body), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                }
            }
        },
        confirmButton = { Text(labels.ok, Modifier.clickable { onApply(values.toMap()) }.padding(KitDensity.medium), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.primary) },
        dismissButton = { Text(labels.cancel, Modifier.clickable(onClick = onDismiss).padding(KitDensity.medium), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

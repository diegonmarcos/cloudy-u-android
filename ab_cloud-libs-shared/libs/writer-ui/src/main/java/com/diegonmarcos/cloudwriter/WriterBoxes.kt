package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.cloudwriter.core.EditMode
import com.diegonmarcos.cloudwriter.core.Export
import com.diegonmarcos.cloudwriter.core.ExportFormat
import com.diegonmarcos.cloudwriter.core.HtmlText
import com.diegonmarcos.cloudwriter.core.Import
import com.diegonmarcos.cloudwriter.core.Markdown
import com.diegonmarcos.cloudwriter.ui.KitBridge
import com.diegonmarcos.superapp.uikit.KitBadge
import com.diegonmarcos.superapp.uikit.KitBadgeChip
import com.diegonmarcos.cloudwriter.core.ImportKind
import com.diegonmarcos.cloudwriter.core.Ocr
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.superapp.mlocr.OcrImage
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import java.io.File
import java.util.concurrent.Executor

/**
 * #1418 ONE BOX of a tool tab (input or output), in either edit mode.
 *
 * The canonical value is HTML ([html]): in WYSIWYG it is the rich editor's own HTML, in Text only
 * it is [plain] through core HtmlText. Switching mode converts through that one value, the engines
 * get [text], export renders [html], and OCR appends to [html] (core Ocr.intoBox), so the four can
 * never disagree about what the box holds. WYSIWYG is the default (core EditMode.DEFAULT).
 */
class BoxState(initial: EditMode = EditMode.DEFAULT) {
    val rich = RichTextState()
    var mode by mutableStateOf(initial)
    var plain by mutableStateOf("")

    fun html(): String = if (mode == EditMode.WYSIWYG) rich.toHtml() else HtmlText.textToHtml(plain)
    fun text(): String = HtmlText.htmlToText(html())

    fun setHtml(html: String) {
        rich.setHtml(html)
        plain = HtmlText.htmlToText(html)
    }

    fun setText(text: String) = setHtml(HtmlText.textToHtml(text))

    fun switchTo(m: EditMode) {
        if (m == mode) return
        val h = html()
        mode = m
        setHtml(h)
    }
}

/** What the input boxes can do besides typing: load a file, OCR an image or a photo, export. */
class BoxIo(
    val loadFile: (BoxState) -> Unit,
    val loadImage: (BoxState) -> Unit,
    val takePicture: (BoxState) -> Unit,
    val export: (BoxState, ExportFormat) -> Unit,
    val copy: (BoxState) -> Unit,
)

/**
 * The launchers behind [BoxIo], one set per screen. [note] is how every outcome is said: a load,
 * an OCR read (with the engine that answered, and the fallback reason), an export, a failure.
 */
@Composable
fun rememberBoxIo(context: Context, worker: Executor, note: (String) -> Unit): BoxIo {
    val main = remember { Handler(Looper.getMainLooper()) }
    var target by remember { mutableStateOf<BoxState?>(null) }
    var shot by remember { mutableStateOf<Uri?>(null) }

    fun ocr(uri: Uri) {
        val box = target ?: return
        ocrInto(context, worker, box, uri, note)
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val box = target
        if (uri != null && box != null) worker.execute {
            val name = displayName(context, uri)
            val kind = Import.kind(name, context.contentResolver.getType(uri))
            val html = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?.let { Import.toHtml(kind, it) }
            main.post {
                when {
                    kind == ImportKind.UNSUPPORTED -> note(context.getString(R.string.box_load_unsupported, name))
                    html == null -> note(context.getString(R.string.box_load_failed, name))
                    else -> { box.setHtml(html); note(context.getString(R.string.box_loaded, name)) }
                }
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ocr(uri) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> shot?.takeIf { ok }?.let { ocr(it) } }
    var pendingExport by remember { mutableStateOf<Pair<BoxState, ExportFormat>?>(null) }
    val exporters = ExportFormat.values().associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri ->
            val (box, format) = pendingExport ?: return@rememberLauncherForActivityResult
            if (uri != null) {
                val body = Export.render(format, box.html(), Export.fileName(box.html(), format).substringBeforeLast('.'))
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
                    .onSuccess { note(context.getString(R.string.box_exported, displayName(context, uri))) }
                    .onFailure { note(context.getString(R.string.box_export_failed, it.message.orEmpty())) }
            }
        }
    }
    return BoxIo(
        loadFile = { box -> target = box; openFile.launch(arrayOf("text/*", "text/markdown", "text/html", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")) },
        loadImage = { box -> target = box; pickImage.launch("image/*") },
        takePicture = { box ->
            target = box
            val dir = File(context.cacheDir, "ocr").apply { mkdirs() }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".ocr", File(dir, "shot.jpg"))
            shot = uri
            takePhoto.launch(uri)
        },
        export = { box, format -> pendingExport = box to format; exporters.getValue(format).launch(Export.fileName(box.html(), format)) },
        copy = { box ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("writer", box.text()))
            note(context.getString(R.string.box_copied))
        },
    )
}

/**
 * Reads the text in the image at [uri] on the Configs engine (WriterRoutes.ocr) and appends it to
 * [box] (core Ocr.intoBox), saying which engine answered, why it fell back, or why it failed.
 * Used by Load image, Take picture and an image shared into Writer.
 */
fun ocrInto(context: Context, worker: Executor, box: BoxState, uri: Uri, note: (String) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    note(context.getString(R.string.ocr_running))
    worker.execute {
        val bmp = OcrImage.load(context, uri)
        val a: Answer = if (bmp == null) Answer(null, null, Route.ML, null, context.getString(R.string.ocr_no_image))
        else WriterRoutes.ocr(context, bmp)
        main.post {
            if (a.ok) {
                box.setHtml(Ocr.intoBox(box.html(), a.text.orEmpty()))
                note(
                    if (a.fellBack) context.getString(R.string.ocr_fell_back, a.fallbackReason.orEmpty())
                    else context.getString(R.string.ocr_done, a.text.orEmpty().length, a.route?.id.orEmpty()),
                )
            } else note(context.getString(R.string.ocr_failed, a.error.orEmpty()))
        }
    }
}

internal fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

/**
 * The box: its label, the mode selector (WYSIWYG | Text only), the formatting bar in WYSIWYG,
 * the editor, and its actions (input boxes: Load file, Load image, Take picture; every box: Export
 * and Copy).
 */
@Composable
fun WriterBox(label: String, state: BoxState, io: BoxIo, input: Boolean, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                // #1457 the box's own word count, in the kit's badge.
                KitBridge { KitBadgeChip(KitBadge(stringResource(R.string.editor_words, Markdown.words(state.text())))) }
                FilterChip(state.mode == EditMode.WYSIWYG, { state.switchTo(EditMode.WYSIWYG) }, { Text(stringResource(R.string.box_mode_wysiwyg)) })
                FilterChip(state.mode == EditMode.TEXT, { state.switchTo(EditMode.TEXT) }, { Text(stringResource(R.string.box_mode_text)) })
            }
            if (state.mode == EditMode.WYSIWYG) {
                FormatStrip(state.rich)
                RichTextEditor(state = state.rich, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
            } else {
                OutlinedTextField(value = state.plain, onValueChange = { state.plain = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (input) {
                    Action(Icons.Rounded.FileOpen, R.string.box_load_file) { io.loadFile(state) }
                    Action(Icons.Rounded.AddPhotoAlternate, R.string.box_load_image) { io.loadImage(state) }
                    Action(Icons.Rounded.PhotoCamera, R.string.box_take_picture) { io.takePicture(state) }
                }
                ExportMenu { io.export(state, it) }
                Action(Icons.Rounded.ContentCopy, R.string.box_copy) { io.copy(state) }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label)) }
}

@Composable
private fun ExportMenu(onPick: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(Icons.Rounded.FileUpload, R.string.box_export) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(ExportFormat.MD to R.string.box_export_md, ExportFormat.TXT to R.string.box_export_txt, ExportFormat.HTML to R.string.box_export_html)
                .forEach { (f, l) -> DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { open = false; onPick(f) }) }
        }
    }
}

/** The WYSIWYG bar: bold, italic, headings, lists, quote, code, link — the modern email composer set. */
@Composable
private fun FormatStrip(rich: RichTextState) {
    var linkOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Action(Icons.Rounded.FormatBold, R.string.fmt_bold) { rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
        Action(Icons.Rounded.FormatItalic, R.string.fmt_italic) { rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
        // The editor's own heading styles, so its HTML writes them back as <h1>/<h2>.
        Action(Icons.Rounded.Title, R.string.fmt_h1) { rich.toggleSpanStyle(SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold)) }
        Action(Icons.Rounded.Title, R.string.fmt_h2) { rich.toggleSpanStyle(SpanStyle(fontSize = 1.5.em, fontWeight = FontWeight.Bold)) }
        Action(Icons.AutoMirrored.Rounded.FormatListBulleted, R.string.fmt_bullets) { rich.toggleUnorderedList() }
        Action(Icons.Rounded.FormatListNumbered, R.string.fmt_numbered) { rich.toggleOrderedList() }
        Action(Icons.Rounded.FormatQuote, R.string.fmt_quote) {
            rich.toggleParagraphStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 1.em, restLine = 1.em)))
            rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        Action(Icons.Rounded.Code, R.string.fmt_code) { rich.toggleCodeSpan() }
        Action(Icons.Rounded.Link, R.string.fmt_link) { linkOpen = true }
    }
    if (linkOpen) LinkDialog(rich) { linkOpen = false }
}

@Composable
private fun LinkDialog(rich: RichTextState, close: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var text by remember { mutableStateOf("") }
    val hasSelection = !rich.selection.collapsed
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.fmt_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.fmt_link_url)) }, singleLine = true)
                if (!hasSelection) OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.fmt_link_text)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (hasSelection) rich.addLinkToSelection(url) else rich.addLink(text.ifBlank { url }, url)
                close()
            }) { Text(stringResource(R.string.fmt_link_add)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.fmt_cancel)) } },
    )
}

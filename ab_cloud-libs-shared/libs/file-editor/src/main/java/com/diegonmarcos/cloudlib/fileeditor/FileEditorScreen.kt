package com.diegonmarcos.cloudlib.fileeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.diegonmarcos.superapp.fleetdata.FleetData
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one entry point libs:file-editor exports: a full text editor over one
 * local file — undo/redo, find/replace (plain or regex, case toggle), line
 * numbers, word wrap, syntax colouring from file-editor-languages.json, line
 * ending and encoding preserved from disk and switchable, atomic save, and an
 * unsaved-changes guard on the way out. Hosted by the app that links this
 * module (cloud-drive, push 5 of #567); this library never names that app.
 *
 * @param target the absolute path of the file to edit (created on first save
 *   if it does not exist yet); null renders an empty scratch buffer.
 * @param onOpenFile the host's file hand-off, used to "reveal" the saved file.
 * @param onClose the host's way out of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    target: String?,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val file = remember(target) { target?.takeIf { it.isNotBlank() }?.let { File(it) } }
    val highlighter by produceState(SyntaxHighlighter(emptyList())) { value = withContext(Dispatchers.IO) { runCatching { FleetData.get(context.applicationContext, SyntaxHighlighter.DATASET) { SyntaxHighlighter.fromJson(it) } }.getOrNull() } ?: value }
    val language = remember(file) { file?.let { highlighter.languageFor(it.name) } }

    var document by remember { mutableStateOf<EditorDocument?>(null) }
    val buffer = remember { EditorBuffer() }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var savedText by remember { mutableStateOf("") }
    var lineEnding by remember { mutableStateOf(LineEnding.LF) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var showNumbers by remember { mutableStateOf(true) }
    var fontSize by remember { mutableIntStateOf(14) }
    var showFind by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val dirty = value.text != savedText

    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { if (file != null && file.isFile) EditorDocument.read(file) else EditorDocument("", LineEnding.LF, Charsets.UTF_8, false) }
        }
        loaded.onSuccess { doc ->
            document = doc; lineEnding = doc.lineEnding; savedText = doc.text
            buffer.edit(doc.text); value = TextFieldValue(doc.text)
        }.onFailure { loadError = it.message ?: it.toString() }
    }

    fun setText(newText: String, selection: TextRange = value.selection) {
        buffer.edit(newText)
        value = TextFieldValue(newText, TextRange(selection.start.coerceAtMost(newText.length), selection.end.coerceAtMost(newText.length)))
    }

    fun save() {
        val f = file ?: run { scope.launch { snackbar.showSnackbar("No file to save to") }; return }
        val doc = document ?: return
        val text = value.text
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { f.parentFile?.mkdirs(); doc.write(f, text, lineEnding) } }
            r.onSuccess { savedText = text; snackbar.showSnackbar("Saved ${f.name} (${text.length} chars, ${lineEnding.label})") }
                .onFailure { snackbar.showSnackbar("Save failed: ${it.message}") }
        }
    }

    // Recompute colouring per text change; a file past the cap is shown plain
    // rather than re-tokenised on every keystroke.
    val tokens = remember(value.text, language) {
        if (language == null || value.text.length > HIGHLIGHT_CAP) emptyList() else highlighter.tokenize(value.text, language)
    }
    val transformation = remember(tokens) { HighlightTransformation(tokens) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((file?.name ?: stringResource(R.string.cloudlib_fileeditor_title)) + if (dirty) " •" else "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmClose = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(enabled = buffer.canUndo && !readOnly, onClick = { if (buffer.undo()) value = TextFieldValue(buffer.text, TextRange(buffer.text.length.coerceAtMost(value.selection.start))) }) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                    IconButton(enabled = buffer.canRedo && !readOnly, onClick = { if (buffer.redo()) value = TextFieldValue(buffer.text, TextRange(buffer.text.length.coerceAtMost(value.selection.start))) }) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                    IconButton(onClick = { showFind = !showFind }) { Icon(Icons.Default.Search, contentDescription = "Find") }
                    IconButton(enabled = dirty && !readOnly, onClick = { save() }) { Icon(Icons.Default.Save, contentDescription = "Save") }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(if (wrap) "Disable word wrap" else "Enable word wrap") }, onClick = { wrap = !wrap; showMenu = false })
                        DropdownMenuItem(text = { Text(if (showNumbers) "Hide line numbers" else "Show line numbers") }, onClick = { showNumbers = !showNumbers; showMenu = false })
                        DropdownMenuItem(text = { Text(if (readOnly) "Allow editing" else "Read only") }, onClick = { readOnly = !readOnly; showMenu = false })
                        DropdownMenuItem(text = { Text("Go to line…") }, onClick = { showGoto = true; showMenu = false })
                        DropdownMenuItem(text = { Text("Larger text") }, onClick = { fontSize = (fontSize + 2).coerceAtMost(28) })
                        DropdownMenuItem(text = { Text("Smaller text") }, onClick = { fontSize = (fontSize - 2).coerceAtLeast(9) })
                        LineEnding.values().forEach { le ->
                            DropdownMenuItem(text = { Text((if (le == lineEnding) "● " else "   ") + "Line endings: ${le.label}") }, onClick = { lineEnding = le; showMenu = false })
                        }
                        if (file != null) DropdownMenuItem(text = { Text("Reveal in files") }, onClick = { showMenu = false; onOpenFile(file.absolutePath) })
                    }
                },
            )
        },
        bottomBar = {
            val s = value.selection.start
            Text(
                "Ln ${buffer.lineOf(s)}, Col ${buffer.columnOf(s)}  ·  ${buffer.lineCount} lines, ${value.text.length} chars, ${buffer.wordCount} words" +
                    "  ·  ${document?.charset?.name() ?: "UTF-8"}${if (document?.hadBom == true) " BOM" else ""} ${lineEnding.label}" +
                    (language?.let { "  ·  ${it.id}" } ?: "") + (if (readOnly) "  ·  read-only" else ""),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            loadError?.let { Text("Cannot open: $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            if (showFind) FindBar(buffer, value, readOnly, onSelect = { range -> value = value.copy(selection = TextRange(range.first, range.last + 1)) },
                onReplaced = { setText(buffer.text) }, onClose = { showFind = false })
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                if (showNumbers) {
                    val digits = buffer.lineCount.toString().length
                    Text(
                        (1..buffer.lineCount).joinToString("\n") { it.toString().padStart(digits) },
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 8.dp),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                val editorModifier = if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(hScroll)
                BasicTextField(
                    value = value,
                    onValueChange = { v -> if (!readOnly) { if (v.text != value.text) buffer.edit(v.text); value = v } },
                    modifier = editorModifier.padding(8.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    readOnly = readOnly,
                    visualTransformation = transformation,
                )
            }
        }
    }

    if (showGoto) {
        var line by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoto = false },
            title = { Text("Go to line (1–${buffer.lineCount})") },
            text = { OutlinedTextField(line, { line = it.filter { c -> c.isDigit() } }, singleLine = true) },
            confirmButton = { TextButton(onClick = { line.toIntOrNull()?.let { n -> val o = buffer.offsetOfLine(n); value = value.copy(selection = TextRange(o)) }; showGoto = false }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel") } },
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Unsaved changes") },
            text = { Text("${file?.name ?: "This buffer"} has changes that are not saved.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; save(); onClose() }) { Text("Save and close") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false; onClose() }) { Text("Discard") }
                    TextButton(onClick = { confirmClose = false }) { Text("Keep editing") }
                }
            },
        )
    }
}

/** Above this many characters colouring is skipped: re-tokenising per keystroke would lag the keyboard. */
internal const val HIGHLIGHT_CAP = 200_000

@Composable
private fun FindBar(buffer: EditorBuffer, value: TextFieldValue, readOnly: Boolean, onSelect: (IntRange) -> Unit, onReplaced: () -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    val matches = remember(query, caseSensitive, regex, value.text) { buffer.find(query, caseSensitive, regex) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) {
        Row {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Find (${matches.size})") }, singleLine = true)
            TextButton(onClick = { caseSensitive = !caseSensitive }) { Text(if (caseSensitive) "Aa ●" else "Aa") }
            TextButton(onClick = { regex = !regex }) { Text(if (regex) ".* ●" else ".*") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close find") }
        }
        Row {
            OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), label = { Text("Replace") }, singleLine = true, enabled = !readOnly)
            TextButton(enabled = matches.isNotEmpty(), onClick = { buffer.findPrevious(query, value.selection.start, caseSensitive, regex)?.let(onSelect) }) { Text("Prev") }
            TextButton(enabled = matches.isNotEmpty(), onClick = { buffer.findNext(query, value.selection.end, caseSensitive, regex)?.let(onSelect) }) { Text("Next") }
            TextButton(enabled = !readOnly && matches.isNotEmpty(), onClick = {
                val sel = value.selection
                val hit = matches.firstOrNull { it.first == sel.start && it.last + 1 == sel.end } ?: buffer.findNext(query, sel.start, caseSensitive, regex)
                if (hit != null) { buffer.replace(hit, replacement); onReplaced() }
            }) { Text("Replace") }
            TextButton(enabled = !readOnly && matches.isNotEmpty(), onClick = { buffer.replaceAll(query, replacement, caseSensitive, regex); onReplaced() }) { Text("All") }
        }
    }
}

/** Colours tokens without changing offsets, so selection and cursor stay exact. */
private class HighlightTransformation(private val tokens: List<Token>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (tokens.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        for (t in tokens) {
            if (t.start >= text.length) continue
            val end = t.end.coerceAtMost(text.length)
            val style = when (t.kind) {
                TokenKind.COMMENT -> SpanStyle(color = Color(0xFF8A8A8A))
                TokenKind.STRING -> SpanStyle(color = Color(0xFF2E7D32))
                TokenKind.NUMBER -> SpanStyle(color = Color(0xFFEF6C00))
                TokenKind.KEYWORD -> SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
                TokenKind.HEADING -> SpanStyle(color = Color(0xFF6A1B9A), fontWeight = FontWeight.Bold)
            }
            builder.addStyle(style, t.start, end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

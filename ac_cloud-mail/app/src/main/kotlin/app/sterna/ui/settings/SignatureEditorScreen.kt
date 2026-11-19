package app.sterna.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sterna.R
import app.sterna.ui.components.Icon
import app.sterna.ui.mailNav
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.LocalSternaDarkTheme
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import app.sterna.core.data.account.SignatureTransfer
import app.sterna.core.data.account.StoredSignature
import app.sterna.core.data.text.looksLikeHtml
import app.sterna.ui.theme.MailMetrics
import kotlinx.coroutines.delay

/** How long the preview waits after the last edit before it re-renders. */
internal const val PREVIEW_DEBOUNCE_MS = 150L

/** The template Reset puts back: a name line, a role line and a link, all inline-styled. */
internal const val SIGNATURE_TEMPLATE =
    "<div style=\"font-family:sans-serif;font-size:14px;color:#202020\">\n" +
        "  <b>Your Name</b><br>\n" +
        "  Your role, Your company<br>\n" +
        "  <a href=\"https://example.com\" style=\"color:#1565c0\">example.com</a>\n" +
        "</div>"

private const val PANE_SOURCE = 0
private const val PANE_PREVIEW = 1
private const val PANE_SPLIT = 2

private enum class Prompt { NONE, LINK, IMAGE, COLOUR, TABLE, SOCIAL }

private val COLOUR_SWATCHES = listOf("#000000", "#cc0000", "#e67e22", "#2e7d32", "#1565c0", "#6a1b9a", "#757575")
private val FONT_SIZES = listOf(10, 12, 13, 14, 16, 18, 20, 24)

/**
 * The signature editor as a full-screen dialog: owns the unsaved-changes guard, so back, the close
 * button and a tap outside all ask before throwing the owner's edits away.
 */
@Composable
internal fun SignatureEditorDialog(
    signature: StoredSignature,
    isDefault: Boolean,
    delimiter: Boolean,
    onSave: (name: String, source: String, makeDefault: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberSaveable(
        signature.id,
        saver = SignatureEditorState.saver(signature.source(), signature.name, isDefault),
    ) { SignatureEditorState(signature.source(), signature.name, isDefault) }
    var guard by rememberSaveable(signature.id) { mutableStateOf(false) }
    val requestClose: () -> Unit = { if (state.dirty) guard = true else onClose() }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        SignatureEditorScreen(
            state = state,
            delimiter = delimiter,
            onSave = { name, source, makeDefault ->
                onSave(name, source, makeDefault)
                onClose()
            },
            onRequestClose = requestClose,
        )
    }
    if (guard) {
        AlertDialog(
            onDismissRequest = { guard = false },
            title = { Text(stringResource(R.string.sigedit_unsaved_title)) },
            text = { Text(stringResource(R.string.sigedit_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = { guard = false; onClose() }) {
                    Text(stringResource(R.string.sigedit_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { guard = false }) { Text(stringResource(R.string.sigedit_keep_editing)) }
            },
        )
    }
}

/**
 * The editor itself: toolbar, source pane and live preview (side by side when wide, Source / Preview /
 * Split tabs on a phone), the validation panel and the file actions. The source in [state] is the one
 * model; see [SignatureEditorState].
 *
 * [preview] renders the document the preview pane is given; it defaults to the WebView and is a
 * parameter so a test can read the document instead of a WebView's pixels.
 */
@Composable
internal fun SignatureEditorScreen(
    state: SignatureEditorState,
    delimiter: Boolean,
    onSave: (name: String, source: String, makeDefault: Boolean) -> Unit,
    onRequestClose: () -> Unit,
    preview: @Composable (document: String, visual: Boolean, handle: VisualHandle, onVisualHtml: (String) -> Unit, modifier: Modifier) -> Unit =
        { document, visual, handle, onVisualHtml, modifier -> SignatureWebPreview(document, visual, handle, onVisualHtml, modifier) },
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val handle = remember { VisualHandle() }

    var pane by rememberSaveable { mutableStateOf(PANE_SPLIT) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var mobile by rememberSaveable { mutableStateOf(false) }
    var inReply by rememberSaveable { mutableStateOf(false) }
    var visual by rememberSaveable { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf(Prompt.NONE) }
    var menu by remember { mutableStateOf(false) }
    var problemsOpen by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    val source = state.source
    val issues = remember(source) { SignatureValidator.validate(source) }
    val markers = remember(issues) { SignatureValidator.markers(issues) }
    val matches = remember(source, query, findOpen) { if (findOpen) SignatureEditing.find(source, query) else emptyList() }
    val canVisual = remember(source) { source.isBlank() || looksLikeHtml(source) }
    val isVisual = visual && canVisual

    // -- the live preview: the SAME document builder, debounced --------------------------------------
    val options = SignaturePreview.Options(dark, mobile, inReply, isVisual, delimiter)
    var shown by remember { mutableStateOf(SignaturePreview.document(source, options)) }
    val lastOptions = remember { mutableStateOf(options) }
    val echo = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source, options) {
        // An edit the visual pane itself reported is already on screen; reloading would drop its caret.
        if (isVisual && source == echo.value && options == lastOptions.value) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        shown = SignaturePreview.document(source, options)
        lastOptions.value = options
    }

    // -- file actions -----------------------------------------------------------------------------
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = readText(context, uri)
            if (text == null) notice = R.string.sigedit_import_failed else state.replaceAll(importedSource(text))
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) notice = if (writeText(context, uri, state.source)) R.string.sigedit_exported else R.string.sigedit_import_failed
    }

    fun focusSource() {
        runCatching { focus.requestFocus() }
    }

    fun save() {
        if (!SignatureValidator.blocksSave(issues)) onSave(state.name, state.source, state.makeDefault)
    }

    fun styleOrWrap(command: String, wrap: (String, Int, Int) -> SignatureHtmlTools.Edit) {
        if (isVisual) handle.exec(command) else state.commit(wrap(state.source, state.textField.selection.start, state.textField.selection.end))
    }

    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("sig-editor"),
        color = scheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar -----------------------------------------------------------------------------
            Row(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s4), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRequestClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sigedit_close))
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state.name = it },
                    label = { Text(stringResource(R.string.settings_signature_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(vertical = MailMetrics.s4).testTag("sig-name"),
                )
                TextButton(
                    onClick = ::save,
                    enabled = state.dirty && !SignatureValidator.blocksSave(issues),
                    modifier = Modifier.testTag("sig-save"),
                ) { Text(stringResource(R.string.sigedit_save)) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sigedit_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(R.string.sigedit_reload) { menu = false; state.replaceAll(state.baselineSource) }
                        MenuItem(R.string.sigedit_import_file) { menu = false; importLauncher.launch(arrayOf("text/html", "text/plain", "application/json", "*/*")) }
                        MenuItem(R.string.sigedit_import_paste) {
                            menu = false
                            val pasted = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.signatureSource()
                            if (pasted.isNullOrBlank()) notice = R.string.sigedit_import_failed else state.replaceAll(importedSource(pasted))
                        }
                        MenuItem(R.string.sigedit_export_file) { menu = false; exportLauncher.launch("signature.html") }
                        MenuItem(R.string.sigedit_copy) {
                            menu = false
                            clipboard.setText(AnnotatedString(state.source))
                            notice = R.string.sigedit_copied
                        }
                        MenuItem(R.string.sigedit_reset) { menu = false; state.replaceAll(SIGNATURE_TEMPLATE) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = MailMetrics.s16), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.makeDefault, onCheckedChange = { state.makeDefault = it })
                Text(stringResource(R.string.settings_signature_default), style = MaterialTheme.typography.bodyMedium)
                notice?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier.padding(start = MailMetrics.s16),
                    )
                    LaunchedEffect(it) { delay(2500); notice = null }
                }
            }

            // ---- toolbar -----------------------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MailMetrics.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.AutoMirrored.Filled.Undo, R.string.settings_signature_undo, state.canUndo) { state.undo() }
                ToolButton(Icons.AutoMirrored.Filled.Redo, R.string.settings_signature_redo, state.canRedo) { state.redo() }
                ToolButton(Icons.Filled.Search, R.string.sigedit_find) { findOpen = !findOpen }
                ToolButton(Icons.Filled.FormatBold, R.string.settings_signature_bold) { styleOrWrap("bold", SignatureHtmlTools::bold) }
                ToolButton(Icons.Filled.FormatItalic, R.string.settings_signature_italic) { styleOrWrap("italic", SignatureHtmlTools::italic) }
                ToolButton(Icons.Filled.FormatUnderlined, R.string.settings_signature_underline) { styleOrWrap("underline", SignatureHtmlTools::underline) }
                ToolButton(Icons.Filled.StrikethroughS, R.string.sigedit_strike) { styleOrWrap("strikeThrough", SignatureEditing::strike) }
                ToolButton(Icons.Filled.Link, R.string.settings_signature_link) { prompt = Prompt.LINK }
                ToolButton(Icons.Filled.Image, R.string.settings_signature_image) { prompt = Prompt.IMAGE }
                ToolButton(Icons.Filled.TableChart, R.string.sigedit_table) { prompt = Prompt.TABLE }
                ToolButton(Icons.Filled.HorizontalRule, R.string.sigedit_divider) {
                    state.commit(SignatureEditing.divider(state.source, state.textField.selection.start, state.textField.selection.end))
                }
                ToolButton(Icons.Filled.Share, R.string.sigedit_social) { prompt = Prompt.SOCIAL }
                ToolButton(Icons.Filled.FormatColorText, R.string.settings_signature_colour) { prompt = Prompt.COLOUR }
                var sizeMenu by remember { mutableStateOf(false) }
                Box {
                    ToolButton(Icons.Filled.FormatSize, R.string.sigedit_size) { sizeMenu = true }
                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                        FONT_SIZES.forEach { px ->
                            DropdownMenuItem(text = { Text("$px px") }, onClick = {
                                sizeMenu = false
                                state.commit(SignatureEditing.fontSize(state.source, state.textField.selection.start, state.textField.selection.end, px))
                            })
                        }
                    }
                }
                var alignMenu by remember { mutableStateOf(false) }
                Box {
                    ToolButton(Icons.Filled.FormatAlignLeft, R.string.sigedit_align) { alignMenu = true }
                    DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
                        listOf(
                            Triple(SignatureEditing.Align.LEFT, R.string.sigedit_align_left, "justifyLeft"),
                            Triple(SignatureEditing.Align.CENTER, R.string.sigedit_align_center, "justifyCenter"),
                            Triple(SignatureEditing.Align.RIGHT, R.string.sigedit_align_right, "justifyRight"),
                        ).forEach { (a, label, command) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                alignMenu = false
                                if (isVisual) handle.exec(command)
                                else state.commit(SignatureEditing.align(state.source, state.textField.selection.start, state.textField.selection.end, a))
                            })
                        }
                    }
                }
            }
            if (findOpen) {
                FindBar(
                    query = query,
                    onQuery = { query = it },
                    replacement = replacement,
                    onReplacement = { replacement = it },
                    count = matches.size,
                    onStep = { forward ->
                        SignatureEditing.step(matches, state.textField.selection.max, forward)?.let {
                            state.select(it, it + query.length)
                            focusSource()
                        }
                    },
                    onReplaceAll = { state.replaceAll(SignatureEditing.replaceAll(state.source, query, replacement)) },
                )
            }
            HorizontalDivider()

            // ---- panes -------------------------------------------------------------------------------
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= MailMetrics.s256 * 3
                val showSource = wide || pane != PANE_PREVIEW
                val showPreview = wide || pane != PANE_SOURCE
                Column(Modifier.fillMaxSize()) {
                    if (!wide) {
                        val pages = listOf(
                            NavPage(PANE_SOURCE.toString(), stringResource(R.string.sigedit_source), "code"),
                            NavPage(PANE_PREVIEW.toString(), stringResource(R.string.sigedit_preview), "visibility"),
                            NavPage(PANE_SPLIT.toString(), stringResource(R.string.sigedit_split), "vertical_split"),
                        )
                        NavBarTopSecondRow(
                            pages = pages,
                            selectedId = pane.toString(),
                            onSelect = { pane = it.id.toInt() },
                            modifier = Modifier.padding(horizontal = MailMetrics.s12),
                            underTopChrome = false,
                            surface = mailNav.stripSurface("mail", LocalSternaDarkTheme.current),
                        )
                    }
                    val sourcePane: @Composable (Modifier) -> Unit = { m ->
                        SignatureCodeEditor(state, markers, matches, query.length, onFind = { findOpen = true }, focus = focus, modifier = m)
                    }
                    val previewPane: @Composable (Modifier) -> Unit = { m ->
                        Column(m) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MailMetrics.s8),
                                horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                            ) {
                                OptionChip(R.string.sigedit_dark, dark, "sig-opt-dark") { dark = it }
                                OptionChip(R.string.sigedit_mobile, mobile, "sig-opt-mobile") { mobile = it }
                                OptionChip(R.string.sigedit_reply, inReply, "sig-opt-reply") { inReply = it }
                                OptionChip(R.string.sigedit_visual, isVisual, "sig-opt-visual", enabled = canVisual) { visual = it }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                preview(
                                    shown,
                                    isVisual,
                                    handle,
                                    { html ->
                                        val edited = SignaturePreview.fromVisual(html)
                                        echo.value = edited
                                        state.fromVisual(edited)
                                    },
                                    Modifier.fillMaxSize(),
                                )
                            }
                            if (canVisual && source.isNotBlank()) {
                                val plain = remember(source) { SignatureHtmlTools.plainText(state.source) }
                                Text(
                                    stringResource(R.string.settings_signature_plain_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = MailMetrics.s8, vertical = MailMetrics.s2),
                                )
                                Text(
                                    plain,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = MailMetrics.s8).padding(bottom = MailMetrics.s4),
                                )
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            sourcePane(Modifier.weight(1f).fillMaxSize())
                            VerticalDivider()
                            previewPane(Modifier.weight(1f).fillMaxSize())
                        }
                    } else {
                        if (showSource) sourcePane(Modifier.weight(1f).fillMaxWidth())
                        if (showSource && showPreview) HorizontalDivider()
                        if (showPreview) previewPane(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }

            // ---- validation panel -----------------------------------------------------------------------
            HorizontalDivider()
            ValidationPanel(
                issues = issues,
                open = problemsOpen,
                onToggle = { problemsOpen = !problemsOpen },
                onPick = { issue ->
                    state.select(issue.start, issue.end)
                    if (pane == PANE_PREVIEW) pane = PANE_SPLIT
                    focusSource()
                },
            )
        }
    }

    // ---- insert prompts ---------------------------------------------------------------------------------
    when (prompt) {
        Prompt.NONE -> Unit
        Prompt.LINK, Prompt.IMAGE, Prompt.COLOUR -> TextPrompt(
            title = when (prompt) {
                Prompt.LINK -> R.string.compose_link_title
                Prompt.IMAGE -> R.string.settings_signature_image_title
                else -> R.string.settings_signature_colour_title
            },
            label = when (prompt) {
                Prompt.LINK -> R.string.compose_link_url
                Prompt.IMAGE -> R.string.settings_signature_image_url_label
                else -> R.string.settings_signature_colour_hex_label
            },
            swatches = prompt == Prompt.COLOUR,
            onDismiss = { prompt = Prompt.NONE },
            onApply = { input ->
                val at = state.textField.selection
                val ok = when (prompt) {
                    Prompt.LINK ->
                        if (isVisual) SignatureHtmlTools.safeLinkUrl(input)?.also { handle.exec("createLink", it) } != null
                        else state.commit(SignatureHtmlTools.link(state.source, at.start, at.end, input))
                    Prompt.IMAGE -> state.commit(SignatureHtmlTools.image(state.source, at.start, at.end, input))
                    else ->
                        if (isVisual) input.trim().takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.also { handle.exec("foreColor", it) } != null
                        else state.commit(SignatureHtmlTools.colour(state.source, at.start, at.end, input))
                }
                if (ok) prompt = Prompt.NONE
                ok
            },
        )
        Prompt.TABLE -> TablePrompt(
            onDismiss = { prompt = Prompt.NONE },
            onApply = { rows, cols ->
                val at = state.textField.selection
                val ok = state.commit(SignatureEditing.table(state.source, at.start, at.end, rows, cols))
                if (ok) prompt = Prompt.NONE
                ok
            },
        )
        Prompt.SOCIAL -> SocialPrompt(
            onDismiss = { prompt = Prompt.NONE },
            onApply = { entries ->
                val at = state.textField.selection
                val ok = state.commit(SignatureEditing.social(state.source, at.start, at.end, entries))
                if (ok) prompt = Prompt.NONE
                ok
            },
        )
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    val description = stringResource(label)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun OptionChip(label: Int, selected: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        enabled = enabled,
        label = { Text(stringResource(label)) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    replacement: String,
    onReplacement: (String) -> Unit,
    count: Int,
    onStep: (Boolean) -> Unit,
    onReplaceAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.sigedit_find)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("sig-find"),
            )
            Text(
                if (query.isEmpty()) "" else if (count == 0) stringResource(R.string.sigedit_find_none) else stringResource(R.string.sigedit_find_count, count),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = MailMetrics.s8),
            )
            IconButton(onClick = { onStep(false) }, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.sigedit_find_prev))
            }
            IconButton(onClick = { onStep(true) }, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.sigedit_find_next))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacement,
                label = { Text(stringResource(R.string.sigedit_replace_with)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReplaceAll, enabled = count > 0) { Text(stringResource(R.string.sigedit_replace_all)) }
        }
    }
}

@Composable
private fun ValidationPanel(
    issues: List<SignatureValidator.Issue>,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (SignatureValidator.Issue) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val errors = issues.count { it.severity == SignatureValidator.Severity.ERROR }
    val warnings = issues.count { it.severity == SignatureValidator.Severity.WARNING }
    val notes = issues.count { it.severity == SignatureValidator.Severity.INFO }
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().testTag("sig-validation-summary").clickable(onClick = onToggle).padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (issues.isEmpty()) stringResource(R.string.sigedit_validation_ok)
                else stringResource(R.string.sigedit_validation_summary, errors, warnings, notes),
                style = MaterialTheme.typography.labelLarge,
                color = if (errors > 0) scheme.error else scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (issues.isNotEmpty()) {
                Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null)
            }
        }
        if (open && issues.isNotEmpty()) {
            Column(Modifier.heightIn(max = MailMetrics.s256).verticalScroll(rememberScrollState())) {
                issues.forEach { issue ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(issue) }.padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s4),
                        verticalAlignment = Alignment.Top,
                    ) {
                        val (icon, tint) = when (issue.severity) {
                            SignatureValidator.Severity.ERROR -> Icons.Filled.Error to scheme.error
                            SignatureValidator.Severity.WARNING -> Icons.Filled.Warning to scheme.tertiary
                            SignatureValidator.Severity.INFO -> Icons.Filled.Info to scheme.secondary
                        }
                        Icon(icon, contentDescription = null, tint = tint)
                        Text(
                            "${issue.line}: " + issueMessage(issue),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = MailMetrics.s8),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun issueMessage(i: SignatureValidator.Issue): String {
    val parts = i.detail.split('|')
    return when (i.code) {
        SignatureValidator.Code.UNCLOSED_TAG -> stringResource(R.string.sigedit_issue_unclosed, i.detail)
        SignatureValidator.Code.MISMATCHED_CLOSE -> stringResource(R.string.sigedit_issue_mismatched, parts[0], parts.getOrElse(1) { "" })
        SignatureValidator.Code.STRAY_CLOSE -> stringResource(R.string.sigedit_issue_stray, i.detail)
        SignatureValidator.Code.UNTERMINATED_TAG -> stringResource(R.string.sigedit_issue_unterminated_tag)
        SignatureValidator.Code.UNTERMINATED_COMMENT -> stringResource(R.string.sigedit_issue_unterminated_comment)
        SignatureValidator.Code.EXTERNAL_IMAGE -> stringResource(R.string.sigedit_issue_external_image, i.detail)
        SignatureValidator.Code.DATA_IMAGE -> stringResource(R.string.sigedit_issue_data_image)
        SignatureValidator.Code.MISSING_ALT -> stringResource(R.string.sigedit_issue_missing_alt)
        SignatureValidator.Code.STYLE_BLOCK -> stringResource(R.string.sigedit_issue_style_block)
        SignatureValidator.Code.CLASS_ATTRIBUTE -> stringResource(R.string.sigedit_issue_class)
        SignatureValidator.Code.STRIPPED_ELEMENT -> stringResource(R.string.sigedit_issue_stripped_element, i.detail)
        SignatureValidator.Code.STRIPPED_ATTRIBUTE -> stringResource(R.string.sigedit_issue_stripped_attribute, i.detail)
        SignatureValidator.Code.SIZE_WARNING -> stringResource(R.string.sigedit_issue_size_warn, i.detail.toIntOrNull() ?: 0)
        SignatureValidator.Code.SIZE_LIMIT -> stringResource(R.string.sigedit_issue_size_limit, i.detail.toIntOrNull() ?: 0)
    }
}

/** One input, optional colour swatches; [onApply] returns false to keep the dialog open with the error shown. */
@Composable
private fun TextPrompt(title: Int, label: Int, swatches: Boolean, onDismiss: () -> Unit, onApply: (String) -> Boolean) {
    var input by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; rejected = false },
                    label = { Text(stringResource(label)) },
                    singleLine = true,
                    isError = rejected,
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.compose_link_bad_url)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("sig-prompt-input"),
                )
                if (swatches) {
                    Row(Modifier.fillMaxWidth().padding(top = MailMetrics.s8)) {
                        COLOUR_SWATCHES.forEach { hex ->
                            Box(
                                Modifier
                                    .padding(end = MailMetrics.s8)
                                    .size(MailMetrics.s40)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                    .clickable { input = hex; rejected = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!onApply(input)) rejected = true }) { Text(stringResource(R.string.compose_link_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_link_cancel)) } },
    )
}

@Composable
private fun TablePrompt(onDismiss: () -> Unit, onApply: (Int, Int) -> Boolean) {
    var rows by rememberSaveable { mutableStateOf("2") }
    var cols by rememberSaveable { mutableStateOf("2") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sigedit_table)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                OutlinedTextField(
                    value = rows, onValueChange = { rows = it.take(2); rejected = false },
                    label = { Text(stringResource(R.string.sigedit_table_rows)) }, singleLine = true, isError = rejected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cols, onValueChange = { cols = it.take(2); rejected = false },
                    label = { Text(stringResource(R.string.sigedit_table_cols)) }, singleLine = true, isError = rejected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = rows.toIntOrNull()?.let { r -> cols.toIntOrNull()?.let { c -> onApply(r, c) } } ?: false
                if (!ok) rejected = true
            }) { Text(stringResource(R.string.compose_link_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_link_cancel)) } },
    )
}

@Composable
private fun SocialPrompt(onDismiss: () -> Unit, onApply: (List<SignatureEditing.Social>) -> Boolean) {
    val urls = remember { mutableStateOf(List(SignatureEditing.SOCIAL_NETWORKS.size) { "" }) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sigedit_social)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SignatureEditing.SOCIAL_NETWORKS.forEachIndexed { index, network ->
                    OutlinedTextField(
                        value = urls.value[index],
                        onValueChange = { v -> urls.value = urls.value.toMutableList().also { it[index] = v }; rejected = false },
                        label = { Text(network) },
                        singleLine = true,
                        isError = rejected,
                        modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entries = SignatureEditing.SOCIAL_NETWORKS.mapIndexedNotNull { i, n ->
                    urls.value[i].takeIf { it.isNotBlank() }?.let { SignatureEditing.Social(n, it) }
                }
                if (!onApply(entries)) rejected = true
            }) { Text(stringResource(R.string.compose_link_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_link_cancel)) } },
    )
}

// -- files -----------------------------------------------------------------------------------------

/** A file or pasted text as editor source: a signature export is unwrapped to its default signature, a page to its body. */
internal fun importedSource(text: String): String {
    SignatureTransfer.parseDocument(text)?.values?.firstOrNull()?.let { entry ->
        val pick = entry.signatures.firstOrNull { it.id == entry.default } ?: entry.signatures.firstOrNull()
        if (pick != null) return pick.source()
    }
    return SignatureEditing.importFragment(text)
}

private const val MAX_IMPORT_BYTES = 256 * 1024

private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size > MAX_IMPORT_BYTES) null else bytes.toString(Charsets.UTF_8)
    }
}.getOrNull()

private fun writeText(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
}.getOrDefault(false)

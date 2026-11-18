package app.sterna.ui.compose

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.InsertPhoto
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.viewinterop.AndroidView
import app.sterna.R
import app.sterna.core.data.text.TEXT_COLORS
import app.sterna.core.data.text.TEXT_COLOR_OF
import app.sterna.core.data.text.normalizeLinkUrl
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.MailMetrics
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/*
 * THE COMPOSER'S BODY EDITOR: a WebView whose page is one `contenteditable` element, so the body IS
 * HTML - there is no plain string that gets wrapped later. The page lives in assets/html-editor.html
 * (CSP: no network, no remote images, own script only); this file is the app's side of it: the
 * controller the screen drives, the bridge the page calls back through, the formatting toolbar, and
 * the "Source" view that edits the raw markup.
 *
 * The pure parts - the document template, the state string the page reports, a JS result decoded -
 * are top-level functions so a JVM test can execute them.
 */

/** What the page reports about the selection, for the toolbar to light its buttons. */
internal data class EditorFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val bullets: Boolean = false,
    val numbers: Boolean = false,
    /** `l`, `c` or `r`. */
    val align: Char = 'l',
    /** The block tag around the caret: `div`, `p`, `h1`..`h6`, `blockquote`, `li`... */
    val block: String = "",
    /** The computed colour of the selection (`rgb(r, g, b)`), or empty. */
    val color: String = "",
    /** The address of the link the caret is in, or null. */
    val link: String? = null,
    val hasRange: Boolean = false,
    val inQuote: Boolean = false,
)

/** Decodes the `k=v&k=v` state string the page's `report()` sends (values percent-encoded). */
internal fun parseEditorState(encoded: String): EditorFormat {
    val map = encoded.split('&').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) null else pair.substring(0, at) to decodeComponent(pair.substring(at + 1))
    }.toMap()
    fun flag(key: String) = map[key] == "1"
    val link = map["link"]
    return EditorFormat(
        bold = flag("b"), italic = flag("i"), underline = flag("u"), strike = flag("s"),
        bullets = flag("ul"), numbers = flag("ol"),
        align = map["al"]?.firstOrNull() ?: 'l',
        block = map["blk"].orEmpty(), color = map["color"].orEmpty(),
        link = if (link == null || link == "-") null else link,
        hasRange = flag("range"), inQuote = flag("quote"),
    )
}

private fun decodeComponent(s: String): String =
    runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

/**
 * The string `evaluateJavascript` hands back for a JS string result: a JSON string literal (quotes,
 * `\"`, `\\`, `\n`, `<`...). Anything that is not one - `null` for undefined - is the empty string.
 */
internal fun unquoteJsResult(raw: String?): String {
    if (raw == null || raw.length < 2 || raw[0] != '"' || raw.last() != '"') return ""
    val out = StringBuilder(raw.length)
    var i = 1
    val end = raw.length - 1
    while (i < end) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= end) {
            out.append(c)
            i++
            continue
        }
        when (val n = raw[i + 1]) {
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            'f' -> { out.append('\u000C'); i += 2 }
            '/' -> { out.append('/'); i += 2 }
            '"' -> { out.append('"'); i += 2 }
            '\\' -> { out.append('\\'); i += 2 }
            'u' -> {
                val code = if (i + 6 <= end) raw.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (code != null) { out.append(code.toChar()); i += 6 } else { out.append(n); i += 2 }
            }
            else -> { out.append(n); i += 2 }
        }
    }
    return out.toString()
}

/** [s] as a JavaScript string literal. `<` is escaped so no markup can end the call early. */
internal fun jsString(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '<' -> append("\\u003c")
            c == ' ' -> append("\\u2028")
            c == ' ' -> append("\\u2029")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** The page with its theme slots filled. Values are colours and a number, plus the one escaped text. */
internal fun editorDocument(
    template: String,
    foreground: String,
    hint: String,
    accent: String,
    fontPx: Int,
    placeholder: String,
): String = template
    .replace("%%FG%%", foreground)
    .replace("%%HINT%%", hint)
    .replace("%%ACCENT%%", accent)
    .replace("%%FONT_PX%%", fontPx.toString())
    .replace("%%PLACEHOLDER%%", placeholder.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))

internal fun cssColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** What the text tools may rewrite, as the page reports it: the selection (`sel`) or the owner's lines (`all`). */
internal data class EditorScope(val mode: String, val text: String)

internal fun parseEditorScope(raw: String): EditorScope? = when {
    raw.startsWith("sel:") -> EditorScope("sel", raw.removePrefix("sel:"))
    raw.startsWith("all:") -> EditorScope("all", raw.removePrefix("all:"))
    else -> null
}

/**
 * The screen's handle on the editor. Every command is a call into the page; the page is the model.
 * [load] before the page is ready is held and delivered when it is.
 */
internal class HtmlEditorController {
    private var web: WebView? = null
    private var ready = false
    private var pending: String? = null
    private var wantFocus = false

    /** The last markup the page is known to hold, so a rebuilt page (the view was released) is refilled. */
    private var known = ""

    var format by mutableStateOf(EditorFormat())
        internal set

    /** Whether the page has the focus: the toolbar is drawn for it alone. */
    var focused by mutableStateOf(false)
        internal set

    /** Whether the raw-markup view is up. */
    var sourceMode by mutableStateOf(false)

    private var userChange: (String) -> Unit = {}
    private var loadedChange: (String) -> Unit = {}

    /** Wire the screen's two listeners: the owner's edits, and the normalised markup after a [load]. */
    internal fun listen(onUserChange: (String) -> Unit, onLoaded: (String) -> Unit) {
        userChange = onUserChange
        loadedChange = onLoaded
    }

    internal fun userChanged(html: String) {
        known = html
        userChange(html)
    }

    internal fun loaded(html: String) {
        known = html
        loadedChange(html)
    }

    internal fun attach(view: WebView) {
        web = view
        ready = false
        pending = pending ?: known.ifEmpty { null }
    }

    internal fun detach(view: WebView) {
        if (web === view) {
            web = null
            ready = false
        }
    }

    internal fun onReady() {
        ready = true
        pending?.let {
            pending = null
            run("Sterna.setHtml(${jsString(it)})")
        }
        if (wantFocus) {
            wantFocus = false
            focusNow()
        }
    }

    /** Replace the whole body with [html] (sanitised by the page). */
    fun load(html: String) {
        known = html
        if (ready) run("Sterna.setHtml(${jsString(html)})") else pending = html
    }

    private fun run(script: String, callback: ((String?) -> Unit)? = null) {
        val view = web ?: return
        view.evaluateJavascript(script, callback?.let { cb -> android.webkit.ValueCallback<String> { cb(it) } })
    }

    /** The page's markup right now, flushed - [fallback] when the page is gone or too slow. */
    suspend fun currentHtml(fallback: String): String {
        val view = web ?: return fallback
        if (!ready) return pending ?: fallback
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<String> { cont ->
                view.evaluateJavascript("Sterna.html()") { raw ->
                    if (cont.isActive) cont.resume(unquoteJsResult(raw))
                }
            }
        } ?: fallback
    }

    /** The text the tools may rewrite, or null when the page cannot say. */
    suspend fun scopeText(): EditorScope? {
        val view = web ?: return null
        if (!ready) return null
        val raw = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<String> { cont ->
                view.evaluateJavascript("Sterna.scopeText()") { r -> if (cont.isActive) cont.resume(unquoteJsResult(r)) }
            }
        } ?: return null
        return parseEditorScope(raw)
    }

    fun applyScope(mode: String, text: String) = run("Sterna.applyScope(${jsString(mode)},${jsString(text)})")

    /** Put the caret in the body and raise the keyboard - held until the page is up if it is not yet. */
    fun focusBody() {
        if (ready) focusNow() else wantFocus = true
    }

    private fun focusNow() {
        val view = web ?: return
        view.requestFocus()
        run("Sterna.focus()")
        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun exec(command: String, argument: String? = null) =
        run(if (argument == null) "Sterna.exec(${jsString(command)})" else "Sterna.exec(${jsString(command)},${jsString(argument)})")

    fun block(tag: String) = run("Sterna.block(${jsString(tag)})")

    fun quote() = run("Sterna.quote()")

    fun link(url: String, label: String) = run("Sterna.link(${jsString(url)},${jsString(label)})")

    fun image(dataUri: String, cid: String, alt: String) =
        run("Sterna.image(${jsString(dataUri)},${jsString(cid)},${jsString(alt)})")

    fun color(hex: String?) = if (hex == null) run("Sterna.clearColor()") else exec("foreColor", hex)

    fun insertText(text: String) = run("Sterna.insertText(${jsString(text)})")

    fun appendHtml(html: String) = run("Sterna.appendHtml(${jsString(html)})")

    /** Swap the managed block [kind] (the signature) for [html], unless the owner edited it. */
    fun replaceBlock(kind: String, html: String, belowQuote: Boolean) =
        run("Sterna.replaceBlock(${jsString(kind)},${jsString(html)},$belowQuote)")
}

private class EditorBridge(private val controller: HtmlEditorController) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun ready() { main.post { controller.onReady() } }

    @JavascriptInterface
    fun loaded(html: String) { main.post { controller.loaded(html) } }

    @JavascriptInterface
    fun change(html: String) { main.post { controller.userChanged(html) } }

    @JavascriptInterface
    fun state(encoded: String) { main.post { controller.format = parseEditorState(encoded) } }

    @JavascriptInterface
    fun focus(flag: String) { main.post { controller.focused = flag == "1" } }
}

/**
 * The editor page alone, filling its slot. The theme slots are read from [MaterialTheme] once per
 * document; [onChange] hears the owner's edits (debounced by the page), [onLoaded] the normalised
 * markup after each programmatic [HtmlEditorController.load].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlBodyEditor(
    controller: HtmlEditorController,
    placeholder: String,
    onChange: (String) -> Unit,
    onLoaded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val template = remember(context) {
        context.assets.open("html-editor.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val scheme = MaterialTheme.colorScheme
    val fontPx = MaterialTheme.typography.bodyLarge.fontSize.value.toInt().coerceIn(12, 28)
    val document = remember(template, scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, placeholder, fontPx) {
        editorDocument(
            template,
            foreground = cssColor(scheme.onSurface.toArgb()),
            hint = cssColor(scheme.onSurfaceVariant.toArgb()),
            accent = cssColor(scheme.primary.toArgb()),
            fontPx = fontPx,
            placeholder = placeholder,
        )
    }
    controller.listen(onChange, onLoaded)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // our editor over foreign markup: UNTRUSTED_HTML with sandboxed JS (#1371), and no network at all
            CloudWeb.create(ctx, WebProfile.UNTRUSTED_HTML).apply {
                CloudWeb.sandboxedJs(this, true)
                settings.blockNetworkLoads = true
                settings.setSupportZoom(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isFocusable = true
                isFocusableInTouchMode = true
                // Nothing leaves the page: no navigation, no subresource. The document is its own
                // world (see its CSP); this is the second lock on the same door.
                webViewClient = object : CloudWebClient(WebProfile.UNTRUSTED_HTML) {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                controller.attach(this)
                addJavascriptInterface(EditorBridge(controller), "SternaBridge")
                loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            controller.detach(view)
            view.removeJavascriptInterface("SternaBridge")
            view.destroy()
        },
    )
}

/**
 * The body area as the screen uses it: the editor page, the formatting bar under it while it has the
 * focus, and the raw-markup view in its place when "Source" is on. [bodyHtml] is the screen's copy of
 * the markup, read when the source view opens; [onSourceEdit] hears each keystroke in it.
 */
@Composable
internal fun HtmlBodyPane(
    controller: HtmlEditorController,
    bodyHtml: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onLoaded: (String) -> Unit,
    onSourceEdit: (String) -> Unit,
    onInlineImage: () -> Unit,
    modifier: Modifier = Modifier,
    /** #1800: plain-text mode is the owner's opt-in for one message; rich (false) is the default. */
    plainText: Boolean = false,
    onPlainText: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var linkDialog by remember { mutableStateOf(false) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HtmlBodyEditor(
                controller = controller,
                placeholder = placeholder,
                onChange = onChange,
                onLoaded = onLoaded,
                modifier = if (controller.sourceMode) Modifier.size(MailMetrics.s2) else Modifier.fillMaxSize(),
            )
            if (controller.sourceMode) {
                BasicTextField(
                    value = source,
                    onValueChange = { source = it; onSourceEdit(it) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxSize().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
                )
            }
        }
        if (controller.focused || controller.sourceMode) {
            HtmlFormattingBar(
                controller = controller,
                plainText = plainText,
                onPlainText = onPlainText,
                onLink = { linkDialog = true },
                onInlineImage = onInlineImage,
                onSource = {
                    scope.launch {
                        if (controller.sourceMode) {
                            // Back to the page: what was typed as markup is re-read (and sanitised) there.
                            controller.sourceMode = false
                            controller.load(source)
                        } else {
                            source = controller.currentHtml(bodyHtml)
                            controller.sourceMode = true
                        }
                    }
                },
            )
        }
        if (linkDialog) {
            HtmlLinkDialog(
                existing = controller.format.link,
                labelEditable = !controller.format.hasRange && controller.format.link == null,
                onApply = { url, label -> controller.link(url, label); linkDialog = false },
                onRemove = { controller.link("", ""); linkDialog = false },
                onDismiss = { linkDialog = false },
            )
        }
    }
}

/** The address (and, when nothing is selected, the label) for a link. The guard is [normalizeLinkUrl]. */
@Composable
private fun HtmlLinkDialog(
    existing: String?,
    labelEditable: Boolean,
    onApply: (url: String, label: String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(existing.orEmpty()) }
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_link_title)) },
        text = {
            Column {
                TextField(
                    value = url,
                    onValueChange = { url = it; refused = false },
                    label = { Text(stringResource(R.string.compose_link_url)) },
                    singleLine = true,
                    isError = refused,
                )
                if (refused) {
                    Text(
                        stringResource(R.string.compose_link_bad_url),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (labelEditable) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.compose_link_text)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalised = normalizeLinkUrl(url)
                if (normalised == null) refused = true else onApply(normalised, text)
            }) { Text(stringResource(R.string.compose_link_apply)) }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.compose_link_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_link_cancel)) }
            }
        },
    )
}

/**
 * The formatting bar: bold, italic, underline, strikethrough, headings, lists, quote, alignment, text
 * colour, link, picture, clear formatting - and "Source". Scrolls sideways, as the span-model bar did:
 * it does not fit a narrow phone.
 */
@Composable
private fun HtmlFormattingBar(
    controller: HtmlEditorController,
    plainText: Boolean,
    onPlainText: (Boolean) -> Unit,
    onLink: () -> Unit,
    onInlineImage: () -> Unit,
    onSource: () -> Unit,
) {
    val f = controller.format
    val source = controller.sourceMode
    var headingMenu by remember { mutableStateOf(false) }
    var alignMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MailMetrics.s4),
    ) {
        // Plain text (#1800): every formatting tool stays on the bar in rich mode, the default; in
        // plain-text mode they are put away, and this toggle brings them back.
        if (!source) {
            BarButton(Icons.Filled.TextFields, R.string.compose_format_plain_text, plainText) { onPlainText(!plainText) }
        }
        if (!source && !plainText) {
            BarButton(Icons.Filled.FormatBold, R.string.compose_format_bold, f.bold) { controller.exec("bold") }
            BarButton(Icons.Filled.FormatItalic, R.string.compose_format_italic, f.italic) { controller.exec("italic") }
            BarButton(Icons.Filled.FormatUnderlined, R.string.compose_format_underline, f.underline) { controller.exec("underline") }
            BarButton(Icons.Filled.FormatStrikethrough, R.string.compose_format_strikethrough, f.strike) { controller.exec("strikeThrough") }
            Box {
                BarButton(Icons.Filled.Title, R.string.compose_format_heading, f.block.length == 2 && f.block[0] == 'h') { headingMenu = true }
                DropdownMenu(expanded = headingMenu, onDismissRequest = { headingMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_format_paragraph)) },
                        onClick = { headingMenu = false; controller.block("div") },
                    )
                    for (tag in listOf("h1", "h2", "h3")) {
                        DropdownMenuItem(
                            text = { Text(tag.uppercase()) },
                            onClick = { headingMenu = false; controller.block(tag) },
                        )
                    }
                }
            }
            BarButton(Icons.Filled.FormatListBulleted, R.string.compose_format_list_bulleted, f.bullets) { controller.exec("insertUnorderedList") }
            BarButton(Icons.Filled.FormatListNumbered, R.string.compose_format_list_numbered, f.numbers) { controller.exec("insertOrderedList") }
            BarButton(Icons.Filled.FormatQuote, R.string.compose_format_quote, f.inQuote) { controller.quote() }
            Box {
                BarButton(
                    when (f.align) {
                        'c' -> Icons.Filled.FormatAlignCenter
                        'r' -> Icons.Filled.FormatAlignRight
                        else -> Icons.Filled.FormatAlignLeft
                    },
                    R.string.compose_format_align,
                    f.align != 'l',
                ) { alignMenu = true }
                DropdownMenu(expanded = alignMenu, onDismissRequest = { alignMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_format_align_left)) },
                        leadingIcon = { Icon(Icons.Filled.FormatAlignLeft, contentDescription = null) },
                        onClick = { alignMenu = false; controller.exec("justifyLeft") },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_format_align_center)) },
                        leadingIcon = { Icon(Icons.Filled.FormatAlignCenter, contentDescription = null) },
                        onClick = { alignMenu = false; controller.exec("justifyCenter") },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_format_align_right)) },
                        leadingIcon = { Icon(Icons.Filled.FormatAlignRight, contentDescription = null) },
                        onClick = { alignMenu = false; controller.exec("justifyRight") },
                    )
                }
            }
            Box {
                IconButton(onClick = { colorMenu = true }, modifier = Modifier.size(MailMetrics.s48)) {
                    Icon(
                        Icons.Filled.FormatColorText,
                        contentDescription = stringResource(R.string.compose_format_text_color),
                        tint = LocalContentColor.current,
                    )
                }
                DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_format_color_default)) },
                        onClick = { colorMenu = false; controller.color(null) },
                    )
                    for ((kind, label) in TEXT_COLORS.zip(
                        listOf(R.string.compose_format_color_red, R.string.compose_format_color_blue, R.string.compose_format_color_green),
                    )) {
                        val hex = TEXT_COLOR_OF.getValue(kind)
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = Color(AndroidColor.parseColor(hex))) },
                            onClick = { colorMenu = false; controller.color(hex) },
                        )
                    }
                }
            }
            BarButton(Icons.Filled.InsertPhoto, R.string.compose_format_inline_image, false, onInlineImage)
            BarButton(Icons.Filled.Link, R.string.compose_format_link, f.link != null, onLink)
            BarButton(Icons.Filled.FormatClear, R.string.compose_format_clear, false) { controller.exec("removeFormat") }
        }
        if (!plainText) BarButton(Icons.Filled.Code, R.string.compose_format_source, source, onSource)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(MailMetrics.s48).semantics { selected = active },
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

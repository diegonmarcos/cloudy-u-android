package app.sterna.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import app.sterna.ui.theme.MailMetrics

/** Paints [SignatureHtmlTools.highlight] over the text; the text itself, and so every offset, is untouched. */
internal class HighlightTransformation(
    private val colours: Map<SignatureHtmlTools.Kind, Color>,
    private val find: List<Int> = emptyList(),
    private val findLength: Int = 0,
    private val findColour: Color = Color.Unspecified,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val painted = buildAnnotatedString {
            append(text.text)
            for (span in SignatureHtmlTools.highlight(text.text)) {
                colours[span.kind]?.let { addStyle(SpanStyle(color = it), span.start, span.end) }
            }
            if (findLength > 0 && findColour != Color.Unspecified) {
                for (at in find) {
                    if (at + findLength <= text.length) addStyle(SpanStyle(background = findColour), at, at + findLength)
                }
            }
        }
        return TransformedText(painted, OffsetMapping.Identity)
    }
}

/** The dense body style in monospace; gutter and field share it, so their lines stay level. */
@Composable
internal fun codeStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/**
 * The monospace source pane: line numbers with error / warning markers in the gutter, HTML syntax colours,
 * and the key handling a code editor owes (Tab = two spaces, Shift+Tab, Ctrl+Z / Y / F).
 *
 * No wrapping (the field sits in a horizontal scroll, so its width is unbounded): one logical line is one
 * visual line, which is what keeps the gutter's numbers level with the text without measuring the field.
 * The pane scrolls both ways.
 */
@Composable
internal fun SignatureCodeEditor(
    state: SignatureEditorState,
    markers: Map<Int, SignatureValidator.Severity>,
    find: List<Int>,
    findLength: Int,
    onFind: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val transformation = remember(scheme, find, findLength) {
        HighlightTransformation(
            mapOf(
                SignatureHtmlTools.Kind.TAG to scheme.primary,
                SignatureHtmlTools.Kind.ATTRIBUTE to scheme.tertiary,
                SignatureHtmlTools.Kind.VALUE to scheme.secondary,
                SignatureHtmlTools.Kind.COMMENT to scheme.outline,
                SignatureHtmlTools.Kind.ENTITY to scheme.error,
            ),
            find, findLength, scheme.tertiaryContainer,
        )
    }
    val lineCount = state.source.count { it == '\n' } + 1
    val gutter = remember(lineCount, markers, scheme) {
        buildAnnotatedString {
            for (n in 1..lineCount) {
                val sev = markers[n]
                val colour = when (sev) {
                    SignatureValidator.Severity.ERROR -> scheme.error
                    SignatureValidator.Severity.WARNING -> scheme.tertiary
                    SignatureValidator.Severity.INFO -> scheme.secondary
                    null -> scheme.outline
                }
                pushStyle(SpanStyle(color = colour))
                append(if (sev == null) "  " else "● ")
                append(n.toString().padStart(3))
                pop()
                if (n < lineCount) append('\n')
            }
        }
    }
    val context = LocalContext.current
    val codeStyle = codeStyle()
    val textStyle = codeStyle.copy(color = scheme.onSurface)
    Row(modifier.fillMaxSize().background(scheme.surfaceContainerLowest).verticalScroll(rememberScrollState())) {
        Text(
            gutter,
            style = codeStyle,
            textAlign = TextAlign.End,
            modifier = Modifier
                .background(scheme.surfaceContainerLow)
                .padding(horizontal = MailMetrics.s8, vertical = MailMetrics.s10)
                .testTag("sig-gutter"),
        )
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BasicTextField(
                value = state.textField,
                onValueChange = { v ->
                    state.typed(v) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.let(::readSignatureClip)
                            ?: SignatureClip(null, null)
                    }
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = transformation,
                modifier = Modifier
                    .widthIn(min = MailMetrics.s256)
                    .padding(horizontal = MailMetrics.s8, vertical = MailMetrics.s10)
                    .focusRequester(focus)
                    .testTag("sig-source")
                    .onPreviewKeyEvent { e -> handleKey(e, state, onFind) },
            )
        }
    }
}

private fun handleKey(e: androidx.compose.ui.input.key.KeyEvent, state: SignatureEditorState, onFind: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when {
        e.key == Key.Tab -> { state.tab(outdent = e.isShiftPressed); true }
        e.isCtrlPressed && e.key == Key.Z -> { if (e.isShiftPressed) state.redo() else state.undo(); true }
        e.isCtrlPressed && e.key == Key.Y -> { state.redo(); true }
        e.isCtrlPressed && e.key == Key.F -> { onFind(); true }
        else -> false
    }
}

package com.diegonmarcos.cloudwriter.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.em
import com.diegonmarcos.cloudwriter.core.Dictation
import com.diegonmarcos.cloudwriter.core.Edit
import com.diegonmarcos.cloudwriter.core.Span
import com.diegonmarcos.cloudwriter.core.SpanKind
import com.diegonmarcos.cloudwriter.core.WriterGrid
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * The editor's RICH TEXT, drawn over plain Markdown (#800): heading lines larger and bold, **bold**,
 * *italic*, `code` in monospace, quotes and list markers in the accent colour, and every find match
 * highlighted. Offsets are the identity — the characters on screen are the characters in the file,
 * so the caret, the selection, Listen's insertion point and every tool see the same text. The spans
 * themselves are core's Markdown.spans, unit-tested and mutated there.
 */
class MarkdownLook(
    private val spans: List<Span>,
    private val matches: List<IntRange>,
    private val scheme: ColorScheme,
    /** #1532 focus mode: the ranges drawn at the dimmed ink (everything but the current paragraph). */
    private val dimmed: List<IntRange> = emptyList(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text)
        val n = text.length
        for (s in spans) {
            if (s.start < 0 || s.end > n || s.start >= s.end) continue
            b.addStyle(styleOf(s.kind), s.start, s.end)
        }
        for (m in matches) {
            if (m.first < 0 || m.last >= n) continue
            b.addStyle(SpanStyle(background = scheme.tertiaryContainer, color = scheme.onTertiaryContainer), m.first, m.last + 1)
        }
        for (d in dimmed) {
            if (d.first < 0 || d.last >= n || d.isEmpty()) continue
            b.addStyle(SpanStyle(color = scheme.onSurface.copy(alpha = WriterGrid.DIM_ALPHA)), d.first, d.last + 1)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun styleOf(kind: SpanKind): SpanStyle = when (kind) {
        SpanKind.HEADING1 -> SpanStyle(fontSize = 1.6.em, fontWeight = FontWeight.Bold, color = scheme.primary)
        SpanKind.HEADING2 -> SpanStyle(fontSize = 1.35.em, fontWeight = FontWeight.Bold, color = scheme.primary)
        SpanKind.HEADING3 -> SpanStyle(fontSize = 1.15.em, fontWeight = FontWeight.SemiBold, color = scheme.primary)
        SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        SpanKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainerHighest)
        SpanKind.QUOTE -> SpanStyle(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
        SpanKind.MARKER -> SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)
    }
}

/** A toolbar action's result as the field's value. */
fun Edit.asValue(): TextFieldValue = TextFieldValue(text, TextRange(selStart, selEnd))

/** A dictated segment written at the caret, spaced as prose, with the caret after it. */
fun insertAtCaret(v: TextFieldValue, segment: String): TextFieldValue {
    val at = v.selection.max.coerceIn(0, v.text.length)
    val before = v.text.substring(0, at)
    val inserted = Dictation.separator(before, segment) + segment
    return TextFieldValue(before + inserted + v.text.substring(at), TextRange(at + inserted.length))
}

/**
 * The fleet UI kit inside this app's Material theme: the kit paints with a [KitPalette], so the
 * palette is taken from the current colour scheme and a theme switch recolours kit parts with the
 * rest of the screen.
 */
@Composable
fun KitBridge(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    CloudKitTheme(
        KitPalette(
            surface = cs.surfaceContainer,
            surfaceSelected = cs.secondaryContainer,
            textPrimary = cs.onSurface,
            textSecondary = cs.onSurfaceVariant,
            accent = cs.primary,
            hairline = cs.outlineVariant,
            tileInk = cs.onPrimary,
        ),
    ) {
        // The kit sets a dark scheme from the palette; the type scale stays this app's.
        MaterialTheme(colorScheme = cs.copy(background = Color.Transparent), typography = typography, content = content)
    }
}

package com.diegonmarcos.superapp.jsoncanvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LINK = Color(0xFF58A6FF)

fun spansToAnnotated(spans: List<Span>, base: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            color = base,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) Color(0x33888888) else Color.Unspecified,
        )
        if (s.link != null && (s.link.startsWith("http://") || s.link.startsWith("https://") || s.link.startsWith("mailto:"))) {
            withLink(LinkAnnotation.Url(s.link, TextLinkStyles(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(s.text) } }
        } else withStyle(style) { append(s.text) }
    }
}

/** Markdown-light drawn as a column (headings, paragraphs, lists, quotes, code, rules, tappable links). Scrolling is the caller's. */
@Composable
fun MarkdownLiteView(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val blocks = androidx.compose.runtime.remember(text) { MarkdownLite.parse(text) }
    Column(modifier) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(spansToAnnotated(b.spans, color), Modifier.padding(top = 10.dp, bottom = 4.dp),
                fontWeight = FontWeight.Bold, fontSize = when (b.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp })
            is MdBlock.Para -> Text(spansToAnnotated(b.spans, color), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            is MdBlock.Item -> Text(spansToAnnotated(listOf(Span(if (b.ordered) "${b.number}. " else "• ")) + b.spans, color),
                Modifier.padding(start = (12 + 16 * b.depth).dp, top = 2.dp, bottom = 2.dp), style = MaterialTheme.typography.bodyMedium)
            is MdBlock.Quote -> Text(spansToAnnotated(b.spans, color), Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
            is MdBlock.Code -> Text(b.text, Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0x33888888)).padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
            MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
    }
}

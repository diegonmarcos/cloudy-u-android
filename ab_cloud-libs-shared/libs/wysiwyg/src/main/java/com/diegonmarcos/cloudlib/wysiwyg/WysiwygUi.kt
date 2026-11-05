package com.diegonmarcos.cloudlib.wysiwyg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.diegonmarcos.superapp.uikit.KitDensity

object WysiwygTags {
    const val VIEW = "wysiwyg_view"
    const val EDITOR = "wysiwyg_editor"
    const val TOOLBAR = "wysiwyg_toolbar"
    fun tool(id: String) = "wysiwyg_tool_$id"
}

/** Colours the host may override (Cloud-Notes passes its own); the defaults are the Material roles of the host's theme. */
data class WysiwygColors(
    val content: Color,
    val link: Color,
    val codeBackground: Color,
    val rule: Color,
    val quoteBar: Color,
) {
    companion object {
        @Composable
        fun themed() = WysiwygColors(
            content = MaterialTheme.colorScheme.onSurface,
            link = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            rule = MaterialTheme.colorScheme.outlineVariant,
            quoteBar = MaterialTheme.colorScheme.outline,
        )
    }
}

/** The text of [block] with its marks drawn. */
fun annotate(block: Block, colors: WysiwygColors): AnnotatedString = buildAnnotatedString {
    append(block.text)
    for (m in block.marks) {
        val a = m.start.coerceIn(0, block.text.length)
        val b = m.end.coerceIn(0, block.text.length)
        if (b <= a) continue
        when (m.type) {
            MarkType.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), a, b)
            MarkType.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), a, b)
            MarkType.Code -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground), a, b)
            MarkType.Link -> {
                addStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline), a, b)
                m.url?.takeIf { it.isNotBlank() }?.let { addLink(LinkAnnotation.Url(it), a, b) }
            }
        }
    }
}

private fun styleOf(block: Block, colors: WysiwygColors): TextStyle = when (block.kind) {
    BlockKind.Heading -> TextStyle(
        color = colors.content, fontWeight = FontWeight.Bold,
        fontSize = when (block.level) { 1 -> KitDensity.head; 2 -> KitDensity.title; else -> KitDensity.title },
    )
    BlockKind.Code -> TextStyle(color = colors.content, fontFamily = FontFamily.Monospace, fontSize = KitDensity.caption)
    BlockKind.Quote -> TextStyle(color = colors.content, fontStyle = FontStyle.Italic, fontSize = KitDensity.body)
    else -> TextStyle(color = colors.content, fontSize = KitDensity.body)
}

/** The gutter of a block: the bullet, the number, nothing. */
private fun marker(block: Block, number: Int): String = when (block.kind) {
    BlockKind.Bullet -> "•"
    BlockKind.Numbered -> "$number."
    BlockKind.Quote -> "\u2502"
    else -> ""
}

/** Reads a document: Markdown and HTML as formatted blocks, plain text as it is. */
@Composable
fun WysiwygView(source: String, format: TextFormat, modifier: Modifier = Modifier, colors: WysiwygColors = WysiwygColors.themed()) {
    if (format == TextFormat.Text) {
        LazyColumn(modifier.fillMaxSize().padding(KitDensity.rowPadH).testTag(WysiwygTags.VIEW)) {
            itemsIndexed(source.split("\n")) { _, line -> Text(line, style = TextStyle(color = colors.content, fontFamily = FontFamily.Monospace, fontSize = KitDensity.body)) }
        }
        return
    }
    DocView(remember(source, format) { Codecs.decode(format, source) }, modifier, colors)
}

/** Reads a [Doc] as formatted blocks. */
@Composable
fun DocView(doc: Doc, modifier: Modifier = Modifier, colors: WysiwygColors = WysiwygColors.themed()) {
    val numbers = remember(doc) { Ops.numbering(doc.blocks) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV).testTag(WysiwygTags.VIEW), verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadV)) {
        itemsIndexed(doc.blocks, key = { _, b -> b.id }) { i, b ->
            BlockFrame(b, numbers[i], colors) {
                if (b.kind == BlockKind.Rule) HorizontalDivider(color = colors.rule)
                else Text(annotate(b, colors), style = styleOf(b, colors))
            }
        }
    }
}

/** The gutter, the quote bar and the code background around a block's body. */
@Composable
private fun BlockFrame(b: Block, number: Int, colors: WysiwygColors, body: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = KitDensity.large * (if (b.kind == BlockKind.Bullet || b.kind == BlockKind.Numbered) b.level.coerceAtMost(4) else 0)).let { if (b.kind == BlockKind.Code) it.clip(RoundedCornerShape(KitDensity.small)).background(colors.codeBackground).padding(KitDensity.small) else it },
        verticalAlignment = Alignment.Top,
    ) {
        val m = marker(b, number)
        if (m.isNotEmpty()) Text(m, Modifier.width(KitDensity.large + KitDensity.small), style = TextStyle(color = if (b.kind == BlockKind.Quote) colors.quoteBar else colors.content, fontSize = KitDensity.body))
        Box(Modifier.weight(1f)) { body() }
    }
}

/**
 * Edits a document. Markdown and HTML are edited block by block with their marks drawn (bold, italic, code, links), a toolbar
 * for the format commands, Enter splitting a block and Backspace at its start joining it to the one above. [initial] is the
 * file as read (the host re-keys the editor when it loads another file); every edit reports the whole file through [onChange]
 * in the same [format]. Plain text is a monospace field.
 */
@Composable
fun WysiwygEditor(initial: String, format: TextFormat, onChange: (String) -> Unit, modifier: Modifier = Modifier, colors: WysiwygColors = WysiwygColors.themed()) {
    if (format == TextFormat.Text) {
        var tf by remember(initial) { mutableStateOf(TextFieldValue(initial)) }
        BasicTextField(
            value = tf, onValueChange = { tf = it; onChange(it.text) },
            modifier = modifier.fillMaxSize().padding(KitDensity.rowPadH).testTag(WysiwygTags.EDITOR),
            textStyle = TextStyle(color = colors.content, fontFamily = FontFamily.Monospace, fontSize = KitDensity.body),
            cursorBrush = SolidColor(colors.link),
        )
        return
    }
    DocEditor(remember(initial, format) { Codecs.decode(format, initial) }, { onChange(Codecs.encode(format, it, initial)) }, modifier, colors)
}

/**
 * The block editor on a [Doc] itself (no codec in between, so text with asterisks or angle brackets is never reinterpreted).
 * [initial] is the document as loaded; every edit reports the whole new document through [onChange].
 */
@Composable
fun DocEditor(initial: Doc, onChange: (Doc) -> Unit, modifier: Modifier = Modifier, colors: WysiwygColors = WysiwygColors.themed()) {
    var doc by remember(initial) { mutableStateOf(initial) }
    var focused by remember(initial) { mutableStateOf<Long?>(null) }
    var requestFocus by remember(initial) { mutableStateOf<Long?>(null) }
    val selections = remember(initial) { mutableStateMapOf<Long, TextRange>() }
    fun commit(next: Doc) { doc = next; onChange(next) }
    fun indexOfFocused(): Int = doc.blocks.indexOfFirst { it.id == focused }
    fun editFocused(f: (Block, TextRange) -> Block) {
        val i = indexOfFocused()
        if (i < 0) return
        val b = doc.blocks[i]
        commit(Ops.replace(doc, i, f(b, selections[b.id] ?: TextRange(0, 0))))
    }
    val numbers = remember(doc) { Ops.numbering(doc.blocks) }

    Column(modifier.fillMaxSize().testTag(WysiwygTags.EDITOR)) {
        val on = doc.blocks.getOrNull(indexOfFocused())
        /** Whether [type] covers the whole selection in the focused block (lights its tool). */
        fun marked(type: MarkType): Boolean {
            val b = on ?: return false
            val s = selections[b.id] ?: return false
            return s.max > s.min && b.marks.any { it.type == type && it.start <= s.min && it.end >= s.max }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2).testTag(WysiwygTags.TOOLBAR), horizontalArrangement = Arrangement.spacedBy(KitDensity.rowPadH / 2), verticalAlignment = Alignment.CenterVertically) {
            Tool("bold", Icons.Filled.FormatBold, marked(MarkType.Bold)) { editFocused { b, s -> Ops.toggle(b, MarkType.Bold, s.min, s.max) } }
            Tool("italic", Icons.Filled.FormatItalic, marked(MarkType.Italic)) { editFocused { b, s -> Ops.toggle(b, MarkType.Italic, s.min, s.max) } }
            Tool("code", Icons.Filled.Code, on?.kind == BlockKind.Code) { editFocused { b, s -> if (b.kind == BlockKind.Code) Ops.setKind(b, BlockKind.Paragraph) else if (s.max > s.min) Ops.toggle(b, MarkType.Code, s.min, s.max) else Ops.setKind(b, BlockKind.Code) } }
            Tool("heading", Icons.Filled.Title, on?.kind == BlockKind.Heading) { editFocused { b, _ -> if (b.kind == BlockKind.Heading) (if (b.level >= 3) Ops.setKind(b, BlockKind.Paragraph) else Ops.setKind(b, BlockKind.Heading, b.level + 1)) else Ops.setKind(b, BlockKind.Heading, 1) } }
            Tool("bullet", Icons.Filled.FormatListBulleted, on?.kind == BlockKind.Bullet) { editFocused { b, _ -> Ops.setKind(b, if (b.kind == BlockKind.Bullet) BlockKind.Paragraph else BlockKind.Bullet) } }
            Tool("numbered", Icons.Filled.FormatListNumbered, on?.kind == BlockKind.Numbered) { editFocused { b, _ -> Ops.setKind(b, if (b.kind == BlockKind.Numbered) BlockKind.Paragraph else BlockKind.Numbered) } }
            Tool("quote", Icons.Filled.FormatQuote, on?.kind == BlockKind.Quote) { editFocused { b, _ -> Ops.setKind(b, if (b.kind == BlockKind.Quote) BlockKind.Paragraph else BlockKind.Quote) } }
            Tool("rule", Icons.Filled.HorizontalRule, false) {
                val i = indexOfFocused().let { if (it < 0) doc.blocks.lastIndex else it }
                commit(Ops.insertAfter(doc, i, Block(BlockKind.Rule)))
            }
            Tool("delete", Icons.Filled.Delete, false) {
                val i = indexOfFocused()
                if (i >= 0) commit(if (doc.blocks.size == 1) Doc(listOf(Block(BlockKind.Paragraph))) else Ops.remove(doc, i))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KitDensity.rowPadH), verticalArrangement = Arrangement.spacedBy(KitDensity.rowPadV)) {
            itemsIndexed(doc.blocks, key = { _, b -> b.id }) { i, b ->
                BlockFrame(b, numbers[i], colors) {
                    if (b.kind == BlockKind.Rule) HorizontalDivider(Modifier.clickable { focused = b.id }, color = colors.rule)
                    else BlockField(
                        block = b, colors = colors, wantFocus = requestFocus == b.id,
                        onFocusServed = { requestFocus = null },
                        onFocused = { focused = b.id },
                        onSelection = { selections[b.id] = it },
                        onText = { text, sel ->
                            val grew = text.count { it == '\n' } > b.text.count { it == '\n' }
                            val at = sel.start - 1
                            if (grew && b.kind != BlockKind.Code && at >= 0 && text.getOrNull(at) == '\n') {
                                val (head, tail) = Ops.split(Ops.withText(b, text.removeRange(at, at + 1)), at)
                                requestFocus = tail.id
                                commit(Ops.insertAfter(Ops.replace(doc, i, head), i, tail))
                            } else commit(Ops.replace(doc, i, Ops.withText(b, text)))
                        },
                        onBackspaceAtStart = {
                            if (i > 0) {
                                val prev = doc.blocks[i - 1]
                                val merged = Ops.merge(prev, b)
                                requestFocus = prev.id
                                commit(Ops.remove(Ops.replace(doc, i - 1, merged), i))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tool(id: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.testTag(WysiwygTags.tool(id)).clip(RoundedCornerShape(KitDensity.small))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick).padding(KitDensity.small),
    ) {
        Icon(icon, contentDescription = id, Modifier.size(KitDensity.glyph - KitDensity.small), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BlockField(
    block: Block,
    colors: WysiwygColors,
    wantFocus: Boolean,
    onFocusServed: () -> Unit,
    onFocused: () -> Unit,
    onSelection: (TextRange) -> Unit,
    onText: (String, TextRange) -> Unit,
    onBackspaceAtStart: () -> Unit,
) {
    var tf by remember(block.id) { mutableStateOf(TextFieldValue(block.text)) }
    LaunchedEffect(block.text) { if (tf.text != block.text) tf = TextFieldValue(block.text, TextRange(block.text.length)) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(wantFocus) { if (wantFocus) { runCatching { fr.requestFocus() }; onFocusServed() } }
    val transform = remember(block.marks, block.kind, block.level, colors) {
        object : VisualTransformation {
            override fun filter(text: AnnotatedString) = TransformedText(annotate(block.copy(text = text.text), colors), OffsetMapping.Identity)
        }
    }
    BasicTextField(
        value = tf,
        onValueChange = { nv ->
            val textChanged = nv.text != block.text
            tf = nv
            onSelection(nv.selection)
            if (textChanged) onText(nv.text, nv.selection)
        },
        modifier = Modifier.fillMaxWidth().focusRequester(fr)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && tf.selection.collapsed && tf.selection.start == 0) { onBackspaceAtStart(); true } else false
            },
        textStyle = styleOf(block, colors),
        cursorBrush = SolidColor(colors.link),
        visualTransformation = transform,
    )
}

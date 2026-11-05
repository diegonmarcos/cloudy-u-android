package com.diegonmarcos.cloudlib.wysiwyg

import java.util.concurrent.atomic.AtomicLong

enum class BlockKind { Paragraph, Heading, Bullet, Numbered, Quote, Code, Rule }

enum class MarkType { Bold, Italic, Code, Link }

/** An inline format over [start] until [end] (end exclusive) of a block's text. A Link carries its [url]. */
data class Mark(val type: MarkType, val start: Int, val end: Int, val url: String? = null)

/** A block: its [kind], the heading [level] (1..6, else 0), the plain [text] and the inline [marks] over it. [id] is its identity in the editor. */
data class Block(
    val kind: BlockKind,
    val text: String = "",
    val marks: List<Mark> = emptyList(),
    val level: Int = 0,
    val id: Long = BlockIds.next(),
) {
    /** Everything but the id: what two blocks must share to be the same content. */
    fun content(): Block = copy(id = 0)
}

object BlockIds {
    private val n = AtomicLong(0)
    fun next(): Long = n.incrementAndGet()
}

/** A document: blocks in order. */
data class Doc(val blocks: List<Block> = emptyList()) {
    fun content(): List<Block> = blocks.map { it.content() }
}

/** The formats a file can be read and written as. Text is the raw string (no model); Markdown and Html go through the codecs. */
enum class TextFormat {
    Text, Markdown, Html;

    companion object {
        /** The [TextFormat] of an editor-core route format name ("text", "markdown", "html"). */
        fun of(name: String): TextFormat = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Text
    }
}

/** One entry point per format. Text is not a document: the host edits the raw string. */
object Codecs {
    fun decode(format: TextFormat, source: String): Doc = when (format) {
        TextFormat.Markdown -> MarkdownCodec.parse(source)
        TextFormat.Html -> HtmlCodec.parse(source)
        TextFormat.Text -> Doc(source.split("\n").map { Block(BlockKind.Paragraph, it) })
    }

    /** [original] is the file as read: HTML keeps its head, scripts and attributes around the edited body. */
    fun encode(format: TextFormat, doc: Doc, original: String? = null): String = when (format) {
        TextFormat.Markdown -> MarkdownCodec.render(doc)
        TextFormat.Html -> HtmlCodec.renderDocument(doc, original)
        TextFormat.Text -> doc.blocks.joinToString("\n") { it.text }
    }
}

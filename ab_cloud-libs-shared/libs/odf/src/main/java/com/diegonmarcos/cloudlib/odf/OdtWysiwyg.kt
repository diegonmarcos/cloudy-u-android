package com.diegonmarcos.cloudlib.odf

import com.diegonmarcos.cloudlib.wysiwyg.Block
import com.diegonmarcos.cloudlib.wysiwyg.BlockKind
import com.diegonmarcos.cloudlib.wysiwyg.Doc

/** An odt as a WYSIWYG document: headings, paragraphs and list items (text only; character styles stay in the file untouched). */
fun OdfFile.toDoc(): Doc = Doc(
    textBlocks().map { b ->
        when (b.kind) {
            OdtBlock.Kind.Heading -> Block(BlockKind.Heading, b.text, level = b.level)
            OdtBlock.Kind.ListItem -> Block(BlockKind.Bullet, b.text)
            OdtBlock.Kind.Paragraph -> Block(BlockKind.Paragraph, b.text)
        }
    },
)

/** The WYSIWYG blocks as odt blocks: a rule has no odt block, quote, code and numbered items become paragraphs and list items. */
fun Doc.toOdtBlocks(): List<OdtBlock> = blocks.mapNotNull { b ->
    when (b.kind) {
        BlockKind.Heading -> OdtBlock(OdtBlock.Kind.Heading, b.level.coerceIn(1, 6), b.text)
        BlockKind.Bullet, BlockKind.Numbered -> OdtBlock(OdtBlock.Kind.ListItem, 0, b.text)
        BlockKind.Paragraph, BlockKind.Quote, BlockKind.Code -> OdtBlock(OdtBlock.Kind.Paragraph, 0, b.text)
        BlockKind.Rule -> null
    }
}

/** Writes an edited WYSIWYG document back into the odt. */
fun OdfFile.applyDoc(doc: Doc) = setTextBlocks(doc.toOdtBlocks())

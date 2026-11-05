package com.diegonmarcos.cloudlib.wysiwyg

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownCodecTest {
    private val sample = """
        # Title

        A paragraph with **bold**, *italic*, `code` and a [link](https://example.org/a).

        ## Lists

        - one
        - two
          - nested

        1. first
        2. second

        > quoted text

        ```
        val x = 1
        ```

        ---

        The end.
    """.trimIndent() + "\n"

    @Test fun `the blocks of a document are read`() {
        val d = MarkdownCodec.parse(sample)
        assertEquals(
            listOf(BlockKind.Heading, BlockKind.Paragraph, BlockKind.Heading, BlockKind.Bullet, BlockKind.Bullet, BlockKind.Bullet, BlockKind.Numbered, BlockKind.Numbered, BlockKind.Quote, BlockKind.Code, BlockKind.Rule, BlockKind.Paragraph),
            d.blocks.map { it.kind },
        )
        assertEquals(1, d.blocks[0].level)
        assertEquals(2, d.blocks[2].level)
        assertEquals(1, d.blocks[5].level)
        assertEquals("val x = 1", d.blocks[9].text)
    }

    @Test fun `inline marks are read with their ranges`() {
        val b = MarkdownCodec.parse("A **bold** and *it* and `c` and [l](u)").blocks.single()
        assertEquals("A bold and it and c and l", b.text)
        assertEquals(
            listOf(Mark(MarkType.Bold, 2, 6), Mark(MarkType.Italic, 11, 13), Mark(MarkType.Code, 18, 19), Mark(MarkType.Link, 24, 25, "u")),
            b.marks,
        )
    }

    @Test fun `markdown round-trips through the model unchanged`() {
        assertEquals(sample, MarkdownCodec.render(MarkdownCodec.parse(sample)))
    }

    @Test fun `a second pass changes nothing`() {
        val once = MarkdownCodec.render(MarkdownCodec.parse("*a **b** c*\n\n***both***\n\n2 * 3 * 4\n\nsnake_case_word\n"))
        assertEquals(once, MarkdownCodec.render(MarkdownCodec.parse(once)))
    }

    @Test fun `stars used as arithmetic and snake case stay plain text`() {
        val b = MarkdownCodec.parse("2 * 3 * 4 and snake_case_word").blocks.single()
        assertEquals("2 * 3 * 4 and snake_case_word", b.text)
        assertEquals(emptyList<Mark>(), b.marks)
    }

    @Test fun `nested and overlapping marks write back as markdown`() {
        val b = Block(BlockKind.Paragraph, "abcd", listOf(Mark(MarkType.Bold, 0, 3), Mark(MarkType.Italic, 2, 4)))
        val md = MarkdownCodec.render(Doc(listOf(b)))
        val back = MarkdownCodec.parse(md).blocks.single()
        assertEquals("abcd", back.text)
        assertEquals(b.marks.sortedBy { it.start }, back.marks.sortedBy { it.start })
    }

    @Test fun `numbered items are renumbered and soft line breaks stay in the paragraph`() {
        val d = MarkdownCodec.parse("1. a\n1. b\n7. c\n\nline one\nline two\n")
        assertEquals("1. a\n2. b\n3. c\n\nline one\nline two\n", MarkdownCodec.render(d))
    }

    @Test fun `an empty document renders as empty`() {
        assertEquals("", MarkdownCodec.render(Doc()))
        assertEquals(0, MarkdownCodec.parse("").blocks.size)
    }
}

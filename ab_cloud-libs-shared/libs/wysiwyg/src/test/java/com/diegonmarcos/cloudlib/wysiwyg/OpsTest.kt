package com.diegonmarcos.cloudlib.wysiwyg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsTest {
    private fun p(text: String, vararg m: Mark) = Block(BlockKind.Paragraph, text, m.toList())

    @Test fun `typing at the end of bold stays bold, typing before it shifts it`() {
        val b = p("hello world", Mark(MarkType.Bold, 0, 5))
        assertEquals(listOf(Mark(MarkType.Bold, 0, 6)), Ops.withText(b, "helloX world").marks)
        assertEquals(listOf(Mark(MarkType.Bold, 1, 6)), Ops.withText(b, "Xhello world").marks)
        assertEquals(listOf(Mark(MarkType.Bold, 0, 5)), Ops.withText(b, "hello world!").marks)
    }

    @Test fun `deleting inside a mark shrinks it and deleting all of it drops it`() {
        val b = p("hello world", Mark(MarkType.Italic, 6, 11))
        assertEquals(listOf(Mark(MarkType.Italic, 6, 9)), Ops.withText(b, "hello wor").marks)
        assertEquals(emptyList<Mark>(), Ops.withText(b, "hello ").marks)
    }

    @Test fun `toggle adds a mark over the selection and removes it when fully covered`() {
        val b = p("abcdef")
        val on = Ops.toggle(b, MarkType.Bold, 1, 4)
        assertEquals(listOf(Mark(MarkType.Bold, 1, 4)), on.marks)
        assertEquals(emptyList<Mark>(), Ops.toggle(on, MarkType.Bold, 1, 4).marks)
        assertEquals(listOf(Mark(MarkType.Bold, 1, 2), Mark(MarkType.Bold, 3, 4)), Ops.toggle(on, MarkType.Bold, 2, 3).marks)
        assertEquals(listOf(Mark(MarkType.Bold, 0, 4)), Ops.toggle(on, MarkType.Bold, 0, 4).marks)
        assertEquals("an empty selection changes nothing", b, Ops.toggle(b, MarkType.Bold, 2, 2))
    }

    @Test fun `marks of different types are independent`() {
        val b = Ops.toggle(Ops.toggle(p("abcd"), MarkType.Bold, 0, 4), MarkType.Italic, 1, 3)
        assertEquals(setOf(Mark(MarkType.Bold, 0, 4), Mark(MarkType.Italic, 1, 3)), b.marks.toSet())
    }

    @Test fun `links are set and cleared by range`() {
        val b = Ops.setLink(p("click here now"), 6, 10, "https://x.org")
        assertEquals(listOf(Mark(MarkType.Link, 6, 10, "https://x.org")), b.marks)
        assertEquals(emptyList<Mark>(), Ops.setLink(b, 7, 8, "").marks)
    }

    @Test fun `split carries the marks to both halves and merge puts them back`() {
        val b = p("abcdef", Mark(MarkType.Bold, 1, 5))
        val (head, tail) = Ops.split(b, 3)
        assertEquals("abc", head.text)
        assertEquals(listOf(Mark(MarkType.Bold, 1, 3)), head.marks)
        assertEquals("def", tail.text)
        assertEquals(listOf(Mark(MarkType.Bold, 0, 2)), tail.marks)
        assertEquals(b.copy(id = 0), Ops.merge(head, tail).copy(id = 0))
        assertEquals("a heading continues as a paragraph", BlockKind.Paragraph, Ops.split(Block(BlockKind.Heading, "ab", level = 1), 1).second.kind)
    }

    @Test fun `changing the kind keeps the text, a heading takes a clamped level, a rule loses its text`() {
        val b = p("text", Mark(MarkType.Bold, 0, 4))
        assertEquals(3, Ops.setKind(b, BlockKind.Heading, 3).level)
        assertEquals(6, Ops.setKind(b, BlockKind.Heading, 9).level)
        assertEquals(0, Ops.setKind(Ops.setKind(b, BlockKind.Heading, 2), BlockKind.Bullet).level)
        assertEquals("", Ops.setKind(b, BlockKind.Rule).text)
        assertEquals(emptyList<Mark>(), Ops.setKind(b, BlockKind.Code).marks)
    }

    @Test fun `numbering counts a run and restarts after another block`() {
        val blocks = listOf(BlockKind.Numbered, BlockKind.Numbered, BlockKind.Paragraph, BlockKind.Numbered, BlockKind.Bullet, BlockKind.Numbered).map { Block(it, "x") }
        assertEquals(listOf(1, 2, 0, 1, 0, 2), Ops.numbering(blocks))
    }

    @Test fun `doc operations insert, replace and remove by index`() {
        val d = Doc(listOf(p("a"), p("b")))
        assertEquals(listOf("a", "x", "b"), Ops.insertAfter(d, 0, p("x")).blocks.map { it.text })
        assertEquals(listOf("a", "y"), Ops.replace(d, 1, p("y")).blocks.map { it.text })
        assertEquals(listOf("b"), Ops.remove(d, 0).blocks.map { it.text })
        assertTrue(Codecs.encode(TextFormat.Text, Codecs.decode(TextFormat.Text, "l1\nl2")) == "l1\nl2")
    }
}

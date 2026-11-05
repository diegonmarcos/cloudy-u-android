package com.diegonmarcos.cloudlib.wysiwyg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCodecTest {
    private val doc = Doc(
        listOf(
            Block(BlockKind.Heading, "Title & more", level = 1),
            Block(BlockKind.Paragraph, "Hello bold it code link <tag>", listOf(Mark(MarkType.Bold, 6, 10), Mark(MarkType.Italic, 11, 13), Mark(MarkType.Code, 14, 18), Mark(MarkType.Link, 19, 23, "https://e.org/?a=1&b=2"))),
            Block(BlockKind.Bullet, "one"),
            Block(BlockKind.Bullet, "two"),
            Block(BlockKind.Bullet, "nested", level = 1),
            Block(BlockKind.Bullet, "three"),
            Block(BlockKind.Numbered, "first"),
            Block(BlockKind.Numbered, "second"),
            Block(BlockKind.Quote, "wise words"),
            Block(BlockKind.Code, "if (a < b) {\n  go()\n}"),
            Block(BlockKind.Rule),
            Block(BlockKind.Paragraph, "line one\nline two"),
        ),
    )

    @Test fun `a document round-trips through html`() {
        val html = HtmlCodec.render(doc)
        assertEquals(doc.content(), HtmlCodec.parse(html).content())
    }

    @Test fun `special characters are escaped in text and attributes`() {
        val html = HtmlCodec.render(doc)
        assertTrue(html, html.contains("Title &amp; more"))
        assertTrue(html, html.contains("&lt;tag&gt;"))
        assertTrue(html, html.contains("href=\"https://e.org/?a=1&amp;b=2\""))
    }

    @Test fun `a hand written page is read, scripts and head are not content`() {
        val src = "<!DOCTYPE html><html><head><title>T</title><style>p{color:red}</style></head><body class=x>\n<h2>Head</h2>\n<p>Some <b>bold</b>\n   text&nbsp;here &#65;&#x42;</p><script>var a = '<p>no</p>';</script><ul><li>a</li><li>b</li></ul></body></html>"
        val d = HtmlCodec.parse(src)
        assertEquals(listOf(BlockKind.Heading, BlockKind.Paragraph, BlockKind.Bullet, BlockKind.Bullet), d.blocks.map { it.kind })
        assertEquals("Some bold text here AB", d.blocks[1].text)
        assertEquals(listOf(Mark(MarkType.Bold, 5, 9)), d.blocks[1].marks)
    }

    @Test fun `editing keeps the head scripts and body attributes of the original file`() {
        val original = "<!doctype html><html><head><title>Keep</title><script>x()</script></head><body class=\"c\" onload=\"y()\"><p>old</p></body></html>"
        val out = HtmlCodec.renderDocument(Doc(listOf(Block(BlockKind.Paragraph, "new text"))), original)
        assertTrue(out, out.startsWith("<!doctype html><html><head><title>Keep</title><script>x()</script></head><body class=\"c\" onload=\"y()\">"))
        assertTrue(out, out.contains("<p>new text</p>"))
        assertTrue(out, !out.contains("old") && out.endsWith("</body></html>"))
        assertEquals("new text", HtmlCodec.parse(out).blocks.single().text)
    }

    @Test fun `a new file gets a minimal page`() {
        val out = HtmlCodec.renderDocument(Doc(listOf(Block(BlockKind.Paragraph, "x"))), null)
        assertTrue(out.contains("<body>") && out.contains("<p>x</p>"))
    }

    @Test fun `markdown and html agree on the same document`() {
        val md = "# T\n\nA **b** c\n\n- x\n- y\n"
        val viaHtml = HtmlCodec.parse(HtmlCodec.render(MarkdownCodec.parse(md)))
        assertEquals(md, MarkdownCodec.render(viaHtml))
    }
}

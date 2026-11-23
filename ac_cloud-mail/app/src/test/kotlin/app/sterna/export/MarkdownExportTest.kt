package app.sterna.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1870 "Export... > Markdown (.md)": the HTML-to-Markdown conversion on fixtures, and the document around it. */
class MarkdownExportTest {

    @Test fun `headings, paragraphs and emphasis`() {
        assertEquals(
            "# Title\n\nHello **world** and *you*.\n\n### Small print",
            htmlToMarkdown("<h1>Title</h1><p>Hello <strong>world</strong> and <em>you</em>.</p><h3>Small print</h3>"),
        )
    }

    @Test fun `bullet, nested and numbered lists`() {
        assertEquals(
            "- One\n- Two\n  - Nested\n\n3. Three\n4. Four",
            htmlToMarkdown("<ul><li>One</li><li>Two<ul><li>Nested</li></ul></li></ul><ol start=\"3\"><li>Three</li><li>Four</li></ol>"),
        )
    }

    @Test fun `list items with implied end tags`() {
        assertEquals("- a\n- b", htmlToMarkdown("<ul><li>a<li>b</ul>"))
    }

    @Test fun `links, bare links, images`() {
        assertEquals(
            "See [the docs](https://example.org/a_b) or <https://x.org>.",
            htmlToMarkdown("<p>See <a href=\"https://example.org/a_b\">the docs</a> or <a href=\"https://x.org\">https://x.org</a>.</p>"),
        )
        assertEquals("![Logo](cid:logo@x)", htmlToMarkdown("<p><img src=\"cid:logo@x\" alt=\"Logo\"></p>"))
        assertEquals("no script link", htmlToMarkdown("<p><a href=\"javascript:alert(1)\">no script link</a></p>"))
    }

    @Test fun `a data table becomes a GFM table, pipes escaped`() {
        assertEquals(
            "| Name | Qty |\n| --- | --- |\n| Apple | 3 |\n| Pear\\|x | 5 |",
            htmlToMarkdown(
                "<table><tr><th>Name</th><th>Qty</th></tr><tr><td>Apple</td><td>3</td></tr>" +
                    "<tr><td>Pear|x</td><td>5</td></tr></table>",
            ),
        )
    }

    @Test fun `a layout table is unwrapped into its content`() {
        assertEquals(
            "Inner",
            htmlToMarkdown("<table><tr><td><table><tr><td><p>Inner</p></td></tr></table></td></tr></table>"),
        )
    }

    @Test fun `a quoted reply becomes a blockquote`() {
        assertEquals(
            "Thanks!\n\n> On Monday, Ann wrote:\n>\n> Can you **send** it?",
            htmlToMarkdown("<p>Thanks!</p><blockquote><p>On Monday, Ann wrote:</p><p>Can you <b>send</b> it?</p></blockquote>"),
        )
    }

    @Test fun `code blocks and inline code keep their text unescaped`() {
        assertEquals(
            "```\nval x = 1 < 2\n  y()\n```\n\nRun `make_all`",
            htmlToMarkdown("<pre>val x = 1 &lt; 2\n  y()</pre><p>Run <code>make_all</code></p>"),
        )
    }

    @Test fun `line breaks, entities, escaping, and what is never text`() {
        assertEquals("Line one\\\nLine two", htmlToMarkdown("<p>Line one<br>Line two<br></p>"))
        assertEquals("5 \\* 3 = 15 & a\\_b", htmlToMarkdown("<p>5 * 3 = 15 &amp; a_b</p>"))
        assertEquals("Visible", htmlToMarkdown("<style>p{color:red}</style><p>Visible</p><script>alert(1)</script>"))
        assertEquals("café — ok", htmlToMarkdown("<p>caf&#233; &mdash; ok</p>"))
    }

    @Test fun `the document - front matter, title, header block, body, attachments`() {
        val msg = ExportMessage(
            subject = "Hi \"there\"", from = "Ann <a@x.org>", to = "b@y.org", cc = "",
            dateIso = "2026-11-23T10:00:00Z", messageId = "m@x.org", bodyHtml = "<h2>Agenda</h2><ul><li>Item</li></ul>",
            parts = listOf(
                part("report.pdf", ByteArray(2048), "application/pdf"),
                part("logo.png", ByteArray(10), "image/png", cid = "logo@x", inline = true),
            ),
        )
        val md = buildMarkdown(msg, LABELS.copy(attachments = "Attachments"))
        assertTrue(md.startsWith("---\nsubject: \"Hi \\\"there\\\"\"\nfrom: \"Ann <a@x.org>\"\nto: \"b@y.org\"\ndate: \"2026-11-23T10:00:00Z\"\nmessage_id: \"m@x.org\"\n---\n\n"))
        assertFalse("an empty Cc is left out", "cc:" in md)
        assertTrue("# Hi \"there\"\n" in md)
        assertTrue("**From:** Ann <a@x.org>  \n" in md)
        assertTrue("the HTML body as Markdown", "## Agenda\n\n- Item\n" in md)
        assertTrue("files are listed by name", "## Attachments\n\n- `report.pdf` (application/pdf, 2 KB)\n" in md)
        assertFalse("inline images are part of the body, not the list", "logo.png" in md)
    }

    @Test fun `a plain-text body is kept as it is`() {
        val msg = ExportMessage(
            subject = "Plain", from = "a@x", to = "b@y", cc = "", dateIso = null, messageId = null,
            bodyHtml = "<p>Line *one*</p>", parts = emptyList(), bodyText = "Line *one*\r\nLine two",
        )
        val md = buildMarkdown(msg, LABELS)
        assertTrue("no escaping, no reflow", "\n---\n\nLine *one*\nLine two\n" in md)
        assertFalse("no attachment heading without attachments", "## " in md)
    }

    @Test fun `Markdown is an export format through the same path, with its extension and type`() {
        assertEquals("md", ExportFormat.MARKDOWN.ext)
        assertEquals("text/markdown", ExportFormat.MARKDOWN.mime)
        assertEquals("2026-11-23 Plan.md", exportFileName("2026-11-23T10:00:00Z", "Plan", ExportFormat.MARKDOWN.ext))
    }
}

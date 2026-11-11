package com.diegonmarcos.cloudwriter.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** #1418 bottom nav order, OCR routing + landing, export formats, HTML/text conversion, imports. */
class BoxesTest {

    // ── bottom nav order ─────────────────────────────────────────────────
    @Test fun `the bar is Enhancer Drafter Home Translator Grammar, in that order`() {
        assertEquals(listOf("enhancer", "drafter", "home", "translator", "grammar"), WriterTabs.ORDER)
        assertNull(WriterTabs.check(listOf("enhancer", "drafter", "home", "translator", "grammar")))
    }

    @Test fun `a swapped, short or long bar is named`() {
        assertEquals("tab 1 is 'drafter', expected 'enhancer'", WriterTabs.check(listOf("drafter", "enhancer", "home", "translator", "grammar")))
        assertEquals("tab 5 is 'translator', expected 'grammar'", WriterTabs.check(listOf("enhancer", "drafter", "home", "translator", "translator")))
        assertEquals("tab 3 is 'documents', expected 'home'", WriterTabs.check(listOf("enhancer", "drafter", "documents", "translator", "grammar")))
        assertEquals("tab 4 is 'grammar', expected 'translator'", WriterTabs.check(listOf("enhancer", "drafter", "home", "grammar", "translator")))
        assertEquals("expected 5 tabs, found 4", WriterTabs.check(listOf("enhancer", "drafter", "home", "translator")))
        assertEquals("expected 5 tabs, found 6", WriterTabs.check(WriterTabs.ORDER + "settings"))
    }

    // ── OCR routing: ML vs OpenRouter, with fallback ──────────────────────
    private var orCalls = 0
    private var mlCalls = 0
    private fun or(o: Outcome): () -> Outcome = { orCalls++; o }
    private fun ml(o: Outcome): () -> Outcome = { mlCalls++; o }

    @Test fun `engine ids parse and default to ML`() {
        assertEquals(OcrEngine.ML, OcrEngine.DEFAULT)
        assertEquals(OcrEngine.ML, OcrEngine.of(null))
        assertEquals(OcrEngine.ML, OcrEngine.of("tesseract"))
        assertEquals(OcrEngine.OPENROUTER, OcrEngine.of("openrouter"))
        assertEquals(OcrEngine.ML, OcrEngine.of("ml"))
        assertEquals(Route.MODEL, Ocr.route(OcrEngine.OPENROUTER))
        assertEquals(Route.ML, Ocr.route(OcrEngine.ML))
    }

    @Test fun `ML runs on-device only and never calls OpenRouter`() {
        val a = Ocr.run(OcrEngine.ML, null, or(Outcome.ok("cloud")), ml(Outcome.ok("local")))
        assertEquals("local", a.text); assertEquals(Route.ML, a.route); assertFalse(a.fellBack)
        assertEquals(0, orCalls); assertEquals(1, mlCalls)
    }

    @Test fun `OpenRouter answers when it can`() {
        val a = Ocr.run(OcrEngine.OPENROUTER, null, or(Outcome.ok("cloud")), ml(Outcome.ok("local")))
        assertEquals("cloud", a.text); assertEquals(Route.MODEL, a.route); assertEquals(0, mlCalls)
    }

    @Test fun `OpenRouter blocked falls back to ML without calling it`() {
        val a = Ocr.run(OcrEngine.OPENROUTER, Routing.NO_TOKEN, or(Outcome.ok("cloud")), ml(Outcome.ok("local")))
        assertEquals("local", a.text); assertTrue(a.fellBack); assertEquals(Routing.NO_TOKEN, a.fallbackReason); assertEquals(0, orCalls)
    }

    @Test fun `OpenRouter failing or reading nothing falls back to ML`() {
        val a = Ocr.run(OcrEngine.OPENROUTER, null, or(Outcome.failed("HTTP 500")), ml(Outcome.ok("local")))
        assertEquals("local", a.text); assertEquals("HTTP 500", a.fallbackReason)
        val b = Ocr.run(OcrEngine.OPENROUTER, null, or(Outcome.ok("  ")), ml(Outcome.ok("local")))
        assertEquals("local", b.text); assertEquals(Ocr.NOTHING_READ, b.fallbackReason)
    }

    @Test fun `ML reading nothing is an error, not an empty box`() {
        val a = Ocr.run(OcrEngine.ML, null, or(Outcome.ok("cloud")), ml(Outcome.ok("")))
        assertFalse(a.ok); assertEquals("on-device: " + Ocr.NOTHING_READ, a.error)
        val b = Ocr.run(OcrEngine.OPENROUTER, null, or(Outcome.failed("x")), ml(Outcome.ok("\n")))
        assertFalse(b.ok); assertEquals("model: x; on-device: " + Ocr.NOTHING_READ, b.error)
    }

    // ── the OCR result reaches the box ───────────────────────────────────
    @Test fun `recognised text fills an empty box`() {
        assertEquals("<p>Hello<br>world</p>", Ocr.intoBox("", "Hello\nworld"))
        assertEquals("<p>Hi</p>", Ocr.intoBox("<p><br></p>", " Hi "))
    }

    @Test fun `recognised text is appended after existing text, and blank changes nothing`() {
        assertEquals("<p>Old</p><p>New</p>", Ocr.intoBox("<p>Old</p>", "New"))
        assertEquals("<p>Old</p>", Ocr.intoBox("<p>Old</p>", "  "))
        assertEquals("Old\n\nNew", HtmlText.htmlToText(Ocr.intoBox("<p>Old</p>", "New")))
    }

    @Test fun `vision body carries the prompt and a base64 data URL`() {
        val body = OpenRouter.visionBody("m/v", Ocr.PROMPT, byteArrayOf(1, 2, 3), "image/jpeg", 99)
        assertEquals("m/v", body.getString("model")); assertEquals(99, body.getInt("max_tokens"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(Ocr.PROMPT, content.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun `vision models are image-in text-out, cheapest first`() {
        val body = JSONObject().put("data", org.json.JSONArray()
            .put(model("b/vis", "0.000002", "text", "image"))
            .put(model("a/txt", "0.0000001", "text"))
            .put(model("c/vis", "0.000001", "image"))).toString()
        val all = OpenRouter.catalogue(body)
        assertTrue(all.first { it.id == "b/vis" }.imageIn); assertFalse(all.first { it.id == "a/txt" }.imageIn)
        assertEquals(listOf("c/vis", "b/vis"), OpenRouter.visionModels(all).map { it.id })
    }

    private fun model(id: String, price: String, vararg ins: String) = JSONObject().put("id", id)
        .put("pricing", JSONObject().put("prompt", price))
        .put("architecture", JSONObject().put("input_modalities", org.json.JSONArray(ins.toList())).put("output_modalities", org.json.JSONArray(listOf("text"))))

    // ── HTML / text conversion ───────────────────────────────────────────
    @Test fun `text to html escapes and splits paragraphs and lines`() {
        assertEquals("<p>a &lt;b&gt; &amp; &quot;c&quot;</p>", HtmlText.textToHtml("a <b> & \"c\""))
        assertEquals("<p>one<br>two</p><p>three</p>", HtmlText.textToHtml("one\r\ntwo\n\n\nthree\n"))
        assertEquals("", HtmlText.textToHtml("  \n "))
    }

    @Test fun `html to text keeps paragraphs, breaks, lists and entities`() {
        assertEquals("one\ntwo\n\nthree", HtmlText.htmlToText("<p>one<br>two</p><p>three</p>"))
        assertEquals("- a\n- b", HtmlText.htmlToText("<ul><li>a</li><li>b</li></ul>"))
        assertEquals("T\n\nx & y < z > \"q\" 'p' é é &lt;", HtmlText.htmlToText("<h1>T</h1><div>x &amp; y &lt; z &gt; &quot;q&quot; &#39;p&#39; &#233; &#xe9; &amp;lt;</div>"))
        assertEquals("ok", HtmlText.htmlToText("<style>p{}</style><script>x()</script><b>ok</b>"))
        assertEquals("a b", HtmlText.htmlToText("a&nbsp;b   "))
    }

    @Test fun `text survives a round trip through html`() {
        val t = "Title & co\n\nLine one\nLine <two>"
        assertEquals(t, HtmlText.htmlToText(HtmlText.textToHtml(t)))
    }

    @Test fun `html to markdown covers the editor's marks`() {
        val html = "<h2>Head</h2><p><strong>b</strong> <em>i</em> <code>c</code> <a href=\"https://x.y\">l</a></p>" +
            "<ul><li>u1</li><li>u2</li></ul><ol><li>o1</li><li>o2</li></ol><blockquote>q1<br>q2</blockquote><pre><code>x = 1</code></pre>"
        assertEquals(
            "## Head\n\n**b** *i* `c` [l](https://x.y)\n\n- u1\n- u2\n\n1. o1\n2. o2\n\n> q1\n> q2\n\n```\nx = 1\n```",
            HtmlText.htmlToMarkdown(html),
        )
        assertEquals("a  \nb", HtmlText.htmlToMarkdown("<p>a<br>b</p>"))
        assertEquals("# A\n\n###### F", HtmlText.htmlToMarkdown("<h1>A</h1><h6>F</h6>"))
        assertEquals("**B** *I*", HtmlText.htmlToMarkdown("<b>B</b> <i>I</i>"))
    }

    @Test fun `markdown to html covers headings, marks, lists, quotes and code`() {
        assertEquals("<h1>T</h1><p><strong>b</strong> <em>i</em> <code>c</code> <a href=\"https://x\">l</a></p>",
            HtmlText.markdownToHtml("# T\n**b** *i* `c` [l](https://x)"))
        assertEquals("<ul><li>a</li><li>b</li></ul><ol><li>c</li><li>d</li></ol>", HtmlText.markdownToHtml("- a\n* b\n1. c\n2) d"))
        assertEquals("<blockquote>q<br>r</blockquote><pre><code>&lt;x&gt;\ny</code></pre>", HtmlText.markdownToHtml("> q\n>r\n```\n<x>\ny\n```"))
        assertEquals("<p>a<br>b</p><p>c</p>", HtmlText.markdownToHtml("a\nb\n\nc"))
        assertEquals("<h6>s</h6>", HtmlText.markdownToHtml("###### s"))
        assertEquals("<p>####### s</p>", HtmlText.markdownToHtml("####### s"))
    }

    @Test fun `markdown survives html and back`() {
        val md = "## Head\n\n**b** *i*\n\n- u1\n- u2"
        assertEquals(md, HtmlText.htmlToMarkdown(HtmlText.markdownToHtml(md)))
    }

    @Test fun `edit mode defaults to WYSIWYG`() {
        assertEquals(EditMode.WYSIWYG, EditMode.DEFAULT)
        assertEquals(EditMode.WYSIWYG, EditMode.of(null))
        assertEquals(EditMode.WYSIWYG, EditMode.of("rtf"))
        assertEquals(EditMode.TEXT, EditMode.of("text"))
        assertEquals(EditMode.WYSIWYG, EditMode.of("wysiwyg"))
    }

    // ── export formats ───────────────────────────────────────────────────
    private val doc = "<h1>Hi</h1><p>a &amp; <strong>b</strong></p>"

    @Test fun `export txt md and html`() {
        assertEquals("Hi\n\na & b\n", Export.render(ExportFormat.TXT, doc, "t"))
        assertEquals("# Hi\n\na & **b**\n", Export.render(ExportFormat.MD, doc, "t"))
        assertEquals("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>t &amp; u</title></head><body>\n$doc\n</body></html>\n",
            Export.render(ExportFormat.HTML, doc, "t & u"))
    }

    @Test fun `export formats carry extension and mime`() {
        assertEquals(listOf("md", "txt", "html"), ExportFormat.values().map { it.id })
        assertEquals("text/markdown", ExportFormat.MD.mime); assertEquals("text/plain", ExportFormat.TXT.mime); assertEquals("text/html", ExportFormat.HTML.mime)
        assertEquals(ExportFormat.HTML, ExportFormat.of("html")); assertNull(ExportFormat.of("pdf"))
        assertEquals("Hi.md", Export.fileName(doc, ExportFormat.MD))
        assertEquals("writer.txt", Export.fileName("<p> </p>", ExportFormat.TXT))
        assertEquals("Hello-World-x.html", Export.fileName("<p>Hello, World! x</p>", ExportFormat.HTML))
        assertEquals(40, Export.fileName("<p>" + "a".repeat(60) + "</p>", ExportFormat.MD).removeSuffix(".md").length)
    }

    // ── imports ──────────────────────────────────────────────────────────
    @Test fun `import kind by extension then mime`() {
        assertEquals(ImportKind.TXT, Import.kind("a.TXT", null)); assertEquals(ImportKind.TXT, Import.kind("a.text", null))
        assertEquals(ImportKind.MD, Import.kind("a.md", null)); assertEquals(ImportKind.MD, Import.kind("a.markdown", "x"))
        assertEquals(ImportKind.HTML, Import.kind("a.htm", null)); assertEquals(ImportKind.HTML, Import.kind("a.html", null))
        assertEquals(ImportKind.DOCX, Import.kind("a.docx", null))
        assertEquals(ImportKind.MD, Import.kind(null, "text/markdown")); assertEquals(ImportKind.HTML, Import.kind("x", "text/html"))
        assertEquals(ImportKind.DOCX, Import.kind(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(ImportKind.TXT, Import.kind(null, "text/csv")); assertEquals(ImportKind.UNSUPPORTED, Import.kind("a.pdf", "application/pdf"))
        assertEquals(ImportKind.UNSUPPORTED, Import.kind(null, null))
    }

    @Test fun `imports become editor html`() {
        assertEquals("<p>a &amp; b</p>", Import.toHtml(ImportKind.TXT, "a & b".toByteArray()))
        assertEquals("<h1>x</h1>", Import.toHtml(ImportKind.MD, "# x".toByteArray()))
        assertEquals("<p>x</p>", Import.toHtml(ImportKind.HTML, "<html><head><title>t</title></head><body><script>1</script><p>x</p></body></html>".toByteArray()))
        assertEquals("<p>bare</p>", Import.bodyOf(" <p>bare</p> "))
        assertNull(Import.toHtml(ImportKind.UNSUPPORTED, ByteArray(0)))
        assertEquals("<p>One\tA</p><p>Two<br>B &amp; C</p>", Import.toHtml(ImportKind.DOCX, docx()))
    }

    @Test fun `docx without a document part or not a zip reads as nothing`() {
        assertNull(Docx.text("not a zip".toByteArray()))
        assertNull(Docx.text(zip("word/other.xml" to "<x/>")))
        assertNull(Import.toHtml(ImportKind.DOCX, ByteArray(0)))
        assertEquals("", Docx.documentText("<w:document><w:p/></w:document>"))
    }

    private fun docx() = zip("[Content_Types].xml" to "<Types/>", "word/document.xml" to
        "<w:document><w:body><w:p><w:pPr/><w:r><w:t>One</w:t><w:tab/><w:t xml:space=\"preserve\">A</w:t></w:r></w:p>" +
        "<w:p><w:r><w:t>Two</w:t><w:br/><w:t>B &amp; C</w:t></w:r></w:p></w:body></w:document>")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
}

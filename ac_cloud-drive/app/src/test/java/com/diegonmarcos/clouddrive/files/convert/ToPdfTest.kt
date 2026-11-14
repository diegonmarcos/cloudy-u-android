package com.diegonmarcos.clouddrive.files.convert

import com.diegonmarcos.clouddrive.PdfConvert
import com.diegonmarcos.clouddrive.PdfStructure
import com.diegonmarcos.clouddrive.files.ArchiveExtractor
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1740 Convert fixtures: kinds and refusals, image/text/md PDF writing, PDF text to MD/HTML, zip round trip. */
class ToPdfTest {
    @get:Rule val tmp = TemporaryFolder()
    private val noProgress = FileOps.Progress { _, _, _ -> }

    @Test fun kindsAndReasons() {
        assertEquals(ToPdf.Kind.IMAGE, ToPdf.kindOf("a.PNG"))
        assertEquals(ToPdf.Kind.TEXT, ToPdf.kindOf("main.kt"))
        assertEquals(ToPdf.Kind.TEXT, ToPdf.kindOf("n.md"))
        assertEquals(ToPdf.Kind.HTML, ToPdf.kindOf("p.htm"))
        assertEquals(ToPdf.Kind.OFFICE, ToPdf.kindOf("d.docx"))
        assertNull(ToPdf.refusal("a.jpg")); assertNull(ToPdf.refusal("a.html")); assertNull(ToPdf.refusal("a.py"))
        assertTrue(ToPdf.refusal("d.docx")!!.contains("Cloud Office"))
        assertTrue(ToPdf.refusal("x.bin")!!.contains("no PDF form"))
        assertTrue(ToPdf.refusal("x.pdf")!!.contains("already"))
    }

    @Test fun markdownAndCodeBecomeAPdf() {
        for (name in listOf("n.md", "code.kt")) {
            val src = tmp.newFile(name).apply { writeText("# Title\n\nbody text\n- item\n") }
            val out = ConverterRegistry.default.forName(name).first { it.id == "text-pdf" }.convert(src, tmp.root, noProgress) { false }.single()
            val head = out.readBytes().take(8).map { it.toInt().toChar() }.joinToString("")
            assertTrue(head.startsWith("%PDF-1.4")); assertEquals("${src.nameWithoutExtension}.pdf", out.name)
        }
    }

    @Test fun imagePagesWriteAWellFormedPdf() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val bos = ByteArrayOutputStream()
        PdfWriter.writeImages(listOf(PdfWriter.ImagePage(jpeg, 800, 600), PdfWriter.ImagePage(jpeg, 600, 800)), bos)
        val text = String(bos.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4")); assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2")); assertTrue(text.contains("/DCTDecode")); assertTrue(text.contains("/Length ${jpeg.size}"))
        val f = PdfWriter.fit(800, 600) // wide image: landscape A4
        assertEquals(842f, f[0], 0f); assertEquals(595f, f[1], 0f)
        // xref offsets point at "N 0 obj"
        val xref = text.substringAfter("xref\n").lines().drop(2).take(8).filter { it.endsWith(" n ") }
        xref.forEachIndexed { i, l -> assertTrue(text.substring(l.substring(0, 10).toInt()).startsWith("${i + 1} 0 obj")) }
    }

    @Test fun textLayerToMarkdownAndHtmlKeepsStructure() {
        val page = "ANNUAL REPORT\nIntroduction\nThis is a long line of text that wraps across the page and\ncontinues here with more words.\n- first point\n- second point\n1. one\n2. two\n"
        val md = PdfStructure.toMarkdown(listOf(page))
        assertTrue(md, md.contains("# ANNUAL REPORT"))
        assertTrue(md, md.contains("### Introduction"))
        assertTrue(md, md.contains("wraps across the page and continues here with more words."))
        assertTrue(md, md.contains("- first point\n- second point"))
        assertTrue(md, md.contains("1. one\n2. two"))
        val html = PdfStructure.toHtml(listOf(page), "a&b.pdf")
        assertTrue(html.contains("<h1>ANNUAL REPORT</h1>")); assertTrue(html.contains("<ul><li>first point</li><li>second point</li></ul>"))
        assertTrue(html.contains("<title>a&amp;b.pdf</title>")); assertTrue(html.contains("<ol><li>one</li>"))
    }

    @Test fun ocrTextGoesThroughTheSameStructureAndNameRules() {
        // what a scanned page's OCR returns (no blank lines, CRLF) is cleaned then structured like a text layer
        val ocr = PdfConvert.cleanPage("Invoice 2026\\r\nTotal due is payable within thirty days of the date shown.\\r\n")
        assertTrue(PdfStructure.toMarkdown(listOf(ocr)).contains("# Invoice 2026"))
        assertEquals("scan.ocr.txt", PdfConvert.preferredName("scan.pdf", PdfConvert.OCR))
        assertEquals(PdfConvert.TARGETS + "ocr", PdfConvert.DIALOG_TARGETS)
        assertTrue(PdfConvert.hasNoText(listOf("", "  \n")))
    }

    @Test fun zipAndUnzipRoundTripAFolder() {
        val dir = tmp.newFolder("src").also { File(it, "sub").mkdir(); File(it, "a.txt").writeText("alpha"); File(it, "sub/b.txt").writeText("beta") }
        val zip = File(tmp.root, "src.zip")
        FileOps.archive(listOf(dir), zip, noProgress) { false }
        val dest = tmp.newFolder("out")
        ArchiveExtractor.extract(zip, dest, noProgress, { false }, Long.MAX_VALUE)
        assertEquals("alpha", File(dest, "src/a.txt").readText()); assertEquals("beta", File(dest, "src/sub/b.txt").readText())
        val tgz = File(tmp.root, "src.tar.gz"); FileOps.archiveTarGz(listOf(dir), tgz, noProgress) { false }
        val dest2 = tmp.newFolder("out2"); ArchiveExtractor.extract(tgz, dest2, noProgress, { false }, Long.MAX_VALUE)
        assertEquals("beta", File(dest2, "src/sub/b.txt").readText())
        assertNotNull(ArchiveExtractor.kindOf(zip.name))
    }
}

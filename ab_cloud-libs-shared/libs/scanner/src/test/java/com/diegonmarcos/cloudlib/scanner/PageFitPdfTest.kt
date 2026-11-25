package com.diegonmarcos.cloudlib.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** #1839 the page-size fit and the PDF page plan (and the writer it feeds). */
class PageFitPdfTest {
    private val mm = PageFit.MM_PT

    @Test fun a4PortraitIsTheStandardSheet() {
        val p = PageFit.fit(1000, 1414, PageSize.A4, 0.0, Orientation.AUTO)
        assertEquals(210 * mm, p.pageW, 1e-6); assertEquals(297 * mm, p.pageH, 1e-6)
        assertEquals(p.pageW, p.w, 0.5); assertEquals(0.0, p.x, 0.5)
    }

    @Test fun autoOrientationFollowsTheImage() {
        val p = PageFit.fit(2000, 1000, PageSize.A4, 0.0, Orientation.AUTO)
        assertEquals(297 * mm, p.pageW, 1e-6); assertEquals(210 * mm, p.pageH, 1e-6)
    }

    @Test fun forcedOrientationWinsOverTheImage() {
        val p = PageFit.fit(2000, 1000, PageSize.LETTER, 0.0, Orientation.PORTRAIT)
        assertEquals(215.9 * mm, p.pageW, 1e-6); assertEquals(279.4 * mm, p.pageH, 1e-6)
        assertTrue("fitted, not stretched", Math.abs(p.w / p.h - 2.0) < 1e-9)
    }

    @Test fun marginsShrinkTheImageAndCentreIt() {
        val p = PageFit.fit(1000, 1000, PageSize.A4, 10.0, Orientation.PORTRAIT)
        assertEquals(190 * mm, p.w, 1e-6); assertEquals(190 * mm, p.h, 1e-6)
        assertEquals(10 * mm, p.x, 1e-6); assertEquals((297 - 190) / 2.0 * mm, p.y, 1e-6)
    }

    @Test fun legalAndCardsAreTheirDeclaredSizes() {
        assertEquals(355.6 * mm, PageFit.fit(10, 10, PageSize.LEGAL, 0.0, Orientation.PORTRAIT).pageH, 1e-6)
        val id = PageFit.fit(856, 540, PageSize.ID_CARD, 0.0, Orientation.AUTO)
        assertEquals(85.6 * mm, id.pageW, 1e-6); assertEquals(53.98 * mm, id.pageH, 1e-6)
        val bc = PageFit.fit(889, 508, PageSize.BUSINESS_CARD, 0.0, Orientation.AUTO)
        assertEquals(88.9 * mm, bc.pageW, 1e-6)
    }

    @Test fun aReceiptIsAFixedWidthRollAsLongAsTheReceipt() {
        val p = PageFit.fit(400, 1600, PageSize.RECEIPT, 5.0, Orientation.AUTO)
        assertEquals(80 * mm, p.pageW, 1e-6)
        assertEquals(70 * mm * 4, p.h, 1e-6)
        assertEquals(p.h + 10 * mm, p.pageH, 1e-6)
    }

    @Test fun originalSizeIsThePixelsAt150Dpi() {
        val p = PageFit.fit(1500, 750, PageSize.ORIGINAL, 0.0, Orientation.AUTO)
        assertEquals(720.0, p.pageW, 1e-6); assertEquals(360.0, p.pageH, 1e-6)
    }

    @Test fun planMovesOcrWordsIntoPdfSpace() {
        val plan = PdfPlan.build(listOf(PlanPage(1000, 1000, listOf(OcrWord("Hello", 100.0, 200.0, 300.0, 50.0), OcrWord(" ", 0.0, 0.0, 5.0, 5.0)))),
            PageSize.ORIGINAL, 0.0, Orientation.AUTO)
        assertEquals(1, plan.size)
        val pg = plan[0]
        assertEquals("a blank word is dropped", 1, pg.words.size)
        val s = pg.placement.w / 1000
        val w = pg.words[0]
        assertEquals(100 * s, w.x, 1e-6)
        assertEquals(pg.placement.pageH - (240.0 * s), w.y, 1e-6)
        assertEquals(50 * s * 0.8, w.fontSize, 1e-6)
    }

    @Test fun planHasOnePagePerScanWithItsOwnSheet() {
        val plan = PdfPlan.build(listOf(PlanPage(100, 200, emptyList()), PlanPage(300, 200, emptyList())), PageSize.A4, 5.0, Orientation.AUTO)
        assertEquals(2, plan.size)
        assertTrue(plan[0].placement.pageW < plan[0].placement.pageH)
        assertTrue(plan[1].placement.pageW > plan[1].placement.pageH)
    }

    @Test fun theWrittenPdfHasAValidXrefAndASearchableTextLayer() {
        val plan = PdfPlan.build(
            listOf(PlanPage(100, 100, listOf(OcrWord("Total (EUR)", 10.0, 10.0, 60.0, 12.0))), PlanPage(100, 100, emptyList())),
            PageSize.A4, 0.0, Orientation.AUTO,
        )
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val out = ByteArrayOutputStream()
        PdfWriter.write(out, plan.map { PdfPageData(it, jpeg, 100, 100) }, "scan")
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        assertTrue("invisible text mode", text.contains("3 Tr"))
        assertTrue("the word is in the layer, parentheses escaped", text.contains("(Total \\(EUR\\)) Tj"))
        val startxref = text.substringAfterLast("startxref\n").substringBefore("\n").trim().toInt()
        assertTrue(text.substring(startxref).startsWith("xref"))
        val count = Regex("0 (\\d+)\n0000000000 65535 f").find(text.substring(startxref))!!.groupValues[1].toInt()
        val entries = Regex("(\\d{10}) 00000 n").findAll(text.substring(startxref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(count - 1, entries.size)
        entries.forEachIndexed { i, off -> assertTrue("object ${i + 1} sits at its offset", text.substring(off).startsWith("${i + 1} 0 obj")) }
        assertEquals("one image per page", 2, Regex("/Subtype /Image").findAll(text).count())
    }

    @Test fun escapeKeepsLatinAndReplacesTheRest() {
        assertEquals("a\\\\b\\(c\\) café ?", PdfWriter.escape("a\\b(c)\n" + "café 中"))
    }
}

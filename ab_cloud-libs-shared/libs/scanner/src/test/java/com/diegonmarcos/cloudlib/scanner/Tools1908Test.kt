package com.diegonmarcos.cloudlib.scanner

import com.diegonmarcos.cloudlib.pdf.PdfEditSession
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1908 the new engines: crop, page operations, filters, naming, image metadata, target size and the PDF tools (round trips on generated PDFs). */
class Tools1908Test {
    // ── crop ─────────────────────────────────────────────────────────────────────────────

    @Test fun cropPresetsKeepTheirRatioInsideThePhoto() {
        for (p in CropPreset.values()) {
            val r = CropMath.ratioOf(p, 2000, 1500)
            val q = CropMath.centred(2000, 1500, r)
            val xs = q.points.map { it.x }; val ys = q.points.map { it.y }
            val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
            assertTrue("${p.id} inside", xs.min() >= 0 && ys.min() >= 0 && xs.max() <= 1999 && ys.max() <= 1499)
            if (r != null) assertEquals("${p.id} ratio", r, w / h, 1e-6)
            assertTrue(q.isConvex())
        }
    }

    @Test fun originalRatioIsThePhotosAndFreeHasNone() {
        assertEquals(2000.0 / 1500, CropMath.ratioOf(CropPreset.ORIGINAL, 2000, 1500)!!, 1e-9)
        assertNull(CropMath.ratioOf(CropPreset.FREE, 2000, 1500))
    }

    @Test fun reshapeKeepsTheCentreAndStaysInside() {
        val q = Quad(P(400.0, 300.0), P(1200.0, 300.0), P(1200.0, 900.0), P(400.0, 900.0))
        val r = CropMath.reshaped(q, 2000, 1500, 1.0)
        val xs = r.points.map { it.x }; val ys = r.points.map { it.y }
        assertEquals((xs.max() - xs.min()), (ys.max() - ys.min()), 1e-6)
        assertEquals(800.0, (xs.min() + xs.max()) / 2, 1e-6); assertEquals(600.0, (ys.min() + ys.max()) / 2, 1e-6)
        val edge = Quad(P(0.0, 0.0), P(1999.0, 0.0), P(1999.0, 1499.0), P(0.0, 1499.0))
        val s = CropMath.reshaped(edge, 2000, 1500, 0.4)
        assertTrue(s.points.all { it.x in 0.0..1999.0 && it.y in 0.0..1499.0 })
        assertEquals(edge, CropMath.reshaped(edge, 2000, 1500, null))
    }

    // ── page operations ──────────────────────────────────────────────────────────────────

    @Test fun pageOperations() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PageOps.move(l, 0, 2))
        assertEquals(l, PageOps.move(l, 9, 1))
        assertEquals(listOf("a", "c", "d"), PageOps.delete(l, 1))
        assertEquals(l, PageOps.delete(l, -1))
        assertEquals(listOf("a", "b", "b2", "c", "d"), PageOps.duplicate(l, 1) { it + "2" })
        assertEquals(listOf("x", "a", "b", "c", "d"), PageOps.insert(l, 0, "x"))
        assertEquals(listOf("a", "b", "c", "d", "x"), PageOps.insert(l, 99, "x"))
        assertEquals(listOf("a", "Z", "c", "d"), PageOps.replace(l, 1, "Z"))
        assertEquals(listOf("a", "b") to listOf("c", "d"), PageOps.split(l, 2))
        assertEquals(emptyList<String>() to l, PageOps.split(l, 0))
        assertEquals(l + l, PageOps.merge(l, l))
        assertEquals("the source list is never changed", listOf("a", "b", "c", "d"), l)
    }

    // ── filters ──────────────────────────────────────────────────────────────────────────

    private fun page(): Raster {
        val w = 90; val h = 60
        return Raster(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; if (y in 25..28 && x in 10..80) Raster.rgb(200, 40, 40) else Raster.rgb(215, 210, 190) })
    }

    @Test fun magicColourWhitensPaperAndKeepsInkMoreVividThanTheColourDocument() {
        val magic = DocFilter.apply(page(), FilterSpec(FilterMode.MAGIC))
        val doc = DocFilter.apply(page(), FilterSpec(FilterMode.COLOR_DOC))
        val paper = magic[5, 5]
        assertTrue(((paper shr 16) and 255) >= 240 && ((paper shr 8) and 255) >= 240)
        fun chroma(p: Int) = ((p shr 16) and 255) - (p and 255)
        assertTrue("ink is livelier: ${chroma(magic[40, 26])} vs ${chroma(doc[40, 26])}", chroma(magic[40, 26]) >= chroma(doc[40, 26]))
    }

    @Test fun sharpnessRaisesEdgeContrastAndZeroLeavesThePageAlone() {
        val src = page()
        val same = DocFilter.apply(src, FilterSpec(FilterMode.ORIGINAL, sharpness = 0))
        assertArrayEquals(src.px, same.px)
        val sharp = DocFilter.apply(src, FilterSpec(FilterMode.ORIGINAL, sharpness = 100))
        fun lum(r: Raster, x: Int, y: Int) = r.luma(y * r.w + x)
        val before = lum(src, 40, 29) - lum(src, 40, 27)
        val after = lum(sharp, 40, 29) - lum(sharp, 40, 27)
        assertTrue("edge step grew: $before -> $after", after > before)
        assertEquals("flat paper is unchanged", lum(src, 5, 5), lum(sharp, 5, 5))
    }

    // ── naming ───────────────────────────────────────────────────────────────────────────

    @Test fun namingTemplateTokens() {
        val now = LocalDateTime.of(2026, 11, 25, 13, 45, 2)
        assertEquals("Invoice_2026-11-25", NameTemplate.render("{title}_{date}", "Invoice", "pdf", 1, now))
        assertEquals("scan_134502_007.pdf", NameTemplate.render("scan_{time}_{n}.{type}", "x", "pdf", 7, now))
        assertEquals("a_b_2026-11-25", NameTemplate.render("a/b:{date}", "t", "pdf", 1, now))
        assertEquals("scan_2026-11-25", NameTemplate.render("", "", "pdf", 1, now))
        assertEquals("keeps_{unknown}".replace("{", "{"), NameTemplate.render("keeps_{unknown}", "t", "pdf", 1, now))
    }

    @Test fun exportNameFollowsTheTemplateOrTheTypedName() {
        val now = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("my_doc", ScannerExport.baseName(ExportOptions(name = "my doc"), 1, now))
        assertEquals("my_doc-2026-01-02-002", ScannerExport.baseName(ExportOptions(name = "my doc", template = "{title}-{date}-{n}"), 2, now))
    }

    // ── image metadata ───────────────────────────────────────────────────────────────────

    private fun fakeJpeg(withJfif: Boolean): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val jfif = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16, 74, 70, 73, 70, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val tail = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2, 0xFF.toByte(), 0xD9.toByte())
        return if (withJfif) head + jfif + tail else head + tail
    }

    @Test fun jpegDpiIsWrittenIntoAnExistingOrANewJfifHeader() {
        for (has in booleanArrayOf(true, false)) {
            val out = ImageMeta.jpegWithDpi(fakeJpeg(has), 300)
            assertEquals(300, ImageMeta.jpegDpi(out))
            assertEquals(0xFF, out[0].toInt() and 255); assertEquals(0xD8, out[1].toInt() and 255)
            assertEquals("the rest of the file is intact", 0xD9, out[out.size - 1].toInt() and 255)
        }
        assertEquals(600, ImageMeta.jpegDpi(ImageMeta.jpegWithDpi(ImageMeta.jpegWithDpi(fakeJpeg(false), 300), 600)))
    }

    private fun fakePng(): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray(Charsets.ISO_8859_1) + data
            val crc = java.util.zip.CRC32().also { it.update(body) }.value
            val len = byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte())
            return len + body + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        }
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)
        return byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", byteArrayOf(1, 2, 3)) + chunk("IEND", ByteArray(0))
    }

    @Test fun pngDpiIsAPhysChunkAfterIhdrWithAValidCrc() {
        val out = ImageMeta.pngWithDpi(fakePng(), 300)
        assertEquals(300, ImageMeta.pngDpi(out))
        assertEquals("replacing, not stacking", 150, ImageMeta.pngDpi(ImageMeta.pngWithDpi(out, 150)))
        assertEquals(fakePng().size + 21, out.size)
        val i = out.indexOfFirst { it.toInt() == 'p'.code } // "pHYs" type starts at 12 + 8 + 13 + 4 + 4
        assertTrue(i > 0)
        val body = out.copyOfRange(37, 37 + 4 + 9)
        val crc = java.util.zip.CRC32().also { it.update(body) }.value
        val stored = ((out[50].toLong() and 255) shl 24) or ((out[51].toLong() and 255) shl 16) or ((out[52].toLong() and 255) shl 8) or (out[53].toLong() and 255)
        assertEquals(crc, stored)
    }

    // ── target size ──────────────────────────────────────────────────────────────────────

    @Test fun targetSizeFindsTheHighestQualityThatFits() {
        val encode = { q: Int -> ByteArray(q * 100) }
        val r = TargetSize.search(5000, encode = encode)
        assertTrue(r.fits); assertEquals(50, r.quality); assertEquals(5000, r.bytes.size)
        val big = TargetSize.search(100, encode = encode)
        assertFalse(big.fits); assertEquals(5, big.quality)
        assertEquals(100, TargetSize.search(1_000_000, encode = encode).quality)
    }

    // ── PDF tools ────────────────────────────────────────────────────────────────────────

    private fun widths(bytes: ByteArray) = PdfEditSession.open(bytes).use { s -> (0 until s.pageCount).map { s.pageSize(it).width } }
    private val a = PdfEditSession.blank(listOf(200f to 300f, 210f to 300f))
    private val b = PdfEditSession.blank(listOf(300f to 300f, 310f to 300f, 320f to 300f))

    @Test fun mergeJoinsInOrder() {
        assertEquals(listOf(200f, 210f, 300f, 310f, 320f), widths(PdfTools.merge(listOf(a, b))))
        assertEquals(5, PdfTools.pageCount(PdfTools.merge(listOf(a, b))))
    }

    @Test fun extractKeepsTheChosenPagesInTheGivenOrder() {
        val m = PdfTools.merge(listOf(a, b))
        assertEquals(listOf(320f, 200f), widths(PdfTools.extract(m, listOf(4, 0))))
        assertTrue(runCatching { PdfTools.extract(m, listOf(9)) }.isFailure)
        assertTrue(runCatching { PdfTools.extract(m, emptyList()) }.isFailure)
    }

    @Test fun splitCutsRangesAndEveryPage() {
        val m = PdfTools.merge(listOf(a, b))
        val parts = PdfTools.split(m, listOf(0..1, 2..4))
        assertEquals(listOf(listOf(200f, 210f), listOf(300f, 310f, 320f)), parts.map { widths(it) })
        assertEquals(5, PdfTools.splitEveryPage(m).size)
    }

    @Test fun rangesParse() {
        assertEquals(listOf(0..2, 4..4, 6..8), PdfTools.parseRanges("1-3, 5, 7-9", 9))
        assertNull(PdfTools.parseRanges("0-2", 9)); assertNull(PdfTools.parseRanges("3-2", 9)); assertNull(PdfTools.parseRanges("1-10", 9))
        assertNull(PdfTools.parseRanges("a", 9)); assertNull(PdfTools.parseRanges("", 9))
    }

    @Test fun rotateReorderDelete() {
        val m = PdfTools.merge(listOf(a, b))
        val r = PdfTools.rotate(m, listOf(1), 90)
        PdfEditSession.open(r).use { s -> assertEquals(0, s.pageSize(0).rotation); assertEquals(90, s.pageSize(1).rotation) }
        PdfEditSession.open(PdfTools.rotate(m, emptyList(), 180)).use { s -> assertTrue((0 until s.pageCount).all { s.pageSize(it).rotation == 180 }) }
        assertEquals(listOf(320f, 310f, 300f, 210f, 200f), widths(PdfTools.reorder(m, listOf(4, 3, 2, 1, 0))))
        assertEquals(listOf(200f, 300f, 320f), widths(PdfTools.delete(m, listOf(1, 3))))
    }

    @Test fun protectLocksAndTheRightPasswordOpens() {
        val locked = PdfTools.protect(a, "s3cret")
        assertTrue(PdfTools.isEncrypted(locked))
        assertTrue(runCatching { PdfTools.pageCount(locked, "wrong") }.isFailure)
        assertEquals(2, PdfTools.pageCount(locked, "s3cret"))
        val open = PdfTools.unprotect(locked, "s3cret")
        assertFalse(PdfTools.isEncrypted(open))
        assertEquals(listOf(200f, 210f), widths(open))
        assertNotEquals(a.size, locked.size)
    }

    // ── PDF plan: the searchable text layer (#1839 kept) with a converter's plain page ───

    @Test fun aPdfWithoutOcrWordsHasNoTextLayer() {
        val plan = PdfPlan.build(listOf(PlanPage(100, 100, emptyList())), PageSize.A4, 0.0, Orientation.AUTO)
        assertEquals(emptyList<PlannedWord>(), plan[0].words)
    }
}

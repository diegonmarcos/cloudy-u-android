package com.diegonmarcos.cloudlib.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1839 the filter pipeline and the edge detector, on synthetic pages. */
class DocFilterTest {
    /** A page: paper [paper] with a horizontal shadow gradient, dark ink bars. */
    private fun page(paper: Int = 200, shadow: Boolean = false): Raster {
        val w = 160; val h = 120
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val level = if (shadow) paper - x / 4 else paper
            val ink = (y in 40..46 && x in 20..140) || (y in 70..76 && x in 20..110)
            if (ink) Raster.rgb(30, 30, 60) else Raster.rgb(level, level, level - 10)
        }
        return Raster(w, h, px)
    }

    @Test fun blackAndWhiteIsStrictlyTwoLevelsAndKeepsTheInk() {
        val out = DocFilter.apply(page(shadow = true), FilterSpec(FilterMode.BW))
        assertTrue(out.px.all { it == DocFilter.BLACK || it == Raster.WHITE })
        assertEquals(DocFilter.BLACK, out[60, 43])
        assertEquals(Raster.WHITE, out[10, 10])
        assertEquals("a shadow must not turn paper black", Raster.WHITE, out[150, 100])
    }

    @Test fun grayscaleHasEqualChannels() {
        val out = DocFilter.apply(page(), FilterSpec(FilterMode.GRAYSCALE))
        assertTrue(out.px.all { ((it shr 16) and 255) == ((it shr 8) and 255) && ((it shr 8) and 255) == (it and 255) })
    }

    @Test fun colourDocumentWhitensPaperAcrossAShadowAndKeepsInkColour() {
        val out = DocFilter.apply(page(shadow = true), FilterSpec(FilterMode.COLOR_DOC))
        for (x in intArrayOf(5, 80, 155)) {
            val p = out[x, 100]
            assertTrue("paper at x=$x is white, was ${Integer.toHexString(p)}", ((p shr 16) and 255) >= 245 && ((p shr 8) and 255) >= 245)
        }
        val ink = out[60, 43]
        assertTrue("ink stays dark", ((ink shr 16) and 255) < 100)
        assertTrue("ink keeps its blue cast", (ink and 255) > ((ink shr 16) and 255))
    }

    @Test fun originalWithNoAdjustmentIsTheSameRaster() {
        val src = page()
        assertTrue(DocFilter.apply(src, FilterSpec(FilterMode.ORIGINAL)) === src)
    }

    @Test fun brightnessAndContrastMoveTheLevels() {
        val src = Raster(2, 1, intArrayOf(Raster.rgb(100, 100, 100), Raster.rgb(200, 200, 200)))
        val bright = DocFilter.adjust(src, 20, 0, gray = false)
        assertEquals(125, bright[0, 0] and 255)
        val flat = DocFilter.adjust(src, 0, -100, gray = false)
        assertEquals(128, flat[0, 0] and 255); assertEquals(128, flat[1, 0] and 255)
        val hard = DocFilter.adjust(src, 0, 100, gray = false)
        assertTrue((hard[0, 0] and 255) < 100 && (hard[1, 0] and 255) > 200)
    }

    private fun skewedSheet(): Raster {
        val img = Raster(220, 180, IntArray(220 * 180) { Raster.rgb(40, 40, 40) })
        for (y in 30..150) for (x in 40..180) img.px[y * 220 + x] = Raster.WHITE
        return img
    }

    @Test fun edgesOfALightSheetOnADarkTableAreItsCorners() {
        val q = EdgeDetector.findQuad(skewedSheet())
        assertEquals(40.0, q.tl.x, 4.0); assertEquals(30.0, q.tl.y, 4.0)
        assertEquals(180.0, q.br.x, 4.0); assertEquals(150.0, q.br.y, 4.0)
        assertTrue(q.isConvex())
    }

    @Test fun edgesOfADarkCardOnALightDeskAreFoundToo() {
        val img = Raster(220, 180, IntArray(220 * 180) { Raster.rgb(230, 230, 230) })
        for (y in 50..130) for (x in 60..160) img.px[y * 220 + x] = Raster.rgb(20, 20, 20)
        val q = EdgeDetector.findQuad(img)
        assertEquals(60.0, q.tl.x, 4.0); assertEquals(130.0, q.br.y, 4.0)
    }

    @Test fun anEmptyFrameFallsBackToTheWholePage() {
        val q = EdgeDetector.findQuad(Raster(200, 100, IntArray(20000) { Raster.rgb(128, 128, 128) }))
        assertEquals(Quad.full(200, 100, 0.03), q)
    }

    @Test fun deskewFindsTheTiltOfTextLinesAndTheCorrectionLevelsThem() {
        val flat = Raster(240, 240, IntArray(240 * 240) { Raster.WHITE })
        for (line in 0 until 6) for (y in 0..3) for (x in 20..220) flat.px[(40 + line * 28 + y) * 240 + x] = Raster.rgb(20, 20, 20)
        val tilted = flat.rotate(7.0)
        val fix = EdgeDetector.deskewDegrees(tilted)
        assertEquals(-7.0, fix, 1.0)
        assertEquals(0.0, EdgeDetector.deskewDegrees(tilted.rotate(fix)), 1.0)
        assertEquals(0.0, EdgeDetector.deskewDegrees(flat), 0.6)
    }
}

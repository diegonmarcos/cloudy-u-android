package com.diegonmarcos.cloudlib.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfGeometryTest {
    private val page = PdfPageSize(200f, 300f)

    private fun at(u: Float, v: Float, rotation: Int, ox: Float = 0f, oy: Float = 0f) = PdfGeometry.toPdf(u, v, page.copy(rotation = rotation, originX = ox, originY = oy))

    @Test fun `an upright page flips the vertical axis`() {
        assertEquals(PdfPoint(0f, 300f), at(0f, 0f, 0))
        assertEquals(PdfPoint(200f, 0f), at(1f, 1f, 0))
        assertEquals(PdfPoint(100f, 150f), at(0.5f, 0.5f, 0))
    }

    @Test fun `a quarter turn clockwise maps the corners`() {
        assertEquals(PdfPoint(0f, 0f), at(0f, 0f, 90))
        assertEquals(PdfPoint(0f, 300f), at(1f, 0f, 90))
        assertEquals(PdfPoint(200f, 0f), at(0f, 1f, 90))
    }

    @Test fun `half and three quarter turns map the corners`() {
        assertEquals(PdfPoint(200f, 0f), at(0f, 0f, 180))
        assertEquals(PdfPoint(200f, 300f), at(0f, 0f, 270))
        assertEquals(PdfPoint(0f, 0f), at(1f, 0f, 270))
    }

    @Test fun `the media box origin is added and touches outside the page are clamped`() {
        assertEquals(PdfPoint(10f, 330f), at(0f, 0f, 0, 10f, 30f))
        assertEquals(PdfPoint(200f, 0f), at(5f, 5f, 0))
        assertEquals(PdfPoint(0f, 300f), at(-1f, -1f, 0))
    }

    @Test fun `display size swaps for a quarter turn`() {
        assertEquals(200f to 300f, PdfGeometry.displaySize(page))
        assertEquals(300f to 200f, PdfGeometry.displaySize(page.copy(rotation = 90)))
        assertEquals(300f to 200f, PdfGeometry.displaySize(page.copy(rotation = 270)))
        assertEquals(200f to 300f, PdfGeometry.displaySize(page.copy(rotation = 180)))
    }

    @Test fun `moving a page gives the new order`() {
        assertEquals(listOf(1, 2, 0), PdfGeometry.moved(3, 0, 2))
        assertEquals(listOf(2, 0, 1), PdfGeometry.moved(3, 2, 0))
        assertEquals(listOf(0, 1, 2), PdfGeometry.moved(3, 5, 0))
    }
}

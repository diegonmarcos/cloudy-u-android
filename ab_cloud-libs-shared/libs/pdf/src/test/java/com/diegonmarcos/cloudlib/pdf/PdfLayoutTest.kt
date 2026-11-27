package com.diegonmarcos.cloudlib.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's page layout (#577) is arithmetic, so it is executed here. The numbers are picked to
 * be exact in floating point: a 1000 x 2000 view and a 500 x 1000 pt page scale by exactly 2.
 */
class PdfLayoutTest {

    private val delta = 0.001f

    private fun continuous(pages: Int) = PdfLayout(pages, 1000, 2000, false, 10f, 500f, 1000f)

    @Test
    fun continuousModeFitsWidthAndStacksSlots() {
        val layout = continuous(3)
        assertEquals(1000f, layout.pageWidth(0), delta)
        assertEquals(2000f, layout.pageHeight(0), delta)
        assertEquals(0f, layout.pageLeft(0), delta)
        // A slot is the page plus the gap, and the page sits in the middle of it.
        assertEquals(2010f, layout.slotHeight(0), delta)
        assertEquals(5f, layout.pageTop(0), delta)
        assertEquals(2010f, layout.slotTop(1), delta)
        assertEquals(4020f, layout.slotTop(2), delta)
        assertEquals(6030f, layout.totalHeight, delta)
    }

    @Test
    fun pageAtFindsTheSlotUnderAScrollPosition() {
        val layout = continuous(3)
        assertEquals(0, layout.pageAt(0f))
        assertEquals(0, layout.pageAt(2009.9f))
        assertEquals(1, layout.pageAt(2010f))
        assertEquals(2, layout.pageAt(6029f))
        // Past either end there is still a real page under the finger.
        assertEquals(0, layout.pageAt(-5f))
        assertEquals(2, layout.pageAt(1e9f))
    }

    @Test
    fun aRealSizeMovesEveryLaterPage() {
        val layout = continuous(3)
        assertTrue(layout.setSize(1, 500f, 500f))
        layout.rebuild()
        assertEquals(1000f, layout.pageHeight(1), delta)
        assertEquals(2010f, layout.slotTop(1), delta)
        assertEquals(3020f, layout.slotTop(2), delta)
        assertEquals(5030f, layout.totalHeight, delta)
        // The same size again changes nothing, so the view does not re-anchor for it.
        assertFalse(layout.setSize(1, 500f, 500.2f))
        assertFalse(layout.setSize(7, 500f, 500f))
    }

    @Test
    fun pagedModeGivesEachPageAWholeScreen() {
        val layout = PdfLayout(3, 1000, 2000, true, 10f, 500f, 500f)
        // 500 x 500 pt in a 1000 x 2000 view: width is the limit, scale 2, centred vertically.
        assertEquals(1000f, layout.pageWidth(0), delta)
        assertEquals(1000f, layout.pageHeight(0), delta)
        assertEquals(2000f, layout.slotHeight(0), delta)
        assertEquals(500f, layout.pageTop(0), delta)
        assertEquals(2000f, layout.slotTop(1), delta)
        assertEquals(6000f, layout.totalHeight, delta)
    }

    @Test
    fun pagedModeFitsATallPageWhole() {
        val layout = PdfLayout(1, 1000, 2000, true, 10f, 500f, 2000f)
        // Height is the limit: scale 1, so the page is 500 wide and centred in the 1000 view.
        assertEquals(500f, layout.pageWidth(0), delta)
        assertEquals(2000f, layout.pageHeight(0), delta)
        assertEquals(250f, layout.pageLeft(0), delta)
        assertEquals(0f, layout.pageTop(0), delta)
    }

    @Test
    fun aModeChangeKeepsEverySizeAlreadyLearned() {
        val before = continuous(3)
        // 250 x 400 pt, against a 500 x 1000 guess: losing the learned width OR height changes the answer.
        before.setSize(1, 250f, 400f)
        before.rebuild()
        val after = PdfLayout(3, 1000, 2000, true, 10f, 500f, 1000f)
        after.copySizesFrom(before)
        // Scale min(1000/250, 2000/400) = 4 -> 1000 x 1600 px. The guess alone would give 1000 x 2000;
        // the guessed width with the learned height would give 1000 x 800.
        assertEquals(1000f, after.pageWidth(1), delta)
        assertEquals(1600f, after.pageHeight(1), delta)
        assertEquals(0f, after.pageLeft(1), delta)
    }

    @Test
    fun aDegenerateSizeNeverBreaksTheMaths() {
        val layout = PdfLayout(2, 1000, 2000, false, 10f, 0f, 0f)
        assertTrue(layout.pageHeight(0).isFinite())
        assertTrue(layout.totalHeight.isFinite())
        assertEquals(0, PdfLayout(0, 100, 100, false, 0f, 1f, 1f).pageAt(5f))
    }
}

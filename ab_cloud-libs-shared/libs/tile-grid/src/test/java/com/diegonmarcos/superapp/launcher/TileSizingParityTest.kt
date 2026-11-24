package com.diegonmarcos.superapp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1893 parity: the sizing arithmetic and the row split Cloud SuperApp's Cloud page grid used BEFORE it moved into libs:tile-grid
 * (settings/GridColumns and launcher/IndexTiles), pinned as reference values: the page draws the same tiles at the same sizes.
 */
class TileSizingParityTest {
    @Test fun `cell width is the available width over the columns`() {
        assertEquals(90, TileSizing.cellPx(360, 4))
        assertEquals(45, TileSizing.cellPx(360, 8))
        assertEquals(360, TileSizing.cellPx(360, 0))
    }

    @Test fun `icon edge is the wanted size capped by the cell and floored`() {
        assertEquals(84, TileSizing.iconPx(84, 150, 20, 40))
        assertEquals(70, TileSizing.iconPx(84, 90, 20, 40))
        assertEquals(40, TileSizing.iconPx(84, 50, 20, 40))
    }

    @Test fun `label size and padding step at seven columns`() {
        assertEquals(11f, TileSizing.labelSp(4), 0f)
        assertEquals(11f, TileSizing.labelSp(6), 0f)
        assertEquals(10f, TileSizing.labelSp(7), 0f)
        assertEquals(8, TileSizing.cellPadDp(6))
        assertEquals(4, TileSizing.cellPadDp(7))
    }

    @Test fun `rows are full then the remainder and a card is 96 dp with two label lines`() {
        assertEquals(listOf(6, 6, 4), IndexTiles.rowSizes(16, 6))
        assertEquals(listOf(3), IndexTiles.rowSizes(3, 7))
        assertEquals(emptyList<Int>(), IndexTiles.rowSizes(0, 4))
        assertEquals(96, IndexTiles.TILE_HEIGHT_DP)
        assertEquals(2, IndexTiles.LABEL_MAX_LINES)
    }
}

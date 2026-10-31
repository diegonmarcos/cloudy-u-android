// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.toolbarKeysOnOwnPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toolbar pages by a whole screen of icons and the page size is measured, never declared. This is
 * the arithmetic of that row (it used to be a HorizontalScrollView's), on the real default row.
 */
class ToolbarPagingTest {
    private val keyDp = 36 // config_suggestions_strip_edge_key_width

    /** The default first row, in order: the enabled entries of the stored default. */
    private fun defaultRow(): List<ToolbarKey> = defaultToolbarPref.split(Separators.ENTRY).mapNotNull {
        val (name, enabled) = it.split(Separators.KV)
        if (enabled == "true") ToolbarKey.valueOf(name) else null
    }

    private val scales = listOf(0.85f, 1.0f, 1.15f, 1.3f)
    private val viewports = listOf(1080, 1176, 1200, 1440, 960) // px: A37 at 450 dpi, a wider phone, a tablet column, a narrow one

    @Test fun iconsPerPageIsTheViewportDividedByTheCell() {
        assertEquals(11, ToolbarPaging.iconsPerPage(411, keyDp))
        assertEquals(10, ToolbarPaging.iconsPerPage(380, keyDp))
        assertEquals(1, ToolbarPaging.iconsPerPage(20, keyDp)) // a cell wider than the viewport still pages one at a time
        assertEquals(1, ToolbarPaging.iconsPerPage(411, 0)) // nothing measured yet
        assertEquals(1, ToolbarPaging.iconsPerPage(0, 0))
    }

    @Test fun theCellFollowsTheScale() {
        assertEquals(31, ToolbarPaging.cell(36, 0.85f))
        assertEquals(36, ToolbarPaging.cell(36, 1.0f))
        assertEquals(41, ToolbarPaging.cell(36, 1.15f))
        assertEquals(47, ToolbarPaging.cell(36, 1.3f))
        assertEquals(47, ToolbarPaging.cell(36, 2.0f)) // clamped to the owner's range
        assertEquals(31, ToolbarPaging.cell(36, 0.1f))
    }

    @Test fun everyScaleMakesEveryPageExactlyOneScreen() {
        for (scale in scales) for (viewport in viewports) {
            val base = 36 * 3 // 36 dp at 3 px/dp
            val layout = ToolbarPaging.layout(defaultRow(), toolbarKeysOnOwnPage, viewport, base, scale)
            val cell = ToolbarPaging.cell(base, scale)
            assertEquals("icons per page at scale $scale, $viewport px", viewport / cell, layout.perPage)
            assertEquals("a page is the viewport at scale $scale", viewport, layout.page)
            // the row ends on a page boundary: scrolling by whole pages shows nothing half drawn
            val end = layout.keyStart.last() + layout.keyWidth.last() + layout.trailing
            assertEquals("the row is whole pages at scale $scale, $viewport px", 0, end % viewport)
            // every key sits inside ONE page, and its width is the page split into perPage whole pixels
            layout.keyStart.indices.forEach { i ->
                val first = layout.keyStart[i] / viewport
                val last = (layout.keyStart[i] + layout.keyWidth[i] - 1) / viewport
                assertEquals("key $i straddles a page at scale $scale, $viewport px", first, last)
                assertTrue(layout.keyWidth[i] >= viewport / layout.perPage)
                assertTrue(layout.keyWidth[i] <= viewport / layout.perPage + 1)
            }
            // no key is drawn over another
            layout.keyStart.indices.drop(1).forEach { i ->
                assertEquals(layout.keyStart[i - 1] + layout.keyWidth[i - 1] + layout.gapBefore[i], layout.keyStart[i])
            }
        }
    }

    /** The own-page mechanism is kept for the next destination key; Vault used it until #1103 put it in the flow of the row. */
    private val vaultOwnPage = setOf(ToolbarKey.VAULT)

    @Test fun anOwnPageKeyStartsAPageAtEveryScale() {
        for (scale in scales) for (viewport in viewports) {
            val keys = defaultRow()
            val layout = ToolbarPaging.layout(keys, vaultOwnPage, viewport, 36 * 3, scale)
            val v = keys.indexOf(ToolbarKey.VAULT)
            assertEquals("the own-page key starts a page at scale $scale, $viewport px", 0, layout.keyStart[v] % viewport)
            assertTrue("and is alone on it", layout.keyStart[v - 1] / viewport < layout.keyStart[v] / viewport)
        }
    }

    @Test fun vaultFollowsAndroidSettingsInTheFlowOfTheRow() {
        val keys = defaultRow()
        val a = keys.indexOf(ToolbarKey.ANDROID_SETTINGS)
        assertEquals(a + 1, keys.indexOf(ToolbarKey.VAULT))
        assertTrue(toolbarKeysOnOwnPage.isEmpty())
        val layout = ToolbarPaging.layout(keys, toolbarKeysOnOwnPage, 1188, 108, 1.0f)
        // eleven to a page: the first page ends with the Android settings key and Vault opens the second
        assertEquals(0, layout.keyStart[a] / layout.page)
        assertEquals(1, layout.keyStart[a + 1] / layout.page)
    }

    @Test fun theDefaultRowFillsWholePagesAtTheDeclaredScale() {
        val keys = defaultRow()
        val layout = ToolbarPaging.layout(keys, toolbarKeysOnOwnPage, 1188, 108, 1.0f)
        assertEquals(11, layout.perPage) // 1188 / 108
        val perPage = sortedMapOf<Int, MutableList<ToolbarKey>>()
        keys.forEachIndexed { i, key -> perPage.getOrPut(layout.keyStart[i] / layout.page) { mutableListOf() }.add(key) }
        assertEquals(listOf(11, 11, 2), perPage.values.map { it.size }) // #1384: 24 keys (Draft Answer); #1103: 11 / 11 / 1
        assertEquals(ToolbarKey.ANDROID_SETTINGS, perPage.values.first().last())
        assertEquals(ToolbarKey.VAULT, perPage.values.drop(1).first().first())
        // #1384: Draft Answer on page one where Emoji was; Emoji on page two, right after Vault
        assertTrue(ToolbarKey.ANSWER in perPage.values.first())
        assertEquals(ToolbarKey.EMOJI, perPage.values.drop(1).first()[1])
        assertEquals(3, layout.pages)
    }

    @Test fun onlyAKeyThatWantsItsOwnPageGetsAGap() {
        val keys = listOf(ToolbarKey.UNDO, ToolbarKey.REDO, ToolbarKey.VAULT)
        val gaps = ToolbarPaging.gaps(keys, vaultOwnPage, 4)
        assertArrayEquals(intArrayOf(0, 0, 2), gaps) // the 2 cells that remain of the first page
    }

    @Test fun aKeyAlreadyOnAPageBoundaryNeedsNoGap() {
        val keys = listOf(ToolbarKey.UNDO, ToolbarKey.REDO, ToolbarKey.COPY, ToolbarKey.CUT, ToolbarKey.VAULT)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), ToolbarPaging.gaps(keys, vaultOwnPage, 4))
    }

    @Test fun theLayoutDoesNotMoveWhenTheRowIsLaidOutAgain() {
        val a = ToolbarPaging.layout(defaultRow(), toolbarKeysOnOwnPage, 1080, 108, 1.15f)
        val b = ToolbarPaging.layout(defaultRow(), toolbarKeysOnOwnPage, 1080, 108, 1.15f)
        assertArrayEquals(a.keyStart, b.keyStart)
        assertArrayEquals(a.keyWidth, b.keyWidth)
    }

    @Test fun slotsOfAPageAddUpToThePageWithoutADrift() {
        for (perPage in 1..14) for (page in listOf(1000, 1080, 1177)) {
            val widths = (0 until perPage).map { ToolbarPaging.slotStart(it + 1, perPage, page) - ToolbarPaging.slotStart(it, perPage, page) }
            assertEquals(page, widths.sum())
            assertEquals(page * 3, ToolbarPaging.slotStart(perPage * 3, perPage, page))
        }
    }

    @Test fun aFlingTurnsOnePageAndLandsFlushAtTheEnd() {
        val page = 396 // 11 icons
        val limit = 700
        assertEquals(396, ToolbarPaging.turnPageTarget(0, 1, page, limit))
        assertEquals(limit, ToolbarPaging.turnPageTarget(396, 1, page, limit)) // the short last page: clamped to the end
        assertEquals(0, ToolbarPaging.turnPageTarget(396, -1, page, limit))
        assertEquals(396, ToolbarPaging.turnPageTarget(700, -1, page, limit)) // back from the end to the page before it
        assertEquals(0, ToolbarPaging.turnPageTarget(0, -1, page, limit)) // nowhere to go
    }

    @Test fun aSlowDragSettlesOnTheNearestPage() {
        val page = 396
        val limit = 700
        assertEquals(0, ToolbarPaging.nearestPageTarget(150, page, limit))
        assertEquals(396, ToolbarPaging.nearestPageTarget(250, page, limit))
        assertEquals(limit, ToolbarPaging.nearestPageTarget(690, page, limit))
    }

    @Test fun whenTheIconsFitPagingIsInert() {
        assertEquals(0, ToolbarPaging.turnPageTarget(0, 1, 396, 0))
        assertEquals(0, ToolbarPaging.nearestPageTarget(0, 396, 0))
        assertEquals(0, ToolbarPaging.gapSlotsForOwnPage(0, 11))
    }
}

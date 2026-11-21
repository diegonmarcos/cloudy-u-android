package com.diegonmarcos.cloudbrowser.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1842 Weby's Bookmarks (all) view: the browser's bookmarks, every Quickmark, the catalogue. */
class WebyBookmarksTest {
    private val quick = listOf(WebyFolder("Primary", listOf(WebyTile("p1", "One", "https://one")), listOf(WebyFolder("Sub", listOf(WebyTile("p2", "Two", "https://two"))))))
    private val cat = listOf(WebyFolder("Phone apps › A", emptyList(), listOf(WebyFolder("F", listOf(WebyTile("c", "C", null))))))

    @Test fun `it holds the browser bookmarks by folder, every Quickmark with its tiles, then the catalogue`() {
        val all = WebyBookmarks.all(quick, listOf(Triple("https://a.org/x", "A", ""), Triple("https://b.org", "", "Work/Docs")), cat)
        assertEquals(listOf(WebyBookmarks.BROWSER, WebyBookmarks.QUICK, "Phone apps › A"), all.map { it.title })
        assertEquals(listOf("A"), all[0].tiles.map { it.label })
        assertEquals(listOf("Work-Docs"), all[0].folders.map { it.title })
        assertEquals("b.org", all[0].folders[0].tiles.single().label)
        val tiles = WebyOps.flatten(all).flatMap { it.second.tiles }.map { it.id }
        assertTrue("every Quickmark tile is in Bookmarks (all)", listOf("p1", "p2").all { it in tiles })
    }

    @Test fun `with nothing saved only the Quickmarks and the catalogue remain, and with nothing at all it is empty`() {
        assertEquals(listOf(WebyBookmarks.QUICK, "Phone apps › A"), WebyBookmarks.all(quick, emptyList(), cat).map { it.title })
        assertTrue(WebyBookmarks.all(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun `a glyph set parses its viewport and paths`() {
        val g = WebyGlyphs.parse(org.json.JSONObject("""{"ic_x":{"w":24,"h":24,"d":["M0,0h24v24H0z"]},"ic_bad":{"w":1}}"""))
        assertEquals(setOf("ic_x"), g.keys); assertEquals(24f, g["ic_x"]!!.w, 0f)
    }
}

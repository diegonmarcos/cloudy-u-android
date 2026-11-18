package com.diegonmarcos.cloudcanvas

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav shape: build.json::ui declares the bottom nav Draw | Painter | Play | Draw Enhancer | Music Enhancer (#1808 Painter inserted right after
 * Draw), each id a section, and the default is one of them; Draw has the pages Draw | Board (#1787). */
class NavShapeTest {
    private fun ui(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return JSONObject(File(dir!!, "build.json").readText()).getJSONObject("ui")
    }

    @Test fun bottomNavIsDrawPlayEnhancerMusic() {
        val u = ui()
        val bar = (0 until u.getJSONArray("bottom_nav").length()).map { u.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("draw", "painter", "play", "enhance", "music"), bar)
        assertEquals("draw", u.getString("default_section"))
    }

    @Test fun theBarHasFourItemsAndTheMusicSectionIsTheLast() {
        val u = ui()
        // #1808 four items plus Painter; Music Enhancer is still the last
        assertEquals(5, u.getJSONArray("bottom_nav").length())
        assertEquals("music", u.getJSONArray("bottom_nav").getString(4))
    }

    @Test fun everyBarIdIsASectionWithAnIcon() {
        val u = ui()
        val secs = u.getJSONArray("sections")
        val ids = (0 until secs.length()).map { secs.getJSONObject(it) }
        assertEquals(listOf("draw", "painter", "play", "enhance", "music"), ids.map { it.getString("id") })
        assertTrue(ids.all { it.getString("icon").isNotBlank() && it.getString("label").isNotBlank() })
        assertEquals(Decl.SECTION_DRAW, "draw"); assertEquals(Decl.SECTION_PLAY, "play"); assertEquals(Decl.SECTION_ENHANCE, "enhance"); assertEquals(Decl.SECTION_MUSIC, "music")
        assertEquals(Decl.SECTION_PAINTER, "painter")
    }

    /** #1787 Draw holds two sibling pages, Draw then Board; every other section keeps its one page (nothing was removed). */
    @Test fun drawSectionHasDrawAndBoardPages() {
        val secs = ui().getJSONArray("sections")
        val pages = (0 until secs.length()).associate { i ->
            val s = secs.getJSONObject(i); val p = s.getJSONArray("pages")
            s.getString("id") to (0 until p.length()).map { p.getJSONObject(it).getString("id") }
        }
        assertEquals(listOf(Decl.PAGE_DRAW, Decl.PAGE_BOARD), pages["draw"])
        assertEquals(listOf("play"), pages["play"]); assertEquals(listOf("enhance"), pages["enhance"]); assertEquals(listOf("music"), pages["music"])
        assertEquals(listOf(Decl.PAGE_PAINTER), pages["painter"])
    }

    /** The bar and the sections before #1808, label, icon and pages of each: the parity baseline Painter is inserted into. */
    private val before = listOf(
        Triple("draw", "Draw", "edit") to listOf("draw:Draw:edit", "board:Board:account_tree"),
        Triple("play", "Play", "music_note") to listOf("play:Play:music_note"),
        Triple("enhance", "Draw Enhancer", "auto_awesome") to listOf("enhance:Draw Enhancer:auto_awesome"),
        Triple("music", "Music Enhancer", "graphic_eq") to listOf("music:Music Enhancer:graphic_eq"),
    )

    /** #1808 nav parity: Painter sits right after Draw, every earlier tab is still there with its label, icon and pages, and their order is unchanged. */
    @Test fun painterSitsRightAfterDrawAndNothingElseMoved() {
        val u = ui()
        val bar = (0 until u.getJSONArray("bottom_nav").length()).map { u.getJSONArray("bottom_nav").getString(it) }
        assertEquals(bar.indexOf("draw") + 1, bar.indexOf("painter"))
        assertEquals(before.map { it.first.first }, bar.filter { it != "painter" })
        val secs = u.getJSONArray("sections")
        val now = (0 until secs.length()).map { secs.getJSONObject(it) }
        assertEquals(before.map { it.first.first }, now.map { it.getString("id") }.filter { it != "painter" })
        for ((sec, pages) in before) {
            val s = now.first { it.getString("id") == sec.first }
            assertEquals(sec.second, s.getString("label")); assertEquals(sec.third, s.getString("icon"))
            val p = s.getJSONArray("pages")
            assertEquals(pages, (0 until p.length()).map { p.getJSONObject(it) }.map { it.getString("id") + ":" + it.getString("label") + ":" + it.getString("icon") })
        }
        val painter = now.first { it.getString("id") == "painter" }
        assertEquals("Painter", painter.getString("label")); assertTrue(painter.getString("icon").isNotBlank())
        assertEquals("draw", u.getString("default_section"))
    }
}

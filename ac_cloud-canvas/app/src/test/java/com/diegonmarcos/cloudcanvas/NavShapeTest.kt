package com.diegonmarcos.cloudcanvas

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav shape: build.json::ui declares the bottom nav Draw | Play | Draw Enhancer | Music Enhancer, each id a section, and the default is one of them; Draw has the pages Draw | Board (#1787). */
class NavShapeTest {
    private fun ui(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return JSONObject(File(dir!!, "build.json").readText()).getJSONObject("ui")
    }

    @Test fun bottomNavIsDrawPlayEnhancerMusic() {
        val u = ui()
        val bar = (0 until u.getJSONArray("bottom_nav").length()).map { u.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("draw", "play", "enhance", "music"), bar)
        assertEquals("draw", u.getString("default_section"))
    }

    @Test fun theBarHasFourItemsAndTheMusicSectionIsTheLast() {
        val u = ui()
        assertEquals(4, u.getJSONArray("bottom_nav").length())
        assertEquals("music", u.getJSONArray("bottom_nav").getString(3))
    }

    @Test fun everyBarIdIsASectionWithAnIcon() {
        val u = ui()
        val secs = u.getJSONArray("sections")
        val ids = (0 until secs.length()).map { secs.getJSONObject(it) }
        assertEquals(listOf("draw", "play", "enhance", "music"), ids.map { it.getString("id") })
        assertTrue(ids.all { it.getString("icon").isNotBlank() && it.getString("label").isNotBlank() })
        assertEquals(Decl.SECTION_DRAW, "draw"); assertEquals(Decl.SECTION_PLAY, "play"); assertEquals(Decl.SECTION_ENHANCE, "enhance"); assertEquals(Decl.SECTION_MUSIC, "music")
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
    }
}

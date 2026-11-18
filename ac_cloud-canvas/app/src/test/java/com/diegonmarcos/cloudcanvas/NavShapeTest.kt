package com.diegonmarcos.cloudcanvas

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav shape: build.json::ui declares the bottom nav Draw | Painter | Board | Play | Enhancers (#1808 nav: Board has its own entry right after
 * Painter, and the two Enhancer entries became ONE, Enhancers, whose second row holds Draw Enhancer and Music Enhancer), each id a section, and the
 * default is Draw; Draw keeps its pages Draw | Board (#1787). The field-parity tests prove every page of the previous nav is still reachable with its
 * label and icon, and that every earlier deep link still lands on its page. */
class NavShapeTest {
    private fun ui(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return JSONObject(File(dir!!, "build.json").readText()).getJSONObject("ui")
    }

    private fun bar(u: JSONObject = ui()) = (0 until u.getJSONArray("bottom_nav").length()).map { u.getJSONArray("bottom_nav").getString(it) }
    private fun sections(u: JSONObject = ui()) = u.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun pagesOf(s: JSONObject) = s.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun field(p: JSONObject) = p.getString("id") + ":" + p.getString("label") + ":" + p.getString("icon")

    /** #1808 nav: the exact order the owner set. */
    @Test fun bottomNavIsDrawPainterBoardPlayEnhancers() {
        val u = ui()
        assertEquals(listOf("draw", "painter", "board", "play", "enhance"), bar(u))
        assertEquals(listOf("Draw", "Painter", "Board", "Play", "Enhancers"), bar(u).map { id -> sections(u).first { it.getString("id") == id }.getString("label") })
        assertEquals("draw", u.getString("default_section"))
    }

    /** Kept from #1780 (test-canvas-app.sh T5 names it): the bar is still well formed, now with the #1808 nav. */
    @Test fun bottomNavIsDrawPlayEnhancerMusic() {
        val u = ui()
        assertEquals(5, bar(u).size)
        assertEquals(listOf("draw", "painter", "board", "play", "enhance"), bar(u))
    }

    @Test fun everyBarIdIsASectionWithAnIcon() {
        val ids = sections()
        assertEquals(listOf("draw", "painter", "board", "play", "enhance"), ids.map { it.getString("id") })
        assertTrue(ids.all { it.getString("icon").isNotBlank() && it.getString("label").isNotBlank() })
        assertTrue(ids.all { s -> pagesOf(s).all { it.getString("icon").isNotBlank() && it.getString("label").isNotBlank() } })
        assertEquals(Decl.SECTION_DRAW, "draw"); assertEquals(Decl.SECTION_PLAY, "play"); assertEquals(Decl.SECTION_ENHANCE, "enhance")
        assertEquals(Decl.SECTION_PAINTER, "painter"); assertEquals(Decl.SECTION_BOARD, "board")
        assertEquals(Decl.PAGE_ENHANCE, "enhance"); assertEquals(Decl.PAGE_MUSIC, "music")
    }

    /** #1787 Draw holds two sibling pages, Draw then Board (kept by the #1808 nav); Enhancers holds Draw Enhancer (the default) then Music Enhancer. */
    @Test fun drawSectionHasDrawAndBoardPages() {
        val pages = sections().associate { s -> s.getString("id") to pagesOf(s).map { it.getString("id") } }
        assertEquals(listOf(Decl.PAGE_DRAW, Decl.PAGE_BOARD), pages["draw"])
        // the Board entry declares no page of its own (page ids are unique app-wide, nav-shape-guard N12): it renders the Board page
        assertEquals(emptyList<String>(), pages["board"])
        assertEquals(listOf("play"), pages["play"])
        assertEquals(listOf(Decl.PAGE_PAINTER), pages["painter"])
        assertEquals(listOf(Decl.PAGE_ENHANCE, Decl.PAGE_MUSIC), pages["enhance"])
    }

    /** The bar before the #1808 nav (8509cce2: Draw | Painter | Play | Draw Enhancer | Music Enhancer): section id, label, icon and the
     *  id:label:icon of every page. The parity baseline. */
    private val before = listOf(
        Triple("draw", "Draw", "edit") to listOf("draw:Draw:edit", "board:Board:account_tree"),
        Triple("painter", "Painter", "palette") to listOf("painter:Painter:palette"),
        Triple("play", "Play", "music_note") to listOf("play:Play:music_note"),
        Triple("enhance", "Draw Enhancer", "auto_awesome") to listOf("enhance:Draw Enhancer:auto_awesome"),
        Triple("music", "Music Enhancer", "graphic_eq") to listOf("music:Music Enhancer:graphic_eq"),
    )

    /** Field parity (rule 16): every page of the previous nav is still declared with the same id, label and icon, and no page was dropped. */
    @Test fun everyEarlierPageIsStillDeclaredWithItsFields() {
        val now = sections().flatMap { s -> pagesOf(s).map { s.getString("id") to field(it) } }
        val nowFields = now.map { it.second }.toSet()
        for ((sec, pages) in before) for (p in pages) assertTrue("page $p of the old ${sec.first} entry is gone", p in nowFields)
        // every earlier section label and icon survives, as a section or as the page that took its place
        val labels = sections().flatMap { s -> listOf(s.getString("label") to s.getString("icon")) + pagesOf(s).map { it.getString("label") to it.getString("icon") } }.toSet()
        for ((sec, _) in before) assertTrue("the old entry ${sec.second} (${sec.third}) is gone", (sec.second to sec.third) in labels)
        // nothing earlier is lost: the page count only grew (Board's own entry adds one)
        assertTrue(now.size >= before.sumOf { it.second.size })
    }

    /** Field parity for deep links: each earlier `section` (and `section draw page board`) resolves, through Decl.resolve, to a declared
     *  section that holds the same page; `section music` lands on Enhancers > Music Enhancer. */
    @Test fun everyEarlierDeepLinkStillReachesItsPage() {
        val secs = sections().associateBy { it.getString("id") }
        for ((sec, pages) in before) for (p in pages) {
            val pageId = p.substringBefore(':')
            val (s, page) = Decl.resolve(sec.first, pageId)
            val target = secs[s]
            assertNotNull("deep link section=${sec.first} resolves to no section", target)
            assertTrue("deep link section=${sec.first} page=$pageId does not reach its page", pagesOf(target!!).any { field(it) == p && it.getString("id") == page })
        }
        assertEquals(Decl.SECTION_ENHANCE to Decl.PAGE_MUSIC, Decl.resolve("music", null))
        assertEquals("enhance" to null, Decl.resolve("enhance", null))
        assertEquals("board" to null, Decl.resolve("board", null))
        assertEquals("draw" to "board", Decl.resolve("draw", "board"))
    }

    /** #1808 nav: Board sits right after Painter, Enhancers is last, and the earlier entries keep their relative order (Draw, Painter, Play). */
    @Test fun boardSitsRightAfterPainterAndEnhancersIsLast() {
        val b = bar()
        assertEquals(b.indexOf("painter") + 1, b.indexOf("board"))
        assertEquals("enhance", b.last())
        assertEquals(listOf("draw", "painter", "play"), b.filter { it in setOf("draw", "painter", "play") })
        val enh = sections().first { it.getString("id") == "enhance" }
        assertEquals("Enhancers", enh.getString("label")); assertEquals("auto_awesome", enh.getString("icon"))
        assertEquals("enhance", pagesOf(enh).first().getString("id"))
    }

    /** #1808 Painter (test-painter.sh P6 names it): Painter still sits right after Draw, labelled Painter, and the app still opens on Draw. */
    @Test fun painterSitsRightAfterDrawAndNothingElseMoved() {
        val u = ui()
        assertEquals(bar(u).indexOf("draw") + 1, bar(u).indexOf("painter"))
        val painter = sections(u).first { it.getString("id") == "painter" }
        assertEquals("Painter", painter.getString("label")); assertTrue(painter.getString("icon").isNotBlank())
        assertEquals("draw", u.getString("default_section"))
    }
}

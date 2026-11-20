package com.diegonmarcos.cloudkeyboard.grabber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1829 Grabber: (1) any action minimises the window to the keyboard-area strip; (2) a view-mode switcher at the top right goes
 * between the full window and that strip and back, and the last view is remembered; (3) browsers: a page's selection lives on a text
 * node deep under the web root, found now by a bounded breadth-first search off the main thread.
 */
class GrabberViewAndBrowserTest {

    private val ui by lazy { File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberUi.kt").readText() }
    private val acts by lazy { File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberActivities.kt").readText() }
    private val auto by lazy { File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberAuto.kt").readText() }

    private fun sheet(): String = ui.substring(ui.indexOf("fun GrabberSheet("), ui.indexOf("enum class GrabberViewMode"))

    @Test fun `every action button minimises the window`() {
        assertTrue(ui.contains("enabled = busy == null, style = style) { model.run(a, onReply); onAction() }"))
        assertTrue(sheet().contains("GrabberPanel(model, onReply, onAction = { if (!docked) setMode(GrabberViewMode.DOCKED) })"))
    }

    @Test fun `the switcher sits top right, goes both ways and is remembered`() {
        val s = sheet()
        val close = s.indexOf("KeyChip(\"Close\")")
        val switch = s.indexOf("Modifier.testTag(GrabberViewTags.SWITCH)")
        assertTrue("after the spacer, before Close: the top right", switch in s.indexOf("Spacer(Modifier.weight(1f))") until close)
        assertTrue(s.contains("setMode(if (docked) GrabberViewMode.FULL else GrabberViewMode.DOCKED)"))
        assertTrue("remembered", s.contains("GrabberViewPrefs.set(ctx, m)") && s.contains("mutableStateOf(GrabberViewPrefs.get(ctx))"))
        assertTrue("docked: the keyboard-area height, scrollable", s.contains("Modifier.height(sheetH)") && s.contains("verticalScroll(rememberScrollState())"))
        assertTrue("docked: no dim and no tap-outside close", s.contains("if (!docked) {") && s.contains("!docked) 0.62f else 0f"))
        assertTrue("Close and drag-to-close kept", s.contains("KeyChip(\"Close\") { dismiss() }") && s.contains("drag > 90f && docked -> dismiss()"))
        assertEquals(listOf("FULL", "DOCKED"), GrabberViewMode.entries.map { it.name })
    }

    @Test fun `docked, the app behind takes the touches outside the strip`() {
        assertTrue(acts.contains("onModeChange = { applyViewMode(it) }"))
        val f = acts.substring(acts.indexOf("private fun applyViewMode("))
        assertTrue(f.contains("w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)"))
        assertTrue(f.contains("w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)"))
        assertTrue(f.contains("lp.gravity = android.view.Gravity.BOTTOM"))
    }

    // --- browsers --------------------------------------------------------------------------------------------------------

    /** A fake tree: node ids, children, which one holds the selection, which are off screen. */
    private class Fake(val kids: Map<Int, List<Int>>, val selected: Int?, val hidden: Set<Int> = emptySet()) : SelectionNodeSearch.Tree<Int> {
        override fun children(n: Int) = kids[n].orEmpty()
        override fun hasSelection(n: Int) = n == selected
        override fun visible(n: Int) = n !in hidden
    }

    /** A chain 0 -> 1 -> ... -> depth, each level also carrying [width] leaf siblings: a deep page. */
    private fun page(depth: Int, width: Int): Map<Int, List<Int>> {
        val m = HashMap<Int, List<Int>>()
        var leaf = 100_000
        for (d in 0 until depth) m[d] = listOf(d + 1) + List(width) { leaf++ }
        return m
    }

    @Test fun `a selection 30 levels down a page is found, where the old walk stopped at 6`() {
        val r = SelectionNodeSearch.find(0, Fake(page(40, 5), selected = 30))
        assertEquals(30, r.node)
        assertTrue(r.depth >= 30)
    }

    @Test fun `off-screen subtrees are not walked`() {
        val kids = mapOf(0 to listOf(1, 2), 1 to listOf(3), 2 to listOf(4))
        assertNull(SelectionNodeSearch.find(0, Fake(kids, selected = 3, hidden = setOf(1))).node)
        assertEquals(4, SelectionNodeSearch.find(0, Fake(kids, selected = 4, hidden = setOf(1))).node)
    }

    @Test fun `the search is bounded by nodes and by time`() {
        val wide = mapOf(0 to List(SelectionNodeSearch.MAX_NODES + 500) { it + 1 })
        val r = SelectionNodeSearch.find(0, Fake(wide, selected = SelectionNodeSearch.MAX_NODES + 400))
        assertNull(r.node)
        assertTrue(r.visited <= SelectionNodeSearch.MAX_NODES + 1)
        var t = 0L
        val slow = SelectionNodeSearch.find(0, Fake(page(50, 0), selected = 49), now = { t += 50; t })
        assertNull("the time cap stops it", slow.node)
    }

    @Test fun `the root's own selection wins at once`() {
        assertEquals(0, SelectionNodeSearch.find(0, Fake(emptyMap(), selected = 0)).node)
    }

    @Test fun `the service reads the event at once and searches off the main thread`() {
        assertTrue(auto.contains("android.os.HandlerThread(\"grabber-selection\")"))
        assertTrue(auto.contains("workHandler.post {"))
        assertTrue(auto.contains("val found = SelectionNodeSearch.find(src)"))
        assertTrue("the decision returns to the main thread", auto.contains("handler.post { settle(pkg, false, sel,"))
        assertTrue("the old 6-level walk is gone", !auto.contains("if (depth > 6) return null"))
        assertTrue("diagnostics say how far the search went", auto.contains("(searched \${found.visited} nodes, depth \${found.depth})"))
    }
}

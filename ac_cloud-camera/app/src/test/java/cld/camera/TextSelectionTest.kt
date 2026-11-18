package cld.camera

import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.OcrCache
import com.diegonmarcos.superapp.vision.TextLayout
import com.diegonmarcos.superapp.vision.TextLayout.Builder.L
import com.diegonmarcos.superapp.vision.TextLayout.Builder.W
import com.diegonmarcos.superapp.vision.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1805 the OCR cache and tap-to-select: the layout's reading order and text, hit testing word / line / block, the
 * tap that grows a selection word → line → block, the handles that extend it, Copy all, and the per-frame cache a
 * freeze reuses (least recently used out first, evicted pixels handed back, never a frame read twice).
 */
class TextSelectionTest {
    /**
     * Two blocks: a sign of two lines ("OPEN DAILY" / "9 TO 5") and a second block one line ("CAFE"), in a 640x480 frame.
     * Words 0..3 are the sign, 4 the cafe.
     */
    private fun layout(): TextLayout = TextLayout.Builder(640, 480)
        .block("OPEN DAILY\n9 TO 5", Box(100f, 100f, 300f, 180f), "latin", listOf(
            L("OPEN DAILY", Box(100f, 100f, 300f, 130f), 0.95f, listOf(W("OPEN", Box(100f, 100f, 190f, 130f)), W("DAILY", Box(200f, 100f, 300f, 130f)))),
            L("9 TO 5", Box(100f, 150f, 260f, 180f), 0.9f, listOf(W("9", Box(100f, 150f, 120f, 180f)), W("TO 5", Box(130f, 150f, 260f, 180f)))),
        ))
        .block("CAFE", Box(400f, 300f, 500f, 340f), "latin", listOf(L("CAFE", Box(400f, 300f, 500f, 340f), 0.8f, emptyList())))
        .build()

    @Test fun wordsAreNumberedInReadingOrderAndTheTextKeepsItsBreaks() {
        val l = layout()
        assertEquals(listOf("OPEN", "DAILY", "9", "TO 5", "CAFE"), l.words.map { it.text })
        assertEquals(listOf(0, 1, 2, 3, 4), l.words.map { it.index })
        assertEquals(listOf(0, 0, 1, 1, 2), l.words.map { it.line })
        assertEquals("a line ML Kit split into no words is one word", "CAFE", l.words[4].text)
        assertEquals("OPEN DAILY\n9 TO 5\n\nCAFE", l.allText)
        assertEquals("DAILY\n9", l.textOf(2, 1))
        assertEquals("", TextLayout(10, 10, emptyList()).allText)
    }

    @Test fun hitTestingFindsTheSmallestElementUnderTheFinger() {
        val l = layout()
        assertEquals(TextLayout.Hit(TextLayout.Level.WORD, 1), l.hit(250f, 115f))
        // between OPEN and DAILY (190..200): no word, the line holds it
        assertEquals(TextLayout.Hit(TextLayout.Level.LINE, 0), l.hit(195f, 115f))
        // between the two lines (130..150): the block holds it
        assertEquals(TextLayout.Hit(TextLayout.Level.BLOCK, 0), l.hit(150f, 140f))
        assertNull(l.hit(10f, 10f))
        // where two grown words overlap, the nearest centre wins
        assertEquals(TextLayout.Hit(TextLayout.Level.WORD, 0), l.hit(194f, 115f, slop = 8f))
        assertEquals(TextLayout.Hit(TextLayout.Level.WORD, 1), l.hit(199f, 115f, slop = 8f))
    }

    @Test fun aTapSelectsAWordAndTappingItAgainGrowsToItsLineThenItsBlock() {
        val l = layout()
        val word = TextSelection.tap(l, null, 250f, 115f)!!
        assertEquals(TextSelection(1, 1), word)
        assertEquals("DAILY", word.text(l))
        val line = TextSelection.tap(l, word, 250f, 115f)!!
        assertEquals("OPEN DAILY", line.text(l))
        val block = TextSelection.tap(l, line, 250f, 115f)!!
        assertEquals("OPEN DAILY\n9 TO 5", block.text(l))
        assertEquals("a fourth tap goes back to the word", word, TextSelection.tap(l, block, 250f, 115f))
        assertEquals("a tap on another word starts over there", TextSelection(4, 4), TextSelection.tap(l, block, 450f, 320f))
        assertEquals("a tap on a line's gap selects the line", "OPEN DAILY", TextSelection.tap(l, null, 195f, 115f)!!.text(l))
        assertEquals("a tap inside the block between lines selects the block", TextSelection(0, 3), TextSelection.tap(l, null, 150f, 140f))
        assertNull(TextSelection.tap(l, word, 10f, 10f))
    }

    @Test fun theHandlesExtendTheSelectionToTheWordNearestTheFingerNeverPastTheOtherEnd() {
        val l = layout()
        val start = TextSelection(1, 1)
        val toCafe = TextSelection.drag(l, start, TextSelection.Handle.END, 520f, 330f)
        assertEquals(TextSelection(1, 4), toCafe)
        assertEquals("DAILY\n9 TO 5\n\nCAFE", toCafe.text(l))
        assertEquals(TextSelection(0, 4), TextSelection.drag(l, toCafe, TextSelection.Handle.START, 90f, 110f))
        assertEquals("the start handle dragged past the end stops at the end", TextSelection(1, 1), TextSelection.drag(l, start, TextSelection.Handle.START, 450f, 320f))
        assertEquals("the end handle dragged before the start stops at the start", TextSelection(1, 1), TextSelection.drag(l, start, TextSelection.Handle.END, 100f, 110f))
        assertEquals("the second line, nearer the finger below it", 3, l.nearestWord(200f, 200f))
    }

    @Test fun copyAllSelectsEveryWord() {
        val l = layout()
        val all = TextSelection.all(l)!!
        assertEquals(TextSelection(0, 4), all)
        assertEquals(l.allText, all.text(l))
        assertNull(TextSelection.all(TextLayout(1, 1, emptyList())))
    }

    @Test fun aLayoutReadLargerScalesOntoTheDetectionFrame() {
        val l = layout().scaled(0.5f)
        assertEquals(320, l.frameWidth); assertEquals(240, l.frameHeight)
        assertEquals(Box(100f, 50f, 150f, 65f), l.words[1].box)
        assertEquals(TextLayout.Hit(TextLayout.Level.WORD, 1), l.hit(125f, 57f))
    }

    @Test fun theCacheKeepsEachFramesTextAndEvictsTheLeastRecentlyUsed() {
        val evicted = mutableListOf<String>()
        val cache = OcrCache<String>(capacity = 2) { evicted += it }
        val a = layout(); val b = layout(); val c = layout()
        cache.put(1, a, "px1", 100)
        cache.put(2, b, "px2", 200)
        assertSame("a frozen frame finds its own text: nothing is read again", a, cache.get(1)!!.layout)
        cache.put(3, c, "px3", 300)
        // 1 was used after 2, so 2 is the least recently used
        assertEquals(listOf("px2"), evicted)
        assertNull(cache.get(2))
        assertEquals(2, cache.size())
        assertEquals(3L, cache.latest()!!.frameId)
        assertEquals("a freeze reuses a read no older than it allows", 3L, cache.freshSince(250)!!.frameId)
        assertNull("a read older than the freeze allows is not reused", cache.freshSince(301))
        cache.put(3, c, "px3b", 310)
        assertEquals("a frame read again hands back its old pixels", listOf("px2", "px3"), evicted)
        cache.clear()
        assertEquals(0, cache.size())
        assertTrue(evicted.containsAll(listOf("px1", "px3b")))
    }
}

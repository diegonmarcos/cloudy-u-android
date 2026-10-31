// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.GrammarHighlights
import helium314.keyboard.latin.GrammarIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar Check ▸ Highlight popup: a tap finds the mark, Ignore removes it and keeps it from coming back. */
class GrammarHighlightsTest {
    private val i = GrammarIssue(6, 7, "i", "I")             // "hello i think"
    private val dup = GrammarIssue(14, 18, " the", "")      // a repeated word

    @Test fun tapInsideOrAtTheEdgeFindsTheMark() {
        val h = GrammarHighlights(); h.record(listOf(i, dup))
        assertEquals(i, h.at(6)); assertEquals(i, h.at(7))
        assertEquals(dup, h.at(16))
        assertNull(h.at(3)); assertNull(h.at(10))
    }

    @Test fun ignoreRemovesTheSpan() {
        val h = GrammarHighlights(); h.record(listOf(i, dup))
        h.ignore(i)
        assertEquals(listOf(dup), h.all)
        assertNull(h.at(6))
        assertEquals(1, h.ignoredCount)
    }

    @Test fun ignoredSpanDoesNotReappearOnRecheck() {
        val h = GrammarHighlights(); h.record(listOf(i))
        h.ignore(i)
        assertTrue(h.isIgnored("i", "I"))
        val kept = h.record(listOf(GrammarIssue(20, 21, "i", "I"), dup)) // same text/fix, elsewhere
        assertEquals(listOf(dup), kept)
        assertEquals(listOf(dup), h.all)
        assertFalse(h.isIgnored("i", "It"))                   // a different fix is still marked
    }

    @Test fun ignoreIsPerSession() {
        val h = GrammarHighlights(); h.record(listOf(i)); h.ignore(i)
        h.newSession()
        assertFalse(h.isIgnored("i", "I"))
        assertEquals(listOf(i), h.record(listOf(i)))
    }

    @Test fun replaceRemovesAndShiftsLaterMarks() {
        val h = GrammarHighlights(); h.record(listOf(GrammarIssue(0, 4, "teh ", "the "), GrammarIssue(10, 13, "dog", "dogs")))
        h.record(listOf())
        h.replaced(GrammarIssue(0, 4, "teh ", "the "))
        assertEquals(listOf(GrammarIssue(10, 13, "dog", "dogs")), h.all)       // same length: no shift
        h.replaced(GrammarIssue(10, 13, "dog", "dogs"))
        assertTrue(h.all.isEmpty())
        val g = GrammarHighlights(); g.record(listOf(dup, GrammarIssue(30, 33, "abc", "x")))
        g.replaced(dup)                                                          // 4 chars → 0
        assertEquals(listOf(GrammarIssue(26, 29, "abc", "x")), g.all)
    }

    @Test fun aNewMarkReplacesAnOverlappingOne() {
        val h = GrammarHighlights(); h.record(listOf(GrammarIssue(0, 5, "hellp", "hello")))
        h.record(listOf(GrammarIssue(0, 5, "hellp", "help")))
        assertEquals(1, h.all.size); assertEquals("help", h.all[0].suggestion)
    }
}

package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1532 the toolbar chips apply formatting, the focus paragraph, and tags. */
class FormatTest {
    private fun run(a: FormatAction, text: String, s: Int, e: Int) = a.apply(text, s, e)

    @Test fun everyChipIsInAGroupOnce() {
        val flat = FormatAction.GROUPS.flatten()
        assertEquals(FormatAction.values().toList(), flat)
        assertEquals(flat.size, flat.map { it.id }.toSet().size)
        assertEquals(3, FormatAction.GROUPS.size)
    }

    @Test fun boldItalicCodeWrapTheSelection() {
        assertEquals(Edit("a **b** c", 4, 5), run(FormatAction.BOLD, "a b c", 2, 3))
        assertEquals(Edit("a *b* c", 3, 4), run(FormatAction.ITALIC, "a b c", 2, 3))
        assertEquals(Edit("a `b` c", 3, 4), run(FormatAction.CODE, "a b c", 2, 3))
    }

    @Test fun boldAgainUnwraps() = assertEquals(Edit("a b c", 2, 3), run(FormatAction.BOLD, "a **b** c", 4, 5))

    @Test fun headingsMarkTheCaretLine() {
        assertEquals("# title", run(FormatAction.H1, "title", 2, 2).text)
        assertEquals("## title", run(FormatAction.H2, "title", 0, 0).text)
        assertEquals("title", run(FormatAction.H1, "# title", 3, 3).text)
    }

    @Test fun listsQuotesAndChecklistsPrefixEveryTouchedLine() {
        assertEquals("- a\n- b", run(FormatAction.BULLETS, "a\nb", 0, 3).text)
        assertEquals("1. a\n1. b", run(FormatAction.NUMBERED, "a\nb", 0, 3).text)
        assertEquals("- [ ] a\n- [ ] b", run(FormatAction.CHECKLIST, "a\nb", 0, 3).text)
        assertEquals("> a\nb", run(FormatAction.QUOTE, "a\nb", 0, 0).text)
    }

    @Test fun checklistTogglesOff() = assertEquals("a", run(FormatAction.CHECKLIST, "- [ ] a", 0, 7).text)

    @Test fun linkWrapsTheSelectionAndSelectsTheUrl() {
        val e = run(FormatAction.LINK, "see docs now", 4, 8)
        assertEquals("see [docs](url) now", e.text)
        assertEquals("url", e.text.substring(e.selStart, e.selEnd))
    }

    @Test fun linkWithNoSelectionInsertsASkeletonAndSelectsItsText() {
        val e = run(FormatAction.LINK, "ab", 1, 1)
        assertEquals("a[text](url)b", e.text)
        assertEquals("text", e.text.substring(e.selStart, e.selEnd))
    }

    @Test fun linkAcceptsAReversedSelectionAndClamps() {
        assertEquals("[b](url)", Markdown.link("b", 1, 0).text)
        assertEquals("b[text](url)", Markdown.link("b", 99, 99).text)
    }

    // ── focus mode ──
    private val doc = "one\ntwo\n\nthree\n\nfour"

    @Test fun paragraphIsTheRunOfNonBlankLines() {
        assertEquals(0 until 7, Focus.paragraph(doc, 5))
        assertEquals(0 until 7, Focus.paragraph(doc, 0))
        assertEquals(9 until 14, Focus.paragraph(doc, 10))
        assertEquals(16 until 20, Focus.paragraph(doc, 20))
        assertEquals(16 until 20, Focus.paragraph(doc, 999))
        assertEquals(0 until 7, Focus.paragraph(doc, -5))
    }

    @Test fun aCaretOnABlankLineOwnsNoParagraph() {
        assertTrue(Focus.paragraph(doc, 8).isEmpty())
        assertTrue(Focus.paragraph(doc, 15).isEmpty())
        assertTrue(Focus.paragraph("", 0).isEmpty())
        assertTrue(Focus.paragraph("a\n  \nb", 3).isEmpty())
    }

    @Test fun aWhitespaceOnlyLineSeparatesParagraphs() {
        assertEquals(0 until 1, Focus.paragraph("a\n  \nb", 0))
        assertEquals(5 until 6, Focus.paragraph("a\n  \nb", 5))
    }

    @Test fun dimmedIsEverythingOutsideTheParagraph() {
        assertEquals(listOf(0 until 9, 14 until 20), Focus.dimmed(doc, 10))
        assertEquals(listOf(7 until 20), Focus.dimmed(doc, 0))
        assertEquals(listOf(0 until 16), Focus.dimmed(doc, 20))
        assertEquals(listOf(0 until 20), Focus.dimmed(doc, 8))
        assertTrue(Focus.dimmed("", 0).isEmpty())
        assertTrue(Focus.dimmed("abc", 1).isEmpty())
    }

    // ── tags ──
    @Test fun tagsAreHashWordsLowercasedOnceInOrder() {
        assertEquals(listOf("idea", "todo-now"), Tags.of("Plan #Idea and #todo-now, again #idea"))
    }

    @Test fun headingsNumbersAndFragmentsAreNotTags() {
        assertEquals(emptyList<String>(), Tags.of("# Heading\n## Sub\n#123 a#b http://x/#frag &#39;"))
    }

    @Test fun fencedCodeHasNoTags() = assertEquals(listOf("after"), Tags.of("```\n#code\n```\n#after"))

    @Test fun tagsAreCapped() {
        assertEquals(listOf("a", "b", "c", "d"), Tags.of("#a #b #c #d #e #f"))
        assertEquals(listOf("a", "b"), Tags.of("#a #b #c", 2))
    }

    @Test fun docStoreCarriesTags() {
        val dir = java.nio.file.Files.createTempDirectory("tags").toFile()
        try {
            val store = DocStore(dir)
            val id = store.create("# T\nbody #work")
            assertEquals(listOf("work"), store.list().first { it.id == id }.tags)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── list filters ──
    private fun d(id: String, vararg tags: String) = DocMeta(id, id, "", 0, 0, tags.toList())

    @Test fun allTagsMostUsedFirstThenAlphabetical() {
        val docs = listOf(d("1", "b", "a"), d("2", "b"), d("3", "c"), d("4"))
        assertEquals(listOf("b", "a", "c"), DocList.allTags(docs))
    }

    @Test fun withTagFilters() {
        val docs = listOf(d("1", "a"), d("2", "b"), d("3", "a", "b"))
        assertEquals(listOf("1", "3"), DocList.withTag(docs, "a").map { it.id })
        assertEquals(3, DocList.withTag(docs, null).size)
        assertEquals(0, DocList.withTag(docs, "zzz").size)
    }

    // ── navigation ──
    @Test fun backFromTheListLeavesTheApp() = assertEquals(null, WriterScreens.back(Dest.DOCS, Dest.DOCS, false))

    @Test fun listToEditorAndBack() {
        assertEquals(Transition.FORWARD, WriterScreens.transition(Dest.DOCS, Dest.EDITOR))
        assertEquals(Dest.DOCS, WriterScreens.back(Dest.EDITOR, Dest.DOCS, true))
        assertEquals(Transition.BACKWARD, WriterScreens.transition(Dest.EDITOR, Dest.DOCS))
    }

    @Test fun configsReturnsToTheOpenDocumentOnlyFromIt() {
        assertEquals(Dest.EDITOR, WriterScreens.back(Dest.CONFIGS, Dest.EDITOR, true))
        assertEquals(Dest.DOCS, WriterScreens.back(Dest.CONFIGS, Dest.EDITOR, false))
        assertEquals(Dest.DOCS, WriterScreens.back(Dest.CONFIGS, Dest.DOCS, true))
    }

    @Test fun toolPagesGoBackToTheList() {
        for (d in listOf(Dest.TOOLS, Dest.ENHANCER, Dest.DRAFTER, Dest.TRANSLATOR, Dest.GRAMMAR)) {
            assertEquals(Dest.DOCS, WriterScreens.back(d, Dest.DOCS, true))
        }
    }

    @Test fun otherMovesFadeThrough() {
        assertEquals(Transition.NONE, WriterScreens.transition(Dest.DOCS, Dest.DOCS))
        assertEquals(Transition.FADE_THROUGH, WriterScreens.transition(Dest.DOCS, Dest.TOOLS))
        assertEquals(Transition.FADE_THROUGH, WriterScreens.transition(Dest.EDITOR, Dest.CONFIGS))
        assertEquals(Transition.FADE_THROUGH, WriterScreens.transition(Dest.CONFIGS, Dest.EDITOR))
        assertTrue(WriterScreens.FADE_OUT_MS < WriterScreens.FADE_IN_MS)
    }
}

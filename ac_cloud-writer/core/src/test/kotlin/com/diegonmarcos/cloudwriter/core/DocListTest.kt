package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DocListTest {
    private val a = DocMeta("a", "banana", "", 100, 5)
    private val b = DocMeta("b", "Apple", "", 300, 50)
    private val c = DocMeta("c", "cherry", "", 200, 500)
    private val all = listOf(a, b, c)

    private fun ids(l: List<DocMeta>) = l.map { it.id }

    @Test fun recentIsNewestFirst() = assertEquals(listOf("b", "c", "a"), ids(DocList.sort(all, DocSort.RECENT)))
    @Test fun longestIsMostWordsFirst() = assertEquals(listOf("c", "b", "a"), ids(DocList.sort(all, DocSort.LONGEST)))
    @Test fun titleIgnoresCase() = assertEquals(listOf("b", "a", "c"), ids(DocList.sort(all, DocSort.TITLE)))
    @Test fun sortIdsRoundTripAndDefault() {
        DocSort.values().forEach { assertEquals(it, DocSort.of(it.id)) }
        assertEquals(DocSort.RECENT, DocSort.of(null))
        assertEquals(DocSort.RECENT, DocSort.of("nonsense"))
    }
    @Test fun totalWords() = assertEquals(555, DocList.totalWords(all))
    @Test fun readingMinutesRoundsUpAndNeverZeroForText() {
        assertEquals(0, DocList.readingMinutes(0))
        assertEquals(1, DocList.readingMinutes(1))
        assertEquals(1, DocList.readingMinutes(200))
        assertEquals(2, DocList.readingMinutes(201))
    }
    @Test fun initialIsFirstLetterUpper() {
        assertEquals("M", DocList.initial("meeting"))
        assertEquals("3", DocList.initial("# 3 things"))
        assertEquals("•", DocList.initial(""))
        assertEquals("•", DocList.initial("***"))
    }
    @Test fun charsSkipLineBreaks() = assertEquals(5, DocList.chars("ab\ncd\r\ne"))
}

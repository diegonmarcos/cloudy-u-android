package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1854 The Context and Files windows' sample data: the slices add up to the window, the filters and the words. */
class WindowsDataTest {
    @Test fun theSlicesAddUpToTheWindowAndTheLastIsFree() {
        for (limit in listOf(0L, 8_000L, 200_000L, 1_000_000L)) {
            val s = WindowsData.context(limit)
            val window = if (limit > 0) limit else WindowsData.DEFAULT_WINDOW
            assertEquals(window, s.sumOf { it.tokens })
            assertEquals(listOf("system", "tools", "skills", "memory", "messages", "files", "free"), s.map { it.id })
            assertTrue(s.all { it.tokens >= 0 })
        }
    }

    @Test fun aSmallWindowScalesTheSlicesDownAndNeverGoesNegative() {
        val s = WindowsData.context(1_000)
        assertEquals(1_000L, s.sumOf { it.tokens })
        assertTrue(s.all { it.tokens >= 0 })
        assertTrue(s.first { it.id == "messages" }.tokens > s.first { it.id == "memory" }.tokens)
    }

    @Test fun filtersKeepOrderAndAllIsEverything() {
        val rows = WindowsData.files()
        assertEquals(rows, WindowsData.filtered(rows, "all"))
        for (f in WindowsData.FILTERS.drop(1)) assertTrue(f, WindowsData.filtered(rows, f).all { it.source == f })
        assertEquals(rows.size, WindowsData.FILTERS.drop(1).sumOf { WindowsData.filtered(rows, it).size })
        assertTrue(rows.all { it.sample })
    }

    @Test fun theWords() {
        assertEquals("12,345", WindowsData.n(12_345))
        assertEquals("9.4%", WindowsData.pct(94, 1000))
        assertEquals("-", WindowsData.pct(1, 0))
        assertEquals("2.3 MB", WindowsData.size(2_412_000))
        assertEquals("310 KB", WindowsData.size(317_440))
        assertEquals("86 B", WindowsData.size(86))
        assertEquals("2 files · 3 KB · 30 tokens", WindowsData.summary(listOf(FileRow("a", "t", 1024, "attached", 10, "x", true), FileRow("b", "t", 2048, "attached", 20, "x", true))))
    }
}

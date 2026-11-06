package com.diegonmarcos.clouddrive.files

import java.nio.file.Files
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1537 the search-results table: the five columns, tap-to-sort on each, ascending then descending, stable ties. */
class SearchTableTest {
    private fun entry(path: String, size: Long, modified: Long, dir: Boolean = false) = FileOps.Entry(
        Location.Local(path), path.substringAfterLast('/'), dir, size, modified, if (dir) FileOps.DIRECTORY_MIME else "application/octet-stream", FileOps.extensionOf(path.substringAfterLast('/')), false,
    )

    // name, size, folder, modified, created are all different orders on purpose
    private val entries = listOf(
        entry("/s/zeta/Beta.txt", 300, 2_000),
        entry("/s/alpha/gamma.md", 100, 3_000),
        entry("/s/mid/Alpha.png", 200, 1_000),
        entry("/s/alpha/delta", 0, 4_000, dir = true),
    )
    private val created = mapOf("/s/zeta/Beta.txt" to 40L, "/s/alpha/gamma.md" to 30L, "/s/mid/Alpha.png" to 10L, "/s/alpha/delta" to 20L)
    private val rows = SearchTable.rows(entries) { p, _ -> created.getValue(p) }

    private fun names(s: SearchSort) = SearchTable.sort(rows, s).map { it.name }

    @Test fun `the table has the five columns in the owner's order`() {
        assertEquals(listOf(SearchColumn.NAME, SearchColumn.SIZE, SearchColumn.FOLDER, SearchColumn.MODIFIED, SearchColumn.CREATED), SearchColumn.entries.toList())
    }

    @Test fun `rows flatten an entry into the five cells`() {
        val r = rows.first { it.name == "gamma.md" }
        assertEquals("/s/alpha/gamma.md", r.path); assertEquals("/s/alpha", r.folder); assertEquals(100L, r.size); assertEquals(3_000L, r.modified); assertEquals(30L, r.created)
        assertEquals("a folder has no size", 0L, rows.first { it.name == "delta" }.size)
        assertEquals("", SearchTable.sizeText(rows.first { it.name == "delta" }))
        assertEquals("100 B", SearchTable.sizeText(r))
        val archiveEntry = FileOps.Entry(Location.Archive("/s/a.zip", "x/y.txt"), "y.txt", false, 5, 1, "text/plain", "txt", false)
        assertTrue("only local entries are rows", SearchTable.rows(listOf(archiveEntry)) { _, m -> m }.isEmpty())
    }

    @Test fun `Name sorts case-insensitively, ascending then descending`() {
        assertEquals(listOf("Alpha.png", "Beta.txt", "delta", "gamma.md"), names(SearchSort(SearchColumn.NAME, false)))
        assertEquals(listOf("gamma.md", "delta", "Beta.txt", "Alpha.png"), names(SearchSort(SearchColumn.NAME, true)))
    }

    @Test fun `Size sorts by bytes`() {
        assertEquals(listOf("delta", "gamma.md", "Alpha.png", "Beta.txt"), names(SearchSort(SearchColumn.SIZE, false)))
        assertEquals(listOf("Beta.txt", "Alpha.png", "gamma.md", "delta"), names(SearchSort(SearchColumn.SIZE, true)))
    }

    @Test fun `Folder sorts by the folder path, ties fall back to the name`() {
        assertEquals(listOf("delta", "gamma.md", "Alpha.png", "Beta.txt"), names(SearchSort(SearchColumn.FOLDER, false)))
        assertEquals(listOf("Beta.txt", "Alpha.png", "gamma.md", "delta"), names(SearchSort(SearchColumn.FOLDER, true)))
    }

    @Test fun `Modified sorts by time`() {
        assertEquals(listOf("Alpha.png", "Beta.txt", "gamma.md", "delta"), names(SearchSort(SearchColumn.MODIFIED, false)))
        assertEquals(listOf("delta", "gamma.md", "Beta.txt", "Alpha.png"), names(SearchSort(SearchColumn.MODIFIED, true)))
    }

    @Test fun `Created sorts by creation time`() {
        assertEquals(listOf("Alpha.png", "delta", "gamma.md", "Beta.txt"), names(SearchSort(SearchColumn.CREATED, false)))
        assertEquals(listOf("Beta.txt", "gamma.md", "delta", "Alpha.png"), names(SearchSort(SearchColumn.CREATED, true)))
    }

    @Test fun `ties are stable and a descending sort is the exact reverse`() {
        val same = SearchTable.rows(listOf(entry("/a/x.txt", 5, 1), entry("/b/x.txt", 5, 1), entry("/a/w.txt", 5, 1))) { _, m -> m }
        val asc = SearchTable.sort(same, SearchSort(SearchColumn.SIZE, false)).map { it.path }
        assertEquals(listOf("/a/w.txt", "/a/x.txt", "/b/x.txt"), asc)
        assertEquals(asc.reversed(), SearchTable.sort(same, SearchSort(SearchColumn.SIZE, true)).map { it.path })
        assertEquals(asc, SearchTable.sort(same.reversed(), SearchSort(SearchColumn.SIZE, false)).map { it.path })
    }

    @Test fun `tapping a header sorts it ascending, tapping it again flips, another column starts ascending`() {
        var s = SearchSort()
        assertEquals(SearchSort(SearchColumn.NAME, false), s)
        s = s.tap(SearchColumn.NAME); assertEquals(SearchSort(SearchColumn.NAME, true), s)
        s = s.tap(SearchColumn.NAME); assertEquals(SearchSort(SearchColumn.NAME, false), s)
        s = s.tap(SearchColumn.SIZE); assertEquals(SearchSort(SearchColumn.SIZE, false), s)
        s = s.tap(SearchColumn.SIZE); assertEquals(SearchSort(SearchColumn.SIZE, true), s)
        s = s.tap(SearchColumn.CREATED); assertEquals("a new column resets to ascending", SearchSort(SearchColumn.CREATED, false), s)
        for (c in SearchColumn.entries) { val a = SearchSort().tap(c); assertEquals(c == SearchColumn.NAME, a.descending) }
    }

    @Test fun `dates read yyyy-MM-dd HH-mm and an unknown time is blank`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("1970-01-01 00:00", SearchTable.date(1, utc))
        assertEquals("2026-11-06 12:30", SearchTable.date(1_793_968_200_000L, utc))
        assertEquals("", SearchTable.date(0, utc))
        assertEquals("", SearchTable.date(-5, utc))
    }

    @Test fun `the created time of a real file is read and falls back when the file is gone`() {
        val f = Files.createTempFile("created", ".txt").toFile()
        assertTrue(SearchTable.createdTime(f.path, 7L) > 0L)
        assertEquals(7L, SearchTable.createdTime(f.path + ".gone", 7L))
    }
}

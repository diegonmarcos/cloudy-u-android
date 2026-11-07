package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Search > Cloudy | Android | Torrent: which subpage a source belongs to and which handler a result goes to. Pure, no device. */
class StoreSearchSubpagesTest {
    @Test fun theThreeSourceSubpagesAreCloudyAndroidTorrentInOrder() {
        assertEquals(listOf("search-cloudy", "search-android", "search-torrent"), StoreSearchPage.PAGES)
        assertEquals("search", StoreSearchPage.SECTION)
        assertEquals(listOf("Cloudy", "Android", "Torrent"), StoreSearchPage.PAGES.map { StoreSearchPage.pageLabel(it) })
    }

    @Test fun ourReleasesAreCloudyAndEveryOtherApkSourceIsAndroid() {
        assertEquals("search-cloudy", StoreSearchPage.pageOf(StoreSearchPage.FLEET))
        assertEquals("search-cloudy", StoreSearchPage.pageOf(StoreSearchPage.DECLARED))
        for (id in listOf("fdroid", "izzy", StoreSearchPage.GITHUB, StoreSearchPage.PLAY)) assertEquals(id, "search-android", StoreSearchPage.pageOf(id))
    }

    @Test fun cloudyAndAndroidResultsInstallAndTorrentResultsDownload() {
        assertEquals("install", StoreSearchPage.handlerOf(StoreSearchPage.PAGE_CLOUDY))
        assertEquals("install", StoreSearchPage.handlerOf(StoreSearchPage.PAGE_ANDROID))
        assertEquals("torrent-download", StoreSearchPage.handlerOf(StoreSearchPage.PAGE_TORRENT))
    }

    @Test fun everyDeclaredAndroidSourceOfTheInstallMapLandsOnAPageThatHasASearcher() {
        val map = JSONObject(File("src/main/assets/appstore-install-sources.json").readText())
        val ids = map.getJSONObject("search").getJSONArray("sources").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertTrue("the install-source map declares search sources", ids.isNotEmpty())
        // Aurora's source (Google Play, anonymous) and F-Droid are Android; the fleet is Cloudy: both pages have something to search.
        assertTrue(ids.any { StoreSearchPage.pageOf(it) == "search-cloudy" })
        assertTrue(ids.any { StoreSearchPage.pageOf(it) == "search-android" })
        assertTrue("play (Aurora-style) is an Android source", ids.contains("play") && StoreSearchPage.pageOf("play") == "search-android")
        assertTrue("f-droid is an Android source", ids.any { it.contains("fdroid") })
    }

    @Test fun withoutAHostTheTorrentPageHasNoSourcesAndSaysSo() {
        StoreTorrentHost.host = null
        assertNull(StoreTorrentHost.host)
    }

    private fun hit(t: String, s: Int, l: Int, size: Long, at: Long, src: String = "The Pirate Bay", cat: String? = "Video", files: Int = 3) =
        StoreTorrentHost.Hit("x", t, s, l, size, at, null, Any(), src, cat, files)

    @Test fun theTableSortsBySeedersFirstAndLeavesUnknownCellsLast() {
        val rows = listOf(hit("low", 2, 1, 10, 100), hit("none", -1, -1, -1, 0, "YTS", null, -1), hit("high", 120, 4, 6_000_000_000L, 1_700_000_000L))
        assertEquals(listOf("high", "low", "none"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.SEEDERS, true).map { it.title })
        assertEquals(listOf("low", "high", "none"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.SEEDERS, false).map { it.title })
        assertEquals(listOf("high", "low", "none"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.SIZE, true).map { it.title })
        assertEquals(listOf("high", "low", "none"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.AGE, true).map { it.title })
assertEquals(listOf("none", "low", "high"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.NAME, true).map { it.title })
        assertEquals(listOf("high", "low", "none"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.NAME, false).map { it.title })
        assertEquals("a text column starts A to Z, a figure biggest first", false to true,
            StoreTorrentHost.defaultDescending(StoreTorrentHost.Col.NAME) to StoreTorrentHost.defaultDescending(StoreTorrentHost.Col.SEEDERS))
        assertEquals(listOf("The Pirate Bay", "The Pirate Bay", "YTS"), StoreTorrentHost.sorted(rows, StoreTorrentHost.Col.SOURCE, false).map { it.sourceLabel })
    }

    @Test fun theTableHasEveryColumnAndShowsADashForAMissingField() {
        assertEquals(listOf("Name", "Size", "S", "L", "Source", "Age", "Category", "Files"), StoreTorrentHost.Col.values().map { it.label })
        assertEquals("—", StoreTorrentHost.sizeText(-1)); assertEquals("—", StoreTorrentHost.seedText(-1)); assertEquals("—", StoreTorrentHost.filesText(-1))
        assertEquals("—", StoreTorrentHost.ageText(0)); assertEquals("120", StoreTorrentHost.seedText(120))
        assertEquals("5m", StoreTorrentHost.ageText(1_000, 1_300)); assertEquals("3h", StoreTorrentHost.ageText(0 + 1, 1 + 3 * 3600L))
        assertEquals("12d", StoreTorrentHost.ageText(1, 1 + 12 * 86400L)); assertEquals("2y", StoreTorrentHost.ageText(1, 1 + 2 * 365 * 86400L))
        assertEquals("1.5 KB", StoreTorrentHost.sizeText(1536))
    }
}

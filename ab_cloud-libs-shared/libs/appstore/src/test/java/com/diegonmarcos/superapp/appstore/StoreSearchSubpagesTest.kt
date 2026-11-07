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

    @Test fun torrentHostFormatsResultLines() {
        val h = StoreTorrentHost.Hit("apibay", "Ubuntu", 120, 4, 6_000_000_000L, 1_700_000_000L, null, Any())
        val l = StoreTorrentHost.line(h)
        assertTrue(l, l.startsWith("S 120 · L 4 · 5.6 GB"))
        assertEquals("S ? · L ? · ?", StoreTorrentHost.line(StoreTorrentHost.Hit("x", "t", -1, -1, -1, 0, null, Any())))
        assertEquals("1.5 KB", StoreTorrentHost.sizeText(1536))
    }
}

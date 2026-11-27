package com.diegonmarcos.clouddrive.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1934 THE STALE "List as of": a refresh must advance the list's timestamp, a stale cache is re-read, a failed read keeps the cached rows and says why.
 * A phone showed 17/11 on 27/11: the cache was re-read only on a tap, had no age limit, and a refresh that could not read changed nothing and said nothing.
 */
class ListingRefresh1934Test {
    private val root = File("..").let { if (File(it, "build.json").isFile) it else File(".") }
    private val screen = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt").readText()
    private val day = 24L * 60 * 60 * 1000
    private val t17 = 1_763_400_600_000L          // 17/11/2026 17:30
    private val t27 = t17 + 10 * day              // 27/11/2026 17:30
    private fun repo(n: String) = GitHubRepos.Repo(n, "o", false, false, "main", "d", 1, "u", "p", "https://h/o/$n.git", "git@h:o/$n.git", "https://h/o/$n", 0, "Kotlin")
    private fun cache(): GitListingCache = GitListingCache(File.createTempFile("listing", ".json").also { it.deleteOnExit() })

    @Test fun aRefreshAdvancesTheListTimestamp() {
        val c = cache()
        ListingRefresh.record(c, GitListingCache.SLOT_OWN, listOf(repo("a")), true, true, "", t17)
        assertEquals(t17, GitListingCache.newest(c.load().values))
        val r = ListingRefresh.refresh(c, GitListingCache.SLOT_OWN, t27) { Result.success(listOf(repo("a"), repo("b"))) }
        assertTrue(r.isSuccess)
        val after = GitListingCache.newest(c.load().values)
        assertTrue("the timestamp did not advance after a refresh ($t17 -> $after)", after > t17)
        assertEquals(t27, after)
        assertEquals(listOf("a", "b"), c.load().getValue(GitListingCache.SLOT_OWN).repos.map { it.name })
    }

    @Test fun aFailedRefreshKeepsTheCachedListAndItsTime() {
        val c = cache()
        ListingRefresh.record(c, GitListingCache.SLOT_OWN, listOf(repo("a")), true, true, "", t17)
        val r = ListingRefresh.refresh(c, GitListingCache.SLOT_OWN, t27) { Result.failure(IllegalStateException("no network")) }
        assertTrue(r.isFailure)
        assertEquals(t17, GitListingCache.newest(c.load().values))
        assertEquals(listOf("a"), c.load().getValue(GitListingCache.SLOT_OWN).repos.map { it.name })
    }

    @Test fun aTenDayOldCacheIsStaleAndAFreshOneIsNot() {
        assertTrue(ListingRefresh.stale(t17, t27))
        assertTrue(ListingRefresh.stale(t17, t17 + ListingRefresh.TTL_MINUTES * 60_000L))
        assertFalse(ListingRefresh.stale(t17, t17 + 60_000L))
        assertTrue("never read is stale", ListingRefresh.stale(0L, t27))
    }

    @Test fun thePageReReadsAStaleCacheOncePerSessionAndSaysWhyARefreshFailed() {
        val eff = screen.substringAfter("a cached list older than the TTL").substringBefore("\n    }\n")
        assertTrue(eff.contains("ListingRefresh.stale(") && eff.contains("refreshListings(registry = false)") && eff.contains("staleChecked = true"))
        assertTrue("a failed read keeps the cached rows", screen.contains("listing.copy(loading = false, loaded = true, error ="))
        assertTrue("the reason is shown beside the time", screen.contains("R.string.git_list_failed, listNote"))
        assertTrue("no credential is said, not silent", screen.contains("R.string.git_list_no_credential"))
        assertTrue("every success stamps through the one recorder", screen.contains("ListingRefresh.record(listingCache, slot,"))
    }
}

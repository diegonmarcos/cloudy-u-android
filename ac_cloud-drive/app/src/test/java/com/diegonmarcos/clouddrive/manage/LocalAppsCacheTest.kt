package com.diegonmarcos.clouddrive.manage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local Apps: the cached list shows before the refresh completes, the refresh replaces it, a package broadcast invalidates it. */
class LocalAppsCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun snap(at: Long, vararg pkgs: String) = AppSnapshot.Snapshot(pkgs.map { AppRow(it, it.uppercase(), false, 1, 2, 3, true, 4, "1.0", "G", "https://x") }, true, "adb", at)
    private fun cache() = LocalAppsCache(File(tmp.root, "cloud-drive-cache/local-apps.json"))

    @Test fun roundTripKeepsEveryField() {
        val s = snap(1234, "a.b").let { it.copy(rows = it.rows + AppRow("c.d", "C", true, null, null, null, null, null)) }
        val c = cache(); c.write(s)
        assertEquals(s, c.read())
    }

    @Test fun cachedListIsShownBeforeTheRefreshCompletesAndTheRefreshReplacesIt() {
        val c = cache(); c.write(snap(100, "old.app"))
        val seen = ArrayList<Pair<List<String>, Boolean>>()
        var collectRan = false
        LocalAppsCache.load(c, { collectRan = true; snap(200, "new.app", "newer.app") }) { s, cached ->
            if (cached) assertTrue("the cached list is emitted before collect runs", !collectRan)
            seen += s.rows.map { it.pkg } to cached
        }
        assertEquals(listOf(listOf("old.app") to true, listOf("new.app", "newer.app") to false), seen)
        assertEquals(listOf("new.app", "newer.app"), c.read()!!.rows.map { it.pkg })   // the fresh list was written back
    }

    @Test fun aFailingRefreshKeepsTheCachedList() {
        val c = cache(); c.write(snap(100, "old.app"))
        val seen = ArrayList<Boolean>()
        LocalAppsCache.load(c, { throw IllegalStateException("pm died") }) { _, cached -> seen += cached }
        assertEquals(listOf(true), seen)
        assertEquals("old.app", c.read()!!.rows.single().pkg)
    }

    @Test fun noCacheMeansOnlyTheFreshList() {
        val seen = ArrayList<Boolean>()
        LocalAppsCache.load(cache(), { snap(1, "x") }) { _, cached -> seen += cached }
        assertEquals(listOf(false), seen)
    }

    @Test fun invalidateKeepsRowsButNeverClaimsFresh() {
        val c = cache(); c.write(snap(5_000, "a"))
        c.invalidate()
        assertEquals(0L, c.read()!!.capturedAt); assertEquals(1, c.read()!!.rows.size)
        assertEquals("updated: out of date", LocalAppsCache.ageLabel(10_000, 0))
    }

    @Test fun damagedCacheReadsAsNothing() {
        val f = File(tmp.root, "x/local-apps.json"); f.parentFile!!.mkdirs(); f.writeText("{not json")
        assertNull(LocalAppsCache(f).read())
        f.writeText("""{"v":99,"rows":[]}"""); assertNull(LocalAppsCache(f).read())
        assertNotNull(LocalAppsCache.decode(LocalAppsCache.encode(snap(1, "a"))))
    }

    @Test fun ageLabel() {
        val now = 10_000_000_000L
        assertEquals("updated just now", LocalAppsCache.ageLabel(now, now - 5_000))
        assertEquals("updated 5 min ago", LocalAppsCache.ageLabel(now, now - 300_000))
        assertEquals("updated 3 h ago", LocalAppsCache.ageLabel(now, now - 3 * 3_600_000L))
        assertEquals("updated 2 d ago", LocalAppsCache.ageLabel(now, now - 2 * 86_400_000L))
    }
}

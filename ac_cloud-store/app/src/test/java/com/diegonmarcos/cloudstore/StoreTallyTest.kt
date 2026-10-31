package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.appstore.StoreTally
import com.diegonmarcos.superapp.appstore.StoreTally.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Store's status area: installed vs declared, and the updates' cached vs to-download split. */
class StoreTallyTest {

    private val mb = 1_000_000L

    private val fleet = listOf(
        Item(installed = true, installedBytes = 10 * mb, releaseBytes = -1),                                  // current
        Item(installed = true, installedBytes = 20 * mb, releaseBytes = 25 * mb, update = true, cachedBytes = 25 * mb), // cached
        Item(installed = true, installedBytes = 30 * mb, releaseBytes = 40 * mb, update = true),              // to download
        Item(installed = false, releaseBytes = 50 * mb),                                                      // missing
    )

    @Test fun `installed vs declared counts`() {
        val t = StoreTally.aggregate(fleet)
        assertEquals(4, t.declared)
        assertEquals(3, t.installed)
    }

    @Test fun `installed size sums the APK bytes of installed apps only`() =
        assertEquals(60 * mb, StoreTally.aggregate(fleet).installedBytes)

    @Test fun `declared size is the release size when known, else the installed size`() {
        val t = StoreTally.aggregate(fleet)
        assertEquals((10 + 25 + 40 + 50) * mb, t.declaredBytes)
        assertTrue(t.sizesComplete)
    }

    @Test fun `an app with no size known marks the declared total incomplete`() {
        val t = StoreTally.aggregate(fleet + Item(installed = false))
        assertFalse(t.sizesComplete)
        assertEquals(125 * mb, t.declaredBytes)
        assertTrue(t.installedLine().endsWith("+"))
    }

    @Test fun `updates split into cached and to download`() {
        val t = StoreTally.aggregate(fleet)
        assertEquals(2, t.updates)
        assertEquals(1, t.cached); assertEquals(25 * mb, t.cachedBytes)
        assertEquals(1, t.toDownload); assertEquals(40 * mb, t.toDownloadBytes)
        assertEquals(65 * mb, t.updateBytes)
    }

    @Test fun `a cached update is not counted as to download`() {
        val t = StoreTally.aggregate(listOf(Item(true, 1, 9 * mb, update = true, cachedBytes = 8 * mb)))
        assertEquals(0, t.toDownload); assertEquals(0L, t.toDownloadBytes)
        assertEquals(8 * mb, t.updateBytes)
    }

    @Test fun `the two lines`() {
        val t = StoreTally.aggregate(fleet)
        assertEquals("Installed 3 / 4 apps · 60 MB / 125 MB", t.installedLine())
        assertEquals("Updates 2 · 65 MB — 1 cached (25 MB) · 1 to download (40 MB)", t.updatesLine())
        assertEquals("Updates 0 — all current", StoreTally.aggregate(fleet.take(1)).updatesLine())
    }

    @Test fun `cached update only when the cached build is newer than the installed one`() {
        val cache = mapOf("a" to (5L to 7 * mb))
        assertEquals(7 * mb, StoreTally.cachedUpdate(cache, "a", 4L))
        assertEquals(-1L, StoreTally.cachedUpdate(cache, "a", 5L))
        assertEquals(7 * mb, StoreTally.cachedUpdate(cache, "a", null))
        assertEquals(-1L, StoreTally.cachedUpdate(cache, "b", 1L))
    }
}

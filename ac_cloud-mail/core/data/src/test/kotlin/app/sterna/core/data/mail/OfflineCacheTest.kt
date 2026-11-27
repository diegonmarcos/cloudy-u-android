package app.sterna.core.data.mail

import app.sterna.core.jmap.JmapException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfflineCacheTest {

    // ---- Opening a message offline -------------------------------------------------------------

    @Test
    fun `opening a cached message offline renders from the cache with no network call`() = runTest {
        var networkCalls = 0
        val shown = OfflineFirst.read(
            local = { "cached body" },
            network = { networkCalls++; throw java.net.UnknownHostException("offline") },
        )
        assertEquals("cached body", shown)
        assertEquals(0, networkCalls)
    }

    @Test
    fun `a cache miss falls through to the network`() = runTest {
        assertEquals("fetched", OfflineFirst.read(local = { null }, network = { "fetched" }))
    }

    // ---- 401 handling --------------------------------------------------------------------------

    @Test
    fun `a 401 refreshes the session once and retries`() = runTest {
        var calls = 0
        var refreshes = 0
        val result = AuthRetry.withRefreshOn401(refresh = { refreshes++ }) {
            calls++
            if (calls == 1) throw JmapException("Session request failed: HTTP 401", httpCode = 401)
            "session"
        }
        assertEquals("session", result)
        assertEquals(1, refreshes)
        assertEquals(2, calls)
    }

    @Test
    fun `a second 401 becomes sign-in-required, not a raw HTTP error`() = runTest {
        var refreshes = 0
        try {
            AuthRetry.withRefreshOn401(refresh = { refreshes++ }) {
                throw JmapException("Session request failed: HTTP 401", httpCode = 401)
            }
            fail("expected SignInRequiredException")
        } catch (e: SignInRequiredException) {
            assertTrue(AuthRetry.isAuthFailure(e))
        }
        assertEquals(1, refreshes)
    }

    @Test
    fun `other failures are not retried`() = runTest {
        var refreshes = 0
        try {
            AuthRetry.withRefreshOn401(refresh = { refreshes++ }) { throw JmapException("boom", httpCode = 500) }
            fail("expected JmapException")
        } catch (e: JmapException) {
            assertEquals(500, e.httpCode)
        }
        assertEquals(0, refreshes)
        assertFalse(AuthRetry.isAuthFailure(JmapException("boom", httpCode = 500)))
    }

    // ---- Window setting ------------------------------------------------------------------------

    @Test
    fun `the offline window defaults to all and offers 30 90 180 365 and all`() {
        assertEquals("#1952 fully cached", OfflineWindow.ALL, OfflineWindow.DEFAULT)
        assertEquals(OfflineWindow.ALL, OfflineWindow.from(null))
        assertEquals(OfflineWindow.ALL, OfflineWindow.from("garbage"))
        assertEquals(90, OfflineWindow.THREE.days)
        assertEquals(listOf(1, 3, 6, 12, 0), OfflineWindow.entries.map { it.months })
        assertEquals(listOf(30, 90, 180, 365, 0), OfflineWindow.entries.map { it.days })
        assertNull(OfflineWindow.ALL.cutoffMillis(1_000L))
        // The persisted names keep their meaning: a stored choice is read back as the same window.
        assertEquals(OfflineWindow.SIX, OfflineWindow.from("SIX"))
        assertEquals(OfflineWindow.TWELVE, OfflineWindow.from("TWELVE"))
        val day = 24L * 60 * 60 * 1000
        assertEquals(1_000L * day - 365 * day, OfflineWindow.TWELVE.cutoffMillis(1_000L * day))
    }

    // ---- Sync ----------------------------------------------------------------------------------

    private class FakePort(messages: Map<String, Long>, cached: Set<String>) : OfflineSyncPort {
        val received = messages.toMutableMap()
        val stored = cached.toMutableSet()
        val fetched = mutableListOf<String>()
        var askedCutoff: Long? = -1

        override suspend fun idsInWindow(cutoffMillis: Long?): List<String> {
            askedCutoff = cutoffMillis
            return received.filterValues { cutoffMillis == null || it >= cutoffMillis }.keys.toList()
        }

        override suspend fun cachedIds(ids: List<String>): Set<String> = ids.filter { it in stored }.toSet()

        override suspend fun fetchAndStore(ids: List<String>, attachmentMaxBytes: Long) {
            fetched += ids
            stored += ids
        }

        override suspend fun evictOlderThan(cutoffMillis: Long): Int {
            val old = stored.filter { (received[it] ?: Long.MAX_VALUE) < cutoffMillis }
            stored -= old.toSet()
            return old.size
        }
    }

    @Test
    fun `the sync fetches every message in the window and evicts older ones`() = runTest {
        val day = 24L * 60 * 60 * 1000
        val now = 1_000L * day
        val port = FakePort(
            messages = mapOf(
                "new1" to now - 1 * day,
                "new2" to now - 60 * day,
                "edge" to now - 89 * day,
                "old1" to now - 200 * day,
                "old2" to now - 400 * day,
            ),
            cached = setOf("new2", "old1", "old2"),
        )
        val result = OfflineSync(port, batch = 1).run(OfflineWindow.THREE, OfflineAttachmentLimit.DEFAULT, now)
        assertEquals(now - 90 * day, port.askedCutoff)
        assertEquals(setOf("new1", "edge"), port.fetched.toSet())
        assertEquals(setOf("new1", "new2", "edge"), port.stored)
        assertEquals(2, result.fetched)
        assertEquals(2, result.evicted)
    }

    @Test
    fun `All keeps everything and evicts nothing`() = runTest {
        val port = FakePort(mapOf("a" to 1L, "b" to 2L), cached = setOf("a"))
        val result = OfflineSync(port).run(OfflineWindow.ALL, OfflineAttachmentLimit.NONE, 10_000L)
        assertNull(port.askedCutoff)
        assertEquals(listOf("b"), port.fetched)
        assertEquals(0, result.evicted)
    }
}

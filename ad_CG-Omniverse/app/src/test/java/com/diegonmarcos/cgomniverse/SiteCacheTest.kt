package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cloudlib.web.SiteCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val host = "floor796.com"
    private fun cache() = SiteCache(tmp.root)

    @Test fun missThenHit() {
        val c = cache()
        assertNull(c.serve(host, "https://floor796.com/a.js"))
        c.put(host, "https://floor796.com/a.js", 200, "text/javascript", mapOf("ETag" to "x"), "hi".toByteArray())
        assertArrayEquals("hi".toByteArray(), c.serve(host, "https://floor796.com/a.js")!!.body)
    }

    @Test fun queryIsPartOfTheKeyFragmentIsNot() {
        val c = cache()
        c.put(host, "https://floor796.com/d?v=1", 200, "application/json", emptyMap(), "1".toByteArray())
        assertNull(c.lookup(host, "https://floor796.com/d?v=2"))
        assertNotNull(c.lookup(host, "https://floor796.com/d?v=1#frag"))
    }

    @Test fun serverCacheHeadersAreIgnoredAndNeverExpire() {
        val c = cache()
        c.put(host, "https://floor796.com/x", 200, "text/html", mapOf("Cache-Control" to "max-age=0", "Expires" to "0"), "x".toByteArray(), now = 1)
        val s = c.serve(host, "https://floor796.com/x")!!
        assertFalse(s.headers.keys.any { it.equals("Cache-Control", true) || it.equals("Expires", true) })
        assertNotNull(c.serve(host, "https://floor796.com/x"))
    }

    @Test fun putReportsChangeOnlyWhenBytesDiffer() {
        val c = cache()
        assertTrue(c.put(host, "https://floor796.com/x", 200, "text/html", emptyMap(), "a".toByteArray()))
        assertFalse(c.put(host, "https://floor796.com/x", 200, "text/html", emptyMap(), "a".toByteArray()))
        assertTrue(c.put(host, "https://floor796.com/x", 200, "text/html", emptyMap(), "b".toByteArray()))
        assertEquals("b", String(c.serve(host, "https://floor796.com/x")!!.body))
    }

    @Test fun manifestRoundTripsAcrossInstances() {
        cache().put(host, "https://floor796.com/m.png", 200, "image/png", mapOf("ETag" to "e"), byteArrayOf(1, 2, 3), now = 42)
        val e = cache().lookup(host, "https://floor796.com/m.png")!!
        assertEquals("image/png", e.mime); assertEquals("e", e.headers["ETag"])
        val st = cache().stats(host)
        assertEquals(1, st.files); assertEquals(3L, st.bytes); assertEquals(42L, st.lastRefresh)
        assertEquals(listOf(host), cache().hosts())
    }

    @Test fun wasmMimeAndCors() {
        val c = cache()
        c.put(host, "https://floor796.com/e.wasm", 200, "application/octet-stream", emptyMap(), byteArrayOf(0, 97))
        val s = c.serve(host, "https://floor796.com/e.wasm")!!
        assertEquals("application/wasm", s.mime)
        assertEquals("*", s.headers["Access-Control-Allow-Origin"])
    }

    @Test fun rangeRequestsGetAPartialSlice() {
        val c = cache()
        c.put(host, "https://floor796.com/a.mp3", 200, "audio/mpeg", emptyMap(), ByteArray(10) { it.toByte() })
        val s = c.serve(host, "https://floor796.com/a.mp3", "bytes=2-4")!!
        assertEquals(206, s.status); assertArrayEquals(byteArrayOf(2, 3, 4), s.body)
        assertEquals("bytes 2-4/10", s.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(8, 9), c.serve(host, "https://floor796.com/a.mp3", "bytes=-2")!!.body)
    }

    @Test fun deleteRemovesTheSite() {
        val c = cache()
        c.put(host, "https://floor796.com/x", 200, "text/html", emptyMap(), "a".toByteArray())
        c.delete(host)
        assertNull(c.serve(host, "https://floor796.com/x")); assertTrue(c.hosts().isEmpty())
    }

    @Test fun offlineLoadServesWithoutAnyNetwork() {
        val c = cache()
        c.put(host, "https://floor796.com/", 200, "text/html; charset=utf-8", emptyMap(), "<html>".toByteArray())
        assertEquals("text/html", cache().serve(host, "https://floor796.com/")!!.mime)
    }

    // ── #1711 ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    private val matrix = "https://floor796.com/data/matrix.json"
    private fun blobs() = java.io.File(java.io.File(tmp.root, host), "blobs").listFiles().orEmpty().toList()

    /** floor796 asks data/matrix.json?r=<random>: never the stored address. The copy answers it with the newest query it holds, only when asked to. */
    @Test fun aCacheDefeatingQueryIsAnsweredByTheNewestStoredOne() {
        val c = cache()
        assertNull(c.newest(host, "$matrix?r=0.9"))
        c.put(host, "$matrix?r=0.1", 200, "application/json", mapOf("ETag" to "a"), "{\"v\":1}".toByteArray(), now = 10)
        c.put(host, "$matrix?r=0.2", 200, "application/json", mapOf("ETag" to "b"), "{\"v\":2}".toByteArray(), now = 20)
        c.put(host, "https://floor796.com/data/changelog.json?r=0.3", 200, "application/json", emptyMap(), "[]".toByteArray(), now = 30)
        // the exact address is still the only thing a plain lookup answers (a different query is a different resource)
        assertNull(c.lookup(host, "$matrix?r=0.9")); assertNull(c.serve(host, "$matrix?r=0.9"))
        assertEquals("$matrix?r=0.2", c.newest(host, "$matrix?r=0.9")!!.url)
        assertEquals(20L, c.newest(host, "$matrix?r=0.9")!!.stored)
        val s = c.serve(host, "$matrix?r=0.9", anyQuery = true)!!
        assertEquals("{\"v\":2}", String(s.body)); assertEquals("application/json", s.mime); assertEquals("7", s.headers["Content-Length"])
        // an address that is stored exactly wins over a newer query of its path
        assertEquals("{\"v\":1}", String(c.serve(host, "$matrix?r=0.1", anyQuery = true)!!.body))
        // the path without a query is found too, and another path is never mixed in
        assertEquals("{\"v\":2}", String(c.serve(host, matrix, anyQuery = true)!!.body))
        assertNull(c.serve(host, "https://floor796.com/data/other.json?r=1", anyQuery = true))
        assertEquals("https://floor796.com/data/matrix.json", SiteCache.pathOf("$matrix?r=0.5#top"))
    }

    /** A new query on every load must not grow the copy for ever: a path keeps its newest queries, and their orphaned blobs go. */
    @Test fun aPathKeepsOnlyItsNewestQueries() {
        val c = cache()
        c.put(host, matrix, 200, "application/json", emptyMap(), "plain".toByteArray(), now = 1)
        c.put(host, "https://floor796.com/favicon.ico?10", 200, "image/x-icon", emptyMap(), "ico".toByteArray(), now = 2)
        for (i in 1..9) c.put(host, "$matrix?r=0.$i", 200, "application/json", emptyMap(), (if (i == 9) "same" else if (i == 8) "same" else "body$i").toByteArray(), now = 100L + i)
        val kept = c.urls(host).filter { it.startsWith("$matrix?") }
        assertEquals(SiteCache.MAX_VARIANTS, kept.size)
        assertEquals(listOf("$matrix?r=0.6", "$matrix?r=0.7", "$matrix?r=0.8", "$matrix?r=0.9"), kept)
        // the entry without a query and another path's only query are untouched
        assertNotNull(c.lookup(host, matrix)); assertNotNull(c.lookup(host, "https://floor796.com/favicon.ico?10"))
        // dropped entries leave no blob behind; a blob two kept entries share (0.8 and 0.9) is still there: plain, ico, body6, body7, same
        assertEquals(5, blobs().size)
        assertEquals("same", String(c.serve(host, "$matrix?r=0.8")!!.body)); assertEquals("same", String(c.serve(host, "$matrix?r=0.9")!!.body))
        assertEquals(6, c.stats(host).files)
    }

    /** A file cut short (the process killed while writing) is never served, and the next store writes it whole again. */
    @Test fun aBlobCutShortIsNeverServedAndIsWrittenAgain() {
        val c = cache()
        val url = "https://floor796.com/workers/brotli_decompress_bg.wasm"
        val body = ByteArray(4096) { (it % 251).toByte() }
        c.put(host, url, 200, "application/wasm", emptyMap(), body)
        val blob = blobs().single()
        blob.writeBytes(body.copyOf(100))                       // what a killed write left behind before #1711
        assertNull(c.lookup(host, url)); assertNull(c.serve(host, url)); assertNull(c.newest(host, url))
        assertFalse(c.put(host, url, 200, "application/wasm", emptyMap(), body))   // same bytes as the manifest names: not a change, but the blob is rewritten
        assertArrayEquals(body, c.serve(host, url)!!.body)
        // nothing half-written is ever left in the copy
        assertTrue(java.io.File(tmp.root, host).walkTopDown().none { it.name.contains(".tmp") })
    }

    /** The WebView asks on several threads at once; every one of them reads the manifest, changes it and writes it back. None may undo another. */
    @Test fun writersOnSeveralThreadsLoseNoEntry() {
        val threads = (0 until 8).map { t ->
            Thread { val c = cache(); for (i in 0 until 25) c.put(host, "https://floor796.com/t$t/f$i.js", 200, "text/javascript", emptyMap(), "t$t f$i".toByteArray()) }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val c = cache()
        assertEquals(200, c.urls(host).size)
        for (t in 0 until 8) for (i in 0 until 25) assertEquals("t$t f$i", String(c.serve(host, "https://floor796.com/t$t/f$i.js")!!.body))
    }

    /** A copy made before #1711 (entries without size or time) stays readable; among such entries the one written last is the newest. */
    @Test fun aCopyFromBeforeTheFixStaysReadable() {
        val dir = java.io.File(tmp.root, host); java.io.File(dir, "blobs").mkdirs()
        val h1 = SiteCache.sha256("old1".toByteArray()); val h2 = SiteCache.sha256("old2".toByteArray())
        java.io.File(java.io.File(dir, "blobs"), h1).writeBytes("old1".toByteArray()); java.io.File(java.io.File(dir, "blobs"), h2).writeBytes("old2".toByteArray())
        java.io.File(dir, "manifest.json").writeText(
            "{\"entries\":{\"$matrix?r=0.1\":{\"hash\":\"$h1\",\"mime\":\"application/json\",\"status\":200,\"headers\":{\"ETag\":\"e\"}}," +
                "\"$matrix?r=0.2\":{\"hash\":\"$h2\",\"mime\":\"application/json\",\"status\":200,\"headers\":{}}},\"refreshed\":5}")
        val c = cache()
        val e = c.lookup(host, "$matrix?r=0.1")!!
        assertEquals(0L, e.stored); assertEquals("e", e.headers["ETag"]); assertEquals("old1", String(c.serve(host, "$matrix?r=0.1")!!.body))
        assertTrue(String(c.serve(host, "$matrix?r=0.7", anyQuery = true)!!.body).startsWith("old"))
        // the first store after the update is the newest and the old ones stay within the bound
        c.put(host, "$matrix?r=0.3", 200, "application/json", emptyMap(), "new".toByteArray(), now = 50)
        assertEquals("new", String(c.serve(host, "$matrix?r=0.8", anyQuery = true)!!.body))
        assertEquals(50L, c.newest(host, "$matrix?r=0.8")!!.stored)
    }
}

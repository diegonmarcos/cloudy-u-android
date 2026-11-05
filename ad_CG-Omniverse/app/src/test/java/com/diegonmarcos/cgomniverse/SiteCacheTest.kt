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
}

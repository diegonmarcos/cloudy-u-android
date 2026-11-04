package com.diegonmarcos.cloudnews

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** EventFetcher's policy, on the REAL data/event-finder.json: declared hosts only, https only, keys stay native, cache + stale-on-failure. */
class EventFetcherTest {
    private val cfg = JSONObject(listOf("data/event-finder.json", "../data/event-finder.json").map { File(it) }.first { it.isFile }.readText())

    private class Keys(val m: MutableMap<String, String> = mutableMapOf()) : EventKeyStore {
        override fun get(id: String) = m[id]
        override fun set(id: String, value: String?) { if (value.isNullOrBlank()) m.remove(id) else m[id] = value }
    }

    /** A scripted server: answers per call from [replies]; records every url + header seen. */
    private class Fake(val replies: MutableList<Pair<Int, String>>, val loc: String? = null) {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        fun open(u: URL): HttpURLConnection {
            urls += u.toString()
            val h = mutableMapOf<String, String>(); headers += h
            val (code, body) = if (replies.size > 1) replies.removeAt(0) else replies[0]
            return object : HttpURLConnection(u) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun setRequestProperty(k: String, v: String) { h[k] = v }
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getResponseCode() = code
                override fun getHeaderField(n: String?): String? = if (n == "Location") loc else null
                override fun getInputStream(): InputStream = if (code in 200..299) ByteArrayInputStream(body.toByteArray()) else throw IOException("HTTP $code")
            }
        }
    }

    private fun dir() = File.createTempFile("ef", "").let { it.delete(); it.mkdirs(); it }
    private fun req(url: String, cache: Double = 6.0) = JSONObject().put("method", "GET").put("url", url).put("cache_hours", cache)
    private val tm = "https://app.ticketmaster.com/discovery/v2/events.json?apikey={key}&size=100"

    @Test fun `the key is resolved natively, url-encoded, and the page never gets it back`() {
        val fake = Fake(mutableListOf(200 to "{\"ok\":1}"))
        val f = EventFetcher(cfg, dir(), Keys(mutableMapOf("ticketmaster" to "a b&c")), fake::open)
        val r = f.fetch("ticketmaster", req(tm), false)
        assertTrue(r.getBoolean("ok"))
        assertEquals("https://app.ticketmaster.com/discovery/v2/events.json?apikey=a+b%26c&size=100", fake.urls.single())
        assertFalse(r.toString().contains("a b"))
        assertTrue(fake.headers.single()["User-Agent"]!!.startsWith("CloudNews"))
    }

    @Test fun `no stored key is an error and no request`() {
        val fake = Fake(mutableListOf(200 to "x"))
        val r = EventFetcher(cfg, dir(), Keys(), fake::open).fetch("ticketmaster", req(tm), false)
        assertFalse(r.getBoolean("ok")); assertTrue(fake.urls.isEmpty())
    }

    @Test fun `host and scheme are policed`() {
        val fake = Fake(mutableListOf(200 to "x"))
        val f = EventFetcher(cfg, dir(), Keys(mutableMapOf("ticketmaster" to "k")), fake::open)
        assertFalse(f.fetch("ticketmaster", req("https://evil.example/x?apikey={key}"), false).getBoolean("ok"))
        assertFalse(f.fetch("ticketmaster", req("http://app.ticketmaster.com/x"), false).getBoolean("ok"))
        assertFalse(f.fetch("nope", req(tm), false).getBoolean("ok"))
        assertFalse(f.fetch("ics", req(tm), false).getBoolean("ok"))
        assertTrue(fake.urls.isEmpty())
    }

    @Test fun `a redirect to another host is refused`() {
        val fake = Fake(mutableListOf(302 to ""), loc = "https://evil.example/")
        val r = EventFetcher(cfg, dir(), Keys(), fake::open).fetch("dice", req("https://dice.fm/browse/london"), false)
        assertFalse(r.getBoolean("ok")); assertEquals(1, fake.urls.size)
    }

    @Test fun `a fresh cache answers without a request, a stale one is refetched, force respects the minimum interval`() {
        var t = 1_000_000_000L
        val fake = Fake(mutableListOf(200 to "one", 200 to "two"))
        val f = EventFetcher(cfg, dir(), Keys(), fake::open, { t })
        val u = "https://dice.fm/browse/london"
        assertEquals("one", f.fetch("dice", req(u, 12.0), false).getString("body")); assertEquals(1, fake.urls.size)
        t += 3_600_000L
        val hit = f.fetch("dice", req(u, 12.0), false)
        assertEquals("one", hit.getString("body")); assertTrue(hit.getLong("age_ms") > 0); assertEquals(1, fake.urls.size)
        assertEquals("one", f.fetch("dice", req(u, 12.0), true).getString("body")); assertEquals(1, fake.urls.size)   // dice min_interval_hours is 12
        t += 12 * 3_600_000L
        assertEquals("two", f.fetch("dice", req(u, 12.0), true).getString("body")); assertEquals(2, fake.urls.size)
    }

    @Test fun `a failing network serves the stale copy, and with no copy reports the status`() {
        var t = 1_000_000_000L
        val d = dir()
        val ok = Fake(mutableListOf(200 to "cached body"))
        EventFetcher(cfg, d, Keys(), ok::open, { t }).fetch("dice", req("https://dice.fm/browse/london", 1.0), false)
        t += 5 * 3_600_000L
        val down = Fake(mutableListOf(503 to "x"))
        val r = EventFetcher(cfg, d, Keys(), down::open, { t }).fetch("dice", req("https://dice.fm/browse/london", 1.0), false)
        assertTrue(r.getBoolean("ok")); assertEquals("cached body", r.getString("body")); assertTrue(r.getLong("age_ms") > 0)
        val none = EventFetcher(cfg, dir(), Keys(), down::open, { t }).fetch("dice", req("https://dice.fm/browse/paris", 1.0), false)
        assertFalse(none.getBoolean("ok")); assertEquals(503, none.getInt("status"))
    }

    @Test fun `ticketers that need a key are exactly those declaring one`() {
        val f = EventFetcher(cfg, dir(), Keys())
        assertTrue(f.needsKey("ticketmaster")); assertTrue(f.needsKey("skiddle"))
        assertFalse(f.needsKey("ra")); assertFalse(f.needsKey("dice")); assertFalse(f.needsKey("unknown"))
        assertNull(f.ticketer("unknown"))
    }
}

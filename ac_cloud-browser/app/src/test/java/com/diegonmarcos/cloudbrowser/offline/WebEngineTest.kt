package com.diegonmarcos.cloudbrowser.offline

import com.diegonmarcos.cloudlib.web.GpuCaps
import com.diegonmarcos.cloudlib.web.GpuPageModel
import com.diegonmarcos.cloudlib.web.PageConsole
import com.diegonmarcos.cloudlib.web.PageDiag
import com.diegonmarcos.cloudlib.web.SiteCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1537 the shared web engine, pure: the offline copy keeps the headers a page depends on (a WebResourceResponse drops any it is not
 * given), the WASM MIME, the banner never names another browser, capability detection, the console ring and the diagnostics rows.
 */
class WebEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val host = "floor796.com"
    private fun cache() = SiteCache(tmp.root)

    @Before fun quiet() { PageConsole.sink = null; PageConsole.clear() }
    @After fun reset() { PageConsole.clear() }

    private val upstream = mapOf(
        "Content-Type" to "text/html; charset=windows-1251",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "require-corp",
        "Cross-Origin-Resource-Policy" to "cross-origin",
        "Content-Security-Policy" to "frame-ancestors 'self'",
        "ETag" to "\"abc\"",
        "Set-Cookie" to "sid=1",
        "Cache-Control" to "no-cache",
        "Content-Encoding" to "gzip",
    )

    @Test fun keepHeadersKeepsWhatABrowserActsOnAndDropsTheRest() {
        val k = SiteCache.keepHeaders(upstream)
        assertEquals("same-origin", k["Cross-Origin-Opener-Policy"])
        assertEquals("require-corp", k["Cross-Origin-Embedder-Policy"])
        assertEquals("cross-origin", k["Cross-Origin-Resource-Policy"])
        assertTrue(k.containsKey("Content-Type") && k.containsKey("ETag") && k.containsKey("Content-Security-Policy"))
        assertFalse(k.containsKey("Set-Cookie")); assertFalse(k.containsKey("Cache-Control")); assertFalse(k.containsKey("Content-Encoding"))
        assertEquals("same-origin", SiteCache.keepHeaders(mapOf("cross-origin-opener-policy" to "same-origin"))["cross-origin-opener-policy"])
    }

    @Test fun serveKeepsContentTypeCharsetCoopCoepAndContentLength() {
        val c = cache()
        c.put(host, "https://floor796.com/", 200, "text/html; charset=windows-1251", SiteCache.keepHeaders(upstream), "<html>".toByteArray())
        val s = c.serve(host, "https://floor796.com/")!!
        assertEquals("text/html", s.mime)
        assertEquals("windows-1251", s.encoding)
        assertEquals("same-origin", s.headers["Cross-Origin-Opener-Policy"])
        assertEquals("require-corp", s.headers["Cross-Origin-Embedder-Policy"])
        assertEquals("6", s.headers["Content-Length"])
        assertNull(s.headers["Cache-Control"]); assertNull(s.headers["Content-Encoding"])
        assertEquals("\"abc\"", s.headers["ETag"])
    }

    @Test fun corsEchoesTheRequestOriginElseWildcard() {
        val c = cache()
        c.put(host, "https://floor796.com/data/a.br", 200, "application/octet-stream", emptyMap(), byteArrayOf(1, 2, 3))
        val any = c.serve(host, "https://floor796.com/data/a.br")!!
        assertEquals("*", any.headers["Access-Control-Allow-Origin"]); assertNull(any.headers["Access-Control-Allow-Credentials"])
        val o = c.serve(host, "https://floor796.com/data/a.br", null, "https://floor796.com")!!
        assertEquals("https://floor796.com", o.headers["Access-Control-Allow-Origin"])
        assertEquals("Origin", o.headers["Vary"]); assertEquals("true", o.headers["Access-Control-Allow-Credentials"])
    }

    @Test fun anUpstreamCorsHeaderSurvivesWhenNoOriginIsAsked() {
        val c = cache()
        c.put(host, "https://floor796.com/x.js", 200, "text/javascript", SiteCache.keepHeaders(mapOf("Access-Control-Allow-Origin" to "https://floor796.com")), "x".toByteArray())
        assertEquals("https://floor796.com", c.serve(host, "https://floor796.com/x.js")!!.headers["Access-Control-Allow-Origin"])
    }

    @Test fun wasmIsAlwaysApplicationWasmAndKeepsItsLength() {
        val c = cache()
        c.put(host, "https://floor796.com/workers/brotli_decompress_bg.wasm", 200, "application/octet-stream", emptyMap(), ByteArray(300))
        val s = c.serve(host, "https://floor796.com/workers/brotli_decompress_bg.wasm")!!
        assertEquals("application/wasm", s.mime); assertEquals("300", s.headers["Content-Length"])
        assertEquals("application/wasm", SiteCache.mimeFor("https://x/a.wasm?v=2", "text/plain"))
    }

    @Test fun aRangeSliceCarriesItsOwnLength() {
        val c = cache()
        c.put(host, "https://floor796.com/a.mp4", 200, "video/mp4", emptyMap(), ByteArray(10) { it.toByte() })
        val s = c.serve(host, "https://floor796.com/a.mp4", "bytes=2-4")!!
        assertEquals(206, s.status); assertEquals("3", s.headers["Content-Length"]); assertEquals("bytes 2-4/10", s.headers["Content-Range"])
    }

    private fun caps(webgpu: Boolean, adapter: Boolean) = GpuCaps(true, webgpu, adapter, emptyList(), "156.0", "Mali", adapterInfo = null)

    @Test fun bannerNamesTheReasonCloudBrowserAndTheFlagAndNeverAnotherBrowser() {
        for (debuggable in listOf(false, true)) for (c in listOf(caps(false, false), caps(true, false))) {
            val t = GpuPageModel.banner(c, debuggable)
            assertFalse(t, t.contains("Chrome", ignoreCase = true))
            assertTrue(t, t.contains("Cloud-Browser") && t.contains("Configs > GPU") && t.contains("ignore-gpu-blocklist"))
        }
        assertTrue(GpuPageModel.banner(caps(true, false), false).contains("navigator.gpu has no adapter"))
        assertTrue(GpuPageModel.banner(caps(false, false), false).contains("does not expose navigator.gpu"))
    }

    @Test fun capabilityDetectionFromThePageProbe() {
        assertTrue(caps(true, true).webgpuUsable); assertFalse(caps(true, false).webgpuUsable); assertFalse(caps(false, false).webgpuUsable)
        val raw = "\"{\\\"f\\\":{\\\"canvas2d\\\":true,\\\"webgl\\\":true,\\\"webgl2\\\":true,\\\"webgpu\\\":false,\\\"crossOriginIsolated\\\":false,\\\"sab\\\":false," +
            "\\\"worker\\\":true,\\\"wasm\\\":true,\\\"bitmap\\\":true},\\\"ua\\\":\\\"Mozilla/5.0 wv\\\",\\\"adapter\\\":\\\"none (navigator.gpu is undefined)\\\",\\\"tex\\\":\\\"8192\\\"}\""
        val d = PageDiag.parse(raw)!!
        val rows = d.rows().associate { it.label to it.value }
        assertEquals("yes", rows["Canvas 2D"]); assertEquals("yes", rows["WebGL2"]); assertEquals("no", rows["navigator.gpu (WebGPU API)"])
        assertEquals("no", rows["crossOriginIsolated"]); assertEquals("no", rows["SharedArrayBuffer"]); assertEquals("yes", rows["WebAssembly"])
        assertEquals("none (navigator.gpu is undefined)", rows["WebGPU adapter"]); assertEquals("8192", rows["Max texture size"])
        assertNull(PageDiag.parse(null)); assertNull(PageDiag.parse("null")); assertNull(PageDiag.parse("\"not json\""))
        assertTrue(PageDiag.SCRIPT.contains("requestAdapter") && PageDiag.SCRIPT.contains("crossOriginIsolated") && PageDiag.SCRIPT.contains("SharedArrayBuffer"))
    }

    @Test fun consoleRingIsBoundedKeepsErrorsAndFormats() {
        for (i in 1..PageConsole.CAPACITY + 50) PageConsole.record("LOG", "m$i", "a.js", i, "https://floor796.com/", i.toLong())
        assertEquals(PageConsole.CAPACITY, PageConsole.snapshot().size)
        assertEquals("m51", PageConsole.snapshot().first().message)
        PageConsole.record("error", "TypeError: x is not a function", "https://floor796.com/front.js", 12, "https://floor796.com/")
        PageConsole.record("WARNING", "slow", null, 0, null)
        val errs = PageConsole.errors()
        assertEquals(1, errs.size)
        assertEquals("[ERROR] TypeError: x is not a function (https://floor796.com/front.js:12)", PageConsole.format(errs.single()))
        assertEquals(1, PageConsole.errors("https://floor796.com").size); assertEquals(0, PageConsole.errors("https://other.org").size)
        assertEquals(6, PageConsole.priority("ERROR"))
    }

    @Test fun theSinkReceivesEveryEntry() {
        val seen = ArrayList<String>()
        PageConsole.sink = { seen += it.message }
        PageConsole.record("LOG", "hello", "", 0, "")
        assertEquals(listOf("hello"), seen)
        assertNotNull(PageConsole.snapshot().singleOrNull())
    }
}

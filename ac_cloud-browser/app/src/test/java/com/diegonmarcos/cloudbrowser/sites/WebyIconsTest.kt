package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1531 the icon resolver: manifest, then apple-touch, then favicon, best size; grey with the initial when nothing resolves. */
class WebyIconsTest {
    private val site = "https://example.org/app/"
    private val html = """<html><head>
        <link rel="icon" href="/fav-32.png" sizes="32x32">
        <link rel="shortcut icon" href="favicon.ico">
        <link rel="apple-touch-icon" sizes="180x180" href="/apple-180.png">
        <link rel='apple-touch-icon-precomposed' href='/apple-pre.png'>
        <link rel="mask-icon" href="/mask.svg">
        <link rel="icon" type="image/svg+xml" href="/icon.svg">
        <link rel="manifest" href="/site.webmanifest">
        </head></html>"""
    private val manifest = """{"icons":[{"src":"/m-192.png","sizes":"192x192"},{"src":"m-512.png","sizes":"512x512"},{"src":"/m-48.png","sizes":"48x48"},{"src":"/mono.png","sizes":"96x96","purpose":"monochrome"}]}"""

    private fun fetch(pages: Map<String, String>) = { u: String -> pages[u] }

    @Test
    fun `the manifest icons come first, then apple-touch, then the favicon, then the conventional places`() {
        val urls = WebyIcons.candidates(site, fetch(mapOf(site to html, "https://example.org/site.webmanifest" to manifest))).map { it.url }
        assertEquals("https://example.org/m-192.png", urls[0])
        assertEquals(listOf("https://example.org/m-192.png", "https://example.org/m-512.png", "https://example.org/m-48.png"), urls.take(3))
        assertEquals(listOf("https://example.org/apple-180.png", "https://example.org/apple-pre.png"), urls.drop(3).take(2))
        assertTrue(urls.indexOf("https://example.org/fav-32.png") > urls.indexOf("https://example.org/apple-pre.png"))
        assertTrue("svg and monochrome never offered", urls.none { it.endsWith(".svg") || it.endsWith("mono.png") })
        assertEquals("https://example.org/favicon.ico", urls.last())
    }

    @Test
    fun `without a manifest the apple-touch icon beats the favicon, and the best size wins inside a kind`() {
        val urls = WebyIcons.candidates(site, fetch(mapOf(site to html))).map { it.url }
        assertEquals("https://example.org/apple-180.png", urls[0])
        val small = WebyIcons.rank(listOf(IconCandidate("a", 2, 16), IconCandidate("b", 2, 128), IconCandidate("c", 2, 512), IconCandidate("d", 2, 0), IconCandidate("e", 2, 48)))
        assertEquals(listOf("b", "c", "d", "e", "a"), small.map { it.url })
    }

    @Test
    fun `a site that answers nothing still gets the conventional favicon tried`() {
        val urls = WebyIcons.candidates("https://down.example/", fetch(emptyMap())).map { it.url }
        assertEquals(listOf("https://down.example/apple-touch-icon.png", "https://down.example/favicon.ico"), urls)
    }

    @Test
    fun `the first candidate that downloads and decodes is the icon, and none usable means grey with the initial`() {
        val list = WebyIcons.candidates(site, fetch(mapOf(site to html)))
        val bytes = WebyIcons.pick(list, { u -> if (u.endsWith("apple-pre.png")) byteArrayOf(1, 2, 3) else if (u.endsWith("apple-180.png")) byteArrayOf(9) else null }, { it.size > 1 })
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertNull(WebyIcons.pick(list, { null }, { true }))
        assertNull(WebyIcons.pick(list, { byteArrayOf(1) }, { false }))
        assertEquals("C", WebyIcons.initial("claude"))
        assertEquals("1", WebyIcons.initial("  1Password"))
        assertEquals("?", WebyIcons.initial("   "))
    }

    @Test
    fun `sizes and relative addresses are read as browsers read them`() {
        assertEquals(32, WebyIcons.sizeOf("16x16 32x32"))
        assertEquals(0, WebyIcons.sizeOf("any"))
        assertEquals(0, WebyIcons.sizeOf(null))
        assertEquals("https://example.org/x.png", WebyIcons.resolve("https://example.org/a/b/", "/x.png"))
        assertEquals("https://example.org/a/b/x.png", WebyIcons.resolve("https://example.org/a/b/", "x.png"))
        assertNull(WebyIcons.resolve("https://example.org/", "data:image/png;base64,AAAA"))
    }

    @Test
    fun `the declared override map wins by tile id or host, and a website lookup fills in a missing address`() {
        val o = JSONObject().put("com.x.app", "https://cdn.example/x.png").put("example.org", "https://cdn.example/e.png").put("bad", "ftp://nope")
        assertEquals("https://cdn.example/x.png", WebyIcons.overrideOf(o, "com.x.app", "https://example.org"))
        assertEquals("https://cdn.example/e.png", WebyIcons.overrideOf(o, "other", "https://www.example.org/p"))
        assertNull(WebyIcons.overrideOf(o, "bad", null))
        assertNull(WebyIcons.overrideOf(o, "none", "https://elsewhere.net"))
        assertEquals("https://declared.example", WebyIcons.website("https://declared.example", "p", listOf("website"), { _, _ -> "https://meta.example" }))
        assertEquals("https://meta.example", WebyIcons.website(null, "p", listOf("website", "homepage"), { _, k -> if (k == "homepage") "https://meta.example" else null }))
        assertNull(WebyIcons.website(null, "p", listOf("website"), { _, _ -> "not a url" }))
    }

    @Test
    fun `the website lookup keys come from the SuperApp declaration, and the overrides are a declared map`() {
        val browser = JSONObject(java.io.File("../build.json").readText()).getJSONObject("ui").getJSONObject("weby")
        assertTrue(browser.has("icon_overrides"))
        val gradle = java.io.File("build.gradle").readText()
        assertTrue("app_urls?.website_meta_keys" in gradle)
        val superapp = JSONObject(java.io.File("../../aa_cloud-superapp/build.json").readText()).getJSONObject("ui").getJSONObject("app_urls").getJSONArray("website_meta_keys")
        assertTrue(superapp.length() > 0)
    }
}

package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.scraper.FileSource
import com.diegonmarcos.superapp.scraper.SimpleCache
import com.diegonmarcos.superapp.scraper.SiteFile
import com.diegonmarcos.superapp.scraper.SiteFiles
import com.diegonmarcos.superapp.scraper.SiteSort
import com.diegonmarcos.superapp.uikit.KitFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #1427 Site Files: the WebView cache entry parse, the stores' readers, grouping per site and the three sorts. */
class ScraperSiteFilesTest {

    /** A simple-cache `_0` file as the WebView writes it: header, key, body, EOF(stream 1), headers, EOF(stream 0). */
    private fun entry(key: String, body: ByteArray, headers: String = "HTTP/1.1 200\u0000content-type: text/css; charset=utf-8\u0000", sha: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, put: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(put).array())
        val k = key.toByteArray()
        le(24) { putLong(SimpleCache.INITIAL_MAGIC); putInt(5); putInt(k.size); putInt(0x1234) }
        out.write(k); out.write(body)
        // As Chromium writes it: the stream-1 EOF record carries NO size (that is what an old matcher wrongly required).
        le(24) { putLong(SimpleCache.FINAL_MAGIC); putInt(0); putInt(0); putInt(0) }
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (sha) out.write(ByteArray(32) { 9 })
        le(24) { putLong(SimpleCache.FINAL_MAGIC); putInt(if (sha) 3 else 1); putInt(0); putInt(headers.length) }
        return out.toByteArray()
    }

    @Test fun `a cache entry gives its URL, the exact body and the content type`() {
        val body = "body{color:red}".toByteArray()
        val raw = entry("1/0/_dk_https://a.com https://a.com https://a.com/css/site.css", body)
        val e = SimpleCache.parse(raw)!!
        assertEquals("https://a.com/css/site.css", e.url)
        assertEquals("body{color:red}", String(raw.copyOfRange(e.bodyStart, e.bodyEnd)))
        assertEquals(body.size, e.bodyBytes)
        assertEquals("text/css", e.contentType)
    }

    @Test fun `an entry with the key SHA-256 before the last EOF record still gives the exact body`() {
        val e = SimpleCache.parse(entry("https://a.com/x.js", "var a=1".toByteArray(), sha = true))!!
        assertEquals(7, e.bodyBytes)
        assertEquals("text/css", e.contentType)
    }

    @Test fun `the folders of a URL below its site`() {
        assertEquals("css/v2", SiteFiles.dirOf("https://a.com/css/v2/x.css?q=1"))
        assertEquals("", SiteFiles.dirOf("https://a.com/x.css"))
        assertEquals("", SiteFiles.dirOf("https://a.com/"))
    }

    private fun tf(dirs: List<String>, name: String, mod: Long = 1, bytes: Long = 10) = KitFileTree.Entry(dirs, name, bytes, mod, KitFileTree.typeOf(name, ""), name)

    private val tree = listOf(
        tf(listOf("b.com"), "index.html", 5), tf(listOf("b.com", "css"), "z.css", 9), tf(listOf("b.com", "css"), "a.css", 3),
        tf(listOf("b.com", "css", "v2"), "x.css", 2), tf(listOf("a.com"), "r.js", 7),
    )

    @Test fun `a closed tree shows only the sites, in the given order, with counts and sizes`() {
        val rows = KitFileTree.rows(tree, emptySet(), listOf("b.com", "a.com"))
        assertEquals(listOf("b.com", "a.com"), rows.map { it.key })
        val b = rows[0] as KitFileTree.Row.Dir
        assertEquals(4, b.files); assertEquals(40L, b.bytes); assertEquals(9L, b.modified); assertEquals(false, b.open)
    }

    @Test fun `an open site lists its folders first, then its files, folders open one by one`() {
        val rows = KitFileTree.rows(tree, setOf("b.com", "b.com/css"))
        assertEquals(listOf("a.com", "b.com", "b.com/css", "b.com/css/v2", "a.css", "z.css", "index.html"),
            rows.map { r -> if (r is KitFileTree.Row.Leaf) r.entry.name else r.key })
        assertEquals(listOf(0, 0, 1, 2, 2, 2, 1), rows.map { it.depth })
    }

    @Test fun `newest first orders files by their modified time`() {
        val rows = KitFileTree.rows(tree, setOf("b.com", "b.com/css"), newestFirst = true)
        val names = rows.filterIsInstance<KitFileTree.Row.Leaf<String>>().map { it.entry.name }
        assertEquals(listOf("z.css", "a.css", "index.html"), names)
    }

    @Test fun `expand all names every folder that has content, and types come from the extension or the MIME`() {
        assertEquals(setOf("a.com", "b.com", "b.com/css", "b.com/css/v2"), KitFileTree.containers(tree))
        assertEquals("css", KitFileTree.typeOf("site.CSS", ""))
        assertEquals("json", KitFileTree.typeOf("data", "application/json; charset=utf-8"))
        assertEquals("file", KitFileTree.typeOf("data", ""))
    }

    @Test fun `files become tree entries under their site, downloads and saved pages in a folder of their own`() {
        val g = SiteFiles.group(listOf(
            SiteFile("a.com", "x.css", "/p", FileSource.CACHE, 1, 1, "https://a.com/css/x.css", "text/css", "css"),
            SiteFile("a.com", "d.pdf", "/q", FileSource.DOWNLOAD, 1, 1), SiteFile("a.com", "p.mhtml", "/r", FileSource.SAVED, 1, 1),
            SiteFile(SiteFiles.APP_HOST, "overrides.css", "/o", FileSource.STORAGE, 1, 1, "", "text/css", "Overrides"),
        ), emptyList())
        val dirs = SiteFiles.entries(g).associate { it.name to it.dirs }
        assertEquals(listOf("a.com", "css"), dirs["x.css"])
        assertEquals(listOf("a.com", "Downloads"), dirs["d.pdf"])
        assertEquals(listOf("a.com", "Saved pages"), dirs["p.mhtml"])
        assertEquals(listOf(SiteFiles.APP_HOST, "Overrides"), dirs["overrides.css"])
    }

    @Test fun `a plain-URL key and a body that contains the EOF magic still parse to the full body`() {
        val magic = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(SimpleCache.FINAL_MAGIC).array()
        val body = magic + ByteArray(32) { (it * 7).toByte() }
        val e = SimpleCache.parse(entry("https://b.org/x.bin", body, "HTTP/1.1 200\u0000"))!!
        assertEquals("https://b.org/x.bin", e.url)
        assertEquals(40, e.bodyBytes)
        assertEquals("", e.contentType)
    }

    @Test fun `files that are not cache entries are ignored`() {
        assertNull(SimpleCache.parse(ByteArray(10)))
        assertNull(SimpleCache.parse(ByteArray(64)))
        val raw = entry("https://a.com/a", "x".toByteArray())
        raw[0] = 0
        assertNull(SimpleCache.parse(raw))
        assertNull(SimpleCache.parse(entry("1/0/_dk_chrome://x", "x".toByteArray())))
    }

    @Test fun `host and name of a URL`() {
        assertEquals("example.com", SiteFiles.hostOf("https://WWW.Example.com/a/b.js?x=1"))
        assertEquals("cdn.example.com", SiteFiles.hostOf("https://cdn.example.com/"))
        assertEquals("", SiteFiles.hostOf("not a url"))
        assertEquals("b.js", SiteFiles.nameOf("https://a.com/a/b.js?x=1"))
        assertEquals("index.html", SiteFiles.nameOf("https://a.com/"))
        assertEquals("my file.css", SiteFiles.nameOf("https://a.com/my%20file.css"))
    }

    private fun f(host: String, name: String, mod: Long, src: FileSource = FileSource.CACHE) =
        SiteFile(host, name, "/x/$host/$name", src, 10, mod)

    private val files = listOf(
        f("b.com", "Zeta.js", 500), f("b.com", "alpha.css", 900),
        f("a.com", "index.html", 100),
        f("c.com", "m.json", 300),
        f("", "orphan", 999),
    )
    private val visits = listOf("https://a.com/x" to 5000L, "https://www.a.com/y" to 7000L, "https://c.com/" to 6000L, "https://b.com/" to 1000L)

    @Test fun `files group per site with the newest visit and the newest file`() {
        val g = SiteFiles.group(files, visits).associateBy { it.host }
        assertEquals(setOf("a.com", "b.com", "c.com"), g.keys)
        assertEquals(7000L, g.getValue("a.com").lastVisit)
        assertEquals(900L, g.getValue("b.com").lastUpdated)
        assertEquals(2, g.getValue("b.com").files.size)
        assertEquals(20L, g.getValue("b.com").bytes)
        assertEquals(0L, SiteFiles.group(listOf(f("d.com", "q", 1)), visits).single().lastVisit)
    }

    @Test fun `sort by name is A-Z for sites and files`() {
        val s = SiteFiles.sort(SiteFiles.group(files, visits), SiteSort.NAME)
        assertEquals(listOf("a.com", "b.com", "c.com"), s.map { it.host })
        assertEquals(listOf("alpha.css", "Zeta.js"), s[1].files.map { it.name })
    }

    @Test fun `sort by last visit is newest visit first`() {
        val s = SiteFiles.sort(SiteFiles.group(files, visits), SiteSort.LAST_VISIT)
        assertEquals(listOf("a.com", "c.com", "b.com"), s.map { it.host })
    }

    @Test fun `sort by last updated is newest file first, files too`() {
        val s = SiteFiles.sort(SiteFiles.group(files, visits), SiteSort.LAST_UPDATED)
        assertEquals(listOf("b.com", "c.com", "a.com"), s.map { it.host })
        assertEquals(listOf("alpha.css", "Zeta.js"), s[0].files.map { it.name })
    }

    @Test fun `downloads list only files that exist, under the site they came from`() {
        val json = """[{"id":"1","url":"https://www.dl.com/f/a.pdf","fileName":"a.pdf","mime":"application/pdf","file":"/d/1/a.pdf"},
            {"id":"2","url":"https://x.com/b","fileName":"b","file":"/d/2/b"},{"id":"3","url":"https://x.com/c"}]"""
        val out = SiteFiles.downloads(json) { p -> if (p == "/d/1/a.pdf") 42L to 7L else null }
        assertEquals(1, out.size)
        assertEquals(SiteFile("dl.com", "a.pdf", "/d/1/a.pdf", FileSource.DOWNLOAD, 42, 7, "https://www.dl.com/f/a.pdf", "application/pdf"), out[0])
        assertEquals(emptyList<SiteFile>(), SiteFiles.downloads("not json") { 1L to 1L })
    }

    @Test fun `saved pages resolve under the offline root and are archives`() {
        val json = """[{"id":"s1","title":"Docs","origin":"https://docs.io","start":"https://docs.io/","ts":1,
            "pages":[{"url":"https://docs.io/a","file":"p1.mhtml","bytes":5,"title":"Page A"},{"url":"https://docs.io/b","file":"gone.mhtml"}]}]"""
        val root = File("/off")
        val out = SiteFiles.saved(json, root) { p -> if (p == File(File(root, "s1"), "p1.mhtml").path) 5L to 9L else null }
        assertEquals(1, out.size)
        assertEquals("docs.io", out[0].host)
        assertEquals("Page A.mhtml", out[0].name)
        assertEquals(FileSource.SAVED, out[0].source)
        assertEquals(File("/off/s1/p1.mhtml").path, out[0].path)
    }

    @Test fun `history rows become visits`() {
        assertEquals(listOf("https://a.com" to 3L), SiteFiles.visits("""[{"url":"https://a.com","ts":3},{"url":""},{"ts":4}]"""))
        assertEquals(emptyList<Pair<String, Long>>(), SiteFiles.visits(null))
    }
}

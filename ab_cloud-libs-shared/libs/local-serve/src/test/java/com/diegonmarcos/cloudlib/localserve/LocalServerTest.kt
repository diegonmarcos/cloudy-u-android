package com.diegonmarcos.cloudlib.localserve

import java.io.File
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalServerTest {
    private lateinit var root: File
    private lateinit var outside: File
    private var server: LocalServer? = null

    @Before fun setUp() {
        val base = Files.createTempDirectory("local-serve").toFile()
        root = File(base, "site").also { it.mkdirs() }
        outside = File(base, "secret.txt").also { it.writeText("TOP SECRET") }
        File(root, "index.html").writeText("<html><link rel=stylesheet href=style.css><img src=img/a.png></html>")
        File(root, "style.css").writeText("body{color:red}")
        File(root, "img").mkdirs()
        File(root, "img/a.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "my page.html").writeText("spaced")
    }

    @After fun tearDown() { server?.close(); outside.parentFile.deleteRecursively() }

    private fun start(): LocalServer = LocalServer(root).also { it.start(); server = it }

    /** One raw HTTP exchange; returns the whole response. */
    private fun raw(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1)); s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    private fun get(port: Int, path: String, host: String = "127.0.0.1"): String = raw(port, "GET $path HTTP/1.1\r\nHost: $host:$port\r\n\r\n")

    @Test fun `it binds the loopback address only, on a free port`() {
        val s = start()
        assertTrue(s.port > 0)
        assertEquals("127.0.0.1", s.boundAddress!!.hostAddress)
        assertTrue(s.boundAddress!!.isLoopbackAddress)
        assertFalse(s.boundAddress!!.isAnyLocalAddress)
        assertNotNull(s.url("index.html").takeIf { it.startsWith("http://127.0.0.1:${s.port}/") })
    }

    @Test fun `two servers get two ports`() {
        val a = start()
        val b = LocalServer(root).also { it.start() }
        try { assertTrue(a.port != b.port) } finally { b.close() }
    }

    @Test fun `it serves the page and its relative assets with their types`() {
        val s = start()
        val page = get(s.port, "/index.html")
        assertTrue(page, page.startsWith("HTTP/1.1 200 OK"))
        assertTrue(page, page.contains("Content-Type: text/html"))
        assertTrue(page.contains("href=style.css"))
        val css = get(s.port, "/style.css")
        assertTrue(css, css.contains("Content-Type: text/css") && css.endsWith("body{color:red}"))
        val png = get(s.port, "/img/a.png")
        assertTrue(png.contains("Content-Type: image/png") && png.contains("Content-Length: 3"))
        assertTrue(get(s.port, "/").contains("href=style.css"))
        assertTrue(get(s.port, "/my%20page.html").endsWith("spaced"))
        assertTrue(get(s.port, "/style.css?v=2#x").contains("200 OK"))
    }

    @Test fun `a missing file is 404 and HEAD has no body`() {
        val s = start()
        assertTrue(get(s.port, "/nope.css").startsWith("HTTP/1.1 404"))
        val head = raw(s.port, "HEAD /style.css HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(head.startsWith("HTTP/1.1 200") && head.contains("Content-Length: 15") && !head.contains("body{"))
    }

    @Test fun `path traversal is refused however it is written`() {
        val s = start()
        for (p in listOf("/../secret.txt", "/%2e%2e/secret.txt", "/img/../../secret.txt", "/%2E%2E%2Fsecret.txt", "/..%5csecret.txt", "/img/%00", "/\\..\\secret.txt")) {
            val r = get(s.port, p)
            assertFalse("$p -> $r", r.contains("TOP SECRET"))
            assertTrue("$p -> $r", r.startsWith("HTTP/1.1 403") || r.startsWith("HTTP/1.1 404") || r.startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun `resolve keeps to the folder, a symlink out of it included`() {
        assertNull(LocalServer.resolve(root, "/../secret.txt"))
        assertNull(LocalServer.resolve(root, "/a/../../x"))
        assertNull(LocalServer.resolve(root, "relative"))
        assertEquals(File(root, "style.css").canonicalFile, LocalServer.resolve(root, "/style.css?x=1"))
        assertEquals(root.canonicalFile, LocalServer.resolve(root, "/"))
        val link = File(root, "escape")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (made) assertNull(LocalServer.resolve(root, "/escape"))
    }

    @Test fun `a foreign Host header and other methods are refused`() {
        val s = start()
        assertTrue(get(s.port, "/index.html", host = "evil.example").startsWith("HTTP/1.1 403"))
        assertTrue(raw(s.port, "POST /index.html HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 405"))
        assertTrue(get(s.port, "/index.html", host = "localhost").startsWith("HTTP/1.1 200"))
    }

    @Test fun `close stops the server and the port refuses connections`() {
        val s = start()
        val port = s.port
        assertTrue(s.isRunning)
        s.close()
        assertFalse(s.isRunning)
        // the listener goes away with the close; give the OS a moment, then every connect must be refused
        var refused = false
        for (i in 0 until 30) {
            try { Socket("127.0.0.1", port).close(); Thread.sleep(100) } catch (e: java.io.IOException) { refused = true; break }
        }
        assertTrue(refused)
        s.close() // closing twice is fine
    }

    @Test fun `starting twice is refused`() {
        val s = start()
        var refused = false
        try { s.start() } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
    }

    @Test fun `mime types cover the web basics`() {
        assertEquals("text/javascript; charset=utf-8", LocalServer.mime("a.js"))
        assertEquals("image/svg+xml", LocalServer.mime("a.SVG"))
        assertEquals("application/octet-stream", LocalServer.mime("a.zzz"))
        assertTrue(isLoopback("127.0.0.1") && isLoopback("localhost") && !isLoopback("example.org") && !isLoopback(null))
    }
}

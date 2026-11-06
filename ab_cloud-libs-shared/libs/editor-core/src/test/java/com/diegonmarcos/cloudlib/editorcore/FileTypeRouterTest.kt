package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypeRouterTest {
    private val router = FileTypeRouter.default()

    @Test fun `text markdown and html go to the wysiwyg engine, editable`() {
        for ((name, format) in listOf("/n.txt" to "text", "/n.md" to "markdown", "/N.MARKDOWN" to "markdown", "/p.html" to "html", "/p.htm" to "html")) {
            val r = router.route(name)
            assertEquals(name, Viewers.WYSIWYG, r.viewer)
            assertEquals(name, format, r.format)
            assertTrue(name, r.editable)
        }
    }

    @Test fun `html has three modes, markdown and text two, json has its own viewer`() {
        assertEquals(listOf(EditorMode.View, EditorMode.Edit, EditorMode.Served), router.route("/p.html").modes)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit, EditorMode.Served), router.route("/p.HTM").modes)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), router.route("/n.md").modes)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), router.route("/n.txt").modes)
        assertEquals(Viewers.JSON, router.route("/data.json").viewer)
        assertEquals("json", router.route("/data.json").format)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), router.route("/data.json").modes)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), router.route("/a.png").modes)
        assertEquals(listOf(EditorMode.View), router.route("/a.pdf").modes.take(1))
        assertEquals(emptyList<EditorMode>(), router.route("/a.docx").modes)
    }

    @Test fun `images audio and video go to the media viewer, images editable, audio and video view only`() {
        for ((name, format) in listOf("a.png" to "image", "a.JPG" to "image", "a.webp" to "image", "a.gif" to "image", "a.heic" to "image", "a.avif" to "image", "a.bmp" to "image", "a.mp3" to "audio", "a.flac" to "audio", "a.opus" to "audio", "a.mp4" to "video", "a.webm" to "video", "a.mkv" to "video", "a.avi" to "video", "a.mov" to "video", "a.3gp" to "video")) {
            val r = router.route("/x/$name")
            assertEquals(name, Viewers.MEDIA, r.viewer)
            assertEquals(name, format, r.format)
            assertEquals(name, format == "image", r.editable)
        }
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), router.route("/a.jpg").modes)
        assertEquals(listOf(EditorMode.View), router.route("/a.mp4").modes)
    }

    @Test fun `svg is shown by the page server and edited as source, never as a bitmap`() {
        val r = router.route("/logo.SVG")
        assertEquals(Viewers.WYSIWYG, r.viewer)
        assertEquals("svg", r.format)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), r.modes)
    }

    @Test fun `no extension is claimed by two formats`() {
        assertEquals(emptyList<String>(), router.duplicateExtensions)
    }

    @Test fun `pdf goes to the pdf lib and the opendocument family to the odf lib`() {
        assertEquals(Viewers.PDF, router.route("/a.pdf").viewer)
        assertTrue(router.route("/a.pdf").editable)
        assertEquals("odt", router.route("/a.odt").format)
        assertEquals("ods", router.route("/a.ods").format)
        assertEquals("odp", router.route("/a.odp").format)
        assertEquals(Viewers.ODF, router.route("/a.ods").viewer)
        assertTrue(router.route("/a.odp").editable)
    }

    @Test fun `Microsoft Office formats are refused as unsupported`() {
        for (ext in listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")) {
            val r = router.route("/x/file.$ext")
            assertEquals(ext, Viewers.UNSUPPORTED, r.viewer)
            assertFalse(ext, r.supported)
            assertFalse(ext, r.editable)
        }
        assertEquals("unsupported format", router.unsupportedReason)
    }

    @Test fun `an unknown or missing extension is never unsupported, it is sniffed`() {
        for (name in listOf("/a.xyz", "/noextension", "/dir.d/noext", "/.hidden", "/Makefile")) {
            assertTrue(name, router.route(name).supported)
            assertEquals("no bytes yet: text", Viewers.WYSIWYG, router.route(name).viewer)
        }
    }

    @Test fun `without a name hint the bytes decide, text then hex`() {
        val text = router.route("/LICENSE", "MIT License\n\nCopyright (c) 2026\n".toByteArray())
        assertEquals(Viewers.WYSIWYG, text.viewer); assertEquals("text", text.format)
        assertEquals(listOf(EditorMode.View, EditorMode.Edit), text.modes)
        val bin = router.route("/blob", byteArrayOf(0, 1, 2, 3, 0, 0, 7, 0x7F, 0x00))
        assertEquals(Viewers.HEX, bin.viewer); assertEquals("hex", bin.format); assertTrue(bin.supported); assertFalse(bin.editable)
        val png = router.route("/photo", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))
        assertEquals(Viewers.MEDIA, png.viewer); assertEquals("image", png.format); assertTrue(png.editable)
        val pdf = router.route("/doc", "%PDF-1.7\n".toByteArray())
        assertEquals(Viewers.PDF, pdf.viewer)
    }

    @Test fun `a known extension is not sniffed and an Office name is still refused`() {
        assertEquals(Viewers.WYSIWYG, router.route("/a.txt", byteArrayOf(0, 0, 0)).viewer)
        assertFalse(router.route("/a.docx", "hello".toByteArray()).supported)
    }

    @Test fun `routeFile reads the head only when the name does not decide`() {
        val dir = java.nio.file.Files.createTempDirectory("route").toFile()
        val noExt = java.io.File(dir, "README").apply { writeText("hello\nworld\n") }
        val blob = java.io.File(dir, "dump").apply { writeBytes(ByteArray(64) { (it * 7).toByte() }.also { it[3] = 0 }) }
        val png = java.io.File(dir, "pic").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)) }
        val named = java.io.File(dir, "n.md").apply { writeText("# x") }
        val missing = java.io.File(dir, "gone")
        assertEquals(Viewers.WYSIWYG, router.routeFile(noExt).viewer)
        assertEquals(Viewers.HEX, router.routeFile(blob).viewer)
        assertEquals("image", router.routeFile(png).format)
        assertEquals("markdown", router.routeFile(named).format)
        assertTrue("an unreadable file is still not unsupported", router.routeFile(missing).supported)
    }

    @Test fun `the mapping is data, a custom table routes by its own entries`() {
        val r = FileTypeRouter("""{"viewers":[{"id":"x","modes":["View","Edit"],"formats":{"f":["abc"]}}],"unsupported":{"reason":"no","extensions":["abc2"]}}""")
        assertEquals("x", r.route("/a.ABC").viewer)
        assertFalse(r.route("/a.abc2").supported)
        assertEquals(setOf("abc"), r.supportedExtensions())
        assertFalse("a table without a sniff block keeps the old answer", r.route("/noext").supported)
    }
}

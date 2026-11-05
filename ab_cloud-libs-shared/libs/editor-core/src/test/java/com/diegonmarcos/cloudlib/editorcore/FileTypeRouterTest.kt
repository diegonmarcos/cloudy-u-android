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
        assertEquals(listOf(EditorMode.View), router.route("/a.png").modes)
        assertEquals(listOf(EditorMode.View), router.route("/a.pdf").modes.take(1))
        assertEquals(emptyList<EditorMode>(), router.route("/a.docx").modes)
    }

    @Test fun `images audio and video go to the media viewer, view only`() {
        for ((name, format) in listOf("a.png" to "image", "a.JPG" to "image", "a.mp3" to "audio", "a.flac" to "audio", "a.mp4" to "video", "a.webm" to "video")) {
            val r = router.route("/x/$name")
            assertEquals(name, Viewers.MEDIA, r.viewer)
            assertEquals(name, format, r.format)
            assertFalse(name, r.editable)
        }
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

    @Test fun `an unknown or missing extension is unsupported too`() {
        assertFalse(router.route("/a.xyz").supported)
        assertFalse(router.route("/noextension").supported)
        assertFalse(router.route("/dir.d/noext").supported)
    }

    @Test fun `the mapping is data, a custom table routes by its own entries`() {
        val r = FileTypeRouter("""{"viewers":[{"id":"x","modes":["View","Edit"],"formats":{"f":["abc"]}}],"unsupported":{"reason":"no","extensions":["abc2"]}}""")
        assertEquals("x", r.route("/a.ABC").viewer)
        assertFalse(r.route("/a.abc2").supported)
        assertEquals(setOf("abc"), r.supportedExtensions())
    }
}

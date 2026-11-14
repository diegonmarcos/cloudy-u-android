package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.imageview.ImagePage
import com.diegonmarcos.cloudbrowser.imageview.ImgEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1741 the image page: what is an image, and the page built for a list of pictures. */
class ImagePageTest {
    @Test fun `every image type is an image by its type or, for a generic type, by its extension`() {
        for (m in listOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml", "image/bmp", "image/heic", "image/avif", "IMAGE/PNG; x=1"))
            assertTrue(m, ImagePage.isImage(m, null))
        for (n in listOf("a.png", "a.JPG", "a.b.webp", "a.heif", "a.svg")) assertTrue(n, ImagePage.isImage("application/octet-stream", n))
        assertTrue(ImagePage.isImage(null, "photo.jpeg"))
    }

    @Test fun `text pdf and archives are never images, even with an image name`() {
        assertFalse(ImagePage.isImage("text/plain", "a.png"))
        assertFalse(ImagePage.isImage("application/pdf", "a.pdf"))
        assertFalse(ImagePage.isImage("application/octet-stream", "a.zip"))
        assertFalse(ImagePage.isImage(null, null))
    }

    private val two = listOf(
        ImgEntry("file:///a/one.png", "one.png", 2048, "image/png", listOf("Camera" to "Acme X1")),
        ImgEntry("file:///a/two.gif", "two.gif", 10, "image/gif"),
    )

    @Test fun `the page carries every entry, the start index, and the viewer controls`() {
        val h = ImagePage.html(two, 1)
        assertTrue(h.contains("var E=[{\"url\":\"file:///a/one.png\""))
        assertTrue(h.contains("idx=1"))
        for (id in listOf("prev", "next", "rl", "rr", "fit", "one", "inf")) assertTrue("control $id", h.contains("id=\"$id\""))
        assertTrue("pan and zoom", h.contains("pointermove") && h.contains("wheel"))
        assertTrue("EXIF rows reach the info panel", h.contains("[\"Camera\",\"Acme X1\"]"))
        assertTrue("print shows the picture alone", h.contains("@media print"))
    }

    @Test fun `a hostile file name cannot close the script or the title`() {
        val h = ImagePage.html(listOf(ImgEntry("file:///x.png", "</script><script>alert(1)</script>\".png", 1, "image/png")), 0)
        assertFalse(h.contains("</script><script>alert"))
        assertFalse(h.contains("<title></script>"))
        assertTrue(h.contains("\\u003c/script\\u003e"))
    }

    @Test fun `an out of range index is clamped and an empty list still builds a page that says so`() {
        assertTrue(ImagePage.html(two, 99).contains("idx=1"))
        assertTrue(ImagePage.html(two, -5).contains("idx=0"))
        val empty = ImagePage.html(emptyList(), 0)
        assertTrue(empty.contains("var E=[]"))
        assertTrue(empty.contains("No image to show."))
    }

    @Test fun `a type the web view cannot decode shows a reason instead of a blank page`() {
        assertTrue(ImagePage.html(two, 0).contains("cannot be shown here"))
    }

    @Test fun `json strings escape quotes, backslashes, control characters and angle brackets`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c\\u003e\\u0026\"", ImagePage.jsString("a\"b\\c\n<>&"))
    }
}

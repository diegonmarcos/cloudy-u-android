package app.cloudnotes.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
    @Test fun textTypesGoToTheEditor() {
        for (m in listOf("text/plain", "text/markdown", "text/x-foo", "application/json", "application/xml", "application/ld+json", "TEXT/PLAIN; charset=utf-8"))
            assertEquals(m, OpenRoute.TEXT, OpenRouter.route(m, "x.bin"))
    }

    @Test fun mediaAndPdfOpenInTheViewer() {
        assertEquals(OpenRoute.IMAGE, OpenRouter.route("image/png", null))
        assertEquals(OpenRoute.PDF, OpenRouter.route("application/pdf", "a.txt"))
        assertEquals(OpenRoute.AUDIO, OpenRouter.route("audio/mpeg", null))
        assertEquals(OpenRoute.VIDEO, OpenRouter.route("video/mp4", null))
    }

    @Test fun otherTypedBinaryFallsToTheFileView() {
        assertEquals(OpenRoute.BINARY, OpenRouter.route("application/zip", "a.txt"))
        assertEquals(OpenRoute.BINARY, OpenRouter.route("application/x-weird", "photo.png"))
    }

    @Test fun genericTypeIsDecidedByTheName() {
        for (m in listOf(null, "", "application/octet-stream", "*/*")) {
            assertEquals(OpenRoute.TEXT, OpenRouter.route(m, "notes.MD"))
            assertEquals(OpenRoute.IMAGE, OpenRouter.route(m, "a.jpeg"))
            assertEquals(OpenRoute.PDF, OpenRouter.route(m, "a.pdf"))
            assertEquals(OpenRoute.AUDIO, OpenRouter.route(m, "a.flac"))
            assertEquals(OpenRoute.VIDEO, OpenRouter.route(m, "a.mkv"))
            assertEquals(OpenRoute.BINARY, OpenRouter.route(m, "a.bin"))
        }
    }

    @Test fun neverThrowsOnUnknownOrEmptyInput() {
        for (m in listOf(null, "", " ", "/", "garbage", "a/", "/b", ";", "x/y;;;", "\u0000"))
            for (n in listOf(null, "", ".", "..", "noext", ".hidden", "a."))
                OpenRouter.route(m, n)
        assertEquals(OpenRoute.BINARY, OpenRouter.route(null, null))
        assertEquals(OpenRoute.BINARY, OpenRouter.route("", ""))
    }

    @Test fun editOnAWritableContentUriSavesBack() {
        val edit = "android.intent.action.EDIT"
        assertTrue(OpenRouter.canWriteBack(edit, "content", 2))
        assertFalse(OpenRouter.canWriteBack(edit, "content", 1))
        assertFalse(OpenRouter.canWriteBack(edit, "file", 2))
        assertFalse(OpenRouter.canWriteBack("android.intent.action.VIEW", "content", 2))
        assertFalse(OpenRouter.canWriteBack(null, null, 0))
    }

    @Test fun sizeAndHexPreview() {
        assertEquals("0 B", OpenRouter.humanSize(0))
        assertEquals("1.5 KiB", OpenRouter.humanSize(1536))
        assertEquals("unknown", OpenRouter.humanSize(-1))
        assertEquals("", OpenRouter.hexPreview(ByteArray(0)))
        assertTrue(OpenRouter.hexPreview("Hi\u0000".toByteArray()).startsWith("00000000  48 69 00"))
    }

    // #1949 a PDF with a generic type and no extension is recognised by its first bytes.
    @Test fun pdfMagicIsRecognised() {
        assertTrue(OpenRouter.isPdfMagic("%PDF-1.7".toByteArray()))
        assertFalse(OpenRouter.isPdfMagic("PK\u0003\u0004zip".toByteArray()))
        assertFalse(OpenRouter.isPdfMagic(ByteArray(2)))
        assertEquals(5, OpenRouter.readHead(java.io.ByteArrayInputStream("%PDF-1.7".toByteArray()), 5).size)
    }
}

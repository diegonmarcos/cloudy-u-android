package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudbrowser.textopen.TextIntents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1642 which files the browser shows as text, and that the page never carries the file's markup. */
class TextIntentsTest {
    @Test fun textTypesAreText() {
        for (m in listOf("text/plain", "text/markdown", "text/csv", "text/xml", "application/json", "application/xml", "application/ld+json"))
            assertTrue(m, TextIntents.isText(m, null))
    }

    @Test fun genericTypeFallsBackToTheExtension() {
        for (n in listOf("a.txt", "a.md", "a.log", "a.json", "a.csv", "a.conf", "a.yml", "a.xml", "A.TXT"))
            assertTrue(n, TextIntents.isText("application/octet-stream", n))
        assertTrue(TextIntents.isText(null, "notes.md"))
    }

    @Test fun binaryIsNeverText() {
        assertFalse(TextIntents.isText("image/png", "a.txt"))
        assertFalse(TextIntents.isText("application/pdf", "a.pdf"))
        assertFalse(TextIntents.isText("application/octet-stream", "a.apk"))
        assertFalse(TextIntents.isText("application/x-bittorrent", "a.torrent"))
        assertFalse(TextIntents.isText("multipart/related", "a.mhtml"))
    }

    @Test fun pageEscapesMarkup() {
        val p = TextIntents.page("t", "<script>alert(1)</script>&", false)
        assertFalse(p.contains("<script>"))
        assertTrue(p.contains("&lt;script&gt;"))
    }
}

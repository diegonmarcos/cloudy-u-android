package app.sterna.ui.message

import app.sterna.core.data.mail.InlineImageFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** #1869 step 6: the reader WebView serves a file-stored inline image on its `cid:` request, and nothing else. */
class InlineCid1869Test {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    @Test fun `a cid with a stored file is served with its type, either spelling`() {
        val root = tmp.newFolder("offline-attachments")
        val ref = InlineImageFiles.write(root, "acc", "m1", "k", "image/png", bytes)!!
        val images = mapOf("logo@x" to ref)
        val (type, stream) = InlineCid.respond(root, images, "logo@x")!!
        assertEquals("image/png", type)
        assertArrayEquals(bytes, stream.use { it.readBytes() })
        assertArrayEquals(bytes, InlineCid.respond(root, images, "<logo@x>")!!.second.use { it.readBytes() })
    }

    @Test fun `a data URI, an unknown cid, a missing root or file is not served`() {
        val root = tmp.newFolder("offline-attachments")
        val ref = InlineImageFiles.write(root, "acc", "m1", "k", "image/png", bytes)!!
        assertNull(InlineCid.respond(root, mapOf("a" to "data:image/png;base64,AAAA"), "a"))
        assertNull(InlineCid.respond(root, mapOf("a" to ref), "b"))
        assertNull(InlineCid.respond(null, mapOf("a" to ref), "a"))
        assertNull(InlineCid.respond(root, mapOf("a" to ref), null))
        assertNull(InlineCid.respond(root, mapOf("a" to InlineImageFiles.ref("image/png", "../../etc/passwd")), "a"))
    }

    @Test fun `concurrent requests from the WebView's threads each get the right bytes`() {
        val root = tmp.newFolder("offline-attachments")
        val images = (0 until 16).associate { i -> "c$i" to InlineImageFiles.write(root, "acc", "m1", "k$i", "image/png", byteArrayOf(i.toByte()))!! }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 400).map { n -> pool.submit(Callable { InlineCid.respond(root, images, "c${n % 16}")!!.second.use { it.readBytes() }.single().toInt() to n % 16 }) }
            jobs.forEach { val (got, want) = it.get(10, TimeUnit.SECONDS); assertEquals(want, got) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `a file ref keeps its cid reference in the reader document, a data URI is embedded`() {
        val ref = InlineImageFiles.ref("image/png", "acc/m1/cid-k")
        val out = embedInlineImages("<img src=\"cid:a\"><img src=\"cid:b\">", mapOf("a" to ref, "b" to "data:image/gif;base64,R0lG"))
        assertEquals("<img src=\"cid:a\"><img src=\"data:image/gif;base64,R0lG\">", out)
    }
}

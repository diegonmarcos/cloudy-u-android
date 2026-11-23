package app.sterna.ui.message

import app.sterna.core.data.mail.ReaderFragment
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * #1869 step 7: the fragment stored with a body is EXACTLY what the one sanitiser policy answers, so the reader document built
 * from a stored fragment is byte-identical to the one built from the html (golden), and a stored fragment really reaches
 * the document (the memo is seeded from Room, the sanitiser does not run on a cached open).
 */
class StoredFragment1869Test {
    private fun email(id: String, html: String?, text: String? = null): Email {
        val parts = listOfNotNull(html?.let { EmailBodyPart(partId = "h", type = "text/html") to it }, text?.let { EmailBodyPart(partId = "t", type = "text/plain") to it })
        return Email(
            id = id,
            htmlBody = parts.filter { it.first.type == "text/html" }.map { it.first },
            textBody = parts.filter { it.first.type == "text/plain" }.map { it.first },
            bodyValues = parts.associate { (p, v) -> p.partId!! to EmailBodyValue(value = v) },
        )
    }

    private val newsletter = """
        <html><head><style>@media (prefers-color-scheme: dark) { body { background:#000 } }</style>
        <script>alert(1)</script></head><body onload="x()"><table><tr><td><img src="cid:logo@x"></td>
        <td><a href="https://example.com/a">example.com</a></td></tr></table><blockquote>older</blockquote></body></html>
    """.trimIndent()

    @Test fun `the stored fragment is the policy's answer, cid references kept`() {
        val fragment = ReaderFragment.build(email("g1", newsletter))!!
        assertEquals(app.sterna.core.data.text.sanitiseReceivedHtml(newsletter), fragment)
        assertTrue("cid: is kept for the reader to embed or intercept", fragment.contains("cid:logo@x"))
        assertTrue(!fragment.contains("<script"))
        assertNull("a text-only body has no stored fragment", ReaderFragment.build(email("g2", null, text = "plain")))
    }

    @Test fun `golden - the document from a stored fragment equals the document from the html`() {
        val fresh = buildHtmlDocument(email("g3", newsletter), noContent = "none", quoteLabel = "Quoted", deceptiveLinkLabel = "goes to %s")
        val stored = ReaderFragment.build(email("g3", newsletter))!!
        ReaderFragment.remember(email("g3", newsletter), stored)
        val fromStored = buildHtmlDocument(email("g3", newsletter), noContent = "none", quoteLabel = "Quoted", deceptiveLinkLabel = "goes to %s")
        assertEquals(fresh, fromStored)
    }

    @Test fun `a stored fragment reaches the document without the sanitiser`() {
        val html = "<p>source-only-${System.nanoTime()}</p>"
        ReaderFragment.remember(email("s1", html), "<p>FROM-ROOM</p>")
        val doc = buildHtmlDocument(email("s1", html), noContent = "none")
        assertTrue("the seeded fragment is what the page shows", doc.contains("FROM-ROOM"))
        assertTrue(!doc.contains("source-only-"))
    }

    /**
     * The stored fragment is trusted only under [ReaderFragment.VERSION]. A change to the sanitiser policy that keeps the
     * version would serve fragments made by the OLD policy forever. This hash pins the policy source: when it changes,
     * bump [ReaderFragment.VERSION] (rows are then rebuilt lazily, never purged) and update both values here.
     */
    @Test fun `the policy source and the fragment version move together`() {
        val src = listOf("core/data/src/main/kotlin/app/sterna/core/data/text/ReceivedHtml.kt", "../core/data/src/main/kotlin/app/sterna/core/data/text/ReceivedHtml.kt")
            .map(::File).first { it.isFile }
        val sha = MessageDigest.getInstance("SHA-256").digest(src.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("83958acebd46f0bf084ab3dc5f5bd4f81391db0eab5bbe982d6ba0634cd03c09", sha)
        assertEquals(1, ReaderFragment.VERSION)
    }
}

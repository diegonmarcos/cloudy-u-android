package app.sterna.ui.compose

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.StoredSignature
import app.sterna.core.data.text.composeOpeningHtml
import app.sterna.core.data.text.composeSignatureHtml
import app.sterna.core.data.text.htmlForWire
import app.sterna.core.data.text.linkifiedSignatureHtml
import app.sterna.core.data.text.signatureEditorBlock
import app.sterna.core.data.text.signatureHtmlCandidates
import app.sterna.core.data.text.signatureHtmlTwin
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * #1800: the signature the composer inserts by default is the HTML version, with its links, and the
 * message leaves as multipart/alternative (text/html + generated text/plain).
 *
 * The reported case: From an address whose "Sincerely," signature reached the body as plain text, the
 * four labelled links ("Whatsapp | LinkedIn | E-mail | GitHub") as bare words. That is the shape of a
 * text-only copy flattened before link targets were kept, while the HTML version is stored for the
 * same address (here: on the server identity the manual one shadows).
 */
class ComposeHtmlSignatureDefaultTest {

    private val html =
        "Sincerely,<br>" +
            "Diego C. Marcos<br>" +
            """<a href="https://linktree.diegonmarcos.com">https://linktree.diegonmarcos.com</a><br>""" +
            """<a href="https://wa.me/34600000000">Whatsapp</a> | """ +
            """<a href="https://www.linkedin.com/in/diegonmarcos">LinkedIn</a> | """ +
            """<a href="mailto:me@example.org">E-mail</a> | """ +
            """<a href="https://github.com/diegonmarcos">GitHub</a>"""

    /** What the phone showed: the old flattening, links gone. */
    private val bareText =
        "Sincerely,\nDiego C. Marcos\nhttps://linktree.diegonmarcos.com\nWhatsapp | LinkedIn | E-mail | GitHub"

    private val hrefs = listOf(
        "https://linktree.diegonmarcos.com",
        "https://wa.me/34600000000",
        "https://www.linkedin.com/in/diegonmarcos",
        "mailto:me@example.org",
        "https://github.com/diegonmarcos",
    )

    private val account = StoredAccount(
        id = "a1",
        server = "https://mail.example.org",
        accountName = "Me",
        username = "me@example.org",
        identities = listOf(
            StoredIdentity(
                id = "manual", name = "Me", email = "me@example.org",
                signatures = listOf(StoredSignature(id = "s1", name = "Sincerely,", text = bareText)),
            ),
        ),
        serverIdentities = listOf(
            StoredIdentity(id = "srv", name = "Me", email = "ME@example.org", signature = bareText, signatureHtml = html),
        ),
    )

    @Test fun `a signature with its own html inserts that html, every link kept`() {
        val sig = StoredSignature.of("s1", "Sincerely,", html)
        val block = signatureEditorBlock(composeSignatureHtml(sig, emptyList()), sig.text, delimiter = true)
        for (h in hrefs) assertTrue("missing link $h in $block", block.contains("href=\"$h\""))
        assertFalse("the editor gets markup, not the flattened text", block.contains("Whatsapp &lt;"))
    }

    @Test fun `a text-only copy recovers its html twin stored for the same address`() {
        val candidates = signatureHtmlCandidates(account, "me@example.org")
        assertEquals(listOf(html), candidates)
        val sig = account.identities.single().signatures.single()
        assertEquals(html, signatureHtmlTwin(sig.text, candidates))
        val inserted = composeSignatureHtml(sig, candidates)
        for (h in hrefs) assertTrue("missing link $h", inserted.contains("href=\"$h\""))
    }

    @Test fun `a twin of a different signature is never borrowed`() {
        val other = StoredSignature(id = "s2", name = "Short", text = "Best,\nD.")
        val inserted = composeSignatureHtml(other, signatureHtmlCandidates(account, "me@example.org"))
        assertFalse("another signature's html must not stand in", inserted.contains("Sincerely"))
        assertTrue(inserted.contains("Best,<br>D."))
    }

    @Test fun `a plain signature with no twin is inserted with its urls and addresses linked`() {
        val out = linkifiedSignatureHtml("Sincerely,\nhttps://linktree.diegonmarcos.com\nme@example.org <b>")
        assertTrue(out, out.contains("""<a href="https://linktree.diegonmarcos.com">https://linktree.diegonmarcos.com</a>"""))
        assertTrue(out, out.contains("""<a href="mailto:me@example.org">me@example.org</a>"""))
        assertTrue("text stays escaped", out.contains("&lt;b&gt;"))
        assertTrue(out.startsWith("Sincerely,<br>"))
    }

    @Test fun `no signature inserts nothing`() {
        assertEquals("", composeSignatureHtml(null, listOf(html)))
        assertEquals("", composeSignatureHtml(StoredSignature(id = "e"), listOf(html)))
    }

    // --- MIME: multipart/alternative with both parts --------------------------------------------

    private fun leaves(mime: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val re = Regex("Content-Type: (text/(?:plain|html))[^\r\n]*\r\nContent-Transfer-Encoding: base64\r\n\r\n([A-Za-z0-9+/=\r\n]+)")
        for (m in re.findAll(mime)) {
            out[m.groupValues[1]] = String(Base64.getMimeDecoder().decode(m.groupValues[2].trim()), Charsets.UTF_8)
        }
        return out
    }

    private fun mimeOf(plainTextMode: Boolean): String {
        val sig = signatureEditorBlock(composeSignatureHtml(account.identities.single().signatures.single(),
            signatureHtmlCandidates(account, "me@example.org")), bareText, delimiter = true)
        val editor = composeOpeningHtml("Hola <b>Ana</b>", sig, "", signatureBelowQuote = false)
            .replace("Hola &lt;b&gt;Ana&lt;/b&gt;", "Hola <b>Ana</b>")
        val (text, wire) = composeSendBodies(htmlForWire(editor), plainTextMode)
        return OutgoingMime.build(
            OutgoingMessage(
                from = "Me <me@example.org>", to = listOf("ana@example.org"), subject = "Hi",
                body = text, html = wire, messageId = "m1@example.org", dateMillis = 1_750_000_000_000L,
            ),
        )
    }

    @Test fun `a compose leaves as multipart alternative with an html and a generated plain part`() {
        val mime = mimeOf(plainTextMode = false)
        assertTrue(mime, mime.contains("Content-Type: multipart/alternative; boundary="))
        val parts = leaves(mime)
        val html = parts.getValue("text/html")
        val plain = parts.getValue("text/plain")
        assertTrue("formatting kept in the html part", html.contains("<b>Ana</b>"))
        for (h in hrefs) assertTrue("html part lost $h", html.contains("href=\"$h\""))
        assertTrue("plain part generated", plain.startsWith("Hola Ana"))
        assertTrue("plain part spells the link out: $plain", plain.contains("Whatsapp <https://wa.me/34600000000>"))
        assertFalse("no markup in the plain part", plain.contains("<a "))
    }

    @Test fun `plain-text mode still sends both parts, without formatting`() {
        val parts = leaves(mimeOf(plainTextMode = true))
        assertTrue(parts.containsKey("text/plain") && parts.containsKey("text/html"))
        assertFalse(parts.getValue("text/html").contains("<b>"))
    }
}

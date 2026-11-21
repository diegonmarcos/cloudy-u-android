package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the reader RENDERS, EXECUTED. There is no plain-text reading mode: a message with an html part
 * shows it, sanitised; a text-only message shows its text as escaped, linkified HTML in the same page.
 */
class ReaderBodyTest {

    private val noContent = "NO-CONTENT-SENTINEL"

    // --- which part is rendered ----------------------------------------------------------------

    @Test
    fun `a message with both parts renders the html one`() {
        val body = readerBody(email(text = part("t", "text/plain") to "plain words", html = part("h", "text/html") to "<p>Rich</p>"), noContent)
        assertTrue(body.richHtml)
        assertEquals("<p>Rich</p>", body.fragment)
    }

    @Test
    fun `a text-only message is escaped html in a pre`() {
        val body = readerBody(email(text = part("t", "text/plain") to "a < b & c > d"), noContent)
        assertFalse(body.richHtml)
        assertEquals("<pre class=\"plain\">a &lt; b &amp; c &gt; d</pre>", body.fragment)
    }

    @Test
    fun `a message with only a preview shows that, escaped`() {
        val withPreview = Email(id = "1", preview = "a < b")
        assertEquals("<p>a &lt; b</p>", readerBody(withPreview, noContent).fragment)
    }

    @Test
    fun `a message with nothing says so`() {
        assertEquals("<p>$noContent</p>", readerBody(Email(id = "1"), noContent).fragment)
        assertEquals("<p>$noContent</p>", readerBody(email(html = part("h", "text/html") to "   "), noContent).fragment)
    }

    @Test
    fun `the html part is sanitised before anything renders it`() {
        val body = readerBody(email(html = part("h", "text/html") to "<p onclick=\"x()\">Hi</p><script>x()</script>"), noContent)
        assertFalse(body.fragment, "script" in body.fragment || "onclick" in body.fragment)
        assertTrue(body.fragment, "Hi" in body.fragment)
    }

    // --- remote images -------------------------------------------------------------------------

    @Test
    fun `remote images are blocked only when the setting is off and nothing asked for them`() {
        assertTrue(showRemoteImages(manualShow = true, senderAllowed = false, autoLoad = false))
        assertTrue(showRemoteImages(manualShow = false, senderAllowed = true, autoLoad = false))
        assertTrue(showRemoteImages(manualShow = true, senderAllowed = true, autoLoad = false))
        assertFalse(showRemoteImages(manualShow = false, senderAllowed = false, autoLoad = false))
        // #1837: the setting ON (the default) loads them for any message.
        assertTrue(showRemoteImages(manualShow = false, senderAllowed = false, autoLoad = true))
    }

    // --- the document --------------------------------------------------------------------------

    private val light = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false)
    private val images = mapOf("logo" to "data:image/png;base64,AAAA")

    @Test
    fun `the dark-theme page invert follows the rendered body, not what the message has`() {
        val dark = EmailTheme(background = "#101010", text = "#eeeeee", link = "#8ab4f8", dark = true)
        val rich = buildHtmlDocument(email(html = part("h", "text/html") to "<p>Hi</p>"), theme = dark, noContent = noContent)
        assertTrue("rich HTML in dark theme is rendered light and the whole page inverted", "invert(1)" in rich)

        val text = buildHtmlDocument(email(text = part("t", "text/plain") to "Hi"), theme = dark, noContent = noContent)
        assertFalse("text we paint in the app's own colours must NOT be inverted", "invert(1)" in text)
        assertTrue("the text is painted with the app's dark surface", "background-color: #101010" in text)
    }

    @Test
    fun `the colour scheme of a text rendering follows the app, the message's html is pinned light`() {
        assertTrue("content=\"only light\"" in buildHtmlDocument(email(html = part("h", "text/html") to "<p>Hi</p>"), theme = light, noContent = noContent))
        assertTrue("content=\"light\"" in buildHtmlDocument(email(text = part("t", "text/plain") to "Hi"), theme = light, noContent = noContent))
    }

    @Test
    fun `the document's guards are on for both kinds of body`() {
        for (e in listOf(email(html = part("h", "text/html") to "<p>Hi</p>"), email(text = part("t", "text/plain") to "Hi"))) {
            val doc = buildHtmlDocument(e, theme = light, noContent = noContent)
            assertTrue("the CSP must be on", "default-src 'none'" in doc)
            assertTrue("frame-src 'none'" in doc)
            assertTrue("object-src 'none'" in doc)
        }
    }

    @Test
    fun `a dark-mode media query in the message TEXT reaches the reader untouched`() {
        val css = "@media (prefers-color-scheme: dark) { body { background: #000 } }"
        val asText = buildHtmlDocument(email(text = part("t", "text/plain") to css), theme = light, noContent = noContent)
        assertTrue("the reader's own text was rewritten: $asText", css in asText)
        assertFalse("max-width:0px" in asText)

        val asHtml = buildHtmlDocument(
            email(html = part("h", "text/html") to "<style>@media (prefers-color-scheme: dark){body{background:#000}}</style>"),
            theme = light, noContent = noContent,
        )
        assertTrue("prefers-color-scheme:dark) and (max-width:0px" in asHtml)
    }

    @Test
    fun `a cid reference in the message TEXT stays text and is not turned into an image`() {
        val asText = buildHtmlDocument(
            email(text = part("t", "text/plain") to "the logo lives at cid:logo, ask me"),
            inlineImages = images, theme = light, noContent = noContent,
        )
        assertTrue("the reader's own text was rewritten: $asText", "at cid:logo, ask" in asText)
        assertFalse("data:image/png" in asText)

        val asHtml = buildHtmlDocument(
            email(html = part("h", "text/html") to "<img src=\"cid:logo\">"),
            inlineImages = images, theme = light, noContent = noContent,
        )
        assertTrue("<img src=\"data:image/png;base64,AAAA\">" in asHtml)
    }

    // --- source checks (the weakest things in this file) ---------------------------------------

    @Test
    fun `both remote-image sites ask the same three-term decision`() {
        val calls = lines(messageScreenSource(), """val showRemote = showRemoteImages\(manualShow, senderAllowed, autoLoad\)""")
        assertEquals("the two showRemote sites must both call showRemoteImages(manualShow, senderAllowed, autoLoad)", 2, calls)
        assertFalse(
            "a site still computes the remote-image verdict itself",
            Regex("""showRemote\s*=\s*manualShow""").containsMatchIn(messageScreenSource()),
        )
    }

    @Test
    fun `no plain-text reading mode survives in the reader`() {
        val source = messageScreenSource()
        for (gone in listOf("plainText", "imageMode", "readingMode", "setPlainText", "message_show_plain_text", "message_show_html", "derivedNotice")) {
            assertFalse("MessageScreen still names `$gone`", gone in source)
        }
    }

    @Test
    fun `both Composable sites resolve the no-content string and hand it to the document`() {
        val source = messageScreenSource()
        assertEquals(
            "the menu and the page must each resolve message_no_content - a literal handed to " +
                "readerBody is a reader that says '(no content)' in nine languages",
            2,
            lines(source, """val noContent = stringResource\(R\.string\.message_no_content\)"""),
        )
        assertEquals(
            "the page must pass noContent to buildHtmlDocument AND keep it in the remember key",
            2,
            lines(source, """noContent, quoteLabel, deceptiveLinkLabel,"""),
        )
        assertEquals(
            "the page must RESOLVE message_quoted_text",
            1,
            lines(source, """val quoteLabel = stringResource\(R\.string\.message_quoted_text\)"""),
        )
    }

    private fun messageScreenSource(): String =
        File(repoRoot(), "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

    private fun lines(source: String, pattern: String): Int =
        Regex("^[ \t]*$pattern[ \t]*$", RegexOption.MULTILINE).findAll(source).count()

    private fun part(id: String, type: String) = EmailBodyPart(partId = id, type = type)

    private fun email(
        text: Pair<EmailBodyPart, String>? = null,
        html: Pair<EmailBodyPart, String>? = null,
    ): Email = Email(
        id = "1",
        textBody = listOfNotNull(text?.first),
        htmlBody = listOfNotNull(html?.first),
        bodyValues = listOfNotNull(text, html)
            .associate { (p, v) -> p.partId!! to EmailBodyValue(value = v) },
    )

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
}

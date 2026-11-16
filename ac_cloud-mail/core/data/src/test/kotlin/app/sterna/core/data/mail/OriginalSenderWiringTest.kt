package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the origin header (#160) is READ and where the body is CACHED — the two halves of the
 */
class OriginalSenderWiringTest {

    // --- 1. executed: what may be written to the body cache -------------------------------------

    /**
     * The row that costs the feature: a JMAP read that FAILED must not be cached. `openMessage`
     */
    @Test fun `a failed JMAP origin read is never written to the cache`() {
        assertEquals(false, cachesBodyAfterOriginRead(MailProtocol.JMAP, originReadSucceeded = false))
    }

    /** A JMAP read that happened is cached, whatever it found — including "no such header". */
    @Test fun `a JMAP read that happened is cached`() {
        assertEquals(true, cachesBodyAfterOriginRead(MailProtocol.JMAP, originReadSucceeded = true))
    }

    /**
     * IMAP caches either way: there the header came off the message source already in hand, so
     */
    @Test fun `IMAP caches its body either way`() {
        assertEquals(true, cachesBodyAfterOriginRead(MailProtocol.IMAP, originReadSucceeded = true))
        assertEquals(true, cachesBodyAfterOriginRead(MailProtocol.IMAP, originReadSucceeded = false))
    }

    // --- 2. read as source text: the call sites --------------------------------------------------

    /**
     * The IMAP pose, whole. It rides on the source `openEmailImap` already fetched, and it reads
     */
    @Test fun `the IMAP open poses the origin header off the source it already holds`() {
        // The `rawHeaders` call moved onto its own line when the Autocrypt import started reading
        // the SAME list, so both lines are pinned: what feeds the reader has to stay `rawHeaders`,
        // never `headerOf`, and `headers` has to be the list this very source produced.
        assertEquals(
            "openEmailImap must set originalSender from the raw source it already has. Found:",
            listOf(
                "val headers = MimeParser.rawHeaders(raw)",
                "originalSender = originalSenderHeaderOf(headers),",
            ),
            codeLines(bodyOf("openEmailImap"))
                .filter { it.startsWith("originalSender") || it.startsWith("val headers =") },
        )
    }

    /**
     * And `openEmail` must NOT read it. Three callers reach it (`openMessage`, `rawHeaders`,
     */
    @Test fun `the plain message fetch does not pay for the origin header`() {
        assertEquals(
            "openEmail must not read the origin header; openMessage does it, where a failure can " +
                "still stop the body being cached. Lines found:",
            emptyList<String>(),
            codeLines(bodyOf("openEmail")).filter { "originalSender" in it || "originReadFor(" in it },
        )
    }

    /**
     * The JMAP read and the cache decision live together in `openMessage`, and the persist is
     * GUARDED by the decision. An unguarded `persistBody` here is the defect this file exists for.
     */
    @Test fun `the open reads the origin and only caches a body whose read happened`() {
        val body = codeLines(bodyOf("openMessage"))

        assertEquals(
            "openMessage must obtain the origin through originReadFor(credentials, emailId, email)",
            listOf("val origin = originReadFor(credentials, emailId, email)"),
            body.filter { "originReadFor(" in it },
        )
        assertEquals(
            "…show it on the email it returns",
            listOf("val shown = email.copy(originalSender = origin.header)"),
            body.filter { "originalSender = origin" in it },
        )
        assertEquals(
            "…and persist ONLY under cachesBodyAfterOriginRead. An unguarded persistBody caches a " +
                "failed read as 'no origin', for ever. Found:",
            listOf(
                "if (cachesBodyAfterOriginRead(credentials.protocol, origin.succeeded)) {",
                "persistBody(credentials.id, emailId, shown, inline)",
            ),
            body.filter { "cachesBodyAfterOriginRead(" in it || it.startsWith("persistBody(") },
        )
    }

    /**
     * The prefetch: ONE grouped read for the window, and nothing persisted at all when it fails.
     */
    @Test fun `the prefetch reads the whole window at once and abandons it on failure`() {
        // The fetch moved out of `prefetchInboxBodies` into the one function every prefetch (list, neighbours) shares (#1763).
        val body = codeLines(bodyOf("fetchAndStoreBodies"))

        assertEquals(
            "one grouped read for the ids the prefetch is about to cache",
            listOf("val origins = originalSendersOf(ctx, emails) ?: return"),
            body.filter { "originalSendersOf(" in it },
        )
        assertTrue(
            "the grouped read must be able to say it FAILED — a plain map cannot, and null would " +
                "then be cached as 'no origin' on the whole window. Found: " + body,
            body.any { it == "val origins = originalSendersOf(ctx, emails) ?: return" },
        )
        assertEquals(
            "…and each body is persisted carrying what that read found",
            listOf("val withOrigin = email.copy(originalSender = origins[email.id])"),
            body.filter { "origins[" in it },
        )
    }

    // --- reading the source ----------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }
}

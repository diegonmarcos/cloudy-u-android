package app.sterna.core.data.mail

import app.sterna.core.data.filter.SourceText
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1869 step 10: the tap-time handoff LRU; it never holds a decrypted body. */
class ReaderHandoff1869Test {
    private var now = 1_000L
    private val email = Email(id = "m1")
    private fun hit(crypto: MessageCrypto? = null) = ReaderSnapshot.Hit(email, MessageBody(email, emptyMap(), crypto), null, null)

    @Test fun `a plain hit is held once, then taken`() {
        val h = ReaderHandoff(clock = { now })
        val s = hit()
        assertTrue(h.offer("a", "m1", s))
        assertTrue(h.ready("a", "m1"))
        assertTrue("a null account matches the current account's message", h.ready(null, "m1"))
        assertFalse(h.ready("b", "m1"))
        assertSame(s, h.take("a", "m1"))
        assertNull("taken once", h.take("a", "m1"))
        assertFalse(h.ready("a", "m1"))
    }

    @Test fun `a decrypted body is refused, the Locked card of ciphertext is held, a miss is not held`() {
        val h = ReaderHandoff(clock = { now })
        val decrypted = MessageCrypto.Decrypted(PgpSignatureState.NONE, null, 0L, wasEncrypted = true)
        assertFalse("plaintext of encrypted mail never enters the handoff", h.offer("a", "dec", hit(decrypted)))
        assertNull(h.take("a", "dec"))
        assertFalse(h.ready("a", "dec"))
        assertTrue(h.offer("a", "lock", hit(MessageCrypto.Locked(CryptoKind.PGP_ENCRYPTED))))
        assertFalse(h.offer("a", "miss", ReaderSnapshot.Miss(email, null, null)))
        assertNull(h.take("a", "miss"))
    }

    @Test fun `only Locked or no crypto is holdable`() {
        val src = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/ReaderHandoff.kt")
        assertTrue(src.contains("fun holdable(body: MessageBody): Boolean = body.crypto == null || body.crypto is MessageCrypto.Locked"))
        assertTrue(src.contains("if (!holdable(hit.body)) return false"))
    }

    @Test fun `a stale snapshot is not served`() {
        val h = ReaderHandoff(ttlMillis = 5_000, clock = { now })
        h.offer("a", "m1", hit())
        now += 5_001
        assertFalse(h.ready("a", "m1"))
        assertNull(h.take("a", "m1"))
    }

    @Test fun `the LRU keeps the last few taps`() {
        val h = ReaderHandoff(capacity = 2, clock = { now })
        h.offer("a", "1", hit()); h.offer("a", "2", hit()); h.offer("a", "3", hit())
        assertNull(h.take("a", "1"))
        assertTrue(h.take("a", "3") != null)
    }

    @Test fun `the page's snapshot takes the handoff first, and the preload reads locally only`() {
        val repo = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue(repo.contains("readerHandoff.take(accountId, emailId) ?: LocalReaderStore(localReaderPort).snapshot(accountId, emailId)"))
        val preload = repo.substringAfter("fun preloadReader(accountId: String?, emailId: String) {").substringBefore("\n    }\n")
        assertTrue(preload.contains("readerHandoff.offer(account, emailId, LocalReaderStore(localReaderPort).snapshot(account, emailId))"))
        assertFalse(preload.contains("credentials") || preload.contains("connect("))
        assertEquals(1, Regex("bgScope\\.launch").findAll(preload).count())
    }
}

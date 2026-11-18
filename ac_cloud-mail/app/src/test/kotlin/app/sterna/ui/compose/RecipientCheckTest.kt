package app.sterna.ui.compose

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1803 the wire-independent half of recipient deliverability: syntax, verdicts, merge, cache, one round. */
class RecipientCheckTest {

    private fun server(address: String, vararg lines: Pair<RecipientCheckKind, CheckOutcome>, verdict: RecipientVerdict? = null): RecipientStatus {
        val map = lines.toMap()
        val checks = RecipientCheckKind.entries.map { RecipientCheckItem(it, map[it] ?: CheckOutcome.PASS) }
        return RecipientStatus(address, verdict ?: verdictOf(checks), checks, 1_000L, fromServer = true)
    }

    // -- syntax ---------------------------------------------------------------------------------

    @Test fun `well formed addresses pass the phone's syntax check`() {
        listOf("a@b.co", "first.last+tag@sub.example.org", "x_y-z@xn--bcher-kva.example", "ü@bücher.de").forEach {
            assertNull(it, recipientSyntaxProblem(it))
            assertEquals(CheckOutcome.PASS, localSyntaxItem(it).outcome)
            assertEquals(CheckSource.PHONE, localSyntaxItem(it).source)
        }
    }

    @Test fun `each malformed shape is named for what is wrong with it`() {
        val cases = mapOf(
            "" to SyntaxProblem.EMPTY,
            "a b@c.de" to SyntaxProblem.SPACE,
            "nobody.example" to SyntaxProblem.NO_AT,
            "@example.org" to SyntaxProblem.NO_AT,
            "a@b@c.de" to SyntaxProblem.NO_AT,
            "a".repeat(65) + "@example.org" to SyntaxProblem.LOCAL_TOO_LONG,
            "a@" + listOf("b", "c", "d", "e").joinToString(".") { it.repeat(63) } + ".org" to SyntaxProblem.TOO_LONG,
            ".a@example.org" to SyntaxProblem.LOCAL_DOTS,
            "a.@example.org" to SyntaxProblem.LOCAL_DOTS,
            "a..b@example.org" to SyntaxProblem.LOCAL_DOTS,
            "a@localhost" to SyntaxProblem.DOMAIN_NO_DOT,
            "a@example..org" to SyntaxProblem.DOMAIN_DOTS,
            "a@example.org." to SyntaxProblem.DOMAIN_DOTS,
            "a@" + "b".repeat(64) + ".org" to SyntaxProblem.DOMAIN_LABEL_TOO_LONG,
            "a@-example.org" to SyntaxProblem.DOMAIN_HYPHEN,
            "a@exam_ple.org" to SyntaxProblem.DOMAIN_CHARACTER,
            "a@example.c" to SyntaxProblem.TLD,
            "a@example.123" to SyntaxProblem.TLD,
        )
        cases.forEach { (address, problem) ->
            assertEquals(address, problem, recipientSyntaxProblem(address))
            val item = localSyntaxItem(address)
            assertEquals(CheckOutcome.FAIL, item.outcome)
            assertEquals(problem.name, item.detail)
        }
    }

    @Test fun `every address the phone's check passes is also one Send already accepts`() {
        listOf("a@b.co", "first.last+tag@sub.example.org", "a@b.cd").forEach {
            assertNull(recipientSyntaxProblem(it))
            assertTrue(app.sterna.util.isValidEmail(it))
        }
    }

    // -- verdicts -------------------------------------------------------------------------------

    @Test fun `any failure fails, any warning is risky, any unknown is unknown, all passes are ok`() {
        fun v(vararg o: CheckOutcome) = verdictOf(o.mapIndexed { i, x -> RecipientCheckItem(RecipientCheckKind.entries[i], x) })
        assertEquals(RecipientVerdict.OK, v(CheckOutcome.PASS, CheckOutcome.PASS))
        assertEquals(RecipientVerdict.RISKY, v(CheckOutcome.PASS, CheckOutcome.WARN, CheckOutcome.UNKNOWN))
        assertEquals(RecipientVerdict.WILL_FAIL, v(CheckOutcome.WARN, CheckOutcome.FAIL))
        assertEquals(RecipientVerdict.UNKNOWN, v(CheckOutcome.PASS, CheckOutcome.UNKNOWN))
        assertEquals(RecipientVerdict.UNKNOWN, verdictOf(emptyList()))
    }

    @Test fun `a disposable or role hint is at most risky, never a failure`() {
        val checks = RecipientCheckKind.entries.map {
            RecipientCheckItem(it, if (it == RecipientCheckKind.DISPOSABLE_ROLE) CheckOutcome.FAIL else CheckOutcome.PASS)
        }
        assertEquals(RecipientVerdict.RISKY, verdictOf(checks))
    }

    @Test fun `the phone alone gives a syntax line and leaves every other line unknown`() {
        val s = localRecipientStatus("a@b.co", pending = false, nowMillis = 5)
        assertEquals(RecipientCheckKind.entries, s.checks.map { it.kind })
        assertEquals(CheckOutcome.PASS, s.check(RecipientCheckKind.SYNTAX).outcome)
        RecipientCheckKind.entries.drop(1).forEach { assertEquals(CheckOutcome.UNKNOWN, s.check(it).outcome) }
        assertEquals(RecipientVerdict.UNKNOWN, s.verdict)
        assertFalse(s.fromServer)
        assertEquals(RecipientVerdict.CHECKING, localRecipientStatus("a@b.co", pending = true, nowMillis = 5).verdict)
        assertEquals(RecipientVerdict.WILL_FAIL, localRecipientStatus("a@b", pending = true, nowMillis = 5).verdict)
    }

    // -- merge ----------------------------------------------------------------------------------

    @Test fun `the merge keeps every line, in order, and fills what the server left out as unknown`() {
        val partial = RecipientStatus(
            "a@b.co", RecipientVerdict.OK,
            listOf(RecipientCheckItem(RecipientCheckKind.DOMAIN, CheckOutcome.PASS)), fromServer = true,
        )
        val merged = mergeRecipientStatus("a@b.co", partial, nowMillis = 9)
        assertEquals(RecipientCheckKind.entries, merged.checks.map { it.kind })
        assertEquals(CheckOutcome.PASS, merged.check(RecipientCheckKind.SYNTAX).outcome)
        assertEquals(CheckSource.PHONE, merged.check(RecipientCheckKind.SYNTAX).source)
        assertEquals(CheckOutcome.UNKNOWN, merged.check(RecipientCheckKind.MAILBOX).outcome)
        assertEquals("the server's own verdict is kept", RecipientVerdict.OK, merged.verdict)
        assertTrue(merged.fromServer)
    }

    @Test fun `the phone's syntax failure wins over whatever the server said`() {
        val merged = mergeRecipientStatus("a@b", server("a@b", verdict = RecipientVerdict.OK), nowMillis = 9)
        assertEquals(RecipientVerdict.WILL_FAIL, merged.verdict)
        assertEquals(CheckSource.PHONE, merged.check(RecipientCheckKind.SYNTAX).source)
    }

    @Test fun `a server verdict is never better than its own failing line`() {
        val merged = mergeRecipientStatus(
            "a@b.co", server("a@b.co", RecipientCheckKind.SIZE_LIMIT to CheckOutcome.FAIL, verdict = RecipientVerdict.RISKY), 9,
        )
        assertEquals(RecipientVerdict.WILL_FAIL, merged.verdict)
    }

    @Test fun `a catch-all mailbox comes through as risky with its flag`() {
        val remote = RecipientStatus(
            "a@b.co", RecipientVerdict.RISKY,
            RecipientCheckKind.entries.map {
                if (it == RecipientCheckKind.MAILBOX) RecipientCheckItem(it, CheckOutcome.WARN, acceptsAll = true)
                else RecipientCheckItem(it, CheckOutcome.PASS)
            },
            fromServer = true,
        )
        val merged = mergeRecipientStatus("a@b.co", remote, 1)
        assertEquals(RecipientVerdict.RISKY, merged.verdict)
        assertTrue(merged.check(RecipientCheckKind.MAILBOX).acceptsAll)
    }

    @Test fun `no answer from the server is unknown, with the reason kept`() {
        val merged = mergeRecipientStatus("a@b.co", null, 3, serverNote = "timeout")
        assertEquals(RecipientVerdict.UNKNOWN, merged.verdict)
        assertEquals("timeout", merged.serverNote)
        assertFalse(merged.fromServer)
    }

    // -- cache ----------------------------------------------------------------------------------

    @Test fun `the cache keys on the address case-insensitively and keeps each answer for its own time`() {
        val cache = RecipientStatusCache(settledTtlMillis = 100, riskyTtlMillis = 50, unknownTtlMillis = 10)
        cache.put(server("A@B.co"), nowMillis = 0, sizeBytes = null)
        cache.put(server("r@b.co", RecipientCheckKind.MAILBOX to CheckOutcome.WARN), 0, null)
        cache.put(localRecipientStatus("u@b.co", false, 0), 0, null)
        assertNotNull(cache.get("a@b.CO", 99, null))
        assertEquals("the caller's spelling is shown", "a@b.CO", cache.get("a@b.CO", 99, null)!!.address)
        assertNull("ok expires", cache.get("a@b.co", 100, null))
        assertNotNull(cache.get("r@b.co", 49, null))
        assertNull("risky expires sooner", cache.get("r@b.co", 50, null))
        assertNotNull(cache.get("u@b.co", 9, null))
        assertNull("unknown is asked again soon", cache.get("u@b.co", 10, null))
    }

    @Test fun `a message that grew past the size an answer was taken for is asked again`() {
        val cache = RecipientStatusCache()
        cache.put(server("a@b.co"), 0, sizeBytes = 1_000)
        assertNotNull(cache.get("a@b.co", 1, 1_000))
        assertNotNull(cache.get("a@b.co", 1, 900))
        assertNull(cache.get("a@b.co", 1, 1_001))
    }

    @Test fun `the send-time recheck skips answers older than its limit without forgetting them`() {
        val cache = RecipientStatusCache()
        cache.put(server("a@b.co"), 0, null)
        assertNull(cache.get("a@b.co", 61_000, null, maxAgeMillis = SEND_RECHECK_MAX_AGE_MILLIS))
        assertNotNull(cache.get("a@b.co", 61_000, null))
    }

    @Test fun `the cache drops its oldest entries past capacity, and forgets on request`() {
        val cache = RecipientStatusCache(capacity = 2)
        cache.put(server("1@b.co"), 0, null)
        cache.put(server("2@b.co"), 0, null)
        cache.put(server("3@b.co"), 0, null)
        assertEquals(2, cache.size())
        assertNull(cache.get("1@b.co", 1, null))
        cache.invalidate("3@B.CO")
        assertNull(cache.get("3@b.co", 1, null))
    }

    @Test fun `only distinct, well formed, uncached addresses are asked`() {
        val cache = RecipientStatusCache()
        cache.put(server("cached@b.co"), 0, null)
        assertEquals(
            listOf("new@b.co"),
            recipientsToAsk(listOf("new@b.co", "NEW@b.co", "cached@b.co", "bad@b", " "), cache, 1, null),
        )
    }

    // -- one round ------------------------------------------------------------------------------

    @Test fun `a round asks once, merges, caches, and answers every address`() = runTest {
        val cache = RecipientStatusCache()
        val asked = mutableListOf<RecipientCheckRequest>()
        val checker = RecipientChecker { req ->
            asked += req
            mapOf("ok@b.co" to server("ok@b.co"), "fail@b.co" to server("fail@b.co", RecipientCheckKind.DOMAIN to CheckOutcome.FAIL))
        }
        val out = runRecipientCheck(listOf("OK@b.co", "fail@b.co", "who@b.co", "bad@b"), 2_000, checker, cache, { 10L })
        assertEquals(1, asked.size)
        assertEquals(listOf("OK@b.co", "fail@b.co", "who@b.co"), asked.single().addresses)
        assertEquals(2_000L, asked.single().messageSizeBytes)
        assertEquals(RecipientVerdict.OK, out.getValue("ok@b.co").verdict)
        assertEquals(RecipientVerdict.WILL_FAIL, out.getValue("fail@b.co").verdict)
        assertEquals(RecipientVerdict.UNKNOWN, out.getValue("who@b.co").verdict)
        assertEquals(RecipientVerdict.WILL_FAIL, out.getValue("bad@b").verdict)
        assertEquals(listOf("fail@b.co", "bad@b"), willFailRecipients(listOf("OK@b.co", "fail@b.co", "who@b.co", "bad@b"), out))

        val again = runRecipientCheck(listOf("ok@b.co", "fail@b.co"), 2_000, checker, cache, { 20L })
        assertEquals("the cache answered the second round", 1, asked.size)
        assertEquals(RecipientVerdict.WILL_FAIL, again.getValue("fail@b.co").verdict)
    }

    @Test fun `a checker that cannot be reached leaves the addresses unknown with its reason`() = runTest {
        val cache = RecipientStatusCache()
        val out = runRecipientCheck(listOf("a@b.co"), null, { throw java.io.IOException("connect timed out") }, cache, { 1L })
        val s = out.getValue("a@b.co")
        assertEquals(RecipientVerdict.UNKNOWN, s.verdict)
        assertEquals("connect timed out", s.serverNote)
    }

    @Test fun `the unbound checker answers nothing, so nothing is claimed beyond syntax`() = runTest {
        val out = runRecipientCheck(listOf("a@b.co"), null, UnboundRecipientChecker, RecipientStatusCache(), { 1L })
        assertEquals(RecipientVerdict.UNKNOWN, out.getValue("a@b.co").verdict)
        assertEquals(CheckOutcome.PASS, out.getValue("a@b.co").check(RecipientCheckKind.SYNTAX).outcome)
    }

    @Test fun `a folded field shows its worst verdict`() {
        val ok = server("a@b.co")
        val risky = server("r@b.co", RecipientCheckKind.MAILBOX to CheckOutcome.WARN)
        assertNull(worstVerdict(emptyList()))
        assertEquals(RecipientVerdict.OK, worstVerdict(listOf(ok)))
        assertEquals(RecipientVerdict.CHECKING, worstVerdict(listOf(ok, null)))
        assertEquals(RecipientVerdict.RISKY, worstVerdict(listOf(ok, risky, null)))
        assertEquals(RecipientVerdict.WILL_FAIL, worstVerdict(listOf(localRecipientStatus("x@y", false, 0), risky)))
    }
}

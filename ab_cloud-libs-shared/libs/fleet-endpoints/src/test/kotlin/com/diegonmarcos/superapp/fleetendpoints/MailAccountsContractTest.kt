package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailAccountsContractTest {
    private val p = MailAccountsContract.Published("me@example.org", "jmap.example.org", "imap.example.org", 993, "smtp.example.org", 465,
        listOf(MailAccountsContract.Account("me@example.org", "S3CRET"), MailAccountsContract.Account("bot@example.org", null)))

    @Test fun `a document round-trips with and without a password`() {
        val back = MailAccountsContract.parse(MailAccountsContract.toJson(p))!!
        assertEquals(p, back)
        assertNull(back.accounts[1].password)
    }

    @Test fun `no log line can carry a password`() {
        assertFalse(p.toString().contains("S3CRET"))
        assertFalse(p.accounts[0].toString().contains("S3CRET"))
        assertTrue(p.accounts[0].toString().contains("<redacted>"))
    }

    @Test fun `anything that is not this version reads as nothing, never an exception`() {
        assertNull(MailAccountsContract.parse(null))
        assertNull(MailAccountsContract.parse("not json"))
        assertNull(MailAccountsContract.parse("""{"v":2,"accounts":[]}"""))
        assertEquals(0, MailAccountsContract.parse("""{"v":1,"accounts":[{"email":" "}]}""")!!.accounts.size)
    }

    @Test fun `a refusal names the reason and carries no value`() {
        assertTrue(MailAccountsContract.refusal("no grant for x").contains("no grant for x"))
        assertTrue(MailAccountsContract.refusal(null).contains("no reason given"))
    }
}

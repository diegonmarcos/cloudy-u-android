package com.diegonmarcos.superapp.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCredentialsTest {
    private fun c(v: String) = MusicCredentials.Cred(v, if (v.isBlank()) "not set" else "from Cloud Account")
    private val now = 1_000_000L

    @Test fun `ids are music service field`() = assertEquals("music.ytmusic.access", MusicCredentials.id("ytmusic", "access"))

    @Test fun `a token with an absolute expiry becomes seconds left, never zero for a live one`() {
        val t = MusicCredentials.assemble(c("cid"), c("sec"), c("AT"), c("RT"), c((now + 3_600_000).toString()), now) as MusicCredentials.Taken.Ok
        assertEquals(3600L, t.tokens.expiresInSec)
        assertEquals("RT", t.tokens.refresh); assertEquals("AT", t.tokens.access); assertEquals("cid", t.client)
        val soon = MusicCredentials.assemble(c("cid"), c("sec"), c("AT"), c(""), c((now + 10).toString()), now) as MusicCredentials.Taken.Ok
        assertEquals(1L, soon.tokens.expiresInSec); assertNull(soon.tokens.refresh)
    }

    @Test fun `an expiry of zero or garbage means the token does not expire`() {
        val z = MusicCredentials.assemble(c("cid"), c(""), c("AT"), c(""), c("0"), now) as MusicCredentials.Taken.Ok
        assertEquals(0L, z.tokens.expiresInSec)
        assertEquals(0L, (MusicCredentials.assemble(c("cid"), c(""), c("AT"), c(""), c("x"), now) as MusicCredentials.Taken.Ok).tokens.expiresInSec)
    }

    @Test fun `no access token is a sentence naming the fix`() {
        val m = MusicCredentials.assemble(c("cid"), c("sec"), c(""), c("RT"), c("0"), now) as MusicCredentials.Taken.Missing
        assertEquals("not set", m.line)
        assertEquals(MusicCredentials.UNWIRED, (MusicCredentials.assemble(c(""), c(""), MusicCredentials.Cred("", ""), c(""), c(""), now) as MusicCredentials.Taken.Missing).line)
    }

    @Test fun `no log form carries a value`() {
        val x = MusicCredentials.Cred("S3CRET", "l")
        assertFalse(x.toString().contains("S3CRET")); assertTrue(x.toString().contains("<redacted>"))
    }
}

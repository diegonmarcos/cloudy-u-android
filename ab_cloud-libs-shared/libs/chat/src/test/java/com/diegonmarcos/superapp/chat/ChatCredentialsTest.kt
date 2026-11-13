package com.diegonmarcos.superapp.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCredentialsTest {
    private val ok = ChatCredentials.Cred("me@example.org", "from Cloud Account")
    private val pw = ChatCredentials.Cred("S3CRET", "from Cloud Account")
    private val none = ChatCredentials.Cred("", "Mattermost password is not set: enter it in Cloud Account")

    @Test fun `both present means sign in`() = assertNull(ChatCredentials.usable(ok, pw))

    @Test fun `a missing login or password names the fix and is never an exception`() {
        assertEquals(none.line, ChatCredentials.usable(ok, none))
        assertEquals(none.line, ChatCredentials.usable(none, pw))
        assertEquals(ChatCredentials.UNWIRED, ChatCredentials.usable(ChatCredentials.Cred("", ""), pw))
    }

    @Test fun `the log form never carries the value`() {
        assertFalse(pw.toString().contains("S3CRET"))
        assertTrue(pw.toString().contains("<redacted>"))
    }

    @Test fun `the ids are the catalogue's`() {
        assertEquals("mattermost.login", ChatCredentials.ID_LOGIN)
        assertEquals("mattermost.password", ChatCredentials.ID_PASSWORD)
    }
}

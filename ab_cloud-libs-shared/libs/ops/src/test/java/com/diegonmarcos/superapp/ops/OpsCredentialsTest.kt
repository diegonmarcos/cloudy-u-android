package com.diegonmarcos.superapp.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsCredentialsTest {
    @Test fun `a present token is usable`() = assertNull(OpsCredentials.usable(OpsCredentials.Cred("tok", "from Cloud Account")))

    @Test fun `a missing token names the fix, never throws`() {
        assertEquals("Dagu token is not set", OpsCredentials.usable(OpsCredentials.Cred("", "Dagu token is not set")))
        assertEquals(OpsCredentials.UNWIRED, OpsCredentials.usable(OpsCredentials.Cred("  ", "")))
    }

    @Test fun `the log form never carries the value`() {
        val c = OpsCredentials.Cred("S3CRET", "l")
        assertFalse(c.toString().contains("S3CRET")); assertTrue(c.toString().contains("<redacted>"))
    }

    @Test fun `the id is the catalogue's`() = assertEquals("dagu.token", OpsCredentials.ID_DAGU_TOKEN)
}

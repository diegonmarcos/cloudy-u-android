package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.perms.DevVerifier.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1822 the checker: package present / enabled and the developer-verification allowance from sample shell outputs. Unknown never reads as allowed. */
class DevVerifierTest {
    private val now = 1_800_000_000_000L
    private val pkg = "package:com.google.android.verifier\n"
    private fun parse(present: String? = pkg, disabled: String? = "", global: String? = "adb_enabled=1\n", secure: String? = "", n: Long = now) =
        DevVerifier.parse(present, disabled, global, secure, n)

    @Test fun `not present, disabled and no shell`() {
        assertEquals(State.NOT_PRESENT, parse(present = "").state)
        assertEquals(State.NOT_PRESENT, parse(present = "package:com.google.android.verifier.other\n").state)   // a filter hit is not the package
        assertEquals(State.DISABLED, parse(disabled = pkg).state)
        assertEquals(State.UNKNOWN, parse(present = null).state)
        assertEquals(State.UNKNOWN, parse(present = "refused: pm list packages").state)   // a refusal is not "not installed"
        assertEquals(State.UNKNOWN, parse(global = null, secure = null).state)
    }

    @Test fun `allowed indefinitely, allowed until, pending, not allowed`() {
        assertEquals(State.ALLOWED_INDEFINITELY, parse(global = "adb_enabled=1\ndeveloper_verification_allowance=indefinitely\n").state)
        val until = parse(secure = "unverified_developers_allowed_until=${now + 1000}\n")
        assertEquals(State.ALLOWED_UNTIL, until.state); assertEquals(now + 1000, until.until)
        assertEquals(State.NOT_ALLOWED, parse(secure = "unverified_developers_allowed_until=${now - 1000}\n").state)
        val pend = parse(global = "developer_verification_pending_until=${now + 5000}\n")
        assertEquals(State.PENDING_24H, pend.state); assertEquals(now + 5000, pend.until)
        assertEquals(State.NOT_ALLOWED, parse(global = "developer_verification_allowed=0\n").state)
    }

    @Test fun `unknown is never green`() {
        val none = parse()
        assertEquals(State.UNKNOWN, none.state); assertTrue(none.detail.contains("unconfirmed"))      // absent key is not "not allowed"
        assertEquals(State.UNKNOWN, parse(global = "developer_verification_mode=3\n").state)            // value not understood
        assertEquals(State.UNKNOWN, parse(global = "developer_verification_pending_until=${now - 1}\n").state)
        for (r in listOf(none, parse(present = null), parse(global = "developer_verification_mode=3\n")))
            assertEquals(PermState.UNKNOWN, DevVerifier.status(r).state)
        // legacy Play Protect keys are not this allowance
        assertEquals(State.UNKNOWN, parse(global = "package_verifier_enable=0\nverifier_verify_adb_installs=0\n").state)
    }

    @Test fun `probe answers parse`() {
        assertEquals(true, DevVerifier.parseFlag("1\n")); assertEquals(false, DevVerifier.parseFlag("0")); assertEquals(null, DevVerifier.parseFlag("null"))
        assertEquals(12_345_670L, DevVerifier.parseUptimeMs("12345.67 8910.11\n")); assertEquals(null, DevVerifier.parseUptimeMs("cat: no"))
        assertEquals("9c1b1d0e-1111-4222-8333-444455556666", DevVerifier.parseBootId("9c1b1d0e-1111-4222-8333-444455556666\n"))
        assertEquals(null, DevVerifier.parseBootId("cat: /proc: Permission denied"))
        assertEquals(7, DevVerifier.PROBES.size)
    }
}

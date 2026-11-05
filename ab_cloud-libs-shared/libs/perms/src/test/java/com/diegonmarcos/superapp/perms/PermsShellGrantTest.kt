package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1519 Grant via shell: the exact `pm grant` line through the broker channel, with a read-back. */
class PermsShellGrantTest {
    private val pkg = "com.diegonmarcos.superapp"
    private val fine = PermEntry("fine_location", "Precise location", "LOCATION", PermKind.RUNTIME, PermSection.RUNTIME, permission = PermsRequest.FINE)

    private class Fake(val isUp: Boolean = true, val answer: String? = "", val onExec: () -> Unit = {}) : PermsGrant.GrantChannel {
        val ran = ArrayList<String>()
        override fun up() = isUp
        override fun exec(command: String): String? { ran += command; onExec(); return answer }
    }

    @Test fun `a runtime row grants with pm grant on the app itself`() =
        assertEquals(listOf("pm grant $pkg ${PermsRequest.FINE}"), PermsShellGrant.commands(pkg, fine))

    @Test fun `rows only a page can answer have no shell line`() {
        val hc = PermEntry("health_connect", "Health", "SENSORS", PermKind.SPECIAL, PermSection.SPECIAL)
        assertEquals(null, PermsShellGrant.commands(pkg, hc))
        val loc = PermEntry("location_services", "Location", "SYSTEM", PermKind.SPECIAL, PermSection.SYSTEM)
        assertEquals(listOf("cmd location set-location-enabled true"), PermsShellGrant.commands(pkg, loc))
    }

    @Test fun `the grant runs through the channel and is read back`() {
        var held = false
        val ch = Fake(onExec = { held = true })
        val r = PermsShellGrant.run("Precise location", PermsShellGrant.commands(pkg, fine)!!, ch) { held }
        assertTrue(r.message, r.ok); assertEquals(listOf("pm grant $pkg ${PermsRequest.FINE}"), ch.ran)
    }

    @Test fun `a channel that is down grants nothing and says why`() {
        val ch = Fake(isUp = false)
        val r = PermsShellGrant.run("x", listOf("pm grant a b"), ch) { false }
        assertFalse(r.ok); assertTrue(ch.ran.isEmpty()); assertTrue(r.message.contains(PermsGrant.DISABLED_REASON))
    }

    @Test fun `a refusal and a command that ran without effect are both failures`() {
        assertFalse(PermsShellGrant.run("x", listOf("pm grant a b"), Fake(answer = "Exception occurred: SecurityException")) { false }.ok)
        val noEffect = PermsShellGrant.run("x", listOf("pm grant a b"), Fake(answer = "")) { false }
        assertFalse(noEffect.ok); assertTrue(noEffect.message.contains("still not granted"))
        assertFalse(PermsShellGrant.run("x", listOf("pm grant a b"), Fake(answer = null)) { false }.ok)
    }

    @Test fun `an already held permission runs nothing`() {
        val ch = Fake()
        assertTrue(PermsShellGrant.run("x", listOf("pm grant a b"), ch) { true }.ok); assertTrue(ch.ran.isEmpty())
    }
}

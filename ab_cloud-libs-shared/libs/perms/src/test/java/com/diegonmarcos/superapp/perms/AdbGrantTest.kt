package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.perms.AdbGrant.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1513 the adb grant row: offer, self-grant through the channel, read-back, and the automatic request that runs once. */
class AdbGrantTest {
    private val pkg = "com.diegonmarcos.superapp"
    private val perm = "android.permission.WRITE_SECURE_SETTINGS"

    @Test fun `a missing permission leads to Grant with a channel and to the copy path without one`() {
        assertEquals(Offer.GRANT, AdbGrant.offer(held = false, channelUp = true))
        assertEquals(Offer.COPY, AdbGrant.offer(held = false, channelUp = false))
        assertEquals(Offer.NONE, AdbGrant.offer(held = true, channelUp = true))
    }

    @Test fun `the copy line is the one-line adb command`() =
        assertEquals("adb shell pm grant $pkg $perm", AdbGrant.adbLine(pkg, perm))

    @Test fun `self-grant runs exactly the one command and reads the permission back`() {
        val ran = ArrayList<String>(); var held = false
        val ok = AdbGrant.grant(pkg, perm, { ran += it; held = true }, { held })
        assertTrue(ok)
        assertEquals(listOf("pm grant $pkg $perm"), ran)
    }

    @Test fun `a grant the channel accepted but that is not held reads back as not granted`() {
        val ran = ArrayList<String>()
        assertFalse(AdbGrant.grant(pkg, perm, { ran += it }, { false }))
        assertEquals(1, ran.size)
    }

    @Test fun `an already held permission runs nothing`() {
        val ran = ArrayList<String>()
        assertTrue(AdbGrant.grant(pkg, perm, { ran += it }, { true }))
        assertTrue(ran.isEmpty())
    }

    @Test fun `the auto-request fires once while the channel is armed and never in a loop`() {
        var tried = false; var runs = 0
        repeat(5) {
            if (AdbGrant.shouldAutoRequest(wanted = true, held = false, channelUp = true, alreadyTried = tried)) { tried = true; runs++ }
        }
        assertEquals(1, runs)
        assertFalse(AdbGrant.shouldAutoRequest(true, false, false, false))   // no channel: not now, and not marked tried
        assertFalse(AdbGrant.shouldAutoRequest(false, false, true, false))   // keeper off
        assertFalse(AdbGrant.shouldAutoRequest(true, true, true, false))     // held
    }
}

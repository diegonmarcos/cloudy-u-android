package com.diegonmarcos.superapp.bgpolicy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1602 The SuperApp's own "essential app" flag, as pure rules over [BgEssential]. ON (default) keeps the app's own package
 * essential even when nothing else would; OFF drops that self-protection so Samsung Super Saving may hibernate the app, while
 * every other essential app (roles + fixed list) stays essential. The default constructor keeps the pre-#1602 behaviour.
 */
class EssentialSelfFlagTest {
    private val self = "com.diegonmarcos.superapp"
    private val cfg = BgConfig(
        fleetPrefixes = listOf("com.diegonmarcos."),
        essentialRoles = listOf("self", "dialer", "launcher"),
        essentialPackages = setOf(self, "com.android.systemui"),
        essentialPrefixes = listOf("com.android."),
        bulkIncludesSystem = false,
        hungerTop = 25,
    )
    private val roles = setOf(self, "com.android.dialer")

    @Test fun `self is essential when the flag is on`() {
        val e = BgEssential(cfg, roles, self, selfProtected = true)
        assertTrue("self must stay essential while protected", e.isEssential(self))
    }

    @Test fun `self is not essential when the flag is off even though the role and fixed list name it`() {
        val e = BgEssential(cfg, roles, self, selfProtected = false)
        assertFalse("self must drop out of essential when protection is off", e.isEssential(self))
    }

    @Test fun `turning self-protection off never changes any other essential app`() {
        val e = BgEssential(cfg, roles, self, selfProtected = false)
        assertTrue(e.isEssential("com.android.systemui"))   // fixed package
        assertTrue(e.isEssential("com.android.phone"))       // fixed prefix
        assertTrue(e.isEssential("com.android.dialer"))      // resolved role
    }

    @Test fun `the default constructor keeps the pre-1602 behaviour (self stays essential)`() {
        val e = BgEssential(cfg, roles)
        assertTrue(e.isEssential(self))
    }
}

package com.diegonmarcos.superapp.configs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1833 the accessibility list the Security tab writes: short and long component forms are one, nothing is duplicated or lost. */
class SecurityControlsTest {
    private val kb = SecurityControls.A11Y.getValue("access_keyboard")
    private val vault = SecurityControls.A11Y.getValue("access_vault")

    @Test fun shortAndLongFormsAreOneComponent() {
        assertEquals("com.diegonmarcos.cloudkeyboard/com.diegonmarcos.cloudkeyboard.grabber.GrabberSelectionService",
            SecurityControls.normalize("com.diegonmarcos.cloudkeyboard/.grabber.GrabberSelectionService"))
        assertEquals(kb, SecurityControls.normalize(kb))
    }

    @Test fun turningOnAddsOnceAndKeepsTheOthers() {
        val before = "com.other.app/.Svc:com.diegonmarcos.cloudkeyboard/.grabber.GrabberSelectionService"
        val on = SecurityControls.edit(before, kb, true)
        assertEquals(listOf("com.other.app/com.other.app.Svc", kb), on)
        assertEquals(listOf("com.other.app/com.other.app.Svc", kb, vault), SecurityControls.edit(on.joinToString(":"), vault, true))
    }

    @Test fun turningOffRemovesOnlyThatService() {
        val list = listOf("com.other.app/com.other.app.Svc", kb, vault).joinToString(":")
        assertEquals(listOf("com.other.app/com.other.app.Svc", vault), SecurityControls.edit(list, kb, false))
        assertEquals(emptyList<String>(), SecurityControls.edit(kb, kb, false))
        assertEquals(emptyList<String>(), SecurityControls.parse(null))
        assertEquals(emptyList<String>(), SecurityControls.parse("null"))
    }

    @Test fun extrasAreFleetServicesNotListed() {
        val list = listOf(kb, "com.diegonmarcos.cloudlib.gitsync/.GitSyncAccessibilityService", "com.other.app/.Svc").joinToString(":")
        assertEquals(listOf("com.diegonmarcos.cloudlib.gitsync/com.diegonmarcos.cloudlib.gitsync.GitSyncAccessibilityService"),
            SecurityControls.extras(list))
        assertTrue(SecurityControls.extras(listOf(kb, vault).joinToString(":")).isEmpty())
        assertEquals("the vendored GitSync library counts as the fleet's",
            listOf("com.viscouspot.gitsync/com.viscouspot.gitsync.GitSyncAccessibilityService"),
            SecurityControls.extras("com.viscouspot.gitsync/.GitSyncAccessibilityService:$kb"))
    }

    @Test fun theThreeFleetServicesAreDeclared() {
        assertEquals(listOf("access_keyboard", "access_superapp", "access_vault"), SecurityControls.A11Y.keys.toList())
        SecurityControls.A11Y.values.forEach { assertTrue(it, it.startsWith(SecurityControls.FLEET_PREFIX)) }
    }
}

package com.diegonmarcos.superapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1502 the owner's two rules as a truth table. */
class BackgroundPolicyTest {
    private val account = BackgroundPolicy.ACCOUNT_PKG
    private val store = BackgroundPolicy.STORE_PKG

    @Test fun accountNeverBackgrounds() {
        for (au in listOf(true, false)) for (um in listOf(true, false))
            assertFalse("account au=$au unmetered=$um", BackgroundPolicy.mayBackground(account, au, um))
    }

    @Test fun storeNeedsAutoUpdateAndUnmetered() {
        assertTrue(BackgroundPolicy.mayBackground(store, autoUpdateOn = true, unmetered = true))
        assertFalse(BackgroundPolicy.mayBackground(store, autoUpdateOn = true, unmetered = false))
        assertFalse(BackgroundPolicy.mayBackground(store, autoUpdateOn = false, unmetered = true))
        assertFalse(BackgroundPolicy.mayBackground(store, autoUpdateOn = false, unmetered = false))
    }

    @Test fun otherAppsAreUnchanged() {
        assertTrue(BackgroundPolicy.mayBackground("com.diegonmarcos.superapp", false, false))
        assertEquals(BackgroundPolicy.Mode.FREE, BackgroundPolicy.modeOf("com.diegonmarcos.cloudmail"))
    }

    @Test fun nobodyWakesAccountOrStore() {
        assertFalse(BackgroundPolicy.mayWakePeer(account))
        assertFalse(BackgroundPolicy.mayWakePeer(store))
        assertTrue(BackgroundPolicy.mayWakePeer("com.diegonmarcos.superapp"))
    }
}

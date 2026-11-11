package com.diegonmarcos.superapp.bgpolicy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1506 follow-up: Super Saving yields to the system's extreme / maximum battery saver. */
class SystemSaverTest {
    @Test fun samsungMaximumModeIsExtreme() {
        assertTrue(SystemSaver.isExtreme(mapOf("ultra_powersaving_mode" to 1), true))
        assertTrue(SystemSaver.isExtreme(mapOf("emergency_mode" to 1), false))
        assertTrue(SystemSaver.isExtreme(mapOf("psm_level" to 2), true))
    }
    @Test fun plainOrNoSaverIsNotExtreme() {
        assertFalse(SystemSaver.isExtreme(emptyMap(), true))
        assertFalse(SystemSaver.isExtreme(mapOf("ultra_powersaving_mode" to 0, "psm_level" to 1), true))
        assertFalse(SystemSaver.isExtreme(mapOf("psm_level" to 2), false))
        assertFalse(SystemSaver.isExtreme(mapOf("ultra_powersaving_mode" to null), false))
    }
}

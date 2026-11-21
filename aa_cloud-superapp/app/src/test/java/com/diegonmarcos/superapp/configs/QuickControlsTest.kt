package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.configs.DeviceControls.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1846 the pure parts behind the Quick actions UI and Network tabs. */
class QuickControlsTest {

    @Test fun stepsWrapAndStartAtTheFirstWhenTheCurrentValueIsNotAStep() {
        assertEquals(30_000, QuickControls.next(QuickControls.TIMEOUT_STEPS_MS, 15_000))
        assertEquals(15_000, QuickControls.next(QuickControls.TIMEOUT_STEPS_MS, 600_000))
        assertEquals(15_000, QuickControls.next(QuickControls.TIMEOUT_STEPS_MS, 45_000))
        assertEquals(15_000, QuickControls.next(QuickControls.TIMEOUT_STEPS_MS, null))
        assertEquals(1.15f, QuickControls.next(QuickControls.FONT_STEPS, 1.0f))
        assertEquals(0.85f, QuickControls.next(QuickControls.FONT_STEPS, 1.3f))
    }

    @Test fun theDefaultsAreAStepSoTheCycleCanReturnToThem() {
        assertTrue(QuickControls.DEFAULT_TIMEOUT_MS in QuickControls.TIMEOUT_STEPS_MS)
        assertTrue(QuickControls.DEFAULT_FONT in QuickControls.FONT_STEPS)
    }

    @Test fun privateDnsIsOffOnlyWhenTheModeSaysOff() {
        assertFalse(QuickControls.privateDnsOn("off"))
        assertTrue(QuickControls.privateDnsOn("opportunistic"))
        assertTrue(QuickControls.privateDnsOn("hostname"))
        assertTrue("unset is the opportunistic default", QuickControls.privateDnsOn(null))
    }

    @Test fun timeoutLabelsReadAsSecondsThenMinutes() {
        assertEquals("15 s", QuickControls.timeoutLabel(15_000))
        assertEquals("1 min", QuickControls.timeoutLabel(60_000))
        assertEquals("10 min", QuickControls.timeoutLabel(600_000))
        assertEquals("unreadable", QuickControls.timeoutLabel(null))
    }

    @Test fun aMovedTileWithoutAProbeGetsItsSourceAndValueAsTheDetail() {
        val d = QuickControls.detailed("wifi", Probe(true, ""))
        assertEquals(true, d.state)
        assertEquals("WifiManager.isWifiEnabled: on", d.detail)
        assertEquals("WifiManager.isWifiEnabled: unreadable", QuickControls.detailed("wifi", Probe(null, "")).detail)
    }

    @Test fun aTileThatHasItsOwnDetailKeepsIt() {
        assertEquals("mine", QuickControls.detailed("wifi", Probe(true, "mine")).detail)
        assertEquals("", QuickControls.detailed("not_a_moved_tile", Probe(true, "")).detail)
    }
}

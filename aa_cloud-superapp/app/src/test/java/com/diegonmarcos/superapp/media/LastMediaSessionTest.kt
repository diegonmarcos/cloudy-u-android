package com.diegonmarcos.superapp.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LastMediaSessionTest {
    @Test fun `a playing session advances with elapsed time, a paused one holds`() {
        assertEquals(12_000L, LastMediaSession.positionAt(10_000, 1_000, 1f, true, 3_000))
        assertEquals(14_000L, LastMediaSession.positionAt(10_000, 1_000, 2f, true, 3_000))
        assertEquals(10_000L, LastMediaSession.positionAt(10_000, 1_000, 1f, false, 9_000))
    }

    @Test fun `fraction is position over duration, clamped, zero without a duration`() {
        val a = LastMediaSession.Last("p", "t", "a", 30_000, 120_000, null)
        assertEquals(0.25f, a.fraction, 0.0001f)
        assertEquals(0f, a.copy(durationMs = 0).fraction, 0f)
        assertEquals(1f, a.copy(positionMs = 999_999).fraction, 0f)
    }
}

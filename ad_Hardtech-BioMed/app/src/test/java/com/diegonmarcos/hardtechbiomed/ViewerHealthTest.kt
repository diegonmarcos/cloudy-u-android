package com.diegonmarcos.hardtechbiomed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's health rules (the same pure object Hardtech-CAD's viewer uses, #1658/#1664): what the screen shows, the presentation it steps to, and the gesture calls. */
class ViewerHealthTest {
    @Test fun coverageCountsOnlyPixelsAwayFromTheBackground() {
        val bg = ViewerHealth.BACKGROUND
        assertEquals(0.0, ViewerHealth.coverage(IntArray(10) { bg }), 0.0)
        assertEquals(50.0, ViewerHealth.coverage(IntArray(10) { if (it < 5) 0xFFFFFFFF.toInt() else bg }), 0.0)
        assertEquals(-1.0, ViewerHealth.coverage(IntArray(0)), 0.0)
    }

    @Test fun aPaintedModelThatIsNotOnTheScreenStepsToTheNextPresentation() {
        assertTrue(ViewerHealth.lost(5.0, 0.0))
        assertFalse(ViewerHealth.lost(5.0, -1.0))   // not measured is never a failure
        assertFalse(ViewerHealth.lost(0.0, 0.0))    // nothing was painted: a different failure
        assertEquals(ViewerHealth.Present.CANVAS2D, ViewerHealth.next(ViewerHealth.Present.WEBGL, 5.0, 0.0))
        assertEquals(ViewerHealth.Present.HOST, ViewerHealth.next(ViewerHealth.Present.CANVAS2D, 5.0, 0.0))
        assertNull(ViewerHealth.next(ViewerHealth.Present.HOST, 5.0, 0.0))
        assertNull(ViewerHealth.next(ViewerHealth.Present.WEBGL, 5.0, 40.0))
    }

    @Test fun aRememberedFallbackBelongsToItsBuildOnly() {
        assertEquals(ViewerHealth.Present.HOST, ViewerHealth.start("host", "7-abc", "7-abc"))
        assertEquals(ViewerHealth.Present.WEBGL, ViewerHealth.start("host", "7-abc", "8-def"))
        assertEquals(ViewerHealth.Present.WEBGL, ViewerHealth.start(null, null, "8-def"))
    }

    @Test fun gesturesBecomePageCalls() {
        assertEquals(listOf("window.cadOrbit&&window.cadOrbit(1.50,-2.00)"), ViewerHealth.gesture(1, 1.5f, -2f, 1f))
        assertTrue(ViewerHealth.gesture(0, 1f, 1f, 1f).isEmpty())
        assertTrue(ViewerHealth.gesture(1, 0f, 0f, 1f).isEmpty())
        val two = ViewerHealth.gesture(2, 3f, 0f, 1.5f)
        assertEquals(2, two.size)
        assertTrue(two[0].startsWith("window.cadPan") && two[1].startsWith("window.cadZoom"))
    }
}

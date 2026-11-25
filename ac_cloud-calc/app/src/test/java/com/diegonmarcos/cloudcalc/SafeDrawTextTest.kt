package com.diegonmarcos.cloudcalc

import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.uikit.safeTextOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1921 the shared drawText guard: origins at or past the right/bottom edge are skipped, negatives clamp to 0. */
class SafeDrawTextTest {
    @Test fun insideStaysPut() = assertEquals(Offset(10f, 20f), safeTextOrigin(Offset(10f, 20f), 100f, 100f))
    @Test fun negativeClampsToZero() = assertEquals(Offset(0f, 5f), safeTextOrigin(Offset(-30f, 5f), 100f, 100f))
    @Test fun pastRightEdgeSkipped() = assertNull(safeTextOrigin(Offset(100f, 10f), 100f, 100f))
    @Test fun pastBottomEdgeSkipped() = assertNull(safeTextOrigin(Offset(10f, 250f), 100f, 100f))
    @Test fun nanAndEmptyCanvasSkipped() {
        assertNull(safeTextOrigin(Offset(Float.NaN, 1f), 100f, 100f))
        assertNull(safeTextOrigin(Offset(1f, 1f), 0f, 100f))
    }
}

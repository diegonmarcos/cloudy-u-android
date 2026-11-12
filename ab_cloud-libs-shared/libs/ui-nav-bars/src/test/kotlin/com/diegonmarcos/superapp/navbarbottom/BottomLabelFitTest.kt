package com.diegonmarcos.superapp.navbarbottom

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1698 a bottom label is never cut: a long one shrinks to fit its room (not below LABEL_MIN), a short one keeps LABEL. */
class BottomLabelFitTest {
    @Test fun shortLabelsKeepTheFleetSize() {
        assertEquals(BottomSpec.LABEL, BottomSpec.labelSize("Writer", 80.dp))
    }
    @Test fun longLabelsShrinkToFitAndStayLegible() {
        val s = BottomSpec.labelSize("Mediaboards", 72.dp)
        assertTrue(s.value < BottomSpec.LABEL.value)
        assertTrue(s.value >= BottomSpec.LABEL_MIN.value)
        assertTrue("fits: ${s.value}", "Mediaboards".length * 0.58f * s.value <= 72f + 0.01f)
    }
    @Test fun theSourceNeverEllipsizesTheLabel() {
        val src = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/kotlin/com/diegonmarcos/superapp/navbarbottom/NavBarBottom.kt") }.firstOrNull { it.isFile }
            ?: generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .map { java.io.File(it, "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbarbottom/NavBarBottom.kt") }.first { it.isFile }
        assertTrue(!src.readText().contains("TextOverflow.Ellipsis"))
    }
}

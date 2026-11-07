package com.diegonmarcos.superapp.bottomnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fit-the-text strip never shrinks the font, never cuts a label and wraps each pill to its own text. */
class FitTextPlanTest {

    @Test
    fun `fit-text keeps the full size, wraps the pill to its label and scrolls`() {
        val plan = fitTextPlan(36f)
        assertEquals(36f, plan.textPx, 0f)
        assertEquals("pillPx 0 = wrap the content", 0f, plan.pillPx, 0f)
        assertTrue(plan.scrollable)
    }

    @Test
    fun `the equal-slot plan DOES shrink or cut a long label in a crowded strip (the defect fit-text removes)`() {
        // 4 tabs on 360px, longest label 15 chars (BATTERY HUNGERS), 20px per char at the start size.
        val plan = planPills(360f, 4, 15, 40f, 12f, 36f, 24f, 3f, 4) { px -> px / 2f }
        assertTrue("the equal-slot plan gave up the full size or the whole label", plan.textPx < 36f || plan.pillPx < 15 * 18f + 40f)
    }
}

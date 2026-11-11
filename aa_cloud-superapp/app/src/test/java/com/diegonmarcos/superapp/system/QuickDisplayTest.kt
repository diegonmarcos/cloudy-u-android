package com.diegonmarcos.superapp.system

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Quick Actions > UI display controls: the declaration (centre 411, Set 500), the arithmetic, the commands and the needs-ADB fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickDisplayTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `Q1 Cloud-superapp Scale is a ruler centred on 411 with a Set 500 preset`() {
        val s = QuickDisplay.scaleSpec
        assertEquals("Display Scale", s.label)
        assertEquals(411, s.center)
        assertEquals("symmetric around the centre", s.center - s.min, s.max - s.center)
        assertTrue(500 in s.min..s.max)
        assertTrue(s.presets.any { it.value == 500 && it.label == "Set 500" })
        assertTrue((s.center - s.min) % s.tickMinor == 0)
    }

    @Test
    fun `Q2 Font Size and Zoom Size declare their key and steps`() {
        assertEquals("Font Size", QuickDisplay.fontSpec.label); assertEquals("font_scale", QuickDisplay.fontSpec.key)
        assertEquals("Zoom Size", QuickDisplay.zoomSpec.label); assertEquals("display_density_forced", QuickDisplay.zoomSpec.key)
        assertTrue(1.0f in QuickDisplay.fontSpec.steps && 1.0f in QuickDisplay.zoomSpec.steps)
        assertEquals(QuickDisplay.fontSpec.steps.size, QuickDisplay.fontSpec.names.size)
        assertEquals(QuickDisplay.zoomSpec.steps.size, QuickDisplay.zoomSpec.names.size)
    }

    @Test
    fun `Q3 smallest width and density convert both ways`() {
        // 1080 px short side at 420 dpi is 411 dp.
        assertEquals(411, QuickDisplay.swForDensity(1080, 420))
        // density is an integer dpi, so the round trip is exact only to within one dp (346 dpi gives 499 dp for 500).
        assertEquals(500.0, QuickDisplay.swForDensity(1080, QuickDisplay.densityForSw(1080, 500)).toDouble(), 1.0)
        assertEquals(2, QuickDisplay.nearest(listOf(0.85f, 1.0f, 1.15f), 1.12f))
        assertEquals(462, QuickDisplay.densityForFactor(420, 1.1f))
    }

    @Test
    fun `Q4 commands are the exact settings and wm lines`() {
        assertEquals("settings put system font_scale 1.15", QuickDisplay.fontCommand(1.15f))
        assertEquals("wm density 462", QuickDisplay.densityCommand(462))
    }

    @Test
    fun `Q5 the in-app smallest width is stored, wraps the density and reads back`() {
        assertEquals(0, QuickDisplay.swOverride(ctx))
        QuickDisplay.setSwOverride(ctx, 500)
        assertEquals(500, QuickDisplay.swOverride(ctx))
        val wrapped = SystemDisplay.wrap(ctx)
        val dm = ctx.resources.displayMetrics
        assertEquals(QuickDisplay.densityForSw(minOf(dm.widthPixels, dm.heightPixels), 500), wrapped.resources.configuration.densityDpi)
        QuickDisplay.setSwOverride(ctx, 0)
    }

    @Test
    fun `Q6 without a shell channel and without WRITE_SETTINGS a zoom write says needs ADB with the exact command`() {
        val w = QuickDisplay.writeDensity(ctx, 462)
        assertTrue("$w", w is QuickDisplay.Write.NeedsAdb)
        assertEquals("adb shell wm density 462", (w as QuickDisplay.Write.NeedsAdb).command)
    }
}

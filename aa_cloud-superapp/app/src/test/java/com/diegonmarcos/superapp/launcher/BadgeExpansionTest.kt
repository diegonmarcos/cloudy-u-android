package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.BadgeExpansion.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1794 the All page's three-state badge rows: the state machine, the swipe rule and the remembered stage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgeExpansionTest {

    @Test fun `the arrow walks collapsed - first - second - collapsed`() {
        assertEquals(Stage.FIRST, BadgeExpansion.next(Stage.COLLAPSED))
        assertEquals(Stage.SECOND, BadgeExpansion.next(Stage.FIRST))
        assertEquals(Stage.COLLAPSED, BadgeExpansion.next(Stage.SECOND))
        // every state is reachable from every state, and three steps come home
        for (s in Stage.values()) assertEquals(s, BadgeExpansion.next(BadgeExpansion.next(BadgeExpansion.next(s))))
        assertEquals(3, generateSequence(Stage.COLLAPSED) { BadgeExpansion.next(it) }.take(3).toSet().size)
    }

    @Test fun `the arrow points down while there is more to open, up on the last state`() {
        assertEquals("▾", BadgeExpansion.glyph(Stage.COLLAPSED))
        assertEquals("▾", BadgeExpansion.glyph(Stage.FIRST))
        assertEquals("▴", BadgeExpansion.glyph(Stage.SECOND))
        assertEquals("Expand", BadgeExpansion.describe(Stage.COLLAPSED))
        assertEquals("Collapse", BadgeExpansion.describe(Stage.SECOND))
    }

    @Test fun `only a long, mostly vertical, downward drag is a swipe down`() {
        val t = 48f
        assertTrue(BadgeExpansion.isSwipeDown(dy = 60f, dx = 5f, thresholdPx = t))
        assertTrue(BadgeExpansion.isSwipeDown(dy = 48f, dx = 24f, thresholdPx = t))
        assertFalse("a tap", BadgeExpansion.isSwipeDown(dy = 2f, dx = 1f, thresholdPx = t))
        assertFalse("too short", BadgeExpansion.isSwipeDown(dy = 47f, dx = 0f, thresholdPx = t))
        assertFalse("upwards", BadgeExpansion.isSwipeDown(dy = -80f, dx = 0f, thresholdPx = t))
        assertFalse("sideways", BadgeExpansion.isSwipeDown(dy = 60f, dx = 40f, thresholdPx = t))
    }

    @Test fun `an unknown or missing stored stage reads collapsed`() {
        assertEquals(Stage.COLLAPSED, BadgeExpansion.decode(null))
        assertEquals(Stage.COLLAPSED, BadgeExpansion.decode("HALF"))
        for (s in Stage.values()) assertEquals(s, BadgeExpansion.decode(s.name))
    }

    @Test fun `the stage is per badge and survives a re-read`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("notify_all_badge_stage", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(Stage.COLLAPSED, BadgeExpansion.stage(ctx, "a"))
        BadgeExpansion.set(ctx, "a", Stage.SECOND)
        BadgeExpansion.set(ctx, "b", Stage.FIRST)
        assertEquals(Stage.SECOND, BadgeExpansion.stage(ctx, "a"))
        assertEquals(Stage.FIRST, BadgeExpansion.stage(ctx, "b"))
    }
}

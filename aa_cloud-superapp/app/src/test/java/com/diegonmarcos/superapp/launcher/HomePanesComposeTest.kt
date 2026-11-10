package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment
import com.diegonmarcos.superapp.launcher.themes.cloud.PolytopeMath
import com.diegonmarcos.superapp.launcher.themes.minimalist.MinimalistBlackFragment
import com.diegonmarcos.superapp.launcher.themes.minimalist.MinimalistDesign
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the two home panes (Cloud's neon cube, Minimalist Black's terminal list), Compose since
 * they were a custom View and a hand-built LinearLayout.
 *
 *   H1 the cube pane draws the cube (animation off, so the test frame clock can go idle)
 *   H2 the projection: 8 vertices, 12 edges each joining two distinct vertices, every vertex once
 *      per edge-end three times, a zero-rotation front face is larger than the back face
 *   H3 the rotation never snaps: both axes advance monotonically through a full 16s Y period
 *      and 23.5s X period with no step larger than the per-ms rate
 *   H4 the depth fade is 1.0 at the front, never below 135/255 at the back
 *   M1 the filter is a case-insensitive substring over the label and a blank one matches all
 *   M2 an empty app list shows the filter prompt and "no matches"; typing keeps it
 *   M3 the terminal line grammar: a tappable line carries the prompt, a plain one the indent
 *   M4 the mode's palette maps ground to surface and its two inks to the text roles, borrowing nothing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomePanesComposeTest : KitPageHarness() {

    @Test
    fun `H1 the cube pane draws the cube`() {
        LauncherSettingsPrefs(ApplicationProvider.getApplicationContext()).setToggle("all_anim", false)
        show(Home3DFragment.newInstance())
        compose.onNodeWithTag(Home3DFragment.TAG_CUBE).assertExists()
    }

    // #1621 the cube is one shape of PolytopeMath now; the cube's maths moved
    // there whole, so these three assertions are ported verbatim onto it (the
    // cube mesh's vertex order, projection constants and rotation clocks are
    // unchanged, so the default home background is pixel-identical).
    @Test
    fun `H2 the projection of the unit cube`() {
        val cube = PolytopeMath.mesh(PolytopeMath.CUBE)
        assertEquals(8, cube.verts.size)
        assertEquals(12, cube.edges.size)
        val ends = IntArray(8)
        for (e in cube.edges) {
            assertTrue(e[0] != e[1])
            // An edge joins two vertices that differ in exactly one coordinate.
            val a = cube.verts[e[0]]; val b = cube.verts[e[1]]
            assertEquals(1, (0..2).count { a[it] != b[it] })
            ends[e[0]]++; ends[e[1]]++
        }
        assertTrue("every vertex has three edges", ends.all { it == 3 })
        val p = PolytopeMath.project(cube, 0.0, 460f, 460f, rotXOverride = 0f, rotYOverride = 0f)
        // No rotation: the near face (z = -1 here) is projected larger than the far face (z = +1).
        val nearSpan = p.x[1] - p.x[0]
        val farSpan = p.x[5] - p.x[4]
        assertTrue("near $nearSpan > far $farSpan", nearSpan > farSpan)
        // Centred: the projection is symmetric about the middle of the box.
        assertEquals(230f, (p.x[0] + p.x[1]) / 2f, 0.01f)
    }

    @Test
    fun `H3 the rotation is continuous through a full period`() {
        var prevY = PolytopeMath.angleY(0.0); var prevX = PolytopeMath.angleX(0.0)
        val perMsY = (2.0 * Math.PI / PolytopeMath.PERIOD_Y_MS).toFloat()
        val perMsX = (2.0 * Math.PI / PolytopeMath.PERIOD_X_MS).toFloat()
        var t = 16.0
        while (t <= 24_000.0) {
            val y = PolytopeMath.angleY(t); val x = PolytopeMath.angleX(t)
            assertTrue("Y stepped at $t", y > prevY && y - prevY < perMsY * 17f)
            assertTrue("X stepped at $t", x > prevX && x - prevX < perMsX * 17f)
            prevY = y; prevX = x; t += 16.0
        }
        assertEquals(PolytopeMath.angleY(0.0) + 2f * Math.PI.toFloat(), PolytopeMath.angleY(PolytopeMath.PERIOD_Y_MS), 1e-3f)
    }

    @Test
    fun `H4 the depth fade`() {
        assertEquals(1f, PolytopeMath.edgeAlpha(0f), 1e-6f)
        assertEquals(1f, PolytopeMath.edgeAlpha(-2f), 1e-6f)
        assertEquals(135f / 255f, PolytopeMath.edgeAlpha(5f), 1e-6f)
    }

    @Test
    fun `M1 the filter is a case-insensitive substring`() {
        assertTrue(MinimalistBlackFragment.matches("Firefox", ""))
        assertTrue(MinimalistBlackFragment.matches("Firefox", "  "))
        assertTrue(MinimalistBlackFragment.matches("Firefox", "FIRE"))
        assertTrue(MinimalistBlackFragment.matches("Firefox", " fox "))
        assertFalse(MinimalistBlackFragment.matches("Firefox", "chrome"))
    }

    @Test
    fun `M2 no apps shows no matches and the filter stays usable`() {
        show(MinimalistBlackFragment.newInstance())
        compose.onNodeWithTag(MinimalistBlackFragment.TAG_FILTER).assertExists()
        compose.onNodeWithTag(MinimalistBlackFragment.TAG_NONE).assertExists()
        compose.onNodeWithTag(MinimalistBlackFragment.TAG_FILTER).performTextInput("zzz")
        compose.onNodeWithTag(MinimalistBlackFragment.TAG_NONE).assertExists()
    }

    @Test
    fun `M3 the line grammar`() {
        assertEquals("$ Mail", MinimalistDesign.lineText("Mail", true))
        assertEquals("  Mail", MinimalistDesign.lineText("Mail", false))
    }

    @Test
    fun `M4 the palette is the mode's own`() {
        val k = MinimalistDesign.kit
        assertEquals(MinimalistDesign.GROUND, k.surface)
        assertEquals(MinimalistDesign.INK, k.textPrimary)
        assertEquals(MinimalistDesign.INK_DIM, k.textSecondary)
        assertEquals(MinimalistDesign.GLOW, k.accent)
    }
}

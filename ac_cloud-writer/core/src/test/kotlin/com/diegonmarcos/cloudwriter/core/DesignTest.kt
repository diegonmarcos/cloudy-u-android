package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1532 the token contrast test (WCAG AA on every pair the app draws), the control states and the grid. */
class DesignTest {
    private val themes = listOf("dark" to TokenSet.DARK, "light" to TokenSet.LIGHT)

    @Test fun everyTextPairReachesAA() {
        for ((name, t) in themes) for ((pair, colours) in t.textPairs()) {
            val r = Contrast.ratio(colours.first, colours.second)
            assertTrue("$name $pair is $r, below AA ${Contrast.AA_TEXT}", r >= Contrast.AA_TEXT)
        }
    }

    @Test fun everyGraphicPairReaches3to1() {
        for ((name, t) in themes) for ((pair, colours) in t.graphicPairs()) {
            val r = Contrast.ratio(colours.first, colours.second)
            assertTrue("$name $pair is $r, below ${Contrast.AA_GRAPHIC}", r >= Contrast.AA_GRAPHIC)
        }
    }

    @Test fun theTestReadsEveryPair() {
        for ((_, t) in themes) {
            assertEquals(20, t.textPairs().size)
            assertEquals(2, t.graphicPairs().size)
            assertEquals(t.textPairs().size, t.textPairs().map { it.first }.toSet().size)
        }
    }

    @Test fun theTestCanFail() {
        assertTrue(Contrast.ratio(0xFF777777.toInt(), 0xFF888888.toInt()) < Contrast.AA_TEXT)
    }

    @Test fun contrastMath() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1e-9)
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-9)
        assertEquals(1.0, Contrast.luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, Contrast.luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(0.00303523, Contrast.luminance(0xFF0A0A0A.toInt()), 1e-6)
        assertEquals(0.2159, Contrast.luminance(0xFF808080.toInt()), 1e-3)
        assertEquals(0.2126, Contrast.luminance(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, Contrast.luminance(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, Contrast.luminance(0xFF0000FF.toInt()), 1e-9)
    }

    @Test fun blendIsPerChannelAndOpaque() {
        assertEquals(0xFF808080.toInt(), Contrast.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF102030.toInt(), Contrast.blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), Contrast.blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFFFFFFFF.toInt(), Contrast.blend(0xFF000000.toInt(), 0xFF808080.toInt(), 2f))
        assertEquals(0xFF000000.toInt(), Contrast.blend(0xFF404040.toInt(), 0xFF000000.toInt(), 2f))
        assertEquals(0xFF0A141E.toInt(), Contrast.blend(0x000A141E, 0x00FFFFFF, 0f))
        assertEquals(0xFF200000.toInt(), Contrast.blend(0xFF100000.toInt(), 0xFF300000.toInt(), 0.5f))
        assertEquals(0xFF000020.toInt(), Contrast.blend(0xFF000010.toInt(), 0xFF000030.toInt(), 0.5f))
        assertEquals(0xFF002000.toInt(), Contrast.blend(0xFF001000.toInt(), 0xFF003000.toInt(), 0.5f))
    }

    @Test fun surfaceLevelsStepApart() {
        for ((name, t) in themes) {
            val levels = listOf(t.surface, t.surfaceContainerLow, t.surfaceContainer, t.surfaceContainerHigh, t.surfaceContainerHighest)
            levels.zipWithNext().forEach { (a, b) ->
                assertTrue("$name tonal levels must differ", Contrast.ratio(a, b) > 1.0)
            }
            assertEquals(t.background, t.surface)
        }
        // dark steps up in luminance, light steps down: elevation reads as a tonal move away from the page
        assertTrue(Contrast.luminance(TokenSet.DARK.surfaceContainerHighest) > Contrast.luminance(TokenSet.DARK.surface))
        assertTrue(Contrast.luminance(TokenSet.LIGHT.surfaceContainerHighest) < Contrast.luminance(TokenSet.LIGHT.surface))
    }

    @Test fun enabledAndPressedControlsReachAA() {
        for ((name, t) in themes) for (kind in ControlKind.values()) for (state in listOf(ControlState.ENABLED, ControlState.PRESSED)) {
            val c = Controls.colors(t, kind, state)
            val r = Contrast.ratio(c.container, c.content)
            assertTrue("$name $kind $state is $r", r >= Contrast.AA_TEXT)
            assertEquals(1f, c.alpha, 0f)
        }
    }

    @Test fun controlRolesAreFilledTonalText() {
        val t = TokenSet.LIGHT
        assertEquals(ControlColors(t.primary, t.onPrimary, 1f), Controls.colors(t, ControlKind.PRIMARY, ControlState.ENABLED))
        assertEquals(ControlColors(t.secondaryContainer, t.onSecondaryContainer, 1f), Controls.colors(t, ControlKind.TONAL, ControlState.ENABLED))
        assertEquals(ControlColors(t.surfaceContainerHigh, t.primary, 1f), Controls.colors(t, ControlKind.TEXT, ControlState.ENABLED))
    }

    @Test fun pressedMovesTheContainerTowardsTheInk() {
        for ((_, t) in themes) for (kind in ControlKind.values()) {
            val up = Controls.colors(t, kind, ControlState.ENABLED)
            val down = Controls.colors(t, kind, ControlState.PRESSED)
            assertNotEquals(up.container, down.container)
            assertEquals(up.content, down.content)
            assertEquals(Contrast.blend(up.container, up.content, Controls.PRESSED_LAYER), down.container)
        }
    }

    @Test fun disabledKeepsTheColoursAndDropsTheAlpha() {
        for ((_, t) in themes) for (kind in ControlKind.values()) {
            val up = Controls.colors(t, kind, ControlState.ENABLED)
            val off = Controls.colors(t, kind, ControlState.DISABLED)
            assertEquals(up.container, off.container)
            assertEquals(up.content, off.content)
            assertEquals(0.38f, off.alpha, 0f)
        }
    }

    @Test fun gridHoldsTheDesignRules() {
        assertTrue(WriterGrid.SPACING.all { it % WriterGrid.HALF == 0 })
        assertTrue(WriterGrid.SPACING.containsAll(listOf(WriterGrid.UNIT, WriterGrid.GUTTER)))
        assertEquals(WriterGrid.SPACING.sorted(), WriterGrid.SPACING)
        assertTrue(WriterGrid.RADIUS_CONTROL in 12..16 && WriterGrid.RADIUS_CARD in 12..16)
        assertTrue(WriterGrid.CONTROL_HEIGHT in 40..48 && WriterGrid.CONTROL_HEIGHT_LARGE in 40..48)
        assertTrue(WriterGrid.CONTROL_HEIGHT <= WriterGrid.CONTROL_HEIGHT_LARGE)
        assertTrue(WriterGrid.TOUCH >= 48)
        assertTrue(WriterGrid.MEASURE in 560..720)
        assertTrue(WriterGrid.LINE_HEIGHT_EM in 1.4f..1.8f)
        assertTrue(WriterGrid.DIM_ALPHA in 0.3f..0.5f)
    }
}

package com.diegonmarcos.cloudkeyboard.handwrite

import helium314.keyboard.keyboard.handwrite.StrokeCapture
import com.google.mlkit.vision.digitalink.recognition.Ink
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1781 the pad's strokes become an ML Kit Ink: one stroke per pen-down, the points and their times kept in order. */
class InkConversionTest {
    private fun stroke(ink: Ink, i: Int) = ink.strokes[i] as Ink.Stroke

    @Test fun capturedStrokesBecomeAnInkWithTheSameStrokesAndPoints() {
        val c = StrokeCapture()
        c.down(1f, 2f, 100); c.move(3f, 4f, 110); c.up(5f, 6f, 120)
        c.down(7f, 8f, 200); c.up(9f, 10f, 210)
        val ink = InkConversion.toInk(c.strokes)
        assertEquals(2, ink.strokes.size)
        assertEquals(listOf(1f to 2f, 3f to 4f, 5f to 6f), stroke(ink, 0).points.map { it.x to it.y })
        assertEquals(listOf(100L, 110L, 120L), stroke(ink, 0).points.map { it.timestamp })
        assertEquals(listOf(7f to 8f, 9f to 10f), stroke(ink, 1).points.map { it.x to it.y })
    }

    @Test fun anEmptyPadGivesAnEmptyInk() {
        assertEquals(0, InkConversion.toInk(StrokeCapture().strokes).strokes.size)
    }
}

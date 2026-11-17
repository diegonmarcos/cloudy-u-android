package com.diegonmarcos.cloudkeyboard.handwrite

import com.google.mlkit.vision.digitalink.recognition.Ink
import helium314.keyboard.keyboard.handwrite.InkStroke

/** #1781 the captured strokes of the HandWrite pad as an ML Kit [Ink]: one stroke per pen-down, one point per touch sample. */
object InkConversion {
    fun toInk(strokes: List<InkStroke>): Ink {
        val ink = Ink.builder()
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            val stroke = Ink.Stroke.builder()
            for (p in s.points) stroke.addPoint(Ink.Point.create(p.x, p.y, p.t))
            ink.addStroke(stroke.build())
        }
        return ink.build()
    }
}

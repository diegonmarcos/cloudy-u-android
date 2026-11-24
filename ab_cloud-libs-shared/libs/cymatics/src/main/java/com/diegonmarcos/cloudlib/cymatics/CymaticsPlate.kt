package com.diegonmarcos.cloudlib.cymatics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Chladni plate (#1865 Canvas Play, #1892 shared with Cloud Calc's sound meter): a [grid] x [grid] [pattern] from [Cymatics.blend]
 * drawn as sand (bright) on the nodal lines, where the amplitude is under 0.12. The caller sizes it (it draws square cells from the width).
 */
@androidx.compose.runtime.Composable
fun CymaticsPlate(pattern: FloatArray, grid: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF101418), RoundedCornerShape(8.dp))) {
        val p = pattern
        if (p.size == grid * grid) {
            val cell = size.width / grid
            for (r in 0 until grid) for (c in 0 until grid) {
                val v = p[r * grid + c]
                if (v < 0.12f) drawRect(Color(0xFFF5E6C8).copy(alpha = 1f - v / 0.12f * 0.7f), Offset(c * cell, r * cell), Size(cell, cell))
            }
        }
    }
}

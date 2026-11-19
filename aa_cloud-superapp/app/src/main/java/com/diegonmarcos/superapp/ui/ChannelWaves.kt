package com.diegonmarcos.superapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * #1810 the shared live sound-wave component: three waves, one colour per channel. Used by the media badge's progress line.
 * Driven by the playback state (moving while [playing], flat when paused): the AI badge draws no waves and no audio-level
 * source (Visualizer) exists in the app, so there is nothing level-based to reuse.
 */
internal object ChannelWaves {
    class Channel(val color: Color, val freq: Float, val speed: Float, val amp: Float, val phase: Float)

    /** The three channels, three colours (violet, cyan, pink). */
    val channels = listOf(
        Channel(Color(0xFFB794F4), 2.0f, 1.0f, 1.00f, 0.0f),
        Channel(Color(0xFF4FD1C5), 2.9f, 1.4f, 0.75f, 1.7f),
        Channel(Color(0xFFF687B3), 3.7f, 0.8f, 0.55f, 3.1f),
    )
    /** Height of the flat (paused) line as a fraction of the moving amplitude. */
    const val FLAT = 0.0f

    /** Wave [c] at x-fraction [t] (0..1) and [time] seconds, -1..1; [playing] false collapses it to the flat line. */
    fun sample(c: Channel, t: Float, time: Float, playing: Boolean): Float {
        if (!playing) return FLAT
        val a = t * c.freq * 2f * PI.toFloat() + time * c.speed * 2.2f + c.phase
        val b = t * c.freq * 2.3f * 2f * PI.toFloat() + time * c.speed * 3.1f
        val mod = 0.55f + 0.45f * sin(time * 0.9f * c.speed + c.phase)
        return (sin(a) * 0.7f + sin(b) * 0.3f) * c.amp * mod
    }
}

@Composable
internal fun ChannelWavesView(playing: Boolean, modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) time = (withFrameNanos { it } - start) / 1e9f
    }
    val path = remember { Path() }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val step = (w / 80f).coerceAtLeast(1f)
        for (c in ChannelWaves.channels) {
            path.reset()
            var x = 0f
            while (x <= w) {
                val y = h / 2f + ChannelWaves.sample(c, x / w, time, playing) * h * 0.45f
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            drawPath(path, c.color.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

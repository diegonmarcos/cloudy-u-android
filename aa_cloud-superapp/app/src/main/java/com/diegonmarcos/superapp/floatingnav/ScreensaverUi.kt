package com.diegonmarcos.superapp.floatingnav

import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.ui.ShellFxState
import com.diegonmarcos.superapp.ui.FX_FRAME_NS
import kotlin.math.cos
import kotlin.math.sin

/*
 * The screensaver cover in Compose: an opaque black (or, for 'neon_lights', deep-violet) window with the time and date near
 * the top and one Unlock button near the bottom. ScreensaverService owns the window and the clock; this draws it. The neon
 * scene was NeonBackdropView, a View with a software layer for its blur; here the bloom is a few widening low-alpha strokes,
 * which glows the same on a hardware canvas.
 */

// A natural satellite - orbits its planet. r/size in dp, speed in rad/s.
private data class Moon(val r: Int, val size: Int, val speed: Float, val color: Long)

// A planet - orbits the sun. `orbit` is a 1..8 rank mapped to a screen radius; size in dp; speed in rad/s (inner planets faster).
private data class Planet(
    val orbit: Int, val size: Int, val color: Long, val speed: Float,
    val ring: Boolean, val moons: List<Moon>,
)

// Compressed orbits so all eight fit a phone.
private val PLANETS = listOf(
    Planet(1, 4, 0xFF9E9E9E, 1.30f, false, emptyList()),                                                       // Mercury
    Planet(2, 6, 0xFFE8C16B, 0.95f, false, emptyList()),                                                       // Venus
    Planet(3, 6, 0xFF4B8FE3, 0.80f, false, listOf(Moon(11, 2, 2.4f, 0xFFCFCFCF))),                             // Earth + Moon
    Planet(4, 5, 0xFFD9603B, 0.65f, false, listOf(Moon(9, 1, 3.0f, 0xFFB0A090), Moon(13, 1, 2.1f, 0xFFA09080))), // Mars
    Planet(5, 13, 0xFFD8A87B, 0.42f, false, listOf(                                                            // Jupiter + 4 Galilean
        Moon(20, 2, 2.6f, 0xFFE8E0C0), Moon(26, 2, 2.0f, 0xFFC8D8E0), Moon(32, 3, 1.6f, 0xFFB0A088), Moon(39, 3, 1.2f, 0xFF888078))),
    Planet(6, 11, 0xFFE3D9A6, 0.32f, true, listOf(Moon(26, 3, 1.5f, 0xFFD9A86B), Moon(33, 2, 1.1f, 0xFFB8B0A0))), // Saturn + Titan, Rhea
    Planet(7, 8, 0xFF8FE3E0, 0.24f, false, listOf(Moon(15, 2, 1.4f, 0xFFBFC8C8))),                             // Uranus + Titania
    Planet(8, 8, 0xFF3F54D1, 0.19f, false, listOf(Moon(15, 2, 1.6f, 0xFFC8C0B0))),                             // Neptune + Triton
)

// Apple Intelligence / Siri glow palette (looped back to the first).
private val SIRI = intArrayOf(
    0xFFBC82F3.toInt(), 0xFFF5B9EA.toInt(), 0xFF8D9FFF.toInt(), 0xFFAA6EEE.toInt(),
    0xFFFF6778.toInt(), 0xFFFFBA71.toInt(), 0xFFC686FF.toInt(), 0xFFBC82F3.toInt(),
)

private val SUN = Color(0xFFFFCC33)

/** Seconds since the first frame, on the frame clock; stands still when [ShellFxState.frozen] (tests). */
@Composable
private fun rememberSeconds(): Float {
    var sec by remember { mutableFloatStateOf(0f) }
    val drawing = com.diegonmarcos.superapp.ui.rememberDrawing() // no frame callbacks while backgrounded / screen off
    LaunchedEffect(ShellFxState.frozen, drawing) {
        if (ShellFxState.frozen || !drawing) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        var last = t0
        while (true) {
            val now = withFrameNanos { it }
            if (now - last < FX_FRAME_NS) continue // ~30 fps: this value is read in composition, so every tick recomposes
            last = now
            sec = (now - t0) / 1_000_000_000f
        }
    }
    return sec
}

/**
 * iOS-18 "Siri" edge lighting (the Apple Intelligence palette, a sharp core stroke under a bloom, gently breathing) wrapped
 * around an inner Solar-System orrery: the Sun, all eight planets on their orbits, each with its major moons, Saturn ringed.
 * Time-driven (continuous, no loop reset).
 */
@Composable
internal fun NeonBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val sec = rememberSeconds()
    Canvas(modifier.testTag("neon")) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val minDim = minOf(w, h)
        val d = density

        // ── Solar System orrery ──
        drawCircle(Brush.radialGradient(listOf(SUN, SUN.copy(alpha = 0f)), Offset(cx, cy), 16f * d), 16f * d, Offset(cx, cy))
        drawCircle(SUN, 9f * d, Offset(cx, cy))
        for (p in PLANETS) {
            val orbitR = minDim * (0.085f + 0.046f * p.orbit)
            drawCircle(Color(0x18FFFFFF), orbitR, Offset(cx, cy), style = Stroke(1f * d))
            val a = sec * p.speed
            val px = cx + orbitR * cos(a); val py = cy + orbitR * sin(a)
            if (p.ring) {
                rotate(-20f, Offset(px, py)) {
                    drawOval(Color(0xAAE3D9A6), Offset(px - (p.size + 8) * d, py - (p.size + 2) * d),
                        Size(2 * (p.size + 8) * d, 2 * (p.size + 2) * d), style = Stroke(2f * d))
                }
            }
            drawCircle(Color(p.color), p.size * d, Offset(px, py))
            for (m in p.moons) {
                val ma = sec * m.speed
                drawCircle(Color(m.color), m.size * d, Offset(px + m.r * d * cos(ma), py + m.r * d * sin(ma)))
            }
        }

        // ── iOS-18 Siri edge lighting: the palette swept round a rounded rect, drifting (~40 s/rev), blooming under a core ──
        val inset = 7f * d
        val topLeft = Offset(inset, inset)
        val box = Size(w - 2 * inset, h - 2 * inset)
        val rad = CornerRadius(44f * d)
        val sweep = SweepGradient(cx, cy, SIRI, null).apply {
            setLocalMatrix(Matrix().apply { setRotate((sec * 9f) % 360f, cx, cy) })
        }
        val brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader = sweep
        }
        val breathe = 0.5f + 0.5f * sin(sec * 1.1f)   // 0..1
        val bloomW = 9f * d + 5f * d * breathe
        val bloomA = (120 + 110 * breathe) / 255f
        // The bloom: wide strokes, each fainter than the one inside it.
        for (i in 0 until 4) {
            drawRoundRect(brush, topLeft, box, rad, alpha = bloomA * (0.45f - 0.1f * i).coerceAtLeast(0.05f),
                style = Stroke(bloomW + 6f * d * (3 - i) + 4f * d * i))
        }
        drawRoundRect(brush, topLeft, box, rad, style = Stroke(4f * d))
    }
}

/** The whole cover. [neon] swaps the black backdrop for the violet one with the orrery and edge light. */
@Composable
internal fun ScreensaverScene(neon: Boolean, time: String, date: String, unlockLabel: String, onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize().testTag("screensaver").background(if (neon) Color(0xFF0A0014) else Color.Black)
            // Swallow taps to the app underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        if (neon) NeonBackdrop()
        Column(Modifier.align(Alignment.TopCenter).padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                time, color = if (neon) Color(0xFFEAFCFF) else Color(0xFFEDEDED), fontSize = 56.sp,
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light,
                style = if (neon) TextStyle(shadow = Shadow(Color(0xFF18E0FF), Offset.Zero, 28f)) else TextStyle.Default,
                modifier = Modifier.testTag("sstime"),
            )
            Text(date, color = Color(0xCCFFFFFF), fontSize = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).testTag("ssdate"))
        }
        // Unlock, near the bottom (clear of the system nav bar).
        val pill = RoundedCornerShape(24.dp)
        Text(
            unlockLabel, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).testTag("unlock")
                .background(Color(0x33FFFFFF), pill).border(1.dp, Color(0x66FFFFFF), pill)
                .clickable(onClick = onUnlock).padding(horizontal = 40.dp, vertical = 12.dp),
        )
    }
}

package com.diegonmarcos.superapp.launcher.themes.cloud

// Siblings this mode still talks to (the shell dispatcher, the tile
// contracts). Owning a folder means owning the DESIGN, not seceding
// from the app.
import com.diegonmarcos.superapp.launcher.*
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.lockOnDoubleTap

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.isActive

/**
 * Home — neon-violet wireframe rotating cube. Edges only, no faces,
 * with a soft outer glow + sharp inner stroke for a "neon tube" feel
 * that matches the linktree palette without needing per-face shaders
 * (which would require a real GL textured cube — out of scope for the
 * Canvas pipeline).
 *
 * Compose since #773 (was a FrameLayout around the RotatingCubeView custom View): [NeonCube] is a
 * Canvas whose frame clock is `withFrameNanos`, painted in the launcher palette's roles. The glow is
 * three widening low-alpha strokes: a Compose Canvas stays hardware-accelerated, where the old
 * BlurMaskFilter needed a software layer.
 */
class Home3DFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        // Configs → Launcher → Others → "Cube animation" / "Cube drag-to-spin". Read once; the
        // fragment recreates its view when the toggle flips via chrome re-render.
        val animate = remember {
            runCatching { com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).anim("cube_anim") }.getOrDefault(true)
        }
        val interactive = remember {
            runCatching { com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).toggle("cube_interactive") }.getOrDefault(false)
        }
        // Galaxy backdrop lives at the shell level (R.id.galaxy_backdrop in activity_main.xml) so it
        // extends edge-to-edge into the status-bar area. This pane only draws the cube on top.
        // Double-tap anywhere on the home root → lock the screen (ScreenLocker); single taps on
        // tiles / chips still reach their own handlers.
        Box(Modifier.fillMaxSize().lockOnDoubleTap(ctx), contentAlignment = Alignment.Center) {
            // Oversize host so the cube's rotated corners don't clip against its own bounds.
            NeonCube(animate, interactive, Modifier.size(360.dp).testTag(TAG_CUBE))
        }
    }

    companion object {
        const val TAG_CUBE = "home3d:cube"
        fun newInstance() = Home3DFragment()
    }
}

/** The cube's maths, pure: 8 vertices, 12 edges, perspective projection. */
internal object CubeMath {
    /** Y full rotation period (ms) — 16s = matches the old feel. */
    const val PERIOD_Y_MS = 16_000.0
    /** X period — picked independently. With both axes driven from the same epoch and their own
     *  period, they always wrap cleanly: at no point does either angle skip a value. Different
     *  periods give a non-repeating wobble that reads as organic rather than mechanical. */
    const val PERIOD_X_MS = 23_500.0
    const val ROT_Y0 = -Math.PI.toFloat() / 5.5f
    const val ROT_X0 = Math.PI.toFloat() / 7f

    val vertices = arrayOf(
        floatArrayOf(-1f, -1f, -1f),
        floatArrayOf( 1f, -1f, -1f),
        floatArrayOf( 1f,  1f, -1f),
        floatArrayOf(-1f,  1f, -1f),
        floatArrayOf(-1f, -1f,  1f),
        floatArrayOf( 1f, -1f,  1f),
        floatArrayOf( 1f,  1f,  1f),
        floatArrayOf(-1f,  1f,  1f),
    )
    val edges = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    /**
     * Rotation is computed each frame from the monotonic clock — never reset. A ValueAnimator that
     * looped 0 → 2π over 16s while rotX advanced by `t * 0.4` left rotX at 0.8π at loop end — not a
     * multiple of 2π — so the cube visibly snapped back -144° on X every 16s. Time-based wrap
     * guarantees both axes are always continuous because we never reset the accumulator.
     */
    fun angleY(elapsedMs: Double): Float = (ROT_Y0 + (elapsedMs / PERIOD_Y_MS) * (2.0 * Math.PI)).toFloat()
    fun angleX(elapsedMs: Double): Float = (ROT_X0 + (elapsedMs / PERIOD_X_MS) * (2.0 * Math.PI)).toFloat()

    class Projected(val x: FloatArray, val y: FloatArray, val z: FloatArray)

    /** Project the 8 vertices into a [width] × [height] box at rotation ([rotX], [rotY]). */
    fun project(rotX: Float, rotY: Float, width: Float, height: Float): Projected {
        val cx = width / 2f
        val cy = height / 2f
        // Smaller scale denominator → larger cube; ~3.4f clipped at rotated corners. 4.6 keeps the
        // cube safely inside the 360dp host on every rotation.
        val scale = minOf(width, height) / 4.6f
        val perspective = 4.5f
        val cyR = Math.cos(rotY.toDouble()).toFloat()
        val syR = Math.sin(rotY.toDouble()).toFloat()
        val cxR = Math.cos(rotX.toDouble()).toFloat()
        val sxR = Math.sin(rotX.toDouble()).toFloat()
        val px = FloatArray(8); val py = FloatArray(8); val pz = FloatArray(8)
        for (i in 0..7) {
            val v = vertices[i]
            val x1 = v[0] * cyR + v[2] * syR
            val z1 = -v[0] * syR + v[2] * cyR
            val y2 = v[1] * cxR - z1 * sxR
            val z2 = v[1] * sxR + z1 * cxR
            val k = perspective / (perspective + z2)
            px[i] = cx + x1 * scale * k
            py[i] = cy + y2 * scale * k
            pz[i] = z2
        }
        return Projected(px, py, pz)
    }

    /** Edges in the back fade slightly so depth reads: alpha 255 at the front down to 135. */
    fun edgeAlpha(avgZ: Float): Float = (255 - (avgZ * 80f).coerceIn(0f, 120f)) / 255f
}

/**
 * Wireframe cube with a neon-glow stroke (outer soft halo + inner sharp line), continuously
 * rotating. [animate] off freezes it; [interactive] lets a drag spin it and coasts on release.
 */
@Composable
internal fun NeonCube(animate: Boolean, interactive: Boolean, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val start = remember { SystemClock.elapsedRealtime() }
    // Drag / momentum state (only used when interactive).
    val rot = remember { floatArrayOf(CubeMath.ROT_X0, CubeMath.ROT_Y0) }   // [x, y]
    val vel = remember { floatArrayOf(0f, 0f) }                              // angular velocity, rad/frame
    val dragging = remember { booleanArrayOf(false) }
    val frame = remember { mutableLongStateOf(0L) }
    val animating by rememberUpdatedState(animate)

    // The frame clock. Interactive: only while coasting; otherwise whenever animation is on.
    val drawing = com.diegonmarcos.superapp.ui.rememberDrawing() // no frame callbacks while backgrounded / screen off
    LaunchedEffect(animate, interactive, drawing) {
        while (isActive && drawing) {
            androidx.compose.runtime.withFrameNanos { frame.longValue = it }
            if (interactive) {
                if (!dragging[0]) {      // coast: apply + decay momentum
                    rot[1] += vel[1]; rot[0] += vel[0]
                    vel[1] *= 0.95f; vel[0] *= 0.95f
                    if (kotlin.math.abs(vel[1]) < 1e-4f) vel[1] = 0f
                    if (kotlin.math.abs(vel[0]) < 1e-4f) vel[0] = 0f
                }
            } else if (!animating) {
                break
            }
        }
    }

    Canvas(modifier.then(if (interactive) Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { dragging[0] = true; vel[0] = 0f; vel[1] = 0f },
            onDragEnd = { dragging[0] = false },
            onDragCancel = { dragging[0] = false },
        ) { change, drag ->
            change.consume()
            val k = 0.01f   // radians per pixel
            vel[1] = drag.x * k           // horizontal drag → spin about Y
            vel[0] = -drag.y * k          // vertical drag → tumble about X
            rot[1] += vel[1]; rot[0] += vel[0]
            frame.longValue++             // repaint now
        }
    } else Modifier)) {
        frame.longValue   // read: every frame tick repaints
        val (rx, ry) = if (interactive) rot[0] to rot[1] else {
            // Animation off → the cube is frozen at its resting angles, however often it repaints.
            val elapsed = if (animating) (SystemClock.elapsedRealtime() - start).toDouble() else 0.0
            CubeMath.angleX(elapsed) to CubeMath.angleY(elapsed)
        }
        val pr = CubeMath.project(rx, ry, size.width, size.height)
        fun pt(i: Int) = Offset(pr.x[i], pr.y[i])

        // Draw in 3 passes: outer glow → sharp → accent dots at vertices.
        val glow = p.accent
        for ((w, a) in listOf(18f to 0.12f, 12f to 0.22f, 7f to 0.32f)) {
            for (e in CubeMath.edges) drawLine(glow.copy(alpha = a), pt(e[0]), pt(e[1]), w, StrokeCap.Round)
        }
        for (e in CubeMath.edges) {
            val avgZ = (pr.z[e[0]] + pr.z[e[1]]) / 2f
            drawLine(p.textPrimary.copy(alpha = CubeMath.edgeAlpha(avgZ)), pt(e[0]), pt(e[1]), 4f, StrokeCap.Round)
        }
        for (i in 0..7) drawCircle(p.accent, 4f, pt(i))
    }
}

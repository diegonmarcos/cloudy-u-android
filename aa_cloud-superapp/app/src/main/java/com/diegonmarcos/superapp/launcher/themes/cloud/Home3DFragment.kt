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
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Home — the launcher's animated background: a neon-violet WIREFRAME regular
 * polytope, continuously rotating, edges only with a soft glow for the "neon
 * tube" feel that matches the linktree palette (no per-face shaders needed).
 *
 * The shape used to be a hard-wired cube. Since #1621 it is any of the regular
 * polytopes [PolytopeMath] builds — the five 3D Platonic solids and their 4D
 * analogues (a 4D polytope renders as a rotating 4D→3D→2D projection in this
 * same slot) — chosen in Configs ▸ Launcher ▸ Quick Actions ▸ UI ▸ Background,
 * and optionally auto-cycled every N minutes. The maths is all in PolytopeMath;
 * this file owns only the drawing and the one `withFrameNanos` loop (reused for
 * the 4D clock too — no second animator, for battery).
 *
 * Compose since #773 (was a FrameLayout around a RotatingCubeView custom View):
 * the Canvas frame clock is `withFrameNanos`, painted in the launcher palette's
 * roles; the glow is widening low-alpha strokes, which stays hardware-accelerated
 * where the old BlurMaskFilter needed a software layer.
 */
class Home3DFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val prefs = remember { runCatching { LauncherSettingsPrefs(ctx) }.getOrNull() }
        // Read the choices once; the fragment recreates its view when a toggle
        // flips via the chrome re-render, exactly as the cube toggle already did.
        val enabled = remember { runCatching { prefs?.backgroundEnabled }.getOrNull() ?: true }
        // "Cube animation" (battery group) still gates MOTION; "Animated background" gates DRAWING.
        val animate = remember { runCatching { prefs?.anim("cube_anim") }.getOrNull() ?: true }
        val interactive = remember { runCatching { prefs?.toggle("cube_interactive") }.getOrNull() ?: false }
        val baseShape = remember { runCatching { prefs?.backgroundShape }.getOrNull() ?: PolytopeMath.DEFAULT_SHAPE }
        val cycleOn = remember { runCatching { prefs?.backgroundCycleEnabled }.getOrNull() ?: false }
        val cycleMinutes = remember { runCatching { prefs?.backgroundCycleMinutes }.getOrNull() ?: 5 }
        val cycleIds = remember { runCatching { prefs?.cycleShapeIds() }.getOrNull() ?: listOf(baseShape) }

        Box(Modifier.fillMaxSize().lockOnDoubleTap(ctx), contentAlignment = Alignment.Center) {
            if (enabled) {
                // Oversize host so rotated corners don't clip against its own bounds.
                PolytopeBackground(
                    animate = animate, interactive = interactive,
                    baseShape = baseShape, cycleOn = cycleOn, cycleMinutes = cycleMinutes, cycleIds = cycleIds,
                    modifier = Modifier.size(360.dp).testTag(TAG_CUBE),
                )
            }
        }
    }

    companion object {
        /** Kept from the cube era: the home background's test tag. */
        const val TAG_CUBE = "home3d:cube"
        fun newInstance() = Home3DFragment()
    }
}

/**
 * The wireframe polytope background. [baseShape] is the chosen shape; when
 * [cycleOn] and more than one [cycleIds] shape is selected, the active shape
 * advances every [cycleMinutes] minutes off the SAME monotonic clock the
 * rotation reads (so no second timer runs). [animate] off freezes it;
 * [interactive] lets a drag spin the 3D stage and coast on release.
 */
@Composable
internal fun PolytopeBackground(
    animate: Boolean,
    interactive: Boolean,
    baseShape: String,
    cycleOn: Boolean,
    cycleMinutes: Int,
    cycleIds: List<String>,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    val start = remember { SystemClock.elapsedRealtime() }
    val cycling = cycleOn && cycleIds.size > 1
    // Drag / momentum state (only used when interactive, which only applies to the 3D stage).
    val rot = remember { floatArrayOf(PolytopeMath.ROT_X0, PolytopeMath.ROT_Y0) }   // [x, y]
    val vel = remember { floatArrayOf(0f, 0f) }
    val dragging = remember { booleanArrayOf(false) }
    val frame = remember { mutableLongStateOf(0L) }
    val animating by rememberUpdatedState(animate)

    // Pre-warm the meshes we may draw, OFF the main thread, so the first frame a
    // big polytope (120-/600-cell) appears never hitches building it inline.
    LaunchedEffect(baseShape, cycleIds) {
        withContext(Dispatchers.Default) {
            runCatching {
                PolytopeMath.mesh(baseShape)
                for (id in cycleIds) PolytopeMath.mesh(id)
            }
        }
    }

    // The frame clock — the ONE loop. Interactive: runs while coasting; otherwise whenever animation
    // is on. Auto-cycle keeps it running (the shape must be able to change even on a still shape).
    val drawing = com.diegonmarcos.superapp.ui.rememberDrawing() // no frame callbacks while backgrounded / screen off
    LaunchedEffect(animate, interactive, cycling, drawing) {
        while (isActive && drawing) {
            androidx.compose.runtime.withFrameNanos { frame.longValue = it }
            if (interactive) {
                if (!dragging[0]) {      // coast: apply + decay momentum
                    rot[1] += vel[1]; rot[0] += vel[0]
                    vel[1] *= 0.95f; vel[0] *= 0.95f
                    if (kotlin.math.abs(vel[1]) < 1e-4f) vel[1] = 0f
                    if (kotlin.math.abs(vel[0]) < 1e-4f) vel[0] = 0f
                }
            } else if (!animating && !cycling) {
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
            vel[1] = drag.x * k
            vel[0] = -drag.y * k
            rot[1] += vel[1]; rot[0] += vel[0]
            frame.longValue++
        }
    } else Modifier)) {
        frame.longValue   // read: every frame tick repaints
        // Which shape right now (auto-cycle reads the shared wall clock so it is
        // stable across view recreations).
        val shapeId = if (cycling)
            cycleIds[PolytopeMath.cycleIndex(cycleIds.size, cycleMinutes, SystemClock.elapsedRealtime().toDouble())]
        else baseShape
        val mesh = PolytopeMath.mesh(shapeId)

        val elapsed = if (animating) (SystemClock.elapsedRealtime() - start).toDouble() else 0.0
        val pr = if (interactive)
            PolytopeMath.project(mesh, elapsed, size.width, size.height, rotXOverride = rot[0], rotYOverride = rot[1])
        else
            PolytopeMath.project(mesh, elapsed, size.width, size.height)

        fun pt(i: Int) = Offset(pr.x[i], pr.y[i])
        val edges = pr.edges
        val glow = p.accent
        // Performance: the big polytopes carry hundreds of edges, so the three-pass
        // glow (which triples the draw count) is reserved for the lighter shapes;
        // dense hulls get one soft pass + the sharp line. The sharp pass always runs.
        val heavy = edges.size > 120
        val glowPasses = if (heavy) listOf(10f to 0.16f) else listOf(18f to 0.12f, 12f to 0.22f, 7f to 0.32f)
        for ((w, a) in glowPasses) {
            for (e in edges) drawLine(glow.copy(alpha = a), pt(e[0]), pt(e[1]), w, StrokeCap.Round)
        }
        for (e in edges) {
            val avgZ = (pr.depth[e[0]] + pr.depth[e[1]]) / 2f
            drawLine(p.textPrimary.copy(alpha = PolytopeMath.edgeAlpha(avgZ)), pt(e[0]), pt(e[1]), 4f, StrokeCap.Round)
        }
        // Accent dots at the vertices — skipped for dense hulls (a sea of dots reads as noise and costs draws).
        if (!heavy) for (i in pr.x.indices) drawCircle(p.accent, 4f, pt(i))
    }
}

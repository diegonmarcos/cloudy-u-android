package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The owner's "first row is overflowing on the border": the home screen (status strip over Cloud > Apps' grouped grid) on the
 * Samsung A37 (SM-A376B, 1080 x 2340 px, one centre punch-hole) over the whole settings matrix.
 *
 *   width  1080 px always. Samsung's Screen zoom changes the DENSITY, not the pixels: 420 dpi = 411 dp, 450 dpi = 384 dp
 *          (the two stock densities), 480 dpi = 360 dp and 508 dpi = 340 dp (the two larger zoom steps).
 *   font   1.0, 1.15, 1.3.
 *   camera Rect(508, 0, 572, 86) px, dispatched as the display cutout.
 *
 *   G1 no home-screen child's bounds leave the screen width
 *   G2 no status-strip child leaves the safe width (10 dp gutters) or touches the camera
 *   G3 the left cluster, the clock and the right cluster do not overlap one another
 *
 * Evidence: app/build/home-overflow/<dpi>dpi-<w>dp@<font>.png (CI uploads the folder).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeOverflowMatrixTest(private val dpi: Int, private val fontScale: Float) : KitPageHarness() {

    companion object {
        val DPIS = listOf(420, 450, 480, 508)
        val FONTS = listOf(1.0f, 1.15f, 1.3f)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dpi@{1}")
        fun params(): List<Array<Any>> = DPIS.flatMap { d -> FONTS.map { f -> arrayOf<Any>(d, f) } }

        val OUT = File(System.getProperty("user.dir"), "build/home-overflow").apply { mkdirs() }
        val CAMERA = Rect(508, 0, 572, 86)
    }

    private val wDp get() = (1080 * 160f / dpi).toInt()

    @Before fun setUp() {
        val hDp = (2340 * 160f / dpi).toInt()
        RuntimeEnvironment.setQualifiers("w${wDp}dp-h${hDp}dp-${dpi}dpi")
        RuntimeEnvironment.setFontScale(fontScale)
        ShellFxState.frozen = true
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    private fun insets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, CAMERA.bottom, 0, 0))
        .setDisplayCutout(DisplayCutoutCompat(Rect(0, CAMERA.bottom, 0, 0), listOf(CAMERA)))
        .build()

    private class Box(val tag: String, val l: Float, val t: Float, val r: Float, val b: Float)

    private fun boxes(prefix: String? = null): List<Box> = compose.onAllNodes(SemanticsMatcher("tagged") { n ->
        val t = n.config.getOrNull(SemanticsProperties.TestTag)
        t != null && (prefix == null || t.startsWith(prefix))
    }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).map {
        val b = it.boundsInWindow
        Box(it.config.getOrNull(SemanticsProperties.TestTag)!!, b.left, b.top, b.right, b.bottom)
    }

    @Test fun `home screen fits the A37 at this density and font scale`() {
        lateinit var root: View
        lateinit var model: StatusStripModel
        compose.runOnUiThread {
            val a = compose.activity
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
            val strip = ComposeView(a)
            col.addView(strip, LinearLayout.LayoutParams(-1, -2))
            val pane = FrameLayout(a).apply { id = View.generateViewId() }
            col.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
            a.setContentView(col)
            model = LauncherStatusStrip.install(a, strip)
            a.supportFragmentManager.beginTransaction().add(pane.id, GroupedTilesFragment.newInstance("cloud")).commitNow()
            root = col
        }
        compose.waitForIdle()
        // Every window-insets pass Robolectric makes (it re-dispatches its own, cutout-less ones on each layout) is replaced
        // at the root by the A37's, the way the platform dispatches them, so the strip always holds the camera's height.
        compose.runOnUiThread {
            val col = root as LinearLayout
            ViewCompat.setOnApplyWindowInsetsListener(col) { _, _ ->
                for (i in 0 until col.childCount) ViewCompat.dispatchApplyWindowInsets(col.getChildAt(i), insets())
                WindowInsetsCompat.CONSUMED
            }
            ViewCompat.requestApplyInsets(col)
            ViewCompat.dispatchApplyWindowInsets(col.getChildAt(0), insets())
        }
        compose.waitForIdle()
        // Robolectric's own later passes can still hand the strip cutout-less insets: give the model the A37's reading
        // directly (StatusStripCutoutTest owns the dispatch plumbing; this test owns the geometry).
        compose.runOnUiThread { model.noteInsets(insets()) }
        compose.waitForIdle()

        val name = "${dpi}dpi-${wDp}dp@$fontScale"
        val screenW = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        val d = compose.activity.resources.displayMetrics.density
        val all = boxes()
        val strip = all.filter { it.tag.startsWith("strip:") && it.tag != "strip:row" && it.tag != "strip:reserve" }

        compose.runOnUiThread {
            val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(bmp).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) }
            // The camera, drawn where the A37 has it, so the PNG shows what it is clear of.
            Canvas(bmp).drawRect(CAMERA, android.graphics.Paint().apply { color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE })
            File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(OUT, "$name.tsv").writeText(buildString {
            append("screen\t$name\tscreenW=$screenW\tdensity=$d\tfontScale=${compose.activity.resources.configuration.fontScale}\tcutoutTopPx=${model.cutoutTopPx}\treservePx=${model.clockReservePx()}\n")
            for (b in all) append("${b.tag}\t${b.l}\t${b.t}\t${b.r}\t${b.b}\n")
        })

        assertTrue("$name: no strip children", strip.size >= 13)
        assertTrue("$name: no home tiles", all.any { !it.tag.startsWith("strip:") })
        // G1
        for (b in all) assertTrue("$name: ${b.tag} spans ${b.l}..${b.r} of $screenW px", b.l >= -0.5f && b.r <= screenW + 0.5f)
        // G2
        val gutter = 10 * d
        val cam = androidx.compose.ui.geometry.Rect(CAMERA.left.toFloat(), CAMERA.top.toFloat(), CAMERA.right.toFloat(), CAMERA.bottom.toFloat())
        for (b in strip) {
            assertTrue("$name: ${b.tag} spans ${b.l}..${b.r}, outside the safe ${gutter}..${screenW - gutter}",
                b.l >= gutter - 1f && b.r <= screenW - gutter + 1f)
            assertTrue("$name: ${b.tag} ${b.l},${b.t}..${b.r},${b.b} touches the camera (cutoutTopPx=${model.cutoutTopPx} reserve=${model.clockReservePx()})", !androidx.compose.ui.geometry.Rect(b.l, b.t, b.r, b.b).overlaps(cam))
        }
        // G3
        val clock = strip.first { it.tag == "strip:clock" }
        val rightTags = setOf("strip:ram", "strip:storage", "strip:cpu", "strip:fps", "strip:battery")
        for (b in strip) when {
            b.tag in rightTags -> assertTrue("$name: ${b.tag} (${b.l}) runs into the clock (${clock.r})", b.l >= clock.r)
            b.tag != "strip:clock" -> assertTrue("$name: ${b.tag} (${b.r}) runs into the clock (${clock.l})", b.r <= clock.l)
        }
    }
}

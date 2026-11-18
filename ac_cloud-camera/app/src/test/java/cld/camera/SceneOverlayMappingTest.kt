package cld.camera

import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.FrameMapping
import com.diegonmarcos.superapp.vision.TextLayout
import com.diegonmarcos.superapp.vision.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1805 the Objects overlay's coordinate mapping both ways: a frame's boxes onto the view (rotation, the front
 * camera's mirror, the preview's FILL_CENTER crop, an opened image's FIT_CENTER letterbox), and a tap on the view back
 * into the frame, where the text layout is hit-tested. Every rotation x mirror x scale mode round-trips; a tap on a
 * drawn word lands in that word.
 */
class SceneOverlayMappingTest {
    private val eps = 0.01f

    @Test fun everyRotationMirrorAndScaleModeRoundTrips() {
        val points = listOf(0f to 0f, 12f to 34f, 300f to 200f, 639f to 479f)
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) for (mirror in listOf(false, true)) for (cover in listOf(true, false))
            for (view in listOf(1080 to 2400, 2400 to 1080, 640 to 480)) {
                for ((x, y) in points) {
                    val (vx, vy) = FrameMapping.pointToView(x, y, 640, 480, rotation, mirror, view.first, view.second, cover)
                    val (bx, by) = FrameMapping.viewToImage(vx, vy, 640, 480, rotation, mirror, view.first, view.second, cover)
                    val where = "rotation $rotation mirror $mirror cover $cover view $view point ($x, $y)"
                    assertEquals(where, x, bx, eps)
                    assertEquals(where, y, by, eps)
                }
            }
    }

    @Test fun coverCropAndFitLetterboxMapTapsBackExactly() {
        // 400x400 frame in a 400x800 view. Cover: scale 2, 200 px cropped each side, so view (0, 0) is frame (100, 0).
        val (cx, cy) = FrameMapping.viewToImage(0f, 0f, 400, 400, 0, false, 400, 800, cover = true)
        assertEquals(100f, cx, eps); assertEquals(0f, cy, eps)
        // Fit: scale 1, 200 px bars: view (0, 200) is frame (0, 0) and a tap on the bar is off the frame.
        val (fx, fy) = FrameMapping.viewToImage(0f, 200f, 400, 400, 0, false, 400, 800, cover = false)
        assertEquals(0f, fx, eps); assertEquals(0f, fy, eps)
        assertTrue(FrameMapping.viewToImage(10f, 100f, 400, 400, 0, false, 400, 800, cover = false).second < 0f)
        assertEquals(2f, FrameMapping.viewScale(400, 400, 0, 400, 800, cover = true), eps)
        assertEquals(1f, FrameMapping.viewScale(400, 400, 0, 400, 800, cover = false), eps)
    }

    @Test fun theFrontCameraMirrorFlipsTapsToo() {
        // 100x100 frame in a 100x100 view, mirrored: the view's left edge is the frame's right edge.
        val (x, _) = FrameMapping.viewToImage(5f, 50f, 100, 100, 0, true, 100, 100)
        assertEquals(95f, x, eps)
    }

    @Test fun aSensorOrientedFrameTurnedAQuarterMapsTapsIntoTheSensorFrame() {
        // 640x480 sensor frame shown upright (rotation 90) in a 480x640 view, no scaling: view (10, 20) is sensor
        // (20, 480 - 10) — the inverse of toUpright's (imageH - y, x).
        val (x, y) = FrameMapping.viewToImage(10f, 20f, 640, 480, 90, false, 480, 640)
        assertEquals(20f, x, eps); assertEquals(470f, y, eps)
    }

    private fun layout(): TextLayout = TextLayout.Builder(640, 480)
        .block("CLOUD CAMERA", Box(100f, 100f, 300f, 130f), "latin", listOf(
            TextLayout.Builder.L("CLOUD CAMERA", Box(100f, 100f, 300f, 130f), 0.9f, listOf(
                TextLayout.Builder.W("CLOUD", Box(100f, 100f, 190f, 130f)), TextLayout.Builder.W("CAMERA", Box(200f, 100f, 300f, 130f)))),
        ))
        .build()

    @Test fun aTapOnADrawnWordSelectsThatWordThroughTheMapping() {
        val l = layout()
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) for (cover in listOf(true, false)) {
            val vw = 1080; val vh = 2000
            val drawn = FrameMapping.boxToView(l.words[1].box, 640, 480, rotation, mirror, vw, vh, cover)
            val (x, y) = FrameMapping.viewToImage(drawn.centerX, drawn.centerY, 640, 480, rotation, mirror, vw, vh, cover)
            val sel = TextSelection.tap(l, null, x, y)
            assertEquals("rotation $rotation mirror $mirror cover $cover", TextSelection(1, 1), sel)
            assertEquals("CAMERA", sel!!.text(l))
        }
    }

    @Test fun aTapBesideTheTextSelectsNothingButTheFingerSlopReaches() {
        val l = layout()
        assertNull(TextSelection.tap(l, null, 50f, 115f))
        // 12 dp of finger at 2 view pixels per frame pixel is 6 frame pixels: 95 is within reach of CLOUD (100).
        assertEquals(TextSelection(0, 0), TextSelection.tap(l, null, 95f, 115f, slop = 12f / 2f))
    }
}

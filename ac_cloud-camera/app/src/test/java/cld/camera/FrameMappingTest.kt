package cld.camera

import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.FrameMapping
import org.junit.Assert.assertEquals
import org.junit.Test

/** The detection overlay's mapping: image coordinates to view coordinates, with rotation and mirroring. */
class FrameMappingTest {
    private val eps = 0.01f

    private fun near(want: Box, got: Box) {
        assertEquals("left", want.left, got.left, eps); assertEquals("top", want.top, got.top, eps)
        assertEquals("right", want.right, got.right, eps); assertEquals("bottom", want.bottom, got.bottom, eps)
    }

    @Test fun uprightImageSameAspectIsPlainScaling() {
        // 480x640 upright frame in a 960x1280 view: x2.
        near(Box(20f, 40f, 220f, 340f), FrameMapping.boxToView(Box(10f, 20f, 110f, 170f), 480, 640, 0, false, 960, 1280))
    }

    @Test fun coverCropsTheOverflowAndCentres() {
        // 400x400 frame in a 400x800 view: scale 2 (cover), 400 px of width cropped, 200 each side.
        near(Box(-200f, 0f, 0f, 200f), FrameMapping.boxToView(Box(0f, 0f, 100f, 100f), 400, 400, 0, false, 400, 800))
    }

    @Test fun fitLetterboxes() {
        // 400x400 frame in a 400x800 view, FIT: scale 1, 200 px bars above and below.
        near(Box(0f, 200f, 100f, 300f), FrameMapping.boxToView(Box(0f, 0f, 100f, 100f), 400, 400, 0, false, 400, 800, cover = false))
    }

    @Test fun rotation90TurnsTheSensorImageUpright() {
        // A 640x480 sensor frame turned 90 degrees clockwise is 480x640. Its top-left pixel (0,0) lands top-right (480,0).
        val (x, y) = FrameMapping.toUpright(0f, 0f, 640, 480, 90, false)
        assertEquals(480f, x, eps); assertEquals(0f, y, eps)
        // The sensor's top-right (640,0) lands bottom-right (480,640).
        val (x2, y2) = FrameMapping.toUpright(640f, 0f, 640, 480, 90, false)
        assertEquals(480f, x2, eps); assertEquals(640f, y2, eps)
        near(Box(380f, 0f, 480f, 100f), FrameMapping.boxToView(Box(0f, 0f, 100f, 100f), 640, 480, 90, false, 480, 640))
    }

    @Test fun rotation180And270() {
        val (a, b) = FrameMapping.toUpright(10f, 20f, 640, 480, 180, false)
        assertEquals(630f, a, eps); assertEquals(460f, b, eps)
        // 270: (x,y) -> (y, W - x): the sensor's top-left (0,0) lands bottom-left (0,640).
        val (c, d) = FrameMapping.toUpright(0f, 0f, 640, 480, 270, false)
        assertEquals(0f, c, eps); assertEquals(640f, d, eps)
    }

    @Test fun mirroringFlipsLeftRightInTheUprightImage() {
        near(Box(380f, 0f, 480f, 100f), FrameMapping.boxToView(Box(0f, 0f, 100f, 100f), 480, 640, 0, true, 480, 640))
        // Mirrored twice over the centre is the identity.
        val (x, _) = FrameMapping.toUpright(240f, 10f, 480, 640, 0, true)
        assertEquals(240f, x, eps)
    }

    @Test fun rotationAndMirroringCompose() {
        // 90 degrees then mirrored: sensor (0,0) -> upright (480,0) -> mirrored (0,0).
        val (x, y) = FrameMapping.toUpright(0f, 0f, 640, 480, 90, true)
        assertEquals(0f, x, eps); assertEquals(0f, y, eps)
    }

    @Test fun negativeAndLargeRotationsNormalise() {
        assertEquals(FrameMapping.toUpright(10f, 20f, 640, 480, 90, false), FrameMapping.toUpright(10f, 20f, 640, 480, -270, false))
        assertEquals(FrameMapping.toUpright(10f, 20f, 640, 480, 0, false), FrameMapping.toUpright(10f, 20f, 640, 480, 360, false))
    }

    @Test fun degenerateSizesMapToNothing() {
        near(Box(0f, 0f, 0f, 0f), FrameMapping.boxToView(Box(1f, 1f, 2f, 2f), 0, 0, 0, false, 100, 100))
        near(Box(0f, 0f, 0f, 0f), FrameMapping.boxToView(Box(1f, 1f, 2f, 2f), 100, 100, 0, false, 0, 0))
    }
}

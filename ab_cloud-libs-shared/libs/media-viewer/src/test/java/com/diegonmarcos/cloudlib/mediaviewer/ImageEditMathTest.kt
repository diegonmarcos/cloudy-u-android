package com.diegonmarcos.cloudlib.mediaviewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditMathTest {
    @Test fun `exif orientation is a rotation and a mirror`() {
        assertEquals(0 to false, ImageEditMath.orientation(1))
        assertEquals(0 to true, ImageEditMath.orientation(2))
        assertEquals(180 to false, ImageEditMath.orientation(3))
        assertEquals(180 to true, ImageEditMath.orientation(4))
        assertEquals(90 to true, ImageEditMath.orientation(5))
        assertEquals(90 to false, ImageEditMath.orientation(6))
        assertEquals(270 to true, ImageEditMath.orientation(7))
        assertEquals(270 to false, ImageEditMath.orientation(8))
        assertEquals("an unknown tag is upright", 0 to false, ImageEditMath.orientation(0))
        assertEquals(0 to false, ImageEditMath.orientation(99))
    }

    @Test fun `quarter turns swap the sides on odd counts only`() {
        assertEquals(400 to 300, ImageEditMath.rotatedSize(400, 300, 0))
        assertEquals(300 to 400, ImageEditMath.rotatedSize(400, 300, 1))
        assertEquals(400 to 300, ImageEditMath.rotatedSize(400, 300, 2))
        assertEquals(300 to 400, ImageEditMath.rotatedSize(400, 300, -1))
        assertEquals(300 to 400, ImageEditMath.rotatedSize(400, 300, 5))
    }

    @Test fun `a point follows its image through the turns and four turns come home`() {
        val p = P(0.25f, 0.75f)
        assertEquals(P(0.25f, 0.25f), ImageEditMath.rotatePoint(p, 1))
        assertEquals(P(0.75f, 0.25f), ImageEditMath.rotatePoint(p, 2))
        assertEquals(P(0.75f, 0.75f), ImageEditMath.rotatePoint(p, 3))
        assertEquals(p, ImageEditMath.rotatePoint(p, 4))
        var q = p
        repeat(4) { q = ImageEditMath.rotatePoint(q, 1) }
        assertEquals(p, q)
        assertEquals("a turn back is three turns forward", ImageEditMath.rotatePoint(p, 3), ImageEditMath.rotatePoint(p, -1))
        assertEquals("the top-left corner goes to the top-right on a clockwise turn", P(1f, 0f), ImageEditMath.rotatePoint(P(0f, 0f), 1))
    }

    @Test fun `the crop rectangle is in pixels, never empty and never outside`() {
        assertArrayEquals(intArrayOf(0, 0, 400, 300), ImageEditMath.cropRect(400, 300, CropFractions()))
        assertArrayEquals(intArrayOf(40, 30, 320, 240), ImageEditMath.cropRect(400, 300, CropFractions(0.1f, 0.1f, 0.1f, 0.1f)))
        assertArrayEquals(intArrayOf(100, 0, 300, 300), ImageEditMath.cropRect(400, 300, CropFractions(left = 0.25f)))
        val extreme = ImageEditMath.cropRect(400, 300, CropFractions(0.9f, 0.9f, 0.9f, 0.9f))
        assertEquals("each side is held to MAX_CROP", (400 * 0.45f).toInt(), extreme[0])
        assertTrue("a tenth is always left", extreme[2] >= 40 && extreme[3] >= 30)
        assertTrue(extreme[0] + extreme[2] <= 400 && extreme[1] + extreme[3] <= 300)
        val tiny = ImageEditMath.cropRect(1, 1, CropFractions(0.4f, 0.4f, 0.4f, 0.4f))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), tiny)
        assertTrue(CropFractions().isFull); assertFalse(CropFractions(left = 0.1f).isFull)
    }

    @Test fun `fit centres the image without distortion and normalize inverts it`() {
        val f = ImageEditMath.fit(400, 200, 1000f, 1000f)
        assertArrayEquals(floatArrayOf(0f, 250f, 1000f, 500f), f, 0.01f)
        val g = ImageEditMath.fit(200, 400, 1000f, 500f)
        assertArrayEquals(floatArrayOf(375f, 0f, 250f, 500f), g, 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), ImageEditMath.fit(0, 10, 100f, 100f), 0f)
        assertEquals(P(0.5f, 0.5f), ImageEditMath.normalize(500f, 500f, f))
        assertEquals("a touch outside lands on the edge", P(0f, 0f), ImageEditMath.normalize(-50f, 0f, f))
        assertEquals(P(1f, 1f), ImageEditMath.normalize(5000f, 5000f, f))
        assertEquals(P(0f, 0f), ImageEditMath.normalize(10f, 10f, floatArrayOf(0f, 0f, 0f, 0f)))
    }

    @Test fun `jpeg png and webp are saved in place, everything else as a png copy`() {
        assertEquals(SavePlan("a.jpg", "jpeg", true), ImageEditMath.savePlan("a.jpg"))
        assertEquals(SavePlan("a.JPEG", "jpeg", true), ImageEditMath.savePlan("a.JPEG"))
        assertEquals(SavePlan("a.png", "png", true), ImageEditMath.savePlan("a.png"))
        assertEquals(SavePlan("a.webp", "webp", true), ImageEditMath.savePlan("a.webp"))
        assertEquals(SavePlan("photo-edited.png", "png", false), ImageEditMath.savePlan("photo.heic"))
        assertEquals(SavePlan("anim-edited.png", "png", false), ImageEditMath.savePlan("anim.gif"))
        assertEquals(SavePlan("scan-edited.png", "png", false), ImageEditMath.savePlan("scan.bmp"))
        assertEquals(SavePlan("pic-edited.png", "png", false), ImageEditMath.savePlan("pic.avif"))
        assertEquals(SavePlan("noext-edited.png", "png", false), ImageEditMath.savePlan("noext"))
    }

    @Test fun `the pen palette has distinct opaque colours`() {
        assertTrue(ImageEditMath.PALETTE.size >= 4)
        assertEquals(ImageEditMath.PALETTE.size, ImageEditMath.PALETTE.toSet().size)
        assertTrue(ImageEditMath.PALETTE.all { (it ushr 24) == 0xFF })
    }
}

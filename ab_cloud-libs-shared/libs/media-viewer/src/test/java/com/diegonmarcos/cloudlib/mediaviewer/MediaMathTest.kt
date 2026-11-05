package com.diegonmarcos.cloudlib.mediaviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMathTest {
    @Test fun `the editor-core format names map to the three kinds`() {
        assertEquals(MediaKind.Image, MediaKind.of("image"))
        assertEquals(MediaKind.Audio, MediaKind.of("AUDIO"))
        assertEquals(MediaKind.Video, MediaKind.of("video"))
        assertNull(MediaKind.of("pdf"))
    }

    @Test fun `sample size halves while the larger side stays at least the maximum`() {
        assertEquals(1, MediaMath.sampleSize(1000, 800, 2048))
        assertEquals(1, MediaMath.sampleSize(2048, 1000, 2048))
        assertEquals(1, MediaMath.sampleSize(4000, 3000, 2048))
        assertEquals(2, MediaMath.sampleSize(8000, 6000, 2048))
        assertEquals(4, MediaMath.sampleSize(16000, 12000, 2048))
        assertEquals(1, MediaMath.sampleSize(0, 0, 2048))
        assertEquals(1, MediaMath.sampleSize(100, 100, 0))
    }

    @Test fun `clock formats minutes and hours`() {
        assertEquals("0:05", MediaMath.clock(5000))
        assertEquals("1:05", MediaMath.clock(65000))
        assertEquals("1:02:05", MediaMath.clock(3725000))
        assertEquals("0:00", MediaMath.clock(-10))
    }

    @Test fun `zoom stays between one and the maximum`() {
        assertEquals(1f, MediaMath.zoom(1f, 0.5f), 0f)
        assertEquals(2f, MediaMath.zoom(1f, 2f), 0f)
        assertEquals(8f, MediaMath.zoom(4f, 4f), 0f)
    }
}

package com.diegonmarcos.cloudlib.mediaviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifFormatTest {
    @Test fun `exposure aperture and focal length read like a camera`() {
        assertEquals("1/250 s", ExifFormat.exposure(0.004))
        assertEquals("1/60 s", ExifFormat.exposure(1.0 / 60))
        assertEquals("2.5 s", ExifFormat.exposure(2.5))
        assertEquals("30 s", ExifFormat.exposure(30.0))
        assertEquals("f/1.8", ExifFormat.aperture(1.8))
        assertEquals("f/2", ExifFormat.aperture(2.0))
        assertEquals("26 mm", ExifFormat.focal(26.0))
        assertEquals("5.7 mm", ExifFormat.focal(5.7))
    }

    @Test fun `gps has hemispheres and sizes have units`() {
        assertEquals("48.85660 N, 2.35220 E", ExifFormat.gps(48.8566, 2.3522))
        assertEquals("33.86880 S, 151.20930 W", ExifFormat.gps(-33.8688, -151.2093))
        assertEquals("12.0 MP", ExifFormat.megapixels(4000, 3000))
        assertEquals("999 B", ExifFormat.bytes(999))
        assertEquals("1.5 kB", ExifFormat.bytes(1500))
        assertEquals("2.5 MB", ExifFormat.bytes(2_500_000))
    }

    @Test fun `rows list the file facts first and leave out what the file lacks`() {
        val bare = ExifFormat.rows("a.png", 2048, 640, 480, ExifValues())
        assertEquals(listOf("Name", "Size", "Dimensions"), bare.map { it.first })
        assertEquals("640 x 480 (0.3 MP)", bare.last().second)
        val full = ExifFormat.rows(
            "IMG.jpg", 3_200_000, 4000, 3000,
            ExifValues("Google", "Pixel 8", "2026:10:01 12:30:00", 0.004, 1.68, 100, 6.9, 48.8566, 2.3522),
        )
        assertEquals(listOf("Name", "Size", "Dimensions", "Camera", "Taken", "Exposure", "Aperture", "ISO", "Focal length", "Location"), full.map { it.first })
        assertEquals("Google Pixel 8", full.first { it.first == "Camera" }.second)
        assertEquals("1/250 s", full.first { it.first == "Exposure" }.second)
        assertTrue(full.first { it.first == "Location" }.second.startsWith("48.85660 N"))
    }

    @Test fun `a model that already starts with the make is not repeated`() {
        val rows = ExifFormat.rows("x.jpg", 1, 1, 1, ExifValues(make = "SAMSUNG", model = "Samsung Galaxy S24"))
        assertEquals("Samsung Galaxy S24", rows.first { it.first == "Camera" }.second)
    }

    @Test fun `one coordinate alone is not a location`() {
        assertTrue(ExifFormat.rows("x", 1, 0, 0, ExifValues(latitude = 1.0)).none { it.first == "Location" })
    }
}

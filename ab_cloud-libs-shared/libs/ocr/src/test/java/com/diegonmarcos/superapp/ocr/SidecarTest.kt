package com.diegonmarcos.superapp.ocr

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarTest {
    private fun meta(sha: String = "a".repeat(64), size: Long = 10, mtime: Long = 5) = ScreenshotMeta(
        "s.png", "/x/s.png", size, sha, null, null, "image/png", 1080, 2400, 1L, mtime, 2L,
        mapOf("Orientation" to "1"), null, mapOf("bucket" to "Screenshots", "owner_package" to null), "com.android.settings",
        mapOf("model" to "Pixel", "android" to "15", "density" to 2.75), listOf("#112233" to 0.5), listOf("Text" to 0.8),
        mapOf("ocr" to "mlkit-text-latin 16.0.1"), 9L,
    )

    @Test fun fileNaming() {
        assertEquals("Screenshot_2026.md", SidecarNaming.md(File("/p/Screenshots/Screenshot_2026.png")).name)
        assertEquals("a.b.json", SidecarNaming.json(File("/p/a.b.jpeg")).name)
        assertEquals("/p/Screenshots", SidecarNaming.md(File("/p/Screenshots/x.png")).parent)
        assertEquals("noext", SidecarNaming.base("noext"))
        assertTrue(SidecarNaming.isSidecarName("x.MD")); assertFalse(SidecarNaming.isSidecarName("x.png"))
    }

    @Test fun jsonMatchesTheSchema() {
        val r = LayoutBuilder.build(Fixtures.battery(), 1080, 2400, "mlkit-text-latin", "16.0.1")
        val o = SidecarJson.build(meta(), r)
        assertEquals(emptyList<String>(), SidecarJson.validate(JSONObject(o.toString())))
        assertEquals(1, o.getJSONObject("ocr").getJSONArray("tables").length())
        assertEquals(9, o.getJSONObject("ocr").getJSONArray("tables").getJSONObject(0).getJSONArray("cells").length())
        assertEquals("Screenshots", o.getJSONObject("mediastore").getString("bucket"))
        assertTrue(o.getJSONObject("ocr").getJSONArray("languages").length() >= 1)
        val w = o.getJSONObject("ocr").getJSONArray("blocks").getJSONObject(0).getJSONArray("lines").getJSONObject(0).getJSONArray("words")
        assertTrue(w.length() >= 1)
    }

    @Test fun validateCatchesMissingParts() {
        val o = SidecarJson.build(meta(), LayoutBuilder.build(emptyList(), 1, 1, "e"))
        o.remove("device"); o.getJSONObject("file").put("sha256", "short")
        val p = SidecarJson.validate(o)
        assertTrue(p.contains("missing device")); assertTrue(p.contains("file.sha256 length"))
    }

    @Test fun idempotencySkipsOnMatchAndRedoesOnChange() {
        val rec = SidecarIdempotency.Recorded("h", 10, 5)
        assertTrue(SidecarIdempotency.isCurrent(rec, true, 10, 5) { error("no hash needed") })
        assertTrue(SidecarIdempotency.isCurrent(rec, true, 10, 99) { "h" })          // touched, same bytes
        assertFalse(SidecarIdempotency.isCurrent(rec, true, 10, 99) { "other" })    // edited
        assertFalse(SidecarIdempotency.isCurrent(rec, true, 11, 5) { "h" })         // size changed
        assertFalse(SidecarIdempotency.isCurrent(rec, false, 10, 5) { "h" })        // md missing
        assertFalse(SidecarIdempotency.isCurrent(null, true, 10, 5) { "h" })        // no json
    }

    @Test fun idempotencyOnRealFiles() {
        val dir = Fixtures.tmp()
        val img = File(dir, "s.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(SidecarIdempotency.isCurrent(img))
        val sha = SidecarIdempotency.sha256(img)
        assertEquals(64, sha.length)
        SidecarNaming.json(img).writeText(SidecarJson.build(meta(sha, img.length(), img.lastModified()), LayoutBuilder.build(emptyList(), 1, 1, "e")).toString())
        assertFalse("md still missing", SidecarIdempotency.isCurrent(img))
        SidecarNaming.md(img).writeText("x")
        assertTrue(SidecarIdempotency.isCurrent(img))
        img.writeBytes(byteArrayOf(9, 9, 9)); img.setLastModified(img.lastModified() + 5000)
        assertFalse("edited image is read again", SidecarIdempotency.isCurrent(img))
        assertNotNull(SidecarIdempotency.recorded(SidecarNaming.json(img).readText()))
    }

    @Test fun languagesAndScripts() {
        assertEquals(listOf("en-Latn"), Languages.detect("the app is not for you and this is on"))
        assertTrue(Languages.detect("Привет мир").contains("und-Cyrl"))
        assertEquals(emptyList<String>(), Languages.detect("12 34 %"))
    }
}

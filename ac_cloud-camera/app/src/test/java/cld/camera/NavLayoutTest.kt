package cld.camera

import cld.camera.ui.NavLayout
import cld.camera.ui.NavLayout.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preview is laid out above the nav: their bounds never intersect, for any window, inset or ratio. */
class NavLayoutTest {
    private val windows = listOf(1080 to 2400, 720 to 1280, 1440 to 3120, 1080 to 1920, 600 to 1024, 2000 to 1200)
    private val ratios = listOf(9 to 16, 16 to 9, 3 to 4, 4 to 3)

    @Test fun previewNeverIntersectsNav() {
        for ((w, h) in windows) for (topInset in listOf(0, 24, 96)) for (bottomInset in listOf(0, 16, 63, 132))
            for (navH in listOf(48, 72, 140)) for ((rw, rh) in ratios) {
                val nav = NavLayout.navBounds(w, h, navH, bottomInset)
                val preview = NavLayout.previewBounds(NavLayout.frameBounds(w, h, topInset, navH, bottomInset), rw, rh)
                assertFalse("$w x $h top=$topInset bottom=$bottomInset nav=$navH $rw:$rh: $preview vs $nav", preview.intersects(nav))
                assertTrue("preview ends at or above the nav's top edge", preview.bottom <= nav.top)
                assertTrue("preview stays inside the window", preview.left >= 0 && preview.right <= w && preview.top >= topInset)
            }
    }

    @Test fun navIsAtTheVeryBottom() {
        val nav = NavLayout.navBounds(1080, 2400, 120, 63)
        assertEquals(2400 - 63, nav.bottom)
        assertEquals(120, nav.height)
        assertEquals(1080, nav.width)
        assertEquals(0, NavLayout.navBounds(1080, 2400, 120, 0).let { 2400 - it.bottom })
    }

    @Test fun previewEndsAtNavTopWhenTheFrameIsTheLimit() {
        // A 20:9 phone: a 9:16 preview is height-limited, so it ends exactly at the nav's top edge.
        val nav = NavLayout.navBounds(1080, 2400, 120, 63)
        val frame = NavLayout.frameBounds(1080, 2400, 0, 120, 63)
        assertEquals(nav.top, frame.bottom)
        val p = NavLayout.previewBounds(frame, 9, 16)
        assertEquals(nav.top, p.bottom)
        assertTrue(p.width < 1080)
    }

    @Test fun intersectsIsFalseForTouchingEdges() {
        assertFalse(Box(0, 0, 10, 10).intersects(Box(0, 10, 10, 20)))
        assertTrue(Box(0, 0, 10, 11).intersects(Box(0, 10, 10, 20)))
    }

    @Test fun cameraModesAreDeclaredInNavOrder() {
        val order = CameraMode.entries.map { it.name }
        val flat = listOf("QR_SCAN", "OBJECTS", "AUTO", "FACE_RETOUCH", "PORTRAIT", "NIGHT", "HDR", "CAMERA", "VIDEO", "VR")
        assertEquals(flat, order)
    }

    @Test fun declaredNavOrderIsQrObjectsCameraVideoVr() {
        // The nav is data: build.json::ui.bottom_nav (the unit task runs in the app module, build.json is its parent).
        val ui = org.json.JSONObject(java.io.File("../build.json").readText()).getJSONObject("ui")
        val bar = ui.getJSONArray("bottom_nav")
        assertEquals(listOf("qr_scan", "objects", "photo", "video", "vr"), (0 until bar.length()).map { bar.getString(it) })
        // Every ui section id a bar item opens resolves to a mode: the section itself or the photo family's pages.
        val ids = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(bar.length(), ids.size)
        for (mode in CameraMode.entries) {
            val id = mode.name.lowercase()
            val pages = ui.getJSONArray("sections").let { a -> (0 until a.length()).flatMap { i -> a.getJSONObject(i).getJSONArray("pages").let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("id") } } } }
            assertTrue("$id is declared", id in ids || id in pages)
        }
    }
}

package com.diegonmarcos.hardtechcad

import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.ModelCache
import com.diegonmarcos.superapp.cad.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1695 the House Boat (OBJ) and the DJI S900 (STEP) in the declared catalogue with the owner's photos as previews, their originals only in the
 * originals repo (never in the APK), and STEP / STP files opened from another app routed to the B-rep viewer.
 */
class StepModelsTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/assets/cad/models.json").isFile }
    private val manifest = File(app, "src/main/assets/cad/models.json").readText()
    private val models = ModelManifest.parse(manifest)

    @Test fun bothModelsAreDeclaredWithAViewAndAPhoto() {
        val boat = models.first { it.id == "house-boat" }
        val drone = models.first { it.id == "dji-s900" }
        assertEquals("obj", boat.format); assertEquals("boats", boat.category)
        assertEquals("step", drone.format); assertEquals("mechanics", drone.category)
        for (m in listOf(boat, drone)) {
            val v = m.view!!
            assertTrue(m.id, File(app, "src/main/assets/" + v.asset).isFile)
            val thumb = File(app, "src/main/assets/" + v.thumb)
            assertTrue(m.id, thumb.isFile && thumb.readBytes().take(3) == listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            assertTrue(m.id + " sha256", Regex("^[0-9a-f]{64}$").matches(m.sha256))
            assertTrue(m.id + " is shown through its bundled GLB", (CadFormats.plan(m) as CadFormats.Plan.Show).asset == v.asset)
        }
        // rule 16: every earlier model stays
        assertTrue(models.map { it.id }.containsAll(listOf("one-lilo", "campidoglio", "drone")))
    }

    @Test fun noOriginalModelFileIsBakedIntoTheApk() {
        val bad = File(app, "src/main/assets").walk().filter { it.isFile }.map { it.extension.lowercase() }
            .filter { it in setOf("step", "stp", "igs", "iges", "obj", "skp", "3dm", "dxf", "ifc") }.toList()
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun anOpenedStepIsRoutedToTheBrepViewer() {
        assertEquals("step", OpenedFile.formatOf("DJI S900 STEP File.STEP", null))
        assertEquals("step", OpenedFile.formatOf("part.stp", "application/octet-stream"))
        assertEquals("step", OpenedFile.formatOf("download", "model/step"))
        assertNull(OpenedFile.formatOf("notes.txt", "text/plain"))
        val sha = "a".repeat(64)
        val m = OpenedFile.model("part.stp", "step", 10, sha)
        val plan = CadFormats.plan(m) as CadFormats.Plan.Show
        assertEquals(CadFormats.LIB_BREP, plan.lib)
        // served from the model cache under its hash: no download, the copy is the cache hit
        assertEquals(ModelCache.cachedName(sha, "step"), plan.path)
    }

    @Test fun theManifestAcceptsStepFromOtherApps() {
        val mf = File(app, "src/main/AndroidManifest.xml").readText()
        assertTrue("android.intent.action.VIEW" in mf)
        for (mime in OpenedFile.STEP_MIMES) assertTrue(mime, "android:mimeType=\"$mime\"" in mf)
        assertTrue(".*\\\\.step" in mf && ".*\\\\.stp" in mf)
        val act = File(app, "src/main/java/com/diegonmarcos/hardtechcad/MainActivity.kt").readText()
        assertTrue("OpenedFile.accept(intent)" in act && "onNewIntent" in act)
    }
}

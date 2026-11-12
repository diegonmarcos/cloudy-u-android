package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cgomniverse.ui.ModelPhotos
import com.diegonmarcos.cgomniverse.ui.ModelWeb
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1701 The model viewer's contract with the page, and the bundle holding no model file. */
class ModelPageTest {
    private val ref = ModelRef("https://x.example/a.glb", "ab".repeat(32), 6512592, "https://x.example/a.jpg", "STEP", "SolidWorks", "m", 237, 166007)
    private val world = World("dji", "DJI S900", "https://x.example/repo", "Model", "3D", "three.js", emptyList(), false, "d", model = ref)

    @Test fun theConfigCarriesTheGlbItsShaAndSize() {
        val c = JSONObject(ModelWeb.config(world, ref))
        assertEquals(ref.glb, c.getString("glb")); assertEquals(ref.sha256, c.getString("sha256"))
        assertEquals(ref.size, c.getLong("size")); assertEquals("DJI S900", c.getString("name"))
    }

    @Test fun theFactsLineNamesFormatPartsTrianglesAndSize() {
        assertEquals("STEP - 237 parts - 166,007 triangles - 6.2 MB", ModelWeb.facts(ref))
        assertEquals("OBJ - 1 part - 1.5 MB", ModelWeb.facts(ref.copy(format = "OBJ", parts = 1, triangles = 0, size = 1572864)))
    }

    @Test fun thePhotoLoaderRefusesNonHttpsAndNamesItsCacheByAddress() {
        assertNull(ModelPhotos.load(File("build/none"), "http://x.example/a.jpg"))
        assertNull(ModelPhotos.load(File("build/none"), "file:///etc/passwd"))
        assertEquals(ModelPhotos.cacheName("https://a/1"), ModelPhotos.cacheName("https://a/1"))
        assertTrue(ModelPhotos.cacheName("https://a/1") != ModelPhotos.cacheName("https://a/2"))
    }

    @Test fun noModelFileIsBakedIntoTheApp() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val baked = assets.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("glb", "gltf", "obj", "step", "stp") && !it.path.contains("/avatar/") }.toList()
        assertTrue("model files baked: $baked", baked.isEmpty())
        assertTrue(File(assets, "model/index.html").isFile)
        val page = File(assets, "model/index.html").readText()
        assertTrue("the viewer checks the SHA-256 and can cancel", page.contains("crypto.subtle.digest") && page.contains("AbortController"))
    }
}

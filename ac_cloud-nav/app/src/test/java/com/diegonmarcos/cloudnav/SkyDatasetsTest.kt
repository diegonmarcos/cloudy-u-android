package com.diegonmarcos.cloudnav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 the sky catalog is runtime datasets (Cloud Store), not APK assets: no asset left, readers use FleetData.get, manifest lists them, the files parse. */
class SkyDatasetsTest {
    private val pkg = "com.diegonmarcos.cloudnav"
    private val app = File(System.getProperty("user.dir")).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    private val fleet = File(app.parentFile.parentFile, "data/fleet")
    private val datasets = listOf("celestial/stars.6", "celestial/mw", "celestial/constellations.lines", "celestial/planets", "space/hyg_named")
    private fun src(rel: String) = File(app, "src/main/java/com/diegonmarcos/cloudnav/$rel").readText()

    @Test fun noAssetIsLeft() {
        datasets.forEach { assertFalse("$it must not be baked", File(app, "src/main/assets/$it.json").exists()) }
        assertFalse(File(app, "src/main/assets/space/hyg_stars.bin").exists())
    }

    @Test fun readersGoThroughFleetData() {
        assertTrue(src("sky/CelestialData.kt").contains("FleetData.get("))
        assertTrue(src("SpaceViewActivity.kt").contains("FleetData.get("))
        listOf("sky/CelestialData.kt", "sky/PlanetEphemeris.kt").forEach { assertFalse(src(it).contains("assets.open(")) }
        val html = File(app, "src/main/assets/space/space_view.html").readText()
        assertFalse(html.contains("fetch('../celestial/"))
    }

    @Test fun manifestListsThem() {
        val m = JSONObject(File(fleet, "manifest.json").readText())
        val listed = m.getJSONObject("apps").getJSONArray(pkg).let { a -> (0 until a.length()).map { a.getString(it) } }
        datasets.forEach { assertTrue("manifest lists $it", it in listed) }
        assertTrue(m.getJSONObject("shared").has("packs/hyg-stars"))
    }

    @Test fun servedFilesCarryASchemaVersionAndParse() {
        datasets.forEach { assertEquals(it, 1, JSONObject(File(fleet, "app/$pkg/$it.json").readText()).getInt("schemaVersion")) }
        assertTrue(JSONObject(File(fleet, "app/$pkg/celestial/stars.6.json").readText()).getJSONArray("features").length() > 1000)
    }

    @Test fun theStarPackDescriptorMatchesTheBlob() {
        val d = JSONObject(File(fleet, "packs/hyg-stars.json").readText())
        val blob = File(fleet, "packs/hyg-stars.bin")
        assertEquals(blob.length(), d.getLong("bytes"))
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(blob.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(sha, d.getString("sha256"))
    }
}

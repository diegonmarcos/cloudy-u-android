package com.diegonmarcos.cloudkgstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 kg.json is a runtime dataset (Cloud Store), not an APK asset: no asset left, the reader uses FleetData.get, the manifest lists it, the file parses. */
class KgDatasetTest {
    private val app = File(System.getProperty("user.dir")).let { if (File(it, "src/main").isDirectory) it else File(it, "app") }
    private val repo = app.parentFile.parentFile
    private fun src(rel: String) = File(app, "src/main/java/com/diegonmarcos/cloudkgstore/$rel").readText()

    @Test fun noAssetIsLeft() = assertFalse(File(app, "src/main/assets/kg/kg.json").exists())

    @Test fun readerUsesFleetData() {
        assertTrue(src("KgServices.kt").contains("FleetData.get("))
        assertFalse(src("KgServices.kt").contains("assets.open("))
    }

    @Test fun manifestListsIt() {
        val a = JSONObject(File(repo, "data/fleet/manifest.json").readText()).getJSONObject("apps").getJSONArray("com.diegonmarcos.cloudkgstore")
        assertTrue((0 until a.length()).any { a.getString(it) == "kg" })
    }

    @Test fun servedFileHasSchemaVersionAndParses() {
        assertEquals(1, JSONObject(Data.kgText).getInt("schemaVersion"))
        assertTrue(KgConfig.parse(Data.kgText).pages.isNotEmpty())
    }
}

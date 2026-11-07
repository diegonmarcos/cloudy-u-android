package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 the world-clock and holiday data are runtime datasets (Cloud Store), not APK assets: no asset left, readers use FleetData.get, manifest lists them, files parse. */
class CalcDatasetsTest {
    private val pkg = "com.diegonmarcos.cloudcalc"
    private val names = listOf("worldmap/cities", "worldmap/land", "datetime/holidays")
    private val fleet = File("../../data/fleet")
    private fun src(rel: String) = File("src/main/java/com/diegonmarcos/cloudcalc/$rel").readText()

    @Test fun noAssetIsLeft() {
        names.forEach { assertFalse("$it must not be baked", File("../data/assets/$it.json").exists()) }
    }

    @Test fun readersGoThroughFleetData() {
        assertTrue(src("ui/WorldMap.kt").contains("FleetData.get("))
        assertTrue(src("ui/ModeScreens.kt").contains("FleetData.get("))
        listOf("ui/WorldMap.kt", "ui/ModeScreens.kt").forEach { assertFalse(src(it).contains("assets.open(")) }
    }

    @Test fun manifestListsThem() {
        val a = JSONObject(File(fleet, "manifest.json").readText()).getJSONObject("apps").getJSONArray(pkg)
        val listed = (0 until a.length()).map { a.getString(it) }
        names.forEach { assertTrue("manifest lists $it", it in listed) }
    }

    @Test fun servedFilesCarryASchemaVersionAndParse() {
        names.forEach { assertEquals(it, 1, JSONObject(File(fleet, "app/$pkg/$it.json").readText()).getInt("schemaVersion")) }
        assertTrue(WorkingDays.parse(File(fleet, "app/$pkg/datetime/holidays.json").readText()).regions.size >= 10)
    }
}

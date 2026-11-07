package com.diegonmarcos.mediacenter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 the release notes are the runtime dataset `changelog` (Cloud Store), not APK assets: no asset left, the reader uses FleetData.get, the manifest lists it, the file parses. */
class ChangelogDatasetTest {
    private val pkg = "com.diegonmarcos.mediacenter"
    private val fleet = File("../../data/fleet")
    private val repo = File("src/main/kotlin/com/diegonmarcos/mediacenter/feature_node/presentation/help/data/ChangelogRepository.kt")

    @Test fun noAssetIsLeft() = assertFalse(File("src/main/assets/changelog").exists())

    @Test fun readerUsesFleetData() {
        val s = repo.readText()
        assertTrue(s.contains("FleetData.get("))
        assertFalse(s.contains("assets.open("))
    }

    @Test fun manifestListsIt() {
        val a = JSONObject(File(fleet, "manifest.json").readText()).getJSONObject("apps").getJSONArray(pkg)
        assertTrue((0 until a.length()).any { a.getString(it) == "changelog" })
    }

    @Test fun servedFileHasSchemaVersionAndAllReleases() {
        val d = JSONObject(File(fleet, "app/$pkg/changelog.json").readText())
        assertEquals(1, d.getInt("schemaVersion"))
        val r = d.getJSONArray("releases")
        assertEquals(15, r.length())
        for (i in 0 until r.length()) assertTrue(r.getJSONObject(i).getString("text").contains("version:"))
    }
}

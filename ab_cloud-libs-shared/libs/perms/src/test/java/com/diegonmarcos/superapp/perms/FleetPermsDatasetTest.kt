package com.diegonmarcos.superapp.perms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1547 fleet-perms is a runtime dataset, not a lib asset: libs:perms carries no data file, FleetPerms reads it through
 * libs:fleet-data-client, and the file Cloud Store serves (data/fleet/fleet-perms.json, listed in data/fleet/manifest.json) parses to the index.
 */
class FleetPermsDatasetTest {
    private val root = File("../../..")
    private val served = File(root, "data/fleet/fleet-perms.json")

    @Test fun `the lib holds no fleet-perms data asset`() {
        assertFalse(File("src/main/assets/fleet-perms.json").exists())
        assertFalse("FleetPerms must not open an asset", File("src/main/java/com/diegonmarcos/superapp/perms/FleetPerms.kt").readText().contains("assets.open"))
    }

    @Test fun `the reader goes through the fleet data client`() {
        val src = File("src/main/java/com/diegonmarcos/superapp/perms/FleetPerms.kt").readText()
        assertTrue(src.contains("FleetData.get("))
        assertEquals("fleet-perms", FleetPermsIndex.DATASET)
    }

    @Test fun `the manifest lists the dataset for every fleet app and the served file parses`() {
        val e = JSONObject(File(root, "data/fleet/manifest.json").readText()).getJSONObject("shared").getJSONObject(FleetPermsIndex.DATASET)
        assertEquals(3, e.getInt("tier"))
        assertEquals("*", e.getJSONArray("consumers").getString(0))
        assertTrue("the client needs an integer schemaVersion", JSONObject(served.readText()).optInt("schemaVersion", -1) >= 1)
        val idx = FleetPermsIndex.parse(served.readText())
        assertTrue(idx.apps.isNotEmpty())
        assertTrue(idx.labels().isNotEmpty())
    }
}

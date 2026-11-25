package com.diegonmarcos.superapp.appstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1562 appstore-priority and appstore-install-watch are served datasets: no APK asset, schemaVersion, in the manifest, and the bundled-safe DEFAULT equals what is served. */
class AppstoreServedDatasetsTest {
    private val fleet = File("../../../data/fleet")
    private fun served(n: String) = File(fleet, "$n.json").readText()

    @Test fun noAssetIsLeftInTheLib() {
        listOf("appstore-priority", "appstore-install-watch").forEach {
            assertFalse("$it.json is still an APK asset", File("src/main/assets/$it.json").exists())
        }
    }

    @Test fun servedFilesCarryASchemaVersionAndAreInTheManifest() {
        val shared = JSONObject(File(fleet, "manifest.json").readText()).getJSONObject("shared")
        listOf("appstore-priority", "appstore-install-watch").forEach {
            assertEquals(1, JSONObject(served(it)).getInt("schemaVersion"))
            assertTrue("$it is not in the manifest", shared.has(it))
            assertEquals(3, shared.getJSONObject(it).getInt("tier"))
        }
    }

    @Test fun theBundledSafeDefaultIsWhatIsServed() {
        val p = StorePriority.parse(JSONObject(served(StorePriority.DATASET)))
        val d = StorePriority.DEFAULT
        assertEquals(d.enabled, p.enabled); assertEquals(d.priority, p.priority); assertEquals(d.needAuto, p.needAuto)
        assertEquals(d.network, p.network); assertEquals(d.first, p.first); assertEquals(d.after, p.after)
        val w = StoreInstallWatch.parse(JSONObject(served(StoreInstallWatch.DATASET)))
        val dw = StoreInstallWatch.DEFAULT
        assertEquals(dw.timeoutMs, w.timeoutMs); assertEquals(dw.graceMs, w.graceMs); assertEquals(dw.retryLabel, w.retryLabel)
    }

    @Test fun theReadersGoThroughFleetDataNotAssets() {
        listOf("StorePriority", "StoreInstallWatch").forEach {
            val src = File("src/main/java/com/diegonmarcos/superapp/appstore/$it.kt").readText()
            assertTrue("$it must read FleetData", src.contains("FleetData.get(ctx, DATASET)"))
            assertFalse("$it still opens an asset", src.contains("assets.open"))
        }
    }

    /** #1562 the Store controls' look and the fleet-row actions are served datasets too: no asset, in the manifest, parsed by the real readers, readers off assets. */
    @Test fun controlsAndFleetActionsAreServedToo() {
        val shared = JSONObject(File(fleet, "manifest.json").readText()).getJSONObject("shared")
        for (n in listOf("appstore-controls", "appstore-fleet-actions")) {
            assertFalse("$n.json is still an APK asset", File("src/main/assets/$n.json").exists())
            assertEquals(1, JSONObject(served(n)).getInt("schemaVersion"))
            assertEquals(3, shared.getJSONObject(n).getInt("tier"))
        }
        assertEquals("appstore-controls", StoreControls.DATASET)
        assertEquals("appstore-fleet-actions", FleetActions.DATASET)
        StoreControls.parse(JSONObject(served("appstore-controls")))   // the real parser accepts the served file
        for (f in listOf("StoreControls", "AppsMesh", "FleetActions")) {
            val src = File("src/main/java/com/diegonmarcos/superapp/appstore/$f.kt").readText()
            assertTrue("$f must read FleetData", src.contains("FleetData.get(ctx"))
            assertFalse("$f still opens an asset", src.contains("assets.open"))
        }
    }
}

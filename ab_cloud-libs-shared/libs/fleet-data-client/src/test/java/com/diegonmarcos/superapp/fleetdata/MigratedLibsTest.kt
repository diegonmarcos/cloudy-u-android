package com.diegonmarcos.superapp.fleetdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1547 the shared libs are CODE ONLY. For every migrated dataset: its lib carries no data file in any source set, the lib's code reads it through
 * FleetData / FleetPack (never assets.open), data/fleet/manifest.json lists it, and the served file declares an integer schemaVersion (the file
 * Cloud Store pulls from main). Add a row here with each migration.
 */
class MigratedLibsTest {
    private val root = File("../../..")
    private val libs = File(root, "ab_cloud-libs-shared/libs")
    private val manifest = Json.parseToJsonElement(File(root, "data/fleet/manifest.json").readText()) as JsonObject

    /** lib -> (dataset, token the lib's code must contain). */
    private val migrated = listOf(
        Triple("perms", "fleet-perms", "FleetData.get("),
        Triple("bluetooth", "company-ids", "FleetData.get("),
        Triple("bluetooth", "gatt-catalog", "FleetData.get("),
        Triple("nfc", "nfc-catalog", "FleetData.get("),
        Triple("rclone", "rclone-remote-types", "FleetData.get("),
        Triple("file-editor", "file-editor-languages", "FleetData.get("),
        Triple("wifi", "packs/wifi-oui", "FleetPack.text("),
    )

    private fun dataFiles(lib: String) = File(libs, "$lib/src/main/assets").walkTopDown().filter { it.isFile && it.extension in setOf("json", "tsv", "csv", "geojson") }.toList()
    private fun code(lib: String) = File(libs, "$lib/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }

    @Test fun noMigratedDatasetFileIsLeftInItsLib() {
        for ((lib, ds, _) in migrated) {
            val name = ds.substringAfterLast('/')
            val left = dataFiles(lib).filter { it.name == "$name.json" || it.name == "$name.tsv" || it.name == name.removePrefix("wifi-") + ".tsv" }
            assertTrue("$lib still ships $ds: $left", left.isEmpty())
        }
    }

    @Test fun theReaderGoesThroughTheClient() {
        for ((lib, ds, token) in migrated) assertTrue("$lib must read $ds with $token", code(lib).contains(token))
    }

    @Test fun theManifestListsEveryDatasetAndTheServedFileCarriesASchemaVersion() {
        val shared = manifest["shared"] as JsonObject
        for ((_, ds, _) in migrated) {
            val e = shared[ds] as? JsonObject
            assertNotNull("$ds is not in data/fleet/manifest.json", e)
            val path = ((e!!["source"] as? JsonPrimitive)?.content ?: "data/fleet/$ds.json")
            val served = Json.parseToJsonElement(File(root, path).readText()) as JsonObject
            assertTrue("$path needs an integer schemaVersion", (served["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0 >= 1)
            assertTrue("$ds must be served to every fleet app", ((e["consumers"].toString())).contains("*"))
        }
    }

    @Test fun routingSendsEveryMigratedDatasetToTheStore() {
        for ((_, ds, _) in migrated) { assertEquals(FleetDataRouting.STORE, FleetDataRouting.authorityFor(ds)); assertFalse(!FleetDataRouting.validDataset(ds)) }
    }
}

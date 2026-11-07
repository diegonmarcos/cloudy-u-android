package org.fossify.phone

import org.fossify.phone.helpers.parseProviders
import org.fossify.phone.nav.KeypadSpec
import org.fossify.phone.nav.VoipSpec
import org.fossify.phone.sim.SimSchema
import org.fossify.phone.spam.SpamMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 the Dialer's declarations are runtime datasets (Cloud Store), not APK assets: no asset left, readers use FleetData, manifest lists them, files parse. */
class DialerDatasetsTest {
    private val pkg = "com.diegonmarcos.comms.dialer"
    private val names = listOf("call_providers", "keypad", "sim-fields", "spam_patterns", "voip_providers")
    private fun served(n: String) = File("../../data/fleet/app/$pkg/$n.json")
    private fun src(rel: String) = File("src/main/kotlin/org/fossify/phone/$rel").readText()

    @Test fun noAssetIsLeft() {
        names.forEach { assertFalse("$it.json must not be baked into the APK", File("src/main/assets/$it.json").exists()) }
    }

    @Test fun readersGoThroughFleetData() {
        assertTrue(src("helpers/FleetDataset.kt").contains("FleetData.get("))
        listOf("helpers/CallProviders.kt", "sim/SimSchema.kt", "nav/T9.kt", "nav/VoipPage.kt", "spam/SpamRules.kt").forEach {
            assertTrue("$it uses FleetDataset", src(it).contains("FleetDataset.get("))
            assertFalse("$it must not open an asset", src(it).contains("assets.open("))
        }
    }

    @Test fun manifestListsThem() {
        val m = File("../../data/fleet/manifest.json").readText()
        val block = m.substringAfter("\"$pkg\"").substringBefore("]")
        names.forEach { assertTrue("manifest lists $it", block.contains("\"$it\"")) }
    }

    @Test fun servedFilesCarrySchemaVersionAndParse() {
        names.forEach { assertTrue("$it schemaVersion", Regex("\"schemaVersion\"\\s*:\\s*1\\b").containsMatchIn(served(it).readText())) }
        assertTrue(parseProviders(served("call_providers").readText()).isNotEmpty())
        assertTrue(KeypadSpec.parse(served("keypad").readText()).keys.isNotEmpty())
        assertTrue(SimSchema.parse(served("sim-fields").readText()).fields.isNotEmpty())
        assertTrue(VoipSpec.parse(served("voip_providers").readText()).providers.isNotEmpty())
        assertTrue(SpamMatcher.parse(served("spam_patterns").readText()).isNotEmpty())
    }
}

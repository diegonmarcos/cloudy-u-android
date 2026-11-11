package com.diegonmarcos.superapp.adbdebug

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Declared needs come from the app's build.json::privileged_channel, never from a hand-typed list. */
class DeclaredNeedsTest {

    private val json = """{"_doc":"x","needs":[
        {"id":"silent-installs","label":"Silent installs","why":"pm install without a dialog","commands":["pm install","pm install-write"]},
        {"id":"perms-grant","label":"Perms grant","why":"pm grant","commands":["pm grant"]},
        {"id":"appops","why":"app-ops that pm grant leaves denied"},
        {"id":"silent-installs","label":"duplicate","why":"dropped"},
        {"label":"no id"}, 7, null]}"""

    @Test fun parsesTheDeclaredEntries() {
        val n = DeclaredNeeds.parse(json)
        assertEquals(listOf("silent-installs", "perms-grant", "appops"), n.map { it.id })
        assertEquals("Silent installs", n[0].label)
        assertEquals(listOf("pm install", "pm install-write"), n[0].commands)
        assertEquals("appops", n[2].label)                      // label falls back to the id
        assertEquals("app-ops that pm grant leaves denied", n[2].why)
        assertEquals(emptyList<String>(), n[2].commands)
    }

    @Test fun nothingDeclaredMeansNothingListed() {
        assertEquals(emptyList<Need>(), DeclaredNeeds.parse(null))
        assertEquals(emptyList<Need>(), DeclaredNeeds.parse(""))
        assertEquals(emptyList<Need>(), DeclaredNeeds.parse("{}"))
        assertEquals(emptyList<Need>(), DeclaredNeeds.parse("not json"))
        assertEquals(emptyList<Need>(), DeclaredNeeds.parse("""{"needs":"x"}"""))
    }

    @Test fun base64RoundTripAsTheBuildBakesIt() {
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals(3, DeclaredNeeds.parse(DeclaredNeeds.decode(b64)).size)
        assertEquals(null, DeclaredNeeds.decode(""))
        assertEquals(null, DeclaredNeeds.decode("%%%"))
    }

    /** Every app that links this module declares its needs in its own build.json. Since #1512 that is the SuperApp alone. */
    @Test fun everyConsumerAppDeclaresItsNeeds() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "aa_cloud-superapp/build.json").exists() }
        assumeTrue("not run from a full checkout", repo != null)
        for (app in listOf("aa_cloud-superapp")) {
            val block = JSONObject(File(repo, "$app/build.json").readText()).optJSONObject("privileged_channel")
            assertNotNull("$app declares no privileged_channel", block)
            val needs = DeclaredNeeds.parse(block.toString())
            assertTrue("$app declares no needs", needs.isNotEmpty())
            assertTrue("$app: a need without a reason", needs.all { it.why.isNotBlank() && it.label.isNotBlank() })
            assertEquals("$app: duplicate ids", needs.size, needs.map { it.id }.toSet().size)
        }
    }
}

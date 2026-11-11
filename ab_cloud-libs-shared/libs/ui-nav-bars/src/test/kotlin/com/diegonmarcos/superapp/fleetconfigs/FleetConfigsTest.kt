package com.diegonmarcos.superapp.fleetconfigs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The baked Configs > General / About declaration decodes to what the pages draw; a missing or broken one falls back, never crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FleetConfigsTest {
    private val json = """
        {"app":"ac_cloud-x","name":"Cloud X","version":"1.2.0","version_code":7,"sha":"abc123def456","application_id":"com.x",
         "store_pkg":"com.diegonmarcos.cloudstore","superapp_pkg":"com.diegonmarcos.superapp",
         "general":{"sections":[{"id":"perms"},{"id":"ui","note":"n"},{"id":"app","label":"Cloud X"}]},
         "build_date":"2026-10-31 10:00 UTC",
         "about":{"tagline":"X in short","description":"Does x.","features":["a","b"],"repo":"https://r","license":"MIT","privacy":"p",
                  "screens":[{"name":"Home","what":"h"},{"name":"","what":"dropped"}],"guide":["one","two"],
                  "perms":[{"id":"camera","label":"Camera","kind":"runtime","why":"scan","helper":""}],
                  "related":[{"app":"ac_cloud-store","name":"Cloud Store","pkg":"com.s","why":"updates"}],
                  "backends":[{"id":"jmap","why":"mail","kind":"mail","mesh":"http://stalwart.mesh:8080"}],"crash":"https://c","debug":"https://d"},
         "update":{"repo":"o/r","tag":"latest","assets":{"arm64-v8a":"X.apk","x86_64":"X-x86_64.apk"}}}
    """.trimIndent()

    @Test fun `fleet_agents rows decode for the Agents tab`() {
        val d = FleetConfigs.parse("""{"app":"x","agents":[{"id":"a1","name":"A One","kind":"worker","trigger":"periodic","summary":"does a"},{"id":"a2"}]}""")
        assertEquals(listOf("a1", "a2"), d.agents.map { it.id })
        assertEquals("A One", d.agents[0].name)
        assertEquals("a2", d.agents[1].name)   // a nameless row shows its id
        assertTrue(FleetConfigs.parse("""{"app":"x"}""").agents.isEmpty())
    }

    @Test fun `a declaration decodes field by field`() {
        val d = FleetConfigs.decode(java.util.Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals("Cloud X", d.name)
        assertEquals(7, d.versionCode)
        assertEquals(listOf("perms", "update", "ui", "app"), d.general.map { it.id })
        assertEquals("Perms", d.general[0].label)
        assertEquals("Cloud X", d.general[3].label)
        assertEquals("n", d.general[2].note)
        assertEquals("X in short", d.about.tagline)
        assertEquals("Does x.", d.about.description)
        assertEquals(listOf("Home"), d.about.screens.map { it.name })
        assertEquals("scan", d.about.perms.single().why)
        assertEquals("com.s", d.about.related.single().pkg)
        assertEquals("2026-10-31 10:00 UTC", d.buildDate)
        assertEquals("X-x86_64.apk", d.update.asset(listOf("x86_64", "x86")))
        assertEquals("X.apk", d.update.asset(listOf("riscv64")))
        assertEquals("ac_cloud-x", d.update.appDir)
        assertEquals(listOf("a", "b"), d.about.features)
        assertEquals("http://stalwart.mesh:8080", d.about.backends.single().mesh)
        assertEquals("com.diegonmarcos.cloudstore", d.storePkg)
    }

    @Test fun `no declaration still draws the six required sections and Update`() {
        val want = listOf("perms", "update") + FleetConfigs.REQUIRED_SECTIONS.drop(1)
        assertEquals(want, FleetConfigs.decode("").general.map { it.id })
        assertEquals(want, FleetConfigs.decode("not base64!").general.map { it.id })
    }

    @Test fun `an app that places Update itself keeps its place`() {
        val d = FleetConfigs.parse("""{"general":{"sections":[{"id":"ui"},{"id":"update"},{"id":"perms"}]}}""")
        assertEquals(listOf("ui", "update", "perms"), d.general.map { it.id })
    }

    @Test fun `a v1 declaration reads what as the description`() {
        assertEquals("Old.", FleetConfigs.parse("""{"about":{"what":"Old."}}""").about.description)
    }

    @Test fun `release notes and the up-to-date check`() {
        val notes = FleetReleases.parseNotes("""[{"sha":"abcdef1234567890","commit":{"message":"fix: x

body","committer":{"date":"2026-10-31T09:00:00Z"}}}]""")
        assertEquals(listOf(ReleaseNote("abcdef123456", "2026-10-31", "fix: x")), notes)
        val st = ReleaseState("abcdef1234567890", "", notes, "")
        assertTrue(st.upToDate("abcdef123456"))
        assertTrue(!st.upToDate("0123456789ab"))
        assertTrue(!st.upToDate("local"))
    }

    @Test fun `the licence reads as words`() {
        assertTrue(FleetConfigs.licenceLabel("LicenseRef-NoLicenseGranted").startsWith("All rights reserved"))
        assertEquals("MIT", FleetConfigs.licenceLabel("MIT"))
    }
}

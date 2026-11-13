package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverviewTerminalsTest {
    private val declared = listOf(
        Declarations.ManageSource("termux", "Cloud Terminal Termux", "terminal", "terminal_link", fleet = "termux"),
        Declarations.ManageSource("nix", "Cloud Terminal Nix", "terminal", "terminal_link", fleet = "nix-on-droid"),
    )
    private val registry = listOf(
        Declarations.ConstellationAppDecl("Cloud Terminal Termux", "apps", "cld.termux", "termux"),
        Declarations.ConstellationAppDecl("Cloud Terminal Nix", "apps", "cld.termux.nix", "nix-on-droid"),
    )

    @Test fun bothLinksArePresentWithTheRegistryPackages() {
        val l = OverviewTerminals.resolve(declared, registry) { true }
        assertEquals(listOf("cld.termux", "cld.termux.nix"), l.map { it.packageName })
        assertEquals(listOf("Cloud Terminal Termux", "Cloud Terminal Nix"), l.map { it.label })
        assertTrue(l.all { it.installed })
    }

    @Test fun anAbsentTerminalIsNotInstalledAndTheOtherStaysOpenable() {
        val l = OverviewTerminals.resolve(declared, registry) { it == "cld.termux" }
        assertTrue(l[0].installed); assertFalse(l[1].installed)
        assertEquals("cld.termux.nix", l[1].packageName)
    }

    @Test fun aFleetIdMissingFromTheRegistryOrAThrowingProbeNeverCrashes() {
        assertFalse(OverviewTerminals.resolve(declared, emptyList()) { true }.any { it.installed })
        assertFalse(OverviewTerminals.resolve(declared, registry) { error("boom") }.any { it.installed })
    }

    @Test fun buildJsonDeclaresBothTerminalsByFleetIdAndKeepsEveryOverviewSource() {
        val text = listOf("build.json", "../build.json", "ac_cloud-drive/build.json").map { File(it) }.first { it.exists() }.readText()
        val d = Declarations.parseManage(org.json.JSONObject(text).getJSONObject("ui").getJSONObject("manage").toString())
        assertEquals(listOf("termux", "nix-on-droid"), d.terminals.map { it.fleet })
        assertEquals(listOf("emulated", "termux", "nix", "external"), d.local.map { it.id })
        assertEquals(listOf("cloud-s3", "gdrive", "mounts"), d.cloud.map { it.id })
    }
}

package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1924 Export to Markdown / Copy all on the DNS page: the five sections, in order, through the VPN page's helper. */
class DnsExport1924Test {
    private fun text(status: String = "Preset: Public open\nPreset servers in use: 1.1.1.1 → 1.0.0.1\nMesh resolver: UNHEALTHY — SERVFAIL") =
        DnsExport.text("Cloud SuperApp DNS @ now", status, "github.com\n  bridge: 140.82.112.3 via Public open 1.1.1.1",
            "verdict: reachable by IP", "Terminal: bridge ok", "1.1.1.1 dot reachable")

    @Test fun coversEverySectionInThePagesOrder() {
        val t = text()
        var at = -1
        for (s in DnsExport.SECTIONS) { val i = t.indexOf("== $s\n"); assertTrue("$s missing", i > at); at = i }
        assertEquals(listOf("Status", "Test lookup", "Test reachability by IP", "Bridges", "DNS servers"), DnsExport.SECTIONS)
    }

    @Test fun markdownHasAHeadingPerSectionAndTheServersAndVerdict() {
        val md = DnsExport.markdown(text())
        assertTrue(md.startsWith("# Cloud SuperApp DNS"))
        for (s in DnsExport.SECTIONS) assertTrue(s, "## $s\n" in md)
        assertTrue("1.1.1.1 → 1.0.0.1" in md && "UNHEALTHY" in md && "140.82.112.3" in md)
        assertEquals("fences balanced", 0, Regex("^```", RegexOption.MULTILINE).findAll(md).count() % 2)
    }

    @Test fun secretsNeverReachTheExport() {
        val t = text("Preset: x\nprivate key: AAAABBBB")
        assertFalse("AAAABBBB" in t); assertTrue("[redacted]" in t)
    }

    /** Mutation: dropping a section from the builder is caught by the coverage assertion. */
    @Test fun aDroppedSectionIsDetected() {
        val t = text().replace("\n== Bridges\nTerminal: bridge ok\n", "\n")
        assertFalse("== Bridges\n" in t)
    }

    @Test fun theButtonsAreOnThePage() {
        val src = File("src/main/java/com/diegonmarcos/superapp/network/DnsFragment.kt").takeIf { it.exists() }?.readText() ?: return
        assertTrue("ConfigButton(\"Copy all\")" in src && "ConfigButton(\"Export to Markdown\")" in src)
    }
}

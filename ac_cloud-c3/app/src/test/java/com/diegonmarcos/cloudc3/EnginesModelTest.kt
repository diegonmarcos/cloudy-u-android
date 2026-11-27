package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.EnginesModel
import com.diegonmarcos.cloudc3.pages.FleetLive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1983 Ops > Health > Tests lists every engine of infra-obs_reports. The guard (a new engine folder with no card fails), the real mail report
 * (phases, checks and their evidence fields from a copy of the published JSON), the allow-listed run trigger and the no-fake-data states.
 */
class EnginesModelTest {
    private val dir = FleetFixtures.moduleDir()
    private val mailText = File(dir, "test/fixtures/cloud_mail_full.json").readText()
    private val mail = EnginesModel.ENGINES.first { it.id == "mail" }
    private fun mailParsed() = EnginesModel.parse(mail, mailText)!!

    /** The folders under infra-obs_reports/src/src, as last read from cloudy-u-containers (refresh the file with `ls` when an engine is added). */
    internal fun snapshotDirs() = File(dir, "test/fixtures/reports-engines.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test fun `every engine folder of infra-obs_reports has a card or is a declared library`() {
        val cards = EnginesModel.ENGINES.map { it.dir }.toSet()
        val unknown = snapshotDirs().filter { it !in cards && it !in EnginesModel.LIBS }
        assertTrue("engine folders with no card on Ops > Health > Tests: $unknown", unknown.isEmpty())
        val ghost = cards.filter { it !in snapshotDirs() }
        assertTrue("cards for folders that do not exist: $ghost", ghost.isEmpty())
    }

    @Test fun `the folder snapshot matches the real checkout when the sibling repo is present`() {
        val live = File(dir.parentFile.parentFile, "cloudy-u-containers/infra-obs_reports/src/src")
        if (!live.isDirectory) return
        val real = live.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
        assertEquals(real, snapshotDirs().sorted())
    }

    @Test fun `the mail report phases and checks come out of the real JSON with their own values`() {
        val r = mailParsed()
        assertEquals(listOf("path_checks", "instant_kpis", "preflight", "containers", "network", "dns_auth", "internals", "config_drift", "e2e_delivery"), r.phases.map { it.id })
        assertEquals(77, r.phases.sumOf { it.checks.size })
        assertEquals("2026-11-26T17:22:23.463437904+00:00", r.generated)
        assertEquals(118404L, r.durationMs)
        assertEquals(48, r.pass); assertEquals(21, r.warn); assertEquals(8, r.fail)
        assertEquals("FAIL", r.verdict)
        val c = r.phases.first { it.id == "path_checks" }.checks.first()
        assertEquals("OUT→1 Maddy :465 (SMTPS)", c.name)
        assertEquals("pass", c.status)
        assertEquals(listOf("name" to "OUT→1 Maddy :465 (SMTPS)", "passed" to "true", "details" to "tcp 10.0.0.3:465 OPEN", "duration_ms" to "129", "error" to "null", "severity" to "Info"), c.fields)
        // the phase counts add up to the engine's own summary
        assertEquals(r.pass, r.phases.sumOf { it.pass })
        assertTrue(r.extra.any { it.first == "timers_ms" && "TOTAL 118404" in it.second })
    }

    @Test fun `a failed critical check is fail, a failed warning is warn, with the evidence kept`() {
        val bad = mailParsed().phases.flatMap { it.checks }.first { it.status == "fail" }
        assertTrue(bad.fields.any { it.first == "severity" && it.second == "Critical" })
        assertTrue(bad.fields.any { it.first == "passed" && it.second == "false" })
    }

    @Test fun `the run trigger is allow-listed`() {
        assertEquals("{\"report\":\"mail\",\"runner\":\"gha\"}", EnginesModel.runBody("mail"))
        assertNull(EnginesModel.runBody("xray-vm"))
        assertNull(EnginesModel.runBody("mail; rm -rf /"))
        assertNull(EnginesModel.runBody(null))
        EnginesModel.ENGINES.forEach { assertTrue(it.id, it.kind == null || it.kind in EnginesModel.RUN_KINDS) }
        val analytics = EnginesModel.ENGINES.first { it.id == "analytics" }
        assertTrue(EnginesModel.runLabel(analytics).contains("no trigger"))
        val api = File(dir.parentFile.parentFile, "cloudy-u-containers/infra-api_cloudy-c3-api/src/code/api/routes/reports.ts")
        if (api.isFile) {
            val enum = Regex("REPORT_KINDS = \\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(api.readText())!!.groupValues[1]
            assertEquals(Regex("\"([a-z-]+)\"").findAll(enum).map { it.groupValues[1] }.toList(), EnginesModel.RUN_KINDS)
        }
    }

    @Test fun `a missing report says why and carries no number`() {
        val gap = EnginesModel.classify(mail, FleetLive.Result.Failed("NOT_FOUND: HTTP 404"))
        assertEquals(EnginesModel.Body.Why(EnginesModel.BODY_GAP), gap)
        assertTrue(EnginesModel.verdictText(gap).startsWith("no verdict: report body not served"))
        assertTrue(EnginesModel.verdictText(EnginesModel.classify(mail, FleetLive.Result.Body("<html>"))).startsWith("no verdict"))
        assertEquals(EnginesModel.STATUS_GAP, EnginesModel.freshText(null))
        assertEquals("duration n/a", EnginesModel.durationText(null))
    }

    @Test fun `freshness and history parse what the served routes answer`() {
        val f = EnginesModel.parseStatus("""{"families":[{"family":"cloud_mail_full","ok":true,"status":200,"lastModified":"2026-11-26T17:23:27Z","ageHours":7,"stale":false},{"family":"cloud_sec_data","ok":false,"status":404}]}""")!!
        assertEquals(7, f.getValue("cloud_mail_full").ageHours)
        assertEquals("HTTP 404", f.getValue("cloud_sec_data").error)
        assertTrue(EnginesModel.freshText(f.getValue("cloud_mail_full")).contains("7 h ago"))
        val r = EnginesModel.parseRuns("""{"runs":[{"runner":"gha","id":46066063430,"status":"completed","conclusion":"failure","createdAt":"2026-11-27T10:00:00Z","updatedAt":"2026-11-27T10:02:30Z","event":"schedule"}]}""")!!
        assertEquals(150L, r.single().seconds)
        assertNotNull(r.single().conclusion)
    }

    @Test fun `the other engines parse their own summaries`() {
        val sn = EnginesModel.parse(EnginesModel.ENGINES.first { it.id == "sec-network" }, """{"duration_ms":5,"generated":"g","port_scan":[{"vm_alias":"x","open_ports":[]}],"summary":{"critical":2,"failed":3,"passed":10,"total_checks":13,"warnings":1}}""")!!
        assertEquals(Triple(10, 1, 2), Triple(sn.pass, sn.warn, sn.fail))
        val url = EnginesModel.parse(EnginesModel.ENGINES.first { it.id == "url" }, """{"summary":{"public_total":4,"public_ok":3,"private_total":2,"private_ok":2,"total_ms":900},"public":[{"url":"https://a/","ok":true,"status":200}]}""")!!
        assertEquals(Triple(5, 0, 1), Triple(url.pass, url.warn, url.fail)); assertEquals(900L, url.durationMs)
    }
}

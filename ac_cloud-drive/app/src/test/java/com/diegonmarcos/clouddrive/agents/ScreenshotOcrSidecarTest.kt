package com.diegonmarcos.clouddrive.agents

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screenshot OCR (#1507): the declaration, the record -> libs:ocr layout, the battery rules in the schedule. */
class ScreenshotOcrSidecarTest {
    private fun line(t: String, l: Int, top: Int, r: Int) = ScreenshotRecord.Line(t, listOf(l, top, r, top + 30), 0.9)

    private fun record(lines: List<ScreenshotRecord.Line>) = ScreenshotRecord(
        "id", "/s/Screenshots/a.png", 1, 1, 1, 1, 1080, 2400, "", "und-Latn", lines.map { ScreenshotRecord.Block(it.text, it.bbox, listOf(it)) },
        emptyList(), emptyList(), emptyList(), emptyMap(), null,
    )

    @Test fun agentIsDeclaredAsScreenshotOcr() {
        val a = AgentsDecl.parse(File("../../data/fleet/app/com.diegonmarcos.clouddrive/agents.json").readText()).single()
        assertEquals("screenshot-ocr", a.id); assertEquals("Screenshot OCR", a.name)
        assertEquals(listOf("process_all", "retry_failed", "toggle"), a.actions.map { it.id })
        assertEquals(listOf(".md", ".json"), a.outputs)
    }

    @Test fun recordLinesBecomeATableAndMarkdown() {
        val r = record(listOf(
            line("App", 40, 260, 200), line("Usage", 800, 260, 960),
            line("Chrome", 40, 310, 220), line("12.5%", 820, 310, 960),
        ))
        val ocr = SidecarWriter.layout(r)
        assertEquals(1, ocr.tables.size)
        assertEquals("12.5%", ocr.tables[0].rows[1][1].text)
        assertEquals(SidecarWriter.ENGINE, ocr.engine)
    }

    @Test fun scheduleHasNoPollingAndFloorsRetries() {
        val src = File("src/main/java/com/diegonmarcos/clouddrive/agents/ScreenshotAgentWorker.kt").readText()
        assertTrue(src.contains("setRequiresCharging(true)"))
        assertTrue(!src.contains("PeriodicWorkRequestBuilder"))
        assertTrue(ScreenshotAgentWorker.BACKOFF_SECONDS >= 30)
    }

    @Test fun agentUiUsesTheKitRampNotSpLiterals() {
        val dir = File("src/main/java/com/diegonmarcos/clouddrive/agents")
        dir.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { f ->
            assertTrue("${f.name} has an sp literal", !Regex("\\d\\.?\\d*\\.sp\\b").containsMatchIn(f.readText()))
        }
    }
}

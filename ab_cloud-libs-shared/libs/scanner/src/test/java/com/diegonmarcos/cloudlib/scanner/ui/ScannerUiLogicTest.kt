package com.diegonmarcos.cloudlib.scanner.ui

import com.diegonmarcos.cloudlib.scanner.P
import com.diegonmarcos.cloudlib.scanner.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1908 the logic the screens stand on that needs no device: the Tools hub's table and the live-capture steadiness measure. */
class ScannerUiLogicTest {
    @Test fun theToolsHubListsEveryToolOnce() {
        val listed = toolGroups.flatMap { it.second }.map { it.id }
        assertEquals("every ToolId is in the hub exactly once", ToolId.values().toList().sortedBy { it.name }, listed.sortedBy { it.name })
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(17, listed.size)
    }

    @Test fun toolIdsAreUniqueAndThePageToolsAreTheScanOnes() {
        val ids = ToolId.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf(ToolId.CROP, ToolId.PERSPECTIVE, ToolId.FILTERS, ToolId.PAGE_OPS, ToolId.SCAN_PDF, ToolId.OCR, ToolId.EXPORT),
            ToolId.values().filter { it.onPages }.toSet(),
        )
    }

    @Test fun theHubGroupsAreScanPdfAndImages() {
        assertEquals(3, toolGroups.size)
        assertEquals(listOf(7, 8, 2), toolGroups.map { it.second.size })
    }

    private fun quad(dx: Double) = Quad(P(0.1 + dx, 0.1), P(0.9 + dx, 0.1), P(0.9 + dx, 0.9), P(0.1 + dx, 0.9))

    @Test fun steadinessIsTheLargestCornerMove() {
        assertEquals(0.0, quadShift(quad(0.0), quad(0.0)), 1e-12)
        assertEquals(0.02, quadShift(quad(0.0), quad(0.02)), 1e-9)
        assertTrue("a shaky frame is not steady", quadShift(quad(0.0), quad(0.1)) >= 0.025)
        assertTrue("a still frame is", quadShift(quad(0.0), quad(0.01)) < 0.025)
    }
}

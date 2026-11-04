package com.diegonmarcos.cloudagenda

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1461 the fleet's dense type ramp. Every size in the app's own UI comes from KitStoreRow / KitDensity (the Cloud Store's ramp,
 * TEXT_SCALE 0.78) or from MaterialTheme, which TasksTheme scales by the same factor (Typography.dense). A literal `13.sp`
 * or `fontSize = 14f` in ui/ would put one page back on its own, bigger scale.
 */
class DensityGuardTest {
    private val ui = File("src/main/java/com/diegonmarcos/cloudagenda/ui")
    private val literal = Regex("""\b\d+(\.\d+)?f?\s*\.sp\b|\bfontSize\s*=\s*\d""")

    @Test fun `no screen sets a text size of its own`() {
        val files = ui.listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        org.junit.Assert.assertTrue("ui/ not found from ${File(".").absolutePath}", files.isNotEmpty())
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (literal.containsMatchIn(l.substringBefore("//"))) "${f.name}:${i + 1}: ${l.trim()}" else null } }
        assertEquals("sp literals in ui/ (use KitStoreRow / KitDensity):\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }
}

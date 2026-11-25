package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1562 The cold-start seed (pages, mcp, plugins) is a byte copy of the served dataset, so a first run shows the same pages as a synced one. */
class ColdStartSeedTest {
    private fun seed(n: String) = File("src/main/assets/${AiData.SEED_DIR}$n.json").readText()

    @Test fun seedIsByteIdenticalToTheServedDataset() {
        for (n in listOf("pages", "mcp", "plugins")) assertEquals(n, Data.asset("$n.json"), seed(n))
    }

    @Test fun seedPagesParseAndEveryRowsPageHasGroups() {
        val pages = AiData.pages(seed("pages"))
        assertTrue(pages.isNotEmpty())
        for ((_, p) in pages.filter { it.value.kind == AiData.KIND_ROWS }) for (s in p.sources) {
            val ds = s.asset.removePrefix("ai/").removeSuffix(".json")
            if (ds in setOf("mcp", "plugins")) assertTrue(s.asset, AiData.groups(seed(ds), s.groups).isNotEmpty())
        }
    }
}

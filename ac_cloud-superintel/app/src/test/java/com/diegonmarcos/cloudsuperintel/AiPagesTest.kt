package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1499 The bottom nav, the AIs and Cost sub-pages, and the data behind them, against the real declarations. */
class AiPagesTest {
    private val pages = AiData.pages(Data.ai("pages.json"))

    @Test fun bottomNavOrderIsNotebookCgcsSiToolsAisCost() {
        assertEquals(listOf("notebook", "cgcs", "si", "tools", "ais", "cost"), Data.bottomNav())
    }

    /** What #1563 dropped and the owner restored: the AI Tools section (model catalogue, pickers, tasks, MCP) is on the bottom nav. */
    @Test fun aiToolsAreOnTheBottomNavWithTheirPages() {
        assertTrue("tools" in Data.bottomNav())
        assertEquals(listOf("tools_models", "tools_pickers", "tools_tasks", "tools_mcp"), Data.pages("tools"))
    }

    @Test fun aisSubPagesAreApisMcpDotfilesPlugins() {
        assertEquals(listOf("apis", "mcp", "dotfiles", "plugins"), Data.pages("ais"))
        assertEquals(listOf("cost"), Data.pages("cost"))
    }

    @Test fun everyDeclaredPageHasDataAndEveryDataPageIsDeclared() {
        assertEquals((Data.pages("ais") + Data.pages("cost")).toSet(), pages.keys)
        pages.values.forEach { assertTrue(it.kind in setOf(AiData.KIND_ROWS, AiData.KIND_APIS, AiData.KIND_COST)) }
        pages.values.forEach { assertTrue(Data.ai(it.asset.removePrefix("ai/")).isNotBlank()) }
    }

    @Test fun mcpPageNamesTheRebrandAndTheC3Merge() {
        val g = AiData.groups(Data.ai("mcp.json"))
        val titles = g.map { it.title }
        assertTrue(titles.any { it.contains("#1450") })
        assertTrue(titles.any { it.contains("#1495") })
        val names = g.flatMap { it.rows }.map { it.title }
        listOf("cloudy-c3-mcp", "cloudy-all-mcp", "cloud-infra-mcp").forEach { assertTrue(it, it in names) }
    }

    @Test fun dotfilesHaveTheFourSectionsForBothSettingsFilesAndNoValues() {
        val g = AiData.groups(Data.ai("dotfiles.json"))
        for (file in listOf("settings.project.json", "settings.base.json"))
            for (part in listOf("Permissions", "Env names", "Hooks", "Models")) assertTrue("$file $part", g.any { it.title == "$file · $part" })
        // Env rows carry a name and the words "name only", never a value.
        g.filter { it.title.endsWith("Env names") }.flatMap { it.rows }.forEach { assertEquals("name only, value never shown", it.caption); assertEquals("", it.end) }
    }

    @Test fun pluginsPageHasTheC3InfraSkillRulesAndAgents() {
        val g = AiData.groups(Data.ai("plugins.json")).associate { it.title to it.rows.map { r -> r.title } }
        assertTrue("c3-infra" in g.getValue("Skills"))
        assertTrue("backlog.md" in g.getValue("Rules"))
        assertTrue("ops" in g.getValue("Agents"))
        assertTrue("c3-infra" in g.getValue("Enabled plugins (SOT)"))
    }
}

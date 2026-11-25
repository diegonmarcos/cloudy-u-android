package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1499 / #1854 The bottom nav, the Models and Context sub-pages, and the data behind them, against the real declarations. */
class AiPagesTest {
    private val pages = AiData.pages(Data.ai("pages.json"))

    @Test fun bottomNavOrderIsNotebookCgcsSiModelsContext() {
        assertEquals(listOf("notebook", "cgcs", "si", "models", "context"), Data.bottomNav())
    }

    /** #1854 Tools became Models: Agents (the Agent and Model pickers), Models, Tasks | API (moved from AIs); the MCP page left for Context. */
    @Test fun modelsSectionHasAgentsModelsTasksApi() {
        assertEquals(listOf("tools_pickers", "tools_models", "tools_tasks", "apis"), Data.pages("models"))
        assertEquals(listOf("Agents", "Models", "Tasks", "API"), Data.labels("models"))
        assertEquals("Models", Data.sectionLabel("models"))
    }

    /** #1854 AIs became Context: Plugins | MCP-KG, MCP-Tools, Hooks, Skills; Cost left the bar for the top-right window. */
    @Test fun contextSubPagesArePluginsMcpKgMcpToolsHooksSkills() {
        assertEquals(listOf("plugins", "mcp_kg", "mcp", "hooks", "skills"), Data.pages("context"))
        assertEquals(listOf("Plugins", "MCP-KG", "MCP-Tools", "Hooks", "Skills"), Data.labels("context"))
        assertEquals("Context", Data.sectionLabel("context"))
        assertTrue("cost is not a bottom nav section", "cost" !in Data.bottomNav())
    }

    @Test fun everyDeclaredPageHasDataAndEveryDataPageIsDeclared() {
        // The data pages are the Context pages and the API page, plus the `cost` page the top-right Cost window draws.
        assertEquals((Data.pages("context") + "apis" + "cost").toSet(), pages.keys)
        pages.values.forEach { assertTrue(it.kind in setOf(AiData.KIND_ROWS, AiData.KIND_APIS, AiData.KIND_COST, AiData.KIND_KG)) }
        pages.values.filter { it.kind == AiData.KIND_APIS || it.kind == AiData.KIND_COST }.forEach { assertTrue(Data.ai(it.asset.removePrefix("ai/")).isNotBlank()) }
        pages.values.filter { it.kind == AiData.KIND_ROWS }.forEach { p ->
            assertTrue(p.sources.isNotEmpty())
            p.sources.forEach { assertTrue(Data.ai(it.asset.removePrefix("ai/")).isNotBlank()) }
        }
    }

    /** #1854 Rule 16: the former MCP, Dotfiles, Plugins pages split over Context; every group is on exactly one page, every row shown. */
    @Test fun everyGroupOfTheFormerPagesIsOnExactlyOneContextPage() {
        for (asset in listOf("mcp.json", "dotfiles.json", "plugins.json")) {
            val all = AiData.groups(Data.ai(asset))
            val shown = pages.filterKeys { it != "cost" && it != "apis" }.values.flatMap { p ->
                p.sources.filter { it.asset == "ai/$asset" }.flatMap { AiData.groups(Data.ai(asset), it.groups) }
            }
            assertEquals(asset, all.map { it.title }.sorted(), shown.map { it.title }.sorted())
            assertEquals(asset, all.sumOf { it.rows.size }, shown.sumOf { it.rows.size })
        }
    }

    @Test fun theMcpToolsPageIsTheWholeFormerMcpList() {
        val spec = pages.getValue("mcp")
        val shown = spec.sources.flatMap { AiData.groups(Data.ai(it.asset.removePrefix("ai/")), it.groups) }
        assertEquals(AiData.groups(Data.ai("mcp.json")), shown)
    }

    /** #1854 MCP-KG is the composer's KG homes: every known server of every bucket and every non-server KG-Store source, read from the shared design. */
    @Test fun mcpKgPageIsTheComposersKgHomes() {
        assertEquals(AiData.KIND_KG, pages.getValue("mcp_kg").kind)
        val design = com.diegonmarcos.superapp.ai.AiDesign.parse(LibAi.asset("ai-design.json"))
        val g = AiData.kgGroups(design.mcpBuckets, design.kgStoreTabs)
        assertEquals(design.mcpBuckets.map { it.label } + "KG-Store sources (not servers)", g.map { it.title })
        for (b in design.mcpBuckets) assertEquals(b.known, g.first { it.title == b.label }.rows.map { it.title })
        assertTrue("cloudy-c3-mcp" in g.flatMap { it.rows }.map { it.title })
        val sources = design.kgStoreTabs.flatMap { it.items }.filter { it.kind != "mcp" }.map { it.label }
        assertEquals(sources, g.last().rows.map { it.title })
        assertTrue(sources.isNotEmpty())
    }

    @Test fun aRowsPageFiltersGroupsByFullMatch() {
        val g = AiData.groups(Data.ai("dotfiles.json"), listOf(".* · Hooks"))
        assertEquals(listOf("settings.project.json · Hooks", "settings.base.json · Hooks"), g.map { it.title })
        assertEquals(AiData.groups(Data.ai("dotfiles.json")), AiData.groups(Data.ai("dotfiles.json"), emptyList()))
        assertTrue(AiData.groups(Data.ai("dotfiles.json"), listOf("Hooks")).isEmpty())
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

/** #1562 pages, mcp and plugins are served datasets of Cloud Store: no APK asset, read through FleetData, listed in the manifest, schemaVersion set. */
class AiServedDatasetsTest {
    private val names = listOf("pages", "mcp", "plugins")
    private fun read(p: String) = java.io.File(p).readText()

    @Test fun noAssetIsLeftInTheApk() = names.forEach {
        assertTrue("$it.json is still an APK asset", !java.io.File("src/main/assets/ai/$it.json").exists())
    }

    @Test fun theServedFilesParseAndCarryASchemaVersion() = names.forEach {
        assertEquals(1, org.json.JSONObject(Data.ai("$it.json")).getInt("schemaVersion"))
    }

    @Test fun theManifestListsThem() {
        val listed = org.json.JSONObject(read("../../data/fleet/manifest.json")).getJSONObject("apps").getJSONArray("com.diegonmarcos.cloudsuperintel")
        val ids = (0 until listed.length()).map { listed.getString(it) }
        names.forEach { assertTrue("$it is not in the manifest", it in ids) }
    }

    @Test fun theReaderIsTheServedOneAndNotServedIsEmpty() {
        val src = read("src/main/java/com/diegonmarcos/cloudsuperintel/AiData.kt")
        assertTrue(src.contains("FleetData.get(ctx, ds, own = true)") && src.contains("FleetData.get(ctx, PAGES, own = true)"))
        assertTrue(!src.contains("ctx.assets.open(\"ai/pages.json\")"))
        assertTrue("not served falls back to the cold-start seed, never an APK page asset", src.contains("seed(ctx, ds)") && src.contains("pages(seed(ctx, PAGES))"))   // #1562
        assertTrue(AiData.pages("").isEmpty())
        assertTrue(AiData.groups("").isEmpty())
    }
}

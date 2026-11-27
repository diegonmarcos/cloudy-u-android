package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.GitSheet
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1934 RULE 16 FOR THE TOP NAV: the old row (Refresh | Sync | Clone | Logs | More, the cloud button, the gear) is replaced by the scope chips and the group tabs.
 * Every action of that row is enumerated from the declaration and must be a control of the nav (through its `legacy` id), the five clone modes included; the cloud
 * button and the gear are drawn in the nav's scope row. The mutation tests prove the check goes red when one action is dropped.
 */
class TopNavParity1934Test {
    private val root = File("..").let { if (File(it, "build.json").isFile) it else File(".") }
    private val build: JsonObject = Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject
    private val decl = build["ui"]!!.jsonObject["git_control_sheet"]!!.jsonObject
    private val sheet = GitSheet.parse(decl, "en")
    private val oldGroups = build["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]!!.jsonObject["top_bar"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
    private val main = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync")
    private val screen = File(main, "GitReposScreen.kt").readText()

    /** every action of the old row as "group.item" */
    private fun oldActions(): List<String> = oldGroups.flatMap { g -> g["items"]!!.jsonArray.map { g["id"]!!.jsonPrimitive.content + "." + it.jsonObject["id"]!!.jsonPrimitive.content } }
    /** the old actions the bulk controls stand for */
    private fun covered(s: GitSheet): Set<String> = s.controls(true).flatMap { it.legacy }.filter { it.startsWith("bulk_top:") }.map { it.removePrefix("bulk_top:") }.toSet()
    /** the old actions with no control: only the old row's own "open the bulk sheet" entry, which IS the nav now */
    private fun missing(s: GitSheet) = oldActions().filter { it !in covered(s) && it != "more.control_sheet_all" }

    @Test fun theOldRowHasTheActionsTheTestExpects() {
        val a = oldActions()
        assertTrue("old row enumerated (${a.size})", a.size >= 36)
        for (id in listOf("check.refresh_list", "check.fetch_status", "check.fetch_now", "check.check_lite", "check.check_full", "sync.sync_server_wins", "sync.sync_local_wins", "sync.sync_all",
            "clone.clone_all_full", "clone.clone_all_depth3", "clone.clone_all_bare_main", "clone.clone_all_bare_blobless", "clone.clone_all_bare_treeless", "logs.logs", "more.legend", "more.last_report", "more.advanced")) assertTrue(id, id in a)
    }

    @Test fun everyActionOfTheOldRowIsAControlOfTheNav() = assertEquals(emptyList<String>(), missing(sheet))

    @Test fun allFiveCloneModesAreControlsOfTheCloneTab() {
        val clone = sheet.bulkTabs.first { it.id == "clone" }.groups.flatMap { it.controls }.flatMap { it.legacy }
        for (m in listOf("full", "depth3", "bare_main", "bare_blobless", "bare_treeless")) assertTrue(m, "bulk_top:clone.clone_all_$m" in clone)
    }

    @Test fun theNavHasTheScopeChipsAndTheGroupTabs() {
        assertEquals(listOf("All Repos", "Selected Ones", "Cloned Ones"), sheet.scopes.map { it.label })
        assertEquals(listOf("Overview", "Sync", "Clone", "Workflow", "Manage"), sheet.bulkTabs.map { it.label })
    }

    @Test fun theNavIsDrawnInThePageNotInASheetAndKeepsTheCloudButtonAndTheGear() {
        val block = screen.substring(screen.indexOf("stickyHeader(key = \"table-top\")"))
        assertTrue(block.indexOf("GitTopNav(") in 0 until block.indexOf("items(sortedEntries"))
        assertFalse("the old row is not drawn", screen.contains("GitTopBar(page.topBar"))
        assertFalse("no bottom sheet holds the bulk controls", screen.contains("git_bulk_sheet") || screen.contains("showBulkSheet"))
        assertTrue("the nav draws the pinned part then the open tab's controls", File(main, "GitTopBar.kt").readText().let { it.contains("bulk(GitSheetPart.TOP)") && it.contains("bulk(GitSheetPart.BODY)") })
        assertTrue("cloud button and gear ride the scope row", screen.contains("scopeTrailing = { GitNavUtility(page.topBar, prefs, storageMenu) }"))
        assertTrue(File(main, "GitTopBar.kt").readText().contains("IslandAction(Icons.Filled.Settings"))
        assertTrue("the old More > All repos sheet entry opens the nav", screen.contains("\"open_control_sheet\" -> { navOpen = true"))
    }

    @Test fun everyDeclaredTopKindStillHasABranchInThePage() {
        for (k in com.diegonmarcos.clouddrive.Declarations.TOP_KINDS) assertTrue(k, screen.contains("\"$k\" ->"))
    }

    // ── mutations ──
    @Test fun mutationDroppingTheCloneModeControlIsReported() {
        val cut = sheet.bulkTabs.map { t -> t.copy(groups = t.groups.map { g -> g.copy(controls = g.controls.filter { it.id != "bo.clone.bare_treeless" }) }) }
        assertEquals(listOf("clone.clone_all_bare_treeless"), missing(sheet.copy(bulkTabs = cut)))
    }
    @Test fun mutationDroppingTheLogsControlIsReported() {
        val cut = sheet.bulkTabs.map { t -> t.copy(groups = t.groups.map { g -> g.copy(controls = g.controls.filter { it.id != "bo.logs" }) }) }
        assertTrue("logs.logs" in missing(sheet.copy(bulkTabs = cut)))
    }
    @Test fun mutationDroppingTheRefreshControlIsReported() {
        val cut = sheet.bulkTabs.map { t -> t.copy(groups = t.groups.map { g -> g.copy(controls = g.controls.filter { it.id != "bo.refresh_list" }) }) }
        assertTrue("check.refresh_list" in missing(sheet.copy(bulkTabs = cut)))
    }
}

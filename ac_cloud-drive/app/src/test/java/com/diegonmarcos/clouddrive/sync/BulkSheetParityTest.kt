package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.BulkDerive
import com.diegonmarcos.cloudlib.gitui.BulkProgress
import com.diegonmarcos.cloudlib.gitui.BulkScopes
import com.diegonmarcos.cloudlib.gitui.GitSheet
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1934 THE TOP-NAV BULK MENU IS THE PER-REPO SHEET'S DECLARATION: parity (every per-repo control exists in the bulk sheet, is a declared twin of one, or is
 * declared per-repo-only with a reason), nothing of the earlier bulk sheet lost (rule 16), scopes, concurrency and the progress / summary model. Each parity
 * property is also proven RED on a mutated sheet (the `mutation` tests); drive-bulk-sheet-tests.yml plants source mutants on top.
 */
class BulkSheetParityTest {
    private val decl: JsonObject = Json.parseToJsonElement(File("../build.json").readText()).jsonObject["ui"]!!.jsonObject["git_control_sheet"]!!.jsonObject
    private val sheet = GitSheet.parse(decl, "en")
    private val es = GitSheet.parse(decl, "es")

    @Test fun everyPerRepoControlIsInTheBulkSheetATwinOrDeclaredPerRepoOnly() {
        assertEquals(emptyList<String>(), BulkDerive.missing(sheet))
        assertEquals(emptyList<String>(), BulkDerive.badPerRepoOnly(sheet))
    }

    @Test fun theBulkSheetHasTheFourTabsWithThePerRepoGroups() {
        assertEquals(listOf("overview", "sync", "clone", "workflow", "manage"), sheet.bulkTabs.map { it.id })
        val bulk = sheet.controls(true).map { it.id }.toSet()
        // the same groups and controls as the per-repo sheet, in the bulk sheet
        for (id in listOf("sy.rec.stash", "sy.rec.auto_commit", "sy.auto.toggle", "cl.preset.depth3", "wf.commit", "wf.list_dirty", "mg.auth.test", "mg.auth.https", "mg.clean.5", "mg.life.archive_remote", "mg.life.delete_remote", "sy.direction.pull", "sy.flag.prune", "cl.dest.workspace", "cl.data.blobs", "sy.preset.safe"))
            assertTrue("$id missing from the bulk sheet", id in bulk)
        // per-repo-only ones are NOT drawn in bulk, each with a declared reason in both languages
        for ((id, why) in sheet.perRepoOnly) { assertFalse("$id is declared per-repo-only but drawn in bulk", id in bulk && id !in sheet.twins); assertTrue("$id has no reason", sheet.reasons[why].orEmpty().isNotBlank()); assertTrue("$id has no es reason", es.reasons[why].orEmpty().isNotBlank()) }
        assertTrue(sheet.perRepoOnly.keys.containsAll(listOf("mg.life.rename", "mg.life.rename_remote", "wf.prs")))
    }

    @Test fun nothingOfTheEarlierBulkSheetIsLost() {
        val before = decl["bulk"]!!.jsonObject["tabs"].toString()
        val ids = Regex("\"id\":\"([^\"]+)\"").findAll(before).map { it.groupValues[1] }.filter { it.startsWith("bo.") || it.startsWith("sy.preset") }.toSet()
        val now = sheet.controls(true).map { it.id }.toSet()
        assertTrue(ids.isNotEmpty() && now.containsAll(ids))
        assertTrue(sheet.legacyIds().size >= 160)
    }

    @Test fun everyDerivedActionHasARunnerAndDestructiveOnesConfirm() {
        val declared = sheet.bulkDeclared.flatMap { t -> t.groups.flatMap { it.controls } }.map { it.id }.toSet()
        val perRepo = sheet.controls(false).associateBy { it.id }
        sheet.controls(true).filter { it.id !in declared && it.kind == "action" }.forEach { c ->
            assertTrue("${c.id} (${c.action}) has no bulk runner", sheet.bulkActions.containsKey(c.action))
            if (c.danger || c.id.endsWith("delete_remote") || c.id.endsWith("archive_remote")) assertTrue("${c.id} must confirm in bulk", c.confirm || c.danger || perRepo[c.id]?.confirm == true)
        }
        assertTrue(sheet.controls(true).first { it.id == "mg.life.delete_remote" }.let { it.confirm || it.danger })
        // a derived control that writes over the whole scope confirms first (naming scope and count)
        for (id in listOf("sy.rec.stash", "sy.rec.pull_rebase", "sy.rec.auto_commit", "mg.auth.https", "mg.life.archive_remote", "wf.commit")) assertTrue("$id must confirm in bulk", sheet.controls(true).first { it.id == id }.confirm)
        assertFalse("Test Auth only reads", sheet.controls(true).first { it.id == "mg.auth.test" }.confirm)
    }

    @Test fun threeScopesAndTheDeclaredConcurrency() {
        assertEquals(listOf("all", "selected", "cloned"), sheet.scopes.map { it.id })
        assertEquals(listOf("All Repos", "Selected Ones", "Cloned Ones"), sheet.scopes.map { it.label })
        assertEquals(listOf("Todos los repos", "Los seleccionados", "Los clonados"), es.scopes.map { it.label })
        assertEquals(3, sheet.concurrency)
    }

    // ── mutations: each parity property must go RED on a damaged sheet ──
    @Test fun mutationDroppingABulkControlIsReportedMissing() {
        val cut = sheet.bulkTabs.map { t -> t.copy(groups = t.groups.map { g -> g.copy(controls = g.controls.filter { it.id != "mg.auth.test" }) }) }
        assertTrue("mg.auth.test" in BulkDerive.missing(sheet.copy(bulkTabs = cut)))
    }
    @Test fun mutationUndeclaringAPerRepoOnlyControlIsReportedMissing() {
        assertTrue("wf.prs" in BulkDerive.missing(sheet.copy(perRepoOnly = sheet.perRepoOnly - "wf.prs")))
    }
    @Test fun mutationDroppingATwinIsReportedMissing() {
        val m = BulkDerive.missing(sheet.copy(twins = sheet.twins - "wf.status", bulkTabs = sheet.bulkTabs.map { t -> t.copy(groups = t.groups.map { g -> g.copy(controls = g.controls.filter { it.id != "wf.status" }) }) }))
        assertTrue("wf.status" in m || BulkDerive.missing(sheet.copy(twins = emptyMap())).isNotEmpty())
        assertTrue(BulkDerive.missing(sheet.copy(twins = emptyMap(), bulkTabs = sheet.bulkDeclared)).containsAll(listOf("sy.run", "cl.run")))
    }
    @Test fun mutationAPerRepoOnlyWithoutAReasonIsReported() {
        assertEquals(listOf("wf.prs"), BulkDerive.badPerRepoOnly(sheet.copy(perRepoOnly = sheet.perRepoOnly + ("wf.prs" to "no_such_reason"))))
        assertEquals(listOf("ghost.control"), BulkDerive.badPerRepoOnly(sheet.copy(perRepoOnly = sheet.perRepoOnly + ("ghost.control" to "single_view"))))
    }

    // ── scopes: which repositories, which controls cannot run, and why ──
    private val repos = listOf(BulkScopes.Entry("a", true), BulkScopes.Entry("b", true), BulkScopes.Entry("c", false))

    @Test fun scopesSelectTheirRepositories() {
        assertEquals(listOf("a", "b", "c"), BulkScopes.targets("all", repos, setOf("b")).map { it.name })
        assertEquals(listOf("b"), BulkScopes.targets("selected", repos, setOf("b")).map { it.name })
        assertEquals(emptyList<String>(), BulkScopes.targets("selected", repos, emptySet()).map { it.name })
        assertEquals(listOf("a", "b"), BulkScopes.targets("cloned", repos, setOf("c")).map { it.name })
    }

    @Test fun controlsThatCannotRunStayGreyedWithTheirOwnReason() {
        val none = BulkScopes.unavailableText(sheet, "selected", emptyList(), false)
        assertTrue(none.getValue("bo.sync.stash_all").startsWith("No repository is selected"))
        assertTrue(none.getValue("bo.sync.run").startsWith("No repository is selected"))
        assertTrue("global controls stay available", "mg.auth.oauth" !in none)
        val cloned = BulkScopes.unavailableText(sheet, "cloned", repos.filter { it.cloned }, false)
        assertTrue(cloned.getValue("bo.clone.run").startsWith("Every repository in this scope is already cloned"))
        assertTrue(cloned.getValue("bo.clone.full").startsWith("Every repository"))
        assertTrue("Stash still runs on cloned ones", "bo.sync.stash_all" !in cloned)
        val all = BulkScopes.unavailableText(sheet, "all", repos, false)
        assertFalse("Clone runs over the not-cloned one", "bo.clone.run" in all)
        assertTrue("Stash runs over the cloned ones", "bo.sync.stash_all" !in all)
        val onlyNot = BulkScopes.unavailableText(sheet, "selected", listOf(BulkScopes.Entry("c", false)), false)
        assertTrue(onlyNot.getValue("bo.sync.stash_all").startsWith("No repository in this scope is cloned"))
        assertFalse("clone is the way out", "bo.clone.run" in onlyNot)
        assertTrue(BulkScopes.unavailableText(sheet, "all", repos, true).getValue("bo.sync.run").startsWith("A bulk run is in progress"))
    }

    // ── progress per repository and the summary ──
    @Test fun rowsAreQueuedRunningOkOrFailedWithTheError() {
        val rows = BulkProgress.rows(listOf("a", "b", "c", "d", "e"), running = setOf("b"), ok = mapOf("a" to "pulled"), failed = mapOf("c" to "could not resolve host"), skipped = emptyMap(), notes = mapOf("e" to "needs a local clone"))
        assertEquals(listOf("ok: pulled", "running", "failed: could not resolve host", "queued", "failed: needs a local clone"), rows.map { it.line() })
        val s = BulkProgress.summary(rows)
        assertEquals(BulkProgress.Summary(5, 1, 2, 0, 1, 1), s)
        assertFalse(s.done)
        assertEquals("5 repos: 1 ok, 2 failed, 0 skipped", BulkProgress.summaryLine(s))
    }
    @Test fun cancelledRunsSkipWhatNeverStarted() {
        val rows = BulkProgress.rows(listOf("a", "b"), emptySet(), mapOf("a" to "ok"), emptyMap(), emptyMap(), ended = true, cancelled = true)
        assertEquals(listOf("ok: ok", "skipped: cancelled"), rows.map { it.line() })
        assertTrue(BulkProgress.summary(rows).done)
    }
}

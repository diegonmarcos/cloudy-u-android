package com.diegonmarcos.cloudlib.gitui

import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.GitStep
import com.diegonmarcos.cloudlib.gitcore.SyncOptions
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1934 the control sheet model parsed from Cloud Drive's real declaration; presets applied to the one option model. */
class GitSheetModelTest {
    private val file = listOf("../../../ac_cloud-drive/build.json", "ac_cloud-drive/build.json").map { File(it) }.first { it.exists() }
    private val decl = Json.parseToJsonElement(file.readText()).jsonObject["ui"]!!.jsonObject["git_control_sheet"]!!.jsonObject
    private val sheet = GitSheet.parse(decl, "en")
    private val es = GitSheet.parse(decl, "es")

    @Test fun fiveTabsInOrderForBothSheets() {
        assertEquals(listOf("overview", "sync", "clone", "workflow", "manage"), sheet.tabs.map { it.id })
        assertEquals(sheet.tabs.map { it.id }, sheet.bulkTabs.map { it.id })
        assertEquals(listOf("Repo list", "Fetch status", "Check lite", "Check full"), sheet.header.actions.map { it.label })
    }

    @Test fun spanishLabelsComeFromTheDeclaration() {
        assertEquals(listOf("Resumen", "Sync", "Clonar", "Flujo", "Gestionar"), es.tabs.map { it.label })
        assertEquals("Lista de repos", es.header.actions.first().label)
        assertTrue(es.reasons.getValue("needs_clone").startsWith("Necesita"))
    }

    @Test fun everyLegacyIdIsPlacedOnce() {
        val ids = sheet.legacyIds()
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size >= 160)
    }

    @Test fun disabledControlsStateAReason() {
        val disabled = sheet.controls(false).filter { it.disabled }
        assertTrue(disabled.any { it.id == "sy.flag.submodules" })
        assertTrue(disabled.all { it.reason.isNotBlank() && it.reason != "-" })
        assertTrue(sheet.controls(false).first { it.id == "sy.flag.forceRaw" }.danger)
    }

    @Test fun syncPresetsApplyToTheOneOptionModel() {
        val p = sheet.syncPresets.associateBy { it.id }
        val safe = SheetPresets.sync(SyncOptions(), p.getValue("safe"))
        assertEquals("git pull --ff-only && git push", GitStep.preview(safe.steps()))
        assertTrue(SheetPresets.activeSync(safe, p.getValue("safe")))
        assertFalse(SheetPresets.activeSync(safe, p.getValue("fetch_only")))
        assertEquals("git pull --no-rebase", GitStep.preview(SheetPresets.sync(SyncOptions(), p.getValue("no_winner")).steps()))
        assertEquals("git pull --no-rebase -X theirs --autostash", GitStep.preview(SheetPresets.sync(SyncOptions(), p.getValue("remote_wins")).steps()))
        assertEquals("git add -A && git commit -m \"chore: auto-sync\" || true && git push --force-with-lease", GitStep.preview(SheetPresets.sync(SyncOptions(), p.getValue("local_wins")).steps()))
        assertEquals("git fetch && git reset --hard @{u}", GitStep.preview(SheetPresets.sync(SyncOptions(), p.getValue("mirror_remote")).steps()))
    }

    @Test fun clonePresetsReproduceTheEarlierModes() {
        val p = sheet.clonePresets.associateBy { it.id }
        fun cmd(id: String) = GitStep.preview(listOf(SheetPresets.clone(CloneOptions(), p.getValue(id)).step("U", "D")))
        assertEquals("git clone U D", cmd("full"))
        assertEquals("git clone --depth 3 --no-single-branch U D", cmd("depth3"))
        assertEquals("git clone --bare --single-branch --branch main U D", cmd("bare_main"))
        assertEquals("git clone --bare --filter=blob:none U D", cmd("bare_blobless"))
        assertEquals("git clone --bare --filter=tree:0 U D", cmd("bare_treeless"))
    }
}

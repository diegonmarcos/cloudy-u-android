package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.launcher.folders.AppFilter
import com.diegonmarcos.superapp.launcher.folders.FolderApp
import com.diegonmarcos.superapp.launcher.folders.FolderAssignment
import com.diegonmarcos.superapp.launcher.folders.FolderDef
import com.diegonmarcos.superapp.launcher.folders.DataMode
import com.diegonmarcos.superapp.launcher.folders.FolderEditModel
import com.diegonmarcos.superapp.launcher.folders.FolderScope
import com.diegonmarcos.superapp.launcher.folders.OTHERS_FOLDER_ID
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two-table Folders page drawn and driven: Table A edit/add/delete, Table B dropdown, filters, and the live preview mirror. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderTablesComposeTest : KitPageHarness() {

    private val T = FolderTablesTags
    private val folders = listOf(
        FolderDef("Work", "dev", "Dev", "ic_dev", 1), FolderDef("Work", "ops", "Ops", "ic_ops", 2),
        FolderDef("Life", "media", "Media", "ic_media", 1),
    )
    private val assign = listOf(FolderAssignment("x.ide", "dev", 1), FolderAssignment("x.term", "dev", 0), FolderAssignment("x.play", "media", 1))
    private val apps = listOf(FolderApp("x.ide", "IDE"), FolderApp("x.term", "Term"), FolderApp("x.play", "Player"), FolderApp("x.new", "Newbie"))

    private var copied: String? = null
    private var exported: List<Pair<String, String>>? = null

    private fun open(): FolderEditorState {
        val rt = FolderEditModel.build(folders, assign, apps)
        // Declarative = the SoT: the same tables but x.ide declared in "ops" (so the run-time view drifts from it).
        val decl = rt.setFolder("x.ide", "ops")
        val st = FolderEditorState(rt, decl, FolderScope.PHONE)
        showKit { FolderTablesScreen(st, onSave = {}, onReset = {}, modifier = Modifier.testTag("page"),
            onCopy = { copied = it }, onExport = { exported = it }) }
        return st
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("page").performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test fun `Table A cell edit updates the model and the preview tile`() {
        val st = open()
        scrollTo(T.aCell("label", "dev"))
        compose.onNodeWithTag(T.aCell("label", "dev")).performTextReplacement("Develop")
        compose.waitForIdle()
        assertEquals("Develop", st.model.def("dev")!!.label)
        assertTrue(st.dirty)
        scrollTo(T.tile("dev"))
        compose.onNodeWithTag(T.tile("dev")).assertTextContains("Develop", substring = true)
    }

    @Test fun `Table A add row appears, delete moves its apps to Others and warns`() {
        val st = open()
        scrollTo(T.A_ADD)
        compose.onNodeWithTag(T.A_ADD).performClick()
        compose.waitForIdle()
        assertTrue(st.model.def("new_folder_1") != null)
        scrollTo(T.aRow("new_folder_1"))
        compose.onNodeWithTag(T.aRow("new_folder_1")).assertExists()

        scrollTo(T.aCount("dev"))
        compose.onNodeWithTag(T.aCount("dev")).assertTextEquals("2")
        scrollTo(T.aDel("dev"))
        compose.onNodeWithTag(T.aDel("dev")).performClick()
        compose.waitForIdle()
        assertNull(st.model.def("dev"))
        assertTrue(st.model.apps.filter { it.pkg in setOf("x.ide", "x.term") }.all { it.folderId == OTHERS_FOLDER_ID })
        compose.onNodeWithTag(T.NOTICE).assertTextContains("2 app(s) moved to Others", substring = true)
        scrollTo(T.tile(OTHERS_FOLDER_ID))
        compose.onNodeWithTag(T.tile(OTHERS_FOLDER_ID)).assertTextContains("3", substring = true)
    }

    @Test fun `Table B dropdown reassigns an app, fills Parent, ticks the needs count and moves the preview tile`() {
        val st = open()
        scrollTo(T.bChip(AppFilter.NO_FOLDER))
        compose.onNodeWithTag(T.bChip(AppFilter.NO_FOLDER)).assertTextContains("No Folders (1)", substring = true)
        scrollTo(T.bFolder("x.new"))
        compose.onNodeWithTag(T.bFolder("x.new")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(T.bOpt("x.new", "media")).performClick()
        compose.waitForIdle()
        assertEquals("media", st.model.apps.first { it.pkg == "x.new" }.folderId)
        scrollTo(T.bSection("x.new"))
        compose.onNodeWithTag(T.bSection("x.new")).assertTextEquals("Life")
        scrollTo(T.bChip(AppFilter.NO_FOLDER))
        compose.onNodeWithTag(T.bChip(AppFilter.NO_FOLDER)).assertTextContains("No Folders (0)", substring = true)
        scrollTo(T.tile("media"))
        compose.onNodeWithTag(T.tile("media")).assertTextContains("2", substring = true)
    }

    @Test fun `No folder chip filters to the unassigned apps`() {
        val st = open()
        scrollTo(T.bChip(AppFilter.NO_FOLDER))
        compose.onNodeWithTag(T.bChip(AppFilter.NO_FOLDER)).assertTextContains("No Folders (1)", substring = true)
        compose.onNodeWithTag(T.bChip(AppFilter.NO_FOLDER)).performClick()
        compose.waitForIdle()
        assertEquals(AppFilter.NO_FOLDER, st.bFilter)
        scrollTo(T.bRow("x.new"))
        compose.onNodeWithTag(T.bRow("x.new")).assertExists()
        compose.onNodeWithTag(T.bRow("x.ide")).assertDoesNotExist()
    }

    @Test fun `emptying a folder in Table B removes its preview tile`() {
        val st = open()
        st.edit { it.setFolder("x.play", "dev") }
        compose.waitForIdle()
        assertNotNull(st.model.preview().flatMap { it.folders }.firstOrNull { it.def.id == "dev" })
        compose.onNodeWithTag(T.tile("media")).assertDoesNotExist()
    }

    @Test fun `exactly three filters All, No Folders, Others`() {
        open()
        scrollTo(T.bChip(AppFilter.ALL))
        for (f in AppFilter.values()) compose.onNodeWithTag(T.bChip(f)).assertExists()
        assertEquals(3, AppFilter.values().size)
    }

    @Test fun `the Declarative switch shows the SoT values, the drift, and blocks edits`() {
        val st = open()
        assertEquals(1, st.drift)
        scrollTo(T.mode(DataMode.DECLARATIVE))
        compose.onNodeWithTag(T.mode(DataMode.DECLARATIVE)).performClick()
        compose.waitForIdle()
        assertEquals(DataMode.DECLARATIVE, st.mode)
        assertEquals("ops", st.model.apps.first { it.pkg == "x.ide" }.folderId)
        st.edit { it.setFolder("x.ide", "media") }   // read-only SoT
        assertEquals("ops", st.model.apps.first { it.pkg == "x.ide" }.folderId)
        compose.onNodeWithTag(T.mode(DataMode.RUNTIME)).performClick()
        compose.waitForIdle()
        assertEquals("dev", st.model.apps.first { it.pkg == "x.ide" }.folderId)
        scrollTo(T.bDeclared("x.ide"))
        compose.onNodeWithTag(T.bDeclared("x.ide")).assertTextEquals("Ops")
    }

    @Test fun `Copy All and Export All carry the full tables of the selected scope and mode`() {
        val st = open()
        scrollTo(T.COPY_ALL)
        compose.onNodeWithTag(T.COPY_ALL).performClick()
        compose.waitForIdle()
        val c = org.json.JSONObject(copied!!)
        assertEquals("phone", c.getString("scope")); assertEquals("run-time", c.getString("mode"))
        assertEquals(3, c.getJSONObject("launcher-folders").getJSONArray("folders").length())
        assertEquals(3, c.getJSONObject("launcher-folder-assignments").getJSONArray("assignments").length())
        assertEquals("x.new", c.getJSONArray("unassigned").getString(0))
        compose.onNodeWithTag(T.mode(DataMode.DECLARATIVE)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(T.EXPORT_ALL).performClick()
        compose.waitForIdle()
        val docs = exported!!
        assertEquals(listOf("launcher-folders.phone.declarative.json", "launcher-folder-assignments.phone.declarative.json"), docs.map { it.first })
        val a = org.json.JSONObject(docs[1].second).getJSONArray("assignments")
        val ide = (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("package") == "x.ide" }
        assertEquals("the declarative export carries the declared folder", "ops", ide.getString("folder"))
        assertEquals(st.declarativeModel.folders.map { it.id }, com.diegonmarcos.superapp.launcher.folders.FolderTables.parseFolders(docs[0].second).map { it.id })
    }
}

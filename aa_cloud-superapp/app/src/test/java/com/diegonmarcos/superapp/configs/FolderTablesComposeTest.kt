package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
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
import com.diegonmarcos.superapp.launcher.folders.FolderEditModel
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

    private fun open(): FolderEditorState {
        val st = FolderEditorState(FolderEditModel.build(folders, assign, apps))
        showKit { FolderTablesScreen(st, onSave = {}, onReset = {}, modifier = Modifier.testTag("page")) }
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
        compose.onNodeWithTag(T.tile("dev")).assertTextContains("Develop")
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
        compose.onNodeWithTag(T.NOTICE).assertTextContains("2 app(s) moved to Others")
        scrollTo(T.tile(OTHERS_FOLDER_ID))
        compose.onNodeWithTag(T.tile(OTHERS_FOLDER_ID)).assertTextContains("3")
    }

    @Test fun `Table B dropdown reassigns an app, fills Parent, ticks the needs count and moves the preview tile`() {
        val st = open()
        scrollTo(T.B_NEEDS)
        compose.onNodeWithTag(T.B_NEEDS).assertTextContains("Needs classification (1)")
        scrollTo(T.bFolder("x.new"))
        compose.onNodeWithTag(T.bFolder("x.new")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(T.bOpt("x.new", "media")).performClick()
        compose.waitForIdle()
        assertEquals("media", st.model.apps.first { it.pkg == "x.new" }.folderId)
        scrollTo(T.bSection("x.new"))
        compose.onNodeWithTag(T.bSection("x.new")).assertTextEquals("Life")
        scrollTo(T.B_NEEDS)
        compose.onNodeWithTag(T.B_NEEDS).assertTextContains("Needs classification (0)")
        scrollTo(T.tile("media"))
        compose.onNodeWithTag(T.tile("media")).assertTextContains("2")
    }

    @Test fun `No folder chip filters to the unassigned apps`() {
        val st = open()
        scrollTo(T.bChip(AppFilter.NO_FOLDER))
        compose.onNodeWithTag(T.bChip(AppFilter.NO_FOLDER)).assertTextContains("No folder (1)")
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
}

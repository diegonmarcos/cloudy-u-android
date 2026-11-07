package com.diegonmarcos.superapp.launcher.folders

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two-table editor model: Table A/B edits, needs-classification, grouping/sort/filter, preview, persistence, scope isolation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderEditModelTest {

    private val folders = listOf(
        FolderDef("Work", "dev", "Dev", "ic_dev", 1), FolderDef("Work", "ops", "Ops", "ic_ops", 2),
        FolderDef("Life", "media", "Media", "ic_media", 1),
    )
    private val assign = listOf(FolderAssignment("x.ide", "dev", 1), FolderAssignment("x.term", "dev", 0), FolderAssignment("x.play", "media", 1))
    private val apps = listOf(FolderApp("x.ide", "IDE"), FolderApp("x.term", "Term"), FolderApp("x.play", "Player"), FolderApp("x.new", "Newbie"))
    private fun model() = FolderEditModel.build(folders, assign, apps)
    private fun tiles(m: FolderEditModel) = m.preview().flatMap { it.folders }.associate { it.def.id to it.apps.map { a -> a.pkg } }

    @Test fun `E1 unassigned apps are surfaced as No folder and count as needing classification`() {
        val m = model()
        assertNull(m.apps.first { it.pkg == "x.new" }.folderId)
        assertEquals(1, m.noFolderCount); assertEquals(0, m.othersCount); assertEquals(1, m.needsCount)
        assertEquals(listOf("x.new"), (m.appItems("", AppFilter.NO_FOLDER, null, AppGroup.FOLDER).filterIsInstance<AppItem>()).map { it.row.pkg })
    }

    @Test fun `E2 Table A edit and add update the model, counts and preview`() {
        var m = model().updateFolder("dev") { it.copy(label = "Develop", section = "Make", order = 9) }
        assertEquals(FolderDef("Make", "dev", "Develop", "ic_dev", 9), m.def("dev"))
        assertEquals(2, m.appCount("dev"))
        m = m.addFolder()
        val id = m.newFolderIds.single()
        m = m.renameFolderId(id, "games").setFolder("x.new", "games")
        assertEquals(1, m.appCount("games")); assertEquals(0, m.needsCount)
        assertEquals(listOf("x.new"), tiles(m)["games"])
        assertEquals("existing ids are not renamable", m, m.renameFolderId("dev", "zzz"))
        assertEquals("a taken id is rejected", m, m.renameFolderId("games", "dev"))
    }

    @Test fun `E3 deleting a folder moves its apps to Others and the preview shows them there`() {
        val m = model().deleteFolder("dev")
        assertNull(m.def("dev"))
        assertTrue(m.apps.filter { it.pkg in setOf("x.ide", "x.term") }.all { it.folderId == OTHERS_FOLDER_ID })
        assertEquals(2, m.othersCount)
        val others = m.preview().single { it.title == OTHERS_SECTION }.folders.single()
        assertEquals(setOf("x.ide", "x.term", "x.new"), others.apps.map { it.pkg }.toSet())
        assertFalse(tiles(m).containsKey("dev"))
    }

    @Test fun `E4 Table B reassign sets Parent from the folder and moves the preview tile`() {
        var m = model()
        assertEquals("Work", m.sectionOf(m.apps.first { it.pkg == "x.ide" }))
        m = m.setFolder("x.ide", "media")
        assertEquals("Life", m.sectionOf(m.apps.first { it.pkg == "x.ide" }))
        assertTrue(tiles(m)["media"]!!.contains("x.ide")); assertEquals(listOf("x.term"), tiles(m)["dev"])
        m = m.setFolder("x.term", "media")   // dev emptied -> its tile disappears
        assertFalse(tiles(m).containsKey("dev"))
    }

    @Test fun `E5 default grouping pins needs-classification first then folders in Table A order`() {
        val m = model().setFolder("x.play", OTHERS_FOLDER_ID)
        val heads = m.appItems("", AppFilter.ALL, null, AppGroup.FOLDER).filterIsInstance<THeader>().map { it.title }
        assertEquals(listOf(NEEDS_TITLE, "Dev"), heads)
        val parent = m.appItems("", AppFilter.ALL, null, AppGroup.PARENT).filterIsInstance<THeader>().map { it.level to it.title }
        assertEquals(listOf(0 to NEEDS_TITLE, 0 to "Work", 1 to "Dev"), parent)
    }

    @Test fun `E6 sort and filter`() {
        val m = model()
        val byName = m.appItems("", AppFilter.ALL, SortSpec(BCol.APP, asc = false), AppGroup.NONE).filterIsInstance<AppItem>().map { it.row.name }
        assertEquals(listOf("Term", "Player", "Newbie", "IDE"), byName)
        assertEquals(listOf("IDE"), m.appItems("ide", AppFilter.ALL, null, AppGroup.NONE).filterIsInstance<AppItem>().map { it.row.name })
        val fs = m.folderItems("", SortSpec(ACol.LABEL, asc = false), groupBySection = false).filterIsInstance<FolderItem>().map { it.def.id }
        assertEquals(listOf("ops", "media", "dev"), fs)
        assertEquals(listOf("media"), m.folderItems("life", null, false).filterIsInstance<FolderItem>().map { it.def.id })
        val grouped = m.folderItems("", null, true).filterIsInstance<THeader>().map { it.title }
        assertEquals(listOf("Work", "Life"), grouped)
    }

    // ---- persistence + scope isolation ----

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clean() { ctx.getSharedPreferences("launcher_folder_overrides", android.content.Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `E7 Save persists per scope, Reset reverts, and Cloud and Phone stay isolated`() {
        val repo = FolderRepository(ctx, enumerate = { _, _ -> apps })
        val edited = model().deleteFolder("ops").setFolder("x.new", "media")
        repo.save(FolderScope.PHONE, edited)
        val store = FolderOverrideStore(ctx)
        assertEquals(listOf("dev", "media"), store.savedFolders(FolderScope.PHONE)!!.map { it.id })
        assertEquals("media", store.savedAssignments(FolderScope.PHONE)!!.first { it.pkg == "x.new" }.folderId)
        assertEquals("the grid bridge reads the per-app move", "media", store.all(FolderScope.PHONE)["x.new"])
        assertNull("Cloud untouched", store.savedFolders(FolderScope.CLOUD)); assertTrue(store.all(FolderScope.CLOUD).isEmpty())
        assertTrue(store.hasSaved(FolderScope.PHONE) && !store.hasSaved(FolderScope.CLOUD))
        // the saved override is what the page loads back
        assertEquals("media", repo.editorModel(FolderScope.PHONE).apps.first { it.pkg == "x.new" }.folderId)
        repo.reset(FolderScope.PHONE)
        assertFalse(store.hasSaved(FolderScope.PHONE)); assertTrue(store.all(FolderScope.PHONE).isEmpty())
    }
}

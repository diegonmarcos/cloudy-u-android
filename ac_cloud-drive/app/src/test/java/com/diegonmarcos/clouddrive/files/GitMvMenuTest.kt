package com.diegonmarcos.clouddrive.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1644 menu presence (rule 16: additions only) and the git mv planning/routing. */
class GitMvMenuTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): File = tmp.newFolder("repo").also { File(it, ".git").mkdir(); File(it, "sub").mkdir(); File(it, "a.txt").writeText("x") }

    @Test fun singleFileGitSectionHasOpenRenameMoveOnlyUnderGit() {
        fun git(underGit: Boolean) = EntryMenu.sectionsFor(false, true, emptyList(), false, false, false, underGit).firstOrNull { it.group == EntryMenu.Group.GIT }?.actions
        assertEquals(listOf(EntryMenu.Action.GIT_OPEN, EntryMenu.Action.GIT_RENAME, EntryMenu.Action.GIT_MOVE), git(true))
        assertEquals(null, git(false))
        val all = EntryMenu.sectionsFor(false, true, emptyList(), false, false, false, true).flatMap { it.actions }
        listOf(EntryMenu.Action.RENAME, EntryMenu.Action.DELETE, EntryMenu.Action.COMPRESS, EntryMenu.Action.PROPERTIES).forEach { assertTrue(it in all) }
    }

    @Test fun selectionMenuCarriesEveryRequestedEntry() {
        val a = SelectionMenu.actionsFor(false)
        listOf(SelectionMenu.Action.DELETE_ALL, SelectionMenu.Action.MOVE_ALL, SelectionMenu.Action.CLEAR_SELECTION, SelectionMenu.Action.INVERT_SELECTION, SelectionMenu.Action.ZIP_ALL).forEach { assertTrue(it in a) }
        assertEquals(listOf(SelectionMenu.Action.GIT_MOVE_ALL), SelectionMenu.gitActionsFor(false, true))
        assertTrue(SelectionMenu.gitActionsFor(false, false).isEmpty())
        assertTrue(SelectionMenu.gitActionsFor(true, true).isEmpty())
    }

    @Test fun renameAndMovePlanGitMvPaths() {
        val root = repo()
        val r = GitMv.planRename(File(root, "a.txt"), "b.txt")
        assertEquals("a.txt", r.oldRel); assertEquals("b.txt", r.newRel)
        val m = GitMv.planMove(File(root, "a.txt"), File(root, "sub"))
        assertEquals("sub/a.txt", m.newRel)
    }

    @Test fun refusesWhatGitMvWouldRefuse() {
        val root = repo()
        File(root, "sub/a.txt").writeText("y")
        assertTrue(runCatching { GitMv.planMove(File(root, "a.txt"), File(root, "sub")) }.exceptionOrNull() is GitMv.Refused)
        val outside = tmp.newFolder("outside")
        assertTrue(runCatching { GitMv.planMove(File(root, "a.txt"), outside) }.exceptionOrNull() is GitMv.Refused)
        assertTrue(runCatching { GitMv.planMove(File(root, "sub"), File(root, "sub/deeper").also { it.mkdir() }) }.exceptionOrNull() is GitMv.Refused)
    }

    @Test fun routingGoesThroughTheGitEngineStageAndTheMenusAreWired() {
        val dir = listOf("src/main/java/com/diegonmarcos/clouddrive/files/", "app/src/main/java/com/diegonmarcos/clouddrive/files/", "ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/files/").first { File(it + "GitMv.kt").isFile }
        val mv = File(dir + "GitMv.kt").readText()
        assertTrue(mv.contains("GitEngine(ctx, root).use { it.stage(staged) }"))
        assertTrue(mv.contains("staged += s.oldRel; staged += s.newRel"))
        val screen = File(dir + "FilesScreen.kt").readText()
        assertTrue(screen.contains("controller.gitRename(") && screen.contains("controller.gitMoveEntries("))
        assertTrue(screen.contains("EntryAction.GIT_MOVE -> FilesDialog.Places(") && screen.contains("onGitMoveAll"))
        assertFalse(screen.contains("ProcessBuilder"))
    }
}

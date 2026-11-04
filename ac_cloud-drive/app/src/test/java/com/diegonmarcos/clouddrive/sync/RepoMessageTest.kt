package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1452 the Status Message cell: one line per row, never wrapped under the name or inside the icons. */
class RepoMessageTest {
    private val w = RepoMessage.Words("folder is gone", "not checked yet", { n -> "$n CONFLICTS" }, { e -> "error: $e" })


    @Test fun `a repository with no clone says its note`() {
        assertEquals(RepoMessage.Say("needs a credential", false), RepoMessage.of(false, null, "needs a credential", w))
    }

    @Test fun `a clone says gone, unchecked, its error or its conflicts`() {
        assertEquals(RepoMessage.Say("folder is gone", true), RepoMessage.of(true, GitSyncCoordinator.Glance(gone = true), "", w))
        assertEquals(RepoMessage.Say("not checked yet", false), RepoMessage.of(true, null, "", w))
        assertEquals(RepoMessage.Say("error: boom", true), RepoMessage.of(true, GitSyncCoordinator.Glance(read = true, error = "boom"), "", w))
        assertEquals(RepoMessage.Say("2 CONFLICTS · MERGING", true), RepoMessage.of(true, GitSyncCoordinator.Glance(read = true, conflicts = 2, repositoryState = "MERGING"), "", w))
        assertEquals(RepoMessage.Say("", false), RepoMessage.of(true, GitSyncCoordinator.Glance(read = true), "", w))
    }

    @Test fun `a folder row says the command outcome on one line, else a local problem, else its subtitle`() {
        val failed = RepoMessage.ofFolder("failed: the move failed (nothing changed)", null, "duplicate of x", w)
        assertEquals("failed: the move failed (nothing changed)", failed.text); assertTrue(failed.problem)
        assertFalse(RepoMessage.ofFolder("ok: moved", null, "duplicate of x", w).problem)
        assertEquals("a b", RepoMessage.ofFolder("a\nb", null, "s", w).text)
        assertEquals(RepoMessage.Say("duplicate of x", false), RepoMessage.ofFolder(null, null, "duplicate of x", w))
        assertEquals(RepoMessage.Say("folder is gone", true), RepoMessage.ofFolder(null, GitSyncCoordinator.Glance(gone = true), "duplicate of x", w))
        assertEquals(RepoMessage.Say("duplicate of x", false), RepoMessage.ofFolder(null, GitSyncCoordinator.Glance(read = true), "duplicate of x", w))
    }
}

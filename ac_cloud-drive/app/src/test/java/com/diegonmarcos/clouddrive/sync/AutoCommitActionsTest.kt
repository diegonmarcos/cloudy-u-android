package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1419 Auto Commit as a Drive action (per repo and all repos); the message core is libs:git-repos AutoCommitTest. */
class AutoCommitActionsTest {
    @Test fun perRepoAndAllReposAreTheCompoundVerb() {
        assertTrue(RepoActions.isCompound(RepoActions.AUTO_COMMIT))
        assertTrue(RepoActions.AUTO_COMMIT in RepoActions.PAGE_IDS)
        assertEquals(RepoActions.Route.RUN, RepoActions.route(RepoActions.AUTO_COMMIT))
        assertEquals(SyncAll.Mode.AUTO_COMMIT, SyncAll.modeOf("auto_commit"))
        assertFalse(SyncAll.opensSolver(RepoActions.AUTO_COMMIT, listOf("auto commit: refused" to "conflict")))
    }
}

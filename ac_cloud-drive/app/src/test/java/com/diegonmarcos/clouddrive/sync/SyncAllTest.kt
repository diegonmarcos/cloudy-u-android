package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1356 the Git top bar's Sync dropdown: each mode runs the right engine strategy; No Winner stops at conflicts and opens the solver. */
class SyncAllTest {
    @Test fun `the Sync All modes map to the engine strategies`() {
        assertEquals(RepoActions.SYNC_REMOTE_WINS, SyncAll.Mode.SERVER_WINS.op)
        assertEquals(RepoActions.SYNC_LOCAL_WINS, SyncAll.Mode.LOCAL_WINS.op)
        assertEquals(RepoActions.SYNC_NO_WINNER, SyncAll.Mode.NO_WINNER.op)
        assertEquals(SyncAll.Mode.SERVER_WINS, SyncAll.modeOf("sync_remote_wins"))
        assertEquals(SyncAll.Mode.NO_WINNER, SyncAll.modeOf("sync_no_winner"))
        assertNull(SyncAll.modeOf("force_push"))
    }

    @Test fun `the Manual Sync verbs are compound ids the one engine path runs`() {
        for (id in listOf(RepoActions.STASH, RepoActions.PULL_REBASE, RepoActions.STASH_POP)) {
            assertTrue(id, RepoActions.isCompound(id)); assertTrue(id, id in RepoActions.PAGE_IDS)
            assertEquals(RepoActions.Route.RUN, RepoActions.route(id))
        }
        assertEquals(listOf("stash", "pull_rebase", "stash_pop"), listOf(SyncAll.Mode.STASH, SyncAll.Mode.PULL_REBASE, SyncAll.Mode.STASH_POP).map { it.op })
        assertTrue("stash_pop" in TermuxGitWire.OPS)
    }

    @Test fun `No Winner stops at conflicts and opens the solver`() {
        val conflict = listOf("sync-no-winner: merge conflict: merge aborted, nothing changed" to "")
        assertTrue(SyncAll.opensSolver(RepoActions.SYNC_NO_WINNER, conflict))
        assertFalse(SyncAll.opensSolver(RepoActions.SYNC_NO_WINNER, listOf("sync-no-winner: fetch failed" to "")))
        assertFalse(SyncAll.opensSolver(RepoActions.SYNC_NO_WINNER, emptyList()))
        // a side was picked: never the solver
        assertFalse(SyncAll.opensSolver(RepoActions.SYNC_REMOTE_WINS, conflict))
        assertFalse(SyncAll.opensSolver(RepoActions.SYNC_LOCAL_WINS, conflict))
        assertTrue(SyncAll.opensSolver(RepoActions.STASH_POP, listOf("stash pop: refused" to "CONFLICT (content): Merge conflict in a.txt")))
    }

    @Test fun `Dangerous asks first`() {
        for (id in RepoActions.DANGEROUS) assertEquals(id, RepoActions.Route.CONFIRM, RepoActions.route(id))
        assertEquals(listOf("force_pull", "force_push"), RepoActions.DANGEROUS)
    }

    @Test fun `#1392 every pull-type verb and the clone repair a missing upstream, and a job never needs nohup`() {
        for (verb in listOf("pull", "force_pull", "merge_upstream", "clone")) {
            val plan = TermuxGitWire.plan(verb, "/x/r", org.json.JSONObject().put("url", "https://example.invalid/r.git"), null).first!!
            assertTrue(verb, plan.script.contains("branch.\$B.merge") && plan.script.contains("@{u}"))
        }
        val fp = TermuxGitWire.plan("force_pull", "/x/r", org.json.JSONObject(), null).first!!.script
        assertTrue("the upstream is repaired BEFORE reset --hard @{u}", fp.indexOf("branch.\$B.merge") < fp.indexOf("reset --hard"))
        assertFalse("nohup is not on every cld.termux PATH (exit 127)", com.diegonmarcos.cloudlib.sharedstore.StoreExec.jobStart("true", "/d", "1").contains("nohup"))
    }
}


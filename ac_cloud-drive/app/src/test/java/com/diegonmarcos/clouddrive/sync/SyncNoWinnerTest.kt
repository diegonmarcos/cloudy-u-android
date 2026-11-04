package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncNoWinnerTest {
    // registry renamed_from chains, as Declarations.RepoSotDecl.canonical folds them
    private val renames = mapOf("cloud-infra" to "cloudy-t-infra", "cloud-t-infra" to "cloudy-t-infra", "cloud-u-containers" to "cloudy-u-containers", "cloud-me" to "cloud-me-___", "git-repos-master" to "_git-repos-master")
    private val canonical: (String) -> String = { renames[it] ?: it }

    @Test fun `an old-named clone resolves to its current name by origin url or folder`() {
        assertEquals(SyncNoWinner.Identity("cloudy-t-infra", true), SyncNoWinner.identify("cloud-infra", "git@github.com:diegonmarcos/cloud-infra.git", canonical))
        assertEquals(SyncNoWinner.Identity("cloudy-u-containers", true), SyncNoWinner.identify("cloud-u-containers", "", canonical))
        assertEquals(SyncNoWinner.Identity("cloud-me-___", true), SyncNoWinner.identify("cloud-me", "https://github.com/diegonmarcos/cloud-me", canonical))
        assertEquals(SyncNoWinner.Identity("diegonmarcos", false), SyncNoWinner.identify("diegonmarcos", "https://github.com/diegonmarcos/diegonmarcos.git", canonical))
    }

    @Test fun `one clone rule - a stale old-named duplicate is refused with the current name`() {
        val id = SyncNoWinner.identify("cloud-infra", "", canonical)
        val msg = SyncNoWinner.duplicateOf(id, "cloud-infra", currentHasClone = true)!!
        assertTrue(msg.startsWith("renamed to cloudy-t-infra"))
        assertNull(SyncNoWinner.duplicateOf(id, "cloud-infra", currentHasClone = false))
        assertNull(SyncNoWinner.duplicateOf(SyncNoWinner.identify("diegonmarcos", "", canonical), "diegonmarcos", true))
    }

    @Test fun `a failed merge is explained, never a bare not merged`() {
        val plain = SyncNoWinner.identify("diegonmarcos", "", canonical)
        assertEquals("diverged: 2 local, 5 remote commits and the shallow clone has no common base, nothing changed — open to resolve (remote wins / local wins)",
            SyncNoWinner.explain("fatal: refusing to merge unrelated histories", 2, 5, plain, "diegonmarcos"))
        assertTrue(SyncNoWinner.explain("merge conflict: merge aborted, nothing changed", 1, 1, plain, "x").startsWith("conflict: 1 local, 1 remote"))
        assertTrue(SyncNoWinner.explain("", 3, 4, plain, "x").startsWith("diverged: 3 local, 4 remote commits"))
        val renamed = SyncNoWinner.identify("z-old", "", { if (it == "z-old") "z-archived_front-data" else it })
        assertTrue(SyncNoWinner.explain("", 1, 1, renamed, "z-old").endsWith("(renamed: 'z-old' is now z-archived_front-data)"))
        for (d in listOf("", "fatal: something", "error: Your local changes would be overwritten")) assertFalse(SyncNoWinner.explain(d, -1, -1, plain, "x").contains("not merged"))
    }

    /** #1408 the in-process merge keeps the conflict in place: the text says so (never "nothing changed") and the sheet reads the count back. */
    @Test fun inProcessConflictIsNamedAndCounted() {
        val id = SyncNoWinner.Identity("x", false)
        val why = SyncNoWinner.explain("a.txt\nb.txt\npull conflicts: 2 file(s)", 1, 1, id, "x")
        assertEquals("conflict: 2 file(s) need you, markers kept — open the conflict solver", why)
        assertFalse(why.contains("nothing changed"))
        assertEquals(2, SyncNoWinner.leftConflicts("sync-no-winner: $why"))
        assertEquals(0, SyncNoWinner.leftConflicts("sync-no-winner: fetched and merged"))
        assertEquals(0, SyncNoWinner.leftConflicts(""))
        // the CLI wording (Termux backend aborts the merge) keeps its own sentence
        assertTrue(SyncNoWinner.explain("merge conflict", 1, 1, id, "x").contains("nothing changed"))
    }
}

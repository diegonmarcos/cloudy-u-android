package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {
    private val t: (Long) -> String = { "T$it" }
    private val longErr = "flock: 9: Bad file descriptor\n" + "x".repeat(3000) + "\nfatal: destination path exists"
    private val clone = SyncEvent(100, "id1", "_git-repos-master", SyncHistory.TRIGGER_MANUAL, false, "clone: clone in cld.termux failed (exit 1)", longErr)
    private val ok = SyncEvent(90, "id2", "cloud-me", SyncHistory.TRIGGER_SCHEDULED, true, "pulled, pushed")
    private val bare = SyncEvent(80, "", "", SyncHistory.TRIGGER_MANUAL, true, "")
    private val blankSummary = SyncEvent(70, "id3", "cloudy-t-infra", SyncHistory.TRIGGER_MANUAL, true, "")

    @Test fun `an entry copies untruncated with time, result, repo, trigger, kind and full details`() {
        val s = ActivityLog.entryText(clone, t)
        assertTrue(s.startsWith("T100  FAILED  _git-repos-master  (manual)\nkind: clone\nclone in cld.termux failed (exit 1)\n"))
        assertTrue("the whole error output is copied", s.endsWith(longErr))
        assertFalse(s.contains("…"))
    }

    @Test fun `copy all is one block per entry separated by a blank line`() {
        val s = ActivityLog.allText(listOf(clone, ok), t)
        assertEquals(2, s.split("\n\nT").size)
        assertTrue(s.contains("T90  OK  cloud-me  (scheduled)\nkind: sync\npulled, pushed"))
    }

    @Test fun `bare manual entries are dropped and a blank summary says what ran`() {
        assertEquals(listOf(clone, ok, blankSummary), ActivityLog.visible(listOf(clone, ok, bare, blankSummary)))
        assertEquals(ActivityLog.NO_RESULT, ActivityLog.headline(blankSummary))
        assertEquals("sync", ActivityLog.kindOf(blankSummary))
        assertEquals("sync_no_winner", ActivityLog.kindOf(SyncEvent(1, "i", "r", "manual", false, "sync_no_winner: sync-no-winner: not merged")))
    }

    @Test fun `copy filtered follows status and query`() {
        val all = listOf(clone, ok, bare, blankSummary)
        assertEquals(listOf(clone), ActivityLog.filter(all, ActivityLog.Status.FAILED, ""))
        assertEquals(listOf(ok, blankSummary), ActivityLog.filter(all, ActivityLog.Status.OK, ""))
        assertEquals(listOf(clone), ActivityLog.filter(all, ActivityLog.Status.ALL, "bad file"))
        assertEquals(listOf(ok), ActivityLog.filter(all, ActivityLog.Status.ALL, "CLOUD-ME"))
    }
}

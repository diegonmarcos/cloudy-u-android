package com.diegonmarcos.cloudlib.gitrepos

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #579 the sync history: newest first, capped, round-tripped through JSON, malformed reads as empty. */
class SyncHistoryTest {

    private fun history(cap: Int = 200): SyncHistory = SyncHistory(File(Files.createTempDirectory("hist").toFile(), "git-sync/history.json"), cap)

    @Test fun appendKeepsNewestFirstAndRoundTrips() {
        val h = history()
        h.append(SyncEvent(100, "r1", "cloud-infra", SyncHistory.TRIGGER_MANUAL, true, "pushed main"))
        h.append(SyncEvent(200, "r2", "front-data", SyncHistory.TRIGGER_SCHEDULED, false, "pull conflicts: 2 file(s)", "a.txt\nb.txt"))
        val all = h.load()
        assertEquals(listOf(200L, 100L), all.map { it.epochSeconds })
        assertEquals("a.txt\nb.txt", all.first().details)
        assertTrue(all.first().conflicted)
        assertFalse(all.last().conflicted)
        assertEquals(1, h.forRepo("r1").size)
        assertEquals(200L, h.lastFor("r2")!!.epochSeconds)
        assertNull(h.lastFor("nope"))
    }

    @Test fun capDropsTheOldest() {
        val h = history(cap = 3)
        (1..5).forEach { h.append(SyncEvent(it.toLong(), "r", "r", SyncHistory.TRIGGER_MANUAL, true, "ok")) }
        assertEquals(listOf(5L, 4L, 3L), h.load().map { it.epochSeconds })
    }

    @Test fun malformedFileReadsAsEmpty() {
        val dir = Files.createTempDirectory("hist").toFile()
        val f = File(dir, "history.json").apply { writeText("{ not json") }
        val h = SyncHistory(f)
        assertTrue(h.load().isEmpty())
        h.append(SyncEvent(1, "r", "r", SyncHistory.TRIGGER_MANUAL, true, "ok"))
        assertEquals(1, h.load().size)
    }
}

/** #579 the per-repository scheduling decision the worker makes on every base tick. */
class SyncScheduleTest {

    private val base = 60L
    private val now = 10_000L

    @Test fun optedOutIsNeverDue() {
        assertFalse(SyncSchedule.isDue(false, 0, base, 0, now, requireUnmetered = false, networkUnmetered = true))
    }

    @Test fun neverSyncedIsDueAtOnce() {
        assertTrue(SyncSchedule.isDue(true, 0, base, 0, now, requireUnmetered = false, networkUnmetered = false))
    }

    @Test fun ownPeriodOverridesTheBase() {
        // Base 60, repo 180: 61 minutes after the last sync is NOT due; 181 is.
        assertFalse(SyncSchedule.isDue(true, 180, base, now - 61 * 60, now, false, true))
        assertTrue(SyncSchedule.isDue(true, 180, base, now - 181 * 60, now, false, true))
        // Repo 0 = the base: 59 minutes and 30 seconds is due (a minute of slack), 30 minutes is not.
        assertTrue(SyncSchedule.isDue(true, 0, base, now - (59 * 60 + 30), now, false, true))
        assertFalse(SyncSchedule.isDue(true, 0, base, now - 30 * 60, now, false, true))
    }

    @Test fun networkRuleHolds() {
        assertFalse("Wi-Fi only, on mobile data", SyncSchedule.isDue(true, 0, base, 0, now, requireUnmetered = true, networkUnmetered = false))
        assertFalse("Wi-Fi only, platform would not say", SyncSchedule.isDue(true, 0, base, 0, now, requireUnmetered = true, networkUnmetered = null))
        assertTrue(SyncSchedule.isDue(true, 0, base, 0, now, requireUnmetered = true, networkUnmetered = true))
        assertTrue("any network, on mobile data", SyncSchedule.isDue(true, 0, base, 0, now, requireUnmetered = false, networkUnmetered = false))
    }

    @Test fun minutesUntilNextRoundsUp() {
        assertNull(SyncSchedule.minutesUntilNext(null, 0))
        assertNull(SyncSchedule.minutesUntilNext(0L, 0))
        assertEquals(1L, SyncSchedule.minutesUntilNext(1_000L, 0))
        assertEquals(2L, SyncSchedule.minutesUntilNext(61_000L, 0))
        assertEquals(0L, SyncSchedule.minutesUntilNext(5_000L, 9_000L))
    }
}

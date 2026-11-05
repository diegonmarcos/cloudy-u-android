package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.StoreActivity.Group
import com.diegonmarcos.superapp.appstore.StoreActivity.Phase
import com.diegonmarcos.superapp.appstore.dl.DlJob
import com.diegonmarcos.superapp.appstore.dl.DlState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1524 The one Activity model: the Overall aggregation, the collapsed default, the remembered expand state, downloads and installs side by side. */
class StoreActivityTest {

    private fun job(id: String, state: DlState, bytes: Long = 0, total: Long = 1_000_000, bps: Long = 0, at: Long = 1_000, err: String = "") =
        DlJob(id = id, pkg = "p.$id", versionCode = 1, label = id.uppercase(), namePrefix = "dl-$id-", priority = 0, userStarted = true,
            addedAt = at, state = state, bytes = bytes, total = total, bytesPerSec = bps, error = err)

    private fun row(id: String, phase: JobBoard.Phase, bytes: Long = 0, total: Long = 0, reason: String = "", waiting: String = "") =
        JobBoard.Row("p.$id", id.uppercase(), phase, bytes, total, reason = reason, waitingFor = waiting)

    private fun overall(jobs: List<DlJob>, rows: List<JobBoard.Row> = emptyList(), updates: Int = 0) =
        StoreActivity.overall(StoreActivity.items(jobs, rows), updates)

    @Test fun countsEveryPhase() {
        val o = overall(
            listOf(job("a", DlState.RUNNING, 100, bps = 10), job("b", DlState.RUNNING, 100, bps = 10), job("c", DlState.VERIFYING, 1_000_000),
                job("q1", DlState.QUEUED), job("q2", DlState.QUEUED), job("p", DlState.PAUSED, 5), job("f", DlState.FAILED, err = "HTTP 404")),
            listOf(row("i", JobBoard.Phase.INSTALLING), row("d", JobBoard.Phase.DONE)),
        )
        assertEquals(3, o.downloading)   // two running and one verifying
        assertEquals(1, o.installing)
        assertEquals(2, o.queued)
        assertEquals(1, o.paused)
        assertEquals(1, o.failed)
        assertEquals(1, o.done)
        assertTrue(o.busy)
    }

    @Test fun progressIsWeightedBySizeAndKeepsFinishedWork() {
        // 1 MB at 50% and 3 MB at 0%: (1 * .45 + 3 * 0) / 4 = 11%; the download share is 90%, the install step the last 10%.
        val big = overall(listOf(job("a", DlState.RUNNING, 500_000, total = 1_000_000), job("b", DlState.QUEUED, total = 3_000_000)))
        assertEquals(11, big.percent)
        // A finished app of the same session still counts, so the bar does not fall when one ends: (1 * 1.0 + 1 * 0) / 2 = 50%.
        val half = overall(listOf(job("a", DlState.DONE, 1_000_000, at = 1_000), job("b", DlState.QUEUED, at = 1_001)))
        assertEquals(50, half.percent)
        // An install step in flight is 90%.
        assertEquals(90, overall(emptyList(), listOf(row("i", JobBoard.Phase.INSTALLING, 10, 10))).percent)
        // A failure is not progress: it is left out of the percent.
        assertEquals(0, overall(listOf(job("f", DlState.FAILED, 900_000), job("q", DlState.QUEUED))).percent)
    }

    @Test fun anOldFinishedItemDoesNotInflateANewSession() {
        val old = job("old", DlState.DONE, 1_000_000, at = 1_000)
        val fresh = job("new", DlState.RUNNING, 0, at = 1_000 + StoreActivity.SESSION_MS + 1)
        assertEquals(0, overall(listOf(old, fresh)).percent)
    }

    @Test fun speedIsCombinedAndEtaIsWhatIsLeftAtThatSpeed() {
        // Two running downloads at 1 MB/s and 0.5 MB/s with 0.5 MB and 1.5 MB left, and a queued 2 MB one: 4 MB / 1.5 MB/s = 2 s.
        val o = overall(listOf(
            job("a", DlState.RUNNING, 500_000, bps = 1_000_000), job("b", DlState.RUNNING, 0, total = 1_500_000, bps = 500_000),
            job("q", DlState.QUEUED, total = 2_000_000)))
        assertEquals(1_500_000L, o.bytesPerSec)
        assertEquals(4_000_000L / 1_500_000L, o.etaSec)
        // Nothing moving: no speed and no ETA.
        val idle = overall(listOf(job("q", DlState.QUEUED)))
        assertEquals(0L, idle.bytesPerSec); assertEquals(-1L, idle.etaSec)
    }

    @Test fun theLineReadsLikeTheSpec() {
        val o = StoreActivity.Overall(3, 1, 2, 0, 0, 0, 45, 12_300_000, 60)
        assertEquals("3 downloading · 1 installing · 2 queued · 45% · 12.3 MB/s · ~1 min left", o.text())
        assertEquals("All up to date", StoreActivity.Overall(0, 0, 0, 0, 0, 0, 0, 0, -1).text())
        assertEquals("2 updates available", StoreActivity.Overall(0, 0, 0, 0, 0, 0, 0, 0, -1, updates = 2).text())
        assertEquals("1 update available", StoreActivity.Overall(0, 0, 0, 0, 0, 0, 0, 0, -1, updates = 1).text())
        // Errors are a count, never spread into the line.
        assertEquals("All up to date", StoreActivity.Overall(0, 0, 0, 0, 4, 0, 0, 0, -1).text())
    }

    @Test fun theToneIsActivePausedErrorOrIdle() {
        assertEquals(StoreActivity.Tone.ACTIVE, overall(listOf(job("a", DlState.RUNNING))).tone)
        assertEquals(StoreActivity.Tone.PAUSED, overall(listOf(job("a", DlState.PAUSED))).tone)
        assertEquals(StoreActivity.Tone.ERROR, overall(listOf(job("a", DlState.FAILED))).tone)
        assertEquals(StoreActivity.Tone.IDLE, overall(emptyList()).tone)
        // A failure next to live work does not recolour the line: the chip carries it.
        assertEquals(StoreActivity.Tone.ACTIVE, overall(listOf(job("a", DlState.RUNNING), job("f", DlState.FAILED))).tone)
    }

    // ── the panel's view state ──────────────────────────────────────────────────────

    private class MemFlags(val m: MutableMap<String, Boolean> = HashMap()) : StoreActivity.Flags {
        override fun get(key: String, default: Boolean) = m[key] ?: default
        override fun put(key: String, value: Boolean) { m[key] = value }
    }

    @Test fun theDefaultIsCollapsedWithFinishedClosed() {
        val p = StoreActivity.Prefs(MemFlags())
        assertFalse(p.expanded)
        assertFalse(p.finishedOpen)
    }

    @Test fun theExpandedStateIsRememberedAcrossARestart() {
        val disk = MemFlags()
        StoreActivity.Prefs(disk).expanded = true
        StoreActivity.Prefs(disk).finishedOpen = true
        val reopened = StoreActivity.Prefs(disk)   // a new process reads the same store
        assertTrue(reopened.expanded); assertTrue(reopened.finishedOpen)
        reopened.expanded = false
        assertFalse(StoreActivity.Prefs(disk).expanded)
    }

    // ── downloads and installs, in parallel ─────────────────────────────────────────

    @Test fun aDownloadAndAnInstallOfOtherAppsShowTogether() {
        val items = StoreActivity.items(
            listOf(job("a", DlState.RUNNING, 300_000, bps = 100_000), job("b", DlState.RUNNING, 0, bps = 50_000), job("c", DlState.QUEUED)),
            listOf(row("x", JobBoard.Phase.INSTALLING), row("y", JobBoard.Phase.QUEUED, waiting = "install")),
        )
        val active = StoreActivity.section(items, Group.ACTIVE)
        assertEquals(setOf("p.a", "p.b", "p.x"), active.map { it.key }.toSet())
        assertEquals(setOf(Phase.DOWNLOADING, Phase.INSTALLING), active.map { it.phase }.toSet())
        assertEquals(listOf("p.c", "p.y"), StoreActivity.section(items, Group.QUEUED).map { it.key })
        assertEquals("Installing", items.first { it.key == "p.x" }.phaseText())
        assertEquals("Queued · waiting for the install slot", items.first { it.key == "p.y" }.phaseText())
        assertEquals("Downloading 30% · 100 kB/s · 7s left", items.first { it.key == "p.a" }.phaseText())
    }

    @Test fun oneAppHandsFromItsDownloadToItsInstall() {
        val dl = job("a", DlState.DONE, 1_000_000)
        assertEquals(Phase.DONE, StoreActivity.items(listOf(dl), emptyList()).single().phase)
        val installing = StoreActivity.items(listOf(dl), listOf(row("a", JobBoard.Phase.INSTALLING))).single()
        assertEquals(Phase.INSTALLING, installing.phase)
        assertFalse("the install is not the engine's to pause or cancel", installing.canPause || installing.canCancel)
        assertEquals(Phase.FAILED, StoreActivity.items(listOf(dl), listOf(row("a", JobBoard.Phase.FAILED, reason = "INSTALL_FAILED"))).single().phase)
        assertEquals("Failed: INSTALL_FAILED", StoreActivity.items(listOf(dl), listOf(row("a", JobBoard.Phase.FAILED, reason = "INSTALL_FAILED"))).single().phaseText())
    }

    @Test fun aCancelledDownloadIsNotShownAndTheRowVerbsFollowTheState() {
        assertTrue(StoreActivity.items(listOf(job("a", DlState.CANCELLED)), emptyList()).isEmpty())
        val running = StoreActivity.items(listOf(job("a", DlState.RUNNING)), emptyList()).single()
        assertTrue(running.canPause && running.canCancel && !running.canResume && !running.canRetry)
        val paused = StoreActivity.items(listOf(job("a", DlState.PAUSED, 2_000)), emptyList()).single()
        assertTrue(paused.canResume && paused.canCancel && !paused.canPause)
        assertEquals("Paused · 2 kB kept", paused.phaseText())
        val failed = StoreActivity.items(listOf(job("a", DlState.FAILED, err = "HTTP 404")), emptyList()).single()
        assertTrue(failed.canRetry && failed.canCancel)
        assertEquals("Failed: HTTP 404", failed.phaseText())
    }

    @Test fun theFinishedSectionListsFailuresFirstAndOnlyTheRecentOnes() {
        val jobs = (1..15).map { job("d$it", DlState.DONE, at = it.toLong()) } + job("bad", DlState.FAILED, at = 0)
        val fin = StoreActivity.section(StoreActivity.items(jobs, emptyList()), Group.FINISHED)
        assertEquals(StoreActivity.RECENT, fin.size)
        assertEquals("p.bad", fin.first().key)
        assertEquals("p.d15", fin[1].key)
    }
}

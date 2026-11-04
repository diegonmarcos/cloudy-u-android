package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.clouddrive.DrivePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1195 the auto-behaviours of Git / Fsync / Mounts: everything OFF by default, persisted in DrivePrefs, and the plan each trigger asks for. */
@RunWith(RobolectricTestRunner::class)
class AutoChecksTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `every flag of every page is off by default and nothing is planned`() {
        val prefs = DrivePrefs(ctx)
        for (page in AutoChecks.Page.entries) {
            val s = prefs.autoChecks(page)
            AutoChecks.flags(page).forEach { assertFalse("$page $it must be off by default", s.on(it)) }
            assertEquals(0, s.intervalMinutes)
            assertFalse(s.anyOn)
            assertTrue("opening $page starts no background work", AutoChecks.plan(s, AutoChecks.Trigger.OPEN).isEmpty())
            assertTrue(AutoChecks.plan(s, AutoChecks.Trigger.TICK).isEmpty())
        }
    }

    @Test fun `git offers the six owner flags and fsync and mounts offer status and state`() {
        assertEquals(
            listOf(AutoChecks.Flag.STATUS_ON_OPEN, AutoChecks.Flag.DIRTY, AutoChecks.Flag.BEHIND_AHEAD, AutoChecks.Flag.FETCH, AutoChecks.Flag.PULL, AutoChecks.Flag.SIZE),
            AutoChecks.flags(AutoChecks.Page.GIT),
        )
        assertEquals(listOf(AutoChecks.Flag.STATUS_ON_OPEN, AutoChecks.Flag.STATE), AutoChecks.flags(AutoChecks.Page.FSYNC))
        assertEquals(AutoChecks.flags(AutoChecks.Page.FSYNC), AutoChecks.flags(AutoChecks.Page.MOUNTS))
    }

    @Test fun `a switch persists across a new DrivePrefs and only touches its own page`() {
        val a = DrivePrefs(ctx)
        a.setAutoCheck(AutoChecks.Page.GIT, AutoChecks.Flag.FETCH, true)
        a.setAutoInterval(AutoChecks.Page.MOUNTS, 15)
        val b = DrivePrefs(ctx)
        assertTrue(b.autoChecks(AutoChecks.Page.GIT).on(AutoChecks.Flag.FETCH))
        assertFalse(b.autoChecks(AutoChecks.Page.GIT).on(AutoChecks.Flag.DIRTY))
        assertFalse(b.autoChecks(AutoChecks.Page.FSYNC).on(AutoChecks.Flag.STATE))
        assertEquals(15, b.autoChecks(AutoChecks.Page.MOUNTS).intervalMinutes)
        assertEquals(0, b.autoChecks(AutoChecks.Page.GIT).intervalMinutes)
        // The file that holds them is the one Auto Backup carries (it is not on the exclude lists).
        val rules = java.io.File(System.getProperty("user.dir")).let { if (java.io.File(it, "build.json").isFile) it else it.parentFile }
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { assertFalse(java.io.File(rules, "app/src/main/res/xml/$it").readText().contains("cloud-drive-prefs")) }
    }

    @Test fun `the git pull flag is the key the older auto pull switch wrote`() {
        assertEquals("git.auto_pull_on_open", AutoChecks.key(AutoChecks.Page.GIT, AutoChecks.Flag.PULL))
        assertEquals(AutoChecks.GIT_PULL_KEY, GitSyncCoordinator.KEY_AUTO_PULL)
    }

    private fun settings(page: AutoChecks.Page, vararg on: AutoChecks.Flag, interval: Int = 0) =
        AutoChecks.Settings(page, on.associate { AutoChecks.key(page, it) to true } + (AutoChecks.intervalKey(page) to interval))

    @Test fun `each git flag asks for exactly its own work`() {
        val g = AutoChecks.Page.GIT
        assertEquals(setOf(AutoChecks.Work.READ_STATUS), AutoChecks.plan(settings(g, AutoChecks.Flag.STATUS_ON_OPEN), AutoChecks.Trigger.OPEN))
        assertEquals(setOf(AutoChecks.Work.READ_STATUS), AutoChecks.plan(settings(g, AutoChecks.Flag.DIRTY), AutoChecks.Trigger.OPEN))
        assertEquals(setOf(AutoChecks.Work.READ_STATUS), AutoChecks.plan(settings(g, AutoChecks.Flag.BEHIND_AHEAD), AutoChecks.Trigger.OPEN))
        assertEquals(setOf(AutoChecks.Work.FETCH_ALL), AutoChecks.plan(settings(g, AutoChecks.Flag.FETCH), AutoChecks.Trigger.OPEN))
        assertEquals(setOf(AutoChecks.Work.PULL_ALL), AutoChecks.plan(settings(g, AutoChecks.Flag.PULL), AutoChecks.Trigger.OPEN))
        assertEquals(setOf(AutoChecks.Work.REFRESH_SIZES), AutoChecks.plan(settings(g, AutoChecks.Flag.SIZE), AutoChecks.Trigger.OPEN))
    }

    @Test fun `a tick only runs with an interval, never repeats a pull and ignores status on open`() {
        val g = AutoChecks.Page.GIT
        assertTrue(AutoChecks.plan(settings(g, AutoChecks.Flag.FETCH), AutoChecks.Trigger.TICK).isEmpty())
        val s = settings(g, AutoChecks.Flag.FETCH, AutoChecks.Flag.PULL, AutoChecks.Flag.STATUS_ON_OPEN, interval = 15)
        assertEquals(setOf(AutoChecks.Work.FETCH_ALL), AutoChecks.plan(s, AutoChecks.Trigger.TICK))
    }

    @Test fun `fsync and mounts refresh their state only when asked`() {
        for (p in listOf(AutoChecks.Page.FSYNC, AutoChecks.Page.MOUNTS)) {
            assertEquals(setOf(AutoChecks.Work.REFRESH_STATE), AutoChecks.plan(settings(p, AutoChecks.Flag.STATUS_ON_OPEN), AutoChecks.Trigger.OPEN))
            assertEquals(setOf(AutoChecks.Work.REFRESH_STATE), AutoChecks.plan(settings(p, AutoChecks.Flag.STATE, interval = 5), AutoChecks.Trigger.TICK))
            assertTrue(AutoChecks.plan(settings(p, AutoChecks.Flag.STATE), AutoChecks.Trigger.TICK).isEmpty())
        }
    }
}

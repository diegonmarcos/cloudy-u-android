package com.diegonmarcos.clouddrive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1643 no repository is cloned by app start, an update, a first run or a retry; only the user's action clones. */
class NoAutoCloneTest {
    private fun main(rel: String): String {
        val roots = listOf("src/main/java/com/diegonmarcos/clouddrive/", "app/src/main/java/com/diegonmarcos/clouddrive/", "ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/")
        return roots.map { File(it + rel) }.first { it.isFile }.readText()
    }

    @Test fun onlyAnExplicitActionMayClone() {
        assertTrue(CloneTrigger.mayClone(true))
        assertFalse("automatic (default) passes never clone", CloneTrigger.mayClone(false))
    }

    @Test fun anAutomaticPassReportsAMissingRepositoryInsteadOfCloningIt() {
        val report = SeedReport(2, listOf(
            SeedOutcome("a", SeedOutcome.PRESENT, "/store/git/a"),
            SeedOutcome("b", SeedOutcome.NOT_CLONED, "not cloned automatically"),
        ))
        assertTrue("terminal: no retry loop re-attempting a clone", report.complete)
        assertEquals(1, report.present.size)
        assertFalse(report.tally().endsWith("complete"))
        assertTrue(report.tally().contains("b"))
        assertEquals(SeedPolicy.Next.DONE, SeedPolicy.decide(report, 1))
    }

    @Test fun theClonePathIsGatedAndOnlyTheUserButtonIsExplicit() {
        val seed = main("StoreSeed.kt")
        val gate = seed.indexOf("!CloneTrigger.mayClone(explicit)")
        val clone = seed.indexOf("StoreGit.clone(")
        assertTrue("the gate precedes the clone call", gate in 0 until clone)
        // schedule (onCreate) and kick (onResume / grant) enqueue with the default explicit=false.
        assertTrue(seed.contains("fun kick(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)"))
        assertTrue(seed.contains("fun cloneMissing(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE, explicit = true)"))
        assertTrue(seed.contains("runCatching { enqueue(context, ExistingWorkPolicy.KEEP) }"))
        val activity = main("MainActivity.kt")
        assertFalse("app start / resume never request an explicit clone", activity.contains("cloneMissing"))
        assertTrue(main("sync/GitReposScreen.kt").contains("StoreSeedWorker.cloneMissing("))
    }
}

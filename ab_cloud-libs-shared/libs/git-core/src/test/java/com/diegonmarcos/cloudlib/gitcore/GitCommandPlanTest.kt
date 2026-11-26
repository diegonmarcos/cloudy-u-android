package com.diegonmarcos.cloudlib.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1934 the command preview rules of the control sheet, as exact strings (the reference's build rules). */
class GitCommandPlanTest {
    private fun p(o: SyncOptions) = GitStep.preview(o.steps())

    @Test fun safePullPushPreview() = assertEquals("git pull --ff-only && git push", p(SyncOptions()))
    @Test fun fetchFlags() = assertEquals("git fetch --all --prune --tags --dry-run", p(SyncOptions(direction = "fetch", scope = "all", prune = true, tags = true, dryRun = true)))
    @Test fun mergeRemoteWinsAutostash() = assertEquals("git pull --no-rebase -X theirs --autostash", p(SyncOptions(direction = "pull", strategy = "merge", winner = "remote", dirty = "autostash")))
    @Test fun rebaseLocalWinsIsRefusedByTheEngineButPreviewed() { val o = SyncOptions(direction = "pull", strategy = "rebase", winner = "local"); assertEquals("git pull --rebase -X ours", p(o)); assertTrue(o.unsupported() != null) }
    @Test fun autoCommitComesFirstAndIsTolerant() = assertEquals("git add -A && git commit -m \"fix: it's\" || true && git pull --ff-only && git push", p(SyncOptions(dirty = "autocommit", commitMessage = "fix: it's")))
    @Test fun forcePullBecomesPullAndDropsDirty() = assertEquals("git fetch && git reset --hard @{u}", p(SyncOptions(direction = "both", strategy = "reset", dirty = "autocommit")))
    @Test fun forcePullDryRunSkipsTheReset() = assertEquals("git fetch --dry-run && git status -sb", p(SyncOptions(direction = "pull", strategy = "reset", dryRun = true)))
    @Test fun forceRawBeatsLeaseAndIsDestructive() { val o = SyncOptions(direction = "push", forceLease = true, forceRaw = true); assertEquals("git push --force", p(o)); assertTrue(o.destructive()); assertEquals("destructive", o.badge()) }
    @Test fun leaseIsCaution() { val o = SyncOptions(direction = "push", forceLease = true); assertEquals("git push --force-with-lease", p(o)); assertEquals("caution", o.badge()); assertFalse(o.destructive()) }
    @Test fun pruneIsNotApplicableToPush() = assertEquals("git push", p(SyncOptions(direction = "push", prune = true)))
    @Test fun dryRunIsPreviewOnly() { val o = SyncOptions(dryRun = true, dirty = "autocommit"); assertEquals("preview", o.badge()); assertFalse(p(o).contains("commit")) }
    @Test fun submodulesAreDisabledWithAReason() { assertTrue(SyncOptions(submodules = true).unsupported()!!.contains("submodule")); assertNull(SyncOptions().unsupported()) }

    @Test fun cloneNamesAndCommands() {
        assertEquals("Full", CloneOptions().profileName())
        assertEquals("Bare Main Depth 1 Treeless", CloneOptions(workspace = "bare", branches = "main", history = "shallow", depth = 1, trees = false).profileName())
        assertEquals("git clone --bare --single-branch --branch main --depth 3 --filter=blob:none U S/r.git",
            GitStep.preview(listOf(CloneOptions(workspace = "bare", branches = "main", history = "shallow", depth = 3, blobs = false).step("U", "S/r.git"))))
        assertEquals("git clone U D", GitStep.preview(listOf(CloneOptions().step("U", "D"))))
        assertEquals("git clone --depth 1 --no-single-branch U D", GitStep.preview(listOf(CloneOptions(history = "shallow", depth = 1).step("U", "D"))))
        assertEquals("M/r.git", CloneOptions(dest = "mirror").destPath("r", "S", "W", "M"))
    }
}

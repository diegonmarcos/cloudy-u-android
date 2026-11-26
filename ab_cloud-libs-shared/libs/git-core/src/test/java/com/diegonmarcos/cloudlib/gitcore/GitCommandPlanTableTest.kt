package com.diegonmarcos.cloudlib.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1934 the exhaustive exact-preview table of the option model: every branch of the plan rules, as strings. */
class GitCommandPlanTableTest {
    private fun p(x: SyncOptions) = GitStep.preview(x.steps())

    private fun o(
        d: String = "both", s: String = "ff", w: String = "none", dy: String = "none", sc: String = "current", pr: Boolean = false, tg: Boolean = false, sm: Boolean = false,
        fl: Boolean = false, fr: Boolean = false, dr: Boolean = false, msg: String = "chore: auto-sync",
    ) = SyncOptions(d, s, w, dy, msg, sc, pr, tg, sm, fl, fr, dr)

    @Test fun fetchEveryFlagAlone() {
        assertEquals("git fetch", p(o("fetch")))
        assertEquals("git fetch --all", p(o("fetch", sc = "all")))
        assertEquals("git fetch --prune", p(o("fetch", pr = true)))
        assertEquals("git fetch --tags", p(o("fetch", tg = true)))
        assertEquals("git fetch --recurse-submodules", p(o("fetch", sm = true)))
        assertEquals("git fetch --dry-run", p(o("fetch", dr = true)))
        assertEquals("git fetch", p(o("fetch", fl = true, fr = true, dy = "autocommit", w = "remote", s = "rebase")))
    }

    @Test fun pullEveryStrategyWinnerAndFlag() {
        assertEquals("git pull --ff-only", p(o("pull")))
        assertEquals("git pull --ff-only", p(o("pull", w = "remote")))
        assertEquals("git pull --no-rebase", p(o("pull", "merge")))
        assertEquals("git pull --rebase", p(o("pull", "rebase")))
        assertEquals("git pull --no-rebase -X theirs", p(o("pull", "merge", "remote")))
        assertEquals("git pull --no-rebase -X ours", p(o("pull", "merge", "local")))
        assertEquals("git pull --rebase -X theirs", p(o("pull", "rebase", "remote")))
        assertEquals("git pull --rebase -X ours", p(o("pull", "rebase", "local")))
        assertEquals("git pull --ff-only --all", p(o("pull", sc = "all")))
        assertEquals("git pull --ff-only --prune", p(o("pull", pr = true)))
        assertEquals("git pull --ff-only --autostash", p(o("pull", dy = "autostash")))
        assertEquals("git pull --ff-only --tags", p(o("pull", tg = true)))
        assertEquals("git pull --ff-only --recurse-submodules", p(o("pull", sm = true)))
        assertEquals("git pull --ff-only --dry-run", p(o("pull", dr = true)))
        assertEquals("git pull --ff-only", p(o("pull", fl = true, fr = true)))
    }

    @Test fun pushEveryFlag() {
        assertEquals("git push", p(o("push")))
        assertEquals("git push --all", p(o("push", sc = "all")))
        assertEquals("git push --tags", p(o("push", tg = true)))
        assertEquals("git push --recurse-submodules=on-demand", p(o("push", sm = true)))
        assertEquals("git push --force", p(o("push", fr = true)))
        assertEquals("git push --force-with-lease", p(o("push", fl = true)))
        assertEquals("git push --force", p(o("push", fl = true, fr = true)))
        assertEquals("git push --dry-run", p(o("push", dr = true)))
        assertEquals("git push", p(o("push", pr = true, s = "merge", w = "remote", dy = "autostash")))
    }

    @Test fun bothRunsPullThenPush() {
        assertEquals("git pull --no-rebase --all && git push --all", p(o("both", "merge", sc = "all")))
        assertEquals("git pull --rebase --autostash && git push --force-with-lease", p(o("both", "rebase", dy = "autostash", fl = true)))
        assertEquals("git pull --ff-only --prune --tags && git push --tags", p(o("both", pr = true, tg = true)))
    }

    @Test fun forcePullEveryVariant() {
        assertEquals("git fetch && git reset --hard @{u}", p(o("pull", "reset", w = "local", dy = "autocommit", sm = true)))
        assertEquals("git fetch --all --prune && git reset --hard @{u}", p(o("pull", "reset", sc = "all", pr = true)))
        assertEquals("git fetch && git reset --hard @{u}", p(o("pull", "reset", tg = true)))
        assertEquals("git fetch --dry-run && git status -sb", p(o("both", "reset", dr = true)))
        assertEquals("git fetch && git reset --hard @{u}", p(o("both", "reset", fr = true)))
        val n = o("both", "reset", w = "local", dy = "autostash").normalized()
        assertEquals("pull", n.direction); assertEquals("remote", n.winner); assertEquals("none", n.dirty)
    }

    @Test fun autoCommitRules() {
        assertEquals("git add -A && git commit -m \"chore: auto-sync\" || true && git pull --ff-only && git push", p(o(dy = "autocommit")))
        assertEquals("git fetch", p(o("fetch", dy = "autocommit")))
        assertEquals("git add -A && git commit -m x || true && git push", p(o("push", dy = "autocommit", msg = "x")))
        assertEquals("git pull --ff-only --dry-run && git push --dry-run", p(o(dy = "autocommit", dr = true)))
        assertEquals("git fetch && git reset --hard @{u}", p(o("pull", "reset", dy = "autocommit")))
        assertEquals("git add -A && git commit -m \"a \\\"b\\\" c\" || true && git pull --ff-only && git push", p(o(dy = "autocommit", msg = "a \"b\" c")))
        assertEquals("git add -A && git commit -m \"\" || true && git pull --ff-only && git push", p(o(dy = "autocommit", msg = "")))
        assertTrue(o(dy = "autocommit").steps()[1].tolerant); assertFalse(o(dy = "autocommit").steps()[0].tolerant)
    }

    @Test fun flagApplicabilityPerDirection() {
        for (d in listOf("fetch", "pull")) { assertFalse(o(d).applicable("forceLease")); assertFalse(o(d).applicable("forceRaw")) }
        for (d in listOf("push", "both")) { assertTrue(o(d).applicable("forceLease")); assertTrue(o(d).applicable("forceRaw")) }
        assertFalse(o("push").applicable("prune"))
        for (d in listOf("fetch", "pull", "both")) assertTrue(o(d).applicable("prune"))
        assertFalse(o("pull", "reset").applicable("submodules")); assertTrue(o("pull", "merge").applicable("submodules"))
        for (f in listOf("tags", "dryRun", "anything")) assertTrue(o("push").applicable(f))
    }

    @Test fun badgesAndDestructive() {
        assertEquals("preview", o(dr = true, fr = true).badge()); assertEquals("preview", o("pull", "reset", dr = true).badge())
        assertEquals("destructive", o("pull", "reset").badge()); assertEquals("destructive", o("push", fr = true).badge()); assertEquals("destructive", o("both", fr = true).badge())
        assertEquals("caution", o("push", fl = true).badge()); assertEquals("caution", o("both", fl = true).badge())
        assertEquals("read_only", o("fetch").badge()); assertEquals("safe", o("pull").badge()); assertEquals("safe", o("both").badge()); assertEquals("safe", o("push").badge())
        assertTrue(o("pull", "reset").destructive()); assertTrue(o("both", "reset").destructive()); assertTrue(o("push", fr = true).destructive())
        assertFalse(o("pull", "reset", dr = true).destructive()); assertFalse(o("push", fr = true, dr = true).destructive()); assertFalse(o("pull", fr = true).destructive())
        assertFalse(o("fetch", "reset").destructive()); assertFalse(o("push", fl = true).destructive())
    }

    @Test fun unsupportedCombinationsStateAReason() {
        assertNull(o().unsupported()); assertNull(o("pull", "merge", "remote").unsupported()); assertNull(o("pull", "reset", sm = true).unsupported())
        assertTrue(o(sm = true).unsupported()!!.startsWith("submodules")); assertTrue(o("fetch", sm = true).unsupported()!!.contains("JGit"))
        assertTrue(o("pull", "rebase", "remote").unsupported()!!.startsWith("winner with rebase"))
        assertNull(o("push", "rebase", "remote").unsupported()); assertNull(o("pull", "rebase").unsupported())
    }

    @Test fun cloneProfileNames() {
        assertEquals("Full", CloneOptions().profileName())
        assertEquals("Depth 3", CloneOptions(history = "shallow", depth = 3).profileName())
        assertEquals("Bare Depth 1", CloneOptions(workspace = "bare", history = "shallow", depth = 1).profileName())
        assertEquals("Main Full", CloneOptions(branches = "main").profileName())
        assertEquals("Depth 1", CloneOptions(history = "shallow", depth = 0).profileName())
        assertEquals("Depth 9999", CloneOptions(history = "shallow", depth = 99999).profileName())
        assertEquals("Blobless", CloneOptions(blobs = false).profileName())
        assertEquals("Treeless", CloneOptions(trees = false).profileName())
        assertEquals("Treeless", CloneOptions(trees = false, blobs = false).profileName())
        assertEquals("Bare Main Depth 5 Blobless", CloneOptions(workspace = "bare", branches = "main", history = "shallow", depth = 5, blobs = false).profileName())
        assertEquals("blob:none", CloneOptions(blobs = false).filter); assertEquals("tree:0", CloneOptions(trees = false).filter); assertEquals("", CloneOptions().filter)
    }

    @Test fun cloneCommandsAndDestinations() {
        fun c(x: CloneOptions) = GitStep.preview(listOf(x.step("U", "D")))
        assertEquals("git clone --bare U D", c(CloneOptions(workspace = "bare")))
        assertEquals("git clone --single-branch --branch main U D", c(CloneOptions(branches = "main", branch = "  ")))
        assertEquals("git clone --single-branch --branch dev U D", c(CloneOptions(branches = "main", branch = " dev ")))
        assertEquals("git clone --depth 9999 --no-single-branch U D", c(CloneOptions(history = "shallow", depth = 123456)))
        assertEquals("git clone --depth 1 --no-single-branch U D", c(CloneOptions(history = "shallow", depth = -4)))
        assertEquals("git clone --single-branch --branch main --depth 2 U D", c(CloneOptions(branches = "main", history = "shallow", depth = 2)))
        assertEquals("git clone --filter=blob:none U D", c(CloneOptions(blobs = false)))
        assertEquals("git clone --filter=tree:0 U D", c(CloneOptions(trees = false)))
        assertEquals("S/r", CloneOptions().destPath("r", "S", "W", "M")); assertEquals("W/r", CloneOptions(dest = "workspace").destPath("r", "S", "W", "M"))
        assertEquals("M/r.git", CloneOptions(dest = "mirror").destPath("r", "S", "W", "M")); assertEquals("M/r.git", CloneOptions(dest = "mirror").destPath("r.git", "S", "W", "M"))
    }

    @Test fun quotingAndManagePlan() {
        assertEquals("a-b_c.d/e:f@g=h+i,j{k}~l", GitStep.quote("a-b_c.d/e:f@g=h+i,j{k}~l")); assertEquals("\"\"", GitStep.quote(""))
        assertEquals("\"a b\"", GitStep.quote("a b")); assertEquals("\"a\\\\b\"", GitStep.quote("a\\b")); assertEquals("\"a\\\"b\"", GitStep.quote("a\"b")); assertEquals("\"it's\"", GitStep.quote("it's"))
        assertEquals("git fetch --depth 5 && git reflog expire --expire=now --all && git gc --prune=now", GitStep.preview(ManagePlan.keepDepth(5)))
        assertEquals("git fetch --depth 1 && git reflog expire --expire=now --all && git gc --prune=now", GitStep.preview(ManagePlan.keepDepth(0)))
        assertEquals("x || true", GitStep(listOf("x"), tolerant = true).line()); assertEquals("x", GitStep(listOf("x")).line())
    }
}

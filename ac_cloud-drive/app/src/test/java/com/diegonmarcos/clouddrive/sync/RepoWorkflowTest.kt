package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1289 Workflow: List Dirty / List Conflicts from the real porcelain v2 output, and the Solve Conflicts plan (never a push). */
class RepoWorkflowTest {
    private val z = "\u0000"
    private val merging = "# branch.head main$z# branch.upstream origin/main$z# branch.ab +1 -2$z" +
        "1 .M N... 100644 100644 100644 h h src/a.kt$z" + "1 A. N... 000000 100644 100644 h h added.txt$z" +
        "1 .D N... 100644 100644 000000 h h gone.txt$z" +
        "u UU N... 100644 100644 100644 100644 h h h both.txt$z" + "u AA N... 100644 100644 100644 100644 h h h z/added-both.txt$z" +
        "? untracked dir/f$z" + "\n@@STATE MERGING\n"

    @Test fun `List Dirty names every uncommitted path with its porcelain code`() {
        val d = RepoWorkflow.dirty(TermuxGitWire.parseStatus(merging))
        assertEquals(
            listOf("A " to "added.txt", "UU" to "both.txt", " D" to "gone.txt", " M" to "src/a.kt", "??" to "untracked dir/f", "UU" to "z/added-both.txt"),
            d.map { it.code to it.path },
        )
        assertTrue(RepoWorkflow.dirty(TermuxGitWire.parseStatus("# branch.head main$z\n@@STATE SAFE\n")).isEmpty())
    }

    @Test fun `List Conflicts names only the unmerged paths`() {
        assertEquals(listOf("both.txt", "z/added-both.txt"), RepoWorkflow.conflicts(TermuxGitWire.parseStatus(merging)))
        assertTrue(RepoWorkflow.conflicts(TermuxGitWire.parseStatus("1 .M N... 100644 100644 100644 h h a$z\n@@STATE SAFE\n")).isEmpty())
    }

    @Test fun `the repository state says what to continue`() {
        assertEquals(RepoWorkflow.InProgress.MERGE, RepoWorkflow.inProgress("MERGING"))
        assertEquals(RepoWorkflow.InProgress.MERGE, RepoWorkflow.inProgress("MERGING_RESOLVED"))
        assertEquals(RepoWorkflow.InProgress.REBASE, RepoWorkflow.inProgress("REBASING"))
        assertEquals(RepoWorkflow.InProgress.REBASE, RepoWorkflow.inProgress("REBASING_MERGE"))
        assertEquals(RepoWorkflow.InProgress.CHERRY_PICK, RepoWorkflow.inProgress("CHERRY_PICKING"))
        assertEquals(RepoWorkflow.InProgress.NONE, RepoWorkflow.inProgress("SAFE"))
    }

    @Test fun `Solve Conflicts plans ours, theirs and edited per file, then continues - and is not runnable with a file undecided`() {
        val files = listOf("both.txt", "z/added-both.txt", "c.md")
        val partial = RepoWorkflow.solvePlan(files, mapOf("both.txt" to RepoWorkflow.Choice.OURS), "MERGING")
        assertFalse(partial.ready); assertEquals(listOf("z/added-both.txt", "c.md"), partial.missing)
        assertFalse(partial.steps.any { it is RepoWorkflow.Step.Continue })

        val full = RepoWorkflow.solvePlan(files, mapOf("both.txt" to RepoWorkflow.Choice.OURS, "z/added-both.txt" to RepoWorkflow.Choice.THEIRS, "c.md" to RepoWorkflow.Choice.EDITED), "REBASING")
        assertTrue(full.ready)
        assertEquals(
            listOf(
                RepoWorkflow.Step.Resolve("both.txt", ConflictSide.OURS), RepoWorkflow.Step.Resolve("z/added-both.txt", ConflictSide.THEIRS),
                RepoWorkflow.Step.Stage("c.md"), RepoWorkflow.Step.Continue(RepoWorkflow.InProgress.REBASE),
            ),
            full.steps,
        )
        assertEquals(RepoWorkflow.Step.Continue(RepoWorkflow.InProgress.MERGE), RepoWorkflow.solvePlan(listOf("a"), mapOf("a" to RepoWorkflow.Choice.THEIRS), "MERGING").steps.last())
        assertFalse(RepoWorkflow.solvePlan(emptyList(), emptyMap(), "MERGING").ready)
    }

    @Test fun `the Solve Conflicts verbs never push`() {
        val dir = "/root/cloud-shared-store/git/r"
        val resolve = TermuxGitWire.plan("resolve", dir, JSONObject().put("path", "both.txt").put("side", "OURS"), null).first!!
        assertTrue(resolve.script.contains("checkout --ours -- 'both.txt'")); assertTrue(resolve.script.contains("add -- 'both.txt'"))
        val cont = TermuxGitWire.plan("rebase_continue", dir, JSONObject().put("name", "n").put("email", "e@x"), null).first!!
        assertTrue(cont.script.contains("GIT_EDITOR=true git \"\$(if [ -e") && cont.script.contains("rebase --continue")) // #1491 -C for a working tree, --git-dir for a bare repo, decided in the guest
        for (p in listOf(resolve, cont)) { assertFalse(p.script.contains("push")); assertFalse(p.network) }
        assertTrue("rebase_continue" in TermuxGitWire.OPS)
    }
}

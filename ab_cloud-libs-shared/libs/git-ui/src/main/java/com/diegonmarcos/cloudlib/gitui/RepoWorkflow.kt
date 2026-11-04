package com.diegonmarcos.cloudlib.gitui

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot

/**
 * #1289 WORKFLOW of a repository's menu, pure so the JVM suite holds it: List Dirty (the uncommitted files),
 * List Conflicts (the unmerged paths) and the Solve Conflicts PLAN. The plan resolves each conflicted file the
 * owner chose (keep ours / keep theirs / edited in an editor, then marked resolved), stages it, and continues the
 * merge or rebase in progress. It NEVER pushes: a push stays the owner's own tap.
 */
object RepoWorkflow {

    /** One uncommitted file: [code] is the two-letter porcelain code (index, worktree), e.g. "M ", " M", "??", "UU". */
    data class Dirty(val path: String, val code: String)

    private fun letter(c: GitChange?): Char = when (c) { GitChange.ADDED -> 'A'; GitChange.MODIFIED -> 'M'; GitChange.DELETED -> 'D'; GitChange.UNTRACKED -> '?'; null -> ' ' }

    /** Every uncommitted path (staged, unstaged, untracked; conflicted ones as "UU"), in path order. */
    fun dirty(s: GitStatusSnapshot): List<Dirty> = s.files.map { f ->
        val code = when {
            f.conflicting -> "UU"
            f.unstaged == GitChange.UNTRACKED || f.staged == GitChange.UNTRACKED -> "??"
            else -> "${letter(f.staged)}${letter(f.unstaged)}"
        }
        Dirty(f.path, code)
    }.sortedBy { it.path }

    /** The unmerged paths, in path order. */
    fun conflicts(s: GitStatusSnapshot): List<String> = s.files.filter { it.conflicting }.map { it.path }.distinct().sorted()

    /** What is in progress, from the snapshot's repository state (JGit's names and the terminal wire's). */
    enum class InProgress { NONE, MERGE, REBASE, CHERRY_PICK }

    fun inProgress(state: String): InProgress = when {
        state.startsWith("REBASING") || state.startsWith("REBASE") -> InProgress.REBASE
        state.startsWith("MERGING") -> InProgress.MERGE
        state.startsWith("CHERRY_PICKING") -> InProgress.CHERRY_PICK
        else -> InProgress.NONE
    }

    /** The owner's choice for one conflicted file. EDITED = they fixed it in an editor; the plan only stages it. */
    enum class Choice { OURS, THEIRS, EDITED }

    sealed class Step {
        /** Check out [side]'s version of [path] and stage it. */
        data class Resolve(val path: String, val side: ConflictSide) : Step()
        /** Stage [path] as the owner edited it. */
        data class Stage(val path: String) : Step()
        /** Continue the merge / cherry-pick (a commit) or the rebase; never a push. */
        data class Continue(val what: InProgress) : Step()
    }

    /** [missing] = conflicted files with no choice yet: the plan is not runnable until it is empty. */
    data class Plan(val steps: List<Step>, val missing: List<String>) {
        val ready: Boolean get() = missing.isEmpty() && steps.isNotEmpty()
    }

    fun solvePlan(conflicts: List<String>, choices: Map<String, Choice>, state: String): Plan {
        val missing = conflicts.filter { it !in choices }
        val steps = conflicts.mapNotNull { p ->
            when (choices[p]) {
                Choice.OURS -> Step.Resolve(p, ConflictSide.OURS)
                Choice.THEIRS -> Step.Resolve(p, ConflictSide.THEIRS)
                Choice.EDITED -> Step.Stage(p)
                null -> null
            }
        }
        val what = inProgress(state)
        val tail = if (missing.isEmpty() && steps.isNotEmpty() && what != InProgress.NONE) listOf(Step.Continue(what)) else emptyList()
        return Plan(steps + tail, missing)
    }
}

package com.diegonmarcos.cloudlib.gitcore

/**
 * #1934 THE ONE OPTION MODEL of the Cloud Drive Git control sheet. The sheet's Sync tab and Clone tab each edit ONE options object; this file turns it
 * into the list of git commands (argv, never a shell string) that the COMMAND PREVIEW prints and that [PlanExecutor] EXECUTES, so the preview and
 * the executed argv are the same list by construction (GitCommandPlanTest and SheetCommandMatrixTest pin that). Pure: no Android, no I/O.
 */
data class GitStep(val argv: List<String>, /** `|| true`: a non-zero answer does not stop the plan (nothing to commit). */ val tolerant: Boolean = false) {
    /** The shell-looking line of this step as the preview prints it. */
    fun line(): String = argv.joinToString(" ") { quote(it) } + if (tolerant) " || true" else ""

    companion object {
        fun quote(w: String): String = if (w.isNotEmpty() && w.all { it.isLetterOrDigit() || it in "-_./:@=+,{}~" }) w else "\"" + w.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        /** The preview of a whole plan: the steps joined by `&&`. */
        fun preview(steps: List<GitStep>): String = steps.joinToString(" && ") { it.line() }
    }
}

/** The Sync tab's choices (reference groups 1 to 5). Ids are the declared option values in build.json control_sheet. */
data class SyncOptions(
    val direction: String = "both",      // fetch | pull | push | both
    val strategy: String = "ff",         // ff | merge | rebase | reset (Force Pull)
    val winner: String = "none",         // none | remote | local
    val dirty: String = "none",          // none | autostash | autocommit
    val commitMessage: String = "chore: auto-sync",
    val scope: String = "current",       // current | all
    val prune: Boolean = false,
    val tags: Boolean = false,
    val submodules: Boolean = false,
    val forceLease: Boolean = false,
    val forceRaw: Boolean = false,
    val dryRun: Boolean = false,
) {
    val pulls get() = direction == "pull" || direction == "both"
    val pushes get() = direction == "push" || direction == "both"

    /** Which flags the current direction/strategy makes meaningful (the reference's isSyncFlagApplicable). */
    fun applicable(flag: String): Boolean = when (flag) {
        "prune" -> direction != "push"
        "forceLease", "forceRaw" -> pushes
        "submodules" -> strategy != "reset"
        else -> true
    }

    /** The options as the plan sees them: Force Pull drops Both and picks the remote with nothing dirty kept; inapplicable flags are off; force raw beats lease. */
    fun normalized(): SyncOptions {
        var o = this
        if (o.strategy == "reset" && o.direction == "both") o = o.copy(direction = "pull")
        if (o.strategy == "reset") o = o.copy(winner = "remote", dirty = "none")
        if (!o.applicable("prune")) o = o.copy(prune = false)
        if (!o.applicable("forceLease")) o = o.copy(forceLease = false, forceRaw = false)
        if (!o.applicable("submodules")) o = o.copy(submodules = false)
        if (o.forceRaw) o = o.copy(forceLease = false)
        return o
    }

    /** True when the plan runs something that overwrites work (Force Pull, Force Push without lease). */
    fun destructive(): Boolean { val o = normalized(); return !o.dryRun && ((o.pulls && o.strategy == "reset") || (o.pushes && o.forceRaw)) }

    /** The engine cannot do this combination: the reason, or null. Sync Now is disabled with it. */
    fun unsupported(): String? {
        val o = normalized()
        if (o.submodules) return "submodules: the in-process engine (JGit) has no recursive submodule fetch or push"
        if (o.pulls && o.strategy == "rebase" && o.winner != "none") return "winner with rebase: a rebase has no per-file ours/theirs pick in the engine; use Merge"
        return null
    }

    /** The plan: reference build rules, as argv. */
    fun steps(): List<GitStep> {
        val o = normalized()
        val out = mutableListOf<GitStep>()
        if (o.dirty == "autocommit" && o.direction != "fetch" && o.strategy != "reset" && !o.dryRun) {   // a dry run changes nothing, the commit included
            out += GitStep(listOf("git", "add", "-A"))
            out += GitStep(listOf("git", "commit", "-m", o.commitMessage), tolerant = true)
        }
        fun MutableList<String>.f(on: Boolean, vararg w: String) { if (on) addAll(w) }
        if (o.direction == "fetch") {
            out += GitStep(mutableListOf("git", "fetch").apply { f(o.scope == "all", "--all"); f(o.prune, "--prune"); f(o.tags, "--tags"); f(o.submodules, "--recurse-submodules"); f(o.dryRun, "--dry-run") })
        }
        if (o.pulls) {
            if (o.strategy == "reset") {
                out += GitStep(mutableListOf("git", "fetch").apply { f(o.scope == "all", "--all"); f(o.prune, "--prune"); f(o.dryRun, "--dry-run") })
                out += if (o.dryRun) GitStep(listOf("git", "status", "-sb")) else GitStep(listOf("git", "reset", "--hard", "@{u}"))
            } else {
                out += GitStep(mutableListOf("git", "pull").apply {
                    when (o.strategy) { "ff" -> add("--ff-only"); "merge" -> add("--no-rebase"); "rebase" -> add("--rebase") }
                    if (o.strategy != "ff") { f(o.winner == "remote", "-X", "theirs"); f(o.winner == "local", "-X", "ours") }
                    f(o.scope == "all", "--all"); f(o.prune, "--prune"); f(o.dirty == "autostash", "--autostash")
                    f(o.tags, "--tags"); f(o.submodules, "--recurse-submodules"); f(o.dryRun, "--dry-run")
                })
            }
        }
        if (o.pushes) {
            out += GitStep(mutableListOf("git", "push").apply {
                f(o.scope == "all", "--all"); f(o.tags, "--tags"); f(o.submodules, "--recurse-submodules=on-demand")
                if (o.forceRaw) add("--force") else if (o.forceLease) add("--force-with-lease")
                f(o.dryRun, "--dry-run")
            })
        }
        return out
    }

    /** Safety badge of the sheet: preview | destructive | caution | read_only | safe. */
    fun badge(): String { val o = normalized(); return when {
        o.dryRun -> "preview"; o.destructive() -> "destructive"; o.pushes && o.forceLease -> "caution"; o.direction == "fetch" -> "read_only"; else -> "safe" } }
}

/** A Quick Sync preset: a named set of the options above. */
data class SyncPreset(val id: String, val label: String, val options: SyncOptions)

/** The Clone tab's choices (reference groups: destination, workspace, branches, history depth, data included). */
data class CloneOptions(
    val dest: String = "store",          // store | workspace | mirror
    val workspace: String = "working",   // working | bare
    val branches: String = "all",        // all | main
    val branch: String = "main",
    val history: String = "deep",        // deep | shallow
    val depth: Int = 3,
    val trees: Boolean = true,
    val blobs: Boolean = true,
) {
    /** The partial-clone filter the Data Included group means: blobs off = blob:none, trees off = tree:0, else none. */
    val filter: String get() = if (!trees) "tree:0" else if (!blobs) "blob:none" else ""

    /** The profile name the Clone button wears. */
    fun profileName(): String {
        val p = mutableListOf<String>()
        if (workspace == "bare") p += "Bare"
        if (branches == "main") p += "Main"
        if (history == "shallow") p += "Depth ${depth.coerceIn(1, 9999)}"
        if (filter == "blob:none") p += "Blobless" else if (filter == "tree:0") p += "Treeless" else if (history == "deep") p += "Full"
        return p.joinToString(" ")
    }

    /** The clone command for [url] into [destPath]. */
    fun step(url: String, destPath: String): GitStep = GitStep(mutableListOf("git", "clone").apply {
        if (workspace == "bare") add("--bare")
        if (branches == "main") { add("--single-branch"); add("--branch"); add(branch.trim().ifBlank { "main" }) }
        if (history == "shallow") { add("--depth"); add(depth.coerceIn(1, 9999).toString()); if (branches == "all") add("--no-single-branch") }   // git: --depth alone implies --single-branch
        if (filter.isNotBlank()) add("--filter=$filter")
        add(url); add(destPath)
    })

    /** The destination folder of [name] under the three roots of the Clone Destination group. */
    fun destPath(name: String, storeRoot: String, workspaceRoot: String, mirrorRoot: String): String = when (dest) {
        "mirror" -> "$mirrorRoot/${name.removeSuffix(".git")}.git"
        "workspace" -> "$workspaceRoot/$name"
        else -> "$storeRoot/$name"
    }
}

data class ClonePreset(val id: String, val label: String, val options: CloneOptions)

/** #1934 the Manage tab's commands as argv (the same list the host runs through git in cld.termux and the tests run through the git binary). */
object ManagePlan {
    /** Repo Clean: keep [depth] commits locally, expire the reflog, gc. The remote is never written. */
    fun keepDepth(depth: Int): List<GitStep> = listOf(
        GitStep(listOf("git", "fetch", "--depth", depth.coerceAtLeast(1).toString())),
        GitStep(listOf("git", "reflog", "expire", "--expire=now", "--all")),
        GitStep(listOf("git", "gc", "--prune=now")),
    )
}

package com.diegonmarcos.cloudlib.gitcore

/**
 * #1491 ONE clone mode, as the app declares it in data (Cloud Drive build.json ui.sync.git.clone_modes): depth, bare,
 * single branch, partial-clone filter. Nothing here names a mode; the app's data does.
 *
 * Engines: JGit runs every mode but a filter one ([needsGitBinary]: CloneCommand has no partial-clone filter), which runs the
 * git binary (the app's cld.termux path) from [args]. [branch] blank with [singleBranch] = the remote's default branch.
 */
data class CloneMode(
    val id: String,
    val label: String = id,
    val depth: Int = 0,
    val bare: Boolean = false,
    val singleBranch: Boolean = false,
    val filter: String = "",
) {
    /** JGit cannot filter objects on the wire: a filter mode needs the git binary. */
    val needsGitBinary: Boolean get() = filter.isNotBlank()

    /** The folder name of a clone of [name] in this mode (a bare repository is `<name>.git`). */
    fun folderName(name: String): String = if (bare) name.removeSuffix(".git") + ".git" else name

    /**
     * The words after `git` for this mode. [branch] is inserted as given (already shell-ready when [q] quotes, e.g. `"$B"`);
     * null leaves `--branch` out (git then follows the remote HEAD, the same single branch).
     */
    fun args(url: String, dest: String, branch: String? = null, q: (String) -> String = { it }): String =
        listOfNotNull(
            "clone",
            "--bare".takeIf { bare },
            "--depth $depth".takeIf { depth > 0 },
            "--single-branch".takeIf { singleBranch },
            "--branch $branch".takeIf { singleBranch && !branch.isNullOrBlank() },
            "--filter=$filter".takeIf { filter.isNotBlank() },
            q(url), q(dest),
        ).joinToString(" ")
}

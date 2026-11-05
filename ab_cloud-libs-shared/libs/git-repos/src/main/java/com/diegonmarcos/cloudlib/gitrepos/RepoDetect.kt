package com.diegonmarcos.cloudlib.gitrepos

import java.io.File

/**
 * #1491 ONE repo detection for every folder: a repo folder is a working tree (it has a `.git` entry) or a bare repository
 * (a `*.git` folder holding HEAD and objects). Both are the same [Repo] in the same list; bare is one attribute, never a
 * separate list, table or category. [git] builds the arguments of one git command for either kind: `-C <dir>` for a working
 * tree, `--git-dir <dir>` for a bare repository (status, fetch, pull, worktree add, open all go through it).
 */
object RepoDetect {

    data class Repo(val dir: File, val bare: Boolean) {
        val name: String get() = dir.name
        /** The repository's own name: a bare folder `x.git` is the repository `x`. */
        val repoName: String get() = if (bare) dir.name.removeSuffix(".git") else dir.name
        /** The git dir: `<dir>/.git` for a working tree (a gitfile is followed by git itself), the folder for a bare repo. */
        val gitDir: File get() = if (bare) dir else File(dir, ".git")
    }

    /** A bare repository folder: named `*.git`, holding HEAD and objects, with no `.git` entry of its own. */
    fun isBare(dir: File): Boolean =
        dir.name.endsWith(".git") && File(dir, "HEAD").isFile && File(dir, "objects").isDirectory && !File(dir, ".git").exists()

    /** The repo [dir] is, or null when it is not one. */
    fun detect(dir: File): Repo? = when {
        !dir.isDirectory -> null
        File(dir, ".git").exists() -> Repo(dir, false)
        isBare(dir) -> Repo(dir, true)
        else -> null
    }

    /** The repos among the [folders], ONE list, in the given order (working trees and bare repositories side by side). */
    fun list(folders: List<File>): List<Repo> = folders.mapNotNull { detect(it) }

    /** The repos among the direct children of [root], ONE list sorted by repository name. */
    fun scan(root: File): List<Repo> = list(root.listFiles().orEmpty().toList()).sortedBy { it.repoName.lowercase() }

    /** `-C <dir>` or `--git-dir <dir>`: the words that point `git` at [repo]. */
    fun git(repo: Repo, vararg rest: String): List<String> =
        (if (repo.bare) listOf("--git-dir", repo.dir.path) else listOf("-C", repo.dir.path)) + rest

    /** The same choice inside a shell script, decided where the folder is (the store may only be visible there): `"$(...)" <dir>`. [q] quotes a word. */
    fun shellPrefix(dir: String, q: (String) -> String): String =
        "\"\$(if [ -e ${q("$dir/.git")} ]; then echo -C; else echo --git-dir; fi)\" ${q(dir)}"
}

package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File

/**
 * THE CLONE COUNT IS THE DISK'S. The registry (registry.json) is a persisted list of every folder ever registered; a folder the
 * owner deleted stays in it, so counting registry rows printed a stale "18 cloned" over an empty git folder. A row is a clone only
 * while its folder is one ([isClone]); a declared repository without a clone is "not cloned", never counted as cloned.
 * Pure over the predicate, JVM-tested (CloneTruthTest).
 */
object CloneTruth {
    /** [repos] whose folder is a clone right now; blocking when [isClone] touches disk or cld.termux: call on IO. */
    fun live(repos: List<ManagedRepo>, isClone: (File) -> Boolean): List<ManagedRepo> = repos.filter { runCatching { isClone(File(it.path)) }.getOrDefault(false) }

    /** A working tree (a valid `.git` dir with HEAD, or a `.git` file for a worktree/submodule), a `.git`-only clone, or a bare mirror (HEAD + objects): the on-disk shape of a clone. An empty `.git` folder is NOT one. */
    fun isCloneDir(dir: File): Boolean = dir.isDirectory && (isGitDir(File(dir, ".git")) || isGitDir(dir))

    private fun isGitDir(g: File): Boolean = g.isFile || (g.isDirectory && File(g, "HEAD").isFile && File(g, "objects").isDirectory)

    /** Every immediate subfolder of [root] that is a clone right now (the real scan of the clones folder). */
    fun scan(root: File): List<File> = (root.listFiles() ?: emptyArray()).filter { isCloneDir(it) }.sortedBy { it.name }
}

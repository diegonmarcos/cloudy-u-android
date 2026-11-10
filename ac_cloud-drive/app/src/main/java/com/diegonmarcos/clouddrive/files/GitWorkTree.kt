package com.diegonmarcos.clouddrive.files

import java.io.File

/**
 * Whether an item sits inside a git working tree, and which directory is its root.
 * Pure java.io: walk up from the item's folder for a `.git` entry (a directory for a
 * normal clone, a file for a worktree/submodule gitlink). The per-file dropdown's Git
 * group shows only when [rootOf] finds one, so a folder with no repo never offers it.
 */
object GitWorkTree {

    /** The nearest ancestor (inclusive of a directory item itself) that holds a `.git`, or null. */
    fun rootOf(path: String): File? {
        val start = File(path).let { if (it.isDirectory) it else it.parentFile } ?: return null
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** Convenience: is [path] under a git working tree. */
    fun isUnderGit(path: String): Boolean = rootOf(path) != null
}

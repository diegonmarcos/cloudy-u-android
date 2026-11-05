package com.diegonmarcos.cloudlib.gitrepos

import java.io.File

/**
 * #608 what a clone says about itself WITHOUT git: the hooks installed in it, the
 * GitHub Actions workflows it carries, and how much of the phone it occupies.
 *
 * Deliberately not on GitEngine: none of it is a git verb — they are three reads of
 * the working tree — and keeping them pure Kotlin over a [File] means the JVM suite
 * (GitPageTest) exercises the exact code the row renders, against real directories,
 * instead of a mock. Nothing here writes.
 */
object GitRepoScan {

    /** The hooks that are actually installed: `.git/hooks` minus git's own `*.sample`. */
    fun hooks(repo: File): List<String> {
        // #1491 a bare repository keeps its hooks in the folder itself (RepoDetect.gitDir), a working tree under .git
        val dir = File(RepoDetect.detect(repo)?.gitDir ?: File(repo, ".git"), "hooks")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".sample") }
            .map { it.name }
            .sorted()
    }

    /** The workflows GitHub would run: the .yml and .yaml files of `.github/workflows`. */
    fun workflows(repo: File): List<String> {
        val dir = File(repo, ".github/workflows")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .map { it.name }
            .sorted()
    }

    /**
     * Bytes, files and folders of the whole clone, and of its `.git` alone — the row
     * shows both because "this repository is 400 MB" is a different fact from "its
     * history is 380 MB of it", and the second is the one a shallow re-clone fixes.
     * A symlink is counted as the entry it is, never followed: a link back up the tree
     * would otherwise walk forever.
     */
    data class Size(val bytes: Long, val files: Int, val folders: Int, val gitBytes: Long)

    fun size(repo: File): Size {
        if (!repo.isDirectory) return Size(0, 0, 0, 0)
        var bytes = 0L
        var files = 0
        var folders = 0
        var gitBytes = 0L
        val gitDir = (RepoDetect.detect(repo)?.gitDir ?: File(repo, ".git")).absolutePath
        val stack = ArrayDeque<File>()
        stack.addLast(repo)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                val inGit = child.absolutePath == gitDir || child.absolutePath.startsWith("$gitDir/")
                if (child.isDirectory) {
                    folders++
                    // A directory symlink is counted and NOT descended into.
                    if (!java.nio.file.Files.isSymbolicLink(child.toPath())) stack.addLast(child)
                } else {
                    files++
                    val len = child.length()
                    bytes += len
                    if (inGit) gitBytes += len
                }
            }
        }
        return Size(bytes, files, folders, gitBytes)
    }

    /** `12.3 MB` / `900 kB` / `12 B` — one formatter, so two rows never disagree. */
    fun humanBytes(bytes: Long): String {
        val units = listOf("B", "kB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
        return if (unit == 0) "$bytes ${units[0]}" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
    }
}

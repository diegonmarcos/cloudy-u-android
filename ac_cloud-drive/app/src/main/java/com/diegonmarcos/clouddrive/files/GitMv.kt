package com.diegonmarcos.clouddrive.files

import android.content.Context
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import java.io.File

/**
 * #1644 `git mv` for the Explorer: rename or move an item that lives in a git working tree and
 * leave the move STAGED (old path removed from the index, new path added), exactly what
 * `git mv` leaves. The git part goes through libs:git-sync's [GitEngine.stage] (libs:git-core
 * underneath), the one git path the app has; this object only plans the paths ([plan], pure and
 * JVM-testable) and moves the bytes. Same-repository only: a move out of the work tree is not a
 * `git mv` and is refused with a sentence instead of silently untracking the file.
 */
object GitMv {

    /** One planned move: [oldRel]/[newRel] are work-tree-relative with `/` separators, the form git stages. */
    data class Step(val root: File, val source: File, val target: File, val oldRel: String, val newRel: String)

    class Refused(message: String) : Exception(message)

    private fun rel(root: File, f: File): String = f.absoluteFile.relativeTo(root.absoluteFile).path.replace(File.separatorChar, '/')

    /** Rename [source] to [newName] inside its own folder (`git mv a b`). */
    fun planRename(source: File, newName: String): Step {
        val name = FileOps.sanitize(newName) ?: throw Refused("not a valid name: $newName")
        return plan(source, File(source.parentFile ?: throw Refused("no parent folder"), name))
    }

    /** Move [source] into the folder [destDir] keeping its name (`git mv a dir/`). */
    fun planMove(source: File, destDir: File): Step = plan(source, File(destDir, source.name))

    private fun plan(source: File, target: File): Step {
        val root = GitWorkTree.rootOf(source.absolutePath) ?: throw Refused("${source.name} is not inside a git repository")
        val targetRoot = GitWorkTree.rootOf(target.parentFile?.absolutePath ?: "")
        if (targetRoot?.absoluteFile != root.absoluteFile) throw Refused("git mv stays inside one repository: ${root.name}")
        if (target.exists()) throw Refused("${target.name} already exists")
        if (target.absoluteFile.path.startsWith(source.absoluteFile.path + File.separator)) throw Refused("cannot move a folder into itself")
        val oldRel = rel(root, source)
        if (oldRel.isEmpty() || oldRel == ".git" || oldRel.startsWith(".git/")) throw Refused("refusing to move $oldRel")
        return Step(root, source.absoluteFile, target.absoluteFile, oldRel, rel(root, target))
    }

    /** Do the planned moves and stage them (blocking; call off the main thread). Returns how many were moved. */
    fun apply(ctx: Context, steps: List<Step>): Int {
        var n = 0
        steps.groupBy { it.root }.forEach { (root, group) ->
            val staged = mutableListOf<String>()
            for (s in group) {
                if (!s.source.renameTo(s.target)) throw Refused("could not move ${s.source.name}")
                staged += s.oldRel; staged += s.newRel; n++
            }
            GitEngine(ctx, root).use { it.stage(staged) }
        }
        return n
    }
}

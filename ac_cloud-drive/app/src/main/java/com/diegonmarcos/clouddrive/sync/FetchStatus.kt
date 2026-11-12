package com.diegonmarcos.clouddrive.sync

/**
 * #1679 FETCH STATUS: the words of a read-only `git fetch --dry-run -v` + `git remote show origin` pass, reduced to one line.
 * Pure (no git, no I/O) so the parse is unit-tested; the commands themselves live in [StoreGit.fetchStatusScript].
 */
object FetchStatus {
    /** The two read-only commands, in the order they run (the Refresh menu's Fetch status and the sheet's Fetch status). */
    val COMMANDS = listOf("git fetch --dry-run -v", "git remote show origin")

    /** Ref lines of the dry run that would change something (`[up to date]` lines bring nothing). */
    fun incoming(text: String): List<String> = text.lines().map { it.trim() }
        .filter { "->" in it && !it.contains("[up to date]") && !it.startsWith("$") }

    /** `HEAD branch: main` of `git remote show origin`, or null. */
    fun headBranch(text: String): String? = text.lines().map { it.trim() }
        .firstOrNull { it.startsWith("HEAD branch:") }?.substringAfter(":")?.trim()?.ifBlank { null }

    /** The tracking verdicts of `git remote show origin` ("main pushes to main (up to date)", "(local out of date)", ...). */
    fun tracking(text: String): List<String> = text.lines().map { it.trim() }.filter { " pushes to " in it || " merges with remote " in it }

    fun summary(text: String): String {
        val inc = incoming(text)
        val head = headBranch(text)
        val stale = tracking(text).count { it.contains("out of date") }
        return buildString {
            append(if (inc.isEmpty()) "fetch would bring nothing" else "fetch would update ${inc.size} ref(s)")
            if (head != null) append(" · remote HEAD $head")
            if (stale > 0) append(" · $stale branch(es) out of date")
        }
    }
}

package com.diegonmarcos.clouddrive.sync

/**
 * #1452 the Status Message column: the ONE line of text a table row says besides its status icons (the clone is gone, a probe failed,
 * a conflict, an unfinished merge, a folder command's outcome). It used to wrap under the repository name and under the icons, which
 * made two-line rows; now it is a cell of its own, one line with an ellipsis. Pure: the words are passed in (JVM-tested).
 */
object RepoMessage {
    data class Words(val gone: String, val unchecked: String, val conflicts: (Int) -> String, val error: (String?) -> String)
    data class Say(val text: String, val problem: Boolean)

    /** What a repository row says: [note] for a repository with no clone, else what the local read found. */
    fun of(cloned: Boolean, g: GitSyncCoordinator.Glance?, note: String, w: Words): Say = when {
        !cloned -> Say(note, false)
        g != null && g.gone -> Say(w.gone, true)
        g == null || !g.read -> Say(w.unchecked, false)
        g.error != null -> Say(w.error(g.error), true)
        else -> {
            val parts = listOfNotNull(
                if (g.conflicts > 0) w.conflicts(g.conflicts) else null,
                if (g.repositoryState != "SAFE") g.repositoryState else null,
            )
            Say(parts.joinToString(" · "), g.conflicts > 0)
        }
    }

    /** A folder row (duplicates, parentless, unlisted, bare, debris): the last command's outcome on it, else a local problem, else the table's own [subtitle]. */
    fun ofFolder(outcome: String?, g: GitSyncCoordinator.Glance?, subtitle: String, w: Words): Say {
        if (outcome != null) return Say(outcome.replace('\n', ' '), outcome.startsWith("failed") || outcome.startsWith("skipped"))
        val local = if (g == null) Say("", false) else of(true, g, "", w).takeIf { g.read || g.gone }
        return if (local != null && local.text.isNotBlank() && local.problem) local else Say(subtitle, false)
    }
}
